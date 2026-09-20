package com.knowledge.ai.core.ops;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 问答运营的**窗口聚合**（`T-A02`）—— 高频问题榜与点踩归因的唯一取数点。
 *
 * <h3>为什么两个完全不同的指标放在一个类里</h3>
 * <p>它们不是同一件事，但**共用一个窗口口径**（`from` 怎么算、按哪一列筛）。
 * 把窗口各自算一遍的后果是"两个卡片的 7 天不是同一个 7 天"，而那种漂移**没有任何现象** ——
 * 页面上的两个数字各自都对。这与 `QaMissRepository#filter` 把列表三件收在一处
 * （免得"列表筛了、计数没筛"）是同一条纪律。
 *
 * <h3>🔴 这里是"实时扫明细"，而它是一笔已知的债（`G8-10`）</h3>
 * <p>需求《11》§4.3 要求「统计指标**离线预计算**，不实时扫明细」。本批**刻意不做**
 * （用户 2026-09-20 裁定「不并入，按排期办」），理由写在
 * `plan/2026-09-20-S8-M10-C.md` §1 结论：统计表的**粒度**该由页 20/21 的指标定
 * （`各库排行`=按库 · `Top1 五档`=按档 · `6 阶段耗时`=按阶段），而页 11 只有
 * 「按天计数」一种 ⇒ 现在建表是**用一页的需求猜三页的形状**。
 * <p>⇒ **补偿动作**：窗口怎么聚合成那三个数**只写在本类**（含归一化合并与分母口径），
 * `T-A04` 落地时只改这一处（把数据源从 `sys_retrieval_trace` 换成预计算表），
 * 服务层与控制器不动。**这是本批不做预计算的唯一代价对冲，别把它拆散。**
 *
 * <h3>⚠️ 聚合分两段做，不是一段</h3>
 * <ol>
 *   <li>**SQL 里按原始问法 `group by`** —— 把"完全同字的问法"先塌掉。这一步能把
 *       明细行数从"提问次数"降到"不同写法的个数"，是内存的保险；</li>
 *   <li>**Java 里按 `MissKeyNormalizer.keyOf` 再合并** —— NFKC + 正则 + 小写，
 *       MySQL 表达不了。⚠️ 用 `UPPER(TRIM(...))` 之类去近似会**错合并/漏合并**，
 *       与本仓 `MissKeyNormalizerTest` 里那张逐刀理由表冲突。</li>
 * </ol>
 * <p>⚠️ 第二段只合并「同一问法的不同写法」（`怎么配？` ≡ `怎么配`），
 * **不合并「意思一样但问法不同」** —— 那是 `R-6` 的语义聚合，
 * 阈值无语料可校准、用户 2026-09-20 已裁定挂起（`G8-2` 的挂起半边）。
 * 页面上写着「次数」的地方都要按这个口径读。
 */
@Repository
@RequiredArgsConstructor
public class QaOpsRepository {

    /** 榜单最多取多少行（与 `QaMissService` 的分页上限同一个数）。 */
    public static final int MAX_LIMIT = 100;

    private final EntityManager entityManager;

    // ==================== 高频问题榜（需求 §1.5）====================

    /** SQL 里按**原始问法**预聚合的一行。 */
    public record RawQuestionRow(String question, long asks, long scored, Double avgTop1,
                                 LocalDateTime lastAt) {
    }

    /**
     * 高频问题榜的一行。
     *
     * @param question    该组**最近一次**的原始问法（需求 §1.10「保持原始问法，未被改写」）
     * @param count       组内**提问次数**
     * @param avgTopScore 组内**有分数行**的均值；⚠️ 一条都没拿到过分时为 {@code null}
     *                    ——「还没有一条拿到过分」不能用 0 顶替（0 会被读成"召回质量极差"）
     * @param lastAt      组内最近一次提问时刻
     */
    public record TopQuestionRow(String question, long count, Double avgTopScore,
                                 LocalDateTime lastAt) {
    }

    /**
     * 榜单 + 组数。
     *
     * <p>{@code totalGroups} 是**合并之后**的组数，与 {@code rows.size()} 不是一回事：
     * 榜单只回前 N 行，而"总共几组"要如实说（前端拿它显示总数）。
     * ⚠️ 两者一起返回而**不是**让调用方"再查一次算组数" —— 那会**再跑一遍同一个窗口聚合**，
     * 两次之间数据一变就自相矛盾。
     */
    public record TopQuestions(List<TopQuestionRow> rows, int totalGroups) {
    }

