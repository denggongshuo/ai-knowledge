package com.knowledge.ai.pipeline;

import com.knowledge.ai.core.enums.KbConfigDefaults;

/**
 * 分片参数（`T-503`）—— 从知识库的分片策略里来，**不是常量**。
 *
 * <p>刻意不直接吃 `dto/kb` 的 {@code SplitPolicy}：`pipeline` 是领域层，
 * 不该依赖接口 DTO 的形状（接口字段一改，分片逻辑不该跟着动）。
 * 由 `DocParseService` 在调用处把 DTO 映射成本类型。
 *
 * @param chunkSize    切分窗口。⚠️ **需求里单位是 tokens**（《06》§1.4.2 / `KbPresets`），
 *                     而本项目**没有分词器** —— 见 {@link #estimateTokens(String)} 的近似口径
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
     * **token 数的近似** —— 本项目没有分词器，用字符类型加权估算。
     *
     * <h3>为什么不用"字符数"直接当 token 数</h3>
     * <p>中文文档里 1 个汉字 ≈ 1 个 token，而英文大约 4 个字符 ≈ 1 个 token。
     * 直接数字符会把中英混排的文档**高估**（英文部分被算成 4 倍），
     * 于是分片提前切断，块比配置的窗口小得多。
     *
     * <h3>近似到什么程度</h3>
     * <p>CJK 记 1 个 token／字符，ASCII 记 0.25，其余（标点、全角符号等）记 1。
     * 这是**估算不是精确值** —— 与真实 tokenizer 会有偏差，但偏差方向一致、
     * 且不会出现"中英混排时系统性高估"。真正需要精确值时（如嵌入模型的硬上限校验）
     * 应当引入分词器，那时本方法就是替换点。
     *
     * <p>⚠️ 已知偏差已登记在 `plan/2026-09-16-S4-M05-C1.md` §9：`chunkSize` 的口径
     * 从"tokens"变成了"近似 tokens"。
     */
    public static double estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                // ASCII：英文约 4 字符 = 1 token。空白不计（它基本不产 token）
                if (!Character.isWhitespace(c)) {
                    tokens += 0.25;
                }
            } else {
                tokens += 1;
            }
        }
        return tokens;
    }
}
