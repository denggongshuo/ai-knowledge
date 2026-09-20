/**
 * **登记表机器对账**（`T-O12` · `2026-09-19-S6-M11` 批）。
 *
 * 本仓的批次账 / 门禁账 / 索引全是**手抄的**，没有任何东西在对账。
 * 本脚本把三组事实互相比对，**只报不改**：
 *
 *   L1 **门禁号**   —— 排期里的 `Gx-n` 定义表 ↔ 各批计划里引用的 `Gx-n`
 *   L2 **计划索引** —— `plan/README.md` 的索引表 ↔ 盘上的 `plan/*.md`
 *   L3 **批次账**   —— `plan/汇总.md` 的批次表 ↔ 盘上的 `plan/*.md`
 *
 * ---------------------------------------------------------------------------
 * 🔴 为什么只报不改
 * ---------------------------------------------------------------------------
 * 自动改写台账**改错比不改更贵** —— 它会把错误**固化进权威文档**，
 * 而且下一批人会拿它当事实。判据里有这一条：跑完 `git status --short` 必须无差异。
 *
 * ---------------------------------------------------------------------------
 * 🔴 为什么退出码要分档（承 2026-09-19 的硬口径）
 * ---------------------------------------------------------------------------
 * `0` 全部一致 · `1` 发现不一致 · **`2` 前置不满足**。
 * `2` 里最要紧的一种是「**一张门禁表都没解析到**」——
 * 解析器自己坏掉时会报"零不一致"，**看起来全绿**。那正是本仓反复踩的
 * 「闸说干净 ≠ 真的干净」（`#136`），所以必须与"真的一致"分开。
 *
 * ---------------------------------------------------------------------------
 * ⚠️ 射程（如实写明）
 * ---------------------------------------------------------------------------
 *   · L1 只能对**排期里存在**的门禁号做比对。「同一段文字被挂到了另一个 Sprint 的号」
 *     这种错，如果两边都说得通，**抓不到**。
 *   · L1 用的是**词元重叠**（中文按二元字、英文按词）。中文判据文字短，**必然会误报**，
 *     所以阈值取得很低（{@link SIMILARITY_FLOOR}）且**只报差异不判对错** ——
 *     报告是给人扫一眼的，不是自动判决。
 *   · L2/L3 只认**表格行里**的文件名（不认正文里提到的）——
 *     否则 `plan/README.md` 里那句「例：`2026-09-14-S3-M04-B.md`」会被当成死链。
 *
 * 用法：`node scripts/check-ledgers.mjs`（在 `ai_knowledge_app/` 下跑）
 */
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { basename, dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO = resolve(HERE, '..', '..')
const PLAN_DIR = resolve(REPO, 'ai_knowledge_web', 'plan')
const SCHEDULE = resolve(REPO, 'docs', '进度', '03-Sprint排期.md')
const INDEX = resolve(PLAN_DIR, 'README.md')
const SUMMARY = resolve(PLAN_DIR, '汇总.md')

const EXIT_OK = 0
const EXIT_MISMATCH = 1
const EXIT_PRECONDITION = 2

/**
 * 词元重叠的**下限**。低于它才算"同名不同义"。
 *
 * ⚠️ 取 0.1 而不是 0.5：中文判据文字短（「通知出口」只有 3 个二元字），
 * 一条**合法**的短引用（计划里只写 `G6-12 通知出口`）重叠率也就 0.2 左右。
 * 阈值定高了会把合法引用全报成错 —— 而**一条总在叫的闸会被忽略**
 * （这正是本批另一条硬口径的由来）。⇒ 宁可让 0 重叠的那种刺眼地跳出来。
 */
const SIMILARITY_FLOOR = 0.1

/** L2/L3 不参与对账的文件（不是"批次计划"这一族）。 */
const NOT_A_BATCH_PLAN = new Set(['README.md', '汇总.md', '汇总-归档.md', 'plan-template.md'])

const problems = []
const notes = []

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

/** 是一行表格吗？是的话返回单元格数组（去掉首尾空段）。 */
function tableRow(line) {
  const t = line.trim()
  if (!t.startsWith('|') || !t.endsWith('|')) return null
  const cells = t.slice(1, -1).split('|').map((c) => c.trim())
  return cells.length >= 2 ? cells : null
}

/** 词元集合：中文按**二元字**，英文/标识符按词。 */
function tokens(s) {
  const ascii = (s.match(/[A-Za-z_][A-Za-z0-9_./-]*/g) ?? []).map((t) => t.toLowerCase())
  const bigrams = []
  for (const run of s.replace(/[^一-龥]/g, ' ').split(/\s+/)) {
    for (let i = 0; i + 1 < run.length; i++) bigrams.push(run.slice(i, i + 2))
  }
  return new Set([...ascii, ...bigrams])
}

/** `authoritative` 的词元有多大比例出现在 `claimed` 里。 */
function coverage(claimed, authoritative) {
  const a = tokens(authoritative)
  if (a.size === 0) return 1
  const c = tokens(claimed)
  let hit = 0
  for (const t of a) if (c.has(t)) hit++
  return hit / a.size
}

/**
 * 表格行里出现的**批次计划**文件名。
 *
 * ⚠️ 只认**第一格** —— `plan/汇总.md` 的 §2 是「文件 | 批次 | 任务 | 状态 | …」，
 * 而它后面几列写的是**交付物**（`03-Sprint排期.md`、`13-接口变更记录.md`、
 * `O11-audit-export-fallback.md` …）。扫描整行会把交付物当成批次账，
 * 报出一串"盘上没有"的死链 —— 初版就是这样，5 条误报。**列的语义比文件名形状更可靠。**
 *
 * ⚠️ 也只认**表格行** —— `plan/README.md` 的命名说明里有一句「例：`2026-09-14-S3-M04-B.md`」，
 * 那是举例不是登记，正文里的不算。
 */
function planFilesInTableOf(path) {
  const found = new Set()
  for (const line of readFileSync(path, 'utf8').split(/\r?\n/)) {
    const cells = tableRow(line)
    if (cells === null) continue
    for (const m of cells[0].matchAll(/`([^`]*\.md)`/g)) {
      const name = basename(m[1])
      if (/^\d{4}-\d{2}-\d{2}-/.test(name) && !NOT_A_BATCH_PLAN.has(name)) found.add(name)
    }
  }
  return found
}

// ---------------------------------------------------------------------------
// 前置
// ---------------------------------------------------------------------------

if (!existsSync(PLAN_DIR)) {
  console.error(`✗ 前置不满足：找不到 ${PLAN_DIR}`)
  process.exit(EXIT_PRECONDITION)
}
if (!existsSync(SCHEDULE)) {
  console.error(`✗ 前置不满足：找不到 ${SCHEDULE}`)
  process.exit(EXIT_PRECONDITION)
}

// ---------------------------------------------------------------------------
// L1 · 门禁号对账
// ---------------------------------------------------------------------------

/** 权威表：`docs/进度/03-Sprint排期.md` 里 `| Gx-n | 名称 | 判据 |` 那几张表。 */
function authoritativeGates() {
  const gates = new Map()
  for (const line of readFileSync(SCHEDULE, 'utf8').split(/\r?\n/)) {
    const cells = tableRow(line)
    if (!cells || cells.length < 3) continue
    const id = cells[0].replace(/[`*]/g, '').trim()
    if (!/^G\d+-\d+$/.test(id)) continue
    gates.set(id, `${cells[1]} ${cells[2]}`.replace(/\s+/g, ' ').trim())
  }
  return gates
}

