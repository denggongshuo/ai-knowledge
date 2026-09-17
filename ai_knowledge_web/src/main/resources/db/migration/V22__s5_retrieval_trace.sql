-- ===========================================================================
-- S5 · 阶段 A：检索链路打点（`T-707` · 需求 `I-13` / `I-14`）
--
-- 这张表回答三件现在**完全答不出**的事：
--   ① 用户说"搜不到"时，**当时到底发生了什么** —— 召回几条、融合几条、重排跑没跑、
--      用的是哪个阈值、有没有因为重建而降级；
--   ② `T-A03`（检索质量：四指标 + 链路耗时分解 + Top1 分布 + 各库排行）的**唯一数据源**
--      ——「预计算而非实时扫明细」是它的硬口径；
--   ③ 哪次检索**一条都没召回**（`G5-13` 要求"每次提问都落 Top1 相似度，无遗漏"）。
--
-- ---------------------------------------------------------------------------
-- 为什么它与问答**必须同批**（`D-2` 硬约束）
-- ---------------------------------------------------------------------------
-- 打点行不能由 `RetrievalService` 自己写。`I-13` 要求 6 阶段耗时齐全，其中
-- `llm_generation_millis`（生成耗时）在 `RetrievalService.search()` 返回的那一刻
-- **还不存在** —— 生成是 M08 在那之后才做的事。所以写行的时机只能是
-- **M08 的流式收尾**，而这就意味着：**打点与问答在物理上是同一次请求**。
-- 后补打点 = 从那一刻起的问答**永远无法归因**，这就是 `D-2` 说
-- 「打点后补 = 数据永久缺失」的字面原因。
--
-- 推论（本表的两条设计后果）：
--   · 本表**必须**带 `user_id` / `session_id` / `message_id` —— 它们只有 M08 知道；
--   · 本表**允许** `llm_generation_millis` 为 NULL —— 空召回时**根本没调模型**，
--     填 0 会把"没生成"说成"生成耗时 0ms"（与《02》「静默降级视为缺陷」同类）。
--
-- ---------------------------------------------------------------------------
-- 表名：为什么是 `sys_retrieval_trace` 而不是文档里写的 `retrieval_trace`
-- ---------------------------------------------------------------------------
-- 库内 **18 张业务表全部**带 `sys_` 前缀（`flyway_schema_history` 是 Flyway 自己的，
-- 不算）。需求侧两处写的是不带前缀的 `retrieval_trace`（`02-任务清单.md` 的 `T-707` 行、
-- `13-接口变更记录.md` 的 S5-M07-A 条），`T-A03` 也按那个名字找数据。
-- 本批按**前缀约定**取名，并同步订正 `13-接口变更记录.md` 的表述 ——
-- 理由：前缀是**机器可查的一致性**（这条规则已经管着 18 张表），
-- 而文档里的名字是散文提及。⚠️ `T-A03`（S8）落地时**按本名查**。
--
-- ---------------------------------------------------------------------------
-- 列与索引的取舍
-- ---------------------------------------------------------------------------
-- · `trace_id` 取 **Logback 与异步链路同一个 traceId**（`TraceContext`）——
--   这样"用户报障的那一次"可以直接拿日志里的 id 查到本行，不必另造一套关联键。
-- · `kb_ids` 存**逗号拼接**而不是子表：它只用于"哪些库参与了"的展示与
--   粗筛排行，不做关联查询；拆子表会让一次检索多出 N 行写入。`kb_count` 单独冗余
--   一列是因为前端与聚合**直接显示**它（与 `RetrievalTrace#kbCount` 同一条理由）。
-- · 阈值四列（`recall_threshold` / `rerank_top_k` / `miss_threshold` / `low_score_warn`）
--   **冗余记一份**：「这条被标黄了」只有在知道**当时用的是哪个阈值**时才有意义，
--   而阈值会变（用户改库配置、管理员调系统参数）。事后没人能还原
--   "三天前那次检索用的是 0.65 还是 0.5"—— 与审计里"存变更前后的值"是同一条理由。
-- · `rerank_note` / `degrade_note` **默认空串不默认 NULL**：「没有特殊情况」是一个
--   **确定的答案**，不是"缺失"。用 NULL 会让 `T-A03` 每次聚合都要多写一层判空。
-- · `top1_score` **可空**：空召回时**真的没有** Top1，NULL 表示"未知"，
--   0 表示"分数就是 0" —— 两者在 `T-A03` 的分布图上含义完全不同。
-- · `idx_created_at`：`T-A03` 的所有查询都带时间窗（近 7 天 / 近 30 天）。
-- · 不建 `idx_query`：正文类列做索引对中文前缀匹配没有帮助，而写放大是确定的。
--
-- ---------------------------------------------------------------------------
-- 子表 `sys_retrieval_trace_chunk`
-- ---------------------------------------------------------------------------
-- `T-707` 的任务行除了 6 阶段耗时，还点名要「**召回 chunkId + 分数 + 最终引用**」。
-- 那是**多值**，塞不进主表（逗号拼接分数会没法做 `T-A03` 的"Top1 分布"与
-- "Rerank 效果"聚合）。故拆一张明细子表，只在需要追溯单次检索时读。
-- `cited` 列本批**恒为 NULL** —— "最终引用"的**被引用**那一半要靠解析答案里的
-- 角标（`T-804`）才判得出，本批只记「**喂给了模型的**那一批」（= 主表行的全部子行）。
--
-- 可逆：两张表都是新建，`DROP TABLE` 即可（有真实数据后不可逆）。
--
-- ⚠️ 跑过的迁移只增不改：V1~V21 一行不动，本批改动全部落在 V22 / V23。
-- ===========================================================================

