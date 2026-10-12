package com.knowledge.ai.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.enums.DataScope;
import com.knowledge.ai.core.enums.SecretLevel;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.storage.MilvusPartitionManager;
import com.knowledge.ai.pipeline.enhance.IndexEnhancer;
import com.knowledge.ai.pipeline.graph.GraphWriter;
import com.knowledge.ai.pipeline.graph.RelationAnchor;
import com.knowledge.ai.pipeline.retrieve.RetrievalResult;
import com.knowledge.ai.pipeline.retrieve.RetrievalService;
import com.knowledge.ai.pipeline.retrieve.RetrievedChunk;
import com.knowledge.ai.pipeline.vectorize.EmbeddingClient;
import com.knowledge.ai.pipeline.vectorize.VectorChunk;
import com.knowledge.ai.pipeline.vectorize.VectorRecord;
import com.knowledge.ai.pipeline.vectorize.VectorWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * `G9-4` 图谱分题型验收（`T-D10` · A 方案：手写关系三元组）。
 *
 * <h3>它测什么，与 `T-711`（表格型）差在哪</h3>
 * <p>`T-711` 的语料是合成**表格**（型号↔价格），多跳/聚合都在**同一表内**做比较 ——
 * 图谱帮不上忙（关系不在正文、跨不出表）。本类用**关系型语料**：实体跨文档多跳连通
 * （系统↔负责人↔部门↔组件↔下游），且多跳题的答案 chunk **不含查询实体名** ⇒
 * 向量/BM25 天然召不到，只有图谱经关系路径够得到。这才是 `G9-4` 要测的东西。
 *
 * <h3>为什么关系三元组是**手写**的（A 方案）</h3>
 * <p>让真模型抽关系会引入**抽取波动** —— 而 `G9-4` 要判的是 ±1pp / +10pp，
 * 波动会污染结论。抽取质量另有 {@code RelationAnchorRateProbeTest} 覆盖。
 * 手写关系把"图谱**检索**收益"这条隔离出来，正是本门禁的问题。
 *
 * <h3>口径</h3>
 * <p>同一语料 + 同一嵌入，**只切图谱开关**跑两遍，按题型出命中率差值。
 * 判据：单跳 ≤1pp · 多跳+聚合 ≥+10pp（不足 5pp ⇒ 建议降 P2 关闭，见计划 §8）。
 */
@Tag("eval")
@SpringBootTest
@TestPropertySource(properties = {
        "m05.enhance.enabled=false",
        "m05.graph.enabled=true",
        "app.retrieval.rebuild-cache-ms=0",
})
class GraphEvalTest {

    private static final Path EVAL_DIR = Path.of("src/test/resources/eval");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KB_CODE = "graph_eval_kb";
    private static final int TOP_K = 5;
    private static final String GRAPH_SETTING = "retrieval.graph-bypass-enabled";

    @Autowired private RetrievalService retrievalService;
    @Autowired private EmbeddingClient embeddingClient;
    @Autowired private VectorWriter vectorWriter;
    @Autowired private MilvusPartitionManager partitions;
    @Autowired private GraphWriter graphWriter;
    @Autowired private JdbcTemplate jdbc;

    private Long kbId;
    private final Map<String, Long> chunkIds = new LinkedHashMap<>();
    private final Map<String, Long> docIds = new LinkedHashMap<>();
    private final Map<String, Integer> blockOrderOf = new LinkedHashMap<>();
    private final List<Long> allDocIds = new ArrayList<>();
    private String originalSetting;

