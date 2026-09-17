package com.knowledge.ai.core.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SiliconFlow 形状的 Rerank 客户端（`T-702`）—— {@code POST {baseUrl}/rerank}。
 *
 * <h3>为什么手写 HTTP 而不是引一个 SDK</h3>
 * <p>本批 §5 的依赖决策是「**不引**」：{@code RestClient} 随
 * {@code spring-boot-starter-web} 已在依赖里，而 rerank 只有一个端点、四个请求字段、
 * 两个响应字段。为这点形状引一整套 SDK（还要跟着它升级）不划算。
 *
 * <h3>请求 / 响应形状（`S5-M07-A` §3.4 假设 #2 —— 这条假设的实测点就在这里）</h3>
 * <pre>
 * POST {baseUrl}/rerank
 *   Authorization: Bearer &lt;apiKey&gt;
 *   {"model":"BAAI/bge-reranker-v2-m3","query":"...","documents":["...","..."],"top_n":5}
 *
 * 200
 *   {"results":[{"index":0,"relevance_score":0.98},{"index":2,"relevance_score":0.41}]}
 * </pre>
 * <p>{@code index} 是**入参 documents 的下标**（不是 id）—— Rerank 服务不知道分片的存在，
 * 把下标映射回分片是调用方的事，这条边界写在这里免得实现者"顺手"塞个 id 进去。
 *
 * <p>响应用 {@code Map} 而不是建模成 record：契约本身还是假设，
 * 建 record 会让"字段名对不上"从**一条日志**变成**一次反序列化异常**，
 * 后者在降级路径上更难定位。这里显式取值 + 缺字段即抛，问题一眼可见。
 *
 * <h3>失败一律抛，不吞</h3>
 * <p>网络抖动 / 5xx / 响应形状不对 → 抛 {@link RerankFailedException}。
 * **由调用方决定降级**（{@code RetrievalService} 捕获后不重排并标注），
 * 而不是在这里悄悄返回空列表 —— 那会让「重排服务挂了」和「重排没配」两种情形
 * 在下游长得一模一样，而它们的下一步动作完全不同。
 */
@Slf4j
public class SiliconFlowRerankClient implements RerankClient {

    /** 单次请求的读超时下限（秒）——比对话短：精排是检索链路上的一跳，不该比生成还慢。 */
    private static final int MIN_TIMEOUT_SECONDS = 5;

    private final ModelConfigSpec spec;

    private final RestClient http;

    public SiliconFlowRerankClient(ModelConfigSpec spec, int timeoutSeconds) {
        this.spec = spec;
        // 去掉尾斜杠：.env 里的 base-url 两种写法都出现过（结尾带不带 /），
        // 带上就拼出 `//rerank`，部分网关直接 404 —— 而现象是"重排不可用"，看不出是拼串问题
        String baseUrl = spec.baseUrl().endsWith("/")
                ? spec.baseUrl().substring(0, spec.baseUrl().length() - 1)
                : spec.baseUrl();
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(timeoutSeconds))
                .build();
    }

    private static JdkClientHttpRequestFactory requestFactory(int timeoutSeconds) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                        .build());
        factory.setReadTimeout(Duration.ofSeconds(Math.max(MIN_TIMEOUT_SECONDS, timeoutSeconds)));
        return factory;
    }

    @Override
    public boolean available() {
        return spec.hasCredentials();
    }

    @Override
    public String describe() {
        return spec.describe();
    }

    @Override
    public List<RerankHit> rerank(String query, List<String> documents, int topK) {
        if (!available()) {
            throw new RerankFailedException("重排模型未配置：" + spec.describe());
        }
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", spec.model());
        payload.put("query", query);
        payload.put("documents", documents);
        payload.put("top_n", topK);

        Map<String, Object> body;
        try {
            body = http.post()
                    .uri("/rerank")
                    .header("Authorization", "Bearer " + spec.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (Exception ex) {
            throw new RerankFailedException("重排调用失败：" + rootMessage(ex) + "（模型 " + spec.model() + "）", ex);
        }
        return parse(body, documents.size());
    }

    /**
     * 解析 {@code results[]}。
     *
     * <p>三条防线，每条都对应一种"看起来成功、其实没排"的形态：
     * <ul>
     *   <li>没有 {@code results} 键 → 报错（更可能是网关返回了一个错误体而不是 4xx）；</li>
     *   <li>下标越界 → **丢掉那一条并留日志**，不整体失败：模型多回一条不该让整次检索降级；</li>
     *   <li>排序 → 显式按分数降序，**不信任响应顺序**。契约里没有"results 已按分数排序"这句话，
     *       依赖一个没写下来的顺序是典型的"今天对、明天错"。</li>
     * </ul>
     */
    private static List<RerankHit> parse(Map<String, Object> body, int documentCount) {
        if (body == null || !(body.get("results") instanceof List<?> raw)) {
            throw new RerankFailedException("重排响应缺少 results 字段，实际响应：" + brief(body));
        }
        List<RerankHit> hits = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            Object index = row.get("index");
            Object score = row.get("relevance_score");
            if (!(index instanceof Number idx) || !(score instanceof Number val)) {
                log.warn("[Rerank] 跳过形状不符的命中：{}", brief(row));
                continue;
            }
            int i = idx.intValue();
            if (i < 0 || i >= documentCount) {
                log.warn("[Rerank] 跳过越界下标：index={} 候选数={}", i, documentCount);
                continue;
            }
            hits.add(new RerankHit(i, val.doubleValue()));
        }
        hits.sort(Comparator.comparingDouble(RerankHit::score).reversed());
        return List.copyOf(hits);
    }

    private static String brief(Object value) {
        String text = String.valueOf(value);
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    private static String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.toString() : cursor.getMessage();
    }

    /**
     * 重排调用失败。**这是可降级的业务失败**，不是系统错误 ——
     * {@code RetrievalService} 捕获它、不重排、并在 trace 里标注降级原因。
     *
     * <p>刻意继承 {@link BizException} 之外的 {@code RuntimeException}：
     * 它是被**显式 catch** 的信号，不该被全局异常处理器翻译成 HTTP 200 + 错误码
     * 直接甩给用户 —— 检索本身是成功的，只是少了精排这一跳。
     */
    public static class RerankFailedException extends RuntimeException {

        public RerankFailedException(String message) {
            super(message);
        }

        public RerankFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
