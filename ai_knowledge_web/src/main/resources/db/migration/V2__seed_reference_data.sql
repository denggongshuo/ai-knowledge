-- ============================================================================
-- V2 · S0 骨架域种子数据（T-011）
--
-- 验收要点原话：「38 权限点 / 内置角色 / 部门树 / 阈值默认值；数值与
-- 《01-示例数据字典》一致」。本脚本就是这四样的落库版本。
--
-- 数值来源（改任何数字前先改这三处，再回来同步本脚本）：
--   · 部门树与用户数   → ai_knowledge_app/src/mock/data.ts 的 DEPTS
--   · 5 个角色及权限点 → 同文件 mockRoles（38 / 21 / 9 / 11 / 3）
--   · 阈值             → docs/需求文档/01-示例数据字典.md §8 与 03-术语与领域模型.md §3
--
-- 本脚本**幂等性说明**：Flyway 保证只执行一次，故直接 INSERT，不做 upsert。
-- 想改种子值请新增 V3，不要回头改这个文件（validate-on-migrate 会报校验失败，
-- 这正是防止"本地能跑、别人炸"的机制）。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 「未分配」部门必须是 id = 0
--
-- MySQL 默认把 INSERT 里的 0 当成"请用自增值"，所以不带这个 sql_mode 的话，
-- 下面那条 id=0 的插入会变成自增出一个新 id，「未分配」就丢了 —— 而前端
-- DeptNode.deptId = 0 是真实取值（见 types/index.ts），不能改成别的数字。
-- ---------------------------------------------------------------------------
SET SESSION sql_mode = CONCAT(@@sql_mode, ',NO_AUTO_VALUE_ON_ZERO');

-- ---------------------------------------------------------------------------
-- 部门树（两层）：4 个顶级中心 + 研发中心下 3 个组 + 虚拟节点「未分配」
-- ---------------------------------------------------------------------------
INSERT INTO sys_department (id, name, parent_id, sort, created_at, updated_at)
VALUES (0, '未分配', NULL, 0, NOW(), NOW()),
       (1, '研发中心', NULL, 1, NOW(), NOW()),
       (2, '销售中心', NULL, 2, NOW(), NOW()),
       (3, '人力资源部', NULL, 3, NOW(), NOW()),
       (4, '运维部', NULL, 4, NOW(), NOW()),
       (5, '培训中心', NULL, 5, NOW(), NOW()),
       (11, '平台架构组', 1, 11, NOW(), NOW()),
       (12, '应用开发组', 1, 12, NOW(), NOW()),
       (13, '测试组', 1, 13, NOW(), NOW());

-- ---------------------------------------------------------------------------
-- 权限点：38 个原子码
--
-- 顺序与后端 core/enums/PermissionCode.java 的声明顺序**逐条一致**，
-- 因为 sort 决定权限树与授权矩阵的展示顺序；两边顺序不同会让页面上
-- 勾选项的排列跳来跳去。
--
-- code 的命名口径是「域:动作[:变体]」—— 按**动作的归属**划分域。
-- （早期文档里的三段式 `kb:doc:download` 已废弃：下载文档属于文档域，故为 doc:download。）
-- ---------------------------------------------------------------------------
INSERT INTO sys_permission (code, label, group_name, sort)
VALUES
    -- 知识问答（7）
    ('qa:ask', '发起问答', '知识问答', 1),
    ('qa:ask:stream', '流式问答', '知识问答', 2),
    ('qa:history:view', '查看历史会话', '知识问答', 3),
    ('qa:history:search', '搜索历史会话', '知识问答', 4),
    ('qa:history:delete', '删除会话记录', '知识问答', 5),
    ('qa:session:export', '导出会话记录', '知识问答', 6),
    ('qa:feedback', '处理问答反馈', '知识问答', 7),
    -- 知识库管理（7）
    ('kb:view', '查看知识库列表', '知识库管理', 11),
    ('kb:list', '查看知识库详情', '知识库管理', 12),
    ('kb:create', '创建知识库', '知识库管理', 13),
    ('kb:update', '编辑知识库', '知识库管理', 14),
    ('kb:config', '配置检索参数', '知识库管理', 15),
    ('kb:delete', '删除知识库', '知识库管理', 16),
    ('kb:grant', '调整知识库授权', '知识库管理', 17),
    -- 文档（8）
    ('doc:upload', '上传文档', '文档', 21),
    ('doc:preview', '预览文档', '文档', 22),
    ('doc:download', '下载文档', '文档', 23),
    ('doc:detail', '查看文档详情', '文档', 24),
    ('doc:delete', '删除文档', '文档', 25),
    ('doc:reindex', '重建索引', '文档', 26),
    ('doc:version', '版本管理', '文档', 27),
    -- 回收站的"还原 / 彻底删除"是不可逆动作，必须独立授权，默认只授予超管
    ('doc:recycle', '回收站管理', '文档', 28),
    -- 系统管理（16）
    ('user:list', '查看用户', '系统管理', 31),
    ('user:create', '新建用户', '系统管理', 32),
    ('user:update', '编辑用户', '系统管理', 33),
    ('user:delete', '删除用户', '系统管理', 34),
    ('user:reset-pwd', '重置密码', '系统管理', 35),
    ('user:import', '批量导入', '系统管理', 36),
    ('role:list', '查看角色', '系统管理', 41),
    ('role:create', '新建角色', '系统管理', 42),
    ('role:update', '编辑角色', '系统管理', 43),
    ('role:delete', '删除角色', '系统管理', 44),
    ('grant:matrix', '角色权限矩阵', '系统管理', 45),
    ('grant:user-role', '用户角色分配', '系统管理', 46),
    ('audit:view', '查看审计日志', '系统管理', 51),
    ('audit:export', '导出审计日志', '系统管理', 52),
    ('system:model', '模型配置', '系统管理', 61),
    -- 对单个用户做逐库授权，凌驾于角色授权之上
    ('kb:grant-extra', '知识库额外授权用户', '系统管理', 62);

