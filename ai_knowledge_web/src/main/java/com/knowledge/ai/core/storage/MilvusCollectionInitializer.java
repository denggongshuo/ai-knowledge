package com.knowledge.ai.core.storage;

import com.knowledge.ai.core.constant.MilvusConstants;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.IndexDescription;
import io.milvus.grpc.KeyValuePair;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CollectionSchemaParam;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.index.DescribeIndexParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Milvus 集合初始化（T-010 / 验收门 G0-7）。
 *
 * <p><b>单集合 schema</b>（决策 **D-4 / R-8**）：全部知识库共用
 * {@code knowledge_vectors}，用标量字段做多租户与密级过滤，
 * 而不是"一个知识库一个 collection"（后者在 100+ 知识库时元数据开销爆炸）。
 *
 * <pre>
 *   id            VarChar(64)   主键，= MySQL chunk.id（两边同值，便于溯源与删除）
 *   embedding     FloatVector   1024 维（bge-m3，L2 归一化）—— 锁死，不可原地改
 *   kb_id         Int64         多租户隔离的第一道过滤
 *   doc_id        Int64         按文档过滤（权限收紧时先算 doc_id 集合）
 *   chunk_type    VarChar(32)   块类型（接口字段名 blockType，列名固定 chunk_type）
 *   secret_level  VarChar(16)   密级，按用户密级上限过滤
 *   content       VarChar      分片正文（BM25 / 结果回填，避免一次 MySQL 往返）
 *   title_path    VarChar      标题路径（索引增强：标题路径注入）
 * </pre>
 *
 * <p><b>幂等</b>：启动时按"集合存在？→ 索引存在？"逐级判断，全部存在则只做
 * 只读校验。因此可以随应用反复重启，不会重复建索引、不会丢数据。
 *
 * <p>⚠️ <b>判"索引存在"必须按索引名、不能按字段名</b>：Milvus 的
 * {@code describeIndex(fieldName=…)} 忽略 fieldName，恒返回整个集合的索引列表。
 * 2026-09-17 前的实现正是按字段名判的，于是那个守卫恒为真、四个标量索引
 * <b>从建表起就没建出来过</b>，而日志看上去一切正常（S0 潜伏到 S4-C3 才被探针逮到）。
 * 现在统一走 {@link #collectionIndexNames(String)}，见其 javadoc。
 *
 * <p><b>维度漂移不自动修复</b>：发现集合维度与配置不一致时**只报错不重建**。
 * 集合一旦按旧维度建好就无法原地修改，擅自 drop 会静默毁掉全部分片向量
 * （量级见 《01-示例数据字典》：38,502 条）。修复必须是人显式决定的动作。
 *
 * <p><b>失败不阻断启动</b>：初始化失败只 ERROR 日志 + 健康检查标红，
 * 让"能跑的部分先跑"，由 {@code StorageStartupReporter} 统一汇总（T-008 口径）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(20)
public class MilvusCollectionInitializer implements ApplicationRunner {

    /** 标量过滤字段：建 INVERTED 索引，避免大集合下逐行扫描。 */
    private static final List<String> SCALAR_FILTER_FIELDS = List.of(
            MilvusConstants.FIELD_KB_ID,
            MilvusConstants.FIELD_DOC_ID,
            MilvusConstants.FIELD_CHUNK_TYPE,
            MilvusConstants.FIELD_SECRET_LEVEL);

    /** 向量索引名（唯一一个；建索引时与判存在时都用它）。 */
    private static final String VECTOR_INDEX_NAME = "idx_embedding";

    /** 标量索引名前缀：{@code idx_<字段名>}。 */
    private static final String SCALAR_INDEX_PREFIX = "idx_";

    private final MilvusServiceClient client;

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
                log.info("[Milvus] 集合已存在，跳过创建：{}", collection);
                if (!verifyDimension(collection)) {
                    return;
                }
            } else {
                createCollection(collection);
                log.info("[Milvus] 集合创建完成：{}（dim={} metric={} index={}）",
                        collection, properties.getDimension(), properties.getMetricType(), properties.getIndexType());
            }
            ensureVectorIndex(collection);
            ensureScalarIndexes(collection);
            load(collection);
        } catch (Exception ex) {
            log.error("[Milvus] 集合初始化失败：{} —— 向量相关的 M07 索引构建 / M08 检索问答在本机不可用。"
                    + "请确认 milvus-standalone 容器健康，然后重启应用重试。原因：{}", collection, rootMessage(ex), ex);
        }
    }

    // ==================== 步骤 ====================

    private boolean collectionExists(String collection) {
        R<Boolean> result = client.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(collection).build());
        assertOk(result, "hasCollection");
        return Boolean.TRUE.equals(result.getData());
    }

    private void createCollection(String collection) throws Exception {
        int dim = properties.getDimension();
        int maxLength = 65_535; // Milvus VarChar 上限

        List<FieldType> fields = List.of(
                FieldType.newBuilder()
                        .withName(MilvusConstants.FIELD_ID)
                        .withDescription("分片 id，与 MySQL chunk.id 同值")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(64)
                        .withPrimaryKey(true)
                        .withAutoID(false)
                        .build(),
                FieldType.newBuilder()
                        .withName(MilvusConstants.FIELD_VECTOR)
                        .withDescription("bge-m3 向量，" + dim + " 维 L2 归一化")
                        .withDataType(DataType.FloatVector)
                        .withDimension(dim)
                        .build(),
                FieldType.newBuilder()
                        .withName(MilvusConstants.FIELD_KB_ID)
                        .withDescription("知识库 id（多租户隔离）")
                        .withDataType(DataType.Int64)
                        .build(),
                FieldType.newBuilder()
                        .withName(MilvusConstants.FIELD_DOC_ID)
                        .withDescription("文档 id")
                        .withDataType(DataType.Int64)
                        .build(),
                FieldType.newBuilder()
                        .withName(MilvusConstants.FIELD_CHUNK_TYPE)
                        .withDescription("块类型（接口字段 blockType）")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(32)
                        .build(),
                FieldType.newBuilder()
                        .withName(MilvusConstants.FIELD_SECRET_LEVEL)
                        .withDescription("密级（PUBLIC/INTERNAL/CONFIDENTIAL/SECRET）")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(16)
                        .build(),
                FieldType.newBuilder()
                        .withName(MilvusConstants.FIELD_CONTENT)
                        .withDescription("分片正文（BM25 / 结果回填）")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(maxLength)
                        .build(),
                FieldType.newBuilder()
                        .withName(MilvusConstants.FIELD_TITLE_PATH)
                        .withDescription("标题路径（索引增强）")
                        .withDataType(DataType.VarChar)
                        .withMaxLength(1024)
                        .build());

        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                .withEnableDynamicField(false)
                .withFieldTypes(fields)
                .build();

        CreateCollectionParam param = CreateCollectionParam.newBuilder()
                .withCollectionName(collection)
                .withDescription("RAG 分片向量（单集合 + 标量过滤）")
                .withShardsNum(2)
                .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED)
                .withSchema(schema)
                .build();

        assertOk(client.createCollection(param), "createCollection");
    }

    /**
     * 只读校验集合维度。
     *
     * @return true = 维度一致，可以继续；false = 漂移，调用方应停止后续步骤
     */
    private boolean verifyDimension(String collection) {
        DescribeCollectionResponse response = describe(collection);
        Integer actual = dimensionOf(response);
        int expected = properties.getDimension();
        if (actual == null) {
            log.error("[Milvus] 集合 {} 里找不到 FloatVector 字段，schema 不合预期，跳过索引检查", collection);
            return false;
        }
        if (actual != expected) {
            log.error("[Milvus] ⚠️ 维度漂移：集合 {} 实际 {} 维，配置期望 {} 维。"
                            + "向量写入会全部失败。修复方式（需人工确认，会清空向量）："
                            + "DROP COLLECTION {}; 然后重启应用自动重建，再对全部文档触发重建索引。",
                    collection, actual, expected, collection);
            return false;
        }
        log.info("[Milvus] 维度校验通过：{} dim={} metric={}", collection, actual, properties.getMetricType());
        return true;
    }

    /** 向量字段索引：IVF_FLAT + L2 + nlist。已存在则跳过。 */
    private void ensureVectorIndex(String collection) throws Exception {
        Set<String> names = collectionIndexNames(collection);
        if (names.contains(VECTOR_INDEX_NAME)) {
            log.info("[Milvus] 向量索引已存在：{}", names);
            return;
        }
        CreateIndexParam param = CreateIndexParam.newBuilder()
                .withCollectionName(collection)
                .withFieldName(MilvusConstants.FIELD_VECTOR)
                .withIndexName(VECTOR_INDEX_NAME)
                .withIndexType(IndexType.valueOf(properties.getIndexType()))
                .withMetricType(MetricType.valueOf(properties.getMetricType()))
                .withExtraParam("{\"nlist\":" + properties.getIndexNlist() + "}")
                .withSyncMode(Boolean.TRUE)
                .build();
        assertOk(client.createIndex(param), "createIndex(" + MilvusConstants.FIELD_VECTOR + ")");
        log.info("[Milvus] 向量索引创建完成：{} {}/{} nlist={}",
                collection, properties.getIndexType(), properties.getMetricType(), properties.getIndexNlist());
    }

    /**
     * 标量字段索引用 INVERTED（Milvus 2.6 对等值/范围过滤最通用的选择）。
     *
     * <p>失败**只 WARN 不中断**：标量索引是性能优化，不是可用性前提。
     * 缺了它检索仍然正确，只是大集合下过滤变慢。
     *
     * <p>⚠️ <b>不能用「该字段有没有索引」来判存在</b>（2026-09-17 修的坑）：
     * {@code describeIndex(fieldName=…)} 的 fieldName **被 Milvus 忽略**，恒返回整个集合的
     * 索引列表。若照此判存在，守卫恒为真 → 四个字段全部被静默跳过，
     * <b>索引从建表起就没建出来过、而日志看上去一切正常</b>（S0 起潜伏至 S4-C3）。
     * 现在一律按**集合的索引名集合**判存在，且三条路径（已存在 / 创建成功 / 被拒绝）都留日志。
     */
    private void ensureScalarIndexes(String collection) {
        if (!properties.isScalarIndexEnabled()) {
            log.info("[Milvus] app.milvus.scalar-index-enabled=false，跳过标量索引");
            return;
        }
        Set<String> names = collectionIndexNames(collection);
        for (String field : SCALAR_FILTER_FIELDS) {
            String indexName = SCALAR_INDEX_PREFIX + field;
            try {
                if (names.contains(indexName)) {
                    log.info("[Milvus] 标量索引已存在：{}", indexName);
                    continue;
                }
                CreateIndexParam param = CreateIndexParam.newBuilder()
                        .withCollectionName(collection)
                        .withFieldName(field)
                        .withIndexName(indexName)
                        .withIndexType(IndexType.INVERTED)
                        .withSyncMode(Boolean.TRUE)
                        .build();
                R<?> result = client.createIndex(param);
                if (result.getStatus() != null && result.getStatus() == R.Status.Success.getCode()) {
                    names.add(indexName);
                    log.info("[Milvus] 标量索引创建完成：{}.{} INVERTED", collection, field);
                } else {
                    log.warn("[Milvus] 标量索引创建被拒绝（不影响功能，仅影响过滤性能）：{}.{} → {}",
                            collection, field, result.getMessage());
                }
            } catch (Exception ex) {
                log.warn("[Milvus] 标量索引创建异常（已忽略）：{}.{} → {}", collection, field, rootMessage(ex));
            }
        }
    }

    /** 载入内存，否则检索会报 collection not loaded。 */
    private void load(String collection) {
        R<?> result = client.loadCollection(
                LoadCollectionParam.newBuilder().withCollectionName(collection).build());
        assertOk(result, "loadCollection");
        log.info("[Milvus] 集合已加载：{}", collection);
    }

    // ==================== 工具 ====================

    private DescribeCollectionResponse describe(String collection) {
        R<DescribeCollectionResponse> result = client.describeCollection(
                DescribeCollectionParam.newBuilder().withCollectionName(collection).build());
        assertOk(result, "describeCollection");
        return result.getData();
    }

    /**
     * 取集合的**全部索引名**。
     *
     * <p>⚠️ <b>刻意不传 {@code fieldName}</b>：Milvus 忽略它（实测 REST 与 gRPC 同现象 ——
     * 带不带 fieldName 返回的都是整个集合的索引）。传了会得到一个"看起来像按字段查、
     * 其实不是"的结果，`ensureScalarIndexes` 正是因此从 S0 起就没建出过标量索引。
     *
     * <p>「集合还没有任何索引」时 Milvus 返回失败态 —— 这不是错误，是"还没建"，故降级 debug。
     */
    private Set<String> collectionIndexNames(String collection) {
        R<io.milvus.grpc.DescribeIndexResponse> result = client.describeIndex(
                DescribeIndexParam.newBuilder()
                        .withCollectionName(collection)
                        .build());
        Integer status = result.getStatus();
        if (status == null || status != R.Status.Success.getCode()) {
            log.debug("[Milvus] describeIndex({}) 返回：{}", collection, result.getMessage());
            return Set.of();
        }
        if (result.getData() == null) {
            return Set.of();
        }
        return result.getData().getIndexDescriptionsList().stream()
                .map(IndexDescription::getIndexName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Integer dimensionOf(DescribeCollectionResponse response) {
        for (FieldSchema field : response.getSchema().getFieldsList()) {
            if (field.getDataType() != DataType.FloatVector) {
                continue;
            }
            for (KeyValuePair param : field.getTypeParamsList()) {
                if ("dim".equals(param.getKey())) {
                    try {
                        return Integer.valueOf(param.getValue());
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private void assertOk(R<?> result, String operation) {
        Integer status = result.getStatus();
        if (status != null && status == R.Status.Success.getCode()) {
            return;
        }
        String message = result.getMessage();
        if (message == null && result.getException() != null) {
            message = result.getException().toString();
        }
        throw new IllegalStateException("Milvus " + operation + " 失败（status=" + status + "）：" + message);
    }

    private String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor == ex ? ex.toString() : ex + " ⟵ " + cursor;
    }
}
