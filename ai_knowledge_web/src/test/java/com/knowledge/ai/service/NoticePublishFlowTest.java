package com.knowledge.ai.service;

import com.knowledge.ai.core.async.AsyncTaskInfo;
import com.knowledge.ai.core.async.AsyncTaskService;
import com.knowledge.ai.core.async.AsyncTaskStatus;
import com.knowledge.ai.core.async.SysAsyncTaskRepository;
import com.knowledge.ai.core.notice.NoticePublisher;
import com.knowledge.ai.core.notice.NoticeRepository;
import com.knowledge.ai.support.ApiTestSupport;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * `T-B03` 通知生成点 —— **写侧**门禁（`G6-12`）。
 *
 * <h3>本类要钉住的那件事</h3>
 * <p>终态写入有**两条路**，而通知生成点必须**两条都接**：
 * <pre>
 *   ① 内存命中：TaskContext#complete/#fail        ← 走真 HTTP 提交的任务走这条
 *   ② 库回退：  reportDone/reportFailed 的 else 分支 ← 重启后由 worker 领走的任务走这条
 * </pre>
 * <p>⚠️ 只接 ① 会让**耗时最长的那一类任务**（跨重启、被后台领取的）安静地跑完而没有通知，
 * 且现象极难被注意到 —— 所以 P2 用一条**差分的**判据把两条路都走一遍。
 *
 * <h3>为什么不能挂在 `markDone` 上（本类的判据 P10 就是为它写的）</h3>
 * <p>那是两条路的**唯一交汇点**，看着最省事；但 {@code markDone} 的入参里**没有 `result`**
 * （`result` 不持久化）⇒ 挂在那里，{@code audit-export} 的通知**永远拿不到下载地址**。
 * P10 断言导出的通知里**真的有那个 URL** —— 谁把生成点挪回 `markDone`，这条立刻转红。
 */
@Slf4j
class NoticePublishFlowTest extends ApiTestSupport {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private AsyncTaskService asyncTaskService;

    /** 路径② 要**绕过** registry 造任务 —— 只能直接写库（`insert` 不进内存登记表）。 */
    @Autowired
    private SysAsyncTaskRepository taskRepository;

    /** 本类造出来的任务 id —— 收尾先删通知再删任务行（顺序固定）。 */
    private final List<String> taskIds = new ArrayList<>();

    @AfterEach
    void cleanProbeArtifacts() {
        for (String taskId : taskIds) {
            // 🔴 **顺序固定：先抓对象名 → 删对象 → 删通知行 → 删任务行。**
            //    导出类任务在 MinIO 上留了一个 CSV，而它的名字**只活在任务结果里**
            //    （`sys_async_task.result` **不持久化**）—— 行一删就再也拿不到那个对象名，
            //    于是桶里多一个谁也认领不了的对象。
            //    ⚠️ **初版漏了这一步**，一轮全量后 `audit-export/` 真对象从 **2 涨到 9**；
            //    而 `T-O11` 的兜底**接不住它** —— 那条约定的射程是"归属不存在的对象"，
            //    而这个是 `admin`（真实账号）名下的，只能由用例自己清。
            String objectKey = objectKeyOf(taskId);
            if (objectKey != null) {
                removeObject(objectKey);
            }
            // 通知随后删：`source_task_id` 一旦悬空，通知就成了无主
            // （基类兜底能扫到，但**本类造的东西本类自己清**）。
            jdbc.update("delete from sys_notice where source_task_id = ?", taskId);
            jdbc.update("delete from sys_async_task where task_id = ?", taskId);
        }
        taskIds.clear();
        // 设置行必须还原：`admin` 是**种子账号**，不是探针 ——
        // 留着一条"关掉上传类通知"的设置，会让**后面所有用例**收不到通知而红在别处。
        jdbc.update("delete from sys_notice_setting where user_id = (select id from sys_user where username = ?)",
                ADMIN);
    }

    // ==================== 路径① · 真流程 ====================

