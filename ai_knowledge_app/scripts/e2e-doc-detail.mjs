/**
 * `S7-M06-A` 页面级 E2E：**文档详情页 + 文档预览页**接真后端
 * （`T-600` / `T-602` / `T-603` / `T-604` / `T-605`）。
 *
 * <h3>覆盖的判据</h3>
 * <ul>
 *   <li><b>`G7-2`</b> 分片统计：四桶合计 == `total`，**且** `total + tableSubBlocks` ==
 *       全表行数（两条都要 —— 只有第一条时"把 total 改小"就能假绿）；</li>
 *   <li><b>`G7-1`</b> 父块完整内容 + **全部**子块（`children.length` 与 DB 直查逐条相等）；</li>
 *   <li>两个页面**逐块对账**：页面上显示的每个数与接口返回的逐位相等；</li>
 *   <li>🔴 **不存在的文档 id ⇒ 可行动空态，且无未捕获异常** —— 这一条正是
 *       `console-check` 真后端模式下 `/doc/1`、`/doc/1/preview` 会**转红**的地方
 *       （`lib/routes.mjs` 给它们标了 `mockData`）；</li>
 *   <li>四个"未采集"字段在页面上显示成「未采集」而**不是「0 次」**；</li>
 *   <li>属性保存按钮**置灰**（本版本 `T-601` 未交付 ⇒ 不许给假成功）。</li>
 * </ul>
 *
 * <h3>⚠️ 夹具用 SQL 造，不走真上传+解析</h3>
 * 本页要验的是**读侧与页面的对账**，不是解析链路。造一份"含表格 + 图片 + 摘要"的真 PDF
 * 需要图片链路（`T-512`）与索引增强（`T-507`）同时开，而这两条的产物各自由它们的批次
 * 覆盖（`e2e-doc-parse` 等）。用 SQL 造夹具换来的是**确定性**：分片类型与条数逐个可控，
 * 于是"四桶合计"这类等式才能手算。已登记为遗留。
 *
 * <h3>前置</h3>
 * <pre>
 *   后端 8080：mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8080
 *   前端 5174：VITE_USE_MOCK=false npm run dev -- --host 127.0.0.1 --port 5174
 *   运行：node scripts/e2e-doc-detail.mjs --base=http://127.0.0.1:5174
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
} from './lib/probe-teardown.mjs'
import { probeDevServer, reportPrecondition, EXIT_PRECONDITION } from './lib/dev-server.mjs'

const arg = (name, fallback) => (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://localhost:5174')
const API = arg('api', 'http://localhost:8080')

const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-e2e-docdetail'
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9348)

const PROBE = { account: 'admin', password: 'Admin@123' }

const stamp = Date.now()
const KB_CODE = `kb_e2edocd_${stamp}`
const DOC_TITLE = `探针文档-详情-${stamp}`
const TABLE_CAPTION = '表 2：接口错误码对照表'
const TABLE_ROWS = [
  '| 10021 | 无知识库访问权限 | 检查角色与授权 |',
  '| 10022 | 无该知识库访问权限 | 同上 |',
]

/** 探针文件（不上传，只用来占位 `file_name`/`md5`）。 */
const FILE_NAME = `${DOC_TITLE}.pdf`

const steps = []
function check(ok, label, detail = '') {
  steps.push({ ok: !!ok, label, detail })
  // ⚠️ **逐条打印**，不只打汇总：第一版只打 `结果：40/41 通过`，
  // 于是"哪一条红了"要靠猜（日志里连一条 ✗ 都没有）。没有失败清单的汇总，
  // 会让人以为"再跑一次可能就绿了"。
  console.log(`${ok ? '✓' : '✗'} ${label}${detail ? ` · ${detail}` : ''}`)
  return !!ok
}
function observe(label, detail = '') {
  steps.push({ ok: true, label, detail, observation: true })
  console.log(`… ${label}${detail ? ` · ${detail}` : ''}`)
}

