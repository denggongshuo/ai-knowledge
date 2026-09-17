package com.knowledge.ai.pipeline.retrieve;

/**
 * **单路通道**的一条召回命中（`T-700`）。
 *
 * <h3>为什么把 payload 一起带上，而不是只带 chunkId</h3>
 * <p>Milvus 的 {@code search} 支持 {@code outputFields}，正文、标题路径、块类型**在同一次
 * 往返里就拿到了**。只带 id、事后再回 MySQL 查一遍，会把"一次检索"变成 N+1 次查询 ——
 * 而这一步的产物正好是 Rerank 的输入（Rerank 要的就是正文）。带上它是省一次往返，
 * 不是"顺手多存点东西"。
 *
 * <p>⚠️ {@link #score} 是**本通道自己的分数**，两路之间**不可比**：
 * 向量是 L2 距离（越小越近），BM25 是相关性（越大越好）。这正是
 * {@link RrfFusion} 只吃 {@link #rank} 的原因，也是本类**必须**把
 * {@link #channel} 一起带着的原因 —— 否则没人知道那个 {@code score} 该往哪边比。
 *
 * @param chunkId  分片 id（MySQL {@code sys_chunk.id} 的同值，也是 Milvus 主键）
 * @param docId    所属文档
 * @param kbId     所属知识库
 * @param blockType 块类型（写进 Milvus 标量 {@code chunk_type}）
 * @param titlePath 标题路径，可空
 * @param content  投影后的检索文本（Rerank 的输入、结果回填的正文）
 * @param channel  来自哪一路
 * @param score    本通道的原始分数（**不可跨通道比较**）
 * @param rank     本通道内的排名，**1 起**（RRF 的口径见 {@link RrfFusion}）
 */
public record RetrievalHit(String chunkId,
                           long docId,
                           long kbId,
                           String blockType,
                           String titlePath,
                           String content,
                           Channel channel,
                           double score,
                           int rank) {

    /**
     * 换掉 {@link #rank}，其余字段原样。
     *
     * <p>用途只有一个：{@link HybridRetriever} 从 Milvus 取回结果后，会在本地**显式排序**
     * 并重新编号（见那边的类注释）。之所以不在取回时就把 rank 填对，是因为排序发生在
     * 结果侧过滤**之后** —— 先编号再过滤会留下排名空洞（1,2,4,5…），
     * 而 RRF 吃的是"第几名"，一个空洞会让所有后续名次都偏移。
     */
    public RetrievalHit withRank(int newRank) {
        return new RetrievalHit(chunkId, docId, kbId, blockType, titlePath, content,
                channel, score, newRank);
    }

    /** 召回通道。 */
    public enum Channel {

        /** 稠密向量通道（{@code embedding} 列）。 */
        VECTOR("向量"),

        /** BM25 稀疏向量通道（{@code sparse} 列）。 */
        BM25("BM25");

        private final String label;

        Channel(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
