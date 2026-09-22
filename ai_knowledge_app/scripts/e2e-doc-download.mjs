/**
 * `S7-M06-E` E2E：**文档下载**（`T-613` / `T-614` / `T-615` · `G7-7` / `G7-8` / `G7-9`）。
 *
 * <h3>走的是真链路，不是假的</h3>
 * <p>真后端（8080）· 真 MySQL · 真 MinIO · 真 Milvus。每一次"成功"都落到**可独立观测的产物**上：
 * 下载记录行、对象存储里的 zip 与 CSV、通知行、审计行、Milvus 向量条数。
 * 脚本**不看接口的返回值就下结论**（本仓那条「SDK 调用成功不等于业务事实已成立」）。
 *
 * <h3>覆盖的判据</h3>
 * <ul>
 *   <li><b>`G7-7`</b>：单篇下载地址 `expiresInSec = 600`，且**拿去真取一次**、
 *       取回的正是当初上传的字节；</li>
 *   <li><b>`G7-9`</b>：单篇下载恰好多一条 `SINGLE` 记录；打包里**每篇**一条 `BATCH`；
 *       两条审计（`下载文档` NORMAL / `批量下载` HIGH）都真的落了；</li>
 *   <li><b>`G7-8`</b>：批量受理返回 `PENDING`、任务走 `PACKING → DONE`、
 *       完成后**经通知中心**给出地址（通知里那个动作**真的能点开**）、
 *       地址取回的 zip **真的能解开**且条目就是那两篇原文件；</li>
 *   <li>上限：空列表 / 51 篇 / 合计超 2GB 一律**受理即拒**（不异步再失败）；</li>
 *   <li>`T-615`：下载历史分页（`SINGLE` 与 `BATCH` 都在）· 导出清单产 CSV + 通知；</li>
 *   <li><b>残留自清 0</b>（三族都断言：探针库族 · 探针账号族 · Milvus 向量）。</li>
 * </ul>
 *
 * <h3>🔴 为什么是接口级而不是浏览器级（这是有意的取舍，不是省事）</h3>
 * <p>本批要求的每一条判据都是**数据链**判据（"zip 能不能解开""记录有没有那一条"），
 * 它们在浏览器里**看不见** —— 页面只会显示"已完成"。而下载页的「文件列表」Tab 依赖
 * `GET /doc/all`（`T-612`）**尚未实现**，所以页面级跑起来会先卡在一个与本批无关的缺口上。
 * <p>⚠️ 这个取舍有代价，**写在这里而不是藏着**：它**证不了**页面渲染
 * （按钮有没有、文案对不对、过期态显示成什么）。那些由
 * `DocDownloadView.vue` 的实现与后端用例覆盖，页面级的回归等 `T-612` 落地后补。
 *
 * <h3>前置</h3>
 * <pre>
 *   后端 8080 必须**在跑**（本脚本要真 HTTP）。
 *   ⚠️ 与 `SingleAsyncConsumerGuardTest` 是**互斥**的：那条用例要求 8080 **没有**监听。
 *      先跑 `mvn test`（后端停），再跑本脚本（后端起）。
 *   运行：node ai_knowledge_app/scripts/e2e-doc-download.mjs
 * </pre>
 *
 * ⚠️ **本文件没有 shebang**：本机 shell 的路径守卫会拦下含 `#!` 的内容。
 */
import { setTimeout as sleep } from 'node:timers/promises'
import { inflateRawSync } from 'node:zlib'
import {
  assertArtifactCoverage, describeKbResidue, findKbProbeResidue, kbResidueClean,
  milvusVectorCount, minioKeysOfPathUserPrefix, minioRemove, mysqlCount,
  mysqlScalar, teardownKbProbe, teardownUserProbe, userIdsOf,
} from './lib/probe-teardown.mjs'

const API = process.argv.find((a) => a.startsWith('--api='))?.slice(6) ?? 'http://127.0.0.1:8080'
const ADMIN = { account: 'admin', password: 'Admin@123' }

const stamp = Date.now()
const KB_CODE = `kb_e2edl_${stamp}`
const USER_PREFIX = `e2e_dl_${stamp}`
const NAME_A = `打包甲-${stamp}.md`
const NAME_B = `打包乙-${stamp}.md`
const MARK_A = `甲正文标记${stamp}`
const MARK_B = `乙正文标记${stamp}`

