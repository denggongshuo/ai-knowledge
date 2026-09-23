package com.knowledge.ai.pipeline.graph;

import com.knowledge.ai.pipeline.Block;
import com.knowledge.ai.pipeline.enhance.IndexEnhancer;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一条 {@link IndexEnhancer.Enhancement.Relation} **锚到一个分片**上（`T-C00` 步 2b）。
 *
 * <h3>为什么需要这一步</h3>
 * <p>`FR-GRAPH-01` 的设计底线之一是「**每条关系带 {@code sourceChunkId} → 可溯源回原文**」
 * （`docs/需求文档/07-需求-检索与问答.md:469`）。而模型返回的关系**只有三个词**
 * （subject / predicate / object）—— 它不知道、也没法知道我们把它切成了哪些块。
 * 于是「这条关系出自哪个分片」必须**由我们算出来**。
 *
 * <h3>判据：实体名在块文本里的**首次出现**</h3>
 * <p>优先按 {@code subject} 找，找不到再按 {@code object} 找（主体通常比客体更贴近
 * 关系的陈述位置）。取**首次**出现而不是"最相关的那块"：后者需要一个相关度定义，
 * 而那属于 `T-C01`（实体链接）的射程；这里要的只是**一个可回源的落点**，
 * 越确定越好。块序取 {@code orderNo}，与 {@code sys_chunk.order_no} 同口径。
 *
 * <h3>🔴 找不到就**丢边并留痕**，绝不硬凑</h3>
 * <p>这与 {@code IndexEnhancer#appendChapterSummaries} 是同一条判例
 * （那边是"章节标题在块集里找不到 ⇒ 跳过该章"）：**硬凑一条会产出一条
 * 「挂不上任何分片」的边**，它在检索侧会以"某条关系"的形态出现，
 * 却回不到任何原文 —— 那比少一条更糟，因为它**看起来是通的**。
 *
 * <p>⚠️ 代价照实说：这会丢掉一部分关系（模型改写实体名、或实体名只出现在摘要里）。
 * 锚定率是本批的**实测观察项**（计划 §3.4 假设 1），过低则图谱会稀疏到没有多跳价值。
 *
 * <h3>为什么锚点用「块序」而不是「chunk.id」</h3>
 * <p>因为本方法跑在 {@code replaceChunks} **之前**（那时还没有 id）。块序是块集自带的、
 * 落库时原样写进 {@code sys_chunk.order_no} 的字段 ⇒ 落库后按
 * {@code (doc_id, order_no)} 一次回查就能换到真 id。多一次查询，换来的是
 * **锚定逻辑完全不需要知道数据库**（可纯单测，不依赖 Spring）。
 */
public final class RelationAnchor {

    private RelationAnchor() {
    }

    /**
     * 锚定结果：关系 + 它落在哪个块序上。
     *
     * @param relation   原样带过来（不裁剪、不改写）
     * @param blockOrder 命中的块序（{@code Block#orderNo()}，与 {@code sys_chunk.order_no} 同口径）
     */
    public record Anchored(IndexEnhancer.Enhancement.Relation relation, int blockOrder) {
    }

    /**
     * 锚定一批关系。
     *
     * @param relations 模型抽出的关系（可为空 —— 调用方不必先判）
     * @param blocks    块集（**增强之后、落库之前**；SUMMARY 块会被跳过，理由见下）
     * @return 锚上的那些（**顺序与输入一致**，便于对账）
     */
    public static List<Anchored> anchor(List<IndexEnhancer.Enhancement.Relation> relations,
                                        List<Block> blocks) {
        if (relations == null || relations.isEmpty() || blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<Anchored> out = new ArrayList<>(relations.size());
        for (IndexEnhancer.Enhancement.Relation relation : relations) {
            int order = firstBlockContaining(blocks, relation.subject());
            if (order < 0) {
                order = firstBlockContaining(blocks, relation.object());
            }
            if (order >= 0) {
                out.add(new Anchored(relation, order));
            }
        }
        return List.copyOf(out);
    }

    /**
     * 第一个**原文里**含该词的块序；找不到返回 {@code -1}。
     *
     * <p>⚠️ 跳过 {@code SUMMARY} 块：摘要**是我们生成的**，实体名当然会在里面出现 ——
     * 拿它当锚点等于"自己证明自己"，回源后用户看到的是一段模型写的文字而不是原文，
     * 而需求 `:469` 要的恰恰是**回原文**。
     */
    private static int firstBlockContaining(List<Block> blocks, String needle) {
        if (needle == null || needle.isBlank()) {
            return -1;
        }
        String target = needle.strip();
        for (Block block : blocks) {
            if (block.blockType() == com.knowledge.ai.core.enums.BlockType.SUMMARY) {
                continue;
            }
            String text = block.rawContent();
            if (text != null && text.contains(target)) {
                return block.orderNo();
            }
        }
        return -1;
    }
}
