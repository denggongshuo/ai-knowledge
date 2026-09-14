package com.knowledge.ai.core.audit;

/**
 * 审计「变更前后对照」的单行（`changes_json` 的元素，T-300）。
 *
 * <p>契约与前端逐字对齐（{@code AuditLogView.vue:307-326} 渲染「变更明细」表，
 * 并在 {@code :325} 统计「共 N 项，其中 M 项发生变更」）：
 * <pre>
 *   { label: "文档 · 上传文档", before: "只读", after: "读写" }
 * </pre>
 *
 * <p><b>为什么 before 只能由业务侧提供</b>：切面只能看到「方法被调用了」，
 * 看不到表里的旧值。所以 T-300 的实质不是加字段，而是定义
 * 「业务侧读旧值的时机（必须在写之前）+ 往切面送数据的通道」——
 * 前者由各 Service 自己保证，后者是 {@link AuditContext}。
 */
public record AuditChange(String label, String before, String after) {

    /**
     * 空值占位符：**EM DASH，U+2014**。
     *
     * <p>值不是随手选的：前端 {@code AuditLogView.vue:316/320} 按
     * {@code c.before !== '—'} 分支决定渲染「档位标签」还是「无」。
     * 码点不同（U+2015 HORIZONTAL BAR、U+2500 BOX DRAWINGS LIGHT HORIZONTAL…）
     * 就会走进另一个分支，看着像"档位就叫 —"。
     *
     * <p>2026-09-14 用一次性探针扫全仓 {@code src/**&#47;*.vue|*.ts} 实测：
     * {@code AuditLogView.vue:316}、{@code :320}、{@code OpsRetrievalView.vue:243}
     * 三处消费点**全部是 U+2014**，无混用。见《2026-09-14-S3-M03-A》§3.1 步骤 2。
     */
    public static final String EMPTY = "—";

    public AuditChange {
        label = normalize(label);
        before = normalize(before);
        after = normalize(after);
    }

    /**
     * 该行是否真的发生变化。与前端 {@code before !== after} **同一口径**——
     * 前端算「共 N 项，其中 M 项变更」用的是它，后端若换口径就会两边对不上。
     */
    public boolean changed() {
        return !before.equals(after);
    }

    /**
     * <b>刻意不抛异常</b>：审计登记发生在业务方法体内（见 {@link AuditContext#change}），
     * 抛出去会连带业务失败 —— 与 {@link AuditLogService} 「审计失败绝不影响业务」同一条原则。
     * 所以 null / 空白一律降级成 {@link #EMPTY}，而不是报错。
     *
     * <p>代价是「忘记传 before」这种调用方 bug 会被静默掩盖成「—」。
     * 这个取舍是有意的：审计的可用性不能高于业务的可用性。
     */
    private static String normalize(String raw) {
        return (raw == null || raw.isBlank()) ? EMPTY : raw;
    }
}
