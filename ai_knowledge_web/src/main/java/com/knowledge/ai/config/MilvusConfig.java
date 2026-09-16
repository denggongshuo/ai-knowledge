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
 * 全项目只保留**一个**连 Milvus 的客户端 —— 两套连接并存时，「连的是不是同一台、
 * 看到的是不是同一份 schema」会变成每次排查都要重新确认的事。
 *
 * <p>⚠️ <b>订正（2026-09-17 · S4-M05-C3 步 0.5 探针）</b>：这里原先写
 * 「M07 索引入库、M08 检索都要经 Spring AI 的 VectorStore」。实测 Spring AI 2.0.1 的
 * {@code MilvusVectorStore.Builder} 方法面只有
 * {@code iDFieldName / contentFieldName / metadataFieldName / embeddingFieldName / autoId / initializeSchema} ——
 * **metadata 只能落成"一列"**，无法把 {@code kb_id}/{@code doc_id}/{@code chunk_type}/{@code secret_level}
 * 表达成**四个各自带 INVERTED 索引的标量列**（那是 D-4/R-8 的过滤设计，也是 I-9「先算 doc_id 集合
 * 再作标量过滤」的前提）；而本集合 {@code enableDynamicField=false}，写未声明字段会被 Milvus 直接拒绝。
 * 因此**写入侧走裸 {@code MilvusServiceClient.insert}**（`T-508`）；
 * 检索侧能否改用 VectorStore 属 M07/M08 的口径，**留到 S5 开工时定**
 * （已登记在 {@code ai_knowledge_web/plan/2026-09-17-S4-M05-C3.md} §9）。
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
