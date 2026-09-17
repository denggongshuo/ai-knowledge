package com.knowledge.ai.pipeline;

import com.knowledge.ai.pipeline.routing.NoOpPageContentRouter;
import com.knowledge.ai.pipeline.routing.PageAction;
import com.knowledge.ai.pipeline.routing.PageContentRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * `优化/O2` 的**页级**判据（`S4-E2` 步 1/2/3/4）—— 纯单元，不依赖样本与容器。
 *
 * <h3>它守的是哪个缺陷</h3>
 * <p>「无文本层」原先是**整篇**判定。混合型文档（40 页合同里 2 页是盖章后的扫描插入页）
 * 平均值很高 → guard 放行 → 状态 {@code INDEXING}，而那 2 页内容**永远搜不到**，
 * 且没有任何环节告诉用户"这 2 页不在" —— 正是《02》「静默降级视为缺陷」的残留形态。
 *
 * <h3>页级判据为什么是「绝对零」而不是密度阈值 —— 多条用例都在钉这件事</h3>
 * <p>`O2` §6 原本建议页级阈值取 10。`S4-E2` 步 0 用 5 份真样本证伪了它：
 * 10 会让 **4 份正常文档里的 7 个页面**误报（封面页 10 字 / PPT 导出页 11 字 /
 * 截图页 8 字 —— 它们**都有文本层**）。所以口径是三条、**没有任何阈值参数**：
 * {@code chars > 0 → TEXT}；{@code chars == 0 且有图 → SCANNED}；{@code chars == 0 且无图 → EMPTY}。
 *
 * <p>⚠️ 这些用例**必须能抓住"把阈值接回来"的改动**：谁把 {@code PageKind.of} 改成
 * 密度比较，{@link #shortPageWithFewCharsIsStillText()} 必须立刻转红。
 */
class TextLayerGuardPageLevelTest {

    private static final PageContentRouter ROUTER = new NoOpPageContentRouter();

    // ==================================================================
    // 页级判定的三条口径
    // ==================================================================

    @Test
    @DisplayName("O2 判据 1/2：混合文档 —— 有文本的页不告警，零字有图的页登记为 SCANNED/MISSING")
    void mixedDocumentRegistersOnlyTheScannedPages() {
        // 40 页文档，其中 2 页是扫描插页 —— 正是 O2 §2 表格里那个场景
        ParsedDocument doc = doc(40, Set.of(39, 40), Set.of());

        List<ParsedDocument.DocumentIssue> issues = TextLayerGuard.pageIssues(doc, ROUTER);

        assertThat(issues)
                .as("只有那 2 页扫描页进 issue —— 38 页有文本的页**一条都不该有**")
                .hasSize(2);
        assertThat(issues).extracting(ParsedDocument.DocumentIssue::pageNo)
                .containsExactly(39, 40);
        assertThat(issues).allSatisfy(i -> {
            assertThat(i.kind()).isEqualTo(ParsedDocument.IssueKind.NO_TEXT_LAYER);
            assertThat(i.disposition())
                    .as("v1 不做 OCR，处置结果就是**未处理** —— 这不是占位符，是事实")
                    .isEqualTo(ParsedDocument.Disposition.MISSING);
            assertThat(i.reason())
                    .as("现象 + 下一步动作都要给（G4-3 的文案纪律）")
                    .contains("未提取到任何可复制文本")
                    .contains("启用 OCR");
        });

        assertThat(doc.pagesWithText())
                .as("质量列要能说出「40 页里 38 页有文本」—— 平均值把丢页稀释成了「每页字有点少」")
                .isEqualTo(38);
    }

    @Test
    @DisplayName("O2 判据 5：短但有字的页仍是 TEXT —— 页级不能有密度阈值（步 0 探针的结论）")
    void shortPageWithFewCharsIsStillText() {
        // 实测里最小的"有人写字的页"是 8 个字符（2-提示词工程.pdf p18）；
        // 封面页 10 字、PPT 导出页 11 字、截图页 20 字 —— 全都是正常内容。
        ParsedDocument doc = new ParsedDocument("PDF", 3, 29, "", List.of(), List.of(), 0, 0,
                List.of(),
                List.of(ParsedDocument.DocElement.paragraph("封面标题十个字", 1),
                        ParsedDocument.DocElement.paragraph("版式页十一字啊", 2),
                        ParsedDocument.DocElement.paragraph("八字符页", 3)));

        assertThat(doc.pagesWithText()).isEqualTo(3);
        assertThat(TextLayerGuard.pageIssues(doc, ROUTER))
                .as("O2 §6 建议的「页级阈值 10」会让封面页(10 字)、PPT 页(11 字)、截图页(8 字) 全部误报。"
                        + "本仓已为「误报比漏报更伤」返工过一次（S4-B 的 W1'），不能再来一次")
                .isEmpty();
    }

    @Test
    @DisplayName("O2 判据 4：空白页 = EMPTY/EMPTY_PAGE，且**不算内容缺失**")
    void blankPageIsNotReportedAsMissingContent() {
        ParsedDocument doc = doc(3, Set.of(), Set.of(2, 3));

        List<ParsedDocument.DocumentIssue> issues = TextLayerGuard.pageIssues(doc, ROUTER);

        assertThat(issues).hasSize(2);
        assertThat(issues).allSatisfy(i -> {
            assertThat(i.kind())
                    .as("零字**无图**归空白页，不能与扫描页合并 —— 合并等于把'可能是版式页'"
                            + "说成'是扫描页'，而后者会进告警条")
                    .isEqualTo(ParsedDocument.IssueKind.EMPTY_PAGE);
            assertThat(i.disposition()).isEqualTo(ParsedDocument.Disposition.EMPTY_PAGE);
            assertThat(i.disposition().countsAsMissing())
                    .as("空白页**不计入**『内容缺失』告警 —— 用户对一张空白页没有任何可采取的动作，"
                            + "把它算进告警条只会让红条常态化，而常见的红条没人看")
                    .isFalse();
        });
    }

    // ==================================================================
    // 文档级兜底**没有回归**（判据 3 / 6）
    // ==================================================================

    @Test
    @DisplayName("O2 判据 3：整篇扫描件仍抛 ParseException（文档级兜底未回归），且逐页行仍在")
    void wholeScannedDocumentStillFails() {
        ParsedDocument doc = new ParsedDocument("PDF", 15, 0, "", List.of(), List.of(), 0, 0,
                List.of(), scannedElements(15));

        assertThatThrownBy(() -> TextLayerGuard.check(doc))
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("未提取到可复制文本")
                .as("文案必须给出可执行动作 —— 这是 S4-B 的 P0 项，一行都不能退化")
                .hasMessageContaining("启用 OCR");

        assertThat(TextLayerGuard.pageIssues(doc, ROUTER))
                .as("整篇失败时**仍有**逐页行 —— 失败的文档恰恰最需要逐页定位（G4-11）")
                .hasSize(15);
        assertThat(doc.pagesWithText()).isZero();
    }

    @Test
    @DisplayName("O2 判据 5/6：单页短文档不误报；单页扫描件两层判定一致")
    void singlePageEdgeCases() {
        // 27 字节的 MD 便利贴（S4-B W1' 的原始样本形状）：单页 10 字，**完全正常**
        ParsedDocument note = new ParsedDocument("MD", 1, 10, "", List.of(), List.of(), 0, 0,
                List.of(), List.of(ParsedDocument.DocElement.paragraph("十个字的便利贴", 1)));
        TextLayerGuard.check(note);   // 不抛
        assertThat(TextLayerGuard.pageIssues(note, ROUTER)).isEmpty();

        // 单页扫描件：文档级抛、页级也登记 —— **两层同判**，不会一个说失败一个说没事
        ParsedDocument single = new ParsedDocument("PDF", 1, 0, "", List.of(), List.of(), 0, 0,
                List.of(), List.of(ParsedDocument.DocElement.image(1)));
        assertThatThrownBy(() -> TextLayerGuard.check(single)).isInstanceOf(ParseException.class);
        assertThat(TextLayerGuard.pageIssues(single, ROUTER)).hasSize(1);
    }

    // ==================================================================
    // 合并规则与路由位
    // ==================================================================

    @Test
    @DisplayName("O2 步 2：同一页已有 issue 时页级不重复登记（行粒度 = (文档, 页码)）")
    void pageWithExistingIssueIsNotDuplicated() {
        // 第 2 页：解析器已报过"表格未识别"（零字有图，页级本来也会想报扫描）
        ParsedDocument doc = new ParsedDocument("PDF", 3, 300, "", List.of(), List.of(), 0, 0,
                List.of(ParsedDocument.DocumentIssue.missing(2, "表格未识别",
                        ParsedDocument.IssueKind.TABLE_NOT_PARSED)),
                List.of(ParsedDocument.DocElement.paragraph("x".repeat(150), 1),
                        ParsedDocument.DocElement.image(2),
                        ParsedDocument.DocElement.paragraph("y".repeat(150), 3)));

        List<ParsedDocument.DocumentIssue> merged = TextLayerGuard.merge(doc, ROUTER);

        assertThat(merged)
                .as("第 2 页原本有 1 条（表格类）+ 页级又想加 1 条（扫描类）= 2 条同页 issue。"
                        + "sys_parse_issue 的行粒度是 (文档, 页码)，前端按 docId 去重算「篇数」——"
                        + "重复行会把计数直接翻倍")
                .hasSize(1);
        assertThat(merged.getFirst().kind()).isEqualTo(ParsedDocument.IssueKind.TABLE_NOT_PARSED);
    }

    @Test
    @DisplayName("O2 步 4：路由位真的被调用（不是一个没人调的接口）")
    void routerIsActuallyConsulted() {
        List<ParsedDocument.PageStat> seen = new ArrayList<>();
        PageContentRouter spy = page -> {
            seen.add(page);
            return new PageAction(ParsedDocument.Disposition.OCR_FILLED, "已由测试替身补全");
        };

        ParsedDocument doc = doc(2, Set.of(2), Set.of());
        List<ParsedDocument.DocumentIssue> issues = TextLayerGuard.pageIssues(doc, spy);

        assertThat(seen)
                .as("零字页必须**逐页**问路由 —— 真实文档里扫描页是混进来的（盖章页/附件页/插页），"
                        + "按文档类型分流覆盖不到这种最常见的情况（O2 §4 末句）")
                .hasSize(1);
        assertThat(issues).singleElement()
                .satisfies(i -> assertThat(i.disposition())
                        .as("处置**由路由决定**，不是 guard 写死的 —— 这正是 S6 接 OCR 时只换实现、"
                                + "不动本类与落库、前端的原因")
                        .isEqualTo(ParsedDocument.Disposition.OCR_FILLED));
    }

    @Test
    @DisplayName("O2 步 6：pagesWithText 从 elements 派生（不重新解析）")
    void pagesWithTextIsDerivedFromElements() {
        // `pages = 5`，但只有 3 个元素 —— 一个元素都没有的页必须被补成"零字页"，
        // 否则它们整页缺席，页级告警永远报不出"第几页丢了内容"
        ParsedDocument doc = new ParsedDocument("PDF", 5, 20, "", List.of(), List.of(), 0, 0,
                List.of(),
                List.of(ParsedDocument.DocElement.paragraph("一二三四五", 1),
                        ParsedDocument.DocElement.paragraph("六七八九十", 2),
                        ParsedDocument.DocElement.paragraph("十一十二", 3),
                        ParsedDocument.DocElement.image(4)));

        assertThat(doc.pageStats()).hasSize(5);
        assertThat(doc.pageStats()).extracting(ParsedDocument.PageStat::kind)
                .containsExactly(ParsedDocument.PageKind.TEXT, ParsedDocument.PageKind.TEXT,
                        ParsedDocument.PageKind.TEXT, ParsedDocument.PageKind.SCANNED,
                        ParsedDocument.PageKind.EMPTY);
        assertThat(doc.pagesWithText()).isEqualTo(3);
    }

    @Test
    @DisplayName("O2 步 1：旧产物缺 pageStats 也能算（派生而非新增字段）")
    void oldArtifactWithoutPageStatsStillWorks() {
        // 模拟"本批之前落库的 parse.json"：elements 有、pageStats 概念不存在。
        // 因为 pageStats 是**派生访问器**而不是 record 组件，旧产物照样能算 —— 不必重解析。
        ParsedDocument old = new ParsedDocument("PDF", 2, 100, "", List.of(), List.of(), 0, 0,
                List.of(), List.of(ParsedDocument.DocElement.paragraph("老产物的一页正文", 1)));

        assertThat(old.pageStats()).hasSize(2);
        assertThat(old.pagesWithText()).isEqualTo(1);
        assertThat(TextLayerGuard.pageIssues(old, ROUTER)).hasSize(1);
    }

    // ==================================================================
    // 构造帮手
    // ==================================================================

    /** 造一份 40 页级的产物：`scanned` 里的页零字**带图**，`blank` 里的页零字**不带图**，其余每页 100 字。 */
    private static ParsedDocument doc(int pages, Set<Integer> scanned, Set<Integer> blank) {
        List<ParsedDocument.DocElement> elements = new ArrayList<>();
        int chars = 0;
        for (int p = 1; p <= pages; p++) {
            if (scanned.contains(p)) {
                elements.add(ParsedDocument.DocElement.image(p));
            } else if (blank.contains(p)) {
                // 一个元素都不放 —— 这一页在产物里就是"什么都没有"
            } else {
                elements.add(ParsedDocument.DocElement.paragraph("z".repeat(100), p));
                chars += 100;
            }
        }
        return new ParsedDocument("PDF", pages, chars, "", List.of(), List.of(), 0, 0,
                List.of(), elements);
    }

    private static List<ParsedDocument.DocElement> scannedElements(int pages) {
        List<ParsedDocument.DocElement> elements = new ArrayList<>();
        for (int p = 1; p <= pages; p++) {
            elements.add(ParsedDocument.DocElement.image(p));
        }
        return elements;
    }
}