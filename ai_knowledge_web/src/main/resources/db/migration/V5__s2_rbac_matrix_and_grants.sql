-- ---------------------------------------------------------------------------
-- S2（权限与组织）：功能矩阵 + 知识库授权
--
-- 两套四档词汇必须分清（《05》§0.4 别名表）：
--   功能矩阵   GrantLevel    = MANAGE / WRITE / READ / NONE（管理 / 读写 / 只读 / 无）
--   知识库授权 KbGrantLevel  = NONE / READ / UPLOAD / MANAGE（不可见 / 只读 / 可上传 / 可管理）
-- 映射：管理↔MANAGE、读写↔UPLOAD、只读↔READ、无↔NONE。
-- 注意：MySQL 注释 `--` 后必须有空格，`--文字` 是语法错误（本次真实踩到）。
--
-- 「38 权限点 vs 12 矩阵项」是两个实体（《05》§0.3）：矩阵项是 38 个原子码的
-- 运营聚合视图，item_key **不是**权限码、后端不拿它鉴权。
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- 功能矩阵的 12 个功能项（固定清单，运营视图）
-- key 与前端 mock MATRIX_ITEMS 逐条一致；group用于行分组展示。
-- ---------------------------------------------------------------------------
CREATE TABLE sys_matrix_item
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    item_key   VARCHAR(64) NOT NULL COMMENT '矩阵项 key（非权限码），如 qa:ask',
    label      VARCHAR(64) NOT NULL,
    group_name VARCHAR(32) NOT NULL COMMENT '知识问答 / 知识库 / 文档 / 系统管理',
    sort       INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_matrix_key (item_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='功能矩阵项（12 个聚合视图）';

-- ---------------------------------------------------------------------------
-- 角色 × 矩阵项的档位
-- 用 item_key 而不是 item_id做外键：矩阵项是固定清单，key就是自然键，
-- 联表反而多一跳；且前端 PUT 提交的就是 key。
-- ---------------------------------------------------------------------------
CREATE TABLE sys_role_matrix
(
    role_id    BIGINT      NOT NULL,
    item_key   VARCHAR(64) NOT NULL,
    level      VARCHAR(16) NOT NULL COMMENT 'MANAGE / WRITE / READ / NONE',
    updated_at DATETIME    NOT NULL,
    PRIMARY KEY (role_id, item_key),
    CONSTRAINT fk_rm_role FOREIGN KEY (role_id) REFERENCES sys_role (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='角色 × 功能矩阵档位';

-- ---------------------------------------------------------------------------
-- 知识库授权：库 × 主体（角色或用户）
--
-- principal 二选一：ROLE = 按角色授权（常规），USER = 额外授权单个用户
-- （凌驾于角色授权之上，权限码 kb:grant-extra）。S3 建知识库后开始写入，
-- S2 只建表 —— 不 seed 任何行：kb 不存在，引用会失败。
-- ---------------------------------------------------------------------------
CREATE TABLE sys_kb_grant
(
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    kb_id          BIGINT      NOT NULL,
    principal_type VARCHAR(8)  NOT NULL COMMENT 'ROLE / USER',
    principal_id   BIGINT      NOT NULL COMMENT '角色 id 或用户 id',
    level          VARCHAR(16) NOT NULL COMMENT 'NONE / READ / UPLOAD / MANAGE',
    created_at     DATETIME    NOT NULL,
    updated_at     DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_grant (kb_id, principal_type, principal_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='知识库授权（库 × 角色/用户 → 级别）';

-- ---------------------------------------------------------------------------
-- seed：12 项功能定义
-- ---------------------------------------------------------------------------
INSERT INTO sys_matrix_item (item_key, label, group_name, sort)
VALUES ('qa:ask', '发起问答', '知识问答', 1),
       ('qa:history', '查看历史会话', '知识问答', 2),
       ('qa:delete', '删除会话记录', '知识问答', 3),
       ('kb:list', '查看知识库列表', '知识库', 4),
       ('kb:edit', '创建 / 编辑知识库', '知识库', 5),
       ('kb:delete', '删除知识库', '知识库', 6),
       ('doc:upload', '上传文档', '文档', 7),
       ('doc:view', '预览 / 下载文档', '文档', 8),
       ('doc:manage', '删除 / 重建索引', '文档', 9),
       ('sys:user', '用户管理', '系统管理', 10),
       ('sys:role', '角色 / 授权管理', '系统管理', 11),
       ('sys:audit', '审计日志', '系统管理', 12);

-- ---------------------------------------------------------------------------
-- seed：5 角色 × 12 项默认档位（与前端 mock MATRIX_DATA 逐格一致）
-- 角色顺序 = sys_role seed 的 id 顺序（1 SUPER_ADMIN / 2 KB_ADMIN / 3 USER / 4 AUDITOR / 5 GUEST）。
-- 前端按 levels[key][roleId-1] 取档位，所以**档位必须与角色 id 升序对齐** ——
-- 这里逐项写明 role_id，不靠隐式顺序。
-- ---------------------------------------------------------------------------
INSERT INTO sys_role_matrix (role_id, item_key, level, updated_at)
VALUES
    -- qa:ask: SUPER/KB_ADMIN=MANAGE, USER/AUDITOR=WRITE, GUEST=READ
    (1, 'qa:ask', 'MANAGE', NOW()), (2, 'qa:ask', 'MANAGE', NOW()), (3, 'qa:ask', 'WRITE', NOW()),
    (4, 'qa:ask', 'WRITE', NOW()), (5, 'qa:ask', 'READ', NOW()),
    -- qa:history
    (1, 'qa:history', 'MANAGE', NOW()), (2, 'qa:history', 'WRITE', NOW()), (3, 'qa:history', 'WRITE', NOW()),
    (4, 'qa:history', 'READ', NOW()), (5, 'qa:history', 'NONE', NOW()),
    -- qa:delete
    (1, 'qa:delete', 'MANAGE', NOW()), (2, 'qa:delete', 'WRITE', NOW()), (3, 'qa:delete', 'READ', NOW()),
    (4, 'qa:delete', 'NONE', NOW()), (5, 'qa:delete', 'NONE', NOW()),
    -- kb:list
    (1, 'kb:list', 'MANAGE', NOW()), (2, 'kb:list', 'MANAGE', NOW()), (3, 'kb:list', 'WRITE', NOW()),
    (4, 'kb:list', 'READ', NOW()), (5, 'kb:list', 'READ', NOW()),
    -- kb:edit
    (1, 'kb:edit', 'MANAGE', NOW()), (2, 'kb:edit', 'WRITE', NOW()), (3, 'kb:edit', 'NONE', NOW()),
    (4, 'kb:edit', 'NONE', NOW()), (5, 'kb:edit', 'NONE', NOW()),
    -- kb:delete（G2-3：仅超级管理员为「管理」——删除知识库是生命周期动作）
    (1, 'kb:delete', 'MANAGE', NOW()), (2, 'kb:delete', 'READ', NOW()), (3, 'kb:delete', 'NONE', NOW()),
    (4, 'kb:delete', 'NONE', NOW()), (5, 'kb:delete', 'NONE', NOW()),
    -- doc:upload
    (1, 'doc:upload', 'MANAGE', NOW()), (2, 'doc:upload', 'WRITE', NOW()), (3, 'doc:upload', 'WRITE', NOW()),
    (4, 'doc:upload', 'NONE', NOW()), (5, 'doc:upload', 'NONE', NOW()),
    -- doc:view
    (1, 'doc:view', 'MANAGE', NOW()), (2, 'doc:view', 'WRITE', NOW()), (3, 'doc:view', 'READ', NOW()),
    (4, 'doc:view', 'READ', NOW()), (5, 'doc:view', 'READ', NOW()),
    -- doc:manage
    (1, 'doc:manage', 'MANAGE', NOW()), (2, 'doc:manage', 'WRITE', NOW()), (3, 'doc:manage', 'NONE', NOW()),
    (4, 'doc:manage', 'NONE', NOW()), (5, 'doc:manage', 'NONE', NOW()),
    -- sys:user
    (1, 'sys:user', 'MANAGE', NOW()), (2, 'sys:user', 'READ', NOW()), (3, 'sys:user', 'NONE', NOW()),
    (4, 'sys:user', 'NONE', NOW()), (5, 'sys:user', 'NONE', NOW()),
    -- sys:role
    (1, 'sys:role', 'MANAGE', NOW()), (2, 'sys:role', 'NONE', NOW()), (3, 'sys:role', 'NONE', NOW()),
    (4, 'sys:role', 'NONE', NOW()), (5, 'sys:role', 'NONE', NOW()),
    -- sys:audit
    (1, 'sys:audit', 'MANAGE', NOW()), (2, 'sys:audit', 'NONE', NOW()), (3, 'sys:audit', 'NONE', NOW()),
    (4, 'sys:audit', 'WRITE', NOW()), (5, 'sys:audit', 'NONE', NOW());
