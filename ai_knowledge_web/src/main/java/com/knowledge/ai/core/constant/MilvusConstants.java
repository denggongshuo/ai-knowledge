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

    // ---------- 分区（按代切换，`T-406` · D-7） ----------

    /**
     * 分区名前缀与分隔符 —— 分区名 = {@code kb<kbId>_g<generation>}，如 {@code kb8_g2}。
     *
     * <h3>为什么用分区做"重建的原子切换"</h3>
     * <p>{@code D-7} 要求重建"写新 collection + 原子切换、不得原地删旧索引"，而
     * {@code D-4}/{@code R-8} 定死了单 collection —— 整集合的切换做不了"只换 8 个库里的某一个"。
     * 分区正是这个夹缝里的解：**同一个 collection 内**按代分组，切换 = 换一个分区名去查。
     *
     * <p>⚠️ <b>代价与硬约束</b>（步 0 探针实测，见 {@code MilvusPartitionProbeTest}）：
     * <ul>
     *   <li>写进**不存在**的分区会**显式失败**，不会隐式创建 → 写前必须 ensure-exists；</li>
     *   <li>查一个**已 drop** 的分区会**报错**（65535），不是返回空集 → 检索侧绝不能
     *       指向一个可能不存在的分区，否则整个检索请求炸掉而不是少几条结果；</li>
     *   <li>drop 前必须 {@code releasePartitions}，否则以 65535 静默失败。</li>
     * </ul>
     */
    public static final String PARTITION_PREFIX = "kb";

    /** 代与库 id 的分隔符。 */
    public static final String PARTITION_GENERATION_SEPARATOR = "_g";

    /**
     * 分区名（唯一口径处 —— 读写两侧都必须叫它，不许各自拼字符串）。
     *
     * @param kbId       知识库 id
     * @param generation 代（从 1 起；见 {@code sys_kb.index_generation} 的注释里"为什么不是 0"）
     */
    public static String partitionName(long kbId, int generation) {
        return PARTITION_PREFIX + kbId + PARTITION_GENERATION_SEPARATOR + generation;
    }

    private MilvusConstants() {
    }
}
