/**
 * **探针**：Milvus 孤儿向量清扫（`T-O27` · `S7-M06-E`）。
 *
 * <h3>它证的是什么</h3>
 * <p>"向量库里有、而 `sys_kb` 里已经没有"的那些点。这一族的存在形状与
 * `sys_chunk` 那种"删了行留下分片"不同：向量的归属**只有 `kb_id` 一个维度**，
 * 库行一没，它们就**谁也认领不了** —— 检索却照样捞得出来
 * （幽灵数据：查得到、点进去 404）。
 *
 * <h3>🔴 判据以「向量条数」为准，不看分区数</h3>
 * <p>分区是**容器**：`entities/delete` 删点不删容器。所以"分区都在"推不出"向量都在"，
 * "没有孤儿分区"更推不出"没有孤儿向量"。本脚本的每条断言都落在 `count(*)` 上，
 * 分区名只作为附加信息打印。
 *
 * <h3>🔴 两侧都**人工造样本**，而不是"扫现有数据"</h3>
 * <p>只扫现有数据的话，一个干净环境会让脚本恒绿 —— 而它绿的可能是"扫描根本没工作"。
 * 所以本脚本手工插**两个**向量点（Milvus `entities/insert`）：
 * <ul>
 *   <li><b>孤儿侧</b>：`kb_id = 999999999`（库里不存在这个库）—— 必须被清掉；</li>
 *   <li><b>对照侧</b>：一个**真实存在**的 `kb_id` —— 必须**一条不少**。</li>
 * </ul>
 * 只看"孤儿没了"是不够的：一个"把什么都删掉"的实现也能满足那一半。
 *
 * <h3>⚠️ Milvus 的写入可见性有延迟（本脚本实测踩到）</h3>
 * <p>插入之后**立刻** `count(*)` 可能得 0（与删除同一条：见 `milvusVectorCount` 的注释）。
 * 所以插完要**轮询等到可见**再断言 —— 第一版没轮询，得到的是
 * "人工孤儿已插入且可见 … 实查 count=0" 这种**假失败**。
 *
 * <h3>它不需要后端在跑</h3>
 * <p>只连 MySQL（走 `docker exec kb-mysql`）与 Milvus REST，不调业务接口。
 *
 * 用法：`node ai_knowledge_app/scripts/probe-orphan-vectors.mjs`
 */
import {
  existingKbIds, findOrphanVectors, milvusCountOfKb, milvusDeleteVectors, milvusPost,
  mysqlScalar, sweepOrphanVectors,
} from './lib/probe-teardown.mjs'

/** 人工孤儿的 kb_id：库里**不存在**这个库（`sys_kb` 的自增 id 不可能到十亿）。 */
const FAKE_KB_ID = 999999999

const STAMP = Date.now()
const ORPHAN_VECTOR_ID = `orphan-probe-${STAMP}`
const LIVE_VECTOR_ID = `live-probe-${STAMP}`

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

let failures = 0
function check(label, ok, detail = '') {
  console.log(`${ok ? '✓' : '✗'} ${label}${detail ? ` —— ${detail}` : ''}`)
  if (!ok) failures++
}

/** 手工插一个向量点（只有 kb_id 是真的，别的一律是探针值）。 */
function insertVector(kbId, id) {
  const row = {
    id,
    embedding: new Array(1024).fill(0),
    kb_id: Number(kbId),
    doc_id: Number(kbId),
    chunk_type: 'PARAGRAPH',
    secret_level: 'INTERNAL',
    content: 'probe vector',
    title_path: '',
    bm25_text: 'probe vector',
  }
  const r = milvusPost('entities/insert', { collectionName: 'knowledge_vectors', data: [row] })
  if (r?.code !== 0) {
    throw new Error(`探针向量插入失败（id=${id}）：${JSON.stringify(r).slice(0, 300)}`)
  }
}

/**
 * 等某个 kb 的向量条数**稳定到**期望值（Milvus 的写入/删除可见性都有几秒延迟）。
 *
 * @returns 最后一次实查的值（等不到就返回它，由调用方断言）
 */
async function waitCount(kbId, expected, tries = 8) {
  let n = -1
  for (let i = 0; i < tries; i++) {
    n = milvusCountOfKb(kbId)
    if (n === expected) return n
    if (i < tries - 1) await sleep(1500)
  }
  return n
}

/** 挑一个**真实存在**的库（对照侧要用它）。 */
function pickLiveKbId() {
  const ids = [...existingKbIds()].sort((a, b) => a - b)
  return ids.length > 0 ? ids[0] : null
}

