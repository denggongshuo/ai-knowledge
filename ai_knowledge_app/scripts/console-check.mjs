#!/usr/bin/env node
/**
 * T-3C2 取证：切**真后端**遍历全站页面，断言 console **零未捕获异常**。
 *
 * <h3>为什么要单独一个脚本</h3>
 * `render-check.mjs` 只用 `--dump-dom`（能渲染、不能看 console），且它跑在 **mock** 模式。
 * T-3C2 的判据是「切真后端遍历 22 页 console 零未捕获异常」—— 必须真后端 + 真浏览器 +
 * 抓 `Runtime.exceptionThrown` 与 console，才能看到"后端未实现 → 页面 mounted 抛未捕获异常"这类问题。
 *
 * <h3>判定口径</h3>
 * - **失败**：`Runtime.exceptionThrown`（真正未捕获）、JS 的 `console.error`、
 *   非网络来源的 `Log` error。这些都代表真实缺陷。
 * - **预期**：`[未实现接口] … → 404` 的降噪提示（request 层每接口只打一次），
 *   以及浏览器对 404 的网络资源日志（source=network）—— 不计失败，只当观察项列出。
 *
 * <h3>前置</h3>
 * 后端 8080 + 真后端模式的前端 dev（`VITE_USE_MOCK=false`，绑 127.0.0.1）。
 * <pre>node scripts/console-check.mjs --base=http://127.0.0.1:5174</pre>
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'

const arg = (name, fallback) => (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://127.0.0.1:5174')
const API = arg('api', 'http://localhost:8080')

const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-console-check'
const OUT_DIR = 'F:/work/code/ai-knowledge/temp/console-check'
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9336)

/** 与 render-check.mjs 同一份路由清单（22 个业务页 + 3 公开页 + 3 错误页）。 */
const ROUTES = [
  { path: '/login', public: true },
  { path: '/register', public: true },
  { path: '/forgot-password', public: true },
  { path: '/chat' },
  { path: '/notice' },
  { path: '/kb' },
  { path: '/kb/create' },
  { path: '/kb/1' },
  { path: '/kb/1/upload' },
  { path: '/doc/1' },
  { path: '/doc/1/preview' },
  { path: '/doc/download' },
  { path: '/recycle' },
  { path: '/ops/qa' },
  { path: '/ops/retrieval' },
  { path: '/ops/usage' },
  { path: '/profile' },
  { path: '/system/user' },
  { path: '/system/role' },
  { path: '/system/grant' },
  { path: '/system/model' },
  { path: '/system/audit' },
  { path: '/403' },
  { path: '/500' },
  { path: '/no-such-page' },
]

// ---------------------------------------------------------------- CDP
let ws
let msgId = 0
const pending = new Map()
let exceptions = [] // Runtime.exceptionThrown
let jsConsole = [] // Runtime.consoleAPICalled（error/warning）
let logEntries = [] // Log.entryAdded

