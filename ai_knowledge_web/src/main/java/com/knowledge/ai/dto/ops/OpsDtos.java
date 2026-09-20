package com.knowledge.ai.dto.ops;

import java.util.List;

/**
 * 运营分析（`M10`）—— 本批只交 11 问答运营页里 `T-A00` 那一块的三条端点。
 *
 * <h3>字段名与前端逐字对齐</h3>
 * <p>前端类型在 `ai_knowledge_app/src/types/index.ts`（`MissQuestion` / `QaOverview`），
 * 页面在 `views/ops/OpsQaView.vue`。这里**不发明字段名** —— 改了名前端会静默显示空，
 * 而接口层看不出任何异常（与 `#159` 那条「透传链路要有一条真跑一次的判据」同源）。
 *
 * <h3>⚠️ 可空的四个字段是**真可空**，不是没写全</h3>
 * <p>{@code topScore} / {@code avgTopScore} / {@code asker} / {@code ignoredBy} 都会是
 * {@code null}：前两个是"还没有数据"（没有提问过 ⇒ 没有平均值），后两个是"那个用户
 * 已经不在了"。四个都**不能用 0 顶替** —— `avgTopScore = 0` 会被读成"召回质量极差"，
 * 而事实是"还没有人问过"。这是本仓已经定过的口径（`#169`）。
 */
public final class OpsDtos {

    private OpsDtos() {
    }

    /**
     * 未命中清单的一行。
     *
     * @param question    **原样**的用户问法（需求 §1.2：不做归一化改写）
     * @param askCount    同一语义问题的聚合次数（本批 = 同一归一化键的次数）
     * @param lastAskAt   最近提问时刻，`yyyy-MM-dd HH:mm`（与通知列表同一格式）
     * @param asker       **最近**提问人的显示名；已删除 / 未登录时为 {@code null}
     * @param topScore    提问过的最高相似度（"补录前"那一半）；无分时为 {@code null}
     * @param scoreAfter  补录重放后的相似度；还没重放时为 {@code null}
     * @param ignoredBy   忽略人显示名；仅 IGNORED 状态有值
     * @param kbName      涉及的知识库名（跨库时用「、」连接 —— 见 `kb_ids` 是逗号串）
     */
    public record MissItem(long id,
                           String question,
                           int askCount,
                           String lastAskAt,
                           String asker,
                           Double topScore,
                           Double scoreAfter,
                           String status,
                           String ignoredBy,
                           String kbName) {
    }

    /**
     * 清单响应。
     *
     * <p>🔴 **两个"条数"都给，因为它们不是一回事**（需求 §1.10 把它们写成了一个数）：
     * <ul>
     *   <li>{@code total} = 聚合后的**行数** —— 分页必须用它，否则页数会多于实际行数；</li>
     *   <li>{@code askedTotal} = 这些行的**提问次数合计** —— 需求 §1.10
     *       「未命中清单总条数 = 统计卡的未命中提问数」在有重复提问时说的其实是它。</li>
     * </ul>
     * <p>两者只有在「每个问法都恰好被问过一次」时才相等，而需求 §1.2 同时**要求**把
     * 重复提问聚合起来 ⇒ 不可兼得。所以两个都如实给出，判据自己选口径。
     */
    public record MissListResponse(List<MissItem> list, long total, long askedTotal) {
    }

    /** `PATCH /ops/qa/misses/{id}` 的入参。 */
    public record PatchMissRequest(String status) {
    }

