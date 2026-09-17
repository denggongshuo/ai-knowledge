#!/usr/bin/env node
/**
 * S4 · M05-B 页面级 E2E：**解析可见性 + 未识别表格清单**（`T-502` / `T-511`）。
 *
 * <h3>覆盖的判据（计划 §6+7 的 21 / 22）</h3>
 * <ul>
 *   <li><b>G4-3（后半收口 · S4-M05-C3）</b> —— 上传真 MD → 列表状态列「已就绪」（域内真值 `READY`）；
 *       上传 GBK 文本 → 「解析失败」且原因**具体可执行**（含编码名与下一步动作）；</li>
 *   <li><b>G4-4（质量值收口）</b> —— 状态列与质量列同时渲染，且各自反映独立的事实；</li>
 *   <li><b>G4-11</b> —— 顶部告警条由真接口 `GET /kb/{kbId}/parse-issues` 驱动，
 *       文案按 `docId` **去重计数**（一份 15 页的扫描件是「1 篇」，不是「15 篇」），
 *       并给出**真实页码**与**具体原因**。</li>
 * </ul>
 *
 * <h3>为什么这条 E2E 必须存在（而不是只靠后端用例）</h3>
 * 告警条的"篇数"是**前端算的**：后端返回的行粒度是 (文档, 页码)，
 * 页面上直接 `issues.length` 就会把 1 篇说成 15 篇 —— 这正是本批要修的那个坑
 * （`KbUploadView.vue` 的 `issueDocCount`）。后端用例测不到它，
 * 只有真页面能测到。
 *
 * <h3>样本依赖</h3>
 * 扫描件那一段需要 `docs/测试文件/健康生活方式（指导手册）.pdf`（不入库，21.8MB 无文本层）。
 * **缺失时该段显式跳过并打印原因**，其余判据照跑 —— 与后端用例 `assumeSample` 同一纪律：
 * 静默跳过等于假绿，写明跳过才是诚实的。
 *
 * <h3>自清纪律</h3>
 * 固定 code `kb_e2e_parse_probe` 真建库、真传文件；**开头与结尾各清一次**（幂等）。
 *
 * <h3>前置</h3>
 * <pre>
 *   后端 8080：cd ai_knowledge_web && mvn -o spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
 *   前端 5174：cd ai_knowledge_app && VITE_USE_MOCK=false npm run dev -- --host 127.0.0.1 --port 5174
 *   运行：node scripts/e2e-doc-parse.mjs --base=http://127.0.0.1:5174
 * </pre>
 */
