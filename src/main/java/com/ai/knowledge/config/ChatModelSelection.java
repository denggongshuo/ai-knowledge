package com.ai.knowledge.config;

import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次路由的结果：主模型 + 有序兜底列表（通常是本地模型）。
 * 调用方按顺序尝试，主模型失败则切下一个，实现高可用兜底。
 */
public record ChatModelSelection(ChatModel primary, List<ChatModel> fallbacks) {

    /** 主模型 + 全部兜底，按优先级排列 */
    public List<ChatModel> all() {
        List<ChatModel> all = new ArrayList<>();
        all.add(primary);
        if (fallbacks != null) {
            all.addAll(fallbacks);
        }
        return all;
    }
}
