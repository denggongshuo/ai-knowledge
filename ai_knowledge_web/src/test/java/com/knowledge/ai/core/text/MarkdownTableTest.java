package com.knowledge.ai.core.text;

import com.knowledge.ai.pipeline.ParsedDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `MarkdownTable`（`T-604` 的父块读侧）—— 纯函数，不连任何中间件。
 *
 * <h3>为什么这些用例值得写</h3>
 * <p>它读的是**本仓自己渲染出来的** Markdown（{@code ParsedDocument.ParsedTable#toMarkdown()}），
 * 那个格式有三条不显眼的约定（转义竖线 / {@code <br>} 压平换行 / 每行行尾带 {@code \n}），
 * 每一条猜错都会让"行数"或"列数"悄悄偏一位 —— 而偏一位的表现是
 * 「页面显示 14 行，实际 15 行」，没有任何报错。
 *
 * <h3>⚠️ 还有一条跨类判据不在这里</h3>
 * <p>本类与 {@code TableWindow}（`T-705` 的回表窗口）**共用同一个"表格行"判据**
 * （以 {@code |} 开头的行）。两个读者若分叉，同一张表在预览页与检索侧会显示不同的行数。
 * 那条用例写在 {@code TableWindowTest#agreesWithMarkdownTableOnRowCount} ——
 * <b>不是漏了</b>：{@code TableWindow} 是包内可见的，只有同包的用例访得到它。
 */
@DisplayName("MarkdownTable · 父块内容读侧")
class MarkdownTableTest {

    /**
     * 真实产物：{@code ParsedTable#toMarkdown()} 的形状 ——
     * 表标题（前导行）+ 表头 + 分隔行 + 数据行，每行行尾 {@code \n}。
     *
     * <p>⚠️ <b>分隔行的构造必须逐字照抄渲染器</b>（{@code ParsedDocument.java:418-423}）：
     * 它是先写一个 {@code |}、再按**列数**各写一个 {@code ---|}，得到 {@code |---|---|}。
     * 本助手第一版写成"按竖线个数替换"，产出 {@code ---|---|---|}（少一个前导竖线）
     * ⇒ 被 {@code MarkdownTable.isSeparator} 判成"不是表格"，三条用例当场红。
     * 那一版正是这类"偏一位"的活标本 —— 留着这段注释，是为了下次别再按竖线个数推列数。
     */
    private static String rendered(String caption, String header, List<String> rows) {
        StringBuilder sb = new StringBuilder();
        if (caption != null && !caption.isBlank()) {
            sb.append("**").append(caption).append("**").append('\n');
        }
        sb.append(header).append('\n');
        sb.append('|');
        for (int c = 0; c < MarkdownTable.cellsOf(header).size(); c++) {
            sb.append("---|");
        }
        sb.append('\n');
        for (String row : rows) {
            sb.append(row).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("判据 1：表标题（caption）被认出来，且它**不算**数据行")
    void captionIsNotARow() {
        String withCaption = rendered("表 2：接口错误码对照表", "| a | b |", List.of("| 1 | 2 |"));
        String withoutCaption = rendered(null, "| a | b |", List.of("| 1 | 2 |"));

        assertThat(MarkdownTable.of(withCaption).caption()).isEqualTo("表 2：接口错误码对照表");
        assertThat(MarkdownTable.of(withoutCaption).caption()).isEmpty();
        // 有无表标题，行数必须一样 —— 否则"表标题"会被当成一行数据
        assertThat(MarkdownTable.of(withCaption).rows())
                .isEqualTo(MarkdownTable.of(withoutCaption).rows())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("判据 2：单元格里的转义竖线（`\\|`）不会被数成两列")
    void escapedPipeDoesNotSplitACell() {
        // 渲染器把单元格里的 `|` 转义成 `\|`（ParsedDocument.java:415）
        String content = rendered(null, "| 表达式 | 含义 |", List.of("| a \\| b | 按位或 |"));

        MarkdownTable.Shape shape = MarkdownTable.of(content);

        assertThat(shape.cols()).isEqualTo(2);
        assertThat(shape.rows()).isEqualTo(1);
    }

    @Test
    @DisplayName("判据 3：不是表格的内容 → rows=0/cols=0 且 isTable=false（**不猜**）")
    void nonTableContentIsNotATable() {
        // 只有表头、没有分隔行：**刻意不判成"0 行的表"** —— 那是数据事实，不该由启发式决定
        assertThat(MarkdownTable.of("| a | b |\n| 1 | 2 |").isTable()).isFalse();
        assertThat(MarkdownTable.of("就是一段正文，没有表格").isTable()).isFalse();
        assertThat(MarkdownTable.of("").isTable()).isFalse();
        assertThat(MarkdownTable.of(null).isTable()).isFalse();
    }

    @Test
    @DisplayName("判据 4：空单元格与缺列都如实反映（列数取表头，不按最长行放大）")
    void shortRowsDoNotInflateColumns() {
        String content = rendered(null, "| a | b | c |", List.of("| 1 |  | 3 |"));

        MarkdownTable.Shape shape = MarkdownTable.of(content);

        assertThat(shape.cols()).isEqualTo(3);
        assertThat(shape.header()).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("判据 5：与渲染器对得上 —— 真 ParsedTable 渲染出来的内容能被读回同样的行列")
    void roundTripsWithTheRealRenderer() {
        // 用真渲染器的入口而不是手拼字符串：手拼的"看起来对"证明不了渲染器真的这么产
        ParsedDocument.ParsedTable table = new ParsedDocument.ParsedTable(
                "表 2：接口错误码对照表", 3,
                List.of(List.of("错误码", "含义", "处理建议"),
                        List.of("10021", "无知识库访问权限", "检查角色与授权"),
                        List.of("10022", "无该知识库访问权限", "同上")));

        String markdown = table.toMarkdown();
        MarkdownTable.Shape shape = MarkdownTable.of(markdown);

        assertThat(shape.isTable()).isTrue();
        assertThat(shape.rows()).isEqualTo(2);   // 3 行矩阵 − 表头 = 2 个数据行
        assertThat(shape.cols()).isEqualTo(3);
        assertThat(shape.caption()).isEqualTo("表 2：接口错误码对照表");
    }
}
