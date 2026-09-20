package com.knowledge.ai.service;

import com.knowledge.ai.core.ops.MissKeyNormalizer;
import com.knowledge.ai.core.ops.QaMissRepository;
import com.knowledge.ai.support.ApiTestSupport;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 未命中清单的**读侧 + 状态机 + 权限边界**（`T-A00` · 需求 `08` §1.2/§1.3/§1.8）。
 *
 * <h3>判据对齐</h3>
 * <ul>
 *   <li>`G8-2` 按**聚合**（非每次提问一行）+ 按提问次数降序</li>
 *   <li>`G8-3` 状态机四条边；「已忽略」记**忽略人**；被忽略的**不计入待办数**</li>
 *   <li>`G8-4` / `G6-13` 待办数与 `/notices/pending` **同源**</li>
 *   <li>`G8-9` 仅管理员可见 —— `USER` 两个接口都进不去，`AUDITOR` **能读不能改**</li>
 * </ul>
 *
 * <h3>为什么夹具走真的写入口（`QaMissRepository#upsert`）</h3>
 * <p>它与生产走的是**同一个方法**（`MissSink` 只是把打点行的字段喂给它）。
 * 用 SQL 直插的话，"聚合"这件事就成了我手写的，测的就不是聚合逻辑了。
 * ⚠️ 而"打点 → 沉淀"那一段**接线**由 `ChatStreamFlowTest#missIsSunkIntoPool` 用真链路验
 * —— 两处各自只失败于一个原因。
 *
 * <h3>自清</h3>
 * <p>池子行挂在探针账号名下（`last_user_id`），收尾按**自增 id 水位线**删 ——
 * 与 `ChatStreamFlowTest` 同一条口径。⚠️ **不能只靠基类的无主兜底**：
 * 那一套只在"人已经删了"时才命中，而本类若中途失败，行会带着一个**还有效**的
 * `last_user_id` 留下来（`ProbeCleanup#sweepOrphanMisses` 的 javadoc 写明了这个射程）。
 */
@Slf4j
class QaMissFlowTest extends ApiTestSupport {

    @Autowired
    private QaMissRepository qaMissRepository;

    private String username;
    private long userId;
    private String token;

    /** `sys_qa_miss` 的自增水位线 —— 之后新增的行全是本轮造的。 */
    private long missFloor;

    /**
     * 探针账号**必须是 KB_ADMIN**。
     *
     * <p>⚠️ `createUser` 造出来的默认是 `USER`，而 `ops:qa:view`/`ops:qa:handle` 只有
     * `KB_ADMIN`（与 `AUDITOR` 的读半边）—— 拿默认角色跑，**每条用例都会 10020**，
     * 而现象是"接口没实现"（`S6-M09-B` 的 `T-904` 踩过同一个坑：用例拿超管 token，
     * 于是"权限挂错"这件事**永远不会红**）。这里反过来钉死：探针账号就是那个该有权限的角色。
     *
     * <p>⚠️ 换角色后**必须重新登录** —— 角色在 JWT 里。
     */
    @BeforeEach
    void setUpProbe() {
        var probe = createUser("opsqa.probe");
        username = probe.username();
        userId = jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
        assignRole(userId, "KB_ADMIN");
        token = login(username, probe.password()).data("$.accessToken");
        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from sys_qa_miss", Long.class);
        missFloor = max == null ? 0L : max;
    }

    @AfterEach
    void removeFixtures() {
        jdbc.update("delete from sys_qa_miss where id > ?", missFloor);
    }

    // ==================== G8-2 · 聚合 ====================

