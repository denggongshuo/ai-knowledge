-- ===========================================================================
-- V4 · 密码最后修改时间（M01 · T-111 / T-112）
--
-- 为什么需要这一列：
--   · 《04-需求-认证与账号》§253「密码更新提示 —— 显示距上次修改天数
--     （如「密码 62 天前更新」）」；
--   · 《10-接口约定汇总》§57 / §367 的 `GET /user/security` 出参含
--     `passwordUpdatedAt`。
--   V1 建 sys_user 时没有这一列，此前无处落库 —— 若用 `updated_at` 顶替，
--   用户改一次昵称就会把"密码多久没换"重置，属语义错误。
--
-- 口径：
--   · 只在**设置密码**的三条路径上写入：改密（T-111）、重置密码（T-108/
--     T-110）、管理员重置（S2 的 T-2xx）；
--   · 注册时也写入一次（注册即设密码）；
--   · 它与 `must_change_password` 互补：后者是"强制改"的指令，
--     本列是"多久没改"的事实，互不替代。
-- ===========================================================================

ALTER TABLE sys_user
    ADD COLUMN password_updated_at DATETIME NULL
        COMMENT '密码最后修改时间（《04》§253 密码更新提示 / /user/security 出参）'
        AFTER must_change_password;

-- 回填历史数据：V2 的种子用户此前没有该值。
-- 用 created_at 兜底（建号即设初始密码），比留 NULL 更接近事实 —— 前端拿到
-- NULL 会显示成"—天前更新"，反而像坏了。仅回填为 NULL 的行，不覆盖已有值。
UPDATE sys_user
SET password_updated_at = created_at
WHERE password_updated_at IS NULL;
