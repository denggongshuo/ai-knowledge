package com.knowledge.ai.core.parse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-0S1 · opendataloader 表格识别实测（风险 R-3）。
 *
 * <p><b>为什么这是 S0 必须先做掉的事</b>：R-3 的失败后果不是"表格丑一点"，而是
 * **表格内容根本没进索引** —— 文档状态显示「已就绪」，用户却搜不到报价、搜不到参数表，
 * 而且不知情（《02-需求总览》§「静默降级视为缺陷」）。这条决定 M05 的表格双表示
 * （06 §1.4）到底能不能落地，也决定要不要补 TSR 方案。所以必须在写 M05 之前有结论。
 *
 * <p><b>为什么标的选这两条对照</b>：opendataloader 1.11.0 自带两种表格抽取方法
 * （{@code TABLE_METHOD_DEFAULT} / {@code TABLE_METHOD_CLUSTER}）。
 * 与其猜哪种好，不如在同一批真实文档上各跑一遍，用"抽出的表格数 / 行数 / 是否结构完整"对比。
 *
 * <p><b>为什么默认不跟着 {@code mvn test} 跑</b>：它依赖 {@code docs/测试文件/} 下的
 * 真实样本（不入库，别人 clone 后没有），且要跑分钟级。做法是**文件名不带 Test 后缀** ——
 * surefire 的默认扫描只认 {@code *Test / Test* / *Tests / *TestCase}，
 * 于是它天然不参与日常构建。要跑时显式指名：
 *
 * <pre>
 * mvn -B test -Dtest=TableRecognitionSpikeRunner
 * </pre>
 *
 * <p>（试过 {@code @Tag("spike")} + pom 里 {@code <excludedGroups>spike</excludedGroups>}，
 * 但 CLI 上 {@code -DexcludedGroups=} 空值无法覆盖 pom 配置，
 * 结果是「include 与 exclude 同时命中 spike → 排除优先 → 0 个用例」，
 * 排查成本高于收益，改用命名隔离。）
 *
 * <p>产物写在 {@code docs/中转临时目录/table-spike/}（已 gitignore）——
 * 结论在报告里，原始产物留档供复核。
 */
class TableRecognitionSpikeRunner {

    /** 真实样本目录（不入库，见 .gitignore）。 */
    private static final Path SAMPLE_DIR = repoRoot().resolve("docs/测试文件");

    /** 产物目录（不入库）。 */
    private static final Path OUT_ROOT = repoRoot().resolve("docs/中转临时目录/table-spike");

    /** 两种可对照的表格抽取方法。 */
    private static final List<String> TABLE_METHODS = List.of(
            Config.TABLE_METHOD_DEFAULT, Config.TABLE_METHOD_CLUSTER);

    @Test
    @DisplayName("R-3：真实 PDF 上对照 DEFAULT / CLUSTER 两种表格抽取方法")
    void compareTableMethodsOnRealDocuments() throws IOException {
        assertTrue(Files.isDirectory(SAMPLE_DIR),
                "缺少样本目录 " + SAMPLE_DIR + "（该目录不入库，需本地准备）");
        Files.createDirectories(OUT_ROOT);

        List<Path> pdfs = listPdfs();
        assertTrue(!pdfs.isEmpty(), "样本目录下没有 PDF：" + SAMPLE_DIR);

        List<Row> rows = new ArrayList<>();
        for (Path pdf : pdfs) {
            for (String method : TABLE_METHODS) {
                rows.add(runOne(pdf, method));
            }
        }

        printSummary(rows);
        writeCsv(rows);
        OpenDataLoaderPDF.shutdown();
    }

    // ==================================================================
    // 单个文档 × 单种方法
    // ==================================================================

