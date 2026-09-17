package com.knowledge.ai.pipeline.retrieve;

import java.util.List;

/**
 * 检索的对外契约（`T-704`）—— **`M07` ↔ `M08` 的解耦面**。
 *
 * <h3>为什么这批要先把契约冻结出来</h3>
 * <p>`S5-M07-A` §3.3 把 `T-800`~`T-808`（问答模块）整批列为"不做"，理由只有一条：
 * `M07` 与 `M08` **靠本类型解耦**。契约先冻住，两个模块才能并行开工；
 * 契约一变，两边同时返工 —— 这与 `D-5`（分片产出契约）当年踩过的坑是同一个形状，
 * 所以这里连"顶层 {@code chunks} 与 {@code trace.chunks} 必须同源"这种细节
 * 都写进了构造器而不是留给约定。
 *
 * <h3>{@code chunks} 为什么在顶层和 trace 里各出现一次</h3>
 * <p>它们**必须是同一个 {@code List} 实例**（`G5-9`）。这在 `M08` 的两个页面上各有用途：
 * 问答结果页读顶层 {@code chunks}（生成侧要喂的正文），检索详情抽屉读 {@code trace.chunks}
 * （要连分数、名次、命中通道一起显示）。两次渲染之间若拿了"内容碰巧一样"的两份列表，
 * 一旦其中一份被下游过滤/截断，用户看到的就是"详情说召回了 5 条，答案只用了 3 条，
 * 而那 3 条**不在这 5 条里**"。本类在构造器里把这个不变量**做成结构性事实**（见下），
 * 而不是靠用例发现 —— 用例只负责在有人删掉这行代码时转红。
 *
 * @param chunks  本次检索返回的分片（可为空列表，**不为 null**：没有命中是一种结果，不是缺失）
 * @param trace   全过程留痕
 * @param kbCount 本次覆盖的知识库数量（= {@code kbIds.size()}；前端要直接显示）
 */
public record RetrievalResult(List<RetrievedChunk> chunks, RetrievalTrace trace, int kbCount) {

    /**
     * 把 {@link #chunks} 与 {@code trace.chunks} 拧成**同一个实例**（`G5-9`）。
     *
     * <p>⚠️ 这里是**强制**而不是校验：约束"两者是同一个 List"的合法写法只有一种，
     * 那就没有理由让它成为一个可以构造出非法值的类型（"把非法状态变成不可表达"）。
     * 用例 `RetrievalResultContractTest` 钉的是这行**还在不在** ——
     * 它防的是将来有人把构造器改宽。
     */
    public RetrievalResult {
        if (chunks == null) {
            throw new IllegalArgumentException("chunks 不能为 null —— 没有命中是空列表，不是缺失");
        }
        if (trace == null) {
            throw new IllegalArgumentException("trace 不能为 null —— 每次检索都必须可解释");
        }
        chunks = List.copyOf(chunks);
        trace = trace.withChunks(chunks);
    }

    /** 本次是否判定未命中。⚠️ 未重排时恒为 {@code false}，见 {@link RetrievalTrace#miss()}。 */
    public boolean miss() {
        return trace.miss();
    }

    /** 是否空结果（**与"未命中"不是一回事**：未命中是"有分数但太低"，空结果是"一条都没召回"）。 */
    public boolean empty() {
        return chunks.isEmpty();
    }
}
