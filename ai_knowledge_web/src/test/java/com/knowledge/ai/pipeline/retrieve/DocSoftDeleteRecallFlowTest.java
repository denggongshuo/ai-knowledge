package com.knowledge.ai.pipeline.retrieve;

import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.service.DocLifecycleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `G7-14` 的另一半：**软删的文档不可召回**（`T-611` · `S7-M06-F`）。
 *
 * <h3>为什么这条判据不能落在"列表里看不见"上</h3>
 * <p>`G7-14` 的正面要求是「删除**不立即删向量**」—— 那意味着软删之后
 * **Milvus 里那几条点还在**。于是"不可召回"这半边如果不单独取证，
 * 就会出现一条最坏的形态：**删了等于没删**（谁会去提一个已被删除的文档？答案是谁都想不到，
 * 但检索是"按内容匹配"的，用户提一句相关的话就会把它捞出来）。
 *
 * <h3>🔴 本类的关键齿是"向量还在、却搜不到"</h3>
 * <p>只断言「软删后召回为 0」是一条**恒真的假判据**：向量根本没写进去、
 * 分区被丢了、替身没注册 —— 任何一种都能让它变绿，而它们都不是"软删在挡"。
 * 所以本类把三件事**同时**钉住：
 * <ol>
 *   <li>软删**之前**搜得到（正对照；少这一条，"之后搜不到"什么都证明不了）；</li>
 *   <li>软删**之后**用 `countIn` 直查分区 —— **向量一条没少**（`G7-14` 的正面要求）；</li>
 *   <li>在「向量还在」这个前提下，同一条提问**零召回** ⇒ 挡住它的只可能是
 *       `RetrievalScope#visibleDocIds` 里那句 `d.deleted_at is null`。</li>
 * </ol>
 *
 * <h3>为什么还要验打点</h3>
 * <p>承 `S7-M06-D` 的教训（`D-2`「打点后补 = 数据永久缺失」）：零召回那次如果**不落库**，
 * 页面上"某段时间一次都没搜过"与"搜了但没命中"长得一模一样 —— 而后者正是
 * 未命中池（`T-A00`）的输入。所以本类顺带把"零召回那次的 trace 真落进库、
 * 且如实记着 `doc_scope_count = 0`"也钉住。
 *
 * <p>⚠️ 本类**不走 HTTP**：`RetrievalFlowSupport` 起的是普通 `@SpringBootTest`（无 web 环境）。
 * 软删那一步直调 {@link DocLifecycleService#softDelete}（HTTP 控制器调的就是它），
 * 并**手工把 `LoginUserContext` 设上** —— 那是该方法的第一个动作（`LoginUserContext.require()`），
 * 而且 `@AuditLog` 切面也靠它写"谁删的"。
 */
class DocSoftDeleteRecallFlowTest extends RetrievalFlowSupport {

    private static final String KB_CONFIG =
            "{\"mode\":\"HYBRID\",\"recallThreshold\":0.15,\"rerankTopK\":5,"
                    + "\"rerankEnabled\":false,\"tableWeight\":1.2,\"identifierProtect\":true}";

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private RetrievalTraceWriter retrievalTraceWriter;

    @Autowired
    private DocLifecycleService docLifecycleService;

    private final List<String> traceIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        resetProbeState();
        RetrievalTestStubs.resetRerank();
        kbId = insertKb("doc-soft-delete-probe", "INTERNAL", KB_CONFIG);
    }

    @AfterEach
    void tearDown() {
        for (String traceId : traceIds) {
            jdbc.update("delete from sys_retrieval_trace_chunk where trace_id = ?", traceId);
            jdbc.update("delete from sys_retrieval_trace where trace_id = ?", traceId);
        }
        traceIds.clear();
        cleanProbeData();
    }

    @Test
    @DisplayName("G7-14：软删的文档零召回 —— 而它的向量**一条没少**（所以挡住它的只能是 scope）")
    void softDeletedDocIsNotRecallableWhileItsVectorsRemain() {
        long docId = insertDocWithChunks("软删召回探针", "这段正文属于一篇即将被软删的文档");
        seedVectors(docId, 1);
        awaitVectorsVisible("软删召回探针", 1);
        String partition = MilvusConstants.partitionName(kbId, 1);

        // ---- ① 正对照：软删之前必须搜得到 ----
        RetrievalResult before = retrievalService.search(reader(), "软删召回探针", List.of(kbId));
        assertThat(before.chunks()).as("软删之前必须搜得到，否则本用例证明不了任何事").isNotEmpty();
        assertThat(before.trace().docScopeCount()).as("此时可见文档 = 1 篇").isEqualTo(1);

        // ---- ② 走**真**软删入口 ----
        LoginUserContext.set(reader());
        try {
            docLifecycleService.softDelete(docId);
        } finally {
            // ⚠️ ThreadLocal 用完必须清：JUnit 复用线程，不清会让**下一个用例**
            //    带着这个身份跑（现象是"改了无关的用例就红"）
            LoginUserContext.clear();
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from sys_document where id = ? and deleted_at is not null",
                Long.class, docId)).as("软删确实生效了（不生效的话下面全是空断言）").isEqualTo(1L);

        // ---- ③ 🔴 关键齿：向量**还在** ----
        assertThat(partitions.countIn(partition))
                .as("🔴 软删之后向量一条没少（`G7-14` 的正面要求：删除**不立即删向量**）"
                        + " —— 正因为它还在，下面那条「零召回」才只能由 scope 解释")
                .isEqualTo(1L);

        // ---- ④ 同一条提问，零召回 ----
        RetrievalResult after = retrievalService.search(reader(), "软删召回探针", List.of(kbId));
        assertThat(after.chunks())
                .as("软删的文档必须零召回 —— 否则「软删」等于没删（向量还在，这条尤其要紧）")
                .isEmpty();
        assertThat(after.trace().docScopeCount()).as("可见文档数归 0").isZero();
        assertThat(after.trace().kbIds()).as("库**仍然可见**（软删的是文档不是库，别把两件事混起来）")
                .contains(kbId);
        assertThat(after.trace().rerankNote()).as("零召回的原因要说得出话").isNotNull();

        // ---- ⑤ 打点：零召回那次**也要落库** ----
        String traceId = "doc-soft-delete-" + System.nanoTime();
        traceIds.add(traceId);
        retrievalTraceWriter.write(new RetrievalTraceWriter.TraceWrite(
                traceId, null, null, null, after, 1000L, 1500L, java.util.Set.of()));

        assertThat(jdbc.queryForObject(
                "select count(*) from sys_retrieval_trace where trace_id = ?", Long.class, traceId))
                .as("零召回那次检索也留了打点（`D-2`：打点后补 = 数据永久缺失）")
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "select doc_scope_count from sys_retrieval_trace where trace_id = ?", Integer.class, traceId))
                .as("打点里如实记着「可见文档 0 篇」—— 那是这次零召回的**原因**，"
                        + "不是「没人问过」（两者在页面上本该分得开）")
                .isZero();
        assertThat(jdbc.queryForObject(
                "select kb_count from sys_retrieval_trace where trace_id = ?", Integer.class, traceId))
                .as("库数仍是 1 —— 与 `doc_scope_count = 0` 并存，正说明挡它的是「文档级」那一层")
                .isEqualTo(1);
    }
}
