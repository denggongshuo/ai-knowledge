-- ===========================================================================
-- V36 · S8-E（M10 页 21 使用统计接真 + 统计离线预计算）
--
-- 五件事，都属于 `2026-09-20-S8-M10-E`：
--   ① 给检索打点补 `dept_id`（**部门快照**）—— 需求 `08` §3.7 明文要求
--   ② 新建 `sys_ops_retrieval_daily` —— 页 20 五个端点 + 页 21 `by-kb` 的日汇总
--   ③ 新建 `sys_ops_usage_daily`  —— 页 21 `overview`/`trend`/`by-dept`/`top-users` 的日汇总
--   ④ 权限码 `ops:usage:view`（第 42 个）
--   ⑤ 权限码 `ops:usage:users:view`（第 43 个）
--
-- 可逆：两张表 `DROP TABLE`、两列/两码 `DELETE`/`DROP COLUMN`。
-- ⚠️ 汇总表可**完整重建**（源就是 `sys_retrieval_trace`）⇒ 它不承载任何独有事实，
-- 这正是"统计离线预计算"与"打点"该有的关系：**打点是事实，汇总是缓存**。
--
-- 跑过的迁移只增不改：V1~V35 一行不动。
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- ① `dept_id` —— 为什么必须**现在**加，而不是"等页面要了再说"
--
-- 需求 `08` §3.7 的实现约束表里逐字写着：
--   「部门维度**取当时的部门快照**，而不是实时关联 | 部门调整会影响历史统计」
--
-- 而 `sys_retrieval_trace`（`V22`）**一个部门列都没有** ⇒ 只有两条路：
--   (a) 实时 `join sys_user`：**直接违反那句话** —— 某员工从研发调去销售，
--       他过去半年的提问会**追溯性地**从研发的统计里消失（页面上看不出来）；
--   (b) 把提问时的部门**冻结进这一行**。
--
-- 🔴 走 (b) 的依据是本仓硬约束 **`D-2`「打点后补 = 数据永久缺失」** ——
-- 与 `V22` 建整张打点表、`V35` 补 `rerank_before_top1` 是同一条理由：
-- **不确定的东西可以晚点算，但没记下来的东西永远算不出来**。
--
-- 【取值时刻：写入时查 `sys_user`，**不是**取登录态里的那个】
--   `LoginUser.deptId` 来自 **JWT claim**（`JwtService:190` 的 `CLAIM_DEPT`）⇒
--   它是**登录那一刻**的部门。token 存活期内调岗的话，登录态里那个就是**过期值**，
--   而本列要的是**提问时**的部门 ⇒ `RetrievalTraceWriter` 在写入时按 `user_id`
--   查一次 `sys_user.dept_id`（PK 查，代价可忽略；那一段本来就在
--   `REQUIRES_NEW` 里做若干次写）。
--
-- 【可空，NULL 的两种含义**不需要区分**】
--   · 用户已被软删 / 查不到 ⇒ NULL；
--   · （将来的）匿名提问 ⇒ NULL。
--   两种都归同一行「未分配」，故不另加区分列 —— 与 `V22` 里
--   「`mode` 可空 = 没走到那一步」那种**需要**区分的可空列不同：
--   这里的 NULL 不参与任何判定，只参与分组。
--
-- 【不只存 id，不存部门名】
--   存 id 是本仓的规范化惯例（部门名在 `sys_department` 一处）。⚠️ **代价要写明**：
--   部门**改名**后，历史行会显示新名字（分组不变）。这与 §3.7 要防的
--   「**调整**影响历史统计」是两件事 —— 调岗/撤并改的是**归属**，而归属被这一列冻结住了。
--
-- 不加索引：它只被窗口内的聚合扫到（与 `top1_score` / `rerank_before_top1` 同）。
-- ---------------------------------------------------------------------------
ALTER TABLE sys_retrieval_trace
    ADD COLUMN dept_id BIGINT NULL
        COMMENT '提问人**当时**的部门快照（需求 08 §3.7：取当时快照而非实时关联）。写入时按 user_id 查 sys_user 冻结 ⇒ 调岗不改历史统计。NULL = 用户已软删/查不到，归「未分配」';


