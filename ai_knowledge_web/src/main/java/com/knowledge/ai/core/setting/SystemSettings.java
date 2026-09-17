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

    // ==================== 缺行时的兜底值 ====================

    public static final double DEFAULT_RECALL_THRESHOLD = 0.15;

    public static final double DEFAULT_MISS_THRESHOLD = 0.35;

    public static final double DEFAULT_LOW_SCORE_WARN = 0.65;

    public static final int DEFAULT_RERANK_TOP_K = 5;
}
