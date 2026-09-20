package com.knowledge.ai.pipeline.retrieve;

import com.knowledge.ai.core.ai.RerankClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「重排前 Top1」这一列的**写入侧**（`T-A03` · `V35`）。
 *
 * <h3>它为什么值一条独立用例</h3>
 * <p>需求 `08` §2.4 整节的结论是「288ms 换来 +18 个百分点的命中提升」，
 * 而**只有这一列**能让那句话变成可算的（此前主表没有任何"重排前"的分数，
 * 子表存的又是最终分 —— 见 `V35` 的列注释）。它是本仓硬约束
 * **`D-2`「打点后补 = 数据永久缺失」**的直接落点 ⇒ 要验的不是"代码写了"，
 * 而是**它真的落进了库**。
 *
 * <h3>🔴 断言里那对分数是刻意错开的</h3>
 * <p>替身按**融合池下标**给分：`0.20 / 0.90 / 0.50`。于是
 * <ul>
 *   <li>融合池 rank-1 那条拿 **0.20**（= 重排前 Top1）；</li>
 *   <li>重排后的 Top1 变成拿 **0.90** 的那条（融合池 rank-2）。</li>
 * </ul>
 * 两者**不相等**，所以"取最大值"或"取返回列表第 0 条"这两种写错法都会被这条挡住 ——
 * 若像别的用例那样给单调递减的分（0.90/0.80/0.70），重排前后恰好相同，这条就**什么都验不到**。
 *
 * <h3>⚠️ 不走问答链路</h3>
 * <p>真问答链路（`ChatStreamService#chat`）那条路由 `ImageDescribeNetworkTest` 已经覆盖，
 * 而它要花一次真模型调用。本用例要的只是"这一列怎么算、怎么落库"，直接调
 * {@link RetrievalService} + {@link RetrievalTraceWriter} 即可
 * —— 与打点表里**别的列**的取证口径一致。
 */
class RetrievalTraceBeforeTop1FlowTest extends RetrievalFlowSupport {

    private static final String KB_CONFIG =
            "{\"mode\":\"HYBRID\",\"recallThreshold\":0.15,\"rerankTopK\":3,"
                    + "\"rerankEnabled\":true,\"tableWeight\":1.2,\"identifierProtect\":true}";

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private RetrievalTraceWriter retrievalTraceWriter;

