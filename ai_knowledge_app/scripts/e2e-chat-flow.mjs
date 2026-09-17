/**
 * 页面级联调 —— 问答 SSE 全链路（`S5-M08-A` · `T-800`/`T-801`/`T-707`/`T-708`）。
 *
 * <h3>这个脚本验的四件事</h3>
 * <ol>
 *   <li><b>SSE 事件序列</b>（`G5-7`/`G5-8`）：首包必须是 `retrieval`，
 *       且 `done` 带 `modelId`/`modelName`/`tokenUsage`/`finishReason`/`usedVision`；</li>
 *   <li><b>同源</b>（`G5-9`）：顶层 `chunks` 与 `trace.chunks` **逐字段一致**
 *       —— 前端用前者渲染引用角标、用后者渲染链路面板，漂移了就对不上；</li>
 *   <li><b>打点落库</b>（`G5-12`/`G5-13`/`I-13`/`I-14`）：`sys_retrieval_trace` 真有一行，
 *       6 阶段耗时齐全、Top1 相似度非空；</li>
 *   <li><b>页面级</b>（`G5-15`）：真后端模式下「提问 → 引用角标 → 检索详情面板」能走通。</li>
 * </ol>
 *
 * <h3>⚠️ 探针账号用**种子超管**，不是 `e2e` 前缀的新账号</h3>
 * <p>因为要问出「有引用」的问题，就必须有一个**已授权且已建索引**的知识库；
 * 新建账号既没有可见库、也无法立刻拿到授权。所以本脚本登录 `lin.gong`
 * （`V6` 的种子超管，密码 `Init@12345`）。
 *
 * <p>代价是：它造的 `sys_retrieval_trace` / `sys_chat_session` 行**不属于任何探针前缀**，
 * `teardownUserProbe('e2e')` 按前缀清**看不见它们**。所以本脚本**自己**按
 * 会话 id 精确回收（见 {@link #cleanupChatRows}）—— 那正是「产物认识、归属维度不认识」
 * 这一族问题（`汇总.md` §3 `#130`）的又一次现身。
 *
 * <p>⚠️ 本脚本会真调**对话模型**与**嵌入模型**（上传即向量化），耗时以十秒计。
 *
 * 用法：`node scripts/e2e-chat-flow.mjs --base=http://127.0.0.1:5174`
 */
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync, existsSync, rmSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { mysqlScalar, teardownKbProbe, kbResidueClean, describeKbResidue } from './lib/probe-teardown.mjs'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-e2e-chat'
const OUT_DIR = 'F:/work/code/ai-knowledge/temp/e2e-chat'
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9335)

const argBase = process.argv.find((a) => a.startsWith('--base='))
const BASE = argBase ? argBase.slice('--base='.length) : 'http://127.0.0.1:5174'

const ACCOUNT = process.env.E2E_CHAT_ACCOUNT || 'lin.gong'
const PASSWORD = process.env.E2E_CHAT_PASSWORD || 'Init@12345'

const stamp = Date.now()
const KB_CODE = `kb_e2echat${stamp}`

// ---------------------------------------------------------------- CDP 基础
let ws
let seq = 0
const pending = new Map()
const consoleMsgs = []
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

async function waitFor(expression, label, timeout = 30000) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeout) {
    if (await evaluate(expression)) return true
    await sleep(150)
  }
  throw new Error(`等待超时（${label}）：${expression}`)
}

async function shot(name) {
  const r = await send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(resolve(OUT_DIR, `${name}.png`), Buffer.from(r.data, 'base64'))
}

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

// ---------------------------------------------------------------- HTTP 基础
let token = ''

async function api(path, { method = 'GET', body, raw = false } = {}) {
  const headers = token ? { Authorization: `Bearer ${token}` } : {}
  if (body && !raw) headers['Content-Type'] = 'application/json'
  const resp = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: raw ? body : body ? JSON.stringify(body) : undefined,
  })
  const text = await resp.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    /* 非 JSON（例如 SSE） */
  }
  return { status: resp.status, json, text }
}

/** 断言业务成功（本项目业务错误一律 HTTP 200 + 非 0 code）。 */
function bizOk(r, label) {
  check(r.json, `${label}：响应不是 JSON（HTTP ${r.status}）：${String(r.text).slice(0, 160)}`)
  check(r.json.code === 0, `${label}：HTTP ${r.status} 但业务失败 code=${r.json.code} · ${r.json.message}`)
  return r.json.data
}

