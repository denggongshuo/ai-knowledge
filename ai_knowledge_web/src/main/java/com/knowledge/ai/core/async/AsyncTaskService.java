package com.knowledge.ai.core.async;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 异步任务服务（T-006）：submit → taskId + 进度 + 幂等。
 *
 * <p>解决的问题：文档解析 / 索引 / 批量打包 / 审计导出都是**分钟级**任务，
 * 不能让 HTTP 请求线程一直挂着。统一外壳是：
 * <pre>
 *   POST /kb/{kbId}/doc/upload   → 立刻返回 { docId, taskId, ... }
 *   GET  /doc/tasks/{taskId}      → 轮询进度 { stage, percent, chunkDone, chunkTotal }
 * </pre>
 *
 * <p><b>幂等</b>：{@link #submit(String, String, int, Consumer)} 带
 * {@code idempotencyKey}，同一个 key 在任务未结束前重复提交会拿到**同一个 taskId**，
 * 而不是启动第二个任务。用于抵御"用户连点两下上传""前端超时重试"。
 *
 * <p><b>threading</b>：任务体在工作线程执行，**ThreadLocal 不跨线程** ——
 * 所以这里在提交时把 traceId 取出来传给任务体，并在工作线程重新绑定，
 * 结束后清理，避免污染池化线程（见 {@link TraceContext} 的说明）。
 *
 * <p><b>`S4-E1` 起已落库</b>（{@code T-O01}）：内存登记表保留为**快路径**，
 * 每次状态迁移**写穿**到 {@code sys_async_task}（{@link SysAsyncTaskRepository}），
 * 于是「重启后仍可查」（{@code O1} 判据 6）成立 —— 内存未命中时回落查库。
 * 这同时销掉了 S0 那句「S1 会把它换成 Redis + MySQL」的欠账
 * （终态归档一类不需要 Redis：**能查得到**就够了，而 Redis 反而会引入第二处状态）。
 *
 * <p><b>⚠️ 投递时刻刻意**没有**推迟到事务提交之后</b> —— 试过，会把上传打死。
 * 理由与实测代价写在 {@code doSubmit} 上方那段注释里，**改之前务必先读它**。
 *
 * <p><b>仍未解决</b>：多实例部署时，读路径靠"内存未命中回落查库"兜住，但
 * **领取**必须走 {@code SKIP LOCKED} 的表（{@link SysAsyncTaskRepository#claim}）
 * 才是多实例安全的 —— 内存 registry 天然只认识本进程的任务。
 */
@Slf4j
@Service
public class AsyncTaskService {

    /** 内存登记表上限，超过则淘汰已结束的最旧任务，避免长跑进程内存无界增长。 */
    private static final int MAX_REGISTRY_SIZE = 2000;

    /** 终态任务保留时长（小时），便于前端迟到几十分钟的轮询仍能拿到结果。 */
    private static final int TERMINAL_RETENTION_HOURS = 6;

    private final Executor executor;

    private final SysAsyncTaskRepository repository;

    /**
     * 通知的唯一产出点（`T-B03`）。
     *
     * <p><b>🔴 为什么它由本类持有、而不是挂在 {@link SysAsyncTaskRepository#markDone} 上</b>：
     * 终态写入有两条路（内存命中走 {@link TaskContext}，库回退直达 repository），
     * 交汇点上**拿不到 `result`**（那列不持久化），而 {@code audit-export} 的通知要靠
     * `result.fileUrl` 才有可点的动作。⇒ 生成点必须在"手里还攥着 result"的这一层。
     * 全文与代价见 {@link com.knowledge.ai.core.notice.NoticePublisher} 的 javadoc。
     */
    private final com.knowledge.ai.core.notice.NoticePublisher noticePublisher;

    private final Map<String, AsyncTaskInfo<Object>> registry = new ConcurrentHashMap<>();

    /** 幂等键 → taskId。仅在任务未结束时保留。 */
    private final Map<String, String> idempotencyIndex = new ConcurrentHashMap<>();

    public AsyncTaskService(@Qualifier("taskExecutor") Executor executor, SysAsyncTaskRepository repository,
                            com.knowledge.ai.core.notice.NoticePublisher noticePublisher) {
        this.executor = executor;
        this.repository = repository;
        this.noticePublisher = noticePublisher;
    }

    /**
     * 任务的**业务坐标** —— 解析作业要用（`kbId` 是并发配额的键，`docId` 是执行目标），
     * `ownerId` 是 `O6` 的「谁在何时触发了 X 的解析」要读的列。
     *
     * <p>非解析类任务（审计导出、批量下载）传 {@link #none()}。
     */
    public record TaskSubject(Long kbId, Long docId, Long ownerId) {

        public static TaskSubject none() {
            return new TaskSubject(null, null, null);
        }

        public static TaskSubject of(Long kbId, Long docId, Long ownerId) {
            return new TaskSubject(kbId, docId, ownerId);
        }
    }

    // ==================== 提交 ====================

    /**
     * 提交任务（不带幂等键）。
     *
     * @param type       任务类型标识，如 {@code doc-parse}
     * @param chunkTotal 预期总步数（分片数）；未知传 0
     * @param work       任务体，用 {@link TaskContext} 上报进度
     * @return taskId
     */
    public String submit(String type, int chunkTotal, Consumer<TaskContext> work) {
        return doSubmit(type, TaskSubject.none(), null, chunkTotal, work);
    }

    /**
     * 提交任务（带幂等键）。
     *
     * @param idempotencyKey 幂等键，建议形如 {@code doc-parse:10086:md5}；
     *                       为 null 或空串则退化为不幂等
     */
    public String submit(String type, String idempotencyKey, int chunkTotal, Consumer<TaskContext> work) {
        return doSubmit(type, TaskSubject.none(), idempotencyKey, chunkTotal, work);
    }

    /**
     * 提交任务（带业务坐标）—— `S4-E1` 新增。`kbId` 是解析并发配额的键。
     */
    public String submit(String type, TaskSubject subject, String idempotencyKey, int chunkTotal,
                         Consumer<TaskContext> work) {
        return doSubmit(type, subject, idempotencyKey, chunkTotal, work);
    }

    private String doSubmit(String type, TaskSubject subject, String idempotencyKey, int chunkTotal,
                            Consumer<TaskContext> work) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String existing = idempotencyIndex.get(idempotencyKey);
            if (existing != null) {
                AsyncTaskInfo<Object> info = registry.get(existing);
                if (info != null && !info.getStatus().terminal()) {
                    log.info("幂等命中（内存），复用任务 key={} taskId={}", idempotencyKey, existing);
                    return existing;
                }
                idempotencyIndex.remove(idempotencyKey);
            }
            // 内存未命中：重启后索引是空的，但**库里可能还有一条没跑完的同键任务**。
            // 不回落到这里，"重启后用户重传同一个文件"就会开出第二个任务 —— 幂等在重启面前失效。
            Optional<String> persisted = repository.findActiveByIdempotencyKey(idempotencyKey);
            if (persisted.isPresent()) {
                log.info("幂等命中（落库），复用任务 key={} taskId={}", idempotencyKey, persisted.get());
                return persisted.get();
            }
        }

        String taskId = TraceContext.newTraceId();
        AsyncTaskInfo<Object> info = new AsyncTaskInfo<>();
        info.setTaskId(taskId);
        info.setType(type);
        info.setStatus(AsyncTaskStatus.PENDING);
        info.setStage("排队中");
        info.setChunkTotal(Math.max(chunkTotal, 0));
        info.setCreatedAt(LocalDateTime.now());
        info.setTraceId(TraceContext.current());
        registry.put(taskId, info);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyIndex.put(idempotencyKey, taskId);
        }

        evictIfNeeded();

        // 落库：与业务行**同一个事务**（提交方在事务里时），故必须等提交后才真正投递
        repository.insert(info, subject.kbId(), subject.docId(), subject.ownerId(), idempotencyKey);

        TaskContext context = new TaskContext(info, repository, noticePublisher);
        String submitterTraceId = info.getTraceId();
        executor.execute(() -> run(type, taskId, submitterTraceId, work, context));

        log.info("异步任务已提交 type={} taskId={} chunkTotal={} key={} kbId={} docId={}",
                type, taskId, chunkTotal, idempotencyKey, subject.kbId(), subject.docId());
        return taskId;
    }

    // ==================================================================
    // ⚠️ 这里**刻意没有**「推迟到事务提交之后再投递」的实现（试过，已回退）
    //
    // 动机是好的：任务体要 `markStored` 更新刚插入的 `sys_document` 行，而那一行
    // 还在提交方的未提交事务里 —— 工作线程读不到未提交的数据（表现为 `markStored`
    // 更新 0 行，文档永远没有 `storage_path`）。用
    // `TransactionSynchronizationManager#afterCommit` 把投递推迟到提交之后，
    // 看起来正好解决它。
    //
    // **但它会打死上传**：上传任务体要读 `MultipartFile`，而 `MultipartFile` 是
    // **请求作用域**的 —— 请求一结束，容器就清掉底层的 multipart 临时文件，
    // 于是 `file.isEmpty()` 变成 true。而 `DocStorage#store` 开头就调
    // `validate(file)`（`DocStorage.java:98`），直接抛「请选择要上传的文件」。
    // 实测代价：`V17` 之后每一条 `doc-upload` 都是这个错，且**表现为 FAILED 而不是崩**
    // （任务能落库，所以现象是"上传成功但文档永远停在已上传"）。
    //
    // 结论：**立即投递**，接受"任务可能先于请求事务提交而启动"这个**既有**竞态
    // （`S4-E1` 之前就存在，靠任务体先做一次真实 MinIO PUT 的耗时侥幸躲过）。
    // 它已登记为遗留（计划 §9），修它需要先把上传字节落到不随请求消亡的地方，
    // 那是另一件事，不该混在这一批里顺手做。
    // ==================================================================

    private void run(String type, String taskId, String submitterTraceId,
                     Consumer<TaskContext> work, TaskContext context) {
        // 工作线程重新绑定提交方的 traceId，让异步日志与触发它的请求串成一条线
        TraceContext.set(submitterTraceId);
        context.info.setStatus(AsyncTaskStatus.RUNNING);
        context.stage("执行中");
        try {
            work.accept(context);
            if (!context.finished) {
                context.complete(null);
            }
            log.info("异步任务完成 type={} taskId={} costMs={}", type, taskId, context.info.costMs());
        } catch (Exception ex) {
            context.fail(ex);
            log.error("异步任务失败 type={} taskId={}", type, taskId, ex);
        } finally {
            TraceContext.clear();
        }
    }

    // ==================== 查询 ====================

    /**
     * 查任务（内存优先，**未命中回落查库**）。
     *
     * <p>回落这一步就是「重启后仍可查」（`O1` 判据 6）的全部实现 ——
     * 重启后内存 registry 是空的，能答出这个问题的只剩 `sys_async_task`。
     */
    public Optional<AsyncTaskInfo<Object>> find(String taskId) {
        AsyncTaskInfo<Object> inMemory = registry.get(taskId);
        if (inMemory != null) {
            return Optional.of(inMemory);
        }
        Optional<AsyncTaskInfo<Object>> persisted = repository.find(taskId);
        // 回填内存：下次轮询不必再查库（终态任务也不会被 evictIfNeeded 之外的逻辑动）
        persisted.ifPresent(info -> registry.put(taskId, info));
        return persisted;
    }

    /** 查不到抛 10045（{@code 任务不存在}）。同 {@link #find} —— **重启后仍答得出**。 */
    @SuppressWarnings("unchecked")
    public <T> AsyncTaskInfo<T> require(String taskId) {
        return (AsyncTaskInfo<T>) find(taskId).orElseThrow(() -> BizException.of(ErrorCode.TASK_NOT_FOUND));
    }

    // ==================== 入队（不投递）· `S4-E1` ====================

    /**
     * **只入队、不投递** —— 作业交给**领取方**执行（`ParseWorker` 用 {@code SKIP LOCKED} 领）。
     *
     * <p>与 {@link #submit} 的唯一区别就是这一条：submit 走 executor 立刻开跑；
     * 本方法把执行权交给领取方。之所以要分开，是因为解析作业的执行必须满足两件
     * submit 给不了的事：**按 kbId 的并发配额**、以及**进程重启后被重新领取**
     * （后者要求作业在库里可见，而不是绑在某次 submit 的执行栈上）。
     *
     * <p>返回的 taskId 与 submit 一样可被 {@code GET /doc/tasks/{taskId}} 轮询。
     */
    public String enqueue(String type, TaskSubject subject, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<String> persisted = repository.findActiveByIdempotencyKey(idempotencyKey);
            if (persisted.isPresent()) {
                log.info("入队幂等命中，复用任务 key={} taskId={}", idempotencyKey, persisted.get());
                return persisted.get();
            }
        }
        String taskId = TraceContext.newTraceId();
        AsyncTaskInfo<Object> info = new AsyncTaskInfo<>();
        info.setTaskId(taskId);
        info.setType(type);
        info.setStatus(AsyncTaskStatus.PENDING);
        info.setStage("排队中");
        info.setCreatedAt(LocalDateTime.now());
        info.setTraceId(TraceContext.current());
        registry.put(taskId, info);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyIndex.put(idempotencyKey, taskId);
        }
        repository.insert(info, subject.kbId(), subject.docId(), subject.ownerId(), idempotencyKey);
        log.info("作业已入队（不投递）type={} taskId={} kbId={} docId={}",
                type, taskId, subject.kbId(), subject.docId());
        return taskId;
    }

    // ==================== 领取方用的驱动接口 · `S4-E1` ====================
    //
    // `ParseWorker` 领到作业后执行它，并调用下面这组方法回报进度与终态。
    // 它们都**同时更新内存与库**：内存是轮询的快路径，库是重启后的事实源。

    /** 领取方回报阶段与百分比。 */
    public void reportStage(String taskId, String stage, int percent) {
        AsyncTaskInfo<Object> info = registry.get(taskId);
        if (info != null) {
            info.setStatus(AsyncTaskStatus.RUNNING);
            info.setStage(stage);
            info.setPercent(Math.max(info.getPercent(), percent));
        }
        repository.updateProgress(taskId, stage,
                info == null ? percent : info.getPercent(), 0, 0);
    }

    /** 领取方回报成功。 */
    public void reportDone(String taskId, int chunkDone, int chunkTotal) {
        AsyncTaskInfo<Object> info = registry.get(taskId);
        if (info != null) {
            // 复用 TaskContext 的终态语义（stage 覆写成"已完成"、finishedAt 落上）
            new TaskContext(info, repository, noticePublisher).complete(null);
            return;
        }
        repository.markDone(taskId, chunkDone, chunkTotal);
        // ⚠️ **库回退分支（路径②）也要发通知** —— 这一支恰恰是"重启后由 worker 领走"的
        // 那些任务，也就是**耗时最长、用户最需要被告知**的那一类。少接这一条，
        // 长任务会安静地跑完而没有任何通知，且现象很难被注意到。
        // 代价：这一支**没有 result**（领取方不持有它），只能发不带结果的动作 —— 见 NoticePublisher。
        noticePublisher.onTaskDone(taskId, null);
    }

    /** 领取方回报**终态失败**（不可重试 / 已耗尽重试）。 */
    public void reportFailed(String taskId, String reason) {
        AsyncTaskInfo<Object> info = registry.get(taskId);
        if (info != null) {
            new TaskContext(info, repository, noticePublisher).fail(new IllegalStateException(reason));
            return;
        }
        repository.markFailed(taskId, reason);
        // 同上：库回退分支也要发（失败更需要被告知 —— 那是"任务中断"类告警）
        noticePublisher.onTaskFailed(taskId, reason);
    }

    /**
     * 领取方回报**可重试失败** —— 回到"排队中"，等退避到点再被领走。
     *
     * <p>与 {@link #reportFailed} 的区别就是**对用户说什么**：这条说"还在重试"，
     * 那条说"这篇不行了"。混用会让死信看起来还在跑（`O1` §4 步 7）。
     */
    public void reportRetryScheduled(String taskId, String reason, LocalDateTime nextAttemptAt) {
        AsyncTaskInfo<Object> info = registry.get(taskId);
        if (info != null) {
            info.setStatus(AsyncTaskStatus.PENDING);
            info.setStage("等待重试");
            info.setErrorMsg(reason);
        }
        repository.scheduleRetry(taskId, nextAttemptAt, reason);
    }

    /** 领取方把作业**还回队列**（配额/池满时未真正执行）—— 不消耗 attempt。 */
    public void reportReleased(String taskId, String reason) {
        AsyncTaskInfo<Object> info = registry.get(taskId);
        if (info != null) {
            info.setStatus(AsyncTaskStatus.PENDING);
            info.setStage("排队中");
        }
        repository.release(taskId, reason);
    }

    // ==================== 清理 ====================

    private void evictIfNeeded() {
        if (registry.size() <= MAX_REGISTRY_SIZE) {
            return;
        }
        LocalDateTime deadline = LocalDateTime.now().minusHours(TERMINAL_RETENTION_HOURS);
        registry.entrySet().removeIf(entry -> {
            AsyncTaskInfo<Object> info = entry.getValue();
            boolean expired = info.getStatus().terminal()
                    && info.getFinishedAt() != null
                    && info.getFinishedAt().isBefore(deadline);
            if (expired) {
                idempotencyIndex.values().remove(entry.getKey());
            }
            return expired;
        });

        // 仍然超限：按创建时间淘汰最旧的一批（任务已结束的优先）
        if (registry.size() > MAX_REGISTRY_SIZE) {
            registry.values().stream()
                    .sorted(Comparator.comparing(AsyncTaskInfo<Object>::getCreatedAt))
                    .limit(Math.max(1, registry.size() - MAX_REGISTRY_SIZE))
                    .forEach(info -> {
                        if (info.getStatus().terminal()) {
                            registry.remove(info.getTaskId());
                        }
                    });
        }
    }

    /**
     * 任务上下文：任务体用它上报进度。
     *
     * <p>进度上报是**幂等且单向**的：{@code percent} 只增不减，
     * 且写入 volatile 字段即可 —— 轮询线程读到的可能是稍微旧的值，
     * 对进度展示完全够用，不值得为它上锁。
     */
    public static final class TaskContext {

        private final AsyncTaskInfo<Object> info;

        /** 进度与终态的落点（`S4-E1`）。内存仍是快路径，库是事实源。 */
        private final SysAsyncTaskRepository repository;

        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        /** 终态时发通知（`T-B03`）。**只有这一条产出路径** —— 别在业务里另写。 */
        private final com.knowledge.ai.core.notice.NoticePublisher publisher;

        private volatile boolean finished;

        TaskContext(AsyncTaskInfo<Object> info, SysAsyncTaskRepository repository,
                    com.knowledge.ai.core.notice.NoticePublisher publisher) {
            this.info = info;
            this.repository = repository;
            this.publisher = publisher;
        }

        public String taskId() {
            return info.getTaskId();
        }

        /** 更新阶段文案，如"解析中"→"索引中"。**写穿到库** —— 重启后靠它认出进度。 */
        public void stage(String stage) {
            info.setStage(stage);
            persistProgress();
        }

        /** 已知总步数时修正总数。 */
        public void total(int chunkTotal) {
            info.setChunkTotal(Math.max(chunkTotal, 0));
            recalcPercent();
            persistProgress();
        }

        /** 完成 +1 步。 */
        public void advance() {
            info.setChunkDone(info.getChunkDone() + 1);
            recalcPercent();
            persistProgress();
        }

        /** 直接设置已完成步数（并行任务用）。 */
        public void done(int chunkDone) {
            info.setChunkDone(Math.max(chunkDone, 0));
            recalcPercent();
            persistProgress();
        }

        /** 手工设置百分比（阶段不可细分时用，如打包的 30% → 70%）。 */
        public void percent(int percent) {
            int clamped = Math.max(0, Math.min(percent, 100));
            if (clamped > info.getPercent()) {
                info.setPercent(clamped);
            }
            persistProgress();
        }

        /**
         * 进度**写穿**到 `sys_async_task`。
         *
         * <p>为什么容错：进度是**观测数据**，任务本身跑通才是正事 ——
         * 一次 UPDATE 失败不该把一篇能解析的文档判失败。
         * 但**必须留痕**（"静默降级视为缺陷"是《02》的验收口径第一条），所以是 warn 不是忽略。
         */
        private void persistProgress() {
            try {
                repository.updateProgress(info.getTaskId(), info.getStage(), info.getPercent(),
                        info.getChunkDone(), info.getChunkTotal());
            } catch (Exception ex) {
                log.warn("任务进度落库失败（不影响任务本身）：taskId={} err={}",
                        info.getTaskId(), ex.getMessage());
            }
        }

        /** 任务成功结束，可携带业务结果。 */
        public void complete(Object result) {
            if (finished) {
                return;
            }
            finished = true;
            info.setStatus(AsyncTaskStatus.DONE);
            info.setPercent(100);
            info.setChunkDone(info.getChunkTotal());
            info.setResult(result);
            info.setFinishedAt(LocalDateTime.now());
            info.setStage("已完成");
            persistTerminal();
            // 通知在**终态落库之后**发：先让"这事做完了"成为事实，再说"告诉谁"。
            // ⚠️ 传 result —— 这一层是唯一还拿得到它的地方（见 NoticePublisher 的 javadoc）。
            // 发布失败只记 warn，绝不回滚终态（publisher 内部已包住）。
            publisher.onTaskDone(info.getTaskId(), result);
        }

        /** 任务失败结束。 */
        public void fail(Exception error) {
            if (finished) {
                return;
            }
            finished = true;
            info.setStatus(AsyncTaskStatus.FAILED);
            info.setFinishedAt(LocalDateTime.now());
            info.setStage("失败");
            String message = error.getMessage();
            info.setErrorMsg(message == null || message.isBlank() ? error.getClass().getSimpleName() : message);
            persistTerminal();
            publisher.onTaskFailed(info.getTaskId(), info.getErrorMsg());
        }

        /**
         * 终态落库。
         *
         * <p>与 {@link #persistProgress} 不同，这里**失败了要抛** —— "任务已完成"这件事
         * 是重启后唯一能查到的凭据，写不进去就等于任务状态不明。
         * 但异常也不能盖掉原始失败原因，所以只记日志、不抛（与 `DocRecordWriter#markFailed` 同一取舍）。
         */
        private void persistTerminal() {
            try {
                if (info.getStatus() == AsyncTaskStatus.DONE) {
                    repository.markDone(info.getTaskId(), info.getChunkDone(), info.getChunkTotal());
                } else {
                    repository.markFailed(info.getTaskId(), info.getErrorMsg());
                }
            } catch (Exception ex) {
                log.error("任务终态落库失败（重启后将查不到这个任务）：taskId={} status={} err={}",
                        info.getTaskId(), info.getStatus(), ex.getMessage());
            }
        }

        /** 任务体自行检查是否已被取消（S1 接取消接口后用）。 */
        public boolean isCancelled() {
            return cancelled.get();
        }

        /** 取消任务：任务体在下一个检查点退出。 */
        public void cancel() {
            cancelled.set(true);
        }

        private void recalcPercent() {
            int total = info.getChunkTotal();
            if (total <= 0) {
                return;
            }
            int percent = (int) Math.min(100L, info.getChunkDone() * 100L / total);
            // 未完成时不要显示 100%，避免前端提前停轮询
            if (percent == 100 && info.getStatus() != AsyncTaskStatus.DONE) {
                percent = 99;
            }
            if (percent > info.getPercent()) {
                info.setPercent(percent);
            }
        }
    }
}
