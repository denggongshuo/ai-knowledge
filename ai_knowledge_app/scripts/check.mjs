#!/usr/bin/env node
/**
 * 一条命令完成质量校验：清缓存 → 类型检查 → 生产构建
 *
 * 用法： node scripts/check.mjs      （推荐，跨平台、不经过 npm 包装器）
 *       npm run check                （等价，供本地终端使用）
 *
 * 为什么不用 `npm run build` / `npx vue-tsc`：
 *   - npm/npx 会先启动 .cmd 包装器；在受限沙箱环境中该包装器可能被安全策略拦截
 *   - 因此统一用 `node node_modules/.../xxx.js` 直接调用工具入口
 *
 * 为什么必须先清 node_modules/.tmp：
 *   - 项目是 TypeScript project references 结构（tsconfig.json 的 files 为空）
 *   - 裸 `vue-tsc --noEmit` 在该结构下恒返回 0（假绿），必须用 `--build`
 *   - 且 tsbuildinfo 增量缓存会让「已改动的文件」被跳过，清掉才能拿到真实结果
 */
import { rmSync, existsSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')

function run(label, scriptRelPath, args) {
  const scriptPath = resolve(ROOT, scriptRelPath)
  if (!existsSync(scriptPath)) {
    console.error(`✗ ${label}：找不到入口 ${scriptRelPath}（是否已安装依赖？）`)
    process.exit(1)
  }
  process.stdout.write(`\n▶ ${label}\n`)
  const res = spawnSync(process.execPath, [scriptPath, ...args], { cwd: ROOT, stdio: 'inherit' })
  if (res.status !== 0) {
    console.error(`\n✗ ${label} 未通过（exit ${res.status ?? 'null'}）`)
    process.exit(res.status ?? 1)
  }
  process.stdout.write(`✓ ${label} 通过\n`)
}

// 1) 清 tsbuildinfo 缓存
const tmpDir = resolve(ROOT, 'node_modules/.tmp')
if (existsSync(tmpDir)) {
  rmSync(tmpDir, { recursive: true, force: true })
  process.stdout.write('已清理 node_modules/.tmp（避免 tsbuildinfo 增量缓存造成假绿）\n')
}

// 2) 类型检查（必须 --build）
run('type-check · vue-tsc --build', 'node_modules/vue-tsc/bin/vue-tsc.js', ['--build'])

// 3) 生产构建
run('build · vite build', 'node_modules/vite/bin/vite.js', ['build'])

process.stdout.write('\n全部校验通过 ✓\n')
