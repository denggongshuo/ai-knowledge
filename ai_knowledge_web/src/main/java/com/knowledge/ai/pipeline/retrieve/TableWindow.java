package com.knowledge.ai.pipeline.retrieve;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格行窗口（`T-705` · `G5-6`）—— 父块 Markdown 的**纯函数**裁剪。
 *
 * <h3>要解决的问题</h3>
 * <p>「无脑取整表会撑爆上下文」不是一句担心：一张 500 行的报价单，Markdown 管道表逐行
 * 大约 {@code 60~120} 字符，整表就是 {@code 30K~60K} 字符。一次问答给它塞两三张，
 * 上下文就没了 —— 而其中 480 行与提问无关。所以 {@code G5-6} 的判据是
 * 「父块 >30 行时只取 ±5 行」。
 *
 * <h3>口径：表头永远带上，窗口只裁数据行</h3>
 * <p>裁掉表头，剩下的行就是一堆没有列名的值 —— 「1800」「30 天」读不出是什么。
 * 这与 {@code D-5} §1.4「列名必须拼进每一行」是同一条理由，只是这里发生在表这一侧。
 *
 * <h3>⚠️ 行数对不上时**退回整表**，不猜</h3>
 * <p>{@link com.knowledge.ai.pipeline.TableRenderer} 在把数据行转成子块时会
 * **跳过全空的行**（{@code toLinearRow} 返回空串的那些）。于是"父块里的第 N 个数据行"
 * 与"第 N 个子块"**不再一一对应** —— 而这正是本类把命中子块映射到行号的唯一依据。
 *
 * <p>本类不做内容匹配去补救（那要把 {@code TableRenderer} 的行式化规则抄一遍，
 * 于是同一件事有两处实现，迟早不一致）。发现对不上就**返回整表**：
 * {@code G5-6} 的原话是「返回**父块或其行窗口**」—— 返回父块永远是合规的，
 * 窗口只是大表时的省流优化。**宁可多给一点，不可给错一行。**
 */
final class TableWindow {

    /** 表头 + 分隔行 —— 表体的固定前缀，窗口裁剪时永远保留。 */
    private static final int FIXED_PREFIX_LINES = 2;

    private TableWindow() {
    }

    /**
     * 一次裁剪的结果。
     *
     * @param markdown   裁剪后的 Markdown（可能是整表，也可能是窗口）
     * @param rowFrom    起始数据行，1 起、含；整表时为 1
     * @param rowTo      结束数据行，1 起、含
     * @param totalRows  数据行总数（不含表头）
     * @param windowed   {@code true} = 确实裁了；{@code false} = 给的是整表
     * @param note       非常规情况的原因，正常为 {@code null}
     */
    record Window(String markdown, int rowFrom, int rowTo, int totalRows,
                  boolean windowed, String note) {
    }

    /**
     * 按命中行算窗口。
     *
     * @param parentMarkdown 父块内容（{@code ParsedDocument.ParsedTable#toMarkdown()} 的产物）
     * @param matchedRow     命中的是第几个**子块**，1 起。见类注释：本类假设它与数据行一一对应
     * @param childCount     该父块实际产出了几个子块 —— 用它与数据行数**对账**
     * @param rowLimit       超过这么多行才裁窗口（库级 {@code TablePolicy.rowLimit}，默认 30）
     * @param rowWindow      命中行前后各留几行（库级 {@code TablePolicy.rowWindow}，默认 5）
     */
    static Window of(String parentMarkdown, int matchedRow, int childCount,
                     int rowLimit, int rowWindow) {
        if (parentMarkdown == null || parentMarkdown.isBlank()) {
            return new Window(parentMarkdown == null ? "" : parentMarkdown,
                    1, 0, 0, false, "父块内容为空");
        }

        List<String> lines = List.of(parentMarkdown.split("\n", -1));
        int firstTableLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("|")) {
                firstTableLine = i;
                break;
            }
        }
        if (firstTableLine < 0) {
            // 父块内容里一行表格都没有 —— 与"父块 = Markdown 管道表"的契约不符。
            // 不抛：检索结果少一点上下文，不该让整次问答失败（与父块缺失同一条口径）
            return new Window(parentMarkdown, 1, 0, 0, false, "父块内容不是 Markdown 管道表");
        }

        List<String> preamble = new ArrayList<>(lines.subList(0, firstTableLine));
        // ⚠️ 只收**以 `|` 开头的行**，不能直接 subList(firstTableLine, size)。
        // `ParsedDocument.ParsedTable#toMarkdown()` 每写完一行都补 `\n`，
        // 于是父块内容**以换行结尾** —— split 会多出一个空串。把它当数据行的话，
        // 行数恒比子块数大 1，本类会永远走"退回整表"那条路，窗口**一次都不会触发**，
        // 而现象只是"大表还是整表返回"（看着像没做这个功能）。
        List<String> tableLines = new ArrayList<>();
        for (int i = firstTableLine; i < lines.size(); i++) {
            if (lines.get(i).startsWith("|")) {
                tableLines.add(lines.get(i));
            }
        }
        if (tableLines.size() <= FIXED_PREFIX_LINES) {
            // 只有表头（或连表头都不全）：没有数据行可裁
            return new Window(parentMarkdown, 1, 0, 0, false, null);
        }

        List<String> dataRows = tableLines.subList(FIXED_PREFIX_LINES, tableLines.size());
        int totalRows = dataRows.size();

        if (totalRows <= rowLimit || childCount != totalRows || matchedRow < 1 || matchedRow > totalRows) {
            // 三条"退回整表"：① 表本来就不大；② 行数与子块数对不上（见类注释）；
            // ③ 命中序号越界（数据与索引不一致时宁可多给，也不给错行）
            return new Window(parentMarkdown, 1, totalRows, totalRows, false, fallbackNote(
                    totalRows, rowLimit, childCount, matchedRow));
        }

        int from = Math.max(1, matchedRow - rowWindow);
        int to = Math.min(totalRows, matchedRow + rowWindow);

        StringBuilder sb = new StringBuilder();
        for (String line : preamble) {
            sb.append(line).append('\n');
        }
        sb.append(tableLines.get(0)).append('\n');   // 表头
        sb.append(tableLines.get(1)).append('\n');   // 分隔行
        for (int i = from - 1; i <= to - 1; i++) {
            sb.append(dataRows.get(i)).append('\n');
        }
        return new Window(sb.toString(), from, to, totalRows, true, null);
    }

    private static String fallbackNote(int totalRows, int rowLimit, int childCount, int matchedRow) {
        if (totalRows <= rowLimit) {
            return null; // 正常路径：表不大，整表就是全部
        }
        if (childCount != totalRows) {
            return "表格有 " + totalRows + " 个数据行但只产出 " + childCount
                    + " 个子块（全空行在分片时被跳过），行号无法对应 —— 已返回整表而不是猜一个窗口";
        }
        return "命中序号 " + matchedRow + " 越出 1~" + totalRows + " —— 已返回整表而不是猜一个窗口";
    }
}
