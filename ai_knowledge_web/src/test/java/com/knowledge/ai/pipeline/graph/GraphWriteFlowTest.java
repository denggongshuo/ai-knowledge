package com.knowledge.ai.pipeline.graph;

import com.knowledge.ai.pipeline.enhance.IndexEnhancer;
import com.knowledge.ai.support.DownloadTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-C00` 图谱构建侧的真机取证（判据 1~9）。
 *
 * <h3>为什么必须开真 Neo4j，而不是替身</h3>
 * <p>本批交付物的**全部价值**在 Cypher 里 —— `MERGE` 幂等、按文档删旧建新、
 * `UNWIND` 批量、`ON CREATE/ON MATCH` 的语义。这些**没有一条**能用替身验：
 * 替身只会证明"我调用了它"，而"调用它"这件事本批早就成立（关系数据一直在产）。
 * ⇒ 与 {@code MilvusVectorWriteTest} 同一条取舍：真写、真读、真断言，
 * 但**用负号 doc_id 段**与真实自增 id 隔开，收尾按登记的 id 清干净。
 *
 * <h3>为什么不用 {@code @Tag("network")}</h3>
 * <p>{@code network} 那条 tag 的口径是"**依赖外部网络**"（模型 relay / 公网）。
 * Neo4j 是 `docker-compose` 里的**常驻依赖**，与 MySQL / Redis / Milvus 同级 ——
 * 后三者都有不带 tag 的真机用例（`RecycleFlowTest` 真查 MySQL、
 * `MilvusVectorWriteTest` 真查 Milvus）。把 Neo4j 单独打 tag 会让**本批最核心的
 * 9 条判据在默认跑批里一次都不执行**，那正是本仓反复吃亏的"闸不在必跑路径上"。
 *
 * <h3>⚠️ 测试期的开关</h3>
 * <p>{@code src/test/resources/application.properties} 把 {@code m05.graph.enabled}
 * 关掉了（否则每次上传都会往图库写边）。本类用 {@code @TestPropertySource} 单独打开 ——
 * 与 {@code IndexEnhanceFlowTest} 打开 {@code m05.enhance.enabled} 同一种做法。
 *
 * <p>⚠️ <b>本类**不**自己声明 {@code @SpringBootTest}</b>：父类 {@code ApiTestSupport}
 * 已经声明了 {@code RANDOM_PORT}，而子类再声明一个（默认是 {@code MOCK}）会**覆盖**它 ——
 * 现象是注入失败，报 {@code Could not resolve placeholder 'local.server.port'}
 * （第一版就是这么红的，与"图写错了"毫无关系）。
 */
@TestPropertySource(properties = "m05.graph.enabled=true")
class GraphWriteFlowTest extends DownloadTestSupport {

    @Autowired
    private GraphWriter graphWriter;

    @Autowired
    private GraphSchema graphSchema;

    @Autowired
    private Driver driver;

    /** 本用例造出来的 doc_id（收尾按它清图）。**负号段**，与真实自增 id 绝不相撞。 */
    private final List<Long> probeDocIds = new ArrayList<>();

    private long nextDocId() {
        long id = -(System.nanoTime() % 1_000_000_000L) - 1L;
        probeDocIds.add(id);
        return id;
    }

    @AfterEach
    void cleanGraphResidue() {
        // 🔴 **图库是第四种存储，父类的清理完全不认识它** ——
        //    不收尾就会每跑一轮往图里堆一批谁也认领不了的实体与边
        //    （`S7-M06-D` 的 `#130` 族形状第五次）。
        //    按登记的 docId 清：`deleteDocuments` 会连 `REL` / `CITES` / `Document` 一起删。
        //    ⚠️ **实体节点不清** —— 它是跨文档共享的（`07:462`），
        //    按 docId 删不掉也不该删；探针实体名带前缀（`探针实体-`），
        //    这里额外按名字前缀兜一次底，免得图里越积越多。
        if (!probeDocIds.isEmpty()) {
            graphWriter.deleteDocuments(List.copyOf(probeDocIds), -1L);
            try (Session session = driver.session()) {
                session.run("MATCH (e:Entity) WHERE e.name STARTS WITH '探针' DETACH DELETE e")
                        .consume();
            } catch (Exception ignored) {
                // 清不掉也不该让用例失败（失败信息已经由断言给出）
            }
            probeDocIds.clear();
        }
    }

    /** 造一条关系（四参形态）。 */
    private static IndexEnhancer.Enhancement.Relation rel(String s, String p, String o, double c) {
        return new IndexEnhancer.Enhancement.Relation(s, p, o, c);
    }

    /** 锚定结果 + 块序→chunk.id 映射（探针用假 id，避开自增段）。 */
    private static List<RelationAnchor.Anchored> anchored(List<IndexEnhancer.Enhancement.Relation> rels,
                                                          int... orders) {
        List<RelationAnchor.Anchored> out = new ArrayList<>();
        for (int i = 0; i < rels.size(); i++) {
            out.add(new RelationAnchor.Anchored(rels.get(i), orders[i]));
        }
        return out;
    }

    private static Map<Integer, Long> orderToChunkId(int... orders) {
        Map<Integer, Long> map = new HashMap<>();
        for (int i = 0; i < orders.length; i++) {
            // 负数段：与真实自增 chunk.id 隔开，且**只作为 Cypher 参数**，不落 MySQL
            map.put(orders[i], -(1_000_000L + i));
        }
        return map;
    }

    // ==================================================================
    // 判据 3 · MERGE 幂等
    // ==================================================================

    @Test
    @DisplayName("判据 3：同一篇写两遍 ⇒ 节点与边都不翻倍（MERGE 幂等）")
    void writingTwiceDoesNotDuplicate() {
        long docId = nextDocId();
        List<RelationAnchor.Anchored> edges = anchored(
                List.of(rel("探针实体-甲", "基于", "探针实体-乙", 0.9)), 1);
        Map<Integer, Long> chunkIds = orderToChunkId(1);

        graphWriter.replaceDocument(docId, "探针文档", edges, chunkIds);
        List<Map<String, Object>> first = graphWriter.relationsOf(docId);

        graphWriter.replaceDocument(docId, "探针文档", edges, chunkIds);
        List<Map<String, Object>> second = graphWriter.relationsOf(docId);

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        // 边数相等**不够** —— 还要确认实体没分裂成两个同名节点
        try (Session session = driver.session()) {
            int nodes = session.run(
                            "MATCH (e:Entity) WHERE e.name IN ['探针实体-甲', '探针实体-乙'] "
                                    + "RETURN count(e) AS n").single().get("n").asInt();
            assertThat(nodes).as("同名实体必须 MERGE 成一个节点（幂等）").isEqualTo(2);
        }
    }

    @Test
    @DisplayName("🔴 判据 3 的**承重那一半**：同一批里出现两条**完全相同**的关系 ⇒ 只落一条边")
    void duplicateRelationInOneBatchCollapsesToOneEdge() {
        long docId = nextDocId();
        // 模型完全可能把同一句关系抽两遍（同一个三元组、同一个块序）。
        // ⚠️ **这一条才是 `MERGE` 真正承重的地方** —— 见下面 `writingTwiceDoesNotDuplicate`
        //    的注释：那条的"两遍不翻倍"其实由「先删旧边」保证，把 MERGE 换成 CREATE 它照样绿
        //    （证伪 F1 实测）。而**同一批内**的重复，先删那一步救不了 ——
        //    它只能靠 `MERGE` 把第二条并到第一条上。
        graphWriter.replaceDocument(docId, "探针文档",
                anchored(List.of(
                        rel("探针实体-甲", "基于", "探针实体-乙", 0.9),
                        rel("探针实体-甲", "基于", "探针实体-乙", 0.9)), 1, 1),
                orderToChunkId(1));

        assertThat(graphWriter.relationsOf(docId))
                .as("同一个三元组在同一批里出现两次，图里只能有一条边 —— "
                        + "把 `MERGE (s)-[r:REL {...}]->(o)` 换成 `CREATE` 本条当场转红")
                .hasSize(1);
    }

    // ==================================================================
    // 判据 4 · 按文档版本删旧建新
    // ==================================================================

    @Test
    @DisplayName("判据 4：写 v1 的边、再写 v2 的边 ⇒ 图里只剩 v2")
    void newVersionReplacesOldRelations() {
        long docId = nextDocId();

        graphWriter.replaceDocument(docId, "探针文档",
                anchored(List.of(rel("探针实体-甲", "基于", "探针实体-乙", 0.9)), 1),
                orderToChunkId(1));
        assertThat(graphWriter.relationsOf(docId)).hasSize(1);

        // v2 换了一条完全不同的关系
        graphWriter.replaceDocument(docId, "探针文档",
                anchored(List.of(rel("探针实体-丙", "依赖", "探针实体-丁", 0.8)), 2),
                orderToChunkId(2));

        List<Map<String, Object>> after = graphWriter.relationsOf(docId);
        assertThat(after).as("v1 的边必须被删掉，不是只加不删").hasSize(1);
        assertThat(after.getFirst().get("subject")).isEqualTo("探针实体-丙");
    }

    @Test
    @DisplayName("判据 4 的负向半边：**空关系集也要清旧边**")
    void emptyRelationSetStillClearsOldEdges() {
        long docId = nextDocId();
        graphWriter.replaceDocument(docId, "探针文档",
                anchored(List.of(rel("探针实体-甲", "基于", "探针实体-乙", 0.9)), 1),
                orderToChunkId(1));
        assertThat(graphWriter.relationsOf(docId)).hasSize(1);

        // 重解析后一条关系都没抽到 —— 旧边必须消失，否则"属于上一版的关系"会继续被检索到
        graphWriter.replaceDocument(docId, "探针文档", List.of(), Map.of());

        assertThat(graphWriter.relationsOf(docId)).isEmpty();
    }

    // ==================================================================
    // 判据 5 · sourceChunkId 可回源
    // ==================================================================

    @Test
    @DisplayName("判据 5：每条边都带 sourceChunkId（需求 :469 的可溯源底线）")
    void everyRelationCarriesItsSourceChunkId() {
        long docId = nextDocId();
        graphWriter.replaceDocument(docId, "探针文档",
                anchored(List.of(rel("探针实体-甲", "基于", "探针实体-乙", 0.9)), 7),
                orderToChunkId(7));

        try (Session session = driver.session()) {
            var record = session.run("""
                    MATCH ()-[r:REL]->()
                    WHERE r.docId = $docId
                    RETURN r.sourceChunkId AS chunkId, count(r) AS n
                    """, Map.of("docId", docId)).single();
            assertThat(record.get("chunkId").isNull())
                    .as("sourceChunkId 为空 = 这条边回不到原文").isFalse();
            assertThat(record.get("chunkId").asLong()).isEqualTo(-1_000_000L);
            assertThat(record.get("n").asInt()).isEqualTo(1);
        }
        // 回源锚（`CITES`）也要在：需求 :469「(:Entity)-[r:CITES]->(:Chunk)」
        try (Session session = driver.session()) {
            int cites = session.run("""
                    MATCH ()-[r:CITES]->() WHERE r.docId = $docId RETURN count(r) AS n
                    """, Map.of("docId", docId)).single().get("n").asInt();
            assertThat(cites).as("主客体各一条 CITES ⇒ 2 条").isEqualTo(2);
        }
    }

    // ==================================================================
    // 判据 6 · 定位不到 ⇒ 丢弃并留痕（负向锚）
    // ==================================================================

    @Test
    @DisplayName("判据 6：块序在映射里查不到 ⇒ 那条边**不写**（不硬凑）")
    void unanchoredRelationIsDroppedNotGuessed() {
        long docId = nextDocId();
        // 两条关系，但映射里只有块序 1 —— 块序 9 那条必须被丢掉
        graphWriter.replaceDocument(docId, "探针文档",
                anchored(List.of(
                        rel("探针实体-甲", "基于", "探针实体-乙", 0.9),
                        rel("探针实体-丙", "依赖", "探针实体-丁", 0.9)), 1, 9),
                orderToChunkId(1));

        List<Map<String, Object>> after = graphWriter.relationsOf(docId);
        assertThat(after).as("只该写入锚得上的那一条").hasSize(1);
        assertThat(after.getFirst().get("subject")).isEqualTo("探针实体-甲");
    }

    // ==================================================================
    // 判据 7 · confidence 是带过来的，不是常量
    // ==================================================================

    @Test
    @DisplayName("判据 7：confidence 原样落到图上（写死常量会被这条逮到）")
    void confidenceIsCarriedThrough() {
        long docId = nextDocId();
        graphWriter.replaceDocument(docId, "探针文档",
                anchored(List.of(
                        rel("探针实体-甲", "基于", "探针实体-乙", 0.3),
                        rel("探针实体-丙", "依赖", "探针实体-丁", 0.95)), 1, 2),
                orderToChunkId(1, 2));

        List<Map<String, Object>> after = graphWriter.relationsOf(docId);
        assertThat(after).hasSize(2);
        // 按 predicate 排序（`relationsOf` 的 ORDER BY）⇒ 依赖 在前、基于 在后
        assertThat(after.get(0).get("confidence")).isEqualTo(0.95);
        assertThat(after.get(1).get("confidence")).isEqualTo(0.3);
    }

    // ==================================================================
    // 判据 8 · GraphSchema 幂等
    // ==================================================================

    @Test
    @DisplayName("判据 8：连续建两次 schema 都不抛，约束数不翻倍")
    void ensureIsIdempotent() {
        int first = graphSchema.ensureSchema();
        int second = graphSchema.ensureSchema();

        assertThat(first).isEqualTo(3);
        assertThat(second).as("`IF NOT EXISTS` 让它变成 no-op，但调用仍然成功").isEqualTo(3);

        try (Session session = driver.session()) {
            int count = session.run("""
                    SHOW CONSTRAINTS YIELD name
                    WHERE name IN ['entity_name_unique', 'document_doc_id_unique', 'chunk_chunk_id_unique']
                    RETURN count(*) AS n
                    """).single().get("n").asInt();
            assertThat(count).as("同名约束只该有一条").isEqualTo(3);
        }
    }

    // ==================================================================
    // 判据 9 · 清理面
    // ==================================================================

    @Test
    @DisplayName("判据 9：deleteDocument ⇒ 该文档的边与 CITES 全清（Document 节点也走）")
    void deleteDocumentRemovesEverythingOfThatDoc() {
        long docId = nextDocId();
        graphWriter.replaceDocument(docId, "探针文档",
                anchored(List.of(rel("探针实体-甲", "基于", "探针实体-乙", 0.9)), 1),
                orderToChunkId(1));
        assertThat(graphWriter.relationsOf(docId)).hasSize(1);

        assertThat(graphWriter.deleteDocument(docId, "用例")).isTrue();

        assertThat(graphWriter.relationsOf(docId)).isEmpty();
        assertThat(graphWriter.relationCountOf(docId)).isZero();
        try (Session session = driver.session()) {
            int cites = session.run("""
                    MATCH ()-[r:CITES]->() WHERE r.docId = $docId RETURN count(r) AS n
                    """, Map.of("docId", docId)).single().get("n").asInt();
            assertThat(cites).isZero();
            int docs = session.run("""
                    MATCH (d:Document {docId: $docId}) RETURN count(d) AS n
                    """, Map.of("docId", docId)).single().get("n").asInt();
            assertThat(docs).isZero();
        }
    }

    @Test
    @DisplayName("判据 9 的库级半边：deleteDocuments 一次清一批，**不动别的文档**")
    void deleteDocumentsIsScopedToOneBatch() {
        long keep = nextDocId();
        long goneA = nextDocId();
        long goneB = nextDocId();

        for (long id : List.of(keep, goneA, goneB)) {
            graphWriter.replaceDocument(id, "探针文档",
                    anchored(List.of(rel("探针实体-甲", "基于", "探针实体-乙", 0.9)), 1),
                    orderToChunkId(1));
        }

        graphWriter.deleteDocuments(List.of(goneA, goneB), -1L);

        assertThat(graphWriter.relationsOf(goneA)).isEmpty();
        assertThat(graphWriter.relationsOf(goneB)).isEmpty();
        assertThat(graphWriter.relationsOf(keep))
                .as("**不该误伤别的文档** —— 库级删除是按 docIds 白名单做的").hasSize(1);
    }

    @Test
    @DisplayName("S9-M12-B 判据 10：CITES 指向 (:Chunk) 之后，删除面必须把它一起清掉")
    void deleteRemovesChunkNodesToo() {
        long docId = nextDocId();
        long neighbourDocId = nextDocId();
        long chunkId = -9_100_001L;
        long neighbourChunkId = -9_100_002L;

        graphWriter.replaceDocument(docId, "探针文档",
                anchored(List.of(rel("探针实体-甲", "基于", "探针实体-乙", 0.9)), 1),
                Map.of(1, chunkId));
        graphWriter.replaceDocument(neighbourDocId, "探针文档",
                anchored(List.of(rel("探针实体-甲", "基于", "探针实体-乙", 0.9)), 1),
                Map.of(1, neighbourChunkId));

        assertThat(chunkNodeExists(chunkId)).as("前提：CITES 真的指向了 (:Chunk)").isTrue();
        assertThat(chunkNodeExists(neighbourChunkId)).isTrue();

        assertThat(graphWriter.deleteDocument(docId, "用例")).isTrue();

        assertThat(chunkNodeExists(chunkId))
                .as("删文档不清 (:Chunk) ⇒ 这就是本批新开的一个泄漏面").isFalse();
        assertThat(chunkNodeExists(neighbourChunkId))
                .as("别人的分片节点不许被顺手清掉").isTrue();
        assertThat(graphWriter.relationsOf(docId)).isEmpty();
        try (Session session = driver.session()) {
            int cites = session.run("""
                    MATCH ()-[r:CITES]->() WHERE r.docId = $docId RETURN count(r) AS n
                    """, Map.of("docId", docId)).single().get("n").asInt();
            assertThat(cites).isZero();
            int entities = session.run("""
                    MATCH (e:Entity) WHERE e.name STARTS WITH '探针实体-' RETURN count(e) AS n
                    """).single().get("n").asInt();
            assertThat(entities).as("实体是跨文档共享的，不该被删").isPositive();
        }
    }

    private boolean chunkNodeExists(long chunkId) {
        try (Session session = driver.session()) {
            return session.run("MATCH (c:Chunk {chunkId: $chunkId}) RETURN count(c) AS n",
                    Map.of("chunkId", chunkId)).single().get("n").asInt() > 0;
        }
    }
}
