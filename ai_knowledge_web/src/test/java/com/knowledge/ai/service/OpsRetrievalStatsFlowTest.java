package com.knowledge.ai.service;

import com.knowledge.ai.support.ApiTestSupport;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 页 20 检索质量分析的**读侧**（`T-A03` · 需求 `08` `FR-OPS-02`）。
 *
 * <h3>判据对齐</h3>
 * <ul>
 *   <li>`G8-6` 链路耗时分解：`A + B + C == D`（检索侧 + 生成 + 其他 = 全链路）</li>
 *   <li>`G8-7` Top1 五档分布：**五档 + 无法定档 == 提问总数**（本批订正版，见下）</li>
 *   <li>需求 §2.4：重排前后两个命中率必须在**同一子集**上比（否则差值里混进覆盖率）</li>
 *   <li>需求 §2.8 第 4 条：各库合计 ≤ 提问总数（本批按"只归单库行"做到**精确相等**）</li>
 * </ul>
 *
 * <h3>🔴 需求 §2.8 有两条验收要点**不可能成立**，本类按订正后的口径验</h3>
 * <ol>
 *   <li>「**五档**合计 = 提问总数」—— 空召回的行（`G5-13`：**仍有一行**且 `top1_score` 为
 *       `NULL` 不是 0）落不进任何一档；未重排的行分数是融合分、与档位边界不同量纲。
 *       ⇒ 补第 6 档「无法定档」+ 把两个成因分开回，等式改为「五档 + 无法定档 == 总数」。</li>
 *   <li>「未命中档次数 = 11 页清单条数」—— 前者数**提问次数**、后者是**归一化聚合后的行数**
 *       （与需求 §1.10 那两条**同一个形状**，这是第三处）。本类不把两者当等式，只钉住
 *       **本页这一侧**的数（分档之和），并把订正建议写进结论文档。</li>
 * </ol>
 *
 * <h3>🔴 每条都自己造行</h3>
 * <p>本批开工实测 `sys_retrieval_trace` 是 **0 行**。而这一页的全部价值就在聚合
 * （分档、可比集、加权均值）—— 拿空库去验，验到的只是"空的时候不报错"。
 * `@BeforeEach` 与 `@AfterEach` **都**清窗口：起手干净，断言才敢写绝对数。
 */
@Slf4j
class OpsRetrievalStatsFlowTest extends ApiTestSupport {

    /** 与 `V2` 种子的 `retrieval.miss-threshold` 同值 —— 造行时**必须显式写**，
     * 否则列默认是 0，每一行都会被算成"达标"。 */
    private static final double MISS_THRESHOLD = 0.35;

    private long userId;
    private String token;

    @BeforeEach
    void setUpProbe() {
        clearTraceWindow();
        // ⚠️ 必须是**超管**：`ops:retrieval:view` 刻意只给超管（AUDITOR 拿不到 —— 那正是
        // 可证伪的边界，见 `V35`）。拿默认 USER 跑，五条用例全会 403，
        // 而现象是"接口没实现"（`QaMissFlowTest` 的类注释记了同一个坑）
        TestUser probe = createUser("or.probe");
        userId = userIdOf(probe.username());
        assignRole(userId, "SUPER_ADMIN");
        token = login(probe.username(), probe.password()).data("$.accessToken");
    }

    @AfterEach
    void removeFixtures() {
        clearTraceWindow();
    }

    // ==================== ③ 分档（G8-7）====================

