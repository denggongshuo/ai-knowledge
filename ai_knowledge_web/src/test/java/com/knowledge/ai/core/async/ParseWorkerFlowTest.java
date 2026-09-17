package com.knowledge.ai.core.async;

import com.knowledge.ai.core.storage.DocStorage;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 解析作业调度 —— `T-O01` / `S4-E1` 的门禁用例。
 *
 * <h3>覆盖的四条判据（对齐 `优化/O1` §5）</h3>
 * <ol>
 *   <li><b>判据 1 · 任务态落库</b> —— 入队即写行，且带业务坐标（`kb_id` 是配额的键）；</li>
 *   <li><b>判据 3 · 按 kbId 配额的前提</b> —— 领取置 RUNNING 且计一次 attempt；
 *       **放回队列不烧 attempt**（否则"被配额挡回去三次"会被算成"失败三次"直接进死信）；</li>
 *   <li><b>判据 4 · 重启不丢</b> —— 持有者被强杀后，`RUNNING` 行靠 `locked_at` 超时被回收；</li>
 *   <li><b>判据 5 · 重试与死信分流</b> —— 基础设施类失败**可重试**，文档自身的问题**直接终态**。
 *       这两条混起来是最糟的：把"这文件不行"说成"重试中"，用户会一直等一个不会来的成功。</li>
 * </ol>
 *
 * <h3>为什么用 drainOnce() 而不是等后台轮询</h3>
 * 测试期 `app.parse.worker.enabled=false`（见 `src/test/resources/application.properties`）。
 * 显式驱动让断言**不依赖时序**，而覆盖一点没少 —— 走的仍是同一条
 * 领取 → 执行 → 回报 的路（后台那一圈另有 {@code tickSafely} 的节流与回收逻辑被本类直接调用）。
 */
// ⚠️ 不要在本类再写 @SpringBootTest —— 基类已声明
// {@code @SpringBootTest(webEnvironment = RANDOM_PORT)}，子类加一个默认（MOCK）的会**覆盖它**，
// 于是 ${local.server.port} 解析不出来，8 个用例全在注入阶段就炸（本轮实测踩过）。
class ParseWorkerFlowTest extends ApiTestSupport {

    private static final String PROBE_PREFIX = "kb_probe_pw_";

    @Autowired
    private ParseWorker parseWorker;

    @Autowired
    private SysAsyncTaskRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocStorage docStorage;

    @Autowired
    private AsyncTaskService asyncTaskService;

    private final List<Long> probeKbIds = new ArrayList<>();

    /**
     * 驱动前先确认**没有别人留下的** PENDING 解析作业。
     *
     * <h3>这一条是 `#128` 的机制落点（`S5` 测试可靠性批查明的）</h3>
     * <p>{@code ParseWorker#drainOnce()} 领的是**全局最老**的那一条 PENDING
     * （`V17` 的 {@code idx_task_claim} 就是按 {@code created_at} 排的）。
     * 于是库里只要躺着**别人**的陈旧作业，它就会被先领走，
     * 而**本用例的作业永远轮不到** —— 现象是"入队了却没执行"、状态停在 {@code PENDING}，
     * 且**失败的方法每次不同**（看谁的作业排在后面）。
     *
     * <p>实测（双向）：往表里灌 80 行一天前的 PENDING {@code doc-parse} 后，本类
     * **8 个用例里 6 个转红**；清掉后立刻 **8/8 绿**。所以它不是并发 bug，
     * 是**残留累积** —— 而残留的来源（{@code cleanProbeData} 漏删 {@code sys_async_task}）
     * 已在同批修掉（见 {@code ProbeCleanup}）。
     *
     * <p>⚠️ 这条断言真红了**不要**去改本类 —— 去查**谁没清自己的作业行**。
     * 它把那个"看起来像并发问题的偶发"变成一句能直接读懂的话。
     */
    @BeforeEach
    void noForeignPendingJobs() {
        Integer foreign = jdbcTemplate.queryForObject(
                "select count(*) from sys_async_task where type = 'doc-parse' and status = 'PENDING'",
                Integer.class);
        assertThat(foreign)
                .as("表里有 %s 行**别人留下的** PENDING 解析作业 —— drainOnce() 领的是全局最老的那条，"
                        + "本用例的作业会被永远压在后面（#128）。真红了请去查谁没清自己的作业行"
                        + "（S5 测试可靠性批实测：灌 80 行 ⇒ 本类 6 处红）", foreign)
                .isZero();
    }

