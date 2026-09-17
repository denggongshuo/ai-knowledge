package com.knowledge.ai.entity;

import com.knowledge.ai.core.enums.DocStatus;
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
 * 文档（`V12` · S4-M05-A 建表）。
 *
 * <h3>为什么有实体类，业务却全走原生 SQL</h3>
 * <p>与 {@link SysKb} 同一种情况：实体类的价值是**让 {@code ddl-auto: validate} 在启动时
 * 替我们核表结构**（列名/类型/可空性对不上就启动失败，而不是等某次查询报
 * {@code Unknown column}）。写入仍走原生 SQL —— 因为「建行拿自增 id」这条在
 * Hibernate 的原生更新里拿不到生成键（见 {@code DocRecordWriter#insert} 的说明），
 * 而实体落库又会被 {@code @Transactional} 的代理语义限制（同类自调用不生效）。
 *
 * <h3>字段口径的来源</h3>
 * <p>{@code V12__s4_document_skeleton.sql} 的列注释逐条写了来源；
 * 前端侧对齐 {@code src/types/index.ts} 的 {@code KbDocRow}。
 * ⚠️ 改这里必须同步改 `V12`（已执行的迁移**只增不改** —— 要改就新加一个 `V{n}`）。
 *
 * <h3>本批只用到一部分列</h3>
 * <p>{@code table_total/table_parsed/image_total/image_parsed/chunk_count} 本批恒 0，
 * 由 `T-502`~`T-508`（S4-B/C）填；{@code deleted_at} 由 `T-611` 起用。
 * 一并建列是因为它们同属"这一行文档的事实"，分两次加列会让 `KbDocRow` 的投影
 * 在两个批次里各写一版。
 */
@Data
@Entity
@Table(name = "sys_document")
public class SysDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属知识库（不变量 I-2：文档必属恰好一个库）。 */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /**
     * 上传人。
     *
     * <p>⚠️ <b>它同时是去重键 {@code (kb_id, owner_id, md5)} 的左半边</b>
     * —— 见 `V12` 的 {@code idx_doc_md5} 注释。改这个字段的含义会连带改 T-501 的语义。
     *
     * <p>无外键（同 {@link SysKb#getOwnerId()}）：账号被彻底删除后文档必须保留。
     */
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** 文档标题，**不含扩展名**（与前端 {@code KbDocRow.title} 一致）。 */
    @Column(name = "title", length = 256, nullable = false)
    private String title;

    /** 原始文件名（含扩展名）：下载与打包下载要用。 */
    @Column(name = "file_name", length = 256, nullable = false)
    private String fileName;

    /** 取值 {@code PDF/DOC/DOCX/XLS/XLSX/PPT/PPTX/TXT/MD}（《06》§3.1 的 9 种）。 */
    @Column(name = "type", length = 16, nullable = false)
    private String type;

    /** 字节数；也是 {@code storage-quota.used} 从"全桶扫描"改成 {@code sum} 的前提。 */
    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    /**
     * 内容指纹，32 位小写十六进制。
     *
     * <p>⚠️ 列类型是 {@code CHAR(32)} 而**不是** {@code VARCHAR} —— 定长在这里是有意的
     * （MD5 长度恒定，CHAR 省一个长度字节且比较更快）。Hibernate 的 {@code validate}
     * 会核对列类型，所以这里必须写 {@code columnDefinition}，不能只给 {@code length}。
     */
    @Column(name = "md5", columnDefinition = "CHAR(32)", nullable = false)
    private String md5;

    /** MinIO 对象名，**不存 URL**（存事实不存视图，同 {@code sys_user.avatar}）。 */
    @Column(name = "storage_path", length = 512)
    private String storagePath;

    /**
     * 解析产物对象名（{@code parsed/{kbId}/{docId}/parse.json}，`V13` 新增）。
     *
     * <p>可空：只有解析**成功**才写；{@code UPLOADED/PARSING/FAILED} 三态均为 {@code null}。
     * 与 {@link #storagePath} 同一条纪律 —— 存对象名不存 URL。
     */
    @Column(name = "parse_path", length = 512)
    private String parsePath;

    /**
     * 状态机（《03》§4.2）。
     *
     * <p>用 {@link DocStatus} 枚举 + {@code @Enumerated(STRING)}，与前端
     * {@code DocInfo['status']} 逐字一致 —— 与 {@link SysKb#getStatus()} 的处理**不同**：
     * 那边因为词表与前端分叉而刻意留自由字符串（B1 的 W1），这边词表一致，
     * 用枚举才不会把拼错的状态值悄悄写进库。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private DocStatus status;

    /** 失败的具体原因（`G4-3`：不许只说"失败"两字）。仅 FAILED 时非空。 */
    @Column(name = "error_msg", length = 512)
    private String errorMsg;

    /**
     * 分片数。
     *
     * <p>口径见 {@code BlockType#countsInChunkTotal()}：**只计顶层块**
     * （正文 + 表格父块 + 图片 + 摘要），表格子块单列在 {@code ChunkStat.tableSubBlocks}。
     */
    @Column(name = "chunk_count", nullable = false)
    private Integer chunkCount;

    /** 表格总数（`G4-4`）。 */
    @Column(name = "table_total", nullable = false)
    private Integer tableTotal;

    /**
     * 已识别表格数。
     *
     * <p><b>它与 {@link #status} 必须独立成列</b>（《06》§3.6 本页最重要的设计）：
     * 表格识别失败是**静默故障** —— 状态列显示「已就绪」，表格内容却没进索引。
     * 两行的状态列一模一样，只有质量列才看得出差别。
     */
    @Column(name = "table_parsed", nullable = false)
    private Integer tableParsed;

    /** 图片总数（V2 新增）。 */
    @Column(name = "image_total", nullable = false)
    private Integer imageTotal;

    /** 已理解图片数（VL 描述成功）。 */
    @Column(name = "image_parsed", nullable = false)
    private Integer imageParsed;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 软删位（`T-611` 删除文档走软删进回收站）。
     *
     * <p>{@code NULL} = 未删。`T-616` 彻底删除时才真正清行。
     * 判「有业务数据」与回收站列表都按它过滤 —— 注意 {@code UserAdminService#hasBusinessData}
     * 也必须带这个条件，否则"删干净文档后账号仍不可删"（那里的 javadoc 写了理由）。
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * 这篇文档的向量**写齐了哪一代**（{@code V16} 新增 · {@code T-406} 的断点依据）。
     * {@code 0} = 没有向量；首代从 {@code 1} 起，故 {@code 0} 是不会被真实代占用的哨兵值。
     *
     * <p>与 {@code sys_kb.index_generation}（检索该查哪一代）**不是一回事**：重建期间两者
     * 故意不一致 —— 库还指旧代（检索照旧可用），文档一篇篇变成新代，全部完成才切库那一列。
     *
     * <p>⚠️ 不能用「查 Milvus 里有没有这篇文档的点」代替本列：写入是先删后插，
     * 半截写入会被误判成"已完成"而 skip，留下一篇缺块却标着可检索的文档。
     * 详见 {@code V16} 的注释。
     */
    @Column(name = "index_generation", nullable = false)
    private Integer indexGeneration;
}
