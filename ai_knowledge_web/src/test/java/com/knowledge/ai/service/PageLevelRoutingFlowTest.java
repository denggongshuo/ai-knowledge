package com.knowledge.ai.service;

import com.knowledge.ai.core.async.ParseWorker;
import com.knowledge.ai.support.ApiTestSupport;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `优化/O2` 的**端到端取证**（`S4-E2` 判据 1/2/4）——
 * 混合文档走**真 HTTP + 真解析器**，看它在库与接口上的样子。
 *
 * <h3>为什么必须真造一份混合 PDF</h3>
 * <p>`docs/测试文件/` 里**没有任何混合样本**（5 份 PDF 要么整篇有文本层、要么整篇是扫描件）。
 * 而 `O2` §5 判据 1 要的正是"38 页文本 + 2 页扫描图"那种**混进来**的形态 ——
 * 用单元级构造 `new ParsedDocument(...)` 只能验服务层逻辑，验不到
 * "opendataloader 面对一份真混合 PDF 时，逐页 pageNo 确实分得开"。
 *
 * <p>所以用 **pdfbox**（Tika 的传递依赖，已在 classpath，**不引新依赖**）合成：
 * 从一份有文本层的真 PDF 抽 3 页正文 + 从整篇扫描件抽 1 页 + 造 1 页真空白页。
 * {@code importPage} 而不是 {@code addPage} —— 后者会得到一个**空白壳**
 * （页数看着对，内容全丢），那正是合成夹具最容易踩的坑。
 *
 * <h3>它覆盖的三条判据</h3>
 * <ol>
 *   <li><b>判据 1</b>：有文本的页仍正常进产物（{@code pages_with_text} 如实）且文档**没有**被判失败；</li>
 *   <li><b>判据 2</b>：那 1 页扫描页出现在 issue 清单里，且 {@code kind}/{@code disposition}
 *       是**列**（`V18`）而不是 reason 里的文字前缀；</li>
 *   <li><b>判据 4</b>：真空白页归 {@code EMPTY_PAGE} —— **不计入**"内容缺失"。</li>
 * </ol>
 */
class PageLevelRoutingFlowTest extends ApiTestSupport {

    private static final Path SAMPLE_DIR = Path.of("..", "docs", "测试文件").toAbsolutePath().normalize();

    /** 有文本层的样本（取中间几页 —— 封面页字符太少，会让文档级密度判据擦边）。 */
    private static final String TEXT_SAMPLE = "1-DeepSeek使用.pdf";

    /** 整篇扫描件（抽它的第 1 页当"扫描插页"）。 */
    private static final String SCANNED_SAMPLE = "健康生活方式（指导手册）.pdf";

    /** ⚠️ 必须以 `kb_` 开头、只用小写字母/数字/下划线 —— 否则建库直接 `10030`。 */
    private static final String PROBE_PREFIX = "kb_probe_e2page_";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ParseWorker parseWorker;

    @Autowired
    private com.knowledge.ai.core.storage.DocStorage docStorage;

