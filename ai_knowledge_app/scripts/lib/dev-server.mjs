/**
 * **dev server 前置自检** —— 让「闸红了」这件事**可解释**（`#226`）。
 *
 * <h3>为什么需要它：上一批的 7/25 至今没人说得清</h3>
 * `2026-09-19` 上一批观察到 `render-check` **7/25** · `layout-check` **4/4** 全红，
 * 根因**未查明**就结项了。本批回头复现：**三次全绿**（mock 热 25/25 · 清空
 * `node_modules/.vite` 后的冷启动 25/25 · 真后端模式且后端没起 22/25）。
 *
 * 已证伪三个假设：① 真后端模式（只红 3 条，且红的是**数据依赖**的标题断言）；
 * ② 冷启动/依赖预打包；③ token 被守卫拒（`router/index.ts` 的判据是
 * `!meta.public && !auth.isLoggedIn`，而 `stores/auth.ts` 是 `isLoggedIn: (s) => !!s.token`
 * —— **纯 localStorage，不校验、无 API 调用**）。
 *
 * <h3>⇒ 真问题不是"闸红了"，而是"闸的红/绿取决于一批没写下来的环境前置"</h3>
 * `render-check` / `layout-check` **硬编码 `localhost:5173`**（`console-check` 有 `--base`），
 * 而本机有这么一条坑：**vite 在 5173 被占用时会自动换到 5174**，
 * 而脚本仍然在打 5173 —— **你以为在测新代码，其实在测另一个进程**。
 * 同一批还有"必须是 mock 模式"这条隐含前提（有几条断言的数据只在 mock 里）。
 *
 * **一条会给出无法解释的红/绿的判据，与没有判据等价。**
 * 所以现在三个脚本都先跑这里：前置不满足 → **指名前置地退出**（exit 2），
 * 而不是让调用者去面对一屏"看着像代码坏了"的红。
 *
 * 这也是 `2026-09-17-测试可靠性` 那句「**闸说干净 ≠ 真的干净**」的下一代形态：
 * 那一次是"观测口径骗了人"，这一次是"**观测前提没说出口**"。
 */

/** 探针路径：mock 层**注册过**它（`src/mock/index.ts` 的 `GET /api/auth/captcha`），且无副作用。 */
const PROBE_PATH = '/api/auth/captcha'

/** 前置不满足时的退出码 —— 与"路由断言失败"（1）**分开**，让 CI/人一眼分得清。 */
export const EXIT_PRECONDITION = 2

/**
 * 探一个 dev server：**能连上吗、是不是这个前端、跑在哪个模式**。
 *
 * 本函数**不做任何输出、不退出** —— 它只诊断，由调用方决定怎么呈现
 * （纯函数才好证伪：见 `render-check.mjs` 的 `--base` 自证）。
 *
 * @param {string} base 形如 `http://127.0.0.1:5173`
 * @returns {Promise<{ok:boolean, mode:'mock'|'real'|'unknown', fatal:string[], notes:string[]}>}
 *          `ok=false` 当且仅当 `fatal` 非空
 */
export async function probeDevServer(base) {
  const fatal = []
  const notes = []

  // ---------- ① 可达吗 ----------
  let html
  try {
    const res = await fetch(`${base}/`, { signal: AbortSignal.timeout(8000) })
    html = await res.text()
  } catch (ex) {
    return {
      ok: false,
      mode: 'unknown',
      fatal: [`dev server 不可达：${base}（${ex.message}）`],
      notes: [
        '这一步是「一屏全红」最常见的成因 —— **红的是环境，不是代码**。',
        '下一步：先起 dev server，或用 `--base=<地址>` 指到已经起着的那个。',
        '⚠️ 本机的坑：**vite 在端口被占用时会自动换到下一个端口**，而脚本打的是原来那个 —— '
          + '于是你以为在测新代码，其实在测另一个进程（本仓已有先例：孤儿 vite 进程占着 5173）。',
      ],
    }
  }
  if (!/id="app"/.test(html)) {
    fatal.push(`${base}/ 回了页面，但里面没有 \`#app\` —— 它可能不是本项目的前端`)
    notes.push('确认 `--base` 指向的是 `ai_knowledge_app` 的 **dev server**（不是 `vite preview` 的 dist、也不是别的服务）。')
  }

  // ---------- ② 哪个模式 ----------
  let mode = 'unknown'
  try {
    const res = await fetch(`${base}${PROBE_PATH}`, { signal: AbortSignal.timeout(8000) })
    const text = await res.text()
    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`)
    }
    let body
    try {
      body = JSON.parse(text)
    } catch {
      throw new Error('回的体不是 JSON')
    }
    // 真后端的 `ApiResponse` **每个响应都带 `traceId`**；mock 的 `json()` 恒定只发
    // `{code, message, data}` 三个字段（见 `src/mock/index.ts`）—— 这就是判别依据。
    mode = body && typeof body === 'object' && 'traceId' in body ? 'real' : 'mock'
  } catch (ex) {
    fatal.push(`API 层探针失败：GET ${PROBE_PATH} → ${ex.message}`)
    notes.push(
      '最可能是「**真后端模式的 dev server，而后端没起**」：页面拿不到任何数据，'
        + '靠数据才能渲染的页会整页空白 —— **这就是假红的主要来源**。',
      `（另一种可能是 mock 层没拦到 ${PROBE_PATH}，但它在 \`src/mock/index.ts\` 里是注册过的，所以可能性低。）`,
      '下一步：确认这个 dev server 是怎么起的 —— 不带 `VITE_USE_MOCK=false` 起的是 **mock 模式**。',
    )
  }

  // ---------- ③ 模式与断言是否匹配（**不是错误，是解释**） ----------
  if (mode === 'real') {
    notes.push(
      '当前是 **真后端模式**。⚠️ 路由清单里有几条 `expect` 是**数据依赖**的'
        + '（`/kb/1` 期望「产品技术文档库」、`/doc/1` 与 `/doc/1/preview` 期望'
        + '「AI 知识库平台白皮书 V3」）—— 那些标题**只在 mock 数据里**。'
        + '真后端下它们会红，**而红的不是代码**。',
    )
  }

  return { ok: fatal.length === 0, mode, fatal, notes }
}

/**
 * 把诊断结果打到 stdout；前置不满足时 **exit 2**。
 *
 * @param {{ok:boolean, mode:string, fatal:string[], notes:string[]}} diag
 * @param {string} script 脚本名（只为打印好看）
 * @returns {{ok:boolean, mode:'mock'|'real'|'unknown', fatal:string[], notes:string[]}} 原样回传
 *          —— 调用方要拿 `mode` 决定"哪些数据依赖的断言不该判红"
 */
export function reportPrecondition(diag, script) {
  if (!diag.ok) {
    console.error(`\n✗ ${script}：**开工前置不满足**（exit ${EXIT_PRECONDITION}）\n`)
    for (const f of diag.fatal) {
      console.error(`  • ${f}`)
    }
    if (diag.notes.length) {
      console.error('')
      for (const n of diag.notes) {
        console.error(`    ${n}`)
      }
    }
    console.error('')
    process.exit(EXIT_PRECONDITION)
  }
  console.log(`dev server 前置自检通过（模式：${diag.mode}）`)
  for (const n of diag.notes) {
    console.log(`  ⚠️ ${n}`)
  }
  return diag
}
