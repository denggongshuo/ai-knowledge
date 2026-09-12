#!/usr/bin/env node
/**
 * 全站渲染自检（无头 Edge，逐页访问并按「每页精确断言」校验渲染结果）
 *
 * 用法： node scripts/render-check.mjs
 * 前置：
 *   1) dev server 已在 5173 运行
 *   （注入登录态用的 public/_selfcheck.html 由本脚本自动生成并在结束时清理）
 *
 * 说明：
 *   - 用 Node 直接驱动 msedge.exe，不经过 npm/.cmd 包装器，避免在受限沙箱里被 wsl.exe 拦截
 *   - 断言用「页面主体特征」（.page-title / .err-title / 认证卡 / 聊天页类名），
 *     而不是通用文案，否则会被侧边栏菜单文案误命中
 *   - 额外校验标签栏边界：布局壳内的页面必须有 .tags-view，壳外（认证页 / 错误页）
 *     必须没有 —— 后者是全屏页，混进标签栏说明 keep-alive 挂错了位置
 */
import { spawnSync } from 'node:child_process'
import { mkdirSync, writeFileSync, existsSync, rmSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const BASE = 'http://localhost:5173'
const PROFILE = 'F:/work/code/ai-knowledge/temp/edge-selfcheck'
const OUT_DIR = 'F:/work/code/ai-knowledge/temp/render-check'

/**
 * 路由清单：每页给一条精确断言（正则在整页 HTML 上匹配）
 * - public: true 表示无需登录态，直接访问
 * - expect: 必须出现的页面主体特征；缺失即判定渲染失败
 */
const ROUTES = [
  { path: '/login', public: true, expect: /class="auth-card"/, label: '登录页' },
  { path: '/register', public: true, expect: /class="auth-card"/, label: '注册页' },
  { path: '/forgot-password', public: true, expect: /class="auth-card"/, label: '忘记密码' },
  { path: '/chat', expect: /class="[a-z-]*chat[a-z-]*"/, label: '知识问答' },
  { path: '/notice', expect: /class="page-title"[^>]*>消息通知/, label: '消息通知' },
  { path: '/kb', expect: /class="page-title"[^>]*>知识库管理/, label: '知识库管理' },
  { path: '/kb/create', expect: /class="page-title"[^>]*>创建知识库/, label: '创建知识库' },
  { path: '/kb/1', expect: /class="page-title"[^>]*>产品技术文档库/, label: '知识库设置' },
  { path: '/kb/1/upload', expect: /class="page-title"[^>]*>文档上传/, label: '文档上传' },
  { path: '/doc/1', expect: /class="page-title"[^>]*>AI 知识库平台白皮书 V3/, label: '文档详情' },
  { path: '/doc/1/preview', expect: /class="page-title"[^>]*>AI 知识库平台白皮书 V3/, label: '文档预览' },
  { path: '/doc/download', expect: /class="page-title"[^>]*>文档下载/, label: '文档下载' },
  { path: '/recycle', expect: /class="page-title"[^>]*>回收站/, label: '回收站' },
  { path: '/ops/qa', expect: /class="page-title"[^>]*>问答运营/, label: '问答运营' },
  { path: '/ops/retrieval', expect: /class="page-title"[^>]*>检索质量分析/, label: '检索质量分析' },
  { path: '/ops/usage', expect: /class="page-title"[^>]*>使用统计/, label: '使用统计' },
  { path: '/profile', expect: /class="page-title"[^>]*>个人信息/, label: '个人信息' },
  { path: '/system/user', expect: /class="page-title"[^>]*>用户管理/, label: '用户管理' },
  { path: '/system/role', expect: /class="page-title"[^>]*>角色管理/, label: '角色管理' },
  { path: '/system/grant', expect: /class="page-title"[^>]*>授权管理/, label: '授权管理' },
  { path: '/system/model', expect: /class="page-title"[^>]*>模型配置/, label: '模型配置' },
  { path: '/system/audit', expect: /class="page-title"[^>]*>审计日志/, label: '审计日志' },
  { path: '/403', expect: /class="err-title"[^>]*>你没有访问该页面的权限/, label: '403 无权访问' },
  { path: '/500', expect: /class="err-title"[^>]*>服务端发生异常/, label: '500 服务异常' },
  { path: '/no-such-page', expect: /class="err-title"[^>]*>页面地址可能已失效/, label: '404 页面不存在' },
]

if (!existsSync(EDGE)) {
  console.error(`找不到 Edge：${EDGE}`)
  process.exit(1)
}
mkdirSync(OUT_DIR, { recursive: true })
// 开跑前清掉 profile：否则残留的登录态会让公开页（/login 等）被守卫重定向到 /chat
if (existsSync(PROFILE)) rmSync(PROFILE, { recursive: true, force: true })

/**
 * 注入页：临时写入 public/，跑完删除。
 * 不长期放进仓库，避免被 vite build 打进 dist 产物。
 */
const BOOTSTRAP = resolve(ROOT, 'public/_selfcheck.html')
const BOOTSTRAP_HTML = `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="UTF-8"><title>render-check bootstrap</title></head>
<body><script>
try { localStorage.setItem('ak_token','mock_selftest_token'); localStorage.setItem('ak_remember','0'); } catch (e) {}
var to = new URLSearchParams(location.search).get('to') || '/chat';
location.replace('/#' + to);
<\/script></body></html>
`
writeFileSync(BOOTSTRAP, BOOTSTRAP_HTML)

function dump(url, outFile) {
  const res = spawnSync(
    EDGE,
    [
      '--headless=new',
      '--disable-gpu',
      '--no-sandbox',
      '--disable-extensions',
      `--user-data-dir=${PROFILE}`,
      '--virtual-time-budget=15000',
      '--run-all-compositor-stages-before-draw',
      '--dump-dom',
      url,
    ],
    { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 },
  )
  const html = res.stdout ?? ''
  writeFileSync(outFile, html)
  return html
}

const pad = (s, n) => String(s).padEnd(n, ' ')
const rows = []
let failed = 0

/** 不在布局壳内的路由（全屏页），不应出现标签栏 */
const NO_SHELL = new Set(['/login', '/register', '/forgot-password', '/403', '/500', '/no-such-page'])

for (const r of ROUTES) {
  const url = r.public ? `${BASE}/#${r.path}` : `${BASE}/_selfcheck.html?to=${r.path}`
  const outFile = resolve(OUT_DIR, `${r.path.replace(/\//g, '_') || 'root'}.html`)
  const html = dump(url, outFile)

  const inShell = !NO_SHELL.has(r.path)
  const tagCount = (html.match(/class="tv-tab/g) ?? []).length

  let status = 'OK'
  if (!r.expect.test(html)) status = `FAIL(dom=${html.length}B)`
  // 受保护页不应被弹回登录页
  else if (!r.public && /class="auth-card"/.test(html)) status = 'REDIRECTED_TO_LOGIN'
  // 标签栏边界：壳内必须有且至少含 2 个常驻标签；壳外必须没有
  else if (inShell && tagCount < 2) status = `FAIL(标签栏缺常驻:${tagCount})`
  else if (!inShell && tagCount > 0) status = `FAIL(壳外混入标签栏:${tagCount})`

  if (status !== 'OK') failed++
  rows.push([r.path, r.label, inShell ? String(tagCount) : '—', status])
}

console.log(`${pad('ROUTE', 20)} ${pad('PAGE', 18)} ${pad('TAGS', 5)} STATUS`)
console.log('-'.repeat(76))
for (const [p, l, t, s] of rows) console.log(`${pad(p, 20)} ${pad(l, 18)} ${pad(t, 5)} ${s}`)
console.log('-'.repeat(76))
console.log(`合计 ${rows.length} 条路由，通过 ${rows.length - failed} 条，失败 ${failed} 条`)
console.log(`DOM dump 目录：${OUT_DIR}`)

if (existsSync(PROFILE)) rmSync(PROFILE, { recursive: true, force: true })
if (existsSync(BOOTSTRAP)) rmSync(BOOTSTRAP, { force: true })
process.exit(failed ? 1 : 0)
