package com.knowledge.ai.pipeline.retrieve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表格回表的**纯函数**用例（`T-705` · `G5-6`）。
 *
 * <h3>本用例存在的意义：把"窗口到底裁没裁"变成可判定的</h3>
 * <p>{@code G5-6} 的判据是「父块 >30 行时只取 ±5 行」。这句话有两个失败形态，
 * 而且**都不报错**：
 * <ul>
 *   <li><b>永远不裁</b>（窗口一次都没触发）—— 表现只是"大表还是整表返回"，
 *       看着像"这个功能还没做"。本类第一次写就差点栽在这里：父块内容**以换行结尾**，
 *       按 {@code split("\n")} 数行会多出一个空行，行数恒比子块数大 1，
 *       于是每次都走"退回整表"。所以下面专门有一条用例钉住结尾换行。</li>
 *   <li><b>裁错行</b> —— 给用户看了与提问无关的几行，并且**丢掉了真正命中的那一行**。
 *       {@code matchedRow} 落在头/尾时的边界（1 与 N）就是为这个钉的。</li>
 * </ul>
 *
 * <p>纯函数用例：{@link TableWindow} 与 {@link TableLookup#apply} 都不碰数据库。
 */
class TableWindowTest {

    private static final int LIMIT = 30;
    private static final int WINDOW = 5;

    // ==================== 判据 1 · 整表 / 行窗口三档 ====================

    @Test
    @DisplayName("G5-6 · 不超 30 行 → 原样返回整表（含结尾换行，逐字相同）")
    void smallTableIsReturnedWhole() {
        String markdown = table(10);

        TableWindow.Window window = TableWindow.of(markdown, 4, 10, LIMIT, WINDOW);

        assertThat(window.windowed()).as("10 行没超过 30，不该裁").isFalse();
        assertThat(window.rowFrom()).isEqualTo(1);
        assertThat(window.rowTo()).isEqualTo(10);
        assertThat(window.totalRows()).isEqualTo(10);
        assertThat(window.note()).isNull();
        assertThat(window.markdown())
                .as("整表路径必须**原样**返回 —— 重新拼一遍会丢掉结尾换行这类细节，"
                        + "而下游是逐字比对的")
                .isEqualTo(markdown);
    }

    @Test
    @DisplayName("G5-6 · 80 行命中第 40 行 → 只取第 35~45 行，且表头仍在")
    void largeTableIsWindowedAroundTheMatchedRow() {
        String markdown = table(80);

        TableWindow.Window window = TableWindow.of(markdown, 40, 80, LIMIT, WINDOW);

        assertThat(window.windowed()).isTrue();
        assertThat(window.rowFrom()).isEqualTo(35);
        assertThat(window.rowTo()).isEqualTo(45);
        assertThat(window.totalRows()).isEqualTo(80);
        assertThat(window.note()).isNull();

        String out = window.markdown();
        assertThat(out.lines().filter(l -> l.startsWith("|")).count())
                .as("表头 1 + 分隔行 1 + 窗口 11 行 = 13")
                .isEqualTo(13);
        assertThat(out)
                .as("表头必须带上 —— 没有列名的行读不出「1800」是什么")
                .contains("| 型号 | 单价 |");
        assertThat(out).contains("| M-35 |").contains("| M-45 |");
        assertThat(out)
                .as("窗口外的行不能出现（裁了就等于少给，没裁就等于撑爆上下文）")
                .doesNotContain("| M-34 |").doesNotContain("| M-46 |");
    }

    @Test
    @DisplayName("G5-6 · 80 行但**以换行结尾**（真实产物的形状）—— 仍要裁")
    void trailingNewlineDoesNotBecomeAPhantomRow() {
        String markdown = table(80);
        assertThat(markdown).as("前提：真实父块内容确实以换行结尾").endsWith("\n");

        TableWindow.Window window = TableWindow.of(markdown, 40, 80, LIMIT, WINDOW);

        assertThat(window.totalRows())
                .as("行数必须是 80 不是 81 —— 多算一个空行会让「行数 ≠ 子块数」，"
                        + "于是每次都走退回整表那条路，窗口永远不触发")
                .isEqualTo(80);
        assertThat(window.windowed()).isTrue();
    }

    // ==================== 边界：头与尾 ====================

    @Test
    @DisplayName("G5-6 · 命中在头部 → 窗口从第 1 行开始（不出现负行号）")
    void windowClampsAtHead() {
        TableWindow.Window window = TableWindow.of(table(80), 2, 80, LIMIT, WINDOW);

        assertThat(window.rowFrom()).isEqualTo(1);
        assertThat(window.rowTo()).isEqualTo(7);
        assertThat(window.markdown()).doesNotContain("| M-8 |");
    }

    @Test
    @DisplayName("G5-6 · 命中在尾部 → 窗口止于最后一行（不越界）")
    void windowClampsAtTail() {
        TableWindow.Window window = TableWindow.of(table(80), 80, 80, LIMIT, WINDOW);

        assertThat(window.rowFrom()).isEqualTo(75);
        assertThat(window.rowTo()).isEqualTo(80);
        assertThat(window.markdown()).contains("| M-80 |").doesNotContain("| M-74 |");
    }

    // ==================== 退回整表的三条路（宁可多给，不可给错行）====================

    @Test
    @DisplayName("G5-6 · 数据行数与子块数对不上 → 退回整表并说明原因")
    void rowCountMismatchFallsBackToWholeTable() {
        String markdown = table(80);

        // 分片时全空行被跳过 —— 于是"第 N 个子块"不再等于"第 N 个数据行"
        TableWindow.Window window = TableWindow.of(markdown, 40, 79, LIMIT, WINDOW);

        assertThat(window.windowed())
                .as("宁可不裁，也不能按错位的行号裁 —— 那会给用户看与提问无关的行")
                .isFalse();
        assertThat(window.markdown()).isEqualTo(markdown);
        assertThat(window.note()).isNotNull().contains("行号无法对应");
    }

    @Test
    @DisplayName("G5-6 · 命中序号越界 → 退回整表并说明原因")
    void matchedRowOutOfRangeFallsBack() {
        String markdown = table(80);

        TableWindow.Window window = TableWindow.of(markdown, 99, 80, LIMIT, WINDOW);

        assertThat(window.windowed()).isFalse();
        assertThat(window.markdown()).isEqualTo(markdown);
        assertThat(window.note()).isNotNull().contains("越出");
    }

    @Test
    @DisplayName("G5-6 · 父块内容不是管道表 → 原样返回 + 留痕（不抛）")
    void nonTableContentFallsBackWithoutThrowing() {
        TableWindow.Window window = TableWindow.of("这是一段正文，不是表", 1, 1, LIMIT, WINDOW);

        assertThat(window.windowed()).isFalse();
        assertThat(window.markdown()).isEqualTo("这是一段正文，不是表");
        assertThat(window.note()).contains("不是 Markdown 管道表");
    }

    @Test
    @DisplayName("G5-6 · 只有表头没有数据行 → 不裁也不报错")
    void headerOnlyTableHasNothingToWindow() {
        String onlyHeader = "| 型号 | 单价 |\n|---|---|\n";

        TableWindow.Window window = TableWindow.of(onlyHeader, 1, 0, LIMIT, WINDOW);

        assertThat(window.windowed()).isFalse();
        assertThat(window.totalRows()).isZero();
        assertThat(window.note()).as("没有数据行是正常的表，不是异常情况").isNull();
    }

    // ==================== TableLookup.apply · 替换语义与顺序 ====================

    @Test
    @DisplayName("T-705 · 只替换表格子块：条数/顺序/分数/名次全不变，非表格块不挂 context")
    void applyOnlyTouchesTableChunks() {
        RetrievedChunk plain = chunk("1", 1L, 0.9, 1);
        RetrievedChunk table = chunk("2", 1L, 0.8, 2);
        RetrievedChunk other = chunk("3", 1L, 0.7, 3);

        List<RetrievedChunk> out = TableLookup.apply(
                List.of(plain, table, other),
                Map.of("2", new TableLookup.TableFact(99L, table(80), 40, 80)),
                Map.of(), new TableLookup.TableParams(LIMIT, WINDOW));

        assertThat(out).extracting(RetrievedChunk::chunkId).containsExactly("1", "2", "3");
        assertThat(out).extracting(RetrievedChunk::rank).containsExactly(1, 2, 3);
        assertThat(out).extracting(RetrievedChunk::score).containsExactly(0.9, 0.8, 0.7);
        assertThat(out.get(0).tableContext()).as("非表格块不该被挂上 context").isNull();
        assertThat(out.get(2).tableContext()).isNull();

        RetrievedChunk.TableContext ctx = out.get(1).tableContext();
        assertThat(ctx).isNotNull();
        assertThat(ctx.parentChunkId()).isEqualTo(99L);
        assertThat(ctx.rowFrom()).isEqualTo(35);
        assertThat(ctx.rowTo()).isEqualTo(45);
        assertThat(ctx.totalRows()).isEqualTo(80);
        assertThat(ctx.windowed()).isTrue();
        assertThat(ctx.matchedRow())
                .as("原始命中行必须留着 —— 父块随时能从库里再取，那一行不能")
                .isEqualTo(table.content());
        assertThat(out.get(1).content())
                .as("content 被**换掉**成行窗口（不是追加第二条）")
                .contains("| 型号 | 单价 |").doesNotContain("| M-34 |");
    }

    @Test
    @DisplayName("T-705 · 窗口参数按**分片自己所属的库**取（跨库检索不会串参数）")
    void applyUsesTheChunksOwnKbPolicy() {
        RetrievedChunk inKb7 = chunk("11", 7L, 0.9, 1);
        RetrievedChunk inKb8 = chunk("12", 8L, 0.8, 2);
        Map<String, TableLookup.TableFact> facts = Map.of(
                "11", new TableLookup.TableFact(1L, table(40), 20, 40),
                "12", new TableLookup.TableFact(2L, table(40), 20, 40));

        List<RetrievedChunk> out = TableLookup.apply(List.of(inKb7, inKb8), facts,
                Map.of(7L, new TableLookup.TableParams(LIMIT, WINDOW),
                        // 库 8 把窗口设成 0（只要命中那一行）+ 阈值 100（40 行压根不裁）
                        8L, new TableLookup.TableParams(100, 0)),
                new TableLookup.TableParams(LIMIT, WINDOW));

        RetrievedChunk.TableContext kb7 = out.get(0).tableContext();
        RetrievedChunk.TableContext kb8 = out.get(1).tableContext();
        assertThat(kb7.windowed()).as("库 7：40 行 > 30 → 裁").isTrue();
        assertThat(kb7.rowFrom()).isEqualTo(15);
        assertThat(kb7.rowTo()).isEqualTo(25);

        assertThat(kb8.windowed()).as("库 8：阈值 100，40 行不裁 → 整表").isFalse();
        assertThat(kb8.totalRows()).isEqualTo(40);
    }

    @Test
    @DisplayName("T-705 · 库没有配置行时走兜底参数（不是不裁、也不是崩）")
    void applyFallsBackToDefaultsWhenKbHasNoPolicy() {
        RetrievedChunk chunk = chunk("21", 555L, 0.9, 1);

        List<RetrievedChunk> out = TableLookup.apply(List.of(chunk),
                Map.of("21", new TableLookup.TableFact(1L, table(80), 40, 80)),
                Map.of(), new TableLookup.TableParams(LIMIT, WINDOW));

        assertThat(out.getFirst().tableContext().windowed()).isTrue();
    }

    @Test
    @DisplayName("T-705 · 没有命中任何表格时原样返回（不新建列表）")
    void applyIsANoOpWhenNoTableHit() {
        List<RetrievedChunk> chunks = List.of(chunk("31", 1L, 0.9, 1));

        assertThat(TableLookup.apply(chunks, Map.of(), Map.of(), new TableLookup.TableParams(LIMIT, WINDOW)))
                .isSameAs(chunks);
        assertThat(TableLookup.apply(List.of(), Map.of("1", fact()), Map.of(),
                new TableLookup.TableParams(LIMIT, WINDOW))).isEmpty();
    }

    // ==================== 构造 ====================

    @Test
    @DisplayName("S7-M06-A：与 MarkdownTable 对同一段内容给出的行数逐位相等（两个读者不分叉）")
    void agreesWithMarkdownTableOnRowCount() {
        // `S7-M06-A`（T-604）新增了第二个表格读者：`core.text.MarkdownTable` ——
        // 它给"父块 N 行 × M 列"里的 N，而本类给回表窗口的行号。
        // 两者认表格行的判据必须**逐字相同**（都以 `|` 开头的行是表格行、其余是前导行），
        // 否则同一张表在预览页与检索侧会显示不同的行数，而那种偏差不报错、只让数字对不上。
        // ⚠️ 这条只能写在本包：TableWindow 是包内可见的
        String content = table(3);

        TableWindow.Window window = TableWindow.of(content, 2, 3, LIMIT, 5);

        assertThat(com.knowledge.ai.core.text.MarkdownTable.of(content).rows())
                .isEqualTo(3)
                .isEqualTo(window.totalRows());
        assertThat(window.windowed()).isFalse();
    }

    /** 一张 {@code rows} 个数据行的 Markdown 管道表，形状与 {@code ParsedTable#toMarkdown()} 一致（以换行结尾）。 */
    private static String table(int rows) {
        StringBuilder sb = new StringBuilder("| 型号 | 单价 |\n|---|---|\n");
        for (int i = 1; i <= rows; i++) {
            sb.append("| M-").append(i).append(" | 价").append(i).append(" |\n");
        }
        return sb.toString();
    }

    private static TableLookup.TableFact fact() {
        return new TableLookup.TableFact(1L, table(1), 1, 1);
    }

    private static RetrievedChunk chunk(String chunkId, long kbId, double score, int rank) {
        return new RetrievedChunk(chunkId, 1L, kbId, "TABLE", "报价 > 明细", "型号 M-1，单价 1",
                score, RetrievedChunk.ScoreKind.RERANK, rank, rank, score, score,
                Map.of(RetrievalHit.Channel.VECTOR, rank), false, null);
    }
}