    private final List<String> traceIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        resetProbeState();
        RetrievalTestStubs.resetRerank();
        kbId = insertKb("before-top1-probe", "INTERNAL", KB_CONFIG);
        long docId = insertDocWithChunks("重排前 Top1 探针",
                "第一段正文，讲的是分片方案与容量规划",
                "第二段正文，讲的是迁移步骤与回滚预案",
                "第三段正文，讲的是监控告警与值班安排");
        seedVectors(docId, 1);
        awaitVectorsVisible("正文", 3);
    }

    @AfterEach
    void tearDown() {
        for (String traceId : traceIds) {
            jdbc.update("delete from sys_retrieval_trace_chunk where trace_id = ?", traceId);
            jdbc.update("delete from sys_retrieval_trace where trace_id = ?", traceId);
        }
        traceIds.clear();
        RetrievalTestStubs.resetRerank();
        cleanProbeData();
    }

    @Test
    @DisplayName("重排前后**不是同一条**时：取的是融合池 rank-1 拿到的分，不是最大值")
    void beforeTop1IsTheFusedLeadersScoreNotTheMax() {
        // 按融合池下标给分：第 0 条（融合 rank-1）得 0.20，第 1 条得 0.90
        RetrievalTestStubs.rerankStub = fixedScores(0.20, 0.90, 0.50);

        RetrievalResult result = retrievalService.search(reader(), "正文", List.of(kbId));

        assertThat(result.trace().rerankApplied()).as("重排确实跑过").isTrue();
        assertThat(RetrievalTraceWriter.top1(result.chunks()))
                .as("重排后的 Top1 是拿 0.90 的那条（融合池 rank-2）")
                .isEqualTo(0.90, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(result.trace().rerankBeforeTop1())
                .as("🔴 重排前 Top1 = **融合池 rank-1** 那条拿到的重排分（0.20）。"
                        + "取最大值会给 0.90、取 chunks 第 0 条也会给 0.90 —— 两种写错法都过不了这条")
                .isEqualTo(0.20, org.assertj.core.api.Assertions.within(1e-9));

        String traceId = "before-top1-" + System.nanoTime();
        traceIds.add(traceId);
        retrievalTraceWriter.write(new RetrievalTraceWriter.TraceWrite(
                traceId, null, null, null, result, 1000L, 1500L));

        Double stored = jdbc.queryForObject(
                "select rerank_before_top1 from sys_retrieval_trace where trace_id = ?",
                Double.class, traceId);
        assertThat(stored)
                .as("🔴 `D-2`：这一列必须**真落进库** —— 只在返回值里算对了，"
                        + "历史数据依然永远缺这一半（那就白加了）")
                .isEqualTo(0.20, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(jdbc.queryForObject(
                "select top1_score from sys_retrieval_trace where trace_id = ?",
                Double.class, traceId))
                .as("同一次写入里最终分也落了库 —— 两列是**同一次检索**的两个时刻")
                .isEqualTo(0.90, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    @DisplayName("重排前那条没进重排结果时，这一列落 NULL（不是 0、也不是融合分）")
    void beforeTop1IsNullWhenTheFusedLeaderIsNotCovered() {
        // 替身**跳过**融合池第 0 条：只回下标 1、2 的命中 ⇒ 融合池 rank-1 没有分数
        RetrievalTestStubs.rerankStub = onlyIndicesFrom(1);

        RetrievalResult result = retrievalService.search(reader(), "正文", List.of(kbId));
        assertThat(result.trace().rerankApplied()).isTrue();
        assertThat(result.trace().rerankBeforeTop1())
                .as("融合池 rank-1 没进重排结果的 top_n ⇒ 它的重排分**未知** ⇒ NULL。"
                        + "填 0 会把「没测到」说成「重排前得了 0 分」")
                .isNull();

        String traceId = "before-top1-miss-" + System.nanoTime();
        traceIds.add(traceId);
        retrievalTraceWriter.write(new RetrievalTraceWriter.TraceWrite(
                traceId, null, null, null, result, 1000L, 1500L));

        assertThat(jdbc.queryForObject(
                "select rerank_before_top1 from sys_retrieval_trace where trace_id = ?",
                Double.class, traceId)).as("NULL 原样落库").isNull();
        assertThat(jdbc.queryForObject(
                "select top1_score from sys_retrieval_trace where trace_id = ?",
                Double.class, traceId))
                .as("另一列仍是非空 —— 说明这一行确实被写进去了，不是整行没写")
                .isNotNull();
    }

    // ==================== 替身 ====================

    /** 按**融合池下标**给固定分的替身（与 `RetrievalServiceTest#fixedScores` 同一个思路）。 */
    private static RerankClient fixedScores(double... scores) {
        return new RerankClient() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String describe() {
                return "固定分替身";
            }

            @Override
            public List<RerankHit> rerank(String query, List<String> documents, int topK) {
                List<RerankHit> hits = new ArrayList<>();
                for (int i = 0; i < documents.size() && i < scores.length; i++) {
                    hits.add(new RerankHit(i, scores[i]));
                }
                return hits;
            }
        };
    }

    /** 只给从 {@code from} 起的候选打分 —— 用来制造"融合池 rank-1 未被覆盖"。 */
    private static RerankClient onlyIndicesFrom(int from) {
        return new RerankClient() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String describe() {
                return "跳过分替身";
            }

            @Override
            public List<RerankHit> rerank(String query, List<String> documents, int topK) {
                List<RerankHit> hits = new ArrayList<>();
                for (int i = from; i < documents.size(); i++) {
                    hits.add(new RerankHit(i, 0.90 - 0.1 * (i - from)));
                }
                return hits;
            }
        };
    }
}