    @Test
    @DisplayName("G8-7：五档 + 无法定档 = 提问总数（需求 §2.8 第 2 条的订正版）")
    void bucketsCoverEveryQuestionIncludingTheUnbucketable() {
        insertTrace("b1", 0.85, 0.30, true, "1", 1, false, 3, 10);   // 优秀
        insertTrace("b2", 0.70, 0.70, true, "1", 1, false, 3, 9);    // 良好
        insertTrace("b3", 0.50, 0.50, true, "1", 1, false, 3, 8);    // 一般
        insertTrace("b4", 0.37, 0.37, true, "1", 1, false, 3, 7);    // 边缘
        insertTrace("b5", 0.10, 0.10, true, "1", 1, true, 3, 6);     // 未命中
        insertTrace("b6", null, null, true, "1", 1, false, 0, 5);    // 空召回（有重排、无 Top1）
        insertTrace("b7", 0.02, null, false, "1", 1, false, 3, 4);   // 未重排（分数是融合量纲）

        Map<String, Object> res = distribution("7d");
        assertThat(num(res, "total")).as("七行全在窗口里").isEqualTo(7);

        assertThat(countOf(res, "优秀")).isEqualTo(1L);
        assertThat(countOf(res, "良好")).isEqualTo(1L);
        assertThat(countOf(res, "一般")).isEqualTo(1L);
        assertThat(countOf(res, "边缘")).isEqualTo(1L);
        assertThat(countOf(res, "未命中")).isEqualTo(1L);
        assertThat(countOf(res, "无法定档"))
                .as("🔴 空召回 + 未重排 —— 需求那五档装不下这两行，"
                        + "漏掉它们会让「五档合计 = 提问总数」看起来成立而实际少了 2")
                .isEqualTo(2L);

        long sum = listOf(res).stream().mapToLong(r -> ((Number) r.get("count")).longValue()).sum();
        assertThat(sum).as("各档合计**恰等于**提问总数（含无法定档那一档）")
                .isEqualTo(num(res, "total"));
        assertThat(num(res, "unscored")).as("无法定档的两个成因分开回").isEqualTo(2);
        assertThat(num(res, "noRerank")).isEqualTo(1);
        assertThat(num(res, "emptyRecall")).isEqualTo(1);
    }

    @Test
    @DisplayName("分档边界取需求 §2.3 的常量（0.35 那一档含下界、不含上界）")
    void bucketBoundariesAreHalfOpen() {
        insertTrace("c1", 0.35, 0.35, true, "1", 1, false, 1, 3);   // 边缘（含下界）
        insertTrace("c2", 0.40, 0.40, true, "1", 1, false, 1, 2);   // 一般（不含下界那一侧）

        Map<String, Object> res = distribution("7d");
        assertThat(countOf(res, "边缘")).as("0.35 属于「边缘」").isEqualTo(1L);
        assertThat(countOf(res, "一般")).as("0.40 属于「一般」").isEqualTo(1L);
        assertThat(countOf(res, "未命中")).as("0.35 **不**低于判定线 ⇒ 不是未命中").isZero();
    }

    // ==================== ① overview（可比集口径）====================

