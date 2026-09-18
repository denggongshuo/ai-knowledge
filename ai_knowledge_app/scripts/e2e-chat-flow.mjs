/**
 * 页面级联调 —— 问答 SSE 全链路（`S5-M08-A` · `T-800`/`T-801`/`T-707`/`T-708`）。
 *
 * <h3>这个脚本验的六件事</h3>
 * <ol>
 *   <li><b>SSE 事件序列</b>（`G5-7`/`G5-8`）：首包必须是 `retrieval`，
 *       且 `done` 带 `modelId`/`modelName`/`tokenUsage`/`finishReason`/`usedVision`；</li>
 *   <li><b>同源</b>（`G5-9`）：顶层 `chunks` 与 `trace.chunks` **逐字段一致**
 *       —— 前端用前者渲染引用角标、用后者渲染链路面板，漂移了就对不上；</li>
 *   <li><b>打点落库</b>（`G5-12`/`G5-13`/`I-13`/`I-14`）：`sys_retrieval_trace` 真有一行，
 *       6 阶段耗时齐全、Top1 相似度非空；</li>
 *   <li><b>页面级</b>（`G5-15`）：真后端模式下「提问 → 引用角标 → 检索详情面板」能走通；</li>
 *   <li><b>图片附件</b>（`T-805`，`S5-M08-C`）：从页面喂一张**真文件**，验它走 multipart
 *       落桶 + 落库 —— 改前前端发的是 JSON 元数据，**一个字节都没传**，而接口照样返回 200；</li>
 *   <li><b>反馈</b>（`T-807`/`G5-14`）：页面点「点赞」再改成「点踩+原因」，回查 `sys_chat_message`
 *       的三列真的跟着变（覆盖语义）；</li>
 *   <li>🆕 <b>流真的是流</b>（`S6-M05-C` 的 P0 修复 · `G6-11` 的页面半边）：带图提问时，
 *       页面在**描述期间**要看得见「正在理解图片」（`.as-line.describing`）。
 *       `S6-M09-B` 时这条**挂起**过 —— 整条 SSE 被攒到控制器方法返回后一次性重放，
 *       `DESCRIBING` 与 `DONE` 之间只隔几毫秒，页面来不及渲染。修复后那两帧之间是
 *       真的 40~60 秒（真模型），所以这条断言**必须**成立，且修复被回退时它会**超时转红**。</li>
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
import { spawn, execFileSync } from 'node:child_process'
import { mkdirSync, writeFileSync, readFileSync, existsSync, rmSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, join } from 'node:path'
import {
  mysqlScalar,
  teardownKbProbe,
  kbResidueClean,
  describeKbResidue,
  minioKeysOfUserPrefix,
  minioRemove,
} from './lib/probe-teardown.mjs'

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

/**
 * 轮询一条 SQL 直到它给出**非空**值。
 *
 * <h3>⚠️ 为什么需要它 —— 一次被"假流式"掩盖了整整一批的竞态</h3>
 * <p>`S6-M09-B` 时，本脚本在 `.as-done` 之后直接读 `sys_retrieval_trace` 的最新一行，
 * 而 `.as-done` 只是**描述完成**（SSE 的 `attachment{DONE}` 帧）—— 那时检索、生成、
 * 落库**一件都还没发生**。这个假设**碰巧成立**，因为当时整条 SSE 被攒到控制器方法
 * 返回后一次性重放，`.as-done` 只在整轮跑完之后才出现。
 *
 * <p>`S6-M05-C` 的 P0 修复（编排搬出请求线程）让帧变成真的之后，这个假设当场就塌了：
 * 实测 `order by id desc limit 1` 取回的是**上一轮**的 trace（脚本报
 * 「检索用的 query 丢了原问题：A-100 的单价和交期分别是多少？」）。
 *
 * <p>所以改成**等事件发生，再断言值**（而不是"等到期望值出现"—— 那会把真失败
 * 变成慢成功）。水位线在**发送之前**取，`id > 水位` 即本轮那一行。
 */
