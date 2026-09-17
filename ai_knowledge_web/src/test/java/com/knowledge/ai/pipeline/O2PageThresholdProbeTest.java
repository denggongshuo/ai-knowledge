package com.knowledge.ai.pipeline;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **页级文本层阈值的实况取证**（`优化/O2` 步 0 的探针 —— 用户 2026-09-17 裁定「先跑样本再定阈值」）。
 *
 * <h3>为什么必须先跑，而不是照 `O2` §6 建议的 10</h3>
 * <p>`O2` §6 自己把这条列为风险：「文档级有平均值稀释，页级是逐页判。现有
 * {@code MIN_CHARS_PER_PAGE = 20} 直接在页级复用会误报"封面页/版权页"（只有书名与年份）。
 * **页级阈值必须另取**（建议 10）并**先跑样本**」。
 *
 * <p>本仓已经为「拍了没量」返工过一次：`S4-B` 的 W1' —— 密度判据对单页文档是**误报**
 * （27 字节的 MD 便利贴被判"疑似扫描件"并建议用户"启用 OCR"，而那文档本来就是完整的）。
 * 所以这次先量。判据本仓有现成的：**误报比漏报更伤**。
 *
 * <h3>探针要回答的三个问题</h3>
 * <ol>
 *   <li><b>页级阈值取多少</b> —— 真样本里"封面/版权/版式页"的字符数下界是多少？
 *       "真扫描页"的上界是多少？两者**有没有间隙**（有间隙才谈得上选阈值）。</li>
 *   <li><b>首页/末页要不要放宽</b> —— `O2` §6 的另一条建议。数据上封面页是不是**系统性**地短？</li>
 *   <li><b>"有图无字页"在真样本里的形态</b> —— `kind = SCANNED` 的判据是"该页有整页图像"，
 *       而产物里只有 `imageCount`（`DocElement.image` 只存页号，不带尺寸）。这张表要给出
 *       "imageCount &gt; 0 且 chars 低"的页**实际长什么样**（避免把"正文里插了一张小图"误判成扫描页）。</li>
 * </ol>
 *
 * <h3>样本缺失时跳过（`Assumptions`），与 O3 探针同一条纪律</h3>
 * <p>`docs/测试文件/` 是 gitignored，别的机器 clone 后没有样本。跳过而不是失败 ——
 * 但**要清楚这意味着什么**：本用例在无样本的环境里**静默不跑**，所以真正进 CI 的
 * 回归保护是合成用例（`TextLayerGuardTest` / `PdfDocumentParserTest`），它们不依赖样本。
 *
 * <p>⚠️ 本类**只打印、不断言业务结论**（除了"样本能读、页数对得上"这类自证）。
 * 它的产物是一张表，结论要人工读进 {@code TextLayerGuard} 的类注释。把刚量出来的数字
 * 直接写成断言，等于把"探针"变成"把当时的观测固化成规范" —— 那是另一件事。
 */
class O2PageThresholdProbeTest {

    private static final Path SAMPLE_DIR = Path.of("..", "docs", "测试文件").toAbsolutePath().normalize();

    /** 探针样本：覆盖"整篇扫描件"与"含图但对不上"两类极端，外加几份正常文本件作对照。 */
    private static final List<String> SAMPLES = List.of(
            "健康生活方式（指导手册）.pdf",              // 15 页 0 字符 —— 整篇扫描件（S4-B 的 P0 样本）
            "百福云PaaS平台应用部署快速入门 v1.0.pdf",     // 23 个 image 元素 —— 图多但对得上？
            "1-DeepSeek使用.pdf",                        // 有封面/版权页的正常件（O2 §6 点名的误报风险源）
            "2-提示词工程.pdf",
            "江西省农村信用社联合社_业务中台整体规划项目_项目管理章程.pdf", // PPT 导出，版式页多
            "江西农商银行_营销中心系统-软件需求说明书-20210511.docx"       // 非 PDF 路径的对照
    );

    /**
     * 逐页指标（**从产物派生**，与将来 `PageStat` 同一条口径）。
     *
     * @param pageNo     页码
     * @param chars      该页正文字符数（标题 + 段落 + 表格单元格，与文档级 `chars` 同一算法）
     * @param imageCount 该页 image 元素个数
     * @param hasTable   该页有没有表
     */
    private record PageRow(int pageNo, int chars, int imageCount, boolean hasTable) {
    }

