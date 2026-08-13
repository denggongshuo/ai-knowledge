package com.ai.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 绑定 application.yml 中 ai.models.chat 下的多模型配置（生产环境使用）。
 */
@Data
@ConfigurationProperties(prefix = "ai.models.chat")
public class ChatModelProperties {

    private Map<String, ModelConfig> models = new LinkedHashMap<>();
}
