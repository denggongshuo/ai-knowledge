package com.knowledge.ai.service;

import com.knowledge.ai.core.storage.DocStorage;
import com.knowledge.ai.support.DownloadTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-614` / `G7-8` / `G7-9`：打包下载。
 *
 * <h3>本类钉住的三件事</h3>
 * <ol>
 *   <li><b>上限在受理时同步生效</b>（`≤50 篇` / `≤2GB`）—— 超限是用户当场能改的输入错误，
 *       挪到异步里再失败等于让他白等一次打包；</li>
 *   <li><b>授权是全有或全无</b> —— 任意一篇不可见就**整批拒绝**，不静默过滤
 *       （静默过滤会让用户拿到一个"看着成功、里面少了几篇"的包）；</li>
 *   <li><b>产物是真的</b> —— zip 能解开、条目名是原文件名、每一篇各有一条 `BATCH` 记录、
 *       通知里有一个**能点**的下载动作。</li>
 * </ol>
 */
class BatchDownloadFlowTest extends DownloadTestSupport {

    private static final String TASK_TYPE = "batch-download";

    // ==================================================================
    // 受理时同步校验（不异步再失败）
    // ==================================================================

    @Test
    @DisplayName("G7-8：空列表 ⇒ 受理即 10030（含下一步动作，不是一句「参数非法」）")
    void emptyListIsRejectedSynchronously() {
        String token = loginAsAdmin().data("$.accessToken");
        RawResponse res = post("/api/doc/batch-download", Map.of("docIds", List.of()), token);
        assertThat(res.code()).as("空列表：" + res.body()).isEqualTo(10030);
        assertThat(res.message()).as("必须说清下一步动作（G4-3）").contains("选择");
    }

    @Test
    @DisplayName("G7-8：51 篇 ⇒ 10030（上限 50），且**不产生任何任务行**")
    void overFiftyIsRejectedSynchronously() {
        String token = loginAsAdmin().data("$.accessToken");
        long adminId = adminUserId();
        trackUser(adminId);

        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            ids.add((long) (i + 1));
        }
        RawResponse res = post("/api/doc/batch-download", Map.of("docIds", ids), token);
        assertThat(res.code()).as("51 篇：" + res.body()).isEqualTo(10030);
        assertThat(res.message()).contains("50");

        assertThat(taskCountOf(adminId)).as("被拒的请求不该留下任务行").isZero();
    }

    @Test
    @DisplayName("G7-8：合计超 2 GB ⇒ 10030（大小按 sys_document.size_bytes 算）")
    void overTwoGigabytesIsRejectedSynchronously() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());
        long kbId = createProbeKb(token);
        long docId = ((Number) uploadReady(kbId, "超限探针.md", token).data("$.docId")).longValue();

        // 造一个"超大文件"：**直接改库里的登记大小**。
        // ⚠️ 这样构造是**对的**，不是取巧：门禁 `G7-8` 的 2GB 是**受理口径**
        //    （`Document.size_bytes`，也是列表上显示给用户看的那个数），
        //    而校验发生在任何 MinIO 读取之前 —— 真去传一个 2GB 对象既慢又证明不了更多。
        jdbc.update("update sys_document set size_bytes = ? where id = ?",
                3L * 1024 * 1024 * 1024, docId);

        RawResponse res = post("/api/doc/batch-download", Map.of("docIds", List.of(docId)), token);
        assertThat(res.code()).as("3GB：" + res.body()).isEqualTo(10030);
        assertThat(res.message()).as("要给可执行的下一步（分批）").contains("分批");
    }

    @Test
    @DisplayName("G7-9：批里有一篇不存在 ⇒ **整批拒绝**（10044），不静默过滤；且不产生任何记录/任务")
    void anyMissingDocRejectsTheWholeBatch() {
        String token = loginAsAdmin().data("$.accessToken");
        long adminId = adminUserId();
        trackUser(adminId);
        long kbId = createProbeKb(token);
        long docId = ((Number) uploadReady(kbId, "整批拒绝探针.md", token)
                .data("$.docId")).longValue();

        long recordsBefore = recordCountOf(adminId);
        long tasksBefore = taskCountOf(adminId);

        // 第一篇是**真的能看到**的，第二篇不存在 —— 这正是"静默过滤"会放过去的那一种输入
        RawResponse res = post("/api/doc/batch-download",
                Map.of("docIds", List.of(docId, 999999999L)), token);

        assertThat(res.code()).as("含不存在的文档：" + res.body()).isEqualTo(10044);
        assertThat(recordCountOf(adminId)).as("整批拒绝 ⇒ 一条记录都不该写").isEqualTo(recordsBefore);
        assertThat(taskCountOf(adminId)).as("整批拒绝 ⇒ 一个任务都不该建").isEqualTo(tasksBefore);
    }

    @Test
    @DisplayName("G7-9：批里有不可见文档 ⇒ 10022（HTTP 403），不是 10044")
    void anyInvisibleDocRejectsTheWholeBatchWith403() {
        String adminToken = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());
        // 只对超管可见（`visible_roles` 与数据范围是两条并行的准入线，要关就一起关）
        long kbId = createProbeKb(adminToken, List.of("SUPER_ADMIN"));
        long docId = ((Number) uploadReady(kbId, "越权打包探针.md", adminToken)
                .data("$.docId")).longValue();

        String userToken = createUser("batchprobe").accessToken();
        RawResponse res = post("/api/doc/batch-download", Map.of("docIds", List.of(docId)), userToken);

        assertThat(res.code()).as("越权打包应给 10022：" + res.body()).isEqualTo(10022);
        assertThat(res.status()).isEqualTo(403);
    }

    // ==================================================================
    // 真打包（判据：zip 能解、记录齐、通知有点得动的动作）
    // ==================================================================

    @Test
    @DisplayName("G7-8/G7-9：两篇真文档 → 任务 DONE + zip 能解开且内容正确 + 每篇一条 BATCH + 通知带下载动作")
    void batchDownloadPacksRealZipAndLeavesFullEvidence() {
        String token = loginAsAdmin().data("$.accessToken");
        long adminId = adminUserId();
        trackUser(adminId);
        long kbId = createProbeKb(token);

        String nameA = "打包探针甲.md";
        String nameB = "打包探针乙.md";
        long docA = ((Number) uploadReady(kbId, nameA, token).data("$.docId")).longValue();
        long docB = ((Number) uploadReady(kbId, nameB, token).data("$.docId")).longValue();

        RawResponse accepted = post("/api/doc/batch-download",
                Map.of("docIds", List.of(docA, docB)), token).assertOk("受理打包");
        String taskId = accepted.data("$.taskId");
        assertThat((Integer) accepted.data("$.count")).as("受理即回文档数").isEqualTo(2);
        // ⚠️ `status` 给的是**调度层真值** `PENDING`，不是 mock 里的 `QUEUED` ——
        //    同一个 taskId 在两次请求里必须只有一种状态词（`AuditExportService` 的战绩）
        assertThat((String) accepted.data("$.status")).isEqualTo("PENDING");

        RawResponse done = awaitDownloadTerminal(taskId, token);
        String fileUrl = done.data("$.fileUrl");
        assertThat((String) done.data("$.status")).isEqualTo("DONE");
        assertThat((Integer) done.data("$.count")).isEqualTo(2);
        assertThat((Integer) done.data("$.percent")).isEqualTo(100);
        assertThat((String) done.data("$.name")).isEqualTo(DocStorage.BATCH_ZIP_NAME);
        Long size = ((Number) done.data("$.size")).longValue();
        assertThat(size).as("产物大小必须来自对象存储的真值（不是 0）").isPositive();
        assertThat((String) done.data("$.expiresAt")).isNotBlank();
        assertThat(fileUrl).as("完成态必须带可下载地址").isNotBlank();

        // ---- 产物：真下载、真解压、真对内容 ----
        Map<String, byte[]> entries = unzip(fetch(fileUrl));
        assertThat(entries.keySet()).as("条目名就是原文件名").containsExactlyInAnyOrder(nameA, nameB);
        assertThat(new String(entries.get(nameA), StandardCharsets.UTF_8)).contains("打包探针甲");

        // 对象落在打包专用前缀下（`batch-download/{ownerId}/{taskId}/documents.zip`）
        String objectKey = DocStorage.batchZipObjectKey(adminId, taskId);
        assertThat(docStorage.sizeOf(objectKey)).as("产物真的落进桶里了").isEqualTo(size);

        // ---- G7-9 证据一：每篇一条 BATCH 记录 ----
        assertThat(batchRecordCountOf(docA)).as("甲各一条 BATCH").isEqualTo(1);
        assertThat(batchRecordCountOf(docB)).as("乙各一条 BATCH").isEqualTo(1);

        // ---- G7-9 证据二：审计 ----
        Map<String, Object> audit = jdbc.queryForMap(
                "select action, risk_level, result from sys_audit_log where action = '批量下载' "
                        + "order by id desc limit 1");
        assertThat(audit.get("risk_level")).as("数据导出，高危（G3-8 第 4 类）").isEqualTo("HIGH");
        assertThat(audit.get("result")).isEqualTo("SUCCESS");

        // ---- G7-8：经通知中心交付，且**动作能点** ----
        Map<String, Object> notice = awaitNotice(taskId);
        assertThat(notice.get("type")).as("复用 SYSTEM，不新增通知类型").isEqualTo("SYSTEM");
        assertThat((String) notice.get("title")).contains("打包下载完成");
        // 正文里的名词必须是「打包文件」而不是「导出文件」—— 同一个交付规则，
        // 但不该把 zip 叫成导出件（`NoticePublisher#exportDone` 的 noun 参数就是为这个）
        assertThat((String) notice.get("content")).contains("打包文件已生成");
        assertThat((String) notice.get("actions_json"))
                .as("需求 09 §2.10：每条通知至少一个可执行动作 —— 而且那个动作得**能点**")
                .contains("下载打包文件").contains(fileUrl);
    }

    @Test
    @DisplayName("T-615：任务状态走 DownloadStatus 词表（RUNNING→PACKING / FAILED→FAILED），不下发调度层枚举名")
    void taskViewSpeaksDownloadStatusNotAsyncTaskStatus() {
        String token = loginAsAdmin().data("$.accessToken");
        long adminId = adminUserId();
        trackUser(adminId);

        String running = insertTask("RUNNING", null, 40);
        RawResponse view = get("/api/doc/download-tasks/" + running, token).assertOk("查任务");
        // 🔴 这一条钉的就是"两套词表不许混用"：库里存 `RUNNING`，接口必须给 `PACKING`
        assertThat((String) view.data("$.status")).as("调度层 RUNNING ⇒ 下载态 PACKING")
                .isEqualTo("PACKING");
        assertThat((String) view.data("$.fileUrl")).as("没完成就没有地址").isNull();
        assertThat((String) view.data("$.expiresAt")).isNull();

        String failed = insertTask("FAILED", "磁盘被打满了", 0);
        RawResponse failedView = get("/api/doc/download-tasks/" + failed, token).assertOk("查失败任务");
        assertThat((String) failedView.data("$.status")).isEqualTo("FAILED");
        assertThat((String) failedView.data("$.errorMsg")).as("失败原因必须具体（G4-3）")
                .isEqualTo("磁盘被打满了");
    }

    @Test
    @DisplayName("T-615：DONE 但产物已不在存储里 ⇒ 如实报 FAILED 并说明原因（不给一个点不动的按钮）")
    void doneTaskWithMissingArtifactIsReportedAsFailedHonestly() {
        String token = loginAsAdmin().data("$.accessToken");
        long adminId = adminUserId();
        trackUser(adminId);

        // 一条"已完成"的任务行，而它的产物从来没写过 —— 这正是"桶被清过"之后的真实形态
        String ghost = insertTask("DONE", null, 100);

        RawResponse view = get("/api/doc/download-tasks/" + ghost, token).assertOk("查幽灵任务");
        assertThat((String) view.data("$.status")).as("产物不在就必须说不在，不能报 DONE")
                .isEqualTo("FAILED");
        assertThat((String) view.data("$.fileUrl")).isNull();
        assertThat((String) view.data("$.errorMsg")).contains("重新");
    }

    @Test
    @DisplayName("T-615：别人的下载任务查不到（归属过滤），且与「不存在」给同一个错（不泄露存在性）")
    void otherUsersDownloadTaskIsNotFound() {
        String adminToken = loginAsAdmin().data("$.accessToken");
        long adminId = adminUserId();
        trackUser(adminId);
        String taskId = insertTask("RUNNING", null, 10);

        String userToken = createUser("taskprobe").accessToken();
        RawResponse res = get("/api/doc/download-tasks/" + taskId, userToken);
        assertThat(res.code()).as("越权查任务应给 10045：" + res.body()).isEqualTo(10045);

        // 同一个人查同一条：能查到（证明确实是**归属**拦下的，而不是 id 拼错了）
        assertThat(get("/api/doc/download-tasks/" + taskId, adminToken).code()).isZero();
    }

    // ==================== 小工具 ====================

    /** 直接插一条指定状态的任务行（用来**确定性地**断言状态词表，不靠轮询撞运气）。 */
    private String insertTask(String status, String errorMsg, int percent) {
        String taskId = "probe-" + Long.toString(System.nanoTime(), 36);
        jdbc.update("insert into sys_async_task (task_id, type, status, owner_id, stage, percent, "
                        + "chunk_done, chunk_total, attempt, created_at, updated_at) "
                        + "values (?, ?, ?, ?, '探针', ?, 0, 2, 0, now(), now())",
                taskId, TASK_TYPE, status, adminUserId(), percent);
        if (errorMsg != null) {
            jdbc.update("update sys_async_task set error_msg = ? where task_id = ?", errorMsg, taskId);
        }
        return taskId;
    }

    private long taskCountOf(long ownerId) {
        Long n = jdbc.queryForObject("select count(*) from sys_async_task "
                + "where owner_id = ? and type = ?", Long.class, ownerId, TASK_TYPE);
        return n == null ? 0L : n;
    }

    private long recordCountOf(long userId) {
        Long n = jdbc.queryForObject("select count(*) from sys_download_record where user_id = ?",
                Long.class, userId);
        return n == null ? 0L : n;
    }

    private long batchRecordCountOf(long docId) {
        Long n = jdbc.queryForObject("select count(*) from sys_download_record "
                + "where doc_id = ? and download_type = 'BATCH'", Long.class, docId);
        return n == null ? 0L : n;
    }

    private byte[] fetch(String url) {
        byte[] body = client.get().uri(URI.create(url)).retrieve().body(byte[].class);
        assertThat(body).as("预签名 URL 取不到 zip：" + url).isNotNull();
        return body;
    }

    private static Map<String, byte[]> unzip(byte[] zip) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip),
                StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
            }
        } catch (Exception ex) {
            throw new AssertionError("取回的字节不是合法 zip", ex);
        }
        return entries;
    }
}
