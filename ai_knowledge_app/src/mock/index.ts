import type { ViteDevServer } from 'vite'
import type { IncomingMessage, ServerResponse } from 'node:http'
import {
  allUsers,
  mockRoles,
  mockKbs,
  mockDocs,
  THRESHOLDS,
  RETRIEVAL_COST,
  ROLE_CODES,
  ALL_PERMISSION_CODES,
  PERMISSION_TREE,
  DEPTS,
  DEPT_NAME,
  DATA_SCOPE_LABEL,
  USER_STATUS_LABEL,
  SPLIT_PRESETS,
  SECRET_LEVEL_LABEL,
  RETRIEVAL_MODE_LABEL,
  defaultKbConfig,
  kbDocsOf,
  mockParseIssues,
  PARSE_CONFIG_VIEW,
  docMetaOf,
  mockDocVersions,
  mockIndexTasks,
  mockChunkStat,
  mockChunks,
  mockParentChunk,
  mockToc,
  mockDownloadTasks,
  mockDownloadRecords,
  mockStorageQuota,
  allDocsAcross,
  mockQaOverview,
  mockMissQuestions,
  MISS_STATUS_LABEL,
  mockTopQuestions,
  mockFeedbackStats,
  mockRetrievalOverview,
  mockPipeline,
  mockScoreDistribution,
  mockRerankEffect,
  mockKbQuality,
  mockUsageOverview,
  mockTrend,
  mockDeptUsage,
  mockKbHeat,
  mockTopUsers,
  mockNotices,
  mockNoticeCounts,
  mockNoticeSettings,
  mockPendingItems,
  mockRecycleDocs,
  mockRecycleKbs,
  RECYCLE_KEEP_DAYS,
  mockChatModels,
  mockEmbeddingModel,
  mockRerankModels,
  mockVlModels,
  mockUsageByModel,
  mockVlImpact,
  mockChatSessions,
} from './data'
import type { RetrievalTrace, KbConfigForm, KnowledgeBase, ModelConfigItem, ModelType } from '../types/index.ts'

/**
 * Mock 中间件 —— Vite dev-server 插件
 * 拦截 /api 前缀请求，返回与《01-示例数据字典》对齐的模拟数据。
 * 后端就绪后:设置 VITE_USE_MOCK=false 即切真实接口,前端零改动。
 */

const USE_MOCK = process.env.VITE_USE_MOCK !== 'false'

// ---------------------------------------------------------------- utils
type Req = IncomingMessage
type Res = ServerResponse
interface Ctx {
  query: Record<string, string>
  body: Record<string, unknown>
  params: Record<string, string>
  method: string
}
type Handler = (req: Req, res: Res, ctx: Ctx) => Promise<void> | void

function json(res: Res, code: number, data: unknown, message = 'ok') {
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.end(JSON.stringify({ code, message, data }))
}

const delay = (ms: number) => new Promise((r) => setTimeout(r, ms))

function readBody(req: Req): Promise<Record<string, unknown>> {
  return new Promise((resolve) => {
    let raw = ''
    req.on('data', (c: Buffer) => (raw += c))
    req.on('end', () => {
      try {
        resolve(raw ? JSON.parse(raw) : {})
      } catch {
        resolve({})
      }
    })
  })
}

// ---------------------------------------------------------------- 状态
const captchaStore = new Map<string, { code: string; expireAt: number }>()
const smsStore = new Map<string, { code: string; expireAt: number }>()
const sessions = new Map<string, { userId: number; expireAt: number }>()

