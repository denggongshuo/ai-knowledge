package com.knowledge.ai.pipeline.retrieve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RRF 融合的门禁用例（`G5-2` · `T-701`）。
 *
 * <h3>本用例要钉住的三件事</h3>
 * <ol>
 *   <li><b>用的是倒数排名，不是分数相加</b> —— 断言**手算的期望值逐条相等**
 *       （{@code 1/(k+rank)}），见 {@link #handComputedScoresMatchExactly()}；</li>
 *   <li><b>改写成分数相加必红</b> —— 见 {@link #scoreAdditionWouldProduceADifferentOrder()}：
 *       那组输入刻意让"分数大的一方"与"排名靠前的一方"指向**不同的分片**，
 *       于是两种算法给出完全不同的第一名；</li>
 *   <li><b>按 chunkId 去重</b>，且两路的名次**都**计入分数 ——
 *       见 {@link #bothChannelsContributeToTheSameChunk()}。</li>
 * </ol>
 *
 * <p>纯函数用例：不起 Spring、不连任何中间件，毫秒级。
 */
class RrfFusionTest {

    private static final int K = 60;

    @Test
    @DisplayName("G5-2 · 手算的倒数排名分逐条相等")
    void handComputedScoresMatchExactly() {
        // 向量路：a(1) b(2) c(3)；BM25 路：b(1) d(2) a(3)
        List<RetrievalHit> vector = List.of(
                hit("a", RetrievalHit.Channel.VECTOR, 1),
                hit("b", RetrievalHit.Channel.VECTOR, 2),
                hit("c", RetrievalHit.Channel.VECTOR, 3));
        List<RetrievalHit> bm25 = List.of(
                hit("b", RetrievalHit.Channel.BM25, 1),
                hit("d", RetrievalHit.Channel.BM25, 2),
                hit("a", RetrievalHit.Channel.BM25, 3));

        List<RrfFusion.FusedHit> fused = RrfFusion.fuse(K, vector, bm25);

        assertThat(fused).hasSize(4);
        // ⚠️ 下面四个数是**手算**的，不是从实现里抄的：
        //   b = 1/(60+2) + 1/(60+1) = 1/62 + 1/61  ← 两路都靠前 → 第一
        //   a = 1/(60+1) + 1/(60+3) = 1/61 + 1/63
        //   d = 1/(60+2)            = 1/62        ← 只有一路
        //   c = 1/(60+3)            = 1/63
        assertThat(fused.get(0).chunkId()).isEqualTo("b");
        assertThat(fused.get(0).score()).isCloseTo(1.0 / 62 + 1.0 / 61, offset());
        assertThat(fused.get(1).chunkId()).isEqualTo("a");
        assertThat(fused.get(1).score()).isCloseTo(1.0 / 61 + 1.0 / 63, offset());
        assertThat(fused.get(2).chunkId()).isEqualTo("d");
        assertThat(fused.get(2).score()).isCloseTo(1.0 / 62, offset());
        assertThat(fused.get(3).chunkId()).isEqualTo("c");
        assertThat(fused.get(3).score()).isCloseTo(1.0 / 63, offset());
        // rank 是 1 起的连续整数
        assertThat(fused.stream().map(RrfFusion.FusedHit::rank)).containsExactly(1, 2, 3, 4);
    }

    @Test
    @DisplayName("G5-2 · 改用分数相加会得到不同的第一名（负向：这条是防回归的锚）")
    void scoreAdditionWouldProduceADifferentOrder() {
        // 刻意构造一组"排名"与"原始分数"指向**不同**赢家的输入：
        //
        //   分片 V(L2 距离)  B(BM25 分)      RRF 分                Σ 原始分
        //   A    1 (0.001)   3 (20.0)     1/61+1/63 = 0.0322657   20.001
        //   B    2 (0.5)     1 (40.0)     1/62+1/61 = 0.0325224   40.5
        //   C    3 (900.0)   4 (0.5)      1/63+1/64 = 0.0314980   900.5   ← Σ 的最大值
        //   D    4 (0.8)     2 (30.0)     1/64+1/62 = 0.0317540   30.8
        //
        // RRF 的第一名是 **B**（两路都靠前）；Σ 原始分的第一名是 **C**
        // ——因为向量那一路的原始分是 **L2 距离**（越大越不相关），相加等于奖励最差的那条。
        // 两种算法给出不同的冠军，这让"实现被改成分数相加"必然让本用例转红。
        List<RetrievalHit> vector = List.of(
                score("A", RetrievalHit.Channel.VECTOR, 1, 0.001),
                score("B", RetrievalHit.Channel.VECTOR, 2, 0.5),
                score("C", RetrievalHit.Channel.VECTOR, 3, 900.0),
                score("D", RetrievalHit.Channel.VECTOR, 4, 0.8));
        List<RetrievalHit> bm25 = List.of(
                score("B", RetrievalHit.Channel.BM25, 1, 40.0),
                score("D", RetrievalHit.Channel.BM25, 2, 30.0),
                score("A", RetrievalHit.Channel.BM25, 3, 20.0),
                score("C", RetrievalHit.Channel.BM25, 4, 0.5));

        List<RrfFusion.FusedHit> fused = RrfFusion.fuse(K, vector, bm25);

        assertThat(fused.stream().map(RrfFusion.FusedHit::chunkId))
                .as("RRF 的排序：B > A > D > C（手算见上表）")
                .containsExactly("B", "A", "D", "C");
        assertThat(fused.getFirst().chunkId())
                .as("冠军必须是两路都靠前的 B；若变成 C（Σ 原始分 900.5 最大的那条），"
                        + "说明实现退化成按分数相加")
                .isEqualTo("B");
        // 逐条断言分数 = 手算的倒数排名之和（**一个原始分都没有参与**）
        assertThat(fused.get(0).score()).isCloseTo(1.0 / 62 + 1.0 / 61, offset());
        assertThat(fused.get(1).score()).isCloseTo(1.0 / 61 + 1.0 / 63, offset());
        assertThat(fused.get(2).score()).isCloseTo(1.0 / 64 + 1.0 / 62, offset());
        assertThat(fused.get(3).score()).isCloseTo(1.0 / 63 + 1.0 / 64, offset());
    }

    @Test
    @DisplayName("G5-2 · 按 chunkId 去重，两路名次都计入")
    void bothChannelsContributeToTheSameChunk() {
        List<RetrievalHit> vector = List.of(
                hit("x", RetrievalHit.Channel.VECTOR, 1),
                hit("y", RetrievalHit.Channel.VECTOR, 2));
        List<RetrievalHit> bm25 = List.of(
                hit("x", RetrievalHit.Channel.BM25, 1),
                hit("z", RetrievalHit.Channel.BM25, 2));

        List<RrfFusion.FusedHit> fused = RrfFusion.fuse(K, vector, bm25);

        assertThat(fused).hasSize(3);
        RrfFusion.FusedHit x = fused.stream().filter(h -> h.chunkId().equals("x")).findFirst().orElseThrow();
        assertThat(x.channelRanks())
                .containsEntry(RetrievalHit.Channel.VECTOR, 1)
                .containsEntry(RetrievalHit.Channel.BM25, 1);
        assertThat(x.fromBothChannels()).isTrue();
        assertThat(x.score()).isCloseTo(2.0 / 61, offset());
        // 只进了一路的 z，分数必然低于两路都进的 x —— 这就是混合检索的增益来源
        assertThat(x.score()).isGreaterThan(
                fused.stream().filter(h -> h.chunkId().equals("z")).findFirst().orElseThrow().score());
    }

    @Test
    @DisplayName("G5-2 · 20+20 去重后条数 = 并集大小（不是 40，也不是 20）")
    void twentyPlusTwentyDeduplicatesToTheUnion() {
        // 前 14 条重叠：两路各自 20 条 → 并集 26 条（计划 §6 判据 2 的期望）
        List<RetrievalHit> vector = new ArrayList<>();
        List<RetrievalHit> bm25 = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            vector.add(hit("v" + i, RetrievalHit.Channel.VECTOR, i + 1));
            bm25.add(hit("v" + i, RetrievalHit.Channel.BM25, i + 1));
        }
        for (int i = 0; i < 6; i++) {
            bm25.add(hit("w" + i, RetrievalHit.Channel.BM25, 21 + i));
        }

        List<RrfFusion.FusedHit> fused = RrfFusion.fuse(K, vector, bm25);

        assertThat(fused).as("20 + 20 去重后应为 26 条").hasSize(26);
    }

    @Test
    @DisplayName("G5-2 · 列表顺序不影响结果（两路是并行的，谁先返回不该改变融合）")
    void listOrderDoesNotMatter() {
        List<RetrievalHit> vector = List.of(
                hit("a", RetrievalHit.Channel.VECTOR, 1),
                hit("b", RetrievalHit.Channel.VECTOR, 2));
        List<RetrievalHit> bm25 = List.of(
                hit("b", RetrievalHit.Channel.BM25, 1),
                hit("c", RetrievalHit.Channel.BM25, 2));

        List<RrfFusion.FusedHit> forward = RrfFusion.fuse(K, vector, bm25);
        List<RrfFusion.FusedHit> reversed = RrfFusion.fuse(K, bm25, vector);

        assertThat(forward.stream().map(RrfFusion.FusedHit::chunkId))
                .containsExactlyElementsOf(reversed.stream().map(RrfFusion.FusedHit::chunkId).toList());
        assertThat(forward.get(0).score()).isCloseTo(reversed.get(0).score(), offset());
    }

    @Test
    @DisplayName("G5-2 · 空输入 / null 不炸，k=0 是合法取值")
    void degenerateInputs() {
        assertThat(RrfFusion.fuse(K, List.of(), List.of())).isEmpty();
        assertThat(RrfFusion.fuse(K, null, null)).isEmpty();
        // k=0 退化为纯倒数排名（1/rank），是合法口径而不是错误
        List<RrfFusion.FusedHit> fused = RrfFusion.fuse(0,
                List.of(hit("a", RetrievalHit.Channel.VECTOR, 1)), List.of());
        assertThat(fused.getFirst().score()).isCloseTo(1.0, offset());
    }

    // ==================== 构造 ====================

    private static RetrievalHit hit(String chunkId, RetrievalHit.Channel channel, int rank) {
        return score(chunkId, channel, rank, 1.0);
    }

    private static RetrievalHit score(String chunkId, RetrievalHit.Channel channel, int rank, double value) {
        return new RetrievalHit(chunkId, 1L, 1L, "PARAGRAPH", "标题", "正文", channel, value, rank);
    }

    private static org.assertj.core.data.Offset<Double> offset() {
        return org.assertj.core.data.Offset.offset(1e-9);
    }
}
