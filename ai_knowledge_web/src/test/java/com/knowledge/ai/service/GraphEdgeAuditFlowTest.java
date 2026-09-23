package com.knowledge.ai.service;

import com.knowledge.ai.core.storage.DocStorage;
import com.knowledge.ai.support.DownloadTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * `T-C04` 判据 6：图谱补边的**审计落库** —— 必须走**真 HTTP**。
 *
 * <h3>为什么不能像同类那样直调 Service</h3>
 * <p>审计里有三格**只在请求上下文里成立**：`operator`（谁点的）、`api_path`（从哪个接口进来的）、
 * `ip`。直调 Service 时它们**全是空值**，而断言"日志落了"照样通过 ——
 * 那会是一条**看着绿、其实没验到**的判据（`S2AuditFlowTest` 的教训，
 * `SystemFeatureFlowTest` 的 `T-C03` 判据也是照这条写的）。
 *
 * <h3>⚠️ 本类刻意只验"审计"这一件事</h3>
 * <p>补边的**业务**判据（边落库、能不能被召回、悬空锚点被拒、动词闭集、抗重建）都在
 * {@code GraphManualEdgeFlowTest} 里，那边直调 Service、不建 HTTP —— 两条分开的理由是：
 * 业务判据要好造数据（直调可以精确控制输入），而审计判据**必须**走 HTTP。
 * 合成一个类就得让所有业务判据都背上一次上传 + 等 `READY`，慢且与它们要验的东西无关。
 */
@DisplayName("判据6 · 图谱补边的审计留痕（真 HTTP）")
class GraphEdgeAuditFlowTest extends DownloadTestSupport {

    private static final String PROBE_FILE = "补边审计探针.md";

    @Autowired
    private Driver driver;

    private long probeKbId;
    private long probeDocId;
    private long missId;

    /** 本轮审计行的起点 —— 断言按它之后的行捞，避免捞到历史（同 `S2AuditFlowTest`）。 */
    private long auditFloor;

    @BeforeEach
    void setUp() {
        auditFloor = jdbc.queryForObject("select coalesce(max(id), 0) from sys_audit_log", Long.class);
    }

    /**
     * 上传一篇探针文档并**把它置成 `READY`**。
     *
     * <p>⚠️ 为什么不能只靠 `uploadReady` 的等待：测试期 `m05.vectorize.enabled=false`
     * ⇒ 向量化那一步返回 `DISABLED`、**不盖 `READY`**（那是正字：宁可不报可检索，
     * 也不谎报）⇒ 文档停在 `INDEXING`，而补边端点会以「尚未解析成功」拒掉它。
     * <p>本类验的是**审计**，与"解析有没有跑完"无关；分片其实已经由解析落库了
     * （`INDEXING` 只差向量那一步）⇒ 直接改状态列，把前置补齐。
     */
    private long uploadReadyProbe(String token) {
        long docId = ((Number) uploadReady(probeKbId, PROBE_FILE,
                "# 补边审计探针\n\n订单系统采用一致性哈希做路由。\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                token).data("$.docId")).longValue();
        jdbc.update("update sys_document set status = 'READY' where id = ?", docId);
        return docId;
    }

    @AfterEach
    void cleanUp() {
        try (Session session = driver.session()) {
            if (probeDocId > 0) {
                session.run("MATCH ()-[r:REL]-() WHERE r.docId = $id DELETE r",
                        Map.of("id", probeDocId)).consume();
                session.run("MATCH ()-[r:CITES]-() WHERE r.docId = $id DELETE r",
                        Map.of("id", probeDocId)).consume();
                // 🔴 `(:Document)` 节点**不会**被"按前缀删实体"碰到（它没有 name、只有 docId）
                //    ⇒ 不显式删它就攒节点。本批实测：一轮跑批留下 30+ 个 `(:Document)`
                //    （`S9-M12-B` 漏清 `(:Chunk)` 是同形的第二次）。
                session.run("MATCH (d:Document {docId: $id}) DELETE d",
                        Map.of("id", probeDocId)).consume();
            }
            session.run("MATCH (e:Entity) WHERE e.name STARTS WITH '补边审计探针-' DETACH DELETE e")
                    .consume();
            session.run("MATCH (c:Chunk) WHERE NOT (()-[:CITES]->(c)) DELETE c").consume();
        } catch (Exception ignored) {
            // 清不掉不该让用例失败：结论已由断言给出
        }
        if (missId > 0) {
            jdbc.update("delete from sys_qa_miss where id = ?", missId);
            missId = 0;
        }
    }

    @Test
    @DisplayName("判据6 · 真 HTTP 补一条边 ⇒ 审计里有一条「图谱补边」，operator/api_path 非空、档位 NORMAL")
    void graphEdgeIsAudited() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());

        probeKbId = createProbeKb(token);
        probeDocId = uploadReadyProbe(token);
        long chunkId = jdbc.queryForObject(
                "select id from sys_chunk where doc_id = ? order by order_no limit 1",
                Long.class, probeDocId);

        // 一条真实存在的未命中行（`sys_qa_miss` 没有 trace 关联，手工插最直接）
        // ⚠️ `kb_ids` 是 NOT NULL（默认空串）：显式传 null 会覆盖默认值 ⇒ 违反约束
        jdbc.update("""
                insert into sys_qa_miss (miss_key, question, ask_count, first_ask_at, last_ask_at,
                                         last_user_id, max_top_score, kb_ids, status, updated_at)
                values (?, ?, 1, now(), now(), 1, 0.10, '', 'PENDING', now())
                """, "补边审计探针-" + System.nanoTime(), "补边审计探针-订单系统怎么路由");
        missId = jdbc.queryForObject("select max(id) from sys_qa_miss", Long.class);

        call("POST", "/api/ops/qa/misses/" + missId + "/graph-edge", Map.of(
                "subject", "补边审计探针-订单系统",
                "predicate", "基于",
                "object", "补边审计探针-一致性哈希",
                "docId", probeDocId,
                "chunkId", chunkId), token).assertOk("图谱补边");

        // 🔴 回查审计表（**不拿接口返回值当"记上了"的证据**）
        Map<String, Object> row = jdbc.queryForMap("""
                select module, action, risk_level, operator, api_path
                  from sys_audit_log
                 where action = '图谱补边' and id > ?
                 order by id desc limit 1
                """, auditFloor);
        assertThat(row.get("module")).isEqualTo("问答");
        // ⚠️ 存的是**枚举名** `NORMAL`（`AuditLogService:71` 用的是 `event.riskLevel().name()`），
        //    不是那个中文标签「普通」—— 第一版按标签写，实测取回的是 `NORMAL`。
        assertThat(row.get("risk_level")).as("补边是 NORMAL（与「开关图谱旁路」的 HIGH 刻意分档）")
                .isEqualTo("NORMAL");
        // ⚠️ 这两格是本类存在的理由：直调 Service 时它们是空的
        assertThat(row.get("operator")).as("取自请求上下文 —— 空值说明这条判据没走 HTTP")
                .isNotNull();
        assertThat(row.get("api_path")).as("取自请求上下文")
                .asString().contains("graph-edge");
    }

