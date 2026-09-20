package com.knowledge.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 运营统计的**定时补算**（`T-A04` · `G8-10`）—— 把 {@link OpsPrecomputeService} 挂上时钟。
 *
 * <h3>为什么要有它，既然读侧自己会补算</h3>
 * <p>读侧补算只覆盖**被问到的那段窗口**（近 7/30/90 天）。如果没人打开运营页，
 * 汇总表就一直是空的 ⇒ 某天有人来看"近 90 天"时**一次性算 90 天**（在一个 HTTP 请求里）。
 * 定时任务把这件事挪到凌晨，读路径就只剩"当天那一份"。
 *
 * <h3>它是**幂等**的，所以"跑重了"不要紧</h3>
 * <p>本仓单实例部署（见 `plan/2026-09-20-S8-M10-E.md` §5），不需要分布式锁；
 * 而即便将来多实例同时触发，{@code delete + insert}` 的按天重算也不会留下两行
 * —— 最坏是同一件事做两遍。⚠️ 但**不要**据此认为可以随便并发：
 * `delete` 与 `insert` 之间存在极短的空窗，恰好在那一刻读页面会看到那一天的数为 0。
 * 这属于"已知的、可自愈的"窗口（下一次读路径就会补回来），登记在 §9。
 *
 * <h3>⚠️ 开关默认是 <b>false</b> 吗？不，是 true</h3>
 * <p>与 `app.parse.worker.enabled`（默认 false，测试期靠手动 `drainOnce()` 驱动）**不同**：
 * 那个后台 worker 会**抢走测试的作业**（`#151` 踩过），而这个任务只是把同一份数据
 * 重算一遍 —— 它**不消费任何队列、不改任何业务行**，测试期跑与不跑结果一样（幂等）。
 * ⇒ 关掉它只影响"谁来算"，不影响"算出来是什么"，所以默认开着是安全的。
 *
 * <p>⚠️ 但**测试期它不该跑**这一点仍然成立（白花时间），cron 落在 04:20，
 * 与 `mvn test` 的时段天然不重叠；真要显式关掉用
 * {@code app.ops.precompute-cron-enabled=false}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.ops.precompute-cron-enabled", havingValue = "true", matchIfMissing = true)
public class OpsPrecomputeJob {

    private final OpsPrecomputeService precomputeService;

    /**
     * 每天 04:20 全量补算一次（从打点表最早那天算到今天）。
     *
     * <p>⚠️ 避开整点：本仓另两个任务在 03:30（审计留存清理）等位置，
     * 与本任务错开是为了让跑批日志互不淹没（单实例、同一台机器）。
     */
    @Scheduled(cron = "${app.ops.precompute-cron:0 20 4 * * *}")
    public void run() {
        try {
            int days = precomputeService.rebuildAll();
            log.info("[预计算] 定时全量补算完成：{} 天", days);
        } catch (RuntimeException ex) {
            // 定时任务抛出去的异常只进日志线程的 uncaught handler，看不出是谁——
            // 这里自己接住并点名
            log.warn("[预计算] 定时全量补算失败（读路径仍会自行补算，页面不受影响）：err={}",
                    ex.getMessage());
        }
    }
}
