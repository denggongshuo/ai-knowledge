package com.knowledge.ai.dto.system;

import com.knowledge.ai.core.enums.BillingUnit;
import com.knowledge.ai.core.enums.ModelType;

import java.math.BigDecimal;
import java.util.List;

/**
 * M09 模型配置的**线格式**（`T-900`~`T-903`）—— 与 `09-需求` FR-SYS-01 §1.14 及前端契约逐字段一致。
 *
 * <h3>字段形状以**前端 `types/index.ts`** 为准，不以"后端方便"为准</h3>
 * <p>与 {@code ChatDtos} 的会话族同一条规矩（`mock/index.ts:71` 立的：**切真实接口前端零改动**）。
 * 前端 {@code ModelConfigItem} 已经把 27 个字段的形状定死了，而且 {@code ModelConfigView.vue}
 * 是**逐字段原样渲染**的。三处特别容易踩：
 * <ul>
 *   <li>{@link ModelConfigItem#lastTestAt} 是**已格式化展示串**（{@code 2026-09-12 15:20}），
 *       <b>不是时间戳</b> —— 前端 {@code {{ row.lastTestAt }}} 原样渲染，发时间戳过去那一行就是空的
 *       （与 {@code ChatSessionItem#time} 同型）；</li>
 *   <li>{@link ModelConfigItem#apiKeyMasked} 是**掩码串**而不是有无布尔 —— 前端直接显示它；</li>
 *   <li>计费三态：{@code inputPrice}/{@code outputPrice}/{@code perCallPrice} 为 {@code null}
 *       = **未配置**（费用未知），而 {@code 0} 或 {@code billingUnit = NONE} = **已知不计费**。
 *       两者**语义不同**，前端 UI 要区分（`G6-6`）。</li>
 * </ul>
 */
public final class ModelConfigDtos {

    private ModelConfigDtos() {
    }

    /**
     * 能力标记（前端 {@code ModelCapabilities}）。
     *
     * <p>问答页切模型时用它做**上下文校验**与**能力差异提示**（《09》§1.7）。
     * ⚠️ 本批（`S6-A`）只把它**存下来并回显**，消费方（问答页切换器 `T-904`、
     * 「直接读原图」`T-806`）在后续批次。
     */
    public record Capabilities(boolean stream, boolean vision, int contextWindow, boolean supportSystemRole) {
    }

    /**
     * 生成参数（前端 {@code ModelParams}）。
     *
     * <p>⚠️ {@code maxTokens} 可空：{@code null} = **不下发这个字段**（让网关用自己的默认值）。
     * 这在本批之前是既有行为；给它编一个默认值会**悄悄改变所有模型的生成长度上限**。
     */
    public record Params(double temperature, Integer maxTokens, int timeoutSec, int retries) {
    }

    /**
     * 一条模型配置（前端 {@code ModelConfigItem}）。
     *
     * @param id            自增主键。⚠️ 前端类型是 {@code number}，与 `ChatDtos` 里那些
     *                      "字符串形式的 id"**不是一回事** —— 那边是因为前端类型声明成了 string
     * @param modelType     四类之一，**创建后不可修改**
     * @param protocol      接口协议，如「OpenAI 兼容」
     * @param apiKeyMasked  **脱敏后的 Key**，如 {@code sk-••••••••3f9a}。
     *                      ⚠️ 本批密钥不落库（见 `S6-M09-A` §3.3），掩码由**读取时**从 `.env` 现算 ——
     *                      不落一列快照：换了 key 而掩码没变就是撒谎
     * @param dimension     向量维度（仅 EMBEDDING）
     * @param metric        相似度度量（仅 EMBEDDING）
     * @param isActive      是否当前启用。同类型至多一条为真（库层唯一索引保证）
     * @param lastTestOk    最近一次连接测试是否通过 —— **启用门槛读它**（`G6-3`）
     * @param activatedAt   最近一次启用时间（展示串）；⚠️ 不复用 {@code updatedAt}
     */
    public record ModelConfigItem(long id,
                                  ModelType modelType,
                                  String displayName,
                                  String protocol,
                                  String baseUrl,
                                  String apiKeyMasked,
                                  String modelName,
                                  Integer dimension,
                                  String norm,
                                  Integer batchSize,
                                  String metric,
                                  Params params,
                                  Capabilities capabilities,
                                  BillingUnit billingUnit,
                                  BigDecimal inputPrice,
                                  BigDecimal outputPrice,
                                  BigDecimal perCallPrice,
                                  String currency,
                                  boolean isActive,
                                  int sortOrder,
                                  String lastTestAt,
                                  boolean lastTestOk,
                                  Integer lastLatencyMs,
                                  String note) {
    }

