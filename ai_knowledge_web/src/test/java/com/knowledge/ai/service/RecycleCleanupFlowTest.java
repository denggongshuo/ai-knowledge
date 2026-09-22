package com.knowledge.ai.service;

import com.knowledge.ai.core.notice.NoticePublisher;
import com.knowledge.ai.support.RecycleTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `G7-13` 定时清理**幂等** + `§7.9` 清理失败**走通知中心广播**。
 *
 * <h3>为什么幂等要单独一个类</h3>
 * <p>它不是"再跑一次也不报错"那么弱 —— 判据是**第二次的 `cleaned` 必须是 0**。
 * 两者的差别在于：一个"把回收站整个清掉"的实现同样满足"不报错"，
 * 却把**没超期的条目**也一起干了（那是数据丢失，不是幂等）。
 * ⇒ 判据要落在"**超期的那条清了、没超期的那条还在**"上。
 *
 * <h3>广播那一对：正例与反例缺一不可</h3>
 * <p>"无提交人就广播"这条口子如果开大了，`#110` 那一族（提交方漏传坐标）会从
 * 「少一条通知」变成「给所有人发一条莫名其妙的通知」。所以本类**同时**断言：
 * ① `recycle-cleanup` 无 owner ⇒ **有**广播；② `batch-download` 无 owner ⇒ **没有**通知。
 * 只验①会把白名单偷偷变成"NULL 就广播"而看不出来。
 */
class RecycleCleanupFlowTest extends RecycleTestSupport {

    @Autowired
    private NoticePublisher noticePublisher;

    @Test
    @DisplayName("G7-13：清理只清**超期**的；第二次跑 cleaned = 0（幂等，不是「不报错」）")
    void cleanupIsIdempotentAndOnlyTouchesExpired() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());
        long kbId = createProbeKb(token);

        long expired = ((Number) uploadReady(kbId, "超期探针.md", token).data("$.docId")).longValue();
        long fresh = ((Number) uploadReady(kbId, "未超期探针.md", token).data("$.docId")).longValue();
        call("DELETE", "/api/doc/" + expired, null, token).assertOk("软删超期那篇");
        call("DELETE", "/api/doc/" + fresh, null, token).assertOk("软删未超期那篇");
        // 把其中一篇推到 40 天前（超过 30 天保留期）
        jdbc.update("update sys_document set deleted_at = ? where id = ?",
                LocalDateTime.now().minusDays(40), expired);

        RawResponse first = post("/api/recycle/cleanup", null, token).assertOk("第一次清理");
        assertThat((Integer) first.data("$.cleaned")).as("只清超期的那 1 条").isEqualTo(1);
        assertThat(docRowExists(expired)).as("超期的那篇被**物理**删掉了").isFalse();
        assertThat(docRowExists(fresh)).as("🔴 未超期的那篇**必须还在** —— 这正是幂等与「不报错」的差别所在")
                .isTrue();

        RawResponse second = post("/api/recycle/cleanup", null, token).assertOk("第二次清理");
        assertThat((Integer) second.data("$.cleaned"))
                .as("幂等的判据就是这个 0：清理条件本身只认未清的、超期的行")
                .isZero();
        // ⚠️ 必须显式转型：`data()` 是 `<T> T data(String)`，不给类型时
        //    `String.valueOf` 的重载解析会挑中 `valueOf(char[])`（`ClassCastException`：
        //    String cannot be cast to char[]）—— 与 `assertThat` 那个歧义同源。
        assertThat((String) second.data("$.note")).contains("无需清理");
    }

    @Test
    @DisplayName("§7.9：定时清理**失败**发**广播**告警；而漏传 owner 的普通任务**不**广播")
    void cleanupFailureBroadcastsButOwnerlessOrdinaryTasksDoNot() {
        // ① 正例：recycle-cleanup 没有提交人 ⇒ 走广播（user_id 写 NULL）
        String cleanupTask = insertOwnerlessTask(RecycleService.TASK_TYPE_CLEANUP);
        noticePublisher.onTaskFailed(cleanupTask, "模拟失败：对象存储不可达");

        List<java.util.Map<String, Object>> broadcast = jdbc.queryForList(
                "select type, title, content, user_id, actions_json from sys_notice "
                        + "where source_task_id = ?", cleanupTask);
        assertThat(broadcast).as("定时清理失败**必须**留一条通知（需求 §7.9）").hasSize(1);
        java.util.Map<String, Object> row = broadcast.getFirst();
        assertThat(row.get("type")).isEqualTo("ALERT");
        assertThat(row.get("user_id")).as("🔴 广播的判据就是 user_id 为 NULL（V31 的列语义）").isNull();
        assertThat((String) row.get("title")).contains("回收站清理失败");
        assertThat((String) row.get("content")).as("失败原因必须具体（G4-3）").contains("不可达");
        assertThat((String) row.get("actions_json")).as("每条通知至少一个可执行动作")
                .contains("/recycle");

        // ② 反例：同样是 owner 为 NULL，但**不在白名单里** ⇒ 不广播
        String downloadTask = insertOwnerlessTask("batch-download");
        noticePublisher.onTaskFailed(downloadTask, "模拟失败：磁盘满了");
        assertThat(jdbc.queryForList("select id from sys_notice where source_task_id = ?", downloadTask))
                .as("🔴 **不能**把白名单写成「NULL 就广播」—— 那会把「提交方漏传坐标」这条静默缺陷 "
                        + "升级成给所有人发噪声")
                .isEmpty();
    }

    // ==================== 小工具 ====================

    /** 插一条**没有提交人**的任务行（定时任务的真实形态：三个坐标全空）。 */
    private String insertOwnerlessTask(String type) {
        String taskId = "probe-" + Long.toString(System.nanoTime(), 36);
        jdbc.update("insert into sys_async_task (task_id, type, status, owner_id, stage, percent, "
                        + "chunk_done, chunk_total, attempt, created_at, updated_at) "
                        + "values (?, ?, 'RUNNING', null, '探针', 10, 0, 0, 1, now(), now())",
                taskId, type);
        return taskId;
    }
}
