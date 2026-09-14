import { http, chatStream, type SSEHandlers } from './request'
import type {
  ActiveUserStat,
  ApiResponse,
  AttachmentUploadResult,
  AuditLog,
  AuditLogDetail,
  ChatSessionItem,
  ChunkInfo,
  ChunkStat,
  ConfigDiffItem,
  DeptUsage,
  DocMeta,
  DocVersion,
  DownloadRecord,
  DownloadTask,
  EmbeddingImpact,
  FeedbackStats,
  IndexTask,
  KbConfigForm,
  KbDocRow,
  KbHeat,
  KbQuality,
  KnowledgeBase,
  MissQuestion,
  MissStatus,
  ModelConfigItem,
  ModelConfigResponse,
  ModelOption,
  ModelType,
  ModelUsage,
  NoticeCounts,
  NoticeItem,
  NoticeSetting,
  NoticeType,
  PageResult,
  ParentChunkRelation,
  ParseConfigView,
  ParseIssue,
  PendingItem,
  PermissionNode,
  PipelineStage,
  QaOverview,
  RecycleItem,
  ReindexImpact,
  ReplayResult,
  RetrievalOverview,
  RerankEffect,
  RoleInfo,
  ScoreBucket,
  SplitPresetDef,
  StorageQuota,
  TocItem,
  TopQuestion,
  TrendPoint,
  UsageOverview,
  UserInfo,
  VlImpact,
} from '@/types'

/** 认证接口 */
export const authApi = {
  captcha: () => http.get<{ captchaId: string; imageBase64: string }>('/auth/captcha'),
  login: (body: {
    account: string
    password: string
    captchaId?: string
    captchaCode?: string
    remember?: boolean
  }) => http.post<{ accessToken: string; refreshToken: string; user: unknown; roles: unknown[] }>('/auth/login', body),
  /** 短信验证码登录（与文档 §1 `/auth/login-by-sms` 一致；不再走密码占位 hack） */
  loginBySms: (body: { phone: string; smsCode: string; remember?: boolean }) =>
    http.post<{ accessToken: string; refreshToken: string; user: unknown; roles: unknown[] }>('/auth/login-by-sms', body),
  logout: () => http.post('/auth/logout'),
  checkUsername: (username: string) =>
    http.get<{ available: boolean; reason: string }>('/auth/check-username', { username }),
  checkPhone: (phone: string) => http.get<{ available: boolean; reason: string }>('/auth/check-phone', { phone }),
  /**
   * 发送短信验证码（`scene` 区分场景，与接口约定 §1 一致）。
   *
   * `bind` = 换绑手机 / 邮箱：换绑手机发往**新号**，换绑邮箱发往**当前已绑手机号**
   * （v1 无邮件通道，只能用短信证明身份）。验证码按 `scene + target` 存键，
   * 发码与换绑两处的 scene 必须一致，否则校验必然失败。
   */
  smsCode: (body: { phone?: string; username?: string; captchaId?: string; captchaCode?: string; scene: 'login' | 'register' | 'reset-password' | 'bind' }) =>
    http.post<{ sent: boolean; debugCode?: string; scene: string }>('/auth/sms-code', body),
  register: (body: { username: string; phone: string; smsCode: string; password: string; realName?: string }) =>
    http.post<{ registered: boolean; username: string }>('/auth/register', body),
  forgotVerify: (body: { username: string; phone: string; smsCode: string }) =>
    http.post<{ token: string; maskedPhone: string }>('/auth/verify-reset', body),
  forgotReset: (body: { token: string; password: string }) =>
    http.post<{ reset: boolean }>('/auth/reset-password', body),
}

/** 用户接口 */
export const userApi = {
  profile: () => http.get<Record<string, unknown>>('/user/profile'),
  security: () => http.get<Record<string, unknown>>('/user/security'),
  loginLogs: (query: { page?: number; pageSize?: number }) => http.get('/user/login-logs', query),
  updateProfile: (body: { realName?: string; email?: string; position?: string; intro?: string }) =>
    http.put('/user/profile', body),
  /** 头像上传：`multipart/form-data`，字段名固定 `file`（后端 `@RequestPart("file")`） */
  avatar: (form: FormData) => http.post<{ url: string }>('/user/avatar', form),
  permissions: () => http.get<{ codes: string[] }>('/user/permissions'),
  changePhone: (body: { phone: string; code: string }) => http.post('/user/change-phone', body),
  changeEmail: (body: { email: string; code: string }) => http.post('/user/change-email', body),
  changePassword: (body: { oldPassword: string; newPassword: string }) => http.post('/user/password', body),
}

