package com.knowledge.ai.pipeline.graph;

import com.knowledge.ai.core.enums.SecretLevel;
import com.knowledge.ai.pipeline.retrieve.RetrievalScope;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从 Neo4j 取图谱 1~2 跳邻居（`T-C02`）。
 *
 * <h3>返回什么</h3>
 * <p>只返回**回源所需的最小事实**：{@code sourceChunkId}、置信度、跳数、关系路径。
 * 不把全文塞进 Neo4j，也不在这里查 MySQL —— MySQL 回源由 {@link GraphRetriever} 调
 * {@code ChunkReader#readByIds} 做，两个存储的边界清楚、测试也能分别打到真值。
 *
 * <h3>两条硬上限</h3>
 * <ul>
 *   <li>{@code confidence >= 0.6}（需求 `07:512`）：低置信关系默认不参与跳转；</li>
 *   <li>最多 2 跳 + 硬 {@code LIMIT}：一个高频实体不能把几万条边拖进问答线程。</li>
 * </ul>
 *
 * <p>⚠️ 本类**永不抛**：Neo4j 是旁路，挂掉时调用方拿空结果并置 fallback。
 */
@Service
public class GraphNeighborReader {

    private static final double MIN_CONFIDENCE = 0.6d;
    private static final int MAX_HOPS = 2;
    private static final int MAX_CANDIDATES = 128;

    private final ObjectProvider<Driver> driverProvider;
    /** `T-C03` 起开关来自 {@link GraphFeatureToggle}（`sys_setting`），**不再是构造期 {@code @Value}**。 */
    private final GraphFeatureToggle featureToggle;
    private final Duration timeout;

    public GraphNeighborReader(ObjectProvider<Driver> driverProvider,
                               GraphFeatureToggle featureToggle,
                               @Value("${m05.graph.retrieve.timeout-ms:200}") long timeoutMs) {
        this.driverProvider = driverProvider;
        this.featureToggle = featureToggle;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    public record Candidate(long sourceChunkId, double confidence, int hops, List<String> path) {
    }

    public record Result(List<Candidate> candidates, long millis, boolean fallback, String note) {
        static Result skipped(String note) {
            return new Result(List.of(), 0L, false, note);
        }

        static Result failed(long millis, String note) {
            return new Result(List.of(), millis, true, note);
        }
    }

    /** 查询实体的 1~2 跳邻居；异常/超时转为空结果而不是抛给问答。 */
    public Result neighbors(List<String> entityNames) {
        if (!featureToggle.enabled() || entityNames == null || entityNames.isEmpty()) {
            return Result.skipped("图谱旁路未触发");
        }
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null) {
            return Result.failed(0L, "Neo4j 驱动不可用");
        }
        long start = System.nanoTime();
        try (Session session = driver.session()) {
            List<Candidate> candidates = session.executeRead(tx -> {
                var result = tx.run("""
                        MATCH p=(start:Entity)-[rels:REL*1..2]->(end:Entity)
                        WHERE start.name IN $names
                          AND all(r IN rels WHERE r.confidence >= $minConfidence)
                        WITH p, rels, nodes(p) AS ns,
                             reduce(score = 1.0, r IN rels | score * r.confidence) AS score
                        UNWIND rels AS rel
                        WITH p, ns, score,
                             collect(DISTINCT coalesce(rel.sourceChunkId, 0)) AS chunkIds
                        UNWIND chunkIds AS chunkId
                        WITH p, score, length(p) AS hops, chunkId
                        // 0 = 这条边没带 sourceChunkId（上面 coalesce 的哨兵值）；**负数要放行** ——
                        // 图侧的探针走负号段（见 GraphWriteFlowTest），写成 `> 0` 会连它一起毙掉
                        WHERE chunkId <> 0
                        RETURN chunkId, score, hops,
                               [n IN nodes(p) | n.name] AS names,
                               [r IN relationships(p) | r.relation] AS relations
                        ORDER BY score DESC, hops ASC
                        LIMIT $limit
                        """, Map.of("names", entityNames,
                        "minConfidence", MIN_CONFIDENCE,
                        "limit", MAX_CANDIDATES));
                List<Candidate> out = new ArrayList<>();
                Set<String> seen = new LinkedHashSet<>();
                while (result.hasNext()) {
                    var row = result.next();
                    long chunkId = row.get("chunkId").asLong();
                    if (!seen.add(Long.toString(chunkId))) {
                        continue;
                    }
                    List<String> names = row.get("names").asList(v -> v.asString());
                    List<String> predicates = row.get("relations").asList(v -> v.asString());
                    List<String> path = new ArrayList<>();
                    for (int i = 0; i < predicates.size(); i++) {
                        path.add(names.get(i));
                        path.add(predicates.get(i));
                    }
                    if (!names.isEmpty()) {
                        path.add(names.get(names.size() - 1));
                    }
                    out.add(new Candidate(chunkId, row.get("score").asDouble(),
                            row.get("hops").asInt(), List.copyOf(path)));
                }
                return List.copyOf(out);
            }, TransactionConfig.builder().withTimeout(timeout).build());
            return new Result(candidates, elapsed(start), false, "");
        } catch (Exception ex) {
            return Result.failed(elapsed(start), "Neo4j 查询失败，已静默降级：" + ex.getClass().getSimpleName());
        }
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000L;
    }
}
