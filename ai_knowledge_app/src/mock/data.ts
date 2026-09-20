import type {
  UserInfo,
  RoleInfo,
  KnowledgeBase,
  DocInfo,
  DeptNode,
  PermissionNode,
  DataScope,
  SecretLevel,
  SplitPresetDef,
  KbConfigForm,
  ParseConfigView,
  KbDocRow,
  ParseIssue,
  RetrievalMode,
  UploadStatus,
  DocMeta,
  DocVersion,
  IndexTask,
  ChunkStat,
  ChunkInfo,
  ParentChunkRelation,
  TocItem,
  DownloadTask,
  DownloadRecord,
  StorageQuota,
  MissQuestion,
  MissStatus,
  ModelConfigItem,
  ModelUsage,
  VlImpact,
  QaOverview,
  TopQuestion,
  FeedbackStats,
  PipelineStage,
  ScoreBucket,
  RetrievalOverview,
  RerankEffect,
  KbQuality,
  UsageOverview,
  TrendPoint,
  DeptUsage,
  KbHeat,
  ActiveUserStat,
  NoticeItem,
  NoticeCounts,
  NoticeSetting,
  PendingItem,
  RecycleItem,
} from '../types/index.ts'

/**
 * Mock 种子数据 —— 所有数字与《01-示例数据字典.md》及原型 1:1 对齐
 * 用户 128 = 121 正常 + 5 待激活 + 2 禁用
 * 部门树：研发中心52(平台18+应用24+测试10)+销售34+人力12+运维16+培训8+未分配6 = 128
 * 角色 5 个：3/12/109/4/0 = 128（各角色成员数之和 = 用户总数）
 * 权限点：38 个原子权限码，12 个功能项聚合
 */

// ==================== 部门树 ====================
/** 部门树（本系统内维护；「未分配」为虚拟节点 id=0，用于发现异常账号） */
export const mockDeptTree: DeptNode[] = [
  {
    id: 11,
    name: '平台架构组',
    parentId: 1,
    userCount: 18,
  },
  { id: 12, name: '应用开发组', parentId: 1, userCount: 24 },
  { id: 13, name: '测试组', parentId: 1, userCount: 10 },
]

export const DEPTS: DeptNode[] = [
  { id: 0, name: '未分配', parentId: null, userCount: 6 },
  { id: 1, name: '研发中心', parentId: null, userCount: 52, children: mockDeptTree },
  { id: 2, name: '销售中心', parentId: null, userCount: 34 },
  { id: 3, name: '人力资源部', parentId: null, userCount: 12 },
  { id: 4, name: '运维部', parentId: null, userCount: 16 },
  { id: 5, name: '培训中心', parentId: null, userCount: 8 },
]

export const DEPT_NAME: Record<number, string> = {
  0: '未分配',
  1: '研发中心',
  11: '研发中心 / 平台架构组',
  12: '研发中心 / 应用开发组',
  13: '研发中心 / 测试组',
  2: '销售中心',
  3: '人力资源部',
  4: '运维部',
  5: '培训中心',
}

// ==================== 用户 ====================
/** 手工定义的典型用户（16 人，覆盖全部状态与角色组合） */
export const mockUsers: UserInfo[] = [
  { id: 1, username: 'admin', realName: '系统管理员', phone: '13800000001', email: 'admin@corp.com', department: '未分配', deptId: 0, roles: ['SUPER_ADMIN'], status: 'ACTIVE', createdAt: '2025-01-06 09:00', lastLoginAt: '2026-09-11 17:32', hasBusinessData: true },
  { id: 2, username: 'lin.gong', realName: '林工', phone: '13888886666', email: 'lin.gong@corp.com', department: '研发中心 / 平台架构组', deptId: 11, roles: ['SUPER_ADMIN'], status: 'ACTIVE', createdAt: '2026-03-12 10:24', lastLoginAt: '2026-09-11 15:12', hasBusinessData: true },
  { id: 3, username: 'zhang.wei', realName: '张伟', phone: '13888886667', email: 'zhang.wei@corp.com', department: '研发中心 / 平台架构组', deptId: 11, roles: ['KB_ADMIN'], status: 'ACTIVE', createdAt: '2026-03-12 10:24', lastLoginAt: '2026-09-11 11:30', hasBusinessData: true },
  { id: 4, username: 'li.na', realName: '李娜', phone: '13888886668', email: 'li.na@corp.com', department: '销售中心', deptId: 2, roles: ['USER'], status: 'ACTIVE', createdAt: '2026-04-02 09:30', lastLoginAt: '2026-09-10 17:45' },
  { id: 5, username: 'wang.fang', realName: '王芳', phone: '13888886669', email: 'wang.fang@corp.com', department: '研发中心 / 平台架构组', deptId: 11, roles: ['KB_ADMIN'], status: 'ACTIVE', createdAt: '2026-03-19 14:05', lastLoginAt: '2026-09-09 09:20', hasBusinessData: true },
  { id: 6, username: 'liu.qiang', realName: '刘强', phone: '13888886670', email: 'liu.qiang@corp.com', department: '运维部', deptId: 4, roles: ['AUDITOR'], status: 'DISABLED', createdAt: '2026-04-15 11:00', lastLoginAt: '2026-08-28 14:02', hasBusinessData: true },
  { id: 7, username: 'chen.jing', realName: '陈静', phone: '13888886671', email: 'chen.jing@corp.com', department: '培训中心', deptId: 5, roles: ['USER'], status: 'PENDING', createdAt: '2026-09-10 16:20' },
  { id: 8, username: 'zhao.lei', realName: '赵磊', phone: '13888886672', email: 'zhao.lei@corp.com', department: '销售中心', deptId: 2, roles: ['USER'], status: 'ACTIVE', createdAt: '2026-05-06 10:15', lastLoginAt: '2026-09-11 09:03', hasBusinessData: true },
  { id: 9, username: 'sun.jie', realName: '孙洁', phone: '13888886673', email: 'sun.jie@corp.com', department: '人力资源部', deptId: 3, roles: ['AUDITOR'], status: 'ACTIVE', createdAt: '2026-05-20 13:45', lastLoginAt: '2026-09-10 18:21' },
  { id: 10, username: 'zhou.min', realName: '周敏', phone: '13888886674', email: 'zhou.min@corp.com', department: '人力资源部', deptId: 3, roles: ['USER'], status: 'ACTIVE', createdAt: '2026-06-01 08:50', lastLoginAt: '2026-09-09 11:30' },
  { id: 11, username: 'wu.tao', realName: '吴涛', phone: '13888886675', email: 'wu.tao@corp.com', department: '运维部', deptId: 4, roles: ['USER'], status: 'DISABLED', createdAt: '2026-06-15 09:20', lastLoginAt: '2026-08-30 10:00' },
  { id: 12, username: 'liu.yang', realName: '刘洋', phone: '13888886676', email: 'liu.yang@corp.com', department: '培训中心', deptId: 5, roles: ['USER'], status: 'PENDING', createdAt: '2026-09-10 17:05' },
  { id: 13, username: 'huang.wei', realName: '黄伟', phone: '13888886677', email: 'huang.wei@corp.com', department: '研发中心 / 应用开发组', deptId: 12, roles: ['USER'], status: 'PENDING', createdAt: '2026-09-11 09:12' },
  { id: 14, username: 'xu.jing', realName: '徐静', phone: '13888886678', email: 'xu.jing@corp.com', department: '销售中心', deptId: 2, roles: ['USER'], status: 'PENDING', createdAt: '2026-09-11 10:33' },
  { id: 15, username: 'he.ping', realName: '何平', phone: '13888886679', email: 'he.ping@corp.com', department: '人力资源部', deptId: 3, roles: ['USER'], status: 'PENDING', createdAt: '2026-09-11 11:48' },
  { id: 16, username: 'gao.fei', realName: '高飞', phone: '13888886680', email: 'gao.fei@corp.com', department: '培训中心', deptId: 5, roles: ['USER'], status: 'ACTIVE', createdAt: '2026-07-01 15:30', lastLoginAt: '2026-09-08 16:40' },
]

const SURNAMES = ['王', '李', '张', '刘', '陈', '杨', '黄', '赵', '周', '吴', '徐', '孙', '马', '朱', '胡', '郭', '何', '林', '高', '罗']
const GIVEN = ['伟', '芳', '娜', '敏', '静', '磊', '军', '洋', '勇', '艳', '杰', '涛', '明', '超', '秀兰', '霞', '平', '刚', '桂英', '强']

/** 生成 128 个用户；部门人数精确对齐部门树（112 个生成用户） */
function genUsers(): UserInfo[] {
  const base = [...mockUsers]
  let idx = mockUsers.length + 1 // 从 17 开始
  // 各部门还需生成的数（与树数字自洽）
  const quota: [number, number][] = [
    [11, 15], // 平台架构组 18 - 手写3
    [12, 23], // 应用开发组 24 - 手写1
    [13, 10], // 测试组 10
    [2, 31], // 销售中心 34 - 手写3
    [3, 9], // 人力资源部 12 - 手写3
    [4, 14], // 运维部 16 - 手写2
    [5, 5], // 培训中心 8 - 手写3
    [0, 5], // 未分配 6 - 手写1
  ]
  // 角色配额（精确）：SUPER 1 / KB 10 / AUD 2 / USER 99 = 112，与手写合并后 3/12/4/109/0
  const genRoles: string[] = [
    'SUPER_ADMIN',
    ...Array.from({ length: 10 }, () => 'KB_ADMIN' as const),
    ...Array.from({ length: 2 }, () => 'AUDITOR' as const),
    ...Array.from({ length: 99 }, () => 'USER' as const),
  ]
  let g = 0
  for (const [deptId, n] of quota) {
    for (let i = 0; i < n; i++) {
      const surname = SURNAMES[(idx + i) % SURNAMES.length]!
      const given = GIVEN[(idx * 3 + i) % GIVEN.length]!
      const realName = surname + given
      const role = genRoles[g] ?? 'USER'
      g++
      base.push({
        id: idx,
        username: `staff${String(idx).padStart(3, '0')}`,
        realName,
        phone: `139${String(10000000 + idx * 137).padStart(8, '0')}`,
        department: DEPT_NAME[deptId] ?? '未分配',
        deptId,
        roles: [role],
        status: 'ACTIVE',
        createdAt: `2025-${String((idx % 9) + 1).padStart(2, '0')}-${String((idx % 27) + 1).padStart(2, '0')} 09:${String(idx % 60).padStart(2, '0')}`,
        lastLoginAt: `2026-09-${String((idx % 11) + 1).padStart(2, '0')} 1${idx % 10}:${String(idx % 60).padStart(2, '0')}`,
      })
      idx++
    }
  }
  return base
}

