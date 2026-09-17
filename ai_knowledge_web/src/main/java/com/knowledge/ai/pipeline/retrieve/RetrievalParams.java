package com.knowledge.ai.pipeline.retrieve;

import com.knowledge.ai.core.enums.KbConfigDefaults;
import com.knowledge.ai.core.enums.RetrievalMode;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.setting.SystemSettingService;
import com.knowledge.ai.core.setting.SystemSettings;
import com.knowledge.ai.dto.kb.KbDtos;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;

/**
 * 一次检索的**全部参数**（`T-703`）—— 库级与全局两个来源在这里合成一个不可变值。
 *
 * <h3>两个阈值为什么必须分开（`I-11`）</h3>
 * <p>这是本类存在的**唯一理由**，也是 `G5-4` 的判据：
 * <ul>
 *   <li>{@link #recallThreshold}（默认 0.15，**库级**）—— 宽进。
 *       低于它的候选**根本不进融合池**，所以它们**没有分数**，也就无从判断"是不是没命中"；</li>
 *   <li>{@link #missThreshold}（默认 0.35，**全局**）—— 严出。
 *       Rerank 之后的最高分低于它 → 判定未命中，走兜底话术并记入未命中清单。</li>
 * </ul>
 * <p>如果 {@code missThreshold <= recallThreshold}，那么"低于未命中线"的那些候选
 * 早就在召回阶段被丢掉了 —— **未命中判定的输入不存在**。这不是"阈值设歪了"，
 * 是这个判据**在语义上无法成立**。所以这一条**拒绝**而不是夹取（见下）。
 *
 * <h3>两种越界，两种处置（口径写死，`G5-3`）</h3>
 * <table border="1">
 *   <caption>越界处置</caption>
 *   <tr><th>参数</th><th>越界时</th><th>为什么</th></tr>
 *   <tr><td>{@code rerankTopK}</td><td><b>夹取</b>到 [3, 15] + warn</td>
 *       <td>它只是个条数旋钮。历史配置行里存了个 20 就**让整次问答失败**，
 *           代价远大于"这次多给几条"。检索是热路径，不该被一行陈年配置打死</td></tr>
 *   <tr><td>{@code missThreshold} / {@code recallThreshold} 的相对关系</td><td><b>拒绝</b>（抛错）</td>
 *       <td>见上：它让判据在语义上不成立，不是"值不好看"。夹取会**掩盖**
 *           一次配置写坏，而掩盖的后果是"未命中永远不发生"——静默且危险</td></tr>
 * </table>
 *
 * <h3>跨库检索时怎么合成</h3>
 * <p>一次提问可能命中多个知识库，而 {@code rerankTopK} / {@code rerankEnabled} 是**库级**的。
 * 本类取**最保守**的一组：{@code rerankTopK} 取最小、{@code recallThreshold} 取最大、
 * {@code rerankEnabled} **全部启用才启用**、{@code tableWeight} 取最小。
 * 理由：跨库时无法对每个库各切一刀（最终只有一份 topK 结果），
 * 而"取最保守"是唯一不会让某个库越权多给内容的口径。
 * ⚠️ 本批的消费方是 M08 单库问答；多库口径等真实用例出现时按那时的事实重定。
 *
 * @param mode            检索方式（决定开哪几路通道）
 * @param recallThreshold 库级召回阈值（宽进），低于它的候选不进融合池
 * @param rerankTopK      精排保留数，区间 [3, 15]
 * @param rerankEnabled   库级重排开关
 * @param tableWeight     表格 Rerank 加权系数
 * @param missThreshold   全局未命中判定线（严出）
 * @param lowScoreWarn    全局降权提示线，低于它**只标黄、不过滤**
 * @param recallTopK      每路召回的候选条数（固定 20，见 {@link #RECALL_TOP_K}）
 */
