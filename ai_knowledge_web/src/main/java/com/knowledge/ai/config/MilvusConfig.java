package com.knowledge.ai.config;

import com.knowledge.ai.core.storage.MilvusProperties;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Milvus 客户端装配（T-008；2026-09-17 · `S5-M07-A` **从 v1 整体迁到 v2**）。
 *
 * <h3>为什么全项目只保留一个连 Milvus 的客户端</h3>
 * <p>两套连接并存时，「连的是不是同一台、看到的是不是同一份 schema」会变成每次排查都要
 * 重新确认的事。所以本批**不是**"再挂一个 v2 客户端"，而是**换掉 v1**：
 * {@code MilvusServiceClient} 已从本类移除，写入/分区/建集合三条路径全部走
 * {@link MilvusClientV2}。
 *
 * <h3>为什么这次非换不可（v1 做不到的事）</h3>
 * <p>`FR-RET-03` §3.2 把 BM25 定为必需品、`G5-1` 要求「两路召回并行」，而 BM25 在
 * Milvus 里必须由**稀疏向量字段 + BM25 函数 + 带 analyzer 的文本字段**三者共同承载。
 * 步 0 探针（2026-09-17）实测：
 * <ul>
 *   <li>v1 的 {@code io.milvus.param.**} 里**没有任何** function / analyzer / sparse 类
 *       —— 它<b>表达不了</b>这套 schema；</li>
 *   <li>v2 的 {@code io.milvus.v2.client.MilvusClientV2} <b>就在同一个 jar 里</b>
 *       （{@code milvus-sdk-java 2.6.21}）→ <b>不引新依赖</b>。</li>
 * </ul>
 * <p>顺带拿到 {@code hybridSearch}（多路召回一次往返）与 {@code queryIterator}，
 * 正是 M07 检索侧要用的。
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
    public MilvusClientV2 milvusClientV2(MilvusProperties properties) {
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                // v2 用 uri 而不是 host/port 两段 —— 拼一处，别让调用方各拼各的
                .uri("http://" + properties.getHost() + ":" + properties.getPort())
                .connectTimeoutMs(properties.getConnectTimeoutMs())
                .rpcDeadlineMs(properties.getRpcDeadlineMs());

        if (StringUtils.hasText(properties.getDatabase())) {
            builder.dbName(properties.getDatabase());
        }
        if (StringUtils.hasText(properties.getUsername())) {
            // 本地 standalone 默认免认证，空用户名时不要塞凭据
            builder.username(properties.getUsername()).password(properties.getPassword());
        }

        log.info("[Milvus] 客户端装配（v2）：{}:{} db={} collection={} dim={} metric={}",
                properties.getHost(), properties.getPort(), properties.getDatabase(),
                properties.getCollection(), properties.getDimension(), properties.getMetricType());

        return new MilvusClientV2(builder.build());
    }
}
