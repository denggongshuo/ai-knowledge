package com.knowledge.ai.pipeline.graph;

import com.knowledge.ai.support.DownloadTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 邻居查询（`T-C02` 的图侧）在**真 Neo4j** 上的判据（`S9-M12-B` 判据 4）。
 *
 * <h3>为什么必须真库</h3>
 * <p>"`confidence >= 0.6`、只走 1~2 跳、按路径分数降序"这三条**全部长在 Cypher 里**
 * —— `WHERE all(r IN rels WHERE …)`、`REL*1..2`、`reduce(… * …)`。
 * 替身只证明"我发了条语句"，而这几行的语义恰恰是容易写错的地方
 * （例如写成 `any(...)`：只要有**一条**边过线，整条路径就放行）。
 *
 * <p>⚠️ 实体名统一带 `探针实体-` 前缀，收尾按前缀清干净 —— 图库是**第四种存储**，
 * 父类的清理不认识它（`S7-M06-D` 的 `#130` 族）。
 */
@TestPropertySource(properties = {
        // ⚠️ 测试期默认**关着**（见 src/test/resources/application.properties 的理由），
        // 本类要的正是"开着时的真库行为"，所以单独打开 —— 与 GraphWriteFlowTest 同一种做法
        "m05.graph.retrieve.enabled=true",
        "m05.graph.enabled=true"})
class GraphNeighborReaderTest extends DownloadTestSupport {

    private static final String START = "探针实体-起点";

    @Autowired
    private Driver driver;

    @Autowired
    private GraphNeighborReader neighborReader;

    @AfterEach
    void cleanProbeNodes() {
        try (Session session = driver.session()) {
            session.run("MATCH (e:Entity) WHERE e.name STARTS WITH '探针实体-' DETACH DELETE e").consume();
        } catch (Exception ignored) {
            // 清不掉不该让用例失败：失败信息已经由断言给出
        }
    }

    /** 造一张固定的四层图：见 {@link #confidenceThresholdFilters} 的注释。 */
    private void seedGraph() {
        try (Session session = driver.session()) {
            session.run("""
                    CREATE (s:Entity {name: $start})
                    CREATE (one:Entity {name: '探针实体-一跳'})
                    CREATE (two:Entity {name: '探针实体-二跳'})
                    CREATE (three:Entity {name: '探针实体-三跳'})
                    CREATE (low:Entity {name: '探针实体-低置信'})
                    CREATE (s)-[:REL {relation: '高置信一跳', docId: -1, confidence: 0.9, sourceChunkId: -1001}]->(one)
                    CREATE (s)-[:REL {relation: '低置信一跳', docId: -1, confidence: 0.55, sourceChunkId: -1002}]->(low)
                    CREATE (s)-[:REL {relation: '一跳', docId: -1, confidence: 0.8, sourceChunkId: -2001}]->(two)
                    CREATE (two)-[:REL {relation: '二跳', docId: -1, confidence: 0.7, sourceChunkId: -1003}]->(three)
                    CREATE (three)-[:REL {relation: '三跳', docId: -1, confidence: 0.7, sourceChunkId: -1004}]->(one)
                    """, java.util.Map.of("start", START)).consume();
        }
    }

    @Test
    @DisplayName("判据4 · confidence >= 0.6 且只走 1~2 跳（3 跳到不了）")
    void confidenceThresholdFilters() {
        seedGraph();

        GraphNeighborReader.Result result = neighborReader.neighbors(List.of(START));

        assertThat(result.fallback()).as("真库真查询不该降级").isFalse();
        List<GraphNeighborReader.Candidate> candidates = result.candidates();
        assertThat(candidates).as("至少要能跳出去").isNotEmpty();

        // 0.9 那条（1 跳）与 0.8×0.7 那条（2 跳）都要到
        assertThat(candidates).extracting(GraphNeighborReader.Candidate::sourceChunkId)
                .as("1 跳 0.9 与 2 跳的锚点都必须召回")
                .contains(-1001L, -1003L);
        // 0.55 那条：低于 0.6 的边**整条路径**都不许放行
        assertThat(candidates).extracting(GraphNeighborReader.Candidate::sourceChunkId)
                .as("0.55 < 0.6 —— 低置信关系的锚点一条都不能来").doesNotContain(-1002L);
        // 3 跳：MAX_HOPS=2 之外
        assertThat(candidates).extracting(GraphNeighborReader.Candidate::sourceChunkId)
                .as("3 跳的锚点不许出现（否则就是 07:512 的 1~2 跳没生效）").doesNotContain(-1004L);
    }

    @Test
    @DisplayName("判据4 · 跳数如实回报（1 跳 / 2 跳各归各的）")
    void hopsAreReportedHonestly() {
        seedGraph();

        List<GraphNeighborReader.Candidate> candidates = neighborReader.neighbors(List.of(START)).candidates();

        assertThat(candidates).filteredOn(c -> c.sourceChunkId() == -1001L)
                .singleElement().satisfies(c -> {
                    assertThat(c.hops()).isEqualTo(1);
                    assertThat(c.confidence()).isEqualTo(0.9d);
                });
        assertThat(candidates).filteredOn(c -> c.sourceChunkId() == -1003L)
                .singleElement().satisfies(c -> {
                    assertThat(c.hops()).as("两跳就是两跳").isEqualTo(2);
                    // ⚠️ 用 isCloseTo 而不是 isEqualTo：0.8*0.7 在双精度里不是"漂亮的 0.56"
                    assertThat(c.confidence()).as("路径分数 = 各段相乘")
                            .isCloseTo(0.56d, org.assertj.core.data.Offset.offset(1e-9));
                });
    }

    @Test
    @DisplayName("判据4 · 关系路径带出来（前端要显示「订单系统 → 基于 → 一致性哈希」）")
    void pathIsCarried() {
        seedGraph();

        List<GraphNeighborReader.Candidate> candidates = neighborReader.neighbors(List.of(START)).candidates();

        // ⚠️ 用 0.8×0.7 那条（两跳）—— 路径要能把中间的实体与谓词都串起来
        assertThat(candidates).filteredOn(c -> c.sourceChunkId() == -1003L)
                .singleElement()
                .satisfies(c -> assertThat(c.path()).containsExactly(
                        START, "一跳", "探针实体-二跳", "二跳", "探针实体-三跳"));
    }
}