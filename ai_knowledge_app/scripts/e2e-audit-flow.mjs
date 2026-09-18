#!/usr/bin/env node
/**
 * S3 · C 阶段页面级 E2E：审计查询侧接真后端（列表 / 筛选 / 详情 / 统计 / 导出）。
 *
 * <h3>覆盖的判据（对应 `2026-09-15-S3-M03-C.md` §6+7 第 19~22 条）</h3>
 * <ul>
 *   <li><b>判据 19</b> —— 变更明细真实渲染 + 「共 N 项，其中 M 项发生变更」计数与 API 逐数一致
 *       （A 批挂起的消费侧判据 1/3，本批销账）；</li>
 *   <li><b>判据 20</b> —— 「影响用户 N 人（X 下全部成员）」的 N 与 X 都来自真后端
 *       （A 批挂起 2/3；W1 修复的页面级回归 —— 修错了这里会显示成操作人角色码）；</li>
 *   <li><b>判据 21</b> —— 日期默认「今日」**且真发出** startTime/endTime（W3）；
 *       统计卡四数与 `GET /audit/stats` 一致（W5：不再是 mock 的 247/12/34/3）；</li>
 *   <li><b>判据 22</b> —— B2 留的「保存配置」日志在 `/audit/logs` 可查且详情有 changes
 *       （`G3-7` 三批合并回填的那一条）。</li>
 * </ul>
 *
 * <h3>技术路线</h3>
 * 与 S1/S2/S3-B 的四个 E2E 脚本一致：Node 自带 WebSocket 直驱无头 Edge 的 CDP，
 * **零新增依赖**；断言看业务 `code` 不看 HTTP 码；抓 `x-trace-id` 证明是真后端
 * （mock 不产出该头）。
 *
 * <h3>为什么准备数据走直连 API 而不是点页面</h3>
 * 本脚本要验的是**审计页**。若为了造一条"有变更明细的日志"去点功能矩阵页，
 * 脚本就与那个页面的 DOM 耦合了 —— 那边改个按钮文案，本脚本红，
 * 而红的理由与被测页面（审计页）毫无关系。所以：造数据走 HTTP，验收走页面。
 *
 * <h3>自清纪律</h3>
 * 准备阶段会**真改**一次功能矩阵与一个探针库，跑完必须还原/删除 ——
 * 否则下次跑「矩阵档位」「sys_kb 8 行」的用例会看到漂移的数据。
 * 审计行本身**不删**：§4.8 规定审计日志不可删除，测试也不该例外
 * （它是业务副产品，不计入种子核对，见 C 批计划 §6 的种子核对说明）。
 *
 * <h3>前置</h3>
 * <pre>
 *   后端 8080：mvn -o spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
 *   前端 5174：VITE_USE_MOCK=false npm run dev -- --host 127.0.0.1 --port 5174
 *   运行：node scripts/e2e-audit-flow.mjs --base=http://127.0.0.1:5174
 * </pre>
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
// 探针自清 —— **登记表 + 覆盖率闸 + 残留自检**。S4-D2 起清理逻辑只此一份。
import { assertArtifactCoverage, describeKbResidue, kbResidueClean, mysqlScalar, teardownKbProbe } from './lib/probe-teardown.mjs'

/**
 * 本脚本触发的那次审计导出任务的 taskId（`S5` 测试可靠性批加）。
 *
 * <p>⚠️ 它必须由脚本**自己**点名删：导出任务行 `kb_id` 为 NULL，
 * 而库族的自清是按 `kb_id` 走的 ⇒ **看不见它**。实测不删的代价是每跑一轮留一行。
 */
let exportTaskId = null

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const REPO_ROOT = resolve(ROOT, '..')
const arg = (name, fallback) =>
  (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://127.0.0.1:5174')
const API = arg('api', 'http://127.0.0.1:8080')

const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-e2e-audit'
const OUT_DIR = 'F:/work/code/ai-knowledge/temp/e2e-audit'
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9338)

const ADMIN = { account: 'admin', password: 'Admin@123' }