/** 生成 SVG 验证码（转 base64 data-url） */
function genCaptcha(): { captchaId: string; imageBase64: string } {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
  let code = ''
  for (let i = 0; i < 4; i++) code += chars[Math.floor(Math.random() * chars.length)]
  const id = `cap_${Date.now()}_${Math.floor(Math.random() * 9999)}`
  captchaStore.set(id, { code, expireAt: Date.now() + 5 * 60_000 })
  const colors = ['#185fa5', '#0c447c', '#2f6fe4', '#5a3fd0']
  let inner = `<rect width="108" height="40" fill="#f5f7fa"/>`
  for (let i = 0; i < 4; i++) {
    const x = 12 + i * 24
    const y = 27 + Math.floor(Math.random() * 6)
    const rot = Math.floor(Math.random() * 24) - 12
    inner += `<text x="${x}" y="${y}" transform="rotate(${rot} ${x} ${y})" font-size="20" font-family="Arial" font-weight="bold" fill="${colors[i]}">${code[i]}</text>`
  }
  for (let i = 0; i < 3; i++) {
    const x1 = Math.floor(Math.random() * 108)
    const y1 = Math.floor(Math.random() * 40)
    inner += `<line x1="${x1}" y1="${y1}" x2="${x1 + 22}" y2="${y1 + 14}" stroke="#c6e2ff" stroke-width="1"/>`
  }
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="108" height="40" viewBox="0 0 108 40">${inner}</svg>`
  return { captchaId: id, imageBase64: `data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}` }
}

function findUser(account: string) {
  const norm = account.replace(/\s/g, '')
  return allUsers.find(
    (u) => u.username === account || u.phone.replace(/\s/g, '') === norm || (u.email != null && u.email === account),
  )
}

// ---------------------------------------------------------------- 问答预设
interface QAPreset {
  match: RegExp
  answer: string
  chunks: { docId: number; score: number; source: 'vector' | 'bm25' | 'graph' }[]
}

const QA_PRESETS: QAPreset[] = [
  {
    match: /分片|shard|切片|切分/,
    answer:
      '该系统的分片采用「两级流水线」设计：\n\n**第一级 · 结构边界（锁定不可关）**\n- 按标题层级划分基础块（H1/H2/H3）\n- 表格、代码块、完整列表作为原子块整体保留，不拆分\n\n**第二级 · 超长兜底（默认开）**\n- 递归分隔符切分兜底超长块\n- 语义切分默认关闭\n\n以《AI 知识库平台白皮书 V3》为例，24 页文档最终产出 222 个分片：198 正文 + 12 表格 + 8 图片 + 4 文档级摘要，表格子块 88 个（回表行数上限 30 行 / ±5 行）。',
    chunks: [
      { docId: 1, score: 0.882, source: 'vector' },
      { docId: 1, score: 0.761, source: 'vector' },
      { docId: 3, score: 0.648, source: 'bm25' },
      { docId: 1, score: 0.592, source: 'graph' },
    ],
  },
  {
    match: /阈值|recall|0\.15|0\.35|0\.65|命中|分数/,
    answer:
      '系统采用「三阈值 + Rerank 保留数」体系：\n\n| 阈值 | 值 | 定位 |\n|---|---|---|\n| 召回阈值 recallThreshold | 0.15 | 库级 · 宽进，低于则不出候选 |\n| 未命中判定 missThreshold | 0.35 | 全局 · 严出，低于记入未命中沉淀 |\n| 降权提示 lowScoreWarn | 0.65 | 全局 · 达到即可参与引用 |\n| Rerank 保留数 rerankTopK | 5 | 库级可配 3~15 |\n\n设计理由：未命中清单里存在 0.19~0.33 分的记录，若召回阈值设 0.30，这些分数根本不该存在——所以召回阈值必须低于未命中判定线，形成「宽进严出」漏斗。',
    chunks: [
      { docId: 1, score: 0.872, source: 'vector' },
      { docId: 3, score: 0.719, source: 'vector' },
    ],
  },
  {
    match: /图谱|graph|实体|多跳|关系/,
    answer:
      '系统在检索链路中加入「轻量实体图谱旁路」（FR-GRAPH-01）：\n\n**构建侧**：复用文档级摘要那次 LLM 调用，顺带抽取实体关系三元组（实体A, 关系, 实体B），边带 source_chunk_id + confidence。1,246 篇文档构建成本可控，增量随文档版本重建。\n\n**检索侧**：查询解析后先做实体链接（n-gram/BM25 匹配，不强制 LLM），Neo4j 走 1~2 跳邻居，按 source_chunk_id 回源补 chunk，与主链路 top-k 合并进 Rerank。\n\n**降级**：图谱旁路 30~80ms 并行执行，超过 100ms 红线自动静默降级，主链路不受影响。\n\n例：文档 A「订单系统用一致性哈希分片」+ 文档 B「一致性哈希由 Karger 提出」，问「分片方案是谁提出的」→ 图谱沿「基于 → 提出者」两跳直达 Karger。',
    chunks: [
      { docId: 1, score: 0.846, source: 'vector' },
      { docId: 3, score: 0.702, source: 'graph' },
      { docId: 2, score: 0.655, source: 'graph' },
    ],
  },
  {
    match: /表格|报价|单价|双表示|型号/,
    answer:
      '表格内容检索采用「双表示」策略：\n\n1. 给大模型读：保留 Markdown 表格原样（结构可读）\n2. 给向量化用：行式自然语言（列名+取值连成句子），如「型号 A-100，单价 ¥1,200」\n\n整表原子化不拆分；父子块回表行数上限 30 行 / ±5 行；表格子块进入主召回池 + BM25 + Rerank 加权 1.2（不单独开召回通道）。标识符保护分词保证 A-100 不被切成 A / 100 导致误命中 A-1000。',
    chunks: [
      { docId: 1, score: 0.831, source: 'vector' },
      { docId: 2, score: 0.688, source: 'bm25' },
    ],
  },
  {
    match: /权限|越权|交集|数据范围|多租户/,
    answer:
      '权限模型为「三层求交」：最终权限 = 功能矩阵 ∩ 数据范围 ∩ 知识库授权。\n\n- 功能矩阵：12 项业务能力，由 38 个原子权限码聚合\n- 数据范围：角色属性（ALL 全公司 / DEPT 本部门 / SELF 仅本人），部门只是计算基准\n- 知识库授权：逐库授权（查看 / 管理）\n\n「可管理知识库」不含删除知识库，删除只由功能矩阵决定。知识库级权限隔离保证多租户数据不串门。',
    chunks: [
      { docId: 2, score: 0.794, source: 'vector' },
      { docId: 1, score: 0.626, source: 'bm25' },
    ],
  },
  {
    match: /延迟|耗时|链路|1668|428|慢/,
    answer:
      '检索链路平均耗时 1,668ms，阶段拆解：\n\n| 阶段 | 耗时 | 占比 |\n|---|---|---|\n| 查询解析 | 12ms | 0.7% |\n| Embedding | 86ms | 5.2% |\n| 向量召回 | 34ms | 2.0% |\n| BM25 召回 | 8ms | 0.5% |\n| Rerank | 288ms | 17.3% |\n| LLM 生成 | 1240ms | 74.3% |\n\nRerank 占检索侧 67%，但贡献 +18pp 命中率提升（63%→81%），是性价比最高的 288ms。',
    chunks: [
      { docId: 3, score: 0.812, source: 'vector' },
      { docId: 1, score: 0.634, source: 'bm25' },
    ],
  },
  {
    match: /未命中|闭环|补文档|重放|运营/,
    answer:
      '未命中问题走「五步闭环」（FR-OPS-01）：\n\n1. 沉淀：检索分数 < 0.35 自动记入未命中清单\n2. 补文档：一键发起补录\n3. 重建索引：定向重建受影响知识库\n4. 自动重放验证：系统自动重放原问题，对比补充前后召回分与是否命中新增文档\n5. 标记已解决：重放验证通过才允许标记\n\n当前未命中 108 条（占提问总数 1,284 的 8.4%），7 天趋势 45/52/241/236/229/268/213，工作日均值 237 / 周末 49。',
    chunks: [
      { docId: 1, score: 0.778, source: 'vector' },
      { docId: 3, score: 0.641, source: 'bm25' },
    ],
  },
  {
    match: /成本|token|费用|embedding|2\.5/,
    answer:
      '每日 Token 消耗约 2.5 万 tokens。索引侧（文档级摘要 + HyQ）占 63%、不随问答量增长；问答生成占 31%、随使用量线性增长。\n\n降本措施：\n- 内容标签与文档级摘要复用同一次 LLM 调用（零边际成本）\n- 实体图谱同样复用摘要调用抽实体（增量 ~10~15%）\n- 嵌入模型：bge-m3（1024 维，L2 归一化），后端配置、前端只读',
    chunks: [
      { docId: 1, score: 0.745, source: 'vector' },
      { docId: 3, score: 0.618, source: 'bm25' },
    ],
  },
]

const DEFAULT_ANSWER =
  '根据检索结果，这个问题在知识库中有相关材料。该设计在检索侧采用「三索引增强」策略：标题路径注入、HyQ 假设性问题、文档级摘要（不做 chunk 级摘要），配合混合检索（向量 + BM25）+ RRF 融合 + Cross-Encoder Rerank。\n\n最终输出形态为 top-k chunk 列表交由上游处理，融合压缩作为 P2 可选能力、通过 post_process 参数开启。建议查阅《RAG 检索链路设计稿》§3 获取多路召回与分数融合细节。'

function buildTrace(chunks: { docId: number; score: number; source: 'vector' | 'bm25' | 'graph' }[], graphUsed: boolean): RetrievalTrace {
  const list = chunks.map((c, i) => ({
    chunkId: 1000 + i,
    docId: c.docId,
    docTitle: mockDocs.find((d) => d.id === c.docId)?.title ?? '未知文档',
    score: c.score,
    source: c.source,
  }))
  return {
    queryAnalysis: RETRIEVAL_COST.queryAnalysis,
    embed: RETRIEVAL_COST.embed,
    vectorRecall: RETRIEVAL_COST.vectorRecall,
    bm25Recall: RETRIEVAL_COST.bm25Recall,
    rerank: RETRIEVAL_COST.rerank,
    llmGeneration: RETRIEVAL_COST.llmGeneration,
    totalMs: RETRIEVAL_COST.total,
    stages: [
      { name: '查询解析', costMs: RETRIEVAL_COST.queryAnalysis },
      { name: 'Embedding', costMs: RETRIEVAL_COST.embed },
      { name: '向量召回', costMs: RETRIEVAL_COST.vectorRecall },
      { name: 'BM25 召回', costMs: RETRIEVAL_COST.bm25Recall },
      { name: 'Rerank', costMs: RETRIEVAL_COST.rerank },
      { name: 'LLM 生成', costMs: RETRIEVAL_COST.llmGeneration },
    ],
    chunks: list,
    graphUsed,
    graphCostMs: graphUsed ? 52 : undefined,
    graphFallback: false,
  }
}

// ---------------------------------------------------------------- 路由注册
interface Route {
  method: string
  pattern: RegExp
  paramNames: string[]
  handler: Handler
}

const routes: Route[] = []
function register(method: string, path: string, handler: Handler) {
  // middlewares.use('/api') 挂载后 req.url 不含 /api 前缀，这里剥掉统一
  const norm = path.startsWith('/api') ? path.slice(4) : path
  // 提取 :param 名并转成正则捕获组
  const paramNames = [...norm.matchAll(/:([^/]+)/g)].map((m) => m[1]!)
  const pattern = new RegExp(`^${norm.replace(/:[^/]+/g, '([^/]+)')}$`)
  routes.push({ method: method.toUpperCase(), pattern, paramNames, handler })
}

// ============ 认证 ============
register('GET', '/api/auth/captcha', (_req, res) => {
  json(res, 0, genCaptcha())
})

register('POST', '/api/auth/login', async (req, res, ctx) => {
  void req
  await delay(420)
  const { account, remember } = ctx.body
  const user = findUser(String(account ?? ''))
  if (!user) return json(res, 1001, null, '账号或密码错误')
  if (user.status === 'DISABLED') return json(res, 1003, null, '账号已被禁用，请联系管理员')
  if (user.status === 'PENDING') return json(res, 1004, null, '账号待激活，请使用短信验证码登录或联系管理员')
  // mock：预设错误演示账号
  if (user.username === 'chenjing' && ctx.body.password !== 'Admin@123') return json(res, 1001, null, '账号或密码错误')
  const token = `mock_${user.id}_${Date.now()}`
  sessions.set(token, { userId: user.id, expireAt: Date.now() + (remember ? 7 * 24 * 3600_000 : 2 * 3600_000) })
  json(res, 0, {
    accessToken: token,
    refreshToken: `rf_${user.id}`,
    tokenType: 'Bearer',
    expiresIn: remember ? 604800 : 7200,
    user: { ...user, permissions: ['*'] },
    roles: user.roles.map((c: string) => ({ code: c, name: ROLE_CODES[c] ?? c })),
  })
})

register('POST', '/api/auth/login-by-sms', async (req, res, ctx) => {
  void req
  await delay(420)
  const { phone, smsCode, remember } = ctx.body
  const u = allUsers.find((x) => x.phone.replace(/\s/g, '') === String(phone ?? '').replace(/\s/g, ''))
  const sms = smsStore.get(String(phone ?? ''))
  if (sms && String(smsCode) !== sms.code && String(smsCode) !== '123456') return json(res, 1002, null, '短信验证码错误')
  if (!u) return json(res, 1004, null, '该手机号未绑定账号')
  if (u.status === 'DISABLED') return json(res, 1003, null, '账号已被禁用，请联系管理员')
  const token = `mock_${u.id}_${Date.now()}`
  sessions.set(token, { userId: u.id, expireAt: Date.now() + (remember ? 7 * 24 * 3600_000 : 2 * 3600_000) })
  json(res, 0, {
    accessToken: token,
    refreshToken: `rf_${u.id}`,
    tokenType: 'Bearer',
    expiresIn: remember ? 604800 : 7200,
    user: { ...u, permissions: ['*'] },
    roles: u.roles.map((c: string) => ({ code: c, name: ROLE_CODES[c] ?? c })),
  })
})

register('POST', '/api/auth/logout', (_req, res) => json(res, 0, null))

register('GET', '/api/auth/check-username', (_req, res, ctx) => {
  const name = ctx.query.username ?? ''
  const taken = allUsers.some((u) => u.username === name)
  json(res, 0, { available: !taken, reason: taken ? '该用户名已被占用' : '该用户名可用' })
})

register('GET', '/api/auth/check-phone', (_req, res, ctx) => {
  const phone = (ctx.query.phone ?? '').replace(/\s/g, '')
  const taken = allUsers.some((u) => u.phone.replace(/\s/g, '') === phone)
  json(res, 0, { available: !taken, reason: taken ? '该手机号已注册' : '该手机号可用' })
})

register('POST', '/api/auth/sms-code', async (req, res, ctx) => {
  void req
  await delay(200)
  const { phone } = ctx.body
  const code = String(100000 + Math.floor(Math.random() * 900000))
  smsStore.set(String(phone), { code, expireAt: Date.now() + 5 * 60_000 })
  json(res, 0, { sent: true, debugCode: code, scene: ctx.body.scene ?? 'register' })
})

register('POST', '/api/auth/register', async (req, res, ctx) => {
  void req
  await delay(600)
  const { username, phone, smsCode } = ctx.body
  const sms = smsStore.get(String(phone ?? ''))
  if (sms && String(smsCode) !== sms.code && String(smsCode) !== '123456') return json(res, 1002, null, '短信验证码错误')
  if (allUsers.some((u) => u.username === username)) return json(res, 1001, null, '用户名已被占用')
  json(res, 0, { registered: true, username })
})

register('POST', '/api/auth/verify-reset', async (req, res, ctx) => {
  void req
  await delay(450)
  const { username, phone, smsCode } = ctx.body
  const user = findUser(String(username ?? ''))
  if (!user) return json(res, 1001, null, '账号不存在')
  const sms = smsStore.get(String(phone ?? ''))
  if (sms && String(smsCode) !== sms.code && String(smsCode) !== '123456') return json(res, 1002, null, '短信验证码错误')
  json(res, 0, {
    token: `reset_${user.id}_${Date.now()}`,
    maskedPhone: user.phone.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2'),
  })
})

register('POST', '/api/auth/reset-password', async (req, res) => {
  void req
  await delay(500)
  json(res, 0, { reset: true })
})

// ============ 当前用户 ============
register('GET', '/api/user/profile', (_req, res) => {
  const user = allUsers[1]! // lin.gong
  json(res, 0, { ...user, permissions: ['*'], roleNames: user.roles.map((c: string) => ROLE_CODES[c] ?? c) })
})

register('GET', '/api/user/security', (_req, res) => {
  json(res, 0, {
    phone: '138****6666',
    email: 'lin.***@corp.com',
    mfaEnabled: false,
    passwordUpdatedAt: '2026-08-02 14:20',
    recentDevices: [
      { device: 'Chrome 138 · Windows 11', ip: '10.12.8.31', location: '内网', loggedInAt: '2026-09-11 17:40', current: true },
      { device: 'Chrome 137 · macOS 15', ip: '10.12.8.77', location: '内网', loggedInAt: '2026-09-11 09:12', current: false },
    ],
  })
})

register('GET', '/api/user/login-logs', (_req, res, ctx) => {
  const page = Number(ctx.query.page ?? 1)
  const pageSize = Number(ctx.query.pageSize ?? 10)
  const logs = Array.from({ length: 47 }, (_, i) => ({
    id: 47 - i,
    method: ['密码登录', '密码登录', '验证码登录', '统一身份认证'][i % 4],
    ip: `10.12.${8 + (i % 4)}.${31 + i * 13}`,
    device: ['Chrome · Windows', 'Chrome · macOS', 'Edge · Windows'][i % 3],
    result: i % 9 === 0 ? 'FAILED' : 'SUCCESS',
    time: `2026-09-${String(11 - Math.floor(i / 5)).padStart(2, '0')} ${String(9 + (i % 12))}:${String((i * 7) % 60).padStart(2, '0')}`,
  }))
  const start = (page - 1) * pageSize
  json(res, 0, { list: logs.slice(start, start + pageSize), total: logs.length, page, pageSize })
})

// ============ 知识库 / 文档 ============
/** 新建的知识库（内存态，创建后可跳转上传页正常工作） */
const createdKbs = new Map<number, KnowledgeBase & { config?: KbConfigForm }>()
let nextKbId = 9

function findKb(id: string | number): KnowledgeBase | undefined {
  const nid = Number(id)
  return createdKbs.get(nid) ?? mockKbs.find((k) => k.id === nid)
}

register('GET', '/api/kb', (_req, res) => {
  json(res, 0, {
    list: mockKbs.map((k) => ({ id: k.id, name: k.name, docCount: k.docCount, chunkCount: k.chunkCount })),
    total: mockKbs.length,
  })
})

register('POST', '/api/kb', async (req, res, ctx) => {
  void req
  await delay(600)
  const form = ctx.body as unknown as KbConfigForm
  const id = nextKbId++
  const kb: KnowledgeBase & { config?: KbConfigForm } = {
    id,
    name: form.name ?? `新建知识库 ${id}`,
    description: form.description ?? '',
    owner: '当前用户',
    department: DEPT_NAME[form.deptId] ?? '未分配',
    docCount: 0,
    chunkCount: 0,
    status: 'ACTIVE',
    createdAt: '2026-09-12 13:20',
    updatedAt: '2026-09-12 13:20',
    config: form,
  }
  createdKbs.set(id, kb)
  json(res, 0, { id, name: kb.name, status: 'ACTIVE', note: '创建成功，即将跳转文档上传页' })
})

/** 策略预设 + 默认配置 + 只读嵌入模型（新建向导初始化） */
register('GET', '/api/kb/presets', (_req, res) => {
  json(res, 0, {
    presets: SPLIT_PRESETS,
    defaults: defaultKbConfig(),
    embedding: { model: 'BAAI/bge-m3', dimension: 1024, norm: 'L2 归一化', locked: true },
    vlModel: activeVlModel(),
  })
})

/** 知识库配置（设置页 / 详情页） */
const KB_DEPT: Record<number, { deptId: number; domain: string }> = {
  1: { deptId: 1, domain: '研发 / 平台' },
  2: { deptId: 1, domain: '研发 / 应用' },
  3: { deptId: 2, domain: '销售 / 售前' },
  4: { deptId: 3, domain: '财务 / 税务' },
  5: { deptId: 3, domain: '人力资源' },
  6: { deptId: 4, domain: '运维 / 安全' },
  7: { deptId: 1, domain: '研发 / 平台' },
  8: { deptId: 2, domain: '销售 / 售前' },
}
/** 依据既有知识库生成合理配置（保证设置页回显与实际一致） */
function kbConfigOf(kb: KnowledgeBase): KbConfigForm {
  const d = KB_DEPT[kb.id] ?? { deptId: 1, domain: '研发 / 平台' }
  return {
    ...defaultKbConfig(),
    name: kb.name,
    code: `kb_${kb.id}_${kb.department}`,
    description: kb.description,
    deptId: d.deptId,
    businessDomain: d.domain,
    secretLevel: 'INTERNAL',
    customTags: kb.id === 1 ? ['2026 年度', '技术白皮书'] : [],
  }
}

register('GET', '/api/kb/:id/config', (_req, res, ctx) => {
  const kb = findKb(ctx.params.id ?? '1') ?? mockKbs[0]!
  const created = createdKbs.get(Number(ctx.params.id))
  const d = KB_DEPT[kb.id] ?? { deptId: 1, domain: '研发 / 平台' }
  json(res, 0, {
    id: kb.id,
    name: kb.name,
    code: `kb_${kb.id}_${kb.department}`,
    description: kb.description,
    department: kb.department,
    deptId: d.deptId,
    docCount: kb.docCount,
    chunkCount: kb.chunkCount,
    status: kb.status,
    createdAt: kb.createdAt,
    updatedAt: kb.updatedAt,
    visibleRoles: ['SUPER_ADMIN', 'KB_ADMIN'],
    config: created?.config ?? kbConfigOf(kb),
    embedding: { model: 'BAAI/bge-m3', dimension: 1024, norm: 'L2 归一化', locked: true },
    vlModel: activeVlModel(),
    presetOptions: SPLIT_PRESETS,
    secretLevelLabel: SECRET_LEVEL_LABEL,
    retrievalModeLabel: RETRIEVAL_MODE_LABEL,
  })
})

register('PUT', '/api/kb/:id/config', async (req, res, ctx) => {
  await delay(400)
  const body = ctx.body as unknown as { config?: KbConfigForm }
  const created = createdKbs.get(Number(ctx.params.id))
  if (created && body.config) created.config = body.config
  json(res, 0, { saved: true, needReindex: true, updatedAt: '2026-09-12 13:24' })
})

/** 变更影响预估（抽样推算） */
register('POST', '/api/kb/:id/config/impact', async (req, res, ctx) => {
  void req
  await delay(500)
  const kb = findKb(ctx.params.id ?? '1') ?? mockKbs[0]!
  json(res, 0, {
    docCount: kb.docCount,
    chunkBefore: kb.chunkCount,
    chunkAfter: Math.round(kb.chunkCount * 0.72),
    estimateMinutes: 18,
    estimateTokens: 3_200_000,
  })
})

register('POST', '/api/kb/:id/reindex', async (req, res, ctx) => {
  await delay(500)
  const mode = String((ctx.body as { mode?: string }).mode ?? 'SCHEDULED')
  json(res, 0, {
    taskId: `reindex_${Date.now()}`,
    mode,
    status: mode === 'IMMEDIATE' ? 'RUNNING' : 'QUEUED',
    scheduledAt: mode === 'SCHEDULED' ? '今日 02:00' : null,
  })
})

register('DELETE', '/api/kb/:id/index', async (req, res) => {
  void req
  await delay(400)
  json(res, 0, { cleared: true })
})

/** 删除知识库（软删，进回收站；文档 §5 `DELETE /kb/{id}`） */
register('DELETE', '/api/kb/:id', async (req, res, ctx) => {
  void req
  await delay(500)
  const kb = findKb(ctx.params.id ?? '1') ?? mockKbs[0]!
  createdKbs.delete(Number(ctx.params.id))
  json(res, 0, { id: kb.id, deleted: true, recycled: true, name: kb.name })
})

/** 库内文档列表（含解析质量：表格 N/M） */
register('GET', '/api/kb/:id/docs', (_req, res, ctx) => {
  const kb = findKb(ctx.params.id ?? '1') ?? mockKbs[0]!
  const rows = kbDocsOf(kb.id)
  const kw = (ctx.query.keyword ?? '').trim().toLowerCase()
  const type = ctx.query.type ?? ''
  const status = ctx.query.status ?? ''
  let list = rows
  if (kw) list = list.filter((d) => d.title.toLowerCase().includes(kw))
  if (type && type !== 'all') list = list.filter((d) => d.type === type)
  if (status && status !== 'all') list = list.filter((d) => d.status === status)
  json(res, 0, { list, total: kb.docCount, displayed: list.length })
})

/** 未识别表格清单 */
register('GET', '/api/kb/:id/parse-issues', (_req, res) => {
  json(res, 0, { list: mockParseIssues, total: mockParseIssues.length })
})

/** 只读解析配置（继承自知识库） */
register('GET', '/api/kb/:id/parse-config', (_req, res) => {
  json(res, 0, PARSE_CONFIG_VIEW)
})

/** MD5 去重判断（秒传） */
register('POST', '/api/kb/:id/doc/check-md5', async (req, res, ctx) => {
  await delay(200)
  const body = ctx.body as { md5?: string; name?: string }
  // 演示：与库内已有文档同名 → 视为重复（实际按 (owner_id, md5) 判定）
  const hit = mockDocs.find((d) => body.name && d.title === body.name.replace(/\.[^.]+$/, ''))
  json(res, 0, {
    duplicated: !!hit,
    docId: hit?.id,
    docTitle: hit?.title,
    hint: hit ? '文件已存在，将归档为新版本（不覆盖历史问答引用）' : '',
  })
})

/** 上传（分片上传简化：返回 docId + taskId） */
register('POST', '/api/kb/:id/doc/upload', async (req, res, ctx) => {
  await delay(320)
  const name = String((ctx.body as { name?: string }).name ?? '未命名文件')
  const docId = Date.now() % 100000
  json(res, 0, {
    docId,
    taskId: `task_${docId}`,
    name,
    kbId: Number(ctx.params.id),
    chunkTotal: 40 + Math.floor(Math.random() * 120),
  })
})

/** 任务进度轮询 */
register('GET', '/api/doc/tasks/:taskId', (_req, res, ctx) => {
  const taskId = ctx.params.taskId ?? ''
  const seed = [...taskId].reduce((a, c) => a + c.charCodeAt(0), 0)
  const stage = seed % 3 === 0 ? 'PARSING' : seed % 3 === 1 ? 'INDEXING' : 'DONE'
  json(res, 0, {
    taskId,
    stage,
    percent: stage === 'DONE' ? 100 : 40 + (seed % 50),
    chunkDone: stage === 'DONE' ? 121 : 46,
    chunkTotal: 121,
    errorMsg: null,
  })
})

register('POST', '/api/doc/:id/reindex', async (req, res, ctx) => {
  void req
  await delay(400)
  json(res, 0, { docId: Number(ctx.params.id), taskId: `task_${Date.now()}`, status: 'RUNNING' })
})

/** 批量删除必须在 :id 之前注册（3 段路径冲突） */
register('DELETE', '/api/doc/batch', async (req, res, ctx) => {
  await delay(400)
  const ids = (ctx.body as { ids?: number[] }).ids ?? []
  json(res, 0, { deleted: ids.length })
})

register('DELETE', '/api/doc/:id', async (req, res, ctx) => {
  await delay(400)
  json(res, 0, { docId: Number(ctx.params.id), recycled: true })
})

// ============ 问答（SSE 流式） ============
/** 提问传图时 VL 生成的结构化描述（与后端内部流转一致：前端只读展示，不作为输入） */
const MOCK_VL_DESC =
  '【类型】表格截图（报价单）\n\n| 型号 | 单价（元） | 起订量 | 交期 |\n|---|---|---|---|\n| A-100 | 1,280 | 50 | 7 天 |\n| A-1000 | 2,450 | 20 | 15 天 |\n\n【关键数值】A-100 单价 1,280 元 / A-1000 单价 2,450 元，价差 1,170 元；起订量分别为 50 与 20。'
const MOCK_VL_SEARCH_SUMMARY = '报价单, A-100, A-1000, 单价, 1280, 2450, 起订量, 交期'

/** 提问附件（内存态；只落存储 + 落库，不调 VL） */
const attachmentStore = new Map<string, { name: string; size: number; md5: string; mime: string; desc?: string }>()

register('POST', '/api/chat/attachments', async (req, res) => {
  void req
  await delay(420)
  const id = `att_${Date.now().toString(36)}`
  const md5 = 'a3f8c1e2b47d9e02c8f1a5b3d7e94150'
  attachmentStore.set(id, { name: '报价单截图.png', size: 486_400, md5, mime: 'image/png' })
  json(res, 0, {
    attachmentId: id,
    presignedUrl: `https://minio.internal/kb-attach/${id}.png?X-Amz-Expires=600`,
    md5,
    size: 486_400,
    mime: 'image/png',
  })
})

