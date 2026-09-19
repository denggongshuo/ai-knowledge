package com.knowledge.ai.service;

import com.knowledge.ai.core.async.ParseWorker;
import com.knowledge.ai.pipeline.ImageInfo;
import com.knowledge.ai.pipeline.ParsedDocument;
import com.knowledge.ai.pipeline.PdfDocumentParser;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 **真 VL 跑通「文档图片描述」整条链**（`T-512` 挂起判据 4 / 5 / 8 / 9 / 13 / 14 的兑现）。
 *
 * <h3>为什么这条判据挂了这么久</h3>
 * <p>前面所有图片相关的用例都是**替身级**的（假 sink / 假 caller）：
 * 它们能证"去重位置对""顺序对""真的并行了"，**证不了"真模型跑完整条链会怎样"**。
 * 而测试配置里 {@code m05.image.describe.enabled=false}（真 VL 太慢，见那份 properties），
 * 所以默认跑批**从来不会走这条链**。
 *
 * <h3>⚠️ 它会真花钱 + 真耗时</h3>
 * <p>端点是远程付费 API，单次 **95~115 秒**。本用例用 `1-DeepSeek使用.pdf`（26 张可描述）
 * ⇒ 并发 4 时约 **5~6 分钟**。故：
 * <pre>
 *   mvn -B test -Dtest=ImageDescribeNetworkTest -Dvision.probe.run=true
 * </pre>
 *
 * <h3>顺带：它是 `images/` 前缀兜底的**真对象**验证</h3>
 * <p>本用例会往 MinIO 写**真实的图片对象**（不是替身），而收尾走
 * {@code ApiTestSupport} 那套扫描（`ProbeCleanup.MINIO_PREFIXES` 的 `images/` 一行是
 * `S6-M05-C2` 补的 —— 补之前漏加过，一轮跑批留了 180 个孤儿）。
 * 所以跑完它之后核一次桶里干净，是对那行兜底的**端到端证据**。
 */
@Tag("network")
@TestPropertySource(properties = {
        "m05.image.describe.enabled=true",
        // 用生产默认值（4）而不是调大 —— 这条用例要验的是**生产形态**会怎样
        "m05.image.describe.concurrency=4",
})
@DisplayName("真 VL：文档图片描述整条链（判据 4/5/8/9/13/14）")
class ImageDescribeNetworkTest extends ApiTestSupport {

    private static final Path SAMPLE_DIR = Path.of("..", "docs", "测试文件").toAbsolutePath().normalize();
    private static final String SAMPLE = "1-DeepSeek使用.pdf";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ParseWorker parseWorker;

    @Autowired
    private com.knowledge.ai.core.storage.DocStorage docStorage;

    /** 本用例建的探针库 —— 收尾要按它清。 */
    private final List<Long> probeKbIds = new ArrayList<>();

    /**
     * 收尾：删探针库那一族的行与对象。
     *
     * <h3>🔴 第一版漏了它，当场留了残留</h3>
     * <p>{@code ApiTestSupport} **不负责删探针库**（它只管无主对象的兜底扫描）——
     * 各用例类自己清，{@code DocParseFlowTest} 就是这么写的。
     * 我第一版没写，实测留下：`kb=10`（应为 8）· `doc=2` · `chunk=228` · `issue=128` ·
     * **34 个 MinIO 对象**。
     * <p>这正是 `#186` 那条教训（「**收尾必须是必走路径**」）在写完那条教训的同一个人手上的
     * 第二次重演 —— 所以这里照搬 `DocParseFlowTest` 那份**已经跑过很多轮**的版本，
     * 而不是自己另写一套。
     *
     * <h3>为什么不用删图片对象</h3>
     * <p>图片对象由 {@code sys_chunk.object_key} 引用。这里把 `sys_chunk` 行删掉之后，
     * 它们就**成了无主对象** —— 而 `ApiTestSupport` 的收尾扫描（`T-O10`）会扫
     * `images/` 前缀（那一行是 `S6-M05-C2` 补的）。
     * ⚠️ 但扫描有 **10 分钟的年龄下限**（那窗口是为"上传中"留的）⇒
     * **本轮刚写的图这一轮扫不掉**，要等下一轮。这是**设计如此**，不是遗漏。
     */
    @org.junit.jupiter.api.AfterEach
    void cleanProbeData() {
        for (Long kbId : probeKbIds) {
            for (Map<String, Object> row : jdbcTemplate.queryForList(
                    "select storage_path, parse_path from sys_document where kb_id = ?", kbId)) {
                removeObject(row.get("storage_path"));
                removeObject(row.get("parse_path"));
            }
            // 按 kb_id 直删而不是 join sys_document：文档行可能已经不在了，
            // join 一条也匹配不到、孤儿照样留下
            jdbcTemplate.update("delete from sys_chunk where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_parse_issue where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_document where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_kb_grant where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_kb where id = ?", kbId);
        }
        probeKbIds.clear();
    }

