package com.knowledge.ai.pipeline.graph;

import com.knowledge.ai.pipeline.chat.ChatDtos;
import com.knowledge.ai.pipeline.chat.ChatWireMapper;
import com.knowledge.ai.pipeline.retrieve.RetrievalFlowSupport;
import com.knowledge.ai.pipeline.retrieve.RetrievalResult;
import com.knowledge.ai.pipeline.retrieve.RetrievalService;
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
 * `S9-M12-B` 判据 7：**超时 200ms 静默降级**（需求 `07:522`）。
 *
 * <h3>怎么把超时做成确定性的</h3>
 * <p>把预算设成 **1ms**（`m05.graph.retrieve.timeout-ms=1`）：旁路至少要跑两次 Neo4j 往返，
 * 物理上不可能压进 1ms ⇒ 必然命中 `orTimeout` 那条分支。用"真库真的慢"来制造超时是不行的
 * （那是随机红），用 1ms 是把**分支**钉死。
 *
 * <p>断言三件事：① 降级标记为真；② **不抛**（主链路一条不少）；③ 同一格下发到 SSE。
 */
@TestPropertySource(properties = {
        "m05.graph.retrieve.enabled=true",
        "m05.graph.retrieve.timeout-ms=1",
        "m05.graph.enabled=false"})
class GraphRetrieveTimeoutTest extends RetrievalFlowSupport {

    private static final String ENTITY = "图谱探针-超时";
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
    @DisplayName("判据7 · 超时 ⇒ graphFallback=true、不报错、主链路一条不少")
    void timeoutDegradesSilently() {
        kbId = insertKb("graph-timeout", "INTERNAL", KB_CONFIG);
        long docId = insertDocWithChunks("图谱超时", "一致性哈希方案与容量规划");
        List<Long> chunkIds = jdbc.queryForList(
                "select id from sys_chunk where doc_id = ? order by order_no", Long.class, docId);
        seedVectors(docId, 1);
        awaitVectorsVisible("一致性哈希", 1);
        try (Session session = driver.session()) {
            session.run("""
                    MERGE (s:Entity {name: $subject})
                    MERGE (o:Entity {name: '图谱探针-对端'})
                    MERGE (s)-[r:REL {relation: '基于', sourceChunkId: $chunkId}]->(o)
                    SET r.confidence = 0.9, r.docId = -1
                    """, Map.of("subject", ENTITY, "chunkId", chunkIds.get(0))).consume();
        }

        // ⚠️ 这一行**必须不抛**：那是本判据的一半（"问答不受影响"）
        RetrievalResult result = retrievalService.search(reader(),
                "图谱探针-超时的时候一致性与容量规划是怎么定的", List.of(kbId));
        ChatDtos.RetrievalEvent packet = wireMapper.firstPacket(result, Map.of());

        assertThat(result.trace().graphFallback()).as("超时 ⇒ 降级标记为真").isTrue();
        assertThat(result.trace().graphUsed()).as("降级了就没贡献分片").isFalse();
        assertThat(result.chunks()).as("主链路一条不少").isNotEmpty();
        assertThat(result.chunks()).extracting(c -> c.chunkId())
                .as("被向量路召回的那条必须还在（超时不许影响主链路）")
                .contains(String.valueOf(chunkIds.get(0)));
        assertThat(packet.trace().graphFallback()).isTrue();
    }
}