register('GET', '/api/chat/attachments/:id', (_req, res, ctx) => {
  const aid = ctx.params.id ?? ''
  const a = attachmentStore.get(aid) ?? { name: '报价单截图.png', size: 486_400, md5: 'a3f8c1e2b47d9e02c8f1a5b3d7e94150', mime: 'image/png' }
  json(res, 0, {
    id: aid,
    type: 'IMAGE',
    ...a,
    desc: MOCK_VL_DESC,
    searchSummary: MOCK_VL_SEARCH_SUMMARY,
    vlModelName: 'Qwen2.5-VL-72B',
    degraded: false,
    promptVersion: 'v1',
  })
})

register('GET', '/api/chat/sessions', (_req, res) => {
  json(res, 0, { list: mockChatSessions, total: mockChatSessions.length })
})

/** 历史会话消息（切回旧会话时回填；含模型归因与引用） */
register('GET', '/api/chat/sessions/:id/messages', (_req, res, ctx) => {
  const sid = String(ctx.params.id ?? 's1')
  const sess = mockChatSessions.find((s) => s.id === sid) ?? mockChatSessions[0]!
  const t0 = Date.now() - 3600_000
  const list = [
    {
      id: `${sid}-m1`,
      role: 'user',
      content: sess.title,
      createdAt: t0,
    },
    {
      id: `${sid}-m2`,
      role: 'assistant',
      content:
        `围绕「${sess.title}」，系统采用「混合检索 + Rerank 精排 + 生成」三段式：` +
        `先用 BGE-M3 做向量召回、BM25 做关键词召回，融合后再由重排模型精排，最后交由对话模型生成带引用的回答。`,
      createdAt: t0 + 4200,
      modelId: sess.modelId,
      modelName: sess.modelName,
      citations: [
        { index: 1, docId: 1, docTitle: 'AI 知识库平台白皮书 V3', chunkId: 46, source: 'vector', score: 0.82 },
      ],
    },
  ]
  json(res, 0, { list, total: list.length })
})