import { spawn, spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const REPO_ROOT = resolve(ROOT, '..')
const arg = (name, fallback) => (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://127.0.0.1:5174')
const API = arg('api', 'http://127.0.0.1:8080')

const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = `${REPO_ROOT.replace(/\\/g, '/')}/temp/edge-e2e-parse`
const OUT_DIR = `${REPO_ROOT.replace(/\\/g, '/')}/temp/e2e-parse`
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9341)

/** 真实扫描件样本（不入库）。 */
const SCAN_SAMPLE = `${REPO_ROOT.replace(/\\/g, '/')}/docs/测试文件/健康生活方式（指导手册）.pdf`

const PROBE = { account: 'admin', password: 'Admin@123' }
const PROBE_CODE = 'kb_e2e_parse_probe'
const PROBE_NAME = 'M05-B 解析探针库'

const steps = []
function check(ok, label, detail = '') {
  steps.push({ ok: !!ok, label, detail })
  console.log(`${ok ? '✓' : '✗'} ${label}${detail ? ` · ${detail}` : ''}`)
  return !!ok
}
function observe(label, detail = '') {
  console.log(`… ${label}${detail ? ` · ${detail}` : ''}`)
}
function skip(label, reason) {
  steps.push({ ok: true, label: `（跳过）${label}`, detail: reason })
  console.log(`− ${label} · 跳过：${reason}`)
}

// ---------------------------------------------------------------- 数据库直连（仅自清 / 计数）
function mysqlScalar(sql) {
  const envFile = `${REPO_ROOT.replace(/\\/g, '/')}/ai_knowledge_web/.env`
  const script = `
set -a; . ${JSON.stringify(envFile)}; set +a
docker exec kb-mysql mysql --default-character-set=utf8mb4 -u"$MYSQL_USERNAME" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -N -e ${JSON.stringify(sql)} 2>/dev/null
`.trim()
  const r = spawnSync('bash', ['-c', script], { cwd: REPO_ROOT, encoding: 'utf8' })
  if (r.status !== 0) throw new Error(`mysql 查询失败（status=${r.status}）：${r.stderr || r.stdout}`)
  return (r.stdout ?? '').trim()
}

/**
 * 删 MinIO 里的对象（**只删库行是不够的**）。
 *
 * <p>不删的后果是实测过的（`S4-M05-C2`，2026-09-16）：两个 E2E 各跑一轮就在桶里留下
 * 9 个对象，而 `GET /kb/storage-quota` 会**全桶递归列举**（`KbService#minioUsedBytes`）——
 * 攒到上千个之后慢到读超时，把毫不相干的 `KbWriteFlowTest` 拖红。
 * 那是最费时间的假红形态：错误的信号指向错误的地方。
 *
 * <p>对象名必须在**删行之前**取出来。`mysql -N` 的空值打印成字面量 `NULL`，要滤掉。
 *
 * <h3>⚠️ 必须把路径塞进 `sh -c` 里，不能当裸参数传给 `docker exec`</h3>
 * <p>Git Bash 会把形似 Unix 绝对路径的**裸参数**改写成 Windows 路径
 * （`/data/…` → `C:/Program Files/Git/data/…`，MSYS 的自动转换）。
 * 于是 `rm -rf` 删的是一个不存在的路径，而 `-f` 让它**静默返回 0** ——
 * 本轮实测就是这个形态：命令报成功，对象一个没少。
 * 塞进 `sh -c '...'` 的整串里就绕开了转换（容器内的 sh 才是解析者）。
 */
function minioRemove(rawObjectKeys) {
  const keys = (rawObjectKeys ?? '')
    .split('\n')
    .map((s) => s.trim())
    .filter((s) => s && s !== 'NULL')
  if (keys.length === 0) return
  const paths = keys.map((k) => `'/data/rag-documents/${k}'`).join(' ')
  spawnSync('bash', ['-c', `MSYS_NO_PATHCONV=1 docker exec kb-minio sh -c "rm -rf ${paths}"`],
    { cwd: REPO_ROOT, encoding: 'utf8' })
}

function purgeProbe() {
  // ① 先删对象存储里的文件 —— 删了行就再也查不到对象名了
  const probeDocs = `from sys_document d join sys_kb k on k.id=d.kb_id where k.code='${PROBE_CODE}'`
  minioRemove(mysqlScalar(`select d.storage_path ${probeDocs};`))
  minioRemove(mysqlScalar(`select d.parse_path ${probeDocs};`))
  // ①' Milvus 里的向量 —— **必须在删 sys_kb 行之前做**（下面按 probe kb id 删，删完就查不到了）。
  //     `S4-M05-C3` 起探针文档也会被向量化（T-508）；不删就是"文档没了、向量还在"的幽灵数据，
  //     检索得到而 MySQL 里查无此文 —— 比反向更难查。这是 `汇总.md` §3 #79 那条教训的**第 N 次复发**：
  //     新增持久化产物时，没人回头查所有清理函数。
  milvusRemoveByProbeKb()
  // ② 再删库行
  mysqlScalar(
    `delete i from sys_parse_issue i join sys_document d on d.id=i.doc_id join sys_kb k on k.id=d.kb_id where k.code='${PROBE_CODE}';
      delete c from sys_chunk c join sys_document d on d.id=c.doc_id join sys_kb k on k.id=d.kb_id where k.code='${PROBE_CODE}';
      delete d from sys_document d join sys_kb k on k.id=d.kb_id where k.code='${PROBE_CODE}';` +
      `delete g from sys_kb_grant g join sys_kb k on k.id=g.kb_id where k.code='${PROBE_CODE}';` +
      `delete from sys_kb where code='${PROBE_CODE}';`,
  )
}
/** 探针库的分片行数 —— `S4-M05-C1` 起 `sys_chunk` 也要跟着清，否则留下**孤儿块**。 */
const chunkRowsOfProbe = () =>
  Number(mysqlScalar(`select count(*) from sys_chunk c join sys_kb k on k.id=c.kb_id where k.code='${PROBE_CODE}';`))

/**
 * 探针库在 **Milvus** 里残留的向量条数（`S4-M05-C3` / `T-508` 起会 > 0）。
 *
 * <p>走 Milvus 的 REST v2（`/v2/vectordb/…`，2.4+ 自带，零新增依赖）；
 * **必须用 `entities/query` 的 `count(*)`，不能用 `get_stats` 的 `rowCount`** ——
 * 后者只统计**已 flush** 的数据，而写入路径刻意不 flush（见 `MilvusChunkWriter` 类 javadoc），
 * 拿它判残留会恒得 0，等于没查。
 */
const milvusVectorRowsOfProbeKb = async (ids) => {
  if (!ids || ids.length === 0) return 0
  // ⚠️ **删除的可见性有几秒延迟**（实测：`deleteCount=9` 之后立刻 count 仍得 9，过几秒才归 0）。
  //    只查一次会把"刚删完"读成"没删掉" → 假失败。故轮询到 0 或超时为止。
  for (let i = 0; i < 6; i++) {
    const out = spawnSync('bash', ['-c',
      `curl -s -m 15 -X POST http://127.0.0.1:19530/v2/vectordb/entities/query ` +
      `-H 'Content-Type: application/json' ` +
      `-d '{"collectionName":"knowledge_vectors","filter":"kb_id in [${ids.join(',')}]","outputFields":["count(*)"]}'`,
    ], { cwd: REPO_ROOT, encoding: 'utf8' }).stdout ?? ''
    const n = Number((out.match(/"count\(\*\)":(\d+)/) ?? [])[1] ?? -1)
    if (n === 0) return 0
    if (i < 5) await sleep(2000)
    else return n
  }
  return -1
}

/** 探针库的 id 列表（**要在 purgeProbe 之前取**：行删了就查不到 id 了）。 */
const probeKbIds = () => (mysqlScalar(`select id from sys_kb where code='${PROBE_CODE}';`) ?? '')
  .split('\n').map((s) => s.trim()).filter(Boolean)

/** 删掉探针库的全部向量 + **丢掉它的代分区**。**调用必须在删 `sys_kb` 行之前。** */
function milvusRemoveByProbeKb() {
  const ids = probeKbIds()
  if (ids.length === 0) return
  spawnSync('bash', ['-c',
    `curl -s -m 15 -X POST http://127.0.0.1:19530/v2/vectordb/entities/delete ` +
    `-H 'Content-Type: application/json' ` +
    `-d '{"collectionName":"knowledge_vectors","filter":"kb_id in [${ids.join(',')}]"}'`,
  ], { cwd: REPO_ROOT, encoding: 'utf8' })
  milvusDropProbePartitions(ids)
}

/**
 * 丢掉探针库的**代分区**。
 *
 * ⚠️ 为什么必须有这一步：`T-406` 起向量按 `kb{id}_g{代}` 分区存放，而上面的
 * `entities/delete` 只删**点**、不删**容器** —— 于是每次跑完都留下几个空分区。
 * 这是本项目「新增持久化产物、清理函数不认识它」的**第三次**复现
 * （前两次：`sys_chunk` 新表、Milvus 向量本身），所以这次把注释也写在这里。
 *
 * ⚠️ 顺序固定：**delete 实体 → release → drop**。裸 drop 会失败，
 * 服务端原话是 `partition cannot be dropped, partition is loaded, please release it first`
 * （步 0 探针在 Java SDK 那侧看到的是同一个原因，但只给了 65535）。
 */
function milvusDropProbePartitions(ids) {
  const listOut = spawnSync('bash', ['-c',
    `curl -s -m 15 -X POST http://127.0.0.1:19530/v2/vectordb/partitions/list ` +
    `-H 'Content-Type: application/json' -d '{"collectionName":"knowledge_vectors"}'`,
  ], { cwd: REPO_ROOT, encoding: 'utf8' }).stdout ?? ''
  let names = []
  try { names = JSON.parse(listOut).data ?? [] } catch { return }
  for (const id of ids) {
    for (const name of names.filter((n) => n.startsWith(`kb${id}_g`))) {
      spawnSync('bash', ['-c',
        `curl -s -m 15 -X POST http://127.0.0.1:19530/v2/vectordb/partitions/release ` +
        `-H 'Content-Type: application/json' ` +
        `-d '{"collectionName":"knowledge_vectors","partitionNames":["${name}"]}'`,
      ], { cwd: REPO_ROOT, encoding: 'utf8' })
      spawnSync('bash', ['-c',
        `curl -s -m 15 -X POST http://127.0.0.1:19530/v2/vectordb/partitions/drop ` +
        `-H 'Content-Type: application/json' ` +
        `-d '{"collectionName":"knowledge_vectors","partitionName":"${name}"}'`,
      ], { cwd: REPO_ROOT, encoding: 'utf8' })
    }
  }
}
const kbCount = () => Number(mysqlScalar('select count(*) from sys_kb;'))
const docRowsOfProbe = () =>
  Number(mysqlScalar(`select count(*) from sys_document d join sys_kb k on k.id=d.kb_id where k.code='${PROBE_CODE}';`))
const issueRowsOfProbe = () =>
  Number(mysqlScalar(`select count(*) from sys_parse_issue i join sys_kb k on k.id=i.kb_id where k.code='${PROBE_CODE}';`))

/** 探针库里某文档的关键列（`title|status|error_msg|table_total|table_parsed|parse_path`）。 */
function docRowOf(title) {
  const raw = mysqlScalar(
    `select concat_ws('|', d.title, d.status, coalesce(d.error_msg,''), d.table_total, d.table_parsed, coalesce(d.parse_path,'')) ` +
      `from sys_document d join sys_kb k on k.id=d.kb_id where k.code='${PROBE_CODE}' and d.title='${title}' order by d.id desc limit 1;`,
  )
  return raw ? raw.split('|') : null
}

/**
 * 探针库里某文档的分片统计（`chunk_count|顶层块数|全部块数`）。
 *
 * 用于 `S4-M05-C1` 的判据 15：`chunk_count` 从「恒 0」变成真值，
 * 且口径必须是**顶层块数**（D-5 §1.5 —— 表格子块不计入）。
 */
function chunkStatOf(title) {
  const raw = mysqlScalar(
    `select concat_ws('|', d.chunk_count, ` +
      `(select count(*) from sys_chunk c where c.doc_id=d.id and c.parent_id is null), ` +
      `(select count(*) from sys_chunk c where c.doc_id=d.id)) ` +
      `from sys_document d join sys_kb k on k.id=d.kb_id where k.code='${PROBE_CODE}' and d.title='${title}' order by d.id desc limit 1;`,
  )
  return raw ? raw.split('|') : null
}

// ---------------------------------------------------------------- 直连后端（建库 / 兜底）
async function api(path, { method = 'GET', body, token } = {}) {
  const res = await fetch(`${API}/api${path}`, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    /* 非 JSON 保留 text */
  }
  return { status: res.status, json, text }
}

// ---------------------------------------------------------------- CDP
let ws
let msgId = 0
const pending = new Map()
const ledger = []
const byRequestId = new Map()
const consoleMsgs = []

function send(method, params = {}, sessionId) {
  const id = ++msgId
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }))
  })
}
const headerOf = (headers, name) => {
  if (!headers) return undefined
  const key = Object.keys(headers).find((k) => k.toLowerCase() === name)
  return key ? headers[key] : undefined
}
async function evaluate(expression) {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (r.exceptionDetails) throw new Error(`页面脚本抛错：${r.exceptionDetails.text ?? ''} ${r.exceptionDetails.exception?.description ?? ''}`)
  return r.result?.value
}
async function waitFor(expression, { timeout = 15000, interval = 150, label = expression } = {}) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    try {
      const v = await evaluate(expression)
      if (v) return v
    } catch {
      /* 页面可能正在导航 */
    }
    await sleep(interval)
  }
  // 超时打现场（同 e2e-doc-upload：只报"等超时"会让排查从零开始）
  const snapshot = await evaluate(`JSON.stringify({
    hash: location.hash,
    queue: [...document.querySelectorAll('.file-row')].map((r) => window.__t.norm(r.textContent).slice(0, 200)),
    issueBar: window.__t.text('.issue-bar'),
    docs: window.__t.docRows(),
  })`).catch(() => '(取现场失败)')
  const consoleTail = consoleMsgs.slice(-8).map((m) => `    [${m.level}] ${m.text.slice(0, 160)}`).join('\n')
  const ledgerTail = ledger.map((e) => `    ${e.method} ${e.url.replace(BASE, '')} → ${e.status}`).join('\n')
  throw new Error(
    `等待超时（${timeout}ms）：${label}\n  现场：${snapshot}` +
      (consoleTail ? `\n  console 尾部：\n${consoleTail}` : '') +
      (ledgerTail ? `\n  接口台账：\n${ledgerTail}` : ''),
  )
}
async function waitForApi(match, { timeout = 60000, label = '接口' } = {}) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    const hit = ledger.find((e) => match(e) && e.status > 0 && e.method !== 'OPTIONS')
    if (hit) {
      const bodyDeadline = Date.now() + 3000
      while (!hit.bodyPromise && Date.now() < bodyDeadline) await sleep(50)
      if (hit.bodyPromise) await hit.bodyPromise.catch(() => {})
      return hit
    }
    await sleep(120)
  }
  throw new Error(`未观察到 ${label} 的响应（${timeout}ms）；已记录：\n${ledger.map((e) => `  ${e.method} ${e.url} → ${e.status}`).join('\n')}`)
}
function assertBizOk(entry, label) {
  let biz = null
  try {
    biz = JSON.parse(entry.body ?? '')
  } catch {
    throw new Error(`${label}：响应体不是 JSON（HTTP ${entry.status} · ${entry.method} ${entry.url}）`)
  }
  if (biz.code !== 0) throw new Error(`${label}：HTTP ${entry.status} 但业务失败 code=${biz.code} · ${biz.message}`)
  return `HTTP ${entry.status} · code=0 · traceId=${headerOf(entry.headers, 'x-trace-id') ?? '—'}`
}

