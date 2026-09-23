package com.knowledge.ai.pipeline.graph;

import com.knowledge.ai.pipeline.chat.ChatDtos;
import com.knowledge.ai.pipeline.chat.ChatWireMapper;
import com.knowledge.ai.pipeline.retrieve.RetrievalFlowSupport;
import com.knowledge.ai.pipeline.retrieve.RetrievalHit;
import com.knowledge.ai.pipeline.retrieve.RetrievalResult;
import com.knowledge.ai.pipeline.retrieve.RetrievalService;
import com.knowledge.ai.pipeline.retrieve.RetrievedChunk;
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
import static org.assertj.core.data.Offset.offset;

/**
 * 图谱旁路**接进检索链路**之后的判据（`S9-M12-B` 判据 5/6/8/9 + 两个观察项）。
 *
 * <h3>为什么这一批判据必须落在 RetrievalService 上，而不是 GraphRetriever 自己的单测</h3>
 * <p>本批最值钱的两件事都发生在**接线处**：
 * <ol>
 *   <li>🔴 **可见性**：图谱不过 Milvus，所以"用户看不见的文档不会被召回"这条
 *       **没有任何下游兜底** —— 判据 5 用它当负向锚：拿掉那一步过滤，本条当场转红；</li>
 *   <li>**融合口径**：图谱是第三路进同一个 {@code RrfFusion}，按名次计分、不再单独加权
 *       （需求 `07:508`）—— 判据 6 断言的是**分数等于 RRF 公式**，而不是"图谱的条目在结果里"
 *       （后者对"单独加权"的实现照样绿 —— 上一批栽的正是这个形状）。</li>
 * </ol>
 *
 * <p>⚠️ 测试期默认把 {@code m05.graph.retrieve.enabled} 关着（见
 * {@code src/test/resources/application.properties}），本类单独打开它 —— 与
 * {@code GraphWriteFlowTest} 打开 {@code m05.graph.enabled} 同一种做法。
 */
@TestPropertySource(properties = {
        "m05.graph.retrieve.enabled=true",
        "m05.graph.enabled=true"})
class GraphRetrieveFlowTest extends RetrievalFlowSupport {

    /** 实体名统一带 `图谱探针-` 前缀：收尾按前缀清图（图库是第四种存储，父类不认识它）。 */
    private static final String ENTITY = "图谱探针-订单系统";
    private static final String PEER = "图谱探针-一致性哈希";
    private static final String HIDDEN_ENTITY = "图谱探针-机密";

    private static final String KB_CONFIG =
            "{\"mode\":\"HYBRID\",\"recallThreshold\":0.15,\"rerankTopK\":3,"
                    + "\"rerankEnabled\":true,\"tableWeight\":1.2,\"identifierProtect\":true}";

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private ChatWireMapper wireMapper;

    @Autowired
    private Driver driver;

    @BeforeEach
    void setUp() {
        resetProbeState();
    }

    @AfterEach
    void cleanGraph() {
        try (Session session = driver.session()) {
            session.run("MATCH (e:Entity) WHERE e.name STARTS WITH '图谱探针-' DETACH DELETE e").consume();
        } catch (Exception ignored) {
            // 清不掉不该让用例失败：失败信息已经由断言给出
        }
        cleanProbeData();
    }

    /** 造一条 `(:Entity)-[:REL]->(:Entity)`，把 `sourceChunkId` 锚到某个真分片上。 */
    private void seedGraph(String subject, String object, long sourceChunkId, double confidence) {
        try (Session session = driver.session()) {
            session.run("""
                    MERGE (s:Entity {name: $subject})
                    MERGE (o:Entity {name: $object})
                    MERGE (s)-[r:REL {relation: '基于', sourceChunkId: $chunkId}]->(o)
                    SET r.confidence = $confidence, r.docId = -1
                    """, Map.of("subject", subject, "object", object,
                    "chunkId", sourceChunkId, "confidence", confidence)).consume();
        }
    }

    private List<Long> chunkIdsOf(long docId) {
        return jdbc.queryForList("select id from sys_chunk where doc_id = ? order by order_no", Long.class, docId);
    }

    /** RRF：第 r 名的贡献是 `1/(60+r)`（见 {@code RrfFusion.DEFAULT_K}）。 */
    private static double rrf(int... ranks) {
        double sum = 0d;
        for (int rank : ranks) {
            sum += 1d / (60d + rank);
        }
        return sum;
    }