    /**
     * 配置页的统计卡（前端 {@code ModelStats}）。
     *
     * @param monthTokensWan ⚠️ **本月 token（万）当前只统计对话链路** —— 数据来自
     *                       {@code sys_chat_message} 的 {@code token_prompt + token_completion}，
     *                       而嵌入 / 重排 / VL 的用量**没有落点**（那要等 `T-905` 的用量统计）。
     *                       本批给的是**真数但是部分口径**，不是全量；边界写在 `S6-M09-A` §9。
     *                       （刻意不给 {@code 0}：那会被读成"本月确实没用量"，是另一种撒谎。）
     * @param defaultChatOk  当前默认对话模型是否**连接测试通过**；未配置时为 {@code false}
     */
    public record Stats(int chatCount,
                        int rerankCount,
                        int vlCount,
                        String defaultChatName,
                        boolean defaultChatOk,
                        double monthTokensWan) {
    }

    /**
     * 四类模型配置（`GET /system/model/config`）。
     *
     * <p>⚠️ {@code embedding} 是**单条对象**而不是数组 —— 嵌入是全局单例（`G6-2`），
     * 前端类型也是 {@code embedding: ModelConfigItem}。没配置时为 {@code null}
     * （前端按"未配置"渲染）。
     */
    public record ConfigResponse(Stats stats,
                                 List<ModelConfigItem> chat,
                                 ModelConfigItem embedding,
                                 List<ModelConfigItem> rerank,
                                 List<ModelConfigItem> vl) {
    }

    /**
     * 新增 / 编辑的请求体。
     *
     * <p>{@code modelType} 在**新增**时必填且在**编辑**时不可改（《09》§1.5）。
     * 其余字段都可空 —— 编辑时前端只传改动的那些，服务层按"未传即不动"处理。
     *
     * <p>⚠️ **请求体里没有 {@code apiKey}**：本批密钥不落库（`S6-M09-A` §3.3）。
     * 前端表单上的 Key 输入框是只读掩码，文案指向运维改 `.env`。
     * 将来真要做加密落库时，这个字段才加回来。
     */
    public record SaveRequest(ModelType modelType,
                              String displayName,
                              String protocol,
                              String baseUrl,
                              String modelName,
                              Integer dimension,
                              String norm,
                              Integer batchSize,
                              String metric,
                              Params params,
                              Capabilities capabilities,
                              BillingUnit billingUnit,
                              BigDecimal inputPrice,
                              BigDecimal outputPrice,
                              BigDecimal perCallPrice,
                              String currency,
                              Integer sortOrder,
                              String note) {
    }

    /** 新增结果（前端 {@code createModel} 的返回类型）。 */
    public record SavedResult(long id, boolean saved) {
    }

    /**
     * 编辑结果（前端 {@code updateModel} 的返回类型）。
     *
     * @param needRetest 改了 Base URL / 模型名 → **原测试结论失效**，必须重测才能再次启用
     *                   （《09》§1.6）。前端据此把该行的「启用」按钮置灰
     */
    public record UpdatedResult(long id, boolean saved, boolean needRetest) {
    }

    /** 删除结果。 */
    public record DeletedResult(long id, boolean deleted) {
    }

    // ==================================================================
    // T-904 —— 问答页模型选项
    // ==================================================================

    /**
     * 问答页切换器的一项（`GET /system/model/options`，前端 {@code ModelOption}）。
     *
     * <h3>为什么它比 {@link ModelConfigItem} 少那么多字段</h3>
     * <p>因为它是**给普通用户看的**，而 {@code ModelConfigItem} 是给管理员看的。
     * 需求 §1.14 对此的约束是「**不含密钥**」（本批连密钥都没落库，见 `#173`），
     * 而更实际的一条是：问答页不需要知道 base URL、计费单价、批大小这些东西。
     * 少发字段本身就是一种最小暴露面 —— 这与 `G6-5` 的"密钥不外泄"是同一条纪律的延伸。
     *
     * <p>⚠️ {@link #id} 是 {@code long}（前端类型 {@code number}），
     * 与 {@code ChatDtos} 里那些"字符串形式的 id"**不是一回事** ——
     * 那边是因为前端把会话/消息 id 声明成了 {@code string}。
     * DB 生效时主键就是数字，转得出来。
     *
     * @param isActive 是否为该类型**当前启用**的那一条（前端据此打勾）
     * @param capabilities 能力标记。⚠️ 本批只回显，**不做上下文校验** ——
     *                     那个消费方（选模型时按 contextWindow 拦过长会话）属 `T-806`，
     *                     而且种子里的 `contextWindow=128000` 是**保守初值不是实测值**（`#170`）
     */
    public record ModelOption(long id,
                              String displayName,
                              String modelName,
                              boolean isActive,
                              Capabilities capabilities) {
    }

    /** 模型选项列表（形状是 {@code {list}}，与 mock 逐字一致）。 */
    public record OptionList(List<ModelOption> list) {
    }

    // ==================================================================
    // T-905 —— 用量统计
    // ==================================================================

