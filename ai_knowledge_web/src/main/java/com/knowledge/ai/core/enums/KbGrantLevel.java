package com.knowledge.ai.core.enums;

import lombok.Getter;

/**
 * 知识库授权级别（逐库授权）。取值与前端 {@code KbGrantLevel} 逐字一致。
 *
 * <p>注意它与 {@link GrantLevel} 的**枚举名顺序不同**（这里是 NONE 打头），
 * 序列化成字符串后不敏感，但不要互相强转。
 */
@Getter
public enum KbGrantLevel {

    NONE("不可见", 0),
    READ("只读", 1),
    UPLOAD("可上传", 2),
    MANAGE("可管理", 3),
    ;

    private final String label;

    private final int rank;

    KbGrantLevel(String label, int rank) {
        this.label = label;
        this.rank = rank;
    }

    /** 同一知识库多角色有不同级别时取最高级。 */
    public static KbGrantLevel highest(KbGrantLevel left, KbGrantLevel right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.rank >= right.rank ? left : right;
    }

    /** 是否参与检索（不可见 = 不参与）。 */
    public boolean visible() {
        return this != NONE;
    }
}
