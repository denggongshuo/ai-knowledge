/** 全站共享 TypeScript 类型定义（与需求文档 / 接口约定对齐） */

/** 用户状态 */
export type UserStatus = 'ACTIVE' | 'PENDING' | 'DISABLED'

/** 用户实体 */
export interface UserInfo {
  id: number
  /** 登录标识，全站统一用用户名（决策 1） */
  username: string
  realName: string
  phone: string
  email?: string
  /** 所属部门（比较基准，数据范围类型由角色的 dataScope 决定） */
  department: string
  /** 部门树节点 id（未分配 = 0，仅展示不含子节点的行） */
  deptId: number
  roles: string[]
  status: UserStatus
  createdAt: string
  lastLoginAt?: string
  /** 重置密码后为 true，强制下次登录修改 */
  mustChangePassword?: boolean
  hasBusinessData?: boolean
  /** 职位（个人信息页可编辑） */
  position?: string
  /** 个人简介（个人信息页可编辑） */
  intro?: string
}

/** 数据范围类型（角色属性，部门仅作比较基准） */
export type DataScope = 'ALL' | 'DEPT_AND_CHILD' | 'DEPT' | 'SELF' | 'CUSTOM'

/** 角色 */
export interface RoleInfo {
  id: number
  name: string
  code: string
  description: string
  memberCount: number
  dataScope: DataScope
  permissions: string[]
  enabled: boolean
  /** true = 内置角色（不可删除） */
  builtin: boolean
  createdAt: string
}

/** 部门树节点 */
export interface DeptNode {
  id: number
  name: string
  parentId: number | null
  /** 该节点（含子节点）的用户数 */
  userCount: number
  children?: DeptNode[]
}

/** 权限树节点（38 个原子权限码，按 4 大业务域分组） */
export interface PermissionNode {
  /** 叶节点为权限码，父节点为分组编码 */
  id: string
  label: string
  /** 仅叶子节点有值 */
  code?: string
  children?: PermissionNode[]
}

/** 授权矩阵档位：管理 / 读写 / 只读 / 无 */
export type GrantLevel = 'MANAGE' | 'WRITE' | 'READ' | 'NONE'

/** 知识库授权级别 */
export type KbGrantLevel = 'NONE' | 'READ' | 'UPLOAD' | 'MANAGE'

/** 知识库 / 知识库 */
export interface KnowledgeBase {
  id: number
  name: string
  description: string
  owner: string
  department: string
  docCount: number
  chunkCount: number
  status: 'ACTIVE' | 'BUILDING' | 'PAUSED'
  createdAt: string
  updatedAt: string
}

/** 文档 */
export interface DocInfo {
  id: number
  kbId: number
  kbName: string
  title: string
  path: string
  type: string
  size: number
  pageCount: number
  chunkCount: number
  status: 'UPLOADED' | 'PARSING' | 'INDEXING' | 'READY' | 'FAILED'
  securityLevel: SecretLevel
  owner: string
  uploadedAt: string
  version: string
}

/** 分片 */
export interface ChunkInfo {
  id: number
  docId: number
  docTitle: string
  titlePath: string
  blockType: 'PARAGRAPH' | 'HEADING' | 'TABLE' | 'CODE' | 'LIST' | 'SUMMARY' | 'HYPOTHESIS_QA' | 'IMAGE'
  index: number
  content: string
  tokens: number
  /** 顶层块（正文/标题/代码/列表/图片/表格父块/摘要）为 `null`；子块指向 `blockType = TABLE` 的父块 id。**禁止用 `0` 表示"无父块"** */
  parentId?: number | null
  childrenCount?: number
}

