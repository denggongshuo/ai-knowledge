package com.knowledge.ai.core.text;

import java.util.ArrayList;
import java.util.List;

/**
 * 标识符保护分词器（`T-506` · `FR-RET-03`）。
 *
 * <h3>它解决的是"差一个字符的型号检索不出来"</h3>
 * <p>`07` §3.1 的原话：**向量检索对「差一个字符的型号」没有分辨力** ——
 * `A-100` 与 `A-1000` 在向量空间里几乎重合，而报价单的核心查询对象恰恰就是型号/料号。
 * 标准分词会把 `A-100` 切成 `A` + `100`，于是 `A-1000` 也被切成 `A` + `1000`，
 * **共享了 `A` 这个 token**，于是互相命中。这层防护的第一个动作就是
 * **让整条标识符成为一个不可再分的 token**（`07` §3.2 的分词层）。
 *
 * <h3>两个出口，一份规则</h3>
 * <ul>
 *   <li>{@link #tokenize(String)} —— token 序列。M07（S5）的 BM25 通道将来要用它，
 *       「查询侧标识符保护」也归那边（`01-业务模块拆分.md:237`）；</li>
 *   <li>{@link #count(String)} —— **长度**。Q-4 定稿的口径（`07:501`）：
 *       中日韩 **1 字 = 1**、连续 ASCII 词/型号整体算 **1**。
 *       `T-C00` 的图谱旁路阈值（提问长度 ≥ 6）与分片的 `chunkSize` 都吃它。</li>
 * </ul>
 *
 * <p><b>落点（本批只落一半，另一半的归属要写清）</b>：
 * <ul>
 *   <li>✅ 分片窗口 —— {@code ChunkingOptions#estimateTokens} 已改为委托 {@link #count}；</li>
 *   <li>⏭️ **图谱旁路阈值（`T-C00`）** —— `07:499` 的「提问长度 ≥ 6」**必须**用
 *       {@link #count}，不要另写 {@code length()} 或按空格切。`T-C00` 属后续批次，
 *       本批只把这个落点钉在这里，免得那边开写时又自己数一遍；</li>
 *   <li>⏭️ **查询侧标识符保护（`T-700`，S5）** —— `FR-RET-03` 的检索侧半边
 *       （查 `A-100` **不召回** `A-1000`）归 M07，见 {@code 01-业务模块拆分.md:237}。
 *       本批交付的是**分词器本身**（M05 拥有的那一半），够 M07 直接复用。</li>
 * </ul>
 * <p>⚠️ <b>{@code count} 就是 {@code tokenize().size()}，不是第二套规则。</b>
 * 写两遍必然会漂移，而"长度说是 7、实际切出 8 个 token"这种漂移没有任何测试看得见 ——
 * 除非有人恰好同时算了两边。「一份规则两个出口」是把漂移的可能性直接消掉。
 *
 * <h3>长度口径逐字对齐 Q-4 的四个例子</h3>
 * <pre>
 *   订单系统分片方案         = 8   （8 个汉字，触发图谱旁路）
 *   Milvus 分片扩容方案      = 7   （Milvus 算 1 + 6 个汉字）
 *   如何扩容                = 4   （不触发）
 *   Milvus 扩容             = 3   （不触发）
 * </pre>
 *
 * <h3>识别的标识符（`07` §3.3 的五类）</h3>
 * <table border="1">
 *   <caption>规则与实现</caption>
 *   <tr><th>类型</th><th>示例</th><th>实现</th></tr>
 *   <tr><td>字母数字编号</td><td>{@code A-100}、{@code SKU-8890-A}</td><td>字母数字与分隔符的连续段整体成 token</td></tr>
 *   <tr><td>金额</td><td>{@code ￥1,200}、{@code $1,500.00}</td><td>货币符号 + 千分位数字整体成 token</td></tr>
 *   <tr><td>日期</td><td>{@code 2026-09-11}、{@code 2026/09/11}</td><td>被"字母数字编号"那条覆盖（纯数字 + 分隔符）</td></tr>
 *   <tr><td>版本号</td><td>{@code v1.4}、{@code v3}</td><td>同理（字母 + 数字 + 点）</td></tr>
 *   <tr><td>错误码</td><td>{@code 10021}</td><td>数字串整体成 token（见下方"关于上下文条件"）</td></tr>
 * </table>
 *
 * <h3>关于错误码的"上下文"条件（一处刻意的口径取舍）</h3>
 * <p>`07` §3.3 对错误码写的是「纯数字（长度 ≥ 4）**且出现在"错误码/errno"上下文中**」。
 * <b>本实现不检查上下文，把任何数字串都整体当一个 token。</b> 理由：
 * 那个上下文条件服务的是**另一件事** —— 判断"这个数字串算不算一个实体/标识符"（图谱侧的实体抽取），
 * 而不是"要不要把它切成单字"。切成 `1`/`0`/`0`/`2`/`1` 对任何用途都没有好处，
 * 所以"数字串整体成 token"是无条件正确的；上下文判断留给真正需要它的地方。
 * ⚠️ 这是一处**有意的解释**而非疏漏，改动前先确认那件事确实需要它。
 *
 * <h3>中日韩按**单字**成 token（本仓没有分词器）</h3>
 * <p>`T-506` 明确不引 HanLP/jieba（计划 §5：本需求要解决的正是"标准分词把 `A-100` 切坏"，
 * 引一整套 NLP 栈来处理"计数 + 保标识符"是代价远大于收益）。没有词表就只能按单字切 ——
 * 好处是**可复现、与 Q-4 的计数口径天然一致**（1 字 = 1 token）。
 *
 * <p>代价说清楚：单字 BM25 的中文召回高、精度低（相邻字的共现噪声）。
 * 真要提升得靠 **bigram 或真分词器**，那是 `T-700`（S5 混合检索）落地时按评测集决定的事
 * ——`07` §3.4 的验收要点写的就是"建立评测集能验证查 `A-100` 不应召回 `A-1000`"，
 * 有评测集才谈得上调分词。**本类的两个出口在那之前不会变形状。**
 */
