/**
 * `S8-M10-C` 页面级 E2E：问答运营页的**高频问题榜 / 点踩原因归因 / 导出报表**切真后端（`T-A02`）。
 *
 * <h3>覆盖的判据</h3>
 * <ul>
 *   <li><b>`G8-2` 半边</b> —— 高频榜按**归一化**聚合：同一句话用**两种标点**问两次 ⇒ 榜单**一行、次数 2**。
 *       ⚠️ 不是插两行假数据：本脚本走「上传 → 解析 → 索引 → 真提问」的完整链路，打点是链路自己产的；</li>
 *   <li>需求 §1.10 —— 榜单展示的是**最近一次**的原始问法（不是归一化后的键）；</li>
 *   <li>需求 §1.6 —— 点踩归因：真点一次踩 ⇒ 那一档 +1，且三个数（原因合计 / 未填原因 / 总数）对得上；</li>
 *   <li>需求 §1.7 与 `G6-12` —— 页面上点「导出报表」⇒ **任务真跑**、CSV 真落 MinIO、**通知带真下载地址**；</li>
 *   <li>`T-O19` —— 本脚本造的那几张表（含**会话水位线够不着**的用量/池子）收尾**自己清**。</li>
 * </ul>
 *
 * <h3>🔴 为什么必须走真链路造数据</h3>
 * 两个接口的全部价值就在于**聚合**（分组、加权均值、分母口径）。往表里插两行假数据的话，
 * 验的只是"SQL 能跑"——而这一批最容易坏的地方（归一化有没有生效、均值分母是"有分数行"
 * 还是"全部行"、负反馈率的分母是不是提问总数）**一条都验不到**。
 * 开工实测：`sys_retrieval_trace` / `sys_chat_message` **都是 0 行**，所以"库里正好有数据"
 * 这条路本来也不存在。
 *
 * <h3>技术路线</h3>
 * 与 `e2e-notice-flow.mjs` / `e2e-ops-miss-flow.mjs` 同：Node 自带 WebSocket 直驱无头 Edge
 * 的 CDP，**零新增依赖**；断言看业务 `code` 不看 HTTP 码。
 *
 * <h3>自清纪律</h3>
 * 探针库走 `teardownKbProbe`；**池子/打点/会话/消息/用量**由本脚本按**会话水位线 +
 * 精确 session_id** 删（提问人是种子超管 `admin`，他还在 ⇒ 无主兜底够不着）；
 * 导出产生的**任务行 / 通知行 / MinIO 对象**按**精确 taskId / objectKey** 删
 * （`T-O19`：会造行的脚本必须自己认领）。
 *
 * <h3>前置</h3>
 * <pre>
 *   后端 8080：mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
 *   前端 5174：VITE_USE_MOCK=false npm run dev -- --host 127.0.0.1 --port 5174
 *   运行：node scripts/e2e-ops-qa-stats.mjs --base=http://127.0.0.1:5174
 * </pre>
 *
 * ⚠️ **本文件没有 shebang**：本机 shell 的路径守卫会拦下含 `#!` 的内容。
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, rmSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import {
  mysqlScalar,
  mysqlCount,
  teardownKbProbe,
  purgeUserScopedArtifacts,
  minioRemove,
  minioKeysOfPathUserPrefix,
  kbResidueClean,
  describeKbResidue,
} from './lib/probe-teardown.mjs'

const arg = (name, fallback) => (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://localhost:5174')
const API = arg('api', 'http://localhost:8080')

const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-e2e-qastats'
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9341)

/** 固定账号：种子超管（`ops:qa:view` + `ops:qa:handle` 都有）。 */
const PROBE = { account: 'admin', password: 'Admin@123' }

/**
 * 探针问句 —— **两种标点写法**，用来验"归一化聚合"。
 *
 * ⚠️ 问的东西必须是**库里没有的**：问了库里有的内容就不会未命中，池子那半边会空；
 * 而问的两次必须**只差标点**，否则测的是"语义聚合"，那是本批**不做**的那一半（`G8-2` 挂起半边）。
 */
const Q_PLAIN = '量子纠缠的贝尔不等式怎么推导'
const Q_MARK = '量子纠缠的贝尔不等式怎么推导？？'

