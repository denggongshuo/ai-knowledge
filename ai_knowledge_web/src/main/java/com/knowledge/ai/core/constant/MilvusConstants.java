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
 *
 * <h3>列数口径（2026-09-17 · S5-M07-A **订正**）</h3>
 * <p>原 `G4-10` 的口径是「字段集<b>恰好 8 列</b>」，`S0VerificationTest#milvusSchemaIsLocked`
 * 逐字钉着它。本批为 BM25 增加了 {@link #FIELD_BM25_TEXT} 与 {@link #FIELD_SPARSE} 两列
 * （原因见 {@link #FIELD_BM25_TEXT}），故订正为：
 *
 * <blockquote>
 * <b>载荷 9 列</b>（id / embedding / kb_id / doc_id / chunk_type / secret_level /
 * content / title_path / bm25_text）+ <b>BM25 稀疏 1 列</b>（sparse）= <b>10 列</b>；
 * 其中建 <b>INVERTED 索引的可过滤字段仍是 4 个</b>（kb_id / doc_id / chunk_type /
 * secret_level）—— 后半句才是 `G4-10` 真正要锁的东西（见 `汇总.md` §3 #82 的裁定）。
 * </blockquote>
 *
 * <p>⚠️ 这次订正是「门禁与需求直接冲突」的唯一解：`FR-RET-03` §3.2 把 BM25 定为必需品，
 * `G5-1` 要求两路并行，而 Milvus 的 BM25 <b>必须</b>有稀疏字段承载 —— 且步 0 探针实测
 * <b>稀疏字段加不进已有集合</b>（`not support to add vector field`），所以那次重建
 * <b>只能趁向量数为 0 时做</b>。订正记录见 `plan/2026-09-17-S5-M07-A.md` §4 与 `汇总.md` §5。
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

    /** 分片正文。用于结果回填（**人读的那份**）。 */
    public static final String FIELD_CONTENT = "content";

    /** 标题路径（索引增强之一：标题路径注入）。 */
    public static final String FIELD_TITLE_PATH = "title_path";

    // ---------- BM25 全文检索（2026-09-17 · S5-M07-A 步 0 探针定稿） ----------

    /**
     * BM25 的**输入文本列** —— 存 {@link #FIELD_CONTENT} 经
     * {@code IdentifierTokenizer} 分词后的空格分隔形式。
     *
     * <h3>为什么不能直接拿 {@link #FIELD_CONTENT} 当 BM25 输入</h3>
     * <p>因为分词器要**可控**。步 0 探针实测（2026-09-17，五组对照样本）：
     * <ul>
     *   <li>Milvus 内置 <b>standard</b> 分词：查 {@code A-100} <b>召回了 {@code A-1000}</b>
     *       —— 它把标识符切成 {@code a} + {@code 100}，正是 `07` §3.2 点名的那个坑；</li>
     *   <li>Milvus <b>jieba</b> 分词：保住 {@code A-100}/{@code v1.4}，
     *       但<b>保不住</b> {@code ￥1,200} / {@code SKU-8890-A} / {@code 2026-09-11}；</li>
     *   <li><b>whitespace + 本仓 {@code IdentifierTokenizer} 预分词</b>：
     *       Q-4 的五类标识符 <b>10/10 零串扰</b>。</li>
     * </ul>
     * <p>所以 analyzer 固定为 {@link #ANALYZER_TOKENIZER_WHITESPACE}（只按空格切，
     * **不做任何大小写归一**），分词责任全部收归本仓 —— 写入侧与查询侧**必须调同一个函数**，
     * 否则会出现"查得到却排不上"的怪现象。
     *
     * <p>⚠️ 与 {@link #FIELD_CONTENT} 分成两列（而不是就地覆盖）是 `O8`「块存事实、视图是投影」
     * 的同一条原则：同一段文本的两种**视图**（人读 / 检索）不该互相覆盖。
     */
    public static final String FIELD_BM25_TEXT = "bm25_text";

    /**
     * BM25 的**输出稀疏向量列** —— 由 Milvus 的 BM25 函数从
     * {@link #FIELD_BM25_TEXT} 自动生成，**应用侧永不直接写它**。
     */
    public static final String FIELD_SPARSE = "sparse";

    /** BM25 函数名（建集合时声明，检索时报它作为 {@code annsField} 的搭档）。 */
    public static final String BM25_FUNCTION_NAME = "bm25_fn";

    /**
     * {@link #FIELD_BM25_TEXT} 的 analyzer 分词器。
     *
     * <p>取 {@code whitespace} 的唯一理由是**它什么都不做**（只按空白切、不改大小写）
     * —— 这样"什么算一个 token"就完全由本仓的 {@code IdentifierTokenizer} 决定。
     * 换成 {@code standard}/{@code jieba} 都会让标识符保护失效（见
     * {@link #FIELD_BM25_TEXT} 的实测记录）。
     */
    public static final String ANALYZER_TOKENIZER_WHITESPACE = "whitespace";

    // ---------- 维度与度量 ----------

    /**
     * 向量维度，**锁死 1024**。这是**集合（schema）的维度**，也是本系统唯一认的维度。
     *
     * <p>由嵌入模型 BAAI/bge-m3 决定（1024 维，L2 归一化）。
     * ⚠️ 曾误配置为 1536，与 bge-m3 不符会导致入库直接失败。
     * 这是全局单例约束的一半：模型换了维度就换，维度换了必须全库重建。
     *
     * <h4>🔴 `#225` 的口径：它不是"模型的元数据"，而是"集合的硬约束"</h4>
     * <p>取值来自**本常量**，不是去问模型。⚠️ 因此页面上那行「模型 X · 1024 维」在
     * "配置换成了一个非 1024 维的模型"时会**说错** —— 而那种配置下写入会在 Milvus 侧
     * **显式失败**（维度不符），所以错误当次就暴露，不会静默产出坏数据。
     * 这正是本版本「**不支持换嵌入模型**」的技术原因（用户 2026-09-19 裁定，`T-906`）：
     * 换维度意味着 `DROP COLLECTION` + 全量重建（`MilvusChunkWriter` 也用的是本常量）。
     * ⇒ 若要支持换维度，改的是**这里 + 重建流程**，而不是让某个页面去读模型自报的维度。
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

    // ---------- 索引名（建索引与判存在都用同一处口径） ----------

    /** 稠密向量索引名。 */
    public static final String INDEX_VECTOR = "idx_embedding";

    /** BM25 稀疏向量索引名（{@code SPARSE_INVERTED_INDEX} / {@code BM25}）。 */
    public static final String INDEX_SPARSE = "idx_sparse";

    /** 标量索引名前缀：{@code idx_<字段名>}。 */
    public static final String INDEX_SCALAR_PREFIX = "idx_";

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
