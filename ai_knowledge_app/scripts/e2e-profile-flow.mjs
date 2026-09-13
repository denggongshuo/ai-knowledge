#!/usr/bin/env node
/**
 * 个人信息页 E2E（A-1 ~ A-7 的收口验证）。
 *
 * <h3>为什么单独一个脚本</h3>
 * `e2e-auth-flow.mjs` 覆盖的是 G1-9 的「注册 → 登录 → 登出」；本脚本覆盖
 * `/user/**` 这一片的**页面级接线**：头像上传（multipart）、换绑发码（scene=bind）、
 * 安全概览、账号信息、手机号格式化、角色标签。
 * 两者都必须是"真浏览器 + 真后端"，因为 A-1~A-7 的共同特征是
 * **只在浏览器里才现形**（curl 不带 Origin、也不会把 FormData 序列化成 `"{}"`）。
 *
 * <h3>与 `e2e-auth-flow.mjs` 的两点不同（都是有意的）</h3>
 * <ol>
 *   <li><b>不重复走 UI 注册</b>：先用固定探针账号登录，账号不存在才补注册。
 *       每次运行都随机注册会**在库里累积一次性账号**（上一轮留下过 9 个），
 *       而本脚本的目标不是注册流程（那条链路 G1-9 已覆盖）。</li>
 *   <li><b>登录态靠写 localStorage 注入</b>：这样能固定账号；页面内的所有断言
 *       仍然是真页面读写（DOM + 真请求），没有 mock 成分。</li>
 * </ol>
 *
 * <h3>前置</h3>
 * 后端 8080、前端 dev server（默认 5174）都已在跑。用法：
 * <pre>node scripts/e2e-profile-flow.mjs --base=http://localhost:5174</pre>
 *
 * <h3>判据</h3>
 * 业务码必须为 0 —— **不能只看 HTTP 200**：本项目业务错误一律 HTTP 200 + 非 0 code，
 * 只判状态码会把失败当成功，然后在后面的"等界面变化"处超时，报出的现象离真因差三层。
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'

const arg = (name, fallback) => (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://localhost:5174')
const API = arg('api', 'http://localhost:8080')

const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-e2e-profile'
const OUT_DIR = 'F:/work/code/ai-knowledge/temp/e2e-profile'
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9334)

/** 固定探针账号：`e2e-profile-flow.mjs` 专用，不要拿去当业务数据。 */
const PROBE = { username: 'probe.profile', phone: '13900000001', password: 'Probe@1234' }

/** 1x1 PNG（68 字节）：喂给 `.avatar-file` 的真图片，用来走通 multipart 上传。 */
const PNG_1X1 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII='

// ---------------------------------------------------------------- 结果账
const steps = []
function check(ok, label, detail = '') {
  steps.push({ ok: !!ok, label, detail })
  console.log(`${ok ? '✓' : '✗'} ${label}${detail ? ` · ${detail}` : ''}`)
  return !!ok
}

// ---------------------------------------------------------------- 直连后端的探针
/** 只为"准备账号 / 兜底取证"用；页面断言一律走真浏览器。 */
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
    /* 非 JSON（例如过滤器层的裸文本 403），保留 text 供报错 */
  }
  return { status: res.status, json, text }
}

const fmtPhone = (raw) => raw.replace(/^(\d{3})(\d{4})(\d{4})$/, '$1 $2 $3')

// ---------------------------------------------------------------- CDP 连接
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

/** 等某个表达式返回真值。轮询而不是 sleep 固定时长：慢机器上固定等待必然假失败。 */
async function waitFor(expression, { timeout = 15000, interval = 150, label = expression } = {}) {
  const deadline = Date.now() + timeout
  let last
  while (Date.now() < deadline) {
    try {
      last = await evaluate(expression)
      if (last) return last
    } catch {
      /* 页面可能正在导航，重试 */
    }
    await sleep(interval)
  }
  throw new Error(`等待超时（${timeout}ms）：${label}`)
}

