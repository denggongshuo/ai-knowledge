package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.BlockType;
import com.knowledge.ai.pipeline.Block;
import com.knowledge.ai.pipeline.ParsedDocument;
import com.knowledge.ai.pipeline.enhance.ChatCaller;
import com.knowledge.ai.pipeline.enhance.IndexEnhancer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-507` 的**落库侧**取证（判据 10：`sys_chunk` 里存在 `SUMMARY` 分片）。
 *
 * <h3>为什么要单独一个类，而不是塞进 {@link DocParseFlowTest}</h3>
 * <p>{@code src/test/resources/application.properties} 把 {@code m05.enhance.enabled}
 * 关掉了（否则那二十来次上传每次都要真打一次模型）。也正因如此，那边**永远产不出**
 * `SUMMARY` 行 —— 判据 10 需要的是一个开着增强、但**模型是假的**环境：
 * 确定、免费、且能走到真实的落库路径。
 *
 * <h3>用 {@code @Primary} 换掉 {@link ChatCaller}，而不是 mock</h3>
 * <p>本仓的 pom **没有 Mockito**，也不需要为这一个替身引进来 ——
 * {@link ChatCaller} 是函数式接口，一个 lambda 就是全部实现。
 * 它替换的是**模型**这一层，管道其余部分（清洗 → 分片 → 增强 → 落库）全是真的，
 * 所以判据 10 的 SQL 断言打在真实产物上。
 */
@SpringBootTest
@TestPropertySource(properties = "m05.enhance.enabled=true")
class IndexEnhanceFlowTest {

    /** 覆盖生产用的 {@code RegistryChatCaller} —— 只换模型，不换管道。 */
    @TestConfiguration
    static class StubModel {

        /** 预置的模型回复（形状与 {@code EnhancePrompts} 要求的 JSON 一致）。 */
        static final String REPLY = """
                {"summary": "本探针文档说明接口鉴权的令牌方式与错误码约定。",
                 "labels": ["接口设计", "鉴权"],
                 "relations": [{"subject": "刷新令牌", "predicate": "换取", "object": "访问令牌"}],
                 "chapters": [{"title": "3. 接口设计", "summary": "本章讲鉴权与令牌有效期。"}]}
                """;

        /** 分片级 HyQ 那一跳的回复。 */
        static final String HYQ_REPLY = """
                {"questions": ["令牌有效期是多久？", "鉴权用的是什么方式？"]}
                """;

        /** 与 {@code IndexEnhancerTest} 同一个标记：用来分辨两类提示词。 */
        static final String HYQ_MARKER = "推测**用户可能怎样提问**";

        /**
         * ⚠️ 必须**按提示词分辨**该回哪一种：`T-507` 落地后一次 enhance 有
         * 文档级 1 次 + 分片级 n 次两类调用。对所有提示词回同一个字符串的话，
         * HyQ 那一跳拿到的是一份没有 {@code questions} 的 JSON，
         * 于是"HyQ 解析失败"与"模型答不出问题"分不开，用例也就断言不到真行为。
         */
        @Bean
        @Primary
        ChatCaller stubChatCaller() {
            return prompt -> prompt.contains(HYQ_MARKER) ? HYQ_REPLY : REPLY;
        }
    }

    @Autowired
    private DocRecordWriter recordWriter;

    @Autowired
    private IndexEnhancer indexEnhancer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 探针文档 id（本类直接建行，不走上传 —— 本类考的是落库，不是上传）。 */
    private final List<Long> probeDocIds = new ArrayList<>();

    @AfterEach
    void cleanProbeData() {
        for (Long docId : probeDocIds) {
            jdbcTemplate.update("delete from sys_chunk where doc_id = ?", docId);
            jdbcTemplate.update("delete from sys_document where id = ?", docId);
        }
        probeDocIds.clear();
    }

    @Test
    @DisplayName("判据10（G4-9）：SUMMARY 块真的落进 sys_chunk，parent_id 为 NULL，且计入 chunk_count")
    void summaryBlockIsProducedAndStored() {
        long docId = insertProbeDoc();
        List<Block> blocks = List.of(
                new Block("3. 接口设计", BlockType.HEADING, "3. 接口设计", 1, 1, null, null, null),
                new Block("接口鉴权采用令牌方式，令牌有效期 2 小时。", BlockType.PARAGRAPH,
                        "3. 接口设计", 2, 1, null, null, null));

        IndexEnhancer.Result enhanced = indexEnhancer.enhance(doc(), blocks);
        assertThat(enhanced.status())
                .as("替身模型必须让增强走通，否则下面的 SQL 断言是空断言。detail=%s", enhanced.detail())
                .isEqualTo(IndexEnhancer.Status.OK);

        int written = recordWriter.replaceChunks(docId, probeKbIdOf(docId), enhanced.blocks());

        // 限定 `title_path is null` 取**文档级**那一条：章节级摘要带标题路径，
// 两者现在都是 SUMMARY 类型（T-O09 步 6 之后）
        Map<String, Object> summary = jdbcTemplate.queryForMap(
                "select chunk_type, parent_id, raw_content from sys_chunk "
                        + "where doc_id = ? and chunk_type = 'SUMMARY' and title_path is null", docId);
        assertThat(summary.get("chunk_type")).isEqualTo("SUMMARY");
        assertThat(summary.get("parent_id"))
                .as("`D-5` §1.6 明写摘要块 `parentId = null`。"
                        + "挂父块还会撞上 G4-6 的全局断言（有 parent_id 的块父块必须是 TABLE）")
                .isNull();
        assertThat((String) summary.get("raw_content")).contains("令牌方式");

        assertThat(count("select count(*) from sys_chunk where doc_id = ? and chunk_type = 'SUMMARY'", docId))
                .as("`G4-9` 的判据原文：「存在 SUMMARY 类型分片」。⚠️ 期望 2 而不是 1 —— "
                        + "`T-O09` 步 6 的章节级摘要**复用同一次调用**产出，"
                        + "所以同一篇里有一条文档级 + 一条章节级")
                .isEqualTo(2);

        assertThat(count("select count(*) from sys_chunk where doc_id = ? and title_path = ?",
                docId, "3. 接口设计"))
                .as("`G4-9` 的另一半：「分片内容含标题路径」。⚠️ 本批之前**没人证过这一列** —— "
                        + "`ChunkingServiceTest` 只断言了 Block 上的 titlePath 算得对，"
                        + "而「算对了但没写进库」是两条独立的失败路径（写入侧是 blankToNull）。"
                        + "期望 4 = 标题块自身 + 它下面的正文块 + **章节摘要**（继承章节标题）+ "
                        + "**HyQ 块**（继承源块的标题路径，好让「这条问句属于哪一节」跟着它进检索）")
                .isEqualTo(4);
        assertThat(count("select count(*) from sys_chunk where doc_id = ? and chunk_type = 'SUMMARY' "
                + "and title_path is null", docId))
                .as("摘要块是**文档级**的，不属于任何一节 —— 空标题路径落库为 NULL 而不是空串"
                        + "（空串与 NULL 在库里是两种值，见 DocRecordWriter#blankToNull）")
                .isEqualTo(1);
        assertThat(count("select count(*) from sys_chunk where doc_id = ? and parent_id is null", docId))
                .as("库里真正落下的**顶层块** = 标题 + 正文 + 文档摘要 + 章节摘要 + HyQ = 5")
                .isEqualTo(5);
        assertThat(written)
                .as("⚠️ `replaceChunks` 返回的是**计入总数**的顶层块 = 4，**不含 HyQ** —— "
                        + "`BlockType#countsInChunkTotal()` 只对它返回 false（D-5 §1.5）。"
                        + "把「块写进去了」与「块算不算进总数」分成两个断言，"
                        + "是因为混在一起时，少写一个 HyQ 与 HyQ 被误计入总数**都表现为同一个数字**")
                .isEqualTo(4);
        assertThat(count("select chunk_count from sys_document where id = ?", docId))
                .as("chunk_count 与 replaceChunks 的返回值同源（都用 countsInChunkTotal 过滤）")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("判据9（G4-9 第三条）：HyQ 分片真的落库，且 source_chunk_id 指回源块")
    void hyqBlockIsStoredWithItsSource() {
        long docId = insertProbeDoc();
        List<Block> blocks = List.of(
                new Block("3. 接口设计", BlockType.HEADING, "3. 接口设计", 1, 1, null, null, null),
                new Block("接口鉴权采用令牌方式，令牌有效期 2 小时。", BlockType.PARAGRAPH,
                        "3. 接口设计", 2, 1, null, null, null));

        IndexEnhancer.Result enhanced = indexEnhancer.enhance(doc(), blocks);
        recordWriter.replaceChunks(docId, probeKbIdOf(docId), enhanced.blocks());

        Map<String, Object> hyq = jdbcTemplate.queryForMap(
                "select parent_id, source_chunk_id, raw_content, chunk_type from sys_chunk "
                        + "where doc_id = ? and chunk_type = 'HYPOTHESIS_QA'", docId);
        assertThat(hyq.get("chunk_type")).isEqualTo("HYPOTHESIS_QA");
        assertThat(hyq.get("parent_id"))
                .as("HyQ 是顶层块 —— 挂父块会撞 G4-6 的全局断言")
                .isNull();
        assertThat((String) hyq.get("raw_content"))
                .as("块内容是**问句**（召回用），不是源文")
                .contains("令牌有效期是多久？");

        Long sourceId = ((Number) hyq.get("source_chunk_id")).longValue();
        Map<String, Object> source = jdbcTemplate.queryForMap(
                "select chunk_type, raw_content from sys_chunk where id = ?", sourceId);
        assertThat(source.get("chunk_type"))
                .as("⚠️ 这一条就是 `汇总.md` §4-271 的答案：命中 HyQ 后按 source_chunk_id "
                        + "取回的是**源块**（那段含参数的正文），而不是那条问句")
                .isEqualTo("PARAGRAPH");
        assertThat((String) source.get("raw_content")).contains("令牌有效期 2 小时");

        assertThat(count("select count(*) from sys_chunk where doc_id = ? and chunk_type = 'HYPOTHESIS_QA' "
                + "and source_chunk_id is null", docId))
                .as("每一个 HyQ 块都必须挂上来源；悬空是**降级路径**，不该出现在正常流程里")
                .isZero();

        assertThat(count("select count(*) from sys_chunk where doc_id = ? and source_chunk_id is not null "
                + "and chunk_type <> 'HYPOTHESIS_QA' and chunk_type <> 'SUMMARY'", docId))
                .as("除 HyQ 与章节摘要外，没有别的块类型该有来源")
                .isZero();
    }

    @Test
    @DisplayName("判据10 反面：增强被关掉时，库里就**没有** SUMMARY 行（开关是真的生效的）")
    void noSummaryWhenEnhancerCannotProduceOne() {
        long docId = insertProbeDoc();
        // 空正文 → EMPTY_INPUT，等同于"这次增强没产出摘要"，走的是与关掉开关同一条落库路径
        IndexEnhancer.Result enhanced = indexEnhancer.enhance(
                new ParsedDocument("PDF", 1, 0, "", List.of(), List.of(), 0, 0, List.of(), List.of()),
                List.of(new Block("正文一段", BlockType.PARAGRAPH, "", 1, 1, null, null, null)));

        assertThat(enhanced.status()).isEqualTo(IndexEnhancer.Status.EMPTY_INPUT);
        recordWriter.replaceChunks(docId, probeKbIdOf(docId), enhanced.blocks());

        assertThat(count("select count(*) from sys_chunk where doc_id = ? and chunk_type = 'SUMMARY'", docId))
                .as("没有摘要时库里就不该有 SUMMARY 行 —— 上面那条用例证明了「有」，"
                        + "这条证明「有」不是某种默认行为")
                .isZero();
        assertThat(count("select count(*) from sys_chunk where doc_id = ?", docId))
                .as("但正文块必须照旧落库：摘要失败不牵连文档（这是 T-507 的失败姿态）")
                .isEqualTo(1);
    }

    // ==================================================================

    /**
     * 直接建一行文档 —— 本类考落库，不需要走 MinIO 上传那一整套。
     *
     * <p>md5 每次随机、并**按它回查 id**：用 {@code max(id)} 的话，一旦库里有别的探针残留
     * （上一次跑崩了没清掉），拿到的是**别人的** docId —— 那会让断言在一个与本用例
     * 无关的行上做判断，而且看起来还挺像通过的。
     */
    private long insertProbeDoc() {
        String md5 = java.util.UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("""
                insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5,
                                          status, chunk_count, table_total, table_parsed,
                                          image_total, image_parsed, created_at, updated_at)
                values (8, 1, 'T-507 落库探针', 'probe.md', 'MD', 16, ?,
                        'INDEXING', 0, 0, 0, 0, 0, now(), now())
                """, md5);
        Long id = jdbcTemplate.queryForObject(
                "select id from sys_document where md5 = ?", Long.class, md5);
        probeDocIds.add(id);
        return id;
    }

    private long probeKbIdOf(long docId) {
        Long kbId = jdbcTemplate.queryForObject(
                "select kb_id from sys_document where id = ?", Long.class, docId);
        return kbId == null ? 8L : kbId;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static ParsedDocument doc() {
        List<ParsedDocument.DocElement> elements = List.of(
                ParsedDocument.DocElement.heading(1, "3. 接口设计", 1),
                ParsedDocument.DocElement.paragraph("接口鉴权采用令牌方式，令牌有效期 2 小时。", 1));
        return new ParsedDocument("PDF", 1, 40, "", List.of(), List.of(), 1, 0, List.of(), elements);
    }
}
