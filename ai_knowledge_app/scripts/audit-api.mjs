#!/usr/bin/env node
/**
 * 接口对齐核对：需求约定 ↔ mock 实现 ↔ 前端调用
 *
 * 用法： node scripts/audit-api.mjs
 *
 * 四方比对（以《10-接口约定汇总》为契约基准）：
 *   ① 前端调用了、但 mock 未实现  → 会导致页面运行时报错，必须补
 *   ② mock 实现了、但文档未列      → 前端扩展，必须回填文档
 *   ③ 文档有、但 mock 未实现       → 未开发 or 已废弃，需逐项定性
 *   ④ 文档有、但前端未调用         → 后端仍需实现（契约），但前端尚未接入
 *
 * 第五方（T-3C1 新增第四维，解析后端 controller 的 @*Mapping）：
 *   ⑦ mock 有、但真后端未实现      → 按归属 Sprint 分组，直接回答「该 Sprint 开工前端会撞到哪些 404」
 *   ⑧ 真后端有、但 mock 未实现      → 后端超集 / 前端未 mock
 *
 * 解析注意事项（曾踩坑，勿回退）：
 *   - 文档表格里 \| 是转义竖线，必须先保护再 split，否则单元格错位
 *   - 路径列可能是 `METHOD /path` 内联形式，不能被 startsWith('/') 过滤掉
 *   - api/index.ts 有嵌套泛型（PageResult<Record<string,unknown>>），
 *     不能用 <[^>]*> 匹配泛型，要用 [^(]* 直到左括号
 *   - §13「接口状态标注」表用于给差异项定性；解析 reqRoutes 时必须先截断掉 §13，
 *     否则 §13 里的路径会被当成契约接口重新加入
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const REPO = resolve(ROOT, '..')

const apiSrc = readFileSync(resolve(ROOT, 'src/api/index.ts'), 'utf8')
const reqSrc = readFileSync(resolve(ROOT, 'src/api/request.ts'), 'utf8')
const mockSrc = readFileSync(resolve(ROOT, 'src/mock/index.ts'), 'utf8')
const reqDoc = readFileSync(resolve(REPO, 'docs/需求文档/10-接口约定汇总.md'), 'utf8')

/** 非前端接口：由定时任务 / 服务端内部调用，前端不应出现在 apiCalls 中 */
const NON_FRONTEND = new Set(['POST /recycle/cleanup'])

/** 归一化：${id}、{id}、:id 统一为 {p} */
function norm(p) {
  return p
    .replace(/\$\{[^}]+\}/g, '{p}')
    .replace(/\{[^}]+\}/g, '{p}')
    .replace(/:[A-Za-z_][\w]*/g, '{p}')
    .replace(/\/+$/, '')
}

