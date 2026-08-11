package com.ai.knowledge.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.util.List;

/**
 * 开发 / 测试环境（非 prod）：只用一个模型。
 * 直接复用 spring-ai-starter-model-openai 自动配置的 ChatModel（由 application.yml 的 spring.ai.openai 驱动），
 * 包装成 ChatModelSelector，使业务层 API 与生产环境一致。
 */
@Configuration
@Profile("!prod")
public class SingleModelConfig {

    @Bean
    @Primary
    public ChatModelSelector chatModelSelector(ChatModel chatModel) {
        return new ChatModelSelector() {
            @Override
            public ChatModel get(String name) {
                return chatModel;
            }

            @Override
            public ChatModelSelection select(TaskType task) {
                return new ChatModelSelection(chatModel, List.of());
            }

            @Override
            public ChatModel defaultModel() {
                return chatModel;
            }
        };
    }
}
