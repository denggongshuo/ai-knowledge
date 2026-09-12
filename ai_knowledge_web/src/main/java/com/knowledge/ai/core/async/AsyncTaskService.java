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
 * <p><b>S0 的边界（明确不做）</b>：登记表放在进程内存里，
 * 因此**多实例部署时轮询可能打到没有该任务的实例上**；
 * 重启后进度也丢失。S1 会把它换成 Redis（进度）+ MySQL（终态归档），
 * 本类的对外方法签名不变，所以调用方不会被这次替换波及。
 */
@Slf4j
@Service
public class AsyncTaskService {

    /** 内存登记表上限，超过则淘汰已结束的最旧任务，避免长跑进程内存无界增长。 */
    private static final int MAX_REGISTRY_SIZE = 2000;

    /** 终态任务保留时长（小时），便于前端迟到几十分钟的轮询仍能拿到结果。 */
    private static final int TERMINAL_RETENTION_HOURS = 6;

    private final Executor executor;

    private final Map<String, AsyncTaskInfo<Object>> registry = new ConcurrentHashMap<>();

    /** 幂等键 → taskId。仅在任务未结束时保留。 */
    private final Map<String, String> idempotencyIndex = new ConcurrentHashMap<>();

    public AsyncTaskService(@Qualifier("taskExecutor") Executor executor) {
        this.executor = executor;
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
        return doSubmit(type, null, chunkTotal, work);
    }

    /**
     * 提交任务（带幂等键）。
     *
     * @param idempotencyKey 幂等键，建议形如 {@code doc-parse:10086:md5}；
     *                       为 null 或空串则退化为不幂等
     */
    public String submit(String type, String idempotencyKey, int chunkTotal, Consumer<TaskContext> work) {
        return doSubmit(type, idempotencyKey, chunkTotal, work);
    }

    private String doSubmit(String type, String idempotencyKey, int chunkTotal, Consumer<TaskContext> work) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String existing = idempotencyIndex.get(idempotencyKey);
            if (existing != null) {
                AsyncTaskInfo<Object> info = registry.get(existing);
                if (info != null && !info.getStatus().terminal()) {
                    log.info("幂等命中，复用任务 key={} taskId={}", idempotencyKey, existing);
                    return existing;
                }
                idempotencyIndex.remove(idempotencyKey);
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

        TaskContext context = new TaskContext(info);
        String submitterTraceId = info.getTraceId();
        executor.execute(() -> run(type, taskId, submitterTraceId, work, context));

        log.info("异步任务已提交 type={} taskId={} chunkTotal={} key={}", type, taskId, chunkTotal, idempotencyKey);
        return taskId;
    }

    private void run(String type, String taskId, String submitterTraceId,
                     Consumer<TaskContext> work, TaskContext context) {
        // 工作线程重新绑定提交方的 traceId，让异步日志与触发它的请求串成一条线
        TraceContext.set(submitterTraceId);
        context.info.setStatus(AsyncTaskStatus.RUNNING);
        context.info.setStage("执行中");
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

    public Optional<AsyncTaskInfo<Object>> find(String taskId) {
        return Optional.ofNullable(registry.get(taskId));
    }

    /** 查不到抛 10045（{@code 任务不存在}）。 */
    @SuppressWarnings("unchecked")
    public <T> AsyncTaskInfo<T> require(String taskId) {
        AsyncTaskInfo<Object> info = registry.get(taskId);
        if (info == null) {
            throw BizException.of(ErrorCode.TASK_NOT_FOUND);
        }
        return (AsyncTaskInfo<T>) info;
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

        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        private volatile boolean finished;

        TaskContext(AsyncTaskInfo<Object> info) {
            this.info = info;
        }

        public String taskId() {
            return info.getTaskId();
        }

        /** 更新阶段文案，如"解析中"→"索引中"。 */
        public void stage(String stage) {
            info.setStage(stage);
        }

        /** 已知总步数时修正总数。 */
        public void total(int chunkTotal) {
            info.setChunkTotal(Math.max(chunkTotal, 0));
            recalcPercent();
        }

        /** 完成 +1 步。 */
        public void advance() {
            info.setChunkDone(info.getChunkDone() + 1);
            recalcPercent();
        }

        /** 直接设置已完成步数（并行任务用）。 */
        public void done(int chunkDone) {
            info.setChunkDone(Math.max(chunkDone, 0));
            recalcPercent();
        }

        /** 手工设置百分比（阶段不可细分时用，如打包的 30% → 70%）。 */
        public void percent(int percent) {
            int clamped = Math.max(0, Math.min(percent, 100));
            if (clamped > info.getPercent()) {
                info.setPercent(clamped);
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
