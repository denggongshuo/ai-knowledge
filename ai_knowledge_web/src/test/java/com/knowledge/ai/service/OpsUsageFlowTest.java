package com.knowledge.ai.service;

import com.knowledge.ai.support.ApiTestSupport;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 页 21 使用统计（`T-A05`）+ 统计离线预计算（`T-A04` · `G8-10`）。
 *
 * <h3>判据对齐</h3>
 * <ul>
 *   <li>`G8-8` 活跃用户口径：「至少提问 1 次」；`PENDING`/`DISABLED` **不计入**</li>
 *   <li>`G8-9` 合规：`AUDITOR` 能读聚合四个端点、读不到 `top-users`（**可证伪**）；不展示提问内容</li>
 *   <li>`G8-10` 离线预计算：统计**不实时扫明细**</li>
 *   <li>`G8-1` 后半：页 21 答「有没有人在用」，与页 11 / 页 20 **同源同一个数**</li>
 *   <li>需求 §3.8 的七条验收要点（其中「占比合计 = 100%」靠最大余数法）</li>
 * </ul>
 *
 * <h3>🔴 大部分判据写成「**内部等式**」而不是绝对数</h3>
 * <p>`sys_user` 的基线是 128 行（含 `probe.profile` 这个常驻联调设施），别的用例类还会再造号
 * ⇒ 任何"总用户数应该是 N"的绝对断言都会**随跑批顺序变红**，而那种红看起来像代码坏了。
 * 所以凡涉及全站基线的判据一律写成「A == B」（都在同一次响应或同一对响应里取），
 * 只对**本类自己造的打点行**用绝对数。
 *
 * <h3>⚠️ 清理必须同时清两张表</h3>
 * <p>汇总表是**缓存**：只清打点不清缓存的话，**过去那几天**的缓存行会留下来，
 * 于是下一个用例读到的是上一个用例的残影（而"今天"因为每次读都会重算，反倒看不出来）。
 * ⇒ `@BeforeEach` / `@AfterEach` 都调 {@link #clearAll()}。
 */
@Slf4j
class OpsUsageFlowTest extends ApiTestSupport {

    /** 与 `V2` 种子的 `retrieval.miss-threshold` 同值；造行**必须显式写**（列默认是 0）。 */
    private static final double MISS_THRESHOLD = 0.35;

    /** 种子里真实存在的两个部门（`V6`：1 研发中心 / 2 销售中心）。 */
    private static final long DEPT_A = 1L;
    private static final long DEPT_B = 2L;

    private long userId;
    private String token;

    /** 幂等判据直接调它跑两次（不必等定时任务，也不必走 HTTP）。 */
    @org.springframework.beans.factory.annotation.Autowired
    private OpsPrecomputeService precomputeService;

    @BeforeEach
    void setUpProbe() {
        clearAll();
        // 超管：`ops:usage:users:view` **只给超管** ⇒ 拿普通账号跑，后四个端点全会 403，
        // 而现象是"接口没实现"（`OpsRetrievalStatsFlowTest` 的类注释记了同一个坑）
        TestUser probe = createUser("ou.probe");
        userId = userIdOf(probe.username());
        assignRole(userId, "SUPER_ADMIN");
        setDeptAndStatus(userId, DEPT_A, "ACTIVE");
        token = login(probe.username(), probe.password()).data("$.accessToken");
    }

    @AfterEach
    void clearFixtures() {
        clearAll();
    }

    // ==================== `G8-8` 活跃用户口径 ====================

    @Test
    @DisplayName("G8-8：停用账号**不计入活跃**，但他的提问仍然计入提问总数")
    void disabledUserIsNotActive() {
        long disabled = createProbeUser("ou.dis", DEPT_A, "DISABLED");
        insertTraceToday("g8a", userId, DEPT_A, 0.90, 0.90, true, "1", 1, false, 3, 10);
        insertTraceToday("g8b", userId, DEPT_A, 0.80, 0.80, true, "1", 1, false, 3, 8);
        insertTraceToday("g8c", disabled, DEPT_A, 0.70, 0.70, true, "1", 1, false, 3, 6);

        Map<String, Object> res = overview("7d");
        assertThat(num(res, "totalQuestions"))
                .as("三行都在 —— 停用不影响**提问数**（那件事发生过）")
                .isEqualTo(3);
        assertThat(num(res, "activeUsers"))
                .as("🔴 只有探针那一个活跃；停用的那个提问了也不算（G8-8 逐字要求）")
                .isEqualTo(1);
        assertThat(num(res, "totalUsers"))
                .as("分母是**全部非软删账号**（含停用/待激活）—— 与需求 §3.1 的 73/128 同一个分母口径")
                .isEqualTo(jdbc.queryForObject(
                        "select count(*) from sys_user where deleted_at is null", Long.class));
    }

    @Test
    @DisplayName("🔴 无归属提问（user_id 为 NULL）计入提问总数，但**不算一个人**")
    void ownerlessQuestionCountsButIsNotAUser() {
        insertTraceToday("nl1", 0L, 0L, 0.90, 0.90, true, "1", 1, false, 3, 5);

        Map<String, Object> res = overview("7d");
        assertThat(num(res, "totalQuestions")).as("提问数是事实").isEqualTo(1);
        assertThat(num(res, "activeUsers")).as("哨兵 0 不是一个人").isZero();
        assertThat(res.get("avgPerUser")).as("没有活跃用户 ⇒ 人均**算不出来**（不是 0）").isNull();
        assertThat(listOf(topUsers("7d"))).as("榜单里也不该出现「已删除用户#0」").isEmpty();
    }

    // ==================== 需求 §3.8 的三条「合计 = 总数」 ====================

    @Test
    @DisplayName("需求 §3.8：部门人数/提问数/活跃用户三个合计 **各等于** overview 的对应项")
    void deptTotalsMatchOverview() {
        long other = createProbeUser("ou.b", DEPT_B, "ACTIVE");
        insertTraceToday("d1", userId, DEPT_A, 0.90, 0.90, true, "1", 1, false, 3, 9);
        insertTraceToday("d2", userId, DEPT_A, 0.85, 0.85, true, "1", 1, false, 3, 7);
        insertTraceToday("d3", other, DEPT_B, 0.80, 0.80, true, "1", 1, false, 3, 5);

        Map<String, Object> ov = overview("7d");
        Map<String, Object> dept = byDept("7d");
        assertThat(num(dept, "questions")).as("部门提问数合计 == 提问总数").isEqualTo(num(ov, "totalQuestions"));
        assertThat(num(dept, "active")).as("部门活跃合计 == 活跃用户总数").isEqualTo(num(ov, "activeUsers"));
        assertThat(num(dept, "users")).as("部门人数合计 == 用户总数").isEqualTo(num(ov, "totalUsers"));

        long rowActive = listOf(dept).stream()
                .mapToLong(r -> ((Number) r.get("activeUsers")).longValue()).sum();
        assertThat(rowActive).as("逐行相加也要等于那个合计（合计不是另算的一个数）")
                .isEqualTo(num(dept, "active"));
    }

    @Test
    @DisplayName("🔴 **没有提问的部门也要出行** —— 否则「部门人数合计 = 用户总数」必然不成立")
    void departmentsWithoutQuestionsStillAppear() {
        insertTraceToday("z1", userId, DEPT_A, 0.90, 0.90, true, "1", 1, false, 3, 4);

        Map<String, Object> dept = byDept("7d");
        assertThat(num(dept, "users"))
                .as("128 个用户分布在 9 个部门里，而只有 1 个部门有人提问 —— "
                        + "只回「有提问的部门」会让这一列少掉绝大部分人，而页面上看不出来")
                .isEqualTo(jdbc.queryForObject(
                        "select count(*) from sys_user where deleted_at is null", Long.class));
        assertThat(listOf(dept).stream().anyMatch(r -> ((Number) r.get("questions")).longValue() == 0))
                .as("确实有提问数为 0 的部门行在（否则上一条断言只是碰巧成立）")
                .isTrue();
    }

    // ==================== 部门快照（需求 §3.7）====================

    @Test
    @DisplayName("§3.7：部门取**当时的快照** —— 事后调岗不改历史统计")
    void deptIsSnapshotNotLiveJoin() {
        insertTraceToday("s1", userId, DEPT_A, 0.90, 0.90, true, "1", 1, false, 3, 5);
        assertThat(rowQuestions(byDept("7d"), DEPT_A)).as("先落在研发中心").isEqualTo(1);

        // 调岗：**只改 sys_user**，一行打点都不动
        setDeptAndStatus(userId, DEPT_B, "ACTIVE");
        clearDailyOnly();       // 逼读路径重算（否则读的是上一次的缓存）

        Map<String, Object> dept = byDept("7d");
        assertThat(rowQuestions(dept, DEPT_A))
                .as("🔴 历史提问**仍在研发中心** —— 这正是 §3.7 要防的那件事"
                        + "（实时 join sys_user 的话，这一格会凭空变成 0，而页面上没有任何提示）")
                .isEqualTo(1);
        assertThat(rowQuestions(dept, DEPT_B)).as("销售中心不该凭空多出来").isZero();
        assertThat(jdbc.queryForObject(
                "select dept_id from sys_retrieval_trace where trace_id = 's1'", Long.class))
                .as("打点行上的快照列**没有被任何东西改过**").isEqualTo(DEPT_A);
    }

    // ==================== 趋势（需求 §3.2）====================

    @Test
    @DisplayName("🔴 趋势恰好 **7 个点**且合计 == 提问总数（日历天口径才成立）")
    void trendHasExactlySevenPointsAndSumsToTotal() {
        insertTraceToday("t1", userId, DEPT_A, 0.90, 0.90, true, "1", 1, false, 3, 5);
        insertTraceDays("t2", userId, DEPT_A, 0.80, 0.80, true, "1", 1, false, 3, 3);

        Map<String, Object> trend = trend("7d");
        assertThat(listOf(trend)).as("近 7 天 = **恰好 7 个点**（滚动窗口会给 8 个）").hasSize(7);
        long sum = listOf(trend).stream().mapToLong(r -> ((Number) r.get("value")).longValue()).sum();
        assertThat(sum).as("各点合计 == 提问总数（同一窗口、同一个起点）")
                .isEqualTo(num(overview("7d"), "totalQuestions"))
                .isEqualTo(num(trend, "total"));
        assertThat(listOf(trend).getLast().get("today")).as("最后一个点是今天").isEqualTo(true);
        assertThat(listOf(trend).stream().filter(r -> Boolean.TRUE.equals(r.get("weekend"))).count())
                .as("周末标记是服务端现算的，7 天里必然有 1~2 个")
                .isBetween(1L, 2L);
    }

    // ==================== 库热度（需求 §3.4）====================

    @Test
    @DisplayName("🔴 库热度与页 20 `kb-quality` **同一个数**，且合计 = 提问总数")
    void kbHeatMatchesPage20AndSumsToTotal() {
        insertTraceToday("k1", userId, DEPT_A, 0.90, 0.90, true, "1", 1, false, 3, 7);
        insertTraceToday("k2", userId, DEPT_A, 0.20, 0.20, true, "1", 1, true, 3, 6);
        // 跨库提问：当前链路上**每次提问都是这样**（ChatRequest 没有 kbIds）
        insertTraceToday("k3", userId, DEPT_A, 0.50, 0.50, true, "1,2", 2, false, 3, 5);

        Map<String, Object> usage = byKb("7d");
        Map<String, Object> page20 = get("/api/ops/retrieval/kb-quality?range=7d", token)
                .assertOk("页 20 各库排行").data("$");

        assertThat(num(usage, "questions"))
                .as("需求 §3.4：「与 20 页的知识库提问数必须一致」—— 同一个数不在两个页面各写一遍")
                .isEqualTo(num(page20, "questions"));
        assertThat(num(usage, "othersQuestions"))
                .as("「其他」桶也要一致").isEqualTo(num(page20, "othersQuestions"));
        assertThat(num(usage, "questions") + num(usage, "othersQuestions"))
                .as("🔴 单库 + 其他 == 提问总数（需求 §3.4 那条「合计 = 提问总数」的**订正版**："
                        + "原文按字面要求「每库一行」，而跨库提问装不进任何一个库）")
                .isEqualTo(num(overview("7d"), "totalQuestions"));

        long sum = listOf(usage).stream()
                .mapToLong(r -> ((Number) r.get("questions")).longValue()).sum();
        assertThat(sum).as("list 里的行**不含**「其他」（它由 othersQuestions 单独回）")
                .isEqualTo(num(usage, "questions"));
    }

    @Test
    @DisplayName("🔴 单库提问恒为 0 时，库热度**只剩「其他」** —— 页面必须如实显示这个空态")
    void kbHeatHasOnlyTheOthersBucketOnTheCurrentChain() {
        // 真实链路就是这个形状：提问覆盖全部可见库 ⇒ 一条单库行都没有
        insertTraceToday("m1", userId, DEPT_A, 0.90, 0.90, true, "1,2,3", 3, false, 3, 5);
        insertTraceToday("m2", userId, DEPT_A, 0.80, 0.80, true, "1,2,3", 3, false, 3, 4);

        Map<String, Object> usage = byKb("7d");
        assertThat(listOf(usage)).as("没有单库行 ⇒ list 为空（不是「随便填几行」）").isEmpty();
        assertThat(num(usage, "othersQuestions")).as("全部落进「其他」").isEqualTo(2);
    }

    // ==================== 占比（需求 §3.8 最后一条）====================

    @Test
    @DisplayName("需求 §3.8：**占比列合计 = 100%** —— 靠最大余数法，不是各行独立四舍五入")
    void percentagesSumToExactlyOneHundred() {
        long other = createProbeUser("ou.c", DEPT_B, "ACTIVE");
        // 三个部门各 1 行 ⇒ 各占 33.333…%：独立四舍五入会得到 99.9，那正是这条要挡的
        insertTraceToday("p1", userId, DEPT_A, 0.90, 0.90, true, "1", 1, false, 3, 9);
        insertTraceToday("p2", other, DEPT_B, 0.90, 0.90, true, "7", 1, false, 3, 7);
        insertTraceToday("p3", 0L, 0L, 0.90, 0.90, true, "1,2", 2, false, 3, 5);

        double deptSum = listOf(byDept("7d")).stream()
                .mapToDouble(r -> ((Number) r.get("percent")).doubleValue()).sum();
        assertThat(deptSum).as("部门占比合计").isCloseTo(100.0, within(1e-9));

        Map<String, Object> kb = byKb("7d");
        double kbSum = listOf(kb).stream()
                .mapToDouble(r -> ((Number) r.get("percent")).doubleValue()).sum();
        assertThat(kbSum + ((Number) kb.get("othersPercent")).doubleValue())
                .as("🔴 库占比合计 = 单库各行 + **「其他」那一行**，恰好 100.0"
                        + "（「其他」与单库行**一起**参与最大余数法分配，不是事后补的余数）")
                .isCloseTo(100.0, within(1e-9));
        assertThat(num(kb, "questions")).as("单库行 2 条（p1 / p2）").isEqualTo(2);
        assertThat(num(kb, "othersQuestions")).as("跨库行 1 条（p3）").isEqualTo(1);
    }

    // ==================== 平均 Top1 的分母（需求 §3.5）====================

    @Test
    @DisplayName("🔴 平均 Top1 的分母**只含有分数的行**（空召回不进）；没有样本时回 null")
    void avgTopScoreSkipsEmptyRecall() {
        insertTraceToday("a1", userId, DEPT_A, 0.80, 0.80, true, "1", 1, false, 3, 9);
        // 空召回：重排跑过但**没有 Top1**（NULL，不是 0）
        insertTraceToday("a2", userId, DEPT_A, null, null, true, "1", 1, false, 0, 7);

        Map<String, Object> row = listOf(topUsers("7d")).getFirst();
        assertThat(((Number) row.get("questions")).longValue()).as("两行都算提问").isEqualTo(2);
        assertThat(((Number) row.get("avgTopScore")).doubleValue())
                .as("只有一行有分数 ⇒ 平均就是 0.80。把空召回按 0 计会给 0.40")
                .isCloseTo(0.80, within(1e-9));

        // 只剩空召回 ⇒ 一行样本都没有
        clearAll();
        long u = createProbeUser("ou.n", DEPT_A, "ACTIVE");
        insertTraceToday("a3", u, DEPT_A, null, null, true, "1", 1, false, 0, 5);
        assertThat(listOf(topUsers("7d")).getFirst().get("avgTopScore"))
                .as("没有一行有分数 ⇒ 回 null（页面显示 —），不是 0").isNull();
    }

    // ==================== 页 11 / 20 / 21 三页同源 ====================

    @Test
    @DisplayName("🔴 三页的「提问总数」是**同一个数**（需求 §3.4 那句的字面要求）")
    void questionTotalIsTheSameAcrossThreePages() {
        insertTraceToday("q1", userId, DEPT_A, 0.90, 0.90, true, "1", 1, false, 3, 9);
        insertTraceToday("q2", userId, DEPT_A, 0.80, 0.80, true, "1", 1, false, 3, 7);
        insertTraceToday("q3", userId, DEPT_A, 0.10, 0.10, true, "1", 1, true, 3, 5);

        Map<String, Object> page11 = get("/api/ops/qa/overview?range=7d", token)
                .assertOk("页 11 四指标").data("$");
        Map<String, Object> page20 = get("/api/ops/retrieval/overview?range=7d", token)
                .assertOk("页 20 四指标").data("$");

        assertThat(num(page11, "totalQuestions")).as("页 11").isEqualTo(3);
        assertThat(num(page20, "totalQuestions")).as("页 20").isEqualTo(3);
        assertThat(num(overview("7d"), "totalQuestions")).as("页 21").isEqualTo(3);
    }

    // ==================== 权限（`G8-9` 的可证伪边界）====================

    @Test
    @DisplayName("🔴 `G8-9`：同一个 AUDITOR —— 四个聚合端点 200、`top-users` 403")
    void auditorReadsAggregatesButNotTheUserDimension() {
        TestUser auditor = createUser("ou.aud");
        assignRole(userIdOf(auditor.username()), "AUDITOR");
        String auditorToken = login(auditor.username(), auditor.password()).data("$.accessToken");

        for (String path : List.of("overview", "trend", "by-dept", "by-kb")) {
            assertThat(get("/api/ops/usage/" + path + "?range=7d", auditorToken).code())
                    .as("AUDITOR 有 ops:usage:view，能读聚合：" + path).isZero();
        }
        assertThat(get("/api/ops/usage/top-users?range=7d", auditorToken).code())
                .as("🔴 AUDITOR **读不到个人维度**（ops:usage:users:view 只给超管）"
                        + "—— 需求 §3.7「用户维度统计仅对管理员开放」的落点。"
                        + "这条边界就是「拆两个码」而不是「复用一个码」的全部理由")
                .isNotZero();

        // 普通用户两个码都没有
        TestUser plain = createUser("ou.usr");
        String plainToken = login(plain.username(), plain.password()).data("$.accessToken");
        for (String path : List.of("overview", "trend", "by-dept", "by-kb", "top-users")) {
            assertThat(get("/api/ops/usage/" + path + "?range=7d", plainToken).code())
                    .as("USER 一个码都没有：" + path).isNotZero();
        }
        assertThat(get("/api/ops/usage/overview?range=7d").code()).as("无 token 401").isNotZero();
        assertThat(get("/api/ops/usage/top-users?range=7d", token).code())
                .as("超管靠 `*` 通配，五个端点都通").isZero();
    }

    // ==================== `G8-10` 离线预计算 ====================

    @Test
    @DisplayName("🔴 `G8-10`：读路径**不扫明细** —— 把打点全删掉，历史那几天的数照旧")
    void readPathDoesNotScanTheDetailTable() {
        // 只在**昨天往前 6 天**造行（**不造今天**）：这样窗口 [今天−6, 今天] 里，
        // 有数据的那些天全部落在缓存覆盖区间内，而今天因为"每天必重算"会是 0。
        // ⇒ 把打点全删掉之后，历史那几天的缓存行**仍然在**，读数不变。
        // 🔴 这就是"读路径没有扫明细"的**决定性证据**：真扫明细的话它只会读到 0。
        for (int d = 1; d <= 6; d++) {
            insertTraceDays("pc1-" + d, userId, DEPT_A, 0.90, 0.90, true, "1", 1, false, 3, d);
            insertTraceDays("pc2-" + d, userId, DEPT_A, 0.20, 0.20, true, "1", 1, true, 3, d);
        }
        Map<String, Object> first = overview("7d");
        assertThat(num(first, "totalQuestions")).as("6 天 × 2 行").isEqualTo(12);
        assertThat(jdbc.queryForObject(
                "select count(*) from sys_ops_retrieval_daily", Long.class))
                .as("第一次读就把汇总表填上了（缓存确实生效）").isPositive();
        assertThat(jdbc.queryForObject(
                "select count(*) from sys_ops_usage_daily", Long.class)).isPositive();

        // 把**事实**删掉，只留**缓存**
        jdbc.update("delete from sys_retrieval_trace");
        assertThat(jdbc.queryForObject("select count(*) from sys_retrieval_trace", Long.class))
                .as("此刻明细表是空的 —— 下面那个数只可能来自汇总表").isZero();

        Map<String, Object> second = overview("7d");
        assertThat(num(second, "totalQuestions"))
                .as("🔴 明细表已空而读数不变 ⇒ 读路径读的是汇总表。"
                        + "把读路径改回实时扫明细，这一条立刻转红（会读到 0）")
                .isEqualTo(12);
        assertThat(num(second, "activeUsers")).isEqualTo(num(first, "activeUsers"));
    }

    @Test
    @DisplayName("预计算**幂等**：同一件事做两遍，汇总表逐行不变（打点行也不动）")
    void precomputeIsIdempotent() {
        insertTraceToday("i1", userId, DEPT_A, 0.90, 0.90, true, "1", 1, false, 3, 9);
        insertTraceDays("i2", userId, DEPT_A, 0.40, 0.40, true, "2", 1, false, 3, 2);

        OpsPrecomputeService precompute = precomputeService;
        overview("7d");                                    // 先让读路径填一次
        String before = dumpDaily();
        long traceBefore = jdbc.queryForObject("select count(*) from sys_retrieval_trace", Long.class);

        precompute.rebuildAll();
        precompute.rebuildAll();

        assertThat(dumpDaily())
                .as("🔴 两次重算之后逐行相同 —— 「无累积副作用」而不是「逐位可复现」"
                        + "（`S8-M10-B` 的幂等口径：能累积的东西一个都不动）")
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from sys_retrieval_trace", Long.class))
                .as("重算**只读**打点表，绝不改它").isEqualTo(traceBefore);
    }

    // ==================== range 过滤 ====================

    @Test
    @DisplayName("按 range 过滤：窗口外的行不进任何统计")
    void rangeFiltersTheWindow() {
        insertTraceToday("r1", userId, DEPT_A, 0.90, 0.90, true, "1", 1, false, 3, 5);
        insertTraceDays("r2", userId, DEPT_A, 0.90, 0.90, true, "1", 1, false, 3, 10);

        assertThat(num(overview("7d"), "totalQuestions")).as("7d 只看到今天那行").isEqualTo(1);
        assertThat(num(overview("30d"), "totalQuestions")).as("30d 两行都在").isEqualTo(2);
        assertThat(listOf(trend("30d"))).as("30d 的趋势是 30 个点").hasSize(30);

        // 越界夹取、格式错抛参数错 —— 与 `range` 的既有口径一致
        assertThat(num(overview("0d"), "totalQuestions")).as("夹取到 1 天，不是拒绝").isEqualTo(1);
        assertThat(get("/api/ops/usage/overview?range=abc", token).code())
                .as("格式错才抛（猜一个天数比报错更糟）").isNotZero();
    }

    // ==================== 纯函数（不启 Spring）====================

    @Test
    @DisplayName("最大余数法：合计恒为 100.0，且差额补给余数最大的那几行")
    void largestRemainderAlwaysSumsToOneHundred() {
        // 三个 1 ⇒ 各 33.333…：独立四舍五入得 33.3×3 = 99.9
        List<Double> p = OpsUsageService.largestRemainderPercent(List.of(1L, 1L, 1L), 3);
        assertThat(p.stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(100.0, within(1e-9));
        assertThat(p).containsExactly(33.4, 33.3, 33.3);

        // 7 行 1 份 ⇒ 同样的形状，差额更大
        List<Long> seven = List.of(1L, 1L, 1L, 1L, 1L, 1L, 1L);
        assertThat(OpsUsageService.largestRemainderPercent(seven, 7).stream()
                .mapToDouble(Double::doubleValue).sum()).isCloseTo(100.0, within(1e-9));

        // 分母为 0 ⇒ 全是 null（不是 0%）
        assertThat(OpsUsageService.largestRemainderPercent(List.of(0L, 0L), 0)).containsExactly(null, null);
        assertThat(OpsUsageService.largestRemainderPercent(List.of(), 0)).isEmpty();
    }

    @Test
    @DisplayName("时间窗改成**日历天**：7d = 今天 + 前 6 天（恰好 7 个日历天）")
    void windowIsCalendarDayBased() {
        LocalDate today = LocalDate.now();
        assertThat(com.knowledge.ai.core.ops.TimeWindow.fromDate("7d"))
                .as("起点是**今天 − 6 天**那一天，不是 now − 7×24h（那会跨 8 个日历天）")
                .isEqualTo(today.minusDays(6));
        assertThat(com.knowledge.ai.core.ops.TimeWindow.from("7d"))
                .as("而且落在**天边界**上 —— 读侧 `stat_date >= fromDate()` 与实时扫 "
                        + "`created_at >= from()` 只有在这一点上才是同一批行")
                .isEqualTo(today.minusDays(6).atStartOfDay());
        assertThat(com.knowledge.ai.core.ops.TimeWindow.mid("7d"))
                .as("中点也是天边界（7 天窗口 ⇒ 前半 4 天、后半 3 天）")
                .isEqualTo(today.minusDays(2).atStartOfDay());
    }

    // ==================== 内部 ====================

    private Map<String, Object> overview(String range) {
        return get("/api/ops/usage/overview?range=" + range, token)
                .assertOk("使用统计四指标").data("$");
    }

    private Map<String, Object> trend(String range) {
        return get("/api/ops/usage/trend?range=" + range + "&granularity=day", token)
                .assertOk("提问趋势").data("$");
    }

    private Map<String, Object> byDept(String range) {
        return get("/api/ops/usage/by-dept?range=" + range, token)
                .assertOk("部门维度").data("$");
    }

    private Map<String, Object> byKb(String range) {
        return get("/api/ops/usage/by-kb?range=" + range, token)
                .assertOk("知识库热度").data("$");
    }

    private Map<String, Object> topUsers(String range) {
        return get("/api/ops/usage/top-users?range=" + range, token)
                .assertOk("活跃用户排行").data("$");
    }

    /** 某个部门行的提问数（按**部门名**取样 —— 下标是展示顺序，写反了看不出来）。 */
    private static long rowQuestions(Map<String, Object> res, long deptId) {
        String name = deptId == DEPT_A ? "研发中心" : "销售中心";
        return listOf(res).stream().filter(r -> name.equals(r.get("dept")))
                .mapToLong(r -> ((Number) r.get("questions")).longValue())
                .findFirst().orElseThrow(() -> new AssertionError("响应里没有部门 " + name));
    }

    /** 造一个**指定部门 / 状态**的探针账号（返回 id）。 */
    private long createProbeUser(String prefix, long deptId, String status) {
        TestUser u = createUser(prefix);
        long id = userIdOf(u.username());
        setDeptAndStatus(id, deptId, status);
        return id;
    }

    private void setDeptAndStatus(long id, long deptId, String status) {
        jdbc.update("update sys_user set dept_id = ?, status = ? where id = ?", deptId, status, id);
    }

    private void insertTraceToday(String traceId, long owner, long deptId, Double top1, Double before,
                                 boolean applied, String kbIds, int kbCount, boolean miss,
                                 int chunkCount, int minutesAgo) {
        insert(traceId, owner, deptId, top1, before, applied, kbIds, kbCount, miss, chunkCount,
                "now() - interval ? minute", minutesAgo);
    }

    private void insertTraceDays(String traceId, long owner, long deptId, Double top1, Double before,
                                 boolean applied, String kbIds, int kbCount, boolean miss,
                                 int chunkCount, int daysAgo) {
        insert(traceId, owner, deptId, top1, before, applied, kbIds, kbCount, miss, chunkCount,
                "now() - interval ? day", daysAgo);
    }

    /**
     * 🔴 `dept_id` 与 `miss_threshold` 都必须**显式写**：
     * 前者列默认 NULL（会被读侧归到「未分配」），后者默认 0（会让每一行都被算成达标）。
     */
    private void insert(String traceId, long owner, long deptId, Double top1, Double before,
                        boolean applied, String kbIds, int kbCount, boolean miss, int chunkCount,
                        String createdExpr, int ago) {
        jdbc.update("insert into sys_retrieval_trace "
                        + "(trace_id, query_text, user_id, dept_id, rerank_applied, rerank_before_top1, "
                        + " kb_ids, kb_count, miss_threshold, miss_flag, top1_score, chunk_count, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, " + MISS_THRESHOLD + ", ?, ?, ?, "
                        + createdExpr + ")",
                traceId, "使用统计探针", owner == 0L ? null : owner, owner == 0L ? null : deptId,
                applied, before, kbIds, kbCount, miss, top1, chunkCount, ago);
    }

    /** 汇总表的可比较快照（幂等判据用它做"逐行不变"的比较）。 */
    private String dumpDaily() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from sys_ops_retrieval_daily order by stat_date, kb_id");
        List<Map<String, Object>> usage = jdbc.queryForList(
                "select * from sys_ops_usage_daily order by stat_date, dept_id, user_id");
        return rows + "|" + usage;
    }

    /** 只清汇总表（用来逼读路径重算，同时不打扰打点行）。 */
    private void clearDailyOnly() {
        jdbc.update("delete from sys_ops_retrieval_daily");
        jdbc.update("delete from sys_ops_usage_daily");
    }

    /**
     * 清掉探针窗口内的**打点与汇总**。
     *
     * <p>⚠️ 两张都要清：汇总表是缓存，只清打点会让**过去那几天**的缓存行留下来，
     * 下一个用例读到上一个用例的残影（而"今天"因为每天必重算，反倒看不出来）。
     */
    private void clearAll() {
        jdbc.update("delete from sys_retrieval_trace_chunk");
        jdbc.update("delete from sys_retrieval_trace");
        clearDailyOnly();
    }

    private long userIdOf(String name) {
        Long id = jdbc.queryForObject("select id from sys_user where username = ?", Long.class, name);
        assertThat(id).as("账号 %s 应该存在", name).isNotNull();
        return id;
    }

    /** 造号 → 换角色 → **重新登录**（角色在 JWT 里，不重登测的还是旧权限）。 */
    private void assignRole(long uid, String roleCode) {
        jdbc.update("delete from sys_user_role where user_id = ?", uid);
        jdbc.update("insert into sys_user_role (user_id, role_id, created_at) "
                + "select ?, id, now() from sys_role where code = ?", uid, roleCode);
    }

    private static long num(Map<String, Object> map, String key) {
        Object value = map.get(key);
        assertThat(value).as("响应里必须有字段 %s", key).isNotNull();
        return ((Number) value).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> res) {
        Object value = res.get("list");
        assertThat(value).as("响应里必须有数组 list").isInstanceOf(List.class);
        return (List<Map<String, Object>>) value;
    }
}
