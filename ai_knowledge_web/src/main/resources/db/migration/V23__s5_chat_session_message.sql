-- ===========================================================================
-- S5 · 阶段 A：问答会话与消息（`T-802` 的**建表半边**）
--
-- 为什么建表半边要提前到本批（`T-800`/`T-801` 所在的批次）
-- ---------------------------------------------------------------------------
-- `10-接口约定汇总.md` §9 的 `done` 事件**必带 `messageId`**，而 `T-808`
-- （回答归因 `used_model_id`）与 `T-807`（反馈 `like`/`unlike` + `reason` 落库）
-- **都挂在消息行上**。若本批只发一个临时 id，那么：
--   · 本批产出的每一次问答**永远无法归因**（"同一会话为何答案风格变了"答不出）；
--   · `T-807` 落地时，之前的问答**没有一行可挂反馈**。
-- 这与 `T-707` 的 `D-2` 是**同一种形状**：事件的参与者不齐，数据就永久缺一块，
-- 事后补不回来。所以把两张表提前，`T-802` 只留**查询/删除/导出**半边。
--
-- ⚠️ 与会话管理（`T-802`）的边界：本批只提供「会话解析 + 开新会话 + 落消息」，
--    **列表 / 消息列表 / 删除 / 导出**四个接口不在本批（前端 `chatApi.sessions/messages`
--    已真调，本批不交则该处仍 404 —— 已登记进计划 §9）。
--
-- ---------------------------------------------------------------------------
-- `model_id` 为什么在会话上，而不是在消息上
-- ---------------------------------------------------------------------------
-- 需求 `12` §模型的语义表（`10` §9 的「`modelId` 的语义」）冻结了三条：
-- 不传 → 用会话绑定的；传了且一致 → 继续；**传了但不一致 → 服务端开新会话**。
-- 也就是「会话与模型是**绑定**关系，不支持同一会话中途换模型」（保证答案可归因 +
-- 避免上下文跨模型语义漂移）。所以绑定必须记在**会话**上。
-- 消息行仍冗余一份 `model_id`：它是**实际用的**那一个（`T-808` 的 `used_model_id`），
-- 与"会话当时绑定的是哪个"是两个事实 —— 前者是痕迹，后者是配置。
--
-- ---------------------------------------------------------------------------
-- 软删口径
-- ---------------------------------------------------------------------------
-- 与 `sys_document` 同型：`deleted_at` 软删，读侧一律带 `deleted_at is null`。
-- 消息行**不单独软删** —— 它们随会话一起不可见（会话是聚合根）。
--
-- ---------------------------------------------------------------------------
-- `title` 与 `updated_at`
-- ---------------------------------------------------------------------------
-- `title` 本批取**首个提问的截断**（`T-802` 若要做"自动起标题"再改，那是它的口径）。
-- 不设 `DEFAULT NULL` 而设 `DEFAULT ''`：列表页不必为"还没标题"多写一层判空。
-- `updated_at` 由**每条消息落库时更新**，列表按它倒序 —— 不用 `max(created_at)`
-- 子查询，那是每次列表都要扫一次消息表。
--
-- 可逆：两张表都是新建，`DROP TABLE` 即可（有真实会话数据后不可逆）。
--
-- ⚠️ 表名同样按 `sys_` 前缀约定（与 V22 同一条理由，见 V22 头注释）。
-- ===========================================================================

CREATE TABLE sys_chat_session
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL COMMENT '会话归属人 —— 会话列表与回看都按它过滤（越权读会话是数据泄露）',
    title       VARCHAR(255) NOT NULL DEFAULT '' COMMENT '会话标题。本批取首个提问的截断（T-802 若做自动起标题再改）',
    model_id    BIGINT       NULL COMMENT '该会话**绑定**的对话模型 id。⚠️ 绑定关系：不支持同一会话中途换模型，不一致时服务端开新会话（G5-10）',

    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '每条消息落库时更新；列表按它倒序（避免 max(created_at) 子查询）',
    deleted_at  DATETIME     NULL COMMENT '软删标记，与 sys_document 同型；读侧一律带 is null',

    -- 列表页的唯一热查询：某人的会话，未删，按最近活动倒序
    KEY idx_cs_user (user_id, deleted_at, updated_at)
) COMMENT '问答会话（T-802 建表半边）—— 会话与模型是绑定关系（G5-10）';


CREATE TABLE sys_chat_message
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      BIGINT       NOT NULL COMMENT '所属会话（会话是聚合根，消息不单独软删）',
    role            VARCHAR(16)  NOT NULL COMMENT 'user / assistant',
    content         MEDIUMTEXT   NOT NULL COMMENT '消息正文。user 行是提问原文，assistant 行是完整回答',

    -- ---------- 归因（T-808 的落点）----------
    model_id        BIGINT       NULL COMMENT '该轮**实际使用**的对话模型 id（used_model_id）。⚠️ 与会话绑定的 model_id 是两个事实：这是痕迹，那是配置。user 行为 NULL',
    model_name      VARCHAR(128) NULL COMMENT '模型展示名（done 事件要回带；模型改名后仍要能还原当时显示的是什么）',
    trace_id        VARCHAR(64)  NULL COMMENT '关联 sys_retrieval_trace.trace_id —— "这轮答案是哪些分片支撑的"从它跳过去',

    -- ---------- 用量（done 事件的 tokenUsage / finishReason）----------
    token_prompt    INT          NULL COMMENT '提示词 token 数；模型未回报时为 NULL（不填 0）',
    token_completion INT         NULL COMMENT '生成 token 数；模型未回报时为 NULL',
    finish_reason   VARCHAR(32)  NULL COMMENT '结束原因（stop / length / 客户端断开等）；未知为 NULL',

    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- 消息列表的唯一热查询：某会话的消息，按时间正序
    KEY idx_cm_session (session_id, id)
) COMMENT '问答消息（T-802 建表半边）—— T-807 反馈与 T-808 归因挂在它上面';
