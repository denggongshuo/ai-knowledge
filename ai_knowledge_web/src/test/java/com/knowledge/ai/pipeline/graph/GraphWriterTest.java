package com.knowledge.ai.pipeline.graph;

import com.knowledge.ai.pipeline.enhance.IndexEnhancer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link GraphWriter} 的**两条红线**取证（判据 1 与判据 2）—— **不依赖 Spring、不依赖 Neo4j**。
 *
 * <h3>为什么这两条必须单独一个类</h3>
 * <p>{@code GraphWriteFlowTest} 那 10 条跑在真 Neo4j 上（值 40 秒一次），
 * 而本类的两条验的是**"什么都没发生"**：
 * <ul>
 *   <li>判据 2 —— 开关关掉 ⇒ **一次调用都没有**（`I-16` 的"零代码路径残留"）；</li>
 *   <li>判据 1 —— Neo4j 不可达 ⇒ **不抛**（`G9-3`：文档仍正常入库）。</li>
 * </ul>
 * 两条都不需要真图库：前者要的是"驱动一次都没被取过"，后者要的是"连不上时怎么办"——
 * 而**"连不上"这件事在真图库里恰恰造不出来**（那里连得上）。
 *
 * <h3>为什么用真 {@code Driver} 指向一个坏端口，而不是替身</h3>
 * <p>判据 1 要验的是 {@code catch (Exception)} 那一层真的兜住了 —— 而**它兜的是驱动的异常**。
 * 用一个"永远抛"的替身驱动，验的是"替身抛了它接住"，与驱动真抛什么无关。
 * 指向 `bolt://127.0.0.1:1`（一个几乎必然拒绝连接的端口）得到的是**真实的连接失败**，
 * 与生产里 Neo4j 挂掉时是同一条路径。
 *
 * <h3>为什么"开关关掉 ⇒ 零调用"能验得比"没写图"更强</h3>
 * <p>用 {@link ObjectProvider} 的替身**记调用次数**：开关关掉时它必须是 0。
 * ⚠️ 这条与"写了图但写失败"**完全不同** —— 后者至少取了驱动、发了请求。
 * `I-16`（`07:537`）要的正是前者：**关闭时链路与未开启完全一致**。
 */
class GraphWriterTest {

    /** 一个只记"被取过几次"的 provider —— 判据 2 的全部证据就是那个计数。 */
    private static ObjectProvider<Driver> countingProvider(AtomicInteger calls, Driver driver) {
        return new ObjectProvider<>() {
            @Override
            public Driver getObject(Object... args) {
                calls.incrementAndGet();
                return driver;
            }

            @Override
            public Driver getIfAvailable() {
                calls.incrementAndGet();
                return driver;
            }

            @Override
            public Driver getIfUnique() {
                calls.incrementAndGet();
                return driver;
            }
        };
    }

    private static final List<IndexEnhancer.Enhancement.Relation> RELS =
            List.of(new IndexEnhancer.Enhancement.Relation("甲", "基于", "乙", 0.9));

    private static final List<RelationAnchor.Anchored> ANCHORED =
            List.of(new RelationAnchor.Anchored(RELS.getFirst(), 1));

    // ==================================================================
    // 判据 2 · 关掉 ⇒ 零调用
    // ==================================================================

    @Test
    @DisplayName("判据 2：`m05.graph.enabled=false` ⇒ **驱动一次都没被取过**（I-16 的构建侧半边）")
    void disabledMeansNoCallAtAll() {
        AtomicInteger driverCalls = new AtomicInteger();
        GraphWriter writer = new GraphWriter(
                countingProvider(driverCalls, null), false, 1000);

        GraphWriter.Result write = writer.replaceDocument(1L, "文档", ANCHORED, Map.of(1, 10L));
        boolean deleted = writer.deleteDocument(1L, "用例");
        int deletedBatch = writer.deleteDocuments(List.of(1L, 2L), 7L);
        int schema = new GraphSchema(countingProvider(driverCalls, null), false).ensureSchema();

        assertThat(write.applied()).as("关闭时不该报告「写了」").isFalse();
        assertThat(deleted).isFalse();
        assertThat(deletedBatch).isZero();
        assertThat(schema).isZero();
        assertThat(driverCalls.get())
                .as("🔴 `I-16`（需求 `07:537`）：关闭时链路必须与未开启**完全一致** —— "
                        + "连驱动都不许取。取一次就说明有一条代码路径没被开关挡住，"
                        + "而那种残留**在功能上完全看不出来**（它什么都不会写）")
                .isZero();
    }

