/**
 * 页面级自检 —— **思考过程折叠 + 可关闭思考模式**（`T-D07` / `T-D08`）。
 *
 * <h3>验的三件事</h3>
 * <ol>
 *   <li><b>默认开</b>：提问后出现 `.think-box`，且思考文本与正文**分处两个区域**；</li>
 *   <li><b>自动折叠</b>：正文开始（首个 `delta`）后，`.think-body` 变为隐藏
 *       （`reasoningCollapsed === true`），但思考内容**仍在 DOM 里**（不丢）；</li>
 *   <li><b>可关闭</b>：把「思考模式」开关拨到关后提问，DOM 里**不再出现** `.think-box`。</li>
 * </ol>
 *
 * <h3>前置</h3>
 * <pre>
 *   mock 模式 dev server：cd ai_knowledge_app && npm run dev -- --host 127.0.0.1 --port 5174
 *   运行：node scripts/reasoning-check.mjs --base=http://127.0.0.1:5174
 * </pre>
 * 退出码：0 全过 · 1 断言失败 · 2 前置不满足（server 不可达 / 非 mock）。
 *
 * ⚠️ 本文件没有 shebang（本机路径守卫会拦 `#!`）。
 */
import { spawn } from 'node:child_process'
import { mkdirSync, rmSync, existsSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import { probeDevServer, reportPrecondition, requireMockMode, EXIT_PRECONDITION } from './lib/dev-server.mjs'

const arg = (name, fallback) => (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://localhost:5174')
const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = `F:/work/code/ai-knowledge/temp/edge-reasoning-${Date.now() % 100000}`
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9345)

mkdirSync('F:/work/code/ai-knowledge/temp', { recursive: true })
if (existsSync(PROFILE)) rmSync(PROFILE, { recursive: true, force: true })

let edgeProc
let ws
let seq = 0
const pending = new Map()
function send(method, params = {}) {
  const id = ++seq
  return new Promise((res, rej) => {
    pending.set(id, { res, rej })
    ws.send(JSON.stringify({ id, method, params }))
    setTimeout(() => {
      if (pending.has(id)) { pending.delete(id); rej(new Error(`CDP 超时：${method}`)) }
    }, 30000)
  })
}
async function evaluate(expression) {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text)
  return r.result?.value
}
async function waitFor(expression, label, timeout = 30000) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeout) {
    if (await evaluate(expression)) return true
    await sleep(150)
  }
  throw new Error(`等待超时（${label}）：${expression}`)
}

const checks = []
function check(cond, msg) {
  checks.push([!!cond, msg])
  console.log(`${cond ? 'PASS' : 'FAIL'}  ${msg}`)
}

async function startEdge() {
  edgeProc = spawn(EDGE, [
    '--headless=new', '--disable-gpu', '--no-sandbox', '--disable-extensions',
    `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${PROFILE}`,
    'about:blank',
  ], { stdio: 'ignore', detached: false })
  const t0 = Date.now()
  while (Date.now() - t0 < 20000) {
    try { const v = await fetch(`http://127.0.0.1:${CDP_PORT}/json/version`); if (v.ok) return } catch {}
    await sleep(200)
  }
  throw new Error('Edge CDP 未就绪')
}
async function pickPageTarget() {
  const list = await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`).then((r) => r.json())
  const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl)
  if (!page) throw new Error('没有可用的 page target')
  return page.webSocketDebuggerUrl
}
function connect(url) {
  return new Promise((res, rej) => {
    ws = new WebSocket(url)
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data)
      if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.rej(new Error(m.error.message)) : p.res(m.result) }
    }
    ws.onopen = () => res()
    ws.onerror = () => rej(new Error('CDP WebSocket 连接失败'))
  })
}

/** 发一条提问：往输入框写文本并点发送按钮。 */
const SEND = `(async () => {
  const ta = document.querySelector('.chat-input textarea, textarea')
  if (!ta) return 'no-textarea'
  const setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set
  setter.call(ta, '思考过程演示')
  ta.dispatchEvent(new Event('input', { bubbles: true }))
  await new Promise(r => setTimeout(r, 50))
  const btns = [...document.querySelectorAll('button')]
  const send = btns.find(b => /发送/.test(b.textContent || '')) || btns.find(b => b.querySelector('.el-icon') && b.closest('.chat-input,.input-area,.chat-main'))
  if (!send) return 'no-send-button'
  send.click()
  return 'sent'
})()`

async function main() {
  const diag = reportPrecondition(await probeDevServer(BASE), 'reasoning-check')
  requireMockMode(diag, 'reasoning-check')

  await startEdge()
  await connect(await pickPageTarget())
  await send('Page.enable')
  await send('Runtime.enable')
  await send('Page.navigate', { url: `${BASE}/#/chat` })
  await sleep(1500)
  // 注入 mock token，避免被守卫弹回登录页
  await evaluate(`localStorage.setItem('ak_token','mock_selftest_token'); location.reload();`)
  await sleep(2500)
  await waitFor(`!!document.querySelector('.chat-input textarea, textarea')`, '聊天输入框就绪', 20000)

  // ---- 1. 默认开：发送后出现思考区 ----
  const sent = await evaluate(SEND)
  check(sent === 'sent', `提问已发出（实际：${sent}）`)
  await waitFor(`!!document.querySelector('.think-box')`, '思考区出现', 30000)
  const thinkText = await evaluate(`document.querySelector('.think-body')?.textContent || ''`)
  check(thinkText.length > 0, '思考区有内容')
  const bodyText = await evaluate(`document.querySelector('.ai-content')?.textContent || ''`)
  check(!bodyText.includes('先看用户问题'), '思考未混入正文区域')

  // ---- 2. 自动折叠：正文开始后折叠（内容仍在 DOM） ----
  await waitFor(`document.querySelector('.think-caret.collapsed') ? true : false`, '思考区自动折叠', 30000)
  const stillInDom = await evaluate(`(document.querySelector('.think-body')?.textContent || '').length > 0`)
  check(stillInDom, '折叠后思考内容仍保留在 DOM')

  // ---- 3. 关闭思考模式：不再出现思考区 ----
  await evaluate(`(() => {
    const box = document.querySelector('.mb-think .el-switch')
    if (box) box.click()
    return !!box
  })()`)
  await sleep(400)
  const off = await evaluate(`document.querySelector('.mb-think .el-switch')?.classList.contains('is-checked') === false`)
  check(off, '思考模式开关已拨到关')
  await evaluate(`document.querySelector('.mb-think')?.scrollIntoView()`)
  const sent2 = await evaluate(SEND)
  check(sent2 === 'sent', `第二轮提问已发出（实际：${sent2}）`)
  await waitFor(`document.querySelectorAll('.msg.assistant .ai-content').length >= 1`, '第二轮出现', 20000)
  await sleep(2500)
  const thinkCount = await evaluate(`document.querySelectorAll('.think-box').length`)
  check(thinkCount <= 1, `关闭思考后未新增思考区（当前 .think-box 数：${thinkCount}）`)
}

let failed = 0
try {
  await main()
} catch (ex) {
  console.error('脚本异常：', ex.message)
  failed = 1
}
const bad = checks.filter(([ok]) => !ok).length
console.log('-'.repeat(60))
console.log(`合计 ${checks.length} 条，通过 ${checks.length - bad} 条，失败 ${bad} 条`)
try { edgeProc?.kill() } catch {}
await sleep(600)
try { if (existsSync(PROFILE)) rmSync(PROFILE, { recursive: true, force: true }) } catch {}
process.exit(bad || failed ? 1 : 0)
