package com.knowledge.ai.core.enums;

import lombok.Getter;

/**
 * 回收站对象类型。取值与前端 {@code RecycleType} 逐字一致。
 *
 * <p>注意「知识库」的还原是**连带操作**：恢复知识库会连带恢复其下全部文档，
 * 且恢复后需要重建索引（《06-需求-知识库与文档》FR-DOC-05 §7.2）。
 */
@Getter
public enum RecycleType {

    DOC("文档"),
    KB("知识库"),
    ;

    private final String label;

    RecycleType(String label) {
        this.label = label;
    }

    /** 还原是否连带子对象。 */
    public boolean cascades() {
        return this == KB;
    }
}
