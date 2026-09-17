package com.knowledge.ai.config;

import lombok.extern.slf4j.Slf4j;
import com.knowledge.ai.core.trace.TraceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步执行器配置（T-006）。
 *
 * <p>为什么不用 {@code @Async} + 默认 {@code SimpleAsyncTaskExecutor}：
 * 默认执行器**每次调用新建线程**，不池化、无上限、无队列 —— 上传并发一高就会把机器打爆。
 * 这里显式给出有界池 + 有界队列 + 明确的拒绝策略。
 *
 * <p><b>为什么用 CallerRunsPolicy</b>：文档解析/索引是用户主动触发的长任务，
 * 队列满了的正确反应是"让提交者自己跑"（即反压到请求线程），
 * 而不是静默丢弃任务（AbortPolicy 会让用户看到一个莫名的 500）。
 * 反压会让上传接口变慢，但**任务不会凭空消失**。
 *
 * <p>注意：解析 / 索引 / 打包这类任务会阻塞在 IO 与外部模型调用上，
 * 线程数按"CPU 核数 × 2"给，不做 IO/CPU 混合调优（S0 阶段够用）。
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    /** 业务异步任务执行器（文档解析、索引、打包、导出）。 */
    public static final String TASK_EXECUTOR = "taskExecutor";

    @Value("${app.async.core-pool-size:4}")
    private int corePoolSize;

    @Value("${app.async.max-pool-size:8}")
    private int maxPoolSize;

    @Value("${app.async.queue-capacity:200}")
    private int queueCapacity;

    @Value("${app.async.keep-alive-seconds:120}")
    private int keepAliveSeconds;

    @Bean(name = TASK_EXECUTOR)
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix("ak-task-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // `S4-E2`：把 traceId 带过线程边界（见下方 traceIdPropagating）。
        executor.setTaskDecorator(AsyncConfig::traceIdPropagating);
        executor.initialize();
        log.info("异步任务线程池已就绪 core={} max={} queue={}", corePoolSize, maxPoolSize, queueCapacity);
        return executor;
    }

    /**
     * 把提交线程的 {@code traceId} 传给工作线程（`S4-E2` · `T-O06` 判据 2 的前提）。
     *
     * <h3>为什么现在才补 —— 这是一句"写了但没做"的承诺</h3>
     * <p>{@link com.knowledge.ai.core.trace.TraceContext} 的类注释一直写着：
     * 「异步任务在提交时通过 {@code current()} 取出 traceId，在工作线程里重新 {@code set()}，
     * 并在 finally 里 {@code clear()}」—— **而在此之前没有任何地方实现它**
     * （本类的 {@code taskExecutor} 从建起来就没有 {@code TaskDecorator}）。
     *
     * <p>这个缺口不显眼，因为它的默认行为**看起来是对的**：`TraceContext.current()`
     * 在工作线程里会**现场生成一个**新 traceId（它兜底生成，不返回 null），
     * 于是每条日志都有 traceId、都不为 null —— 只是**与触发它的那次请求不是同一个**。
     * 异步链路的日志因此无法用 traceId 串起来，而这正是 traceId 存在的唯一理由。
     *
     * <p>`O6` 的判据 2（「提交记录与完成记录**同 traceId**」）踩的就是这条 ——
     * 不补它，判据 2 无论怎么写都过不去。
     *
     * <h3>为什么"提交线程没有就跳过 set"</h3>
     * <p>那时工作线程会自己生成一个。**不要**在这里造一个再传 —— 那会让"这条异步任务
     * 是被谁触发的"永远查不出来（一个凭空生成的 id 与任何请求都对不上）。
     */
    private static Runnable traceIdPropagating(Runnable task) {
        String submitted = TraceContext.get();   // 提交线程的值；非请求线程时为 null
        if (submitted == null) {
            return task;
        }
        return () -> {
            TraceContext.set(submitted);
            try {
                task.run();
            } finally {
                // 池化线程必须清 —— 不清的话下一条不相关的任务会**继承**上一个请求的 traceId，
                // 那比没有 traceId 更糟：日志里会出现两个不相干的操作共用一个 id。
                TraceContext.clear();
            }
        };
    }

    /**
     * 定时任务调度器。
     * 当前用途：回收站 30 天到期清理、下载打包产物过期清理、异步任务登记表清理。
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ak-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        return scheduler;
    }
}