    @AfterEach
    void cleanProbeData() {
        for (Long kbId : probeKbIds) {
            for (Map<String, Object> row : jdbcTemplate.queryForList(
                    "select storage_path, parse_path from sys_document where kb_id = ?", kbId)) {
                removeObject(row.get("storage_path"));
                removeObject(row.get("parse_path"));
            }
            // 作业行必须一起清：它没有外键，`sys_kb` 删掉之后就是**孤儿任务行**，
            // 而孤儿任务会被别的用例的领取循环捞到（本仓已经吃过"孤儿数据污染无关用例"的亏）。
            jdbcTemplate.update("delete from sys_async_task where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_chunk where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_parse_issue where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_document where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_kb_grant where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_kb where id = ?", kbId);
        }
        probeKbIds.clear();
    }

    // ==================================================================
    // 判据 1：入队即落库
    // ==================================================================

    @Test
    @DisplayName("判据1：入队写 sys_async_task（PENDING + 业务坐标），重启后仍查得到")
    void enqueuePersistsPendingRowWithBusinessCoords() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        long docId = uploadAndReturnDocId(kbId, mdBytes("# 入队探针\n\n正文。"), "入队探针.md", token);

        String taskId = parseWorker.enqueue(kbId, docId, 1L);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select type, status, kb_id, doc_id, owner_id, stage from sys_async_task where task_id = ?",
                taskId);
        assertThat(row.get("type")).isEqualTo("doc-parse");
        assertThat(row.get("status")).as("入队即 PENDING（领取前不占执行资源）").isEqualTo("PENDING");
        assertThat(((Number) row.get("kb_id")).longValue()).as("kb_id 是并发配额的键，必须落库").isEqualTo(kbId);
        assertThat(((Number) row.get("doc_id")).longValue()).isEqualTo(docId);
        assertThat(((Number) row.get("owner_id")).longValue()).as("O6 的「谁触发了这次解析」要读它").isEqualTo(1L);
        assertThat(row.get("stage")).isEqualTo("排队中");

        // 判据 6 的前半：**内存未命中时回落查库** —— 这条就是"重启后仍可查"的全部实现。
        // 直接查 repository 而不是 service，是为了绕开内存 registry（它一定有值，证明不了落库）。
        assertThat(repository.find(taskId))
                .as("重启后内存 registry 是空的，能答出这个问题的只剩 sys_async_task")
                .isPresent();
        assertThat(asyncTaskService.find(taskId)).as("走 service 也要能查到（内存或回落）").isPresent();
    }

    @Test
    @DisplayName("入队幂等：同一篇文档只有一个在跑的解析作业（键按 docId 给）")
    void enqueueIsIdempotentPerDocument() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        long docId = uploadAndReturnDocId(kbId, mdBytes("# 幂等探针\n\n正文。"), "幂等探针.md", token);

        String first = parseWorker.enqueue(kbId, docId, 1L);
        String second = parseWorker.enqueue(kbId, docId, 1L);

        assertThat(second).as("同一篇文档重复投递应复用同一个作业，而不是排两条队").isEqualTo(first);
        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from sys_async_task where doc_id = ? and type = 'doc-parse'",
                Integer.class, docId);
        assertThat(rows).isEqualTo(1);
    }

    // ==================================================================
    // 判据 3：领取与放回（配额的两条基础事实）
    // ==================================================================

    @Test
    @DisplayName("判据3（前提）：领取置 RUNNING、记 locked_by、attempt 计 1")
    void claimMovesToRunningAndCountsAttempt() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        long docId = uploadAndReturnDocId(kbId, mdBytes("# 领取探针\n\n正文。"), "领取探针.md", token);
        String taskId = parseWorker.enqueue(kbId, docId, 1L);

        List<SysAsyncTaskRepository.ClaimedJob> claimed = repository.claim("doc-parse", 8, "test-worker");

        assertThat(claimed).extracting(SysAsyncTaskRepository.ClaimedJob::taskId).contains(taskId);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select status, attempt, locked_by, locked_at from sys_async_task where task_id = ?", taskId);
        assertThat(row.get("status")).isEqualTo("RUNNING");
        assertThat(((Number) row.get("attempt")).intValue()).as("attempt = 已开始的执行次数").isEqualTo(1);
        assertThat((String) row.get("locked_by")).as("崩溃回收要靠它认出持有者").contains("test-worker");
        assertThat(row.get("locked_at")).isNotNull();
    }

    @Test
    @DisplayName("判据3（前提）：放回队列**不烧 attempt** —— 否则被配额挡三次就等于失败三次")
    void releaseReturnsJobWithoutBurningAttempt() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        long docId = uploadAndReturnDocId(kbId, mdBytes("# 放回探针\n\n正文。"), "放回探针.md", token);
        String taskId = parseWorker.enqueue(kbId, docId, 1L);

        repository.claim("doc-parse", 8, "test-worker");
        asyncTaskService.reportReleased(taskId, "该知识库解析并发已达上限，排队等待");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select status, attempt, stage, locked_by from sys_async_task where task_id = ?", taskId);
        assertThat(row.get("status")).as("放回后仍可被领取").isEqualTo("PENDING");
        assertThat(((Number) row.get("attempt")).intValue())
                .as("领取时的 +1 必须减回去 —— 它从来没被执行过，不该计入失败次数")
                .isZero();
        assertThat(row.get("stage")).isEqualTo("排队中");
        assertThat(row.get("locked_by")).as("放回即释放持有者标记").isNull();
    }

    // ==================================================================
    // 判据 4：重启不丢
    // ==================================================================

    @Test
    @DisplayName("判据4：持有者被强杀后，RUNNING 行靠 locked_at 超时被回收为 PENDING")
    void reclaimStaleResurrectsJobsHeldByDeadWorkers() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        long docId = uploadAndReturnDocId(kbId, mdBytes("# 回收探针\n\n正文。"), "回收探针.md", token);
        String taskId = parseWorker.enqueue(kbId, docId, 1L);

        repository.claim("doc-parse", 8, "worker-killed-by-kill-9");
        // 模拟"持有者已经死了很久"：把领取时刻往前推
        jdbcTemplate.update("update sys_async_task set locked_at = ? where task_id = ?",
                LocalDateTime.now().minusHours(1), taskId);

        int reclaimed = repository.reclaimStale("doc-parse", LocalDateTime.now().minusMinutes(15));

        assertThat(reclaimed).isGreaterThanOrEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select status, locked_by, locked_at from sys_async_task where task_id = ?", taskId);
        assertThat(row.get("status")).as("没人会回来把 RUNNING 改回 PENDING —— 只能靠超时兜底").isEqualTo("PENDING");
        assertThat(row.get("locked_by")).isNull();
        assertThat(row.get("locked_at")).isNull();
        assertThat(repository.find(taskId)).as("回收后能被重新领取，作业不丢").isPresent();
    }

    // ==================================================================
    // 判据 5：重试与死信**分流**
    // ==================================================================

    @Test
    @DisplayName("判据5a：源对象不在（基础设施类）→ 可重试（回到 PENDING + 定下次领取时刻）")
    void missingSourceObjectIsRetryable() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        // 造一行"已转存"的文档，但对象名指向一个**不存在的对象** —— 这正是 MinIO 抖动时的样子
        long docId = insertDocRow(kbId, "缺失源文件探针.md", "MD", "documents/" + kbId + "/ghost/none.md");

        String taskId = parseWorker.enqueue(kbId, docId, 1L);
        parseWorker.drainOnce();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select status, attempt, next_retry_at, error_msg from sys_async_task where task_id = ?", taskId);
        assertThat(row.get("status"))
                .as("基础设施类失败**不能判死** —— 一次网络抖动不该永久毁掉一篇本来能解析的文档")
                .isEqualTo("PENDING");
        assertThat(row.get("next_retry_at")).as("退避：定一个下次可领的时刻，而不是立刻重打").isNotNull();
        assertThat((String) row.get("error_msg")).contains("读取源文件失败");
    }

    @Test
    @DisplayName("判据5b：文档自身的问题 → **直接终态**，不重试（重试也还是这份文件）")
    void unparseableDocumentIsTerminal() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        // GBK 字节不是合法 UTF-8 —— 解析器会明确拒绝（与本仓既有 GBK 探针同一手法）
        byte[] gbk = new byte[240];
        for (int i = 0; i < 240; i += 4) {
            gbk[i] = (byte) 0xc4;
            gbk[i + 1] = (byte) 0xe3;
            gbk[i + 2] = (byte) 0xba;
            gbk[i + 3] = (byte) 0xc3;
        }
        long docId = uploadAndReturnDocId(kbId, gbk, "编码探针.txt", token);
        String taskId = parseWorker.enqueue(kbId, docId, 1L);

        parseWorker.drainOnce();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select status, attempt, next_retry_at, error_msg from sys_async_task where task_id = ?", taskId);
        assertThat(row.get("status")).as("文档自身的问题应直接终态").isEqualTo("FAILED");
        assertThat(row.get("next_retry_at")).as("不排重试 —— 对用户撒谎说「重试中」比直说「这文件不行」更糟").isNull();
        assertThat((String) row.get("error_msg")).as("失败必须给具体原因（G4-3）").isNotBlank();
        assertThat(jdbcTemplate.queryForObject(
                "select status from sys_document where id = ?", String.class, docId)).isEqualTo("FAILED");
    }

    // ==================================================================
    // 判据 6：上传与解析是两个作业
    // ==================================================================

    @Test
    @DisplayName("判据6：上传任务终态是 UPLOADED（不谎报「解析完成」），解析由作业推进到 INDEXING")
    void uploadStopsAtUploadedUntilWorkerRuns() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);

        // 等**上传**任务到终态（转存完成）—— 此时解析作业刚被投出来，还没人领
        long docId = uploadAndReturnDocId(kbId, mdBytes("# 两段式探针\n\n正文段落。"), "两段式探针.md", token);

        assertThat(jdbcTemplate.queryForObject(
                "select status from sys_document where id = ?", String.class, docId))
                .as("转存完成、解析还没跑 —— 这一刻**如实**是 UPLOADED，不是 INDEXING")
                .isEqualTo("UPLOADED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from sys_async_task where doc_id = ? and type = 'doc-parse' and status = 'PENDING'",
                Integer.class, docId))
                .as("转存一完成就投作业，且作业处于 PENDING（等被领取）")
                .isEqualTo(1);

        parseWorker.drainOnce();

        assertThat(jdbcTemplate.queryForObject(
                "select status from sys_document where id = ?", String.class, docId))
                .as("解析作业跑完后才推进到 INDEXING（测试期向量化关，所以还不是 READY）")
                .isEqualTo("INDEXING");
        assertThat(jdbcTemplate.queryForObject(
                "select status from sys_async_task where doc_id = ? and type = 'doc-parse'", String.class, docId))
                .as("作业自身也收了终态").isEqualTo("DONE");
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private long createProbeKb(String token) {
        String code = PROBE_PREFIX + Long.toString(System.nanoTime(), 36);
        Map<String, Object> form = new HashMap<>();
        form.put("name", "S4-E1 探针库");
        form.put("code", code);
        form.put("deptId", 1);
        // ⚠️ `visibleRoles` 与 `businessDomain` 是**必填**（漏了两者都拿到 10030）——
        //    表单从上往下填，任何一个必填缺失都会让"建库"这一步先炸，
        //    而现象是 8 个用例全红在同一个 helper 上，看不出真正缺的是哪个字段。
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

    private byte[] mdBytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** 走真接口上传，等**上传**任务到终态（此时文档是 UPLOADED，解析还没跑）。 */
    private long uploadAndReturnDocId(long kbId, byte[] content, String filename, String token) {
        RawResponse accepted = postMultipart("/api/kb/" + kbId + "/doc/upload", content, filename,
                "application/octet-stream", token).assertOk("上传");
        long docId = ((Number) accepted.data("$.docId")).longValue();
        String taskId = accepted.data("$.taskId");
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            Integer percent = get("/api/doc/tasks/" + taskId, token).assertOk("轮询上传任务").data("$.percent");
            if (percent != null && percent >= 100) {
                return docId;
            }
            sleep(50);
        }
        throw new AssertionError("上传任务 60s 内未到终态：" + taskId);
    }

    /** 直接插一行"已转存"的文档（用于构造"对象不在"这类仓储层场景，不必真传一个文件）。 */
    private long insertDocRow(long kbId, String fileName, String type, String storagePath) {
        jdbcTemplate.update("""
                        insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5,
                                                  storage_path, status, chunk_count, table_total, table_parsed,
                                                  image_total, image_parsed, index_generation, created_at, updated_at)
                        values (?, 1, ?, ?, ?, 128, ?, ?, 'UPLOADED', 0, 0, 0, 0, 0, 0, now(), now())
                        """,
                kbId, fileName.replaceAll("\\.[^.]+$", ""), fileName, type, "0".repeat(32), storagePath);
        return jdbcTemplate.queryForObject("select max(id) from sys_document where kb_id = ?", Long.class, kbId);
    }

    private void removeObject(Object objectKey) {
        if (objectKey != null && !objectKey.toString().isBlank()) {
            docStorage.remove(objectKey.toString());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
