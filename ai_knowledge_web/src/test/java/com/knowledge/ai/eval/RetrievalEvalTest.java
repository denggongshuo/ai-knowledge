package com.knowledge.ai.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.enums.BlockType;
import com.knowledge.ai.core.enums.DataScope;
import com.knowledge.ai.core.enums.RetrievalMode;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.storage.MilvusPartitionManager;
import com.knowledge.ai.core.storage.MilvusProperties;
import com.knowledge.ai.pipeline.Block;
import com.knowledge.ai.pipeline.ParsedDocument;
import com.knowledge.ai.pipeline.enhance.HyqGate;
import com.knowledge.ai.pipeline.enhance.IndexEnhancer;
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
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
// `T-O09` 判据 5 的 A/B 要**真跑一次 HyQ 生成**，而测试配置里增强是关的
// （`m05.enhance.enabled=false`，与向量化同一条理由）。本类的其余用例不碰增强器，
// 所以把这一条开在这里不影响既有基线（`#117` 与 `T-711` 的数字都不经过它）
@TestPropertySource(properties = "m05.enhance.enabled=true")
class RetrievalEvalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path EVAL_DIR = Path.of("src", "test", "resources", "eval");

    private static final String KB_CODE = "eval-retrieval";

    /** 前 K 条（需求点名 Recall@5）。 */
    private static final int TOP_K = 5;

    /** 候选池大小 —— 与 `RetrievalParams.RECALL_TOP_K` 同源。 */
    private static final int CANDIDATE_K = 20;

    /** 评测库的检索配置（`ingestCorpus` 与 HyQ A/B 两组**共用同一份**）。 */
    private static final String EVAL_RETRIEVAL_CONFIG =
            "{\"mode\":\"HYBRID\",\"recallThreshold\":0.15,\"rerankTopK\":5,"
                    + "\"rerankEnabled\":false,\"tableWeight\":1.2,\"identifierProtect\":true}";

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

    /**
     * **真**索引增强器（`T-O09` 判据 5 的 A/B 用）。
     *
     * <p>本类不 `@Import` 替身 ⇒ 容器里是 `RegistryChatCaller`（真调对话模型）。
     * ⚠️ A/B **必须**走它，而不是在用例里自己拼提示词 —— 拼一份就多一处会漂移的
     * 提示词副本，而"两组差的是门控"这个前提当场就不成立了。
     */
    @Autowired
    private IndexEnhancer indexEnhancer;

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

    // ==================== T-O09 判据 5 · HyQ 门控的 A/B ====================

    /** A/B 组名与它的独立库编码 —— 与 `KB_CODE` 分开，否则同 JVM 里跑两个用例会撞 `uk_kb_code`。 */
    private static final String AB_KB_CODE = "eval-hyq-ab";

    /** 一组的结果。 */
    private record AbGroup(String label, int hyqBlocks, double recall, Map<String, Double> byType) {
    }

    /**
     * `T-O09` 判据 5 —— **HyQ 门控到底省了多少召回质量**（销 `#152` / `#198`）。
     *
     * <h3>为什么不能借 `ingestCorpus` 的那份语料</h3>
     * <p>它是**从 `corpus.json` 直插库、只做嵌入**的 —— 不走解析、更不走增强
     * ⇒ **HyQ 块一块都不会生成**，两组跑出来一模一样。所以本方法自己把
     * **真增强器**接上（`IndexEnhancer.enhance`，真对话模型），A/B 才有对象。
     *
     * <h3>两组差的是**哪一个变量**</h3>
     * <p>只有门控。做法是**一次性把所有源块都生成出来**（门控关），然后：
     * <ul>
     *   <li>**全量组** —— 全部 HyQ 都进索引；</li>
     *   <li>**门控后组** —— 只留源块过了 {@link HyqGate} 的那些。</li>
     * </ul>
     * 🔑 这样做而不是"各生成一遍"，是因为那样两组拿到的 HyQ **文本本身不同**
     * （模型每次输出不一样）⇒ 量到的是"门控 + 生成波动"，两个变量混在一个数里。
     * 共用一批文本之后，唯一的自变量就是门控。
     *
     * <h3>它与成本那一半必须一起报</h3>
     * <p>只报召回率会得出「门控纯粹是损失」的结论。调用次数才是分数的分母
     * —— 所以两个数并排打出来。
     *
     * <h3>⚠️ 采样边界（照 `#117` 的口径如实写）</h3>
     * <p>结论只在**这 27 条分片 / 24 条题集**上成立。这份语料里门控放行 9 块、跳过 18 块，
     * 而跳过的那 18 块里 **15 块是表行** ⇒ 本 A/B 量到的其实主要是
     * 「**表行该不该各自生成 HyQ**」这一个设计选择，不是门控全部规则的总和。
     */
    @Test
    @DisplayName("T-O09 判据 5：HyQ 门控的召回质量 A/B（门控后 vs 全量生成）")
    void hyqGateRecallAb() throws Exception {
        QuerySet querySet = loadQueries();
        Corpus corpus = loadCorpus();

        // ---------- ① 基座语料：两组**逐字相同** ----------
        kbId = insertEvalKb(AB_KB_CODE);
        Map<String, Long> idByKey = new LinkedHashMap<>();
        Map<String, Integer> orderByKey = new LinkedHashMap<>();
        int order = 0;
        for (DocSpec doc : corpus.documents()) {
            long docId = insertDocument(doc);
            int docOrder = 1;
            for (ChunkSpec chunk : doc.chunks()) {
                Long parentId = chunk.parentKey() == null ? null : idByKey.get(chunk.parentKey());
                idByKey.put(chunk.key(), insertChunk(docId, chunk, parentId, docOrder++));
                orderByKey.put(chunk.key(), ++order);
            }
        }

        // ---------- ② 一次生成（门控**关**）----------
        List<Block> baseBlocks = blocksOf(corpus, orderByKey);
        IndexEnhancer.Result enhanced = indexEnhancer.enhance(evalDoc(corpus), baseBlocks, false);
        assertThat(enhanced.status())
                .as("文档级调用必须成功 —— 它一失败 `enhance` 就**提前返回**，HyQ 那一步压根没跑，"
                        + "而两组会双双退化成同一个索引、比出一个 0 差。"
                        + "⚠️ 原因必须打出来（`detail()`）：只印一个 MODEL_FAILED 的话，"
                        + "排查得去翻日志，而日志里那行常被编码搞成一团乱码 —— %s",
                        enhanced.detail())
                .isEqualTo(IndexEnhancer.Status.OK);

        // 增强器把入参块原样留在前面、追加在后面 —— 本方法靠这条切分
        assertThat(enhanced.blocks().subList(0, baseBlocks.size()))
                .as("增强器必须**原样保留并保序**入参块（它现在是这么做的，本方法依赖它）")
                .containsExactlyElementsOf(baseBlocks);
        List<Block> extras = enhanced.blocks().subList(baseBlocks.size(), enhanced.blocks().size());

        // ---------- ③ 增强块落成 chunk 行（两组共用同一批文本）----------
        Map<Integer, Long> idByOrder = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : idByKey.entrySet()) {
            idByOrder.put(orderByKey.get(e.getKey()), e.getValue());
        }
        Set<Integer> gateOpenOrders = new LinkedHashSet<>();
        for (Block b : baseBlocks) {
            if (HyqGate.shouldGenerate(b)) {
                gateOpenOrders.add(b.orderNo());
            }
        }

        Set<String> hyqKeys = new LinkedHashSet<>();
        Set<String> nonHyqExtraKeys = new LinkedHashSet<>();
        int extraOrder = order + 1;
        for (Block b : extras) {
            boolean isHyq = b.blockType() == BlockType.HYPOTHESIS_QA;
            String key = isHyq ? "hyq:" + b.sourceOrderNo() : "extra:" + extraOrder;
            Long sourceId = isHyq ? idByOrder.get(b.sourceOrderNo()) : null;
            idByKey.put(key, insertEnhancedChunk(docIds.getFirst(), b, sourceId, extraOrder++));
            (isHyq ? hyqKeys : nonHyqExtraKeys).add(key);
        }

        // ---------- ④ 两组各写一代分区、各跑一遍 24 条题集 ----------
        // 🔴 **基座块必须进两组**（`orderByKey.keySet()` = 那 27 块）。
        //    第一版漏了它 —— 于是写进 Milvus 的只有增强块、检索池里一个源块都没有，
        //    现象是"top-5 全是 HyQ"，我当时**差点把它当成一条真发现报出去**。
        //    真正暴露它的是那两个我当时没在意的数字：`写可见稳定 26 条` 与 `40 条`
        //    —— 恰好等于 min(20, 13)×2 与 min(20, 29)×2，也就是**两组实际写进去的向量数**。
        //    ⇒ 教训：等待/探针打出来的数字要**跟预期对一遍**，别只看它"稳定了"。
        Set<String> baseKeys = orderByKey.keySet();
        Set<String> gatedKeys = new LinkedHashSet<>(baseKeys);
        gatedKeys.addAll(nonHyqExtraKeys);
        for (String k : hyqKeys) {
            if (gateOpenOrders.contains(Integer.parseInt(k.substring("hyq:".length())))) {
                gatedKeys.add(k);
            }
        }
        Set<String> fullKeys = new LinkedHashSet<>(baseKeys);
        fullKeys.addAll(nonHyqExtraKeys);
        fullKeys.addAll(hyqKeys);

        AbGroup gated = measureGroup(1, "门控后（HyqGate 生效）", gatedKeys, idByKey, querySet);
        AbGroup full = measureGroup(2, "全量（门控关闭）", fullKeys, idByKey, querySet);

        // ---------- ⑤ 结论 ----------
        StringBuilder out = new StringBuilder();
        out.append("T-O09 判据 5 · HyQ 门控的召回质量 A/B\n");
        out.append("语料：").append(idByKey.size()).append(" 块（基座 ").append(order)
                .append(" + 增强 ").append(extras.size()).append("）· 题集 ")
                .append(querySet.queries().size()).append(" 条\n");
        out.append("门控口径：放行 ").append(gateOpenOrders.size()).append(" / 跳过 ")
                .append(baseBlocks.size() - gateOpenOrders.size()).append(" 个源块\n\n");
        out.append(String.format(Locale.ROOT, "%-22s %8s %10s %10s%n", "组", "HyQ块", "Recall@5", "相对差"));
        out.append(String.format(Locale.ROOT, "%-22s %8d %10.3f %10s%n",
                full.label(), full.hyqBlocks(), full.recall(), "—"));
        out.append(String.format(Locale.ROOT, "%-22s %8d %10.3f %+10.3f%n",
                gated.label(), gated.hyqBlocks(), gated.recall(), gated.recall() - full.recall()));
        out.append("\n按题型：\n");
        for (String type : full.byType().keySet()) {
            out.append(String.format(Locale.ROOT, "  %-10s 全量=%.3f  门控后=%.3f  %+.3f%n",
                    type, full.byType().get(type), gated.byType().getOrDefault(type, 0d),
                    gated.byType().getOrDefault(type, 0d) - full.byType().get(type)));
        }
        out.append("\n⚠️ 采样边界：结论只在**这 27 条分片 / 24 条题集**上成立。\n");
        out.append("   本语料里被门控跳过的那 18 块中 **15 块是表行** ⇒ 这个差值主要量的是\n");
        out.append("   「表行该不该各自生成 HyQ」这一个设计选择，不是门控全部规则的总和。\n");
        out.append("⚠️ 两组共用**同一批 HyQ 文本**（生成一次、按门控过滤）—— 唯一的自变量就是门控，\n");
        out.append("   因此这个差值里不含生成波动。\n");

        String report = out.toString();
        System.out.println("EVAL-HYQ-AB\n" + report);
        Files.writeString(Path.of("target", "hyq-gate-ab.txt"), report, StandardCharsets.UTF_8);

        // ⚠️ **刻意不设通过线**（与 `T-711` 同一条口径）：需求没给阈值，而"是多少"本身就是结论。
        // 设一条拍脑袋的线，只会得到一个"为了让用例变绿而调门控"的动机
        assertThat(full.recall()).isGreaterThan(0d);
        assertThat(gated.hyqBlocks())
                .as("门控后组必须真的**少**了一批 HyQ 块，否则两组是同一个东西，差值为 0 毫无意义")
                .isLessThan(full.hyqBlocks());
    }

    /**
     * 写一代分区、跑一遍 24 条题集。
     *
     * <p>⚠️ **换代而不是换库**：两组的基座分片是同一批 `sys_chunk` 行，
     * 换库会让"两组语料相同"这个前提要靠人工保证；换代则由 `sys_kb.index_generation`
     * 与分区命名一起保证（检索侧就是按它找分区的）。
     */
    private AbGroup measureGroup(int generation, String label, Set<String> keys,
                                 Map<String, Long> idByKey, QuerySet querySet) {
        this.generation = generation;
        jdbc.update("update sys_kb set index_generation = ? where id = ?", generation, kbId);
        chunkIds.clear();
        for (String key : keys) {
            chunkIds.put(key, idByKey.get(key));
        }
        writeGroupVectors(keys, idByKey);
        awaitGroupVisible();

        Map<String, double[]> byType = new LinkedHashMap<>();
        boolean first = true;
        for (QuerySpec q : querySet.queries()) {
            List<String> top = searchTopKFollowingSource(q.query(), TOP_K);
            if (first) {
                // 诊断：区分「keyOf 没映回来（top 里是裸 id）」与「映回来了但全是 HyQ 键
                // （HyQ 块把源块挤出了 top-5）」—— 两种都会让 recall 变成 0，但下一步完全不同
                first = false;
                System.out.println("AB-DIAG[" + label + "] Q=" + q.query()
                        + " golden=" + q.golden() + " top=" + top
                        + " chunkIdsSample=" + chunkIds.entrySet().stream().limit(3).toList());
            }
            Set<String> golden = new LinkedHashSet<>(q.golden());
            long hit = golden.stream().filter(top::contains).count();
            double recall = golden.isEmpty() ? 0d : (double) hit / golden.size();
            double[] acc = byType.computeIfAbsent(q.type(), k -> new double[2]);
            acc[0]++;
            acc[1] += recall;
        }
        Map<String, Double> perType = new LinkedHashMap<>();
        double all = 0;
        int n = 0;
        for (Map.Entry<String, double[]> e : byType.entrySet()) {
            perType.put(e.getKey(), e.getValue()[1] / e.getValue()[0]);
            all += e.getValue()[1];
            n += (int) e.getValue()[0];
        }
        int hyq = (int) keys.stream().filter(k -> k.startsWith("hyq:")).count();
        return new AbGroup(label, hyq, all / n, perType);
    }

    /** 把一组里**进召回池**的块写进当前代的分区（真嵌入）。 */
    private void writeGroupVectors(Set<String> keys, Map<String, Long> idByKey) {
        String partition = MilvusConstants.partitionName(kbId, generation);
        partitions.ensure(partition);
        Map<Long, List<VectorRecord>> byDoc = new LinkedHashMap<>();
        for (String key : keys) {
            long chunkId = idByKey.get(key);
            Object[] row = jdbc.queryForObject(
                    "select raw_content, chunk_type, title_path, parent_id, doc_id from sys_chunk where id = ?",
                    (rs, i) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getObject(4), rs.getLong(5)}, chunkId);
            Long parentId = row[3] == null ? null : ((Number) row[3]).longValue();
            VectorChunk vc = new VectorChunk(chunkId, (String) row[1], (String) row[0],
                    (String) row[2], "INTERNAL", parentId);
            String text = vc.vectorText();
            if (text == null) {
                continue;           // 表格父块不进召回池（`D-5` §1.4）
            }
            long docId = (Long) row[4];
            byDoc.computeIfAbsent(docId, k -> new ArrayList<>()).add(new VectorRecord(
                    vc, kbId, docId, embeddingClient.embed(List.of(text)).getFirst(), "BAAI/bge-m3", "eval"));
        }
        for (Map.Entry<Long, List<VectorRecord>> e : byDoc.entrySet()) {
            vectorWriter.replaceDocument(partition, e.getKey(), e.getValue());
        }
    }

    /**
     * 等这一代的向量**稳定可检索**。
     *
     * <h3>为什么不用 {@link #awaitVisible()}</h3>
     * <p>那个方法等的是"命中数 ≥ 期望数"，而期望数是**这一组全部可召回块**。
     * 两组加到十几种 HyQ 块之后，单次召回的上限是**两条通道各 top-20**
     * ⇒ 期望数可能结构上就够不到，于是它必然超时。
     *
     * <p>这里等的是**稳定**（连续三次读数不变）而不是某个数：`BOUNDED` 的写可见窗口
     * 是秒级（`#114` 实测），"还在涨"才是没写完的信号；停止增长之后再等一小会儿即可。
     * 等的东西必须与断言的东西同源 —— 断言的是召回，等的就是召回。
     */
    private void awaitGroupVisible() {
        int last = -1;
        int stable = 0;
        long start = System.nanoTime();
        long minNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() - start < java.util.concurrent.TimeUnit.SECONDS.toNanos(60)) {
            RetrievalScope.Scope scope = retrievalScope.resolve(reader(), List.of(kbId));
            RetrievalParams params = RetrievalParams.resolve(
                    new com.knowledge.ai.dto.kb.KbDtos.RetrievalConfig("HYBRID", 0.15, CANDIDATE_K, false, 1.2, true),
                    systemSettingService.snapshot());
            HybridRetriever retriever = new HybridRetriever(milvusClient, milvusProperties,
                    embeddingClient, Runnable::run);
            int now = retriever.recall("型号 单价 交期 起订量 报价 服务", scope, params).totalHits();
            if (now == last) {
                stable++;
                if (stable >= 3 && System.nanoTime() - start >= minNanos) {
                    System.out.println("EVAL HyQ-AB 写可见稳定：命中 " + now + " 条");
                    return;
                }
            } else {
                stable = 0;
                last = now;
            }
            sleep(300);
        }
        // **转红而不是继续**：读到的数字如果被写可见性低估，那两组都会偏低，
        // 而"门控值多少"这个差值就建立在一个不可信的地基上 ——
        // 那种结论比"没测"更糟（它会被人当成数据引用）
        throw new AssertionError("60 秒内这一代的向量仍未稳定可检索（最后一次 " + last
                + " 条）—— 本组召回率不可信，先查 Milvus 写入与分区，不要带着这个状态出结论");
    }

    /** 把语料块建成 `Block`（`orderNo` **全局唯一** —— 它要当 HyQ 回指源块的键）。 */
    private static List<Block> blocksOf(Corpus corpus, Map<String, Integer> orderByKey) {
        Map<String, Integer> orderOfParent = new LinkedHashMap<>();
        List<Block> blocks = new ArrayList<>();
        for (DocSpec doc : corpus.documents()) {
            for (ChunkSpec chunk : doc.chunks()) {
                Integer parentOrder = chunk.parentKey() == null ? null
                        : orderOfParent.get(chunk.parentKey());
                blocks.add(new Block(chunk.content(), BlockType.valueOf(chunk.chunkType()),
                        chunk.titlePath(), orderByKey.get(chunk.key()), 1, parentOrder, null, null));
                orderOfParent.put(chunk.key(), orderByKey.get(chunk.key()));
            }
        }
        return blocks;
    }

    /**
     * 给增强器造一份**最小可用**的文档。
     *
     * <p>⚠️ 它只喂**文档级摘要**那一跳 —— HyQ 是 `appendHypotheticalQuestions` 遍历
     * **`blocks` 参数**做的，与这份文档无关。所以这里不必把表格结构还原出来，
     * 只要"正文非空"即可：正文一空，`enhance` 会以 `EMPTY_INPUT` **提前返回**，
     * HyQ 一步都不会跑，而两组会静默地比出一个 0 差。
     */
    private static ParsedDocument evalDoc(Corpus corpus) {
        StringBuilder all = new StringBuilder();
        for (DocSpec doc : corpus.documents()) {
            for (ChunkSpec chunk : doc.chunks()) {
                all.append(chunk.content()).append('\n');
            }
        }
        String text = all.toString();
        return new ParsedDocument("PDF", 1, text.length(), text, List.of(), List.of(),
                1, 0, List.of(), List.of(ParsedDocument.DocElement.paragraph(text, 1)));
    }

    /** 建一个评测库（与 {@link #ingestCorpus} 同形，只是编码不同、且两组共用这一个）。 */
    private long insertEvalKb(String code) {
        jdbc.update("""
                        insert into sys_kb (code, name, dept_id, doc_count, chunk_count, status,
                                            secret_level, business_domain, reindex_required,
                                            index_generation, retrieval_config_json, created_at, updated_at)
                        values (?, ?, 1, 0, 0, 'READY', 'INTERNAL', '', 0, 1, ?, now(), now())
                        """, code, code, EVAL_RETRIEVAL_CONFIG);
        long id = jdbc.queryForObject("select id from sys_kb where code = ?", Long.class, code);
        jdbc.update("""
                        insert into sys_kb_grant (kb_id, principal_type, principal_id, level, created_at, updated_at)
                        values (?, 'USER', 1, 'MANAGE', now(), now())
                        """, id);
        return id;
    }

    /** 落一条增强块（摘要 / 章节摘要 / HyQ）。HyQ 要带 `source_chunk_id`（`V24`）。 */
    private long insertEnhancedChunk(long docId, Block block, Long sourceChunkId, int orderNo) {
        jdbc.update("""
                        insert into sys_chunk (doc_id, kb_id, chunk_type, parent_id, title_path,
                                               raw_content, content_sha256, source_chunk_id, order_no,
                                               page_no, created_at)
                        values (?, ?, ?, null, ?, ?, ?, ?, ?, 1, now())
                        """, docId, kbId, block.blockType().name(), block.titlePath(), block.rawContent(),
                com.knowledge.ai.core.text.ContentFingerprint.of(block.rawContent()),
                sourceChunkId, orderNo);
        return jdbc.queryForObject("select id from sys_chunk where doc_id = ? and order_no = ?",
                Long.class, docId, orderNo);
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
                EVAL_RETRIEVAL_CONFIG);
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

    /**
     * 同 {@link #keyOf}，但**命中 HyQ 块时跟着 `source_chunk_id` 落到源块**。
     *
     * <h3>为什么不改 {@link #keyOf} 而另开一个</h3>
     * <p>改了会**动到已有基线的数字**（`T-711` 与 `#117` 记下来的那些）——
     * 那是一次口径变更，要有自己的理由与记录，不能顺手改。
     *
     * <h3>为什么 A/B 必须用它（🔴 第一次跑就是这么错的）</h3>
     * <p>第一次 A/B 两组都量出 `Recall@5 = 0.000`，而同一份语料的基线是 0.935。
     * 根因**不是门控**，是量测口径：
     * <ul>
     *   <li>HyQ 块是**照着源块生成的问句**，天然比源块更贴自然提问 ⇒ 它会把源块
     *       **挤出 top-5**（这正是"瓶颈是 top-5 的名额"那个已知问题）；</li>
     *   <li>而 `HyqSourceService` 命中后只 **`chunk.withHyqSource(source)` 换内容**，
     *       `chunkId` **仍是 HyQ 块自己那个** ⇒ 旧的 {@code keyOf} 把它映成
     *       `hyq:&lt;源块orderNo&gt;`，**不在金标里** ⇒ 记成未命中。</li>
     * </ul>
     * 两条合起来：**检索明明找对了内容，却被记成没找到。**
     * 而 HyQ 的整个设计就是"问句只用于召回、正文来自源块"（`HyqSourceService` 的类注释）
     * ⇒ 口径本就该按**源块**算。不修这条，A/B 量的是"谁占了 top-5 的位子"，与判据无关。
     */
    private String keyOfFollowingSource(String chunkId) {
        long id;
        try {
            id = Long.parseLong(chunkId);
        } catch (NumberFormatException ex) {
            return chunkId;
        }
        Long source = sourceCache.get(id);
        if (source == null) {
            // ⚠️ **不要用 `queryForObject(..., Long.class)`**：源块 id 为 NULL 时它抛
            // `EmptyResultDataAccessException`；也不要用 `stream().findFirst()` ——
            // 那一行是 null 时 `Optional.of(null)` 直接 NPE（本方法第一版就是这么炸的）。
            // 用 `rs.wasNull()` 显式接住 NULL，再按"列表空/首元素空"两条分别判
            List<Long> rows = jdbc.query("select source_chunk_id from sys_chunk where id = ?",
                    (rs, i) -> {
                        long v = rs.getLong(1);
                        return rs.wasNull() ? null : v;
                    }, id);
            source = rows.isEmpty() ? null : rows.get(0);
            if (source != null) {
                // 只缓存**非空**的结果：缓存 null 会让"没查到"与"没缓存"在 get() 上
                // 长得一样，于是每条非 HyQ 的块都要重查一次库
                sourceCache.put(id, source);
            }
        }
        return keyOf(String.valueOf(source == null ? id : source));
    }

    /** {@link #keyOfFollowingSource} 的缓存（一次 A/B 里有 48×5 次查，逐次打库是浪费）。 */
    private final Map<Long, Long> sourceCache = new HashMap<>();

    /** 检索前 K 条，但 key 按 {@link #keyOfFollowingSource} 解（A/B 专用）。 */
    private List<String> searchTopKFollowingSource(String query, int k) {
        RetrievalResult result = retrievalService.search(reader(), query, List.of(kbId));
        List<String> keys = new ArrayList<>(k);
        for (RetrievedChunk chunk : result.chunks()) {
            if (keys.size() >= k) {
                break;
            }
            keys.add(keyOfFollowingSource(chunk.chunkId()));
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
