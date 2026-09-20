package com.knowledge.ai.service;

import com.knowledge.ai.core.ops.MissKeyNormalizer;
import com.knowledge.ai.support.ApiTestSupport;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 高频问题榜 + 点踩归因的**读侧**（`T-A02` · 需求 `08` §1.5/§1.6）。
 *
 * <h3>判据对齐</h3>
 * <ul>
 *   <li>`G8-2` 半边：高频榜按**归一化**聚合（非每次提问一行）+ 按次数降序</li>
 *   <li>需求 §1.10：「用户问题**保持原始问法**，未被改写」</li>
 *   <li>🔴 需求 §1.10「点踩原因次数合计 = 负反馈总数」**在有"没填原因的点踩"时不成立**
 *       ⇒ 本类按"三个数都如实给"验：`reasons` 合计 + `unreasoned` = `negativeTotal`</li>
 *   <li>需求 §1.6：负反馈率的分母是**提问总数**（与 `overview` 同源）</li>
 * </ul>
 *
 * <h3>🔴 为什么每条断言都自己造行，不读"库里现成的"</h3>
 * <p>本批开工实测：`sys_retrieval_trace` / `sys_chat_message` **都是 0 行**。
 * 而这两个接口的全部价值就在于聚合 —— 拿空库去验，验到的只是"空的时候不报错"。
 * ⇒ 每条用例都真造打点行与反馈行（`insertTrace` / `insertFeedback`），
 * 并**自己清掉**（`@AfterEach` 的水位线 + 窗口清理）。
 *
 * <h3>⚠️ 刻意没写的两条（怕它们变成空断言）</h3>
 * <ol>
 *   <li>**不断言"空窗口返回空列表"** —— 那要求先证明窗口里真的没有别人的行，
 *       而"断言环境状态"正是 `#185` 记的那个坑（用一条断言把"别人造的数据"当成自己的失败）。
 *       缺的那半边由 `windowBoundary` 正向覆盖：**我自己造的行**在窗口内出现、在窗口外不出现。</li>
 *   <li>**不断言负反馈率的绝对数** —— 分母是"窗口内提问总数"，而那是全库共享的。
 *       改成**跨接口对账**：`rate` 必须等于 `negativeTotal ÷ overview.totalQuestions`
 *       （同一时刻两次读取）。分母若换了口径，这条立刻红。</li>
 * </ol>
 */
@Slf4j
class OpsQaStatsFlowTest extends ApiTestSupport {

    private String username;
    private long userId;
    private String token;

    private long messageFloor;
    private long sessionFloor;

    private static final List<String> CLOSED_SET = List.of(
            "答案不准确", "没找到相关内容", "引用来源不对", "回答太啰嗦", "其他");

    @BeforeEach
    void setUpProbe() {
        // ⚠️ 必须是 KB_ADMIN（`ops:qa:view` 只有它和 AUDITOR）—— 拿默认 USER 跑，
        // 每条用例都会 403，而现象是"接口没实现"（`QaMissFlowTest` 的类注释记了同一个坑）
        // ⚠️ 前缀要**短**：`createUser` 会拼上 `.时间戳毫秒.纳秒后三位`，
        // 而用户名上限是 **32 位**（`AuthDtos.LoginRequest` 的 `@Pattern`）——
        // 「opsstats.probe」这种 15 位前缀会**时灵时不灵**（取决于纳秒那三位有几位数）
        TestUser probe = createUser("oa.probe");
        username = probe.username();
        userId = userIdOf(username);
        assignRole(userId, "KB_ADMIN");
        token = login(username, probe.password()).data("$.accessToken");

        Long m = jdbc.queryForObject("select coalesce(max(id), 0) from sys_chat_message", Long.class);
        messageFloor = m == null ? 0L : m;
        Long s = jdbc.queryForObject("select coalesce(max(id), 0) from sys_chat_session", Long.class);
        sessionFloor = s == null ? 0L : s;
    }

