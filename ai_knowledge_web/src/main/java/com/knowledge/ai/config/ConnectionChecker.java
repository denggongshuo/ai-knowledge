package com.knowledge.ai.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.CheckHealthResponse;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class ConnectionChecker implements ApplicationRunner {

    private final DataSource dataSource;
    private final Driver neo4jDriver;
    private final StringRedisTemplate redisTemplate;
    private final MinioClient minioClient;

    @Value("${spring.ai.vectorstore.milvus.client.host}")
    private String milvusHost;

    @Value("${spring.ai.vectorstore.milvus.client.port}")
    private int milvusPort;

    @Value("${minio.bucket}")
    private String minioBucket;

    public ConnectionChecker(DataSource dataSource,
                             Driver neo4jDriver,
                             StringRedisTemplate redisTemplate,
                             MinioClient minioClient) {
        this.dataSource = dataSource;
        this.neo4jDriver = neo4jDriver;
        this.redisTemplate = redisTemplate;
        this.minioClient = minioClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("========================================");
        log.info("  开始检查基础设施连接...");
        log.info("========================================");

        checkMySQL();
        checkNeo4j();
        checkRedis();
        checkMilvus();
        checkMinIO();

        log.info("========================================");
        log.info("  连接检查完成");
        log.info("========================================");
    }

    private void checkMySQL() {
        try (Connection conn = dataSource.getConnection()) {
            String dbName = conn.getCatalog();
            log.info("[OK] MySQL 连接成功 -> {}", dbName);
        } catch (Exception e) {
            log.error("[FAIL] MySQL 连接失败: {}", e.getMessage());
        }
    }

    private void checkNeo4j() {
        try {
            neo4jDriver.verifyConnectivity();
            log.info("[OK] Neo4j 连接成功");
        } catch (Exception e) {
            log.error("[FAIL] Neo4j 连接失败: {}", e.getMessage());
        }
    }

    private void checkRedis() {
        RedisConnectionFactory connectionFactory = redisTemplate.getConnectionFactory();
        if (connectionFactory == null) {
            log.error("[FAIL] Redis 连接失败: 未配置 RedisConnectionFactory");
            return;
        }
        try (RedisConnection connection = connectionFactory.getConnection()) {
            log.info("[OK] Redis 连接成功 -> {}", connection.ping());
        } catch (Exception e) {
            log.error("[FAIL] Redis 连接失败: {}", e.getMessage());
        }
    }

    private void checkMilvus() {
        // MilvusServiceClient 未实现 AutoCloseable，不能用 try-with-resources，只能手动 close()
        MilvusServiceClient client = null;
        try {
            client = new MilvusServiceClient(
                    ConnectParam.newBuilder()
                            .withHost(milvusHost)
                            .withPort(milvusPort)
                            .withConnectTimeout(5, TimeUnit.SECONDS)
                            .withRpcDeadline(5, TimeUnit.SECONDS)
                            .build());
            R<CheckHealthResponse> response = client.checkHealth();
            boolean healthy = response.getStatus() != null
                    && response.getStatus() == R.Status.Success.getCode()
                    && response.getData() != null
                    && response.getData().getIsHealthy();
            if (healthy) {
                log.info("[OK] Milvus 连接成功 -> {}:{}", milvusHost, milvusPort);
            } else {
                log.error("[FAIL] Milvus 连接异常: status={}, message={}, reasons={}",
                        response.getStatus(), response.getMessage(),
                        response.getData() == null ? null : response.getData().getReasonsList());
            }
        } catch (Exception e) {
            log.error("[FAIL] Milvus 连接失败: {}", e.getMessage());
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private void checkMinIO() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(minioBucket).build());
            log.info("[OK] MinIO 连接成功 -> Bucket '{}' {}", minioBucket, exists ? "已存在" : "不存在");
        } catch (Exception e) {
            log.error("[FAIL] MinIO 连接失败: {}", e.getMessage());
        }
    }
}
