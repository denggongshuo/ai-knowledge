#!/usr/bin/env node
/**
 * S3 · C2 阶段页面级 E2E：审计采集范围补打点（判据 13 / T-3C3）。
 *
 * <h3>覆盖的判据（对应 `2026-09-15-S3-M03-C2.md` §6+7 第 13 条）</h3>
 * <ul>
 *   <li><b>判据 13</b> —— 在**用户管理页真操作**（点「新建用户」弹窗、填表、选部门、
 *       提交），再到审计页筛「用户」模块：看到刚建的那条「新建用户」行、带 🚩 高危标签，
 *       点开抽屉看到「账号 / 部门 / 角色」三行变更明细。跑完探针账号自清。</li>
 * </ul>
 *
 * <h3>为什么这页要"真点"而不是直连 API 造数据</h3>
 * 本批的使命是「§4.2 采集范围的操作都能查到」，判据 13 的落点是**从用户管理页到审计页的
 * 全链路**：用户管理页把「新建用户」这个动作发出去（真 UI 事件、真表单校验、真部门树选择），
 * 审计页把「这条动作」捞回来。若直接 POST /api/users，就等于把"页面那一半"留给假绿。
 *
 * <h3>技术路线</h3>
 * 与 S1/S2/S3-B/S3-C 的 E2E 脚本一致：Node 自带 WebSocket 直驱无头 Edge 的 CDP，
 * 零新增依赖；断言看业务 `code` 不看 HTTP 码；抓 `x-trace-id` 证明是真后端。
 *
 * <h3>自清纪律</h3>
 * 探针账号在 `finally` 里直连 DB 删除（先 `sys_user_role` 后 `sys_user`），
 * 且开头按用户名前缀幂等清一次上次的残留 —— 不让"上一跑的垃圾"变成"这一跑的噪声"。
 * 审计行本身**不删**：§4.8 规定审计日志不可删除，测试也不该例外。
 *
 * <h3>前置</h3>
 * <pre>
 *   后端 8080：mvn -o spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
 *   前端 5174：VITE_USE_MOCK=false npm run dev -- --host 127.0.0.1 --port 5174
 *   运行：node scripts/e2e-audit-backfill.mjs --base=http://127.0.0.1:5174
 * </pre>
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
// 探针自清 —— **登记表 + 覆盖率闸 + 残留自检**。S4-D2 起清理逻辑只此一份。
import { assertArtifactCoverage, mysqlScalar, teardownUserProbe } from './lib/probe-teardown.mjs'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const REPO_ROOT = resolve(ROOT, '..')
const arg = (name, fallback) =>
  (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://127.0.0.1:5174')
const API = arg('api', 'http://127.0.0.1:8080')

const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-e2e-audit-backfill-' + Date.now()
const OUT_DIR = 'F:/work/code/ai-knowledge/temp/e2e-audit-backfill'
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9340)

const ADMIN = { account: 'admin', password: 'Admin@123' }

/** 探针账号命名前缀（用户名全局唯一 + 开头自清 + 结束自清，三处共用同一前缀）。 */
const PROBE_PREFIX = 'e2e_a1_'
const stamp = () => String(Date.now()).slice(-10)

const steps = []
function check(ok, label, detail = '') {
  steps.push({ ok: !!ok, label, detail })
  console.log(`${ok ? '✓' : '✗'} ${label}${detail ? ` · ${detail}` : ''}`)
  return !!ok
}

// ---------------------------------------------------------------- 数据库直连（仅计数 / 读列）
// `mysqlScalar` 与清理都来自 `./lib/probe-teardown.mjs`（S4-D2 起只此一份）。
//
// ⚠️ 本脚本原先的 `purgeProbeUsers` **只删 `sys_user_role` + `sys_user`**，
// **漏了 `sys_login_log`** —— 而该表有 `username` 列，本可按前缀删。
// 实测后果：`sys_login_log` 累积出 **3984 行孤儿**（`user_id` 指向已删用户，
// 既 join 不回用户、又永远没人清）。这正是「新增持久化产物、清理函数不认识它」的第四例，
// 所以本批顺手把它一起收敛掉。

