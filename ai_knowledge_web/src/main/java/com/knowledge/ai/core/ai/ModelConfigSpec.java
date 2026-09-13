package com.knowledge.ai.core.ai;

import com.knowledge.ai.core.enums.ModelType;

/**
 * 一条生效的模型配置（T-014）。
 *
 * <p><b>为什么要有这个类型，而不是直接用实体</b>：{@code sys_model_config} 是**存储形态**，
 * 而客户端构造只需要"端点 + 密钥 + 模型名 + 版本"这几个字段。中间加一层不可变规格，
 * 让 S6 接 DB 配置时只换 {@link ModelConfigSource} 的实现，
 * {@link ModelClientRegistry} 与业务代码一行都不用改。
 *
 * <p><b>{@code version} 是缓存失效的依据</b>（《09-需求-系统配置与通用》§FR-SYS-01）：
 * 客户端按 {@code (id + 配置版本)} 缓存，配置一变版本就变 → 新的 key 自然不命中，
 * 旧客户端随之淘汰。这比"配置变更时去猜要清哪些缓存"可靠得多，
 * 也满足需求里"改了 A 模型不能把 B 的客户端也清了"。
 *
 * @param id      模型配置标识。DB 生效时为表主键字符串；引导配置为 {@code bootstrap-{type}}
 * @param type    四类之一
 * @param baseUrl OpenAI 兼容接口根地址，**要写全**（含 {@code /v1}）
 * @param apiKey  供应商密钥。**不可进日志、不可进响应体**
 * @param model   模型标识，如 {@code BAAI/bge-m3}
 * @param version 配置版本，参与缓存 key
 * @param enabled 是否启用。列表接口只返回启用的
 */
public record ModelConfigSpec(
        String id,
        ModelType type,
        String baseUrl,
        String apiKey,
        String model,
        String version,
        boolean enabled
) {

    /** 缓存 key：{@code type|id|version}。三段都参与，任一变化即视为新客户端。 */
    public String cacheKey() {
        return type.name() + "|" + id + "|" + version;
    }

    /** 同类型的客户端缓存 key 前缀，用于「按模型失效」。 */
    public String cacheKeyPrefix() {
        return type.name() + "|" + id + "|";
    }

    /**
     * 端点是否齐备。
     *
     * <p>刻意不叫 {@code isConfigured()} —— {@code isXxx()} 会被 Jackson 当成属性序列化出去
     * （{@code ApiResponse.isSuccess()} 已经栽过一次，见 G0-2）。用 {@code hasXxx()} 天然不是 getter。
     */
    public boolean hasCredentials() {
        return isNotBlank(baseUrl) && isNotBlank(apiKey) && isNotBlank(model);
    }

    /**
     * 脱敏摘要，可安全进日志与响应体。
     *
     * <p>key 只报「已配置 / 未配置」—— 《11》§1.4 要求密钥永不外泄（G0-8）。
     */
    public String describe() {
        return type.name() + "#" + id + "(" + version + ") · " + baseUrl
                + " · " + model + " · key=" + (isNotBlank(apiKey) ? "已配置" : "未配置");
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
