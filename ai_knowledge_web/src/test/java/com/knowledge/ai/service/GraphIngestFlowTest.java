package com.knowledge.ai.service;

import com.knowledge.ai.pipeline.enhance.ChatCaller;
import com.knowledge.ai.pipeline.graph.GraphWriter;
import com.knowledge.ai.support.DownloadTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-C00` 的**端到端接线判据** —— 真上传 → 真解析 → 真写图 → 真彻底删除 → 图里的边真消失。
 *
 * <h3>为什么 {@code GraphWriteFlowTest} 那 9 条不够</h3>
 * <p>那 9 条验的是 {@link GraphWriter} **自己**做对了（幂等 / 删旧建新 / 可回源 / 清理）。
 * 它们**一条都验不到**"解析链路真的调了它"与"彻底删除真的调了它" ——
 * 而这两处**接线**才是本批第 3 步与第 5 步的全部内容。
 *
 * <p>本仓对这类"接线"吃过亏（`S7-M06-D` 的 `G7-5`：两条机制互为充分，只应验一条时判据照样绿）。
 * 所以这里走**真 HTTP**：上传（`POST /kb/{id}/doc/upload` + `parseWorker.drainOnce()`）、
 * 软删（`DELETE /doc/{id}`）、彻底删除（`DELETE /recycle/items`）。
 *
 * <h3>两个开关都要打开，而且理由不同</h3>
 * <ul>
 *   <li>{@code m05.enhance.enabled=true} —— 否则 `Enhancement` 恒为 null，
 *       **一条关系都不产**，图里永远没东西可写（这条链路会变成空断言）；</li>
 *   <li>{@code m05.graph.enabled=true} —— 否则 `GraphWriter` 每次都走"开关未开启"的早退分支。</li>
 * </ul>
 * 两个都用 {@code @TestPropertySource} 单独打开（测试期默认都是 `false`），
 * 与 {@code IndexEnhanceFlowTest} 同一种做法。
 *
 * <h3>为什么模型是假的</h3>
 * <p>{@code ChatCaller} 是函数式接口，一个 lambda 就是全部实现 ——
 * 它替换的只有**模型**那一层，管道其余部分（清洗 → 分片 → 增强 → 落库 → 写图）全是真的。
 * 这样判据打的是真实产物，而跑批不依赖 relay 通不通。
 * <p>⚠️ 本仓 pom **没有 Mockito**，这里也不需要（见 {@code IndexEnhanceFlowTest} 的说明）。
 */
@TestPropertySource(properties = {
        "m05.enhance.enabled=true",
        "m05.graph.enabled=true",
})
class GraphIngestFlowTest extends DownloadTestSupport {

    /**
     * 覆盖生产用的 {@code RegistryChatCaller}。
     *
     * <p>⚠️ <b>必须声明在**本类**里</b>：父类里的嵌套 {@code @TestConfiguration}
     * **不会被 Spring Boot 自动发现**（`#118` 记过的通用教训）。
     *
     * <p>关系里的实体名（「订单系统」/「一致性哈希」）**必须真的出现在上传的正文里** ——
     * 否则 {@code RelationAnchor} 锚不上，图里一条边都不会有，
     * 而下面那条"图里真的有边"的断言会当场红（这正是它该有的行为）。
     */
    @TestConfiguration
    static class StubModel {

        static final String REPLY = """
                {"summary": "本探针文档说明订单系统的分片方案与一致性哈希的提出者。",
                 "labels": ["分布式", "一致性哈希"],
                 "relations": [{"subject": "订单系统", "predicate": "基于", "object": "一致性哈希",
                                "confidence": 0.92}],
                 "chapters": []}
                """;

        @Bean
        @Primary
        ChatCaller stubChatCaller() {
            return (prompt) -> prompt.contains("推测") ? "{\"questions\": []}" : REPLY;
        }
    }

    /**
     * 探针正文 —— **实体名必须逐字出现在里面**（见 {@link StubModel} 的说明）。
     *
     * <p>正文里刻意让「订单系统」出现在**第一段**、「一致性哈希」出现在第二段：
     * 于是 {@code RelationAnchor} 取主体优先的判据也一并被验到了
     * （锚到第 1 块而不是客体所在的第 2 块）。
     */
    private static final byte[] PROBE_CONTENT = """
            # 分片方案

            订单系统采用分库分表，路由基于一致性哈希。

            一致性哈希由 Karger 提出，用于减少扩缩容时的数据迁移。
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Autowired
    private GraphWriter graphWriter;

    @Autowired
    private Driver driver;

    /** 本用例上传过的文档 id（收尾清图用）。 */
    private final List<Long> probeDocIds = new ArrayList<>();

    @AfterEach
    void cleanGraphResidue() {
        // ⚠️ 本方法在父类 `cleanDownloadProbeData` **之前**跑（JUnit 先跑子类的 `@AfterEach`），
        //    所以此刻 MySQL 的行还在 —— 但也**不必**依赖它：按登记好的 docId 清就够了。
        //    🔴 图库是**第四种存储**，父类那套（行 + 桶对象 + 向量）**完全不认识它**。
        if (probeDocIds.isEmpty()) {
            return;
        }
        try (Session session = driver.session()) {
            session.run("MATCH ()-[r:REL]-() WHERE r.docId IN $ids DELETE r",
                    Map.of("ids", List.copyOf(probeDocIds))).consume();
            session.run("MATCH ()-[r:CITES]-() WHERE r.docId IN $ids DELETE r",
                    Map.of("ids", List.copyOf(probeDocIds))).consume();
            session.run("MATCH (d:Document) WHERE d.docId IN $ids DELETE d",
                    Map.of("ids", List.copyOf(probeDocIds))).consume();
            session.run("MATCH (e:Entity) WHERE e.name STARTS WITH '订单系统' "
                    + "OR e.name STARTS WITH '一致性哈希' DETACH DELETE e").consume();
        } catch (Exception ex) {
            // 清不掉不该让用例失败 —— 断言已经给出结论；这里留一条日志便于排查
            System.err.println("图谱残留清理失败（不影响判据）：" + ex);
        }
        probeDocIds.clear();
    }

    @Test
    @DisplayName("接线①：真上传真解析 ⇒ 图里真的有边，且 sourceChunkId 指回真实分片")
    void ingestWritesGraphWithRealChunkId() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());
        long kbId = createProbeKb(token);
        long docId = ((Number) uploadReady(kbId, "图谱探针.md", PROBE_CONTENT, token).data("$.docId")).longValue();
        probeDocIds.add(docId);

        List<Map<String, Object>> relations = graphWriter.relationsOf(docId);
        assertThat(relations)
                .as("解析链路必须真的调了 GraphWriter —— 空集说明接线断了，"
                        + "或实体名不在正文里（锚定失败）")
                .hasSize(1);
        assertThat(relations.getFirst().get("subject")).isEqualTo("订单系统");
        assertThat(relations.getFirst().get("predicate")).isEqualTo("基于");
        assertThat(relations.getFirst().get("object")).isEqualTo("一致性哈希");
        assertThat(relations.getFirst().get("confidence")).isEqualTo(0.92);

        // 🔴 `sourceChunkId` 必须指向**真实的 `sys_chunk` 行**，且那一行的正文里
        //    真的含这条关系的实体名 —— 这是需求 `:469`「可溯源回原文」的可执行版本。
        //    ⚠️ 只断言"字段非空"是不够的：写一个不存在的 id 也能非空。
        Object chunkId = relations.getFirst().get("sourceChunkId");
        assertThat(chunkId).as("回源锚不能为空").isNotNull();
        Long realId = ((Number) chunkId).longValue();
        Long rowCount = jdbc.queryForObject(
                "select count(*) from sys_chunk where id = ?", Long.class, realId);
        assertThat(rowCount).as("sourceChunkId=%s 必须真的在 sys_chunk 里", realId).isEqualTo(1L);
        String raw = jdbc.queryForObject(
                "select raw_content from sys_chunk where id = ?", String.class, realId);
        assertThat(raw)
                .as("回源到的那一块的正文里必须含实体名 —— 否则这个锚是「看起来通的」")
                .contains("订单系统");
    }

    @Test
    @DisplayName("接线②：彻底删除文档 ⇒ 图里它的边与回源锚一并消失")
    void purgeRemovesGraphEdges() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());
        long kbId = createProbeKb(token);
        long docId = ((Number) uploadReady(kbId, "图谱删除探针.md", PROBE_CONTENT, token).data("$.docId")).longValue();
        probeDocIds.add(docId);

        assertThat(graphWriter.relationsOf(docId)).as("前置态：图里先有边").hasSize(1);

        // 软删（进回收站）—— **不该动图**（与 `G7-14` 的"软删不动向量/对象"同一条口径）
        call("DELETE", "/api/doc/" + docId, null, token).assertOk("软删");
        assertThat(graphWriter.relationsOf(docId))
                .as("🔴 软删**不清图** —— 30 天内还要能恢复，恢复后图不能是空的").hasSize(1);

        // 彻底删除 ⇒ 图里的边与 CITES 必须一起消失
        RawResponse purged = call("DELETE", "/api/recycle/items",
                Map.of("type", "DOC", "ids", List.of(docId)), token);
        assertThat(purged.code()).as("彻底删除：" + purged.body()).isZero();

        assertThat(graphWriter.relationsOf(docId))
                .as("🔴 `purgeDoc` 必须真的调了 `GraphWriter#deleteDocument` —— "
                        + "不接这一步就是一个**任何清扫都发现不了的泄漏面**").isEmpty();
        try (Session session = driver.session()) {
            int cites = session.run("MATCH ()-[r:CITES]->() WHERE r.docId = $docId RETURN count(r) AS n",
                    Map.of("docId", docId)).single().get("n").asInt();
            assertThat(cites).as("回源锚也要清").isZero();
        }
    }
}
