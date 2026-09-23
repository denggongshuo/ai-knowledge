package com.knowledge.ai.pipeline.graph;

import java.util.List;
import java.util.Map;

/**
 * 图谱回源时的 MySQL 分片事实（`T-C02`）。
 *
 * <p>⚠️ 与 {@code VectorChunk} 刻意分开：向量层的对象带的是"喂嵌入模型用什么"，
 * 这里要的是"图谱候选回到哪一条原文"，还必须携带 {@code docId}/{@code kbId}
 * 供调用方按 {@code RetrievalScope.Scope} 做第二次可见性过滤。
 */
public record GraphChunk(long chunkId,
                         long docId,
                         long kbId,
                         String blockType,
                         String titlePath,
                         String content) {

    /** 变成对外图谱路径里的一个稳定片段。 */
    public Map<String, Object> asMap() {
        return Map.of(
                "chunkId", chunkId,
                "docId", docId,
                "kbId", kbId,
                "blockType", blockType == null ? "" : blockType,
                "titlePath", titlePath == null ? "" : titlePath,
                "content", content == null ? "" : content);
    }
}
