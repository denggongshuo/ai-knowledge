package com.knowledge.ai.core.text;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 管道表的**只读视图**（行数 / 列数 / 表标题）—— `T-604` 的父块回表用。
 *
 * <h3>为什么手写而不是引 Markdown 库</h3>
 * <p>本仓只需要三样东西：数据行数、列数、表标题。引 `commonmark-java` 会把一次全量
 * AST 解析带进读接口，而**事实源是已经落库的 {@code raw_content}** ——
 * 它由本仓自己的渲染器产出（见下），格式是确定的。这与 `Csv`（手写、省 15MB poi-ooxml）
 * 是同一条取舍。
 *
 * <h3>⚠️ 格式的唯一事实源是 {@code ParsedDocument.ParsedTable#toMarkdown()}</h3>
 * <p>本类只认它产出的那一种形状，逐条对照（{@code ParsedDocument.java:400-425}）：
 * <pre>
 *   [**表标题**]              ← 可选前导行（caption 里的 `|` 已被换成 `/`）
 *   | 表头 | 表头 | 表头 |
 *   |---|---|---|              ← 分隔行（紧跟表头）
 *   | 数据 | 数据 | 数据 |
 * </pre>
 * <ul>
 *   <li>单元格里的 `|` 被转义成 `\|`（`ParsedDocument.java:415`）⇒ **本类必须跳过转义竖线**，
 *       否则"一个含竖线的单元格"会被数成两列；</li>
 *   <li>单元格里的换行被压成 `&lt;br&gt;`（同一行）⇒ 一行就是一行，不存在跨行单元格；</li>
 *   <li>每行行尾都有 {@code \n} ⇒ 末行 split 后会出现空串，**只收以 `|` 开头的行**。</li>
 * </ul>
 *
 * <h3>⚠️ 「表格行」的判定与 {@code TableWindow} 保持一致</h3>
 * <p>{@code TableWindow}（`T-705` 已交付的回表窗口）用**同一个判据**：
 * 以 {@code |} 开头的行是表格行、其余是前导行（preamble）。两处若分叉，
 * 同一张表在预览页与检索侧会显示不同的行数 —— 那是查不出原因的那类偏差。
 * 有一条用例（{@code MarkdownTableTest}）**双向**钉住这一点。
 *
 * <h3>口径：{@code rows} 不含表头</h3>
 * <p>与 {@code TableWindow.Window#totalRows} 同口径（数据行数，不含表头），
 * 也与"子块数与数据行一一对应"这条前提同源（见 {@code TableWindow} 的类注释）。
 * 需求 §4.5 那句「14 行 × 3 列全部返回，**含表头与表标题**」说的是**返回的正文里包含**
 * 表头行与表标题（{@link #caption()} 与 {@link #content()} 都给了），不是把表头算进行数。
 */
public final class MarkdownTable {

    /** 分隔行的最小形态：只由 `-` / `:` / 空格 / `|` 组成，且至少一个 `-`。 */
    private static final String SEPARATOR_CHARS = "-: |";

    private MarkdownTable() {
    }

    /**
     * 一张表的只读视图。
     *
     * @param rows      **数据行数（不含表头）**；不是表格时为 0
     * @param cols      列数（取表头行的单元格数）；不是表格时为 0
     * @param caption   表标题（前导行里形如 `**…**` 的那一行内文）；没有时为空串
     * @param header    表头单元格（去掉首尾空白）；不是表格时为空列表
     * @param isTable   是否被判定为 Markdown 管道表（表头 + 分隔行都在）
     */
    public record Shape(int rows, int cols, String caption, List<String> header, boolean isTable) {

        public Shape {
            caption = caption == null ? "" : caption;
            header = header == null ? List.of() : List.copyOf(header);
        }

        static Shape notATable() {
            return new Shape(0, 0, "", List.of(), false);
        }
    }

    /**
     * 读一张表。**不抛异常** —— 父块内容不是表格时返回 {@link Shape#notATable()}，
     * 由调用方决定"这算不算异常"（检索侧与预览页对此的处置不同：
     * 前者少一点上下文、后者要如实告诉用户"这个块不是表格"）。
     */
    public static Shape of(String content) {
        if (content == null || content.isBlank()) {
            return Shape.notATable();
        }

        List<String> lines = List.of(content.split("\n", -1));
        String caption = "";
        List<String> tableLines = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("|")) {
                tableLines.add(line);
            } else if (tableLines.isEmpty() && isCaption(line)) {
                // 只认**紧贴表格之前**的那一行标题；表格之后再出现的 `**…**`
                // 属于正文，不是这张表的标题
                caption = line.trim().substring(2, line.trim().length() - 2).trim();
            }
        }
        if (tableLines.size() < 2 || !isSeparator(tableLines.get(1))) {
            // 没有分隔行 ⇒ 不是管道表。**刻意不猜**（比如"只有表头也算表"）：
            // 猜错的表现是"这张表被说成 0 行"，而那是数据事实，不该由启发式决定
            return Shape.notATable();
        }

        List<String> header = cellsOf(tableLines.get(0));
        return new Shape(tableLines.size() - 2, header.size(), caption, header, true);
    }

    /**
     * 按未转义的 `|` 切出单元格。
     *
     * <p>首尾的 `|` 不产生空单元格：`| a | b |` 切成 `[a, b]`，不是 `["", a, b, ""]`。
     */
    static List<String> cellsOf(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String body = line;
        // 去掉最外层的一对竖线（渲染器的产物必定首尾各一个）
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        if (body.endsWith("|") && !body.endsWith("\\|")) {
            body = body.substring(0, body.length() - 1);
        }
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length() && body.charAt(i + 1) == '|') {
                // 转义竖线是**内容**，不是分隔符（`ParsedDocument.java:415`）
                cur.append('|');
                i++;
            } else if (c == '|') {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().trim());
        return List.copyOf(out);
    }

    /** 分隔行：只由 `-` / `:` / 空格 / `|` 组成，且至少一个 `-`。 */
    private static boolean isSeparator(String line) {
        if (!line.startsWith("|") || line.indexOf('-') < 0) {
            return false;
        }
        for (int i = 0; i < line.length(); i++) {
            if (SEPARATOR_CHARS.indexOf(line.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCaption(String line) {
        String t = line.trim();
        return t.length() > 4 && t.startsWith("**") && t.endsWith("**");
    }
}
