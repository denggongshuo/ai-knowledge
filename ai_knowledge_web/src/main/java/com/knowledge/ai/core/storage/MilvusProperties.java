package com.knowledge.ai.core.storage;

import com.knowledge.ai.core.constant.MilvusConstants;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Milvus 连接与集合配置（T-008 / T-010）。
 *
 * <p><b>为什么不再用 {@code spring.ai.vectorstore.milvus.*}</b>：
 * 那套属性的消费者是 Spring AI 的 {@code MilvusVectorStoreAutoConfiguration}，
 * 而本项目把它排除掉了（见 {@code application.yml} 的 {@code spring.autoconfigure.exclude}）——
 * 客户端要由我们显式建，才能做到"连接参数一处定义、schema 与业务共用同一个 client"。
 * 留在 yml 里只会变成一份**没人读的假配置**（曾经因此把维度写成 1536 而无人察觉）。
 *
 * <p>维度与度量**不在这里给自由值**：默认值直接取自 {@link MilvusConstants}，
 * 让"锁死 1024 / L2"这件事只有一个口径（**D-4 / R-8**）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.milvus")
public class MilvusProperties {

    private String host = "127.0.0.1";

    private int port = 19530;

    /** 用户名，空 = 免认证（本地 standalone 默认无鉴权）。 */
    private String username;

    private String password;

    private String database = "default";

    /** 集合名。单集合设计，所有知识库共用（**R-8**）。 */
    private String collection = MilvusConstants.COLLECTION;

    /** 向量维度。**锁死 1024**（bge-m3，L2 归一化）。改它等于全库重建。 */
    private int dimension = MilvusConstants.DIMENSION;

    /** 度量方式。bge-m3 输出已 L2 归一化，用 L2。 */
    private String metricType = MilvusConstants.METRIC_TYPE;

    private String indexType = MilvusConstants.INDEX_TYPE;

    private int indexNlist = MilvusConstants.INDEX_NLIST;

    private int indexNprobe = MilvusConstants.INDEX_NPROBE;

    /** 是否在启动时自动建集合/建索引（幂等）。false 时只做只读校验。 */
    private boolean initializeSchema = true;

    private long connectTimeoutMs = 5_000L;

    private long rpcDeadlineMs = 10_000L;

    /** 是否给标量过滤字段（kb_id/doc_id/chunk_type/secret_level）建 INVERTED 索引。 */
    private boolean scalarIndexEnabled = true;
}