register('POST', '/api/chat/completions', async (req, res, ctx) => {
  void req
  res.setHeader('Content-Type', 'text/event-stream; charset=utf-8')
  res.setHeader('Cache-Control', 'no-cache')
  res.setHeader('Connection', 'keep-alive')
  res.flushHeaders?.()

  const message = String(ctx.body.message ?? '')
  const attachmentIds = Array.isArray(ctx.body.attachmentIds) ? (ctx.body.attachmentIds as string[]) : []
  const modelId = ctx.body.modelId == null ? null : Number(ctx.body.modelId)
  const useVision = ctx.body.useVision === true
  const graphHint = /图谱|graph|实体|多跳|关系/.test(message)

  // 0) 带图片：首包必须是 attachment{DESCRIBING}，让用户看到「正在理解图片」
  if (attachmentIds.length) {
    for (const id of attachmentIds) {
      res.write(`event: attachment\ndata: ${JSON.stringify({ stage: 'DESCRIBING', attachmentId: String(id) })}\n\n`)
    }
    await delay(1200)
    for (const id of attachmentIds) {
      res.write(
        `event: attachment\ndata: ${JSON.stringify({
          stage: 'DONE',
          attachmentId: String(id),
          desc: MOCK_VL_DESC,
          searchSummary: MOCK_VL_SEARCH_SUMMARY,
          vlModel: 'Qwen2.5-VL-72B',
          degraded: false,
        })}\n\n`,
      )
    }
  }

  // 1) retrieval 事件（不带图片时必须是首包）
  const preset = QA_PRESETS.find((p) => p.match.test(message))
  const rawChunks = preset?.chunks ?? [
    { docId: 1, score: 0.72, source: 'vector' as const },
    { docId: 3, score: 0.61, source: 'vector' as const },
    { docId: 2, score: 0.55, source: 'bm25' as const },
    { docId: 1, score: 0.48, source: 'bm25' as const },
    { docId: 5, score: 0.42, source: 'graph' as const },
  ]
  const graphUsed = graphHint || rawChunks.some((c) => c.source === 'graph')
  const trace = buildTrace(rawChunks, graphUsed)
  res.write(`event: retrieval\ndata: ${JSON.stringify({ chunks: trace.chunks, trace, kbCount: 3 })}\n\n`)

  // 2) delta 打字机
  const answer = preset ? preset.answer : DEFAULT_ANSWER
  const fragments = answer.split(/(?<=[。！？\n])/).filter(Boolean)
  await delay(120)
  for (const frag of fragments) {
    res.write(`event: delta\ndata: ${JSON.stringify({ content: frag })}\n\n`)
    await delay(24 + Math.floor(Math.random() * 22))
  }

  // 3) done
  res.write(
    `event: done\ndata: ${JSON.stringify({
      messageId: `msg_${Date.now()}`,
      modelId: modelId ?? 1,
      modelName: 'DeepSeek-V3（硅基流动）',
      tokenUsage: { prompt: 1284, completion: 386, total: 1670 },
      finishReason: 'stop',
      usedVision: useVision,
    })}\n\n`,
  )
  res.end()
})

// ============ 系统 ============
register('GET', '/api/system/thresholds', (_req, res) => {
  json(res, 0, THRESHOLDS)
})

// ============ 系统管理 · 部门 ============
register('GET', '/api/depts/tree', (_req, res) => {
  json(res, 0, DEPTS)
})

register('POST', '/api/depts', async (req, res, ctx) => {
  void req
  await delay(280)
  json(res, 0, { id: 99, ...ctx.body })
})

// ============ 系统管理 · 用户 ============
function userToRow(u: (typeof allUsers)[number]) {
  return {
    id: u.id,
    username: u.username,
    realName: u.realName,
    phone: u.phone,
    email: u.email,
    department: u.department,
    deptId: u.deptId,
    roles: u.roles,
    roleNames: u.roles.map((c: string) => ROLE_CODES[c] ?? c),
    status: u.status,
    statusLabel: USER_STATUS_LABEL[u.status],
    createdAt: u.createdAt,
    lastLoginAt: u.lastLoginAt,
    mustChangePassword: u.mustChangePassword ?? false,
    hasBusinessData: u.hasBusinessData ?? false,
  }
}

register('GET', '/api/users', (_req, res, ctx) => {
  const page = Number(ctx.query.page ?? 1)
  const size = Number(ctx.query.size ?? 10)
  const { deptId, keyword, roleId, status } = ctx.query

  let list = [...allUsers]
  // 部门筛选（含子节点；0=未分配）
  if (deptId !== undefined && deptId !== '' && deptId !== 'all') {
    const d = Number(deptId)
    if (d === 1) list = list.filter((u) => u.deptId === 1 || u.deptId === 11 || u.deptId === 12 || u.deptId === 13)
    else list = list.filter((u) => u.deptId === d)
  }
  if (keyword) {
    const kw = String(keyword).toLowerCase()
    list = list.filter(
      (u) => u.realName.toLowerCase().includes(kw) || u.username.toLowerCase().includes(kw) || u.phone.includes(kw),
    )
  }
  if (roleId && roleId !== 'all') {
    const r = String(roleId)
    list = list.filter((u) => u.roles.includes(r))
  }
  if (status && status !== 'all') {
    list = list.filter((u) => u.status === status)
  }
  const start = (page - 1) * size
  json(res, 0, {
    list: list.slice(start, start + size).map(userToRow),
    total: list.length,
    page,
    pageSize: size,
  })
})

register('POST', '/api/users', async (req, res, ctx) => {
  void req
  await delay(420)
  const body = ctx.body as Record<string, unknown>
  const deptId = Number(body.deptId ?? 0)
  const user = {
    id: Date.now() % 100000,
    username: String(body.username),
    realName: String(body.name),
    phone: String(body.phone),
    email: (body.email as string) || undefined,
    department: DEPT_NAME[deptId] ?? '未分配',
    deptId,
    roles: (body.roleIds as string[]) ?? ['USER'],
    status: 'PENDING' as const,
    createdAt: '2026-09-11 17:44',
  }
  allUsers.push(user)
  json(res, 0, { id: user.id, status: 'PENDING' })
})

register('PUT', '/api/users/:id', async (req, res, ctx) => {
  void req
  await delay(280)
  const id = Number(ctx.params.id)
  const u = allUsers.find((x) => x.id === id)
  if (!u) return json(res, 1001, null, '用户不存在')
  const body = ctx.body as Record<string, unknown>
  if (body.realName) u.realName = String(body.realName)
  if (body.phone) u.phone = String(body.phone)
  if (body.email !== undefined) u.email = body.email as string
  if (body.deptId !== undefined) {
    const deptId = Number(body.deptId)
    u.deptId = deptId
    u.department = DEPT_NAME[deptId] ?? '未分配'
  }
  if (body.roleIds) u.roles = body.roleIds as string[]
  json(res, 0, userToRow(u))
})

register('PATCH', '/api/users/:id/status', async (req, res, ctx) => {
  void req
  await delay(260)
  const id = Number(ctx.params.id)
  const u = allUsers.find((x) => x.id === id)
  if (!u) return json(res, 1001, null, '用户不存在')
  const status = String((ctx.body as Record<string, unknown>).status ?? 'ACTIVE')
  u.status = status as (typeof allUsers)[number]['status']
  json(res, 0, userToRow(u))
})

register('POST', '/api/users/:id/reset-password', async (req, res) => {
  void req
  await delay(320)
  json(res, 0, { reset: true, mustChangePassword: true, message: '已重置为初始密码，用户下次登录需修改' })
})

register('POST', '/api/users/:id/resend-invite', async (req, res) => {
  void req
  await delay(300)
  json(res, 0, { sent: true, inviteExpireDays: 7 })
})

register('POST', '/api/users/import', async (req, res) => {
  void req
  await delay(900)
  json(res, 0, { success: 46, failed: 2, rows: [{ row: 12, error: '用户名重复' }, { row: 25, error: '部门不存在' }] })
})

// ============ 系统管理 · 角色 ============
function roleToRow(r: (typeof mockRoles)[number]) {
  const codes = r.permissions.includes('*') ? ALL_PERMISSION_CODES : (r.permissions as string[])
  return {
    id: r.id,
    name: r.name,
    code: r.code,
    description: r.description,
    builtin: r.builtin,
    dataScope: r.dataScope,
    dataScopeLabel: DATA_SCOPE_LABEL[r.dataScope],
    memberCount: r.memberCount,
    permissionCount: codes.length,
    enabled: r.enabled,
    createdAt: r.createdAt,
  }
}

register('GET', '/api/roles', (_req, res, ctx) => {
  const { keyword, type, status } = ctx.query
  let list = [...mockRoles]
  if (keyword) {
    const kw = String(keyword).toLowerCase()
    list = list.filter((r) => r.name.toLowerCase().includes(kw) || r.code.toLowerCase().includes(kw))
  }
  if (type && type !== 'all') list = list.filter((r) => (type === 'BUILTIN' ? r.builtin : !r.builtin))
  if (status && status !== 'all') list = list.filter((r) => r.enabled === (status === 'ENABLED'))
  json(res, 0, { list: list.map(roleToRow), total: list.length })
})

