package com.knowledge.ai.core.ops;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 使用统计的**读侧**（`T-A05`）—— 只读 `sys_ops_usage_daily`（`T-A04` 的汇总表）。
 *
 * <h3>为什么不直接扫 `sys_retrieval_trace`</h3>
 * <p>`G8-10` 要求统计不实时扫明细；而更要紧的是**口径只有一份**：
 * 页面上每个数都能追到 {@link OpsPrecomputeRepository} 里那一条 `insert ... select`，
 * 而不是"读的时候临时算一个"。⚠️ 唯一的例外是**今天**（读路径会先补算当天，
 * 见 {@link com.knowledge.ai.service.OpsPrecomputeService} 的类注释）。
 *
 * <h3>🔴 归属规则：一个人的提问数**全部**归他"窗口内最后一天"所属的部门</h3>
 * <p>`V36` 的汇总表保留的是"每人每天各自的部门"（那才是事实 —— 调岗发生在哪一天是可知的）。
 * 而**分组**规则在这里定：取该用户窗口内**最后一天**的 `dept_id`。
 *
 * <p>为什么不是"按天各归各的部门"（那看着更"精确"）：需求 §3.8 有两条验收要点
 * ——「部门活跃用户合计 = 活跃用户总数」与「部门提问数合计 = 提问总数」。
 * 按天各归各的话，一个在窗口内调过岗的人会**同时出现在两个部门里**，
 * 两条等式**都会破**（人算了两次、提问也算了两次）。
 * 取"最后一天"则每人恰好落一个部门 ⇒ 两条等式**恒成立**，而代价只是
 * "他调岗前那几天算到了新部门去" —— 那是**可以解释**的偏差（页面上写着口径），
 * 而"两边加起来对不上"是解释不了的。
 *
 * <p>⚠️ 这条规则的另一个后果要记住：**如果一个人窗口内一天都没提问，
 * 他既不在任何部门行里，也不在活跃数里** —— 那是对，因为两列数的都是"活跃"口径。
 * 部门**人数**那一列（`totalUsers`）走的是 `sys_user` 的**当前**归属，与窗口无关。
 */
