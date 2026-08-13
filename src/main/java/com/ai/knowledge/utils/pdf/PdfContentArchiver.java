package com.ai.knowledge.utils.pdf;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 步骤四：内容归档。
 * <ul>
 *   <li>清洗后的内容落地为 markdown：文本块直出、表格块渲染为 markdown 表格、
 *       内嵌图片写入 images/ 目录并在对应页面位置插入引用；</li>
 *   <li>生成 meta.json 记录来源、页数、图片 / 表格 / 分块数量等元数据；</li>
 *   <li>若配置了向量库，则按内容块分块（TokenTextSplitter）后写入 VectorStore，
 *       携带 source / page / type 元数据，供 RAG 检索。</li>
 * </ul>
 */
public class PdfContentArchiver {

    private static final Logger log = LoggerFactory.getLogger(PdfContentArchiver.class);
    private static final DateTimeFormatter DIR_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Path archiveRoot;
    private final VectorStore vectorStore;

    /** 仅归档到磁盘，不写入向量库 */
    public PdfContentArchiver(Path archiveRoot) {
        this(archiveRoot, null);
    }

    public PdfContentArchiver(Path archiveRoot, VectorStore vectorStore) {
        this.archiveRoot = archiveRoot;
        this.vectorStore = vectorStore;
    }

    public PdfPipelineResult archive(String sourceName, List<PdfContentBlock> blocks,
                                     List<PdfExtractedImage> images, int totalPages) throws IOException {
        String dirName = sanitize(sourceName) + "_" + LocalDateTime.now().format(DIR_STAMP);
        Path dir = archiveRoot.resolve(dirName);
        Path imageDir = dir.resolve("images");
        Files.createDirectories(imageDir);

        // 1. 内嵌图片写入磁盘
        List<String> imageFiles = writeImages(images, imageDir);

        // 2. 清洗后内容渲染为 markdown
        String markdown = renderMarkdown(blocks, images);
        Path markdownFile = dir.resolve("content.md");
        Files.writeString(markdownFile, markdown, StandardCharsets.UTF_8);

        // 3. 分块写入向量库（可选）
        int chunkCount = storeChunks(sourceName, blocks);

        // 4. 元数据文件
        long tableCount = blocks.stream().filter(b -> b instanceof PdfContentBlock.TableBlock).count();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", sourceName);
        meta.put("totalPages", totalPages);
        meta.put("processedAt", LocalDateTime.now().toString());
        meta.put("blockCount", blocks.size());
        meta.put("tableCount", tableCount);
        meta.put("imageCount", imageFiles.size());
        meta.put("chunkCount", chunkCount);
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(dir.resolve("meta.json").toFile(), meta);

        log.info("内容归档完成：{}（内容块 {} / 表格 {} / 图片 {} / 分块 {}）",
                dir, blocks.size(), tableCount, imageFiles.size(), chunkCount);
        return new PdfPipelineResult(dir, markdownFile, totalPages,
                blocks.size(), (int) tableCount, imageFiles.size(), chunkCount);
    }

    /** 图片统一写为 PNG，单张失败不影响整体归档 */
    private List<String> writeImages(List<PdfExtractedImage> images, Path imageDir) {
        List<String> written = new ArrayList<>();
        for (PdfExtractedImage image : images) {
            try {
                Path file = imageDir.resolve(image.name() + ".png");
                if (ImageIO.write(image.image(), "png", file.toFile())) {
                    written.add(image.name() + ".png");
                } else {
                    log.warn("图片 {} 无可用编码器，跳过", image.name());
                }
            } catch (IOException e) {
                log.warn("图片 {} 写入磁盘失败：{}", image.name(), e.getMessage());
            }
        }
        return written;
    }

    /** 按阅读顺序渲染 markdown，图片引用插在其所属页内容之后 */
    private String renderMarkdown(List<PdfContentBlock> blocks, List<PdfExtractedImage> images) {
        Map<Integer, List<PdfExtractedImage>> imagesByPage = new HashMap<>();
        for (PdfExtractedImage image : images) {
            imagesByPage.computeIfAbsent(image.pageNumber(), k -> new ArrayList<>()).add(image);
        }

        StringBuilder sb = new StringBuilder();
        Integer currentPage = null;
        for (PdfContentBlock block : blocks) {
            if (currentPage != null && block.pageNumber() != currentPage) {
                appendImages(sb, imagesByPage.remove(currentPage));
            }
            currentPage = block.pageNumber();
            switch (block) {
                case PdfContentBlock.TextBlock text -> sb.append(text.text()).append("\n\n");
                case PdfContentBlock.TableBlock table -> sb.append(renderTable(table.rows())).append('\n');
            }
        }
        if (currentPage != null) {
            appendImages(sb, imagesByPage.remove(currentPage));
        }
        // 没有任何文本块、只有图片的页面，引用追加到末尾
        imagesByPage.values().forEach(list -> appendImages(sb, list));
        return sb.toString().strip() + "\n";
    }

    private void appendImages(StringBuilder sb, List<PdfExtractedImage> images) {
        if (images == null || images.isEmpty()) {
            return;
        }
        for (PdfExtractedImage image : images) {
            sb.append("![").append(image.name()).append("](images/")
                    .append(image.name()).append(".png)\n\n");
        }
    }

    /** 二维行数据渲染为 markdown 表格，首行作为表头 */
    static String renderTable(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            sb.append('|');
            for (int c = 0; c < columns; c++) {
                String cell = c < row.size() ? escapeCell(row.get(c)) : "";
                sb.append(' ').append(cell).append(" |");
            }
            sb.append('\n');
            if (i == 0) {
                sb.append('|').append(" --- |".repeat(columns)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String escapeCell(String cell) {
        return cell.replace("|", "\\|").replace("\n", " ");
    }

    /** 内容分块后写入向量库，返回分块数量；未配置向量库时返回 0 */
    private int storeChunks(String sourceName, List<PdfContentBlock> blocks) {
        if (vectorStore == null) {
            return 0;
        }
        List<Document> documents = new ArrayList<>();
        for (PdfContentBlock block : blocks) {
            String text = switch (block) {
                case PdfContentBlock.TextBlock t -> t.text();
                case PdfContentBlock.TableBlock t -> renderTable(t.rows());
            };
            if (text.isBlank()) {
                continue;
            }
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", sourceName);
            metadata.put("page", block.pageNumber());
            metadata.put("type", block instanceof PdfContentBlock.TableBlock ? "table" : "text");
            documents.add(new Document(text, metadata));
        }
        List<Document> chunks = new TokenTextSplitter().apply(documents);
        vectorStore.add(chunks);
        return chunks.size();
    }

    /** 源文件名净化为目录名 */
    private static String sanitize(String name) {
        String base = name.replaceFirst("\\.[pP][dD][fF]$", "");
        String sanitized = base.replaceAll("[^\\w\\u4e00-\\u9fa5.-]", "_");
        return sanitized.isEmpty() ? "document" : sanitized;
    }
}