/** 在请求台账里等一条匹配记录（已拿到响应体）。 */
async function waitForApi(match, { timeout = 20000, label = '接口' } = {}) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    // 排除 OPTIONS：它是 CORS 预检，状态 200 但响应体为空，匹配到它会把它判成
    // "响应体不是 JSON"，看着像产品缺陷，实际是探针抓错了对象。
    // 倒序找：同一 URL 可能有多条记录（并行/重复请求），最早那条的 loadingFinished
    // 早已过去，bodyPromise 也没挂上 —— 拿它必然读到 body=null。要最新那条。
    const hit = [...ledger].reverse().find((e) => match(e) && e.status > 0 && e.method !== 'OPTIONS')
    if (hit) {
      // 条目在 responseReceived 时就进台账了，bodyPromise 要等 loadingFinished 才挂上。
      // 只 await 不等待，仍会偶发读到 body=null。
      const bodyDeadline = Date.now() + 3000
      while (!hit.bodyPromise && Date.now() < bodyDeadline) await sleep(50)
      if (hit.bodyPromise) await hit.bodyPromise.catch(() => {})
      return hit
    }
    await sleep(120)
  }
  throw new Error(`未观察到 ${label} 的响应（${timeout}ms）；已记录：\n${ledger.map((e) => `  ${e.method} ${e.url} → ${e.status}`).join('\n')}`)
}

/**
 * 断业务码为 0。
 *
 * <p>**不要退化成只看 `status === 200`**：本项目业务错误一律 HTTP 200 + 非 0 code
 * （10015 密码错、10030 手机号格式不对、10037 场景非法…）。只判状态码会把失败当成功，
 * 然后在后续「等待界面变化」处超时 —— 报出来的是"等待超时"，离真正原因差了三层。
 */
function assertBizOk(entry, label) {
  let biz = null
  try {
    biz = JSON.parse(entry.body ?? '')
  } catch {
    throw new Error(`${label}：响应体不是 JSON（HTTP ${entry.status} · ${entry.method} ${entry.url}）· ${String(entry.body).slice(0, 200)} · cdp=${entry.bodyError ?? '(未报错)'}`)
  }
  const traceId = headerOf(entry.headers, 'x-trace-id')
  if (biz.code !== 0) throw new Error(`${label}：HTTP ${entry.status} 但业务失败 code=${biz.code} · ${biz.message}`)
  return `HTTP ${entry.status} · code=0 · traceId=${traceId ?? '—'}`
}

// ---------------------------------------------------------------- 注入页内工具
/**
 * 页内小工具集。用原生 setter + input 事件驱动 Vue 的 v-model —— 直接改 `input.value`
 * 不会触发响应式更新，表单看起来填了、提交时却是空的。
 */
const PAGE_TOOLS = `
window.__t = {
  norm: (s) => (s || '').replace(/\\s+/g, ' ').trim(),
  /** 可见的 Element Plus 弹窗（隐藏的 dialog 也留在 DOM 里，按 offsetParent 过滤） */
  dialog: () => [...document.querySelectorAll('.el-dialog')].find((d) => d.offsetParent !== null) || null,
  setVal: (el, v) => {
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
    setter.call(el, v)
    el.dispatchEvent(new Event('input', { bubbles: true }))
    el.dispatchEvent(new Event('change', { bubbles: true }))
  },
  /** 文本命中即点。用 contains 判断归属而不是对 scope 查后代 —— scope 常常就是元素自身 */
  clickText: (text, scope) => {
    const root = scope || document
    const all = [...root.querySelectorAll('.el-button, button, .el-dropdown-menu__item, .el-dropdown-item')]
      .filter((x) => (scope ? scope.contains(x) : true) || x === scope)
    const b = all.find((x) => window.__t.norm(x.textContent).includes(window.__t.norm(text)))
    if (!b) throw new Error('找不到可点元素：' + text)
    b.click()
    return true
  },
  kv: () => {
    const out = {}
    const dl = document.querySelector('.kv')
    if (!dl) return out
    const nodes = [...dl.children]
    for (let i = 0; i < nodes.length; i += 2) out[window.__t.norm(nodes[i].textContent)] = window.__t.norm(nodes[i + 1]?.textContent)
    return out
  },
  tags: () => [...document.querySelectorAll('.profile-tags .plain-tag')].map((x) => window.__t.norm(x.textContent)),
  safeItems: () => [...document.querySelectorAll('.safe-list .safe-item')].map((x) => window.__t.norm(x.textContent)),
  headTags: () => [...document.querySelectorAll('.profile-info h3 .tag-status')].map((x) => window.__t.norm(x.textContent)),
}
'ok'
`

