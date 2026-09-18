package com.knowledge.ai.pipeline.vision;

/**
 * 一次 VL 调用（`T-907`）—— **能力接口**，生产实现在 {@link RegistryVisionCaller}。
 *
 * <h3>为什么照 {@code ChatCaller} / {@code RegistryChatCaller} 的形状再开一个</h3>
 * <p>`T-014` 的验收要点原话：「任何模块要调模型，都经 {@code ModelClientRegistry} 取客户端，
 * 不得自己 {@code new}」。把"唯一碰注册中心的地方"收成一个组件之后，
 * {@link VisionDescriber} 就不再持有注册中心、也就不可能绕过它 —— 依赖关系本身成了
 * 那条纪律的保证。同时它让"一次调用两产出""缓存 key 三段""降级矩阵"这几条判据
 * **可以在全替身下断言**（本仓硬口径：`mvn test` 全替身无网络）。
 *
 * <h3>为什么返回 {@code String} 而不是某个结构化结果</h3>
 * <p>因为**需求 §4.4 明确不要 JSON**：「很多 VL 模型会输出带 markdown 代码块或
 * 前后解释文字的 JSON，解析失败会**直接打断整条链路**」。所以这一层的契约就是
 * 「给我一段原始文本」，切分是 {@link VisionDescriber} 的事 ——
 * 把切分放进实现方，替身就得复刻切分逻辑，那条判据就测不到真东西了。
 */
public interface VisionCaller {

    /**
     * 调一次 VL。
     *
     * @param prompt      提示词文本（来自 {@link VisionPrompt#of}）
     * @param image       压缩后的图片字节。{@code null} = 调用方没有字节，只能走 URL 通路
     * @param presignedUrl 预签名 URL（10 分钟 TTL）。{@code null} = 拿不到 URL，只能走字节通路
     * @param mime        原始 MIME（用于挑 {@code Media} 的类型）
     * @return 模型返回的**原始文本**（未切分）+ 它自报的 token 用量
     * @throws RuntimeException 调用失败 / 未配置 —— 由调用方转成降级，**不往外冒**
     */
    VisionRaw call(String prompt, byte[] image, String presignedUrl, String mime);

    /**
     * 本次调用实际使用的 VL 模型标识 —— **"这条描述是谁产的"**。
     *
     * <p>与 {@code EmbeddingClient#modelRef()} 同一个用途与同一条纪律：
     * 描述要落 {@code vl_model_id} / {@code vl_model_name} 两列，
     * 而 §4.2 要求「换 VL 模型后同一张图的描述应当重算」——那就必须先认得出来。
     *
     * <p>⚠️ 实现方**不要**把它缓存成字段：`T-908` 换模型之后写进来的描述要能被区分出来。
     */
    default VisionModelRef modelRef() {
        return VisionModelRef.NONE;
    }

    /**
     * 一次 VL 调用的原始产出。
     *
     * <h3>为什么要把 token 用量带出来（而不是只返回文本）</h3>
     * <p>需求 `09` §1.13 的用量统计要「按模型拆分（**含 VL 图片描述单列**）」，
     * 而 `T-900` 阶段 `stats.monthTokensWan` 只能覆盖对话链路（`#169`）。
     * 如果这一层只吐文本，VL 的 tokens 就**永远拿不到** —— 上层再想补也补不回来。
     * 所以用量必须在这里就带出来，哪怕多数调用方（描述器）只关心文本。
     *
     * <p>⚠️ token 可空：模型没回报时是 {@code null}，**不是 0** ——
     * 「没回报」与「用了 0 个 token」是两件事（同 {@code ChatDtos.TokenUsage} 的取舍）。
     */
    record VisionRaw(String text, Integer promptTokens, Integer completionTokens) {

        /** 只要文本的便捷构造（用量未知）。 */
        public static VisionRaw of(String text) {
            return new VisionRaw(text, null, null);
        }

        /** token 合计；两者都没回报时为 {@code null}。 */
        public Integer totalTokens() {
            if (promptTokens == null && completionTokens == null) {
                return null;
            }
            return (promptTokens == null ? 0 : promptTokens) + (completionTokens == null ? 0 : completionTokens);
        }
    }

    /**
     * 产出描述的模型归因。
     *
     * @param id   {@code sys_model_config} 主键的字符串形式；未接入 DB 时为空串
     * @param name 模型名（如 {@code Qwen/Qwen3-VL-8B-Instruct}）；未配置时为空串
     */
    record VisionModelRef(String id, String name) {

        /** 没有可归因的模型（未配置 / 取不到）。 */
        public static final VisionModelRef NONE = new VisionModelRef("", "");

        public boolean present() {
            return !name.isBlank();
        }
    }
}
