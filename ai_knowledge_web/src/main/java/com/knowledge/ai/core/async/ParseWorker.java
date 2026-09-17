package com.knowledge.ai.core.async;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.trace.TraceContext;
import com.knowledge.ai.service.DocParseService;
import com.knowledge.ai.service.ParseAuditTrail;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 解析作业的执行者（`T-O01` · `S4-E1`）。
 *
 * <h2>它替掉了什么</h2>
 * `S4-E1` 之前，解析跑在 {@code AsyncConfig#taskExecutor}（`ak-task-*`，core=4/max=8）——
 * 和审计导出、批量打包**共用一个池**，且队列满时 `CallerRunsPolicy` 把活反压给
 * HTTP 请求线程。结果有三件事同时成立：解析占满 API 的池、单个库的批量导入能饿死所有人、
 * 以及**一个畸形 PDF 能靠一把全局锁把整个解析队列卡死**（`PdfDocumentParser` 的那把锁）。
 *
 * <p>本类把解析拆成**独立的池**（线程名 {@code ak-parse-*}，与 {@code ak-task-*} 分得清），
 * 并且作业**从库里领**而不是从内存队列里取 —— 于是它天然满足三条：
 * <ul>
 *   <li><b>重启不丢</b>：作业是库里的行，不是内存里的对象（`O1` 判据 4）；</li>
 *   <li><b>按库配额</b>：领取前先过 {@link #permitOf} 的信号量，一个库的批量导入
 *       占不满别人的位置（`O1` 判据 3）；</li>
 *   <li><b>池满不反压 HTTP</b>：领不到就不领，作业留在库里等下一轮 ——
 *       "排队中"是**如实**的用户可见状态，不转嫁给请求线程。</li>
 * </ul>
 *
 * <h2>⚠️ 本类不提供的能力（用户 2026-09-17 裁定不拆进程的代价）</h2>
 * 解析与 API 仍在**同一个 JVM、同一个堆**里。PDFBox/opendataloader 的 OOM 仍会打死 API 进程。
 * `O1` 痛点 #2 本批**没有解决** —— 这是有意识付的代价，不是遗漏（计划 §3.3 第 1 行）。
 * 同理，卡在**不可中断的本地解析调用**里的线程无法被真正杀死：超时只能把**作业**判失败
 * （用户看到明确原因、后续作业继续），而那个线程要等本地调用自己返回才会释放。
 * 这条限制的根因与 OOM 隔离是同一个，故与它一并归属后续批次（计划 §9）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParseWorker implements SmartLifecycle {

    /** 作业类型（与 `sys_async_task.type` 一致）。 */
    public static final String TASK_TYPE = "doc-parse";

    /** 单轮最多领几条 —— 防止一次领取把库读成大结果集。 */
    private static final int MAX_BATCH = 8;

    private final SysAsyncTaskRepository repository;

    private final AsyncTaskService asyncTaskService;

    private final DocParseService docParseService;

    /**
     * 解析完成/失败两条**系统审计**的落点（`S4-E2` · `O6` 步 3）。
     *
     * <p>它是个 `@AuditLog` 承载点（方法体为空），切面在本线程采集 ——
     * 无请求、无登录态 → 操作人写为 {@code system}/「系统」（`O6` 判据 4）。
     */
    private final ParseAuditTrail parseAuditTrail;

    @Value("${app.parse.worker.enabled:true}")
    private boolean enabled;

    @Value("${app.parse.worker.threads:4}")
    private int threads;

    @Value("${app.parse.worker.poll-interval-ms:1000}")
    private long pollIntervalMs;

    @Value("${app.parse.worker.stale-seconds:900}")
    private long staleSeconds;

    @Value("${app.parse.worker.max-attempts:3}")
    private int maxAttempts;

    @Value("${app.parse.worker.retry-backoff-seconds:30}")
    private long retryBackoffSeconds;

    @Value("${app.parse.kb-concurrency:2}")
    private int kbConcurrency;

    /** 每个库一把信号量 —— 这就是「按 kbId 的并发配额」。 */
    private final Map<Long, Semaphore> kbPermits = new ConcurrentHashMap<>();

    private final AtomicLong executedTotal = new AtomicLong();

    private volatile ThreadPoolExecutor pool;

    private volatile ScheduledExecutorService ticker;

    private volatile boolean running;

    private volatile LocalDateTime lastReclaimAt;

    // ==================== 生命周期 ====================

    @Override
    public void start() {
        if (!enabled) {
            log.warn("解析 worker 未启用（app.parse.worker.enabled=false）—— "
                    + "作业只会入队，不会被消费。测试期走这条路，由用例显式调 drainOnce() 驱动");
            return;
        }
        this.pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(threads), r -> {
            Thread t = new Thread(r, "ak-parse-" + POOL_SEQ.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ak-parse-tick");
            t.setDaemon(true);
            return t;
        });
        this.running = true;
        ticker.scheduleWithFixedDelay(this::tickSafely, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
        log.info("解析 worker 已就绪 threads={} kbConcurrency={} maxAttempts={} pollMs={} —— "
                        + "解析不再占 ak-task-* 线程池",
                threads, kbConcurrency, maxAttempts, pollIntervalMs);
    }

    private static final AtomicLong POOL_SEQ = new AtomicLong();

    @Override
    public void stop() {
        this.running = false;
        if (ticker != null) {
            ticker.shutdownNow();
        }
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(20, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
        log.info("解析 worker 已停止（累计执行 {} 条）", executedTotal.get());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 排在 Web 容器之后启动、之前停止 —— 避免容器还在收请求时作业已被打断。 */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    @PreDestroy
    void onShutdown() {
        if (running) {
            stop();
        }
    }

    // ==================== 入队 ====================

    /**
     * 投一条解析作业（转存完成后调用）。**不执行** —— 执行由领取循环负责。
     *
     * @return taskId（可被 {@code GET /doc/tasks/{taskId}} 轮询）
     */
    public String enqueue(long kbId, long docId, Long ownerId) {
        // 幂等键按**文档**给：同一篇文档同时只应有一个在跑的解析作业。
        // 完成或失败之后该键自动释放（`findActiveByIdempotencyKey` 只认 PENDING/RUNNING），
        // 所以 `T-406` 的重建索引照样能再投一次。
        return asyncTaskService.enqueue(TASK_TYPE,
                AsyncTaskService.TaskSubject.of(kbId, docId, ownerId), "doc-parse:" + docId);
    }

    // ==================== 领取与执行 ====================

    private void tickSafely() {
        try {
            tick();
        } catch (Exception ex) {
            // 轮询线程绝不能因为一次异常退出 —— 它退了就再也没人领作业，
            // 而表现是"上传之后一直停在已上传"，没有任何报错。
            log.error("解析 worker 轮询异常（下一轮继续）", ex);
        }
    }

    private void tick() {
        reclaimIfDue();
        int free = threads - pool.getActiveCount();
        if (free <= 0) {
            return;
        }
        for (SysAsyncTaskRepository.ClaimedJob job : claimBatch(free)) {
            Semaphore permit = permitOf(job.kbId());
            if (!permit.tryAcquire()) {
                // 该库并发已满 —— 还回队列，下一个 tick 再说。
                // ⚠️ 不能在这里阻塞等待：轮询线程被一个库堵住，别的库也跟着停。
                asyncTaskService.reportReleased(job.taskId(), "该知识库解析并发已达上限，排队等待");
                continue;
            }
            try {
                pool.execute(() -> {
                    try {
                        execute(job);
                    } finally {
                        permit.release();
                    }
                });
            } catch (RejectedExecutionException ex) {
                permit.release();
                asyncTaskService.reportReleased(job.taskId(), "解析线程池已满，排队等待");
            }
        }
    }

    /**
     * **同步**领一批并跑完（测试与本地排查用）。
     *
     * <p>存在的理由：测试期 worker 是关掉的（`app.parse.worker.enabled=false`），
     * 后台轮询会让断言依赖时序。有了这个方法，用例可以「上传 → drainOnce() → 断言」，
     * 既确定又**没有丢掉覆盖** —— 比"关掉 worker 然后只断言 UPLOADED"诚实得多。
     *
     * <p>与后台路径的区别：配额**阻塞等待**而不是还回队列（同步调用方要的是确定性）。
     *
     * @return 实际执行的作业数
     */
    public int drainOnce() {
        List<SysAsyncTaskRepository.ClaimedJob> jobs = claimBatch(MAX_BATCH);
        for (SysAsyncTaskRepository.ClaimedJob job : jobs) {
            Semaphore permit = permitOf(job.kbId());
            permit.acquireUninterruptibly();
            try {
                execute(job);
            } finally {
                permit.release();
            }
        }
        return jobs.size();
    }

    private List<SysAsyncTaskRepository.ClaimedJob> claimBatch(int limit) {
        int n = Math.min(Math.max(limit, 1), MAX_BATCH);
        List<SysAsyncTaskRepository.ClaimedJob> jobs = repository.claim(TASK_TYPE, n, workerId());
        if (!jobs.isEmpty()) {
            log.debug("领取解析作业 {} 条", jobs.size());
        }
        return jobs;
    }

    private void execute(SysAsyncTaskRepository.ClaimedJob job) {
        executedTotal.incrementAndGet();
        // ---- `S4-E2`：把提交时的 traceId 恢复到本线程 ----
        // 这条池是**自建**的（不走 `taskExecutor`，因此没有 `AsyncConfig` 的 TaskDecorator），
        // 所以传播只能在这里手动做。不做的后果不是报错而是**串不起来**：
        // `TraceContext.current()` 在工作线程会现场生成一个新 id，
        // 于是"提交"与"完成"两条审计的 traceId 不同（`O6` 判据 2 直接失败），
        // 而且**看起来完全正常** —— 每条日志都有 traceId。
        String traceId = job.traceId();
        if (traceId != null && !traceId.isBlank()) {
            TraceContext.set(traceId);
        }
        try {
            asyncTaskService.reportStage(job.taskId(), "解析中", 10);
            Map<String, Object> result = docParseService.parseDocument(job.docId());
            if (!"FAILED".equals(result.get("status"))) {
                asyncTaskService.reportDone(job.taskId(), 0, 0);
                // 完成审计：与"触发解析"同 target、同 traceId，操作人为「系统」
                // （`AuditAspect` 在无请求无登录态时判为 system —— `O6` 判据 4）
                parseAuditTrail.parseDone(job.docId());
                log.info("解析作业完成 taskId={} docId={} 第 {} 次尝试 status={}",
                        job.taskId(), job.docId(), job.attempt(), result.get("status"));
                return;
            }
            onFailure(job, String.valueOf(result.get("reason")),
                    Boolean.TRUE.equals(result.get("retryable")));
        } catch (BizException ex) {
            // 文档行没了（被删 / 从未转存成功）—— 重试也不会变出来，直接终态
            asyncTaskService.reportFailed(job.taskId(), ex.getMessage());
            parseAuditTrail.parseFailed(job.docId());
            log.warn("解析作业终态失败（业务性）taskId={} docId={} 原因={}",
                    job.taskId(), job.docId(), ex.getMessage());
        } catch (Exception ex) {
            // 异常逃出了 parseDocument 自己的两处 catch → 说明失败发生在**管线之外**
            // （拿行、写状态、线程被中断……），属基础设施类，值得重试
            onFailure(job, "解析执行异常：" + ex.getClass().getSimpleName()
                    + (ex.getMessage() == null ? "" : "：" + ex.getMessage()), true);
        } finally {
            // 池化线程必须清 —— 不清的话下一个作业会继承上一个的 traceId，
            // 那比没有更糟：两条不相干的解析会共用一个 id，排查时被引向错误的方向。
            TraceContext.clear();
        }
    }

    private void onFailure(SysAsyncTaskRepository.ClaimedJob job, String reason, boolean retryable) {
        boolean exhausted = job.attempt() >= maxAttempts;
        if (retryable && !exhausted) {
            LocalDateTime next = LocalDateTime.now().plusSeconds(retryBackoffSeconds * job.attempt());
            asyncTaskService.reportRetryScheduled(job.taskId(), reason, next);
            log.warn("解析作业可重试失败 taskId={} docId={} 第 {}/{} 次 原因={} → {}",
                    job.taskId(), job.docId(), job.attempt(), maxAttempts, reason, next);
            return;
        }
        String finalReason = retryable
                ? reason + "（已重试 " + maxAttempts + " 次仍未成功）"
                : reason;
        asyncTaskService.reportFailed(job.taskId(), finalReason);
        // 只有**终态**才留审计 —— 可重试失败（上面 return 掉的那条）不留：
        // 那个作业还会被重新领走，现在写"解析失败"是**当时不成立**的记录。
        // `O6` 判据 4 要的是"不产生假记录"，这里正是最容易产假记录的一处。
        parseAuditTrail.parseFailed(job.docId());
        log.warn("解析作业终态失败 taskId={} docId={} 尝试 {}/{} retryable={} 原因={}",
                job.taskId(), job.docId(), job.attempt(), maxAttempts, retryable, finalReason);
    }

    /** 回收僵死作业（持有者被强杀后留在 RUNNING 的行）。节流到每 20 轮一次。 */
    private void reclaimIfDue() {
        LocalDateTime now = LocalDateTime.now();
        if (lastReclaimAt != null && lastReclaimAt.plusSeconds(Math.max(pollIntervalMs / 1000 * 20, 20))
                .isAfter(now)) {
            return;
        }
        lastReclaimAt = now;
        repository.reclaimStale(TASK_TYPE, now.minusSeconds(staleSeconds));
    }

    private Semaphore permitOf(Long kbId) {
        long key = kbId == null ? -1L : kbId;
        return kbPermits.computeIfAbsent(key, k -> new Semaphore(Math.max(kbConcurrency, 1)));
    }

    private String workerId() {
        return "parse-worker@" + Integer.toHexString(System.identityHashCode(this))
                + "/" + Thread.currentThread().getName();
    }

    /** 观测用：已执行的作业总数。 */
    public long executedTotal() {
        return executedTotal.get();
    }
}