    private final List<Long> probeKbIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long kbId : probeKbIds) {
            for (Map<String, Object> row : jdbcTemplate.queryForList(
                    "select storage_path, parse_path from sys_document where kb_id = ?", kbId)) {
                // 对象存储里的两份产物要一起清 —— 本仓的清理纪律：不清就会累积到
                // 桶里上千个对象，把毫不相干的 GET /kb/storage-quota 拖红（DocParseFlowTest 的注释记过）
                removeObject(row.get("storage_path"));
                removeObject(row.get("parse_path"));
            }
            jdbcTemplate.update("delete from sys_chunk where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_parse_issue where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_document where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_kb_grant where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_kb where id = ?", kbId);
        }
        probeKbIds.clear();
    }

    private void removeObject(Object objectKey) {
        if (objectKey != null && !objectKey.toString().isBlank()) {
            docStorage.remove(objectKey.toString());
        }
    }

    @Test
    @DisplayName("O2 判据 1/2/4：混合文档（文本页 + 扫描页 + 空白页）不再静默丢页")
    void mixedDocumentRoutesPerPage() throws Exception {
        Path text = SAMPLE_DIR.resolve(TEXT_SAMPLE);
        Path scanned = SAMPLE_DIR.resolve(SCANNED_SAMPLE);
        Assumptions.assumeTrue(Files.isRegularFile(text), "缺样本 " + TEXT_SAMPLE);
        Assumptions.assumeTrue(Files.isRegularFile(scanned), "缺样本 " + SCANNED_SAMPLE);

        Path mixed = composeMixed(text, scanned);
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        try {
            byte[] pdf = Files.readAllBytes(mixed);
            RawResponse accepted = postMultipart("/api/kb/" + kbId + "/doc/upload", pdf,
                    "混合文档探针.pdf", "application/pdf", token).assertOk("上传混合文档");
            long docId = ((Number) accepted.data("$.docId")).longValue();
            awaitTaskDone(accepted.data("$.taskId"), token);
            parseWorker.drainOnce();   // 测试期后台轮询是关的，显式驱动一轮

            Map<String, Object> doc = jdbcTemplate.queryForMap(
                    "select status, error_msg, pages_total, pages_with_text from sys_document where id = ?",
                    docId);

            // ---- 判据 1：文档**没有**被判失败（有文本的 3 页仍在产物里）----
            assertThat(doc.get("status"))
                    .as("混合文档的文档级判据必须放行 —— 它挡住的是「整篇不可用」那一种。"
                            + "若这里成了 FAILED，说明页级逻辑把文档级兜底也搅了")
                    .isIn("INDEXING", "READY");
            assertThat(doc.get("error_msg")).isNull();
            assertThat(((Number) doc.get("pages_total")).intValue())
                    .as("3 页文本 + 1 页扫描 + 1 页空白")
                    .isEqualTo(5);
            assertThat(((Number) doc.get("pages_with_text")).intValue())
                    .as("质量列要能说出「5 页里 3 页有文本」—— 平均值把丢页稀释成了「每页字有点少」。"
                            + "这一条同时证明 PdfDocumentParser 的 pageNo 是真的分得开的："
                            + "若 opendataloader 没给逐页页码，所有元素都会落在第 1 页、这里会是 1")
                    .isEqualTo(3);

            // ---- 判据 2 + 4：两页零字页都登记了，但**处置不同** ----
            List<Map<String, Object>> issues = jdbcTemplate.queryForList(
                    "select page_no, kind, disposition, reason from sys_parse_issue "
                            + "where doc_id = ? order by page_no", docId);
            assertThat(issues).hasSize(2);

            Map<String, Object> scannedIssue = issues.get(0);
            assertThat(((Number) scannedIssue.get("page_no")).intValue())
                    .as("扫描页是第 4 页 —— 页码必须是真的（G4-11 点名要「第几页」）")
                    .isEqualTo(4);
            assertThat(scannedIssue.get("kind")).isEqualTo("NO_TEXT_LAYER");
            assertThat(scannedIssue.get("disposition"))
                    .as("v1 不做 OCR → 处置就是「未处理」；它不是 reason 里的文字前缀，而是**列**")
                    .isEqualTo("MISSING");
            assertThat((String) scannedIssue.get("reason"))
                    .as("reason 回归**纯原因文本**（种类有列了，再拼前缀就是同一信息存两份、必然漂）")
                    .doesNotContain("【");

            Map<String, Object> blankIssue = issues.get(1);
            assertThat(blankIssue.get("kind"))
                    .as("真空白页（零字**无图**）不能与扫描页合并 —— 合并等于把"
                            + "「可能是版式页」说成「是扫描页」，而后者会进告警条")
                    .isEqualTo("EMPTY_PAGE");
            assertThat(blankIssue.get("disposition")).isEqualTo("EMPTY_PAGE");

            // ---- 接口侧：两个字段真的下发（前端按 disposition 分流）----
            RawResponse page = get("/api/kb/" + kbId + "/parse-issues", token).assertOk("取问题清单");
            List<Map<String, Object>> rows = page.data("$.list");
            assertThat(rows).hasSize(2);
            assertThat(rows).allSatisfy(r -> {
                assertThat(r).containsKeys("docId", "docTitle", "pageNo", "reason", "kind", "disposition");
                assertThat(r.get("kind")).isNotNull();
                assertThat(r.get("disposition")).isNotNull();
            });
        } finally {
            Files.deleteIfExists(mixed);
        }
    }

    // ==================================================================
    // 夹具：合成"文本页 + 扫描页 + 空白页"
    // ==================================================================

    /**
     * 合成一份混合 PDF。
     *
     * <p>取文本样本的**第 9/10/20 页**（实测 394 / 232 / 163 字）而不是前 3 页 ——
     * 前 3 页是封面与目录页（8 / 83 / 8 字），合计只有 99 字 / 5 页 = 19.8 字/页，
     * 会**擦着**文档级密度阈值（20）过 —— 那种夹具一旦别人换了样本就红，
     * 而红的原因与它要验的东西毫无关系。
     */
    private Path composeMixed(Path textSample, Path scannedSample) throws Exception {
        Path out = Files.createTempFile("e2-mixed-", ".pdf");
        try (PDDocument target = new PDDocument()) {
            try (PDDocument src = Loader.loadPDF(textSample.toFile())) {
                for (int pageIndex : new int[]{8, 9, 19}) {          // 0-based → 第 9/10/20 页
                    target.importPage(src.getPage(pageIndex));
                }
            }
            try (PDDocument src = Loader.loadPDF(scannedSample.toFile())) {
                target.importPage(src.getPage(0));                    // 整页扫描图 → 零字 + 有图
            }
            target.addPage(new PDPage());                             // 真空白页 → 零字 + 无图
            target.save(out.toFile());
        }
        return out;
    }

    private void awaitTaskDone(String taskId, String token) {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            RawResponse t = get("/api/doc/tasks/" + taskId, token).assertOk("轮询任务");
            Integer percent = t.data("$.percent");
            if (percent != null && percent >= 100) {
                return;
            }
            sleep(150);
        }
        throw new AssertionError("任务 120s 内未到终态：" + taskId);
    }

    private long createProbeKb(String token) {
        String code = PROBE_PREFIX + Long.toString(System.nanoTime(), 36);
        Map<String, Object> form = new HashMap<>();
        form.put("name", "E2 页级探针库");
        form.put("code", code);
        form.put("deptId", 1);
        form.put("visibleRoles", List.of("SUPER_ADMIN", "USER"));
        form.put("secretLevel", "INTERNAL");
        form.put("businessDomain", "研发 / 平台");
        form.put("description", "");
        form.put("customTags", List.of());
        form.put("splitPolicy", Map.of("preset", "GENERAL", "chunkSize", 512, "overlap", 64,
                "recursiveSplit", true, "semanticSplit", false));
        form.put("tablePolicy", Map.of("atomicTable", true, "dualRepresentation", true,
                "repeatHeader", true, "mergeCrossPage", true, "ocrTable", true,
                "rowLimit", 30, "rowWindow", 5));
        form.put("retrievalConfig", Map.of("mode", "HYBRID", "recallThreshold", 0.15,
                "rerankTopK", 5, "rerankEnabled", true, "tableWeight", 1.2, "identifierProtect", true));
        RawResponse created = post("/api/kb", form, token).assertOk("建探针库");
        long kbId = ((Number) created.data("$.id")).longValue();
        probeKbIds.add(kbId);
        return kbId;
    }
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", ex);
        }
    }
}