    @Test
    @DisplayName("G6-12 路径①：真跑一次导出 → 通知落库、带可直达动作、且**带着 result 里的下载地址**")
    void exportTaskProducesNoticeWithDownloadAction() {
        String token = loginAsAdmin().data("$.accessToken");
        String today = LocalDate.now().format(DATE);

        var accepted = post("/api/audit/export", Map.of("startTime", today, "endTime", today), token)
                .assertOk("提交导出");
        String taskId = accepted.data("$.taskId");
        taskIds.add(taskId);

        AsyncTaskInfo<?> info = awaitTerminal(taskId);
        assertThat(info.getStatus()).as("导出任务必须真跑成功，errorMsg=%s", info.getErrorMsg())
                .isEqualTo(AsyncTaskStatus.DONE);

        // ① 通知真的落库了（不是 toast —— 需求 §2.9）
        Map<String, Object> row = awaitNotice(taskId);
        assertThat(row.get("type")).isEqualTo("SYSTEM");
        assertThat(row.get("source_type")).as("从通知能反查是哪种任务产出的").isEqualTo("audit-export");

        // ② §2.10 验收要点：每条通知**都有至少一个可执行动作**
        String actions = (String) row.get("actions_json");
        assertThat(actions).as("动作非空且能解").contains("下载导出文件");
        assertThat(actions).as("🔴 动作里必须有**真的下载地址** —— 它只在 result 里拿得到（P10）")
                .contains("http");

        // ③ 归属：本批的产出方是异步任务终态、总带 owner ⇒ **不产广播行**。
        //    这条同时是"广播行的清理没着落"那条登记的守门人（见计划 §9）。
        assertThat(((Number) row.get("user_id")).longValue())
                .as("必须定向给提交人，不能是 NULL（广播行按 user_id 删不掉）")
                .isEqualTo(adminUserId());
    }

    // ==================== 路径② · 库回退 ====================

    @Test
    @DisplayName("🔴 P2：库回退路径也产通知 —— 只接一条路会让「重启后领走的长任务」安静跑完")
    void bothTerminalPathsPublish() {
        long adminId = adminUserId();
        String taskId = insertTaskOutsideRegistry("audit-export", adminId);

        // 🔴🔴 **这里绝对不许调 `asyncTaskService.find(taskId)`。**
        //
        //    第一版在这里写了一句"前提：这条任务不在内存登记表里"的断言，用的是 `find`。
        //    结果**既写错了、又把前提毁了**：`find` 在内存未命中时会**回落查库并回填内存**
        //    （`AsyncTaskService:243`「回填内存：下次轮询不必再查库」）。
        //    于是那一步一执行，registry 里就有了这条任务 ⇒ 下面的 `reportDone` 走的是
        //    **路径①（内存命中）**，本判据根本没碰到库回退分支。
        //    它甚至"通过"了 —— 是**证伪实验**（把 else 分支的发布注释掉）**没让它转红**
        //    才把这个空判据揪出来的。**观测动作改变了被观测的状态**，与 `#210` 同族。
        //
        //    ⇒ 正确的前提保证是**行为上的**：`insertTaskOutsideRegistry` 只写库、不碰 registry，
        //      而本用例此后**不再调用 `find`** ⇒ `reportDone` 里 `registry.get(taskId)` 必为 null
        //      ⇒ 必走库回退分支。证据在结果里：只有那一支拿不到 `result`，
        //      也就只有它会产出「去审计页」这个**退回动作**（见下面那条断言）。
        asyncTaskService.reportDone(taskId, 0, 0);

        Map<String, Object> row = awaitNotice(taskId);
        assertThat(row.get("source_type")).isEqualTo("audit-export");
        // ⚠️ 这一支**拿不到 result**（领取方不持有它）⇒ 动作必须**退回一个总达的入口**，
        //    而不是编一个假的下载地址。这条断言就是这个取舍的凭据。
        assertThat((String) row.get("actions_json"))
                .as("没有 result 时退回审计页入口，而不是编一个地址")
                .contains("去审计页");
    }

