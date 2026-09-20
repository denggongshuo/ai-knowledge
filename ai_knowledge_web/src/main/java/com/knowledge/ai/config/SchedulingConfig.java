package com.knowledge.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关（T-306）。
 *
 * <h3>为什么单独一个类，而不是加在 {@link AsyncConfig} 上</h3>
 * <p>{@code AsyncConfig} 管的是**异步执行器**（{@code @EnableAsync}，任务由请求触发），
 * 本类管的是**定时调度**（{@code @EnableScheduling}，任务由时钟触发）。
 * 两者常被混在一起写，但它们的失效模式不同：
 * <ul>
 *   <li>异步执行器坏了 → 接口返回慢 / 任务不执行，用户看得见；</li>
 *   <li>调度没开 → {@code @Scheduled} 方法**静静地一次都不跑**，没有任何报错。</li>
 * </ul>
 * 分开一个类，是为了让"调度到底开没开"在 {@code config/} 目录下一眼可查
 * （本次就是因为全仓零 {@code @EnableScheduling} 才发现 T-306 需要先补这一步）。
 *
 * <h3>调度器 Bean 是复用既有的</h3>
 * <p>{@code AsyncConfig#taskScheduler}（{@code ThreadPoolTaskScheduler}，池大小 2）
 * 已经存在，Spring 的调度自动配置会按类型找到它 —— 这里**不再声明第二个**。
 * 多一个调度器等于多一份线程池，而两个池之间没有任何隔离保证。
 *
 * <p>⚠️ 池大小是 2 且当前只有一个定时任务，够用。将来若加清理类任务，
 * 先确认最慢的那个不会把另一个饿死（这两个线程是**共享**的）。
 */
@Slf4j
@Configuration
@EnableScheduling
public class SchedulingConfig {

    public SchedulingConfig() {
        log.info("定时调度已启用（@EnableScheduling）——当前任务：审计日志留存清理（T-306）"
                + " / 运营统计预计算（T-A04，可用 app.ops.precompute-cron-enabled=false 关掉）");
    }
}
