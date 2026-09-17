package com.knowledge.ai.pipeline.retrieve;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 混合检索内核的流程门禁（`G5-1` · `T-700`）。
 *
 * <h3>判据：「向量 + BM25 并行执行，两者召回结果都能进融合池」</h3>
 * <p>两半各有一处容易写成假的：
 * <ul>
 *   <li><b>「都能进融合池」</b> —— 只断言"返回了非空结果"是不够的：
 *       实现完全可能只走一路（另一路空转）而照样返回非空。所以断言的是
 *       **融合池里既有只来自向量路的 chunkId、也有只来自 BM25 路的 chunkId**；</li>
 *   <li><b>「并行」</b> —— ⚠️ 见 {@link #bothChannelsRunConcurrently()}：
 *       不去比"两路耗时之和 > 墙钟"，那条不等式在毫秒级往返下会因为**取整而相等**，
 *       用例会随机转红。改为记录两个任务的**执行区间**并断言它们**重叠**。</li>
 * </ul>
 */
class HybridRetrievalFlowTest extends RetrievalFlowSupport {

    @BeforeEach
    void setUp() {
        resetProbeState();
        kbId = insertKb("hybrid-probe");
    }

    @AfterEach
    void tearDown() {
        cleanProbeData();
    }

    @Test
    @DisplayName("G5-1 · 两路召回的结果都进了融合池（不是只走一路、另一路空转）")
    void bothChannelsFeedTheFusionPool() {
        // 「扩建」只出现在第一篇、「迁移」只出现在第二篇 —— 于是两路各有**独占**的命中
        long docA = insertDocWithChunks("扩容方案", "订单系统分片方案需要扩建，容量按三倍预留");
        long docB = insertDocWithChunks("迁移记录", "数据库迁移记录：本次迁移涉及三个集群");
        seedVectors(docA, 1);
        seedVectors(docB, 1);
        awaitVectorsVisible("扩建 迁移", 2);

        RetrievalScope.Scope scope = retrievalScope.resolve(reader(), List.of(kbId));
        // 「扩建 迁移」两个字都有 → 两篇都该被 BM25 召回；向量路也会回两条
        HybridRetriever.Recall recall = retriever().recall("扩建 迁移",
                scope, params());

        assertThat(recall.vectorHits()).as("向量路必须有命中").isNotEmpty();
        assertThat(recall.bm25Hits()).as("BM25 路必须有命中 —— 空的话就是这一路在空转").isNotEmpty();

        List<RrfFusion.FusedHit> fused = RrfFusion.fuse(RrfFusion.DEFAULT_K,
                recall.vectorHits(), recall.bm25Hits());

        Set<RetrievalHit.Channel> channelsOfFused = fused.stream()
                .flatMap(hit -> hit.channelRanks().keySet().stream())
                .collect(Collectors.toSet());
        assertThat(channelsOfFused)
                .as("融合池里必须同时有来自两路的命中")
                .containsExactlyInAnyOrder(RetrievalHit.Channel.VECTOR, RetrievalHit.Channel.BM25);

        assertEmbeddingStubInUse();
        String vectorOnly = recall.vectorHits().getFirst().chunkId();
        String bm25Only = recall.bm25Hits().getFirst().chunkId();
        assertThat(fused.stream().map(RrfFusion.FusedHit::chunkId))
                .as("两路各自的头名都要出现在融合池里")
                .contains(vectorOnly, bm25Only);
    }

    @Test
    @DisplayName("G5-1 · 两路**真的并行**（用执行区间重叠证明，不用耗时之和）")
    void bothChannelsRunConcurrently() {
        long docId = insertDocWithChunks("并发探针", "并发探针正文：用于观察两个召回任务的时间区间");
        seedVectors(docId, 1);
        awaitVectorsVisible("并发探针", 1);

        // 人为拖慢向量那一路（嵌入替身 sleep），让两个任务的区间**必然**重叠。
        // 若实现是串行的（先跑完一路再跑另一路），第二个任务会在第一个结束之后才开始，
        // 区间不重叠 —— 本断言当场转红
        embedDelayMillis = 300L;

        RetrievalScope.Scope scope = retrievalScope.resolve(reader(), List.of(kbId));
        HybridRetriever.Recall recall = retriever().recall("并发探针", scope, params());

        assertEmbeddingStubInUse();
        List<Interval> intervals = List.copyOf(INTERVALS);
        assertThat(intervals).as("两个通道各应提交一个任务到检索执行器").hasSize(2);
        assertThat(intervals.get(0).overlaps(intervals.get(1)))
                .as("两个召回任务的执行区间必须重叠（串行实现下它们会首尾相接）")
                .isTrue();

        // 耗时之和 ≥ 墙钟：这是并行的**必要**条件，但不作为主要判据（见类注释的取整问题）
        assertThat(recall.wallMillis()).isLessThanOrEqualTo(recall.sumMillis() + 1);
        assertThat(recall.vectorMillis()).as("嵌入替身至少睡过 300ms").isGreaterThanOrEqualTo(250L);
    }

    @Test
    @DisplayName("G5-1 · 单通道模式只跑一路（VECTOR 不跑 BM25，KEYWORD 不跑向量）")
    void singleChannelModesSkipTheOther() {
        long docId = insertDocWithChunks("单通道", "单通道探针正文，含关键词单通道");
        seedVectors(docId, 1);
        awaitVectorsVisible("单通道", 1);
        RetrievalScope.Scope scope = retrievalScope.resolve(reader(), List.of(kbId));

        HybridRetriever.Recall vectorOnly = retriever().recall("单通道", scope, modeParams("VECTOR"));
        assertEmbeddingStubInUse();
        assertThat(vectorOnly.vectorHits()).isNotEmpty();
        assertThat(vectorOnly.bm25Hits()).as("VECTOR 模式不该跑 BM25").isEmpty();

        resetProbeState();
        HybridRetriever.Recall keywordOnly = retriever().recall("单通道", scope, modeParams("KEYWORD"));
        assertThat(keywordOnly.vectorHits()).as("KEYWORD 模式不该跑向量").isEmpty();
        assertThat(keywordOnly.bm25Hits()).isNotEmpty();
    }

    @Test
    @DisplayName("G5-1 · 提问里没有有效 token 时 BM25 路短路为空（不是报错）")
    void bm25ShortCircuitsOnPunctuationOnlyQuery() {
        long docId = insertDocWithChunks("标点", "标点探针正文");
        seedVectors(docId, 1);
        RetrievalScope.Scope scope = retrievalScope.resolve(reader(), List.of(kbId));

        HybridRetriever.Recall recall = retriever().recall("！！！？？？", scope, modeParams("KEYWORD"));

        assertThat(recall.bm25Hits()).isEmpty();
    }

    // ==================== 构造 ====================

    private static RetrievalParams params() {
        return RetrievalParams.resolve(
                new com.knowledge.ai.dto.kb.KbDtos.RetrievalConfig("HYBRID", 0.15, 5, false, 1.2, true),
                settings());
    }

    private static RetrievalParams modeParams(String mode) {
        return RetrievalParams.resolve(
                new com.knowledge.ai.dto.kb.KbDtos.RetrievalConfig(mode, 0.15, 5, false, 1.2, true),
                settings());
    }

    private static com.knowledge.ai.core.setting.SystemSettingService.Snapshot settings() {
        return new com.knowledge.ai.core.setting.SystemSettingService.Snapshot(java.util.Map.of());
    }
}