export const allUsers: UserInfo[] = genUsers()

// 各状态计数（对齐字典 121/5/2）
export const USER_STATS = {
  total: 128,
  active: 121,
  pending: 5,
  disabled: 2,
}

// ==================== 角色 ====================
/** 38 个原子权限码的权限树（4 大业务域 × 12 个功能项） */
export const PERMISSION_TREE: PermissionNode[] = [
  {
    id: 'grp:qa',
    label: '知识问答',
    children: [
      { id: 'qa:ask', label: '发起问答', code: 'qa:ask' },
      { id: 'qa:ask:stream', label: '流式问答', code: 'qa:ask:stream' },
      { id: 'qa:history:view', label: '查看历史会话', code: 'qa:history:view' },
      { id: 'qa:history:search', label: '搜索历史会话', code: 'qa:history:search' },
      { id: 'qa:history:delete', label: '删除会话记录', code: 'qa:history:delete' },
      { id: 'qa:session:export', label: '导出会话记录', code: 'qa:session:export' },
      { id: 'qa:feedback', label: '处理问答反馈', code: 'qa:feedback' },
    ],
  },
  {
    id: 'grp:kb',
    label: '知识库管理',
    children: [
      { id: 'kb:view', label: '查看知识库列表', code: 'kb:view' },
      { id: 'kb:list', label: '查看知识库详情', code: 'kb:list' },
      { id: 'kb:create', label: '创建知识库', code: 'kb:create' },
      { id: 'kb:update', label: '编辑知识库', code: 'kb:update' },
      { id: 'kb:config', label: '配置检索参数', code: 'kb:config' },
      { id: 'kb:delete', label: '删除知识库', code: 'kb:delete' },
      { id: 'kb:grant', label: '调整知识库授权', code: 'kb:grant' },
    ],
  },
  {
    id: 'grp:doc',
    label: '文档',
    children: [
      { id: 'doc:upload', label: '上传文档', code: 'doc:upload' },
      { id: 'doc:preview', label: '预览文档', code: 'doc:preview' },
      { id: 'doc:download', label: '下载文档', code: 'doc:download' },
      { id: 'doc:detail', label: '查看文档详情', code: 'doc:detail' },
      { id: 'doc:delete', label: '删除文档', code: 'doc:delete' },
      { id: 'doc:reindex', label: '重建索引', code: 'doc:reindex' },
      { id: 'doc:version', label: '版本管理', code: 'doc:version' },
    ],
  },
  {
    id: 'grp:system',
    label: '系统管理',
    children: [
      { id: 'user:list', label: '查看用户', code: 'user:list' },
      { id: 'user:create', label: '新建用户', code: 'user:create' },
      { id: 'user:update', label: '编辑用户', code: 'user:update' },
      { id: 'user:delete', label: '删除用户', code: 'user:delete' },
      { id: 'user:reset-pwd', label: '重置密码', code: 'user:reset-pwd' },
      { id: 'user:import', label: '批量导入', code: 'user:import' },
      { id: 'role:list', label: '查看角色', code: 'role:list' },
      { id: 'role:create', label: '新建角色', code: 'role:create' },
      { id: 'role:update', label: '编辑角色', code: 'role:update' },
      { id: 'role:delete', label: '删除角色', code: 'role:delete' },
      { id: 'grant:matrix', label: '角色权限矩阵', code: 'grant:matrix' },
      { id: 'grant:user-role', label: '用户角色分配', code: 'grant:user-role' },
      { id: 'audit:view', label: '查看审计日志', code: 'audit:view' },
      { id: 'audit:export', label: '导出审计日志', code: 'audit:export' },
      { id: 'system:model', label: '模型配置', code: 'system:model' },
      { id: 'kb:grant-extra', label: '知识库额外授权用户', code: 'kb:grant-extra' },
    ],
  },
]

/** 叶子权限码总数（应 = 38） */
export const ALL_PERMISSION_CODES: string[] = (() => {
  const codes: string[] = []
  for (const g of PERMISSION_TREE) {
    for (const c of g.children ?? []) if (c.code) codes.push(c.code)
  }
  return codes
})()

export const DATA_SCOPE_LABEL: Record<DataScope, string> = {
  ALL: '全部数据',
  DEPT_AND_CHILD: '本部门及以下',
  DEPT: '本部门',
  SELF: '仅本人',
  CUSTOM: '自定义',
}

/** 5 个角色：成员数 3+12+109+4+0 = 128；权限点数对齐原型 38/21/9/11/3 */
export const mockRoles: RoleInfo[] = [
  { id: 1, name: '超级管理员', code: 'SUPER_ADMIN', description: '全功能 + 全数据 + 系统配置，内置角色不可删除', memberCount: 3, dataScope: 'ALL', permissions: ['*'], enabled: true, builtin: true, createdAt: '2026-03-12 10:24' },
  { id: 2, name: '知识库管理员', code: 'KB_ADMIN', description: '知识库管理 + 文档维护 + 用户只读查看', memberCount: 12, dataScope: 'DEPT_AND_CHILD', permissions: ['qa:ask', 'qa:ask:stream', 'qa:history:view', 'qa:history:search', 'qa:history:delete', 'qa:session:export', 'qa:feedback', 'kb:view', 'kb:list', 'kb:create', 'kb:update', 'kb:config', 'kb:grant', 'doc:upload', 'doc:preview', 'doc:download', 'doc:detail', 'doc:delete', 'doc:reindex', 'doc:version', 'user:list'], enabled: true, builtin: true, createdAt: '2026-03-12 10:24' },
  { id: 3, name: '普通用户', code: 'USER', description: '检索问答 + 个人会话，仅访问本单位范围', memberCount: 109, dataScope: 'SELF', permissions: ['qa:ask', 'qa:ask:stream', 'qa:history:view', 'qa:history:search', 'kb:view', 'kb:list', 'doc:upload', 'doc:preview', 'doc:download'], enabled: true, builtin: true, createdAt: '2026-03-12 10:24' },
  { id: 4, name: '审计员', code: 'AUDITOR', description: '审计日志只读 + 问答只读，用于合规核查', memberCount: 4, dataScope: 'ALL', permissions: ['qa:ask', 'qa:ask:stream', 'qa:history:view', 'qa:history:search', 'kb:view', 'kb:list', 'doc:preview', 'doc:download', 'doc:detail', 'audit:view', 'audit:export'], enabled: true, builtin: false, createdAt: '2026-07-02 09:41' },
  { id: 5, name: '外部访客', code: 'GUEST', description: '受限只读（外部人员），当前未分配成员', memberCount: 0, dataScope: 'SELF', permissions: ['kb:view', 'doc:preview', 'doc:download'], enabled: false, builtin: false, createdAt: '2026-08-18 16:07' },
]

/** 知识库 8 个：文档 1,246 / 分片 38,502 */
export const mockKbs: KnowledgeBase[] = [
  { id: 1, name: '产品技术文档库', description: '产品白皮书、技术规范、架构设计', owner: '王芳', department: '研发中心', docCount: 128, chunkCount: 8240, status: 'ACTIVE', createdAt: '2025-06-01', updatedAt: '2026-09-11 16:40' },
  { id: 2, name: '项目管理制度库', description: '项目立项、评审、交付规范', owner: '林工', department: '研发中心', docCount: 86, chunkCount: 3102, status: 'ACTIVE', createdAt: '2025-06-15', updatedAt: '2026-09-10 14:20' },
  { id: 3, name: '销售话术与案例库', description: '销售培训、客户案例、常见答疑', owner: '赵磊', department: '销售中心', docCount: 214, chunkCount: 6895, status: 'ACTIVE', createdAt: '2025-07-01', updatedAt: '2026-09-11 10:05' },
  { id: 4, name: '财务政策与报销库', description: '报销制度、预算编制、税务指引', owner: '孙洁', department: '人力资源部', docCount: 45, chunkCount: 1280, status: 'ACTIVE', createdAt: '2025-07-20', updatedAt: '2026-09-09 16:30' },
  { id: 5, name: '人力资源制度库', description: '招聘、绩效、培训、员工手册', owner: '周敏', department: '人力资源部', docCount: 68, chunkCount: 2104, status: 'ACTIVE', createdAt: '2025-08-05', updatedAt: '2026-09-08 11:00' },
  { id: 6, name: '信息安全规范库', description: '等级保护、数据安全、应急预案', owner: '系统管理员', department: '运维部', docCount: 39, chunkCount: 1507, status: 'ACTIVE', createdAt: '2025-08-18', updatedAt: '2026-09-11 09:15' },
  { id: 7, name: '研发知识沉淀库', description: '技术分享、疑难解答、代码规范', owner: '王芳', department: '研发中心', docCount: 305, chunkCount: 9680, status: 'ACTIVE', createdAt: '2025-09-01', updatedAt: '2026-09-11 17:22' },
  { id: 8, name: '客户交付资料库', description: '交付文档、验收报告、培训材料', owner: '赵磊', department: '销售中心', docCount: 361, chunkCount: 5694, status: 'ACTIVE', createdAt: '2025-09-20', updatedAt: '2026-09-10 15:45' },
]

/** 文档示例（产品技术文档库，白皮书 24 页/222 分片） */
export const mockDocs: DocInfo[] = [
  { id: 1, kbId: 1, kbName: '产品技术文档库', title: 'AI 知识库平台白皮书 V3', path: '/产品技术文档/白皮书/AI知识库平台白皮书V3.pdf', type: 'PDF', size: 26_214_400, pageCount: 24, chunkCount: 222, status: 'READY', securityLevel: 'CONFIDENTIAL', owner: '王芳', uploadedAt: '2026-09-11 14:30', version: 'v3.0' },
  { id: 2, kbId: 1, kbName: '产品技术文档库', title: '系统架构设计说明书', path: '/产品技术文档/技术规范/系统架构设计说明书.docx', type: 'DOCX', size: 8_388_608, pageCount: 18, chunkCount: 132, status: 'READY', securityLevel: 'CONFIDENTIAL', owner: '林工', uploadedAt: '2026-09-08 10:00', version: 'v2.1' },
  { id: 3, kbId: 1, kbName: '产品技术文档库', title: 'RAG 检索链路设计稿', path: '/产品技术文档/技术规范/RAG检索链路设计稿.md', type: 'MD', size: 2_097_152, pageCount: 12, chunkCount: 96, status: 'READY', securityLevel: 'INTERNAL', owner: '林工', uploadedAt: '2026-09-05 15:20', version: 'v1.4' },
  { id: 4, kbId: 7, kbName: '研发知识沉淀库', title: '大模型微调实践总结', path: '/研发沉淀/AI实践/大模型微调实践总结.md', type: 'MD', size: 1_048_576, pageCount: 8, chunkCount: 54, status: 'READY', securityLevel: 'INTERNAL', owner: '王芳', uploadedAt: '2026-09-03 09:40', version: 'v1.0' },
  { id: 5, kbId: 7, kbName: '研发知识沉淀库', title: 'Milvus 向量检索优化实战', path: '/研发沉淀/AI实践/Milvus向量检索优化实战.md', type: 'MD', size: 786_432, pageCount: 10, chunkCount: 71, status: 'READY', securityLevel: 'INTERNAL', owner: '林工', uploadedAt: '2026-08-28 16:00', version: 'v1.2' },
  { id: 6, kbId: 6, kbName: '信息安全规范库', title: '数据安全管理制度', path: '/信息安全/制度规范/数据安全管理制度.pdf', type: 'PDF', size: 5_242_880, pageCount: 15, chunkCount: 98, status: 'READY', securityLevel: 'CONFIDENTIAL', owner: '系统管理员', uploadedAt: '2026-08-20 11:30', version: 'v2.0' },
]