// ---------------------------------------------------------------- 后端直连
let token = ''
async function api(path, { method = 'GET', body, as = token } = {}) {
  const res = await fetch(`${API}/api${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(as ? { Authorization: `Bearer ${as}` } : {}) },
    ...(body ? { body: JSON.stringify(body) } : {}),
  })
  const text = await res.text()
  let parsed
  try {
    parsed = JSON.parse(text)
  } catch {
    throw new Error(`响应不是 JSON（HTTP ${res.status}）：${text.slice(0, 200)}`)
  }
  return { status: res.status, ...parsed }
}
function bizOk(res, label) {
  check(res.code === 0, `${label} · 业务码 0`, `code=${res.code} ${res.message ?? ''}`)
  return res.data
}
/** `?` 里的字段一律走它，避免把 `undefined` 拼进 URL（那会变成字面量 "undefined"）。 */
const q = (s) => encodeURIComponent(s)

/** 严格 null 判断（§5「缺席 ≡ 0」：falsy 断言会在值错了时照样绿）。 */
function isNull(v) {
  return v === null || v === undefined
}

// ---------------------------------------------------------------- CDP
let ws
let msgId = 0
const pending = new Map()
const consoleMsgs = []
const docResponses = []
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
  await sleep(700)
}
async function clickReal(selector) {
  return clickRealIndex(selector, 0)
}

/**
 * 点**第 N 个**匹配元素的中心（真鼠标事件）。
 *
 * <p>⚠️ 不用 CSS 的 `:nth-of-type` 拼下标：它数的是"同标签的兄弟里的第几个"，
 * 而 `querySelectorAll('.chunk-item')` 的下标是"匹配选择器的第几个" —— 两者在
 * 中间夹着别的 `div` 时会错位，而现象是"点到了另一张卡片"，看着像业务错。
 */
async function clickRealIndex(selector, index, visibleOnly = true) {
  const box = await evaluate(`(() => {
      const all = [...document.querySelectorAll(${JSON.stringify(selector)})];
      const els = ${visibleOnly} ? all.filter((e) => e.offsetParent !== null) : all;
      const el = els[${index}];
      if (!el) return null;
      el.scrollIntoView({ block: 'center' });
      const r = el.getBoundingClientRect();
      return { x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2), w: Math.round(r.width) };
    })()`)
  if (!box || box.w === 0) throw new Error(`找不到可点的元素：${selector}[${index}]`)
  for (const type of ['mousePressed', 'mouseReleased']) {
    await send('Input.dispatchMouseEvent', { type, x: box.x, y: box.y, button: 'left', clickCount: 1 })
    await sleep(60)
  }
  return box
}

/**
 * 数**当前可见**的匹配元素。
 *
 * 🔴 本仓页面级脚本的一条硬要求：**Element Plus 的 `el-tab-pane` 会把所有 Tab 的 DOM
 * 都渲染出来**（未激活的那个只是 `display:none`，`offsetParent === null`）——
 * 于是 `querySelectorAll('.chunk-item')` 数到的是**两份**（本页两个 Tab 各有分片流）。
 * 实测第一次跑：卡片数 16 / 接口 8，而"点第 5 张卡片"点在了隐藏那一份上，
 * 关系框永远不出现 ⇒ 两条断言红，**而页面本身完全正常**。
 */
async function visibleCount(selector) {
  return evaluate(`[...document.querySelectorAll(${JSON.stringify(selector)})]
      .filter((e) => e.offsetParent !== null).length`)
}
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

// ---------------------------------------------------------------- 目标
let kbId = 0
let docId = 0
let tableParentId = 0
let childCount = 0
let topLevelCount = 0

/** 顶层块的 `raw_content` 与"没有标题路径"的条数（判据 8 的手算输入）。 */
const topLevel = []
let missingTitlePath = 0

async function main() {
  // ---------- ① 前置：dev server 必须是真后端模式 ----------
  const diag = await probeDevServer(BASE)
  if (!diag.ok) {
    console.error(reportPrecondition(diag, 'e2e-doc-detail'))
    process.exit(EXIT_PRECONDITION)
  }
  // ⚠️ 本脚本与 `requireMockMode` 那批**正好相反**：它要的是**真后端模式**。
  // 模式不对时一条都别跑 —— 在 mock 模式下本脚本的"四桶合计/全部子块"仍会对上
  // （mock 数据自洽），于是**看似通过、实际什么都没验**。
  if (diag.mode !== 'real') {
    console.error(`\n✗ e2e-doc-detail：**前置不满足**（exit ${EXIT_PRECONDITION}）—— 它只在**真后端模式**下成立\n`)
    console.error('  • 本脚本对账的是"页面显示的数 == 真接口返回的数 == 库里的行"。')
    console.error('    在 mock 模式下三者同源于一份夹具 ⇒ 恒绿，等于没跑。')
    console.error('  • 下一步：用 `VITE_USE_MOCK=false npm run dev -- --host 127.0.0.1 --port 5174`')
    console.error('    起 dev server，并确认后端 8080 在跑。\n')
    process.exit(EXIT_PRECONDITION)
  }
  observe('dev server 就绪（真后端模式）', diag.notes?.join(' / ') ?? '')

  // ---------- ② 登录 ----------
  const login = bizOk(await api('/auth/login', { method: 'POST', body: { ...PROBE, remember: true }, as: '' }), '登录')
  token = login.accessToken
  check(!!token, '拿到 accessToken')

  // 🔴 **带 token 进页面**：前端从 `localStorage` 读 `ak_token`，而本脚本是**直连后端**
  // 登的录 —— 不注入的话路由守卫会把页面赶回 `/#/login`，而现象是
  // 「详情页一直挂不上、`.stat-card` 等不到」（看着像接口坏了）。
  //
  // 🔴 **注入之后必须整页 reload**：`useAuthStore` 的 token 是**创建时读一次**
  // `localStorage`（`stores/auth.ts:25`），而 `isLoggedIn` 是那个内存值。
  // 标签页里只要**已经挂过任何一页**，store 就已经用空 token 建好了 ——
  // 此后 `localStorage.setItem` 对它毫无影响，`router.beforeEach` 照样把人赶回登录页。
  // ⚠️ 不 reload 也**可能**跑通 —— 那取决于"注入时 app 还没挂载"这个时序，
  // 而靠时序跑通的用例会在某次慢启动之后毫无征兆地红。
  await goto(`${BASE}/#/login`)
  await evaluate(`localStorage.setItem('ak_token', ${JSON.stringify(token)});
      localStorage.setItem('ak_remember', '1'); 'ok'`)
  await send('Page.reload')
  // ⚠️ 必须 `waitFor` 而不是"睡一下再看一眼"：整页冷启动（vite dev 首次编译）
  // 常常超过 1s，而**守卫还没跑**时 hash 仍是 `#/login` ⇒ 那条断言会因为竞态红，
  // 而现象与"token 没生效"一模一样（第一版就是这么红的，于是差点去改对的东西）。
  await waitFor(`!location.hash.includes('/login')`, { label: '注入 token 后离开登录页', timeout: 15000 })
  check(true, '注入 token 后离开登录页（store 读到的是新 token）', await evaluate('location.hash'))

  // ---------- ③ SQL 造夹具：探针库 + 一篇含全部块类型的文档 ----------
  buildFixtures()
  check(kbId > 0 && docId > 0, '准备：探针库与文档已建', `kbId=${kbId} docId=${docId}`)

  // ---------- ④ 五条端点直连对账 ----------
  const stat = bizOk(await api(`/doc/${docId}/chunk-stat`), 'chunk-stat')
  const chunks = bizOk(await api(`/doc/${docId}/chunks`), 'chunks')

  // `G7-2` 两条等式
  check(stat.text + stat.table + stat.summary + (stat.image ?? 0) === stat.total,
    'G7-2 ①：四桶合计 == total',
    `${stat.text}+${stat.table}+${stat.summary}+${stat.image ?? 0} vs ${stat.total}`)
  const allRows = mysqlCount(`select count(*) from sys_chunk where doc_id=${docId};`)
  check(stat.total + stat.tableSubBlocks === allRows,
    'G7-2 ②：total + 子块 == 全表行数（没有一行掉在地上）',
    `${stat.total}+${stat.tableSubBlocks} vs ${allRows}`)
  check(stat.total === topLevelCount && stat.total === Number(mysqlScalar(`select chunk_count from sys_document where id=${docId};`)),
    'G7-2 ③：total 与 chunk_count 同源（顶层块）', `${stat.total} / ${topLevelCount}`)
  check(chunks.list.length === stat.total, '分片列表（不带 type）条数 == total', `${chunks.list.length} / ${stat.total}`)

  // 五档互斥完备
  const buckets = {}
  const union = new Set()
  for (const type of ['text', 'table', 'image', 'summary']) {
    const part = bizOk(await api(`/doc/${docId}/chunks?type=${type}`), `chunks?type=${type}`)
    buckets[type] = part.list.length
    part.list.forEach((c) => union.add(c.id))
    check(part.list.every((c) => groupOf(c.blockType) === type),
      `G7-2「${type}」档里只有该组的块`, `条数=${part.list.length}`)
  }
  check(union.size === chunks.list.length, '四档并集恰好覆盖全量且无重复', `${union.size} / ${chunks.list.length}`)
  const badType = await api(`/doc/${docId}/chunks?type=bogus`)
  check(badType.code === 10030, 'type 闭集之外的取值 → 10030（不静默当成 all）', `code=${badType.code}`)

  // `G7-1` 父块 + 全部子块
  const rel = bizOk(await api(`/doc/${docId}/chunks/${tableParentId}`), 'chunks/{parentId}')
  const dbChildren = mysqlCount(`select count(*) from sys_chunk where parent_id=${tableParentId};`)
  check(rel.children.length === rel.total && rel.total === dbChildren,
    'G7-1：返回**全部**子块（与 DB 直查逐条相等）', `接口 ${rel.children.length} · DB ${dbChildren}`)
  check(rel.parent.rows === TABLE_ROWS.length && rel.parent.cols === 3,
    'G7-1：父块行×列（数据行不含表头）', `${rel.parent.rows} × ${rel.parent.cols}`)
  check(rel.parent.returnedRows === rel.parent.rows, '本页返回整表（returnedRows == rows）',
    `${rel.parent.returnedRows} / ${rel.parent.rows}`)
  check(rel.parent.title === TABLE_CAPTION, '父块 title = 表标题（markdown 的 **…** 前导行）', rel.parent.title)
  check(!!rel.parent.titlePath, '父块 titlePath = 所属章节（与 title 分开两个字段）', rel.parent.titlePath)

  const zero = await api(`/doc/${docId}/chunks/0`)
  check(zero.code === 10030, 'parentId = 0 被拒（禁止 0 当哨兵）', `code=${zero.code} ${zero.message ?? ''}`)

  // 目录树
  const toc = bizOk(await api(`/doc/${docId}/toc`), 'toc')
  const headings = mysqlCount(`select count(*) from sys_chunk where doc_id=${docId} and chunk_type='HEADING';`)
  check(toc.list.length === headings, 'T-602：目录条数 == HEADING 块数', `${toc.list.length} / ${headings}`)
  check(toc.list[1]?.level === 2, 'T-602：二级标题的 level = title_path 分段数', JSON.stringify(toc.list.map((t) => t.level)))

  // 元信息 + 四个"未采集"
  const meta = bizOk(await api(`/doc/${docId}`), 'doc 元信息')
  check(meta.chunkCount === stat.total, 'T-600：meta.chunkCount == chunk-stat.total', `${meta.chunkCount} / ${stat.total}`)
  check(meta.fileDesc.includes('原生文本'), 'T-600：fileDesc 由 type + pages_with_text 派生', meta.fileDesc)
  // 🔴 严格 null：falsy 断言会在"值错了"时照样绿（`0`/`''`/`[]` 全是 falsy）
  check(isNull(meta.citations) && isNull(meta.downVotes) && isNull(meta.version) && isNull(meta.tags),
    'T-600：citations/downVotes/version/tags **严格 null**（未采集，不是 0）',
    `${meta.citations} / ${meta.downVotes} / ${meta.version} / ${meta.tags}`)

  // 索引任务
  const tasks = bizOk(await api(`/doc/${docId}/index-tasks`), 'index-tasks')
  check(tasks.list.length === 2, 'T-605：两条解析任务都在', `条数=${tasks.list.length}`)
  check(tasks.list[0].status === 'FAILED' && tasks.list[1].status === 'DONE', 'T-605：新→旧排序 + 状态**原样**下发（不改名）',
    tasks.list.map((t) => t.status).join(','))
  check(isNull(tasks.list[0].chunkCount) && isNull(tasks.list[1].version),
    'T-605：chunk_total=0 是"未知"（下发 null）· version 无来源',
    `${tasks.list[0].chunkCount} / ${tasks.list[1].version}`)
  check(String(tasks.list[0].detail ?? '').includes('没有文本层'),
    'T-605：失败任务带上**具体原因**（G4-3：不许只说"失败"两字）', tasks.list[0].detail)

  // ---------- ⑤ 页面：文档详情 ----------
  await goto(`${BASE}/#/doc/${docId}`)
  await sleep(600)
  // 诊断观察项：页面"挂上了但没数据"与"根本没挂上"是两件事，而它们的现象都是超时
  const snapshot = await evaluate(`(() => ({
      hash: location.hash,
      title: document.title,
      hasTitle: !!document.querySelector('.page-title'),
      hasTabs: !!document.querySelector('.el-tabs'),
      text: document.body.innerText.replace(/\\s+/g, ' ').slice(0, 160),
    }))()`)
  observe('详情页快照', `${snapshot.hash} · 标题=${snapshot.title} · .page-title=${snapshot.hasTitle} · .el-tabs=${snapshot.hasTabs} · ${snapshot.text}`)
  await waitFor(`document.querySelectorAll('.stat-card').length > 0`, { label: '详情页统计卡' })

  const detail = await evaluate(`(() => {
      const cards = {};
      document.querySelectorAll('.stat-card').forEach((c) => {
        cards[c.querySelector('.sc-label').textContent.trim()] = c.querySelector('.sc-val').textContent.trim();
      });
      const text = document.body.innerText;
      const saveBtn = [...document.querySelectorAll('button')].find((b) => b.textContent.includes('保存属性'));
      return {
        cards,
        hasUncollected: text.includes('未采集'),
        zeroCitation: /0 次问答引用/.test(text),
        saveDisabled: !!saveBtn && saveBtn.disabled,
        title: document.querySelector('.page-title')?.textContent?.trim() ?? '',
      };
    })()`)

  check(detail.title === DOC_TITLE, '详情页：标题来自真接口', detail.title)
  check(Number(detail.cards['分片总数']) === stat.total, '详情页：分片总数与接口逐位相等',
    `${detail.cards['分片总数']} / ${stat.total}`)
  check(Number(detail.cards['正文块']) === stat.text, '详情页：正文块数一致', detail.cards['正文块'])
  check(detail.cards['平均 tokens'] !== '', '详情页：平均 tokens 有值', detail.cards['平均 tokens'])
  check(detail.hasUncollected && !detail.zeroCitation,
    '🔴「被引用」显示「未采集」而**不是**「0 次问答引用」（判不了 ≠ 判否）',
    `未采集=${detail.hasUncollected} 0次=${detail.zeroCitation}`)
  check(detail.saveDisabled, '属性保存按钮**置灰**（T-601 未交付 ⇒ 不给假成功）', `disabled=${detail.saveDisabled}`)

  // 索引任务 Tab：状态点（本批补的第四档）
  await clickReal('.el-tabs__item:nth-child(4)')
  await sleep(500)
  const dots = await evaluate(`(() => {
      const ds = [...document.querySelectorAll('.dot')].map((d) => d.className);
      return {
        n: ds.length,
        done: ds.filter((c) => c.includes('done')).length,
        failed: ds.filter((c) => c.includes('failed')).length,
      };
    })()`)
  check(dots.done > 0 && dots.failed > 0,
    '详情页：状态点渲染出 `.dot.done` 与 `.dot.failed`（**档位补齐**的页面级证据 —— '
    + "原类型里那个 `INFO` 连 `.dot.info` 都没有，圆点是裸的）", JSON.stringify(dots))
  const noUncaught = consoleMsgs.filter((m) => m.type === 'error')
  check(noUncaught.length === 0, '详情页 console 无 error', noUncaught.map((m) => m.text.slice(0, 120)).join(' | ') || '—')

  // ---------- ⑥ 页面：文档预览 ----------
  await goto(`${BASE}/#/doc/${docId}/preview`)
  await waitFor(`[...document.querySelectorAll('.chunk-item')].some((e) => e.offsetParent !== null)`,
    { label: '预览页分片卡片' })

  const preview = await evaluate(`(() => {
      const chips = [...document.querySelectorAll('.chip')]
        .filter((e) => e.offsetParent !== null)
        .map((c) => c.textContent.replace(/\\s+/g, ' ').trim());
      return { chips, text: document.body.innerText };
    })()`)
  const previewCards = await visibleCount('.chunk-item')
  check(previewCards === stat.total, '预览页：分片卡片数 == 接口 total（**只数可见的**那一份）',
    `${previewCards} / ${stat.total}`)
  check(preview.chips.some((c) => c.startsWith(`图片 ${stat.image}`)),
    '预览页：「图片」chip 有四档里的图片数（mock 缺的那一档在真后端下必须有）',
    preview.chips.join(' | '))
  check(preview.chips.some((c) => c.startsWith(`表格 ${stat.table}`)) && preview.chips.some((c) => c.startsWith(`全部 ${stat.total}`)),
    '预览页：全部/表格 chip 的数与接口一致', preview.chips.slice(0, 3).join(' | '))

  // 点表格块 → 父子块关系框（`G7-1` 的页面级证据）
  const clicked = await evaluate(`(() => {
      const items = [...document.querySelectorAll('.chunk-item')].filter((e) => e.offsetParent !== null);
      const idx = items.findIndex((el) => el.className.includes('table-item'));
      if (idx < 0) return -1;
      items[idx].scrollIntoView({ block: 'center' });
      return idx;
    })()`)
  check(clicked >= 0, '预览页：分片流里有表格块卡片', `下标=${clicked}`)
  await clickRealIndex('.chunk-item', clicked)
  await waitFor(`[...document.querySelectorAll('.relation-box')].some((e) => e.offsetParent !== null)`,
    { label: '父子块关系框' })

  // ⚠️ 同样只认**可见**的那一份（两个 Tab 各有一个关系框）
  const relBox = await evaluate(`(() => {
      const box = [...document.querySelectorAll('.relation-box')].find((e) => e.offsetParent !== null);
      const text = box.innerText;
      return {
        text,
        childRows: box.querySelectorAll('.rel-content.child').length,
        hasWindowLine: /全部返回/.test(text),
      };
    })()`)
  check(relBox.childRows === childCount,
    '🔴 预览页关系框列出**全部**子块（不是只有一个）', `${relBox.childRows} / ${childCount}`)
  check(relBox.text.includes(`${TABLE_ROWS.length} 行 × 3 列`) && relBox.hasWindowLine,
    '预览页关系框：显示「N 行 × M 列全部返回」', relBox.text.slice(0, 80).replace(/\n/g, ' '))
  check(relBox.text.includes(TABLE_CAPTION), '预览页关系框：显示表标题', TABLE_CAPTION)

  // 切「图片」chip（真后端下这一档必须筛得出东西）
  await evaluate(`[...document.querySelectorAll('.chip')]
      .filter((e) => e.offsetParent !== null)
      .find((c) => c.textContent.includes('图片'))?.click()`)
  await sleep(400)
  const imageCards = await visibleCount('.chunk-item')
  check(imageCards === (stat.image ?? 0), '预览页：「图片」档筛出 image 桶的条数', `${imageCards} / ${stat.image}`)

  // 点目录项（真 id 定位，原先那个 'h3' 魔法值永不命中）
  const tocClicked = await evaluate(`(() => {
      const item = document.querySelector('.toc-item');
      if (!item) return false;
      item.scrollIntoView({ block: 'center' });
      return true;
    })()`)
  if (tocClicked) {
    await clickReal('.toc-item')
    await sleep(500)
    check(consoleMsgs.filter((m) => m.type === 'error').length === 0, '预览页：点目录项无 console error')
  } else {
    observe('预览页：左栏目录项（观察项）', '本探针文档的目录项未渲染，跳过点击')
  }

  // ---------- ⑦ 不存在的文档 id ⇒ 可行动空态（判据 18） ----------
  for (const [path, name] of [[`/#/doc/99999999`, '详情页'], [`/#/doc/99999999/preview`, '预览页']]) {
    const before = consoleMsgs.length
    await goto(`${BASE}${path}`)
    await sleep(900)
    const state = await evaluate(`(() => {
        const t = document.body.innerText;
        return { empty: !!document.querySelector('.el-empty') || t.includes('打不开'), blank: t.trim().length < 40 };
      })()`)
    const newErr = consoleMsgs.slice(before).filter((m) => m.type === 'error')
    check(state.empty && !state.blank, `${name}：不存在的文档 ⇒ 可行动空态（不是空白页）`,
      `empty=${state.empty} blank=${state.blank}`)
    check(newErr.length === 0, `${name}：不存在的文档 ⇒ **无未捕获异常**`,
      newErr.map((m) => m.text.slice(0, 140)).join(' | ') || '—')
  }

  // ---------- ⑧ 两个页面的请求确实打到真后端 ----------
  //
  // ⚠️ 允许的状态里**必须点名** `/versions` 的 404：那是 `T-606` 未交付的端点，
  // 页面走的是"按端点降级"（版本 Tab 给诚实说明）。第一版只允许 200/403，
  // 于是把这条**已知且有意**的 404 报成"怪状态" —— 一条把预期内的东西当异常的红，
  // 会让人去修没坏的地方。
  const docCalls = docResponses.filter((r) => r.url.includes('/doc/'))
  const knownMissing = (r) => r.status === 404 && r.url.includes('/versions')
  const unexpected = docCalls.filter((r) => r.status !== 200 && !knownMissing(r))
  const callLog = docCalls.slice(0, 8).map((r) => `${r.url}:${r.status}`).join(' ') || '（未抓到）'
  check(docCalls.some((r) => r.status === 200) && unexpected.length === 0,
    '页面确实向 /api/doc/* 发了请求；**除已知未交付的 `/versions`（404）外**全部 200',
    unexpected.length ? `${callLog} · 意外状态：${unexpected.map((r) => `${r.url}:${r.status}`).join(',')}` : callLog)
}

