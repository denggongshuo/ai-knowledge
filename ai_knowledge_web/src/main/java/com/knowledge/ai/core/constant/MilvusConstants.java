package com.knowledge.ai.core.constant;

/**
 * Milvus 向量库的字段名 / 集合名常量（T-010 的口径来源）。
 *
 * <p>**单 collection 设计**（决策 D-4 / 风险 R-8）：所有知识库共用
 * {@link #COLLECTION} 一个 collection，靠标量字段做多租户与权限过滤，
 * 而不是"一个知识库一个 collection"。
 *
 * <p>字段名与《10-接口约定汇总》§14 的口径对应：
 * 接口字段名 {@code blockType} ↔ 入库列名 {@code chunk_type}。
 */
public final class MilvusConstants {

    /** 集合名。 */
    public static final String COLLECTION = "knowledge_vectors";

    /** 向量字段名。 */
    public static final String FIELD_VECTOR = "embedding";

    /** 主键：分片 id（MySQL 侧 chunk.id 的同值）。 */
    public static final String FIELD_ID = "id";

    // ---------- 标量字段（过滤维度） ----------

    /** 知识库 id。多租户隔离的第一道过滤条件。 */
    public static final String FIELD_KB_ID = "kb_id";

    /** 文档 id。 */
    public static final String FIELD_DOC_ID = "doc_id";

    /** 分片块类型（取值见 {@code BlockType}）。**列名固定为 chunk_type**。 */
    public static final String FIELD_CHUNK_TYPE = "chunk_type";

    /** 密级（取值见 {@code SecretLevel}）。检索时按用户密级上限过滤。 */
    public static final String FIELD_SECRET_LEVEL = "secret_level";

    /** 分片正文。用于 BM25 / 结果回填。 */
    public static final String FIELD_CONTENT = "content";

    /** 标题路径（索引增强之一：标题路径注入）。 */
    public static final String FIELD_TITLE_PATH = "title_path";

    // ---------- 维度与度量 ----------

    /**
     * 向量维度，**锁死 1024**。
     *
     * <p>由嵌入模型 BAAI/bge-m3 决定（1024 维，L2 归一化）。
     * ⚠️ 曾误配置为 1536，与 bge-m3 不符会导致入库直接失败。
     * 这是全局单例约束的一半：模型换了维度就换，维度换了必须全库重建。
     */
    public static final int DIMENSION = 1024;

    /** 度量方式。bge-m3 输出已做 L2 归一化，用 L2（等价于余弦）比 COSINE 更省一次归一化。 */
    public static final String METRIC_TYPE = "L2";

    /** 索引类型。 */
    public static final String INDEX_TYPE = "IVF_FLAT";

    /** 建索引的聚类数。小数据量下 128 足够。 */
    public static final int INDEX_NLIST = 128;

    /** 检索时探测的聚类数，取 nlist 的 1/4 ~ 1/8。 */
    public static final int INDEX_NPROBE = 16;

    private MilvusConstants() {
    }
}
