package com.knowledge.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledge.ai.pipeline.ParsedDocument;
import com.knowledge.ai.pipeline.TextLayerGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 解析质量基线（`T-O05` 步 1/2/3 · `O5` §5 判据 1/2/3）。
 *
 * <h3>它存在的唯一理由：让「跑起来了但输出变了」被发现</h3>
 * <p>企业知识库里，解析质量事故 90% 是**静默的** —— 不是崩，是"少了一点东西"。
 * 本项目已经踩到过两次同形状的坑，但那两次都靠**侥幸**（spike 期、接入时就崩）。
 * 真正没有机制的是第三种：**升级 Tika 后某类表少识别 2 张、改了配置后多行单元格被压平**。
 * 本用例就是那个机制。
 *
 * <h3>为什么是区间 + 结构等值，而不是整篇哈希</h3>
 * <p>{@code O5} §4 的原话：「整篇哈希对任何无关改动都敏感（改个换行就红），
 * 会导致"狼来了"而被人关掉」。所以：
 * <ul>
 *   <li>{@code chars} 用**区间**（±2%）—— 它是"量"，会被清洗规则微调；</li>
 *   <li>{@code verdict / tableTotal / tableParsedMin / imageCount} 用**等值或下界** ——
 *       它们是"结构事实"，表还在不在、表头还是不是那几列，那才是质量红线；</li>
 *   <li>再加两条**逐字锚点**（首个标题、第一张有内容的表的表头）。</li>
 * </ul>
 *
 * <h3>缺样本时**失败**，不跳过</h3>
 * <p>{@code O5} §6 明确要求：「用例在缺样本时**失败而非跳过**（`Assumptions` 会静默假绿）」。
 * 样本目录（{@code docs/测试文件/}）是 gitignored 的，所以换一台机器就没有 ——
 * 那时**必须**红，否则这份基线在别人机器上是一条永远通过的空断言。
 *
 * <p>⚠️ 这与存量 {@code DocParseFlowTest#assumeSample} 的**跳过**口径不同，而且同一次
 * {@code mvn test} 里两种行为并存。本批**刻意不改存量**（改它是"顺手改"，会让本批验收与
 * 遗留双双失真），分歧与理由写在这里，并已登记为遗留。
 *
 * <h3>跑红了怎么办</h3>
 * <p>先看差异清单（失败信息里那张表）—— 它回答的是"**到底变了什么**"，而不是
 * {@code expected: <7> but was: <5>}。然后二选一：
 * <ul>
 *   <li>是**预期变更** → 改 {@code parse-fixtures.json} 并**进提交记录**（`O5` §6 的要求：
 *       期望值更新必须可追溯，否则"狼来了"会让人不敢改）；</li>
 *   <li>是**退化** → 修代码。</li>
 * </ul>
 */
class ParseBaselineTest {

    /** 样本目录。可用 {@code -Dparse.sample.dir=…} 覆盖 —— 供"缺样本必须失败"那条用例用。 */
    private static final String SAMPLE_DIR_PROPERTY = "parse.sample.dir";

    private static final String DEFAULT_SAMPLE_DIR = "../docs/测试文件";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================== 判据 1 · 基线覆盖全部样本 ====================

    @Test
    @DisplayName("O5-1 · 17 个样本逐个断言（缺样本则失败，不跳过）")
    void allSamplesMatchTheRegisteredBaseline() throws IOException {
        List<Expect> expects = loadFixtures();
        assertThat(expects)
                .as("夹具不能是空的 —— 空夹具会让本用例变成一条永真的断言")
                .isNotEmpty();

        Outcome outcome = runBaseline(expects, sampleDir());

        assertThat(outcome.missing())
                .as("样本目录 %s 里缺这些文件。基线**不会跳过**（`O5` §6）："
                        + "跳过等于静默假绿，而这份用例的全部价值就是「没有东西能静默变坏」。"
                        + "请把样本放回该目录，或用 -D%s=… 指向它们所在的位置",
                        sampleDir(), SAMPLE_DIR_PROPERTY)
                .isEmpty();

        assertThat(outcome.diffs())
                .as("解析输出与登记的基线不一致。**差异清单**：%n%s%n"
                        + "—— 是预期变更就改 parse-fixtures.json 并进提交记录；是退化就修代码",
                        String.join(System.lineSeparator(), outcome.diffs()))
                .isEmpty();
    }

    // ==================== 判据 3 · 差异清单可读 ====================

    @Test
    @DisplayName("O5-3 · 差异清单是「样本 → 字段 → 旧值 → 新值 → 位置」，不是一行 assert 失败")
    void mismatchIsDescribedNotJustAsserted() {
        String line = describe("1-DeepSeek使用.pdf", "tableTotal", 7, 5, "第 12 页的 Model/Params 表");

        assertThat(line)
                .as("四个部分缺一不可：没有「哪个样本」就没法定位，没有「旧值→新值」就不知道变了多少，"
                        + "没有「位置」就得自己去翻 2000 行明细")
                .contains("1-DeepSeek使用.pdf").contains("tableTotal")
                .contains("7").contains("5").contains("第 12 页的 Model/Params 表");
    }

    // ==================== 判据 1 的反面 · 缺样本必须红 ====================

    @Test
    @DisplayName("O5-1 · 样本目录不存在时**失败而非跳过**（Assumptions 会静默假绿）")
    void missingSamplesFailInsteadOfSkipping() throws IOException {
        Path emptyDir = Files.createTempDirectory("parse-baseline-empty");
        try {
            Outcome outcome = runBaseline(loadFixtures(), emptyDir);

            assertThat(outcome.missing())
                    .as("换个目录就该「全都缺」 —— 这条用例是上一条的前提："
                            + "如果这里返回空，上一条的「缺样本」检查就是个摆设")
                    .hasSize(loadFixtures().size());
            assertThat(outcome.diffs()).isEmpty();
        } finally {
            Files.deleteIfExists(emptyDir);
        }
    }

    // ==================== 核心 ====================

    /**
     * 跑一遍基线。
     *
     * @return 缺了哪些样本 + 哪些字段对不上（**都收齐再报**，不是遇到第一个就抛 ——
     *         一次解析器升级往往同时影响多个文档，逐条抛会让人改一次跑一次）
     */
    static Outcome runBaseline(List<Expect> expects, Path dir) {
        List<String> missing = new ArrayList<>();
        List<String> diffs = new ArrayList<>();

        for (Expect expect : expects) {
            Path file = dir.resolve(expect.file());
            if (!Files.isRegularFile(file)) {
                missing.add(expect.file());
                continue;
            }
            ParsedDocument doc;
            try {
                doc = DocParseService.dispatch(expect.type(), Files.readAllBytes(file), expect.file());
            } catch (Exception ex) {
                diffs.add(describe(expect.file(), "解析结果", expect.verdict(),
                        "抛异常：" + ex.getClass().getSimpleName() + " " + ex.getMessage(), "解析阶段"));
                continue;
            }
            compare(expect, doc, diffs);
        }
        return new Outcome(missing, diffs);
    }

    private static void compare(Expect expect, ParsedDocument doc, List<String> diffs) {
        // 结局：能不能用守卫挡在外面。用**守卫自己**判，而不是"chars 是不是 0" ——
        // 基线要钉的是"守卫还拦不拦得住"
        String actualVerdict;
        try {
            TextLayerGuard.check(doc);
            actualVerdict = "PARSED";
        } catch (Exception ex) {
            actualVerdict = "REJECTED";
        }
        if (!expect.verdict().equals(actualVerdict)) {
            diffs.add(describe(expect.file(), "verdict", expect.verdict(), actualVerdict,
                    "TextLayerGuard 的判定 —— 它变了意味着「某些文档能不能用」的判断变了"));
            // 结局都不同，后面的字段对比没有意义（REJECTED 的文档本来就没有内容）
            return;
        }
        if ("REJECTED".equals(actualVerdict)) {
            // 被拒的文档只钉"确实被拒"与"它本来有多少图" —— 那才是这份样本的意义
            if (expect.imageCount() != doc.imageCount()) {
                diffs.add(describe(expect.file(), "imageCount", expect.imageCount(), doc.imageCount(),
                        "扫描件的图片数 —— 它变了说明这一页的解析方式变了"));
            }
            return;
        }

        if (doc.chars() < expect.charsMin() || doc.chars() > expect.charsMax()) {
            diffs.add(describe(expect.file(), "chars",
                    expect.charsMin() + "~" + expect.charsMax(), doc.chars(),
                    "可检索文本量。**低于下界最值得警惕** —— 那正是「少了一点东西」的形状"));
        }
        if (doc.headings().size() < expect.headingsMin()) {
            diffs.add(describe(expect.file(), "headings", "≥" + expect.headingsMin(),
                    doc.headings().size(), "标题层级数 —— 掉了意味着有章节没被识别出来"));
        }
        if (doc.tableTotal() != expect.tableTotal()) {
            diffs.add(describe(expect.file(), "tableTotal", expect.tableTotal(), doc.tableTotal(),
                    "表格总数（单列空表不计）—— 这是 `O5` §2 点名的那个形态："
                            + "升级依赖后某类表少识别几张"));
        }
        if (doc.tableParsed() < expect.tableParsedMin()) {
            diffs.add(describe(expect.file(), "tableParsed", "≥" + expect.tableParsedMin(),
                    doc.tableParsed(),
                    "**抽到内容的**表数（与 tableTotal 的差 = 识别不到内容的那些）。"
                            + "掉下来意味着「表格还在、格子里的字没了」"));
        }
        if (doc.imageCount() != expect.imageCount()) {
            diffs.add(describe(expect.file(), "imageCount", expect.imageCount(), doc.imageCount(),
                    "图片数"));
        }
        int multiLine = multiLineCells(doc);
        if (multiLine < expect.multiLineCellsMin()) {
            diffs.add(describe(expect.file(), "multiLineCells", "≥" + expect.multiLineCellsMin(), multiLine,
                    "**单元格里含换行的个数**（`toMarkdown` 会把它写成 `<br>`）。"
                            + "它掉到 0 意味着整篇的多行单元格被**压平**了 —— 行列会错位，"
                            + "而 `chars` 与 `tableTotal` 对此**都不敏感**"));
        }
        checkAnchor(expect.file(), "anchorHeading", expect.anchorHeading(),
                doc.headings().stream().map(h -> normalize(h.text())).toList(), diffs,
                "首个标题的逐字锚点 —— 标题没了/变了，读者最先能感觉到");
        checkAnchor(expect.file(), "anchorTableHeader", expect.anchorTableHeader(),
                contentTableHeaders(doc), diffs, "第一张有内容的表的表头逐字锚点 —— "
                        + "`O5` 的原话是「这张表还在不在、表头还是不是那几列」");
    }

    /** 锚点是**前缀**匹配：夹具里存的是规范化后截断到 60 字的期望值。 */
    private static void checkAnchor(String file, String field, String anchor, List<String> candidates,
                                    List<String> diffs, String where) {
        if (anchor == null || anchor.isBlank()) {
            return;
        }
        boolean hit = candidates.stream().anyMatch(c -> c.startsWith(anchor));
        if (!hit) {
            diffs.add(describe(file, field, anchor,
                    candidates.isEmpty() ? "（一个候选都没有）" : candidates.getFirst(), where));
        }
    }

    /**
     * 跨全部表格，**单元格里含换行的个数**。
     *
     * <h3>为什么非要有这一条（它不是"多加一个指标"）</h3>
     * <p>本用例第一版**抓不到它自己点名的那个退化**：把
     * {@code PdfDocumentParser} 的 {@code config.setKeepLineBreaks(true)} 改成 {@code false}
     * 之后，17 个样本**全绿** —— 因为 {@code chars} / {@code tableTotal} / 首个表头锚点
     * 对「多行单元格被压平」都不敏感。
     *
     * <p>而 `O5` §5 判据 2 的原话是：「**故意注入退化能被抓到** …… 这是本方案最重要的
     * 验证项：证明基线真能发现静默退化」。所以这一条度量是**被证伪逼出来的**，不是设计时想到的。
     *
     * <p>{@code D-5} / `T-0S1` §三 的结论是「多行单元格压平会让行列错位」，
     * 而 {@code ParsedTable#toMarkdown} 正是**用换行是否还在**来判断要不要写 {@code <br>}。
     * 所以「含换行的单元格数」就是那个红线的**直接观测量**：它从 N 掉到 0，
     * 说明整篇文档的多行单元格全被压平了 —— 而那正是"表格内容悄悄坏掉"的形状。
     */
    static int multiLineCells(ParsedDocument doc) {
        int count = 0;
        for (ParsedDocument.ParsedTable table : doc.tables()) {
            for (List<String> row : table.rows()) {
                for (String cell : row) {
                    if (cell != null && cell.indexOf('\n') >= 0) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** 有内容的表的表头，规范化后的列表 —— 供锚点匹配。 */
    private static List<String> contentTableHeaders(ParsedDocument doc) {
        List<String> headers = new ArrayList<>();
        for (ParsedDocument.ParsedTable table : doc.tables()) {
            if (table.hasContent() && !table.rows().isEmpty()) {
                headers.add(normalize(String.join(" ", table.rows().getFirst())));
            }
        }
        return headers;
    }

    /** 折叠所有空白：锚点里带换行会让断言"改个换行就红"（`O5` §4 要避免的"狼来了"）。 */
    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    /**
     * 差异清单的一行：**样本 → 字段 → 旧值 → 新值 → 位置**。
     *
     * <p>这是本用例存在的意义所在 —— 它要替人回答"到底变了什么"。
     * 一行 {@code expected: <7> but was: <5>} 做不到这一点：它没说哪个样本、也没说该去哪看。
     */
    static String describe(String file, String field, Object expected, Object actual, String where) {
        return "【" + file + "】" + field + "：" + expected + " → " + actual + "　（" + where + "）";
    }

    private static Path sampleDir() {
        return Path.of(System.getProperty(SAMPLE_DIR_PROPERTY, DEFAULT_SAMPLE_DIR))
                .toAbsolutePath().normalize();
    }

    private static List<Expect> loadFixtures() throws IOException {
        Path fixture = Path.of("src", "test", "resources", "parse-fixtures.json").toAbsolutePath().normalize();
        assertThat(Files.isRegularFile(fixture))
                .as("夹具文件不见了：%s", fixture)
                .isTrue();
        return MAPPER.readValue(Files.readString(fixture), Fixture.class).samples();
    }

    /** 夹具一行。字段与 {@code parse-fixtures.json} 逐字对应。 */
    record Expect(String file, String type, String verdict, int charsMin, int charsMax,
                  int headingsMin, int tableTotal, int tableParsedMin, int imageCount,
                  int multiLineCellsMin, String anchorHeading, String anchorTableHeader) {
    }

    private record Fixture(String _note, List<Expect> samples) {
    }

    /** 基线的产出：缺了哪些样本、哪些字段对不上。 */
    record Outcome(List<String> missing, List<String> diffs) {
    }

    /** 供探针与人工核对：样本目录的绝对路径。 */
    static Path resolvedSampleDir() {
        return sampleDir();
    }

    /** 便于在测试里列出样本目录内容（排查"到底缺了什么"）。 */
    static List<String> listSamples(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    static String typeOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "TXT" : fileName.substring(dot + 1).toUpperCase(Locale.ROOT);
    }
}
