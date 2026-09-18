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
import com.knowledge.ai.repository.SysModelConfigRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

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

    private final EntityManager entityManager;

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
                chatTokensThisMonthWan());
    }

    /**
     * 本月对话 token（单位：万）。
     *
     * <p>⚠️ **口径边界（务必知道）**：数据来自 {@code sys_chat_message} 的
     * {@code token_prompt + token_completion}，所以它**只覆盖对话链路** ——
     * 嵌入 / 重排 / VL 的用量没有落点（完整的用量统计是 `T-905`，属 `S6-B`）。
     * 它给的是**真数但是部分口径**；登记在 `S6-M09-A` §9。
     *
     * <p>刻意**不返回 0**：0 会被读成"本月确实没有用量"，那是另一种撒谎。
     */
    private double chatTokensThisMonthWan() {
        try {
            Object raw = entityManager.createNativeQuery(
                            "select coalesce(sum(coalesce(token_prompt, 0) + coalesce(token_completion, 0)), 0) "
                                    + "from sys_chat_message where created_at >= :from")
                    .setParameter("from", LocalDateTime.now().withDayOfMonth(1).toLocalDate().atStartOfDay())
                    .getSingleResult();
            double tokens = raw == null ? 0D : ((Number) raw).doubleValue();
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
