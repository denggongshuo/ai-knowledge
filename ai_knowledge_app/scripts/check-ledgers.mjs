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
import { execFileSync } from 'node:child_process'
import { basename, dirname, relative, resolve } from 'node:path'
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
   * 🔴 **两个"豁免口"，都不许当消音器用**（第二个是 `T-O18` 2026-09-20 加的）：
   * <ol>
   *   <li>{@code 门禁号订正}（整份文件豁免）—— 见下；</li>
   *   <li>{@code 门禁号旁注：G3-3 / G3-8}（**点名**豁免）—— 见 {@link curated}。</li>
   * </ol>
   * 为什么需要第二个：L1 的判法只能看出"表述与排期那一行的文字重叠为零"，
   * 而**一个门禁常常有几个侧面**（口径 / 运行时 / 前半 / 后半）。那些行不是错号，
   * 逐条判定为"接受"之后就**没有地方记**，于是每批开工都再报一遍 ——
   * 而一条永远在报的闸会被忽略，被忽略的闸等于没有（本仓已定过这条）。
   *
   * <hr>
   * 🔴 **已声明订正的文件**（第一个口子）：`S8-M10-A`（2026-09-20）起支持。
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

  /**
   * 🔴 **已点名的「同名不同义」判定**（`T-O18` · 2026-09-20 起）。
   *
   * 与上一个口子的区别：`门禁号订正` 是**整份文件**豁免，而这个要求**把门禁号点出来**，
   * 且只对该文件里**那几个号**生效。
   *
   * 由来（`2026-09-16-S3-D.md` 的三条）：一个门禁常常有**几个侧面**
   * （`G3-3` = 前半 `needReindex` / 字段集同源 / 后半「未重建前检索不变」；
   * `G3-8` = 口径侧 / 运行时侧面），而本脚本的判法只能看出"表述与排期的文字重叠为零"。
   * 那三条**不是错号**（同一门禁的不同侧面），判定为**接受**；
   * 但没有记录判定的地方 ⇒ 它们每批都会再报一遍，
   * 而**一条永远在报的闸会被忽略，被忽略的闸等于没有**（上一批刚定过这条）。
   *
   * ⚠️ 它仍然不是消音器：① 必须**点名**门禁号（不点名 = 不生效）；
   * ② 只降级「同名不同义」这一类（引用了不存在的号**照报**）；
   * ③ 只在该文件内生效；④ 声明行会原样打印在输出里，加一行来消音是看得见的。
   */
  const curated = new Set(
    [...text.matchAll(/门禁号旁注[：:]?[^\n]*/g)]
      .flatMap((m) => [...m[0].matchAll(/G\d+-\d+/g)].map((g) => g[0])),
  )
  let curatedMismatch = 0

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
      if (curated.has(id)) {
        curatedMismatch++
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
  if (curatedMismatch > 0) {
    notes.push(`L1 ${file}：${curatedMismatch} 处同名不同义落在**已点名判定**的号上`
      + `（${[...curated].join(' / ')}）—— 按"同一门禁的另一侧面，已判定接受"处理。`
      + '⚠️ 声明行本身请留在文件里，别删')
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
// L4 · 回填清单与索引状态列（`S7-M06-B` 立）
//
// 由来是**同一个形状的两次实测**（都在 `S7-M06-A` 的收尾阶段）：
//   · 计划的「本批同步的文档回填」清单里把 `00-开工速览.md` 勾成了 `[x]`，
//     而那份文件在本批的 12 个提交里**一次都没出现** —— 于是下一个开工的人
//     读到的是**上一批的定位**，而清单上写着"已完成"。**勾选框本身在撒谎。**
//   · `plan/README.md` 的索引行状态列停在 `🟡 进行中`（那行是批次**还在跑**时应急补的），
//     而计划文件自己第 1 行写着 `✅ 已完成`；L2 只查"在不在索引里"，**不查状态**。
//
// 判据：**清单里打勾的，必须真的被这批的提交改过；计划说自己完成，索引就得说完成。**
// ---------------------------------------------------------------------------

function gitLines(args) {
  try {
    // 🔴 `-c core.quotepath=false` 是**必须的**：少了它，非 ASCII 路径会被 git 转义成
    //    `"docs/\350\277\233\345\272\246/..."` 再吐出来 ⇒ 与清单里的中文路径**一个都对不上**
    //    ⇒ 闸会**大面积误报**（本脚本第一版把每一份计划的每一个勾选项都报成了漏做）。
    //    ⚠️ 它误报的方向恰好是"说别人没做"，比漏报更容易被当成真的 —— 所以这条闸自己也要先证伪。
    return execFileSync('git', ['-c', 'core.quotepath=false', ...args], { cwd: REPO, encoding: 'utf8' })
      .split('\n').map((s) => s.trim()).filter(Boolean)
  } catch {
    return null
  }
}

/** git 是否跟踪该路径（相对仓库根，正斜杠）。不跟踪的（gitignore）没法做 git 对账。 */
function gitTracks(rel) {
  try {
    execFileSync('git', ['-c', 'core.quotepath=false', 'ls-files', '--error-unmatch', rel],
      { cwd: REPO, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })
    return true
  } catch {
    return false
  }
}

/** 本批改动过的全部文件（相对仓库根，正斜杠）。`null` = 取不到（**取不到 ≠ 没问题**）。 */
function filesTouchedBy(file) {
  const rel = relative(REPO, file).replace(/\\/g, '/')
  const commits = gitLines(['log', '--format=%H', '--', rel])
  if (!commits || commits.length === 0) return null
  const first = commits[commits.length - 1] // `git log` 新→旧
  const last = commits[0]
  const changed = new Set()
  // 立据那一次提交里的文件 + 之后到回填之间的文件
  for (const args of [['show', '--format=', '--name-only', first], ['log', '--format=', '--name-only', `${first}..${last}`]]) {
    const out = gitLines(args)
    if (!out) return null
    out.forEach((f) => changed.add(f))
  }
  return changed
}

/** `plan/README.md` 里每一行的文件名 → {status, date}。 */
function indexStatusOf(path) {
  const rows = new Map()
  const lines = readFileSync(path, 'utf8').split('\n')
  for (const line of lines) {
    if (!tableRow(line)) continue
    const cells = line.split('|').map((c) => c.trim())
    // `| 文件 | 描述 | 状态 | 日期 |` ⇒ split 后首尾各一个空串
    const m = /^`([^`]+\.md)`$/.exec(cells[1] ?? '')
    if (!m) continue
    rows.set(m[1], { status: cells[cells.length - 3] ?? '', date: cells[cells.length - 2] ?? '' })
  }
  return rows
}

const indexRows = existsSync(INDEX) ? indexStatusOf(INDEX) : new Map()
/** 回填清单里**不是路径**的条目（模式，如 `.workbuddy/memory/<日期>.md`）—— 报出来，不静默丢。 */
const skipped = []
/** 被「点名判定」放行的条目数（**报出来**，不静默）。 */
let adjudicatedCount = 0

for (const f of onDisk) {
  const path = resolve(PLAN_DIR, f)
  const text = readFileSync(path, 'utf8')
  const head = (text.split('\n')[0] ?? '').trim()
  const done = /状态：\s*✅/.test(head)
  const inProgress = /状态：\s*🟡/.test(head)

  // ---- L4b · 状态列与计划自述一致（**两个方向都查**）----
  const row = indexRows.get(f)
  if (!row) {
    // 漏登由 L2 报；这里不重复
  } else {
    const rowDone = /✅/.test(row.status)
    if (done && !rowDone) {
      problems.push(`L4b ${f}：计划第 1 行说**已完成**，而 \`plan/README.md\` 的状态列是「${row.status}」`
        + '（L2 只查"在不在索引里"，不查状态 —— 这一列此前没有任何机器盯着）')
    }
    if (inProgress && rowDone) {
      problems.push(`L4b ${f}：计划第 1 行说**进行中**，而索引状态列说「${row.status}」`)
    }
    if (done && rowDone && !/\d{4}-\d{2}-\d{2}/.test(row.date)) {
      problems.push(`L4b ${f}：索引说已完成，但**完成日期为空**（那一列是"什么时候交的"的唯一记录）`)
    }
  }

  // ---- L4a · 回填清单里勾了的，必须真的被这批改过 ----
  // 🔴 **点名判定口**（与 L1 的「门禁号旁注」同一机制、同一理由）：历史漏项是**事实**，
  //    改掉会毁证据；而**一条永远在叫的闸会被忽略**（本仓硬口径）。
  //    ⇒ 计划文件里写一行 `> L4a 已判定：\`<文件>\` —— <理由>`，**必须点名**逐个文件，
  //      只在该文件内生效。没点名的一律照报。
  const adjudicated = new Set()
  for (const line of text.split('\n')) {
    const m = /^\s*>\s*L4a\s*已判定[：:]\s*(.+)$/.exec(line)
    if (m) for (const t of m[1].matchAll(/`([^`]+\.md)`/g)) adjudicated.add(t[1].replace(/\\/g, '/'))
  }
  const checked = [...text.matchAll(/^\s*-\s*\[x\][^\n]*?`([^`]+\.md)`/gm)].map((m) => m[1])
  if (checked.length === 0) continue
  const touched = filesTouchedBy(path)
  if (touched === null) {
    console.error(`✗ 前置不满足：L4a 取不到 ${f} 的提交范围（git 不可用？）—— 取不到 ≠ 没问题`)
    process.exit(EXIT_PRECONDITION)
  }
  const norm = new Set([...touched].map((p) => p.replace(/\\/g, '/')))
  for (const raw of checked) {
    // 🔴 **路径写法要归一化**：清单里两种写法都有（`docs\进度\...` 与 `docs/进度/...`），
    //    而 git 只认正斜杠 ⇒ 不归一化的话反斜杠那批会被当成"不是路径"**静默跳过** ——
    //    闸于是漏掉一半输入，而它自己看着是绿的（这正是本仓那条"闸的输入为空"的形态）。
    const target = raw.replace(/\\/g, '/')
    if (adjudicated.has(target)) {
      adjudicatedCount++
      continue
    }
    // 清单里有些条目是**模式**而不是路径（`.workbuddy/memory/<日期>.md`）——
    // 对它们无从做 git 对账。判据是"仓库里到底有没有这个文件"，
    // 而不是"它看起来像不像路径"（后者只能靠猜）。
    // 🔴 还要**排掉 git 不跟踪的**：`.workbuddy/memory/*.md` 是 gitignore 的，
    //    它们在盘上存在、也**确实被每一批改过**，但 git 里一行记录都没有 ⇒
    //    不排掉就会把每一批都报成漏做（第一版正是如此）。判据只能是"git 跟不跟踪它"。
    if (!existsSync(resolve(REPO, target)) || !gitTracks(target)) {
      skipped.push(target)
      continue
    }
    const candidates = [...norm].filter((p) => p.endsWith(target))
    if (candidates.length === 0) {
      problems.push(`L4a ${f}：回填清单里把 \`${target}\` 勾成了 \`[x]\`，`
        + '但它**不在本批改动过的文件里** —— 勾选框说做了、git 说没做')
    }
  }
}
if (indexRows.size > 0) notes.push(`L4 索引状态列：读了 ${indexRows.size} 行 · 回填清单勾选项已逐条对 git`)
if (adjudicatedCount > 0) {
  notes.push(`L4a 有 ${adjudicatedCount} 条走了**点名判定**（计划文件里写了 \`> L4a 已判定\` 的声明行）`)
}
if (skipped.length > 0) {
  notes.push(`L4a 跳过 ${skipped.length} 条**不是路径**的清单项（模式而非文件）：`
    + `${[...new Set(skipped)].join(' · ')}`)
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
