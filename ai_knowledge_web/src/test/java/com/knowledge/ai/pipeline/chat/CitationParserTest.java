package com.knowledge.ai.pipeline.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角标解析（`T-804` · 需求 `07` §6.6）。
 *
 * <h3>为什么这个类不需要 Spring 上下文</h3>
 * <p>{@link CitationParser} 是**纯函数**（正文 + 资料条数 → 编号集合）。把它接进
 * 真库真检索上只会让"解析规则"这条判据被别的东西掩盖 —— 而那正是本批最容易
 * 悄悄错掉的一环（解析为空 → `cited` 全 0 → 「引用数」从"没采集"变成"确实没有"）。
 *
 * <h3>🔴 负向锚为什么必须在这里</h3>
 * <p>「认得出的认」这件事，正向用例过了不代表"认不出"的处理是对的。
 * 下面三条负向锚各自钉住一种**错法**：
 * <ul>
 *   <li>{@link #outOfRangeMarkerIsDroppedNotClamped()} —— 越界角标被"就近取一个"；</li>
 *   <li>{@link #titlePathBracketsAreNotMarkers()} —— 资料块里的标题路径被当成角标；</li>
 *   <li>{@link #noMarkerYieldsEmptyNotZero()} —— 解析不出时返回 {@code {0}} 或 {@code null}。</li>
 * </ul>
 */
class CitationParserTest {

    @Test
    @DisplayName("单个角标")
    void singleMarker() {
        assertThat(CitationParser.parse("单价是 1800 元[1]。", 3)).containsExactly(1);
    }

    @Test
    @DisplayName("多个角标：按**出现顺序**去重，不是按数字排序")
    void multipleMarkersKeepFirstSeenOrder() {
        Set<Integer> ranks = CitationParser.parse("先说 A[3]，再说 B[1]，最后两条一起[2][3]。", 5);
        assertThat(ranks)
                .as("`[3]` 先出现 ⇒ 它在前面。用 `TreeSet` 排序会让这个顺序恒为升序，"
                        + "而下游 `ChatWireMapper#citations` 本来就会自己排序 —— 两处都排等于白排，"
                        + "但**解析器自己**该如实反映正文顺序")
                .containsExactly(3, 1, 2);
    }

    @Test
    @DisplayName("重复角标只算一次（`cited` 是布尔列，不是计数列）")
    void repeatedMarkerCountsOnce() {
        assertThat(CitationParser.parse("[1] 前半段。[1] 后半段还是这句。[1]", 2)).containsExactly(1);
    }

    @Test
    @DisplayName("🔴 负向锚：越界角标**丢掉**，不许就近取一个")
    void outOfRangeMarkerIsDroppedNotClamped() {
        // 只喂了 3 条资料，模型打了 `[7]`。若实现是 `min(7, 3)`，这里会得到 `[3]` ——
        // 一条**假引用**，而它在页面上完全正常（用户点角标会跳到一篇无关的分片）
        assertThat(CitationParser.parse("见资料[7]。", 3)).isEmpty();
        // 混着来：合法的留着，越界的丢掉
        assertThat(CitationParser.parse("A[1] B[9] C[2]", 3)).containsExactly(1, 2);
    }

    @Test
    @DisplayName("🔴 负向锚：资料块里的**标题路径**不会被当成角标")
    void titlePathBracketsAreNotMarkers() {
        // 资料块的形状是 `[1] （第 3 页 3.1 鉴权流程） 正文…` —— 编号是半角方括号，
        // 标题路径是**全角圆括号**（prompt 里刻意用两种，见 ChatStreamService#prompt）。
        // 若两处都用 `[...]`，这段"正文"里的 `[3]` 会被读成"引用了第 3 条"
        String answerLikePrompt = "[1] （第 3 页 3.1 鉴权流程） 接口统一以 /api 为前缀";
        assertThat(CitationParser.parse(answerLikePrompt, 5))
                .as("`（…）` 是圆括号，正则不匹配它；若匹配到了会得到 [1] 之外的东西")
                .containsExactly(1);
    }

    @Test
    @DisplayName("🔴 负向锚：解析不出时返回**空集**（调用方据此写 NULL，不是 0）")
    void noMarkerYieldsEmptyNotZero() {
        assertThat(CitationParser.parse("这段回答没有任何角标。", 5)).isEmpty();
        assertThat(CitationParser.parse("", 5)).isEmpty();
        assertThat(CitationParser.parse(null, 5)).isEmpty();
        assertThat(CitationParser.parse("只有非数字角标[a]与[注]。", 5)).isEmpty();
    }

    @Test
    @DisplayName("资料条数为 0 / 负数时直接空集（空召回的兜底话术会走到这里）")
    void zeroChunksYieldsEmpty() {
        assertThat(CitationParser.parse("回答里恰好写了[1]。", 0)).isEmpty();
        assertThat(CitationParser.parse("回答里恰好写了[1]。", -1)).isEmpty();
    }

    @Test
    @DisplayName("4 位以上不算角标（`[1234]` 一定是模型编的，喂给模型的资料远小于 1000 条）")
    void fourDigitsIsNotAMarker() {
        assertThat(CitationParser.parse("见[1234]。", 2000))
                .as("上限 3 位是与 prompt 一致的约束，不是「防大数」")
                .isEmpty();
    }

    @Test
    @DisplayName("与正文其他方括号共存：只认纯数字的")
    void mixedBrackets() {
        assertThat(CitationParser.parse("参见[附录A]与[1]，另见[2-3]。", 5))
                .as("[附录A] 与 [2-3] 都不是纯数字 ⇒ 只认 [1]")
                .containsExactly(1);
    }

    @Test
    @DisplayName("多段文本拼接后的完整解析（`ChatStreamService` 是逐段 append 的）")
    void parsesAccumulatedStreamText() {
        StringBuilder sb = new StringBuilder();
        for (String piece : List.of("结论：单价是 ", "1800 元[1]。", "交期 30 天[2]。")) {
            sb.append(piece);
        }
        assertThat(CitationParser.parse(sb.toString(), 3)).containsExactly(1, 2);
    }
}