-- ---------------------------------------------------------------------------
-- ② `sys_ops_retrieval_daily` —— 检索质量的**按天 × 库**汇总（`T-A04` · `G8-10`）
--
-- 【为什么粒度是（天 × 库）而不是（天）】
--   页 20 的五个端点里，`kb-quality` 要**按库**分；其余四个要**全站**。
--   把库当一个维度带上，全站口径 = 对所有行求和（含哨兵行），按库口径 = 只取 `kb_id > 0`
--   ⇒ **一张表同时服务两种读法**，而它们**必然同源**（不是两处各算一遍）。
--
-- 【为什么页 21 的 `by-kb` 也读这张表，而不是另建一张】
--   需求 `08` §3.4 逐字写着「与 20 页的知识库提问数必须一致——**同一个数不要在两个页面各写一遍**」。
--   让两个页面读**同一张表的同一列**，那句要求就由**构造**保证，而不是靠两处实现碰巧一致。
--   （本条是对开工计划 §4「三张表」的**收窄**：原计划的第三张
--   `sys_ops_usage_kb_daily` 的每一格都等于本表的 `total` —— 那就是"同一个数写两遍"。）
--
-- 【哨兵 `kb_id = 0`】
--   `sys_retrieval_trace` **没有 `kb_id` 列**，只有逗号串 `kb_ids`（`V22` 的 DDL 注释：
--   「只展示与粗筛，不做关联」）。归属**确定**的行当且仅当 `kb_count = 1` 且
--   `kb_ids` 是**单个数字**（与 `OpsRetrievalService#firstId` 的判据逐字对齐）。
--   其余（跨库提问 / 空串 / 非数字）一律落 `kb_id = 0`。
--   🔴 于是 `全站口径 = sum(所有行) == count(sys_retrieval_trace)` **恒等**（可证伪），
--   而 `kb_id > 0` 的行集合与页 20 `singleKbAggregates` 的 `kb_count = 1` **同一集合**。
--
-- 【列取的都是"可加和量"，没有一个是 max/min/去重计数】
--   这是本表能被跨天求和的**唯一前提**。`avg(x)` 一律拆成 `x_sum` + `x_count`
--   （`top1_sum/top1_count`、`llm_ms_sum/llm_count`）—— 直接存 `avg` 会让
--   「7 天的平均」变成「7 个平均的平均」，那是**另一个数**。
--
-- 【`hits`/`b_*`/`before_hits`/`after_hits` 用的是**行自己的** `miss_threshold`】
--   `V22` 把 `miss_threshold` 冗余在每一行上（当时的全局判定线）⇒ 这些判定**不依赖现在的配置**，
--   因而可以预计算。⚠️ 而页 20 `overview` 回给页面的 `missThreshold` 是**当前**配置
--   （`SystemThresholdService`）—— 那两个数**本来就该不同**（一个是历史事实，一个是当前口径）。
--
-- 【游标列】
--   本表**没有**"前半/后半"列：`trend` 的两半由 `TimeWindow.mid(range)` 决定，
--   而它随 `range` 变（7d/30d/90d）。日历天口径下 `mid` 落在一个**天边界**上
--   ⇒ 读侧按 `stat_date` 把本表切成两半再求和即可，**不需要**为每个 range 各存一套。
-- ---------------------------------------------------------------------------
CREATE TABLE sys_ops_retrieval_daily
(
    stat_date       DATE           NOT NULL COMMENT '统计日（打点 created_at 的日历天，按 JVM 时区）',
    kb_id           BIGINT         NOT NULL COMMENT '知识库 id；**0 = 归属不确定**（kb_count <> 1 / kb_ids 不是单个数字 / 空串）',

    -- ---------- 划分（三者互斥且完备：comparable + no_rerank + empty_recall == total）----------
    total           INT            NOT NULL DEFAULT 0 COMMENT '提问行数（= 该天该库的打点行数）',
    comparable      INT            NOT NULL DEFAULT 0 COMMENT '可比集：rerank_applied=1 且 top1_score 非 NULL',
    no_rerank       INT            NOT NULL DEFAULT 0 COMMENT '未重排：rerank_applied=0（分数是融合分，与判定线不同量纲）',
    empty_recall    INT            NOT NULL DEFAULT 0 COMMENT '空召回：rerank_applied=1 且 top1_score 为 NULL',

    -- ---------- 分子 / 分母（都是求和，不是均值）----------
    top1_sum        DECIMAL(18, 6) NOT NULL DEFAULT 0 COMMENT '可比集 top1_score 之和；÷ top1_count = avgTop1（**直接存 avg 就没法跨天合**）',
    top1_count      INT            NOT NULL DEFAULT 0 COMMENT '加入 top1_sum 的行数（= comparable）',
    miss_count      INT            NOT NULL DEFAULT 0 COMMENT 'miss_flag=1 的行数之和',
    hits            INT            NOT NULL DEFAULT 0 COMMENT '可比集里 top1_score >= 该行自己的 miss_threshold 的行数',
    covered         INT            NOT NULL DEFAULT 0 COMMENT 'rerank_applied=1 且 rerank_before_top1 非 NULL（重排前分数**已知**的行数）',
    before_hits     INT            NOT NULL DEFAULT 0 COMMENT 'covered 里 rerank_before_top1 >= 该行的 miss_threshold',
    after_hits      INT            NOT NULL DEFAULT 0 COMMENT 'covered 里 top1_score >= 该行的 miss_threshold',

    -- ---------- 六档分布（需求 §2.3 五档 + 本仓补的第 6 档）----------
    b_excellent     INT            NOT NULL DEFAULT 0 COMMENT '优秀 [0.80, 1.00]',
    b_good          INT            NOT NULL DEFAULT 0 COMMENT '良好 [0.60, 0.80)',
    b_fair          INT            NOT NULL DEFAULT 0 COMMENT '一般 [0.40, 0.60)',
    b_marginal      INT            NOT NULL DEFAULT 0 COMMENT '边缘 [0.35, 0.40)',
    b_miss          INT            NOT NULL DEFAULT 0 COMMENT '未命中 [0, 0.35)（按硬边界，不是行上的 miss_threshold）',

    -- ---------- 产出量 ----------
    chunk_sum       BIGINT         NOT NULL DEFAULT 0 COMMENT '返回分片数之和；÷ total = avgChunks',

    -- ---------- 九段耗时（🔴 **只在"跑完全链路"的行上求和**）----------
    -- `RetrievalOpsRepository#stages` 的 WHERE 是 `llm_generation_millis is not null`
    -- （空召回不调模型，"生成耗时"对它**不存在**），而它的三段合计判据
    -- `检索侧 + 生成 + 其他 == 全链路` **要求三项在同一个行集上**。
    -- ⇒ 下面这一组 *sum 必须与 `full_rows` **同一行集**，否则那条恒等式会
    -- **假成立**（分子分母来自不同行集，差值里混进"空召回的比例"而页面看不出来）。
    full_rows       INT            NOT NULL DEFAULT 0 COMMENT '**跑完全链路**的行数（llm_generation_millis 非空）—— 下面所有 *sum 的分母',
    scope_ms_sum    BIGINT         NOT NULL DEFAULT 0 COMMENT '可见范围',
    query_ms_sum    BIGINT         NOT NULL DEFAULT 0 COMMENT '查询解析（**恒 0**：后端无此步，见 #145；不拿别的耗时冒充）',
    embed_ms_sum    BIGINT         NOT NULL DEFAULT 0 COMMENT '提问向量化',
    vector_ms_sum   BIGINT         NOT NULL DEFAULT 0 COMMENT '向量检索',
    bm25_ms_sum     BIGINT         NOT NULL DEFAULT 0 COMMENT 'BM25 召回',
    fuse_ms_sum     BIGINT         NOT NULL DEFAULT 0 COMMENT 'RRF 融合',
    rerank_ms_sum   BIGINT         NOT NULL DEFAULT 0 COMMENT 'Rerank',
    table_ms_sum    BIGINT         NOT NULL DEFAULT 0 COMMENT '表格回表',
    llm_ms_sum      BIGINT         NOT NULL DEFAULT 0 COMMENT '生成耗时之和',
    fused_sum       BIGINT         NOT NULL DEFAULT 0 COMMENT 'RRF 融合后条数之和（÷ full_rows = avgFused）',
    total_ms_sum    BIGINT         NOT NULL DEFAULT 0 COMMENT '端到端总耗时之和',
    -- ⚠️ 这一列的**行集与上面那一组不同**（`rerank_applied = 1`，不是"跑完全链路"）——
    -- 它不是"顺手多存一个"，而是 `rerank-effect` 的 `avgLatencyMs` 的**唯一**来源：
    -- 那个数现在的口径是 `avg(case when rerank_applied = 1 then rerank_millis end)`
    -- （`RetrievalOpsRepository#window` 的第 9 个 select）。若拿上面那组里的
    -- `rerank_ms_sum` 去除，分母就从"重排执行过"变成了"跑完全链路"⇒
    -- 那个**页面上会显示的延迟**会悄悄换一个数，而谁都没改一行业务代码。
    applied_rerank_ms_sum BIGINT   NOT NULL DEFAULT 0 COMMENT '**重排实际执行过**的行上的 rerank 耗时之和（÷ (comparable+empty_recall) = rerank-effect 的 avgLatencyMs）',

    PRIMARY KEY (stat_date, kb_id),
    -- 读侧永远按窗口取（近 7 天 / 近 30 天），主键前缀就是它 ⇒ 无需额外索引
    KEY idx_ops_retrieval_daily_kb (kb_id)
) COMMENT '检索质量按天汇总（T-A04）：页 20 五个端点 + 页 21 by-kb 的**唯一**读取源；行由 OpsPrecomputeService 按天幂等重算';