/** 检索链路耗时（对齐数据字典：12+86+34+8+288+1240=1668ms） */
export interface RetrievalTrace {
  queryAnalysis: number // 12ms 查询解析
  embed: number // 86ms 向量化
  vectorRecall: number // 34ms 向量召回
  bm25Recall: number // 8ms 关键词召回
  rerank: number // 288ms Rerank
  llmGeneration: number // 1240ms 生成
  totalMs: number // 1668ms
  stages: { name: string; costMs: number }[]
  chunks: { chunkId: number; docId: number; docTitle: string; score: number; source: 'vector' | 'bm25' | 'graph' }[]
  graphUsed?: boolean
  graphCostMs?: number
  graphFallback?: boolean
}

/** 聊天消息 */
export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  /** SSE 是否仍在生成 */
  streaming?: boolean
  citations?: Citation[]
  trace?: RetrievalTrace
  createdAt: number
  /** 该轮回答使用的对话模型（落库 usedModelId / 名称，用于归因） */
  modelId?: number
  modelName?: string
  /** 该轮附带的图片（含 VL 描述与理解进度） */
  attachments?: ChatAttachment[]
  /** 是否把原图直接传给了对话模型（「直接读原图」兜底，仅该轮生效） */
  usedVision?: boolean
  /**
   * 当前反馈：`like` / `unlike`；`null` = **从未反馈**（与会话列表里可空的 `modelId` 同一口径）。
   *
   * <p>⚠️ 与后端那三列一对一 —— 它表示的是**当前值**，不是"我这次点了什么"。
   * 用户改主意时是覆盖，前端据此把高亮跟着换过去。
   */
  feedback?: 'like' | 'unlike' | null
  /** 点踩原因（`ChatFeedbackReason` 的 5 个中文标签之一）；点赞或未反馈时为 `null` */
  feedbackReason?: string | null
}

/** 点赞 / 点踩的原因选项（**必须与后端 `ChatFeedbackReason` 逐字一致**） */
export const FEEDBACK_REASONS = [
  '答案不准确',
  '没找到相关内容',
  '引用来源不对',
  '回答太啰嗦',
  '其他',
] as const

/** 图片附件（V2 新增：提问传图 → VL 生成描述） */
export interface ChatAttachment {
  id: string
  type: 'IMAGE'
  name: string
  /** 本地预览 / 服务端回看 URL */
  url?: string
  size: number
  md5: string
  mime?: string
  /** 完整结构化描述（desc） */
  desc?: string
  /** 检索用摘要（关键词密集） */
  searchSummary?: string
  /** 生成描述的 VL 模型名 */
  vlModelName?: string
  /** 是否降级（VL 未配置 / 调用失败，由备用多模态模型完成） */
  degraded?: boolean
  /** 理解进度 */
  stage?: 'UPLOADING' | 'DESCRIBING' | 'DONE' | 'FAILED'
  /** 上传进度 0~100 */
  progress?: number
}

/** 提问附件上传响应（只落存储 + 落库，不调 VL） */
export interface AttachmentUploadResult {
  attachmentId: string
  presignedUrl: string
  md5: string
  size: number
  mime: string
}

/** 会话列表项（V2：显示所用模型） */
export interface ChatSessionItem {
  id: string
  title: string
  time?: string
  count: number
  modelId?: number
  modelName?: string
}

/** 引用角标 */
export interface Citation {
  index: number
  docId: number
  docTitle: string
  chunkId: number
  source: 'vector' | 'bm25' | 'graph'
  score: number
}

/** 分页响应 */
export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  pageSize: number
}

export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
}

// ==================== 系统管理模块 ====================

/** 审计日志 */
export interface AuditLog {
  id: number
  time: string
  operator: string
  operatorName: string
  module: string
  action: string
  riskLevel: 'HIGH' | 'NORMAL'
  target: string
  ip: string
  result: 'SUCCESS' | 'FAILED'
  failReason?: string
}