register('POST', '/api/roles', async (req, res, ctx) => {
  void req
  await delay(360)
  const body = ctx.body as Record<string, unknown>
  const role = {
    id: 100 + mockRoles.length + 1,
    name: String(body.name),
    code: String(body.code),
    description: String(body.description ?? ''),
    builtin: false,
    dataScope: body.dataScope as 'ALL' | 'DEPT_AND_CHILD' | 'DEPT' | 'SELF' | 'CUSTOM',
    memberCount: 0,
    permissions: (body.permissions as string[]) ?? [],
    enabled: body.enabled !== false,
    createdAt: '2026-09-11 17:44',
  }
  mockRoles.push(role)
  json(res, 0, roleToRow(role))
})

register('PUT', '/api/roles/:id', async (req, res, ctx) => {
  void req
  await delay(320)
  const id = Number(ctx.params.id)
  const r = mockRoles.find((x) => x.id === id)
  if (!r) return json(res, 1001, null, '角色不存在')
  const body = ctx.body as Record<string, unknown>
  if (body.name) r.name = String(body.name)
  if (body.description !== undefined) r.description = String(body.description)
  if (body.dataScope) r.dataScope = body.dataScope as (typeof mockRoles)[number]['dataScope']
  if (body.permissions) r.permissions = body.permissions as string[]
  if (body.enabled !== undefined) r.enabled = body.enabled as boolean
  json(res, 0, roleToRow(r))
})

register('DELETE', '/api/roles/:id', async (req, res, ctx) => {
  void req
  await delay(320)
  const id = Number(ctx.params.id)
  const r = mockRoles.find((x) => x.id === id)
  if (!r) return json(res, 1001, null, '角色不存在')
  if (r.builtin) return json(res, 1005, null, '内置角色不可删除')
  if (r.memberCount > 0) return json(res, 1006, null, '角色下存在成员，请先转移')
  const i = mockRoles.indexOf(r)
  mockRoles.splice(i, 1)
  json(res, 0, { deleted: true })
})

register('PATCH', '/api/roles/:id/status', async (req, res, ctx) => {
  void req
  await delay(240)
  const r = mockRoles.find((x) => x.id === Number(ctx.params.id))
  if (!r) return json(res, 1001, null, '角色不存在')
  r.enabled = Boolean((ctx.body as Record<string, unknown>).enabled ?? true)
  json(res, 0, roleToRow(r))
})

register('GET', '/api/permissions/tree', (_req, res) => {
  json(res, 0, { tree: PERMISSION_TREE, total: ALL_PERMISSION_CODES.length })
})

register('GET', '/api/roles/:id/permissions', (_req, res, ctx) => {
  const r = mockRoles.find((x) => x.id === Number(ctx.params.id))
  if (!r) return json(res, 1001, null, '角色不存在')
  json(res, 0, { codes: r.permissions.includes('*') ? ALL_PERMISSION_CODES : r.permissions })
})

// ============ 系统管理 · 授权 ============
/** 12 个功能项 × 5 角色 矩阵（由 38 原子权限码聚合） */
const MATRIX_ITEMS = [
  { key: 'qa:ask', label: '发起问答', group: '知识问答' },
  { key: 'qa:history', label: '查看历史会话', group: '知识问答' },
  { key: 'qa:delete', label: '删除会话记录', group: '知识问答' },
  { key: 'kb:list', label: '查看知识库列表', group: '知识库' },
  { key: 'kb:edit', label: '创建 / 编辑知识库', group: '知识库' },
  { key: 'kb:delete', label: '删除知识库', group: '知识库' },
  { key: 'doc:upload', label: '上传文档', group: '文档' },
  { key: 'doc:view', label: '预览 / 下载文档', group: '文档' },
  { key: 'doc:manage', label: '删除 / 重建索引', group: '文档' },
  { key: 'sys:user', label: '用户管理', group: '系统管理' },
  { key: 'sys:role', label: '角色 / 授权管理', group: '系统管理' },
  { key: 'sys:audit', label: '审计日志', group: '系统管理' },
]
/** 档位：MANAGE/WRITE/READ/NONE */
const MATRIX_DATA: Record<string, string[]> = {
  'qa:ask': ['MANAGE', 'MANAGE', 'WRITE', 'WRITE', 'READ'],
  'qa:history': ['MANAGE', 'WRITE', 'WRITE', 'READ', 'NONE'],
  'qa:delete': ['MANAGE', 'WRITE', 'READ', 'NONE', 'NONE'],
  'kb:list': ['MANAGE', 'MANAGE', 'WRITE', 'READ', 'READ'],
  'kb:edit': ['MANAGE', 'WRITE', 'NONE', 'NONE', 'NONE'],
  'kb:delete': ['MANAGE', 'READ', 'NONE', 'NONE', 'NONE'],
  'doc:upload': ['MANAGE', 'WRITE', 'WRITE', 'NONE', 'NONE'],
  'doc:view': ['MANAGE', 'WRITE', 'READ', 'READ', 'READ'],
  'doc:manage': ['MANAGE', 'WRITE', 'NONE', 'NONE', 'NONE'],
  'sys:user': ['MANAGE', 'READ', 'NONE', 'NONE', 'NONE'],
  'sys:role': ['MANAGE', 'NONE', 'NONE', 'NONE', 'NONE'],
  'sys:audit': ['MANAGE', 'NONE', 'NONE', 'WRITE', 'NONE'],
}

register('GET', '/api/permissions/matrix', (_req, res) => {
  json(res, 0, {
    roles: mockRoles.map((r) => ({ id: r.id, code: r.code, name: r.name, builtin: r.builtin })),
    items: MATRIX_ITEMS,
    levels: MATRIX_DATA,
    updatedAt: '2026-09-11 14:02',
    updatedBy: '林工',
  })
})

register('PUT', '/api/permissions/matrix', async (req, res, ctx) => {
  void req
  await delay(420)
  const body = ctx.body as { changes: unknown[] }
  json(res, 0, { applied: body.changes?.length ?? 0, updatedAt: '2026-09-11 17:44', updatedBy: '林工' })
})

register('GET', '/api/kb/grants', (_req, res) => {
  const grants = mockKbs.map((k) => ({
    kbId: k.id,
    name: k.name,
    department: k.department,
    docCount: k.docCount,
    updatedAt: k.updatedAt,
    roles: k.id === 3 || k.id === 4
      ? [{ code: 'SUPER_ADMIN', name: '超级管理员' }, { code: 'KB_ADMIN', name: '知识库管理员' }, { code: 'AUDITOR', name: '审计员' }]
      : [{ code: 'SUPER_ADMIN', name: '超级管理员' }, { code: 'KB_ADMIN', name: '知识库管理员' }, { code: 'USER', name: '普通用户' }],
    level: k.id === 3 || k.id === 4 ? 'MANAGE' : 'UPLOAD',
  }))
  json(res, 0, { list: grants, total: grants.length, page: 1, pageSize: grants.length })
})

register('GET', '/api/kb/:id/grants', (_req, res, ctx) => {
  const kb = mockKbs.find((k) => String(k.id) === ctx.params.id) ?? mockKbs[0]!
  json(res, 0, {
    kbId: kb.id,
    name: kb.name,
    department: kb.department,
    docCount: kb.docCount,
    roles: mockRoles.map((r) => ({
      roleId: r.id,
      name: r.name,
      memberCount: r.memberCount,
      granted: r.id === 1 || r.id === 4,
    })),
    level: 'MANAGE',
    extraUsers: [
      { userId: 4, name: '李娜' },
      { userId: 5, name: '王芳' },
    ],
  })
})

register('PUT', '/api/kb/:id/grants', async (req, res) => {
  void req
  await delay(380)
  json(res, 0, { saved: true })
})

// ============ 系统管理 · 个人信息 ============
register('PUT', '/api/user/profile', async (req, res, ctx) => {
  void req
  await delay(300)
  const u = allUsers.find((x) => x.id === 2) ?? allUsers[1]!
  const body = ctx.body as Record<string, unknown>
  if (body.realName) u.realName = String(body.realName)
  if (body.position !== undefined) u.position = String(body.position)
  if (body.intro !== undefined) u.intro = String(body.intro)
  json(res, 0, { ...userToRow(u), position: u.position, intro: u.intro, savedAt: '2026-09-11 17:44' })
})

register('POST', '/api/user/avatar', async (req, res) => {
  void req
  await delay(400)
  json(res, 0, { url: '/mock-avatar/lin.gong.png' })
})

register('GET', '/api/user/permissions', (_req, res) => {
  json(res, 0, { codes: ALL_PERMISSION_CODES })
})

register('POST', '/api/user/change-phone', async (req, res) => {
  void req
  await delay(300)
  json(res, 0, { changed: true, phone: '138****6666' })
})

register('POST', '/api/user/change-email', async (req, res) => {
  void req
  await delay(300)
  json(res, 0, { changed: true, emailVerified: false })
})

register('POST', '/api/user/password', async (req, res) => {
  void req
  await delay(300)
  json(res, 0, { changed: true, reloginRequired: true })
})

// ============ 系统管理 · 模型配置（V2：四类 · 多配置 · 可切换） ============
const MODEL_TYPE_LABEL: Record<ModelType, string> = {
  CHAT: '对话模型',
  EMBEDDING: '嵌入模型',
  RERANK: '重排模型',
  VL: 'VL 视觉模型',
}

const modelStore = {
  chat: mockChatModels.map((m) => ({ ...m })),
  embedding: { ...mockEmbeddingModel },
  rerank: mockRerankModels.map((m) => ({ ...m })),
  vl: mockVlModels.map((m) => ({ ...m })),
}

/** 四类模型摊平（便于按 id 查找） */
function allModels(): ModelConfigItem[] {
  return [...modelStore.chat, modelStore.embedding, ...modelStore.rerank, ...modelStore.vl]
}

/** 某类型的可变列表（EMBEDDING 为单例，返回 null） */
function listOf(type: ModelType): ModelConfigItem[] | null {
  if (type === 'CHAT') return modelStore.chat
  if (type === 'RERANK') return modelStore.rerank
  if (type === 'VL') return modelStore.vl
  return null
}

function modelStats() {
  const def = modelStore.chat.find((m) => m.isActive)
  return {
    chatCount: modelStore.chat.length,
    rerankCount: modelStore.rerank.length,
    vlCount: modelStore.vl.length,
    defaultChatName: def?.displayName ?? '未配置',
    defaultChatOk: def?.lastTestOk ?? false,
    // 与「按模型拆分」合计一致：405.2 万
    monthTokensWan: 405.2,
  }
}

/** 测试延迟基准（按类型） */
function latencyOf(type: ModelType) {
  return type === 'CHAT' ? 420 : type === 'RERANK' ? 288 : type === 'VL' ? 1760 : 86
}

/** 当前启用的 VL 模型（供知识库配置页只读展示） */
function activeVlModel() {
  const v = modelStore.vl.find((m) => m.isActive)
  return { name: v?.displayName ?? '未配置', enabled: !!v, appliedAt: '2026-09-11 16:20' }
}

register('GET', '/api/system/model/config', (_req, res) => {
  json(res, 0, {
    stats: modelStats(),
    chat: modelStore.chat,
    embedding: modelStore.embedding,
    rerank: modelStore.rerank,
    vl: modelStore.vl,
  })
})

