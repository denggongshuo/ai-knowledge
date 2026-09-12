package com.knowledge.ai.core.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模型接入端点配置（T-009）。
 *
 * <p>绑定 {@code app.model.*}，四组与 {@link com.knowledge.ai.core.enums.ModelType}
 * 的四个值一一对应（CHAT / EMBEDDING / RERANK / VL）—— 枚举是契约，这里只是把
 * 环境变量装进去，**不新增第五种类型**。
 *
 * <p><b>为什么 chat 与 embedding 各自持有一份 base-url / api-key</b>：
 * 本项目对话走 DeepSeek、向量化走硅基流动 bge-m3，是两家供应商。
 * 把 key 做成"公共的一份"会逼着以后所有模型都挤在同一家，
 * 与"四类模型可各自选供应商"的产品设定不符。
 *
 * <p><b>与 {@code spring.ai.openai.*} 的关系</b>：{@code application.yml} 里
 * {@code spring.ai.openai.chat.base-url} 等值写成 {@code ${app.model.chat.base-url}}，
 * 即**引用**本类绑定的同一份配置。这样"环境变量 → app.model"只写一次，
 * 不会出现两处各写一份、改了一处另一处悄悄过期的情况。
 *
 * <p><b>密钥安全</b>：本类只用于装配与"是否已配置"的判断；
 * 暴露到 HTTP 或日志时一律走 {@link Endpoint#describe()}，只输出 base-url / 模型名
 * 与 key 的**有无**，绝不输出 key 本身（G0-8）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.model")
public class ModelProperties {

    private Endpoint chat = new Endpoint();

    private Endpoint embedding = new Endpoint();

    private Endpoint rerank = new Endpoint();

    private Endpoint vl = new Endpoint();

    /**
     * 单个模型端点。
     *
     * <p>没有用 record：{@code @ConfigurationProperties} 的宽松绑定在可变 Bean 上
     * 行为最直观（与 {@code MilvusProperties} 保持一致），且需要 setter 支持
     * 配置文件里只写其中一项而不动其它项。
     */
    @Data
    public static class Endpoint {

        /** OpenAI 兼容接口根地址。<b>要写全</b>（如 {@code https://api.siliconflow.cn/v1}）。 */
        private String baseUrl;

        /** 供应商 API Key。绝不外泄，日志里只打印有无。 */
        private String apiKey;

        /** 模型标识（如 {@code BAAI/bge-m3}）。 */
        private String model;

        /**
         * 三项是否齐备。
         *
         * <p>刻意不叫 {@code isConfigured()}：{@code isXxx()} 会被 Jackson 当成
         * 名为 {@code configured} 的属性序列化出去，{@code ApiResponse} 已经栽过一次
         * （见 G0-2 与 {@code ApiResponse.isSuccess()} 的 {@code @JsonIgnore}）。
         * 用 {@code hasXxx()} 天然不构成 getter。
         */
        public boolean hasCredentials() {
            return isNotBlank(baseUrl) && isNotBlank(apiKey) && isNotBlank(model);
        }

        /**
         * 脱敏摘要，用于启动日志与健康端点。
         *
         * <p>形如 {@code https://api.siliconflow.cn/v1 · BAAI/bge-m3 · key=已配置}。
         * key 只报"已配置/未配置"。
         */
        public String describe() {
            if (!isNotBlank(baseUrl)) {
                return "未配置";
            }
            return baseUrl + " · " + (isNotBlank(model) ? model : "模型名未配置")
                    + " · key=" + (isNotBlank(apiKey) ? "已配置" : "未配置");
        }

        private static boolean isNotBlank(String value) {
            return value != null && !value.isBlank();
        }
    }
}
