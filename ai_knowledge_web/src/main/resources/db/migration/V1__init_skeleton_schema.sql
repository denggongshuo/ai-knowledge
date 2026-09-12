-- ============================================================================
-- V1 · S0 骨架域建表（T-011）
--
-- 范围说明：本脚本只建「所有模块都要用」的骨架表 —— 组织与账号、权限、系统参数、
-- 模型配置、审计日志。知识库 / 文档 / 分片 / 会话 / 未命中问题等**业务表随各自
-- 模块的 Sprint 再建**（它们此时还没有实现，先定表结构等于先猜一遍，
-- 等需求落到代码时改动概率很高）。
--
-- 命名口径：本项目的表一律 sys_ 前缀。库中原有一批 t_* 表（更早的单角色模板：
-- t_user.role 是 varchar、t_permission 只有 9 行、无部门与数据范围），
-- 与本项目的 n:m 角色模型不兼容，已按决策整体删除，不做兼容层。
--
-- 字段口径对齐：docs/需求文档/03-术语与领域模型.md §3
--             + docs/需求文档/05-需求-权限与审计.md
--             + 前端 ai_knowledge_app/src/types/index.ts 的 UserInfo / RoleInfo / DeptNode
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 部门（两层：顶级部门 → 子组）
--
-- 关于「未分配」= id 0：它是**虚拟节点**（不是真实部门），用于把"没归到任何部门的
-- 账号"显形出来，便于发现异常账号。前端 DeptNode.parentId 为 null 的顶级节点里
-- 就包含它，且 deptId = 0 会作为真实取值出现在接口里，因此 id 必须真的是 0。
-- ---------------------------------------------------------------------------
CREATE TABLE sys_department
(
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '部门 id。「未分配」固定为 0',
    name       VARCHAR(64) NOT NULL COMMENT '部门名',
    parent_id  BIGINT      NULL COMMENT '父部门 id；NULL = 顶级。仅两层',
    sort       INT         NOT NULL DEFAULT 0 COMMENT '同级排序',
    created_at DATETIME    NOT NULL,
    updated_at DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_dept_parent (parent_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='部门（两层）';

-- ---------------------------------------------------------------------------
-- 用户
--
-- 注意这里**没有** department（部门全路径名）和 hasBusinessData 两列：
-- 前者由 dept_id 关联部门树拼出来，后者是按是否存在知识库/文档/会话算出来的。
-- 派生值不落库，避免"改了部门名，用户表里的冗余名字还是旧的"这类漂移。
-- ---------------------------------------------------------------------------
CREATE TABLE sys_user
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    username             VARCHAR(64)  NOT NULL COMMENT '登录标识，全站唯一（决策 1）',
    password_hash        VARCHAR(100) NOT NULL COMMENT 'BCrypt 摘要（60 字符），永不返回前端',
    real_name            VARCHAR(64)  NOT NULL,
    phone                VARCHAR(20)  NULL,
    email                VARCHAR(128) NULL,
    dept_id              BIGINT       NOT NULL DEFAULT 0 COMMENT '所属部门；0 = 未分配。数据范围另由角色 dataScope 决定',
    status               VARCHAR(16)  NOT NULL COMMENT 'ACTIVE / DISABLED / PENDING',
    position             VARCHAR(64)  NULL COMMENT '职位（个人信息页可编辑）',
    intro                VARCHAR(500) NULL COMMENT '个人简介（个人信息页可编辑）',
    avatar               VARCHAR(255) NULL,
    must_change_password TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '重置密码后置 1，强制下次登录修改',
    last_login_at        DATETIME     NULL,
    created_at           DATETIME     NOT NULL,
    updated_at           DATETIME     NOT NULL,
    deleted_at           DATETIME     NULL COMMENT '软删除。有业务数据的账号"只能停用不能删"，删除前必须校验',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username),
    KEY idx_user_dept (dept_id),
    KEY idx_user_status (status),
    CONSTRAINT fk_user_dept FOREIGN KEY (dept_id) REFERENCES sys_department (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户';

-- ---------------------------------------------------------------------------
-- 角色
--
-- data_scope 是**角色的属性**（不是部门的）：同一用户多角色时权限点取并集、
-- 数据范围取最宽（DataScope.widest），所以范围必须挂在角色上才能叠加。
-- ---------------------------------------------------------------------------
CREATE TABLE sys_role
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    code        VARCHAR(32) NOT NULL COMMENT 'SUPER_ADMIN / KB_ADMIN / USER / AUDITOR / GUEST',
    name        VARCHAR(64) NOT NULL,
    description VARCHAR(255) NULL,
    data_scope  VARCHAR(24) NOT NULL COMMENT 'ALL / DEPT_AND_CHILD / DEPT / SELF / CUSTOM',
    enabled     TINYINT(1)  NOT NULL DEFAULT 1,
    builtin     TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '1 = 内置角色，不可删除',
    sort        INT         NOT NULL DEFAULT 0,
    created_at  DATETIME    NOT NULL,
    updated_at  DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='角色';

-- ---------------------------------------------------------------------------
-- 权限点（38 个原子码）
--
-- 列名用 group_name 而不是 group：GROUP 是 MySQL 保留字，用了就得处处反引号。
-- 这里是**只读参考表** —— 权限码的事实源是后端 PermissionCode 枚举，
-- 本表是它在库里的投影，供权限矩阵页做联表查询。
-- ---------------------------------------------------------------------------
CREATE TABLE sys_permission
(
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    code       VARCHAR(64) NOT NULL COMMENT '域:动作[:变体]，如 doc:download',
    label      VARCHAR(64) NOT NULL,
    group_name VARCHAR(32) NOT NULL COMMENT '权限树分组：知识问答 / 知识库管理 / 文档 / 系统管理',
    sort       INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_perm_code (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='权限点（38 个原子码）';

-- ---------------------------------------------------------------------------
-- 用户 ↔ 角色（n:m）
-- ---------------------------------------------------------------------------
CREATE TABLE sys_user_role
(
    user_id    BIGINT   NOT NULL,
    role_id    BIGINT   NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (user_id, role_id),
    KEY idx_ur_role (role_id),
    CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES sys_role (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户-角色';

-- ---------------------------------------------------------------------------
-- 角色 ↔ 权限（n:m）
-- ---------------------------------------------------------------------------
CREATE TABLE sys_role_permission
(
    role_id       BIGINT   NOT NULL,
    permission_id BIGINT   NOT NULL,
    created_at    DATETIME NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    KEY idx_rp_permission (permission_id),
    CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES sys_role (id),
    CONSTRAINT fk_rp_permission FOREIGN KEY (permission_id) REFERENCES sys_permission (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='角色-权限';

-- ---------------------------------------------------------------------------
-- 系统参数（阈值等）
--
-- 用 key-value 而不是"一列一个参数"：阈值会随口径调整增删（召回阈值/未命中判定线/
-- 降权线/图谱触发长度…），每加一个就改表结构不划算；而且运营分析页要按组展示，
-- 元信息（分组、中文名、类型、是否可改）必须随值一起存，否则前端只能硬编码文案。
--
-- 注意 I-11：missThreshold > recallThreshold 必须成立。这个不变量**不在库里约束**
-- （KV 表表达不了跨行约束），由写入服务校验，见 T-014 与《07》§2.1。
-- ---------------------------------------------------------------------------
CREATE TABLE sys_setting
(
    setting_key   VARCHAR(64)  NOT NULL COMMENT '参数键，如 retrieval.recall-threshold',
    setting_value VARCHAR(255) NOT NULL,
    value_type    VARCHAR(16)  NOT NULL COMMENT 'INT / DECIMAL / BOOLEAN / STRING / JSON',
    group_name    VARCHAR(32)  NOT NULL COMMENT '分组：检索 / 运营 / 文档 / 安全。GROUP 是保留字，故用 group_name',
    label         VARCHAR(64)  NOT NULL COMMENT '中文名，供前端直接展示，避免文案散落两处',
    description   VARCHAR(255) NULL,
    editable      TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '0 = 只读展示（如派生指标的口径说明）',
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (setting_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='系统参数（阈值等）';

-- ---------------------------------------------------------------------------
-- 模型配置（四类：CHAT / EMBEDDING / RERANK / VL）
--
-- api_key_cipher 存**密文**，不落明文：模型页允许用户填 key，
-- 一旦明文入库，任何一次库导出/慢日志/备份都会泄露它。
-- 加密密钥来自环境变量（与 JWT_SECRET 同级处理），空值表示"由环境变量提供"，
-- 即 .env 里那四组 *_API_KEY —— 本地开发用后者，不往库里写 key。
-- ---------------------------------------------------------------------------
CREATE TABLE sys_model_config
(
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    model_type     VARCHAR(16)   NOT NULL COMMENT 'CHAT / EMBEDDING / RERANK / VL（对齐 ModelType 枚举）',
    name           VARCHAR(64)   NOT NULL COMMENT '展示名',
    provider       VARCHAR(32)   NOT NULL COMMENT '供应商，如 deepseek / siliconflow',
    base_url       VARCHAR(255)  NOT NULL COMMENT 'OpenAI 兼容接口根地址，要写全（可能含 /v1）',
    api_key_cipher VARCHAR(512)  NULL COMMENT '加密后的 API Key；NULL = 用环境变量里同名配置',
    model_name     VARCHAR(128)  NOT NULL COMMENT '模型标识，如 BAAI/bge-m3',
    temperature    DECIMAL(3, 2) NULL COMMENT '仅 CHAT 有',
    max_tokens     INT           NULL COMMENT '仅 CHAT 有',
    billing_unit   VARCHAR(24)   NULL COMMENT '计费单位（按 token / 按次 / 免费）',
    billing_price  DECIMAL(12, 6) NULL COMMENT '单价。费用是**派生指标**，前端措辞统一用"费用估算"',
    enabled        TINYINT(1)    NOT NULL DEFAULT 1,
    is_default     TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '1 = 该类型的默认模型',
    remark         VARCHAR(255)  NULL,
    created_at     DATETIME      NOT NULL,
    updated_at     DATETIME      NOT NULL,
    PRIMARY KEY (id),
    KEY idx_model_type (model_type),
    KEY idx_model_type_default (model_type, is_default)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='模型配置（四类）';

-- ---------------------------------------------------------------------------
-- 审计日志
--
-- **列名必须与实体 com.knowledge.ai.entity.SysAuditLog 逐字一致** ——
-- ddl-auto 已改为 validate，对不上会在启动时直接失败（这是刻意的：
-- 让"表结构与代码不一致"在启动时暴露，而不是等某次查询报 Unknown column）。
--
-- 本表只增不改不删：接口层没有编辑/删除入口，写入走 REQUIRES_NEW 独立事务，
-- 业务回滚不连带回滚日志（不变量 I-12）。
-- created_date 是冗余的 DATE 列：列表页支持按天筛选，日志量大时还要按月分区，
-- 冗余一列可以避免对 DATETIME 做函数运算（那样用不上索引、也没法分区裁剪）。
-- ---------------------------------------------------------------------------
CREATE TABLE sys_audit_log
(
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    log_no         VARCHAR(32)  NOT NULL COMMENT '展示用单号：LOG-20260911-0008821',
    trace_id       VARCHAR(64)  NULL COMMENT '与业务日志、前端错误页展示的是同一个值',
    api_path       VARCHAR(512) NULL COMMENT '接口路径（含查询串）',
    module         VARCHAR(32)  NOT NULL COMMENT '业务模块，取值见 AuditModule',
    action         VARCHAR(64)  NOT NULL COMMENT '动作描述，如"新建用户"',
    risk_level     VARCHAR(16)  NOT NULL COMMENT 'HIGH / NORMAL',
    target         VARCHAR(255) NULL COMMENT '操作对象',
    operator       VARCHAR(64)  NULL COMMENT '操作人登录账号',
    operator_name  VARCHAR(64)  NULL COMMENT '操作人姓名',
    role_name      VARCHAR(128) NULL COMMENT '操作人角色（多角色逗号连接）',
    ip             VARCHAR(64)  NULL,
    user_agent     VARCHAR(255) NULL,
    result         VARCHAR(16)  NOT NULL COMMENT 'SUCCESS / FAILED',
    fail_reason    VARCHAR(500) NULL COMMENT '失败原因（失败必填，成功为 NULL）',
    cost_ms        INT          NULL COMMENT '接口耗时（毫秒）',
    affected_users INT          NULL COMMENT '受影响人数（权限变更类动作才有）',
    changes_json   TEXT         NULL COMMENT '变更前后对照，JSON 数组：[{label,before,after}]',
    note           VARCHAR(1000) NULL COMMENT '补充说明',
    created_at     DATETIME     NOT NULL,
    created_date   DATE         NOT NULL COMMENT '冗余日期列，供按天筛选与未来按月分区',
    PRIMARY KEY (id),
    UNIQUE KEY uk_audit_log_no (log_no),
    KEY idx_audit_created_at (created_at),
    KEY idx_audit_module_created (module, created_at),
    KEY idx_audit_operator_created (operator, created_at),
    KEY idx_audit_trace_id (trace_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='审计日志（只增不改不删）';
