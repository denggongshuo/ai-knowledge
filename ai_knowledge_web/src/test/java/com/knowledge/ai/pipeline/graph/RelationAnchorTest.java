package com.knowledge.ai.pipeline.graph;

import com.knowledge.ai.core.enums.BlockType;
import com.knowledge.ai.pipeline.Block;
import com.knowledge.ai.pipeline.enhance.IndexEnhancer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RelationAnchor} 的纯函数取证（`T-C00` 步 2b）。
 *
 * <h3>为什么这一半必须**不依赖 Spring**</h3>
 * <p>锚定逻辑是本批唯一"算出来"的东西 —— 其余都是接线与搬运。它必须能
 * 在毫秒级跑完、可穷举边界（空集 / 找不到 / SUMMARY 块 / 主客体次序）。
 * 而真跑一遍解析链路要真上传 + 真解析，几十秒起步，且覆盖不到这些分支。
 *
 * <p>⚠️ 用真实的 {@link Block} 而不是替身：它的紧凑构造器会把
 * {@code rawContent == null} 归一成空串 —— 那正是本类要依赖的行为之一
 * （见 {@code nullContentDoesNotMatch}）。
 */
class RelationAnchorTest {

    private static Block block(String content, BlockType type, int orderNo) {
        return new Block(content, type, "", orderNo, 1, null, null, null);
    }

    @Test
    @DisplayName("实体名在块文本里出现 ⇒ 锚到那个块的块序")
    void anchorsToTheBlockContainingTheSubject() {
        List<Block> blocks = List.of(
                block("订单系统采用分库分表。", BlockType.PARAGRAPH, 1),
                block("一致性哈希由 Karger 提出。", BlockType.PARAGRAPH, 2));

        List<RelationAnchor.Anchored> out = RelationAnchor.anchor(
                List.of(new IndexEnhancer.Enhancement.Relation("订单系统", "基于", "一致性哈希", 0.9)),
                blocks);

        assertThat(out).hasSize(1);
        // 主体命中第 1 块（不是客体所在的第 2 块）—— 取主体优先
        assertThat(out.getFirst().blockOrder()).isEqualTo(1);
        assertThat(out.getFirst().relation().confidence()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("主体找不到 ⇒ 退到客体；客体也找不到 ⇒ 丢弃（不硬凑）")
    void fallsBackToObjectThenDrops() {
        List<Block> blocks = List.of(
                block("一致性哈希由 Karger 提出。", BlockType.PARAGRAPH, 2));

        // 主体「订单系统」不在任何块里，客体在 ⇒ 锚到客体那块
        List<RelationAnchor.Anchored> fallback = RelationAnchor.anchor(
                List.of(new IndexEnhancer.Enhancement.Relation("订单系统", "基于", "一致性哈希", 0.9)),
                blocks);
        assertThat(fallback).hasSize(1);
        assertThat(fallback.getFirst().blockOrder()).isEqualTo(2);

        // 两个都不在 ⇒ **丢弃**。这一条是本类的核心纪律：
        // 硬凑一条会产出一条"回不到原文"的边，而它看起来是通的。
        List<RelationAnchor.Anchored> dropped = RelationAnchor.anchor(
                List.of(new IndexEnhancer.Enhancement.Relation("张三", "负责", "李四", 1.0)),
                blocks);
        assertThat(dropped).isEmpty();
    }

    @Test
    @DisplayName("SUMMARY 块不算锚点 —— 拿模型生成的摘要当\"原文\"等于自己证明自己")
    void summaryBlocksAreNotAnchorTargets() {
        List<Block> blocks = List.of(
                block("本探针文档说明订单系统与一致性哈希的关系。", BlockType.SUMMARY, 1),
                block("订单系统采用分库分表。", BlockType.PARAGRAPH, 2));

        List<RelationAnchor.Anchored> out = RelationAnchor.anchor(
                List.of(new IndexEnhancer.Enhancement.Relation("订单系统", "基于", "一致性哈希", 0.9)),
                blocks);

        assertThat(out).hasSize(1);
        // 第 1 块（SUMMARY）里含"订单系统"却**必须跳过** ⇒ 落在第 2 块
        assertThat(out.getFirst().blockOrder()).isEqualTo(2);
    }

    @Test
    @DisplayName("同名词出现在多块 ⇒ 取**首次**出现（确定、可复现）")
    void firstOccurrenceWins() {
        List<Block> blocks = List.of(
                block("无关段落。", BlockType.PARAGRAPH, 1),
                block("Milvus 是向量库。", BlockType.PARAGRAPH, 5),
                block("Milvus 支持分区。", BlockType.PARAGRAPH, 9));

        List<RelationAnchor.Anchored> out = RelationAnchor.anchor(
                List.of(new IndexEnhancer.Enhancement.Relation("Milvus", "属于", "向量库", 0.8)),
                blocks);

        assertThat(out.getFirst().blockOrder()).isEqualTo(5);
    }

    @Test
    @DisplayName("空输入与坏输入都不抛（调用方不必先判空）")
    void emptyAndNullInputsAreSafe() {
        List<Block> blocks = List.of(block("有内容。", BlockType.PARAGRAPH, 1));

        assertThat(RelationAnchor.anchor(null, blocks)).isEmpty();
        assertThat(RelationAnchor.anchor(List.of(), blocks)).isEmpty();
        assertThat(RelationAnchor.anchor(
                List.of(new IndexEnhancer.Enhancement.Relation("A", "r", "B", 1.0)), null)).isEmpty();
        assertThat(RelationAnchor.anchor(
                List.of(new IndexEnhancer.Enhancement.Relation("A", "r", "B", 1.0)), List.of())).isEmpty();
        // 空/空白实体名不该匹配到任何东西（`""` 是每个字符串的子串 —— 不挡就会锚到第 1 块）
        assertThat(RelationAnchor.anchor(
                List.of(new IndexEnhancer.Enhancement.Relation("   ", "r", "  ", 1.0)), blocks)).isEmpty();
    }

    @Test
    @DisplayName("块的 rawContent 为 null 时不炸（紧凑构造器归一成空串）")
    void nullContentDoesNotMatch() {
        List<Block> blocks = List.of(block(null, BlockType.PARAGRAPH, 1));

        assertThat(RelationAnchor.anchor(
                List.of(new IndexEnhancer.Enhancement.Relation("A", "r", "B", 1.0)), blocks)).isEmpty();
    }

    @Test
    @DisplayName("多条关系 ⇒ 顺序与输入一致（便于与模型输出逐条对账）")
    void orderIsPreserved() {
        List<Block> blocks = List.of(
                block("甲与乙相关。", BlockType.PARAGRAPH, 1),
                block("丙与丁相关。", BlockType.PARAGRAPH, 2));

        List<RelationAnchor.Anchored> out = RelationAnchor.anchor(List.of(
                new IndexEnhancer.Enhancement.Relation("丙", "r", "丁", 0.7),
                new IndexEnhancer.Enhancement.Relation("甲", "r", "乙", 0.9)), blocks);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).relation().subject()).isEqualTo("丙");
        assertThat(out.get(1).relation().subject()).isEqualTo("甲");
    }
}