const PAGE_TOOLS = `
window.__t = {
  norm: (s) => (s || '').replace(/\\s+/g, ' ').trim(),
  text: (sel) => window.__t.norm(document.querySelector(sel)?.textContent),
  rows: () => [...document.querySelectorAll('.file-row')],
  rowOf: (name) => window.__t.rows().find((r) => window.__t.norm(r.querySelector('.file-name')?.textContent) === window.__t.norm(name)) || null,
  tagOf: (name) => { const r = window.__t.rowOf(name); return r ? window.__t.norm(r.querySelector('.el-tag')?.textContent) : null },
  msgOf: (name) => { const r = window.__t.rowOf(name); return r ? window.__t.norm(r.querySelector('.err-msg, .file-err, .item-error')?.textContent) : null },
  docRows: () => [...document.querySelectorAll('.el-table__body .el-table__row')].map((r) =>
    [...r.querySelectorAll('td')].map((td) => window.__t.norm(td.textContent))),
  docRowOf: (title) => window.__t.docRows().find((cells) => cells.some((c) => c.includes(title))) || null,
  issueBar: () => window.__t.text('.issue-bar'),
  messages: () => [...document.querySelectorAll('.el-message')].map((m) => window.__t.norm(m.textContent)),
}
'ok'
`

async function navigate(hash) {
  await send('Page.navigate', { url: `${BASE}/?boot=${Date.now()}#${hash}` })
}

