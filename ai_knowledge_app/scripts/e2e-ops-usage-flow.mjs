/**
 * `S8-M10-E` 页面级 E2E：使用统计页（页 21）接真后端（`T-A05`）+ 离线预计算（`T-A04`）。
 *
 * <h3>覆盖的判据</h3>
 * <ul>
 *   <li><b>`G8-8`</b> 活跃用户：「至少提问 1 次」；停用账号不计入；无归属提问（`user_id` 为 NULL）
 *       计入提问总数但**不算一个人**；</li>
 *   <li><b>`G8-9`</b> 合规边界：**同一个 AUDITOR token** —— 四个聚合端点通、`top-users` **403**；</li>
 *   <li><b>`G8-10`</b> 离线预计算：读的数是汇总表来的（本脚本用"删掉打点、读数不变"证，与单测同法）；</li>
 *   <li><b>`G8-1` 后半</b>：页 21 答「有没有人在用」，且**提问总数与页 11 / 页 20 是同一个数**；</li>
 *   <li>需求 §3.2 趋势 7 点且合计 = 提问总数；§3.4 库热度与页 20 一致；§3.8 三条合计 + 占比 100%。</li>
 * </ul>
 *
 * <h3>🔴 一个当场撞上的产品事实（已断言下来）</h3>
 * `ChatRequest` **没有 `kbIds`**、提问页也没有库选择器 ⇒ **每次提问覆盖全部可见库**
 * ⇒ 需求 §3.4 的「各库被提问次数，合计 = 提问总数」**不可能以「每库一行」成立**：
 * 一次提问装不进任何一个库。处置照页 20 的判例 —— **只归单库行 + 跨库入「其他」**，
 * 于是「各库 + 其他 == 提问总数」**恒等**（比原文的 `=` 更可证伪）。
 * ⚠️ **当前链路下单库行恒为 0** ⇒ 页面必须显示那个诚实空态。将来提问侧支持库范围时
 * 这两条会转红 —— 那正是要的。
 *
 * <h3>🔴 时间范围下拉框是**真的驱动**五块（`S8-M10-E` 修）</h3>
 * 接真之前五个 API 方法里**四个把 `range: '7d'` 写死**、页面也从不传 ⇒ 换了不生效，
 * 而页面上的数**看着完全正常**（它确实是一份正确的 7 天统计）。
 * 脚本切到 30 天并断言趋势变成 **30 个点** —— 那是"下拉框真的驱动了请求"的页面级证据。
 *
 * <h3>前置</h3>
 * <pre>
 *   后端 8080：mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
 *   前端 5174：VITE_USE_MOCK=false npm run dev -- --host 127.0.0.1 --port 5174
 *   运行：node scripts/e2e-ops-usage-flow.mjs --base=http://127.0.0.1:5174
 * </pre>
 *
 * ⚠️ **本文件没有 shebang**：本机 shell 的路径守卫会拦下含 `#!` 的内容。
 */