    @Test
    @DisplayName("判据6/8 · 双路命中的分片在融合池里只有一条，且分数 = RRF 公式（不是图谱额外加权）")
    void graphFusesByRankNotWeight() {
        kbId = insertKb("graph-fuse", "INTERNAL", KB_CONFIG);
        long docId = insertDocWithChunks("图谱融合",
                "图谱探针-订单系统的一致性哈希方案",
                "迁移步骤与回滚预案",
                "监控告警与值班安排");
        List<Long> chunkIds = chunkIdsOf(docId);
        seedVectors(docId, 1);
        awaitVectorsVisible("一致性哈希", 1);
        // 锚点挂在**向量路最相关**的那条上 ⇒ 同一分片两路都命中
        seedGraph(ENTITY, PEER, chunkIds.get(0), 0.9d);

        RetrievalResult result = retrievalService.search(reader(),
                "图谱探针-订单系统的一致性哈希方案是怎么定的", List.of(kbId));

        String anchored = String.valueOf(chunkIds.get(0));
        List<RetrievedChunk> same = result.chunks().stream()
                .filter(c -> anchored.equals(c.chunkId())).toList();
        assertThat(same).as("按 chunkId 去重 —— 两路命中只能占一个位置").hasSize(1);
        RetrievedChunk hit = same.get(0);
        assertThat(hit.channelRanks()).as("两路的名次都要记着（这是'双路命中'唯一的事实来源）")
                .containsKeys(RetrievalHit.Channel.VECTOR, RetrievalHit.Channel.GRAPH);

        // 🔴 本条的锚：**分数必须等于 RRF 公式**。若实现改成"图谱单独加权"，
        //    条目照样在结果里（只看"有没有"的断言会绿），但这里会红。
        assertThat(result.trace().rerankApplied()).as("本用例不许重排介入（换尺子）").isFalse();
        assertThat(hit.scoreKind()).isEqualTo(RetrievedChunk.ScoreKind.FUSION);
        // ⚠️ 求和要走**全部通道**（本用例里 BM25 也会命中同一条）：RRF 的口径是
        // "命中几路就加几次 1/(k+名次)"，只挑两路来加会得出一个"看起来像公式"的假期望值
        // —— 第一次就是这么红的（实际 3/61 vs 期望 2/61）。
        assertThat(hit.score())
                .isCloseTo(rrf(hit.channelRanks().values().stream()
                        .mapToInt(Integer::intValue).toArray()), offset(1e-9));

        // 判据 8 的"命中"那一格 + 观察项：耗时与实体名
        assertThat(result.trace().graphUsed()).isTrue();
        assertThat(result.trace().graphEntities()).contains(ENTITY);
        assertThat(result.trace().graphFallback()).isFalse();
    }

    @Test
    @DisplayName("判据5 · 🔴 可见性：软删文档的锚点一条都不许回来（负向锚）")
    void invisibleDocIsNotRecalled() {
        kbId = insertKb("graph-hidden", "INTERNAL", KB_CONFIG);
        long visibleDoc = insertDocWithChunks("图谱可见", "图谱探针-公开方案的正文");
        long hiddenDoc = insertDocWithChunks("图谱隐藏", "图谱探针-机密方案的正文");
        long hiddenChunk = chunkIdsOf(hiddenDoc).get(0);
        seedVectors(visibleDoc, 1);
        awaitVectorsVisible("公开方案", 1);
        // 软删 ⇒ 它不在 `Scope#docIds()` 里（与"库里每一篇都超密级"是同一类边界）
        jdbc.update("update sys_document set deleted_at = now() where id = ?", hiddenDoc);
        seedGraph(HIDDEN_ENTITY, PEER, hiddenChunk, 0.9d);

        RetrievalResult result = retrievalService.search(reader(),
                "图谱探针-机密方案与公开方案分别规定了什么", List.of(kbId));

        assertThat(result.trace().graphEntities())
                .as("前提：旁路确实链上了这个实体（否则这条用例什么都没验）")
                .contains(HIDDEN_ENTITY);
        assertThat(result.chunks()).extracting(RetrievedChunk::docId)
                .as("🔴 去掉 GraphRetriever 里的 scope 过滤，这里就会多出这一条")
                .doesNotContain(hiddenDoc);
        assertThat(result.trace().graphUsed()).as("被过滤掉 ⇒ 图谱没有贡献分片").isFalse();
    }

    @Test
    @DisplayName("判据8 · 没链上实体：图谱四列如实为「没命中」，主链路照常")
    void noEntityLinkedLeavesTraceClean() {
        kbId = insertKb("graph-none", "INTERNAL", KB_CONFIG);
        long docId = insertDocWithChunks("图谱无关", "这段正文讲的是值班安排与告警阈值");
        seedVectors(docId, 1);
        awaitVectorsVisible("告警阈值", 1);

        RetrievalResult result = retrievalService.search(reader(),
                "这段话里没有任何图谱实体但长度足够长", List.of(kbId));

        assertThat(result.trace().graphUsed()).isFalse();
        assertThat(result.trace().graphFallback()).as("没链上不是降级").isFalse();
        assertThat(result.trace().graphEntities()).as("空集写 NULL，不写空串").isNull();
        assertThat(result.chunks()).as("主链路一条不少").isNotEmpty();
    }

    @Test
    @DisplayName("判据9 · SSE 里 source=graph 真的下发（且 graphCostMs 非 null）")
    void wireCarriesGraphSource() {
        kbId = insertKb("graph-wire", "INTERNAL", KB_CONFIG);
        long docId = insertDocWithChunks("图谱来源",
                "图谱探针-订单系统的一致性哈希方案",
                "迁移步骤与回滚预案",
                "监控告警与值班安排");
        List<Long> chunkIds = chunkIdsOf(docId);
        seedVectors(docId, 1);
        awaitVectorsVisible("一致性哈希", 1);
        // 锚点挂在**向量路最不相关**的那条上 ⇒ 图谱名次（1）严格优于向量名次 ⇒ source 必须是 graph
        seedGraph(ENTITY, PEER, chunkIds.get(2), 0.9d);

        RetrievalResult result = retrievalService.search(reader(),
                "图谱探针-订单系统的一致性哈希方案是怎么定的", List.of(kbId));
        ChatDtos.RetrievalEvent packet = wireMapper.firstPacket(result, Map.of(docId, "图谱来源"));

        assertThat(result.trace().graphUsed()).isTrue();
        assertThat(packet.trace().graphUsed()).isTrue();
        assertThat(packet.trace().graphCostMs())
                .as("跑过了就**必须**下发耗时（判据 1 反过来要求没跑时下发 null）").isNotNull();
        ChatDtos.ChunkRef ref = packet.chunks().stream()
                .filter(r -> r.chunkId() == chunkIds.get(2))
                .findFirst()
                .orElseThrow(() -> new AssertionError("图谱锚定的那条没进 SSE 的 chunks[]"));
        assertThat(ref.source()).isEqualTo("graph");
    }
}