/** 知识库 / 文档接口 */
export const kbApi = {
  list: (scope?: string) => http.get<{ list: unknown[]; total: number }>('/kb', { scope }),
  /** 创建（含 splitPolicy / tablePolicy / governanceTags / retrievalConfig） */
  create: (body: KbConfigForm) => http.post<{ id: number; name: string; status: string; note: string }>('/kb', body),
  /** 策略预设 + 默认配置 + 只读嵌入模型 */
  presets: () =>
    http.get<{
      presets: SplitPresetDef[]
      defaults: KbConfigForm
      embedding: { model: string; dimension: number; norm: string; locked: boolean }
      vlModel: { name: string; enabled: boolean; appliedAt: string }
    }>('/kb/presets'),
  /** 知识库配置（设置页） */
  config: (id: number | string) =>
    http.get<{
      id: number
      name: string
      code: string
      description: string
      department: string
      deptId: number
      docCount: number
      chunkCount: number
      status: string
      createdAt: string
      updatedAt: string
      visibleRoles: string[]
      config: KbConfigForm
      embedding: { model: string; dimension: number; norm: string; locked: boolean }
      vlModel: { name: string; enabled: boolean; appliedAt: string }
      presetOptions: SplitPresetDef[]
    }>(`/kb/${id}/config`),
  saveConfig: (id: number | string, config: KbConfigForm) =>
    http.put<{ saved: boolean; needReindex: boolean; updatedAt: string }>(`/kb/${id}/config`, { config }),
  /** 变更影响预估（docCount / chunkBefore / chunkAfter / estimateMinutes / estimateTokens） */
  impact: (id: number | string, changes: ConfigDiffItem[]) =>
    http.post<ReindexImpact>(`/kb/${id}/config/impact`, { changes }),
  reindex: (id: number | string, mode: 'IMMEDIATE' | 'SCHEDULED') =>
    http.post<{ taskId: string; mode: string; status: string; scheduledAt: string | null }>(`/kb/${id}/reindex`, { mode }),
  clearIndex: (id: number | string) => http.delete<{ cleared: boolean }>(`/kb/${id}/index`),
  /** 删除知识库（软删，进回收站）🚩（与文档 §5 `DELETE /kb/{id}` 一致） */
  remove: (id: number | string) =>
    http.delete<{ id: number; deleted: boolean; recycled: boolean; name: string }>(`/kb/${id}`),
  /** 库内文档列表（含解析质量） */
  docs: (id: number | string, query?: { keyword?: string; type?: string; status?: string; page?: number; size?: number }) =>
    http.get<{ list: KbDocRow[]; total: number; displayed: number }>(`/kb/${id}/docs`, query),
  /** 未识别表格清单 */
  parseIssues: (id: number | string) => http.get<{ list: ParseIssue[]; total: number }>(`/kb/${id}/parse-issues`),
  /** 只读解析配置 */
  parseConfig: (id: number | string) => http.get<ParseConfigView>(`/kb/${id}/parse-config`),
  /** MD5 去重判断 */
  checkMd5: (id: number | string, body: { name: string; md5: string }) =>
    http.post<{ duplicated: boolean; docId?: number; docTitle?: string; hint: string }>(`/kb/${id}/doc/check-md5`, body),
  uploadDoc: (id: number | string, body: { name: string; size: number; md5: string }) =>
    http.post<{ docId: number; taskId: string; name: string; kbId: number; chunkTotal: number }>(`/kb/${id}/doc/upload`, body),
  /** 存储用量与配额 */
  storageQuota: () => http.get<StorageQuota>('/kb/storage-quota'),
}

