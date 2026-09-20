package com.knowledge.ai.core.ops;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;

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

    /** `range` → 窗口起点。空 / `null` 按默认 7 天。 */
    public static LocalDateTime from(String range) {
        return LocalDateTime.now().minusDays(daysOf(range));
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
