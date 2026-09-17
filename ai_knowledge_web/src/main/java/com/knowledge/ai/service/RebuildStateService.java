package com.knowledge.ai.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「这个库**此刻正在重建索引**吗」（`T-709`）。
 *
 * <h3>判据是任务表，不是知识库的某个状态列</h3>
 * <p>重建走的是 {@code ReindexService#submit} → 进度写 {@code sys_async_task}。
 * 库里**没有**"重建中"这个列，也不该有：那会是与任务表并存的第二份状态，
 * 而两份状态迟早不一致（进程被杀时库里的那条永远不会被清掉，
 * 于是"永久重建中"—— 检索永远降级，且没有任何东西会把它改回来）。
 *
 * <h3>为什么要有缓存</h3>
 * <p>它挂在问答热路径上，而结果**绝大多数时候都是"没有库在重建"**。
 * 每问一次就查一次任务表是纯浪费；一个几秒的缓存把"批量重建期间的检索"
 * 从 N 次查询压到 1 次。
 *
 * <p>代价是**降级会迟到几秒**（重建开始后最多 TTL 才生效，结束后最多 TTL 才恢复）。
 * 这个代价是明知的、可接受的：晚几秒降级最多让几秒钟的检索用到正在换代的索引，
 * 而那正是"重建期检索质量下降"这句已写进需求（`06`:279）的已知行为。
 * 反过来说，**不能**为了"立即"而把它做成事件推送 —— 那要引入跨进程的失效通知，
 * 为一个几秒的窗口不值得。
 *
 * <p>TTL 做成可配（{@code app.retrieval.rebuild-cache-ms}）是为了用例：
 * 用例要在同一个方法里"建任务 → 立刻看到降级"，所以把它设成 0（每次都真查）。
 */
@Slf4j
@Service
public class RebuildStateService {

    /** 重建任务类型 —— 与 {@code ReindexService#TASK_TYPE} 同值。 */
    private static final String TASK_TYPE = "KB_REINDEX";

    @PersistenceContext
    private EntityManager entityManager;

    /** 缓存存活毫秒数；{@code 0} = 不缓存（每次都查库）。 */
    private final long cacheMillis;

    private volatile Set<Long> cached = Set.of();

    private volatile long cachedUntil = 0L;

    public RebuildStateService(@Value("${app.retrieval.rebuild-cache-ms:2000}") long cacheMillis) {
        this.cacheMillis = Math.max(0L, cacheMillis);
    }

    /**
     * 这些库里**有没有任何一个**正在重建。
     *
     * <p>「有任何一个」而不是"全部"：降级是一次检索整体的开关（{@code mode} 只有一个），
     * 而跨库检索里只要有一个库在换代，走向量路就会把那个库的旧代/半截内容混进来 ——
     * 所以宁可整次降级。这与 {@code RetrievalParams#conservative} 的"取最保守"同一条口径。
     */
    @Transactional(readOnly = true)
    public boolean isAnyRebuilding(Collection<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return false;
        }
        Set<Long> rebuilding = cachedRebuilding();
        for (Long kbId : kbIds) {
            if (rebuilding.contains(kbId)) {
                return true;
            }
        }
        return false;
    }

    private Set<Long> cachedRebuilding() {
        long now = System.currentTimeMillis();
        if (now < cachedUntil) {
            return cached;
        }
        Set<Long> fresh = queryRebuilding();
        // 先写数据再写有效期：反过来的话，读到新有效期却读到旧数据的窗口真实存在
        cached = fresh;
        cachedUntil = now + cacheMillis;
        return fresh;
    }

    /**
     * 在飞的重建任务对应的库。
     *
     * <p>{@code kb_id} 可能为 {@code NULL}（历史行 —— `T-709` 之前 {@code ReindexService#submit}
     * 没传业务坐标），那种行**认不出是哪个库**，只能不参与降级判断。
     * 那种行不会一直存在：重建任务几十分钟内必然走到终态。
     * ⚠️ 但这正是"归属维度不认识产物"那类缺陷（`plan/汇总.md` §3 #109）的形态 ——
     * 所以本服务**不**假装能处理它，只把它排除掉。
     */
    private Set<Long> queryRebuilding() {
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager.createNativeQuery("""
                        select kb_id from sys_async_task
                         where type = :type
                           and status in ('PENDING', 'RUNNING')
                           and kb_id is not null
                        """)
                .setParameter("type", TASK_TYPE)
                .getResultList();

        Set<Long> rebuilding = new HashSet<>(rows.size());
        for (Object row : rows) {
            rebuilding.add(((Number) row).longValue());
        }
        if (!rebuilding.isEmpty()) {
            log.info("[检索降级] 正在重建索引的知识库：{}", rebuilding);
        }
        return Set.copyOf(rebuilding);
    }
}
