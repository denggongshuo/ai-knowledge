-- ===========================================================================
-- S5 · 阶段 C：图片附件（`T-805`）+ 反馈落库（`T-807` 的建表半边）
--
-- 本文件两件事，合在一个迁移里
-- ---------------------------------------------------------------------------
-- ① 建 `sys_chat_attachment` —— `POST/GET /chat/attachments` 的落点；
-- ② 给 `sys_chat_message` 加三列 —— `POST /chat/feedback` 的落点。
-- 两者同属"问答侧的持久化产物"，同一个批次落地，分成两个文件只会多一次版本号。
--
-- ---------------------------------------------------------------------------
-- ① `sys_chat_attachment` 为什么要有自己的表
-- ---------------------------------------------------------------------------
-- 需求 `09` FR-SYS-04 的链路是「**预上传 → 发送时才由 VL 生成描述**」：
-- 图片在用户**点发送之前**就已经上传完了，而此刻**会话还不存在**
-- （会话是 `completions` 里 `ChatSessionService#resolve` 才建/选的）。
-- 所以附件不能直接挂在消息行上 —— 它必须先有一个**不依赖会话**的落点。
--
-- 归属因此只能校验到 `user_id`：`GET /chat/attachments/{id}` 要求
-- "属于当前用户"。需求 `09:661` 写的是"需校验**会话**归属"，那条口径要等
-- 附件与会话真正绑定（`T-806`/`T-512`）之后才谈得上 —— 本表先把
-- `user_id` 这一层做扎实，绑定列留到那时再加（不预留空列）。
--
-- **不设 `deleted_at`**：附件是"预上传的临时件"，它在会话绑定之后才谈得上生命周期
-- （孤儿附件的清理规则属 `T-806`）。此时加软删标记，会出现"有标记但没有任何读侧在用它"
-- 的空壳列 —— 与 `sys_chat_message` 不单独软删是同一条取舍。
--
-- `md5` 存**客户端算的那个**（前端 `pseudoMd5` 目前是伪值，真算由 `T-806` 接）：
-- 它是 VL 描述缓存 key 的前半段（`md5 : vlModelId : PROMPT_VERSION`，需求 `09` §4.8），
-- 但现在**还没有消费方** —— 只落不用，等 `T-512` 接上。
--
-- ---------------------------------------------------------------------------
-- ② `sys_chat_message` 的三个反馈列
-- ---------------------------------------------------------------------------
-- 选「加列」而不是「另建 `sys_chat_feedback` 表」，理由三条：
--   · 一条消息只可能有一个**当前**反馈（用户改主意是覆盖，不是追加）—— 唯一性天然成立；
--   · 运营页（`G5-14` 的"可统计"）只读当前分布：负反馈率 + 按 reason 分组；
--   · 本仓已为「新增持久化产物要在 N 处登记清理」付过**四次**代价（`汇总.md` §5）。
--     少一张表 = 少一处覆盖率闸、少两处登记表、少一条残留自检。
-- 代价是**丢变更历史**（like 改成 unlike 后看不出曾经 like 过）—— 如实登记在计划 §3.4 假设 3。
--
-- 🔴 **NULL 的语义是「从未反馈」**，`unlike` 是另一个值。所以运营统计**必须**写
--    `where feedback = 'unlike'`，**不能**写 `where feedback <> 'like'` ——
--    MySQL 里 `NULL <> 'like'` 求值为 **NULL（非 true）**，看起来"过滤掉了 like"，
--    实际也把"从未反馈"一起滤掉了，负反馈率分母会静默变小。
--
-- `feedback_reason` 存**中文标签本身**（`答案不准确` 等 5 条），不存代号：
-- 与 `sys_audit_log.module` 存「问答」（`AuditModule` 的取值直接是中文）同一条口径 ——
-- 运营页分组时不必再过一层映射表。
--
-- 可逆：新表 `DROP TABLE`；三列 `ALTER TABLE ... DROP COLUMN`。
-- （有真实附件/反馈数据后均不可逆。）
--
-- ⚠️ 表名按 `sys_` 前缀约定（与 V22/V23 同一条理由）。
-- ===========================================================================

CREATE TABLE sys_chat_attachment
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT       NOT NULL COMMENT '上传人 —— 预上传时会话还不存在，归属只能记在这里（回看按它校验）',
    object_key VARCHAR(512) NOT NULL COMMENT 'MinIO 对象名（chat-attachments/{userId}/{uuid}.{ext}）。**只存对象名不存 URL**，预签名每次读取时重新签发',
    name       VARCHAR(255) NOT NULL COMMENT '原始文件名',
    size       BIGINT       NOT NULL COMMENT '字节数（与 MinIO 对象大小对照，可发现落盘截断）',
    mime       VARCHAR(128) NULL COMMENT '客户端声明的 MIME；白名单校验在服务层（只收 image/*）',
    md5        CHAR(32)     NOT NULL COMMENT '客户端算的 md5。⚠️ 目前无消费方（VL 描述缓存 key 的前半段，T-512 才用）',
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- 回看接口的唯一热查询：某个人的附件
    KEY idx_ca_user (user_id, id)
) COMMENT '问答图片附件（T-805）—— 预上传，会话绑定见 T-806/T-512';


ALTER TABLE sys_chat_message
    ADD COLUMN feedback        VARCHAR(16) NULL COMMENT '点赞/点踩：like / unlike；NULL = **从未反馈**（与 unlike 是两件事，统计别用 <> 判）',
    ADD COLUMN feedback_reason VARCHAR(64) NULL COMMENT '点踩原因（中文标签，5 条枚举之一）。⚠️ 只在 unlike 时有值；like 或未反馈为 NULL',
    ADD COLUMN feedback_at     DATETIME    NULL COMMENT '反馈时间（可被改写 —— 改主意是覆盖，不是追加）';
