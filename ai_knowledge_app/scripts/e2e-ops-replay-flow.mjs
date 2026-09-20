/**
 * `S8-M10-B` 页面级 E2E：**重放验证**（`T-A01`）—— 闭环第 2 步到第 5 步的整条真链路。
 *
 * <h3>覆盖的判据</h3>
 * <ul>
 *   <li><b>`G8-5` ①</b> —— 补录一篇「真答得上该问题」的文档 ⇒ 重跑原问题 ⇒ **召回分达标**；</li>
 *   <li><b>`G8-5` ②</b> —— 达标的那一条**就是刚补录那篇**（不是别的文档碰巧被召回）；</li>
 *   <li><b>需求 §1.4 步 2</b> —— 「带问题跳上传页，**问题文本作为补录备注带入**」：
 *       断言 url 上带了 `note=`、上传页的备注框**真的被预填**、上传时随表单发出去；</li>
 *   <li><b>需求 §1.4 步 5</b> —— 达标 ⇒ 自动关闭；页面列表出现 `补录前 → 补录后`；</li>
 *   <li><b>`G8-4` / `G6-13`</b> —— 关闭之后侧边栏角标与 `/notices/pending` **同时** −1；</li>
 *   <li><b>🔴 重放不落打点</b> —— 重放前后 `sys_retrieval_trace` 行数**一模一样**
 *       （否则「提问总数 / 未命中数 / 活跃用户」都会把一次重放算成一次提问）；</li>
 *   <li><b>幂等</b> —— 再点一次：状态、分数、`ask_count` 全不变。</li>
 * </ul>
 *
 * <h3>🔴 为什么必须走"页面点、页面传文件"</h3>
 * 这一批的命门恰恰是**跨页的那一跳**（运营页 → 上传页 → 回运营页）。
 * 从 Node 直接调上传接口的话，`remark` 是我自己塞进去的，验不出
 * 「页面有没有把问题文本带过去」—— 而那正是需求 §1.4 步 2 的全部内容。
 * 所以：**点页面上的「补文档」→ 在页面上塞真文件 → 点页面上的「重放验证」**。
 *
 * <h3>真实路径是"横跨多库"那一条</h3>
 * 提问人是种子超管 ⇒ 检索覆盖**所有可见库** ⇒ 那一行的 `kb_ids` 是一长串 ⇒
 * `OpsQaView.supplement()` 走的必然是「跳知识库列表让用户选目标库」那一支。
 * 于是本脚本也顺带钉住**备注要能跨过这一跳**（`KbListView` 的透传）——
 * 原来它在这一跳是把备注丢掉的（现象只是"重放说没命中补录文档"）。
 *
 * <h3>技术路线</h3>
 * 与 `e2e-ops-miss-flow.mjs` 同：Node 自带 WebSocket 直驱无头 Edge 的 CDP，**零新增依赖**；
 * 断言看业务 `code` 不看 HTTP 码；页面里的探针每次导航后重新注入。
 *
 * <h3>自清纪律</h3>
 * 探针库走 `teardownKbProbe`；池子行按 id 删（提问人是超管，无主兜底够不着）；
 * 会话四张表按水位线删；`temp/` 下的探针文件删掉。
 *
 * <h3>前置</h3>
 * <pre>
 *   后端 8080：mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
 *   前端 5174：VITE_USE_MOCK=false npm run dev -- --host 127.0.0.1 --port 5174
 *   运行：node scripts/e2e-ops-replay-flow.mjs --base=http://127.0.0.1:5174
 * </pre>
 *
 * ⚠️ **本文件没有 shebang**：本机 shell 的路径守卫会拦下含 `#!` 的内容。
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import { mysqlCount, mysqlScalar, teardownKbProbe } from './lib/probe-teardown.mjs'

const arg = (name, fallback) => (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://localhost:5174')
const API = arg('api', 'http://localhost:8080')

const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-e2e-opsreplay'
const OUT_DIR = 'F:/work/code/ai-knowledge/temp/e2e-opsreplay'
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9341)

/** 固定账号：种子超管（`ops:qa:view` + `ops:qa:handle`，且是唯一能走完整条闭环的角色之一）。 */
const PROBE = { account: 'admin', password: 'Admin@123' }

/**
 * 探针问题：**知识库里故意没有的东西**（和 `e2e-ops-miss-flow` 同一个问句 ——
 * 那一批已实测它的 rerank 分是 `0.000016`，稳稳低于判定线 `0.35`）。
 */
