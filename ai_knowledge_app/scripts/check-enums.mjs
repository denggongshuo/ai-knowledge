import { existsSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { VALUE_LEDGERS } from './lib/value-ledgers.mjs'

/**
 * 「需求 ↔ 后端枚举 ↔ mock 取值」的**三方对账**（`T-O20` · 由来 `#164`）。
 *
 * <h3>为什么它不是 `audit-api.mjs` 的一部分</h3>
 * <p>那个脚本对的是**接口路径**（① 前端调用有 mock 吗 / ② mock 有文档吗 / ⑥ 差异项定性）。
 * 取值**不在它的输入里** —— 于是 `#164` 那种漂移（需求与后端一致、只有 mock 独自不同）
 * 结构上不可能被它发现。这是「闸覆盖了路径、没覆盖取值」，与 `T-O15`
 * （覆盖率闸只认精确列名）同族：**闸的射程与它的声明不一致**。
 *
 * <h3>退出码（承既有约定）</h3>
 * <ul>
 *   <li>{@code 0} 三方逐字一致；</li>
 *   <li>{@code 1} 有取值差异（**会点名是哪一方、哪个值**）；</li>
 *   <li>{@code 2} **前置不满足** —— 文件不存在，或某一方**解析出空集**
 *       （空集不能当成"一致"：那会让这个脚本在文档改名之后**静默变绿**）。</li>
 * </ul>
 *
 * <h3>只报不改</h3>
 * <p>与 `check-ledgers.mjs` 同一条纪律：自动改写会把错误固化进权威文档。
 *
 * <p>用法：`node scripts/check-enums.mjs`（在 `ai_knowledge_app/` 下跑）
 */
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')

const problems = []
const preconditions = []

for (const ledger of VALUE_LEDGERS) {
  console.log(`\n### ${ledger.id} —— ${ledger.what}`)
  const sets = []
  for (const source of ledger.sources) {
    const path = resolve(root, source.file)
    if (!existsSync(path)) {
      preconditions.push(`${ledger.id} / ${source.name}：文件不存在 → ${source.file}`)
      continue
    }
    const labels = source.extract(readFileSync(path, 'utf8'))
    if (labels.length !== source.expect) {
      // 🔴 空集**不是**"一致"：文档改一句措辞就再也解析不到了，
      // 而那时这个闸会**静默变绿** —— 那正是它要防的形状。
      // ⚠️ **数量少了同样不是"不一致"**：那是提取式过期（证伪时实测踩到过：
      // 枚举里一个值的双引号变了写法 ⇒ 少抽到一个 ⇒ 闸报的却是
      // "需求文档多了一个值"，把一个提取式的毛病说成了别人文档的毛病）。
      // ⇒ 数量与登记的 `expect` 不符一律退 2，**不拿去和别方比**。
      preconditions.push(
        `${ledger.id} / ${source.name}：解析出 ${labels.length} 个取值，登记的冻结数量是 `
          + `${source.expect} —— 提取式过期了，还是真的增删了取值？看 ${source.file}`,
      )
      continue
    }
    console.log(`  ${source.name.padEnd(6)}（${labels.length}）: ${labels.join(' / ')}`)
    sets.push({ source, labels })
  }
  if (sets.length !== ledger.sources.length) {
    continue
  }

  const base = sets[0]
  for (const other of sets.slice(1)) {
    const missing = base.labels.filter((l) => !other.labels.includes(l))
    const extra = other.labels.filter((l) => !base.labels.includes(l))
    if (missing.length || extra.length) {
      problems.push(
        `${ledger.id}：${base.source.name} 与 ${other.source.name} 不一致 —— `
          + `${other.source.name} 少了 [${missing.join(' / ')}]、多了 [${extra.join(' / ')}]`,
      )
    }
  }
}

console.log('')
if (preconditions.length) {
  console.error('⛔ 前置不满足（退出码 2）——**不是"通过"**：')
  for (const p of preconditions) {
    console.error(`  · ${p}`)
  }
  process.exit(2)
}
if (problems.length) {
  console.error(`✗ 发现 ${problems.length} 处取值不一致（退出码 1）：`)
  for (const p of problems) {
    console.error(`  · ${p}`)
  }
  console.error('\n⚠️ 只报不改 —— 先判断**哪一方是口径来源**（本仓的口径来源是需求文档，'
    + '不是 mock），再改错的那一方。')
  process.exit(1)
}
console.log(`✓ ${VALUE_LEDGERS.length} 组闭集三方一致`)
