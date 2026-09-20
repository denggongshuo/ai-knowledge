package com.knowledge.ai.dto.ops;

import java.util.List;

/**
 * 页 20「检索质量分析」（需求 `08` `FR-OPS-02`）的五个响应体。
 *
 * <h3>为什么每一处都带"口径补充"字段，而不是只回需求点名的那几个数</h3>
 * <p>这一页的三处口径**需求文档没写**，而每一处都决定页面上的数**怎么读**：
 * <ol>
 *   <li><b>可比集</b>：`top1_score` 的量纲随 `rerank_applied` 变（重排过是 0~1 的重排分，
 *       未重排是 RRF 融合分 ≈0.016，见 {@code RetrievalTraceWriter#top1} 的警告）——
 *       把两种分混进同一个均值/同一张分布图，**数不会报错，只是悄悄失真**。
 *       所以「未重排」与「空召回」的行数**必须单独回**，页面才说得清自己在哪批行上算。</li>
 *   <li><b>无法定档的那一份</b>：需求 §2.8 第 2 条要「五档合计 = 提问总数」，
 *       而空召回的行（`G5-13`：**仍有一行**，且 `top1_score` 为 `NULL` 不是 0）
 *       落不进任何一档 ⇒ 那个等式**在有空召回时不可能成立**。
 *       补一档「无法定档」+ 把它的两个成因分开回，等式才重新成立。</li>
 *   <li><b>覆盖数</b>：「重排前 Top1」这一列只在**重排前那条进了重排结果**时可算
 *       （重排客户端只返回 `top_n` 条）⇒ 重排前后两个命中率是在**一个子集**上比的，
 *       覆盖了多少必须一起给，否则那对数字看起来是全量的。</li>
 * </ol>
 *
 * <p>这与上一批 `OpsDtos.FeedbackStats` 加 {@code unreasoned} 是**同一种处置**：
 * 需求把"差一个量"的等式写成了勾选框，判例是**几个数都如实给**，不挑一个好看的。
 */
public final class OpsRetrievalDtos {

    private OpsRetrievalDtos() {
    }

    /**
     * `GET /ops/retrieval/overview` —— 四个统计指标（需求 §2.1）。
     *
     * @param totalQuestions      窗口内的提问行数（= `sys_retrieval_trace` 的行数）
     * @param comparableQuestions **可比集**：`rerank_applied=1` 且 `top1_score` 非空的行。
     *                            下面三个比率/均值都在这一批上算
     * @param noRerankQuestions   未重排的行 —— 它们的"Top1"是**融合分**，与判定线不同量纲，
     *                            恒**不参与**任何比率（只会把均值拉到一个没有意义的数上）
     * @param emptyRecallQuestions 重排了但空召回的行 —— 没有 Top1，不是"Top1 是 0 分"
     * @param avgTopScore         平均 Top1 相似度（**只算可比集**）
     * @param afterTop1HitRate    Rerank 后 Top1 命中率（可比集里 `top1_score >= miss_threshold` 的占比，0~100）。
     *                            ⚠️ 这里「命中」的语义是「**库里有了相关内容**」——承 `G8-5`：
     *                            rerank 判的是**主题相关**，不是"问题被回答了"
     * @param rerankGain          Rerank 净提升（百分点）。⚠️ 取 `rerank-effect` 的**同子集**口径
     *                            （重排前后在同一批上有前值的行上比），不是本对象两个比率的差 ——
     *                            那两个比率的分母不同，相减会把"覆盖率"混进"提升"里
     * @param avgCitedChunks      平均分片数。⚠️ 是**喂给模型的那批**（`chunk_count`），
     *                            不是"被答案引用的那批"—— `cited` 列恒 NULL（要解析答案角标，属 `T-804`）。
     *                            ⚠️ 分母是**全部提问**（含空召回，它们贡献 0）——
     *                            空召回的行**没有 Top1 但有 chunk_count**，那是真值
     * @param missThreshold       未命中判定线（《10》§11 的全站默认值）。
     *                            ⚠️ **发当前值而不是行内值**：本对象是"现在这一刻的口径快照"，
     *                            而分档用的是行内值（见 {@link ScoreDistribution}）
     */
    public record Overview(long totalQuestions,
                           long comparableQuestions,
                           long noRerankQuestions,
                           long emptyRecallQuestions,
                           Double avgTopScore,
                           Double afterTop1HitRate,
                           Double rerankGain,
                           Double avgCitedChunks,
                           double missThreshold) {
    }

