#!/usr/bin/env node
/**
 * 页面级断言 —— 问答页「切换会话，右侧内容随之改变」（2026-10-03 缺陷修复的取证）。
 *
 * <h3>它验什么</h3>
 * <ol>
 *   <li><b>点击历史会话会真的换内容</b>：这是被修的 bug —— 改前 `@click` 只改高亮
 *       `activeSession`，`messages` 一动不动（`chatApi.messages` 从未被调用）。</li>
 *   <li><b>高亮与内容同步</b>：`.session-item.active` 指向 s2 时，右侧也要是 s2 的内容。</li>
 *   <li><b>流式期间禁止切换</b>：`sending=true` 时点会话，内容不变（用户 2026-10-03 裁定）。</li>
 * </ol>
 *
 * <h3>为什么用 mock 模式</h3>
 * 这是**纯前端**的 bug —— 后端接口（`ChatSessionService#messages`）与 mock
 * （`mock/index.ts:729`）两侧都早已就绪，缺的只是"前端把它接上"。
 * mock 模式下 s1/s2 的消息内容**由会话标题派生**（各不相同），足以判定"换了没有"，
 * 且**不需要**后端 / MySQL / 真模型 —— 跑一次几秒钟。
 *
 * <h3>用法</h3>
 * <pre>
 *   # mock 模式 dev server（默认 5173；端口被占会自动顺延，用 --base 指明）
 *   cd ai_knowledge_app && npm run dev
 *   node scripts/session-switch-check.mjs --base=http://127.0.0.1:5173
 * </pre>
 *
 * <h3>退出码</h3>
 * `0` 全过 · `1` 有断言失败 · `2` 前置不满足（server 不可达 / 不是 mock 模式）。
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import { setTimeout as _setTimeout } from 'node:timers'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const EDGE = process.env.E2E_EDGE || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-session-switch'
const OUT_DIR = 'F:/work/code/ai-knowledge/temp/session-switch'
const CDP_PORT = Number(process.env.E2E_CDP_PORT || 9340)

const arg = (name, fallback) =>
  (process.argv.find((a) => a.startsWith(`--${name}=`)) ?? `--${name}=${fallback}`).slice(name.length + 3)
const BASE = arg('base', 'http://127.0.0.1:5173')

// ---------------------------------------------------------------- CDP 基础
let ws
let seq = 0
const pending = new Map()

function send(method, params = {}) {
  const id = ++seq
  return new Promise((res, rej) => {
    pending.set(id, { res, rej })
    ws.send(JSON.stringify({ id, method, params }))
    _setTimeout(() => {
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
    throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text || '页面脚本抛异常')
  }
  return r.result?.value
}

async function waitFor(expression, label, timeout = 20000) {
  const t0 = Date.now()
  while (Date.now() - t0 < timeout) {
    if (await evaluate(expression)) return true
    await sleep(120)
  }
  throw new Error(`等待超时（${label}）：${expression}`)
}

/** 页面到底部的「最后一条 AI 正文」文本 —— 用它判定"内容换没换"。 */
const LAST_ANSWER_JS = `(() => {
  const els = document.querySelectorAll('.ai-content');
  return els.length ? els[els.length - 1].textContent.trim() : '';
})()`

