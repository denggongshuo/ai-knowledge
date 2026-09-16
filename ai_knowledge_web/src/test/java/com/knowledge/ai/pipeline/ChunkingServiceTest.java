package com.knowledge.ai.pipeline;

import com.knowledge.ai.core.enums.BlockType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * `T-503` 两级分片取证（`G4-5` / `G4-6` / `G4-7` 的产出侧半边）。
 *
 * <h3>这些判据防的是什么</h3>
 * <p>分片是**检索质量的第一变量**，而它出错时不会报错 —— 只会让检索结果悄悄变差。
 * 所以每一条都钉在"能解释为什么"的具体形状上：叶子节点是不是真的合并了、
 * 父块是不是真的不进检索、子块的 parentId 是不是真的指向 TABLE。
 */
class ChunkingServiceTest {

    private final ChunkingService chunking = new ChunkingService();

    /** 默认参数够小，便于造"超长"样本；真实的 512 太大，测起来要几千字。 */
    private static ChunkingOptions options(int chunkSize, int overlap, boolean semantic) {
        return new ChunkingOptions(chunkSize, overlap, semantic);
    }

    private static ParsedDocument doc(ParsedDocument.DocElement... elements) {
        return new ParsedDocument("PDF", 1, 100, "", List.of(), List.of(),
                0, 0, List.of(), List.of(elements));
    }

    // ==================================================================
    // 第一级 · 结构边界
    // ==================================================================

    @Test
    @DisplayName("T-503：叶子节点即一个候选块 —— 一节里的多个短段落合并成一块，而不是各出一块")
    void paragraphsInOneSectionMergeIntoASingleBlock() {
        List<Block> blocks = chunking.chunk(doc(
                ParsedDocument.DocElement.heading(1, "第一章 总则", 1),
                ParsedDocument.DocElement.paragraph("第一条 本规范适用于全部平台。", 1),
                ParsedDocument.DocElement.paragraph("第二条 术语以附录为准。", 1),
                ParsedDocument.DocElement.paragraph("第三条 解释权归架构组。", 1)),
                options(512, 64, false));

        assertThat(blocks).extracting(Block::blockType)
                .as("标题是原子块，三个短段落**合并成一个**正文块（合计远不到 chunkSize）")
                .containsExactly(BlockType.HEADING, BlockType.PARAGRAPH);
        assertThat(blocks.get(1).rawContent())
                .as("合并时保留段落边界（双换行），块内仍读得出是三条")
                .contains("第一条").contains("第二条").contains("第三条");
    }

    @Test
    @DisplayName("T-503：遇标题即断 —— 下一节的正文不会并进上一节的块")
    void headingClosesThePreviousSection() {
        List<Block> blocks = chunking.chunk(doc(
                ParsedDocument.DocElement.heading(1, "第一章", 1),
                ParsedDocument.DocElement.paragraph("甲的正文", 1),
                ParsedDocument.DocElement.heading(1, "第二章", 2),
                ParsedDocument.DocElement.paragraph("乙的正文", 2)),
                options(512, 64, false));

        assertThat(blocks).extracting(Block::rawContent)
                .containsExactly("第一章", "甲的正文", "第二章", "乙的正文");
    }

    @Test
    @DisplayName("T-503：orderNo 从 1 起连续，且等于块的文档顺序（回表与详情页靠它还原阅读顺序）")
    void orderNoIsSequentialFromOne() {
        List<Block> blocks = chunking.chunk(doc(
                ParsedDocument.DocElement.heading(1, "第一章", 1),
                ParsedDocument.DocElement.paragraph("正文", 1),
                ParsedDocument.DocElement.paragraph("续写", 2)),
                options(512, 64, false));

        assertThat(blocks).extracting(Block::orderNo).containsExactly(1, 2);
    }

    // ==================================================================
    // 标题路径（G4-9 的产出侧前提）
    // ==================================================================

    @Test
    @DisplayName("T-503：titlePath 由标题栈算出 —— 二级标题下的块带完整路径，同级标题会弹栈")
    void titlePathFollowsTheTocStack() {
        List<Block> blocks = chunking.chunk(doc(
                ParsedDocument.DocElement.heading(1, "3. 接口设计", 1),
                ParsedDocument.DocElement.heading(2, "3.1 鉴权流程", 1),
                ParsedDocument.DocElement.paragraph("走 JWT。", 1),
                ParsedDocument.DocElement.heading(2, "3.2 错误码", 2),
                ParsedDocument.DocElement.paragraph("10020 是无权限。", 2)),
                options(512, 64, false));

        assertThat(blocks.get(2).titlePath())
                .as("3.1 下的正文块，路径是完整的两级")
                .isEqualTo("3. 接口设计 > 3.1 鉴权流程");
        assertThat(blocks.get(4).titlePath())
                .as("同级标题 3.2 出现时，3.1 必须被弹出 —— 否则路径会串成 3.1 > 3.2")
                .isEqualTo("3. 接口设计 > 3.2 错误码")
                .doesNotContain("3.1");
    }