    @Test
    @DisplayName("G8-2：同一问法的不同写法合成一行、次数累加；列表按次数降序")
    void aggregatesByNormalizedQuestion() {
        // 三次"同一个问题"（三种标点写法）+ 一次另一个问题
        String key = MissKeyNormalizer.keyOf("报销要几天");
        qaMissRepository.upsert(key, "报销要几天？", userId, 0.21, "1,2", LocalDateTime.now());
        qaMissRepository.upsert(key, "报销要几天！！", userId, 0.29, "1,2", LocalDateTime.now());
        qaMissRepository.upsert(key, "报销 要 几天", userId, 0.13, "1,2", LocalDateTime.now());
        qaMissRepository.upsert(MissKeyNormalizer.keyOf("量子纠缠的贝尔不等式"),
                "量子纠缠的贝尔不等式怎么推导", userId, 0.02, "3", LocalDateTime.now());

        Map<String, Object> page = get("/api/ops/qa/misses", token).assertOk("未命中清单").data("$");

        assertThat(num(page, "total")).as("聚合后只有 2 行 —— 不是把每次提问当成一行").isEqualTo(2);
        assertThat(num(page, "askedTotal")).as("提问次数合计 = 3 + 1").isEqualTo(4);

        List<Map<String, Object>> list = listOf(page);
        Map<String, Object> top = list.getFirst();
        assertThat(top.get("askCount")).as("默认按提问次数降序：3 次的那条在最前").isEqualTo(3);
        assertThat(top.get("question"))
                .as("存的是**第一次**记下的原始问法，后续重复提问不改写它（需求 §1.2）")
                .isEqualTo("报销要几天？");
        assertThat(((Number) top.get("topScore")).doubleValue())
                .as("最高相似度取三次里的最大值 —— 一次都没过线才叫未命中")
                .isEqualTo(0.29);
    }

    @Test
    @DisplayName("筛选：状态 / 关键词 / 按库（kb_ids 是逗号串 ⇒ FIND_IN_SET）")
    void filtersWork() {
        seed("报销要几天", "1,2", 0.21);
        seed("合同违约金上限", "2", 0.22);
        seed("Milvus 支持多少维", "3", 0.27);

        assertThat(questions("/api/ops/qa/misses?keyword=合同", token)).containsExactly("合同违约金上限");
        assertThat(questions("/api/ops/qa/misses?kbId=3", token)).containsExactly("Milvus 支持多少维");
        assertThat(questions("/api/ops/qa/misses?kbId=2", token))
                .as("`1,2` 那一行横跨两个库 ⇒ 按任一库筛都命中（近似，不是精确关联）")
                .containsExactlyInAnyOrder("报销要几天", "合同违约金上限");
        assertThat(questions("/api/ops/qa/misses?status=IGNORED", token)).isEmpty();
    }

    // ==================== G8-3 · 状态机 ====================

    @Test
    @DisplayName("G8-3：状态流转 + 忽略人 + 被忽略的不计入待办数")
    void statusMachineAndIgnorer() {
        long id = seed("报销要几天", "1", 0.21);
        assertThat(pending()).as("刚沉淀的问题默认是待处理").isEqualTo(1);

        // 待处理 → 已加入清单：**仍然算待办**（有人认领了，但还没解决）
        patch(id, "IN_LIST");
        assertThat(pending()).as("IN_LIST 不算解决，仍在待办里").isEqualTo(1);

        // 已加入清单 → 已忽略：记忽略人，且**不再算待办**
        Map<String, Object> ignored = patch(id, "IGNORED");
        assertThat(num(ignored, "pending")).as("被忽略的不计入待办数（需求 §1.3）").isZero();
        assertThat(pending()).isZero();
        assertThat(ignoredBy(id))
                .as("必须记忽略人 —— 否则同一问题会被反复忽略又反复冒出")
                .isEqualTo(realName());

        // 忽略 → 恢复待处理：待办数回来
        patch(id, "PENDING");
        assertThat(pending()).as("恢复边存在，否则被忽略的问题永久沉底").isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("PENDING");

        // → 已解决：离开待办
        patch(id, "RESOLVED");
        assertThat(pending()).isZero();
        assertThat(jdbc.queryForObject("select resolved_at from sys_qa_miss where id = ?",
                LocalDateTime.class, id)).as("已解决要留下时刻（闭环证据）").isNotNull();
    }

