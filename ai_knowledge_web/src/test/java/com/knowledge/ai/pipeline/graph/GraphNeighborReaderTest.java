package com.knowledge.ai.pipeline.graph;

import com.knowledge.ai.support.DownloadTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
        // ⚠️ `T-C04`：这里原来还有一条 `m05.graph.retrieve.enabled=true`。
        // `T-C03` 把**检索侧开关的事实源**搬到了 `sys_setting`（读口 `GraphFeatureToggle`）
        // ⇒ 那个属性不再被任何代码读，留着只会让下一个人以为"改它就能关图谱"。
        // 本类要的是"开着时的真库行为"，而 `V43` 种的默认值就是 `true`，无需声明（`#233`）。
        "m05.graph.enabled=true"})
class GraphNeighborReaderTest extends DownloadTestSupport {

    private static final String START = "探针实体-起点";

    /** 进本类前开关的值 —— 收尾还原它（不硬编码：产品默认已改为关）。 */
    private String graphSwitchBefore;

    @Autowired
    private Driver driver;

    @Autowired
    private GraphNeighborReader neighborReader;

    /**
     * 🔴 `T-C04`：开关现在读 `sys_setting`（不再受测试配置影响）⇒ 本类**必须自己把它打开**。
     *
     * <p>为什么不能只依赖种子的默认值：`GraphRetrieveDisabledTest` 会把这一行改成 `false`
     * 再改回来，而 **JUnit 默认同线程顺序执行、两个类共用一个库** —— 若它因故没还原，
     * 本类会以"一条邻居都查不到"的形态**静默变绿**（`GraphNeighborReader` 对开关关掉是
     * 返回空结果、不抛也不报错）。这正是本仓反复复发的"闸的红绿取决于没说出口的前置"。
     * ⇒ 显式打开 + 收尾还原为 `true`（种子的值），让它**不依赖别的类干不干净**。
     */
    @BeforeEach
    void enableGraphSwitch() {
        graphSwitchBefore = jdbc.queryForObject(
                "select setting_value from sys_setting where setting_key = 'retrieval.graph-bypass-enabled'", String.class);
        jdbc.update("update sys_setting set setting_value = 'true'"
                + " where setting_key = 'retrieval.graph-bypass-enabled'");
    }

    @AfterEach
    void cleanProbeNodes() {
        try (Session session = driver.session()) {
            session.run("MATCH (e:Entity) WHERE e.name STARTS WITH '探针实体-' DETACH DELETE e").consume();
        } catch (Exception ignored) {
            // 清不掉不该让用例失败：失败信息已经由断言给出
        }
        // 还原开关（本类 @BeforeEach 打开了它；不还原会污染后面的批次）
        if (graphSwitchBefore != null) {
            jdbc.update("update sys_setting set setting_value = ? where setting_key = 'retrieval.graph-bypass-enabled'", graphSwitchBefore);
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
