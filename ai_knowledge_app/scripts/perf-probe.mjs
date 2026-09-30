#!/usr/bin/env node
/**
 * 性能采样探针（`T-D04` / 门禁 `G9-5`）
 *
 * 用法：
 *   node scripts/perf-probe.mjs --base=http://127.0.0.1:8081 --account=admin --password=Admin@123 [--samples=20]
 *
 * 采三项（第 4 项"首字"**本批挂起**，见计划 §3.3：口径未定 + 根因是模型选型）：
 *   ① 检索侧 P95   —— 取 **SSE `retrieval` 帧的 `totalMs`**（= `RetrievalTrace#totalMillis()`，**只到检索为止**）
 *   ② 列表接口 P95 —— 真 HTTP 重复采样 `GET /api/kb`
 *   ③ 单篇索引耗时 —— 上传→READY 的墙钟（由调用方或用例单独测，本脚本只提供 HTTP 采样骨架）
 *
 * 🔴 口径与边界：
 *   - 采样的是**本机环境**（宿主机 mvn + Docker Desktop），**不代表生产基线**
 *   - P95 用 nearest-rank：升序后取 ceil(0.95*N)-1
 *   - 首字**不在此脚本内判**（挂起项）
 */
import { performance } from 'node:perf_hooks'

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const m = /^--([^=]+)=(.*)$/.exec(a)
    return m ? [m[1], m[2]] : [a.replace(/^--/, ''), true]
  })
)
const BASE = (args.base || 'http://127.0.0.1:8081').replace(/\/$/, '')
const ACCOUNT = args.account || 'admin'
const PASSWORD = args.password || 'Admin@123'
const SAMPLES = Number(args.samples || 20)
const WARMUP = Number(args.warmup || 3)

function p95(values) {
  if (!values.length) return null
  const s = [...values].sort((a, b) => a - b)
  const idx = Math.max(0, Math.ceil(0.95 * s.length) - 1)
  return s[idx]
}
function stats(values) {
  if (!values.length) return { n: 0 }
  const s = [...values].sort((a, b) => a - b)
  const sum = s.reduce((a, b) => a + b, 0)
  return {
    n: s.length,
    min: s[0],
    p50: s[Math.max(0, Math.ceil(0.5 * s.length) - 1)],
    p95: p95(s),
    max: s[s.length - 1],
    avg: Math.round(sum / s.length),
  }
}

async function login() {
  const r = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ account: ACCOUNT, password: PASSWORD }),
  })
  const j = await r.json()
  if (j.code !== 0) throw new Error(`登录失败 code=${j.code} ${j.message}`)
  return j.data.accessToken
}

async function timedFetch(url, token) {
  const t0 = performance.now()
  const r = await fetch(url, { headers: token ? { Authorization: `Bearer ${token}` } : {} })
  const body = await r.text()
  const ms = performance.now() - t0
  return { ms, status: r.status, body }
}

async function main() {
  console.log(`# perf-probe  base=${BASE}  samples=${SAMPLES} (warmup=${WARMUP})`)
  const token = await login()
  console.log(`# 登录成功（token len=${token.length}）`)

  // ---------- ① 列表接口 P95（GET /api/kb）----------
  for (let i = 0; i < WARMUP; i++) await timedFetch(`${BASE}/api/kb?page=1&size=20`, token)
  const listMs = []
  let listOk = 0
  for (let i = 0; i < SAMPLES; i++) {
    const r = await timedFetch(`${BASE}/api/kb?page=1&size=20`, token)
    if (r.status === 200) { listMs.push(r.ms); listOk++ }
  }
  const listStat = stats(listMs)
  console.log(`\n① 列表接口 GET /api/kb  (${listOk}/${SAMPLES} 成功)`)
  console.log(`   ${JSON.stringify(listStat)}   门禁 <500ms  ⇒ ${listStat.p95 < 500 ? 'PASS' : 'FAIL'}`)

  // ---------- ② 检索侧 P95（从运营统计读 trace）----------
  // 说明：`GET /api/ops/retrieval/stats` 返回聚合，**单条 trace 的 total_millis 要另取**。
  // 这里先探接口形状，把原始响应打出来供人工确认字段名。
  console.log(`\n② 检索侧（需要 trace 级数据）`)
  const probe = await timedFetch(`${BASE}/api/ops/retrieval/stats`, token)
  console.log(`   GET /api/ops/retrieval/stats → HTTP ${probe.status}`)
  console.log(`   body.head: ${probe.body.slice(0, 400)}`)

console.log('\n# 检索侧 P95 口径：SSE retrieval 帧的 totalMs（只到检索为止）；DB 的 sys_retrieval_trace.total_millis 是【检索+生成】的端到端，勿混用')
  console.log(`# 首字（判据 7）本批挂起 —— 不在本脚本判定`)
}

main().catch((e) => { console.error('# ERROR', e.message); process.exit(1) })
