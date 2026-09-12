package com.knowledge.ai.core.enums;

import lombok.Getter;

/**
 * 密级。取值与前端 {@code SecretLevel} 逐字一致。
 *
 * <p>用于检索侧的安全过滤：用户的密级上限低于分片密级时，该分片不参与召回。
 * 顺序由低到高：{@code PUBLIC < INTERNAL < CONFIDENTIAL < SECRET}。
 *
 * <p><b>注意</b>：密级只存 MySQL（文档 / 知识库），
 * <b>不写进向量库的标签里</b>（《11-非功能需求与技术架构》§2.1 I-9）——
 * 过滤条件由业务侧传给 Milvus 做标量过滤，而不是当作可检索标签。
 */
@Getter
public enum SecretLevel {

    PUBLIC("公开", 1),
    INTERNAL("内部", 2),
    CONFIDENTIAL("机密", 3),
    SECRET("绝密", 4),
    ;

    private final String label;

    /** 密级高度，用于比较。 */
    private final int rank;

    SecretLevel(String label, int rank) {
        this.label = label;
        this.rank = rank;
    }

    /** 当前密级是否不高于（即允许访问）给定上限。 */
    public boolean within(SecretLevel upperBound) {
        if (upperBound == null) {
            return false;
        }
        return this.rank <= upperBound.rank;
    }
}
