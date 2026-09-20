/**
 * `M11` 页面级 E2E：通知中心 `/notice` 切真后端（`T-B00`/`T-B01`/`T-B02` + `G6-12`）。
 *
 * <h3>覆盖的判据</h3>
 * <ul>
 *   <li><b>G6-12</b> —— 异步任务完成 → 通知**落库**且**带可直达动作**；🔴 **不是 toast**：
 *       判据写成「**重新加载页面后那条通知还在**」—— toast 做不到这件事，这是本页
 *       与"弹个提示"的分界线；</li>
 *   <li><b>T-B00</b> —— 列表 + 六个计数来自真后端；分类计数合计 = 全部数；</li>
 *   <li><b>T-B02</b> —— 标已读走通真写接口；设置抽屉里「系统告警」开关**不可操作**；</li>
 *   <li><b>T-B01</b> —— 「待我处理」**只出有源的那半**（解析问题）。🔴 未命中那一项
 *       **不该出现**（它的源属 S8 的 `T-A00`，本批如实不发而不是发 0）；</li>
 *   <li>顶栏铃铛与侧边导航角标是**真数** —— 负向锚：页面不许再出现写死的 `5` / `23`。</li>
 * </ul>
 *
 * <h3>技术路线</h3>
 * 与 `e2e-admin-flow.mjs` 同：Node 自带 WebSocket 直驱无头 Edge 的 CDP，**零新增依赖**；
 * 断言看业务 `code` 不看 HTTP 码；抓 `x-trace-id` 证明打到了真后端（mock 不产出该头）。
 *
 * <h3>自清纪律</h3>
 * 本脚本用**真流程**造一条通知（`POST /api/audit/export` 跑完导出），跑完把那一条通知
 * 与它的任务行删掉 —— 收尾在 `finally` 里，**中途任何一步抛错都会走到**。
 * ⚠️ 不删的话，它会变成"归属 admin、`source_task_id` 悬空"的无主通知；
 * Java 用例侧与 kb 族脚本侧的兜底能扫到它，但**本脚本造的东西本脚本自己清**。
 *
 * <h3>前置</h3>
 * <pre>
 *   后端 8080：mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
 *   前端 5174：VITE_USE_MOCK=false npm run dev -- --host 127.0.0.1 --port 5174
 *   运行：node scripts/e2e-notice-flow.mjs --base=http://127.0.0.1:5174
 * </pre>
 *
 * ⚠️ **本文件没有 shebang**：本机 shell 的路径守卫会拦下含 `#!` 的内容。
 * 本脚本一律用 `node scripts/…` 显式运行（与仓里另外几个脚本的使用方式一致）。
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
// 自清走与后端同一条 SQL 通道（不经业务接口）
import { mysqlScalar, minioRemove } from './lib/probe-teardown.mjs'

const arg = (name, fallback) => (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://localhost:5174')
const API = arg('api', 'http://localhost:8080')

const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-e2e-notice'
const OUT_DIR = 'F:/work/code/ai-knowledge/temp/e2e-notice'
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9338)

/** 固定账号：种子超级管理员（导出接口要 `audit:view`，且通知归他自己）。 */
const PROBE = { account: 'admin', password: 'Admin@123' }

const steps = []
function check(ok, label, detail = '') {
  steps.push({ ok: !!ok, label, detail })
  console.log(`${ok ? '✓' : '✗'} ${label}${detail ? ` · ${detail}` : ''}`)
  return !!ok
}
function observe(label, detail = '') {
  console.log(`… ${label}${detail ? ` · ${detail}` : ''}`)
}

