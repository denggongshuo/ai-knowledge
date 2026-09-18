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

    private final ObjectProvider<ObservationRegistry> observationRegistryProvider;

    private final MeterRegistry meterRegistry;

    /** 构造对话模型。 */
    public ChatModel createChat(ModelConfigSpec spec) {
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(spec.model())
                // `S6-M09-A` 起 temperature 取自**该条模型自己的** params（`T-901` 可编辑），
                // 不再是全局单值 —— 否则在配置页改温度会"保存成功但行为不变"。
                // 引导路径的 spec 仍逐项照抄 ModelProperties，故行为没有变化。
                .temperature(spec.params().temperatureOrDefault());
        // ⚠️ maxTokens 为 null 时**不下发**这个字段（让网关用自己的默认值）——
        // 这是 S6-M09-A 之前的既有行为。给一个非空默认值会悄悄改变所有模型的生成长度上限。
        if (spec.params().maxTokens() != null) {
            options.maxTokens(spec.params().maxTokens());
        }
        return OpenAiChatModel.builder()
                .openAiClient(buildSdkClient(spec))
                // 同步、异步两个 client 都要给：OpenAiChatModel.build() 在缺异步 client 时
                // 会走 OpenAiSetup.setupAsyncClient 兜底，而那一路读的是 builder 上的
                // 插件字段（本项目没设）→ 直接抛
                // "At least one credential source must be specified"。
                // 官方自动配置同样是把两个都传进去的（见 OpenAiChatAutoConfiguration）。
                .openAiClientAsync(buildSdkClientAsync(spec))
                .options(options.build())
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
     * <p>超时取**该条模型自己的** {@code params.timeoutSec}（`S6-M09-A` 起）。
     * 引导路径的 spec 逐项照抄 {@code app.model.timeout-seconds}，故行为没有变化；
     * 而 DB 路径改了超时是真生效的 —— 这正是本批要让 `params` 落到实处的理由之一。
     * 客户端内部对该值有下限保护（重排不该比生成还慢等）。
     */
    public RerankClient createRerank(ModelConfigSpec spec) {
        return new SiliconFlowRerankClient(spec, spec.params().timeoutSecOrDefault());
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
                Duration.ofSeconds(spec.params().timeoutSecOrDefault()),
                spec.params().retriesOrDefault(),
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
                Duration.ofSeconds(spec.params().timeoutSecOrDefault()),
                spec.params().retriesOrDefault(),
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