    @AfterEach
    void cleanup() {
        // 1) 还原图开关
        if (originalSetting != null) {
            jdbc.update("update sys_setting set setting_value = ? where setting_key = ?", originalSetting, GRAPH_SETTING);
        }
        // 2) 清图（按 docId 删边 + 文档 + 孤儿）
        if (!allDocIds.isEmpty()) {
            for (Long docId : allDocIds) {
                graphWriter.deleteDocument(docId, "graph-eval teardown");
            }
        }
        // 3) 清 MySQL + Milvus
        if (kbId != null) {
            // 向量随分区删除一起清（VectorWriter 只有 replaceDocument，无单独 delete）
            jdbc.update("delete from sys_chunk where kb_id = ?", kbId);
            jdbc.update("delete from sys_document where kb_id = ?", kbId);
            jdbc.update("delete from sys_kb_grant where kb_id = ?", kbId);
            jdbc.update("delete from sys_kb where id = ?", kbId);
            try { partitions.dropQuietly(MilvusConstants.partitionName(kbId, 1)); } catch (Exception ignored) {}
        }
        chunkIds.clear(); docIds.clear(); blockOrderOf.clear(); allDocIds.clear();
    }

    @Test
    @DisplayName("G9-4 · 图谱开/关 A/B：单跳持平 · 多跳+聚合 ≥+10pp")
    void graphOnOffAbByType() throws Exception {
        ingestCorpus();
        ingestGraph();
        awaitVisible();
        originalSetting = jdbc.queryForObject(
                "select setting_value from sys_setting where setting_key = ?", String.class, GRAPH_SETTING);

        QuerySet querySet = loadQueries();

        // ---- 图关 ----
        setGraph(false);
        Map<String, double[]> off = runSet(querySet);
        // ---- 图开 ----
        setGraph(true);
        Map<String, double[]> on = runSet(querySet);

        StringBuilder sb = new StringBuilder();
        sb.append("语料：").append(chunkIds.size()).append(" 分片 / ").append(docIds.size()).append(" 篇\n");
        sb.append(String.format(Locale.ROOT, "%-11s %5s %10s %10s %9s%n", "题型", "题数", "命中率(关)", "命中率(开)", "Δ(pp)"));
        double multiOffSum = 0, multiOnSum = 0; int multiN = 0;
        double singleDelta = 0;
        for (String type : List.of("SINGLE", "MULTI", "AGGREGATE")) {
            double[] o = off.get(type); double[] n = on.get(type);
            if (o == null || n == null) continue;
            double ho = o[2] / o[0] * 100, hn = n[2] / n[0] * 100, d = hn - ho;
            sb.append(String.format(Locale.ROOT, "%-11s %5d %9.1f%% %9.1f%% %+8.1f%n", type, (int) o[0], ho, hn, d));
            if ("SINGLE".equals(type)) singleDelta = d;
            if ("MULTI".equals(type) || "AGGREGATE".equals(type)) {
                multiOffSum += o[2]; multiOnSum += n[2]; multiN += (int) o[0];
            }
        }
        double multiOff = multiOffSum / multiN * 100, multiOn = multiOnSum / multiN * 100, multiDelta = multiOn - multiOff;
        sb.append(String.format(Locale.ROOT, "%-11s %5d %9.1f%% %9.1f%% %+8.1f%n", "多跳+聚合", multiN, multiOff, multiOn, multiDelta));
        System.out.println("==== G9-4 A/B ====\n" + sb);

        // 判据（判据 3/4）
        if (Math.abs(singleDelta) > 1.0) {
            System.out.printf(Locale.ROOT, "[判据3] ⚠️ 单跳差值 %.1fpp 超过 1pp 阈值%n", singleDelta);
        }
        if (multiDelta >= 10.0) {
            System.out.printf(Locale.ROOT, "[判据4] ✅ 多跳+聚合 +%.1fpp ≥ +10pp，图谱验收通过%n", multiDelta);
        } else if (multiDelta < 5.0) {
            System.out.printf(Locale.ROOT, "[判据4] ❌ 多跳+聚合 +%.1fpp < +5pp ⇒ 按需求建议降 P2 并关闭图谱旁路%n", multiDelta);
        } else {
            System.out.printf(Locale.ROOT, "[判据4] ⚠️ 多跳+聚合 +%.1fpp 在 [5,10) 区间：有收益但不达 +10pp 目标%n", multiDelta);
        }
    }