const gates = authoritativeGates()
if (gates.size === 0) {
  console.error('✗ 前置不满足：在排期里**一张门禁表都没解析到** —— '
    + '解析器坏了会报「零不一致」而看着全绿，故这里按前置失败处理（退出码 2）')
  process.exit(EXIT_PRECONDITION)
}
notes.push(`L1 权威门禁表：${gates.size} 条（来自 03-Sprint排期.md）`)

const planFiles = readdirSync(PLAN_DIR).filter((f) => f.endsWith('.md') && !NOT_A_BATCH_PLAN.has(f))

/** 表的**第一格是否以门禁号开头** —— "这一行是在讲这个门禁"，而不是正文里随口提到它。 */
const STARTS_WITH_GATE = /^[*`\s]*(G\d+-\d+)[*`\s]*/

/**
 * 只判**验收段（§6/§7）**里的行。
 *
 * <p>⚠️ 这一条是**降噪的关键**，也是初版 140 条误报的根因：
 * 在别的章节里，`Gx-n` 是**证据的引用**（「见 `G3-7`」「本批处理 `G3-7`」），
 * 那一行写的是"我做了什么"，**本来就不该复述门禁的原文**。拿它去比对文字，
 * 必然大面积误报 —— 而**一条总在叫的闸会被忽略**（本批另一条硬口径的由来）。
 * §6+7 是"验收方法与标准"，那一张表**才**是"这批认为这个门禁是什么"的正式表述。
 */