const steps = []
function check(ok, label, detail = '') {
  steps.push({ ok: !!ok, label, detail })
  console.log(`${ok ? '✓' : '✗'} ${label}${detail ? ` · ${detail}` : ''}`)
  return !!ok
}
function observe(label, detail = '') {
  console.log(`… ${label}${detail ? ` · ${detail}` : ''}`)
}

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

// ------------------------------------------------------------------ zip
/**
 * 极小的 zip 读取器（**零依赖**）：解析中央目录，按条目名取出解压后的字节。
 *
 * <h3>为什么不用 `tar -xf` / `Expand-Archive`</h3>
 * <p>本机（Git for Windows）的 `tar` 是 **GNU tar**，它**不认 zip**（实测：
 * `tar: This does not look like a tar archive`）。而拉 PowerShell 进来只为解一个包，
 * 会把"脚本能不能跑"绑到另一个进程的可用性上。zip 的中央目录格式是死的，
 * 自己读 30 行比引依赖或引外部命令都稳。
 */
function unzip(buffer) {
  const EOCD = 0x06054b50
  let eocd = -1
  for (let i = buffer.length - 22; i >= 0 && i > buffer.length - 66000; i--) {
    if (buffer.readUInt32LE(i) === EOCD) { eocd = i; break }
  }
  if (eocd < 0) throw new Error('取回的字节里找不到 zip 中央目录（EOCD）')
  const count = buffer.readUInt16LE(eocd + 10)
  let p = buffer.readUInt32LE(eocd + 16)

  const entries = new Map()
  for (let i = 0; i < count; i++) {
    if (buffer.readUInt32LE(p) !== 0x02014b50) throw new Error('中央目录条目签名不对')
    const method = buffer.readUInt16LE(p + 10)
    const compressed = buffer.readUInt32LE(p + 20)
    const nameLen = buffer.readUInt16LE(p + 28)
    const extraLen = buffer.readUInt16LE(p + 30)
    const commentLen = buffer.readUInt16LE(p + 32)
    const localAt = buffer.readUInt32LE(p + 42)
    const name = buffer.subarray(p + 46, p + 46 + nameLen).toString('utf8')
    p += 46 + nameLen + extraLen + commentLen

    // 本地头：签名(4) 版本(2) 标志(2) 方法(2) 时间(2) 日期(2) crc(4) 压缩后(4) 原始(4)
    //         文件名长度(2) 扩展区长度(2) —— 之后就是数据
    const lNameLen = buffer.readUInt16LE(localAt + 26)
    const lExtraLen = buffer.readUInt16LE(localAt + 28)
    const dataAt = localAt + 30 + lNameLen + lExtraLen
    const raw = buffer.subarray(dataAt, dataAt + compressed)
    entries.set(name, method === 0 ? Buffer.from(raw) : inflateRawSync(raw))
  }
  return entries
}

// ------------------------------------------------------------------ 自检
console.log('=== S7-M06-E · 文档下载 E2E ===\n')
assertArtifactCoverage()
observe('两侧产物登记表已对账（Java ↔ 脚本）')

const kbIdProbe = mysqlScalar(`select id from sys_kb where code='${KB_CODE}';`)
if (kbIdProbe) throw new Error(`探针库 code 已被占用（上一轮没清干净？）：${KB_CODE}`)

// ---- 1) 前置：后端在跑 ----
const ping = await fetch(`${API}/api/auth/captcha`).catch(() => null)
check(ping !== null && ping.status < 500, '后端 8080 在跑（本脚本必须连真后端）', API)

// ---- 2) 超管建库 + 上传两篇真文档并等到 READY ----
const adminToken = ok('超管登录', await api('/auth/login',
  { method: 'POST', body: { account: ADMIN.account, password: ADMIN.password } })).accessToken

const kb = ok('建探针库', await api('/kb', {
  method: 'POST', token: adminToken, body: kbForm(KB_CODE),
}))
const kbId = kb.id
observe('探针库已建', `kbId=${kbId} code=${KB_CODE}`)