/** ---- 1) 前端调用：api/index.ts（http.xxx）+ request.ts（${BASE}/xxx） ---- */
const apiCalls = new Set()
// 泛型里可能含括号（如 (KbDocRow & {...})[]）或很长（config 的响应泛型跨 15 行），
// 因此不能用 [^(]* 截断，也不能把窗口设得太小；用惰性匹配 + 「( 紧跟引号」定位，
// 并只接受以 / 开头的路径（防止误吞泛型里的字符串字面量）。
//
// ⚠️ `upload` 也在名单里（S4-M05-A 新增的 `http.upload`，走 XHR 真进度）。
// 它**同样是一个 POST**（`request.ts` 里 `xhr.open('POST', ...)`），漏掉它会让
// `POST /kb/{p}/doc/upload` 从"前端调用"里消失，进而在 ⑥ 段被报成
// 「需求有、mock/前端没有」的差异项 —— 那是个由本脚本自身的白名单造成的假差异。
const VERB_TO_METHOD = { upload: 'POST' }
for (const m of apiSrc.matchAll(/http\.(get|post|put|patch|delete|upload)\b[\s\S]{0,2000}?\(\s*[`'"]([^`'"]+)/g)) {
  if (!m[2].startsWith('/')) continue
  apiCalls.add(`${(VERB_TO_METHOD[m[1]] ?? m[1]).toUpperCase()} ${norm(m[2])}`)
}
// chatStream 等直接用 fetch(`${BASE}/path`)，method 需从上下文推断（默认 POST）
for (const m of reqSrc.matchAll(/\$\{BASE\}(\/[^`'"]*)/g)) {
  const p = norm(m[1])
  if (!p) continue
  const around = reqSrc.slice(Math.max(0, m.index - 200), m.index)
  const method = /method:\s*'([A-Z]+)'/.exec(around)?.[1] ?? 'POST'
  apiCalls.add(`${method} ${p}`)
}

/** ---- 2) mock 实现：register('METHOD', '/api/...') ---- */
const mockRoutes = new Set()
for (const m of mockSrc.matchAll(/register\('([A-Z]+)',\s*'([^']+)'/g)) {
  mockRoutes.add(`${m[1]} ${norm(m[2].replace(/^\/api/, ''))}`)
}

/** ---- 3) 需求约定：10-接口约定汇总.md 表格（§13 状态表单独解析，先截断） ---- */
const [docMain, docStatusRaw = ''] = reqDoc.split(/^##\s*13\./m)
const reqRoutes = new Set()
const deprecatedInDoc = new Set()
for (const rawLine of docMain.split('\n')) {
  const line = rawLine.replace(/\\\|/g, '\u0000')
  if (!line.trim().startsWith('|')) continue
  const cells = line.split('|').map((c) => c.trim().replace(/\u0000/g, '|'))
  if (cells.length < 4) continue
  const methodCell = cells[1].replace(/\*/g, '')
  const pathCell = cells[2]
  const descCell = cells.slice(3).join(' ')
  // 已废弃 / 删除线标注 → 不计入契约，但必须出现在 §13 定性表
  const isDeprecated = /~~|已废弃/.test(methodCell + pathCell + descCell)
  const methods = methodCell.match(/\b(GET|POST|PUT|PATCH|DELETE)\b/g)
  if (!methods) continue
  const paths = [...pathCell.matchAll(/`([^`]+)`/g)]
    .map((m) => m[1])
    .filter((p) => p.startsWith('/') || /^(GET|POST|PUT|PATCH|DELETE)\s+\//.test(p))
  if (!paths.length) continue
  for (const p of paths) {
    let method = methods[0]
    const inline = p.match(/^(GET|POST|PUT|PATCH|DELETE)\s+(\/.*)$/)
    let realPath = p
    if (inline) {
      method = inline[1]
      realPath = inline[2]
    } else {
      const m2 = methodCell.match(/\b(GET|POST|PUT|PATCH|DELETE)\b/g) ?? []
      method = m2[Math.min(paths.indexOf(p), m2.length - 1)] ?? method
    }
    realPath = realPath.replace(/（.*$/, '').split('?')[0]
    if (!realPath.startsWith('/')) continue
    const key = `${method} ${norm(realPath)}`
    if (isDeprecated) deprecatedInDoc.add(key)
    else reqRoutes.add(key)
  }
}

/** ---- 4) §13 状态标注：`METHOD /path` → 状态（逐项定性） ---- */
const disposition = new Map()
for (const rawLine of docStatusRaw.split('\n')) {
  const line = rawLine.replace(/\\\|/g, '\u0000')
  if (!line.trim().startsWith('|')) continue
  const cells = line.split('|').map((c) => c.trim().replace(/\u0000/g, '|'))
  if (cells.length < 4) continue
  const status = cells[2] ?? ''
  if (!/已接入|已实现|待开发|前端改用|已废弃/.test(status)) continue
  for (const c of [...cells[1].matchAll(/`([^`]+)`/g)].map((m) => m[1])) {
    const m = c.match(/^(GET|POST|PUT|PATCH|DELETE)\s+(\/.*)$/)
    if (m) disposition.set(`${m[1]} ${norm(m[2])}`, status.replace(/\s*\|\s*$/, ''))
  }
}

/** ---- 5) 真后端实现：后端 controller 的 @*Mapping（T-3C1 新增第四维） ---- */
const backendRoutes = new Set()
const ctrlDir = resolve(REPO, 'ai_knowledge_web/src/main/java/com/knowledge/ai/controller')
if (existsSync(ctrlDir)) {
  for (const f of readdirSync(ctrlDir)) {
    if (!f.endsWith('Controller.java')) continue
    const src = readFileSync(resolve(ctrlDir, f), 'utf8')
    // 类级前缀：@RequestMapping("/api/xxx")
    const base = /@RequestMapping\(\s*(?:value\s*=\s*)?["']([^"']+)["']/.exec(src)?.[1] ?? ''
    // 方法级：@GetMapping / @GetMapping("/x") / @PostMapping(value = "/x", consumes = ...)
    for (const m of src.matchAll(/@(Get|Post|Put|Patch|Delete)Mapping\s*(?:\(\s*(?:value\s*=\s*)?["']([^"']*)["'])?/g)) {
      const method = m[1].toUpperCase()
      const sub = m[2] ?? ''
      const full = (base + sub).replace(/^\/api(?=\/|$)/, '')
      if (!full.startsWith('/')) continue
      backendRoutes.add(`${method} ${norm(full)}`)
    }
  }
}

/**
 * 归属 Sprint：路径前缀 → Sprint（用于把「mock 有、后端未实现」按 Sprint 分组）。
 * 按前缀长度降序匹配，长的优先（`/kb/grants` 先于 `/kb`，`/kb/{p}/grants` 也要单列）。
 */
const SPRINT_BY_PREFIX = [
  ['/auth', 'S1'], ['/user', 'S1'],
  ['/depts', 'S2'], ['/users', 'S2'], ['/roles', 'S2'], ['/permissions', 'S2'],
  ['/kb/grants', 'S2'], ['/kb/{p}/grants', 'S2'],
  // 文档管线 M05（S4）—— 路径落在 /kb/{p}/doc* 与 /doc/tasks 下，必须先于 /kb、/doc 匹配
  ['/kb/{p}/doc', 'S4'], ['/kb/{p}/docs', 'S4'], ['/kb/{p}/parse-issues', 'S4'], ['/doc/tasks', 'S4'],
  // 知识库 M04（S3）
  ['/kb', 'S3'], ['/audit', 'S3'],
  // 检索 M07（S5）
  ['/system/thresholds', 'S5'],
  // 文档域 M06（S7）
  ['/doc', 'S7'],
  ['/chat', 'S5'],
  ['/notices', 'S6'], ['/system/model', 'S6'],
  ['/recycle', 'S7'],
  ['/ops', 'S8'],
  ['/health', 'S0'],
].sort((a, b) => b[0].length - a[0].length)

function sprintOf(route) {
  const path = route.split(' ')[1] ?? ''
  for (const [prefix, sprint] of SPRINT_BY_PREFIX) {
    if (path === prefix || path.startsWith(prefix + '/')) return sprint
  }
  return '未归属'
}

/** ---- 报告 ---- */
const diff = (a, b) => [...a].filter((x) => !b.has(x)).sort()
const fmt = (list) => {
  if (!list.length) return '  — 无'
  return list.map((x) => `  ${x}${NON_FRONTEND.has(x) ? '  （定时任务，前端不调用）' : ''}`).join('\n')
}

const apiNotInMock = diff(apiCalls, mockRoutes)
const mockNotInReq = diff(mockRoutes, reqRoutes)
const reqNotInMock = diff(reqRoutes, mockRoutes)
const reqNotInApi = diff(reqRoutes, apiCalls)

// ③/④ 差异项：须在 §13 定性
const allDiff = [...new Set([...reqNotInMock, ...reqNotInApi])].filter((x) => !NON_FRONTEND.has(x))
const disposed = allDiff.filter((x) => disposition.has(x))
const undecided = allDiff.filter((x) => !disposition.has(x))
// 文档里标注为废弃的：须在 §13 定为「已废弃」
const deprecatedUndisposed = [...deprecatedInDoc].filter((x) => !disposition.has(x))

const fmtDisposed = (list) =>
  list.length
    ? list.map((x) => `  ${x}  →  ${disposition.get(x)}`).join('\n')
    : '  — 无'

console.log('接口对齐核对：需求约定 ↔ mock 实现 ↔ 前端调用')
console.log(`统计：前端调用 ${apiCalls.size} 条 · mock 实现 ${mockRoutes.size} 条 · 需求约定 ${reqRoutes.size} 条 · **真后端实现 ${backendRoutes.size} 条**`)
console.log(`文档已标注废弃 ${deprecatedInDoc.size} 条 · §13 已定性 ${disposition.size} 条`)

console.log(`\n### ① 前端调用了、但 mock 未实现（会导致运行时报错，必须补）（${apiNotInMock.length}）`)
console.log(fmt(apiNotInMock))
console.log(`\n### ② mock 实现了、但文档未列（前端扩展，必须回填文档）（${mockNotInReq.length}）`)
console.log(fmt(mockNotInReq))
console.log(`\n### ③ 文档有、但 mock 未实现（${reqNotInMock.length} · 其中已定性 ${reqNotInMock.filter((x) => disposition.has(x)).length}）`)
console.log(fmtDisposed(reqNotInMock.filter((x) => disposition.has(x))))
const reqNotInMockUndecided = reqNotInMock.filter((x) => !disposition.has(x) && !NON_FRONTEND.has(x))
if (reqNotInMockUndecided.length) console.log(`  ⚠️ 未定性：\n${fmt(reqNotInMockUndecided)}`)
console.log(`\n### ④ 文档有、但前端未调用（${reqNotInApi.length} · 其中已定性 ${reqNotInApi.filter((x) => disposition.has(x)).length}）`)
console.log(fmtDisposed(reqNotInApi.filter((x) => disposition.has(x))))
const reqNotInApiUndecided = reqNotInApi.filter((x) => !disposition.has(x) && !NON_FRONTEND.has(x))
if (reqNotInApiUndecided.length) console.log(`  ⚠️ 未定性：\n${fmt(reqNotInApiUndecided)}`)

console.log(`\n### ⑤ 文档标注废弃、但未在 §13 定性（${deprecatedUndisposed.length}）`)
console.log(fmt(deprecatedUndisposed))
console.log(`\n### ⑥ 差异项未定性合计（必须为 0）（${undecided.length}）`)
console.log(fmt(undecided))

// ---- ⑦/⑧ 真后端对账（T-3C1 第四维）：mock 有、后端未实现 → 该 Sprint 开工前端会撞到的 404 ----
const mockNotInBackend = diff(mockRoutes, backendRoutes)
const backendNotInMock = diff(backendRoutes, mockRoutes)

console.log(`\n### ⑦ mock 有、真后端未实现（${mockNotInBackend.length}）—— 按归属 Sprint 分组：本 Sprint 开工会撞到这些 404`)
{
  const bySprint = new Map()
  for (const x of mockNotInBackend) {
    const s = sprintOf(x)
    if (!bySprint.has(s)) bySprint.set(s, [])
    bySprint.get(s).push(x)
  }
  const order = ['S1', 'S2', 'S3', 'S4/S7', 'S5', 'S6', 'S7', 'S8', '未归属']
  const entries = [...bySprint.entries()].sort((a, b) => order.indexOf(a[0]) - order.indexOf(b[0]))
  if (!entries.length) console.log('  — 无')
  for (const [s, list] of entries) {
    console.log(`  【${s}】${list.length} 条`)
    for (const x of list) console.log(`    ${x}`)
  }
}

console.log(`\n### ⑧ 真后端有、mock 未实现（后端超集 / 前端未 mock）（${backendNotInMock.length}）`)
console.log(fmt(backendNotInMock))

const ok = apiNotInMock.length === 0 && mockNotInReq.length === 0 && undecided.length === 0 && deprecatedUndisposed.length === 0
console.log(
  ok
    ? '\n✅ 通过：①=0（前端调用均有 mock）· ②=0（mock 均已回填文档）· ⑥=0（差异项均已定性）'
    : '\n❌ 未通过：需补齐 ①/② 缺口，或在 §13 中为差异项补状态标注。',
)
process.exitCode = ok ? 0 : 1
