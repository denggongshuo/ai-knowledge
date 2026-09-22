/**
 * `S7-M06-F` E2E：**删除文档 + 回收站**（`T-611` / `T-612` / `T-616` · `G7-10`~`G7-14`）。
 *
 * <h3>走的是真链路，不是假的</h3>
 * <p>真后端（8080）· 真 MySQL · 真 MinIO · 真 Milvus。每一次"成功"都落到**可独立观测的产物**上：
 * 行上的 `deleted_at`/`deleted_by`、桶里的对象、Milvus 里的向量条数、回收站列表里的字段。
 * 脚本**不看接口的返回值就下结论**（本仓那条「SDK 调用成功不等于业务事实已成立」）。
 *
 * <h3>覆盖的判据</h3>
 * <ul>
 *   <li><b>`G7-14` 两半</b>：软删**只写两个列** —— 对象与向量一个都不动；
 *       而软删之后**不可达也不可召回**（详情 / 按库列表 / 跨库列表 / 过滤表达式四处）；</li>
 *   <li><b>`G7-10`</b>：回收站列表的 joined 列（删除人 / 原库 / 大小）· `keepDays` 由服务端下发 ·
 *       剩余天数在三档边界上逐点对（20 / 19 / 16 / 15 / 0）· `keyword` 过滤；</li>
 *   <li><b>`G7-11`</b>：原地恢复 · **原库已删时必须指定目标库**（否则 10030 且零副作用）·
 *       换库恢复要连 `sys_chunk.kb_id` 一起换 · 恢复知识库是**连带**且 `affectedDocs` **如实数得出**；</li>
 *   <li><b>`O7` 规则表 · 彻底删除逐格</b>：源文件删 · 解析产物删 · 向量删 · 派生行删 ·
 *       **`sys_download_record` 留**（下载是既成事实）；</li>
 *   <li><b>`G7-12`/`G7-13`</b>：清空回收站两类条目一起清 · 超期清理**幂等**（第二次 `cleaned = 0`）；</li>
 *   <li>入参：`type` 必填且只能 DOC/KB · 空 ids 报 10030；</li>
 *   <li><b>残留自清 0</b>（四族都断言：探针库族 · 桶对象 · Milvus 向量 · Milvus 分区）。</li>
 * </ul>
 *
 * <h3>🔴 为什么脚本要**自己往 Milvus 插向量**</h3>
 * <p>本机 `.env` 里 `EMBEDDED_API_BASE_URL` 是**空的** ⇒ 后端根本没有嵌入端点
 * ⇒ 上传的文档走完解析也**一条向量都不会有**（`m05.vectorize.enabled` 那层是开着的，
 * 但客户端拿不到地址）。
 * <p>而本批最要紧的两条判据问的正是**向量条数**（软删后还在 / 彻底删除才没）。
 * 不造前置态的写法是「断言 0 == 0」—— 它看着是绿的、实际什么都没验。
 * 所以这里照 {@code probe-orphan-vectors.mjs} 的既定做法手工 `entities/insert`：
 * 往 `kb{kbId}_g1` 分区插几条真向量，并把 `sys_document.index_generation` 盖成 1
 * —— 与解析链路成功之后的库状态**逐项一致**。这是**造前置态**，不是伪造结果：
 * 每一条断言问的都是"脚本插进去的那几条点还在不在"。
 *
 * <h3>前置</h3>
 * <pre>
 *   后端 8080 必须**在跑**（本脚本要真 HTTP）。
 *   ⚠️ 与 `SingleAsyncConsumerGuardTest` 是**互斥**的：那条用例要求 8080 **没有**监听。
 *      先跑 `mvn test`（后端停），再跑本脚本（后端起）。
 *   运行：node ai_knowledge_app/scripts/e2e-doc-recycle.mjs
 * </pre>
 *
 * ⚠️ **本文件没有 shebang**：本机 shell 的路径守卫会拦下含 `#!` 的内容。
 */
import { setTimeout as sleep } from 'node:timers/promises'
import {
  assertArtifactCoverage, describeKbResidue, kbResidueClean, milvusCountOfKb,
  milvusDeleteVectors, milvusLeftoverPartitions, milvusPost, minioStillPresent,
  mysqlCount, mysqlScalar,
  sweepAbandonedKbProbes, teardownKbProbe,
} from './lib/probe-teardown.mjs'

const API = process.argv.find((a) => a.startsWith('--api='))?.slice(6) ?? 'http://127.0.0.1:8080'
const ADMIN = { account: 'admin', password: 'Admin@123' }
const COLLECTION = 'knowledge_vectors'

