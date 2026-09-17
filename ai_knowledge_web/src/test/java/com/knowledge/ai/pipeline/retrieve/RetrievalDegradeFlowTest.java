package com.knowledge.ai.pipeline.retrieve;

import com.knowledge.ai.core.enums.RetrievalMode;
import com.knowledge.ai.core.setting.SystemSettings;
import com.knowledge.ai.service.ReindexService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重建期降级的门禁用例（`T-709` · 《11》§2.1「降级而非中断」）。
 *
 * <h3>本用例防的是一件"看起来正常"的事</h3>
 * <p>降级如果不生效，现象**不是报错**，而是"重建期间检索照样走向量" ——
 * 用户看到的结果条数正常、页面正常，只是内容来自正在换代的那份索引。
 * 所以这里的断言不看"有没有结果"，只看 **trace 上写的是哪一种 mode**。
 *
 * <h3>还有两条"假降级"要挡掉</h3>
 * <ul>
 *   <li><b>本来就配成 KEYWORD 的库被报成"因重建降级"</b> —— trace 上写着一句假话，
 *       运维会去查一个根本没发生的降级。见 {@link #keywordModeIsNeverReportedAsDegraded()}。</li>
 *   <li><b>别的库在重建却把这次也降了</b> —— 降级范围过宽，没有任何痕迹。</li>
 * </ul>
 *
 * <h3>用例自己插的任务行自己删</h3>
 * <p>{@code sys_async_task} 是已知的测试残留产物（`plan/汇总.md` §3 #98/#105/#109/#115
 * —— `mvn test` 一轮约 58~76 行）。本用例**不添新的**：自己插的行记下来，
 * 在 {@code @AfterEach} 里按 task_id 删掉。用 `cleanProbeData()` 删不掉它们 ——
 * 那个方法按 kb_id 清，而这里插的正是"归属正确"的行。
 */
class RetrievalDegradeFlowTest extends RetrievalFlowSupport {

    private static final String HYBRID_CONFIG =
            "{\"mode\":\"HYBRID\",\"recallThreshold\":0.15,\"rerankTopK\":3,"
                    + "\"rerankEnabled\":true,\"tableWeight\":1.2,\"identifierProtect\":true}";

    private static final String KEYWORD_CONFIG =
            "{\"mode\":\"KEYWORD\",\"recallThreshold\":0.15,\"rerankTopK\":3,"
                    + "\"rerankEnabled\":true,\"tableWeight\":1.2,\"identifierProtect\":true}";

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private ReindexService reindexService;

    private final List<String> createdTaskIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        resetProbeState();
        RetrievalTestStubs.resetRerank();
        kbId = insertKb("degrade-probe", "INTERNAL", HYBRID_CONFIG);
        long docId = insertDocWithChunks("降级探针",
                "第一段正文，讲的是分片方案与容量规划",
                "第二段正文，讲的是迁移步骤与回滚预案",
                "第三段正文，讲的是监控告警与值班安排");
        seedVectors(docId, 1);
        awaitVectorsVisible("正文", 3);
    }

    @AfterEach
    void tearDown() {
        for (String taskId : createdTaskIds) {
            jdbc.update("delete from sys_async_task where task_id = ?", taskId);
        }
        createdTaskIds.clear();
        RetrievalTestStubs.resetRerank();
        cleanProbeData();
    }

    // ==================== 判据 9 / 10：降级生效且留痕、问答仍可用 ====================

    @Test
    @DisplayName("T-709 · 库在重建 → 本次降级为 KEYWORD，trace 写明原因，且**不报错**")
    void rebuildingKbDegradesToKeyword() {
        insertRunningReindex(kbId);

        RetrievalResult result = retrievalService.search(reader(), "正文", List.of(kbId));

        assertThat(result.trace().mode())
                .as("降级必须体现在**实际使用的** mode 上 —— trace.mode 是有效的那个，不是配置里的那个")
                .isEqualTo(RetrievalMode.KEYWORD);
        assertThat(result.trace().degraded()).isTrue();
        assertThat(result.trace().degradeNote())
                .as("降级必须能回答「为什么」—— 静默降级是本项目明令禁止的形态")
                .contains("重建").contains("关键词")
                .as("还要说清是**从哪一种**降下来的，否则看不出被牺牲掉了什么")
                .contains(RetrievalMode.HYBRID.getLabel());
    }

    @Test
    @DisplayName("T-709 · 降级期间问答**仍可用**：不抛异常，trace 各段齐全")
    void degradedSearchStillReturnsAWellFormedResult() {
        insertRunningReindex(kbId);

        RetrievalResult result = retrievalService.search(reader(), "正文", List.of(kbId));

        assertThat(result).as("「降级而非中断」的全部含义就是「它得有返回值」").isNotNull();
        assertThat(result.trace()).isNotNull();
        assertThat(result.trace().kbCount()).isEqualTo(1);
        assertThat(result.trace().totalMillis()).isGreaterThanOrEqualTo(0);
        assertThat(result.trace().chunks())
                .as("G5-9 同源不受降级影响").isSameAs(result.chunks());
    }

    // ==================== 三条不该降的 ====================

    @Test
    @DisplayName("T-709 · 本来就配成 KEYWORD → **不算降级**（trace 上不许写假话）")
    void keywordModeIsNeverReportedAsDegraded() {
        // 改**主库**的库级配置，而不是另建一个空库：
        // 空库没有分区，`RetrievalScope` 会走"可见范围为空"那条短路，
        // 返回的 trace 里 mode 是 null（没发生过检索，也就没有"实际使用的方式"）——
        // 那样用例根本走不到被判定的那条路上，却会以"expected KEYWORD but was null"红，
        // 看着像降级判错了，实际是夹具建错了
        jdbc.update("update sys_kb set retrieval_config_json = ? where id = ?", KEYWORD_CONFIG, kbId);
        insertRunningReindex(kbId);

        RetrievalResult result = retrievalService.search(reader(), "正文", List.of(kbId));

        assertThat(result.trace().mode()).isEqualTo(RetrievalMode.KEYWORD);
        assertThat(result.trace().degraded())
                .as("没有可降的东西就不是降级。报成降级会让运维去查一个没发生的事")
                .isFalse();
        assertThat(result.trace().degradeNote()).isEmpty();
    }

    @Test
    @DisplayName("T-709 · 全局开关关掉 → 不降级（需求里「暂停问答服务」那一侧的开关位）")
    void switchOffKeepsTheConfiguredMode() {
        String original = jdbc.queryForObject(
                "select setting_value from sys_setting where setting_key = ?", String.class,
                SystemSettings.RETRIEVAL_DEGRADE_DURING_REINDEX);
        insertRunningReindex(kbId);
        try {
            jdbc.update("update sys_setting set setting_value = 'false' where setting_key = ?",
                    SystemSettings.RETRIEVAL_DEGRADE_DURING_REINDEX);

            RetrievalResult result = retrievalService.search(reader(), "正文", List.of(kbId));

            assertThat(result.trace().mode()).isEqualTo(RetrievalMode.HYBRID);
            assertThat(result.trace().degraded()).isFalse();
        } finally {
            jdbc.update("update sys_setting set setting_value = ? where setting_key = ?",
                    original, SystemSettings.RETRIEVAL_DEGRADE_DURING_REINDEX);
        }
        assertThat(jdbc.queryForObject(
                "select setting_value from sys_setting where setting_key = ?", String.class,
                SystemSettings.RETRIEVAL_DEGRADE_DURING_REINDEX))
                .as("用例必须把开关还原（否则后面所有检索用例都在「不降级」的配置下跑）")
                .isEqualTo(original);
    }

    @Test
    @DisplayName("T-709 · 没有库在重建 → 按配置走 HYBRID，不降级")
    void noRebuildKeepsHybrid() {
        RetrievalResult result = retrievalService.search(reader(), "正文", List.of(kbId));

        assertThat(result.trace().mode()).isEqualTo(RetrievalMode.HYBRID);
        assertThat(result.trace().degraded()).isFalse();
    }

    @Test
    @DisplayName("T-709 · 重建的是**别的库**时，本次不降级（范围不许放大）")
    void otherKbRebuildingDoesNotDegradeThisOne() {
        long otherKb = insertKb("degrade-other", "INTERNAL", HYBRID_CONFIG);
        insertRunningReindex(otherKb);

        RetrievalResult result = retrievalService.search(reader(), "正文", List.of(kbId));

        assertThat(result.trace().degraded())
                .as("降级的依据是「参与本次检索的库里有没有在重建」，不是「全站有没有」")
                .isFalse();
        assertThat(result.trace().mode()).isEqualTo(RetrievalMode.HYBRID);
    }

    @Test
    @DisplayName("T-709 · 任务已是终态（DONE/FAILED）→ 不降级 —— 重建跑完要能**自动恢复**")
    void terminalTaskDoesNotDegrade() {
        insertTask(kbId, "DONE");

        RetrievalResult result = retrievalService.search(reader(), "正文", List.of(kbId));

        assertThat(result.trace().degraded())
                .as("只看 PENDING/RUNNING。漏判终态会让库**永久降级**，而没有任何东西会把它改回来")
                .isFalse();
    }

    // ==================== 判据 8：降级判据的**前置** ====================

    @Test
    @DisplayName("T-709 前置 · 重建任务的 sys_async_task.kb_id 必须落库（否则降级恒不触发）")
    void reindexTaskCarriesItsKbId() {
        // 空的探针库：重建会很快走到 NOTHING_TO_DO，避免与收尾清理抢同一次写入
        long emptyKb = insertKb("degrade-reindex", "INTERNAL", HYBRID_CONFIG);

        String taskId = reindexService.submit(emptyKb);
        createdTaskIds.add(taskId);

        Long kbIdInDb = jdbc.queryForObject(
                "select kb_id from sys_async_task where task_id = ?", Long.class, taskId);
        assertThat(kbIdInDb)
                .as("kb_id 为 NULL 时，`RebuildStateService` 认不出是哪个库在重建 —— "
                        + "于是「重建期降级」整个功能**永不触发，而且不报错**")
                .isEqualTo(emptyKb);

        awaitTaskTerminal(taskId);
    }

    // ==================== 夹具 ====================

    /** 插一行**在飞**的重建任务（`RUNNING`）—— 不带幂等键，避免与真实任务撞。 */
    private void insertRunningReindex(long kb) {
        insertTask(kb, "RUNNING");
    }

    private void insertTask(long kb, String status) {
        String taskId = "probe-degrade-" + kb + "-" + status + "-" + System.nanoTime();
        jdbc.update("""
                        insert into sys_async_task (task_id, type, status, kb_id, doc_id, owner_id, trace_id,
                                                    idempotency_key, stage, percent, chunk_done, chunk_total,
                                                    attempt, created_at, updated_at)
                        values (?, 'KB_REINDEX', ?, ?, null, 1, 'probe-degrade', null, '重建中', 0, 0, 0,
                                0, now(), now())
                        """, taskId, status, kb);
        createdTaskIds.add(taskId);
    }

    /** 等任务走到终态，免得收尾清库时它还在写（`S4-E1` 登记过的那条竞态）。 */
    private void awaitTaskTerminal(String taskId) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            String status = jdbc.queryForObject(
                    "select status from sys_async_task where task_id = ?", String.class, taskId);
            if ("DONE".equals(status) || "FAILED".equals(status)) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("重建任务 " + taskId + " 30 秒内没有走到终态 —— "
                + "收尾清理会与它抢同一次写入，本用例的清理不再可信");
    }
}
