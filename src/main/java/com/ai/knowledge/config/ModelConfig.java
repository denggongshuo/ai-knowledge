package com.ai.knowledge.config;

import lombok.Data;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 单个 chat 模型的元数据配置（对应 application.yml 中 ai.models.chat.<name> 下的一项）。
 */
@Data
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
}