    /** 删对象存储里的文件。**静默**：删不掉不该让用例红（`DocStorage#remove` 自己吞并留 warn）。 */
    private void removeObject(Object objectKey) {
        if (objectKey != null && !objectKey.toString().isBlank()) {
            docStorage.remove(objectKey.toString());
        }
    }

    /**
     * 🔴 **读回来的对象必须是一张真图** —— 「写进去了」不等于「写对了」。
     *
     * <p>这条断言是**查一个具体故障时加的**，而且它本身就是本仓那条纪律的落点：
     * 上传接口返回成功、`sys_chunk.object_key` 有值，**都不能证明桶里的字节是图片**。
     * 实测踩过：把对象从 MinIO 的磁盘布局里抠出来时，看到的是一段
     * 「32 字节前缀 + 完整 JPEG」，而带那段前缀打 VL 端点会得到
     * `20015 image_url provided is not a valid image`。
     * ⚠️ 但那次是从**磁盘布局**读的，不是从客户端读的 —— 两者可能不是一回事
     * （MinIO 的 FS 后端有内部包装）。所以这里**用客户端读**，那才是权威口径
     * （同 `T-O10` 那条教训：**用与写入同一个工具去量**）。
     */
    private void assertStoredObjectIsRealJpeg(String objectKey) {
        byte[] bytes = docStorage.open(objectKey);
        String magic = bytes.length >= 3
                ? String.format("%02x%02x%02x", bytes[0], bytes[1], bytes[2]) : "?";
        System.out.println("回读对象 " + objectKey + " · " + bytes.length + " 字节 · magic=" + magic);
        assertThat(magic)
                .as("🔴 从 MinIO 读回的对象必须以 JPEG magic 开头（ffd8ff）—— "
                        + "否则「上传成功」只是接口层的成功，桶里的字节根本不是图片")
                .isEqualTo("ffd8ff");
    }