const stamp = Date.now()
const KB_CODE_A = `kb_e2erc_${stamp}`
const KB_CODE_B = `kb_e2erc_t_${stamp}`
const NAME_A = `回收甲-${stamp}.md`
const NAME_B = `回收乙-${stamp}.md`

const steps = []
function check(ok, label, detail = '') {
  steps.push({ ok: !!ok, label, detail })
  console.log(`${ok ? '✓' : '✗'} ${label}${detail ? ` · ${detail}` : ''}`)
  return !!ok
}
function observe(label, detail = '') {
  console.log(`… ${label}${detail ? ` · ${detail}` : ''}`)
}

/** 收尾用的快照（要在**任何删除之前**抓）。 */
const refs = { docA1: null, docA2: null, kbAId: null, kbBId: null, minioKeys: [] }

/**
 * 超管的令牌。
 *
 * <p>⚠️ 它**必须**待在模块顶层，不能写成 `try` 块里的 `const` ——
 * {@code waitDocReady} / {@code findRecycleRow} 那几个助手函数声明在**块外**
 * （函数声明在 ESM 的块里是**块作用域**的，所以我试过把它们也放进去 ⇒ 当场 `kbForm is not defined`），
 * 它们闭包不到块内的 `const` ⇒ 会在第一次调用时报 {@code adminToken is not defined}。
 * 这不是风格问题：`try` 块里的 `const` 与"包在外面的助手函数"本质上**互不可见**。
 */
let adminToken = null

