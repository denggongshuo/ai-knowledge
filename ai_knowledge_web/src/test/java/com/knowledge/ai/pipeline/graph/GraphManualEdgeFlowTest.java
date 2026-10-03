package com.knowledge.ai.pipeline.graph;

import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.pipeline.Block;
import com.knowledge.ai.pipeline.enhance.IndexEnhancer;
import com.knowledge.ai.pipeline.retrieve.RetrievalFlowSupport;
import com.knowledge.ai.pipeline.retrieve.RetrievalResult;
import com.knowledge.ai.pipeline.retrieve.RetrievalService;
import com.knowledge.ai.pipeline.retrieve.RetrievedChunk;
import com.knowledge.ai.service.GraphEdgeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * `T-C04` 判据 1~8：人工补边（需求 `07:544` · `03:102`）。
 *
 * <h3>为什么这些判据全都问图库、不问接口返回值</h3>
 * <p>补边接口返回 `{applied:true, detail:"…"}` —— 而 `applied` 只说明
 * {@code GraphWriter} 的那个事务没抛异常。**它不证明边真的在库里**，
 * 更不证明它**能被检索侧跳出来**（那是两回事，`S9-M12-B` 栽过同形的跟头）。
 * ⇒ 每条判据都去 `relationsOf` / `relationCountOf` 或真检索结果里取事实。
 *
 * <h3>🔴 判据 8 是本类存在的另一半理由</h3>
 * <p>人工边与自动边在 Neo4j 里**同形**（都是 `REL` + `docId`），唯一的区分是
 * {@code r.manual}。没有它，文档**任何一次重建**都会把人工边连同自动边一起删掉
 * （`GraphWriter#replaceDocument` 的删旧边那一步），而现象只是"补过的边过几天没了"。
 *
 * <h3>⚠️ 收尾必须还原开关</h3>
 * <p>本类显式把开关写成 `true`（事实源是 `sys_setting`，不受测试配置影响），
 * 收尾再断言它确实是 `true` —— 同一个库上的其它用例都靠这一行。
 *
 * <p>⚠️ 另外要 `@TestPropertySource` 打开 {@code m05.graph.enabled}：那是**构建侧**的总开关
 * （`GraphWriter` 构造期读它），而 `src/test/resources/application.properties` 默认把它关着
 * （跑批不该依赖本机 Neo4j）。不打开的话 `addManualEdge` 会直接返回 `skipped`
 * —— 而本类所有写判据都会以"边是 0 条"的形态红，看起来像 Cypher 写错。
 */
@TestPropertySource(properties = {
        "m05.graph.enabled=true"})
class GraphManualEdgeFlowTest extends RetrievalFlowSupport {

    private static final String KB_CONFIG =
            "{\"mode\":\"HYBRID\",\"recallThreshold\":0.15,\"rerankTopK\":3,"
                    + "\"rerankEnabled\":true,\"tableWeight\":1.2,\"identifierProtect\":true}";

    /** 实体名统一带前缀：收尾按前缀清图（图库是第四种存储，父类不认识它）。 */
    private static final String SUBJECT = "图谱补边探针-订单系统";
    private static final String OBJECT = "图谱补边探针-一致性哈希";

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private GraphEdgeService graphEdgeService;

    @Autowired
    private GraphWriter graphWriter;

    @Autowired
    private Driver driver;

    /** 未命中行的 id（判据要它 —— 补边是"针对一条未命中问题"的动作）。 */
    private long missId;

    /** 进本类之前开关的值 —— 收尾还原它（**不要硬编码 true**：产品默认已改为关，硬编码会把库改回开）。 */
    private String graphSwitchBefore;

