package com.knowledge.ai.core.storage;

/**
 * 系统依赖的五个存储（T-008 / 验收门 G0-5）。
 *
 * <p>这不是"可选依赖清单"，而是**启动自检的登记表**：任何一项不可用，
 * 就有一批功能在运行时必然失败（见每项的 {@link #getRole()}）。
 * 把"哪个存储挂了"在启动日志里一次说清楚，好过业务跑到一半抛
 * {@code Connection refused}。
 *
 * <p>顺序即 {@code /actuator/health} 的展示顺序，也是启动自检报告的打印顺序。
 */
public enum StorageKind {

    /** 业务元数据库。挂了 = 全站不可用（连登录都做不了）。 */
    MYSQL("MySQL", "业务元数据：用户/角色/权限/知识库/文档/审计"),

    /** 缓存与会话。挂了可降级（审计序号退化为进程内计数），但短信码/令牌黑名单会失效。 */
    REDIS("Redis", "缓存 / 短信验证码 / 令牌黑名单 / 上传去重"),

    /** 向量库。挂了 = M07 索引构建与 M08 检索问答不可用。 */
    MILVUS("Milvus", "向量库：knowledge_vectors 单集合（1024 维 / L2）"),

    /** 对象存储。挂了 = 文档无法上传归档、下载预签名失效。 */
    MINIO("MinIO", "对象存储：原始文档归档 + 预签名下载地址"),

    /** 图数据库。挂了可降级为纯向量检索（GraphRAG 增强不可用）。 */
    NEO4J("Neo4j", "知识图谱：实体与关系（GraphRAG 增强）");

    private final String displayName;

    private final String role;

    StorageKind(String displayName, String role) {
        this.displayName = displayName;
        this.role = role;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getRole() {
        return role;
    }
}
