package com.knowledge.ai.core.text;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 内容指纹（`T-O09` 步 1）—— 规范化文本的 sha256。
 *
 * <h3>它回答的问题与 md5 不是同一个</h3>
 * <p>{@code sys_document.md5} 回答"这两个文件是不是**同一串字节**"。
 * 本类回答"这两个文档是不是**同一份内容**"。后者的价值在于它能拦住前者的三类漏网之鱼：
 * <table border="1">
 *   <caption>md5 拦不住的（O9 §2.1）</caption>
 *   <tr><th>场景</th><th>为什么 md5 拦不住</th></tr>
 *   <tr><td>同一份合同 v1 / v2（改一句话）</td><td>字节不同，99% 内容相同 → 两份索引、两份向量、检索时互相抢位</td></tr>
 *   <tr><td>PDF 与它的 DOCX 原件</td><td>同一内容两种容器，抽取出的文本几乎相同</td></tr>
 *   <tr><td>转存 / 另存过的同一文件</td><td>元数据与时间戳变了</td></tr>
 * </table>
 *
 * <h3>规范化只做一件"安全"的事</h3>
 * <p>把**空白折叠成单个空格**并去掉首尾空白。仅此而已。
 * <ul>
 *   <li>页眉页脚与页码**不在这里去** —— 那是 `O3` 的 {@code DocumentCleaner} 在解析后、
 *       分片前做的事。在这里再去一遍等于把同一件事写两遍，两遍的阈值迟早不一致
 *       （而"清理阈值改了一处没改另一处"的表现是"去重突然不灵了"）；</li>
 *   <li>**不做大小写归一、不做全角半角归一**。这两种归一都会让"看起来不一样但被判成一样"
 *       —— 而那是**误报**：用户会被告知"与《X》内容高度相同"，而它们其实不同。
 *       去重的代价必须偏向"漏报"而不是"误报"，因为误报会让人不再相信这个提示。</li>
 * </ul>
 *
 * <h3>⚠️ 只在同一解析器版本内可比（O9 §6 点名的风险）</h3>
 * <p>升级 Tika / opendataloader 之后，同一份文件抽出的文本可能变化 → 指纹跟着变。
 * 本类**不把 parserVersion 并进指纹输入**：那样会让跨版本比对永远不命中，
 * 而"升级后重复告警消失"这件事照样会发生，只是变得更难解释。
 * 当前口径是**只做同版本比较**（写入侧与读取侧都是这一批的解析器）。
 * 真要跨版本，得等 `O5` 的版本指纹落地后一起做 —— 那是另一件事，不是这里的问题。
 */
public final class ContentFingerprint {

    /** 指纹长度（sha256 的十六进制表示）。与迁移里两处 {@code CHAR(64)} 同源。 */
    public static final int LENGTH = 64;

    private ContentFingerprint() {
    }

    /**
     * 算指纹。
     *
     * @param text 原始文本
     * @return 64 位小写十六进制；{@code null} 或空白文本返回 **{@code null}**
     *         （"没有内容"不是"内容相同" —— 给空文本发一个固定指纹会让所有空块互相命中）
     */
    public static String of(String text) {
        String normalized = normalize(text);
        if (normalized == null) {
            return null;
        }
        return sha256(normalized);
    }

    /**
     * 规范化：空白折叠 + 去首尾。
     *
     * <p>覆盖的不只是 ASCII 空白：不换行空格（{@code  }）与全角空格（{@code 　}）
     * 在中英文混排的文档里很常见，而 {@code String.trim()} 只认 {@code <= ' '} ——
     * 用它会漏掉后者，于是"两份内容一样、一份从网页复制来带了全角空格"就成了两份。
     *
     * @return 规范化后的文本；{@code null} 或空白输入返回 {@code null}
     */
    public static String normalize(String text) {
        if (text == null) {
            return null;
        }
        // \s 在 Java 默认**不匹配**   / 　（它们不是 ASCII 空白），所以显式列出
        String collapsed = text.replaceAll("[\\s\\u00A0\\u3000\\u200B\\uFEFF]+", " ").trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    /**
     * 两个指纹是否"同一个内容"。
     *
     * <p>⚠️ **两侧都非空才比较**（迁移注释里的 NULL 口径）：
     * 一个 {@code null} 指纹（本批之前入库的旧数据）与另一个 {@code null}
     * 不代表"它们一样"，只代表"两边都没记" —— 当成相同会让一批旧文档互相报重复。
     */
    public static boolean same(String left, String right) {
        return left != null && right != null && left.equals(right);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(LENGTH);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            // JDK 必须提供 SHA-256；走到这里说明运行环境坏了，不是业务问题
            throw new IllegalStateException("JVM 不支持 SHA-256，无法计算内容指纹", ex);
        }
    }
}
