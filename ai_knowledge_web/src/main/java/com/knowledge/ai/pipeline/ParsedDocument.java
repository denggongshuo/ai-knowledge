package com.knowledge.ai.pipeline;

import com.knowledge.ai.core.enums.BlockType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * 每页平均字符数 —— **文档级「无文本层」检测的判据**（见 {@link TextLayerGuard}）。
     *
     * <p>{@code pages <= 0} 时返回 {@code chars}：不分页的格式（TXT/MD）按"整篇一页"算。
     * 这样调用方不必先判页数，一个值就能表达"密不密"。
     *
     * <p>⚠️ **本方法只用于文档级兜底**。页级判定**不用密度** —— 见 {@link #pageStats()}
     * 与 {@link PageStat} 的口径说明。
     */
    public double charsPerPage() {
        return pages <= 0 ? chars : (double) chars / pages;
    }

    /**
     * **逐页指标**（`优化/O2` 步 1）—— 页级文本层判定的唯一输入。
     *
     * <h3>为什么是派生字段而不是解析时另传一份</h3>
     * <p>它是从 {@link #elements()}（**已带 {@code pageNo}**）+ {@link #tables()} 算出来的
     * 纯函数结果，不读取任何新东西。所以本字段**不是 {@code parse.json} 的字段** ——
     * 它是 record 的**派生访问器**，旧产物照样能算（这正是 `O2` §4 步 1 那句
     * 「**不需要重新解析**」的落地方式）。
     *
     * <h3>页级判据为什么取"绝对零"而不是"密度阈值"</h3>
     * <p>`O2` §6 建议页级阈值取 10。**`S4-E2` 步 0 探针用 5 份真样本证伪了它**：
     * 10 会让 4 份正常文档里的 **7 个页面**误报 —— 封面页 10 字、PPT 导出页 11 字、
     * 截图页 8 字，它们**都有字**，只是作者写得少（最小 8 vs 真扫描页 0，**间隙干净**）。
     * 这与 `S4-B` 的 W1'（单页短文档被误判成扫描件）是**同一类错**：误报比漏报更伤。
     *
     * <p>所以口径固定为三条，**不引入任何阈值参数**：
     * <ul>
     *   <li>{@code chars > 0} → {@link PageKind#TEXT}（哪怕只有 8 个字，也是"有文本层"）；</li>
     *   <li>{@code chars == 0} 且 {@code imageCount > 0} → {@link PageKind#SCANNED}（进告警）；</li>
     *   <li>{@code chars == 0} 且无图 → {@link PageKind#EMPTY}（**不计入"内容缺失"告警**）。</li>
     * </ul>
     * 采样边界写在 {@code plan/2026-09-17-S4-E2.md} §3.4：结论来自 **5 份 PDF**，
     * 第三分支的"无字无图"是为**版式页/空白页**留的独立出口，不与扫描页合并。
     */
    public List<PageStat> pageStats() {
        Map<Integer, int[]> acc = new LinkedHashMap<>();   // pageNo -> [chars, imageCount]

        for (DocElement el : elements) {
            int[] slot = acc.computeIfAbsent(el.pageNo(), k -> new int[2]);
            switch (el.kind()) {
                case HEADING, PARAGRAPH -> slot[0] += el.text() == null ? 0 : el.text().length();
                case IMAGE -> slot[1]++;
                case TABLE -> {
                    // 表格单元格文本计入该页 —— 与文档级 chars 的口径一致
                    // （`汇总.md` §5：「chars = 可检索的文本量，表格必须计入」）
                    ParsedTable t = tables.get(el.tableIndex());
                    slot[0] += t.rows().stream()
                            .flatMap(List::stream)
                            .mapToInt(String::length)
                            .sum();
                }
            }
        }
        // 补上"一个字都没有、也没有任何元素"的页 —— 真扫描件在 opendataloader 下正是这个形状
        // （15 页 / 0 字符 / 每页恰好 1 个 image 元素）。不补的话这些页会**整页缺席**，
        // 页级告警就永远报不出"第几页丢了内容"。
        for (int p = 1; p <= Math.max(pages, 1); p++) {
            acc.computeIfAbsent(p, k -> new int[2]);
        }

        List<PageStat> stats = new ArrayList<>(acc.size());
        acc.forEach((pageNo, v) -> stats.add(
                new PageStat(pageNo, v[0], v[1], PageKind.of(v[0], v[1]))));
        return List.copyOf(stats);
    }

    /**
     * 有几页**判定为有文本层**（`优化/O2` §4 步 6 的 {@code pagesWithText}，
     * 与 {@code pages} 一起进文档列表的质量列）。
     *
     * <p>「40 页里 38 页有文本」比「平均 300 字/页」信息量大得多 —— 后者会被稀释，
     * 前者直接指出丢了 2 页。**它与文档级 {@code chars} 是两把尺**：
     * 一篇 3000 字 / 40 页的文档，`charsPerPage = 75` 看着健康，而 `pagesWithText = 6`
     * 说明 34 页是扫描页 —— 这正是 `O2` 要堵的静默故障形态。
     */
    public int pagesWithText() {
        return (int) pageStats().stream().filter(s -> s.kind() == PageKind.TEXT).count();
    }

    /**
     * 一个页面的**逐页指标**（`优化/O2` 步 1）。
     *
     * <p>⚠️ 它是**派生值**（见 {@link #pageStats()}），不是 {@code parse.json} 的字段 ——
     * 所以旧产物也能算，改口径不必重解析。
     *
     * @param pageNo     页码，1-based
     * @param chars      该页的可检索字符数（标题 + 段落 + **表格单元格**，与文档级同算法）
     * @param imageCount 该页 IMAGE 元素个数
     * @param kind       页级判定，见 {@link PageKind}
     */
    public record PageStat(int pageNo, int chars, int imageCount, PageKind kind) {
    }

    /**
     * 页面种类（`优化/O2` 步 1）。
     *
     * <p><b>判据是"绝对零"，没有任何阈值参数</b> —— 依据是 `S4-E2` 步 0 的实测
     * （见 {@link #pageStats()}）。这里刻意**不提供** {@code ofCharsPerPage(double)} 之类
     * 的密度工厂方法：`O2` §6 原本建议的"页级阈值 10"已被数据证伪，把那个入口留着，
     * 下一个人就会把它接回去。
     */
    public enum PageKind {

        /** 该页抽到了文本（哪怕只有几个字 —— 封面页也是"有文本层"）。 */
        TEXT,

        /** 该页零字**且有整页图像** → 疑似扫描页，进"内容缺失"告警。 */
        SCANNED,

        /** 该页零字**且无图** → 空白页 / 纯版式页，**不计入"内容缺失"告警**。 */
        EMPTY,
        ;

        /**
         * 判定一页。
         *
         * <p>{@code chars == 0 且 imageCount == 0} 归 {@link #EMPTY} 而不是 {@link #SCANNED}：
         * 本项目「误报比漏报更伤」的排序下，把一个"看起来空白"的页说成"疑似扫描件"
         * 会把用户引向一个无从下手的动作（"启用 OCR"对一张空白页没有意义）。
         * 采样边界见 {@link #pageStats()} 的 javadoc。
         */
        static PageKind of(int chars, int imageCount) {
            if (chars > 0) {
                return TEXT;
            }
            return imageCount > 0 ? SCANNED : EMPTY;
        }

        /** 是否属于"内容缺失"（驱动 `T-511` 告警条与质量列）。 */
        public boolean missing() {
            return this == SCANNED;
        }
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
     * @param pageNo      页码，1-based（**无"全文级"哨兵值**：整体性失败走 {@code error_msg}，不进本列表）
     * @param reason      可执行的原因（同 {@link ParseException} 的文案纪律）
     * @param kind        问题**种类**（`S4-M05-C2` 步 0.6 新增，见 {@link IssueKind}）
     * @param disposition **处置结果**（`S4-E2` 新增，见 {@link Disposition}）——
     *                    让 issue 从"通知"升级为"**处置记录**"：企业知识库要能回答
     *                    "这条问题被处理了吗"，而不是只回答"这里有个问题"
     */
    public record DocumentIssue(int pageNo, String reason, IssueKind kind, Disposition disposition) {

        /**
         * 缺省值 —— 兼容**旧产物**用的。
         *
         * <p>{@code kind} 是 2026-09-16 新增、{@code disposition} 是 2026-09-17 新增。
         * 在它们之前落库的 {@code parse.json} 里没有对应项，Jackson 会传 {@code null}
         * 进来（记录类的紧凑构造器就是干这个的）。不做兜底，读旧产物会直接炸在反序列化上
         * —— 而那正是"换个批次就跑不起来"的形态。
         *
         * <p>⚠️ {@code disposition} 的兜底是 {@link Disposition#MISSING} 而不是 {@code null}：
         * 旧产物里能落进 {@code sys_parse_issue} 的，按当时的语义**全部是"未处理"**
         * （那时根本没有 OCR/VL 能力）。留 {@code null} 会让前端在两代数据上走两条渲染分支。
         */
        public DocumentIssue {
            kind = kind == null ? IssueKind.OTHER : kind;
            disposition = disposition == null ? Disposition.MISSING : disposition;
        }

        /** 便捷构造：默认处置为"未处理"（绝大多数 issue 就是它）。 */
        public static DocumentIssue missing(int pageNo, String reason, IssueKind kind) {
            return new DocumentIssue(pageNo, reason, kind, Disposition.MISSING);
        }

        /** 便捷构造：空白页 —— 归 EMPTY_PAGE 且**不进"内容缺失"告警**。 */
        public static DocumentIssue emptyPage(int pageNo) {
            return new DocumentIssue(pageNo, "该页无任何内容（空白页或纯版式页），无需处理",
                    IssueKind.EMPTY_PAGE, Disposition.EMPTY_PAGE);
        }
    }

    /**
     * 一处 issue 的**处置结果**（`优化/O2` §4 步 3）。
     *
     * <h3>为什么要专门一个字段，而不是靠 {@link IssueKind} 区分</h3>
     * <p>种类回答"这是什么问题"，处置回答"**这个问题被怎么办了**" —— 两者是正交的：
     * 同一个"无文本层"可以是 {@link #MISSING}（没能力补）也可以是
     * {@link #OCR_FILLED}（能力接上后已补全，内容**已经能检索**）。
     * 前端告警条要按后者分流：前者是"要不要管"，后者是"已经管好了"。
     * 挤在一个字段里，接 OCR 的那天就没法区分"这篇还会复发"与"这篇已解决"。
     *
     * <p>v1 只有前两个值会出现（{@link #OCR_FILLED} 等 `S6` 的 `T-512`/`T-907`）。
     * **枚举先行、能力后接** —— 与 {@code PageContentRouter} 同一个思路：
     * 形状留好，接能力时不必改落库与前端。
     */
    public enum Disposition {

        /** 内容缺失，**本批未处理**（v1 恒走此路 —— OCR/VL 口径不做）。 */
        MISSING,

        /** 已由 OCR/VL **补全**，内容可检索（`S6` 起可达）。 */
        OCR_FILLED,

        /** 空白页 / 纯版式页 —— **不算问题**，只登记不告警。 */
        EMPTY_PAGE,
        ;

        /** 是否要在告警条上计为"内容缺失"。 */
        public boolean countsAsMissing() {
            return this == MISSING;
        }
    }

    /**
     * 逐页问题的**种类**（`S4-M05-C2` 步 0.6）。
     *
     * <h3>为什么现在才加</h3>
     * <p>在这之前 {@code DocumentIssue} 只有一句自由文本，于是所有断言只能写成
     * 「{@code reason} 里包含某个中文字串」—— 那种断言在**改一次文案**时就转红，
     * 而它对"这是哪一类问题"其实一无所知。种类是机器可读的那一半，
     * 文案是给人看的那一半，两者不该挤在同一个字段里。
     *
     * <h3>⚠️ 它落库时会退化成文字前缀 —— `S4-E2` 起**不再退化**</h3>
     * <p>{@code sys_parse_issue} 原本**没有**种类列（`S4-M05-C2` 不加迁移），所以写入时
     * 由 {@link #tag()} 拼在 {@code reason} 前面。也就是说：**种类在库里是文字，
     * 不是可查询的列**。要按种类过滤就得加列 —— 那件事留给真正需要它的批次。
     *
     * <p>✅ **`S4-E2` 就是那个批次**：{@code V18} 给 {@code sys_parse_issue} 加了
     * {@code kind} / {@code disposition} 两列，写入改走列，{@code reason} 回归**纯原因文本**。
     * {@link #tag()} 因此只服务于**旧产物**（`parse.json` 里没有列的那种），不再进库。
     *
     * <h3>为什么不用它替掉 reason</h3>
     * <p>本项目对失败态的要求是「给出具体原因，不许只说'失败'两字」（`G4-3`）。
     * 种类只回答"哪一类"，`reason` 才回答"我能做什么"。两者都留着。
     */
    public enum IssueKind {

        /** 整页抽不到可复制文本（疑似扫描图）。 */
        NO_TEXT_LAYER("无文本层"),

        /** 表格结构检出了、格子却没内容（静默故障的正面）。 */
        TABLE_NOT_PARSED("表格未识别"),

        /** 重复样板（页眉/页脚）已从正文中剥离（`优化/O3`）。 */
        HEADER_FOOTER_STRIPPED("页眉页脚已剥离"),

        /**
         * 空白页 / 纯版式页（`S4-E2` 新增）。
         *
         * <p>与 {@link #NO_TEXT_LAYER} 的分界是 `S4-E2` 步 0 探针量出来的：**零字 + 无图**。
         * 它进 {@code sys_parse_issue} 是为了**看得见**，但 {@code disposition = EMPTY_PAGE}
         * 让它**不计入"内容缺失"告警** —— 一张空白页不是需要用户去补内容的问题。
         */
        EMPTY_PAGE("空白页"),

        /** 其余 —— 也是**旧产物**缺 {@code kind} 时的兜底。 */
        OTHER("其他"),
        ;

        private final String label;

        IssueKind(String label) {
            this.label = label;
        }

        /** 人读的中文名（前端与日志用）。 */
        public String label() {
            return label;
        }

        /**
         * 落 {@code sys_parse_issue.reason} 时拼在前面的**文字前缀**。
         *
         * <p>⚠️ **`S4-E2` 起本方法不再用于落库** —— `V18` 已把种类提升为 {@code kind} 列。
         * 保留它是为了**旧产物**（`parse.json` 与库里已带前缀的旧行）：前端与诊断代码
         * 读老数据时仍可能看到这个前缀，需要一个解析它的地方。
         */
        public String tag() {
            return "【" + label + "】";
        }
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