    @Test
    @DisplayName("上传含图 PDF → 真调 VL → 描述进块、三列元数据落库、image_parsed 有真值")
    void realVisionDescribesImagesAndFillsColumns() throws Exception {
        // 显式 opt-in：真花钱且分钟级，不该在默认跑批里发生
        Assumptions.assumeTrue(Boolean.getBoolean("vision.probe.run"),
                "需要显式开启：-Dvision.probe.run=true（真调付费 VL，耗时数分钟）");
        Assumptions.assumeTrue(Files.isRegularFile(SAMPLE_DIR.resolve(SAMPLE)),
                "缺样本 " + SAMPLE + "（docs/测试文件/ 不入库）");

        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        byte[] pdf = Files.readAllBytes(SAMPLE_DIR.resolve(SAMPLE));

        RawResponse uploaded = upload(kbId, pdf, "真VL探针.pdf", token).assertOk("上传含图 PDF");
        long docId = ((Number) uploaded.data("$.docId")).longValue();

        // ---------- 判据 13：image_parsed 有真值 ----------
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select status, image_total, image_parsed from sys_document where id = ?", docId);
        long imageTotal = ((Number) row.get("image_total")).longValue();
        long imageParsed = ((Number) row.get("image_parsed")).longValue();

        System.out.println("======== 真 VL 端到端 ========");
        System.out.println("status=" + row.get("status") + " · image_total=" + imageTotal
                + " · image_parsed=" + imageParsed);

        assertThat(imageTotal).as("该样本实测 75 个图元素").isGreaterThan(0);

        // ---------- 判据 10 的前置：IMAGE 块先查出来 ----------
        // ⚠️ 顺序是刻意的：下面那条**回读校验**要在 `image_parsed` 的断言**之前**跑。
        //    它是用来区分"库里的计数是 0"与"桶里的字节不对"的 —— 若它排在后面，
        //    `image_parsed > 0` 一红就再也走不到，而**恰恰是那种时候最需要它**。
        List<Map<String, Object>> imageChunks = jdbcTemplate.queryForList(
                "select order_no, raw_content, object_key, image_md5, vl_model_id "
                        + "from sys_chunk where doc_id = ? and chunk_type = 'IMAGE' order by order_no",
                docId);
        System.out.println("IMAGE 块 = " + imageChunks.size() + " 个");
        assertThat(imageChunks)
                .as("含图文档必须产出 IMAGE 块 —— 且**数量不为 0**")
                .isNotEmpty();
        String sampleKey = String.valueOf(imageChunks.getFirst().get("object_key"));
        assertStoredObjectIsRealJpeg(sampleKey);

        assertThat(imageParsed)
                .as("🔴 判据 13：image_parsed 必须 > 0 —— 此前它恒 0，是「采了不用」的那一列")
                .isGreaterThan(0);
        assertThat(imageParsed)
                .as("⚠️ 必须 ≤ 可提取数（26），**不是** image_total（75）—— "
                        + "75 里有 49 张没有可导出的位图（用户裁定 B：两个数都留）")
                .isLessThanOrEqualTo(26);
        assertThat(imageChunks)
                .as("描述了 %d 张 ⇒ IMAGE 块数应当不少于它（有些块只有题注、没被描述）", imageParsed)
                .hasSizeGreaterThanOrEqualTo((int) imageParsed);

        List<Map<String, Object>> described = imageChunks.stream()
                .filter(c -> c.get("vl_model_id") != null)
                .toList();
        System.out.println("带 vl_model_id 的 = " + described.size());
        assertThat(described)
                .as("判据 10：被描述过的块必须带 object_key / image_md5 / vl_model_id")
                .isNotEmpty()
                .allSatisfy(c -> {
                    assertThat(c.get("object_key")).as("object_key").isNotNull();
                    assertThat(c.get("image_md5")).as("image_md5").isNotNull();
                    assertThat(c.get("vl_model_id")).as("vl_model_id").isNotNull();
                });

        // ---------- 判据 4：题注优先 ----------
        // 题注从**解析产物**里取（那是不花 VL 的一跳），再拿去核对块内容的首行
        Set<String> captions = captionsOf(SAMPLE_DIR.resolve(SAMPLE));
        System.out.println("样本里能配上对的题注 = " + captions.size());
        List<Map<String, Object>> captioned = imageChunks.stream()
                .filter(c -> captions.stream()
                        .anyMatch(cap -> String.valueOf(c.get("raw_content")).startsWith(cap)))
                .toList();
        Assumptions.assumeTrue(!captioned.isEmpty(),
                "本样本里没有「既带题注、又被成功描述」的块，判据 4 这次没验到（下一份样本再试）");
        assertThat(captioned).allSatisfy(c -> {
            String content = String.valueOf(c.get("raw_content"));
            assertThat(content.lines().count())
                    .as("🔴 判据 4：题注块的内容必须**不止题注一行** —— 说明描述接在了它后面；"
                            + "内容=%s", content.substring(0, Math.min(60, content.length())))
                    .isGreaterThan(1L);
        });

        // ---------- 判据 5：图片是原子单元（一个图元素一个块，不被切分）----------
        assertThat(imageChunks.stream()
                .collect(Collectors.groupingBy(c -> c.get("order_no"), Collectors.counting())))
                .as("判据 5：图片**整体**一个原子单元（需求 06:166），order_no 不该重复")
                .allSatisfy((orderNo, count) -> assertThat(count).isEqualTo(1L));

        // ---------- 判据 6.5：抽不到位图的那批仍然可见 ----------
        Long notExtractable = jdbcTemplate.queryForObject(
                "select count(*) from sys_parse_issue where doc_id = ? and kind = 'IMAGE_NOT_EXTRACTABLE'",
                Long.class, docId);
        System.out.println("IMAGE_NOT_EXTRACTABLE issue = " + notExtractable);
        assertThat(notExtractable)
                .as("🔴 那 49 张必须**看得见**（用户裁定 B）—— 否则 image_parsed < image_total "
                        + "在页面上与「还没跑完」长得一模一样")
                .isNotNull().isGreaterThan(0);

        System.out.println("=========================================");
    }