/**
 * 文档生命周期状态机。
 *
 * <p>⚠️ **本常量当前没有任何引用**（全仓只有这一行定义，`mock/index.ts` 也没导入它）。
 * 页面上的文档状态文案在 {@code KbUploadView.vue} 的本地映射里（那份还要带 `type` 给标签上色），
 * 详情/预览页则是硬编码的「已就绪」。
 *
 * <p>🔴 `READY` 的文案 **2026-09-18 从「可检索」改成「已就绪」**：与 `READY` 字面一致，
 * 也与另外两处对齐。改之前它是个**陷阱** —— 三处状态文案里只有这一处写「可检索」，
 * 而它恰好没人引用，于是"看起来像两种说法、实际只有一种在跑"（`#85` 的前提在核实时被推翻：
 * 那"两个页面"并不存在）。留着这个值的话，下一个人一导入就会把漂移**重新引回来**。
 */
export const DOC_STATUS_LABEL: Record<DocInfo['status'], string> = {
  UPLOADED: '已上传',
  PARSING: '解析中',
  INDEXING: '索引中',
  READY: '已就绪',
  FAILED: '解析失败',
}

export const KB_STATUS_LABEL: Record<KnowledgeBase['status'], string> = {
  ACTIVE: '正常',
  BUILDING: '构建中',
  PAUSED: '已暂停',
}

export const ROLE_CODES: Record<string, string> = {
  SUPER_ADMIN: '超级管理员',
  KB_ADMIN: '知识库管理员',
  USER: '普通用户',
  AUDITOR: '审计员',
  GUEST: '外部访客',
}

export const USER_STATUS_LABEL: Record<UserInfo['status'], string> = {
  ACTIVE: '正常',
  PENDING: '待激活',
  DISABLED: '已禁用',
}

/** 三阈值（决策 5）：召回 0.15 宽进 / 未命中 0.35 严出 / 降权提示 0.65 */
export const THRESHOLDS = {
  recallThreshold: 0.15,
  missThreshold: 0.35,
  lowScoreWarn: 0.65,
  rerankTopK: 5,
}

/** 检索侧链路耗时基准（对齐数据字典 1668ms） */
export const RETRIEVAL_COST = {
  queryAnalysis: 12,
  embed: 86,
  vectorRecall: 34,
  bm25Recall: 8,
  rerank: 288,
  llmGeneration: 1240,
  total: 1668,
}

/** 白皮书 v3 分片构成（198 正文 + 12 表格 + 8 图片 + 4 摘要 = 222，不含表格子块 88） */
export const WHITEPAPER_CHUNK_MIX = {
  paragraph: 198,
  table: 12,
  image: 8,
  summary: 4,
  tableSubBlocks: 88,
  unrecognizedTables: 1,
}

// ==================== 知识库 / 文档（批次 2） ====================

/** 密级标签（四档） */
export const SECRET_LEVEL_LABEL: Record<SecretLevel, string> = {
  PUBLIC: '公开',
  INTERNAL: '内部',
  CONFIDENTIAL: '机密',
  SECRET: '绝密',
}

/** 密级升序（用于「只能提高不能降低」校验） */
export const SECRET_LEVEL_ORDER: SecretLevel[] = ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'SECRET']

/** 业务域候选 */
export const BUSINESS_DOMAINS = [
  '研发 / 平台',
  '研发 / 应用',
  '销售 / 售前',
  '人力资源',
  '财务 / 税务',
  '运维 / 安全',
  '培训 / 赋能',
]

/** 检索方式标签 */
export const RETRIEVAL_MODE_LABEL: Record<RetrievalMode, string> = {
  VECTOR: '向量检索',
  HYBRID: '混合检索',
  KEYWORD: '关键词检索',
}

/** 分片策略预设（5 个，选中即带出全部参数；仍可逐项覆盖 → 标记「自定义」） */
export const SPLIT_PRESETS: SplitPresetDef[] = [
  { id: 'GENERAL', label: '通用文档', desc: '结构一般、内容混合的常规资料', chunkSize: 512, overlap: 64, semanticSplit: false, tableAtomic: true },
  { id: 'STRUCTURED', label: '结构化规范', desc: '标题层级清晰的规范 / 手册 / API 文档', chunkSize: 640, overlap: 80, semanticSplit: false, tableAtomic: true },
  { id: 'CONTRACT', label: '合同条款', desc: '条款编号明确、需按条切分', chunkSize: 384, overlap: 48, semanticSplit: false, tableAtomic: true },
  { id: 'TABLE_HEAVY', label: '表格密集', desc: '报价单、参数表为主的资料', chunkSize: 512, overlap: 64, semanticSplit: false, tableAtomic: true },
  { id: 'QA_PAIR', label: '问答对', desc: 'FAQ、问答知识库', chunkSize: 320, overlap: 32, semanticSplit: false, tableAtomic: true },
]

/** 新建知识库默认配置（通用文档预设 + 系统默认检索配置） */
export function defaultKbConfig(): KbConfigForm {
  return {
    name: '',
    code: '',
    deptId: 1,
    visibleRoles: ['SUPER_ADMIN'],
    secretLevel: 'INTERNAL',
    businessDomain: '研发 / 平台',
    description: '',
    customTags: [],
    splitPolicy: { preset: 'GENERAL', chunkSize: 512, overlap: 64, recursiveSplit: true, semanticSplit: false },
    tablePolicy: {
      atomicTable: true,
      dualRepresentation: true,
      repeatHeader: true,
      mergeCrossPage: true,
      ocrTable: true,
      rowLimit: 30,
      rowWindow: 5,
    },
    retrievalConfig: {
      mode: 'HYBRID',
      recallThreshold: 0.15,
      rerankTopK: 5,
      rerankEnabled: true,
      tableWeight: 1.2,
      identifierProtect: true,
    },
  }
}

/** 只读解析配置（继承自知识库；V2 起上传页四栏展示） */
export const PARSE_CONFIG_VIEW: ParseConfigView = {
  split: [
    { label: '策略预设', value: '通用文档' },
    { label: '第一级 · 结构边界', value: '标题层级 / 表格整表 / 代码块 / 完整列表', locked: true },
    { label: '第二级 · 递归分隔符', value: '开 · 段落 → 句号 → 分号 → 空格' },
    { label: '第二级 · 语义切分', value: '关 · 仅无标题且超长时兜底', warning: true },
    { label: 'chunkSize / overlap', value: '512 / 64 tokens' },
    { label: '嵌入模型', value: 'BAAI/bge-m3 · 1024 维 · L2', locked: true },
  ],
  table: [
    { label: '整表原子化', value: '开 · 锁定不切分', locked: true },
    { label: '双表示', value: 'Markdown 给大模型 / 行式文本给向量' },
    { label: '表头重复', value: '开' },
    { label: '跨页表格合并', value: '开 · 列数一致时并回一张表' },
    { label: '扫描件表格识别', value: '开 · 失败标红' },
    { label: '回表行数上限', value: '30 行 · ±5' },
    { label: '表格召回通道', value: '未单开（Rerank 加权 1.2 代替）' },
  ],
  image: [
    { label: '图片处理', value: '开 · 图片整体原子化（不切分）', locked: true },
    { label: '题注优先', value: '有题注则并入图片描述' },
    { label: 'VL 描述生成', value: '开 · 产出描述 + 检索摘要', enabled: true },
    { label: '当前 VL 模型', value: 'Qwen2.5-VL-72B', locked: true },
    { label: '图片去重', value: '按 md5 去重，同图只理解一次' },
    { label: '识别失败标记', value: '开 · 标红以便补录', warning: true },
  ],
  enhance: [
    { label: '标题路径注入', value: '把「3. 接口设计 > 3.1 鉴权流程」拼进分片', enabled: true },
    { label: 'HyQ 假设性问题', value: '为每个分片生成可能被问到的问句一并索引', enabled: true },
    { label: '文档级摘要', value: '仅文档级，不做 chunk 级摘要', enabled: true },
    { label: '内容标签抽取', value: '复用摘要同一次 LLM 调用，边际成本为零', enabled: true },
  ],
}

