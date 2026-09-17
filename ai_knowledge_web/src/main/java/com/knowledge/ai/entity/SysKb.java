package com.knowledge.ai.entity;

import com.knowledge.ai.core.enums.SecretLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库（表 {@code sys_kb}）。
 *
 * <p>字段口径对齐 {@code V7__s2_kb_skeleton_and_grants.sql}（骨架 + 授权种子）、
 * {@code V8__s3_kb_owner.sql}（创建人）、{@code V9__s3_kb_governance_and_policies.sql}
 * （治理标签 + 三类策略）、{@code V10__s3_kb_reindex_required.sql}（待重建标记）
 * 与 {@code V15__s4_kb_lifecycle.sql}（切代落点 + 软删）。
 *
 * <p><b>本实体存在的首要作用是让 {@code ddl-auto: validate} 生效</b>：在这之前
 * {@code sys_kb} 没有对应的实体，Hibernate 启动时**根本不看这张表** ——
 * 于是"迁移写了 7 列"与"实体缺 7 列"这类不一致会一直沉默到某次查询报
 * {@code Unknown column}。加了本类之后，实体与 V9 对不上就是**启动即失败**，
 * 这正是 {@code application.yml} 里那句"让表结构与代码对不上在启动时暴露"的意思。
 *
 * <p><b>三件刻意的设计</b>（都是"顺手加就会坏事"的地方）：
 * <ul>
 *   <li><b>{@code customTags} 与三张 {@code *_policy_json} 字段保持 {@code String}</b>：
 *       实体是**存储映射**，JSON 的序列化 / 反序列化归 Service。若在这里挂
 *       {@code AttributeConverter} 声明成 {@code List<String>} / {@code SplitPolicy}，
 *       则每加一个策略字段都要改实体 + 转换器两处，且失败时抛在 Hibernate 内部
 *       （错误信息指向 SQL 而不是业务字段）。读侧的兜底规则见 {@code KbConfigDefaults}。</li>
 *   <li><b>没有 {@code department}（部门全名）字段</b>：与 {@code SysUser} 同一理由 ——
 *       由 {@code deptId} 关联部门树拼出，落库就会在部门改名后漂移。
 *       出参里的部门名在 Service 侧现拼。</li>
 *   <li><b>没有 {@code owner}（创建人姓名）字段</b>：同理，由 {@code ownerId}
 *       关联 {@code sys_user} 取，落库会随改名漂移。</li>
 * </ul>
 *
 * <p><b>{@code ownerId} 可空且无外键</b>（V8 的决策）：演示库里有匹配不到账号的行，
 * 且账号被彻底删除后知识库必须保留 —— 加了外键会让删账号级联失败。
 */
@Data
@Entity
@Table(name = "sys_kb")
public class SysKb {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 库编码，如 {@code kb_prod_doc}。全站唯一（{@code uk_kb_code}），创建后不可改。 */
    @Column(name = "code", length = 64, nullable = false, unique = true)
    private String code;

    @Column(name = "name", length = 128, nullable = false)
    private String name;

    /** 归属部门（数据范围 ② 的计算基准），{@code 0} = 「未分配」虚拟节点。 */
    @Column(name = "dept_id", nullable = false)
    private Long deptId;

    @Column(name = "doc_count", nullable = false)
    private Integer docCount;

    @Column(name = "chunk_count", nullable = false)
    private Integer chunkCount;

    /**
     * 库状态。取值是域内真值 {@code READY / INDEXING}（V7 建表注释、《01》§3），
     * 与前端历史类型的 {@code ACTIVE / BUILDING / PAUSED} **不是一个词表**（B1 计划 §1.3 W1）。
     *
     * <p>B1 的取舍：**保留域内真值**，另出派生字段 {@code statusLabel} 承担展示，
     * 前端改用 label —— 让枚举迁就展示文案会反过来污染检索链路的判定。
     * 故此处不建 enum（枚举一建，就等于承认它只有这两个值，而 S3-B3 的重建索引
     * 还会引入新状态）。
     */
    @Column(name = "status", length = 16, nullable = false)
    private String status;

