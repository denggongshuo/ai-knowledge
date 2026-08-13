package com.ai.knowledge.utils.pdf;

import java.util.List;

/**
 * PDF 内容块：流水线处理的最小单元。
 * <p>读取阶段把每一页切分为若干块：文本块保存段落文字，表格块保存结构化二维数据，
 * 避免表格在后续文本清洗中被打散成普通文本。
 */
public sealed interface PdfContentBlock {

    /** 所属页码（从 1 开始） */
    int pageNumber();

    /** 文本块 */
    record TextBlock(int pageNumber, String text) implements PdfContentBlock {
    }

    /** 表格块：行 -> 列，归档阶段渲染为 markdown 表格 */
    record TableBlock(int pageNumber, List<List<String>> rows) implements PdfContentBlock {
    }
}