-- ---------------------------------------------------------------------------
-- ③ `sys_ops_usage_daily` —— 使用统计的**按天 × 部门 × 人**汇总（`T-A04`）
--
-- 【为什么要留 `user_id` 这一维，而不是只按天存总数】
--   `G8-8` 把「活跃用户」定义成「周期内**至少提问 1 次**的用户数」——
--   那是一个**去重计数**，而**去重计数不可加和**（同一个人两天的活跃不能相加）。
--   ⇒ 必须把粒度下到人，读侧才能 `count(distinct user_id)`。
--   ⚠️ 这与本仓硬口径「列取的都是可加和量」**不冲突**：去重发生在**读侧**，
--   而它读的是"每人每天一行"的明细粒度 —— 这正是"存明细粒度、读时聚合"的标准做法。
--
-- 【`dept_id` 是**快照**，来自打点行（`V36` ① 那一列），不是 `sys_user` 的当前值】
--   与 `sys_retrieval_trace.dept_id` 同一条口径 —— 否则本表就成了"实时关联"的缓存版，
--   而需求 §3.7 恰恰禁止实时关联。
--
-- 【哨兵 `user_id = 0` / `dept_id = 0`】
--   `sys_retrieval_trace.user_id` 可空（`V22`：「未登录不留痕（问答要求登录，正常恒非空）」）。
--   可空列进不了唯一键（MySQL 唯一索引把多个 NULL 视为互不相同 ⇒ 会插出重复行），
--   故用 `0` 当哨兵（真实 id 从 1 起，`sys_user.id` 是 AUTO_INCREMENT）。
--   🔴 读侧算**活跃用户**时必须 `user_id > 0` —— 否则「无归属提问」会被当成一个活跃用户。
--   `dept_id = 0` 同理（NULL 部门归「未分配」那一行）。
--
-- 【一个人的提问数**全部归他"窗口内最后一天"所属的部门】—— 见下方读侧口径
--   本表**保留**每人每天各自的部门（不合并），因为"调岗发生在哪一天"是事实；
--   而 `by-dept` 的分组规则由读侧定（取每人窗口内最后一天的部门）——
--   那条规则让需求 §3.8 的两条等式**同时**成立（活跃合计 = 活跃总数 · 提问合计 = 提问总数）。
--   这是**口径决策**，写在 `OpsUsageService` 的 javadoc 里，不藏在这里。
-- ---------------------------------------------------------------------------
CREATE TABLE sys_ops_usage_daily
(
    stat_date  DATE           NOT NULL COMMENT '统计日（打点 created_at 的日历天，按 JVM 时区）',
    dept_id    BIGINT         NOT NULL DEFAULT 0 COMMENT '提问人**当时**的部门快照；0 = 无归属（含用户已软删）',
    user_id    BIGINT         NOT NULL DEFAULT 0 COMMENT '提问人；**0 = 无归属提问**（打点里 user_id 为 NULL 的行）',
    questions  INT            NOT NULL DEFAULT 0 COMMENT '提问行数',
    top1_sum   DECIMAL(18, 6) NOT NULL DEFAULT 0 COMMENT 'top1_score 之和（只含有分数的行）',
    top1_count INT            NOT NULL DEFAULT 0 COMMENT '加入 top1_sum 的行数（空召回不进 ⇒ 与 questions 不等是正常的）',

    PRIMARY KEY (stat_date, dept_id, user_id),
    KEY idx_ops_usage_daily_user (user_id)
) COMMENT '使用统计按天汇总（T-A04）：页 21 overview/trend/by-dept/top-users 的**唯一**读取源；按天幂等重算';


