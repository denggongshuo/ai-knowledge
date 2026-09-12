package com.knowledge.ai.core.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * MinIO bucket 初始化（T-008）。
 *
 * <p>幂等：bucket 存在则跳过。放启动期做，是因为"上传第一个文档时才发现 bucket 不存在"
 * 会让故障暴露在业务路径上 —— 而且那时用户已经等了一会。
 *
 * <p>失败只 WARN：MinIO 挂了不该挡住整站启动（登录、问答仍能跑），
 * 由 {@link StorageStartupReporter} 汇总 + 健康检查标红。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(30)
public class MinioBucketInitializer implements ApplicationRunner {

    private final MinioClient minioClient;

    @Value("${minio.bucket:rag-documents}")
    private String bucket;

    @Override
    public void run(ApplicationArguments args) {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (exists) {
                log.info("[MinIO] bucket 已存在：{}", bucket);
                return;
            }
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("[MinIO] bucket 创建完成（私有策略）：{}", bucket);
        } catch (Exception ex) {
            log.warn("[MinIO] bucket 初始化失败：{} —— 文档上传/下载在本机不可用。原因：{}", bucket, ex.toString());
        }
    }
}
