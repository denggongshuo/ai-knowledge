/**
 * `S7-M06-B` 页面级 E2E：**文档属性修改 + 单篇重建 + 密级贯通**（`T-601` / `T-610`）。
 *
 * <h3>覆盖的判据</h3>
 * <ul>
 *   <li><b>`G7-3`</b> 密级只能提高：提高成功 + **降低被拒**（两个方向都要）·
 *       且**拒了之后库里那行没变**（"返回错误"与"什么都没发生"是两件事）；</li>
 *   <li><b>`I-6` 防降级绕过**在移动这条路上**：移到**密级更低**的库被拒，且库归属没变
 *       —— 「换一个密级更低的库」正是 `I-6` 原文点名要防的那条绕过路径；</li>
 *   <li><b>`G7-4`</b> 移动知识库：响应 `needReindex` + `sys_document.kb_id` 真的变了 +
 *       分片也跟着换库 + **旧库分区向量归零** + **新库当前代分区向量 > 0**；</li>
 *   <li><b>`G7-4`</b> 两条新审计点**都留痕**且 `result` 分开报（成功一条 + 那次被拒的 `FAILED` 一条）；</li>
 *   <li><b>`T-610`</b> 单篇重建受理（返回 `taskId` + `ACCEPTED`），且**不推进全库的「代」**；</li>
 *   <li>页面级：密级下拉**过滤掉低于当前生效值的选项**、当前值标注「（继承知识库）」、
 *       「直接保存」真的**发得出 `PATCH` 请求**、移动前**弹变更确认**；</li>
 *   <li>`tags` **明确拒绝**（不是静默忽略）；</li>
 *   <li>收尾**自清残留 0 行**（断言，不是日志 —— `T-O21` 的口径）。</li>
 * </ul>
 *
 * <h3>⚠️ 一处**刻意不在本脚本**里验：检索侧的密级上限</h3>
 * 它需要「密级上限 = 内部」的账号，而本脚本手上只有种子超管（上限 = 绝密）。
 * 那一半由后端用例
 * `RetrievalScopeFlowTest#docSecretLevelIsTheMaxOfDocAndKb` 覆盖 —— 那里能直接构造
 * `LoginUser`，而且做了 **A/B/A 正对照**（先确认搜得到 → 提密级后搜不到 → 撤回覆盖又搜得到）。
 * **写在这里会验成假的**（超管什么都看得见，判据恒绿），所以不写。
 *
 * <h3>前置</h3>
 * <pre>
 *   后端 8080（真后端）· 前端 5174：`VITE_USE_MOCK=false npm run dev -- --host 127.0.0.1 --port 5174`
 *   运行：node scripts/e2e-doc-attributes.mjs --base=http://127.0.0.1:5174
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
  kbResidueClean,
  describeKbResidue,
  milvusVectorCount,
  kbIdsOf,
} from './lib/probe-teardown.mjs'
import { probeDevServer, reportPrecondition, EXIT_PRECONDITION } from './lib/dev-server.mjs'

const arg = (name, fallback) => (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://localhost:5174')
const API = arg('api', 'http://localhost:8080')

const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'

const stamp = Date.now()
/**
 * 🔴 **每次运行用**独立**的 profile 目录**（本脚本第一版的教训）。
 *
 * <p>Edge 会把"新起的实例"**交给已存在的同 profile 实例**接管：于是新进程立刻退出、
 * CDP 端口归旧实例所有，而脚本连上的是**上一轮那个半死的浏览器**（页面停在 `about:blank`）。
 * 现象是 `Page.enable` 永远不回包 —— 脚本没有任何输出、也没有报错，白等到超时。
 * 上一轮被 `TaskStop` 杀掉时尤其容易留下这种实例。
 */
const runTag = `p${stamp % 100000}`
const PROFILE = `F:/work/code/ai-knowledge/temp/edge-e2e-docattr-${runTag}`
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9350)