// ---------------------------------------------------------------- 直连后端（准备 + 轮询）
async function api(path, { method = 'GET', body, token } = {}) {
  const res = await fetch(`${API}/api${path}`, {
    method,
    headers: { ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
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

function send(method, params = {}) {
  const id = ++msgId
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, method, params }))
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
  throw new Error(`等待超时（${timeout}ms）：${label}`)
}
async function waitForApi(match, { timeout = 20000, label = '接口' } = {}) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    const hit = [...ledger].reverse().find((e) => match(e) && e.status > 0 && e.method !== 'OPTIONS')
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
window.__n = {
  norm: (s) => (s || '').replace(/\\s+/g, ' ').trim(),
  texts: (sel) => [...document.querySelectorAll(sel)].map((e) => window.__n.norm(e.textContent)),
  clickText: (t, scope) => {
    const root = scope || document
    const btn = [...root.querySelectorAll('button, .cat-item')].find((b) => window.__n.norm(b.textContent).includes(t))
    if (!btn) throw new Error('找不到可点元素：' + t)
    btn.click(); return 'ok'
  },
  navBadge: (title) => {
    const item = [...document.querySelectorAll('.el-menu-item')].find((m) => window.__n.norm(m.textContent).includes(title))
    if (!item) return null
    const b = item.querySelector('.nav-badge')
    return b ? window.__n.norm(b.textContent) : ''
  },
}
// 🔴 **探针自检**：写错这里（比如把 window.__n.norm(x) 写成 window.__n(x)）
//    的表现是「页面像是没渲染」—— 因为错误在 waitFor 的 try/catch 里被吞掉，
//    一直等到超时。本脚本第一版就是这么错了两轮。
//    有这一句，坏掉的探针会**当场抛**，而不是伪装成产品缺陷。
if (typeof window.__n.norm !== 'function' || window.__n.texts('body').length !== 1) {
  throw new Error('PAGE_TOOLS 自检失败：探针没装好')
}
'ok'
`

async function navigate(hash) {
  await send('Page.navigate', { url: `${BASE}/?boot=${Date.now()}#${hash}` })
}

/**
 * 从预签名 URL 里反推 MinIO 对象名。
 *
 * 形状：`http://host:9000/<bucket>/<objectKey>?X-Amz-...` ⇒ 取 path 去掉首段（bucket）。
 * ⚠️ 解析不出来返回 `null`（**不抛**）—— 收尾失败不该把一整轮的判据结论带走，
 *    但调用方会把"没解析出来"**打印出来**，不静默。
 */
function keyFromPresignedUrl(url) {
  if (!url) return null
  try {
    const segments = new URL(url).pathname.split('/').filter(Boolean)
    if (segments.length < 2) return null
    return segments.slice(1).join('/')
  } catch {
    return null
  }
}