    // ==================================================================
    // 判据 1 · 写失败不牵连文档
    // ==================================================================

    @Test
    @DisplayName("判据 1：Neo4j 不可达 ⇒ **不抛**，返回 failed（G9-3：文档仍正常入库）")
    void unreachableNeo4jNeverThrows() {
        // 指向一个必然拒绝连接的端口 —— 与生产里 Neo4j 挂掉是同一条路径。
        // 🔴 **超时参数是必需的，不是调优**：驱动的默认重试策略会让一次连接失败
        //    反复重试（实测**三条调用共 154 秒**，而其中大半是纯等待）——
        //    而这条判据要的是"连不上时怎么办"，不是"连不上时等多久"。
        //    压到 200ms/0ms 之后整条用例 1 秒内跑完，且验的还是同一条路径。
        Driver dead = GraphDatabase.driver("bolt://127.0.0.1:1", AuthTokens.none(),
                Config.builder()
                        .withConnectionTimeout(200, TimeUnit.MILLISECONDS)
                        .withMaxTransactionRetryTime(0, TimeUnit.MILLISECONDS)
                        .build());
        try {
            AtomicInteger driverCalls = new AtomicInteger();
            GraphWriter writer = new GraphWriter(countingProvider(driverCalls, dead), true, 1000);

            assertThatCode(() -> {
                GraphWriter.Result r = writer.replaceDocument(1L, "文档", ANCHORED, Map.of(1, 10L));
                assertThat(r.applied()).isFalse();
                assertThat(r.detail()).as("失败原因要可读（`G4-3` 的口径：不许只说「失败」两字）")
                        .contains("Neo4j");
                assertThat(writer.deleteDocument(1L, "用例")).isFalse();
                assertThat(writer.deleteDocuments(List.of(1L), 7L)).isZero();
            })
                    .as("🔴 `07:523`：构建侧 Neo4j 写失败 → **只记失败日志，不影响文档索引主流程**。"
                            + "本方法一旦抛，`DocParseService` 就会把一篇完全可用的文档判 FAILED —— "
                            + "而那是本仓最不能接受的形态（用一件增强功能的故障否掉一篇好文档）")
                    .doesNotThrowAnyException();

            assertThat(driverCalls.get()).as("这一次**取过**驱动 —— 与判据 2 正好相反").isPositive();
        } finally {
            dead.close();
        }
    }

    @Test
    @DisplayName("判据 1 的边界：**驱动压根没装配**（ObjectProvider 给 null）⇒ 同样不抛")
    void missingDriverIsAlsoSafe() {
        GraphWriter writer = new GraphWriter(countingProvider(new AtomicInteger(), null), true, 1000);

        assertThatCode(() -> {
            assertThat(writer.replaceDocument(1L, "文档", ANCHORED, Map.of(1, 10L)).applied()).isFalse();
            assertThat(writer.deleteDocument(1L, "用例")).isFalse();
            assertThat(writer.deleteDocuments(List.of(1L), 7L)).isZero();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("判据 8 的边界：驱动没装配时 `ensureSchema` 也不抛（图谱是增强不是依赖）")
    void schemaIsSafeWithoutDriver() {
        GraphSchema schema = new GraphSchema(countingProvider(new AtomicInteger(), null), true);

        assertThatCode(() -> assertThat(schema.ensureSchema()).isZero())
                .as("`07:525`：系统主链路永远不依赖 Neo4j 的可用性 —— "
                        + "Neo4j 起不来时正确行为是「图写不进去、文档照常入库」，"
                        + "而不是「应用起不来」")
                .doesNotThrowAnyException();
    }
}