const stamp = Date.now()
const KB_CODE = `kb_e2estats_${stamp}`

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
async function api(path, { method = 'GET', body, raw = false } = {}) {
  const res = await fetch(`${API}/api${path}`, {
    method,
    headers: {
      ...(raw ? {} : body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
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

// ---------------------------------------------------------------- CDP
let ws
let msgId = 0
const pending = new Map()
const consoleMsgs = []

function send(method, params = {}) {
  const id = ++msgId
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject })
    ws.send(JSON.stringify({ id, method, params }))
  })
}
async function evaluate(expression) {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (r.exceptionDetails) throw new Error(`页面脚本抛错：${r.exceptionDetails.text ?? ''} ${r.exceptionDetails.exception?.description ?? ''}`)
  return r.result?.value
}
async function waitFor(expression, { timeout = 20000, interval = 200, label = expression } = {}) {
  const deadline = Date.now() + timeout
  let last
  while (Date.now() < deadline) {
    try {
      const v = await evaluate(expression)
      if (v) return v
      last = v
    } catch (e) {
      last = e.message
    }
    await sleep(interval)
  }
  throw new Error(`等待超时（${timeout}ms）：${label}（最后一次求值：${JSON.stringify(last)}）`)
}
async function goto(url) {
  await send('Page.navigate', { url })
  await sleep(800)
  await evaluate(PAGE_TOOLS)
}

/**
 * 页面里能用的探针小工具。
 *
 * ⚠️ **每次导航之后都要重新注入** —— 导航会清掉上一次的注入，而现象是
 * 「探针说找不到元素」，看着像页面没渲染。
 * ⚠️ 取「可见的那个」而不是 `[0]`：Element Plus 的 tab 面板**全部渲染在 DOM 里**，
 * 隐藏的那些同样能被 `querySelectorAll` 选中（`#160` 的判例）。
 */
const PAGE_TOOLS = `
window.__m = {
  txt: (sel) => document.querySelector(sel)?.innerText?.trim() ?? null,
  byText: (tag, text) => [...document.querySelectorAll(tag)].find((e) => ((e.innerText ?? '').includes(text)) && e.offsetParent !== null) ?? null,
  clickText: (tag, text) => {
    const el = window.__m.byText(tag, text)
    if (!el) return false
    el.click()
    return true
  },
  /** 可见的 tab 面板里的文本 */
  panel: () => [...document.querySelectorAll('.el-tab-pane')].filter((p) => p.offsetParent !== null).map((p) => p.innerText).join('\\n'),
}
'ok'`

// ---------------------------------------------------------------- 主流程
let edge
let kbId
let sessionFloor = 0
/**
 * 🔴 **本轮到底问没问过** —— 收尾的守卫看**它**，不看 `sessionFloor` 的值。
 *
 * 由来（`T-O19` 剩半，`S8-M10-D` 现场定位）：水位线取 `coalesce(max(id), 0)`，
 * 而 **表为空时它就是 0** —— 而“表为空”正是本仓**基线干净时的常态**（每批收尾都把会话族清空）。
 * 于是 `if (sessionFloor)` 把“干净的起点”误判成“没有活要干”，**整段清理被跳过**，
 * 而那恰恰是在干净环境里**必然发生**的（脏环境里反而不发生 ⇒ 偶发得像环境问题）。
 *
 * ⚠️ `sessionFloor` 的**值**仍然是对的（`delete ... where session_id > floor` 用它当阈值），
 * 错的是**拿它当守卫** —— 守卫要问“本轮做没做事”，不是“我抓到的那个值是多少”。
 */
let askedThisRun = false
/** 本轮提问**精确**的会话 id（不靠水位线 —— 见 `S8-M10-B` 那次"水位线以下没人认领"的教训）。 */
const sessionIds = new Set()
/** 本轮造出来的导出任务 id（收尾要精确删任务行 + 通知行 + 桶里那个对象）。 */
const exportTaskIds = new Set()

async function main() {
  // ---------- ① 登录 ----------
  const loginData = bizOk(await api('/auth/login', { method: 'POST', body: { ...PROBE, remember: true } }), '登录')
  token = loginData.accessToken
  loginUserId = Number(loginData.user?.id ?? 0)
  check(!!token && loginUserId > 0, '登录成功（种子超管）', `userId=${loginUserId}`)

  // ---------- ② 建探针库 ----------
  const created = bizOk(await api('/kb', {
    method: 'POST',
    body: {
      name: `运营统计探针库 ${stamp}`,
      code: KB_CODE,
      deptId: 1,
      visibleRoles: ['USER'],
      businessDomain: '技术文档',
      description: 'S8-M10-C 高频榜/点踩归因/导出 页面级联调探针，脚本结束即删',
    },
  }), '建库')
  kbId = created.id
  check(!!kbId, `探针库已建 · id=${kbId}`)

  // ---------- ③ 上传一篇真内容并等索引 ----------
  const content = [
    '# 季度报价单（2026Q3）',
    '',
    '型号 A-100 单价 1800 元，交期 7 天；型号 A-200 单价 2400 元，交期 15 天。',
  ].join('\n')
  const form = new FormData()
  form.append('file', new Blob([content], { type: 'text/markdown' }), `e2estats-${stamp}.md`)
  bizOk(await api(`/kb/${kbId}/doc/upload`, { method: 'POST', body: form, raw: true }), '上传')

  const deadline = Date.now() + 120000
  let lastStatus = ''
  while (Date.now() < deadline) {
    const docs = bizOk(await api(`/kb/${kbId}/docs?page=1&size=10`), '查文档')
    lastStatus = (docs.list ?? [])[0]?.status ?? '（还没行）'
    if (lastStatus === 'READY') break
    if (lastStatus === 'FAILED') throw new Error('文档解析失败')
    await sleep(2000)
  }
  check(lastStatus === 'READY', `文档已就绪（状态 ${lastStatus}）`)

  // ---------- ④ 同一句话问两次（只差标点）→ 高频榜应当只有一行、次数 2 ----------
  askedThisRun = true
  sessionFloor = mysqlCount('select coalesce(max(id), 0) from sys_chat_session;')
  const first = await askSse(Q_PLAIN)
  const second = await askSse(Q_MARK)
  check(!!first.messageId && !!second.messageId, '两次提问都拿到了 done.messageId',
    `${first.messageId} / ${second.messageId}`)

  const traces = mysqlCount(
    `select count(*) from sys_retrieval_trace where query_text like '%贝尔不等式%';`)
  check(traces >= 2, '两次提问都落了打点（高频榜的"次数"才有来源）', `打点行数=${traces}`)

  // ---------- ⑤ 点踩一次（带原因）→ 归因那一档 ----------
  // ⚠️ 必须点**回答**（`role='assistant'`）：`ChatFeedbackService` 拦掉了给提问点赞/踩
  const fb = bizOk(await api('/chat/feedback', {
    method: 'POST',
    body: { messageId: first.messageId, type: 'unlike', reason: '没找到相关内容' },
  }), '点踩')
  check(fb.feedback === 'unlike' && fb.reason === '没找到相关内容', '点踩已落库（带原因）')

  // ---------- ⑥ 接口层：高频榜的归一化聚合 ----------
  const top = bizOk(await api('/ops/qa/top-questions?range=7d&limit=20'), '高频榜')
  const row = (top.list ?? []).find((t) => (t.question ?? '').includes('贝尔不等式'))
  check(!!row, '榜单里出现这两次提问合成的那一行', row ? `次数=${row.count}` : '')
  check(row?.count >= 2, '**同一句话的两种标点写法合并成一行、次数累加**（归一化聚合）', `count=${row?.count}`)
  check(row?.question === Q_MARK,
    '展示的是**最近一次**的原始问法（需求 §1.10：保持原始问法，未被改写）',
    `实际=${row?.question}`)

  // ---------- ⑦ 接口层：点踩归因的三个数 ----------
  const stats = bizOk(await api('/ops/qa/feedback-stats?range=7d'), '点踩归因')
  const hit = (stats.reasons ?? []).find((r) => r.reason === '没找到相关内容')
  check(!!hit, '「没找到相关内容」那一档在（闭集逐条都在）')
  check((stats.reasons ?? []).length >= 5, '闭集那 5 条都在，没出现的原因补 0', `档数=${stats.reasons?.length}`)
  const summed = (stats.reasons ?? []).reduce((s, r) => s + Number(r.count ?? 0), 0)
  check(summed + Number(stats.unreasoned ?? 0) === Number(stats.negativeTotal),
    '🔴 三个数对得上：原因合计 + 未填原因 = 负反馈总数（需求 §1.10 那条等式的**正确形状**）',
    `${summed} + ${stats.unreasoned} vs ${stats.negativeTotal}`)

  const overview = bizOk(await api('/ops/qa/overview?range=7d'), '四指标')
  const expectedRate = overview.totalQuestions
    ? Math.round((Number(stats.negativeTotal) * 1000) / overview.totalQuestions) / 10
    : 0
  check(Number(stats.negativeRate) === expectedRate,
    '负反馈率的分母是**提问总数**（与 overview 同源）',
    `${stats.negativeRate} vs ${expectedRate}`)

  // ---------- ⑧ 页面级：两个 Tab 真的显示这些数 ----------
  // 🔴 **带 token 进页面**：前端从 localStorage 读 `ak_token`，而本脚本是直连后端登的录。
  // 不注入的话路由守卫会把页面赶回 `/#/login`，而现象是「运营页一直挂不上」——
  // 看着像接口坏了（`e2e-ops-miss-flow` 的同一段注释记过这件事）。
  await goto(`${BASE}/#/login`)
  await evaluate(`localStorage.setItem('ak_token', ${JSON.stringify(token)}); localStorage.setItem('ak_remember','1'); 'ok'`)
  // 🔴 路由是 **hash 模式** ⇒ 必须写 `BASE/?boot=…#/ops/qa`。
  // 写成 `BASE/ops/qa` 的话服务端照样回 index.html，而 hash 为空 ⇒ 落到默认路由再重定向
  // ⇒ 地址栏变成 `/ops/qa#/chat`、**页面停在知识问答**（实测踩过一次）
  await goto(`${BASE}/?boot=${Date.now()}#/ops/qa`)
  await waitFor(`window.__m.txt('.page-title') === '问答运营'`,
    { timeout: 25000, label: '运营页挂载' })

  check(!!(await waitFor(`window.__m.clickText('.el-tabs__item', '高频问题榜')`, { label: '切到高频榜 Tab' })),
    '页面切到「高频问题榜」Tab')
  const topPanel = await waitFor(
    `window.__m.panel().includes('贝尔不等式') && window.__m.panel()`,
    { label: '高频榜渲染出那一行' })
  check(topPanel.includes('贝尔不等式'), '页面上出现那一行（真数据，不是 mock 的 8 行占位）')
  check(!/分片策略是怎么设计的/.test(topPanel),
    '🔴 mock 的占位问题**不在**页面上（那 8 行是 S8 之前的假数据，接口接上后必须消失）')

  check(!!(await waitFor(`window.__m.clickText('.el-tabs__item', '点踩原因归因')`, { label: '切到点踩归因 Tab' })),
    '页面切到「点踩原因归因」Tab')
  const fbPanel = await waitFor(
    `window.__m.panel().includes('没找到相关内容') && window.__m.panel()`,
    { label: '点踩归因渲染出那一档' })
  check(fbPanel.includes('没找到相关内容'), '页面上出现那一档（真数据）')
  check(fbPanel.includes('校验口径'),
    '页面写出了三数校验口径（原因合计 + 未填原因 = 总数）—— 否则用户会以为账错了')

  // ---------- ⑨ 页面级：点「导出报表」→ 任务 + 通知 ----------
  check(await evaluate(`window.__m.clickText('button', '导出报表')`), '页面上点了「导出报表」')

  // 轮询任务行：类型 `ops-export` 且提交人是本账号
  let taskId = ''
  const taskDeadline = Date.now() + 10000
  while (Date.now() < taskDeadline && !taskId) {
    taskId = mysqlScalar(
      `select task_id from sys_async_task where type = 'ops-export' and owner_id = ${loginUserId} `
        + `order by created_at desc limit 1;`)
    if (!taskId) await sleep(300)
  }
  check(!!taskId, '导出任务真的建了（页面那一按不是假启动）', `taskId=${taskId}`)
  if (taskId) exportTaskIds.add(taskId)

  const owner = taskId
    ? mysqlScalar(`select owner_id from sys_async_task where task_id = '${taskId}';`)
    : ''
  check(String(owner) === String(loginUserId), '任务行 owner_id 非空且是提交人（承 `#110`）', `owner=${owner}`)

  // 等终态：真跑完、真落 MinIO
  let status = ''
  const doneDeadline = Date.now() + 30000
  while (Date.now() < doneDeadline) {
    status = mysqlScalar(`select status from sys_async_task where task_id = '${taskId}';`)
    if (status === 'DONE' || status === 'FAILED') break
    await sleep(500)
  }
  check(status === 'DONE', '导出任务真跑到 DONE', `status=${status}`)

  // 通知：`G6-12` 要的是**带可直达动作**（真下载地址）
  let actions = ''
  const noticeDeadline = Date.now() + 8000
  while (Date.now() < noticeDeadline && !actions) {
    actions = mysqlScalar(`select actions_json from sys_notice where source_task_id = '${taskId}';`)
    if (!actions) await sleep(300)
  }
  check(!!actions, '导出完成后通知落库（`G6-12`：重新加载后仍在，toast 做不到）')
  check(/(ops-export\/)/.test(actions), '通知里的动作是**真下载地址**（落在 `ops-export/` 前缀下）')

  // 页面级：重新加载后那条通知还在。
  // `?boot=` 强制**整页重载**（只改 hash 不会重跑应用）—— 这一条的判词就是
  // 「重新加载后那条通知**仍在**」，而 toast 做不到这件事。
  // ⚠️ 路由是 **`/notice`（单数）** —— 写成 `/notices` 会落到默认路由，
  // 然后 `document.body.innerText` 里当然没有那句话（本脚本第一版就是这么写错的）
  await goto(`${BASE}/?boot=${Date.now()}#/notice`)
  const noticePage = await waitFor(
    `document.body.innerText.includes('运营报表') && document.body.innerText`,
    { timeout: 15000, label: '通知页渲染出那条运营报表通知' })
  check(noticePage.includes('运营报表'), '重新加载通知页后那条**仍在**（toast 做不到这件事）')

  // ---------- ⑩ 收尾 ----------
  const { residue } = await cleanupProbe()
  check(kbResidueClean(residue), `探针库已清（${describeKbResidue(residue)}）`)
}

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
  let payload = {}
  while (!done) {
    const r = await reader.read()
    if (r.done) break
    buffer += decoder.decode(r.value, { stream: true })
    let idx
    while ((idx = buffer.indexOf('\n\n')) !== -1) {
      const frame = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      const lines = frame.split('\n')
      if (lines.some((l) => l.startsWith('event:') && l.includes('done'))) {
        done = true
        const dataLine = lines.find((l) => l.startsWith('data:'))
        if (dataLine) {
          try {
            payload = JSON.parse(dataLine.slice(5).trim())
          } catch {
            /* done 的 data 解析不出来时 payload 留空，由下面的断言报出来 */
          }
        }
      } else if (lines.some((l) => l.startsWith('event:') && l.includes('retrieval'))) {
        // 打点行的 session_id 在这里 —— 记下来，收尾按**精确会话**删
        const dataLine = lines.find((l) => l.startsWith('data:'))
        if (dataLine) {
          try {
            const retrieval = JSON.parse(dataLine.slice(5).trim())
            void retrieval
          } catch {
            /* 忽略 */
          }
        }
      }
    }
  }
  check(done, `SSE 提问完成（${message}）`)
  // 会话 id 从打点表按**本轮问句**反查（比解析事件体稳 —— 事件的字段名换过一次）
  const sid = Number(mysqlScalar(
    `select session_id from sys_retrieval_trace where query_text = '${message.replaceAll("'", "''")}' `
      + `order by id desc limit 1;`))
  if (sid) sessionIds.add(sid)
  return payload
}