/**
 * 通过 CDP 给 el-upload 的隐藏 input 塞一个真文件。
 *
 * @param path **绝对路径**（样本目录与临时产物目录都在用，所以不拼前缀）。
 *             ⚠️ 路径必须真实存在：CDP 的 {@code DOM.setFileInputFiles} 传错路径时
 *             页面拿到的是 {@code net::ERR_FILE_NOT_FOUND}，表现为队列项
 *             「解析失败 · 网络异常，请检查网络连接重试」—— 看起来像后端挂了，
 *             实际是**没找到文件**（本脚本第一版就踩到，故显式记下来）。
 */
async function setFile(path) {
  const doc = await send('DOM.getDocument', { depth: -1 })
  const node = await send('DOM.querySelector', { nodeId: doc.root.nodeId, selector: '.upload-page .el-upload input[type="file"]' })
  if (!node.nodeId) throw new Error('找不到上传页的文件 input')
  await send('DOM.setFileInputFiles', { nodeId: node.nodeId, files: [path] })
}

/** 上传一个文件（绝对路径）并等它落进列表。 */
async function uploadAndAwaitRow(path, title, { timeout = 120000 } = {}) {
  const before = ledger.filter((e) => e.url.includes('/doc/upload')).length
  await setFile(path)
  await waitForApi(
    (e) => e.url.includes('/doc/upload') && ledger.filter((l) => l.url.includes('/doc/upload')).length > before,
    { label: `POST doc/upload（${title}）`, timeout },
  )
  const entry = ledger.filter((e) => e.url.includes('/doc/upload')).at(-1)
  // ⚠️ 必须等 bodyPromise —— 台账项在 `loadingFinished` 之前就进 ledger 了，
  // 这里立刻取 `.body` 会拿到 null（首轮实测：`响应体不是 JSON`，看着像接口坏了）
  if (entry.bodyPromise) await entry.bodyPromise.catch(() => {})
  check(true, `上传受理：${title}`, assertBizOk(entry, `upload(${title})`))
  // 前端轮询到终态后会刷新列表 —— 等那一行出现，就说明解析已落库
  await waitFor(`!!window.__t.docRowOf(${JSON.stringify(title)})`, { label: `列表出现「${title}」`, timeout })
}