function userCount() {
  return Number(mysqlScalar('select count(*) from sys_user where deleted_at is null;'))
}

// ---------------------------------------------------------------- 直连后端（准备 / 自清）
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
    /* 保留 text */
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
  if (r.exceptionDetails) {
    throw new Error(`页面脚本抛错：${r.exceptionDetails.text ?? ''} ${r.exceptionDetails.exception?.description ?? ''}`)
  }
  return r.result?.value
}
async function waitFor(expression, { timeout = 15000, interval = 150, label = expression } = {}) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    try {
      const v = await evaluate(expression)
      if (v) return v
    } catch {
      /* 导航中 */
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
  throw new Error(
    `未观察到 ${label} 的响应（${timeout}ms）；已记录：\n${ledger.map((e) => `  ${e.method} ${e.url} → ${e.status}`).join('\n')}`,
  )
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
window.__t = {
  norm: (s) => (s || '').replace(/\\s+/g, ' ').trim(),
  text: (sel) => window.__t.norm(document.querySelector(sel)?.textContent),
  rows: () => [...document.querySelectorAll('.el-table__body .el-table__row')],
  rowTexts: () => window.__t.rows().map((r) => window.__t.norm(r.textContent)),
  rowIndexOf: (text) => window.__t.rowTexts().findIndex((t) => t.includes(text)),
  openDetailAt: (i) => {
    const r = window.__t.rows()[i]
    if (!r) throw new Error('没有第 ' + i + ' 行')
    const btn = [...r.querySelectorAll('button')].find((b) => window.__t.norm(b.textContent).includes('查看'))
    if (!btn) throw new Error('该行没有「查看」按钮')
    btn.click()
    return true
  },
  diffRows: () => [...document.querySelectorAll('.drawer-body .diff-table .diff-row')].map((r) => window.__t.norm(r.textContent)),
  techValue: (key) => {
    const dts = [...document.querySelectorAll('.drawer-body .tech-list dt')]
    const dt = dts.find((d) => window.__t.norm(d.textContent) === key)
    return dt ? window.__t.norm(dt.nextElementSibling?.textContent) : null
  },
  openModuleSelect: () => {
    // ⚠️ #160 顺一遍查过这里，**结论是"不用改"** —— 与 e2e-audit-flow 同因同结论，
    //    详见那里的长注释（两个 select 都可见、且开错了下一步会当场转红；
    //    试过"按文案选"被探针否掉：el-select 显示的是**选中的值**不是占位符）。
    const wraps = [...document.querySelectorAll('.head-right .el-select')]
    if (!wraps.length) throw new Error('页头没有 el-select')
    wraps[0].querySelector('.el-select__wrapper')?.click() || wraps[0].click()
    return true
  },
  selectOptions: () => [...document.querySelectorAll('.el-select-dropdown:not([style*="display: none"]) .el-select-dropdown__item')].map((o) => window.__t.norm(o.textContent)),
  pickOption: (text) => {
    const pool = [...document.querySelectorAll('.el-select-dropdown__item')].filter((o) => o.closest('.el-select-dropdown')?.offsetParent !== null)
    const opt = pool.find((o) => window.__t.norm(o.textContent) === window.__t.norm(text))
    if (!opt) throw new Error('下拉里找不到：' + text + '（现有：' + pool.map((o) => window.__t.norm(o.textContent)).join('/') + '）')
    opt.click()
    return true
  },
  drawerOpen: () => !!document.querySelector('.drawer-body .diff-table, .drawer-body .tech-list'),
}
'ok'
`

/** 原生 setter 填输入框（Vue 只认这个；直接改 .value 不触发响应式）。 */
async function setInput(placeholder, value, { scope = 'document' } = {}) {
  return evaluate(`(() => {
    const pool = ${scope}.querySelectorAll('input')
    const el = [...pool].find((i) => i.placeholder === ${JSON.stringify(placeholder)})
    if (!el) throw new Error('找不到输入框（placeholder=' + ${JSON.stringify(placeholder)} + '），现有：' + [...pool].map((i) => i.placeholder).join('/'))
    const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set
    setter.call(el, ${JSON.stringify(value)})
    el.dispatchEvent(new Event('input', { bubbles: true }))
    el.dispatchEvent(new Event('change', { bubbles: true }))
    return el.value
  })()`)
}

/** 点开弹窗里的「所属部门」树选择，选第一个真实部门（跳过「全部用户」根节点）。 */
async function pickFirstDept() {
  await evaluate(`(() => {
    const items = [...document.querySelectorAll('.el-form-item')]
    const item = items.find((f) => window.__t.norm(f.querySelector('.el-form-item__label')?.textContent) === '所属部门')
    const w = item?.querySelector('.el-select__wrapper')
    if (!w) throw new Error('找不到「所属部门」选择器')
    w.click()
    return true
  })()`)
  await waitFor(`document.querySelectorAll('.el-select__popper .el-tree-node__content').length > 0`, { label: '部门树弹层展开' })
  await evaluate(`(() => {
    const icon = document.querySelector('.el-select__popper .el-tree-node__expand-icon')
    if (icon && !icon.classList.contains('is-leaf')) icon.click()
    return true
  })()`)
  await sleep(300)
  return evaluate(`(() => {
    const nodes = [...document.querySelectorAll('.el-select__popper .el-tree-node__content')]
    const n = nodes.find((x) => { const t = window.__t.norm(x.textContent); return t && t !== '全部用户' && t !== '未分配' })
    if (!n) throw new Error('部门树没有可选节点：' + nodes.map((x) => window.__t.norm(x.textContent)).join('/'))
    const label = window.__t.norm(n.textContent)
    n.click()
    return label
  })()`)
}

async function navigate(hash) {
  await send('Page.navigate', { url: `${BASE}/?boot=${Date.now()}#${hash}` })
}

