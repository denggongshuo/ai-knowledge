package com.knowledge.ai.pipeline.graph;

import com.knowledge.ai.pipeline.enhance.IndexEnhancer;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一篇文档的实体与关系写进 Neo4j（`T-C00` · `FR-GRAPH-01` 的**构建侧**）。
 *
 * <h3>这个类存在的唯一理由：那批数据此前**没有任何出口**</h3>
 * <p>`T-507`（`S5-M08-B`）早就让文档级那次 LLM 调用产出 `relations` 了 ——
 * {@code IndexEnhancer.Enhancement#relations()} 一直在、条数也一直在日志里报。
 * 而 {@code DocParseService} 只取了 {@code enhanced.blocks()}，`Result#enhancement()`
 * **一个调用方都没有**。所以本批不是"新增抽取能力"，是**把已有的数据接出去**。
 *
 * <h3>🔴 三条红线（全部来自需求，逐条对得上）</h3>
 * <ol>
 *   <li><b>Neo4j 写失败**绝不影响文档入库**</b>（`07:523`「构建侧 Neo4j 写失败 → 只记失败日志，
 *       不影响文档索引主流程」）。⇒ 本类**永不抛**，任何异常都收在这里。
 *       这一条是 `G9-3` 的判据本体，也是 `07:525` 那句"系统主链路永远不依赖 Neo4j 的可用性"。</li>
 *   <li><b>实体 {@code MERGE} 幂等</b>（`07:482`）。⇒ 同一篇写两遍，节点与边都不翻倍。</li>
 *   <li><b>关系按文档版本删旧建新</b>（`07:482`）。⇒ 先删该文档的旧边再建新的，
 *       而不是"只加不删"—— 后者会让 v1 的关系在 v2 里继续被检索到，且**看不出是旧的**。</li>
 * </ol>
 *
 * <h3>为什么走裸 {@code Driver} 而不是 Spring Data Neo4j</h3>
 * <p>图模型只有 **3 种节点 + 2 种边**，而 `MERGE` 的精确语义（幂等靠它）用 Cypher 表达最直接。
 * 更实际的一条：本仓 {@code StorageConnectivityService#probeNeo4j} 已经在用 `Driver` 跑真查询
 * （`StorageConnectivityService.java:202`）—— 同一套依赖，不引第二个抽象。
 * 用 `ObjectProvider` 取驱动与那边同形：驱动**取不到**时本类退化成"什么都不做"，
 * 而不是让整个应用起不来（`07:525` 的决策锁）。
 *
 * <h3>⚠️ 事务边界：一次写入 = 一个 Neo4j 事务</h3>
 * <p>删除 + 建节点 + 建边放在**同一个** {@code executeWrite} 里：否则"删了旧的、建新的失败"
 * 会让这篇文档的图**整个消失**（比旧数据留着更糟 —— 旧数据至少是"过期"不是"没有"）。
 */
@Slf4j
@Service
public class GraphWriter {

    /** 实体标签（`07:462`：实体名是联合主键，同一全库实体合一，跨库共享）。 */
    public static final String LABEL_ENTITY = "Entity";
    public static final String LABEL_DOCUMENT = "Document";

    /** 关系类型。⚠️ 与 `:REL` 分开：后者是"实体—实体"的语义边，前者是"实体—分片"的回源锚。 */
    public static final String REL_TYPE = "REL";
    public static final String REL_CITES = "CITES";

    private final ObjectProvider<Driver> driverProvider;

    private final boolean enabled;

    /**
     * 单次图写入的事务超时。`07:534` 给的是"超时 200ms 兜底"，那是**检索侧**的红线
     * （问答等不起）；构建侧在解析 worker 线程里跑，宽松一些但要有个上限 ——
     * 无上限时 Neo4j 卡住会把一个 worker 线程占死。取 5 秒：比 30~80ms 的正常量级
     * 宽两个数量级，只在真出问题时才命中。
     */
    private final Duration timeout;

    public GraphWriter(ObjectProvider<Driver> driverProvider,
                       @Value("${m05.graph.enabled:true}") boolean enabled,
                       @Value("${m05.graph.write-timeout-ms:5000}") long timeoutMs) {
        this.driverProvider = driverProvider;
        this.enabled = enabled;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    /** 本次调用写了什么 —— 只进日志与任务结果（检索侧还没有消费方）。 */
    public record Result(boolean applied, int entities, int relations, int cites, String detail) {

        static Result skipped(String why) {
            return new Result(false, 0, 0, 0, why);
        }

        static Result failed(String why) {
            return new Result(false, 0, 0, 0, why);
        }
    }

    /**
     * 用这篇文档**当前的**实体关系覆盖图库里的旧数据（删旧建新）。
     *
     * <p>⚠️ **本方法永不抛** —— 见类注释红线 ①。
     *
     * @param docId          文档 id（`(:Document{docId})` 的主键，也是删旧的锚）
     * @param docTitle       文档标题（只进节点属性，供人工排查时看得懂）
     * @param anchored       已锚定的关系（{@link RelationAnchor#anchor} 的产物）
     * @param chunkIdByOrder **块序 → `sys_chunk.id`** 的映射（落库后回查得到，见
     *                       {@code DocParseService} 的调用点）。映射里没有的块序
     *                       一律**丢弃该条关系**并留痕 —— 需求 `:469` 要的是"可回源"，
     *                       回不到原文的边不该进图（与 {@link RelationAnchor} 同一条判例）
     */
    public Result replaceDocument(long docId, String docTitle, List<RelationAnchor.Anchored> anchored,
                                  Map<Integer, Long> chunkIdByOrder) {
        if (!enabled) {
            return Result.skipped("图谱开关未开启（m05.graph.enabled=false）");
        }
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null) {
            return Result.failed("Neo4j 驱动不可用（应用未装配 spring-boot-starter-data-neo4j？）");
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        int unanchored = 0;
        for (RelationAnchor.Anchored a : anchored == null ? List.<RelationAnchor.Anchored>of() : anchored) {
            Long chunkId = chunkIdByOrder == null ? null : chunkIdByOrder.get(a.blockOrder());
            if (chunkId == null) {
                unanchored++;
                continue;
            }
            IndexEnhancer.Enhancement.Relation rel = a.relation();
            Map<String, Object> row = new HashMap<>();
            row.put("subject", rel.subject());
            row.put("predicate", rel.predicate());
            row.put("object", rel.object());
            row.put("confidence", rel.confidence());
            row.put("sourceChunkId", chunkId);
            rows.add(row);
        }
        if (unanchored > 0) {
            // 照实留痕：这个数就是"锚定率"的另一面（计划 §3.4 假设 1 的实测观察项）
            log.warn("[图谱] {} 条关系在块序→chunk.id 映射里找不到落点，已丢弃：docId={}",
                    unanchored, docId);
        }
        // lambda 里要用的量必须 effectively final —— 上面那个计数器是累加出来的
        final int dropped = unanchored;
        final List<Map<String, Object>> graphRows = rows;

        long start = System.nanoTime();
        try (Session session = driver.session()) {
            Result result = session.executeWrite(tx -> {
                // ① 删该文档的旧边。
                //    ⚠️ `REL` 是**实体↔实体**的边（带 `r.docId` 属性标明出处），
                //    不是"挂到 Document 节点上"的 —— 所以按**属性**删，
                //    写成 `(d:Document)-[r:REL]-()` 会一条都匹配不到（一个静默的空删）。
                //
                //    🔴 `T-C04`：**人工补的边不删**（`r.manual = true`）。
                //    人工边与自动边同形（都是 `REL` + `docId`），唯一区分就是那个布尔属性。
                //    不留这一步的后果很安静：运营页补的边在文档**任何一次重建**后消失，
                //    而现象只是"补过的边过几天没了" —— 没人会把它和重建联系起来。
                //
                //    ⚠️ 条件必须写成 `r.manual IS NULL OR r.manual = false`：
                //    `S9-M12-A`~`C` 写进去的**存量边没有 `manual` 属性**（值是 null），
                //    写成 `r.manual = false` 会让存量边**一条都删不掉** ⇒ 重建后边翻倍
                //    （一个静默的数据腐化，而现象是"关系变多了"）。
                tx.run("""
                        MATCH ()-[r:REL]-()
                        WHERE r.docId = $docId AND (r.manual IS NULL OR r.manual = false)
                        DELETE r
                        """, Map.of("docId", docId)).consume();
                //    🔴 `CITES` 同理必须放行人工边：锚节点 `(:Chunk)` 是靠
                //    `MATCH (c:Chunk) WHERE NOT (()-[:CITES]->(c)) DELETE c`（见 `deleteDocument`）
                //    做兜底清理的 ⇒ 人工边的 CITES 一被删，那条人工边的 `sourceChunkId`
                //    就指向一个已被删掉的 `(:Chunk)`，回源变成**悬空锚点**
                //    （而 `GraphRetriever` 对悬空锚点是静默 `continue` —— 边看着在、跳不到原文）。
                tx.run("""
                        MATCH ()-[r:CITES]-()
                        WHERE r.docId = $docId AND (r.manual IS NULL OR r.manual = false)
                        DELETE r
                        """, Map.of("docId", docId)).consume();

                // ② 文档节点（MERGE：重解析不该造出第二个 Document 节点）
                tx.run("""
                        MERGE (d:Document {docId: $docId})
                        SET d.title = $title
                        """, Map.of("docId", docId, "title", docTitle == null ? "" : docTitle)).consume();

                if (graphRows.isEmpty()) {
                    return new Result(true, 0, 0, 0,
                            "本次没有可写入的关系，只做旧边清理");
                }

                // ③ 实体 MERGE + 关系 MERGE + 回源锚。
                //    `MERGE (s)-[r:REL {relation: row.predicate, docId: $docId}]->(o)`：
                //    同一对实体之间同一个动词、**同一篇文档**只留一条边 —— 这正是"幂等"的落点。
                //
                //    🔴 `docId` **必须进 MERGE 键**，不能只当 SET 的属性。
                //    第一版写成 `MERGE (s)-[r:REL {relation: $predicate}]->(o)` + `SET r.docId`，
                //    于是**同一句关系出现在两篇文档里时，边只有一条、docId 是后写的那篇** ——
                //    后果有两条，都很安静：
                //      ① 删 A 文档会把"B 文档也依赖的那条关系"一起删掉（**误伤**）；
                //      ② A 文档的版本重建会把 B 的归属覆盖成 A（**归属漂移**）。
                //    用例 `deleteDocumentsIsScopedToOneBatch` 当场逮到的是 ①。
                //    ⇒ 一条边 = 一篇文档的一次陈述，这与需求 `:469`「每条关系带
                //      sourceChunkId」是同一条口径（不同文档必然不同 chunkId）。
                //
                //    ⚠️ 两条 `CITES` 用**不同变量名**（c1/c2）：Cypher 里同名变量会被
                //    当成同一条边，写 `MERGE (s)-[c:CITES ...]->(d) MERGE (o)-[c:CITES ...]->(d)`
                //    会让第二句去改第一句那条边 —— 实体到分片的锚就只剩一条。
                tx.run("""
                        UNWIND $rows AS row
                        MERGE (s:Entity {name: row.subject})
                        MERGE (o:Entity {name: row.object})
                        MERGE (s)-[r:REL {relation: row.predicate, docId: $docId}]->(o)
                          SET r.confidence = row.confidence,
                              r.sourceChunkId = row.sourceChunkId
                        WITH s, o, r, row
                        MATCH (d:Document {docId: $docId})
                        MERGE (c:Chunk {chunkId: row.sourceChunkId})
                        MERGE (s)-[c1:CITES {docId: $docId, sourceChunkId: row.sourceChunkId}]->(c)
                        MERGE (o)-[c2:CITES {docId: $docId, sourceChunkId: row.sourceChunkId}]->(c)
                        """, Map.of("docId", docId, "rows", graphRows)).consume();

                int entities = tx.run("""
                                MATCH (n:Entity) RETURN count(n) AS n
                                """).single().get("n").asInt();
                int rels = tx.run("""
                                MATCH ()-[r:REL]->() WHERE r.docId = $docId RETURN count(r) AS n
                                """, Map.of("docId", docId)).single().get("n").asInt();
                int cites = tx.run("""
                                MATCH ()-[r:CITES]->() WHERE r.docId = $docId RETURN count(r) AS n
                                """, Map.of("docId", docId)).single().get("n").asInt();
                return new Result(true, entities, rels, cites,
                        "实体合计 " + entities + " · 关系 " + rels + " · 回源锚 " + cites
                                + (dropped == 0 ? "" : " · 丢弃 " + dropped));
            }, TransactionConfig.builder().withTimeout(timeout).build());

            log.info("[图谱] 写入完成：docId={} 关系={} 回源锚={} 耗时={}ms",
                    docId, result.relations(), result.cites(), (System.nanoTime() - start) / 1_000_000);
            return result;
        } catch (Exception ex) {
            // 🔴 红线 ①：**吞掉一切**。文档该入库还是入库。
            //    这里用 `log.error` 而不是 `warn`：Neo4j 写失败是**运维要看见**的事，
            //    而它不会以任何其它形式浮出来（接口不报错、状态正常）——
            //    不在这里喊一声，它就成了一个彻底的静默故障。
            log.error("[图谱] 写入失败（**文档仍将正常入库**）：docId={} 关系={} 原因={}",
                    docId, rows.size(), ex.toString());
            return Result.failed("Neo4j 写入失败：" + ex.getClass().getSimpleName()
                    + "（不影响文档索引，图谱下次重建补齐）");
        }
    }

    /**
     * 人工补一条边（`T-C04` · 需求 `07:544`「未命中问题可图谱补边」· 术语见 `03:102`）。
     *
     * <h3>与 {@link #replaceDocument} 的三处不同</h3>
     * <ol>
     *   <li><b>只加不删</b> —— 它是"针对未命中问题补一条缺失的边"，不是"用本篇覆盖"。
     *       （需求 `03:102` 明确对比过另一条路：「靠改文档重索引（慢且无针对性）」是**不做的**做法。）</li>
     *   <li><b>{@code confidence} 写死为 {@code 1.0}</b> —— 需求 `03:102` 逐字：「人工添加缺失的实体关系边
     *       （{@code confidence = 1}）」。模型自评的置信度（`0.x`）表达的是"抽得准不准"，
     *       而人工确认过的不存在这个问题。⚠️ 因此**本参数不开放给调用方** ——
     *       收一个客户端传的置信度，等于让"人工 = 1"这条口径可以被绕过。</li>
     *   <li><b>打 {@code manual = true}</b> —— 它是 {@link #replaceDocument} 删旧边时**唯一放行**的标记
     *       （见那边的 ①）。没有它，人工边会和自动边一起在下次重建时消失。</li>
     * </ol>
     *
     * <h3>⚠️ 为什么要两条 {@code CITES}（而不是只连 subject）</h3>
     * <p>回源锚是 `(:Entity)-[:CITES]->(:Chunk)`（需求 `07:469`「实体出现在哪个分片」）。
     * 两条都建与 {@link #replaceDocument} 同形 —— 且 `manual` 也要打在 CITES 上，
     * 否则重建时它们会被 `deleteDocument` 的 `Chunk` 兜底清理牵连（理由见那边的注释）。
     *
     * <p>⚠️ **本方法永不抛** —— 与 {@link #replaceDocument} 同一条红线 ①（`G9-3`）。
     * 运营页上补一条边不能让整页 500，Neo4j 挂了就如实回一个失败的 {@code detail}。
     *
     * @param docId     这篇关系出自哪篇文档（进 `MERGE` 键，与自动边同一口径）
     * @param chunkId   **必须是真的 `sys_chunk.id`**。⚠️ 调用方（{@code GraphEdgeService}）
     *                  负责校验它确实属于 {@code docId} —— 本类**不查 MySQL**（那是分层的事），
     *                  而写一个悬空锚点的后果是**静默的**：`GraphRetriever` 回源查不到就
     *                  `continue`，边看着在、跳不到原文。
     * @param subject   实体名（主体）
     * @param predicate 关系动词。⚠️ 固定集合的校验也在调用方 —— 需求 `07:468` 只认 8 个
     *                  （`基于/提出者/包含/使用/依赖/属于/负责/引用`）
     * @param object    实体名（客体）
     */
    public Result addManualEdge(long docId, long chunkId, String subject, String predicate, String object) {
        if (!enabled) {
            return Result.skipped("图谱开关未开启（m05.graph.enabled=false）");
        }
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null) {
            return Result.failed("Neo4j 驱动不可用（应用未装配 spring-boot-starter-data-neo4j？）");
        }

        long start = System.nanoTime();
        try (Session session = driver.session()) {
            Result result = session.executeWrite(tx -> {
                // ① 文档节点（MERGE —— 这次补边可能是该文档在图里的**第一笔**，
                //    例如它当初抽出的关系全被锚定丢弃了）
                tx.run("""
                        MERGE (d:Document {docId: $docId})
                        """, Map.of("docId", docId)).consume();

                // ② 实体 + 边 + 回源锚。MERGE 键与 replaceDocument 一致（`relation` + `docId`）
                //    ⇒ 同一条人工边补两次只留一条；而它**与自动边也不会撞车**
                //      （同一个动词 + 同一篇文档 + 同一对实体 = 语义上就是同一条陈述）。
                tx.run("""
                        MERGE (s:Entity {name: $subject})
                        MERGE (o:Entity {name: $object})
                        MERGE (s)-[r:REL {relation: $predicate, docId: $docId}]->(o)
                          SET r.confidence = 1.0,
                              r.sourceChunkId = $chunkId,
                              r.manual = true
                        WITH s, o, r
                        MATCH (d:Document {docId: $docId})
                        MERGE (c:Chunk {chunkId: $chunkId})
                        MERGE (s)-[c1:CITES {docId: $docId, sourceChunkId: $chunkId}]->(c)
                          SET c1.manual = true
                        MERGE (o)-[c2:CITES {docId: $docId, sourceChunkId: $chunkId}]->(c)
                          SET c2.manual = true
                        """, Map.of("docId", docId, "chunkId", chunkId,
                        "subject", subject, "predicate", predicate, "object", object)).consume();

                int entities = tx.run("MATCH (n:Entity) RETURN count(n) AS n")
                        .single().get("n").asInt();
                int rels = tx.run("MATCH ()-[r:REL]-() WHERE r.docId = $docId RETURN count(r) AS n",
                        Map.of("docId", docId)).single().get("n").asInt();
                int cites = tx.run("MATCH ()-[r:CITES]-() WHERE r.docId = $docId RETURN count(r) AS n",
                        Map.of("docId", docId)).single().get("n").asInt();
                return new Result(true, entities, rels, cites,
                        "实体合计 " + entities + " · 关系 " + rels + " · 回源锚 " + cites);
            }, TransactionConfig.builder().withTimeout(timeout).build());

            log.info("[图谱] 人工补边完成：docId={} chunkId={} {}-[{}]->{} 耗时={}ms",
                    docId, chunkId, subject, predicate, object,
                    (System.nanoTime() - start) / 1_000_000);
            return result;
        } catch (Exception ex) {
            // 红线 ①：吞掉一切。运营页会拿到 detail 里的原因，而不是一个 500。
            log.error("[图谱] 人工补边失败：docId={} chunkId={} 原因={}", docId, chunkId, ex.toString());
            return Result.failed("Neo4j 写入失败：" + ex.getClass().getSimpleName()
                    + "（这条边没有落库，可稍后重试）");
        }
    }

    /**
     * 删掉一篇文档在图库里的全部痕迹（**彻底删除文档时调**）。
     *
     * <p>⚠️ 与 {@link #replaceDocument} 同一条纪律：**永不抛**。
     * 但语义不同 —— 这里的失败**要留痕**，因为它是"删不干净"（数据泄漏面），
     * 而那边是"没写进去"（功能缺失）。
     *
     * @return 是否执行了删除（{@code false} = 开关关闭 / 驱动缺失 / 失败）
     */
    public boolean deleteDocument(long docId, String why) {
        if (!enabled) {
            return false;
        }
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null) {
            return false;
        }
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH ()-[r:REL]-() WHERE r.docId = $docId DELETE r",
                        Map.of("docId", docId)).consume();
                tx.run("MATCH ()-[r:CITES]-() WHERE r.docId = $docId DELETE r",
                        Map.of("docId", docId)).consume();
                tx.run("MATCH (c:Chunk) WHERE NOT (()-[:CITES]->(c)) DELETE c",
                        Map.of()).consume();
                // 🔴 `T-O31`（销 `#237`）：**孤儿实体回收**。
                //    上面两条把 `REL`/`CITES` 删掉之后，只被这篇文档引用过的 `Entity`
                //    就成了"没有任何边"的孤立节点 —— 此前**不回收**，实测彻底删一篇文档会
                //    留下 34 个 `rels=0` 的实体（`S9-M13-B` 实测）。
                //
                //    ⚠️ **为什么能全局扫，而不是按 docId 定向删**：
                //    需求 `07:465` 写死了「实体名是联合主键（**同一全库实体合一，跨库共享**）」
                //    —— 一个 `Entity` 可能同时被**多篇文档、多个库**引用，所以**绝不能**
                //    "这篇文档删了就把它的实体删掉"。唯一安全的判据是「**还有没有边引用它**」。
                //    ⚠️ 与上面那条"扫孤 `Chunk`"是**同一手法**（同样是"删完边再收尾"），
                //    不是新引入的范式。
                //    ⚠️ 它不会误伤：`GraphNeighborReader` 的入口是
                //    `MATCH p=(start:Entity)-[rels:REL*1..2]->(end:Entity)` ——
                //    **无 `REL` 的孤立实体在这条路径上本来就不可达**。
                tx.run("MATCH (e:Entity) WHERE NOT (e)--() DELETE e",
                        Map.of()).consume();
                tx.run("MATCH (d:Document {docId: $docId}) DELETE d",
                        Map.of("docId", docId)).consume();
                return null;
            }, TransactionConfig.builder().withTimeout(timeout).build());
            log.info("[图谱] 已清理文档痕迹：docId={}（{}）", docId, why);
            return true;
        } catch (Exception ex) {
            log.error("[图谱] **清理失败，图里仍有该文档的痕迹**：docId={} 原因={}", docId, ex.toString());
            return false;
        }
    }

    /**
     * 删掉一个知识库在图库里的全部痕迹（**彻底删除知识库时调**）。
     *
     * <p>⚠️ 与文档那一半的**关键差别**：图里**没有 `kbId` 维度** ——
     * 实体是"同一全库实体合一、跨库共享"（`07:462`），一个 `:Entity` 节点可能同时被
     * 多个库的文档引用。所以这里**不删实体节点**，只删「**属于该库那些文档**的边」。
     * 归属靠 `(:Document{docId})`，而 docId 由调用方（那时 MySQL 的行还在）查出来传进来。
     *
     * @param docIds 该库下**全部文档**的 id（调用方在删 MySQL 行**之前**查好）
     * @return 删掉的 `Document` 节点数（{@code 0} 也可能是"开关关闭"，看日志）
     */
    public int deleteDocuments(List<Long> docIds, long kbId) {
        if (!enabled || docIds == null || docIds.isEmpty()) {
            return 0;
        }
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null) {
            return 0;
        }
        try (Session session = driver.session()) {
            Integer deleted = session.executeWrite(tx -> {
                // 一次删完（UNWIND）而不是 N 个事务：库级删除本来就是"要么全清要么别做"，
                // 而逐个删在中途失败时会留下"删了一半"的库 —— 那是最难解释的状态
                tx.run("UNWIND $docIds AS did MATCH ()-[r:REL]-() WHERE r.docId = did DELETE r",
                        Map.of("docIds", docIds)).consume();
                tx.run("UNWIND $docIds AS did MATCH ()-[r:CITES]-() WHERE r.docId = did DELETE r",
                        Map.of("docIds", docIds)).consume();
                tx.run("MATCH (c:Chunk) WHERE NOT (()-[:CITES]->(c)) DELETE c", Map.of()).consume();
                // 🔴 `T-O31`（销 `#237`）：库级删除**同样**要回收孤儿实体 ——
                //    ⚠️ 与文档级那半必须**口径一致**，否则"清空整个库"之后图里仍留一批孤儿。
                //    判据 4 专门钉这一点（只补一处 ⇒ 它转红）。
                tx.run("MATCH (e:Entity) WHERE NOT (e)--() DELETE e", Map.of()).consume();
                return tx.run("""
                                UNWIND $docIds AS did
                                MATCH (d:Document {docId: did}) DELETE d
                                RETURN count(*) AS n
                                """, Map.of("docIds", docIds)).single().get("n").asInt();
            }, TransactionConfig.builder().withTimeout(timeout).build());
            log.info("[图谱] 已清理知识库痕迹：kbId={} 文档 {} 篇", kbId, deleted);
            return deleted;
        } catch (Exception ex) {
            log.error("[图谱] **知识库清理失败，图里仍有该库文档的痕迹**：kbId={} 文档数={} 原因={}",
                    kbId, docIds.size(), ex.toString());
            return 0;
        }
    }

    // ==================================================================
    // 读侧 —— **只给用例与排查用**，检索侧走 `T-C01`/`T-C02`（本批不做）
    // ==================================================================

    /**
     * 某篇文档在图里还剩几条 `REL`。
     *
     * <p>⚠️ 与写路径相反，**它抛异常**（驱动不可用时返回 {@code -1} 而不是 0）：
     * 一个"查不到就说 0"的计数会让「图里到底有没有东西」这件事永远看不出来，
     * 而那正是本批要钉住的东西。返回 {@code -1} 是**哨兵**，不是"没有"。
     */
    public int relationCountOf(long docId) {
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null) {
            return -1;
        }
        try (Session session = driver.session()) {
            return session.executeRead(tx -> tx.run("""
                            MATCH ()-[r:REL]->() WHERE r.docId = $docId
                            RETURN count(r) AS n
                            """, Map.of("docId", docId)).single().get("n").asInt());
        }
    }

    /**
     * 某篇文档的 `REL` 边的**三元组 + 置信度 + 回源锚**。
     *
     * <p>按 `relation` 再按 `subject` 排序返回 —— 让断言**不依赖图库的返回顺序**
     * （Neo4j 不保证遍历顺序，而依赖顺序的断言会变成偶发红）。
     */
    public List<Map<String, Object>> relationsOf(long docId) {
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null) {
            return List.of();
        }
        try (Session session = driver.session()) {
            return session.executeRead(tx -> {
                List<Map<String, Object>> out = new ArrayList<>();
                var result = tx.run("""
                        MATCH (s:Entity)-[r:REL]->(o:Entity)
                        WHERE r.docId = $docId
                        RETURN s.name AS subject, r.relation AS predicate, o.name AS object,
                               r.confidence AS confidence, r.sourceChunkId AS sourceChunkId
                        ORDER BY r.relation, s.name
                        """, Map.of("docId", docId));
                while (result.hasNext()) {
                    var rec = result.next();
                    Map<String, Object> row = new HashMap<>();
                    row.put("subject", rec.get("subject").asString());
                    row.put("predicate", rec.get("predicate").asString());
                    row.put("object", rec.get("object").asString());
                    row.put("confidence", rec.get("confidence").isNull()
                            ? null : rec.get("confidence").asDouble());
                    row.put("sourceChunkId", rec.get("sourceChunkId").isNull()
                            ? null : rec.get("sourceChunkId").asLong());
                    out.add(row);
                }
                return out;
            });
        }
    }
}
