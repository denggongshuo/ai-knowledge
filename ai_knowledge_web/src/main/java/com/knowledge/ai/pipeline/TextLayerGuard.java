package com.knowledge.ai.pipeline;

/**
 * 「无文本层」检测（`T-0S1` §六-1 点名的 **P0 必做项**）。
 *
 * <h3>为什么这条检测值得单独一个类</h3>
 * <p>《02-需求总览》把「静默降级视为缺陷」写成了验收口径，而本项目**最典型**的静默故障就是它：
 * 一份 21.8MB 的扫描件，opendataloader 在 **15ms** 内"成功"产出 **0 字符** ——
 * 不加检测，文档会一路走到「已就绪」，用户搜不到任何内容，且**无从知道为什么**。
 * 把判据收拢在一个类里，两条解析路径（PDF / Tika）才不会各写一份、各漂一份。
 *
 * <h3>判据与阈值出处</h3>
 * <p>`T-0S1` §六-1 只给了方向（"产出字符数 == 0，或 字符数/页数 低于阈值"），没给数。
 * 本实现取 <b>20 字/页</b>，依据是开工前的实测（S4-M05-B 计划 §1 样本表）：
 * <ul>
 *   <li>扫描件（健康生活方式.pdf）：15 页产出 0 字符 → <b>0 字/页</b>，命中；</li>
 *   <li>最"稀"的正常样本（幻灯片型 PDF）：约 <b>294 字/页</b> → 距阈值一个数量级，不误伤。</li>
 * </ul>
 * 一页正文哪怕只有几行也有几十字；20 是保守下限。**若将来出现误判，应下调阈值而不是取消检测**
 * —— 取消它等于把"21.8MB 产出 0 字节报成功"这个故障重新放出来。
 *
 * <h3>命中后的行为</h3>
 * <p>抛 {@link ParseException}，文案同时给出**现象**（未提取到可复制文本）与**下一步动作**
 * （补录文本 / 启用 OCR 后重传）—— 这是 {@code G4-3}「失败给出具体原因」的正面样板。
 */
public final class TextLayerGuard {

    /**
     * 每页字符数下限。低于它视为"无文本层"。
     *
     * <p>⚠️ 数值的依据写在类注释里（实测余量：0 vs 294）。改它之前先重跑样本。
     */
    static final double MIN_CHARS_PER_PAGE = 20.0;

    private TextLayerGuard() {
    }

    /**
     * 校验解析产物"确实抽到了文本"。
     *
     * <p><b>为什么密度判据只在 {@code pages >= 2} 时生效</b>：扫描件的形状是
     * 「**页数多** + 每页几乎没字」，不是「单页文档本来就短」。
     * 一份 27 字节的 MD 便利贴（单页 10 字）是完全正常的文档，
     * 拿密度去卡它会把"短文档"误报成"扫描件"，而给出的建议
     * （补录文本 / 启用 OCR）对用户完全无从下手 —— 误报比漏报更伤。
     * 所以单页文档只判**绝对零**（一个字都没有才是真问题）。
     *
     * @param parsed 解析产物（非空）
     * @throws ParseException 字符数为 0，或多页文档的每页平均字符数低于 {@link #MIN_CHARS_PER_PAGE}
     */
    public static void check(ParsedDocument parsed) {
        // 1) 绝对零：一个字符都没有。这是扫描件在 opendataloader 下的确切形状
        //    （15 页、15ms、0 字符 —— 24ms 的耗时说明它根本没做逐页解析）。
        if (parsed.chars() <= 0) {
            throw new ParseException(noTextMessage(parsed));
        }
        // 2) 相对稀疏：**多页**文档里字少得反常（如 300 页只有 500 字）。
        //    单看总量会漏掉这种"看似有内容"的中间态，所以多页时才加这一条。
        if (parsed.pages() >= 2 && parsed.charsPerPage() < MIN_CHARS_PER_PAGE) {
            throw new ParseException(noTextMessage(parsed));
        }
    }

    private static String noTextMessage(ParsedDocument parsed) {
        return "未提取到可复制文本（疑似扫描件或纯图片，"
                + parsed.pages() + " 页仅得到 " + parsed.chars() + " 字符），"
                + "该文档内容无法被检索命中；请补录文本版本，或启用 OCR 后重新上传";
    }
}
