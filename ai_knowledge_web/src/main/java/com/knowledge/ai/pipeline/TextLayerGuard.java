package com.knowledge.ai.pipeline;

import com.knowledge.ai.pipeline.routing.PageAction;
import com.knowledge.ai.pipeline.routing.PageContentRouter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「无文本层」检测（`T-0S1` §六-1 点名的 **P0 必做项**）—— **两层判据**。
 *
 * <h3>为什么这条检测值得单独一个类</h3>
 * <p>《02-需求总览》把「静默降级视为缺陷」写成了验收口径，而本项目**最典型**的静默故障就是它：
 * 一份 21.8MB 的扫描件，opendataloader 在 **15ms** 内"成功"产出 **0 字符** ——
 * 不加检测，文档会一路走到「已就绪」，用户搜不到任何内容，且**无从知道为什么**。
 * 把判据收拢在一个类里，两条解析路径（PDF / Tika）才不会各写一份、各漂一份。
 *
 * <h3>`S4-E2`（`优化/O2`）：为什么从一层变两层</h3>
 * <p>原判据是**整篇**的：{@code chars == 0} 或（{@code pages >= 2} 且 {@code chars/页 < 20}）。
 * 它挡住的是"整篇扫描件"（做得对），挡不住的是**混合型文档** ——
 * 40 页合同里 38 页有文本层 + 2 页是盖章后的扫描插入页：平均值很高 → guard 放行 →
 * 状态 {@code INDEXING}，而那 2 页内容**永远搜不到**，且没有任何环节告诉用户"这 2 页不在"。
 * 这正是本项目自定的「最不可接受那类故障」的**残留形态**。
 *
 * <p>所以判据分两层，**职责不同、都要保留**：
 * <table border="1">
 *   <caption>两层判据的分工</caption>
 *   <tr><th></th><th>文档级（{@link #check}）</th><th>页级（{@link #pageIssues}）</th></tr>
 *   <tr><td>回答</td><td>整篇还能不能用</td><td>哪几页丢了内容</td></tr>
 *   <tr><td>动作</td><td>抛 {@link ParseException} → 整篇 {@code FAILED}</td><td>登记 issue → 路由处置</td></tr>
 *   <tr><td>判据</td><td>绝对零 + 多页密度（**原样保留**）</td><td>逐页绝对零（见下）</td></tr>
 * </table>
 *
 * <p>⚠️ <b>文档级判据一行未改</b> —— 它已经证明了价值（挡住 21.8MB 空产出），
 * 而且 {@code S4-B} 的 W1' 教训钉死了它的形状（密度只在 {@code pages >= 2} 时生效）。
 * 本批只**新增**页级一层，不动既有那两条。
 *
 * <h3>页级判据为什么取"绝对零"而不是密度阈值</h3>
 * <p>`O2` §6 建议页级阈值取 10 并**先跑样本**。`S4-E2` 步 0 用 5 份真样本跑了，结论是：
 * **10 会让 4 份正常文档里的 7 个页面误报** —— 封面页 10 字、PPT 导出页 11 字、
 * 截图页 8 字，它们**都有文本层**，只是作者写得少（最小 8 vs 真扫描页 0，**间隙干净**）。
 * 这与 W1' 是同一类错：**误报比漏报更伤**（把用户引向一个无从下手的动作）。
 * 口径与依据完整写在 {@link ParsedDocument#pageStats()}。
 */
public final class TextLayerGuard {

    /**
     * 每页字符数下限。低于它视为"无文本层"。
     *
     * <p>⚠️ 数值的依据写在类注释里（实测余量：0 vs 294）。改它之前先重跑样本。
     *
     * <p>⚠️ **它只管文档级**（{@link #check}），页级判定**不用密度** ——
     * 见 {@link ParsedDocument.PageKind} 的三条口径与 `S4-E2` 步 0 的探针结论。
     */
    static final double MIN_CHARS_PER_PAGE = 20.0;

    /** 零字页的文案里"现象"那一半 —— 与下一步动作分开写，两处都要给。 */
    private static final String MISSING_PHENOMENON = "该页未提取到任何可复制文本（疑似整页扫描图）";

    private TextLayerGuard() {
    }

    /**
     * 文档级校验：解析产物"确实抽到了文本"。
     *
     * <p><b>为什么密度判据只在 {@code pages >= 2} 时生效</b>：扫描件的形状是
     * 「**页数多** + 每页几乎没字」，不是「单页文档本来就短」。
     * 一份 27 字节的 MD 便利贴（单页 10 字）是完全正常的文档，
     * 拿密度去卡它会把"短文档"误报成"扫描件"，而给出的建议
     * （补录文本 / 启用 OCR）对用户完全无从下手 —— 误报比漏报更伤。
     * 所以单页文档只判**绝对零**（一个字都没有才是真问题）。
     *
     * <p>⚠️ **本方法在 `S4-E2` 里一行未改**（判据 3/5/6 靠它不回归）。
     *
     * @param parsed 解析产物（非空）
     * @throws ParseException 字符数为 0，或多页文档的每页平均字符数低于 {@link #MIN_CHARS_PER_PAGE}
     */
    public static void check(ParsedDocument parsed) {
        // 1) 绝对零：一个字符都没有。这是扫描件在 opendataloader 下的确切形状
        //    （15 页、15ms、0 字符 —— 24ms 的耗时说明它根本没做逐页解析）。
        if (parsed.chars() <= 0) {
            throw new ParseException(noTextMessage(parsed));
        }
        // 2) 相对稀疏：**多页**文档里字少得反常（如 300 页只有 500 字）。
        //    单看总量会漏掉这种"看似有内容"的中间态，所以多页时才加这一条。
        if (parsed.pages() >= 2 && parsed.charsPerPage() < MIN_CHARS_PER_PAGE) {
            throw new ParseException(noTextMessage(parsed));
        }
    }

    /**
     * 页级判定：给出**该登记为 issue 的那些页**（`优化/O2` 步 2）。
     *
     * <h3>两条排除规则（都不是可选的）</h3>
     * <ol>
     *   <li><b>已经有 issue 的页跳过</b> —— 整篇扫描件的情形下，
     *       {@code PdfDocumentParser} 已经逐页产出 {@code NO_TEXT_LAYER}。
     *       不排除就会**同一页两条 issue**，而 {@code sys_parse_issue} 的行粒度是
     *       (文档, 页码)，前端按页展示 —— 重复行会让"篇数/页数"计数直接翻倍。</li>
     *   <li><b>{@code TEXT} 页不产出任何 issue</b> —— 哪怕它只有 8 个字。
     *       这正是本批推翻"页级密度阈值"的地方，理由见类注释。</li>
     * </ol>
     *
     * <h3>路由位在这里被用上（而不是留一个没人调的接口）</h3>
     * <p>{@code SCANNED} 页的处置**不由本类决定**，而是问 {@link PageContentRouter}。
     * v1 的实现是 {@code NoOpPageContentRouter}（只登记不处理），`S6` 接 OCR/VL 时
     * 换实现即可 —— **本类与落库、前端都不必动**。这就是"路由位先留、能力后接"的落地方式。
     *
     * @param parsed 解析产物（非空）
     * @param router 页内容路由器（v1 传 {@code NoOpPageContentRouter} 实例）
     * @return 需要登记的 issue；**顺序按页码**（与 {@code sys_parse_issue} 的查询序一致）
     */
    public static List<ParsedDocument.DocumentIssue> pageIssues(ParsedDocument parsed,
                                                                PageContentRouter router) {
        Set<Integer> already = new HashSet<>();
        for (ParsedDocument.DocumentIssue issue : parsed.issues()) {
            already.add(issue.pageNo());
        }

        List<ParsedDocument.DocumentIssue> out = new ArrayList<>();
        for (ParsedDocument.PageStat page : parsed.pageStats()) {
            if (already.contains(page.pageNo())) {
                continue;
            }
            switch (page.kind()) {
                case TEXT -> {
                    // 有文本层 —— 无论多短都不登记。见类注释的探针结论。
                }
                case EMPTY -> out.add(ParsedDocument.DocumentIssue.emptyPage(page.pageNo()));
                case SCANNED -> {
                    PageAction action = router.route(page);
                    out.add(new ParsedDocument.DocumentIssue(page.pageNo(),
                            MISSING_PHENOMENON + "，" + action.note(),
                            ParsedDocument.IssueKind.NO_TEXT_LAYER,
                            action.disposition()));
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * 把两层判定的结果合并成最终要落库的那一份。
     *
     * <p>顺序是刻意的：**先解析器自己的 issue**（表格未识别、页眉页脚剥离 —— 它们知道
     * 得比 guard 多），**再页级新增的**。分片与前端都按这个顺序展示。
     */
    public static List<ParsedDocument.DocumentIssue> merge(ParsedDocument parsed,
                                                          PageContentRouter router) {
        List<ParsedDocument.DocumentIssue> merged = new ArrayList<>(parsed.issues());
        merged.addAll(pageIssues(parsed, router));
        return List.copyOf(merged);
    }

    private static String noTextMessage(ParsedDocument parsed) {
        return "未提取到可复制文本（疑似扫描件或纯图片，"
                + parsed.pages() + " 页仅得到 " + parsed.chars() + " 字符），"
                + "该文档内容无法被检索命中；请补录文本版本，或启用 OCR 后重新上传";
    }
}