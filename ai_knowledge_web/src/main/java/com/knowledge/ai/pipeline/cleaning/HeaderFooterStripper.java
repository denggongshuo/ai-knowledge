package com.knowledge.ai.pipeline.cleaning;

import com.knowledge.ai.pipeline.ParsedDocument;
import com.knowledge.ai.pipeline.ParsedDocument.DocElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 页眉/页脚剥离（`优化/O3` §4 步 1-2）。
 *
 * <h3>为什么必须自己写 —— 探针已经问过了</h3>
 * <p>`O3` §4 步 1 的原话是「先查 opendataloader 是否已分类 header/footer …
 * **若已有，直接用，不要自己写启发式** —— 这是一次探针就能确认的事，成本极低」。
 * 探针做了（{@code O3HeaderFooterProbeTest#elementTypeInventoryHasNoHeaderFooter}），
 * 答案是：元素类型全集 **6 种**（{@code heading/paragraph/list/table/image/text block}），
 * **没有** {@code header}/{@code footer}。所以启发式躲不掉。
 *
 * <h3>判据：跨页重复 + 只出现在页边</h3>
 * <p>两个条件**同时**成立才判定，缺一不可：
 * <ol>
 *   <li><b>归一化后出现在 ≥ {@code ratio} 的页上</b> —— 页眉里的页码逐页在变，
 *       不去数字连"完全重复"都算不上（`O3` §2 痛点 1 说的就是这个）；</li>
 *   <li><b>只出现在每页的首/尾 {@code edgeLines} 行以内</b> —— 这是"页边位置"的形式化。
 *       它同时是防误杀的主力：`O3` §4 步 2 只提了重复计数，没有它的话，
 *       一篇正文里反复出现的口号会被整段剥掉。</li>
 * </ol>
 *
 * <h3>三条刻意收紧的口子（都是"宁可漏剥离也不误杀"）</h3>
 * <ul>
 *   <li><b>页数不足不判</b>（{@code minPages}，默认 3）：2 页里重复 2 次说明不了什么；</li>
 *   <li><b>只有一行的页不参与</b>：那种页的"唯一一行"按定义就是该页正文，
 *       没有页边与正文之分 —— 让它参与的话，一篇每页一行、行行相同的文档会被整篇剥空；</li>
 *   <li><b>正文出现不剥离</b>：只剥**页边位置**上的那一次。同一句话在正文里出现照旧保留
 *       —— 这条直接对应 `O3` 判据 2（正文中的重复句不被误剥离）。</li>
 * </ul>
 *
 * <h3>本批没有真样本可标定（必须让人看见）</h3>
 * <p>三份 PDF 样本干跑下来**一条都判不出来**，包括 `O3` §5 判据 1 点名的那份
 * （它是 PPT 导出，没有页眉页脚）。所以本类的**正向**证据只有合成用例；
 * **反向**证据是真实的：真样本上一处不剥、{@code chars} 一字不减。
 * 详见 {@link CleaningOptions} 的 javadoc 与计划 §8。
 *
 * <p>本类是**纯函数**（无状态、无 IO、无随机），同一输入必得同一输出 ——
 * 这是 `O3` 判据 5「清洗可回放」的实现基础。
 */
final class HeaderFooterStripper {

    private HeaderFooterStripper() {
    }

    /**
     * 判定并剥离。
     *
     * @param elements 解析产物的元素序列（**文档顺序**）
     * @param pages    文档总页数 —— 阈值分母。用**总页数**而不是"参与判定的页数"：
     *                 扫描件那种大段无文本的文档，分母用总页数时门槛更高，更保守
     */
    static Result detect(List<DocElement> elements, int pages, CleaningOptions options) {
        if (!options.enoughPages(pages)) {
            return Result.none(elements);
        }

        Map<Integer, List<Integer>> textIdxByPage = groupTextElementsByPage(elements);

        // 页 → 该页的候选元素下标（首/尾各 edgeLines 个）
        Map<String, Set<Integer>> pagesByPattern = new LinkedHashMap<>();
        Map<String, String> sampleByPattern = new LinkedHashMap<>();
        Map<String, Integer> firstPageByPattern = new LinkedHashMap<>();
        Set<Integer> candidateIdx = new LinkedHashSet<>();

        for (Map.Entry<Integer, List<Integer>> entry : textIdxByPage.entrySet()) {
            List<Integer> idx = entry.getValue();
            // 只有一行的页不参与判定 —— 那一行就是该页正文，没有"页边"可言（见类 javadoc）
            if (idx.size() < 2) {
                continue;
            }
            int n = Math.min(options.edgeLines(), idx.size());
            List<Integer> edges = new ArrayList<>(idx.subList(0, n));
            // 页数少时首尾会重叠，用 Set 去重（同一元素只当一次候选）
            Set<Integer> edgeSet = new LinkedHashSet<>(edges);
            for (int i = Math.max(n, idx.size() - n); i < idx.size(); i++) {
                edgeSet.add(idx.get(i));
            }

            for (Integer i : edgeSet) {
                DocElement el = elements.get(i);
                String text = el.text();
                if (text == null || text.isBlank()) {
                    continue;
                }
                String pattern = normalize(text);
                if (pattern.isEmpty()) {
                    continue;
                }
                candidateIdx.add(i);
                pagesByPattern.computeIfAbsent(pattern, k -> new LinkedHashSet<>()).add(entry.getKey());
                sampleByPattern.putIfAbsent(pattern, text.strip());
                firstPageByPattern.merge(pattern, entry.getKey(), Math::min);
            }
        }

        int threshold = options.pageThreshold(pages);
        Set<String> strippedPatterns = new LinkedHashSet<>();
        pagesByPattern.forEach((pattern, seenOn) -> {
            if (seenOn.size() >= threshold) {
                strippedPatterns.add(pattern);
            }
        });
        if (strippedPatterns.isEmpty()) {
            return Result.none(elements);
        }

        // 逐元素决定去留：**只剥页边位置上的那一次**，正文里的同串照旧保留
        List<DocElement> kept = new ArrayList<>(elements.size());
        Map<String, int[]> removedChars = new LinkedHashMap<>();   // pattern → [字符数]
        for (int i = 0; i < elements.size(); i++) {
            DocElement el = elements.get(i);
            boolean removed = false;
            if (candidateIdx.contains(i)) {
                String text = el.text();
                String pattern = text == null ? "" : normalize(text);
                if (strippedPatterns.contains(pattern)) {
                    removed = true;
                    removedChars.computeIfAbsent(pattern, k -> new int[1])[0] += text.strip().length();
                }
            }
            if (!removed) {
                kept.add(el);
            }
        }

        List<CleanedDocument.StrippedPattern> stripped = new ArrayList<>();
        strippedPatterns.stream()
                .map(p -> new CleanedDocument.StrippedPattern(p, sampleByPattern.get(p),
                        firstPageByPattern.get(p), pagesByPattern.get(p).size(),
                        removedChars.getOrDefault(p, new int[1])[0]))
                .sorted((a, b) -> Integer.compare(a.firstPage(), b.firstPage()))
                .forEach(stripped::add);

        return new Result(List.copyOf(kept), List.copyOf(stripped));
    }

    /**
     * 文本元素的**归一化**形态 —— 去掉页眉里唯一会变的那部分。
     *
     * <p>干两件事：① 抹掉所有空白（视觉断行不该让"第 3 页"与"第 17 页"变成两个模式）；
     * ② 把连续数字换成单个 {@code #}。
     *
     * <p>⚠️ 数字用 {@code \p{Nd}} 而不是 {@code \d}：后者默认只认 ASCII 数字，
     * 全角「３」会漏网 —— 而中文文档里全角数字并不罕见。
     *
     * <p>刻意**不**去掉「第」「页」「共」这类字：留着它们反而更安全 ——
     * `第#页` 与 `第#章` 不会被归并成一个模式（去掉的话会）。
     */
    static String normalize(String text) {
        return text.replaceAll("\\s+", "").replaceAll("\\p{Nd}+", "#");
    }

    /** 页码 → 该页**文本类**元素（标题/正文）在 {@code elements} 里的下标，保持文档顺序。 */
    private static Map<Integer, List<Integer>> groupTextElementsByPage(List<DocElement> elements) {
        Map<Integer, List<Integer>> byPage = new LinkedHashMap<>();
        for (int i = 0; i < elements.size(); i++) {
            DocElement el = elements.get(i);
            boolean textBearing = el.kind() == DocElement.Kind.HEADING
                    || el.kind() == DocElement.Kind.PARAGRAPH;
            if (!textBearing) {
                continue;
            }
            byPage.computeIfAbsent(el.pageNo(), k -> new ArrayList<>()).add(i);
        }
        return byPage;
    }

    /** 剥离结果。{@code kept} 与入参**同序**（只删不重排 —— 顺序是分片的输入）。 */
    record Result(List<DocElement> kept, List<CleanedDocument.StrippedPattern> stripped) {

        static Result none(List<DocElement> elements) {
            return new Result(elements, List.of());
        }
    }
}