/** 知识库文档列表示例（产品技术文档库 kb=1，覆盖四种状态与解析质量） */
const KB1_DOCS: KbDocRow[] = [
  { id: 1, title: 'AI 知识库平台白皮书 V3', type: 'PDF', size: 26_214_400, chunkCount: 222, status: 'READY', tableTotal: 12, tableParsed: 11, imageTotal: 8, imageParsed: 8, owner: '王芳', uploadedAt: '2026-09-11 14:30' },
  { id: 2, title: '系统架构设计说明书', type: 'DOCX', size: 8_388_608, chunkCount: 132, status: 'READY', tableTotal: 4, tableParsed: 4, imageTotal: 5, imageParsed: 4, owner: '林工', uploadedAt: '2026-09-08 10:00' },
  { id: 3, title: 'RAG 检索链路设计稿', type: 'MD', size: 2_097_152, chunkCount: 96, status: 'READY', tableTotal: 3, tableParsed: 3, imageTotal: 4, imageParsed: 4, owner: '林工', uploadedAt: '2026-09-05 15:20' },
  { id: 4, title: '服务接口清单', type: 'XLSX', size: 1_572_864, chunkCount: 88, status: 'READY', tableTotal: 6, tableParsed: 6, imageTotal: 0, imageParsed: 0, owner: '张伟', uploadedAt: '2026-09-04 09:12' },
  { id: 5, title: '数据库设计规范', type: 'DOCX', size: 3_145_728, chunkCount: 71, status: 'READY', tableTotal: 5, tableParsed: 5, imageTotal: 3, imageParsed: 3, owner: '张伟', uploadedAt: '2026-09-03 16:45' },
  { id: 6, title: '部署运维手册', type: 'PDF', size: 12_582_912, chunkCount: 164, status: 'READY', tableTotal: 8, tableParsed: 8, imageTotal: 6, imageParsed: 6, owner: '王芳', uploadedAt: '2026-09-02 11:20' },
  { id: 7, title: 'API 鉴权流程图解', type: 'PPTX', size: 6_291_456, chunkCount: 76, status: 'READY', tableTotal: 2, tableParsed: 2, imageTotal: 12, imageParsed: 12, owner: '林工', uploadedAt: '2026-09-01 14:08' },
  { id: 8, title: '性能压测报告', type: 'PDF', size: 9_437_184, chunkCount: 112, status: 'INDEXING', tableTotal: 7, tableParsed: 5, imageTotal: 9, imageParsed: 7, owner: '张伟', uploadedAt: '2026-09-11 17:05' },
  { id: 9, title: '2026 年度技术白皮书', type: 'PDF', size: 31_457_280, chunkCount: 0, status: 'PARSING', tableTotal: 12, tableParsed: 0, imageTotal: 14, imageParsed: 0, owner: '王芳', uploadedAt: '2026-09-11 17:36' },
  { id: 10, title: '历史故障复盘汇编', type: 'DOCX', size: 4_194_304, chunkCount: 0, status: 'FAILED', tableTotal: 3, tableParsed: 0, imageTotal: 2, imageParsed: 0, owner: '刘强', uploadedAt: '2026-09-11 15:02' },
  { id: 11, title: '中间件选型对比', type: 'XLSX', size: 2_621_440, chunkCount: 57, status: 'READY', tableTotal: 9, tableParsed: 9, imageTotal: 1, imageParsed: 1, owner: '张伟', uploadedAt: '2026-08-30 10:30' },
  { id: 12, title: '灰度发布方案', type: 'MD', size: 1_048_576, chunkCount: 43, status: 'READY', tableTotal: 1, tableParsed: 1, imageTotal: 0, imageParsed: 0, owner: '林工', uploadedAt: '2026-08-29 09:15' },
]

/** 各库文档行（未定义者回落到通用生成） */
export function kbDocsOf(kbId: number): KbDocRow[] {
  if (kbId === 1) return KB1_DOCS
  const kb = mockKbs.find((k) => k.id === kbId)
  if (!kb) return []
  const types = ['PDF', 'DOCX', 'XLSX', 'MD', 'PPTX']
  const owners = ['王芳', '林工', '张伟', '赵磊', '周敏']
  const n = Math.min(12, kb.docCount)
  return Array.from({ length: n }, (_, i) => {
    const tableTotal = (i % 5) + 1
    const unparsed = i === 2 ? 1 : 0
    const imageTotal = i % 3 === 0 ? 0 : (i % 4) + 1
    return {
      id: kbId * 1000 + i + 1,
      title: `${kb.name} · 资料 ${String(i + 1).padStart(2, '0')}`,
      type: types[i % types.length]!,
      size: 1_048_576 * ((i % 9) + 2),
      chunkCount: 40 + i * 11,
      status: 'READY' as DocInfo['status'],
      tableTotal,
      tableParsed: tableTotal - unparsed,
      imageTotal,
      imageParsed: i === 3 ? imageTotal - 1 : imageTotal,
      owner: owners[i % owners.length]!,
      uploadedAt: `2026-09-${String(11 - (i % 9)).padStart(2, '0')} ${String(9 + (i % 8)).padStart(2, '0')}:20`,
    }
  })
}

/** 未识别表格清单（驱动上传页顶部红色告警条） */
export const mockParseIssues: ParseIssue[] = [
  { docId: 1, docTitle: 'AI 知识库平台白皮书 V3', pageNo: 12, reason: '表格为扫描图片，未提取到可复制文本结构' },
  { docId: 8, docTitle: '性能压测报告', pageNo: 7, reason: '跨页表格列数不一致，未能合并' },
  { docId: 10, docTitle: '历史故障复盘汇编', pageNo: 3, reason: '文件编码为 GBK，无法按 UTF-8 解码' },
]

/** 上传队列状态标签 */
export const UPLOAD_STATUS_LABEL: Record<UploadStatus, string> = {
  UPLOADING: '上传中',
  PARSING: '解析中',
  DONE: '已完成',
  FAILED: '失败',
}

/** 支持的上传格式白名单（9 种） */
export const UPLOAD_ACCEPT = ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt', 'md']

// ==================== 文档域（批次 3：详情 / 预览 / 下载） ====================

/** 文档元信息（docId=1 白皮书，与 08/17 原型 1:1：12 张表 / 88 子块 / 第 12 页 1 处未识别） */
export function docMetaOf(docId: number): DocMeta {
  const base = mockDocs.find((d) => d.id === docId) ?? mockDocs[0]!
  const isWhitepaper = base.id === 1
  return {
    ...base,
    fileDesc: base.type === 'PDF' ? 'PDF（原生文本，非扫描件）' : `${base.type} 文档`,
    department: mockKbs.find((k) => k.id === base.kbId)?.department ?? '未分配',
    md5: 'a3f8c1e2b47d9e02c8f1a5b3d7e94002',
    storagePath: `kb-prod/${base.id === 1 ? '2026/whitepaper.pdf' : `${base.kbId}/${base.title}.${base.type.toLowerCase()}`}`,
    tableTotal: isWhitepaper ? 12 : 4,
    tableParsed: isWhitepaper ? 11 : 4,
    imageTotal: isWhitepaper ? 8 : 3,
    imageParsed: isWhitepaper ? 8 : 3,
    citations: isWhitepaper ? 37 : 12,
    downVotes: isWhitepaper ? 2 : 0,
    lastIndexAt: '2026-09-11 14:34:20',
    kbSecurityLevel: 'INTERNAL',
    tags: isWhitepaper ? ['产品架构', '接口鉴权', '检索策略', '错误码规范'] : ['技术规范', '架构设计'],
  }
}

/** 文档版本（同名上传自动归档，不覆盖） */
export const mockDocVersions: DocVersion[] = [
  { version: 'v3', size: 26_214_400, chunkCount: 222, owner: '王芳', at: '2026-09-11 14:30', current: true, note: '新增表格双表示章节' },
  { version: 'v2', size: 22_020_096, chunkCount: 198, owner: '张伟', at: '2026-08-15 10:18', current: false, note: '补充检索链路设计' },
  { version: 'v1', size: 18_874_368, chunkCount: 176, owner: '林工', at: '2026-06-02 14:02', current: false, note: '首次发布' },
]

/** 索引任务记录 */
export const mockIndexTasks: IndexTask[] = [
  { id: 4, stage: '索引完成', at: '2026-09-11 14:34', version: 'v3', status: 'SUCCESS', durationSec: 74, chunkCount: 222, detail: '切分 + 表格识别 + 向量化' },
  { id: 3, stage: '开始索引', at: '2026-09-11 14:33', version: 'v3', status: 'RUNNING', detail: '切分 + 表格识别 + 向量化' },
  { id: 2, stage: '索引完成', at: '2026-08-15 10:22', version: 'v2', status: 'SUCCESS', durationSec: 62, chunkCount: 198 },
  { id: 1, stage: '首次索引', at: '2026-06-02 14:05', version: 'v1', status: 'INFO', durationSec: 58, chunkCount: 176 },
]

/** 分片块类型统计（198 正文 + 12 表格 + 4 摘要 + 8 图片 = 222） */
export const mockChunkStat: ChunkStat = {
  total: 222,
  text: 198,
  table: 12,
  summary: 4,
  image: 8,
  tableSubBlocks: 88,
  avgTokens: 312,
  titlePathCoverage: 96.3,
}

/** 分片样例（正文 / 表格 / 摘要 / 图片） */
export const mockChunks: ChunkInfo[] = [
  { id: 47, docId: 1, docTitle: 'AI 知识库平台白皮书 V3', titlePath: '3. 接口设计 > 3.1 鉴权流程', blockType: 'PARAGRAPH', index: 47, tokens: 312, content: '客户端首次登录成功后，服务端签发一对令牌：访问令牌有效期 2 小时，刷新令牌有效期 7 天。当访问令牌过期时，客户端应使用刷新令牌换取新的访问令牌，而不是重新走登录流程。' },
  { id: 48, docId: 1, docTitle: 'AI 知识库平台白皮书 V3', titlePath: '3. 接口设计 > 3.1 鉴权流程', blockType: 'PARAGRAPH', index: 48, tokens: 286, content: '访问令牌存放于内存或 sessionStorage，避免 XSS 窃取；刷新令牌通过 HttpOnly Cookie 下发，前端不可读取。' },
  { id: 88, docId: 1, docTitle: 'AI 知识库平台白皮书 V3', titlePath: '3. 接口设计 > 3.2 错误码规范', blockType: 'TABLE', index: 88, tokens: 640, parentId: null, childrenCount: 8, content: '表 2：接口错误码对照表（14 行 × 3 列）' },
  { id: 150, docId: 1, docTitle: 'AI 知识库平台白皮书 V3', titlePath: '2. 系统架构 > 2.1 整体链路', blockType: 'IMAGE', index: 150, tokens: 428, content: '图 1：RAG 全链路架构图 —— 上传解析 → 分片与索引增强 → 混合检索（向量 + BM25）→ Rerank 精排 → 生成。图注：检索侧的 Rerank 是延迟最大项（288ms）。\n\n关键词：RAG, 架构图, 混合检索, Rerank, 索引增强' },
  { id: 151, docId: 1, docTitle: 'AI 知识库平台白皮书 V3', titlePath: '4. 检索策略 > 4.2 混合检索与重排', blockType: 'IMAGE', index: 151, tokens: 386, content: '图 2：重排前后 Top1 命中率对比（柱状图）—— 重排前 63%，重排后 81%，净提升 18 个百分点。\n\n关键词：重排效果, Top1 命中率, 63%, 81%, 评测' },
  { id: 214, docId: 1, docTitle: 'AI 知识库平台白皮书 V3', titlePath: '文档级摘要', blockType: 'SUMMARY', index: 222, tokens: 186, parentId: null, content: '本文档描述 2026 版产品技术架构、接口鉴权与检索策略，涵盖分片增强、混合检索与重排链路。' },
]

