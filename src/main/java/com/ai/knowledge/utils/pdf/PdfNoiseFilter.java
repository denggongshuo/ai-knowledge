package com.ai.knowledge.utils.pdf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 步骤三：去除噪音。
 * <ul>
 *   <li>页码行：纯数字、「第 X 页」、「Page 3 of 10」、「- 3 -」等常见形态；</li>
 *   <li>重复页眉页脚：出现在 40% 以上页面中的同一短行（总页数 ≥ 3 时才判定，避免误杀）；</li>
 *   <li>过滤后为空的文本块直接丢弃，表格块原样保留。</li>
 * </ul>
 */
public class PdfNoiseFilter {

    /** 页码常见形态 */
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "^(?:\\d{1,4}"
                    + "|第\\s*\\d+\\s*页"
                    + "|Page\\s*\\d+(?:\\s*(?:of|/)\\s*\\d+)?"
                    + "|[-–—·•*]\\s*\\d{1,4}\\s*[-–—·•*])$",
            Pattern.CASE_INSENSITIVE);

    /** 不超过该长度的行才有资格作为页眉页脚候选 */
    private static final int MAX_HEADER_LINE_LENGTH = 80;
    /** 重复行覆盖页面比例阈值 */
    private static final double REPEATED_LINE_RATIO = 0.4;

    public List<PdfContentBlock> filter(List<PdfContentBlock> blocks) {
        Set<String> repeatedLines = findRepeatedLines(blocks);
        List<PdfContentBlock> result = new ArrayList<>();
        for (PdfContentBlock block : blocks) {
            if (block instanceof PdfContentBlock.TextBlock text) {
                String filtered = filterText(text.text(), repeatedLines);
                if (!filtered.isBlank()) {
                    result.add(new PdfContentBlock.TextBlock(text.pageNumber(), filtered));
                }
            } else {
                result.add(block);
            }
        }
        return result;
    }

    /** 统计出现在多页中的短行，视为页眉页脚噪音 */
    private Set<String> findRepeatedLines(List<PdfContentBlock> blocks) {
        int totalPages = blocks.stream().mapToInt(PdfContentBlock::pageNumber).max().orElse(0);
        if (totalPages < 3) {
            return Set.of();
        }
        Map<String, Set<Integer>> linePages = new HashMap<>();
        for (PdfContentBlock block : blocks) {
            if (block instanceof PdfContentBlock.TextBlock text) {
                for (String line : text.text().split("\n")) {
                    String normalized = line.strip();
                    if (!normalized.isEmpty() && normalized.length() <= MAX_HEADER_LINE_LENGTH) {
                        linePages.computeIfAbsent(normalized, k -> new HashSet<>())
                                .add(block.pageNumber());
                    }
                }
            }
        }
        int threshold = Math.max(3, (int) Math.ceil(totalPages * REPEATED_LINE_RATIO));
        Set<String> repeated = new HashSet<>();
        linePages.forEach((line, pages) -> {
            if (pages.size() >= threshold) {
                repeated.add(line);
            }
        });
        return repeated;
    }

    /** 逐行过滤页码与页眉页脚，空行保留以维持段落分隔 */
    private String filterText(String text, Set<String> repeatedLines) {
        List<String> kept = new ArrayList<>();
        for (String line : text.split("\n")) {
            String stripped = line.strip();
            if (stripped.isEmpty()
                    || (!PAGE_NUMBER.matcher(stripped).matches()
                    && !repeatedLines.contains(stripped))) {
                kept.add(stripped);
            }
        }
        return String.join("\n", kept);
    }
}