async function main() {
  let usersBefore = 0
  let child = null
  if (!existsSync(EDGE)) throw new Error(`找不到 Edge：${EDGE}`)
  mkdirSync(OUT_DIR, { recursive: true })

  try {
  // ==================== 0. 准备数据（直连 API + DB 自清） ====================
  // ⚠️ 覆盖率闸必须在建探针之前跑（放在 finally 里会导致「闸抛错 → 清理反而不执行」）
  const cov = assertArtifactCoverage()
  check(true, '准备：产物覆盖率闸通过',
    `扫描 ${cov.scanned} 张带归属列的表 · 已登记 ${cov.registered} 张 · 显式豁免 ${cov.exempt} 张`)

  teardownUserProbe(PROBE_PREFIX)
  usersBefore = userCount()
  check(true, '准备：探针账号前缀幂等清空（含登录日志）', `sys_user 当前 ${usersBefore} 行`)

  const login = await api('/auth/login', {
    method: 'POST',
    body: { ...ADMIN, remember: true },
  })
  if (login.json?.code !== 0) throw new Error(`admin 登录失败：code=${login.json?.code} · ${login.json?.message}`)
  const token = login.json.data.accessToken
  check(true, '准备：admin 登录，拿到 accessToken', `userId=${login.json.data.user?.id}`)

  const probeName = 'E2E审计探针' + stamp()
  const probeUsername = PROBE_PREFIX + stamp()
  const probePhone = '139' + String(Date.now()).slice(-8)

  // ==================== 1. 起浏览器 ====================
  child = spawn(
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
      if (msg.error) p.reject(new Error(`${msg.error.message}`))
      else p.resolve(msg.result)
      return
    }
    if (msg.method === 'Network.requestWillBeSent') {
      const { requestId, request } = msg.params
      if (!request.url.startsWith(`${BASE}/api/`)) return
      byRequestId.set(requestId, { method: request.method, postData: request.postData, reqHeaders: request.headers })
    }
    if (msg.method === 'Network.responseReceived') {
      const { requestId, response } = msg.params
      if (!response.url.startsWith(`${BASE}/api/`)) return
      const prev = byRequestId.get(requestId) ?? {}
      const entry = { requestId, url: response.url, status: response.status, method: prev.method ?? 'GET', postData: prev.postData, reqHeaders: prev.reqHeaders, headers: response.headers, body: null, bodyPromise: null }
      ledger.push(entry)
      byRequestId.set(requestId, entry)
    }
    if (msg.method === 'Network.loadingFinished') {
      const entry = byRequestId.get(msg.params.requestId)
      if (!entry || !entry.url) return
      entry.bodyPromise = send('Network.getResponseBody', { requestId: msg.params.requestId })
        .then((r) => {
          entry.body = r.base64Encoded ? Buffer.from(r.body, 'base64').toString('utf8') : r.body
          return entry.body
        })
        .catch((e) => {
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
  await send('Emulation.setDeviceMetricsOverride', { width: 1600, height: 1000, deviceScaleFactor: 1, mobile: false })

  await send('Page.navigate', { url: `${BASE}/#/login` })
  await waitFor(`!!document.querySelector('#app') && !!location.hash`, { label: '应用挂载', timeout: 30000 })
  await evaluate(`localStorage.setItem('ak_token', ${JSON.stringify(token)}); 'ok'`)

  // ==================== 2. 用户管理页 · 真操作建账号 ====================
  await navigate('/system/user')
  await waitFor(`[...document.querySelectorAll('button')].some((b) => b.textContent.trim() === '新建用户')`, { label: '用户管理页渲染' })
  await evaluate(PAGE_TOOLS)

  await evaluate(`(() => { const b = [...document.querySelectorAll('button')].find((x) => window.__t.norm(x.textContent) === '新建用户'); if (!b) throw new Error('找不到「新建用户」按钮'); b.click(); return true })()`)
  await waitFor(`[...document.querySelectorAll('input')].some((i) => i.placeholder === '真实姓名，2-20 字')`, { label: '新建用户弹窗打开' })

  await setInput('真实姓名，2-20 字', probeName, { scope: 'document' })
  await setInput('登录账号，全局唯一', probeUsername, { scope: 'document' })
  await setInput('用于接收验证码', probePhone, { scope: 'document' })
  check(true, '弹窗：姓名 / 用户名 / 手机号已填', `${probeName} · ${probeUsername}`)

  const deptLabel = await pickFirstDept()
  await waitFor(
    `(() => { const f = [...document.querySelectorAll('.el-dialog .el-form-item')].find((x) => window.__t.norm(x.querySelector('.el-form-item__label')?.textContent) === '所属部门'); const t = window.__t.norm(f?.querySelector('.el-select__wrapper')?.textContent); return !!t && t !== '未分配' && !t.includes('选择部门') })()`,
    { label: '所属部门已选中' },
  )
  const deptShown = await evaluate(
    `(() => { const f = [...document.querySelectorAll('.el-dialog .el-form-item')].find((x) => window.__t.norm(x.querySelector('.el-form-item__label')?.textContent) === '所属部门'); return window.__t.norm(f?.querySelector('.el-select__wrapper')?.textContent) })()`,
  )
  check(!!deptShown && deptShown !== '未分配', '弹窗：所属部门已选中（树选择生效）', `显示=${deptShown} · 树节点=${deptLabel}`)
  const roleChecked = await evaluate(`[...document.querySelectorAll('.role-check-group .el-checkbox-button.is-checked')].length`)
  check(roleChecked >= 1, '弹窗：角色默认已勾选（openCreate 默认 roleIds=[USER]）', `${roleChecked} 个`)

  await evaluate(`(() => {
    const b = [...document.querySelectorAll('.el-dialog button')].find((x) => window.__t.norm(x.textContent).includes('创建并发送邀请'))
    if (!b) throw new Error('找不到「创建并发送邀请」')
    b.click()
    return true
  })()`)
  const createEntry = await waitForApi(
    (e) => e.url.includes('/api/users') && e.method === 'POST' && !e.url.includes('import'),
    { label: 'POST /api/users（真 UI 事件触发）' },
  )
  check(true, '判据 13a：提交按钮真发出 POST /api/users', assertBizOk(createEntry, 'POST /api/users'))
  const created = JSON.parse(createEntry.body ?? '{}').data ?? {}
  const dbUsername = mysqlScalar(`SELECT username FROM sys_user WHERE id = ${created.id}`)
  check(
    created.id > 0 && created.status === 'PENDING' && dbUsername === probeUsername,
    '判据 13a：创建的正是探针账号（返回体 id + DB 反查 username）',
    `id=${created.id} · status=${created.status} · db=${dbUsername}`,
  )

  // 页面级反证：搜索框真按探针用户名筛，用户列表出现该行（证明不是"接口通了但页面没反应"）
  await setInput('姓名 / 用户名 / 手机', probeUsername, { scope: 'document' })
  await waitForApi(
    (e) => e.url.includes('/api/users') && e.url.includes('keyword=') && e.method === 'GET',
    { label: 'GET /api/users?keyword=探针用户名' },
  )
  await waitFor(`window.__t.rowIndexOf(${JSON.stringify(probeName)}) >= 0`, { label: '用户列表出现探针行' })
  check(true, '判据 13a：用户管理页列表能筛出刚建的探针账号', `keyword=${probeUsername}`)
  const shot1 = await send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(`${OUT_DIR}/user-created.png`, Buffer.from(shot1.data, 'base64'))

  // ==================== 3. 审计页 · 筛「用户」模块查到这条 ====================
  await navigate('/system/audit')
  await waitFor(`!!document.querySelector('.sys-audit-page')`, { label: '审计页渲染' })
  await evaluate(PAGE_TOOLS)

  const listEntry = await waitForApi((e) => e.url.includes('/api/audit/logs') && e.method === 'GET', { label: 'GET /api/audit/logs' })
  check(true, '审计页调用 GET /api/audit/logs', assertBizOk(listEntry, '审计列表'))

  await evaluate(`window.__t.openModuleSelect()`)
  await waitFor(`window.__t.selectOptions().length > 0`, { label: '模块下拉展开' })
  const options = await evaluate(`window.__t.selectOptions()`)
  check(
    options.includes('用户') && options.includes('授权') && options.includes('系统'),
    '模块下拉含本批三个模块（用户 / 授权 / 系统）—— 缺一个就是「写得进、筛不出」',
    options.join('/'),
  )
  await evaluate(`window.__t.pickOption('用户')`)
  const userFiltered = await waitForApi(
    (e) => e.url.includes('/api/audit/logs') && e.url.includes('module=') && e !== listEntry,
    { label: 'GET /api/audit/logs?module=用户' },
  )
  check(true, '筛选「用户」模块真重发请求（带 module 参数）', assertBizOk(userFiltered, 'module=用户'))

  await waitFor(`window.__t.rowIndexOf(${JSON.stringify(probeName)}) >= 0`, { label: '筛出探针的「新建用户」行' })
  const probeIdx = await evaluate(`window.__t.rowIndexOf(${JSON.stringify(probeName)})`)
  const probeRowText = (await evaluate(`window.__t.rowTexts()`))[probeIdx] ?? ''
  check(
    probeRowText.includes('新建用户') && probeRowText.includes('高危'),
    '判据 13b：审计页该行 = 「新建用户」+ 🚩 高危标签',
    probeRowText.slice(0, 90),
  )
  check(
    !probeRowText.includes('审计取证'),
    '判据 13b 反向：命中的是**本次**探针（不是今天早些时候后端用例留下的痕迹）',
    probeName,
  )

  await evaluate(`window.__t.openDetailAt(${probeIdx})`)
  await waitFor(`window.__t.drawerOpen()`, { label: '详情抽屉打开' })
  const detailEntry = await waitForApi((e) => /\/api\/audit\/logs\/\d+/.test(e.url), { label: 'GET /api/audit/logs/{id}' })
  const d = JSON.parse(detailEntry.body ?? '{}').data ?? {}
  check(
    d.module === '用户' && d.action === '新建用户' && d.riskLevel === 'HIGH',
    '判据 13b：详情三元组 = 用户 / 新建用户 / HIGH',
    `${d.module} · ${d.action} · ${d.riskLevel} · operator=${d.operator}`,
  )

  const diffRows = await evaluate(`window.__t.diffRows()`)
  check(
    diffRows.length === (d.changes?.length ?? 0) && diffRows.length > 0,
    '判据 13b：变更明细逐行渲染（DOM 行数 = API changes.length）',
    `DOM=${diffRows.length} · API=${d.changes?.length ?? 0}`,
  )
  for (const label of ['账号', '部门', '角色']) {
    check(
      diffRows.some((r) => r.includes(label)),
      `判据 13b：明细含「${label}」行`,
      diffRows.find((r) => r.includes(label)) ?? diffRows.join(' | ').slice(0, 80),
    )
  }
  check(
    diffRows.some((r) => r.includes(probeUsername)),
    '判据 13b：「账号」行的值就是探针用户名（明细不是空壳）',
    probeUsername,
  )
  const affectedText = await evaluate(`window.__t.techValue('影响用户')`)
  check(
    affectedText === '1 人' && Number(d.affectedUsers) === 1,
    '判据 13b：技术信息「影响用户 = 1 人」（affected_users 落库并被页面消费）',
    `页面=${affectedText} · API=${d.affectedUsers}`,
  )
  const shot2 = await send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(`${OUT_DIR}/audit-new-user.png`, Buffer.from(shot2.data, 'base64'))

  // ==================== 4. console 与溯源 ====================
  // 口径与 e2e-audit-flow.mjs 一致：只判未捕获异常/组件解析失败，不把资源加载 404 当失败。
  // 本页会顺带请求 layout 的通知未读数（GET /api/notices/unread-count 目前 404，
  // 属既有接口缺口，与 T-3C3 补打点无关，记在计划书 §9 遗留）。
  const bad = consoleMsgs.filter(
    (m) => /Failed to resolve component|Unhandled error|TypeError|is not a function/.test(m.text),
  )
  check(bad.length === 0, '本页链路 console 无未捕获异常（资源 404 噪音不计）', bad.map((b) => b.text).join(' | ') || '—')
  const keyEntries = [createEntry, listEntry, userFiltered, detailEntry].filter(Boolean)
  const missingTrace = keyEntries.filter((e) => !headerOf(e.headers, 'x-trace-id'))
  check(
    missingTrace.length === 0,
    '关键 /api 响应均带 x-trace-id（证明确为真后端，mock 不产出该头）',
    `${keyEntries.length - missingTrace.length}/${keyEntries.length}`,
  )

  console.log(`\n接口台账：\n${ledger.map((e) => `  ${e.method.padEnd(6)} ${e.url.replace(BASE, '')} → ${e.status}`).join('\n')}`)
  console.log(`截图：${OUT_DIR}/{user-created,audit-new-user}.png`)

  child.kill()
  } finally {
    try { child?.kill() } catch { /* exited */ }
    try {
      ws?.close()
    } catch {
      /* ignore */
    }
    // ---- 自清：探针账号删除（登录日志 → 关联 → 主表；审计行不删，见脚本头注）----
    try {
      const r = teardownUserProbe(PROBE_PREFIX)
      const after = userCount()
      check(
        after === usersBefore && r.residue.sqlTotal === 0,
        '收尾：探针账号已自清（含**登录日志**），sys_user 回到基线',
        `${usersBefore} → ${after} · 残留 ${JSON.stringify(r.residue.sql)} · 本次删登录日志 ${r.loginLogs} 行`,
      )
    } catch (e) {
      check(false, '收尾：自清失败，需人工检查', String(e.message))
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
    try {
      teardownUserProbe(PROBE_PREFIX)
    } catch {
      /* 已在 finally 里报过 */
    }
    process.exit(1)
  })