/** 父子块关系（命中子块 → parent_id → 完整父块） */
export const mockParentChunk: ParentChunkRelation = {
  child: { ref: 'T-03-002', content: '错误码 10021，含义 无知识库访问权限，处理建议 检查该用户的角色与知识库授权' },
  parent: {
    ref: 'T-03',
    title: '表 2：接口错误码对照表',
    rows: 14,
    cols: 3,
    returnedRows: 14,
    content: '| 错误码 | 含义 | 处理建议 |\n|---|---|---|\n| 10021 | 无知识库访问权限 | 检查该用户的角色与知识库授权 |\n| 10022 | 文档已被删除 | 刷新文档列表或联系管理员 |\n| … | … | … |',
  },
}

/** 文档目录树 */
export const mockToc: TocItem[] = [
  { id: 'h1', label: '1. 文档概述', level: 1 },
  { id: 'h2', label: '2. 系统架构', level: 1 },
  { id: 'h2-1', label: '2.1 整体链路', level: 2 },
  { id: 'h2-2', label: '2.2 技术选型', level: 2 },
  { id: 'h3', label: '3. 接口设计', level: 1 },
  { id: 'h3-1', label: '3.1 鉴权流程', level: 2 },
  { id: 'h3-2', label: '3.2 错误码规范', level: 2 },
  { id: 'h4', label: '4. 检索策略', level: 1 },
  { id: 'h4-1', label: '4.1 分片与索引增强', level: 2 },
  { id: 'h4-2', label: '4.2 混合检索与重排', level: 2 },
  { id: 'h5', label: '5. 部署说明', level: 1 },
]

/** 下载任务（四态） */
export const mockDownloadTasks: DownloadTask[] = [
  { taskId: 'dl_a', name: '产品技术文档库 · 批量下载', count: 8, size: 186_646_528, status: 'PACKING', percent: 62, createdAt: '2026-09-12 13:40' },
  { taskId: 'dl_b', name: 'AI 知识库平台白皮书 V3.pdf', count: 1, size: 26_214_400, status: 'DONE', percent: 100, createdAt: '2026-09-12 11:05', fileUrl: '/mock-files/whitepaper-v3.pdf', expiresAt: '2026-09-12 11:15' },
  { taskId: 'dl_c', name: '研发知识沉淀库 · 批量下载', count: 12, size: 96_468_992, status: 'EXPIRED', percent: 100, createdAt: '2026-09-12 09:12', errorMsg: '预签名链接已过期（有效期 10 分钟），请重新生成' },
  { taskId: 'dl_d', name: '客户合同库 · 批量下载', count: 3, size: 41_943_040, status: 'FAILED', percent: 0, createdAt: '2026-09-11 18:20', errorMsg: '权限不足：其中 1 篇文档不在你的下载范围内' },
]

/** 下载记录 */
export const mockDownloadRecords: DownloadRecord[] = [
  { id: 1, docId: 1, docTitle: 'AI 知识库平台白皮书 V3', kbName: '产品技术文档库', size: 26_214_400, operator: '林工', at: '2026-09-12 11:05', ip: '10.12.3.44', type: 'SINGLE' },
  { id: 2, docId: 0, docTitle: '研发知识沉淀库 · 12 个文件', kbName: '研发知识沉淀库', size: 96_468_992, operator: '王芳', at: '2026-09-12 09:12', ip: '10.12.3.18', type: 'BATCH' },
  { id: 3, docId: 6, docTitle: '数据安全管理制度', kbName: '信息安全规范库', size: 5_242_880, operator: '系统管理员', at: '2026-09-11 16:48', ip: '10.12.1.2', type: 'SINGLE' },
  { id: 4, docId: 3, docTitle: 'RAG 检索链路设计稿', kbName: '产品技术文档库', size: 2_097_152, operator: '张伟', at: '2026-09-11 14:20', ip: '10.12.4.77', type: 'SINGLE' },
]

/** 存储用量与配额 */
export const mockStorageQuota: StorageQuota = {
  used: '2.4 GB',
  kbCount: 8,
  docCount: 1246,
  chunkCount: 38502,
  // 全站本月 tokens（与 /system/model/usage 的 tokens 合计一致：405.2 万）
  monthTokens: 4_052_000,
}

/** 跨库文档列表（供 09 页；总量取全站口径 1,246） */
export function allDocsAcross(): (KbDocRow & { kbId: number; kbName: string })[] {
  const rows: (KbDocRow & { kbId: number; kbName: string })[] = []
  for (const kb of mockKbs) {
    const part = kbDocsOf(kb.id)
    rows.push(...part.slice(0, 3).map((r) => ({ ...r, kbId: kb.id, kbName: kb.name })))
  }
  return rows
}

// ==================== 运营分析（批次 4） ====================
/** 口径统一：全部数字与《08-需求-运营分析.md》一致
 *  提问总数 1284 = 未命中 108 + 其余 1176；未命中率 8.4%；待处理 23
 *  趋势 7 天 45+241+236+229+268+213+52 = 1284（工作日均值 237 / 周末 49 / 日均 183）
 *  部门 73 活跃 / 128 人 / 1284 提问；知识库热度合计 1284；分档合计 1284
 */
export const mockQaOverview: QaOverview = {
  totalQuestions: 1284,
  missed: 108,
  missedRate: 8.4,
  avgTopScore: 0.71,
  pending: 23,
  // 与 `V2` 种子的 `retrieval.miss-threshold` 同值（0.35）—— 卡片标题显示它
  missThreshold: 0.35,
}

/** 未命中问题清单（默认按提问次数降序；用户问法保持原样不改写） */
export const mockMissQuestions: MissQuestion[] = [
  { id: 1, question: '订单系统的分片方案是谁提出的？', askCount: 18, lastAskAt: '2026-09-11 16:20', asker: '张伟', topScore: 0.29, status: 'PENDING', kbName: '产品技术文档库' },
  { id: 2, question: '合同里的违约金上限是多少？', askCount: 15, lastAskAt: '2026-09-11 15:02', asker: '赵磊', topScore: 0.22, status: 'PENDING', kbName: '客户合同库' },
  { id: 3, question: 'A-100 与 A-1000 型号的单价差异', askCount: 12, lastAskAt: '2026-09-11 15:42', asker: '李娜', topScore: 0.33, status: 'PENDING', kbName: '产品技术文档库', hasImageDesc: true },
  { id: 4, question: '财务报销需要什么等级的机密要求？', askCount: 9, lastAskAt: '2026-09-11 14:08', asker: '周敏', topScore: 0.19, status: 'IN_LIST', kbName: '财务政策与报销库' },
  { id: 5, question: 'SQL 递归 CTE 超过几跳会退化？', askCount: 7, lastAskAt: '2026-09-11 11:26', asker: '林工', topScore: 0.31, status: 'PENDING', kbName: '研发知识沉淀库' },
  { id: 6, question: 'Milvus 2.6 支持多少维度向量？', askCount: 6, lastAskAt: '2026-09-10 17:12', asker: '张伟', topScore: 0.27, scoreAfter: 0.68, status: 'RESOLVED', kbName: '研发知识沉淀库' },
  { id: 7, question: '培训资料里的考核标准是什么？', askCount: 5, lastAskAt: '2026-09-10 10:35', asker: '高飞', topScore: 0.18, status: 'IGNORED', ignoredBy: '系统管理员', kbName: '外部培训资料库' },
  { id: 8, question: '一致性哈希的虚拟节点怎么配？', askCount: 4, lastAskAt: '2026-09-09 16:50', asker: '王芳', topScore: 0.3, status: 'IN_LIST', kbName: '研发知识沉淀库' },
]

export const MISS_STATUS_LABEL: Record<MissStatus, string> = {
  PENDING: '待处理',
  IN_LIST: '已加入清单',
  RESOLVED: '已解决',
  IGNORED: '已忽略',
}

/** 高频问题榜（真实问法是校准 HyQ 的语料；分数偏低的说明 HyQ 与真实问法有偏差） */
export const mockTopQuestions: TopQuestion[] = [
  { question: '分片策略是怎么设计的？', count: 86, avgTopScore: 0.88 },
  { question: '召回阈值为什么是 0.15？', count: 64, avgTopScore: 0.84 },
  { question: 'Rerank 有什么作用，值不值得留？', count: 52, avgTopScore: 0.86 },
  { question: '权限模型是怎么算的？', count: 41, avgTopScore: 0.79 },
  { question: '检索延迟是多少，瓶颈在哪？', count: 33, avgTopScore: 0.62 },
  { question: '图谱旁路怎么降级？', count: 28, avgTopScore: 0.81 },
  { question: '表格内容怎么检索？', count: 22, avgTopScore: 0.83 },
  { question: '未命中问题怎么闭环？', count: 19, avgTopScore: 0.76 },
]

/**
 * 点踩原因归因（负反馈率 2.2% vs 阈值 5%；前两项合计 75%）。
 *
 * 🔴 **原因文案取需求那一份**：需求 `07` §6.8 与《01-示例数据字典》§7.11 都写的是
 * 「回答太**啰嗦**」「引用**来源不对**」，而后端 `ChatFeedbackReason` 与它们逐字一致。
 * 这里原先写的是「回答太**笼统**」「引用**不相关**」—— 四方里三方一致、只有 mock 独自漂着。
 * 由来见 `#164`；机器对账见 `T-O20`（`scripts/check-enums.mjs`）。
 *
 * ⚠️ `unreasoned` 不是凑数的：点踩时原因**可不填**（需求 §6.8「可选」），
 * 那部分既不属于任何原因、也不能丢 —— 三个数合起来才等于 `negativeTotal`。
 */
export const mockFeedbackStats: FeedbackStats = {
  negativeTotal: 28,
  negativeRate: 2.2,
  threshold: 5,
  unreasoned: 0,
  reasons: [
    { reason: '答案不准确', count: 12, percent: 42.9 },
    { reason: '没找到相关内容', count: 9, percent: 32.1 },
    { reason: '回答太啰嗦', count: 4, percent: 14.3 },
    { reason: '引用来源不对', count: 2, percent: 7.1 },
    { reason: '其他', count: 1, percent: 3.6 },
  ],
}

// ---------------- 20 检索质量分析 ----------------
export const mockRetrievalOverview: RetrievalOverview = {
  totalQuestions: 1284,
  comparableQuestions: 1284,
  noRerankQuestions: 0,
  emptyRecallQuestions: 0,
  avgTopScore: 0.71,
  afterTop1HitRate: 81,
  rerankGain: 18,
  avgCitedChunks: 3.2,
  // 与 `V2` 种子的 `retrieval.miss-threshold` 同值
  missThreshold: 0.35,
}