/** 探针库（造一条「保存配置」的审计日志用；跑完删掉）。 */
const PROBE_CODE = 'kb_e2e_auditprobe'
const PROBE_NAME = 'C 批审计探针库'

/** 矩阵探针：改 USER 角色的 sys:audit 档位再还原（该角色有 109 名成员 → 影响人数非 0）。 */
const MATRIX_ROLE_ID = 3
const MATRIX_ITEM = 'sys:audit'

const steps = []
function check(ok, label, detail = '') {
  steps.push({ ok: !!ok, label, detail })
  console.log(`${ok ? '✓' : '✗'} ${label}${detail ? ` · ${detail}` : ''}`)
  return !!ok
}
function observe(label, detail = '') {
  console.log(`… ${label}${detail ? ` · ${detail}` : ''}`)
}

// ---------------------------------------------------------------- 数据库直连（仅计数 / 读列）
// `mysqlScalar` 与清理都来自 `./lib/probe-teardown.mjs`（S4-D2 起只此一份）。
//
// ⚠️ 本脚本原先的 `purgeProbe` **只删 `sys_kb_grant` + `sys_kb`**，对
// `sys_document` / `sys_chunk` / `sys_parse_issue` / MinIO / Milvus **一无所知**。
// 它当前是对的 —— 但纯属「本脚本恰好还没建文档」，**没有任何机制保证下次还对**。

function kbCount() {
  return Number(mysqlScalar('select count(*) from sys_kb;'))
}