    @Test
    @DisplayName("状态机：不认识的状态 10030；不存在的 id 10040")
    void illegalInputIsRejected() {
        long id = seed("报销要几天", "1", 0.21);

        assertThat(call("PATCH", "/api/ops/qa/misses/" + id, Map.of("status", "DONE"), token).code())
                .as("拼错的状态必须报错，不能静默当成 PENDING —— 那会让页面上显示的状态骗人")
                .isEqualTo(10030);
        assertThat(call("PATCH", "/api/ops/qa/misses/99999999", Map.of("status", "PENDING"), token).code())
                .isEqualTo(10040);
    }

    // ==================== G8-9 · 权限边界 ====================

    @Test
    @DisplayName("G8-9：USER 两个接口都进不去；AUDITOR 能读**不能改**")
    void permissionBoundary() {
        long id = seed("报销要几天", "1", 0.21);

        Actor user = userWithRole("opsqa.user", "USER");
        assertThat(get("/api/ops/qa/misses", user.token()).status())
                .as("普通用户不该看到全站的提问原文").isEqualTo(403);
        assertThat(get("/api/ops/qa/overview", user.token()).status()).isEqualTo(403);

        Actor auditor = userWithRole("opsqa.auditor", "AUDITOR");
        assertThat(get("/api/ops/qa/misses", auditor.token()).code())
                .as("审计员能看（V32 授予了 ops:qa:view）").isZero();
        assertThat(call("PATCH", "/api/ops/qa/misses/" + id, Map.of("status", "IGNORED"), auditor.token()).status())
                .as("但**不能改** —— 这正是把读/写拆成两个权限码的意义；"
                        + "合成一个码的话这条边界永远证伪不了")
                .isEqualTo(403);

        assertThat(get("/api/ops/qa/misses", token).code()).as("KB_ADMIN 两个码都有").isZero();
    }

    // ==================== G6-13 · 同源 ====================

    @Test
    @DisplayName("G6-13：待办数在三处是同一个数（overview / 清单回话 / notices/pending）")
    void pendingIsSameSourceEverywhere() {
        seed("报销要几天", "1", 0.21);
        seed("合同违约金上限", "2", 0.22);

        int overview = (int) num(overview(), "pending");
        assertThat(overview).isEqualTo(2).isEqualTo(pending());

        List<Map<String, Object>> pendingItems = get("/api/notices/pending", token).assertOk("待我处理")
                .data("$.list");
        Map<String, Object> miss = pendingItems.stream()
                .filter(p -> "miss".equals(p.get("key"))).findFirst()
                .orElseThrow(() -> new AssertionError("未命中那一项必须出现（有源了就该发）"));
        assertThat(((Number) miss.get("count")).intValue())
                .as("角标 / 11 页统计卡 / 待我处理 三处同源")
                .isEqualTo(overview);

        Map<String, Object> pendingRes = get("/api/notices/pending", token).assertOk("待我处理").data("$");
        long sum = pendingItems.stream().mapToLong(p -> ((Number) p.get("count")).longValue()).sum();
        assertThat(num(pendingRes, "total"))
                .as("`total` 是各类目 count 之和，不是类目个数（与 mock 一致）")
                .isEqualTo(sum);
    }

    // ==================== 需求 §1.1 · 四指标 ====================

    @Test
    @DisplayName("overview：四指标来自打点窗口；无数据时 avgTopScore 是 null 而不是 0")
    void overviewComesFromTraces() {
        Map<String, Object> empty = overview();
        assertThat(num(empty, "totalQuestions")).isZero();
        assertThat(empty.get("avgTopScore"))
                .as("没有提问过 ⇒ 平均相似度**没有**。给 0 会被读成'召回质量极差'（承 #169）")
                .isNull();

        insertTrace("t-opsqa-1", "报销要几天", 1, 0.21);
        insertTrace("t-opsqa-2", "报销要几天", 1, 0.18);
        insertTrace("t-opsqa-3", "公司年会在哪办", 0, 0.88);

        Map<String, Object> res = overview();
        assertThat(num(res, "totalQuestions")).isEqualTo(3);
        assertThat(num(res, "missed")).as("未命中按**提问次数**算（需求 §1.1 的定义）").isEqualTo(2);
        assertThat(((Number) res.get("missedRate")).doubleValue()).isEqualTo(66.7);
        assertThat(((Number) res.get("avgTopScore")).doubleValue()).isCloseTo(0.4233, offset(0.001));

        // 空召回那一行：Top1 为 NULL ⇒ **不进分母**（不是按 0 计）
        insertTrace("t-opsqa-4", "没有召回到任何东西", 0, null);
        assertThat(((Number) overview().get("avgTopScore")).doubleValue())
                .as("把'没有分数'按 0 算会把整体质量凭空拉低")
                .isCloseTo(0.4233, offset(0.001));
    }

