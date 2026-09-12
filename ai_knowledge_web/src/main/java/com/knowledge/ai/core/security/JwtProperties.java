package com.knowledge.ai.core.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 相关配置。
 *
 * <p>取值来源：{@code ai_knowledge_web/.env}（gitignored）里的 {@code JWT_SECRET}，
 * 经 {@code application.yml} 中转。**不在 yml 里给默认值** —— 缺了就启动失败，
 * 好过用一个"看似能跑"的弱密钥。
 *
 * <p>有效期口径（《11-非功能需求与技术架构》§5.1）：
 * accessToken **2 小时**、refreshToken **7 天**（HttpOnly Cookie）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** HMAC-SHA256 密钥。必须 ≥ 32 字节，否则 jjwt 直接拒绝。 */
    private String secret;

    /** 签发者。 */
    private String issuer = "ai-knowledge";

    /** accessToken 有效期（分钟），默认 120 = 2 小时。 */
    private long accessTtlMinutes = 120;

    /** refreshToken 有效期（天），默认 7 天。 */
    private long refreshTtlDays = 7;

    /** 注销后令牌黑名单的兜底 TTL（秒），应 >= accessToken 有效期。 */
    private long blacklistTtlSeconds = 7200;
}
