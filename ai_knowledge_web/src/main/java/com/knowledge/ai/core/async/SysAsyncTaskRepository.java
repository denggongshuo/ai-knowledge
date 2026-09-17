package com.knowledge.ai.core.async;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code sys_async_task} 的读写（`T-O01` · `S4-E1`）。
 *
 * <h3>为什么是表驱动，而不是内存 registry 的持久化版本</h3>
 * 内存 {@code ConcurrentHashMap}（{@link AsyncTaskService}）回答不了三个问题：
 * <b>重启后还剩什么</b>、<b>每个库同时在跑几篇</b>、<b>失败该不该重试</b>。
 * 本类就是那三个答案的落点 —— 它不替代内存 registry（读路径仍优先走内存，快），
 * 而是给它补上**持久化的事实源**。
 *
 * <h3>领取为什么必须 {@code SKIP LOCKED}</h3>
 * 多个 worker 同时领取时，普通 {@code FOR UPDATE} 会让它们**互相排队**（领取退化成串行）；
 * {@code SKIP LOCKED} 让每个 worker 拿到**互不重叠**的一批，这是本方案不用 MQ 的前提。
 * ⚠️ 领取语句与随后的置 RUNNING 必须在**同一个事务**里 ——
 * {@code FOR UPDATE} 的锁在事务结束时释放，分成两个事务就失去了互斥。
 *
 * <h3>为什么不写 {@code @Transactional} 到每个方法上</h3>
 * 只有 {@link #claim} 需要（它必须原子地"选中并占住"）；其余都是单条 UPDATE，
 * 由各自的写路径决定事务边界 —— 尤其 {@link #markFailed} 不能与业务事务同生共死
 * （业务回滚了，"失败"这件事仍然要记下来）。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SysAsyncTaskRepository {

    private final EntityManager entityManager;

    // ==================== 写 ====================

    /** 入队：写一行 PENDING。 */
    @Transactional
    public void insert(AsyncTaskInfo<?> info, Long kbId, Long docId, Long ownerId, String idempotencyKey) {
        entityManager.createNativeQuery(
                        "insert into sys_async_task (task_id, type, status, kb_id, doc_id, owner_id, trace_id, "
                                + "idempotency_key, stage, percent, chunk_done, chunk_total, attempt, "
                                + "created_at, updated_at) "
                                + "values (:taskId, :type, 'PENDING', :kbId, :docId, :ownerId, :traceId, "
                                + ":idemKey, :stage, 0, 0, :chunkTotal, 0, :now, :now)")
                .setParameter("taskId", info.getTaskId())
                .setParameter("type", info.getType())
                .setParameter("kbId", kbId)
                .setParameter("docId", docId)
                .setParameter("ownerId", ownerId)
                .setParameter("traceId", info.getTraceId())
                .setParameter("idemKey", idempotencyKey)
                .setParameter("stage", info.getStage())
                .setParameter("chunkTotal", info.getChunkTotal())
                .setParameter("now", LocalDateTime.now())
                .executeUpdate();
    }

    /** 进度：阶段文案 / 百分比 / 分片数。 */
    @Transactional
    public void updateProgress(String taskId, String stage, int percent, int chunkDone, int chunkTotal) {
        entityManager.createNativeQuery(
                        "update sys_async_task set stage = :stage, percent = :percent, "
                                + "chunk_done = :chunkDone, chunk_total = :chunkTotal, updated_at = :now "
                                + "where task_id = :taskId")
                .setParameter("stage", stage)
                .setParameter("percent", percent)
                .setParameter("chunkDone", chunkDone)
                .setParameter("chunkTotal", chunkTotal)
                .setParameter("now", LocalDateTime.now())
                .setParameter("taskId", taskId)
                .executeUpdate();
    }

    /** 终态：成功。 */
    @Transactional
    public void markDone(String taskId, int chunkDone, int chunkTotal) {
        LocalDateTime now = LocalDateTime.now();
        entityManager.createNativeQuery(
                        "update sys_async_task set status = 'DONE', stage = '已完成', percent = 100, "
                                + "chunk_done = :chunkDone, chunk_total = :chunkTotal, error_msg = null, "
                                + "locked_by = null, locked_at = null, finished_at = :now, updated_at = :now "
                                + "where task_id = :taskId")
                .setParameter("chunkDone", chunkDone)
                .setParameter("chunkTotal", chunkTotal)
                .setParameter("now", now)
                .setParameter("taskId", taskId)
                .executeUpdate();
    }

    /** 终态：失败（含死信）。{@code reason} 截断到列宽。 */
    @Transactional
    public void markFailed(String taskId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        entityManager.createNativeQuery(
                        "update sys_async_task set status = 'FAILED', stage = '失败', error_msg = :msg, "
                                + "locked_by = null, locked_at = null, finished_at = :now, updated_at = :now "
                                + "where task_id = :taskId")
                .setParameter("msg", truncate(reason))
                .setParameter("now", now)
                .setParameter("taskId", taskId)
                .executeUpdate();
    }

    /**
     * 可重试失败：**回到 PENDING** 并定下次可领时刻（退避）。
     *
     * <p>与 {@link #markFailed} 的区别就是「对用户说什么」：这一条说"还在重试"，
     * 那一条说"这篇不行了"。两者**不能混**（`O1` §4 步 7）。
     */
    @Transactional
    public void scheduleRetry(String taskId, LocalDateTime nextRetryAt, String reason) {
        entityManager.createNativeQuery(
                        "update sys_async_task set status = 'PENDING', stage = :stage, "
                                + "error_msg = :msg, next_retry_at = :next, "
                                + "locked_by = null, locked_at = null, updated_at = :now "
                                + "where task_id = :taskId")
                .setParameter("stage", "等待重试")
                .setParameter("msg", truncate(reason))
                .setParameter("next", nextRetryAt)
                .setParameter("now", LocalDateTime.now())
                .setParameter("taskId", taskId)
                .executeUpdate();
    }

    /**
     * **还回队列**：作业被领走但因配额/池满**没有真正执行**，放回 PENDING。
     *
     * <p>⚠️ 必须把 {@code attempt} 减回去 —— {@link #claim} 在领取时就 +1 了，
     * 而 {@code attempt} 的语义是「**已开始的执行次数**」。不减的话，
     * "被配额挡回去三次"会被算成"失败三次"直接进死信 —— 一个从来没跑过的作业被判死。
     */
    @Transactional
    public void release(String taskId, String reason) {
        entityManager.createNativeQuery(
                        "update sys_async_task set status = 'PENDING', stage = '排队中', "
                                + "attempt = greatest(attempt - 1, 0), error_msg = :msg, "
                                + "locked_by = null, locked_at = null, updated_at = :now "
                                + "where task_id = :taskId")
                .setParameter("msg", truncate(reason))
                .setParameter("now", LocalDateTime.now())
                .setParameter("taskId", taskId)
                .executeUpdate();
    }

    // ==================== 领取 ====================

    /**
     * 领取一批作业并**原子地**置为 RUNNING。
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} + 同一事务内的 UPDATE —— 见类注释。
     * {@code attempt} 在这里 +1，语义是「**已开始的执行次数**」。
     */
    @SuppressWarnings("unchecked")
    @Transactional
    public List<ClaimedJob> claim(String type, int limit, String lockedBy) {
        LocalDateTime now = LocalDateTime.now();
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select task_id, kb_id, doc_id, attempt from sys_async_task "
                                + "where type = :type and status = 'PENDING' "
                                + "and (next_retry_at is null or next_retry_at <= :now) "
                                + "order by created_at "
                                + "limit :limit for update skip locked")
                .setParameter("type", type)
                .setParameter("now", now)
                .setParameter("limit", limit)
                .getResultList();
        if (rows.isEmpty()) {
            return List.of();
        }
        List<ClaimedJob> claimed = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            String taskId = (String) r[0];
            Long kbId = r[1] == null ? null : ((Number) r[1]).longValue();
            Long docId = r[2] == null ? null : ((Number) r[2]).longValue();
            int attempt = ((Number) r[3]).intValue() + 1;
            entityManager.createNativeQuery(
                            "update sys_async_task set status = 'RUNNING', attempt = :attempt, "
                                    + "locked_by = :by, locked_at = :now, updated_at = :now "
                                    + "where task_id = :taskId")
                    .setParameter("attempt", attempt)
                    .setParameter("by", lockedBy)
                    .setParameter("now", now)
                    .setParameter("taskId", taskId)
                    .executeUpdate();
            claimed.add(new ClaimedJob(taskId, kbId, docId, attempt));
        }
        return claimed;
    }

    /**
     * 回收**持有者已死**的 RUNNING 行（`O1` 判据 4 的落地方式）。
     *
     * <p>进程被 {@code kill -9} 时，它领走的那几行会永远停在 RUNNING —— 没有任何代码
     * 会回来把它们改回 PENDING。靠 {@code locked_at} 超时兜底：心跳停了足够久，
     * 就认为持有者死了，交给别人重做。
     *
     * @return 回收行数
     */
    @Transactional
    public int reclaimStale(String type, LocalDateTime lockedBefore) {
        LocalDateTime now = LocalDateTime.now();
        int n = entityManager.createNativeQuery(
                        "update sys_async_task set status = 'PENDING', locked_by = null, locked_at = null, "
                                + "updated_at = :now where type = :type and status = 'RUNNING' "
                                + "and locked_at is not null and locked_at < :before")
                .setParameter("now", now)
                .setParameter("type", type)
                .setParameter("before", lockedBefore)
                .executeUpdate();
        if (n > 0) {
            log.warn("回收僵死任务 {} 行（type={} 领取时刻早于 {}）—— 持有者大概率已被强杀", n, type, lockedBefore);
        }
        return n;
    }

    // ==================== 读 ====================

    /** 按 taskId 查（内存 registry 未命中时回落用）。 */
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Optional<AsyncTaskInfo<Object>> find(String taskId) {
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select task_id, type, status, stage, percent, chunk_done, chunk_total, error_msg, "
                                + "trace_id, created_at, finished_at from sys_async_task where task_id = :taskId")
                .setParameter("taskId", taskId)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] r = rows.getFirst();
        AsyncTaskInfo<Object> info = new AsyncTaskInfo<>();
        info.setTaskId((String) r[0]);
        info.setType((String) r[1]);
        info.setStatus(AsyncTaskStatus.valueOf((String) r[2]));
        info.setStage((String) r[3]);
        info.setPercent(((Number) r[4]).intValue());
        info.setChunkDone(((Number) r[5]).intValue());
        info.setChunkTotal(((Number) r[6]).intValue());
        info.setErrorMsg((String) r[7]);
        info.setTraceId((String) r[8]);
        info.setCreatedAt(toLdt(r[9]));
        info.setFinishedAt(toLdt(r[10]));
        // ⚠️ `result` **不持久化**（它是任务体内的对象，无法通用地序列化）。
        //    重启后拿到的 result 为 null —— 前端不依赖它：上传响应本来就同步返回 docId，
        //    进度详情由 sys_document 承载。这是有意识的取舍，见计划 §9。
        return Optional.of(info);
    }

    /**
     * 幂等命中查询。
     *
     * <p>⚠️ **必须带状态过滤** —— 现有语义是「仅任务**未结束时**该键被占，完成即释放」
     * （{@link AsyncTaskService} 的注释）。所以去重键上**不能加唯一索引**，
     * 只能这样查（见 `V17` 的列注释）。
     */
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Optional<String> findActiveByIdempotencyKey(String key) {
        List<Object> rows = entityManager.createNativeQuery(
                        "select task_id from sys_async_task where idempotency_key = :key "
                                + "and status in ('PENDING', 'RUNNING') order by created_at desc limit 1")
                .setParameter("key", key)
                .getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of((String) rows.getFirst());
    }

    /** 某文档的全部任务（排查用）。 */
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public List<String> taskIdsOfDoc(long docId) {
        return entityManager.createNativeQuery(
                        "select task_id from sys_async_task where doc_id = :docId order by created_at")
                .setParameter("docId", docId)
                .getResultList();
    }

    // ==================== 小工具 ====================

    /** 领取到的一条作业。 */
    public record ClaimedJob(String taskId, Long kbId, Long docId, int attempt) {
    }

    private static String truncate(String reason) {
        if (reason == null || reason.isBlank()) {
            return "任务失败（未提供细节）";
        }
        String v = reason.trim();
        return v.length() > 500 ? v.substring(0, 500) : v;
    }

    /** Hibernate 7 的 native query 可能回 {@code java.sql.Timestamp} 或 {@code LocalDateTime}。 */
    private static LocalDateTime toLdt(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return null;
    }
}
