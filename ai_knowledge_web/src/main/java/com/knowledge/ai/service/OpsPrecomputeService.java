package com.knowledge.ai.service;

import com.knowledge.ai.core.ops.OpsPrecomputeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 运营统计的**离线预计算**（`T-A04` · `G8-10`）。
 *
 * <h3>它在数据流里的位置</h3>
 * <pre>
 *   在线：每次提问 → sys_retrieval_trace（打点，事实）
 *   离线：本服务按天把打点**汇总**进 sys_ops_retrieval_daily / sys_ops_usage_daily（缓存）
 *   读侧：三个运营页只读汇总表
 * </pre>
 * <p>🔴 **两者的关系是「事实 vs 缓存」**：汇总表可以整张删掉重建（源就是打点表），
 * 所以它不承载任何独有信息 —— 这正是"预计算"能安全存在的前提。
 * 反过来（打点从汇总表派生）就是 `D-2` 说的「打点后补 = 数据永久缺失」。
 *
 * <h3>幂等：同一件事做两遍结果相同</h3>
 * <p>见 {@link OpsPrecomputeRepository} 的类注释。本类只负责"算哪些天"。
 *
 * <h3>🔴 「今天」必须现场重算 —— 这是本实现唯一一处"读路径会扫明细"的地方</h3>
 * <p>历史天读汇总表（不扫明细），**今天**在每次读请求里重算一次。
 * 理由不是偷懒，是正确性：
 * <ol>
 *   <li>用户问完一句**立刻**打开页 11/20/21，看到的必须是"已经算进去了"——
 *       否则页面上那个数与刚刚发生的事实不符，而**页面上看不出来它是旧的**
 *       （这正是本仓最怕的"报告说成功"那一类）；</li>
 *   <li>页级 E2E 就是这么验的（提问 → 开页面对账），跑不出这个前提；</li>
 *   <li>代价可控：重算的是**一天**的打点，而不是整个窗口（7/30/90 天）。
 *       `G8-10` 要防的「每次实时扫明细」指的是**全窗口**，那才是随窗口线性增长的那一笔。</li>
 * </ol>
 * <p>⚠️ 这条限制**必须写下来**：它意味着"零明细扫描"这句话**不成立**，
 * 成立的是"**历史不扫，当天扫一天**"。若将来要连当天也不扫，
 * 入口是在 `RetrievalTraceWriter` 落点里做**增量 upsert**（本批刻意不做：
 * 那会让聚合逻辑存在两份，而两份迟早漂移 —— 本仓已被"同一件事写两遍"坑过多次）。
 *
 * <h3>补算历史</h3>
 * <p>`rebuild(from, to)` 是通用的那把；`ensureCovered` 是读侧那把。
 * 两者都**不抛**（统计算不出来不该让页面 500）—— 但会**如实向上报**
 * {@code rebuilt} 天数，好让"没算"与"算了但是 0"分得开。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsPrecomputeService {

    /**
     * 补算的正向窗口上限（天）—— 防止"打点表里有一天是 1970 年"把一次补算变成百万次循环。
     * 与 `TimeWindow.MAX_DAYS`（365）同量级：比任何运营页能问的窗口都宽。
     */
    public static final int MAX_BACKFILL_DAYS = 400;

    private final OpsPrecomputeRepository repository;

    /**
     * 重算 `[from, to]` 闭区间内的每一天。
     *
     * @return 实际重算的天数
     */
    public int rebuild(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            return 0;
        }
        // 正向窗口上限（见 MAX_BACKFILL_DAYS）；下界也要有：from 太早时从 to 往前推
        LocalDate end = to;
        LocalDate start = from.isBefore(end.minusDays(MAX_BACKFILL_DAYS))
                ? end.minusDays(MAX_BACKFILL_DAYS)
                : from;

        int days = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            rebuildOneDay(d);
            days++;
        }
        if (days > 0) {
            log.info("[预计算] 已重算 {} 天（{} ~ {}）", days, start, end);
        }
        return days;
    }

    /** 全量补算：从打点表最早的那一天算到今天。打点表为空时**什么都不做**。 */
    public int rebuildAll() {
        LocalDate earliest = repository.earliestTraceDate();
        if (earliest == null) {
            log.info("[预计算] 打点表为空 ⇒ 没有要算的历史");
            return 0;
        }
        return rebuild(earliest, LocalDate.now());
    }

    /**
     * 读侧入口：确保 `[from, 今天]` 这一段已经被算过（缺哪天补哪天）。
     *
     * <p>判定用 `min/max(stat_date)`，**不是**"这一天有没有行" —— 没有提问的那天
     * **本来就不该有行**（汇总行是数据的函数，不是日历的函数）。
     * 于是"有行才算覆盖"会把每个安静的周末都当成缺口，天天重算一遍。
     * ⚠️ 这条判定的已知盲区：**有人手工删掉中间某天的行时看不出来**
     * （min/max 仍然包住它）—— 代价是那一天的数偏小；重新跑一次全量补算即恢复。
     *
     * @return 本次补算的天数（0 = 已经算过，一个明细都没扫）
     */
    public int ensureCovered(LocalDate from) {
        if (from == null) {
            return 0;
        }
        LocalDate today = LocalDate.now();
        if (from.isAfter(today)) {
            return 0;
        }
        List<LocalDate> todo;
        try {
            todo = daysToRebuild(from, today,
                    repository.retrievalCoverage(), repository.usageCoverage());
        } catch (RuntimeException ex) {
            // 覆盖判定失败（表不存在 / 连接问题）时**倾向重算**：宁可多花一次，
            // 不能因为"判不出"就跳过 —— 那会让页面显示旧数而没有任何信号
            log.warn("[预计算] 覆盖判定失败，按全窗口重算：from={} err={}", from, ex.getMessage());
            return rebuild(from, today);
        }
        for (LocalDate d : todo) {
            rebuildOneDay(d);
        }
        if (!todo.isEmpty()) {
            log.info("[预计算] 读路径补算 {} 天（{}）", todo.size(), todo.get(0));
        }
        return todo.size();
    }

    // ==================== 内部 ====================

    /**
     * 需要（重）算的那些天。
     *
     * <p>包级可见**为了可测**：这是本类唯一的判断逻辑，而它有两处容易被写错的边界
     * （"今天永远要重算"、"表空时全都要算"），用一个纯函数把它们钉住比启一整套集成环境便宜。
     */
    static List<LocalDate> daysToRebuild(LocalDate from, LocalDate today,
                                        LocalDate[] retrievalCoverage, LocalDate[] usageCoverage) {
        List<LocalDate> todo = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            if (needs(d, today, retrievalCoverage) || needs(d, today, usageCoverage)) {
                todo.add(d);
            }
            if (todo.size() >= MAX_BACKFILL_DAYS) {
                break;
            }
        }
        return todo;
    }

    /**
     * 某一天要不要重算。
     *
     * <p>三条，缺一不可：
     * <ol>
     *   <li>两张表**任一张**没覆盖它 ⇒ 要（两张表可能不同步：一张算完另一张失败）；</li>
     *   <li>它是**今天** ⇒ **永远**要（今天的数据随时在变，见类注释）；</li>
     *   <li>覆盖区间的 `min` 比它晚 / `max` 比它早 ⇒ 要。</li>
     * </ol>
     * <p>⚠️ **不能**写成 `d < min || d > max` 就把今天排除掉 —— 那正是本类
     * 一开始想省的优化，而它会让"用户刚问完就打开页面"看到旧数。
     */
    private static boolean needs(LocalDate d, LocalDate today, LocalDate[] coverage) {
        if (coverage == null) {
            return true;
        }
        if (d.isBefore(coverage[0]) || d.isAfter(coverage[1])) {
            return true;
        }
        return d.equals(today);
    }

    private void rebuildOneDay(LocalDate d) {
        try {
            repository.deleteRetrievalDaily(d);
            repository.rebuildRetrievalDaily(d);
            repository.deleteUsageDaily(d);
            repository.rebuildUsageDaily(d);
        } catch (RuntimeException ex) {
            // 不抛：统计算不出来不该让页面 500。记 warn 并**带上是哪一天** ——
            // 否则"某天的数偏小"事后查不出是哪次失败留下的
            log.warn("[预计算] 重算失败：day={} err={}", d, ex.getMessage());
        }
    }
}
