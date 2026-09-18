/**
 * **探针**：「清空索引」与「在跑的解析作业」之间的竞态（`S6-M05-C`）。
 *
 * <h3>它已经证过一件事，现在守着一件修复</h3>
 * <p>最初它是**待证伪**的 —— 机制由代码阅读推出，写在这里当假设。实测**证实**了：
 * <pre>
 *   [清空前]     分区=[kb1007378_g1] 向量=4   10910:READY|代=1   10911:PARSING|代=0
 *   DELETE → 200 {"cleared":true}
 *   [清空后立刻]  分区=[—]            向量=0
 *   [B 终态后]   分区=[kb1007378_g1] 向量=4   10911:READY|1
 *                 └─ **分区名逐字相同**，被在途作业重建了出来
 * </pre>
 * 即：**用户拿到 `{"cleared":true}`，而清空被静默撤销**。
 *
 * <p>修复（`S6-M05-C`，两半）之后，本探针**转为回归探针**：它现在该打印
 * 「向量没回来 + 出现新代空分区 + 在途文档未被标 READY」。实测：
 * <pre>
 *   [清空后立刻]  分区=[kb1007381_g2] 向量=0   10916:INDEXING|0   10917:PARSING|0
 *   [B 终态后]   分区=[kb1007381_g2] 向量=0   10917:INDEXING|0|**152 块**
 *   后端日志：向量写入放弃（索引代已变更，文档留在 INDEXING）：docId=10917
 *            原因=本作业持有第 1 代，当前第 2 代
 * </pre>
 *
 * <h3>⚠️ 它**会**在两种情况下给出误导性的"通过"</h3>
 * <ol>
 *   <li><b>B 跑得太快</b>：清空时已经没有在途作业 ⇒ 只验到修复的第②半。
 *       脚本会打印 `B 开跑时的状态` 与「本轮真的有在途作业」标记 —— **看那一行**。</li>
 *   <li><b>B 跑得太慢</b>：300s 还没到写入点 ⇒ 也一样只验到第②半。
 *       所以**必须**配合后端日志里那条 `向量写入放弃` 才算完整证据。</li>
 * </ol>
 * 用法：`node ai_knowledge_app/scripts/probe-clear-index-race.mjs`（需后端在跑）。
 */
import { spawnSync } from 'node:child_process'
import {
  mysqlScalar, milvusPartitionNames, milvusVectorCount, teardownKbProbe,
} from './lib/probe-teardown.mjs'

const BASE = 'http://127.0.0.1:8080'
const stamp = Date.now()
const CODE = `kb_zzrace${stamp}`
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

let token = ''
async function api(path, { method = 'GET', body, raw } = {}) {
  const headers = token ? { Authorization: `Bearer ${token}` } : {}
  if (body && !raw) headers['Content-Type'] = 'application/json'
  const resp = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: raw ? body : body ? JSON.stringify(body) : undefined,
  })
  const text = await resp.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* 非 JSON */ }
  return { status: resp.status, json, text }
}

function docRow(docId) {
  return mysqlScalar(
    `select concat_ws('|', status, coalesce(index_generation,-1), coalesce(chunk_count,0)) `
    + `from sys_document where id = ${docId};`,
  )
}

function partitionsOf(kbId) {
  return milvusPartitionNames().filter((p) => p.startsWith(`kb${kbId}_g`))
}

async function snapshot(label, kbId, docIds) {
  const parts = partitionsOf(kbId)
  const vec = await milvusVectorCount([kbId])
  const docs = docIds.map((d) => `${d}:${docRow(d)}`).join('  ')
  console.log(`  [${label}] 分区=[${parts.join(',') || '—'}] 向量=${vec} 文档=${docs}`)
  return { parts, vec }
}

function upload(kbId, name, text) {
  const form = new FormData()
  form.append('file', new Blob([text], { type: 'text/markdown' }), name)
  return fetch(`${BASE}/api/kb/${kbId}/doc/upload`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
    body: form,
  }).then(async (r) => {
    const t = await r.text()
    try { return JSON.parse(t).data } catch { throw new Error(`上传失败 HTTP ${r.status}：${t.slice(0, 200)}`) }
  })
}

async function waitTerminal(docId, timeoutMs) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeoutMs) {
    if (['READY', 'FAILED'].includes(docRow(docId).split('|')[0])) return
    await sleep(1000)
  }
}

const cleanup = () => teardownKbProbe(CODE)

console.log(`\n清空索引竞态探针 —— ${CODE}\n`)
await cleanup()

const login = await api('/api/auth/login', {
  method: 'POST',
  body: { account: 'admin', password: 'Admin@123', remember: true },
})
token = login.json?.data?.accessToken
if (!token) throw new Error(`登录失败：${login.text.slice(0, 200)}`)

