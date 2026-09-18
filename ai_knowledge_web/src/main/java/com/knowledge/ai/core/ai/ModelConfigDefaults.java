package com.knowledge.ai.core.ai;

/**
 * 模型配置的**读侧兜底**（`sys_model_config` 的 `params` / `capabilities` 为 {@code NULL} 时用）。
 *
 * <h3>为什么兜底放在读侧，而不是"落库时写满"</h3>
 * <p>与 {@code KbConfigDefaults} 同一条取舍：JSON 列存**事实**，默认值是**投影规则**。
 * 把默认值落进库里，等于把"当时那版默认值"冻死在某一行上 —— 后来调整默认值，
 * 老行纹丝不动，于是**同一个默认值在系统里有两种表现**，而且看不出哪一行是"没配"哪一行是"配成了默认"。
 * 读侧兜底则天然统一：{@code NULL} 永远是"没配"。
 *
 * <h3>{@code capabilities} 的默认值为什么是保守值</h3>
 * <p>{@link #DEFAULT_CONTEXT_WINDOW} 取 128000（与内网既有两个对话模型的窗口一致）。
 * ⚠️ 它是**保守初值，不是实测值** —— 种子落下的四行没有能力信息来源（`.env` 里没有这类字段），
 * 只能给一组保守值，再由使用者在模型配置页按实际订正。
 * 这一点必须写进 {@code S6-A} 的遗留，否则"猜的窗口"会被当成"测出来的窗口"。
 */
public final class ModelConfigDefaults {

    private ModelConfigDefaults() {
    }

    // ---------- params ----------

    /** 生成温度默认值。知识库问答**建议 0.2~0.4，偏高容易编造**（《09》§1.5）。 */
    public static final double TEMPERATURE = 0.3;

    /** 单次生成上限。 */
    public static final int MAX_TOKENS = 2048;

    /** 单次请求超时（秒）。 */
    public static final int TIMEOUT_SEC = 60;

    /** 失败重试次数。 */
    public static final int RETRIES = 2;

    // ---------- capabilities ----------

    /**
     * 是否支持流式：默认**支持**。
     *
     * <p>默认值取"支持"而不是"不支持"是有意的：不支持流式的模型会让问答退化成
     * "转圈很久然后一次性出结果"，那是**用户立刻会报障**的形态；
     * 而反过来（默认支持但其实不支持）表现为"流式请求失败"，同样是立刻可见。
     * 两者都会被发现，故选与既有两个对话模型一致的那个。
     */
    public static final boolean STREAM = true;

    /**
     * 是否支持视觉：默认**不支持**。
     *
     * <p>这是四类里唯一"默认值会影响功能开关"的一项 —— {@code T-806} 的
     * 「直接读原图」按钮据此置灰。默认 {@code false} 是**安全的**：宁可让一个
     * 真支持视觉的模型暂时用不了该按钮（用户改一下配置即可），
     * 也不要让一个不支持视觉的模型被传原图、然后在调用时报一个难懂的错误。
     */
    public static final boolean VISION = false;

    /** 上下文窗口（tokens）。⚠️ **保守初值，未实测**，见类注释。 */
    public static final int CONTEXT_WINDOW = 128_000;

    /** 是否支持 {@code system} role：默认支持。不支持时前端提示「指令遵循可能变弱」。 */
    public static final boolean SUPPORT_SYSTEM_ROLE = true;
}