    @Test
    @DisplayName("库回退路径的失败分支同样产通知（且归「系统告警」）")
    void reportFailedPublishesAlert() {
        String taskId = insertTaskOutsideRegistry("audit-export", adminUserId());
        asyncTaskService.reportFailed(taskId, "导出时数据库连接超时");

        Map<String, Object> row = awaitNotice(taskId);
        assertThat(row.get("type")).isEqualTo("ALERT");
        assertThat(row.get("need_action")).as("失败需要人来处理 ⇒ 进「待我处理」").isEqualTo(true);
        assertThat((String) row.get("content"))
                .as("**不许只说「失败」两字** —— 现象 + 下一步动作")
                .contains("数据库连接超时").contains("重新导出");
    }

    @Test
    @DisplayName("本批不产通知的任务类型安静跳过（不为不存在的流程写代码）")
    void uncoveredTaskTypesAreSkipped() {
        String taskId = insertTaskOutsideRegistry("DOC_SCAN", adminUserId());
        asyncTaskService.reportDone(taskId, 0, 0);

        assertThat(countNotices(taskId)).as("DOC_SCAN 本批明确不覆盖（需求未点名）").isZero();
    }

    // ==================== 开关真的管事 ====================

    @Test
    @DisplayName("🔴 设置开关**真的拦住发布** —— 否则「系统告警强制开启」那句是纯装饰")
    void disabledTypeStopsPublishing() {
        long adminId = adminUserId();
        String token = loginAsAdmin().data("$.accessToken");

        // 关掉「上传与解析」
        put("/api/notices/settings",
                Map.of("list", List.of(Map.of("type", "UPLOAD", "enabled", false))), token)
                .assertOk("关掉上传类通知");

        String off = insertTaskOutsideRegistry("doc-upload", adminId);
        asyncTaskService.reportDone(off, 0, 0);
        assertThat(countNotices(off)).as("关掉的类型不该产出通知").isZero();

        // 🔴 负向锚（差分）：把它打开，**同样的流程**必须产出 ——
        //    这一半才是"闸真的在测东西"的证据。少了它，上面那条断言可能是
        //    因为别的原因（比如 doc-upload 压根不在覆盖表里）而通过。
        put("/api/notices/settings",
                Map.of("list", List.of(Map.of("type", "UPLOAD", "enabled", true))), token)
                .assertOk("打开上传类通知");

        String on = insertTaskOutsideRegistry("doc-upload", adminId);
        asyncTaskService.reportDone(on, 0, 0);
        assertThat(countNotices(on)).as("打开后同样的流程必须产出").isEqualTo(1);
    }

    // ==================== P9 · 发布失败不外溢 ====================

    @Test
    @DisplayName("🔴 P9：发布器内部任何失败**都不外溢** —— 否则会把任务终态一起带走")
    void publishFailureDoesNotEscape() {
        // 注入一个必抛的仓储：这是最现实的失败形态（库连接断了）。
        // ⚠️ 本判据**重新界定了 P9**（见计划 §8 的偏差记录）：
        //    初版写的是"注入必抛的**发布器**"，但**调用方并没有防御** ——
        //    防御在发布器内部（try/catch 包住全部）。所以能承诺、也应当验的是
        //    「**发布器自己的失败不外溢**」，而不是"调用方接到必抛替身也不出事"。
        NoticeRepository boom = new NoticeRepository(null) {
            @Override
            public TaskCoord findTask(String taskId) {
                throw new IllegalStateException("注入的必抛仓储（P9）");
            }
        };
        NoticePublisher publisher = new NoticePublisher(boom);

        assertThatCode(() -> publisher.onTaskDone("any-task", null))
                .as("成功分支：发布失败不抛").doesNotThrowAnyException();
        assertThatCode(() -> publisher.onTaskFailed("any-task", "原因"))
                .as("失败分支：发布失败不抛").doesNotThrowAnyException();

        // 另一半（集成侧）：发布发生在 `persistTerminal()` **之后** ——
        // 顺序在 `TaskContext#complete` 里，这条顺序就是"终态先成为事实"的保证。
        // 它由 P1/P2 的实际观察覆盖（通知落库 ⇒ 终态早已写好）。
    }

    // ==================== 小工具 ====================

