package com.knowledge.ai.core.enums;

import lombok.Getter;

/**
 * 模型类型（四类）。取值与前端 {@code ModelType} 逐字一致。
 *
 * <p>约束（《11-非功能需求与技术架构》§1.4）：
 * <ul>
 *   <li>{@link #EMBEDDING} —— **全局单例**，BAAI/bge-m3，1024 维，L2 归一化。
 *       不可多实例、不可逐库切换；更换 = 全库重建。列表接口里**不可新增**。</li>
 *   <li>{@link #CHAT} —— 可配置多条，运行时按 id 选用。</li>
 *   <li>{@link #RERANK} —— 全局唯一，切换即时生效。</li>
 *   <li>{@link #VL} —— 图片理解（视觉）模型，配合独立 OCR 链路。</li>
 * </ul>
 */
@Getter
public enum ModelType {

    CHAT("对话模型"),
    EMBEDDING("嵌入模型"),
    RERANK("重排模型"),
    VL("视觉模型"),
    ;

    private final String label;

    ModelType(String label) {
        this.label = label;
    }

    /** 是否全局单例（同类型只能有一条启用）。 */
    public boolean isSingleton() {
        return this == EMBEDDING || this == RERANK;
    }

    /** 是否允许在「模型配置」页新增多条。 */
    public boolean allowsMultiple() {
        return this == CHAT || this == VL;
    }
}
