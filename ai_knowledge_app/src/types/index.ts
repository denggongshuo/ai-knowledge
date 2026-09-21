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
  /**
   * 文档版本号，形如 `v2`。
   *
   * ✅ **`S7-M06-D` 起为真值**：`V39` 给 `sys_document` 加了 `version_no`，
   * 本字段 = `"v" + version_no`，与 `versionList` / `diff` / `rollback` 的版本号
   * **同源同格式**（`DocDetailService.meta` 与 `DocVersionService` 都这么拼）。
   *
   * ⚠️ **不要**拿 `sys_document.parse_version` 顶替：那是**解析器指纹**
   * （`V20` 的注释明写「≠ 文档版本」），一个说"谁解析的"、一个说"第几版"。
   *
   * ⚠️ 类型保留 `| null` 是**读侧防御**，不是能力声明：真后端**恒回字符串** ——
   * `DocDetailService#meta` 拼的是 `"v" + num(version_no)`，而 `num` 把 `NULL` 收成 `0`，
   * 所以即便真出现脏数据，你拿到的是 **`"v0"`** 而不是 `null`（`V39` 那列本身也是 `NOT NULL`）。
   * 而 `"v0"` 拿去回滚/对比会被 `parseVersion` 拒（`10030`「版本号「v0」无法识别」）——
   * **那才是"没有版本号"的症状**。⇒ 别用 `!version` 判断"这篇文档没有版本"，它判不出来。
   */
  version: string | null
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
  /** 答案**实际引用**的那几条（`T-804`；来自 `done.citations`）—— 渲染行内角标与来源卡片 */
  citations?: Citation[]
  /**
   * **检索找到**的那批分片（来自 `retrieval.chunks`）—— 渲染检索详情面板。
   *
   * <p>⚠️ 与 {@link citations} 是**两份不同的清单**：这份答“系统找到了哪些材料”，
   * 那份答“答案真的用上了哪几条”。`T-804` 之前只有一个 `citations`，
   * 而它是拿这份按 `i + 1` 拼出来的 —— 于是“没被引用”与“被引用”在页面上长得一样。
   */
  retrievedChunks?: Citation[]
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
  /**
   * 该附件在本轮里的阶段。
   *
   * <p>`READING` = **正在把原图交给对话模型**（`T-806` 的「直接读原图」）——
   * 与 `DESCRIBING`（VL 正在描述）是两件事：后者要等 40~60 秒（真 VL 实测），
   * 前者不调 VL。合成一个阶段会让用户**等一个不会来的东西**。
   */
  stage?: 'UPLOADING' | 'DESCRIBING' | 'READING' | 'DONE' | 'FAILED'
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
  /** 答案里的**角标号**（`[1]` 就是 1）—— 点角标定位到分片用它 */
  index: number
  /**
   * 检索名次（`sys_retrieval_trace_chunk.rank_no`）。
   *
   * <p>⚠️ 它与 `index` **可以不等** —— 模型完全可能只引用名次 3 的那条，
   * 而它在那句话里是 `[1]`。面板按它排序，角标按 `index` 定位。
   */
  rankNo: number
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
/**
 * 只读的嵌入模型信息（`kbApi.presets()` / `kbApi.config()` 的 `embedding` 字段）。
 *
 * <h3>为什么要有这个命名类型（`#225` · `T-O16`）</h3>
 * 同一个四字段形状原来在 `api/index.ts` 里内联了**两遍**、在 `KbDetailView` /
 * `KbCreateView` 里各自又写了一遍初值 —— 四处各说各的。收成一处之后，
 * 「后端加了字段前端没跟上」这类漂移只会在**一个**地方露头。
 *
 * ⚠️ `dimension` 是**集合的维度**（后端 `MilvusConstants.DIMENSION`），
 * 不是"模型自称的维度"。两者不一致时写入会在 Milvus 侧显式失败 ——
 * 这正是本版本「不支持换嵌入模型」的技术原因（`T-906`）。
 */