export const docApi = {
  /** ☟ 文档域（详情 / 预览 / 下载） */
  /** 文档元信息（路径与《10-接口约定汇总》一致）：含 tableTotal / tableParsed 供解析质量展示 */
  meta: (id: number | string) => http.get<DocMeta>(`/doc/${id}`),
  versionList: (id: number | string) => http.get<{ current: string; list: DocVersion[] }>(`/doc/${id}/versions`),
  rollback: (id: number | string, version: string) =>
    http.post<{ docId: number; version: string; rolledBack: boolean; note: string }>(`/doc/${id}/rollback`, { version }),
  indexTasks: (id: number | string) => http.get<{ list: IndexTask[]; total: number }>(`/doc/${id}/index-tasks`),
  chunkStat: (id: number | string) => http.get<ChunkStat>(`/doc/${id}/chunk-stat`),
  toc: (id: number | string) => http.get<{ list: TocItem[] }>(`/doc/${id}/toc`),
  chunks: (id: number | string, type?: string) =>
    http.get<{ list: ChunkInfo[]; total: number; stat: ChunkStat }>(`/doc/${id}/chunks`, { type }),
  parentChunk: (id: number | string, parentId: number | string) => http.get<ParentChunkRelation>(`/doc/${id}/chunks/${parentId}`),
  saveAttributes: (id: number | string, body: { secretLevel?: string; kbId?: number; tags?: string[] }) =>
    http.patch<{ saved: boolean; docId: number; needReindex: boolean }>(`/doc/${id}/attributes`, body),
  /** 任务进度轮询 */
  task: (taskId: string) =>
    http.get<{ taskId: string; stage: string; percent: number; chunkDone: number; chunkTotal: number; errorMsg: string | null }>(
      `/doc/tasks/${taskId}`,
    ),
  reindex: (id: number | string) => http.post<{ docId: number; taskId: string; status: string }>(`/doc/${id}/reindex`),
  remove: (id: number | string) => http.delete<{ docId: number; recycled: boolean }>(`/doc/${id}`),
  batchRemove: (ids: number[]) => http.delete<{ deleted: number }>('/doc/batch', { ids }),
  /** 单文件下载：预签名 URL（有效期 10 分钟） */
  downloadUrl: (docId: number | string) =>
    http.get<{ url: string; expiresAt: string; expiresInSec: number }>(`/doc/download-url/${docId}`),
  /** 批量打包下载（异步任务） */
  batchDownload: (docIds: number[]) =>
    http.post<{ taskId: string; count: number; status: string }>('/doc/batch-download', { docIds }),
  downloadTask: (taskId: string) => http.get<DownloadTask>(`/doc/download-tasks/${taskId}`),
  downloadTasks: () => http.get<{ list: DownloadTask[]; total: number }>('/doc/download-tasks'),
  downloadRecords: (query?: { page?: number; pageSize?: number }) =>
    http.get<PageResult<DownloadRecord>>('/doc/download-records', query),
  /** 跨库文档列表（全站口径） */
  all: (query?: { keyword?: string; kbId?: string; type?: string; page?: number; size?: number }) =>
    http.get<{ list: (KbDocRow & { kbId: number; kbName: string })[]; total: number }>('/doc/all', query),
  exportList: () => http.post<{ taskId: string; status: string; note: string }>('/doc/export-list'),
}

/** 检索 / 运营 */
export const opsApi = {
  // ---- 11 问答运营 ----
  qaOverview: () => http.get<QaOverview>('/ops/qa/overview', { range: '7d' }),
  misses: (query?: { status?: string; kbId?: string; keyword?: string; page?: number; size?: number }) =>
    http.get<{ list: MissQuestion[]; total: number }>('/ops/qa/misses', query),
  patchMiss: (id: number, status: MissStatus) => http.patch<{ id: number; status: string; pending: number }>(`/ops/qa/misses/${id}`, { status }),
  /** 重放验证：同时校验分数提升与召回的是补录文档 */
  replayMiss: (id: number) => http.post<ReplayResult>(`/ops/qa/misses/${id}/replay`),
  topQuestions: (query?: { range?: string; limit?: number }) =>
    http.get<{ list: TopQuestion[]; total: number }>('/ops/qa/top-questions', query),
  feedbackStats: () => http.get<FeedbackStats>('/ops/qa/feedback-stats'),
  qaExport: () => http.post<{ taskId: string; status: string; note: string }>('/ops/qa/export'),

  // ---- 20 检索质量分析 ----
  retrievalOverview: () => http.get<RetrievalOverview>('/ops/retrieval/overview', { range: '7d' }),
  pipeline: () =>
    http.get<{ list: PipelineStage[]; retrievalSideMs: number; totalMs: number }>('/ops/retrieval/pipeline', { range: '7d' }),
  scoreDistribution: () => http.get<{ list: ScoreBucket[]; total: number }>('/ops/retrieval/score-distribution', { range: '7d' }),
  rerankEffect: () => http.get<RerankEffect>('/ops/retrieval/rerank-effect', { range: '7d' }),
  kbQuality: () => http.get<{ list: KbQuality[]; questions: number }>('/ops/retrieval/kb-quality', { range: '7d' }),

  // ---- 21 使用统计 ----
  usageOverview: () => http.get<UsageOverview>('/ops/usage/overview', { range: '7d' }),
  trend: () => http.get<{ list: TrendPoint[]; total: number }>('/ops/usage/trend', { range: '7d', granularity: 'day' }),
  byDept: () => http.get<{ list: DeptUsage[]; active: number; users: number; questions: number }>('/ops/usage/by-dept', { range: '7d' }),
  byKb: () => http.get<{ list: KbHeat[]; questions: number }>('/ops/usage/by-kb', { range: '7d' }),
  topUsers: (query?: { range?: string; limit?: number }) =>
    http.get<{ list: ActiveUserStat[]; total: number }>('/ops/usage/top-users', query),
}

