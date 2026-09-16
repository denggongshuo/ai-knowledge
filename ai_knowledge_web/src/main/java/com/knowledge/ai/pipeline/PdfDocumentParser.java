package com.knowledge.ai.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * PDF 解析器（opendataloader-pdf-core 2.5.0，`T-502` 的 PDF 路径）。
 *
 * <h3>为什么坚持产出 JSON 而不是只拿 markdown</h3>
 * <p>markdown 里的表格是"看起来对齐"的字符串，行列边界已经糊掉了；
 * 而 {@code G4-4}（解析质量）与 `T-504`（表格双表示）需要的是**结构化的 cell 矩阵**
 * —— 《07》§2.2 把它列为"一张表必须拆成的三份"里的第一份。
 * JSON 的 {@code table.rows[].cells[].kids[].content} 是唯一能拿到它的入口
 * （开工探针实测，见 S4-M05-B 计划 §1 样本表）。
 *
 * <h3>为什么标题从 JSON 取而不用 markdown 的 {@code #}</h3>
 * <p>JSON 的 {@code heading level} 是结构化的层级数（1~6），
 * markdown 的 {@code #} 是排版近似 —— 幻灯片型 PDF 会把正文行也标成标题。
 * 层级数是 `T-503` 第一级切分（"标题层级 H1→H3，叶子节点即候选块"）的输入，
 * 用错一层，切分边界就整体漂移。
 *
 * <h3>调用纪律（三条，都是实测踩出来的）</h3>
 * <ol>
 *   <li><b>临时目录 {@code finally} 必删</b>：opendataloader 只吃路径，产物落在
 *       {@code outputFolder}；漏删会让并发上传时磁盘以"每篇文档一份产物"的速度漏；</li>
 *   <li><b>图片输出必须关</b>（{@code IMAGE_OUTPUT_OFF}）：21.8MB 那类图多的手册
 *       开了会把产物撑到几百 MB（`T-0S1` §二原话）；</li>
 *   <li><b>全局串行</b>（见 {@link #LOCK}）：API 是静态入口（含 {@code shutdown()}），
 *       线程安全性未验证 —— 宁可排队，不要"偶发跨文档串内容"这种没法复现的错。</li>
 * </ol>
 */
@Slf4j
public class PdfDocumentParser {

    /**
     * opendataloader 的调用锁 —— **全局串行**的落点。
     *
     * <p>{@code AsyncConfig} 的任务池是 core=4/max=8；没有这把锁，四个解析任务
     * 会同时走进这个静态 API。S9 的吞吐优化若证实线程安全，去掉它即可（计划 §3.4 假设 5）。
     */
    private static final Object LOCK = new Object();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 产物文件名的后缀（opendataloader 按 {@code <源名>.json/.md} 落盘，源名带中文时后缀仍固定）。 */
    private static final String JSON_SUFFIX = ".json";

    /**
     * 解析一份 PDF。
     *
     * @param pdfPath 源文件路径（**必须是磁盘上的真实文件** —— API 只吃路径不吃流）
     */
    public static ParsedDocument parse(Path pdfPath) throws IOException {
        Path outDir = Files.createTempDirectory("kb-parse-");
        try {
            Config config = new Config();
            config.setOutputFolder(outDir.toString());
            config.setGenerateMarkdown(true);
            config.setGenerateJSON(true);
            config.setGenerateText(false);
            config.setGenerateHtml(false);
            config.setImageOutput(Config.IMAGE_OUTPUT_OFF);
            // 表格方法保持 DEFAULT（T-0S1 §一-3：DEFAULT 与 CLUSTER 输出逐字节一致，不为 cluster 加复杂度）
            config.setTableMethod(Config.TABLE_METHOD_DEFAULT);
            // 多行单元格压平会让行列错位（T-0S1 §三），所以必须保留换行
            config.setKeepLineBreaks(true);

            synchronized (LOCK) {
                OpenDataLoaderPDF.processFile(pdfPath.toAbsolutePath().toString(), config);
            }

            Path json = findFirst(outDir, JSON_SUFFIX);
            if (json == null) {
                // 连 JSON 都没产出：要么文件损坏要么被杀。给可读原因，不让下游猜
                throw new ParseException("PDF 解析未产出任何结构化结果，文件可能已损坏；请确认文件能正常打开后重新上传");
            }
            return fromJson(MAPPER.readTree(Files.readString(json, StandardCharsets.UTF_8)));
        } catch (ParseException ex) {
            throw ex;
        } catch (Exception ex) {
            // 底层异常（PDFBox 的运行时炸、IO 错）对外不可读 —— 包一层，原始异常进日志与 cause
            throw ParseException.of("PDF 解析失败：" + readableCause(ex)
                    + "。请确认文件未加密且能正常打开，然后重新上传", ex);
        } finally {
            deleteRecursively(outDir);
        }
    }

    /**
     * 从 opendataloader 的 JSON 元素树构造产物。
     *
     * <p>元素树的形状（探针实测）：顶层 {@code kids[]} 是文档级元素序列，
     * {@code type} 取 {@code heading/paragraph/list/table/image/caption}；
     * 表格的 {@code rows[].cells[].kids[].content} 才是格子文本
     * （cell 自身没有 content 字段）。
     */
    static ParsedDocument fromJson(JsonNode root) {
        String title = textOr(root.get("title"), "");

        List<ParsedDocument.Heading> headings = new ArrayList<>();
        List<ParsedDocument.ParsedTable> tables = new ArrayList<>();
        List<String> paragraphs = new ArrayList<>();
        List<ParsedDocument.DocumentIssue> issues = new ArrayList<>();
        int imageCount = 0;
        int pages = intOr(root.get("number of pages"), 0);

        JsonNode kids = root.get("kids");
        if (kids != null && kids.isArray()) {
            for (JsonNode el : kids) {
                String type = textOr(el.get("type"), "");
                int pageNo = intOr(el.get("page number"), 0);
                switch (type) {
                    case "heading" -> {
                        String text = textOr(el.get("content"), "").trim();
                        if (!text.isEmpty()) {
                            // "Doctitle" 之类的非数字层级归 1；数字层级夹到 1~6
                            int level = switch (textOr(el.get("heading level"), "1")) {
                                case "2" -> 2;
                                case "3" -> 3;
                                case "4" -> 4;
                                case "5" -> 5;
                                case "6" -> 6;
                                default -> 1;
                            };
                            headings.add(new ParsedDocument.Heading(level, text, pageNo));
                        }
                    }
                    case "table" -> tables.add(toTable(textOr(el.get("title"), title), pageNo, el));
                    case "image" -> imageCount++;
                    case "paragraph" -> {
                        String text = textOr(el.get("content"), "").trim();
                        if (!text.isEmpty()) {
                            paragraphs.add(text);
                        }
                    }
                    case "list" -> {
                        // 列表的 items 各自是一条 content，整表按一个段落计
                        String joined = joinListItems(el);
                        if (!joined.isBlank()) {
                            paragraphs.add(joined);
                        }
                    }
                    default -> {
                        // caption / 其它：题注若挂着表格，会在 table 侧用 "title" 承接；
                        // 独立题注不进正文（它是"关于某图的说明"，不是可检索的正文段落）
                    }
                }
            }
        }

        // ---- 「无文本层」的逐页问题（P0，T-0S1 §六-1）----
        // 一行一页：15 页的扫描件就是 15 行 —— 「第几页」是 G4-11 点名的字段，
        // 聚成一行会把它丢掉。TextLayerGuard 仍会整篇判失败（可执行的 error_msg），
        // 这里的逐页行是"哪几页有问题"的定位面 —— 两条证据指向同一件事。
        int chars = paragraphs.stream().mapToInt(String::length).sum()
                + headings.stream().mapToInt(h -> h.text().length()).sum()
                + tables.stream().mapToInt(PdfDocumentParser::tableCharCount).sum();
        if (chars <= 0) {
            for (int p = 1; p <= Math.max(pages, 1); p++) {
                issues.add(new ParsedDocument.DocumentIssue(p,
                        "该页未提取到任何可复制文本（疑似整页扫描图），内容无法参与检索"));
            }
        }
        // ---- 检出结构但抽不到内容的表格（G4-4 的"静默故障"正面）----
        // 结构在（rows/cols 都有）、格子全空 —— 正是"识别不出内容的真表格"，
        // 必须让人看见，而不是被 status 的成功掩盖。
        for (ParsedDocument.ParsedTable t : tables) {
            if (!t.layoutArtifact() && !t.hasContent()) {
                issues.add(new ParsedDocument.DocumentIssue(t.pageNo(),
                        "第 " + t.pageNo() + " 页的表格检出了结构（" + t.rowCount() + " 行 × "
                                + t.colCount() + " 列）但未提取到内容，该表无法参与检索；"
                                + "疑似扫描件中的表格，请补录文本或启用 OCR 后重建索引"));
            }
        }

        String markdown = buildMarkdown(title, headings, paragraphs, tables);
        return new ParsedDocument("PDF", Math.max(pages, 1), chars, markdown,
                headings, tables, paragraphs.size(), imageCount, issues);
    }

    /** {@code table} 元素 → cell 矩阵。格子在 {@code cells[].kids[].content} 里逐段拼。 */
    private static ParsedDocument.ParsedTable toTable(String caption, int pageNo, JsonNode table) {
        List<List<String>> rows = new ArrayList<>();
        JsonNode rowsNode = table.get("rows");
        if (rowsNode != null && rowsNode.isArray()) {
            for (JsonNode row : rowsNode) {
                List<String> cells = new ArrayList<>();
                JsonNode cellsNode = row.get("cells");
                if (cellsNode != null && cellsNode.isArray()) {
                    for (JsonNode cell : cellsNode) {
                        cells.add(cellText(cell));
                    }
                }
                rows.add(cells);
            }
        }
        return new ParsedDocument.ParsedTable(caption, pageNo, rows);
    }

    private static String cellText(JsonNode cell) {
        StringBuilder sb = new StringBuilder();
        JsonNode kids = cell.get("kids");
        if (kids != null && kids.isArray()) {
            for (JsonNode kid : kids) {
                String t = textOr(kid.get("content"), "");
                if (!t.isBlank()) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(t.trim());
                }
            }
        }
        return sb.toString();
    }

    private static int tableCharCount(ParsedDocument.ParsedTable t) {
        return t.rows().stream()
                .flatMap(List::stream)
                .mapToInt(String::length)
                .sum();
    }

    private static String joinListItems(JsonNode list) {
        StringBuilder sb = new StringBuilder();
        JsonNode items = list.get("list items");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                String t = textOr(item.get("content"), "").trim();
                if (!t.isEmpty()) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append("- ").append(t);
                }
            }
        }
        return sb.toString();
    }

    /** 统一形态的 markdown：标题 + 段落 + 表格（按元素出现顺序的近似）。 */
    private static String buildMarkdown(String title, List<ParsedDocument.Heading> headings,
                                        List<String> paragraphs, List<ParsedDocument.ParsedTable> tables) {
        StringBuilder sb = new StringBuilder();
        if (!title.isBlank()) {
            sb.append("# ").append(title.trim()).append("\n\n");
        }
        for (ParsedDocument.Heading h : headings) {
            sb.append("#".repeat(Math.max(1, Math.min(6, h.level()))))
                    .append(' ').append(h.text()).append("\n\n");
        }
        for (String p : paragraphs) {
            sb.append(p).append("\n\n");
        }
        for (ParsedDocument.ParsedTable t : tables) {
            String md = t.toMarkdown();
            if (!md.isBlank()) {
                sb.append(md).append('\n');
            }
        }
        return sb.toString();
    }

    private static String readableCause(Exception ex) {
        String m = ex.getMessage();
        if (m == null || m.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    private static Path findFirst(Path dir, String suffix) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))
                    .max(Comparator.comparingLong(PdfDocumentParser::sizeOf))
                    .orElse(null);
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1L;
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // 删不掉只能留日志：临时目录泄漏不该让解析本身失败
                    log.warn("解析临时目录删除失败：{}", p);
                }
            });
        } catch (IOException ex) {
            log.warn("解析临时目录遍历失败：{} err={}", dir, ex.getMessage());
        }
    }

    private static String textOr(JsonNode node, String fallback) {
        return node == null || node.isNull() ? fallback : node.asText();
    }

    private static int intOr(JsonNode node, int fallback) {
        return node == null || node.isNull() || !node.canConvertToInt() ? fallback : node.asInt();
    }
}