export interface EmbeddingInfo {
  model: string
  dimension: number
  norm: string
  locked: boolean
}

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
  /**
   * 被引用次数（问答引用）。
   *
   * ⚠️ **`null` = 未采集，不是"0 次"** —— `cited` 列存在但写入方恒写 null
   * （引用角标属 `T-804`）⇒ 后端如实给 null，页面**不许渲染成「0 次」**：
   * 那是一句关于内容的断言，而我们并没有那个事实（长期约定「判不了与判否分开报」）。
   */
  citations: number | null
  /** 点踩次数。同 `citations`：`null` = 未采集（同一根因） */
  downVotes: number | null
  /** 最近索引时刻；**从没跑过解析任务时为 `null`** */
  lastIndexAt: string | null
  /**
   * **当前生效**的密级 = `max(文档密级, 库密级)`（不变量 `I-6`）。
   *
   * ⚠️ 它就是上一条 `securityLevel`；这里把它和下面两个的关系写清楚：
   * 生效值是**算出来的**，不是单独存的一列。密级下拉的**下限**取的正是它。
   */
  securityLevel: SecretLevel
  /** 所属知识库的密级（文档密级不可低于此） */
  kbSecurityLevel: SecretLevel
  /**
   * 文档**自己**的密级；**`null` = 未覆盖（继承知识库）**。
   *
   * ⚠️ 它与 `kbSecurityLevel` 的关系**不能靠"值相等"推断**：用户主动把文档设成
   * 与库相同的值，和"它继承自库"是两回事 —— 数据上分得很清（`sys_document.secret_level`
   * 是不是 NULL）。需求 §6.7 要的「内部（**继承知识库**）」标注只用它判断。
   */
  ownSecurityLevel: SecretLevel | null
  /**
   * 内容标签（大模型生成，可人工修正）。
   *
   * ⚠️ **`null` = 未落库**（文档级内容标签至今没有归属表，`IndexEnhancer` 的 javadoc
   * 自己写着这一条）⇒ 不要用 `tags.length` 判断"没有标签"，那是两件事。
   */
  tags: string[] | null
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

/** 版本对比里的**一版**元数据（`GET /doc/{id}/versions/{v}/diff` 的 `left` / `right`）。 */
export interface VersionMeta {
  version: string
  size: number
  chunkCount: number
  tableTotal: number
  owner: string
  at: string
}

/**
 * 版本对比结果（需求 §6.8 · **v1 只做元数据对比**）。
 *
 * ⚠️ `textDiff` **恒 `null`**：需求 §6.8 逐字写「v1 只做元数据对比 + 文本 diff 接口预留」，
 * 后端也如实恒回 `null`（`DocVersionService#diff` 的第三参）。
 * ⇒ 页面上要说的是「**正文差异本版未做**」，**不是**「两版正文没有差别」——
 * 后者是一句关于内容的断言，而现在没有任何数据支撑它。
 */
export interface DocVersionDiff {
  left: VersionMeta
  right: VersionMeta
  /** 恒 `null`（接口预留）；**不要**用 `!textDiff` 推出"内容没变" */
  textDiff: string | null
}

/** 索引任务记录 */
export interface IndexTask {
  /**
   * 任务 id。
   *
   * ⚠️ 是**字符串**（`sys_async_task` 的主键就是 `task_id VARCHAR(64)`，那张表
   * **没有数字 id 列**）。mock 里给的 `1/2/3/4` 是示例数据的序号，不是真事实 ——
   * 真后端下发的是任务 id 本身，页面上只用它当 `:key`。
   */
  id: string
  stage: string
  at: string
  /**
   * ⚠️ 恒 `null` —— **不是**"这份任务没有版本"，而是**没有该来源**：
   * `sys_async_task` 上根本没有版本列，后端 `DocDtos.IndexTaskView` 的 javadoc 写着这一条。
   *
   * 🔴 与同名的 `DocInfo.version` **不是一回事**：后者自 `S7-M06-D` 起是**真值**
   * （`"v" + sys_document.version_no`）。两者同名纯属巧合 —— 一个说"这次任务为哪一版跑的"
   * （无处可取），一个说"这篇文档是第几版"（已落地）。别互相顶替。
   */
  version: string | null
  /**
   * **原样**取后端状态机 `AsyncTaskStatus` 的四态（`PENDING/RUNNING/DONE/FAILED`），
   * 不在 API 边界改名。
   *
   * ⚠️ 改名的代价不只是"哪个词好看"：一旦后端把它映射成 `SUCCESS`，这个取值就
   * **再也不可能三方逐字相等**，`check-enums.mjs` 的取值登记表（它只比"三方一致"）
   * 就覆盖不了它 ⇒ 三份词表又回到"靠人同步"。
   *
   * ⚠️ 原类型里还有一个 `INFO` —— 它在本仓**没有任何定义**（既不在 `AsyncTaskStatus` 里，
   * CSS 里也没有 `.dot.info`，那一行的圆点本来就是裸的），本批收回。
   */
  status: 'DONE' | 'RUNNING' | 'FAILED' | 'PENDING'
  durationSec?: number | null
  /** 分片数。⚠️ `null` = 未知（后端那列 0 的口径就是"还没数出来"），不是 0 个 */
  chunkCount?: number | null
  detail?: string | null
}

