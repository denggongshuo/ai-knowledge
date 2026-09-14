#!/usr/bin/env node
/**
 * S3 · B1 页面级 E2E：知识库三页切真后端（列表 / 创建向导 / 设置页）。
 *
 * <h3>覆盖的判据</h3>
 * <ul>
 *   <li><b>G3-1</b> —— 创建向导四步走通、真写入、跳转上传页；</li>
 *   <li><b>G3-2</b> —— 向导与设置页的嵌入模型都是只读（锁图标 + 文案），预设来自真后端；</li>
 *   <li><b>T-400</b> —— 列表渲染真后端 8 库；「我创建的」切到 scope=mine 时重新发请求
 *       （W5 的页面级回归：不实现 scope 时按钮点了没反应，是**静默失效**）；</li>
 *   <li><b>T-403</b> —— 设置页回显与提交值一致（含 W1 的状态标签、W3 的 appliedAt 守卫）。</li>
 * </ul>
 *
 * <h3>技术路线</h3>
 * 与 S1/S2 的三个 E2E 脚本完全一致：Node 自带 WebSocket 直驱无头 Edge 的 CDP，
 * **零新增依赖**；断言看业务 code 不看 HTTP 码；抓 `x-trace-id` 证明打到了真后端
 * （mock 不产出该头）。
 *
 * <h3>自清纪律</h3>
 * 本脚本会用固定 code {@code kb_e2e_probe} 真建一个库，跑完必须删掉 —— 否则
 * 下次跑列表断言就会看到 9 个库、判据 17 的「sys_kb 回到 8 行」失败。
 * **开头也清一次**（幂等）：上一次异常中断留下的残留同样会污染。
 * 删除走 `docker exec kb-mysql mysql`，与后端同一条 SQL 通道，不经业务接口
 * （没有删除知识库的接口 —— 那是 S3-B3 的 T-407）。
 *
 * <h3>前置</h3>
 * <pre>
 *   后端 8080：mvn -o spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
 *   前端 5174：VITE_USE_MOCK=false npm run dev -- --host 127.0.0.1 --port 5174
 *   运行：node scripts/e2e-kb-flow.mjs --base=http://127.0.0.1:5174
 * </pre>
 */
