package com.knowledge.ai.service;

import com.knowledge.ai.support.ApiTestSupport;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `M11` 通知中心的**读侧**门禁（`T-B00`/`T-B01`/`T-B02`）。
 *
 * <h3>判据对齐</h3>
 * <ul>
 *   <li>{@code G6-13} 分类计数合计 = 全部数（需求 §2.10）</li>
 *   <li>{@code G6-13} 系统告警开关**不可关闭**（需求 §2.5 / §2.10）</li>
 *   <li>{@code G6-13} 已读**按用户维度**存（需求 §2.7 实现约束）</li>
 *   <li>{@code T-B01} 「待我处理」—— ⚠️ **本批只验有源的那一半**（解析问题）。
 *       未命中问题那一半的源属 `T-A00`（S8），见计划 §1.2/§6 判据 P7（挂起）</li>
 * </ul>
 *
 * <h3>为什么夹具用 JDBC 直插而不用真流程造</h3>
 * <p>读侧要验的是**筛选、计数、分组、可见性**这些逻辑，造数据的方式与它们无关；
 * 用真流程造 5 条不同类型/不同时间的通知会很啰嗦，而且会把"写侧的问题"混进来 ——
 * 写侧在 {@code NoticePublishFlowTest} 里单独验（那边**一律走真流程**）。
 * ⇒ 一条判据只让它失败于一个原因。
 *
 * <h3>自清纪律</h3>
 * <p>夹具一律挂在本类新建的**探针账号**名下 ⇒ 收尾删账号，通知随之变成无主、
 * 被基类的 {@code sweepOrphanNotices} 扫掉（那是设计好的清理路径，不是侥幸）。
 * ⚠️ **例外是 `readIsPerUser`**：它必须用**广播**通知（`user_id is null`）才能验
 * "同一条通知两个人各自已读" —— 而广播行**谁也认领不了**（按 `user_id` 删的那条路够不着它），
 * 所以那一条夹具由本类**按 id 显式删**。这个例外本身就是"广播行的清理没着落"的实证，
 * 已登记进计划 §9。
 */
@Slf4j
class NoticeFlowTest extends ApiTestSupport {

    /** 探针账号 —— 夹具全挂在它名下。 */
    private String username;

    private long userId;

    private String token;

    /** 显式跟踪的夹具 id（只给广播行用 —— 见类注释的"例外"）。 */
    private final List<Long> explicitIds = new java.util.ArrayList<>();

    @BeforeEach
    void setUpProbe() {
        var probe = createUser("notice.probe");
        username = probe.username();
        token = probe.accessToken();
        userId = jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
    }

    @org.junit.jupiter.api.AfterEach
    void removeExplicitFixtures() {
        for (Long id : explicitIds) {
            jdbc.update("delete from sys_notice_read where notice_id = ?", id);
            jdbc.update("delete from sys_notice where id = ?", id);
        }
        explicitIds.clear();
    }

    // ==================== G6-13 · 计数 ====================

    @Test
    @DisplayName("G6-13：分类计数合计 = 全部数；未读只数没读过的那几条")
    void countsAddUpToAll() {
        insert("UPLOAD", "解析完成", true);
        insert("UPLOAD", "表格未识别", false);
        insert("ALERT", "索引失败", true);
        insert("PERMISSION", "角色调整", false);
        insert("SYSTEM", "模型配置变更", false);

        Map<String, Object> counts = counts(token);
        assertThat(num(counts, "all")).as("全部 = 5").isEqualTo(5);
        assertThat(num(counts, "unread")).as("未读 = 2").isEqualTo(2);
        assertThat(num(counts, "UPLOAD")).isEqualTo(2);
        assertThat(num(counts, "ALERT")).isEqualTo(1);
        assertThat(num(counts, "PERMISSION")).isEqualTo(1);
        assertThat(num(counts, "SYSTEM")).isEqualTo(1);

        // 🔴 需求 §2.10 的第一条验收要点：四类**合计必须等于全部**。
        // 这条断言看着是废话，但它抓的是"某一类漏算/多算"——
        // mock 里那份示例数据就是靠 9+4+3+12=28 自证的（数据字典 §8）。
        long sum = num(counts, "UPLOAD") + num(counts, "ALERT")
                + num(counts, "PERMISSION") + num(counts, "SYSTEM");
        assertThat(sum).as("四类合计必须 = all（需求 §2.10）").isEqualTo(num(counts, "all"));
    }