/**
 * 分片块类型统计。
 *
 * <h3>🔴 `total` = <b>顶层块数</b>，两条等式都要成立（`G7-2`）</h3>
 * <pre>
 *   text + table + summary + image == total      桶内自洽
 *   total + tableSubBlocks          == 全表行数   没有一行掉在地上
 * </pre>
 * 表格子块（子块的 `parentId != null`）**不进任何一个桶、也不进 `total`**，
 * 单独走 `tableSubBlocks`。⚠️ 只断言第一条时，「把 total 改小」就能假绿 ——
 * 所以后端用例 `DocDetailFlowTest` 两条都断言了。
 */
export interface ChunkStat {
  total: number
  text: number
  table: number
  summary: number
  /**
   * 图片块数。
   *
   * ⚠️ `null`（而不是 0）= **这一类块对它不适用** —— `T-512` 之前落的文档没有
   * IMAGE 块，「0 张图」与「这份文档早于图片能力上线」不是一件事。
   */
  image?: number | null
  /** 表格已拆出的子块数（**不属于**上面四个桶） */
  tableSubBlocks: number
  /**
   * 平均 token 数。分母与 `total` 同源（顶层块）。
   * ⚠️ 空文档（`total = 0`）时为 `null` —— 0 会被读成"分片都很短"。
   */
  avgTokens: number | null
  /** 标题路径覆盖率 %（分母 = 顶层块：子块**继承**父块路径，算进去会让含大表的文档虚高） */
  titlePathCoverage: number
}

/**
 * 父子块关系（`GET /doc/{id}/chunks/{parentId}`）。
 *
 * <h3>⚠️ 相对 mock 的旧形状：多了 `children[]`，去掉了 `child`</h3>
 * <p>门禁 `G7-1` 的原文是「返回父块完整内容 + **全部**子块」，而一个 `child`
 * 表达不了"全部"（旧的单一 `child` 是 mock 的产物 —— 那里恰好只有一个子块）。
 * 需求 §4.5 那张「命中子块 → 父块」的图描述的是**检索**场景，而检索详情面板用的是
 * 打点里的 `trace.chunks`，从不调本接口 ⇒ 没有真实消费方被破坏。
 */