    /**
     * 链路的一段耗时（需求 §2.2）。
     *
     * @param name        段名 —— **与 `ChatWireMapper.stages()` 的 9 段同源同名同序**，
     *                    不另起一套（两处各写一份的话，检索详情面板与这张图迟早对不上，
     *                    而"对不上"表现为两个页面各说各话、各自都自洽）
     * @param costMs      平均耗时（ms，四舍五入到整数）
     * @param metric      需求 §2.2「关注指标」列的名字；`null` = 该阶段无可用打点
     * @param metricValue 该指标的实测值；`null` = **算不出来**（页面显示 `—`，**不许填 0**）
     * @param action      需求 §2.2「异常时的动作」列的处置建议。⚠️ 它是**固定 playbook**
     *                    （不随数据变），页面当前不渲染它，保留是为了接口契约与需求逐项对齐
     */
    public record Stage(String name, long costMs, String metric, String metricValue, String action) {
    }

    /**
     * `GET /ops/retrieval/pipeline` —— 各阶段耗时与输出量。
     *
     * <h4>🔴 为什么多两个数（`generationMs` / `otherMs`）而不是只给需求那两个</h4>
     * <p>需求 §2.8 第 1 条要「各阶段耗时合计 = 声明的检索侧总耗时」，而需求 §2.2 列的
     * **5 项之和里不含「可见范围」与「表格回表」** —— 那两段是**真花的**时间
     * （`scope_millis` / `table_millis`）。只报需求那 5 项的话，`检索侧 + 生成` 对不上
     * `全链路`，而差额**没有任何一栏显示它**。
     * <p>⇒ 页面按 **`检索侧 + 生成 + 其他 = 全链路`** 三段写。恒等式 **`A+B+C == D`**
     * 是一条**真判据**：`otherMs` 是**独立算出来的残差**
     * （`total − 检索侧 − 生成`），不是拿总数减出来的余额 ⇒ 它不为 0 就说明有未归段的时间。
     *
     * @param list               9 段（见 {@link Stage#name()} 的同源说明）
     * @param pipelineRows       这张表算在**跑完全链路**的行上（`llm_generation_millis` 非空）
     * @param skippedNoGeneration 被排除的行数（空召回**不调模型** ⇒ "生成耗时"对它们**不存在**，
     *                           填 0 就是把"没生成"说成"耗时 0ms"）。这个数**必须回**，
     *                           否则读者会以为表是全量的
     * @param retrievalSideMs    检索侧 = **需求口径的那 5 项**之和（查询解析 + 向量召回(含嵌入)
     *                           + 关键词召回 + RRF 融合 + Rerank）
     * @param generationMs       生成段
     * @param otherMs            **其他** = 可见范围 + 表格回表 + 未归段（真残差）
     * @param totalMs            全链路（端到端）
     */
    public record Pipeline(List<Stage> list,
                           long pipelineRows,
                           long skippedNoGeneration,
                           long retrievalSideMs,
                           long generationMs,
                           long otherMs,
                           long totalMs) {
    }

    /**
     * 相似度分布的一档（需求 §2.3）。
     *
     * @param percent 占比的**分母是提问总数**（含"无法定档"那档）⇒ 各档合计恰为 100%
     */
    public record Bucket(String label, String range, long count, double percent, boolean danger) {
    }

    /**
     * `GET /ops/retrieval/score-distribution` —— 相似度分档分布。
     *
     * <h4>🔴 六档不是五档 —— 需求 §2.8 第 2 条在有空召回时不可能成立</h4>
     * <p>五档是需求 §2.3 定的（0.80 / 0.60 / 0.40 / 0.35），但它们**都要求有一个可比的分数**。
     * 而两类行拿不出这样的数：
     * <ul>
     *   <li>{@code rerank_applied=0} ⇒ 分数是**融合分**，与档位边界（也是判定线）不同量纲；</li>
     *   <li>空召回 ⇒ `top1_score` 为 `NULL`（`G5-13` 明确：**不是 0**）。</li>
     * </ul>
     * ⇒ 补第 6 档「**无法定档**」，并把它两个成因的数（{@code noRerank} / {@code emptyRecall}）
     * 分开回 —— 合成一个数会让"重排没开"（配置问题）与"召回为空"（覆盖问题）
     * 看起来是同一件事，而它们的下一步动作完全不同。
     *
     * <p>判据：**五档 + 无法定档 == {@code total}**（可证伪）。
     *
     * @param total      窗口内提问总行数（= 各档合计）
     * @param unscored   无法定档的行数（= `noRerank + emptyRecall`）
     */
    public record ScoreDistribution(List<Bucket> list,
                                    long total,
                                    long unscored,
                                    long noRerank,
                                    long emptyRecall) {
    }

