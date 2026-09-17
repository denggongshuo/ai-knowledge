/**
 * **覆盖率闸的自测**（`S4-D2` 判据 1 / 2）—— 证伪这个闸不是恒真守卫。
 *
 * <p>本项目已经吃过三次「守卫恒真」的亏（`describeIndex` 忽略 `fieldName`、
 * `get_stats.rowCount` 只统计已 flush 的数据、裸 `dropPartition` 的返回值没人看）。
 * 所以**新加一道闸，必须先证明它在该报错时真的会报错** —— 否则它只是个装饰。
 *
 * <h3>用法</h3>
 * <pre>
 *   # 判据 1：造一张未登记的表 → 闸必须抛错并点名它
 *   docker exec kb-mysql mysql ... -e "create table tmp_probe_canary(kb_id bigint);"
 *   node scripts/lib/selftest-coverage.mjs        # 期望：退出码 1，消息含 tmp_probe_canary
 *
 *   # 判据 2：删掉 → 闸必须放行
 *   docker exec kb-mysql mysql ... -e "drop table tmp_probe_canary;"
 *   node scripts/lib/selftest-coverage.mjs        # 期望：退出码 0
 * </pre>
 */
import { assertArtifactCoverage } from './probe-teardown.mjs'

try {
  const r = assertArtifactCoverage()
  console.log(`✓ 覆盖率闸通过：扫描 ${r.scanned} 张带归属列的表 · 已登记 ${r.registered} 张 · 显式豁免 ${r.exempt} 张`)
  console.log(`  扫描到的表：${r.tables.join(' · ')}`)
  process.exit(0)
} catch (e) {
  console.error(`✗ ${e.message}`)
  process.exit(1)
}
