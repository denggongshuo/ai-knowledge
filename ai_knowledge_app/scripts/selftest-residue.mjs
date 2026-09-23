/**
 * 残留自检的**自检**（`#136` 的负向锚）—— `S5` 测试可靠性修复批。
 *
 * <h3>它要证明什么</h3>
 * <p>旧版 `findKbProbeResidue` 按 `join sys_kb k on k.id = t.kb_id where k.code=?` 数残留，
 * 而 `sys_kb` 那一行**被同一轮清理删掉** ⇒ 那个 join **永远不命中** ⇒ **恒返回 0**。
 * 实测反例：自检报「库行=0 文档=0 分片=0 …」的同时，`sys_async_task` 里躺着 **634 行**。
 *
 * <p>所以这个脚本**不只看"现在数得出来"**，它把**旧口径与新区别并排跑一次**：
 * 同一个孤儿行，旧口径数出 **0**（假绿），新口径数出 **≥1**。
 * 「闸说干净」与「真的干净」是两件事 —— 这一行输出就是它们的分界。
 *
 * <h3>为什么是一个独立脚本而不是塞进某个 E2E</h3>
 * <p>因为它是**元判据**：验的是"闸还灵不灵"，而不是"业务对不对"。
 * 塞进业务脚本里，一旦那个脚本被跳过，闸失灵就再也没人发现。
 *
 * 用法：`node scripts/selftest-residue.mjs`（退出码 0 = 闸灵）
 */
import {
  mysqlCount,
  mysqlScalar,
  findKbProbeResidue,
  teardownKbProbe,
  describeKbResidue,
  kbResidueClean,
} from './lib/probe-teardown.mjs'
import {
  readWatermark,
  writeWatermark,
  deltaOf,
  WATERMARK_FILE,
} from './lib/residue-watermark.mjs'
import { existsSync, readFileSync, writeFileSync, unlinkSync } from 'node:fs'

const stamp = Date.now()
const CODE = `kb_zzselftest${stamp}`
/** 一个**不存在**的库 code —— 旧口径拿它 join，必然 0。 */
const GHOST_CODE = `kb_zzghost${stamp}`
/** 一个**不存在**的库 id —— 孤儿行的归属。 */
const GHOST_KB_ID = 999000000 + (stamp % 1000000)

const steps = []
function check(name, ok, detail) {
  steps.push({ name, ok })
  console.log(`  ${ok ? '✅' : '❌'} ${name}${detail ? '  —  ' + detail : ''}`)
}

function cleanup() {
  mysqlScalar(`delete from sys_async_task where kb_id in (${GHOST_KB_ID}, ${GHOST_KB_ID + 1});`)
  mysqlScalar(`delete from sys_kb where code like 'kb_zzselftest%';`)
  mysqlScalar(`delete from sys_kb where code like 'kb_zzghost%';`)
}

console.log(`\n残留自检的自检 —— 探针库 ${CODE}\n`)
cleanup() // 幂等：上一次异常退出留下的先清掉

