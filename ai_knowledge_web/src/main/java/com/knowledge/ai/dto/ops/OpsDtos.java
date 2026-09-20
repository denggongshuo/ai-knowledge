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

    /**
     * 高频问题榜的一行（`T-A02` · 需求 `08` §1.5）。
     *
     * @param question     该组**最近一次**的**原始问法**。🔴 需求 §1.10 逐字要求
     *                     「用户问题**保持原始问法**，未被改写」⇒ 这里**不是**
     *                     `MissKeyNormalizer.normalize()` 的结果（那是聚合键，人是读不懂的）
     * @param count        该组的**提问次数**
     * @param avgTopScore  该组**有分数行**的平均 Top1；⚠️ 一条都没拿到过分时为 {@code null}
     *                     —— 前端显示 `—`。**不能用 0 顶替**：0 会被读成"召回质量极差"，
     *                     而事实是"这些提问一条都没召回上来"（`#169` 的同一条口径）
     *
     * <h4>🔴 「次数」是**归一化聚合**，不是语义聚合</h4>
     * <p>「同一问法的不同写法」必然合并（`怎么配？` ≡ `怎么配` ≡ `怎么配`），
     * 而「意思一样但问法不同」**不合并** —— 那是 `R-6` 的语义聚合，阈值无语料可校准，
     * 用户 2026-09-20 已裁定挂起（`G8-2` 的挂起半边）。页面必须写明这一句，
     * 否则「次数 12」会被读成"有 12 个人问过同一个问题"。
     */
    public record TopQuestion(String question, long count, Double avgTopScore) {
    }

    /**
     * 高频问题榜的响应。
     *
     * <p>⚠️ {@code total} 是**归一化合并之后的组数**，与清单页那个 `total` 不是同一种东西：
     * 那边数的是未命中**行**，这边数的是问法的**种类**。两者都不等于"提问次数合计" ——
     * 需要那个数时看 `list` 里各行各的 `count`。
     */
    public record TopQuestionsResponse(List<TopQuestion> list, int total) {
    }

    /**
     * 点踩归因里的一档。
     *
     * @param reason  原因文案 —— 取自 {@link com.knowledge.ai.core.enums.ChatFeedbackReason}
     *                的**落库值**（就是中文标签本身，见该枚举「为什么不存代号」）
     * @param count   该原因的次数
     * @param percent 占比（%，保留一位小数）= `count / negativeTotal`
     */
    public record FeedbackReason(String reason, long count, double percent) {
    }

    /**
     * 点踩原因归因（`T-A02` · 需求 `08` §1.6）。
     *
     * <h4>🔴 为什么有 {@code unreasoned} 这个字段 —— 需求 §1.10 那条等式不成立</h4>
     * <p>需求 §1.10 的验收要点写着「**点踩原因次数合计 = 统计的负反馈总数**」。
     * 而需求 §6.8 自己写的是「点踩时**可选**原因」⇒ `ChatFeedbackService#submit`
     * 允许 `unlike` **不带原因**（`reason.isEmpty()` 直接通过，落库为 `NULL`）。
     * ⇒ **只要库里有一行"点踩但没填原因"，那条等式就永远不成立。**
     * <p>这与 `S8-M10-A` 逮到的另一条（「清单总条数 = 未命中提问数」）是**同一个形状**：
     * 需求把一个"差一个量"的等式写成了勾选框。处置照同一判例 ——
     * **两个数都如实给**，<b>不挑一个"好看"的</b>：
     * <ul>
     *   <li>{@code negativeTotal} = 负反馈**总数**（含没填原因的）；</li>
     *   <li>{@code reasons} = 按原因分档（**只含填了原因的**）；</li>
     *   <li>{@code unreasoned} = 其中**没填原因**的条数；</li>
     *   <li>⇒ `sum(reasons[].count) + unreasoned == negativeTotal` **真的成立**，
     *       而且这个等式是**跨两条独立查询**验的（不是 `sum` 推出来的 —— 见
     *       `QaOpsRepository#feedbackCounts` 的注释：推出来的等式会变成空断言）。</li>
     * </ul>
     *
     * @param negativeTotal 负反馈**总数**（窗口内 `feedback = 'unlike'` 的行数）
     * @param negativeRate  负反馈率（%，保留一位小数）= `negativeTotal / 窗口内提问总数`。
     *                      ⚠️ 分母是**提问总数**（`sys_retrieval_trace` 的行数），
     *                      与 `QaOverview#totalQuestions` **同一个口径** —— 需求 §1.6 原话
     *                      「负反馈率 = 负反馈数 ÷ **提问总数**」（它的示例是 `28 ÷ 1284`）
     * @param threshold     需求 §1.6 点名要对比的阈值（%）。**写死的常量**：
     *                      需求那一行给的就是 5，它不是可配置项（`sys_setting` 里没有它）
     * @param unreasoned    点踩但没填原因的条数（见上）
     * @param reasons       按原因分档。⚠️ **闭集那 5 条逐条都在**（缺的补 0）——
     *                      否则"这个原因的次数是 0"与"这个原因不存在"长得一模一样
     */
    public record FeedbackStats(long negativeTotal,
                                double negativeRate,
                                double threshold,
                                long unreasoned,
                                List<FeedbackReason> reasons) {
    }

    /**
     * `POST /ops/qa/export` 的入参。
     *
     * <h4>🔴 为什么筛选条件要**从请求里拿**，而不是"导出全部"</h4>
     * <p>需求 §1.7 写的是「导出报表 | 字段与表格列保持一致」—— 用户看到的是**筛过的**
     * 那张表（状态 / 知识库 / 关键词三个筛选器）。导出全部的话，用户拿到的与他看到的
     * 不是同一批数据，而报表的用途恰恰是"周会汇报我看到的那张表"。
     * <p>这与审计导出的取舍逐字相同（`AuditQueryService#exportCsv` 的注释：
     * 「**筛选条件原样带走**：这既是用户预期，也是数据外带面的控制」）——
     * 本仓在这条上是同一条纪律，不是本批新发明的。
     *
     * <p>⚠️ `kbId` 收 **String**，与 `GET /ops/qa/misses` 同因：前端「全部」发的是字面量
     * `all`。声明成 `Long` 会在参数绑定阶段就抛错，而现象是"页面空着"。
     *
     * @param status  状态筛选（`PENDING`/`IN_LIST`/`RESOLVED`/`IGNORED`）；空或 `all` = 不筛
     * @param kbId    知识库筛选；空或 `all` = 不筛
     * @param keyword 问题原文关键词；空 = 不筛
     */
    public record ExportRequest(String status, String kbId, String keyword) {
    }

    /**
     * 导出任务受理回话。
     *
     * <p>三个字段与前端 `api/index.ts` 的 `qaExport` 泛型、以及 `mock/index.ts` 的同名分支
     * **逐字一致**（本批是"把后端补上"，不是重新设计契约）。
     *
     * @param status 调度层的**真值**（`AsyncTaskStatus` 的枚举名，即 `PENDING`），
     *               ⚠️ **不是** mock 那个字面量 `QUEUED` —— 理由见 `AuditExportService#submit`
     *               的注释：同一个 `taskId` 在两次请求里不能有两种状态词
     * @param note   给用户看的一句话。⚠️ 前端**应当显示它**而不是自己写死一句 ——
     *               否则"后端说不支持"与"后端说已提交"在前端长得一样
     */
    public record ExportAccepted(String taskId, String status, String note) {
    }
}
