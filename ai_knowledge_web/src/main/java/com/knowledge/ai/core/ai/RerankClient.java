package com.knowledge.ai.core.ai;

import java.util.List;

/**
 * Rerank（精排）客户端（`T-702` · `S5-M07-A` 步 8）。
 *
 * <h3>为什么是接口，而不是直接写死一家供应商</h3>
 * <p>{@code S5-M07-A} §3.4 假设 #2 写得很直白：SiliconFlow {@code POST {baseUrl}/rerank}
 * 的请求/响应契约**只是"预期"**，没实测过。做成接口的意义就是——
 * 万一形状不对，换的是**实现**，{@link com.knowledge.ai.pipeline.retrieve.RetrievalService}
 * 一行不动。
 *
 * <h3>降级是契约的一部分，不是异常处理</h3>
 * <p>{@link #available()} 为 {@code false} 时调用方**不重排**、直接取融合结果并标注
 * 「重排已降级」。这条降级是本批唯一交付的降级路径（`T-709` 的其余部分归 `S5-M07-B`）：
 * Rerank 是**锦上添花**的一跳，它挂了不该让整个检索请求失败 —— 但必须**让人看得见它挂了**，
 * 所以降级不是"悄悄返回空"，而是带 {@code available()} 的事实。
 *
 * <p>⚠️ 与 {@code ModelClientRegistry} 的纪律一致：本接口的实现**只由
 * {@link ModelClientFactory#createRerank} 构造**，业务侧不得自己 {@code new}（`T-014`）。
 */
public interface RerankClient {

    /** 端点是否齐备。{@code false} = 调用方走「不重排」降级，**不要**再去调 {@link #rerank}。 */
    boolean available();

    /** 脱敏摘要，可安全进日志与检索 trace（key 只报有无，`G0-8`）。 */
    String describe();

    /**
     * 精排：给 {@code documents} 按与 {@code query} 的相关性重新打分并排序。
     *
     * @param query     用户提问原文
     * @param documents 候选文本，**顺序即入参顺序**（返回的 {@link RerankHit#index()}
     *                  指向本列表的下标 —— 调用方据此把分数映射回分片）
     * @param topK      保留条数；实现应透传给模型侧的 {@code top_n}
     * @return 按相关性**降序**的命中；不可用时返回空列表
     */
    List<RerankHit> rerank(String query, List<String> documents, int topK);

    /**
     * 一条精排命中。
     *
     * @param index 在 {@code documents} 里的下标（**不是**分片 id ——
     *              分片 id 由调用方按下标回查，客户端不持有分片概念）
     * @param score 相关性分数，取值区间由模型决定（bge-reranker 是 0~1）
     */
    record RerankHit(int index, double score) {
    }
}