/**
 * 读一次 SSE，把事件按顺序解析出来。
 *
 * <p>解析规则按 §9：帧以空行分隔，帧内 `event:` 给名字、`data:` 给 JSON。
 */
async function readSse(path, body) {
  const resp = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify(body),
  })
  check(resp.ok, `SSE 请求失败：HTTP ${resp.status}`)
  check(resp.body, 'SSE 响应没有 body')

  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  const events = []
  let buffer = ''
  let sawDone = false
  while (!sawDone) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx
    while ((idx = buffer.indexOf('\n\n')) !== -1) {
      const frame = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      const ev = parseFrame(frame)
      if (!ev) continue
      events.push(ev)
      if (ev.name === 'done') sawDone = true
    }
  }
  return events
}

function parseFrame(frame) {
  let name = 'message'
  let data = null
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) name = line.slice(6).trim()
    else if (line.startsWith('data:')) {
      const payload = line.slice(5).trim()
      if (!payload) continue
      try {
        data = JSON.parse(payload)
      } catch {
        /* 残缺帧 */
      }
    }
  }
  return data ? { name, data } : null
}

// ---------------------------------------------------------------- 主流程

/**
 * 收尾要用的两个坐标（见文件头注释）。
 *
 * <p>{@link sessionFloor} = 脚本开跑那一刻 `sys_chat_session` 的最大 id。
 * 收尾删的是「本账号 + id 大于水位」的那些行 —— 这样**页面级那一步自己开的会话**
 * 也在网里。只记"我知道的那几个 id"是不够的（第一版就漏了一个）。
 */
let loginUserId = 0
let sessionFloor = 0