    /**
     * 从任务结果里取 `objectKey` —— **必须赶在删任务行之前**（行一删，对象名就丢了）。
     *
     * <p>与 `AuditQueryFlowTest#objectKeyOf` 同一写法、同一理由（`T-O10` 那条硬规矩：
     * **归属 id 必须在删行之前抓**）。取不到返回 {@code null}（本类造的路径② 任务就没有结果）。
     */
    private String objectKeyOf(String taskId) {
        Object result = asyncTaskService.find(taskId).map(AsyncTaskInfo::getResult).orElse(null);
        if (!(result instanceof Map<?, ?> map)) {
            return null;
        }
        Object key = map.get("objectKey");
        return key == null ? null : String.valueOf(key);
    }

    private void removeObject(String objectKey) {
        try {
            minioClient.removeObject(io.minio.RemoveObjectArgs.builder()
                    .bucket(minioBucket).object(objectKey).build());
        } catch (Exception ex) {
            log.warn("删除导出对象失败：{}（{}）", objectKey, ex.getMessage());
        }
    }

    private long adminUserId() {
        return jdbc.queryForObject("select id from sys_user where username = ?", Long.class, ADMIN);
    }

    private int countNotices(String taskId) {
        return jdbc.queryForObject("select count(*) from sys_notice where source_task_id = ?",
                Integer.class, taskId);
    }

    /**
     * 造一条**不在内存登记表里**的任务（= 路径② 的前提）。
     *
     * <p>{@code SysAsyncTaskRepository#insert} 只写库、不碰 registry ——
     * 所以这样造出来的任务，与服务重启后由 worker 领走的那种**处境相同**
     * （{@code AsyncTaskService#find} 走的是"内存未命中回落查库"）。
     */
    private String insertTaskOutsideRegistry(String type, long ownerId) {
        String taskId = "noticeprobe" + System.nanoTime();
        AsyncTaskInfo<Object> info = new AsyncTaskInfo<>();
        info.setTaskId(taskId);
        info.setType(type);
        info.setStatus(AsyncTaskStatus.PENDING);
        info.setStage("排队中");
        info.setCreatedAt(LocalDateTime.now());
        taskRepository.insert(info, null, null, ownerId, null);
        taskIds.add(taskId);
        return taskId;
    }

    /**
     * 等通知落库，返回那一行。
     *
     * <h3>🔴 为什么不能"查到任务终态就立刻查通知"</h3>
     * <p>路径① 上，终态在**内存 registry** 里是可轮询的最快快照，而发布是同一条线程上的
     * 后续几步：{@code complete()} 先置 status=DONE 与 finishedAt，**然后**才
     * {@code persistTerminal()} → {@code publisher.onTaskDone(...)}。
     * 于是测试线程完全可能在"registry 已说 DONE"而"通知还没 INSERT"的那一瞬就去查库 ——
     * 初版正是这样**在单跑时通过、在整类一起跑时偶发失败**。
     *
     * <p>这是 `#210` 那个形状的又一次现身：**观测点跑在被观测动作之前**。
     * 那次的观测方是用例收尾（夹住了异步写库的提交窗口），这次的观测方是断言自己。
     * ⇒ 修法同样是"等一个确定性的信号"，而不是"跑一次没红就算过了"。
     */
    private Map<String, Object> awaitNotice(String taskId) {
        for (int i = 0; i < 50; i++) {
            var rows = jdbc.queryForList(
                    "select type, title, content, actions_json, need_action, user_id, source_type "
                            + "from sys_notice where source_task_id = ?", taskId);
            if (!rows.isEmpty()) {
                return rows.getFirst();
            }
            sleep();
        }
        throw new AssertionError("5 秒内没等到通知落库，taskId=" + taskId);
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待时被中断", ex);
        }
    }

    private AsyncTaskInfo<?> awaitTerminal(String taskId) {
        for (int i = 0; i < 100; i++) {
            Optional<AsyncTaskInfo<Object>> found = asyncTaskService.find(taskId);
            if (found.isPresent() && found.get().getStatus().terminal()) {
                return found.get();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待任务终态时被中断", ex);
            }
        }
        throw new AssertionError("导出任务 10 秒内未到终态，taskId=" + taskId);
    }
}
