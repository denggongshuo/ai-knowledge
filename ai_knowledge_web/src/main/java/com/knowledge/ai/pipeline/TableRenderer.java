package com.knowledge.ai.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * 表格双表示（`T-504`）—— 一张表产出**两份内容**（D-5 §1.4）。
 *
 * <h3>两份内容，两条去向</h3>
 * <table border="1">
 *   <caption>D-5 §1.4</caption>
 *   <tr><th>份</th><th>内容</th><th>去向</th><th>参与检索</th></tr>
 *   <tr><td>父块</td><td>整表 Markdown 管道表</td><td>给大模型（回表取用）</td><td>❌ 否</td></tr>
 *   <tr><td>子块</td><td>行式文本（列名 + 值）</td><td>给向量检索</td><td>✅ 是</td></tr>
 * </table>
 *
 * <h3>为什么子块必须带列名</h3>
 * <p>D-5 §1.4 硬约束原话：「列名必须拼进每一行 —— 列名是语义的载体，只 embed 一行数据等于没写」。
 * Markdown 里的竖线与对齐空格会**稀释语义**，且「报价」「价格」这类词在串里**完全不存在**，
 * 向量里压根没有这个概念。所以子块是「型号 A-100，产品名称 智能网关」，
 * 而不是「| A-100 | 智能网关 |」。
 *
 * <h3>为什么本类不碰 orderNo / parentId</h3>
 * <p>序号与父子关系由 {@link ChunkingService} 统一分配 —— 分片器才知道"上一块是几号"。
 * 本类只回答"这张表要产出几段内容、分别是什么"，是个纯函数，可单独测。
 */
final class TableRenderer {

    /** 行式文本里列名与值之间的连接符（`G4-7` 的形态判据）。 */
    private static final String PAIR_SEPARATOR = " ";

    /** 行式文本里字段之间的分隔符 —— 中文顿号，与 D-5 §1.4 的示例逐字一致。 */
    private static final String FIELD_SEPARATOR = "，";

    /** 表头与数据行之间：表头只有一行（D-5 §1.4 的形态假设）。 */
    private static final int HEADER_INDEX = 0;

    private TableRenderer() {
    }

    /**
     * 一张表的两份内容。
     *
     * @param markdown   父块内容（Markdown 管道表，给大模型读）；空表为空串
     * @param linearRows 子块内容（每行一条「列名 + 值」）；**空列表表示该表不产出子块**
     */
    record RenderedTable(String markdown, List<String> linearRows) {

        /** 两份都空 —— 调用方据此判定"这张表不成块"。 */
        boolean isEmpty() {
            return markdown.isBlank() && linearRows.isEmpty();
        }
    }

    /**
     * 渲染一张表。
     *
     * <p><b>版式误判的表在调用前就该被滤掉</b> —— 复用 {@code ParsedDocument#tableTotal()} 的
     * 同一口径（`1×1` 空表是分隔线被误判成表格，不是表格）。这里再滤一次会造成
     * "口径有两处、迟早对不上"，所以本方法**不判** `layoutArtifact`，只管渲染。
     */
    static RenderedTable render(ParsedDocument.ParsedTable table) {
        String markdown = table.toMarkdown();
        List<List<String>> rows = table.rows();

        if (rows.isEmpty()) {
            return new RenderedTable(markdown, List.of());
        }

        List<String> linear = new ArrayList<>();
        if (rows.size() == 1) {
            // 只有一行：没有"数据行"，但那一行**就是这张表的全部内容**（多半是表头）。
            // 直接丢弃会让"表格存在、却完全检索不到"—— 那正是本项目最怕的静默形态。
            // 所以把它作为唯一一条子块发出：内容是这一行本身，不假装它是"列名 + 值"。
            String only = String.join(FIELD_SEPARATOR, nonBlank(rows.get(HEADER_INDEX)));
            if (!only.isBlank()) {
                linear.add(only);
            }
            return new RenderedTable(markdown, List.copyOf(linear));
        }

        List<String> header = rows.get(HEADER_INDEX);
        for (int r = HEADER_INDEX + 1; r < rows.size(); r++) {
            String line = toLinearRow(header, rows.get(r));
            if (!line.isBlank()) {
                linear.add(line);
            }
        }
        return new RenderedTable(markdown, List.copyOf(linear));
    }

    /**
     * 一行数据 → 行式文本：{@code 列名 值，列名 值，…}
     *
     * <p>空值单元格**跳过**：拼接「单价 」（后面什么都没有）会产出没有语义的噪声 token，
     * 而空单元格本身不携带信息 —— 与"空串占位是为了保持列对齐"（{@code ParsedTable.rows} 的
     * 注释）不矛盾：那是**渲染 Markdown 时**的需要，这是**转自然语言时**的取舍。
     */
    private static String toLinearRow(List<String> header, List<String> row) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < row.size(); c++) {
            String value = row.get(c) == null ? "" : row.get(c).strip();
            if (value.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(FIELD_SEPARATOR);
            }
            String column = c < header.size() && header.get(c) != null ? header.get(c).strip() : "";
            if (column.isEmpty()) {
                // 这列没有表头（数据行比表头宽，或表头那一格是空的）：
                // 只放值，**不要**编一个"列 1"之类的假列名 —— 假列名会进向量。
                sb.append(value);
            } else {
                sb.append(column).append(PAIR_SEPARATOR).append(value);
            }
        }
        return sb.toString();
    }

    private static List<String> nonBlank(List<String> cells) {
        List<String> out = new ArrayList<>();
        for (String c : cells) {
            if (c != null && !c.isBlank()) {
                out.add(c.strip());
            }
        }
        return out;
    }
}
