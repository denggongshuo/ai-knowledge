package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.ChatFeedbackReason;
import com.knowledge.ai.core.ops.QaMissRepository;
import com.knowledge.ai.core.ops.QaOpsRepository;
import com.knowledge.ai.core.ops.TimeWindow;
import com.knowledge.ai.dto.ops.OpsDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 问答运营 —— 高频问题榜与点踩归因（`T-A02` · 需求 `08` §1.5 / §1.6）。
 *
 * <h3>本批为什么与 `QaMissService` 分成两个类</h3>
 * <p>同属 11 页，但取数源、窗口聚合与可空口径都不一样：`QaMissService` 那一半是
 * 「池子里的行」（`sys_qa_miss`，状态机的产品），这两条是「明细窗口的聚合」
 * （`sys_retrieval_trace` / `sys_chat_message`，只读、不落任何行）。
 * 合在一起会让那个类同时承担"改状态"与"扫明细"两件事。
 * <p>⚠️ 共用的只有**窗口口径**，它被提到了 {@link TimeWindow}（只此一处）。
 *
 * <h3>🔴 这一层不做"分子分母凑得上"的补偿</h3>
 * <p>{@link #feedbackStats} 里的每个数都来自**它自己的观测点**，没有任何一个是由别的数
 * 算出来的。见 `QaOpsRepository#feedbackCounts` 的注释：把总数写成 `sum(分档)` 的话，
 * "两者相等"就成了结构上恒真的事实，判据会退化成**空断言**（本仓被这个形状咬过一次）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaOpsService {

    /**
     * 负反馈率的对比阈值（%）。
     *
     * <p>来源：需求 `08` §1.6 原话「展示各原因的次数与占比 + **负反馈率**（与阈值 5% 对比）」。
     * ⚠️ **刻意不放进 `sys_setting`**：需求把它写成一个固定的对比基线（示例 `28 ÷ 1284 = 2.2%`
     * 对 `5%`），不是可调参数。做成配置项就等于凭空发明一个没人会调的开关
     * —— 而 `#153`/`#168` 记的正是"一条没有角色能证伪的边界"。
     */
    private static final double NEGATIVE_RATE_THRESHOLD = 5d;

    /** 榜单默认取几行 —— 需求 §1.8 那条接口写的是 `limit=20`。 */
    private static final int DEFAULT_LIMIT = 20;

    private final QaOpsRepository qaOpsRepository;

    /** 只为拿**窗口内提问总数**（负反馈率的分母）—— 见 {@link #feedbackStats}。 */
    private final QaMissRepository qaMissRepository;

    // ==================== 高频问题榜 · `GET /ops/qa/top-questions` ====================

    /**
     * 需求 §1.5：问题 + 次数 + 平均 Top1 相似度。
     *
     * @param range 时间窗（`7d` / `30d` / `90d`），空 = 7 天 —— 与 `overview` **同一个** {@link TimeWindow}
     * @param limit 取前几行，空 = 20（上限 100，夹取不拒绝）
     */
    public OpsDtos.TopQuestionsResponse topQuestions(String range, Integer limit) {
        LocalDateTime from = TimeWindow.from(range);
        int want = limit == null || limit < 1 ? DEFAULT_LIMIT : limit;

        QaOpsRepository.TopQuestions found = qaOpsRepository.topQuestions(from, want);
        List<OpsDtos.TopQuestion> items = new ArrayList<>(found.rows().size());
        for (QaOpsRepository.TopQuestionRow row : found.rows()) {
            items.add(new OpsDtos.TopQuestion(row.question(), row.count(), row.avgTopScore()));
        }
        return new OpsDtos.TopQuestionsResponse(items, found.totalGroups());
    }

    // ==================== 点踩归因 · `GET /ops/qa/feedback-stats` ====================

    /**
     * 需求 §1.6：各原因的次数与占比 + 负反馈率。
     *
     * <h3>🔴 分母是「窗口内提问总数」，不是「有反馈的消息数」</h3>
     * <p>需求 §1.6 原话「负反馈率 = 负反馈数 ÷ **提问总数**」，示例 `28 ÷ 1284`。
     * 所以分母走 {@link QaMissRepository#traceStats} —— 那个方法正是 `overview`
     * 算 `totalQuestions` 用的**同一个**方法 ⇒ 两个卡片的"提问总数"必然一致。
     * ⚠️ 不要在这里改成"另一个计数"：那会让两个卡片的比率各自成立、彼此对不上，
     * 而页面上看不出哪个错了。
     *
     * <h3>🔴 没填原因的点踩单独一个数</h3>
     * <p>见 {@link OpsDtos.FeedbackStats} 的类注释 —— 需求 §1.10 那条等式
     * 在"点踩不带原因"时不可能成立，本类的处置是**三个数都如实给**。
     */
    public OpsDtos.FeedbackStats feedbackStats(String range) {
        LocalDateTime from = TimeWindow.from(range);
        QaOpsRepository.FeedbackCounts counts = qaOpsRepository.feedbackCounts(from);
        long totalQuestions = qaMissRepository.traceStats(from).totalQuestions();

        List<OpsDtos.FeedbackReason> reasons = new ArrayList<>();
        Set<String> known = new LinkedHashSet<>();
        // 闭集**逐条都在**（缺的补 0）：否则"这个原因是 0"与"这个原因不存在"长得一样，
        // 而页面上少一行没有任何现象
        for (ChatFeedbackReason reason : ChatFeedbackReason.values()) {
            long count = counts.byReason().getOrDefault(reason.getLabel(), 0L);
            known.add(reason.getLabel());
            reasons.add(new OpsDtos.FeedbackReason(reason.getLabel(), count,
                    percentOf(count, counts.unlikeTotal())));
        }
        // 闭集之外的值**不静默丢**：丢了会让 sum(reasons) + unreasoned < negativeTotal，
        // 而那个差在页面上没有任何现象（照常渲染，只是数字对不上）。
        // 出现它就说明有人绕过了 ChatFeedbackService 的白名单（老数据 / 直写库）。
        for (Map.Entry<String, Long> entry : counts.byReason().entrySet()) {
            if (known.contains(entry.getKey())) {
                continue;
            }
            log.warn("[运营] 点踩原因出现闭集之外的值：「{}」{} 条 —— 不是这几个入口写的？"
                    + "（白名单在 ChatFeedbackService#submit）", entry.getKey(), entry.getValue());
            reasons.add(new OpsDtos.FeedbackReason(entry.getKey(), entry.getValue(),
                    percentOf(entry.getValue(), counts.unlikeTotal())));
        }

        double rate = totalQuestions == 0
                ? 0d
                : Math.round(counts.unlikeTotal() * 1000.0 / totalQuestions) / 10.0;

        return new OpsDtos.FeedbackStats(counts.unlikeTotal(), rate, NEGATIVE_RATE_THRESHOLD,
                counts.unreasoned(), reasons);
    }

    // ==================== 内部 ====================

    /** 占比（%，一位小数）；总数为 0 时给 0 —— 与 `QaMissService#overview` 的 `missedRate` 同一个写法。 */
    private static double percentOf(long count, long total) {
        return total == 0 ? 0d : Math.round(count * 1000.0 / total) / 10.0;
    }
}