/** 审计日志详情（含 diff 对照） */
export interface AuditLogDetail extends AuditLog {
  logNo: string
  traceId: string
  apiPath: string
  costMs: number
  userAgent: string
  /**
   * 受影响人数。未登记时为 `null` —— 与 `0` 语义不同（`0` = 确实没人受影响，
   * `null` = 这类动作本就没有影响面）。页面按真值判断是否渲染该行。
   */
  affectedUsers: number | null
  /** 变更前后对照（权限/配置类日志）。无变更时为 `null`，**不是空数组**。 */
  changes?: { label: string; before: string; after: string }[] | null
  note?: string
  /**
   * 受影响**角色名**（如「访客」），不是操作人的角色码。
   *
   * ⚠️ 与后端 `sys_audit_log.role_name`（操作人角色码）**语义相反**：
   * 本字段来自 `affected_role` 列。为 `null` 时页面会自动省略
   * 「（该角色下全部成员）」与「查看该角色当前权限」按钮。
   */
  roleName?: string | null
}

// ==================== 模型配置（V2：四类模型 · 多配置 · 可切换） ====================

/** 四类模型：对话 / 嵌入 / 重排 / VL */
export type ModelType = 'CHAT' | 'EMBEDDING' | 'RERANK' | 'VL'

/** 计费方式：按 token / 按次 / 本地部署不计费 */
export type BillingUnit = 'PER_TOKEN' | 'PER_CALL' | 'NONE'

/** 能力标记（问答页切换时用于上下文校验与能力差异提示） */
export interface ModelCapabilities {
  stream: boolean
  vision: boolean
  /** 上下文窗口（tokens），如 128000 / 32000 */
  contextWindow: number
  supportSystemRole: boolean
}

/** 生成参数 */
export interface ModelParams {
  temperature: number
  maxTokens: number
  timeoutSec: number
  retries: number
}

/**
 * 一条模型配置（四类共用）
 * ⚠️ 计费字段三态：有值 = 可估算；`NONE` = 本地部署不计费；`null` = 未配置（费用未知）
 * —— `0` 与 `null` 语义不同，UI 必须区分。
 */
export interface ModelConfigItem {
  id: number
  modelType: ModelType
  displayName: string
  protocol: string
  baseUrl: string
  /** 脱敏后的 API Key（sk-••••3f9a），保存后不再回显明文 */
  apiKeyMasked: string
  modelName: string
  /** 向量维度（仅 EMBEDDING） */
  dimension?: number
  /** 归一化方式（仅 EMBEDDING） */
  norm?: string
  /** 批量大小（仅 EMBEDDING） */
  batchSize?: number
  /** 相似度度量（仅 EMBEDDING） */
  metric?: 'COSINE' | 'DOT' | 'EUCLIDEAN'
  params: ModelParams
  capabilities: ModelCapabilities
  billingUnit: BillingUnit
  /** 元 / 百万 tokens；null = 未配置单价 */
  inputPrice: number | null
  outputPrice: number | null
  /** 元 / 次；null = 未配置单价 */
  perCallPrice: number | null
  currency: string
  isActive: boolean
  sortOrder: number
  lastTestAt?: string
  /** 最近一次连接测试是否通过（未测试过为 false） */
  lastTestOk: boolean
  lastLatencyMs?: number
  note?: string
}

/** 模型配置页统计卡 */
export interface ModelStats {
  chatCount: number
  rerankCount: number
  vlCount: number
  defaultChatName: string
  defaultChatOk: boolean
  /** 本月 tokens（单位：万） */
  monthTokensWan: number
}

/** 四类模型配置响应 */
export interface ModelConfigResponse {
  stats: ModelStats
  chat: ModelConfigItem[]
  /** 嵌入模型全局单例（只读） */
  embedding: ModelConfigItem
  rerank: ModelConfigItem[]
  vl: ModelConfigItem[]
}

/** 模型用量统计（V2：按模型拆分 + 费用三态） */
export interface ModelUsage {
  calls: number
  tokens: number
  /** 费用估算：null = 全部模型未配单价，无法估算 */
  cost: number | null
  currency: string
  /** 未配置单价的模型数（不含 `NONE` 本地部署） */
  unpricedCount: number
  avgLatencySec: number
  byPurpose: { name: string; tokens: number; percent: number }[]
  /** 按模型拆分（含 VL 图片描述单列） */
  byModel: {
    modelName: string
    modelType: ModelType
    calls: number
    tokens: number
    /** 可估算 = number；`NONE` = 0（本地不计费）；未配置 = null */
    cost: number | null
    billingUnit: BillingUnit
  }[]
}

