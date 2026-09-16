package com.knowledge.ai.pipeline.cleaning;

/**
 * 清洗参数（`优化/O3` §6 的风险对策：阈值**必须是配置，不是常量**）。
 *
 * <h3>为什么做成参数而不是常量</h3>
 * <p>`O3` §6 明写：清洗最大的风险是**误杀** —— 剥掉页眉页脚的同时把正文也剥了一块，
 * 而这件事在页面上**看不出来**（文档照样显示"已就绪"）。阈值可配，才好在实测误杀时
 * 只调数字、不动架构；写死成常量就只剩改代码一条路，而下一次误杀又要再改一次。
 *
 * <h3>默认值一律偏保守</h3>
 * <p>`O3` §3.4（计划）的原话：**"宁可漏剥离也不误杀"**。理由不是抽象的美德 ——
 * 漏剥离的页眉只是稀释语义，误杀的正文是**永久丢失**（建向量前剥掉的东西，
 * 事后只能靠重新解析捞回来）。所以默认值都取"更不容易判定为噪音"的那一侧：
 * {@code ratio = 0.6}（要出现在 60% 的页上）、{@code edgeLines = 3}（只看每页首尾 3 行）、
 * {@code minPages = 3}（少于 3 页的文档**干脆不判**）。
 *
 * <h3>⚠️ 本批没有真样本可以标定这些数</h3>
 * <p>干跑实测（见 `2026-09-16-S4-M05-C2.md` §8）：手上三份 PDF 样本
 * **没有一份含可被本规则判出的页眉页脚** —— `O3` §5 判据 1 点名的那份
 * `江西省…项目管理章程.pdf` 其实是 PPT 导出（`Author: zhuangx`），页脚只有一个孤零零的页码，
 * 且只出现在 6 张章节隔页上（20 页里占 4 页 = 20%，够不到 60%）。
 * 所以本参数组的**正向**证据只有合成用例；**反向**证据是真实的 ——
 * 三份真样本上一处都不剥离、{@code chars} 一字不减（`DocumentCleanerTest#realSamplesAreUntouched`）。
 * 标定这件事归 `O5`（解析质量基线）。
 *
 * @param ratio     判定阈值：样板必须出现在**至少这个比例**的页上（按文档总页数算）
 * @param edgeLines 只看每页**首/尾**各几行 —— 页眉页脚的定义就是"页边位置"
 * @param minPages  少于这么多页**不做任何判定**（页数太少时"跨页重复"没有统计意义）
 */
public record CleaningOptions(double ratio, int edgeLines, int minPages) {

    /** 紧凑构造器：参数越界就当场炸，不让它变成一个"悄悄什么都没剥"的清洗器。 */
    public CleaningOptions {
        if (ratio <= 0 || ratio > 1) {
            throw new IllegalArgumentException("清洗阈值 ratio 必须落在 (0, 1]，收到 " + ratio);
        }
        if (edgeLines < 1) {
            throw new IllegalArgumentException("每页采样行数 edgeLines 必须 ≥ 1，收到 " + edgeLines);
        }
        if (minPages < 2) {
            throw new IllegalArgumentException(
                    "最小页数 minPages 必须 ≥ 2（1 页的文档谈不上「跨页重复」），收到 " + minPages);
        }
    }

    /**
     * 默认参数 —— **保守侧**，取值理由见类 javadoc。
     *
     * <p>做成静态方法而不是常量：调用方（{@code DocParseService}）按知识库读配置，
     * 读不到时用这里兜底，返回的是**不可变记录**，共享一份没有别名风险。
     */
    public static CleaningOptions defaults() {
        return new CleaningOptions(0.6, 3, 3);
    }

    /** 样本不足时的判据：页数够不够做跨页判定。 */
    public boolean enoughPages(int pages) {
        return pages >= minPages;
    }

    /**
     * 页数门槛：样板要出现在**至少这么多页**上才算。
     *
     * <p>向上取整而不是四舍五入 —— 20 页 × 0.6 = 12.0 取 12；7 页 × 0.6 = 4.2 取 **5**
     * （四舍五入会取 4，比配置的意思更宽，那不该由取整方式悄悄决定）。
     */
    public int pageThreshold(int pages) {
        return (int) Math.ceil(pages * ratio);
    }
}
