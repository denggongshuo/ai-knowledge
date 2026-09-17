#!/usr/bin/env node
/**
 * S1 · G1-9 页面级联调自检（真浏览器 + 真后端，走 CDP）
 *
 * 用法：
 *   1) 后端：mvn -o spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
 *   2) 前端：VITE_USE_MOCK=false node node_modules/vite/bin/vite.js
 *      （注意看 Vite 日志的 `Local:` 行——5173 被占时会落到 5174）
 *   3) node scripts/e2e-auth-flow.mjs --base=http://localhost:5174
 *
 * 为什么不用 agent-browser / playwright：
 *   项目既有自检脚本（render-check 等）都是「Node 直接 spawn msedge.exe」，不装 npm 依赖。
 *   render-check 只用 --dump-dom，**只能渲染、不能交互**；要跑「注册 → 登录 → 登出」
 *   就必须真点真填。Node 22 自带 WebSocket，于是直接用 CDP 驱动同一个 Edge：
 *   零新增依赖，且与既有脚本同一条技术路线。
 *
 * 判据（03-Sprint排期 §Sprint 1 · G1-9）：前端 VITE_USE_MOCK=false 完成
 * 「注册 → 登录 → 登出」全流程。逐步断言，并抓 `x-trace-id` 响应头 ——
 * **mock 不产出该字段**，所以它同时证明了"请求真的打到了真后端"。
 *
 * 两个踩过的坑（都在代码里留了注释）：
 *   ① 断言必须看**业务 code**，不能看 HTTP 状态码 —— 本项目业务错误一律 200 + 非 0 code；
 *   ② 每次运行前必须**清掉 Edge profile** —— 残留的 ak_token 会让路由守卫
 *      把 `/login` 重定向到 `/chat`，表现为"注册成功却没跳登录页"。
 */
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync, existsSync, rmSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { teardownUserProbe } from './lib/probe-teardown.mjs'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-e2e'
const OUT_DIR = 'F:/work/code/ai-knowledge/temp/e2e-auth'
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9333)

const argBase = process.argv.find((a) => a.startsWith('--base='))
const BASE = argBase ? argBase.slice('--base='.length) : 'http://localhost:5174'

const PASSWORD = 'E2eProbe@12345'
const stamp = Date.now()
const USERNAME = `e2e${stamp}`
const PHONE = `139${String(stamp % 100000000).padStart(8, '0')}`

// ---------------------------------------------------------------- CDP 基础
let ws
let seq = 0
const pending = new Map()
/** /api/ 响应记录：`x-trace-id` 是真后端的指纹，mock 不会产出 */
const apiCalls = []
/** 页面 console 的 warn/error：用来抓 Vue 的「Failed to resolve component」这类只在控制台的错 */
const consoleMsgs = []
/** 非致命观察项：不计入 G1-9 成败，但要报出来（如页面写死的展示值） */
const observations = []

function send(method, params = {}) {
  const id = ++seq
  return new Promise((res, rej) => {
    pending.set(id, { res, rej })
    ws.send(JSON.stringify({ id, method, params }))
    setTimeout(() => {
      if (pending.has(id)) {
        pending.delete(id)
        rej(new Error(`CDP 调用超时：${method}`))
      }
    }, 30000)
  })
}

async function evaluate(expression) {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (r.exceptionDetails) {
    const d = r.exceptionDetails
    throw new Error(d.exception?.description || d.text || '页面脚本抛异常')
  }
  return r.result?.value
}

async function waitFor(expression, label, timeout = 20000) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeout) {
    if (await evaluate(expression)) return true
    await sleep(120)
  }
  throw new Error(`等待超时（${label}）：${expression}`)
}

/** 取响应体（业务码只在 body 里，`Network.responseReceived` 只给状态码） */
async function bodyOf(call) {
  try {
    const r = await send('Network.getResponseBody', { requestId: call.requestId })
    return r.base64Encoded ? Buffer.from(r.body, 'base64').toString('utf8') : r.body
  } catch {
    return ''
  }
}

/** 页面上最后一条错误提示（业务失败时前端会 ElMessage.error 后端 message） */
async function lastToast() {
  return evaluate(
    `(document.querySelector('.el-message--error') || document.querySelector('.el-message'))?.textContent?.trim() ?? ''`,
  )
}

/**
 * 断言「业务成功」。
 *
 * <p>**不要退化成只看 `status === 200`**：本项目业务错误一律 HTTP 200 + 非 0 code
 * （10015 密码错、10031 用户名已存在、10037 场景非法…）。只判状态码会把失败当成功，
 * 然后在后续的「等待跳转」处超时 —— 报出来的是"等待超时"，离真正原因差了三层。
 */
