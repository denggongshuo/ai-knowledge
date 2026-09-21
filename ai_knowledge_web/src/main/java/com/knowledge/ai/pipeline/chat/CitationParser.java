package com.knowledge.ai.pipeline.chat;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从答案正文里解析**行内引用角标**（`T-804` · 需求 `07` §6.6）。
 *
 * <h3>它解析的是一个"我们自己定义的小语言"</h3>
 * <p>角标的形态**由 {@code ChatStreamService#prompt} 定义**（`[1]` / `[1][3]`），
 * 不是模型自由发挥的 Markdown。所以这里用一条正则就够，不需要引入 Markdown 解析器
 * —— 见计划 §5 的依赖决策。
 *
 * <h3>🔴 为什么编号与标题路径的括号**必须不同形**</h3>
 * <p>资料块写成 `[1] （第 3 页 3.1 鉴权流程） 正文…`：编号用**半角方括号**（与角标同形，
 * 模型看到 `[1]` 才会照着打 `[1]`），标题路径用**全角圆括号**。
 * 若两处都用 `[...]`，一个形如 `[12]` 的标题路径会被解析成"引用了第 12 条" ——
 * 那是一条**假引用**，而它在页面上看起来完全正常。
 *
 * <h3>认不出的一律忽略，不猜、不抛</h3>
 * <p>三条边界：
 * <ul>
 *   <li>越界（`[99]` 而只喂了 5 条）⇒ **丢掉**。猜一个最近的编号会造出一条假引用；</li>
 *   <li>非数字（`[a]` / `[注]`）⇒ 正则根本不匹配，天然忽略；</li>
 *   <li>重复（`[1]…[1]`）⇒ **去重**，但它只算一次引用（`cited` 是布尔列）。</li>
 * </ul>
 *
 * <p>⚠️ **解析结果为空时，调用方必须写 `NULL` 而不是 `0`** —— `0` 是一句关于内容的断言
 * （"这条没被引用"），而"解析不出来"是"没采集到"。两者在页面上长得一样，在语义上差一个量级。
 * 见 {@code RetrievalTraceWriter} 的 `cited` 写入分支。
 */
public final class CitationParser {

    /**
     * 角标：`[` + 1~3 位数字 + `]`。
     *
     * <p>上限 3 位是**与 prompt 一致**的约束（喂给模型的资料条数远小于 1000），
     * 而不是"防大数"：`[1234]` 若被当成合法角标，它一定是模型编的。
     */
    private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,3})]");

    private CitationParser() {
    }

    /**
     * 解析角标编号。
     *
     * @param answer      模型输出的完整正文（可能是空串 / {@code null}）
     * @param chunkCount  本次喂给模型的资料条数 —— **越界判定的依据**
     * @return **按出现顺序去重**的编号集合（1 起）。解析不出任何角标时返回**空集**
     *         （调用方据此写 `NULL`，见类注释）
     */
    public static Set<Integer> parse(String answer, int chunkCount) {
        if (answer == null || answer.isEmpty() || chunkCount <= 0) {
            return Set.of();
        }
        Set<Integer> ranks = new LinkedHashSet<>();
        Matcher m = MARKER.matcher(answer);
        while (m.find()) {
            int no;
            try {
                no = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ex) {
                // 3 位以内不可能溢出；真发生就跳过这一条，绝不让整轮问答失败
                continue;
            }
            if (no >= 1 && no <= chunkCount) {
                ranks.add(no);
            }
            // 越界的静默丢掉：模型偶尔会打 `[6]` 而只喂了 5 条。
            // 这里**刻意不留日志** —— 一次生成里可能出现多个越界角标，
            // 而"模型多打了个数字"不值得在每次问答里刷日志（它不影响任何落库结果：
            // 丢掉它 = 这条没被引用，而那正是事实）
        }
        return ranks;
    }
}
