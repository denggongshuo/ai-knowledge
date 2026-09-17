package com.knowledge.ai.pipeline.retrieve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF 融合（`T-701`）—— **倒数排名**融合，不做分数相加。
 *
 * <h3>为什么是倒数排名，而不是把两路分数加起来</h3>
 * <p>这是 `G5-2` 的判据，也是整条检索链路里最容易写错的一处：
 * <ul>
 *   <li>向量那一路的"分"是 **L2 距离**（越小越近），BM25 那一路是**相关性**（越大越好）。
 *       两者**符号方向相反、量纲无关**；</li>
 *   <li>即使方向一致，它们的**分布也毫无可比性**：一次 L2 距离的 0.3 与一次 BM25 的 0.3
 *       说的根本不是一回事。相加等于给"哪一路的分数量级大"投票，而不是给"哪一段更相关"投票；</li>
 *   <li>而**排名**是两路**共有**的、量纲无关的东西。倒数排名只用它。</li>
 * </ul>
 * <p>⚠️ 所以本类的输入里，{@link RetrievalHit#score()} **一次都没被读过**。
 * 这不是遗漏 —— 读过它就说明有人想按分数加权，那是另一个算法（并且要先解决上面三条）。
 * 用例里有一条**负向断言**：把实现改成分数相加，`RrfFusionTest` 必须转红。
 *
 * <h3>公式</h3>
 * <pre>
 *   score(chunk) = Σ_channel  1 / (k + rank_channel(chunk))
 * </pre>
 * <p>{@code rank} **1 起**（见 {@link RetrievalHit#rank()}），故 {@code rank=1} 时贡献
 * {@code 1/(k+1)}。计划 §3.1 写的 {@code 1/(k+rank+1)} 用的是 0 起排名，两者是同一个式子。
 *
 * <p>{@code k} 取 {@link #DEFAULT_K} = 60（RRF 原论文的取值）。它的作用是**压平头部**：
 * {@code k} 越大，第 1 名与第 10 名的贡献越接近，融合结果越取决于"两路都进了"而不是
 * "单路排第一"。调它有实际影响，但**本批不调** —— 调融合常数要有评测集（`T-711`）。
 *
 * <h3>去重键是 chunkId</h3>
 * <p>同一个分片被两路都召回到是**常态**（正是混合检索的价值所在），它在融合池里
 * **只能占一个位置**。按 {@code chunkId} 去重后，两路的排名**都**计入分数 ——
 * 于是"两路都进了前 10"的分片会自然浮到"只有一路进了前 10"的前面，
 * 这就是 RRF 相对单路召回的增益来源。
 */
public final class RrfFusion {

    /**
     * 排名平滑常数 {@code k}。见类注释：本批固定 60，不配置化。
     */
    public static final int DEFAULT_K = 60;

    private RrfFusion() {
    }

    /**
     * 融合若干路召回结果。
     *
     * @param k    平滑常数，见 {@link #DEFAULT_K}；小于 0 时按 0 处理（{@code k=0} 退化为
     *             "纯倒数排名"，是合法取值，不是一个"错误"）
     * @param lists 各路结果。**列表顺序不影响结果**（求和可交换）——
     *              这一点是刻意的：两路是并行的，谁先返回不该改变融合结果
     * @return 按融合分**降序**的命中，{@code rank} 从 1 起；分数相同时按
     *         "最先进入融合池"稳定排序（见 {@link #fuse} 的实现说明）
     */
    @SafeVarargs
    public static List<FusedHit> fuse(int k, List<RetrievalHit>... lists) {
        int smooth = Math.max(0, k);

        // LinkedHashMap：插入顺序 = 首次出现的顺序，用来做同分时的稳定排序。
        // 用 HashMap 的话，同分的两条在两次调用里可能换位 —— 那会让"同样的输入两次
        // 结果不同"，是最难查的一类问题（尤其是分页或缓存之后）
        Map<String, Accumulator> pool = new LinkedHashMap<>();

        if (lists != null) {
            for (List<RetrievalHit> hits : lists) {
                if (hits == null) {
                    continue;
                }
                for (RetrievalHit hit : hits) {
                    pool.computeIfAbsent(hit.chunkId(), id -> new Accumulator(hit))
                            .add(hit, smooth);
                }
            }
        }

        List<Accumulator> ordered = new ArrayList<>(pool.values());
        // 同分时的次序 = 首次进融合池的次序。**显式写进比较器**，不靠 List.sort 的稳定性 ——
        // 它今天稳，但把"结果可复现"押在库的排序实现上，是没人会去复核的那种依赖
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).order = i;
        }
        ordered.sort(Comparator.comparingDouble(Accumulator::score).reversed()
                .thenComparingInt(a -> a.order));

        List<FusedHit> out = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            out.add(ordered.get(i).toHit(i + 1));
        }
        return List.copyOf(out);
    }

    /** 便捷重载：用 {@link #DEFAULT_K}。 */
    @SafeVarargs
    public static List<FusedHit> fuse(List<RetrievalHit>... lists) {
        return fuse(DEFAULT_K, lists);
    }

    /**
     * 融合中的一条命中（`T-700` 的融合池条目）。
     *
     * @param chunkId      分片 id（去重键）
     * @param docId        所属文档
     * @param kbId         所属知识库
     * @param blockType    块类型（Rerank 加权与结果展示要用）
     * @param titlePath    标题路径，可空
     * @param content      投影后的检索文本（Rerank 的输入）
     * @param score        RRF 融合分（**不是**任何一路的原始分，不可与阈值比较）
     * @param rank         融合后的排名，1 起
     * @param channelRanks 每个通道各自的排名（**1 起**）；没进那一路则**无此键**。
     *                     它让"这条为什么排在前面"可解释 —— 见类注释的增益来源
     */
    public record FusedHit(String chunkId,
                           long docId,
                           long kbId,
                           String blockType,
                           String titlePath,
                           String content,
                           double score,
                           int rank,
                           Map<RetrievalHit.Channel, Integer> channelRanks) {

        /** 是否两路都召回到了（混合检索真正的增益区）。 */
        public boolean fromBothChannels() {
            return channelRanks.size() > 1;
        }

        /** 该分片在某一路的名次；没进那一路返回 {@code 0}。 */
        public int rankIn(RetrievalHit.Channel channel) {
            return channelRanks.getOrDefault(channel, 0);
        }
    }

    /**
     * 融合累加器。**持有首条命中的 payload** —— 同一分片在两路里的正文必然相同
     * （同一个 Milvus 主键、同一次写入），取先到的即可。
     */
    private static final class Accumulator {

        private final RetrievalHit first;

        /** 首次进融合池的次序，由 {@link #fuse} 在排序前统一回填（同分时的稳定键）。 */
        private int order;

        private double score;

        private final Map<RetrievalHit.Channel, Integer> ranks = new LinkedHashMap<>();

        Accumulator(RetrievalHit first) {
            this.first = first;
        }

        void add(RetrievalHit hit, int k) {
            score += 1.0 / (k + hit.rank());
            // 同一路里出现两次是上游的 bug（Milvus 一个主键只回一条），保留**最好**的名次
            ranks.merge(hit.channel(), hit.rank(), Math::min);
        }

        double score() {
            return score;
        }

        FusedHit toHit(int rank) {
            return new FusedHit(first.chunkId(), first.docId(), first.kbId(), first.blockType(),
                    first.titlePath(), first.content(), score, rank, Map.copyOf(ranks));
        }
    }
}