    @Test
    @DisplayName("T-503：标题之前的块没有 titlePath（空串），投影时退化为原文")
    void blocksBeforeAnyHeadingHaveNoTitlePath() {
        List<Block> blocks = chunking.chunk(doc(
                ParsedDocument.DocElement.paragraph("封面段：某公司内部规范 v2。", 1),
                ParsedDocument.DocElement.heading(1, "第一章", 2)),
                options(512, 64, false));

        assertThat(blocks.getFirst().titlePath()).isEmpty();
        assertThat(ChunkViews.project(blocks.getFirst(), com.knowledge.ai.core.enums.ChunkView.WITH_TITLE_PATH))
                .as("没有标题路径时投影退化为原文，不留下孤立的换行")
                .isEqualTo(blocks.getFirst().rawContent());
    }

    // ==================================================================
    // 表格 · G4-5 / G4-6 / G4-7
    // ==================================================================

    private static ParsedDocument docWithTable(ParsedDocument.ParsedTable table) {
        return new ParsedDocument("PDF", 1, 100, "", List.of(), List.of(table),
                0, 0, List.of(), List.of(ParsedDocument.DocElement.table(0, 5)));
    }

    @Test
    @DisplayName("G4-5：一张表 = 1 个父块 + N 个子块；父块 rawContent 是 Markdown，子块是行式文本")
    void oneTableYieldsOneParentAndNChildren() {
        List<Block> blocks = chunking.chunk(docWithTable(new ParsedDocument.ParsedTable("费率表", 5,
                List.of(List.of("型号", "产品名称", "单价"),
                        List.of("A-100", "智能网关", "￥1,200"),
                        List.of("A-1000", "汇聚交换机", "￥38,000")))),
                options(512, 64, false));

        assertThat(blocks).hasSize(3);
        Block parent = blocks.getFirst();
        assertThat(parent.blockType()).isEqualTo(BlockType.TABLE);
        assertThat(parent.isTopLevel()).as("父块的 parentOrderNo 必须是 null（D-5 §1.2）").isTrue();
        assertThat(parent.rawContent())
                .as("父块是 Markdown 管道表 —— 给大模型读的那一份")
                .contains("| 型号 |").contains("| A-100 |");

        for (Block child : blocks.subList(1, blocks.size())) {
            assertThat(child.blockType()).isEqualTo(BlockType.TABLE);
            assertThat(child.parentOrderNo())
                    .as("G4-6 / I-3：子块的 parentId 必指向 TABLE 父块的 orderNo，禁止用 0 当哨兵")
                    .isEqualTo(parent.orderNo())
                    .isNotEqualTo(0);
            assertThat(child.rawContent())
                    .as("G4-7：子块是「列名 + 值」的自然语言，**不是** Markdown 竖线串")
                    .doesNotContain("|");
        }
        assertThat(blocks.get(1).rawContent()).isEqualTo("型号 A-100，产品名称 智能网关，单价 ￥1,200");
    }

    @Test
    @DisplayName("G4-5：父块不参与检索 —— 视图与投影两条路都指向同一个结论")
    void tableParentIsExcludedFromRetrieval() {
        List<Block> blocks = chunking.chunk(docWithTable(new ParsedDocument.ParsedTable("", 1,
                List.of(List.of("k", "v"), List.of("a", "b")))),
                options(512, 64, false));

        Block parent = blocks.getFirst();
        assertThat(parent.views())
                .as("父块只声明 TABLE_MARKDOWN，它不进向量检索池（D-5 §1.4 —— I-4 的落点）")
                .containsExactly(com.knowledge.ai.core.enums.ChunkView.TABLE_MARKDOWN);
        assertThat(ChunkViews.defaultVectorText(parent)).isNull();
        assertThat(ChunkViews.defaultVectorText(blocks.get(1)))
                .as("子块才进检索池")
                .isEqualTo("k a，v b");
    }

    @Test
    @DisplayName("G4-5：1×1 空表不成块 —— 与 ParsedDocument#tableTotal() 同一口径（版式分隔线不是表）")
    void layoutArtifactTableProducesNoBlock() {
        List<Block> blocks = chunking.chunk(docWithTable(
                        new ParsedDocument.ParsedTable("", 2, List.of(List.of("")))),
                options(512, 64, false));

        assertThat(blocks)
                .as("若在这里成块，就会在 sys_chunk 里凭空多出一个空表格 —— "
                        + "而 tableTotal 又不计它，两处口径当场打架")
                .isEmpty();
    }