import { spawn } from 'node:child_process'
import { mkdirSync, rmSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import {
  mysqlCount,
  mysqlScalar,
  teardownKbProbe,
  purgeUserScopedArtifacts,
  kbResidueClean,
  describeKbResidue,
} from './lib/probe-teardown.mjs'

const arg = (name, fallback) => (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://localhost:5174')
const API = arg('api', 'http://localhost:8080')

const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-e2e-usage'
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9346)

/** 固定账号：`ops:usage:users:view` **只给超管**（见 `V36`）。 */
const PROBE = { account: 'admin', password: 'Admin@123' }
/** AUDITOR：`ops:usage:view` 有它、`ops:usage:users:view` 没有 —— `G8-9` 的落点。 */
const AUDITOR = { account: 'liu.qiang', password: 'Init@12345' }

/** 探针问句：与库里已有内容**不重合**（重复的话分不出"这次检索"与"上次"）。 */
const Q_ONE = '使用统计探针 甲乙丙丁 戊己庚辛'
const Q_TWO = '使用统计探针 甲乙丙丁 戊己庚辛？？'

const stamp = Date.now()
const KB_CODE = `kb_e2eusage_${stamp}`

const steps = []
function check(ok, label, detail = '') {
  steps.push({ ok: !!ok, label, detail })
  console.log(`${ok ? '✓' : '✗'} ${label}${detail ? ` · ${detail}` : ''}`)
  return !!ok
}
function observe(label, detail = '') {
  console.log(`… ${label}${detail ? ` · ${detail}` : ''}`)
}

// ---------------------------------------------------------------- 直连后端
let token = ''
let loginUserId = 0
async function api(path, { method = 'GET', body, raw = false, as = token } = {}) {
  const res = await fetch(`${API}/api${path}`, {
    method,
    headers: {
      ...(raw ? {} : body ? { 'Content-Type': 'application/json' } : {}),
      ...(as ? { Authorization: `Bearer ${as}` } : {}),
    },
    body: raw ? body : body ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    /* 非 JSON */
  }
  return { status: res.status, json, text }
}
function bizOk(res, label) {
  if (!res.json) throw new Error(`${label}：响应不是 JSON（HTTP ${res.status}）· ${String(res.text).slice(0, 200)}`)
  if (res.json.code !== 0) throw new Error(`${label}：业务失败 code=${res.json.code} · ${res.json.message}`)
  return res.json.data
}
const usage = async (ep, range = '7d') => bizOk(await api(`/ops/usage/${ep}?range=${range}`), ep)

// ---------------------------------------------------------------- CDP
let ws
let msgId = 0
const pending = new Map()
const consoleMsgs = []
/** 页面对 `/ops/usage/*` 的响应（`{url, status}`）—— 用来区分"没发出去"与"发了但失败"。 */
const usageResponses = []
function send(method, params = {}) {
  const id = ++msgId
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, method, params }))
  })
}
async function evaluate(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (r.exceptionDetails) throw new Error(`页面求值失败：${r.exceptionDetails.text}`)
  return r.result?.value
}
async function goto(url) {
  await send('Page.navigate', { url })
  await sleep(600)
}
/**
 * 用 **CDP 真鼠标事件**点一个元素的中心。
 *
 * 🔴 为什么不能用 `el.dispatchEvent(new MouseEvent('click'))`：
 * 本脚本第一版就是那么写的，而 Element Plus 的 Select **没有展开**。
 * 合成事件虽然 `bubbles`，但它不经过**浏览器的命中测试与默认行为**（聚焦、键盘可用性、指针捕获）
 * —— 而组件库恰恰依赖后者（EP 的下拉还会在开启时做 `focus()` 与指针捕获）。
 * 这与本仓已有的一条同源：**看不出“红是环境还是代码”的判据会被忽略** ——
 * 这里如果不用真事件，失败会长得像“页面没渲染”。
 */
async function clickReal(selector) {
  const box = await evaluate(`(() => {
      const el = document.querySelector(${JSON.stringify(selector)});
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2), w: Math.round(r.width) };
    })()`)
  if (!box || box.w === 0) throw new Error(`找不到可点的元素：${selector}`)
  for (const type of ['mousePressed', 'mouseReleased']) {
    await send('Input.dispatchMouseEvent',
      { type, x: box.x, y: box.y, button: 'left', clickCount: 1 })
    await sleep(60)
  }
  return box
}

/** 轮询直到表达式为真值（返回它）或超时。 */
async function waitFor(expression, { timeout = 20000, label = '', interval = 300 } = {}) {
  const t0 = Date.now()
  let last
  while (Date.now() - t0 < timeout) {
    try {
      last = await evaluate(expression)
      if (last) return last
    } catch (e) {
      last = e.message
    }
    await sleep(interval)
  }
  throw new Error(`等待超时（${label || expression}）：最后值 = ${JSON.stringify(last)}`)
}

/** 本轮提问**精确**的会话 id（不靠水位线 —— `S8-M10-B` 那次"水位线以下没人认领"的教训）。 */
const sessionIds = new Set()

/** `G8-10` 那条证据用的人造历史打点（收尾要点名删）。 */
const HIST_TRACE = '__e2e_usage_hist__'

