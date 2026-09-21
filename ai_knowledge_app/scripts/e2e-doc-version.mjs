/**
 * `S7-M06-D` 页面级 E2E：**文档版本历史**（`T-606` / `T-607` / `T-608` / `T-609` · `G7-5` / `G7-6`）。
 *
 * <h3>覆盖的判据</h3>
 * <ul>
 *   <li><b>`I-5` 同名上传归档</b>：同名重传**不新增独立文档**，而是落进同一 `doc_group_id`、
 *       `version_no = 2`、`is_current = 1`，并把旧当前行翻 `is_current = 0`；</li>
 *   <li><b>`G7-5`（旧版本不再被召回）</b>：**A/B/A 三段** —— 归档前问 v1 正文搜得到、
 *       归档后同一个问句搜不到 v1、回滚之后又搜得到。单看「搜不到」是**会假绿的**：
 *       "打点根本没落"与"召回了 0 条"在计数上长得一样，所以每一问都先断言**打点落了**；</li>
 *   <li><b>向量随归档清理</b>：归档后 v1 的 Milvus 向量 = 0（**独立观测点**：直查 Milvus，
 *       不看清理命令的返回值）；</li>
 *   <li><b>`T-606` 版本列表</b>：`current` 取 `is_current=1` 那一行、`hasNewer` 标记、
 *       且**从组内任意一版的 doc id 进**都拿到同一个组；</li>
 *   <li><b>`T-607` 版本对比（仅元数据）</b>：`left`/`right` 两版元数据 + `textDiff` **恒 `null`**
 *       （需求 §6.8 的"接口预留"，**不是**「两版正文没差别」）· `against` 留空 = 与当前版本比 ·
 *       同版对比被拒（`10030`）；</li>
 *   <li><b>`T-608` 回滚（`G7-6`）</b>：**新增**一条版本行（版本数只增不减）+ 复制分片 +
 *       **重新向量化** + 旧当前行清向量 + `storage_path` 指向同一个 MinIO 对象；</li>
 *   <li><b>`G7-6` 的后半句「历史问答的引用不变」</b>：回滚**之前**那次提问的打点明细，
 *       回滚**之后**仍然指向它当初命中的那一版（回滚不改写历史 ⇒ 可追溯）；</li>
 *   <li><b>`T-609` 标签</b>：`GET /doc/{id}/tags` 回**空数组**且 `code = 0`
 *       （契约保留 + 最小实现；如实为空，**没有编造标签**）；</li>
 *   <li>页面级：版本 Tab 真渲染两组版本行、旧版标「已有更新版本」、「对比」弹**真的**元数据对话框
 *       且**真的发了 `diff` 请求**、「回滚」经确认框后**真的发了 `POST /rollback`**、
 *       回滚后页面**重新拉取**版本列表（3 行）；</li>
 *   <li><b>判据 13</b>：**当前版本那一行没有回滚按钮**，而其它行有（两半都要 ——
 *       只验"当前行没有"在"一个按钮都没渲染"时会假绿）；</li>
 *   <li>收尾**自清残留 0 行**（两族都断言：探针库族 + 会话族 —— `T-O21` 的口径）。</li>
 * </ul>
 *
 * <h3>⚠️ 刻意**不在本脚本**里验的三件</h3>
 * <ol>
 *   <li><b>正文文本 diff</b>：需求 §6.8 明写 v1 只做元数据对比、文本 diff 接口预留 ⇒
 *       本脚本验的是「它**如实为空**」，而不是"它能比正文"；</li>
 *   <li><b>跨库 / 跨用户同名不折进同组</b>：那要构造第二个账号与第二个库，属后端用例
 *       `UploadArchiveFlowTest` 的射程（那里能直接读分组列，比页面级精确）；</li>
 *   <li><b>检索侧的密级上限</b>：本脚本手上只有种子超管（上限 = 绝密）⇒ 写在这里会验成假的，
 *       由 `RetrievalScopeFlowTest` 覆盖（同 `e2e-doc-attributes.mjs` 的取舍）。</li>
 * </ol>
 *
 * <h3>前置</h3>
 * <pre>
 *   后端 8080（真后端）· 前端 5174（`node temp/start-vite.mjs`，真后端模式）
 *   运行：node scripts/e2e-doc-version.mjs --base=http://127.0.0.1:5174
 * </pre>
 *
 * ⚠️ **本文件没有 shebang**：本机 shell 的路径守卫会拦下含 `#!` 的内容。
 */