/** 通知中心 */
export const noticeApi = {
  list: (query?: { type?: string; read?: string; page?: number; size?: number }) =>
    http.get<{ list: NoticeItem[]; total: number; counts: NoticeCounts }>('/notices', query),
  unreadCount: () => http.get<{ count: number }>('/notices/unread-count'),
  /** 「待我处理」聚合（与问答运营页待处理数一致） */
  pending: () => http.get<{ list: PendingItem[]; total: number }>('/notices/pending'),
  settings: () => http.get<{ list: NoticeSetting[] }>('/notices/settings'),
  saveSettings: (list: { type: NoticeType; enabled: boolean }[]) =>
    http.put<{ saved: boolean; applied: unknown[]; note: string }>('/notices/settings', { list }),
  read: (id: number, read = true) => http.patch<{ id: number; read: boolean; unread: number }>(`/notices/${id}/read`, { read }),
  /** 全部标为已读：无需二次确认（已读可逆） */
  readAll: () => http.post<{ updated: number; unread: number }>('/notices/read-all'),
}

/** 回收站 */
export const recycleApi = {
  items: (query?: { type?: string; keyword?: string; page?: number; size?: number }) =>
    http.get<{ list: RecycleItem[]; total: number; keepDays: number }>('/recycle/items', query),
  /** 恢复：文档恢复后需重建索引；知识库恢复连带其下全部文档 */
  restore: (body: { ids: number[]; targetKbId?: number }) =>
    http.post<{ restored: number; needReindex: boolean; affectedDocs: number; targetKbId: number | null; note: string }>(
      '/recycle/restore',
      body,
    ),
  remove: (ids: number[]) => http.delete<{ deleted: number }>('/recycle/items', { ids }),
  clearAll: () => http.delete<{ cleared: number }>('/recycle/all'),
  cleanup: () => http.post<{ cleaned: number; note: string }>('/recycle/cleanup'),
}

/** 问答（图片附件与会话；流式生成走 chatStream） */
export const chatApi = {
  /** 图片预上传：只落存储 + 落库，不调 VL（描述由后端在 completions 内部完成） */
  uploadAttachment: (body: { name: string; size: number; md5: string; mime?: string }) =>
    http.post<AttachmentUploadResult>('/chat/attachments', body),
  /** 回看附件（含 VL 描述，只读展示） */
  attachment: (id: string) => http.get<Record<string, unknown>>(`/chat/attachments/${id}`),
  sessions: () => http.get<{ list: ChatSessionItem[]; total: number }>('/chat/sessions'),
  messages: (id: string) => http.get<{ list: unknown[]; total: number }>(`/chat/sessions/${id}/messages`),
}

