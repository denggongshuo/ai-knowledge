package com.ai.knowledge.utils.pdf;

import java.util.List;

/**
 * 读取阶段产物：按阅读顺序排列的内容块 + 文档内嵌图片 + 总页数。
 */
public record PdfParseResult(List<PdfContentBlock> blocks, List<PdfExtractedImage> images, int totalPages) {
}
