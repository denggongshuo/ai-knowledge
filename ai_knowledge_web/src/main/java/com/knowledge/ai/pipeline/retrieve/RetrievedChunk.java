package com.knowledge.ai.pipeline.retrieve;

import java.util.Map;

/**
 * 检索结果里的一条分片（`T-704`）—— 最终喂给生成侧的东西。
 *
 * <h3>{@link #score()} 的**两种量纲**，以及为什么必须标出来</h3>
 * <p>重排成功时它是 Rerank 分数（0~1，可与 {@code missThreshold} / {@code lowScoreWarn} 比）；
 * 重排降级时它是 **RRF 融合分**（约 {@code 1/61}≈0.016 量级，**不可**与那两个阈值比）。
 * 两者同名却不同义，所以 {@link #scoreKind} 不是"给调试看的注释"，
 * 而是**下游判断该不该拿它比阈值**的唯一依据。
 *
 * <p>⚠️ 少了它会出的事：重排一降级，最高分永远低于 {@code missThreshold}，
 * 于是**每一次检索都被判成"未命中"** —— 而现象是"换了个环境后问答全走兜底话术"，
 * 没人会想到是分数换了量纲。
 *
 * @param chunkId      分片 id（MySQL {@code sys_chunk.id}）
 * @param docId        所属文档
 * @param kbId         所属知识库
 * @param blockType    块类型
 * @param titlePath    标题路径，可空
 * @param content      投影后的检索文本（引用卡片与生成侧的输入）
 * @param score        最终排序分；含义由 {@link #scoreKind} 决定
 * @param scoreKind    这个分数是**重排分**还是**融合分**
 * @param rank         最终名次，1 起
 * @param fusionRank   融合阶段的名次（重排前）；等于 {@link #rank} 表示"重排没有改变它的位置"
 * @param rerankScore  Rerank 原始分；未重排时为 {@code null}（**不是 0** —— 0 是一个合法的重排分）
 * @param rankedScore  加权后的排序分（表格加权前的重排分 × {@code tableWeight}）；
 *                     未重排时等于 {@link #score}
 * @param channelRanks 各通道的名次（1 起），见 {@link RrfFusion.FusedHit#channelRanks()}
 * @param warn         是否低于降权提示线 —— **只标黄，不过滤**（`G5-5`）
 */
public record RetrievedChunk(String chunkId,
                             long docId,
                             long kbId,
                             String blockType,
                             String titlePath,
                             String content,
                             double score,
                             ScoreKind scoreKind,
                             int rank,
                             int fusionRank,
                             Double rerankScore,
                             double rankedScore,
                             Map<RetrievalHit.Channel, Integer> channelRanks,
                             boolean warn) {

    /** {@link RetrievedChunk#score()} 的量纲。 */
    public enum ScoreKind {

        /** 重排分：0~1，**可以**与 {@code missThreshold} / {@code lowScoreWarn} 比较。 */
        RERANK("重排分"),

        /** 融合分：RRF 倒数排名之和，**不可**与阈值比较（见类注释）。 */
        FUSION("融合分");

        private final String label;

        ScoreKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 是否能拿 {@link #score()} 与全局阈值比较。 */
    public boolean scoreComparable() {
        return scoreKind == ScoreKind.RERANK;
    }

    /**
     * 换掉 {@link #rank}，其余字段原样。
     *
     * <p>与 {@link RetrievalHit#withRank} 同一条理由：编号必须发生在**排序与裁剪之后**。
     * 先编号再裁剪会留下空洞（1,2,3 裁到只剩 2 条 → 1,3），而前端要按序号显示「第 N 条引用」。
     */
    public RetrievedChunk withRank(int newRank) {
        return new RetrievedChunk(chunkId, docId, kbId, blockType, titlePath, content,
                score, scoreKind, newRank, fusionRank, rerankScore, rankedScore, channelRanks, warn);
    }
}
