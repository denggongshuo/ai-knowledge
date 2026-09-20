package com.knowledge.ai.pipeline.retrieve;

import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.enums.DataScope;
import com.knowledge.ai.core.enums.SecretLevel;
import com.knowledge.ai.core.security.LoginUser;
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
        // 🔴 **修掉一处假绿**（`S7-M06-B` 施工时发现）：夹具的 `insertDocWithChunks` /
        //    `seedVectors` 用的是**字段 `kbId`**（默认库），不是"刚建的那个库" ——
        //    第一版没重指 `kbId`，于是文档与向量全落在**默认库**里，而断言搜的是 `secretKb`。
        //    那样"零召回"的原因是**新库压根没有内容**，与密级一点关系没有：
        //    把这一格的密级换成 PUBLIC，它照样绿。
        kbId = insertKb("scope-probe-secret", "SECRET");
        long docId = insertDocWithChunks("绝密探针", "这段正文属于绝密知识库");
        seedVectors(docId, 1);
        awaitVectorsVisible("这段正文属于绝密知识库", 1);

        // 正对照：上限够的账号搜得到（证明内容真的在那个库里）
        assertThat(retrievalService.search(reader(), "这段正文属于绝密知识库", List.of(kbId)).chunks())
                .as("正对照：超管（绝密上限）应当搜得到它").isNotEmpty();

        // 一个密级上限只有 PUBLIC 的用户：连这个库都不该出现
        RetrievalResult result = retrievalService.search(publicOnlyReader(),
                "这段正文属于绝密知识库", List.of(kbId));

        assertThat(result.chunks()).as("密级超限 → 零召回").isEmpty();
        assertThat(result.trace().kbIds()).doesNotContain(kbId);
    }

    @Test
    @DisplayName("G7-3/I-6 · 文档级密级：生效值 = max(文档, 库) —— **A/B/A 三格缺一不可**")
    void docSecretLevelIsTheMaxOfDocAndKb() {
        // 上限 = 内部的账号：能看见「内部」的库，但看不见「机密」及以上的库/文档
        LoginUser internalUpper = internalUpperReader();
        // ⚠️ 查询串 = 分片正文本身。夹具的向量是**真的**（`seedVectors` 走 `VectorWriter`），
        //    但检索要 token 交集 ⇒ 拿一个不相干的串去查，"零召回"什么都证明不了。
        String q = "这段正文被单独提到了绝密级别";

        // ---- ① / ②：文档**自己**比库更严 ----
        // 🔴 夹具的 `insertDocWithChunks` / `seedVectors` / `awaitVectorsVisible` **都用字段
        //    `kbId`**（不是"文档自己的库"）⇒ 必须先把它指到本用例的库上，否则文档与向量
        //    都落在**默认库**里，而这里却在搜另一个库 —— "零召回"于是与密级毫无关系。
        //    （这条正是第一版的翻车点：它是被下面的正对照逮出来的。）
        kbId = insertKb("scope-probe-docsec", "INTERNAL");
        long doc = insertDocWithChunks("自己绝密", q);
        seedVectors(doc, 1);
        awaitVectorsVisible(q, 1);

        // 正对照：同一个库、**同一个账号、同一个问句**，先确认它本来搜得到。
        // 少了这一句，「零召回」既可能是密级挡的、也可能是检索没匹配上 —— 判据就废了。
        jdbc.update("update sys_document set secret_level = null where id = ?", doc);
        assertThat(retrievalService.search(internalUpper, q, List.of(kbId)).chunks())
                .as("正对照：文档未覆盖（继承内部）⇒ 这个账号本来搜得到")
                .isNotEmpty();

        // 提到绝密 ⇒ 同一个账号同一个问句必须搜不到
        jdbc.update("update sys_document set secret_level = 'SECRET' where id = ?", doc);
        RetrievalResult blocked = retrievalService.search(internalUpper, q, List.of(kbId));
        assertThat(blocked.trace().kbIds())
                .as("库本身不超限（内部 ≤ 内部上限）⇒ 挡住它的**必须**是文档级那条")
                .contains(kbId);
        assertThat(blocked.chunks())
                .as("🔴 文档自己提到绝密 ⇒ 库级放行也召回不到它（生效值 = max(文档, 库)）")
                .isEmpty();

        // 🔴 这条**单独钉 `resolve` 的短路**。为什么不能省：上面那条"零召回"是
        //    `milvusFilter()` 的防御（空 docIds ⇒ `doc_id in [-1]`）**独自**就能满足的 ——
        //    实测把短路的判断去掉，上面那条照样绿（证伪失败）。两处修的是同一件事，
        //    判据只钉得住一处就等于没钉住另一处。
        RetrievalScope.Scope scope = retrievalScope.resolve(internalUpper, List.of(kbId));
        assertThat(scope.partitions()).as("可见文档为 0 ⇒ 不给 Milvus 任何分区（省一次必然空的往返）")
                .isEmpty();
        assertThat(scope.kbIds()).as("但库仍要如实报出来 —— 它**确实**可见，只是里面没有你能看的文档")
                .contains(kbId);
        assertThat(scope.empty()).as("empty() 为真 ⇒ 调用方短路，根本不去连 Milvus").isTrue();

        // 🔴 再钉一下 `milvusFilter()` 的**防御分支**。短路之后它在服务路径上已经走不到了
        //    （`resolve` 不会再把空 docIds 交出去），但它防的是"别处直接构造 Scope"——
        //    而 `Scope` 是公开 record，用例与将来的调用方都可能那么干。
        //    直接构造一个空 docIds 的 Scope，断言它**恒不匹配**而不是退回"只看库"。
        RetrievalScope.Scope handMade = new RetrievalScope.Scope(
                List.of(kbId), java.util.Set.of(), List.of("probe-partition"), false);
        assertThat(handMade.milvusFilter())
                .as("🔴 空 docIds 时**绝不能**回退成只按库过滤 —— 那是取消过滤 = 越权可见")
                .contains(MilvusConstants.FIELD_DOC_ID + " in [-1]")
                .doesNotEndWith(MilvusConstants.FIELD_KB_ID + " in [" + kbId + "]");

        // 对照组（B 回到 A）：撤回覆盖 ⇒ 又搜得到。这一步同时证明上一格的"空"是它造成的
        jdbc.update("update sys_document set secret_level = null where id = ?", doc);
        assertThat(retrievalService.search(internalUpper, q, List.of(kbId)).chunks())
                .as("对照组：撤回覆盖后应当恢复召回")
                .isNotEmpty();

        // ---- ③：库级超限（文档未覆盖，文档级那条兜不住）----
        String q2 = "这段正文继承所属知识库的机密级别";
        kbId = insertKb("scope-probe-docsec-kb", "CONFIDENTIAL");
        long inherited = insertDocWithChunks("继承机密", q2);
        seedVectors(inherited, 1);
        awaitVectorsVisible(q2, 1);
        assertThat(retrievalService.search(reader(), q2, List.of(kbId)).chunks())
                .as("正对照：上限够的账号（超管/绝密）能搜到它 —— 否则下面那一格的空不算数")
                .isNotEmpty();

        RetrievalResult byKb = retrievalService.search(internalUpper, q2, List.of(kbId));
        // 判据落在 `kbIds` 上才是决定性的：库**根本不在集合里**（而不是"库里搜不到"）
        assertThat(byKb.trace().kbIds()).as("密级超限的库连出现都不出现").doesNotContain(kbId);
        assertThat(byKb.chunks()).as("库级超限 ⇒ 零召回（文档级那条是 null，兜不住）").isEmpty();
    }

    /** 密级上限 = 「内部」的账号：看得见「内部」的库，看不见「机密」及以上的。 */
    private static LoginUser internalUpperReader() {
        return LoginUser.builder()
                .userId(1L)
                .username("admin")
                .deptId(1L)
                .superAdmin(false)
                .dataScope(DataScope.ALL)
                .secretLevelUpper(SecretLevel.INTERNAL)
                .build();
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
