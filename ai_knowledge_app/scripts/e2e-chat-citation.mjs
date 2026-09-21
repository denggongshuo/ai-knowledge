/**
 * `S7-M06-C` 页面级 E2E：**引用角标可溯源（`T-804`）+ 按需「直接读原图」（`T-806`）**。
 *
 * <h3>覆盖的判据</h3>
 * <ul>
 *   <li><b>`T-804`</b> 需求 `07` §6.6：真上传 → 等 `READY` → 真提问 → 答案里出现**可点角标**
 *       → 点角标 ⇒ 检索详情面板**滚动定位**到对应分片 → 点引用卡片里的文档名 ⇒
 *       打开预览页且 URL 带 `?chunk=`，且**页面真的定位到那一片**；</li>
 *   <li><b>`T-804`</b> 的**反面**：`sys_retrieval_trace_chunk.cited` 是 `1/0/NULL` 三态，
 *       而**不是**"喂给模型的那批全标 1"；</li>
 *   <li><b>`T-806`</b> 需求 `09` §4.7：`vision=false` 时按钮**置灰且带原因** →
 *       把配置改成 `vision=true` ⇒ 按钮可点 → 点它 ⇒ 页脚出现「已读原图」，
 *       且 `sys_chat_message.used_vision = 1`（**只对该轮生效**：同一会话上一轮仍是 0）；</li>
 *   <li>收尾**自清残留 0 行**（断言，不是日志 —— `T-O21` 的口径）。</li>
 * </ul>
 *
 * <h3>⚠️ 三处**刻意不在本脚本**里验</h3>
 * <ol>
 *   <li><b>能力闸的服务端一侧</b>（带 `useVision: true` 直接打端点会被拒）——
 *       页面上的按钮是 `disabled` 的，**点不动**，脚本没法从页面触发它。
 *       那一半由 `ChatStreamFlowTest#useVisionIsRejectedWhenModelCannotSee` 覆盖；</li>
 *   <li><b>模型真的看得懂图</b>—— 那是步 0 探针的事（`temp/probe-vision3.mjs`，
 *       真 PNG ⇒ 「红色」）。本脚本只验**链路把原图送到了**（`used_vision` 落库）；</li>
 *   <li><b>`citations` 逐条等于答案角标</b>—— 由后端用例逐条断言过。本脚本验的是
 *       **它到了页面上、且可点**。</li>
 * </ol>
 *
 * <h3>前置</h3>
 * <pre>
 *   后端 8080（真后端）· 前端 5174：`VITE_USE_MOCK=false npm run dev -- --host 127.0.0.1 --port 5174`
 *   运行：node scripts/e2e-chat-citation.mjs --base=http://127.0.0.1:5174
 * </pre>
 *
 * ⚠️ **本文件没有 shebang**：本机 shell 的路径守卫会拦下含 `#!` 的内容。
 */