    /**
     * 窗口内的高频问题榜。
     *
     * @param limit 取前几行；**在合并之后**截断（否则"排第 3 的问法"会被"排第 1 的另一种写法"
     *              挤掉，而两者本该合成一行）
     * @return {@code rows} 按 {@code count desc, lastAt desc, question asc} 排序；
     *         ⚠️ 最后那个 `question` 是为**确定性**加的 —— 次数与时刻都相同时，
     *         Map 的迭代顺序不该决定榜单顺序（用例会逐条比对，不稳定等于靠运气绿）
     */
    public TopQuestions topQuestions(LocalDateTime from, int limit) {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (RawQuestionRow row : rawQuestionRows(from)) {
            Group group = groups.computeIfAbsent(MissKeyNormalizer.keyOf(row.question()), k -> new Group());
            group.count += row.asks();
            if (row.avgTop1() != null && row.scored() > 0) {
                group.scored += row.scored();
                group.scoreSum += row.avgTop1() * row.scored();
            }
            // 展示用的是**最近一次**的原始问法：同一问法有好几种写法时，
            // 取最近那条最贴近"用户现在怎么问"（需求 §1.5 的反向用途是校准 HyQ 语料）
            if (group.lastAt == null || row.lastAt().isAfter(group.lastAt)) {
                group.lastAt = row.lastAt();
                group.question = row.question();
            }
        }

        List<TopQuestionRow> rows = new ArrayList<>(groups.size());
        for (Group g : groups.values()) {
            rows.add(new TopQuestionRow(g.question, g.count,
                    g.scored == 0 ? null : g.scoreSum / g.scored, g.lastAt));
        }
        rows.sort(Comparator.comparingLong(TopQuestionRow::count).reversed()
                .thenComparing(TopQuestionRow::lastAt, Comparator.reverseOrder())
                .thenComparing(TopQuestionRow::question));

        int totalGroups = rows.size();
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        return new TopQuestions(rows.size() <= capped ? rows : new ArrayList<>(rows.subList(0, capped)),
                totalGroups);
    }

    /**
     * SQL 侧预聚合。
     *
     * <p>⚠️ `count(top1_score)` 与 `count(*)` 是**两个不同的数**，缺一不可：
     * 前者是均值真正的分母（空召回行的 `top1_score` 为 `NULL`），后者是"提问次数"。
     * 只取 `avg(top1_score)` 再乘 `count(*)` 会**把没有分数的行也算进分母**，
     * 而那个错误的表现是"平均相似度偏低"—— 看起来像检索质量差，不像 SQL 写错了。
     */
    private List<RawQuestionRow> rawQuestionRows(LocalDateTime from) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select query_text,
                               count(*),
                               count(top1_score),
                               avg(top1_score),
                               max(created_at)
                        from sys_retrieval_trace
                        where created_at >= :from
                        group by query_text
                        """)
                .setParameter("from", from)
                .getResultList();
        List<RawQuestionRow> result = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            result.add(new RawQuestionRow(
                    (String) r[0],
                    ((Number) r[1]).longValue(),
                    ((Number) r[2]).longValue(),
                    r[3] == null ? null : ((Number) r[3]).doubleValue(),
                    (LocalDateTime) r[4]));
        }
        return result;
    }

    // ==================== 点踩归因（需求 §1.6）====================

    /**
     * 点踩归因的三个数。
     *
     * <p>🔴 **{@code unlikeTotal} 与 {@code byReason} 来自两条独立的查询，不是"加起来"**
     * —— 见 {@link #feedbackCounts} 的注释。
     *
     * @param unlikeTotal 窗口内 `feedback = 'unlike'` 的**总行数**（含没填原因的）
     * @param unreasoned  其中 `feedback_reason` 为空的行数（点踩时原因**是可选的**）
     * @param byReason    原因 → 条数（只含非空的）；⚠️ 可能是**闭集之外**的值，
     *                    见 {@link com.knowledge.ai.core.enums.ChatFeedbackReason}
     */
    public record FeedbackCounts(long unlikeTotal, long unreasoned, Map<String, Long> byReason) {
    }

    /**
     * 🔴 **两条独立查询，不是一个数减出来**。
     *
     * <p>`unlikeTotal` 走 `count(*)`，`byReason` 走 `group by`。**刻意不把总数写成
     * `sum(byReason) + unreasoned`** —— 那样写的话，"两者相等"就成了**结构上恒真**的
     * 事实，判据会变成**空断言**（本仓在 `S8-M10-B` 被这个形状咬过一次：
     * `hasSize(1)` 与"未命中那项不该存在"在池子为空时恒绿）。
     * 两条独立的观测点才能问出真问题："`group by` 有没有丢行？"
     * <p>⚠️ 会丢行的真实情形只有一个：库里出现了**闭集之外**的原因值（老数据或被绕过白名单的直写）。
     * 服务层为此**不静默丢** —— 见 `QaOpsService#feedbackStats` 的告警与「未知原因也如实列出」。
     */
    public FeedbackCounts feedbackCounts(LocalDateTime from) {
        Object total = entityManager.createNativeQuery("""
                        select count(*) from sys_chat_message
                        where feedback = 'unlike' and feedback_at >= :from
                        """)
                .setParameter("from", from)
                .getSingleResult();
        long unlikeTotal = total == null ? 0L : ((Number) total).longValue();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select feedback_reason, count(*) from sys_chat_message
                        where feedback = 'unlike' and feedback_at >= :from
                        group by feedback_reason
                        """)
                .setParameter("from", from)
                .getResultList();

        Map<String, Long> byReason = new LinkedHashMap<>();
        long unreasoned = 0;
        for (Object[] r : rows) {
            String reason = (String) r[0];
            long count = ((Number) r[1]).longValue();
            if (reason == null || reason.isBlank()) {
                unreasoned += count;
            } else {
                byReason.merge(reason, count, Long::sum);
            }
        }
        return new FeedbackCounts(unlikeTotal, unreasoned, byReason);
    }

    // ==================== 内部 ====================

    /** 归一化合并的累加器 —— 可变的，只在 {@link #topQuestions} 里活一次。 */
    private static final class Group {
        private String question;
        private long count;
        private long scored;
        private double scoreSum;
        private LocalDateTime lastAt;
    }
}
