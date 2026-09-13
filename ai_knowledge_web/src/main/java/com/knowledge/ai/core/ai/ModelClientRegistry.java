package com.knowledge.ai.core.ai;

import com.knowledge.ai.core.enums.ModelType;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 模型读取与客户端缓存（T-014）。
 *
 * <p><b>业务模块的唯一入口</b>：任何模块要调模型，都经此取客户端，
 * **不得自己 {@code new}**（验收要点）。这样"换模型 / 换供应商"只有一处生效点。
 *
 * <p><b>缓存口径</b>（《09-需求-系统配置与通用》§FR-SYS-01）：
 * <ul>
 *   <li>客户端按 {@code (type, id, version)} 缓存 —— 版本参与 key，配置一改版本就变，
 *       新 key 自然不命中，不需要"猜哪些缓存该清"。</li>
 *   <li>{@link #invalidate(ModelType, String)} 只清**指定模型**的缓存，
 *       避免需求里点名的那个坑：「改了 A 模型把 B 的客户端也清了」。</li>
 *   <li>{@link #invalidateAll(ModelType)} 用于"整类重配"（如换嵌入模型）。</li>
 * </ul>
 *
 * <p><b>兜底口径</b>：配置来源（S6 后是 DB）**抛异常**时，改用该类"最近一次有效配置"，
 * 让配置中心抖动不至于把全站问答打断；但来源**正常返回空**时**不兜底** ——
 * 那代表配置确实被删了，继续用旧的会让页面显示与实际不符，必须显式报错。
 * 这两种情况的区别是刻意区分的：一个是"读不到"，一个是"真的没有"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelClientRegistry {

    private final ModelConfigSource configSource;

    private final ModelClientFactory clientFactory;

    /** 客户端缓存：{@code type|id|version} -> ChatClient / EmbeddingModel。 */
    private final Map<String, Object> clients = new ConcurrentHashMap<>();

    /** 每类最近一次有效配置，供来源不可用时兜底。 */
    private final Map<ModelType, ModelConfigSpec> lastKnownGood = new ConcurrentHashMap<>();

    // ==================================================================
    // 取用
    // ==================================================================

    /** 取当前启用的对话客户端（问答主链路用这个）。 */
    public ChatClient chatClient() {
        return chatClient(activeSpec(ModelType.CHAT).id());
    }

    /**
     * 按模型配置 id 取对话客户端（用户在问答页选了某个模型时用）。
     *
     * @param modelId {@link ModelConfigSpec#id()}，取自模型选项接口
     */
    public ChatClient chatClient(String modelId) {
        ModelConfigSpec spec = specById(ModelType.CHAT, modelId);
        return getOrCreate(spec, () -> ChatClient.builder((ChatModel) clientFactory.createChat(spec)).build(),
                ChatClient.class);
    }

    /**
     * 取嵌入模型。
     *
     * <p><b>没有重载、不接受 id</b>：嵌入是**全局单例**（决策 D-4），
     * bge-m3 / 1024 维 / L2 归一化。换它等于换向量空间、全库重建 ——
     * 所以这里刻意不提供"按库选嵌入"的口子。
     */
    public EmbeddingModel embeddingModel() {
        ModelConfigSpec spec = activeSpec(ModelType.EMBEDDING);
        return getOrCreate(spec, () -> clientFactory.createEmbedding(spec), EmbeddingModel.class);
    }

    // ==================================================================
    // 规格解析
    // ==================================================================

    /** 取某类型当前生效的规格。 */
    public ModelConfigSpec activeSpec(ModelType type) {
        List<ModelConfigSpec> specs;
        try {
            specs = configSource.listEnabled(type);
        } catch (Exception ex) {
            ModelConfigSpec fallback = lastKnownGood.get(type);
            if (fallback == null) {
                log.error("模型配置来源不可用，且没有可兜底的配置：type={} source={}",
                        type, configSource.sourceName(), ex);
                throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED,
                        "模型配置暂时不可用：" + type.getLabel(), ex);
            }
            log.warn("模型配置来源不可用，改用最近一次有效配置：type={} 用={} 来源={} 原因={}",
                    type, fallback.describe(), configSource.sourceName(), ex.getMessage());
            return fallback;
        }

        if (specs == null || specs.isEmpty()) {
            throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED,
                    type.getLabel() + "未配置：请检查 .env 的密钥，或在模型配置页启用一条");
        }

        ModelConfigSpec active = specs.get(0);
        lastKnownGood.put(type, active);
        return active;
    }

    /** 取某类型下全部启用规格（模型选项列表用）。 */
    public List<ModelConfigSpec> listEnabled(ModelType type) {
        return List.copyOf(configSource.listEnabled(type));
    }

    /** 按 id 取规格，不存在则报错。 */
    public ModelConfigSpec specById(ModelType type, String id) {
        return listEnabled(type).stream()
                .filter(spec -> spec.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.MODEL_NOT_CONFIGURED,
                        type.getLabel() + "不存在或未启用：" + id));
    }

    // ==================================================================
    // 失效（配置变更时由配置服务调用）
    // ==================================================================

    /**
     * 只失效指定模型的客户端。
     *
     * <p>这是需求点名的场景：多模型之后「改了 A 模型不能把 B 的客户端也清了」。
     */
    public int invalidate(ModelType type, String id) {
        String prefix = type.name() + "|" + id + "|";
        int removed = removeIfKeyStartsWith(prefix);
        log.info("模型客户端缓存失效：type={} id={} 清除 {} 个（其余模型不受影响）", type, id, removed);
        return removed;
    }

    /** 失效某类型的全部客户端（如整体切换嵌入模型）。 */
    public int invalidateAll(ModelType type) {
        String prefix = type.name() + "|";
        int removed = removeIfKeyStartsWith(prefix);
        log.info("模型客户端缓存整类失效：type={} 清除 {} 个", type, removed);
        return removed;
    }

    /** 失效全部（配置来源整体切换时用）。 */
    public int invalidateAll() {
        int removed = clients.size();
        clients.clear();
        log.info("模型客户端缓存全部失效：清除 {} 个", removed);
        return removed;
    }

    // ==================================================================
    // 观测（健康端点用，不含密钥）
    // ==================================================================

    /**
     * 当前解析结果概览。
     *
     * <p>只输出 {@link ModelConfigSpec#describe()}（key 只报有无），
     * 与 {@code /api/health/config} 的口径一致（G0-8）。
     */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("source", configSource.sourceName());
        status.put("cachedClients", clients.size());

        Map<String, Object> resolved = new LinkedHashMap<>();
        for (ModelType type : ModelType.values()) {
            try {
                resolved.put(type.name(), activeSpec(type).describe());
            } catch (BizException ex) {
                // 状态查询不该因为"某一类没配"而整体失败
                resolved.put(type.name(), "不可用：" + ex.getMessage());
            }
        }
        status.put("resolved", resolved);
        return status;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 缓存未命中才构造。
     *
     * <p>用 {@code computeIfAbsent} 而不是"先查后放"：并发下同一个 key 只会构造一次，
     * 避免瞬时打满连接池。构造过程里不做 I/O（只装配客户端对象），故持锁时间可忽略。
     */
    @SuppressWarnings("unchecked")
    private <T> T getOrCreate(ModelConfigSpec spec, Supplier<Object> factory, Class<T> expectedType) {
        Object client = clients.computeIfAbsent(spec.cacheKey(), key -> {
            log.info("装配模型客户端：{}", spec.describe());
            return factory.get();
        });
        return (T) expectedType.cast(client);
    }

    private int removeIfKeyStartsWith(String prefix) {
        int before = clients.size();
        clients.keySet().removeIf(key -> key.startsWith(prefix));
        return before - clients.size();
    }

    /** 供测试与排查：判断某规格的客户端是否已在缓存里。 */
    Optional<Object> peekCached(ModelConfigSpec spec) {
        return Optional.ofNullable(clients.get(spec.cacheKey()));
    }
}
