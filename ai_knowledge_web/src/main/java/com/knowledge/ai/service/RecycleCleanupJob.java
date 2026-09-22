package com.knowledge.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 回收站**超期清理**的定时入口（`T-616` · 需求 `06` §7.9 · 门禁 `G7-13`）。
 *
 * <h3>它只做一件事：把清理**提交**出去</h3>
 * <p>真正的清理在 {@link RecycleService#cleanup()}，而这里走的是
 * {@link RecycleService#submitCleanup()}（异步任务）。
 * ⇒ 本类是"什么时候跑"，不是"怎么清" —— 判据一律打在 Service 那一层，
 * 免得判据要在半夜等一个定时器。
 *
 * <h3>照 {@code AuditRetentionJob} 的两条既定做法</h3>
 * <ol>
 *   <li><b>cron 来自配置</b>（{@code app.recycle.cleanup-cron}）：测试 profile 把它设成
 *       {@code -}（Spring 的禁用写法）⇒ 跑批期间不会半夜真删数据。
 *       ⚠️ 这不是洁癖：一个真会删数据的定时任务在测试期跑起来，红的是**别人**的用例，
 *       而现象是"某张表莫名少了几行"。</li>
 *   <li><b>异常吞掉 + 记日志</b>：调度器把未捕获异常记成"任务失败"并继续下次调度，
 *       但那样日志里只会看到一条不知来源的堆栈；显式吞掉能写清"回收站清理提交失败"。
 *       ⚠️ **提交成功之后的失败不在这里管** —— 那由异步任务的终态发**广播告警**
 *       （需求 §7.9），本类看不见。</li>
 * </ol>
 *
 * <p>⚠️ **cron 选取**：定在 03:40，与既有两个任务（审计留存 03:30、运营预计算 04:20）错开
 * —— 三个都是"扫表 + 删数据"，撞在一起会让凌晨那一段的 IO 互相放大。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecycleCleanupJob {

    private final RecycleService recycleService;

    /**
     * 每日清理。cron 来自配置，默认 **03:40**。
     *
     * <p>⚠️ {@code "-"} 是 Spring 的**禁用写法**（{@code Scheduled.CRON_DISABLED}）——
     * 测试 profile 设成 {@code -}，跑批期间不会真删数据。
     */
    @Scheduled(cron = "${app.recycle.cleanup-cron:0 40 3 * * *}")
    public void scheduledCleanup() {
        try {
            String taskId = recycleService.submitCleanup();
            log.info("回收站超期清理已提交：taskId={}（留存 {} 天；失败会经通知中心广播告警）",
                    taskId, RecycleService.KEEP_DAYS);
        } catch (Exception ex) {
            // ⚠️ 这一步失败**不会**产通知（通知挂在异步任务的终态上，而任务还没建起来）
            //    ⇒ 只能靠这条日志。这是"提交都提不出去"（库不可达之类），属基础设施级故障。
            log.error("回收站超期清理**提交**失败（下次调度会重试）", ex);
        }
    }

    /** 供用例断言"配置项确实被读进来了"（`-` 表示已禁用）。 */
    @Value("${app.recycle.cleanup-cron:0 40 3 * * *}")
    private String cron;

    public boolean disabled() {
        return "-".equals(cron);
    }
}