async function main() {
  console.log(`\n联调目标：${BASE}`)
  console.log(`探针账号：${ACCOUNT}（种子超管）· 探针知识库：${KB_CODE}\n`)

  // ---------- ① 登录 ----------
  let kbId
  await step('登录', async () => {
    const r = await api('/api/auth/login', { method: 'POST', body: { account: ACCOUNT, password: PASSWORD } })
    const data = bizOk(r, '登录')
    token = data.accessToken
    check(!!token, '登录成功但没拿到 accessToken')
    // 记下**水位线**：收尾要删的是"这个账号在本脚本跑起来之后新造的会话"。
    // ⚠️ 不能只记"我知道的那几个会话 id" —— 页面级那一步也会自己开一个新会话
    // （前端不传 sessionId），而它不在任何返回值里。第一版就是这么漏掉一个会话的
    loginUserId = Number(data.user?.id ?? 0)
    check(loginUserId > 0, '登录响应里没有 user.id')
    sessionFloor = Number(mysqlScalar('select coalesce(max(id), 0) from sys_chat_session;'))
    return `userId=${loginUserId} · traceId=${r.json.traceId} · 会话水位=${sessionFloor}`
  })

  // ---------- ② 建探针库 ----------
  await step('建探针知识库', async () => {
    const r = await api('/api/kb', {
      method: 'POST',
      body: {
        name: `问答联调库 ${stamp}`,
        code: KB_CODE,
        deptId: 1,
        visibleRoles: ['USER'],
        businessDomain: '技术文档',
        description: 'S5-M08-A 问答链路页面级联调探针，脚本结束即删',
      },
    })
    const data = bizOk(r, '建库')
    kbId = data.id
    check(!!kbId, '建库成功但没拿到 id')
    return `kbId=${kbId}`
  })

  // ---------- ③ 传一篇文档（会自动解析 + 向量化） ----------
  await step('上传文档并等待解析、索引', async () => {
    const content = [
      '# 季度报价单（2026Q3）',
      '',
      '## 型号与价格',
      '',
      '| 型号 | 单价（元） | 交期（天） |',
      '| --- | --- | --- |',
      '| A-100 | 1800 | 7 |',
      '| A-200 | 2400 | 15 |',
      '| A-300 | 3600 | 30 |',
      '',
      '## 起订量',
      '',
      '型号 A-100 的起订量为 10 台；型号 A-200 的起订量为 5 台。',
      '所有型号均支持定制，定制件的交期在原交期基础上顺延 15 天。',
    ].join('\n')

    const form = new FormData()
    form.append('file', new Blob([content], { type: 'text/markdown' }), `e2echat-${stamp}.md`)
    const r = await api(`/api/kb/${kbId}/doc/upload`, { method: 'POST', body: form, raw: true })
    bizOk(r, '上传')

    // 解析 + 增强 + 向量化：实测单篇 8.5~13s（增强那次 LLM 调用占大头），
    // 再加上嵌入，给足 120s。轮询而不是死等 —— 失败时能早点报出来
    const deadline = Date.now() + 120000
    let lastStatus = ''
    while (Date.now() < deadline) {
      const docs = bizOk(await api(`/api/kb/${kbId}/docs?page=1&size=10`), '查文档列表')
      const doc = (docs.list ?? [])[0]
      lastStatus = doc?.status ?? '（还没行）'
      if (lastStatus === 'READY') {
        const vectors = mysqlScalar(
          `select count(*) from sys_chunk c join sys_document d on d.id = c.doc_id where d.kb_id = ${kbId};`,
        )
        return `状态 READY · 分片 ${vectors} 条 · 耗时 ${Math.round((Date.now() - (deadline - 120000)) / 1000)}s`
      }
      if (lastStatus === 'FAILED') throw new Error(`文档解析失败：${doc?.errorMsg ?? '（无原因）'}`)
      await sleep(2000)
    }
    throw new Error(`等待文档就绪超时，最后状态=${lastStatus}`)
  })

  // ---------- ④ SSE 契约（API 侧） ----------
  let events
  await step('SSE 事件序列与字段（G5-7 / G5-8 / G5-9）', async () => {
    events = await readSse('/api/chat/completions', { message: 'A-100 的单价和交期分别是多少？' })

    const names = events.map((e) => e.name)
    check(names.length > 0, '一个事件都没收到')
    check(names[0] === 'retrieval', `首包必须是 retrieval，实际是 ${names[0]}`)
    check(names.includes('delta'), '没有收到任何 delta')
    check(names[names.length - 1] === 'done', `最后一个事件必须是 done，实际是 ${names[names.length - 1]}`)

    const retrievals = events.filter((e) => e.name === 'retrieval')
    check(retrievals.length === 2, `retrieval 应发两次（首包 + 终包），实际 ${retrievals.length}`)

    const first = retrievals[0].data
    const last = retrievals[1].data
    check(first.chunks.length > 0, '一条分片都没召回 —— 引用角标无从渲染，本步失去意义')
    check(
      JSON.stringify(first.chunks) === JSON.stringify(first.trace.chunks),
      'G5-9 破了：顶层 chunks 与 trace.chunks 内容不一致（前端两处会各显示一套）',
    )
    for (const c of first.chunks) {
      check(['vector', 'bm25', 'graph'].includes(c.source), `source 取值非法：${c.source}`)
      check(typeof c.chunkId === 'number', `chunkId 必须是 number，实际 ${typeof c.chunkId}`)
      check(!!c.docTitle, 'docTitle 为空 —— 引用卡片会显示「未知文档」')
    }
    check(first.trace.graphUsed === false, 'graphUsed 应为 false（图谱旁路未实现）')

    // 首包拿不到生成耗时（生成还没开始），终包才是真值 —— 这正是发两次的原因
    check(first.trace.llmGeneration === 0, `首包的 llmGeneration 应为 0，实际 ${first.trace.llmGeneration}`)
    check(last.trace.llmGeneration > 0, `终包的 llmGeneration 应大于 0，实际 ${last.trace.llmGeneration}`)
    check(last.trace.totalMs >= last.trace.llmGeneration, '终包的 totalMs 应覆盖生成耗时')

    const done = events.find((e) => e.name === 'done').data
    check(/^\d+$/.test(done.messageId), `messageId 应是数字串（前端类型是 string），实际 ${done.messageId}`)
    check(done.modelName, 'G5-8：done 必须带 modelName')
    check(done.finishReason, 'G5-8：done 必须带 finishReason')
    check(done.usedVision === false, 'T-512 之前 usedVision 必须如实回 false')
    if (done.modelId === null) {
      // 引导期 ModelConfigSpec.id 是 bootstrap-chat，解析不出数字 —— 见计划 §9
      observations.push('done.modelId 为 null（引导期模型 id 是字符串，S6 接 DB 后才是数字）')
    }
    if (!done.tokenUsage) {
      observations.push('done.tokenUsage 为 null —— 模型未回报用量，按「未知」处理而不是 0')
    }

    return `${names.length} 个事件 · 首包 retrieval · 召回 ${first.chunks.length} 条 · 生成 ${last.trace.llmGeneration}ms`
  })

  // ---------- ⑤ 打点落库（G5-12 / G5-13） ----------
  await step('检索打点落库（G5-12 / G5-13 / I-13 / I-14）', async () => {
    const done = events.find((e) => e.name === 'done').data
    const messageId = Number(done.messageId)
    const sessionId = Number(
      mysqlScalar(`select session_id from sys_chat_message where id = ${messageId};`),
    )
    check(sessionId > 0, `done.messageId=${messageId} 查不到对应的消息行 —— 它不是真行 id`)
    check(
      sessionId > sessionFloor,
      `会话 id=${sessionId} 没超过水位 ${sessionFloor} —— 说明这次问答落进了别人的会话里`,
    )

    const row = mysqlScalar(
      `select concat_ws('|', embed_millis, vector_search_millis, bm25_recall_millis, rerank_millis, `
        + `coalesce(llm_generation_millis,'NULL'), total_millis, coalesce(top1_score,'NULL'), chunk_count) `
        + `from sys_retrieval_trace where session_id = ${sessionId} order by id desc limit 1;`,
    )
    check(!!row, '没有落 sys_retrieval_trace 行')
    const [embed, vsearch, bm25, rerank, llm, total, top1, chunkCount] = row.split('|')
    check(Number(embed) > 0, `embed_millis 应大于 0（拆了计时器才有），实际 ${embed}`)
    check(Number(vsearch) >= 0 && vsearch !== '', `vector_search_millis 缺失：${vsearch}`)
    check(bm25 !== '', `bm25_recall_millis 缺失：${bm25}`)
    check(rerank !== '', `rerank_millis 缺失：${rerank}`)
    check(llm !== 'NULL' && Number(llm) > 0, `llm_generation_millis 应大于 0，实际 ${llm}`)
    check(Number(total) > 0, `total_millis 应大于 0，实际 ${total}`)
    check(top1 !== 'NULL', 'G5-13 / I-14：Top1 相似度必须落，这里是 NULL')
    check(Number(chunkCount) > 0, `chunk_count 应大于 0，实际 ${chunkCount}`)

    const details = Number(
      mysqlScalar(`select count(*) from sys_retrieval_trace_chunk where trace_id = `
        + `(select trace_id from sys_retrieval_trace where session_id = ${sessionId} order by id desc limit 1);`),
    )
    check(details === Number(chunkCount), `分片明细 ${details} 条与 chunk_count=${chunkCount} 对不上`)

    return `embed=${embed}ms · rerank=${rerank}ms · 生成=${llm}ms · 合计=${total}ms · Top1=${top1} · 明细 ${details} 条`
  })

  // ---------- ⑥ 页面级（G5-15） ----------
  await step('页面级：提问 → 引用角标 → 检索详情面板', async () => {
    // 直接把令牌塞进 localStorage 再进页面：省掉一遍登录 UI，而本步要验的是**问答页**
    await send('Page.navigate', { url: `${BASE}/login` })
    await sleep(1200)
    await evaluate(`localStorage.setItem('ak_token', ${JSON.stringify(token)}); true`)
    await send('Page.navigate', { url: `${BASE}/chat` })
    await waitFor(`!!document.querySelector('.chat-input textarea')`, '问答页挂载')

    // 用原生 setter 再派发 input —— Vue 的 v-model 只认这个（直接赋 value 不触发）
    await evaluate(`(() => {
      const ta = document.querySelector('.chat-input textarea')
      const proto = HTMLTextAreaElement.prototype
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(ta, 'A-100 的单价和交期分别是多少？')
      ta.dispatchEvent(new Event('input', { bubbles: true }))
      return true
    })()`)
    await sleep(300)
    await evaluate(`(() => {
      const btn = [...document.querySelectorAll('.chat-input button')]
        .find((b) => (b.textContent || '').includes('发送'))
      if (!btn) throw new Error('找不到发送按钮')
      btn.click()
      return true
    })()`)

    // 真模型生成比 mock 慢得多 —— 给 120s
    await waitFor(`document.querySelectorAll('.cit-tag').length > 0`, '引用角标出现', 120000)
    await waitFor(`!!document.querySelector('.trace-panel')`, '检索详情面板出现', 30000)
    await waitFor(`document.querySelectorAll('.tp-cite').length > 0`, '面板内的引用条目出现', 30000)

    const summary = await evaluate(`(() => {
      const tags = document.querySelectorAll('.cit-tag').length
      const panel = document.querySelector('.trace-panel')
      const cites = panel.querySelectorAll('.tp-cite').length
      const scores = [...panel.querySelectorAll('.tp-cite-score')].map((e) => e.textContent.trim())
      return { tags, cites, scores }
    })()`)

    check(summary.tags > 0, '引用角标一个都没有')
    check(summary.cites > 0, '检索详情面板里的引用条目为空')
    check(
      summary.scores.every((s) => s.includes('%')),
      `面板里的分数没有按百分号渲染：${JSON.stringify(summary.scores)}`,
    )
    await shot('chat-with-citations')
    return `角标 ${summary.tags} 个 · 面板引用 ${summary.cites} 条 · 分数样例 ${summary.scores[0]}`
  })

  // ---------- ⑦ 收尾 ----------
  await step('收尾：清理探针库与会话行', async () => {
    // 会话族：**先子后父**（明细按 trace_id 顺上去、消息按 session_id 顺上去）。
    // ⚠️ 顺序反了会留下孤儿明细，而它们**不属于任何探针前缀**，没有别的机制能发现。
    // 口径是「本账号 + id 大于水位」—— 页面级那一步自己开的会话也在这张网里
    mysqlScalar(
      `delete from sys_retrieval_trace_chunk where trace_id in (select trace_id from sys_retrieval_trace `
        + `where user_id = ${loginUserId} and session_id > ${sessionFloor});`,
    )
    mysqlScalar(
      `delete from sys_retrieval_trace where user_id = ${loginUserId} and session_id > ${sessionFloor};`,
    )
    mysqlScalar(
      `delete from sys_chat_message where session_id in (select id from sys_chat_session `
        + `where user_id = ${loginUserId} and id > ${sessionFloor});`,
    )
    mysqlScalar(
      `delete from sys_chat_session where user_id = ${loginUserId} and id > ${sessionFloor};`,
    )

    // ⚠️ `teardownKbProbe` 是 async（它要等 Milvus 那边确认）—— 漏了 await 拿到的是 Promise，
    // 而 `residue` 是 undefined，报出来的是 "Cannot read properties of undefined"，
    // 与"清理失败"看起来毫不相干（本脚本第一版正是如此）
    const r = await teardownKbProbe(KB_CODE)
    check(kbResidueClean(r.residue), `探针库仍有残留：${describeKbResidue(r.residue)}`)

    // 独立观测点：不看清理命令的返回值，直接再查一次
    const left = Number(
      mysqlScalar(
        `select (select count(*) from sys_chat_session where user_id = ${loginUserId} and id > ${sessionFloor}) `
          + `+ (select count(*) from sys_chat_message where session_id > ${sessionFloor}) `
          + `+ (select count(*) from sys_retrieval_trace where user_id = ${loginUserId} and session_id > ${sessionFloor}) `
          + `+ (select count(*) from sys_retrieval_trace_chunk where trace_id in `
          + `(select trace_id from sys_retrieval_trace where user_id = ${loginUserId} and session_id > ${sessionFloor}));`,
      ),
    )
    check(left === 0, `仍残留 ${left} 行会话族数据`)
    return `探针库已清（${describeKbResidue(r.residue)}）· 会话族残留 ${left} 行`
  })
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

// 残留的 ak_token 会让路由守卫把 /login 重定向走 —— 开跑前清 profile
if (existsSync(PROFILE)) rmSync(PROFILE, { recursive: true, force: true })

let child
try {
  mkdirSync(OUT_DIR, { recursive: true })
  if (!existsSync(EDGE)) throw new Error(`找不到 Edge：${EDGE}`)
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
  await send('Log.enable')
  // 固定视口：默认无头窗口 800x600 会把问答页挤变形（假报布局问题）
  await send('Emulation.setDeviceMetricsOverride', {
    width: 1440, height: 900, deviceScaleFactor: 1, mobile: false,
  })

  await main()

  const failed = steps.filter((s) => !s.ok).length
  console.log(`\n合计 ${steps.length} 步，通过 ${steps.length - failed}，失败 ${failed}`)

  // 控制台告警是**观察项**：本批新页面可能带出既有告警，不该让它把功能判据染红。
  // 但必须报出来 —— 组件未解析这类错只在控制台，不报就等于没看见
  const noisy = consoleMsgs.filter((m) => !/favicon|DevTools/i.test(m.text))
  if (noisy.length) {
    console.log(`\n[控制台告警 ${noisy.length} 条（观察项）]`)
    for (const m of noisy.slice(0, 12)) console.log(`  · [${m.level}] ${m.text.slice(0, 160)}`)
  }
  if (observations.length) {
    console.log('\n[非致命观察项]')
    for (const o of observations) console.log(`  · ${o}`)
  }
  console.log(`\n截图证据：${OUT_DIR}`)
  process.exitCode = failed ? 1 : 0
} catch (e) {
  console.error(`\n✗ 中断：${e.message}`)
  process.exitCode = 1
} finally {
  try {
    ws?.close()
  } catch {}
  child?.kill()
  await sleep(300)
}