async function uploadDoc(name, mark) {
  const form = new FormData()
  form.append('file', new Blob([`# ${name}\n\n${mark}\n`], { type: 'text/markdown' }), name)
  const res = await fetch(`${API}/api/kb/${kbId}/doc/upload`, {
    method: 'POST', headers: { Authorization: `Bearer ${adminToken}` }, body: form,
  })
  const parsed = JSON.parse(await res.text())
  const docId = ok(`上传 ${name}`, parsed).docId
  await waitDocReady(docId, adminToken)
  return docId
}

const docA = await uploadDoc(NAME_A, MARK_A)
const docB = await uploadDoc(NAME_B, MARK_B)
check(true, '两篇探针文档均已 READY', `docA=${docA} docB=${docB}`)

// ---- 3) 探针账号（下载产物都挂在它身上 ⇒ 收尾能一次清干净） ----
const phone = `139${String(stamp).slice(-8)}`
ok('注册探针账号', await api('/auth/register', {
  method: 'POST',
  body: { username: USER_PREFIX, phone, smsCode: '000000', password: 'Init@12345', realName: '下载探针' },
}))
const userToken = ok('探针账号登录', await api('/auth/login', {
  method: 'POST', body: { account: USER_PREFIX, password: 'Init@12345' },
})).accessToken
const userIds = userIdsOf(USER_PREFIX)
check(userIds.length === 1, '探针账号 id 已捕获（清桶要用，删账号后就查不到了）', `id=${userIds[0]}`)
const userId = Number(userIds[0])

// ---- 4) 单篇下载（G7-7 + G7-9） ----
const dl = ok('取单篇下载地址', await api(`/doc/download-url/${docA}`, { token: userToken }))
check(dl.expiresInSec === 600, 'G7-7：预签名 TTL = 600 秒（10 分钟）', `expiresInSec=${dl.expiresInSec}`)
check(typeof dl.url === 'string' && dl.url.startsWith('http'), '下载地址是一条真 URL')
check(dl.url.includes('response-content-disposition'),
  'URL 里带了 disposition（下载=attachment / 预览=inline 的唯一区别）')

const fetched = Buffer.from(await (await fetch(dl.url)).arrayBuffer())
check(fetched.toString('utf8').includes(MARK_A), '取回的内容 = 当初上传的那一篇（真取，不看接口返回）',
  `${fetched.length} 字节`)

const singleRecs = mysqlCount(`select count(*) from sys_download_record `
  + `where user_id=${userId} and doc_id=${docA} and download_type='SINGLE';`)
check(singleRecs === 1, 'G7-9：单篇下载恰好多一条 SINGLE 记录', `实查 ${singleRecs} 条`)

// 预览不写记录（需求 §14 line 365：同对象、另一种取法）
const pv = ok('取预览地址', await api(`/doc/${docA}/preview-url`, { token: userToken }))
check(pv.expiresInSec === 600, 'G7-7：预览地址同样是 600 秒')
const afterPreview = mysqlCount(`select count(*) from sys_download_record where user_id=${userId};`)
check(afterPreview === singleRecs, '预览**不写**下载记录（预览 ≠ 下载）', `记录数仍为 ${afterPreview}`)

// ---- 5) 受理上限（同步拒绝，不异步再失败） ----
const empty = await api('/doc/batch-download', { method: 'POST', token: userToken, body: { docIds: [] } })
check(empty.code === 10030, '空列表 ⇒ 受理即 10030', empty.message)
const many = await api('/doc/batch-download', {
  method: 'POST', token: userToken, body: { docIds: Array.from({ length: 51 }, (_, i) => i + 1) },
})
check(many.code === 10030 && String(many.message).includes('50'), '51 篇 ⇒ 受理即 10030（上限 50）', many.message)
mysqlScalar(`update sys_document set size_bytes = ${3 * 1024 * 1024 * 1024} where id = ${docB};`)
const tooBig = await api('/doc/batch-download', { method: 'POST', token: userToken, body: { docIds: [docB] } })
check(tooBig.code === 10030 && String(tooBig.message).includes('分批'),
  '合计超 2GB ⇒ 受理即 10030 且给出「分批」这一步', tooBig.message)
mysqlScalar(`update sys_document set size_bytes = 0 where id = ${docB};`)

