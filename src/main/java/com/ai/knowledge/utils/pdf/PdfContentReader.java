package com.ai.knowledge.utils.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 步骤一：读取文档内容。
 * <ul>
 *   <li>基于 PDFBox 逐页提取文本，按「多列对齐」启发式拆分为文本块 / 表格块；</li>
 *   <li>逐页抽取内嵌图片（跳过图标类小图，减少噪音）；</li>
 *   <li>表格识别为启发式：一行被 2 个以上连续空白 / 制表符分隔视为表格行，
 *       连续 {@code minTableRows} 行以上构成表格块。合并单元格等复杂场景
 *       可在此基础上替换为 tabula-pdf 等专用库。</li>
 * </ul>
 */
public class PdfContentReader {

    private static final Logger log = LoggerFactory.getLogger(PdfContentReader.class);

    /** 表格行的列分隔符：连续 2 个及以上空白或制表符 */
    private static final Pattern COLUMN_SEPARATOR = Pattern.compile("(?:\\t+|\\s{2,})");

    /** 一行至少有多少列才视为表格行 */
    private final int minColumns;
    /** 连续多少行以上才构成一个表格块，不足则退回为普通文本 */
    private final int minTableRows;
    /** 宽或高小于该像素值的图片直接跳过（图标 / logo 类噪音） */
    private final int minImageDimension;

    public PdfContentReader() {
        this(2, 3, 64);
    }

    public PdfContentReader(int minColumns, int minTableRows, int minImageDimension) {
        this.minColumns = minColumns;
        this.minTableRows = minTableRows;
        this.minImageDimension = minImageDimension;
    }

    /** 读取 PDF 文件 */
    public PdfParseResult read(Path pdfFile) throws IOException {
        try (InputStream in = Files.newInputStream(pdfFile)) {
            return read(in);
        }
    }

    /** 读取 PDF 输入流 */
    public PdfParseResult read(InputStream input) throws IOException {
        try (PDDocument document = Loader.loadPDF(input.readAllBytes())) {
            int totalPages = document.getNumberOfPages();
            List<PdfContentBlock> blocks = new ArrayList<>();
            List<PdfExtractedImage> images = new ArrayList<>();

            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= totalPages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                blocks.addAll(splitPage(page, stripper.getText(document)));
                images.addAll(extractImages(page, document.getPage(page - 1)));
            }
            log.info("PDF 读取完成：共 {} 页，内容块 {} 个，内嵌图片 {} 张",
                    totalPages, blocks.size(), images.size());
            return new PdfParseResult(blocks, images, totalPages);
        }
    }

    /** 把一页文本切分为文本块 / 表格块，保持原始阅读顺序 */
    private List<PdfContentBlock> splitPage(int pageNumber, String pageText) {
        List<PdfContentBlock> blocks = new ArrayList<>();
        List<String> textLines = new ArrayList<>();
        List<List<String>> tableRows = new ArrayList<>();

        for (String rawLine : pageText.split("\\r?\\n")) {
            String line = rawLine.stripTrailing();
            List<String> columns = parseColumns(line);
            if (columns != null) {
                tableRows.add(columns);
            } else {
                flushTable(blocks, pageNumber, tableRows, textLines);
                textLines.add(line);
            }
        }
        flushTable(blocks, pageNumber, tableRows, textLines);
        flushText(blocks, pageNumber, textLines);
        return blocks;
    }

    /** 连续表格行数达标则产出表格块，不足则退回为文本行 */
    private void flushTable(List<PdfContentBlock> blocks, int pageNumber,
                            List<List<String>> tableRows, List<String> textLines) {
        if (tableRows.isEmpty()) {
            return;
        }
        if (tableRows.size() >= minTableRows) {
            // 先落盘排在前面的文本，保证阅读顺序
            flushText(blocks, pageNumber, textLines);
            blocks.add(new PdfContentBlock.TableBlock(pageNumber, List.copyOf(tableRows)));
        } else {
            for (List<String> row : tableRows) {
                textLines.add(String.join("  ", row));
            }
        }
        tableRows.clear();
    }

    private void flushText(List<PdfContentBlock> blocks, int pageNumber, List<String> textLines) {
        if (textLines.isEmpty()) {
            return;
        }
        String text = String.join("\n", textLines);
        if (!text.isBlank()) {
            blocks.add(new PdfContentBlock.TextBlock(pageNumber, text));
        }
        textLines.clear();
    }

    /** 判断一行是否为表格行：是则返回各列内容，否则返回 null */
    private List<String> parseColumns(String line) {
        if (line.isBlank()) {
            return null;
        }
        List<String> columns = new ArrayList<>();
        for (String part : COLUMN_SEPARATOR.split(line.strip())) {
            if (!part.isBlank()) {
                columns.add(part.strip());
            }
        }
        return columns.size() >= minColumns ? columns : null;
    }

    /** 提取一页中的内嵌图片 */
    private List<PdfExtractedImage> extractImages(int pageNumber, PDPage page) {
        List<PdfExtractedImage> result = new ArrayList<>();
        PDResources resources = page.getResources();
        if (resources == null) {
            return result;
        }
        int index = 0;
        for (COSName name : resources.getXObjectNames()) {
            try {
                if (!resources.isImageXObject(name)) {
                    continue;
                }
                PDImageXObject imageObject = resources.getXObject(name) instanceof PDImageXObject img
                        ? img : null;
                if (imageObject == null) {
                    continue;
                }
                if (imageObject.getWidth() < minImageDimension
                        || imageObject.getHeight() < minImageDimension) {
                    continue;
                }
                index++;
                String imageName = "p%d_img%d".formatted(pageNumber, index);
                result.add(new PdfExtractedImage(pageNumber, imageName, imageObject.getImage()));
            } catch (IOException e) {
                log.warn("第 {} 页图片 {} 提取失败：{}", pageNumber, name.getName(), e.getMessage());
            }
        }
        return result;
    }
}