    @Test
    @DisplayName("🔴 未重排的行不进均值/命中率 —— 融合分与判定线不同量纲")
    void overviewExcludesFusionScaleRows() {
        insertTrace("o1", 0.90, 0.90, true, "1", 1, false, 4, 5);
        insertTrace("o2", 0.20, 0.20, true, "1", 1, true, 4, 4);
        // 未重排：融合分 0.99 —— 若被算进去，均值会跳到 0.70、命中率会跳到 66.7%
        insertTrace("o3", 0.99, null, false, "1", 1, false, 4, 3);

        Map<String, Object> res = overview("7d");
        assertThat(num(res, "totalQuestions")).isEqualTo(3);
        assertThat(num(res, "comparableQuestions")).as("可比集 = 重排过且有 Top1 的两行").isEqualTo(2);
        assertThat(num(res, "noRerankQuestions")).isEqualTo(1);
        assertThat(((Number) res.get("avgTopScore")).doubleValue())
                .as("(0.90 + 0.20) / 2 = 0.55 —— 把 0.99 算进去会给 0.6967")
                .isEqualTo(0.55, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(((Number) res.get("afterTop1HitRate")).doubleValue())
                .as("2 行里 1 行达标 = 50.0%（含未重排行会给 66.7%）")
                .isEqualTo(50.0, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    @DisplayName("🔴 空窗口回 null 而不是 0 —— 需求 §2.8 最后一条点名要的")
    void emptyWindowIsHonestNotNullZero() {
        // 不造任何行（`@BeforeEach` 已清窗口）
        Map<String, Object> res = overview("7d");

        assertThat(num(res, "totalQuestions")).isZero();
        assertThat(res.get("avgTopScore")).as("「没有数据」不是「平均分 0」").isNull();
        assertThat(res.get("afterTop1HitRate")).as("「没有数据」不是「命中率 0%」").isNull();
        assertThat(res.get("rerankGain")).as("「算不出来」不是「提升 0 个百分点」").isNull();
        assertThat(res.get("avgCitedChunks"))
                .as("分片数是**全提问**的均值 —— 一行都没有时同样没有数").isNull();

        Map<String, Object> dist = distribution("7d");
        assertThat(num(dist, "total")).isZero();
        assertThat(listOf(dist))
                .as("档位是闭集：没有数据时六档**仍然都在**（各 0），不是空数组")
                .hasSize(6);
    }

    // ==================== ④ rerank-effect（同子集）====================

    @Test
    @DisplayName("🔴 重排前后在**同一子集**上比 —— 否则差值里混进覆盖率")
    void rerankEffectComparesOnTheSameSubset() {
        insertTrace("e1", 0.90, 0.20, true, "1", 1, false, 3, 5);   // 覆盖：前未达标、后达标
        insertTrace("e2", 0.60, 0.50, true, "1", 1, false, 3, 4);   // 覆盖：前后都达标
        insertTrace("e3", 0.95, null, true, "1", 1, false, 3, 3);   // 重排过但**前值未覆盖**

        Map<String, Object> res = rerankEffect("7d");
        assertThat(num(res, "covered")).as("有前值的只有两行").isEqualTo(2);
        assertThat(num(res, "comparable")).as("可比集是三行（都重排过且有 Top1）").isEqualTo(3);
        assertThat(((Number) res.get("beforeTop1HitRate")).doubleValue())
                .as("1/2 —— 若用可比集当分母会给 33.3，那正是这条要挡的错误")
                .isEqualTo(50.0, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(((Number) res.get("afterTop1HitRate")).doubleValue())
                .as("2/2 —— 同样只在覆盖到的那两行上")
                .isEqualTo(100.0, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(((Number) res.get("gainPercent")).doubleValue())
                .as("净提升 = 后 − 前 = 50.0 个百分点")
                .isEqualTo(50.0, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    @DisplayName("一行前值都没有时净提升回 null（不是 0）")
    void rerankEffectIsNullWithoutCoverage() {
        insertTrace("f1", 0.90, null, true, "1", 1, false, 3, 3);

        Map<String, Object> res = rerankEffect("7d");
        assertThat(num(res, "covered")).isZero();
        assertThat(res.get("beforeTop1HitRate")).isNull();
        assertThat(res.get("afterTop1HitRate")).isNull();
        assertThat(res.get("gainPercent"))
                .as("「没有可比数据」与「提升 0 个百分点」是两件事").isNull();
    }

    // ==================== ② pipeline（G8-6）====================

    @Test
    @DisplayName("G8-6：检索侧 + 生成 + 其他 = 全链路（三段都是独立算出来的）")
    void pipelineThreePartsAddUp() {
        // 范围 10 / 查询 0 / 嵌入 20 / 向量 30 / BM25 40 / 融合 5 / 重排 50 / 回表 15 / 生成 1000
        // 合计 = 10+0+20+30+40+5+50+15+1000 = 1170
        // 检索侧（需求口径的 5 项：查询 + 嵌入 + 向量 + BM25 + 融合 + 重排）= 0+20+30+40+5+50 = 145
        // 其他 = 1170 − 145 − 1000 = 25（= 可见范围 10 + 表格回表 15）
        insertPipeline("p1", 10, 20, 30, 40, 5, 50, 15, 1000L, 1170);

        Map<String, Object> res = pipeline("7d");
        assertThat(num(res, "retrievalSideMs")).isEqualTo(145);
        assertThat(num(res, "generationMs")).isEqualTo(1000);
        assertThat(num(res, "otherMs"))
                .as("其他 = 可见范围 + 表格回表 + 未归段 —— 需求 §2.2 那 5 项里没有它俩，"
                        + "可它们是真花的钱；不给这一栏的话 `检索侧 + 生成` 对不上全链路，"
                        + "而差额没有任何一栏显示它")
                .isEqualTo(25);
        assertThat(num(res, "totalMs")).isEqualTo(1170);
        assertThat(num(res, "retrievalSideMs") + num(res, "generationMs") + num(res, "otherMs"))
                .as("🔴 三段相加恰等于全链路 —— 这是 `G8-6` 的落点")
                .isEqualTo(num(res, "totalMs"));
    }

    @Test
    @DisplayName("跑不到生成那一段的行**整行排除**，而不是把生成耗时当 0")
    void pipelineSkipsRowsWithoutGeneration() {
        insertPipeline("p1", 10, 20, 30, 40, 5, 50, 15, 1000L, 1170);
        // 空召回：没调模型 ⇒ `llm_generation_millis` 为 NULL。填 0 会把"没生成"说成"耗时 0ms"
        insertPipeline("p2", 999, 999, 999, 999, 999, 999, 999, null, 9999);

        Map<String, Object> res = pipeline("7d");
        assertThat(num(res, "pipelineRows")).as("只有 p1 跑完了全链路").isEqualTo(1);
        assertThat(num(res, "skippedNoGeneration")).as("被排除的那一行必须报出来").isEqualTo(1);
        assertThat(num(res, "retrievalSideMs")).as("p2 的 999 一点都没渗进来").isEqualTo(145);
        assertThat(num(res, "totalMs")).isEqualTo(1170);
    }

    @Test
    @DisplayName("九段段名与检索详情面板**逐字一致**（改一处两边一起改）")
    void pipelineStageNamesMatchTheDetailPanel() {
        insertPipeline("p1", 1, 1, 1, 1, 1, 1, 1, 10L, 20);

        List<String> names = listOf(pipeline("7d")).stream()
                .map(r -> String.valueOf(r.get("name"))).toList();
        assertThat(names)
                .as("与 `ChatWireMapper.stages()` 的 9 段同名同序 —— 两处各写一份的话，"
                        + "检索详情面板与这张图迟早对不上，而那时两个页面各自都自洽")
                .containsExactly("可见范围", "查询解析", "Embedding", "向量召回", "BM25 召回",
                        "RRF 融合", "Rerank", "表格回表", "LLM 生成");
    }

    @Test
    @DisplayName("没有采集点的「关注指标」如实回 null，不留 mock 里的示例值")
    void pipelineMetricsWithoutCollectorAreNull() {
        insertPipeline("p1", 1, 1, 1, 1, 7, 1, 1, 10L, 20);

        List<Map<String, Object>> list = listOf(pipeline("7d"));
        // 「去重后候选数」是唯一有采集点的（`fused_count`）
        Map<String, Object> fuse = list.stream()
                .filter(r -> "RRF 融合".equals(r.get("name"))).findFirst().orElseThrow();
        assertThat(String.valueOf(fuse.get("metricValue"))).isEqualTo("7");

        // 「改写成功率」后端没有这一步（`#145`）⇒ 值必须是 null，不是 96.4%
        Map<String, Object> query = list.stream()
                .filter(r -> "查询解析".equals(r.get("name"))).findFirst().orElseThrow();
        assertThat(query.get("metric")).isEqualTo("改写成功率");
        assertThat(query.get("metricValue"))
                .as("没有采集点 ⇒ 如实给 null（页面显示 —），不许填 mock 的示例值")
                .isNull();
    }

    // ==================== ⑤ kb-quality（归属口径）====================

    @Test
    @DisplayName("🔴 只归**单库**提问；跨库提问归「其他」——这样各库合计才 ≤ 提问总数")
    void kbQualityAttributesOnlySingleKbRows() {
        insertTrace("k1", 0.90, 0.90, true, "1", 1, false, 3, 5);
        insertTrace("k2", 0.20, 0.20, true, "1", 1, true, 3, 4);
        // 跨库提问：`kb_ids` 两个 id。若按 FIND_IN_SET 拆给每个库，它会**被算两次**
        insertTrace("k3", 0.50, 0.50, true, "1,2", 2, false, 3, 3);

        Map<String, Object> res = kbQuality("7d");
        assertThat(num(res, "questions")).as("单库行只有两行").isEqualTo(2);
        assertThat(num(res, "othersQuestions")).as("跨库那行在「其他」桶里").isEqualTo(1);

        List<Map<String, Object>> list = listOf(res);
        Map<String, Object> others = list.stream()
                .filter(r -> String.valueOf(r.get("kbName")).startsWith("其他"))
                .findFirst().orElseThrow();
        assertThat(((Number) others.get("questions")).longValue()).isEqualTo(1);

        long sum = list.stream().mapToLong(r -> ((Number) r.get("questions")).longValue()).sum();
        assertThat(sum).as("各库 + 其他 == 单库行 + 跨库行 —— 比需求 §2.8 第 4 条的「≤」更强")
                .isEqualTo(num(res, "questions") + num(res, "othersQuestions"));
    }

    @Test
    @DisplayName("走势：样本不足回 UNKNOWN（不是 STABLE）；样本够了按未命中率的两半差判 DOWN")
    void kbQualityTrendNeedsSamplesAndFollowsMissRate() {
        // 前半（> 3 天前）6 行全达标；后半（< 3 天前）6 行全未命中 ⇒ 未命中率从 0% 涨到 100%
        // ⚠️ 7 天窗口的中点是 `now - 3d`（`TimeWindow#mid`）
        for (int i = 0; i < 6; i++) {
            insertTraceDays("h1-" + i, 0.90, 0.90, true, "1", 1, false, 3, 5);
        }
        for (int i = 0; i < 6; i++) {
            insertTraceDays("h2-" + i, 0.10, 0.10, true, "1", 1, true, 3, 1);
        }

        Map<String, Object> row = listOf(kbQuality("7d")).stream()
                .filter(r -> !String.valueOf(r.get("kbName")).startsWith("其他"))
                .findFirst().orElseThrow();
        assertThat(String.valueOf(row.get("trend")))
                .as("未命中率从 0% 涨到 100% ⇒ 质量在降 = DOWN（不是 UP —— 语义写反了就抓不到"
                        + "需求 §2.5 最想区分的「稳定偏低 vs 持续下降」）")
                .isEqualTo("DOWN");

        // 只留一行 ⇒ 每半区样本不足
        clearTraceWindow();
        insertTraceDays("only", 0.90, 0.90, true, "1", 1, false, 3, 5);
        Map<String, Object> thin = listOf(kbQuality("7d")).getFirst();
        assertThat(String.valueOf(thin.get("trend")))
                .as("🔴 样本不足回 UNKNOWN，**不是** STABLE —— 把「没数据」说成「稳定」"
                        + "正好会让这条判据失效")
                .isEqualTo("UNKNOWN");
        assertThat(thin.get("issue"))
                .as("「主要问题」要靠文档质量列/解析问题表，与检索打点是两张表 ⇒ 不猜")
                .isNull();
    }

    // ==================== 权限（V35 的可证伪边界）====================

    @Test
    @DisplayName("`ops:retrieval:view` 只给超管：AUDITOR 五个端点全 403")
    void readSidePermission() {
        TestUser auditor = createUser("or.aud");
        assignRole(userIdOf(auditor.username()), "AUDITOR");
        String auditorToken = login(auditor.username(), auditor.password()).data("$.accessToken");

        // AUDITOR 在页 11 是通的（`ops:qa:view`），在页 20 不通 —— 两个码射程不同，
        // 所以这个新码不是"谁也区分不出"的空码
        assertThat(get("/api/ops/qa/overview?range=7d", auditorToken).code())
                .as("AUDITOR 有 ops:qa:view").isZero();

        for (String path : List.of("overview", "pipeline", "score-distribution",
                "rerank-effect", "kb-quality")) {
            assertThat(get("/api/ops/retrieval/" + path + "?range=7d", auditorToken).code())
                    .as("AUDITOR 没有 ops:retrieval:view：" + path).isNotZero();
        }
        assertThat(get("/api/ops/retrieval/overview?range=7d", token).code())
                .as("超管走 `*` 通配，五个端点都通").isZero();
        assertThat(get("/api/ops/retrieval/overview?range=7d").code())
                .as("无 token 一律 401（`get(path)` 单参就是不带 token 的那个重载）").isNotZero();
    }

    // ==================== 内部 ====================

    private void insertTrace(String traceId, Double top1, Double before, boolean applied,
                             String kbIds, int kbCount, boolean miss, int chunkCount, int minutesAgo) {
        insert(traceId, top1, before, applied, kbIds, kbCount, miss, chunkCount,
                "now() - interval ? minute", minutesAgo);
    }

    /** 窗口外/窗口内按**天**放（走势用例要控制前半 / 后半）。 */
    private void insertTraceDays(String traceId, Double top1, Double before, boolean applied,
                                 String kbIds, int kbCount, boolean miss, int chunkCount, int daysAgo) {
        insert(traceId, top1, before, applied, kbIds, kbCount, miss, chunkCount,
                "now() - interval ? day", daysAgo);
    }

    /**
     * 🔴 `miss_threshold` **必须显式写 0.35**：列默认是 0，而"达标"判定是
     * `top1_score >= miss_threshold` ⇒ 不写的话每一行都会被算成达标，用例全绿而口径全错。
     */
    private void insert(String traceId, Double top1, Double before, boolean applied,
                        String kbIds, int kbCount, boolean miss, int chunkCount,
                        String createdExpr, int ago) {
        jdbc.update("insert into sys_retrieval_trace "
                        + "(trace_id, query_text, user_id, rerank_applied, rerank_before_top1, "
                        + " kb_ids, kb_count, miss_threshold, miss_flag, top1_score, chunk_count, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, " + MISS_THRESHOLD + ", ?, ?, ?, "
                        + createdExpr + ")",
                traceId, "探针提问", userId, applied, before, kbIds, kbCount,
                miss, top1, chunkCount, ago);
    }

    /**
     * 一条**跑完全链路**的打点（`llm` 为 `null` = 空召回没调模型）。
     * `total` 由调用方给全 —— 用例要验的正是"三段相加等于它"，让它自己算就没意义了。
     */
    private void insertPipeline(String traceId, long scope, long embed, long vector, long bm25,
                                long fuse, long rerank, long table, Long llm, long total) {
        jdbc.update("insert into sys_retrieval_trace "
                        + "(trace_id, query_text, user_id, kb_ids, kb_count, rerank_applied, "
                        + " miss_threshold, top1_score, fused_count, chunk_count, "
                        + " scope_millis, embed_millis, vector_search_millis, bm25_recall_millis, "
                        + " fuse_millis, rerank_millis, table_millis, llm_generation_millis, total_millis) "
                        + "values (?, '探针提问', ?, '1', 1, 1, " + MISS_THRESHOLD + ", 0.90, ?, 3, "
                        + "?, ?, ?, ?, ?, ?, ?, ?, ?)",
                traceId, userId, fuse, scope, embed, vector, bm25, fuse, rerank, table, llm, total);
    }

    private Map<String, Object> overview(String range) {
        return get("/api/ops/retrieval/overview?range=" + range, token)
                .assertOk("检索质量四指标").data("$");
    }

    private Map<String, Object> pipeline(String range) {
        return get("/api/ops/retrieval/pipeline?range=" + range, token)
                .assertOk("链路耗时分解").data("$");
    }

    private Map<String, Object> distribution(String range) {
        return get("/api/ops/retrieval/score-distribution?range=" + range, token)
                .assertOk("相似度分档").data("$");
    }

    private Map<String, Object> rerankEffect(String range) {
        return get("/api/ops/retrieval/rerank-effect?range=" + range, token)
                .assertOk("重排效果").data("$");
    }

    private Map<String, Object> kbQuality(String range) {
        return get("/api/ops/retrieval/kb-quality?range=" + range, token)
                .assertOk("各库质量排行").data("$");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> res) {
        Object value = res.get("list");
        assertThat(value).as("响应里必须有数组 list").isInstanceOf(List.class);
        return (List<Map<String, Object>>) value;
    }

    /** 按**档位文案**取样（不按下标 —— 下标是展示顺序，断言写反了看不出来）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> bucket(Map<String, Object> res, String label) {
        return listOf(res).stream().filter(r -> label.equals(r.get("label")))
                .findFirst().orElseThrow(() -> new AssertionError("响应里没有档位 " + label));
    }

    /**
     * 按档位取次数。
     *
     * <p>⚠️ 不能写成 `assertThat(bucket(res, "优秀").get("count")).isEqualTo(1L)` ——
     * JSON 里的小整数解出来是 `Integer`，与 `Long` 比**永远不等**（而失败信息长得像逻辑错）。
     * 取数一律过 `Number#longValue`。
     */
    private static long countOf(Map<String, Object> res, String label) {
        return ((Number) bucket(res, label).get("count")).longValue();
    }

    private static long num(Map<String, Object> map, String key) {
        Object value = map.get(key);
        assertThat(value).as("响应里必须有字段 %s", key).isNotNull();
        return ((Number) value).longValue();
    }

    private long userIdOf(String name) {
        Long id = jdbc.queryForObject("select id from sys_user where username = ?", Long.class, name);
        assertThat(id).as("账号 %s 应该存在", name).isNotNull();
        return id;
    }

    /** 造号 → 换角色 → **重新登录**（角色在 JWT 里，不重登测的还是旧权限）。 */
    private void assignRole(long uid, String roleCode) {
        jdbc.update("delete from sys_user_role where user_id = ?", uid);
        jdbc.update("insert into sys_user_role (user_id, role_id, created_at) "
                + "select ?, id, now() from sys_role where code = ?", uid, roleCode);
        assertThat(jdbc.queryForObject("select count(*) from sys_user_role where user_id = ?",
                Integer.class, uid)).as("角色切换必须生效").isEqualTo(1);
    }

    /** 清 7 天窗口内的打点（与 `QaMissFlowTest` / `OpsQaStatsFlowTest` 同一个口径）。 */
    /**
     * 清 7 天窗口内的打点，**并且清掉按天汇总表的对应天**（`T-A04` 之后本类必须加这一步）。
     *
     * <h3>🔴 为什么这个类的清理要多清两张表 —— 全量跑批当场逮到的</h3>
     * <p>`S8-M10-E` 之后，本类读的五个端点**不再实时扫打点表**，改读按天汇总表
     * （`sys_ops_retrieval_daily`）。而那张表的失效判据是**日期覆盖**（`min/max(stat_date)`），
     * **不是"源变了没有"** —— 于是本类这种「直接 delete 打点行、再插一批新的」的用法
     * 会读到**上一批数据的残影**：汇总行还在，而打点已经换了。
     *
     * <p>现象恰好落在 `kbQualityTrendNeedsSamplesAndFollowsMissRate` 上：它后半段
     * 清空后只插一行、期望走势是 `UNKNOWN`（两半区样本都不足），而实际得到 `DOWN`
     * —— 因为前半区那 6 行"全达标"与后半区那 6 行"全未命中"还留在汇总表里。
     * ⚠️ **它看起来很像个产品缺陷，其实是一条清理没跟上新增产物**
     * （与 `#130` 那一族同形，但更隐蔽：不是"留下了行"，而是"**留下了旧数**"）。
     *
     * <p>生产侧不受影响：打点是**只增**的，没有谁会在一段窗口内删掉它
     * （留存清理属于 `T-306` 那条线，且远在窗口之外）。
     */
    private void clearTraceWindow() {
        jdbc.update("delete from sys_retrieval_trace_chunk where trace_id in "
                        + "(select trace_id from sys_retrieval_trace where created_at >= ?)",
                LocalDateTime.now().minusDays(7));
        jdbc.update("delete from sys_retrieval_trace where created_at >= ?",
                LocalDateTime.now().minusDays(7));
        // 汇总表是**缓存**：打点删了它必须跟着删，否则读侧读的是旧数（见 javadoc）
        jdbc.update("delete from sys_ops_retrieval_daily where stat_date >= curdate() - interval 7 day");
        jdbc.update("delete from sys_ops_usage_daily where stat_date >= curdate() - interval 7 day");
    }
}
