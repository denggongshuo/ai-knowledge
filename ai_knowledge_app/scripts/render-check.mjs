#!/usr/bin/env node
/**
 * 全站渲染自检（无头 Edge，逐页访问并按「每页精确断言」校验渲染结果）
 *
 * 用法： node scripts/render-check.mjs [--base=http://127.0.0.1:5173]
 * 前置：
 *   1) dev server 已在 `--base` 指定的地址运行（**默认 5173，mock 模式**）
 *   2) 注入登录态用的 public/_selfcheck.html 由本脚本自动生成并在结束时清理
 *
 * 🔴 **退出码是有语义的（`#226`）**：
 *   - `0` 全部路由通过
 *   - `1` 有路由**渲染断言失败**（页面真的坏了）
 *   - `2` **开工前置不满足**（server 不可达 / 不是这个前端 / API 层探针失败）
 *     —— 这时**一条路由都不会跑**，因为跑出来的红没有解释价值。
 *
 * 说明：
 *   - 用 Node 直接驱动 msedge.exe，不经过 npm/.cmd 包装器，避免在受限沙箱里被 wsl.exe 拦截
 *   - 路由清单与断言在 **`lib/routes.mjs`**（与 `console-check.mjs` 共用的单一事实源）
 *   - 断言用「页面主体特征」（.page-title / .err-title / 认证卡 / 聊天页类名），
 *     而不是通用文案，否则会被侧边栏菜单文案误命中
 *   - 额外校验标签栏边界：布局壳内的页面必须有 .tags-view，壳外（认证页 / 错误页）
 *     必须没有 —— 后者是全屏页，混进标签栏说明 keep-alive 挂错了位置
 */
import { spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync, existsSync, rmSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { ROUTES, NO_SHELL } from './lib/routes.mjs'
import { probeDevServer, reportPrecondition } from './lib/dev-server.mjs'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'

/**
 * `--base=http://host:port`，默认 mock 模式的 dev server。
 *
 * ⚠️ 这里**曾经硬编码** `localhost:5173`。而 vite 在端口被占用时会**自动换到下一个端口**，
 * 于是"脚本打的是哪个 dev server"这件事**无从检查** —— 那正是 `#226` 里
 * "同一份代码一个 25/25、一个 7/25"最合理的解释。现在它可配、且会被前置自检说出来。
 */
const arg = (name, fallback) =>
  (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://localhost:5173')
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-selfcheck'
const OUT_DIR = 'F:/work/code/ai-knowledge/temp/render-check'

if (!existsSync(EDGE)) {
  console.error(`找不到 Edge：${EDGE}`)
  process.exit(1)
}
mkdirSync(OUT_DIR, { recursive: true })
// 开跑前清掉 profile：否则残留的登录态会让公开页（/login 等）被守卫重定向到 /chat
if (existsSync(PROFILE)) rmSync(PROFILE, { recursive: true, force: true })

/**
 * 注入页：临时写入 public/，跑完删除。
 * 不长期放进仓库，避免被 vite build 打进 dist 产物。
 */
const BOOTSTRAP = resolve(ROOT, 'public/_selfcheck.html')
const BOOTSTRAP_HTML = `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="UTF-8"><title>render-check bootstrap</title></head>
<body><script>
try { localStorage.setItem('ak_token','mock_selftest_token'); localStorage.setItem('ak_remember','0'); } catch (e) {}
var to = new URLSearchParams(location.search).get('to') || '/chat';
location.replace('/#' + to);
<\/script></body></html>
`
writeFileSync(BOOTSTRAP, BOOTSTRAP_HTML)

function dump(url, outFile) {
  const res = spawnSync(
    EDGE,
    [
      '--headless=new',
      '--disable-gpu',
      '--no-sandbox',
      '--disable-extensions',
      `--user-data-dir=${PROFILE}`,
      '--virtual-time-budget=15000',
      '--run-all-compositor-stages-before-draw',
      '--dump-dom',
      url,
    ],
    { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 },
  )
  const html = res.stdout ?? ''
  writeFileSync(outFile, html)
  return html
}

const pad = (s, n) => String(s).padEnd(n, ' ')
const rows = []
let failed = 0

// 🔴 **前置自检** —— 在跑任何路由之前，先说清"我打的是哪个 server、它跑在哪个模式"。
// 前置不满足 → **exit 2**（与"路由断言失败"的 exit 1 **分开**，让调用方一眼分得清
// 「环境没准备好」与「页面真的坏了」）。理由见 `lib/dev-server.mjs` 的模块头注。
reportPrecondition(await probeDevServer(BASE), 'render-check')

for (const r of ROUTES) {
  const url = r.public ? `${BASE}/#${r.path}` : `${BASE}/_selfcheck.html?to=${r.path}`
  const outFile = resolve(OUT_DIR, `${r.path.replace(/\//g, '_') || 'root'}.html`)
  const html = dump(url, outFile)

  const inShell = !NO_SHELL.has(r.path)
  const tagCount = (html.match(/class="tv-tab/g) ?? []).length

  let status = 'OK'
  if (!r.expect.test(html)) status = `FAIL(dom=${html.length}B)`
  // 受保护页不应被弹回登录页
  else if (!r.public && /class="auth-card"/.test(html)) status = 'REDIRECTED_TO_LOGIN'
  // 标签栏边界：壳内必须有且至少含 2 个常驻标签；壳外必须没有
  else if (inShell && tagCount < 2) status = `FAIL(标签栏缺常驻:${tagCount})`
  else if (!inShell && tagCount > 0) status = `FAIL(壳外混入标签栏:${tagCount})`

  if (status !== 'OK') failed++
  rows.push([r.path, r.label, inShell ? String(tagCount) : '—', status])
}

console.log(`${pad('ROUTE', 20)} ${pad('PAGE', 18)} ${pad('TAGS', 5)} STATUS`)
console.log('-'.repeat(76))
for (const [p, l, t, s] of rows) console.log(`${pad(p, 20)} ${pad(l, 18)} ${pad(t, 5)} ${s}`)
console.log('-'.repeat(76))
console.log(`合计 ${rows.length} 条路由，通过 ${rows.length - failed} 条，失败 ${failed} 条`)
console.log(`DOM dump 目录：${OUT_DIR}`)

if (existsSync(PROFILE)) rmSync(PROFILE, { recursive: true, force: true })
if (existsSync(BOOTSTRAP)) rmSync(BOOTSTRAP, { force: true })
process.exit(failed ? 1 : 0)
