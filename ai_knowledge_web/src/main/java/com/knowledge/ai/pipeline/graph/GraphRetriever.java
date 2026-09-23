package com.knowledge.ai.pipeline.graph;

import com.knowledge.ai.pipeline.retrieve.RetrievalHit;
import com.knowledge.ai.pipeline.retrieve.RetrievalScope;
import com.knowledge.ai.service.ChunkReader;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图谱旁路的**编排**（`T-C02`）：实体链接 → 1~2 跳邻居 → **可见性过滤** → MySQL 回源 →
 * 一路 {@link RetrievalHit}（`Channel.GRAPH`）。
 *
 * <h3>🔴 为什么"自己过滤"这一步不能省</h3>
 * <p>图谱是第三条召回路径，而它**不经过 Milvus** —— 文档级可见性（数据范围 ∩ 授权 ∩ 密级
 * ∩ 软删）在既有实现里是**下推给 Milvus 的**（见 {@code RetrievalScope.Scope#milvusFilter()}
 * 的说明）。所以图谱回来的分片必须自己按 {@code Scope#docIds()} 再筛一遍：少筛一次不是
 * "少召回几条"，而是**一处越权**（一条指向看不见的文档的边照样会把正文交付出去）。
 * 判据 5 用**负向锚**钉住它：拿掉这里的过滤，那条用例当场转红。
 *
 * <h3>开关与超时不在这里</h3>
 * <p>两者都由调用方 {@code RetrievalService} 持有：开关关时**连本类都不会被调用**
 * （判据 1 要的"零开销"），整体预算由那边的 {@code orTimeout} 兜住（需求 `07:522` 的 200ms）。
 * 本类只负责把一次旁路跑完；叶子（{@link GraphEntityLinker} / {@link GraphNeighborReader}）
 * 各自把 Neo4j 异常与超时收敛成空结果。
 *
 * <p>⚠️ 本类也**永不抛**：编排层不把"叶子说自己不抛"这条假设押在别人身上。
 */
@Service
public class GraphRetriever {

    /** 回源上限：与 {@link GraphNeighborReader} 的候选上限一致，避免回源把 MySQL 放大成 N 倍。 */
    private static final int MAX_HITS = 128;

    private final GraphEntityLinker entityLinker;
    private final GraphNeighborReader neighborReader;
    private final ChunkReader chunkReader;

    public GraphRetriever(GraphEntityLinker entityLinker, GraphNeighborReader neighborReader,
                          ChunkReader chunkReader) {
        this.entityLinker = entityLinker;
        this.neighborReader = neighborReader;
        this.chunkReader = chunkReader;
    }

    /**
     * 一次图谱旁路的完整结果。
     *
     * @param hits     **已经过可见性过滤**，按关系得分降序、{@code rank} 从 1 起
     * @param millis   本段耗时（落 {@code sys_retrieval_trace.graph_millis}）
     * @param fallback 是否静默降级（Neo4j 不可用 / 异常）；正常为 {@code false}
     * @param note     降级说明；正常为空串
     * @param entities 链上的实体名；**没链上就是空列表**（落库时写 NULL，不写空串 ——
     *                 与 {@code graph_used=false} 是两件事，见 `V42` 的列注释）
     */
    public record GraphRecall(List<RetrievalHit> hits, long millis, boolean fallback, String note,
                              List<String> entities) {

        /** 空结果：@param millis 如实记耗时，不补 0。 */
        public static GraphRecall none(long millis, boolean fallback, String note) {
            return new GraphRecall(List.of(), millis, fallback, note, List.of());
        }
    }

    /**
     * 跑一次图谱旁路。
     *
     * @param query 用户提问原文
     * @param scope 本次的可见性边界（**唯一的**可见性来源，与另外两路同一个）
     */
    public GraphRecall recall(String query, RetrievalScope.Scope scope) {
        long start = System.nanoTime();
        try {
            GraphEntityLinker.Result link = entityLinker.link(query);
            if (link.entities().isEmpty()) {
                // 三种"没链上"（长度不足 / 图里没有这个名字 / 驱动不可用）都走这里：
                // 如实记耗时与说明，**不造假命中**，也不继续往下查邻居
                return GraphRecall.none(link.millis(), link.fallback(), link.note());
            }
            GraphNeighborReader.Result neighbors = neighborReader.neighbors(link.entities());
            long millis = link.millis() + neighbors.millis();
            if (neighbors.candidates().isEmpty()) {
                return new GraphRecall(List.of(), millis, neighbors.fallback(), neighbors.note(),
                        link.entities());
            }

            Map<Long, GraphChunk> byId = new HashMap<>();
            for (GraphChunk chunk : chunkReader.readByIds(
                    neighbors.candidates().stream().map(GraphNeighborReader.Candidate::sourceChunkId).toList())) {
                byId.put(chunk.chunkId(), chunk);
            }

            Set<Long> visibleDocs = new HashSet<>(scope.docIds());
            List<RetrievalHit> hits = new ArrayList<>();
            int rank = 1;
            // 顺序 = 邻居查询给的顺序（关系得分降序、跳数升序）⇒ 名次稳定可复现
            for (GraphNeighborReader.Candidate candidate : neighbors.candidates()) {
                if (hits.size() >= MAX_HITS) {
                    break;
                }
                GraphChunk chunk = byId.get(candidate.sourceChunkId());
                if (chunk == null) {
                    // 图里的锚点在 MySQL 里不存在（悬空锚点）：**不拿别的块冒充回源成功**
                    continue;
                }
                if (!visibleDocs.contains(chunk.docId())) {
                    // 🔴 越权防线：图谱不过这层就没别的机会了（判据 5 的负向锚点）
                    continue;
                }
                hits.add(new RetrievalHit(String.valueOf(chunk.chunkId()), chunk.docId(), chunk.kbId(),
                        chunk.blockType(), chunk.titlePath(), chunk.content(),
                        RetrievalHit.Channel.GRAPH, candidate.confidence(), rank++));
            }
            return new GraphRecall(List.copyOf(hits), millis, false, "", link.entities());
        } catch (Exception ex) {
            return GraphRecall.none(elapsed(start), true,
                    "图谱旁路异常，已静默降级：" + ex.getClass().getSimpleName());
        }
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000L;
    }
}