/**
 * 链路九段（段名与 `ChatWireMapper.stages()` 逐字一致）。
 *
 * ⚠️ 需求 §2.2 的「关注指标」列里**只有两项有采集点**（去重后候选数 / 重排前后命中率差），
 * 其余四个后端没有数据源 ⇒ 与真实后端一样填 `null`（页面显示 `—`），
 * **不留** 96.4% / 88.2% 这类看起来像真的示例值。
 *
 * 检索侧 = 12 + 30 + 56 + 34 + 8 + 288 = 428ms；生成 1240ms；其他 0 ⇒ 全链路 1668ms。
 */
export const mockPipeline: PipelineStage[] = [
  { name: '可见范围', costMs: 0, metric: null, metricValue: null, action: null },
  { name: '查询解析', costMs: 12, metric: '改写成功率', metricValue: null, action: '调整 HyQ 生成提示词' },
  { name: 'Embedding', costMs: 30, metric: null, metricValue: null, action: null },
  { name: '向量召回', costMs: 56, metric: 'Top20 内命中率', metricValue: null, action: '调 chunkSize / 换嵌入模型' },
  { name: 'BM25 召回', costMs: 34, metric: '精确匹配命中率', metricValue: null, action: '优化分词与标识符保护' },
  { name: 'RRF 融合', costMs: 8, metric: '去重后候选数', metricValue: '26', action: '调整融合权重' },
  { name: 'Rerank', costMs: 288, metric: '重排前后命中率差', metricValue: '+18pp', action: '切换重排模型 / 调候选数' },
  { name: '表格回表', costMs: 0, metric: null, metricValue: null, action: null },
  { name: 'LLM 生成', costMs: 1240, metric: '首字延迟 / 引用采纳率', metricValue: null, action: '调 prompt 与上下文长度' },
]

/** Top1 相似度分布（需求五档 + 「无法定档」，合计 1284） */
export const mockScoreDistribution: ScoreBucket[] = [
  { label: '优秀', range: '0.80 ~ 1.00', count: 514, percent: 40.0 },
  { label: '良好', range: '0.60 ~ 0.80', count: 398, percent: 31.0 },
  { label: '一般', range: '0.40 ~ 0.60', count: 231, percent: 18.0 },
  { label: '边缘', range: '0.35 ~ 0.40', count: 33, percent: 2.6, danger: true },
  { label: '未命中', range: '< 0.35', count: 108, percent: 8.4, danger: true },
  // 空召回（没有 Top1）+ 未重排（分数不同量纲）—— 这两类装不进上面五档
  { label: '无法定档', range: '—', count: 0, percent: 0, danger: true },
]

export const mockRerankEffect: RerankEffect = {
  beforeTop1HitRate: 63,
  afterTop1HitRate: 81,
  gainPercent: 18,
  avgLatencyMs: 288,
  covered: 1284,
  comparable: 1284,
}

/** 各知识库检索质量排行（提问数合计 ≤ 1284，未列出的归入其他） */
export const mockKbQuality: KbQuality[] = [
  { kbId: null, kbName: '产品技术文档库', questions: 486, avgTopScore: 0.78, missRate: 4.2, trend: 'STABLE', issue: '表格密集 · 1 张表格未识别' },
  { kbId: null, kbName: '客户合同库', questions: 238, avgTopScore: 0.62, missRate: 14.3, trend: 'DOWN', issue: '跨页表格多 · 索引未完成（274/316）' },
  { kbId: null, kbName: '人力资源制度库', questions: 212, avgTopScore: 0.74, missRate: 6.1, trend: 'STABLE', issue: '—' },
  { kbId: null, kbName: '运维故障知识库', questions: 164, avgTopScore: 0.71, missRate: 7.8, trend: 'STABLE', issue: '—' },
  { kbId: null, kbName: '外部培训资料库', questions: 84, avgTopScore: 0.58, missRate: 19.0, trend: 'DOWN', issue: '扫描件为主 · OCR 质量差' },
]

// ---------------- 21 使用统计 ----------------
export const mockUsageOverview: UsageOverview = {
  activeUsers: 73,
  totalUsers: 128,
  activeRate: 57,
  totalQuestions: 1284,
  avgPerUser: 17.6,
  coveredDepts: 5,
  totalDepts: 5,
  workdayAvg: 237,
  weekendAvg: 49,
  dailyAvg: 183,
}

/** 近 7 天趋势（合计 1284；周末灰色、今天浅色） */
export const mockTrend: TrendPoint[] = [
  { date: '2026-09-06', label: '09-06 周日', value: 45, weekend: true, today: false },
  { date: '2026-09-07', label: '09-07 周一', value: 241, weekend: false, today: false },
  { date: '2026-09-08', label: '09-08 周二', value: 236, weekend: false, today: false },
  { date: '2026-09-09', label: '09-09 周三', value: 229, weekend: false, today: false },
  { date: '2026-09-10', label: '09-10 周四', value: 268, weekend: false, today: false },
  { date: '2026-09-11', label: '09-11 周五', value: 213, weekend: false, today: false },
  { date: '2026-09-12', label: '09-12 周六', value: 52, weekend: true, today: true },
]

/** 部门维度（活跃 73 / 人数 128 / 提问 1284 自洽） */
export const mockDeptUsage: DeptUsage[] = [
  { dept: '研发中心', activeUsers: 34, totalUsers: 52, questions: 586, perUser: 17.2, percent: 45.6 },
  { dept: '销售中心', activeUsers: 12, totalUsers: 34, questions: 248, perUser: 20.7, percent: 19.3 },
  { dept: '人力资源部', activeUsers: 8, totalUsers: 12, questions: 164, perUser: 20.5, percent: 12.8 },
  { dept: '运维部', activeUsers: 9, totalUsers: 16, questions: 142, perUser: 15.8, percent: 11.1 },
  { dept: '培训中心', activeUsers: 4, totalUsers: 8, questions: 86, perUser: 21.5, percent: 6.7 },
  { dept: '其他 / 未分配', activeUsers: 6, totalUsers: 6, questions: 58, perUser: 9.7, percent: 4.5 },
]

/** 知识库热度（合计 1284，与 20 页一致） */
export const mockKbHeat: KbHeat[] = [
  { kbName: '产品技术文档库', questions: 486, percent: 37.9 },
  { kbName: '客户合同库', questions: 238, percent: 18.5 },
  { kbName: '人力资源制度库', questions: 212, percent: 16.5 },
  { kbName: '运维故障知识库', questions: 164, percent: 12.8 },
  { kbName: '外部培训资料库', questions: 84, percent: 6.5 },
  { kbName: '其他', questions: 100, percent: 7.8 },
]

/** 活跃用户 TOP5（提问多 ≠ 用得好：提问多 + 分低说明总在问答不上来的） */
export const mockTopUsers: ActiveUserStat[] = [
  { name: '张伟', dept: '研发中心', questions: 86, avgTopScore: 0.79 },
  { name: '李娜', dept: '销售中心', questions: 72, avgTopScore: 0.74 },
  { name: '林工', dept: '研发中心', questions: 64, avgTopScore: 0.82 },
  { name: '王芳', dept: '人力资源部', questions: 51, avgTopScore: 0.71 },
  { name: '刘强', dept: '运维部', questions: 43, avgTopScore: 0.68 },
]

// ==================== 通用（批次 5：通知 / 回收站 / 错误页） ====================
/** 通知 28 条 = 上传与解析 9 + 系统告警 4 + 权限变更 3 + 系统消息 12；未读 5 */
export const mockNotices: NoticeItem[] = [
  // ---------- 上传与解析（9） ----------
  { id: 1, type: 'UPLOAD', title: '批量上传完成', content: '4 个文件已处理：3 成功 / 1 失败。「旧版部署脚本.txt」因编码为 GBK 无法按 UTF-8 解码。', time: '2026-09-12 17:36', read: false, group: '今天', needAction: true, muteable: true, actions: [{ label: '查看解析质量', to: '/kb/1/upload', primary: true }, { label: '重试解析', to: '/kb/1/upload' }] },
  { id: 2, type: 'UPLOAD', title: '解析失败', content: '「历史故障复盘汇编.docx」解析失败：文件编码为 GBK。建议转码后重新上传，失败不会产生脏向量数据。', time: '2026-09-12 15:02', read: false, group: '今天', needAction: true, muteable: true, actions: [{ label: '重试解析', to: '/kb/1/upload', primary: true }, { label: '去文档详情', to: '/doc/10' }] },
  { id: 3, type: 'UPLOAD', title: '表格未识别', content: '「AI 知识库平台白皮书 V3」第 12 页有 1 处表格未识别，该表格内容无法被检索命中。', time: '2026-09-12 14:34', read: false, group: '今天', needAction: true, muteable: true, actions: [{ label: '查看解析质量', to: '/doc/1/preview', primary: true }, { label: '重建索引', to: '/doc/1' }] },
  { id: 4, type: 'UPLOAD', title: '批量上传完成', content: '8 个文件已全部处理成功，共生成 642 个分片。', time: '2026-09-12 11:20', read: false, group: '今天', actions: [{ label: '查看文档列表', to: '/kb/1/upload', primary: true }] },
  { id: 5, type: 'UPLOAD', title: '解析完成', content: '「服务接口清单.xlsx」解析完成，生成 37 个分片，表格 6/6 全部识别。', time: '2026-09-12 09:12', read: true, group: '今天', actions: [{ label: '查看文档', to: '/doc/4' }] },
  { id: 6, type: 'UPLOAD', title: '索引完成', content: '「部署运维手册.pdf」索引完成，耗时 74 秒，生成 158 个分片。', time: '2026-09-11 16:45', read: true, group: '昨天', actions: [{ label: '在线预览', to: '/doc/6/preview' }] },
  { id: 7, type: 'UPLOAD', title: '表格未识别', content: '「性能压测报告.pdf」第 7 页跨页表格列数不一致，未能合并。', time: '2026-09-11 15:20', read: false, group: '昨天', needAction: true, muteable: true, actions: [{ label: '查看解析质量', to: '/doc/8/preview', primary: true }, { label: '重建索引', to: '/doc/8' }] },
  { id: 8, type: 'UPLOAD', title: '批量上传完成', content: '12 个文件已处理：12 成功 / 0 失败，共生成 1,284 个分片。', time: '2026-09-11 10:08', read: true, group: '昨天', actions: [{ label: '查看文档列表', to: '/kb/7/upload' }] },
  { id: 9, type: 'UPLOAD', title: '解析完成', content: '「数据库设计规范.docx」解析完成，生成 71 个分片，未发现解析问题。', time: '2026-09-09 16:30', read: true, group: '更早', actions: [{ label: '查看文档', to: '/doc/5' }] },

  // ---------- 系统告警（4，强制开启） ----------
  { id: 10, type: 'ALERT', title: '索引失败', content: '「客户合同库」文档「2026 年度合同模板.pdf」索引失败：向量化服务超时（重试 3 次均失败）。', time: '2026-09-12 13:05', read: true, group: '今天', needAction: true, actions: [{ label: '重试索引', to: '/kb/3/upload', primary: true }, { label: '去处理', to: '/ops/qa' }] },
  { id: 11, type: 'ALERT', title: '存储用量接近上限', content: '当前已用 2.4 GB，占配额 80%。建议清理回收站超期项或归档历史文档。', time: '2026-09-11 18:40', read: true, group: '昨天', actions: [{ label: '查看用量', to: '/doc/download' }, { label: '去回收站', to: '/recycle', primary: true }] },
  { id: 12, type: 'ALERT', title: '定时清理任务失败', content: '回收站超期项清理任务第 2 次执行失败，可能导致存储泄漏，请尽快排查。', time: '2026-09-11 03:10', read: true, group: '昨天', needAction: true, actions: [{ label: '去回收站', to: '/recycle', primary: true }] },
  { id: 13, type: 'ALERT', title: '索引任务中断', content: '「研发知识沉淀库」全库重建任务被中断（服务重启），已重建 182 / 305 篇。', time: '2026-09-09 02:15', read: true, group: '更早', needAction: true, actions: [{ label: '重新发起重建', to: '/kb/7', primary: true }] },

  // ---------- 权限变更（3） ----------
  { id: 14, type: 'PERMISSION', title: '你的角色已调整', content: '管理员为你新增了「知识库管理员」角色，数据范围为「本部门及以下」。', time: '2026-09-12 10:22', read: true, group: '今天', actions: [{ label: '查看我的权限', to: '/profile', primary: true }] },
  { id: 15, type: 'PERMISSION', title: '知识库授权变更', content: '「产品技术文档库」已授予你「可上传」权限（此前仅可查看）。', time: '2026-09-11 14:50', read: true, group: '昨天', actions: [{ label: '去知识库', to: '/kb/1', primary: true }] },
  { id: 16, type: 'PERMISSION', title: '角色成员变动', content: '「知识库管理员」角色新增 1 名成员（张伟 · zhang.wei）。', time: '2026-09-08 09:30', read: true, group: '更早', actions: [{ label: '查看角色', to: '/system/role' }] },
]