    @Test
    @DisplayName("O2 步 0：逐页字符数分布 —— 页级阈值取多少才有间隙")
    void perPageCharDistribution() {
        for (String name : SAMPLES) {
            Path file = SAMPLE_DIR.resolve(name);
            Assumptions.assumeTrue(Files.isRegularFile(file), "缺样本 " + name);

            if (name.endsWith(".pdf")) {
                reportPdf(file, name);
            } else {
                reportNonPdf(file, name);
            }
        }
    }

    // ==================================================================
    // PDF 路径：走真解析器，取逐页分布
    // ==================================================================

    private void reportPdf(Path file, String name) {
        ParsedDocument parsed;
        try {
            parsed = PdfDocumentParser.parse(file);
        } catch (Exception ex) {
            System.out.println("======== " + name + " ========");
            System.out.println("  ⚠️ 解析失败（探针跳过本样本）：" + ex.getMessage());
            return;
        }

        List<PageRow> rows = perPage(parsed);

        System.out.println();
        System.out.println("======== O2 步 0 探针 · " + name + " ========");
        System.out.println("  文档级：pages=" + parsed.pages() + " chars=" + parsed.chars()
                + " charsPerPage=" + String.format("%.1f", parsed.charsPerPage())
                + " images=" + parsed.imageCount() + " tables=" + parsed.tableTotal());
        System.out.println("  ---- 逐页（只有 chars < 200 或含图的页逐行列出，其余折叠）----");
        for (PageRow r : rows) {
            boolean interesting = r.chars() < LOW_PAGE_PRINT_THRESHOLD || r.imageCount() > 0;
            if (interesting) {
                System.out.printf("   p%-4d chars=%-6d images=%-3d table=%s%n",
                        r.pageNo(), r.chars(), r.imageCount(), r.hasTable() ? "Y" : "-");
            }
        }
        printHistogram(rows);
    }

    /** 逐页聚合：从 `elements` 派生（**不重新解析** —— `O2` §4 步 1 的前提）。 */
    private static List<PageRow> perPage(ParsedDocument parsed) {
        Map<Integer, int[]> acc = new TreeMap<>();   // pageNo -> [chars, imageCount]
        Map<Integer, Boolean> tableOn = new TreeMap<>();

        for (ParsedDocument.DocElement el : parsed.elements()) {
            int[] slot = acc.computeIfAbsent(el.pageNo(), k -> new int[2]);
            switch (el.kind()) {
                case HEADING, PARAGRAPH -> slot[0] += el.text() == null ? 0 : el.text().length();
                case IMAGE -> slot[1]++;
                case TABLE -> {
                    tableOn.put(el.pageNo(), true);
                    // 表格单元格文本计入该页 —— 与文档级 chars 的口径一致
                    ParsedDocument.ParsedTable t = parsed.tables().get(el.tableIndex());
                    slot[0] += t.rows().stream()
                            .flatMap(List::stream)
                            .mapToInt(String::length)
                            .sum();
                }
            }
        }
        // 补上"一个字都没有、也没有元素"的页（真扫描件在 opendataloader 下就是这种形状）
        for (int p = 1; p <= Math.max(parsed.pages(), 1); p++) {
            acc.computeIfAbsent(p, k -> new int[2]);
        }
        return acc.entrySet().stream()
                .map(e -> new PageRow(e.getKey(), e.getValue()[0], e.getValue()[1],
                        tableOn.getOrDefault(e.getKey(), false)))
                .toList();
    }

    private static final int LOW_PAGE_PRINT_THRESHOLD = 200;
    private static final int LOW_PAGE_HISTOGRAM_THRESHOLD = 300;

    /** 打印"低字页"的分布直方图 —— 阈值要从这张表读出来。 */
    private static void printHistogram(List<PageRow> rows) {
        int[] buckets = new int[7];      // 0 / 1-9 / 10-19 / 20-49 / 50-99 / 100-299 / >=300
        String[] labels = {"=0", "1-9", "10-19", "20-49", "50-99", "100-299", ">=300"};
        for (PageRow r : rows) {
            buckets[bucketOf(r.chars())]++;
        }
        System.out.println("  ---- 逐页 chars 分布（共 " + rows.size() + " 页）----");
        for (int i = 0; i < buckets.length; i++) {
            System.out.printf("   %-8s %d%n", labels[i], buckets[i]);
        }
        long lowWithImage = rows.stream()
                .filter(r -> r.chars() < LOW_PAGE_HISTOGRAM_THRESHOLD && r.imageCount() > 0)
                .count();
        long lowNoImage = rows.stream()
                .filter(r -> r.chars() < LOW_PAGE_HISTOGRAM_THRESHOLD && r.imageCount() == 0)
                .count();
        System.out.printf("   ↳ 低字页( < %d )中：带图 %d 页 · 不带图 %d 页%n",
                LOW_PAGE_HISTOGRAM_THRESHOLD, lowWithImage, lowNoImage);
        // 首页 / 末页单独看 —— O2 §6 建议"对首页/末页放宽"，数据上成不成立
        if (!rows.isEmpty()) {
            PageRow first = rows.getFirst();
            PageRow last = rows.getLast();
            System.out.printf("   ↳ 首页 p%d chars=%d images=%d · 末页 p%d chars=%d images=%d%n",
                    first.pageNo(), first.chars(), first.imageCount(),
                    last.pageNo(), last.chars(), last.imageCount());
        }
    }