register('POST', '/api/system/model/config', async (req, res, ctx) => {
  void req
  await delay(360)
  const body = ctx.body as Record<string, unknown>
  const type = String(body.modelType ?? 'CHAT') as ModelType
  const list = listOf(type)
  if (!list) {
    json(res, 4091, null, '嵌入模型为全局单例，不支持新增')
    return
  }
  const id = Math.max(0, ...allModels().map((m) => m.id)) + 1
  const item: ModelConfigItem = {
    id,
    modelType: type,
    displayName: String(body.displayName ?? '新模型'),
    protocol: String(body.protocol ?? 'OpenAI 兼容'),
    baseUrl: String(body.baseUrl ?? ''),
    apiKeyMasked: 'sk-••••••••••••••••••••0000',
    modelName: String(body.modelName ?? ''),
    params: { temperature: 0.3, maxTokens: 2048, timeoutSec: 60, retries: 2 },
    capabilities: {
      stream: body.stream !== false,
      vision: body.vision === true,
      contextWindow: Number(body.contextWindow ?? 128000),
      supportSystemRole: body.supportSystemRole !== false,
    },
    billingUnit: (body.billingUnit as ModelConfigItem['billingUnit']) ?? 'PER_TOKEN',
    inputPrice: body.inputPrice == null ? null : Number(body.inputPrice),
    outputPrice: body.outputPrice == null ? null : Number(body.outputPrice),
    perCallPrice: body.perCallPrice == null ? null : Number(body.perCallPrice),
    currency: 'CNY',
    isActive: false,
    sortOrder: list.length + 1,
    lastTestOk: false,
    note: String(body.note ?? ''),
  }
  list.push(item)
  json(res, 0, { id: item.id, saved: true })
})

register('PUT', '/api/system/model/config/:id', async (req, res, ctx) => {
  void req
  await delay(380)
  const item = allModels().find((m) => m.id === Number(ctx.params.id))
  if (!item) {
    json(res, 4040, null, '模型不存在')
    return
  }
  Object.assign(item, ctx.body, { id: item.id, modelType: item.modelType })
  // 改了 Base URL / Key / 模型名 → 原测试结论失效，必须重测才能再次启用
  item.lastTestOk = false
  json(res, 0, { id: item.id, saved: true, needRetest: true })
})

register('DELETE', '/api/system/model/config/:id', async (req, res, ctx) => {
  void req
  await delay(300)
  const item = allModels().find((m) => m.id === Number(ctx.params.id))
  if (!item) {
    json(res, 4040, null, '模型不存在')
    return
  }
  if (item.isActive) {
    json(res, 4090, null, '当前启用的模型不可删除')
    return
  }
  const list = listOf(item.modelType)
  if (!list) {
    json(res, 4091, null, '嵌入模型为单例，不可删除')
    return
  }
  const idx = list.indexOf(item)
  if (idx >= 0) list.splice(idx, 1)
  json(res, 0, { id: item.id, deleted: true })
})

register('POST', '/api/system/model/config/:id/activate', async (req, res, ctx) => {
  void req
  await delay(420)
  const item = allModels().find((m) => m.id === Number(ctx.params.id))
  if (!item) {
    json(res, 4040, null, '模型不存在')
    return
  }
  if (!item.lastTestOk) {
    json(res, 4092, null, '请先通过连接测试再启用')
    return
  }
  allModels()
    .filter((m) => m.modelType === item.modelType)
    .forEach((m) => (m.isActive = false))
  item.isActive = true
  json(res, 0, {
    id: item.id,
    activated: true,
    modelType: item.modelType,
    needRebuildImageDocs: item.modelType === 'VL',
    needReindex: item.modelType === 'EMBEDDING',
  })
})

register('POST', '/api/system/model/config/:id/test', async (req, res, ctx) => {
  void req
  await delay(650)
  const item = allModels().find((m) => m.id === Number(ctx.params.id))
  if (!item) {
    json(res, 4040, null, '模型不存在')
    return
  }
  item.lastTestOk = true
  item.lastTestAt = new Date().toLocaleString('zh-CN', { hour12: false })
  item.lastLatencyMs = latencyOf(item.modelType)
  json(res, 0, {
    id: item.id,
    ok: true,
    latencyMs: item.lastLatencyMs,
    message: `${MODEL_TYPE_LABEL[item.modelType]}连接正常`,
  })
})

register('GET', '/api/system/model/options', (_req, res, ctx) => {
  const type = (ctx.query.type ?? 'CHAT') as ModelType
  const list = (listOf(type) ?? [])
    .filter((m) => m.lastTestOk)
    .map((m) => ({ id: m.id, displayName: m.displayName, modelName: m.modelName, isActive: m.isActive, capabilities: m.capabilities }))
  json(res, 0, { list })
})

register('GET', '/api/system/model/usage', (_req, res) => {
  const byModel = mockUsageByModel
  const priced = byModel.filter((m) => m.cost != null)
  const cost = priced.length ? Math.round(priced.reduce((s, m) => s + (m.cost ?? 0), 0) * 10) / 10 : null
  const unpricedCount = byModel.filter((m) => m.billingUnit !== 'NONE' && m.cost == null).length
  json(res, 0, {
    calls: byModel.reduce((s, m) => s + m.calls, 0),
    tokens: byModel.reduce((s, m) => s + m.tokens, 0),
    cost,
    currency: 'CNY',
    unpricedCount,
    avgLatencySec: 1.6,
    // 按用途分布：合计必须等于 byModel 的 tokens 合计（4,052,000 = 405.2 万）
    // 比例固定 48/31/15/6（索引侧 摘要+HyQ = 63%）
    byPurpose: [
      { name: '文档级摘要 + 内容标签', tokens: 1945000, percent: 48 },
      { name: '问答生成', tokens: 1256000, percent: 31 },
      { name: 'HyQ 假设性问题生成', tokens: 608000, percent: 15 },
      { name: '文档向量化（嵌入）', tokens: 243000, percent: 6 },
    ],
    byModel,
  })
})

register('GET', '/api/system/model/embedding-impact', (_req, res) => {
  json(res, 0, { kbCount: 8, docCount: 1246, chunkCount: 38502, estimateMinutes: 42, extraTokens: 11800000 })
})

register('POST', '/api/system/model/change-embedding', async (req, res, ctx) => {
  void req
  await delay(500)
  const body = ctx.body as Record<string, unknown>
  json(res, 0, { taskId: `rebuild_${Date.now()}`, model: body.model, rebuildMode: body.rebuildMode })
})

register('GET', '/api/system/model/vl-impact', (_req, res) => {
  json(res, 0, mockVlImpact)
})

register('POST', '/api/system/model/rebuild-image-docs', async (req, res) => {
  void req
  await delay(460)
  json(res, 0, {
    taskId: `rebuild_img_${Date.now()}`,
    imageDocCount: mockVlImpact.imageDocCount,
    imageCount: mockVlImpact.imageCount,
    status: 'RUNNING',
  })
})

// ============ 系统管理 · 审计日志 ============
const AUDIT_LOGS = [
  { id: 8825, time: '2026-09-11 16:48:22', operator: 'lin.gong', operatorName: '林工', module: '系统', action: '切换重排模型', riskLevel: 'HIGH', target: 'Rerank：gte-rerank → bge-reranker-v2-m3', ip: '10.8.3.21', result: 'SUCCESS' },
  { id: 8824, time: '2026-09-11 16:12:05', operator: 'lin.gong', operatorName: '林工', module: '系统', action: '启用对话模型', riskLevel: 'NORMAL', target: 'DeepSeek-V3（设为默认对话模型）', ip: '10.8.3.21', result: 'SUCCESS' },
  { id: 8823, time: '2026-09-11 15:40:31', operator: 'lin.gong', operatorName: '林工', module: '系统', action: '新增 VL 模型', riskLevel: 'NORMAL', target: 'Qwen2.5-VL-7B', ip: '10.8.3.21', result: 'FAILED', failReason: '连接测试失败：401 Unauthorized' },
  { id: 8822, time: '2026-09-11 11:58:47', operator: 'lin.gong', operatorName: '林工', module: '系统', action: '更换嵌入模型', riskLevel: 'HIGH', target: 'bge-large-zh-v1.5 → bge-m3（触发全库重建 5 个知识库）', ip: '10.8.3.21', result: 'SUCCESS' },
  { id: 8821, time: '2026-09-11 15:12:04', operator: 'lin.gong', operatorName: '林工', module: '授权', action: '修改角色权限', riskLevel: 'HIGH', target: '角色「知识库管理员」', ip: '10.8.3.21', result: 'SUCCESS' },
  { id: 8820, time: '2026-09-11 14:20:33', operator: 'lin.gong', operatorName: '林工', module: '知识库', action: '创建知识库', riskLevel: 'NORMAL', target: '客户合同库', ip: '10.8.3.21', result: 'SUCCESS' },
  { id: 8819, time: '2026-09-11 11:30:17', operator: 'zhang.wei', operatorName: '张伟', module: '文档', action: '上传文档', riskLevel: 'NORMAL', target: '接口鉴权设计说明.docx', ip: '10.8.4.15', result: 'SUCCESS' },
  { id: 8818, time: '2026-09-11 10:05:52', operator: 'li.na', operatorName: '李娜', module: '文档', action: '下载文档', riskLevel: 'HIGH', target: '客户合同模板 v3.docx', ip: '10.8.7.32', result: 'SUCCESS' },
  { id: 8817, time: '2026-09-10 17:45:09', operator: 'li.na', operatorName: '李娜', module: '问答', action: '导出会话记录', riskLevel: 'HIGH', target: '会话「客户续约流程」', ip: '10.8.7.32', result: 'SUCCESS' },
  { id: 8816, time: '2026-09-10 16:30:41', operator: 'zhang.wei', operatorName: '张伟', module: '文档', action: '删除文档', riskLevel: 'HIGH', target: '2024-旧版接口文档.pdf', ip: '10.8.5.22', result: 'SUCCESS' },
  { id: 8815, time: '2026-09-10 09:12:28', operator: 'lin.gong', operatorName: '林工', module: '系统', action: '修改模型配置', riskLevel: 'HIGH', target: '对话模型 Base URL', ip: '10.8.3.21', result: 'SUCCESS' },
  { id: 8814, time: '2026-09-10 09:02:10', operator: 'lin.gong', operatorName: '林工', module: '授权', action: '知识库授权变更', riskLevel: 'HIGH', target: '客户合同库', ip: '10.8.3.21', result: 'FAILED', failReason: '包含超管角色不可降级' },
  { id: 8813, time: '2026-09-09 18:43:55', operator: 'sun.jie', operatorName: '孙洁', module: '认证', action: '登录失败', riskLevel: 'NORMAL', target: '账号 sun.jie', ip: '10.9.2.14', result: 'FAILED', failReason: '密码错误（第 1 次）' },
  { id: 8812, time: '2026-09-09 15:33:41', operator: 'wang.fang', operatorName: '王芳', module: '文档', action: '重建索引', riskLevel: 'NORMAL', target: 'AI 知识库平台白皮书 V3', ip: '10.8.4.15', result: 'SUCCESS' },
]

