package com.knowledge.ai.pipeline.vectorize;

import java.util.List;

/**
 * 向量落库通道（`T-508`）—— 与 {@link EmbeddingClient} 对称：**唯一碰 Milvus 的地方**。
 *
 * <h3>为什么是接口</h3>
 * <p>两个理由，都不是"为了抽象而抽象"：
 * <ol>
 *   <li><b>分层</b>：{@link ChunkVectorizer} 在 {@code pipeline} 层，若直接依赖
 *       {@code service.MilvusChunkWriter}，就成了 pipeline → service 的反向依赖；</li>
 *   <li><b>可测</b>：{@link ChunkVectorizer} 的正确性（挑块 / 投影 / 分批 / 幂等调用）
 *       要能用假 writer 验，**不联网、不碰 Milvus** —— 与 {@code ChatCaller} 同一条理由。</li>
 * </ol>
 *
 * <p>真实实现见 {@code service.MilvusChunkWriter}。
 */
@FunctionalInterface
public interface VectorWriter {

    /**
     * 用给定记录**整篇替换**某文档在**指定分区**里的全部条目（先删后插，天然幂等）。
     *
     * @param partition 目标分区名（口径见 {@code MilvusConstants#partitionName}）。
     *                  ⚠️ <b>必填，不是可选优化</b> —— 不指定分区时删的是**整个集合**
     *                  （含别的库、别的代），步 0 探针实测过这条语义。
     * @param docId     文档 id
     * @param records   要写入的记录；**空列表 = 只删不插**（清掉上一次的残留）
     * @return 实际写入的条数
     */
    int replaceDocument(String partition, long docId, List<VectorRecord> records);
}
