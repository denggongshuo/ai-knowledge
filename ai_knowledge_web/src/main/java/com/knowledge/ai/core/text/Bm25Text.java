package com.knowledge.ai.core.text;

import java.util.List;

/**
 * BM25 的**文本口径**（`S5-M07-A`）—— 把一段文本变成"进 BM25 的那串 token"。
 *
 * <h3>为什么必须有这个类（而不是在写入侧和检索侧各写一次）</h3>
 * <p>Milvus 的 BM25 输入列配的是 {@code whitespace} 分词器 —— 也就是说
 * <b>Milvus 自己几乎不做分词</b>（只按空白切、不做大小写归一），"什么算一个 token"
 * 完全由本仓决定。于是<b>写入侧与查询侧必须切出同一套 token</b>，
 * 否则会出现"这段文本明明在里面却检索不到"—— 而它**不报错**，只是结果不对。
 *
 * <p>本类就是那个唯一口径处：{@link IdentifierTokenizer} 切词，空格连接。
 *
 * <h3>为什么要过 {@link IdentifierTokenizer}（而不是让 Milvus 自己分）</h3>
 * <p>步 0 探针实测（2026-09-17，五组对照样本）：
 * <ul>
 *   <li>Milvus 内置 <b>standard</b>：把 {@code A-100} 切成 {@code a}+{@code 100}
 *       → 查 {@code A-100} <b>召回了 {@code A-1000}</b>；</li>
 *   <li>Milvus <b>jieba</b>：保住 {@code A-100}/{@code v1.4}，
 *       但<b>保不住</b> {@code ￥1,200} / {@code SKU-8890-A} / {@code 2026-09-11}；</li>
 *   <li><b>本类</b>：Q-4 的五类标识符 <b>10/10 零串扰</b>（`G4-8` 运行时侧的判据）。</li>
 * </ul>
 *
 * <h3>已知代价：中文退化为单字 token</h3>
 * <p>{@link IdentifierTokenizer} 对 CJK 是「1 字 = 1 token」（没有词表）。这把中文的
 * BM25 精度压低了 —— 但它是**为标识符保护付的代价**，且有两层兜底：
 * RRF 只看**排名**（不看分数绝对值）、Rerank 用交叉编码器重新打分。
 * 量化与判定点是 `T-711` 评测集的 Recall@5；若不合格，备选方案是「双字段
 * （jieba 一路 + 本类一路）双 BM25 函数」，那条路不推翻本类，只是再加一路。
 */
public final class Bm25Text {

    private Bm25Text() {
    }

    /**
     * 把文本切成 BM25 的 token 串（空格分隔）。
     *
     * @param text 原文；{@code null}/空白 → 空串（**不是 null**：Milvus 那列未声明 nullable，
     *             缺值必须写空串，否则整批插入被拒）
     */
    public static String of(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return String.join(" ", IdentifierTokenizer.tokenize(text));
    }

    /**
     * 同 {@link #of(String)}，但保证结果不超过 {@code maxChars}。
     *
     * <p>按 **token 边界**截断（丢掉整条超出的 token），不做字符级截断 ——
     * 后者会把最后一个 token 切一半，凭空造出一个原文里不存在的词项，
     * 那是一种"检索得到但谁也解释不了为什么"的脏数据。
     *
     * <p>正常路径不该走到这里：单块的投影文本远小于 Milvus 的 64K 上限。
     * 它是**兜底**，走到时至少要留日志（调用方负责）。
     *
     * @return 截断后的 token 串；{@code truncated=true} 表示确实丢了 token
     */
    public static Result of(String text, int maxChars) {
        String full = of(text);
        if (full.length() <= maxChars) {
            return new Result(full, false);
        }
        List<String> tokens = IdentifierTokenizer.tokenize(text);
        StringBuilder sb = new StringBuilder(Math.min(full.length(), maxChars));
        for (String token : tokens) {
            int extra = token.length() + (sb.isEmpty() ? 0 : 1);
            if (sb.length() + extra > maxChars) {
                break;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(token);
        }
        return new Result(sb.toString(), true);
    }

    /**
     * @param text      进 BM25 的 token 串
     * @param truncated 是否因长度上限丢了 token
     */
    public record Result(String text, boolean truncated) {
    }
}