    /**
     * 重放验证的回话（`T-A01` · 需求 `08` §1.8 与 §1.4 步 4）。
     *
     * <h3>字段名的来源</h3>
     * <p>前五个字段**逐字对齐前端 `types/index.ts` 的 `ReplayResult`**（那个类型在本批之前
     * 就写好了，`OpsQaView.replay()` 与 `mock/index.ts:1715` 都按它实现）——
     * 本批是"把后端补上"，不是重新设计契约。{@code status} 是本批**新增**的那一个，
     * 理由见下。
     *
     * <h3>🔴 为什么多一个 {@code status}（而不是让前端自己推断）</h3>
     * <p>前端原来写的是 `item.status = res.improved ? 'RESOLVED' : 'PENDING'`，
     * 而需求 §1.4 步 5 的判据是「**分数达标**自动关闭，未达标退回待处理」——
     * 两回事（涨了却没到判定线，那个问题实际仍答不上来）。
     * 本批把判定放在后端之后，前端**不再有推断的余地**：状态是权威结论，
     * 而且「前置未就绪」（补录文档还在解析中 / 没有可比的补录前分）这一档
     * **不改状态**，前端**推断不出来**。
     *
     * @param before             补录前的最高相似度（取自池子的 {@code max_top_score}，**记录值**）。
     *                           ⚠️ 可为 {@code null}（旧行没有这个数），此时**判定不成立**、
     *                           {@code status} 原样返回
     * @param after              本次重放的 Top1；{@code null} = **一条都没召回**
     *                           （"没有 Top1"与"Top1 是 0 分"是两件事，见 `RetrievalTraceWriter#top1`）
     * @param improved           {@code after > before}（**分数是否提升**）
     * @param hitSupplementedDoc {@code Top1} 是不是**刚补录那篇**（需求 §1.4 步 4 的第二条校验）。
     *                           ⚠️ 不是"召回列表里有没有它" —— 补录那篇挂在第二名时，
     *                           达标的分其实是别人给的，那正是这一条要防的
     * @param verdict            **判定结论**：{@code RESOLVED}（达标且命中，已自动关闭）/
     *                           {@code NOT_RESOLVED}（判过了、没通过，已退回待处理）/
     *                           {@code BLOCKED}（**判不了** —— 补录文档没就绪 / 没有可比的前分，
     *                           状态**未改**）
     * @param status             重放后该行的**实际**状态（权威值）
     * @param note               一句话说明这次判定的依据，或"为什么判不了"。**必须具体**：
     *                           它要能回答"我该去做什么"（`G4-3` 的同一条纪律）
     *
     * <h4>🔴 为什么 {@code verdict} 与 {@code status} 不能合并成一个</h4>
     * <p>`status` 单独**区分不了**「判过了、没通过（退回待处理）」与「压根判不了（状态不动）」
     * —— 两者都是 {@code PENDING}。而页面对这两件事的展示完全不同：前者是"这次补录没管用"，
     * 后者是"你还没补录 / 索引还没建好"。与"闸的退出码分档"是同一条纪律：
     * **判不了要和判否分开报**，否则前者会被当成后者处理，而两者的下一步动作相反。
     */
    public record ReplayResult(Double before,
                               Double after,
                               boolean improved,
                               boolean hitSupplementedDoc,
                               String verdict,
                               String status,
                               String note) {
    }

    /**
     * 改状态的回话。
     *
     * <p>{@code pending} 必带：前端 `OpsQaView.vue:91` 改完状态**直接拿它刷新统计卡**
     * （不再发一次 `overview`）⇒ 它必须与 `/notices/pending` 是**同一个数**
     * （`G6-13` 的同源要求就落在这里）。
     */
    public record PatchMissResponse(long id, String status, int pending) {
    }

    /**
     * 问答运营四指标（需求 §1.1）。
     *
     * @param totalQuestions 周期内问答请求数
     * @param missed         未命中**提问数**（按次算，`miss_flag=1` 的行数）
     * @param missedRate     未命中率（%，保留一位小数）= `missed / totalQuestions`
     * @param avgTopScore    平均 Top1 相似度；⚠️ **无提问时为 {@code null}**，不是 0
     * @param pending        待处理问题数（清单里 `PENDING` + `IN_LIST`）—— 驱动侧边栏角标
     * @param missThreshold  **当前**的全局未命中判定线。页面的卡片标题要显示它
     *                      （「未命中提问（Top1 < 0.35）」）—— 那个数**不能写死在前端**：
     *                      它是 `sys_setting` 里的可配置项，写死之后管理员改成 0.4，
     *                      页面会一直显示 0.35（`#85`「陷阱：没人用所以漂移看不见」同族）。
     *                      ⚠️ 口径边界：`miss_flag` 是**写入时**按当时的判定线算的
     *                      （`V22` 每行都存了 `miss_threshold`）⇒ 改过线之后，
     *                      列表里那些旧行反映的是**旧的线**。要精确只能按行取那个列。
     */
    public record QaOverview(long totalQuestions,
                             long missed,
                             double missedRate,
                             Double avgTopScore,
                             int pending,
                             double missThreshold) {
    }
}
