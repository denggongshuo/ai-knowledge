package com.knowledge.ai.pipeline.retrieve;

import com.knowledge.ai.core.ops.MissKeyNormalizer;
import com.knowledge.ai.core.ops.QaMissRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 未命中**沉淀**（`T-A00` · 需求 `08` §1.4 闭环第 1 步：「召回分低于阈值**自动入池**，
 * 无需人工发现」）。
 *
 * <h3>为什么挂在 `RetrievalTraceWriter` 上，而不是另起一处</h3>
 * <p>它是打点行的**纯函数派生**：入池要的三个东西（问句、提问人、Top1 分）全都在
 * 那一次写打点的入参里，而 {@code miss_flag} 是**同一次计算**的产物。
 * 另起一处就要重新判一次未命中 —— 于是"打点说未命中、池子说没有"会成为可能，
 * 而那种不一致**没有任何现象**，只有对账才发现。
 *
 * <p>⚠️ 与 `S6-M11` 的教训同一条：**接线点要选在"数据在手上"的那一层**，
 * 而不是"看起来唯一"的那一层。这里 `write()` 手里同时有 `trace.miss()` 与 `chunks`。
 *
 * <h3>🔴 绝不抛 —— 沉淀失败不能让问答失败</h3>
 * <p>与 `RetrievalTraceWriter` / `NoticePublisher` 同一条纪律：这是旁路。
 * 失败只 warn，且 warn 里必须带 traceId（否则"池子少一行"与"用户没提问"长得一样）。
 *
 * <p>⚠️ **代价要写明**：打点与沉淀不是同一个事务（{@code QaMissRepository#upsert}
 * 自己开 `REQUIRES_NEW`），所以可能出现「打点行在、池子没有」的窗口。
 * 这是**故意的取舍** —— 打点是证据流，不能因为池子写不进去而被回滚；
 * 而"缺的那一行"可以由 `miss_flag = 1` 的打点**重建出来**。反过来（池子有、打点没有）
 * 才是不可恢复的，所以顺序是**先打点、后沉淀**。
 *
 * <h3>为什么只沉淀 `miss_flag = 1`</h3>
 * <p>空召回**不入池**（`G5-11` 早把两者分流了）：那是"一条都没召回"，
 * 与"召回了但都不像"的下一步动作完全不同（前者要查索引/可见范围，后者要补内容）。
 * 把它们混进同一个池子，会让人对着一个"库里根本没有这一条"的问题去补文档。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MissSink {

    /** 与 `sys_qa_miss.question` 的列宽一致。 */
    private static final int MAX_QUESTION_LEN = 500;

    private final QaMissRepository qaMissRepository;

    /** 打点写完之后调用。**本方法不会抛**。 */
    public void record(RetrievalTraceWriter.TraceWrite write) {
        RetrievalTrace trace = write.result().trace();
        if (!trace.miss()) {
            return;
        }
        String question = trace.query();
        if (question == null || question.isBlank()) {
            // 打点表的 query_text 是 NOT NULL，正常走不到这里；走到了说明入参构造有问题，
            // 留痕而不是拿空串去算一个"所有空问句都合并"的键
            log.warn("[未命中池] 问句为空，跳过沉淀：traceId={}", write.traceId());
            return;
        }
        try {
            qaMissRepository.upsert(
                    MissKeyNormalizer.keyOf(question),
                    truncate(question),
                    write.userId(),
                    // 与打点行的 top1_score **同一个算法**（`RetrievalTraceWriter#top1`），
                    // 否则池子里的"最高相似度"与打点里的对不上，而两处都看着合理
                    RetrievalTraceWriter.top1(write.result().chunks()),
                    RetrievalTraceWriter.joinIds(trace.kbIds()),
                    LocalDateTime.now());
        } catch (RuntimeException ex) {
            log.warn("[未命中池] 沉淀失败，本次问答不受影响：traceId={} err={}",
                    write.traceId(), ex.getMessage());
        }
    }

    /**
     * 截到列宽上限，且**不切断代理对**。
     *
     * <p>`substring(0, 500)` 在 emoji 上会把一个 UTF-16 代理对切成两半，
     * 那串文本再发往 MySQL 就是非法字符 ⇒ 整条 upsert 失败 ⇒ **池子里少一行**，
     * 而现象只是"这个问题没沉淀进去"，看不出与那个 emoji 有关。
     *
     * <p>键仍按**全文**算（见 {@code MissKeyNormalizer}）：截断只影响展示，
     * 不影响聚合 —— 否则两条前 500 字相同的长问句会被错并成一行。
     */
    private static String truncate(String question) {
        if (question.length() <= MAX_QUESTION_LEN) {
            return question;
        }
        int end = MAX_QUESTION_LEN;
        if (Character.isHighSurrogate(question.charAt(end - 1))) {
            end--;
        }
        return question.substring(0, end);
    }
}
