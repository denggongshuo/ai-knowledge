package com.knowledge.ai.core.enums;

import lombok.Getter;

import java.util.Set;

/**
 * 文档生命周期状态。取值与前端 {@code DocInfo['status']} 逐字一致。
 *
 * <p>状态机（《06-需求-知识库与文档》）：
 * <pre>
 *   UPLOADED ──> PARSING ──> INDEXING ──> READY
 *                    │            │
 *                    └────────────┴────> FAILED（可重试，重试回 PARSING）
 * </pre>
 * {@code READY} 是**唯一可被检索**的状态。
 */
@Getter
public enum DocStatus {

    UPLOADED("已上传"),
    PARSING("解析中"),
    INDEXING("索引中"),
    READY("可检索"),
    FAILED("解析失败"),
    ;

    private final String label;

    DocStatus(String label) {
        this.label = label;
    }

    /** 是否允许触发「重建索引」。 */
    public boolean canReindex() {
        return this == READY || this == FAILED;
    }

    /** 是否处于进行中（前端显示进度、禁止重复提交）。 */
    public boolean inProgress() {
        return this == PARSING || this == INDEXING;
    }

    /** 终态：解析/索引流水线不会再自动流转。 */
    public static final Set<DocStatus> TERMINAL = Set.of(READY, FAILED);
}