/** 系统消息（12 条，用模板生成避免重复） */
const SYS_NOTICE_TPL: [string, string, string][] = [
  ['全库重建完成', '「产品技术文档库」全库重建完成：128 篇文档、8,240 个分片，耗时 18 分钟。', '/ops/retrieval'],
  ['模型配置变更', '对话模型已切换为 deepseek-ai/DeepSeek-V3（协议 OpenAI 兼容），首字延迟 420ms。', '/system/model'],
  ['索引增强启用', '「标题路径注入」「HyQ 假设性问题」「文档级摘要」三项索引增强已对全库开启。', '/kb/1'],
  ['召回阈值调整', '「产品技术文档库」召回阈值由 0.15 调整为 0.18，未命中率下降 1.2pp。', '/kb/1'],
  ['系统版本更新', 'v1.4.0 已发布：新增轻量实体图谱旁路（默认关），优化 Rerank 候选数配置。', '/system/model'],
  ['嵌入模型确认', '嵌入模型确认为 BAAI/bge-m3（1024 维 · L2 归一化），本周期内无变更。', '/system/model'],
  ['评测集更新', '表格问答评测集扩充至 28 条，Recall@5 由 0.81 提升至 0.87。', '/ops/retrieval'],
  ['权限矩阵发布', '角色权限矩阵更新已生效：本次变更 3 项，涉及 2 个角色。', '/system/grant'],
  ['审计日志归档', '2026-08 月审计日志已归档至冷存储（共 12,847 条）。', '/system/audit'],
  ['配额策略调整', '单次批量下载上限调整为 50 个文件 / 2 GB，超出请分批。', '/doc/download'],
  ['通知设置变更', '「权限变更」通知已重新开启（此前被关闭，强烈建议保持开启）。', '/notice'],
  ['知识库下线提醒', '「外部培训资料库」检索质量持续下降（未命中率 19.0%），建议检查 OCR 质量。', '/ops/retrieval'],
]

mockNotices.push(
  ...SYS_NOTICE_TPL.map(([title, content, to], i) => ({
    id: 17 + i,
    type: 'SYSTEM' as const,
    title,
    content,
    time: `2026-09-${String(7 - (i % 5)).padStart(2, '0')} ${String(9 + (i % 9)).padStart(2, '0')}:${String((i * 13) % 60).padStart(2, '0')}`,
    read: true,
    group: (i < 2 ? '今天' : i < 5 ? '昨天' : '更早') as '今天' | '昨天' | '更早',
    actions: [{ label: '查看详情', to, primary: i === 0 }],
  })),
)

/** 分类计数（合计 28 = 全部数） */
export const mockNoticeCounts: NoticeCounts = {
  all: 28,
  unread: 5,
  UPLOAD: 9,
  ALERT: 4,
  PERMISSION: 3,
  SYSTEM: 12,
}

/** 通知设置（系统告警强制开启不可关） */
export const mockNoticeSettings: NoticeSetting[] = [
  { type: 'UPLOAD', label: '上传与解析', enabled: true, hint: '批量上传完成、解析失败、表格未识别' },
  { type: 'ALERT', label: '系统告警', enabled: true, forced: true, hint: '索引失败、存储超限、任务中断（强制开启，不可关闭）' },
  { type: 'PERMISSION', label: '权限变更', enabled: true, hint: '角色调整、知识库授权变化（强烈建议保留）' },
  { type: 'SYSTEM', label: '系统消息', enabled: true, hint: '模型配置变更、重建完成、版本更新' },
]

/** 「待我处理」聚合（未命中 23 与 11 页一致；解析问题 3 与文档域一致） */
export const mockPendingItems: PendingItem[] = [
  { key: 'miss', label: '未命中问题待处理', count: 23, desc: '检索分数低于 0.35 的提问，已沉淀为清单，需补文档后重放验证', to: '/ops/qa', btnText: '去处理' },
  { key: 'parse', label: '文档解析问题待处理', count: 3, desc: '3 篇文档存在未识别表格或解析失败，内容无法被检索命中', to: '/kb/1/upload', btnText: '查看解析质量' },
]

/** 回收站 · 文档（剩余天数三档：≥20 绿 / 16~19 橙 / <16 红） */
export const mockRecycleDocs: RecycleItem[] = [
  { id: 101, type: 'DOC', name: '旧版部署脚本.txt', origin: '产品技术文档库', size: 184_320, deletedBy: '林工', deletedAt: '2026-09-12 16:40', remainDays: 30 },
  { id: 102, type: 'DOC', name: '2025 年度合同模板（已废止）.docx', origin: '客户合同库', size: 2_621_440, deletedBy: '赵磊', deletedAt: '2026-09-10 11:05', remainDays: 28, hasNewer: true },
  { id: 103, type: 'DOC', name: '临时压测数据.xlsx', origin: '研发知识沉淀库', size: 5_242_880, deletedBy: '张伟', deletedAt: '2026-09-05 09:30', remainDays: 23 },
  { id: 104, type: 'DOC', name: '旧版接口文档 v1.2.pdf', origin: '（原知识库已删除）', size: 3_145_728, deletedBy: '王芳', deletedAt: '2026-08-30 15:20', remainDays: 17, originDeleted: true },
  { id: 105, type: 'DOC', name: '废弃的培训材料.pptx', origin: '培训中心资料库', size: 8_388_608, deletedBy: '高飞', deletedAt: '2026-08-20 10:15', remainDays: 8, hasNewer: true },
]

/** 回收站 · 知识库（恢复连带其下全部文档） */
export const mockRecycleKbs: RecycleItem[] = [
  { id: 201, type: 'KB', name: '临时测试库', origin: '研发中心', docCount: 14, size: 41_943_040, deletedBy: '张伟', deletedAt: '2026-09-11 14:20', remainDays: 29 },
  { id: 202, type: 'KB', name: '旧版制度库（2024）', origin: '人力资源部', docCount: 46, size: 128_974_848, deletedBy: '周敏', deletedAt: '2026-08-28 09:40', remainDays: 15 },
]

/** 回收站保留期（天） */
export const RECYCLE_KEEP_DAYS = 30

// ==================== 模型配置（V2：四类 · 多配置） ====================
/**
 * 四类模型清单 —— 与《01-示例数据字典》§5.3 的 10 条示例 1:1 对齐。
 * 除嵌入外均可配多条；同类型同时只有一条 isActive。
 * ⚠️ 计费三态示例（刻意保留）：
 *   · id=4 本地 vLLM → billingUnit='NONE'（确定不计费）
 *   · id=5 内部测试模型 → 单价全为 null（费用未知）
 * 这两条不是一回事，UI 必须能区分。
 */
