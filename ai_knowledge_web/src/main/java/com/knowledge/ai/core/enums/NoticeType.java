package com.knowledge.ai.core.enums;

import lombok.Getter;

/**
 * 通知类型。取值与前端 {@code NoticeType} 逐字一致。
 *
 * <p>与前端 {@code mockNoticeCounts} 的分类计数一一对应：
 * {@code all / unread / UPLOAD / ALERT / PERMISSION / SYSTEM}。
 */
@Getter
public enum NoticeType {

    UPLOAD("上传"),
    ALERT("告警"),
    PERMISSION("权限"),
    SYSTEM("系统"),
    ;

    private final String label;

    NoticeType(String label) {
        this.label = label;
    }
}