    /**
     * 收尾：**先删消息再删会话**（子查询要靠会话），再清打点窗口。
     *
     * <p>打点窗口与 `QaMissFlowTest` 用同一个清理口径（7 天）—— 两处都得清，
     * 因为本类与它共用同一张表，而「清理必须走必走路径」：`@AfterEach` 就是那条路。
     */
    @AfterEach
    void removeFixtures() {
        jdbc.update("delete from sys_chat_message where id > ?", messageFloor);
        jdbc.update("delete from sys_chat_session where id > ?", sessionFloor);
        clearTraceWindow();
    }

    // ==================== 高频问题榜（需求 §1.5）====================

    @Test
    @DisplayName("G8-2 半边：同一问法的三种写法合成一行、次数累加；均分按**有分数的行**加权")
    void topQuestionsAggregateByNormalizedQuestion() {
        insertTrace("t-a", "报销要几天？", 0.20, 3);
        insertTrace("t-b", "报销要几天!", 0.40, 2);
        insertTrace("t-c", "报销 要 几天", null, 1);
        insertTrace("t-d", "量子纠缠是什么", 0.90, 1);

        Map<String, Object> res = topQuestions("7d", 20);
        assertThat(num(res, "total")).as("三种写法合成一组 + 另一个问题 = 2 组").isEqualTo(2);

        List<Map<String, Object>> list = listOf(res);
        Map<String, Object> top = list.getFirst();
        assertThat(top.get("count")).as("三次提问累加 —— 不是把每种写法各算一组").isEqualTo(3);
        assertThat(((Number) top.get("avgTopScore")).doubleValue())
                .as("均值 0.30 = (0.20 + 0.40) / 2 —— 没有分数那一行**不进分母**。"
                        + "只取最大值会给 0.40，只取第一行会给 0.20，两者都能被这条区分")
                .isEqualTo(0.30, within(1e-9));
    }

    @Test
    @DisplayName("需求 §1.10：展示的是**最近一次**的原始问法，不是归一化后的键")
    void topQuestionsKeepRawWording() {
        insertTrace("t-a", "报销要几天？", 0.20, 5);
        insertTrace("t-b", "报销要几天！！", 0.30, 3);
        insertTrace("t-c", "报销 要 几天", 0.40, 1);

        String shown = String.valueOf(listOf(topQuestions("7d", 20)).getFirst().get("question"));

        assertThat(shown)
                .as("三行里最近的那一次（`t-c`）才是展示用的问法")
                .isEqualTo("报销 要 几天");
        assertThat(shown)
                .as("🔴 需求 §1.10 要的是**原始问法** —— 归一化后的串（去掉标点与空格）"
                        + "是聚合键，人读不懂。这条断言正是那句话的机器检查")
                .isNotEqualTo(MissKeyNormalizer.normalize(shown));
    }

