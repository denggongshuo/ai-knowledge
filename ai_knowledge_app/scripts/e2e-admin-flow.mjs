#!/usr/bin/env node
/**
 * S2 页面级 E2E：用户管理 / 角色管理 / 授权管理 三页切真后端
 * （`S6-M09-C` 起加了第四页 **模型配置**）。
 *
 * <h3>为什么单独一个脚本</h3>
 * S2 的完成判据是「后端做完 + 与前端联调通过」（进度/README §4），页面级验收从 S1 起是硬要求。
 * 本脚本沿用 S1 的两个 E2E 脚本的技术路线（CDP 直驱无头 Edge、**零新增依赖**、
 * 断言看业务 code 不看 HTTP 码），覆盖 M02 的三页 + M09 的一页：
 * <ol>
 *   <li><b>用户管理</b> `/system/user` —— 部门树 + 用户列表来自真后端（128 人 / 分页 10 行 / admin 首行）；</li>
 *   <li><b>角色管理</b> `/system/role` —— 5 角色 + 权限树 total=38；</li>
 *   <li><b>授权管理</b> `/system/grant` —— 12×5 矩阵 + 8 库授权列表；并在矩阵上做一次
 *       「改档 → 保存 → 还原 → 保存」的**真实写入**，证明写侧接口与审计打点接通（G2-3/G2-4/G2-9）。</li>
 *   <li><b>模型配置</b> `/system/model`（`S6-M09-C`）—— 四条**反面**判据：本版本把
 *       「换模型 + 重建」收敛成「只保留切换」，故要证**界面上再也看不到那些入口 ·
 *       mock 的假影响量不上屏 · 四条「换 + 重建」接口不再被调用 · 全程无 404**。</li>
 * </ol>
 *
 * <h3>判据 vs 观察项</h3>
 * 页面上的统计卡（用户 128 / 角色 5）是前端写死值或派生值，登记为**观察项**不计入成败；
 * 判据只看「真请求 code=0 + 表格渲染出后端数据 + 响应带 x-trace-id（mock 不产出该头）」。
 *
 * <h3>前置</h3>
 * 后端 8080、前端 dev（`VITE_USE_MOCK=false`）已在跑。用法：
 * <pre>node scripts/e2e-admin-flow.mjs --base=http://localhost:5174</pre>
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'

const arg = (name, fallback) => (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://localhost:5174')
const API = arg('api', 'http://localhost:8080')

const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-e2e-admin'
const OUT_DIR = 'F:/work/code/ai-knowledge/temp/e2e-admin'
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9335)

/** 固定账号：种子超级管理员（数据范围 ALL，三页都有权限）。 */
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

