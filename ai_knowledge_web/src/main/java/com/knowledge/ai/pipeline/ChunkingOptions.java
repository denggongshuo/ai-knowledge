package com.knowledge.ai.pipeline;

import com.knowledge.ai.core.enums.KbConfigDefaults;
import com.knowledge.ai.core.text.IdentifierTokenizer;

/**
 * 分片参数（`T-503`）—— 从知识库的分片策略里来，**不是常量**。
 *
 * <p>刻意不直接吃 `dto/kb` 的 {@code SplitPolicy}：`pipeline` 是领域层，
 * 不该依赖接口 DTO 的形状（接口字段一改，分片逻辑不该跟着动）。
 * 由 `DocParseService` 在调用处把 DTO 映射成本类型。
 *
 * @param chunkSize    切分窗口。**单位是 tokens**（《06》§1.4.2 / `KbPresets`）。
 *                     计量走 {@link #estimateTokens(String)} → `T-506` 的
 *                     {@code IdentifierTokenizer}（Q-4 口径）。⚠️ 与**真实嵌入模型的**
 *                     tokenizer 仍有偏差 —— 后者是模型私有的，本仓无从复现；
 *                     需要精确值的地方（如嵌入模型的硬上限校验）应显式用自己的 tokenizer
 * @param overlap      相邻分片的重叠量（同单位）—— 只在**第二级兜底**切出来的相邻片之间生效；
 *                     结构边界切出来的块之间**不重叠**（那是两节内容，重叠会让检索命中串节）
 * @param semanticSplit 第二级是否启用语义切分。**本批不实现**（需要句级 embedding，属 `T-508` 能力）；
 *                     开启时 {@link ChunkingService} 会**显式失败**而不是静默降级
 */
public record ChunkingOptions(int chunkSize, int overlap, boolean semanticSplit) {

    /** 紧致构造器：几何校验。{@code overlap >= chunkSize} 会让有效步长非正，切分会退化甚至死循环。 */
    public ChunkingOptions {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0，实际 " + chunkSize);
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException(
                    "overlap 必须满足 0 <= overlap < chunkSize，实际 " + overlap + " / " + chunkSize);
        }
    }

    /** 知识库默认分片策略（= GENERAL 预设），与 `KbConfigDefaults` 同源。 */
    public static ChunkingOptions defaults() {
        return new ChunkingOptions(KbConfigDefaults.CHUNK_SIZE, KbConfigDefaults.OVERLAP,
                KbConfigDefaults.SEMANTIC_SPLIT);
    }

    /**
     * **窗口计量** —— 走 {@link IdentifierTokenizer#windowTokens}（`T-506`，2026-09-16 替换）。
     *
     * <h3>它此前是"字符加权近似"，现在不是了</h3>
     * <p>`S4-M05-C1` 时期的实现是"CJK 记 1／字符、ASCII 记 0.25"，那是权宜 ——
     * 当时本仓**还没有分词器**。`T-506` 交付 `IdentifierTokenizer` 之后，
     * 切词这件事只有一份规则，各处不必再各写一套。
     *
     * <h3>⚠️ 但它**不是** {@code IdentifierTokenizer.count}</h3>
     * <p>计划 §3.1 的 1.3 原写"改为委托 {@code IdentifierTokenizer.count}"，**这里偏离了**，
     * 而且偏离是有实证的：`count` 是 Q-4 的**长度**口径（连续 ASCII 词整体算 1），
     * 拿它当窗口判据会让"超过窗口就切断"对一段无分隔符的长串**永不触发**
     * —— `ChunkingServiceTest#textWithoutSeparatorsIsHardSplit` 当场转红，
     * 而那条用例保的是"宁可切坏也不能产出超长块"。
     * 见 {@link IdentifierTokenizer#windowTokens(String)} 的 javadoc。
     *
     * <p>换口径对**正常文本**的影响很小（中日韩仍是 1 字 1 token；
     * `Milvus` 从 1.5 变 2），中英混排的块边界可能挪一点。
     * **约束不变**：每片不超窗口、无内容丢失 —— 这是判据 8 要盯的那两条。
     */
    public static double estimateTokens(String text) {
        return IdentifierTokenizer.windowTokens(text);
    }
}
