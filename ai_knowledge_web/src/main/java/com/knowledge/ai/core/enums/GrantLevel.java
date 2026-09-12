package com.knowledge.ai.core.enums;

import lombok.Getter;

/**
 * 角色权限矩阵的档位（运营视图，四档）。取值与前端 {@code GrantLevel} 逐字一致。
 *
 * <p>与 {@link KbGrantLevel} 是**同一概念的两个视图**（《05-需求-权限与审计》§0.4）：
 * <pre>
 *   MANAGE(管理)  ≈ KbGrantLevel.MANAGE  上传 + 删除文档 + 重建索引
 *   WRITE(读写)   ≈ KbGrantLevel.UPLOAD  只读的全部 + 上传文档
 *   READ(只读)    ≈ KbGrantLevel.READ    问答 / 预览 / 下载
 *   NONE(无)      ≈ KbGrantLevel.NONE    不参与检索
 * </pre>
 *
 * <p><b>注意</b>：{@code MANAGE} **不包含删除知识库** —— 那是独立权限点，见
 * {@link PermissionCode#KB_DELETE}。
 */
@Getter
public enum GrantLevel {

    MANAGE("管理", 3),
    WRITE("读写", 2),
    READ("只读", 1),
    NONE("无", 0),
    ;

    private final String label;

    private final int rank;

    GrantLevel(String label, int rank) {
        this.label = label;
        this.rank = rank;
    }

    /** 同一知识库多角色有不同级别时取最高级。 */
    public static GrantLevel highest(GrantLevel left, GrantLevel right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.rank >= right.rank ? left : right;
    }

    public boolean canRead() {
        return rank >= READ.rank;
    }

    public boolean canUpload() {
        return rank >= WRITE.rank;
    }

    public boolean canManage() {
        return rank >= MANAGE.rank;
    }
}