CREATE TABLE sys_retrieval_trace
(
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,

    -- ---------- 身份与关联（这三列只有 M08 知道，故必须在同一次请求里写）----------
    trace_id                VARCHAR(64)  NOT NULL COMMENT '当次请求的 traceId（TraceContext，与日志同源）',
    user_id                 BIGINT       NULL COMMENT '提问人；未登录不留痕（问答要求登录，正常恒非空）',
    session_id              BIGINT       NULL COMMENT '会话 id（T-802 建表半边随本批落地）',
    message_id              BIGINT       NULL COMMENT 'assistant 消息 id —— T-808 的 used_model_id 挂在它上面',
    query_text              TEXT         NOT NULL COMMENT '原始提问',

    -- ---------- 决策依据：本次实际用的是哪套参数 ----------
    mode                    VARCHAR(16)  NULL COMMENT '检索方式，取值域 = RetrievalMode（VECTOR/HYBRID/KEYWORD）。⚠️ 记的是**降级之后**实际用的那一个。**可空**：可见范围为空时根本没走到"选检索方式"那一步（RetrievalService#emptyResult 传的就是 null）—— NULL = 没有这一步，不是"方式未知"',
    kb_ids                  VARCHAR(512) NOT NULL DEFAULT '' COMMENT '参与检索的库 id，逗号拼接（只展示与粗筛，不做关联）',
    kb_count                INT          NOT NULL DEFAULT 0 COMMENT '库数量（= kb_ids 的个数，前端直接显示）',
    doc_scope_count         INT          NOT NULL DEFAULT 0 COMMENT 'MySQL 侧先算出的可见 doc_id 集合大小（I-9）',
    doc_scope_fallback      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否因 doc 集合过大退化为「只按 kb 过滤 + 结果侧再筛」—— true 时召回精度会下降',

    -- ---------- 召回与融合（G5-1 / G5-2）----------
    vector_hits             INT          NOT NULL DEFAULT 0 COMMENT '向量通道召回到的条数',
    bm25_hits               INT          NOT NULL DEFAULT 0 COMMENT 'BM25 通道召回到的条数',
    fused_count             INT          NOT NULL DEFAULT 0 COMMENT 'RRF 融合去重后的条数（G5-2 期望 ≈26）',

    -- ---------- 6 阶段耗时（I-13 / G5-12）----------
    -- 三段召回相关的分开记：`recall_millis` 是两路**并行**的墙钟（G5-1 用它证并行），
    -- `embed_millis` / `vector_search_millis` 是把向量那一路再拆开 —— §9 的线格式要把
    -- 「Embedding」与「向量召回」当**两个阶段**分别显示，不拆就只能填 0（= 伪造）。
    query_analysis_millis   BIGINT       NOT NULL DEFAULT 0 COMMENT '查询解析耗时。⚠️ 后端无独立的查询解析步，恒 0 —— 不拿「可见范围耗时」冒充',
    embed_millis            BIGINT       NOT NULL DEFAULT 0 COMMENT '提问向量化耗时（§9 的 embed 阶段）',
    vector_search_millis    BIGINT       NOT NULL DEFAULT 0 COMMENT '向量检索耗时（§9 的 vectorRecall 阶段）',
    bm25_recall_millis      BIGINT       NOT NULL DEFAULT 0 COMMENT 'BM25 召回耗时（§9 的 bm25Recall 阶段）',
    rerank_millis           BIGINT       NOT NULL DEFAULT 0 COMMENT 'Rerank 耗时；未重排为 0',
    llm_generation_millis   BIGINT       NULL COMMENT '生成耗时（§9 的 llmGeneration 阶段）。⚠️ 可空：空召回不调模型时**真的没有**这个数，填 0 = 把"没生成"说成"耗时 0ms"',
    total_millis            BIGINT       NOT NULL DEFAULT 0 COMMENT '端到端总耗时（M08 侧：检索 + 生成）',

    -- ---------- 检索链路的其余分段（§9 的 6 段里没有，但后端真测到了）----------
    scope_millis            BIGINT       NOT NULL DEFAULT 0 COMMENT 'MySQL 算可见范围的耗时',
    recall_millis           BIGINT       NOT NULL DEFAULT 0 COMMENT '两路召回并行的墙钟耗时（G5-1：应小于两路之和）',
    fuse_millis             BIGINT       NOT NULL DEFAULT 0 COMMENT 'RRF 融合耗时',
    table_millis            BIGINT       NOT NULL DEFAULT 0 COMMENT '表格回表耗时（T-705 / G5-6）；无表格命中时接近 0',

    -- ---------- 本次参数（冗余，见头注释）----------
    recall_top_k            INT          NOT NULL DEFAULT 0 COMMENT '每路召回条数',
    recall_threshold        DECIMAL(12, 6) NOT NULL DEFAULT 0 COMMENT '本次使用的库级召回阈值（T-706 的 recallThreshold）',
    rerank_top_k            INT          NOT NULL DEFAULT 0 COMMENT '本次使用的精排保留数（T-706 的 rerankTopK）',
    rerank_enabled          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '库级重排开关',
    table_weight            DECIMAL(12, 6) NOT NULL DEFAULT 0 COMMENT '表格加权系数',
    miss_threshold          DECIMAL(12, 6) NOT NULL DEFAULT 0 COMMENT '本次使用的全局未命中判定线（T-706 的 missThreshold）',
    low_score_warn          DECIMAL(12, 6) NOT NULL DEFAULT 0 COMMENT '本次使用的全局降权提示线（T-706 的 lowScoreWarn）',

    -- ---------- 处置说明（"没重排 / 降级了"必须能回答为什么）----------
    rerank_applied          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '重排**实际是否执行**（开关开着但服务不可用时为 false）',
    rerank_note             VARCHAR(512) NOT NULL DEFAULT '' COMMENT '重排的处置说明（降级原因 / 未执行原因）；无特殊情况为空串',
    degrade_note            VARCHAR(512) NOT NULL DEFAULT '' COMMENT '检索方式**降级**的说明（T-709，如"该库正在重建索引"）；无降级为空串。⚠️ mode 记的是降级**之后**的方式，降级前的那一个只在这段文字里',
    miss_flag               TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否判定未命中。⚠️ 只在 rerank_applied = 1 时才有意义（未重排时分数是融合分，与 missThreshold 不同量纲，恒 false）',

    -- ---------- 结果 ----------
    top1_score              DECIMAL(12, 6) NULL COMMENT 'Top1 相似度（I-14 / G5-13）。⚠️ 可空：空召回时**没有** Top1，NULL = 未知，0 = 分数真的是 0',
    chunk_count             INT          NOT NULL DEFAULT 0 COMMENT '本次返回的分片数（明细在 sys_retrieval_trace_chunk）',

    created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '打点时刻 = 流式收尾时刻',

    UNIQUE KEY uk_rt_trace_id (trace_id),
    -- T-A03 的所有查询都带时间窗（近 7 天 / 近 30 天）
    KEY idx_rt_created_at (created_at),
    -- 「这个用户的提问质量」与「这个会话发生了什么」
    KEY idx_rt_user (user_id),
    KEY idx_rt_session (session_id)
) COMMENT '检索链路打点（T-707）：T-A03 的唯一数据源；行由 M08 在流式收尾时写入（D-2）';