function send(method, params = {}) {
  const id = ++msgId
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, method, params }))
  })
}
async function evaluate(expression) {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text ?? 'evaluate failed')
  return r.result?.value
}
async function waitFor(expression, { timeout = 15000, interval = 150 } = {}) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    try {
      if (await evaluate(expression)) return true
    } catch {
      /* 导航中 */
    }
    await sleep(interval)
  }
  return false
}
async function apiLogin() {
  const res = await fetch(`${API}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ account: 'admin', password: 'Admin@123', remember: true }),
  })
  const json = await res.json()
  if (json.code !== 0) throw new Error(`admin 登录失败：${json.message}`)
  return json.data.accessToken
}

const isNetworkNoise = (text) => /Failed to load resource|net::ERR|favicon/i.test(text)

async function main() {
  if (!existsSync(EDGE)) throw new Error(`找不到 Edge：${EDGE}`)
  rmSync(PROFILE, { recursive: true, force: true })
  mkdirSync(OUT_DIR, { recursive: true })

  const token = await apiLogin()
  console.log(`admin 登录 OK（${token.length} 字符）；开始遍历 ${ROUTES.length} 路由 …\n`)

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
      if (msg.error) p.reject(new Error(msg.error.message))
      else p.resolve(msg.result)
      return
    }
    if (msg.method === 'Runtime.exceptionThrown') {
      const d = msg.params.exceptionDetails
      exceptions.push({ text: `${d.text ?? ''} ${d.exception?.description ?? ''}`.trim() })
    }
    if (msg.method === 'Runtime.consoleAPICalled') {
      const { type, args } = msg.params
      if (type === 'error' || type === 'warning') jsConsole.push({ type, text: args.map((a) => a.value ?? a.description ?? '').join(' ') })
    }
    if (msg.method === 'Log.entryAdded') {
      const { level, text, source } = msg.params.entry
      if (level === 'error') logEntries.push({ level, text, source })
    }
  }

  await send('Page.enable')
  await send('Runtime.enable')
  await send('Log.enable')

  const rows = []
  let failed = 0

  try {
    // 先落地站点，才能写本站 localStorage
    await send('Page.navigate', { url: `${BASE}/#/login` })
    await waitFor(`!!document.querySelector('#app')`, { timeout: 30000 })

    for (const r of ROUTES) {
      await evaluate(r.public ? `localStorage.removeItem('ak_token'); 'ok'` : `localStorage.setItem('ak_token', ${JSON.stringify(token)}); 'ok'`)
      exceptions = []
      jsConsole = []
      logEntries = []
      // 带 query + hash 整页重载：让 guard 读最新登录态、每页重新 onMounted
      await send('Page.navigate', { url: `${BASE}/?boot=${Date.now()}#${r.path}` })
      await waitFor(`!!document.querySelector('#app') && document.querySelector('#app').children.length > 0`, { timeout: 20000 })
      await sleep(1500) // 等页面内请求落地（含 404 的未实现接口）

      const uncaught = exceptions.length
      const jsErr = jsConsole.filter((m) => m.type === 'error')
      const logErr = logEntries.filter((e) => e.source !== 'network' && !isNetworkNoise(e.text))
      const notices = [...jsConsole, ...logEntries]
        .map((m) => m.text)
        .filter((t) => t.includes('[未实现接口]'))

      const bad = uncaught + jsErr.length + logErr.length
      if (bad > 0) failed++
      rows.push({ path: r.path, uncaught, jsErr: jsErr.length, logErr: logErr.length, notices: [...new Set(notices)] })
      const detail = bad ? ` uncaught=${uncaught} jsErr=${jsErr.length} logErr=${logErr.length}` : ''
      console.log(`${bad ? '✗' : '✓'} ${r.path.padEnd(22)} 未捕获=${uncaught} jsError=${jsErr.length} logError=${logErr.length} 未实现提示=${notices.length}${detail}`)
      if (bad) {
        for (const e of exceptions) console.log(`      [exceptionThrown] ${e.text.slice(0, 200)}`)
        for (const e of jsErr) console.log(`      [console.error] ${e.text.slice(0, 200)}`)
        for (const e of logErr) console.log(`      [log.error:${e.source}] ${e.text.slice(0, 200)}`)
      }
    }

    // 汇总所有"未实现接口"提示（去重）
    console.log('\n—— 与后端未实现接口相关的降噪提示（预期，非失败）——')
    const allNotices = [...new Set(rows.flatMap((r) => r.notices))]
    console.log(allNotices.length ? allNotices.map((n) => `  ${n}`).join('\n') : '  — 无')
    console.log(`\n合计 ${ROUTES.length} 路由，通过 ${ROUTES.length - failed}，失败 ${failed}`)
  } finally {
    await sleep(200)
    try {
      ws?.close()
    } catch {
      /* ignore */
    }
    child.kill()
  }
  process.exit(failed ? 1 : 0)
}

main().catch((e) => {
  console.error(`\n✗ 中断：${e.message}`)
  process.exit(1)
})
