package com.knowledge.ai.core.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * accessToken 黑名单（T-101）。
 *
 * <p>JWT 是**自包含**的：签发后服务端不再持有状态，因此"登出"与"改密后旧令牌立即失效"
 * 这两件事无法靠 JWT 自己完成，必须引入一份**短命的**撤销名单。
 *
 * <p><b>为什么只装 jti 而不是整条 token</b>：jti 是 32 字符随机串，token 约 1KB；
 * 存 jti 让 Redis 的内存占用缩小两个数量级。校验时从已解析的 claims 里取 jti 查表，
 * 不需要再解析一次 token。
 *
 * <p><b>TTL 为什么等于"剩余有效期"而不是固定值</b>：令牌过期后 JWT 自身就会拒收，
 * 名单里再留着它纯属浪费。按剩余时间设置 TTL，名单大小天然被"accessToken 有效期
 * （2 小时）× 登出频率"封顶，不需要任何清理任务。
 *
 * <p>本类**只做撤销登记与查询**。撤销之后的 401 由 {@link JwtAuthFilter} 统一抛
 * {@code TOKEN_INVALID}（10012），与"签名不对"共用一条响应路径 ——
 * 这样前端处理逻辑只需一份。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenBlacklist {

    /** 单个 jti 的键前缀。 */
    private static final String KEY_PREFIX = "auth:bl:";

    private final StringRedisTemplate redis;

    /**
     * 把令牌加入黑名单。
     *
     * @param jti            令牌唯一 id（JWT 的 {@code jti}）
     * @param ttlSeconds     剩余有效期（秒）。≤0 表示令牌本已过期，直接跳过
     */
    public void revoke(String jti, long ttlSeconds) {
        if (jti == null || jti.isBlank() || ttlSeconds <= 0) {
            return;
        }
        redis.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofSeconds(ttlSeconds));
        log.debug("令牌已加入黑名单：jti={} ttl={}s", jti, ttlSeconds);
    }

    /**
     * 该令牌是否已被撤销。
     *
     * <p><b>Redis 不可用时的取向：放行</b>。理由是可访问性优先 ——
     * 否则 Redis 一抖全站所有人都变成未登录，影响面远大于"某个已登出的令牌
     * 在 Redis 恢复前仍能用几分钟"。这个取舍在《11》§5.1 的令牌设计里有对应说明。
     */
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + jti));
        } catch (RuntimeException ex) {
            log.warn("黑名单查询失败（按放行处理）：jti={} err={}", jti, ex.getMessage());
            return false;
        }
    }
}
