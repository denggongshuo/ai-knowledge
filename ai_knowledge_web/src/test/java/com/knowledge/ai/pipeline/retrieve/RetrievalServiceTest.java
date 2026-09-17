package com.knowledge.ai.pipeline.retrieve;

import com.knowledge.ai.core.ai.RerankClient;
import com.knowledge.ai.core.enums.RetrievalMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索编排的门禁用例（`G5-5` · `S5-M07-A` 步 11）。
 *
 * <h3>判据 5：「`lowScoreWarn` 0.65 以下**只标黄提示，不过滤**」</h3>
 * <p>"被过滤掉即判失败"—— 所以本用例的核心断言不是"有 warn 标记"，
 * 而是 **那些低分分片仍然出现在结果里**。只断言标记存在的话，
 * 一个"标记完再丢掉"的实现照样能绿。
 *
 * <h3>本用例还钉住一件容易被写反的事：分数换了量纲</h3>
 * <p>重排分在 0~1，RRF 融合分在 {@code 1/61} 量级。若实现拿融合分去比
 * {@code missThreshold=0.35}，**每一次检索都会被判成未命中**。
 * 见 {@link #degradedRerankDoesNotProduceAFalseMiss()}。
 */
class RetrievalServiceTest extends RetrievalFlowSupport {

    @Autowired
    private RetrievalService retrievalService;

    /** 库级检索配置：HYBRID + rerankTopK=3 + 重排开启 —— 用例要的就是"重排真的会跑"。 */
    private static final String KB_CONFIG =
            "{\"mode\":\"HYBRID\",\"recallThreshold\":0.15,\"rerankTopK\":3,"
                    + "\"rerankEnabled\":true,\"tableWeight\":1.2,\"identifierProtect\":true}";

    private long docId;

    @BeforeEach
    void setUp() {
        resetProbeState();
        RetrievalTestStubs.resetRerank();
        kbId = insertKb("service-probe", "INTERNAL", KB_CONFIG);
        docId = insertDocWithChunks("检索编排",
                "第一段正文，讲的是分片方案与容量规划",
                "第二段正文，讲的是迁移步骤与回滚预案",
                "第三段正文，讲的是监控告警与值班安排");
        seedVectors(docId, 1);
        awaitVectorsVisible("正文", 3);
    }

    @AfterEach
    void tearDown() {
        RetrievalTestStubs.resetRerank();
        cleanProbeData();
    }

    @Test
    @DisplayName("G5-5 · 低于降权线的分片**仍在结果里**，只是被标黄")
    void lowScoreChunksAreWarnedButNotDropped() {
        // 重排给三条打分：0.90 / 0.50 / 0.20 —— 后两条低于 0.65，但**都不该消失**
        RetrievalTestStubs.rerankStub = fixedScores(0.90, 0.50, 0.20);

        RetrievalResult result = retrievalService.search(reader(), "正文", List.of(kbId));

        assertThat(result.trace().rerankApplied()).as("重排确实跑过").isTrue();
        assertThat(result.chunks())
                .as("三条都要在 —— 被判失败的是「低于降权线的分片被过滤掉了」")
                .hasSize(3);
        assertThat(result.chunks()).extracting(RetrievedChunk::score)
                .containsExactly(0.90, 0.50, 0.20);
        assertThat(result.chunks()).extracting(RetrievedChunk::warn)
                .as("只有低于 0.65 的两条标黄")
                .containsExactly(false, true, true);
        // 标黄**不是**丢弃：条数必须与融合池里能出的条数一致
        assertThat(result.chunks().stream().filter(RetrievedChunk::warn).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("G5-5 · 重排降级时**不产生假未命中**（融合分不与 0.35 比）")
    void degradedRerankDoesNotProduceAFalseMiss() {
        // 默认替身报告「不可用」→ 走降级，分数是融合分（≈0.016 量级）
        RetrievalResult result = retrievalService.search(reader(), "正文", List.of(kbId));

        assertThat(result.trace().rerankApplied()).isFalse();
        assertThat(result.trace().rerankNote()).as("降级原因必须写明").isNotBlank();
        assertThat(result.chunks()).isNotEmpty();
        assertThat(result.chunks()).allSatisfy(chunk -> {
            assertThat(chunk.scoreKind()).isEqualTo(RetrievedChunk.ScoreKind.FUSION);
            assertThat(chunk.scoreComparable())
                    .as("融合分不可与阈值比较 —— 这就是量纲标志的意义").isFalse();
            assertThat(chunk.warn()).as("没重排就不该有降权标记").isFalse();
        });
        assertThat(result.miss())
                .as("融合分若被拿去比 0.35，这里会永远是 true —— 那就是假未命中").isFalse();
        assertThat(result.trace().miss()).isFalse();
    }

    @Test
    @DisplayName("G5-5 · 重排后最高分低于未命中线 → 判未命中（这次是真未命中）")
    void rerankedLowTopScoreIsARealMiss() {
        RetrievalTestStubs.rerankStub = fixedScores(0.10, 0.08, 0.05);

        RetrievalResult result = retrievalService.search(reader(), "正文", List.of(kbId));

        assertThat(result.trace().rerankApplied()).isTrue();
        assertThat(result.miss()).as("最高分 0.10 < 0.35").isTrue();
        assertThat(result.chunks()).as("判未命中**不等于**清空结果（兜底是生成侧的事）").hasSize(3);
    }

    @Test
    @DisplayName("G5-5 · 重排调用抛异常 → 降级但不失败，且原因进 trace")
    void rerankFailureDegradesInsteadOfFailing() {
        RetrievalTestStubs.rerankStub = new RerankClient() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String describe() {
                return "总是失败的替身";
            }

            @Override
            public List<RerankHit> rerank(String query, List<String> documents, int topK) {
                throw new com.knowledge.ai.core.ai.SiliconFlowRerankClient.RerankFailedException("探针注入的重排故障");
            }
        };

        RetrievalResult result = retrievalService.search(reader(), "正文", List.of(kbId));

        assertThat(result.trace().rerankApplied()).isFalse();
        assertThat(result.trace().rerankNote()).contains("探针注入的重排故障");
        assertThat(result.chunks()).as("重排挂了不该让检索失败").isNotEmpty();
        assertThat(result.miss()).as("降级时不判未命中").isFalse();
    }

    @Test
    @DisplayName("G5-5 · rerankTopK 生效：结果条数被裁到配置值（这里配 3）")
    void rerankTopKLimitsTheResultSize() {
        RetrievalTestStubs.rerankStub = fixedScores(0.9, 0.8, 0.7, 0.6, 0.5);

        RetrievalResult result = retrievalService.search(reader(), "正文", List.of(kbId));

        assertThat(result.trace().rerankTopK()).isEqualTo(3);
        assertThat(result.chunks()).as("融合池有 3 条，topK=3 时全留").hasSize(3);
        assertThat(result.chunks()).extracting(RetrievedChunk::rank).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("G5-9 前半的运行时验证：返回的 chunks 与 trace.chunks 同源")
    void resultKeepsTheSameChunkListInstance() {
        RetrievalResult result = retrievalService.search(reader(), "正文", List.of(kbId));

        assertThat(result.chunks()).isSameAs(result.trace().chunks());
    }

    @Test
    @DisplayName("检索参数与可见范围都进了 trace（事后能还原「当时用的是哪个阈值」）")
    void traceCarriesTheEffectiveParams() {
        RetrievalResult result = retrievalService.search(reader(), "正文", List.of(kbId));

        RetrievalTrace trace = result.trace();
        assertThat(trace.kbIds()).containsExactly(kbId);
        assertThat(trace.kbCount()).isEqualTo(1);
        assertThat(trace.docScopeCount()).as("MySQL 先算的可见文档数").isEqualTo(1);
        assertThat(trace.docScopeFallback()).isFalse();
        assertThat(trace.recallTopK()).isEqualTo(20);
        assertThat(trace.missThreshold()).isEqualTo(0.35);
        assertThat(trace.lowScoreWarn()).isEqualTo(0.65);
        assertThat(trace.mode()).isEqualTo(RetrievalMode.HYBRID);
        assertThat(trace.query()).isEqualTo("正文");
    }

    @Test
    @DisplayName("没有可见知识库时返回可解释的空结果，且**不去连 Milvus**")
    void noVisibleKbYieldsAnExplainableEmptyResult() {
        long otherKb = insertKb("service-probe-invisible");
        // 把授权撤掉 —— 该库对任何人都不可见（`I-7` 的求交）
        jdbc.update("delete from sys_kb_grant where kb_id = ?", otherKb);

        RetrievalResult result = retrievalService.search(reader(), "正文", List.of(otherKb));

        assertThat(result.empty()).isTrue();
        assertThat(result.trace().rerankNote()).contains("没有可检索的知识库");
        assertThat(result.trace().chunks()).isSameAs(result.chunks());

        jdbc.update("delete from sys_kb where id = ?", otherKb);
    }

    @Test
    @DisplayName("空提问被拒（不是返回空结果）")
    void blankQueryIsRejected() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> retrievalService.search(reader(), "   ", List.of(kbId)))
                .isInstanceOf(com.knowledge.ai.core.exception.BizException.class)
                .hasMessageContaining("请先输入问题");
    }

    // ==================== 替身 ====================

    /**
     * 一个按**融合池下标**给固定分的重排替身。
     *
     * <p>按下标而不是按内容给分：用例要控制的是"哪一条得多少分"，
     * 而融合池的次序由 RRF 决定 —— 用下标就与内容解耦，改语料不会连带改断言。
     */
    private static RerankClient fixedScores(double... scores) {
        AtomicReference<List<String>> seen = new AtomicReference<>();
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
                seen.set(documents);
                List<RerankHit> hits = new java.util.ArrayList<>();
                for (int i = 0; i < documents.size() && i < scores.length; i++) {
                    hits.add(new RerankHit(i, scores[i]));
                }
                return hits;
            }
        };
    }
}
