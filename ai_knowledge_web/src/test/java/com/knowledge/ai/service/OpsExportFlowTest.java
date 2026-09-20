package com.knowledge.ai.service;

import com.knowledge.ai.core.async.AsyncTaskInfo;
import com.knowledge.ai.core.async.AsyncTaskService;
import com.knowledge.ai.core.ops.MissKeyNormalizer;
import com.knowledge.ai.core.ops.MissStatus;
import com.knowledge.ai.core.ops.QaMissRepository;
import com.knowledge.ai.core.text.Csv;
import com.knowledge.ai.support.ApiTestSupport;
import com.knowledge.ai.support.ProbeCleanup;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运营报表导出（`T-A02` · 需求 `08` §1.7/§1.8）—— 走**真 HTTP + 真异步 + 真 MinIO + 真通知**。
 *
 * <h3>判据对齐</h3>
 * <ul>
 *   <li>需求 §1.7「导出报表 | 字段与表格列保持一致」+「筛选条件原样带走」</li>
 *   <li>需求 §1.8 `POST /ops/qa/export`（异步，走通知中心）</li>
 *   <li>`G6-12` 通知**带可直达动作**（真下载地址），不是 toast</li>
 *   <li>审计三处同步的**运行时**证据（注解真挂上了 → 审计表里真有那一行）</li>
 *   <li>`#110` 的教训：任务的 `owner_id` **必须非空**（否则「谁触发的」永远查不出）</li>
 * </ul>
 *
 * <h3>🔴 刻意不测的一条（写在类注释里，不写在 §9 就没人知道）</h3>
 * <p>**超限拒绝**（`rows.size() > EXPORT_MAX_ROWS`）在本类里**跑不到** ——
 * 那要 50001 行未命中清单。本类只测**它依赖的机制**：`QaMissRepository#findAll`
 * 会多取一行（`cap + 1`），所以调用方**判得出**超限。**判据与实现的落差如实记在计划 §9**，
 * 不假装覆盖了（本仓被"看着覆盖了"咬过多次）。
 *
 * <h3>自清</h3>
 * <p>任务行 / MinIO 对象 / 池子行**逐个记下精确标识**再删（不只靠水位线）——
 * `T-O19` 记的正是"只按水位线清 ⇒ 水位线以下没人认领"那个洞。
 */
@Slf4j
class OpsExportFlowTest extends ApiTestSupport {

    private static final String PREFIX = ProbeCleanup.OPS_EXPORT_PREFIX;

    @Autowired
    private AsyncTaskService asyncTaskService;

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private QaMissRepository qaMissRepository;

    @Value("${minio.bucket:rag-documents}")
    private String bucket;

    private String token;
    private long userId;

    private long missFloor;

    /** 本轮造出来的任务 / 对象 —— 收尾按**精确标识**删。 */
    private final List<String> taskIds = new ArrayList<>();

    private Instant runStart = Instant.now();

    @BeforeEach
    void setUpProbe() {
        // 导出挂的是 `ops:qa:handle`（数据外带比"看"更严）⇒ 探针账号要是 KB_ADMIN
        TestUser probe = createUser("oe.probe");
        userId = userIdOf(probe.username());
        assignRole(userId, "KB_ADMIN");
        token = login(probe.username(), probe.password()).data("$.accessToken");

        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from sys_qa_miss", Long.class);
        missFloor = max == null ? 0L : max;
        runStart = Instant.now();
    }

    @AfterEach
    void removeFixtures() {
        // ① 任务行（精确 id）→ ② 桶里的对象（精确 objectKey）→ ③ 池子行（水位线）
        for (String taskId : taskIds) {
            jdbc.update("delete from sys_notice where source_task_id = ?", taskId);
            jdbc.update("delete from sys_async_task where task_id = ?", taskId);
        }
        taskIds.clear();
        sweepObjectsCreatedThisRun();
        jdbc.update("delete from sys_qa_miss where id > ?", missFloor);
    }

