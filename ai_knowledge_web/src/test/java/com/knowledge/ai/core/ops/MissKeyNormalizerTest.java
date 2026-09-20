package com.knowledge.ai.core.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 未命中聚合键（`T-A00` · `R-6` 本批做的那一半）。
 *
 * <h3>这个类的判据为什么必须**成对**写</h3>
 * <p>归一化的两个方向都会出错，而且**错的方向相反**：
 * <ul>
 *   <li>**合得太少**（该合的没合）⇒ 提问次数偏小，排序失真 —— 后果轻；</li>
 *   <li>**合得太多**（不该合的合了）⇒ 两个不同的问题被算成"很多人问"，
 *       清单上就会出现一行**根本不存在的热点问题**，人按它去补文档会白干。</li>
 * </ul>
 * <p>所以每一条"必须相同"都配一条"必须不同"。只有前者的话，
 * 把 {@code normalize} 写成 `return ""`（全部塌成一行）也能全绿。
 */
class MissKeyNormalizerTest {

    // ==================== 必须相同（该合的要合）====================

    @Test
    @DisplayName("标点 / 空白 / 大小写 / 全半角 —— 四个都必须合并")
    void noiseIsMerged() {
        assertSame("标点不影响问的是哪件事",
                "报销要几天？", "报销要几天", "报销要几天！！！", "报销 要 几天...");
        assertSame("英文大小写不承载语义",
                "Milvus 支持多少维", "milvus 支持多少维", "MILVUS 支持多少维");
        assertSame("全角与半角是同一句话的两种写法（NFKC）",
                "SQL 递归 CTE 几跳退化", "ＳＱＬ 递归 ＣＴＥ 几跳退化");
        assertSame("零宽字符是看不见的噪声，不能进键",
                "合同违约金上限", "合同违约​金上限");
    }

    // ==================== 必须不同（不该合的别合）====================

    @Test
    @DisplayName("不同的问题绝不能合并 —— 错合并比漏合并坏得多")
    void differentQuestionsStayApart() {
        assertDifferent("只差一个字也是两个问题",
                "合同的违约金上限是多少", "合同的违约金下限是多少");
        assertDifferent("词序不同不合并（本批刻意不做词序归一）",
                "A 与 B 的差异", "B 与 A 的差异");
        assertDifferent("同义词不合并（那是语义，不是归一化）",
                "怎么申请年假", "如何申请年假");
        assertDifferent("数字不同不是同一个问题",
                "A-100 的单价", "A-1000 的单价");
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("全标点的问句不能塌成同一行 —— 退化成抹平空白后的原文")
    void punctuationOnlyFallsBackToRaw() {
        assertThat(MissKeyNormalizer.keyOf("？？？"))
                .as("归一化后为空 ⇒ 退回原文，否则所有纯标点问句会合成一行")
                .isNotEqualTo(MissKeyNormalizer.keyOf("！！！"));
        assertThat(MissKeyNormalizer.keyOf("！！！"))
                .as("全角与半角仍是同一句")
                .isEqualTo(MissKeyNormalizer.keyOf("!!!"));
    }

    @Test
    @DisplayName("键是定长十六进制；null / 空串不抛")
    void keyShapeIsStable() {
        assertThat(MissKeyNormalizer.keyOf("报销要几天")).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(MissKeyNormalizer.keyOf(null)).isEqualTo(MissKeyNormalizer.keyOf(""));
        assertThat(MissKeyNormalizer.normalize(null)).isEmpty();
    }

    // ==================== 夹具 ====================

    private static void assertSame(String why, String... questions) {
        String first = MissKeyNormalizer.keyOf(questions[0]);
        for (String q : questions) {
            assertThat(MissKeyNormalizer.keyOf(q))
                    .as("%s：'%s' 应与 '%s' 合并", why, q, questions[0])
                    .isEqualTo(first);
        }
    }

    private static void assertDifferent(String why, String a, String b) {
        assertThat(MissKeyNormalizer.keyOf(a))
                .as("%s：'%s' 与 '%s' 不能合并", why, a, b)
                .isNotEqualTo(MissKeyNormalizer.keyOf(b));
    }
}