export const mockChatModels: ModelConfigItem[] = [
  {
    id: 1,
    modelType: 'CHAT',
    displayName: 'DeepSeek-V3（硅基流动）',
    protocol: 'OpenAI 兼容',
    baseUrl: 'https://api.siliconflow.cn/v1',
    apiKeyMasked: 'sk-••••••••••••••••••••3f9a',
    modelName: 'deepseek-ai/DeepSeek-V3',
    params: { temperature: 0.3, maxTokens: 2048, timeoutSec: 60, retries: 2 },
    capabilities: { stream: true, vision: false, contextWindow: 128000, supportSystemRole: true },
    billingUnit: 'PER_TOKEN',
    inputPrice: 1.0,
    outputPrice: 4.0,
    perCallPrice: null,
    currency: 'CNY',
    isActive: true,
    sortOrder: 1,
    lastTestAt: '2026-09-12 15:20',
    lastTestOk: true,
    lastLatencyMs: 420,
    note: '主力模型，128K 窗口，支持流式',
  },
  {
    id: 2,
    modelType: 'CHAT',
    displayName: 'GPT-4o（第三方中转）',
    protocol: 'OpenAI 兼容',
    baseUrl: 'https://api.openai-proxy.example.com/v1',
    apiKeyMasked: 'sk-••••••••••••••••••••7c21',
    modelName: 'gpt-4o',
    params: { temperature: 0.3, maxTokens: 2048, timeoutSec: 60, retries: 2 },
    capabilities: { stream: true, vision: true, contextWindow: 128000, supportSystemRole: true },
    billingUnit: 'PER_TOKEN',
    inputPrice: 18.0,
    outputPrice: 72.0,
    perCallPrice: null,
    currency: 'CNY',
    isActive: false,
    sortOrder: 2,
    lastTestAt: '2026-09-11 10:05',
    lastTestOk: true,
    lastLatencyMs: 1180,
    note: '支持 vision，单价高，按需切换',
  },
  {
    id: 3,
    modelType: 'CHAT',
    displayName: 'Qwen2.5-72B（阿里云）',
    protocol: 'OpenAI 兼容',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    apiKeyMasked: 'sk-••••••••••••••••••••a4e8',
    modelName: 'qwen2.5-72b-instruct',
    params: { temperature: 0.3, maxTokens: 2048, timeoutSec: 60, retries: 2 },
    capabilities: { stream: true, vision: false, contextWindow: 32000, supportSystemRole: true },
    billingUnit: 'PER_TOKEN',
    inputPrice: 4.0,
    outputPrice: 12.0,
    perCallPrice: null,
    currency: 'CNY',
    isActive: false,
    sortOrder: 3,
    lastTestAt: '2026-09-10 16:42',
    lastTestOk: true,
    lastLatencyMs: 560,
    note: '上下文 32K，切换时注意窗口',
  },
  {
    id: 4,
    modelType: 'CHAT',
    displayName: '本地 vLLM · Llama-3.1-70B',
    protocol: 'OpenAI 兼容',
    baseUrl: 'http://10.8.0.21:8000/v1',
    apiKeyMasked: '（内网免鉴权）',
    modelName: 'llama-3.1-70b-instruct',
    params: { temperature: 0.3, maxTokens: 2048, timeoutSec: 120, retries: 1 },
    capabilities: { stream: true, vision: false, contextWindow: 128000, supportSystemRole: true },
    billingUnit: 'NONE',
    inputPrice: null,
    outputPrice: null,
    perCallPrice: null,
    currency: 'CNY',
    isActive: false,
    sortOrder: 4,
    lastTestAt: '2026-09-12 14:30',
    lastTestOk: true,
    lastLatencyMs: 980,
    note: '本地部署，不计 API 费用；tokens 计入用量但不计价',
  },
  {
    id: 5,
    modelType: 'CHAT',
    displayName: '内部测试模型',
    protocol: 'OpenAI 兼容',
    baseUrl: 'http://10.8.0.33:9000/v1',
    apiKeyMasked: 'sk-••••••••••••••••••••0001',
    modelName: 'test-model',
    params: { temperature: 0.5, maxTokens: 1024, timeoutSec: 60, retries: 2 },
    capabilities: { stream: true, vision: false, contextWindow: 8000, supportSystemRole: false },
    billingUnit: 'PER_TOKEN',
    inputPrice: null,
    outputPrice: null,
    perCallPrice: null,
    currency: 'CNY',
    isActive: false,
    sortOrder: 5,
    lastTestAt: '2026-09-09 11:18',
    lastTestOk: true,
    lastLatencyMs: 2400,
    note: '演示「费用未知」第三种状态：单价留空、不支持 system role',
  },
]

/** 嵌入模型：全局单例（前端只读） */
export const mockEmbeddingModel: ModelConfigItem = {
  id: 6,
  modelType: 'EMBEDDING',
  displayName: 'BAAI/bge-m3',
  protocol: 'OpenAI 兼容',
  baseUrl: 'https://api.siliconflow.cn/v1',
  apiKeyMasked: 'sk-••••••••••••••••••••3f9a',
  modelName: 'BAAI/bge-m3',
  dimension: 1024,
  norm: 'L2',
  batchSize: 32,
  metric: 'COSINE',
  params: { temperature: 0, maxTokens: 0, timeoutSec: 60, retries: 2 },
  capabilities: { stream: false, vision: false, contextWindow: 8192, supportSystemRole: false },
  billingUnit: 'PER_TOKEN',
  inputPrice: 0.5,
  outputPrice: null,
  perCallPrice: null,
  currency: 'CNY',
  isActive: true,
  sortOrder: 1,
  lastTestAt: '2026-09-12 15:20',
  lastTestOk: true,
  lastLatencyMs: 86,
  note: '1024 维 · L2 归一化；全局单例，不可新增',
}

/** 重排模型（多条，全局唯一启用） */
export const mockRerankModels: ModelConfigItem[] = [
  {
    id: 7,
    modelType: 'RERANK',
    displayName: 'bge-reranker-v2-m3',
    protocol: 'OpenAI 兼容',
    baseUrl: 'https://api.siliconflow.cn/v1',
    apiKeyMasked: 'sk-••••••••••••••••••••3f9a',
    modelName: 'BAAI/bge-reranker-v2-m3',
    params: { temperature: 0, maxTokens: 0, timeoutSec: 30, retries: 2 },
    capabilities: { stream: false, vision: false, contextWindow: 8192, supportSystemRole: false },
    billingUnit: 'PER_TOKEN',
    inputPrice: 0.8,
    outputPrice: null,
    perCallPrice: null,
    currency: 'CNY',
    isActive: true,
    sortOrder: 1,
    lastTestAt: '2026-09-12 15:20',
    lastTestOk: true,
    lastLatencyMs: 288,
    note: '交叉编码器精排',
  },
  {
    id: 8,
    modelType: 'RERANK',
    displayName: 'gte-rerank（备选）',
    protocol: 'OpenAI 兼容',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    apiKeyMasked: 'sk-••••••••••••••••••••a4e8',
    modelName: 'gte-rerank',
    params: { temperature: 0, maxTokens: 0, timeoutSec: 30, retries: 2 },
    capabilities: { stream: false, vision: false, contextWindow: 8192, supportSystemRole: false },
    billingUnit: 'PER_TOKEN',
    inputPrice: 0.7,
    outputPrice: null,
    perCallPrice: null,
    currency: 'CNY',
    isActive: false,
    sortOrder: 2,
    lastTestAt: '2026-09-08 09:50',
    lastTestOk: true,
    lastLatencyMs: 312,
    note: '待评测集对比后再切换',
  },
]

/** VL 视觉模型（多条，全局唯一启用；承担解析期 + 问答期图片理解） */
export const mockVlModels: ModelConfigItem[] = [
  {
    id: 9,
    modelType: 'VL',
    displayName: 'Qwen2.5-VL-72B',
    protocol: 'OpenAI 兼容',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    apiKeyMasked: 'sk-••••••••••••••••••••a4e8',
    modelName: 'Qwen/Qwen2.5-VL-72B-Instruct',
    params: { temperature: 0.1, maxTokens: 1500, timeoutSec: 120, retries: 2 },
    capabilities: { stream: false, vision: true, contextWindow: 32000, supportSystemRole: true },
    billingUnit: 'PER_TOKEN',
    inputPrice: 8.0,
    outputPrice: 24.0,
    perCallPrice: null,
    currency: 'CNY',
    isActive: true,
    sortOrder: 1,
    lastTestAt: '2026-09-12 15:20',
    lastTestOk: true,
    lastLatencyMs: 1760,
    note: '扫描件解析 + 提问传图描述',
  },
  {
    id: 10,
    modelType: 'VL',
    displayName: 'GPT-4o（备用）',
    protocol: 'OpenAI 兼容',
    baseUrl: 'https://api.openai-proxy.example.com/v1',
    apiKeyMasked: 'sk-••••••••••••••••••••7c21',
    modelName: 'gpt-4o',
    params: { temperature: 0.1, maxTokens: 1500, timeoutSec: 120, retries: 2 },
    capabilities: { stream: false, vision: true, contextWindow: 128000, supportSystemRole: true },
    billingUnit: 'PER_TOKEN',
    inputPrice: 18.0,
    outputPrice: 72.0,
    perCallPrice: null,
    currency: 'CNY',
    isActive: false,
    sortOrder: 2,
    lastTestAt: '2026-09-11 10:06',
    lastTestOk: true,
    lastLatencyMs: 1420,
    note: 'VL 主模型不可用时降级备用',
  },
]

/**
 * 用量 · 按模型拆分
 * 口径取《01-示例数据字典》§5.3「存在未计价模型时的展示口径」示例表（覆盖全部三态）：
 * 合计 13,207 次 / 405.2 万 tokens / ¥128.4，其中 1 个模型未配置单价（不进合计）。
 * ⚠️ 本地 vLLM 的 cost=0 与 test-model 的 cost=null 语义不同，UI 必须区分。
 */
export const mockUsageByModel: ModelUsage['byModel'] = [
  { modelName: 'deepseek-ai/DeepSeek-V3', modelType: 'CHAT', calls: 10240, tokens: 3100000, cost: 96.2, billingUnit: 'PER_TOKEN' },
  { modelName: 'Qwen/Qwen2.5-VL-72B-Instruct', modelType: 'VL', calls: 1180, tokens: 420000, cost: 21.8, billingUnit: 'PER_TOKEN' },
  { modelName: 'BAAI/bge-m3', modelType: 'EMBEDDING', calls: 1412, tokens: 210000, cost: 6.4, billingUnit: 'PER_TOKEN' },
  { modelName: 'BAAI/bge-reranker-v2-m3', modelType: 'RERANK', calls: 15, tokens: 130000, cost: 4.0, billingUnit: 'PER_TOKEN' },
  { modelName: 'llama-3.1-70b-instruct（本地 vLLM）', modelType: 'CHAT', calls: 320, tokens: 180000, cost: 0, billingUnit: 'NONE' },
  { modelName: 'test-model（内部测试）', modelType: 'CHAT', calls: 40, tokens: 12000, cost: null, billingUnit: 'PER_TOKEN' },
]

/** 更换 VL 模型影响量（仅含图文档；与嵌入模型的「全库重建」刻意区分） */
export const mockVlImpact: VlImpact = {
  kbCount: 5,
  imageDocCount: 92,
  imageCount: 286,
  estimateMinutes: 6,
  extraTokens: 1480000,
}

/** 问答会话列表（每条会话绑定模型，用于归因） */
export const mockChatSessions = [
  { id: 's1', title: 'RAG 检索链路设计', time: '10:24', count: 8, modelId: 1, modelName: 'DeepSeek-V3（硅基流动）' },
  { id: 's2', title: '多跳推理与知识图谱', time: '昨天', count: 5, modelId: 1, modelName: 'DeepSeek-V3（硅基流动）' },
  { id: 's3', title: '用户权限模型', time: '昨天', count: 3, modelId: 3, modelName: 'Qwen2.5-72B（阿里云）' },
  { id: 's4', title: '表格双表示与报价单', time: '周二', count: 6, modelId: 1, modelName: 'DeepSeek-V3（硅基流动）' },
  { id: 's5', title: '系统性能与延迟优化', time: '周一', count: 4, modelId: 2, modelName: 'GPT-4o（第三方中转）' },
]