    @Test
    @DisplayName("判据6b · 参数错（动词越界）**不产生**审计行 —— 没有动作就不该留痕")
    void rejectedRequestLeavesNoAuditRow() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());

        probeKbId = createProbeKb(token);
        probeDocId = uploadReadyProbe(token);
        long chunkId = jdbc.queryForObject(
                "select id from sys_chunk where doc_id = ? order by order_no limit 1",
                Long.class, probeDocId);
        jdbc.update("""
                insert into sys_qa_miss (miss_key, question, ask_count, first_ask_at, last_ask_at,
                                         last_user_id, max_top_score, kb_ids, status, updated_at)
                values (?, ?, 1, now(), now(), 1, 0.10, '', 'PENDING', now())
                """, "补边审计探针-" + System.nanoTime(), "补边审计探针-订单系统怎么路由");
        missId = jdbc.queryForObject("select max(id) from sys_qa_miss", Long.class);

        // 动词不在闭集 ⇒ 10030
        RawResponse rejected = call("POST", "/api/ops/qa/misses/" + missId + "/graph-edge", Map.of(
                "subject", "补边审计探针-订单系统",
                "predicate", "喜欢",   // 🔴 不在需求 07:468 的 8 个里
                "object", "补边审计探针-一致性哈希",
                "docId", probeDocId,
                "chunkId", chunkId), token);
        assertThat(rejected.code())
                .as("动词越界该被拒（10030）—— 实际：%s", rejected.message())
                .isEqualTo(10030);

        // ⚠️ 被拒的请求**不该**留下"图谱补边"的成功痕迹。
        //    本仓的切面在**方法抛异常**时记的是 `result=FAILED`（那是对的、也是要留的），
        //    但本用例拒在**参数校验**那一步 —— 它发生在写图之前，**没有任何东西被改**。
        //    两种都要能区分，所以这里只断言"没有一条 result=SUCCESS 的图谱补边"。
        Long succeeded = jdbc.queryForObject("""
                select count(*) from sys_audit_log
                 where action = '图谱补边' and id > ? and result = 'SUCCESS'
                """, Long.class, auditFloor);
        assertThat(succeeded).as("参数错的请求不该留下「补边成功」的痕迹").isZero();
    }
}
