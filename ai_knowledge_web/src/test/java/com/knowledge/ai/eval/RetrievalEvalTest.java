package com.knowledge.ai.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.enums.DataScope;
import com.knowledge.ai.core.enums.RetrievalMode;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.storage.MilvusPartitionManager;
import com.knowledge.ai.core.storage.MilvusProperties;
import com.knowledge.ai.pipeline.retrieve.HybridRetriever;
import com.knowledge.ai.pipeline.retrieve.RetrievalParams;
import com.knowledge.ai.pipeline.retrieve.RetrievalResult;
import com.knowledge.ai.pipeline.retrieve.RetrievalScope;
import com.knowledge.ai.pipeline.retrieve.RetrievedChunk;
import com.knowledge.ai.pipeline.retrieve.RetrievalService;
import com.knowledge.ai.pipeline.vectorize.EmbeddingClient;
import com.knowledge.ai.pipeline.vectorize.VectorChunk;
import com.knowledge.ai.pipeline.vectorize.VectorRecord;
import com.knowledge.ai.pipeline.vectorize.VectorWriter;
import com.knowledge.ai.core.setting.SystemSettingService;
import io.milvus.v2.client.MilvusClientV2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索质量评测集（`T-711` · `R-5`）。
 *
 * <h3>为什么它**不在** `mvn test` 里</h3>
 * <p>它必须调**真嵌入模型**（`BAAI/bge-m3`）—— 而 `mvn test` 的硬口径是「全替身、无网络」，
 * 那条口径是 `S5-M07-A` 花整批钉死的（它抓到过"替身没注册却照样绿"）。
 * 所以本类打 {@code @Tag("eval")}，默认被 surefire 排除，用
 * {@code mvn test -Peval} 显式跑。
 *
 * <p>代价说明白：**评测不参与每批门禁**，所以检索质量退化不会被自动发现。
 * 缓解是"结论落文档 + 每次改检索策略手工跑一遍"（`07` §4.4 的原话），
 * 而不是假装它在 CI 里。
 *
 * <h3>语料为什么以「已入库的分片」形态给出，而不是走 upload → parse → chunk</h3>
 * <ol>
 *   <li>**技术上走不通**：表格块只由 PDF（openDataLoader）与 Tika（XHTML `&lt;table&gt;`）两条路
 *       产出，而 `PlainTextParser` 覆盖的 MD/TXT **完全不抽表格** —— 合成报价单写成 `.md`
 *       会产出 **0 个 TABLE 块**，评测根本测不到表格。要端到端就得手写 OOXML 生成器；</li>
 *   <li>**概念上也不该走**：本评测的对象是**检索层**（`07` §4.4 原话是「每次改**检索策略**
 *       就跑一遍 Recall@5」），解析质量由 `ParseBaselineTest` 单独覆盖。
 *       把两者耦在一起，解析器一有变动，召回率的数字就没法解释了。</li>
 * </ol>
 *
 * <h3>度量口径</h3>
 * <ul>
 *   <li>{@code Recall@5} = 金标分片里进了前 5 条的比例，对题取平均；
 *       同时报「命中率」= 金标**全部**进前 5 的题占比（对多金标题更严格）；</li>
 *   <li>聚合题的 golden 多达 6 条 → Recall@5 **天然到不了 1.0**（只有 5 个名额）。
 *       这是"表格聚合问题需要独立召回通道（`T-710`）"这条判断的量化依据，照实报。</li>
 * </ul>
 *
 * <h3>它同时销 `#117`</h3>
 * <p>`plan/汇总.md` §3 #117 登记「`IVF_FLAT` + `nprobe=16 / nlist=128` 的召回率未量化」，
 * 归属本批。{@link #vectorRecallUnderDifferentNprobe()} 用同一套金标跑
 * {@code nprobe ∈ {8, 16, 32}}，给出三档 Recall@20 —— 那是 `#117` 要的那个数。
 */
@Tag("eval")
@SpringBootTest
class RetrievalEvalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path EVAL_DIR = Path.of("src", "test", "resources", "eval");

    private static final String KB_CODE = "eval-retrieval";

    /** 前 K 条（需求点名 Recall@5）。 */
    private static final int TOP_K = 5;

    /** 候选池大小 —— 与 `RetrievalParams.RECALL_TOP_K` 同源。 */
    private static final int CANDIDATE_K = 20;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MilvusClientV2 milvusClient;

    @Autowired
    private MilvusProperties milvusProperties;

    @Autowired
    private MilvusPartitionManager partitions;

    @Autowired
    private VectorWriter vectorWriter;

    /**
     * **真**嵌入客户端。
     *
     * <p>本类**刻意不继承** {@code RetrievalFlowSupport}、也不 {@code @Import}
     * {@code RetrievalTestStubs}：那两个一进来，容器里的 `EmbeddingClient` 就是
     * 记哈希的替身 —— 而用哈希向量测召回率，测出来的是"哈希函数的性质"，不是检索质量。
     * 不导入替身，容器里剩下的就是 {@code RegistryEmbeddingClient}（真调模型）。
     */
    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private RetrievalService retrievalService;

    @Autowired
    private RetrievalScope retrievalScope;

    @Autowired
    private SystemSettingService systemSettingService;

    private long kbId;

    private int generation = 1;

    /** 分片 key → 真实 chunkId。 */
    private final Map<String, Long> chunkIds = new LinkedHashMap<>();

    private final List<Long> docIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (int g = 1; g <= generation; g++) {
            partitions.dropQuietly(MilvusConstants.partitionName(kbId, g));
        }
        if (kbId > 0) {
            jdbc.update("delete from sys_kb_grant where kb_id = ?", kbId);
            jdbc.update("delete from sys_chunk where kb_id = ?", kbId);
            jdbc.update("delete from sys_document where kb_id = ?", kbId);
            jdbc.update("delete from sys_kb where id = ?", kbId);
        }
        chunkIds.clear();
        docIds.clear();
    }

    // ==================== 主判据 ====================

    @Test
    @DisplayName("T-711 · 24 条题集的 Recall@5（三种题型分别统计 + 负向锚必须不串扰）")
    void recallAtFiveOnTheEvalSet() throws Exception {
        QuerySet querySet = loadQueries();
        ingestCorpus();
        awaitVisible();

        List<String> report = new ArrayList<>();
        Map<String, double[]> byType = new LinkedHashMap<>();   // type -> [题数, recall 累加, 命中数]
        List<String> negativeFailures = new ArrayList<>();

        for (QuerySpec q : querySet.queries()) {
            List<String> top = searchTopK(q.query(), TOP_K);
            Set<String> golden = new LinkedHashSet<>(q.golden());
            long hit = golden.stream().filter(top::contains).count();
            double recall = golden.isEmpty() ? 0d : (double) hit / golden.size();
            boolean fullyHit = hit == golden.size();

            double[] acc = byType.computeIfAbsent(q.type(), k -> new double[3]);
            acc[0]++;
            acc[1] += recall;
            if (fullyHit) {
                acc[2]++;
            }

            // 负向锚：`G4-8` 的"查 A-100 不应召回 A-1000"
            for (String forbidden : q.mustNotRecall()) {
                if (top.contains(forbidden)) {
                    negativeFailures.add(q.id() + "「" + q.query() + "」召回了不该召回的 " + forbidden);
                }
            }

            List<String> missed = golden.stream().filter(g -> !top.contains(g)).toList();
            report.add(String.format(Locale.ROOT, "%-4s %-9s recall=%.2f %s%s%n        问：%s%n        前5：%s%s",
                    q.id(), q.type(), recall, fullyHit ? "✓" : "",
                    missed.isEmpty() ? "" : "  未召回=" + missed,
                    q.query(), top,
                    q.mustNotRecall().isEmpty() ? "" : "  负向锚=" + q.mustNotRecall()));
        }

        StringBuilder summary = new StringBuilder();
        summary.append("语料：").append(chunkIds.size()).append(" 条分片 / ")
                .append(docIds.size()).append(" 篇文档\n");
        summary.append("题集：").append(querySet.queries().size()).append(" 条（")
                .append("单跳 ").append(count(querySet, "SINGLE")).append(" · 多跳 ")
                .append(count(querySet, "MULTI")).append(" · 聚合 ")
                .append(count(querySet, "AGGREGATE")).append("）\n\n");

        double allRecall = 0;
        int allCount = 0;
        for (Map.Entry<String, double[]> e : byType.entrySet()) {
            double[] acc = e.getValue();
            allRecall += acc[1];
            allCount += (int) acc[0];
            summary.append(String.format(Locale.ROOT, "%-10s 题数=%2d  Recall@5=%.3f  全中率=%.3f%n",
                    e.getKey(), (int) acc[0], acc[1] / acc[0], acc[2] / acc[0]));
        }
        summary.append(String.format(Locale.ROOT, "%-10s 题数=%2d  Recall@5=%.3f%n",
                "合计", allCount, allRecall / allCount));

        String full = summary + "\n—— 逐题 ——\n" + String.join("\n", report) + "\n";
        System.out.println("EVAL-REPORT\n" + full);
        Files.writeString(Path.of("target", "eval-report.txt"), full, StandardCharsets.UTF_8);

        assertThat(querySet.queries())
                .as("需求要求 20~30 条（`07` §4.4）").hasSizeBetween(20, 30);
        assertThat(count(querySet, "SINGLE")).as("单跳题 ≥5").isGreaterThanOrEqualTo(5);
        assertThat(count(querySet, "MULTI")).as("多跳题 ≥5").isGreaterThanOrEqualTo(5);
        assertThat(count(querySet, "AGGREGATE")).as("聚合题 ≥5").isGreaterThanOrEqualTo(5);
        assertThat(negativeFailures)
                .as("负向锚（`G4-8`）：查 A-100 不该召回 A-1000 —— 串扰是**确定性缺陷**，不是精度问题")
                .isEmpty();

        // ⚠️ **刻意不设 Recall@5 的通过线**：需求没给阈值（`07` §4.4 只要求"用 Recall@5 度量"），
        // 而这是本项目的**第一条基线** —— "是多少"本身就是结论（与 `T-O02` 探针同一条口径：
        // 探针的产出常常是否掉一个数，而不是确认它）。
        // 设一条拍脑袋的线，只会得到一个"为了让用例变绿而调阈值"的动机。
        assertThat(allRecall).isGreaterThan(0d);
    }

    // ==================== #117 · nprobe 的召回率 ====================

    @Test
    @DisplayName("#117 · nprobe ∈ {8,16,32} 的 Recall@20（量化 IVF_FLAT 的召回率）")
    void vectorRecallUnderDifferentNprobe() throws Exception {
        QuerySet querySet = loadQueries();
        ingestCorpus();
        awaitVisible();

        StringBuilder out = new StringBuilder();
        out.append("nprobe 召回率（向量单通道，top-").append(CANDIDATE_K).append("）\n");
        out.append("集合参数：nlist=").append(milvusProperties.getIndexNlist())
                .append(" indexType=").append(milvusProperties.getIndexType()).append("\n\n");

        for (int nprobe : new int[] {8, 16, 32}) {
            MilvusProperties tuned = new MilvusProperties();
            BeanUtils.copyProperties(milvusProperties, tuned);
            tuned.setIndexNprobe(nprobe);
            HybridRetriever retriever = new HybridRetriever(milvusClient, tuned, embeddingClient, Runnable::run);

            RetrievalScope.Scope scope = retrievalScope.resolve(reader(), List.of(kbId));
            RetrievalParams params = RetrievalParams.resolve(
                    new com.knowledge.ai.dto.kb.KbDtos.RetrievalConfig("VECTOR", 0.15, 5, false, 1.2, true),
                    systemSettingService.snapshot());

            double total = 0;
            int count = 0;
            for (QuerySpec q : querySet.queries()) {
                HybridRetriever.Recall recall = retriever.recall(q.query(), scope, params);
                Set<String> retrieved = new LinkedHashSet<>();
                for (var hit : recall.vectorHits()) {
                    retrieved.add(keyOf(hit.chunkId()));
                }
                long hit = q.golden().stream().filter(retrieved::contains).count();
                total += q.golden().isEmpty() ? 0d : (double) hit / q.golden().size();
                count++;
            }
            out.append(String.format(Locale.ROOT, "nprobe=%-3d Recall@%d = %.3f%n",
                    nprobe, CANDIDATE_K, total / count));
        }

        String text = out.toString();
        System.out.println("EVAL-NPROBE\n" + text);
        Files.writeString(Path.of("target", "eval-nprobe.txt"), text, StandardCharsets.UTF_8);

        assertThat(text).contains("nprobe=16").contains("nprobe=32");
    }

    // ==================== 语料与查询 ====================

    /** 把语料入库 + 用**真嵌入**写 Milvus。 */
    private void ingestCorpus() throws Exception {
        Corpus corpus = MAPPER.readValue(Files.readString(EVAL_DIR.resolve("corpus.json")), Corpus.class);

        jdbc.update("""
                        insert into sys_kb (code, name, dept_id, doc_count, chunk_count, status,
                                            secret_level, business_domain, reindex_required,
                                            index_generation, retrieval_config_json, created_at, updated_at)
                        values (?, ?, 1, 0, 0, 'READY', 'INTERNAL', '', 0, 1, ?, now(), now())
                        """, KB_CODE, KB_CODE,
                // ⚠️ rerankEnabled=false：本评测测的是**召回**。开着重排会把它变成
                // "召回 + 重排"的联合效果，而两者的问题要分开定位
                // （重排的契约验证另有 SiliconFlow 的真实网络冒烟）
                "{\"mode\":\"HYBRID\",\"recallThreshold\":0.15,\"rerankTopK\":5,"
                        + "\"rerankEnabled\":false,\"tableWeight\":1.2,\"identifierProtect\":true}");
        kbId = jdbc.queryForObject("select id from sys_kb where code = ?", Long.class, KB_CODE);
        jdbc.update("""
                        insert into sys_kb_grant (kb_id, principal_type, principal_id, level, created_at, updated_at)
                        values (?, 'USER', 1, 'MANAGE', now(), now())
                        """, kbId);

        String partition = MilvusConstants.partitionName(kbId, generation);
        partitions.ensure(partition);

        for (DocSpec doc : corpus.documents()) {
            long docId = insertDocument(doc);
            List<VectorRecord> records = new ArrayList<>();
            int order = 1;
            for (ChunkSpec chunk : doc.chunks()) {
                Long parentId = chunk.parentKey() == null ? null : chunkIds.get(chunk.parentKey());
                long chunkId = insertChunk(docId, chunk, parentId, order++);
                chunkIds.put(chunk.key(), chunkId);

                VectorChunk vectorChunk = new VectorChunk(chunkId, chunk.chunkType(), chunk.content(),
                        chunk.titlePath(), "INTERNAL", parentId);
                String text = vectorChunk.vectorText();
                if (text == null) {
                    // 表格父块不进召回池 —— 与 `D-5` §1.4 一致，这里跳过而不是塞个空向量
                    continue;
                }
                records.add(new VectorRecord(vectorChunk, kbId, docId,
                        embeddingClient.embed(List.of(text)).getFirst(), "BAAI/bge-m3", "eval"));
            }
            vectorWriter.replaceDocument(partition, docId, records);
        }
        System.out.println("EVAL 语料入库完成：分片 " + chunkIds.size() + " 条");
    }

    private long insertDocument(DocSpec doc) {
        String md5 = String.format("%032x", Math.abs(doc.docKey().hashCode()));
        jdbc.update("""
                        insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5,
                                                  storage_path, status, chunk_count, table_total, table_parsed,
                                                  image_total, image_parsed, index_generation, created_at, updated_at)
                        values (?, 1, ?, ?, 'PDF', 1, ?, 'documents/eval', 'READY', ?, 0, 0, 0, 0, 1, now(), now())
                        """, kbId, doc.title(), doc.title() + ".pdf", md5, doc.chunks().size());
        long docId = jdbc.queryForObject(
                "select id from sys_document where kb_id = ? and title = ?", Long.class, kbId, doc.title());
        docIds.add(docId);
        return docId;
    }

    private long insertChunk(long docId, ChunkSpec chunk, Long parentId, int orderNo) {
        jdbc.update("""
                        insert into sys_chunk (doc_id, kb_id, chunk_type, parent_id, title_path,
                                               raw_content, content_sha256, order_no, page_no, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, 1, now())
                        """, docId, kbId, chunk.chunkType(), parentId, chunk.titlePath(),
                chunk.content(), com.knowledge.ai.core.text.ContentFingerprint.of(chunk.content()), orderNo);
        return jdbc.queryForObject("""
                        select id from sys_chunk where doc_id = ? and order_no = ?
                        """, Long.class, docId, orderNo);
    }

    /** 检索前 5 条的分片 key（金标比对要按**语料里的逻辑 key**，不是随机自增 id）。 */
    private List<String> searchTopK(String query, int k) {
        RetrievalResult result = retrievalService.search(reader(), query, List.of(kbId));
        List<String> keys = new ArrayList<>(k);
        for (RetrievedChunk chunk : result.chunks()) {
            if (keys.size() >= k) {
                break;
            }
            keys.add(keyOf(chunk.chunkId()));
        }
        return keys;
    }

    private String keyOf(String chunkId) {
        long id;
        try {
            id = Long.parseLong(chunkId);
        } catch (NumberFormatException ex) {
            return chunkId;
        }
        for (Map.Entry<String, Long> e : chunkIds.entrySet()) {
            if (e.getValue() == id) {
                return e.getKey();
            }
        }
        return chunkId;
    }

    /**
     * 等写入的向量**对检索侧可见**。
     *
     * <p>依据是 `S5-M07-A` 查出并已登记的产品级事实（`plan/汇总.md` §3 #114）：
     * `BOUNDED` 一致性下有**秒级写可见窗口** —— 写 5 条向量后立刻检索只回 4 条，
     * 隔 1 秒再查回 5 条，重复查会自愈。
     * 不等的后果是把"写可见性"算成"漏召回"，从而把召回率**系统性低估**，
     * 并可能得出"需要调大 nprobe"这种基于假象的结论（`#117` 正是要回答那个问题）。
     */
    private void awaitVisible() {
        int expected = (int) chunkIds.values().stream()
                .filter(id -> isRecallable(id))
                .count();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        int last = -1;
        while (System.nanoTime() < deadline) {
            RetrievalScope.Scope scope = retrievalScope.resolve(reader(), List.of(kbId));
            RetrievalParams params = RetrievalParams.resolve(
                    new com.knowledge.ai.dto.kb.KbDtos.RetrievalConfig("HYBRID", 0.15, 20, false, 1.2, true),
                    systemSettingService.snapshot());
            HybridRetriever retriever = new HybridRetriever(milvusClient, milvusProperties, embeddingClient, Runnable::run);
            last = retriever.recall("型号 单价 交期 起订量", scope, params).totalHits();
            if (last >= expected) {
                System.out.println("EVAL 写可见等待完成：命中 " + last + "/" + expected);
                return;
            }
            sleep(500);
        }
        throw new AssertionError("60 秒内向量仍未被检索侧看见（最后一次命中 " + last + "/" + expected + "）—— "
                + "评测结果不可信，先查 Milvus 写入与分区，不要带着这个状态出召回率");
    }

    /** 表格父块不进召回池（`D-5` §1.4），别把它算进期望命中数。 */
    private boolean isRecallable(long chunkId) {
        Integer parentIsNull = jdbc.queryForObject(
                "select count(*) from sys_chunk where id = ? and chunk_type = 'TABLE' and parent_id is null",
                Integer.class, chunkId);
        return parentIsNull == null || parentIsNull == 0;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static int count(QuerySet set, String type) {
        return (int) set.queries().stream().filter(q -> type.equals(q.type())).count();
    }

    private static Corpus loadCorpus() throws Exception {
        return MAPPER.readValue(Files.readString(EVAL_DIR.resolve("corpus.json")), Corpus.class);
    }

    private static QuerySet loadQueries() throws Exception {
        return MAPPER.readValue(Files.readString(EVAL_DIR.resolve("queries.json")), QuerySet.class);
    }

    private static LoginUser reader() {
        // 与 `RetrievalFlowSupport#reader` 逐字段对齐 —— 特别是 `secretLevelUpper`：
        // 少了它（null），`RetrievalScope#visibleKbs` 的 `secret_level in (:secretLevels)`
        // 会得到空集合，于是**可见库为零**、检索恒空 —— 而现象是"召回率 0.000"，
        // 看起来像检索坏了，实际是夹具少填了一个字段
        return LoginUser.builder()
                .userId(1L).username("admin").realName("评测").deptId(1L)
                .superAdmin(true).dataScope(DataScope.ALL)
                .secretLevelUpper(com.knowledge.ai.core.enums.SecretLevel.SECRET)
                .build();
    }

    // ==================== 夹具类型 ====================

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Corpus(List<DocSpec> documents) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DocSpec(String docKey, String title, List<ChunkSpec> chunks) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChunkSpec(String key, String chunkType, String parentKey, String titlePath, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record QuerySet(List<QuerySpec> queries) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record QuerySpec(String id, String type, String query, List<String> golden, List<String> mustNotRecall) {

        QuerySpec {
            golden = golden == null ? List.of() : List.copyOf(golden);
            mustNotRecall = mustNotRecall == null ? List.of() : List.copyOf(mustNotRecall);
        }
    }
}
