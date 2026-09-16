package com.knowledge.ai.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `S4-M05-C2` 步 0.5：`PdfDocumentParser` 的**容器递归**取证。
 *
 * <h3>这条用例防的是哪个缺陷</h3>
 * <p>此前 `fromJson` **只遍历顶层 `kids[]`**，不认识的一律 `default ->` 丢掉。
 * 而 opendataloader 有一类**容器**：`type = "text block"` —— 它自己没有 `content`，
 * 真正的段落与列表在它的 `kids[]` 里。于是整棵子树**静默消失**。
 *
 * <p>实测（20 页样本）：`text block × 17`，其子树含 32 个后代元素 / 200 字符，
 * 占全部可提取文本的 **8.1%**。而既有测试全绿 —— 因为 `table` 都在顶层
 * （`table_total = 7` 照旧成立），`chars` 又不计被丢的部分。**这就是"少了一点东西而没人发现"。**
 *
 * <h3>为什么断言要写成"后代出现"而不是"数量对得上"</h3>
 * <p>"数量"是间接证据：改了别的地方也会让数量变。这条要钉的是**行为语义** ——
 * 容器里的内容必须**出现在产物里**（`elements` 与 `markdown` 双查），
 * 且容器自身**不产出元素**（它没有内容，不是一块）。
 *
 * <p>⚠️ **本用例必须能抓住旧实现**：把 `walk` 的 `default` 分支改回"什么都不做"，
 * 它必须转红（已反证，见 `2026-09-16-S4-M05-C2.md` §8）。
 */
class PdfDocumentParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 元素树：一个 `text block` 容器装着段落与列表，外加一个**嵌套容器**。
     *
     * <p>嵌套那一层是刻意的 —— 只处理一层的实现（给 `text block` 加 case 但不递归）
     * 会在这里露馅。
     */
    private static final String JSON = """
            {
              "title": "领导班子",
              "number of pages": 3,
              "kids": [
                {"type": "heading", "heading level": "1", "page number": 1, "content": "第一章 组织架构"},
                {"type": "text block", "id": 139, "page number": 3,
                 "bounding box": [193.66, 387.435, 659.3, 447.14],
                 "kids": [
                   {"type": "paragraph", "id": 7, "page number": 3, "content": "：孔发龙"},
                   {"type": "list", "id": 9, "page number": 3, "numbering style": "unordered",
                    "list items": [
                      {"type": "list item", "content": "? 组长"},
                      {"type": "list item", "content": "? 副组长"}
                    ]},
                   {"type": "text block", "id": 140, "page number": 3,
                    "kids": [
                      {"type": "paragraph", "id": 8, "page number": 3,
                       "content": "：邹强、刘桂花、徐平岸、唐弢、王祥生、罗晓明、周晓兰"}
                    ]}
                 ]}
              ]
            }
            """;

    @Test
    @DisplayName("步 0.5：容器的后代内容必须出现 —— elements 与 markdown 双查")
    void containerDescendantsAreNotDropped() throws Exception {
        ParsedDocument doc = PdfDocumentParser.fromJson(MAPPER.readTree(JSON));

        // 顶层的内容照旧
        assertThat(doc.elements()).extracting(ParsedDocument.DocElement::kind)
                .as("顶层标题仍是第一个元素")
                .first().isEqualTo(ParsedDocument.DocElement.Kind.HEADING);

        // ★ 容器里的内容 —— 旧实现在这几条上全红
        assertThat(doc.elements()).extracting(ParsedDocument.DocElement::text)
                .as("容器里的段落「：孔发龙」必须在 elements 里。"
                        + "旧实现只遍历顶层，整棵子树被 default 丢掉 —— 这是本条要防的")
                .contains("：孔发龙");

        assertThat(doc.elements()).extracting(ParsedDocument.DocElement::text)
                .as("容器的直接子列表也要被读到（list 走 joinListItems，不递归）")
                .anySatisfy(t -> assertThat(t).contains("组长").contains("副组长"));

        assertThat(doc.elements()).extracting(ParsedDocument.DocElement::text)
                .as("**嵌套容器**里的段落 —— 只处理一层的实现会在这里露馅")
                .anySatisfy(t -> assertThat(t).contains("邹强").contains("周晓兰"));

        assertThat(doc.markdown())
                .as("markdown 与 elements 是同一条记录的两面，不能只有一面补上")
                .contains("：孔发龙").contains("邹强");

        assertThat(doc.paragraphs())
                .as("容器里的两个段落 + 一个列表 = 3 条（标题不计入 paragraphs）")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("步 0.5：容器自身**不产出元素** —— 它没有内容，不是一块")
    void containerItselfIsNotAnElement() throws Exception {
        ParsedDocument doc = PdfDocumentParser.fromJson(MAPPER.readTree(JSON));

        assertThat(doc.elements())
                .as("`text block` 是容器不是内容。若把它也当成一个元素，"
                        + "会产出一条空块 —— 空块进向量就是纯噪声")
                .hasSize(4);   // 标题 1 + 段落 2 + 列表 1
    }

    @Test
    @DisplayName("步 0.5：容器的后代继承页号（子元素自己带页号时以子为准）")
    void descendantsInheritPageNumber() throws Exception {
        // 容器标第 3 页，子元素**不带** page number —— 应当继承 3，而不是掉成 0
        String json = """
                {
                  "title": "", "number of pages": 3,
                  "kids": [
                    {"type": "text block", "page number": 3,
                     "kids": [{"type": "paragraph", "content": "没有页号的段落"}]}
                  ]
                }
                """;
        ParsedDocument doc = PdfDocumentParser.fromJson(MAPPER.readTree(json));

        assertThat(doc.elements()).singleElement()
                .as("页号掉成 0 会让引用卡片显示「第 0 页」，也会让逐页问题定位错页")
                .extracting(ParsedDocument.DocElement::pageNo)
                .isEqualTo(3);
    }

    @Test
    @DisplayName("步 0.5：`caption` 仍然不进正文（它是「关于某图的说明」）")
    void captionStillStaysOut() throws Exception {
        String json = """
                {
                  "title": "", "number of pages": 1,
                  "kids": [
                    {"type": "caption", "page number": 1, "content": "图 1 系统架构"},
                    {"type": "paragraph", "page number": 1, "content": "正文一段"}
                  ]
                }
                """;
        ParsedDocument doc = PdfDocumentParser.fromJson(MAPPER.readTree(json));

        assertThat(doc.elements()).extracting(ParsedDocument.DocElement::text)
                .as("递归不是「什么都收」：题注依旧排除在正文之外，"
                        + "否则检索会命中「图 1」这种没有检索价值的串")
                .containsExactly("正文一段");
    }
}
