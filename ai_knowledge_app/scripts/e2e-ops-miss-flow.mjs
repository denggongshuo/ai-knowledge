/**
 * `S8-M10-A` 页面级 E2E：问答运营页的**未命中清单**切真后端（`T-A00`）。
 *
 * <h3>覆盖的判据</h3>
 * <ul>
 *   <li><b>`G8-2`</b> —— 清单来自**真提问沉淀**：问一个知识库里根本没有的问题 ⇒
 *       打点 `miss_flag=1` ⇒ 池子出现那一行。⚠️ **不是插一条假数据**：本脚本走的是
 *       「上传 → 解析 → 索引 → 提问」的完整链路，池子里的行是链路自己产出的；</li>
 *   <li><b>`G8-3` / `G8-4`</b> —— 页面点「忽略」→ 状态变、**侧边栏角标跟着变**；</li>
 *   <li><b>`G6-13`（S6 挂起项，与 `S8-M10-A` 合并验收）</b> ——
 *       `/notices/pending` 的未命中数与 `/ops/qa/overview` 的 `pending`、与 11 页统计卡、
 *       与侧边栏角标**是同一个数**；</li>
 *   <li>需求 §1.10 —— 清单条数与统计卡的「未命中提问」两个口径都如实给出（见下）。</li>
 * </ul>
 *
 * <h3>🔴 为什么必须走真链路造未命中</h3>
 * `miss_flag = 1` 的条件是「重排真的跑过 + 有候选 + Top1 < 判定线」三条缺一不可。
 * 直接往 `sys_qa_miss` 插一行的话，验的只是"表能读"——而这一批最容易坏的地方
 * （沉淀有没有接上、归一化有没有生效、判定线有没有区分力）**一条都验不到**。
 * 实测：不相关问句的 rerank 分是 `0.000016`，判定线 `0.35` —— 夹在中间，所以造得出来。
 *
 * <h3>技术路线</h3>
 * 与 `e2e-notice-flow.mjs` 同：Node 自带 WebSocket 直驱无头 Edge 的 CDP，**零新增依赖**；
 * 断言看业务 `code` 不看 HTTP 码。
 *
 * <h3>自清纪律</h3>
 * 探针库走 `teardownKbProbe`（与其它脚本同一条路）；**池子行由本脚本按 miss_key 删** ——
 * 提问人是种子超管（`admin`），他的行**无主兜底够不着**（人还在），
 * 所以必须自己删（`ProbeCleanup#sweepOrphanMisses` 的 javadoc 写明了这个射程）。
 *
 * <h3>前置</h3>
 * <pre>
 *   后端 8080：mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
 *   前端 5174：VITE_USE_MOCK=false npm run dev -- --host 127.0.0.1 --port 5174
 *   运行：node scripts/e2e-ops-miss-flow.mjs --base=http://127.0.0.1:5174
 * </pre>
 *
 * ⚠️ **本文件没有 shebang**：本机 shell 的路径守卫会拦下含 `#!` 的内容。
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, rmSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import { mysqlScalar, mysqlCount, teardownKbProbe, purgeUserScopedArtifacts } from './lib/probe-teardown.mjs'

const arg = (name, fallback) => (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://localhost:5174')
const API = arg('api', 'http://localhost:8080')

const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-e2e-opsmiss'
const OUT_DIR = 'F:/work/code/ai-knowledge/temp/e2e-opsmiss'
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9339)

/** 固定账号：种子超管（有 `ops:qa:view` + `ops:qa:handle`，且它是唯一能走完整条链路的角色之一）。 */
const PROBE = { account: 'admin', password: 'Admin@123' }

/**
 * 探针问题：**故意的知识库里没有的东西**。
 *
 * ⚠️ 它必须"明显不相关"——相关性一高，rerank 分就过线，`miss_flag` 就是 0，
 * 于是这整条判据会**假绿**（池子空着，而"池子空"与"确实没人问倒过"长得一样）。
 */
const MISS_QUESTION = '量子纠缠的贝尔不等式怎么推导？'

