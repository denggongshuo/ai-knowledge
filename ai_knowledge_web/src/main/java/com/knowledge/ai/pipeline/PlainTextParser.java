package com.knowledge.ai.pipeline;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 纯文本解析（`T-502` 的 MD / TXT 路径 —— **不走 Tika**，严格 UTF-8）。
 *
 * <h3>为什么 MD/TXT 不交给 Tika</h3>
 * <p>探针实测（S4-M05-B 计划 §1 样本表）：Tika 把 {@code .md} 判成
 * {@code text/x-web-markdown}，但实际走的是 {@code TextAndCSVParser} ——
 * {@code #} 标题层级全被当成普通文本行。而标题层级正是 `T-503` 第一级切分的输入，
 * 丢了它，MD 讲义会退化成"一整篇一个块"。
 *
 * <h3>为什么严格 UTF-8、不给乱码</h3>
 * <p>用替换字符（{@code CodingErrorAction.REPLACE}）解 GBK 字节，得到的是
 * 一篇"能解析成功、内容全是乱码"的文档 —— 静默进索引，用户搜出一堆 �。
 * 《03》§4.2 亲口举的失败例子就是「文件编码为 GBK，无法按 UTF-8 解码」，
 * 所以这里把这条路径**显式失败**，交给 {@code G4-3} 的"失败给具体原因"。
 * 这是口径决定（计划 §3.4 假设 2），不是技术限制。
 */
public final class PlainTextParser {

    private PlainTextParser() {
    }

    /**
     * 解析一份 MD / TXT。
     *
     * @param bytes    源字节
     * @param fileName 原始文件名（决定按 MD 还是 TXT 产出结构）
     */
    public static ParsedDocument parse(byte[] bytes, String fileName) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw new ParseException("文件不是 UTF-8 编码（疑似 GBK），无法按 UTF-8 解码；"
                    + "请转存为 UTF-8 后重新上传");
        }

        boolean isMd = fileName != null && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".md");
        java.util.List<ParsedDocument.Heading> headings = new java.util.ArrayList<>();
        java.util.List<String> paragraphs = new java.util.ArrayList<>();
        StringBuilder md = new StringBuilder();

        if (isMd) {
            // MD 本身就是 markdown：标题按 # 层级收进结构（给 T-503 的切分边界），正文原样保留
            java.util.regex.Pattern headingPattern = java.util.regex.Pattern.compile("^(#{1,6})\\s+(.*\\S)\\s*$");
            for (String line : text.split("\\R", -1)) {
                var matcher = headingPattern.matcher(line);
                if (matcher.matches()) {
                    int level = matcher.group(1).length();
                    String t = matcher.group(2);
                    headings.add(new ParsedDocument.Heading(level, t, 1));
                    md.append("#".repeat(level)).append(' ').append(t).append("\n\n");
                } else if (line.isBlank()) {
                    if (!md.isEmpty() && !md.substring(md.length() - 2).equals("\n\n")) {
                        md.append('\n');
                    }
                } else {
                    md.append(line).append('\n');
                    paragraphs.add(line);
                }
            }
        } else {
            // TXT：没有结构，整篇按段落收（连续非空行合为一段）
            StringBuilder para = new StringBuilder();
            for (String line : text.split("\\R", -1)) {
                if (line.isBlank()) {
                    flushPara(para, paragraphs, md);
                } else {
                    if (!para.isEmpty()) {
                        para.append('\n');
                    }
                    para.append(line.trim());
                }
            }
            flushPara(para, paragraphs, md);
        }

        int chars = text.strip().length();
        return new ParsedDocument(isMd ? "MD" : "TXT", 1, chars, md.toString().trim(),
                headings, java.util.List.of(), paragraphs.size(), 0, java.util.List.of());
    }

    private static void flushPara(StringBuilder para, java.util.List<String> paragraphs, StringBuilder md) {
        if (!para.isEmpty()) {
            String t = para.toString();
            paragraphs.add(t);
            md.append(t).append("\n\n");
            para.setLength(0);
        }
    }
}