CREATE TABLE sys_retrieval_trace_chunk
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id    VARCHAR(64)    NOT NULL COMMENT '所属检索（sys_retrieval_trace.trace_id）',
    rank_no     INT            NOT NULL COMMENT '名次，从 1 开始（精排后的顺序；未重排时为融合顺序）',
    chunk_id    BIGINT         NOT NULL COMMENT '分片 id',
    doc_id      BIGINT         NULL COMMENT '所属文档（展示与"哪些文档被引用最多"用）',
    score       DECIMAL(12, 6) NULL COMMENT '该条最终分数（重排分或融合分，见主表 rerank_applied）',
    source      VARCHAR(16)    NOT NULL DEFAULT '' COMMENT '召回来源，取值域 = RetrievalSource（vector/bm25/graph）。本批实为前两个 —— 图谱旁路未实现（T-C00）',
    table_parent_id BIGINT     NULL COMMENT '表格回表（T-705）后指向的父块 id；未回表为 NULL',
    cited       TINYINT(1)     NULL COMMENT '是否被答案**实际引用**。⚠️ 本批恒 NULL —— "被引用"要从答案的角标解析（T-804），本批只记「喂给了模型的那一批」',

    KEY idx_rtc_trace (trace_id),
    KEY idx_rtc_chunk (chunk_id)
) COMMENT '检索打点的分片明细（T-707 任务行点名要的「召回 chunkId + 分数 + 最终引用」）';
