package com.knowledge.ai.pipeline.retrieve;

import com.knowledge.ai.core.constant.MilvusConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可见范围与代切换的门禁用例（`S4-D` §8 判据 12 的**检索侧** · `T-700` 第 ① 步）。
 *
 * <h3>这一批销的是两条**挂起判据**</h3>
 * <ol>
 *   <li><b>软删知识库不出现在检索结果</b> —— `T-407` 把软删加进了
 *       {@code DataScopeService#kbVisibility}，当时只验到"列表不出现"；
 *       "检索也不该召回它的分片"要等本批的检索链路才验得了；</li>
 *   <li><b>重建后返回**新代**结果</b> —— `S4-D` 的重建只验到
 *       "切代是一次 UPDATE、新代分区里就是那些向量"；**用户实际搜到的是哪一代**
 *       同样要等这条链路。</li>
 * </ol>
 *
 * <p>两条都是"链路合起来才对"的性质 —— 单看任何一段的实现都发现不了它们，
 * 这正是把它们挂在检索侧验收的原因。
 */
class RetrievalScopeFlowTest extends RetrievalFlowSupport {

    @Autowired
    private RetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        resetProbeState();
        RetrievalTestStubs.resetRerank();
        kbId = insertKb("scope-probe", "INTERNAL",
                "{\"mode\":\"HYBRID\",\"recallThreshold\":0.15,\"rerankTopK\":5,"
                        + "\"rerankEnabled\":false,\"tableWeight\":1.2,\"identifierProtect\":true}");
    }

    @AfterEach
    void tearDown() {
        cleanProbeData();
    }

    @Test
    @DisplayName("判据 12 · 软删的知识库：检索零召回，且 trace 里连它都不出现")
    void softDeletedKbIsNotRetrievable() {
        long docId = insertDocWithChunks("软删探针", "这段正文属于一个即将被软删的知识库");
        seedVectors(docId, 1);
        awaitVectorsVisible("软删探针", 1);

        // 先确认软删之前确实搜得到 —— 否则下面的"零召回"可能是因为别的原因（比如没写进去）
        RetrievalResult before = retrievalService.search(reader(), "软删探针", List.of(kbId));
        assertThat(before.chunks()).as("软删之前必须搜得到，否则本用例证明不了任何事").isNotEmpty();

        // `T-407` 的软删：改的是 sys_kb.deleted_at，`kbVisibility` 里的 `k.deleted_at IS NULL` 会硬交掉
        jdbc.update("update sys_kb set deleted_at = now() where id = ?", kbId);

        RetrievalResult after = retrievalService.search(reader(), "软删探针", List.of(kbId));

        assertThat(after.chunks()).as("软删库的分片必须零召回").isEmpty();
        assertThat(after.trace().kbIds()).as("trace 里也不该出现这个库").doesNotContain(kbId);
        assertThat(after.trace().kbCount()).isZero();
        assertThat(after.trace().rerankNote()).contains("没有可检索的知识库");
    }

    @Test
    @DisplayName("判据 12 · 重建切代之后，检索返回的是**新代**的内容")
    void afterGenerationSwitchRetrievalReadsTheNewGeneration() {
        // 第 1 代：文档内容里只有「旧代字样」
        long docId = insertDocWithChunks("代切换探针", "旧代字样：容量按两倍预留");
        seedVectors(docId, 1);
        awaitVectorsVisible("旧代字样", 1);

        RetrievalResult before = retrievalService.search(reader(), "旧代字样", List.of(kbId));
        assertThat(before.chunks()).isNotEmpty();

        // 第 2 代：同一篇文档在新代里的向量换了内容（模拟重建时重新分片/重新投影）
        int generation = jdbc.queryForObject(
                "select index_generation from sys_kb where id = ?", Integer.class, kbId);
        int next = generation + 1;
        String newPartition = MilvusConstants.partitionName(kbId, next);
        partitions.ensure(newPartition);
        Long chunkId = jdbc.queryForObject(
                "select id from sys_chunk where doc_id = ? order by order_no limit 1", Long.class, docId);
        vectorWriter.replaceDocument(newPartition, docId, List.of(new com.knowledge.ai.pipeline.vectorize.VectorRecord(
                new com.knowledge.ai.pipeline.vectorize.VectorChunk(
                        chunkId, "PARAGRAPH", "新代字样：容量按五倍预留", null, "INTERNAL", null),
                kbId, docId, hashedVector("新代字样：容量按五倍预留"), "BAAI/bge-m3", "v1")));
        // `T-406` 的原子切换：一次 UPDATE 改的只是这一列
        jdbc.update("update sys_kb set index_generation = ? where id = ?", next, kbId);
        jdbc.update("update sys_document set index_generation = ? where id = ?", next, docId);
        // 新代分区刚写入 —— 同样要等可见（见 RetrievalFlowSupport#awaitVectorsVisible 的实测记录）
        awaitVectorsVisible("新代字样", 1);

        RetrievalResult after = retrievalService.search(reader(), "新代字样", List.of(kbId));

        assertThat(after.chunks()).as("新代的向量必须搜得到").isNotEmpty();
        assertThat(after.chunks()).allSatisfy(chunk ->
                assertThat(chunk.content()).as("召回的必须是新代分区里的那份内容")
                        .contains("五倍"));
        assertThat(after.trace().chunks()).isSameAs(after.chunks());
    }

    @Test
    @DisplayName("密级高于用户上限的知识库不参与召回")
    void kbAboveUserSecretLevelIsInvisible() {
        long secretKb = insertKb("scope-probe-secret", "SECRET");
        long docId = insertDocWithChunks("绝密探针", "这段正文属于绝密知识库");
        seedVectors(docId, 1);

        // 一个密级上限只有 PUBLIC 的用户：连这个库都不该出现
        RetrievalResult result = retrievalService.search(publicOnlyReader(), "绝密探针", List.of(secretKb));

        assertThat(result.chunks()).as("密级超限 → 零召回").isEmpty();
        assertThat(result.trace().kbIds()).doesNotContain(secretKb);
    }

    @Test
    @DisplayName("请求里指定了看不见的库，不放大范围（限定值只能取交集）")
    void requestingAnInvisibleKbDoesNotWidenScope() {
        long visibleDoc = insertDocWithChunks("可见探针", "可见库里的正文，含关键词可见探针");
        seedVectors(visibleDoc, 1);
        awaitVectorsVisible("可见探针", 1);

        long otherKb = insertKb("scope-probe-other");
        long otherDoc = insertDocWithChunks("别的探针", "别的库里的正文");

        RetrievalResult result = retrievalService.search(reader(), "探针", List.of(otherKb));

        assertThat(result.trace().kbIds()).containsExactly(otherKb);
        assertThat(result.chunks())
                .as("只查了 otherKb，就不该召回 visible 那个库的分片")
                .allSatisfy(chunk -> assertThat(chunk.kbId()).isEqualTo(otherKb));
        assertThat(otherDoc).isPositive();
    }
}
