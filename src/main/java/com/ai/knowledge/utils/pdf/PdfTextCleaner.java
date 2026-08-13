package com.ai.knowledge.utils.pdf;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 步骤二：数据清洗。
 * <ul>
 *   <li>去除控制字符，规范化不可见空白（NBSP、全角空格）；</li>
 *   <li>修复英文单词行尾连字符断词（con- tinue → continue）；</li>
 *   <li>合并中文因版面宽度被截断的折行（仅合并「长行」，短行保留换行，
 *       便于后续步骤识别页眉页脚等噪音）；</li>
 *   <li>压缩连续空行，丢弃清洗后为空的块；</li>
 *   <li>清理表格块中的单元格内容（控制字符、首尾空白、全空行）。</li>
 * </ul>
 */
public class PdfTextCleaner {

    /** 除换行 / 制表符以外的控制字符 */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\n\t]]");
    private static final Pattern MULTI_BLANK_LINES = Pattern.compile("\\n{3,}");
    /** 句尾标点：以这些字符结尾的行视为句子结束，保留换行 */
    private static final String SENTENCE_ENDS = "。！？；：….!?:;";
    /** 列表项行首特征：短横线 / 项目符号 / 数字编号，保留换行 */
    private static final Pattern LIST_ITEM = Pattern.compile("^(?:[-•*▪◦]|\\d+[.、)])\\s*\\S");
    /** 中文折行合并的最小行长：过短的行（标题、页眉等）不合并 */
    private static final int CJK_JOIN_MIN_LENGTH = 15;

    public List<PdfContentBlock> clean(List<PdfContentBlock> blocks) {
        List<PdfContentBlock> result = new ArrayList<>();
        for (PdfContentBlock block : blocks) {
            switch (block) {
                case PdfContentBlock.TextBlock text -> {
                    String cleaned = cleanText(text.text());
                    if (!cleaned.isBlank()) {
                        result.add(new PdfContentBlock.TextBlock(text.pageNumber(), cleaned));
                    }
                }
                case PdfContentBlock.TableBlock table -> {
                    List<List<String>> rows = cleanRows(table.rows());
                    if (!rows.isEmpty()) {
                        result.add(new PdfContentBlock.TableBlock(table.pageNumber(), rows));
                    }
                }
            }
        }
        return result;
    }

    private String cleanText(String text) {
        String cleaned = CONTROL_CHARS.matcher(text).replaceAll("");
        cleaned = cleaned.replace('\u00a0', ' ').replace('\u3000', ' ');
        cleaned = joinBrokenLines(cleaned);
        cleaned = MULTI_BLANK_LINES.matcher(cleaned).replaceAll("\n\n");
        return cleaned.strip();
    }

    /**
     * 合并 PDF 版面造成的折行：
     * 英文行尾连字符 → 去连字符直连；中文长行折行 → 直接拼接；
     * 句尾标点 / 列表项 / 空行 → 保留换行；其余西文段内折行 → 空格拼接。
     */
    private String joinBrokenLines(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        String previous = null;
        for (String raw : lines) {
            String line = raw.strip();
            if (previous == null) {
                sb.append(line);
                previous = line;
                continue;
            }
            if (line.isEmpty() || previous.isEmpty()
                    || endsWithSentencePunct(previous)
                    || LIST_ITEM.matcher(line).lookingAt()) {
                sb.append('\n');                                  // 段落 / 列表边界，保留换行
            } else if (endsWithHyphen(previous)) {
                sb.setLength(sb.length() - 1);                    // 去掉行尾连字符
            } else if (previous.length() >= CJK_JOIN_MIN_LENGTH
                    && isCjk(lastChar(previous)) && isCjk(firstChar(line))) {
                // 中文长行折行，直接拼接不加空格
            } else {
                sb.append(' ');                                   // 西文段内折行，空格拼接
            }
            sb.append(line);
            previous = line;
        }
        return sb.toString();
    }

    private List<List<String>> cleanRows(List<List<String>> rows) {
        List<List<String>> result = new ArrayList<>();
        for (List<String> row : rows) {
            List<String> cells = new ArrayList<>();
            boolean anyContent = false;
            for (String cell : row) {
                String cleaned = CONTROL_CHARS.matcher(cell).replaceAll("")
                        .replace('\u00a0', ' ')
                        .strip();
                cells.add(cleaned);
                anyContent |= !cleaned.isEmpty();
            }
            if (anyContent) {
                result.add(cells);
            }
        }
        return result;
    }

    private boolean endsWithHyphen(String line) {
        return line.length() >= 2
                && line.charAt(line.length() - 1) == '-'
                && Character.isLetter(line.charAt(line.length() - 2));
    }

    private boolean endsWithSentencePunct(String line) {
        return SENTENCE_ENDS.indexOf(line.charAt(line.length() - 1)) >= 0;
    }

    private boolean isCjk(char c) {
        if (c == 0) {
            return false;
        }
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }

    private char lastChar(String s) {
        return s.isEmpty() ? 0 : s.charAt(s.length() - 1);
    }

    private char firstChar(String s) {
        return s.isEmpty() ? 0 : s.charAt(0);
    }
}