    /**
     * 配置已改、索引未重建（{@code V10} 新增）。
     *
     * <p>由 {@code T-404}（保存配置）置 <b>1</b>，由 S4 的 {@code T-406}（重建索引）
     * 完成后置回 <b>0</b> —— 本批只负责写，清除在下一批。
     *
     * <p><b>刻意独立于 {@link #status}，不扩它的词表</b>：{@code status} 描述的是
     * "索引**过程**"（{@code READY} / {@code INDEXING}），本列描述的是"配置**条件**"
     * （脏了 / 干净）。两者可以同时成立 —— 正在索引的库又被改了一次配置，就是
     * {@code INDEXING} + {@code reindexRequired=true}。并进一列会让这个组合无法表达。
     *
     * <p>用 {@code boolean} 而不是 {@link Boolean}：列是 {@code NOT NULL DEFAULT 0}，
     * 不存在"未设置"态；用包装类会逼每个消费点去决定 {@code null} 算哪边。
     */
    @Column(name = "reindex_required", nullable = false)
    private boolean reindexRequired;

    /** 创建人。演示数据可能为 {@code NULL}（无对应账号），**无外键**（见类注释）。 */
    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "description", length = 200)
    private String description;

    /**
     * 密级。检索期过滤维度（《11》§2.1 I-9）：用户密级上限低于该值时，本库分片不参与召回。
     * 用 {@code @Enumerated(STRING)} 与 {@code SysUser#status} 同型 —— 它们是同一类
     * "取值受控、要参与判定"的列，不适合像 {@code status} 那样留自由字符串。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "secret_level", length = 16, nullable = false)
    private SecretLevel secretLevel;

    /** 业务域（治理语义列），取值见前端 {@code BUSINESS_DOMAINS}。可为空串（未设置）。 */
    @Column(name = "business_domain", length = 64, nullable = false)
    private String businessDomain;

    /** 自定义标签，JSON 数组字符串；{@code NULL} 表示"没有标签"（不是空数组）。 */
    @Column(name = "custom_tags", columnDefinition = "TEXT")
    private String customTags;

    /** 分片策略，JSON 对象字符串；{@code NULL} 时读侧用 {@code KbConfigDefaults} 兜底。 */
    @Column(name = "split_policy_json", columnDefinition = "TEXT")
    private String splitPolicyJson;

    /** 表格处理策略，JSON 对象字符串；语义同 {@link #splitPolicyJson}。 */
    @Column(name = "table_policy_json", columnDefinition = "TEXT")
    private String tablePolicyJson;

    /** 检索配置，JSON 对象字符串；语义同 {@link #splitPolicyJson}。 */
    @Column(name = "retrieval_config_json", columnDefinition = "TEXT")
    private String retrievalConfigJson;

    /**
     * 当前向量所在的分区**代**（{@code V15} 新增 · {@code T-406}）。
     *
     * <p>Milvus 侧对应分区名 {@code kb{kbId}_g{indexGeneration}}。重建索引的流程是
     * 「写新代分区 → 完成后**一次 UPDATE 本列**完成切换 → 滚动删旧代」，所以
     * **本列就是 D-7 那个"原子切换点"**（见 `V15` 的注释里对 BLOCKER 的说明）。
     *
     * <p>检索侧只查本列指向的分区。⚠️ <b>这一列必须与 Milvus 分区同生共死</b>——
     * 步 0 探针实测：查一个**不存在**的分区，Milvus 会**报错**（65535）而不是
     * 返回空集，所以指向的分区一旦缺失，整个检索请求会炸掉而不是少几条结果。
     */
    @Column(name = "index_generation", nullable = false)
    private Integer indexGeneration;

    /**
     * 软删时间（{@code V15} 新增 · {@code T-407}）。{@code NULL} = 未删。
     *
     * <p>删除知识库是软删进回收站（《03》§领域模型：保留 30 天，可恢复，恢复后需重建索引）。
     * 与 {@link #status} **正交**：一个库可以"正在索引时被删除"，并进 status 会让这个组合无法表达。
     *
     * <p>口径与 {@code SysUser}/{@code SysDocument} 的软删一致（全站统一用 {@code NULL} = 未删）。
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