/** 块类型 → 展示分组（与后端 `DocDetailService.TEXT_GROUP` 同口径）。 */
function groupOf(blockType) {
  if (blockType === 'TABLE') return 'table'
  if (blockType === 'IMAGE') return 'image'
  if (blockType === 'SUMMARY') return 'summary'
  return 'text'
}

// ---------------------------------------------------------------- 夹具
function sql(s) {
  mysqlScalar(s)
}
function buildFixtures() {
  sql(`insert into sys_kb (code, name, dept_id, doc_count, chunk_count, status, secret_level,
        business_domain, reindex_required, index_generation, created_at, updated_at)
      values ('${KB_CODE}', '${KB_CODE}', 1, 1, 0, 'READY', 'INTERNAL', '', 0, 1, now(), now());`)
  kbId = Number(mysqlScalar(`select id from sys_kb where code='${KB_CODE}';`))
  // 可见性求交认的是**授权行**（`DataScopeService#kbVisibility`），不是超管身份
  sql(`insert into sys_kb_grant (kb_id, principal_type, principal_id, level, created_at, updated_at)
      values (${kbId}, 'USER', 1, 'MANAGE', now(), now());`)

  const md5 = String(stamp).padStart(32, 'a').slice(0, 32)
  sql(`insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5, storage_path,
        status, chunk_count, table_total, table_parsed, image_total, image_parsed, pages_total, pages_with_text,
        index_generation, created_at, updated_at)
      values (${kbId}, 1, '${DOC_TITLE}', '${FILE_NAME}', 'PDF', 2048, '${md5}', 'documents/probe',
        'READY', 0, 1, 1, 1, 1, 5, 4, 1, now(), now());`)
  docId = Number(mysqlScalar(`select id from sys_document where kb_id=${kbId} and title='${DOC_TITLE}';`))

  const add = (type, content, titlePath, topLevelFlag) => {
    const tp = titlePath === null ? 'null' : `'${titlePath}'`
    const parent = topLevelFlag ? 'null' : String(tableParentId)
    sql(`insert into sys_chunk (doc_id, kb_id, chunk_type, parent_id, title_path, raw_content,
          content_sha256, order_no, page_no, created_at)
        values (${docId}, ${kbId}, '${type}', ${parent}, ${tp}, '${content.replace(/'/g, "''")}',
          '${md5}', ${nextOrder()}, 1, now());`)
    const id = Number(mysqlScalar(`select id from sys_chunk where doc_id=${docId} order by order_no desc limit 1;`))
    if (topLevelFlag) {
      topLevel.push(content)
      if (titlePath === null) missingTitlePath++
    }
    return id
  }

  add('HEADING', '1. 概述', '1. 概述', true)
  add('PARAGRAPH', '这是一段正文。', '1. 概述', true)
  add('HEADING', '1.1 细节', '1. 概述 > 1.1 细节', true)
  add('PARAGRAPH', '封面段，标题之前。', null, true)
  tableParentId = add('TABLE', tableMarkdown(), '1. 概述', true)
  add('IMAGE', '图 1：整体链路', '1. 概述', true)
  add('SUMMARY', '本文档概述了接口错误码。', null, true)
  add('HYPOTHESIS_QA', '错误码 10021 是什么？', '1. 概述', true)
  for (const row of TABLE_ROWS) {
    add('TABLE', row, '1. 概述', false)
    childCount++
  }

  topLevelCount = topLevel.length
  sql(`update sys_document set chunk_count=${topLevelCount} where id=${docId};`)
  sql(`update sys_kb set chunk_count=${topLevel.length + childCount} where id=${kbId};`)

  // 两条解析任务：一条完成（74 秒）、一条失败（给具体原因）。
  // ⚠️ created_at 刻意不同：都用 now() 时先后不定，而"新→旧"是这条端点的真实口径。
  //
  // 🔴 **刻意不用 `RUNNING` 与 `PENDING`**（第一版用了 RUNNING，收尾当场报"不可信"）：
  //   · `RUNNING` —— 收尾的 `quiesceParseJobs` 会**等它停下来**（上限 60s），等不到就把
  //     "清理报告不可信"标红（`#187`：还在跑的 worker 会在删库之后继续写）。它判红是对的，
  //     错在**我给一个假文档造了"正在跑"的作业**；
  //   · `PENDING` —— 后端**真的会来领取它**（`type='doc-parse'` 是它的作业池），
  //     于是那一行会被真 worker 执行并改写，用例数据随运行环境漂移。
  // 一条 DONE + 一条 FAILED 既覆盖了状态点渲染，又不碰作业池。
  sql(`insert into sys_async_task (task_id, type, status, kb_id, doc_id, owner_id, stage, percent,
        chunk_done, chunk_total, error_msg, created_at, updated_at, locked_at, finished_at)
      values ('probe-docd-${stamp}-1', 'doc-parse', 'DONE', ${kbId}, ${docId}, 1, '解析中', 100, 8, 8,
        null, date_add(now(), interval -3600 second), now(),
        date_add(now(), interval -3500 second), date_add(now(), interval -3426 second));`)
  sql(`insert into sys_async_task (task_id, type, status, kb_id, doc_id, owner_id, stage, percent,
        chunk_done, chunk_total, error_msg, created_at, updated_at, locked_at, finished_at)
      values ('probe-docd-${stamp}-2', 'doc-parse', 'FAILED', ${kbId}, ${docId}, 1, '解析失败', 100, 0, 0,
        '该文件没有文本层（扫描件），请先做 OCR', date_add(now(), interval -60 second), now(),
        date_add(now(), interval -50 second), date_add(now(), interval -40 second));`)
}

