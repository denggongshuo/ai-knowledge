package com.knowledge.ai.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库的**真实**文档数 / 分片数 —— 全系统**唯一**的计数口径（2026-10-12）。
 *
 * <h3>它存在的理由：{@code sys_kb.doc_count / chunk_count} 是死列</h3>
 * <p>那两列是 `V7__s2_kb_skeleton_and_grants.sql:28` 建表时 seed 的**示例常量**
 * （128 / 42 / 316 … 文档合计 1246；分片合计 38502），而**全仓没有任何代码写过它们**：
 * 建库那次写死 `0, 0`（`KbService:314`）之后，上传 / 删除 / 重建 / 移库**一条都不更新**
 * （`setDocCount` 全仓零调用；唯一碰它们的是测试夹具手工 `+1`）。
 *
 * <p>于是读它等于回答一个**永远不变**的假数：库下 0 篇文档时它说「128 篇」，而库内文档
 * 列表（{@code DocService#page}）回答的是 0 —— 这正是 `plan/2026-09-16-S4-M05-A.md`
 * §1 W3 登记过、2026-10-12 被用户以「数据和真实情况对不上」重新翻出来的那个"双答案"。
 *
 * <h3>为什么必须收敛到一处</h3>
 * <p>五个消费方（知识库列表 / 知识库设置页 / 重建影响预估 / 授权页 / 配额卡）过去各读各的
 * 冗余列。只修其中几个，就会从"库内双答案"变成"**跨页双答案**"（列表说 0、设置页说 128）
 * —— 同样是查不出来的静默不一致。所以计数只在**这里**算，谁要谁调。
 *
 * <h3>口径（与 {@code DocService#page} 同源）</h3>
 * <ul>
 *   <li>只数 {@code sys_document.deleted_at is null} 的行（软删进回收站的不算）；</li>
 *   <li>分片数取各文档自己的 {@code chunk_count} 之和 —— 那是解析产物回填的**顶层块**数
 *       （`DocRecordWriter:707`，不含表格子块），与文档列表里那一列同名同义。</li>
 * </ul>
 *
 * <p>不选"给冗余列补写侧"（上传 +1 / 删除 -1）那条路：它要覆盖上传、删除、软删、恢复、
 * 重建、移库**每一条**路径，漏一条就是新的静默漂移；现算没有这个问题。
 */
@Component
public class KbCountReader {

    @PersistenceContext
    private EntityManager entityManager;

    /** 一个库的计数。**没有文档就是 {@code (0, 0)}**，不是"未知"。 */
    public record Counts(long docCount, long chunkCount) {

        public static final Counts ZERO = new Counts(0L, 0L);
    }

    /**
     * 一批库的计数。
     *
     * <p>⚠️ **没有文档的库不会出现在返回的 map 里** —— 调用方用
     * {@code getOrDefault(kbId, Counts.ZERO)} 兜。这不是疏漏：一次聚合注定只回有数据的行，
     * 而"库里一篇都没有"与"没查到这个库"在这里是同一件事（都给 0）。
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<Long, Counts> countsOf(Collection<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return Map.of();
        }
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select d.kb_id, count(*), coalesce(sum(d.chunk_count), 0)
                        from sys_document d
                        where d.deleted_at is null and d.kb_id in (:kbIds)
                        group by d.kb_id
                        """)
                .setParameter("kbIds", kbIds)
                .getResultList();
        Map<Long, Counts> out = new HashMap<>(rows.size());
        for (Object[] row : rows) {
            out.put(((Number) row[0]).longValue(), new Counts(
                    ((Number) row[1]).longValue(), ((Number) row[2]).longValue()));
        }
        return out;
    }

    /** 单库计数（没有文档时 {@link Counts#ZERO}）。 */
    public Counts countOf(long kbId) {
        return countsOf(List.of(kbId)).getOrDefault(kbId, Counts.ZERO);
    }

    /**
     * 全站合计（`storageQuota` 用）—— **只算未软删的库**。
     *
     * <p>为什么要 join 库：软删的库其文档行**还在**（恢复要用，`T-407` 明确"向量与文档保留"）。
     * 把它们算进配额就是"列表里看不到、配额里却占着" —— 与 `T-407` 那句
     * 「用户看到的用量应与列表里的库对得上」正好相反。
     */
    @Transactional(readOnly = true)
    public Counts totalsOfActiveKbs() {
        Object[] row = (Object[]) entityManager.createNativeQuery("""
                        select count(*), coalesce(sum(d.chunk_count), 0)
                        from sys_document d
                        join sys_kb k on k.id = d.kb_id
                        where d.deleted_at is null and k.deleted_at is null
                        """).getSingleResult();
        return new Counts(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
    }
}
