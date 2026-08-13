package com.ai.knowledge.utils.pdf;

import java.awt.image.BufferedImage;

/**
 * 从 PDF 中提取的内嵌图片，先保存在内存中，归档阶段统一写入磁盘。
 */
public record PdfExtractedImage(int pageNumber, String name, BufferedImage image) {
}
