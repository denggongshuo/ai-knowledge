package com.knowledge.ai.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `S4-M05-C1` 步 0.6：`elements`（按文档顺序的元素序列）取证。
 *
 * <h3>这条用例防的是哪个 bug</h3>
 * <p>落 `elements` 之前，`PdfDocumentParser` 按序遍历元素树、却把元素**分桶**进
 * `headings`/`paragraphs`/`tables`，`buildMarkdown` 随后"先把标题全拼完、再拼段落、再拼表格"。
 * 症状不是崩溃，而是**结构错位**：`markdown` 里第二章的标题会排在第一章的正文**前面**。
 * 老代码的注释把它写成了「按元素出现顺序的**近似**」—— 那个"近似"其实不成立。
 *
 * <p>所以关键断言是**第二个标题的位置**：它在文档顺序上应在第一段正文之后。
 * 分桶拼的版本会把它排到最前，这条就会红。
 *
 * <p>同一形状的 bug 在 `TikaDocumentParser` 上没有（它一直是流式按序的）——
 * 两条路径**结构不一致**本身就是问题（下游要面对两种 markdown 形态），故两边都断言。
 */
class ParsedDocumentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================================================================
    // PDF 路径：opendataloader 的 JSON 元素树
    // ==================================================================

    /** 元素树是**交错**的：标题 → 正文 → 表格 → 图片 → 正文 → 标题 → 正文。 */
    private static final String OPENDATALOADER_JSON = """
            {
              "title": "规范v2",
              "number of pages": 3,
              "kids": [
                {"type": "heading", "heading level": "1", "page number": 1, "content": "第一章 总则"},
                {"type": "paragraph", "page number": 1, "content": "正文甲"},
                {"type": "table", "title": "费率表", "page number": 2,
                 "rows": [{"cells": [{"kids": [{"content": "型号"}]}, {"kids": [{"content": "单价"}]}]},
                          {"cells": [{"kids": [{"content": "A-100"}]}, {"kids": [{"content": "￥1,200"}]}]}]},
                {"type": "image", "page number": 2},
                {"type": "paragraph", "page number": 2, "content": "正文乙"},
                {"type": "heading", "heading level": "2", "page number": 3, "content": "第二章 附则"},
                {"type": "paragraph", "page number": 3, "content": "正文丙"}
              ]
            }
            """;

    @Test
    @DisplayName("步 0.6：PDF 路径的 elements 严格按文档顺序（标题与段落不再分家）")
    void pdfElementsFollowDocumentOrder() throws Exception {
        ParsedDocument doc = PdfDocumentParser.fromJson(MAPPER.readTree(OPENDATALOADER_JSON));

        assertThat(doc.elements()).extracting(ParsedDocument.DocElement::kind).containsExactly(
                ParsedDocument.DocElement.Kind.HEADING,
                ParsedDocument.DocElement.Kind.PARAGRAPH,
                ParsedDocument.DocElement.Kind.TABLE,
                ParsedDocument.DocElement.Kind.IMAGE,
                ParsedDocument.DocElement.Kind.PARAGRAPH,
                ParsedDocument.DocElement.Kind.HEADING,
                ParsedDocument.DocElement.Kind.PARAGRAPH);

        assertThat(doc.elements())
                .as("标题的层级要随元素带出来 —— T-503 的第一级分片靠它锁结构边界")
                .filteredOn(e -> e.kind() == ParsedDocument.DocElement.Kind.HEADING)
                .extracting(ParsedDocument.DocElement::level)
                .containsExactly(1, 2);

        assertThat(doc.elements())
                .as("页码要随元素带出来（引用卡片显示「第 N 页」）")
                .extracting(ParsedDocument.DocElement::pageNo)
                .containsExactly(1, 1, 2, 2, 2, 3, 3);

        assertThat(doc.elements())
                .as("TABLE 元素用 tableIndex 指向 tables 里的那张，不是复制一份内容")
                .filteredOn(e -> e.kind() == ParsedDocument.DocElement.Kind.TABLE)
                .extracting(ParsedDocument.DocElement::tableIndex)
                .containsExactly(0);
    }

    @Test
    @DisplayName("步 0.6：markdown 与 elements 同序 —— 第二个标题必须排在正文之后（旧的\"分桶拼\"在此为红）")
    void pdfMarkdownFollowsElementsOrder() throws Exception {
        ParsedDocument doc = PdfDocumentParser.fromJson(MAPPER.readTree(OPENDATALOADER_JSON));
        String md = doc.markdown();

        int firstHeading = md.indexOf("第一章 总则");
        int firstBody = md.indexOf("正文甲");
        int tableRow = md.indexOf("A-100");
        int secondBody = md.indexOf("正文乙");
        int secondHeading = md.indexOf("第二章 附则");

        assertThat(List.of(firstHeading, firstBody, tableRow, secondBody, secondHeading))
                .as("五个锚点都要在 markdown 里找得到（找不到时 indexOf 返回 -1，顺序断言会失去意义）")
                .allMatch(i -> i >= 0);

        assertThat(firstHeading).isLessThan(firstBody);
        assertThat(firstBody).isLessThan(tableRow);
        assertThat(tableRow)
                .as("表格要排在它前后的正文之间 —— 分桶拼的版本会把表格甩到最后")
                .isLessThan(secondBody);
        assertThat(secondBody)
                .as("这是本用例的**关键断言**：分桶拼的版本把所有标题排在最前，"
                        + "「第二章 附则」会跑到「正文甲」前面。结构错位就是这么发生的 —— 不崩，但全歪")
                .isLessThan(secondHeading);
    }

    @Test
    @DisplayName("步 0.6：markdown 与 elements 逐段对应（同一条记录的两面，不得漂移）")
    void markdownMirrorsElements() throws Exception {
        ParsedDocument doc = PdfDocumentParser.fromJson(MAPPER.readTree(OPENDATALOADER_JSON));

        for (ParsedDocument.DocElement el : doc.elements()) {
            switch (el.kind()) {
                case HEADING, PARAGRAPH ->
                        assertThat(doc.markdown())
                                .as("%s 元素「%s」必须出现在 markdown 里 —— "
                                        + "两边各自累积就会漂移，而漂移了没人看得出来", el.kind(), el.text())
                                .contains(el.text());
                case TABLE ->
                        assertThat(doc.markdown())
                                .as("表格元素要由对应的 tables[%d] 渲染进 markdown", el.tableIndex())
                                .contains("A-100");
                case IMAGE -> assertThat(doc.markdown())
                        .as("图片在 T-512 产出 VL 描述之前不该往正文里放占位 —— 那会变成一条假正文")
                        .doesNotContain("[图片]");
            }
        }

        assertThat(doc.headings())
                .as("headings 是由 elements 派生的视图，不是另一份独立累积的列表")
                .hasSize(2);
        assertThat(doc.paragraphs())
                .as("同上：三条正文段落")
                .isEqualTo(3);
    }

    // ==================================================================
    // Tika 路径：XHTML 流（本来就是按序的 —— 断的是"与 PDF 同形"）
    // ==================================================================

    private static final String XHTML = """
            <html xmlns="http://www.w3.org/1999/xhtml"><body>
            <h1>第一章 总则</h1>
            <p>正文甲</p>
            <table><tr><td>型号</td><td>单价</td></tr><tr><td>A-100</td><td>￥1,200</td></tr></table>
            <p>正文乙</p>
            <h2>第二章 附则</h2>
            <p>正文丙</p>
            </body></html>
            """;

    @Test
    @DisplayName("步 0.6：Tika 路径同样产出按序 elements —— 两条路径结构不再分叉")
    void tikaElementsFollowDocumentOrder() {
        ParsedDocument doc = new TikaDocumentParser.XhtmlToMarkdown().convert(XHTML, "DOCX");

        assertThat(doc.elements()).extracting(ParsedDocument.DocElement::kind).containsExactly(
                ParsedDocument.DocElement.Kind.HEADING,
                ParsedDocument.DocElement.Kind.PARAGRAPH,
                ParsedDocument.DocElement.Kind.TABLE,
                ParsedDocument.DocElement.Kind.PARAGRAPH,
                ParsedDocument.DocElement.Kind.HEADING,
                ParsedDocument.DocElement.Kind.PARAGRAPH);

        String md = doc.markdown();
        int firstHeading = md.indexOf("第一章 总则");
        int firstBody = md.indexOf("正文甲");
        int secondBody = md.indexOf("正文乙");
        int secondHeading = md.indexOf("第二章 附则");

        assertThat(List.of(firstHeading, firstBody, secondBody, secondHeading)).allMatch(i -> i >= 0);
        assertThat(firstHeading).isLessThan(firstBody);
        assertThat(firstBody)
                .as("与 PDF 路径**同一条断言** —— 这才是\"两条路径结构一致\"的可执行形式")
                .isLessThan(secondBody);
        assertThat(secondBody).isLessThan(secondHeading);
    }

    // ==================================================================
    // 纯文本路径
    // ==================================================================

    @Test
    @DisplayName("步 0.6：MD 路径的 # 行是 HEADING 元素，其余行是 PARAGRAPH —— 顺序即行序")
    void markdownFileYieldsOrderedElements() {
        byte[] md = """
                # 第一讲 Netty 为什么快
                它把 IO 多路复用藏了起来。
                ## 1.1 三大件
                Channel、Buffer、Selector。
                """.getBytes(StandardCharsets.UTF_8);

        ParsedDocument doc = PlainTextParser.parse(md, "第01讲-笔记.md");

        assertThat(doc.elements()).extracting(ParsedDocument.DocElement::kind).containsExactly(
                ParsedDocument.DocElement.Kind.HEADING,
                ParsedDocument.DocElement.Kind.PARAGRAPH,
                ParsedDocument.DocElement.Kind.HEADING,
                ParsedDocument.DocElement.Kind.PARAGRAPH);
        assertThat(doc.elements()).extracting(ParsedDocument.DocElement::level)
                .as("非标题元素的 level 恒 0（标题层级不可能为 0，无歧义哨兵）")
                .containsExactly(1, 0, 2, 0);
        assertThat(doc.elements())
                .as("纯文本没有页的概念 —— pageNo 恒 1，是格式事实不是缺陷")
                .extracting(ParsedDocument.DocElement::pageNo)
                .containsOnly(1);
    }

    @Test
    @DisplayName("步 0.6：空文档的 elements 是空列表而不是 null（Jackson 读老产物时也走这条）")
    void elementsAreNeverNull() {
        // 老 parse.json 没有 elements 字段 —— Jackson 给 null，紧凑构造器必须收成空列表。
        // 否则下游的 `doc.elements().stream()` 会在读到老产物时 NPE。
        ParsedDocument blank = new ParsedDocument("PDF", 1, 0, "", null, null, 0, 0, null, null);

        assertThat(blank.elements()).isEmpty();
        assertThat(blank.markdown()).isEmpty();
        assertThat(blank.headings()).isEmpty();
    }
}