// ---------------------------------------------------------------- 对账
async function prepareAccount() {
  // 先试登录：这样重复运行时**不会**反复触发发码（同目标 1h 限 5 次），
  // 也避免"用户名已存在"把脚本变成一个只在第一次能跑的东西。
  let login = await api('/auth/login', { method: 'POST', body: { account: PROBE.username, password: PROBE.password } })
  if (login.json?.code === 0) {
    check(true, '准备探针账号：固定账号直接登录', `userId=${login.json.data.user?.id}`)
  } else {
    const code = await api('/auth/sms-code', { method: 'POST', body: { phone: PROBE.phone, scene: 'register' } })
    if (code.json?.code !== 0) throw new Error(`发码失败：code=${code.json?.code} · ${code.json?.message}`)
    const reg = await api('/auth/register', {
      method: 'POST',
      body: { username: PROBE.username, phone: PROBE.phone, smsCode: '000000', password: PROBE.password, realName: '联调探针' },
    })
    if (reg.json?.code !== 0) throw new Error(`注册探针账号失败：code=${reg.json?.code} · ${reg.json?.message}`)
    login = await api('/auth/login', { method: 'POST', body: { account: PROBE.username, password: PROBE.password } })
    if (login.json?.code !== 0) throw new Error(`探针账号登录失败：code=${login.json?.code} · ${login.json?.message}`)
    check(true, '准备探针账号：新注册 + 登录', `userId=${login.json.data.user?.id}`)
  }
  return login.json.data
}

