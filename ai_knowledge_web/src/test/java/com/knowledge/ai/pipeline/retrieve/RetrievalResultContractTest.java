package com.knowledge.ai.pipeline.retrieve;

import com.knowledge.ai.core.enums.RetrievalMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * `RetrievalResult` 契约的门禁用例（`G5-9` 前半 · `T-704`）。
 *
 * <h3>判据为什么写的是「同一个 List 实例」而不是「内容相等」</h3>
 * <p>"内容碰巧一样"是**偶然**，"同一个实例"是**结构**。前者靠每次构造时两边碰巧用了
 * 同一份数据，而它会在某次重构（比如给 trace 里的分片加一层过滤）之后悄悄失效 ——
 * 那时的表现是**检索详情页显示的分片与答案引用的分片不是同一批**，
 * 一种用户能看见、开发者几乎不可能想到去查的错位。
 *
 * <h3>这条用例钉的是"那行代码还在不在"</h3>
 * <p>{@link RetrievalResult} 的紧凑构造器**强制**把两者拧成同一个实例 ——
 * 也就是说，只要构造器没被改宽，本用例恒绿。这不是"测了个必然成立的东西"：
 * 它防的是**将来有人把构造器改宽**（比如"我就传两份不同的进去，方便测试"）。
 * 那种改动不会让任何别的用例变红，只会让这个不变量消失。
 *
 * <p>纯函数用例，不起 Spring。
 */
class RetrievalResultContractTest {

    @Test
    @DisplayName("G5-9 · 顶层 chunks 与 trace.chunks 是**同一个 List 实例**")
    void chunksAndTraceChunksAreTheSameInstance() {
        List<RetrievedChunk> chunks = List.of(chunk("c1", 0.9), chunk("c2", 0.5));

        RetrievalResult result = new RetrievalResult(chunks, trace(List.of(), 1), 1);

        assertThat(result.chunks())
                .as("必须是同一个实例，不是「内容碰巧一样」")
                .isSameAs(result.trace().chunks());
        assertThat(result.chunks()).isEqualTo(chunks);
    }

    @Test
    @DisplayName("G5-9 · 构造时传进 trace 的 chunks 会被**覆盖**成顶层那一份")
    void traceChunksIsForcedToMatchTheTopLevel() {
        List<RetrievedChunk> topLevel = List.of(chunk("real", 0.8));
        // 故意传一份**不一样**的进 trace —— 构造器必须把它拧回来
        List<RetrievedChunk> decoy = List.of(chunk("decoy", 0.1));

        RetrievalResult result = new RetrievalResult(topLevel, trace(decoy, 1), 1);

        assertThat(result.trace().chunks()).isSameAs(result.chunks());
        assertThat(result.trace().chunks()).extracting(RetrievedChunk::chunkId).containsExactly("real");
    }

    @Test
    @DisplayName("G5-9 · 逐字段相等（equals 语义也要成立，前端 JSON 序列化才不会有差异）")
    void fieldByFieldEquality() {
        List<RetrievedChunk> chunks = List.of(chunk("c1", 0.9), chunk("c2", 0.5));

        RetrievalResult result = new RetrievalResult(chunks, trace(List.of(), 2), 2);

        assertThat(result.chunks()).isEqualTo(result.trace().chunks());
        assertThat(result.kbCount()).isEqualTo(2);
        assertThat(result.trace().kbCount()).as("两处 kbCount 必须一致").isEqualTo(result.kbCount());
    }

    @Test
    @DisplayName("空结果也是合法结果：chunks 是空列表而不是 null，且 trace 仍然完整")
    void emptyResultIsStillACompleteContract() {
        RetrievalResult result = new RetrievalResult(List.of(), trace(List.of(), 0), 0);

        assertThat(result.empty()).isTrue();
        assertThat(result.chunks()).isEmpty();
        assertThat(result.trace().chunks()).isSameAs(result.chunks());
        assertThat(result.miss()).as("空结果不是「未命中」—— 未命中是「有分数但太低」").isFalse();
    }

    @Test
    @DisplayName("null chunks / null trace 当场拒绝，不产生一个半残的契约对象")
    void nullsAreRejectedAtConstruction() {
        assertThatThrownBy(() -> new RetrievalResult(null, trace(List.of(), 1), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunks");
        assertThatThrownBy(() -> new RetrievalResult(List.of(), null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trace");
    }

    @Test
    @DisplayName("传入的 chunks 会被复制成不可变列表（下游改不动它）")
    void chunksAreDefensivelyCopied() {
        List<RetrievedChunk> mutable = new ArrayList<>();
        mutable.add(chunk("c1", 0.9));

        RetrievalResult result = new RetrievalResult(mutable, trace(List.of(), 1), 1);
        // 构造之后改原列表 —— 结果对象不该跟着变（否则 trace 与顶层会因为"谁先被读"而不同）
        mutable.clear();

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.trace().chunks()).hasSize(1);
    }

    // ==================== 构造 ====================

    private static RetrievedChunk chunk(String chunkId, double score) {
        return new RetrievedChunk(chunkId, 1L, 1L, "PARAGRAPH", "标题", "正文",
                score, RetrievedChunk.ScoreKind.RERANK, 1, 1, score, score,
                Map.of(RetrievalHit.Channel.VECTOR, 1), false);
    }

    private static RetrievalTrace trace(List<RetrievedChunk> chunks, int kbCount) {
        return new RetrievalTrace("测试提问", RetrievalMode.HYBRID, List.of(1L), kbCount,
                10, false, 5, 5, 6, 1L, 2L, 1L, 3L, 7L, 20, 0.15, 5, true, 1.2,
                0.35, 0.65, true, "", false, chunks);
    }
}