/** 系统配置 */
export const sysApi = {
  thresholds: () => http.get<{ recallThreshold: number; missThreshold: number; lowScoreWarn: number; rerankTopK: number }>('/system/thresholds'),
  /** ☟ 系统管理模块（用户 / 角色 / 授权 / 模型 / 审计 / 部门） */
  deptTree: () => http.get<Record<string, unknown>[]>('/depts/tree'),
  createDept: (body: { name: string; parentId?: number }) => http.post<{ id: number }>('/depts', body),
  users: (query: { deptId?: number | string; keyword?: string; roleId?: string; status?: string; page?: number; size?: number }) =>
    http.get<PageResult<Record<string, unknown>>>('/users', { ...query }),
  createUser: (body: Record<string, unknown>) => http.post<{ id: number; status: string }>('/users', body),
  updateUser: (id: number | string, body: Record<string, unknown>) => http.put(`/users/${id}`, body),
  /** 删除账号（软删）。服务端三道闸：不能删自己 / 最后超管 / 有业务数据只能禁用。 */
  deleteUser: (id: number | string) => http.delete<{ deleted: boolean }>(`/users/${id}`),
  patchUserStatus: (id: number | string, status: string) => http.patch(`/users/${id}/status`, { status }),
  resetPassword: (id: number | string) => http.post(`/users/${id}/reset-password`),
  resendInvite: (id: number | string) => http.post(`/users/${id}/resend-invite`),
  importUsers: (form: FormData) => http.post<{ success: number; failed: number; rows: { row: number; error: string }[] }>('/users/import', form),
  roles: (query: { keyword?: string; type?: string; status?: string }) =>
    http.get<{ list: Record<string, unknown>[]; total: number }>('/roles', query),
  createRole: (body: Record<string, unknown>) => http.post<Record<string, unknown>>('/roles', body),
  updateRole: (id: number | string, body: Record<string, unknown>) => http.put(`/roles/${id}`, body),
  deleteRole: (id: number | string) => http.delete(`/roles/${id}`),
  patchRoleStatus: (id: number | string, enabled: boolean) => http.patch(`/roles/${id}/status`, { enabled }),
  permissionTree: () => http.get<{ tree: PermissionNode[]; total: number }>('/permissions/tree'),
  rolePermissions: (id: number | string) => http.get<{ codes: string[] }>(`/roles/${id}/permissions`),
  grantMatrix: () =>
    http.get<{
      roles: { id: number; code: string; name: string; builtin: boolean }[]
      items: { key: string; label: string; group: string }[]
      levels: Record<string, string[]>
      updatedAt?: string
      updatedBy?: string
    }>('/permissions/matrix'),
  saveMatrix: (changes: unknown[]) => http.put<{ applied: number }>('/permissions/matrix', { changes }),
  kbGrants: (query: { page?: number; pageSize?: number }) =>
    http.get<{ list: Record<string, unknown>[]; total: number }>('/kb/grants', query),
  kbGrantDetail: (id: number | string) => http.get<Record<string, unknown>>(`/kb/${id}/grants`),
  saveKbGrant: (id: number | string, body: Record<string, unknown>) => http.put(`/kb/${id}/grants`, body),
  modelConfig: () => http.get<ModelConfigResponse>('/system/model/config'),
  /** 新增一条（modelType 必填；EMBEDDING 不可新增）🚩 */
  createModel: (body: Partial<ModelConfigItem> & { modelType: ModelType }) =>
    http.post<{ id: number; saved: boolean }>('/system/model/config', body),
  /** 编辑一条 🚩 */
  updateModel: (id: number | string, body: Partial<ModelConfigItem>) =>
    http.put<{ id: number; saved: boolean; needRetest: boolean }>(`/system/model/config/${id}`, body),
  /** 删除（当前启用不可删）🚩 */
  deleteModel: (id: number | string) => http.delete<{ id: number; deleted: boolean }>(`/system/model/config/${id}`),
  /** 切换启用 🚩（同类型其他自动取消启用） */
  activateModel: (id: number | string) =>
    http.post<{ id: number; activated: boolean; modelType: ModelType; needRebuildImageDocs?: boolean; needReindex?: boolean }>(
      `/system/model/config/${id}/activate`,
    ),
  /** 连接测试（按 id；启用前必须通过） */
  testModel: (id: number | string) =>
    http.post<{ id: number; ok: boolean; latencyMs: number; message: string }>(`/system/model/config/${id}/test`),
  /** 问答页切换器选项：只返回已启用且测试通过的模型（不含密钥） */
  modelOptions: (type: ModelType = 'CHAT') => http.get<{ list: ModelOption[] }>('/system/model/options', { type }),
  modelUsage: () => http.get<ModelUsage>('/system/model/usage', { range: 'month' }),
  embeddingImpact: () => http.get<EmbeddingImpact>('/system/model/embedding-impact'),
  changeEmbedding: (body: { model: string; dimension: number; rebuildMode: string }) =>
    http.post<{ taskId: string }>('/system/model/change-embedding', body),
  /** 换 VL 模型影响量（仅含图文档，非全库） */
  vlImpact: () => http.get<VlImpact>('/system/model/vl-impact'),
  /** 重建含图片 / 扫描件的文档 🚩 */
  rebuildImageDocs: () =>
    http.post<{ taskId: string; imageDocCount: number; imageCount: number; status: string }>('/system/model/rebuild-image-docs'),
  auditLogs: (query: Record<string, string | number | undefined>) =>
    http.get<PageResult<Record<string, unknown>>>('/audit/logs', query),
  auditStats: () => http.get<{ today: number; highRisk: number; activeUsers: number; failed: number }>('/audit/stats'),
  auditLogDetail: (id: number | string) => http.get<Record<string, unknown>>(`/audit/logs/${id}`),
  auditExport: () => http.post<{ taskId: string; status: string }>('/audit/export'),
}

export { chatStream }
export type { SSEHandlers }