    // ==================== 需求 §1.8 · 受理 ====================

    @Test
    @DisplayName("受理回话三字段与 mock 同形；状态是 PENDING（**不是** mock 那个 QUEUED）")
    void submitAcceptsAndReportsPending() {
        seed("导出的问题", "PENDING", "1");

        Map<String, Object> res = submit(null, null, null);
        String taskId = String.valueOf(res.get("taskId"));
        taskIds.add(taskId);

        assertThat(taskId).as("必须回一个真 taskId（`T-908` 的判例：不发假 taskId）").isNotBlank();
        assertThat(res.get("status"))
                .as("🔴 状态词表只能有一套：调度层是 PENDING，mock 那个 'QUEUED' 是字面量占位"
                        + "（`AuditExportService#submit` 的注释记了同一个取舍）")
                .isEqualTo("PENDING");
        assertThat(String.valueOf(res.get("note"))).as("给用户看的那句话由后端给").isNotBlank();
    }

    @Test
    @DisplayName("#110：任务行的 owner_id **非空**（否则「谁触发的」永远查不出）+ 审计真落一条")
    void taskHasOwnerAndAuditRow() {
        seed("导出的问题", "PENDING", "1");

        String taskId = submitAndTrack(null, null, null);
        Long ownerId = jdbc.queryForObject(
                "select owner_id from sys_async_task where task_id = ?", Long.class, taskId);

        assertThat(ownerId)
                .as("🔴 提交时不带业务坐标 ⇒ `TaskSubject.none()` ⇒ owner_id 为 NULL，"
                        + "而通知那一层靠它找收件人（`NoticePublisher` 会 warn 并跳过）")
                .isEqualTo(userId);

        // 审计：注解挂没挂上，只有真 HTTP 能验（`S2AuditFlowTest` 定下的姿势）
        Long auditRows = jdbc.queryForObject(
                "select count(*) from sys_audit_log where module = '问答' and action = '导出运营报表'"
                        + " and operator = (select username from sys_user where id = ?)",
                Long.class, userId);
        assertThat(auditRows)
                .as("`AuditRiskPolicy` 的 `DATA_EXPORT` 类里逐字列着「导出报表」⇒ 这条必须有")
                .isEqualTo(1L);
    }

    // ==================== G6-12 · 通知带真下载地址 ====================

    @Test
    @DisplayName("G6-12：导出完成 ⇒ 通知落库且动作里是**真能取到文件**的地址")
    void noticeCarriesRealDownloadUrl() throws Exception {
        seed("导出的问题", "PENDING", "1");
        String taskId = submitAndTrack(null, null, null);

        AsyncTaskInfo<?> done = awaitTerminal(taskId);
        assertThat(done.getStatus().terminal()).as("任务必须到终态").isTrue();

        // ⚠️ **要等一会儿**：任务到终态与通知落库是**两步**（`NoticePublisher` 在终态之后
        // 才被调用）⇒ 查到 DONE 却查不到通知，是竞态不是缺陷。
        // 单发一次查询会**偶发红**，而偶发红的下场是被当成环境问题忽略
        Map<String, Object> notice = awaitNotice(taskId);
        assertThat(notice.get("title"))
                .as("标题写明是**运营**报表 —— 别与「审计导出完成」混起来，两者同属 SYSTEM 类通知")
                .isEqualTo("运营报表导出完成");
        assertThat(String.valueOf(notice.get("actions_json")))
                .as("动作里必须是**真下载地址**（带 `ops-export/` 前缀的预签名 URL），"
                        + "不是页面路径、也不是占位串")
                .contains("/" + PREFIX);

        // 真去取一次 —— "地址里有前缀"与"这个地址能取到文件"是两件事
        String objectKey = objectKeyOf(taskId);
        assertThat(objectKey).as("任务结果里要带 objectKey").isNotNull();
        String csv = readObject(objectKey);
        assertThat(csv).as("取回来的真的是那份 CSV").contains("用户问题");
    }