const PROBE = { account: 'admin', password: 'Admin@123' }
/** 源库=内部、目标库=绝密、低密级库=公开 —— 三档刚好把 `I-6` 的**两个方向**都摆出来。 */
const SRC_CODE = `kb_e2edocattr_s_${stamp}`
const TGT_CODE = `kb_e2edocattr_t_${stamp}`
const LOW_CODE = `kb_e2edocattr_l_${stamp}`
const DOC_NAME = `探针文档-属性-${stamp}.md`
/** 正文里的关键词：移动之后要靠它在**新库**里搜到（token 必须有交集）。 */
const MAGIC = `山茶花协议${stamp}`
const CONTENT = `# 探针文档\n\n${MAGIC} 是本文档用来验证"移动知识库之后向量真的换了分区"的关键词。\n`

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

// ---------------------------------------------------------------- CDP
let ws
let msgId = 0
const pending = new Map()
const consoleMsgs = []
/**
 * 本脚本关心的响应（`PATCH /api/doc/{id}/attributes` 与 `POST /api/doc/{id}/reindex`）。
 *
 * ⚠️ 不要写成 `doc/*` 紧接着 `/reindex` 的形式 —— 那个 `*` 加 `/` 会**提前闭合块注释**，
 * 而报错落在几十行之后（`SyntaxError: Unexpected identifier`），看着像别处坏了。
 */
const docWrites = []