import { spawn } from 'node:child_process'
import { mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import {
  mysqlCount,
  mysqlScalar,
  teardownKbProbe,
  sweepAbandonedKbProbes,
  kbResidueClean,
  describeKbResidue,
} from './lib/probe-teardown.mjs'
import { solidPng, selfTest } from './lib/test-image.mjs'
import { probeDevServer, reportPrecondition, EXIT_PRECONDITION } from './lib/dev-server.mjs'

const arg = (name, fallback) => (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://localhost:5174')
const API = arg('api', 'http://localhost:8080')

const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'

const stamp = Date.now()
/** 每次运行用**独立** profile —— 见 `e2e-doc-attributes.mjs` 里那段（同一条教训）。 */
const runTag = `p${stamp % 100000}`
const PROFILE = `F:/work/code/ai-knowledge/temp/edge-e2e-citation-${runTag}`
const SHOT_DIR = `F:/work/code/ai-knowledge/temp/e2e-citation-${stamp}`
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9351)

const PROBE = { account: 'admin', password: 'Admin@123' }
const KB_CODE = `kb_e2ecitation_${stamp}`
/** 正文里的关键词：提问要用它，才能保证召回得到这篇（token 必须有交集）。 */
const MAGIC = `银杏叶索引${stamp}`
const DOC_NAME = `探针文档-引用-${stamp}.md`
const CONTENT = `# 探针文档\n\n${MAGIC} 是本文档用来验证「答案角标能定位到分片」的关键词。\n\n`
  + `## 第二节\n\n另一段与 ${MAGIC} 有关的说明文字，保证至少有两个可被引用的分片。\n`

/**
 * 喂给 `input[type=file]` 的图。
 *
 * 🔴 **用 `lib/test-image.mjs` 现造，不用仓库里的真图表**（`T-O26`）。
 * 本脚本验的是"原图被送到了对话模型"（`used_vision` 落库），而**不是**"模型看懂了什么" ——
 * 一张纯色 PNG 足够，且它**可复算**（同一行代码在任何机器上都得到同一张图）。
 * ⚠️ 另一份 E2E 借了 `docs/面试/图表/*.png`：那让脚本依赖一份**与它无关**的资料，
 * 而那份资料被移动 / 删除时，脚本会以一个**与引用无关**的原因失败。
 */
const IMAGE = solidPng(64, 64, [220, 20, 20])
const IMAGE_NAME = `探针图-${stamp}.png`

const steps = []
function check(ok, label, detail = '') {
  steps.push({ ok, label, detail })
  console.log(`  ${ok ? '✅' : '❌'} ${label}${detail ? `  —  ${detail}` : ''}`)
}
function observe(label, detail = '') {
  console.log(`  · ${label}${detail ? `  —  ${detail}` : ''}`)
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
const chatWrites = []

function send(method, params = {}) {
  const id = ++msgId
  return new Promise((resolve, reject) => {
    // ⚠️ **必须带超时**：CDP 不回包时原来会永久挂起（脚本再无输出、也无报错）
    const timer = setTimeout(() => {
      pending.delete(id)
      reject(new Error(`CDP 无响应（${method}）：超过 20s —— 多半是连到了**别的** Edge 实例`))
    }, 20000)
    pending.set(id, {
      resolve: (v) => { clearTimeout(timer); resolve(v) },
      reject: (e) => { clearTimeout(timer); reject(e) },
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
async function clickReal(selector) {
  const ok = await evaluate(`(() => {
    const el = document.querySelector(${JSON.stringify(selector)})
    if (!el) return false
    el.click()
    return true
  })()`)
  if (!ok) throw new Error(`点不到元素：${selector}`)
  await sleep(300)
}
function onCdpMessage(raw) {
  // 🔴 **载荷在 `raw.data` 里**（`onmessage` 收到的是 `MessageEvent`）。
  //    写成 `JSON.parse(raw)` 会把 MessageEvent 序列化成 `"[object MessageEvent]"` ⇒
  //    每次抛错被 catch 吞掉 ⇒ **所有回包静默丢弃**（现象是"`Page.enable` 永不回包"）。
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
    if (/\/api\/chat\/(completions|attachments)/.test(r.url)) {
      chatWrites.push({ url: r.url.replace(API, ''), status: r.status })
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

// ---------------------------------------------------------------- 状态
let edge
let kbId = 0
let docId = 0
let sessionId = 0
let attachmentFixture = ''
/** 改动前的 `capabilities` 原文 —— 收尾必须**原样复原**（`#166` 那族：改全局状态要复原）。 */
let originalCapabilities = null
let chatModelId = 0

async function main() {
  // ---------- ① 前置：**必须是真后端模式** ----------
  const diag = await probeDevServer(BASE)
  if (!diag.ok) {
    console.error(reportPrecondition(diag, 'e2e-chat-citation'))
    process.exit(EXIT_PRECONDITION)
  }
  if (diag.mode !== 'real') {
    console.error(`\n✗ e2e-chat-citation：**前置不满足**（exit ${EXIT_PRECONDITION}）—— 只在**真后端模式**下成立\n`)
    console.error('  • 本脚本验的是"角标能定位到真分片、原图真的被送出去"，mock 模式这两层都不存在。')
    console.error('  • 下一步：`VITE_USE_MOCK=false npm run dev -- --host 127.0.0.1 --port 5174`。\n')
    process.exit(EXIT_PRECONDITION)
  }
  observe('dev server 就绪（真后端模式）', diag.notes?.join(' / ') ?? '')

  // 素材生成器自检 —— 本脚本的存在理由之一就是"素材要可复算"（`T-O26`）
  const imgCheck = selfTest()
  observe('素材生成器自检', `${imgCheck.width}×${imgCheck.height} · ${imgCheck.bytes} 字节 · 头=${imgCheck.head}`)
  mkdirSync(SHOT_DIR, { recursive: true })
  attachmentFixture = `${SHOT_DIR}/${IMAGE_NAME}`
  writeFileSync(attachmentFixture, IMAGE)

  // ---------- ② 登录 ----------
  token = bizOk(await api('/auth/login', { method: 'POST', body: { ...PROBE, remember: true }, as: '' }), '登录').accessToken
  check(!!token, '拿到 accessToken')

  // 🔴 开工先把"被中断的轮次"留下的探针库收掉（`T-O23`：兜底必须由**下一轮**做）
  const swept = await sweepAbandonedKbProbes(['kb_e2ecitation_'])
  if (swept.length > 0) observe('收掉上一轮（或更早被中断的轮次）留下的探针库', `${swept.length} 个：${swept.join(' · ')}`)

  // ---------- ③ 建库 + 真上传 → 等 READY ----------
  const created = bizOk(await api('/kb', {
    method: 'POST',
    body: {
      name: KB_CODE, code: KB_CODE, deptId: 1, secretLevel: 'INTERNAL',
      visibleRoles: ['USER'], businessDomain: '技术文档',
      description: `S7-M06-C 引用角标页面级联调探针（${stamp}），脚本结束即删`,
    },
  }), '建探针库')
  kbId = created.id
  check(kbId > 0, '探针库已建', `kbId=${kbId}`)

  const form = new FormData()
  form.append('file', new Blob([CONTENT], { type: 'text/markdown' }), DOC_NAME)
  bizOk(await api(`/kb/${kbId}/doc/upload`, { method: 'POST', body: form, raw: true }), '上传探针文档')
  docId = Number(mysqlScalar(
    `select id from sys_document where kb_id = ${kbId} and title like '%引用%' order by id desc limit 1;`) || 0)
  check(docId > 0, '探针文档已入库', `docId=${docId}`)
  await waitDocReady(kbId, 1)
  check(true, '解析 + 索引完成（READY —— 本仓口径里它就等于"可被检索"）')

  // ---------- ④ 取对话模型配置行（`T-806` 的能力闸要改它，收尾要复原） ----------
  chatModelId = Number(mysqlScalar(
    "select id from sys_model_config where model_type = 'CHAT' and is_active = 1 order by id limit 1;") || 0)
  check(chatModelId > 0, '取到启用的对话模型配置行', `id=${chatModelId}`)
  originalCapabilities = mysqlScalar(`select coalesce(capabilities, 'NULL') from sys_model_config where id = ${chatModelId};`)
  observe('对话模型改动前的 capabilities', String(originalCapabilities))

  // ---------- ⑤ 起浏览器 ----------
  edge = spawn(EDGE, [
    '--headless=new', `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${PROFILE}`,
    '--no-first-run', '--no-default-browser-check', '--window-size=1440,900', 'about:blank',
  ], { stdio: 'ignore' })
  ws = new WebSocket(await waitForEdge())
  await new Promise((res, rej) => {
    const timer = setTimeout(() => rej(new Error('CDP 握手超时（15s）—— 检查是否有同 profile 的残留 Edge')), 15000)
    ws.onopen = () => { clearTimeout(timer); res() }
    ws.onerror = (e) => { clearTimeout(timer); rej(new Error(`CDP 握手失败：${e?.message ?? 'unknown'}`)) }
  })
  ws.onmessage = onCdpMessage
  // 连上之后先自检一句：**连到的到底是谁**（少了它，"连到旧实例"只表现为某一步超时）
  const targets = await send('Target.getTargets')
  observe('已连上的浏览器目标',
    (targets.targetInfos ?? []).map((t) => `${t.type}:${String(t.url).slice(0, 40)}`).join(' | ') || '(无)')
  await send('Page.enable')
  await send('Runtime.enable')
  await send('Network.enable')
  await send('DOM.enable')
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

  await goto(`${BASE}/#/login`)
  await evaluate(`localStorage.setItem('ak_token', ${JSON.stringify(token)}); localStorage.setItem('ak_remember','1'); 'ok'`)
  await goto(`${BASE}/#/chat`)
  // 🔴 注入 token 后**必须整页 reload**：`useAuthStore` 是**创建时**读一次 localStorage
  await send('Page.reload')
  await waitFor(`!location.hash.includes('/login')`, { timeout: 15000, label: '登录态生效' })
  await waitFor(`!!document.querySelector('.chat-input')`, { timeout: 20000, label: '问答页挂载' })
  await waitFor(`!!document.querySelector('.el-select .el-select__wrapper')`, { timeout: 15000, label: '模型切换器渲染' })

  // ---------- ⑥ 传一张图（真文件塞进 `input[type=file]`） ----------
  // 「直接读原图」的按钮**只在带图的消息上出现**（`v-if="m.attachments?.length"`）
  await waitFor(`!!document.querySelector('.chat-input input[type="file"]')`, { timeout: 15000, label: '上传入口存在' })
  const handle = await send('Runtime.evaluate', {
    expression: `document.querySelector('.chat-input input[type="file"]')`,
  })
  await send('DOM.setFileInputFiles', { files: [attachmentFixture], objectId: handle.result.objectId })
  await waitFor(`document.querySelectorAll('.pending-item').length > 0`, { timeout: 30000, label: '待发送区出现缩略图' })
  check(true, '真文件已塞进上传入口（待发送区出现缩略图）')

  // ---------- ⑦ 真提问 ----------
  await evaluate(`(() => {
    const ta = document.querySelector('.chat-input textarea')
    const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set
    setter.call(ta, ${JSON.stringify(MAGIC + ' 是什么？')})
    ta.dispatchEvent(new Event('input', { bubbles: true }))
    return true
  })()`)
  await sleep(300)
  await clickReal('.chat-input button.el-button--primary')
  await waitFor(`document.querySelectorAll('.bubble.ai-bubble').length > 0`, { timeout: 20000, label: 'AI 气泡出现' })
  // 等生成结束（`.msg-foot` 只在 `!streaming && content` 时渲染）
  await waitFor(`document.querySelectorAll('.msg-foot').length > 0`, { timeout: 180000, label: '生成结束（页脚出现）' })
  check(chatWrites.some((r) => r.url.includes('/chat/completions')), '页面上的提问**真的发了** `POST /chat/completions`',
    chatWrites.map((r) => `${r.url}=${r.status}`).join(' · ') || '(一个都没发)')

  // 🔴 把本轮提问的会话 id **精确**记下来（`T-O23`：不靠水位线 ——
  //    水位线只保护"本轮造的"，前一轮的残留**永远没人认领**）
  sessionId = Number(mysqlScalar('select id from sys_chat_session order by id desc limit 1;') || 0)
  observe('本轮会话 id', `sessionId=${sessionId}`)

  // ---------- ⑧ `T-804`：行内角标可点 + 面板定位 ----------
  const markerCount = await evaluate(`document.querySelectorAll('.inline-cit').length`)
  const citationCount = await evaluate(`document.querySelectorAll('.cit-row .cit-tag').length`)
  check(citationCount > 0,
    '🔴 引用来源卡片渲染出来了（来自 `done.citations` —— `T-804` 之前它是拿 `chunks` 按 `i+1` 拼的）',
    `卡片=${citationCount} 张 · 正文里可点角标=${markerCount} 个`)
  check(markerCount > 0,
    '🔴 答案正文里的 `[n]` 被渲染成**可点角标**（需求 07 §6.6「行内引用角标」）',
    `角标=${markerCount}`)

  const clicked = await evaluate(`(() => {
    const el = document.querySelector('.inline-cit')
    if (!el) return null
    el.click()
    return true
  })()`)
  check(clicked === true, '角标可点（`onAnswerClick` 的事件委托接到了）')
  await sleep(500)
  const flashed = await evaluate(`document.querySelectorAll('.tp-cite-flash').length`)
  check(flashed > 0,
    '🔴 点角标 ⇒ 检索详情面板**滚动定位**到对应分片（需求 07 §6.6 第二行）',
    `被高亮的面板行=${flashed}`)
  const flashIsCited = await evaluate(`(() => {
    const row = document.querySelector('.tp-cite-flash')
    if (!row) return null
    return !!row.querySelector('.tp-cited')
  })()`)
  check(flashIsCited === true,
    '🔴 定位到的那一行带「已引用」标记（**不是**滚到了任意一行）',
    `定位行有标记=${flashIsCited}`)

  // ---------- ⑨ `T-804` 的反面：`cited` 三态，不是"全标 1" ----------
  const traceId = mysqlScalar('select trace_id from sys_retrieval_trace order by id desc limit 1;')
  const detailTotal = mysqlCount(`select count(*) from sys_retrieval_trace_chunk where trace_id = '${traceId}';`)
  const citedOne = mysqlCount(`select count(*) from sys_retrieval_trace_chunk where trace_id = '${traceId}' and cited = 1;`)
  const citedNull = mysqlCount(`select count(*) from sys_retrieval_trace_chunk where trace_id = '${traceId}' and cited is null;`)
  check(citedOne > 0,
    '🔴 `cited = 1` 有行（角标解析真的落库了 —— 此前这一列**恒 NULL**）',
    `cited=1 有 ${citedOne} 条 / 明细共 ${detailTotal} 条 / cited is null 有 ${citedNull} 条`)
  check(citedOne < detailTotal || citedNull > 0,
    '🔴 `cited` 不是"全标 1"（"被引用"与"喂给模型"是两份不同的清单）',
    `1 的条数 ${citedOne} < 明细总数 ${detailTotal}（或存在 null 行 ${citedNull}）`)

  // ---------- ⑩ 点引用卡片里的文档名 ⇒ 预览页带 `?chunk=` 且真的定位 ----------
  const popupUrl = await evaluate(`(() => {
    const title = document.querySelector('.tp-cite-title-clickable')
    if (!title) return null
    // 拦下 window.open：**本脚本要断言的是 URL**，而新标签页在 CDP 里另开一个 target，
    // 断言它要再连一次 WebSocket —— 代价大于收益，且拦下来看到的 URL 与它逐字相同
    const real = window.open
    let captured = null
    window.open = (u) => { captured = u; return null }
    title.click()
    window.open = real
    return captured
  })()`)
  check(!!popupUrl && String(popupUrl).includes('chunk='),
    '🔴 点引用来源卡片 ⇒ 打开预览页并**带 `?chunk={chunkId}`**（需求 07 §6.6 第三行）',
    `URL=${popupUrl ?? '(没打开)'}`)

  if (popupUrl) {
    await goto(popupUrl)
    await waitFor(`!!document.querySelector('.page-title')`, { timeout: 20000, label: '预览页挂载' })
    const selected = await waitFor(`(() => {
      const el = document.querySelector('.flash')
      return el ? el.getAttribute('data-chunk') : null
    })()`, { timeout: 15000, label: '预览页定位到分片（`.flash` 类）' })
    const wantedChunk = String(popupUrl).split('chunk=')[1]?.split('&')[0]
    check(String(selected) === String(wantedChunk),
      '🔴 预览页**真的定位到了那一片**（不是"只是打开了页面"—— 两者在截图上长得一样）',
      `URL 要的分片=${wantedChunk} 页面上闪的那个=${selected}`)
  }

  // ---------- ⑪ `T-806`：`vision=false` ⇒ 按钮置灰且带原因 ----------
  await goto(`${BASE}/#/chat`)
  await waitFor(`document.querySelectorAll('.msg-foot').length > 0`, { timeout: 20000, label: '问答页仍挂着上一轮' })
  const denied = await evaluate(`(() => {
    const btns = [...document.querySelectorAll('.mf-actions button')]
    const b = btns.find((x) => x.textContent.includes('直接读原图'))
    if (!b) return null
    return { disabled: b.disabled || b.classList.contains('is-disabled'), title: b.getAttribute('title') ?? '' }
  })()`)
  check(denied && denied.disabled === true,
    '🔴 `vision=false` 时「直接读原图」**置灰**（需求 09 §4.7「否则按钮置灰并说明原因」）',
    `disabled=${denied?.disabled} title="${denied?.title}"`)
  check(!!denied && denied.title.includes('视觉'),
    '🔴 置灰**带原因**（不是"点了没反应"）', `title="${denied?.title}"`)

  // ---------- ⑫ `T-806`：把 vision 打开 ⇒ 按钮可点 ⇒ 点它 ⇒ 「已读原图」 ----------
  mysqlScalar(`update sys_model_config set capabilities = '{"stream":true,"vision":true,"contextWindow":128000,"supportSystemRole":true}' where id = ${chatModelId};`)
  // ⚠️ `ModelConfigSpec#version` 取自 `updated_at` 的毫秒值，而它是客户端缓存的 key 之一 ——
  //    只改 `capabilities` 而不动 `updated_at`，注册中心仍拿旧 spec（页面看着改了、行为没变）
  mysqlScalar(`update sys_model_config set updated_at = now() where id = ${chatModelId};`)
  await send('Page.reload')
  await waitFor(`document.querySelectorAll('.msg-foot').length > 0`, { timeout: 25000, label: '重载后仍能取到模型能力' })
  const enabled = await evaluate(`(() => {
    const btns = [...document.querySelectorAll('.mf-actions button')]
    const b = btns.find((x) => x.textContent.includes('直接读原图'))
    if (!b) return null
    return { disabled: b.disabled || b.classList.contains('is-disabled'), title: b.getAttribute('title') ?? '' }
  })()`)
  check(enabled && enabled.disabled === false,
    '🔴 `vision=true` 时按钮**可点**（能力闸跟着配置走 —— 不是写死的）',
    `disabled=${enabled?.disabled} title="${enabled?.title}"`)

  const roundsBefore = mysqlCount('select count(*) from sys_chat_message;')
  await clickReal('.mf-actions button')
  await waitFor(`document.querySelectorAll('.mf-vision').length > 0`, { timeout: 180000, label: '页脚出现「已读原图」' })
  check(true, '🔴 点「直接读原图」⇒ 消息页脚出现「已读原图」（需求 07 §6.12）')

  const usedVisionRows = mysqlCount('select count(*) from sys_chat_message where used_vision = 1;')
  const usedVisionZero = mysqlCount(`select count(*) from sys_chat_message where used_vision = 0 and id > ${roundsBefore - 2};`)
  check(usedVisionRows >= 1,
    '🔴 `sys_chat_message.used_vision = 1` 真的落库了（需求 09 §4.7 的「记录」那一行）',
    `used_vision=1 的行 ${usedVisionRows} 条`)
  check(usedVisionZero >= 1,
    '🔴 **只对该轮生效**：同一会话上一轮仍是 `0`（会话级的话上一轮会被覆盖成 1，'
      + '而「用描述 vs 用原图」的对比就做不出来了）',
    `used_vision=0 的行 ${usedVisionZero} 条`)

  // ---------- ⑬ 无未捕获异常 ----------
  check(consoleMsgs.length === 0, '页面控制台无未捕获异常', consoleMsgs.slice(0, 2).join(' | '))
}

// ---------------------------------------------------------------- 辅助
/** 等某库的文档到 `READY`（本仓口径：READY 是唯一"可被检索"的状态）。 */
async function waitDocReady(kbIdToWait, expected, timeout = 180000) {
  const deadline = Date.now() + timeout
  let last = ''
  while (Date.now() < deadline) {
    const list = bizOk(await api(`/kb/${kbIdToWait}/docs?page=1&pageSize=50`), '文档列表').list ?? []
    last = list.map((d) => d.status).join(',')
    if (list.filter((d) => d.status === 'READY').length >= expected) return
    if (list.some((d) => d.status === 'FAILED')) throw new Error(`解析失败：${last}`)
    await sleep(1500)
  }
  throw new Error(`等待 READY 超时（${timeout}ms）：最后一次状态 = ${last}`)
}

async function run() {
  await main()
}

run()
  .catch((e) => {
    check(false, '脚本执行中断', e.message)
  })
  .finally(async () => {
    // 收尾必须走 finally：**中途任何一步抛错都要清干净**（`#186` 那条教训）
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
    try {
      rmSync(PROFILE, { recursive: true, force: true })
    } catch {
      /* 忽略 */
    }

    // 🔴 **`capabilities` 必须原样复原**（`#166` 那族：改全局状态的用例要复原，
    //    否则"看着无关"的类会红 —— 因为能力闸读的正是这一列）
    try {
      if (chatModelId > 0) {
        const value = originalCapabilities === 'NULL' ? 'null' : `'${originalCapabilities}'`
        mysqlScalar(`update sys_model_config set capabilities = ${value} where id = ${chatModelId};`)
        mysqlScalar(`update sys_model_config set updated_at = now() where id = ${chatModelId};`)
      }
    } catch (e) {
      check(false, '收尾：复原 capabilities 抛错', e.message)
    }

    // 本轮提问产生的会话族：按**精确 id** 删（`T-O23`：不靠水位线）
    try {
      if (sessionId > 0) {
        mysqlScalar(
          `delete from sys_retrieval_trace_chunk where trace_id in (select trace_id from sys_retrieval_trace where session_id = ${sessionId});`
          + `delete from sys_retrieval_trace where session_id = ${sessionId};`
          + `delete from sys_chat_message where session_id = ${sessionId};`
          + `delete from sys_chat_session where id = ${sessionId};`)
      }
    } catch (e) {
      check(false, '收尾：会话族精确清理抛错', e.message)
    }

    let clean = false
    try {
      const r = await teardownKbProbe(KB_CODE)
      const residue = r.residue ?? r
      clean = kbResidueClean(residue)
      check(clean, '收尾：探针库族残留 = 0（断言，不是日志）', describeKbResidue(residue))
      // 🔴 **第二族也要断言**（会话/打点/消息/附件）—— 只核一族等于没核
      const chatLeft = mysqlCount('select count(*) from sys_chat_session;')
        + mysqlCount('select count(*) from sys_chat_message;')
        + mysqlCount('select count(*) from sys_retrieval_trace;')
        + mysqlCount('select count(*) from sys_retrieval_trace_chunk;')
        + mysqlCount('select count(*) from sys_chat_attachment;')
      check(chatLeft === 0, '收尾：**会话族 + 附件族**残留 = 0', `剩余 ${chatLeft} 行`)
      if (chatLeft !== 0) clean = false
    } catch (e) {
      check(false, '收尾：残留自检自身抛错', e.message)
    }

    const failed = steps.filter((s) => !s.ok)
    console.log(`\n${failed.length === 0 ? '✓' : '✗'} ${steps.length - failed.length}/${steps.length} 通过`)
    console.log(`探针库 id=${kbId} · 文档 id=${docId} · 截图目录=${SHOT_DIR}`)
    if (failed.length > 0) {
      console.log('\n未通过：')
      failed.forEach((s) => console.log(`  ✗ ${s.label}${s.detail ? ` · ${s.detail}` : ''}`))
      process.exit(1)
    }
    if (!clean) process.exit(2)
    process.exit(0)
  })