async function awaitSqlScalar(sql, label, timeout = 180000) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeout) {
    const value = mysqlScalar(sql)
    if (value) return value
    await sleep(500)
  }
  throw new Error(`等待超时（${label}）：${sql}`)
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

/** 喂给 `input[type=file]` 的临时 PNG 路径（跑完即删）。 */
let attachmentFixture = ''

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

    // `T-808` 归因（判据 8）：这一列是「同一会话为何答案风格变了」的第一手线索，
    // 而它在**用例里非空、在真实运行时可能是 NULL** —— 只能在这里查。
    // ⚠️ 空召回不生成回答（`G5-11`），那条路上的 `outcome` 是 null，两个列本来就该是空；
    // 所以本步先确认"这一轮真的生成了"，再断言 model_name。
    const attribution = mysqlScalar(
      `select concat_ws('|', coalesce(model_name,'NULL'), coalesce(model_id,'NULL'), finish_reason) `
        + `from sys_chat_message where id = ${messageId};`,
    )
    const [usedModelName, usedModelId, finishReason] = attribution.split('|')
    check(
      finishReason !== 'empty_recall',
      `这一轮走的是空召回（无回答），归因两列为空是正常的 —— 但本步需要一次真生成`,
    )
    check(usedModelName !== 'NULL', `T-808：真生成后 model_name 不该为空（finish=${finishReason}）`)
    if (usedModelId === 'NULL') {
      // 已知偏差（`#139`/`#15`）：模型配置尚未接进 DB，引导配置的 id 是 `bootstrap-chat`
      // 这种字符串，`RegistryStreamingChatCaller#numericId` 解析不出数字就如实返回 null。
      // **不是本步的失败**，但要留痕 —— 否则"归因列有值"会被误当成"归因可用"
      observations.push(
        `T-808：model_id 为 NULL（引导配置 id 非数字，S6 接 DB 后才恒非空）—— 已登记为 S6 T-900 遗留`,
      )
    }

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

  // ---------- ⑥.5 图片附件（T-805）与反馈（T-807 / G5-14） ----------
  await step('图片附件上传（T-805）：真字节走 multipart，落桶 + 落库', async () => {
    // ⚠️ 必须喂**真文件**：本步要证的恰恰是"文件字节真的上传了"。
    // 改动前前端走 `http.post` 发 `{name,size,md5,mime}` —— 一个字节都没传，
    // 而接口照样能返回 200，**从接口层完全看不出来**。所以只能从页面把真文件塞进
    // `input[type=file]`，再看库里那行的 size 与真文件对不对得上。
    // ⚠️ 用**真图**而不是原来那张 1×1 的 base64 像素：`T-907` 起这张图会被送去 VL
    // 生成描述，而 1×1 的透明像素**没有任何可描述的内容** —— VL 要么返回空、
    // 要么胡说，两种都让下面「描述真的落库」的断言变成掷骰子。
    // 借仓库里的一张真图表（44KB，有文字有结构），VL 能读出东西来。
    const png = readFileSync(
      'F:/work/code/ai-knowledge/docs/面试/图表/04-检索链路总览.png',
    )
    attachmentFixture = join(tmpdir(), `e2e-chat-att-${stamp}.png`)
    writeFileSync(attachmentFixture, png)

    // ⚠️ **不重新导航**：上一步已经把页面停在 /chat 上，重载会丢掉那段会话
    // （`ChatView` 挂载时只渲染欢迎语），而下一步的反馈要挂在**刚问出来的那条回答**上。
    await waitFor(
      `!!document.querySelector('.chat-input input[type="file"]')`,
      '问答页仍挂载（沿用上一步的页面）',
    )

    // `DOM.setFileInputFiles` 要的是元素句柄（objectId），不是选择器
    const handle = await send('Runtime.evaluate', {
      expression: `document.querySelector('.chat-input input[type="file"]')`,
    })
    await send('DOM.setFileInputFiles', { files: [attachmentFixture], objectId: handle.result.objectId })

    await waitFor(`document.querySelectorAll('.pending-item').length > 0`, '待发送区出现缩略图', 30000)
    // 文案从「上传中 N%」变成「待发送」= 上传真的结束了（进度来自 XHR，不是假的定时器）
    await waitFor(
      `[...document.querySelectorAll('.pending-item')].some((e) => (e.textContent || '').includes('待发送'))`,
      '附件上传完成',
      30000,
    )

    const row = mysqlScalar(
      `select concat_ws('|', id, object_key, size) from sys_chat_attachment `
        + `where user_id = ${loginUserId} order by id desc limit 1;`,
    )
    check(!!row, '页面上传成功，但库里没有 sys_chat_attachment 行')
    const [attId, objectKey, size] = row.split('|')
    check(
      Number(size) === png.length,
      `库里 size=${size}，真文件是 ${png.length} 字节 —— 落的不是同一份字节`,
    )
    check(objectKey.startsWith('chat-attachments/'), `对象名前缀不对：${objectKey}`)
    // ⚠️ 这里**不再把它存到模块级变量**去给收尾用（`#163`）：收尾改成了按
    //    `userId` 前缀列全量 —— 存一份"我知道的那一个"正是漏的成因。

    // 回看接口也要真能读（本批页面上还没有消费方，但接口必须成立）
    const detail = bizOk(await api(`/api/chat/attachments/${attId}`), '回看附件')
    check(detail.name && detail.url, `回看响应缺字段：${JSON.stringify(detail)}`)
    check(detail.desc === null, 'T-512 之前 desc 必须如实为 null（填 "" 会被当成「模型没看出什么」）')

    await shot('chat-attachment-uploaded')
    return `附件 id=${attId} · ${png.length} 字节 · 对象 ${objectKey}`
  })

  // ---------- ⑥.6 图片理解（T-907）----------
  await step('图片理解（T-907 · 需求 09 §4.9/§4.12）：带图提问 → attachment 事件 → 描述落库', async () => {
    // 上一步那张图还在**待发送区**（没发出去）—— 本步把它连同问题一起发出去。
    // 这条链路才是 `T-907` 真正要证的：预上传只是把图放进桶里，**描述是发送时才生成的**。
    await waitFor(`document.querySelectorAll('.pending-item').length > 0`, '待发送区仍有那张图')

    // 🔴 **先清掉这张图的 VL 描述缓存**（需求 `09` §4.8 的 `md5:vlModelId:PROMPT_VERSION`）。
    // 不清的话，上一步刚传的这张图若此前被理解过，描述会**秒回** —— 那本步就只验了"缓存命中"，
    // 而真正要证的是**真调用**那条路（两产出、关键词切分、落库全是真模型给的）。
    // 清掉之后走真调用（本机实测 40~60s），`as-done` 的等待上限也按它给。
    const attMd5 = mysqlScalar(
      `select md5 from sys_chat_attachment where user_id = ${loginUserId} order by id desc limit 1;`,
    )
    execFileSync('docker', ['exec', 'kb-valkey', 'sh', '-c',
      `redis-cli --scan --pattern 'vision:desc:${attMd5}:*' | xargs -r redis-cli del`],
    { encoding: 'utf8' })
    const leftKeys = execFileSync('docker', ['exec', 'kb-valkey', 'sh', '-c',
      `redis-cli --scan --pattern 'vision:desc:${attMd5}:*' | wc -l`], { encoding: 'utf8' }).trim()
    check(leftKeys === '0', `缓存没清干净（还剩 ${leftKeys} 个键）—— 本步要的是**冷缓存**下的真调用`)

    await evaluate(`(() => {
      const ta = document.querySelector('.chat-input textarea')
      const proto = HTMLTextAreaElement.prototype
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(ta, '这张图讲的是什么？请说明检索链路有哪几个阶段。')
      ta.dispatchEvent(new Event('input', { bubbles: true }))
      return true
    })()`)
    await sleep(300)
    // ⚠️ **先等按钮可用**：`send()` 的第一行是 `if (... || sending.value) return`，
    // 而上一轮流还没结束时按钮是 `loading`（等效 disabled）的 —— **点一个不能点的按钮
    // 不报错也不发送**，表现是"脚本跑过了、请求根本没出去"（实测踩到：后端日志里
    // 那次带图问答压根没有）。等它可点，把这类静默失败变成一条明确的等待。
    await waitFor(`(() => {
      const b = [...document.querySelectorAll('.chat-input button')]
        .find((x) => (x.textContent || '').includes('发送'))
      return !!b && !b.disabled
    })()`, '发送按钮可用（上一轮流式已结束）', 120000)

    // 🔴 **发送前记下 trace 水位线**：本轮的检索打点在整轮跑完（检索 + 生成 + 落库）
    // 之后才写，而下面第 ① 条等到的是**描述完成**—— 两者之间隔着几十秒。
    // 见 `awaitSqlScalar` 的 javadoc：这个竞态是"假流式"掩盖下来的，修复后才露出来。
    const traceFloor = Number(
      mysqlScalar(`select coalesce(max(id), 0) from sys_retrieval_trace where user_id = ${loginUserId};`) || '0',
    )

    await evaluate(`(() => {
      const btn = [...document.querySelectorAll('.chat-input button')]
        .find((b) => (b.textContent || '').includes('发送'))
      if (!btn) throw new Error('找不到发送按钮')
      btn.click()
      return true
    })()`)

    // ①-a 🔴 **描述期间页面看得到「正在理解图片」**（`G6-11` 的页面半边）。
    //      `S6-M09-B` 时这一条**挂起**了，原因不在前端也不在 `T-907`：整条 SSE 被
    //      攒到控制器方法返回后**一次性重放**，`DESCRIBING` 与 `DONE` 之间只隔几毫秒，
    //      页面根本来不及渲染。`S6-M05-C` 的 P0 修复（编排搬出请求线程）之后，
    //      这两帧之间是**真的 40~60 秒**（真模型实测），所以这条断言现在必须成立。
    //      ⚠️ 它同时是那次修复**最有力的页面级证据**：修复若被回退，这里会**超时转红**
    //      —— 而不是像以前那样只是"看不见"（那是没法判红的一种失败）。
    await waitFor(`!!document.querySelector('.as-line.describing')`,
      '页面在描述期间显示「正在理解图片」（G6-11）', 30000)

    // ① 页面把**描述**渲染出来了（`.as-done`，见 `ChatView` 的 attach-status 区块）。
    //    真模型耗时以十秒计，给足 180s。
    await waitFor(`!!document.querySelector('.as-done')`, '页面渲染出图片描述', 180000)

    // ② 库里那行「真的被理解过」—— 三个字段必须同时非空
    const row = mysqlScalar(
      `select concat_ws('|', coalesce(prompt_version,''), coalesce(degraded,''), `
        + `length(coalesce(description,'')), length(coalesce(search_summary,'')), `
        + `coalesce(vl_model_name,'')) from sys_chat_attachment `
        + `where user_id = ${loginUserId} order by id desc limit 1;`,
    )
    const [pv, degraded, descLen, sumLen, vlName] = row.split('|')
    check(!!pv, 'prompt_version 没落库 —— 「改 prompt 缓存不失效」那条就无从回溯（需求 §4.8）')
    check(!!vlName, 'vl_model_name 没落库 —— 换 VL 模型（T-908）时认不出这条是谁产的')
    check(
      degraded === '0',
      `这张图**降级**了（degraded=${degraded}）—— 说明 VL 没跑成：要么未配置、要么调用失败。`
      + '本步喂的是真图表，VL 可用时必须给出描述；降级在这里是**真失败**而不是可接受的结果',
    )
    check(
      Number(descLen) > 0 && Number(sumLen) > 0,
      `描述为空：desc=${descLen} 字 / searchSummary=${sumLen} 字 —— 一次调用两产出一件都没成`,
    )

    // ③ 检索真的用上了合成 query（§4.5 的 v1 拼接口径）
    // ⚠️ 列名是 `query_text` 不是 `query` —— 实体的字段叫 query，但 `V15` 建表时
    // 避开了 `query` 这个在各家 SQL 里含义微妙的词。按字段名写会 status=1，
    // 而 `mysqlScalar` 把 stderr 吞了（`2>/dev/null`），报出来只有一句空原因。
    // 🔴 **必须等到本轮那一行出现**（`id > traceFloor`）—— 上面第 ① 条等到的
    // `.as-done` 只是**描述完成**，这一轮的打点那时还没写（见 `awaitSqlScalar`）。
    const query = await awaitSqlScalar(
      `select query_text from sys_retrieval_trace where user_id = ${loginUserId} `
        + `and id > ${traceFloor} order by id desc limit 1;`,
      '本轮问答的检索打点落库（③ 的 query）',
    )
    check((query || '').includes('这张图讲的是什么'), `检索用的 query 丢了原问题：${query}`)
    check(
      (query || '').length > '这张图讲的是什么？请说明检索链路有哪几个阶段。'.length,
      `检索用的 query 与问题等长，说明**没有拼上 searchSummary**：${query}`,
    )

    // ④ T-905 的采集点：VL 描述这次调用真的记了用量
    const vlCalls = mysqlScalar(
      `select coalesce(sum(calls), 0) from sys_model_usage `
        + `where purpose = 'VL_DESC' and occurred_at = curdate();`,
    )
    check(Number(vlCalls) > 0, 'VL_DESC 用量一行都没记 —— T-905 的第六个采集点没接上')

    // ⑤ 缓存真的落了键，且 key 是**三段**（md5 : vlModelId : PROMPT_VERSION，§4.8）。
    //    这是「同图同模型不重复调用」的**机制证据** —— 没有它，"命中缓存"只是一句声明。
    const md5 = mysqlScalar(
      `select md5 from sys_chat_attachment where user_id = ${loginUserId} order by id desc limit 1;`,
    )
    const keys = execFileSync('docker', ['exec', 'kb-valkey', 'redis-cli', '--scan',
      '--pattern', 'vision:desc:*'], { encoding: 'utf8' }).trim().split('\n').filter(Boolean)
    const mine = keys.filter((k) => k.startsWith(`vision:desc:${md5}:`) && !k.endsWith(':kw'))
    // ⚠️ 同一个逻辑条目占了**两个物理 key**（正文用主 key、检索摘要用 `:kw` 后缀）——
    // 理由见 `VisionDescriptionCache` 的类注释（拼分隔符会在描述里出现分隔符时错切）。
    // 所以这里要先把 `:kw` 那条滤掉再判"三段"，否则拿到的是六段的那条。
    check(mine.length > 0, `Redis 里没有该图的描述缓存：keys=${JSON.stringify(keys)} md5=${md5}`)
    const segments = mine[0].split(':')
    check(
      segments.length === 5 && segments[4] === 'v1',
      `缓存 key 不是「前缀:md5:vlModelId:PROMPT_VERSION」三段：${mine[0]}`,
    )

    await shot('chat-attachment-understood')
    return `promptVersion=${pv} · 描述 ${descLen} 字 · 关键词 ${sumLen} 字 · VL=${vlName} · VL_DESC 调用 ${vlCalls} 次`
  })
  await step('反馈落库（G5-14）：页面点「点赞」→「点踩+原因」，库里真的跟着变', async () => {
    // ⑥ 在页面上问过一次，取那条**回答**的 id（DOM 里没有 messageId）
    const messageId = Number(
      mysqlScalar(
        `select id from sys_chat_message where role = 'assistant' and session_id in `
          + `(select id from sys_chat_session where user_id = ${loginUserId} and id > ${sessionFloor}) `
          + `order by id desc limit 1;`,
      ),
    )
    check(Number.isFinite(messageId) && messageId > 0, '取不到页面上那条回答的 messageId')

    // 取**最后一个可用**的动作区 —— 欢迎语那一条也有 `.mf-actions`，但它的按钮是禁用的
    // （它不是真实消息，后端没有它的行）。按"最后一个"取会随页面状态漂移，按"可用的"取才稳
    await evaluate(`(() => {
      const rows = [...document.querySelectorAll('.msg.assistant .mf-actions')]
      const usable = rows.filter((r) => {
        const b = [...r.querySelectorAll('button')].find((x) => (x.textContent || '').includes('点赞'))
        return b && !b.disabled
      })
      if (!usable.length) throw new Error('页面上没有一个可用的点赞按钮')
      const btn = [...usable[usable.length - 1].querySelectorAll('button')]
        .find((x) => (x.textContent || '').includes('点赞'))
      btn.click()
      return true
    })()`)
    await waitFor(
      `[...document.querySelectorAll('.msg.assistant .mf-actions')].some((e) => e.textContent.includes('已点赞'))`,
      '点赞生效',
      15000,
    )
    const afterLike = mysqlScalar(
      `select coalesce(feedback,'NULL') from sys_chat_message where id = ${messageId};`,
    )
    check(afterLike === 'like', `点赞后库里 feedback 应为 like，实际 ${afterLike}`)

    // 改成点踩 + 选原因 → 覆盖语义（库里只留终态），且原因跟着换
    await evaluate(`(() => {
      const rows = [...document.querySelectorAll('.msg.assistant .mf-actions')]
      const usable = rows.filter((r) => {
        const b = [...r.querySelectorAll('button')].find((x) => (x.textContent || '').includes('点踩'))
        return b && !b.disabled
      })
      if (!usable.length) throw new Error('页面上没有一个可用的点踩按钮')
      const btn = [...usable[usable.length - 1].querySelectorAll('button')]
        .find((x) => (x.textContent || '').includes('点踩'))
      btn.click()
      return true
    })()`)
    // ⚠️ **要挑"可见的"那一个**：`el-popover` 的内容会为**每条** assistant 消息都渲染一份
    // （欢迎语那条也有，只是它的入口是禁用的），所以 `.fb-reason` 一次能查到 10 个（2×5）。
    // 直接取 `[0]` 会点到欢迎语那一份 —— 它的 `canFeedback` 为 false，**静默返回、一个请求都不发**，
    // 表现是"点了没反应"（实测：backend 日志里连一条 `/chat/feedback` 都没有）。
    await waitFor(
      `[...document.querySelectorAll('.fb-reason')].some((e) => e.getBoundingClientRect().width > 0)`,
      '点踩原因弹层出现',
      15000,
    )
    // 选中 + 读文案 + 点击放在**同一次 evaluate** 里：分两次调用之间有往返，
    // 中间弹层一旦消失，第二次就会点到 null
    const reason = await evaluate(`(() => {
      const visible = [...document.querySelectorAll('.fb-reason')]
        .filter((e) => e.getBoundingClientRect().width > 0)
      if (!visible.length) throw new Error('没有可见的点踩原因项')
      const label = visible[0].textContent.trim()
      visible[0].click()
      return label
    })()`)
    try {
      await waitFor(
        `[...document.querySelectorAll('.msg.assistant .mf-actions')].some((e) => e.textContent.includes('已点踩'))`,
        '点踩生效',
        15000,
      )
    } catch (e) {
      // 失败时把**现场**带出来：只报"等待超时"等于没说 —— 是没发请求、
      // 是请求被拒、还是点了没反应，三种原因的下一步完全不同
      await shot('chat-feedback-timeout')
      const diag = await evaluate(`(() => ({
        rows: [...document.querySelectorAll('.msg.assistant .mf-actions')].map((r) => r.textContent.trim()),
        reasons: document.querySelectorAll('.fb-reason').length,
        toasts: [...document.querySelectorAll('.el-message')].map((t) => t.textContent.trim()),
      }))()`)
      throw new Error(`${e.message} · 现场=${JSON.stringify(diag)}`)
    }

    const picked = mysqlScalar(
      `select concat_ws('|', feedback, coalesce(feedback_reason,'NULL')) `
        + `from sys_chat_message where id = ${messageId};`,
    )
    const [fb, fbReason] = picked.split('|')
    check(fb === 'unlike', `改成点踩后库里应为 unlike，实际 ${fb}`)
    check(fbReason === reason, `库里原因「${fbReason}」与页面选的「${reason}」不一致`)

    await shot('chat-feedback')
    return `messageId=${messageId} · 点赞 → 点踩（${reason}），两次都落库`
  })

  // ---------- ⑦ 收尾 ----------
  // 🔴 这一步**只负责报告结论**：真正的清理在 `cleanupProbe()`，而它由下面的
  //    `finally` **无条件**调用（`#186`：`step()` 失败会 rethrow，所以"清理是一个 step"
  //    等于"中途失败就不清理"）。这里再调一次是**有意的幂等重复** —— 见该函数的 javadoc。
  await step('收尾：清理探针库与会话行', async () => {
    const { residue, left } = await cleanupProbe()
    check(kbResidueClean(residue), `探针库仍有残留：${describeKbResidue(residue)}`)
    check(left === 0, `仍残留 ${left} 行会话族数据（含问答附件）`)
    return `探针库已清（${describeKbResidue(residue)}）· 会话族残留 ${left} 行`
  })
}

