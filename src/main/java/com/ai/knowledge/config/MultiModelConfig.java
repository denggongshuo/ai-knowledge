package com.ai.knowledge.config;

import com.openai.client.OpenAIClient;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生产环境（prod）：多模型工厂 + 路由 + 兜底。
 * 读取 ai.models.chat 配置，为每个模型构建独立 ChatModel（base-url / api-key / 超时 各自独立），
 * 并按任务类型自动选择主模型，本地模型作为兜底。
 */
@Configuration
@Profile("prod")
@EnableConfigurationProperties(ChatModelProperties.class)
public class MultiModelConfig {

    private final ChatModelProperties properties;

    public MultiModelConfig(ChatModelProperties properties) {
        this.properties = properties;
    }

    @Bean
    public ChatModelSelector chatModelSelector() {
        Map<String, ModelConfig> cfgs = properties.getModels();

        // 构建所有模型实例
        Map<String, ChatModel> built = new LinkedHashMap<>();
        cfgs.forEach((name, cfg) -> {
            // Spring AI 2.0 基于官方 com.openai SDK：先用 OpenAiSetup 构建同步客户端，再装配 ChatModel
            OpenAIClient client = OpenAiSetup.setupSyncClient(
                    cfg.getBaseUrl(),
                    StringUtils.hasText(cfg.getApiKey()) ? cfg.getApiKey() : null,
                    null,                       // credential（非 Azure 场景不需要）
                    null,                       // microsoft deployment name
                    null,                       // azure service version
                    null,                       // organization
                    false,                      // microsoftFoundry
                    false,                      // gitHubModels
                    cfg.getModel(),
                    cfg.getTimeout(),
                    1,                          // maxRetries
                    null,                       // proxy
                    Map.of(),                   // custom headers
                    ObservationRegistry.NOOP,
                    null,                       // meterRegistry
                    List.of());                 // http client customizers

            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                    .openAiClient(client)
                    .options(OpenAiChatOptions.builder().model(cfg.getModel()).build())
                    .build();

            built.put(name, chatModel);
        });

        // 本地兜底模型名称（按配置顺序）
        List<String> localNames = new ArrayList<>();
        cfgs.forEach((name, cfg) -> {
            if (cfg.isLocal()) {
                localNames.add(name);
            }
        });

        return new ChatModelSelector() {

            @Override
            public ChatModel get(String name) {
                ChatModel model = built.get(name);
                if (model == null) {
                    throw new IllegalArgumentException("未配置的 chat 模型: " + name
                            + "，可选: " + built.keySet());
                }
                return model;
            }

            @Override
            public ChatModelSelection select(TaskType task) {
                // 1. 优先选 preferredTasks 含该任务的模型
                List<String> candidates = new ArrayList<>();
                cfgs.forEach((name, cfg) -> {
                    if (cfg.getPreferredTasks().contains(task)) {
                        candidates.add(name);
                    }
                });
                // 2. 否则选 capabilities 含该任务的模型
                if (candidates.isEmpty()) {
                    cfgs.forEach((name, cfg) -> {
                        if (cfg.getCapabilities().contains(task)) {
                            candidates.add(name);
                        }
                    });
                }
                if (candidates.isEmpty()) {
                    throw new IllegalStateException("没有模型支持任务类型: " + task);
                }
                // 同任务多模型时按档位升序（LOCAL < STANDARD < PREMIUM，即省钱优先）
                candidates.sort(Comparator.comparingInt(n -> cfgs.get(n).getTier().ordinal()));

                String primaryName = candidates.get(0);
                List<ChatModel> fallbacks = new ArrayList<>();
                for (String localName : localNames) {
                    if (!localName.equals(primaryName)) {
                        fallbacks.add(built.get(localName));
                    }
                }
                return new ChatModelSelection(built.get(primaryName), fallbacks);
            }

            @Override
            public ChatModel defaultModel() {
                return built.values().iterator().next();
            }
        };
    }
}