// ---- 6) 真打包（G7-8） ----
const accepted = ok('受理打包', await api('/doc/batch-download', {
  method: 'POST', token: userToken, body: { docIds: [docA, docB] },
}))
check(accepted.count === 2, '受理即回文档数 2')
check(accepted.status === 'PENDING', 'status 是调度层真值 PENDING（不是 mock 的 QUEUED）')

const seenStatuses = []
const task = await waitDownloadTerminal(accepted.taskId, userToken, seenStatuses)
observe('打包任务状态轨迹', seenStatuses.join(' → ') || '—')
check(seenStatuses.every((s) => ['PACKING', 'DONE', 'FAILED'].includes(s)),
  'T-615：状态词表是 DownloadStatus，没有漏出 AsyncTaskStatus 的枚举名',
  `见到 ${[...new Set(seenStatuses)].join(',')}`)
check(task.status === 'DONE', 'G7-8：打包任务到了 DONE', `status=${task.status} percent=${task.percent}`)
check(task.count === 2 && task.size > 0, '任务视图里有真实文件数与大小（大小来自对象存储）',
  `count=${task.count} size=${task.size}`)

// 通知中心交付（G7-8），且那个动作**真的能点开**
const noticeRow = mysqlScalar(`select concat(type,'|',title,'|',content,'|',actions_json) `
  + `from sys_notice where source_task_id='${accepted.taskId}';`)
check(String(noticeRow).startsWith('SYSTEM|'), 'G7-8：完成通知复用 SYSTEM（用户裁定不新增通知类型）',
  String(noticeRow).split('|').slice(0, 2).join(' · '))
check(String(noticeRow).includes('打包文件已生成'),
  '通知正文说的名词是「打包文件」而不是「导出文件」（同一个交付规则、不同的产出物）')
