package com.knowledge.ai.pipeline.cleaning;

import com.knowledge.ai.pipeline.ParsedDocument;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 逐文档清洗层（`优化/O3`）—— **解析与分片之间**的那一环。
 *
 * <h3>为什么它与解析器分开（`O3` §4 的收尾段）</h3>
 * <p>解析器的职责是「忠实抽取」，清洗的职责是「判定什么是噪音」。混在一起会让解析器
 * 变成一个"各文档类型各有一套规则"的泥球，而且**改清洗规则要重新解析**。
 * 分开之后清洗是对 {@link ParsedDocument} 的**纯函数变换**：可单测、可回放、可 A/B。
 * 本类就是那个变换，且刻意**无状态**（除了 Spring 的注册，它不持有任何字段）。
 *
 * <h3>它在哪里被调用 —— 顺序是有讲究的</h3>
 * <pre>
 *   解析 → dispatch → TextLayerGuard.check → 【清洗】 → 落产物 → 分片
 * </pre>
 * <ul>
 *   <li>在 {@code TextLayerGuard} **之后**：那位的判据吃 {@code chars}，而清洗**只减不增**。
 *       先清洗再体检，等于让一篇正常文档因为"剥了页眉"而掉到扫描件的阈值以下 —— 那是误报。</li>
 *   <li>在**落产物之后、分片之前**（{@code DocParseService} 的实际排布）：
 *       {@code parse.json} 存的是**原始产物**（{@code O3` 的分层要求），
 *       分片吃的是**清洗后**的元素序列。所以换清洗阈值不必重新解析，重新分片即可。</li>
 * </ul>
 *
 * <h3>⚠️ 本批只做页眉页脚，装饰图判定挂起</h3>
 * <p>`O3` §4 步 3 的装饰图判定（三条件：跨文档 pHash 重复 + 尺寸/位置固定 + 无题注）
 * **本批不做**，2026-09-16 用户裁定挂到 **S6（`T-512`）**。两条理由写在
 * {@code 2026-09-16-S4-M05-C2.md} §9 第 1 条，摘要：
 * ① pHash 需要图像字节，而解析产物里只有元素存在性 —— 最强的那个判据本批根本拿不到；
 * ② 图片本批**不进索引**（`IMAGE` 块等 `T-512`），所以它**没有**"建向量前必须做"的硬窗，
 * 而现在做等于把两条判据的版本先上一遍、等 `T-512` 有字节了再重做一遍。
 *
 * <p>连带后果：{@code image_total} 本批按**原始图片数**回填（不再恒 0，销掉
 * `S4-B` §9 第 3 条的"采了不用"），但它**不含**装饰图扣减 ——
 * 前端会看到"有 N 张图（未理解）"，其中可能混着 logo。这一点已登记为遗留。
 */
@Service
public class DocumentCleaner {

    /**
     * 清洗一份解析产物。
     *
     * @param doc     原始产物（来自 {@code T-502} 的三条解析路径之一）
     * @param options 该知识库的清洗参数。传 {@link CleaningOptions#defaults()} 即默认行为
     * @return 清洗后的产物 + 被剥离清单。**没剥到东西时返回的产物与入参内容一致**
     *         （{@code stripped} 为空），调用方据此可以完全跳过后续处理
     */
    public CleanedDocument clean(ParsedDocument doc, CleaningOptions options) {
        if (doc == null) {
            throw new IllegalArgumentException("清洗输入不能为空");
        }
        CleaningOptions opts = options == null ? CleaningOptions.defaults() : options;

        HeaderFooterStripper.Result result =
                HeaderFooterStripper.detect(doc.elements(), doc.pages(), opts);
        if (result.stripped().isEmpty()) {
            // 一处都没剥：**原样返回入参**，不新建一个"看起来一样的"对象。
            // 这样"清洗没改任何东西"是可证的（同一引用），而不是"看起来没变"。
            return new CleanedDocument(doc, List.of());
        }

        return new CleanedDocument(recompute(doc, result.kept(), result.stripped()), result.stripped());
    }

    /**
     * 用清洗后的元素序列**重新派生**产物里的派生字段。
     *
     * <h3>为什么不把原产物的字段直接带过来</h3>
     * <p>{@link ParsedDocument} 的 javadoc 写着"单一事实源"：{@code headings} /
     * {@code paragraphs} / {@code chars} 都由 {@code elements} **派生**。
     * 剥掉元素却留着旧的派生值，等于让它们漂移 —— 而漂移了没人看得出来，
     * 这正是本项目反复吃亏的那种形态（{@code S4-M05-C2` 步 0.5 的 8.1%）。
     *
     * <p>所以这里逐项重算：
     * <ul>
     *   <li>{@code headings} ← 滤后序列里的 HEADING（**不能过滤原 {@code headings} 列表**：
     *       那样得靠文本匹配，两个相同标题会一起消失）；</li>
     *   <li>{@code paragraphs} ← 滤后序列里的 PARAGRAPH 计数；</li>
     *   <li>{@code chars} ← 滤后序列里标题与正文的字符数 + **全部**表格的字符数。
     *       算式与 {@code PdfDocumentParser} 保持一致：表格不进元素文本，要单独加；</li>
     *   <li>{@code tables} / {@code pages} / {@code format} / {@code imageCount} ← **不变**。
     *       剥离只动文本元素，表格与图片的清单、下标都不受影响
     *       （{@code DocElement.tableIndex} 因此仍然指向正确的表）；</li>
     *   <li>{@code markdown} ← 原样带过，理由见 {@link CleanedDocument} 的 javadoc。</li>
     * </ul>
     *
     * <p>{@code issues} = 原 issues **加上**剥离记录 —— `O3` §4 步 6：
     * 剥离是**处置**，不是**隐瞒**。原来的 issue 一条不动。
     */
    private static ParsedDocument recompute(ParsedDocument doc, List<ParsedDocument.DocElement> kept,
                                            List<CleanedDocument.StrippedPattern> stripped) {
        List<ParsedDocument.Heading> headings = new ArrayList<>();
        int paragraphs = 0;
        int chars = 0;
        for (ParsedDocument.DocElement el : kept) {
            switch (el.kind()) {
                case HEADING -> {
                    headings.add(new ParsedDocument.Heading(el.level(), el.text(), el.pageNo()));
                    chars += el.text().length();
                }
                case PARAGRAPH -> {
                    paragraphs++;
                    chars += el.text().length();
                }
                // 表格的字符不在元素文本里（元素只有下标），下面统一加；图片不计字符
                case TABLE, IMAGE -> {
                }
            }
        }
        chars += doc.tables().stream()
                .flatMap(t -> t.rows().stream())
                .flatMap(List::stream)
                .mapToInt(String::length)
                .sum();

        List<ParsedDocument.DocumentIssue> issues = new ArrayList<>(doc.issues());
        issues.addAll(toIssues(stripped));

        return new ParsedDocument(doc.format(), doc.pages(), chars, doc.markdown(),
                headings, doc.tables(), paragraphs, doc.imageCount(), issues, kept);
    }

    /**
     * 剥离清单 → 逐页问题（`O3` §4 步 6）。
     *
     * <h3>粒度：一个模式一条，锚在它**首次出现**的页</h3>
     * <p>不按页展开：一篇 20 页的文档若每页都有页眉，展开就是 20 行，
     * 而上传页的告警条是按页铺的 —— 那样会把它冲垮，真正的问题反而看不见了
     * （"喊狼来了"的代价是没人再看告警条）。一个模式一行、写清
     * 「第 N 页起，共 M 页出现」，信息量足够定位，也不至于淹没别的告警。
     *
     * <p>{@code page_no} 用**首次出现的真实页码**，不是全文级哨兵 ——
     * {@code sys_parse_issue} 的建表口径明确禁止哨兵值（"那样才会有
     * 'page_no = 0 是什么意思'这种要靠注释才能读懂的行"）。
     *
     * <p>文案按本项目纪律给**现象 + 下一步**（`G4-3`），并且**说清剥离是有意的** ——
     * 否则用户看到"少了一段"只会当成 bug。
     */
    private static List<ParsedDocument.DocumentIssue> toIssues(
            List<CleanedDocument.StrippedPattern> stripped) {
        List<ParsedDocument.DocumentIssue> issues = new ArrayList<>();
        for (CleanedDocument.StrippedPattern s : stripped) {
            issues.add(ParsedDocument.DocumentIssue.missing(s.firstPage(),
                    "该文档的「" + s.sample() + "」出现在 " + s.occurrences() + " 页的页面边缘位置，"
                            + "判定为页眉/页脚样板，已从正文中剥离（共 " + s.chars()
                            + " 字符），以免它每页都进入检索、稀释语义。"
                            + "若这段文字确有需要被检索的内容，请告知管理员调整清洗阈值。",
                    ParsedDocument.IssueKind.HEADER_FOOTER_STRIPPED));
        }
        return issues;
    }
}
