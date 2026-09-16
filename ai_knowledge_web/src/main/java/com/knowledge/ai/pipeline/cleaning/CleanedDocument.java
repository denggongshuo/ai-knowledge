package com.knowledge.ai.pipeline.cleaning;

import com.knowledge.ai.pipeline.ParsedDocument;

import java.util.List;

/**
 * 清洗产物：**清洗后的产物 + 被剥离清单**（`优化/O3` §3 的 {@code CleanedDocument}）。
 *
 * <h3>为什么要带上"被剥离清单"，而不是只返回干净的产物</h3>
 * <p>本项目最不能接受的形态是"少了一点东西而没人发现"（`T-0S1` 的结论原话，
 * 也是 `S4-M05-C2` 步 0.5 那个 8.1% 静默丢内容的教训）。清洗**就是**在删东西，
 * 所以它天然踩在这条线上 —— 清单是让"删了什么"看得见的那一半。
 *
 * <p>清单去向有两处（`O3` §4 步 6）：① 拼成 {@code sys_parse_issue} 的行，用户在页面上看得到；
 * ② 留在本类型的返回值里，测试与将来的 `O5` 基线可以直接断言。
 *
 * <h3>⚠️ {@code markdown} 不做改写（本批的有意取舍）</h3>
 * <p>清洗只作用于 {@code elements} 序列，**{@code markdown} 原样带过**。理由：
 * <ol>
 *   <li>{@code ParsedDocument.markdown} 是"**解析器抽到了什么**"的记录，
 *       它的重建需要文档标题（{@code PdfDocumentParser#buildMarkdown} 的入参），
 *       而 {@code ParsedDocument} 里**没有**标题 —— 硬重建会丢掉 {@code # 标题} 那一行；</li>
 *   <li>它**目前没有任何消费者**：{@code ChunkingService} 只读 {@code elements} 与 {@code tables}
 *       （见其 {@code chunk} 方法），分片结果与 {@code markdown} 无关；</li>
 *   <li>不改写 ⇒ 没有可被误杀的正文文本，也不会让 Tika 路径的 markdown 形态漂移
 *       （Tika 的列表项前缀 {@code - } 不在元素文本里，重建必然改形）。</li>
 * </ol>
 * <p>代价是：**事后翻 {@code parse.json} 的人会在 {@code markdown} 里看到被剥离的页眉**，
 * 而 {@code elements} 里已经没有它。这个差异已登记为遗留（计划 §9），
 * 等真有人消费 {@code markdown} 时再决定由谁负责重建。
 * <b>判据一律以 {@code elements} 与 {@code sys_chunk.raw_content} 为准，不看 {@code markdown}。</b>
 *
 * @param document 清洗后的产物。{@code elements} 已滤掉样板、{@code headings}/{@code paragraphs}/{@code chars}
 *                 由滤后的序列**重新派生**（单一事实源）；{@code tables}/{@code pages}/{@code format} 不变
 * @param stripped 被剥离的样板清单，按**首次出现的页码**升序。没剥到任何东西时为空列表
 */
public record CleanedDocument(ParsedDocument document, List<StrippedPattern> stripped) {

    public CleanedDocument {
        stripped = stripped == null ? List.of() : List.copyOf(stripped);
    }

    /** 是否真的剥掉了东西 —— 调用方据此决定要不要记 issue、要不要打日志。 */
    public boolean changed() {
        return !stripped.isEmpty();
    }

    /** 被剥掉的总字符数（`O3` §6 建议的观察指标"清洗前后 chars 比值"的分子）。 */
    public int strippedChars() {
        return stripped.stream().mapToInt(StrippedPattern::chars).sum();
    }

    /**
     * 一处被判定为"跨页重复样板"并已剥离的内容。
     *
     * <p>{@code pattern} 是**归一化后**的形态（数字换成 {@code #}），
     * 这是"第 3 页"与"第 17 页"能归并成同一个模式的原因（`O3` §4 步 2）。
     * {@code sample} 保留**原文**，因为给人看的时候需要看到真实长什么样。
     *
     * @param pattern     归一化形态（含 {@code #} 占位符），用于判定与去重
     * @param sample      首次出现的**原文**（截断到 {@value #SAMPLE_MAX} 字符）
     * @param firstPage   首次出现的页码（1-based）—— 拼 issue 时作为 {@code page_no} 的锚点
     * @param occurrences 出现在多少**页**上（注意是页数不是元素数：同一页出现两次只算一页）
     * @param chars       被剥掉的总字符数（含同一样板在同一页的多次出现）
     */
    public record StrippedPattern(String pattern, String sample,
                                  int firstPage, int occurrences, int chars) {

        /** 样本文本进 issue 文案前的截断长度（{@code reason} 列宽 512，留足余量）。 */
        public static final int SAMPLE_MAX = 60;

        public StrippedPattern {
            sample = sample == null ? "" : sample;
            if (sample.length() > SAMPLE_MAX) {
                sample = sample.substring(0, SAMPLE_MAX) + "…";
            }
        }
    }
}