async function main() {
  if (!existsSync(EDGE)) throw new Error(`找不到 Edge：${EDGE}`)
  rmSync(PROFILE, { recursive: true, force: true })
  mkdirSync(OUT_DIR, { recursive: true })

  const avatarFile = `${OUT_DIR}/avatar.png`
  writeFileSync(avatarFile, Buffer.from(PNG_1X1, 'base64'))

  const session = await prepareAccount()
  const token = session.accessToken
  const userId = session.user?.id

  // ---- 起浏览器 ----
  const child = spawn(
    EDGE,
    [
      '--headless=new',
      `--remote-debugging-port=${CDP_PORT}`,
      `--user-data-dir=${PROFILE}`,
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-gpu',
      'about:blank',
    ],
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
    // ---- 请求台账 ----
    if (msg.method === 'Network.requestWillBeSent') {
      const { requestId, request } = msg.params
      if (!request.url.startsWith(`${BASE}/api/`)) return
      // 注意：请求头只在 requestWillBeSent 里拿得到。responseReceived 的
      // response.headers 是**响应头**（API 一律返回 JSON），拿它断 multipart 必错。
      byRequestId.set(requestId, {
        method: request.method,
        postData: request.postData,
        reqHeaders: request.headers,
      })
    }
    if (msg.method === 'Network.responseReceived') {
      const { requestId, response } = msg.params
      // 只统计真正的后端调用：Vite 会把源码模块也走 HTTP，而 `/src/api/index.ts`
      // 这个 URL 里就含 "/api/" —— 只按 includes('/api/') 过滤会把它算进台账
      if (!response.url.startsWith(`${BASE}/api/`)) return
      const prev = byRequestId.get(requestId) ?? {}
      const entry = {
        requestId,
        url: response.url,
        status: response.status,
        method: prev.method ?? 'GET',
        postData: prev.postData,
        reqHeaders: prev.reqHeaders,
        headers: response.headers,
        body: null,
        bodyPromise: null,
      }
      ledger.push(entry)
      byRequestId.set(requestId, entry)
    }
    // 响应体要单独取：业务码藏在 body 里，只看状态码不够。
    // **必须把 Promise 挂回条目上**：取体是异步的，而条目在 responseReceived 时就已经
    // 进台账了 —— 不 await 就交给断言方，偶发拿到 body=null（表现为"响应体不是 JSON"）。
    if (msg.method === 'Network.loadingFinished') {
      const entry = byRequestId.get(msg.params.requestId)
      if (!entry || !entry.url) return
      entry.bodyPromise = send('Network.getResponseBody', { requestId: msg.params.requestId })
        .then((r) => {
          entry.body = r.base64Encoded ? Buffer.from(r.body, 'base64').toString('utf8') : r.body
          return entry.body
        })
        .catch((e) => {
          /* body 可能已被丢弃 */
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
  await send('DOM.enable')
  // 固定视口。默认无头窗口只有 800x600，会把右栏挤成逐字竖排（那是截图窗口太窄的假象）
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

  // ---- 1. 先落地到站点，才能写 localStorage（about:blank 的 localStorage 不是本站的）----
  await send('Page.navigate', { url: `${BASE}/#/login` })
  await waitFor(`!!document.querySelector('#app') && !!location.hash`, { label: '应用挂载' })
  await evaluate(`localStorage.setItem('ak_token', ${JSON.stringify(token)}); 'ok'`)

  // 带 query 导航：只改 hash 不会重新加载文档，而 Pinia 的 token 是启动时从
  // localStorage 读一次的 —— 不整页重载，注入的 token 不会进 store。
  await send('Page.navigate', { url: `${BASE}/?boot=${Date.now()}#/profile` })
  await waitFor(`!!document.querySelector('.sys-profile-page')`, { label: '个人信息页渲染' })
  await evaluate(PAGE_TOOLS)

  try {
    // ---- 2. A-4：安全概览真的调了 /user/security ----
    const secEntry = await waitForApi((e) => e.url.includes('/api/user/security') && e.method === 'GET', { label: 'GET /user/security' })
    check(true, 'A-4 页面调用 GET /user/security', assertBizOk(secEntry, 'GET /user/security'))
    // 必须走 waitForApi：裸 ledger.find 会抓到「最早那条」记录，而响应体是异步挂上去的，
    // 且 /user/profile 在启动时会被调两次 —— 抓到先到的那条就读到 body=null。
    const meEntry = await waitForApi((e) => e.url.includes('/api/user/profile') && e.method === 'GET', { label: 'GET /user/profile' })
    check(true, 'A-6 页面调用 GET /user/profile', assertBizOk(meEntry, 'GET /user/profile'))

    const profile = JSON.parse(meEntry?.body ?? '{}').data ?? {}
    const sec = JSON.parse(secEntry.body ?? '{}').data ?? {}

    // ---- 3. A-6：账号信息全部来自真实数据 ----
    const kv = await evaluate('window.__t.kv()')
    check(kv['用户 ID'] === String(profile.id), 'A-6 用户 ID 为真实值', `页面=${kv['用户 ID']} 后端=${profile.id}`)
    check(kv['用户 ID'] !== '1001', 'A-6 用户 ID 不再是写死的 1001', `页面=${kv['用户 ID']}`)
    check(kv['注册时间'] === String(profile.createdAt ?? ''), 'A-6 注册时间来自 createdAt', `页面=${kv['注册时间']}`)
    check(/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(kv['注册时间'] ?? ''), 'A-6 注册时间格式正确', kv['注册时间'])
    check(!!kv['最近登录'] && kv['最近登录'] !== '—', 'A-6 最近登录来自 lastLoginAt', kv['最近登录'])
    check(kv['账号状态'] === '正常', 'A-6 账号状态由 status 映射', kv['账号状态'])

    // ---- 4. A-7：角色/状态标签取真实值 ----
    const headTags = await evaluate('window.__t.headTags()')
    check(headTags.includes('普通用户'), 'A-7 头像旁角色标签为真实 roleNames', headTags.join(' | '))
    check(!headTags.includes('超级管理员'), 'A-7 不再写死「超级管理员」', headTags.join(' | '))
    check(kv['角色'] === (profile.roleNames ?? []).join('、'), 'A-7 右栏角色与后端一致', `页面=${kv['角色']}`)
    check(kv['角色'] === headTags.find((t) => t !== '正常') || kv['角色'] === headTags[0], 'A-7 同一页两处角色一致', headTags.join(' | '))

    // ---- 5. A-5：手机号三段格式化（中间 4 位不能丢）----
    const chips = await evaluate('window.__t.tags()')
    const phoneChip = chips.find((t) => /^1\d/.test(t)) ?? ''
    check(/^1\d{2} \d{4} \d{4}$/.test(phoneChip), 'A-5 手机号 3-4-4 分段且 11 位完整', `页面=${phoneChip}`)
    check(phoneChip.replace(/\s/g, '') === PROBE.phone, 'A-5 分段后号码与后端一致', `页面=${phoneChip.replace(/\s/g, '')}`)

    // ---- 6. A-4：安全项按真实状态渲染（探针账号无邮箱 → 必须显示未绑定）----
    const safeItems = await evaluate('window.__t.safeItems()')
    const phoneItem = safeItems.find((t) => t.includes('手机号') || t.includes('已绑定 1')) ?? ''
    const emailItem = safeItems.find((t) => t.includes('邮箱')) ?? ''
    check(sec.email === null && emailItem.includes('未绑定邮箱'), 'A-4 未绑定邮箱时显示「未绑定邮箱」', `后端 email=${sec.email} 页面=${emailItem}`)
    check(phoneItem.includes(sec.phone ?? '###'), 'A-4 手机号用脱敏值展示', `后端=${sec.phone} 页面=${phoneItem}`)
    const safeText = await evaluate(`window.__t.norm(document.querySelector('.mini-head').parentElement.textContent)`)
    check(!safeText.includes('天前更新') || /\d+ 天前更新|今天刚更新|更新时间未知/.test(safeText), 'A-4 密码更新时间由时间戳派生', safeText.slice(0, 80))

    // ---- 7. A-1 + A-2：真选一个文件走 multipart 上传 ----
    const doc = await send('DOM.getDocument', { depth: -1 })
    const node = await send('DOM.querySelector', { nodeId: doc.root.nodeId, selector: 'input.avatar-file' })
    check(!!node.nodeId, 'A-2 存在隐藏的文件选择器', `nodeId=${node.nodeId}`)
    await send('DOM.setFileInputFiles', { nodeId: node.nodeId, files: [avatarFile] })
    const avatarEntry = await waitForApi((e) => e.url.includes('/api/user/avatar') && e.method === 'POST', { label: 'POST /user/avatar' })
    check(true, 'A-1/A-2 头像上传走通 multipart', assertBizOk(avatarEntry, 'POST /user/avatar'))
    check((headerOf(avatarEntry.reqHeaders, 'content-type') ?? '').includes('multipart/form-data'), 'A-1 请求确实是 multipart（不是 JSON "{}"）', headerOf(avatarEntry.reqHeaders, 'content-type'))
    check(typeof avatarEntry.postData !== 'string' || !avatarEntry.postData.trim().startsWith('{'), 'A-1 请求体不是被 JSON 化的 "{}"', `postData=${typeof avatarEntry.postData === 'string' ? avatarEntry.postData.slice(0, 40) : '(CDP 未提供，multipart 常为空)'}`)
    const imgSrc = await waitFor(`document.querySelector('.avatar-img')?.getAttribute('src') || ''`, { label: '头像回显', timeout: 10000 })
    check(!!imgSrc, 'A-2 上传后头像回显', String(imgSrc).slice(0, 60) + '…')

    // ---- 8. A-3：换绑发码（scene=bind，发往新号）与提交 ----
    await evaluate(`window.__t.clickText('更换', document.querySelector('.input-affix'))`)
    await waitFor(`!!window.__t.dialog()`, { label: '更换手机号弹窗' })
    const newPhone = `139${String(Date.now() % 100000000).padStart(8, '0')}`
    await evaluate(`(() => {
      const inputs = [...window.__t.dialog().querySelectorAll('input')]
      window.__t.setVal(inputs[0], ${JSON.stringify(newPhone)})
      return true
    })()`)
    await evaluate(`window.__t.clickText('获取验证码', window.__t.dialog())`)
    const codeEntry = await waitForApi((e) => e.url.includes('/api/auth/sms-code') && e.method === 'POST', { label: 'POST /auth/sms-code' })
    check(true, 'A-3 发码按钮已接线', assertBizOk(codeEntry, 'POST /auth/sms-code'))
    const sentBody = JSON.parse(codeEntry.postData ?? '{}')
    check(sentBody.scene === 'bind', 'A-3 scene=bind', `body=${codeEntry.postData}`)
    check(sentBody.phone === newPhone, 'A-3 验证码发往新手机号', `目标=${sentBody.phone}`)
    const cd = await evaluate(`window.__t.norm([...window.__t.dialog().querySelectorAll('button')].map(b=>b.textContent).join('|'))`)
    check(/后重发|已发送/.test(cd), 'A-3 发码后进入倒计时', cd)

    await evaluate(`(() => {
      const inputs = [...window.__t.dialog().querySelectorAll('input')]
      window.__t.setVal(inputs[1], '000000')
      return true
    })()`)
    await evaluate(`window.__t.clickText('确认更换', window.__t.dialog())`)
    const chgEntry = await waitForApi((e) => e.url.includes('/api/user/change-phone') && e.method === 'POST', { label: 'POST /user/change-phone' })
    check(true, 'A-3 提交换绑走真接口', assertBizOk(chgEntry, 'POST /user/change-phone'))
    await waitFor(`window.__t.tags().some(t => t === ${JSON.stringify(fmtPhone(newPhone))})`, { label: '换绑后号码刷新', timeout: 15000 })
    check(true, 'A-3 换绑后页面自动刷新为新号', fmtPhone(newPhone))

    // ---- 9. 无未捕获异常 ----
    const bad = consoleMsgs.filter((m) => /Failed to resolve component|Unhandled error|TypeError|is not a function/.test(m.text))
    check(bad.length === 0, '页面 console 无未捕获异常', bad.map((b) => b.text).join(' | ') || '—')

    // ---- 10. 证据截图 ----
    const shot = await send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(`${OUT_DIR}/profile.png`, Buffer.from(shot.data, 'base64'))
    const traceIds = ledger.filter((e) => headerOf(e.headers, 'x-trace-id')).length
    check(traceIds >= ledger.length - 1, '响应带 x-trace-id（证明确实打到真后端）', `${traceIds}/${ledger.length}`)
    console.log(`\n接口台账：\n${ledger.map((e) => `  ${e.method.padEnd(5)} ${e.url.replace(BASE, '')} → ${e.status}`).join('\n')}`)
    console.log(`截图：${OUT_DIR}/profile.png`)
  } finally {
    await sleep(200)
    try {
      ws?.close()
    } catch {
      /* ignore */
    }
    child.kill()
  }
}

main()
  .then(() => {
    const failed = steps.filter((s) => !s.ok).length
    console.log(`\n合计 ${steps.length} 步，通过 ${steps.length - failed}，失败 ${failed}`)
    process.exitCode = failed ? 1 : 0
  })
  .catch((e) => {
    console.error(`\n✗ 中断：${e.message}`)
    if (ledger.length) {
      console.error('  台账：')
      for (const e of ledger) {
        console.error(`    ${e.method} ${e.url.replace(BASE, '')} → ${e.status} · body=${e.body === null ? 'NULL' : `${String(e.body).length}B`} · promise=${e.bodyPromise ? 'yes' : 'no'} · err=${e.bodyError ?? '-'}`)
      }
    }
    const failed = steps.filter((s) => !s.ok).length
    if (steps.length) console.log(`\n已执行 ${steps.length} 步，通过 ${steps.length - failed}，失败 ${failed}`)
    process.exitCode = 1
  })
