package com.knowledge.ai.core.enums;

import lombok.Getter;

/**
 * 未命中问题的处理状态（问答运营五步闭环）。取值与前端 {@code MissStatus} 逐字一致。
 *
 * <pre>
 *   PENDING   待处理
 *   IN_LIST   已加入待办 / 优化清单
 *   RESOLVED  已解决
 *   IGNORED   已忽略
 * </pre>
 */
@Getter
public enum MissStatus {

    PENDING("待处理"),
    IN_LIST("已入清单"),
    RESOLVED("已解决"),
    IGNORED("已忽略"),
    ;

    private final String label;

    MissStatus(String label) {
        this.label = label;
    }

    /** 终态：不需要再跟进。 */
    public boolean closed() {
        return this == RESOLVED || this == IGNORED;
    }
}