async function main() {
  // ---------- ① 登录 ----------
  const loginData = bizOk(await api('/auth/login', { method: 'POST', body: { ...PROBE, remember: true } }), '登录')
  token = loginData.accessToken
  loginUserId = Number(loginData.user?.id ?? 0)
  check(!!token && loginUserId > 0, '登录成功（种子超管）', `userId=${loginUserId}`)

  const auditorToken = bizOk(
    await api('/auth/login', { method: 'POST', body: { ...AUDITOR, remember: true } }), 'AUDITOR 登录').accessToken
  check(!!auditorToken, `AUDITOR 已登录（${AUDITOR.account}）`)

  // ---------- ② 建探针库 + 传一篇真内容（让提问拿到**有分数**的 Top1）----------
  const created = bizOk(await api('/kb', {
    method: 'POST',
    body: {
      name: `使用统计探针库 ${stamp}`,
      code: KB_CODE,
      deptId: 1,
      visibleRoles: ['USER'],
      businessDomain: '技术文档',
      description: 'S8-M10-E 使用统计 页面级联调探针，脚本结束即删',
    },
  }), '建库')
  check(!!created.id, `探针库已建 · id=${created.id}`)

  const content = [
    '# 使用统计与预计算',
    '',
    '运营统计按天汇总到汇总表，读侧只读汇总表；缓存可以从打点表完整重建。',
  ].join('\n')
  const form = new FormData()
  form.append('file', new Blob([content], { type: 'text/markdown' }), `e2eusage-${stamp}.md`)
  bizOk(await api(`/kb/${created.id}/doc/upload`, { method: 'POST', body: form, raw: true }), '上传')

  const deadline = Date.now() + 120000
  let lastStatus = ''
  while (Date.now() < deadline) {
    const docs = bizOk(await api(`/kb/${created.id}/docs?page=1&size=10`), '查文档')
    lastStatus = (docs.list ?? [])[0]?.status ?? '（还没行）'
    if (lastStatus === 'READY') break
    if (lastStatus === 'FAILED') throw new Error('文档解析失败')
    await sleep(2000)
  }
  check(lastStatus === 'READY', `文档已就绪（状态 ${lastStatus}）`)

  // ---------- ③ 真提问两次 ----------
  // ⚠️ 本脚本**不用会话水位线**（那是 `T-O19` 剩半要修的那种写法：水位线在表为空时就是 0，
  //    而"表为空"正是干净基线上的常态 ⇒ 拿它当守卫会把整段清理跳过）。
  //    改为按**本轮精确的 session_id** 清（`S8-M10-B` 的教训：不靠水位线），
  //    于是"守卫该看什么"这个问题在这里**根本不存在**。
  await askSse(Q_ONE)
  await askSse(Q_TWO)

  const traces = mysqlCount(
    `select count(*) from sys_retrieval_trace where query_text like '%使用统计探针%';`)
  check(traces >= 2, '两次提问都落了打点（页面上的"提问总数"才有来源）', `打点行数=${traces}`)

  // ---------- ④ 接口层：四指标与趋势（`G8-8` / 需求 §3.2）----------
  const ov = await usage('overview')
  check(Number(ov.totalQuestions) >= 2, '提问总数 ≥ 本轮那两次', `total=${ov.totalQuestions}`)
  check(Number(ov.activeUsers) >= 1,
    '🔴 `G8-8`：活跃用户 = 周期内**至少提问 1 次**（本轮的 admin 一定在里面）',
    `active=${ov.activeUsers} / total=${ov.totalUsers}`)
  check(Number(ov.activeUsers) <= Number(ov.totalUsers), '活跃人数 ≤ 总人数（分子不越过分母）')

  const tr = await usage('trend')
  check(Array.isArray(tr.list) && tr.list.length === 7,
    '🔴 需求 §3.2：趋势**恰好 7 个点**（日历天口径 —— 滚动窗口会给出 8 个）',
    `点数=${tr.list?.length}`)
  const trendSum = (tr.list ?? []).reduce((s, p) => s + Number(p.value), 0)
  check(trendSum === Number(tr.total) && trendSum === Number(ov.totalQuestions),
    '🔴 「7 天合计 = 提问总数」—— 趋势各点之和、趋势合计、四指标的提问总数**三者相等**',
    `${trendSum} = ${tr.total} = ${ov.totalQuestions}`)
  check((tr.list ?? []).filter((p) => p.weekend).length >= 1
    && (tr.list ?? []).filter((p) => p.today).length === 1,
    '周末标记与"今天"标记都由服务端算（颜色承载信息）')

  // ---------- ⑤ 三页同源（`G8-1` 后半）----------
  const p11 = bizOk(await api('/ops/qa/overview?range=7d'), '页 11 四指标')
  const p20 = bizOk(await api('/ops/retrieval/overview?range=7d'), '页 20 四指标')
  check(Number(p11.totalQuestions) === Number(p20.totalQuestions)
    && Number(p20.totalQuestions) === Number(ov.totalQuestions),
    '🔴 **三页的「提问总数」是同一个数**（需求 §3.4 那句「同一个数不要在两个页面各写一遍」）',
    `页11 ${p11.totalQuestions} = 页20 ${p20.totalQuestions} = 页21 ${ov.totalQuestions}`)

  // ---------- ⑥ 部门维度：三条合计（需求 §3.8）----------
  const dept = await usage('by-dept')
  check(Number(dept.questions) === Number(ov.totalQuestions),
    '🔴 §3.8：部门提问数合计 = 提问总数', `${dept.questions} vs ${ov.totalQuestions}`)
  check(Number(dept.active) === Number(ov.activeUsers),
    '🔴 §3.8：部门活跃用户合计 = 活跃用户总数', `${dept.active} vs ${ov.activeUsers}`)
  check(Number(dept.users) === Number(ov.totalUsers),
    '🔴 §3.8：部门人数列合计 = 用户总数', `${dept.users} vs ${ov.totalUsers}`)
  const rowActiveSum = (dept.list ?? []).reduce((s, d) => s + Number(d.activeUsers), 0)
  check(rowActiveSum === Number(dept.active), '逐行相加 == 那个合计（合计不是另算的一个数）',
    `${rowActiveSum} vs ${dept.active}`)
  check((dept.list ?? []).some((d) => Number(d.questions) === 0),
    '🔴 **没有提问的部门也出行** —— 否则「部门人数合计 = 用户总数」必然不成立')

  // ---------- ⑦ 库热度：与页 20 一致、合计 = 提问总数（需求 §3.4）----------
  const kb = await usage('by-kb')
  const kbq = bizOk(await api('/ops/retrieval/kb-quality?range=7d'), '页 20 各库排行')
  check(Number(kb.questions) === Number(kbq.questions)
    && Number(kb.othersQuestions) === Number(kbq.othersQuestions),
    '🔴 需求 §3.4：库热度与页 20 `kb-quality` **每一项都一致**（同一张汇总表的同一列）',
    `单库 ${kb.questions}/${kbq.questions} · 其他 ${kb.othersQuestions}/${kbq.othersQuestions}`)
  check(Number(kb.questions) + Number(kb.othersQuestions) === Number(ov.totalQuestions),
    '🔴 §3.4 的**订正版**：各库 + 「其他」== 提问总数（原文按字面要「每库一行」，'
      + '而跨库提问装不进任何一个库）',
    `${kb.questions} + ${kb.othersQuestions} = ${ov.totalQuestions}`)
  check(Number(kb.questions) === 0 && Number(kb.othersQuestions) > 0,
    '🔴 当前链路上**单库行恒为 0**（`ChatRequest` 没有 `kbIds`、提问页没有库选择器 ⇒ 每次提问覆盖全部可见库）'
      + ' —— 将来提问侧支持库范围时这条会转红，那正是要的',
    `单库 ${kb.questions} · 其他 ${kb.othersQuestions}`)

  // ---------- ⑧ 占比合计 = 100%（需求 §3.8 最后一条）----------
  const deptPct = (dept.list ?? []).reduce((s, d) => s + (d.percent ?? 0), 0)
  check(Math.abs(deptPct - 100) < 1e-9,
    '🔴 §3.8：部门占比列合计**恰好** 100.0（最大余数法，不是各行独立四舍五入）', `合计=${deptPct}`)
  const kbPct = (kb.list ?? []).reduce((s, k) => s + (k.percent ?? 0), 0) + Number(kb.othersPercent ?? 0)
  check(Math.abs(kbPct - 100) < 1e-9,
    '🔴 库占比合计（单库各行 + 「其他」那一行）**恰好** 100.0', `合计=${kbPct}`)

  // ---------- ⑨ 活跃用户排行 + `G8-9` 的可证伪边界 ----------
  const top = await usage('top-users')
  check((top.list ?? []).some((u) => Number(u.questions) >= 2),
    '本轮提问的超管出现在榜单里（次数 ≥ 2）',
    (top.list ?? []).slice(0, 3).map((u) => `${u.name}/${u.questions}`).join(' · '))
  check(Number(top.total) >= (top.list ?? []).length,
    'total 是**去重后的活跃用户总数**，不是 list.size()（榜外还有多少人靠它）',
    `total=${top.total} list=${top.list?.length}`)

  let auditorAggOk = true
  for (const ep of ['overview', 'trend', 'by-dept', 'by-kb']) {
    const r = await api(`/ops/usage/${ep}?range=7d`, { as: auditorToken })
    if (r.json?.code !== 0) auditorAggOk = false
  }
  check(auditorAggOk, '🔴 `G8-9`：AUDITOR 能读**四个聚合端点**（`ops:usage:view` 有它）')
  const auditorTop = await api('/ops/usage/top-users?range=7d', { as: auditorToken })
  check(auditorTop.json?.code !== 0,
    '🔴 `G8-9`：**同一个 AUDITOR token 读不到 `top-users`**（`ops:usage:users:view` 只给超管）'
      + ' —— 需求 §3.7「用户维度统计仅对管理员开放」的落点，这条边界可证伪',
    `code=${auditorTop.json?.code}`)

  // ---------- ⑪ 页面级：挂载 + 逐块对账 ----------
  // 🔴 **带 token 进页面**：前端从 localStorage 读 `ak_token`，而本脚本是直连后端登的录。
  // 不注入的话路由守卫会把页面赶回 `/#/login`，而现象是「运营页一直挂不上」（看着像接口坏了）。
  await goto(`${BASE}/#/login`)
  await evaluate(`localStorage.setItem('ak_token', ${JSON.stringify(token)}); localStorage.setItem('ak_remember','1'); 'ok'`)
  // 🔴 路由是 **hash 模式** ⇒ 必须写 `BASE/?boot=…#/ops/usage`
  await goto(`${BASE}/?boot=${Date.now()}#/ops/usage`)
  await waitFor(`window.document.querySelector('.page-title')?.innerText?.trim() === '使用统计'`,
    { timeout: 25000, label: '使用统计页挂载' })
  check(true, '页面挂载成功（真后端，非 mock）')

  // 🔴 五个请求的 HTTP 状态：不看它的话，"页面数据全是 0"会被误读成业务缺陷
  await sleep(1500)   // 等网络事件刷进来（它们在挂载那一刻发出，异步到达）
  const respCodes = usageResponses.map((r) => `${r.url}=${r.status}`)
  check(usageResponses.length >= 5 && usageResponses.every((r) => r.status === 200),
    '🔴 页面对五个 `/ops/usage/*` 的请求**都是 200**'
      + '（若有 401/403/404，下面那几条全会显示 0 而看不出原因）',
    respCodes.join(' · ') || '（一个都没捕到）')

  // 四指标与接口对账（页面读的是同一批数）
  const statText = await evaluate(`[...document.querySelectorAll('.stat-card')].map((c) => c.innerText.replace(/\\s+/g, ' ')).join(' | ')`)
  check(statText.includes(String(ov.activeUsers)) && statText.includes(String(ov.totalQuestions)),
    '🔴 四指标卡上的「活跃/总用户」与「提问总数」与接口**逐位一致**',
    statText.slice(0, 140))

  // 趋势柱数（7 个点）
  const bars7 = await evaluate(`document.querySelectorAll('.chart .bar-col').length`)
  check(bars7 === 7, '页面上趋势是 **7 根柱**（与接口的 7 个点一致）', `柱数=${bars7}`)

  // 部门表合计行与接口对账
  const sumText = await evaluate(`document.querySelector('.table-sum')?.innerText?.replace(/\\s+/g, ' ') ?? ''`)
  check(sumText.includes(String(dept.active)) && sumText.includes(String(dept.users))
    && sumText.includes(String(dept.questions)),
    '🔴 部门表的合计行与接口的 `active/users/questions` 三个数**逐位一致**', sumText)

  const bodyText = await evaluate('document.body.innerText')
  check(bodyText.includes('其他（跨库提问）'),
    '🔴 页面把「其他（跨库提问）」当成一行显示（当前链路上它就是全部）')
  check(bodyText.includes('暂时没有「单库提问」的行'),
    '🔴 库热度的**诚实空态**在（不是空白、也不是假数据）—— 说明"源不存在"而不是"数据还没到"')
  check(!bodyText.includes('45.6') && !bodyText.includes('37.9') && !bodyText.includes('刘强'),
    '🔴 mock 里那几个示例数字（45.6% / 37.9% / 刘强 43 次）**不在**真页面上')
  check(!bodyText.includes('导出任务已创建'),
    '🔴 「导出报表」的**假动作已销**（原来那条 toast 说了一件没有发生的事：一个请求都没发）')

  // ---------- ⑫ 时间范围**真的驱动**五块（页面级证据）----------
  await clickReal('.head-actions .el-select .el-select__wrapper')
  await waitFor(`[...document.querySelectorAll('.el-select-dropdown__item')]
      .some((i) => i.offsetParent !== null && i.innerText.includes('近 30 天'))`, { label: '时间范围下拉展开' })
  // 🔴 **用键盘选，不用点浮层**：Element Plus 的下拉是一个**传送到 body 的 popper**
  //（带动画与自动定位），而"先量 rect 再发鼠标事件"对它不可靠 ——
  // 本脚本就是那么写的，现象是**下拉开了、但选中没生效**（页面一个 30d 请求都没发）。
  // 鼠标事件没报错、也没有任何现象 —— 而"没选中"与"页面不重渔染"长得一样。
  // 键盘导航只依赖焦点，与几何无关（EP 的 Select 完整支持：高亮从当前值起算）。
  await send('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: 'ArrowDown', code: 'ArrowDown', windowsVirtualKeyCode: 40 })
  await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'ArrowDown', code: 'ArrowDown', windowsVirtualKeyCode: 40 })
  await sleep(120)
  await send('Input.dispatchKeyEvent', { type: 'rawKeyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 })
  await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 })
  // 🔴 两件事分开报："请求有没有发出去"与"柱子有没有变 30 根"——他们的修法完全不同
  //（前者：下拉交互没点上；后者：页面没重渲染）。本脚本第一版把两者合成一条，
  // 结果只得到一句"超时"而分不清哪边坏了。
  await sleep(1500)
  const asked30 = usageResponses.some((r) => r.url.includes('range=30d'))
  check(asked30,
    '🔴 切到「近 30 天」后页面**真的发了 range=30d 的请求**'
      + '（接真之前四个 API 方法把 range 写死 7d、页面也从不传 —— 换了不生效）',
    usageResponses.map((r) => `${r.url}=${r.status}`).join(' · '))
  const bars30 = await waitFor(`(() => { const n = document.querySelectorAll('.chart .bar-col').length; return n === 30 ? n : null })()`,
    { timeout: 20000, label: '切到 30 天后趋势变成 30 根柱' })
  check(bars30 === 30,
    '🔴 切到「近 30 天」后趋势变成 **30 根柱** ⇒ 下拉框真的驱动了请求'
      + '（接真之前四个 API 方法把 range 写死 7d、页面也从不传 —— 换了不生效，而页面看着完全正常）')

  // ---------- ④ `G8-10`：读的是汇总表（这段必须放在**最后**）----------
  // 🔴 为什么要先造一行**过去某天**的打点：真链路只会造**今天**的，而今天每次读都会重算（见
  // `OpsPrecomputeService` 的类注释）⇒ 命题其中的那一半不成立。单测用的就是过去某天的行（已证伪过）。
  mysqlScalar(
    `insert into sys_retrieval_trace (trace_id, query_text, user_id, dept_id, kb_ids, kb_count,`
      + ` miss_threshold, top1_score, rerank_applied, chunk_count, created_at) `
      + `values ('${HIST_TRACE}', '使用统计探针-历史', ${loginUserId}, 1, '1', 1,`
      + ` 0.35, 0.90, 1, 3, now() - interval 3 day);`)
  const dailyRows = mysqlCount('select count(*) from sys_ops_retrieval_daily;')
    + mysqlCount('select count(*) from sys_ops_usage_daily;')
  const beforeDelete = Number((await usage('overview')).totalQuestions)
  check(dailyRows > 0, '🔴 `G8-10`：读过一次之后**汇总表里有行**（预计算确实生效）', `汇总行数=${dailyRows}`)
  check(beforeDelete >= 3, '后加的那行历史打点也进了统计', `total=${beforeDelete}`)

  // 把**事实**删掉、只留缓存：今天那一天会被重算（归 0），而**历史那一天不会**
  mysqlScalar('delete from sys_retrieval_trace;')
  const afterDelete = Number((await usage('overview')).totalQuestions)
  check(Number(mysqlCount('select count(*) from sys_retrieval_trace;')) === 0,
    '明细表此刻是空的（下面那个数只可能来自汇总表）')
  check(afterDelete > 0,
    '🔴 `G8-10`：**打点删光了，历史那一天的读数还在** ⇒ 读路径读的是汇总表'
      + '（改回实时扫明细，这里会是 0）',
    `${beforeDelete} → ${afterDelete}（今天那部分叙旧地归 0，历史那行依然在）`)

  // ---------- ⑬ 收尾：**不在这里清** ----------
  // ⚠️ 清理只在 `finally` 里做一次（`#186`：清理必须走必走路径）。
}

