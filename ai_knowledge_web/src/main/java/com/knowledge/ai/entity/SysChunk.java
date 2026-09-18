package com.knowledge.ai.entity;

import com.knowledge.ai.core.enums.BlockType;
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
 * 分片块（`V14` · S4-M05-C1 建表）—— M05 产出侧与 M07 消费侧的接缝。
 *
 * <h3>与本契约的关系（重要）</h3>
 * <p>本类的**业务字段集必须与 `docs/后端开发文档/10-M05-分片产出契约（D-5）.md` §1.1 逐条一致**，
 * 由 {@code ChunkContractTest} 双向断言。这条要求从 S4-A 说到 S4-C，是 M07（整个 S5）的返工面 ——
 * 契约文档写错了字段名，照着它写代码的人会全线返工，而那时才发现就晚了。
 *
 * <p>「业务字段」= 除审计列（{@link #createdAt}）之外的字段。审计列是"这一行的元数据"，
 * 不是"这个块是什么"，故不进契约清单。
 *
 * <h3>为什么有实体类，业务却全走原生 SQL</h3>
 * <p>与 {@link SysDocument} 同一种情况：实体的价值是让 {@code ddl-auto: validate}
 * 在启动时核表结构（列名/类型/可空性对不上就启动失败，而不是等某次查询报
 * {@code Unknown column}）。写入走原生 SQL，因为父子关系落库要先插父块拿自增 id
 * 再回填子块（见 {@code DocRecordWriter#replaceChunks}）。
 *
 * <h3>一行为一个块，视图不进表</h3>
 * <p>{@link #rawContent} 是**事实，永不改写**；「喂给嵌入模型的文本」是索引期的
 * 纯函数投影（D-5 §1.7 / `O8`）。把多视图拆成多行会破坏父子关系。
 */
@Data
@Entity
@Table(name = "sys_chunk")
public class SysChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属文档（D-5 §1.1 `docId`）。 */
    @Column(name = "doc_id", nullable = false)
    private Long docId;

    /**
     * 所属知识库（D-5 §1.1 `kbId`）。
     *
     * <p>冗余存一份而不是每次 join {@code sys_document}：按库统计分片数与
     * `M07` 的标量过滤都要按它走，join 在大表上是纯浪费。
     */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /**
     * 块类型（D-5 §1.3：**入库列名与 Milvus 标量字段名固定为 {@code chunk_type}**，
     * 接口出参 key 才是 {@code blockType}）。
     *
     * <p>用枚举 + {@code @Enumerated(STRING)} 而不是裸 {@code String}，与
     * {@link SysDocument#getStatus()} 同一条理由：**拼错的值写不进库**。
     * 契约文档把列类型写成 {@code String} 说的是"列是字符串列"，
     * 不排斥应用层用枚举收口取值域。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "chunk_type", length = 32, nullable = false)
    private BlockType chunkType;

    /**
     * 表格子块指向其 {@code TABLE} 父块的 id；**顶层块为 {@code null}**（D-5 §1.2）。
     *
     * <p>⚠️ <b>禁用 {@code 0} 当哨兵</b> —— {@code 0} 不是"无父块"的约定值，
     * 它会与真实的 {@code id = 0} 混淆。判"是不是顶层块"一律用 {@code is null}。
     */
    @Column(name = "parent_id")
    private Long parentId;

    /**
     * HyQ 块的**来源块** id（`T-507` 的挂起半边，`V24`；契约见 D-5 §1.1 / §1.8）。
     *
     * <p>⚠️ 与 {@link #parentId} 是**两件事**，不可合并：
     * 那是**结构上的父子**（表格行属于表格），这是**生成上的来源**（这段正文推出过这条问句）。
     * 合并会当场把 `G4-6` 的断言打成红的 —— 它要求「凡有 {@code parent_id} 的块，
     * 父块必须是 {@code TABLE}」，而 HyQ 的源块是正文。
     *
     * <p>只有 {@code HYPOTHESIS_QA} 有值（章节级 {@code SUMMARY} 指向所属标题块，
     * 是同一套回落口径）。检索侧按它把 HyQ 的内容换成源块正文 ——
     * 问句只用于召回，不用于回答。
     */
    @Column(name = "source_chunk_id")
    private Long sourceChunkId;

    /**
     * 标题路径（D-5 §1.1 `titlePath`）。
     *
     * <p>它**既进 Milvus 标量**（`title_path`，供过滤）**又进索引期投影**（供语义）——
     * 两件事互不替代（D-5 §1.7）。可空：标题之前的块（封面段）没有路径。
     */
    @Column(name = "title_path", length = 512)
    private String titlePath;

    /**
     * 块的原始内容 —— **永不改写**（`O8` 的核心：块存事实，视图是投影）。
     *
     * <p>表格父块 = Markdown 管道表（给大模型读）；表格子块 = 行式文本（给向量检索）；
     * 正文块 = 原文。嵌入文本 = 本字段 + 标题路径的**索引期投影**。
     *
     * <p>用 {@code columnDefinition} 而不是 {@code @Lob}：Hibernate 的 {@code @Lob}
     * 在 MySQL 上默认映射 {@code LONGTEXT}，与迁移里的 {@code MEDIUMTEXT} 不符，
     * {@code validate} 会当场拒绝启动。
     */
    @Column(name = "raw_content", columnDefinition = "MEDIUMTEXT", nullable = false)
    private String rawContent;

    /** 文档内顺序，1-based（回表与详情页按它还原阅读顺序）。 */
    @Column(name = "order_no", nullable = false)
    private Integer orderNo;

    /**
     * 所在页码，1-based（引用卡片显示「第 N 页」）。
     *
     * <p>可空是**格式事实**：TXT/MD 没有页的概念；Tika 路径（docx/xlsx 的 XHTML）
     * 同样无页号，恒为 1。不是缺陷。
     */
    @Column(name = "page_no")
    private Integer pageNo;

    /**
     * 写入时间。**没有 {@code updated_at}** —— 块是整批重写的
     * （重跑解析时按 {@code doc_id} 先删后插），从不单行更新。
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