    // ==================== 需求 §1.7 · 内容与筛选 ====================

    @Test
    @DisplayName("需求 §1.7：CSV 表头与页面表格列一致（含 BOM，Excel 打开不乱码）")
    void csvHeaderMatchesTableColumns() throws Exception {
        seed("导出的问题", "PENDING", "1");
        String taskId = submitAndTrack(null, null, null);
        awaitTerminal(taskId);
        String csv = readObject(objectKeyOf(taskId));

        assertThat(csv).as("中文 CSV 必须先写 BOM —— 否则部分 Excel 打开是乱码，"
                        + "而「下载的表格打不开」会被当成导出功能坏了").startsWith(Csv.BOM);

        List<String> lines = csv.lines().toList();
        assertThat(lines.getFirst().substring(1).split(",", -1))
                .as("🔴 与 `OpsQaView.vue` 的 `<el-table-column>` 逐列对应："
                        + "用户问题 / 涉及知识库 / 提问次数 / 提问人 / 最高相似度 / 最近提问 / 状态 / 忽略人。"
                        + "⚠️ 「涉及知识库」「忽略人」在页面上是单元格内的第二行，CSV 里各占一列；"
                        + "「最高相似度」保留页面的 `补录前 → 补录后` 渲染，不拆成两个数字列")
                .containsExactly("用户问题", "涉及知识库", "提问次数", "提问人",
                        "最高相似度", "最近提问", "状态", "忽略人");
        assertThat(lines).as("表头 + 一行数据").hasSize(2);
        assertThat(lines.get(1)).contains("导出的问题").contains("PENDING");
    }

    @Test
    @DisplayName("需求 §1.7：**筛选条件原样带走** —— 筛了状态就只导那一个状态")
    void filtersAreCarriedOver() throws Exception {
        seed("待处理的问题", "PENDING", "1");
        seed("已忽略的问题", "IGNORED", "1");

        String taskId = submitAndTrack("PENDING", null, null);
        awaitTerminal(taskId);
        String csv = readObject(objectKeyOf(taskId));

        assertThat(csv).as("筛选内的那行在").contains("待处理的问题");
        assertThat(csv)
                .as("🔴 筛选外的那行**不在** —— 导出「全部」的话，用户拿到的与他在页面上看到的"
                        + "不是同一批数据，而报表的用途正是「周会汇报我看到的那张表」。"
                        + "这条同时是**数据外带面的控制**（`AuditQueryService#exportCsv` 的同一条口径）")
                .doesNotContain("已忽略的问题");
    }

    @Test
    @DisplayName("结果里的 objectKey 落在 `ops-export/{userId}/` 下 —— 与登记表是**同一个前缀**")
    void objectKeyLivesUnderDeclaredPrefix() {
        seed("导出的问题", "PENDING", "1");
        String taskId = submitAndTrack(null, null, null);
        awaitTerminal(taskId);

        String objectKey = objectKeyOf(taskId);
        assertThat(objectKey)
                .as("🔴 服务里的 `PREFIX` 与 `ProbeCleanup.OPS_EXPORT_PREFIX` 必须是同一个字面量"
                        + "—— 各写一份的话，「兜底扫得到」这句话就成了猜测")
                .startsWith(PREFIX + userId + "/")
                .endsWith(".csv");
        assertThat(OpsExportService.PREFIX)
                .as("两个常量直接比一次：它是上面那条断言的前提")
                .isEqualTo(ProbeCleanup.OPS_EXPORT_PREFIX);
    }

    // ==================== 权限（数据外带比"看"更严）====================

