package com.knowledge.ai.pipeline.retrieve;

import com.knowledge.ai.core.enums.RetrievalMode;

import java.util.List;

/**
 * 一次检索的**全过程留痕**（`T-704`）—— 决策依据、各段耗时、以及那批分片本身。
 *
 * <h3>它为什么在 `RetrievalResult` 里而不另开一张表</h3>
 * <p>`T-707`（`retrieval_trace` 打点表）被计划**明确推到与 `T-800` 同批**（`D-2` 硬约束：
 * 打点后补 = 数据永久缺失）。本批交付的是**进程内的那一半**：
 * 检索详情页要看的"为什么是这几条"，在这一次请求的生命周期里就已经完整了，
 * 落库只是把它搬到另一张表。于是 M08 接上打点时，`RetrievalTrace` 就是那张表的**行形状**。
 *
 * <h3>为什么阈值要冗余记一份</h3>
 * <p>「这条被标黄了」只有在知道**当时用的是哪个阈值**时才有意义 —— 阈值是会变的
 * （用户改库配置、管理员调系统参数），而事后没人能还原"三天前那次检索用的是 0.65 还是 0.5"。
 * 这与审计里"存变更前后的值而不是只存变更后"是同一条理由。
 *
 * @param query             原始提问
 * @param mode              本次实际使用的检索方式（跨库合成后的，见 {@link RetrievalParams#conservative}）
 * @param kbIds             本次检索覆盖的知识库 id
 * @param kbCount           库数量（= {@code kbIds.size()}，单独给出是因为前端要直接显示）
 * @param docScopeCount     MySQL 侧先算出的**可见 doc_id 集合**大小（`I-9`）
 * @param docScopeFallback  是否因为 doc 集合过大而退化为「只按 kb 过滤 + 结果侧再筛」。
 *                          {@code true} 时召回精度**会下降**（见 {@code RetrievalScope}），必须显式暴露
 * @param vectorHits        向量通道召回到的条数
 * @param bm25Hits          BM25 通道召回到的条数
 * @param fusedCount        融合去重后的条数（`G5-2` 期望 ≈26）
 * @param scopeMillis       MySQL 算可见集合的耗时
 * @param recallMillis      两路召回**并行**的墙钟耗时（`G5-1`：应小于两路耗时之和）
 * @param fuseMillis        融合耗时
 * @param rerankMillis      重排耗时；未重排为 0
 * @param totalMillis       端到端耗时
 * @param recallTopK        每路召回条数
 * @param recallThreshold   本次使用的库级召回阈值
 * @param rerankTopK        本次使用的精排保留数
 * @param rerankEnabled     库级重排开关
 * @param tableWeight       表格加权系数
 * @param missThreshold     本次使用的全局未命中判定线
 * @param lowScoreWarn      本次使用的全局降权提示线
 * @param rerankApplied     重排**实际是否执行**（开关开着但服务不可用时为 {@code false}）
 * @param rerankNote        重排的处置说明（降级原因 / 未执行原因）；无特殊情况为空串
 * @param miss              是否判定未命中。⚠️ **只在 {@code rerankApplied=true} 时才有意义** ——
 *                          未重排时分数是融合分，与 {@code missThreshold} 不同量纲，恒为 {@code false}
 * @param chunks            本次返回的分片。**与 {@link RetrievalResult#chunks()} 是同一个
 *                          {@code List} 实例**（`G5-9`），由 {@link RetrievalResult} 的
 *                          构造器强制，见 {@link #withChunks}
 */
public record RetrievalTrace(String query,
                             RetrievalMode mode,
                             List<Long> kbIds,
                             int kbCount,
                             int docScopeCount,
                             boolean docScopeFallback,
                             int vectorHits,
                             int bm25Hits,
                             int fusedCount,
                             long scopeMillis,
                             long recallMillis,
                             long fuseMillis,
                             long rerankMillis,
                             long totalMillis,
                             int recallTopK,
                             double recallThreshold,
                             int rerankTopK,
                             boolean rerankEnabled,
                             double tableWeight,
                             double missThreshold,
                             double lowScoreWarn,
                             boolean rerankApplied,
                             String rerankNote,
                             boolean miss,
                             List<RetrievedChunk> chunks) {

    /**
     * 换掉 {@link #chunks}，其余字段原样。
     *
     * <p>**唯一**的写入口是 {@link RetrievalResult} 的构造器 —— 它用这个方法把两个
     * {@code chunks} 拧成同一个实例（`G5-9`）。之所以不让调用方直接 new：
     * "同源"如果靠调用方自觉，那它迟早会被一次重构拆散，而拆散的表现是
     * **检索详情页显示的分片与答案引用的分片不是同一批** —— 一种用户能看见、
     * 但开发者几乎不可能想到去查的错位。
     */
    public RetrievalTrace withChunks(List<RetrievedChunk> newChunks) {
        if (newChunks == this.chunks) {
            return this;
        }
        return new RetrievalTrace(query, mode, kbIds, kbCount, docScopeCount, docScopeFallback,
                vectorHits, bm25Hits, fusedCount, scopeMillis, recallMillis, fuseMillis,
                rerankMillis, totalMillis, recallTopK, recallThreshold, rerankTopK, rerankEnabled,
                tableWeight, missThreshold, lowScoreWarn, rerankApplied, rerankNote, miss, newChunks);
    }
}
