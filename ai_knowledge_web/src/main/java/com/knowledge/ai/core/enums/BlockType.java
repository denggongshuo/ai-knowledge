package com.knowledge.ai.core.enums;

import lombok.Getter;

/**
 * 分片块类型（T-012 核心口径，**勿改回**）。
 *
 * <p>命名口径（《10-接口约定汇总》§14）：
 * <ul>
 *   <li><b>接口字段名</b> = {@code blockType}</li>
 *   <li><b>入库列名 / Milvus 标量字段名</b> = {@code chunk_type}</li>
 *   <li>❌ 废弃第三种写法 {@code chunkType}</li>
 * </ul>
 *
 * <p>取值与前端 {@code ChunkInfo['blockType']} 逐字一致，8 种。
 * UI 上归为 4 组：正文（PARAGRAPH/HEADING/CODE/LIST）、表格（TABLE）、图片（IMAGE）、摘要（SUMMARY）。
 * {@code HYPOTHESIS_QA} 是索引增强阶段的产物（HyQ 假设性问题），不在正文分组里。
 */
@Getter
public enum BlockType {

    PARAGRAPH("正文", BlockGroup.TEXT),
    HEADING("标题", BlockGroup.TEXT),
    TABLE("表格", BlockGroup.TABLE),
    CODE("代码", BlockGroup.TEXT),
    LIST("列表", BlockGroup.TEXT),
    SUMMARY("摘要", BlockGroup.SUMMARY),
    HYPOTHESIS_QA("假设问题", BlockGroup.ENHANCE),
    IMAGE("图片", BlockGroup.IMAGE),
    ;

    /** UI 上的 4 个分组口径。 */
    public enum BlockGroup {
        TEXT, TABLE, IMAGE, SUMMARY, ENHANCE
    }

    /** 接口字段名（出参 key）。 */
    public static final String FIELD_NAME = "blockType";

    /** 入库列名 / Milvus 标量字段名。 */
    public static final String COLUMN_NAME = "chunk_type";

    private final String label;

    private final BlockGroup group;

    BlockType(String label, BlockGroup group) {
        this.label = label;
        this.group = group;
    }

    /**
     * 是否计入文档的 {@code chunkCount}。
     *
     * <p>口径（《01-示例数据字典》）：<b>只计顶层块</b> —— 正文 + 表格父块 + 图片 + 摘要；
     * **表格子块不计**（单列 {@code tableSubBlocks}）。
     */
    public boolean countsInChunkTotal() {
        return this != HYPOTHESIS_QA;
    }

    /** 是否可以拥有子块（目前只有表格父块有子块）。 */
    public boolean canHaveChildren() {
        return this == TABLE;
    }
}