    /**
     * 一个用途桶的用量（前端 {@code ModelUsage.byPurpose[]}）。
     *
     * @param name    中文桶名，取值见 {@code ModelUsageRecorder.Purpose#getLabel()}。
     *                ⚠️ 是**已经算好的展示名**而不是枚举名 —— 前端直接渲染，不做映射
     *                （同 {@code ChatDtos.TraceStage#name} 的口径）
     * @param percent 占比（整数百分比）。⚠️ 各桶之和**可能不是 100**（四舍五入），
     *                前端不要拿它做"加起来必须等于 100"的校验
     */
    public record PurposeUsage(String name, long tokens, int percent) {
    }

    /**
     * 一个模型的用量（前端 {@code ModelUsage.byModel[]}）。
     *
     * @param cost **三态**（`G6-6` 的诚实口径，与 {@link ModelConfigItem} 的计价三态同源）：
     *             <ul>
     *               <li>{@code number} = 已配单价，可估算；</li>
     *               <li>{@code 0} = {@code billingUnit = NONE}（本地部署 / 包月），
     *                   **已知不计费**；</li>
     *               <li>{@code null} = **未配单价，无法估算** —— 前端要显示成
     *                   「未配置单价」而不是「¥0」。</li>
     *             </ul>
     *             ⚠️ 后两者都是"0 元"，但语义完全相反 —— 这正是 `T-900` 阶段
     *             反复强调的那条区分（`mock/data.ts:1147` 的注释逐字写着它）。
     */
    public record ModelUsageItem(String modelName,
                                 ModelType modelType,
                                 long calls,
                                 long tokens,
                                 BigDecimal cost,
                                 BillingUnit billingUnit) {
    }

    /**
     * 用量统计（`GET /system/model/usage`，前端 {@code ModelUsage}）。
     *
     * <h3>措辞：是「费用估算」，不是账单</h3>
     * <p>`09` §1.13.1 专门为此写了一节「费用估算的诚实口径」，`BillingUnit` 的类注释
     * 也复述了一遍。单价是运维手填的，可能与供应商实际账单不一致 ——
     * 页面上永远不能出现"实际费用"这类说法。
     *
     * @param cost          费用合计（{@code currency} 计价）。**只累加已计价的项**；
     *                      全部模型都未配单价时为 {@code null}（"无法估算"），
     *                      **不是 0** —— 0 会被读成"确认不花钱"
     * @param unpricedCount 未配置单价的模型数（**不含 {@code NONE}**：本地部署不是"忘了配"）。
     *                      它存在的意义就是给那个 {@code null} 一个交代：
     *                      "这个合计里少了几个模型"
     * @param avgLatencySec 平均单次耗时（秒）。⚠️ 没有耗时记录时为 {@code 0} ——
     *                      这里用 0 是**如实**：本字段只有一个空态，"没测过"与"测到 0 秒"
     *                      在页面上无法也不需区分（与 {@code cost} 的三态不同）
     */
    public record UsageResponse(long calls,
                                long tokens,
                                BigDecimal cost,
                                String currency,
                                int unpricedCount,
                                double avgLatencySec,
                                List<PurposeUsage> byPurpose,
                                List<ModelUsageItem> byModel) {
    }

    /**
     * 切换启用结果（前端 {@code activateModel} 的返回类型）。
     *
     * <p>两个 {@code Boolean} 是**可空**的，且三态各有含义 —— 别把 {@code null} 当成 {@code false}：
     * <ul>
     *   <li>{@code needReindex}：换**嵌入**模型要全库重建（`T-906`）。
     *       本批**恒 {@code false}** —— 嵌入的 activate 在服务层直接被拒（全局单例，`G6-2`），
     *       走不到这里。留这个字段是因为前端类型已经声明了它，将来 `T-906` 接管时不必改契约；</li>
     *   <li>{@code needRebuildImageDocs}：换 **VL** 模型后，**已入库文档的图片描述不会自动更新**
     *       （仍是旧模型产出的），需要重建含图文档（`T-908`）。所以 VL 切换成功时这里如实回
     *       {@code true} —— 本批只**如实告知**，重建入口本身在 `T-908`，
     *       前端的 VL 切换对话框据此提示（`ModelConfigView.vue:317-332` 已有那个弹窗）。</li>
     * </ul>
     */
    public record ActivateResult(long id,
                                 boolean activated,
                                 ModelType modelType,
                                 Boolean needReindex,
                                 Boolean needRebuildImageDocs) {
    }

    /**
     * 连接测试结果（`T-903`；前端 {@code testModel} 的返回类型）。
     *
     * @param latencyMs **首字延迟**（毫秒）。测不出来时为 {@code -1}
     *                  —— 用负数而不是 {@code 0}：{@code 0ms} 是"快得不可能"，
     *                  而"没测到"与"很快"在前端展示上必须能区分
     * @param message   给人看的一句话。**失败时必须给「现象 + 下一步动作」**（`G4-3`）：
     *                  只说"连接失败"等于把"下一步该做什么"留给用户猜
     */
    public record TestResult(long id, boolean ok, long latencyMs, String message) {
    }
}