    @Test
    @DisplayName("列表按类型 / 已读筛选；group 是按今天现算的（不落库）")
    void listFiltersAndGroups() {
        insert("UPLOAD", "解析完成", true);
        insert("ALERT", "索引失败", false);

        var all = get("/api/notices", token).assertOk("列表");
        assertThat((int) all.<Integer>data("$.list.length()")).isEqualTo(2);

        var onlyAlert = get("/api/notices?type=ALERT", token).assertOk("按类型筛");
        assertThat((int) onlyAlert.<Integer>data("$.list.length()")).isEqualTo(1);
        assertThat((String) onlyAlert.data("$.list[0].type")).isEqualTo("ALERT");

        var onlyUnread = get("/api/notices?read=unread", token).assertOk("按未读筛");
        assertThat((int) onlyUnread.<Integer>data("$.list.length()")).isEqualTo(1);

        // ⚠️ group 是**服务端现算**的相对日期（今天/昨天/更早）—— 落库当天是对的、第二天就错了，
        //    所以它不能是列。这条断言钉住"它确实按 created_at 算出来了"。
        assertThat((String) all.data("$.list[0].group")).isEqualTo("今天");
        assertThat((String) all.data("$.list[0].time")).as("time 形状与前端 mock 一致").matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
        assertThat(((List<?>) all.data("$.list[0].actions")).size()).as("§2.10：每条都有动作").isEqualTo(1);
    }

    // ==================== G6-13 · 已读按用户维度 ====================

    @Test
    @DisplayName("G6-13：已读按用户维度 —— 甲标已读，乙看同一条仍是未读")
    void readIsPerUser() {
        // 🔴 必须用**广播**通知才验得了这件事：定向通知乙根本看不见，
        //    "乙仍未读"就成了废话（他连看都看不到）。
        long broadcastId = insertBroadcast("系统维护通知");

        var other = createUser("notice.other");
        String otherToken = other.accessToken();
        assertThat(unread(otherToken)).as("乙也看得见广播通知").isEqualTo(1);

        call("PATCH", "/api/notices/" + broadcastId + "/read", Map.of("read", true), token)
                .assertOk("甲标已读");

        assertThat(unread(token)).as("甲的未读归 0").isZero();
        assertThat(unread(otherToken)).as("🔴 乙的未读不受影响 —— 已读是按用户存的（§2.7）").isEqualTo(1);

        // 已读可逆（§2.7）：标回未读要真的回得去
        call("PATCH", "/api/notices/" + broadcastId + "/read", Map.of("read", false), token)
                .assertOk("甲标回未读");
        assertThat(unread(token)).as("已读可逆").isEqualTo(1);
    }

    @Test
    @DisplayName("越权：改别人的定向通知 → 10040（不区分「没有这条」与「不归你看」，避免探测）")
    void cannotMarkOthersNotice() {
        var other = createUser("notice.other2");
        long otherId = jdbc.queryForObject("select id from sys_user where username = ?", Long.class,
                other.username());
        long theirs = insertFor(otherId, "UPLOAD", "别人的通知", false);

        var res = call("PATCH", "/api/notices/" + theirs + "/read", Map.of("read", true), token);
        assertThat(res.code()).as("不可见 = 不存在，不回 403（否则能探测出「这条存在」）").isEqualTo(10040);
    }

    @Test
    @DisplayName("read-all：一条语句全标已读，且幂等（第二次 updated 为 0）")
    void readAllIsIdempotent() {
        insert("UPLOAD", "a", true);
        insert("SYSTEM", "b", true);

        var first = post("/api/notices/read-all", Map.of(), token).assertOk("全部已读");
        assertThat(((Number) first.data("$.updated")).longValue()).as("两条都新标").isEqualTo(2);
        assertThat(((Number) first.data("$.unread")).longValue()).isZero();

        var second = post("/api/notices/read-all", Map.of(), token).assertOk("再标一次");
        assertThat(((Number) second.data("$.updated")).longValue()).as("幂等：没有新的可标").isZero();
    }

    // ==================== G6-13 · 设置 ====================

    @Test
    @DisplayName("G6-13：系统告警**关不掉** —— 请求被剔除且回话说明；其余三类照常生效")
    void alertCannotBeDisabled() {
        var saved = put("/api/notices/settings", Map.of("list", List.of(
                Map.of("type", "ALERT", "enabled", false),
                Map.of("type", "UPLOAD", "enabled", false))), token).assertOk("保存设置");

        List<?> applied = saved.data("$.applied");
        assertThat(applied).as("只应用了 UPLOAD 那一条").hasSize(1);
        assertThat((String) saved.data("$.note")).as("必须回话说明，否则前端以为保存成功了")
                .contains("强制开启");

        // 落库侧也卡住：ALERT 连"关掉的行"都不该存在
        assertThat(jdbc.queryForObject(
                "select count(*) from sys_notice_setting where user_id = ? and type = 'ALERT'",
                Integer.class, userId)).as("ALERT 不允许落 enabled=0 的行").isZero();

        var list = get("/api/notices/settings", token).assertOk("读设置");
        assertThat((int) list.<Integer>data("$.list.length()")).as("四类永远全给").isEqualTo(4);
        assertThat(findSetting(list, "ALERT").get("enabled")).isEqualTo(true);
        assertThat(findSetting(list, "ALERT").get("forced")).as("给前端置灰用").isEqualTo(true);
        assertThat(findSetting(list, "UPLOAD").get("enabled")).isEqualTo(false);

        // 没行 = 默认开启：PERMISSION 从没被改过，它必须是 true
        assertThat(findSetting(list, "PERMISSION").get("enabled")).as("没有行 = 默认开启").isEqualTo(true);
    }

