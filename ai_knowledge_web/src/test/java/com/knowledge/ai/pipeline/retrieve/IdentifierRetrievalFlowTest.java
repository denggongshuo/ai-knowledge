package com.knowledge.ai.pipeline.retrieve;

import com.knowledge.ai.dto.kb.KbDtos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 标识符保护的**真链路**门禁（`G4-8` 运行时侧 · `T-700`）。
 *
 * <h3>本用例把计划 §3.4 的探针表在真链路上重跑一遍</h3>
 * <p>步 0 的那次探针是在**一次性探测集合**上做的（见 `plan/2026-09-17-S5-M07-A.md` §3.4），
 * 它证明的是"Milvus 的 BM25 + whitespace + {@code IdentifierTokenizer} 这套组合**能**做到"。
 * 本用例证明的是"**本仓的检索链路真的那么做了**" —— 两者不是同一件事：
 * 探针用的是现拼的查询，链路走的是 {@code Bm25Text.of(query)}；只要哪一侧漏了这个函数，
 * 探针照样全绿而链路全错（而且**不报错**，只是召回不到）。
 *
 * <h3>⚠️ 为什么用 KEYWORD 模式而不是 HYBRID</h3>
 * <p>`G4-8` 要的判据是「查 {@code A-100} **不召回** {@code A-1000}」，说的是
 * **关键词这一路**的分辨力。HYBRID 模式下向量路会把"语义相近"的内容一并带回来 ——
 * 那是混合检索**应有**的行为（`FR-RET-03` 要的正是两路互补），拿它去断言"不召回"
 * 等于要求向量路也精确匹配，那不是这条判据的语义。
 * 所以：**分辨力**在 KEYWORD 下验，**通道确实在跑**在下面的用例里单独验。
 */
class IdentifierRetrievalFlowTest extends RetrievalFlowSupport {

    /** `FR-RET-03` §3.3 的五类标识符，每类一对"差一个字符"的最小对照。 */
    private static final String[][] PAIRS = {
            {"￥1,200", "￥1,2000"},
            {"SKU-8890-A", "SKU-8890-AB"},
            {"2026-09-11", "2026-09-12"},
            {"v1.4", "v1.44"},
            {"A-100", "A-1000"},
    };

    @BeforeEach
    void setUp(TestInfo info) {
        resetProbeState();
        RetrievalTestStubs.resetRerank();
        // 方法名会成为库编码的一部分 —— 每个用例一个库，互不干扰
        String tag = info.getTestMethod().map(java.lang.reflect.Method::getName).orElse("case");
        kbId = insertKb("ident-" + tag.toLowerCase(java.util.Locale.ROOT), "INTERNAL",
                "{\"mode\":\"KEYWORD\",\"recallThreshold\":0.15,\"rerankTopK\":5,"
                        + "\"rerankEnabled\":false,\"tableWeight\":1.2,\"identifierProtect\":true}");
    }

    @AfterEach
    void tearDown() {
        cleanProbeData();
    }

    @Test
    @DisplayName("G4-8 · 五类标识符 10 对对照：只回它自己那一篇")
    void allFiveIdentifierKindsDiscriminateExactly() {
        for (String[] pair : PAIRS) {
            String left = pair[0];
            String right = pair[1];

            // 每对独立建库建文档，避免上一对的向量/词项干扰这一对
            long caseKb = insertKb("ident-case-" + Math.abs(left.hashCode()), "INTERNAL", KEYWORD_CONFIG);
            long savedKb = kbId;
            kbId = caseKb;
            try {
                long docLeft = insertDocWithChunks("文档一", "型号 " + left + " 的技术参数说明");
                long docRight = insertDocWithChunks("文档二", "型号 " + right + " 的技术参数说明");
                seedVectors(docLeft, 1);
                seedVectors(docRight, 1);
                // ⚠️ 两条都要等：只等一条的话，第二条可能还没可见，
                // 于是"查它自己"会返回空 —— 那是可见性窗口，不是标识符串扰
                awaitRecallVisible(left, "KEYWORD", 1);
                awaitRecallVisible(right, "KEYWORD", 1);

                assertRecall(caseKb, left, docLeft, docRight);
                assertRecall(caseKb, right, docRight, docLeft);
            } finally {
                kbId = savedKb;
                cleanCaseKb(caseKb, "ident-case-" + Math.abs(left.hashCode()));
            }
        }
    }

