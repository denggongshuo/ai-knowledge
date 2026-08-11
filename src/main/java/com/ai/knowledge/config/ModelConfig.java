package com.ai.knowledge.config;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 单个 chat 模型的元数据配置（对应 application.yml 中 ai.models.chat.<name> 下的一项）。
 */
public class ModelConfig {

    /** 唯一标识，如 deepseek-v4-pro */
    private String id;

    /** 前端展示名，如 "DeepSeek V4 Pro（推理）" */
    private String displayName;

    /** OpenAI 兼容端点（各模型可不同） */
    private String baseUrl;

    /** API Key（各模型可不同，允许为空，如本地模型） */
    private String apiKey;

    /** 模型名，如 deepseek/deepseek-v4-pro */
    private String model;

    /** 该模型能承担的任务类型 */
    private Set<TaskType> capabilities = new LinkedHashSet<>();

    /** 作为首选的任务类型（路由优先命中） */
    private Set<TaskType> preferredTasks = new LinkedHashSet<>();

    /** 档位，影响路由时的省钱排序 */
    private Tier tier = Tier.STANDARD;

    /** 是否本地模型，用于高可用兜底 */
    private boolean local = false;

    /** 调用超时 */
    private Duration timeout = Duration.ofSeconds(60);

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Set<TaskType> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(Set<TaskType> capabilities) {
        this.capabilities = capabilities;
    }

    public Set<TaskType> getPreferredTasks() {
        return preferredTasks;
    }

    public void setPreferredTasks(Set<TaskType> preferredTasks) {
        this.preferredTasks = preferredTasks;
    }

    public Tier getTier() {
        return tier;
    }

    public void setTier(Tier tier) {
        this.tier = tier;
    }

    public boolean isLocal() {
        return local;
    }

    public void setLocal(boolean local) {
        this.local = local;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }
}
