package com.knowledge.ai.core.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 按 {@link ModelConfigSpec} 构造 Spring AI 客户端（T-014）。
 *
 * <p><b>这是全项目唯一构造 {@code ChatModel} / {@code EmbeddingModel} 的地方</b>
 * （决策：注册中心唯一构造点）。以前靠 {@code spring.ai.openai.*} 自动配置产出 Bean，
 * 那样"用哪个模型"由 yml 决定、无法按库切换，也无法做缓存与失效。
 *
 * <p><b>为什么直接调 {@code OpenAiSetup.setupSyncClient}</b>：Spring AI 2.0 的
 * {@code OpenAiChatModel.builder()} 收的是 **OpenAI 官方 Java SDK 的 {@code OpenAIClient}**，
 * 不是 {@code OpenAiApi}。该 client 的构造入口就是 {@code OpenAiSetup}（自动配置内部也是这么调的）。
 * 参数顺序按自动配置的字节码逐一核对过，不是猜的 —— 位置一错就会静默连到错误的端点。
 *
 * <p>注意 {@code baseUrl} **不做 {@code /v1} 补全**：非 OpenAI / GitHub / Azure 分支原样透传
 * （已读 {@code OpenAiSetup.calculateBaseUrl} 确认），所以 .env 里要写全地址。
 */
@Component
@RequiredArgsConstructor
public class ModelClientFactory {

    private final ModelProperties modelProperties;

    private final ObjectProvider<ObservationRegistry> observationRegistryProvider;

    private final MeterRegistry meterRegistry;

    /** 构造对话模型。 */
    public ChatModel createChat(ModelConfigSpec spec) {
        return OpenAiChatModel.builder()
                .openAiClient(buildSdkClient(spec))
                // 同步、异步两个 client 都要给：OpenAiChatModel.build() 在缺异步 client 时
                // 会走 OpenAiSetup.setupAsyncClient 兜底，而那一路读的是 builder 上的
                // 插件字段（本项目没设）→ 直接抛
                // "At least one credential source must be specified"。
                // 官方自动配置同样是把两个都传进去的（见 OpenAiChatAutoConfiguration）。
                .openAiClientAsync(buildSdkClientAsync(spec))
                .options(OpenAiChatOptions.builder()
                        .model(spec.model())
                        .temperature(modelProperties.getChatTemperature())
                        .build())
                .observationRegistry(observationRegistry())
                .meterRegistry(meterRegistry)
                .build();
    }

    /** 构造嵌入模型。 */
    public EmbeddingModel createEmbedding(ModelConfigSpec spec) {
        return OpenAiEmbeddingModel.builder()
                .openAiClient(buildSdkClient(spec))
                .options(OpenAiEmbeddingOptions.builder()
                        .model(spec.model())
                        // 刻意不设 dimensions：bge-m3 固定输出 1024（G0-6 实测），
                        // 显式下发反被部分网关拒绝。
                        .build())
                .observationRegistry(observationRegistry())
                .build();
    }

    /**
     * 构造重排客户端（`T-702`）。
     *
     * <p><b>为什么不走 Spring AI、也不走 {@link #buildSdkClient}</b>：Spring AI 2.0 没有
     * Rerank 抽象，而 OpenAI 官方 SDK 也没有 {@code /rerank} 端点（那是各家网关自己加的）。
     * 这里返回的是自写的 {@link SiliconFlowRerankClient}，只用 {@code RestClient}——
     * 决策与理由见 `S5-M07-A` §5（不引依赖）。
     *
     * <p>超时取 {@code app.model.timeout-seconds}（与对话/嵌入同一个值）：
     * 另立一个旋钮的收益是零，而"改了超时却没生效"这类问题会多一处来源。
     * 客户端内部对该值有下限保护（重排不该比生成还慢等）。
     */
    public RerankClient createRerank(ModelConfigSpec spec) {
        return new SiliconFlowRerankClient(spec, modelProperties.getTimeoutSeconds());
    }

    private OpenAIClient buildSdkClient(ModelConfigSpec spec) {
        return OpenAiSetup.setupSyncClient(
                spec.baseUrl(),   // baseUrl
                spec.apiKey(),    // apiKey
                null,             // credential（用 apiKey 即可）
                null,             // microsoftDeploymentName
                null,             // microsoftFoundryServiceVersion
                null,             // organizationId
                false,            // isMicrosoftFoundry
                false,            // isGitHubModels
                spec.model(),     // model（供 SDK 侧默认值）
                Duration.ofSeconds(modelProperties.getTimeoutSeconds()),
                modelProperties.getMaxRetries(),
                null,             // proxy
                Map.of(),         // customHeaders
                observationRegistry(),
                meterRegistry,
                List.of());       // httpClientBuilderCustomizers
    }

    /** 异步形态，参数顺序与同步版一致。 */
    private OpenAIClientAsync buildSdkClientAsync(ModelConfigSpec spec) {
        return OpenAiSetup.setupAsyncClient(
                spec.baseUrl(),
                spec.apiKey(),
                null,
                null,
                null,
                null,
                false,
                false,
                spec.model(),
                Duration.ofSeconds(modelProperties.getTimeoutSeconds()),
                modelProperties.getMaxRetries(),
                null,
                Map.of(),
                observationRegistry(),
                meterRegistry,
                List.of());
    }

    private ObservationRegistry observationRegistry() {
        return observationRegistryProvider.getIfAvailable(() -> ObservationRegistry.NOOP);
    }
}