    private Row runOne(Path pdf, String tableMethod) throws IOException {
        String docName = pdf.getFileName().toString().replaceAll("\\.pdf$", "");
        Path outDir = OUT_ROOT.resolve(sanitize(docName) + "/" + tableMethod);

        Config config = new Config();
        config.setOutputFolder(outDir.toString());
        config.setGenerateMarkdown(true);
        // 只要 markdown：JSON/Text 对本次结论没有增量信息，还会拖慢大文件
        config.setGenerateJSON(false);
        config.setGenerateText(false);
        config.setGenerateHtml(false);
        // 21MB 那份是图片较多的手册，打开图片输出会把产物撑到几百 MB
        config.setImageOutput(Config.IMAGE_OUTPUT_OFF);
        config.setTableMethod(tableMethod);
        // 保留换行：表格单元格里的多行文本如果被压平，行列就错位了
        config.setKeepLineBreaks(true);

        long start = System.currentTimeMillis();
        String failure = null;
        try {
            OpenDataLoaderPDF.processFile(pdf.toAbsolutePath().toString(), config);
        } catch (Exception ex) {
            failure = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        long costMs = System.currentTimeMillis() - start;

        String markdown = readMarkdown(outDir);
        Long bytes = Files.exists(pdf) ? Files.size(pdf) : null;

        return new Row(docName,
                bytes == null ? -1 : bytes,
                tableMethod,
                costMs,
                countOccurrences(markdown, "<table"),
                countOccurrences(markdown, "<tr"),
                countPipeTableRows(markdown),
                markdown.length(),
                failure);
    }

    /** 读取输出目录下的 markdown（文件名由 opendataloader 决定，这里不假设）。 */
    private String readMarkdown(Path outDir) throws IOException {
        if (!Files.isDirectory(outDir)) {
            return "";
        }
        try (Stream<Path> files = Files.walk(outDir)) {
            return files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .sorted()
                    .map(p -> {
                        try {
                            return Files.readString(p, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            return "";
                        }
                    })
                    .reduce("", String::concat);
        }
    }

    // ==================================================================
    // 输出
    // ==================================================================

    private void printSummary(List<Row> rows) {
        System.out.println("\n==================== T-0S1 表格识别实测（R-3） ====================");
        System.out.printf("%-34s %-12s %8s %8s %8s %8s %8s%n",
                "文档", "方法", "耗时ms", "<table>", "<tr>", "管道表行", "字符数");
        System.out.println("-".repeat(100));
        rows.forEach(r -> System.out.printf("%-34s %-12s %8d %8d %8d %8d %8d%s%n",
                truncate(r.doc(), 32), r.tableMethod(), r.costMs(),
                r.htmlTables(), r.htmlRows(), r.pipeRows(), r.markdownChars(),
                r.failure() == null ? "" : "  ⚠️ " + r.failure()));
        System.out.println("=".repeat(100));

        System.out.println("\n--- 认定口径 ---");
        System.out.println("  <table>  : HTML 表格个数（useHTMLInMarkdown 默认 false，出现即说明该 PDF 走的是结构树分支）");
        System.out.println("  管道表行 : markdown 原生表格的行数（| a | b |）");
        System.out.println("  两者为 0 : 该文档在这套配置下**没有抽出任何表格** —— R-3 的风险点");
    }

    private void writeCsv(List<Row> rows) throws IOException {
        StringBuilder sb = new StringBuilder("doc,bytes,tableMethod,costMs,htmlTables,htmlRows,pipeRows,markdownChars,failure\n");
        for (Row r : rows) {
            sb.append(String.join(",",
                    quote(r.doc()), String.valueOf(r.bytes()), r.tableMethod(),
                    String.valueOf(r.costMs()), String.valueOf(r.htmlTables()),
                    String.valueOf(r.htmlRows()), String.valueOf(r.pipeRows()),
                    String.valueOf(r.markdownChars()),
                    quote(r.failure() == null ? "" : r.failure()))).append('\n');
        }
        Path csv = OUT_ROOT.resolve("summary.csv");
        Files.writeString(csv, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("\n原始指标已落盘：" + csv);
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static Path repoRoot() {
        // maven test 的工作目录是模块目录 ai_knowledge_web，上一级即仓库根
        Path cwd = Path.of("").toAbsolutePath();
        return Files.isDirectory(cwd.resolve("docs")) ? cwd : cwd.getParent();
    }

    private List<Path> listPdfs() throws IOException {
        try (Stream<Path> files = Files.list(SAMPLE_DIR)) {
            return files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    /** markdown 原生表格行：以 | 开头且行内至少两个 |。 */
    private static int countPipeTableRows(String markdown) {
        int count = 0;
        for (String line : markdown.split("\r?\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("|") && countOccurrences(trimmed, "|") >= 2) {
                count++;
            }
        }
        return count;
    }

    private record Row(String doc, long bytes, String tableMethod, long costMs,
                       int htmlTables, int htmlRows, int pipeRows, int markdownChars, String failure) {
    }
}
