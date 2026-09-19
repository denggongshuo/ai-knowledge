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
 * - 🔴 **失败（`#227` 补的两条）**：① **页面没挂载**（`#app` 没有子节点）
 *   ② **期望的页面特征没出现**（与 `render-check` 同一份 `expect`）。
 *   ⚠️ 修前只有"有没有 error"这一条判据，而**页面整页挂掉 = 没有 error** ⇒ 恒绿。
 * - **预期**：`[未实现接口] … → 404` 的降噪提示（request 层每接口只打一次），
 *   以及浏览器对 404 的网络资源日志（source=network）—— 不计失败，只当观察项列出。
 *
 * <h3>前置</h3>
 * 后端 8080 + 前端 dev（**真后端模式**为常规用法：`VITE_USE_MOCK=false`，绑 127.0.0.1；
 * mock 模式也能跑）。脚本会先做 **dev server 前置自检**并把模式打出来。
 * <pre>node scripts/console-check.mjs --base=http://127.0.0.1:5174</pre>
 *
 * <h3>退出码</h3>
 * `0` 全过 · `1` 有路由失败 · `2` **前置不满足**（server 不可达 / API 层探针失败，一条路由都没跑）。
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import { ROUTES } from './lib/routes.mjs'
import { probeDevServer, reportPrecondition } from './lib/dev-server.mjs'

const arg = (name, fallback) => (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://127.0.0.1:5174')
const API = arg('api', 'http://localhost:8080')

const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-console-check'
const OUT_DIR = 'F:/work/code/ai-knowledge/temp/console-check'
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9336)

/**
 * 路由清单从 **`lib/routes.mjs`** 来 —— 与 `render-check.mjs` 共用**同一份**。
 *
 * 🔴 这里此前是一份**手抄的拷贝**，注释还写着「与 render-check.mjs 同一份路由清单」
 * —— **那句话是靠注释维持的**：抄出来的那份**没有 `expect`**，于是本脚本只能断言
 * "控制台没有 error"，而**页面整页挂掉 = 没有 error** ⇒ 一条恒绿的判据（`#227`）。
 * 现在清单是单一事实源，本脚本也**用同一份 `expect` 做渲染断言**。
 */


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

  // 🔴 **前置自检**：先证明"我打的是哪个 server、它在哪个模式"。
  // 本脚本**两种模式都能跑**，所以只在连不上/探针失败时退出（exit 2）。
  // 注意顺序：放在 `apiLogin()` **之前** —— 否则"后端没起"会先以登录失败的面目出现，
  // 把人引去查后端，而真正的问题可能是"打错了 dev server"。
  const server = reportPrecondition(await probeDevServer(BASE), 'console-check')

  // mock 模式下**不去连真后端**：守卫只检查 localStorage 里有没有 token
  // （`stores/auth.ts` 的 `isLoggedIn: (s) => !!s.token`），页面数据全部来自 mock 层。
  // ⇒ 这时还去连 8080，只会让"本来在 mock 下就能跑的判据"依赖一个它并不需要的东西，
  //    而那个依赖失败时报的是"登录失败"，把人引去查后端（真正的问题可能是别的）。
  let token
  if (server.mode === 'mock') {
    token = 'console_check_mock_token'
    console.log(`mock 模式：跳过真后端登录（占位 token，页面数据来自 mock 层）；开始遍历 ${ROUTES.length} 路由 …\n`)
  } else {
    token = await apiLogin()
    console.log(`admin 登录 OK（${token.length} 字符）；开始遍历 ${ROUTES.length} 路由 …\n`)
  }

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

      // 🔴 **必须接住 `waitFor` 的返回值**（`#227` 的根因）
      //    原写法是 `await waitFor(...)` —— 返回值被**整个丢掉**。
      //    于是"页面**根本没挂载**"与"挂载了、也没报错"在下游**完全分不出来**，后者照样记 ✓。
      //    这与 `#187`（`await quiesceParseJobs(code)` 的返回值被丢）是**同一个形状**：
      //    信息存在，但在传递的路上被扔了。
      const mounted = await waitFor(
        `!!document.querySelector('#app') && document.querySelector('#app').children.length > 0`,
        { timeout: 20000 },
      )
      await sleep(1500) // 等页面内请求落地（含 404 的未实现接口）

      // ② **渲染断言** —— 用与 `render-check` **同一份** `expect`。
      //    只断言"控制台没有 error"是不够的：**页面整页挂掉 = 没有 error** ⇒ 恒绿。
      // ⚠️ 但本脚本跑的是**真后端**，而清单里有几条 `expect` 的标题**只在 mock 数据里**
      //    （`lib/routes.mjs` 的 `mockData` 标记）。真后端模式下**跳过**这几条 ——
      //    不跳的话这个闸会在它**本该跑的模式里恒红**，那比恒绿好不了多少。
      //    ⚠️ 跳过必须**打印出来**，不静默：否则"跳过"与"通过"长得一样。
      const skipExpect = r.mockData === true && server.mode === 'real'
      const rendered = skipExpect
        ? mounted
        : mounted && (await evaluate(`(${r.expect.toString()}).test(document.documentElement.outerHTML)`))

      const uncaught = exceptions.length
      const jsErr = jsConsole.filter((m) => m.type === 'error')
      const logErr = logEntries.filter((e) => e.source !== 'network' && !isNetworkNoise(e.text))
      const notices = [...jsConsole, ...logEntries]
        .map((m) => m.text)
        .filter((t) => t.includes('[未实现接口]'))

      const bad = uncaught + jsErr.length + logErr.length + (mounted ? 0 : 1) + (rendered ? 0 : 1)
      if (bad > 0) failed++
      rows.push({ path: r.path, uncaught, jsErr: jsErr.length, logErr: logErr.length, notices: [...new Set(notices)] })
      const marks = `${mounted ? '' : ' **没挂载**'}${rendered ? '' : ' **期望的页面特征没出现**'}${skipExpect ? ' （数据依赖断言：真后端模式跳过）' : ''}`
      const detail = bad ? ` uncaught=${uncaught} jsErr=${jsErr.length} logErr=${logErr.length}` : ''
      console.log(`${bad ? '✗' : '✓'} ${r.path.padEnd(22)} 未捕获=${uncaught} jsError=${jsErr.length} logError=${logErr.length} 未实现提示=${notices.length}${marks}${detail}`)
      if (!mounted) {
        console.log('      [挂载] #app 里没有任何子节点 —— **页面整页没渲染**（这正是修前会被漏掉的那种）')
      } else if (!rendered) {
        console.log(`      [渲染] ${r.label ?? ''} 的页面特征没出现 —— 断言：${r.expect}`)
      }
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