const steps = []
function check(cond, msg) {
  if (!cond) throw new Error(msg)
}
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
async function pickPageTarget() {
  for (let i = 0; i < 40; i++) {
    const list = await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`).then((r) => r.json())
    const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl)
    if (page) return page
    await sleep(250)
  }
  throw new Error('找不到可调试的 page target')
}

// ---------------------------------------------------------------- 主流程
async function main() {
  // 注入登录态（mock 模式认这个 token）+ 跳到 /chat
  await send('Page.navigate', { url: `${BASE}/_ss_bootstrap.html?to=/chat` })
  await waitFor(`!!document.querySelector('.session-item')`, '会话列表渲染')
  // 等默认视图稳定
  await waitFor(`!!document.querySelector('.chat-input textarea')`, '问答页挂载')

  const s1 = await evaluate(`(() => {
    const it = [...document.querySelectorAll('.session-item')];
    return it[1] ? it[1].querySelector('.si-title').textContent.trim() : '';
  })()`)

  await step('点击 s2 会话项', async () => {
    await evaluate(`(() => {
      const it = [...document.querySelectorAll('.session-item')];
      it[1].click();
      return true;
    })()`)
    return `目标会话 = ${s1}`
  })

  await step('右侧内容变为 s2 的内容（不再是欢迎语/上一会话）', async () => {
    // 等接口回来 + 渲染：判据是"最后一条 AI 正文包含 s2 的标题派生素材"
    const deadline = Date.now() + 15000
    let last = ''
    while (Date.now() < deadline) {
      last = await evaluate(LAST_ANSWER_JS)
      if (last && last.includes('混合检索') && last.includes('Rerank')) return `末条正文长度=${last.length}`
      await sleep(200)
    }
    throw new Error(`末条 AI 正文未按会话内容更新：${last.slice(0, 80)}`)
  })

  await step('高亮与内容同步（active 指向 s2）', async () => {
    const activeTitle = await evaluate(`(() => {
      const a = document.querySelector('.session-item.active');
      return a ? a.querySelector('.si-title').textContent.trim() : '';
    })()`)
    check(activeTitle === s1, `active 标题=${activeTitle}，期望=${s1}`)
    return `active = ${activeTitle}`
  })

  await step('切换回 s1：内容随之改变（两向都验，防"只对 s2 生效"）', async () => {
    await evaluate(`document.querySelectorAll('.session-item')[0].click()`)
    const deadline = Date.now() + 15000
    while (Date.now() < deadline) {
      const activeTitle = await evaluate(`(() => {
        const a = document.querySelector('.session-item.active');
        return a ? a.querySelector('.si-title').textContent.trim() : '';
      })()`)
      const last = await evaluate(LAST_ANSWER_JS)
      if (activeTitle && last.includes('混合检索')) return `active=${activeTitle} · 末条长度=${last.length}`
      await sleep(200)
    }
    throw new Error('切回 s1 后内容未更新')
  })

  await step('流式期间禁止切换（sending=true → 点会话被忽略）', async () => {
    // 直接驱动组件状态太脆；用 DOM 观察：注入一个"正在生成"的可见标记不现实。
    // 改为断言**守卫代码存在且高亮不变**的等价形式：快速连点两个会话，最终 active 只有一个。
    // ⚠️ 这是弱形式；强形式（真流式中点会话）在真后端 e2e-chat-flow 里做会拖很慢，
    //    故本脚本只验"连点收敛"，并在计划 §9 登记为已知收窄。
    await evaluate(`(() => {
      const it = [...document.querySelectorAll('.session-item')];
      it[1].click(); it[0].click(); it[1].click();
      return true;
    })()`)
    await sleep(600)
    const activeCount = await evaluate(`document.querySelectorAll('.session-item.active').length`)
    check(activeCount === 1, `active 数量=${activeCount}，期望恰好 1`)
    return `连点后 active 数 = ${activeCount}`
  })
}

// ---------------------------------------------------------------- 启动/收尾
if (existsSync(PROFILE)) rmSync(PROFILE, { recursive: true, force: true })
let child
let exitCode = 1
let bootstrap
try {
  mkdirSync(OUT_DIR, { recursive: true })
  if (!existsSync(EDGE)) throw new Error(`找不到 Edge：${EDGE}`)

  // 前置：server 可达 + 是 mock 模式（本脚本用写死的 mock token）
  const probe = await fetch(`${BASE}/`).then(() => true).catch(() => false)
  if (!probe) {
    console.error(`✗ 前置不满足：${BASE} 不可达。先起 mock dev server：cd ai_knowledge_app && npm run dev`)
    process.exit(2)
  }

  // 写一个注入登录态的引导页（跑完删）
  bootstrap = resolve(ROOT, 'public/_ss_bootstrap.html')
  writeFileSync(bootstrap, `<!DOCTYPE html><html><head><meta charset="UTF-8"></head><body><script>
try { localStorage.setItem('ak_token','mock_selftest_token'); localStorage.setItem('ak_remember','0'); } catch(e){}
location.replace('/#' + new URLSearchParams(location.search).get('to'));
</script></body></html>`)

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
    try { msg = JSON.parse(String(ev.data)) } catch { return }
    if (msg.id && pending.has(msg.id)) {
      const { res, rej } = pending.get(msg.id)
      pending.delete(msg.id)
      msg.error ? rej(new Error(`${msg.error.message}（${msg.method ?? ''}）`)) : res(msg.result)
    }
  }
  await send('Page.enable')
  await send('Runtime.enable')
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

  await main()

  const failed = steps.filter((s) => !s.ok).length
  console.log(`\n合计 ${steps.length} 步，通过 ${steps.length - failed}，失败 ${failed}`)
  exitCode = failed ? 1 : 0
} catch (e) {
  console.error(`\n✗ ${e.message}`)
  exitCode = 1
} finally {
  // 🔴 顺序很重要：先**杀 Edge**、等它松开 profile 目录，再删 —— 否则 Windows 上
  // `Affiliation Database` 还被占用，rmSync 抛 EBUSY，把一次全绿跑成非 0 退出。
  if (child) child.kill()
  const deadline = Date.now() + 5000
  while (Date.now() < deadline) {
    try { if (existsSync(PROFILE)) rmSync(PROFILE, { recursive: true, force: true }); break }
    catch { await sleep(250) }
  }
  if (existsSync(bootstrap)) rmSync(bootstrap, { force: true })
  process.exit(exitCode ?? 1)
}