    private void printPerQuery(QuerySet qs) {
        for (QuerySpec q : qs.queries()) {
            List<String> top = searchTopK(q.query(), TOP_K);
            long hit = q.golden().stream().filter(top::contains).count();
            System.out.printf(Locale.ROOT, "   %s %-9s hit=%d/%d top=%s%n", q.id(), q.type(), hit, q.golden().size(), top);
        }
    }

    private Map<String, double[]> runSet(QuerySet querySet) {
        Map<String, double[]> byType = new LinkedHashMap<>();
        for (QuerySpec q : querySet.queries()) {
            List<String> top = searchTopK(q.query(), TOP_K);
            Set<String> golden = new LinkedHashSet<>(q.golden());
            long hit = golden.stream().filter(top::contains).count();
            boolean fullyHit = hit == golden.size();
            double[] acc = byType.computeIfAbsent(q.type(), k -> new double[3]);
            acc[0]++; if (fullyHit) acc[2]++;
            for (String forbidden : q.mustNotRecall()) {
                if (top.contains(forbidden)) {
                    System.out.printf(Locale.ROOT, "  ⚠️ 负向锚被召回：%s「%s」→ %s%n", q.id(), q.query(), forbidden);
                }
            }
        }
        return byType;
    }

    private void setGraph(boolean on) {
        jdbc.update("update sys_setting set setting_value = ? where setting_key = ?", Boolean.toString(on), GRAPH_SETTING);
    }

    private List<String> searchTopK(String query, int k) {
        RetrievalResult result = retrievalService.search(reader(), query, List.of(kbId));
        List<String> keys = new ArrayList<>();
        for (RetrievedChunk chunk : result.chunks()) {
            if (keys.size() >= k) break;
            keys.add(keyOf(chunk.chunkId()));
        }
        return keys;
    }

    private String keyOf(String chunkId) {
        for (Map.Entry<String, Long> e : chunkIds.entrySet()) {
            if (String.valueOf(e.getValue()).equals(chunkId)) return e.getKey();
        }
        return "?" + chunkId;
    }

