package com.knowledge.ai.service;

import com.knowledge.ai.core.ops.OpsUsageDailyRepository;
import com.knowledge.ai.core.ops.RetrievalOpsRepository;
import com.knowledge.ai.core.ops.TimeWindow;
import com.knowledge.ai.dto.ops.OpsUsageDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 运营分析 —— 使用统计页（需求 `08` §3；`T-A05`）。
 *
 * <p>五个端点：{@code overview} · {@code trend} · {@code by-dept} · {@code by-kb} · {@code top-users}。
 * 全部只读，**一个审计点都不加**（审计冻结清单仍 37）。
 *
 * <h3>🔴 窗口是**日历天**，而且三页共用一个起点</h3>
 * <p>起点一律取 {@link TimeWindow#fromDate}（今天 00:00 −(N−1) 天），终点是**今天**。
 * 于是 `trend` 的 N 个点之和 ≡ `overview.totalQuestions` ≡ 页 11 / 页 20 的同一个数
 * —— 需求 §3.4 那句「同一个数不要在两个页面各写一遍」在这里是**由构造保证**的。
 * ⚠️ 这条性质依赖 `S8-M10-E` 那次口径改动（滚动窗口下第一天是残缺的，按天求和会对不上）。
 *
 * <h3>分母为 0 一律回 {@code null}</h3>
 * <p>见 {@link OpsUsageDtos} 的类注释。页面上那几个数会显示成 `—`，
 * **不是** `0` —— "算不出来"与"结果是 0"是两句相反的话。
 *
 * <h3>三个合计字段是**为了可断言**而存在的</h3>
 * <p>需求 §3.8 的前三条验收要点都是「A 的合计 = B」的形状。
 * `ByDept` 把 `active`/`users`/`questions` 三个合计**直接返回**，
 * 判据就能拿它与 `overview` 逐位比 —— 而不是判据自己把列表加起来
 * （那样测的是判据自己，而且列表被截断时照样绿）。
 *
 * <h3>⚠️ 本页已知的口径边界（都不改实现，但必须知道）</h3>
 * <ul>
 *   <li><b>「库热度」只有「其他」一行</b>：`ChatRequest` 没有 `kbIds`、
 *       提问页没有库选择器 ⇒ 每次提问覆盖全部可见库 ⇒ 单库提问恒为 0。
 *       聚合侧**已经按页 20 的口径做对了**（只归单库行 + 跨库入「其他」），缺的是**源**。
 *       页面必须**如实显示这个空态**，不许拿别的数填满它。</li>
 *   <li><b>「其他 / 未分配」那一行在当前基线上是空的</b>：`sys_user.dept_id` 是
 *       `NOT NULL DEFAULT 0`，而 `sys_department` 里 `id = 0` 就叫「未分配」且**有 6 个用户**
 *       ⇒ 真实的「未分配」是一个**普通部门行**，而不是一个"兜底桶"。
 *       ⚠️ 这与页 20 的「其他」桶**恰好相反**（那边恒为全部）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class OpsUsageService {

    private final OpsUsageDailyRepository repository;

    private final RetrievalOpsRepository retrievalOpsRepository;

    private final OpsPrecomputeService precomputeService;

    /** `top-users` 的默认条数（需求 §3.6 的 `limit=10`）。 */
    public static final int DEFAULT_TOP_USERS = 10;

    /** `top-users` 的条数上界 —— 越界**夹取**，与 `range` 同一条口径。 */
    public static final int MAX_TOP_USERS = 100;

    // ==================== ① overview ====================

    /** `GET /ops/usage/overview?range=7d` —— 四个核心指标（需求 §3.1）。 */
    public OpsUsageDtos.Overview overview(String range) {
        Window w = window(range);

        long totalUsers = w.usersPerDept().values().stream().mapToLong(Long::longValue).sum();
        long activeUsers = w.activeIds().size();
        long questions = w.totalQuestions();

        // 「覆盖部门」的分母**不含**虚拟的「未分配」节点（见 OpsUsageDailyRepository#realDeptCount），
        // 分子也照同一口径排除 dept_id = 0 —— 否则会出现「覆盖 9 / 9」这种
        // 把虚拟节点算进去的假满分
        long covered = w.activeDepts().stream().filter(d -> d != null && d != 0L).count();

        List<Object[]> byDay = w.byDay();
        return new OpsUsageDtos.Overview(
                activeUsers,
                totalUsers,
                rate(activeUsers, totalUsers),
                questions,
                ratio(questions, activeUsers),
                covered,
                repository.realDeptCount(),
                dayAvg(byDay, true),
                dayAvg(byDay, false),
                byDay.isEmpty() ? null : round1((double) questions / byDay.size()));
    }

    // ==================== ② trend ====================

    /**
     * `GET /ops/usage/trend?range=7d&granularity=day` —— 时间序列（需求 §3.2）。
     *
     * <p>⚠️ `granularity` **本批只支持 `day`**：需求 §3.6 的签名里有它，
     * 而 §3.2 的展示要求只有"近 7 天柱状图"一种。传别的值**不报错、按 day 处理**
     * —— 与 `range` 越界夹取同一条口径（口径写死，不制造新错误码）。
     */
    public OpsUsageDtos.Trend trend(String range, String granularity) {
        Window w = window(range);
        Map<LocalDate, Long> byDate = new LinkedHashMap<>();
        for (Object[] row : w.byDay()) {
            byDate.put((LocalDate) row[0], (Long) row[1]);
        }

        LocalDate today = LocalDate.now();
        List<OpsUsageDtos.TrendPoint> list = new ArrayList<>(w.days());
        long total = 0;
        for (LocalDate d = w.from(); !d.isAfter(today); d = d.plusDays(1)) {
            // 🔴 没提问的那天**补 0 也要在**：只回"有数据的那几天"会让折线看着对、
            // 合计差一截，而差的那一截没人发现（需求 §3.2 的验收要点就是"合计 = 提问总数"）
            long value = byDate.getOrDefault(d, 0L);
            total += value;
            list.add(new OpsUsageDtos.TrendPoint(
                    d.toString(), label(d), value, isWeekend(d), d.equals(today)));
        }
        return new OpsUsageDtos.Trend(list, total);
    }

    // ==================== ③ by-dept ====================

    /** `GET /ops/usage/by-dept?range=7d` —— 部门维度（需求 §3.3）。 */
    public OpsUsageDtos.ByDept byDept(String range) {
        Window w = window(range);

        // 每个部门：提问数 + 活跃集合。**活跃按人算一次**（每人的部门取自他窗口内最后一天，
        // 见 OpsUsageDailyRepository 的类注释）⇒ 三条合计等式同时成立
        Map<Long, long[]> agg = new LinkedHashMap<>();   // deptId -> [questions]
        Map<Long, Set<Long>> activeByDept = new LinkedHashMap<>();
        for (OpsUsageDailyRepository.UserAgg u : w.userAggs()) {
            Long dept = deptOf(u);
            long[] cell = agg.computeIfAbsent(dept, k -> new long[1]);
            cell[0] += u.questions();
            if (w.activeIds().contains(u.userId())) {
                activeByDept.computeIfAbsent(dept, k -> new LinkedHashSet<>()).add(u.userId());
            }
        }

        // 🔴 **没有提问的部门也要出行**：需求 §3.8 第一条是
        // 「部门人数列合计 = 用户总数（128）」—— 只回"有提问的部门"会让那条等式
        // 因为"某个部门没人提问"而**必然不成立**，而页面上看不出来（只是合计少了一点）
        Set<Long> allDepts = new LinkedHashSet<>(w.usersPerDept().keySet());
        allDepts.addAll(agg.keySet());

        long activeSum = 0;
        long usersSum = 0;
        long questionsSum = 0;
        for (Long d : agg.keySet()) {
            activeSum += activeByDept.getOrDefault(d, Set.of()).size();
            questionsSum += agg.get(d)[0];
        }
        for (Long n : w.usersPerDept().values()) {
            usersSum += n;
        }

        // 占比走最大余数法 ⇒ 各行之和**恰好** 100.0（需求 §3.8 最后一条验收要点）
        List<Long> values = new ArrayList<>();
        List<Long> order = new ArrayList<>(allDepts);
        for (Long d : order) {
            values.add(agg.containsKey(d) ? agg.get(d)[0] : 0L);
        }
        List<Double> percents = largestRemainderPercent(values, questionsSum);

        Map<Long, String> names = repository.deptNames();
        List<OpsUsageDtos.DeptRow> rows = new ArrayList<>(order.size());
        for (int i = 0; i < order.size(); i++) {
            Long d = order.get(i);
            long questions = values.get(i);
            int active = activeByDept.getOrDefault(d, Set.of()).size();
            long users = w.usersPerDept().getOrDefault(d, 0L);
            rows.add(new OpsUsageDtos.DeptRow(
                    names.getOrDefault(d, "已删除部门#" + d),
                    active, users, questions,
                    ratio(questions, active),
                    percents.get(i)));
        }
        rows.sort(Comparator.comparingLong(OpsUsageDtos.DeptRow::questions).reversed());
        return new OpsUsageDtos.ByDept(rows, activeSum, usersSum, questionsSum);
    }

    // ==================== ④ by-kb ====================

    /**
     * `GET /ops/usage/by-kb?range=7d` —— 知识库热度（需求 §3.4）。
     *
     * <p>🔴 **只归单库提问，跨库入「其他」** —— 与页 20 `kb-quality` 同一段聚合、
     * 同一张汇总表（`RetrievalOpsRepository#dailyKbTotals`）。
     * 需求 §3.4 那句「与 20 页的知识库提问数必须一致」因此由**构造**保证。
     * ⚠️ 当前链路下单库行恒为 0（见类注释）⇒ `list` 会只剩「其他」一行，
     * 那是**真实结果**，页面如实显示。
     */
    public OpsUsageDtos.ByKb byKb(String range) {
        Window w = window(range);
        List<Object[]> totals = retrievalOpsRepository.dailyKbTotals(w.from(), w.to());

        long questions = 0;
        long others = 0;
        List<Long> kbIds = new ArrayList<>();
        List<long[]> single = new ArrayList<>();
        for (Object[] row : totals) {
            long kbId = (Long) row[0];
            long n = (Long) row[1];
            if (kbId == 0L) {
                others += n;      // 哨兵 0 = 跨库 / 归属不确定（`V36` 的口径）
                continue;
            }
            questions += n;
            kbIds.add(kbId);
            single.add(new long[]{kbId, n});
        }

        Map<Long, String> names = kbNames(kbIds);
        // 🔴 「其他」**一起参与**占比的分配（放在列表最后），不是一个事后补出来的余数 ——
        //   否则「各库 + 其他 = 100%」只在四舍五入碰巧对的时候成立，
        //   而需求 §3.8 最后一条要的是**恰好** 100%。
        List<Long> values = new ArrayList<>(single.size() + (others > 0 ? 1 : 0));
        for (long[] s : single) {
            values.add(s[1]);
        }
        if (others > 0) {
            values.add(others);
        }
        List<Double> percents = largestRemainderPercent(values, questions + others);

        List<OpsUsageDtos.KbRow> rows = new ArrayList<>(single.size());
        for (int i = 0; i < single.size(); i++) {
            long kbId = single.get(i)[0];
            rows.add(new OpsUsageDtos.KbRow(
                    names.getOrDefault(kbId, "已删除库#" + kbId), single.get(i)[1], percents.get(i)));
        }
        Double othersPercent = others > 0 ? percents.getLast() : null;
        return new OpsUsageDtos.ByKb(rows, questions, others, othersPercent);
    }

    // ==================== ⑤ top-users ====================

    /**
     * `GET /ops/usage/top-users?range=7d&limit=10` —— 活跃用户排行（需求 §3.5）。
     *
     * <p>🔴 **本页唯一含个人维度的端点** ⇒ 挂 `ops:usage:users:view`（只给超管）。
     * 它只展示**姓名 + 部门 + 次数 + 平均分**，**没有任何提问内容**
     * —— `sys_ops_usage_daily` 这张表里根本没有 `query_text` 这一列，
     * 「不展示具体提问内容」（`G8-9`）在这一层是**结构上**成立的，不是靠代码自觉。
     */
    public OpsUsageDtos.TopUsers topUsers(String range, Integer limit) {
        Window w = window(range);
        int n = limit == null || limit <= 0 ? DEFAULT_TOP_USERS : Math.min(limit, MAX_TOP_USERS);

        List<OpsUsageDailyRepository.UserAgg> users = new ArrayList<>();
        for (OpsUsageDailyRepository.UserAgg u : w.userAggs()) {
            // 哨兵 0（无归属提问）**不进榜单**：榜单是"人"的排行，而那一行不是一个人。
            // ⚠️ 但它仍然计入 overview 的提问总数（见 OpsUsageDailyRepository）
            if (u.userId() > 0) {
                users.add(u);
            }
        }
        users.sort(Comparator.comparingLong(OpsUsageDailyRepository.UserAgg::questions).reversed());

        Map<Long, String> names = repository.userNames(users.stream()
                .map(OpsUsageDailyRepository.UserAgg::userId).toList());
        Map<Long, String> deptNames = repository.deptNames();

        List<OpsUsageDtos.UserRow> rows = new ArrayList<>(n);
        for (int i = 0; i < users.size() && i < n; i++) {
            OpsUsageDailyRepository.UserAgg u = users.get(i);
            rows.add(new OpsUsageDtos.UserRow(
                    names.getOrDefault(u.userId(), "已删除用户#" + u.userId()),
                    deptNames.getOrDefault(deptOf(u), "未分配"),
                    u.questions(),
                    u.avgTopScore()));
        }
        // total = **去重后的活跃用户总数**（不是 list.size()）—— 它是"榜外还有多少人"的分母
        return new OpsUsageDtos.TopUsers(rows, users.size());
    }

    // ==================== 内部 ====================

    /**
     * 一次请求要用的窗口与它派生出来的东西（**只查一遍**）。
     *
     * <p>为什么收成一个对象：五个端点里有四个要同时用到「窗口内每人聚合」与「活跃集合」，
     * 分成两次取会各查一遍数据库、而且**两次之间新落一行打点就会让两组数对不上**
     * （与 `RetrievalOpsRepository#window` 那条"分五条查会对不上"同一条纪律）。
     */
    private Window window(String range) {
        LocalDate from = TimeWindow.fromDate(range);
        LocalDate to = LocalDate.now();
        // 🔴 读侧入口：确保这一段已经被算进汇总表（缺哪补哪，今天必补）
        precomputeService.ensureCovered(from);
        return new Window(from, to,
                repository.userAggregates(from, to),
                repository.activeUserIds(from, to),
                repository.usersPerDept(),
                repository.dailyTotals(from, to),
                repository.totalQuestions(from, to));
    }

    private record Window(LocalDate from,
                          LocalDate to,
                          List<OpsUsageDailyRepository.UserAgg> userAggs,
                          Set<Long> activeIds,
                          Map<Long, Long> usersPerDept,
                          List<Object[]> byDay,
                          long totalQuestions) {

        int days() {
            return (int) (to.toEpochDay() - from.toEpochDay()) + 1;
        }

        /** 有活跃用户的部门集合（去重）。 */
        Set<Long> activeDepts() {
            Set<Long> out = new LinkedHashSet<>();
            for (OpsUsageDailyRepository.UserAgg u : userAggs) {
                if (activeIds.contains(u.userId())) {
                    out.add(u.deptId() == null ? 0L : u.deptId());
                }
            }
            return out;
        }
    }

    private Map<Long, String> kbNames(List<Long> ids) {
        Map<Long, String> map = new LinkedHashMap<>();
        for (Object[] row : retrievalOpsRepository.kbNames(ids)) {
            map.put(((Number) row[0]).longValue(), String.valueOf(row[1]));
        }
        return map;
    }

    /** 用户的部门；快照列理论上非空，兜底归 0（「未分配」）。 */
    private static long deptOf(OpsUsageDailyRepository.UserAgg u) {
        return u.deptId() == null ? 0L : u.deptId();
    }

    /**
     * 工作日 / 周末的**日均**（需求 §3.2）。
     *
     * <p>分母是**窗口内该类型的天数**（需求示例：`(241+236+229+268+213)/5 = 237.4`、
     * `(45+52)/2 = 48.5`、`1284/7 = 183.4` —— 三条都能对上，所以这不是我猜的算法）。
     *
     * <p>⚠️ **窗口的第一天是完整的一天**（日历天口径），所以不存在"残缺天把日均拉低"
     * 那种事 —— 这正是 `S8-M10-E` 改口径换来的性质之一。
     *
     * @return 该类型天数为 0 时回 {@code null}
     */
    private static Double dayAvg(List<Object[]> byDay, boolean workday) {
        long sum = 0;
        long days = 0;
        for (Object[] row : byDay) {
            LocalDate d = (LocalDate) row[0];
            if (isWeekend(d) == workday) {
                continue;
            }
            sum += (Long) row[1];
            days++;
        }
        return days == 0 ? null : round1((double) sum / days);
    }

    private static boolean isWeekend(LocalDate d) {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    /** 展示标签 `MM-dd 周X`（与原型一致）。 */
    private static String label(LocalDate d) {
        String[] cn = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return String.format("%02d-%02d %s", d.getMonthValue(), d.getDayOfMonth(),
                cn[d.getDayOfWeek().getValue() - 1]);
    }

    /** 百分数（一位小数）；分母为 0 ⇒ {@code null}。 */
    private static Double rate(long numerator, long denominator) {
        return denominator == 0 ? null : round1(100d * numerator / denominator);
    }

    /** 比值（一位小数）；分母为 0 ⇒ {@code null}。 */
    private static Double ratio(long numerator, long denominator) {
        return denominator == 0 ? null : round1((double) numerator / denominator);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10d;
    }

    /**
     * 占比：**最大余数法**（把"四舍五入丢掉的那点"补给余数最大的几行）。
     *
     * <p>需求 §3.8 的最后一条验收要点是「**占比列合计 = 100%**」。逐行独立四舍五入
     * 会让合计变成 `99.9` 或 `100.1`（三个部门各 1/3 时必然 99.9），
     * 而那正是"验收要点做不到"里**最没必要**的一种 —— 它纯粹是舍入的锅。
     *
     * <p>做法：先按 0.1% 为单位取整（`floor`），把差额按小数部分从大到小补给各行；
     * 差为负时从最小的小数部分开始扣。合计**恒等** 100.0。
     *
     * @param values 各行的原始值；`total <= 0` 或列表为空时全部回 {@code null}
     */
    static List<Double> largestRemainderPercent(List<Long> values, long total) {
        int n = values.size();
        List<Double> out = new ArrayList<>(n);
        if (n == 0) {
            return out;
        }
        if (total <= 0) {
            for (int i = 0; i < n; i++) {
                out.add(null);
            }
            return out;
        }

        long[] scaled = new long[n];        // 单位 0.1%
        long[] remainder = new long[n];
        long acc = 0;
        for (int i = 0; i < n; i++) {
            long numerator = values.get(i) * 1000L;   // value/total × 100% × 10
            scaled[i] = numerator / total;
            remainder[i] = numerator % total;
            acc += scaled[i];
        }

        long diff = 1000L - acc;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        if (diff > 0) {
            // 余数大的先补
            java.util.Arrays.sort(idx, (a, b) -> Long.compare(remainder[b], remainder[a]));
            for (int k = 0; k < diff; k++) {
                scaled[idx[k % n]]++;
            }
        } else if (diff < 0) {
            // 余数小的先扣（它们本来就被舍得最少）
            java.util.Arrays.sort(idx, Comparator.comparingLong(a -> remainder[a]));
            for (int k = 0; k < -diff; k++) {
                scaled[idx[k % n]]--;
            }
        }
        for (int i = 0; i < n; i++) {
            out.add(scaled[i] / 10d);
        }
        return out;
    }
}