async function assertBizOk(call, label) {
  check(call, `${label}：未观察到请求（多半是表单校验没过，请求根本没发出去）`)
  const raw = await bodyOf(call)
  if (!raw) throw new Error(`${label}：拿不到响应体（HTTP ${call.status}）`)
  let biz
  try {
    biz = JSON.parse(raw)
  } catch {
    throw new Error(`${label}：响应不是 JSON（HTTP ${call.status}）：${raw.slice(0, 120)}`)
  }
  if (biz.code !== 0) {
    throw new Error(`${label}：HTTP ${call.status} 但业务失败 code=${biz.code} · ${biz.message}`)
  }
  return `HTTP ${call.status} · code=0 · traceId=${call.traceId}`
}

async function shot(name) {
  const r = await send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(resolve(OUT_DIR, `${name}.png`), Buffer.from(r.data, 'base64'))
}

// 页面内工具函数（注入一次，后续复用）
const HELPERS = `
window.__t = {
  byLabel(label, value) {
    const items = [...document.querySelectorAll('.el-form-item')]
    const it = items.find((i) =>
      (i.querySelector('.el-form-item__label')?.textContent ?? '').trim().startsWith(label))
    if (!it) throw new Error('找不到表单项：' + label)
    const input = it.querySelector('input, textarea')
    if (!input) throw new Error('表单项内无输入框：' + label)
    const proto = input.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(input, value)
    // Vue 的 v-model 监听 input 事件；blur 让 el-form 的 trigger:'blur' 校验也跑起来
    input.dispatchEvent(new Event('input', { bubbles: true }))
    input.dispatchEvent(new Event('change', { bubbles: true }))
    input.dispatchEvent(new Event('blur', { bubbles: true }))
    return true
  },
  clickText(text, scope) {
    const norm = (s) => (s || '').replace(/\\s/g, '')
    const scopeEl = scope ? document.querySelector(scope) : document
    if (!scopeEl) throw new Error('找不到作用域：' + scope)
    // 用 contains 判断归属而不是对 scope 做 querySelectorAll：scope 常常就是元素**自身**
    // （.reg-btn / .login-btn 这两个类名就挂在按钮上），对自身查后代永远查不到。
    // 另外 Element Plus 的 el-dropdown-item 渲染出的类名是 .el-dropdown-menu__item，不是组件名。
    const all = [...document.querySelectorAll('.el-button, button, .el-dropdown-menu__item, .el-dropdown-item')]
    const b = all.find((x) => scopeEl.contains(x) && norm(x.textContent).includes(norm(text)))
    if (!b) throw new Error('找不到可点元素：' + text)
    b.click()
    return true
  },
}
true`

// ---------------------------------------------------------------- 主流程
const steps = []

async function step(name, fn) {
  try {
    const detail = await fn()
    steps.push({ name, ok: true })
    console.log(`  ✅ ${name}${detail ? '  —  ' + detail : ''}`)
  } catch (e) {
    steps.push({ name, ok: false, error: e.message })
    console.log(`  ❌ ${name}  —  ${e.message}`)
    throw e
  }
}

function check(cond, msg) {
  if (!cond) throw new Error(msg)
}

