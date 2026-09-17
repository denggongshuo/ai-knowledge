package com.knowledge.ai.core.text;

/**
 * 跨文档样板的判定规则（`T-O09` 步 3）—— **双阈值**，两个都过才算样板。
 *
 * <h3>为什么要有"出现频次"这一维</h3>
 * <p>`O9` §2.2 的量化后果：假设 top-20 里有 6 条是同一段"免责声明"，**实际有效候选只剩 14 条**
 * —— 直接拉低召回率，而且**污染 RRF 融合**（同一内容占多个排名位，倒数排名相加后权重被虚高）。
 * 用户看到的现象是"召回了很多条，但答案里全是废话"。
 *
 * <h3>为什么要有"长度"这一维（`O9` 判据 4）</h3>
 * <p>频次单独用会**误杀**。反例：一条"注意事项：请勿断电"出现在 50 个文档里 ——
 * 它是**真样板**，该抑制；而"总计"两个字出现得更多，却毫无意义。
 * 所以再加两条边界：
 * <table border="1">
 *   <caption>长度双阈值的理由</caption>
 *   <tr><th>边界</th><th>挡住的误杀</th></tr>
 *   <tr><td>短于 {@link #MIN_LENGTH} 字符</td>
 *       <td>"总计" / "备注" / "（续）"这类**通用词**。它们高频但无信息，
 *           而且把它们标成样板会让"这一页只有一行'备注'"这种块彻底消失</td></tr>
 *   <tr><td>长于 {@link #MAX_LENGTH} 字符</td>
 *       <td>"一模一样的长正文"基本不可能是样板 —— 它更可能是**同一份文档传了两遍**。
 *           那条路由内容指纹（{@link ContentFingerprint}）在文档级去重，
 *           不该由"样板抑制"顺手处理掉：后者是**静默让内容搜不到**，
 *           而前者会**明确告诉用户"与《X》高度相同"**</td></tr>
 * </table>
 *
 * <h3>长度按**字符数**算，不是 token 数</h3>
 * <p>用 {@code String.length()}。之所以不用 {@link IdentifierTokenizer#count}：
 * 那是**检索语义**的长度（中文 1 字 = 1、ASCII 词整体算 1），而这里的阈值是
 * 一条"这段文本够不够长到不像通用词"的**语料统计口径**，与检索无关。
 * 两者混用会让"改分词规则"顺手改掉样板判定 —— 那是没人会预料到的连带影响。
 *
 * <h3>阈值是**语料级**的，冷启动期不生效是正常的（`O9` §6）</h3>
 * <p>前 20 篇文档里，任何一段文本的文档数都到不了 {@link #MIN_DOCS}，
 * 于是没有一条会被标成样板 —— 这是**正确行为**而不是缺陷：
 * 20 篇里出现 19 次的段落，还没有证据说它是"跨文档样板"。
 * 真正的历史样板要靠离线回扫（`O9` §6 的建议，本批不做，见计划 §3.3）。
 */
public final class BoilerplateRule {

    private BoilerplateRule() {
    }

    /**
     * 判定为样板所需的**最少文档数**。
     *
     * <p>取值来源：{@code O9} §4 步 3「计数 > 阈值（建议出现在 ≥20 个文档）」与
     * 判据 3「把同一段免责声明放进 25 个文档 → 该 chunk {@code is_boilerplate = true}」。
     * 取 `≥20`（而不是判据里的 25）是因为 25 是**测试用的样本量**，
     * 20 才是规则 —— 两者不冲突：25 篇的语料当然满足 20。
     *
     * <p>⚠️ **含本文档自己**。判据 3 的样本是"25 个文档"，若不含自己则第 25 篇时
     * 计数为 24、恰好差一篇不触发。口径写死在这里：`计数 = 含当前文档在内的、
     * 同库中含该指纹的**不同文档**数`。
     */
    public static final int MIN_DOCS = 20;

    /** 长度下界（字符数）。见类注释。 */
    public static final int MIN_LENGTH = 200;

    /** 长度上界（字符数）。见类注释。 */
    public static final int MAX_LENGTH = 2_000;

    /**
     * 该段文本是否**够格**参与样板判定（长度在合法区间内）。
     *
     * <p>单独暴露这个方法是为了让"为什么没被标"可回答：
     * 调用方可以对没被标记的块追问一句"是频次不够还是长度不够"。
     */
    public static boolean lengthInRange(String normalizedText) {
        if (normalizedText == null) {
            return false;
        }
        int length = normalizedText.length();
        return length >= MIN_LENGTH && length <= MAX_LENGTH;
    }

    /**
     * 是否应标记为样板。
     *
     * @param docCountWithSameFingerprint **含当前文档在内**的、同库中含有该指纹的不同文档数
     * @param normalizedText             规范化后的分片文本（见 {@link ContentFingerprint#normalize}）
     * @return {@code true} = 标为样板，**不进向量召回池**
     */
    public static boolean shouldMark(long docCountWithSameFingerprint, String normalizedText) {
        return docCountWithSameFingerprint >= MIN_DOCS && lengthInRange(normalizedText);
    }
}
