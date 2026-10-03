package com.knowledge.ai.core.setting;

/**
 * 系统参数的**键名与默认值**（`S5-M07-A` 步 9 的全局半边）。
 *
 * <h3>为什么要有这个类</h3>
 * <p>与 {@code KbPresets} / {@code AuditRiskPolicy} 同型：把口径写成一张**可被用例逐条比对**的表。
 * 检索侧要读 {@code sys_setting} 里的三个阈值，如果键名散在几个 Service 的字面量里，
 * 「改了 V2 的种子却没改代码」这种情况不会有任何测试发现 —— 它只会在某一天表现为
 * "未命中判定线悄悄回到了默认值"。
 *
 * <h3>默认值 = V2 种子的值，不是"我认为合理的值"</h3>
 * <p>它们只在 {@code sys_setting} **缺行**时兜底（见 {@link SystemSettingService}）。
 * 之所以要有兜底而不是直接抛：检索是问答热路径，**一行配置缺失不该让整站问不出答案**。
 * 但兜底会留 {@code warn} 日志 —— 静默兜底比不兜底更糟。
 *
 * <p>⚠️ 数值源是 {@code V2__seed_reference_data.sql}。改这里必须同步改那里（或反过来），
 * 两边不一致的后果是"开发机上跑得好、干净库上跑出别的阈值"。
 */
public final class SystemSettings {

    private SystemSettings() {
    }

    // ==================== 键名（与 V2 种子逐字一致） ====================

    /** 召回阈值（宽进）。**库级可调**，本键是全站默认值。 */
    public static final String RETRIEVAL_RECALL_THRESHOLD = "retrieval.recall-threshold";

    /** 未命中判定线（严出）。**只有全局一处** —— 见 {@link com.knowledge.ai.pipeline.retrieve.RetrievalParams}。 */
    public static final String RETRIEVAL_MISS_THRESHOLD = "retrieval.miss-threshold";

    /** 降权提示线。低于此分的分片**只标黄、不过滤**。 */
    public static final String RETRIEVAL_LOW_SCORE_WARN = "retrieval.low-score-warn";

    /** Rerank 保留数的全局默认值（库级可覆盖）。 */
    public static final String RETRIEVAL_RERANK_TOP_K = "retrieval.rerank-top-k";

    /**
     * 重建索引期间是否把检索降级为关键词（`T-709` · 《11》§2.1「降级而非中断」）。
     *
     * <p>需求把「重建期间的服务状态」做成一个**二选一**：降级为关键词检索（推荐）
     * 或暂停问答服务。本键是**全站默认**，那个二选一的对话框（属 S6 的 `T-906`）
     * 会按库/按次覆盖它。
     *
     * <p>默认 {@code true}：重建期间向量代正在切换，继续走向量路会召回**旧代或半截**
     * 的内容；而降级后问答仍然可用（只是精度下降）—— 这正是需求选的"降级而非中断"。
     */
    public static final String RETRIEVAL_DEGRADE_DURING_REINDEX = "retrieval.degrade-during-reindex";

    /**
     * 图谱旁路开关（FR-GRAPH-01 · `T-C03`）。**运行时可翻**，事实源就是本键。
     *
     * <p>为什么要有兜底而不是直接抛：检索是问答热路径，**一行配置缺失不该让整站问不出答案**
     * （同 {@link #RETRIEVAL_DEGRADE_DURING_REINDEX} 的理由）。兜底会留 {@code warn}。
     *
     * <p>⚠️ 它**不**管建图。写路径用的是另一个开关 {@code m05.graph.enabled}
     * （{@code GraphWriter} / {@code GraphSchema} 构造期读取）—— 关掉旁路不等于停建图。
     *
     * <p>⚠️ **曾经**还有一个启动期属性 {@code m05.graph.retrieve.enabled} 被当作本键的
     * 部署兜底（改造前它由 {@code RetrievalService} / {@code GraphEntityLinker} /
     * {@code GraphNeighborReader} 三处各自用 {@code @Value} 读）。`T-C03` 之后它**没有
     * 任何生产代码读者** —— 唯一读口收拢到了 {@code GraphFeatureToggle}，走
     * {@link #DEFAULT_GRAPH_ENABLED}。残留的 {@code @TestPropertySource} 声明是无害的
     * 空转（见计划 §9 遗留项）。**别照着旧注释去找那个属性的读者**，它会把你引到一个空处。
     */
    public static final String RETRIEVAL_GRAPH_ENABLED = "retrieval.graph-bypass-enabled";

    // ==================== 缺行时的兜底值 ====================

    public static final double DEFAULT_RECALL_THRESHOLD = 0.15;

    public static final double DEFAULT_MISS_THRESHOLD = 0.35;

    public static final double DEFAULT_LOW_SCORE_WARN = 0.65;

    public static final int DEFAULT_RERANK_TOP_K = 5;

    public static final boolean DEFAULT_DEGRADE_DURING_REINDEX = true;

    /**
     * 图谱旁路缺行时的兜底 = **开**。
     *
     * <p>缺行是**部署事故**而不是常规状态（{@code V43} 会种上这一行）—— 兜底选"开"是为了
     * 让"迁移没跑成功"退化成"功能照旧"，而不是"图谱悄悄停了而没人知道"。
     * 每次兜底都会在 {@code SystemSettingService#snapshot()} 留一条 {@code warn}，
     * 所以它不会被静默吞掉。
     *
     * <p>⚠️ `T-D10`（2026-10-03）把**产品默认**翻成了关（`V44` 把行置 `false`），但**本兜底不动**：
     * "产品默认关"与"缺配置时怎么退化"是两件事 —— 缺行仍是部署事故，仍应可见（warn）+ 不静默改行为。
     */
    public static final boolean DEFAULT_GRAPH_ENABLED = true;
}
