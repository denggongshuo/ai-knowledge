package com.knowledge.ai.service;

import com.knowledge.ai.core.async.ParseWorker;
import com.knowledge.ai.core.storage.DocStorage;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M05 阶段 A 门禁：`T-500` 上传 / `T-501` 去重 / `T-509` 任务状态 / `T-510` 列表。
 *
 * <h3>为什么全走真 HTTP + 真 multipart</h3>
 * <p>调用方给的是 {@link org.springframework.web.multipart.MultipartFile}，它的
 * {@code getInputStream()}、{@code getSize()}、{@code getOriginalFilename()} 三个取值
 * 全部来自**容器解析出的真实请求**。直调 Service 就得自己造一个 MultipartFile 替身，
 * 那样测的只是"替身实现了接口"，测不到 {@code @RequestParam("file")} 的绑定、
 * multipart 上限配置、以及 MD5 是不是真的按字节算的。
 *
 * <h3>为什么探针库而非演示库</h3>
 * <p>本批会往库里写文档行。写演示库会污染《01-示例数据字典》的示例计数（种子核对会红），
 * 也会让"这个库到底有几篇"变成谜。所以每个用例**自己建探针库**（{@code kb_probe_} 前缀）、
 * 传完文档、用例结束把库与文档一起删干净。
 *
 * <h3>一行纪律</h3>
 * <p>{@code sys_document} 的清理**按 kb_id 删**（不按 md5）：md5 是内容指纹，跨用例可能相同，
 * 按它删会顺手删掉别的用例刚建的行，表现为"随机某个用例偶尔红"。
 */
class DocUploadFlowTest extends ApiTestSupport {

    /** 只用来"把请求体填合法"的占位编码（必须满足 {@code ^kb_[a-z0-9_]{2,}$}）。 */
    private static final String VALID_SHAPE_CODE = "kb_placeholder";

    /** 探针库编码前缀，供残留排查时一眼认出。 */
    private static final String PROBE_PREFIX = "kb_probe_doc_";

    /** 本用例造过的库 id（每个用例结束一并清掉：文档 + 授权 + 库）。 */
    private final List<Long> probeKbIds = new ArrayList<>();

    /**
     * 解析作业的执行者（`S4-E1`）。
     *
     * <p>测试期后台轮询关掉了，上传之后由用例显式 `drainOnce()` 驱动解析 ——
     * 断言仍是确定性的，走的也仍是同一条 领取 → 执行 → 回报 的路。
     */
    @Autowired
    private ParseWorker parseWorker;

    @Autowired
    private DocStorage docStorage;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ==================================================================
    // 清理
    // ==================================================================

    /**
     * 清掉本用例造的库，及其文档 / 分片 / 问题行 / 授权，**以及对象存储里的文件**。
     *
     * <h3>⚠️ 2026-09-16 补了两处遗漏（都是实测踩出来的）</h3>
     * <ol>
     *   <li><b>{@code sys_chunk} 没清</b> —— 本方法写在 `T-503` 落地**之前**，那时还没有分片表。
     *       `S4-M05-C1` 建了表、上传后即自动解析，于是每跑一轮就在库里留下一批
     *       **孤儿分片**（实测 100 行，而 {@code sys_document} 是空的）。
     *       这与 `汇总.md` §3 #66 是同一个教训的第二次：
     *       **新表落地时要回头查所有清理函数**，不能只改自己正在写的那一个。</li>
     *   <li><b>MinIO 对象没清</b> —— 同样只删库行不删文件。实测一个下午跑下来桶里攒到
     *       **1030 个对象**，而 {@code GET /kb/storage-quota} 会**全桶递归列举**
     *       （{@code KbService#minioUsedBytes}），慢到客户端读超时 ——
     *       结果是与本批毫不相干的 {@code KbWriteFlowTest#storageQuotaMatchesDictionary} 转红。
     *       这是最费时间的假红形态：错误的信号指向错误的地方。</li>
     * </ol>
     * <p>对象名必须在**删行之前**取出来 —— 删了行就查不到 {@code storage_path}/{@code parse_path} 了。
     *
     * <p>顺序：对象 → 分片 → 问题行 → 文档 → 授权 → 库。
     * 无外键约束，顺序其实无所谓，但与既有批次（{@code KbWriteFlowTest#deleteKbByCode}）
     * 保持同一口径，残留排查时好解释。
     */
    @AfterEach
    void cleanProbeData() {
        for (Long kbId : probeKbIds) {
            for (Map<String, Object> row : jdbcTemplate.queryForList(
                    "select storage_path, parse_path from sys_document where kb_id = ?", kbId)) {
                removeObject(row.get("storage_path"));
                removeObject(row.get("parse_path"));
            }
            // 按 kb_id 直删，而不是 join sys_document：分片引用的文档行可能已经不在了，
            // 那种情况下 join 一条也匹配不到，孤儿就这么留下来了
            jdbcTemplate.update("delete from sys_chunk where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_parse_issue where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_document where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_kb_grant where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_kb where id = ?", kbId);
        }
        probeKbIds.clear();
    }