import { spawn, spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
/** 仓库根（前端模块的上一层）—— .env 在后端模块里，自清脚本要用。 */
const REPO_ROOT = resolve(ROOT, '..')
const arg = (name, fallback) => (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://127.0.0.1:5174')
const API = arg('api', 'http://127.0.0.1:8080')

const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-e2e-kb'
const OUT_DIR = 'F:/work/code/ai-knowledge/temp/e2e-kb'
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9337)

/** 固定账号：种子超级管理员（数据范围 ALL，kb:view/kb:list/kb:create 全有）。 */
const PROBE = { account: 'admin', password: 'Admin@123' }

/** 探针库的固定编码（自清锚点，必须匹配 ^kb_[a-z0-9_]{2,}$）。 */
const PROBE_CODE = 'kb_e2e_probe'
const PROBE_NAME = 'B1 页面级探针库'

const steps = []
function check(ok, label, detail = '') {
  steps.push({ ok: !!ok, label, detail })
  console.log(`${ok ? '✓' : '✗'} ${label}${detail ? ` · ${detail}` : ''}`)
  return !!ok
}
function observe(label, detail = '') {
  console.log(`… ${label}${detail ? ` · ${detail}` : ''}`)
}

// ---------------------------------------------------------------- 数据库直连（仅自清 / 计数）
/**
 * 从后端 .env 读 MySQL 连接串，再走 `docker exec kb-mysql mysql` 执行一条 SQL。
 *
 * 为什么不连宿主 3306：A 阶段遇到过 Docker 宿主端口转发失效（容器 healthy、
 * `docker exec` 能查库、宿主连上零字节）。`docker exec` 这条路不受端口转发影响，
 * 是**更可靠**的那条 —— 出问题时也能立刻分辨是"库挂了"还是"转发挂了"。
 */
function mysqlScalar(sql) {
  // ⚠️ 必须给 .env **绝对路径**（前端模块的上一层才是仓库根）：`bash -c` 的 cwd 虽然
  // 已设为仓库根，但把路径写死更抗调用方式变化 —— 少一层"从哪跑"的隐式依赖。
  const envFile = `${REPO_ROOT.replace(/\\/g, '/')}/ai_knowledge_web/.env`
  const script = `
set -a; . ${JSON.stringify(envFile)}; set +a
docker exec kb-mysql mysql --default-character-set=utf8mb4 -u"$MYSQL_USERNAME" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -N -e ${JSON.stringify(sql)} 2>/dev/null
`.trim()
  const r = spawnSync('bash', ['-c', script], { cwd: REPO_ROOT, encoding: 'utf8' })
  if (r.status !== 0) throw new Error(`mysql 查询失败（status=${r.status}）：${r.stderr || r.stdout}`)
  return (r.stdout ?? '').trim()
}

/** 幂等清理探针库（先授权后主表；无外键，顺序只为口径一致）。 */
function purgeProbe() {
  mysqlScalar(`delete g from sys_kb_grant g join sys_kb k on k.id=g.kb_id where k.code='${PROBE_CODE}';`)
  mysqlScalar(`delete from sys_kb where code='${PROBE_CODE}';`)
}

/** 当前 sys_kb 行数（自清判据）。 */
function kbCount() {
  return Number(mysqlScalar('select count(*) from sys_kb;'))
}

// ---------------------------------------------------------------- 直连后端（准备登录）
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
  text: (sel) => window.__t.norm(document.querySelector(sel)?.textContent),
  cards: () => [...document.querySelectorAll('.kb-grid .kb-card')],
  cardNames: () => window.__t.cards().map((c) => window.__t.norm(c.querySelector('.kb-name')?.textContent)),
  /** 点 scope 内文本命中的可点元素；scope 缺省为 document */
  clickText: (text, scope) => {
    const root = scope || document
    const all = [...root.querySelectorAll('button, .el-button, .el-radio-button__inner, .el-tabs__item, .preset-btn')]
    const b = all.find((x) => window.__t.norm(x.textContent) === window.__t.norm(text))
      || all.find((x) => window.__t.norm(x.textContent).includes(window.__t.norm(text)))
    if (!b) throw new Error('找不到可点元素：' + text)
    b.click()
    return true
  },
  /** 按 label 找 el-form-item 并填入 input / textarea */
  fillByLabel: (label, value) => {
    const items = [...document.querySelectorAll('.el-form-item')]
    const item = items.find((it) => window.__t.norm(it.querySelector('.el-form-item__label')?.textContent).includes(window.__t.norm(label)))
    if (!item) throw new Error('找不到表单项：' + label)
    const el = item.querySelector('input, textarea')
    if (!el) throw new Error('表单项没有可填控件：' + label)
    const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, value)
    el.dispatchEvent(new Event('input', { bubbles: true }))
    el.dispatchEvent(new Event('change', { bubbles: true }))
    return true
  },
  /** el-select 选一个选项（点开 → 从下拉里点文本命中的项） */
  pickSelect: (optionText) => {
    const dd = [...document.querySelectorAll('.el-select-dropdown')].filter((d) => d.offsetParent !== null || d.style.display !== 'none')
    const visible = dd[dd.length - 1]
    const pool = visible ? [...visible.querySelectorAll('.el-select-dropdown__item')] : []
    const opt = pool.find((o) => window.__t.norm(o.textContent) === window.__t.norm(optionText))
    if (!opt) throw new Error('下拉里找不到选项：' + optionText + '（现有：' + pool.map((o) => window.__t.norm(o.textContent)).join('/') + '）')
    opt.click()
    return true
  },
  openSelectByLabel: (label) => {
    const items = [...document.querySelectorAll('.el-form-item')]
    const item = items.find((it) => window.__t.norm(it.querySelector('.el-form-item__label')?.textContent).includes(window.__t.norm(label)))
    if (!item) throw new Error('找不到表单项：' + label)
    const trigger = item.querySelector('.el-select__wrapper, .el-select')
    if (!trigger) throw new Error('表单项不是下拉：' + label)
    trigger.click()
    return true
  },
  box: () => [...document.querySelectorAll('.el-message-box')].find((b) => b.offsetParent !== null) || null,
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

  // ---- 幂等自清：上一次中断的残留同样会污染这次的「列表 8 库」断言 ----
  purgeProbe()
  const kbBefore = kbCount()
  check(kbBefore === 8, '准备：sys_kb 回到 8 行（幂等清理探针库）', `行数=${kbBefore}`)

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
    // ==================== 1. 列表页 ====================
    await navigate('/kb')
    await waitFor(`!!document.querySelector('.kb-page')`, { label: '知识库列表页渲染' })
    await evaluate(PAGE_TOOLS)

    const listEntry = await waitForApi((e) => e.url.includes('/api/kb') && e.method === 'GET' && !e.url.includes('/kb/'), { label: 'GET /api/kb' })
    check(true, '列表页调用 GET /api/kb', assertBizOk(listEntry, 'GET /api/kb'))
    const listData = JSON.parse(listEntry.body ?? '{}').data ?? {}
    check(Number(listData.total) === 8, '列表 total 来自后端（8 库）', `total=${listData.total}`)

    await waitFor(`window.__t.cards().length === 8`, { label: '列表渲染 8 张卡片' })
    const names = await evaluate(`window.__t.cardNames()`)
    check(names.includes('产品技术文档库') && names.includes('客户合同库'), '卡片渲染真后端库名', `${names[0]} … ${names.length} 张`)
    const firstCard = await evaluate(`window.__t.cards()[0].textContent`)
    check(/文档\s*\d+/.test(firstCard) && /分片\s*[\d,]+/.test(firstCard), '卡片带文档 / 分片计数（真后端计数字段）', normCard(firstCard))
    const shot1 = await send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(`${OUT_DIR}/kb-list.png`, Buffer.from(shot1.data, 'base64'))

    // ---- W5：「我创建的」必须真的把 scope 传出去（不传 = 静默失效） ----
    await evaluate(`window.__t.clickText('我创建的')`)
    const mineEntry = await waitForApi(
      (e) => e.url.includes('/api/kb') && e.method === 'GET' && e.url.includes('scope=mine') && e !== listEntry,
      { label: 'GET /api/kb?scope=mine' },
    )
    check(true, '切换「我创建的」发出 scope=mine（W5 页面级回归）', assertBizOk(mineEntry, 'GET /api/kb?scope=mine'))
    const mineData = JSON.parse(mineEntry.body ?? '{}').data ?? {}
    // admin 不是 8 个演示库里任何一个的创建人 → 0
    check(Number(mineData.total) === 0, 'scope=mine 按 owner_id 收窄（admin 无自建库 → 0）', `total=${mineData.total}`)
    await evaluate(`window.__t.clickText('全部')`)
    await waitFor(`window.__t.cards().length === 8`, { label: '切回全部后 8 张卡片' })

    // ==================== 2. 创建向导 ====================
    await evaluate(`window.__t.clickText('创建知识库')`)
    await waitFor(`!!document.querySelector('.create-page')`, { label: '创建向导渲染' })
    await evaluate(PAGE_TOOLS)

    const presetsEntry = await waitForApi((e) => e.url.includes('/api/kb/presets'), { label: 'GET /api/kb/presets' })
    check(true, '向导调用 GET /api/kb/presets', assertBizOk(presetsEntry, 'GET /api/kb/presets'))
    const presetsData = JSON.parse(presetsEntry.body ?? '{}').data ?? {}
    check(presetsData.presets?.length === 5, '预设来自真后端（5 条）', `presets=${presetsData.presets?.length}`)
    check(presetsData.embedding?.locked === true, 'G3-2：嵌入模型 locked=true（来自真后端）', `locked=${presetsData.embedding?.locked}`)
    await waitFor(`!!document.querySelector('.preset-row') || !!document.querySelector('.el-step')`, { label: '向导表单渲染' })

    // 步骤 ①：填名称 + 编码
    await evaluate(`window.__t.fillByLabel('知识库名称', ${JSON.stringify(PROBE_NAME)})`)
    await evaluate(`window.__t.fillByLabel('知识库编码', ${JSON.stringify(PROBE_CODE)})`)
    await evaluate(`window.__t.clickText('下一步')`)

    // 步骤 ②：预设卡片来自真后端（5 个），点一个换预设
    await waitFor(`[...document.querySelectorAll('.preset-btn')].length === 5`, { label: '步骤②渲染 5 个预设卡片' })
    const presetLabels = await evaluate(`[...document.querySelectorAll('.preset-btn .pb-label')].map((x) => window.__t.norm(x.textContent))`)
    check(presetLabels.length === 5, '预设卡片 5 个、文案与后端一致', presetLabels.join('/'))
    await evaluate(`window.__t.clickText('合同条款')`)
    const activeLabel = await evaluate(`window.__t.text('.preset-btn.active .pb-label')`)
    check(activeLabel === '合同条款', '点预设即带出参数（激活态切到「合同条款」）', `active=${activeLabel}`)
    await evaluate(`window.__t.clickText('下一步')`)

    // 步骤 ③：治理标签（select 来自真后端 DOMAINS 常量）
    await waitFor(`window.__t.norm(document.body.textContent).includes('治理标签')`, { label: '步骤③渲染' })
    const secretVisible = await evaluate(`window.__t.norm(document.body.textContent).includes('内部')`)
    check(secretVisible, '步骤③渲染密级（默认 INTERNAL）', `含「内部」=${secretVisible}`)
    await evaluate(`window.__t.clickText('下一步')`)

    // 步骤 ④：确认创建
    await waitFor(`window.__t.norm(document.body.textContent).includes('确认创建「')`, { label: '步骤④渲染' })
    await evaluate(`window.__t.clickText('确认创建')`)
    const createEntry = await waitForApi((e) => e.url.replace(/\/$/, '').endsWith('/api/kb') && e.method === 'POST', { label: 'POST /api/kb' })
    check(true, '向导提交走通真写接口 POST /api/kb', assertBizOk(createEntry, 'POST /api/kb'))
    const createBody = JSON.parse(createEntry.postData ?? '{}')
    check(createBody.code === PROBE_CODE, '请求体带向导填的编码', `code=${createBody.code}`)
    check(createBody.splitPolicy?.preset === 'CONTRACT', '请求体带步骤②选的预设（CONTRACT）', `preset=${createBody.splitPolicy?.preset}`)

    // 创建成功 → 跳上传页（G3-1 的后半，上传页本身的数据源在 S4，只验跳转发生）
    await waitFor(`location.hash.includes('/upload')`, { label: '跳转到文档上传页', timeout: 20000 })
    check(true, 'G3-1：创建成功跳转文档上传页', `hash=${await evaluate('location.hash')}`)
    const created = JSON.parse(createEntry.body ?? '{}').data ?? {}
    const createdId = created.id
    const shot2 = await send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(`${OUT_DIR}/kb-create-done.png`, Buffer.from(shot2.data, 'base64'))

    // ==================== 3. 设置页回显 ====================
    await navigate(`/kb/${createdId}`)
    await waitFor(`!!document.querySelector('.detail-page')`, { label: '设置页渲染' })
    await evaluate(PAGE_TOOLS)
    const configEntry = await waitForApi((e) => e.url.includes(`/api/kb/${createdId}/config`), { label: 'GET /api/kb/:id/config' })
    check(true, '设置页调用 GET /api/kb/:id/config', assertBizOk(configEntry, 'GET /api/kb/:id/config'))
    const cfg = JSON.parse(configEntry.body ?? '{}').data ?? {}
    check(cfg.name === PROBE_NAME && cfg.code === PROBE_CODE, '设置页回显名称 / 编码（真后端）', `${cfg.name} · ${cfg.code}`)
    check(cfg.config?.splitPolicy?.preset === 'CONTRACT', '回显的预设与向导提交值一致', `preset=${cfg.config?.splitPolicy?.preset}`)
    check(cfg.embedding?.locked === true, 'G3-2：设置页嵌入模型只读', `locked=${cfg.embedding?.locked}`)

    await waitFor(`window.__t.norm(document.querySelector('.page-desc')?.textContent).length > 0`, { label: '设置页页头渲染' })
    const statusTag = await evaluate(`window.__t.text('.detail-page .el-form-item .el-tag')`)
    check(
      statusTag === '已就绪',
      'W1：状态标签渲染派生文案「已就绪」（不是裸 READY / 不是按 ACTIVE 分支的兜底）',
      `状态=${statusTag}`,
    )

    // W3：VL 模型区块存在，且**没有**渲染出「自  起生效」（appliedAt 为空串时被 v-if 挡住）
    const vlText = await evaluate(`window.__t.norm(document.body.textContent)`)
    const hasEmptyAppliedAt = /自\s{2,}起生效/.test(vlText) || vlText.includes('自  起生效')
    check(!hasEmptyAppliedAt, 'W3：appliedAt 为空时不渲染「自  起生效」（v-if 守卫生效）', `出现空时间=${hasEmptyAppliedAt}`)
    const shot3 = await send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(`${OUT_DIR}/kb-detail.png`, Buffer.from(shot3.data, 'base64'))

    // ==================== 4. console 与溯源 ====================
    const bad = consoleMsgs.filter((m) => /Failed to resolve component|Unhandled error|TypeError|is not a function/.test(m.text))
    check(bad.length === 0, 'KB 三页 console 无未捕获异常', bad.map((b) => b.text).join(' | ') || '—')
    const keyEntries = [listEntry, presetsEntry, createEntry, configEntry]
    const missingTrace = keyEntries.filter((e) => !headerOf(e.headers, 'x-trace-id'))
    check(missingTrace.length === 0, '关键 /api 响应均带 x-trace-id（证明确为真后端）', `${keyEntries.length - missingTrace.length}/${keyEntries.length}`)

    console.log(`\n接口台账：\n${ledger.map((e) => `  ${e.method.padEnd(6)} ${e.url.replace(BASE, '')} → ${e.status}`).join('\n')}`)
    console.log(`截图：${OUT_DIR}/{kb-list,kb-create-done,kb-detail}.png`)
  } finally {
    await sleep(200)
    try {
      ws?.close()
    } catch {
      /* ignore */
    }
    child.kill()
    // ---- 自清：无论成败都要把探针库删掉，否则下次跑列表断言就是 9 个库 ----
    try {
      purgeProbe()
      const after = kbCount()
      check(after === 8, '收尾：探针库已自清，sys_kb 回到 8 行', `行数=${after}`)
    } catch (e) {
      check(false, '收尾：探针库自清失败，需人工检查', String(e.message))
    }
  }
}

// 页面里没有 window.__normCard，这里补一个只用于日志的简短化（避免把整卡文本打进报告）
const normCard = (s) => String(s ?? '').replace(/\s+/g, ' ').trim().slice(0, 60)

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
      purgeProbe()
    } catch {
      /* 已在 finally 里报过 */
    }
    process.exit(1)
  })