@Slf4j
public record RetrievalParams(RetrievalMode mode,
                              double recallThreshold,
                              int rerankTopK,
                              boolean rerankEnabled,
                              double tableWeight,
                              double missThreshold,
                              double lowScoreWarn,
                              int recallTopK) {

    /** 每路召回的候选条数。固定值，不做配置项 —— 见下。 */
    public static final int RECALL_TOP_K = 20;

    /** {@code rerankTopK} 的下界（前端「Rerank 保留数」的滑杆下限）。 */
    public static final int RERANK_TOP_K_MIN = 3;

    /** {@code rerankTopK} 的上界（同上）。 */
    public static final int RERANK_TOP_K_MAX = 15;

    /**
     * 每路召回条数**固定 20**，不做配置项。
     *
     * <p>理由：它不是产品口径，而是"融合池要有多少候选"的**工程值**。
     * 20+20 去重后约 26 条（`G5-2` 的期望），刚好够 Rerank 从里面挑 3~15 条。
     * 让它可配意味着"库 A 召回 20、库 B 召回 200"，跨库融合的结果将取决于
     * **哪几路先返回**，那是一种没人能解释的排序。真要调，改这里并重跑评测集（`T-711`）。
     */
    public static final int FIXED_RECALL_TOP_K = RECALL_TOP_K;

    /**
     * 由库级配置 + 全局参数合成。
     *
     * @param kbConfig 库级检索配置（读 {@code sys_kb.retrieval_config_json}）。
     *                 为 {@code null} 时全部走默认值 —— 库配置这一列在历史行上可能为空
     * @param settings {@link SystemSettingService#snapshot()}：全局阈值快照
     * @throws BizException {@code 10030} 当 {@code missThreshold <= recallThreshold}（`I-11`）
     */
    public static RetrievalParams resolve(KbDtos.RetrievalConfig kbConfig,
                                          SystemSettingService.Snapshot settings) {
        double globalRecall =
                settings.decimal(SystemSettings.RETRIEVAL_RECALL_THRESHOLD,
                        SystemSettings.DEFAULT_RECALL_THRESHOLD);
        double missThreshold =
                settings.decimal(SystemSettings.RETRIEVAL_MISS_THRESHOLD,
                        SystemSettings.DEFAULT_MISS_THRESHOLD);
        double lowScoreWarn =
                settings.decimal(SystemSettings.RETRIEVAL_LOW_SCORE_WARN,
                        SystemSettings.DEFAULT_LOW_SCORE_WARN);
        // `T-706` 查出：这个键此前**取了却没用** —— `SystemSettingService.KEYS` 一直在查
        // `retrieval.rerank-top-k`，而本方法读的是 Java 常量 `KbConfigDefaults.RERANK_TOP_K`。
        // 两个真值源当时都等于 5，所以没有任何现象；一旦有人在 `sys_setting` 里把它改成 7，
        // 就会出现「`GET /system/thresholds` 报 7、检索实际用 5」—— 两边都不报错。
        // 兜底仍取常量（与 `V2` 种子、`KbConfigDefaults` 三者同值，含义是"键缺行"）。
        int globalRerankTopK = settings.integer(SystemSettings.RETRIEVAL_RERANK_TOP_K,
                SystemSettings.DEFAULT_RERANK_TOP_K);

        RetrievalMode mode = kbConfig == null
                ? KbConfigDefaults.RETRIEVAL_MODE
                : parseMode(kbConfig.mode());
        double recallThreshold = kbConfig == null ? globalRecall : kbConfig.recallThreshold();
        int rerankTopK = kbConfig == null ? globalRerankTopK : kbConfig.rerankTopK();
        boolean rerankEnabled = kbConfig == null || kbConfig.rerankEnabled();
        double tableWeight = kbConfig == null ? KbConfigDefaults.TABLE_WEIGHT : kbConfig.tableWeight();

        requireMissLineAbove(missThreshold, recallThreshold, kbConfig == null ? "默认值" : "库级配置");

        return new RetrievalParams(mode, recallThreshold, clampTopK(rerankTopK), rerankEnabled,
                tableWeight, missThreshold, lowScoreWarn, RECALL_TOP_K);
    }

    /**
     * 跨库检索时把各库参数折成**最保守**的一组。
     *
     * @throws BizException 空集合（调用方应先确保至少有一个库才走到这里）
     */
    public static RetrievalParams conservative(Collection<RetrievalParams> all) {
        if (all == null || all.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "没有可检索的知识库");
        }
        RetrievalParams first = all.iterator().next();
        if (all.size() == 1) {
            return first;
        }
        double recallThreshold = all.stream().mapToDouble(RetrievalParams::recallThreshold).max().orElseThrow();
        int rerankTopK = all.stream().mapToInt(RetrievalParams::rerankTopK).min().orElseThrow();
        boolean rerankEnabled = all.stream().allMatch(RetrievalParams::rerankEnabled);
        double tableWeight = all.stream().mapToDouble(RetrievalParams::tableWeight).min().orElseThrow();
        // 两个全局阈值与检索方式在各库里必然相同（来源同一处 / 库级 mode 不参与跨库合成），
        // 取首库的即可 —— 不取 min/max 是为了避免"看起来也是算出来的"这种错觉
        RetrievalParams merged = new RetrievalParams(first.mode(), recallThreshold, rerankTopK,
                rerankEnabled, tableWeight, first.missThreshold(), first.lowScoreWarn(), RECALL_TOP_K);
        if (all.stream().map(RetrievalParams::mode).distinct().count() > 1) {
            // 不静默：跨库而检索方式不一致时，本次用的是**首库**的方式。
            // 表现会是"某个库的关键词命中了却没回来"，所以必须留痕
            log.warn("跨库检索而各库检索方式不一致，本次按首库的 {} 执行：{}",
                    first.mode(), all.stream().map(RetrievalParams::mode).distinct().toList());
        }
        return merged;
    }

    /** 是否走向量通道。 */
    public boolean usesVector() {
        return mode.usesVector();
    }

    /** 是否走 BM25 通道。 */
    public boolean usesBm25() {
        return mode.usesBm25();
    }

    /**
     * `I-11`：未命中判定线必须**严格高于**召回阈值。
     *
     * <p>错误信息里给出原因（"未命中无从判定"），而不只是"参数不合法" ——
     * 硬口径：失败要给「现象 + 下一步」。
     */
    private static void requireMissLineAbove(double missThreshold, double recallThreshold, String source) {
        if (missThreshold > recallThreshold) {
            return;
        }
        throw BizException.of(ErrorCode.PARAM_INVALID,
                "未命中判定线（" + missThreshold + "）必须大于召回阈值（" + recallThreshold + "，来自" + source
                        + "）—— 否则低于召回阈值的候选根本没有分数，未命中无从判定。"
                        + "请调高 retrieval.miss-threshold 或调低该库的召回阈值");
    }

    private static int clampTopK(int value) {
        if (value >= RERANK_TOP_K_MIN && value <= RERANK_TOP_K_MAX) {
            return value;
        }
        int clamped = Math.min(RERANK_TOP_K_MAX, Math.max(RERANK_TOP_K_MIN, value));
        log.warn("Rerank 保留数越界，已夹取到合法区间：配置={} 区间=[{}, {}] 实际={}",
                value, RERANK_TOP_K_MIN, RERANK_TOP_K_MAX, clamped);
        return clamped;
    }

    /**
     * 解析检索方式。库里的值由写入侧校验过，但**历史脏数据不该让检索整体崩掉**：
     * 认不出来时按 {@code HYBRID} 走（它是默认值，也是"多召回一路"的那一侧）。
     */
    private static RetrievalMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return KbConfigDefaults.RETRIEVAL_MODE;
        }
        try {
            return RetrievalMode.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            log.warn("库级检索方式取值不合法，按默认 {} 处理：实际={}", KbConfigDefaults.RETRIEVAL_MODE, raw);
            return KbConfigDefaults.RETRIEVAL_MODE;
        }
    }
}
