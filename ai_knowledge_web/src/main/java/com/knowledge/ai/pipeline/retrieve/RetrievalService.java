package com.knowledge.ai.pipeline.retrieve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledge.ai.core.ai.RerankClient;
import com.knowledge.ai.core.ai.SiliconFlowRerankClient;
import com.knowledge.ai.core.ai.ModelClientRegistry;
import com.knowledge.ai.core.enums.ModelType;
import com.knowledge.ai.core.enums.RetrievalMode;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.setting.SystemSettingService;
import com.knowledge.ai.core.setting.SystemSettings;
import com.knowledge.ai.pipeline.graph.GraphFeatureToggle;
import com.knowledge.ai.pipeline.graph.GraphRetriever;
import com.knowledge.ai.service.ModelUsageRecorder;
import com.knowledge.ai.service.RebuildStateService;
import com.knowledge.ai.dto.kb.KbDtos;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static com.knowledge.ai.config.AsyncConfig.RETRIEVAL_EXECUTOR;

/**
 * 检索编排（`T-700`~`T-704` 的缝合处）—— **`M08` 的唯一入口**。
 *
 * <h3>一条链路的六步</h3>
 * <ol>
 *   <li>{@link RetrievalScope} —— MySQL 先算可见范围（`I-9`）；</li>
 *   <li>{@link RetrievalParams} —— 库级配置 + 全局阈值合成；</li>
 *   <li>{@link HybridRetriever} —— 两路并行召回 top-20；</li>
 *   <li>{@link GraphRetriever} —— 图谱旁路（`T-C02`，第三条召回路径，**可关可超时**）；</li>
 *   <li>{@link RrfFusion} —— 倒数排名融合、按 chunkId 去重；</li>
 *   <li>{@link RerankClient} —— 精排（可降级）；</li>
 *   <li>`T-704` 契约 —— {@link RetrievalResult}。</li>
 * </ol>
 * <p>与计划 §3.2 的流程图逐节点对应。**本类只做编排**：每一步的判据都在各自的类里，
 * 这里不重新实现任何一条 —— 那样等于把同一件事写两遍，而两遍迟早不一致。
 *
 * <h3>未命中判定为什么只在"重排真的跑过"时才做</h3>
 * <p>{@code V2} 的种子注释写得很明确：「**Rerank 后**最高分低于此值判定未命中」。
 * 这不是一句可以忽略的限定：重排分在 0~1，而 RRF 融合分在 {@code 1/61}≈0.016 量级 ——
 * 拿后者去比 {@code missThreshold=0.35}，**每一次检索都会被判成未命中**。
 * 于是本类把"降级"与"未命中"这两件事**绑成互斥**：没重排，就没有未命中判定
 * （{@link RetrievalTrace#miss()} 恒 {@code false}，且 {@code rerankNote} 写明原因）。
 * 降权提示线（{@code lowScoreWarn}）同理 —— 见 {@link RetrievedChunk#scoreComparable()}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PersistenceContext
    private EntityManager entityManager;

    private final RetrievalScope retrievalScope;

    private final HybridRetriever hybridRetriever;

    private final ModelClientRegistry modelClientRegistry;

    private final SystemSettingService systemSettingService;

    /** `T-705`：把命中的表格子块换成它所在的表（`G5-6`）。 */
    private final TableLookupService tableLookupService;

    /** `T-507` 的 HyQ 半边：命中假设问句时换成源块正文（见该类的 javadoc）。 */
    private final HyqSourceService hyqSourceService;

    /** `T-709`：重建索引期间降级为关键词检索。 */
    private final RebuildStateService rebuildStateService;

    /** `T-905` 的用量落点。见 {@link #recordRerankUsage}。 */
    private final ModelUsageRecorder usageRecorder;

    /** `T-C02`：图谱旁路（实体链接 → 1~2 跳 → 可见性过滤 → 回源）。 */
    private final GraphRetriever graphRetriever;

    /**
     * 图谱旁路开关（`T-C03` 起 = `sys_setting` 的 `retrieval.graph-bypass-enabled`）。
     *
     * <p>⚠️ **不再是字段注入的 {@code @Value}**：那个属性只在 bean 构造期读一次，
     * 于是 `PUT /system/feature/graph` 打开/关闭之后**必须重启才生效**——
     * 而需求 `G9-1`/I-16 要的是"关得掉、可回滚"。改为每次检索问一次
     * {@link GraphFeatureToggle}（一次 KV 主键查询）。
     *
     * <p>关掉时**连异步任务都不提交**（判据 1 的"零开销"）。
     */
    private final GraphFeatureToggle graphFeatureToggle;

    /** 图谱旁路的整体预算（`m05.graph.retrieve.timeout-ms`；需求 `07:522` 给的是 200ms）。 */
    @Value("${m05.graph.retrieve.timeout-ms:200}")
    private long graphTimeoutMs;

    /**
     * 图谱旁路**与主链路并行**发起用的池 —— 刻意复用 `HybridRetriever` 同一个
     * （见 {@link com.knowledge.ai.config.AsyncConfig#retrievalExecutor} 上关于
     * **traceId 装饰器**的硬约束：少了它，"这一次请求花了多久"就拼不起来）。
     */
    @Autowired
    @Qualifier(RETRIEVAL_EXECUTOR)
    private Executor retrievalExecutor;

    /**
     * 检索。
     *
     * @param user    登录用户。**不用 {@code LoginUserContext}** —— 检索会被 M08 从
     *                异步链路里调（生成任务在工作线程上跑，而 ThreadLocal 不跨线程，
     *                见 {@code LoginUserContext} 的类注释）。显式传参是唯一安全的口径
     * @param query   用户提问原文
     * @param kbIds   限定知识库；{@code null}/空 = 该用户可见的全部库
     * @return 冻结的对外契约（`T-704`）
     */
    public RetrievalResult search(LoginUser user, String query, List<Long> kbIds) {
        long start = System.nanoTime();
        if (user == null || user.getUserId() == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请先输入问题再进行检索");
        }

        // ---------- ① MySQL 先算可见范围（I-9）----------
        long scopeStart = System.nanoTime();
        RetrievalScope.Scope scope = retrievalScope.resolve(user, kbIds);
        long scopeMillis = elapsed(scopeStart);
        if (scope.empty()) {
            // 没有可见的库或没有分区 —— **不去连 Milvus**，直接给一个可解释的空结果
            return emptyResult(trimmed, scope, scopeMillis, start,
                    scope.kbIds().isEmpty() ? "没有可检索的知识库" : "可见知识库下还没有已索引的分区");
        }

        // ---------- ② 合成参数 ----------
        SystemSettingService.Snapshot settings = systemSettingService.snapshot();
        RetrievalParams params = RetrievalParams.conservative(paramsOf(scope.kbIds(), settings));

        // ---------- ②.5 重建期降级（T-709）----------
        // 需求《11》§2.1：「重建索引期间 —— **降级而非中断**：可切「降级为关键词检索」，
        // 问答仍可用（精度下降）」。所以这里不拒绝请求，只把通道收窄到 BM25。
        Degrade degrade = degradeIfRebuilding(scope.kbIds(), settings, params);
        params = degrade.params();
        String degradeNote = degrade.note();

        // ---------- ③ 两路并行召回 ----------
        long recallStart = System.nanoTime();
        // ---------- ③.5 图谱旁路（T-C02）：**先发起**，与主链路并行跑 ----------
        // 开关关 ⇒ 连任务都不提交（判据 1 要的"零开销"）；跑起来后整体压进 `timeout-ms`
        // （默认 200ms），超时/异常**静默降级**：问答不受影响（需求 `07:522`）
        long graphStart = System.nanoTime();
        CompletableFuture<GraphRetriever.GraphRecall> graphFuture = graphFeatureToggle.enabled()
                ? CompletableFuture.supplyAsync(() -> graphRetriever.recall(trimmed, scope), retrievalExecutor)
                : null;

        HybridRetriever.Recall recall = hybridRetriever.recall(trimmed, scope, params);
        long recallMillis = elapsed(recallStart);

        GraphRetriever.GraphRecall graph = resolveGraph(graphFuture, graphStart);

        // ---------- ④ RRF 融合 ----------
        long fuseStart = System.nanoTime();
        // ⚠️ 图谱**不多占一步加权**：它作为第三路进同一个 `fuse`，只按名次计分
        // （需求 `07:508`：去重合并进 Rerank，不单独加权）。没跑（开关关）时**只传两路**
        // —— 判据 1 要的正是这个形状
        List<RrfFusion.FusedHit> fused = graph == null
                ? RrfFusion.fuse(RrfFusion.DEFAULT_K, recall.vectorHits(), recall.bm25Hits())
                : RrfFusion.fuse(RrfFusion.DEFAULT_K, recall.vectorHits(), recall.bm25Hits(), graph.hits());
        long fuseMillis = elapsed(fuseStart);

        // ---------- ⑤ 精排（可降级）----------
        RerankOutcome rerank = rerank(trimmed, fused, params);

        // 重排前的 Top1（`T-A03`）：融合池 rank-1 那条**在重排结果里**拿到的分
        // （= `rerank.chunks()` 里 `fusionRank == 1` 那条的 `rerankScore`）。
        // ⚠️ 取的是**重排出口**而不是最终 `chunks`：⑤.5 的回表 / HyQ 回落会换内容与实例，
        // 而"重排前是多少"不该取决于后面那几步。
        // 重排前那条未进重排 `top_n` 时**取不到** ⇒ null（覆盖率由端点单独报，
        // 见 `V35` 的列注释：NULL 有两种含义，靠 `rerank_applied` 区分）。
        Double rerankBeforeTop1 = rerankBeforeTop1(rerank.chunks());

        // ---------- ⑤.5 表格回表（T-705 · G5-6）----------
        // 位置在精排**之后**，两个理由：
        //   ① 窗口要"命中哪一行"，而命中哪几条是精排 + 裁剪才定下来的 —— 放前面会为
        //      一堆随后被裁掉的分片白查父块（N+1 的代价白付一半）；
        //   ② 回表**不改名次也不改分数**，所以它不会让精排的结果失真。
        // 它只换内容：命中子块（一行）→ 父块或其行窗口（`TableLookup`）。
        long tableStart = System.nanoTime();
        List<RetrievedChunk> chunks = tableLookupService.apply(rerank.chunks());
        // ---------- ⑤.6 HyQ 回落（T-507 的挂起半边 · 汇总 §4-271）----------
        // 与表格回表**同一类动作**（把"检索到的那条"换成"该喂给模型的那份"），
        // 所以计在同一段耗时里。⚠️ 不为它单开一段：`G5-12` 要的是
        // `totalMs ≈ 各段之和`，而"回表"这个词本来就覆盖这两种查找
        // （区别只是触发它的块类型不同）。多开一段反而会让那个等式失衡
        chunks = hyqSourceService.apply(chunks);
        long tableMillis = elapsed(tableStart);

        // ---------- ⑥ 契约 ----------
        int kbCount = scope.kbIds().size();
        long totalMillis = elapsed(start);

        RetrievalTrace trace = new RetrievalTrace(trimmed, params.mode(), scope.kbIds(), kbCount,
                scope.docScopeCount(), scope.docFallback(),
                recall.vectorHits().size(), recall.bm25Hits().size(), fused.size(),
                scopeMillis, recallMillis, recall.embedMillis(), recall.vectorSearchMillis(),
                recall.bm25Millis(), fuseMillis, rerank.millis(), tableMillis, totalMillis,
                params.recallTopK(), params.recallThreshold(), params.rerankTopK(),
                params.rerankEnabled(), params.tableWeight(), params.missThreshold(),
                params.lowScoreWarn(), rerank.applied(), rerank.note(),
                // 图谱四格（判据 8 的四种状态都落在这四列上）：`graphUsed` = 本次是否
                // 真的贡献了分片；`graphMillis` 为 0 表示**没跑**（开关关），跑了就会 > 0；
                // `graphEntities` 空集写 NULL（"缺席不是空值"，照 `S3-M03-A` 的既有口径）
                degradeNote, rerankBeforeTop1,
                graph != null && !graph.hits().isEmpty(),
                graph == null ? 0L : graph.millis(),
                graph != null && graph.fallback(),
                graph == null || graph.entities().isEmpty() ? null : graph.entities(),
                rerank.miss(chunks, params), chunks);

        log.info("[检索] 完成：query={} 库数={} 可见文档={} 向量={} BM25={} 融合={} 返回={} "
                        + "重排={} 回表={} 未命中={} 耗时={}ms（范围 {} + 召回 {} + 融合 {} + 重排 {} + 回表 {}）",
                abbreviate(trimmed), kbCount, scope.docScopeCount(),
                recall.vectorHits().size(), recall.bm25Hits().size(), fused.size(), chunks.size(),
                rerank.applied() ? "已执行" : "未执行", tableWindowCount(chunks), trace.miss(), totalMillis,
                scopeMillis, recallMillis, fuseMillis, rerank.millis(), tableMillis);

        return new RetrievalResult(chunks, trace, kbCount);
    }

    // ==================== 精排 ====================

    /**
     * 第 ⑤ 步：精排 + 最终裁剪。
     *
     * <p>三条降级路径**都要写明原因**（{@link RerankOutcome#note()}）：
     * 库配置关掉了 / 没配重排模型 / 调用失败。它们的结果相同（都用融合分），
     * 但**下一步动作完全不同** —— 第一种什么都不用做，第二种要去配模型，
     * 第三种要去看重排服务的健康。合并成一个"未重排"会让第二、三种永远没人处理。
     */
    private RerankOutcome rerank(String query, List<RrfFusion.FusedHit> fused, RetrievalParams params) {
        if (fused.isEmpty()) {
            return new RerankOutcome(List.of(), false, 0L, "没有候选可重排");
        }
        if (!params.rerankEnabled()) {
            return new RerankOutcome(toChunks(fused, params.rerankTopK(), null, params), false, 0L,
                    "该知识库关闭了重排（rerankEnabled=false）");
        }

        RerankClient client = modelClientRegistry.rerankClient();
        if (!client.available()) {
            return new RerankOutcome(toChunks(fused, params.rerankTopK(), null, params), false, 0L,
                    "未配置重排模型 —— " + client.describe());
        }

        long start = System.nanoTime();
        List<String> documents = fused.stream().map(RrfFusion.FusedHit::content).toList();
        List<RerankClient.RerankHit> hits;
        try {
            hits = client.rerank(query, documents, params.rerankTopK());
        } catch (SiliconFlowRerankClient.RerankFailedException ex) {
            // **降级而不失败**：Rerank 是锦上添花的一跳，它挂了不该让用户问不出答案。
            // 但必须让人看得见 —— 所以原因进 trace，日志进 warn
            log.warn("[检索] 重排失败，本次走不重排降级：{}", ex.getMessage());
            return new RerankOutcome(toChunks(fused, params.rerankTopK(), null, params), false,
                    elapsed(start), "重排调用失败，已降级为不重排：" + ex.getMessage());
        }
        long millis = elapsed(start);
        recordRerankUsage(millis);

        if (hits.isEmpty()) {
            // 服务通了但一条都没返回 —— 与"调用失败"是两回事，不要混为一谈
            return new RerankOutcome(toChunks(fused, params.rerankTopK(), null, params), false, millis,
                    "重排服务返回了空结果，已降级为不重排");
        }

        Map<String, Double> rerankScores = new java.util.HashMap<>(hits.size());
        for (RerankClient.RerankHit hit : hits) {
            // 下标 → 融合池位置 → chunkId。**越界在这里也再判一次**：
            // 客户端已经筛过一轮，但这条映射是"分数挂到哪个分片上"，错一位就是张冠李戴
            if (hit.index() < 0 || hit.index() >= fused.size()) {
                log.warn("[检索] 跳过越界的重排下标：index={} 融合池={}", hit.index(), fused.size());
                continue;
            }
            rerankScores.put(fused.get(hit.index()).chunkId(), hit.score());
        }
        if (rerankScores.isEmpty()) {
            return new RerankOutcome(toChunks(fused, params.rerankTopK(), null, params), false, millis,
                    "重排结果与候选对不上（下标全部越界），已降级为不重排");
        }
        return new RerankOutcome(toChunks(fused, params.rerankTopK(), rerankScores, params), true, millis,
                "");
    }

    /**
     * 融合池 → 最终分片。
     *
     * @param rerankScores 非 {@code null} 表示**真的重排过**（哪怕只有一部分分片拿到分）；
     *                     {@code null} 表示没重排，此时用融合分、不做阈值比较
     */
    /**
     * 记一次重排用量（`T-905`）。
     *
     * <p>⚠️ **只记次数与耗时，不记 token**：重排接口（`SiliconFlowRerankClient`）返回的是
     * 「候选 + 相关度分数」，**没有 token 用量**。硬凑一个（比如拿候选字符数当 token）
     * 会让 `byPurpose` 里出现一个与其它桶不同量纲的数 —— 那比留 0 更误导。
     * 重排的计费口径本来也多为 {@code PER_CALL}（按次），计次就够算钱了。边界登记在计划 §9。
     *
     * <p>⚠️ 只在**真的调用了模型**的那条路上记：调用失败与"未配置"两条降级路径
     * 都在上面提前 return 了，一次请求都没发出去 —— 记进去会让"这个模型这个月调了 N 次"变成假的。
     */
    private void recordRerankUsage(long millis) {
        try {
            usageRecorder.record(modelClientRegistry.activeSpec(ModelType.RERANK),
                    ModelUsageRecorder.Purpose.RERANK, 1, 0L, 0L,
                    (int) Math.min(millis, Integer.MAX_VALUE));
        } catch (Exception ex) {
            log.warn("[检索] 重排用量记录失败（不影响本次检索）：原因={}", ex.getMessage());
        }
    }

    private static List<RetrievedChunk> toChunks(List<RrfFusion.FusedHit> fused, int topK,
                                                 Map<String, Double> rerankScores,
                                                 RetrievalParams params) {
        List<RetrievedChunk> out = new ArrayList<>(Math.min(topK, fused.size()));
        for (RrfFusion.FusedHit hit : fused) {
            Double rawRerank = rerankScores == null ? null : rerankScores.get(hit.chunkId());
            // 表格加权（tableWeight）只作用在**重排分**上：融合分是排名之和，
            // 乘一个系数会同时放大它在两路里的名次贡献，那不是"表格加权"而是"表格刷榜"
            boolean table = "TABLE".equals(hit.blockType());
            double weighted = rawRerank == null ? hit.score()
                    : rawRerank * (table ? params.tableWeight() : 1.0d);
            boolean comparable = rawRerank != null;
            boolean warn = comparable && weighted < params.lowScoreWarn();

            out.add(new RetrievedChunk(hit.chunkId(), hit.docId(), hit.kbId(), hit.blockType(),
                    hit.titlePath(), hit.content(),
                    weighted,
                    comparable ? RetrievedChunk.ScoreKind.RERANK : RetrievedChunk.ScoreKind.FUSION,
                    // rank 先按加权分排完再回填，这里给占位值
                    0,
                    hit.rank(),
                    rawRerank,
                    weighted,
                    hit.channelRanks(),
                    warn,
                    // `T-705`：表格上下文由第 ⑤.5 步（`tableLookupService.apply`）补。
                    // 这里**必须**给 null —— 回表放在精排之后，这一层还不知道哪几条会被留下
                    null));
        }

        // 重排过就按加权分重排；没重排时保持融合名次（融合分本来就是降序的）
        if (rerankScores != null) {
            out.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed()
                    .thenComparingInt(RetrievedChunk::fusionRank));
        }
        List<RetrievedChunk> cut = out.size() > topK ? new ArrayList<>(out.subList(0, topK)) : out;

        List<RetrievedChunk> ranked = new ArrayList<>(cut.size());
        for (int i = 0; i < cut.size(); i++) {
            ranked.add(cut.get(i).withRank(i + 1));
        }
        return List.copyOf(ranked);
    }

    // ==================== 参数与范围 ====================

    /** 逐库读检索配置（{@code sys_kb.retrieval_config_json}），一个库一条。 */
    private List<RetrievalParams> paramsOf(List<Long> kbIds, SystemSettingService.Snapshot settings) {
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager.createNativeQuery(
                        "select retrieval_config_json from sys_kb where id in (:kbIds) order by id")
                .setParameter("kbIds", kbIds)
                .getResultList();

        List<RetrievalParams> out = new ArrayList<>(rows.size());
        for (Object raw : rows) {
            out.add(RetrievalParams.resolve(parseConfig((String) raw), settings));
        }
        if (out.isEmpty()) {
            // 理论上到不了（scope 已保证 kbIds 非空），但兜一手：宁可给默认参数也不要空列表
            out.add(RetrievalParams.resolve(null, settings));
        }
        return out;
    }

    /**
     * 解析库级检索配置。**解析失败不抛** —— 一行历史配置写坏了不该让整个库搜不出东西：
     * 退化为默认值并留 warn（与 {@link RetrievalParams} 对越界值的处置同一条口径）。
     */
    private static KbDtos.RetrievalConfig parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, KbDtos.RetrievalConfig.class);
        } catch (Exception ex) {
            log.warn("库级检索配置解析失败，本次使用默认值：json={} err={}", json, ex.getMessage());
            return null;
        }
    }

    /**
     * 重建期降级（`T-709`）—— 决定本次是否把通道收窄到只走 BM25。
     *
     * <p>三条**不降级**的理由，各自独立：
     * <ol>
     *   <li>全局开关关着（`sys_setting` 的 `retrieval.degrade-during-reindex`）——
     *       需求把「重建期间的服务状态」做成二选一，这是"另一个选项"那一侧；</li>
     *   <li>本次本来就走 `KEYWORD` —— 没有可降的东西。**这条不是优化，是正确性**：
     *       不判它的话「本来就只有关键词」会被报成一次降级，而 trace 上写着
     *       "因重建降级"——那是一句假话，运维会去查一个根本没发生的降级；</li>
     *   <li>参与检索的库里没有正在重建的。</li>
     * </ol>
     */
    private Degrade degradeIfRebuilding(List<Long> kbIds, SystemSettingService.Snapshot settings,
                                        RetrievalParams params) {
        if (!settings.bool(SystemSettings.RETRIEVAL_DEGRADE_DURING_REINDEX,
                SystemSettings.DEFAULT_DEGRADE_DURING_REINDEX)) {
            return Degrade.none(params);
        }
        if (params.mode() == RetrievalMode.KEYWORD) {
            return Degrade.none(params);
        }
        if (!rebuildStateService.isAnyRebuilding(kbIds)) {
            return Degrade.none(params);
        }

        String note = "知识库正在重建索引，本次已降级为关键词检索（" + params.mode().getLabel()
                + " → 关键词检索）。精度下降但问答仍可用，重建完成后自动恢复";
        log.warn("[检索] 重建期降级：{} → KEYWORD，库={}", params.mode(), kbIds);
        return new Degrade(params.withMode(RetrievalMode.KEYWORD), note);
    }

    /**
     * 等图谱旁路的结果：**超时/异常一律静默降级**成空结果（不抛、不影响主链路）。
     *
     * <p>{@code future == null} 只有一个含义：**本次压根没跑**（开关关）。调用方据此
     * 判断"要不要把第三路交给 {@link RrfFusion}"与"该不该下发 {@code graphCostMs}"
     * —— 那两件事都以「跑没跑」为准，而不是以「有没有命中」为准（判据 1）。
     *
     * <p>⚠️ 超时后不取消底层任务：那一步多半正卡在 Neo4j 的套接字上，
     * 且叶子自己带了 `TransactionConfig` 超时；这里只保证**主链路不等它**。
     */
    private GraphRetriever.GraphRecall resolveGraph(CompletableFuture<GraphRetriever.GraphRecall> future,
                                                   long start) {
        if (future == null) {
            return null;
        }
        try {
            return future.orTimeout(graphTimeoutMs, TimeUnit.MILLISECONDS).join();
        } catch (CompletionException | CancellationException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            return new GraphRetriever.GraphRecall(List.of(), elapsed(start), true,
                    "图谱旁路超时/异常，已静默降级：" + cause.getClass().getSimpleName(), List.of());
        }
    }

    /** 降级的结果：换过的参数 + 说明（没有降级时说明为空串）。 */
    private record Degrade(RetrievalParams params, String note) {

        static Degrade none(RetrievalParams params) {
            return new Degrade(params, "");
        }
    }

    /** 空结果：**仍然带完整 trace** —— "为什么什么都没搜到"必须能回答。 */
    private static RetrievalResult emptyResult(String query, RetrievalScope.Scope scope,
                                               long scopeMillis, long start, String note) {
        // 各段耗时全 0 是**真值**：这条路连 Milvus 都没去（没有可见的库 / 没有已索引的分区），
        // 所以"召回 0 条、耗时 0ms"如实。⚠️ 只有 scopeMillis 非 0 —— 那次 MySQL 确实跑了
        RetrievalTrace trace = new RetrievalTrace(query, null, scope.kbIds(), scope.kbIds().size(),
                scope.docScopeCount(), scope.docFallback(),
                0, 0, 0, scopeMillis, 0L, 0L, 0L, 0L, 0L, 0L, 0L, elapsed(start),
                0, 0d, 0, false, 0d, 0d, 0d, false, note, "",
                null, false, 0L, false, null, false, List.of());
        return new RetrievalResult(List.of(), trace, scope.kbIds().size());
    }

    /**
     * 本次有几条走了表格回表。
     *
     * <p>**从 {@code chunks} 派生，不另存一个计数** —— {@code trace.chunks} 与顶层
     * {@code chunks} 是同一个实例（`G5-9`），所以这个数在 trace 里也查得到，
     * 再存一份就是给同一个事实两个来源（它们迟早不一致）。
     * 这正是"同源由构造器强制"换来的好处：能派生的东西就不必存。
     */
    private static long tableWindowCount(List<RetrievedChunk> chunks) {
        return chunks.stream().filter(c -> c.tableContext() != null).count();
    }

    /**
     * 重排前 Top1 的**重排分**（`T-A03`）—— 融合池 rank-1 那条在重排结果里拿到的分。
     *
     * <p>取**重排分**而不是融合分：只有与 `top1_score` 同一把尺，两侧命中率才比得出
     * 「净提升」；融合分是 RRF 量级（≈0.016），与判定线 0.35 不同量纲
     * （见 `V35` 的列注释与 {@code RetrievalTraceWriter#top1} 的量纲警告）。
     *
     * <p>实现上**不另存一个字段**：`RetrievedChunk` 本来就带着 `fusionRank`（融合名次）
     * 与 `rerankScore`（那条拿到的重排分），而 `toChunks` 已经把它们都填好了 ——
     * 派生即可，与 {@link #tableWindowCount} 同一条纪律（能派生的就不必存）。
     *
     * <p>**取不到就返回 {@code null}**：重排前那条没进重排结果的 `top_n`
     * （客户端只返回 `top_n` 条，见 {@code RerankClient#rerank}）⇒ 它的重排分**未知**。
     * 未重排时 `rerankScore` 恒 null，同样落到这里。⚠️ 两种 NULL 靠 `rerank_applied` 区分，
     * **不许**用 0 顶替 —— 那会把"没测到"说成"重排前得了 0 分"。
     */
    private static Double rerankBeforeTop1(List<RetrievedChunk> chunks) {
        // ⚠️ 必须是 `findFirst().map(...)` 而不是 `map(...).findFirst()` ——
        // 后者在**映射结果为 null** 时直接抛 NPE（`Stream#findFirst` 不接受 null 元素），
        // 而"融合池 rank-1 没进重排结果"恰恰就是那个 null 的情形（重排开着的每一次都可能撞上）。
        return chunks.stream()
                .filter(c -> c.fusionRank() == 1)
                .findFirst()
                .map(RetrievedChunk::rerankScore)
                .orElse(null);
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String abbreviate(String text) {
        return text.length() > 60 ? text.substring(0, 60) + "…" : text;
    }

    /**
     * 精排那一步的结果。
     *
     * @param chunks  最终分片（已裁剪、已编号）
     * @param applied 重排**是否真的执行并生效**
     * @param millis  重排耗时；未执行为 0
     * @param note    处置说明（降级原因）；正常执行时为空串
     */
    private record RerankOutcome(List<RetrievedChunk> chunks, boolean applied, long millis,
                                 String note) {

        /**
         * 是否判定未命中。
         *
         * <p>两条前置条件缺一不可（见 {@link RetrievalService} 类注释）：
         * <b>重排真的跑过</b>（否则分数不同量纲）+ <b>有分可比</b>（空结果不是"未命中"，
         * 是"一条都没召回" —— 那两件事的兜底话术不一样，见 `T-708`）。
         */
        boolean miss(List<RetrievedChunk> chunks, RetrievalParams params) {
            if (!applied || chunks.isEmpty()) {
                return false;
            }
            double top = chunks.stream().mapToDouble(RetrievedChunk::score).max().orElse(0d);
            return top < params.missThreshold();
        }
    }
}