    @Test
    @DisplayName("导出要 `ops:qa:handle`：AUDITOR 能读统计但**导不出**（可证伪的边界）")
    void exportNeedsHandleNotView() {
        seed("导出的问题", "PENDING", "1");

        TestUser auditor = createUser("oe.auditor");
        long auditorId = userIdOf(auditor.username());
        assignRole(auditorId, "AUDITOR");
        String auditorToken = login(auditor.username(), auditor.password()).data("$.accessToken");

        assertThat(get("/api/ops/qa/top-questions", auditorToken).code())
                .as("审计员**能读**（`ops:qa:view`）").isZero();
        assertThat(call("POST", "/api/ops/qa/export", Map.of(), auditorToken).status())
                .as("🔴 但**导不出** —— 导出含提问原文 + 提问人姓名，是数据外带。"
                        + "复用既有的 handle 码而不新增第 41 个权限码，"
                        + "正是为了让这条边界**有角色能证伪**（`#153`/`#168` 的教训："
                        + "没有角色被授予的码 = 没有角色能证伪它）")
                .isEqualTo(403);

        assertThat(call("POST", "/api/ops/qa/export", Map.of(), token).code())
                .as("KB_ADMIN 有 handle 码").isZero();
        // 上面那次真提交了任务，也要收尾
        trackLatestTask();
    }

    // ==================== 超限机制（服务层的拒绝够不着，只测它依赖的那半边）====================

    @Test
    @DisplayName("超限**判得出来**：`findAll` 多取一行 ⇒ 调用方能发现「还有更多」")
    void findAllDetectsOverflow() {
        seed("问题一", "PENDING", "1");
        seed("问题二", "PENDING", "1");

        List<QaMissRepository.Row> one = qaMissRepository.findAll(null, null, null, 1);
        List<QaMissRepository.Row> both = qaMissRepository.findAll(null, null, null, 10);

        assertThat(one).as("cap=1 时返回 **2** 行 —— 多出来的那一行就是「超限」的信号"
                        + "（服务层凭它抛 10030 并要求收窄条件）").hasSize(2);
        assertThat(both).as("上限之内时不多取").hasSizeGreaterThanOrEqualTo(2);
    }

    // ==================== 内部 ====================

    private Map<String, Object> submit(String status, String kbId, String keyword) {
        // ⚠️ 不能用 `Map.of(...)`：它**不接受 null 值**（会 NPE，而现象是"提交导出就崩"）。
        // 三个筛选各自可空，这正是"不筛"的表达方式
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("status", status);
        body.put("kbId", kbId);
        body.put("keyword", keyword);
        return call("POST", "/api/ops/qa/export", body, token).assertOk("提交导出").data("$");
    }

    private String submitAndTrack(String status, String kbId, String keyword) {
        String taskId = String.valueOf(submit(status, kbId, keyword).get("taskId"));
        taskIds.add(taskId);
        return taskId;
    }

    /** 给「用 `call(...)` 直接提交」的用例补登记（那条路径拿不到回话体里的 taskId）。 */
    private void trackLatestTask() {
        String taskId = jdbc.queryForObject(
                "select task_id from sys_async_task where owner_id = ? order by created_at desc limit 1",
                String.class, userId);
        if (taskId != null) {
            taskIds.add(taskId);
        }
    }

    /**
     * 一行未命中池的数据。
     *
     * <p>沉淀走 {@code upsert}（与生产**同一个**方法）；改状态走 **HTTP PATCH** ——
     * 不是绕路：`QaMissRepository#updateStatus` 没有自己的 `@Transactional`
     * （它只在 `QaMissService#patch` 的事务里被调），直接调会报
     * `No active transaction for update or delete query`。走接口既解决了这件事，
     * 又顺带把"状态真的改得动"这条接线一起验了。
     */
    private void seed(String question, String status, String kbIds) {
        qaMissRepository.upsert(MissKeyNormalizer.keyOf(question), question, userId, 0.21, kbIds,
                LocalDateTime.now());
        Long id = jdbc.queryForObject("select id from sys_qa_miss where miss_key = ?", Long.class,
                MissKeyNormalizer.keyOf(question));
        assertThat(id).as("沉淀必须真的落库").isNotNull();
        if (!MissStatus.PENDING.name().equals(status)) {
            call("PATCH", "/api/ops/qa/misses/" + id, Map.of("status", status), token)
                    .assertOk("置状态 → " + status);
        }
    }

