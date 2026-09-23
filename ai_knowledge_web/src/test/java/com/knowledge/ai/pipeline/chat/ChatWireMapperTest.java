package com.knowledge.ai.pipeline.chat;

import com.knowledge.ai.core.enums.RetrievalMode;
import com.knowledge.ai.pipeline.retrieve.RetrievalHit;
import com.knowledge.ai.pipeline.retrieve.RetrievalResult;
import com.knowledge.ai.pipeline.retrieve.RetrievalTrace;
import com.knowledge.ai.pipeline.retrieve.RetrievedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 线格式映射（`T-800` · `G5-9`）—— **纯单元**，不起 Spring、不连库。
 *
 * <p>为什么值得一个独立用例：本批的核心风险就是「两套同名不同类型的转换只有一处」，
 * 而这一处错了的现象是**前端白屏**（`10:369` 把它列为 P0）。
 * 走真 HTTP 的 E2E 能发现白屏，但发现不了"`stages` 里少了一段""`docTitle` 落成了空串"
 * 这类**不白屏但不对**的偏差。
 */
class ChatWireMapperTest {

    private static final ChatWireMapper MAPPER = new ChatWireMapper();

    @Test
    @DisplayName("首包：llmGeneration 与含生成的总耗时都还没有真值，如实给 0 与检索端到端")
    void firstPacketHasNoGenerationYet() {
        RetrievalResult result = result(chunk("1001", 1L, 0.91, Map.of(RetrievalHit.Channel.VECTOR, 1)));

        ChatDtos.RetrievalEvent event = MAPPER.firstPacket(result, Map.of(1L, "报价单 A"));

        assertThat(event.trace().llmGeneration())
                .as("首包发出时生成还没开始 —— 这里必须是 0，不能编一个像样的假数字")
                .isZero();
        assertThat(event.trace().totalMs())
                .as("首包的 totalMs 是**检索**端到端（trace.totalMillis），不是含生成的总数")
                .isEqualTo(result.trace().totalMillis());
    }

    @Test
    @DisplayName("终包：生成耗时与含生成的总耗时都换成真值")
    void finalPacketCarriesRealGeneration() {
        RetrievalResult result = result(chunk("1001", 1L, 0.91, Map.of(RetrievalHit.Channel.VECTOR, 1)));

        ChatDtos.RetrievalEvent event = MAPPER.finalPacket(result, Map.of(), 1234L, 5678L);

        assertThat(event.trace().llmGeneration()).isEqualTo(1234L);
        assertThat(event.trace().totalMs()).isEqualTo(5678L);
    }

    @Test
    @DisplayName("G5-9：顶层 chunks 与 trace.chunks 是同一份内容（不是两次 map 出来的巧合）")
    void chunksAreTheSameListInstance() {
        RetrievalResult result = result(
                chunk("1001", 1L, 0.91, Map.of(RetrievalHit.Channel.VECTOR, 1)),
                chunk("1002", 2L, 0.55, Map.of(RetrievalHit.Channel.BM25, 1)));

        ChatDtos.RetrievalEvent event = MAPPER.firstPacket(result, Map.of());

        assertThat(event.chunks())
                .as("必须是同一个 List 实例 —— '内容碰巧一样'不是同源")
                .isSameAs(event.trace().chunks());
        assertThat(event.chunks()).hasSize(2);
    }

    @Test
    @DisplayName("stages 按真实先后排，且 §9 的 6 段都在（后 3 段是本批追加的真阶段）")
    void stagesCoverNinePhases() {
        RetrievalResult result = result(chunk("1001", 1L, 0.91, Map.of(RetrievalHit.Channel.VECTOR, 1)));

        List<ChatDtos.TraceStage> stages = MAPPER.firstPacket(result, Map.of()).trace().stages();

        assertThat(stages).extracting(ChatDtos.TraceStage::name).containsExactly(
                "可见范围", "查询解析", "Embedding", "向量召回", "BM25 召回",
                "RRF 融合", "Rerank", "表格回表", "LLM 生成");
        assertThat(stages).hasSize(9);
    }