// ---------------------------------------------------------------- 清理（幂等）

/**
 * 清理本脚本造的一切，并回报残留。
 *
 * <h3>🔴 为什么它必须能从 `finally` 里被调 —— `#186` 的根因</h3>
 * <p>原先清理是**一个普通的 step**（⑦ 收尾），而 {@link step} 失败时**会 rethrow**
 * ⇒ 任何一步没走通，收尾就**根本不会执行**。实测代价：`S6-M05-C` 期间两次中断
 * 各留下一个 `kb_e2echat*` 探针库 + 它的文档/分片/作业 —— 而探针库会把 `sys_kb`
 * 顶上去、同时**搞红 4 个 kb 用例类**（`#186` 原文）。本批两轮中断各手工清了一次。
 *
 * <p>所以：**清理从"一步"变成"一条必走的路径"**，⑦ 收尾那一步改为只**报告结论**。
 *
 * <p><b>幂等</b>：删除都是「水位」形状（`id > 水位` / `session_id > 水位`），跑两遍是
 * no-op；`teardownKbProbe` 也自带"删不到就跳过"。所以正常路径上它会跑两次
 * （⑦ 一次、`finally` 一次）—— 这是**有意的**：宁可多做一次无副作用的清理，
 * 也不要把"这条路一定会走"当成前提。
 *
 * <p><b>半途状态安全</b>：登录之前就失败时 `loginUserId` / `sessionFloor` 还是 0 ——
 * 那时会话族本来就没被造出来，跳过即可（kb 族与会话族**分开判**，不要用一个
 * `if` 把两者绑在一起：它们的水位来源不同，绑起来将来一定漏一处）。
 */