/** 问答页模型切换器选项（不含密钥） */
export interface ModelOption {
  id: number
  displayName: string
  modelName: string
  /** 是否为该类型当前启用的一条 */
  isActive?: boolean
  capabilities: ModelCapabilities
}

/** 更换嵌入模型影响量预计算（全库） */
export interface EmbeddingImpact {
  kbCount: number
  docCount: number
  chunkCount: number
  estimateMinutes: number
  extraTokens: number
}

/** 更换 VL 模型影响量（仅含图文档，非全库） */
export interface VlImpact {
  kbCount: number
  /** 含图片 / 扫描件的文档数 */
  imageDocCount: number
  /** 需重新理解的图片总数 */
  imageCount: number
  estimateMinutes: number
  extraTokens: number
}

// ==================== 知识库 / 文档模块 ====================

/** 密级（四档；下传到库内每篇文档，可提高不可降低） */
export type SecretLevel = 'PUBLIC' | 'INTERNAL' | 'CONFIDENTIAL' | 'SECRET'

/** 分片策略预设（五选一，含「自定义」） */
export type SplitPreset = 'GENERAL' | 'STRUCTURED' | 'CONTRACT' | 'TABLE_HEAVY' | 'QA_PAIR'
export type SplitPresetKey = SplitPreset | 'CUSTOM'

/** 分片策略预设定义（选中后带出全部参数） */
export interface SplitPresetDef {
  id: SplitPreset
  label: string
  desc: string
  chunkSize: number
  overlap: number
  semanticSplit: boolean
  tableAtomic: boolean
}

/** 分片策略（两级流水线：第一级锁定结构边界，第二级仅超长兜底） */
export interface SplitPolicy {
  preset: SplitPresetKey
  chunkSize: number
  overlap: number
  /** 第二级 · 递归分隔符（默认开） */
  recursiveSplit: boolean
  /** 第二级 · 语义切分（默认关，断点不可复现） */
  semanticSplit: boolean
}

/** 表格处理策略（六项配置） */
export interface TablePolicy {
  /** 整表原子化（锁定常开，识别到即整块封存） */
  atomicTable: boolean
  /** 双表示：Markdown 给大模型 / 行式文本给向量 */
  dualRepresentation: boolean
  repeatHeader: boolean
  mergeCrossPage: boolean
  /** 扫描件表格识别（OCR + 结构识别，失败必须标红） */
  ocrTable: boolean
  /** 回表行数上限 */
  rowLimit: number
  /** 命中行窗口 */
  rowWindow: number
}

/** 标签来源：治理（人工）/ 内容（自动）/ 系统默认 */
export type TagSource = 'MANUAL' | 'AUTO' | 'SYSTEM'

/** 治理标签（知识库级） */
export interface GovernanceTags {
  secretLevel: SecretLevel
  businessDomain: string
  customTags: string[]
}

/** 检索方式三选一 */
export type RetrievalMode = 'VECTOR' | 'HYBRID' | 'KEYWORD'

/** 检索配置（知识库级） */
export interface RetrievalConfig {
  mode: RetrievalMode
  /** 召回阈值（宽进，默认 0.15） */
  recallThreshold: number
  /** Rerank 保留数（3~15） */
  rerankTopK: number
  rerankEnabled: boolean
  /** 表格 Rerank 加权系数 */
  tableWeight: number
  /** 标识符保护：型号 / 金额 / 日期整体成 token */
  identifierProtect: boolean
}

