package com.knowledge.ai.pipeline.graph;

import com.knowledge.ai.pipeline.chat.ChatDtos;
import com.knowledge.ai.pipeline.chat.ChatWireMapper;
import com.knowledge.ai.pipeline.retrieve.RetrievalFlowSupport;
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

/**
 * `S9-M12-B` 判据 1：**图谱检索开关关闭时，链路与未开启完全一致**（`G9-1` 的检索侧半边）。
 *
 * <h3>这条判据的锚是怎么下的</h3>
 * <p>"零开销"只断言 trace 全 0 是不够的 —— 那对一个"照样跑了、只是把结果丢掉了"的实现
 * 也会绿。所以这里让图谱锚点指向一篇**没有向量**的文档：
 * 向量/BM25 两路**不可能**召回它 ⇒ 它出现在结果里**当且仅当**图谱旁路真的跑了。
 * 连带的三格（`graphUsed` / `graphCostMs` / `graphFallback`）一起钉"没走过"这件事。
 *
 * <p>另一半（**一个驱动都不取**）在 {@code GraphEntityLinkerTest#disabledNeverTouchesTheDriver}：
 * 那边用记数替身直接数调用次数。
 */
@TestPropertySource(properties = {
        "m05.graph.retrieve.enabled=false",
        "m05.graph.enabled=false"})
class GraphRetrieveDisabledTest extends RetrievalFlowSupport {

    private static final String ENTITY = "图谱探针-关掉也不该跑";
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
            // 清不掉不该让用例失败
        }
        cleanProbeData();
    }

    @Test
    @DisplayName("判据1 · 关闭 ⇒ 图谱锚点一条不回来，三格全为「没走过」")
    void disabledMeansNoCallAndNoField() {
        kbId = insertKb("graph-off", "INTERNAL", KB_CONFIG);
        long searchable = insertDocWithChunks("图谱关闭-可检索", "值班安排与告警阈值的正文");
        // ⚠️ 这一篇**不写向量**：它只能靠图谱被召回 —— 于是"它在不在结果里"就是
        //    "图谱跑没跑"的探针（比只看 trace 更强）
        long graphOnly = insertDocWithChunks("图谱关闭-仅图谱可达", "只有图谱能到这篇文档");
        long graphOnlyChunk = chunkIdsOf(graphOnly).get(0);
        seedVectors(searchable, 1);
        awaitVectorsVisible("告警阈值", 1);
        try (Session session = driver.session()) {
            session.run("""
                    MERGE (s:Entity {name: $subject})
                    MERGE (o:Entity {name: '图谱探针-对端'})
                    MERGE (s)-[r:REL {relation: '基于', sourceChunkId: $chunkId}]->(o)
                    SET r.confidence = 0.9, r.docId = -1
                    """, Map.of("subject", ENTITY, "chunkId", graphOnlyChunk)).consume();
        }

        RetrievalResult result = retrievalService.search(reader(),
                "图谱探针-关掉也不该跑的时候一致性与容量规划怎么算", List.of(kbId));
        ChatDtos.RetrievalEvent packet = wireMapper.firstPacket(result, Map.of());

        assertThat(result.chunks()).extracting(RetrievedChunk::docId)
                .as("关掉开关还能被图谱召回 ⇒ 这条会红")
                .doesNotContain(graphOnly);
        assertThat(result.chunks()).as("主链路照常").isNotEmpty();

        assertThat(result.trace().graphUsed()).isFalse();
        assertThat(result.trace().graphMillis()).as("0 = 没跑").isZero();
        assertThat(result.trace().graphFallback()).isFalse();
        assertThat(result.trace().graphEntities()).isNull();

        assertThat(packet.trace().graphUsed()).isFalse();
        assertThat(packet.trace().graphCostMs()).as("没走过就**不发**（不是发 0）").isNull();
        assertThat(packet.trace().graphFallback()).as("没走过 ≠ 降级").isNull();
    }

    private List<Long> chunkIdsOf(long docId) {
        return jdbc.queryForList("select id from sys_chunk where doc_id = ? order by order_no", Long.class, docId);
    }
}