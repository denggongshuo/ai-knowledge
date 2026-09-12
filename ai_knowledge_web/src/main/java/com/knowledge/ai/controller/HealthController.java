package com.knowledge.ai.controller;

import com.knowledge.ai.core.ai.ModelProperties;
import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.storage.MilvusProperties;
import com.knowledge.ai.core.storage.StorageConnectivityService;
import com.knowledge.ai.core.storage.StorageOverview;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查（T-008 / T-009）。
 *
 * <p>与 {@code /actuator/health} 的分工：
 * <ul>
 *   <li>{@code /actuator/health} —— 给运维与探针用，标准格式、可被 Prometheus 抓。</li>
 *   <li>{@code /api/health/storage} —— 给**前端联调与 spike 取证**用，
 *       走统一响应体 {@code {code,message,data,traceId}}，能在浏览器里直接看结论。</li>
 * </ul>
 *
 * <p>路径在 {@code SecurityConfig.PUBLIC_PATHS} 的 {@code /api/health/**} 白名单内，
 * 无需登录 —— 但**不含任何凭据**（只暴露库名/集合名/维度/模型名这类定位信息，
 * API Key 一律只报"已配置/未配置"，见 G0-8）。
 */
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final StorageConnectivityService connectivity;

    private final MilvusProperties milvusProperties;

    private final ModelProperties modelProperties;

    private final Environment environment;

    @Value("${spring.application.name:ai_knowledge_web}")
    private String applicationName;

    /** 五存储连通性（验收门 G0-5 的接口级证据）。 */
    @GetMapping("/storage")
    public ApiResponse<StorageOverview> storage() {
        return ApiResponse.ok(connectivity.overview());
    }

    /**
     * 应用自身的存活探针。
     *
     * <p>不查任何外部依赖 —— 依赖状态看 {@code /storage}。
     * 语义区分很重要：「进程活着」与「依赖可用」是两件事。
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> ping() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("application", applicationName);
        body.put("profiles", environment.getActiveProfiles().length == 0
                ? new String[]{"default"}
                : environment.getActiveProfiles());
        body.put("time", OffsetDateTime.now().toString());
        return ApiResponse.ok(body);
    }

    /**
     * 生效的关键配置快照（**不含密钥**）。
     *
     * <p>用途：spike 取证时证明"配置真的生效了"
     * （G0-7 维度 1024 / G0-8 配置外置 / G0-6 模型端点），免得靠翻日志或读代码猜。
     *
     * <p>模型段只暴露 base-url、模型名与 key 的有无 —— 这是"能定位问题"与
     * "泄露凭据"之间的分界线：知道打了哪家、用的哪个模型就足以排查，
     * 而 key 本身对排查没有增量价值。
     */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config() {
        Map<String, Object> milvus = new LinkedHashMap<>();
        milvus.put("host", milvusProperties.getHost());
        milvus.put("port", milvusProperties.getPort());
        milvus.put("collection", milvusProperties.getCollection());
        milvus.put("dimension", milvusProperties.getDimension());
        milvus.put("metricType", milvusProperties.getMetricType());
        milvus.put("indexType", milvusProperties.getIndexType());
        milvus.put("indexNlist", milvusProperties.getIndexNlist());
        milvus.put("authEnabled", milvusProperties.getUsername() != null && !milvusProperties.getUsername().isBlank());

        // 四个端点与 ModelType 枚举同序：CHAT / EMBEDDING / RERANK / VL
        Map<String, Object> models = new LinkedHashMap<>();
        models.put("chat", modelSnapshot(modelProperties.getChat()));
        models.put("embedding", modelSnapshot(modelProperties.getEmbedding()));
        models.put("rerank", modelSnapshot(modelProperties.getRerank()));
        models.put("vl", modelSnapshot(modelProperties.getVl()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("milvus", milvus);
        body.put("models", models);
        body.put("minioBucket", environment.getProperty("minio.bucket"));
        body.put("minioEndpoint", environment.getProperty("minio.endpoint"));
        body.put("redisHost", environment.getProperty("spring.data.redis.host")
                + ":" + environment.getProperty("spring.data.redis.port"));
        body.put("corsAllowedOrigins", environment.getProperty("app.cors.allowed-origins"));
        return ApiResponse.ok(body);
    }

    /** 单个模型端点的脱敏快照。 */
    private Map<String, Object> modelSnapshot(ModelProperties.Endpoint endpoint) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("baseUrl", endpoint.getBaseUrl());
        item.put("model", endpoint.getModel());
        item.put("apiKeyConfigured", endpoint.getApiKey() != null && !endpoint.getApiKey().isBlank());
        return item;
    }
}