@Component
public class OpsUsageDailyRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 窗口内每个人的聚合（含 `user_id = 0` 那一行"无归属提问"）。
     *
     * @param from 窗口起点（含）
     * @param to   窗口终点（含）—— 就是今天（汇总表只算到今天）
     */
    @SuppressWarnings("unchecked")
    public List<UserAgg> userAggregates(LocalDate from, LocalDate to) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select d.user_id,
                               (select d2.dept_id from sys_ops_usage_daily d2
                                 where d2.user_id = d.user_id
                                   and d2.stat_date >= :from and d2.stat_date <= :to
                                 order by d2.stat_date desc, d2.dept_id desc
                                 limit 1) as dept_id,
                               sum(d.questions),
                               sum(d.top1_sum),
                               sum(d.top1_count)
                        from sys_ops_usage_daily d
                        where d.stat_date >= :from and d.stat_date <= :to
                        group by d.user_id
                        """)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        List<UserAgg> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new UserAgg(
                    num(r[0]),
                    r[1] == null ? null : num(r[1]),
                    num(r[2]),
                    dec(r[3]),
                    num(r[4])));
        }
        return out;
    }

    /**
     * 窗口内**活跃**的用户 id（`user_id > 0` 且账号 `ACTIVE` 且未软删）。
     *
     * <p>⚠️ 三个条件都不是可选的（`G8-8`）：
     * <ul>
     *   <li>`user_id > 0` —— 否则「无归属提问」会被当成**一个活跃用户**；</li>
     *   <li>`status = 'ACTIVE'` —— 需求 `G8-8` 逐字写着「`PENDING`/`DISABLED` **不计入**」；</li>
     *   <li>`deleted_at is null` —— 已删账号不再是"用户"，但**他提问的那些行仍然计入提问总数**
     *       （那件事发生过）。两条口径分开是对：提问数是事实，活跃数是"现在有多少人在用"。</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public Set<Long> activeUserIds(LocalDate from, LocalDate to) {
        List<Object> rows = entityManager.createNativeQuery("""
                        select d.user_id
                        from sys_ops_usage_daily d
                                 join sys_user u on u.id = d.user_id
                        where d.stat_date >= :from and d.stat_date <= :to
                          and d.user_id > 0
                          and u.deleted_at is null
                          and u.status = 'ACTIVE'
                        group by d.user_id
                        """)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        Set<Long> ids = new HashSet<>(rows.size() * 2);
        for (Object o : rows) {
            ids.add(num(o));
        }
        return ids;
    }

    /**
     * 每天一行：`[LocalDate statDate, long questions]`。
     *
     * <p>⚠️ **只回有行的那几天** —— 没提问的那天汇总表里**本来就没有行**
     * （`V36` 的 DDL 注释：汇总行是数据的函数，不是日历的函数）。
     * 补 0 是**调用方**的事（页面的折线要 N 个点），别在这里编日历。
     */
    @SuppressWarnings("unchecked")
    public List<Object[]> dailyTotals(LocalDate from, LocalDate to) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select d.stat_date, sum(d.questions)
                        from sys_ops_usage_daily d
                        where d.stat_date >= :from and d.stat_date <= :to
                        group by d.stat_date
                        order by d.stat_date
                        """)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        List<Object[]> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new Object[]{toLocalDate(r[0]), num(r[1])});
        }
        return out;
    }

    /**
     * 窗口内提问总数（= 汇总表的 `questions` 之和）。
     *
     * <p>它**应当**等于 `RetrievalOpsRepository` 的按天 `total` 之和（同一批打点行）。
     * 两条路各算各的，对不上就是"两张汇总表的行集不同"——
     * 有一条判据专门断言这件事（它抓的是"某张表的哨兵写漏了一类行"这种缺陷）。
     */
    public long totalQuestions(LocalDate from, LocalDate to) {
        Object v = entityManager.createNativeQuery("""
                        select coalesce(sum(d.questions), 0) from sys_ops_usage_daily d
                        where d.stat_date >= :from and d.stat_date <= :to
                        """)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        return num(v);
    }

    /** 全部非软删用户按部门计数（部门**人数**那一列，与窗口无关）。 */
    @SuppressWarnings("unchecked")
    public Map<Long, Long> usersPerDept() {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select u.dept_id, count(*) from sys_user u
                        where u.deleted_at is null
                        group by u.dept_id
                        """)
                .getResultList();
        Map<Long, Long> map = new LinkedHashMap<>(rows.size() * 2);
        for (Object[] r : rows) {
            map.put(num(r[0]), num(r[1]));
        }
        return map;
    }

    /** 部门 id → 名（不含虚拟的「未分配」也无妨：它会作为 `dept_id = 0` 被查到）。 */
    @SuppressWarnings("unchecked")
    public Map<Long, String> deptNames() {
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select id, name from sys_department").getResultList();
        Map<Long, String> map = new HashMap<>(rows.size() * 2);
        for (Object[] r : rows) {
            map.put(num(r[0]), String.valueOf(r[1]));
        }
        return map;
    }

    /**
     * **真实**部门数（不含 `id = 0` 那个虚拟的「未分配」节点）。
     *
     * <p>⚠️ 为什么排除它：需求 §3.1 的「覆盖部门 5 / 5」里，`5` 是**真实部门**的个数
     * —— 需求 §3.3 的表格把「其他 / 未分配」列在 5 个部门**之外**（它占的是"合计"行上面那一行）。
     * `V1` 的 DDL 也明写它是**虚拟节点**（不是真实部门，用于把"没归到任何部门的账号"显形）。
     * 把虚拟节点算进分母会让"覆盖部门"这个指标永远差一个 —— 而它衡量的是**推广广度**。
     */
    public long realDeptCount() {
        Object v = entityManager.createNativeQuery(
                        "select count(*) from sys_department where id <> 0").getSingleResult();
        return num(v);
    }

    /** 姓名（`top-users` 用）。只取要展示的那些 id。 */
    @SuppressWarnings("unchecked")
    public Map<Long, String> userNames(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select id, real_name from sys_user where id in (:ids)")
                .setParameter("ids", ids)
                .getResultList();
        Map<Long, String> map = new HashMap<>(rows.size() * 2);
        for (Object[] r : rows) {
            map.put(num(r[0]), String.valueOf(r[1]));
        }
        return map;
    }

    // ==================== 行类型 ====================

    /**
     * 窗口内一个人的聚合。
     *
     * @param userId    用户 id；**0 = 无归属提问**（打点里 `user_id` 为 NULL 的那些行）
     * @param deptId    该用户窗口内**最后一天**所属的部门（快照口径，见类注释）；可为 null
     * @param questions 提问数
     * @param top1Sum   可比集 `top1_score` 之和
     * @param top1Count 加入 `top1Sum` 的行数（**空召回不进** ⇒ 与 `questions` 不等是正常的）
     */
    public record UserAgg(long userId, Long deptId, long questions, double top1Sum, long top1Count) {

        /** 平均 Top1；没有一行有分数时回 {@code null}（**不是 0**）。 */
        public Double avgTopScore() {
            return top1Count == 0 ? null : Math.round(top1Sum / top1Count * 1000) / 1000d;
        }
    }

    // ==================== 取值 ====================

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static double dec(Object o) {
        if (o == null) {
            return 0d;
        }
        return o instanceof java.math.BigDecimal bd ? bd.doubleValue() : ((Number) o).doubleValue();
    }

    private static LocalDate toLocalDate(Object o) {
        if (o instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (o instanceof LocalDate d) {
            return d;
        }
        return LocalDate.parse(String.valueOf(o));
    }
}
