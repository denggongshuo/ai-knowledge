package com.knowledge.ai.pipeline;

import com.knowledge.ai.core.enums.BlockType;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析产物（`T-502`）—— **M05 的领域模型，也是 `T-503` 分片的唯一输入**。
 *
 * <h3>为什么把两条解析路径的产物收敛成一个类型</h3>
 * <p>PDF 走 opendataloader（有元素树、有真正的 cell 矩阵），DOCX/XLSX 走 Tika
 * （只有 XHTML 流，合并单元格信息已丢），MD/TXT 直接是文本。
 * 三条来源的**能力不同**，但下游（分片、质量统计、issue 清单）不该知道这件事 ——
 * 它们只问"这篇文档有哪些结构单元、有没有问题"。
 * 把这个收敛点放在这里，是让 `T-503` 只面对一种输入的唯一办法。
 *
 * <h3>字段口径即契约</h3>
 * <p>本类型序列化成 {@code parse.json} 落对象存储（{@code sys_document.parse_path}），
 * 下游读它而不重新解析。所以**改这里的字段名 = 改已落库产物的格式** ——
 * 老产物会读不出来。加字段可以（Jackson 对缺失字段有默认值），改名要先想清楚。
 *
 * <p>⚠️ {@link #elements()} 是 **2026-09-16（`S4-M05-C1` 步 0.6）新增**的字段。
 * 在这之前落库的 {@code parse.json} 没有它（Jackson 给空列表）→ 那些文档分片时会得到**空块集**。
 * 所以 {@code DocParseService} 有一条防御：elements 为空而 {@code chars > 0} 时**显式失败**
 * 并要求重解析，而不是静默产出 0 个块（"跑了但什么都没产出"是本项目最不能接受的形态）。
 *
 * @param format      源格式（{@code PDF}/{@code DOCX}/… ，取自 {@code sys_document.type}）
 * @param pages       页数。非分页格式（TXT/MD/SHEET）按 {@code 1} 计 —— **不能填 0**，
 *                    否则 {@link #charsPerPage()} 除零，且"0 页"在下游会被读成"没解析出东西"
 * @param chars       抽出的正文字符数（含 markdown 里的表格串 —— 它就是"内容有多少"）
 * @param markdown    统一形态的正文。**这是"内容"本身**，也是将来 `T-504` 表格双表示的原料
 * @param headings    标题结构，按文档顺序
 * @param tables      表格（含 cell 矩阵）。`T-504` 的 Markdown 表示与行式文本都由它产出
 * @param paragraphs  正文段落数（不含标题、不含表格内文本）—— 供质量展示与分片估算
 * @param imageCount  图片元素个数。⚠️ 只用于**统计**，本批不产出 `IMAGE` 分片
 *                    （图片处理是 `T-512`，P1）
 * @param issues      逐页问题（null 与空列表等价，见 {@link DocumentIssue}）
 * @param elements    **按文档顺序**的元素序列 —— **分片第一级（结构边界）吃它**
 *                    （《06》§1.4.2：「标题层级 H1→H3，叶子节点即一个候选块」）。
 *                    见 {@link DocElement} 与下方"为什么需要它"
 */
public record ParsedDocument(String format,
                             int pages,
                             int chars,
                             String markdown,
                             List<Heading> headings,
                             List<ParsedTable> tables,
                             int paragraphs,
                             int imageCount,
                             List<DocumentIssue> issues,
                             List<DocElement> elements) {

    /** 紧凑构造器：把"没解析出东西"的两种表达统一成空列表，下游不必两处判空。 */
    public ParsedDocument {
        markdown = markdown == null ? "" : markdown;
        headings = headings == null ? List.of() : List.copyOf(headings);
        tables = tables == null ? List.of() : List.copyOf(tables);
        issues = issues == null ? List.of() : List.copyOf(issues);
        elements = elements == null ? List.of() : List.copyOf(elements);
    }

    /**
     * 每页平均字符数 —— **「无文本层」检测的判据**（见 {@link TextLayerGuard}）。
     *
     * <p>{@code pages <= 0} 时返回 {@code chars}：不分页的格式（TXT/MD）按"整篇一页"算。
     * 这样调用方不必先判页数，一个值就能表达"密不密"。
     */
    public double charsPerPage() {
        return pages <= 0 ? chars : (double) chars / pages;
    }

    /** 有多少张表**抽到了内容**（与 {@link #tableTotal()} 的差就是"识别不到"的那些）。 */
    public int tableParsed() {
        return (int) tables.stream().filter(ParsedTable::hasContent).count();
    }

    /**
     * 表格总数。
     *
     * <p><b>口径（`T-0S1` §六-3）</b>：**单列空表不计** —— 那是版式分隔线被误判成表格的产物
     * （实测 `1-DeepSeek使用.pdf` 的 9 张表里有 2 张是 `1×1` 的空表）。
     * 「多行但单元格全空」的表**计**，因为那更像是"识别不出内容的真表格"，
     * 而它正是 {@code G4-4} 要求可见的那种失败。
     */
    public int tableTotal() {
        return (int) tables.stream().filter(t -> !t.layoutArtifact()).count();
    }

    /**
     * 标题路径注入（`T-507`）与分片（`T-503`）都会用到的一跳。
     *
     * <p>返回形如 {@code ["3. 接口设计", "3.2 错误码规范"]} 的层级路径 ——
     * 契约里的 {@code titlePath} 字段就是这样拼出来的（`10` §一）。
     * 本批先把结构备好，因为**它是从解析产物里唯一能算出来的东西**，
     * 等到 `T-507` 再回头猜"标题在原文哪儿"就晚了。
     *
     * @param index 第几个结构单元（与 {@link #headings} 的下标一致）
     */
    public List<String> titlePathAt(int index) {
        List<String> path = new ArrayList<>();
        for (int i = 0; i <= index && i < headings.size(); i++) {
            Heading h = headings.get(i);
            // 新标题出现时，先把层级 >= 它的那些弹掉（标准 TOC 栈操作）
            while (!path.isEmpty() && path.size() >= h.level()) {
                path.removeLast();
            }
            path.add(h.text());
        }
        return List.copyOf(path);
    }

    /**
     * 文档顺序上的一个元素（`S4-M05-C1` 步 0.6 新增）。
     *
     * <h3>为什么需要它 —— 而不是直接用 {@link #headings()} + {@link #tables()}</h3>
     * <p>这三份列表各自有序，但**彼此的相对位置丢了**：知道"有哪些标题"和"有哪些段落"，
     * 却不知道"这段正文属于哪个标题"。而 {@code T-503} 的第一级分片正是
     * 「按标题层级锁结构边界」—— 没有归属关系就没有边界。
     *
     * <p>本字段落地前的实测症状：`PdfDocumentParser` 按序遍历元素树，却把它们**分桶**进
     * 三个列表；`buildMarkdown` 随后"先把标题全拼完、再拼段落、再拼表格"，
     * 于是连 {@code markdown} 都不是文档顺序的忠实还原。而 `TikaDocumentParser` 是
     * 流式按序拼的 —— **两条路径产出的结构还不一致**。本字段把两条路收敛到同一形状。
     *
     * <h3>派生关系（单一事实源）</h3>
     * <p>{@code headings} / {@code paragraphs} / {@code markdown} 都**由本序列派生**，
     * 不再各自独立累积 —— 否则它们会漂移，而漂移了没人看得出来。
     *
     * @param kind       元素种类
     * @param text       文本。`HEADING`/`PARAGRAPH` 是原文；`TABLE`/`IMAGE` 为 {@code null}
     *                   （表格内容在 {@link #tables()} 里，用 {@link #tableIndex()} 取）
     * @param pageNo     所在页码，1-based
     * @param level      仅 `HEADING` 有意义：1~6；**非标题为 {@code 0}**（标题层级不可能为 0，无歧义）
     * @param tableIndex 仅 `TABLE` 有意义：{@link #tables()} 的**下标（0-based，0 是合法值）**；
     *                   **非表格为 {@code -1}** —— 这里必须用负数哨兵，
     *                   因为 0 是一个真实的表格下标（与 `parentId` 禁用 0 的理由同源：0 有真身）
     */
    public record DocElement(Kind kind, String text, int pageNo, int level, int tableIndex) {

        /** 元素种类。与 {@link com.knowledge.ai.core.enums.BlockType} 不是一回事 ——
         * 那是**分片后**的块类型（8 种，含摘要与 HyQ），这是**解析产物**的元素形状。 */
        public enum Kind {
            HEADING, PARAGRAPH, TABLE, IMAGE
        }

        /** 排序恒为文档顺序 —— 分片靠的就是它，不该有第二种顺序。 */
        public static DocElement heading(int level, String text, int pageNo) {
            return new DocElement(Kind.HEADING, text, pageNo, level, -1);
        }

        public static DocElement paragraph(String text, int pageNo) {
            return new DocElement(Kind.PARAGRAPH, text, pageNo, 0, -1);
        }

        public static DocElement table(int tableIndex, int pageNo) {
            return new DocElement(Kind.TABLE, null, pageNo, 0, tableIndex);
        }

        public static DocElement image(int pageNo) {
            return new DocElement(Kind.IMAGE, null, pageNo, 0, -1);
        }
    }

    /**
     * 一个标题节点。
     *
     * @param level 1~6（H1~H6）；{@code Doctitle} 之类的非数字层级归 1
     * @param pageNo 所在页码，1-based
     */
    public record Heading(int level, String text, int pageNo) {
    }

    /**
     * 一张表。
     *
     * @param caption 表题（<b>题注优先</b>：作者写的比识别出来的准 —— 与《06》§1.4.7 图片同一条口径）
     * @param pageNo  所在页码（`G4-11` 要"第几页"）
     * @param rows    逐行的单元格文本。**空串的单元格保留占位**，否则列会错位
     *                （这是"行列对齐"与"看起来对齐"的分界）
     */
    public record ParsedTable(String caption, int pageNo, List<List<String>> rows) {

        public ParsedTable {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }

        /**
         * 是否抽到了内容 —— {@code tableParsed} 的判据。
         *
         * <p>「同行同列的空表」视为未识别：它正是"表格结构检出了、但格子里的文本没抽出来"
         * 这个**静默故障**的形状（扫描件里的表格就长这样）。
         */
        public boolean hasContent() {
            return rows.stream().anyMatch(row -> row.stream().anyMatch(c -> !c.isBlank()));
        }

        /**
         * 是否是版式误判（`T-0S1` §六-3：`| |` / `|---|` 这类单列空表）。
         *
         * <p>判据取"1 行 × 1 列且无内容"：实测样本里被误判的分隔线恰好全是这个形状，
         * 而真正的表格（哪怕只有表头）至少是 1×2。
         */
        public boolean layoutArtifact() {
            return rows.size() == 1 && rows.getFirst().size() == 1
                    && rows.getFirst().getFirst().isBlank();
        }

        public int rowCount() {
            return rows.size();
        }

        public int colCount() {
            return rows.stream().mapToInt(List::size).max().orElse(0);
        }

        /** 转成 Markdown 管道表 —— **给大模型读的那一份**（`T-504` 的父块内容）。 */
        public String toMarkdown() {
            if (rows.isEmpty()) {
                return "";
            }
            int cols = Math.max(1, colCount());
            StringBuilder sb = new StringBuilder();
            if (caption != null && !caption.isBlank()) {
                sb.append("**").append(caption.replace('|', '/')).append("**").append('\n');
            }
            for (int r = 0; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                sb.append('|');
                for (int c = 0; c < cols; c++) {
                    String v = c < row.size() ? row.get(c) : "";
                    // 单元格里的换行必须炸成 <br>：压平会让行列错位（T-0S1 §三的实测结论）
                    sb.append(' ').append(v.replace("|", "\\|").replace("\n", "<br>")).append(' ').append('|');
                }
                sb.append('\n');
                if (r == 0) {
                    sb.append('|');
                    for (int c = 0; c < cols; c++) {
                        sb.append("---|");
                    }
                    sb.append('\n');
                }
            }
            return sb.toString();
        }
    }

    /**
     * 一处逐页问题（落 {@code sys_parse_issue}，驱动 `T-511` 的告警条）。
     *
     * <p>类型上刻意**不**带 {@code docId}/{@code kbId}/{@code docTitle} —— 那三个是
     * "这条问题属于谁"的信息，由落库与查询时补（{@link #issues} 是相对于"这一篇文档"的）。
     * 这样解析器不必知道文档 id，不必知道知识库，也不必知道标题。
     *
     * @param pageNo 页码，1-based（**无"全文级"哨兵值**：整体性失败走 {@code error_msg}，不进本列表）
     * @param reason 可执行的原因（同 {@link ParseException} 的文案纪律）
     */
    public record DocumentIssue(int pageNo, String reason) {
    }

    /**
     * 本批尚未产出、但契约里要求的块类型 —— 留给 `T-504`/`T-505`/`T-512`。
     *
     * <p>写在这里是为了让"解析产物里没有它"这件事**看的见**：
     * {@code SUMMARY} 要等 `T-507` 的文档级调用，{@code HYPOTHESIS_QA} 同理，
     * {@code TABLE} 子块要等 `T-505`。
     */
    public static List<BlockType> pendingBlockTypes() {
        return List.of(BlockType.SUMMARY, BlockType.HYPOTHESIS_QA, BlockType.IMAGE);
    }
}
