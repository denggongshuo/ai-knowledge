package com.knowledge.ai.pipeline.enhance;

import com.knowledge.ai.core.enums.BlockType;
import com.knowledge.ai.pipeline.Block;

import java.util.regex.Pattern;

/**
 * HyQ 生成门控（`T-O09` 步 5）—— **成本与收益的直接交易**。
 *
 * <h3>为什么需要一个门控</h3>
 * <p>`O9` 摸底实测：索引侧的一次性成本里，**文档级摘要 + HyQ 合计占 63%**，
 * 而 HyQ 是**分片级、逐分片调用**（`D-5` §1.6）—— 分片越多越贵，**与文档量线性增长**。
 * 一篇 200 页手册切成 300 块就是 300 次 LLM 调用，而其中**大量块是普通叙述**，
 * HyQ 对它们几乎无增益（"假设有人会这样问这段流水账吗"——不会）。
 *
 * <h3>门控规则为什么可以这么粗</h3>
 * <p>它是**启发式**的：块类型 + 长度 + 特征词。`O9` 自己写明了风险 ——
 * 「门控可能砍掉真正有用的 HyQ」，并要求"必须先有基线 + A/B，用数据定规则"。
 * ⚠️ 本批**只落了规则与调用计数**：`O5` 的解析质量基线已经在手，
 * 但"关掉门控后召回质量差多少"要真模型才能量，属挂起项（见计划 §6+7 判据 11 与 §9）。
 * 所以这里的阈值是**保守**的（宁可多生成，不少生成）—— 在没有量化依据之前，
 * 把规则定得激进等于**拿召回质量换成本而没人知道换了多少**。
 *
 * <h3>纯函数</h3>
 * <p>不碰 IO、不碰模型、无状态 —— 门控规则是这套方案里最需要被反复调整的部分，
 * 做成纯函数才能一改就单独跑用例验证（不启动容器、不调模型）。
 */
public final class HyqGate {

    /**
     * 「短段落」的长度上限（字符）。
     *
     * <p>取 200：`O9` §6 的判据 5 要的是「长叙述性 PARAGRAPH → 跳过」，
     * 而"叙述"与"参数/步骤"的分界不在字数是**形态** —— 字数只是第一道粗筛。
     * 200 字大致是一段"有信息密度"的说明（规格条目、故障处置、参数表注），
     * 超过它基本就是连续叙述了。
     */
    static final int SHORT_PARAGRAPH_MAX_CHARS = 200;

    /**
     * 「含参数」的特征：阿拉伯数字 + 量纲/单位。
     *
     * <p>不要求匹配具体单位（那会漏掉"3 档""5 步""2 类"这类），
     * 只要求**出现数字且前后有实义字符** —— 纯数字的段落（如目录页码）不在此列。
     */
    private static final Pattern HAS_PARAMETER = Pattern.compile("\\d+(\\.\\d+)?\\s*[%°℃]|"
            + "[\\u4e00-\\u9fa5A-Za-z]\\s*\\d+|[\\d]+\\s*[\\u4e00-\\u9fa5A-Za-z]");

    /**
     * 「问句特征」：疑问词或问号。
     *
     * <p>带问号的段落本身就长得像问句，拿它生成假设问句的命中率最高。
     */
    private static final Pattern HAS_QUESTION = Pattern.compile(
            "[?？]|如何|怎么|怎样|是否|能否|多少|哪些|什么|为什么|何时|何地|哪一");

    /**
     * 「步骤特征」：有序步骤的常见写法。
     *
     * <p>⚠️ 本仓的分片管线目前**不产出 `LIST` 块**（`ChunkingService` 只产出
     * `HEADING`/`PARAGRAPH`/`TABLE`），所以这条规则当前命中的是**写在段落里的步骤**
     * （"第一步……"）。它保留 `LIST` 分支是为了 `D-5` 的块类型一旦补齐就自动生效。
     */
    private static final Pattern HAS_STEPS = Pattern.compile(
            "第[一二三四五六七八九十]+步|步骤\\s*\\d|^\\s*\\d+[.、)]|首先|其次|然后|最后");

    private HyqGate() {
    }

    /**
     * 这个块该不该为它生成 HyQ。
     *
     * @return {@code true} = 值得花一次分片级调用
     */
    public static boolean shouldGenerate(Block block) {
        return reason(block) != null;
    }

    /**
     * 门控结论的**理由**（{@code null} = 跳过）。
     *
     * <p>让门控输出理由而不是布尔值，是为了让"为什么这篇文档少跑了 200 次调用"
     * 在运行期可回答 —— 成本治理最怕的就是"省了，但没人知道省掉了什么"。
     * 日志与用例都用它。
     */
    public static String reason(Block block) {
        if (block == null) {
            return null;
        }
        BlockType type = block.blockType();
        String text = block.rawContent() == null ? "" : block.rawContent().strip();
        if (text.isEmpty()) {
            return null;
        }

        // 表格：整表 Markdown —— 参数/型号/报价密集，"有人会怎么问这张表"最容易答
        if (type == BlockType.TABLE) {
            // 只从**父块**生成：父块是整表（Markdown），子块是一行。
            // 父块不进检索池，但 HyQ 进 —— 命中后按 source_chunk_id 取回父块整表，
            // 这正是"问句用于召回、源文用于回答"的典型形态
            return block.isTopLevel() ? "表格父块：整表参数密集，假设问句收益最高" : null;
        }

        // 列表：含步骤的才值得（纯枚举型列表生成问句价值低）
        if (type == BlockType.LIST) {
            return HAS_STEPS.matcher(text).find() ? "列表：含步骤，适合生成操作类问句" : null;
        }

        // 正文段：短 + 含参数或问句特征才生成；长叙述跳过（这是本门控省钱的主力）
        if (type == BlockType.PARAGRAPH) {
            if (text.length() > SHORT_PARAGRAPH_MAX_CHARS) {
                return null;
            }
            if (HAS_QUESTION.matcher(text).find()) {
                return "短段落：含问句特征，天然接近用户问法";
            }
            if (HAS_PARAMETER.matcher(text).find()) {
                return "短段落：含参数/数值，适合生成事实型问句";
            }
            if (HAS_STEPS.matcher(text).find()) {
                return "短段落：含步骤，适合生成操作类问句";
            }
            return null;
        }

        // 标题 / 代码 / 图片 / 摘要 / HyQ 自身：都不生成。
        // 标题是"这一段叫什么"，拿它生成问句是噪声；摘要已经是浓缩过的内容，
        // 再为它生成问句等于在同一段文字上花两次钱
        return null;
    }
}