package com.knowledge.ai.core.ops;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 页 20「检索质量分析」的**唯一数据访问点**（`T-A03`）。
 *
 * <h3>为什么所有窗口聚合都收在这一个类里</h3>
 * <p>与上一批的 {@link QaOpsRepository} 同一条纪律：`T-A04`（离线预计算）落地时，
 * 要换的是**数据源**（`sys_retrieval_trace` → 预计算表），不是五个端点的写法。
 * 聚合散在 Service 里的话，那一批得逐个方法去改，而漏掉的那一个**不会报错**
 * —— 它会继续实时扫明细，只是慢，而"慢"没有任何现象。
 *
 * <h3>🔴 三条口径写在这里，因为它们决定 SQL 长什么样</h3>
 * <ol>
 *   <li><b>可比集</b> = `rerank_applied = 1 AND top1_score IS NOT NULL`
 *       —— 只有这批行的分数与判定线**同量纲**（见 `V22` 的列注释与
 *       {@code RetrievalTraceWriter#top1} 的警告）。所有均值/比率都加这两个条件。</li>
 *   <li><b>分档边界是常量</b>（0.80 / 0.60 / 0.40 / 0.35，需求 §2.3），**不读行内的
 *       `miss_threshold`** —— 档位是需求定的口径，不是当时的配置快照。
 *       ⚠️ 行内的那条线另有用途（`miss_flag` 就是按它算的）。今天两者都是 0.35
 *       （全站一处），将来分档仍按常量。</li>
 *   <li><b>跨库行不拆</b>：`kb_ids` 是逗号串，拆开会把一次提问算进多个库
 *       （DDL 明写它「不做关联」）。只统计 `kb_count = 1` 的行。
 *       ⚠️ `kb_count = 2` 但两个 id 相同时（理论上不该有）也会落到「其他」——
 *       判据是**列**，不是内容。</li>
 * </ol>
 */
@Repository
public class RetrievalOpsRepository {

    /** 分档边界（需求 §2.3 的五个区间）。**从高到低**，最后一段是开区间。 */
    private static final double[] BOUNDS = {0.80, 0.60, 0.40, 0.35};

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 窗口汇总（一次查询喂齐 `overview` 与 `score-distribution` 的**分母**）。
     *
     * <p>合成一条 SQL 而不是拆成五条：它们的分母必须**同源** ——
     * 分五条查的话，两次查询之间新落一行打点就会让"合计"与"分档"对不上，
     * 而那是**偶发**的（跑批时几乎必现，手工点页面时几乎不现）。
     */
    public Window window(LocalDateTime from) {
        Object[] r = (Object[]) entityManager.createNativeQuery("""
                        select count(*),
                               sum(case when rerank_applied = 1 then 1 else 0 end),
                               sum(case when rerank_applied = 1 and top1_score is not null then 1 else 0 end),
                               sum(case when rerank_applied = 0 then 1 else 0 end),
                               sum(case when rerank_applied = 1 and top1_score is null then 1 else 0 end),
                               avg(case when rerank_applied = 1 then top1_score end),
                               sum(case when rerank_applied = 1 and top1_score is not null
                                         and top1_score >= miss_threshold then 1 else 0 end),
                               avg(chunk_count),
                               avg(case when rerank_applied = 1 then rerank_millis end)
                        from sys_retrieval_trace
                        where created_at >= :from
                        """)
                .setParameter("from", from)
                .getSingleResult();

        return new Window(lng(r[0]), lng(r[1]), lng(r[2]), lng(r[3]), lng(r[4]),
                dbl(r[5]), lng(r[6]), dbl(r[7]), dbl(r[8]));
    }

    /**
     * 重排前后（`rerank-effect`）。
     *
     * <p>🔴 **两个比率共用 {@code covered} 这一个分母**：只有同一批行上的两个比率相减，
     * 差值才是"重排带来的提升"。分母不同的话，差值里会混进覆盖率
     * （"有多少行本来就没有前值"），而页面上看不出来。
     */
    public BeforeAfter beforeAfter(LocalDateTime from) {
        Object[] r = (Object[]) entityManager.createNativeQuery("""
                        select count(*),
                               sum(case when rerank_before_top1 >= miss_threshold then 1 else 0 end),
                               sum(case when top1_score >= miss_threshold then 1 else 0 end)
                        from sys_retrieval_trace
                        where created_at >= :from
                          and rerank_applied = 1
                          and rerank_before_top1 is not null
                        """)
                .setParameter("from", from)
                .getSingleResult();
        return new BeforeAfter(lng(r[0]), lng(r[1]), lng(r[2]));
    }

    /**
     * 九段耗时 + 三段合计（`pipeline`）。
     *
     * <p>只算在**跑完全链路**的行上（`llm_generation_millis` 非空）：空召回不调模型，
     * "生成耗时"对它们**不存在**（`V22` 的列注释点名了这条）。
     * 三段合计必须是**同一个行集**上的平均，否则 `A + B + C == D` 这条判据是假的。
     *
     * <p>`otherMs` 是**独立算出来的残差**（`total − 检索侧 − 生成`），
     * 不是拿总数减出来的余额 —— 所以它不为 0 就真的说明有未归段的时间。
     */
    public Stages stages(LocalDateTime from) {
        String side = "query_analysis_millis + embed_millis + vector_search_millis"
                + " + bm25_recall_millis + fuse_millis + rerank_millis";
        Object[] r = (Object[]) entityManager.createNativeQuery("""
                        select avg(query_analysis_millis),
                               avg(embed_millis),
                               avg(vector_search_millis),
                               avg(bm25_recall_millis),
                               avg(fuse_millis),
                               avg(rerank_millis),
                               avg(scope_millis),
                               avg(table_millis),
                               avg(llm_generation_millis),
                               avg(fused_count),
                               count(*),
                               avg(""" + side + """
                               ),
                               avg(total_millis),
                               avg(total_millis - (""" + side + """
                               ) - llm_generation_millis)
                        from sys_retrieval_trace
                        where created_at >= :from and llm_generation_millis is not null
                        """)
                .setParameter("from", from)
                .getSingleResult();
        return new Stages(dbl(r[0]), dbl(r[1]), dbl(r[2]), dbl(r[3]), dbl(r[4]), dbl(r[5]),
                dbl(r[6]), dbl(r[7]), dbl(r[8]), dbl(r[9]), lng(r[10]),
                dbl(r[11]), dbl(r[12]), dbl(r[13]));
    }

    /** 五档次数（需求 §2.3 的区间）+ 两类"无法定档"的成因。 */
    public Buckets buckets(LocalDateTime from) {
        StringBuilder sql = new StringBuilder("select");
        // 区间半开：优秀 [0.80, 1.00]、良好 [0.60, 0.80]、一般 [0.40, 0.60]、边缘 [0.35, 0.40)、未命中 [0, 0.35)
        sql.append(" sum(case when rerank_applied = 1 and top1_score >= ").append(BOUNDS[0])
                .append(" then 1 else 0 end),");
        sql.append(" sum(case when rerank_applied = 1 and top1_score >= ").append(BOUNDS[1])
                .append(" and top1_score < ").append(BOUNDS[0]).append(" then 1 else 0 end),");
        sql.append(" sum(case when rerank_applied = 1 and top1_score >= ").append(BOUNDS[2])
                .append(" and top1_score < ").append(BOUNDS[1]).append(" then 1 else 0 end),");
        sql.append(" sum(case when rerank_applied = 1 and top1_score >= ").append(BOUNDS[3])
                .append(" and top1_score < ").append(BOUNDS[2]).append(" then 1 else 0 end),");
        sql.append(" sum(case when rerank_applied = 1 and top1_score < ").append(BOUNDS[3])
                .append(" then 1 else 0 end),");
        sql.append(" sum(case when rerank_applied = 0 then 1 else 0 end),");
        sql.append(" sum(case when rerank_applied = 1 and top1_score is null then 1 else 0 end),");
        sql.append(" count(*) from sys_retrieval_trace where created_at >= :from");

        Object[] r = (Object[]) entityManager.createNativeQuery(sql.toString())
                .setParameter("from", from)
                .getSingleResult();
        return new Buckets(lng(r[0]), lng(r[1]), lng(r[2]), lng(r[3]), lng(r[4]),
                lng(r[5]), lng(r[6]), lng(r[7]));
    }

    /**
     * 各库聚合（`kb-quality`）—— **只含 `kb_count = 1` 的行**（归属确定）。
     *
     * <p>走势要"前半 / 后半"两个窗口的未命中率，所以一条 SQL 用 `:mid` 把它们一起取回
     * （两次查询之间落一行就会让两半对不上，而那是偶发的）。
     */
    public List<KbAgg> singleKbAggregates(LocalDateTime from, LocalDateTime mid) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select kb_ids,
                               count(*),
                               sum(case when rerank_applied = 1 and top1_score is not null then 1 else 0 end),
                               avg(case when rerank_applied = 1 then top1_score end),
                               sum(miss_flag),
                               sum(case when created_at >= :mid
                                         and rerank_applied = 1 and top1_score is not null then 1 else 0 end),
                               sum(case when created_at >= :mid then miss_flag else 0 end),
                               sum(case when created_at < :mid
                                         and rerank_applied = 1 and top1_score is not null then 1 else 0 end),
                               sum(case when created_at < :mid then miss_flag else 0 end)
                        from sys_retrieval_trace
                        where created_at >= :from and kb_count = 1
                        group by kb_ids
                        """)
                .setParameter("from", from)
                .setParameter("mid", mid)
                .getResultList();

        List<KbAgg> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new KbAgg(String.valueOf(r[0]), lng(r[1]), lng(r[2]), dbl(r[3]), lng(r[4]),
                    lng(r[5]), lng(r[6]), lng(r[7]), lng(r[8])));
        }
        return out;
    }

    /** 跨库提问的行数（「其他」桶）。 */
    public long multiKbQuestions(LocalDateTime from) {
        Object n = entityManager.createNativeQuery("""
                        select count(*) from sys_retrieval_trace
                        where created_at >= :from and kb_count <> 1
                        """)
                .setParameter("from", from)
                .getSingleResult();
        return lng(n);
    }

    /** 库 id → 库名（含软删的：软删的也要有名字，否则"某一个库"与"其他"的账对不上）。 */
    public List<Object[]> kbNames(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select id, name, deleted_at from sys_kb where id in (:ids)")
                .setParameter("ids", ids)
                .getResultList();
        return rows;
    }

    // ==================== 行类型 ====================

    /**
     * @param applied    重排实际执行过的行数
     * @param comparable **可比集**（重排过且有 Top1）—— 所有比率/均值的分母
     * @param noRerank   未重排（分数是融合分，不同量纲）
     * @param emptyRecall 重排了但空召回（没有 Top1）
     */
    public record Window(long total, long applied, long comparable, long noRerank, long emptyRecall,
                         Double avgTop1, long hits, Double avgChunks, Double avgRerankMillis) {
    }

    /** @param covered 有"重排前"分数的行数；两个比率的**共同**分母 */
    public record BeforeAfter(long covered, long beforeHits, long afterHits) {
    }

    /** 九段均值 + 三段合计（都是 double 的原值，取整在 Service 做 —— 见那里的注释）。 */
    public record Stages(Double queryAnalysis, Double embed, Double vectorSearch, Double bm25,
                         Double fuse, Double rerank, Double scope, Double table, Double llm,
                         Double fusedCount, long rows, Double side, Double total, Double other) {
    }

    /** @param noRerank / @param emptyRecall "无法定档"的两个成因（分开回，见 DTO 注释） */
    public record Buckets(long excellent, long good, long fair, long marginal, long miss,
                          long noRerank, long emptyRecall, long total) {
    }

    /**
     * 一个库的聚合。
     *
     * @param h1Comparable / @param h1Miss 前半窗口的可比行数与未命中数（**没减半 = 各自独立算**，
     *                                     不写成"总数 − 后半"，那样一个半区错了会伪装成另一个半区错了）
     */
    public record KbAgg(String kbIds, long total, long comparable, Double avgTop1, long miss,
                        long h2Comparable, long h2Miss, long h1Comparable, long h1Miss) {
    }

    // ==================== 取值 ====================

    private static long lng(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static Double dbl(Object o) {
        if (o == null) {
            return null;
        }
        return o instanceof BigDecimal bd ? bd.doubleValue() : ((Number) o).doubleValue();
    }
}
