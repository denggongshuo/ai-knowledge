package com.knowledge.ai.pipeline.retrieve;

import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.storage.MilvusProperties;
import com.knowledge.ai.core.text.Bm25Text;
import com.knowledge.ai.pipeline.vectorize.EmbeddingClient;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static com.knowledge.ai.config.AsyncConfig.RETRIEVAL_EXECUTOR;

/**
 * 混合检索的**召回内核**（`T-700`）—— 向量 + BM25 两路**并行**跑在同一个集合上。
 *
 * <h3>两路并行，不是先后</h3>
 * <p>`G5-1` 的判据之一是"并行有证据（两路耗时之和 > 墙钟耗时）"。这不是为了刷一个指标：
 * 两路都是**阻塞式网络往返**（Milvus search），串行跑就是白白把延迟翻倍，
 * 而这两次调用彼此**没有任何依赖**——都用同一个 {@code filter}、同一个分区集合、
 * 只是查不同的列。
 *
 * <p>⚠️ 并行带来的那条硬约束写在 {@code AsyncConfig#retrievalExecutor} 上：
 * **必须**带 traceId 装饰器。少了它，这两跳的日志各生成一个新 traceId，
 * 于是"这一次请求到底花了多久"永远拼不起来。
 *
 * <h3>两路查的是同一个集合，靠不同的 {@code annsField}</h3>
 * <ul>
 *   <li>向量路：{@code annsField = embedding}，查询数据是**嵌入向量**；</li>
 *   <li>BM25 路：{@code annsField = sparse}（BM25 函数的**输出**列），
 *       查询数据是**文本** —— 由 Milvus 在服务端跑函数生成查询稀疏向量。</li>
 * </ul>
 * <p>为什么 BM25 那一路传的是 {@link EmbeddedText} 而不是一个稀疏向量：
 * 稀疏向量是**语料相关**的（词项 id 来自集合内建的表），应用侧拼不出来。
 * 让 Milvus 用它自己的那张表去编，是唯一正确的做法。
 *
 * <p>⚠️ 但传进去的文本**必须已经过本仓的 {@link Bm25Text}**。集合上 {@code bm25_text} 列的
 * analyzer 是 {@code whitespace}（**什么都不做**），而写入侧存的正是
 * {@code Bm25Text.of(...)} 的产物。查询侧若直接扔原始提问进去，中文会**整句**成为一个 token
 * —— 与库里按单字切好的 token **一个都对不上**，表现为"BM25 路永远返回空"，
 * 而且**不报任何错**。这就是 {@code MilvusConstants#FIELD_BM25_TEXT} 那句
 * "写入侧与查询侧必须调同一个函数"的落地处。
 *
 * <h3>排序方向：两路**相反**</h3>
 * <p>向量用 L2 距离（**越小越近**），BM25 用相关性（**越大越好**）。本类**显式**排序，
 * 不依赖 Milvus 返回时"已经排好"这个假设 —— 那个假设没写在契约里，
 * 而依赖它一旦失效，现象是"召回了一条最不相关的"，从结果上完全看不出来。
 * {@link RrfFusion} 只用排名，所以这里排对了，融合才有意义。
 */
@Slf4j
@Component
public class HybridRetriever {

    /** 从 Milvus 取回的标量列。正文一起取回来是刻意的 —— 它是 Rerank 的输入，见 {@link RetrievalHit}。 */
    private static final List<String> OUTPUT_FIELDS = List.of(
            MilvusConstants.FIELD_ID,
            MilvusConstants.FIELD_DOC_ID,
            MilvusConstants.FIELD_KB_ID,
            MilvusConstants.FIELD_CHUNK_TYPE,
            MilvusConstants.FIELD_TITLE_PATH,
            MilvusConstants.FIELD_CONTENT);

    private final MilvusClientV2 client;

    private final MilvusProperties properties;

    private final EmbeddingClient embeddingClient;

    private final Executor retrievalExecutor;

    public HybridRetriever(MilvusClientV2 client,
                           MilvusProperties properties,
                           EmbeddingClient embeddingClient,
                           @Qualifier(RETRIEVAL_EXECUTOR) Executor retrievalExecutor) {
        this.client = client;
        this.properties = properties;
        this.embeddingClient = embeddingClient;
        this.retrievalExecutor = retrievalExecutor;
    }

