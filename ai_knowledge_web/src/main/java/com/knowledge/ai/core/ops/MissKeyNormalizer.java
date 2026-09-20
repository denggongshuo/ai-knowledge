package com.knowledge.ai.core.ops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 未命中池的**聚合键**（`T-A00` · 需求 `08` §1.2 的「提问次数」怎么算）。
 *
 * <h3>这是 `R-6`「语义聚合」本批做的那一半</h3>
 * <p>排期风险表把 `R-6` 标成「实现未定」，并给了一条退化方案：
 * 「按**归一化后的问句**分组 + 相似度阈值合并」。本批（用户 2026-09-20 裁定）
 * **只做左边那一半**：
 * <ul>
 *   <li>**做**：归一化 —— 同一问法的不同标点/空格/全半角/大小写**必然合并**；</li>
 *   <li>**不做**：相似度阈值合并 —— 「意思一样但问法不同」**不合并**。</li>
 * </ul>
 *
 * <p>理由是那条阈值**没有语料可校准**：库里真实提问几乎为 0，量出来的只会是
 * "我造的那几句"的分布。本仓已经在「没有正样本语料就定阈值」上反复吃亏
 * （`T-O03` 的三个阈值至今卡着）。**零阈值的东西才能被机器对账。**
 *
 * <h3>⚠️ 归一化的每一刀都要能说出理由</h3>
 * <table>
 *   <tr><th>这一步</th><th>合并掉什么</th><th>为什么安全</th></tr>
 *   <tr><td>NFKC</td><td>全角/半角、兼容字符（`ＳＱＬ` ≡ `SQL`）</td>
 *       <td>同一句话在不同输入法下的两种写法，**含义完全相同**</td></tr>
 *   <tr><td>去标点/符号/控制/空白</td><td>`？` `！` `,` 零宽字符</td>
 *       <td>标点只影响语气，不影响"问的是哪件事"；零宽字符更是不可见噪声</td></tr>
 *   <tr><td>小写</td><td>英文大小写（`Milvus` ≡ `milvus`）</td>
 *       <td>中文无影响；英文里大小写在提问中不承载语义</td></tr>
 * </table>
 *
 * <p><b>刻意不做的两刀</b>（做了会**错合并**，比漏合并坏得多 —— 漏合并只是提问次数偏小，
 * 错合并会把两个不同的问题算成"很多人问"）：
 * <ol>
 *   <li>**不做同义词替换**（`怎么` ≡ `如何`）—— 那是语义，不是归一化；</li>
 *   <li>**不做词序调整**（`A 与 B 的差异` ≡ `B 与 A 的差异`）—— 中文里词序常带含义。</li>
 * </ol>
 *
 * <h3>为什么最后取 SHA-256 而不是直接存归一化文本</h3>
 * <p>定长 64 字符 + 唯一索引，索引小、不比长文本。⚠️ 代价是**库里看不出为什么两行合并了**
 * —— 所以排查要靠"取两条原始 `question` 手算一次"（本类的单测就是那份对照表）。
 */
public final class MissKeyNormalizer {

    /**
     * 要抹掉的字符类：标点 / 符号 / 控制字符（含零宽）/ 各类空白。
     *
     * <p>⚠️ `\p{C}` 覆盖 `Cc`（控制）、`Cf`（格式，零宽连接符与 BOM 都在这里）、
     * `Co`/`Cs`（私用区与代理对）。不带上它，从网页复制来的问句会带着**看不见的字符**
     * 落进键里 —— 那会让"同一问法"在不同复制来源下变成两个键。
     */
    private static final Pattern NOISE = Pattern.compile("[\\p{P}\\p{S}\\p{C}\\s]");

    private MissKeyNormalizer() {
    }

    /** 问句 → 聚合键（64 位十六进制）。 */
    public static String keyOf(String question) {
        return sha256(normalize(question));
    }

    /**
     * 问句 → 归一化文本。**公开是给对账与排查用的**：池子里两行为什么合并，
     * 拿它们的原始问句各跑一次本方法，看结果是否相同即可。
     */
    public static String normalize(String question) {
        if (question == null) {
            return "";
        }
        String nfkc = Normalizer.normalize(question, Normalizer.Form.NFKC);
        String flat = NOISE.matcher(nfkc).replaceAll("").toLowerCase(Locale.ROOT);
        // 全标点的问句（`？？？`）去掉标点后会变成空串 —— 那会让它们**全部塌成一行**。
        // ⚠️ 退化时用的是**已经 NFKC 过的那一份**，不是原文：否则 `！！！` 与 `!!!`
        // 会因为"原文不同"而分成两个键 —— 而它们本来就是同一串字符的两种写法。
        if (flat.isEmpty()) {
            return nfkc.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        }
        return flat;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必须支持的算法，走不到这里；真走到了也不该静默降级成
            // "不聚合"（那会让同一问句每次提问都新建一行，池子飞涨）
            throw new IllegalStateException("JDK 不支持 SHA-256", ex);
        }
    }
}
