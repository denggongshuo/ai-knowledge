/**
 * 答案正文的 Markdown 渲染 + XSS 清洗（`T-D05` / 门禁 `G9-7`）。
 *
 * <h3>为什么抽成独立模块（而不是留在 `ChatView` 里）</h3>
 * <p>渲染逻辑此前是 `ChatView.vue` 的 `<script setup>` 内部函数，**外部够不着** ——
 * 既没法直接单测，也容易被误当成"视图细节"而随手改动。抽出来后：
 * <ol>
 *   <li><b>可测真实现</b>：`scripts/markdown-xss-check.mjs` 直接 import 本模块断言，
 *       而不是测一份"抄来的副本"（副本测不到真改动，是本仓反复强调的坑）；</li>
 *   <li><b>可复用</b>：文档预览等将来要渲染 Markdown 的地方可直接用。</li>
 * </ol>
 *
 * <h3>🔴 为什么必须 sanitize，不能只靠手写转义</h3>
 * <p>模型输出是**不可信文本**。此前只做 `& < >` 三替换 —— 那在"不渲染 Markdown"时够用，
 * 但门禁 `G9-7` 要求"经 Markdown 渲染"，而一旦 `marked` 产出真 HTML，手写转义**完全不够**：
 * `[x](javascript:alert(1))` 会被渲染成 `<a href="javascript:...">`。
 * <p>所以用标准组合：**`marked` 出 HTML → `DOMPurify` 按白名单清洗**。
 *
 * <h3>⚠️ 三步顺序不可颠倒</h3>
 * <ol>
 *   <li><b>render</b>：Markdown → HTML（此时不可信）</li>
 *   <li><b>sanitize</b>：白名单清洗（这之后才可信）</li>
 *   <li><b>插角标</b>：`[n]` → `<span data-cit>`。这段 HTML 由我们生成、不含模型数据；
 *       反过来先插再 sanitize 的话，`data-cit` 会被 DOMPurify 当未知属性清掉。</li>
 * </ol>
 */
import { marked } from 'marked'
import DOMPurify from 'dompurify'

/** 引用角标的最小信息 —— 只用来判断"这个角标该不该做成可点"。 */
export interface CitationLike {
  /** 角标号（与正文里的 `[n]` 对应）。 */
  index?: number
}

/**
 * 把答案正文渲染成可安全塞进 `v-html` 的 HTML。
 *
 * @param content   模型输出的原始文本（**不可信**）
 * @param citations 本次引用清单；只把**清单里有的**角标做成可点
 */
export function renderAnswerHtml(content: string, citations?: CitationLike[]): string {
  // ① Markdown → HTML（不可信）
  const rendered = marked.parse(content ?? '', { async: false }) as string

  // ② 白名单清洗 —— 这之后才可信
  //    显式禁掉可执行标签/事件属性：宁可窄，也不依赖库的默认策略
  const safe = DOMPurify.sanitize(rendered, {
    FORBID_TAGS: ['script', 'style', 'iframe', 'object', 'embed', 'form'],
    FORBID_ATTR: ['onerror', 'onload', 'onclick', 'onmouseover', 'onfocus', 'onstart'],
  })

  // ③ 插角标（自行生成，安全）
  //    ⚠️ 不认得的角标**原样留着** —— 删掉会让正文与后端落库的那份不一致，
  //    而"模型打了个越界角标"这件事应该看得见，不是被悄悄抹掉
  return safe.replace(/\[(\d{1,3})\]/g, (whole, digits: string) => {
    const no = Number(digits)
    const cited = citations?.some((c) => c.index === no)
    return cited ? `<span class="inline-cit" data-cit="${no}">${whole}</span>` : whole
  })
}