const created = await api('/api/kb', {
  method: 'POST',
  body: {
    name: '清空索引竞态探针库', code: CODE, deptId: 1,
    visibleRoles: ['USER', 'KB_ADMIN'], secretLevel: 'INTERNAL', businessDomain: '研发 / 平台',
  },
})
const kbId = Number(created.json?.data?.id)
if (!kbId) throw new Error(`建库失败：${created.text.slice(0, 300)}`)
console.log(`探针库 kbId=${kbId}\n`)

// ---------- ① 先建一篇并等它就绪：让库里真的有一代分区可 drop ----------
console.log('① 上传文档 A 并等它 READY（让库里出现一代分区）')
const docA = (await upload(kbId, 'a.md', '# 甲文档\n\n甲的内容，用于制造一代分区。\n')).docId
await waitTerminal(docA, 120000)
await snapshot('A 就绪后', kbId, [docA])

// ---------- ② 起第二篇，立刻清空索引 ----------
console.log('\n② 上传文档 B，然后**立刻**清空索引（趁 B 还在跑）')
// ⚠️ B 要**够大**：小文档可能在一秒内就跑完，于是"清空时没有在途作业"，
//    探针会**看起来通过而实际什么都没验**（第二轮就是这么过去的 —— 实测
//    `[清空前] … 10913:READY` 说明它早就跑完了）。
const bigB = '# 乙文档\n\n'
  + Array.from({ length: 60 },
    (_, i) => `## 第 ${i + 1} 节\n\n这是第 ${i + 1} 节的正文，用来把解析与嵌入的时间拉长，`
      + '确保「清空索引」打下来的时候这篇**还在跑**。').join('\n\n') + '\n'
const docB = (await upload(kbId, 'b.md', bigB)).docId

// 轮询到它**真的开始跑**再清 —— 并把它此刻的状态打出来，当作"本轮确实有在途作业"的证据
let bState = ''
for (let i = 0; i < 40; i++) {
  const row = docRow(docB)
  bState = row ? row.split('|')[0] : ''
  if (['PARSING', 'INDEXING'].includes(bState)) break
  if (['READY', 'FAILED'].includes(bState)) break
  await sleep(250)
}
console.log(`  B 开跑时的状态 = ${bState}${['PARSING', 'INDEXING'].includes(bState) ? ' ✅（本轮真的有在途作业）' : ' ⚠️（跑太快，本轮没验到第①半）'}`)

const beforeClear = await snapshot('清空前', kbId, [docA, docB])
const cleared = await api(`/api/kb/${kbId}/index`, { method: 'DELETE' })
console.log(`  DELETE /kb/${kbId}/index → HTTP ${cleared.status} ${JSON.stringify(cleared.json?.data ?? cleared.json)}`)
await snapshot('清空后立刻', kbId, [docA, docB])

// ---------- ③ 等在途作业跑完，再看一次 ----------
console.log('\n③ 等 B 跑到终态（说明那个在途作业确实写完了）')
await waitTerminal(docB, 300000)
await sleep(2000) // 让 Milvus 的写入可见（写入不 flush，但 query 能读到自己写的）
const after = await snapshot('B 终态后', kbId, [docA, docB])

// ---------- ④ 结论 ----------
console.log('\n======== 结论 ========')
console.log(`清空前分区 = [${beforeClear.parts.join(',') || '—'}] · 向量 ${beforeClear.vec}`)
console.log(`清空后分区 = [${after.parts.join(',') || '—'}] · 向量 ${after.vec}`)

// 修复后的期望：向量**不再回来**，且库里出现的是**新代**的空分区
// （新代分区必须先建 —— 否则检索侧指向不存在的分区是"报错"而不是"返回空"）
const docBStatus = docRow(docB).split('|')[0]
const noVectorsBack = after.vec === 0
const freshGenExists = after.parts.length === 1 && !beforeClear.parts.includes(after.parts[0])
const docBNotReady = docBStatus !== 'READY'

console.log(`\n判定（修复后）：向量没回来=${noVectorsBack} · 出现新代空分区=${freshGenExists} · 在途文档未被标 READY=${docBNotReady}（实测 ${docBStatus}）`)
if (noVectorsBack && freshGenExists && docBNotReady) {
  console.log('✅ 修复生效：清空**没有被在途作业撤销**，且库里已切到新代（检索不会指向已删分区）')
} else if (!noVectorsBack) {
  console.log(`🔴 向量回来了（${after.vec} 条）⇒ **缺陷仍在**，清空被静默撤销`)
} else {
  console.log('⚠️ 没有向量回来，但另两条不全满足 —— 上两行是实测，按数据判，别按期望判')
}
console.log('=====================\n')

await cleanup()
console.log(`清理后 kb 总数 = ${mysqlScalar('select count(*) from sys_kb;')}`)
void spawnSync