import { spawn, spawnSync } from 'node:child_process'
import { existsSync, rmSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import {
  mysqlCount,
  mysqlScalar,
  teardownKbProbe,
  sweepAbandonedKbProbes,
  sweepOrphanChatFamily,
  kbResidueClean,
  describeKbResidue,
  kbIdsOf,
  milvusPost,
  assertArtifactCoverage,
} from './lib/probe-teardown.mjs'
import { probeDevServer, reportPrecondition, EXIT_PRECONDITION } from './lib/dev-server.mjs'

const arg = (name, fallback) => (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://localhost:5174')
const API = arg('api', 'http://localhost:8080')

const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'

const stamp = Date.now()
/** 每次运行用独立 profile 目录 —— 理由见 `e2e-doc-attributes.mjs` 的同名常量注释。 */
const runTag = `p${stamp % 100000}`
const PROFILE = `F:/work/code/ai-knowledge/temp/edge-e2e-docver-${runTag}`
/**
 * CDP 端口**每轮不同**（同一台机器上可能并行跑别的页面级脚本，也可能有上一轮的
 * Edge 还没退干净）。
 *
 * <p>🔴 固定端口有个**静默错连**的风险：`waitForEdge` 只是"某人在这个端口上答话"，
 * 于是新起的 Edge（端口被占、CDP 没起来）会被忽略，脚本跑去驱动**别人的浏览器** ——
 * 那样后面每一条页面级判据验的都是另一个进程里的页面，**而它看起来全绿**。
 * 端口每轮变 + 下面那条"目标里必须有我刚要的 about:blank"的断言，才把这个口子堵上。
 */
const CDP_PORT = Number(process.env.E2E_CDP_PORT || (9360 + (stamp % 500)))

const PROBE = { account: 'admin', password: 'Admin@123' }
const KB_CODE = `kb_e2edocver_${stamp}`
/**
 * 🔴 **两次上传用同一个文件名** —— 那正是"同名归档"的触发条件
 * （分组键 = `(kb_id, owner_id, file_name)`，见本批计划 §3.4 假设 1）。
 * 改一个字符就会变成"新开一个组"，而那时本脚本全部判据都会红在**看起来不相干**的地方。
 */
const DOC_NAME = `探针文档-版本-${stamp}.md`
/** 两版正文各带一个**只属于自己**的关键词 —— A/B/A 三段靠它们分辨"现在召回到的是哪一版"。 */
const MAGIC_V1 = `山茶花协议${stamp}`
const MAGIC_V2 = `雪松协议${stamp}`
const CONTENT_V1 = `# 探针文档（第一版）\n\n${MAGIC_V1} 是本文档第一版独有的关键词，用来验证归档之后它不再被召回。\n`
const CONTENT_V2 = `# 探针文档（第二版）\n\n${MAGIC_V2} 是本文档第二版独有的关键词，用来验证同名重传后新版本可被召回。\n`

/** Milvus collection 名（与 `probe-teardown.mjs` 的 `COLLECTION` 同值；那边没导出）。 */
const COLLECTION = 'knowledge_vectors'

const steps = []
function check(ok, label, detail = '') {
  steps.push({ ok: !!ok, label, detail })
  console.log(`${ok ? '✓' : '✗'} ${label}${detail ? ` · ${detail}` : ''}`)
  return !!ok
}
function observe(label, detail = '') {
  console.log(`… ${label}${detail ? ` · ${detail}` : ''}`)
}

// ---------------------------------------------------------------- 直连后端
let token = ''
async function api(path, { method = 'GET', body, raw = false, as = token } = {}) {
  const res = await fetch(`${API}/api${path}`, {
    method,
    headers: {
      ...(raw ? {} : body ? { 'Content-Type': 'application/json' } : {}),
      ...(as ? { Authorization: `Bearer ${as}` } : {}),
    },
    body: raw ? body : body ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    /* 非 JSON */
  }
  return { status: res.status, json, text }
}
function bizOk(res, label) {
  if (!res.json) throw new Error(`${label}：响应不是 JSON（HTTP ${res.status}）· ${String(res.text).slice(0, 200)}`)
  if (res.json.code !== 0) throw new Error(`${label}：业务失败 code=${res.json.code} · ${res.json.message}`)
  return res.json.data
}

// ---------------------------------------------------------------- CDP
let ws
let msgId = 0
const pending = new Map()
const consoleMsgs = []
/**
 * 本脚本关心响应：版本**读**接口（列表 / 对比）与**回滚**写接口。
 * 页面级判据的核心是"按钮真的发了请求"，所以这一份流水必须记全。
 */
const versionCalls = []

function send(method, params = {}) {
  const id = ++msgId
  return new Promise((resolve, reject) => {
    // 必须带超时：CDP 不回包时原来会永久挂起，现象与"卡在某业务步"长得一样
    const timer = setTimeout(() => {
      pending.delete(id)
      reject(new Error(`CDP 无响应（${method}）：超过 20s —— 多半是连到了别的 Edge 实例`))
    }, 20000)
    pending.set(id, {
      resolve: (v) => {
        clearTimeout(timer)
        resolve(v)
      },
      reject: (e) => {
        clearTimeout(timer)
        reject(e)
      },
    })
    ws.send(JSON.stringify({ id, method, params }))
  })
}
async function evaluate(expression) {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (r.exceptionDetails) throw new Error(`页面脚本抛错：${r.exceptionDetails.text ?? ''} ${r.exceptionDetails.exception?.description ?? ''}`)
  return r.result?.value
}
async function waitFor(expression, { timeout = 15000, interval = 150, label = expression } = {}) {
  const deadline = Date.now() + timeout
  let last
  while (Date.now() < deadline) {
    try {
      const v = await evaluate(expression)
      if (v) return v
      last = v
    } catch (e) {
      last = e.message
    }
    await sleep(interval)
  }
  throw new Error(`等待超时（${timeout}ms）：${label}（最后一次求值：${JSON.stringify(last)}）`)
}
async function goto(url) {
  await send('Page.navigate', { url })
  await sleep(700)
}
/**
 * 版本表的取数脚本（**与 Element Plus 的表结构解耦**）。
 *
 * <p>⚠️ 为什么不能直接写 `.el-table > .el-table__body-wrapper`：EP 2.3 起重构过表格
 * （本仓装的是 **2.14.5**）—— ① `body-wrapper` 现在挂在 `.el-table__inner-wrapper` 下，
 * 不再是 `.el-table` 的直接子节点；② `fixed="right"` 的固定列改成**同一张表内 sticky**，
 * 不再克隆一张 `el-table__fixed-right`。也就是说：**"操作列的按钮在哪一行里"这件事，
 * 老版 EP 与新版的答案不同**，而两个答案都"看起来能跑"。
 *
 * <p>所以这里按**行下标**把同一行的所有 `.el-table__body` 取并集：文本取**最长**的那一份
 * （只有操作列的克隆行文本很短，主表行文本长），按钮取**全部**。两种结构下都对，
 * 且把结构本身（`bodies` / `shapes`）**报出来** —— 结构变了要看得见，不能静默退化。
 */
const VERSION_TABLE_JS = `(() => {
  const pane = document.querySelector('#pane-version')
  if (!pane) return null
  const lists = [...pane.querySelectorAll('.el-table__body')]
    .map((b) => [...b.querySelectorAll('.el-table__row')])
    .filter((l) => l.length > 0)
  const n = lists[0] ? lists[0].length : 0
  const pick = (i) => lists.map((l) => (l[i] ? l[i].innerText.replace(/\\n+/g, ' ').trim() : ''))
    .sort((a, b) => b.length - a.length)[0] || ''
  const btns = (i) => lists.flatMap((l) => (l[i] ? [...l[i].querySelectorAll('button')].map((b) => b.textContent.trim()) : []))
  return {
    rows: Array.from({ length: n }, (_, i) => pick(i)),
    btns: Array.from({ length: n }, (_, i) => btns(i)),
    bodies: lists.length,
    shapes: lists.map((l) => l.length),
  }
})()`

/** 等版本表渲染出至少 `min` 行，返回快照。 */
async function waitVersionRows(min, label, timeout = 15000) {
  return await waitFor(`(() => { const s = ${VERSION_TABLE_JS}; return s && s.rows.length >= ${min} ? s : null })()`,
    { timeout, label })
}

/**
 * 在 `#pane-version` 里点某一版那一行的操作按钮。
 *
 * <p>两处刻意的写法：
 * <ol>
 *   <li>**按行文本定位**（`^v1` 这种），不按下标 —— 下标会随版本数变化而漂；</li>
 *   <li>返回**具体原因**而不是 `false`：失败时说清是"行没找到"还是"按钮没找到"，
 *       以及当前到底看到几张 body、各几行（结构假设错了要当场能定位）。</li>
 * </ol>
 */
async function clickVersionOp(rowPattern, buttonText) {
  const res = await evaluate(`(() => {
    const st = ${VERSION_TABLE_JS}
    if (!st) return 'no-pane'
    const idx = st.rows.findIndex((r) => ${rowPattern}.test(r))
    if (idx < 0) return 'no-row:' + JSON.stringify(st.rows)
    const pane = document.querySelector('#pane-version')
    const lists = [...pane.querySelectorAll('.el-table__body')]
      .map((b) => [...b.querySelectorAll('.el-table__row')]).filter((l) => l.length > 0)
    const hit = lists.flatMap((l) => (l[idx] ? [...l[idx].querySelectorAll('button')] : []))
      .find((b) => b.textContent.trim() === ${JSON.stringify(buttonText)})
    if (!hit) return 'no-btn:' + JSON.stringify(st.btns[idx]) + ' bodies=' + st.bodies
    hit.click()
    return 'ok'
  })()`)
  if (res !== 'ok') throw new Error(`点不到版本行按钮（行=${rowPattern} 按钮=${buttonText}）：${res}`)
  await sleep(400)
}

function onCdpMessage(raw) {
  // 🔴 载荷在 `raw.data` 里（`onmessage` 收到的是 `MessageEvent`）—— 写成 `JSON.parse(raw)` 会
  //    把 MessageEvent 序列化成 "[object MessageEvent]" ⇒ 所有回包静默丢弃（详见同族脚本注释）
  let msg
  try {
    msg = JSON.parse(raw.data)
  } catch {
    return
  }
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id)
    pending.delete(msg.id)
    if (msg.error) reject(new Error(msg.error.message))
    else resolve(msg.result)
    return
  }
  if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
    consoleMsgs.push(msg.params.args.map((a) => a.value ?? a.description ?? '').join(' '))
  }
  if (msg.method === 'Network.responseReceived') {
    const r = msg.params.response
    if (/\/(api\/)?doc\/\d+\/(versions|rollback|tags)/.test(r.url)) {
      versionCalls.push({ url: r.url.replace(API, ''), status: r.status, method: msg.params.type })
    }
  }
}
async function waitForEdge() {
  const deadline = Date.now() + 20000
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`)
      const list = await res.json()
      const page = list.find((t) => t.type === 'page')
      if (page?.webSocketDebuggerUrl) return page.webSocketDebuggerUrl
    } catch {
      /* 还没起来 */
    }
    await sleep(300)
  }
  throw new Error('Edge 的 CDP 端口没起来')
}

// ---------------------------------------------------------------- 观测点
/**
 * 某一版自己的向量条数 —— **独立观测点**：直查 Milvus，不看"清理命令执行过没有"。
 *
 * <p>⚠️ 删除的可见性有秒级延迟 ⇒ 期望 0 时**轮询**（同 `milvusVectorCount` 的理由）：
 * 只查一次会把"刚删完"读成"没删掉"，那是**假失败**。
 * 返回 `-1` 表示查询本身失败 —— 它与 0 是两件事，调用方的判据要能分辨。
 */
async function vectorCountOfDoc(docId, expectZero = true) {
  let n = -1
  // 两个方向都轮询：写入与删除**都有秒级可见性延迟**，只查一次会把"刚删完/刚写完"
  // 读成相反的结论 —— 那是**假失败**，与假绿一样费时间
  for (let i = 0; i < 8; i++) {
    const r = milvusPost('entities/query', {
      collectionName: COLLECTION,
      filter: `doc_id in [${docId}]`,
      outputFields: ['count(*)'],
    })
    n = Number(r?.data?.[0]?.['count(*)'] ?? -1)
    // 🔴 查询**本身失败**（-1）立即返回：它不是"还没到"，接着轮询会把它轮成通过
    if (n < 0) return n
    if (expectZero ? n === 0 : n > 0) return n
    await sleep(2000)
  }
  return n
}

/**
 * 轮询一条 SQL 标量直到满足断言（回滚是**同步**接口，但它内部要向量化 ——
 * 拿一个固定 `sleep` 去赌它跑完，就是在赌一个**别人的**耗时）。
 * 返回最后一次的原始字符串，`null` 表示超时。
 */
async function waitSql(sql, pred, timeout = 90000, label = sql) {
  const deadline = Date.now() + timeout
  let last = ''
  while (Date.now() < deadline) {
    last = mysqlScalar(sql)
    if (pred(last)) return last
    await sleep(1000)
  }
  observe(`⚠️ 等待超时（${timeout}ms）：${label}`, `最后一次 = ${JSON.stringify(last)}`)
  return null
}

/** 本脚本提问产生的全部会话 id（**精确清理**用，不靠水位线 —— `T-O23`）。 */
const sessionIds = new Set()

/**
 * 真提问一次，并把"这一问到底命中了哪些文档"量出来。
 *
 * <p>🔴 **先断言打点落了**（`check` 在调用方）：没有打点行时 `hits()` 恒 0，
 * 那会把"旧版本没被召回"验成**假的绿** —— 而"打点没落"与"召回 0 条"在计数上一模一样。
 */
async function ask(message) {
  const floor = mysqlCount('select coalesce(max(id), 0) from sys_retrieval_trace;')
  await api('/chat/completions', { method: 'POST', body: { message, useVision: false } })
  await sleep(1500)
  const row = mysqlScalar(`select id, trace_id, session_id from sys_retrieval_trace where id > ${floor} order by id desc limit 1;`)
  const [id, traceId, sessionId] = row ? row.split('\t') : ['', '', '']
  if (Number(sessionId) > 0) sessionIds.add(Number(sessionId))
  return {
    floor,
    traceId: traceId || '',
    sessionId: Number(sessionId) || 0,
    /**
     * 这次检索时「MySQL 侧算出的可见文档数」（`sys_retrieval_trace.doc_scope_count`）。
     *
     * <p>🔴 它是**归档那一半**（`is_current = 1` 合取项）唯一能被单独观测到的证据：
     * 归档后"多出一篇 v1"如果被算进可见范围，这个数**会 +1**，
     * 而只看"v1 有没有被召回"是**分不出来的** —— 归档同时还清了 v1 的向量，
     * 两半各自都足以让召回为 0（谁干的活说不清）。见 `G7-5` 那两条的分工。
     */
    docScope: Number(mysqlScalar(`select doc_scope_count from sys_retrieval_trace where id > ${floor} order by id desc limit 1;`) || -1),
    /** 这一问命中某篇文档的分片数（`docId` 为 0 时恒 0，用来表达"不关心"）。 */
    hits: (docId) => (docId
      ? mysqlCount('select count(*) from sys_retrieval_trace_chunk c join sys_retrieval_trace t on t.trace_id = c.trace_id '
        + `where t.id > ${floor} and c.doc_id = ${docId};`)
      : 0),
    floorId: Number(id) || 0,
  }
}

// ---------------------------------------------------------------- 状态
let edge
let kbId = 0
let groupId = 0
let docV1 = 0
let docV2 = 0
/** 回滚产出的**新行**（`v3`）—— 它才是回滚后的当前版本。 */
let docV3 = 0
/** 回滚**之前**那次提问的 trace_id：用来验「历史问答的引用不变」（`G7-6` 后半句）。 */
let traceBeforeRollback = ''

async function main() {
  // ---------- ① 前置：必须是真后端模式 ----------
  const diag = await probeDevServer(BASE)
  if (!diag.ok) {
    console.error(reportPrecondition(diag, 'e2e-doc-version'))
    process.exit(EXIT_PRECONDITION)
  }
  if (diag.mode !== 'real') {
    console.error(`\n✗ e2e-doc-version：**前置不满足**（exit ${EXIT_PRECONDITION}）—— 只在**真后端模式**下成立\n`)
    console.error('  • 本脚本验的是"归档清向量 / 回滚重向量化"，mock 模式没有 Milvus 这一层。')
    console.error('  • 下一步：`node temp/start-vite.mjs`（真后端模式 · 5174）。\n')
    process.exit(EXIT_PRECONDITION)
  }
  observe('dev server 就绪（真后端模式）', diag.notes?.join(' / ') ?? '')

  // ---------- ② 产物覆盖率闸（**必须在建探针之前**）----------
  try {
    const cov = assertArtifactCoverage()
    check(true, '产物覆盖率闸通过（带归属列的表格都已登记或豁免）',
      `扫到 ${cov.scanned} 张 · 已登记 ${cov.registered} · 豁免 ${cov.exempt}`)
  } catch (e) {
    check(false, '产物覆盖率闸未通过', e.message)
    throw e
  }

  // ---------- ③ 登录 ----------
  token = bizOk(await api('/auth/login', { method: 'POST', body: { ...PROBE, remember: true }, as: '' }), '登录').accessToken
  check(!!token, '拿到 accessToken')

  // ---------- ④ 基线残留核对（跑批纪律）----------
  const baseChat = mysqlCount('select count(*) from sys_chat_session;')
    + mysqlCount('select count(*) from sys_chat_message;')
    + mysqlCount('select count(*) from sys_retrieval_trace;')
    + mysqlCount('select count(*) from sys_retrieval_trace_chunk;')
  if (baseChat > 0) {
    // 按纪律"开工前清"，但**必须报出来**：残留是事实，不该被静默抹掉（`T-O19`/`T-O23` 那一族）
    const sweptChat = sweepOrphanChatFamily()
    observe('⚠️ 开工前基线**不是**干净的：会话族有残留，已按无主谓词收掉',
      `清前 ${baseChat} 行 → 清掉 ${sweptChat} 行`)
  } else {
    observe('开工前基线：会话族 = 0 行')
  }
  const sweptKb = await sweepAbandonedKbProbes(['kb_e2edocver_'])
  if (sweptKb.length > 0) observe('收掉上一轮（或更早被中断的轮次）留下的探针库', `${sweptKb.length} 个：${sweptKb.join(' · ')}`)

  // ---------- ⑤ 建探针库 ----------
  const created = bizOk(await api('/kb', {
    method: 'POST',
    body: {
      name: `版本历史探针库 ${stamp}`,
      code: KB_CODE,
      deptId: 1,
      visibleRoles: ['USER'],
      businessDomain: '技术文档',
      description: 'S7-M06-D 版本历史页面级联调探针，脚本结束即删',
    },
  }), '建库')
  kbId = created.id
  check(kbId > 0, `探针库已建 · id=${kbId}`)

  // ---------- ⑥ 上传 v1 → 等 READY ----------
  await upload(DOC_NAME, CONTENT_V1)
  docV1 = await latestDocId()
  check(docV1 > 0, 'v1 已入库', `docId=${docV1}`)
  await waitDocReady(docV1)
  groupId = Number(mysqlScalar(`select doc_group_id from sys_document where id = ${docV1};`) || 0)
  check(groupId === docV1, '🔴 新组的 `doc_group_id` 就指向自己（`V39` 的回填口径：每篇各自成组、v1、当前）',
    `group_id=${groupId} doc_id=${docV1}`)
  check(Number(mysqlScalar(`select version_no from sys_document where id = ${docV1};`)) === 1
    && truthy(`select is_current from sys_document where id = ${docV1};`), 'v1 是 `version_no=1` 且 `is_current=1`')

  // ---------- ⑦ A 段：归档前，v1 的正文搜得到 ----------
  const askA1 = await ask(MAGIC_V1)
  check(!!askA1.traceId, 'A1 提问落了打点（否则下面"搜不到"的判据会假绿）', `trace=${askA1.traceId} 会话=${askA1.sessionId}`)
  check(askA1.hits(docV1) > 0, '🔴 **A 段**：归档前用 v1 独有的关键词提问，召回到了 v1',
    `命中 v1 分片 ${askA1.hits(docV1)} 条`)

  // ---------- ⑧ 同名重传 v2（内容不同）----------
  const beforeReuploadChunks = mysqlCount(`select count(*) from sys_chunk where doc_id = ${docV1};`)
  await upload(DOC_NAME, CONTENT_V2)
  docV2 = await latestDocId()
  check(docV2 > 0 && docV2 !== docV1, '同名重传落成**另一行**文档（不是覆盖同一行）', `v1=${docV1} v2=${docV2}`)
  check(Number(mysqlScalar(`select doc_group_id from sys_document where id = ${docV2};`)) === groupId,
    '🔴 新行落进**同一个版本组**（同名 = 更新，不是新文档 —— `I-5`）', `group_id=${groupId}`)
  check(Number(mysqlScalar(`select version_no from sys_document where id = ${docV2};`)) === 2,
    '🔴 新行的 `version_no = 2`', `version_no=${mysqlScalar(`select version_no from sys_document where id = ${docV2};`)}`)
  check(truthy(`select is_current from sys_document where id = ${docV2};`), '新行是当前版本')
  check(!truthy(`select is_current from sys_document where id = ${docV1};`),
    '🔴 旧当前行被翻成 `is_current = 0`（归档 —— 不删行、不改写历史）')
  check(Number(mysqlScalar(
    `select count(*) from sys_document where doc_group_id = ${groupId} and is_current = 1 and deleted_at is null;`)) === 1,
    '组内**恰有一行**是当前版本（两行都 current 会让检索召回两份）')
  check(mysqlCount(`select count(*) from sys_chunk where doc_id = ${docV1};`) === beforeReuploadChunks,
    '🔴 旧版本的分片**原样保留**（回滚要靠它们复制）',
    `${beforeReuploadChunks} → ${mysqlCount(`select count(*) from sys_chunk where doc_id = ${docV1};`)}`)

  await waitDocReady(docV2)
  const vecV1 = await vectorCountOfDoc(docV1, true)
  const vecV2 = await vectorCountOfDoc(docV2, false)
  check(vecV1 === 0, '🔴 归档后 v1 的 Milvus 向量 = 0（**直查 Milvus** —— 不看清理命令的返回值）',
    `v1 向量=${vecV1}`)
  check(vecV2 > 0, 'v2 的向量已写入（否则下面的检索判据没有起点）', `v2 向量=${vecV2}`)

  // ---------- ⑨ 版本列表（`T-606`）----------
  const vl = bizOk(await api(`/doc/${docV2}/versions`), '版本列表')
  check(vl.current === 'v2', '🔴 `current` 取的是 `is_current=1` 那一行（不是 `max(version_no)`）', `current=${vl.current}`)
  check(vl.list?.length === 2, '组内两版都在列表里', `行数=${vl.list?.length}`)
  check(vl.list?.[0]?.version === 'v2' && vl.list?.[0]?.current === true,
    '列表按 `version_no` 降序、当前版本在最前', vl.list?.map((x) => `${x.version}${x.current ? '(当前)' : ''}`).join(' · '))
  check(vl.list?.find((x) => x.version === 'v1')?.hasNewer === true,
    '🔴 旧版本带「已有更新版本」标记（`hasNewer`）')
  check(vl.list?.find((x) => x.version === 'v2')?.hasNewer === false, '当前版本**不带**「已有更新版本」标记')
  const vlFromV1 = bizOk(await api(`/doc/${docV1}/versions`), '版本列表(从旧版 id 进)')
  check(vlFromV1.list?.length === 2 && vlFromV1.current === 'v2',
    '入参是**组内任意一版**的 doc id，看到的是同一个版本组（旧版页面上也要有兄弟版本）',
    `从 v1 进：${vlFromV1.current} · ${vlFromV1.list?.length} 行`)

  // ---------- ⑩ 版本对比（`T-607` · 仅元数据）----------
  const diff = bizOk(await api(`/doc/${docV2}/versions/v1/diff?against=v2`), '版本对比')
  check(diff.left?.version === 'v1' && diff.right?.version === 'v2',
    '对比返回两版各自的元数据', `left=${diff.left?.version} right=${diff.right?.version}`)
  check(diff.textDiff === null,
    '🔴 `textDiff` **恒 `null`** —— 需求 §6.8 的"接口预留"，**不是**「两版正文没差别」'
      + '（页面文案必须照这个口径写）')
  check(Number(diff.left?.size) > 0 && Number(diff.left?.chunkCount) > 0 && !!diff.left?.owner && !!diff.left?.at,
    '元数据字段齐（大小 / 分片 / 上传人 / 时间）',
    `size=${diff.left?.size} chunks=${diff.left?.chunkCount} owner=${diff.left?.owner} at=${diff.left?.at}`)
  const diffCur = bizOk(await api(`/doc/${docV2}/versions/v1/diff`), '版本对比(缺省 against)')
  check(diffCur.right?.version === 'v2',
    '🔴 `against` 留空 = 与**当前版本**比（这是后端语义，不是"没指定就不比"）', `right=${diffCur.right?.version}`)
  const diffSelf = await api(`/doc/${docV2}/versions/v2/diff?against=v2`)
  check(diffSelf.json?.code === 10030,
    '🔴 与自己是同一版时**拒绝**（回一份全等数据看起来就像"两版没差别"—— 那是一句错误断言）',
    `code=${diffSelf.json?.code} msg=${diffSelf.json?.message ?? ''}`)

  // ---------- ⑪ 标签（`T-609` · 契约保留、最小实现）----------
  const tagsRes = await api(`/doc/${docV2}/tags`)
  check(tagsRes.json?.code === 0 && Array.isArray(tagsRes.json?.data) && tagsRes.json.data.length === 0,
    '`GET /doc/{id}/tags` 回**空数组**且 `code=0`（如实为空，没有编造标签）',
    `code=${tagsRes.json?.code} data=${JSON.stringify(tagsRes.json?.data)}`)

  // ---------- ⑫ B 段：归档后 v1 不再被召回（`G7-5`）----------
  const askB1 = await ask(MAGIC_V2)
  check(!!askB1.traceId, 'B1 提问落了打点', `trace=${askB1.traceId} 会话=${askB1.sessionId}`)
  check(askB1.hits(docV2) > 0, '🔴 **B 段**：用 v2 独有的关键词提问，召回到了 v2',
    `命中 v2 分片 ${askB1.hits(docV2)} 条`)
  traceBeforeRollback = askB1.traceId

  const askB2 = await ask(MAGIC_V1)
  check(!!askB2.traceId, 'B2 提问落了打点（这一条是下面那个"0"的前提）', `trace=${askB2.traceId}`)
  check(askB2.hits(docV1) === 0,
    '🔴🔴 **`G7-5`**：归档后同一个问句**再也搜不到 v1**'
      + '（`visibleDocIds` 的 `is_current = 1` 合取项 + 归档时清向量，两条路都在）',
    `命中 v1 分片 ${askB2.hits(docV1)} 条`)
  // 🔴 上面那条"搜不到"**分不出是哪一半**干的活：归档同时也清了 v1 的向量，
  //    两半各自都足以让召回归零。所以再钉一条**只对"可见范围那一半"敏感**的观测点：
  //    归档不增加可见文档数（去掉 `is_current = 1`，这个数会 +1，而召回仍然是 0）。
  //    实测（本文档 §8 的证伪）：只拆掉过滤条件时，**只有这一条变红**。
  check(askB2.docScope === askA1.docScope && askB2.docScope > 0,
    '🔴 **归档不增加可见文档数**（`doc_scope_count` 前后相同）—— 这一条单独证明'
      + '「检索侧 `is_current = 1`」在起作用（`G7-5` 的另一半是"归档清向量"，两条各证一条）',
    `归档前 ${askA1.docScope} → 归档后 ${askB2.docScope}`)

  // ---------- ⑬ 页面级：版本 Tab ----------
  edge = spawn(EDGE, [
    '--headless=new', `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${PROFILE}`,
    '--no-first-run', '--no-default-browser-check', '--window-size=1440,900', 'about:blank',
  ], { stdio: 'ignore' })
  ws = new WebSocket(await waitForEdge())
  await new Promise((res, rej) => {
    const timer = setTimeout(() => rej(new Error('CDP 握手超时（15s）—— 检查是否有同 profile 的残留 Edge')), 15000)
    ws.onopen = () => {
      clearTimeout(timer)
      res()
    }
    ws.onerror = (e) => {
      clearTimeout(timer)
      rej(new Error(`CDP 握手失败：${e?.message ?? 'unknown'}`))
    }
  })
  ws.onmessage = onCdpMessage
  const targets = await send('Target.getTargets')
  observe('已连上的浏览器目标',
    (targets.targetInfos ?? []).map((t) => `${t.type}:${String(t.url).slice(0, 40)}`).join(' | ') || '(无)')
  // 🔴 断言"连到的确实是我刚起的那个"：命令行最后一项是 `about:blank`，**只有新实例**会是它
  //    （别人的浏览器里展开的是他自己的标签页）。不判这一条，就连不上也照样往下跑。
  const mine = (targets.targetInfos ?? []).some((t) => t.type === 'page' && t.url === 'about:blank')
  check(mine, '🔴 连上的浏览器**是本轮新起的那个**（目标里有我给它的 `about:blank`）——'
    + '否则后面每条页面判据都在验别人的进程，而它看起来是绿的',
  `port=${CDP_PORT} 目标数=${(targets.targetInfos ?? []).length}`)
  if (!mine) throw new Error(`CDP ${CDP_PORT} 上没有本轮的 about:blank 目标 —— 端口被别的浏览器占了`)
  await send('Page.enable')
  await send('Runtime.enable')
  await send('Network.enable')
  await send('DOM.enable')
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

  // 路由是 hash 模式；token 存 localStorage，而 `useAuthStore` **只在创建时读一次**。
  //
  // 🔴 顺序有讲究（本批实测踩过，`#/chat` 那一版报告就是它）：
  //    ① 先在应用里种 token；② 再**整页加载**目标路由（带一次性查询串，保证是文档级导航）。
  //    ⚠️ 不能写成"导航到目标路由 → `Page.reload`"：`goto` 之后路由守卫可能**先一步**
  //    发现"未登录"而把地址改写成登录页 ⇒ 随后那次 reload 重载的是**被改写后的地址**，
  //    应用起来后落在首页（实测落在 `#/chat`）⇒ `.page-title` 永远不出现。
  //    那是**竞态**（守卫与 reload 谁先跑），所以它在快机器上时绿时红 —— 实测 3 绿 1 红。
  //    "带 token 直接整页加载目标路由"没有这个竞态：store 启动即已登录。
  await goto(`${BASE}/#/login`)
  await evaluate(`localStorage.setItem('ak_token', ${JSON.stringify(token)}); localStorage.setItem('ak_remember','1'); 'ok'`)
  await goto(`${BASE}/?e2e=${stamp}#/doc/${docV2}`)
  try {
    await waitFor(`!!document.querySelector('.page-title')`, { timeout: 20000, label: '详情页挂载' })
  } catch (e) {
    // 🔴 **失败时必须给出可诊断的快照**。原来这一条只报"等待超时"—— 于是"页面白屏"
    //    （模块没加载上）、"停在登录页"（token 没被认）、"跳到了别的路由"三种完全不同的
    //    病因在报告里长得一模一样，只能靠重跑和猜（本批实测就为此多花了一轮）。
    const snap = await evaluate(`JSON.stringify({
      url: location.href,
      title: document.title,
      appChildren: document.querySelector('#app')?.children.length ?? -1,
      text: (document.body?.innerText ?? '').replace(/\\s+/g, ' ').slice(0, 160),
      hasTitle: !!document.querySelector('.page-title'),
    })`).catch((x) => `（快照求值也失败：${x.message}）`)
    throw new Error(`${e.message}\n    诊断快照：${snap}\n    控制台错误：${consoleMsgs.slice(0, 3).join(' | ') || '(无)'}`)
  }

  // 切到「版本历史」Tab
  await clickTab('版本历史')
  const paneOk = await waitFor(`!!document.querySelector('#pane-version')`, { timeout: 8000, label: '版本 Tab 面板渲染' })
  check(!!paneOk, '版本 Tab 面板已渲染（`#pane-version`）')
  const st = await waitVersionRows(1, '版本表渲染')
  check(st.rows.length === 2 && st.rows.some((r) => /^v1\b/.test(r)) && st.rows.some((r) => /^v2\b/.test(r)),
    '🔴 页面**真的渲染了组内两版**（而不是渲染一张恒空的表）',
    `${st.rows.join(' // ')} · body 数=${st.bodies} 各${JSON.stringify(st.shapes)}`)
  check(st.rows.some((r) => /^v1\b/.test(r) && r.includes('已有更新版本')),
    '🔴 页面上 v1 那一行标着「已有更新版本」（需求 §6.4 的标记）')
  check(st.rows.some((r) => /^v2\b/.test(r) && r.includes('当前')), '页面上 v2 那一行标着「当前」')
  check(st.btns.some((b) => b.includes('回滚')),
    '操作列里**确实有**「回滚」按钮（否则下面那条"当前行没有回滚按钮"会假绿）',
    `各行按钮=${JSON.stringify(st.btns)}`)
  const curIdx = st.rows.findIndex((r) => r.includes('当前'))
  const v1Idx = st.rows.findIndex((r) => /^v1\b/.test(r))
  check(curIdx >= 0 && !st.btns[curIdx].includes('回滚'),
    '🔴 **判据 13**：当前版本那一行**没有回滚按钮**',
    `行#${curIdx}（${st.rows[curIdx] ?? '—'}）按钮=${JSON.stringify(st.btns[curIdx] ?? [])}`)
  check(v1Idx >= 0 && st.btns[v1Idx].includes('回滚'),
    '🔴 非当前版本那一行**有**回滚按钮（"谁都能回滚"与"谁都不能"都会让上一条失去意义）',
    `行#${v1Idx} 按钮=${JSON.stringify(st.btns[v1Idx] ?? [])}`)

  // ---------- ⑭ 页面级：对比 → 真请求 + 元数据对话框 ----------
  await clickVersionOp('/^v1\\b/', '对比')
  const dlg = await waitFor(`(() => {
      // ⚠️ 判「可见」**不能**用 offsetParent !== null：EP 的 .el-overlay 是 position: fixed，
      //    而规范规定 fixed 定位元素的 offsetParent **恒为 null** ⇒ 对话框开着的每一刻
      //    都会被判成"没出现"（一条恒红的判据，且红得像"按钮没接上"）。
      //    getClientRects().length 只对 display:none 为 0，与定位方式无关。
      const d = [...document.querySelectorAll('.el-dialog')].find((x) => x.getClientRects().length > 0)
      if (!d) return null
      const table = d.querySelector('.el-table')
      const rows = table ? [...table.querySelectorAll('tbody tr')] : []
      return { title: (d.querySelector('.el-dialog__title')?.textContent ?? '').trim(), rows: rows.length, text: d.innerText.replace(/\\n+/g, ' ').trim() }
  })()`, { timeout: 10000, label: '对比对话框出现' })
  check(dlg.rows >= 5, '🔴 对比对话框渲染了元数据对照表（大小 / 分片 / 表格 / 上传人 / 时间）',
    `行数=${dlg.rows} 标题=${dlg.title}`)
  check(versionCalls.some((r) => r.url.includes('/versions/v1/diff')),
    '🔴 点「对比」**真的发了 `GET /doc/{id}/versions/v1/diff`**（接真之前它只弹一句 `ElMessage.info`）',
    versionCalls.map((r) => `${r.url}=${r.status}`).join(' · ') || '(一个都没发)')
  check(dlg.text.includes('不代表两版正文相同'),
    '🔴 对话框**说清了"正文文本差异本版未做"**（`textDiff` 恒 null 不能被读成"两版正文一样"）')
  await evaluate(`(() => { const b = [...document.querySelectorAll('.el-dialog__headerbtn')].pop(); if (b) b.click(); return 'ok' })()`)
  await sleep(500)

  // ---------- ⑮ 页面级：回滚 → 真请求 ----------
  await clickVersionOp('/^v1\\b/', '回滚')
  await waitFor(`!!document.querySelector('.el-message-box')`, { timeout: 8000, label: '回滚确认框出现' })
  check(true, '点「回滚」先弹确认框（不会一按就动索引）')
  const confirmText = await evaluate(`document.querySelector('.el-message-box')?.innerText.replace(/\\n+/g, ' ').trim() ?? ''`)
  check(confirmText.includes('历史问答的引用不会改变'),
    '🔴 确认框里逐字写着「历史问答的引用不会改变」（`G7-6` 的口径要在动手之前告诉用户）',
    confirmText.slice(0, 120))
  await evaluate(`(() => {
    const b = [...document.querySelectorAll('.el-message-box__btns button')].find((x) => x.textContent.includes('确定'))
    if (!b) return 'no-btn'
    b.click()
    return 'ok'
  })()`)
  await sleep(2500)
  check(versionCalls.some((r) => r.url.includes('/rollback')),
    '🔴 确认之后**真的发了 `POST /doc/{id}/rollback`**（接真之前它只弹一句"已回滚"）',
    versionCalls.map((r) => `${r.url}=${r.status}`).join(' · ') || '(一个都没发)')

  // ---------- ⑯ 回滚之后：库侧事实 ----------
  // 🔴 先**轮询**"当前版本变成了新那一条"，再断言别的。回滚是同步接口，但它内部要
  //    复制分片 + 向量化（还要打一次 embedding）—— 拿一个固定 `sleep` 去赌它跑完，
  //    赌的是**别人的**耗时，快机器上绿、慢机器上红，而红的是环境不是代码。
  const curNo = await waitSql(
    `select version_no from sys_document where doc_group_id = ${groupId} and is_current = 1 and deleted_at is null limit 1;`,
    (v) => v === '3', 90000, '回滚产出的新版本成为当前版本')
  check(curNo === '3',
    '🔴 回滚把**新造的那一版**立为当前版本（`version_no = 3` = 组内 max+1）', `当前版本号=${curNo ?? '超时未变'}`)
  docV3 = Number(mysqlScalar(
    `select id from sys_document where doc_group_id = ${groupId} and version_no = 3 and deleted_at is null limit 1;`) || 0)
  check(docV3 > 0 && docV3 !== docV2 && docV3 !== docV1, '🔴 回滚产出**新的一行**版本（不改写历史）',
    `v1=${docV1} v2=${docV2} 新行=${docV3}`)
  check(Number(mysqlScalar(`select count(*) from sys_document where doc_group_id = ${groupId} and deleted_at is null;`)) === 3,
    '🔴 组内版本数 3（**只增不减** —— 需求 §6.4「保留一条新版本记录而不是改写历史」）')
  check(Number(mysqlScalar(
    `select count(*) from sys_document where doc_group_id = ${groupId} and is_current = 1 and deleted_at is null;`)) === 1,
    '回滚后组内**仍恰有一行**是当前版本')
  check(truthy(`select is_current from sys_document where id = ${docV3};`)
    && !truthy(`select is_current from sys_document where id = ${docV2};`),
    '🔴 新行成为当前版本、原当前版本（v2）退出当前版本')
  check(mysqlCount(`select count(*) from sys_chunk where doc_id = ${docV3};`) > 0,
    '🔴 新版本**复制到了分片**（复制不到会回一句"该版本分片不可用"而不是产出空索引）',
    `新行分片 ${mysqlCount(`select count(*) from sys_chunk where doc_id = ${docV3};`)} 条`)
  check(String(mysqlScalar(`select storage_path from sys_document where id = ${docV3};`))
    === String(mysqlScalar(`select storage_path from sys_document where id = ${docV1};`)),
    '新版本的 `storage_path` 指向**同一个 MinIO 对象**（回滚复制的是分片，不是重新传一份原件）')
  const vecV2After = await vectorCountOfDoc(docV2, true)
  const vecV3After = await vectorCountOfDoc(docV3, false)
  check(vecV2After === 0, '🔴 原当前版本（v2）的向量已清 0', `v2 向量=${vecV2After}`)
  check(vecV3After > 0, '🔴 新版本**重新向量化**了（`G7-6` 的字面要求：回滚要重向量化，不是只改一列）',
    `新行向量=${vecV3After}`)
  check(mysqlCount(`select count(*) from sys_audit_log where action='版本回滚' and result='SUCCESS';`) >= 1,
    '审计留痕：文档 / 版本回滚（需求 05 §6 的动作表）')

  // ---------- ⑰ `G7-6` 后半句：历史问答的引用不变 ----------
  check(!!traceBeforeRollback
    && mysqlCount(`select count(*) from sys_retrieval_trace_chunk where trace_id = '${traceBeforeRollback}' and doc_id = ${docV2};`) > 0,
    '🔴🔴 **回滚之前那次提问的打点，回滚之后仍然指向它当初命中的那一版**'
      + '（回滚不改写历史 ⇒ 历史答案的来源角标仍然点得开）',
    `trace=${traceBeforeRollback} 指向 v2 的明细 ${traceBeforeRollback ? mysqlCount(`select count(*) from sys_retrieval_trace_chunk where trace_id = '${traceBeforeRollback}' and doc_id = ${docV2};`) : 0} 条`)

  // ---------- ⑱ A 段回来：检索回到 v1 的内容 ----------
  const askC1 = await ask(MAGIC_V1)
  check(!!askC1.traceId, 'C1 提问落了打点', `trace=${askC1.traceId}`)
  check(askC1.hits(docV3) > 0,
    '🔴🔴 **`G7-6`**：回滚后用 v1 的关键词提问，召回到的是**新版本那一行**'
      + '（回滚不是"把 v1 那行翻回当前"，而是新造一版 —— 所以命中必须落在 v3）',
    `命中 ${askC1.hits(docV3)} 条`)
  check(askC1.hits(docV2) === 0, '🔴 回滚后 v2 不再被召回（当前版本只有一个）', `命中 v2 ${askC1.hits(docV2)} 条`)

  // ---------- ⑲ 页面级：回滚后列表真的刷新了 ----------
  const st2 = await waitVersionRows(3, '版本表刷新出 3 行')
  check(st2.rows.length === 3 && st2.rows.some((r) => /^v3\b/.test(r) && r.includes('当前')),
    '🔴 回滚成功后页面**重新拉取了版本列表**（3 行、v3 标当前）—— 不重取会把"当前"标在已归档的那一版上',
    st2.rows.join(' // '))

  // ---------- ⑳ 无未捕获异常 ----------
  check(consoleMsgs.length === 0, '页面控制台无未捕获异常', consoleMsgs.slice(0, 2).join(' | '))
}