async function main() {
  if (!existsSync(EDGE)) throw new Error(`找不到 Edge：${EDGE}`)
  rmSync(PROFILE, { recursive: true, force: true })
  mkdirSync(OUT_DIR, { recursive: true })

  purgeProbe()
  const before = kbCount()
  check(before === 8, '准备：sys_kb 回到 8 行（幂等清理探针库）', `行数=${before}`)

  // ---- 造三个真文件：正常 MD / GBK 文本 / （可选）扫描件 ----
  const mdName = 'E2E解析探针.md'
  const mdBytes = Buffer.from(
    '# E2E 解析探针\n\n型号 A-100，产品名称 智能网关，单价 ￥1,200，交期 7 天。\n',
    'utf8',
  )
  writeFileSync(`${OUT_DIR}/${mdName}`, mdBytes)

  // GBK 字节：`你好` 的 GBK 编码是 C4E3 BAC3，**不是合法 UTF-8** ——
  // 这正是《03》§4.2 举的那个例子。Node 没有 gbk 编码器，所以直接写字节。
  const gbkName = 'E2E编码探针.txt'
  const gbkUnit = Buffer.from([0xc4, 0xe3, 0xba, 0xc3, 0x2c, 0x0a]) // 你好,\n
  const gbkBytes = Buffer.concat(Array.from({ length: 60 }, () => gbkUnit))
  writeFileSync(`${OUT_DIR}/${gbkName}`, gbkBytes)

  const hasScan = existsSync(SCAN_SAMPLE)
  // 扫描件直接用样本原路径（不复制 21.8MB），故不造副本
  let scanSizeMb = 0
  if (hasScan) {
    const bytes = statSync(SCAN_SAMPLE)
    scanSizeMb = bytes.size / 1024 / 1024
    // 直接引用样本路径塞给 input，不复制（21.8MB 复制没必要）
  }

  const token = (await api('/auth/login', { method: 'POST', body: { ...PROBE, remember: true } })).json?.data?.accessToken
  if (!token) throw new Error('admin 登录失败')
  check(true, '准备：admin 登录，拿到 accessToken')

  const created = await api('/kb', {
    method: 'POST',
    token,
    body: {
      name: PROBE_NAME,
      code: PROBE_CODE,
      deptId: 1,
      visibleRoles: ['SUPER_ADMIN', 'USER'],
      secretLevel: 'INTERNAL',
      businessDomain: '研发 / 平台',
      description: '',
      customTags: [],
      splitPolicy: { preset: 'GENERAL', chunkSize: 512, overlap: 64, recursiveSplit: true, semanticSplit: false },
      tablePolicy: { atomicTable: true, dualRepresentation: true, repeatHeader: true, mergeCrossPage: true, ocrTable: true, rowLimit: 30, rowWindow: 5 },
      retrievalConfig: { mode: 'HYBRID', recallThreshold: 0.15, rerankTopK: 5, rerankEnabled: true, tableWeight: 1.2, identifierProtect: true },
    },
  })
  if (created.json?.code !== 0) throw new Error(`建探针库失败：${created.text}`)
  const kbId = created.json.data.id
  check(true, '准备：探针库已建（API 直建，创建向导由 e2e-kb-flow 覆盖）', `kbId=${kbId}`)

  const child = spawn(
    EDGE,
    ['--headless=new', `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${PROFILE}`, '--no-first-run', '--no-default-browser-check', '--disable-gpu', 'about:blank'],
    { stdio: 'ignore' },
  )

  let target = null
  for (let i = 0; i < 40 && !target; i++) {
    await sleep(250)
    try {
      const list = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`)).json()
      target = list.find((t) => t.type === 'page')
    } catch {
      /* 端口未起 */
    }
  }
  if (!target) throw new Error('连不上 Edge 的 CDP 端口')

  ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => {
    ws.onopen = resolve
    ws.onerror = () => reject(new Error('CDP WebSocket 连接失败'))
  })

  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data)
    if (msg.id) {
      const p = pending.get(msg.id)
      if (!p) return
      pending.delete(msg.id)
      if (msg.error) p.reject(new Error(`${msg.error.message}`))
      else p.resolve(msg.result)
      return
    }
    if (msg.method === 'Network.requestWillBeSent') {
      const { requestId, request } = msg.params
      if (!request.url.startsWith(`${BASE}/api/`)) return
      byRequestId.set(requestId, { method: request.method, postData: request.postData, reqHeaders: request.headers })
    }
    if (msg.method === 'Network.responseReceived') {
      const { requestId, response } = msg.params
      if (!response.url.startsWith(`${BASE}/api/`)) return
      const prev = byRequestId.get(requestId) ?? {}
      const entry = {
        requestId, url: response.url, status: response.status, method: prev.method ?? 'GET',
        postData: prev.postData, reqHeaders: prev.reqHeaders, headers: response.headers, body: null, bodyPromise: null,
      }
      ledger.push(entry)
      byRequestId.set(requestId, entry)
    }
    if (msg.method === 'Network.loadingFinished') {
      const entry = byRequestId.get(msg.params.requestId)
      if (!entry || !entry.url) return
      entry.bodyPromise = send('Network.getResponseBody', { requestId: msg.params.requestId })
        .then((r) => {
          entry.body = r.base64Encoded ? Buffer.from(r.body, 'base64').toString('utf8') : r.body
          return entry.body
        })
        .catch(() => null)
    }
    if (msg.method === 'Runtime.consoleAPICalled') {
      const { type, args } = msg.params
      if (type === 'warning' || type === 'error') consoleMsgs.push({ level: type, text: args.map((a) => a.value ?? a.description ?? '').join(' ') })
    }
    if (msg.method === 'Log.entryAdded') {
      const { level, text } = msg.params.entry
      if (level === 'warning' || level === 'error') consoleMsgs.push({ level, text })
    }
  }

  await send('Page.enable')
  await send('Runtime.enable')
  await send('Network.enable')
  await send('Log.enable')
  await send('Emulation.setDeviceMetricsOverride', { width: 1600, height: 1000, deviceScaleFactor: 1, mobile: false })

  await send('Page.navigate', { url: `${BASE}/#/login` })
  await waitFor(`!!document.querySelector('#app') && !!location.hash`, { label: '应用挂载', timeout: 30000 })
  await evaluate(`localStorage.setItem('ak_token', ${JSON.stringify(token)}); 'ok'`)

  try {
    // ==================== 1. 正常 MD：解析成功 → 已就绪（向量已入 Milvus） ====================
    await navigate(`/kb/${kbId}/upload`)
    await waitFor(`!!document.querySelector('.upload-page') && !!document.querySelector('.four-col')`, { label: '上传页渲染', timeout: 30000 })
    await evaluate(PAGE_TOOLS)

    await uploadAndAwaitRow(`${OUT_DIR}/${mdName}`, "E2E解析探针")

    const mdRow = await evaluate(`window.__t.docRowOf('E2E解析探针')`)
    check(Array.isArray(mdRow) && mdRow.length > 0, 'G4-3：文档列表出现该行（真刷新的列表）', mdRow ? `列数=${mdRow.length}` : '—')
    check(
      mdRow && mdRow[5] === '已就绪',
      'G4-3（后半收口）：正常 MD 状态列渲染「已就绪」（域内真值 READY —— 向量已入 Milvus、可被检索，S4-C3 起的终态）',
      `状态=${mdRow?.[5]}`,
    )
    check(
      mdRow && mdRow[6] === '无表格 / 图片',
      'G4-4：质量列如实为「无表格 / 图片」（MD 没有表格），与状态列**各自独立**',
      `质量=${mdRow?.[6]}`,
    )

    const mdChunks = chunkStatOf('E2E解析探针')
    check(
      mdRow && /^\d+$/.test(mdRow[4]) && Number(mdRow[4]) > 0,
      'T-503：分片数列显示**真值**而非占位「—」（此前 chunk_count 恒 0，本页只能显示「—」）',
      `分片数=${mdRow?.[4]}`,
    )
    check(
      mdChunks && Number(mdChunks[0]) > 0 && mdChunks[0] === mdChunks[1],
      'T-503：chunk_count 口径 = 顶层块数（D-5 §1.5：表格子块不计入）—— 销 汇总.md §3 #54',
      mdChunks ? `chunk_count=${mdChunks[0]} 顶层=${mdChunks[1]} 全部=${mdChunks[2]}` : '—',
    )

    const mdDb = docRowOf('E2E解析探针')
    check(
      mdDb && mdDb[1] === 'READY' && mdDb[5].startsWith('parsed/'),
      'T-502：解析产物真的落了 MinIO（parse_path = parsed/{kbId}/{docId}/parse.json）',
      mdDb ? `status=${mdDb[1]} parse_path=${mdDb[5]}` : '—',
    )

    // ==================== 2. GBK 文本：显式失败，原因可执行 ====================
    await uploadAndAwaitRow(`${OUT_DIR}/${gbkName}`, "E2E编码探针")

    const gbkRow = await evaluate(`window.__t.docRowOf('E2E编码探针')`)
    check(
      gbkRow && gbkRow[5] === '解析失败',
      'G4-3：GBK 文本状态列渲染「解析失败」（域内真值 FAILED）',
      `状态=${gbkRow?.[5]}`,
    )
    const gbkDb = docRowOf('E2E编码探针')
    check(
      gbkDb && gbkDb[1] === 'FAILED' && gbkDb[2].includes('GBK') && gbkDb[2].includes('UTF-8'),
      'G4-3：失败原因是**具体可执行**的（含编码名 + 「转存为 UTF-8 后重新上传」），不是「失败」两字',
      gbkDb ? gbkDb[2].slice(0, 80) : '—',
    )
    check(
      gbkDb && gbkDb[5] === '',
      'T-502：失败时不应有解析产物（parse_path 为空 —— 没有半成品当成果）',
      gbkDb ? `parse_path=「${gbkDb[5]}」` : '—',
    )

    // ==================== 3. 扫描件：告警条（G4-11 的核心） ====================
    if (!hasScan) {
      skip('扫描件告警条（G4-11）', `缺少样本 ${SCAN_SAMPLE}（不入库）`)
    } else {
      // 21.8MB：页面要先算 MD5（自研增量实现），再上传；解析在服务端毫秒级失败。
      // ⚠️ 直接塞样本绝对路径（不复制 21.8MB）—— setFile 收的就是绝对路径。
      await uploadAndAwaitRow(SCAN_SAMPLE, '健康生活方式（指导手册）', { timeout: 300000 })
      observe('扫描件上传', `体积 ${scanSizeMb.toFixed(1)}MB`)

      const scanRow = await evaluate(`window.__t.docRowOf('健康生活方式（指导手册）')`)
      check(
        scanRow && scanRow[5] === '解析失败',
        'G4-3（P0）：21.8MB 无文本层扫描件**失败**而非静默成功 —— 这是本项目最不能接受的一类故障',
        `状态=${scanRow?.[5]}`,
      )

      // 告警条：由真接口驱动，按 docId 去重。
      // ⚠️ 必须取**最后一次** /parse-issues，且判据要落在"这次响应"上：
      // 页面首次加载时就调过一次（那时还没上传，必然返回空）。
      // 用 `waitForApi`（返回**第一个**匹配项）+ 全局计数条件会拿到那条空响应 ——
      // 实测 `total=0` 且 `[].every(...)` 恒真，一条红一条**假绿**。
      // 所以这里等"**最后一条** /parse-issues 的 total>0"，判据直接读那条响应。
      const issuesEntry = await waitForApi(
        (e) => e.url.includes('/parse-issues') && Number(JSON.parse(e.body ?? '{}').data?.total ?? 0) > 0,
        { label: 'GET /parse-issues（上传扫描件后非空的那次）', timeout: 60000 },
      )
      check(true, 'G4-11：告警条数据来自真接口 GET /api/kb/:id/parse-issues（非空响应）',
        assertBizOk(issuesEntry, 'parse-issues'))
      const issueBody = JSON.parse(issuesEntry.body ?? '{}').data ?? {}
      observe('parse-issues 返回', `total=${issueBody.total} 首行=${JSON.stringify(issueBody.list?.[0] ?? null)}`)
      check(
        Number(issueBody.total) >= 15,
        'G4-11：行粒度 = (文档, 页码) —— 15 页扫描件返回 ≥15 行（每页一条，含真实页码）',
        `total=${issueBody.total}`,
      )
      const list = issueBody.list ?? []
      check(
        list.length > 0 &&
          list.every((i) => i.docId > 0 && i.pageNo >= 1 && typeof i.reason === 'string' && i.reason.length > 0),
        'G4-11：每行都带 docId / 真实 pageNo / 非空 reason（契约的四个字段逐条到位）',
        `首行 pageNo=${list[0]?.pageNo} reason=${String(list[0]?.reason).slice(0, 40)}…`,
      )

      const barText = await evaluate(`window.__t.text('.issue-bar')`)
      check(barText.length > 0, 'G4-11：页面顶部告警条真的显示出来了（不是恒空）', barText.slice(0, 100))
      check(
        /1 篇文档存在解析问题/.test(barText),
        'G4-11：告警条「篇数」按 docId **去重**（1 篇，不是 15 篇）—— 这是本批修的前端坑',
        barText.slice(0, 60),
      )
      check(
        /第 \d+ 页/.test(barText),
        'G4-11：告警条给出**真实页码**（不是占位）',
        (barText.match(/第 \d+ 页/) ?? ['—'])[0],
      )

      const shot = await send('Page.captureScreenshot', { format: 'png' })
      writeFileSync(`${OUT_DIR}/doc-parse-issues.png`, Buffer.from(shot.data, 'base64'))
    }

    // ==================== 4. 溯源与 console ====================
    const bad = consoleMsgs.filter((m) => /Failed to resolve component|Unhandled error|TypeError|is not a function/.test(m.text))
    check(bad.length === 0, '上传页 console 无未捕获异常', bad.map((b) => b.text).join(' | ') || '—')

    const keyEntries = ledger.filter((e) => e.url.includes('/doc/upload') || e.url.includes('/parse-issues'))
    const missingTrace = keyEntries.filter((e) => !headerOf(e.headers, 'x-trace-id'))
    check(missingTrace.length === 0, '关键 /api 响应均带 x-trace-id（证明确为真后端，mock 不产出该头）', `${keyEntries.length - missingTrace.length}/${keyEntries.length}`)

    console.log(`\n接口台账：\n${ledger.map((e) => `  ${e.method.padEnd(6)} ${e.url.replace(BASE, '')} → ${e.status}`).join('\n')}`)
    console.log(`截图：${OUT_DIR}/doc-parse-issues.png`)
  } finally {
    await sleep(200)
    try {
      ws?.close()
    } catch {
      /* ignore */
    }
    child.kill()
    try {
      // ⚠️ 在 purgeProbe **之前**取探针库 id：行删掉后就再也定位不到 Milvus 里的向量了
      const idsBefore = probeKbIds()
      const vectorsBefore = await milvusVectorRowsOfProbeKb(idsBefore)
      purgeProbe()
      const after = kbCount()
      const docsLeft = docRowsOfProbe()
      const issuesLeft = issueRowsOfProbe()
      const chunksLeft = chunkRowsOfProbe()
      const vectorsLeft = await milvusVectorRowsOfProbeKb(idsBefore)
      check(
        after === 8 && docsLeft === 0 && issuesLeft === 0 && chunksLeft === 0 && vectorsLeft === 0,
        '收尾：探针库 / 文档 / 问题行 / 分片行 / **Milvus 向量**已自清（sys_kb 回到 8 行）',
        `库=${after} 文档残留=${docsLeft} 问题行残留=${issuesLeft} 分片残留=${chunksLeft} ` +
          `Milvus 向量残留=${vectorsLeft}（跑动前 ${Math.max(0, vectorsBefore)}）`,
      )
    } catch (e) {
      check(false, '收尾：探针库自清失败，需人工检查', String(e.message))
    }
  }
}

main()
  .then(() => {
    const failed = steps.filter((s) => !s.ok).length
    console.log(`\n合计 ${steps.length} 步，通过 ${steps.length - failed}，失败 ${failed}`)
    process.exit(failed ? 1 : 0)
  })
  .catch((e) => {
    console.error(`\n✗ 中断：${e.message}`)
    const failed = steps.filter((s) => !s.ok).length
    if (steps.length) console.log(`\n已执行 ${steps.length} 步，通过 ${steps.length - failed}，失败 ${failed}`)
    try {
      purgeProbe()
    } catch {
      /* 已在 finally 里报过 */
    }
    process.exit(1)
  })
