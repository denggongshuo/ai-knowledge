package com.ai.knowledge.config;

import org.springframework.ai.chat.model.ChatModel;

/**
 * 模型选择器抽象：业务层统一依赖此接口，与"单模型 / 多模型"环境解耦。
 * <ul>
 *   <li>开发/测试：由 SingleModelConfig 提供，固定返回自动配置的单一模型</li>
 *   <li>生产：由 MultiModelConfig 提供，按任务路由 + 本地兜底</li>
 * </ul>
 */
public interface ChatModelSelector {

    /** 按模型名称精确获取（前端显式选择） */
    ChatModel get(String name);

    /** 按任务类型自动选择（含兜底顺序） */
    ChatModelSelection select(TaskType task);

    /** 默认模型 */
    ChatModel defaultModel();
}