register('GET', '/api/audit/logs', (_req, res, ctx) => {
  const page = Number(ctx.query.page ?? 1)
  const size = Number(ctx.query.size ?? 10)
  const { module, riskLevel, result, operator, keyword } = ctx.query
  let list = [...AUDIT_LOGS]
  if (module && module !== 'all') list = list.filter((l) => l.module === module)
  if (riskLevel && riskLevel !== 'all') list = list.filter((l) => l.riskLevel === riskLevel)
  if (result && result !== 'all') list = list.filter((l) => l.result === result)
  if (keyword) {
    const kw = String(keyword).toLowerCase()
    list = list.filter((l) => l.operatorName.toLowerCase().includes(kw) || l.operator.includes(kw) || l.target.includes(kw) || l.ip.includes(kw))
  }
  const start = (page - 1) * size
  json(res, 0, { list: list.slice(start, start + size), total: list.length, page, pageSize: size })
})

register('GET', '/api/audit/stats', (_req, res) => {
  json(res, 0, { today: 247, highRisk: 12, activeUsers: 34, failed: 3 })
})

register('GET', '/api/audit/logs/:id', (_req, res, ctx) => {
  const id = Number(ctx.params.id)
  const log = AUDIT_LOGS.find((l) => l.id === id) ?? AUDIT_LOGS[0]!
  const base: Record<string, unknown> = {
    ...log,
    logNo: `LOG-20260911-${String(log.id).padStart(7, '0')}`,
    traceId: 'a3f8c1e2b47d9e02',
    apiPath: log.module === '授权' ? 'PUT /api/permissions/matrix' : `${log.module === '用户' ? 'PUT' : 'POST'} /api/${log.module}/...`,
    costMs: 128,
    userAgent: 'Chrome 130 / Windows',
    affectedUsers: log.module === '授权' ? 12 : 1,
  }
  // 最热一条：修改角色权限带 diff
  if (id === 8821) {
    base.changes = [
      { label: '文档 · 上传文档', before: '只读', after: '读写' },
      { label: '文档 · 删除文档', before: '—', after: '读写' },
      { label: '文档 · 重建索引', before: '—', after: '读写' },
      { label: '知识库 · 删除知识库', before: '只读', after: '只读' },
    ]
    base.roleName = '知识库管理员'
    base.note = '本次变更使 12 名用户的权限范围扩大（新增「删除文档」与「重建索引」）。权限扩大属于风险动作，建议在授权变更后通知相关成员。'
  }
  // 更换嵌入模型：全库重建，属于高风险且不可即时回退
  if (id === 8822) {
    base.changes = [
      { label: '嵌入模型', before: 'bge-large-zh-v1.5', after: 'bge-m3' },
      { label: '向量维度', before: '768', after: '1024' },
      { label: '归一化', before: 'L2', after: 'L2' },
      { label: '受影响知识库', before: '5 个', after: '5 个（全部重建）' },
    ]
    base.affectedUsers = 0
    base.apiPath = 'POST /api/system/model/embedding/change'
    base.note = '嵌入模型为全局单例，更换会改变向量维度与语义空间，必须全库重建；重建期间旧向量不可用，完成前检索结果不保证一致。历史问答的引用不受影响。'
  }
  // 切换重排模型：切换前应先跑评测集对比 Recall@5
  if (id === 8825) {
    base.changes = [
      { label: '重排模型', before: 'gte-rerank', after: 'bge-reranker-v2-m3' },
      { label: 'Rerank Top-K', before: '20 → 5', after: '20 → 5' },
      { label: '评测 Recall@5', before: '0.86', after: '0.89' },
    ]
    base.affectedUsers = 0
    base.apiPath = 'PUT /api/system/model/config/7'
    base.note = '重排模型全局唯一，切换即时生效于所有知识库。切换前已用评测集对比 Recall@5（0.86 → 0.89），切换后可回滚到上一模型（gte-rerank 仍在列表中）。'
  }
  // 启用对话模型
  if (id === 8824) {
    base.changes = [
      { label: '默认对话模型', before: 'Qwen2.5-72B', after: 'DeepSeek-V3' },
      { label: '连接测试', before: '通过', after: '通过（延迟 620ms）' },
    ]
    base.affectedUsers = 0
    base.apiPath = 'POST /api/system/model/config/1/activate'
    base.note = '对话模型可配置多条，问答页可即时切换（切换即开新会话）。此项仅修改默认值，不影响进行中的会话。'
  }
  json(res, 0, base)
})

register('POST', '/api/audit/export', async (req, res) => {
  void req
  await delay(500)
  json(res, 0, { taskId: `export_${Date.now()}`, status: 'QUEUED', note: '导出完成后将通过通知中心提醒' })
})

// ============ 文档域（详情 / 预览 / 下载） ============
/** 块类型归组：正文 / 表格 / 摘要 */
function blockGroup(t: string): 'text' | 'table' | 'summary' {
  if (t === 'TABLE') return 'table'
  if (t === 'SUMMARY') return 'summary'
  return 'text'
}

register('GET', '/api/doc/:id/versions', (_req, res) => {
  json(res, 0, { current: 'v3', list: mockDocVersions })
})

register('POST', '/api/doc/:id/rollback', async (req, res, ctx) => {
  await delay(500)
  const version = String((ctx.body as { version?: string }).version ?? 'v2')
  json(res, 0, { docId: Number(ctx.params.id), version, rolledBack: true, note: '历史问答的引用不会改变' })
})

register('GET', '/api/doc/:id/index-tasks', (_req, res) => {
  json(res, 0, { list: mockIndexTasks, total: mockIndexTasks.length })
})

register('GET', '/api/doc/:id/chunk-stat', (_req, res) => {
  json(res, 0, mockChunkStat)
})

register('GET', '/api/doc/:id/toc', (_req, res) => {
  json(res, 0, { list: mockToc })
})

/** 已废弃：/api/doc/:id/chunks/stat 与 /api/doc/:id/chunk-stat 重复，统一用后者（见《10》§13） */
register('GET', '/api/doc/:id/chunks', (_req, res, ctx) => {
  const type = ctx.query.type ?? 'all'
  const list = type === 'all' ? mockChunks : mockChunks.filter((c) => blockGroup(c.blockType) === type)
  json(res, 0, { list, total: mockChunkStat.total, stat: mockChunkStat })
})

/** 父块完整内容 + 其子块 */
register('GET', '/api/doc/:id/chunks/:parentId', (_req, res) => {
  json(res, 0, mockParentChunk)
})

register('PATCH', '/api/doc/:id/attributes', async (req, res, ctx) => {
  await delay(400)
  const body = ctx.body as { secretLevel?: string; kbId?: number; tags?: string[] }
  json(res, 0, {
    saved: true,
    docId: Number(ctx.params.id),
    needReindex: body.kbId != null,
    attributes: body,
  })
})

register('GET', '/api/doc/download-url/:docId', (_req, res, ctx) => {
  const now = Date.now()
  json(res, 0, {
    url: `/mock-files/doc-${ctx.params.docId}.pdf`,
    expiresAt: new Date(now + 10 * 60_000).toISOString(),
    expiresInSec: 600,
  })
})

register('POST', '/api/doc/batch-download', async (req, res, ctx) => {
  await delay(400)
  const ids = (ctx.body as { docIds?: number[] }).docIds ?? []
  json(res, 0, { taskId: `dl_${Date.now()}`, count: ids.length, status: 'PACKING' })
})

register('GET', '/api/doc/download-tasks/:taskId', (_req, res, ctx) => {
  const task = mockDownloadTasks.find((t) => t.taskId === ctx.params.taskId) ?? mockDownloadTasks[0]!
  json(res, 0, task)
})

register('GET', '/api/doc/download-records', (_req, res, ctx) => {
  const page = Number(ctx.query.page ?? 1)
  const pageSize = Number(ctx.query.pageSize ?? 10)
  const start = (page - 1) * pageSize
  json(res, 0, { list: mockDownloadRecords.slice(start, start + pageSize), total: mockDownloadRecords.length })
})

/** 跨库文档列表（全站口径 1,246） */
register('GET', '/api/doc/all', (_req, res, ctx) => {
  const rows = allDocsAcross()
  const kw = (ctx.query.keyword ?? '').trim().toLowerCase()
  const kbId = ctx.query.kbId ?? ''
  const type = ctx.query.type ?? ''
  let list = rows
  if (kw) list = list.filter((d) => d.title.toLowerCase().includes(kw) || d.owner.toLowerCase().includes(kw))
  if (kbId && kbId !== 'all') list = list.filter((d) => String(d.kbId) === String(kbId))
  if (type && type !== 'all') list = list.filter((d) => d.type === type)
  json(res, 0, { list, total: mockStorageQuota.docCount })
})

/** 下载任务列表 */
register('GET', '/api/doc/download-tasks', (_req, res) => {
  json(res, 0, { list: mockDownloadTasks, total: mockDownloadTasks.length })
})

register('GET', '/api/kb/storage-quota', (_req, res) => {
  json(res, 0, mockStorageQuota)
})

register('POST', '/api/doc/export-list', async (req, res) => {
  void req
  await delay(400)
  json(res, 0, { taskId: `export_${Date.now()}`, status: 'QUEUED', note: '导出完成后将通过通知中心提醒' })
})

/**
 * 文档元信息（GET /api/doc/{id}，路径与《10-接口约定汇总》一致）
 * ⚠️ 必须注册在所有 3 段字面路由（/doc/all、/doc/download-tasks、/doc/download-records 等）之后：
 *    mock 路由首匹配胜出，否则 `:id` 会把 'all' / 'download-tasks' 当成文档 ID 吃掉
 */
register('GET', '/api/doc/:id', (_req, res, ctx) => {
  json(res, 0, docMetaOf(Number(ctx.params.id)))
})

// ============ 运营分析 ============
/** 未命中清单内存态（支持状态流转与重放验证） */
const missStore = mockMissQuestions.map((m) => ({ ...m }))
/** 待处理计数（初始 23，随状态流转增减；同步侧边栏角标） */
let pendingCount = mockQaOverview.pending
void MISS_STATUS_LABEL

register('GET', '/api/ops/qa/overview', (_req, res) => {
  json(res, 0, { ...mockQaOverview, pending: pendingCount })
})

register('GET', '/api/ops/qa/misses', (_req, res, ctx) => {
  const status = ctx.query.status ?? ''
  const kbId = ctx.query.kbId ?? ''
  const keyword = (ctx.query.keyword ?? '').trim().toLowerCase()
  let list = missStore
  if (status && status !== 'all') list = list.filter((m) => m.status === status)
  if (kbId && kbId !== 'all') list = list.filter((m) => m.kbName === kbId)
  if (keyword) list = list.filter((m) => m.question.toLowerCase().includes(keyword))
  // 默认按提问次数降序：把「很多人问但答不上来」排在最前
  list = [...list].sort((a, b) => b.askCount - a.askCount)
  json(res, 0, { list, total: mockQaOverview.missed })
})