console.log('=== T-O27 · Milvus 孤儿向量清扫 ===\n')

// ---- 0) 起手：把上一轮可能残留的探针向量先清掉，免得数字对不上 ----
milvusDeleteVectors([FAKE_KB_ID])
const start = findOrphanVectors()
console.log(`[起手] kb_id 扫描=${start.truncated ? '翻页未完整（结论不可信）' : '完整'}`
  + ` · 现存孤儿 ${start.orphans.length} 个（向量 ${start.totalBefore} 条）\n`)

// ---- 1) 人工造孤儿，并**等到它可见**再断言 ----
insertVector(FAKE_KB_ID, ORPHAN_VECTOR_ID)
const orphanVisible = await waitCount(FAKE_KB_ID, 1)
check(`人工孤儿已插入且可见（kb_id=${FAKE_KB_ID}，id=${ORPHAN_VECTOR_ID}）`,
  orphanVisible === 1, `轮询后实查 count=${orphanVisible}`)
check('人工孤儿的 kb_id 在 MySQL 里确实不存在',
  !existingKbIds().has(FAKE_KB_ID), 'sys_kb 查无此库')

// ---- 2) 人工给一个**真库**加一条向量，作为"不误伤"的对照 ----
const liveKbId = pickLiveKbId()
let liveBefore = -1
if (liveKbId === null) {
  check('存在可用于对照的真库', false, 'sys_kb 是空的 —— 本轮证不了"没误伤"那一半')
} else {
  const base = milvusCountOfKb(liveKbId)
  insertVector(liveKbId, LIVE_VECTOR_ID)
  liveBefore = await waitCount(liveKbId, base + 1)
  check(`对照真库 kb_id=${liveKbId} 已插入一条探针向量且可见`, liveBefore === base + 1,
    `插入前 ${base} → 插入后 ${liveBefore}（期望 ${base + 1}）`)
}

// ---- 3) 清扫 ----
const before = findOrphanVectors()
console.log(`\n[清扫前] 孤儿 ${before.orphans.length} 个：`
  + `${before.orphans.map((id) => `${id}(${before.before[id]} 条)`).join(', ') || '—'}`)
console.log(`[清扫前] 孤儿向量合计 ${before.totalBefore} 条\n`)

const report = await sweepOrphanVectors()

console.log(`[清扫后] 成功清掉 ${report.swept.length} 个（${report.swept.join(', ') || '—'}）`
  + ` · 失败 ${report.failed.length} 个${report.failed.length ? `（${report.failed.join(', ')}）` : ''}`)
console.log(`[清扫后] 孤儿向量合计 ${report.totalAfter} 条\n`)

check('人工孤儿已被清掉（**独立观测点**：重新 count，不信 delete 的返回）',
  (await waitCount(FAKE_KB_ID, 0)) === 0, `实查 count=${milvusCountOfKb(FAKE_KB_ID)}`)
check('所有已识别的孤儿都清干净了', report.failed.length === 0 && report.totalAfter === 0,
  `残留 ${report.totalAfter} 条`)

if (liveKbId !== null) {
  const after = milvusCountOfKb(liveKbId)
  check(`对照真库 kb_id=${liveKbId} 的向量**一条没少**（清扫没有误伤）`, after === liveBefore,
    `清扫前 ${liveBefore} → 清扫后 ${after}`)
}

// ---- 4) 复扫：已经没孤儿了（幂等） ----
const again = findOrphanVectors()
check('复扫：已是 0 个孤儿（幂等，不会越删越乱）', again.orphans.length === 0,
  `实查 ${again.orphans.length} 个`)

// ---- 5) 收尾：把对照侧那条探针向量删掉（它是我们自己造的，不该留在库里） ----
if (liveKbId !== null) {
  milvusPost('entities/delete', {
    collectionName: 'knowledge_vectors',
    filter: `id == "${LIVE_VECTOR_ID}"`,
  })
  const left = await waitCount(liveKbId, liveBefore - 1)
  check('对照侧的探针向量已自行清掉（不留痕）', left === liveBefore - 1,
    `收尾后 ${left}（期望 ${liveBefore - 1}）`)
}

// ---- 6) 旁证：分区名（**只打印不作判据**） ----
console.log(`\n[旁证] 现存知识库 ${mysqlScalar('select count(*) from sys_kb')} 个`
  + '（分区数不作判据，理由见脚本头注）')

console.log(`\n${failures === 0 ? '✓ 全部通过' : `✗ ${failures} 条断言失败`}`)
process.exit(failures === 0 ? 0 : 1)
