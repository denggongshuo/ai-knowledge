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

    /** 检索召回执行器（`T-700` 的两路并行召回）。**与上面的池子分开**，理由见 {@link #retrievalExecutor()}。 */
    public static final String RETRIEVAL_EXECUTOR = "retrievalExecutor";

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
     * 检索召回执行器（`T-700`）—— 向量与 BM25 两路**并行**跑在这里。
     *
     * <h3>为什么不复用 {@link #TASK_EXECUTOR}</h3>
     * <p>两个池的**背压形状完全相反**：
     * <ul>
     *   <li>解析/索引任务：用户主动触发、可等、队列积压是正常的 → {@code CallerRunsPolicy}
     *       把压力反推给提交者；</li>
     *   <li>检索召回：**在线请求的关键路径**，用户正等着答案。此处若排队，
     *       正确的反应是"尽早失败"而不是"让请求线程去跑"—— 后者会把一次慢查询
     *       扩散成整条 HTTP 线程池的阻塞。所以这里用 {@code AbortPolicy}。</li>
     * </ul>
     * <p>共用另一个后果是：一次批量重建索引能把队列占满，于是**问答同时被拖慢** ——
     * 两类负载互相踩踏，且现象是"有时候慢"，极难归因。分开池子是廉价的隔离。
     *
     * <p>线程数 4 是"两路 × 并发几个用户"的折中：每次检索占 2 个线程，
     * 高峰期不让它去挤 CPU。它做的是**阻塞式网络 IO**（Milvus search），不占 CPU。
     *
     * <p>⚠️ 与 {@code taskExecutor} 同一条纪律：**必须**带上
     * {@code traceIdPropagating}。少了它，并行召回那两跳的日志会各自生成一个
     * 新 traceId，于是"检索详情里的耗时分解"永远对不上同一次请求（`G5-12` 的前提）。
     */
    @Bean(name = RETRIEVAL_EXECUTOR)
    public Executor retrievalExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        // 无队列：宁可当场拒绝，也不让请求在队列里排队 —— 见上方 javadoc
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("ak-recall-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(AsyncConfig::traceIdPropagating);
        executor.initialize();
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
