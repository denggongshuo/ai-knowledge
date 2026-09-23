package com.knowledge.ai.pipeline.graph;

import com.knowledge.ai.core.text.IdentifierTokenizer;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把提问中的词映射到 Neo4j 实体名（`T-C01`）。
 *
 * <p>这是**词典匹配**，不是第二次 LLM 调用：需求 `07:511` 明确选择 n-gram / BM25，
 * 理由是避免为每个问题再增加一次延迟与失败点。本批使用 Neo4j 里的实体名作为词典，
 * Java 做匹配，实体规模过大或 Neo4j 不可用时由调用方静默降级。
 *
 * <p>触发条件在这里集中实现：按 `IdentifierTokenizer.count` 计数，少于 6 个 token
 * 直接返回空且**不取驱动**；长度够但没有实体命中同样直接返回空。
 */
@Service
public class GraphEntityLinker {

    private static final int MIN_QUERY_TOKENS = 6;
    private static final int MAX_ENTITIES = 20_000;

    private final ObjectProvider<Driver> driverProvider;
    /**
     * `T-C03` 起开关来自 {@link GraphFeatureToggle}（`sys_setting`），**不再是构造期 {@code @Value}**。
     * 理由同 {@link com.knowledge.ai.pipeline.retrieve.RetrievalService}：构造期读一次的属性
     * 让 `PUT /system/feature/graph` 变成"重启才生效"，而 I-16 要的是"关得掉"。
     */
    private final GraphFeatureToggle featureToggle;
    private final Duration timeout;

    public GraphEntityLinker(ObjectProvider<Driver> driverProvider,
                             GraphFeatureToggle featureToggle,
                             @Value("${m05.graph.retrieve.timeout-ms:200}") long timeoutMs) {
        this.driverProvider = driverProvider;
        this.featureToggle = featureToggle;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    public record Result(List<String> entities, long millis, boolean fallback, String note) {
        static Result skipped(String note) {
            return new Result(List.of(), 0L, false, note);
        }

        static Result failed(long millis, String note) {
            return new Result(List.of(), millis, true, note);
        }
    }

    public Result link(String query) {
        String text = query == null ? "" : query.strip();
        if (!featureToggle.enabled()) {
            return Result.skipped("图谱检索开关未开启");
        }
        if (IdentifierTokenizer.count(text) < MIN_QUERY_TOKENS) {
            return Result.skipped("提问长度不足 6 个 token");
        }
        Driver driver = driverProvider.getIfAvailable();
        if (driver == null) {
            return Result.failed(0L, "Neo4j 驱动不可用");
        }

        long start = System.nanoTime();
        try (Session session = driver.session()) {
            List<String> names = session.executeRead(tx -> {
                var result = tx.run("MATCH (e:Entity) RETURN e.name AS name ORDER BY size(e.name) DESC LIMIT $limit",
                        Map.of("limit", MAX_ENTITIES));
                List<String> out = new ArrayList<>();
                while (result.hasNext()) {
                    String name = result.next().get("name").asString("").strip();
                    if (!name.isEmpty()) {
                        out.add(name);
                    }
                }
                return out;
            }, TransactionConfig.builder().withTimeout(timeout).build());
            return new Result(match(text, names), elapsed(start), false, "");
        } catch (Exception ex) {
            return Result.failed(elapsed(start), "实体链接失败，已静默降级：" + ex.getClass().getSimpleName());
        }
    }

    /** 纯匹配函数，单测不依赖 Neo4j。 */
    static List<String> match(String query, List<String> dictionary) {
        if (query == null || query.isBlank() || dictionary == null || dictionary.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String name : dictionary) {
            if (name != null && !name.isBlank() && query.contains(name.strip())) {
                unique.add(name.strip());
            }
        }
        // 同一位置的短实体不能抢走长实体（A-1 不应抢 A-100）；
        // 不同位置按在提问中的出现位置稳定排序。
        return unique.stream()
                .sorted(Comparator.comparingInt((String s) -> query.indexOf(s))
                        .thenComparing(Comparator.comparingInt(String::length).reversed())
                        .thenComparing(Comparator.naturalOrder()))
                .filter(name -> unique.stream().noneMatch(other -> !other.equals(name)
                        && other.length() > name.length()
                        && other.contains(name)
                        && query.contains(other)))
                .toList();
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000L;
    }
}