async function cleanupProbe() {
  // 会话族：**先子后父**（明细按 trace_id 顺上去、消息按 session_id 顺上去）。
  // ⚠️ 顺序反了会留下孤儿明细，而它们**不属于任何探针前缀**，没有别的机制能发现。
  // 口径是「本账号 + id 大于水位」—— 页面级那一步自己开的会话也在这张网里
  if (loginUserId > 0) {
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

    // `T-805` 的附件：**行 + 桶里那个对象都要收**。
    // ⚠️ 与上面几族不同 —— 它**不属于探针库**（`teardownKbProbe` 看不见它），
    // 而 `probe-teardown` 的 MinIO 清理只覆盖库族（要 join `sys_kb`）。
    // 问答附件是按**人**归属的前缀，与 `avatars/` 同一条取舍 ⇒ 脚本自己收。
    // 🔴 `#163`：**不能只删"自己那一个"**。
    //    下一行删的是该用户**全部**附件行，若只删自己那一个对象，**更早几轮的对象
    //    就永远成了无主孤儿** —— 实测桶里躺着 **10 个** `chat-attachments/689/…`
    //    而对应的 DB 行**一个都没有**（全是历轮 E2E 漏下的）。
    //    口径对齐：**行删多少，对象就收多少**。
    //    ⚠️ 按 **userId 前缀**列全量，**不记"我知道的那一个"** —— 后者正是漏的成因：
    //      脚本每轮都会删掉该用户**全部**行，于是上一轮的对象在下一次跑的时候就
    //      没有任何行指向它了，而脚本只记得"自己造的那一个"。
    const myAttachmentKeys = minioKeysOfUserPrefix([loginUserId])
    if (myAttachmentKeys.length) {
      minioRemove(myAttachmentKeys)
    }
    mysqlScalar(`delete from sys_chat_attachment where user_id = ${loginUserId};`)
  }
  if (attachmentFixture && existsSync(attachmentFixture)) {
    rmSync(attachmentFixture, { force: true })
  }

  // ⚠️ `teardownKbProbe` 是 async（它要等 Milvus 那边确认）—— 漏了 await 拿到的是 Promise，
  // 而 `residue` 是 undefined，报出来的是 "Cannot read properties of undefined"，
  // 与"清理失败"看起来毫不相干（本脚本第一版正是如此）
  const r = await teardownKbProbe(KB_CODE)

  // 独立观测点：不看清理命令的返回值，直接再查一次
  const left = loginUserId > 0
    ? Number(
        mysqlScalar(
          `select (select count(*) from sys_chat_session where user_id = ${loginUserId} and id > ${sessionFloor}) `
            + `+ (select count(*) from sys_chat_message where session_id > ${sessionFloor}) `
            + `+ (select count(*) from sys_retrieval_trace where user_id = ${loginUserId} and session_id > ${sessionFloor}) `
            + `+ (select count(*) from sys_retrieval_trace_chunk where trace_id in `
            + `(select trace_id from sys_retrieval_trace where user_id = ${loginUserId} and session_id > ${sessionFloor})) `
            + `+ (select count(*) from sys_chat_attachment where user_id = ${loginUserId});`,
        ),
      )
    : 0
  return { residue: r.residue, left }
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
  // 🔴 `#186`：清理是**必走路径**，不是"一步"。
  //    `step()` 失败会 rethrow ⇒ 中途失败时 ⑦ 收尾根本不会执行，于是探针库留在库里，
  //    把 `sys_kb` 顶上去、同时搞红 4 个 kb 用例类。实测本批两轮中断各手工清了一次。
  //    ⚠️ 这里**不再 check**（那会掩盖真正的失败原因）：清理结果由 ⑦ 收尾那一步报告，
  //    这里只在**兜底清理自己炸了**时说一声 —— 那时才需要人工去核残留。
  try {
    await cleanupProbe()
  } catch (e) {
    console.error(`  ⚠️ 兜底清理失败（正常路径的 ⑦ 收尾会报残留，这里只提示）：${e.message}`)
  }
  try {
    ws?.close()
  } catch {}
  child?.kill()
  await sleep(300)
}