    @Test
    @DisplayName("stages 里各段的值就是 trace 里对应的耗时（不是摆设）")
    void stageValuesComeFromTheTrace() {
        RetrievalResult result = result(chunk("1001", 1L, 0.91, Map.of(RetrievalHit.Channel.VECTOR, 1)));

        ChatDtos.TraceWire trace = MAPPER.firstPacket(result, Map.of()).trace();

        assertThat(trace.embed()).isEqualTo(8L);
        assertThat(trace.vectorRecall()).isEqualTo(12L);
        assertThat(trace.bm25Recall()).isEqualTo(9L);
        assertThat(trace.rerank()).isEqualTo(30L);
        assertThat(trace.queryAnalysis()).as("后端没有查询解析这一步，恒 0").isZero();
    }

    @Test
    @DisplayName("source 是单值：两路都命中时取名次更好的那一路")
    void sourcePicksTheBetterRankedChannel() {
        RetrievalResult result = result(
                chunk("1001", 1L, 0.9, Map.of(RetrievalHit.Channel.VECTOR, 5, RetrievalHit.Channel.BM25, 2)),
                chunk("1002", 2L, 0.8, Map.of(RetrievalHit.Channel.VECTOR, 1, RetrievalHit.Channel.BM25, 6)),
                chunk("1003", 3L, 0.7, Map.of(RetrievalHit.Channel.BM25, 3)));

        List<ChatDtos.ChunkRef> refs = MAPPER.firstPacket(result, Map.of()).chunks();

        assertThat(refs).extracting(ChatDtos.ChunkRef::source)
                .as("BM25 名次更前 → bm25；向量更前 → vector；只有 BM25 → bm25")
                .containsExactly("bm25", "vector", "bm25");
    }

    @Test
    @DisplayName("docTitle 来自传进来的那张表；查不到时是空串，由前端兜底成「未知文档」")
    void docTitleFallsBackToEmpty() {
        RetrievalResult result = result(
                chunk("1001", 1L, 0.9, Map.of(RetrievalHit.Channel.VECTOR, 1)),
                chunk("1002", 2L, 0.8, Map.of(RetrievalHit.Channel.VECTOR, 2)));

        List<ChatDtos.ChunkRef> refs = MAPPER.firstPacket(result, Map.of(1L, "报价单 A")).chunks();

        assertThat(refs).extracting(ChatDtos.ChunkRef::docTitle).containsExactly("报价单 A", "");
    }

    @Test
    @DisplayName("主键不是数字的分片整条丢掉，不猜一个 id 出来")
    void nonNumericChunkIdIsDropped() {
        RetrievalResult result = result(
                chunk("1001", 1L, 0.9, Map.of(RetrievalHit.Channel.VECTOR, 1)),
                chunk("chunk-abc", 2L, 0.8, Map.of(RetrievalHit.Channel.VECTOR, 2)));

        ChatDtos.RetrievalEvent event = MAPPER.firstPacket(result, Map.of());

        assertThat(event.chunks()).hasSize(1);
        assertThat(event.chunks().getFirst().chunkId()).isEqualTo(1001L);
        // ⚠️ 顶层与 trace 必须**同步**地少这一条，否则两者的长度会不一致
        assertThat(event.trace().chunks()).hasSize(1);
    }

    @Test
    @DisplayName("图谱旁路未实现：graphUsed 恒 false，另两个可选字段不发（不是发 0）")
    void graphFieldsAreHonest() {
        RetrievalResult result = result(chunk("1001", 1L, 0.9, Map.of(RetrievalHit.Channel.VECTOR, 1)));

        ChatDtos.TraceWire trace = MAPPER.firstPacket(result, Map.of()).trace();

        assertThat(trace.graphUsed()).isFalse();
        assertThat(trace.graphCostMs())
                .as("不发比发 0 诚实 —— '没走过图谱'与'图谱耗时 0ms'不是同一件事")
                .isNull();
        assertThat(trace.graphFallback()).isNull();
    }

    // ==================== T-804 · 引用映射 ====================