/** 知识库创建 / 配置表单（步骤 ①~③ 的完整载荷） */
export interface KbConfigForm {
  name: string
  /** kb_ 前缀 + 业务语义，创建后不可修改 */
  code: string
  /** 归属部门（可见范围的基准） */
  deptId: number
  /** 可见角色（知识库级授权的初始值） */
  visibleRoles: string[]
  secretLevel: SecretLevel
  businessDomain: string
  description: string
  customTags: string[]
  splitPolicy: SplitPolicy
  tablePolicy: TablePolicy
  retrievalConfig: RetrievalConfig
}

/** 上传队列项（四态：上传中 / 解析中 / 已完成 / 失败） */
export type UploadStatus = 'UPLOADING' | 'PARSING' | 'DONE' | 'FAILED'

export interface UploadItem {
  uid: string
  name: string
  size: number
  type: string
  status: UploadStatus
  percent: number
  chunkDone: number
  chunkTotal: number
  docId?: number
  /** 失败的具体原因（可执行动作） */
  errorMsg?: string
  /** 命中 (kb_id, owner_id, md5) 去重 */
  duplicated?: boolean
  speedKbps?: number
  /**
   * 本轮上传产生的异步任务 id（`GET /doc/tasks/{taskId}` 用它轮询）。
   *
   * <p>⚠️ 上一版把它设了却从不轮询（`docApi.task` 零调用方），
   * 进度是前端编的 —— S4-M05-A 起真接上。
   */
  taskId?: string
  /** 文件的真实 MD5（32 位小写十六进制，前端算、后端复核）。 */
  md5?: string
  /**
   * 原始 `File` 对象（真上传用）。
   *
   * <p>类型里放一个 `File` 会让「上传队列」与「文件系统」耦合，但队列项的生命周期
   * 本来就和这个文件绑定（重试要重传同一份字节），所以直接挂上最省事 ——
   * 比在视图里另开一个 `Map<uid, File>` 少一处对账。
   */
  file?: File
}

/** 知识库内文档行（含解析质量列：表格 N/M · 图片 N/M） */
export interface KbDocRow {
  id: number
  title: string
  type: string
  size: number
  chunkCount: number
  status: DocInfo['status']
  /** 表格总数 */
  tableTotal: number
  /** 已识别表格数 */
  tableParsed: number
  /** 图片总数（V2 新增） */
  imageTotal?: number
  /** 已理解图片数（VL 描述成功，V2 新增） */
  imageParsed?: number
  /**
   * 解析出的总页数 / 判定为「有文本层」的页数（`S4-E2` 新增 · `优化/O2`）。
   *
   * ⚠️ **`pagesTotal === 0` 必须渲染成「—」而不是「0/0 页有文本」**：
   * 后端那两列是 `NOT NULL DEFAULT 0`，「0」既可能是"本批之前落的旧行、尚未统计"，
   * 也可能是"整篇扫描件、统计结果确实是 0"。后者状态必是 `FAILED`，
   * 前者只可能出现在 READY/INDEXING 的历史行上 —— 判据写在后端 `V18` 的列注释里。
   */
  pagesTotal?: number
  /** 其中判定为「有文本层」的页数。口径 = 该页 `chars > 0`（**无密度阈值**）。 */
  pagesWithText?: number
  owner: string
  uploadedAt: string
  /**
   * 恶意扫描结论（`T-O04` · `优化/O4` 步 3）。
   *
   * 🔴 **三态，少一态就会误导**：
   * - `'SKIPPED'` = **没扫成**（ClamAV 未部署 / 连不上）→ 列表上显示「未扫描」；
   * - `null` / `undefined` = **该行早于扫描能力上线**（从没试过）→ **不显示**那个标记；
   * - `'CLEAN'` / `'INFECTED'` = 扫过。
   *
   * ⚠️ 前两者绝不能合并显示：把历史行也标成「未扫描」会让运维以为清理任务漏跑了，
   * 而那是两件完全不同的事（"这台机器本来就没装" vs "这批老数据没扫过"）。
   */
  scanStatus?: 'CLEAN' | 'INFECTED' | 'SKIPPED' | null
  /**
   * 是否含宏（`T-O04` 步 4）。
   *
   * ⚠️ `null` / `undefined` = **判不了或没检测过**，**不是**"没有宏" ——
   * 所以不要用 `!hasMacro` 去判断"没有宏"，那会把"没检测过"也当成"干净"。
   */
  hasMacro?: boolean | null
}