register('PATCH', '/api/ops/qa/misses/:id', async (req, res, ctx) => {
  await delay(300)
  const id = Number(ctx.params.id)
  const target = missStore.find((m) => m.id === id)
  const status = (ctx.body as { status?: string }).status ?? 'PENDING'
  if (target) {
    const wasPending = target.status === 'PENDING'
    const willPending = status === 'PENDING'
    if (wasPending && !willPending) pendingCount = Math.max(0, pendingCount - 1)
    if (!wasPending && willPending) pendingCount += 1
    target.status = status as typeof target.status
    if (status === 'IGNORED') target.ignoredBy = '系统管理员'
  }
  json(res, 0, { id, status, pending: pendingCount })
})

/** 重放验证（闭环第 4 步）：同时校验分数提升 + 召回的是补录文档 */
register('POST', '/api/ops/qa/misses/:id/replay', async (req, res, ctx) => {
  void req
  await delay(900)
  const id = Number(ctx.params.id)
  const target = missStore.find((m) => m.id === id)
  const before = target?.topScore ?? 0.3
  const improved = Math.random() > 0.25
  const after = improved ? Math.min(0.92, before + 0.35 + Math.random() * 0.15) : before + 0.02
  if (target) {
    const wasPending = target.status === 'PENDING'
    if (improved) {
      if (wasPending) pendingCount = Math.max(0, pendingCount - 1)
      target.status = 'RESOLVED'
      target.scoreAfter = Number(after.toFixed(2))
    } else {
      target.status = 'PENDING'
    }
  }
  json(res, 0, {
    before,
    after: Number(after.toFixed(2)),
    improved,
    hitSupplementedDoc: improved,
    note: improved
      ? '召回分提升且命中的是刚补录的文档，可标记已解决'
      : '分数未达标或召回的不是补录文档，已退回待处理',
  })
})

register('GET', '/api/ops/qa/top-questions', (_req, res, ctx) => {
  const limit = Number(ctx.query.limit ?? 20)
  json(res, 0, { list: mockTopQuestions.slice(0, limit), total: mockTopQuestions.length })
})

register('GET', '/api/ops/qa/feedback-stats', (_req, res) => {
  json(res, 0, mockFeedbackStats)
})

register('POST', '/api/ops/qa/export', async (req, res) => {
  void req
  await delay(500)
  json(res, 0, { taskId: `export_${Date.now()}`, status: 'QUEUED', note: '导出完成后将通过通知中心提醒' })
})

register('GET', '/api/ops/retrieval/overview', (_req, res) => {
  json(res, 0, mockRetrievalOverview)
})

register('GET', '/api/ops/retrieval/pipeline', (_req, res) => {
  const retrievalSide = mockPipeline.slice(0, 5).reduce((s, p) => s + p.costMs, 0)
  const total = retrievalSide + (mockPipeline[5]?.costMs ?? 0)
  json(res, 0, { list: mockPipeline, retrievalSideMs: retrievalSide, totalMs: total })
})

register('GET', '/api/ops/retrieval/score-distribution', (_req, res) => {
  const count = mockScoreDistribution.reduce((s, b) => s + b.count, 0)
  json(res, 0, { list: mockScoreDistribution, total: count })
})

register('GET', '/api/ops/retrieval/rerank-effect', (_req, res) => {
  json(res, 0, mockRerankEffect)
})

register('GET', '/api/ops/retrieval/kb-quality', (_req, res) => {
  const questions = mockKbQuality.reduce((s, k) => s + k.questions, 0)
  json(res, 0, { list: mockKbQuality, questions })
})

register('GET', '/api/ops/usage/overview', (_req, res) => {
  json(res, 0, mockUsageOverview)
})

register('GET', '/api/ops/usage/trend', (_req, res) => {
  const total = mockTrend.reduce((s, t) => s + t.value, 0)
  json(res, 0, { list: mockTrend, total })
})

register('GET', '/api/ops/usage/by-dept', (_req, res) => {
  const active = mockDeptUsage.reduce((s, d) => s + d.activeUsers, 0)
  const users = mockDeptUsage.reduce((s, d) => s + d.totalUsers, 0)
  const questions = mockDeptUsage.reduce((s, d) => s + d.questions, 0)
  json(res, 0, { list: mockDeptUsage, active, users, questions })
})

register('GET', '/api/ops/usage/by-kb', (_req, res) => {
  const questions = mockKbHeat.reduce((s, k) => s + k.questions, 0)
  json(res, 0, { list: mockKbHeat, questions })
})

register('GET', '/api/ops/usage/top-users', (_req, res, ctx) => {
  const limit = Number(ctx.query.limit ?? 10)
  json(res, 0, { list: mockTopUsers.slice(0, limit), total: mockTopUsers.length })
})

// ============ 通用（通知中心 / 回收站） ============
const noticeStore = mockNotices.map((n) => ({ ...n }))
const recycleDocStore = mockRecycleDocs.map((r) => ({ ...r }))
const recycleKbStore = mockRecycleKbs.map((r) => ({ ...r }))
void RECYCLE_KEEP_DAYS
void mockNoticeCounts

register('GET', '/api/notices', (_req, res, ctx) => {
  const type = ctx.query.type ?? 'all'
  const read = ctx.query.read ?? 'all'
  let list = noticeStore
  if (type !== 'all') list = list.filter((n) => n.type === type)
  if (read === 'unread') list = list.filter((n) => !n.read)
  if (read === 'read') list = list.filter((n) => n.read)
  const unread = noticeStore.filter((n) => !n.read).length
  json(res, 0, {
    list,
    total: noticeStore.length,
    counts: {
      all: noticeStore.length,
      unread,
      UPLOAD: noticeStore.filter((n) => n.type === 'UPLOAD').length,
      ALERT: noticeStore.filter((n) => n.type === 'ALERT').length,
      PERMISSION: noticeStore.filter((n) => n.type === 'PERMISSION').length,
      SYSTEM: noticeStore.filter((n) => n.type === 'SYSTEM').length,
    },
  })
})

register('GET', '/api/notices/unread-count', (_req, res) => {
  json(res, 0, { count: noticeStore.filter((n) => !n.read).length })
})

register('GET', '/api/notices/pending', (_req, res) => {
  const total = mockPendingItems.reduce((s, p) => s + p.count, 0)
  json(res, 0, { list: mockPendingItems, total })
})

register('GET', '/api/notices/settings', (_req, res) => {
  json(res, 0, { list: mockNoticeSettings })
})

register('PUT', '/api/notices/settings', async (req, res, ctx) => {
  await delay(300)
  const body = ctx.body as { list?: { type: string; enabled: boolean }[] }
  // 系统告警强制开启：忽略对 ALERT 的关闭请求
  const applied = (body.list ?? []).filter((x) => x.type !== 'ALERT' || x.enabled)
  json(res, 0, { saved: true, applied, note: '系统告警为强制开启，不受设置影响' })
})

register('POST', '/api/notices/read-all', async (req, res) => {
  void req
  await delay(300)
  noticeStore.forEach((n) => (n.read = true))
  json(res, 0, { updated: noticeStore.length, unread: 0 })
})

register('PATCH', '/api/notices/:id/read', async (req, res, ctx) => {
  await delay(200)
  const id = Number(ctx.params.id)
  const target = noticeStore.find((n) => n.id === id)
  const read = (ctx.body as { read?: boolean }).read ?? true
  if (target) target.read = read
  json(res, 0, { id, read, unread: noticeStore.filter((n) => !n.read).length })
})

register('GET', '/api/recycle/items', (_req, res, ctx) => {
  const type = ctx.query.type ?? 'DOC'
  const list = type === 'KB' ? recycleKbStore : recycleDocStore
  json(res, 0, { list, total: list.length, keepDays: RECYCLE_KEEP_DAYS })
})

register('POST', '/api/recycle/restore', async (req, res, ctx) => {
  await delay(600)
  const body = ctx.body as { ids?: number[]; targetKbId?: number }
  const ids = body.ids ?? []
  const all = [...recycleDocStore, ...recycleKbStore]
  const items = all.filter((r) => ids.includes(r.id))
  const affectedDocs = items.reduce((s, r) => s + (r.type === 'KB' ? (r.docCount ?? 0) : 1), 0)
  // 从回收站移除
  for (const id of ids) {
    const di = recycleDocStore.findIndex((r) => r.id === id)
    if (di >= 0) recycleDocStore.splice(di, 1)
    const ki = recycleKbStore.findIndex((r) => r.id === id)
    if (ki >= 0) recycleKbStore.splice(ki, 1)
  }
  json(res, 0, {
    restored: items.length,
    needReindex: true,
    affectedDocs,
    targetKbId: body.targetKbId ?? null,
    note: '恢复后需重建索引才能参与检索',
  })
})

register('DELETE', '/api/recycle/items', async (req, res, ctx) => {
  await delay(500)
  const ids = (ctx.body as { ids?: number[] }).ids ?? []
  for (const id of ids) {
    const di = recycleDocStore.findIndex((r) => r.id === id)
    if (di >= 0) recycleDocStore.splice(di, 1)
    const ki = recycleKbStore.findIndex((r) => r.id === id)
    if (ki >= 0) recycleKbStore.splice(ki, 1)
  }
  json(res, 0, { deleted: ids.length })
})

register('DELETE', '/api/recycle/all', async (req, res) => {
  void req
  await delay(600)
  const deleted = recycleDocStore.length + recycleKbStore.length
  recycleDocStore.length = 0
  recycleKbStore.length = 0
  json(res, 0, { cleared: deleted })
})

register('POST', '/api/recycle/cleanup', async (req, res) => {
  void req
  await delay(400)
  const expired = [...recycleDocStore, ...recycleKbStore].filter((r) => r.remainDays <= 0).length
  json(res, 0, { cleaned: expired, note: '定时任务：清理超期项（含向量数据），需幂等且失败告警' })
})

// ---------------------------------------------------------------- 插件入口
export function mockPlugin() {
  if (!USE_MOCK) return { name: 'ai-knowledge-mock-disabled', apply: 'serve' as const }
  return {
    name: 'ai-knowledge-mock',
    apply: 'serve' as const,
    configureServer(server: ViteDevServer) {
      server.middlewares.use('/api', (req, res, next) => {
        const url = req.url ?? '/'
        const [path, queryStr] = url.split('?')
        const query: Record<string, string> = {}
        if (queryStr) {
          for (const pair of queryStr.split('&')) {
            const [k, v] = pair.split('=')
            if (k) query[k] = decodeURIComponent(v ?? '')
          }
        }
        const method = (req.method ?? 'GET').toUpperCase()
        void (async () => {
          const body = await readBody(req)
          for (const r of routes) {
            const m = r.pattern.exec(path ?? '')
            if (m && r.method === method) {
              const params: Record<string, string> = {}
              r.paramNames.forEach((name, i) => {
                params[name] = m[i + 1] ?? ''
              })
              try {
                await r.handler(req, res, { query, body, params, method: r.method })
              } catch (e) {
                json(res, 500, null, String(e))
              }
              return
            }
          }
          next()
        })()
      })
    },
  }
}

// 供类型引用，避免未使用告警
export type { Handler }
void sessions