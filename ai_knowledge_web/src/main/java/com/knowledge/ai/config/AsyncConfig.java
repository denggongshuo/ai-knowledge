package com.knowledge.ai.config;

import lombok.extern.slf4j.Slf4j;
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
        executor.initialize();
        log.info("异步任务线程池已就绪 core={} max={} queue={}", corePoolSize, maxPoolSize, queueCapacity);
        return executor;
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