try {
  // ---------- 场景 ①：清理侧真的删得掉（`#137`）----------
  //
  // 造一个**真实存在**的探针库 + 一行归属它的作业。
  // 旧口径的删除是 `delete t from sys_async_task t join sys_kb k ... where k.code=?` ——
  // 它在"删 kb 之前"执行，所以**删得掉**；真正瞎的是**自检**那一半。
  // 这里一并验两边：删得掉 + 数得出来。
  mysqlScalar(
    `insert into sys_kb (code, name, dept_id, created_at, updated_at) `
    + `values ('${CODE}', '自检探针库', 1, now(), now());`,
  )
  const kbId = Number(mysqlScalar(`select id from sys_kb where code='${CODE}';`))
  check('① 造出探针库', Number.isFinite(kbId) && kbId > 0, `kbId=${kbId}`)

  mysqlScalar(
    `insert into sys_async_task (task_id, type, status, kb_id, created_at, updated_at) `
    + `values ('zzselftest${stamp}', 'doc-parse', 'DONE', ${kbId}, now(), now());`,
  )
  const byIds = await findKbProbeResidue(CODE, { ids: [kbId] })
  check('① 残留自检按 ids 数得出来', byIds.sql.sys_async_task === 1,
    `sys_async_task=${byIds.sql.sys_async_task}（idsUsed=${byIds.idsUsed}）`)

  const torn = await teardownKbProbe(CODE)
  check('① 清理把它删掉了（独立观测点）', torn.residue.sql.sys_async_task === 0,
    describeKbResidue(torn.residue))
  check('① 清理后判据为"干净"', kbResidueClean(torn.residue))

  // ---------- 场景 ②：**闸是不是瞎的**（`#136` 的核心）----------
  //
  // 造一行"无主残留"：归属列指向一个**根本不存在的** kb。
  // 这正是线上真实出现过的形状（634 行 async_task 的 kb 全被删了）。
  mysqlScalar(
    `insert into sys_async_task (task_id, type, status, kb_id, created_at, updated_at) `
    + `values ('zzghost${stamp}', 'doc-parse', 'DONE', ${GHOST_KB_ID}, now(), now());`,
  )

  // ⚠️ 这一行就是**负向锚**：旧口径（按一个不存在的 code 去 join sys_kb）数出 0，
  //    而那一行明明就在库里。修之前，**所有**归属类的计数都是这个下场。
  const oldStyle = mysqlCount(
    `select count(*) from sys_async_task a join sys_kb k on k.id = a.kb_id where k.code='${GHOST_CODE}';`,
  )
  check('② 负向锚：旧口径数不出来（0 = 假绿）', oldStyle === 0, `旧口径=${oldStyle}`)

  const ghost = await findKbProbeResidue(GHOST_CODE, { ids: [] })
  check('② 新口径：无主残留数得出来', ghost.orphanTotal >= 1,
    `无主残留=${ghost.orphanTotal}（sys_async_task=${ghost.orphans.sys_async_task}）`)

  // ⚠️ `ghost.orphanDelta` 这时**必然**是 `added >= 1`（上一轮收尾把基线记成了 0，
  //    而这一轮刚插了一行孤儿）—— 也就是说下面那条"存量不计"必须**显式构造** added=0，
  //    不能直接用 `ghost.orphanDelta`。这不是绕路：它正是"闸要先证伪自己"那条纪律，
  //    直接用现场值会让两条断言里的一条永远测不到。

  // 🔴 `T-O30` 把这条判据拆成了两半 —— 两半都要断言，只测一半抓不住"恒红的实现"：
  //    ① **存量**（在基线以内）不计 ⇒ 不判红（跨轮的老问题不该由这一轮背）；
  //    ② **新增**计 ⇒ 判红（这一轮造的就是这一轮的问题）。
  //    旧版只有 ① 的语义，于是它"报了 25 次而每轮照样全绿"。
  const noDelta = { ...ghost, milvusVectors: 0, milvusPartitions: [], minioStillThere: [] }
  check('② 存量（在基线以内）**不计**入 kbResidueClean',
    kbResidueClean({ ...noDelta, orphanDelta: { ...ghost.orphanDelta, added: 0 } }) === true,
    `sqlTotal=${ghost.sqlTotal} orphanTotal=${ghost.orphanTotal} added=0`)
  check('② 新增（超基线）**计入** kbResidueClean（`T-O30` 新增的那一半）',
    kbResidueClean(noDelta) === false,
    `orphanTotal=${ghost.orphanTotal} added=${ghost.orphanDelta.added}`)

  // ---------- 场景 ③：**跨轮水位**（`T-O30` · `S9-M12-A`）----------
  //
  // 这里要证的是那条**分界**：存量不再重复判红、新增一定判红。
  // 判据 10 的全部内容都在这三格里。
  //
  // ⚠️ 水位文件是**本机状态**，所以本场景先把它**备份再恢复**：
  //    自检脚本不许改变"这个仓库当前的残留基线"（那会让它自己的运行成为别人假红的来源）。
  const savedWatermark = existsSync(WATERMARK_FILE) ? readFileSync(WATERMARK_FILE, 'utf8') : null
  try {
    const before = await findKbProbeResidue(GHOST_CODE, { ids: [] })

    // ③-① **无基线** ⇒ 存量也判红一次（保守方向：文件丢了不许变成免检通道）
    if (existsSync(WATERMARK_FILE)) unlinkSync(WATERMARK_FILE)
    const noBaseline = deltaOf(before.orphanTotal, readWatermark())
    check('③ 无水位文件 ⇒ 存量也判红一次（保守方向，不静默当 0）',
      noBaseline.firstRun && noBaseline.added === before.orphanTotal,
      `added=${noBaseline.added} total=${noBaseline.total}（${noBaseline.note}）`)

    // ③-② 把当前存量记成基线 ⇒ **同样的数不再判红**
    writeWatermark(before.orphanTotal)
    const steady = deltaOf(before.orphanTotal, readWatermark())
    check('③ 存量计入基线 ⇒ 同样的数**不判红**（这正是"报了 25 次没人看"的修法）',
      steady.added === 0 && !steady.firstRun,
      `added=${steady.added} total=${steady.total} baseline=${steady.baseline}`)

    // ③-③ **再新造一个孤儿** ⇒ 必须判红，且只报新增的那 1 个
    mysqlScalar(
      `insert into sys_async_task (task_id, type, status, kb_id, created_at, updated_at) `
      + `values ('zzghost2${stamp}', 'doc-parse', 'DONE', ${GHOST_KB_ID + 1}, now(), now());`,
    )
    const grown = await findKbProbeResidue(GHOST_CODE, { ids: [] })
    const delta = deltaOf(grown.orphanTotal, readWatermark())
    check('③ 新增 1 个孤儿 ⇒ **必判红**，且只报新增的那个（不是把存量一起报）',
      delta.added === 1 && grown.orphanTotal === before.orphanTotal + 1,
      `added=${delta.added} total=${delta.total} baseline=${delta.baseline}`)

    // ③-④ 存量**涨了**时不许抬高基线 —— 否则那条红会被它自己抹掉
    const beforeRaise = readWatermark().orphanTotal
    writeWatermark(beforeRaise + 5)
    check('③ 存量涨了 ⇒ **不抬高基线**（抬高等于把刚判出来的红抹掉）',
      readWatermark().orphanTotal === beforeRaise,
      `基线仍=${readWatermark().orphanTotal}（试写 ${beforeRaise + 5}）`)

    // ③-⑤ 存量**降了**（兜底清掉了一些）⇒ 基线跟着降。
    //      ⚠️ 这一条必须**先手工把基线抬到实测值之上**才验得到 ——
    //      否则 `writeWatermark` 的"只降不升"会让它恒等于当前值（一个恒真的假判据）。
    mysqlScalar(
      `insert into sys_async_task (task_id, type, status, kb_id, created_at, updated_at) `
      + `values ('zzghost3${stamp}', 'doc-parse', 'DONE', ${GHOST_KB_ID + 2}, now(), now());`,
    )
    const high = (await findKbProbeResidue(GHOST_CODE, { ids: [] })).orphanTotal
    writeFileSync(WATERMARK_FILE, `${JSON.stringify({ orphanTotal: high + 10, at: null })}\n`, 'utf8')
    mysqlScalar(`delete from sys_async_task where kb_id = ${GHOST_KB_ID + 2};`)
    const nowLower = (await findKbProbeResidue(GHOST_CODE, { ids: [] })).orphanTotal
    const lowered = writeWatermark(nowLower)
    check('③ 存量降了 ⇒ 基线跟着降（否则"清掉又新造"会看起来正常）',
      lowered === nowLower && readWatermark().orphanTotal === nowLower,
      `新基线=${lowered}（原基线=${high + 10} · 实测=${nowLower}）`)
  } finally {
    if (savedWatermark === null) {
      if (existsSync(WATERMARK_FILE)) unlinkSync(WATERMARK_FILE)
    } else {
      writeFileSync(WATERMARK_FILE, savedWatermark, 'utf8')
    }
    mysqlScalar(`delete from sys_async_task where kb_id in `
      + `(${GHOST_KB_ID}, ${GHOST_KB_ID + 1}, ${GHOST_KB_ID + 2});`)
  }
} finally {
  cleanup()
}

// ---------- 收尾核对：自检自己不许留痕 ----------
const left = mysqlCount(`select count(*) from sys_kb where code like 'kb_zzselftest%' or code like 'kb_zzghost%';`)
  + mysqlCount(`select count(*) from sys_async_task where kb_id in (${GHOST_KB_ID}, ${GHOST_KB_ID + 1});`)
check('自检自身不留痕', left === 0, `剩余=${left}`)

const failed = steps.filter((s) => !s.ok).length
console.log(`\n合计 ${steps.length} 项，通过 ${steps.length - failed}，失败 ${failed}\n`)
process.exit(failed ? 1 : 0)
