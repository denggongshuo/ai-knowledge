-- ===========================================================================
-- S6 · 阶段 B：VL 描述落点（`T-907`）+ 用量统计落点（`T-905`）的地基
--
-- 一处加列、一处建表，放在同一个迁移里，因为它们**同源**：
-- 需求 `09` §4.6 要求「每条 assistant 消息记录 `modelId` / `modelName`」，
-- 而 §1.13 要求用量「按模型拆分（含 VL 图片描述单列）」—— 两条都指向
-- 「**谁产出的这段内容/这次调用**」。VL 描述不落点，用量就统计不到 VL；
-- 用量不落点，模型页的 `monthTokensWan` 就只能覆盖对话一条链路（`#169`）。
--
-- ---------------------------------------------------------------------------
-- 一、`sys_chat_attachment` 加 6 列 —— 需求 `09` §4.6 的 attachments[] 形状
-- ---------------------------------------------------------------------------
-- `V25` 建这张表时 `T-805` 的边界是「只落 MinIO + 落库，**不调 VL**」，
-- 所以描述五件套**一列都没有**（`ChatDtos.AttachmentView` 的 javadoc 与
-- `ChatAttachmentService#get` 都如实回了 `null`，没有编假值）。
-- 本批 `T-907` 把 VL 链路接上，这六列同时成为**事实源**与**缓存旁证**。
--
-- ⚠️ **全可空、无默认值**，且这不是省事：
--   · `description` / `search_summary` / `vl_model_id` / `vl_model_name` / `prompt_version`
--     为 `NULL` = **这张图还没有被理解过**。给空串默认值会造出一个
--     "理解过、但理解结果什么都没看出来"的**假状态** —— 而这两件事对用户的
--     下一步动作完全不同（前者重试，后者换图）。`ChatDtos.AttachmentView#degraded`
--     刻意用 `Boolean` 而不是 `boolean` 就是同一条口径。
--
-- ⚠️ **列名是 `description` 而不是 `desc`** —— `DESC` 是 MySQL 的**保留字**
--   （`ORDER BY x DESC`）。用保留字当列名，Hibernate 生成的每一条语句都得
--   靠反引号活着，而漏一个反引号的表现是**运行时 SQL 语法错误**（只在真跑到
--   那一条时才炸，`ddl-auto: validate` 也未必拦得住）。对外契约里的字段名
--   仍然是 `desc`（前端 `AttachmentView.desc` 与需求 §4.6 都这么写）——
--   **契约名与列名本来就不必相同**，本仓先例：接口字段 `blockType` ↔ 列名
--   `chunk_type`（`MilvusConstants` 头注）。
--   · `degraded` 为 `NULL` = 还没有描述；`0` = 有描述且**没降级**；
--     `1` = 有描述但是**降级产出的**（VL 不可用，需求 §4.10 第 3 行）。
--     三态，压不成两态。
--
-- `prompt_version` 为什么值得单独一列（而不是并入 `desc` 的一段文本）：
--   它是**缓存 key 的第三段**（§4.8）。回看时要能回答"这段描述是哪版 prompt 产的"
--   —— 换了 prompt 而描述没变，正是 §4.8 点名的那种「改完毫无效果且很难发现」。
-- ---------------------------------------------------------------------------

ALTER TABLE sys_chat_attachment
    ADD COLUMN `description` VARCHAR(8000) NULL COMMENT 'VL 产出的完整结构化描述（给生成用）；列名不用 desc —— 那是 MySQL 保留字',
    ADD COLUMN `search_summary` VARCHAR(1000) NULL COMMENT '从描述最后一行切出的检索关键词（给检索用）',
    ADD COLUMN `vl_model_id` BIGINT NULL COMMENT '产出该描述的 VL 模型配置 id（模型被删则留 NULL，描述仍是事实）',
    ADD COLUMN `vl_model_name` VARCHAR(128) NULL COMMENT '产出该描述的 VL 模型名（冗余存，模型被删后仍可追溯）',
    ADD COLUMN `degraded` TINYINT(1) NULL COMMENT 'NULL=还没有描述；0=有描述未降级；1=有描述但降级产出',
    ADD COLUMN `prompt_version` VARCHAR(16) NULL COMMENT '生成时的 prompt 版本（缓存 key 第三段，§4.8）';

-- 为什么 `vl_model_name` 与 `vl_model_id` 冗余存两个：
--   `sys_model_config` 的行**可以被删**（`T-901`），而历史上这条描述确实由它产出。
--   只存 id 的话，模型一删，回看就只剩一个悬空数字 —— 与 `S5-M08-C` 给
--   `sys_chat_message` 同时存 `model_id` + `model_name` 是同一条理由
--   （`model_name` 才是「同一会话为什么答案风格变了」能读的那一列）。


