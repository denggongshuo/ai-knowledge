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
  mysqlScalar(`delete from sys_async_task where kb_id = ${GHOST_KB_ID};`)
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

  check('② 无主残留**不进** kbResidueClean（它是跨轮的报警，不是本轮的判据）',
    kbResidueClean({ ...ghost, milvusVectors: 0, milvusPartitions: [], minioStillThere: [] }) === true,
    `sqlTotal=${ghost.sqlTotal} orphanTotal=${ghost.orphanTotal}`)
} finally {
  cleanup()
}

// ---------- 收尾核对：自检自己不许留痕 ----------
const left = mysqlCount(`select count(*) from sys_kb where code like 'kb_zzselftest%' or code like 'kb_zzghost%';`)
  + mysqlCount(`select count(*) from sys_async_task where kb_id = ${GHOST_KB_ID};`)
check('自检自身不留痕', left === 0, `剩余=${left}`)

const failed = steps.filter((s) => !s.ok).length
console.log(`\n合计 ${steps.length} 项，通过 ${steps.length - failed}，失败 ${failed}\n`)
process.exit(failed ? 1 : 0)
