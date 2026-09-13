package com.knowledge.ai.core.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 重置密码的一次性凭证（T-108）。
 *
 * <p><b>为什么用"短期凭证"而不是"让用户直接把新密码发过来"</b>：忘记密码是
 * 一条**匿名可达**的接口（登录前就用），它必须自己证明"这次操作的人确实是账号主人"。
 * 把证明拆成两步：
 * <ol>
 *   <li>{@code /auth/verify-reset} —— 用「用户名 + 短信验证码」换取一张
 *       {@code resetToken}（5 分钟、一次性）；</li>
 *   <li>{@code /auth/reset-password} —— 拿这张凭证换新密码。</li>
 * </ol>
 * 好处是：验证码只在第一步消费（第二步不再需要"把验证码再传一次"），
 * 且第一步的响应里不含任何可用来登录的东西 —— 泄露它只泄露一张 5 分钟的死凭证。
 *
 * <p><b>为什么存 Redis 而不是签一个 JWT</b>：凭证必须**一次性可作废**。
 * JWT 是无状态的，签出去就收不回来，只能再建一张黑名单 —— 等于把状态换个地方存。
 * 与 refreshToken 同一取舍（见 {@code RefreshTokenStore}）。
 *
 * <p><b>读出即删</b>：{@link #consume} 无论后续业务成功与否都先删键。
 * 代价是"新密码强度不足被拒后要重新验证身份"；这正是刻意的 ——
 * 允许同一张凭证反复提交，就等于允许对"密码策略"做无成本探测。
 *
 * <p><b>Redis 不可用时的取向：拒绝</b>。与 {@code TokenBlacklist}（放行）相反 ——
 * 黑名单失效的后果是"少拦一个已登出的令牌"，而本凭证失效的后果是
 * "任何人都能改任意账号的密码"。安全设施的降级方向取决于失败后果，不取决于便利性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResetTokenStore {

    private static final String KEY_PREFIX = "auth:reset:";

    /** 凭证有效期（《10》§37：5 分钟一次性）。 */
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;

    /** 签发一张重置凭证。 */
    public String issue(Long userId) {
        String token = JwtService.newTokenId() + JwtService.newTokenId();
        redis.opsForValue().set(KEY_PREFIX + token, String.valueOf(userId), TTL);
        return token;
    }

    /**
     * 消费凭证并返回其归属用户 id。
     *
     * @return 归属用户 id；凭证不存在 / 已过期 / 已使用 / Redis 不可用时返回 {@code null}
     */
    public Long consume(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String key = KEY_PREFIX + token;
            String raw = redis.opsForValue().get(key);
            // 读出即删：先删再解析，保证"同一张凭证两次提交"第二次必然失败
            redis.delete(key);
            if (raw == null) {
                log.debug("重置凭证不存在或已过期");
                return null;
            }
            return Long.valueOf(raw);
        } catch (RuntimeException ex) {
            // 含 NumberFormatException（载荷被人工改坏）与 Redis 连接异常：
            // 一律按"凭证无效"处理 —— 见类注释的降级取向
            log.warn("重置凭证消费失败（按无效处理）：err={}", ex.getMessage());
            return null;
        }
    }
}