    @Test
    @DisplayName("按次数降序；`limit` 在**合并之后**截断；`total` 给的是合并后的组数")
    void topQuestionsOrderedByCountAndLimit() {
        // 次数是**造出来的**：一次提问一行，所以"问了 3 次"要插 3 行。
        // ⚠️ 别把 `insertTrace` 的第 4 个参数当次数 —— 那是 `minutesAgo`（第一版就是这么写错的）
        insertTrace("t-h1", "高频问题", 0.50, 9);
        insertTrace("t-h2", "高频问题", 0.50, 8);
        insertTrace("t-h3", "高频问题", 0.50, 7);
        insertTrace("t-m1", "中频问题", 0.50, 6);
        insertTrace("t-m2", "中频问题", 0.50, 5);
        insertTrace("t-l1", "低频问题", 0.50, 4);

        List<Map<String, Object>> all = listOf(topQuestions("7d", 20));
        assertThat(all).extracting(r -> String.valueOf(r.get("question")))
                .as("按提问次数降序").containsExactly("高频问题", "中频问题", "低频问题");

        Map<String, Object> limited = topQuestions("7d", 1);
        assertThat(listOf(limited)).as("limit 只截行数").hasSize(1);
        assertThat(num(limited, "total"))
                .as("⚠️ `total` 是**合并后的组数**，不跟着 limit 缩 —— 它答的是"
                        + "「一共有几种问法」，不是「这一页有几行」")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("一条都没拿到过分时 `avgTopScore` 是 null，**不是 0**")
    void avgTopScoreStaysNullWhenNothingScored() {
        // 空召回行：`top1_score` 为 NULL（不是 0 分）—— 见 `RetrievalTraceWriter#top1`
        insertTrace("t-a", "谁都没召回来的问题", null, 3);
        insertTrace("t-b", "谁都没召回来的问题!", null, 2);
        insertTrace("t-c", "谁都没召回来的问题", null, 1);

        Map<String, Object> top = listOf(topQuestions("7d", 20)).getFirst();
        assertThat(top.get("count")).isEqualTo(3);
        assertThat(top.get("avgTopScore"))
                .as("🔴 0 会被读成「召回质量极差」，而事实是**一条都没召回上来**"
                        + "（`#169` 的同一条口径：不能用 0 顶替「没有数据」）")
                .isNull();
    }

    @Test
    @DisplayName("窗口边界：30 天前的提问在 7d 里看不见、在 90d 里看得见")
    void topQuestionsRespectWindow() {
        insertTrace("t-new", "最近问的问题", 0.50, 1);
        insertTraceAt("t-old", "很久以前问的问题", 0.50, 30);

        assertThat(questionsOf(topQuestions("7d", 20)))
                .as("窗口外的不算 —— 否则「7 天」这个参数是假的")
                .containsExactly("最近问的问题");
        assertThat(questionsOf(topQuestions("90d", 20)))
                .contains("很久以前问的问题");
    }

    // ==================== 点踩归因（需求 §1.6）====================

    @Test
    @DisplayName("🔴 `reasons` 合计 + `unreasoned` = `negativeTotal`，且总数与**独立 SQL** 相符")
    void reasonsPlusUnreasonedEqualsTotal() {
        long session = insertSession();
        insertFeedback(session, "unlike", "答案不准确", 0);
        insertFeedback(session, "unlike", "答案不准确", 0);
        insertFeedback(session, "unlike", "没找到相关内容", 0);
        // 🔴 点踩但**没填原因** —— 需求 §6.8 说原因是"可选"的，服务层也允许
        insertFeedback(session, "unlike", null, 0);
        // 点赞不算负反馈（且它本来就不带原因）
        insertFeedback(session, "like", null, 0);
        // 窗口外的不算
        insertFeedback(session, "unlike", "答案不准确", 30);

        Map<String, Object> res = feedback("7d");
        long total = num(res, "negativeTotal");
        long unreasoned = num(res, "unreasoned");
        long summed = listOf(res, "reasons").stream()
                .mapToLong(r -> ((Number) r.get("count")).longValue()).sum();

        assertThat(total)
                .as("unlike 总数 = 4（第 5 行是点赞、第 6 行在窗口外）")
                .isEqualTo(4);
        assertThat(unreasoned).as("没填原因的那一条").isEqualTo(1);

        // ⚠️ 双向断言：只写 `summed + unreasoned == total` 的话，
        // 两处都为 0 时恒绿（`S8-M10-B` 的空断言教训）
        assertThat(summed).as("填了原因的合计 = 3（不能是 0 —— 那样这条等式无意义）").isEqualTo(3);
        assertThat(summed + unreasoned).as("这才是需求 §1.10 那条等式的**正确形状**").isEqualTo(total);

        // 再与**独立的 SQL** 对一遍 —— 总数若不是自己的观测点，上面那条等式会退化
        // 成"结构上恒真"（见 `QaOpsRepository#feedbackCounts` 的注释）
        Long bySql = jdbc.queryForObject(
                "select count(*) from sys_chat_message where feedback = 'unlike' and feedback_at >= ?",
                Long.class, LocalDateTime.now().minusDays(7));
        assertThat(total).as("与独立 count(*) 相符 —— `group by` 没丢行").isEqualTo(bySql);
    }

    @Test
    @DisplayName("闭集那 5 条**逐条都在**（缺的补 0）；占比按 total 算且加起来 + 未填原因 = 100%")
    void closedSetIsAlwaysComplete() {
        long session = insertSession();
        insertFeedback(session, "unlike", "回答太啰嗦", 0);
        insertFeedback(session, "unlike", "回答太啰嗦", 0);
        insertFeedback(session, "unlike", "回答太啰嗦", 0);
        insertFeedback(session, "unlike", null, 0);

        List<Map<String, Object>> reasons = listOf(feedback("7d"), "reasons");
        assertThat(reasons).extracting(r -> String.valueOf(r.get("reason")))
                .as("🔴 五条**逐条都在**：少一条的话，「这个原因的次数是 0」与"
                        + "「这个原因不存在」在页面上长得一模一样")
                .containsExactlyElementsOf(CLOSED_SET);
        // ⚠️ 按**文案查**而不是按下标 —— 下标是枚举顺序（`回答太啰嗦` 在第 4 位），
        // 拿 `getFirst()` 去断"回答太啰嗦"是错的（第一版就这么写错了一次）
        assertThat(countOf(reasons, "回答太啰嗦")).as("三次点踩都是这个原因").isEqualTo(3);
        assertThat(countOf(reasons, "答案不准确")).as("没出现过的原因补 0，不是缺失").isZero();
        assertThat(percentOf(reasons, "回答太啰嗦"))
                .as("占比的分母是**负反馈总数**（4），不是「出现过原因的那些」").isEqualTo(75.0);

        // 占比之和 + 未填原因那一份 = 100% —— 这才是「那 3 个点踩的构成」
        double percentSum = reasons.stream()
                .mapToDouble(r -> ((Number) r.get("percent")).doubleValue()).sum();
        Map<String, Object> stats = feedback("7d");
        long total = num(stats, "negativeTotal");
        long unreasoned = num(stats, "unreasoned");
        assertThat(unreasoned).as("4 条点踩里有 1 条没填原因").isEqualTo(1);
        assertThat(percentSum + unreasoned * 100.0 / total)
                .as("75%（回答太啰嗦 3 条）+ 25%（没填原因 1 条）= 100% —— "
                        + "若把未填原因的算进某个原因里，这条立刻红")
                .isEqualTo(100.0, within(0.1));
    }

    @Test
    @DisplayName("🔴 闭集**之外**的原因值不静默丢 —— 如实列出来，等式仍然成立")
    void unknownReasonIsNotSwallowed() {
        long session = insertSession();
        insertFeedback(session, "unlike", "答案不准确", 0);
        // 绕过白名单直写库的值（老数据 / 手工改库）—— `ChatFeedbackService#submit` 拦不住它
        insertFeedback(session, "unlike", "页面卡了", 0);

        Map<String, Object> res = feedback("7d");
        List<Map<String, Object>> reasons = listOf(res, "reasons");

        assertThat(reasons).extracting(r -> String.valueOf(r.get("reason")))
                .as("闭集 5 条 + 这个未知值 —— 丢掉它会让下面的等式差 1，"
                        + "而那个差在页面上没有任何现象")
                .hasSize(6).endsWith("页面卡了");
        long summed = reasons.stream().mapToLong(r -> ((Number) r.get("count")).longValue()).sum();
        assertThat(summed + num(res, "unreasoned"))
                .as("即使出现未知值，等式仍然成立（这正是「不静默丢」换来的）")
                .isEqualTo(num(res, "negativeTotal"));
    }

    @Test
    @DisplayName("需求 §1.6：负反馈率的分母是**提问总数** —— 与 `/ops/qa/overview` 同源")
    void negativeRateSharesDenominatorWithOverview() {
        insertTrace("t-a", "提问一", 0.50, 3);
        insertTrace("t-b", "提问二", 0.50, 2);
        insertTrace("t-c", "提问三", 0.50, 1);
        long session = insertSession();
        insertFeedback(session, "unlike", "答案不准确", 0);
        insertFeedback(session, "unlike", "其他", 0);

        Map<String, Object> stats = feedback("7d");
        long totalQuestions = num(get("/api/ops/qa/overview?range=7d", token)
                .assertOk("四指标").data("$"), "totalQuestions");
        assertThat(totalQuestions)
                .as("分母必须非零 —— 否则下面那条断言是空断言（`S8-M10-B` 的教训）")
                .isGreaterThanOrEqualTo(3);

        double expected = Math.round(num(stats, "negativeTotal") * 1000.0 / totalQuestions) / 10.0;
        assertThat(((Number) stats.get("negativeRate")).doubleValue())
                .as("🔴 分母若换成「有反馈的消息数」或「会话数」，这条立刻红 ——"
                        + " 而页面上两个卡片的比率会各自成立、彼此对不上")
                .isEqualTo(expected, within(1e-9));
        assertThat(((Number) stats.get("threshold")).doubleValue())
                .as("需求 §1.6 写定要对比的 5% —— 它是展示口径，不是可配置项")
                .isEqualTo(5.0, within(1e-9));
    }

    @Test
    @DisplayName("窗口边界：30 天前的点踩在 7d 里不算、在 90d 里算")
    void feedbackRespectsWindow() {
        long session = insertSession();
        insertFeedback(session, "unlike", "很久以前的踩", 30);

        assertThat(listOf(feedback("7d"), "reasons"))
                .extracting(r -> ((Number) r.get("count")).longValue())
                .as("7 天窗里一条都没有").containsOnly(0L);

        long in90 = listOf(feedback("90d"), "reasons").stream()
                .filter(r -> "很久以前的踩".equals(r.get("reason")))
                .mapToLong(r -> ((Number) r.get("count")).longValue()).sum();
        assertThat(in90).as("90 天窗里看得见（这条同时证明窗口参数真的生效了）").isEqualTo(1);
    }

    // ==================== G8-9 · 权限边界 ====================

    @Test
    @DisplayName("G8-9：读侧两个端点要 `ops:qa:view` —— USER 进不去、AUDITOR 能读")
    void readSidePermission() {
        TestUser created = createUser("oa.user");
        long uid = userIdOf(created.username());
        assignRole(uid, "USER");
        String userToken = login(created.username(), created.password()).data("$.accessToken");

        assertThat(get("/api/ops/qa/top-questions", userToken).status())
                .as("榜单里是全站用户的**提问原文** —— 与「我自己的通知」是两回事")
                .isEqualTo(403);
        assertThat(get("/api/ops/qa/feedback-stats", userToken).status()).isEqualTo(403);

        TestUser auditor = createUser("oa.auditor");
        long auditorId = userIdOf(auditor.username());
        assignRole(auditorId, "AUDITOR");
        String auditorToken = login(auditor.username(), auditor.password()).data("$.accessToken");

        assertThat(get("/api/ops/qa/top-questions", auditorToken).code())
                .as("审计员能读（`V32` 授予了 `ops:qa:view`）—— 这条同时是「接口真的挂上了」的接线判据")
                .isZero();
        assertThat(get("/api/ops/qa/feedback-stats", auditorToken).code()).isZero();

        assertThat(get("/api/ops/qa/top-questions", token).code()).as("KB_ADMIN 有读码").isZero();
    }

    // ==================== 内部 ====================

    /** 一条打点（`minutesAgo` 越大越旧）。`top1` 为 null = 空召回那一行。 */
    private void insertTrace(String traceId, String query, Double top1, int minutesAgo) {
        jdbc.update("insert into sys_retrieval_trace "
                        + "(trace_id, query_text, user_id, miss_flag, top1_score, created_at) "
                        + "values (?, ?, ?, 0, ?, now() - interval ? minute)",
                traceId, query, userId, top1, minutesAgo);
    }

    /** 窗口外的打点（天为单位）。 */
    private void insertTraceAt(String traceId, String query, Double top1, int daysAgo) {
        jdbc.update("insert into sys_retrieval_trace "
                        + "(trace_id, query_text, user_id, miss_flag, top1_score, created_at) "
                        + "values (?, ?, ?, 0, ?, now() - interval ? day)",
                traceId, query, userId, top1, daysAgo);
    }

    private long insertSession() {
        jdbc.update("insert into sys_chat_session (user_id, title, model_id, created_at, updated_at) "
                + "values (?, '运营统计用例会话', null, now(), now())", userId);
        Long id = jdbc.queryForObject("select max(id) from sys_chat_session", Long.class);
        assertThat(id).isNotNull();
        return id;
    }

    /** 一条带反馈的消息（`role='assistant'` —— 只有回答能点赞/踩，见 `ChatFeedbackService`）。 */
    private void insertFeedback(long sessionId, String feedback, String reason, int daysAgo) {
        jdbc.update("insert into sys_chat_message "
                        + "(session_id, role, content, feedback, feedback_reason, feedback_at, created_at) "
                        + "values (?, 'assistant', '回答正文', ?, ?, now() - interval ? day, now())",
                sessionId, feedback, reason, daysAgo);
    }

    private Map<String, Object> topQuestions(String range, int limit) {
        return get("/api/ops/qa/top-questions?range=" + range + "&limit=" + limit, token)
                .assertOk("高频问题榜").data("$");
    }

    private Map<String, Object> feedback(String range) {
        return get("/api/ops/qa/feedback-stats?range=" + range, token)
                .assertOk("点踩归因").data("$");
    }

    private static List<Map<String, Object>> listOf(Map<String, Object> res) {
        return listOf(res, "list");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> res, String key) {
        Object value = res.get(key);
        assertThat(value).as("响应里必须有数组 %s", key).isInstanceOf(List.class);
        return (List<Map<String, Object>>) value;
    }

    private static List<String> questionsOf(Map<String, Object> res) {
        return listOf(res).stream().map(r -> String.valueOf(r.get("question"))).toList();
    }

    private static long num(Map<String, Object> map, String key) {
        Object value = map.get(key);
        assertThat(value).as("响应里必须有字段 %s", key).isNotNull();
        return ((Number) value).longValue();
    }

    /** 按**原因文案**取次数（不按下标 —— 下标是枚举顺序，容易把断言的语义写反）。 */
    private static long countOf(List<Map<String, Object>> reasons, String reason) {
        return reasons.stream().filter(r -> reason.equals(r.get("reason")))
                .mapToLong(r -> ((Number) r.get("count")).longValue()).sum();
    }

    private static double percentOf(List<Map<String, Object>> reasons, String reason) {
        return reasons.stream().filter(r -> reason.equals(r.get("reason")))
                .mapToDouble(r -> ((Number) r.get("percent")).doubleValue()).sum();
    }

    private long userIdOf(String name) {
        Long id = jdbc.queryForObject("select id from sys_user where username = ?", Long.class, name);
        assertThat(id).as("账号 %s 应该存在", name).isNotNull();
        return id;
    }

    /**
     * 同 `QaMissFlowTest` / `ChatSessionApiFlowTest` 的写法：造号 → 换角色 → **重新登录**
     * （角色在 JWT 里，不重登测的还是旧权限）。
     */
    private void assignRole(long uid, String roleCode) {
        jdbc.update("delete from sys_user_role where user_id = ?", uid);
        jdbc.update("insert into sys_user_role (user_id, role_id, created_at) "
                + "select ?, id, now() from sys_role where code = ?", uid, roleCode);
        assertThat(jdbc.queryForObject("select count(*) from sys_user_role where user_id = ?",
                Integer.class, uid)).as("角色切换必须生效").isEqualTo(1);
    }

    /** 清 7 天窗口内的打点（与 `QaMissFlowTest#clearTraceWindow` 同一个口径）。 */
    private void clearTraceWindow() {
        jdbc.update("delete from sys_retrieval_trace_chunk where trace_id in "
                        + "(select trace_id from sys_retrieval_trace where created_at >= ?)",
                LocalDateTime.now().minusDays(7));
        jdbc.update("delete from sys_retrieval_trace where created_at >= ?",
                LocalDateTime.now().minusDays(7));
    }
}
