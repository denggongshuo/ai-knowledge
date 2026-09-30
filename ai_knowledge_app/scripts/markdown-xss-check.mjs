#!/usr/bin/env node
/**
 * `G9-7` XSS 防护判据（`T-D05`）—— **测真实现，不测副本**。
 *
 * <h3>为什么不写成 Node 单测</h3>
 * 实测：`DOMPurify` 在 Node 里是**工厂函数**（`DOMPurify.sanitize is not a function`），
 * 它的正常工作环境是浏览器。且本项目已有 `render-check.mjs` / `console-check.mjs`
 * 用 **Edge headless** 的范式 —— 本脚本沿用同一套，不起新依赖（不装 jsdom）。
 *
 * <h3>口径</h3>
 * 两组断言，缺一不可：
 *   ① **恶意样本必须被清掉** —— DOM 里零 <script>、零 on* 属性、零 javascript: 协议
 *   ② **正常 Markdown 必须仍能渲染** —— 防止"为了安全把功能砍了"的实现蒙混过关
 *      （只验①的话，一个把内容全砍掉的实现也能过）
 *
 * 退出码：`0` 全过 · `1` 有断言失败 · `2` 前置不满足（dev server 不可达）
 */
import { spawn } from 'node:child_process'

const BASE = (process.argv.find((a) => a.startsWith('--base=')) ?? '--base=http://127.0.0.1:5173').slice(7)
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
const PORT = 9334

const MALICIOUS = [
  { label: '<script> 标签',      input: '<script>alert(1)</script>',                       forbid: /<script/i },
  { label: '<img onerror>',      input: '<img src=x onerror=alert(1)>',                     forbid: /onerror=/i },
  { label: 'js: 链接（Markdown）', input: '[click](javascript:alert(1))',                     forbid: /javascript:/i },
  { label: '<iframe>',           input: '<iframe src="http://evil.com"></iframe>',          forbid: /<iframe/i },
  { label: 'onclick 属性',        input: '<div onclick="alert(1)">x</div>',                  forbid: /onclick=/i },
  { label: 'svg onload',         input: '<svg onload=alert(1)></svg>',                      forbid: /onload=/i },
]

const NORMAL = [
  { label: '粗体',       input: '**加粗**',        expect: /<strong>加粗<\/strong>/i },
  { label: '无序列表',    input: '- 项目一',        expect: /<li>项目一<\/li>/i },
  { label: '行内代码',    input: '`code`',          expect: /<code>code<\/code>/i },
  { label: '标题',       input: '# 标题一',         expect: /<h1[^>]*>标题一<\/h1>/i },
]

function cdp() {
  return new Promise((resolve, reject) => {
    const child = spawn(EDGE, [
      `--remote-debugging-port=${PORT}`, '--headless=new', '--disable-gpu', '--no-sandbox',
      '--user-data-dir=F:/work/code/ai-knowledge/temp/edge-xss', 'about:blank',
    ], { stdio: 'ignore' })
    reject._child = child
    setTimeout(() => resolve(child), 3500)
  })
}

async function main() {
  // 前置：dev server 可达
  try {
    const r = await fetch(BASE)
    if (!r.ok) throw new Error(`HTTP ${r.status}`)
  } catch (e) {
    console.error(`✗ 前置不满足：dev server ${BASE} 不可达（${e.message}）`)
    process.exit(2)
  }

  const child = await cdp()
  const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()
  const page = list.find((t) => t.type === 'page')
  const ws = new WebSocket(page.webSocketDebuggerUrl)
  let id = 0
  const send = (m, p = {}) => new Promise((res) => {
    const mid = ++id
    const h = (e) => { const d = JSON.parse(e.data); if (d.id === mid) { ws.removeEventListener('message', h); res(d) } }
    ws.addEventListener('message', h)
    ws.send(JSON.stringify({ id: mid, method: m, params: p }))
  })
  await new Promise((r) => ws.addEventListener('open', r))
  await send('Page.enable')
  await send('Runtime.enable')

  // 打开前端页（模块经由 Vite 提供），在页面上下文里动态 import 真模块
  await send('Page.navigate', { url: BASE })
  await new Promise((r) => setTimeout(r, 4000))

  const expr = `
    (async () => {
      const mod = await import('/src/utils/markdown.ts')
      const malicious = ${JSON.stringify(MALICIOUS)}
      const normal = ${JSON.stringify(NORMAL)}
      const out = { mal: [], norm: [] }
      for (const c of malicious) {
        const html = mod.renderAnswerHtml(c.input, [])
        // 塞进真 DOM，再读回 —— 这才是"浏览器实际会执行什么"的检验
        const d = document.createElement('div'); d.innerHTML = html
        out.mal.push({ label: c.label, html, dom: d.innerHTML })
      }
      for (const c of normal) {
        const html = mod.renderAnswerHtml(c.input, [])
        const d = document.createElement('div'); d.innerHTML = html
        out.norm.push({ label: c.label, html, dom: d.innerHTML })
      }
      return JSON.stringify(out)
    })()
  `
  const res = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })
  child.kill()
  if (!res.result?.result?.value) {
    console.error('✗ 无法在页面里执行断言：', JSON.stringify(res).slice(0, 400))
    process.exit(1)
  }
  const data = JSON.parse(res.result.result.value)

  let failed = 0
  console.log('=== ① 恶意样本：必须被清掉 ===')
  for (let i = 0; i < data.mal.length; i++) {
    const d = data.mal[i]; const c = MALICIOUS[i]
    const bad = c.forbid.test(d.dom)
    if (bad) failed++
    console.log(`${bad ? '✗' : '✓'} ${d.label.padEnd(20)} dom=${d.dom.slice(0, 70)}`)
  }
  console.log('\n=== ② 正常 Markdown：必须仍能渲染 ===')
  for (let i = 0; i < data.norm.length; i++) {
    const d = data.norm[i]; const c = NORMAL[i]
    const ok = c.expect.test(d.dom)
    if (!ok) failed++
    console.log(`${ok ? '✓' : '✗'} ${d.label.padEnd(20)} dom=${d.dom.slice(0, 70)}`)
  }
  console.log(`\n合计：${data.mal.length + data.norm.length} 条断言，失败 ${failed} 条`)
  process.exit(failed ? 1 : 0)
}

main().catch((e) => { console.error('✗ 脚本异常：', e.message); process.exit(1) })