    @Test
    @DisplayName("T-804：citations 按**角标号**升序，且 index 与 rankNo 是两个数")
    void citationsAreOrderedByMarkerNumber() {
        List<RetrievedChunk> chunks = List.of(
                chunk("1001", 11L, 0.90, Map.of()),
                chunk("1002", 22L, 0.80, Map.of()),
                chunk("1003", 33L, 0.70, Map.of()));

        // 答案里只打了 `[3]` 与 `[1]` —— 故意**逆序**，好让"按出现顺序返回"当场露馅
        var citations = MAPPER.citations(java.util.Set.of(3, 1), chunks, Map.of(11L, "甲", 33L, "丙"));

        assertThat(citations).hasSize(2);
        assertThat(citations.get(0).index())
                .as("按角标号**升序** —— 前端拿它渲染，顺序乱会让角标行读起来是乱的")
                .isEqualTo(1);
        assertThat(citations.get(1).index()).isEqualTo(3);
        assertThat(citations.get(0).rankNo())
                .as("角标 1 对应喂给模型的第 1 条 ⇒ 检索名次也是 1（本夹具里名次 = 列表下标 + 1）")
                .isEqualTo(1);
        assertThat(citations.get(0).chunkId()).isEqualTo(1001L);
        assertThat(citations.get(0).docTitle()).isEqualTo("甲");
        assertThat(citations.get(0).score()).isEqualTo(0.90);
        assertThat(citations.get(1).chunkId()).isEqualTo(1003L);
        assertThat(citations.get(1).docTitle()).isEqualTo("丙");
    }

    @Test
    @DisplayName("T-804：角标越界时整条丢掉（解析器已滤过一遍，这里是它自己的契约）")
    void outOfRangeMarkerIsDroppedHereToo() {
        List<RetrievedChunk> chunks = List.of(chunk("1001", 11L, 0.9, Map.of()));

        assertThat(MAPPER.citations(java.util.Set.of(1, 5), chunks, Map.of()))
                .as("`5` 越界（只喂了 1 条）⇒ 丢掉。"
                        + "⚠️ 解析器也会滤，但本方法的入参是公开的 —— "
                        + "「越界即跳过」是**它自己**的契约，不该靠调用方守")
                .hasSize(1);
    }

    @Test
    @DisplayName("T-804：空角标集合 ⇒ 空清单（调用方据此让 cited 写 NULL）")
    void emptyRanksYieldEmptyCitations() {
        List<RetrievedChunk> chunks = List.of(chunk("1001", 11L, 0.9, Map.of()));

        assertThat(MAPPER.citations(java.util.Set.of(), chunks, Map.of())).isEmpty();
        assertThat(MAPPER.citations(null, chunks, Map.of())).isEmpty();
        assertThat(MAPPER.citations(java.util.Set.of(1), List.of(), Map.of())).isEmpty();
    }

    // ==================== 夹具 ====================

    private static RetrievalResult result(RetrievedChunk... chunks) {
        List<RetrievedChunk> list = List.of(chunks);
        return new RetrievalResult(list, trace(list), 1);
    }

    private static RetrievedChunk chunk(String chunkId, long docId, double score,
                                        Map<RetrievalHit.Channel, Integer> channelRanks) {
        return new RetrievedChunk(chunkId, docId, 1L, "PARAGRAPH", "标题", "正文",
                score, RetrievedChunk.ScoreKind.RERANK, 1, 1, score, score,
                channelRanks, false, null);
    }

    /** 耗时：范围 5 / 召回 20（其中嵌入 8 + 向量检索 12）/ BM25 9 / 融合 1 / 重排 30 / 回表 2 / 合计 60。 */
    private static RetrievalTrace trace(List<RetrievedChunk> chunks) {
        return new RetrievalTrace("提问", RetrievalMode.HYBRID, List.of(1L), 1,
                3, false, 2, 2, 3,
                5L, 20L, 8L, 12L, 9L, 1L, 30L, 2L, 60L,
                20, 0.15, 5, true, 1.2, 0.35, 0.65, true, "", "", 0.42, false, 0L, false, null, false, chunks);
    }
}