// ---------------------------------------------------------------- 清理
/**
 * 清本脚本造的一切，并回报残留。
 *
 * <p>🔴 **必须能从 `finally` 里被调**（`#186`：`step()` 一抛就不执行的那条教训）。
 * <p>🔴 `T-O19`：除了会话族，还要清**会话水位线够不着**的那几张
 * （`sys_model_usage` 按日期 / `sys_qa_miss` 按人）—— 本脚本真提问、真点踩、真导出。
 */
async function cleanupProbe() {
  // ① 导出产物：通知行 → 任务行（按**精确 taskId**）
  for (const taskId of exportTaskIds) {
    mysqlScalar(`delete from sys_notice where source_task_id = '${taskId}';`)
    mysqlScalar(`delete from sys_async_task where task_id = '${taskId}';`)
  }
  // 桶里 `ops-export/{userId}/…` 的对象：**按账号前缀列全量**再删。
  // ⚠️ 不记"我知道的那一个" —— 那正是 `#163` 漏的成因（脚本每轮删掉该用户全部行，
  //    于是上一轮的对象在下一次跑的时候就没人指了）。
  // ⚠️ `sys_async_task.result` **不持久化**，所以 objectKey 拿不回来 ⇒ 只能按前缀扫。
  if (loginUserId) {
    const keys = minioKeysOfPathUserPrefix('ops-export/', [loginUserId])
    if (keys.length) {
      minioRemove(keys)
      observe('导出对象已收', `${keys.length} 个`)
    }
  }

  // ② 会话族：先子后父。有精确 sessionId 就按它删，否则退回水位线
  if (sessionIds.size) {
    const list = [...sessionIds].join(',')
    mysqlScalar(`delete from sys_retrieval_trace_chunk where trace_id in `
      + `(select trace_id from sys_retrieval_trace where session_id in (${list}));`)
    mysqlScalar(`delete from sys_retrieval_trace where session_id in (${list});`)
    mysqlScalar(`delete from sys_chat_message where session_id in (${list});`)
    mysqlScalar(`delete from sys_chat_session where id in (${list});`)
  }
  if (askedThisRun) {
    mysqlScalar(`delete from sys_retrieval_trace_chunk where trace_id in `
      + `(select trace_id from sys_retrieval_trace where session_id > ${sessionFloor});`)
    mysqlScalar(`delete from sys_retrieval_trace where session_id > ${sessionFloor};`)
    mysqlScalar(`delete from sys_chat_message where session_id > ${sessionFloor};`)
    mysqlScalar(`delete from sys_chat_session where id > ${sessionFloor};`)
  }

  // ③ 池子 + 用量（按 id / 按日期）—— 会话水位线够不着它们
  purgeUserScopedArtifacts(loginUserId)

  // ④ 探针库
  const r = await teardownKbProbe(KB_CODE)

  // ⑤ 独立观测点：不看清理命令的返回值，直接再查一次
  const left = mysqlCount(
    `select (select count(*) from sys_chat_session where user_id = ${loginUserId} and id > ${sessionFloor}) `
      + `+ (select count(*) from sys_chat_message where session_id > ${sessionFloor}) `
      + `+ (select count(*) from sys_retrieval_trace where session_id > ${sessionFloor}) `
      + `+ (select count(*) from sys_async_task where type = 'ops-export' and owner_id = ${loginUserId}) `
      + `+ (select count(*) from sys_qa_miss where last_user_id = ${loginUserId});`)
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
  try {
    if (ws) ws.close()
    if (edge) edge.kill()
    rmSync(PROFILE, { recursive: true, force: true })
  } catch {
    /* 清不动就算了，下次跑会用新的 profile */
  }
  const failed = steps.filter((s) => !s.ok)
  console.log(`\n===== ${steps.length - failed.length}/${steps.length} 通过 =====`)
  if (failed.length) {
    console.log('失败项：')
    for (const f of failed) console.log(`  ✗ ${f.label}${f.detail ? ` · ${f.detail}` : ''}`)
    process.exitCode = 1
  }
  const errs = consoleMsgs.filter((m) => m.type === 'error')
  console.log(`控制台 error ${errs.length} 条${errs.length ? `：${errs.map((m) => m.text).join(' | ').slice(0, 300)}` : ''}`)
  if (!existsSync(PROFILE)) { /* 已删 */ }
  process.exit(process.exitCode ?? 0)
}
