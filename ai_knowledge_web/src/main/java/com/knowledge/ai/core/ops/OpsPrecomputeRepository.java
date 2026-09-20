package com.knowledge.ai.core.ops;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 按天汇总表的**重算**（`T-A04` · `G8-10`）—— 写侧。
 *
 * <h3>幂等：先删该天、再插该天</h3>
 * <p>「同一件事做两遍，结果完全相同」—— 这正是 `S8-M10-B` 定的那条幂等口径
 * （**无累积副作用**，不是"逐位可复现"）。具体到这里：{@code delete where stat_date = ?}
 * 之后再 {@code insert ... select}，跑一次与跑十次留下的行**一模一样**。
 * ⚠️ **不能**用「有行就跳过」代替：那会让"打点在算完之后又多了一行"永远进不了汇总表，
 * 而现象是"今天的数偏小"—— 每个页面都自洽，只有跟打点表对不上。
 *
 * <h3>🔴 为什么这一层的 SQL 必须与实时查询**逐条对齐**</h3>
 * <p>汇总表是**缓存**，`sys_retrieval_trace` 才是事实。两者的口径一旦漂移，
 * 页面的数会**整体偏移**而没有任何现象 —— 盯不出来。所以这里每一个
 * {@code case when} 都是从 {@link RetrievalOpsRepository} 的对应查询**逐字搬过来的**，
 * 并且有一条判据专门断言"读汇总表 == 实时算"（逐位相等）。
 *
 * <p>三处刻意对齐的细节，都是踩过或差点踩到的：
 * <ol>
 *   <li><b>耗时的九个 {@code *sum} 只在"跑完全链路"的行上求和</b>
 *       （{@code llm_generation_millis is not null}）—— `stages()` 的 WHERE 就是它。
 *       若在全部行上求和再除全部行数，「检索侧 + 生成 + 其他 == 全链路」会**假成立**
 *       （三项的分子来自不同行集，差值里混进空召回的比例）。</li>
 *   <li><b>{@code hits} / {@code before_hits} / {@code after_hits} 用的是行自己的
 *       {@code miss_threshold}</b>（`V22` 把它冗余在每一行上），不是当前的全局配置 ——
 *       否则"改一次判定线，历史各档分布全变"。</li>
 *   <li><b>五档用 {@link RetrievalOpsRepository#BOUNDS}</b>（同一组常量），
 *       不是另抄一份数字。</li>
 * </ol>
 *
 * <h3>哨兵值</h3>
 * <p>{@code kb_id = 0} / {@code dept_id = 0} / {@code user_id = 0} 都表示"无归属"，
 * 与 `V36` 的 DDL 注释同一口径 —— 可空列进不了唯一键，而"无归属"必须能与"有归属"分开计数。
 */
@Slf4j
@Component
public class OpsPrecomputeRepository {

    @PersistenceContext
    private EntityManager entityManager;

    // ==================== 检索质量（页 20 五个端点 + 页 21 by-kb）====================

    /** 删掉某天的检索质量汇总行。 */
    @Transactional
    public int deleteRetrievalDaily(LocalDate day) {
        return entityManager.createNativeQuery(
                        "delete from sys_ops_retrieval_daily where stat_date = :d")
                .setParameter("d", day)
                .executeUpdate();
    }

    /**
     * 重算某天的检索质量汇总（按 `kb_id` 一行）。
     *
     * @return 写入的行数（0 = 那天没有打点，这是**正常**的：没有提问就没有汇总行）
     */
    @Transactional
    public int rebuildRetrievalDaily(LocalDate day) {
        double[] b = RetrievalOpsRepository.BOUNDS;
        // ⚠️ 那些 b_* 的 case 表达式是从 RetrievalOpsRepository#buckets **逐字**搬来的
        //   （区间半开：[0.80,1.00] / [0.60,0.80) / [0.40,0.60) / [0.35,0.40) / [0,0.35)）
        String sql = """
                insert into sys_ops_retrieval_daily
                  (stat_date, kb_id, total, comparable, no_rerank, empty_recall,
                   top1_sum, top1_count, miss_count, hits, covered, before_hits, after_hits,
                   b_excellent, b_good, b_fair, b_marginal, b_miss, chunk_sum,
                   full_rows, scope_ms_sum, query_ms_sum, embed_ms_sum, vector_ms_sum,
                   bm25_ms_sum, fuse_ms_sum, rerank_ms_sum, table_ms_sum,
                   llm_ms_sum, fused_sum, total_ms_sum, applied_rerank_ms_sum)
                select :d, s.kb_id,
                       count(*),
                       sum(case when s.rerank_applied = 1 and s.top1_score is not null then 1 else 0 end),
                       sum(case when s.rerank_applied = 0 then 1 else 0 end),
                       sum(case when s.rerank_applied = 1 and s.top1_score is null then 1 else 0 end),
                       coalesce(sum(case when s.rerank_applied = 1 then s.top1_score end), 0),
                       sum(case when s.rerank_applied = 1 and s.top1_score is not null then 1 else 0 end),
                       sum(s.miss_flag),
                       sum(case when s.rerank_applied = 1 and s.top1_score is not null
                                 and s.top1_score >= s.miss_threshold then 1 else 0 end),
                       sum(case when s.rerank_applied = 1 and s.rerank_before_top1 is not null then 1 else 0 end),
                       sum(case when s.rerank_applied = 1 and s.rerank_before_top1 is not null
                                 and s.rerank_before_top1 >= s.miss_threshold then 1 else 0 end),
                       sum(case when s.rerank_applied = 1 and s.rerank_before_top1 is not null
                                 and s.top1_score >= s.miss_threshold then 1 else 0 end),
                       sum(case when s.rerank_applied = 1 and s.top1_score >= :b0 then 1 else 0 end),
                       sum(case when s.rerank_applied = 1 and s.top1_score >= :b1
                                 and s.top1_score < :b0 then 1 else 0 end),
                       sum(case when s.rerank_applied = 1 and s.top1_score >= :b2
                                 and s.top1_score < :b1 then 1 else 0 end),
                       sum(case when s.rerank_applied = 1 and s.top1_score >= :b3
                                 and s.top1_score < :b2 then 1 else 0 end),
                       sum(case when s.rerank_applied = 1 and s.top1_score < :b3 then 1 else 0 end),
                       sum(s.chunk_count),
                       sum(case when s.llm_generation_millis is not null then 1 else 0 end),
                       coalesce(sum(case when s.llm_generation_millis is not null then s.scope_millis end), 0),
                       coalesce(sum(case when s.llm_generation_millis is not null
                                         then s.query_analysis_millis end), 0),
                       coalesce(sum(case when s.llm_generation_millis is not null then s.embed_millis end), 0),
                       coalesce(sum(case when s.llm_generation_millis is not null
                                         then s.vector_search_millis end), 0),
                       coalesce(sum(case when s.llm_generation_millis is not null
                                         then s.bm25_recall_millis end), 0),
                       coalesce(sum(case when s.llm_generation_millis is not null then s.fuse_millis end), 0),
                       coalesce(sum(case when s.llm_generation_millis is not null then s.rerank_millis end), 0),
                       coalesce(sum(case when s.llm_generation_millis is not null then s.table_millis end), 0),
                       coalesce(sum(s.llm_generation_millis), 0),
                       coalesce(sum(case when s.llm_generation_millis is not null
                                         then s.fused_count end), 0),
                       coalesce(sum(case when s.llm_generation_millis is not null
                                         then s.total_millis end), 0),
                       -- ⚠️ 行集是「重排**执行过**」，不是上面那一组的「跑完全链路」
                       --   —— 见 V36 的列注释：它是 rerank-effect 的 avgLatencyMs 的唯一来源
                       coalesce(sum(case when s.rerank_applied = 1 then s.rerank_millis end), 0)
                from (
                    select case when kb_count = 1 and trim(kb_ids) regexp '^[0-9]+$'
                                then cast(trim(kb_ids) as unsigned) else 0 end as kb_id,
                           t.*
                    from sys_retrieval_trace t
                    where t.created_at >= :ds and t.created_at < :de
                ) s
                group by s.kb_id
                """;

        return entityManager.createNativeQuery(sql)
                .setParameter("d", day)
                .setParameter("ds", day.atStartOfDay())
                .setParameter("de", day.plusDays(1).atStartOfDay())
                // 边界用**命名参数**传，不拼字符串：拼进去的 SQL 每次执行都是新语句，
                // Hibernate 的查询计划缓存会逐条堆积（窗口只有 7/30/90 三种，但边界是 4 个浮点）
                .setParameter("b0", b[0])
                .setParameter("b1", b[1])
                .setParameter("b2", b[2])
                .setParameter("b3", b[3])
                .executeUpdate();
    }

    // ==================== 使用统计（页 21 overview/trend/by-dept/top-users）====================

    /** 删掉某天的使用统计汇总行。 */
    @Transactional
    public int deleteUsageDaily(LocalDate day) {
        return entityManager.createNativeQuery(
                        "delete from sys_ops_usage_daily where stat_date = :d")
                .setParameter("d", day)
                .executeUpdate();
    }

    /**
     * 重算某天的使用统计汇总（按 `dept_id × user_id` 一行）。
     *
     * <p>⚠️ `top1_sum` / `top1_count` **只算可比集**（`rerank_applied = 1`）——
     * 与页 20 的 `avgTop1` 同一口径。未重排那一行的 `top1_score` 是**融合分**
     * （RRF 量级 ≈0.016），把它混进来会让"平均 Top1"变成一个**没有意义的数**
     * （`V35` 的头注把这条写死过：「存融合分与判定线**不同量纲**，那个差值就是假数」）。
     * ⚠️ 而 `questions` 数的是**全部**行（提问次数与"有没有重排"无关）。
     */
    @Transactional
    public int rebuildUsageDaily(LocalDate day) {
        String usageSql = """
                        insert into sys_ops_usage_daily
                          (stat_date, dept_id, user_id, questions, top1_sum, top1_count)
                        select :d,
                               coalesce(t.dept_id, 0),
                               coalesce(t.user_id, 0),
                               count(*),
                               coalesce(sum(case when t.rerank_applied = 1 then t.top1_score end), 0),
                               sum(case when t.rerank_applied = 1 and t.top1_score is not null
                                        then 1 else 0 end)
                        from sys_retrieval_trace t
                        where t.created_at >= :ds and t.created_at < :de
                        group by coalesce(t.dept_id, 0), coalesce(t.user_id, 0)
                        """;
        return entityManager.createNativeQuery(usageSql)
                .setParameter("d", day)
                .setParameter("ds", day.atStartOfDay())
                .setParameter("de", day.plusDays(1).atStartOfDay())
                .executeUpdate();
    }

    // ==================== 覆盖判定 ====================

    /**
     * 汇总表覆盖到哪一天了（`min` 与 `max` 一起给）。
     *
     * <p>读侧靠它判"这个窗口算过了没有"：{@code min <= from 且 max == 今天} 才算覆盖。
     * ⚠️ 只看 {@code max} 是不够的：一张只有今天一行的表会让"近 30 天"窗口
     * 拿 1 天的数据冒充 30 天，而页面上的数**看着完全正常**（只是偏小）。
     *
     * @return `[minDate, maxDate]`；表为空时返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    public LocalDate[] retrievalCoverage() {
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select min(stat_date), max(stat_date) from sys_ops_retrieval_daily")
                .getResultList();
        return bounds(rows);
    }

    /** 同上，使用统计表。 */
    @SuppressWarnings("unchecked")
    public LocalDate[] usageCoverage() {
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select min(stat_date), max(stat_date) from sys_ops_usage_daily")
                .getResultList();
        return bounds(rows);
    }

    /** 打点表里最早的那一天（= 补算历史的下界）；表为空回 {@code null}。 */
    public LocalDate earliestTraceDate() {
        Object v = entityManager.createNativeQuery(
                        "select min(date(created_at)) from sys_retrieval_trace")
                .getSingleResult();
        if (v == null) {
            return null;
        }
        return v instanceof java.sql.Date d ? d.toLocalDate() : LocalDate.parse(String.valueOf(v));
    }

    private static LocalDate[] bounds(List<Object[]> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        Object[] r = rows.get(0);
        if (r[0] == null || r[1] == null) {
            return null;
        }
        return new LocalDate[]{toDate(r[0]), toDate(r[1])};
    }

    private static LocalDate toDate(Object o) {
        if (o instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (o instanceof LocalDate d) {
            return d;
        }
        if (o instanceof LocalDateTime dt) {
            return dt.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(o));
    }
}