    private void awaitVisible() throws Exception {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            if (searchTopK("订单系统", 1).size() > 0) return;
            Thread.sleep(500);
        }
    }

    private void ingestCorpus() throws Exception {
        Corpus corpus = MAPPER.readValue(Files.readString(EVAL_DIR.resolve("graph-corpus.json")), Corpus.class);
        jdbc.update("""
                insert into sys_kb (code, name, dept_id, status,
                                    secret_level, business_domain, reindex_required,
                                    index_generation, retrieval_config_json, created_at, updated_at)
                values (?, ?, 1, 'READY', 'INTERNAL', '', 0, 1, ?, now(), now())
                """, KB_CODE, KB_CODE,
                "{\"rerankEnabled\":false,\"recallThreshold\":0.2,\"missThreshold\":0.3}");
        kbId = jdbc.queryForObject("select id from sys_kb where code = ?", Long.class, KB_CODE);
        jdbc.update("insert into sys_kb_grant (kb_id, principal_type, principal_id, level, created_at, updated_at) "
                + "values (?, 'USER', 1, 'MANAGE', now(), now())", kbId);

        String partition = MilvusConstants.partitionName(kbId, 1);
        partitions.ensure(partition);

        for (DocSpec doc : corpus.documents()) {
            long docId = insertDocument(doc);
            docIds.put(doc.docKey(), docId);
            List<VectorRecord> records = new ArrayList<>();
            int order = 0;
            for (ChunkSpec chunk : doc.chunks()) {
                long chunkId = insertChunk(docId, chunk, order);
                chunkIds.put(chunk.key(), chunkId);
                blockOrderOf.put(chunk.key(), order++);
                VectorChunk vc = new VectorChunk(chunkId, chunk.chunkType(), chunk.content(),
                        chunk.titlePath(), "INTERNAL", null);
                String text = vc.vectorText();
                if (text == null) continue;
                records.add(new VectorRecord(vc, kbId, docId,
                        embeddingClient.embed(List.of(text)).getFirst(), "BAAI/bge-m3", "graph-eval"));
            }
            vectorWriter.replaceDocument(partition, docId, records);
        }
    }

    /** 把 graph-corpus 的关系按「已锚定」形态写进图（实体名在正文里 ⇒ 锚得上）。 */
    private void ingestGraph() throws Exception {
        Corpus corpus = MAPPER.readValue(Files.readString(EVAL_DIR.resolve("graph-corpus.json")), Corpus.class);
        for (DocSpec doc : corpus.documents()) {
            List<IndexEnhancer.Enhancement.Relation> rels = new ArrayList<>();
            List<RelationAnchor.Anchored> anchored = new ArrayList<>();
            List<Integer> orders = new ArrayList<>();
            for (RelationSpec r : corpus.relations()) {
                if (!r.docKey().equals(doc.docKey())) continue;
                Long chunkId = chunkIds.get(r.key());
                if (chunkId == null) continue;
                IndexEnhancer.Enhancement.Relation rel =
                        new IndexEnhancer.Enhancement.Relation(r.subject(), r.predicate(), r.object(), r.confidence());
                anchored.add(new RelationAnchor.Anchored(rel, blockOrderOf.get(r.key())));
            }
            Map<Integer, Long> chunkIdByOrder = new LinkedHashMap<>();
            for (ChunkSpec ch : doc.chunks()) {
                chunkIdByOrder.put(blockOrderOf.get(ch.key()), chunkIds.get(ch.key()));
            }
            graphWriter.replaceDocument(docIds.get(doc.docKey()), doc.title(), anchored, chunkIdByOrder);
        }
        System.out.println("EVAL 图谱写入完成");
    }

    private long insertDocument(DocSpec doc) {
        jdbc.update("""
                insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5,
                                          storage_path, status, chunk_count, table_total, table_parsed,
                                          image_total, image_parsed, index_generation, created_at, updated_at)
                values (?, 1, ?, ?, 'PDF', 1, ?, 'documents/graph-eval', 'READY', ?, 0, 0, 0, 0, 1, now(), now())
                """, kbId, doc.title(), doc.title() + ".pdf", String.format("%032x", Math.abs(doc.docKey().hashCode())), doc.chunks().size());
        long docId = jdbc.queryForObject("select id from sys_document where kb_id = ? and title = ?", Long.class, kbId, doc.title());
        allDocIds.add(docId);
        return docId;
    }

    private long insertChunk(long docId, ChunkSpec chunk, int orderNo) {
        jdbc.update("""
                insert into sys_chunk (doc_id, kb_id, chunk_type, parent_id, title_path,
                                       raw_content, content_sha256, order_no, page_no, created_at)
                values (?, ?, ?, null, ?, ?, ?, ?, 1, now())
                """, docId, kbId, chunk.chunkType(), chunk.titlePath(), chunk.content(),
                com.knowledge.ai.core.text.ContentFingerprint.of(chunk.content()), orderNo);
        return jdbc.queryForObject("select id from sys_chunk where doc_id = ? and order_no = ?", Long.class, docId, orderNo);
    }

    private static QuerySet loadQueries() throws Exception {
        return MAPPER.readValue(Files.readString(EVAL_DIR.resolve("graph-queries.json")), QuerySet.class);
    }

    private static LoginUser reader() {
        return LoginUser.builder()
                .userId(1L).username("admin").realName("图谱评测").deptId(1L)
                .superAdmin(true).dataScope(DataScope.ALL).secretLevelUpper(SecretLevel.SECRET)
                .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Corpus(List<DocSpec> documents, List<RelationSpec> relations) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DocSpec(String docKey, String title, List<ChunkSpec> chunks) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChunkSpec(String key, String chunkType, String parentKey, String titlePath, String content) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RelationSpec(String docKey, String key, String subject, String predicate, String object, double confidence) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record QuerySet(List<QuerySpec> queries) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    record QuerySpec(String id, String type, String query, List<String> golden, List<String> mustNotRecall) {
        QuerySpec {
            golden = golden == null ? List.of() : List.copyOf(golden);
            mustNotRecall = mustNotRecall == null ? List.of() : List.copyOf(mustNotRecall);
        }
    }
}
