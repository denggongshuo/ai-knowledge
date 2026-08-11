package com.ai.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 绑定 application.yml 中 ai.models.chat 下的多模型配置（生产环境使用）。
 */
@ConfigurationProperties(prefix = "ai.models.chat")
public class ChatModelProperties {

    private Map<String, ModelConfig> models = new LinkedHashMap<>();

    public Map<String, ModelConfig> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelConfig> models) {
        this.models = models;
    }
}