// ------------------------------------------------------------------ HTTP
async function api(path, { method = 'GET', body, token, raw = false } = {}) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const res = await fetch(`${API}/api${path}`, {
    method, headers, body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  if (raw) return { status: res.status, text }
  let parsed
  try {
    parsed = JSON.parse(text)
  } catch {
    throw new Error(`${method} ${path} 不是合法 JSON：${text.slice(0, 200)}`)
  }
  return parsed
}

const ok = (label, r) => {
  if (r.code !== 0) throw new Error(`${label} 失败：${r.message}（code=${r.code}）`)
  return r.data
}

// ------------------------------------------------------------------ Milvus 前置态
/** 建一个分区（幂等：已存在时 Milvus 会报错，那不算失败）。 */
function ensurePartition(partition) {
  const r = milvusPost('partitions/create', { collectionName: COLLECTION, partitionName: partition })
  if (r?.code !== 0 && !JSON.stringify(r).includes('already exist')) {
    throw new Error(`建分区失败（${partition}）：${JSON.stringify(r).slice(0, 300)}`)
  }
  milvusPost('partitions/load', { collectionName: COLLECTION, partitionNames: [partition] })
}

/** 往指定分区插 {@code count} 条**真**向量（脚本自己造的前置态，理由见文件头注）。 */
function seedVectors(kbId, docId, partition, count, tag) {
  ensurePartition(partition)
  const data = []
  for (let i = 0; i < count; i++) {
    const v = new Array(1024).fill(0)
    v[i] = 1
    data.push({
      id: `recycle-e2e-${tag}-${stamp}-${i}`,
      embedding: v,
      kb_id: Number(kbId),
      doc_id: Number(docId),
      chunk_type: 'PARAGRAPH',
      secret_level: 'INTERNAL',
      title_path: '探针标题',
      content: `探针正文 ${i}`,
      bm25_text: `探针正文 ${i}`,
    })
  }
  const r = milvusPost('entities/insert', {
    collectionName: COLLECTION, partitionName: partition, data,
  })
  if (r?.code !== 0) {
    throw new Error(`探针向量插入失败（${partition}/${docId}）：${JSON.stringify(r).slice(0, 300)}`)
  }
  // 🔴 必须同时盖断点列：`purgeDoc` 靠 `index_generation > 0` 才去清向量。
  //    只插向量不盖这一列，被测代码会**跳过**向量清理，而断言 `count == 0` 照样绿 —— 空断言。
  mysqlScalar(`update sys_document set index_generation = 1 where id = ${docId};`)
}

/** 等某个 kb 的向量条数稳定到期望值（Milvus 的写入可见性有几秒延迟）。 */
async function waitKbCount(kbId, expected, tries = 10) {
  let n = -1
  for (let i = 0; i < tries; i++) {
    n = milvusCountOfKb(kbId)
    if (n === expected) return n
    if (i < tries - 1) await sleep(1200)
  }
  return n
}

// ------------------------------------------------------------------ 自检
console.log('=== S7-M06-F · 删除文档 + 回收站 E2E ===\n')
assertArtifactCoverage()
observe('两侧产物登记表已对账（Java ↔ 脚本）')

// 🔴 开工前先收掉**被中断的轮次**留下的探针库（`T-O23` 的第二形态）。
//    ⚠️ 这不是"顺手多做一步"：本脚本**第一次跑就崩过**（Milvus 的 UTF-8 报错），
//    而它当时还没跑到收尾 —— 于是留下 2 个探针库 + 7 条向量 + 一批对象。
//    下一轮如果不清，那些**残留在同一个探针库里**的向量会直接把
//    「前置态 = 我自己种的那几条」这条断言算错（实测：期望 5、实查 14）。
//    「清理必须走必走路径」在这里的落点就是：**开工先自清，收尾再自清**。
const sweptKb = await sweepAbandonedKbProbes(['kb_e2erc'])
if (sweptKb.length > 0) {
  observe('收掉上一轮（或更早被中断的轮次）留下的探针库', `${sweptKb.length} 个：${sweptKb.join(' · ')}`)
}
const occupied = mysqlScalar(`select group_concat(code) from sys_kb where code in ('${KB_CODE_A}','${KB_CODE_B}');`)
if (occupied && occupied !== 'NULL') throw new Error(`探针库 code 已被占用（上一轮没清干净？）：${occupied}`)

const ping = await fetch(`${API}/api/auth/captcha`).catch(() => null)
check(ping !== null && ping.status < 500, '后端 8080 在跑（本脚本必须连真后端）', API)

// 🔴 全流程包在 `try/finally` 里 —— **中断的轮次不许把残留留给下一轮**。
//    本脚本第一版没包，第一次跑就崩在一句 Milvus 的 UTF-8 报错上（见 `milvusPost` 的注释），
//    而它当时还没走到收尾 ⇒ 留下 2 个探针库 + 7 条向量 + 一批桶对象，
//    下一轮的前置态断言当场算错（期望 5、实查 14）。
//    ⚠️ 这与三个 O 轨遗留是**同一个形状**：「清理函数存在、调用方也在，
//       但 `step()` 一抛它就执行不到」（`#186`）。闸要拦住的正是这种。
try {
  // ------------------------------------------------------------------ 1) 造前置态
  adminToken = ok('超管登录', await api('/auth/login',
    { method: 'POST', body: { account: ADMIN.account, password: ADMIN.password } })).accessToken
  const adminId = Number(mysqlScalar("select id from sys_user where username = 'admin';"))
  const adminName = mysqlScalar("select real_name from sys_user where username = 'admin';")

  const kbA = ok('建探针库 A', await api('/kb', { method: 'POST', token: adminToken, body: kbForm(KB_CODE_A) }))
  const kbB = ok('建探针库 B（换库恢复的目标）', await api('/kb', {
    method: 'POST', token: adminToken, body: kbForm(KB_CODE_B, 'M06-F 回收站 E2E 目标库'),
  }))
  refs.kbAId = Number(kbA.id)
  refs.kbBId = Number(kbB.id)
  observe('两个探针库已建', `A=${refs.kbAId}（${KB_CODE_A}） B=${refs.kbBId}（${KB_CODE_B}）`)

  async function uploadDoc(name) {
    const form = new FormData()
    form.append('file', new Blob([`# ${name}\n\n回收站 E2E 正文 ${stamp}\n`], { type: 'text/markdown' }), name)
    const res = await fetch(`${API}/api/kb/${refs.kbAId}/doc/upload`, {
      method: 'POST', headers: { Authorization: `Bearer ${adminToken}` }, body: form,
    })
    const docId = ok(`上传 ${name}`, JSON.parse(await res.text())).docId
    await waitDocReady(docId)
    return Number(docId)
  }

  refs.docA1 = await uploadDoc(NAME_A)
  refs.docA2 = await uploadDoc(NAME_B)
  check(true, '两篇探针文档均已 READY', `docA1=${refs.docA1} docA2=${refs.docA2}`)

  // 对象与解析产物的快照（收尾要断言"真的没了"）
  refs.minioKeys = mysqlScalar(
    `select group_concat(k) from (select storage_path k from sys_document where id in (${refs.docA1},${refs.docA2})`
    + ` union all select parse_path from sys_document where id in (${refs.docA1},${refs.docA2})) t`
    + ' where k is not null and k <> "";').split(',').map((s) => s.trim()).filter(Boolean)
  check(refs.minioKeys.length >= 4, '前置态：两篇文档的源文件 + 解析产物都在桶里',
    `快照 ${refs.minioKeys.length} 个对象`)

  // 🔴 种向量之前**先把这两个探针库在集合里的点清成 0**。
  //    不这么做的话，"前置态 = 我自己种的那几条"这条断言会**算进上一轮的残留** ——
  //    实测踩到过：期望 5、实查 14（上一轮被中断、分区被 drop 之后，
  //    那批点仍在集合里可见一段时间，而 `count(*)` 的过滤是**整个集合**范围的）。
  //    ⚠️ 这与"清不掉"无关：`entities/delete` 是按 `kb_id` 的**真删**，
  //    清完再数到 0 才开始种 —— 于是断言里那个数是"我这轮种下去的"，
  //    而不是"历史上恰好落在同一个 kb_id 上的所有点"。
  milvusDeleteVectors([refs.kbAId, refs.kbBId])
  const preA = await waitKbCount(refs.kbAId, 0)
  const preB = await waitKbCount(refs.kbBId, 0)
  check(preA === 0 && preB === 0, '前置态：两个探针库起点都是 0 条向量（断言不掺上一轮的残留）',
    `A=${preA} B=${preB}`)

  // 造"已被向量化过"的前置态：甲 2 条、乙 3 条，同在第 1 代
  seedVectors(refs.kbAId, refs.docA1, `kb${refs.kbAId}_g1`, 2, 'a1')
  seedVectors(refs.kbAId, refs.docA2, `kb${refs.kbAId}_g1`, 3, 'a2')
  const vectorsSeeded = await waitKbCount(refs.kbAId, 5)
  check(vectorsSeeded === 5, '前置态：探针库分区里真有 5 条向量（甲 2 + 乙 3）',
    `轮询后实查 count=${vectorsSeeded}`)

  // ------------------------------------------------------------------ 2) G7-14：软删只写两个列
  console.log('\n--- G7-14 · 软删 ---')
  const deleted = ok('删除文档甲', await api(`/doc/${refs.docA1}`, { method: 'DELETE', token: adminToken }))
  check(deleted.recycled === true && Number(deleted.docId) === refs.docA1,
    'G7-14：响应说它是「移入回收站」，不是真删', JSON.stringify(deleted))
  check(mysqlCount(`select count(*) from sys_document where id = ${refs.docA1} and deleted_at is not null;`) === 1,
    'deleted_at 已写')
  check(mysqlScalar(`select deleted_by from sys_document where id = ${refs.docA1};`) === String(adminId),
    'deleted_by 也写了（V41 新列）', `实查 ${mysqlScalar(`select deleted_by from sys_document where id = ${refs.docA1};`)}`)

  const stillThere = minioStillPresent([mysqlScalar(`select storage_path from sys_document where id = ${refs.docA1};`)])
  check(stillThere.length === 1, '🔴 源文件**一个字节没动**（这是"可恢复"的前提）', stillThere.join(', ') || '—')
  const afterSoftDelete = await waitKbCount(refs.kbAId, 5)
  check(afterSoftDelete === 5, '🔴 Milvus 向量**一条没少**（G7-14 的原文）', `实查 count=${afterSoftDelete}`)
  check(mysqlScalar(`select index_generation from sys_document where id = ${refs.docA1};`) === '1',
    '连「写齐了哪一代」的断点都没被动过（清了这列等于让向量永远无主）')

  // ------------------------------------------------------------------ 3) G7-14 的另一半：不可达、不可召回
  console.log('\n--- G7-14 · 不可达 / 不可召回 ---')
  check((await api(`/doc/${refs.docA1}`, { token: adminToken })).code === 10044,
    '详情接口查不到它（10044）')
  check(!(await kbDocIds()).includes(refs.docA1), '按库文档列表里没有它')
  check(!(await allDocIds()).includes(refs.docA1), '跨库文档列表里也没有它')

  // ------------------------------------------------------------------ 4) G7-10：回收站列表
  console.log('\n--- G7-10 · 回收站列表 ---')
  const pageDoc = ok('查回收站 · 文档档', await api('/recycle/items?type=DOC&page=1&size=200', { token: adminToken }))
  check(pageDoc.keepDays === 30, '保留期由**服务端**下发（不是前端常量）', `keepDays=${pageDoc.keepDays}`)
  const rowA = pageDoc.list.find((r) => Number(r.id) === refs.docA1)
  check(!!rowA, '软删的那篇在文档档里', rowA ? `id=${rowA.id}` : '找不到')
  if (rowA) {
    check(String(rowA.name).includes('回收甲'), 'name = 文档标题', String(rowA.name))
    check(rowA.origin === 'M06-F 回收站 E2E 探针库', 'origin = **原所属知识库**（文档档的含义）', String(rowA.origin))
    check(rowA.deletedBy === adminName, 'deletedBy 由 V41 的 deleted_by join 出来', String(rowA.deletedBy))
    check(rowA.docCount === null, '文档条目的 docCount 恒 null（不是 0 —— 0 是在说"这篇下面有 0 篇"）',
      String(rowA.docCount))
    check(rowA.originDeleted === false && rowA.hasNewer === false,
      '原库还在 ⇒ originDeleted=false；同组没有更新版本 ⇒ hasNewer=false')
    check(rowA.remainDays === 30, '刚删的 ⇒ 满 30 天', `remainDays=${rowA.remainDays}`)
  }

  // 三档色阶的分界点（与前端 remainClass 同口径：≥20 绿 / 16~19 橙 / <16 红）
  for (const [daysAgo, expected] of [[10, 20], [11, 19], [14, 16], [15, 15], [45, 0]]) {
    mysqlScalar(`update sys_document set deleted_at = now() - interval ${daysAgo} day where id = ${refs.docA1};`)
    const n = (await findRecycleRow('DOC', refs.docA1))?.remainDays
    check(n === expected, `G7-10：${daysAgo} 天前删 ⇒ 剩余 ${expected} 天`, `实查 ${n}`)
  }
  mysqlScalar(`update sys_document set deleted_at = now() where id = ${refs.docA1};`)

  const kw = await api(`/recycle/items?type=DOC&keyword=${encodeURIComponent('回收甲')}&page=1&size=200`,
    { token: adminToken })
  check(ok('keyword 过滤', kw).list.some((r) => Number(r.id) === refs.docA1), '按标题关键字能筛到它')
  const kwMiss = await api(`/recycle/items?type=DOC&keyword=${encodeURIComponent('绝不存在的关键字')}&page=1&size=200`,
    { token: adminToken })
  check(ok('keyword 反例', kwMiss).list.length === 0, '不匹配的关键字筛不到任何东西（不是"忽略过滤"）')

  // ------------------------------------------------------------------ 5) G7-11：原地恢复
  console.log('\n--- G7-11 · 恢复 ---')
  const restored = ok('恢复文档甲', await api('/recycle/restore',
    { method: 'POST', token: adminToken, body: { type: 'DOC', ids: [refs.docA1] } }))
  check(restored.restored === 1 && restored.affectedDocs === 1, '恢复 1 篇', JSON.stringify(restored))
  check(restored.needReindex === true, 'G7-11：如实提示「需重建索引才能参与检索」（不替用户做重活）')
  check(restored.note.includes('重建索引'), 'note 里明说下一步', restored.note)
  check(mysqlCount(`select count(*) from sys_document where id = ${refs.docA1} and deleted_at is null;`) === 1,
    'deleted_at 已清')
  check((await api(`/doc/${refs.docA1}`, { token: adminToken })).code === 0, '恢复之后详情接口又能查到了')
  check(!(await findRecycleRow('DOC', refs.docA1)), '它已不在回收站列表里')

  // ------------------------------------------------------------------ 6) T-612：跨库列表
  console.log('\n--- T-612 · 跨库文档列表 ---')
  const allPage = ok('跨库列表', await api('/doc/all?page=1&size=200', { token: adminToken }))
  const allRow = allPage.list.find((r) => Number(r.id) === refs.docA1)
  check(!!allRow, '刚上传的文档在跨库列表里')
  if (allRow) {
    check(Number(allRow.kbId) === refs.kbAId && allRow.kbName === 'M06-F 回收站 E2E 探针库',
      '行里带 kbId/kbName（跨库列表显示的就是它）', `${allRow.kbId} · ${allRow.kbName}`)
  }
  check((await api('/doc/all?kbId=不是数字', { token: adminToken })).code === 10030,
    '非法 kbId 显式报错，**不静默当成「全部」**')

  // ------------------------------------------------------------------ 7) 批量删除：整批拒绝
  console.log('\n--- T-611 · 批量删除 ---')
  const batch = await api('/doc/batch', { method: 'DELETE', token: adminToken, body: { ids: [refs.docA2, 999999999] } })
  check(batch.code === 10044, '含一个不存在的文档 ⇒ 报 10044', batch.message)
  check(mysqlCount(`select count(*) from sys_document where id = ${refs.docA2} and deleted_at is not null;`) === 0,
    '🔴 **整批拒绝**：另外那篇也一篇没删（静默过滤会把它删掉）')

  // ------------------------------------------------------------------ 8) 原库已删 ⇒ 恢复必须指定目标库
  console.log('\n--- G7-11 · 原库已删 ---')
  ok('删除文档乙（先进回收站）', await api(`/doc/${refs.docA2}`, { method: 'DELETE', token: adminToken }))
  const kbDeleted = ok('删除知识库 A', await api(`/kb/${refs.kbAId}`, { method: 'DELETE', token: adminToken }))
  check(kbDeleted.recycled === true, '知识库是**软删**进回收站（`deleted`/`recycled` 两列都在）',
    JSON.stringify(kbDeleted))

  const pageKb = ok('查回收站 · 知识库档', await api('/recycle/items?type=KB&page=1&size=200', { token: adminToken }))
  const rowKb = pageKb.list.find((r) => Number(r.id) === refs.kbAId)
  check(!!rowKb, '软删的库在知识库档里')
  if (rowKb) {
    check(rowKb.type === 'KB', 'type 是 KB（两档由 type 区分，两个 id 序列是独立的）')
    check(rowKb.docCount === 1, 'docCount = 会被**连带恢复**的篇数（甲还在这个库里）', String(rowKb.docCount))
    check(rowKb.docCount !== null && rowKb.originDeleted === null,
      '知识库条目没有文档专属字段（originDeleted/hasNewer 不适用）')
  }
  check((await api(`/kb/${refs.kbAId}/config`, { token: adminToken })).code !== 0,
    '软删的库**对谁都不可达** —— 这正是"连带恢复"能成立的那半边')

  const rejected = await api('/recycle/restore',
    { method: 'POST', token: adminToken, body: { type: 'DOC', ids: [refs.docA2] } })
  check(rejected.code === 10030 && String(rejected.message).includes('原知识库已删除')
    && String(rejected.message).includes('请指定'),
  '原库已删 + 没给目标库 ⇒ 拒绝，并说清**为什么**与**下一步**', rejected.message)
  check(mysqlCount(`select count(*) from sys_document where id = ${refs.docA2} and deleted_at is not null;`) === 1,
    '被拒时**零副作用**：它还在回收站里')

  const moved = ok('换库恢复', await api('/recycle/restore',
    { method: 'POST', token: adminToken, body: { type: 'DOC', ids: [refs.docA2], targetKbId: refs.kbBId } }))
  check(Number(moved.targetKbId) === refs.kbBId, '恢复到指定库', JSON.stringify(moved))
  check(mysqlScalar(`select kb_id from sys_document where id = ${refs.docA2};`) === String(refs.kbBId),
    '文档已换库')
  check(mysqlCount(`select count(*) from sys_chunk where doc_id = ${refs.docA2} and kb_id = ${refs.kbBId};`) > 0,
    '🔴 分片也换了 kb_id（只改文档列会让分片按**旧库密级**参与过滤 —— 一个安静的越权口子）')
  const afterMove = await waitKbCount(refs.kbAId, 2)
  check(afterMove === 2, '🔴 换库时**清掉了它在旧库分区里的 3 条向量**（不清就是谁也认领不了的残留点）',
    `换库前 5 → 换库后 ${afterMove}（期望 2）`)

  const restoredKb = ok('恢复知识库 A', await api('/recycle/restore',
    { method: 'POST', token: adminToken, body: { type: 'KB', ids: [refs.kbAId] } }))
  check(restoredKb.affectedDocs === 1,
    '知识库恢复是**连带**的，且 affectedDocs 是**如实数出来**的（乙已搬走，所以是 1 不是 2）',
    `实查 ${restoredKb.affectedDocs}`)
  check((await api(`/kb/${refs.kbAId}/config`, { token: adminToken })).code === 0, '恢复之后库可达')
  check((await api(`/doc/${refs.docA1}`, { token: adminToken })).code === 0,
    '原库里的文档也回到了列表里（它们**从未被软删** —— 这就是"连带"是零动作的原因）')

  // ------------------------------------------------------------------ 9) O7 规则表 · 彻底删除逐格
  console.log('\n--- O7 规则表 · 彻底删除 ---')
  ok('先制造一条下载记录', await api(`/doc/download-url/${refs.docA1}`, { token: adminToken }))
  const downloadsBefore = mysqlCount(`select count(*) from sys_download_record where doc_id = ${refs.docA1};`)

  ok('软删文档甲', await api(`/doc/${refs.docA1}`, { method: 'DELETE', token: adminToken }))
  const purged = ok('彻底删除文档甲', await api('/recycle/items',
    { method: 'DELETE', token: adminToken, body: { type: 'DOC', ids: [refs.docA1] } }))
  check(purged.deleted === 1, '彻底删除 1 条', JSON.stringify(purged))
  check(mysqlCount(`select count(*) from sys_document where id = ${refs.docA1};`) === 0, '文档行没了')
  check(mysqlCount(`select count(*) from sys_chunk where doc_id = ${refs.docA1};`) === 0, '派生行（分片）也清了')
  check(minioStillPresent([String(refs.minioKeys[0])]).length === 0, '源文件对象删了')
  const vectorLeft = await waitKbCount(refs.kbAId, 0)
  check(vectorLeft === 0, '🔴 Milvus 向量到这一步才清，且真清到了 0', `实查 count=${vectorLeft}`)
  check(mysqlCount(`select count(*) from sys_download_record where doc_id = ${refs.docA1};`) === downloadsBefore,
    '🔴 下载记录**留着** —— O7 规则表说它永久保留（下载是既成事实）',
    `删前 ${downloadsBefore} 条 / 删后 ${mysqlCount(`select count(*) from sys_download_record where doc_id = ${refs.docA1};`)} 条`)

  // ------------------------------------------------------------------ 10) G7-13 · 超期清理幂等
  console.log('\n--- G7-13 · 超期清理 ---')
  ok('软删文档乙（未超期的那条）', await api(`/doc/${refs.docA2}`, { method: 'DELETE', token: adminToken }))
  ok('软删知识库 A（未超期的那条）', await api(`/kb/${refs.kbAId}`, { method: 'DELETE', token: adminToken }))
  // 把乙推到 40 天前（超过 30 天保留期）
  mysqlScalar(`update sys_document set deleted_at = now() - interval 40 day where id = ${refs.docA2};`)

  const first = ok('第一次清理', await api('/recycle/cleanup', { method: 'POST', token: adminToken }))
  check(first.cleaned === 1, '只清**超期**的那 1 条', `cleaned=${first.cleaned} · ${first.note}`)
  check(mysqlCount(`select count(*) from sys_document where id = ${refs.docA2};`) === 0, '超期那篇被**物理**删掉了')
  check(mysqlCount(`select count(*) from sys_kb where id = ${refs.kbAId} and deleted_at is not null;`) === 1,
    '🔴 未超期的知识库**必须还在** —— 这正是「幂等」与「不报错」的差别所在')
  const second = ok('第二次清理', await api('/recycle/cleanup', { method: 'POST', token: adminToken }))
  check(second.cleaned === 0, '幂等的判据就是这个 0（清理条件本身只认未清的、超期的行）',
    `cleaned=${second.cleaned}`)
  check(String(second.note).includes('无需清理'), '第二次的 note 说清"没有超期项"', second.note)

  // ------------------------------------------------------------------ 11) G7-12 · 清空
  console.log('\n--- G7-12 · 清空回收站 ---')
  const beforeClear = (await api('/recycle/items?type=DOC&page=1&size=200', { token: adminToken })).data.total
    + (await api('/recycle/items?type=KB&page=1&size=200', { token: adminToken })).data.total
  const cleared = ok('清空回收站', await api('/recycle/all', { method: 'DELETE', token: adminToken }))
  check(cleared.cleared === beforeClear && beforeClear > 0,
    '清空的条数与"清空前两份列表的合计"一致', `清空 ${cleared.cleared} / 清空前合计 ${beforeClear}`)
  const afterDoc = ok('清空后查文档档', await api('/recycle/items?type=DOC&page=1&size=200', { token: adminToken }))
  const afterKb = ok('清空后查知识库档', await api('/recycle/items?type=KB&page=1&size=200', { token: adminToken }))
  check(afterDoc.total === 0 && afterKb.total === 0, '两档都空了', `${afterDoc.total} / ${afterKb.total}`)

  // ------------------------------------------------------------------ 12) 入参
  console.log('\n--- 入参 ---')
  check((await api('/recycle/items?type=XX', { token: adminToken })).code === 10030,
    '非法 type 不静默当成 DOC（那会让知识库档显示成文档列表）')
  check((await api('/recycle/items', { token: adminToken })).code === 10030, '缺 type 直接 10030')
  check((await api('/recycle/items', {
    method: 'DELETE', token: adminToken, body: { type: 'DOC', ids: [] },
  })).code === 10030, '空 ids 报 10030')

} catch (err) {
  console.error(`
✗ 运行中断：${err.message}`)
  steps.push({ ok: false, label: '运行未完成（中断）—— 收尾照跑', detail: err.message })
} finally {
  // ------------------------------------------------------------------ 13) 残留自清
  console.log('\n--- 收尾 · 残留自清 ---')
  const kbARes = await teardownKbProbe(KB_CODE_A)
  const kbBRes = await teardownKbProbe(KB_CODE_B)
  check(kbResidueClean(kbARes.residue), `探针库 A 族残留已清 0：${describeKbResidue(kbARes.residue)}`)
  check(kbResidueClean(kbBRes.residue), `探针库 B 族残留已清 0：${describeKbResidue(kbBRes.residue)}`)
  const leftObjects = minioStillPresent(refs.minioKeys)
  check(leftObjects.length === 0, '桶里那批对象已清 0（四族里的"桶对象"那一族）',
    leftObjects.join(', ') || '—')
  const vecA = await waitKbCount(refs.kbAId, 0)
  const vecB = await waitKbCount(refs.kbBId, 0)
  check(vecA === 0 && vecB === 0, '两个探针库的 Milvus 向量已清 0（**独立观测点**：直查条数，不看分区）',
    `A=${vecA} B=${vecB}`)
  const partsLeft = (await milvusLeftoverPartitions([refs.kbAId, refs.kbBId]))
  check(partsLeft.length === 0, '两个探针库的代分区也丢干净了（含换库时留下的那些）',
    partsLeft.join(', ') || '—')
  check(mysqlCount(`select count(*) from sys_kb where code in ('${KB_CODE_A}','${KB_CODE_B}');`) === 0,
    '探针库行已删（清到最后"连按 code 都查不到"）')

  // ------------------------------------------------------------------ 汇总
  const failed = steps.filter((s) => !s.ok)
  console.log(`\n共 ${steps.length} 条断言，失败 ${failed.length} 条`)
  if (failed.length) {
    failed.forEach((s) => console.log(`  ✗ ${s.label}${s.detail ? ` · ${s.detail}` : ''}`))
    process.exit(1)
  }
  console.log('✓ 全部通过')

}

// ==================================================================== 内部
function kbForm(code, name = 'M06-F 回收站 E2E 探针库') {
  return {
    name, code, deptId: 1,
    visibleRoles: ['SUPER_ADMIN'],
    secretLevel: 'INTERNAL', businessDomain: '研发 / 平台', description: '', customTags: [],
    splitPolicy: { preset: 'GENERAL', chunkSize: 512, overlap: 64, recursiveSplit: true, semanticSplit: false },
    tablePolicy: {
      atomicTable: true, dualRepresentation: true, repeatHeader: true, mergeCrossPage: true,
      ocrTable: true, rowLimit: 30, rowWindow: 5,
    },
    retrievalConfig: {
      mode: 'HYBRID', recallThreshold: 0.15, rerankTopK: 5, rerankEnabled: true,
      tableWeight: 1.2, identifierProtect: true,
    },
  }
}

async function waitDocReady(docId) {
  for (let i = 0; i < 90; i++) {
    const meta = await api(`/doc/${docId}`, { token: adminToken })
    const status = meta?.data?.status
    if (status === 'READY') return
    if (status === 'FAILED') throw new Error(`文档 ${docId} 解析失败：${meta.data.errorMsg ?? '—'}`)
    await sleep(1000)
  }
  throw new Error(`文档 ${docId} 90s 内没到 READY`)
}

/** 回收站某一档里找一条（找不到返回 undefined，交由调用方断言）。 */
async function findRecycleRow(type, id) {
  const page = await api(`/recycle/items?type=${type}&page=1&size=200`, { token: adminToken })
  return (page.data?.list ?? []).find((r) => Number(r.id) === id)
}

/** 某个库的按库文档列表 id（`GET /kb/{id}/docs`）。 */
async function kbDocIds() {
  const page = await api(`/kb/${refs.kbAId}/docs?page=1&size=200`, { token: adminToken })
  return (page.data?.list ?? []).map((r) => Number(r.id))
}

/** 跨库文档列表 id（`GET /doc/all`）。 */
async function allDocIds() {
  const page = await api('/doc/all?page=1&size=200', { token: adminToken })
  return (page.data?.list ?? []).map((r) => Number(r.id))
}