-- ---------------------------------------------------------------------------
-- ④⑤ 两个权限码
--
-- 🔴 **为什么是两个，而不是一个** —— 需求 §3.7 把这一页的可见性**分了两层**：
--   「用户维度统计**仅对管理员开放**，且不展示具体提问内容」
--   前半句是**维度级**的限制：聚合量（提问总数 / 各部门活跃 / 库热度）与
--   **个人维度**（某一行的提问数是**某个人**的）根本不是同一件事。
--   ⇒ 拆成 `view`（聚合四端点）+ `users:view`（`top-users`），边界**可证伪**：
--   **同一个 AUDITOR token，四个端点是 200、`top-users` 是 403**。
--
-- ⚠️ **为什么不复用 `ops:qa:view`**：那会给 `AUDITOR` 连 `top-users` 一起看，
--   与 §3.7 那半句直接冲突（页 21 是**唯一**会把"某个人问了多少次"摆出来的运营页）。
-- ⚠️ **为什么不只加一个"只给超管"的码**：按 `V35` 自己写下的判据，
--   射程与既有 `ops:retrieval:view` **完全相同**的码 = **谁也区分不出**的空码。
--   本批这两个码各自都有既存码区分得开：`view` 与 `retrieval:view` 射程不同
--   （前者 AUDITOR 可读）、`users:view` 与 `ops:qa:handle` 射程不同（前者超管专属）。
--
-- 授予：
--   · `ops:usage:view` → **`AUDITOR`**（与它的 `audit:view`、页 11 的 `ops:qa:view` 同性质：
--     全站视角的只读统计）。**刻意不给 `KB_ADMIN`** —— 页 21 没有"按库运维"的动作，
--     而 `KB_ADMIN` 的既有码全是围绕某个库的运维能力。
--   · `ops:usage:users:view` → **一个人都不给**（只靠 `SUPER_ADMIN` 的运行时 `*` 通配，
--     与 `V35` 的 `ops:retrieval:view` 同一条口径）。
--     ⚠️ 那它算不算 `#153`/`#168` 说的"没有角色能证伪的装饰码"？**不算**：
--     有角色能证伪它 —— `AUDITOR` 打 `top-users` 会 403，而打那四个会 200。
--     "只有超管能用"本身就是一个**可被同一个 token 证伪**的边界（这正是不复用 `ops:qa:view` 的理由）。
--
-- 分组取「系统管理」（与 `audit:*` / `ops:*` 同组）；sort 66/67 —— 该组现有 31~65，顺延。
-- ---------------------------------------------------------------------------
INSERT INTO sys_permission (code, label, group_name, sort)
VALUES ('ops:usage:view', '查看使用统计', '系统管理', 66),
       ('ops:usage:users:view', '查看使用统计的用户维度', '系统管理', 67);

INSERT INTO sys_role_permission (role_id, permission_id, created_at)
SELECT r.id, p.id, NOW()
FROM sys_role r
         JOIN sys_permission p ON p.code = 'ops:usage:view'
WHERE r.code = 'AUDITOR';