    private static int bucketOf(int chars) {
        if (chars == 0) {
            return 0;
        }
        if (chars < 10) {
            return 1;
        }
        if (chars < 20) {
            return 2;
        }
        if (chars < 50) {
            return 3;
        }
        if (chars < 100) {
            return 4;
        }
        if (chars < 300) {
            return 5;
        }
        return 6;
    }

    // ==================================================================
    // 非 PDF 路径：Tika 无页概念，整篇视为一页（O2 §6 的格式事实）
    // ==================================================================

    private void reportNonPdf(Path file, String name) {
        System.out.println();
        System.out.println("======== O2 步 0 探针 · " + name + "（非 PDF 路径）========");
        try (var in = Files.newInputStream(file)) {
            ParsedDocument parsed = new TikaDocumentParser().parse(in, name);
            System.out.println("  pages=" + parsed.pages() + "（Tika 无页概念 → 恒 1）"
                    + " chars=" + parsed.chars() + " images=" + parsed.imageCount()
                    + " elements=" + parsed.elements().size());
            long distinctPageNos = parsed.elements().stream()
                    .map(ParsedDocument.DocElement::pageNo).distinct().count();
            System.out.println("  elements 里出现的不同 pageNo 个数 = " + distinctPageNos
                    + "（**必须是 1** —— 大于 1 说明 Tika 路径凭空造了页号，页级判据会算错）");
            assertThat(distinctPageNos)
                    .as("Tika 路径的所有元素都应落在同一页（无页概念是格式事实，不是缺陷）")
                    .isEqualTo(1L);
        } catch (Exception ex) {
            System.out.println("  ⚠️ 解析失败（探针跳过本样本）：" + ex.getMessage());
        }
    }

    // ==================================================================
    // 合成能力验证（本批 §3.4 假设 2）
    // ==================================================================