-- ---------------------------------------------------------------------------
-- 角色：5 个（成员数 3 + 12 + 109 + 4 + 0 = 128）
--
-- data_scope 取值口径见 core/enums/DataScope.java：
--   ALL(最宽) > DEPT_AND_CHILD > DEPT > SELF，多角色叠加取最宽。
-- builtin = 1 的角色不可删除（超管 / 知识库管理员 / 普通用户）。
-- ---------------------------------------------------------------------------
INSERT INTO sys_role (code, name, description, data_scope, enabled, builtin, sort, created_at, updated_at)
VALUES ('SUPER_ADMIN', '超级管理员', '全功能 + 全数据 + 系统配置，内置角色不可删除', 'ALL', 1, 1, 1, NOW(), NOW()),
       ('KB_ADMIN', '知识库管理员', '知识库管理 + 文档维护 + 用户只读查看', 'DEPT_AND_CHILD', 1, 1, 2, NOW(), NOW()),
       ('USER', '普通用户', '检索问答 + 个人会话，仅访问本单位范围', 'SELF', 1, 1, 3, NOW(), NOW()),
       ('AUDITOR', '审计员', '审计日志只读 + 问答只读，用于合规核查', 'ALL', 1, 0, 4, NOW(), NOW()),
       ('GUEST', '外部访客', '受限只读（外部人员），当前未分配成员', 'SELF', 0, 0, 5, NOW(), NOW());

-- ---------------------------------------------------------------------------
-- 角色 × 权限
--
-- 超级管理员用「全选」而不是逐个列举：它的语义就是"拥有全部权限点"
-- （PermissionCode.WILDCARD = "*"），以后新增权限点时它自动覆盖，
-- 不会出现"加了新权限但超管没有"的诡异状态。
--
-- 其余 4 个角色的权限码**逐条来自 mockRoles**，数量必须精确为
-- KB_ADMIN 21 / USER 9 / AUDITOR 11 / GUEST 3 —— 页面上会显示这些计数，
-- 多一个少一个都对不上。
-- ---------------------------------------------------------------------------
INSERT INTO sys_role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW()
FROM sys_role r
         CROSS JOIN sys_permission p
WHERE r.code = 'SUPER_ADMIN';

-- 知识库管理员：21 个
INSERT INTO sys_role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW()
FROM sys_role r
         JOIN sys_permission p ON p.code IN ('qa:ask', 'qa:ask:stream', 'qa:history:view',
                                             'qa:history:search', 'qa:history:delete', 'qa:session:export',
                                             'qa:feedback', 'kb:view', 'kb:list', 'kb:create',
                                             'kb:update', 'kb:config', 'kb:grant', 'doc:upload',
                                             'doc:preview', 'doc:download', 'doc:detail', 'doc:delete',
                                             'doc:reindex', 'doc:version', 'user:list')
WHERE r.code = 'KB_ADMIN';

-- 普通用户：9 个
INSERT INTO sys_role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW()
FROM sys_role r
         JOIN sys_permission p ON p.code IN ('qa:ask', 'qa:ask:stream', 'qa:history:view',
                                             'qa:history:search', 'kb:view', 'kb:list',
                                             'doc:upload', 'doc:preview', 'doc:download')
WHERE r.code = 'USER';

-- 审计员：11 个（注意只有 audit:view / audit:export 是它独有的，其余是只读项）
INSERT INTO sys_role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW()
FROM sys_role r
         JOIN sys_permission p ON p.code IN ('qa:ask', 'qa:ask:stream', 'qa:history:view',
                                             'qa:history:search', 'kb:view', 'kb:list',
                                             'doc:preview', 'doc:download', 'doc:detail',
                                             'audit:view', 'audit:export')
WHERE r.code = 'AUDITOR';