/** 读矩阵档位（无行视为 NONE，与读侧同口径）。 */
function matrixLevel() {
  const out = mysqlScalar(
    `select level from sys_role_matrix where role_id=${MATRIX_ROLE_ID} and item_key='${MATRIX_ITEM}';`,
  )
  return out || 'NONE'
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

const today = () => {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
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
  /** 统计卡四数，按 DOM 顺序（今日操作 / 高危操作 / 活跃操作人 / 失败操作） */
  statValues: () => [...document.querySelectorAll('.stat-card .sv')].map((x) => window.__t.norm(x.textContent)),
  rows: () => [...document.querySelectorAll('.el-table__body .el-table__row')],
  rowTexts: () => window.__t.rows().map((r) => window.__t.norm(r.textContent)),
  /** 点第 i 行的「查看」 */
  openDetailAt: (i) => {
    const r = window.__t.rows()[i]
    if (!r) throw new Error('没有第 ' + i + ' 行')
    const btn = [...r.querySelectorAll('button')].find((b) => window.__t.norm(b.textContent).includes('查看'))
    if (!btn) throw new Error('该行没有「查看」按钮')
    btn.click()
    return true
  },
  /** 找含某段文本的行下标；找不到返回 -1 */
  rowIndexOf: (text) => window.__t.rowTexts().findIndex((t) => t.includes(text)),
  /** 抽屉里的变更明细行 */
  diffRows: () => [...document.querySelectorAll('.drawer-body .diff-table .diff-row')].map((r) => window.__t.norm(r.textContent)),
  diffSummary: () => window.__t.text('.drawer-body .diff-summary'),
  /** 技术信息里的「影响用户」那一行的值（dt/dd 配对） */
  techValue: (key) => {
    const dts = [...document.querySelectorAll('.drawer-body .tech-list dt')]
    const dt = dts.find((d) => window.__t.norm(d.textContent) === key)
    return dt ? window.__t.norm(dt.nextElementSibling?.textContent) : null
  },
  /** 模块下拉的选项文案（先把下拉点开再读） */
  openModuleSelect: () => {
    // ⚠️ #160 顺一遍查过这里，**结论是"不用改"**，理由记在下面（免得下一个人再想一遍）：
    //    ① .head-right 下确实有**两个** el-select（模块 / 结果，AuditLogView.vue:239/243），
    //       但两个都**可见** ⇒ 不是 #160 那个"不可见孪生"的形态；
    //    ② [0] 虽然是位置耦合，却**不会静默取错** —— 紧接着的断言会检查"开出来的
    //       下拉里有没有「全部模块」/「审计」"，开错了当场转红。
    //    🔴 **试过改成"按文案选"，被探针当场否掉**：el-select 显示的是**选中的值**
    //       （实测是「知识库」），**不是占位符**「全部模块」——
    //       于是按 textContent 找「模块」一个也找不到，脚本直接中断。
    //       教训：给守卫写"现有的有哪些"那句话，能让误报**当场自证**（失败信息里
    //       直接打出了「现有：知识库 / 全部结果」，一眼看出它显示的是选中值）。
    // ⚠️ 本段在**模板字符串**里（要注入页面执行）—— 注释里不能出现反引号。
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
  closeDrawer: () => {
    const btn = document.querySelector('.el-drawer__close-btn')
    if (btn) { btn.click(); return true }
    return false
  },
  drawerOpen: () => !!document.querySelector('.drawer-body .diff-table, .drawer-body .tech-list'),
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

  // ==================== 0. 准备数据（直连 API，不点页面） ====================
  // ⚠️ 覆盖率闸必须在建探针之前跑（放在 finally 里会导致「闸抛错 → 清理反而不执行」）
  const cov = assertArtifactCoverage()
  observe('产物覆盖率闸通过', `扫描 ${cov.scanned} 张带归属列的表 · 已登记 ${cov.registered} 张 · 显式豁免 ${cov.exempt} 张`)

  await teardownKbProbe(PROBE_CODE)
  check(kbCount() === 8, '准备：sys_kb 回到 8 行（幂等清理探针库）', `行数=${kbCount()}`)

  const login = await api('/auth/login', {
    method: 'POST',
    body: { ...ADMIN, remember: true },
  })
  if (login.json?.code !== 0) throw new Error(`admin 登录失败：code=${login.json?.code} · ${login.json?.message}`)
  const token = login.json.data.accessToken
  const operatorName = login.json.data.user?.realName ?? ''
  check(true, '准备：admin 登录，拿到 accessToken', `userId=${login.json.data.user?.id}`)

  const matrixBefore = matrixLevel()

  try {
    // ---- ① 造一条「保存配置」日志（B2 那条判据 22 的素材）----
    const created = await api('/kb', {
      method: 'POST',
      token,
      body: {
        name: PROBE_NAME,
        code: PROBE_CODE,
        deptId: 1,
        visibleRoles: ['SUPER_ADMIN'],
        secretLevel: 'INTERNAL',
        businessDomain: '研发 / 平台',
        description: '',
        customTags: [],
        splitPolicy: { preset: 'GENERAL', chunkSize: 512, overlap: 64, recursiveSplit: true, semanticSplit: false },
        tablePolicy: { atomicTable: true, dualRepresentation: true, repeatHeader: true, mergeCrossPage: true, ocrTable: true, rowLimit: 30, rowWindow: 5 },
        retrievalConfig: { mode: 'HYBRID', recallThreshold: 0.15, rerankTopK: 5, rerankEnabled: true, tableWeight: 1.2, identifierProtect: true },
      },
    })
    if (created.json?.code !== 0) throw new Error(`建探针库失败：${created.json?.message}`)
    const kbId = created.json.data.id

    const saved = await api(`/kb/${kbId}/config`, {
      method: 'PUT',
      token,
      body: {
        config: {
          name: PROBE_NAME,
          code: PROBE_CODE,
          deptId: 1,
          visibleRoles: ['SUPER_ADMIN'],
          secretLevel: 'INTERNAL',
          businessDomain: '研发 / 平台',
          description: 'C 批页面级探针：这条描述会进审计明细',
          customTags: [],
          splitPolicy: { preset: 'CUSTOM', chunkSize: 256, overlap: 32, recursiveSplit: true, semanticSplit: false },
          tablePolicy: { atomicTable: true, dualRepresentation: true, repeatHeader: true, mergeCrossPage: true, ocrTable: true, rowLimit: 30, rowWindow: 5 },
          retrievalConfig: { mode: 'HYBRID', recallThreshold: 0.15, rerankTopK: 5, rerankEnabled: true, tableWeight: 1.2, identifierProtect: true },
        },
      },
    })
    if (saved.json?.code !== 0) throw new Error(`保存探针库配置失败：${saved.json?.message}`)
    check(true, '准备：探针库建好并保存了一次配置（产生「保存配置」审计行）', `kbId=${kbId}`)

    // ---- ② 造一条「保存功能矩阵」日志（影响人数非 0 的素材）----
    const flipped = matrixBefore === 'READ' ? 'NONE' : 'READ'
    const matrixRes = await api('/permissions/matrix', {
      method: 'PUT',
      token,
      body: { changes: [{ roleId: MATRIX_ROLE_ID, permKey: MATRIX_ITEM, level: flipped }] },
    })
    if (matrixRes.json?.code !== 0) throw new Error(`矩阵保存失败：${matrixRes.json?.message}`)
    check(true, '准备：改了一次功能矩阵（产生「保存功能矩阵」审计行，影响人数非 0）', `${MATRIX_ITEM}: ${matrixBefore} → ${flipped}`)

    // ==================== 1. 起浏览器 ====================
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

    await send('Page.navigate', { url: `${BASE}/#/login` })
    await waitFor(`!!document.querySelector('#app') && !!location.hash`, { label: '应用挂载', timeout: 30000 })
    await evaluate(`localStorage.setItem('ak_token', ${JSON.stringify(token)}); 'ok'`)

    // ==================== 2. 审计页：列表 + 时间窗（W3） ====================
    await navigate('/system/audit')
    await waitFor(`!!document.querySelector('.sys-audit-page')`, { label: '审计页渲染' })
    await evaluate(PAGE_TOOLS)

    const listEntry = await waitForApi((e) => e.url.includes('/api/audit/logs') && e.method === 'GET', {
      label: 'GET /api/audit/logs',
    })
    check(true, '审计页调用 GET /api/audit/logs', assertBizOk(listEntry, 'GET /api/audit/logs'))

    // ⚠️ W3 的核心：**只看"页面显示了今天"是不够的**，必须验请求 URL 里真的带了
    //    今天的 startTime/endTime —— 旧版页面显示的日期与发出去的参数毫无关系。
    const url = new URL(listEntry.url)
    const t = today()
    check(
      url.searchParams.get('startTime') === t && url.searchParams.get('endTime') === t,
      'W3：列表请求真带 startTime/endTime，且是**今天**（不再是写死的 2026-09-10~11）',
      `startTime=${url.searchParams.get('startTime')} endTime=${url.searchParams.get('endTime')}`,
    )
    check(
      url.searchParams.get('size') === '10',
      '列表请求带 size（前端发的参数名是 size，不是 pageSize）',
      `size=${url.searchParams.get('size')}`,
    )

    const listData = JSON.parse(listEntry.body ?? '{}').data ?? {}
    const windowDays = mysqlScalar(
      `select count(*) from sys_audit_log where created_date='${t}';`,
    )
    check(
      Number(listData.total) === Number(windowDays),
      '列表 total 等于「今天」的日志行数（证明窗口真的收窄了，不是扫全表）',
      `total=${listData.total} · DB 今天=${windowDays}`,
    )

    await waitFor(`window.__t.rows().length > 0`, { label: '列表渲染出数据行' })
    const shot1 = await send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(`${OUT_DIR}/audit-list.png`, Buffer.from(shot1.data, 'base64'))

    // ==================== 3. 统计卡（W5） ====================
    const statsEntry = await waitForApi((e) => e.url.includes('/api/audit/stats'), { label: 'GET /api/audit/stats' })
    check(true, '统计卡调用 GET /api/audit/stats', assertBizOk(statsEntry, 'GET /api/audit/stats'))
    const statsData = JSON.parse(statsEntry.body ?? '{}').data ?? {}
    // 等卡片渲染完（初值是 0，接口回来后才有值）
    await waitFor(`window.__t.statValues().some((v) => v !== '0')`, { label: '统计卡从真后端取值', timeout: 10000 })
    const cards = await evaluate(`window.__t.statValues()`)
    const expectedCards = [String(statsData.today), String(statsData.highRisk), String(statsData.activeUsers), String(statsData.failed)]
    check(
      cards.join('|') === expectedCards.join('|'),
      'W5：四个统计卡与 GET /audit/stats 逐数一致（不再是写死的 247/12/34/3）',
      `卡片=${cards.join(' / ')} · API=${expectedCards.join(' / ')}`,
    )
    check(
      cards.join('|') !== '247|12|34|3',
      'W5：卡片**确实不是** mock 常数（防止"恰好相等"造成的假绿）',
      `卡片=${cards.join(' / ')}`,
    )

    // ==================== 4. 模块下拉含「审计」（W2） ====================
    await evaluate(`window.__t.openModuleSelect()`)
    await waitFor(`window.__t.selectOptions().length > 0`, { label: '模块下拉展开' })
    const options = await evaluate(`window.__t.selectOptions()`)
    check(
      options.includes('审计'),
      'W2：模块下拉含「审计」—— 否则 T-304 的导出日志写得进、筛不出',
      options.join('/'),
    )
    check(options.includes('全部模块') && options.includes('知识库'), '模块下拉含既有项（全部模块 / 知识库）', options.join('/'))
    await evaluate(`window.__t.pickOption('知识库')`)
    const kbFiltered = await waitForApi(
      (e) => e.url.includes('/api/audit/logs') && e.url.includes('module=') && e !== listEntry,
      { label: 'GET /api/audit/logs?module=知识库' },
    )
    check(true, '切换模块下拉真的重发请求（带 module 参数）', assertBizOk(kbFiltered, 'module 筛选'))
    await waitFor(`window.__t.rowIndexOf('保存配置') >= 0`, { label: '筛出「保存配置」行' })

    // ==================== 5. 判据 22：B2 的「保存配置」日志可查 + 详情有 changes ====================
    // ⚠️ 按**对象列**定位，不能按知识库名 —— 列表的「对象」列渲染的是 `target`，
    //    而保存配置的 target 是 `知识库#{id}`（不是库名）。按名字找会永远 -1，
    //    看起来像"日志没写进去"，实际是断言找错了字段。
    const probeRow = await evaluate(`window.__t.rowIndexOf(${JSON.stringify(`知识库#${kbId}`)})`)
    check(probeRow >= 0, '判据 22：B2 留的「保存配置」日志在 /audit/logs 可查（G3-7 消费侧）', `行下标=${probeRow}`)
    let detailEntry = null
    if (probeRow >= 0) {
      await evaluate(`window.__t.openDetailAt(${probeRow})`)
      await waitFor(`window.__t.drawerOpen()`, { label: '详情抽屉打开' })
      detailEntry = await waitForApi((e) => /\/api\/audit\/logs\/\d+/.test(e.url), { label: 'GET /api/audit/logs/{id}' })
      const d = JSON.parse(detailEntry.body ?? '{}').data ?? {}
      check(
        d.changes?.length > 0,
        '判据 22：该条详情的 changes 含真实变更明细（B2 保存配置时登记的字段）',
        `${d.changes?.length ?? 0} 项`,
      )
      const diffRows = await evaluate(`window.__t.diffRows()`)
      check(
        diffRows.length === (d.changes?.length ?? 0) && diffRows.length > 0,
        '判据 19：变更明细真实渲染，行数 = API 的 changes.length（不再是 mock 的 4 行）',
        `DOM=${diffRows.length} 行 · API=${d.changes?.length ?? 0} 项`,
      )
      const expectedSummary = `共 ${d.changes.length} 项，其中 ${d.changes.filter((c) => c.before !== c.after).length} 项发生变更`
      const summary = await evaluate(`window.__t.diffSummary()`)
      check(
        summary.includes(expectedSummary),
        '判据 19：「共 N 项，其中 M 项发生变更」与 API 逐数一致',
        summary,
      )
      // 「分片策略预设」是 B2 那条日志的典型字段，钉一下明细确实渲染到了页面上
      check(
        diffRows.some((r) => r.includes('分片策略预设')),
        '判据 22：明细里含「分片策略预设」（B2 保存配置的 diff 字段）',
        diffRows.find((r) => r.includes('分片策略预设')) ?? diffRows.join(' | ').slice(0, 80),
      )
      const shot2 = await send('Page.captureScreenshot', { format: 'png' })
      writeFileSync(`${OUT_DIR}/audit-detail-changes.png`, Buffer.from(shot2.data, 'base64'))
    }

    // ==================== 6. 判据 20：影响用户 N 人（X 下全部成员） ====================
    await evaluate(`window.__t.closeDrawer()`)
    await sleep(400)
    await evaluate(`window.__t.openModuleSelect()`)
    await waitFor(`window.__t.selectOptions().length > 0`, { label: '模块下拉再次展开' })
    await evaluate(`window.__t.pickOption('授权')`)
    await waitForApi((e) => e.url.includes('/api/audit/logs') && e.url.includes('module=%E6%8E%88%E6%9D%83'), {
      label: 'GET /api/audit/logs?module=授权',
    })
    await waitFor(`window.__t.rowIndexOf('保存功能矩阵') >= 0`, { label: '筛出「保存功能矩阵」行' })
    const matrixRow = await evaluate(`window.__t.rowIndexOf('保存功能矩阵')`)
    await evaluate(`window.__t.openDetailAt(${matrixRow})`)
    await waitFor(`window.__t.drawerOpen()`, { label: '矩阵日志详情抽屉打开' })
    const matrixDetailEntry = await waitForApi(
      (e) => /\/api\/audit\/logs\/\d+/.test(e.url) && e.url !== detailEntry?.url,
      { label: 'GET /api/audit/logs/{id}（矩阵）' },
    )
    const md = JSON.parse(matrixDetailEntry.body ?? '{}').data ?? {}
    await waitFor(`window.__t.techValue('影响用户') !== null`, { label: '「影响用户」行渲染', timeout: 10000 })
    const affectedText = await evaluate(`window.__t.techValue('影响用户')`)
    check(
      Number(md.affectedUsers) > 0 && affectedText.startsWith(`${md.affectedUsers} 人`),
      '判据 20：「影响用户 N 人」的 N 来自 API 的 affectedUsers',
      `页面=${affectedText} · API=${md.affectedUsers}`,
    )
    // ⚠️ W1 的核心断言：括号里必须是**受影响角色名**（普通用户），不是操作人的角色码（SUPER_ADMIN）。
    //    取错列时这里会显示「109 人（SUPER_ADMIN 下全部成员）」——数字对、格式对、语义错。
    check(
      md.roleName === '普通用户' && affectedText.includes('（普通用户下全部成员）'),
      '判据 20 / W1：括号里是**受影响角色名**（普通用户），不是操作人角色码（SUPER_ADMIN）',
      `页面=${affectedText} · API roleName=${md.roleName}`,
    )
    check(
      !affectedText.includes('SUPER_ADMIN'),
      'W1 反向：页面**没有**渲染出操作人角色码（取错 role_name 列的典型症状）',
      affectedText,
    )
    const shot3 = await send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(`${OUT_DIR}/audit-detail-affected.png`, Buffer.from(shot3.data, 'base64'))

    // ==================== 7. 导出（真调用异步接口，不再假 toast） ====================
    await evaluate(`window.__t.closeDrawer()`)
    await sleep(400)
    const beforeExport = ledger.length
    await evaluate(`window.__t.norm([...document.querySelectorAll('.head-actions button')].find((b) => window.__t.norm(b.textContent).includes('导出日志'))?.textContent)`)
    await evaluate(`(() => { const b = [...document.querySelectorAll('.head-actions button')].find((x) => window.__t.norm(x.textContent).includes('导出日志')); if (!b) throw new Error('找不到导出按钮'); b.click(); return true })()`)
    const exportEntry = await waitForApi((e) => e.url.includes('/api/audit/export') && e.method === 'POST', { label: 'POST /api/audit/export' })
    check(true, '导出按钮真调用 POST /api/audit/export（此前是纯 toast 假成功）', assertBizOk(exportEntry, '导出'))
    const exportBody = JSON.parse(exportEntry.postData ?? '{}')
    check(
      exportBody.module === '授权',
      '导出请求**带上页面当前的筛选条件**（筛了什么就导出什么，不是全量外带）',
      `module=${exportBody.module} · startTime=${exportBody.startTime}`,
    )
    const exportData = JSON.parse(exportEntry.body ?? '{}').data ?? {}
    check(!!exportData.taskId, '导出返回 taskId', `taskId=${exportData.taskId}`)
    // 记下来收尾删 —— 这个任务行**没有知识库维度**（kb_id 为 NULL），库族自清看不见它，
    // 只能由**造它的脚本**点名删（`S5` 测试可靠性批实测：不删的话每跑一轮留一行）
    exportTaskId = exportData.taskId
    check(ledger.length > beforeExport, '导出确实发出了一条网络请求（台账有新增）', `${beforeExport} → ${ledger.length}`)

    // ==================== 8. console 与溯源 ====================
    const bad = consoleMsgs.filter((m) => /Failed to resolve component|Unhandled error|TypeError|is not a function/.test(m.text))
    check(bad.length === 0, '审计页 console 无未捕获异常', bad.map((b) => b.text).join(' | ') || '—')
    const keyEntries = [listEntry, statsEntry, detailEntry, matrixDetailEntry, exportEntry].filter(Boolean)
    const missingTrace = keyEntries.filter((e) => !headerOf(e.headers, 'x-trace-id'))
    check(
      missingTrace.length === 0,
      '关键 /api 响应均带 x-trace-id（证明确为真后端，mock 不产出该头）',
      `${keyEntries.length - missingTrace.length}/${keyEntries.length}`,
    )

    console.log(`\n接口台账：\n${ledger.map((e) => `  ${e.method.padEnd(6)} ${e.url.replace(BASE, '')} → ${e.status}`).join('\n')}`)
    console.log(`截图：${OUT_DIR}/{audit-list,audit-detail-changes,audit-detail-affected}.png`)

    child.kill()
  } finally {
    try {
      ws?.close()
    } catch {
      /* ignore */
    }
    // ---- 自清：矩阵还原 + 探针库删除 ----
    // 顺序无关紧要，但**必须都做** —— 任一项残留都会让下一次跑别批用例时看到漂移的数据。
    try {
      const restore = await api('/permissions/matrix', {
        method: 'PUT',
        token,
        body: { changes: [{ roleId: MATRIX_ROLE_ID, permKey: MATRIX_ITEM, level: matrixBefore }] },
      })
      check(restore.json?.code === 0, '收尾：功能矩阵已还原', `level=${matrixLevel()}`)
      // 导出任务行点名删（它没有 kb 维度，库族自清扫不到）—— 见文件头 `exportTaskId` 的注释
      if (exportTaskId) {
        mysqlScalar(`delete from sys_async_task where task_id = '${exportTaskId}';`)
      }
      const r = await teardownKbProbe(PROBE_CODE)
      check(
        kbCount() === 8 && kbResidueClean(r.residue),
        '收尾：探针库及其全部产物（库行 / 文档 / 分片 / Milvus 向量与分区 / MinIO 对象）已自清',
        `库=${kbCount()} · ${describeKbResidue(r.residue)}`,
      )
    } catch (e) {
      check(false, '收尾：自清失败，需人工检查', String(e.message))
    }
  }
}

// 页面里没有 window.__normCard，这里补一个只用于日志的简短化
const normCard = (s) => String(s ?? '').replace(/\s+/g, ' ').trim().slice(0, 60)

main()
  .then(() => {
    const failed = steps.filter((s) => !s.ok).length
    console.log(`\n合计 ${steps.length} 步，通过 ${steps.length - failed}，失败 ${failed}`)
    process.exit(failed ? 1 : 0)
  })
  .catch(async (e) => {
    console.error(`\n✗ 中断：${e.message}`)
    const failed = steps.filter((s) => !s.ok).length
    if (steps.length) console.log(`\n已执行 ${steps.length} 步，通过 ${steps.length - failed}，失败 ${failed}`)
    try {
      await teardownKbProbe(PROBE_CODE)
    } catch {
      /* 已在 finally 里报过 */
    }
    process.exit(1)
  })