    /**
     * 本轮在 Neo4j 里碰过的 `docId`（收尾按它删边与 `(:Document)` 节点）。
     *
     * <p>⚠️ 每个建了文档的用例都要往里加 —— 漏一次就攒下一批节点，
     * 而"按前缀删实体"是**碰不到 `(:Document)` 的**（它没有 name 属性，只有 docId）。
     */
    private final List<Long> touchedDocIds = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        graphSwitchBefore = jdbc.queryForObject(
                "select setting_value from sys_setting where setting_key = 'retrieval.graph-bypass-enabled'", String.class);
        // 本类要测「人工补边后能召回」⇒ 必须打开（不依赖别的类干不干净）
        jdbc.update("update sys_setting set setting_value = 'true' where setting_key = 'retrieval.graph-bypass-enabled'");
        LoginUserContext.set(reader());
        resetProbeState();
        // 一条真实存在的未命中行（`sys_qa_miss` 是聚合表，手工插一行最直接 —— 它没有 trace 关联）
        // ⚠️ `kb_ids` 是 **NOT NULL**（默认空串）：显式传 `null` 会覆盖掉默认值 ⇒ 违反约束。
        //    跨库那一路才填（「A、B、C」形态），这一行刻意不绑库。
        jdbc.update("""
                insert into sys_qa_miss (miss_key, question, ask_count, first_ask_at, last_ask_at,
                                         last_user_id, max_top_score, kb_ids, status, updated_at)
                values (?, ?, 1, now(), now(), 1, 0.10, '', 'PENDING', now())
                """, "图谱补边探针-" + System.nanoTime(), "图谱补边探针-订单系统用的是什么方案");
        missId = jdbc.queryForObject("select max(id) from sys_qa_miss", Long.class);
    }

    @AfterEach
    void cleanUp() {
        try (Session session = driver.session()) {
            // ⚠️ ① 边**必须按 docId 删**（探针实体名带前缀，但边挂在文档上）
            if (!touchedDocIds.isEmpty()) {
                session.run("MATCH ()-[r:REL]-() WHERE r.docId IN $ids DELETE r",
                        Map.of("ids", List.copyOf(touchedDocIds))).consume();
                session.run("MATCH ()-[r:CITES]-() WHERE r.docId IN $ids DELETE r",
                        Map.of("ids", List.copyOf(touchedDocIds))).consume();
            }
            // ⚠️ ② 实体按前缀删
            session.run("MATCH (e:Entity) WHERE e.name STARTS WITH '图谱补边探针-'"
                            + " OR e.name IN ['迁移步骤', '回滚预案', '订单系统', '方案'] DETACH DELETE e")
                    .consume();
            // 🔴 ③ **`Document` 节点：本批踩到的漏**
            //    `GraphWriter#replaceDocument` / `addManualEdge` 都会 `MERGE (d:Document {docId})`，
            //    而按前缀删实体**碰不到它** ⇒ 一轮跑批攒下 30+ 个 `(:Document)` 节点。
            //    `S9-M12-B` 已经栽过一次同形的（`GraphIngestFlowTest` 漏清 `(:Chunk)`）
            //    —— "新增一类节点/存储，回头必须把用例侧收尾一起改"。
            session.run("MATCH (d:Document) WHERE d.docId IN $ids DELETE d",
                    Map.of("ids", touchedDocIds.isEmpty() ? List.of(-1L) : List.copyOf(touchedDocIds)))
                    .consume();
            // ④ 无主的 `(:Chunk)` 锚（CITES 删完之后就没人引用了）
            session.run("MATCH (c:Chunk) WHERE NOT (()-[:CITES]->(c)) DELETE c").consume();
        } catch (Exception ignored) {
            // 清不掉不该让用例失败：失败信息已经由断言给出
        }
        if (missId > 0) {
            jdbc.update("delete from sys_qa_miss where id = ?", missId);
        }
        cleanProbeData();
        LoginUserContext.clear();
        // 还原为**进入本类前的值**（不要再硬编码 true：产品默认已改为关）
        jdbc.update("update sys_setting set setting_value = ? where setting_key = 'retrieval.graph-bypass-enabled'", graphSwitchBefore);
        assertThat(jdbc.queryForObject(
                "select setting_value from sys_setting where setting_key = 'retrieval.graph-bypass-enabled'",
                String.class))
                .as("开关没还原成进入本类前的值 ⇒ 会污染后面的批次")
                .isEqualTo(graphSwitchBefore);
    }

    // ==================================================================
    // 判据 1：补边真的落库，且 confidence = 1、锚点是挑的那个分片
    // ==================================================================

    @Test
    @DisplayName("判据1 · 补边落库：confidence = 1.0、sourceChunkId 就是挑的那个分片")
    void manualEdgeLandsWithConfidenceOne() {
        kbId = insertKb("graph-manual", "INTERNAL", KB_CONFIG);
        long docId = insertDocWithChunks("图谱补边-源文档",
                "订单系统的一致性哈希方案", "迁移步骤与回滚预案");
        touchedDocIds.add(docId);
        long chunkId = chunkIdsOf(docId).get(0);

        graphEdgeService.addEdge(missId, SUBJECT, "基于", OBJECT, docId, chunkId);

        List<Map<String, Object>> edges = graphWriter.relationsOf(docId);
        assertThat(edges).as("人工边没落库 ⇒ 接口回 applied=true 也是假的").hasSize(1);
        assertThat(edges.get(0))
                .containsEntry("subject", SUBJECT)
                .containsEntry("predicate", "基于")
                .containsEntry("object", OBJECT)
                .containsEntry("confidence", 1.0d)
                .containsEntry("sourceChunkId", chunkId);
    }

    // ==================================================================
    // 判据 2：补完能被检索侧真的跳出来（需求 07:544 的闭环）
    // ==================================================================

    @Test
    @DisplayName("判据2 · 补边后，图谱这一路真的把它带进结果（source = graph）")
    void manualEdgeIsRetrievable() {
        kbId = insertKb("graph-manual", "INTERNAL", KB_CONFIG);
        // ⚠️ 必须**另有一篇带向量的文档**：`RetrievalService` 在
        //    `scope.empty()`（没有可见分区）时**直接返回空结果、根本不发起图谱** ——
        //    而分区是靠写向量建出来的。只有 graphOnly 那一篇的话，本判据会以
        //    "结果为空"的形态红，看起来像图谱没接上，其实是范围为空。
        long searchable = insertDocWithChunks("图谱补边-可检索", "值班安排与告警阈值的正文");
        touchedDocIds.add(searchable);
        seedVectors(searchable, 1);
        awaitVectorsVisible("告警阈值", 1);

        // ⚠️ 这一篇**不写向量**：它只能靠图谱被召回 ⇒ 它的出现当且仅当图谱真的跑了
        //    （与 GraphRetrieveDisabledTest 同一种锚法，比只看 trace 强）
        long graphOnly = insertDocWithChunks("图谱补边-仅图谱可达",
                "订单系统的一致性哈希方案是怎么定的");
        touchedDocIds.add(graphOnly);
        long anchor = chunkIdsOf(graphOnly).get(0);

        // 补边**之前**：召回不到它（向量/BM25 两路都没有它）
        RetrievalResult before = retrievalService.search(reader(),
                "图谱补边探针-订单系统用的是什么方案", List.of(kbId));
        assertThat(before.chunks()).extracting(RetrievedChunk::docId)
                .as("补边之前它就在结果里 ⇒ 后面那条断言证明不了任何事").doesNotContain(graphOnly);

        graphEdgeService.addEdge(missId, SUBJECT, "基于", OBJECT, graphOnly, anchor);

        // 🔴 分两段断言，别一步到位 —— "边写了没"与"边跳得出来吗"是两件事：
        //    合成一条的话，一旦红了分不清是写侧还是检索侧（`S9-M12-B` 栽过同形的跟头）。
        assertThat(graphWriter.relationsOf(graphOnly))
                .as("边没落库 ⇒ 后面的检索断言证明不了任何事")
                .hasSize(1);
        assertThat(graphWriter.relationCountOf(graphOnly)).isEqualTo(1);

        RetrievalResult after = retrievalService.search(reader(),
                "图谱补边探针-订单系统用的是什么方案", List.of(kbId));
        assertThat(after.chunks()).extracting(RetrievedChunk::docId)
                .as("补了边还是召回不到 ⇒ 这条边在图里但不可达").contains(graphOnly);
        assertThat(after.chunks())
                .filteredOn(c -> c.docId() == graphOnly)
                .extracting(RetrievedChunk::source)
                .containsOnly(com.knowledge.ai.core.enums.RetrievalSource.GRAPH);
    }

    // ==================================================================
    // 判据 3：悬空锚点被挡在入口（GraphRetriever 对它是静默 continue）
    // ==================================================================

    @Test
    @DisplayName("判据3 · chunkId 不属于该文档 ⇒ 拒绝，且图里零新增")
    void foreignChunkIsRejected() {
        kbId = insertKb("graph-manual", "INTERNAL", KB_CONFIG);
        long docA = insertDocWithChunks("图谱补边-文档A", "文档 A 的正文");
        touchedDocIds.add(docA);
        long docB = insertDocWithChunks("图谱补边-文档B", "文档 B 的正文");
        touchedDocIds.add(docB);
        long chunkOfB = chunkIdsOf(docB).get(0);

        assertThatThrownBy(() -> graphEdgeService.addEdge(missId, SUBJECT, "基于", OBJECT, docA, chunkOfB))
                .hasMessageContaining("不属于文档");

        assertThat(graphWriter.relationCountOf(docA)).as("被拒了却写进去了").isZero();
        assertThat(graphWriter.relationCountOf(docB)).isZero();
    }

    // ==================================================================
    // 判据 4：动词闭集（需求 07:468）
    // ==================================================================

    @Test
    @DisplayName("判据4 · 8 个固定动词全部可补；闭集之外的动词被拒且零副作用")
    void predicateIsAClosedSet() {
        kbId = insertKb("graph-manual", "INTERNAL", KB_CONFIG);
        long docId = insertDocWithChunks("图谱补边-源文档", "订单系统的一致性哈希方案");
        touchedDocIds.add(docId);
        long chunkId = chunkIdsOf(docId).get(0);

        // 闭集之外的动词：拒（且把可选值列在报错里 —— 下一个人不必翻需求文档）
        assertThatThrownBy(() -> graphEdgeService.addEdge(missId, SUBJECT, "喜欢", OBJECT, docId, chunkId))
                .hasMessageContaining("关系动词仅支持");
        assertThat(graphWriter.relationCountOf(docId)).isZero();

        // 8 个合法动词：一个不少地能补（每个换一对实体名，免得被 MERGE 合并成一条）
        int i = 0;
        for (String predicate : GraphEdgeService.allowedPredicates()) {
            graphEdgeService.addEdge(missId, "图谱补边探针-主体" + i, predicate,
                    "图谱补边探针-客体" + i, docId, chunkId);
            i++;
        }
        assertThat(graphWriter.relationCountOf(docId))
                .as("8 个固定动词应各留一条边")
                .isEqualTo(GraphEdgeService.allowedPredicates().size());
    }

    // ==================================================================
    // 判据 5：越权 —— 不可见的文档不能被锚
    // ==================================================================

    @Test
    @DisplayName("判据5 · 文档不可见 ⇒ 拒绝，图中零新增")
    void invisibleDocumentIsRejected() {
        kbId = insertKb("graph-manual", "INTERNAL", KB_CONFIG);
        long docId = insertDocWithChunks("图谱补边-源文档", "订单系统的一致性哈希方案");
        touchedDocIds.add(docId);
        long chunkId = chunkIdsOf(docId).get(0);

        // 软删该文档 ⇒ requireVisibleDoc 报「文档不存在」（10044）
        jdbc.update("update sys_document set deleted_at = now() where id = ?", docId);

        assertThatThrownBy(() -> graphEdgeService.addEdge(missId, SUBJECT, "基于", OBJECT, docId, chunkId))
                .hasMessageContaining("文档不存在");
        assertThat(graphWriter.relationCountOf(docId)).isZero();
    }

    // ==================================================================
    // 判据 6：非 READY 的文档不能被锚（没有分片可锚）
    // ==================================================================

    @Test
    @DisplayName("判据6 · 文档没解析成功 ⇒ 拒绝（它没有分片，锚什么都是悬空的）")
    void notReadyDocumentIsRejected() {
        kbId = insertKb("graph-manual", "INTERNAL", KB_CONFIG);
        long docId = insertDocWithChunks("图谱补边-源文档", "订单系统的一致性哈希方案");
        touchedDocIds.add(docId);
        long chunkId = chunkIdsOf(docId).get(0);

        jdbc.update("update sys_document set status = 'UPLOADED' where id = ?", docId);

        assertThatThrownBy(() -> graphEdgeService.addEdge(missId, SUBJECT, "基于", OBJECT, docId, chunkId))
                .hasMessageContaining("尚未解析成功");
        assertThat(graphWriter.relationCountOf(docId)).isZero();
    }

    // ==================================================================
    // 判据 7：幂等 —— 同一条边补两次只有一条
    // ==================================================================

    @Test
    @DisplayName("判据7 · 同一条边（同动词 + 同文档 + 同实体对）补两次只留一条")
    void sameEdgeTwiceStaysOne() {
        kbId = insertKb("graph-manual", "INTERNAL", KB_CONFIG);
        long docId = insertDocWithChunks("图谱补边-源文档", "订单系统的一致性哈希方案");
        touchedDocIds.add(docId);
        long chunkId = chunkIdsOf(docId).get(0);

        graphEdgeService.addEdge(missId, SUBJECT, "基于", OBJECT, docId, chunkId);
        graphEdgeService.addEdge(missId, SUBJECT, "基于", OBJECT, docId, chunkId);

        assertThat(graphWriter.relationCountOf(docId))
                .as("MERGE 键没生效 ⇒ 点两次补边会造出两条一模一样的边")
                .isEqualTo(1);
    }

    // ==================================================================
    // 判据 8（本类最要紧的一条）：人工边抗重建
    // ==================================================================

    @Test
    @DisplayName("判据8 · 文档重建后：人工边仍在，自动边全换，且边不翻倍")
    void manualEdgeSurvivesRebuild() {
        kbId = insertKb("graph-manual", "INTERNAL", KB_CONFIG);
        long docId = insertDocWithChunks("图谱补边-源文档",
                "订单系统的一致性哈希方案", "迁移步骤与回滚预案");
        touchedDocIds.add(docId);
        List<Long> chunks = chunkIdsOf(docId);
        long manualAnchor = chunks.get(0);

        // ① 先补一条人工边
        graphEdgeService.addEdge(missId, SUBJECT, "基于", OBJECT, docId, manualAnchor);
        assertThat(graphWriter.relationCountOf(docId)).isEqualTo(1);

        // ①.5 🔴 **先造一条"上一版的自动边"** —— 这一步是判据 8 的另一半，别省。
        //
        //   只有"人造边 + 自动边"同时在场，重建才真的在考"谁被删了"。
        //   第一版没造它，于是**删旧边那条条件写成 `r.manual = false`（漏掉 `IS NULL`）
        //   也照样绿** —— 因为那时库里一条"该被删的旧边"都没有，删不删都看不出来。
        //   实测（2026-09-23，证伪 B）：漏 `IS NULL` ⇒ 本判据 **BUILD SUCCESS**，
        //   而它在真实数据上会让每一篇重建过的文档**多一批重复的旧边**。
        //   ⇒ 补这一步之后，漏 `IS NULL` 会留下旧边，下面的 `hasSize(2)` 当场红。
        List<Block> blocks = blocksOf(docId);
        Map<Integer, Long> chunkIdByOrder = new java.util.HashMap<>();
        for (Block b : blocks) {
            chunkIdByOrder.put(b.orderNo(), anchorForOrder(chunks, blocks, b.orderNo()));
        }
        graphWriter.replaceDocument(docId, "图谱补边-源文档", List.of(), chunkIdByOrder);
        // 「订单系统」逐字出现在第 1 个分片里 ⇒ 锚得到；这条就是"上一版的自动边"
        graphWriter.replaceDocument(docId, "图谱补边-源文档",
                RelationAnchor.anchor(List.of(new IndexEnhancer.Enhancement.Relation(
                        "订单系统", "包含", "方案", 0.7d)), blocks), chunkIdByOrder);
        assertThat(graphWriter.relationCountOf(docId))
                .as("前置没造出来（旧自动边不在）⇒ 下面的判据会变成空断言")
                .isEqualTo(2);

        // ② 再把该文档"重建"一次 —— 这次只给一条**新的自动**关系（模拟重新解析的产物）。
        //    🔴 自动主体/客体必须**逐字出现在某一分片的正文里**：`RelationAnchor` 找不到落点就把
        //    这条关系**丢掉**（与 `S9-M12-C` 立的"实体名逐字照抄"同一条规则）。
        //    第一版把它们写成 `图谱补边探针-自动主体`，正文里没有 ⇒ 被丢掉 ⇒ 判据红在
        //    "自动边不在"，看着像重建逻辑坏了，其实是锚定把上游吞了。
        var relations = List.of(new IndexEnhancer.Enhancement.Relation(
                "迁移步骤", "包含", "回滚预案", 0.8d));
        var anchored = RelationAnchor.anchor(relations, blocks);
        graphWriter.replaceDocument(docId, "图谱补边-源文档", anchored, chunkIdByOrder);

        // ③ 人工边必须还在（这是本批修的那条）
        List<Map<String, Object>> edges = graphWriter.relationsOf(docId);
        assertThat(edges).extracting(e -> e.get("subject"))
                .as("重建把人工边删掉了 ⇒ 运营补的边会在下一次解析后静默消失")
                .contains(SUBJECT);
        // 自动边也必须换了（旧的"订单系统 包含 方案"没了、新的在）
        assertThat(edges).extracting(e -> e.get("subject"))
                .contains("迁移步骤")
                .doesNotContain("订单系统");
        // 且**不翻倍**：1 条人工 + 1 条新自动 = 2
        // 🔴 漏掉 `IS NULL`（把条件写成 `r.manual = false`）时，上面那条"旧自动边"删不掉 ⇒ 这里会是 3
        assertThat(edges).as("旧自动边漏删 ⇒ 重建过的文档会攒下重复的边").hasSize(2);
    }

    /** 第 {@code orderNo} 个块对应的分片 id（按块序在 chunks 里的下标取，越界退到最后一个）。 */
    private long anchorForOrder(List<Long> chunks, List<Block> blocks, int orderNo) {
        int idx = Math.max(0, blocks.indexOf(blocks.stream()
                .filter(b -> b.orderNo() == orderNo).findFirst().orElse(blocks.get(0))));
        return chunks.get(Math.min(idx, chunks.size() - 1));
    }

    /** 该文档的分片内容，按 `order_no` 还原成 `Block`（只填锚定用得到的两个字段）。 */
    private List<Block> blocksOf(long docId) {
        // ⚠️ 参数必须走**变参重载** `query(sql, rowMapper, args...)`；
        // 写成 `query(sql, rowMapper)` 时 `?` 没人绑定 ⇒ 报 "bad SQL grammar"
        //（看着像 SQL 写错，其实是参数没传）。
        return jdbc.query("select raw_content, order_no from sys_chunk where doc_id = ? order by order_no",
                (rs, i) -> new Block(rs.getString(1),
                        com.knowledge.ai.core.enums.BlockType.PARAGRAPH, "", rs.getInt(2), 1, null, null, null),
                docId);
    }

    private List<Long> chunkIdsOf(long docId) {
        return jdbc.queryForList("select id from sys_chunk where doc_id = ? order by order_no",
                Long.class, docId);
    }
}
