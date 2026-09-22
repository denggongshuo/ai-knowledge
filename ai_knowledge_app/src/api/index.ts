import { http, chatStream, type SSEHandlers } from './request'
import type { ActiveUserStat, ApiResponse, AttachmentUploadResult, AuditLog, AuditLogDetail, ChatSessionItem, ChunkInfo, ChunkStat, DeptUsage, DocMeta, DocVersion, DocVersionDiff, DownloadRecord, DownloadTask, EmbeddingImpact, EmbeddingInfo, FeedbackStats, IndexTask, KbConfigForm, KbDocRow, KbHeat, KbQualityRanking, KnowledgeBase, MissQuestion, MissStatus, ModelConfigItem, ModelConfigResponse, ModelOption, ModelType, ModelUsage, NoticeCounts, NoticeItem, NoticeSetting, NoticeType, PageResult, ParentChunkRelation, ParseConfigView, ParseIssue, PendingItem, PermissionNode, QaOverview, RecycleItem, ReindexImpact, ReplayResult, RerankEffect, RetrievalOverview, RetrievalPipeline, RoleInfo, ScoreDistribution, SplitPresetDef, StorageQuota, TocItem, TopQuestion, TrendPoint, UsageOverview, UserInfo, VlImpact } from '@/types'

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
      embedding: EmbeddingInfo
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
      /**
       * 派生的展示文案（已就绪 / 索引中）。
       * ⚠️ 状态标签必须用它，不要按 `status` 分支 —— 后端的域内真值是
       * `READY` / `INDEXING`，与 `KnowledgeBase['status']` 的
       * `ACTIVE|BUILDING|PAUSED` 不是一个词表（W1）。
       */
      statusLabel: string
      /**
       * 配置已改、索引未重建（T-404 置位，S4 的重建索引清除）。
       * ⚠️ 它**不是** `status` 的一个取值：`status` 描述"索引过程"（READY/INDEXING），
       * 本字段描述"配置条件"（脏了/干净），两者可以同时成立。
       */
      reindexRequired: boolean
      createdAt: string
      updatedAt: string
      visibleRoles: string[]
      config: KbConfigForm
      embedding: EmbeddingInfo
      vlModel: { name: string; enabled: boolean; appliedAt: string }
      presetOptions: SplitPresetDef[]
    }>(`/kb/${id}/config`),
  saveConfig: (id: number | string, config: KbConfigForm) =>
    http.put<{ saved: boolean; needReindex: boolean; updatedAt: string }>(`/kb/${id}/config`, { config }),
  /**
   * 变更影响预估。
   * ⚠️ 提交的是**完整表单**而不是 diff 列表：服务端要用与保存完全同一段 diff 代码
   * 算出变了哪些字段，估算才和真实保存一致（只发展示后的中文串，服务端得先解析中文）。
   */
  impact: (id: number | string, config: KbConfigForm) =>
    http.post<ReindexImpact>(`/kb/${id}/config/impact`, { config }),
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
  /**
   * 上传文档（`multipart/form-data`，真文件字节）。
   *
   * <p>⚠️ 上一版发的是 **JSON**（`{name, size, md5}`）—— 一个字节的文件内容都没发，
   * 进度条是前端编的，后端收到的只有"我叫这个名"。这就是本批要修掉的"假上传"。
   * 现在：前端读 File → 算 MD5 → FormData 真字节 → `http.upload`（XHR 真进度）。
   *
   * <p>进度回调：{@code 0..99} 真实网络进度，{@code 100} 表示服务端已确认收下。
   */
  uploadDoc: (
    id: number | string,
    form: FormData,
    onProgress?: (percent: number) => void,
  ): Promise<{ docId: number; taskId: string; name: string; kbId: number; chunkTotal: number }> =>
    http.upload(`/kb/${id}/doc/upload`, form, onProgress),
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
  /**
   * 版本对比（`T-607` · **仅元数据**）。
   *
   * ⚠️ `against` 留空 = 与**当前版本**比（这是后端语义，不是"没指定就不比"）。
   * `against` 与 `version` 相同会被后端拒（`10030`「和自己是同一个版本，无需对比」）——
   * 页面因此不给当前版本行渲染「对比」以外的动作，也不允许拿它当对照。
   * 响应里的 `textDiff` 恒 `null`：需求 §6.8 只要求元数据对比，文本 diff 接口预留。
   */
  diff: (id: number | string, version: string, against?: string) =>
    http.get<DocVersionDiff>(`/doc/${id}/versions/${version}/diff`, { against }),
  indexTasks: (id: number | string) => http.get<{ list: IndexTask[]; total: number }>(`/doc/${id}/index-tasks`),
  chunkStat: (id: number | string) => http.get<ChunkStat>(`/doc/${id}/chunk-stat`),
  toc: (id: number | string) => http.get<{ list: TocItem[] }>(`/doc/${id}/toc`),
  /**
   * 分片列表。
   *
   * `type` 是**闭集**：`all/text/table/image/summary`（需求 §4.4 的四个展示分组 + 全部）。
   * ⚠️ 闭集之外的取值后端会回 `10030` —— **刻意不静默当成 `all`**：
   * "我筛了图片，它把全部给我了"会让用户以为这个库真没有图片块。
   */
  chunks: (id: number | string, type?: string, page?: number, size?: number) =>
    http.get<{ list: ChunkInfo[]; total: number; stat: ChunkStat }>(`/doc/${id}/chunks`, { type, page, size }),
  /**
   * 父块完整内容 + **全部**子块（`G7-1`）。
   *
   * ⚠️ `parentId` 必须是**父块**（`blockType = 'TABLE'` 且 `parentId === null`）的真实 id：
   * 传子块 id、传非表格块、传 `0` 都会得到明确的业务错。**`0` 不是"无父块"的哨兵**
   * ——顶层块的 `parentId` 是 `null`（`ChunkInfo.parentId` 的注释里写了这条）。
   */
  parentChunk: (id: number | string, parentId: number | string) =>
    http.get<ParentChunkRelation>(`/doc/${id}/chunks/${parentId}`),
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
  /**
   * 单文件下载：预签名 URL（有效期 10 分钟）。
   * ⚠️ 后端每次调用都会**写一条下载记录**（`G7-9`）—— 不要拿它当"探活"或预取来用。
   */
  downloadUrl: (docId: number | string) =>
    http.get<{ url: string; expiresAt: string; expiresInSec: number }>(`/doc/download-url/${docId}`),
  /**
   * 预览原文件：预签名 URL（`inline`，10 分钟）。
   * 与 `downloadUrl` 是**同一个对象、两种取法**：这条让浏览器直接打开（响应头 attachment→inline）。
   * ⚠️ 它**不写**下载记录 —— 预览不是下载。
   */
  previewUrl: (docId: number | string) =>
    http.get<{ url: string; expiresAt: string; expiresInSec: number }>(`/doc/${docId}/preview-url`),
  /**
   * 批量打包下载（异步任务）。受理时同步校验：非空 · ≤ 50 篇 · 合计 ≤ 2 GB ·
   * 每篇都可见（任一失败整批拒绝）。返回的 `status` 是调度层真值 `PENDING`。
   */
  batchDownload: (docIds: number[]) =>
    http.post<{ taskId: string; count: number; status: string }>('/doc/batch-download', { docIds }),
  /**
   * 单条打包任务。🔴 它是**唯一的"重新生成地址"入口**：后端每次读都现签一个
   * 新鲜的 10 分钟地址（完成态附带 `fileUrl`），所以"链接过期"重拉一次即可，
   * **不需要也没有**单独的续签接口。
   */
  downloadTask: (taskId: string) => http.get<DownloadTask>(`/doc/download-tasks/${taskId}`),
  downloadTasks: () => http.get<{ list: DownloadTask[]; total: number }>('/doc/download-tasks'),
  /**
   * 下载历史（仅本人）。
   * ⚠️ 请求参数**两个名字都发**（`size` 与 `pageSize`）：后端两个都认、`size` 优先，
   * 这里保留 `pageSize` 是为了不改动既有调用点。
   */
  downloadRecords: (query?: { page?: number; pageSize?: number; size?: number }) =>
    http.get<PageResult<DownloadRecord>>('/doc/download-records', query),
  /** 跨库文档列表（全站口径） */
  all: (query?: { keyword?: string; kbId?: string; type?: string; page?: number; size?: number }) =>
    http.get<{ list: (KbDocRow & { kbId: number; kbName: string })[]; total: number }>('/doc/all', query),
  /** 导出**下载记录清单**（异步 CSV，完成后经通知中心给地址）。无请求体。 */
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
  feedbackStats: (query?: { range?: string }) => http.get<FeedbackStats>('/ops/qa/feedback-stats', query),
  /**
   * 导出运营报表（异步，完成后走通知中心）。
   *
   * ⚠️ **筛选条件要发过去**：导出的是"页面上筛出来的那张表"，不是全表 ——
   * 需求 §1.7 那句「字段与表格列保持一致」的落点就在这（后端 `OpsDtos.ExportRequest`）。
   */
  qaExport: (body?: { status?: string; kbId?: string; keyword?: string }) =>
    http.post<{ taskId: string; status: string; note: string }>('/ops/qa/export', body),

  // ---- 20 检索质量分析 ----
  // ⚠️ 五个方法**都要吃 `range`**：页面上是同一个下拉框，页 11 那批就是因为
  // `loadFeedback()` 不传 range 而让"统计卡 7 天、榜单 7 天"变成两个不同的窗口。
  retrievalOverview: (query?: { range?: string }) =>
    http.get<RetrievalOverview>('/ops/retrieval/overview', query),
  pipeline: (query?: { range?: string }) =>
    http.get<RetrievalPipeline>('/ops/retrieval/pipeline', query),
  scoreDistribution: (query?: { range?: string }) =>
    http.get<ScoreDistribution>('/ops/retrieval/score-distribution', query),
  rerankEffect: (query?: { range?: string }) =>
    http.get<RerankEffect>('/ops/retrieval/rerank-effect', query),
  kbQuality: (query?: { range?: string }) =>
    http.get<KbQualityRanking>('/ops/retrieval/kb-quality', query),

  // ---- 21 使用统计 ----
  // 🔴 五个方法**都收 `range`**（`S8-M10-E` 接真时改的）。
  //    改之前四个把 `range: '7d'` **写死**、页面也从不传 —— 于是右上角那个
  //    「近 7 天 / 近 30 天 / 近 90 天」下拉框**换了也不生效**，
  //    而页面上的数**看着完全正常**（它确实是一份正确的 7 天统计）。
  //    这正是本仓点名的「页面显示已切换、实际仍打旧端点」那一类假交互。
  usageOverview: (query?: { range?: string }) =>
    http.get<UsageOverview>('/ops/usage/overview', query),
  trend: (query?: { range?: string; granularity?: string }) =>
    http.get<{ list: TrendPoint[]; total: number }>('/ops/usage/trend', { granularity: 'day', ...query }),
  byDept: (query?: { range?: string }) =>
    http.get<{ list: DeptUsage[]; active: number; users: number; questions: number }>('/ops/usage/by-dept', query),
  byKb: (query?: { range?: string }) =>
    http.get<{ list: KbHeat[]; questions: number; othersQuestions: number; othersPercent: number | null }>(
      '/ops/usage/by-kb',
      query,
    ),
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