    @Test
    @DisplayName("G4-7：只有表头的表也产出一条子块 —— 不让「表存在却完全检索不到」静默发生")
    void headerOnlyTableStillYieldsOneChild() {
        List<Block> blocks = chunking.chunk(docWithTable(
                        new ParsedDocument.ParsedTable("", 2, List.of(List.of("型号", "单价")))),
                options(512, 64, false));

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(1).rawContent())
                .as("只有一行时没有「数据行」，但那一行就是这张表的全部内容。"
                        + "直接丢弃会让表格检索不到 —— 正是本项目最怕的静默形态")
                .isEqualTo("型号，单价");
    }

    @Test
    @DisplayName("T-504：子块逐行产出，每行一条独立可检索的自然语言（不是整表塞一条）")
    void eachDataRowBecomesItsOwnChild() {
        List<Block> blocks = chunking.chunk(docWithTable(new ParsedDocument.ParsedTable("", 1,
                List.of(List.of("型号", "单价"),
                        List.of("A-100", "￥1,200"),
                        List.of("A-1000", "￥38,000"),
                        List.of("B-5", "￥99")))),
                options(512, 64, false));

        assertThat(blocks).extracting(Block::rawContent).containsExactly(
                blocks.getFirst().rawContent(),
                "型号 A-100，单价 ￥1,200",
                "型号 A-1000，单价 ￥38,000",
                "型号 B-5，单价 ￥99");
    }

    // ==================================================================
    // 第二级 · 超长兜底
    // ==================================================================

    @Test
    @DisplayName("T-503：结构单元超 chunkSize 时按递归分隔符切开，每片不超窗口")
    void longSectionIsSplitByRecursiveSeparators() {
        String sentence = "这是一句用于测试分片的句子，它需要足够长才能触发兜底。";
        String longText = sentence.repeat(8);   // 远超 chunkSize = 60

        List<Block> blocks = chunking.chunk(doc(
                ParsedDocument.DocElement.heading(1, "第一章", 1),
                ParsedDocument.DocElement.paragraph(longText, 1)),
                options(60, 0, false));

        assertThat(blocks).hasSizeGreaterThan(1);
        assertThat(blocks.subList(1, blocks.size()))
                .allSatisfy(b -> assertThat(ChunkingOptions.estimateTokens(b.rawContent()))
                        .as("每一片都必须在窗口内，否则 chunkSize 形同虚设")
                        .isLessThanOrEqualTo(60));
        assertThat(String.join("", blocks.subList(1, blocks.size()).stream()
                .map(Block::rawContent).toList()))
                .as("切分不能丢内容（overlap = 0 时应当可以逐字拼回）")
                .isEqualTo(longText);
    }

    @Test
    @DisplayName("T-503：overlap 只让后一片复述前一片的尾部，且叠加后仍不超窗口")
    void overlapRepeatsTheTailOfThePreviousPiece() {
        String sentence = "这是一句用于测试重叠的句子，它需要足够长才能触发兜底。";
        String longText = sentence.repeat(8);

        List<Block> withOverlap = chunking.chunk(doc(
                ParsedDocument.DocElement.paragraph(longText, 1)),
                options(60, 20, false));

        assertThat(withOverlap).hasSizeGreaterThan(1);
        assertThat(withOverlap)
                .allSatisfy(b -> assertThat(ChunkingOptions.estimateTokens(b.rawContent()))
                        .as("合并目标取了 chunkSize - overlap，所以叠加重叠后仍不超过窗口 —— "
                                + "否则「窗口 512」的库会切出 576 的块，与配置对不上")
                        .isLessThanOrEqualTo(60));
        // 第二片的开头应当能在第一片的结尾找到（复述关系）
        String firstTail = withOverlap.getFirst().rawContent();
        String secondHead = withOverlap.get(1).rawContent().substring(0,
                Math.min(10, withOverlap.get(1).rawContent().length()));
        assertThat(firstTail).contains(secondHead);
    }

    @Test
    @DisplayName("T-503：没有分隔符的长串走硬切兜底 —— 宁可切坏，也不能产出超长块")
    void textWithoutSeparatorsIsHardSplit() {
        String noPunctuation = "A".repeat(4000);   // 无标点无空格：任何分隔符都切不开

        List<Block> blocks = chunking.chunk(doc(
                ParsedDocument.DocElement.paragraph(noPunctuation, 1)),
                options(100, 0, false));

        assertThat(blocks).hasSizeGreaterThan(1);
        assertThat(blocks).allSatisfy(b -> assertThat(ChunkingOptions.estimateTokens(b.rawContent()))
                .isLessThanOrEqualTo(100));
        assertThat(String.join("", blocks.stream().map(Block::rawContent).toList()))
                .as("硬切也不能丢内容")
                .isEqualTo(noPunctuation);
    }

    // ==================================================================
    // 明确不做 / 不静默降级
    // ==================================================================

    @Test
    @DisplayName("T-503：配置了语义切分就显式失败 —— 不静默用递归分隔符替代")
    void semanticSplitFailsLoudlyInsteadOfDegradingSilently() {
        assertThatThrownBy(() -> chunking.chunk(doc(
                ParsedDocument.DocElement.paragraph("正文", 1)),
                options(512, 64, true)))
                .as("《02》验收口径第一条是「静默降级视为缺陷」。"
                        + "假装开了语义切分，用户看到的是「已开启」，实际得到的是另一种切法 —— "
                        + "那比直接说「没实现」糟得多")
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("语义切分")
                .hasMessageContaining("关闭");
    }

    @Test
    @DisplayName("T-512 未落地：IMAGE 元素不产出块，但也不假装处理过")
    void imageElementsProduceNoBlock() {
        List<Block> blocks = chunking.chunk(doc(
                ParsedDocument.DocElement.heading(1, "第一章", 1),
                ParsedDocument.DocElement.paragraph("正文", 1),
                ParsedDocument.DocElement.image(2)),
                options(512, 64, false));

        assertThat(blocks).extracting(Block::blockType)
                .as("图片处理是 T-512（P1，依赖 T-907 的 VL 模型）—— 与 ParsedDocument#pendingBlockTypes() "
                        + "声明的「尚未产出」一致。数量仍由 imageCount 统计，不静默丢")
                .containsExactly(BlockType.HEADING, BlockType.PARAGRAPH);
    }

    @Test
    @DisplayName("T-503：空文档产出空块集，不抛异常（没有内容不是错误）")
    void emptyDocumentYieldsNoBlocks() {
        // chars = 0 才是真正的空文档。chars > 0 而无元素是**另一种**情形（产物版本过旧），
        // 由下一条用例负责 —— 两者混在一起会让"没内容"与"产物读不出来"分不清。
        ParsedDocument blank = new ParsedDocument("PDF", 1, 0, "", List.of(), List.of(),
                0, 0, List.of(), List.of());
        assertThat(chunking.chunk(blank, options(512, 64, false))).isEmpty();
    }

    @Test
    @DisplayName("步 0.6 判据17：老产物（有内容却没 elements）显式失败 —— 不静默产出 0 个块")
    void staleArtifactFailsLoudly() {
        // elements 是 2026-09-16 新增的字段。在它之前落库的 parse.json 没有这一项，
        // Jackson 读出来是 null —— 模拟那个形状。
        ParsedDocument legacy = new ParsedDocument("PDF", 8, 2149, "很长的正文……",
                List.of(), List.of(), 40, 0, List.of(), null);

        assertThatThrownBy(() -> chunking.chunk(legacy, options(512, 64, false)))
                .as("静默产出 0 个块，会让文档显示「解析完成、0 个分片」—— "
                        + "用户看不出是产物过旧还是文档本来就没内容。那是本项目最怕的形态")
                .isInstanceOf(ParseException.class)
                .hasMessageContaining("重新解析");
    }

    // ==================================================================
    // 契约：块类型取值域
    // ==================================================================

    @Test
    @DisplayName("T-503：本批可能产出的块类型只有 3 种 —— 超出即契约漂移")
    void onlyThreeBlockTypesAreProducibleToday() {
        List<Block> blocks = new ArrayList<>(chunking.chunk(docWithTable(
                        new ParsedDocument.ParsedTable("", 1,
                                List.of(List.of("k"), List.of("v")))),
                options(512, 64, false)));
        blocks.addAll(chunking.chunk(doc(
                ParsedDocument.DocElement.heading(1, "标题", 1),
                ParsedDocument.DocElement.paragraph("正文", 1)),
                options(512, 64, false)));

        assertThat(blocks).extracting(Block::blockType).containsOnly(
                BlockType.TABLE, BlockType.HEADING, BlockType.PARAGRAPH);

        assertThat(ParsedDocument.pendingBlockTypes())
                .as("SUMMARY / HYPOTHESIS_QA 属 T-507（S4-C2），IMAGE 属 T-512（S6）—— "
                        + "本批产出它们的任何一个，都说明有东西越界了")
                .containsExactly(BlockType.SUMMARY, BlockType.HYPOTHESIS_QA, BlockType.IMAGE);
    }
}
