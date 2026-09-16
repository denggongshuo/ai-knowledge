package com.knowledge.ai.core.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-506` 标识符保护分词器的取证（`G4-8` 的**口径侧**）。
 *
 * <h3>⚠️ `G4-8` 只有一半能在这里验</h3>
 * <p>`G4-8` 的判据原话是「**搜索** `A-100` 不命中 `A-1000`」。那需要一个**检索接口** ——
 * 而 S4 没有（`T-700` 属 M07/S5，见 `01-业务模块拆分.md:237`）。
 * 所以本类验的是**口径侧**：`A-100` 与 `A-1000` 是**两个不同的 token**
 * （而不是共享一个 `A`）。运行时侧（真的搜不出对方）与 S5 计划**合并验收**。
 *
 * <h3>这条用例的锚点取自需求原文，不是我自己编的</h3>
 * <p>`07:501`（Q-4 定稿）给了四个例子，本类逐字断言它们：
 * {@code 订单系统分片方案} = 8 · {@code Milvus 分片扩容方案} = 7 ·
 * {@code 如何扩容} = 4 · {@code Milvus 扩容} = 3。
 * 用需求里的数字当判据，是为了让"口径被改动了"和"实现写错了"这两件事
 * 在同一个地方响 —— 自己编的例子只能证明自己的实现符合自己的预期。
 */
class IdentifierTokenizerTest {

    // ==================================================================
    // G4-8 口径侧：标识符整体成 token
    // ==================================================================

    @Test
    @DisplayName("G4-8：A-100 是 **1 个** token，不是 A + 100 —— 这是整条需求的起点")
    void modelNumberIsOneToken() {
        assertThat(IdentifierTokenizer.tokenize("A-100"))
                .as("`07` §3.2 的原话：标准分词会把它切成 A / 100，于是 A-1000 也命中。"
                        + "整体成 token 是这层防护的第一个动作")
                .containsExactly("A-100");
    }

    @Test
    @DisplayName("G4-8：A-100 与 A-1000 **没有任何共同 token** —— 差一个字符必须能分辨")
    void modelNumbersShareNoToken() {
        List<String> short100 = IdentifierTokenizer.tokenize("A-100");
        List<String> long1000 = IdentifierTokenizer.tokenize("A-1000");

        assertThat(short100).isNotEqualTo(long1000);
        assertThat(short100)
                .as("共享 token 是误命中的根因。`A` 只要出现在两边，BM25 就会把两者算成相关 —— "
                        + "这条断言就是「不共享」本身，比断言 token 数更贴近它要防的东西")
                .doesNotContainAnyElementsOf(long1000);
    }

    @Test
    @DisplayName("G4-8：五类标识符逐条整体成 token（07 §3.3 的原文示例）")
    void allFiveIdentifierKindsAreWhole() {
        assertThat(IdentifierTokenizer.tokenize("SKU-8890-A"))
                .as("字母数字编号（不只 A-100 这种短的）").containsExactly("SKU-8890-A");
        assertThat(IdentifierTokenizer.tokenize("￥1,200"))
                .as("金额：逗号不在词字符集里，不为它单开规则就会被切成 1 与 200")
                .containsExactly("￥1,200");
        assertThat(IdentifierTokenizer.tokenize("$1,500.00"))
                .as("金额带小数").containsExactly("$1,500.00");
        assertThat(IdentifierTokenizer.tokenize("2026-09-11"))
                .as("日期（被「字母数字编号」那条覆盖）").containsExactly("2026-09-11");
        assertThat(IdentifierTokenizer.tokenize("2026/09/11"))
                .as("日期的另一种写法").containsExactly("2026/09/11");
        assertThat(IdentifierTokenizer.tokenize("v1.4"))
                .as("版本号：这里的点是标识符的一部分").containsExactly("v1.4");
        assertThat(IdentifierTokenizer.tokenize("10021"))
                .as("错误码：数字串整体一个 token（上下文条件见类 javadoc 的口径取舍）")
                .containsExactly("10021");
    }

    @Test
    @DisplayName("句末的点**不**被吸进 token —— 与 v1.4 里的点只差「后面跟不跟字母数字」")
    void trailingPeriodIsNotPartOfToken() {
        assertThat(IdentifierTokenizer.tokenize("详见 v1.4."))
                .as("尾部若吞掉句号，token 变成 v1.4. —— 而 `v1.4` 与 `v1.4.` 在检索里就是两个词")
                .containsExactly("详", "见", "v1.4");
        assertThat(IdentifierTokenizer.tokenize("end. Next"))
                .as("英文句末同理").containsExactly("end", "Next");
    }

    // ==================================================================
    // Q-4 长度口径（07:501 的四个例子逐字）
    // ==================================================================

    @Test
    @DisplayName("Q-4：需求给的四个例子逐字对上（8 / 7 / 4 / 3）")
    void q4ExamplesMatchRequirementsVerbatim() {
        assertThat(IdentifierTokenizer.count("订单系统分片方案"))
                .as("8 —— 8 个汉字，触发图谱旁路（阈值 ≥ 6）").isEqualTo(8);
        assertThat(IdentifierTokenizer.count("Milvus 分片扩容方案"))
                .as("7 —— Milvus 算 1，不是 6 个字母").isEqualTo(7);
        assertThat(IdentifierTokenizer.count("如何扩容"))
                .as("4 —— 不触发").isEqualTo(4);
        assertThat(IdentifierTokenizer.count("Milvus 扩容"))
                .as("3 —— 不触发").isEqualTo(3);
    }

    @Test
    @DisplayName("Q-4：空格与标点不贡献长度（否则上面四个数字全都对不上）")
    void whitespaceAndPunctuationDoNotCount() {
        assertThat(IdentifierTokenizer.count("Milvus  扩容"))
                .as("多几个空格不该改变长度").isEqualTo(3);
        assertThat(IdentifierTokenizer.count("如何扩容？"))
                .as("问号不是 token").isEqualTo(4);
        assertThat(IdentifierTokenizer.count("A-100，单价 ￥1,200。"))
                .as("4 个 token：两个标识符各算 1，中间两个汉字各算 1；全角逗号与句号不算")
                .isEqualTo(4);
        assertThat(IdentifierTokenizer.tokenize("A-100，单价 ￥1,200。"))
                .as("把 4 逐个列出来 —— 只断言总数的话，谁被漏算、谁被多算都看不出来")
                .containsExactly("A-100", "单", "价", "￥1,200");
    }

    @Test
    @DisplayName("count 就是 tokenize().size() —— 一份规则两个出口，不许各写一套")
    void countIsExactlyTokenizeSize() {
        String[] samples = {
                "订单系统分片方案", "Milvus 分片扩容方案", "A-100", "", "  ", "2026-09-11",
                "如何扩容？", "SKU-8890-A 的单价是 ￥1,500.00", "Ｍｉｌｖｕｓ　１００",
        };
        for (String s : samples) {
            assertThat(IdentifierTokenizer.count(s))
                    .as("「%s」的 count 必须等于它切出的 token 数 —— "
                            + "两套规则一旦漂移，只会表现为某个地方的分片窗口悄悄偏了，没人看得出来", s)
                    .isEqualTo(IdentifierTokenizer.tokenize(s).size());
        }
    }

    // ==================================================================
    // 窗口计量：与 count 必须分开的那条理由
    // ==================================================================

    @Test
    @DisplayName("★ 窗口计量：无分隔符长串**不能**算 1 个 token —— 否则硬切兜底永不触发")
    void windowTokensDoesNotCollapseLongRuns() {
        String longRun = "A".repeat(4000);

        assertThat(IdentifierTokenizer.count(longRun))
                .as("Q-4 口径下它确实就是 1（连续 ASCII 词整体算 1）—— 这对提问是对的")
                .isEqualTo(1);
        assertThat(IdentifierTokenizer.windowTokens(longRun))
                .as("但拿 1 去当窗口判据，「超过窗口就切断」永不触发，"
                        + "分片会产出一个 4000 字符的块去撞嵌入模型 —— "
                        + "而硬切兜底的**全部意义**就是防止这件事。"
                        + "把 estimateTokens 改成委托 count 时，"
                        + "ChunkingServiceTest#textWithoutSeparatorsIsHardSplit 当场转红")
                .isGreaterThan(100);
    }

    @Test
    @DisplayName("窗口计量：正常文本上两者很接近（不是换了一套完全不同的口径）")
    void windowTokensTracksCountOnNormalText() {
        String normal = "订单系统分片方案 Milvus 扩容方案 A-100 单价 ￥1,200。";

        assertThat(IdentifierTokenizer.windowTokens(normal))
                .as("正常文本里没有超长 token，窗口计量应当与长度口径同一个量级 —— "
                        + "若这里差出好几倍，说明除数选错了，而不是「两种口径本就不同」")
                .isBetween((double) IdentifierTokenizer.count(normal),
                        IdentifierTokenizer.count(normal) * 1.5);
        assertThat(IdentifierTokenizer.windowTokens("订单系统分片方案"))
                .as("中日韩仍是 1 字 1 token（与 Q-4 的 8 一致）").isEqualTo(8);
    }

    // ==================================================================
    // 边界
    // ==================================================================

    @Test
    @DisplayName("边界：空与 null 返回空列表，不是 null（调用方不必判空）")
    void emptyInputsAreSafe() {
        assertThat(IdentifierTokenizer.tokenize(null)).isEmpty();
        assertThat(IdentifierTokenizer.tokenize("")).isEmpty();
        assertThat(IdentifierTokenizer.count(null)).isZero();
        assertThat(IdentifierTokenizer.count("   ")).isZero();
    }

    @Test
    @DisplayName("边界：全角字母数字不被丢掉（中文文档里写 １００／Ｍｉｌｖｕｓ 是常见写法）")
    void fullWidthAlnumSurvive() {
        assertThat(IdentifierTokenizer.tokenize("１００"))
                .as("全角数字若不算「词字符」，会被当标点**整个丢掉** —— "
                        + "那就是本仓最忌讳的「少了一点东西而没人发现」")
                .containsExactly("１００");
        assertThat(IdentifierTokenizer.tokenize("Ｍｉｌｖｕｓ"))
                .as("全角拉丁词同理").containsExactly("Ｍｉｌｖｕｓ");
        assertThat(IdentifierTokenizer.tokenize("Ｍｉｌｖｕｓ"))
                .as("⚠️ 刻意**不做**全角半角归一：`Ｍｉｌｖｕｓ` 与 `Milvus` 是两个 token。"
                        + "归一要连标点一起动，顺手做半个比不做更难排查")
                .isNotEqualTo(IdentifierTokenizer.tokenize("Milvus"));
    }

    @Test
    @DisplayName("边界：中日韩覆盖到日文与韩文，且 1 字 = 1")
    void cjkRangesCoverJapaneseAndKorean() {
        assertThat(IdentifierTokenizer.count("ひらがな")).as("平假名 4 字").isEqualTo(4);
        assertThat(IdentifierTokenizer.count("カタカナ")).as("片假名 4 字").isEqualTo(4);
        assertThat(IdentifierTokenizer.count("한글")).as("韩文 2 字").isEqualTo(2);
    }

    @Test
    @DisplayName("边界：孤零零的货币符号不是金额（后面没数字就当标点丢掉）")
    void loneCurrencySymbolIsNotAnAmount() {
        assertThat(IdentifierTokenizer.tokenize("价格 ￥ 待定"))
                .as("`￥` 后面没有数字时若仍成一个 token，检索里会多出一个纯符号词")
                .containsExactly("价", "格", "待", "定");
        assertThat(IdentifierTokenizer.tokenize("￥1200"))
                .as("紧跟数字就是金额（不要求千分位）").containsExactly("￥1200");
    }
}
