package com.knowledge.ai.config;

import com.knowledge.ai.core.storage.MilvusProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * Milvus 客户端装配（T-008）。
 *
 * <p><b>为什么用 v1 的 {@link MilvusServiceClient} 而不是 v2 fluent client</b>：
 * Spring AI 2.0.1 的 {@code MilvusVectorStore.builder(...)} 签名为
 * {@code builder(io.milvus.client.MilvusServiceClient, EmbeddingModel)} ——
 * 它只认 v1 客户端。M07 索引入库、M08 检索都要经 Spring AI 的 VectorStore，
 * 所以全项目统一 v1，**避免以后出现两个连同一台 Milvus 的客户端**。
 *
 * <p>连接参数全部来自 {@link MilvusProperties}（{@code app.milvus.*}），
 * 而 {@code app.milvus.*} 的值来自 {@code .env} 的 {@code MILVUS_*} —— 
 * 换环境只改 {@code .env}。
 *
 * <p>{@code destroyMethod = "close"}：客户端持有 gRPC channel，
 * 容器关闭时显式释放，避免验证/重启时端口与线程残留。
 */
@Slf4j
@Configuration
public class MilvusConfig {

    @Bean(destroyMethod = "close")
    public MilvusServiceClient milvusServiceClient(MilvusProperties properties) {
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(properties.getHost())
                .withPort(properties.getPort())
                .withConnectTimeout(properties.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .withRpcDeadline(properties.getRpcDeadlineMs(), TimeUnit.MILLISECONDS);

        if (StringUtils.hasText(properties.getDatabase())) {
            builder.withDatabaseName(properties.getDatabase());
        }
        if (StringUtils.hasText(properties.getUsername())) {
            // 本地 standalone 默认免认证，空用户名时不要塞空 Authorization 头
            builder.withAuthorization(properties.getUsername(), properties.getPassword());
        }

        log.info("[Milvus] 客户端装配：{}:{} db={} collection={} dim={} metric={}",
                properties.getHost(), properties.getPort(), properties.getDatabase(),
                properties.getCollection(), properties.getDimension(), properties.getMetricType());

        return new MilvusServiceClient(builder.build());
    }
}
