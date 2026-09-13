-- ============================================================================
-- V3 · 认证域建表（S1 · M01 认证与账号）
--
-- 为什么这时才建：V1 只建「所有模块都要用」的骨架表，业务表随各自 Sprint 建。
-- 登录日志属于 M01，S1 开工时才有明确字段口径，此时建表不算「先猜一遍」。
--
-- 字段口径对齐：
--   frontend ai_knowledge_app/src/api/index.ts  userApi.loginLogs
--   frontend ai_knowledge_app/src/mock/index.ts  GET /api/user/login-logs
--     → 响应 { list: [{ id, method, ip, device, result, time }], total, page, pageSize }
--   docs/需求文档/10-接口约定汇总.md §14（同上一行）
--   docs/需求文档/04-需求-认证与账号.md §4.5（安全设置与登录日志）
--
-- 字段命名与「枚举 vs 展示文案」的口径：
--   method / result 落库存**枚举码**（PASSWORD / SMS / SSO、SUCCESS / FAILED），
--   接口出参时由服务层映射成中文展示文案（密码登录 / 验证码登录 / 统一身份认证）。
--   理由：库里存展示文案，将来改文案就要刷历史数据；而前端要的是中文，
--   映射放在出参侧是唯一能同时满足两者的位置。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 登录日志
--
-- 与 sys_audit_log 的分工（两者都会在登录时产生数据，容易混）：
--   sys_audit_log  → 「谁做了什么危险操作」，面向管理员，仅 🚩 接口落库；
--   sys_login_log  → 「谁在什么时候从哪台设备登录成功/失败」，面向**用户自己**
--                    （个人信息页的「登录日志」Tab）与风控（异常登录红色标记）。
-- 登录是**匿名**可达的动作（失败时账号可能都不存在），所以没有 @AuditLog 切面，
-- 由 AuthService 显式写入。
--
-- user_id 允许 NULL：登录失败时账号可能压根不存在，此时只剩 username。
-- 不设外键：账号被彻底删除后，登录日志必须保留（它是风控证据）。
-- ---------------------------------------------------------------------------
CREATE TABLE sys_login_log
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    user_id      BIGINT       NULL COMMENT '用户 id；账号不存在导致的失败为 NULL',
    username     VARCHAR(64)  NULL COMMENT '提交的登录账号（失败时也记录，用于风控统计）',
    method       VARCHAR(16)  NOT NULL COMMENT 'PASSWORD / SMS / SSO',
    ip           VARCHAR(64)  NULL,
    location     VARCHAR(64)  NULL COMMENT '归属地。v1 只区分「内网 / 外网」，不做 IP 库解析（《04》明确不做）',
    device       VARCHAR(128) NULL COMMENT '设备，由 User-Agent 简化为「浏览器 · 操作系统」',
    user_agent   VARCHAR(255) NULL COMMENT '原始 UA，排障用',
    result       VARCHAR(16)  NOT NULL COMMENT 'SUCCESS / FAILED',
    fail_reason  VARCHAR(255) NULL COMMENT '失败原因（成功为 NULL）。只记不泄敏的措辞',
    created_at   DATETIME     NOT NULL,
    created_date DATE         NOT NULL COMMENT '冗余日期列，供按天筛选/聚合与未来分区裁剪',
    PRIMARY KEY (id),
    KEY idx_login_log_user_time (user_id, created_at),
    KEY idx_login_log_created (created_at),
    KEY idx_login_log_result_created (result, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='登录日志';