-- ---------------------------------------------------------------------------
-- 二、`sys_model_usage` —— 用量统计的落点（`T-905`）
-- ---------------------------------------------------------------------------
-- 现状（本批开工前实测）：**没有任何一张表记用量**。
--   · 对话链路勉强可算：`sys_chat_message` 有 `token_prompt` / `token_completion`；
--   · 嵌入 / 重排 / 摘要 / HyQ / VL **全部没有落点**。
-- 而需求 `09` §1.13 要求的是「按用途分布 + 按模型拆分 + 费用估算」。
-- 于是 `T-900` 的 `stats.monthTokensWan` 只能覆盖对话一条链路（登记为 `#169`，
-- 并且**刻意不给 0** —— 那会被读成"本月确实没用量"，是另一种撒谎）。
--
-- 为什么是**聚合表**而不是"每次调用一行明细"：
--   §1.13 的消费方只有一个（模型配置页的用量卡），而它要的是**本月合计**。
--   明细表在这个需求下会同时带来两个代价：① 每次问答/每篇文档解析都多一次 INSERT
--   （问答是热路径）；② 一个月后表里的行数按调用次数增长，而**没有任何页面看它**。
--   按 `(日期, 模型, 用途)` 聚合后，行数上界是 `天数 × 模型数 × 用途数`，
--   一个月满打满算几百行。⚠️ 代价如实记在计划 §9：**无法回溯"这一次调用花了多少"**
--   （`trace_id` 只留最后一次，见下），真要做逐次归因时得另立明细表。
--
-- `purpose` 的取值域（写死在 `ModelUsageRecorder.Purpose`，与 §1.13 的
-- `byPurpose` 四个桶 + VL 一一对应）：
--   CHAT / SUMMARY / HYQ / EMBEDDING / RERANK / VL_DESC
--
-- ---------------------------------------------------------------------------
-- 🔴 为什么是**两列 token**（prompt / completion）而不是一列合计
-- ---------------------------------------------------------------------------
-- `sys_model_config` 的计价列是**分开的**：`input_price` + `output_price`
-- （`T-900` 已交付，`V26` 加的）。而输入与输出单价通常差 3~4 倍（DeepSeek-V3
-- 是 ¥2 / ¥8 每百万 token）。只存一个合计的话，费用估算只能用其中一个单价去乘
-- 全部 token —— 那不是"估算"，是**系统性偏低或偏高**，而页面上的措辞偏偏是
-- 「费用估算」（§1.13.1 的「诚实口径」明确要求它能反映真实量级）。
--
-- 于是存两列、不存合计：合计在查询里 `sum(prompt_tokens + completion_tokens)` 算。
-- **刻意不存第三列合计** —— 冗余列与明细列迟早漂移，而"哪一列是真的"没人说得清。
--
-- ⚠️ 只有**对话链路**有真正的输入/输出之分：嵌入全部是输入（`completion = 0`），
--   重排与 VL 按网关回报的拆分填；网关只报总数不报拆分时**整笔记进 prompt_tokens**
--   （宁可把它当输入计，也不均分 —— 均分会造出一个两边都不对的数）。
--
-- `trace_id` 为什么留着却"只留最后一次"：
--   它让"这一格是哪个请求写进去的"至少有一条线索可查（`0013` 的 traceId 串联纪律）。
--   真正的逐次归因不在本表能力范围内 —— 与其存一个会误导人的半截设计，不如写清边界。
-- ---------------------------------------------------------------------------

CREATE TABLE sys_model_usage
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    occurred_at DATE         NOT NULL COMMENT '归属日期（按天聚合；月统计按它 SUM）',
    model_type  VARCHAR(16)  NOT NULL COMMENT '四类之一（ModelType）',
    model_id    BIGINT       NULL COMMENT '模型配置 id；模型被删则留 NULL —— 用量是事实，不随配置删除而消失',
    model_name  VARCHAR(128) NOT NULL COMMENT '模型名（冗余存，理由同 sys_chat_message.model_name）',
    purpose     VARCHAR(24)  NOT NULL COMMENT '用途桶：CHAT/SUMMARY/HYQ/EMBEDDING/RERANK/VL_DESC',
    calls       INT          NOT NULL DEFAULT 0 COMMENT '调用次数',
    prompt_tokens     BIGINT NOT NULL DEFAULT 0 COMMENT '输入 token（计价按 input_price）。未回报为 0，不是 NULL —— 本表要能直接 SUM',
    completion_tokens BIGINT NOT NULL DEFAULT 0 COMMENT '输出 token（计价按 output_price）。同上',
    latency_ms  INT          NULL COMMENT '耗时合计（毫秒）；NULL = 本次没测（不是 0ms）',
    trace_id    VARCHAR(64)  NULL COMMENT '最后一次写入的 traceId（只留最后一次，见文件头注）',
    PRIMARY KEY (id),
    KEY idx_mu_date_type (occurred_at, model_type),
    KEY idx_mu_date_purpose (occurred_at, purpose)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '模型用量按天聚合（T-905）';

-- ⚠️ **不落任何种子数据**。空表的空态语义是明确的：`GET /system/model/usage`
-- 返回全 0 + `cost: null`（"无法估算"），而**不是** 0 元（"确认免费"）——
-- 后者的语义已经被 `NONE`（本地部署）占了。两种"0"必须能被区分。
