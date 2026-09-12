package com.knowledge.ai.config;

import com.knowledge.ai.core.storage.StorageConnectivityService;
import com.knowledge.ai.core.storage.StorageKind;
import com.knowledge.ai.core.storage.StorageStatus;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 五存储健康检查装配（T-008 / 验收门 G0-5）。
 *
 * <p><b>为什么自定义而不是用 Spring Boot 自带的</b>：
 * 自带贡献者把 MySQL 记作 {@code db}、Neo4j 记作 {@code neo4j}，
 * 且 Milvus / MinIO 没有现成的。这里统一成 <b>容器名口径</b>：
 * {@code mysql / redis / milvus / minio / neo4j}，与 docker-compose 的
 * {@code kb-*} 服务一一对应 —— 运维看到 {@code minio: DOWN} 就知道该去
 * 重启哪个容器，不用再查映射表。
 *
 * <p>自带的三个（{@code db} / {@code redis} / {@code neo4j}）已在
 * {@code application.yml} 里用 {@code management.health.*.enabled=false} 关掉，
 * 否则会出现两套重复指标。
 *
 * <p>探测逻辑全部复用 {@link StorageConnectivityService}，
 * 与启动自检报告、{@code /api/health/storage} 是**同一份实现** ——
 * 三处结论永远一致。
 */
@Configuration
public class StorageHealthConfig {

    @Bean
    public HealthIndicator mysqlHealthIndicator(StorageConnectivityService connectivity) {
        return indicator(connectivity, StorageKind.MYSQL);
    }

    @Bean
    public HealthIndicator redisHealthIndicator(StorageConnectivityService connectivity) {
        return indicator(connectivity, StorageKind.REDIS);
    }

    @Bean
    public HealthIndicator milvusHealthIndicator(StorageConnectivityService connectivity) {
        return indicator(connectivity, StorageKind.MILVUS);
    }

    @Bean
    public HealthIndicator minioHealthIndicator(StorageConnectivityService connectivity) {
        return indicator(connectivity, StorageKind.MINIO);
    }

    @Bean
    public HealthIndicator neo4jHealthIndicator(StorageConnectivityService connectivity) {
        return indicator(connectivity, StorageKind.NEO4J);
    }

    private HealthIndicator indicator(StorageConnectivityService connectivity, StorageKind kind) {
        return () -> {
            StorageStatus status = connectivity.probe(kind);
            Health.Builder builder = status.up() ? Health.up() : Health.down();
            return builder
                    .withDetail("store", status.store())
                    .withDetail("role", status.role())
                    .withDetail("costMs", status.costMs())
                    .withDetail("detail", status.detail())
                    .build();
        };
    }
}
