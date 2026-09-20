package com.knowledge.ai.core.ops;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    /**
     * 分档边界（需求 §2.3 的五个区间）。**从高到低**，最后一段是开区间。
     *
     * <p>⚠️ **包内可见**（不是 `private`）：`OPS` 的按天汇总表（`T-A04`）也要按同一组边界
     * 数五档 —— 两处各写一份的话，"实时算"与"读汇总表"会**漂移**，
     * 而那种漂移的现象是"某天之后各档合计对不上提问总数"，看起来像数据坏了。
     * 单一事实源在这里比封装重要。
     */
    static final double[] BOUNDS = {0.80, 0.60, 0.40, 0.35};

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

    /**
     * 窗口内**按库**的提问数 —— 读按天汇总表（`T-A04`），`kb_id = 0` 是「其他」桶。
     *
     * <h4>🔴 它同时服务页 20 与页 21，这不是"顺便"</h4>
     * <p>需求 `08` §3.4 逐字写着页 21 的库热度「**与 20 页的知识库提问数必须一致
     * —— 同一个数不要在两个页面各写一遍**」。把两个页面指到**同一张表的同一列**
     * （`sys_ops_retrieval_daily.total`）之后，那句话由**构造**保证：
     * 不存在"两处实现碰巧一致、改一处就漂"的可能。
     *
     * <p>⚠️ 这与 `singleKbAggregates`（实时、按 `kb_ids` 字符串分组）**必须给出同样的数**：
     * 前者的 `kb_count = 1` 判据与本表写入时的哨兵判据是**同一套**
     * （见 {@link OpsPrecomputeRepository#rebuildRetrievalDaily} 的派生表）。
     * 判据里有一条"读汇总表 == 实时算"专门钉住这件事。
     *
     * @return `[Long kbId, long questions]`，按提问数降序；含 `kb_id = 0` 那一行
     */
    @SuppressWarnings("unchecked")
    public List<Object[]> dailyKbTotals(LocalDate from, LocalDate to) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select d.kb_id, sum(d.total)
                        from sys_ops_retrieval_daily d
                        where d.stat_date >= :from and d.stat_date <= :to
                        group by d.kb_id
                        order by sum(d.total) desc
                        """)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        List<Object[]> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new Object[]{lng(r[0]), lng(r[1])});
        }
        return out;
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

    // ==================== 汇总表读法（`T-A04` · `G8-10`）====================
    //
    // 🔴 下面六个方法是上面六个**实时**查询的"读汇总表"版本。每一对**必须给出同样的数**，
    //   否则"离线预计算"这件事本身就成了一个静默的口径漂移来源。
    //   有一条判据逐条断言这件事（造一个已知的打点集合 → 补算 → 两条路对比，逐位相等）。
    //
    // ⚠️ 三处**故意的**差异，都不是漂移：
    //   ① 时间谓词从 `created_at >= :from` 变成 `stat_date between :from and :to`
    //      —— 二者在**日历天口径**下是同一批行（`TimeWindow.fromDate` 的注释写了这条）；
    //   ② `multiKbQuestions` 的实时版只抓 `kb_count <> 1`，而哨兵 0 还含
    //      "`kb_count = 1` 但 `kb_ids` 不是单个数字"那种行 ⇒ 汇总版是**超集**
    //      （那类行在库里实际不存在，`kb_ids` 恒是数字串）；
    //   ③ `h1/h2` 的分界从 `created_at >= :mid` 变成 `stat_date >= :midDate`
    //      —— 同理，`mid` 是**天边界**。

    /** `window` 的汇总表版。 */
    public Window dailyWindow(LocalDate from, LocalDate to) {
        Object[] r = (Object[]) entityManager.createNativeQuery("""
                        select coalesce(sum(total), 0),
                               coalesce(sum(comparable), 0) + coalesce(sum(empty_recall), 0),
                               coalesce(sum(comparable), 0),
                               coalesce(sum(no_rerank), 0),
                               coalesce(sum(empty_recall), 0),
                               sum(top1_sum) / nullif(sum(top1_count), 0),
                               coalesce(sum(hits), 0),
                               sum(chunk_sum) / nullif(sum(total), 0),
                               sum(applied_rerank_ms_sum)
                                   / nullif(coalesce(sum(comparable), 0)
                                            + coalesce(sum(empty_recall), 0), 0)
                        from sys_ops_retrieval_daily
                        where stat_date >= :from and stat_date <= :to
                        """)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        return new Window(lng(r[0]), lng(r[1]), lng(r[2]), lng(r[3]), lng(r[4]),
                dbl(r[5]), lng(r[6]), dbl(r[7]), dbl(r[8]));
    }

    /** `beforeAfter` 的汇总表版。 */
    public BeforeAfter dailyBeforeAfter(LocalDate from, LocalDate to) {
        Object[] r = (Object[]) entityManager.createNativeQuery("""
                        select coalesce(sum(covered), 0),
                               coalesce(sum(before_hits), 0),
                               coalesce(sum(after_hits), 0)
                        from sys_ops_retrieval_daily
                        where stat_date >= :from and stat_date <= :to
                        """)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        return new BeforeAfter(lng(r[0]), lng(r[1]), lng(r[2]));
    }

    /**
     * `stages` 的汇总表版。
     *
     * <p>分母一律是 `sum(full_rows)`（"跑完全链路"的行数），与实时版
     * `where llm_generation_millis is not null` 同一行集 —— 这是
     * `检索侧 + 生成 + 其他 == 全链路` 那条判据能成立的前提。
     * ⚠️ `otherMs` 是**残差**（总数 − 六段 − 生成），与实时版同一个算法。
     */
    public Stages dailyStages(LocalDate from, LocalDate to) {
        String sums = "sum(query_ms_sum) + sum(embed_ms_sum) + sum(vector_ms_sum)"
                + " + sum(bm25_ms_sum) + sum(fuse_ms_sum) + sum(rerank_ms_sum)";
        Object[] r = (Object[]) entityManager.createNativeQuery("""
                        select sum(query_ms_sum) / nullif(sum(full_rows), 0),
                               sum(embed_ms_sum) / nullif(sum(full_rows), 0),
                               sum(vector_ms_sum) / nullif(sum(full_rows), 0),
                               sum(bm25_ms_sum) / nullif(sum(full_rows), 0),
                               sum(fuse_ms_sum) / nullif(sum(full_rows), 0),
                               sum(rerank_ms_sum) / nullif(sum(full_rows), 0),
                               sum(scope_ms_sum) / nullif(sum(full_rows), 0),
                               sum(table_ms_sum) / nullif(sum(full_rows), 0),
                               sum(llm_ms_sum) / nullif(sum(full_rows), 0),
                               sum(fused_sum) / nullif(sum(full_rows), 0),
                               coalesce(sum(full_rows), 0),
                               (""" + sums + """
                               ) / nullif(sum(full_rows), 0),
                               sum(total_ms_sum) / nullif(sum(full_rows), 0),
                               (sum(total_ms_sum) - (""" + sums + """
                               ) - sum(llm_ms_sum)) / nullif(sum(full_rows), 0)
                        from sys_ops_retrieval_daily
                        where stat_date >= :from and stat_date <= :to
                        """)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        return new Stages(dbl(r[0]), dbl(r[1]), dbl(r[2]), dbl(r[3]), dbl(r[4]), dbl(r[5]),
                dbl(r[6]), dbl(r[7]), dbl(r[8]), dbl(r[9]), lng(r[10]),
                dbl(r[11]), dbl(r[12]), dbl(r[13]));
    }

    /** `buckets` 的汇总表版（五档边界用的是行自己的判定线，见 `V36` 的列注释）。 */
    public Buckets dailyBuckets(LocalDate from, LocalDate to) {
        Object[] r = (Object[]) entityManager.createNativeQuery("""
                        select coalesce(sum(b_excellent), 0),
                               coalesce(sum(b_good), 0),
                               coalesce(sum(b_fair), 0),
                               coalesce(sum(b_marginal), 0),
                               coalesce(sum(b_miss), 0),
                               coalesce(sum(no_rerank), 0),
                               coalesce(sum(empty_recall), 0),
                               coalesce(sum(total), 0)
                        from sys_ops_retrieval_daily
                        where stat_date >= :from and stat_date <= :to
                        """)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        return new Buckets(lng(r[0]), lng(r[1]), lng(r[2]), lng(r[3]), lng(r[4]),
                lng(r[5]), lng(r[6]), lng(r[7]));
    }

    /**
     * `multiKbQuestions` 的汇总表版 —— 哨兵 `kb_id = 0` 那一桶。
     *
     * <p>⚠️ 与实时版有**一处已知的超集关系**：实时版抓 `kb_count &lt;&gt; 1`，
     * 而哨兵 0 还包含"`kb_count = 1` 但 `kb_ids` 不是单个数字"那种行
     * （见 `OpsPrecomputeRepository#rebuildRetrievalDaily` 的派生表）。
     * 那类行在实际库里不存在（`kb_ids` 恒是数字串），
     * 真出现了也是**该归「其他」**而不是该混进某个库 —— 故取超集是更保守的一侧。
     */
    public long dailyMultiKbQuestions(LocalDate from, LocalDate to) {
        Object n = entityManager.createNativeQuery("""
                        select coalesce(sum(total), 0) from sys_ops_retrieval_daily
                        where stat_date >= :from and stat_date <= :to and kb_id = 0
                        """)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        return lng(n);
    }

    /** `singleKbAggregates` 的汇总表版（只取 `kb_id > 0` 的确定归属行）。 */
    @SuppressWarnings("unchecked")
    public List<KbAgg> dailySingleKbAggregates(LocalDate from, LocalDate midDate, LocalDate to) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select cast(kb_id as char),
                               coalesce(sum(total), 0),
                               coalesce(sum(comparable), 0),
                               sum(top1_sum) / nullif(sum(top1_count), 0),
                               coalesce(sum(miss_count), 0),
                               coalesce(sum(case when stat_date >= :mid then comparable else 0 end), 0),
                               coalesce(sum(case when stat_date >= :mid then miss_count else 0 end), 0),
                               coalesce(sum(case when stat_date < :mid then comparable else 0 end), 0),
                               coalesce(sum(case when stat_date < :mid then miss_count else 0 end), 0)
                        from sys_ops_retrieval_daily
                        where stat_date >= :from and stat_date <= :to and kb_id > 0
                        group by kb_id
                        """)
                .setParameter("from", from)
                .setParameter("mid", midDate)
                .setParameter("to", to)
                .getResultList();

        List<KbAgg> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new KbAgg(String.valueOf(r[0]), lng(r[1]), lng(r[2]), dbl(r[3]), lng(r[4]),
                    lng(r[5]), lng(r[6]), lng(r[7]), lng(r[8])));
        }
        return out;
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
