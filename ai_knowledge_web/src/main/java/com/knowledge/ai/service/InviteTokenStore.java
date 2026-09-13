package com.knowledge.ai.service;

import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 邀请令牌（T-206 inviteMode=INVITE / T-208 重发邀请）。
 *
 * <p>与 {@code ResetTokenStore} 同一条技术路线：Redis 一次性凭证、TTL 即有效期。
 * 「读出即删」在这里尤其重要 —— 邀请链接一旦被用于设密，同一链接不能再用第二次，
 * 否则等于一个长期有效的注册后门。
 *
 * <p>接受邀请的落地端点在 S2 批次 4 与联调一起定：前端目前没有「邀请接受页」，
 * 签发侧先按契约把令牌与有效期做对（创建 → PENDING + 邀请；重发 → 新令牌）。
 */
@Service
@RequiredArgsConstructor
public class InviteTokenStore {

    private static final String KEY_PREFIX = "invite:token:";

    /** 邀请有效期默认 7 天（《05》§1.4）。 */
    public static final int DEFAULT_INVITE_DAYS = 7;

    private final StringRedisTemplate redisTemplate;

    /** 签发邀请令牌，返回明文 token（放进邀请链接的 query）。 */
    public String issue(Long userId, int days) {
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(KEY_PREFIX + token, String.valueOf(userId),
                Duration.ofDays(days <= 0 ? DEFAULT_INVITE_DAYS : days));
        return token;
    }

    /** 消费令牌：返回 userId；无效 / 过期返回 {@code null}。**读出即删**。 */
    public Long consume(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + token);
        if (value == null) {
            return null;
        }
        redisTemplate.delete(KEY_PREFIX + token);
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