// ---------------------------------------------------------------- 辅助
async function upload(filename, content) {
  const form = new FormData()
  form.append('file', new Blob([content], { type: 'text/markdown' }), filename)
  return bizOk(await api(`/kb/${kbId}/doc/upload`, { method: 'POST', body: form, raw: true }), `上传 ${filename}`)
}

/** 该探针库里 id 最大的文档行（= 刚上传的那一行；同名归档会**新建行**）。 */
const latestDocId = async () => Number(mysqlScalar(
  `select id from sys_document where kb_id = ${kbId} and deleted_at is null order by id desc limit 1;`) || 0)

/** `TINYINT(1)` 经过 `mysql -N` 出来是 `1`/`0`，但空值会是字面量 `NULL` ⇒ 一并判掉。 */
const truthy = (sql) => mysqlScalar(sql) === '1'

/** 点 Tab（EP 的 Tab 栏项；按文本找，避免依赖下标）。 */
async function clickTab(name) {
  const ok = await evaluate(`(() => {
    const el = [...document.querySelectorAll('.el-tabs__item')].find((n) => n.textContent.includes(${JSON.stringify(name)}))
    if (!el) return false
    el.click()
    return true
  })()`)
  if (!ok) throw new Error(`点不到 Tab：${name}`)
  await sleep(400)
}

