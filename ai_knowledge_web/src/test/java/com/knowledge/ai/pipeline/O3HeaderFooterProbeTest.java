package com.knowledge.ai.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **opendataloader 元素树的实况取证**（`优化/O3` 步 0.1 的探针，已转为常驻用例）。
 *
 * <p>它回答两个问题，两个都值得长期钉住：
 * <ol>
 *   <li><b>元素类型的全集是什么？有没有原生 `header`/`footer`？</b> —— 这是 `O3` §4 步 1
 *       「若已有 `header`/`footer` 类型，直接用，不要自己写启发式」的判据。
 *       实测：**6 种，没有 header/footer** → 启发式必须自己写。</li>
 *   <li><b>容器（`text block`）的后代有没有进产物？</b> —— `S4-M05-C2` 步 0.5 修的缺陷
 *       就死在这里。这条用**真样本**守，与 `PdfDocumentParserTest` 的合成用例互为补充。</li>
 * </ol>
 *
 * <h3>样本缺失时跳过（`Assumptions`），与 `DocParseFlowTest` 同一条纪律</h3>
 * <p>`docs/测试文件/` 是 gitignored，别的机器 clone 后没有样本。跳过而不是失败 ——
 * 但**要清楚这意味着什么**：本用例在无样本的环境里是**静默不跑的**，
 * 所以真正进 CI 的回归保护是合成用例 `PdfDocumentParserTest`（它不依赖样本）。
 */
class O3HeaderFooterProbeTest {

    private static final Path SAMPLE = Path.of("..", "docs", "测试文件",
            "江西省农村信用社联合社_业务中台整体规划项目_项目管理章程.pdf").toAbsolutePath().normalize();

