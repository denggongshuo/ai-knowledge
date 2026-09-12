package com.knowledge.ai.core.enums;

import lombok.Getter;

/**
 * 召回来源（SSE {@code event: retrieval} 里每个 chunk 的 {@code source} 字段）。
 *
 * <p><b>取值是小写</b>（{@code vector} / {@code bm25} / {@code graph}），
 * 与前端 {@code Citation['source']} 逐字一致（《10-接口约定汇总》§9 / §14）。
 *
 * <p>⚠️ 曾经误写成 {@code hybrid}，已废弃 —— {@code hybrid} 是**检索模式**
 * （见 {@link RetrievalMode}），不是**单个 chunk 的来源**。一个 chunk 一定来自
 * 某一条具体通道，不会来自"混合"这件事本身。
 */
@Getter
public enum RetrievalSource {

    /** 向量通道召回。 */
    VECTOR("vector", "向量"),
    /** BM25 关键词通道召回。 */
    BM25("bm25", "关键词"),
    /** 实体图谱旁路召回（FR-GRAPH-01）。 */
    GRAPH("graph", "图谱"),
    ;

    /** 线上协议值（小写）。序列化用这个，不要用 {@code name()}。 */
    private final String code;

    private final String label;

    RetrievalSource(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static RetrievalSource fromCode(String code) {
        for (RetrievalSource value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return null;
    }
}