/**
 * 等**指定的那一行文档**到 `READY`（本仓口径：READY 是唯一"可被检索"的状态）。
 *
 * <p>⚠️ 刻意**不**数"这个库里有几篇 READY"：归档之后 v1 仍然留在同一个库里，
 * 于是"数到 2 篇 READY"在"v1 变 READY、v2 还没开始解析"时**就已经成立**了 ——
 * 那会让后面所有"v2 内容被召回"的判据跑在一个还没解析完的 v2 上。
 * 判据要钉在**唯一确定的那个对象**上（`(doc_id)` 那一行），不钉在"数量够不够"。
 */
async function waitDocReady(docId, timeout = 180000) {
  const deadline = Date.now() + timeout
  let last = '（列表里没有这一行）'
  while (Date.now() < deadline) {
    const list = bizOk(await api(`/kb/${kbId}/docs?page=1&pageSize=50`), '文档列表').list ?? []
    const row = list.find((d) => Number(d.id) === docId)
    last = row ? `${row.id}:${row.status}` : last
    if (row?.status === 'READY') return
    if (row?.status === 'FAILED') throw new Error(`文档 ${docId} 解析失败（status=FAILED）`)
    await sleep(1500)
  }
  throw new Error(`等待文档 ${docId} 到 READY 超时（${timeout}ms）：${last}`)
}

