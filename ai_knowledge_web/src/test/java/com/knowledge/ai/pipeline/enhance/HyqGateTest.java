package com.knowledge.ai.pipeline.enhance;

import com.knowledge.ai.core.enums.BlockType;
import com.knowledge.ai.pipeline.Block;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HyQ 门控（`T-O09` 步 5）的规则表。
 *
 * <h3>为什么单独一个类、且不启动容器</h3>
 * <p>门控是这套方案里**最需要被反复调整**的部分（`O9` §7 的风险条写着"门控可能砍掉
 * 真正有用的 HyQ，必须先有基线 + A/B 用数据定规则"）。做成纯函数 + 纯用例，
 * 意味着调阈值时**改一行、跑一秒就有结论**；而它一旦需要启动 Spring 容器，
 * 每次调参就要等十几秒，实际结果是没人愿意调。
 *
 * <h3>⚠️ 本类证明不了的事</h3>
 * <p>它只证明**规则按写的那样生效**，证明不了"这样切分召回质量没退化"——
 * 后者要真模型跑 A/B，属挂起项（见计划 §6+7 判据 11 与 §9）。
 * 别把"门控按预期工作"读成"门控是划算的"。
 */
class HyqGateTest {

    @Test
    @DisplayName("表格父块放行 —— 整表参数密集，假设问句收益最高")
    void tableParentIsGatedIn() {
        Block table = block(BlockType.TABLE, "| 型号 | 单价 |\n| A-100 | 1800 |", null);

        assertThat(HyqGate.shouldGenerate(table)).isTrue();
        assertThat(HyqGate.reason(table)).contains("表格父块");
    }

    @Test
    @DisplayName("表格**子块**跳过 —— 一行是一条记录，为它单独花一次调用不划算")
    void tableChildIsGatedOut() {
        // 子块 = parentOrderNo 非 null（TABLE 类型靠父子区分，见 Block#isTopLevel）
        Block row = new Block("型号 A-100，单价 1800", BlockType.TABLE, "1. 报价", 5, 1, 3, null);

        assertThat(HyqGate.shouldGenerate(row)).isFalse();
    }

    @Test
    @DisplayName("判据12：短段落含**参数**放行 —— 「令牌有效期 2 小时」这种最适合生成事实型问句")
    void shortParagraphWithParameterIsGatedIn() {
        Block para = block(BlockType.PARAGRAPH, "接口鉴权采用令牌方式，令牌有效期 2 小时。", null);

        assertThat(HyqGate.shouldGenerate(para)).isTrue();
        assertThat(HyqGate.reason(para)).contains("参数");
    }

    @Test
    @DisplayName("判据12：短段落含**问句特征**放行 —— 它本身就长得像用户会问的话")
    void shortParagraphWithQuestionShapeIsGatedIn() {
        Block para = block(BlockType.PARAGRAPH, "那么令牌过期后如何续期？", null);

        assertThat(HyqGate.shouldGenerate(para)).isTrue();
        assertThat(HyqGate.reason(para)).contains("问句特征");
    }

    @Test
    @DisplayName("判据12：含步骤的列表放行（D-5 补齐 LIST 块后自动生效）")
    void listWithStepsIsGatedIn() {
        Block list = block(BlockType.LIST, "第一步，申请令牌。第二步，用令牌调用接口。", null);

        assertThat(HyqGate.shouldGenerate(list)).isTrue();
        assertThat(HyqGate.reason(list)).contains("步骤");
    }

    @Test
    @DisplayName("判据11：长叙述段落**跳过** —— 门控省钱的主力，一次调用都不产生")
    void longNarrativeParagraphIsGatedOut() {
        Block longPara = block(BlockType.PARAGRAPH,
                "本系统在设计之初就充分考虑了扩展性与可维护性。".repeat(20), null);

        assertThat(longPara.rawContent().length())
                .as("固件本身要够长，否则这条用例在空断言上通过")
                .isGreaterThan(HyqGate.SHORT_PARAGRAPH_MAX_CHARS);
        assertThat(HyqGate.shouldGenerate(longPara)).isFalse();
        assertThat(HyqGate.reason(longPara)).isNull();
    }

    @Test
    @DisplayName("短但**无特征**的段落也跳过 —— 「本节结束」这种生成不出有价值的问句")
    void shortParagraphWithoutFeaturesIsGatedOut() {
        Block para = block(BlockType.PARAGRAPH, "本节到此结束，感谢阅读。", null);

        assertThat(HyqGate.shouldGenerate(para)).isFalse();
    }

    @Test
    @DisplayName("标题、摘要、HyQ 自身都不生成 —— 为标题写问句是噪声，为摘要写是重复花钱")
    void headingsAndSummariesAreGatedOut() {
        assertThat(HyqGate.shouldGenerate(block(BlockType.HEADING, "3. 接口设计", null))).isFalse();
        assertThat(HyqGate.shouldGenerate(block(BlockType.SUMMARY, "本文介绍了接口设计。", null))).isFalse();
        assertThat(HyqGate.shouldGenerate(block(BlockType.HYPOTHESIS_QA, "令牌多久过期？", null))).isFalse();
    }

    @Test
    @DisplayName("空内容的块跳过 —— 不值得为一段空文本花一次调用")
    void emptyBlockIsGatedOut() {
        assertThat(HyqGate.shouldGenerate(block(BlockType.PARAGRAPH, "   ", null))).isFalse();
        assertThat(HyqGate.shouldGenerate(null)).isFalse();
    }

    @Test
    @DisplayName("门控输出的是**理由**而不只是布尔 —— 「省了 200 次调用」要能回答「省掉了什么」")
    void everyAdmittedBlockExplainsWhy() {
        for (BlockType type : BlockType.values()) {
            Block candidate = block(type, "第一步，把 A-100 的单价核对一遍。", null);
            if (HyqGate.shouldGenerate(candidate)) {
                assertThat(HyqGate.reason(candidate))
                        .as("%s 被放行了，就必须说得出为什么", type)
                        .isNotBlank();
            }
        }
    }

    private static Block block(BlockType type, String text, Integer parentOrderNo) {
        return new Block(text, type, "3. 接口设计", 1, 1, parentOrderNo, null);
    }
}