package com.knowledge.ai.core.text;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BM25 文本口径（`S5-M07-A`）—— `G4-8` **运行时侧**在单元层的钉子。
 *
 * <h3>为什么这条单测值得单独存在（它防的是"静默不准"）</h3>
 * <p>BM25 输入列配的是 {@code whitespace} 分词器，Milvus 自己**几乎不分词**。
 * 于是"什么算一个 token"完全由 {@link IdentifierTokenizer} 决定 —— 而一旦这里切错，
 * 表现是**查 {@code A-100} 把 {@code A-1000} 也召回了**（排序仍会把它排在后面，
 * 所以**看不出错**，只是结果不精确）。这正是 `FR-RET-03` §3.1 点名的那件事。
 *
 * <p>⚠️ 断言的是「**token 列表里有没有那一项**」，不是「整串含不含这个子串」——
 * 后者对 {@code A-1000} 同样成立（{@code "A-1000".contains("A-100")} 为真），
 * 那是本类最容易写错、也最容易**假绿**的一条。
 *
 * <p>实测对照（`plan/2026-09-17-S5-M07-A.md` §3.4 步 0 探针）：Milvus 内置 {@code standard}
 * 第 1 组就挂（{@code A-100}→{@code a}+{@code 100}）；{@code jieba} 保住 2 类、
 * 挂掉 {@code ￥1,200}/{@code SKU-8890-A}/{@code 2026-09-11} 三类。
 */
class Bm25TextTest {

    private static List<String> tokensOf(String text) {
        String joined = Bm25Text.of(text);
        return joined.isBlank() ? List.of() : Arrays.stream(joined.split(" ")).toList();
    }

    @Test
    @DisplayName("G4-8：Q-4 点名的五类标识符各自整体成 token（换 Milvus 内置分词则会挂三类）")
    void q4IdentifiersStayWhole() {
        List<String> tokens = tokensOf("型号 A-100，单价 ￥1,200，交期 2026-09-11，料号 SKU-8890-A，版本 v1.4");

        assertThat(tokens)
                .as("五类标识符必须各自是一个 token")
                .contains("A-100", "￥1,200", "2026-09-11", "SKU-8890-A", "v1.4");
        assertThat(tokens)
                .as("任何一个标识符都不该被切成片段（切成 100 就会让 A-1000 也命中）")
                .doesNotContain("100", "1", "200", "2026", "09", "11", "SKU", "8890", "v1");
    }

    @Test
    @DisplayName("G4-8：A-100 与 A-1000 不共享任何 token —— 共享 token 正是误命中的根因")
    void nearMissIdentifiersShareNoToken() {
        List<String> shortOne = tokensOf("型号 A-100 的报价");
        List<String> longOne = tokensOf("型号 A-1000 的报价");

        // 与 IdentifierTokenizerTest 同一判据：断言"不共享 token"比断言 token 数更贴近要防的东西
        assertThat(shortOne).contains("A-100").doesNotContain("A-1000");
        assertThat(longOne).contains("A-1000").doesNotContain("A-100");
    }

    @Test
    @DisplayName("CJK 按单字成 token —— 中文退化的已知代价，写在这里免得被当成 bug")
    void cjkFallsBackToSingleChars() {
        // 这是为标识符保护付的代价（IdentifierTokenizer 没有词表）。
        // 影响由两层兜底吸收：RRF 只看排名、Rerank 用交叉编码器重排；
        // 量化与判定点是 T-711 评测集的 Recall@5。
        assertThat(tokensOf("智能网关")).containsExactly("智", "能", "网", "关");
    }

    @Test
    @DisplayName("空输入 → 空串（不是 null：Milvus 那列未声明 nullable，null 会拒整批插入）")
    void blankInputYieldsEmptyString() {
        assertThat(Bm25Text.of(null)).isEmpty();
        assertThat(Bm25Text.of("")).isEmpty();
        assertThat(Bm25Text.of("   \n\t ")).isEmpty();
    }

    @Test
    @DisplayName("截断按 token 边界，不产生半个 token（半截 token 是原文里不存在的词项）")
    void truncationKeepsTokenBoundaries() {
        String text = "型号 A-100 单价 ￥1,200 交期 2026-09-11";

        // 给一个"刚好放得下前几个 token"的宽度：结果必须是若干**完整** token
        Bm25Text.Result clipped = Bm25Text.of(text, 12);
        assertThat(clipped.truncated()).as("确实截断了，才谈得上验边界").isTrue();
        assertThat(clipped.text()).doesNotEndWith(" ");

        for (String token : clipped.text().split(" ")) {
            assertThat(text)
                    .as("截断产出的每个 token 都必须原样存在于原文中，实际出现了「%s」", token)
                    .contains(token);
        }

        // 宽度足够时不截断，且与 of(String) 逐字一致
        Bm25Text.Result full = Bm25Text.of(text, 10_000);
        assertThat(full.truncated()).isFalse();
        assertThat(full.text()).isEqualTo(Bm25Text.of(text));
    }
}