async function main() {
  if (!existsSync(EDGE)) throw new Error(`找不到 Edge：${EDGE}`)
  mkdirSync(OUT_DIR, { recursive: true })

  console.log(`\n联调目标：${BASE}`)
  console.log(`探针账号：${USERNAME} / ${PHONE} / ${PASSWORD}\n`)

  const probe = await fetch(`${BASE}/api/health/storage`).then((r) => r.json()).catch(() => null)
  check(probe?.code === 0, `经前端代理访问 ${BASE}/api/health/storage 失败——前端 dev 是否已启动？`)
  console.log(`[前置] 经前端代理打到真后端：traceId=${probe.traceId} · 存储 ${probe.data?.upCount}/${probe.data?.total} 在线\n`)

  console.log('[1/5] 注册')
  await step('打开注册页', async () => {
    await send('Page.navigate', { url: `${BASE}/#/register` })
    await waitFor(`!!document.querySelector('.auth-card')`, '注册页渲染')
    const hash = await evaluate(`location.hash`)
    check(hash.includes('/register'), `未停留在注册页（当前 ${hash}）——profile 是否没清干净？`)
    return 'auth-card 已渲染'
  })
  await step('填写注册表单', async () => {
    await evaluate(`${HELPERS}; __t.byLabel('用户名', ${JSON.stringify(USERNAME)})`)
    await evaluate(`__t.byLabel('手机号', ${JSON.stringify(PHONE)})`)
    await evaluate(`__t.byLabel('设置密码', ${JSON.stringify(PASSWORD)})`)
    await evaluate(`__t.byLabel('确认密码', ${JSON.stringify(PASSWORD)})`)
    await evaluate(`__t.byLabel('真实姓名', '联调探针')`)
    return '5 个字段已填（原生 setter + input 事件，走 Vue 的 v-model）'
  })
  await step('点「获取验证码」→ /auth/sms-code', async () => {
    const before = apiCalls.length
    await evaluate(`__t.clickText('获取验证码')`)
    await sleep(1200)
    return assertBizOk(apiCalls.slice(before).find((c) => c.url.includes('/auth/sms-code')), '发码')
  })
  await step('填验证码 000000 并勾选协议', async () => {
    await evaluate(`__t.byLabel('短信验证码', '000000')`)
    await evaluate(`(() => {
      const cb = document.querySelector('.el-checkbox')
      if (!cb) throw new Error('找不到协议勾选框')
      if (!cb.classList.contains('is-checked')) cb.click()
      return true
    })()`)
    // 断言必须和 click 分成两次往返：is-checked 是 Vue 重渲染后才加上的 class，
    // 在同一次 evaluate 里紧接着读，读到的还是旧值（会随机失败）。
    await sleep(300)
    const checked = await evaluate(`document.querySelector('.el-checkbox')?.classList.contains('is-checked') ?? false`)
    check(checked, '协议勾选框未能勾上')
    return '验证码 + 已阅读协议'
  })
  await step('提交注册 → 应跳转 /login', async () => {
    const before = apiCalls.length
    await evaluate(`__t.clickText('注 册', '.reg-btn')`)
    await sleep(1600)
    const detail = await assertBizOk(
      apiCalls.slice(before).find((c) => c.url.includes('/auth/register')),
      '注册',
    )
    if (!(await evaluate(`location.hash.includes('/login')`))) {
      const hash = await evaluate(`location.hash`)
      const toast = await lastToast()
      throw new Error(`注册已成功但未跳到 /login（当前 ${hash}）。页面提示：「${toast}」`)
    }
    return `${detail} · 已跳转 #/login`
  })
  await shot('1-after-register')

  console.log('\n[2/5] 登录')
  await step('填写账号密码并登录', async () => {
    const before = apiCalls.length
    await evaluate(`${HELPERS}; __t.byLabel('账号', ${JSON.stringify(USERNAME)})`)
    await evaluate(`__t.byLabel('密码', ${JSON.stringify(PASSWORD)})`)
    await evaluate(`__t.clickText('登 录', '.login-btn')`)
    await sleep(1800)
    const detail = await assertBizOk(
      apiCalls.slice(before).find((c) => c.url.includes('/auth/login')),
      '登录',
    )
    if (!(await evaluate(`location.hash.includes('/chat')`))) {
      const hash = await evaluate(`location.hash`)
      const toast = await lastToast()
      throw new Error(`登录已成功但未进 /chat（当前 ${hash}）。页面提示：「${toast}」`)
    }
    return `${detail} · 已进入 #/chat`
  })
  await step('登录态写入 localStorage 且顶栏显示真实用户', async () => {
    const info = JSON.parse(
      await evaluate(`JSON.stringify({
        tokenLen: (localStorage.getItem('ak_token') || '').length,
        chip: (document.querySelector('.user-chip')?.textContent ?? '').trim(),
      })`),
    )
    check(info.tokenLen > 40, `localStorage.ak_token 异常（长度 ${info.tokenLen}）`)
    check(info.chip.length > 0, '顶栏未显示用户名')
    return `ak_token ${info.tokenLen} 字符 · 顶栏「${info.chip}」`
  })
  await step('登录态可访问受保护页面（个人信息）', async () => {
    await send('Page.navigate', { url: `${BASE}/#/profile` })
    await waitFor(`!!document.querySelector('.sys-profile-page')`, '个人信息页渲染')
    return '未被弹回登录页'
  })
  await step('核对个人信息页显示的账号数据', async () => {
    // 真实值取自同一账号的 /user/profile，用来和页面**渲染出来的文字**比对
    const token = await evaluate(`localStorage.getItem('ak_token') || ''`)
    const real = await fetch(`${BASE}/api/user/profile`, { headers: { Authorization: `Bearer ${token}` } })
      .then((r) => r.json())
      .then((r) => r.data)
    const view = JSON.parse(
      await evaluate(`JSON.stringify({
        head: [...document.querySelectorAll('.profile-info h3 .tag-status')].map(e => e.textContent.trim()),
        kv: [...document.querySelectorAll('.kv dd')].map(e => e.textContent.trim()),
      })`),
    )
    // 非致命：只登记，不打断 G1-9（G1-9 判据是三条链路跑通，不含展示口径）
    if (!view.kv.includes(String(real.id))) {
      observations.push(`个人信息页「用户 ID」显示 ${view.kv[0]}，真实 id=${real.id}（页面用的是写死的初始值）`)
    }
    if (view.head.some((t) => t.includes('管理员')) && !real.roles.includes('SUPER_ADMIN')) {
      observations.push(
        `个人信息页把「${real.roleNames?.join('/')}」账号标注为「${view.head.find((t) => t.includes('管理员'))}」（模板写死，未按真实角色渲染）`,
      )
    }
    return `页面账号卡：${view.kv.slice(0, 2).join(' / ')} · 真实 id=${real.id} 角色=${real.roleNames?.join('/')}`
  })
  await shot('2-after-login')

  console.log('\n[3/5] 登出')
  await step('点顶栏用户菜单 → 退出登录 → 确认', async () => {
    const before = apiCalls.length
    await evaluate(`document.querySelector('.user-chip').click()`)
    await waitFor(`!!document.querySelector('.el-dropdown-menu')`, '用户下拉菜单展开')
    await evaluate(`__t.clickText('退出登录', '.el-dropdown-menu')`)
    await waitFor(`!!document.querySelector('.el-message-box')`, '二次确认弹窗')
    await evaluate(`__t.clickText('确定', '.el-message-box__btns')`)
    await sleep(1500)
    return assertBizOk(
      apiCalls.slice(before).find((c) => c.url.includes('/auth/logout')),
      '登出（应是真链路，不是只清本地）',
    )
  })
  await step('回到登录页且本地令牌已清空', async () => {
    await waitFor(`location.hash.includes('/login')`, '跳回登录页')
    const left = await evaluate(`(localStorage.getItem('ak_token') || '').length`)
    check(left === 0, `localStorage.ak_token 未清空（长度 ${left}）`)
    return '已回 #/login · ak_token 已清'
  })
  await shot('3-after-logout')

  console.log('\n[4/5] 登出后受保护页面不可再进入')
  await step('直接访问 /#/profile 应被守卫弹回登录页', async () => {
    await send('Page.navigate', { url: `${BASE}/#/profile` })
    await sleep(1200)
    const hash = await evaluate(`location.hash`)
    check(hash.includes('/login'), `未被弹回登录页，当前停留 ${hash}`)
    return `当前 ${hash}`
  })

  console.log('\n[5/5] 请求台账（经前端代理打到的真后端）')
  for (const line of [...new Set(apiCalls.map((c) => `${c.url.split('?')[0]} → HTTP ${c.status}`))]) {
    console.log(`  · ${line}`)
  }
  const withTrace = apiCalls.filter((c) => c.traceId).length
  console.log(`  · 带 x-trace-id 的响应：${withTrace}/${apiCalls.length}（有 traceId = 真后端，mock 不产出）`)

  const warns = consoleMsgs.filter((m) => m.level !== 'info')
  if (warns.length) {
    console.log('\n[页面 console 警告/错误]')
    for (const m of [...new Set(warns.map((w) => `${w.level}: ${w.text}`))]) console.log(`  · ${m}`)
  }
  if (observations.length) {
    console.log('\n[非致命观察项（不阻塞 G1-9，但要处理）]')
    for (const o of observations) console.log(`  · ${o}`)
  }
  console.log(`\n截图证据：${OUT_DIR}`)
}

