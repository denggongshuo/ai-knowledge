package com.knowledge.ai.core.storage;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.knowledge.ai.core.ai.ModelProperties;
import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.model.ApiResponse;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.DescribeCollectionResponse;
import io.milvus.grpc.FieldSchema;
import io.milvus.grpc.KeyValuePair;
import io.milvus.param.collection.DescribeCollectionParam;
import io.milvus.param.index.DescribeIndexParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S0 验收自动化（T-008 / T-010 · 门 G0-2 / G0-5 / G0-7 / G0-8）。
 *
 * <p><b>为什么用测试而不是"起服务然后 curl"</b>
 * <ol>
 *   <li>把"五存储连通 / Milvus schema / 统一响应体"变成**可重复的断言**，
 *       而不是一次性手工观察 —— 换机器、换同事、改配置后都能重跑一遍。</li>
 *   <li>{@code @SpringBootTest} 会真实启动上下文，因此
 *       {@link MilvusCollectionInitializer}、{@link MinioBucketInitializer}、
 *       {@link StorageStartupReporter} **都会被触发**，它们的日志就是启动期自检的取证。</li>
 *   <li>用 {@code RANDOM_PORT} + 真 HTTP 调 {@code /api/health/storage}，
 *       顺带把 traceId 过滤器、Security 白名单、统一响应体三件事一起验了
 *       （比 MockMvc 更接近真实链路，且不依赖 Boot 4 里已挪走的 MockMvc 测试自动配置）。</li>
 * </ol>
 *
 * <p>前提：{@code docker compose up -d} 的五个容器健康。
 * 任一不可用会以"**哪个存储 + 什么原因**"的形式失败，而不是泛泛的断言错误。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S0VerificationTest {

    @Autowired
    private StorageConnectivityService connectivity;

    @Autowired
    private MilvusProperties milvusProperties;

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private ModelProperties modelProperties;

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("G0-5：MySQL / Redis / Milvus / MinIO / Neo4j 五存储全部连通")
    void fiveStoresAreReachable() {
        List<StorageStatus> statuses = connectivity.probeAll();

        // 这段输出就是"启动自检报告"的等价物：失败时直接告诉你在哪一步、什么原因
        StringBuilder report = new StringBuilder("\n===== 五存储连通性（S0 / G0-5）=====\n");
        statuses.forEach(s -> report.append(String.format("  [%-4s] %-7s %-6dms %s%n",
                s.up() ? "OK" : "FAIL", s.store(), s.costMs(), s.detail())));
        System.out.print(report);

        assertEquals(5, statuses.size(), "自检项数应为 5（五存储）");
        List<String> down = statuses.stream()
                .filter(s -> !s.up())
                .map(s -> s.store() + " → " + s.detail())
                .toList();
        assertTrue(down.isEmpty(), "存在不可用的存储：\n  " + String.join("\n  ", down));
    }

    @Test
    @DisplayName("G0-7：Milvus 集合建成且维度锁死 1024 / L2，标量字段齐备")
    void milvusSchemaIsLocked() {
        DescribeCollectionResponse response = milvusClient.describeCollection(
                        DescribeCollectionParam.newBuilder()
                                .withCollectionName(milvusProperties.getCollection())
                                .build())
                .getData();
        assertNotNull(response, "describeCollection 未返回数据");

        Map<String, FieldSchema> fields = response.getSchema().getFieldsList().stream()
                .collect(Collectors.toMap(FieldSchema::getName, f -> f));

        // 契约要求的四个标量过滤字段（R-8 / G0-7）
        for (String scalar : List.of(MilvusConstants.FIELD_KB_ID, MilvusConstants.FIELD_DOC_ID,
                MilvusConstants.FIELD_CHUNK_TYPE, MilvusConstants.FIELD_SECRET_LEVEL)) {
            assertTrue(fields.containsKey(scalar), "缺少标量字段：" + scalar);
        }

        FieldSchema id = fields.get(MilvusConstants.FIELD_ID);
        assertNotNull(id, "缺少主键字段 " + MilvusConstants.FIELD_ID);
        assertTrue(id.getIsPrimaryKey(), "主键字段必须标记为 primary key");
        assertEquals(DataType.VarChar, id.getDataType(),
                "主键应为 VarChar（= MySQL chunk.id，非自增，便于两侧同值溯源）");

        FieldSchema vector = fields.get(MilvusConstants.FIELD_VECTOR);
        assertNotNull(vector, "缺少向量字段 " + MilvusConstants.FIELD_VECTOR);
        assertEquals(DataType.FloatVector, vector.getDataType(), "向量字段必须是 FloatVector");

        // 维度是整个 S0 最贵的不可逆决策（D-4）：错一次要重建 38,502 条向量
        int dim = vectorDimension(vector);
        assertEquals(MilvusConstants.DIMENSION, dim,
                "向量维度必须锁死 1024（bge-m3）—— 不匹配会导致所有写入失败");
        assertEquals(1024, milvusProperties.getDimension(), "配置维度应为 1024");
        assertEquals("L2", milvusProperties.getMetricType(), "度量应为 L2（bge-m3 输出已 L2 归一化）");

        System.out.printf("%n===== Milvus schema（S0 / G0-7）=====%n"
                        + "  collection=%s fields=%d dim=%d metric=%s index=%s nlist=%d%n",
                milvusProperties.getCollection(), response.getSchema().getFieldsCount(),
                dim, milvusProperties.getMetricType(), milvusProperties.getIndexType(),
                milvusProperties.getIndexNlist());
    }

    @Test
    @DisplayName("G0-7：四个标量过滤字段的 INVERTED 索引真的建出来了")
    void scalarFilterIndexesExist() {
        // ⚠️ 这条断言是 2026-09-17（S4-M05-C3 step 0）补的，它本该在 S0 就存在：
        //    上面那条只验了「标量**字段**齐备」，没验「标量**索引**存在」——
        //    而 `MilvusCollectionInitializer.ensureScalarIndexes` 的旧守卫
        //    （按 fieldName 调 describeIndex，被 Milvus 忽略）恒为真，
        //    于是四个 INVERTED 索引从建表起就没建出来过，且静默无日志。
        //    字段在、索引不在，恰好落在两条断言的缝隙里。
        Set<String> names = milvusClient.describeIndex(
                        DescribeIndexParam.newBuilder()
                                .withCollectionName(milvusProperties.getCollection())
                                .build())
                .getData()
                .getIndexDescriptionsList().stream()
                .map(io.milvus.grpc.IndexDescription::getIndexName)
                .collect(Collectors.toSet());

        assertTrue(names.contains("idx_embedding"), "缺少向量索引：" + names);
        for (String scalar : List.of(MilvusConstants.FIELD_KB_ID, MilvusConstants.FIELD_DOC_ID,
                MilvusConstants.FIELD_CHUNK_TYPE, MilvusConstants.FIELD_SECRET_LEVEL)) {
            assertTrue(names.contains("idx_" + scalar),
                    "缺少标量过滤索引 idx_" + scalar + "（大集合下过滤会退化为逐行扫描）—— 实际索引：" + names);
        }
    }

    @Test
    @DisplayName("G0-2 + G0-5：/api/health/storage 走统一响应体并汇总五存储")
    void storageHealthEndpointUsesUnifiedEnvelope() {
        String body = RestClient.create("http://127.0.0.1:" + port)
                .get()
                .uri("/api/health/storage")
                .retrieve()
                .body(String.class);

        assertNotNull(body, "响应体为空");
        DocumentContext json = JsonPath.parse(body);
        assertEquals(ApiResponse.SUCCESS_CODE, json.read("$.code", Integer.class), "成功响应 code 必须为 0");
        assertFalse(json.read("$.traceId", String.class).isBlank(), "响应体必须带 traceId（G0-3 的一半）");
        assertEquals(5, json.read("$.data.total", Integer.class), "应汇总五个存储");
        assertEquals(5, json.read("$.data.upCount", Integer.class), "应五个全部连通");
        assertTrue(json.read("$.data.allUp", Boolean.class), "allUp 应为 true");
        assertEquals(5, json.read("$.data.items.length()", Integer.class), "items 应有五条明细");

        // G0-2 严格契约：顶层字段**恰好**这四个，多一个都算违约。
        // 反例：ApiResponse.isSuccess() 曾被 Jackson 当成属性序列化成 "success":true，已用 @JsonIgnore 修掉；
        // 这条断言就是防止它（或任何新的 isXxx()/getXxx() 辅助方法）再次漏到线上。
        Map<String, Object> root = JsonPath.parse(body).read("$", Map.class);
        assertEquals(List.of("code", "data", "message", "traceId"),
                root.keySet().stream().sorted().toList(),
                "响应体顶层必须恰好是 {code, message, data, traceId} 四字段");

        System.out.printf("%n===== /api/health/storage（S0 / G0-5）=====%n  %s%n", body);
    }

    @Test
    @DisplayName("G0-7：/api/health/config 暴露生效的维度与度量（配置外置的证据）")
    void effectiveConfigIsResolvedFromYaml() {
        assertEquals("knowledge_vectors", milvusProperties.getCollection(), "集合名应来自配置");
        assertEquals(1024, milvusProperties.getDimension(), "维度应来自配置（不是 1536）");
        assertEquals("L2", milvusProperties.getMetricType(), "度量应来自配置（不是 COSINE）");
        assertTrue(milvusProperties.isInitializeSchema(), "启动应自动初始化 schema");

        List<StorageStatus> all = connectivity.probeAll();
        StorageStatus milvus = all.stream()
                .filter(s -> "Milvus".equals(s.store()))
                .findFirst()
                .orElseThrow();
        assertTrue(milvus.detail().contains("dim=1024"), "健康明细应体现 1024 维，实际：" + milvus.detail());
        assertTrue(milvus.detail().contains("metric=L2"), "健康明细应体现 L2，实际：" + milvus.detail());
    }

    @Test
    @DisplayName("G0-5：/actuator/health 逐个反映五个存储依赖")
    void actuatorHealthReflectsFiveStores() {
        // 用 /actuator/health 而不是自建端点：这是运维/探针/Prometheus 实际会看的那一个，
        // 也是 G0-5 的原话要求。自建的 /api/health/storage 是给人看的，两者都要在。
        String body = RestClient.create("http://127.0.0.1:" + port)
                .get()
                .uri("/actuator/health")
                .retrieve()
                .body(String.class);

        assertNotNull(body, "actuator/health 响应体为空");
        DocumentContext json = JsonPath.parse(body);
        assertEquals("UP", json.read("$.status", String.class), "整体健康状态应为 UP");

        // 五个贡献者必须各自出现，而不是被合并成一个笼统的 db 检查。
        // 注意：Spring Boot 自带的 db/redis/neo4j 贡献者已在 application.yml 里关闭
        //      （management.health.*.enabled=false），统一由 StorageHealthConfig 提供，
        //      免得同一依赖出现两套口径。
        for (String component : List.of("mysql", "redis", "milvus", "minio", "neo4j")) {
            assertEquals("UP", json.read("$.components." + component + ".status", String.class),
                    "actuator 组件 " + component + " 应为 UP");
        }

        System.out.printf("%n===== /actuator/health（S0 / G0-5）=====%n  components=%s%n",
                json.read("$.components", Map.class).keySet());
    }

    @Test
    @DisplayName("G0-8：/api/health/config 暴露模型端点，但不泄露密钥")
    void configEndpointExposesModelsWithoutLeakingSecrets() {
        String body = RestClient.create("http://127.0.0.1:" + port)
                .get()
                .uri("/api/health/config")
                .retrieve()
                .body(String.class);

        assertNotNull(body, "响应体为空");
        DocumentContext json = JsonPath.parse(body);

        // 四个模型端点与 ModelType 枚举同序出现（CHAT/EMBEDDING/RERANK/VL）
        for (String type : List.of("chat", "embedding", "rerank", "vl")) {
            assertNotNull(json.read("$.data.models." + type, Map.class), "缺少模型段：" + type);
        }

        // 值确实来自配置，而不是某处写死的默认值
        assertEquals(modelProperties.getEmbedding().getModel(),
                json.read("$.data.models.embedding.model", String.class),
                "embedding 模型名应与配置一致");
        assertEquals(modelProperties.getChat().getBaseUrl(),
                json.read("$.data.models.chat.baseUrl", String.class),
                "chat base-url 应与配置一致");

        // 本用例真正的价值在这里：key 的**值**绝不能出现在响应里。
        // 只让接口说"已配置"，不让它说"配的是什么"（G0-8）。
        String[] secrets = {
                modelProperties.getChat().getApiKey(),
                modelProperties.getEmbedding().getApiKey(),
                modelProperties.getRerank().getApiKey(),
                modelProperties.getVl().getApiKey()
        };
        int configured = 0;
        for (String secret : secrets) {
            if (secret == null || secret.isBlank()) {
                continue;
            }
            configured++;
            assertFalse(body.contains(secret),
                    "响应体泄露了 API Key（前 6 位 " + secret.substring(0, 6) + "…）");
        }
        assertTrue(configured > 0,
                "一个 key 都没配，本用例形同虚设 —— 请先补齐 .env 再跑 G0-8");

        System.out.printf("%n===== /api/health/config 模型段（S0 / G0-8）=====%n  %s%n"
                        + "  已配置 %d 个 key，响应体中均未出现%n",
                json.read("$.data.models", Map.class), configured);
    }

    private int vectorDimension(FieldSchema vector) {
        Optional<KeyValuePair> dim = vector.getTypeParamsList().stream()
                .filter(p -> "dim".equals(p.getKey()))
                .findFirst();
        assertTrue(dim.isPresent(), "向量字段缺少 dim 参数");
        return Integer.parseInt(dim.get().getValue());
    }
}
