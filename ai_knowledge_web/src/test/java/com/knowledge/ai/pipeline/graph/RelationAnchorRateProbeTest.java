package com.knowledge.ai.pipeline.graph;

import com.knowledge.ai.pipeline.enhance.ChatCaller;
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
 * 🔴 **关系锚定率探针**（`RelationAnchor` 的实测）—— `S9-M12-A` §9-1 与 `S9-M12-B` §9-1
 * **两批都点名"下一批第一件事"**的那件事。计划：`plan/2026-09-23-图谱锚定率探针.md`。
 *
 * <h3>它量的到底是什么（口径先说清，免得后人拿它当"真实锚定率"）</h3>
 * <p>`G9-4`（图谱分题型验收）的全部价值建立在"图里有够用的多跳关系"上，而关系能不能进图取决于
 * {@link RelationAnchor} 能不能把模型的三个词（subject / predicate / object）锚到一个分片。
 * 规则是**实体名在块文本里的首次出现**（subject 优先、fallback object），且 **SUMMARY 块不作锚定目标**。
 *
 * <p>⚠️ **本探针给出的是"机制行为 + 上界"，不是真实锚定率**：关系由**模型**产出，而 relay 的
 * 对话模型当前 `402 Insufficient Balance` ⇒ 这里只能用**替身**给关系。所以：
 * <ul>
 *   <li>**上界**：实体名在正文里逐字出现 ⇒ **一定能锚上**（本探针的正样本逐条钉住）；</li>
 *   <li>**两条丢弃边界**：① 两个实体都只在 **SUMMARY** 里（那是模型对全文的概括，不是原文）⇒ 丢；
 *       ② 两个实体都被模型**改写**（正文里逐字不存在）⇒ 丢；</li>
 *   <li>**比例本身不是市场事实**：下面 8 条里有几条可锚定是**我构造的**。真实锚定率 =
 *       "真实模型输出的用词与原文逐字一致的比例"，那要在真模型可用时用同一个入口量。</li>
 * </ul>
 *
 * <h3>为什么负样本比正样本值钱</h3>
 * <p>只断言"可锚定的都锚上了"，一个"**任何关系都硬凑一个锚点**"的实现照样绿 ——
 * 而那正是 `RelationAnchor` 的注释里点名要避免的（"硬凑一条会产出挂不上任何分片的边，
 * 它看起来是通的，却回不到原文"）。故判据 2 专门钉"该丢的必须丢"。
 */
@TestPropertySource(properties = {
        "m05.enhance.enabled=true",
        "m05.graph.enabled=true"})
class RelationAnchorRateProbeTest extends DownloadTestSupport {

    /** 正文里出现的实体（逐字）—— 探针的正样本靠它们。 */
    private static final String BODY = """
            # 分片方案

            订单系统采用分库分表，路由基于一致性哈希。

            数据分片依赖路由表，路由表由配置中心统一下发。
            """;

    /**
     * 替身给 **8 条**关系，三种可锚定形态与三种丢弃形态**各造样本**：
     *
     * <pre>
     *  ① 双实体都在正文        ：订单系统 -基于-> 一致性哈希          ✅
     *  ② 双实体都在正文        ：一致性哈希 -用于-> 数据分片          ✅
     *  ③ 双实体都在正文        ：数据分片 -依赖-> 路由表              ✅
     *  ④ 双实体都在正文        ：路由表 -由-> 配置中心                ✅
     *  ⑤ 客体被改写、**主体在正文**：订单系统 -迁移到-> 订单平台      ✅（subject 优先 ⇒ 仍锚上）
     *  ⑥ 两实体都**只在摘要**里 ：数据迁移 -缓解-> 单机吞吐           ❌（SUMMARY 块不作锚定目标）
     *  ⑦ 两实体都**被改写**     ：订单平台 -使用-> 缓存集群           ❌（正文里逐字不存在）
     *  ⑧ 两实体都**只在摘要**里 ：单机吞吐 -属于-> 容量规划           ❌
     * </pre>
     */
    @TestConfiguration
    static class StubModel {

        static final String REPLY = """
                {"summary": "本文说明数据迁移的成本与单机吞吐的限制。",
                 "labels": ["分布式", "一致性哈希"],
                 "relations": [
                   {"subject": "订单系统", "predicate": "基于", "object": "一致性哈希", "confidence": 0.92},
                   {"subject": "一致性哈希", "predicate": "用于", "object": "数据分片", "confidence": 0.88},
                   {"subject": "数据分片", "predicate": "依赖", "object": "路由表", "confidence": 0.81},
                   {"subject": "路由表", "predicate": "由", "object": "配置中心", "confidence": 0.77},
                   {"subject": "订单系统", "predicate": "迁移到", "object": "订单平台", "confidence": 0.70},
                   {"subject": "数据迁移", "predicate": "缓解", "object": "单机吞吐", "confidence": 0.66},
                   {"subject": "订单平台", "predicate": "使用", "object": "缓存集群", "confidence": 0.64},
                   {"subject": "单机吞吐", "predicate": "属于", "object": "容量规划", "confidence": 0.61}
                 ],
                 "chapters": []}
                """;