    @Test
    @DisplayName("O3 步 0.1：元素类型全集是 6 种，且**没有**原生 header/footer")
    void elementTypeInventoryHasNoHeaderFooter() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(SAMPLE), "缺样本 " + SAMPLE);

        JsonNode root = runAndReadJson(SAMPLE);
        var counts = new TreeMap<String, Integer>();
        collectTypes(root.get("kids"), counts);

        System.out.println("======== O3 步 0.1：元素 type 全集 ========");
        System.out.println("总页数 = " + root.path("number of pages").asInt());
        counts.forEach((type, n) -> System.out.println("  " + type + " × " + n));
        System.out.println("==========================================");

        assertThat(counts)
                .as("元素类型全集。`text block` 是**容器**（子树里才是真内容）—— 它此前漏在清单外，"
                        + "导致实现只遍历顶层、静默丢内容；这里钉住它，避免下一个人再漏")
                .containsKeys("heading", "paragraph", "list", "table", "image", "text block");

        assertThat(counts)
                .as("**没有**原生 header/footer —— 这是 `O3` 的启发式必须自己写的依据"
                        + "（`O3` §4 步 1 的原话是「若已有，直接用」）")
                .doesNotContainKeys("header", "footer");
    }

    @Test
    @DisplayName("步 0.5：真样本里「只在 text block 子树出现」的文本，现在全部进得了产物")
    void containerOnlyContentSurvivesInRealSample() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(SAMPLE), "缺样本 " + SAMPLE);

        // ① 从原始 JSON 算出**判别锚点**：只在容器子树里出现、顶层没有的文本。
        //    这类文本在旧实现下根本进不了产物 —— 所以它天然区分新旧。
        JsonNode root = runAndReadJson(SAMPLE);
        Set<String> topLevel = new LinkedHashSet<>();
        Set<String> insideContainers = new LinkedHashSet<>();
        splitContents(root.get("kids"), false, topLevel, insideContainers);
        Set<String> nestedOnly = new LinkedHashSet<>(insideContainers);
        nestedOnly.removeAll(topLevel);

        assertThat(nestedOnly)
                .as("该样本必须有「只在容器里出现」的文本 —— 否则这条用例区分不了新旧实现，等于没测。"
                        + "（第一版拿 `孔发龙` 当锚点就栽在这儿：它是某个更长顶层串的**子串**，"
                        + "新旧实现都「含」它，看着像证据其实什么都没证明）")
                .isNotEmpty();

        // ② 用真解析器跑，逐条核对
        ParsedDocument doc = PdfDocumentParser.parse(SAMPLE);
        String all = doc.markdown() + "\n" + doc.elements().stream()
                .map(e -> e.text() == null ? "" : e.text())
                .collect(Collectors.joining("\n"));
        // 空白归一化：`keepLineBreaks` 会把同一段文字断在不同行，逐字相等会因换行差异误判
        String allNorm = all.replaceAll("\\s+", "");

        System.out.printf("======== 步 0.5 真样本 ========%n"
                        + "chars=%d · paragraphs=%d · headings=%d · elements=%d%n"
                        + "顶层独有 %d 条 · 容器内独有 %d 条%n"
                        + "==============================%n",
                doc.chars(), doc.paragraphs(), doc.headings().size(), doc.elements().size(),
                topLevel.size(), nestedOnly.size());

        long missing = nestedOnly.stream()
                .filter(t -> !allNorm.contains(t.replaceAll("\\s+", "")))
                .count();
        assertThat(missing)
                .as("%d 条只在容器里的文本没进产物 —— 旧实现下它们会**全部**丢失，"
                        + "而文档显示「解析成功」：本项目最不能接受的静默形态", nestedOnly.size())
                .isZero();
    }

    // ==================================================================

    /** 递归收集 type → 出现次数（**递归**是关键：容器里的元素也要计入）。 */
    private static void collectTypes(JsonNode kids, TreeMap<String, Integer> counts) {
        if (kids == null || !kids.isArray()) {
            return;
        }
        for (JsonNode el : kids) {
            counts.merge(el.path("type").asText("<none>"), 1, Integer::sum);
            collectTypes(el.get("kids"), counts);
        }
    }

    /** 把 `content` 分成「顶层元素自己的」与「容器子树里的」两堆。 */
    private static void splitContents(JsonNode kids, boolean insideContainer,
                                      Set<String> topLevel, Set<String> insideContainers) {
        if (kids == null || !kids.isArray()) {
            return;
        }
        for (JsonNode el : kids) {
            boolean container = "text block".equals(el.path("type").asText());
            String content = el.path("content").asText("").strip();
            // 太短的串（"2"、"目 录"）区分度不够，不拿它当锚点
            if (!content.isEmpty() && content.length() >= 4) {
                (insideContainer ? insideContainers : topLevel).add(content);
            }
            splitContents(el.get("kids"), insideContainer || container, topLevel, insideContainers);
        }
    }

    /**
     * 跑一次 opendataloader 并读回 JSON 根。
     *
     * <p>⚠️ **config 必须与 {@link PdfDocumentParser#parse} 逐项一致** —— 第一版这里漏了
     * {@code keepLineBreaks}，两次跑出的元素树不同、锚点对不上，用例**误报**。
     * 要比较"两次运行"就必须是同一次配置。
     */
    private static JsonNode runAndReadJson(Path sample) throws Exception {
        Path outDir = Files.createTempDirectory("kb-probe-");
        try {
            Config config = new Config();
            config.setOutputFolder(outDir.toString());
            config.setGenerateMarkdown(true);
            config.setGenerateJSON(true);
            config.setGenerateText(false);
            config.setGenerateHtml(false);
            config.setImageOutput(Config.IMAGE_OUTPUT_OFF);
            config.setTableMethod(Config.TABLE_METHOD_DEFAULT);
            config.setKeepLineBreaks(true);
            OpenDataLoaderPDF.processFile(sample.toAbsolutePath().toString(), config);

            Path json;
            try (var s = Files.list(outDir)) {
                json = s.filter(p -> p.toString().endsWith(".json")).findFirst().orElse(null);
            }
            if (json == null) {
                throw new IllegalStateException("探针没拿到 JSON");
            }
            return new ObjectMapper().readTree(Files.readString(json, StandardCharsets.UTF_8));
        } finally {
            try (var s = Files.walk(outDir)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // 探针的临时目录清不掉不影响结论
                    }
                });
            } catch (Exception ignored) {
                // ignore
            }
        }
    }
}