const stamp = Date.now()
const KB_CODE = `kb_e2emiss_${stamp}`

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
async function api(path, { method = 'GET', body, raw = false } = {}) {
  const res = await fetch(`${API}/api${path}`, {
    method,
    headers: {
      ...(raw ? {} : body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
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
const ledger = []
const consoleMsgs = []

function send(method, params = {}) {
  const id = ++msgId
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject })
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
  await sleep(600)
  await evaluate(PAGE_TOOLS)
}

/**
 * 页面里能用的探针小工具。
 *
 * ⚠️ **每次导航之后都要重新注入** —— 导航会清掉上一次的注入，而现象是
 * 「探针说找不到元素」，看着像页面没渲染（本批在 `e2e-notice-flow` 上踩过两轮）。
 */
const PAGE_TOOLS = `
window.__m = {
  txt: (sel) => document.querySelector(sel)?.innerText?.trim() ?? null,
  rows: () => [...document.querySelectorAll('.el-table__body tbody tr')].map((tr) => tr.innerText.trim()),
  byText: (tag, text) => [...document.querySelectorAll(tag)].find((e) => (e.innerText ?? '').includes(text)) ?? null,
  badge: (title) => {
    const item = [...document.querySelectorAll('.el-menu-item, .nav-item, li')]
      .find((e) => (e.innerText ?? '').trim().startsWith(title))
    return item ? (item.querySelector('.nav-badge')?.innerText ?? '').trim() : null
  },
}
'ok'`

// ---------------------------------------------------------------- 主流程
let edge
let kbId
let missKeys = []
/** 问答那四张表的水位线（提问会自己开一个会话，见 ④）。 */
let sessionFloor = 0
/**
 * 🔴 **本轮到底问没问过** —— 收尾的守卫看**它**，不看 `sessionFloor` 的值。
 *
 * 由来（`T-O19` 剩半，`S8-M10-D` 现场定位）：水位线取 `coalesce(max(id), 0)`，
 * 而 **表为空时它就是 0** —— 而“表为空”正是本仓**基线干净时的常态**（每批收尾都把会话族清空）。
 * 于是 `if (sessionFloor)` 把“干净的起点”误判成“没有活要干”，**整段清理被跳过**，
 * 而那恰恰是在干净环境里**必然发生**的（脏环境里反而不发生 ⇒ 偶发得像环境问题）。
 *
 * ⚠️ `sessionFloor` 的**值**仍然是对的（`delete ... where session_id > floor` 用它当阈值），
 * 错的是**拿它当守卫** —— 守卫要问“本轮做没做事”，不是“我抓到的那个值是多少”。
 */
let askedThisRun = false
/**
 * 探针账号（**种子超管**）的 id。
 *
 * <p>⚠️ 不能调 `teardownUserProbe` —— 它会把账号本身删掉，而这是**种子**账号。
 * 所以只清「按 id / 按日期归属」的那几张表（见 `purgeUserScopedArtifacts` 的 javadoc）。
 */
let loginUserId = 0

async function main() {
  // ---------- ① 登录 ----------
  const login = await api('/auth/login', { method: 'POST', body: { ...PROBE, remember: true } })
  const loginData = bizOk(login, '登录')
  token = loginData.accessToken
  // 记下 id：收尾要清「按人归属、而会话水位线够不着」的那几张表（`T-O19`）
  loginUserId = Number(loginData.user?.id ?? 0)
  check(!!token, '登录成功（种子超管）')

  // ---------- ② 建探针库 ----------
  const created = bizOk(await api('/kb', {
    method: 'POST',
    body: {
      name: `未命中探针库 ${stamp}`,
      code: KB_CODE,
      deptId: 1,
      visibleRoles: ['USER'],
      businessDomain: '技术文档',
      description: 'S8-M10-A 未命中清单页面级联调探针，脚本结束即删',
    },
  }), '建库')
  kbId = created.id
  check(!!kbId, `探针库已建 · id=${kbId}`)

  // ---------- ③ 上传一篇真内容并等索引 ----------
  // 内容要"有实质"，否则召回为空 ⇒ miss_flag 恒 0（空召回不算未命中，G5-11 早分流了）
  const content = [
    '# 季度报价单（2026Q3）',
    '',
    '型号 A-100 单价 1800 元，交期 7 天；型号 A-200 单价 2400 元，交期 15 天。',
    '所有型号均支持定制，定制件的交期在原交期基础上顺延 15 天。',
  ].join('\n')
  const form = new FormData()
  form.append('file', new Blob([content], { type: 'text/markdown' }), `e2emiss-${stamp}.md`)
  bizOk(await api(`/kb/${kbId}/doc/upload`, { method: 'POST', body: form, raw: true }), '上传')

  const deadline = Date.now() + 120000
  let lastStatus = ''
  while (Date.now() < deadline) {
    const docs = bizOk(await api(`/kb/${kbId}/docs?page=1&size=10`), '查文档')
    lastStatus = (docs.list ?? [])[0]?.status ?? '（还没行）'
    if (lastStatus === 'READY') break
    if (lastStatus === 'FAILED') throw new Error('文档解析失败')
    await sleep(2000)
  }
  check(lastStatus === 'READY', `文档已就绪（状态 ${lastStatus}）`)

  // ---------- ④ 问一个"库里根本没有"的问题 → 沉淀 ----------
  // ⚠️ 先记水位线：这一次提问会**自己开一个会话**，会话/消息/打点四张表都要收尾。
  //    提问人是种子超管（`admin`），归属还在 ⇒ 谁也不会替他清。
  askedThisRun = true
  sessionFloor = mysqlCount('select coalesce(max(id), 0) from sys_chat_session;')
  await askSse(MISS_QUESTION)

  // ⚠️ 按**问句原文**认这一次打点，别按 `kb_ids` 精确匹配 ——
  //    提问没指定库时检索覆盖**所有可见库**，那一列是逗号串（实测 `kb_ids='1,1009403,…'`）。
  //    本脚本第一版就是按 `kb_ids = '{kbId}'` 写的，于是"沉淀明明成功了却报没打点"。
  const trace = mysqlCount(
    `select count(*) from sys_retrieval_trace where query_text like '%贝尔不等式%' and miss_flag = 1;`,
  )
  check(trace >= 1, '打点里出现 miss_flag=1 的行（判定线真的判出了未命中）', `行数=${trace}`)
  if (trace < 1) {
    // 判据断在这里时，后面每一条都会跟着红 —— 早点说清"是造数据没造出来"
    observe('⚠️ 未造出未命中，后续判据会连锁失败',
      `该问句的打点总数=${mysqlCount(`select count(*) from sys_retrieval_trace where query_text like '%贝尔不等式%';`)}`)
  }

  const pool = bizOk(await api(`/ops/qa/misses?keyword=${encodeURIComponent('贝尔不等式')}`), '清单')
  const hit = (pool.list ?? []).find((m) => m.question.includes('贝尔不等式'))
  check(!!hit, '池子里出现这一行（沉淀接上了）', hit ? `id=${hit.id} 次数=${hit.askCount}` : '')
  check(hit?.status === 'PENDING', '状态默认是 PENDING')
  check(Number(hit?.askCount) === 1, '提问次数 = 1')
  if (hit) missKeys.push(hit.id)

  // ---------- ⑤ 同源：三处一个数（G6-13） ----------
  const overview = bizOk(await api('/ops/qa/overview?range=7d'), '四指标')
  check(overview.pending >= 1, 'overview.pending 计入了这一条', `pending=${overview.pending}`)

  const pendingRes = bizOk(await api('/notices/pending'), '待我处理')
  const missItem = (pendingRes.list ?? []).find((p) => p.key === 'miss')
  check(!!missItem, '「待我处理」里**出现了**未命中那一项（S6-M11 时它因为没源而刻意不发）')
  check(missItem?.count === overview.pending, '待我处理 与 overview 的待办数一致（G6-13）',
    `${missItem?.count} vs ${overview.pending}`)
  const sum = (pendingRes.list ?? []).reduce((s, p) => s + Number(p.count ?? 0), 0)
  check(pendingRes.total === sum, '`/notices/pending` 的 total 是各类目之和（不是类目个数）', `${pendingRes.total} vs ${sum}`)

  // ---------- ⑥ 页面级 ----------
  edge = spawn(EDGE, [
    '--headless=new', `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${PROFILE}`,
    '--no-first-run', '--no-default-browser-check', '--window-size=1440,900', 'about:blank',
  ], { stdio: 'ignore' })
  const target = await waitForEdge()
  ws = new WebSocket(target)
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej })
  ws.onmessage = onCdpMessage
  await send('Page.enable')
  await send('Runtime.enable')
  await send('Network.enable')
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

  // 带 token 进页面（前端从 localStorage 读 `ak_token`）
  //
  // 🔴 **路由是 hash 模式**（`router/index.ts` 的 `createWebHashHistory`）。
  // 写成 `BASE/ops/qa` 的话：服务端把任意路径都返回 index.html，而 hash 为空 ⇒
  // 路由落到默认项再重定向 ⇒ 地址栏变成 `/ops/qa#/chat`、**页面停在知识问答**。
  // 现象是"这一页渲染不出东西"，看着像列表接口坏了。实测踩过一次。
  await goto(`${BASE}/#/login`)
  await evaluate(`localStorage.setItem('ak_token', ${JSON.stringify(token)}); localStorage.setItem('ak_remember','1'); 'ok'`)

  await goto(`${BASE}/?boot=${Date.now()}#/ops/qa`)
  const rendered = await waitFor(
    `window.__m.rows().some((r) => r.includes('贝尔不等式'))`,
    { timeout: 20000, label: '11 页的清单里渲染出这条未命中' },
  )
  check(rendered, '页面级：清单渲染出真数据（不是 mock）')

  const before = await evaluate(`window.__m.badge('问答运营')`)
  observe('侧边栏「问答运营」角标', String(before))
  check(Number(before) >= 1, '侧边栏角标显示真待办数', `角标=${before}`)

  // 点第一行的「更多」→「忽略」。
  // ⚠️ 这一列是 `el-dropdown`，**不是直接按钮** —— 分两步，中间要等菜单挂上来。
  const opened = await evaluate(`
    (() => {
      const tr = [...document.querySelectorAll('.el-table__body tbody tr')].find((r) => r.innerText.includes('贝尔不等式'))
      if (!tr) return 'no-row'
      const btn = [...tr.querySelectorAll('button')].find((b) => b.innerText.includes('更多'))
      if (!btn) return 'no-more:' + tr.innerText.replace(/\\n/g, ' | ')
      btn.click()
      return 'opened'
    })()
  `)
  check(opened === 'opened', `页面打开该行的「更多」菜单（${opened}）`)

  await sleep(350)
  const clicked = await evaluate(`
    (() => {
      const item = [...document.querySelectorAll('.el-dropdown-menu__item')]
        .find((e) => e.offsetParent !== null && e.innerText.trim() === '忽略')
      if (!item) return 'no-item:' + [...document.querySelectorAll('.el-dropdown-menu__item')].map((e) => e.innerText.trim()).join(',')
      item.click()
      return 'clicked'
    })()
  `)
  check(clicked === 'clicked', `页面点「忽略」（${clicked}）`)

  // ⚠️ 从 **Node 侧**轮询，不在页面里 `fetch`。两个坑本脚本各踩过一次：
  //   ① 在页面里打 `${API}` 是**跨源**（前端走 Vite 代理，页面自己不知道 8080）⇒ CORS 拦掉，
  //      而现象是"点击没生效"；
  //   ② 探针表达式返回 `'yes'/'no'` 两个**都是真值**的字符串 ⇒ `waitFor` 立刻返回，
  //      判据变成空断言。
  // 后端效应本来就该由后端侧观测 —— 走 Node 的 `api()` 两个坑一起没有。
  let ignored = 'no'
  const ignoreDeadline = Date.now() + 15000
  while (Date.now() < ignoreDeadline) {
    const r = bizOk(await api('/ops/qa/misses?status=IGNORED'), '查已忽略')
    if ((r.list ?? []).some((m) => m.question.includes('贝尔不等式'))) {
      ignored = 'yes'
      break
    }
    await sleep(500)
  }
  if (ignored !== 'yes') {
    // 失败时把这一段的网络流水与行内容打出来 —— 否则"点了没反应"只能靠猜
    // （没发请求 / 发了被拒 / 发了 500，三种改法完全不同）
    observe('网络流水（/api/ops）', ledger.filter((x) => x.url.includes('/api/ops')).map((x) => `${x.status} ${x.url}`).join(' | ') || '（一条都没有 —— 请求没发出去）')
    observe('行内文本', String(await evaluate(`[...document.querySelectorAll('.el-table__body tbody tr')].map((r) => r.innerText.replace(/\\n/g, ' | ')).join(' // ')`)).slice(0, 300))
  }
  check(ignored === 'yes', '页面操作真的写进了后端（不是只改了本地）')

  const afterOverview = bizOk(await api('/ops/qa/overview?range=7d'), '四指标')
  check(afterOverview.pending === overview.pending - 1,
    '忽略之后待办数 -1（G8-4：角标随状态变更同步）', `${overview.pending} → ${afterOverview.pending}`)

  const afterPending = bizOk(await api('/notices/pending'), '待我处理')
  const afterMiss = (afterPending.list ?? []).find((p) => p.key === 'miss')
  check(Number(afterMiss?.count ?? 0) === afterOverview.pending,
    '两处**同时**变了 —— 同源不是"两个数碰巧相等"', `${afterMiss?.count} vs ${afterOverview.pending}`)
  if (afterMiss?.count === overview.pending - 1) {
    observe('⚠️ 未命中数没有下降', '若上一行仍是"未命中那一项"就说明它对不上')
  }

  // ---------- ⑦ 前端不许再出现写死的演示数字 ----------
  const fake = await evaluate(`document.body.innerText.includes('（23）') || document.body.innerText.includes('保持一致（23')`)
  check(!fake, '页面里不再出现写死的演示数字 23（负向锚）')

  const errors = consoleMsgs.filter((m) => m.level === 'error')
  check(errors.length === 0, '整个过程没有 console error', errors.slice(0, 3).join(' | '))
}

// ---------------------------------------------------------------- SSE
async function askSse(message) {
  const resp = await fetch(`${API}/api/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ message }),
  })
  if (!resp.ok || !resp.body) throw new Error(`SSE 请求失败：HTTP ${resp.status}`)
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let done = false
  while (!done) {
    const r = await reader.read()
    if (r.done) break
    buffer += decoder.decode(r.value, { stream: true })
    let idx
    while ((idx = buffer.indexOf('\n\n')) !== -1) {
      const frame = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      if (frame.split('\n').some((l) => l.startsWith('event:') && l.includes('done'))) done = true
    }
  }
  check(done, `SSE 提问完成（${message}）`)
}

// ---------------------------------------------------------------- CDP 事件
function onCdpMessage(raw) {
  const msg = JSON.parse(raw.data)
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id)
    pending.delete(msg.id)
    msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result)
    return
  }
  if (msg.method === 'Network.responseReceived') {
    const { requestId, response, type } = msg.params
    if (type === 'XHR' || type === 'Fetch') {
      ledger.push({ requestId, url: response.url, status: response.status, method: msg.params.type })
    }
  }
  if (msg.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(msg.params.type)) {
    consoleMsgs.push({ level: msg.params.type, text: (msg.params.args ?? []).map((a) => a.value ?? a.description ?? '').join(' ') })
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

// ---------------------------------------------------------------- 入口
if (!existsSync(OUT_DIR)) mkdirSync(OUT_DIR, { recursive: true })

main()
  .catch((e) => {
    check(false, '脚本抛错', e.message)
  })
  .finally(async () => {
    // 收尾必须走 finally：**中途任何一步抛错都要清干净**（#186 那条教训）
    try {
      if (kbId) {
        const r = await teardownKbProbe(KB_CODE)
        // `residue.sql` 是个对象（各表的残留计数），直接插进模板串会打成 `[object Object]`
        observe('探针库收尾', `MinIO ${r.removed.minio} · 池子 ${r.removed.misses ?? 0} · 残留 ${JSON.stringify(r.residue?.sql ?? {})}`)
      }
    } catch (e) {
      observe('⚠️ 探针库收尾失败', e.message)
    }
    // 池子行按 id 删 —— 提问人是种子超管，无主兜底够不着
    for (const id of missKeys) {
      try {
        mysqlScalar(`delete from sys_qa_miss where id = ${id};`)
      } catch (e) {
        observe('⚠️ 池子行清理失败', `${id}: ${e.message}`)
      }
    }
    // 会话 / 消息 / 打点（含明细）—— 同样是超管名下的，没人替本脚本清
    if (askedThisRun) {
      try {
        mysqlScalar(`delete from sys_retrieval_trace_chunk where trace_id in `
          + `(select trace_id from sys_retrieval_trace where session_id > ${sessionFloor});`)
        mysqlScalar(`delete from sys_retrieval_trace where session_id > ${sessionFloor};`)
        mysqlScalar(`delete from sys_chat_message where session_id > ${sessionFloor};`)
        mysqlScalar(`delete from sys_chat_session where id > ${sessionFloor};`)
        // 🔵 `T-O19`：会话水位线**够不着**的那几张（用量按日期 / 池子按人）——
        // 本脚本真提问 ⇒ 既写打点也（未命中时）写池子、真调模型 ⇒ 写用量
        purgeUserScopedArtifacts(loginUserId)
        observe('会话四张表收尾', `水位线 ${sessionFloor} · 另清用户族(按 id)`)
      } catch (e) {
        observe('⚠️ 会话收尾失败', e.message)
      }
    }
    try {
      if (ws) ws.close()
      if (edge) edge.kill()
      rmSync(PROFILE, { recursive: true, force: true })
    } catch {
      /* 清不动就算了，下次跑会用新的 profile */
    }

    const failed = steps.filter((s) => !s.ok)
    console.log(`\n===== ${steps.length - failed.length}/${steps.length} 通过 =====`)
    if (failed.length) {
      for (const f of failed) console.log(`✗ ${f.label}${f.detail ? ` · ${f.detail}` : ''}`)
      process.exit(1)
    }
    console.log('全部通过 ✓')
    process.exit(0)
  })
