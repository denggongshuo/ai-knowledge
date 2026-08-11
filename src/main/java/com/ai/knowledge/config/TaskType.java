package com.ai.knowledge.config;

/**
 * 任务类型：用于按任务自动路由模型。
 * 前端/业务层用任务类型请求，由选择器决定用哪个模型。
 */
public enum TaskType {
    REASONING,    // 复杂推理
    CHAT,         // 简单闲聊 / 问答
    VISION,       // 多模态 / 图片分析
    CODE,         // 代码生成（可扩展）
    LONG_CONTEXT  // 长文本（可扩展）
}