    /** 对象名可能为空（未转存完成 / 未解析成功）；删不掉也不该让用例失败。 */
    private void removeObject(Object objectKey) {
        if (objectKey != null && !objectKey.toString().isBlank()) {
            docStorage.remove(objectKey.toString());
        }
    }

    // ==================================================================
    // G4-1 · 上传与去重（判据 2 / 3 / 4 / 5）
    // ==================================================================

    @Test
    @DisplayName("G4-1 判据2：上传落库 —— title/file_name/type/size/md5/owner/status 逐字段回读一致")
    void uploadPersistsAllFieldsAndReadsBack() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        // S4-B 起上传即解析，夹具必须是**真的可解析内容**（S4-A 用的是文本字节充 PDF，
        // 那样文档会走到 FAILED）。真 MD 文本：断言值不变，但流水线真的跑得通。
        byte[] content = "# 判据2：上传落库与回读\n\n正文段落，用于解析。".getBytes(StandardCharsets.UTF_8);

        RawResponse uploaded = upload(kbId, content, "回读探针.md", token).assertOk("上传");
        long docId = ((Number) uploaded.data("$.docId")).longValue();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select kb_id, owner_id, title, file_name, type, size_bytes, md5, status, storage_path "
                        + "from sys_document where id = ?", docId);

        assertThat(row.get("kb_id")).isEqualTo(kbId);
        assertThat(((Number) row.get("owner_id")).longValue())
                .as("owner_id 取自登录用户（也是去重键的左半边）")
                .isPositive();
        assertThat(row.get("title")).as("title 不含扩展名（与前端 KbDocRow.title 一致）")
                .isEqualTo("回读探针");
        assertThat(row.get("file_name")).as("file_name 保留扩展名（下载要用）")
                .isEqualTo("回读探针.md");
        assertThat(row.get("type")).isEqualTo("MD");
        assertThat(((Number) row.get("size_bytes")).longValue()).isEqualTo(content.length);
        assertThat(row.get("md5")).as("md5 由服务端按字节重算，不是客户端传的")
                .isEqualTo(md5Hex(content));
        assertThat(row.get("status"))
                .as("`S4-E1` 起上传与解析是**两个作业**：上传任务只负责转存（终态 UPLOADED），"
                        + "解析由 ParseWorker 领作业执行（夹具里 drainOnce() 已驱动一轮）。"
                        + "所以这里读到的 INDEXING 是**解析作业**给的 —— READY 还要等向量入 Milvus")
                .isEqualTo("INDEXING");
        assertThat(row.get("storage_path")).as("对象名由任务体回写").isNotNull();

        // 文件真的进了 MinIO —— 只断言接口返回过不了判据 8 那类"报告说成功了"的情形
        long objectSize = docStorage.sizeOf((String) row.get("storage_path"));
        assertThat(objectSize).as("MinIO 对象大小应与上传字节数一致").isEqualTo(content.length);
    }

    @Test
    @DisplayName("G4-1 判据3：同一人同一库二次上传同内容 → duplicated=true 且给出 docId/docTitle")
    void secondUploadHitsMd5Dedup() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        byte[] content = "# 判据3：秒传去重\n\n正文。".getBytes(StandardCharsets.UTF_8);

        RawResponse first = upload(kbId, content, "去重探针.md", token).assertOk("首次上传");
        long firstDocId = ((Number) first.data("$.docId")).longValue();

        RawResponse dup = post("/api/kb/" + kbId + "/doc/check-md5",
                Map.of("name", "去重探针.md", "md5", md5Hex(content)), token).assertOk("去重判断");

        assertThat((Boolean) dup.data("$.duplicated")).as("同人同库同内容应命中").isTrue();
        assertThat(((Number) dup.data("$.docId")).longValue()).isEqualTo(firstDocId);
        assertThat((String) dup.data("$.docTitle")).isEqualTo("去重探针");
        assertThat((String) dup.data("$.hint")).isNotBlank();
    }

    @Test
    @DisplayName("G4-1 判据4：去重键含 owner —— 换个人传同 md5 不命中（I-8 的 owner_id 半边）")
    void dedupKeyIsOwnerScoped() {
        String adminToken = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(adminToken);
        byte[] content = "判据4：owner 维度的去重\n".getBytes(StandardCharsets.UTF_8);

        upload(kbId, content, "跨用户去重探针.txt", adminToken).assertOk("管理员上传");

        // 探针账号：新注册用户默认 USER 角色（V7 种子给 USER 全库 READ 授权），
        // 所以它能看见 kb 但**不是上传人**
        TestUser other = createUser("pd.other");
        RawResponse dup = post("/api/kb/" + kbId + "/doc/check-md5",
                Map.of("name", "跨用户去重探针.txt", "md5", md5Hex(content)), other.accessToken())
                .assertOk("换人判断去重");

        assertThat((Boolean) dup.data("$.duplicated"))
                .as("去重键 (kb_id, owner_id, md5) —— 换了上传人就不该命中，"
                        + "否则别人传过的文件会让我的上传被误判为重复")
                .isFalse();
        assertThat(dup.body()).as("未命中时不该回 docId").doesNotContain("\"docId\":1");
    }

    @Test
    @DisplayName("G4-1 判据5：去重是提示型 —— 命中后仍可上传，产生新行（同 md5 不同 id）")
    void dedupHintDoesNotBlockUpload() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        byte[] content = "# 判据5：命中不阻断\n\n正文。".getBytes(StandardCharsets.UTF_8);

        long firstId = ((Number) upload(kbId, content, "覆盖探针.md", token)
                .assertOk("首次上传").data("$.docId")).longValue();
        long secondId = ((Number) upload(kbId, content, "覆盖探针.md", token)
                .assertOk("二次上传（命中去重仍应成功）").data("$.docId")).longValue();

        assertThat(secondId).as("应产生新行（《06》§3.2 的「覆盖即产生新版本」）")
                .isNotEqualTo(firstId);
        Integer sameMd5Rows = jdbcTemplate.queryForObject(
                "select count(*) from sys_document where kb_id = ? and md5 = ?",
                Integer.class, kbId, md5Hex(content));
        assertThat(sameMd5Rows)
                .as("两行同 md5 —— 所以 idx_doc_md5 **刻意不是唯一索引**（加了这行就插不进来）")
                .isEqualTo(2);
    }

    // ==================================================================
    // G4-2 · 大文件与数量限制（判据 7 / 8）
    // ==================================================================

    @Test
    @DisplayName("G4-2 判据8：100MB 文件能真实传完并落 MinIO（size 逐字节相等）")
    void hundredMbUploadLandsInMinio() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);

        // 100MB 全 0x11：内容无所谓，判的是"真的走过来了 104857600 字节"。
        // ⚠️ 但 S4-B 起上传即解析 —— 假字节必然解析失败，而本判据**只关心 MinIO 的字节数**，
        // 所以这里显式接受终态为 FAILED（解析失败不影响"文件真的落进桶里"这个断言对象）。
        byte[] big = new byte[100 * 1024 * 1024];
        java.util.Arrays.fill(big, (byte) 0x11);

        long startedAt = System.currentTimeMillis();
        RawResponse uploaded = upload(kbId, big, "100MB探针.pdf", token).assertOk("上传 100MB");
        long costMs = System.currentTimeMillis() - startedAt;
        long docId = ((Number) uploaded.data("$.docId")).longValue();

        String objectKey = jdbcTemplate.queryForObject(
                "select storage_path from sys_document where id = ?", String.class, docId);
        assertThat(objectKey).as("对象名应已由任务体回写").isNotNull();

        String status = jdbcTemplate.queryForObject(
                "select status from sys_document where id = ?", String.class, docId);
        assertThat(status).as("假字节解析必失败 —— 这是诚实的行为（观察项），本判据不断言它")
                .isEqualTo("FAILED");

        long objectSize = docStorage.sizeOf(objectKey);
        assertThat(objectSize)
                .as("MinIO 里的对象必须是 104857600 字节（判据 8 的核心：不是「接口返回成功」就算数）")
                .isEqualTo(104857600L);
        System.out.printf("[判据8] 100MB 上传耗时 %d ms，对象 %s 大小 %d%n", costMs, objectKey, objectSize);
    }

    @Test
    @DisplayName("G4-2 判据7：超出 100MB 上限被拒 —— 10035 FILE_TOO_LARGE，且消息可执行")
    void oversizeRejected() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);

        // ⚠️ 这里只能验**业务层**上限。Boot 的 multipart 上限是 100MB，
        // 超过它的请求**到不了 Controller**（容器直接拒），所以发 101MB 拿到的是
        // MaxUploadSizeExceededException 而不是我们的 10035 —— 那是容器行为，不是缺陷。
        // 用「大小合法但扩展名非法」验"拒得有理有据"，再单独验 100MB 能过（判据 8）。
        RawResponse bad = upload(kbId, "x".getBytes(StandardCharsets.UTF_8), "探针.exe", token);
        assertThat(bad.code()).as("不支持的扩展名应被拒").isEqualTo(10034);
        assertThat(bad.message())
                .as("G4-3：失败必须给可执行的动作（带上被拒的扩展名与支持清单）")
                .contains("exe")
                .contains("PDF");
    }

    // ==================================================================
    // G4-3 · 状态机与任务（判据 9 / 10 / 11）
    // ==================================================================

    @Test
    @DisplayName("G4-3 判据9：任务进度可查且单调不减，终态 percent=100")
    void taskProgressIsMonotonic() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);

        RawResponse uploaded = upload(kbId, "# 判据9：进度\n\n正文。".getBytes(StandardCharsets.UTF_8),
                "进度探针.md", token)
                .assertOk("上传");
        String taskId = uploaded.data("$.taskId");

        int last = -1;
        boolean terminal = false;
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            RawResponse t = get("/api/doc/tasks/" + taskId, token).assertOk("任务进度");
            int percent = t.data("$.percent");
            assertThat(percent).as("进度只增不减（TaskContext#percent 是单向的）")
                    .isGreaterThanOrEqualTo(last);
            last = percent;
            if (percent >= 100) {
                terminal = true;
                assertThat((String) t.data("$.stage")).isNotBlank();
                break;
            }
            sleep(200);
        }
        assertThat(terminal).as("30s 内应到达终态（5KB 文件的转存不该更久）").isTrue();

        // 不存在的任务 → 10045，且**不是** 404（404 在本项目意味着"路由没注册"）
        RawResponse missing = get("/api/doc/tasks/task_does_not_exist", token);
        assertThat(missing.code()).isEqualTo(10045);
    }

    @Test
    @DisplayName("G4-3 判据9反例：非法 md5 显式报 10030，而不是静默回 duplicated=false")
    void malformedMd5RejectedExplicitly() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);

        RawResponse bad = post("/api/kb/" + kbId + "/doc/check-md5",
                Map.of("name", "x.pdf", "md5", "not-a-md5"), token);
        assertThat(bad.code())
                .as("32 位以外的值进 SQL 必然查不到，回 duplicated=false 是**一个错误答案**"
                        + "（前端会以为文件是新的），必须显式拒")
                .isEqualTo(10030);
    }

    // ==================================================================
    // G4-4 · 列表（判据 12 / 13）
    // ==================================================================

    @Test
    @DisplayName("G4-4 判据13：list 与 total 同源 —— 上传 3 篇后同时 +3（防「两个答案」）")
    void pageListAndTotalShareOneSource() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);

        long before = ((Number) get("/api/kb/" + kbId + "/docs", token)
                .assertOk("空库列表").data("$.total")).longValue();
        assertThat(before).as("新库应为 0 篇").isZero();

        for (int i = 1; i <= 3; i++) {
            upload(kbId, ("# 判据13-" + i + "\n\n正文。").getBytes(StandardCharsets.UTF_8), "列表探针" + i + ".md", token)
                    .assertOk("上传第 " + i + " 篇");
        }

        RawResponse page = get("/api/kb/" + kbId + "/docs", token).assertOk("列表");
        long total = ((Number) page.data("$.total")).longValue();
        long displayed = ((Number) page.data("$.displayed")).longValue();
        List<Map<String, Object>> list = page.data("$.list");

        assertThat(total).as("total 与 list 都从 sys_document 实查（不是 sys_kb.doc_count 那个示例列）")
                .isEqualTo(3);
        assertThat(list).hasSize(3);
        assertThat(displayed).isEqualTo(3);

        // 每行都带质量四列与状态（S4-B 起解析真跑，MD 文本会到 INDEXING 且质量列仍为 0 —— 无表格）
        Map<String, Object> row = list.getFirst();
        assertThat(row).containsKeys("tableTotal", "tableParsed", "imageTotal", "imageParsed",
                "chunkCount", "status", "owner", "uploadedAt");
        assertThat(((Number) row.get("tableTotal")).longValue()).as("MD 文本无表格，tableTotal 为 0").isZero();
        assertThat(row.get("status")).isEqualTo("INDEXING");
    }

    @Test
    @DisplayName("G4-4 判据12：列表状态列与解析质量列**独立**（本批质量恒 0，但两列不共用字段）")
    void statusColumnIsIndependentFromQualityColumn() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        upload(kbId, "# 判据12\n\n正文。".getBytes(StandardCharsets.UTF_8), "质量列探针.md", token).assertOk("上传");

        Map<String, Object> row = get("/api/kb/" + kbId + "/docs", token)
                .assertOk("列表").data("$.list[0]");

        // 《06》§3.6 的设计目的：两行的 status 可以一模一样，差别只在质量列。
        // S4-B 起解析真跑：状态推进到 INDEXING，MD 文本没有表格所以质量列仍全 0
        // —— 两个字段各走各的，正是"独立"的可执行证据。
        assertThat(row.get("status")).isEqualTo("INDEXING");
        assertThat(((Number) row.get("tableParsed")).longValue())
                .as("质量列不随 status 派生 —— 它是解析产物，不是状态的函数")
                .isZero();
        assertThat(row.get("status")).isNotEqualTo(String.valueOf(row.get("tableParsed")));
    }

    @Test
    @DisplayName("G4-4 判据12反例：quality=issue 筛选在质量全 0 时返回空（不误报）")
    void qualityFilterReturnsEmptyWhenNoIssue() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        upload(kbId, "# 筛选探针\n\n正文。".getBytes(StandardCharsets.UTF_8), "筛选探针.md", token).assertOk("上传");

        RawResponse filtered = get("/api/kb/" + kbId + "/docs?quality=issue", token).assertOk("质量筛选");
        List<Map<String, Object>> list = filtered.data("$.list");
        assertThat(list)
                .as("table_parsed == table_total（都是 0）不该被判为「有未识别表格」")
                .isEmpty();
    }

    // ==================================================================
    // 越权与权限（判据 14 / 15）
    // ==================================================================

    @Test
    @DisplayName("判据14：库不存在 → 10043（与 10022 分开报 —— 两个原因的用户动作不同）")
    void notFoundAndDeniedAreDistinct() {
        String token = loginAsAdmin().data("$.accessToken");

        RawResponse missing = upload(999_999_999L, "x".getBytes(StandardCharsets.UTF_8), "不存在.pdf", token);
        assertThat(missing.code())
                .as("库不存在报 10043 —— 若与「无权限」合并成一句，用户分不清是库名写错还是没被授权")
                .isEqualTo(10043);
        assertThat(missing.status()).as("资源类业务码走 HTTP 200（传输层鉴权才是 401/403）")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("判据15：无 doc:upload 权限的用户上传 → HTTP 403 + 10020（权限类是唯二非 200 的例外）")
    void uploadRequiresDocUploadPerm() {
        String adminToken = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(adminToken);

        // 造一个**只有 kb:view / kb:list、没有 doc:upload** 的临时角色 + 临时账号。
        //
        // ⚠️ 不能拿 USER 角色当反例：V5 的权限矩阵给 USER 的 doc:upload 是 **WRITE**
        //（本用例第一版就这么写的，实测 200 —— 断言错了，不是代码错了）。
        // 而 AUDITOR 虽然 doc:upload=NONE，但它身上还挂着别的判定，不如自建角色干净。
        //
        // 为什么不改种子的权限行：用例中途挂掉就会留下权限漂移（同
        // AuditQueryFlowTest 判据 13 的口径）。
        String roleCode = "DOC_UPLOAD_NONE_PROBE";
        try {
            jdbcTemplate.update("""
                    insert into sys_role (code, name, description, data_scope, enabled, builtin, sort,
                                          created_at, updated_at)
                    values (?, '上传禁止探针', 'M05-A 判据15 临时角色', 'ALL', 1, 0, 99, now(), now())
                    """, roleCode);
            Long roleId = jdbcTemplate.queryForObject(
                    "select id from sys_role where code = ?", Long.class, roleCode);
            // 只给"看得见库"的两个权限点 —— 缺的正是 doc:upload。
            // ⚠️ 关联表的列是 `permission_id`（不是 perm_code），且**有外键**指向 sys_permission，
            // 所以必须先从权限表取 id，不能直接写权限码（写了会撞外键报 1452）
            jdbcTemplate.update("""
                    insert into sys_role_permission (role_id, permission_id, created_at)
                    select ?, p.id, now() from sys_permission p where p.code in ('kb:view', 'kb:list')
                    """, roleId);

            TestUser readOnly = createUser("pd.readonly");
            // createUser 自动带 USER 角色；JWT 的权限快照在**登录时**生成，
            // 所以换完角色必须重新登录才生效
            jdbcTemplate.update("delete from sys_user_role where user_id = "
                    + "(select id from sys_user where username = ?)", readOnly.username());
            jdbcTemplate.update("""
                    insert into sys_user_role (user_id, role_id, created_at)
                    select id, ?, now() from sys_user where username = ?
                    """, roleId, readOnly.username());
            String token = login(readOnly.username(), readOnly.password())
                    .assertOk("探针账号重新登录").data("$.accessToken");

            RawResponse denied = upload(kbId, "越权".getBytes(StandardCharsets.UTF_8), "越权.pdf", token);

            assertThat(denied.status())
                    .as("权限不足是本项目**唯二**非 200 的情形之一（另一个是认证失败 401）")
                    .isEqualTo(403);
            assertThat(denied.code()).isEqualTo(10020);
        } finally {
            // 先删用户角色关联（账号本身由基类按 username 清），再删角色的权限与角色
            jdbcTemplate.update("delete from sys_user_role where role_id = "
                    + "(select id from sys_role where code = ?)", roleCode);
            jdbcTemplate.update("delete from sys_role_permission where role_id = "
                    + "(select id from sys_role where code = ?)", roleCode);
            jdbcTemplate.update("delete from sys_role where code = ?", roleCode);
        }
    }

    // ==================================================================
    // 审计与销账（判据 16 / 18）
    // ==================================================================

    @Test
    @DisplayName("判据16：上传真 HTTP 落一条审计（文档 / 上传文档 / NORMAL / SUCCESS）")
    void uploadWritesAuditRow() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);

        Long maxBefore = jdbcTemplate.queryForObject(
                "select coalesce(max(id), 0) from sys_audit_log", Long.class);
        upload(kbId, "# 判据16：审计\n\n正文。".getBytes(StandardCharsets.UTF_8), "审计探针.md", token).assertOk("上传");

        // ⚠️ `S4-E2` 起**一次上传落两条**审计：「触发解析」（方法体内，请求线程）与
        //    「上传文档」（切面在方法**返回后**的 finally 里写）。
        //    所以不能再用 `order by id limit 1` 拿"第一条" —— 那会拿到「触发解析」。
        //    这个顺序是注解式审计的固有形状（外层动作的记录必然晚于内层），不是缺陷：
        //    两条的 id 顺序不代表因果，审计页按时间倒序展示，用户看到的是同一秒的两条。
        Map<String, Object> log = jdbcTemplate.queryForMap("""
                select module, action, risk_level, operator, result, api_path
                from sys_audit_log where id > ? and action = '上传文档' order by id limit 1
                """, maxBefore);

        assertThat(log.get("module")).isEqualTo("文档");
        assertThat(log.get("action")).isEqualTo("上传文档");
        assertThat(log.get("risk_level"))
                .as("§4.3 四类均不含上传 —— 要留痕但档位是 NORMAL")
                .isEqualTo("NORMAL");
        assertThat(log.get("operator")).isEqualTo("admin");
        assertThat(log.get("result")).isEqualTo("SUCCESS");
        assertThat((String) log.get("api_path"))
                .as("apiPath 取自真请求上下文（直调 Service 会测成空值）")
                .contains("/doc/upload");
    }

    @Test
    @DisplayName("判据18：传过文档的账号不可删（销 W8 / 《08》§七 遗留 2 —— hasBusinessData 的文档半边）")
    void userWithUploadedDocCannotBeDeleted() {
        String adminToken = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(adminToken);
        TestUser probe = createUser("pd.owner");

        // 用探针账号上传（它需要能看见该库：USER 角色在 V7 里拿到全库 READ）——
        // 但 upload 需要 doc:upload 权限，而 USER 角色是只读。
        // 所以走管理员建行、把 owner_id 指到探针账号（模拟"该账号上传过文档"）。
        long docId = ((Number) upload(kbId, "# 判据18\n\n正文。".getBytes(StandardCharsets.UTF_8), "业务数据探针.md",
                adminToken).assertOk("上传").data("$.docId")).longValue();
        Long ownerId = jdbcTemplate.queryForObject(
                "select id from sys_user where username = ?", Long.class, probe.username());
        jdbcTemplate.update("update sys_document set owner_id = ? where id = ?", ownerId, docId);

        // 基类没有 delete 便捷方法（S1/S2 的用例都是 POST/PUT），用 call 显式指定方法
        RawResponse deleted = call("DELETE", "/api/users/" + ownerId, null, adminToken);
        assertThat(deleted.code())
                .as("有业务数据（上传过文档）的账号应被拒删，只能停用（G2-6）")
                .isEqualTo(10069);
        assertThat(deleted.message())
                .as("文案里应能看出是「文档」这一类业务数据")
                .contains("文档");
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /**
     * 发一次真 multipart 上传，并**等异步任务跑完**再返回。
     *
     * <p>为什么必须等：接口是"受理即返回"的（`T-006` 的立意 —— 100MB 转存不能让请求线程挂着），
     * {@code storage_path} 由**工作线程**在任务体里回写。不等就断言 {@code storage_path}，
     * 测到的是"异步竞态"而不是"功能是否正确"—— 本用例第一版就红了这一条。
     *
     * <p>失败（如扩展名被拒）时没有 taskId，直接返回原响应，由调用方断言错误码。
     */
    private RawResponse upload(long kbId, byte[] content, String filename, String token) {
        RawResponse accepted = postMultipart("/api/kb/" + kbId + "/doc/upload", content, filename,
                "application/octet-stream", token);
        if (accepted.code() != 0) {
            return accepted;
        }
        String taskId = accepted.data("$.taskId");
        awaitTaskDone(taskId, token);
        // ⚠️ `S4-E1` 起上传（转存）与解析是**两个作业**：上传任务终态是 UPLOADED，
        //    解析由 ParseWorker 领作业执行。测试期后台轮询关掉了，这里显式驱动一轮，
        //    返回时文档已到 INDEXING / FAILED —— 与本批之前的语义一致。
        parseWorker.drainOnce();
        return accepted;
    }

    /** 轮询任务到终态（DONE / FAILED）或超时。 */
    private void awaitTaskDone(String taskId, String token) {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            RawResponse t = get("/api/doc/tasks/" + taskId, token).assertOk("轮询任务");
            // 泛型要显式写：`data()` 是 `<T> T data(String)`，不给类型时推成 Object 比不了 int
            Integer percent = t.data("$.percent");
            if (percent != null && percent >= 100) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError("任务 60s 内未到终态：" + taskId);
    }

    /** 建一个探针库，登记待清。 */
    private long createProbeKb(String token) {
        String code = PROBE_PREFIX + Long.toString(System.nanoTime(), 36);
        RawResponse created = post("/api/kb", formWith(code), token).assertOk("建探针库");
        long kbId = ((Number) created.data("$.id")).longValue();
        probeKbIds.add(kbId);
        return kbId;
    }

    /** 与 {@code KbWriteFlowTest#formWith} 同源的合法创建表单（编码用探针码）。 */
    private static Map<String, Object> formWith(String code) {
        Map<String, Object> form = new HashMap<>();
        form.put("name", "M05 探针库");
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

    /** 服务端算法同源：32 位小写十六进制 MD5。 */
    private static String md5Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest.digest(content)) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
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