    private String objectKeyOf(String taskId) {
        // ⚠️ 必须在**删任务行之前**取 —— `sys_async_task.result` 不持久化
        Object result = asyncTaskService.find(taskId).map(AsyncTaskInfo::getResult).orElse(null);
        if (!(result instanceof Map<?, ?> map)) {
            return null;
        }
        Object key = map.get("objectKey");
        return key == null ? null : String.valueOf(key);
    }

    private String readObject(String objectKey) throws Exception {
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucket).object(objectKey).build())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 安全网：删 `ops-export/` 下**本轮开始之后**产生的对象（与 `AuditQueryFlowTest` 同形）。
     * ⚠️ 时间下限是必须的 —— 无上限的删除会把**上一轮或别人的**对象一起带走。
     */
    private void sweepObjectsCreatedThisRun() {
        try {
            for (Result<Item> r : minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket).prefix(PREFIX).recursive(true).build())) {
                Item item = r.get();
                if (item.lastModified().toInstant().isBefore(runStart)) {
                    continue;
                }
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucket).object(item.objectName()).build());
            }
        } catch (Exception ex) {
            log.warn("运营报表对象收尾扫描失败（不影响用例结论）：{}", ex.getMessage());
        }
    }

    /**
     * 等通知落库（超时**判红**并报出任务类型，不静默返回 `null`）。
     *
     * <p>为什么要等：任务转终态与通知落库是**两步** —— `NoticePublisher#onTaskDone`
     * 在终态之后才被调用（`T-B03` 把生成点放在 `AsyncTaskService` 的四个终态调用点上）。
     * 查到 `DONE` 查不到通知是**竞态**，不是缺陷；单发一次查询会偶发红，
     * 而偶发红的判据会被当成环境问题忽略（本仓在 `#226` 上正是栽在这件事上）。
     */
    private Map<String, Object> awaitNotice(String taskId) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "select type, title, actions_json from sys_notice where source_task_id = ?", taskId);
            if (!rows.isEmpty()) {
                return rows.getFirst();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待通知时被中断", ex);
            }
        }
        throw new AssertionError("任务已完成但 5s 内没有通知落库：taskId=" + taskId
                + "（查 `NoticePublisher#payloadOf` 的映射表里有没有这个任务类型）");
    }

    /** 等异步任务到终态（超时**判红**，不静默返回 —— 与 `OrphanMinioSweepTest` 同形）。 */
    private AsyncTaskInfo<?> awaitTerminal(String taskId) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            AsyncTaskInfo<?> info = asyncTaskService.find(taskId).orElse(null);
            if (info != null && info.getStatus().terminal()) {
                assertThat(info.getStatus().name())
                        .as("导出任务必须**成功** —— `AsyncTaskStatus` 的成功值是 `DONE`"
                                + "（不是 `SUCCESS`；失败时原因在任务行的 error_msg 里）")
                        .isEqualTo("DONE");
                return info;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待导出任务时被中断", ex);
            }
        }
        throw new AssertionError("导出任务未在 20s 内到达终态：" + taskId);
    }

    private long userIdOf(String name) {
        Long id = jdbc.queryForObject("select id from sys_user where username = ?", Long.class, name);
        assertThat(id).as("账号 %s 应该存在", name).isNotNull();
        return id;
    }

    private void assignRole(long uid, String roleCode) {
        jdbc.update("delete from sys_user_role where user_id = ?", uid);
        jdbc.update("insert into sys_user_role (user_id, role_id, created_at) "
                + "select ?, id, now() from sys_role where code = ?", uid, roleCode);
        assertThat(jdbc.queryForObject("select count(*) from sys_user_role where user_id = ?",
                Integer.class, uid)).as("角色切换必须生效").isEqualTo(1);
    }
}