/** 问答（图片附件 / 会话 / 反馈；流式生成走 chatStream） */
export const chatApi = {
  /**
   * 图片预上传：只落存储 + 落库，不调 VL（描述由后端在 completions 内部完成）。
   *
   * <p>⚠️ 走 `http.upload` 而**不是** `http.post`：需求 `09` §FR-SYS-04 的接口表写的是
   * `multipart/form-data`，而这里此前发的是一个只有 `{name,size,md5,mime}` 的 JSON ——
   * **文件字节从来没有上传过**。改用 `upload` 后顺带拿到 XHR 的真实进度，
   * 于是 `ChatView` 里那个"每 80ms 自己加 12%"的假进度定时器可以删掉。
   */
  uploadAttachment: (form: FormData, onProgress?: (percent: number) => void) =>
    http.upload<AttachmentUploadResult>('/chat/attachments', form, onProgress),
  /** 回看附件（含 VL 描述，只读展示）。⚠️ 描述字段在 T-512 之前为 null */
  attachment: (id: string) => http.get<Record<string, unknown>>(`/chat/attachments/${id}`),
  sessions: () => http.get<{ list: ChatSessionItem[]; total: number }>('/chat/sessions'),
  messages: (id: string) => http.get<{ list: unknown[]; total: number }>(`/chat/sessions/${id}/messages`),
  /**
   * 点赞 / 点踩（+ 点踩原因）—— 后端落库，运营页据此归因（`G5-14`）。
   *
   * <p>`reason` **只在点踩时**能带：点赞带原因后端会拒（`PARAM_INVALID`），
   * 因为需求 `07` §6.8 写的是「点踩时可选原因」。
   */
  feedback: (body: { messageId: string; type: 'like' | 'unlike'; reason?: string }) =>
    http.post<{ messageId: string; feedback: string; reason: string | null }>('/chat/feedback', body),
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
  /**
   * ⛔ **以下四条本版本都不支持**（用户 2026-09-19 裁定：只开放对话模型的切换）。
   *
   * 后端四个映射**都在**，但一律如实回 `MODEL_SWITCH_UNSUPPORTED`（业务码 `10081`）
   * + 「现象 + 下一步动作」。它们不是 404，是**有语义的拒绝**。
   *
   * ⚠️ **页面已不再调它们**（`ModelConfigView.vue` 的「更换嵌入模型」对话框与 VL 的
   * 「切换并重建」按钮已删）。保留这四个封装是**故意的**：后端接口真的存在，
   * `api/index.ts` 是它的镜像，删掉封装会让人以为接口没了。
   * 谁调用都会拿到那句拒绝 —— 这是正确行为，不是 bug。
   */
  embeddingImpact: () => http.get<EmbeddingImpact>('/system/model/embedding-impact'),
  changeEmbedding: (body: { model: string; dimension: number; rebuildMode: string }) =>
    http.post<{ taskId: string }>('/system/model/change-embedding', body),
  /** ⛔ 本版本不支持（见上方说明）。 */
  vlImpact: () => http.get<VlImpact>('/system/model/vl-impact'),
  /** ⛔ 本版本不支持（见上方说明）：重建含图片 / 扫描件的文档 🚩 */
  rebuildImageDocs: () =>
    http.post<{ taskId: string; imageDocCount: number; imageCount: number; status: string }>('/system/model/rebuild-image-docs'),
  /**
   * 审计日志列表。
   *
   * ⚠️ `startTime` / `endTime` 由页面**必传**（需求 §4.4：时间范围必选、默认今日，
   * 避免一次扫全表）。此前 `loadLogs()` 压根没发这两个参数，页面显示的日期范围
   * 与列表内容毫无关系 —— C 批已修，改回时先看 `AuditLogView.vue#loadLogs`。
   */
  auditLogs: (query: Record<string, string | number | undefined>) =>
    http.get<PageResult<AuditLog>>('/audit/logs', query),
  auditStats: (query?: { date?: string }) =>
    http.get<{ today: number; highRisk: number; activeUsers: number; failed: number }>('/audit/stats', query),
  auditLogDetail: (id: number | string) => http.get<AuditLogDetail>(`/audit/logs/${id}`),
  /**
   * 导出审计日志（异步）。筛选条件原样带走 —— 页面筛了什么就导出什么，
   * 传空对象表示全量导出（后端有 5 万行上限，超出会明确报错而不是静默截断）。
   */
  auditExport: (query?: {
    startTime?: string
    endTime?: string
    module?: string
    riskLevel?: string
    result?: string
    operator?: string
    keyword?: string
  }) => http.post<{ taskId: string; status: string; note: string }>('/audit/export', query ?? {}),
}

export { chatStream }
export type { SSEHandlers }