// ---------------------------------------------------------------- 启动/收尾
function startEdge() {
  return new Promise((res, rej) => {
    const child = spawn(EDGE, [
      '--headless=new',
      '--disable-gpu',
      '--no-sandbox',
      '--disable-extensions',
      '--no-first-run',
      '--no-default-browser-check',
      `--remote-debugging-port=${CDP_PORT}`,
      `--user-data-dir=${PROFILE}`,
      'about:blank',
    ], { stdio: 'ignore' })
    const t0 = Date.now()
    const tick = async () => {
      try {
        const v = await fetch(`http://127.0.0.1:${CDP_PORT}/json/version`).then((r) => r.json())
        res({ child, version: v.Browser })
      } catch {
        if (Date.now() - t0 > 25000) return rej(new Error('Edge 调试端口未就绪'))
        setTimeout(tick, 300)
      }
    }
    tick()
  })
}

async function pickPageTarget() {
  for (let i = 0; i < 40; i++) {
    const list = await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`).then((r) => r.json())
    const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl)
    if (page) return page
    await sleep(250)
  }
  throw new Error('找不到可调试的 page target')
}

// 开跑前清掉 profile：残留的 ak_token 会让路由守卫把 /login 重定向到 /chat，
// 表现为「注册成功却没跳到登录页」这种离原因很远的现象。
// （render-check.mjs 早为同一原因写过同样的清理，此处是同一教训。）
if (existsSync(PROFILE)) rmSync(PROFILE, { recursive: true, force: true })

let child
try {
  const started = await startEdge()
  child = started.child
  const target = await pickPageTarget()
  ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((res, rej) => {
    ws.onopen = res
    ws.onerror = () => rej(new Error('CDP WebSocket 连接失败'))
  })
  ws.onmessage = (ev) => {
    let msg
    try {
      msg = JSON.parse(String(ev.data))
    } catch {
      return
    }
    if (msg.id && pending.has(msg.id)) {
      const { res, rej } = pending.get(msg.id)
      pending.delete(msg.id)
      msg.error ? rej(new Error(`${msg.error.message}（${msg.method ?? ''}）`)) : res(msg.result)
      return
    }
    if (msg.method === 'Network.responseReceived') {
      const { url, status, headers } = msg.params.response
      // 只统计真正的后端调用：Vite 会把源码模块也走 HTTP，而 `/src/api/index.ts`
      // 这个 URL 里就含 "/api/" —— 只按 includes('/api/') 过滤会把它算进台账，
      // 让"带 traceId 的响应 11/13"看起来像有两条后端响应缺 traceId。
      if (!url.startsWith(`${BASE}/api/`)) return
      apiCalls.push({
        requestId: msg.params.requestId,
        url: url.slice(BASE.length),
        status,
        traceId: headers['x-trace-id'] || headers['X-Trace-Id'] || '',
      })
    }
    if (msg.method === 'Runtime.consoleAPICalled') {
      const { type, args } = msg.params
      if (type === 'warning' || type === 'error') {
        consoleMsgs.push({ level: type, text: args.map((a) => a.value ?? a.description ?? '').join(' ') })
      }
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
  // 固定视口。默认的无头窗口只有 800x600，会把个人信息页右栏挤成逐字竖排 ——
  // 那是我自己截图窗口太窄造成的假象，不是布局缺陷；不固定视口就会误报。
  await send('Emulation.setDeviceMetricsOverride', {
    width: 1440,
    height: 900,
    deviceScaleFactor: 1,
    mobile: false,
  })

  await main()
  const failed = steps.filter((s) => !s.ok).length
  console.log(`\n合计 ${steps.length} 步，通过 ${steps.length - failed}，失败 ${failed}`)
  process.exitCode = failed ? 1 : 0
} catch (e) {
  console.error(`\n✗ 中断：${e.message}`)
  process.exitCode = 1
} finally {
  try {
    ws?.close()
  } catch {}
  child?.kill()

  // ⚠️ 自清（`S5` 测试可靠性修复批 · `#120`）：本脚本会**真注册**一个 `e2e<stamp>` 账号。
  // 此前它不删 —— 跑一轮就在 `sys_user` 里留一行，把 `sys_user` 从 128 顶到 129，
  // 而 `e2e-admin-flow` 有一条「总用户数 = 128」的断言 ⇒ **跑完 auth 再跑 admin 必红**。
  // 现象离原因很远（报的是"用户数不对"），所以此前一直当成"admin 脚本自己有问题"。
  try {
    const r = teardownUserProbe(USERNAME)
    const residue = Object.entries(r.residue.sql).filter(([, n]) => n > 0)
    console.log(`  ↩︎ 已清理探针账号 ${USERNAME}：登录日志 ${r.loginLogs} 行`
      + (residue.length ? ` · ⚠️ 仍有残留 ${JSON.stringify(Object.fromEntries(residue))}` : ' · 残留 0'))
  } catch (e) {
    // 清理失败**不改退出码**（用例结论由 steps 决定），但必须喊出来 ——
    // 静默的清理失败正是 `#120` 之所以能藏这么久的原因
    console.warn(`  ⚠️ 探针账号清理失败：${e.message}`)
  }

  await sleep(300)
}
