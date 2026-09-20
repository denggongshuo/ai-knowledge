package com.knowledge.ai.core.text;

/**
 * CSV 的**写法口径**（`T-A02`）—— 导出类功能唯一的一处转义与行拼接。
 *
 * <h3>为什么要有这个类（而不是各处各写一遍）</h3>
 * <p>本批之前，全仓只有一份手写 CSV（{@code AuditQueryService#exportCsv}），
 * 而它自己的注释里就写着「再引一个库等于让仓库里出现**第二种 CSV 写法**，
 * 将来两边转义规则不一致更难查」。`T-A02` 的「导出运营报表」是第二个 CSV 出口 ——
 * 于是那句话在这里兑现：**不复制那 6 行，把它提出来**。
 * <p>⚠️ 两份转义规则不一致的后果不是"报错"，是**列整体错位**：
 * 一个带逗号的文档标题会把后面所有列挤到右边一格，而导出看起来是成功的。
 *
 * <h3>为什么手写而不是引 commons-csv / opencsv</h3>
 * <p>与既有的决定一致（`UserAdminService#importUsers` 选 CSV 而非 poi-ooxml，省 15MB）：
 * 这里要的东西就是 RFC 4180 的最小子集（逗号 / 双引号 / 换行三种字符），
 * 引一个库进来，它的默认方言（分隔符、换行、是否加 BOM）反而要额外配一遍。
 *
 * <h3>约定的三件小事</h3>
 * <ul>
 *   <li>**换行用 `\r\n`** —— Excel 对纯 `\n` 的兼容性取决于版本（既有实现已按 `\r\n` 落了）；</li>
 *   <li>**开头加 BOM**（{@link #BOM}）—— 不然中文在部分 Excel 里是乱码，
 *       而"下载的表格打不开"会被当成导出功能坏了；</li>
 *   <li>**空值写成空串**，不写 `null` 字面量（那会被当成一个叫 "null" 的值）。</li>
 * </ul>
 */
public final class Csv {

    /** 文件头的字节顺序标记（UTF-8 BOM）—— Excel 认它，浏览器与文本编辑器会跳过。 */
    public static final String BOM = "﻿";

    /** RFC 4180 的行分隔符。 */
    public static final String CRLF = "\r\n";

    private Csv() {
    }

    /**
     * 一个单元格的转义。必须处理的三种字符：**逗号、双引号、换行**。
     *
     * <p>⚠️ 别把它当"锦上添花"：导出里的对象名（知识库名 / 文档标题）完全可能带逗号，
     * 不转义会让列错位 —— 而现象是"导出的时候字段对不上"，看起来像查错了而不是 CSV 不合法。
     */
    public static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needsQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /** 一整行（自动转义 + 收尾 `\r\n`）。 */
    public static String row(String... cells) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(cell(cells[i]));
        }
        return line.append(CRLF).toString();
    }

    /** 数字单元格（`long` / `Integer` 等）—— 走 {@link #cell} 的同一套转义，别绕开。 */
    public static String number(Number value) {
        return value == null ? "" : cell(String.valueOf(value));
    }
}