/**
 * 逐页解析问题项（驱动顶部告警条）。
 *
 * ⚠️ `S4-E2` 起多两个字段（后端 `V18` 的列）。在此之前「种类」是**拼在 `reason`
 * 前面的文字前缀**（`【无文本层】…`），前端只能靠关键词猜 —— 现在它是结构化字段。
 *
 * 两者**都可空**：本批之前的旧行没有这两列的值（后端刻意不回填 —— 回填出来的是猜的）。
 * 空值时按"未知"处理，**不伪造**。
 */
export interface ParseIssue {
  docId: number
  docTitle: string
  pageNo: number
  reason: string
  /**
   * 问题种类。
   *
   * 文本侧：`NO_TEXT_LAYER` / `TABLE_NOT_PARSED` / `HEADER_FOOTER_STRIPPED` / `EMPTY_PAGE` / `OTHER`
   * 图片侧（`T-512` 起）：`IMAGE_NOT_EXTRACTABLE`（抽不到位图）/ `IMAGE_SKIPPED`（按策略跳过）/
   * `IMAGE_DESC_FAILED`（VL 理解失败）
   */
  kind?: string | null
  /**
   * 处置结果。
   *
   * `MISSING`（未处理，**计入告警**）/ `OCR_FILLED`（已补全）/ `EMPTY_PAGE`（空白页，不算问题）/
   * `SKIPPED`（按策略跳过，不算问题 —— `T-512` 步 5.1 新增；图标与重复 logo 几乎每份
   * 带图文档都有，计入会让告警条常态化变红）
   */
  disposition?: string | null
}

/** 只读解析配置（继承自知识库，上传页三栏展示） */
export interface ParseConfigGroup {
  label: string
  /** 主值文本 */
  value: string
  /** 锁定不可改 */
  locked?: boolean
  /** 需提示风险 */
  warning?: boolean
  /** 索引增强项是否开启 */
  enabled?: boolean
}

export interface ParseConfigView {
  split: ParseConfigGroup[]
  table: ParseConfigGroup[]
  /** 图片处理（V2 新增，第四栏） */
  image: ParseConfigGroup[]
  enhance: ParseConfigGroup[]
}

/** 配置变更 diff 项 */
export interface ConfigDiffItem {
  label: string
  before: string
  after: string
}

/** 重建影响预估 */
export interface ReindexImpact {
  docCount: number
  chunkBefore: number
  chunkAfter: number
  estimateMinutes: number
  estimateTokens: number
}

// ==================== 文档域（详情 / 预览 / 下载） ====================

/** 文档元信息（详情页 / 预览页，扩展自 DocInfo） */
export interface DocMeta extends DocInfo {
  /** 如「PDF（原生文本，非扫描件）」 */
  fileDesc: string
  /** 所属部门（来自知识库） */
  department: string
  md5: string
  storagePath: string
  tableTotal: number
  tableParsed: number
  /** 图片总数 / 已理解数（V2 新增） */
  imageTotal?: number
  imageParsed?: number
  /** 被引用次数（问答引用） */
  citations: number
  downVotes: number
  lastIndexAt: string
  /** 知识库级密级（文档密级不可低于此） */
  kbSecurityLevel: SecretLevel
  /** 内容标签（大模型生成，可人工修正） */
  tags: string[]
}

/** 文档版本（同名上传自动归档，不覆盖） */
export interface DocVersion {
  version: string
  size: number
  chunkCount: number
  owner: string
  at: string
  current: boolean
  note?: string
  /** 同一 docGroupId 已有更新版本 */
  hasNewer?: boolean
}

