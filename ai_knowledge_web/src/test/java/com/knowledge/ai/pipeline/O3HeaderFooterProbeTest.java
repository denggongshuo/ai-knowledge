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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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

    @Test
    @DisplayName("O3 步 3 前置：image 元素到底带不带几何信息（决定装饰图判定做不做得了）")
    void imageGeometryInventory() throws Exception {
        Path[] samples = {
                SAMPLE,
                Path.of("..", "docs", "测试文件", "1-DeepSeek使用.pdf").toAbsolutePath().normalize(),
                Path.of("..", "docs", "测试文件", "百福云PaaS平台应用部署快速入门 v1.0.pdf")
                        .toAbsolutePath().normalize(),
        };

        StringBuilder sb = new StringBuilder();
        for (Path sample : samples) {
            if (!Files.isRegularFile(sample)) {
                sb.append("（缺样本，跳过）").append(sample).append('\n');
                continue;
            }
            JsonNode root = runAndReadJson(sample);

            // ① 每种 type 的字段全集 —— 一眼看出 image 有没有 bounding box
            Map<String, Set<String>> keysByType = new TreeMap<>();
            collectKeys(root.get("kids"), keysByType);

            // ② 图片清单：页码 + 包围盒
            List<String> images = new ArrayList<>();
            collectImages(root.get("kids"), 0, images);

            sb.append("======== O3 步 3 前置：").append(sample.getFileName()).append(" ========\n");
            sb.append("页数=").append(root.path("number of pages").asInt())
                    .append(" · image 元素=").append(images.size()).append('\n');
            keysByType.forEach((type, keys) -> sb.append("  ").append(type)
                    .append(" 字段 = ").append(keys).append('\n'));
            images.stream().limit(12).forEach(s -> sb.append("  img ").append(s).append('\n'));
            if (images.size() > 12) {
                sb.append("  … 另 ").append(images.size() - 12).append(" 张\n");
            }
        }
        dump("image-geometry", sb.toString());
    }

    @Test
    @DisplayName("O3 步 0 干跑：真样本上「跨页重复」两条启发式分别能判出什么")
    void dryRunOfBothHeuristics() throws Exception {
        Path[] samples = {
                SAMPLE,
                Path.of("..", "docs", "测试文件", "1-DeepSeek使用.pdf").toAbsolutePath().normalize(),
                Path.of("..", "docs", "测试文件", "百福云PaaS平台应用部署快速入门 v1.0.pdf")
                        .toAbsolutePath().normalize(),
        };

        StringBuilder sb = new StringBuilder();
        for (Path sample : samples) {
            if (!Files.isRegularFile(sample)) {
                sb.append("（缺样本，跳过）").append(sample).append('\n');
                continue;
            }
            JsonNode root = runAndReadJson(sample);
            int pages = root.path("number of pages").asInt();

            // ---- ① 文本侧：每页首/尾 3 行的归一化文本 → 出现在几页上 ----
            Map<Integer, List<String>> linesByPage = new TreeMap<>();
            collectTextLines(root.get("kids"), 0, linesByPage);
            Map<String, Set<Integer>> textSeen = new TreeMap<>();
            linesByPage.forEach((page, lines) -> {
                Set<String> edge = new LinkedHashSet<>();
                lines.stream().limit(3).forEach(t -> edge.add(normalize(t)));
                if (lines.size() > 3) {
                    lines.subList(Math.max(3, lines.size() - 3), lines.size()).forEach(t -> edge.add(normalize(t)));
                }
                edge.forEach(t -> textSeen.computeIfAbsent(t, k -> new TreeSet<>()).add(page));
            });

            // ---- ② 图片侧：包围盒取整后 → 出现在几页上 ----
            List<String> imageBoxes = new ArrayList<>();
            collectImageBoxes(root.get("kids"), 0, imageBoxes);

            sb.append("======== O3 干跑：").append(sample.getFileName())
                    .append("（").append(pages).append(" 页）========\n");
            sb.append("-- 文本侧：归一化后出现在 ≥").append(threshold(pages)).append(" 页的首/尾行 --\n");
            int[] textHits = {0};
            textSeen.forEach((text, pagesSeen) -> {
                if (pagesSeen.size() >= threshold(pages)) {
                    textHits[0]++;
                    sb.append(String.format("   [%2d/%d 页] %s%n", pagesSeen.size(), pages, clip(text)));
                }
            });
            if (textHits[0] == 0) {
                sb.append("   （无 —— 该样本没有可被 >60% 页规则判出的页眉页脚）\n");
            }
            sb.append("-- 图片侧：同一包围盒出现在 ≥2 页的 --\n");
            Map<String, Set<Integer>> boxSeen = new TreeMap<>();
            imageBoxes.forEach(s -> {
                int eq = s.indexOf(' ');
                String box = s.substring(eq + 1);
                int page = Integer.parseInt(s.substring(5, eq));
                boxSeen.computeIfAbsent(box, k -> new TreeSet<>()).add(page);
            });
            int[] boxHits = {0};
            boxSeen.forEach((box, pagesSeen) -> {
                if (pagesSeen.size() >= 2) {
                    boxHits[0]++;
                    sb.append(String.format("   [%d 页] %s（需 ≥%d 页才算）%n",
                            pagesSeen.size(), box, threshold(pages)));
                }
            });
            if (boxHits[0] == 0) {
                sb.append("   （无 —— 该样本没有跨页同位置的图）\n");
            }
        }
        dump("dry-run", sb.toString());
    }

    @Test
    @DisplayName("O3 步 0 干跑·原始行：逐页首/尾 3 行原样打印（判「样本到底有没有页眉页脚」）")
    void rawEdgeLinesPerPage() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(SAMPLE), "缺样本 " + SAMPLE);

        JsonNode root = runAndReadJson(SAMPLE);
        Map<Integer, List<String>> linesByPage = new TreeMap<>();
        collectTextLines(root.get("kids"), 0, linesByPage);

        StringBuilder sb = new StringBuilder();
        sb.append("O3 干跑·原始行：").append(SAMPLE.getFileName()).append('\n');
        linesByPage.forEach((page, lines) -> {
            sb.append("-- 第 ").append(page).append(" 页（共 ").append(lines.size()).append(" 行）--\n");
            for (int i = 0; i < Math.min(3, lines.size()); i++) {
                sb.append("   首").append(i + 1).append(": ").append(clip(lines.get(i))).append('\n');
            }
            if (lines.size() > 3) {
                for (int i = Math.max(3, lines.size() - 3); i < lines.size(); i++) {
                    sb.append("   尾").append(lines.size() - i).append(": ")
                            .append(clip(lines.get(i))).append('\n');
                }
            }
        });
        dump("raw-edge-lines", sb.toString());
    }

    // ==================================================================

    /**
     * 把取证写到 **UTF-8 文件**再读。
     *
     * <p>⚠️ 不写 {@code System.out}：本机控制台是 GBK，中文在 Maven → 终端这一跳
     * 会变成 `���`，探针就白跑了（第一次干跑就是这么废掉的）。文件是唯一可靠的落点。
     */
    private static void dump(String fileName, String content) throws Exception {
        Path p = Path.of("target", "o3-probe-" + fileName + ".txt").toAbsolutePath();
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        System.out.println("（取证已写 " + p + "）");
    }

    /** `>60% 页出现` 的页数门槛（向上取整 + 至少 2 页，否则单页文档也"跨页"了）。 */
    private static int threshold(int pages) {
        return Math.max(2, (int) Math.ceil(pages * 0.6));
    }

    private static String clip(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }

    /**
     * 页眉页脚的**归一化**：去掉页眉里唯一会变的那部分 —— 页码与日期。
     *
     * <p>这一步就是 `O3` §4 步 2 说的"归一化去数字"：`第 3 页` 与 `第 17 页`
     * 必须归并成同一个模式，否则逐页文本各不相同，连"完全重复"都算不上。
     */
    static String normalize(String text) {
        return text.replaceAll("\\s+", "")
                .replaceAll("[0-9第页Pp＃#]+", "#")
                .replaceAll("[-—–·•]+", "");
    }

    /** 递归收集「页码 → 该页**正文类**元素的文本，按文档顺序」。 */
    private static void collectTextLines(JsonNode kids, int inheritedPageNo,
                                         Map<Integer, List<String>> out) {
        if (kids == null || !kids.isArray()) {
            return;
        }
        for (JsonNode el : kids) {
            int pageNo = el.path("page number").asInt(inheritedPageNo);
            String type = el.path("type").asText();
            if (type.equals("heading") || type.equals("paragraph")) {
                String content = el.path("content").asText("").strip();
                if (!content.isEmpty()) {
                    out.computeIfAbsent(pageNo, k -> new ArrayList<>()).add(content);
                }
            }
            collectTextLines(el.get("kids"), pageNo, out);
        }
    }

    /** 递归收集图片包围盒，形如 {@code page=3 [90.0,380.8,504.2,611.6]}（坐标取整以容忍浮点抖动）。 */
    private static void collectImageBoxes(JsonNode kids, int inheritedPageNo, List<String> out) {
        if (kids == null || !kids.isArray()) {
            return;
        }
        for (JsonNode el : kids) {
            int pageNo = el.path("page number").asInt(inheritedPageNo);
            if ("image".equals(el.path("type").asText())) {
                JsonNode box = el.get("bounding box");
                if (box != null && box.isArray() && box.size() == 4) {
                    out.add("page=" + pageNo + " [" + box.get(0).asInt() + "," + box.get(1).asInt()
                            + "," + box.get(2).asInt() + "," + box.get(3).asInt() + "]");
                } else {
                    out.add("page=" + pageNo + " <无包围盒>");
                }
            }
            collectImageBoxes(el.get("kids"), pageNo, out);
        }
    }

    /** 递归收集 type → 该 type 出现过的全部字段名。 */
    private static void collectKeys(JsonNode kids, Map<String, Set<String>> byType) {
        if (kids == null || !kids.isArray()) {
            return;
        }
        for (JsonNode el : kids) {
            String type = el.path("type").asText("<none>");
            Set<String> keys = byType.computeIfAbsent(type, k -> new TreeSet<>());
            el.fieldNames().forEachRemaining(keys::add);
            collectKeys(el.get("kids"), byType);
        }
    }

    /** 递归收集 image 元素的「页码 + bounding box」。 */
    private static void collectImages(JsonNode kids, int inheritedPageNo, List<String> out) {
        if (kids == null || !kids.isArray()) {
            return;
        }
        for (JsonNode el : kids) {
            int pageNo = el.path("page number").asInt(inheritedPageNo);
            if ("image".equals(el.path("type").asText())) {
                JsonNode box = el.get("bounding box");
                out.add("page=" + pageNo + " box=" + (box == null || box.isNull() ? "<无>" : box.toString())
                        + " 其余字段=" + fieldsExcept(el, "type", "page number", "bounding box", "kids"));
            }
            collectImages(el.get("kids"), pageNo, out);
        }
    }

    private static String fieldsExcept(JsonNode el, String... exclude) {
        Set<String> skip = new LinkedHashSet<>(List.of(exclude));
        List<String> rest = new ArrayList<>();
        el.fieldNames().forEachRemaining(f -> {
            if (!skip.contains(f)) {
                rest.add(f + "=" + el.get(f));
            }
        });
        return rest.toString();
    }

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
