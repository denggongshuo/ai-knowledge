#!/usr/bin/env node
/**
 * 布局高度链自检
 *
 * 为什么需要它：
 *   内容区外面套了标签栏（36px）+ keep-alive，页面用的都是 `height: 100%` 内部滚动。
 *   一旦高度链断裂（例如给 .main-view 忘了 min-height: 0），表现是「页面底部被裁掉
 *   几十像素」—— DOM 断言和截图都很难稳定发现，但量测几何尺寸一眼就能看出来。
 *
 * 做法：
 *   同源 iframe 加载真实页面 → 等渲染完成 → 在 iframe 内量测关键容器高度 →
 *   把结果写回外层 DOM → 用 Edge --dump-dom 抓出来 → 在本脚本里断言。
 *   （同源所以能读 contentDocument；--dump-dom 只序列化外层 DOM，所以结果要写回外层）
 *
 * 用法： node scripts/layout-check.mjs [--base=http://127.0.0.1:5173]
 * 前置： dev server 已在 `--base` 指定的地址运行（默认 5173，mock 模式）
 *
 * 🔴 **退出码**：`0` 全过 · `1` 有路由的高度链断了 · `2` **前置不满足**（没跑任何路由）。
 * 分开的理由见 `lib/dev-server.mjs` 的模块头注（`#226`）。
 */
import { spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync, existsSync, rmSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { probeDevServer, reportPrecondition } from './lib/dev-server.mjs'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'

/** `--base=http://host:port`，默认 mock 模式的 dev server（理由同 `render-check.mjs`）。 */
const arg = (name, fallback) =>
  (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://localhost:5173')
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-layoutcheck'
const OUT_DIR = 'F:/work/code/ai-knowledge/temp/layout-check'
const BOOTSTRAP = resolve(ROOT, 'public/_layoutcheck.html')

/** 覆盖三类布局：整屏 flex（聊天）/ 内部滚动长页 / 列表页 */
const ROUTES = ['/chat', '/system/model', '/system/role', '/kb']

/** 标签栏固定高度，与 TagsView.vue 的 .tags-view 保持一致 */
const TAGS_H = 36

if (!existsSync(EDGE)) {
  console.error(`找不到 Edge：${EDGE}`)
  process.exit(1)
}
mkdirSync(OUT_DIR, { recursive: true })
if (existsSync(PROFILE)) rmSync(PROFILE, { recursive: true, force: true })

const BOOTSTRAP_HTML = `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="UTF-8"><title>layout-check</title>
<style>html,body{margin:0;height:100%}iframe{width:100%;height:100%;border:0;display:block}</style>
</head><body>
<iframe id="f"></iframe><pre id="out">PENDING</pre>
<script>
try { localStorage.setItem('ak_token','mock_selftest_token'); localStorage.setItem('ak_remember','0'); } catch (e) {}
var to = new URLSearchParams(location.search).get('to') || '/chat';
var f = document.getElementById('f');
var out = document.getElementById('out');
function measure() {
  var res = [];
  try {
    var d = f.contentDocument;
    var main = d.querySelector('.app-main');
    var col = d.querySelector('.main-col');
    var view = d.querySelector('.main-view');
    var tags = d.querySelector('.tags-view');
    var page = view ? view.firstElementChild : null;
    res.push('mainH=' + (main ? Math.round(main.getBoundingClientRect().height) : -1));
    res.push('colH=' + (col ? Math.round(col.getBoundingClientRect().height) : -1));
    res.push('viewH=' + (view ? Math.round(view.getBoundingClientRect().height) : -1));
    res.push('tagsH=' + (tags ? Math.round(tags.getBoundingClientRect().height) : -1));
    res.push('pageH=' + (page ? Math.round(page.getBoundingClientRect().height) : -1));
    res.push('pageBottom=' + (page ? Math.round(page.getBoundingClientRect().bottom) : -1));
    res.push('mainBottom=' + (main ? Math.round(main.getBoundingClientRect().bottom) : -1));
    res.push('pageScrollH=' + (page ? page.scrollHeight : -1));
    res.push('canScroll=' + (page && page.scrollHeight > page.clientHeight ? 1 : 0));
    res.push('tagCount=' + d.querySelectorAll('.tv-tab').length);
  } catch (e) {
    res = ['ERR=' + e.message];
  }
  out.textContent = res.join('\\n');
}
f.addEventListener('load', function () { setTimeout(measure, 2500); });
f.src = '/#' + to;
<\/script></body></html>
`

function dump(url, outFile) {
  const res = spawnSync(
    EDGE,
    [
      '--headless=new',
      '--disable-gpu',
      '--no-sandbox',
      '--disable-extensions',
      `--user-data-dir=${PROFILE}`,
      '--virtual-time-budget=20000',
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
let failed = 0

// 🔴 **前置自检**（同 `render-check.mjs`）：前置不满足 → **exit 2**，一条路由都不跑。
// 理由：本脚本上一批报过 **4/4 全红**而根因未查明 —— 一个"量高度"的判据在
// 「页面根本没挂载」时会给出 `未渲染`，与「高度链真的断了」**长得一模一样**（`#226`）。
reportPrecondition(await probeDevServer(BASE), 'layout-check')

console.log(`${pad('ROUTE', 16)} ${pad('mainH', 7)} ${pad('viewH', 7)} ${pad('pageH', 7)} ${pad('溢出', 6)} STATUS`)
console.log('-'.repeat(70))

for (const route of ROUTES) {
  writeFileSync(BOOTSTRAP, BOOTSTRAP_HTML)
  const outFile = resolve(OUT_DIR, `${route.replace(/\//g, '_') || 'root'}.html`)
  const html = dump(`${BASE}/_layoutcheck.html?to=${route}`, outFile)

  const nums = {}
  for (const m of html.matchAll(/([A-Za-z]+)=(-?\d+)/g)) nums[m[1]] = Number(m[2])

  const { mainH, colH, viewH, tagsH, pageH, pageBottom, mainBottom } = nums
  const problems = []
  if (!Number.isFinite(mainH) || mainH <= 0) problems.push('未渲染')
  else {
    if (Math.abs((colH ?? -1) - mainH) > 1) problems.push(`main-col(${colH})≠app-main(${mainH})`)
    if (tagsH !== TAGS_H) problems.push(`标签栏高=${tagsH}≠${TAGS_H}`)
    if (Math.abs((viewH ?? -1) - (mainH - TAGS_H)) > 1) problems.push(`main-view(${viewH})≠${mainH}-36`)
    if (Math.abs((pageH ?? -1) - (viewH ?? -1)) > 1) problems.push(`页面高(${pageH})≠内容区(${viewH})`)
    // 页面底边不应越过内容区底边（越过即被裁剪）
    if (pageBottom > mainBottom + 1) problems.push(`底部溢出${pageBottom - mainBottom}px`)
  }

  const status = problems.length ? `FAIL(${problems.join('; ')})` : 'OK'
  if (problems.length) failed++
  const overflow = pageBottom > mainBottom ? pageBottom - mainBottom : 0
  console.log(`${pad(route, 16)} ${pad(mainH ?? '-', 7)} ${pad(viewH ?? '-', 7)} ${pad(pageH ?? '-', 7)} ${pad(overflow, 6)} ${status}`)
}

console.log('-'.repeat(70))
console.log(`合计 ${ROUTES.length} 条路由，通过 ${ROUTES.length - failed} 条，失败 ${failed} 条`)

if (existsSync(PROFILE)) rmSync(PROFILE, { recursive: true, force: true })
if (existsSync(BOOTSTRAP)) rmSync(BOOTSTRAP, { force: true })
process.exit(failed ? 1 : 0)