function isAcceptanceHeading(line) {
  return /^#{1,4}\s*(6|7)[\s.、·+]/.test(line.trim()) || /验收方法与标准/.test(line)
}

function isAfterAcceptance(line) {
  return /^#{1,4}\s*(8|9|10)[\s.、·]/.test(line.trim())
}

for (const file of planFiles) {
  const text = readFileSync(resolve(PLAN_DIR, file), 'utf8')
  const lines = text.split(/\r?\n/)
  /**
   * 🔴 **已声明订正的文件**：`S8-M10-A`（2026-09-20）起支持的**唯一**一个"豁免口"。
   *
   * 由来：`S6-M09-B` 那一批把**自定编号**当门禁号写进了判据表，而正文**刻意不改**
   * （当时的记法是事实，改掉会毁掉"那一批到底以为什么"的证据）⇒ 它会被永远报出来。
   * 而**一条永远在报的闸会被忽略，被忽略的闸等于没有**（本仓已定过这条）。
   *
   * 判法：文件里出现一行显式的「门禁号订正」说明 ⇒ 该文件的**同名不同义**降级为
   * **一条汇总说明**（仍然打印，仍然算"有这回事"，但不再计入不一致）。
   * ⚠️ 只降级这一类；「引用了排期里不存在的门禁号」**照报** —— 那是另一回事。
   * ⚠️ 别拿它当消音器：声明行会**原样打印在输出里**，人手加一行"订正"来消音是看得见的。
   */
  const annotated = /门禁号订正/.test(text)
  let annotatedMismatch = 0
  let inAcceptance = false
  lines.forEach((line, i) => {
    if (isAcceptanceHeading(line)) inAcceptance = true
    else if (isAfterAcceptance(line)) inAcceptance = false
    if (!inAcceptance) return

    const cells = tableRow(line)
    if (!cells) return
    // ⚠️ 认**任意一格**「以门禁号开头」，不认第 0 格 ——
    //    本批要抓的那个错号就在第二格（`| 9 | **`G6-12`** 消息可回看… |`，
    //    第一格是行号）。只认第 0 格的话，这条判据**什么都抓不到**（实测：0 条）。
    //    但也不认"正文里随口提到"（号在句子中间）—— 那种行写的是别的事。
    const idx = cells.findIndex((c) => STARTS_WITH_GATE.test(c))
    if (idx < 0) return

    const id = STARTS_WITH_GATE.exec(cells[idx])[1]
    const authoritative = gates.get(id)
    if (!authoritative) {
      problems.push(`L1 ${file}:${i + 1} 引用了排期里**不存在**的门禁号 ${id}`)
      return
    }
    const claimed = `${cells[idx].replace(/[*`]/g, '').replace(/G\d+-\d+/, '')} ${cells[idx + 1] ?? ''}`
      .replace(/\s+/g, ' ').trim()
    if (claimed.length < 2) return

    // 🔴 **证据行不算判据表述**，跳过。它们的第二列是**测试方法名 / 命令**，不是判据描述：
    //    `| G4-1 | 后端：`DocUploadFlowTest#uploadPersists...`（真 multipart） |`
    //    拿这种行去比对文字必然零重叠 —— 初版 140 条误报里一大半是这个。
    //    判法：**把门禁号本身去掉之后**，格子里还能看到反引号标识符或命令行就算证据行。
    if (/`/.test(claimed) || /\bmvn\b|\bnode\b|-Dtest=|\.mjs/.test(claimed)) return

    // 🔴 **交叉引用不算判据表述**：`| G4-11 | 同判据 12 |`、`| G4-4 | / 上游判据 13 |`
    //    ——它说的是"这条与那条是同一次取证"，本来就不复述原文。跳过。
    if (/^[/·\s]*(同|上游|参见|见)?\s*判据\s*\d+/.test(claimed)) return
    // 「同 E2E（…）」「同判据 9」这类**指回上一行**的格子，同样不是在复述判据。
    if (/^同\s/.test(claimed)) return
    // 只有破折号/分隔符的占位格（`——`）没有信息量。
    if (/^[-—–·\s]+$/.test(claimed)) return

    // 只有**两边都零重叠**才报（名字与全文各比一次，任一有共同词元就放行）。
    // ⚠️ 这个口子开得很松，是有意的：计划里常用改写（「创建向导」写成「创建立即可回读」、
    //    「改配置不自动生效」写成「needReindex 语义正确」），那都是**合法的细化**。
    //    松到只剩"完全说的不是一回事"才报 —— 而那正是本批要抓的形态。
    const namePart = authoritative.split(' ')[0]
    if (coverage(claimed, namePart) === 0 && coverage(claimed, authoritative) === 0) {
      if (annotated) {
        annotatedMismatch++
        return
      }
      problems.push(`L1 ${file}:${i + 1} **同名不同义**：${id}\n`
        + `       排期里说的是：${authoritative.slice(0, 90)}\n`
        + `       这里写的是  ：${claimed.slice(0, 90)}`)
    }
  })
  if (annotatedMismatch > 0) {
    notes.push(`L1 ${file}：该文件已声明「门禁号订正」⇒ 其中 ${annotatedMismatch} 处同名不同义`
      + '按**订正记录**处理（不再计入不一致）。⚠️ 声明行本身请留在文件里，别删')
  }
}