async function main() {
  if (!existsSync(EDGE)) throw new Error(`找不到 Edge：${EDGE}`)
  rmSync(PROFILE, { recursive: true, force: true })
  mkdirSync(OUT_DIR, { recursive: true })

  // ---------------------------------------------------------------- ① 准备：真跑一次导出
  const login = await api('/auth/login', { method: 'POST', body: { ...PROBE, remember: true } })
  if (login.json?.code !== 0) throw new Error(`admin 登录失败：code=${login.json?.code} · ${login.json?.message}`)
  const token = login.json.data.accessToken
  check(true, '准备：admin 登录', `userId=${login.json.data.user?.id}`)

  const today = new Date().toISOString().slice(0, 10)
  const accepted = await api('/audit/export', { method: 'POST', token, body: { startTime: today, endTime: today } })
  if (accepted.json?.code !== 0) throw new Error(`提交导出失败：${accepted.text}`)
  const taskId = accepted.json.data.taskId
  check(!!taskId, '准备：真跑一次审计导出（通知的产出方）', `taskId=${taskId}`)

  let stage = ''
  for (let i = 0; i < 100 && stage !== '已完成'; i++) {
    await sleep(100)
    const t = await api(`/doc/tasks/${taskId}`, { token })
    stage = t.json?.data?.stage ?? ''
  }
  check(stage === '已完成', '导出任务跑到终态', `stage=${stage}`)

  // 🔴 **必须轮询等落库，不能"任务到终态就去查通知"** ——
  //    终态在内存 registry 里先可见，而发布是**同一条线程后面的几步**
  //    （`complete()` → `persistTerminal()` → `publisher.onTaskDone(...)`）。
  //    这是本批**第三次**撞上同一个形状：Java 侧 `NoticePublishFlowTest#awaitNotice`、
  //    `#210` 那次用例收尾、以及这里。**观测点跑在被观测动作之前**。
  let listData0 = null
  /** 通知里那个下载地址（预签名 URL）—— 收尾要靠它反推 MinIO 对象名。 */
  let exportUrl = null
  for (let i = 0; i < 60; i++) {
    const r = await api('/notices', { token })
    const d = r.json?.data
    const hit = (d?.list ?? []).find((n) => (n.actions ?? []).some((a) => /导出/.test(a.label)))
    if (hit) {
      listData0 = d
      exportUrl = (hit.actions ?? []).find((a) => /导出/.test(a.label))?.to ?? null
      break
    }
    await sleep(200)
  }
  check(!!listData0, '通知已落库（后端侧，**轮询等待**而不是查到终态就查通知）',
    `未读=${listData0?.counts?.unread ?? '—'} 等了几轮`)

  // ---------------------------------------------------------------- ② 驱动页面
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
      /* 端口还没起来 */
    }
  }
  if (!target) throw new Error('Edge 的 CDP 端口没起来')

  ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true })
    ws.addEventListener('error', reject, { once: true })
  })
  ws.addEventListener('message', (ev) => {
    const msg = JSON.parse(ev.data)
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id)
      pending.delete(msg.id)
      msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result)
      return
    }
    if (msg.method === 'Network.requestWillBeSent') {
      const entry = { requestId: msg.params.requestId, url: msg.params.request.url, method: msg.params.request.method, headers: msg.params.request.headers, status: 0, postData: msg.params.request.postData }
      ledger.push(entry)
      byRequestId.set(msg.params.requestId, entry)
    } else if (msg.method === 'Network.responseReceived') {
      const entry = byRequestId.get(msg.params.requestId)
      if (entry) {
        entry.status = msg.params.response.status
        entry.headers = msg.params.response.headers
      }
    } else if (msg.method === 'Network.loadingFinished') {
      const entry = byRequestId.get(msg.params.requestId)
      if (entry?.url) {
        entry.bodyPromise = send('Network.getResponseBody', { requestId: msg.params.requestId })
          .then((r) => {
            entry.body = r.base64Encoded ? Buffer.from(r.body, 'base64').toString('utf8') : r.body
            return entry.body
          })
          .catch(() => {})
      }
    } else if (msg.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(msg.params.type)) {
      consoleMsgs.push({ type: msg.params.type, text: msg.params.args.map((a) => a.value ?? a.description ?? '').join(' ') })
    }
  })

  await send('Page.enable')
  await send('Runtime.enable')
  await send('Network.enable')
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

  try {
    // 先落地站点，才能把 token 写进本站 localStorage
    await send('Page.navigate', { url: `${BASE}/#/login` })
    await waitFor(`!!document.querySelector('#app') && !!location.hash`, { label: '应用挂载', timeout: 30000 })
    await evaluate(`localStorage.setItem('ak_token', ${JSON.stringify(token)}); 'ok'`)

    // ==================== 1. 列表来自真后端 ====================
    await navigate('/notice')
    await waitFor(`!!document.querySelector('.notice-page')`, { label: '通知中心渲染' })
    // ⚠️ `PAGE_TOOLS` 注进 `window.__n`，**每次导航后都要重注**（整页刷新会丢）。
    //    漏了这一步的表现是：`waitFor` 里 `window.__n.texts(...)` 抛错 → 被 try/catch 吞掉
    //    → 一直等到超时，看着像"页面没渲染"，其实是"探针没注入"。
    await evaluate(PAGE_TOOLS)

    const listEntry = await waitForApi((e) => e.url.includes('/api/notices?') || e.url.endsWith('/api/notices'), { label: 'GET /api/notices' })
    check(true, '通知页调用 GET /api/notices', assertBizOk(listEntry, 'GET /api/notices'))
    const pendEntry = await waitForApi((e) => e.url.includes('/api/notices/pending'), { label: 'GET /api/notices/pending' })
    check(true, '通知页调用 GET /api/notices/pending', assertBizOk(pendEntry, 'GET /api/notices/pending'))
    const setEntry = await waitForApi((e) => e.url.includes('/api/notices/settings'), { label: 'GET /api/notices/settings' })
    check(true, '通知页调用 GET /api/notices/settings', assertBizOk(setEntry, 'GET /api/notices/settings'))

    const listData = JSON.parse(listEntry.body ?? '{}').data ?? {}
    // 诊断（常驻）：列表没渲染时，这条能一眼分出「列表空」「探针没注入」「挂到空态了」三种。
    observe('页面诊断', await evaluate(`JSON.stringify({
      hash: location.hash,
      emptyState: !!document.querySelector('.empty-wrap'),
      catItems: window.__n ? window.__n.texts('.cat-item') : '(未注入)',
      titles: window.__n ? window.__n.texts('.ni-title').length : -1,
      text: window.__n ? window.__n.norm(document.querySelector('.notice-page')?.innerText).slice(0, 180) : '',
      console: ${JSON.stringify(consoleMsgs.slice(-3).map((m) => `${m.type}: ${m.text}`.slice(0, 120)))},
    })`))
    await waitFor(`window.__n.texts('.ni-title').length > 0`, { label: '通知列表渲染出条目' })
    const titles = await evaluate(`window.__n.texts('.ni-title')`)
    check(titles.some((t) => t.includes('审计导出')), '列表渲染出后端产出的那条通知', titles.join(' | '))

    // ==================== 2. 🔴 P3：不是 toast —— 刷新后仍在 ====================
    await navigate('/notice')
    await waitFor(`!!document.querySelector('.notice-page')`, { label: '刷新后重新渲染' })
    await evaluate(PAGE_TOOLS)
    await waitFor(`window.__n.texts('.ni-title').length > 0`, { label: '刷新后列表仍有条目' })
    const afterReload = await evaluate(`window.__n.texts('.ni-title')`)
    check(afterReload.some((t) => t.includes('审计导出')),
      '🔴 G6-12：重新加载页面后那条通知**还在**（toast 做不到这件事）', `${afterReload.length} 条`)

    // 每条通知至少一个动作（需求 §2.10）
    const actionCount = await evaluate(`window.__n.texts('.ni-actions button').length`)
    check(actionCount >= 1, '🔴 §2.10：每条通知都带可执行动作', `按钮数=${actionCount}`)

    // ==================== 3. 计数同源 + 角标是真数 ====================
    const counts = listData.counts ?? {}
    const sum = (counts.UPLOAD ?? 0) + (counts.ALERT ?? 0) + (counts.PERMISSION ?? 0) + (counts.SYSTEM ?? 0)
    check(sum === counts.all, '§2.10：分类计数合计 = 全部数', `${sum} = ${counts.all}`)

    const navNotice = await evaluate(`window.__n.navBadge('消息通知')`)
    check(navNotice === '' || Number(navNotice) === counts.unread,
      '侧边导航「消息通知」角标 = 未读数（不是写死的 5）', `角标=${navNotice} 未读=${counts.unread}`)

    // 🔴 这两条在本批（`S8-M10-B`）**订正过** —— 它们原来是按 `S6-M11` 的口径写的
    //   （「问答运营」角标"已销，不假装"、「待我处理」不含未命中那一项），
    //   而 `T-A00`（`S8-M10-A`）把那个源接上了 ⇒ 旧断言在**池子为空**时恒绿、
    //   在池子里有东西时**断言的正好是错的那一边**。本批 E2E 把待办数压到 0 时才暴露。
    //   现在改成对两端都成立、且两端都不空的形状：拿库里的真实待办数当基准。
    const pendingMisses = Number(mysqlScalar(
      "select count(*) from sys_qa_miss where status in ('PENDING', 'IN_LIST');"))
    const navOps = await evaluate(`window.__n.navBadge('问答运营')`)
    check(pendingMisses === 0 ? navOps === '' : Number(navOps) === pendingMisses,
      '侧边导航「问答运营」角标 = 真实待办数（源已接上：S8-M10-A）',
      `角标=${navOps || '(无)'} 库里待办=${pendingMisses}`)

    check(await evaluate(`!!document.querySelector('.bell-badge')`) || counts.unread === 0,
      '顶栏铃铛角标存在（未读 > 0 时）', `未读=${counts.unread}`)

    // ==================== 4. 「待我处理」：有源的类目**有才发** ====================
    // ⚠️ 「缺席 ≡ 0」是这一栏的口径（`NoticeService#pending` 的注释写明了）——
    //    所以判据必须分两端写，不能只断言"键里没有 miss"（那就成了空断言）。
    const pendList = JSON.parse(pendEntry.body ?? '{}').data?.list ?? []
    const keys = pendList.map((p) => p.key)
    const missEntry = pendList.find((p) => p.key === 'miss')
    check(pendingMisses === 0 ? !keys.includes('miss') : Number(missEntry?.count) === pendingMisses,
      '「待我处理」的未命中项与库里待办数一致（有才发；缺席 ≡ 0）',
      `keys=${keys.join('|') || '(空)'} 库里=${pendingMisses}`)

    // ==================== 5. 标已读走通真写接口 ====================
    const beforeUnread = counts.unread
    await evaluate(`window.__n.clickText('全部标为已读', document.querySelector('.head-actions')); 'ok'`)
    const readAllEntry = await waitForApi((e) => e.url.includes('/api/notices/read-all') && e.method === 'POST', { label: 'POST /api/notices/read-all' })
    check(true, '「全部标为已读」走通真写接口', assertBizOk(readAllEntry, 'POST /api/notices/read-all'))
    const after = JSON.parse(readAllEntry.body ?? '{}').data ?? {}
    check(after.unread === 0 && beforeUnread >= 0, '标已读后未读归 0（真数字，不是前端自减）',
      `前=${beforeUnread} 后=${after.unread}`)

    // 刷新后仍是已读 ⇒ 状态真的落库了
    await navigate('/notice')
    await waitFor(`!!document.querySelector('.notice-page')`, { label: '再刷新渲染' })
    await evaluate(PAGE_TOOLS)
    await waitFor(`window.__n.texts('.ni-title').length > 0`, { label: '再刷新' })
    const dotsAfter = await evaluate(`window.__n.texts('.unread-dot.on').length`)
    check(dotsAfter === 0, '🔴 已读状态**落库**了（刷新后蓝点不再出现）', `蓝点=${dotsAfter}`)

    // ==================== 6. 设置抽屉：系统告警不可关 ====================
    await evaluate(`window.__n.clickText('通知设置', document.querySelector('.head-actions')); 'ok'`)
    await waitFor(`!!document.querySelector('.el-drawer') && document.querySelectorAll('.setting-row').length > 0`, { label: '设置抽屉打开' })
    const rows = await evaluate(`window.__n.texts('.setting-row')`)
    const alertRow = rows.find((r) => r.includes('系统告警')) ?? ''
    check(alertRow.includes('强制开启'), '设置里「系统告警」标注「强制开启」', alertRow.slice(0, 40))
    const alertDisabled = await evaluate(`
      (() => {
        const row = [...document.querySelectorAll('.setting-row')].find((r) => window.__n.norm(r.textContent).includes('系统告警'))
        const sw = row?.querySelector('.el-switch')
        return sw ? sw.classList.contains('is-disabled') : null
      })()
    `)
    check(alertDisabled === true, '🔴 「系统告警」开关**不可操作**（需求 §2.5）', `disabled=${alertDisabled}`)

    const shot = await send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(`${OUT_DIR}/notice.png`, Buffer.from(shot.data, 'base64'))

    // ==================== 7. console ====================
    const bad = consoleMsgs.filter((m) => /Failed to resolve component|Unhandled error|TypeError|is not a function/.test(m.text))
    check(bad.length === 0, 'console 无未捕获异常', bad.map((b) => b.text).join(' | ') || '—')

    console.log(`\n接口台账：\n${ledger.map((e) => `  ${e.method.padEnd(6)} ${e.url.replace(BASE, '')} → ${e.status}`).join('\n')}`)
    console.log(`截图：${OUT_DIR}/notice.png`)
  } finally {
    await sleep(200)
    try {
      ws?.close()
    } catch {
      /* ignore */
    }
    child.kill()
    // ---------------------------------------------------------------- 自清
    // ⚠️ 与造它对称：删通知 + 删任务行。放在 finally 里 —— **中途任何一步抛错都会走到**。
    //    （本仓「清理必须走必走路径」那条：普通 step 里清理会因抛错被跳过，`#186`。）
    try {
      const removed = mysqlScalar(`select count(*) from sys_notice where source_task_id = '${taskId}';`)
      // 🔴 **顺序固定：先删子表 `sys_notice_read`，再删主表 `sys_notice`。**
      //    ⚠️ 第一版只删了主表 ⇒ 留下一行孤儿已读行（实测：`sys_notice_read` = 1）
      //    —— 与"删了导出任务行、把 CSV 留在桶里"是**同一个形状**，
      //    连"我刚刚在 Java 侧修过一次"这件事都一样。
      mysqlScalar(`delete r from sys_notice_read r join sys_notice n on n.id = r.notice_id `
        + `where n.source_task_id = '${taskId}';`)
      mysqlScalar(`delete from sys_notice where source_task_id = '${taskId}';`)
      mysqlScalar(`delete from sys_async_task where task_id = '${taskId}';`)
      // 🔴 **桶里那个 CSV 也要删** —— 它的名字只活在任务结果里，
      //    任务行一删就再也拿不到（`result` 不持久化）。
      //    我们手上唯一的来源是**通知里那个下载地址**（预签名 URL 的 path 里带着 objectKey）。
      //    ⚠️ `T-O11` 的前缀兜底**接不住它**：那条约定的射程是"归属不存在的对象"，
      //    而这个是 `admin`（真实账号）名下的 ⇒ 只能由造它的脚本自己清。
      const objectKey = keyFromPresignedUrl(exportUrl)
      const minioGone = objectKey ? minioRemove([objectKey]) : 0
      observe('自清', `删掉 ${removed} 条通知（连同已读行）+ 1 行任务 + ${minioGone} 个导出对象`
        + `${objectKey ? `（${objectKey}）` : '（⚠️ 没解析出对象名，桶里可能留了一个）'}`)
    } catch (e) {
      console.error(`⚠️ 自清失败，请手工清理 taskId=${taskId}：${e.message}`)
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
    process.exit(1)
  })