    @Test
    @DisplayName("未知类型回 10030（别静默忽略 —— 前端拼错类型会以为保存成功了）")
    void unknownTypeRejected() {
        var res = put("/api/notices/settings",
                Map.of("list", List.of(Map.of("type", "NOPE", "enabled", false))), token);
        assertThat(res.code()).isEqualTo(10030);
    }

    // ==================== T-B01 · 待我处理 ====================

    @Test
    @DisplayName("T-B01：pending **只出有源的那半**（解析问题），未命中那半不发 —— 不发 ≠ 发 0")
    void pendingOnlyShowsParseIssues() {
        // 源 = sys_parse_issue。kb_id 指向一个不存在的库 ⇒ 收尾由 kb 族兜底扫掉。
        // ⚠️ 夹具刻意造成「2 篇文档 / 3 个问题行」——见下面的负向锚。
        insertParseIssue(9_000_001L, 9_000_011L);
        insertParseIssue(9_000_002L, 9_000_012L);
        insertParseIssue(9_000_002L, 9_000_012L); // 同一篇文档的第二个问题行

        var res = get("/api/notices/pending", token).assertOk("待我处理");
        List<Map<String, Object>> list = res.data("$.list");

        assertThat(list).as("🔴 只有一项：未命中那一项必须**不存在**，而不是 count=0").hasSize(1);
        assertThat(list.getFirst().get("key")).isEqualTo("parse");
        assertThat(((Number) list.getFirst().get("count")).longValue())
                .as("按**文档**去重：2 篇")
                .isEqualTo(2);

        // ⚠️ 负向锚：问题行确实是 **3 行**，而上一条只数出 2 —— 两者差 1 就是
        //    `count(distinct doc_id)` 与 `count(*)` 的差。谁把去重写成 count(*)，上一条立刻转红。
        assertThat(jdbc.queryForObject(
                "select count(*) from sys_parse_issue where doc_id in (9000011, 9000012)",
                Integer.class)).as("问题行确实是 3 行（2 篇文档）").isEqualTo(3);
    }

    // ==================== 小工具 ====================

    /** 夹具：定向给本类探针账号的一条通知。 */
    private void insert(String type, String title, boolean unread) {
        long id = insertFor(userId, type, title, unread);
        if (!unread) {
            jdbc.update("insert into sys_notice_read (notice_id, user_id, read_at) values (?, ?, now())", id, userId);
        }
    }

    /** 夹具：广播通知（`user_id` 为 NULL）—— 只能由本类显式删，见类注释。 */
    private long insertBroadcast(String title) {
        jdbc.update("insert into sys_notice (type, title, content, actions_json, need_action, muteable, "
                        + "user_id, created_at) values ('SYSTEM', ?, '内容', ?, 0, 1, null, now())",
                title, "[{\"label\":\"查看详情\",\"to\":\"/notice\",\"primary\":true}]");
        long id = jdbc.queryForObject("select max(id) from sys_notice", Long.class);
        explicitIds.add(id);
        return id;
    }

    private long insertFor(long owner, String type, String title, boolean unread) {
        jdbc.update("insert into sys_notice (type, title, content, actions_json, need_action, muteable, "
                        + "user_id, created_at) values (?, ?, '内容', ?, 0, 1, ?, now())",
                type, title, "[{\"label\":\"查看详情\",\"to\":\"/notice\",\"primary\":true}]", owner);
        // ⚠️ `max(id)` 而不是 `last_insert_id()` —— JdbcTemplate 可能换连接，`last_insert_id()` 是会话级的。
        return jdbc.queryForObject("select max(id) from sys_notice", Long.class);
    }

    private void insertParseIssue(long kbId, long docId) {
        jdbc.update("insert into sys_parse_issue (doc_id, kb_id, page_no, reason, created_at) "
                + "values (?, ?, 1, '表格未识别', now())", docId, kbId);
    }

    private Map<String, Object> counts(String accessToken) {
        return get("/api/notices", accessToken).assertOk("列表计数").data("$.counts");
    }

    private long unread(String accessToken) {
        return ((Number) get("/api/notices/unread-count", accessToken).assertOk("未读数").data("$.count")).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findSetting(RawResponse res, String type) {
        List<Map<String, Object>> list = res.data("$.list");
        return list.stream().filter(s -> type.equals(s.get("type"))).findFirst().orElseThrow();
    }

    private static long num(Map<String, Object> map, String key) {
        return ((Number) map.get(key)).longValue();
    }
}
