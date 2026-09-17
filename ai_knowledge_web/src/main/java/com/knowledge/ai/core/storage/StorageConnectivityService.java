package com.knowledge.ai.core.storage;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

/**
 * 五存储连通性自检（T-008 / 验收门 G0-5）。
 *
 * <p><b>为什么要有这个类</b>：五个依赖里任何一个没起来，
 * 症状都是"某个功能莫名报错"。把探测集中到一处，
 * 启动日志一次说清、{@code /actuator/health} 随时可查、spike 报告直接贴结论。
 *
 * <p><b>设计取舍</b>
 * <ol>
 *   <li><b>一律不抛异常</b>：{@link #probe(StorageKind)} 把任何 {@link Throwable}
 *       收敛成 {@link StorageStatus#down}，调用方（健康检查 / 启动报告）无需 try-catch。
 *       探测本身失败不该让探测框架跟着炸。</li>
 *   <li><b>客户端用 {@link ObjectProvider} 取</b>：MySQL/Redis/Neo4j 的客户端来自
 *       Spring Boot 自动配置，MinIO/Milvus 是自建 Bean。用 ObjectProvider 后，
 *       万一某个自动配置被排除，这里会给出"容器里没有该 Bean"的**明确原因**，
 *       而不是启动时一个 NoSuchBeanDefinitionException 堆栈。</li>
 *   <li><b>探测要轻</b>：只做"能不能连上 + 语义是否可用"，不做全表扫描。
 *       Verdict 判据见各 {@code probeXxx} 方法注释。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageConnectivityService {

    /** 五存储的固定枚举顺序，探测与展示共用。 */
    private static final StorageKind[] ALL = StorageKind.values();

    private final ObjectProvider<DataSource> dataSourceProvider;

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    private final ObjectProvider<MilvusClientV2> milvusProvider;

    private final ObjectProvider<MinioClient> minioProvider;

    private final ObjectProvider<Driver> neo4jProvider;

    private final MilvusProperties milvusProperties;

    @Value("${minio.bucket:rag-documents}")
    private String minioBucket;

    @Value("${minio.endpoint:}")
    private String minioEndpoint;

    @Value("${spring.neo4j.uri:}")
    private String neo4jUri;

    // ==================== 对外入口 ====================

    /** 探测全部五个存储，顺序与 {@link StorageKind} 一致。 */
    public List<StorageStatus> probeAll() {
        return Arrays.stream(ALL).map(this::probe).toList();
    }

    /** 探测单个存储。**永不抛异常**。 */
    public StorageStatus probe(StorageKind kind) {
        long start = System.nanoTime();
        try {
            String detail = switch (kind) {
                case MYSQL -> probeMysql();
                case REDIS -> probeRedis();
                case MILVUS -> probeMilvus();
                case MINIO -> probeMinio();
                case NEO4J -> probeNeo4j();
            };
            return StorageStatus.up(kind, costMs(start), detail);
        } catch (Throwable ex) {
            log.warn("存储自检失败 [{}]：{}", kind.getDisplayName(), ex.toString());
            return StorageStatus.down(kind, costMs(start), describe(ex));
        }
    }

    public StorageOverview overview() {
        return StorageOverview.of(probeAll());
    }

    // ==================== 各存储探测 ====================

    /**
     * MySQL：能拿到连接 + 能执行 {@code SELECT 1} + 能读到目标库名。
     *
     * <p>只 ping 不查业务表：表可能还没建（T-011 之前），拿"表不存在"判故障是误报。
     */
    private String probeMysql() throws Exception {
        DataSource dataSource = require(dataSourceProvider, StorageKind.MYSQL);
        try (Connection connection = dataSource.getConnection()) {
            String catalog = connection.getCatalog();
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(3);
                try (ResultSet rs = statement.executeQuery("SELECT 1")) {
                    rs.next();
                }
            }
            var meta = connection.getMetaData();
            String version = meta.getDatabaseProductVersion();
            if (version != null && version.contains("-")) {
                version = version.substring(0, version.indexOf('-'));
            }
            return "schema=" + catalog + " · " + meta.getDatabaseProductName() + " " + version;
        }
    }

    /**
     * Redis：{@code PING} 有回包即通过。
     *
     * <p>不校验密码是否正确 —— 密码错时 lettuce 在 PING 就会抛
     * {@code NOAUTH/WRONGPASS}，所以 PING 已经覆盖了认证失败。
     */
    private String probeRedis() {
        StringRedisTemplate template = require(redisProvider, StorageKind.REDIS);
        String pong = template.execute((RedisCallback<String>) RedisConnection::ping);
        return "PING=" + (pong == null ? "(空响应)" : pong);
    }

    /**
     * Milvus：集合存在 + 能描述 + **向量维度与配置一致**。
     *
     * <p>维度检查是硬判据：集合一旦按旧维度建好就无法原地修改，
     * 维度不符时写入会全部失败（**D-4**：曾把 bge-m3 误配成 1536）。
     * 这里提前判成"不可用"，并在 detail 里说清期望值/实际值。
     */
    private String probeMilvus() {
        MilvusClientV2 client = require(milvusProvider, StorageKind.MILVUS);
        String collection = milvusProperties.getCollection();

        // v2 客户端失败即抛 —— 不再有"status 非 0 而方法照常返回"的中间态，
        // 所以这里没有 assertMilvusOk 那一层：能走到下一行就是成功了。
        boolean exists = Boolean.TRUE.equals(client.hasCollection(
                HasCollectionReq.builder().collectionName(collection).build()));
        if (!exists) {
            throw new IllegalStateException("集合 " + collection
                    + " 不存在（初始化未执行或已被删除）→ 向量检索不可用");
        }

        DescribeCollectionResp response = client.describeCollection(
                DescribeCollectionReq.builder().collectionName(collection).build());

        Integer actualDim = vectorDimensionOf(response);
        int fields = response.getFieldNames() == null ? 0 : response.getFieldNames().size();
        String summary = "collection=" + collection + " fields=" + fields
                + " dim=" + actualDim + " metric=" + milvusProperties.getMetricType();

        if (actualDim == null) {
            throw new IllegalStateException(summary + " → 未找到 FloatVector 字段，schema 不合预期");
        }
        if (actualDim != milvusProperties.getDimension()) {
            throw new IllegalStateException(summary + " → 维度漂移：配置期望 "
                    + milvusProperties.getDimension() + "，集合实际 " + actualDim
                    + "；改维度必须 drop 集合后全库重建（不能原地改）");
        }
        return summary;
    }

    /**
     * MinIO：bucket 存在即通过。
     *
     * <p>不在这里"顺手创建"，创建是 {@link MinioBucketInitializer} 的职责 ——
     * 探测方法只读，不产生副作用。
     */
    private String probeMinio() throws Exception {
        MinioClient client = require(minioProvider, StorageKind.MINIO);
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(minioBucket).build());
        if (!exists) {
            throw new IllegalStateException("bucket 不存在：" + minioBucket
                    + "（初始化未执行或凭据权限不足）→ 文档上传/下载不可用");
        }
        return "bucket=" + minioBucket + " · endpoint=" + minioEndpoint;
    }

    /** Neo4j：{@code verifyConnectivity()} 通过 + 能跑一次真查询拿到版本号。 */
    private String probeNeo4j() {
        Driver driver = require(neo4jProvider, StorageKind.NEO4J);
        // neo4j-java-driver 6.x 的 verifyConnectivity() 返回 void（失败即抛），
        // 所以版本号要另开一个 session 查 dbms.components。
        driver.verifyConnectivity();
        String version = neo4jVersion(driver);
        return (neo4jUri == null || neo4jUri.isBlank() ? "bolt" : neo4jUri)
                + " · Neo4j " + (version == null ? "(版本未取到，连接正常)" : version);
    }

    /** 查服务端版本；拿不到不算故障（权限不足时某些部署会禁用该过程）。 */
    private String neo4jVersion(Driver driver) {
        try (Session session = driver.session()) {
            Record record = session
                    .run("CALL dbms.components() YIELD name, versions, edition "
                            + "RETURN name + ' ' + versions[0] + ' (' + edition + ')' AS component")
                    .single();
            return record.get("component").asString(null);
        } catch (Exception ex) {
            log.debug("Neo4j 版本查询失败（不影响连通性判定）：{}", ex.toString());
            return null;
        }
    }

    // ==================== 工具 ====================

    private <T> T require(ObjectProvider<T> provider, StorageKind kind) {
        T value = provider.getIfAvailable();
        if (value == null) {
            throw new IllegalStateException("Spring 容器中没有 " + kind.getDisplayName()
                    + " 客户端 Bean（自动配置被排除，或对应 starter 依赖缺失）");
        }
        return value;
    }

    /**
     * 从集合 schema 里读出向量字段的 dim。
     *
     * <p>⚠️ 这里**刻意不校验 Milvus 调用的返回值**：v2 客户端失败即抛异常，
     * 不存在"status 非 0 而方法照常返回"的中间态 —— 当年那个 {@code assertMilvusOk}
     * 是 v1 形态的产物，随本次迁移一并删掉（留着会让人以为还有中间态要防）。
     */
    private Integer vectorDimensionOf(DescribeCollectionResp response) {
        CreateCollectionReq.CollectionSchema schema = response.getCollectionSchema();
        if (schema == null || schema.getFieldSchemaList() == null) {
            return null;
        }
        for (CreateCollectionReq.FieldSchema field : schema.getFieldSchemaList()) {
            if (field.getDataType() == DataType.FloatVector) {
                return field.getDimension();
            }
        }
        return null;
    }

    private long costMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * 把异常压成一行可读文本，并把最深层原因带上。
     *
     * <p>连接类异常常常是"外层包一层、真因在最里面"
     * （如 {@code MyBatisSystemException → CommunicationsException → ConnectException}），
     * 只取最外层会看不懂。
     */
    private String describe(Throwable ex) {
        StringBuilder text = new StringBuilder(128);
        text.append(ex.getClass().getSimpleName());
        if (ex.getMessage() != null && !ex.getMessage().isBlank()) {
            text.append(": ").append(oneLine(ex.getMessage()));
        }
        Throwable cause = ex.getCause();
        int depth = 0;
        while (cause != null && depth < 4) {
            text.append(" ⟵ ").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                text.append(": ").append(oneLine(cause.getMessage()));
            }
            cause = cause.getCause();
            depth++;
        }
        return text.toString();
    }

    private String oneLine(String raw) {
        String trimmed = raw.strip().replaceAll("\\s+", " ");
        return trimmed.length() > 300 ? trimmed.substring(0, 300) + "…" : trimmed;
    }
}