export interface ParentChunkRelation {
  parent: {
    id: number
    orderNo: number
    /** 表格块引用号（`T-03`） */
    ref: string
    /** **表标题**（Markdown 的 `**…**` 前导行）；没有时为空串。⚠️ 刻意不回落成"所属章节" */
    title: string
    /** 所属章节（标题路径） */
    titlePath: string
    /** 数据行数（**不含表头**）；父块内容不是管道表时为 0 */
    rows: number
    cols: number
    /** 本次**实际返回**的数据行数。本页返回整表 ⇒ 恒等于 `rows` */
    returnedRows: number
    /** 完整 Markdown（含表标题与表头） */
    content: string
  }
  /** 全部子块（行式文本），按 `orderNo` 升序 */
  children: { id: number; orderNo: number; ref: string; content: string }[]
  /** `children.length` 的显式副本，让"全部"可断言 */
  total: number
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

/**
 * 高频问题（真实问法是校准 HyQ 的语料）。
 *
 * ⚠️ `count` 是**归一化聚合**的次数：同一问法的不同写法必然合并
 * （`怎么配？` ≡ `怎么配`），而「意思一样但问法不同」**不合并**
 * —— 那是 `R-6` 的语义聚合，阈值无语料可校准、已判定挂起（后端 `G8-2` 的挂起半边）。
 */
export interface TopQuestion {
  question: string
  count: number
  /** 该组**有分数行**的平均 Top1；⚠️ **可空**：一条都没拿到过分时后端给 `null`（页面显示 `—`）—— 用 0 顶替会被读成「召回质量极差」 */
  avgTopScore: number | null
}

/** 点踩原因归因 */
export interface FeedbackReason {
  reason: string
  count: number
  percent: number
}

/**
 * 点踩原因归因。
 *
 * 🔴 **三个数合起来才对得上**：`reasons` 合计 + `unreasoned` = `negativeTotal`。
 * 因为点踩时原因**可不填**（需求 `07` §6.8 写的是"可选"），所以需求 §1.10 那条
 * 「点踩原因次数合计 = 负反馈总数」在有这种行时**不可能成立** ——
 * 后端把三个数都如实给了，页面必须一起显示，不能只显示前两个。
 */
export interface FeedbackStats {
  negativeTotal: number
  negativeRate: number
  /** 需求 §1.6 点名要对比的阈值（%）；后端给常量，不是可配置项 */
  threshold: number
  /** 点踩但**没填原因**的条数（`feedback_reason` 为空的那些） */
  unreasoned: number
  reasons: FeedbackReason[]
}

/** 重放验证结果（闭环第 4 步：命门） */
export interface ReplayResult {
  /** 补录前的最高相似度；⚠️ 可空：旧行没有记录到这个数（此时判定不成立） */
  before: number | null
  /** 本次重放的 Top1；⚠️ 可空：**一条都没召回**（不是 0 分） */
  after: number | null
  /** 分数是否提升（after > before） */
  improved: boolean
  /**
   * **Top1** 是不是刚补录的那篇。
   * ⚠️ 不是"召回列表里有没有它" —— 补录那篇挂在第二名时，达标的分其实是别人给的
   */
  hitSupplementedDoc: boolean
  /**
   * 判定结论。🔴 **不能只看 `status`**：`NOT_RESOLVED`（判过了、没通过）与
   * `BLOCKED`（压根判不了：还没补录 / 索引没建好）都是 `PENDING`，
   * 而页面对这两件事的展示完全不同。
   */
  verdict: 'RESOLVED' | 'NOT_RESOLVED' | 'BLOCKED'
  /** 重放后该行的**实际**状态（后端权威值，前端不再自己推断） */
  status: MissStatus
  /** 一句话依据，或"为什么判不了"。用来告诉用户下一步该做什么 */
  note: string
}

/**
 * 检索链路的一个阶段。
 *
 * ⚠️ `metric` / `metricValue` / `action` 都可为 `null`：需求 §2.2「关注指标」列里
 * **只有「去重后候选数」和「重排前后命中率差」有采集点**，其余四个后端没有数据源
 * ⇒ 如实发 `null`，页面显示 `—`（**不许填 0，也不许填 mock 的示例值**）。
 */
export interface PipelineStage {
  name: string
  costMs: number
  metric: string | null
  metricValue: string | null
  action: string | null
}

/**
 * 链路耗时分解的响应。
 *
 * 🔴 三段相加恰等于全链路：`retrievalSideMs + generationMs + otherMs === totalMs`。
 * 需求 §2.2 那 5 项里**不含「可见范围」与「表格回表」**，可它们是真花的钱 ⇒
 * 由 `otherMs` 承载（不给它的话差额没有任何一栏显示）。
 *
 * ⚠️ 整表只算在 `llm_generation_millis` 非空的行上（空召回不调模型）——
 * `skippedNoGeneration` 是被排除的行数，页面要说明，否则读者会以为它是全量的。
 */
export interface RetrievalPipeline {
  list: PipelineStage[]
  pipelineRows: number
  skippedNoGeneration: number
  retrievalSideMs: number
  generationMs: number
  otherMs: number
  totalMs: number
}

/** 分数档位（需求五档 + 本批补的「无法定档」） */
export interface ScoreBucket {
  label: string
  range: string
  count: number
  percent: number
  /** 危险档位（边缘 / 未命中 / 无法定档） */
  danger?: boolean
}

/**
 * 分数分布的响应。
 *
 * 🔴 需求 §2.8 的「五档合计 = 提问总数」**在有空召回时不可能成立**
 * （空召回的行 `top1_score` 是 `NULL`，落不进任何一档）⇒ 补了第 6 档「无法定档」，
 * 并把它的两个成因分开给：`noRerank`（没重排，分数与档位不同量纲）与
 * `emptyRecall`（重排了但没召回到）。合成一个数会让"配置问题"与"覆盖问题"看着像同一件事。
 */
export interface ScoreDistribution {
  list: ScoreBucket[]
  total: number
  unscored: number
  noRerank: number
  emptyRecall: number
}

/**
 * 检索质量四指标。
 *
 * ⚠️ 四个数**都可为 `null`** —— `null` = **数据不足**（没有可比的行），不是 0。
 * 全是 0 会被读成"一条都没命中"，而真相是"没有数据"（需求 §2.8 最后一条点名要的）。
 */
export interface RetrievalOverview {
  totalQuestions: number
  /** 可比集：重排过且有 Top1 的行 —— 下面几个比率/均值都在这一批上算 */
  comparableQuestions: number
  /** 未重排（分数是融合分，与判定线不同量纲，**不参与**任何比率） */
  noRerankQuestions: number
  /** 重排了但空召回（没有 Top1） */
  emptyRecallQuestions: number
  avgTopScore: number | null
  afterTop1HitRate: number | null
  rerankGain: number | null
  avgCitedChunks: number | null
  missThreshold: number
}

/**
 * Rerank 效果验证（收益 vs 代价）。
 *
 * 🔴 前后两个比率的分母是**同一个子集**（`covered`：重排前那条也拿到了重排分的行）——
 * 只有同一批行上的差才是"重排带来的提升"。`covered / comparable` 就是覆盖率，
 * 页面**必须**显示它：否则这两个数看起来是全量的。
 */
export interface RerankEffect {
  beforeTop1HitRate: number | null
  afterTop1HitRate: number | null
  gainPercent: number | null
  avgLatencyMs: number | null
  /** 有「重排前」分数的行数（两个比率的共同分母） */
  covered: number
  /** 可比集行数（重排过且有 Top1） */
  comparable: number
}

/**
 * 各知识库检索质量。
 *
 * ⚠️ `trend` 里的 `UNKNOWN` 是**样本不足**，不是"稳定"（页面文案要区分）；
 * `issue` 本批**恒为 `null`** —— 「主要问题」要靠文档质量列 / 解析问题表，
 * 与检索打点是两张表的事 ⇒ 后端不猜。
 */
export interface KbQuality {
  kbId: number | null
  kbName: string
  questions: number
  avgTopScore: number | null
  missRate: number | null
  trend: 'STABLE' | 'UP' | 'DOWN' | 'UNKNOWN'
  issue: string | null
}

/** 各知识库质量排行的响应 */
export interface KbQualityRanking {
  list: KbQuality[]
  questions: number
  othersQuestions: number
}

/** 使用统计四指标 */
export interface UsageOverview {
  activeUsers: number
  totalUsers: number
  /**
   * 活跃率（百分数，一位小数）。
   *
   * 🔴 **可为 `null`** —— 分母（总用户数）为 0 时**算不出来**，后端如实回 `null`。
   * 页面必须显示 `—` 而不是 0：`0%` 会被读成「一个人都没用过」，
   * 而真相是「这条比率没有分母」。
   */
  activeRate: number | null
  totalQuestions: number
  /** 人均提问（使用深度）。**可为 null**（没有活跃用户时） */
  avgPerUser: number | null
  coveredDepts: number
  totalDepts: number
  /** 工作日/周末/日均。**均可为 null**（窗口内该类型的天数为 0 时） */
  workdayAvg: number | null
  weekendAvg: number | null
  dailyAvg: number | null
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
  /** 人均。**可为 null**（该部门没有活跃用户时） */
  perUser: number | null
  /** 占比（百分数）。**可为 null**（窗口内一次提问都没有时）。各行之和**恰好 100.0** */
  percent: number | null
}

/** 知识库热度 */
export interface KbHeat {
  kbName: string
  questions: number
  /** 占比（百分数）。同名行的分母是**含「其他」的提问总数**；可为 null */
  percent: number | null
}

/** 活跃用户排行（提问多 ≠ 用得好） */
export interface ActiveUserStat {
  name: string
  dept: string
  questions: number
  /**
   * 平均 Top1。
   *
   * 🔴 **可为 `null`** —— 该用户窗口内**没有一行有分数**（全是空召回）时算不出来。
   * ⚠️ 判断时**不能用 `avgTopScore < 0.7`**：JS 里 `null < 0.7` 是 `true`
   * ⇒ 每一个"没有分数"的人都会被标成「分低」，而那是一个**关于内容的结论**，
   * 与"没测到"是两件事（`S8-M10-B` 定的「判不了必须与判否分开报」）。
   */
  avgTopScore: number | null
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