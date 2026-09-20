/**
 * `S8-M10-D` 页面级 E2E：检索质量分析页（页 20）接真后端（`T-A03`）。
 *
 * <h3>覆盖的判据</h3>
 * <ul>
 *   <li><b>`G8-6`</b> 链路耗时分解：页面上三段相加恰等于全链路
 *       （`检索侧 + 生成 + 其他 = 全链路`），且**页面显示的数与接口回的数一致**；</li>
 *   <li><b>`G8-7`</b> Top1 五档分布：**六档**（含「无法定档」）逐档与接口对账，合计 = 提问总数；</li>
 *   <li>需求 §2.4：重排前后的两个比率（覆盖不到时如实为空）；</li>
 *   <li>需求 §2.8 最后一条：**没有数据时页面显示「数据不足」而不是 0**；</li>
 *   <li>需求 §2.5 的归属口径：**跨库提问计入「其他」**（本脚本实测它当前就是全部 —— 见下）。</li>
 * </ul>
 *
 * <h3>🔴 为什么走真链路造打点</h3>
 * 这一页的全部价值在聚合。开工实测 `sys_retrieval_trace` 是 **0 行** ⇒ 不造行就只能验"空的时候不报错"。
 * 本脚本走「建库 → 上传 → 等索引 → 真提问」的完整链路，打点是链路自己产的。
 *
 * <h3>🔴 一个当场撞上的产品事实（不是脚本的毛病）</h3>
 * `ChatRequest` **没有 `kbIds`**、`ChatStreamService#chat` 硬传 `null`、提问页也没有库选择器
 * ⇒ **每次提问覆盖全部可见库** ⇒ `kb_count` 恒 > 1 ⇒ 需求 §2.5 的「各库排行」
 * **在当前链路上永远只有「其他」一行**（每句话都同时属于每个库，"按库排行"本身就无从谈起）。
 * 脚本把这条**断言下来**：`questions == 0 && othersQuestions > 0`。
 * ⚠️ 将来提问侧支持了库范围，这两条会**转红** —— 那正是要的：它逼着人来更新这条判据。
 *
 * <h3>技术路线与自清</h3>
 * 与 `e2e-ops-qa-stats.mjs` / `e2e-notice-flow.mjs` 同：Node 自带 WebSocket 直驱无头 Edge 的 CDP，
 * 零新增依赖；断言看业务 `code` 不看 HTTP 码。收尾清会话族（按**精确 session_id**）+
 * `purgeUserScopedArtifacts`（会话水位线够不着的那几张）+ 探针库。
 *
 * <h3>前置</h3>
 * <pre>
 *   后端 8080：mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
 *   前端 5174：VITE_USE_MOCK=false npm run dev -- --host 127.0.0.1 --port 5174
 *   运行：node scripts/e2e-ops-retrieval-flow.mjs --base=http://127.0.0.1:5174
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
  kbResidueClean,
  describeKbResidue,
} from './lib/probe-teardown.mjs'

const arg = (name, fallback) => (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://localhost:5174')
const API = arg('api', 'http://localhost:8080')

const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-e2e-retrieval'
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9344)

/** 固定账号：种子超管（`ops:retrieval:view` **只给超管**，见 `V35`）。 */
const PROBE = { account: 'admin', password: 'Admin@123' }

/** 探针问句：与库里已有的内容**不重合**（重复的话分不出"这次检索"与"上次"）。 */
const Q_ONE = '检索质量探针 甲乙丙丁 戊己庚辛'
const Q_TWO = '检索质量探针 甲乙丙丁 戊己庚辛？？'

const stamp = Date.now()
const KB_CODE = `kb_e2eretr_${stamp}`

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

/** 页 20 的五个端点（同一个窗口）。 */
const retrieval = async (ep, range = '7d') => bizOk(await api(`/ops/retrieval/${ep}?range=${range}`), ep)

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
async function goto(url) {
  await send('Page.navigate', { url })
  await sleep(600)
}
async function waitFor(expression, { timeout = 20000, label = '条件' } = {}) {
  const t0 = Date.now()
  for (;;) {
    const v = await evaluate(`(() => { try { return (${expression}) } catch { return null } })()`)
    if (v) return v
    if (Date.now() - t0 > timeout) throw new Error(`等待超时：${label}`)
    await sleep(250)
  }
}

// ---------------------------------------------------------------- 主流程
let edge
let kbId
/** 本轮提问**精确**的会话 id（不靠水位线 —— `S8-M10-B` 那次"水位线以下没人认领"的教训）。 */
const sessionIds = new Set()