    /**
     * `GET /ops/retrieval/rerank-effect` —— Rerank 效果验证（需求 §2.4）。
     *
     * <h4>🔴 这两个比率的分母是**同一个子集**，这是它能不能当"提升"用的关键</h4>
     * <p>取「**重排前那条也拿到了重排分**」的行（{@code covered}）：只有在**同一批行**上比，
     * 差值才是"重排带来的提升"。若两侧分母不同（比如"所有重排行"对"有前值的行"），
     * 差值里会混进**覆盖率**的成分，而页面上看不出来。
     * <p>⚠️ 因此它与 {@link Overview#afterTop1HitRate()} **不是同一个数**（那个在全部可比集上算），
     * 页面要说清各自回答什么问题。
     *
     * @param beforeTop1HitRate 重排前 Top1 命中率（%）；{@code covered = 0} 时为 `null`
     * @param afterTop1HitRate  重排后 Top1 命中率（%），**同一子集**
     * @param gainPercent       净提升（百分点）= after − before；算不出时为 `null`
     * @param avgLatencyMs      重排平均耗时（= 每行的代价）
     * @param covered           有"重排前"分数的行数（分子分母的那个子集）
     * @param comparable        **可比集**行数（重排过且有 Top1）—— 看 {@code covered / comparable}
     *                          才知道这对数字覆盖了多少，**页面必须显示它**
     */
    public record RerankEffect(Double beforeTop1HitRate,
                               Double afterTop1HitRate,
                               Double gainPercent,
                               Double avgLatencyMs,
                               long covered,
                               long comparable) {
    }

    /**
     * 各库质量排行的一行（需求 §2.5）。
     *
     * @param kbId     知识库 id；**「其他」桶为 `null`**
     * @param kbName   库名（已软删的库回 `已删除库#id`，不静默丢 —— 丢了会让"某一个库"
     *                 与"其他"的账对不上）
     * @param avgTopScore 平均 Top1（可比集上算，理由同 {@link Overview#avgTopScore()})
     * @param missRate    未命中率（%，同一分母）
     * @param trend       走势：`UP`/`DOWN`/`STABLE`/`UNKNOWN`。
     *                    ⚠️ **`UNKNOWN` 是"样本不足"**，不是"稳定" —— 任一半的样本少于
     *                    {@code TREND_MIN_SAMPLES} 就回它。把"没数据"说成"稳定"
     *                    正好会让需求 §2.5 最想抓的那句（"稳定偏低 vs 持续下降"）失效
     * @param issue      「主要问题」。⚠️ **本批恒 `null`** —— 需求那张示例表里的
     *                    "表格密集 · 1 张表格未识别"来自**文档质量列 + 解析问题表**，
     *                    与检索打点是两张表的事 ⇒ **不猜**，页面显示 `—`
     */
    public record KbQualityRow(Long kbId, String kbName, long questions, Double avgTopScore,
                               Double missRate, String trend, String issue) {
    }

    /**
     * `GET /ops/retrieval/kb-quality` —— 各知识库检索质量排行。
     *
     * <h4>🔴 归属口径：只把**单库提问**归给库，跨库提问归「其他」</h4>
     * <p>`sys_retrieval_trace` **没有 `kb_id` 列**，只有逗号拼接的 `kb_ids`
     * （`V22` 的 DDL 注释明写「只展示与粗筛，**不做关联**」）。按 `FIND_IN_SET` 把它拆给每个库，
     * 一次提问命中三个库就会被算**三次** ⇒ 「各库合计」会**超过**提问总数，
     * 而需求 §2.8 第 4 条要求的是 **≤**（并且点名"未列出的归入其他"）。
     * <p>⇒ 只统计 `kb_count = 1` 的行（归属**确定**），其余整体归一个「其他」桶。
     * 这样 `各库 + 其他 == 提问总数`（比需求的 `≤` 更强，可证伪）。
     *
     * @param questions        单库行的合计（各库行之和）
     * @param othersQuestions  「其他」桶的行数（`kb_count <> 1`）
     */
    public record KbQuality(List<KbQualityRow> list, long questions, long othersQuestions) {
    }
}