function send(method, params = {}) {
  const id = ++msgId
  return new Promise((resolve, reject) => {
    // ⚠️ **必须带超时**：CDP 不回包时原来会**永久挂起**（脚本不再有任何输出、
    // 也没有错误），现象与"卡在某个业务步骤"长得一样。本脚本第一版就这么白等了 3 分钟。
    const timer = setTimeout(() => {
      pending.delete(id)
      reject(new Error(`CDP 无响应（${method}）：超过 20s —— 多半是连到了**别的** Edge 实例`))
    }, 20000)
    pending.set(id, {
      resolve: (v) => {
        clearTimeout(timer)
        resolve(v)
      },
      reject: (e) => {
        clearTimeout(timer)
        reject(e)
      },
    })
    ws.send(JSON.stringify({ id, method, params }))
  })
}
async function evaluate(expression) {
  const r = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
  if (r.exceptionDetails) throw new Error(`页面脚本抛错：${r.exceptionDetails.text ?? ''} ${r.exceptionDetails.exception?.description ?? ''}`)
  return r.result?.value
}
async function waitFor(expression, { timeout = 15000, interval = 150, label = expression } = {}) {
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
  await sleep(700)
}
/** DOM 上的 `click()`（不是 CDP 鼠标）—— 打开下拉靠它，**选中**靠键盘。 */
async function clickReal(selector) {
  const ok = await evaluate(`(() => {
    const el = document.querySelector(${JSON.stringify(selector)})
    if (!el) return false
    el.click()
    return true
  })()`)
  if (!ok) throw new Error(`点不到元素：${selector}`)
  await sleep(300)
}
async function press(key, code, vk) {
  await send('Input.dispatchKeyEvent', { type: 'rawKeyDown', key, code, windowsVirtualKeyCode: vk })
  await send('Input.dispatchKeyEvent', { type: 'keyUp', key, code, windowsVirtualKeyCode: vk })
  await sleep(120)
}
function onCdpMessage(raw) {
  // 🔴 **载荷在 `raw.data` 里**（`onmessage` 收到的是 `MessageEvent`，不是字符串）。
  //    写成 `JSON.parse(raw)` 会把 `MessageEvent` 序列化成 `"[object MessageEvent]"` ⇒
  //    每次都抛错、被 catch 吞掉 ⇒ **所有回包静默丢弃**。
  //    现象是"`Page.enable` 永远不回包"，而真因与 `Page.enable` 毫无关系。
  //    既有脚本（`e2e-doc-detail.mjs` 等）用的都是 `raw.data`。
  let msg
  try {
    msg = JSON.parse(raw.data)
  } catch {
    return
  }
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id)
    pending.delete(msg.id)
    if (msg.error) reject(new Error(msg.error.message))
    else resolve(msg.result)
    return
  }
  if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
    consoleMsgs.push(msg.params.args.map((a) => a.value ?? a.description ?? '').join(' '))
  }
  if (msg.method === 'Network.responseReceived') {
    const r = msg.params.response
    if (/\/(api\/)?doc\/\d+\/(attributes|reindex)/.test(r.url)) {
      docWrites.push({ url: r.url.replace(API, ''), status: r.status, method: msg.params.type })
    }
  }
}
async function waitForEdge() {
  const deadline = Date.now() + 20000
  while (Date.now() < deadline) {
    try {
      const res = await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`)
      const list = await res.json()
      const page = list.find((t) => t.type === 'page')
      if (page?.webSocketDebuggerUrl) return page.webSocketDebuggerUrl
    } catch {
      /* 还没起来 */
    }
    await sleep(300)
  }
  throw new Error('Edge 的 CDP 端口没起来')
}

// ---------------------------------------------------------------- 状态
let edge
let srcKbId = 0
let tgtKbId = 0
let lowKbId = 0
let docId = 0

async function main() {
  // ---------- ① 前置：**必须是真后端模式** ----------
  const diag = await probeDevServer(BASE)
  if (!diag.ok) {
    console.error(reportPrecondition(diag, 'e2e-doc-attributes'))
    process.exit(EXIT_PRECONDITION)
  }
  if (diag.mode !== 'real') {
    console.error(`\n✗ e2e-doc-attributes：**前置不满足**（exit ${EXIT_PRECONDITION}）—— 只在**真后端模式**下成立\n`)
    console.error('  • 本脚本验的是"移动之后向量真的换了分区"，mock 模式没有 Milvus 这一层。')
    console.error('  • 下一步：`VITE_USE_MOCK=false npm run dev -- --host 127.0.0.1 --port 5174`。\n')
    process.exit(EXIT_PRECONDITION)
  }
  observe('dev server 就绪（真后端模式）', diag.notes?.join(' / ') ?? '')

  // ---------- ② 登录 ----------
  token = bizOk(await api('/auth/login', { method: 'POST', body: { ...PROBE, remember: true }, as: '' }), '登录').accessToken
  check(!!token, '拿到 accessToken')

  // ---------- ③ 建三个探针库（三档密级）----------
  srcKbId = await createKb(SRC_CODE, '内部', 'INTERNAL')
  tgtKbId = await createKb(TGT_CODE, '绝密', 'SECRET')
  lowKbId = await createKb(LOW_CODE, '公开', 'PUBLIC')
  check(srcKbId > 0 && tgtKbId > 0 && lowKbId > 0, '三个探针库已建（公开 / 内部 / 绝密）',
    `src=${srcKbId} tgt=${tgtKbId} low=${lowKbId}`)

  // ---------- ④ 真上传 → 等 READY（要真向量，移动才有东西可搬）----------
  const form = new FormData()
  form.append('file', new Blob([CONTENT], { type: 'text/markdown' }), DOC_NAME)
  bizOk(await api(`/kb/${srcKbId}/doc/upload`, { method: 'POST', body: form, raw: true }), '上传探针文档')
  docId = Number(mysqlScalar(
    `select id from sys_document where kb_id = ${srcKbId} and title like '%属性%' order by id desc limit 1;`) || 0)
  check(docId > 0, '探针文档已入库', `docId=${docId}`)
  await waitDocReady(srcKbId, 1)
  check(true, '解析 + 索引完成（READY —— 本仓口径里它就等于"可被检索"）')

  // `sys_kb.index_generation` 是"当前代"，下面移动要断言向量落到了这一代的分区
  const srcGenBefore = Number(mysqlScalar(`select index_generation from sys_kb where id = ${srcKbId};`) || 0)

  // ---------- ⑤ `G7-3`：密级只能提高（两个方向都要）----------
  const lower = await api(`/doc/${docId}/attributes`, { method: 'PATCH', body: { secretLevel: 'PUBLIC' } })
  check(lower.json?.code === 10030, '🔴 降到低于知识库密级被拒（`PATCH` → 10030）',
    `code=${lower.json?.code} msg=${lower.json?.message ?? ''}`)
  check(String(mysqlScalar(`select coalesce(secret_level,'NULL') from sys_document where id = ${docId};`)) === 'NULL',
    '🔴 拒了之后库里那行**一个字都没变**（"返回错误"≠"什么都没发生"）')

  const raise = await api(`/doc/${docId}/attributes`, { method: 'PATCH', body: { secretLevel: 'CONFIDENTIAL' } })
  check(raise.json?.code === 0 && raise.json?.data?.needReindex === false,
    '提高密级成功，且 `needReindex=false`（需求 §6.7：只改密级无需重建）',
    `code=${raise.json?.code} needReindex=${raise.json?.data?.needReindex}`)
  check(String(mysqlScalar(`select secret_level from sys_document where id = ${docId};`)) === 'CONFIDENTIAL',
    '库里真的写成了 CONFIDENTIAL')
  // ⚠️ **不要**在这里"降回来"：`PATCH` 的设计就是只能提高（`I-6`），降不回去。
  //    第一版写了一句 `secretLevel: 'SECRET'` 当"回流"，那不是回流、是空操作 ——
  //    后果是文档停在绝密，**后面移动那一段被 I-6 的防降级挡住了**（守卫是对的，夹具错了）。
  //    于是目标库的密级取到「绝密」：移动是"往更严的库搬"，这才走得通。

  // ---------- ⑥ `tags` 明确拒绝（不是静默忽略）----------
  const tags = await api(`/doc/${docId}/attributes`, { method: 'PATCH', body: { tags: ['探针甲'] } })
  check(tags.json?.code === 10030 && String(tags.json?.message ?? '').includes('不支持'),
    '`tags` 明确回"本版本不支持"（静默忽略会让用户以为存上了）',
    `code=${tags.json?.code} msg=${tags.json?.message ?? ''}`)

  // ---------- ⑦ `T-610` 单篇重建：受理 + **不推进全库的代** ----------
  const genBefore = Number(mysqlScalar(`select index_generation from sys_kb where id = ${srcKbId};`) || 0)
  const reindex = await api(`/doc/${docId}/reindex`, { method: 'POST' })
  check(reindex.json?.code === 0 && reindex.json?.data?.status === 'ACCEPTED' && !!reindex.json?.data?.taskId,
    '单篇重建受理（返回 `taskId` + `status=ACCEPTED`）',
    `taskId=${reindex.json?.data?.taskId} status=${reindex.json?.data?.status}`)
  const genAfter = Number(mysqlScalar(`select index_generation from sys_kb where id = ${srcKbId};`) || 0)
  check(genAfter === genBefore,
    '🔴 单篇重建**没有推进全库的「代」**（推进了会把全库其余文档判成旧代）',
    `${genBefore} → ${genAfter}`)
  check(mysqlCount(`select count(*) from sys_audit_log where action='重建索引' and target like '%文档#${docId}%' and result='SUCCESS';`) >= 1,
    '审计留痕：文档 / 重建索引（需求 05 §6 的动作表逐字列着它）')

  // ---------- ⑩ 页面级：属性面板 ----------
  edge = spawn(EDGE, [
    '--headless=new', `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${PROFILE}`,
    '--no-first-run', '--no-default-browser-check', '--window-size=1440,900', 'about:blank',
  ], { stdio: 'ignore' })
  ws = new WebSocket(await waitForEdge())
  // ⚠️ 握手也要带超时：连上一个**半死的** Edge（进程在、协议不答）时这里会永久等，
  //    而现象同样是"脚本没有任何输出"。**上机前先清掉同 profile 的残留 Edge** ——
  //    Edge 会把活交给已存在的同 profile 实例，于是新起的那个根本没页面可连。
  await new Promise((res, rej) => {
    const timer = setTimeout(() => rej(new Error('CDP 握手超时（15s）—— 检查是否有同 profile 的残留 Edge')), 15000)
    ws.onopen = () => {
      clearTimeout(timer)
      res()
    }
    ws.onerror = (e) => {
      clearTimeout(timer)
      rej(new Error(`CDP 握手失败：${e?.message ?? 'unknown'}`))
    }
  })
  ws.onmessage = onCdpMessage
  // 连上之后先自检一句：**连到的到底是谁**。少了它，"连到旧实例"只能表现为"某一步超时"，
  // 而超时的那一步（`Page.enable`）与真因（浏览器不是这一轮起的那个）看不出关系。
  const targets = await send('Target.getTargets')
  observe('已连上的浏览器目标',
    (targets.targetInfos ?? []).map((t) => `${t.type}:${String(t.url).slice(0, 40)}`).join(' | ') || '(无)')
  await send('Page.enable')
  await send('Runtime.enable')
  await send('Network.enable')
  await send('DOM.enable')
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

  // 路由是 hash 模式；token 存 localStorage
  await goto(`${BASE}/#/login`)
  await evaluate(`localStorage.setItem('ak_token', ${JSON.stringify(token)}); localStorage.setItem('ak_remember','1'); 'ok'`)
  // 🔴 注入 token 后**必须整页 reload**：`useAuthStore` 是**创建时**读一次 localStorage，
  //    不 reload 也能过，但那靠"注入时 app 还没挂载"的时序运气（上一批实测踩到）
  await goto(`${BASE}/#/doc/${docId}`)
  await send('Page.reload')
  await waitFor(`!location.hash.includes('/login')`, { timeout: 15000, label: '登录态生效' })
  await waitFor(`!!document.querySelector('.page-title')`, { timeout: 20000, label: '详情页挂载' })

  // 🔴 把文档恢复成"继承"态（`secret_level = null`），专门为了验需求 §6.7 那条标注。
  //    为什么用 SQL 改这一列：接口**没有**"取消覆盖"这个动作（`PATCH` 只能提高），
  //    而这一列的语义正是本批新加的（`NULL` = 未覆盖）。这是一次**夹具调整**，不是业务写入。
  mysqlScalar(`update sys_document set secret_level = null where id = ${docId};`)
  await goto(`${BASE}/#/doc/${docId}`)
  await send('Page.reload')
  await waitFor(`!!document.querySelector('.page-title')`, { timeout: 20000, label: '详情页重新挂载' })

  // ⚠️ 生效密级从**接口**读，而且**必须在清掉覆盖之后再读**（清之前是 SECRET、清之后是目标库的机密）。
  //    也不在脚本里用 SQL 算 `max(文档, 库)`：那个序数比较只有 `SecretLevel` 一处口径，
  //    在脚本里改用字符串比较会把密级**做反**（而现象是"有人看得见绝密"而不是报错）。
  const meta = bizOk(await api(`/doc/${docId}`), '文档元信息')
  const effective = meta.securityLevel
  observe('生效密级 / 库级 / 文档自己',
    `${meta.securityLevel} / ${meta.kbSecurityLevel} / ${meta.ownSecurityLevel ?? 'NULL(继承)'}`)

  const selectText = await waitFor(`(() => {
      const el = document.querySelector('.el-select .el-select__wrapper')
      return el && el.textContent.trim() ? el.textContent.trim() : null
  })()`, { timeout: 8000, label: '密级选择框渲染出当前值' })
  check(selectText.includes('继承知识库'),
    '🔴 文档未覆盖时，密级显示的是「X（继承知识库）」（需求 §6.7 明文要求的标注）',
    `选择框文案="${selectText}"`)

  // 下拉选项数 = 生效值及以上的档数（不是 4 档全列）
  await clickReal('.el-select .el-select__wrapper')
  const optionCount = await waitFor(`(() => {
      const items = [...document.querySelectorAll('.el-select-dropdown__item')].filter((i) => i.offsetParent !== null)
      return items.length || null
  })()`, { timeout: 8000, label: '密级下拉展开' })
  const expectedOptions = 4 - ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'SECRET'].indexOf(effective)
  check(optionCount === expectedOptions,
    '🔴 密级下拉**过滤掉了低于当前生效值的选项**（不是 4 档全列）',
    `可见选项=${optionCount} 应为=${expectedOptions} 生效密级=${effective}`)
  await press('Escape', 'Escape', 27)

  // 保存按钮：**没有改动时必须禁用**（不给"点了没反应"的形态）
  const disabledWhenClean = await evaluate(`(() => {
      const btns = [...document.querySelectorAll('.sb-actions button')]
      const save = btns.find((b) => b.textContent.includes('保存属性'))
      return save ? (save.disabled || save.classList.contains('is-disabled')) : null
  })()`)
  check(disabledWhenClean === true, '无改动时「保存属性」是**禁用**的（不是"点了没反应"）')

  // 🔴 页面级真动作：改密级 → 保存 → **真的发出 `PATCH` 请求**
  await clickReal('.el-select .el-select__wrapper')
  await waitFor(`[...document.querySelectorAll('.el-select-dropdown__item')]
      .some((i) => i.offsetParent !== null)`, { timeout: 8000, label: '下拉展开' })
  // ⚠️ **用键盘选，不用点浮层**：EP 的下拉是传送到 body 的 popper（带动画与自动定位），
  //    "先量 rect 再发鼠标事件"对它不可靠 —— 现象是"下拉开了但选中没生效"
  await press('ArrowDown', 'ArrowDown', 40)
  await press('Enter', 'Enter', 13)
  await sleep(400)
  const saveEnabled = await evaluate(`(() => {
      const btns = [...document.querySelectorAll('.sb-actions button')]
      const save = btns.find((b) => b.textContent.includes('保存属性'))
      return save ? !(save.disabled || save.classList.contains('is-disabled')) : null
  })()`)
  if (saveEnabled) {
    await clickReal('.sb-actions button.el-button--primary')
    await sleep(1500)
  }
  check(docWrites.some((r) => r.url.includes('/attributes')),
    '🔴 页面上的「保存属性」**真的发了 `PATCH /doc/{id}/attributes`**'
      + '（接真之前按钮是置灰的：那时点了会弹"保存成功"而一个请求都没发）',
    docWrites.map((r) => `${r.url}=${r.status}`).join(' · ') || '(一个都没发)')

  // ---------- ⑧ `G7-4` 移动知识库 ----------
  // 🔴 **先验"往更松的库搬会被拒"**：`I-6` 原文写它存在的理由就是「否则用户可把机密文档
  //    降级绕过管控」，而"换一个密级更低的库"正是那条绕过路径。文档当前生效 = 机密，
  //    公开库低于它 ⇒ 必须拒，且**库归属不能变**。
  const beforeKbId = Number(mysqlScalar(`select kb_id from sys_document where id = ${docId};`))
  const illegalMove = await api(`/doc/${docId}/attributes`, { method: 'PATCH', body: { kbId: lowKbId } })
  check(illegalMove.json?.code === 10022,
    '🔴 移到**密级更低**的库被拒（`I-6` 防降级绕过：换库不能把文档降级）',
    `code=${illegalMove.json?.code} msg=${illegalMove.json?.message ?? ''}`)
  check(Number(mysqlScalar(`select kb_id from sys_document where id = ${docId};`)) === beforeKbId,
    '被拒之后库归属没变（"返回错误"≠"什么都没发生"）')

  const beforeVecSrc = await milvusVectorCount([srcKbId])
  check(beforeVecSrc > 0, '移动前：源库分区里有向量（否则"搬走了"这件事没有起点）', `条数=${beforeVecSrc}`)

  const move = await api(`/doc/${docId}/attributes`, { method: 'PATCH', body: { kbId: tgtKbId } })
  check(move.json?.code === 0 && move.json?.data?.needReindex === true,
    '🔴 移动知识库返回 `needReindex=true`（`G7-4` 的字面要求）',
    `code=${move.json?.code} needReindex=${move.json?.data?.needReindex}`)
  check(Number(mysqlScalar(`select kb_id from sys_document where id = ${docId};`)) === tgtKbId,
    '🔴 `sys_document.kb_id` 真的改了')
  check(Number(mysqlScalar(`select count(*) from sys_chunk where doc_id = ${docId} and kb_id = ${tgtKbId};`)) > 0,
    '🔴 分片也跟着换库（只改文档列会让分片继续按**旧库的密级**参与过滤）')

  // 向量那一半：旧库归零、新库非零。⚠️ 用 `entities/query` 的 `count(*)` 数，
  // **不用 `get_stats` 的 `rowCount`**（后者只统计已 flush 的，会给出"没写进去"的假失败）
  // 且删除有**秒级可见性延迟** ⇒ `milvusVectorCount` 自带轮询
  const afterVecSrc = await milvusVectorCount([srcKbId])
  const afterVecTgt = await milvusVectorCount([tgtKbId])
  check(afterVecSrc === 0, '🔴 旧库分区里这篇文档的向量已删（不删 ⇒ 旧库会召回一篇不属于它的文档）',
    `源库向量=${afterVecSrc}`)
  check(afterVecTgt > 0, '🔴 新库当前代分区里写进了向量（移动 = 真的搬运，不只是改一列）',
    `目标库向量=${afterVecTgt}（源库移动前 ${beforeVecSrc}）`)

  // ⚠️ 必须带上 `result='SUCCESS'`：`@AuditLog` **失败也留痕**（那正是它的价值），
  //    所以只数条数的话，上面那次**被拒**的移动也会让这一条通过 —— 实测第一版就是这样
  check(mysqlCount(`select count(*) from sys_audit_log where action='移动知识库' and target like '%文档#${docId}%' and result='SUCCESS';`) >= 1,
    '审计留痕：文档 / 移动知识库（`G7-4` 明文要求，也是需求 05 §6 的动作表）')
  check(mysqlCount(`select count(*) from sys_audit_log where action='移动知识库' and target like '%文档#${docId}%' and result='FAILED';`) >= 1,
    '⚠️ 那次**被拒**的移动也留了痕（`result=FAILED`）—— 审计要记"发生过什么"，包括失败')

  // ---------- ⑨ 移动**之后它仍然可被检索**（一次真提问 + 打点对账）----------
  // 🔴 **为什么不是"旧库搜不到、新库搜得到"**：`ChatRequest` **没有 `kbIds`**
  //    （`S8-M10-E` 起就登记着这条），提问**每次覆盖全部可见库** ⇒ 无法把一次检索
  //    限定到单个库。硬要写就会写成一条恒绿的判据。
  //    ⇒ 拆成两件**都观测得到**的事：
  //      ① 向量落在哪个库的分区（上面 ⑧ 已直接观测：旧库 0 条、新库 > 0 条）——
  //         而那正是"检索侧换库"所依赖的**物理事实**（检索按 `kb_id` 标量过滤）；
  //      ② 移动之后它**还能被检索到**（若向量搬丢了，这一条会红）。
  const traceFloor = mysqlCount('select coalesce(max(id), 0) from sys_retrieval_trace;')
  await api('/chat/completions', { method: 'POST', body: { message: MAGIC, useVision: false } })
  await sleep(1200)
  const recalledAfterMove = mysqlCount(
    'select count(*) from sys_retrieval_trace_chunk c join sys_retrieval_trace t on t.trace_id = c.trace_id '
    + `where t.id > ${traceFloor} and c.doc_id = ${docId};`)
  check(recalledAfterMove > 0,
    '🔴 移动之后这篇文档**仍然可被检索**（向量搬丢了的话这里会是 0 —— "搬走"与"搬丢"不是一回事）',
    `打点里出现 ${recalledAfterMove} 条该文档的分片`)
  observe('源库移动前的代', `${srcGenBefore} · 目标库当前代=${mysqlScalar(`select index_generation from sys_kb where id = ${tgtKbId};`)}`)

  // ---------- ⑪ 无未捕获异常 ----------
  check(consoleMsgs.length === 0, '页面控制台无未捕获异常', consoleMsgs.slice(0, 2).join(' | '))
}

// ---------------------------------------------------------------- 辅助
async function createKb(code, label, secretLevel) {
  const created = bizOk(await api('/kb', {
    method: 'POST',
    body: {
      name: code,
      code,
      deptId: 1,
      secretLevel,
      visibleRoles: ['USER'],
      businessDomain: '技术文档',
      description: `S7-M06-B 属性修改页面级联调探针（${label}），脚本结束即删`,
    },
  }), `建库 ${code}`)
  return created.id
}

/** 等某库的文档到 `READY`（本仓口径：READY 是唯一"可被检索"的状态）。 */
async function waitDocReady(kbId, expected, timeout = 180000) {
  const deadline = Date.now() + timeout
  let last = ''
  while (Date.now() < deadline) {
    const list = bizOk(await api(`/kb/${kbId}/docs?page=1&pageSize=50`), '文档列表').list ?? []
    last = list.map((d) => d.status).join(',')
    if (list.filter((d) => d.status === 'READY').length >= expected) return
    if (list.some((d) => d.status === 'FAILED')) throw new Error(`解析失败：${last}`)
    await sleep(1500)
  }
  throw new Error(`等待 READY 超时（${timeout}ms）：最后一次状态 = ${last}`)
}

async function run() {
  await main()
}

run()
  .catch((e) => {
    check(false, '脚本执行中断', e.message)
  })
  .finally(async () => {
    // 收尾必须走 finally：**中途任何一步抛错都要清干净**（`#186` 那条教训）
    try {
      if (ws) {
        try {
          await send('Browser.close')
        } catch {
          /* 已经关了 */
        }
      }
    } catch {
      /* 忽略 */
    }
    try {
      edge?.kill()
    } catch {
      /* 忽略 */
    }
    try {
      rmSync(PROFILE, { recursive: true, force: true })
    } catch {
      /* 忽略 */
    }

    let clean = false
    try {
      const residues = []
      const descs = []
      for (const code of [SRC_CODE, TGT_CODE, LOW_CODE]) {
        const r = await teardownKbProbe(code)
        // ⚠️ 判据要喂**残留对象**、说明要喂**描述字符串** —— 第一版把描述字符串喂给了
        //    `kbResidueClean`，于是它恒为 false：明明报告里每一个数都是 0，
        //    结论却是"残留不为 0"（**判据自己坏了，而不是清理坏了**）
        const residue = r.residue ?? r
        residues.push(residue)
        descs.push(describeKbResidue(residue))
      }
      clean = residues.every((r) => kbResidueClean(r))
      check(clean, '收尾：探针库族残留 = 0（断言，不是日志）', descs.join(' / '))
    } catch (e) {
      check(false, '收尾：残留自检自身抛错', e.message)
    }

    const failed = steps.filter((s) => !s.ok)
    console.log(`\n${failed.length === 0 ? '✓' : '✗'} ${steps.length - failed.length}/${steps.length} 通过`)
    console.log(`探针库 id：src=${srcKbId} tgt=${tgtKbId} low=${lowKbId} · 文档 id=${docId}`)
    console.log(`库 id 复核（按 code 查）：${[SRC_CODE, TGT_CODE, LOW_CODE].map((c) => `${c}=${kbIdsOf(c)}`).join(' ')}`)
    if (failed.length > 0) {
      console.log('\n未通过：')
      failed.forEach((s) => console.log(`  ✗ ${s.label}${s.detail ? ` · ${s.detail}` : ''}`))
      process.exit(1)
    }
    if (!clean) process.exit(2)
    process.exit(0)
  })
