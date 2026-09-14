#!/usr/bin/env node
/**
 * 计划文档里的 mermaid 代码块 → 静态 SVG
 *
 * 用法：
 *   node scripts/gen-diagram.mjs                      # 处理 plan/ 下全部 .md
 *   node scripts/gen-diagram.mjs ../ai_knowledge_web/plan/2026-09-14-S3-M03-A.md
 *   node scripts/gen-diagram.mjs <file.md> --check    # 只校验，不写盘（CI/结项前用）
 *
 * 为什么这么做（而不是让读者装 mermaid 插件）：
 *   plan 文档要能直接看到图。VS Code 默认不渲染 mermaid，本机也没装扩展；
 *   落成静态 SVG 后 git 里是文本、浏览器/编辑器都能看，且不依赖网络。
 *
 * 产物：与 md 同名的 `<md名>/` 目录，内含 `<序号>-<slug>.svg`。
 *   md 里的 ```mermaid 围栏**保持原样**（源码是事实源，可 diff）；
 *   围栏紧跟一行 `<!-- diagram: <相对路径> -->` 指向已渲染的 SVG。
 *
 * 依赖：无。msedge.exe 直驱（同 render-check.mjs 的路子），mermaid 用本地 vendor 副本
 *   （scripts/vendor/mermaid.min.js，不入库，见 .gitignore）。
 */
import { spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync, readFileSync, existsSync, readdirSync, statSync, rmSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve, basename, join } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const REPO = resolve(ROOT, '..')
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const VENDOR = join(ROOT, 'scripts', 'vendor', 'mermaid.min.js')
const PROFILE = join(REPO, 'temp', 'edge-diagram')

const args = process.argv.slice(2)
const CHECK_ONLY = args.includes('--check')
const targets = args.filter((a) => !a.startsWith('--'))

if (!existsSync(EDGE)) {
  console.error(`找不到 msedge.exe：${EDGE}`)
  process.exit(1)
}
if (!existsSync(VENDOR)) {
  console.error(`缺少 mermaid 本地副本：${VENDOR}
一次性补齐（不依赖 CDN 运行，只在需要补/升级时联网）：
  curl -sL -o "${VENDOR}" https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js`)
  process.exit(1)
}
const mermaidJs = readFileSync(VENDOR, 'utf8')

// ==================== 目标文件 ====================

function mdFiles() {
  if (targets.length) {
    return targets.map((t) => resolve(process.cwd(), t))
  }
  const planDir = join(REPO, 'ai_knowledge_web', 'plan')
  if (!existsSync(planDir)) return []
  return readdirSync(planDir)
    .filter((f) => f.endsWith('.md'))
    .map((f) => join(planDir, f))
}

// ==================== 抽 mermaid 块 ====================

const FENCE = /^```mermaid\s*$/gm

/** 返回 [{ code, startLine, endLine, index }]；code 不含围栏行。 */
function extractBlocks(md) {
  const blocks = []
  let m
  FENCE.lastIndex = 0
  while ((m = FENCE.exec(md)) !== null) {
    const after = m.index + m[0].length
    const closeIdx = md.indexOf('```', after)
    if (closeIdx === -1) break
    const code = md.slice(after, closeIdx).replace(/^\n/, '').replace(/\s+$/, '')
    blocks.push({
      code,
      startLine: md.slice(0, m.index).split('\n').length,
      closeLine: md.slice(0, closeIdx).split('\n').length,
      endIndex: closeIdx + 3,
    })
    FENCE.lastIndex = closeIdx + 3
  }
  return blocks
}

