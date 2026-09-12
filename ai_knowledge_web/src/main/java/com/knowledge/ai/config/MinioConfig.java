package com.knowledge.ai.config;

import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端装配（T-008）。
 *
 * <p>只负责"造客户端"；bucket 的创建在 {@code MinioBucketInitializer}，
 * 连通性判定在 {@code StorageConnectivityService}。职责不混。
 *
 * <p>注意区分两个 MinIO：<b>本 Bean 连的是业务对象存储</b>
 * （归档用户上传的原始文档）；Milvus 自己也连一个 MinIO 存 segment 数据，
 * 那是 Milvus 容器内部的事，与本配置无关。
 */
@Slf4j
@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        log.info("[MinIO] 客户端装配：{}", endpoint);
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