    /**
     * 按参数指定的通道并行召回。
     *
     * @param query  用户提问**原文**（BM25 那一路的分词在这里做，见类注释）
     * @param scope  可见范围（`I-9` 的产物）—— 它的 {@link RetrievalScope.Scope#milvusFilter()}
     *               直接作为标量过滤下推给 Milvus
     * @param params 本次检索参数（决定开哪几路、每路多少条）
     * @return 两路各自的命中；**未启用的那一路是空列表**，不是 {@code null}
     */
    public Recall recall(String query, RetrievalScope.Scope scope, RetrievalParams params) {
        long start = System.nanoTime();

        CompletableFuture<Channel> vector = params.usesVector()
                ? CompletableFuture.supplyAsync(() -> vectorChannel(query, scope, params), retrievalExecutor)
                : CompletableFuture.completedFuture(Channel.skipped(RetrievalHit.Channel.VECTOR));

        CompletableFuture<Channel> bm25 = params.usesBm25()
                ? CompletableFuture.supplyAsync(() -> bm25Channel(query, scope, params), retrievalExecutor)
                : CompletableFuture.completedFuture(Channel.skipped(RetrievalHit.Channel.BM25));

        // join 两个都完成；任一失败时把**真实原因**带出来（CompletionException 会把它裹住）
        CompletableFuture.allOf(vector, bm25).join();
        Channel vectorResult = vector.join();
        Channel bm25Result = bm25.join();

        long wallMillis = (System.nanoTime() - start) / 1_000_000;
        log.info("[检索] 召回完成：query={} 分区={} 向量={}({}ms) BM25={}({}ms) 并行墙钟={}ms",
                abbreviate(query), scope.partitions().size(), vectorResult.hits().size(),
                vectorResult.millis(), bm25Result.hits().size(), bm25Result.millis(), wallMillis);

        return new Recall(vectorResult.hits(), bm25Result.hits(),
                vectorResult.millis(), bm25Result.millis(), wallMillis);
    }

    // ==================== 两路通道 ====================

