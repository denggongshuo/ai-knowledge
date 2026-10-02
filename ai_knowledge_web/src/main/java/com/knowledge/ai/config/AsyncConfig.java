package com.knowledge.ai.config;

import lombok.extern.slf4j.Slf4j;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.core.trace.TraceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    /**
     * 问答 SSE 执行器（`S6-M05-C` 的 P0 修复）。
     * **与上面两个池子都分开**，理由见 {@link #chatExecutor()}。
     */
    public static final String CHAT_EXECUTOR = "chatExecutor";

    /**
     * 问答 SSE 的**虚拟线程**执行器（`T-D07`）。
     *
     * <p>与 {@link #CHAT_EXECUTOR} 的区别：那个是有界池（core=4/max=8/queue=16），
     * 排队时长任务会占死固定线程；虚拟线程按"每任务一条"给，生成再久也不占平台线程。
     * ⚠️ **无界**：没有队列上限 ⇒ 过载时不再"当场拒绝"，而是并发上涨 ——
     * 本批按用户指定技术方案落地，过载保护沿用上游模型侧的超时/限流。
     */
    public static final String CHAT_VIRTUAL_EXECUTOR = "chatVirtualExecutor";

    @Value("${app.async.core-pool-size:4}")
    private int corePoolSize;

    @Value("${app.async.max-pool-size:8}")
    private int maxPoolSize;

    @Value("${app.async.queue-capacity:200}")
    private int queueCapacity;

    @Value("${app.async.keep-alive-seconds:120}")
    private int keepAliveSeconds;

    /** 同时在线的提问数。4 是"两三个用户各问一轮 + 一轮重试"的折中。 */
    @Value("${app.chat.core-pool-size:4}")
    private int chatCorePoolSize;

    @Value("${app.chat.max-pool-size:8}")
    private int chatMaxPoolSize;

    /** 队列**刻意小**（16）：问答是长任务，排长队不如早点拒绝并如实告诉用户。 */
    @Value("${app.chat.queue-capacity:16}")
    private int chatQueueCapacity;

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
     * 问答 SSE 执行器（`S6-M05-C` 的 P0 修复）—— 让 `SseEmitter` 能真的流式往外发。
     *
     * <h3>为什么必须新开一个池，而不是复用上面两个</h3>
     * <p>这是本批最核心的一处取舍，**不是"顺手隔离一下"**：
     *
     * <ul>
     *   <li><b>不能复用 {@link #TASK_EXECUTOR}</b>：它的拒绝策略是 {@code CallerRunsPolicy}。
     *       队列一满，"让提交者自己跑"在这里等于**把整轮问答搬回请求线程同步跑完** ——
     *       也就是把 {@code #174}（整条 SSE 攒完一次性发）**原地还原**，
     *       而且只在有负载时才复现：本地跑一百次都是好的。这类"负载一变才集中爆"的
     *       缺陷本仓登记过不止一次（见 `汇总.md` §5 那两条）。</li>
     *   <li><b>不能复用 {@link #RETRIEVAL_EXECUTOR}</b>：它只有 4 个线程、且队列为 0。
     *       问答任务会**长时间占着线程**（一次生成几分钟），而检索只是它内部的一小跳
     *       （检索自己会往这个池里提交两路召回）—— 两者同一个池就是**自己等自己**：
     *       8 个并发提问把线程占满后，每一次检索的召回都拿不到线程，
     *       表现是"问答全部卡死"，而根因在池子大小上。</li>
     * </ul>
     *
     * <p>所以：**线程数按"同时在线的提问数"给，拒绝策略用 {@code AbortPolicy}**
     * —— 队列满时正确的反应是"尽早告诉用户"，而不是把压力偷偷转回请求线程。
     * ⚠️ `T-D07` 起问答**默认改走** {@link #CHAT_VIRTUAL_EXECUTOR}（虚拟线程 + Flux），
     * 本池**保留作回退**（出事了把 {@code ChatController} 的 {@code @Qualifier} 改回本 bean 即可）。
     * 它那套 {@code AbortPolicy} + 当场发 {@code error} 事件的行为，是 `S6-M05-C` 时代的设计，
     * 在虚拟线程下**不适用**（虚拟线程无队列上限，不存在"拒绝"这条路径）。

     *
     * <p>⚠️ 与另两个池同一条纪律：**必须**带上身份传播的装饰器 —— 但这里要传的是
     * **两样**（traceId **和** {@code LoginUser}），所以不能用
     * {@link #traceIdPropagating}，见 {@link #chatContextPropagating}。
     */
    @Bean(name = CHAT_EXECUTOR)
    public Executor chatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(chatCorePoolSize);
        executor.setMaxPoolSize(chatMaxPoolSize);
        executor.setQueueCapacity(chatQueueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix("ak-chat-");
        // 正在生成的回答不该因为一次优雅停机被拦腰砍断；给足排空时间
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(AsyncConfig::chatContextPropagating);
        executor.initialize();
        log.info("问答线程池已就绪 core={} max={} queue={}", chatCorePoolSize, chatMaxPoolSize, chatQueueCapacity);
        return executor;
    }

    /**
     * 问答 SSE 的**虚拟线程**执行器（`T-D07`）。
     *
     * <h3>为什么另起一个，而不是把 {@link #chatExecutor()} 换掉</h3>
     * <p>换掉会让"队列满 ⇒ 当场拒绝"那条既有行为消失（见 {@link #chatExecutor()} 的注释）。
     * 虚拟线程执行器**没有队列上限**，不存在"拒绝"这条路径 —— 但它的代价是过载不再被挡在入口。
     * 本批按用户指定的技术方案（`SseEmitter` → `Flux` + 虚拟线程）落地，
     * 保留旧 bean 让"出事了退回旧执行器"是一次改名的距离。
     *
     * <h3>🔴 上下文必须手动搬运 —— 虚拟线程执行器没有 TaskDecorator</h3>
     * <p>{@link ThreadPoolTaskExecutor#setTaskDecorator} 是那个类的 API；
     * `Executors.newVirtualThreadPerTaskExecutor()` 返回的是 JDK 原生 {@link ExecutorService}，
     * **没有装饰器可挂**。于是 LoginUser 与 traceId 的传播要**自己包一层**
     * （{@link #chatContextPropagating}），否则身份在提交那一刻就丢了 ——
     * 而丢法很安静：`require()` 抛 401、`userIdOrNull()` 返回 null 让数据范围悄悄放宽。
     *
     * <p>⚠️ 这里**不是**"提交时捕获一次就能省"的优化点：捕获必须在提交线程做，
     * 恢复必须在工作线程做，两者是同一个包装函数的头尾。
     */
    @Bean(name = CHAT_VIRTUAL_EXECUTOR)
    public Executor chatVirtualExecutor() {
        // setName 让 jcmd / 线程转储里认得出：问答的虚拟线程一律叫 ak-chat-vt-*
        ExecutorService delegate = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("ak-chat-vt-", 0).factory());
        log.info("问答虚拟线程执行器已就绪（无队列上限）");
        // 包一层：每条任务在提交线程捕获 LoginUser/traceId，在虚拟线程恢复并 finally 清理
        // ⚠️ 这个 delegate **绝不能放进 try-with-resources** —— 那会在方法返回时立刻关掉它，
        //    而返回出去的 lambda 还握着它，表现是每一次提问都 RejectedExecutionException。
        return task -> delegate.execute(chatContextPropagating(task));

    }


    /**
     * 把提交线程的 {@code traceId} **与登录身份**传给问答工作线程。
     *
     * <h3>为什么不能复用 {@link #traceIdPropagating}</h3>
     * <p>那个只传 traceId。问答编排比解析/索引多要一样东西：**`LoginUser`**。
     * 它读 `LoginUserContext`（ThreadLocal）取用户 id、数据范围、密级上限与权限 ——
     * 搬出请求线程后**一个都拿不到**，而且失效的样子是**安静**的：
     * `LoginUserContext.require()` 抛的是 401（看着像"没登录"），
     * `userIdOrNull()` 只是返回 null（数据范围静默退化）。
     *
     * <p>这与 `S4-E2` 补 {@code traceIdPropagating} 时踩的是同一个坑，
     * 只是那次坑在 traceId 上、这次坑在身份上 —— `LoginUserContext` 的类注释
     * 一直写着"**不跨线程**……异步任务必须自己把需要的字段在提交时取出来带过去"，
     * 而在此之前**没有任何地方真的这么做**（原实现干脆同步跑在请求线程上，
     * 所以那个坑一直被"同步"这件事掩盖着）。
     *
     * <p>⚠️ {@code finally} 里两样都要清：池化线程不清，下一条不相关的问答会
     * **继承**上一个用户的身份 —— 那不只是日志串味，是**越权**。
     */
    private static Runnable chatContextPropagating(Runnable task) {
        LoginUser user = LoginUserContext.get();
        String traceId = TraceContext.get();
        return () -> {
            if (user != null) {
                LoginUserContext.set(user);
            }
            if (traceId != null) {
                // 与 traceIdPropagating 同一取舍：提交线程没有就**不造**一个再传
                //（凭空生成的 id 与任何请求都对不上，会让"这次是谁触发的"永远查不出来）
                TraceContext.set(traceId);
            }
            try {
                task.run();
            } finally {
                LoginUserContext.clear();
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
