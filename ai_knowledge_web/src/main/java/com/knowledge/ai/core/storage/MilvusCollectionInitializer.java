package com.knowledge.ai.core.storage;

import com.knowledge.ai.core.constant.MilvusConstants;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.index.request.ListIndexesReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Milvus 集合 / 索引初始化（T-008；`S5-M07-A` 迁到 v2 并加 BM25）。
 *
 * <h3>集合形状（**10 列**，`G4-10` 口径订正见 {@link MilvusConstants} 类 javadoc）</h3>
 * <pre>
 *   id            VarChar(64)   主键（= MySQL chunk.id）
 *   embedding     FloatVector   dim 1024 · L2
 *   kb_id         Int64         ┐
 *   doc_id        Int64         ├ 四个建 INVERTED 索引的**可过滤字段**
 *   chunk_type    VarChar(32)   │
 *   secret_level  VarChar(16)   ┘
 *   content       VarChar(64K)  载荷：投影后的检索文本（人读 / 回填）
 *   title_path    VarChar(1K)   载荷：标题路径
 *   bm25_text     VarChar(64K)  BM25 输入：content 经 IdentifierTokenizer 预分词
 *   sparse        SparseVector  BM25 输出：由 bm25_fn 自动生成，应用侧不写
 * </pre>
 *
 * <h3>为什么标量索引用「集合的索引名集合」判存在（2026-09-17 修的坑，别改回去）</h3>
 * <p>v1 时代曾用 {@code describeIndex(fieldName=…)} 判某个字段有没有索引 —— 但
 * <b>Milvus 忽略那个 fieldName</b>，恒返回整个集合的索引列表。于是守卫恒为真，
 * 四个字段全部被静默跳过，<b>索引从建表起就没建出来过、而日志看上去一切正常</b>
 * （S0 起潜伏到 S4-C3 才被发现）。现在一律按集合的索引名集合判，且
 * 「已存在 / 创建成功 / 被拒绝」三条路径都留日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusCollectionInitializer implements ApplicationRunner {

    /** 标量过滤字段：建 INVERTED 索引，避免大集合下逐行扫描。 */
    private static final List<String> SCALAR_FILTER_FIELDS = List.of(
            MilvusConstants.FIELD_KB_ID,
            MilvusConstants.FIELD_DOC_ID,
            MilvusConstants.FIELD_CHUNK_TYPE,
            MilvusConstants.FIELD_SECRET_LEVEL);

    /** Milvus VarChar 上限（与建表时一致）。 */
    private static final int MAX_VARCHAR = 65_535;

    private final MilvusClientV2 client;

    private final MilvusProperties properties;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isInitializeSchema()) {
            log.info("[Milvus] app.milvus.initialize-schema=false，跳过集合初始化（只读模式）");
            return;
        }
        String collection = properties.getCollection();
        try {
            if (collectionExists(collection)) {
                if (!ensureSchemaCurrent(collection)) {
                    return;
                }
            } else {
                createCollection(collection);
                log.info("[Milvus] 集合创建完成：{}（dim={} metric={} index={} + BM25）",
                        collection, properties.getDimension(), properties.getMetricType(), properties.getIndexType());
            }
            ensureVectorIndex(collection);
            ensureSparseIndex(collection);
            ensureScalarIndexes(collection);
            load(collection);
        } catch (Exception ex) {
            log.error("[Milvus] 集合初始化失败：{} —— 向量与 BM25 检索在本机不可用。"
                    + "请确认 milvus-standalone 容器健康，然后重启应用重试。原因：{}",
                    collection, rootMessage(ex), ex);
        }
    }

    // ==================== 存在性与 schema 守卫 ====================

    private boolean collectionExists(String collection) {
        Boolean exists = client.hasCollection(HasCollectionReq.builder()
                .collectionName(collection)
                .build());
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 校验既有集合的 schema 是否已是本批之后的新形状；不是则**在安全前提下**重建。
     *
     * <p>这是一次**结构迁移**，判据分三档（**失败一律 fail-closed**）：
     * <ol>
     *   <li>已有 {@code sparse} + {@code bm25_text} → 新形状，继续（顺带校验维度）；</li>
     *   <li>旧形状（8 列）且 <b>集合里一条向量都没有</b> → 记 WARN 后 drop + 重建
     *       —— 这正是 2026-09-17 本批的真实情形，**重建代价为零**；</li>
     *   <li>旧形状但**有向量** → <b>拒绝自动重建</b>，打出可执行的处置步骤后返回 false。</li>
     * </ol>
     *
     * <p>⚠️ 为什么第 3 档必须拒绝而不是"顺手重建"：重建等于把全库向量清空，
     * 那是 D-4 级别的不可逆操作，不该由一个启动钩子替用户决定。而这件事**越晚做越贵**，
     * 所以第 2 档的日志会明确写出"现在做是零代价"。
     *
     * @return true = 可以继续后续步骤；false = 调用方应停止
     */
    private boolean ensureSchemaCurrent(String collection) {
        DescribeCollectionRespFields fields = describeFields(collection);
        if (fields == null) {
            return false;
        }
        boolean fresh = fields.names().contains(MilvusConstants.FIELD_SPARSE)
                && fields.names().contains(MilvusConstants.FIELD_BM25_TEXT);
        if (fresh) {
            return verifyDimension(fields);
        }

        // 旧集合可能是"存在但未加载"——那时 query 会抛，countAll 会返回 -1，
        // 于是守卫 fail-closed 把自己挡住、迁移永远做不成。先尽力加载一次再数。
        tryLoadQuietly(collection);
        long vectors = countAll(collection);
        if (vectors < 0) {
            log.error("[Milvus] ⚠️ 集合 {} 是旧 schema（缺 {} / {}），且**无法确定**其中是否已有向量"
                            + "（计数未取到）→ 拒绝自动重建。请人工确认后二选一："
                            + "① 若可丢弃现有向量：DROP COLLECTION {}; 重启应用自动重建，并对全部文档触发重建索引；"
                            + "② 若不可丢弃：先导出/保留现有向量，再按同样步骤处理。",
                    collection, MilvusConstants.FIELD_BM25_TEXT, MilvusConstants.FIELD_SPARSE, collection);
            return false;
        }
        if (vectors > 0) {
            log.error("[Milvus] ⚠️ 集合 {} 是旧 schema（缺 {} / {}），且其中有 **{} 条向量** → 拒绝自动重建"
                            + "（重建会清空它们，那是不可逆操作，不该由启动钩子替你决定）。处置步骤："
                            + "① 备份/确认这些向量可丢弃；② DROP COLLECTION {}; ③ 重启应用自动重建；"
                            + "④ 对全部文档触发重建索引。",
                    collection, MilvusConstants.FIELD_BM25_TEXT, MilvusConstants.FIELD_SPARSE,
                    vectors, collection);
            return false;
        }

        log.warn("[Milvus] 集合 {} 是旧 schema（缺 {} / {}）但**当前 0 条向量** → 自动重建。"
                        + "这是 S5-M07-A 的结构迁移：BM25 必须有稀疏字段承载，而稀疏字段"
                        + "**加不进已有集合**（实测 not support to add vector field），只能重建。"
                        + "此刻重建代价为零；一旦有了向量，代价就是全库重新向量化。",
                collection, MilvusConstants.FIELD_BM25_TEXT, MilvusConstants.FIELD_SPARSE);
        client.dropCollection(DropCollectionReq.builder().collectionName(collection).build());
        createCollection(collection);
        log.info("[Milvus] 集合已重建为新 schema（10 列，含 BM25）：{}", collection);
        return true;
    }

    // ==================== 创建 ====================

    private void createCollection(String collection) {
        int dim = properties.getDimension();

        CreateCollectionReq.FieldSchema idField = CreateCollectionReq.FieldSchema.builder()
                .name(MilvusConstants.FIELD_ID)
                .description("分片 id，与 MySQL chunk.id 同值")
                .dataType(DataType.VarChar)
                .maxLength(64)
                .isPrimaryKey(true)
                .autoID(false)
                .build();

        CreateCollectionReq.FieldSchema vectorField = CreateCollectionReq.FieldSchema.builder()
                .name(MilvusConstants.FIELD_VECTOR)
                .description("bge-m3 向量，" + dim + " 维 L2 归一化")
                .dataType(DataType.FloatVector)
                .dimension(dim)
                .build();

        CreateCollectionReq.FieldSchema kbIdField = CreateCollectionReq.FieldSchema.builder()
                .name(MilvusConstants.FIELD_KB_ID)
                .description("知识库 id（多租户隔离）")
                .dataType(DataType.Int64)
                .build();

        CreateCollectionReq.FieldSchema docIdField = CreateCollectionReq.FieldSchema.builder()
                .name(MilvusConstants.FIELD_DOC_ID)
                .description("文档 id")
                .dataType(DataType.Int64)
                .build();

        CreateCollectionReq.FieldSchema chunkTypeField = CreateCollectionReq.FieldSchema.builder()
                .name(MilvusConstants.FIELD_CHUNK_TYPE)
                .description("块类型（接口字段 blockType）")
                .dataType(DataType.VarChar)
                .maxLength(32)
                .build();

        CreateCollectionReq.FieldSchema secretLevelField = CreateCollectionReq.FieldSchema.builder()
                .name(MilvusConstants.FIELD_SECRET_LEVEL)
                .description("密级（PUBLIC/INTERNAL/CONFIDENTIAL/SECRET）")
                .dataType(DataType.VarChar)
                .maxLength(16)
                .build();

        CreateCollectionReq.FieldSchema contentField = CreateCollectionReq.FieldSchema.builder()
                .name(MilvusConstants.FIELD_CONTENT)
                .description("投影后的检索文本（人读 / 结果回填）；BM25 不读这一列，见 bm25_text")
                .dataType(DataType.VarChar)
                .maxLength(MAX_VARCHAR)
                .build();

        CreateCollectionReq.FieldSchema titlePathField = CreateCollectionReq.FieldSchema.builder()
                .name(MilvusConstants.FIELD_TITLE_PATH)
                .description("标题路径（索引增强）")
                .dataType(DataType.VarChar)
                .maxLength(1_024)
                .build();

        // BM25 的输入列。analyzer 取 whitespace 的唯一理由是**它什么都不做**
        // （只按空白切、不改大小写）—— 这样"什么算一个 token"完全由本仓的
        // IdentifierTokenizer 决定。换成 standard/jieba 都会让标识符保护失效，
        // 实测记录见 MilvusConstants#FIELD_BM25_TEXT。
        CreateCollectionReq.FieldSchema bm25TextField = CreateCollectionReq.FieldSchema.builder()
                .name(MilvusConstants.FIELD_BM25_TEXT)
                .description("BM25 输入：content 经 IdentifierTokenizer 预分词（空格分隔）")
                .dataType(DataType.VarChar)
                .maxLength(MAX_VARCHAR)
                .enableAnalyzer(true)
                .analyzerParams(Map.of("tokenizer", MilvusConstants.ANALYZER_TOKENIZER_WHITESPACE))
                .build();

        CreateCollectionReq.FieldSchema sparseField = CreateCollectionReq.FieldSchema.builder()
                .name(MilvusConstants.FIELD_SPARSE)
                .description("BM25 稀疏向量，由 " + MilvusConstants.BM25_FUNCTION_NAME + " 自动生成")
                .dataType(DataType.SparseFloatVector)
                .build();

        CreateCollectionReq.Function bm25 = CreateCollectionReq.Function.builder()
                .name(MilvusConstants.BM25_FUNCTION_NAME)
                .description("content 预分词文本 → 稀疏向量")
                .functionType(FunctionType.BM25)
                .inputFieldNames(List.of(MilvusConstants.FIELD_BM25_TEXT))
                .outputFieldNames(List.of(MilvusConstants.FIELD_SPARSE))
                .build();

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(false)
                .fieldSchemaList(List.of(
                        idField, vectorField, kbIdField, docIdField, chunkTypeField,
                        secretLevelField, contentField, titlePathField, bm25TextField, sparseField))
                .functionList(List.of(bm25))
                .build();

        client.createCollection(CreateCollectionReq.builder()
                .collectionName(collection)
                .description("RAG 分片向量 + BM25（单集合 + 标量过滤）")
                .numShards(2)
                .consistencyLevel(ConsistencyLevel.BOUNDED)
                .collectionSchema(schema)
                .build());
    }

    // ==================== 索引 ====================

    /** 向量字段索引：IVF_FLAT + L2 + nlist。已存在则跳过。 */
    private void ensureVectorIndex(String collection) {
        Set<String> names = collectionIndexNames(collection);
        if (names.contains(MilvusConstants.INDEX_VECTOR)) {
            log.info("[Milvus] 向量索引已存在：{}", MilvusConstants.INDEX_VECTOR);
            return;
        }
        IndexParam param = IndexParam.builder()
                .fieldName(MilvusConstants.FIELD_VECTOR)
                .indexName(MilvusConstants.INDEX_VECTOR)
                .indexType(IndexParam.IndexType.valueOf(properties.getIndexType()))
                .metricType(IndexParam.MetricType.valueOf(properties.getMetricType()))
                .extraParams(Map.of("nlist", properties.getIndexNlist()))
                .build();
        client.createIndex(CreateIndexReq.builder()
                .collectionName(collection)
                .indexParams(List.of(param))
                .sync(true)
                .build());
        log.info("[Milvus] 向量索引创建完成：{} {}/{} nlist={}",
                collection, properties.getIndexType(), properties.getMetricType(), properties.getIndexNlist());
    }

    /**
     * BM25 稀疏索引：{@code SPARSE_INVERTED_INDEX} + 度量 {@code BM25}。
     *
     * <p>⚠️ <b>度量必须是 {@code BM25} 而不是 {@code IP}</b>：稀疏向量本身是词频权重，
     * 用 {@code IP} 会得到一个"能跑但打分不是 BM25"的结果 —— 那种偏差不会报错，
     * 只会让排序悄悄变差。
     */
    private void ensureSparseIndex(String collection) {
        Set<String> names = collectionIndexNames(collection);
        if (names.contains(MilvusConstants.INDEX_SPARSE)) {
            log.info("[Milvus] BM25 稀疏索引已存在：{}", MilvusConstants.INDEX_SPARSE);
            return;
        }
        IndexParam param = IndexParam.builder()
                .fieldName(MilvusConstants.FIELD_SPARSE)
                .indexName(MilvusConstants.INDEX_SPARSE)
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25)
                .build();
        client.createIndex(CreateIndexReq.builder()
                .collectionName(collection)
                .indexParams(List.of(param))
                .sync(true)
                .build());
        log.info("[Milvus] BM25 稀疏索引创建完成：{}.{} SPARSE_INVERTED_INDEX/BM25",
                collection, MilvusConstants.FIELD_SPARSE);
    }

    /**
     * 标量字段索引用 INVERTED（Milvus 2.6 对等值/范围过滤最通用的选择）。
     *
     * <p>失败**只 WARN 不中断**：标量索引是性能优化，不是可用性前提。
     * 缺了它检索仍然正确，只是大集合下过滤变慢。
     */
    private void ensureScalarIndexes(String collection) {
        if (!properties.isScalarIndexEnabled()) {
            log.info("[Milvus] app.milvus.scalar-index-enabled=false，跳过标量索引");
            return;
        }
        Set<String> names = collectionIndexNames(collection);
        for (String field : SCALAR_FILTER_FIELDS) {
            String indexName = MilvusConstants.INDEX_SCALAR_PREFIX + field;
            try {
                if (names.contains(indexName)) {
                    log.info("[Milvus] 标量索引已存在：{}", indexName);
                    continue;
                }
                IndexParam param = IndexParam.builder()
                        .fieldName(field)
                        .indexName(indexName)
                        .indexType(IndexParam.IndexType.INVERTED)
                        .extraParams(Map.of("mmap.enabled", false))
                        .build();
                client.createIndex(CreateIndexReq.builder()
                        .collectionName(collection)
                        .indexParams(List.of(param))
                        .sync(true)
                        .build());
                names.add(indexName);
                log.info("[Milvus] 标量索引创建完成：{}.{} INVERTED", collection, field);
            } catch (Exception ex) {
                log.warn("[Milvus] 标量索引创建异常（已忽略，不影响功能，仅影响过滤性能）：{}.{} → {}",
                        collection, field, rootMessage(ex));
            }
        }
    }

    /** 载入内存，否则检索会报 collection not loaded。 */
    private void load(String collection) {
        client.loadCollection(LoadCollectionReq.builder()
                .collectionName(collection)
                .build());
        log.info("[Milvus] 集合已加载：{}", collection);
    }

    // ==================== 工具 ====================

    private record DescribeCollectionRespFields(List<String> names,
                                                io.milvus.v2.service.collection.response.DescribeCollectionResp raw) {
    }

    private DescribeCollectionRespFields describeFields(String collection) {
        io.milvus.v2.service.collection.response.DescribeCollectionResp resp = client.describeCollection(
                DescribeCollectionReq.builder().collectionName(collection).build());
        if (resp == null || resp.getFieldNames() == null) {
            log.error("[Milvus] describeCollection({}) 未返回字段列表，schema 不合预期，跳过后续步骤", collection);
            return null;
        }
        return new DescribeCollectionRespFields(List.copyOf(resp.getFieldNames()), resp);
    }

    /**
     * 只读校验集合维度。
     *
     * @return true = 维度一致，可以继续；false = 漂移，调用方应停止后续步骤
     */
    private boolean verifyDimension(DescribeCollectionRespFields fields) {
        Integer actual = dimensionOf(fields.raw());
        int expected = properties.getDimension();
        if (actual == null) {
            log.error("[Milvus] 集合里找不到 FloatVector 字段，schema 不合预期，跳过索引检查");
            return false;
        }
        if (actual != expected) {
            log.error("[Milvus] ⚠️ 维度漂移：集合实际 {} 维，配置期望 {} 维。"
                            + "向量写入会全部失败。修复方式（需人工确认，会清空向量）："
                            + "DROP COLLECTION; 然后重启应用自动重建，再对全部文档触发重建索引。",
                    actual, expected);
            return false;
        }
        log.info("[Milvus] 维度校验通过：dim={} metric={}", actual, properties.getMetricType());
        return true;
    }

    /**
     * 集合里的向量总条数（**跨全部分区**）。
     *
     * <p>只用于结构迁移的守卫，因此口径要保守：
     * <b>取不到就返回 -1</b>（调用方据此 fail-closed），而不是返回 0 让调用方以为"安全"。
     */
    private long countAll(String collection) {
        try {
            QueryResp resp = client.query(QueryReq.builder()
                    .collectionName(collection)
                    .filter(MilvusConstants.FIELD_KB_ID + " >= 0")
                    .outputFields(List.of("count(*)"))
                    .consistencyLevel(ConsistencyLevel.BOUNDED)
                    .build());
            if (resp == null || resp.getQueryResults() == null || resp.getQueryResults().isEmpty()) {
                return -1;
            }
            Object value = resp.getQueryResults().getFirst().getEntity().get("count(*)");
            return value instanceof Number n ? n.longValue() : -1;
        } catch (Exception ex) {
            // 旧集合可能未加载 —— 那不是"没有数据"，故不返回 0
            log.warn("[Milvus] 统计集合向量条数失败：{} → {}", collection, rootMessage(ex));
            return -1;
        }
    }

    /**
     * 尽力加载集合（失败只记 debug）。
     *
     * <p>用途只有一个：结构迁移前要**数一数里面有没有向量**，而没加载就数不了。
     * 加载失败不算错误 —— 数不出来时长会返回 -1，守卫据此 fail-closed。
     */
    private void tryLoadQuietly(String collection) {
        try {
            client.loadCollection(LoadCollectionReq.builder().collectionName(collection).build());
        } catch (Exception ex) {
            log.debug("[Milvus] 迁移前预加载集合未成功（继续尝试计数）：{} → {}", collection, rootMessage(ex));
        }
    }

    private Integer dimensionOf(io.milvus.v2.service.collection.response.DescribeCollectionResp resp) {
        CreateCollectionReq.CollectionSchema schema = resp.getCollectionSchema();
        if (schema == null || schema.getFieldSchemaList() == null) {
            return null;
        }
        for (CreateCollectionReq.FieldSchema field : schema.getFieldSchemaList()) {
            if (field.getDataType() == DataType.FloatVector) {
                return field.getDimension();
            }
        }
        return null;
    }

    /**
     * 取集合的**全部索引名**。
     *
     * <p>⚠️ <b>刻意不传 fieldName</b>：Milvus 忽略它（v1/v2/REST 三处同现象 ——
     * 带不带 fieldName 返回的都是整个集合的索引）。见类注释里那次"索引从没建出来过"的事故。
     */
    private Set<String> collectionIndexNames(String collection) {
        try {
            List<String> names = client.listIndexes(ListIndexesReq.builder()
                    .collectionName(collection)
                    .build());
            return names == null ? new LinkedHashSet<>() : new LinkedHashSet<>(names);
        } catch (Exception ex) {
            // "集合还没有任何索引"时 Milvus 返回失败态 —— 这不是错误，是"还没建"
            log.debug("[Milvus] listIndexes({}) 返回异常（视为尚无索引）：{}", collection, rootMessage(ex));
            return new LinkedHashSet<>();
        }
    }

    private String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        List<String> seen = new ArrayList<>();
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            seen.add(cursor.getClass().getSimpleName());
            cursor = cursor.getCause();
        }
        return cursor == ex ? ex.toString() : cursor + " ⟵ " + String.join(" ⟵ ", seen);
    }
}
