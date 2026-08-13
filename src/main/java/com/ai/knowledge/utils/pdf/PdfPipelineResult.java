package com.ai.knowledge.utils.pdf;

import java.nio.file.Path;

/**
 * PDF 流水线最终产物。
 *
 * @param archiveDir   归档目录（content.md / meta.json / images/）
 * @param markdownFile 清洗后的内容文件
 * @param totalPages   PDF 总页数
 * @param blockCount   清洗去噪后的内容块数量
 * @param tableCount   识别出的表格数量
 * @param imageCount   成功写入磁盘的图片数量
 * @param chunkCount   写入向量库的分块数量（未配置向量库时为 0）
 */
public record PdfPipelineResult(
        Path archiveDir,
        Path markdownFile,
        int totalPages,
        int blockCount,
        int tableCount,
        int imageCount,
        int chunkCount
) {
}