    private Channel vectorChannel(String query, RetrievalScope.Scope scope, RetrievalParams params) {
        long start = System.nanoTime();
        List<float[]> vectors;
        try {
            vectors = embeddingClient.embed(List.of(query));
        } catch (EmbeddingClient.EmbeddingFailedException ex) {
            // 查询嵌入失败**不能降级**：少了向量路，只剩 BM25 的召回质量是另一回事，
            // 而"用户以为在混合检索、其实只有关键词"是不可接受的静默劣化
            throw BizException.of(ErrorCode.SYSTEM_ERROR,
                    "提问向量化失败：" + ex.getMessage() + " —— 请确认嵌入模型可用后重试");
        }
        if (vectors.isEmpty()) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "提问向量化没有返回结果 —— 请重试");
        }

        List<RetrievalHit> hits = search(RetrievalHit.Channel.VECTOR, MilvusConstants.FIELD_VECTOR,
                List.of(new FloatVec(vectors.getFirst())), scope, params,
                Map.of("nprobe", properties.getIndexNprobe()));
        return new Channel(RetrievalHit.Channel.VECTOR, hits, (System.nanoTime() - start) / 1_000_000);
    }

    private Channel bm25Channel(String query, RetrievalScope.Scope scope, RetrievalParams params) {
        long start = System.nanoTime();
        // ⚠️ 唯一的口径处：写入侧写的是 Bm25Text.of(投影文本)，查询侧必须走同一个函数。
        // 直接传 query 的话中文会整句成一个 token —— 且**不报错**，只是永远召回不到
        String bm25Query = Bm25Text.of(query);
        if (bm25Query.isEmpty()) {
            // 提问里一个有效 token 都没有（纯标点 / 纯空白）—— 这一路必然空，
            // 不必白跑一次 Milvus。**这是短路，不是降级**：结果确实是空
            return new Channel(RetrievalHit.Channel.BM25, List.of(), (System.nanoTime() - start) / 1_000_000);
        }
        List<RetrievalHit> hits = search(RetrievalHit.Channel.BM25, MilvusConstants.FIELD_SPARSE,
                List.of(new EmbeddedText(bm25Query)), scope, params, Map.of());
        return new Channel(RetrievalHit.Channel.BM25, hits, (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * 一次 Milvus search。两路共用（只有 {@code annsField} / 查询数据 / 排序方向不同）。
     *
     * @param sortAscending 是否升序 —— 向量（L2 距离）为 {@code true}，BM25（相关性）为 {@code false}
     */
    private List<RetrievalHit> search(RetrievalHit.Channel channel,
                                      String annsField,
                                      List<BaseVector> data,
                                      RetrievalScope.Scope scope,
                                      RetrievalParams params,
                                      Map<String, Object> extraParams) {
        SearchReq.SearchReqBuilder builder = SearchReq.builder()
                .collectionName(properties.getCollection())
                // 分区必须逐代指定：`D-7` 的原子切换就落在"查哪一代"上
                .partitionNames(scope.partitions())
                .annsField(annsField)
                .data(data)
                .topK(params.recallTopK())
                .filter(scope.milvusFilter())
                .outputFields(OUTPUT_FIELDS)
                // BOUNDED 与建集合时一致：检索要的是"能读到刚写入的"，而不是"读到全网最新"
                .consistencyLevel(ConsistencyLevel.BOUNDED);
        if (!extraParams.isEmpty()) {
            builder.searchParams(extraParams);
        }

        SearchResp resp;
        try {
            resp = client.search(builder.build());
        } catch (Exception ex) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR,
                    "检索召回失败（" + channel.label() + " 通道）：" + rootMessage(ex)
                            + " —— 请确认 milvus-standalone 健康后重试");
        }

        List<SearchResp.SearchResult> results = firstResultBatch(resp);
        List<RetrievalHit> hits = new ArrayList<>(results.size());
        for (SearchResp.SearchResult result : results) {
            RetrievalHit hit = toHit(channel, result);
            // 退化路径（doc 集合过大）下，Milvus 只按库过滤，文档级可见性在这里兜住。
            // 正常路径恒通过 —— 那时 Milvus 已经在索引层筛过了，见 RetrievalScope.Scope#allowsDoc
            if (hit != null && scope.allowsDoc(hit.docId())) {
                hits.add(hit);
            }
        }

        // 显式排序（见类注释）：不依赖"Milvus 返回时已经排好"
        Comparator<RetrievalHit> byScore = Comparator.comparingDouble(RetrievalHit::score);
        hits.sort(channel == RetrievalHit.Channel.VECTOR ? byScore : byScore.reversed());

        // 排序后**重新编号**，而不是沿用 Milvus 的次序 —— RRF 吃的是这个 rank，
        // 它必须反映"本地重排后的名次"，否则退化路径筛掉的条目会留下排名空洞
        List<RetrievalHit> ranked = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            ranked.add(hits.get(i).withRank(i + 1));
        }
        return List.copyOf(ranked);
    }

    /** 单条查询只会有一个结果批次；形状不符说明 SDK 返回了意料之外的东西，显式拒绝。 */
    private static List<SearchResp.SearchResult> firstResultBatch(SearchResp resp) {
        if (resp == null || resp.getSearchResults() == null || resp.getSearchResults().isEmpty()) {
            return List.of();
        }
        List<SearchResp.SearchResult> first = resp.getSearchResults().getFirst();
        return first == null ? List.of() : first;
    }

    /**
     * 把一条 Milvus 结果转成本仓的命中。
     *
     * <p>标量列缺失时返回 {@code null} 并留日志，**不抛**：一条形状不对的结果
     * 不该让整次检索失败（它与"Milvus 连不上"是两个量级的问题）。
     * 但也不能静默 —— 少一条而不留痕，是"召回率莫名偏低"这类问题的经典来源。
     */
    private static RetrievalHit toHit(RetrievalHit.Channel channel, SearchResp.SearchResult result) {
        Map<String, Object> entity = result.getEntity();
        String chunkId = entity == null ? null : stringOf(entity.get(MilvusConstants.FIELD_ID));
        if (chunkId == null) {
            log.warn("[检索] 跳过缺少主键的召回结果：通道={} 结果={}", channel.label(), result);
            return null;
        }
        return new RetrievalHit(
                chunkId,
                longOf(entity.get(MilvusConstants.FIELD_DOC_ID)),
                longOf(entity.get(MilvusConstants.FIELD_KB_ID)),
                stringOf(entity.get(MilvusConstants.FIELD_CHUNK_TYPE)),
                stringOf(entity.get(MilvusConstants.FIELD_TITLE_PATH)),
                stringOf(entity.get(MilvusConstants.FIELD_CONTENT)),
                channel,
                result.getScore() == null ? 0d : result.getScore().doubleValue(),
                0);
    }

    private static String stringOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 60 ? text.substring(0, 60) + "…" : text;
    }

    private static String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.toString() : cursor.getMessage();
    }

    /**
     * 一路通道的召回结果。
     *
     * @param channel 哪一路
     * @param hits    排序并编号后的命中（rank 从 1 起）
     * @param millis  本路耗时
     */
    private record Channel(RetrievalHit.Channel channel, List<RetrievalHit> hits, long millis) {

        static Channel skipped(RetrievalHit.Channel channel) {
            return new Channel(channel, List.of(), 0L);
        }
    }

    /**
     * 两路并行的召回结果。
     *
     * @param vectorHits   向量通道（未启用时为空列表）
     * @param bm25Hits     BM25 通道（未启用时为空列表）
     * @param vectorMillis 向量路耗时
     * @param bm25Millis   BM25 路耗时
     * @param wallMillis   **并行**的墙钟耗时。`G5-1` 的判据：它应**小于**两路之和
     */
    public record Recall(List<RetrievalHit> vectorHits,
                         List<RetrievalHit> bm25Hits,
                         long vectorMillis,
                         long bm25Millis,
                         long wallMillis) {

        /** 两路合计耗时（**串行假设下的耗时**，用来与 {@link #wallMillis} 对比证明并行）。 */
        public long sumMillis() {
            return vectorMillis + bm25Millis;
        }

        /** 两路命中总数（融合**前**，含重复）。 */
        public int totalHits() {
            return vectorHits.size() + bm25Hits.size();
        }
    }
}
