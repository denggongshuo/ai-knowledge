package com.knowledge.ai.core.enums;

import lombok.Getter;

/**
 * 检索方式（知识库配置里的模式选择）。取值与前端 {@code RetrievalMode} 逐字一致。
 *
 * <pre>
 *   VECTOR  纯向量（默认）
 *   HYBRID  混合检索：向量 + BM25
 *   KEYWORD 纯关键词
 * </pre>
 */
@Getter
public enum RetrievalMode {

    VECTOR("向量检索"),
    HYBRID("混合检索"),
    KEYWORD("关键词检索"),
    ;

    private final String label;

    RetrievalMode(String label) {
        this.label = label;
    }

    /** 是否启用 BM25 关键词通道。 */
    public boolean usesBm25() {
        return this == HYBRID || this == KEYWORD;
    }

    /** 是否启用向量通道。 */
    public boolean usesVector() {
        return this == VECTOR || this == HYBRID;
    }
}
