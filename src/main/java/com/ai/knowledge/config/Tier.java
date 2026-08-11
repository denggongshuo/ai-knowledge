package com.ai.knowledge.config;

/**
 * 模型档位。ordinal 顺序即"省钱优先"排序：LOCAL(0) < STANDARD(1) < PREMIUM(2)。
 * 路由时同任务多模型默认选最便宜的（除非被 preferredTasks 显式指定）。
 */
public enum Tier {
    LOCAL,     // 本地 / 免费，用作兜底
    STANDARD,  // 均衡
    PREMIUM    // 强但贵
}
