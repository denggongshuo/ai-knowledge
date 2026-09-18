package com.knowledge.ai.core.ai;

import com.knowledge.ai.core.constant.MilvusConstants;
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
        return chatClientOf(spec);
    }

    /** 按规格取对话客户端。**私有** —— 见 {@link #probeConnection} 的解释。 */
    private ChatClient chatClientOf(ModelConfigSpec spec) {
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

    /**
     * 取重排客户端（`T-702`）。**未配置时返回 {@link NoOpRerankClient}，不抛**。
     *
     * <h3>为什么这里与 {@link #chatClient()} / {@link #embeddingModel()} 口径不同</h3>
     * <p>那两类的"没配置"是**硬失败**：没有对话模型就没有问答，没有嵌入模型就建不了索引
     * —— 继续往下走只会得到一个更晚、更难懂的错。所以 {@link #activeSpec} 直接抛
     * {@code MODEL_NOT_CONFIGURED}。
     *
     * <p>重排不一样：它是检索链路上的**可选一跳**，`.env` 里 {@code RERANKER_API_*}
     * 允许为空，知识库配置里 {@code rerankEnabled} 也可以关。没配重排的机器上
     * 检索**必须照常可用**（只是少了一跳精排），所以这里是"**降级**"而不是"**报错**"。
     *
     * <p>⚠️ 但"配置来源**抛异常**"是另一回事：那代表读不到，不是真的没有。
     * 这种情况下也降级（检索不能被配置中心抖动打断），**但留 warn 日志** ——
     * 静默降级会让"重排悄悄失效了"永远没人发现。
     */
    public RerankClient rerankClient() {
        List<ModelConfigSpec> specs;
        try {
            specs = configSource.listEnabled(ModelType.RERANK);
        } catch (Exception ex) {
            log.warn("模型配置来源不可用，本次检索不重排：source={} 原因={}",
                    configSource.sourceName(), ex.getMessage());
            return NoOpRerankClient.INSTANCE;
        }
        if (specs == null || specs.isEmpty()) {
            return NoOpRerankClient.INSTANCE;
        }
        return rerankClientOf(activeSpec(ModelType.RERANK));
    }

    // ==================================================================
    // 连接测试（`T-903`）—— **唯一的"按任意规格直接构造并调用"入口**
    // ==================================================================

    /**
     * 对**任意一条**模型配置做一次连接探测（`T-903`）。
     *
     * <h3>为什么必须由注册中心提供，而不是让调用方自己拼</h3>
     * <p>「客户端只由 {@link ModelClientFactory} 构造、只经本类取得」是本项目的结构纪律
     * （`T-014`）。而连接测试要测的是**待测的那一行** —— 它可能还没启用，
     * 于是 `activeSpec(type)` 够不着它。三条路可选：① 让配置服务直接碰 `ModelClientFactory`
     * （破坏结构纪律）；② 给每种类型开一个"按规格取"的公开重载
     * （等于把「按 id/库选模型」的口子重新打开 —— 而
     * {@code ModelClientRegistryTest#embeddingIsSingleton} 正**刻意钉死**了这一点：
     * 嵌入是全局单例，注册中心不得提供任何"选一个嵌入"的方法）；③ **把探测本身收进来**。
     *
     * <p>本类选③：调用方递一个规格，拿一个结论。它**不是一个"取客户端"的口子** ——
     * 不返回任何客户端对象，调用方拿不到句柄、也就无法用它去跑业务流量。
     * 那正是它和②的区别，也是 {@code embeddingIsSingleton} 那条断言要守的东西。
     *
     * <h3>四类各自的探测方式</h3>
     * <ul>
     *   <li>{@code CHAT} / {@code VL}：发一句极短的文本。VL 模型在协议上**本来就是**
     *       OpenAI 兼容的多模态对话模型 —— 发纯文本足以探到"网络通不通、密钥对不对、
     *       模型名认不认"，而真正的图片理解链路是 `T-907`，连接测试不该依赖它；</li>
     *   <li>{@code EMBEDDING}：嵌一个短句，并**如实报出维度**（维度对不上是真问题，
     *       但它不是"连不通"，所以不算失败）；</li>
     *   <li>{@code RERANK}：给两句候选打分。⚠️ **不走 {@link #rerankClient()} 的降级** ——
     *       降级是为了"检索不被配置抖动打断"，而连接测试恰恰要**看见失败**。</li>
     * </ul>
     *
     * @return 结论；**失败不抛**，由调用方翻译成 `{ok:false, message:...}` 给人看
     */
    public ProbeOutcome probeConnection(ModelConfigSpec spec) {
        long startNanos = System.nanoTime();
        try {
            String detail = switch (spec.type()) {
                case CHAT, VL -> {
                    String content = chatClientOf(spec).prompt().user(PROBE_TEXT).call().content();
                    yield content == null || content.isBlank()
                            ? "连接正常（模型返回为空，但链路是通的）" : "连接正常";
                }
                case EMBEDDING -> {
                    float[] vector = getOrCreate(spec, () -> clientFactory.createEmbedding(spec),
                            EmbeddingModel.class).embed(PROBE_TEXT);
                    int dim = vector == null ? 0 : vector.length;
                    yield dim == MilvusConstants.DIMENSION
                            ? "连接正常 · 向量维度 " + dim
                            : "连接成功，但向量维度是 " + dim + "（期望 " + MilvusConstants.DIMENSION
                                    + "）—— 入库前请先确认嵌入模型";
                }
                case RERANK -> {
                    RerankClient client = getOrCreate(spec, () -> clientFactory.createRerank(spec),
                            RerankClient.class);
                    var hits = client.rerank(PROBE_TEXT, List.of(PROBE_TEXT, "无关的一句"), 1);
                    yield hits.isEmpty() ? "连接正常（未返回排序结果）" : "连接正常 · 返回 " + hits.size() + " 条排序";
                }
            };
            return new ProbeOutcome(true, elapsedMillis(startNanos), detail);
        } catch (RuntimeException ex) {
            return new ProbeOutcome(false, -1L, rootMessage(ex));
        }
    }

    /** 一次连接探测的结论。 */
    public record ProbeOutcome(boolean ok, long latencyMs, String detail) {
    }

    /** 探测话术。极短：它只是一次连通性探针，不是一次问答。 */
    private static final String PROBE_TEXT = "ping";

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** 取最内层原因的话术 —— 外层常见的是包装异常，打给用户看没有意义。 */
    private static String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }

    /** 按规格取重排客户端（不走降级）。**私有** —— 见 {@link #probeConnection} 的解释。 */
    private RerankClient rerankClientOf(ModelConfigSpec spec) {
        return getOrCreate(spec, () -> clientFactory.createRerank(spec), RerankClient.class);
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
