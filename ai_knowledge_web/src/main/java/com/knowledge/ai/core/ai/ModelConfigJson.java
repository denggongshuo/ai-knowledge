package com.knowledge.ai.core.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * `sys_model_config.params` 这一列 JSON 的**读写口径（唯一一处）**。
 *
 * <h3>为什么单独起一个类，而不是塞进 Service</h3>
 * <p>这一列有**两个读者**：{@code DbModelConfigSource}（把行变成 {@link ModelConfigSpec}，
 * 供模型客户端构造）与模型配置的 Service（把请求体写成行）。
 * 序列化如果各写一份，"读出来的"与"写进去的"迟早不是同一个形状 ——
 * 而那种错的表现是**参数静默失效**（读侧拿不到 `maxTokens` 就当没配）。
 *
 * <p>⚠️ 它只管 `params`。`capabilities` 的 JSON 由 DTO 层负责 ——
 * 那个形状是**接口契约**（前端 `ModelCapabilities`），不该让 {@code core} 反向依赖 DTO。
 *
 * <h3>读到坏 JSON 怎么办</h3>
 * <p>**不抛**，退回默认值并留 warn。理由：这一列是"配置"，不是"数据" ——
 * 一条写坏的 JSON 让整个检索链路起不来，代价远大于"这条模型的温度回到默认值"。
 * 但**必须留痕**：静默退回默认值会让人以为配置生效了。
 */
@Slf4j
public final class ModelConfigJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelConfigJson() {
    }

    /**
     * `params` JSON → {@link ModelConfigSpec.GenerationParams}。
     *
     * @param json 列值；{@code null} / 空 / 坏 JSON 一律返回 {@link ModelConfigSpec.GenerationParams#defaults()}
     */
    public static ModelConfigSpec.GenerationParams readParams(String json) {
        if (json == null || json.isBlank()) {
            return ModelConfigSpec.GenerationParams.defaults();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                log.warn("模型配置 params 不是 JSON 对象，按默认值处理：{}", abbreviate(json));
                return ModelConfigSpec.GenerationParams.defaults();
            }
            return new ModelConfigSpec.GenerationParams(
                    doubleOrNull(node, "temperature"),
                    intOrNull(node, "maxTokens"),
                    intOrNull(node, "timeoutSec"),
                    intOrNull(node, "retries"));
        } catch (Exception ex) {
            log.warn("模型配置 params 解析失败，按默认值处理：{} · 原因={}", abbreviate(json), ex.getMessage());
            return ModelConfigSpec.GenerationParams.defaults();
        }
    }

    /**
     * {@link ModelConfigSpec.GenerationParams} → `params` JSON。
     *
     * <p>全 {@code null} 时返回 {@code null} 而不是 {@code "{}"} —— 与
     * `changes_json` 无变更写 `NULL` 不写 `[]` 同一条口径：
     * "没配"与"配成了空对象"在读侧要走同一条分支，那就只留一种表示。
     */
    public static String writeParams(ModelConfigSpec.GenerationParams params) {
        if (params == null) {
            return null;
        }
        try {
            var node = MAPPER.createObjectNode();
            if (params.temperature() != null) {
                node.put("temperature", params.temperature());
            }
            if (params.maxTokens() != null) {
                node.put("maxTokens", params.maxTokens());
            }
            if (params.timeoutSec() != null) {
                node.put("timeoutSec", params.timeoutSec());
            }
            if (params.retries() != null) {
                node.put("retries", params.retries());
            }
            return node.isEmpty() ? null : MAPPER.writeValueAsString(node);
        } catch (Exception ex) {
            log.error("模型配置 params 序列化失败：{}", ex.getMessage(), ex);
            return null;
        }
    }

    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.doubleValue();
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? null : value.intValue();
    }

    /** 日志里别把整段 JSON 打出来（它可能很长），只留前 80 字符。 */
    private static String abbreviate(String raw) {
        return raw.length() <= 80 ? raw : raw.substring(0, 80) + "…";
    }
}