const QUESTION = '量子纠缠的贝尔不等式怎么推导？'

/**
 * 补录文档的正文：**真的把那个问题答了一遍**。
 *
 * ⚠️ 内容必须"有实质"：探针实测里，「只提到关键词但不回答」的段落也有 `0.84`，
 * 所以"随便提一句"也能让分过线 —— 但那样验的是判定线松，不是闭环通。
 * 这里写的是完整推导，重放后的 Top1 应当接近 1。
 */
const SUPPLEMENT = `# 贝尔不等式的推导

## 从 EPR 佯谬出发
贝尔不等式的推导从 EPR 佯谬开始。假设存在局域隐变量 λ，两个粒子的测量结果就由 λ
与各自的测量方向唯一决定，联合概率可以分解为 p(a,b|λ)=p(a|λ)p(b|λ)。

## CHSH 形式
由此写出关联函数 E(a,b)，并考察组合 S = E(a,b) − E(a,b′) + E(a′,b) + E(a′,b′)。
因为每一项的绝对值都不超过 1，可以证明 |S| ≤ 2 —— 这就是 CHSH 形式的贝尔不等式，
它是"局域隐变量"这一假设的直接推论。

## 量子力学的结论
量子力学对自旋单态给出 E(a,b) = −a·b。取四个测量方向夹角 45° 时算得 S = 2√2，
大于 2，因此贝尔不等式被违反，局域隐变量理论被实验证伪。
`

const stamp = Date.now()
const KB_CODE = `kb_e2ereplay_${stamp}`
const KB_NAME = `重放探针库 ${stamp}`
const DOC_NAME = `replay-${stamp}.md`

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
 * 「探针说找不到元素」，看着像页面没渲染。
 * ⚠️ 探针表达式**返回 `'yes'/'no'` 是空断言**（两个字符串都是真值 ⇒ `waitFor` 立刻返回）
 * —— 要返回 `true`/`null`。
 */
const PAGE_TOOLS = `
window.__m = {
  txt: (sel) => document.querySelector(sel)?.innerText?.trim() ?? null,
  rows: () => [...document.querySelectorAll('.el-table__body tbody tr')].map((tr) => tr.innerText.trim()),
  byText: (sel, text) => [...document.querySelectorAll(sel)].find((e) => (e.innerText ?? '').includes(text)) ?? null,
  badge: (title) => {
    const item = [...document.querySelectorAll('.el-menu-item, .nav-item, li')]
      .find((e) => (e.innerText ?? '').trim().startsWith(title))
    return item ? (item.querySelector('.nav-badge')?.innerText ?? '').trim() : null
  },
  /** 当前可见的 ElMessageBox 标题（不可见的那个是正在淡出的旧框） */
  alertTitle: () => {
    const box = [...document.querySelectorAll('.el-message-box')].find((e) => e.offsetParent !== null)
    return box ? (box.querySelector('.el-message-box__title')?.innerText ?? '').trim() : null
  },
  alertBody: () => {
    const box = [...document.querySelectorAll('.el-message-box')].find((e) => e.offsetParent !== null)
    return box ? (box.querySelector('.el-message-box__message')?.innerText ?? '').trim() : null
  },
}
'ok'`

// ---------------------------------------------------------------- 主流程
let edge
let kbId
let missId = 0
let sessionFloor = 0

