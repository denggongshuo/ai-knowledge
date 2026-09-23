/**
 * 跨轮残留水位（`T-O30` · `S9-M12-A`）—— 「本轮新增」与「历史存量」的分界。
 *
 * <h2>它要解决的那件事</h2>
 * <p>{@code findKbProbeResidue} 的 {@code orphanTotal}（无主残留）**刻意不计入**
 * {@code kbResidueClean} —— 理由是真的：它跨轮、跨来源，"跑完 `mvn test` 再跑 E2E"
 * 会把 Java 侧的历史残留算到 E2E 头上，变成假红。
 *
 * <p>但那个取舍的代价被长期低估了：**每一轮的收尾都照样全绿**，
 * 而那个数从 7 涨到 25（`S7-M06-F` 全程看在眼里）。
 * 一个「一直在报、从不判红」的数字**等于没有** —— 它既不会让任何人停下来看，
 * 也不会让任何一轮失败。
 *
 * <h2>水位线的做法</h2>
 * <p>每轮结束把"此刻的无主残留总数"记进一个**本机文件**（不入库：它是运行期状态，
 * 换一台机器没有意义，进 git 反而会让每次拉代码都带来一次假红）。
 * 下一轮开工时：
 * <ul>
 *   <li>{@code orphanTotal > 水位} ⇒ **本轮（或上几轮没跑到收尾的那几轮）新增了孤儿**
 *       ⇒ 这是**信号**，判红；</li>
 *   <li>{@code orphanTotal <= 水位} ⇒ 存量没涨（甚至被兜底清掉了一些）⇒ 不判红，
 *       但**照实报出存量**，让"存量是多少"始终看得见。</li>
 * </ul>
 *
 * <h2>🔴 它的射程边界（必须说清楚，否则会被误读成"残留已经清了"）</h2>
 * <ul>
 *   <li>它**不清理任何东西** —— 存量仍然躺在库里，只是不再重复判红。
 *       清理是 {@code sweepOrphanKbRows} / {@code sweepOrphanPartitions} 那些兜底的事。</li>
 *   <li>水位文件**丢了/被删了** ⇒ 退化成"存量也判红"（保守方向）：
 *       报一次红，然后把新水位记下来。**不静默当 0** ——
 *       "文件没了所以基线是 0"会把存量整批当成新增，那正是最难查的假红。</li>
 *   <li>水位**只会下降**到实测值（存量被兜底清掉时），不会因为某轮变少就把基线冻在高位 ——
 *       否则"清掉了 10 个、又新造了 10 个"会看起来完全正常。</li>
 * </ul>
 */

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const REPO = resolve(HERE, '..', '..')

/** 水位文件。⚠️ **不入库**（见文件头注）—— 路径在 `.gitignore` 的 `temp/` 下。 */
export const WATERMARK_FILE = join(REPO, 'temp', 'residue-watermark.json')

/**
 * 读水位。
 *
 * @returns {{orphanTotal: number, at: string|null, source: string}} `source` 说明这个数是哪来的
 *          （`file` = 上一轮记的 / `absent` = 没有基线 ⇒ 调用方按"存量也判红"处理）
 */
export function readWatermark() {
  if (!existsSync(WATERMARK_FILE)) {
    return { orphanTotal: 0, at: null, source: 'absent' }
  }
  try {
    const raw = JSON.parse(readFileSync(WATERMARK_FILE, 'utf8'))
    const n = Number(raw?.orphanTotal)
    if (!Number.isFinite(n) || n < 0) {
      return { orphanTotal: 0, at: null, source: 'corrupt' }
    }
    return { orphanTotal: n, at: raw?.at ?? null, source: 'file' }
  } catch {
    // 坏 JSON 与"文件不存在"**分开报**：前者说明有人动过它，后者只是第一次跑
    return { orphanTotal: 0, at: null, source: 'corrupt' }
  }
}

/** 记水位（只往下走：实测值更低时覆盖，更高时不动 —— 见文件头注第三条）。 */
export function writeWatermark(orphanTotal, at = new Date().toISOString()) {
  const current = readWatermark()
  if (current.source === 'file' && orphanTotal > current.orphanTotal) {
    // 存量涨了**不更新基线** —— 那正是要被判红的那件事，把基线抬上去等于把红抹掉
    return current.orphanTotal
  }
  try {
    mkdirSync(dirname(WATERMARK_FILE), { recursive: true })
    writeFileSync(WATERMARK_FILE, `${JSON.stringify({ orphanTotal, at }, null, 2)}\n`, 'utf8')
  } catch {
    // 写不进去不该让脚本失败（它是**观测设施**，不是被测对象）
  }
  return orphanTotal
}

/**
 * 把"存量 / 本轮新增"算出来。
 *
 * @param orphanTotal `findKbProbeResidue` 的数
 * @param watermark   {@link readWatermark} 的结果
 * @returns {{total:number, baseline:number, added:number, firstRun:boolean, note:string}}
 */
export function deltaOf(orphanTotal, watermark) {
  if (watermark.source !== 'file') {
    return {
      total: orphanTotal,
      baseline: 0,
      added: orphanTotal,
      firstRun: true,
      // ⚠️ 这里**故意让 added = total**（保守方向）：没有基线时存量也判红一次。
      //    反过来（当 0、不判红）会让"水位文件丢了"变成一条静默的免检通道。
      note: watermark.source === 'corrupt'
        ? '水位文件损坏 ⇒ 按"无基线"处理（存量也判红一次，随后重建基线）'
        : '首次运行（无水位文件）⇒ 存量也判红一次，随后建立基线',
    }
  }
  const added = Math.max(0, orphanTotal - watermark.orphanTotal)
  return {
    total: orphanTotal,
    baseline: watermark.orphanTotal,
    added,
    firstRun: false,
    note: added > 0
      ? `🔴 本轮新增 ${added} 个无主残留（基线 ${watermark.orphanTotal}）—— 这是**信号**`
      : `存量 ${orphanTotal}（基线 ${watermark.orphanTotal}）—— 没涨，不判红`,
  }
}