async function main() {
  // ---------- ① 登录 ----------
  const loginData = bizOk(await api('/auth/login', { method: 'POST', body: { ...PROBE, remember: true } }), '登录')
  token = loginData.accessToken
  loginUserId = Number(loginData.user?.id ?? 0)
  check(!!token && loginUserId > 0, '登录成功（种子超管）', `userId=${loginUserId}`)

  // ---------- ② 建探针库 + 上传一篇真内容并等索引 ----------
  const created = bizOk(await api('/kb', {
    method: 'POST',
    body: {
      name: `检索质量探针库 ${stamp}`,
      code: KB_CODE,
      deptId: 1,
      visibleRoles: ['USER'],
      businessDomain: '技术文档',
      description: 'S8-M10-D 检索质量分析 页面级联调探针，脚本结束即删',
    },
  }), '建库')
  kbId = created.id
  check(!!kbId, `探针库已建 · id=${kbId}`)

  const content = [
    '# 分片与召回设计',
    '',
    '系统的分片方案按语义边界切分，容量规划以 512 token 为目标；召回阶段先走向量再走关键词。',
  ].join('\n')
  const form = new FormData()
  form.append('file', new Blob([content], { type: 'text/markdown' }), `e2eretr-${stamp}.md`)
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

  // ---------- ③ 真提问两次（同一句话，只差标点）----------
  const before = await retrieval('overview')
  await askSse(Q_ONE)
  await askSse(Q_TWO)

  const traces = mysqlCount(
    `select count(*) from sys_retrieval_trace where query_text like '%检索质量探针%';`)
  check(traces >= 2, '两次提问都落了打点（页面上的"提问总数"才有来源）', `打点行数=${traces}`)

  // ---------- ④ 接口层：三段恒等式（`G8-6`）----------
  const pipe = await retrieval('pipeline')
  const sum = Number(pipe.retrievalSideMs) + Number(pipe.generationMs) + Number(pipe.otherMs)
  const drift = Math.abs(sum - Number(pipe.totalMs))
  // ⚠️ 容差 2ms 是**取整误差**，不是逻辑松口：四项各自 `Math.round(平均值)`，
  // 而真实平均值带小数 ⇒ 误差上界 = 3×0.5 + 0.5 = 2ms（精确值之间**恒等**，
  // 因为 `otherMs` 是同一行集上独立算的残差 `avg(total − 检索侧 − 生成)`）。
  // 🔴 判据力不减：把 `otherMs` 去掉（那正是要防的偷懒写法）会差**几百**毫秒。
  // 后端单测用的是**无小数**的构造数据（145+1000+25 = 1170），所以那边是精确等号。
  check(drift <= 2,
    '🔴 `G8-6`：检索侧 + 生成 + 其他 = 全链路（三段独立算出来，不是拿总数减的余额；容差 2ms = 取整）',
    `${pipe.retrievalSideMs} + ${pipe.generationMs} + ${pipe.otherMs} = ${sum} vs ${pipe.totalMs}（差 ${drift}ms）`)
  check(Array.isArray(pipe.list) && pipe.list.length === 9,
    '九段齐全（段名与检索详情面板同源）', `段数=${pipe.list?.length}`)
  check(Number(pipe.pipelineRows) + Number(pipe.skippedNoGeneration) > 0,
    '「跑完全链路 / 被排除」两个数都在（空召回不调模型 ⇒ 生成耗时对它不存在）',
    `rows=${pipe.pipelineRows} · skipped=${pipe.skippedNoGeneration}`)

  // ---------- ⑤ 接口层：分档合计与跨接口对账（`G8-7`）----------
  const dist = await retrieval('score-distribution')
  const bucketSum = (dist.list ?? []).reduce((s, b) => s + Number(b.count ?? 0), 0)
  check(bucketSum === Number(dist.total),
    '🔴 `G8-7`：各档合计 = 提问总数（含「无法定档」那一档）',
    `${bucketSum} vs ${dist.total}`)
  check((dist.list ?? []).some((b) => b.label === '无法定档'),
    '第 6 档「无法定档」在（空召回 + 未重排装不进需求那五档）')
  check(Number(dist.unscored) === Number(dist.noRerank) + Number(dist.emptyRecall),
    '「无法定档」的两个成因分开给，且加起来等于总数',
    `unscored=${dist.unscored} = noRerank ${dist.noRerank} + emptyRecall ${dist.emptyRecall}`)

  const overview = await retrieval('overview')
  check(Number(overview.noRerankQuestions) + Number(overview.emptyRecallQuestions)
    + Number(overview.comparableQuestions) === Number(overview.totalQuestions),
    'overview 的三个子集加起来 = 提问总数（可比 / 未重排 / 空召回 是划分）',
    `${overview.comparableQuestions} + ${overview.noRerankQuestions} + ${overview.emptyRecallQuestions} vs ${overview.totalQuestions}`)

  const fiveBuckets = (dist.list ?? [])
    .filter((b) => b.label !== '无法定档')
    .reduce((s, b) => s + Number(b.count ?? 0), 0)
  check(fiveBuckets === Number(overview.comparableQuestions),
    '🔴 **跨接口对账**：五档合计 == overview 的「可比集」（两个端点各算各的，对不上就是口径漂了）',
    `${fiveBuckets} vs ${overview.comparableQuestions}`)

  observe('本轮的分数口径落在哪一支',
    `可比 ${overview.comparableQuestions} · 未重排 ${overview.noRerankQuestions} · 空召回 ${overview.emptyRecallQuestions}`)

  // ---------- ⑥ 接口层：重排效果（同子集）----------
  const rerank = await retrieval('rerank-effect')
  check(Number(rerank.covered) <= Number(rerank.comparable), '重排前有值的行数 ≤ 可比集行数',
    `covered=${rerank.covered} comparable=${rerank.comparable}`)
  if (Number(rerank.covered) > 0) {
    const gap = Math.abs(Number(rerank.gainPercent)
      - (Number(rerank.afterTop1HitRate) - Number(rerank.beforeTop1HitRate)))
    check(gap < 0.2, '净提升 = 重排后 − 重排前（同一子集，两位小数内自洽）',
      `${rerank.gainPercent} vs ${(Number(rerank.afterTop1HitRate) - Number(rerank.beforeTop1HitRate)).toFixed(1)}`)
  } else {
    check(rerank.beforeTop1HitRate === null && rerank.gainPercent === null,
      '没有可比数据时前后比率与净提升都回 null（不是 0）')
  }

  // ---------- ⑦ 接口层：各库排行的归属口径（需求 §2.5）----------
  const kbq = await retrieval('kb-quality')
  const others = (kbq.list ?? []).filter((r) => String(r.kbName ?? '').startsWith('其他'))
  check(others.length === 1, '跨库提问计入「其他」一行', `其他=${others.length}`)
  check(Number(kbq.questions) + Number(kbq.othersQuestions) <= Number(overview.totalQuestions),
    '各库 + 其他 ≤ 提问总数（需求 §2.8 第 4 条）',
    `${kbq.questions} + ${kbq.othersQuestions} ≤ ${overview.totalQuestions}`)
  // 🔴 当前链路的真相：提问无法限定知识库 ⇒ 每句话都属于每个库 ⇒ 按库排行只有「其他」
  check(Number(kbq.questions) === 0 && Number(kbq.othersQuestions) > 0,
    '🔴 当前链路上按库排行**只有「其他」** —— `ChatRequest` 没有 `kbIds`、提问页也没有库选择器 ⇒ '
      + '每次提问覆盖全部可见库。将来提问侧支持库范围时这条会转红（那正是要的）',
    `单库 ${kbq.questions} · 其他 ${kbq.othersQuestions}`)
  check(others[0]?.trend === 'UNKNOWN' && others[0]?.issue === null,
    '「其他」行的走势是「样本不足」而不是「稳定」；「主要问题」如实为空（要文档质量列，本版本不猜）')

  // ---------- ⑧ 页面级：真数据渲染 + 与接口对账 ----------
  // 🔴 **带 token 进页面**：前端从 localStorage 读 `ak_token`，而本脚本是直连后端登的录。
  // 不注入的话路由守卫会把页面赶回 `/#/login`，而现象是「运营页一直挂不上」（看着像接口坏了）。
  await goto(`${BASE}/#/login`)
  await evaluate(`localStorage.setItem('ak_token', ${JSON.stringify(token)}); localStorage.setItem('ak_remember','1'); 'ok'`)
  // 🔴 路由是 **hash 模式** ⇒ 必须写 `BASE/?boot=…#/ops/retrieval`。
  // 写成 `BASE/ops/retrieval` 的话 hash 为空 ⇒ 落到默认路由再重定向（`e2e-ops-qa-stats` 踩过一次）
  await goto(`${BASE}/?boot=${Date.now()}#/ops/retrieval`)
  await waitFor(`window.document.querySelector('.page-title')?.innerText?.trim() === '检索质量分析'`,
    { timeout: 25000, label: '检索质量页挂载' })
  check(true, '页面挂载成功（真后端，非 mock）')

  // 三段恒等式在页面上也是同一个数
  const sumLine = await waitFor(
    `(() => { const t = document.querySelector('.sum-line')?.innerText ?? ''; return t.includes('${pipe.totalMs}') ? t : null })()`,
    { label: '页面显示出与接口一致的全链路耗时' })
  check(sumLine.includes('检索侧') && sumLine.includes('生成') && sumLine.includes('其他')
    && sumLine.includes('全链路'),
    '页面上写成「检索侧 + 生成 + 其他 = 全链路」，且全链路与接口一致',
    sumLine.replace(/\s+/g, ' ').slice(0, 120))

  // 六档逐档与接口对账（页面读的是同一批数 ⇒ 这也是一次页面↔接口对账）
  const rendered = await evaluate(`JSON.stringify([...document.querySelectorAll('.bucket-row')].map((r) => ({
      label: r.querySelector('.bk-label')?.innerText?.trim() ?? '',
      count: Number((r.querySelector('.bk-count')?.innerText ?? '').replace(/[^0-9]/g, '')) }))) `)
  const pageBuckets = JSON.parse(rendered || '[]')
  check(pageBuckets.length === 6, '页面上是**六档**（含「无法定档」）', `档数=${pageBuckets.length}`)
  const mismatch = (dist.list ?? []).filter((b) => {
    const hit = pageBuckets.find((p) => p.label === b.label)
    return !hit || hit.count !== Number(b.count)
  })
  check(mismatch.length === 0,
    '🔴 每一档的次数都与接口**逐档一致**（页面↔接口对账）',
    mismatch.length ? JSON.stringify(mismatch) : `${pageBuckets.length} 档全对`)

  // 「其他」那一行真的显示出来了
  const bodyText = await evaluate('document.body.innerText')
  check(bodyText.includes('其他（跨库提问）'), '页面表格里出现「其他（跨库提问）」那一行')
  check(!bodyText.includes('96.4') && !bodyText.includes('88.2'),
    '🔴 mock 里那几个**没有采集点**的示例指标值（96.4% / 88.2%）**不在**页面上')
  check(!bodyText.includes('导出报表'),
    '页面**没有**「导出报表」按钮（需求 §2.6 没给这一页导出端点 ⇒ 不留假入口）')
  check(bodyText.includes('数据不足') || bodyText.includes('可比集'),
    '页面上有「数据不足 / 口径」说明（需求 §2.8 最后一条要的诚实空态）')

  // 时间范围真的驱动五块（切 30d 不报错且页面仍在）
  await evaluate(`(() => { const el = document.querySelector('.head-actions .el-select input'); return !!el })()`)
  observe('时间范围下拉存在（五块共用同一个窗口）')

  // ---------- ⑨ 收尾 ----------
  // ⚠️ 这里**不**清 —— 清理只在 `finally` 里做一次（`#186`：清理必须走必走路径）。
  // 清两遍的话第二遍已经找不到那个探针库了，`describeKbResidue` 会打一句
  // "本轮没抓到 kb ids" 的**误导性**警告（东西其实早清干净了）。
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
 * <p>🔴 **必须能从 `finally` 里被调**（`#186`：`step()` 一抛就不执行的那条教训）。
 * <p>🔴 `T-O19`：除了会话族，还要清**会话水位线够不着**的那几张
 * （`sys_model_usage` 按日期 / `sys_qa_miss` 按人）—— 本脚本真提问。
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
  // 池子 + 用量 —— 按人/按日期，会话水位线够不着它们
  // ⚠️ `loginUserId > 0` 才清：登录都没成功时它还是 0，按 0 去删是**删别人**（有 `user_id=0` 的行就误伤）
  if (loginUserId > 0) {
    purgeUserScopedArtifacts(loginUserId)
  }

  const r = await teardownKbProbe(KB_CODE)

  // 独立观测点：不看清理命令的返回值，直接再查一次
  const left = sessionIds.size
    ? mysqlCount(
      `select (select count(*) from sys_chat_session where id in (${[...sessionIds].join(',')})) `
        + `+ (select count(*) from sys_chat_message where session_id in (${[...sessionIds].join(',')})) `
        + `+ (select count(*) from sys_retrieval_trace where session_id in (${[...sessionIds].join(',')})) `
        + `+ (select count(*) from sys_qa_miss where last_user_id = ${loginUserId});`)
    : mysqlCount(`select count(*) from sys_qa_miss where last_user_id = ${loginUserId};`)
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