/** 索引任务记录 */
export interface IndexTask {
  id: number
  stage: string
  at: string
  version: string
  status: 'SUCCESS' | 'RUNNING' | 'FAILED' | 'INFO'
  durationSec?: number
  chunkCount?: number
  detail?: string
}

/** 分片块类型统计 */
export interface ChunkStat {
  total: number
  text: number
  table: number
  summary: number
  /** 图片块数（V2 新增） */
  image?: number
  /** 表格已拆出的子块数 */
  tableSubBlocks: number
  avgTokens: number
  /** 标题路径覆盖率 % */
  titlePathCoverage: number
}

/** 父子块关系（检索命中子块 → parent_id → 完整父块） */
export interface ParentChunkRelation {
  child: { ref: string; content: string }
  parent: { ref: string; title: string; rows: number; cols: number; returnedRows: number; content: string }
}

/** 文档目录项 */
export interface TocItem {
  id: string
  label: string
  level: number
}

/** 下载任务四态 */
export type DownloadStatus = 'PACKING' | 'DONE' | 'EXPIRED' | 'FAILED'

export interface DownloadTask {
  taskId: string
  name: string
  count: number
  size: number
  status: DownloadStatus
  percent: number
  createdAt: string
  fileUrl?: string
  expiresAt?: string
  errorMsg?: string
}

/** 下载记录 */
export interface DownloadRecord {
  id: number
  docId: number
  docTitle: string
  kbName: string
  size: number
  operator: string
  at: string
  ip: string
  type: 'SINGLE' | 'BATCH'
}

/** 存储用量与配额 */
export interface StorageQuota {
  used: string
  kbCount: number
  docCount: number
  chunkCount: number
  monthTokens: number
}

// ==================== 运营分析 ====================

/** 未命中问题状态机 */
export type MissStatus = 'PENDING' | 'IN_LIST' | 'RESOLVED' | 'IGNORED'

/** 未命中问题（保留用户原始问法，不做归一化改写） */
export interface MissQuestion {
  id: number
  /** 原样记录用户问法 */
  question: string
  /** 同一语义问题的聚合次数（用于排优先级） */
  askCount: number
  lastAskAt: string
  /** ⚠️ 可空：那个用户已经不在了（软删）⇒ 不能用「未知」顶替，也用空串假装有名字 */
  asker: string | null
  /** 最高相似度；已解决的显示 补录前 → 补录后。⚠️ 可空：真的没有分数时可空，**不是 0** */
  topScore: number | null
  scoreAfter?: number | null
  status: MissStatus
  /** 忽略人（避免反复忽略又反复冒出）。⚠️ 同 `asker`，可空 */
  ignoredBy?: string | null
  kbName: string
  /** 该问题是否来自「带图片描述」的提问（V2 新增，未命中沉淀保留图片信息） */
  hasImageDesc?: boolean
}

/** 问答运营四指标 */
export interface QaOverview {
  totalQuestions: number
  missed: number
  missedRate: number
  /** ⚠️ 可空：还没有人提问过 ⇒ 没有平均值。给 0 会被读成「召回质量极差」 */
  avgTopScore: number | null
  /** 待处理数（同步侧边栏角标） */
  pending: number
  /** 当前的未命中判定线 —— 卡片标题显示它，**别在前端写死 0.35** */
  missThreshold: number
}

/** 高频问题（真实问法是校准 HyQ 的语料） */
export interface TopQuestion {
  question: string
  count: number
  avgTopScore: number
}

/** 点踩原因归因 */
export interface FeedbackReason {
  reason: string
  count: number
  percent: number
}

export interface FeedbackStats {
  negativeTotal: number
  negativeRate: number
  threshold: number
  reasons: FeedbackReason[]
}

/** 重放验证结果（闭环第 4 步：命门） */
export interface ReplayResult {
  before: number
  after: number
  improved: boolean
  /** 新召回的确实是刚补录的那篇文档 */
  hitSupplementedDoc: boolean
  note: string
}

