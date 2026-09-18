package com.knowledge.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledge.ai.core.ai.DbModelConfigSource;
import com.knowledge.ai.core.ai.ModelClientRegistry;
import com.knowledge.ai.core.ai.ModelConfigDefaults;
import com.knowledge.ai.core.ai.ModelConfigJson;
import com.knowledge.ai.core.ai.ModelConfigSpec;
import com.knowledge.ai.core.ai.ModelProperties;
import com.knowledge.ai.core.enums.BillingUnit;
import com.knowledge.ai.core.enums.ModelType;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.dto.system.ModelConfigDtos;
import com.knowledge.ai.entity.SysModelConfig;
import com.knowledge.ai.entity.SysModelUsage;
import com.knowledge.ai.repository.SysModelConfigRepository;
import com.knowledge.ai.repository.SysModelUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M09 模型配置（`T-900`~`T-903`）—— 列表 / 增改删 / 启用切换 / 连接测试。
 *
 * <h3>本类坐在两件"会互相拆台"的事中间</h3>
 * <ol>
 *   <li><b>它是配置的 owner</b>：DB 里的行怎么改、改完哪些结论失效（测试结论、客户端缓存）；</li>
 *   <li><b>它同时是"模型调用"的触发者</b>：连接测试要按**待测的那一行**造一个客户端并真发请求
 *       （`T-903`）。而"客户端只由 {@code ModelClientRegistry} 构造"是本项目的结构纪律
 *       （`T-014`），所以这里**不碰 {@code ModelClientFactory}**，一律经注册中心。</li>
 * </ol>
 *
 * <h3>三条需求硬规则（都在《09》§1.6）</h3>
 * <ul>
 *   <li><b>必须先连接测试通过才能启用</b>（`G6-3`）—— {@link #activate} 的第一道闸；</li>
 *   <li><b>编辑后需重新测试</b> —— 改了 Base URL / 模型名就把 {@code last_test_ok} 归零，
 *       否则"改完还能启用"会让启用门槛形同虚设；</li>
 *   <li><b>当前启用的不可删除</b> —— 删掉它会让该类当场没有可用模型。</li>
 * </ul>
 *
 * <h3>审计为什么不在这里</h3>
 * <p>与 {@code ChatController} 的导出接口同一条理由：审计的 {@code operator} 与 {@code apiPath}
 * 取自**请求上下文**，打在 Service 上会测成空值。四个写动作的 {@code @AuditLog} 挂在
 * {@link com.knowledge.ai.controller.ModelConfigController} 上。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelConfigService {

    /** {@code lastTestAt} 的展示格式 —— 前端原样渲染，不做格式化。 */
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SysModelConfigRepository repository;

    private final DbModelConfigSource configSource;

    private final ModelClientRegistry registry;

    private final ModelProperties modelProperties;

    /** `T-905` 的用量落点。见 {@link #usage()}。 */
    private final SysModelUsageRepository usageRepository;

    // ==================================================================
    // T-900 —— 列表
    // ==================================================================

    @Transactional(readOnly = true)
    public ModelConfigDtos.ConfigResponse list() {
        List<SysModelConfig> all = repository.findAllByOrderByModelTypeAscSortOrderAscIdAsc();

        List<ModelConfigDtos.ModelConfigItem> chat = itemsOf(all, ModelType.CHAT);
        List<ModelConfigDtos.ModelConfigItem> rerank = itemsOf(all, ModelType.RERANK);
        List<ModelConfigDtos.ModelConfigItem> vl = itemsOf(all, ModelType.VL);
        // 嵌入是单例：前端类型是 `embedding: ModelConfigItem`（**对象**不是数组）。
        // 没有时给 null，前端按"未配置"渲染
        ModelConfigDtos.ModelConfigItem embedding = itemsOf(all, ModelType.EMBEDDING).stream()
                .findFirst().orElse(null);

        return new ModelConfigDtos.ConfigResponse(
                stats(chat, rerank, vl), chat, embedding, rerank, vl);
    }

    private ModelConfigDtos.Stats stats(List<ModelConfigDtos.ModelConfigItem> chat,
                                        List<ModelConfigDtos.ModelConfigItem> rerank,
                                        List<ModelConfigDtos.ModelConfigItem> vl) {
        ModelConfigDtos.ModelConfigItem defaultChat = chat.stream()
                .filter(ModelConfigDtos.ModelConfigItem::isActive)
                .findFirst().orElse(null);
        return new ModelConfigDtos.Stats(
                chat.size(), rerank.size(), vl.size(),
                defaultChat == null ? "未配置" : defaultChat.displayName(),
                defaultChat != null && defaultChat.lastTestOk(),
                monthTokensWan());
    }

    /**
     * 本月 token 合计（单位：万）—— **全链路**，不是只有对话。
     *
     * <h3>`T-905` 起口径变了（销 `#169`）</h3>
     * <p>`S6-M09-A` 时这一格的来源是 {@code sys_chat_message} 的
     * {@code token_prompt + token_completion}，所以它**只覆盖对话链路** ——
     * 嵌入 / 重排 / 摘要 / HyQ / VL 的用量当时一处落点都没有，给的是
     * 「**真数但是部分口径**」（`#169`）。`T-905` 建了 {@code sys_model_usage}
     * 并接上六个采集点之后，这一格改成读它，与 {@link #usage()} 的 {@code tokens}
     * **同源**（两处显示不一致是最容易被发现的那种缺陷）。
     *
     * <p>⚠️ 由此有一条必须知道的后果：本表是新表，**历史对话的 token 不追溯** ——
     * 第一次真实调用发生前这里显示 0。那是正确的空态（换了个落点，从今天起开始记），
     * 历史值仍在 {@code sys_chat_message} 里。
     */
    private double monthTokensWan() {
        try {
            LocalDate today = LocalDate.now();
            long tokens = usageRepository.findByOccurredAtBetween(today.withDayOfMonth(1), today).stream()
                    .mapToLong(ModelConfigService::tokensOf)
                    .sum();
            return BigDecimal.valueOf(tokens / 10_000D).setScale(1, RoundingMode.HALF_UP).doubleValue();
        } catch (RuntimeException ex) {
            // 统计不是配置页的核心功能 —— 它坏了不该让整个页面打不开
            log.warn("本月 token 统计失败（配置页按 0 显示）：{}", ex.getMessage());
            return 0D;
        }
    }

    private List<ModelConfigDtos.ModelConfigItem> itemsOf(List<SysModelConfig> all, ModelType type) {
        return all.stream().filter(row -> row.getModelType() == type).map(this::toItem).toList();
    }

    // ==================================================================
    // T-904 —— 问答页模型选项
    // ==================================================================

    /**
     * 某类型**可选用**的模型（`GET /system/model/options`）。
     *
     * <h3>过滤条件只有一条：{@code last_test_ok}</h3>
     * <p>任务行的验收要点原文是「**仅已启用且测试通过**」。这里的两半要分开看：
     * <ul>
     *   <li>「测试通过」由 {@code last_test_ok} 直接表达 —— 一个从没测通过的模型
     *       选上去只会让用户问一句话然后拿到一个连接错误；</li>
     *   <li>「已启用」**不能**理解成 {@code is_active = 1} —— 那样同类型就只剩一条了，
     *       而 mock 与前端把 {@code isActive} 放在**每一项**上（`isActive?: boolean`），
     *       说明列表里本来就有多条、其中一条被标记为当前启用。</li>
     * </ul>
     * 所以口径是：**回该类型的全部行，用 {@code isActive} 标出当前启用那一条**。
     *
     * <h3>权限码为什么是 {@code qa:ask} 而不是 {@code system:model}</h3>
     * <p>本接口的消费方是**问答页的切换器**（`ChatView.vue:29` 已在调），
     * 而 {@code system:model} 在 `V2` 的授权矩阵里**只有 {@code SUPER_ADMIN} 有**
     * （本批开工前逐行查过 `sys_role_permission`）—— 挂它等于**普通用户打不开切换器**。
     * 与 `S5-M08-C` 的 {@code qa:feedback} 教训同族：**权限码名字像、语义不同，
     * 挂错的表现是普通用户用不了**。见计划 §1.1 第 6 条。
     */
    @Transactional(readOnly = true)
    public ModelConfigDtos.OptionList options(ModelType type) {
        ModelType target = type == null ? ModelType.CHAT : type;
        List<ModelConfigDtos.ModelOption> list = repository
                .findAllByOrderByModelTypeAscSortOrderAscIdAsc().stream()
                .filter(row -> row.getModelType() == target)
                .filter(row -> Boolean.TRUE.equals(row.getLastTestOk()))
                .map(row -> new ModelConfigDtos.ModelOption(row.getId(), row.getDisplayName(),
                        row.getModelName(), Boolean.TRUE.equals(row.getIsActive()),
                        readCapabilities(row.getCapabilities())))
                .toList();
        return new ModelConfigDtos.OptionList(list);
    }

    // ==================================================================
    // T-905 —— 用量统计
    // ==================================================================

    /** 用途桶在页面上的**固定展示顺序** —— 与 mock 的 `byPurpose` 顺序一致。 */
    private static final List<ModelUsageRecorder.Purpose> PURPOSE_ORDER = List.of(
            ModelUsageRecorder.Purpose.SUMMARY,
            ModelUsageRecorder.Purpose.CHAT,
            ModelUsageRecorder.Purpose.HYQ,
            ModelUsageRecorder.Purpose.EMBEDDING,
            ModelUsageRecorder.Purpose.RERANK,
            ModelUsageRecorder.Purpose.VL_DESC);

    /**
     * 本月用量统计（`GET /system/model/usage`）。
     *
     * <h3>数据来源：{@code sys_model_usage} 是**唯一**的用量事实源</h3>
     * <p>`T-905` 之前，对话用量只能从 {@code sys_chat_message} 的
     * {@code token_prompt / token_completion} 算，而嵌入 / 重排 / 摘要 / HyQ / VL
     * **一处落点都没有** —— 这正是 `#169` 登记的"真数但是部分口径"。
     * 本批起用量统一落 {@code sys_model_usage}，本方法**只读它**。
     *
     * <p>⚠️ 由此有一条**必须知道**的后果：本表是**新表**，历史对话的 token
     * **不会**追溯进来 —— 本月的数字在第一次真实调用发生前是 0。
     * 这是正确的空态（"换了个落点，从今天起开始记"），不是数据丢失：
     * 历史值仍在 {@code sys_chat_message} 里，需要时可回算。
     *
     * <h3>费用：三态 + 只累加已计价项</h3>
     * <p>见 {@link ModelConfigDtos.ModelUsageItem#cost}。汇总那个 {@code cost}
     * **只累加可估算的**，并同时给出 {@code unpricedCount} —— 一个少了几个模型的合计，
     * 必须自己说清少了几个。
     */
    @Transactional(readOnly = true)
    public ModelConfigDtos.UsageResponse usage() {
        LocalDate today = LocalDate.now();
        LocalDate from = today.withDayOfMonth(1);
        List<SysModelUsage> rows = usageRepository.findByOccurredAtBetween(from, today);
        Map<String, SysModelConfig> pricing = pricingByModelName();

        long calls = 0L;
        long tokens = 0L;
        long latencySum = 0L;
        long latencyCount = 0L;
        Map<ModelUsageRecorder.Purpose, Long> byPurpose = new LinkedHashMap<>();
        Map<String, UsageBucket> byModel = new LinkedHashMap<>();

        for (SysModelUsage row : rows) {
            long rowTokens = tokensOf(row);
            calls += row.getCalls();
            tokens += rowTokens;
            if (row.getLatencyMs() != null) {
                latencySum += row.getLatencyMs();
                latencyCount += row.getCalls();
            }
            ModelUsageRecorder.Purpose purpose = purposeOf(row.getPurpose());
            if (purpose != null) {
                byPurpose.merge(purpose, rowTokens, Long::sum);
            }
            byModel.computeIfAbsent(row.getModelName(),
                            key -> new UsageBucket(row.getModelType(), key))
                    .add(row.getCalls(), nz(row.getPromptTokens()), nz(row.getCompletionTokens()));
        }

        BigDecimal cost = null;
        int unpriced = 0;
        List<ModelConfigDtos.ModelUsageItem> modelItems = new ArrayList<>(byModel.size());
        String currency = "CNY";
        for (UsageBucket bucket : byModel.values()) {
            SysModelConfig config = pricing.get(bucket.modelName);
            BillingUnit unit = config == null || config.getBillingUnit() == null
                    ? BillingUnit.PER_TOKEN : config.getBillingUnit();
            BigDecimal itemCost = estimate(bucket, config, unit);
            if (itemCost != null) {
                cost = cost == null ? itemCost : cost.add(itemCost);
            } else if (unit != BillingUnit.NONE) {
                // `NONE`（本地部署）不算"忘了配单价" —— 它本来就是 0 元，且页面要把它
                // 显示成「不计费」而不是「未配置」。两者都是"0"，语义相反
                unpriced++;
            }
            if (config != null && config.getCurrency() != null && !config.getCurrency().isBlank()) {
                currency = config.getCurrency();
            }
            modelItems.add(new ModelConfigDtos.ModelUsageItem(bucket.modelName,
                    parseType(bucket.modelType), bucket.calls, bucket.tokens(), itemCost, unit));
        }
        modelItems.sort(Comparator.comparingLong(ModelConfigDtos.ModelUsageItem::tokens).reversed());

        return new ModelConfigDtos.UsageResponse(calls, tokens, cost, currency, unpriced,
                avgLatencySec(latencySum, latencyCount), purposeItems(byPurpose, tokens), modelItems);
    }

    private static long tokensOf(SysModelUsage row) {
        return nz(row.getPromptTokens()) + nz(row.getCompletionTokens());
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    /** 模型名 → 配置行（用于取计价）。同名多条时取**排序最前**的那条，与配置页的展示顺序一致。 */
    private Map<String, SysModelConfig> pricingByModelName() {
        Map<String, SysModelConfig> map = new LinkedHashMap<>();
        for (SysModelConfig row : repository.findAllByOrderByModelTypeAscSortOrderAscIdAsc()) {
            map.putIfAbsent(row.getModelName(), row);
        }
        return map;
    }

    /**
     * 估算一个模型的费用。**返回 {@code null} = 无法估算**（不是 0）。
     *
     * <p>三种计价单位各走各的：
     * <ul>
     *   <li>{@code NONE} → {@code 0}（**已知不计费**，本地部署）；</li>
     *   <li>{@code PER_CALL} → 用了 {@code per_call_price} 才算得出来；</li>
     *   <li>{@code PER_TOKEN} → 用了 {@code input_price} / {@code output_price} 才算得出来。
     *       ⚠️ **两者都没有时返回 {@code null}**，而不是拿其中一个去乘全部 token ——
     *       那会让一个"没配单价"的模型显示成一个偏低的数，比"未配置"更误导。</li>
     * </ul>
     */
    private static BigDecimal estimate(UsageBucket bucket, SysModelConfig config, BillingUnit unit) {
        if (unit == BillingUnit.NONE) {
            return BigDecimal.ZERO;
        }
        if (config == null) {
            return null;
        }
        if (unit == BillingUnit.PER_CALL) {
            return config.getPerCallPrice() == null
                    ? null
                    : config.getPerCallPrice().multiply(BigDecimal.valueOf(bucket.calls));
        }
        if (config.getInputPrice() == null && config.getOutputPrice() == null) {
            return null;
        }
        BigDecimal perMillion = BigDecimal.valueOf(1_000_000L);
        BigDecimal in = config.getInputPrice() == null ? BigDecimal.ZERO : config.getInputPrice();
        BigDecimal out = config.getOutputPrice() == null ? BigDecimal.ZERO : config.getOutputPrice();
        return in.multiply(BigDecimal.valueOf(nz(bucket.promptTokens))).add(
                        out.multiply(BigDecimal.valueOf(nz(bucket.completionTokens))))
                .divide(perMillion, 2, RoundingMode.HALF_UP);
    }

    private static ModelType parseType(String name) {
        try {
            return ModelType.valueOf(name);
        } catch (IllegalArgumentException ex) {
            // 库里存的是枚举名；出现认不出的值说明有人手工改过表 —— 按对话模型兜底
            // 而不是让整个接口 500（用量页不该因为一行脏数据打不开）
            log.warn("用量表里出现认不出的 model_type：{}", name);
            return ModelType.CHAT;
        }
    }

    private static ModelUsageRecorder.Purpose purposeOf(String name) {
        try {
            return ModelUsageRecorder.Purpose.valueOf(name);
        } catch (IllegalArgumentException ex) {
            log.warn("用量表里出现认不出的 purpose：{}", name);
            return null;
        }
    }

    /**
     * 按固定顺序产出用途桶，**空桶也发**。
     *
     * <p>为什么发 0 而不是省略：前端是按桶名匹配渲染的（"索引侧占了 63%"这类判断
     * 需要看到每一个桶）。省掉一个桶，界面上就是那一行凭空消失 —— 而
     * 「这一项是 0」与「这一项不存在」在用量分析里是两件事。
     */
    private static List<ModelConfigDtos.PurposeUsage> purposeItems(
            Map<ModelUsageRecorder.Purpose, Long> byPurpose, long total) {
        List<ModelConfigDtos.PurposeUsage> items = new ArrayList<>(PURPOSE_ORDER.size());
        for (ModelUsageRecorder.Purpose purpose : PURPOSE_ORDER) {
            long value = byPurpose.getOrDefault(purpose, 0L);
            items.add(new ModelConfigDtos.PurposeUsage(purpose.getLabel(), value, percent(value, total)));
        }
        return items;
    }

    private static int percent(long value, long total) {
        return total <= 0L ? 0 : (int) Math.round(value * 100.0D / total);
    }

    private static double avgLatencySec(long latencySum, long latencyCount) {
        if (latencyCount <= 0L) {
            return 0D;
        }
        return BigDecimal.valueOf(latencySum / 1000.0D / latencyCount)
                .setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 按模型聚合的中间态（可变，便于一次遍历累加）。
     *
     * <p>⚠️ **只有这一处能算 {@link #tokens}** —— 它是 {@code prompt + completion}，
     * 而库里**刻意没有**"合计"那一列（冗余列与明细列迟早漂移，见 `V27` 头注）。
     * 让两个数各加各的、合计只在这一处派生，就不会出现"合计与明细对不上"。
     */
    private static final class UsageBucket {

        private final String modelType;
        private final String modelName;
        private long calls;
        private long promptTokens;
        private long completionTokens;

        UsageBucket(String modelType, String modelName) {
            this.modelType = modelType;
            this.modelName = modelName;
        }

        void add(int rowCalls, long prompt, long completion) {
            this.calls += rowCalls;
            this.promptTokens += prompt;
            this.completionTokens += completion;
        }

        long tokens() {
            return promptTokens + completionTokens;
        }
    }

    // ==================================================================
    // T-901 —— 新增 / 编辑 / 删除
    // ==================================================================

    @Transactional
    public ModelConfigDtos.SavedResult create(ModelConfigDtos.SaveRequest request, Long operatorId) {
        ModelType type = request.modelType();
        if (type == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "模型类型（modelType）必填");
        }
        // 《09》§1.2 硬约束一：嵌入是全局单例，不可多实例
        if (type.isSingleton()) {
            throw BizException.of(ErrorCode.MODEL_EMBEDDING_SINGLETON);
        }
        requireText(request.displayName(), "显示名称");
        requireText(request.baseUrl(), "Base URL");
        requireText(request.modelName(), "模型名称");

        LocalDateTime now = LocalDateTime.now();
        SysModelConfig row = new SysModelConfig();
        row.setModelType(type);
        row.setProtocol(blankToDefault(request.protocol(), "OpenAI 兼容"));
        row.setCurrency(blankToDefault(request.currency(), "CNY"));
        row.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        // 新增的行**一律未启用且未测试**：启用要走 activate（那里有 G6-3 的门槛）。
        // 若允许在请求体里带 isActive，就等于绕过了"先测通过才能启用"
        row.setIsActive(false);
        row.setLastTestOk(false);
        row.setCreatedBy(operatorId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        applyEditable(row, request);

        SysModelConfig saved = repository.save(row);
        log.info("[模型配置] 新增：id={} type={} name={} operator={}",
                saved.getId(), type, saved.getDisplayName(), operatorId);
        return new ModelConfigDtos.SavedResult(saved.getId(), true);
    }

    @Transactional
    public ModelConfigDtos.UpdatedResult update(Long id, ModelConfigDtos.SaveRequest request) {
        SysModelConfig row = require(id);
        if (request.modelType() != null && request.modelType() != row.getModelType()) {
            // 《09》§1.5：模型类型**创建后不可修改**。改类型等于换一个模型，
            // 轨迹（测试结论、启用状态、将来的用量归因）会全部对不上
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "模型类型创建后不可修改（当前 " + row.getModelType().getLabel()
                            + "）；如需换类型，请新建成另一条");
        }

        boolean testInvalidated = request.baseUrl() != null || request.modelName() != null;
        applyEditable(row, request);
        row.setUpdatedAt(LocalDateTime.now());
        if (testInvalidated) {
            // 《09》§1.6「编辑后需重新测试」：改了端点或模型名，原测试结论不再说明任何事。
            // ⚠️ 连 `last_test_at` 一起清 —— 只把 ok 置 false 会留下
            // "测过（有时间的）但没通过"的假象，而真相是"那次测试测的是旧配置"
            row.setLastTestOk(false);
            row.setLastTestAt(null);
            row.setLastLatencyMs(null);
        }
        repository.save(row);
        log.info("[模型配置] 编辑：id={} needRetest={}", id, testInvalidated);
        return new ModelConfigDtos.UpdatedResult(id, true, testInvalidated);
    }

    @Transactional
    public ModelConfigDtos.DeletedResult delete(Long id) {
        SysModelConfig row = require(id);
        if (Boolean.TRUE.equals(row.getIsActive())) {
            throw BizException.of(ErrorCode.MODEL_ACTIVE_UNDELETABLE);
        }
        repository.delete(row);
        log.info("[模型配置] 删除：id={} type={} name={}", id, row.getModelType(), row.getDisplayName());
        return new ModelConfigDtos.DeletedResult(id, true);
    }

    // ==================================================================
    // T-902 —— 启用切换
    // ==================================================================

    /**
     * 切换启用。
     *
     * <p>顺序即正确性，四道闸依次是：
     * <ol>
     *   <li>嵌入直接拒（全局单例，切换 = 换模型 + 全库重建，那是 `T-906`）；</li>
     *   <li>必须已通过连接测试（`G6-3`）；</li>
     *   <li>事务内把同类型其余置为未启用 —— 库层还有 `uk_model_active` 唯一索引兜底，</li>
     *   <li>写 {@code activated_at}、失效该类型/该 id 的客户端缓存。</li>
     * </ol>
     *
     * <p>⚠️ 第 3 步**必须先于**第 4 步（或者说必须在同一个事务里）：
     * 唯一索引意味着"先启用新的再停旧的"会当场撞索引，
     * 所以顺序是**先把旧的置 false、再置新的 true**，flush 由事务收尾。
     */
    @Transactional
    public ModelConfigDtos.ActivateResult activate(Long id) {
        SysModelConfig row = require(id);
        ModelType type = row.getModelType();
        if (type.isSingleton()) {
            throw BizException.of(ErrorCode.MODEL_EMBEDDING_SINGLETON);
        }
        if (!Boolean.TRUE.equals(row.getLastTestOk())) {
            throw BizException.of(ErrorCode.MODEL_NOT_TESTED);
        }
        if (Boolean.TRUE.equals(row.getIsActive())) {
            // 已经是启用态：幂等返回，不报错也不重记时间 —— 重复点"启用"不该刷新生效时间
            return result(row, false);
        }

        // ① 先停同类型其余（唯一索引要求任何时刻至多一条）
        List<SysModelConfig> others = repository.findByModelTypeAndIsActiveTrue(type);
        for (SysModelConfig other : others) {
            other.setIsActive(false);
            other.setUpdatedAt(LocalDateTime.now());
            repository.save(other);
        }
        repository.flush();

        // ② 再启用本条
        row.setIsActive(true);
        row.setActivatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        repository.save(row);

        // ③ 缓存失效。嵌入/重排/VL 是全局单例（整类失效）；对话多条（按 id 失效）——
        //    《09》§1.6 之后的口径见 `汇总-归档.md` §C 的「启用切换时调 invalidate」
        if (type == ModelType.CHAT) {
            registry.invalidate(type, String.valueOf(id));
        } else {
            registry.invalidateAll(type);
        }

        log.info("[模型配置] 启用切换：id={} type={} name={}（原启用 {} 条已停）",
                id, type, row.getDisplayName(), others.size());
        return result(row, true);
    }

    private ModelConfigDtos.ActivateResult result(SysModelConfig row, boolean activated) {
        // 换 VL 模型后，**已入库文档的图片描述仍是旧模型产出的**（《09》§1.12）→ 如实告知需要重建。
        // 重建入口本身在 `T-908`，本批只回这个标志位，前端据此提示
        boolean needRebuildImageDocs = row.getModelType() == ModelType.VL;
        return new ModelConfigDtos.ActivateResult(row.getId(), activated, row.getModelType(),
                // 嵌入的 activate 在上面已被拒，所以换嵌入的"全库重建"永远不会走到这里
                false, needRebuildImageDocs ? Boolean.TRUE : Boolean.FALSE);
    }

    // ==================================================================
    // T-903 —— 连接测试
    // ==================================================================

    /**
     * 连接测试：**按待测的那一行真发一次请求**（《09》§1.15）。
     *
     * <p>为什么必须真发：只校验"字段填没填"测不出网络不通、密钥过期、模型名不存在、
     * 配额用尽 —— 而这四件事恰恰是"配了却用不了"的全部原因。
     *
     * <p>四类各用一种**最省的**探测方式：
     * <ul>
     *   <li>CHAT：发一句 {@code ping}，只取有无响应；</li>
     *   <li>EMBEDDING：嵌入一个短句，检查向量维度；</li>
     *   <li>RERANK：给两句候选打分；</li>
     *   <li>VL：**也走对话形态** —— VL 模型本身就是 OpenAI 兼容的多模态对话模型，
     *       发一句纯文本足以探到"通不通、密钥对不对、模型名认不认"。
     *       （真正的图片理解链路是 `T-907`，连接测试不该依赖它。）</li>
     * </ul>
     *
     * <p>无论成败都把结论写回 {@code last_test_*} —— 失败也写，因为"测过且没过"与
     * "从没测过"在配置页上是两句不同的话（前者能告诉用户上次为什么不行）。
     */
    @Transactional
    public ModelConfigDtos.TestResult test(Long id) {
        SysModelConfig row = require(id);
        ModelConfigSpec spec = configSource.specOf(row);

        // 客户端构造与探测都在注册中心里（`T-014` 的结构纪律：客户端只由它造）。
        // 它**不返回客户端句柄** —— 那正是它和"开一个按规格取客户端的口子"的区别，
        // 详见 `ModelClientRegistry#probeConnection` 的注释
        ModelClientRegistry.ProbeOutcome outcome = registry.probeConnection(spec);

        markTest(row, outcome.ok(), outcome.ok() ? outcome.latencyMs() : null);
        if (outcome.ok()) {
            log.info("[模型配置] 连接测试通过：id={} type={} 首字延迟={}ms",
                    id, row.getModelType(), outcome.latencyMs());
            return new ModelConfigDtos.TestResult(id, true, outcome.latencyMs(), outcome.detail());
        }
        // `G4-3`：失败必须给「现象 + 下一步动作」。"连接失败"四个字等于让用户自己猜
        String message = "连接失败：" + outcome.detail()
                + " —— 请核对 Base URL 是否写全（含 /v1）、API Key 是否有效、模型名称是否存在，然后重新测试。";
        log.warn("[模型配置] 连接测试失败：id={} type={} 原因={}", id, row.getModelType(), outcome.detail());
        return new ModelConfigDtos.TestResult(id, false, -1L, message);
    }

    private void markTest(SysModelConfig row, boolean ok, Long latencyMs) {
        row.setLastTestAt(LocalDateTime.now());
        row.setLastTestOk(ok);
        row.setLastLatencyMs(ok && latencyMs != null ? Math.toIntExact(Math.min(latencyMs, Integer.MAX_VALUE)) : null);
        row.setUpdatedAt(LocalDateTime.now());
        repository.save(row);
    }

    // ==================================================================
    // 行 → DTO
    // ==================================================================

    private ModelConfigDtos.ModelConfigItem toItem(SysModelConfig row) {
        ModelConfigSpec.GenerationParams params = ModelConfigJson.readParams(row.getParams());
        return new ModelConfigDtos.ModelConfigItem(
                row.getId(),
                row.getModelType(),
                row.getDisplayName(),
                row.getProtocol(),
                row.getBaseUrl(),
                apiKeyMasked(row.getModelType()),
                row.getModelName(),
                row.getDimension(),
                row.getNorm(),
                row.getBatchSize(),
                row.getMetric(),
                new ModelConfigDtos.Params(params.temperatureOrDefault(), params.maxTokens(),
                        params.timeoutSecOrDefault(), params.retriesOrDefault()),
                readCapabilities(row.getCapabilities()),
                row.getBillingUnit(),
                row.getInputPrice(),
                row.getOutputPrice(),
                row.getPerCallPrice(),
                row.getCurrency(),
                Boolean.TRUE.equals(row.getIsActive()),
                row.getSortOrder() == null ? 0 : row.getSortOrder(),
                row.getLastTestAt() == null ? null : DISPLAY_TIME.format(row.getLastTestAt()),
                Boolean.TRUE.equals(row.getLastTestOk()),
                row.getLastLatencyMs(),
                row.getNote());
    }

    /**
     * 该类型的密钥掩码。
     *
     * <p>⚠️ **读取时现算**，不落库：密钥在 `.env` 里，落一列掩码快照意味着
     * "换了 key 而掩码没变" —— 界面上显示的是一个已经不存在的 key 的尾巴。
     *
     * <p>没有密钥时返回空串（前端按"未配置"渲染），而不是 {@code "sk-****"} 这种假掩码。
     */
    private String apiKeyMasked(ModelType type) {
        ModelProperties.Endpoint endpoint = modelProperties.endpointOf(type);
        String key = endpoint == null ? null : endpoint.getApiKey();
        if (key == null || key.isBlank()) {
            return "";
        }
        // 只露头 3 位与尾 4 位（《09》§1.5 的示例是 `sk-••••••3f9a`）；
        // 太短的 key 一律全掩 —— 否则"掩码"本身就把整个 key 泄出去了
        if (key.length() <= 10) {
            return "••••••";
        }
        return key.substring(0, 3) + "••••••••••••" + key.substring(key.length() - 4);
    }

    private ModelConfigDtos.Capabilities readCapabilities(String json) {
        if (json == null || json.isBlank()) {
            return defaults();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return new ModelConfigDtos.Capabilities(
                    boolOr(node, "stream", ModelConfigDefaults.STREAM),
                    boolOr(node, "vision", ModelConfigDefaults.VISION),
                    intOr(node, "contextWindow", ModelConfigDefaults.CONTEXT_WINDOW),
                    boolOr(node, "supportSystemRole", ModelConfigDefaults.SUPPORT_SYSTEM_ROLE));
        } catch (Exception ex) {
            log.warn("模型配置 capabilities 解析失败，按默认值处理：{}", ex.getMessage());
            return defaults();
        }
    }

    private static ModelConfigDtos.Capabilities defaults() {
        return new ModelConfigDtos.Capabilities(ModelConfigDefaults.STREAM, ModelConfigDefaults.VISION,
                ModelConfigDefaults.CONTEXT_WINDOW, ModelConfigDefaults.SUPPORT_SYSTEM_ROLE);
    }

    private static Boolean boolOr(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.get(field);
        return value == null || !value.isBoolean() ? fallback : value.booleanValue();
    }

    private static int intOr(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? fallback : value.intValue();
    }

    // ==================================================================
    // 请求 → 行
    // ==================================================================

    /** 把请求里**可编辑的那些**写进行。⚠️ 不碰 `modelType` / `isActive` / `lastTest*`。 */
    private void applyEditable(SysModelConfig row, ModelConfigDtos.SaveRequest request) {
        if (request.displayName() != null) {
            row.setDisplayName(request.displayName());
        }
        if (request.protocol() != null) {
            row.setProtocol(request.protocol());
        }
        if (request.baseUrl() != null) {
            row.setBaseUrl(request.baseUrl());
        }
        if (request.modelName() != null) {
            row.setModelName(request.modelName());
        }
        if (request.dimension() != null) {
            row.setDimension(request.dimension());
        }
        if (request.norm() != null) {
            row.setNorm(request.norm());
        }
        if (request.batchSize() != null) {
            row.setBatchSize(request.batchSize());
        }
        if (request.metric() != null) {
            row.setMetric(request.metric());
        }
        if (request.params() != null) {
            row.setParams(ModelConfigJson.writeParams(new ModelConfigSpec.GenerationParams(
                    request.params().temperature(), request.params().maxTokens(),
                    request.params().timeoutSec(), request.params().retries())));
        }
        if (request.capabilities() != null) {
            row.setCapabilities(writeCapabilities(request.capabilities()));
        }
        if (request.billingUnit() != null) {
            row.setBillingUnit(request.billingUnit());
        }
        if (request.inputPrice() != null) {
            row.setInputPrice(request.inputPrice());
        }
        if (request.outputPrice() != null) {
            row.setOutputPrice(request.outputPrice());
        }
        if (request.perCallPrice() != null) {
            row.setPerCallPrice(request.perCallPrice());
        }
        if (request.currency() != null) {
            row.setCurrency(request.currency());
        }
        if (request.sortOrder() != null) {
            row.setSortOrder(request.sortOrder());
        }
        if (request.note() != null) {
            row.setNote(request.note());
        }
    }

    private String writeCapabilities(ModelConfigDtos.Capabilities capabilities) {
        try {
            var node = MAPPER.createObjectNode();
            node.put("stream", capabilities.stream());
            node.put("vision", capabilities.vision());
            node.put("contextWindow", capabilities.contextWindow());
            node.put("supportSystemRole", capabilities.supportSystemRole());
            return MAPPER.writeValueAsString(node);
        } catch (Exception ex) {
            log.error("模型配置 capabilities 序列化失败：{}", ex.getMessage(), ex);
            return null;
        }
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private SysModelConfig require(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, label + "必填");
        }
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

}