// ---------------------------------------------------------------- 直连后端（仅准备登录）
async function api(path, { method = 'GET', body } = {}) {
  const res = await fetch(`${API}/api${path}`, {
    method,
    headers: { ...(body ? { 'Content-Type': 'application/json' } : {}) },
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
window.__t = {
  norm: (s) => (s || '').replace(/\\s+/g, ' ').trim(),
  /** 当前**可见**的 ElMessageBox（旧框在淡出期间仍留在 DOM，必须按可见性过滤，否则会点到上一个） */
  box: () => [...document.querySelectorAll('.el-message-box')].find((b) => b.offsetParent !== null) || null,
  rows: (sel) => [...document.querySelectorAll(sel)],
  text: (sel) => window.__t.norm(document.querySelector(sel)?.textContent),
  /** 点 scope 内文本命中的可点元素；scope 缺省为 document */
  clickText: (text, scope) => {
    const root = scope || document
    const all = [...root.querySelectorAll('button, .el-button, .el-tabs__item, .el-dropdown-menu__item')]
      .filter((x) => (scope ? scope.contains(x) : true) || x === scope)
    const b = all.find((x) => window.__t.norm(x.textContent).includes(window.__t.norm(text)))
    if (!b) throw new Error('找不到可点元素：' + text)
    b.click()
    return true
  },
  /** 矩阵里某功能项行的某个角色列单元格 */
  matrixCell: (funcLabel, roleIdx) => {
    const tr = [...document.querySelectorAll('.matrix-table tbody tr')]
      .find((r) => window.__t.norm(r.querySelector('.func-label')?.textContent) === window.__t.norm(funcLabel))
    if (!tr) throw new Error('找不到矩阵行：' + funcLabel)
    return tr.querySelectorAll('td.cell')[roleIdx]
  },
}
'ok'
`

async function navigate(hash) {
  await send('Page.navigate', { url: `${BASE}/?boot=${Date.now()}#${hash}` })
}

async function main() {
  if (!existsSync(EDGE)) throw new Error(`找不到 Edge：${EDGE}`)
  rmSync(PROFILE, { recursive: true, force: true })
  mkdirSync(OUT_DIR, { recursive: true })

  const login = await api('/auth/login', { method: 'POST', body: { account: PROBE.account, password: PROBE.password, remember: true } })
  if (login.json?.code !== 0) throw new Error(`admin 登录失败：code=${login.json?.code} · ${login.json?.message}`)
  const token = login.json.data.accessToken
  check(true, '准备：admin 登录，拿到 accessToken', `userId=${login.json.data.user?.id}`)

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

  // 先落地站点，才能把 token 写进本站 localStorage
  await send('Page.navigate', { url: `${BASE}/#/login` })
  await waitFor(`!!document.querySelector('#app') && !!location.hash`, { label: '应用挂载', timeout: 30000 })
  await evaluate(`localStorage.setItem('ak_token', ${JSON.stringify(token)}); 'ok'`)

  try {
    // ==================== 1. 用户管理 ====================
    await navigate('/system/user')
    await waitFor(`!!document.querySelector('.sys-user-page')`, { label: '用户管理页渲染' })
    await evaluate(PAGE_TOOLS)

    const usersEntry = await waitForApi((e) => e.url.includes('/api/users?') && e.method === 'GET', { label: 'GET /api/users' })
    check(true, '用户页调用 GET /api/users', assertBizOk(usersEntry, 'GET /api/users'))
    const deptEntry = await waitForApi((e) => e.url.includes('/api/depts/tree'), { label: 'GET /api/depts/tree' })
    check(true, '用户页调用 GET /api/depts/tree', assertBizOk(deptEntry, 'GET /api/depts/tree'))
    const rolesEntry1 = await waitForApi((e) => e.url.match(/\/api\/roles(\?|$)/), { label: 'GET /api/roles' })
    check(true, '用户页调用 GET /api/roles', assertBizOk(rolesEntry1, 'GET /api/roles'))

    const userData = JSON.parse(usersEntry.body ?? '{}').data ?? {}
    await waitFor(`window.__t.rows('.sys-user-page .el-table__body tbody tr').length === 10`, { label: '用户表渲染 10 行' })
    const userRows = await evaluate(`window.__t.rows('.sys-user-page .el-table__body tbody tr').length`)
    check(userRows === 10, '用户表渲染 10 行（后端分页 size=10）', `行数=${userRows}`)
    check(Number(userData.total) === 128, '用户总数来自后端（128）', `total=${userData.total}`)
    check((userData.list?.[0]?.username) === 'admin', '首行为 admin（后端按 id 升序）', `首行=${userData.list?.[0]?.username}`)
    const deptTree = JSON.parse(deptEntry.body ?? '{}').data ?? []
    const rd = deptTree.find((d) => d.name === '研发中心')
    check(rd?.userCount === 52, '部门树人数来自后端（研发中心 52）', `研发中心=${rd?.userCount}`)
    observe('统计卡为前端写死值（观察项，不计成败）', await evaluate(`window.__t.text('.sys-user-page .stat-card .sv')`))
    const shot1 = await send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(`${OUT_DIR}/user.png`, Buffer.from(shot1.data, 'base64'))

    // ==================== 2. 角色管理 ====================
    await navigate('/system/role')
    await waitFor(`!!document.querySelector('.sys-role-page')`, { label: '角色管理页渲染' })
    await evaluate(PAGE_TOOLS)
    const rolesEntry2 = await waitForApi((e) => e.url.match(/\/api\/roles(\?|$)/), { label: 'GET /api/roles' })
    check(true, '角色页调用 GET /api/roles', assertBizOk(rolesEntry2, 'GET /api/roles'))
    const permEntry = await waitForApi((e) => e.url.includes('/api/permissions/tree'), { label: 'GET /api/permissions/tree' })
    check(true, '角色页调用 GET /api/permissions/tree', assertBizOk(permEntry, 'GET /api/permissions/tree'))
    const roleData = JSON.parse(rolesEntry2.body ?? '{}').data ?? {}
    await waitFor(`window.__t.rows('.sys-role-page .el-table__body tbody tr').length === 5`, { label: '角色表渲染 5 行' })
    const roleRows = await evaluate(`window.__t.rows('.sys-role-page .el-table__body tbody tr').length`)
    check(roleRows === 5, '角色表渲染 5 行', `行数=${roleRows}`)
    check(roleData.total === 5, '角色列表 total=5（后端）', `total=${roleData.total}`)
    check(roleData.list?.[0]?.code === 'SUPER_ADMIN', '首行 SUPER_ADMIN', `首行=${roleData.list?.[0]?.code}`)
    const permTotal = JSON.parse(permEntry.body ?? '{}').data?.total
    check(permTotal === 38, '权限树 total=38（G2-2）', `total=${permTotal}`)
    const shot2 = await send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(`${OUT_DIR}/role.png`, Buffer.from(shot2.data, 'base64'))

    // ==================== 3. 授权管理 ====================
    await navigate('/system/grant')
    await waitFor(`!!document.querySelector('.sys-grant-page')`, { label: '授权管理页渲染' })
    await evaluate(PAGE_TOOLS)
    const matrixEntry = await waitForApi((e) => e.url.includes('/api/permissions/matrix') && e.method === 'GET', { label: 'GET /api/permissions/matrix' })
    check(true, '授权页调用 GET /api/permissions/matrix', assertBizOk(matrixEntry, 'GET /api/permissions/matrix'))
    const kbEntry = await waitForApi((e) => e.url.includes('/api/kb/grants'), { label: 'GET /api/kb/grants' })
    check(true, '授权页调用 GET /api/kb/grants', assertBizOk(kbEntry, 'GET /api/kb/grants'))
    const matrixData = JSON.parse(matrixEntry.body ?? '{}').data ?? {}
    check(matrixData.items?.length === 12, '矩阵 12 个功能项', `items=${matrixData.items?.length}`)
    check(matrixData.roles?.length === 5, '矩阵 5 个角色', `roles=${matrixData.roles?.length}`)
    await waitFor(`window.__t.rows('.matrix-table tbody tr:not(.group-row)').length === 12`, { label: '矩阵表渲染 12 行' })
    const matrixRows = await evaluate(`window.__t.rows('.matrix-table tbody tr:not(.group-row)').length`)
    check(matrixRows === 12, '矩阵表渲染 12 行（不含分组头）', `行数=${matrixRows}`)
    const kbTotal = JSON.parse(kbEntry.body ?? '{}').data?.total
    check(kbTotal === 8, '知识库授权列表 total=8', `total=${kbTotal}`)

    // ---- 真实写入：GUEST(sys:audit) NONE → MANAGE → 保存 → 还原 NONE ----
    const cellBefore = await evaluate(`window.__t.norm(window.__t.matrixCell('审计日志', 4).textContent)`)
    await evaluate(`window.__t.matrixCell('审计日志', 4).click(); 'ok'`)
    const cellAfter = await evaluate(`window.__t.norm(window.__t.matrixCell('审计日志', 4).textContent)`)
    check(cellBefore === '—' && cellAfter === '管理', '点击矩阵单元格循环切换（无 → 管理）', `${cellBefore} → ${cellAfter}`)
    check(await evaluate(`!!document.querySelector('.changed-tip')`), '出现「有 N 项未保存」提示条（G2-3）')

    await evaluate(`window.__t.clickText('保存授权', document.querySelector('.page-head'))`)
    await waitFor(`!!window.__t.box()`, { label: '保存确认框' })
    await evaluate(`window.__t.clickText('保存授权', window.__t.box())`)
    const putEntry = await waitForApi((e) => e.url.includes('/api/permissions/matrix') && e.method === 'PUT', { label: 'PUT /api/permissions/matrix' })
    check(true, '保存矩阵走通真写接口', assertBizOk(putEntry, 'PUT /api/permissions/matrix'))
    await waitFor(`!window.__t.box()`, { label: '确认框关闭' })
    const putBody = JSON.parse(putEntry.postData ?? '{}')
    check(putBody.changes?.[0]?.permKey === 'sys:audit', '请求字段是 permKey（非 permCode）', `body=${putEntry.postData}`)

    // 还原：MANAGE 再点 3 次回到 NONE
    for (let i = 0; i < 3; i++) await evaluate(`window.__t.matrixCell('审计日志', 4).click(); 'ok'`)
    const cellReverted = await evaluate(`window.__t.norm(window.__t.matrixCell('审计日志', 4).textContent)`)
    check(cellReverted === '—', '再次点击 3 次还原为「无」', cellReverted)
    await evaluate(`window.__t.clickText('保存授权', document.querySelector('.page-head'))`)
    await waitFor(`!!window.__t.box()`, { label: '还原确认框' })
    await evaluate(`window.__t.clickText('保存授权', window.__t.box())`)
    const putEntry2 = await waitForApi((e) => e.url.includes('/api/permissions/matrix') && e.method === 'PUT' && e !== putEntry, { label: 'PUT /api/permissions/matrix（还原）' })
    check(true, '还原保存走通（GUEST sys:audit 回到 NONE）', assertBizOk(putEntry2, 'PUT /api/permissions/matrix（还原）'))
    await waitFor(`!window.__t.box()`, { label: '确认框关闭（还原）' })

    // 超管列锁定：点 GUEST 之外的第 0 列（SUPER_ADMIN）不产生变更
    const beforeSuper = await evaluate(`window.__t.norm(window.__t.matrixCell('审计日志', 0).textContent)`)
    await evaluate(`window.__t.matrixCell('审计日志', 0).click(); 'ok'`)
    const afterSuper = await evaluate(`window.__t.norm(window.__t.matrixCell('审计日志', 0).textContent)`)
    check(beforeSuper === afterSuper, '超级管理员列锁定，点击不改变档位（G2-4）', `${beforeSuper} → ${afterSuper}`)

    const shot3 = await send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(`${OUT_DIR}/grant.png`, Buffer.from(shot3.data, 'base64'))

    // ==================== 4. 模型配置（/system/model）====================
    // `S6-M09-C` 把「换模型 + 重建」这条线收敛成「只保留切换」：换嵌入 / 换 VL 重建
    // 四条接口一律如实回「不支持」。页面级要证的因此是**四条反面的事** ——
    // 界面上再也看不到那些入口、假数不上屏、四条接口不再被调用、全程没有 404。
    // ⚠️ **先记 ledger 水位线**：`ledger` 是**跨页累积**的，不切片就会把前面几页的 404
    // 算到本页头上。实测踩到：`/api/notices/unread-count` 在每一页都 404（`M11` 通知中心
    // 未开工，是**已登记**的缺口），于是"模型配置页无 404"这条**误报**了 4 次。
    // 本仓「误报比漏报更伤」——一条会误报的判据等于没有判据。
    const ledgerMark = ledger.length

    await navigate('/system/model')
    await waitFor(`!!document.querySelector('.sys-model-page')`, { label: '模型配置页渲染' })
    await evaluate(PAGE_TOOLS)
    const cfgEntry = await waitForApi((e) => e.url.includes('/api/system/model/config') && e.method === 'GET', { label: 'GET /api/system/model/config' })
    check(true, '模型配置页调用 GET /api/system/model/config', assertBizOk(cfgEntry, 'GET /api/system/model/config'))

    const pageText = await evaluate(`window.__t.norm(document.body.innerText)`)
    // 🔴 「不支持」必须**写在页面上**，不能只写在后端的错误码里 —— 用户看不到错误码
    check(pageText.includes('本版本不支持'), '页面明写「本版本不支持」', '')
    // 🔴 mock 里那五个数（`src/mock/index.ts:1429`）一个都不许上屏。
    //    它们此前**在真后端下也会渲染**（`embedImpact` 是个 mock 初值对象）
    const mockNumbers = ['38,502', '38502', '1,246', '1246', '1180', '42 分钟']
    const leaked = mockNumbers.filter((n) => pageText.includes(n))
    check(leaked.length === 0, '页面不出现 mock 的假影响量（8 库 / 1246 篇 / 38502 片 / 42 分钟 / 1180 万）', leaked.join(' | ') || '—')
    // 🔴 被删掉的入口不许回来（负向锚：任何一个回来都会让这条转红）
    check(!pageText.includes('切换并重建'), 'VL 不再提供「切换并重建含图文档」入口', '')
    check(!pageText.includes('模拟「检测到配置变更」'), '嵌入不再提供「模拟检测到配置变更」演示入口', '')
    check(!pageText.includes('确认重建全部索引'), '嵌入不再提供「确认重建全部索引」按钮', '')
    // 🔴 本页浏览下来：没有 404，也没去调那四条「换 + 重建」接口
    // ⚠️ 只切片看**本页**的请求（见上面 ledgerMark 的注释）
    const pageLedger = ledger.slice(ledgerMark)
    const all404 = pageLedger.filter((e) => e.url.includes('/api/') && e.status === 404)

    // 🔴 **本页自己的接口**一个 404 都不许有 —— 这是本批的实质判据
    const own404 = all404.filter((e) => e.url.includes('/api/system/'))
    check(own404.length === 0, '模型配置页**自己的**接口无 404（`/api/system/*`）',
      own404.map((e) => `${e.method} ${e.url.replace(BASE, '')}`).join(' | ') || '—')

    // 🔴 `/notices/*` 这一族**曾经是豁免的**：顶栏那个未读数轮询在**每一页**都 404
    //    （`M11` 通知中心未开工），当时登记为**观察项**而不是判据 ——
    //    因为一条总在红的判据等于没有判据。
    //    `2026-09-19-S6-M11` 批交付了通知中心，**这个缺口关了** ⇒ 现在它也是判据的一部分：
    //    哪一天 `/notices/*` 又 404，本页会当场红，而不是被"已知缺口"四个字盖过去。
    //    （这就是「把豁免收回来」——留着豁免比当初设豁免更危险。）
    const known404 = all404.filter((e) => e.url.includes('/notices/'))
    const other404 = all404.filter((e) => !e.url.includes('/notices/'))
    observe('模型配置页 404 分布', `本页自己 ${own404.length} · /notices/ ${known404.length} · 其它 ${other404.length}`)
    check(all404.length === 0, '模型配置页**一个 404 都没有**（含曾豁免的 /notices/）',
      all404.map((e) => `${e.method} ${e.url.replace(BASE, '')}`).join(' | ') || '—')

    const refusedCalls = pageLedger.filter((e) => /embedding-impact|change-embedding|vl-impact|rebuild-image-docs/.test(e.url))
    check(refusedCalls.length === 0, '页面不再调用「换 + 重建」四条接口', refusedCalls.map((e) => e.url.replace(BASE, '')).join(' | ') || '—')

    const shot4 = await send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(`${OUT_DIR}/model.png`, Buffer.from(shot4.data, 'base64'))

    // ==================== 5. console 与溯源 ====================
    const bad = consoleMsgs.filter((m) => /Failed to resolve component|Unhandled error|TypeError|is not a function/.test(m.text))
    check(bad.length === 0, '四页 console 无未捕获异常', bad.map((b) => b.text).join(' | ') || '—')
    // mock 不产出 x-trace-id，故关键读接口都带它 = 确实打到了真后端
    const keyEntries = [usersEntry, deptEntry, rolesEntry2, permEntry, matrixEntry, kbEntry]
    const missingTrace = keyEntries.filter((e) => !headerOf(e.headers, 'x-trace-id'))
    check(missingTrace.length === 0, '关键 /api 响应均带 x-trace-id（证明确为真后端）', `${keyEntries.length - missingTrace.length}/${keyEntries.length}`)

    console.log(`\n接口台账：\n${ledger.map((e) => `  ${e.method.padEnd(6)} ${e.url.replace(BASE, '')} → ${e.status}`).join('\n')}`)
    console.log(`截图：${OUT_DIR}/{user,role,grant,model}.png`)
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
    // 显式退出：WebSocket / 子进程句柄可能让事件循环不空转，靠 exitCode 会挂住
    process.exit(failed ? 1 : 0)
  })
  .catch((e) => {
    console.error(`\n✗ 中断：${e.message}`)
    const failed = steps.filter((s) => !s.ok).length
    if (steps.length) console.log(`\n已执行 ${steps.length} 步，通过 ${steps.length - failed}，失败 ${failed}`)
    process.exit(1)
  })