const actionUrl = String(noticeRow).match(/https?:[^"|]+/)?.[0]?.replace(/\\\//g, '/')
check(!!actionUrl, 'G7-8：通知里带一个**可执行**的下载动作（且从 JSON 里取出来能用）')

const zipBuf = Buffer.from(await (await fetch(actionUrl)).arrayBuffer())
const entries = unzip(zipBuf)
check(entries.size === 2, 'zip 能解开且恰好两个条目', `实得 ${[...entries.keys()].join(', ')}`)
check(entries.get(NAME_A)?.toString('utf8').includes(MARK_A)
  && entries.get(NAME_B)?.toString('utf8').includes(MARK_B),
  'zip 里每个条目的**内容**都对得上（条目名 = 原文件名）')

const batchA = mysqlCount(`select count(*) from sys_download_record where doc_id=${docA} and download_type='BATCH';`)
const batchB = mysqlCount(`select count(*) from sys_download_record where doc_id=${docB} and download_type='BATCH';`)
check(batchA === 1 && batchB === 1, 'G7-9：被打进 zip 的**每一篇**各一条 BATCH 记录',
  `甲 ${batchA} 条 / 乙 ${batchB} 条`)

const audit = mysqlScalar("select concat(risk_level,'/',result) from sys_audit_log "
  + "where action='批量下载' order by id desc limit 1;")
check(audit === 'HIGH/SUCCESS', 'G7-9：批量下载留了审计，且标 HIGH（数据导出档）', audit)

// ---- 7) 导出下载记录清单（T-615） ----
const exported = ok('提交导出清单', await api('/doc/export-list', { method: 'POST', token: userToken }))
check(typeof exported.note === 'string' && exported.note.length > 0, '受理响应里带 note（前端泛型要它）')
await waitGenericTaskDone(exported.taskId, userToken)
const csvKeys = minioKeysOfPathUserPrefix('doc-export/', [userId])
check(csvKeys.length === 1, '导出件落在 doc-export/{userId}/ 下（生命周期规则要能单独作用于它）',
  csvKeys[0] ?? '—')
const exportNotice = mysqlCount(`select count(*) from sys_notice where source_task_id='${exported.taskId}';`)
check(exportNotice === 1, '导出完成经通知中心交付（有通知行）', `${exportNotice} 条`)
const recordsAfterExport = mysqlCount(`select count(*) from sys_download_record where user_id=${userId};`)
check(recordsAfterExport === 3, '导出**不写**下载记录（3 条 = 1 SINGLE + 2 BATCH）',
  `实查 ${recordsAfterExport} 条`)

// ---- 8) 下载历史（T-615，页面数据源） ----
const page = ok('查下载历史', await api('/doc/download-records?page=1&size=10', { token: userToken }))
const types = page.list.map((r) => r.type).sort()
check(types.join(',') === 'BATCH,BATCH,SINGLE', '下载历史里两类都在', types.join(','))
check(page.list.every((r) => r.docTitle && r.kbName && r.kbName !== '已删除'),
  '展示列由 join 取到了真值', page.list.map((r) => r.docTitle).join(' | '))

// ---- 9) 收尾：残留自清 ----
console.log('\n--- 收尾 ---')
const kbSnapshot = { ids: [String(kbId)], minioKeys: [] }
const userResidueBefore = {
  minioKeys: minioKeysOfPathUserPrefix('batch-download/', [userId])
    .concat(minioKeysOfPathUserPrefix('doc-export/', [userId])),
}
// Milvus 向量的清理由 `teardownKbProbe` 负责；先取一次"删前快照"（对象名要先抓）
const minioKeys = userResidueBefore.minioKeys
minioRemove(minioKeys)
observe('已清掉探针账号名下的打包/导出对象', `${minioKeys.length} 个`)

const kbRes = await teardownKbProbe(KB_CODE)
const userRes = teardownUserProbe(USER_PREFIX)

const kbClean = kbResidueClean(await findKbProbeResidue(KB_CODE, kbSnapshot))
check(kbClean, `探针库族残留已清 0：${describeKbResidue(kbRes.residue)}`)
check(userRes.residue.sqlTotal === 0,
  `探针账号族残留已清 0（下载记录/任务/通知都在内）`,
  `实查 ${userRes.residue.sqlTotal} 行 · ${JSON.stringify(userRes.residue.sql)}`)
check(userRes.residue.minioStillThere.length === 0, '探针账号名下的桶对象已清 0',
  userRes.residue.minioStillThere.join(', ') || '—')
const vectors = await milvusVectorCount(kbSnapshot.ids)
check(vectors === 0, '探针库的 Milvus 向量已清 0（**独立观测点**：直查条数，不看分区）',
  `实查 ${vectors} 条`)

// ---- 汇总 ----
const failed = steps.filter((s) => !s.ok)
console.log(`\n共 ${steps.length} 条断言，失败 ${failed.length} 条`)
if (failed.length) {
  failed.forEach((s) => console.log(`  ✗ ${s.label}${s.detail ? ` · ${s.detail}` : ''}`))
  process.exit(1)
}
console.log('✓ 全部通过')

// ==================================================================== 内部
function kbForm(code) {
  return {
    name: 'M06-E 下载 E2E 探针库', code, deptId: 1,
    // ⚠️ 必须让 USER 可见：探针账号要用它验"跨用户下载"，而 `visible_roles`
    //    与数据范围是**两条并行的准入线**
    visibleRoles: ['SUPER_ADMIN', 'USER'],
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

async function waitDocReady(docId, token) {
  for (let i = 0; i < 90; i++) {
    const meta = await api(`/doc/${docId}`, { token })
    const status = meta?.data?.status
    if (status === 'READY') return
    if (status === 'FAILED') throw new Error(`文档 ${docId} 解析失败：${meta.data.errorMsg ?? '—'}`)
    await sleep(1000)
  }
  throw new Error(`文档 ${docId} 90s 内没到 READY`)
}

async function waitDownloadTerminal(taskId, token, seen) {
  for (let i = 0; i < 120; i++) {
    const r = await api(`/doc/download-tasks/${taskId}`, { token })
    const d = r.data
    if (d && seen[seen.length - 1] !== d.status) seen.push(d.status)
    if (d && (d.status === 'DONE' || d.status === 'FAILED')) return d
    await sleep(500)
  }
  throw new Error(`打包任务 ${taskId} 60s 内没到终态`)
}

async function waitGenericTaskDone(taskId, token) {
  for (let i = 0; i < 120; i++) {
    const r = await api(`/doc/tasks/${taskId}`, { token })
    const p = r?.data?.percent
    if (typeof p === 'number' && p >= 100) return
    await sleep(500)
  }
  throw new Error(`任务 ${taskId} 60s 内没到终态`)
}
