package com.knowledge.ai.core.enums;

import lombok.Getter;

/**
 * 用户状态。取值与前端 {@code src/types/index.ts} 的 {@code UserStatus} 逐字一致。
 *
 * <pre>
 *   ACTIVE   正常
 *   PENDING  待激活（管理员建号后未完成激活）
 *   DISABLED 已停用
 * </pre>
 */
@Getter
public enum UserStatus {

    ACTIVE("正常"),
    PENDING("待激活"),
    DISABLED("已停用"),
    ;

    private final String label;

    UserStatus(String label) {
        this.label = label;
    }
}
