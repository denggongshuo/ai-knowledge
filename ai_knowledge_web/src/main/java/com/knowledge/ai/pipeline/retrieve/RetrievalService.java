package com.knowledge.ai.pipeline.retrieve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledge.ai.core.ai.RerankClient;
import com.knowledge.ai.core.ai.SiliconFlowRerankClient;
import com.knowledge.ai.core.ai.ModelClientRegistry;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.setting.SystemSettingService;
import com.knowledge.ai.dto.kb.KbDtos;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 检索编排（`T-700`~`T-704` 的缝合处）—— **`M08` 的唯一入口**。
 *
 * <h3>一条链路的六步</h3>
 * <ol>
 *   <li>{@link RetrievalScope} —— MySQL 先算可见范围（`I-9`）；</li>
 *   <li>{@link RetrievalParams} —— 库级配置 + 全局阈值合成；</li>
 *   <li>{@link HybridRetriever} —— 两路并行召回 top-20；</li>
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

        // ---------- ③ 两路并行召回 ----------
        long recallStart = System.nanoTime();
        HybridRetriever.Recall recall = hybridRetriever.recall(trimmed, scope, params);
        long recallMillis = elapsed(recallStart);

        // ---------- ④ RRF 融合 ----------
        long fuseStart = System.nanoTime();
        List<RrfFusion.FusedHit> fused = RrfFusion.fuse(RrfFusion.DEFAULT_K,
                recall.vectorHits(), recall.bm25Hits());
        long fuseMillis = elapsed(fuseStart);

        // ---------- ⑤ 精排（可降级）----------
        RerankOutcome rerank = rerank(trimmed, fused, params);

        // ---------- ⑥ 契约 ----------
        int kbCount = scope.kbIds().size();
        long totalMillis = elapsed(start);
        List<RetrievedChunk> chunks = rerank.chunks();

        RetrievalTrace trace = new RetrievalTrace(trimmed, params.mode(), scope.kbIds(), kbCount,
                scope.docScopeCount(), scope.docFallback(),
                recall.vectorHits().size(), recall.bm25Hits().size(), fused.size(),
                scopeMillis, recallMillis, fuseMillis, rerank.millis(), totalMillis,
                params.recallTopK(), params.recallThreshold(), params.rerankTopK(),
                params.rerankEnabled(), params.tableWeight(), params.missThreshold(),
                params.lowScoreWarn(), rerank.applied(), rerank.note(),
                rerank.miss(chunks, params), chunks);

        log.info("[检索] 完成：query={} 库数={} 可见文档={} 向量={} BM25={} 融合={} 返回={} "
                        + "重排={} 未命中={} 耗时={}ms（范围 {} + 召回 {} + 融合 {} + 重排 {}）",
                abbreviate(trimmed), kbCount, scope.docScopeCount(),
                recall.vectorHits().size(), recall.bm25Hits().size(), fused.size(), chunks.size(),
                rerank.applied() ? "已执行" : "未执行", trace.miss(), totalMillis,
                scopeMillis, recallMillis, fuseMillis, rerank.millis());

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
                    warn));
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

    /** 空结果：**仍然带完整 trace** —— "为什么什么都没搜到"必须能回答。 */
    private static RetrievalResult emptyResult(String query, RetrievalScope.Scope scope,
                                               long scopeMillis, long start, String note) {
        RetrievalTrace trace = new RetrievalTrace(query, null, scope.kbIds(), scope.kbIds().size(),
                scope.docScopeCount(), scope.docFallback(),
                0, 0, 0, scopeMillis, 0L, 0L, 0L, elapsed(start),
                0, 0d, 0, false, 0d, 0d, 0d, false, note, false, List.of());
        return new RetrievalResult(List.of(), trace, scope.kbIds().size());
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
