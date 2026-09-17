#!/usr/bin/env node
/**
 * S4 · M05-A 页面级 E2E：**文档上传页**（`/kb/:id/upload`）切真后端。
 *
 * <h3>覆盖的判据</h3>
 * <ul>
 *   <li><b>G4-1</b> —— 选真文件 → 真字节 multipart → 落地 → 文档列表出现该行；</li>
 *   <li><b>G4-2</b> —— 限速下的**真实上传进度**（这条是本批最重要的一条：上一版的进度条
 *       是 `setInterval` 编的，限速下照样"匀速跑到 100%"，一眼就能看出是假的）；</li>
 *   <li><b>G4-3</b> —— 任务进度来自 `GET /doc/tasks/{taskId}` 轮询（不是前端自己算的）；</li>
 *   <li><b>G4-4</b> —— 列表的**状态列与解析质量列独立**（《06》§3.6 本页最重要的设计）；</li>
 *   <li><b>T-501</b> —— 去重预判真发出、且 MD5 与"页面自己读到的字节"逐位一致。</li>
 * </ul>
 *
 * <h3>为什么 MD5 要"三方对照"</h3>
 * 上传链路里 MD5 有两个来源：页面算的（`src/utils/md5.ts`，纯 JS 自研）与后端算的
 * （`MessageDigest`）。两者不一致的后果**不是报错**，而是 `check-md5` 永远不命中 ——
 * 表现为"秒传静默失效"，界面上看不出来。所以这里：
 * <ol>
 *   <li>Node 侧用 `node:crypto` 算同一文件的真值（**第四方**）；</li>
 *   <li>比对 `check-md5` 请求体里的 md5（= 页面自研实现算的）；</li>
 *   <li>若不等 → 断言失败，且失败信息里直接给出两个值，一眼定位是谁错了。</li>
 * </ol>
 * 这条断言的价值：`md5.ts` 是**自研**的（零依赖决策，见计划 §5），
 * 而自研 MD5 最典型的错法是"跨 64 字节分组的边界处理"—— 小文件对、大文件错。
 * 所以对照用的样本特意取 **2MB**（远超 64 字节，且跨 MD5 的填充边界）。
 *
 * <h3>自清纪律</h3>
 * 本脚本会用固定 code {@code kb_e2e_doc_probe} 真建一个库、真传文件，跑完必须
 * 把文档行与库一起删掉 —— 否则下次跑列表断言就会看到 9 个库。
 * **开头也清一次**（幂等）：上一次异常中断留下的残留同样会污染。
 *
 * <h3>前置</h3>
 * <pre>
 *   后端 8080：cd ai_knowledge_web && mvn -o spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
 *   前端 5174：cd ai_knowledge_app && VITE_USE_MOCK=false npm run dev -- --host 127.0.0.1 --port 5174
 *   运行：node scripts/e2e-doc-upload.mjs --base=http://127.0.0.1:5174
 * </pre>
 */
