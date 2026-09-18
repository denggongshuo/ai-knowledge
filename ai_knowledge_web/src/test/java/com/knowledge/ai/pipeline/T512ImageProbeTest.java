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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-512` 步 0 的探针 —— **opendataloader 开了图输出之后到底吐出什么**。
 *
 * <h3>为什么先跑探针，而不是先写代码</h3>
 * <p>整条 `T-512` 的形状取决于四个事实，而这四个**没有一个是能从文档读出来的**：
 * <ol>
 *   <li><b>出图能力</b>：`IMAGE_OUTPUT_EXTERNAL` 真的会落文件吗？落在哪？文件名是什么？</li>
 *   <li><b>图元素带什么字段</b>：`DocElement.image` 现在只存页号，要补的是
 *       `id`（题注配对用）与 `bounding box`（几何）。它们**在 JSON 里存在吗**？</li>
 *   <li><b>题注配得上吗</b>：需求 `06:158` 把「题注优先」写成分片的**核心规则**，而
 *       `caption` 现在掉进 `default ->` 被丢掉。配对靠 `caption` 的“关联内容 id” ↔
 *       `image` 的 `id` —— **那条链在真实 JSON 里通不通**？</li>
 *   <li><b>磁盘代价多大</b>：图片输出被**刻意关掉**过，理由是「21.8MB 那类图多的手册
 *       开了会把产物撑到几百 MB」（`PdfDocumentParser` 调用纪律第 2 条）。
 *       掀掉那条纪律前，先把**真实数字**量出来。</li>
 * </ol>
 *
 * <p>本仓为"不量就定阈值"付过学费：`O2` §6 建议的「页级阈值 10」**被探针否掉**
 * （实测会让 4 份正常文档里的 7 个页面误报）。所以本探针的产出**允许是否掉当前假设**。
 *
 * <h3>它是不是"常驻用例"</h3>
 * <p>是 —— 与 `O3HeaderFooterProbeTest` 同一条理由：这四个字段名/能力是**外部库的契约**，
 * 升级 opendataloader 时它们可能变，而变了之后整条 `T-512` 会静默退化
 * （配不上题注 → 「题注优先」悄悄失效）。钉住它们比钉住我们自己写的代码更值。
 *
 * <p>⚠️ 样本在 `docs/测试文件/`（**gitignored**）⇒ 无样本的机器上**跳过**而不是失败。
 * 代价与 `O3HeaderFooterProbeTest` 一样要清楚：那时它是静默不跑的。
 */
class T512ImageProbeTest {

    private static final Path DIR = Path.of("..", "docs", "测试文件").toAbsolutePath().normalize();

    /**
     * 图最多的那份（`parse-fixtures.json` 记 `imageCount=75`）—— 磁盘代价要按**最坏情形**量。
     */
    private static final Path SAMPLE = DIR.resolve("1-DeepSeek使用.pdf");

    /** 21.8MB 那份 —— 原纪律点名的那一类手册，用来核对"几百 MB"那个说法还成不成立。 */
    private static final Path HEAVY = DIR.resolve("健康生活方式（指导手册）.pdf");

    @Test
    @DisplayName("T-512 步 0：出图能力 / 图元素字段 / 题注配对 / 磁盘代价")
    void imageOutputInventory() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(SAMPLE), "缺样本 " + SAMPLE);

        Probe probe = runWithImages(SAMPLE);

        System.out.println("======== T-512 步 0：图片输出实况 ========");
        System.out.println("样本 = " + SAMPLE.getFileName());
        System.out.println("页数 = " + probe.root.path("number of pages").asInt());
        System.out.println("image 元素 = " + probe.imageNodes.size());
        System.out.println("caption 元素 = " + probe.captionNodes.size());
        System.out.println("出图文件 = " + probe.imageFiles.size() + " 个，合计 "
                + mb(probe.imageBytes) + " MB");
        System.out.println("单图字节：max=" + kb(probe.maxBytes) + " KB · min=" + kb(probe.minBytes)
                + " KB · 中位=" + kb(probe.medianBytes()) + " KB");
        System.out.println("产物 JSON = " + kb(probe.jsonBytes) + " KB");
        System.out.println("------------ image 节点的字段名 ------------");
        System.out.println("  " + probe.imageFields);
        System.out.println("------------ caption 节点的字段名 ------------");
        System.out.println("  " + probe.captionFields);
        System.out.println("------------ 一个 image 节点原文（截断）------------");
        System.out.println("  " + truncate(probe.sampleImageNode, 400));
        if (probe.sampleCaptionNode != null) {
            System.out.println("------------ 一个 caption 节点原文（截断）------------");
            System.out.println("  " + truncate(probe.sampleCaptionNode, 400));
        }
        System.out.println("------------ 出图文件 ↔ 图元素（26 vs 75 那把钥匙）------------");
        System.out.println("  image 带 source 的 = " + probe.imageWithSource + " / " + probe.imageNodes.size());
        System.out.println("  source 去重后 = " + probe.distinctSources.size());
        System.out.println("  前 5 对 (id → source) = " + probe.sourceExamples);
        System.out.println("  实际落盘前 5 个文件名 = " + probe.imageFiles.stream()
                .limit(5).map(p -> p.getFileName().toString()).toList());
        // 🔴 决定性的一问：没有 source 的那 49 张，是**小图标**还是**真图**？
        //    这决定了 `image_total`（=解析出的图元素数）该不该改成"有字节的图数"
        System.out.println("  有 source 的包围盒面积：中位=" + fmt(probe.medianArea(true))
                + " · 最大=" + fmt(probe.maxArea(true)));
        System.out.println("  无 source 的包围盒面积：中位=" + fmt(probe.medianArea(false))
                + " · 最大=" + fmt(probe.maxArea(false)));
        System.out.println("  无 source 的面积分布（前 10 大）=" + probe.topAreasWithoutSource());
        System.out.println("------------ 题注配对 ------------");
        System.out.println("  image.id 有值的 = " + probe.imageWithId + " / " + probe.imageNodes.size());
        System.out.println("  caption 带关联 id 的 = " + probe.captionWithLink + " / " + probe.captionNodes.size());
        System.out.println("  能配上对的 = " + probe.paired);
        if (!probe.pairExample.isEmpty()) {
            System.out.println("  配对样例 = " + probe.pairExample);
        }
        if (!probe.captionExamples.isEmpty()) {
            System.out.println("------------ caption 文本样例（前 3 条）------------");
            probe.captionExamples.forEach(c -> System.out.println("  · " + truncate(c, 120)));
        }
        System.out.println("=========================================");

        // ---- 只钉"能力存在"。具体阈值留给判据 26 的量测结果去定 ----
        assertThat(probe.imageFiles)
                .as("`IMAGE_OUTPUT_EXTERNAL` 必须真的落文件 —— 否则抽图这条路不成立，"
                        + "整条 T-512 要换设计（例如引 PDFBox）")
                .isNotEmpty();
        assertThat(probe.imageNodes)
                .as("样本必须有 image 元素，否则本探针测不出东西")
                .isNotEmpty();
        assertThat(probe.imageFields.contains("id") || probe.imageFields.contains("bounding box"))
                .as("image 节点必须带 id 或 bounding box —— 它们是题注配对与几何判定的**全部输入**。"
                        + "两者都没有的话，需求 06:158 的「题注优先」与 T-O03 的②都无从实现。"
                        + "实测字段名 = %s", probe.imageFields)
                .isTrue();
    }

    @Test
    @DisplayName("T-512 步 0：原纪律说的「几百 MB」现在还成不成立（21.8MB 手册）")
    void heavySampleDiskCost() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(HEAVY), "缺样本 " + HEAVY);

        Probe probe = runWithImages(HEAVY);
        System.out.println("======== T-512 步 0：重样本磁盘代价 ========");
        System.out.println("样本 = " + HEAVY.getFileName());
        System.out.println("image 元素 = " + probe.imageNodes.size());
        System.out.println("出图合计 = " + mb(probe.imageBytes) + " MB · 单图 max = " + kb(probe.maxBytes) + " KB");
        System.out.println("JSON = " + kb(probe.jsonBytes) + " KB");
        System.out.println("=========================================");

        assertThat(probe.imageBytes)
                .as("量与结论都要留痕：原纪律的理由是「会把产物撑到几百 MB」。"
                        + "本断言只保证**量到了**，不预设它一定超标 —— "
                        + "**探针的产出允许是否掉那句老话**")
                .isGreaterThanOrEqualTo(0L);
    }

    // ==================================================================
    // 探针本体
    // ==================================================================

    /** 一次带图输出的解析，及其盘点结果。 */
    private static final class Probe {
        private JsonNode root;
        private int jsonBytes;
        private final List<Path> imageFiles = new ArrayList<>();
        private long imageBytes;
        private long maxBytes;
        private long minBytes = Long.MAX_VALUE;
        private final List<Long> sizes = new ArrayList<>();
        private final List<JsonNode> imageNodes = new ArrayList<>();
        private final List<JsonNode> captionNodes = new ArrayList<>();
        private final Set<String> imageFields = new LinkedHashSet<>();
        private final Set<String> captionFields = new LinkedHashSet<>();
        private String sampleImageNode = "";
        private String sampleCaptionNode;
        private int imageWithId;
        private int captionWithLink;
        private int paired;
        private int imageWithSource;
        private final Set<String> distinctSources = new LinkedHashSet<>();
        private final List<String> sourceExamples = new ArrayList<>();
        /** 有/无 `source` 两组的包围盒面积（宽×高），用来判"没导出的那些是不是小图标"。 */
        private final List<Double> areasWithSource = new ArrayList<>();
        private final List<Double> areasWithoutSource = new ArrayList<>();

        private double medianArea(boolean withSource) {
            List<Double> src = withSource ? areasWithSource : areasWithoutSource;
            if (src.isEmpty()) {
                return 0d;
            }
            List<Double> sorted = new ArrayList<>(src);
            sorted.sort(Comparator.naturalOrder());
            return sorted.get(sorted.size() / 2);
        }

        private double maxArea(boolean withSource) {
            List<Double> src = withSource ? areasWithSource : areasWithoutSource;
            return src.stream().mapToDouble(Double::doubleValue).max().orElse(0d);
        }

        private List<String> topAreasWithoutSource() {
            List<Double> sorted = new ArrayList<>(areasWithoutSource);
            sorted.sort(Comparator.reverseOrder());
            return sorted.stream().limit(10).map(T512ImageProbeTest::fmt).toList();
        }
        private String pairExample = "";
        private final List<String> captionExamples = new ArrayList<>();

        private long medianBytes() {
            if (sizes.isEmpty()) {
                return 0L;
            }
            List<Long> sorted = new ArrayList<>(sizes);
            sorted.sort(Comparator.naturalOrder());
            return sorted.get(sorted.size() / 2);
        }
    }

    private static Probe runWithImages(Path sample) throws Exception {
        Path outDir = Files.createTempDirectory("kb-t512-probe-");
        try {
            Config config = new Config();
            config.setOutputFolder(outDir.toString());
            config.setGenerateMarkdown(true);
            config.setGenerateJSON(true);
            config.setGenerateText(false);
            config.setGenerateHtml(false);
            // ---- 探针与生产**故意不同**的唯一一处 ----
            config.setImageOutput(Config.IMAGE_OUTPUT_EXTERNAL);
            config.setImageFormat(Config.IMAGE_FORMAT_JPEG);
            config.setTableMethod(Config.TABLE_METHOD_DEFAULT);
            config.setKeepLineBreaks(true);
            OpenDataLoaderPDF.processFile(sample.toAbsolutePath().toString(), config);

            Probe probe = new Probe();
            try (Stream<Path> s = Files.walk(outDir)) {
                for (Path p : s.filter(Files::isRegularFile).toList()) {
                    String name = p.getFileName().toString().toLowerCase();
                    if (name.endsWith(".json")) {
                        probe.jsonBytes = (int) Files.size(p);
                        probe.root = new ObjectMapper()
                                .readTree(Files.readString(p, StandardCharsets.UTF_8));
                    } else if (name.endsWith(".jpg") || name.endsWith(".jpeg")
                            || name.endsWith(".png")) {
                        long size = Files.size(p);
                        probe.imageFiles.add(p);
                        probe.imageBytes += size;
                        probe.sizes.add(size);
                        probe.maxBytes = Math.max(probe.maxBytes, size);
                        probe.minBytes = Math.min(probe.minBytes, size);
                    }
                }
            }
            if (probe.root == null) {
                throw new IllegalStateException("探针没拿到 JSON");
            }
            walk(probe.root.get("kids"), probe);
            // 盘点要走配对 —— 不然 `paired`/`pairExample` 恒为 0/空，
            // 而"题注配不上"正是本探针最可能否掉整条设计的地方（不能静默漏掉）
            pairCaptionsByImageId(probe);
            return probe;
        } finally {
            // 与生产同一条纪律：临时产物 `finally` 必删（漏删会让磁盘按"每篇一份"的速度漏）
            try (Stream<Path> s = Files.walk(outDir)) {
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

    /** 递归收 image / caption 节点，记下字段名与配对情况。 */
    private static void walk(JsonNode nodes, Probe probe) {
        if (nodes == null || !nodes.isArray()) {
            return;
        }
        for (JsonNode node : nodes) {
            String type = node.path("type").asText("");
            if ("image".equals(type)) {
                probe.imageNodes.add(node);
                node.fieldNames().forEachRemaining(probe.imageFields::add);
                if (probe.sampleImageNode.isEmpty()) {
                    probe.sampleImageNode = node.toPrettyString();
                }
                if (hasText(node.get("id"))) {
                    probe.imageWithId++;
                }
                // `source` 是「这张图导出成了哪个文件」的线索 —— 它解释"图元素 75 个
                // 而只出了 26 个文件"。有值即计数，另存去重集合与前几对样例
                JsonNode source = node.get("source");
                boolean exported = hasText(source);
                if (exported) {
                    probe.imageWithSource++;
                    probe.distinctSources.add(source.asText());
                    if (probe.sourceExamples.size() < 5) {
                        probe.sourceExamples.add(node.get("id") + " → " + source.asText());
                    }
                }
                double area = areaOf(node.get("bounding box"));
                (exported ? probe.areasWithSource : probe.areasWithoutSource).add(area);
            } else if ("caption".equals(type)) {
                probe.captionNodes.add(node);
                node.fieldNames().forEachRemaining(probe.captionFields::add);
                if (probe.sampleCaptionNode == null) {
                    probe.sampleCaptionNode = node.toPrettyString();
                }
                String link = linkIdOf(node);
                if (link != null) {
                    probe.captionWithLink++;
                }
                String text = textOf(node);
                if (text != null && !text.isBlank() && probe.captionExamples.size() < 3) {
                    probe.captionExamples.add(text);
                }
            }
            walk(node.get("kids"), probe);
        }
    }

    /**
     * 题注挂到哪张图。
     *
     * <p>⚠️ 字段名**故意写成"试几个候选"**：本探针的首要任务就是**把真名找出来**
     * （O3 探针的先例：`image` 的包围盒字段实测叫 `bounding box`，带空格的）。
     * 找不到时返回 {@code null}，由探针把 `captionFields` 全量打出来给人看 ——
     * 那比 "assert 失败" 有用得多。
     */
    private static String linkIdOf(JsonNode caption) {
        for (String key : new String[]{"linked content id", "linkedContentId", "link id",
                "target id", "id"}) {
            JsonNode v = caption.get(key);
            if (hasText(v)) {
                return v.asText();
            }
        }
        return null;
    }

    /** 包围盒 `[x0,y0,x1,y1]` → 面积（宽 × 高）。缺失时返回 0（当成最小，不参与"是否小图标"的判断）。 */
    private static double areaOf(JsonNode box) {
        if (box == null || !box.isArray() || box.size() < 4) {
            return 0d;
        }
        double width = Math.abs(box.get(2).asDouble() - box.get(0).asDouble());
        double height = Math.abs(box.get(3).asDouble() - box.get(1).asDouble());
        return width * height;
    }

    private static String fmt(double value) {
        return String.format("%.0f", value);
    }

    private static String textOf(JsonNode node) {
        JsonNode content = node.get("content");
        return content == null || content.isNull() ? null : content.asText();
    }

    private static boolean hasText(JsonNode node) {
        return node != null && !node.isNull() && !node.asText("").isBlank();
    }

    private static String mb(long bytes) {
        return String.format("%.2f", bytes / 1024.0 / 1024.0);
    }

    private static String kb(long bytes) {
        return String.format("%.1f", bytes / 1024.0);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "—";
        }
        String flat = text.replace('\n', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    /**
     * 按 `image.id` ↔ `caption` 的关联 id 配对，并**把结果写回 probe**。
     *
     * <p>返回配对表（下一步真正实现 `ImageInfo` 时按它取题注）。
     */
    private static Map<String, String> pairCaptionsByImageId(Probe probe) {
        Map<String, String> byId = new LinkedHashMap<>();
        for (JsonNode caption : probe.captionNodes) {
            String link = linkIdOf(caption);
            if (link == null) {
                continue;
            }
            String text = textOf(caption);
            if (text != null && !text.isBlank()) {
                byId.putIfAbsent(link, text);
            }
        }
        Map<String, String> paired = new LinkedHashMap<>();
        for (JsonNode image : probe.imageNodes) {
            String id = image.get("id") == null ? null : image.get("id").asText();
            if (id != null && byId.containsKey(id)) {
                paired.put(id, byId.get(id));
            }
        }
        probe.paired = paired.size();
        if (!paired.isEmpty()) {
            Map.Entry<String, String> first = paired.entrySet().iterator().next();
            probe.pairExample = first.getKey() + " → " + truncate(first.getValue(), 80);
        }
        return paired;
    }
}