    // ==================== 夹具 ====================

    private long seed(String question, String kbIds, double score) {
        qaMissRepository.upsert(MissKeyNormalizer.keyOf(question), question, userId, score, kbIds,
                LocalDateTime.now());
        Long id = jdbc.queryForObject("select id from sys_qa_miss where miss_key = ?",
                Long.class, MissKeyNormalizer.keyOf(question));
        assertThat(id).as("沉淀必须真的落库").isNotNull();
        return id;
    }

    private void insertTrace(String traceId, String query, int missFlag, Double top1) {
        jdbc.update("insert into sys_retrieval_trace "
                        + "(trace_id, query_text, user_id, miss_flag, top1_score, created_at) "
                        + "values (?, ?, ?, ?, ?, now())",
                traceId, query, userId, missFlag, top1);
    }

    private Map<String, Object> overview() {
        return get("/api/ops/qa/overview?range=7d", token).assertOk("四指标").data("$");
    }

    private Map<String, Object> patch(long id, String status) {
        return call("PATCH", "/api/ops/qa/misses/" + id, Map.of("status", status), token)
                .assertOk("改状态 → " + status).data("$");
    }

    private int pending() {
        return (int) num(overview(), "pending");
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("select status from sys_qa_miss where id = ?", String.class, id);
    }

    private String ignoredBy(long id) {
        return jdbc.queryForObject("select u.real_name from sys_qa_miss m join sys_user u "
                + "on u.id = m.ignored_by where m.id = ?", String.class, id);
    }

    private String realName() {
        return jdbc.queryForObject("select real_name from sys_user where username = ?", String.class, username);
    }

    private List<String> questions(String path, String accessToken) {
        return listOf(get(path, accessToken).assertOk("清单").data("$")).stream()
                .map(m -> (String) m.get("question")).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("list");
    }

    private long userIdOf(String username) {
        Long id = jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
        assertThat(id).as("账号 %s 应该存在", username).isNotNull();
        return id;
    }

    private static long num(Map<String, Object> map, String key) {
        Object value = map.get(key);
        assertThat(value).as("响应里必须有字段 %s", key).isNotNull();
        return ((Number) value).longValue();
    }

    private static org.assertj.core.data.Offset<Double> offset(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }

    /** 与 `ChatSessionApiFlowTest` 同形：造号 → 换角色 → **重新登录**（角色在 JWT 里）。 */
    private Actor userWithRole(String prefix, String roleCode) {
        TestUser created = createUser(prefix);
        long uid = userIdOf(created.username());
        assignRole(uid, roleCode);
        return new Actor(created.username(),
                login(created.username(), created.password()).data("$.accessToken"));
    }

    /** 把账号的角色换成**唯一**一个 {@code roleCode}。 */
    private void assignRole(long uid, String roleCode) {
        jdbc.update("delete from sys_user_role where user_id = ?", uid);
        jdbc.update("insert into sys_user_role (user_id, role_id, created_at) "
                + "select ?, id, now() from sys_role where code = ?", uid, roleCode);
        assertThat(jdbc.queryForObject("select count(*) from sys_user_role where user_id = ?",
                Integer.class, uid)).as("角色切换必须生效，否则后面测的是旧权限").isEqualTo(1);
    }

    private record Actor(String username, String token) {
    }
}