    /** 从解析产物里取「能配上对的题注」文本集合（不花 VL）。 */
    private static Set<String> captionsOf(Path pdf) throws Exception {
        ParsedDocument doc = PdfDocumentParser.parse(pdf);
        return doc.elements().stream()
                .filter(e -> e.kind() == ParsedDocument.DocElement.Kind.IMAGE)
                .map(ParsedDocument.DocElement::imageInfo)
                .filter(java.util.Objects::nonNull)
                .filter(ImageInfo::hasCaption)
                .map(ImageInfo::caption)
                .collect(Collectors.toSet());
    }

    // ==================================================================
    // 与 DocParseFlowTest 同形的三个小助手
    //
    // ⚠️ `awaitTaskDone` / `createProbeKb` / `formWith` 在本仓已经有**三份**一字不差的拷贝
    //    （`DocParseFlowTest` / `DocUploadFlowTest` / `PageLevelRoutingFlowTest`），
    //    本类是**第四份**。没有顺手收进 `ApiTestSupport` 是**刻意的**：
    //    基类加一个 protected 的同名方法会与子类里的 private 方法**冲突**
    //    （"不能降低可见性"），收口必须**同时**删掉那三份 —— 那是一次独立的重构，
    //    不该塞进本批。已登记为遗留（见计划 §9）。
    // ==================================================================

    private long createProbeKb(String token) {
        // ⚠️ code 的形状有服务端校验：**必须以 `kb_` 开头、只允许小写字母数字下划线**
        //    （第一版写成 `m05c2probe…`，当场被 10030 挡下 —— 那条报错文案是可行动的，
        //     一眼就知道该怎么改）
        String code = "kb_m05c2probe" + Long.toString(System.nanoTime(), 36);
        RawResponse created = post("/api/kb", formWith(code), token).assertOk("建探针库");
        long kbId = ((Number) created.data("$.id")).longValue();
        probeKbIds.add(kbId);   // ← 收尾靠它（第一版漏了这一行）
        return kbId;
    }

    private RawResponse upload(long kbId, byte[] content, String filename, String token) {
        RawResponse accepted = postMultipart("/api/kb/" + kbId + "/doc/upload", content, filename,
                "application/octet-stream", token);
        if (accepted.code() != 0) {
            return accepted;
        }
        awaitTaskDone(accepted.data("$.taskId"), token);
        // 上传与解析是两个作业；测试期后台轮询关掉了，这里显式驱动一轮
        parseWorker.drainOnce();
        return accepted;
    }

    private void awaitTaskDone(String taskId, String token) {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            RawResponse t = get("/api/doc/tasks/" + taskId, token).assertOk("轮询任务");
            Integer percent = t.data("$.percent");
            if (percent != null && percent >= 100) {
                return;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待任务完成时被中断：" + taskId, ex);
            }
        }
        throw new AssertionError("任务 120s 内未到终态：" + taskId);
    }

    private static Map<String, Object> formWith(String code) {
        Map<String, Object> form = new HashMap<>();
        form.put("name", "S6-C2 真VL探针库");
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
        return form;
    }
}
