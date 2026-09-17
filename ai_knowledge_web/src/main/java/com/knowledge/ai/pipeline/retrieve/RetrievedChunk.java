package com.knowledge.ai.pipeline.retrieve;

import com.knowledge.ai.core.enums.RetrievalSource;

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
 * @param tableContext 表格回表的结果（`T-705`）；{@code null} = 这条不是表格子块，
 *                     或回表没取到父块。见 {@link TableContext}
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
                             boolean warn,
                             TableContext tableContext) {

    /**
     * 表格回表的结果（`T-705` · `G5-6`）。
     *
     * <h3>{@link #content()} 被**换掉**了，所以必须解释换成了什么</h3>
     * <p>命中一条表格子块（一行）时，真正该给大模型看的是**它所在的表**，而不是孤立的一行
     * —— 孤立的「型号 A-100，单价 1800」没有列含义，问「交期最长的型号」时也答不了。
     * 所以回表之后 {@link RetrievedChunk#content()} 是**父块（或它的行窗口）的 Markdown**，
     * 不再是那一行。
     *
     * <p>换掉内容是有代价的：**"到底哪一行命中了"这个信息会丢**。引用卡片要能高亮那一行，
     * 生成侧也不该被整表淹没。所以原始命中行的原文留在 {@link #matchedRow()} 里 ——
     * 它是**唯一没有第二个来源**的东西（父块随时能从库里再取一次，那一行不能）。
     *
     * <h3>{@link #windowed()} 为什么必须显式记，而不是靠行数推</h3>
     * <p>{@code totalRows == rowTo - rowFrom + 1} 也成立时（表恰好和窗口一样大），
     * 光看数字分不出"整表"与"窗口"。而这两件事对用户是不同的承诺：
     * 前者是"这就是全部"，后者是"还有更多，只是没给你"。前端要据此决定要不要显示
     * 「仅显示第 12~22 行 / 共 80 行」。
     *
     * @param parentChunkId 父块（{@code TABLE} 且 {@code parentId is null} 的那个）的分片 id
     * @param rowFrom       窗口起始数据行，**1 起、含**（不含表头；表头永远被带上）
     * @param rowTo         窗口结束数据行，**1 起、含**
     * @param totalRows     父块的数据行总数（不含表头）
     * @param windowed      {@code true} = 只取了行窗口；{@code false} = 返回的是整表
     * @param matchedRow    原始命中子块的内容（行式文本，**永不改写**）
     * @param note          回表过程中的非常规情况（如行数对不上而退回整表）；正常为 {@code null}
     */
    public record TableContext(long parentChunkId,
                               int rowFrom,
                               int rowTo,
                               int totalRows,
                               boolean windowed,
                               String matchedRow,
                               String note) {
    }

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
     * 这条分片**最终算来自哪一路** —— SSE {@code retrieval} 事件里每个 chunk 的
     * {@code source} 字段（`10` §9：取值 {@code vector} / {@code bm25} / {@code graph}）。
     *
     * <h3>为什么要有"算"这一步：融合之后一条可能两路都命中</h3>
     * <p>{@link #channelRanks()} 是 {@code Map<通道, 名次>}，两路都召回时它有**两个**条目；
     * 而 §9 的 {@code source} 是**单值**（前端拿它渲染引用角标上的"来源"）。所以必须定一条
     * 确定性的取法，否则同一条分片在不同请求里可能显示不同的来源。
     *
     * <p>取法是**名次更好的那一路**（并列取向量）：名次更靠前的那一路才是"先找到它"的那一路，
     * 按它归属对用户最有解释力。取"向量优先"是固定的兜底，不随名次摇摆。
     *
     * <p>⚠️ **这是一次有损投影** —— "两路都命中了"这件事实在本方法里被丢掉。
     * 需要它时（例如按"双路命中"给分加权）是**新口径**，届时改 {@link RrfFusion} 的计分，
     * 而不是在这里补第二个返回值。
     *
     * <p>{@code channelRanks} 为空（构造上不该发生 —— 它由 {@code RrfFusion} 逐条填）
     * 时返回 {@link RetrievalSource#VECTOR}：向量是主通道，退到它是所有可能里最不误导的。
     */
    public RetrievalSource source() {
        Integer vector = channelRanks.get(RetrievalHit.Channel.VECTOR);
        Integer bm25 = channelRanks.get(RetrievalHit.Channel.BM25);
        if (vector == null) {
            return bm25 == null ? RetrievalSource.VECTOR : RetrievalSource.BM25;
        }
        if (bm25 == null) {
            return RetrievalSource.VECTOR;
        }
        return vector <= bm25 ? RetrievalSource.VECTOR : RetrievalSource.BM25;
    }

    /**
     * 换掉 {@link #rank}，其余字段原样。
     *
     * <p>与 {@link RetrievalHit#withRank} 同一条理由：编号必须发生在**排序与裁剪之后**。
     * 先编号再裁剪会留下空洞（1,2,3 裁到只剩 2 条 → 1,3），而前端要按序号显示「第 N 条引用」。
     */
    public RetrievedChunk withRank(int newRank) {
        return new RetrievedChunk(chunkId, docId, kbId, blockType, titlePath, content,
                score, scoreKind, newRank, fusionRank, rerankScore, rankedScore, channelRanks, warn,
                tableContext);
    }

    /**
     * 回表结果（`T-705`）：**换掉 {@link #content()}** 并挂上 {@link TableContext}。
     *
     * <p>只此一处改写内容，且必须由 {@code TableLookup} 调用 —— 回表是"把一行的上下文补回来"，
     * 不是"再做一次检索"。别把它实现成往列表里**追加**一条父块：那会让同一次命中共出现两条
     * （一条行、一条表），引用角标指到两处，而两条的分数还一样。
     *
     * @param newContent   父块或它的行窗口 Markdown（{@link #matchedRow} 仍留在 context 里）
     * @param newTableContext 回表结果
     */
    public RetrievedChunk withTableContext(String newContent, TableContext newTableContext) {
        return new RetrievedChunk(chunkId, docId, kbId, blockType, titlePath, newContent,
                score, scoreKind, rank, fusionRank, rerankScore, rankedScore, channelRanks, warn,
                newTableContext);
    }
}