import { spawn, spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const REPO_ROOT = resolve(ROOT, '..')
const arg = (name, fallback) => (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://127.0.0.1:5174')
const API = arg('api', 'http://127.0.0.1:8080')

const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-e2e-doc'
const OUT_DIR = 'F:/work/code/ai-knowledge/temp/e2e-doc'
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9339)

/** 固定账号：种子超级管理员（doc:upload / kb:list 全有）。 */
const PROBE = { account: 'admin', password: 'Admin@123' }

/** 探针库的固定编码（自清锚点，必须匹配 ^kb_[a-z0-9_]{2,}$）。 */
const PROBE_CODE = 'kb_e2e_doc_probe'
const PROBE_NAME = 'M05-A 页面级探针库'

/**
 * 限速参数（字节/秒）。
 *
 * ⚠️ <b>标称值不等于实测吞吐</b>：CDP 的 {@code uploadThroughput} 在本机 loopback 上
 * 实测的收紧倍率不稳定（标 96KB/s 时 400KB 跑了 40s ≈ 实际 10KB/s；标 500KB/s 时
 * 400KB 只跑了 1.0s ≈ 实际 400KB/s）。所以这里**刻意取一个保守的小值 + 一个够大的体**
 * （2MB / 50KB/s，标称预期 40s），把期望耗时压到"即使收紧倍率只生效 1/10，
 * 也仍然远超假进度条那 ~1.1 秒"的量级上 —— 这样那条时间判据才**可复现**。
 */
const UPLOAD_BYTES_PER_SEC = 50 * 1024

/** 限速用的探针体大小：2MB（够大，让"限速是否真的生效"不至于落在噪声里）。 */
const THROTTLE_BYTES = 2 * 1024 * 1024

const steps = []
function check(ok, label, detail = '') {
  steps.push({ ok: !!ok, label, detail })
  console.log(`${ok ? '✓' : '✗'} ${label}${detail ? ` · ${detail}` : ''}`)
  return !!ok
}
function observe(label, detail = '') {
  console.log(`… ${label}${detail ? ` · ${detail}` : ''}`)
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

/**
 * 幂等清理探针库及其文档（顺序：对象 → 问题行 → 分片 → 文档 → 授权 → 主表）。
 *
 * <p>对象排在最前：删了文档行，`storage_path`/`parse_path` 就查不出来了。
 */
function purgeProbe() {
  const probeDocs = `from sys_document d join sys_kb k on k.id=d.kb_id where k.code='${PROBE_CODE}'`
  minioRemove(mysqlScalar(`select d.storage_path ${probeDocs};`))
  minioRemove(mysqlScalar(`select d.parse_path ${probeDocs};`))
  // ①' Milvus 里的向量 —— **必须在删 sys_kb 行之前做**（按 probe kb id 删，删完就查不到了）。
  //     `S4-M05-C3` 起探针文档也会被向量化（T-508）；不删就是"文档没了、向量还在"的幽灵数据。
  //     这是 `汇总.md` §3 #79 那条教训的**第 N 次复发**：新增持久化产物时没人回头查清理函数。
  milvusRemoveByProbeKb()
  mysqlScalar(
    `delete i from sys_parse_issue i join sys_document d on d.id=i.doc_id join sys_kb k on k.id=d.kb_id where k.code='${PROBE_CODE}';
      delete c from sys_chunk c join sys_document d on d.id=c.doc_id join sys_kb k on k.id=d.kb_id where k.code='${PROBE_CODE}';
      delete d from sys_document d join sys_kb k on k.id=d.kb_id where k.code='${PROBE_CODE}';` +
      `delete g from sys_kb_grant g join sys_kb k on k.id=g.kb_id where k.code='${PROBE_CODE}';` +
      `delete from sys_kb where code='${PROBE_CODE}';`,
  )
}
const kbCount = () => Number(mysqlScalar('select count(*) from sys_kb;'))

/**
 * 探针库的 id **列表**（**要在 purgeProbe 之前取**：行删了就查不到 id 了）。
 *
 * <p>⚠️ 必须 split 成数组：`mysqlScalar` 返回的是**字符串**（`-N` 的多行文本），
 * 直接 `.join(',')` 会炸成 `ids.join is not a function` —— 本轮实测踩过。
 */
const probeKbIds = () => (mysqlScalar(`select id from sys_kb where code='${PROBE_CODE}';`) ?? '')
  .split('\n').map((s) => s.trim()).filter(Boolean)

/**
 * 探针库在 **Milvus** 里残留的向量条数（`S4-M05-C3` / `T-508` 起会 > 0）。
 *
 * <p>走 Milvus REST v2，零新增依赖。**必须用 `entities/query` 的 `count(*)`，不能用
 * `get_stats` 的 `rowCount`** —— 后者只统计**已 flush** 的数据，而写入路径刻意不 flush
 * （见 `MilvusChunkWriter` 类 javadoc），拿它判残留恒得 0，等于没查。
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

/** 删掉探针库的全部向量。**调用必须在删 `sys_kb` 行之前。** */
function milvusRemoveByProbeKb() {
  const ids = probeKbIds()
  if (ids.length === 0) return
  spawnSync('bash', ['-c',
    `curl -s -m 15 -X POST http://127.0.0.1:19530/v2/vectordb/entities/delete ` +
    `-H 'Content-Type: application/json' ` +
    `-d '{"collectionName":"knowledge_vectors","filter":"kb_id in [${ids.join(',')}]"}'`,
  ], { cwd: REPO_ROOT, encoding: 'utf8' })
}

/** 探针库的分片行数 —— `S4-M05-C1` 起 `sys_chunk` 也要跟着清，否则留下**孤儿块**。 */
const chunkRowsOfProbe = () =>
  Number(mysqlScalar(`select count(*) from sys_chunk c join sys_kb k on k.id=c.kb_id where k.code='${PROBE_CODE}';`))
const docRowsOfProbe = () =>
  Number(mysqlScalar(`select count(*) from sys_document d join sys_kb k on k.id=d.kb_id where k.code='${PROBE_CODE}';`))

/**
 * 按标题取探针库里那一行的关键列（`title|file_name|type|size_bytes|md5|status|storage_path`）。
 *
 * <h3>为什么"文件内容真的传上来了"要在库里验，而不看请求体</h3>
 * CDP 的 {@code Network.requestWillBeSent} **不给 multipart 请求的 postData**
 * （带二进制体的请求它拿不到可序列化的正文；JSON 请求则正常给）。
 * 本脚本第一版就是拿 `postData.includes('name="file"')` 断言"请求体里有文件部件"，
 * 结果恒为 false —— **是观测手段的限制，不是缺陷**。
 * 改用"落库的 size/md5 与本地文件逐字节相符"来证同一件事，而且这个判据更强：
 * 它证明的不只是"请求里带了字节"，而是"那几个字节原样到了服务端并被认出内容"。
 */
function docRowOf(title) {
  const raw = mysqlScalar(
    `select concat_ws('|', d.title, d.file_name, d.type, d.size_bytes, d.md5, d.status, coalesce(d.storage_path,'')) ` +
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
  // 超时时**把现场打出来**：只报"等超时"会让排查从零开始（本脚本第一版就因此
  // 多跑了一轮 —— 队列项明明已完成，却看不出是 DOM 没更新还是断言写错了）
  const snapshot = await evaluate(`JSON.stringify({
    hash: location.hash,
    queue: [...document.querySelectorAll('.file-row')].map((r) => window.__t.norm(r.textContent).slice(0, 160)),
    docs: window.__t.docRows().length,
  })`).catch(() => '(取现场失败)')
  const consoleTail = consoleMsgs.slice(-8).map((m) => `    [${m.level}] ${m.text.slice(0, 160)}`).join('\n')
  const ledgerTail = ledger.map((e) => `    ${e.method} ${e.url.replace(BASE, '')} → ${e.status}`).join('\n')
  throw new Error(
    `等待超时（${timeout}ms）：${label}\n  现场：${snapshot}` +
      (consoleTail ? `\n  console 尾部：\n${consoleTail}` : '') +
      (ledgerTail ? `\n  接口台账：\n${ledgerTail}` : ''),
  )
}
/** 找台账里**第一个**匹配的响应（带 body）。 */
async function waitForApi(match, { timeout = 30000, label = '接口' } = {}) {
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
  /** 该队列项当前显示的百分比文本（UPLOADING 态才有；其它态渲染的是 tag） */
  pctOf: (name) => { const r = window.__t.rowOf(name); return r ? window.__t.norm(r.querySelector('.file-pct')?.textContent) : null },
  /** 该队列项的状态标签文本（已完成 / 解析中 / 解析失败） */
  tagOf: (name) => { const r = window.__t.rowOf(name); return r ? window.__t.norm(r.querySelector('.el-tag')?.textContent) : null },
  /** 文档列表行（表格体），返回每行各列文本 */
  docRows: () => [...document.querySelectorAll('.el-table__body .el-table__row')].map((r) =>
    [...r.querySelectorAll('td')].map((td) => window.__t.norm(td.textContent))),
  docRowOf: (title) => window.__t.docRows().find((cells) => cells.some((c) => c.includes(title))) || null,
  /** 页面上所有 el-message（去重提示是 ElMessage，不是 notification） */
  messages: () => [...document.querySelectorAll('.el-message')].map((m) => window.__t.norm(m.textContent)),
}
'ok'
`

async function navigate(hash) {
  await send('Page.navigate', { url: `${BASE}/?boot=${Date.now()}#${hash}` })
}

async function main() {
  if (!existsSync(EDGE)) throw new Error(`找不到 Edge：${EDGE}`)
  rmSync(PROFILE, { recursive: true, force: true })
  mkdirSync(OUT_DIR, { recursive: true })

  // ---- 幂等自清 ----
  purgeProbe()
  const before = kbCount()
  check(before === 8, '准备：sys_kb 回到 8 行（幂等清理探针库）', `行数=${before}`)

  // ---- 造两个真文件：小的走主流程，大的走限速进度与 MD5 对照 ----
  const smallName = 'E2E回归探针.md'
  const smallBytes = Buffer.from(
    '# E2E 回归探针\n\n型号 A-100，产品名称 智能网关，单价 ￥1,200，交期 7 天。\n', 'utf8')
  writeFileSync(`${OUT_DIR}/${smallName}`, smallBytes)

  // 大文件也必须是**真能解析的文本**：S4-B 起上传即解析，伪随机字节会被判「无文本层」
  // 而走 FAILED —— 那会让下面的「队列项 ↔ 已完成」等待超时，且把一个真实的解析失败
  // 混进限速判据里（两件事互相干扰）。2MB 的中文文本足够撑起限速窗口，也能解析成功。
  const bigName = 'E2E限速探针.md'
  const bigBytes = Buffer.from(
    ('# E2E 限速探针\n\n' + '型号 A-100，产品名称 智能网关，单价 ￥1,200，交期 7 天。'.repeat(4) + '\n\n')
      .repeat(Math.ceil(THROTTLE_BYTES / 120)),
    'utf8').subarray(0, THROTTLE_BYTES)
  writeFileSync(`${OUT_DIR}/${bigName}`, bigBytes)

  const smallMd5 = createHash('md5').update(smallBytes).digest('hex')
  const bigMd5 = createHash('md5').update(bigBytes).digest('hex')
  observe('Node 侧 md5（真值）', `${smallName}=${smallMd5.slice(0, 8)}… ${bigName}=${bigMd5.slice(0, 8)}…（${bigBytes.length}B）`)

  const login = await api('/auth/login', { method: 'POST', body: { account: PROBE.account, password: PROBE.password, remember: true } })
  if (login.json?.code !== 0) throw new Error(`admin 登录失败：code=${login.json?.code} · ${login.json?.message}`)
  const token = login.json.data.accessToken
  check(true, '准备：admin 登录，拿到 accessToken')

  // 探针库直接走 API 建（上传页的入口是"从创建向导跳过来"，那一段已由 e2e-kb-flow 覆盖）
  // ⚠️ 路径**不带** `/api` 前缀：`api()` 内部已拼 `${API}/api`（与 e2e-kb-flow 同口径）
  const created = await api('/kb', {
    method: 'POST',
    token,
    body: {
      name: PROBE_NAME,
      code: PROBE_CODE,
      deptId: 1,
      visibleRoles: ['SUPER_ADMIN'],
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
        .catch((e) => {
          entry.bodyError = String(e && e.message)
          return null
        })
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
    // ==================== 1. 上传页渲染 ====================
    await navigate(`/kb/${kbId}/upload`)
    await waitFor(`!!document.querySelector('.upload-page') && !!document.querySelector('.four-col')`, { label: '上传页渲染', timeout: 30000 })
    await evaluate(PAGE_TOOLS)

    const docsEntry = await waitForApi((e) => e.url.includes(`/api/kb/${kbId}/docs`), { label: 'GET /docs' })
    check(true, '上传页调用 GET /api/kb/:id/docs', assertBizOk(docsEntry, 'docs'))
    check(JSON.parse(docsEntry.body ?? '{}').data?.total === 0, '新库文档列表为 0 篇', `total=${JSON.parse(docsEntry.body ?? '{}').data?.total}`)
    check(await evaluate(`window.__t.rows().length === 0`), '上传队列初始为空（el-empty 空态）')

    // ==================== 2. 小文件：真字节上传 → 任务轮询 → 列表落地 ====================
    await setFile(smallName)
    const checkMd5Entry = await waitForApi((e) => e.url.includes('/doc/check-md5'), { label: 'POST check-md5' })
    check(true, 'T-501：选文件后真发 POST /api/kb/:id/doc/check-md5（去重预判）', assertBizOk(checkMd5Entry, 'check-md5'))
    const md5Sent = JSON.parse(checkMd5Entry.postData ?? '{}').md5
    check(
      md5Sent === smallMd5,
      'T-501：页面自研 MD5（src/utils/md5.ts）与 node:crypto 真值逐位一致',
      `页面=${md5Sent} · node=${smallMd5}`,
    )
    check(
      (checkMd5Entry.postData ?? '').includes(smallName),
      'T-501：去重请求体带真实文件名（不是占位）',
      `${smallName}`,
    )

    const uploadEntry = await waitForApi((e) => e.url.includes('/doc/upload'), { label: 'POST doc/upload' })
    check(true, 'G4-1：上传走通 POST /api/kb/:id/doc/upload', assertBizOk(uploadEntry, 'upload'))

    // ---- 本批最核心的一条：请求真的是 multipart，且文件字节真的到了服务端 ----
    const ct = String(headerOf(uploadEntry.reqHeaders, 'content-type') ?? '')
    check(
      ct.includes('multipart/form-data') && ct.includes('boundary='),
      'G4-1：上传请求是 multipart/form-data 且带 boundary（上一版发的是 JSON —— W1 的收口点）',
      `content-type=${ct.slice(0, 60)}`,
    )
    check(!ct.includes('application/json'), 'G4-1：上传请求不是 application/json（"假上传"已修）', ct.slice(0, 30) || '—')

    // ⚠️ "请求体里有 file 部件"**不能**从 CDP 的 postData 断言：multipart 请求它拿不到正文
    // （见 docRowOf 的 javadoc）。改用落库值证同一件事，且这个判据更强 ——
    // 它证明"字节原样到了服务端并被认出内容"，而不只是"请求里带了字节"。
    //
    // ⚠️ 还要**等 storage_path 落地**：它是工作线程在异步任务里回写的
    // （接口是"受理即返回"，不让请求线程等 100MB 转存）。不轮询直接读会拿到 null ——
    // 那测的是异步竞态，不是功能（后端用例第一版就红在这一条）。
    for (let i = 0; i < 60; i++) {
      const probe = docRowOf('E2E回归探针')
      if (probe && probe[6]) break
      await sleep(250)
    }
    const smallRow = docRowOf('E2E回归探针')
    check(
      smallRow !== null,
      'G4-1：上传的文件在 sys_document 里有行',
      smallRow ? `status=${smallRow[5]}` : '未找到',
    )
    check(
      smallRow && Number(smallRow[3]) === smallBytes.length && smallRow[4] === smallMd5,
      'G4-1：落库 size_bytes 与 md5 与本地文件逐字节相符（= 文件内容真的传上来了，不是只有元信息）',
      smallRow ? `size=${smallRow[3]}（本地 ${smallBytes.length}） md5=${smallRow[4].slice(0, 8)}…（本地 ${smallMd5.slice(0, 8)}…）` : '—',
    )
    check(
      smallRow && smallRow[1] === smallName && smallRow[2] === 'MD',
      'G4-1：file_name 保留原文件名、type 由扩展名推得（.md → MD）',
      smallRow ? `${smallRow[1]} · ${smallRow[2]}` : '—',
    )
    check(
      smallRow && smallRow[6].startsWith('documents/'),
      'G4-1：storage_path 是 MinIO 对象名（documents/{kbId}/{docId}/…），不是 URL',
      smallRow ? smallRow[6] : '—',
    )

    const uploadBody = JSON.parse(uploadEntry.body ?? '{}').data ?? {}
    check(Number(uploadBody.docId) > 0 && String(uploadBody.taskId).length > 0, 'G4-1：响应回 docId + taskId', `docId=${uploadBody.docId} taskId=${String(uploadBody.taskId).slice(0, 8)}…`)
    check(Number(uploadBody.chunkTotal) === 0, 'G4-1：chunkTotal 恒 0（分片数要解析完才知道，S4-B 回填）', `chunkTotal=${uploadBody.chunkTotal}`)

    // ---- G4-3：任务进度来自真后端轮询 ----
    const taskEntry = await waitForApi((e) => e.url.includes('/api/doc/tasks/'), { label: 'GET /doc/tasks/:id' })
    check(true, 'G4-3：前端轮询 GET /api/doc/tasks/:taskId（上一版该接口零调用方 —— W5）', assertBizOk(taskEntry, 'task'))
    const taskBody = JSON.parse(taskEntry.body ?? '{}').data ?? {}
    check(
      ['taskId', 'stage', 'percent', 'chunkDone', 'chunkTotal', 'errorMsg'].every((k) => k in taskBody),
      'G4-3：任务响应含《10》§6 的六个字段（stage/percent/chunkDone/chunkTotal/errorMsg）',
      `stage=${taskBody.stage} percent=${taskBody.percent}`,
    )

    await waitFor(`window.__t.tagOf(${JSON.stringify(smallName)}) === '已完成'`, { label: '队列项标「已完成」', timeout: 30000 })
    check(true, 'G4-1：队列项走到「已完成」', `tag=${await evaluate(`window.__t.tagOf(${JSON.stringify(smallName)})`)}`)

    // ---- 列表落地 ----
    await waitFor(`window.__t.docRows().length === 1`, { label: '文档列表出现该行', timeout: 20000 })
    const row = await evaluate(`window.__t.docRowOf(${JSON.stringify('E2E回归探针')})`)
    check(Array.isArray(row) && row.length > 0, 'G4-1：上传完成后文档列表出现该行（真刷新的列表）', row ? `列数=${row.length}` : '—')
    check(row && row.includes('已就绪'), 'G4-3（后半收口）：状态列渲染「已就绪」（域内真值 READY → 展示文案；S4-C3 起向量已入 Milvus，不再是 INDEXING）', `状态=${row?.[5]}`)

    // ---- G4-4：状态列与解析质量列**独立** ----
    // 列索引：0 勾选 · 1 文件名 · 2 类型 · 3 大小 · 4 分片数 · 5 状态 · 6 解析质量 · 7 上传人 · 8 上传时间 · 9 操作
    // S4-C3 起：MD 文本解析成功 → READY（向量已入 Milvus）；无表格 → 质量列「无表格 / 图片」。
    // 两列各自反映一件独立的事实（状态来自 status，质量来自 table_*/image_*），这正是 §3.6 的设计目的。
    check(
      row && row[5] === '已就绪' && row[6] === '无表格 / 图片',
      'G4-4：《06》§3.6 的两列独立 —— 状态列「已就绪」而质量列「无表格 / 图片」（MD 无表格）',
      `状态=${row?.[5]} 质量=${row?.[6]}`,
    )
    // ⚠️ 本条在 `S4-M05-C1` 被**改写**（原断言：`row[4] === '—'`）。
    // 原断言立得没错 —— 那时分片还没实现（`T-503` 属 S4-C），`chunk_count` 恒 0，
    // 本列只能显示占位「—」，而"0 会被读成『切了 0 片』这个具体事实"。
    // 现在分片真的落地了，列语义**没变**（0 仍显示「—」），变的是
    // "这篇文档确实有分片了" —— 断言跟着改成断真值，否则它会守住一个已经不存在的行为。
    check(
      row && /^\d+$/.test(row[4]) && Number(row[4]) > 0,
      'G4-4 + T-503：分片数列显示**真值**（分片落地前本列恒为占位「—」，本批销账）',
      `分片数=${row?.[4]}`,
    )
    const shot1 = await send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(`${OUT_DIR}/doc-uploaded.png`, Buffer.from(shot1.data, 'base64'))

    // ==================== 3. 大文件：限速下的**真实**进度 + MD5 三方对照 ====================
    await send('Network.emulateNetworkConditions', {
      offline: false,
      latency: 20,
      downloadThroughput: 4 * 1024 * 1024,
      uploadThroughput: UPLOAD_BYTES_PER_SEC,
    })
    const throttleStart = Date.now()
    await setFile(bigName)

    const bigCheckEntry = await waitForApi((e) => e.url.includes('/doc/check-md5') && (e.postData ?? '').includes(bigName), {
      label: 'POST check-md5（大文件）', timeout: 60000,
    })
    const bigMd5Sent = JSON.parse(bigCheckEntry.postData ?? '{}').md5
    check(
      bigMd5Sent === bigMd5,
      'T-501：400KB 文件的页面 MD5 与 node:crypto 真值一致（自研实现对跨 64 字节分组的边界处理正确）',
      `页面=${bigMd5Sent} · node=${bigMd5}`,
    )

    // 采样真实进度：限速下应在若干秒内出现**多个不同档位**，
    // 且从"首次非零"到"到 99%"之间要**真的走过一段时间**。
    //
    // ⚠️ `.file-pct` 只在 UPLOADING 态渲染（其它态是 tag）—— 所以显式判 `if (raw)`，
    // 不把 null 当成 0 记进档位（那会让档位数虚高，看起来"很平滑"其实只是重复计数）。
    const pcts = []
    let firstNonZeroAt = 0
    const sampleDeadline = Date.now() + 120_000
    while (Date.now() < sampleDeadline) {
      const raw = await evaluate(`window.__t.pctOf(${JSON.stringify(bigName)})`)
      if (raw) {
        const n = Number(String(raw).replace('%', ''))
        if (Number.isFinite(n)) {
          if (n > 0 && !firstNonZeroAt) firstNonZeroAt = Date.now()
          if (pcts[pcts.length - 1] !== n) pcts.push(n)
          // 上传侧进度最高就是 99（100 由服务端确认后置上，见 request.ts 的 upload()）；
          // 等 100 会一直空转到采样超时，白等
          if (n >= 99) break
        }
      }
      await sleep(220)
    }
    const throttleMs = Date.now() - throttleStart
    const progressWindowMs = firstNonZeroAt ? Date.now() - firstNonZeroAt : 0
    const distinct = [...new Set(pcts)]
    observe(
      '限速下进度采样',
      `档位=[${distinct.join(', ')}] 总耗时=${throttleMs}ms 进度窗口=${progressWindowMs}ms ` +
        `体=${(bigBytes.length / 1024 / 1024).toFixed(1)}MB 标称限速=${UPLOAD_BYTES_PER_SEC / 1024}KB/s（标称预期≈${Math.round((bigBytes.length / UPLOAD_BYTES_PER_SEC) * 1000)}ms）`,
    )
    check(
      distinct.length >= 4,
      'G4-2：限速下进度是**真实上传进度**（采样到 ≥4 个不同档位；上一版的 setInterval 也会"动"，所以这条要与下一条一起看）',
      `档位=${distinct.length} 个`,
    )
    // 真实性的硬判据：**进度的推进窗口**必须显著长于"假进度条的一次定长动画"。
    //
    // 上一版的假进度条是 `setInterval(每 110ms 跳 7~16%)` —— 与文件大小、与网速
    // **完全无关**，永远是 ~1.1 秒跑完。所以判据取"进度从 0 推进到 99 用了多久"，
    // 而不是"总耗时"（后者含 MD5 计算与轮询，噪声大）。
    //
    // ⚠️ 不用"标称限速 × 体积"当期望值：CDP 的 uploadThroughput 在本机实测的
    // 收紧倍率不稳定（标 96KB/s → 实测约 10KB/s；标 500KB/s → 实测约 400KB/s），
    // 拿标称值当分母会得到一个时对时错的期望（本脚本第一版就因此报过
    // "期望 4167ms 实际 40194ms"——看着像缺陷，其实是基准取错了）。
    // 这里只用一个**下限**：2MB 的真进度不可能在 2 秒内推完。
    check(
      progressWindowMs >= 2000,
      'G4-2：进度推进窗口 ≥ 2s（假进度条无视体积与限速，永远 ~1.1s 跑完）',
      `进度窗口=${progressWindowMs}ms（下限 2000ms）`,
    )
    check(
      distinct[distinct.length - 1] >= 99,
      'G4-2：进度最终到达 99%（上传侧的那一档；100% 由服务端确认后置上）',
      `末档=${distinct[distinct.length - 1]}`,
    )

    await waitFor(`window.__t.tagOf(${JSON.stringify(bigName)}) === '已完成'`, { label: '大文件队列项完成', timeout: 120000 })
    check(true, `G4-2：${(bigBytes.length / 1024 / 1024).toFixed(1)}MB 文件在限速下真实传完并走到「已完成」`, `tag=${await evaluate(`window.__t.tagOf(${JSON.stringify(bigName)})`)}`)

    // 关掉限速，避免后面的断言被拖慢
    await send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 })

    await waitFor(`window.__t.docRows().length === 2`, { label: '两篇文档都在列表里', timeout: 20000 })
    check(true, 'G4-1：两篇文档都在列表（total 与 list 同源、真刷新）', `行数=${await evaluate('window.__t.docRows().length')}`)

    // ==================== 4. 去重命中：提示而非阻断（T-501 的行为面） ====================
    await setFile(smallName)
    const dupEntry = await waitForApi(
      (e) => e.url.includes('/doc/check-md5') && (e.postData ?? '').includes(smallName) && e !== checkMd5Entry,
      { label: 'POST check-md5（第二次）', timeout: 30000 },
    )
    const dupBody = JSON.parse(dupEntry.body ?? '{}').data ?? {}
    check(
      dupBody.duplicated === true,
      'T-501：同一文件二次选择时命中秒传（duplicated=true，键含 owner_id）',
      `duplicated=${dupBody.duplicated} docId=${dupBody.docId}`,
    )
    check(
      typeof dupBody.hint === 'string' && dupBody.hint.length > 0,
      'T-501：命中时 hint 非空（前端据此提示「将归档为新版本」）',
      dupBody.hint,
    )
    // 提示型而非阻断型：命中之后**仍然**发出上传请求（《06》§3.2「覆盖即产生新版本」）
    // 等**上传**的响应而不是等"新的 check-md5" —— 后者在前一步就已经发出了，
    // 用 `e !== checkMd5Entry` 的匹配条件去等它会立刻命中旧的，等于没等。
    const uploadsBefore = ledger.filter((e) => e.url.includes('/doc/upload')).length
    await waitForApi((e) => e.url.includes('/doc/upload') && ledger.filter((l) => l.url.includes('/doc/upload')).length > uploadsBefore, {
      label: 'POST doc/upload（命中后仍上传）',
      timeout: 30000,
    })
    const dupUpload = ledger.filter((e) => e.url.includes('/doc/upload')).at(-1)
    check(true, 'T-501：命中去重**不阻断**上传（提示型，与不加唯一索引的取舍同源）', assertBizOk(dupUpload, 'upload#2'))
    await waitFor(`window.__t.tagOf(${JSON.stringify(smallName)}) === '已完成'`, { label: '重传后再次完成', timeout: 30000 })
    await waitFor(`window.__t.docRows().length === 3`, { label: '同 md5 产生新行（覆盖=新版本）', timeout: 20000 })
    const sameMd5Rows = docRowsOfProbe()
    check(sameMd5Rows === 3, 'T-501：同一内容重复上传产生新行（3 行都在库，md5 相同）', `sys_document 行数=${sameMd5Rows}`)

    // ==================== 5. 溯源与 console ====================
    const bad = consoleMsgs.filter((m) => /Failed to resolve component|Unhandled error|TypeError|is not a function/.test(m.text))
    check(bad.length === 0, '上传页 console 无未捕获异常', bad.map((b) => b.text).join(' | ') || '—')

    const keyEntries = [docsEntry, checkMd5Entry, uploadEntry, taskEntry, bigCheckEntry, dupEntry, dupUpload]
    const missingTrace = keyEntries.filter((e) => !headerOf(e.headers, 'x-trace-id'))
    check(missingTrace.length === 0, '关键 /api 响应均带 x-trace-id（证明确为真后端，mock 不产出该头）', `${keyEntries.length - missingTrace.length}/${keyEntries.length}`)

    const taskPolls = ledger.filter((e) => e.url.includes('/api/doc/tasks/')).length
    check(taskPolls >= 2, 'G4-3：任务进度是**轮询**出来的（≥2 次 /doc/tasks 请求）', `轮询次数=${taskPolls}`)

    console.log(`\n接口台账：\n${ledger.map((e) => `  ${e.method.padEnd(6)} ${e.url.replace(BASE, '')} → ${e.status}`).join('\n')}`)
    console.log(`截图：${OUT_DIR}/doc-uploaded.png`)
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
      const chunksLeft = chunkRowsOfProbe()
      const vectorsLeft = await milvusVectorRowsOfProbeKb(idsBefore)
      check(
        after === 8 && docsLeft === 0 && chunksLeft === 0 && vectorsLeft === 0,
        '收尾：探针库 / 文档 / 分片行 / **Milvus 向量**已自清，sys_kb 回到 8 行',
        `库=${after} 文档残留=${docsLeft} 分片残留=${chunksLeft} ` +
          `Milvus 向量残留=${vectorsLeft}（跑动前 ${Math.max(0, vectorsBefore)}）`,
      )
    } catch (e) {
      check(false, '收尾：探针库自清失败，需人工检查', String(e.message))
    }
  }
}

/** 通过 CDP 给 el-upload 的隐藏 input 塞一个真文件（等价于用户点选）。 */
async function setFile(name) {
  const doc = await send('DOM.getDocument', { depth: -1 })
  const node = await send('DOM.querySelector', { nodeId: doc.root.nodeId, selector: '.upload-page .el-upload input[type="file"]' })
  if (!node.nodeId) throw new Error('找不到上传页的文件 input')
  await send('DOM.setFileInputFiles', { nodeId: node.nodeId, files: [`${OUT_DIR}/${name}`] })
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