/** 从代码块里挑一个像标题的行，做成文件名 slug。 */
function slugFor(code, index) {
  const line = code.split('\n').find((l) => l.trim() && !l.trim().startsWith('%%')) ?? ''
  const ascii = line
    .replace(/sequenceDiagram|flowchart\s+\w+|graph\s+\w+|stateDiagram-v2|autonumber/g, '')
    .replace(/[^A-Za-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .toLowerCase()
  const kind = /sequenceDiagram/.test(code) ? 'sequence' : /flowchart|graph/.test(code) ? 'flow' : 'diagram'
  return `${String(index + 1).padStart(2, '0')}-${kind}${ascii ? '-' + ascii.slice(0, 40) : ''}`
}

// ==================== 渲染 ====================

function renderBlock(code, outFile) {
  const html = `<!doctype html><html><head><meta charset="utf-8">
<style>html,body{margin:0;padding:16px;background:#fff;font-family:'Microsoft YaHei','PingFang SC',sans-serif}
#out svg{max-width:none;height:auto}</style></head>
<body><div id="out"></div>
<script>${mermaidJs}</script>
<script>
window.__diagram = (async () => {
  mermaid.initialize({ startOnLoad: false, theme: 'neutral', flowchart: { curve: 'basis' } });
  const src = ${JSON.stringify(code)};
  try {
    await mermaid.parse(src);
    const { svg } = await mermaid.render('g', src);
    document.getElementById('out').innerHTML = svg;
    const el = document.querySelector('#out svg');
    document.title = 'OK ' + Math.ceil(el.getBoundingClientRect().width) + 'x' + Math.ceil(el.getBoundingClientRect().height);
  } catch (e) {
    document.title = 'ERR ' + String(e && e.message ? e.message : e).slice(0, 300);
  }
})();
</script></body></html>`

  mkdirSync(dirname(outFile), { recursive: true })
  const htmlFile = outFile.replace(/\.svg$/, '.html')
  writeFileSync(htmlFile, html, 'utf8')

  const shot = spawnSync(EDGE, [
    '--headless=new', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
    `--user-data-dir=${PROFILE}`, '--virtual-time-budget=10000',
    '--dump-dom', `file:///${htmlFile.replace(/\\/g, '/')}`,
  ], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, timeout: 120000 })

  const dom = shot.stdout ?? ''
  rmSync(htmlFile, { force: true }) // 中间产物，校验模式也不留
  const title = (dom.match(/<title>([^<]*)<\/title>/) ?? [])[1] ?? ''
  // file:// 下 <title> 可能不被 --dump-dom 带出，退而从 DOM 里取 svg
  const svgMatch = dom.match(/<svg[\s\S]*?<\/svg>/)
  if (title.startsWith('ERR')) {
    return { ok: false, error: title.slice(4) }
  }
  if (!svgMatch) {
    return { ok: false, error: title || '未渲染出 <svg>（mermaid 解析失败或预算不足）' }
  }
  const size = (title.match(/^OK (\d+x\d+)/) ?? [])[1] ?? '?'
  if (!CHECK_ONLY) {
    // 加 XML 声明；把 mermaid 默认的 width="100%" + max-width 换成 viewBox 的**显式宽高**
    // —— 否则 markdown 里 ![](x.svg) 会按容器宽度拉伸，在窄栏里糊成一团。
    const vb = (svgMatch[0].match(/viewBox="([-\d.]+) ([-\d.]+) ([\d.]+) ([\d.]+)"/) ?? []).slice(1).map(Number)
    let svg = svgMatch[0]
      .replace(/^<svg/, '<svg xmlns:xlink="http://www.w3.org/1999/xlink"')
      .replace(/\s+width="100%"/, '')
      .replace(/\s+style="max-width:[^"]*"/, '')
    if (vb.length === 4 && vb.every((n) => Number.isFinite(n))) {
      const [, , w, h] = vb
      svg = svg.replace(/^<svg([^>]*)>/, `<svg$1 width="${w}" height="${h}">`)
    }
    writeFileSync(outFile, '<?xml version="1.0" encoding="UTF-8"?>\n' + svg + '\n', 'utf8')
  }
  return { ok: true, size, svg: svgMatch[0] }
}

// ==================== 主流程 ====================

mkdirSync(PROFILE, { recursive: true })

const files = mdFiles()
if (!files.length) {
  console.log('没有找到目标 .md')
  process.exit(0)
}

let total = 0
let failed = 0
const missing = []

for (const md of files) {
  const text = readFileSync(md, 'utf8')
  const blocks = extractBlocks(text)
  if (!blocks.length) continue

  const stem = basename(md).replace(/\.md$/, '')
  const outDir = join(dirname(md), stem)
  console.log(`\n${basename(md)} —— ${blocks.length} 个 mermaid 块${CHECK_ONLY ? '（仅校验）' : ''}`)

  let updated = text
  let offsetDelta = 0
  // 从后往前替换，避免位置漂移
  for (let i = blocks.length - 1; i >= 0; i--) {
    const b = blocks[i]
    const name = slugFor(b.code, i) + '.svg'
    const outFile = join(outDir, name)
    const rel = `./${stem}/${name}`
    const r = renderBlock(b.code, outFile)
    total++
    if (!r.ok) {
      failed++
      console.log(`  ✗ 第 ${b.startLine} 行   ${r.error}`)
      continue
    }
    console.log(`  ✓ 第 ${b.startLine} 行 → ${rel}   ${r.size}`)

    const marker = `\n<!-- diagram: ${rel} -->`
    // 去掉旧的 marker（重跑幂等）
    const tailStart = b.endIndex
    const tail = updated.slice(tailStart)
    const cleanedTail = tail.replace(/^\n<!-- diagram: .*? -->/, '')
    // 只保留一个 marker
    updated = updated.slice(0, tailStart) + marker + cleanedTail
  }

  if (!CHECK_ONLY && updated !== text) {
    writeFileSync(md, updated, 'utf8')
  }

  // 校验模式下也报告 markdown 里引用的 svg 是否都在
  for (const b of extractBlocks(text)) {
    void b
  }
  for (const m of text.matchAll(/<!-- diagram: (\.\/[^ ]+) -->/g)) {
    const p = resolve(dirname(md), m[1])
    if (!existsSync(p)) missing.push(`${basename(md)} → ${m[1]}`)
  }
}

// 清理 --check 留下的空目录
if (CHECK_ONLY) {
  for (const md of files) {
    const d = join(dirname(md), basename(md).replace(/\.md$/, ''))
    if (existsSync(d) && statSync(d).isDirectory() && readdirSync(d).length === 0) {
      rmSync(d, { recursive: true, force: true })
    }
  }
}

console.log(`\n合计 ${total} 个块：成功 ${total - failed}，失败 ${failed}`)
if (missing.length) {
  console.log(`\n⚠️ md 里引用了但不存在的图（${missing.length}）：`)
  for (const m of missing) console.log(`  ${m}`)
}
process.exit(failed || missing.length ? 1 : 0)
