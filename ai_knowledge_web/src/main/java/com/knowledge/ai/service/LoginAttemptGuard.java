package com.knowledge.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 登录失败风控（T-105 · 门禁 G1-5「暴力破解防护」）。
 *
 * <p>两条独立阈值，对应两种不同的拦截语义：
 * <table border="1">
 *   <caption>阈值</caption>
 *   <tr><th>连续失败次数</th><th>动作</th><th>为什么</th></tr>
 *   <tr><td>≥ {@value #CAPTCHA_AT}</td>
 *       <td>该次登录**必须**带图形验证码，否则拒绝（{@code CAPTCHA_REQUIRED}）</td>
 *       <td>挡住脚本；对真人几乎无感（前端在第一次失败后就已经开始显示验证码输入框，
 *           见 {@code LoginView.vue} 的 {@code needCaptcha}）</td></tr>
 *   <tr><td>≥ {@value #LOCK_AT}</td>
 *       <td>锁定 {@value #LOCK_MINUTES} 分钟（{@code LOGIN_LOCKED}）</td>
 *       <td>连续 5 次错密码，正常用户几乎不会发生；此时继续放行只是给撞库留时间窗</td></tr>
 * </table>
 *
 * <p><b>为什么计数的键是"账号"而不是"IP"</b>：本项目的攻击面是**撞库**
 * （拿一批真实账号试常见弱密码），而不是单 IP 爆破。按账号计数能精确命中撞库特征；
 * 按 IP 计数则会误伤 NAT 后的整个办公网（一个部门共用出口 IP）。
 * IP 维度的限制放在发码限流（G1-6）那里 —— 那是另一类攻击（短信轰炸）。
 *
 * <p><b>为什么锁定用独立的键而不是复用计数键</b>：锁定到期后计数必须**归零**，
 * 否则第 6 次失败会立刻再次锁定，用户永远出不来。用两个键可以让锁定键
 * 自然过期，而计数键在锁定期间不再被读取。
 *
 * <p><b>Redis 不可用时的取向：放行</b>。与 {@code TokenBlacklist} 同一取舍 ——
 * 风控是"提高攻击成本"的设施，不该成为"正常用户登录不了"的单点故障。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptGuard {

    private static final String COUNT_KEY = "auth:fail:count:";
    private static final String LOCK_KEY = "auth:fail:lock:";

    /** 达到该失败次数后，登录必须携带图形验证码。 */
    public static final int CAPTCHA_AT = 3;

    /** 达到该失败次数后锁定账号。 */
    public static final int LOCK_AT = 5;

    /** 锁定与计数窗口时长（分钟）。 */
    public static final int LOCK_MINUTES = 10;

    private static final Duration WINDOW = Duration.ofMinutes(LOCK_MINUTES);

    private final StringRedisTemplate redis;

    /** 账号当前是否处于锁定期。 */
    public boolean isLocked(String account) {
        if (account == null || account.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(LOCK_KEY + account));
        } catch (RuntimeException ex) {
            log.warn("登录锁定查询失败（按未锁定处理）：err={}", ex.getMessage());
            return false;
        }
    }

    /** 是否已要求出示图形验证码。 */
    public boolean captchaRequired(String account) {
        return failureCount(account) >= CAPTCHA_AT;
    }

    /** 当前连续失败次数。 */
    public int failureCount(String account) {
        if (account == null || account.isBlank()) {
            return 0;
        }
        try {
            String raw = redis.opsForValue().get(COUNT_KEY + account);
            return raw == null ? 0 : Integer.parseInt(raw);
        } catch (RuntimeException ex) {
            // 含 NumberFormatException（键被人工改成非数字）：一律按 0 计，
            // 宁可少拦一次，也不要因为一个脏键让某个账号永远登录不了
            return 0;
        }
    }

    /**
     * 记一次失败。
     *
     * @return 累计失败次数；达到 {@link #LOCK_AT} 时同时打上锁定键
     */
    public int recordFailure(String account) {
        if (account == null || account.isBlank()) {
            return 0;
        }
        try {
            String key = COUNT_KEY + account;
            Long count = redis.opsForValue().increment(key);
            // 首次失败才设 TTL：让窗口是"从第一次失败起 10 分钟"，
            // 而不是"每次失败都续期"（后者会让攻击者用慢速尝试永久锁死某个账号）
            if (count != null && count == 1L) {
                redis.expire(key, WINDOW);
            }
            int failures = count == null ? 0 : count.intValue();
            if (failures >= LOCK_AT) {
                redis.opsForValue().set(LOCK_KEY + account, "1", WINDOW);
                log.warn("账号 {} 连续失败 {} 次，已锁定 {} 分钟", account, failures, LOCK_MINUTES);
            }
            return failures;
        } catch (RuntimeException ex) {
            log.warn("登录失败计数写入失败：err={}", ex.getMessage());
            return 0;
        }
    }

    /** 登录成功后清空计数与锁定（不 clear 的话，用户改对密码后仍会看到验证码输入框）。 */
    public void clear(String account) {
        if (account == null || account.isBlank()) {
            return;
        }
        try {
            redis.delete(java.util.List.of(COUNT_KEY + account, LOCK_KEY + account));
        } catch (RuntimeException ex) {
            log.warn("登录失败计数清理失败：err={}", ex.getMessage());
        }
    }

    /** 锁定期剩余秒数；未锁定返回 0。 */
    public long lockRemainingSeconds(String account) {
        if (account == null || account.isBlank()) {
            return 0;
        }
        try {
            Long ttl = redis.getExpire(LOCK_KEY + account);
            return ttl == null || ttl < 0 ? 0 : ttl;
        } catch (RuntimeException ex) {
            return 0;
        }
    }
}