-- 外部访客：3 个
INSERT INTO sys_role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW()
FROM sys_role r
         JOIN sys_permission p ON p.code IN ('kb:view', 'doc:preview', 'doc:download')
WHERE r.code = 'GUEST';

-- ---------------------------------------------------------------------------
-- 系统参数（阈值）
--
-- 三条检索阈值是一组**互相约束**的参数，不是三个孤立的数：
--   recallThreshold 0.15  ≤  missThreshold 0.35  ≤  lowScoreWarn 0.65
-- 「宽进严出」：召回宁宽勿严（0.15 才会有分数），判定未命中要严（0.35），
-- 降权提示只影响提示、不过滤（0.65）。
--
-- 不变量 I-11：missThreshold > recallThreshold 必须成立。
-- 若召回阈值 ≥ 判定线，低于召回阈值的候选"根本没有分数"，未命中就无从判定。
-- 这条跨行约束 KV 表表达不了，由写入服务校验（T-014）。
--
-- embedding.dimension 设 editable = 0：它是 D-4 的不可逆决策，
-- bge-m3 固定输出 1024，改它等于重建全库 38,502 条向量，只允许只读展示。
-- ---------------------------------------------------------------------------
INSERT INTO sys_setting (setting_key, setting_value, value_type, group_name, label, description, editable, updated_at)
VALUES
    -- 检索
    ('retrieval.recall-threshold', '0.15', 'DECIMAL', '检索', '召回阈值',
     '低于此分的候选不进融合池。宽进——召回宁宽勿严，精排才是判官。库级可调', 1, NOW()),
    ('retrieval.miss-threshold', '0.35', 'DECIMAL', '检索', '未命中判定线',
     'Rerank 后最高分低于此值判定未命中，走兜底话术并记入未命中清单。严出。必须大于召回阈值（I-11）', 1, NOW()),
    ('retrieval.low-score-warn', '0.65', 'DECIMAL', '检索', '降权提示线',
     '低于此分的分片在检索详情标黄提示「已降权，可能影响精度」。只影响提示，不过滤', 1, NOW()),
    ('retrieval.rerank-top-k', '5', 'INT', '检索', 'Rerank 保留数',
     '最终喂给大模型的分片数，可配 3~15。库级可调，此处为全局默认值', 1, NOW()),
    ('retrieval.graph-bypass-min-length', '6', 'INT', '检索', '图谱旁路触发长度',
     '提问长度 ≥ 此值且命中 ≥1 个实体才触发图谱旁路；否则静默跳过，图谱零开销', 1, NOW()),
    ('retrieval.embedding-dimension', '1024', 'INT', '检索', '嵌入维度',
     'bge-m3 输出维度。锁死不可改：改它等于全库重建 38,502 条向量（D-4 / R-8）', 0, NOW()),
    -- 运营
    ('ops.negative-feedback-threshold', '5', 'DECIMAL', '运营', '负反馈率告警阈值',
     '单位为百分比。超过则在运营分析页高亮提示', 1, NOW()),
    ('ops.retrieval-detail-retention-days', '30', 'INT', '运营', '检索明细保留天数',
     '按天分区，聚合指标长期保留', 1, NOW()),
    -- 文档
    ('doc.recycle-retention-days', '30', 'INT', '文档', '回收站保留天数',
     '到期由系统自动清理。不做超过 30 天的例外保留：需长期保留应走归档而非回收站', 1, NOW()),
    ('doc.table-return-rows', '30', 'INT', '文档', '表格回表行数上限',
     '超出时按命中行窗口返回，避免撑爆上下文', 1, NOW()),
    ('doc.table-return-rows-tolerance', '5', 'INT', '文档', '表格回表行数容差',
     '与上限配合使用（30 行 ±5）', 1, NOW());

-- ---------------------------------------------------------------------------
-- 开发期账号
--
-- 只种一个超管账号：本项目登录标识统一用**用户名**（不是邮箱），
-- 密码 Admin@123 的 BCrypt 摘要在下面。种子里没有第二个账号 ——
-- 完整的 128 人名单是前端示例数据，属于演示内容，等 M01 做到用户管理时
-- 由该模块的接口按需生成，不塞进迁移脚本。
--
-- 改这个账号的密码请用接口改，不要改这里的摘要字符串。
-- ---------------------------------------------------------------------------
INSERT INTO sys_user (username, password_hash, real_name, phone, email, dept_id, status,
                      created_at, updated_at)
VALUES ('admin', '$2a$10$85A09wKZNT7FLCDLqdU7LOuiNdSvP0r5gzxBe9quzOHpZlwY8Qk4m',
        '系统管理员', '13800000001', 'admin@corp.com', 0, 'ACTIVE', NOW(), NOW());

INSERT INTO sys_user_role (user_id, role_id, created_at)
SELECT u.id, r.id, NOW()
FROM sys_user u
         CROSS JOIN sys_role r
WHERE u.username = 'admin'
  AND r.code = 'SUPER_ADMIN';