        @Bean
        @Primary
        ChatCaller stubChatCaller() {
            return (prompt) -> prompt.contains("推测") ? "{\"questions\": []}" : REPLY;
        }
    }

    /** 给出 8 条（见 {@link StubModel}）。 */
    private static final int GIVEN = 8;

    @Autowired
    private GraphWriter graphWriter;

    @Autowired
    private Driver driver;

    private final List<Long> probeDocIds = new ArrayList<>();

    @AfterEach
    void cleanGraphResidue() {
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
                    + "OR e.name STARTS WITH '一致性哈希' OR e.name STARTS WITH '数据分片' "
                    + "OR e.name STARTS WITH '路由表' OR e.name STARTS WITH '配置中心' "
                    + "OR e.name STARTS WITH '订单平台' OR e.name STARTS WITH '缓存集群' "
                    + "OR e.name STARTS WITH '数据迁移' OR e.name STARTS WITH '单机吞吐' "
                    + "OR e.name STARTS WITH '容量规划' DETACH DELETE e").consume();
            session.run("MATCH (c:Chunk) WHERE NOT (()-[:CITES]->(c)) DELETE c").consume();
        } catch (Exception ignored) {
            // 清不掉不该让用例失败：结论已由断言给出
        }
        probeDocIds.clear();
    }

    private List<Map<String, Object>> relationsOfProbeDoc() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());
        long kbId = createProbeKb(token);
        long docId = ((Number) uploadReady(kbId, "锚定率探针.md",
                BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8), token).data("$.docId")).longValue();
        probeDocIds.add(docId);
        return graphWriter.relationsOf(docId);
    }

    private static String key(Map<String, Object> relation) {
        return relation.get("subject") + "|" + relation.get("predicate") + "|" + relation.get("object");
    }

    @Test
    @DisplayName("判据1 · 可锚定形态**真的锚上**（含主体在正文、客体被改写那种）")
    void anchorableFormsAreAnchored() {
        List<Map<String, Object>> relations = relationsOfProbeDoc();

        assertThat(relations).extracting(RelationAnchorRateProbeTest::key)
                .as("双实体都在正文的四条必须都在")
                .contains("订单系统|基于|一致性哈希", "一致性哈希|用于|数据分片",
                        "数据分片|依赖|路由表", "路由表|由|配置中心");
        assertThat(relations).extracting(RelationAnchorRateProbeTest::key)
                .as("主体在正文、客体被改写 ⇒ **subject 优先**照样锚上（这条钉住锚定规则的方向）")
                .contains("订单系统|迁移到|订单平台");
    }

    @Test
    @DisplayName("判据2 · 🔴 丢弃形态**真的丢掉**（只在摘要里 / 两实体都被改写）")
    void unanchorableFormsAreDropped() {
        List<Map<String, Object>> relations = relationsOfProbeDoc();

        assertThat(relations).extracting(RelationAnchorRateProbeTest::key)
                .as("两个实体都只在 SUMMARY 里 ⇒ 丢（SUMMARY 块不是原文，回不到出处）")
                .doesNotContain("数据迁移|缓解|单机吞吐", "单机吞吐|属于|容量规划");
        assertThat(relations).extracting(RelationAnchorRateProbeTest::key)
                .as("两个实体都被模型改写 ⇒ 丢（硬凑一个锚点就是'看起来通的'假边）")
                .doesNotContain("订单平台|使用|缓存集群");

        // 观察项：本探针构造下的比例（**不是**真实锚定率 —— 见类注释）
        int anchored = relations.size();
        System.out.printf("[锚定率探针] 锚定 %d / 模型给出 %d = %.1f%%（构造样本的上界；"
                        + "真实比例要真模型）· (:Entity) 节点数 %d%n",
                anchored, GIVEN, 100.0 * anchored / GIVEN, entityCount());
        assertThat(anchored).as("8 条里 5 条可锚定（构造），其余 3 条按规则该丢").isEqualTo(5);
    }

    private int entityCount() {
        try (Session session = driver.session()) {
            return session.run("MATCH (e:Entity) RETURN count(e) AS n").single().get("n").asInt();
        }
    }
}