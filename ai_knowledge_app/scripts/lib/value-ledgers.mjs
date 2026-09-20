/**
 * 「需求 ↔ 后端枚举 ↔ mock 取值」的三方登记表（`T-O20`）。
 *
 * <h3>为什么需要它</h3>
 * <p>`audit-api.mjs` 的 ①/② 只比对**接口路径**（前端调用 ↔ mock ↔ 文档），
 * **不比对取值** —— 于是 `#164` 那种漂移**没有任何机器能发现**：
 * 需求 `07` §6.8 与《01-示例数据字典》§7.11 与后端 `ChatFeedbackReason`
 * **三方逐字一致**，而 `mock/data.ts` 里写的是另两条串。
 * 四方里三方一致、只有 mock 独自漂着，而 mock 正是渲染自检与开发时页面的取数源。
 *
 * <p>🔴 更值得记的是**它为什么没被抓到**：枚举的类注释里**写了**这条漂移
 * （"前端 mock 的展示串与之有漂移"）—— 但**注释拦不住**。本仓对这件事有判例：
 * `#202`/`#203`（新增 MinIO 前缀只有 javadoc 在管，漏一次 ⇒ 一轮跑批留 180 个孤儿）。
 *
 * <h3>登记表而不是通用解析器</h3>
 * <p>只登记**拿得出三处**的闭集，每行写清三处的位置与提取式。不做"给文档写编译器"
 * —— 那件的长期成本远超收益（`check-ledgers.mjs` 的同一条纪律：**只报不改**）。
 *
 * <h3>加一行要写什么</h3>
 * <ul>
 *   <li>{@code extract}：那一方取值的提取式。⚠️ 要取到**列举本身**，
 *       不是"那一行里出现的所有中文"（后者会把无关词也算进来）；</li>
 *   <li>{@code expect}：**冻结的数量** —— 见下。</li>
 * </ul>
 *
 * <h3>🔴 为什么每一方都要写一个冻结数量（`expect`）</h3>
 * <p>第一版没有它，**证伪时被自己的探针逮到**：把枚举里 `INACCURATE("答案不准确")`
 * 的双引号改成单引号，提取式只抽到 **4** 个（那个值静默消失），而闸报的是
 * 「**需求文档**多了 [答案不准确]」—— 退 1 是对的（没有静默变绿），但**指向错的一方**，
 * 排查的人会去查需求文档。
 * <p>更糟的是另一半：**数量少了它是不知道的**。`expect` 把这件事变成一句可判的话：
 * 抽出来的个数与登记的不符 ⇒ **退 2（前置不满足）**，而不是拿去和别方比。
 * <p>代价是"给对方加一个取值"要顺手改这个数 —— 那正是本仓一贯想要的
 * **人工判断点**（与"新增 MinIO 前缀必须先过一次人的判断"同形）。
 */

/** 从 `NAME("中文")` 里抽第二组 —— 本仓枚举的落库值就是中文标签本身。 */
function enumLabelsOf(text) {
  return [...text.matchAll(/\w+\("([^"]+)"\)/g)].map((m) => m[1])
}

/** 「A / B / C」→ ['A','B','C']（需求文档是这么列举的）。 */
function splitSlash(text) {
  return text.split(/\s*[/／]\s*/).map((s) => s.trim()).filter(Boolean)
}

/** mock 里的 `{ reason: '…' }`。 */
function mockReasonsOf(text) {
  return [...text.matchAll(/reason:\s*'([^']+)'/g)].map((m) => m[1])
}

export const VALUE_LEDGERS = [
  {
    id: 'ChatFeedbackReason',
    what: '点踩原因（需求 `07` §6.8「点踩时可选原因」与《01》§7.11 两处一致）',
    /** 三方各自的取值来源。⚠️ 每项返回**数组**；解析不出东西时要报红，不是静默成空集。 */
    sources: [
      {
        name: '后端枚举',
        expect: 5,
        file: 'ai_knowledge_web/src/main/java/com/knowledge/ai/core/enums/ChatFeedbackReason.java',
        extract: (text) => enumLabelsOf(text).filter((l) => !l.includes('点踩')),
      },
      {
        name: '需求文档',
        expect: 5,
        file: 'docs/需求文档/07-需求-检索与问答.md',
        extract: (text) => {
          const m = text.match(/^.*点踩时可选原因[：:]\s*(.+)$/m)
          return m ? splitSlash(m[1]) : []
        },
      },
      {
        name: 'mock 取值',
        expect: 5,
        file: 'ai_knowledge_app/src/mock/data.ts',
        extract: (text) => {
          const m = text.match(/export const mockFeedbackStats[\s\S]*?reasons:\s*\[([\s\S]*?)\n\s*\],/)
          return m ? mockReasonsOf(m[1]) : []
        },
      },
    ],
  },
  {
    id: 'MissStatus',
    what: '未命中清单的状态机（需求 `08` §1.3 的四态）',
    sources: [
      {
        name: '后端枚举',
        expect: 4,
        file: 'ai_knowledge_web/src/main/java/com/knowledge/ai/core/ops/MissStatus.java',
        extract: (text) => [...text.matchAll(/^\s{4}([A-Z_]+)\(/gm)].map((m) => m[1]),
      },
      {
        name: '前端类型',
        expect: 4,
        file: 'ai_knowledge_app/src/types/index.ts',
        extract: (text) => {
          const m = text.match(/export type MissStatus\s*=\s*([^\n]+)/)
          return m ? [...m[1].matchAll(/'([^']+)'/g)].map((x) => x[1]) : []
        },
      },
      {
        name: 'mock 文案表',
        expect: 4,
        file: 'ai_knowledge_app/src/views/ops/OpsQaView.vue',
        extract: (text) => [...text.matchAll(/^\s{2}([A-Z_]+):\s*\{\s*text:/gm)].map((m) => m[1]),
      },
    ],
  },
]
