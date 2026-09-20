package com.knowledge.ai.pipeline.retrieve;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * 检索打点的**落库**那一半（`T-707` · `I-13` / `I-14`）。
 *
 * <h3>为什么是 M08 调它，而不是 {@code RetrievalService} 自己写</h3>
 * <p>`I-13` 要求 6 阶段耗时齐全，其中「LLM 生成」那一段在
 * {@link RetrievalService#search} **返回的那一刻还不存在** —— 生成是 M08 在那之后做的事。
 * 所以写行的时机只能是**流式收尾**，而这就是 `D-2` 说
 * 「打点后补 = 数据永久缺失」的字面原因：打点与问答物理上是同一次请求。
 *
 * <h3>为什么用原生 INSERT 而不是 JPA 实体</h3>
 * <p>本表是**只增不改不读**的追加型打点（读侧是 `T-A03` 的聚合，它自己会写查询）。
 * 39 列建一个永远不会被 `findById` 的实体，只是多一份要同步维护的映射。
 * 同包的 {@link TableLookupService} 也是原生 SQL —— 这一层的既有风格如此。
 * ⚠️ 反过来，`sys_chat_session` / `sys_chat_message` 用的是 JPA 实体：
 * 它们有生命周期（`updated_at` 要更新、`deleted_at` 要软删、要靠 `save()` 拿回自增 id）。
 *
 * <h3>写不进去绝不影响问答</h3>
 * <p>打点是**旁路证据**，不是业务前置条件（与 {@code LoginLogService} 同一条口径）。
 * 失败只记 warn。⚠️ 但**不能静默** —— `T-A03` 的数据少了行，与"用户没提问"长得一样，
 * 所以 warn 里必须带上 traceId，好让"我明明问过"能被查证。
 *
 * <h3>为什么用 {@code REQUIRES_NEW}</h3>
 * <p>与登录日志同一理由：调用方的业务无论成败，这一行都要留下。
 * 独立事务保证它不被外层的回滚带走。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalTraceWriter {

    /**
     * 一次打点要落的东西。
     *
     * @param traceId             当次请求的 traceId（与日志同源，是"用户报障那一次"的关联键）
     * @param userId              提问人
     * @param sessionId           会话 id
     * @param messageId           assistant 消息 id（`T-808` 的 `used_model_id` 挂在它上面）
     * @param result              检索结果（`trace` 提供全部耗时与参数，`chunks` 提供明细）
     * @param llmGenerationMillis 生成耗时；**{@code null} = 本次没有调模型**（空召回兜底）。
     *                            ⚠️ 不传 0 —— 那会把"没生成"记成"生成耗时 0ms"
     * @param totalMillis         端到端总耗时（检索 + 生成）。⚠️ 不是
     *                            {@link RetrievalTrace#totalMillis()} —— 那个只到检索为止
     */
    public record TraceWrite(String traceId,
                             Long userId,
                             Long sessionId,
                             Long messageId,
                             RetrievalResult result,
                             Long llmGenerationMillis,
                             long totalMillis) {
    }

    private static final String INSERT_TRACE = """
            insert into sys_retrieval_trace
              (trace_id, user_id, session_id, message_id, query_text,
               mode, kb_ids, kb_count, doc_scope_count, doc_scope_fallback,
               vector_hits, bm25_hits, fused_count,
               query_analysis_millis, embed_millis, vector_search_millis, bm25_recall_millis,
               rerank_millis, llm_generation_millis, total_millis,
               scope_millis, recall_millis, fuse_millis, table_millis,
               recall_top_k, recall_threshold, rerank_top_k, rerank_enabled, table_weight,
               miss_threshold, low_score_warn, rerank_applied, rerank_note, degrade_note,
               miss_flag, rerank_before_top1, top1_score, chunk_count)
            values
              (:traceId, :userId, :sessionId, :messageId, :queryText,
               :mode, :kbIds, :kbCount, :docScopeCount, :docScopeFallback,
               :vectorHits, :bm25Hits, :fusedCount,
               0, :embedMillis, :vectorSearchMillis, :bm25RecallMillis,
               :rerankMillis, :llmGenerationMillis, :totalMillis,
               :scopeMillis, :recallMillis, :fuseMillis, :tableMillis,
               :recallTopK, :recallThreshold, :rerankTopK, :rerankEnabled, :tableWeight,
               :missThreshold, :lowScoreWarn, :rerankApplied, :rerankNote, :degradeNote,
               :missFlag, :rerankBeforeTop1, :top1Score, :chunkCount)
            """;

    private static final String INSERT_CHUNK = """
            insert into sys_retrieval_trace_chunk
              (trace_id, rank_no, chunk_id, doc_id, score, source, table_parent_id, cited)
            values
              (:traceId, :rankNo, :chunkId, :docId, :score, :source, :tableParentId, null)
            """;

    /** 未命中沉淀（`T-A00`）—— 打点行写完之后按 `miss_flag` 派生池子那一步。 */
    private final MissSink missSink;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 落一行打点 + 它的分片明细。
     *
     * <p>**不会抛** —— 打点失败只 warn。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(TraceWrite write) {
        try {
            RetrievalTrace trace = write.result().trace();
            List<RetrievedChunk> chunks = write.result().chunks();

            var insert = entityManager.createNativeQuery(INSERT_TRACE)
                    .setParameter("traceId", write.traceId())
                    .setParameter("userId", write.userId())
                    .setParameter("sessionId", write.sessionId())
                    .setParameter("messageId", write.messageId())
                    .setParameter("queryText", trace.query())
                    // 空结果时 `mode` 为 null（没走到"选检索方式"那一步），列可空，如实落 null
                    .setParameter("mode", trace.mode() == null ? null : trace.mode().name())
                    .setParameter("kbIds", joinIds(trace.kbIds()))
                    .setParameter("kbCount", trace.kbCount())
                    .setParameter("docScopeCount", trace.docScopeCount())
                    .setParameter("docScopeFallback", trace.docScopeFallback())
                    .setParameter("vectorHits", trace.vectorHits())
                    .setParameter("bm25Hits", trace.bm25Hits())
                    .setParameter("fusedCount", trace.fusedCount())
                    .setParameter("embedMillis", trace.embedMillis())
                    .setParameter("vectorSearchMillis", trace.vectorSearchMillis())
                    .setParameter("bm25RecallMillis", trace.bm25Millis())
                    .setParameter("rerankMillis", trace.rerankMillis())
                    .setParameter("llmGenerationMillis", write.llmGenerationMillis())
                    .setParameter("totalMillis", write.totalMillis())
                    .setParameter("scopeMillis", trace.scopeMillis())
                    .setParameter("recallMillis", trace.recallMillis())
                    .setParameter("fuseMillis", trace.fuseMillis())
                    .setParameter("tableMillis", trace.tableMillis())
                    .setParameter("recallTopK", trace.recallTopK())
                    .setParameter("recallThreshold", trace.recallThreshold())
                    .setParameter("rerankTopK", trace.rerankTopK())
                    .setParameter("rerankEnabled", trace.rerankEnabled())
                    .setParameter("tableWeight", trace.tableWeight())
                    .setParameter("missThreshold", trace.missThreshold())
                    .setParameter("lowScoreWarn", trace.lowScoreWarn())
                    .setParameter("rerankApplied", trace.rerankApplied())
                    .setParameter("rerankNote", nullToEmpty(trace.rerankNote()))
                    .setParameter("degradeNote", nullToEmpty(trace.degradeNote()))
                    .setParameter("missFlag", trace.miss())
                    // 可空：未重排 ⇒ null；重排了但重排前那条没进 top_n ⇒ 也 null。
                    // **不用 0 顶替**（"没测到"与"得了 0 分"是两件事，与 top1 同一条口径）
                    .setParameter("rerankBeforeTop1", trace.rerankBeforeTop1())
                    .setParameter("top1Score", top1(chunks))
                    .setParameter("chunkCount", chunks.size());
            insert.executeUpdate();

            writeChunks(write.traceId(), chunks);

            // 🔴 顺序是**先打点、后沉淀**（`T-A00`）：沉淀自己开 `REQUIRES_NEW`。
            // 反过来先沉淀的话，打点一旦失败就留下"池子里有一行、打点里查不到"——
            // 那是**不可重建**的（池子行说不出它是从哪次提问来的）；
            // 而"打点在、池子少一行"可以由 miss_flag=1 的打点重建出来。
            missSink.record(write);
        } catch (RuntimeException ex) {
            // 绝不因为打点写不进去而让问答失败。traceId 必须带出来 —— 否则"少了一行"
            // 与"用户压根没提问"在库里长得一模一样，事后无从分辨
            log.warn("[打点] 检索链路打点写入失败，本次问答不受影响：traceId={} err={}",
                    write.traceId(), ex.getMessage());
        }
    }

    /**
     * 分片明细。
     *
     * <p>逐条 insert 而不是拼一条多值 INSERT：最多十几行，而拼串要处理转义与超长。
     * 这个量级下两种写法的耗时差在噪声里。
     */
    private void writeChunks(String traceId, List<RetrievedChunk> chunks) {
        for (RetrievedChunk chunk : chunks) {
            Long chunkId = numericId(chunk.chunkId());
            if (chunkId == null) {
                // 主键不是数字（`RetrievalHit#chunkId` 是 String，因为 Milvus 主键是字符串字段）。
                // 与 `TableLookupService#numericIds` 同一条口径：**跳过并留痕**，
                // 不猜一个 id 出来 —— 明细错挂到别的分片上比少一行更糟
                log.warn("[打点] 跳过主键非数字的分片明细：traceId={} chunkId={}", traceId, chunk.chunkId());
                continue;
            }
            RetrievedChunk.TableContext table = chunk.tableContext();
            entityManager.createNativeQuery(INSERT_CHUNK)
                    .setParameter("traceId", traceId)
                    .setParameter("rankNo", chunk.rank())
                    .setParameter("chunkId", chunkId)
                    .setParameter("docId", chunk.docId())
                    .setParameter("score", chunk.score())
                    .setParameter("source", chunk.source().getCode())
                    .setParameter("tableParentId", table == null ? null : table.parentChunkId())
                    .executeUpdate();
        }
    }

    /**
     * 当次的 Top1 分数（`I-14` / `G5-13`）。
     *
     * <p>取**名次最靠前**那一条的分（不是 `chunks.get(0)` —— 后者依赖"列表恰好按名次排好"
     * 这条不变量，而它由两层排序共同保证，取 min 是原地可验的）。
     *
     * <p>⚠️ **量纲随 {@code rerank_applied} 变**：重排过是 0~1 的重排分，
     * 未重排是 RRF 融合分（约 0.016 量级）。`T-A03` 做"Top1 分布"时**必须**按这一列分组，
     * 否则会把两种量纲画进同一张图。这与 {@link RetrievedChunk#scoreKind()} 是同一条纪律。
     *
     * <p>一条都没召回时返回 {@code null} —— "没有 Top1"与"Top1 是 0 分"是两件事。
     *
     * <p><b>公开</b>（`T-A01` 起，原为包内可见）—— 现在有**三处**要用它，
     * 且必须是**同一个算法**，否则三个数（打点 `top1_score` / 池子 `max_top_score` /
     * 重放 `score_after`）会在同一个页面上互相对不上，而每一处看着都合理：
     * <ol>
     *   <li>{@link #write} —— 打点行的 {@code top1_score}；</li>
     *   <li>{@link MissSink} —— 池子的 {@code max_top_score}；</li>
     *   <li>{@code ReplayService} —— 重放后的 {@code score_after}。
     *       🔴 尤其不能在那里另写一次：重放的全部意义就是"和补录前用**同一把尺**量"，
     *       尺子换了，"提升了"这个结论就不成立</li>
     * </ol>
     */
    public static Double top1(List<RetrievedChunk> chunks) {
        RetrievedChunk top = top(chunks);
        return top == null ? null : top.score();
    }

    /**
     * 名次最靠前**那一条**（{@link #top1} 取的就是它的分）。
     *
     * <p>为什么要把"那一条"也公开（`T-A01`）：重放验证要同时回答两件事 ——
     * **分是多少**与**这个分是谁给的**。「命中的是不是刚补录那篇」不能问"召回列表里有没有它"
     * （补录的文档排在第二名也算有），而要问"**Top1 是不是它**"：
     * 达标与命中必须落在**同一条**上，两条判据才自洽 ——
     * 否则「别的文档把分抬上去、补录那篇挂在第二」会被判成已解决，
     * 而那正是需求 §1.4 的注点名要防的（「否则可能只是别的文档碰巧被召回」）。
     *
     * <p>`top1` 与它**共用同一个选择器**（不各写一遍 `min(rank)`）——
     * 分与"谁的分"必须永远指同一条。
     */
    public static RetrievedChunk top(List<RetrievedChunk> chunks) {
        return chunks.stream()
                .min(Comparator.comparingInt(RetrievedChunk::rank))
                .orElse(null);
    }

    private static Long numericId(String chunkId) {
        try {
            return Long.valueOf(chunkId);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** 逗号拼接（包内可见，理由同 {@link #top1} —— 池子与打点表的 `kb_ids` 同口径）。 */
    static String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