/** 检索链路阶段 */
export interface PipelineStage {
  name: string
  costMs: number
  metric: string
  metricValue: string
  action: string
}

/** 分数档位（五档色阶） */
export interface ScoreBucket {
  label: string
  range: string
  count: number
  percent: number
  /** 危险档位（边缘 / 未命中） */
  danger?: boolean
}

/** 检索质量四指标 */
export interface RetrievalOverview {
  avgTopScore: number
  afterTop1HitRate: number
  rerankGain: number
  avgCitedChunks: number
}

/** Rerank 效果验证（收益 vs 代价） */
export interface RerankEffect {
  beforeTop1HitRate: number
  afterTop1HitRate: number
  gainPercent: number
  avgLatencyMs: number
}

/** 各知识库检索质量 */
export interface KbQuality {
  kbName: string
  questions: number
  avgTopScore: number
  missRate: number
  trend: 'STABLE' | 'DOWN'
  issue: string
}

/** 使用统计四指标 */
export interface UsageOverview {
  activeUsers: number
  totalUsers: number
  activeRate: number
  totalQuestions: number
  /** 人均提问（使用深度） */
  avgPerUser: number
  coveredDepts: number
  totalDepts: number
  workdayAvg: number
  weekendAvg: number
  dailyAvg: number
}

/** 趋势点（颜色承载信息：周末灰、今天浅） */
export interface TrendPoint {
  date: string
  label: string
  value: number
  weekend: boolean
  today: boolean
}

/** 部门维度用量 */
export interface DeptUsage {
  dept: string
  activeUsers: number
  totalUsers: number
  questions: number
  perUser: number
  percent: number
}

/** 知识库热度 */
export interface KbHeat {
  kbName: string
  questions: number
  percent: number
}

/** 活跃用户排行（提问多 ≠ 用得好） */
export interface ActiveUserStat {
  name: string
  dept: string
  questions: number
  avgTopScore: number
}

// ==================== 通用（通知中心 / 回收站 / 错误页） ====================

/** 通知类型 */
export type NoticeType = 'UPLOAD' | 'ALERT' | 'PERMISSION' | 'SYSTEM'

/** 通知条目（每条至少带一个可执行动作） */
export interface NoticeItem {
  id: number
  type: NoticeType
  title: string
  content: string
  time: string
  read: boolean
  /** 时间分组：今天 / 昨天 / 更早 */
  group: '今天' | '昨天' | '更早'
  /** 动作按钮（跳到处理现场） */
  actions: { label: string; to?: string; primary?: boolean }[]
  /** 需要人工介入（在「待我处理」栏聚合） */
  needAction?: boolean
  /** 可「不再提醒」 */
  muteable?: boolean
}

/** 通知分类计数 */
export interface NoticeCounts {
  all: number
  unread: number
  UPLOAD: number
  ALERT: number
  PERMISSION: number
  SYSTEM: number
}

/** 通知设置（系统告警强制开启） */
export interface NoticeSetting {
  type: NoticeType
  label: string
  enabled: boolean
  forced?: boolean
  hint?: string
}

/** 「待我处理」聚合项 */
export interface PendingItem {
  key: string
  label: string
  count: number
  desc: string
  to: string
  btnText: string
}

/** 回收站条目类型 */
export type RecycleType = 'DOC' | 'KB'

/** 回收站条目 */
export interface RecycleItem {
  id: number
  type: RecycleType
  name: string
  /** 文档：原所属知识库；知识库：所属部门 */
  origin: string
  /** 知识库条目：其下文档数（恢复时连带恢复） */
  docCount?: number
  size: number
  deletedBy: string
  deletedAt: string
  /** 剩余保留天数（30 天制，绿 ≥20 / 橙 16~19 / 红 <16） */
  remainDays: number
  /** 原知识库已删除 → 恢复文档时需选目标库 */
  originDeleted?: boolean
  /** 同一文档组已有更新版本 */
  hasNewer?: boolean
}