// 守卫看的是**"本轮做没做事"这个布尔**，不是水位线抓到的值（表为空时它是 0，
// 而"表为空"正是干净基线上的常态 ⇒ 老写法会把整段清理跳过，见 `T-O19` 剩半）。
let askedThisRun = false

// ---------------------------------------------------------------- SSE
async function askSse(message) {
  const resp = await fetch(`${API}/api/chat/completions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify({ message }),
  })
  if (!resp.ok || !resp.body) throw new Error(`SSE 请求失败：HTTP ${resp.status}`)
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let done = false
  while (!done) {
    const r = await reader.read()
    if (r.done) break
    buffer += decoder.decode(r.value, { stream: true })
    let idx
    while ((idx = buffer.indexOf('\n\n')) !== -1) {
      const frame = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      if (frame.split('\n').some((l) => l.startsWith('event:') && l.includes('done'))) done = true
    }
  }
  check(done, `SSE 提问完成（${message}）`)
  // 会话 id 从打点表按**本轮问句**反查（比解析事件体稳 —— 事件的字段名换过一次）
  const sid = Number(mysqlScalar(
    `select session_id from sys_retrieval_trace where query_text = '${message.replaceAll("'", "''")}' `
      + `order by id desc limit 1;`))
  if (sid) sessionIds.add(sid)
}

