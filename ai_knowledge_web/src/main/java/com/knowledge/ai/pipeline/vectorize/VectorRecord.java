package com.knowledge.ai.pipeline.vectorize;

/**
 * 一条待写入的向量记录（`T-508`）= 块 + 它的向量 + 落库要用的标量。
 *
 * <p>{@code kbId}/{@code docId} 冗余在这里而不是从 {@code chunk} 拿：
 * {@link VectorChunk} 只带"这个块是什么"，而落 Milvus 要的是"它属于谁"——
 * 后者是**文档级**的事实（一个块的所有标量都来自同一篇文档），
 * 在编排层知道一次即可，不必每个块各存一份。
 *
 * @param chunk  块（主键 / 类型 / 密级 / 原内容都在里面）
 * @param kbId   所属知识库（写进 Milvus 标量 {@code kb_id}，多租户过滤的第一道）
 * @param docId  所属文档（写进 Milvus 标量 {@code doc_id}，也是"先删后插"的删除键）
 * @param vector 嵌入向量，长度必须等于 {@code MilvusConstants#DIMENSION}
 */
public record VectorRecord(VectorChunk chunk, long kbId, long docId, float[] vector) {
}
