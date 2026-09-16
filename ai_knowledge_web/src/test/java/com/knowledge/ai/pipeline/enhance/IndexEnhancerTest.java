package com.knowledge.ai.pipeline.enhance;

import com.knowledge.ai.core.enums.BlockType;
import com.knowledge.ai.pipeline.Block;
import com.knowledge.ai.pipeline.ParsedDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-507` 文档级索引增强的取证 —— **组装逻辑，不启动 Spring、不联网**（计划 §3.4 假设 3）。
 *
 * <h3>为什么主判据必须在假 client 上跑</h3>
 * <p>`I-10` 要证明的是"一次调用产出三样、而不是三次调用"，那是**组装逻辑**：
 * 拼提示词、拆 JSON、装成 {@link Block}。这些在假 {@link ChatCaller} 上是**确定性**的，
 * 顺带还能断言"提示词里真的带了正文" —— 只断言调用次数的话，模型看到了什么仍然一无所知。
 *
 * <p>热路径上多一次网络往返就意味着"跑批结果取决于 relay 通不通"，本仓已有前车之鉴
 * （审计留存 cron 的测试覆盖项就是为此存在的）。所以这里连 Spring 容器都不起：
 * 纯对象、毫秒级。真实的模型调用在
 * {@link IndexEnhancerNetworkTest}，那边才有 {@code @SpringBootTest}。
 */
class IndexEnhancerTest {

    /** 假实现：记下提示词、返回预置文本。**不联网、毫秒级**。 */
    private static final class FakeCaller implements ChatCaller {
        private final List<String> prompts = new ArrayList<>();
        private final String reply;
        private final RuntimeException failure;

        FakeCaller(String reply) {
            this.reply = reply;
            this.failure = null;
        }

        FakeCaller(RuntimeException failure) {
            this.reply = null;
            this.failure = failure;
        }

        @Override
        public String call(String prompt) {
            prompts.add(prompt);
            if (failure != null) {
                throw failure;
            }
            return reply;
        }
    }

    private static final String GOOD_JSON = """
            {
              "summary": "本文规定了项目中台的计划制定、变更与沟通机制，适用于各项目组与工作组。",
              "labels": ["项目管理", "计划管理", "沟通机制", "风险控制"],
              "relations": [
                {"subject": "中台建设委员会办公室", "predicate": "上报", "object": "中台建设领导组"},
                {"subject": "PMO", "predicate": "汇总分析", "object": "风险和问题"}
              ]
            }
            """;

    // ==================================================================
    // I-10：一次调用，三样产出
    // ==================================================================

    @Test
    @DisplayName("I-10：一篇文档**恰好 1 次**调用，且一次就拿到摘要 + 标签 + 关系")
    void documentLevelIsCalledOncePerDoc() {
        FakeCaller caller = new FakeCaller(GOOD_JSON);
        IndexEnhancer enhancer = enhancerWith(caller, true);

        IndexEnhancer.Result result = enhancer.enhance(doc(), blocks());

        assertThat(caller.prompts)
                .as("`D-5` §1.6 / I-10：文档级调用是 1 次/篇。"
                        + "分三次调用是三重成本、三重失败点，而且三份「对文档的理解」还会互相不一致")
                .hasSize(1);
        assertThat(result.status()).isEqualTo(IndexEnhancer.Status.OK);
        assertThat(result.enhancement().summary()).isNotBlank();
        assertThat(result.enhancement().labels())
                .as("标签与摘要来自**同一次**调用的同一个 JSON —— 边际成本 ≈ 0")
                .hasSize(4);
        assertThat(result.enhancement().relations())
                .as("关系同理；它们的落点是 Neo4j（T-C00），本批只解析不落库")
                .hasSize(2);
    }

    @Test
    @DisplayName("提示词里**真的带了正文**，且要求了 JSON 格式 —— 只断言调用次数证明不了这个")
    void promptActuallyCarriesTheDocumentText() {
        FakeCaller caller = new FakeCaller(GOOD_JSON);
        enhancerWith(caller, true).enhance(doc(), blocks());

        String prompt = caller.prompts.getFirst();
        assertThat(prompt)
                .as("正文必须进提示词 —— 否则模型是在凭空总结")
                .contains("接口鉴权流程").contains("令牌有效期");
        assertThat(prompt)
                .as("标题也要带（它是结构信息的来源）")
                .contains("# 3. 接口设计");
        assertThat(prompt)
                .as("格式要求必须在提示词里，解析层的宽松剥离是兜底、不是理由")
                .contains("只输出 JSON").contains("\"summary\"");
    }

    @Test
    @DisplayName("版本号可追溯：「这份摘要是哪版提示词产出的」写进结果 detail，不塞进提示词")
    void promptVersionIsTraceableFromTheResult() {
        IndexEnhancer.Result result = enhancerWith(new FakeCaller(GOOD_JSON), true)
                .enhance(doc(), blocks());

        assertThat(result.detail())
                .as("版本号进 detail 而不是进提示词：模型不需要知道它（白花 token），"
                        + "而**事后抽查摘要质量**时需要一个锚点去定位是哪一版措辞产出的")
                .contains(EnhancePrompts.VERSION);
    }

    @Test
    @DisplayName("摘要块是**顶层**块（parentId = null）—— D-5 §1.6 明写")
    void summaryBlockIsTopLevel() {
        IndexEnhancer.Result result = enhancerWith(new FakeCaller(GOOD_JSON), true)
                .enhance(doc(), blocks());

        Block summary = result.blocks().getLast();
        assertThat(summary.blockType()).isEqualTo(BlockType.SUMMARY);
        assertThat(summary.isTopLevel())
                .as("`D-5` §1.6：「文档级摘要（blockType = SUMMARY，**parentId = null**）」。"
                        + "给它挂父块还会撞上 G4-6 的全局断言（有 parent_id 的块父块必须是 TABLE）")
                .isTrue();
        assertThat(summary.rawContent()).isEqualTo(result.enhancement().summary());
        assertThat(summary.orderNo())
                .as("序号接在既有块之后，不能重号 —— (doc_id, order_no) 是写入后回查的唯一依据")
                .isEqualTo(blocks().size() + 1);
        assertThat(result.blocks()).hasSize(blocks().size() + 1);
    }

    @Test
    @DisplayName("原有块一个不动、顺序不变 —— 增强只**追加**，不重排")
    void existingBlocksAreUntouched() {
        List<Block> before = blocks();
        IndexEnhancer.Result result = enhancerWith(new FakeCaller(GOOD_JSON), true)
                .enhance(doc(), before);

        assertThat(result.blocks().subList(0, before.size()))
                .as("分片结果是有序契约（父子块靠 orderNo 对上），增强不该碰它")
                .containsExactlyElementsOf(before);
    }

    // ==================================================================
    // 失败路径：不牵连文档，但要看得出是哪一种
    // ==================================================================

    @Test
    @DisplayName("模型炸了 → 文档照样成功，块集原样返回，状态是 MODEL_FAILED 而不是静默")
    void modelFailureDoesNotBreakTheDocument() {
        IndexEnhancer.Result result = enhancerWith(
                new FakeCaller(new IllegalStateException("连接超时")), true)
                .enhance(doc(), blocks());

        assertThat(result.status()).isEqualTo(IndexEnhancer.Status.MODEL_FAILED);
        assertThat(result.blocks())
                .as("正文已经切好、清洗过、能检索了 —— 因为摘要没生成就把整篇判失败，"
                        + "等于用一件增强功能的故障否掉一篇可用文档")
                .containsExactlyElementsOf(blocks());
        assertThat(result.detail())
                .as("G4-3：失败态要给具体原因，不许只说「失败」两字")
                .contains("连接超时");
    }

    @Test
    @DisplayName("输出不成 JSON → BAD_OUTPUT，且 detail 里带上原始输出的开头（便于定位）")
    void unparseableOutputIsReportedWithEvidence() {
        IndexEnhancer.Result result = enhancerWith(
                new FakeCaller("好的，我来分析一下这份文档：它的主要内容是……"), true)
                .enhance(doc(), blocks());

        assertThat(result.status()).isEqualTo(IndexEnhancer.Status.BAD_OUTPUT);
        assertThat(result.detail())
                .as("带原始输出前 200 字 —— 日志里只写「解析失败」的话，下一次还是不知道怎么修")
                .contains("好的，我来分析一下");
        assertThat(result.blocks()).containsExactlyElementsOf(blocks());
    }

    @Test
    @DisplayName("解析：模型包了 ``` 围栏 / 前面加了客套话，都要能读出内容（不判失败）")
    void tolerantParsingOfCommonModelSlack() {
        String fenced = """
                好的，以下是分析结果：
                ```json
                {"summary": "围栏包裹的摘要内容够长了。", "labels": [], "relations": []}
                ```
                """;

        IndexEnhancer.Result result = enhancerWith(new FakeCaller(fenced), true)
                .enhance(doc(), blocks());

        assertThat(result.status())
                .as("提示词要求了「只输出 JSON」，但要求不等于保证。"
                        + "为这种无关紧要的包装判失败，是把提示词工程的问题升级成功能故障")
                .isEqualTo(IndexEnhancer.Status.OK);
        assertThat(result.enhancement().summary()).contains("围栏包裹的摘要");
    }

    @Test
    @DisplayName("解析：summary 为空 → 判 BAD_OUTPUT（另两样给空数组是可接受的）")
    void blankSummaryIsBadOutput() {
        String noSummary = """
                {"summary": "  ", "labels": ["甲", "乙"], "relations": []}
                """;
        assertThat(enhancerWith(new FakeCaller(noSummary), true).enhance(doc(), blocks()).status())
                .as("标签再多也替代不了摘要 —— 摘要块是这次调用唯一的落点，它空了整次调用就白费")
                .isEqualTo(IndexEnhancer.Status.BAD_OUTPUT);
    }

    @Test
    @DisplayName("解析：三要素缺一的关系被丢掉（缺了进图库就是一条断边）")
    void incompleteRelationsAreDropped() {
        String partial = """
                {"summary": "摘要内容足够长以通过校验。",
                 "labels": [],
                 "relations": [
                   {"subject": "甲", "predicate": "属于", "object": "乙"},
                   {"subject": "丙", "predicate": "", "object": "丁"},
                   {"subject": "", "predicate": "属于", "object": "戊"}
                 ]}
                """;
        IndexEnhancer.Result result = enhancerWith(new FakeCaller(partial), true)
                .enhance(doc(), blocks());

        assertThat(result.enhancement().relations())
                .as("只有三要素齐全的那一条留下").hasSize(1);
    }

    @Test
    @DisplayName("关掉开关 → DISABLED，不调用模型、块集原样（测试期与运维临时关都走这条）")
    void disabledSkipsTheModelEntirely() {
        FakeCaller caller = new FakeCaller(GOOD_JSON);
        IndexEnhancer.Result result = enhancerWith(caller, false).enhance(doc(), blocks());

        assertThat(caller.prompts)
                .as("关掉就该**一次都不打** —— 打到一半再丢弃，那开关就只是装饰")
                .isEmpty();
        assertThat(result.status()).isEqualTo(IndexEnhancer.Status.DISABLED);
    }

    @Test
    @DisplayName("空正文 → EMPTY_INPUT，不调用模型（没有可摘的内容不是失败）")
    void emptyDocumentIsNotAFailure() {
        FakeCaller caller = new FakeCaller(GOOD_JSON);
        ParsedDocument empty = new ParsedDocument("PDF", 1, 0, "", List.of(), List.of(),
                0, 0, List.of(), List.of());

        IndexEnhancer.Result result = enhancerWith(caller, true).enhance(empty, List.of());

        assertThat(caller.prompts).isEmpty();
        assertThat(result.status()).isEqualTo(IndexEnhancer.Status.EMPTY_INPUT);
    }

    // ==================================================================
    // 输入预算
    // ==================================================================

    @Test
    @DisplayName("超预算的正文被截断，且**提示词里明说被截断了**")
    void truncationIsDisclosedToTheModel() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            huge.append("这是一段很长的正文内容，用来把三万 token 的预算撑满。");
        }
        List<ParsedDocument.DocElement> elements = new ArrayList<>();
        elements.add(ParsedDocument.DocElement.paragraph(huge.toString(), 1));
        ParsedDocument big = new ParsedDocument("PDF", 1, huge.length(), "", List.of(), List.of(),
                1, 0, List.of(), elements);

        FakeCaller caller = new FakeCaller(GOOD_JSON);
        enhancerWith(caller, true).enhance(big, blocks());

        assertThat(caller.prompts)
                .as("★ 这里曾经红过，而且抓到的是**真缺陷**：当时的实现在 `used + cost > budget` 时"
                        + "直接 break —— 而这个构造里**第一个元素本身就超预算**，"
                        + "于是文本为空 → 判 EMPTY_INPUT → 替身模型一次都没被调用 → **永远没有摘要**。"
                        + "而「一整篇就是一个超长段落」恰恰是最需要摘要的那种文档。"
                        + "修法是：一个字都没装下时必须砍一段进来")
                .isNotEmpty();
        String prompt = caller.prompts.getFirst();
        assertThat(prompt)
                .as("截断了必须告诉模型 —— 不说的话它会照常写出一份「看起来读完了全文」的摘要，"
                        + "而它只看到了前半篇。这是「少了一点东西而没人发现」的典型")
                .contains("被截断");
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /**
     * 开关作为**构造器参数**传进来（不是反射塞字段）。
     *
     * <p>这是 {@link IndexEnhancer} 那个构造器签名存在的理由：若开关是
     * {@code @Value} 字段，"关掉之后一次都不打模型"这条分支就只能靠反射测 ——
     * 而那恰恰是最该走正常构造路径的一条。
     */
    private static IndexEnhancer enhancerWith(ChatCaller caller, boolean enabled) {
        return new IndexEnhancer(caller, enabled);
    }

    private static ParsedDocument doc() {
        List<ParsedDocument.DocElement> elements = List.of(
                ParsedDocument.DocElement.heading(1, "3. 接口设计", 1),
                ParsedDocument.DocElement.paragraph("接口鉴权流程采用令牌方式，令牌有效期 2 小时。", 1),
                ParsedDocument.DocElement.paragraph("过期后需用刷新令牌重新获取。", 2));
        return new ParsedDocument("PDF", 2, 60, "# 3. 接口设计\n\n接口鉴权流程…\n",
                List.of(new ParsedDocument.Heading(1, "3. 接口设计", 1)), List.of(),
                2, 0, List.of(), elements);
    }

    private static List<Block> blocks() {
        return List.of(
                new Block("3. 接口设计", BlockType.HEADING, "3. 接口设计", 1, 1, null),
                new Block("接口鉴权流程采用令牌方式，令牌有效期 2 小时。", BlockType.PARAGRAPH,
                        "3. 接口设计", 2, 1, null));
    }
}