    /**
     * **pdfbox 能不能合成"文本页 + 扫描页"混合样本** —— 判据 1/2/4 全靠这个。
     *
     * <p>`O2` §5 判据 1 写「造一份 38 页文本 + 2 页扫描图（或直接用 `健康生活方式.pdf` 拼页）」，
     * 但**没写怎么造**。而 `docs/测试文件/` 里没有任何混合样本 —— 所以本批必须自己合成，
     * 否则判据 1 只能靠单元级构造取证（验服务层逻辑，**验不到"真 PDF 走完全程"**）。
     *
     * <p>本用例验证两件事，缺一不可：
     * <ol>
     *   <li>能从 `健康生活方式.pdf` **抽出一页**（得到真扫描页的字节）；</li>
     *   <li>能把"文本页 + 抽出的扫描页"**合成一份新 PDF**，且它过真解析器后
     *       **确实**表现为"多数页有字、少数页零字"。</li>
     * </ol>
     * 只验 ① 不验 ② 是不够的 —— 合成出来的文件可能是空壳（pdfbox 的 `importPage` 有
     * 资源引用陷阱：只 `addPage` 不 `importPage` 会得到一个空白页，而它**看起来**页数是对的）。
     */
    @Test
    @DisplayName("O2 步 0：pdfbox 能合成「文本页 + 扫描页」混合样本（假设 2 的验证）")
    void pdfboxCanComposeMixedSample() throws Exception {
        Path scanned = SAMPLE_DIR.resolve("健康生活方式（指导手册）.pdf");
        Path textSample = SAMPLE_DIR.resolve("1-DeepSeek使用.pdf");
        Assumptions.assumeTrue(Files.isRegularFile(scanned), "缺样本 健康生活方式（指导手册）.pdf");
        Assumptions.assumeTrue(Files.isRegularFile(textSample), "缺样本 1-DeepSeek使用.pdf");

        Path out = Files.createTempFile("o2-mixed-", ".pdf");
        try {
            int textPages;
            int scannedPageSource;
            try (PDDocument target = new PDDocument()) {
                // ① 文本页：从一份有文本层的真 PDF 抽前 N 页
                try (PDDocument src = Loader.loadPDF(textSample.toFile())) {
                    List<PDPage> picked = new ArrayList<>();
                    for (int i = 0; i < Math.min(3, src.getNumberOfPages()); i++) {
                        picked.add(src.getPage(i));
                    }
                    for (PDPage p : picked) {
                        target.importPage(p);
                    }
                    textPages = picked.size();
                }
                // ② 扫描页：从整篇扫描件抽 1 页 —— importPage 才会带上资源（只 addPage 得到白页）
                try (PDDocument src = Loader.loadPDF(scanned.toFile())) {
                    assertThat(src.getNumberOfPages())
                            .as("扫描件样本应当有多页（少于 2 页说明样本不对）")
                            .isGreaterThan(1);
                    target.importPage(src.getPage(0));
                    scannedPageSource = src.getNumberOfPages();
                }
                assertThat(target.getNumberOfPages())
                        .as("合成件页数 = 文本页 + 扫描页")
                        .isEqualTo(textPages + 1);
                target.save(out.toFile());
            }

            // ③ 关键：过**真解析器**，确认合成件确实"多数页有字、少数页零字"
            ParsedDocument composed = PdfDocumentParser.parse(out);
            Map<Integer, Integer> charsPerPage = new TreeMap<>();
            for (ParsedDocument.DocElement el : composed.elements()) {
                charsPerPage.merge(el.pageNo(),
                        el.text() == null ? 0 : el.text().length(), Integer::sum);
            }
            int totalPages = composed.pages();
            for (int p = 1; p <= totalPages; p++) {
                charsPerPage.putIfAbsent(p, 0);
            }
            long blank = charsPerPage.values().stream().filter(c -> c == 0).count();
            long withText = charsPerPage.values().stream().filter(c -> c > 0).count();

            System.out.println();
            System.out.println("======== O2 步 0 探针 · 合成混合样本 ========");
            System.out.println("  源：文本件 " + textPages + " 页（自 " + textSample.getFileName() + "）"
                    + " + 扫描件 1 页（自 " + scannedPageSource + " 页的样本）");
            System.out.println("  合成件：pages=" + totalPages + " chars=" + composed.chars());
            charsPerPage.forEach((p, c) -> System.out.printf("   p%-4d chars=%d%n", p, c));

            assertThat(totalPages).as("合成件应当有 " + (textPages + 1) + " 页").isEqualTo(textPages + 1);
            assertThat(blank)
                    .as("抽出来的扫描页应当是**零字符**页 —— 否则它不是真扫描页，"
                            + "而判据 1/2/4 就失去了取证对象。实测逐页：%s", charsPerPage)
                    .isGreaterThanOrEqualTo(1L);
            assertThat(withText)
                    .as("文本页应当有字符 —— 否则合成的是空壳（pdfbox 的 importPage 陷阱）")
                    .isGreaterThanOrEqualTo(1L);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    // ==================================================================
    // 对照：文档级阈值的既有余量（S4-B 的实测结论，用来判断页级阈值能取多低）
    // ==================================================================

    @Test
    @DisplayName("O2 步 0：文档级阈值的余量对照（20 字/页 的既有依据）")
    void documentLevelThresholdMargin() {
        System.out.println();
        System.out.println("======== O2 步 0 探针 · 文档级余量（TextLayerGuard 类注释的既有依据）========");
        System.out.println("  既有口径：chars == 0 ｜ 或（pages >= 2 且 chars/页 < 20）");
        System.out.println("  既有实测：扫描件 0 字/页（命中）· 最稀的正常样本约 294 字/页（余量一个数量级）");

        int[] probeChars = {0, 1, 9, 10, 19, 20, 49, 50, 100, 294};
        for (int c : probeChars) {
            double perPage = c;
            boolean hit = c == 0 || perPage < 20;
            System.out.printf("   页 chars=%-5d → 文档级命中=%s%n", c, hit ? "是" : "否");
        }
        // 本用例不设业务断言：它是把 O2 图里的数重新摆一遍给"定页级阈值"的人看对照
        IntStream.range(0, 1).forEach(i -> assertThat(probeChars).isNotEmpty());
    }
}