public final class IdentifierTokenizer {

    private IdentifierTokenizer() {
    }

    /** 货币符号（`07` §3.3 金额那行的"货币符号"，含全角 ￥ 与半角 ¥）。 */
    private static final String CURRENCY = "￥¥$€£";

    /** 标识符内部的连接符 —— 它们把若干"字母数字段"缝成**一个** token。 */
    private static final String JOINERS = "-_/";

    /**
     * 窗口计量里"多少个 ASCII 字符折 1 个 token"。
     *
     * <p>沿用本仓既有口径（英文约 4 字符 = 1 token），见 {@link #windowTokens(String)}。
     */
    private static final double ASCII_CHARS_PER_TOKEN = 4.0;

    /**
     * 切词。
     *
     * <p>输出**只含 token**：空白与不构成 token 的标点（`，。！` 等）都被丢掉 ——
     * 它们对 Q-4 的长度口径没有贡献（"`Milvus 分片扩容方案` = 7"里的空格就没算）。
     *
     * @return 不可变的 token 列表；入参为空时返回空列表（**不是 null**：调用方不必判空）
     */
    public static List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (isCjk(c)) {
                // 中日韩：1 字 = 1 token（没有词表，按单字切，见类 javadoc）
                tokens.add(String.valueOf(c));
                i++;
                continue;
            }
            if (CURRENCY.indexOf(c) >= 0) {
                int end = matchCurrency(text, i);
                if (end > i + 1) {
                    tokens.add(text.substring(i, end));
                    i = end;
                    continue;
                }
                // 光秃秃一个货币符号（"￥"后面没跟数字）—— 不是金额，当标点丢掉
                i++;
                continue;
            }
            if (isWordChar(c)) {
                int end = matchWord(text, i);
                tokens.add(text.substring(i, end));
                i = end;
                continue;
            }
            // 其余（各类标点、emoji 等）不贡献 token
            i++;
        }
        return List.copyOf(tokens);
    }

    /**
     * Q-4 定稿的**长度口径**（`07:501`）。
     *
     * <p>= {@link #tokenize(String)} 的长度。**不要另写一套计数逻辑**：
     * 两套规则的漂移不会有任何测试发现，除非有人恰好同时算了两边。
     *
     * <p>用途：`T-C00` 的图谱旁路阈值（提问长度 ≥ 6，`07:499`），
     * 以及任何"这段话有多长"的语义判断。
     *
     * <p>⚠️ **不要拿它当"会不会撑爆嵌入模型窗口"的判据** —— 那件事用
     * {@link #windowTokens(String)}，理由见那边。
     */
    public static int count(String text) {
        return tokenize(text).size();
    }

    /**
     * **窗口计量**：分片窗口（`chunkSize`）该不该切断，看的是这个值。
     *
     * <h3>为什么它不能等于 {@link #count}</h3>
     * <p>Q-4 的规则是「连续 ASCII 词/型号**整体算 1**」—— 对一个提问是对的
     * （`Milvus` 就是一个词）。但同一个规则用在**窗口**上会裂开：
     * 一段没有任何分隔符的长串（`"A" × 4000`）整体算 **1 个 token**，
     * 于是"超过窗口就切断"永不触发、硬切兜底永不启动 ——
     * 产出一个 4000 字符的块去撞嵌入模型，而那道兜底的**全部意义**就是防止这件事。
     *
     * <p>这不是假想：把 `ChunkingOptions#estimateTokens` 直接改成委托 {@link #count} 之后，
     * `ChunkingServiceTest#textWithoutSeparatorsIsHardSplit` **当场转红**。
     * 那条用例保的是"宁可切坏，也不能产出超长块"，是真被触发的安全属性。
     *
     * <h3>口径</h3>
     * <p>逐 token 累加，**每个 token 的成本 = {@code max(1, 字符数 / 4)}**：
     * <ul>
     *   <li>中日韩单字 token（1 字符）→ {@code max(1, 0.25)} = **1**，与"1 字 = 1"一致；</li>
     *   <li>普通英文词（`Milvus`，6 字符）→ 2；BPE 对 6 个字母大致就是 1~2 个 token；</li>
     *   <li>退化长串（4000 字符）→ 1000 —— 兜底这才重新生效。</li>
     * </ul>
     * <p>{@code 4} 这个除数沿用本仓 `S4-M05-C1` 时期的既有口径（"英文约 4 字符 = 1 token"），
     * 不为这个批次另换一个数 —— 换了就得重新标定所有分片判据，而收益是零。
     *
     * <p>⚠️ 仍是**估算**：真正精确的值只有嵌入模型自己的 tokenizer 知道，
     * 那是模型私有的、本仓无从复现。所以这里是"把偏差控制在不会撑爆的程度"，
     * 不是"算对了"。
     */
    public static double windowTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0;
        for (String token : tokenize(text)) {
            tokens += Math.max(1, Math.ceil(token.length() / ASCII_CHARS_PER_TOKEN));
        }
        return tokens;
    }

    // ==================================================================
    // 扫描细节
    // ==================================================================

    /**
     * 字母数字"词"的匹配：`字母/数字` 段之间由 {@code - _ /} 或**后面跟着字母数字的** `.` 连接。
     *
     * <p>为什么点是特例：`v1.4` 里的点是标识符的一部分，而 `end.` 里的点是句号。
     * 判据取"点后面还跟着字母数字吗"—— 句末的点后面是空白或结尾，于是不会被吸进来。
     * 若不做这个区分，`详见 v1.4.` 会切出一个 `v1.4.`（尾点被吞）。
     */
    private static int matchWord(String text, int start) {
        int i = start;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (isWordChar(c)) {
                i++;
                continue;
            }
            boolean joiner = JOINERS.indexOf(c) >= 0 || c == '.';
            // 连接符必须**后面真的有字母数字**才算数：否则尾随的 `-`/`/`/`.` 会被吞进 token
            if (joiner && i + 1 < n && isWordChar(text.charAt(i + 1))) {
                i += 2;
                continue;
            }
            break;
        }
        return i;
    }

    /**
     * 金额的匹配：货币符号 + 可选空白 + 数字（可带千分位逗号与小数）。
     *
     * <p>为什么金额要单独一条规则：逗号不在 {@link #isWordChar} 里，所以
     * `￥1,200` 不加这条会切成 `1` 与 `200` 两个 token —— 而 `07` §3.3 要求它整体成 token
     * （"查「￥1,200」能精确命中"是 §3.4 的验收要点之一）。
     *
     * @return 结束下标；**没有匹配到数字时返回 {@code start + 1}**（调用方据此丢掉光秃秃的符号）
     */
    private static int matchCurrency(String text, int start) {
        int i = start + 1;
        int n = text.length();
        while (i < n && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i >= n || !Character.isDigit(text.charAt(i))) {
            return start + 1;
        }
        while (i < n && (Character.isDigit(text.charAt(i)) || text.charAt(i) == ',')) {
            i++;
        }
        // 小数：点后面必须真的跟着数字（与 matchWord 里"点"的处理同一个判据）
        if (i + 1 < n && text.charAt(i) == '.' && Character.isDigit(text.charAt(i + 1))) {
            i++;
            while (i < n && Character.isDigit(text.charAt(i))) {
                i++;
            }
        }
        return i;
    }

    /**
     * "词"的基本单位：ASCII 字母数字 + **全角字母数字**。
     *
     * <p>全角（`Ａ`~`Ｚ`、`ａ`~`ｚ`、`０`~`９`）要算进来，否则中文文档里写成 `１００`
     * 或 `Ｍｉｌｖｕｓ` 的内容会被当作标点**整个丢掉** —— 词表里一个 token 都不剩，
     * 那段文字就此检索不到（"少了一点东西而没人发现"的形态）。
     *
     * <p>⚠️ 注意这是"**不被丢掉**"，不是"与半角等价"：`Ａ-100` 与 `A-100`
     * 切出来是**两个不同的 token**，本类不做全角半角归一。
     * 归一（`NFKC`）是另一件事，要动就得连标点一起动，不能在这里顺手做半个 ——
     * 半个归一比不归一更难排查。
     */
    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || (c >= '０' && c <= '９')     // 全角数字
                || (c >= 'Ａ' && c <= 'Ｚ')     // 全角大写
                || (c >= 'ａ' && c <= 'ｚ');    // 全角小写
    }

    /**
     * 中日韩字符（`07` §501 口径里说的"中日韩"）。
     *
     * <p>覆盖：汉字（含扩展 A 与兼容区）、日文平假名/片假名、韩文音节与字母。
     * 刻意**不含**全角标点（`，。！` 等）—— 它们与半角标点一样不贡献 token。
     */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)     // CJK 统一汉字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0xF900 && c <= 0xFAFF)  // 兼容汉字
                || (c >= 0x3040 && c <= 0x309F)  // 平假名
                || (c >= 0x30A0 && c <= 0x30FF)  // 片假名
                || (c >= 0xAC00 && c <= 0xD7AF)  // 韩文音节
                || (c >= 0x1100 && c <= 0x11FF)  // 韩文字母
                || (c >= 0x3130 && c <= 0x318F); // 韩文兼容字母
    }
}