// ---------------------------------------------------------------------------
// L2 / L3 · 索引与批次账对账
// ---------------------------------------------------------------------------

const onDisk = new Set(planFiles)

for (const [label, path] of [['L2 plan/README.md 索引', INDEX], ['L3 plan/汇总.md 批次账', SUMMARY]]) {
  if (!existsSync(path)) {
    problems.push(`${label}：文件不存在 ${path}`)
    continue
  }
  const listed = planFilesInTableOf(path)
  if (listed.size === 0) {
    // 与 L1 同理：解析不到 ≠ 一致。这是"闸的输入为空"的形态。
    console.error(`✗ 前置不满足：${label} 里**一个文件名都没解析到**（解析器坏了会假绿）`)
    process.exit(EXIT_PRECONDITION)
  }
  for (const f of onDisk) {
    if (!listed.has(f)) problems.push(`${label}：盘上有而表里**漏登** —— ${f}`)
  }
  for (const f of listed) {
    if (!onDisk.has(f)) problems.push(`${label}：表里有而盘上**没有**（死链）—— ${f}`)
  }
  notes.push(`${label}：登记 ${listed.size} 个文件`)
}

// ---------------------------------------------------------------------------
// 报告
// ---------------------------------------------------------------------------

for (const n of notes) console.log(`… ${n}`)

if (problems.length === 0) {
  console.log(`✓ 三处对账一致（批次计划 ${onDisk.size} 份）`)
  process.exit(EXIT_OK)
}
console.log(`\n✗ 发现 ${problems.length} 处不一致：\n`)
for (const p of problems) console.log(`  · ${p}`)
console.log('\n⚠️ 本脚本**只报不改** —— 自动改写台账会把错误固化进权威文档，请人工判断后手改。')
process.exit(EXIT_MISMATCH)