async function main() {
  // ---------- ① 登录 ----------
  const login = await api('/auth/login', { method: 'POST', body: { ...PROBE, remember: true } })
  token = bizOk(login, '登录').accessToken
  check(!!token, '登录成功（种子超管）')

  // ---------- ② 建探针库 ----------
  const created = bizOk(await api('/kb', {
    method: 'POST',
    body: {
      name: KB_NAME,
      code: KB_CODE,
      deptId: 1,
      visibleRoles: ['USER'],
      businessDomain: '技术文档',
      description: 'S8-M10-B 重放验证页面级联调探针，脚本结束即删',
    },
  }), '建库')
  kbId = created.id
  check(!!kbId, `探针库已建 · id=${kbId}`)

  // ---------- ③ 先放一篇**无关**内容，让库里有候选 ----------
  // `miss_flag` 的条件是「重排真跑过 + 有候选 + 低于判定线」三条缺一不可：
  // 库里一篇都没有 ⇒ 召回为空 ⇒ 那是"空召回"，**不算未命中**（G5-11 早分流了）。
  const filler = new FormData()
  filler.append('file', new Blob(['# 季度报价单\n\n型号 A-100 单价 1800 元，交期 7 天。'], { type: 'text/markdown' }), `filler-${stamp}.md`)
  bizOk(await api(`/kb/${kbId}/doc/upload`, { method: 'POST', body: filler, raw: true }), '上传无关文档')
  await waitDocReady(1)

  // ---------- ④ 问一个"库里根本没有"的问题 → 沉淀 ----------
  sessionFloor = mysqlCount('select coalesce(max(id), 0) from sys_chat_session;')
  await askSse(QUESTION)

  const trace = mysqlCount(
    `select count(*) from sys_retrieval_trace where query_text like '%贝尔不等式%' and miss_flag = 1;`,
  )
  check(trace >= 1, '打点里出现 miss_flag=1 的行（判定线真的判出了未命中）', `行数=${trace}`)

  const pool = bizOk(await api(`/ops/qa/misses?keyword=${encodeURIComponent('贝尔不等式')}`), '清单')
  const hit = (pool.list ?? []).find((m) => m.question.includes('贝尔不等式'))
  check(!!hit, '池子里出现这一行（沉淀接上了）', hit ? `id=${hit.id}` : '')
  if (hit) missId = hit.id
  check(hit?.topScore != null, '池子记下了"补录前"的分（重放的对比基准）', `topScore=${hit?.topScore}`)
  const pendingBefore = bizOk(await api('/ops/qa/overview?range=7d'), '四指标').pending

  // ---------- ⑤ 起浏览器 ----------
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
  await send('DOM.enable')
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

  // 带 token 进页面（前端从 localStorage 读 `ak_token`）
  // 🔴 路由是 **hash 模式**：写 `BASE/ops/qa` 会落到默认路由再重定向（现象是"这页渲染不出来"）
  await goto(`${BASE}/#/login`)
  await evaluate(`localStorage.setItem('ak_token', ${JSON.stringify(token)}); localStorage.setItem('ak_remember','1'); 'ok'`)

  await goto(`${BASE}/?boot=${Date.now()}#/ops/qa`)
  await waitFor(`window.__m.rows().some((r) => r.includes('贝尔不等式'))`,
    { timeout: 20000, label: '运营页清单渲染出这条未命中' })
  check(true, '页面级：运营页清单渲染出真数据')
  const badgeBefore = Number(await evaluate(`window.__m.badge('问答运营')`))
  check(badgeBefore >= 1, '侧边栏「问答运营」角标是真待办数', `角标=${badgeBefore}`)

  // ---------- ⑥ 闭环第 2 步：点「补文档」 ----------
  const clicked = await evaluate(`
    (() => {
      const tr = [...document.querySelectorAll('.el-table__body tbody tr')].find((r) => r.innerText.includes('贝尔不等式'))
      if (!tr) return 'no-row'
      const btn = [...tr.querySelectorAll('button')].find((b) => b.innerText.includes('补文档'))
      if (!btn) return 'no-btn:' + tr.innerText.replace(/\\n/g, ' | ')
      btn.click()
      return true
    })()
  `)
  check(clicked === true, `页面点「补文档」（${clicked}）`)

  // 这一行的 `kb_ids` 是"检索覆盖了哪些库"（超管提问 ⇒ 一大串）⇒
  // 走的是「跳知识库列表选目标库」那一支。**备注必须跟着走**
  await waitFor(`location.hash.startsWith('#/kb?') || location.hash.includes('note=')`,
    { timeout: 10000, label: '跳到带 note 的下一步' })
  const hash = await evaluate(`location.hash`)
  check(hash.includes('note='), '「补文档」把问题原文带上了（url 里有 note=）', hash.slice(0, 90))
  const noteInUrl = decodeURIComponent(hash.split('note=')[1] ?? '')
  check(noteInUrl.includes('贝尔不等式'), '带的正是那条未命中的问题原文', noteInUrl.slice(0, 40))

  // ---------- ⑦ 在库列表上选目标库 → 上传页 ----------
  await waitFor(`!!window.__m.byText('.kb-card', '重放探针库')`, { timeout: 15000, label: '知识库列表里出现探针库' })
  const picked = await evaluate(`
    (() => {
      const card = [...document.querySelectorAll('.kb-card')].find((c) => c.innerText.includes('重放探针库'))
      if (!card) return 'no-card'
      const btn = [...card.querySelectorAll('button')].find((b) => b.innerText.includes('上传文档'))
      if (!btn) return 'no-btn'
      btn.click()
      return true
    })()
  `)
  check(picked === true, `库列表点「上传文档」（${picked}）`)

  await waitFor(`location.hash.includes('/upload')`, { timeout: 10000, label: '进入上传页' })
  const noteBox = await waitFor(`
    (() => {
      const ta = [...document.querySelectorAll('.supplement-card textarea')][0]
      return ta && ta.value ? ta.value : null
    })()
  `, { timeout: 15000, label: '上传页的「补录备注」被预填' })
  check(String(noteBox).includes('贝尔不等式'),
    '上传页的补录备注框**已被预填**为问题原文（这一步是闭环第 2 步的全部内容）',
    String(noteBox).slice(0, 40))

  // ---------- ⑧ 页面上塞真文件（不是 Node 侧调接口）----------
  const docPath = `${OUT_DIR}/${DOC_NAME}`
  writeFileSync(docPath, SUPPLEMENT, 'utf8')
  await setFile(DOC_NAME)
  check(true, '通过 CDP 给上传页塞入补录文档（等价于用户点选）', DOC_NAME)

  const ready = await waitDocReady(2, 150000)
  check(ready, '补录文档解析并建索引完成（READY —— 本仓口径里它就等于"可被检索"）')

  const remark = mysqlScalar(`select remark from sys_document where kb_id = ${kbId} and title like 'replay-%';`)
  check(!!remark && String(remark).includes('贝尔不等式'),
    '备注**真的落库**了（`sys_document.remark` ⇒ 重放靠它认这篇）', String(remark).slice(0, 40))

  // ---------- ⑨ 闭环第 4 步：回运营页点「重放验证」----------
  // 🔴 先记打点行数：重放**不许**落打点（落了就会把一次重放算成一次提问）
  const traceBefore = mysqlCount('select count(*) from sys_retrieval_trace;')
  const askCountBefore = Number(mysqlScalar(`select ask_count from sys_qa_miss where id = ${missId};`))

  await goto(`${BASE}/?boot=${Date.now()}#/ops/qa`)
  await waitFor(`window.__m.rows().some((r) => r.includes('贝尔不等式'))`,
    { timeout: 20000, label: '回运营页，清单仍在（`status=all` 是页面默认）' })
  const replayClicked = await evaluate(`
    (() => {
      const tr = [...document.querySelectorAll('.el-table__body tbody tr')].find((r) => r.innerText.includes('贝尔不等式'))
      if (!tr) return 'no-row'
      const btn = [...tr.querySelectorAll('button')].find((b) => b.innerText.includes('重放验证'))
      if (!btn) return 'no-btn:' + tr.innerText.replace(/\\n/g, ' | ')
      btn.click()
      return true
    })()
  `)
  check(replayClicked === true, `页面点「重放验证」（${replayClicked}）`)

  const title = await waitFor(`window.__m.alertTitle()`, { timeout: 40000, label: '重放回话弹窗' })
  const body = await evaluate(`window.__m.alertBody()`)
  check(String(title).includes('已自动关闭'),
    '弹窗标题是「重放验证通过，已自动关闭」（页面不再自己用 improved 推状态）', String(title))
  observe('重放回话', String(body).replace(/\n/g, ' ⏎ ').slice(0, 220))
  // 关掉弹窗，否则后面的页面操作会被它挡住
  await evaluate(`
    (() => {
      const box = [...document.querySelectorAll('.el-message-box')].find((e) => e.offsetParent !== null)
      const btn = box && [...box.querySelectorAll('button')].find((b) => b.innerText.includes('知道了'))
      if (btn) btn.click()
      return true
    })()
  `)
  await sleep(400)

  // ---------- ⑩ 后端效应：从 Node 侧轮询 ----------
  // ⚠️ 不在页面里 `fetch(API)` —— 那是**跨源**（前端走 Vite 代理，页面不知道 8080），
  //    CORS 拦掉而现象是"点了没生效"
  const resolved = bizOk(await api(`/ops/qa/misses?keyword=${encodeURIComponent('贝尔不等式')}`), '查清单')
  const row = (resolved.list ?? []).find((m) => m.id === missId)
  check(row?.status === 'RESOLVED', '状态已自动关闭（RESOLVED）', `status=${row?.status}`)
  check(row?.scoreAfter != null && Number(row.scoreAfter) >= 0.35,
    'G8-5①：重放后的分**达标**（≥ 判定线 0.35）', `补录前 ${row?.topScore} → 补录后 ${row?.scoreAfter}`)
  check(Number(row?.scoreAfter) > Number(row?.topScore),
    'G8-5①：分数确实**提升**了', `${row?.topScore} → ${row?.scoreAfter}`)

  // 页面上的「补录前 → 补录后」（需求 §1.10）
  const shown = await waitFor(`
    (() => {
      const tr = [...document.querySelectorAll('.el-table__body tbody tr')].find((r) => r.innerText.includes('贝尔不等式'))
      return tr && tr.innerText.includes('→') ? tr.innerText.replace(/\\n/g, ' | ') : null
    })()
  `, { timeout: 15000, label: '页面上出现 补录前 → 补录后' })
  check(true, '页面展示了「补录前 → 补录后」', String(shown).slice(0, 90))

  // ---------- ⑪ G8-4 / G6-13：关掉一条 ⇒ 两处同时 −1 ----------
  const overviewAfter = bizOk(await api('/ops/qa/overview?range=7d'), '四指标')
  check(overviewAfter.pending === pendingBefore - 1,
    'G8-4：关闭之后待办数 −1', `${pendingBefore} → ${overviewAfter.pending}`)
  const pendingRes = bizOk(await api('/notices/pending'), '待我处理')
  const missItem = (pendingRes.list ?? []).find((p) => p.key === 'miss')
  // ⚠️ **「缺席 ≡ 0」**：`NoticeService#pending` 的口径是"有才发"（那一栏的定义是
  // "需要人工介入的聚合"）⇒ 待办降到 0 时这一项**整个消失**。
  // 判据若写成"必须能找到这一项"就会在池子空时假红；写成"没有就跳过"又会变成空断言。
  check(Number(missItem?.count ?? 0) === overviewAfter.pending,
    'G6-13：`/notices/pending` 与 overview **同时**变了（同源不是"碰巧相等"）',
    `${missItem?.count ?? '(缺席 ≡ 0)'} vs ${overviewAfter.pending}`)

  // ---------- ⑫ 🔴 重放不落打点 ----------
  const traceAfter = mysqlCount('select count(*) from sys_retrieval_trace;')
  check(traceAfter === traceBefore,
    '🔴 重放**没有**落打点（否则「提问总数 / 未命中数 / 活跃用户」会把一次重放算成一次提问）',
    `${traceBefore} → ${traceAfter}`)

  // ---------- ⑬ 幂等：再点一次 ----------
  const again = await evaluate(`
    (() => {
      const tr = [...document.querySelectorAll('.el-table__body tbody tr')].find((r) => r.innerText.includes('贝尔不等式'))
      if (!tr) return 'no-row'
      const btn = [...tr.querySelectorAll('button')].find((b) => b.innerText.includes('重放验证'))
      if (!btn) return 'no-btn'
      btn.click()
      return true
    })()
  `)
  check(again === true, '再点一次「重放验证」（幂等判据）')
  await waitFor(`window.__m.alertTitle()`, { timeout: 40000, label: '第二次重放回话' })
  await evaluate(`
    (() => {
      const box = [...document.querySelectorAll('.el-message-box')].find((e) => e.offsetParent !== null)
      const btn = box && [...box.querySelectorAll('button')].find((b) => b.innerText.includes('知道了'))
      if (btn) btn.click()
      return true
    })()
  `)
  const after2 = bizOk(await api(`/ops/qa/misses?keyword=${encodeURIComponent('贝尔不等式')}`), '查清单')
  const row2 = (after2.list ?? []).find((m) => m.id === missId)
  check(row2?.status === 'RESOLVED', '幂等：状态仍是 RESOLVED', `status=${row2?.status}`)

  // 🔴 **幂等的判据是「无累积副作用 + 结论稳定」，不是"分数逐位相同"。**
  // 实测：同一个问题、同一份语料，两次重放的 top1 是 `0.986707` 与 `0.987033`
  // —— 真 rerank 服务在**服务端**并不是逐位可复现的（第一次跑本脚本时这条断言就是这么红的）。
  // 那属于模型侧的事实，不该被写成一个"我们没做到"的缺陷；而**能累积的东西**
  // （ask_count、打点行数、状态）必须一个都不动。
  observe('重放分抖动（服务端模型，非本仓行为）', `${row?.scoreAfter} → ${row2?.scoreAfter}`)
  check(Math.abs(Number(row2?.scoreAfter) - Number(row?.scoreAfter)) < 0.01,
    '幂等：两次重放的分在同一条判定线上给出**同一个结论**（允许模型侧微幅抖动）',
    `${row?.scoreAfter} → ${row2?.scoreAfter}`)
  const askCountAfter = Number(mysqlScalar(`select ask_count from sys_qa_miss where id = ${missId};`))
  check(askCountAfter === askCountBefore,
    '幂等：`ask_count` 不涨（涨了的话 sum(ask_count) 与 miss_flag=1 的对账当场失效）',
    `${askCountBefore} → ${askCountAfter}`)
  check(mysqlCount('select count(*) from sys_retrieval_trace;') === traceBefore,
    '幂等：第二次重放也没落打点', `水位线 ${traceBefore}`)

  // ---------- ⑭ console 干净 ----------
  const errors = consoleMsgs.filter((m) => m.level === 'error')
  check(errors.length === 0, '整个过程没有 console error', errors.slice(0, 3).join(' | '))
}