// ---------------------------------------------------------------- 清理
/**
 * 清本脚本造的一切，并回报残留。
 *
 * <p>🔴 **必须能从 `finally` 里被调**（`#186`）。
 * <p>🔴 除了会话族，还要清**会话水位线够不着**的那几张：`purgeUserScopedArtifacts` 里
 * 现在含着 `sys_model_usage`（按日期）、`sys_qa_miss`（按人）、
 * 以及本批新增的两张**预计算汇总表**（按日期，纯缓存 ⇒ 清掉可重算）。
 */
async function cleanupProbe() {
  if (sessionIds.size) {
    const list = [...sessionIds].join(',')
    mysqlScalar(`delete from sys_retrieval_trace_chunk where trace_id in `
      + `(select trace_id from sys_retrieval_trace where session_id in (${list}));`)
    mysqlScalar(`delete from sys_retrieval_trace where session_id in (${list});`)
    mysqlScalar(`delete from sys_chat_message where session_id in (${list});`)
    mysqlScalar(`delete from sys_chat_session where id in (${list});`)
  }
  // ⚠️ `loginUserId > 0` 才清：登录都没成功时它还是 0，按 0 去删是**删别人**
  if (loginUserId > 0) {
    purgeUserScopedArtifacts(loginUserId)
  }
  // 本轮造的**跨会话**打点（两次提问之外的残留）：按探针问句点名清
  mysqlScalar(`delete from sys_retrieval_trace where query_text like '%使用统计探针%';`)

  const r = await teardownKbProbe(KB_CODE)
  // 独立观测点：**不看清理命令的返回值**，按本轮**精确的**会话 id 再查一次
  // 🔴 **不能**按 `sys_chat_session.user_id = 超管` 数 —— 超管名下还有**别人造的**会话
  //（真实使用、别的脚本、IDEA 调试那边的）⇒ 那个数永远不会归零，而现象是"每次都报有残留"。
  // 只数**本轮精确知道的那些 id**（与清理同一个集合 —— 独立观测点不等于另找一个范围）。
  const ids = [...sessionIds].join(',') || 0
  const left = mysqlCount(
    `select (select count(*) from sys_retrieval_trace where query_text like '%使用统计探针%') `
      + `+ (select count(*) from sys_qa_miss where last_user_id = ${loginUserId}) `
      + `+ (select count(*) from sys_chat_session where id in (${ids})) `
      + `+ (select count(*) from sys_chat_message where session_id in (${ids})) `
      + `+ (select count(*) from sys_retrieval_trace where session_id in (${ids})) `
      + `+ (select count(*) from sys_retrieval_trace where trace_id = '${HIST_TRACE}');`)
  return { residue: r.residue, left }
}