function nextOrder() {
  return Number(mysqlScalar(`select coalesce(max(order_no), 0) + 1 from sys_chunk where doc_id=${docId};`))
}

/** 与 `ParsedTable#toMarkdown()` 逐字一致的形状：表标题 + 表头 + 分隔行 + 数据行。 */
function tableMarkdown() {
  return `**${TABLE_CAPTION}**\\n| 错误码 | 含义 | 处理建议 |\\n|---|---|---|\\n${TABLE_ROWS.join('\\n')}\\n`
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
  if (msg.method === 'Network.responseReceived') {
    const { url, status } = msg.params.response
    if (url.includes('/api/doc/')) docResponses.push({ url: url.replace(/^.*\/api/, ''), status })
  }
  if (msg.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(msg.params.type)) {
    const text = (msg.params.args ?? []).map((a) => a.value ?? a.description ?? '').join(' ')
    consoleMsgs.push({ type: msg.params.type, text })
  }
}

function startEdge() {
  return new Promise((res, rej) => {
    const child = spawn(EDGE, [
      '--headless=new', '--disable-gpu', '--no-sandbox', '--disable-extensions',
      '--no-first-run', '--no-default-browser-check',
      `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${PROFILE}`, 'about:blank',
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
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })
  // 清掉可能残留的 token（残留的 `ak_token` 会让守卫重定向到登录页）
  await send('Page.navigate', { url: `${BASE}/#/login` })
  await sleep(400)
  await send('Runtime.evaluate', { expression: 'localStorage.clear(); sessionStorage.clear();' })
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
    if (kbId > 0) {
      // ⚠️ 残留取 `teardownKbProbe` 的**返回值**，不自己手搓一份：
      // 第一版手搓了一个 `{docs, chunks, ...}` 的对象喂给 `describeKbResidue`，
      // 当场 `Cannot read properties of undefined (reading 'sys_kb')` —— 它要的是
      // `findKbProbeResidue` 的形状（含 Milvus 向量/分区、MinIO 对象、无主残留）。
      // **库自己就有一份，手搓等于把它的覆盖面砍掉一半还看不出来。**
      const { residue } = await teardownKbProbe(KB_CODE)
      const clean = kbResidueClean(residue)
      console.log(`${clean ? '✓' : '✗'} 收尾清理 · ${describeKbResidue(residue)}`)
      if (!clean) process.exitCode = 1
    }
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
  process.exit(process.exitCode ?? 0)
}