run()
  .catch((e) => {
    check(false, '脚本执行中断', e.message)
  })
  .finally(async () => {
    // 收尾必须走 finally：中途任何一步抛错都要清干净（`#186`）
    try {
      if (ws) {
        try {
          await send('Browser.close')
        } catch {
          /* 已经关了 */
        }
      }
    } catch {
      /* 忽略 */
    }
    try {
      edge?.kill()
    } catch {
      /* 忽略 */
    }
    // 🔴 `edge.kill()` 只杀**启动器**那一个进程；Edge 会再拉起十几个子进程，
    //    它们仍锁着 profile 目录里的文件 ⇒ 紧随其后的 `rmSync` **静默失败**
    //    （本批实测：5 轮跑完留下 5 个 profile 目录 + 一堆 msedge）。
    //    所以按**进程树**杀（`/T`），再删，再**断言删掉了** —— 不然"收尾干净"是一句空话。
    if (edge?.pid) {
      try {
        spawnSync('taskkill', ['/F', '/T', '/PID', String(edge.pid)], { stdio: 'ignore' })
      } catch {
        /* 进程可能已退出 */
      }
    }
    await sleep(800)
    try {
      rmSync(PROFILE, { recursive: true, force: true })
    } catch {
      /* 忽略 —— 下面那条断言会把它暴露出来 */
    }
    check(!existsSync(PROFILE), '收尾：浏览器 profile 目录已删（Edge 的进程树要真被杀掉，'
      + '否则目录被锁住、`rmSync` 静默失败）', PROFILE)

    // 🔴 本轮**提问**产生的会话族：按**精确 id** 删（`T-O23`）。
    //    水位线只保护"本轮造的"且够不着异常退出的轮次，精确 id 才是正解。
    try {
      for (const sid of sessionIds) {
        mysqlScalar(
          `delete from sys_retrieval_trace_chunk where trace_id in (select trace_id from sys_retrieval_trace where session_id = ${sid});`
          + `delete from sys_retrieval_trace where session_id = ${sid};`
          + `delete from sys_chat_message where session_id = ${sid};`
          + `delete from sys_chat_session where id = ${sid};`)
      }
    } catch (e) {
      check(false, '收尾：会话族精确清理抛错', e.message)
    }

    let clean = false
    try {
      const r = await teardownKbProbe(KB_CODE)
      // ⚠️ 判据喂**残留对象**、说明喂**描述字符串** —— 喂反了会恒为 false（同族脚本踩过）
      const residue = r.residue ?? r
      clean = kbResidueClean(residue)
      check(clean, '收尾：探针库族残留 = 0（断言，不是日志）', describeKbResidue(residue))
      const chatLeft = mysqlCount('select count(*) from sys_chat_session;')
        + mysqlCount('select count(*) from sys_chat_message;')
        + mysqlCount('select count(*) from sys_retrieval_trace;')
        + mysqlCount('select count(*) from sys_retrieval_trace_chunk;')
      check(chatLeft === 0,
        '收尾：**会话族**残留 = 0（只核探针库族不够 —— 两族都要断言）',
        `剩余 ${chatLeft} 行`)
    } catch (e) {
      check(false, '收尾：残留自检自身抛错', e.message)
    }

    const failed = steps.filter((s) => !s.ok)
    console.log(`\n${failed.length === 0 ? '✓' : '✗'} ${steps.length - failed.length}/${steps.length} 通过`)
    console.log(`探针库 id=${kbId} (code=${KB_CODE}) · 组=${groupId} · 文档 id：v1=${docV1} v2=${docV2} v3=${docV3}`)
    console.log(`库 id 复核（按 code 查）：${KB_CODE}=${kbIdsOf(KB_CODE)}`)
    if (failed.length > 0) {
      console.log('\n未通过：')
      failed.forEach((s) => console.log(`  ✗ ${s.label}${s.detail ? ` · ${s.detail}` : ''}`))
      process.exit(1)
    }
    if (!clean) process.exit(2)
    process.exit(0)
  })

async function run() {
  await main()
}
