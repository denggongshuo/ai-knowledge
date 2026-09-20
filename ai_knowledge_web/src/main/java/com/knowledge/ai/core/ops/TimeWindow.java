package com.knowledge.ai.core.ops;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 运营页的时间窗参数（`7d` / `30d` / `90d`）→ 窗口起点。
 *
 * <h3>为什么把它从 `QaMissService` 里提出来</h3>
 * <p>`T-A02` 之后，11 页有**四个**入口吃这个参数（`overview` · `top-questions` ·
 * `feedback-stats` · `export`），全部来自页面右上角**同一个**下拉框。
 * 各写一份的后果是"统计卡的 7 天与榜单的 7 天不是同一个 7 天"，而两者的数字**各自都对** ——
 * 这种漂移没有任何现象，只能靠"只写一处"防（与 `QaMissRepository#filter` 同一条纪律）。
 *
 * <h3>🔴 口径是「**日历天**」，不是「N × 24 小时」—— 2026-09-20（`S8-M10-E`）改的</h3>
 * <p>改之前是 {@code now.minusDays(N)}，那给出的是一个**滚动窗口**：`7d` 跨
 * **8 个日历天**（今天 16:48 往前推 7×24h，落在 7 天前的 16:48）⇒ 按日历天分组会得到
 * **8 个桶**，而需求 `08` §3.2 要的是「近 7 天柱状图（**7 个点**）」。
 * 两者**不可能同时成立**，除非窗口本身就是日历天：
 * <pre>
 *   from = 今天 00:00 − (N−1) 天   ⇒ `7d` = 今天 + 前 6 天 = **恰好 7 个日历天**
 * </pre>
 * <p>于是：
 * <ol>
 *   <li>**趋势每天一个点、恰好 N 个**，且**各点合计 ≡ 窗口内提问总数**（同一窗口，自洽）；
 *   <li>页 11 / 页 20 / 页 21 的「提问总数」**是同一个数**（需求 §3.4 点名要的那条）；
 *   <li>`T-A04` 的按天汇总表可以**直接按 `stat_date >= fromDate()` 求和**对齐窗口
 *       （滚动窗口做不到 —— 第一天是残缺的，按天求和会比实时扫多算几个小时）。
 * </ol>
 * <p>⚠️ **这是一次语义变更，爆炸半径是三个运营页的既有数字**（窗口略微收窄：
 * 7d 从 `(T−7)16:48` 变成 `(T−6)00:00`）。三条实测过的边界：
 * 页 11/20 的既有判据都是**自造探针 + 窗口内相对计数**，不吃绝对边界（见
 * `plan/2026-09-20-S8-M10-E.md` §3.4 假设 1）；而 `done` 里那些**绝对时间戳**不受影响。
 *
 * <h3>⚠️ 越界是**夹取**，不是拒绝</h3>
 * <p>与 `rerankTopK` 越界的处置一致（见 `汇总.md` §5：口径写死，不制造新错误码）。
 * 格式错（不是 `Nd`）才抛 —— 那种情况下"猜一个天数"比报错更糟。
 */
public final class TimeWindow {

    /** 缺省窗口天数（需求 §1.7：「时间范围 | 默认近 7 天」）。 */
    public static final int DEFAULT_DAYS = 7;

    /** 夹取上界 —— 与 `sys_retrieval_trace` 的留存口径保持一个数量级内的克制。 */
    public static final int MAX_DAYS = 365;

    private TimeWindow() {
    }

    /** `range` → 窗口起点（**日历天**语义：今天 00:00 − (N−1) 天，见类注释）。空 / `null` 按默认 7 天。 */
    public static LocalDateTime from(String range) {
        return fromDate(range).atStartOfDay();
    }

    /**
     * 窗口起点那一天 —— `T-A04` 的按天汇总表用它对齐（汇总表的粒度是 `DATE`）。
     *
     * <p>⚠️ **必须与 {@link #from} 是同一天**：读侧 `stat_date >= fromDate()` 与
     * 实时扫 `created_at >= from()` 只有在"起点恰好是某天 00:00"时才给出**同一批行**。
     * 这也是本类改成日历天口径之后才成立的性质。
     */
    public static LocalDate fromDate(String range) {
        return LocalDate.now().minusDays(daysOf(range) - 1L);
    }

    /**
     * 窗口的**中点** —— 页 20 的「质量走势」列拿它把窗口切成前半 / 后半
     * （`T-A03`）：「稳定偏低」与「持续下降」的区别就在这两半的差上，
     * 没有它就只能看一个总均值，而"一直很差"和"最近刚变差"在那个数上**长得一样**。
     *
     * <p>⚠️ 同样取**天边界**（日历天口径改动的直接后果）：于是"前半 / 后半"= 按 `stat_date`
     * 把按天汇总表切成两段求和，`T-A04` 因此**不需要**为每个 `range` 各存一套两半。
     * 以 `7d`（7 天窗口）为例：`mid` = 今天−2 的 00:00 ⇒ 前半 4 天、后半 3 天，合计 7 天。
     */
    public static LocalDateTime mid(String range) {
        return LocalDate.now().minusDays(Math.max(1, daysOf(range) / 2) - 1L).atStartOfDay();
    }

    static int daysOf(String range) {
        if (range == null || range.isBlank()) {
            return DEFAULT_DAYS;
        }
        String trimmed = range.trim();
        if (!trimmed.endsWith("d")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "时间范围格式应为 7d / 30d，收到：" + range);
        }
        try {
            int days = Integer.parseInt(trimmed.substring(0, trimmed.length() - 1));
            return Math.max(1, Math.min(days, MAX_DAYS));
        } catch (NumberFormatException ex) {
            throw new BizException(ErrorCode.PARAM_INVALID, "时间范围格式应为 7d / 30d，收到：" + range);
        }
    }
}