    @Test
    @DisplayName("G4-8 · BM25 通道**确实在跑**（不是空转后靠向量蒙对）")
    void bm25ChannelIsActuallyDoingTheWork() {
        long docId = insertDocWithChunks("通道探针", "这段正文里有 SKU-8890-A 这个型号");
        seedVectors(docId, 1);
        awaitVectorsVisible("SKU-8890-A", 1);

        RetrievalScope.Scope scope = retrievalScope.resolve(reader(), List.of(kbId));
        HybridRetriever.Recall keyword = retriever().recall("SKU-8890-A", scope, params("KEYWORD"));
        assertThat(keyword.bm25Hits()).as("BM25 路必须召回得到").isNotEmpty();
        assertThat(keyword.vectorHits()).as("KEYWORD 模式不跑向量").isEmpty();

        // 反向对照：换一个**库里不存在**的标识符 → BM25 路零召回。
        // 这一条排除了"BM25 路对任何输入都返回一堆东西"这种假阳性
        resetProbeState();
        HybridRetriever.Recall miss = retriever().recall("ZZ-99999-X", scope, params("KEYWORD"));
        assertThat(miss.bm25Hits()).as("库里没有的标识符不该召回任何东西").isEmpty();
    }

    @Test
    @DisplayName("G4-8 · 关掉 BM25（纯向量）时，标识符分辨力**下降**（这就是它存在的理由）")
    void disablingBm25LosesTheDiscrimination() {
        long docLeft = insertDocWithChunks("文档一", "型号 A-100 的技术参数说明");
        long docRight = insertDocWithChunks("文档二", "型号 A-1000 的技术参数说明");
        seedVectors(docLeft, 1);
        seedVectors(docRight, 1);
        awaitVectorsVisible("A-100", 1);

        RetrievalScope.Scope scope = retrievalScope.resolve(reader(), List.of(kbId));

        HybridRetriever.Recall keyword = retriever().recall("A-100", scope, params("KEYWORD"));
        long keywordHitsOnWrongDoc = keyword.bm25Hits().stream()
                .filter(hit -> hit.docId() == docRight).count();
        assertThat(keywordHitsOnWrongDoc).as("BM25 路零串扰").isZero();

        // 向量路（`hashedVector` 是按 token 哈希的声明式向量）：`A-100` 与 `A-1000`
        // 是两个不同的 token，所以本替身下向量路**也**分得开。
        // 这条不是为了证明向量路不行，而是把"本用例的替身是什么形状"写下来 ——
        // 真语料上向量路的串扰由 `T-711` 的评测集量化，那才是这条判据的终审
        resetProbeState();
        HybridRetriever.Recall vector = retriever().recall("A-100", scope, params("VECTOR"));
        assertThat(vector.vectorHits()).as("向量路仍应召回到东西（只是不保证分辨力）").isNotEmpty();
    }

    // ==================== 工具 ====================

    /** 断言：查 {@code query} 只命中 {@code expected} 那一篇，不命中 {@code forbidden}。 */
    private void assertRecall(long caseKb, String query, long expected, long forbidden) {
        RetrievalResult result = retrievalService().search(reader(), query, List.of(caseKb));

        assertThat(result.chunks()).as("查「%s」必须召回它自己那一篇", query).isNotEmpty();
        assertThat(result.chunks()).allSatisfy(chunk -> assertThat(chunk.docId())
                .as("查「%s」召回了错误的文档", query)
                .isEqualTo(expected));
        assertThat(result.chunks()).as("查「%s」不该召回对照文档", query)
                .noneSatisfy(chunk -> assertThat(chunk.docId()).isEqualTo(forbidden));
    }

    @org.springframework.beans.factory.annotation.Autowired
    private RetrievalService injectedService;

    private RetrievalService retrievalService() {
        return injectedService;
    }

    private static final String KEYWORD_CONFIG =
            "{\"mode\":\"KEYWORD\",\"recallThreshold\":0.15,\"rerankTopK\":5,"
                    + "\"rerankEnabled\":false,\"tableWeight\":1.2,\"identifierProtect\":true}";

    private static RetrievalParams params(String mode) {
        return RetrievalParams.resolve(new KbDtos.RetrievalConfig(mode, 0.15, 5, false, 1.2, true),
                new com.knowledge.ai.core.setting.SystemSettingService.Snapshot(Map.of()));
    }

    /** 清掉一个临时子用例库（连同它的分区）。 */
    private void cleanCaseKb(long caseKb, String code) {
        for (int generation : partitions.generationsOf(caseKb)) {
            partitions.dropQuietly(com.knowledge.ai.core.constant.MilvusConstants
                    .partitionName(caseKb, generation));
        }
        jdbc.update("delete from sys_chunk where kb_id = ?", caseKb);
        jdbc.update("delete from sys_document where kb_id = ?", caseKb);
        jdbc.update("delete from sys_kb_grant where kb_id = ?", caseKb);
        jdbc.update("delete from sys_kb where id = ?", caseKb);
        assertThat(code).isNotBlank();
    }
}