// ---------------------------------------------------------------- 工具
/** 等文档列表里出现 N 篇 READY。 */
async function waitDocReady(expected, timeout = 120000) {
  const deadline = Date.now() + timeout
  let last = ''
  while (Date.now() < deadline) {
    const docs = bizOk(await api(`/kb/${kbId}/docs?page=1&size=10`), '查文档')
    const list = docs.list ?? []
    const ready = list.filter((d) => d.status === 'READY').length
    last = list.map((d) => `${d.title}:${d.status}`).join(' / ')
    if (ready >= expected) return true
    if (list.some((d) => d.status === 'FAILED')) throw new Error(`文档解析失败：${last}`)
    await sleep(2000)
  }
  observe('⚠️ 等待就绪超时', last)
  return false
}

/** 通过 CDP 给 el-upload 的隐藏 input 塞一个真文件（等价于用户点选）。 */
async function setFile(name) {
  const doc = await send('DOM.getDocument', { depth: -1 })
  const node = await send('DOM.querySelector', {
    nodeId: doc.root.nodeId,
    selector: '.upload-page .el-upload input[type="file"]',
  })
  if (!node.nodeId) throw new Error('找不到上传页的文件 input')
  await send('DOM.setFileInputFiles', { nodeId: node.nodeId, files: [`${OUT_DIR}/${name}`] })
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
        observe('探针库收尾', `MinIO ${r.removed.minio} · 池子 ${r.removed.misses ?? 0} · 残留 ${JSON.stringify(r.residue?.sql ?? {})}`)
      }
    } catch (e) {
      observe('⚠️ 探针库收尾失败', e.message)
    }
    // 池子行按 id 删 —— 提问人是种子超管，无主兜底够不着
    if (missId) {
      try {
        mysqlScalar(`delete from sys_qa_miss where id = ${missId};`)
      } catch (e) {
        observe('⚠️ 池子行清理失败', `${missId}: ${e.message}`)
      }
    }
    // 会话 / 消息 / 打点（含明细）—— 同样是超管名下的，没人替本脚本清
    if (sessionFloor) {
      try {
        mysqlScalar(`delete from sys_retrieval_trace_chunk where trace_id in `
          + `(select trace_id from sys_retrieval_trace where session_id > ${sessionFloor});`)
        mysqlScalar(`delete from sys_retrieval_trace where session_id > ${sessionFloor};`)
        mysqlScalar(`delete from sys_chat_message where session_id > ${sessionFloor};`)
        mysqlScalar(`delete from sys_chat_session where id > ${sessionFloor};`)
        observe('会话四张表收尾', `水位线 ${sessionFloor}`)
      } catch (e) {
        observe('⚠️ 会话收尾失败', e.message)
      }
    }
    try {
      if (ws) ws.close()
      if (edge) edge.kill()
      rmSync(PROFILE, { recursive: true, force: true })
      rmSync(OUT_DIR, { recursive: true, force: true })
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