// ---------------------------------------------------------------- CDP 事件
function onCdpMessage(raw) {
  const msg = JSON.parse(raw.data)
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id)
    pending.delete(msg.id)
    msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result)
    return
  }
  // 🔴 **页面级脚本必须能回答"我那五个请求到底成没成"** ——
  // 否则"页面挂上了但数据全是 0"只能靠猜（本脚本第一版就是这么走的弯路）。
  if (msg.method === 'Network.responseReceived') {
    const { url, status } = msg.params.response
    if (url.includes('/ops/usage/')) usageResponses.push({ url: url.replace(/^.*\/api/, ''), status })
  }
  if (msg.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(msg.params.type)) {
    const text = (msg.params.args ?? []).map((a) => a.value ?? a.description ?? '').join(' ')
    consoleMsgs.push({ type: msg.params.type, text })
  }
}

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

async function connectCdp() {
  const targets = await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`).then((r) => r.json())
  const page = targets.find((t) => t.type === 'page')
  const socket = new WebSocket(page.webSocketDebuggerUrl)
  await new Promise((r) => { socket.onopen = r })
  socket.onmessage = onCdpMessage
  ws = socket
  await send('Page.enable')
  await send('Runtime.enable')
  await send('Network.enable')
  await send('Emulation.setDeviceMetricsOverride',
    { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
}

// ---------------------------------------------------------------- 入口
let edge
try {
  rmSync(PROFILE, { recursive: true, force: true })
  mkdirSync(PROFILE, { recursive: true })
  const started = await startEdge()
  edge = started.child
  observe('Edge 已启动', started.version)
  await connectCdp()
  await main()
} catch (e) {
  check(false, '脚本抛错', e.message)
} finally {
  // 🔴 清理放在 `finally` 里（`#186`）—— 上面任何一步抛错都要走到
  try {
    const { residue, left } = await cleanupProbe()
    const clean = left === 0 && kbResidueClean(residue)
    console.log(`${clean ? '✓' : '✗'} 收尾清理 · 残留 ${left} 行 · 探针库 ${describeKbResidue(residue)}`)
    if (!clean) process.exitCode = 1
  } catch (e) {
    console.log(`✗ 收尾清理抛错：${e.message}`)
    process.exitCode = 1
  }
  const bad = consoleMsgs.filter((m) => m.type === 'error')
  const total = steps.length
  const failed = steps.filter((s) => !s.ok).length
  console.log(`\n结果：${total - failed}/${total} 通过 · console error ${bad.length} 条`)
  if (bad.length) bad.slice(0, 5).forEach((m) => console.log(`  [console.error] ${m.text.slice(0, 200)}`))
  if (failed) process.exitCode = 1
  if (edge) edge.kill()
  // 显式退出（WebSocket / 子进程句柄会挂住事件循环）
  process.exit(process.exitCode ?? 0)
}
