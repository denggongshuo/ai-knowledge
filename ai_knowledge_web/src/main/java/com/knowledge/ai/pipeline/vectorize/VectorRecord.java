package com.knowledge.ai.pipeline.vectorize;

/**
 * 一条待写入的向量记录（`T-508`）= 块 + 它的向量 + 落库要用的标量。
 *
 * <p>{@code kbId}/{@code docId} 冗余在这里而不是从 {@code chunk} 拿：
 * {@link VectorChunk} 只带"这个块是什么"，而落 Milvus 要的是"它属于谁"——
 * 后者是**文档级**的事实（一个块的所有标量都来自同一篇文档），
 * 在编排层知道一次即可，不必每个块各存一份。
 *
 * @param chunk             块（主键 / 类型 / 密级 / 原内容都在里面）
 * @param kbId              所属知识库（写进 Milvus 标量 {@code kb_id}，多租户过滤的第一道）
 * @param docId             所属文档（写进 Milvus 标量 {@code doc_id}，也是"先删后插"的删除键）
 * @param vector            嵌入向量，长度必须等于 {@code MilvusConstants#DIMENSION}
 * @param embedModel        产出这条向量的**模型标识**（如 {@code BAAI/bge-m3}）。
 *                          `O9` 步 7：有了它才能"按模型筛出旧向量"，让换嵌入模型从
 *                          "全库停摆重建"变成"新旧双写 + 灰度切查询"。
 *                          ⚠️ 它记的是**当时**的模型，不能在读的时候用当前配置去推 ——
 *                          那正是这个字段要解决的问题。
 * @param embedModelVersion 产出这条向量时的**配置版本**（{@code ModelConfigSpec#version()}）。
 *                          与 {@code embedModel} 合起来唯一标识"哪一版配置干的"。
 *                          对旧数据（本批之前写入的）两者为空串 —— 界面按「未记录」渲染，
 *                          **不要把它显示成"未知模型"**，那是两回事。
 */
public record VectorRecord(VectorChunk chunk, long kbId, long docId, float[] vector,
                           String embedModel, String embedModelVersion) {

    /** 旧形态（无模型归因）—— 仅供本批之前的调用点与测试使用，**新代码不要用它**。 */
    public VectorRecord(VectorChunk chunk, long kbId, long docId, float[] vector) {
        this(chunk, kbId, docId, vector, "", "");
    }
}
