package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.SmsScene;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 短信验证码（T-104 · 门禁 G1-6）。
 *
 * <h3>S1 的实现口径：伪代码，不发真实短信</h3>
 * <p>2026-09-13 决定：**短信通道不接，发码走伪代码，校验值固定 {@code 000000}**。
 * 理由与边界：
 * <ul>
 *   <li><b>为什么还要有发码接口</b>：忘记密码页（{@code ForgotPasswordView.vue}）
 *       直连 {@code /auth/sms-code}，不发码则 T-108 交付不了；注册页同理。
 *       所以发码链路必须在，只是"发"这一步不做真实投递。</li>
 *   <li><b>契约不变</b>：响应仍是 {@code { sent, debugCode, scene }}（《10》§14）。
 *       {@code scene} 的全部取值见 {@code SmsScene}：{@code login / register /
 *       reset-password / bind}（{@code bind} 为 2026-09-13 补入，服务换绑手机·邮箱）。
 *       将来接入真实通道属**新增实现**，不是破坏性变更。</li>
 *   <li><b>限流照做</b>（G1-6）：同用户名 1 小时 ≤5 次、同 IP 1 小时 ≤20 次。
 *       限流的价值不依赖真实通道 —— 它保护的是"这条接口不会被当作免费资源刷"，
 *       接入真实通道后这套计数器**原样生效**，不需要重写。</li>
 *   <li><b>{@code debugCode} 恒定返回</b>：本地联调与自动化测试都需要知道码值。
 *       接入真实通道后由配置开关关掉（生产不得返回）。</li>
 * </ul>
 *
 * <p><b>为什么固定码是安全的</b>：本项目的登录主链路是账号密码；
 * 短信只服务注册与忘记密码。在开发/演示环境，固定码把"等短信"从流程里摘掉，
 * 是刻意的效率取舍。**上线前必须替换为真实通道**，这条已记入
 * `docs/进度/03-Sprint排期.md` 的 S1 范围说明。
 *
 * <p>限流键按"目标"（用户名或手机号）与 IP 分别计数：两个维度的攻击面不同 ——
 * 按目标计数防"盯住一个账号轰炸"，按 IP 计数防"用一个 IP 群发不同号码"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsCodeService {

    /** 伪代码固定校验值。 */
    public static final String FIXED_CODE = "000000";

    private static final String TARGET_KEY = "auth:sms:target:";
    private static final String IP_KEY = "auth:sms:ip:";

    /** 同目标 1 小时上限。 */
    public static final int TARGET_LIMIT_PER_HOUR = 5;

    /** 同 IP 1 小时上限。 */
    public static final int IP_LIMIT_PER_HOUR = 20;

    private static final Duration WINDOW = Duration.ofHours(1);

    /** 验证码有效期（《10》§1 与原型文案：5 分钟内有效）。 */
    private static final Duration CODE_TTL = Duration.ofMinutes(5);

    private static final String CODE_KEY = "auth:sms:code:";

    private final StringRedisTemplate redis;

    /**
     * 发送验证码（伪代码实现）。
     *
     * @param target 用户名或手机号（计数与存储的键）
     * @param scene  场景，决定验证码可被哪个接口消费
     * @param ip     请求方 IP
     * @return 恒定返回 {@link #FIXED_CODE} 作为 {@code debugCode}
     */
    public String send(String target, SmsScene scene, String ip) {
        if (target == null || target.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID);
        }
        checkRateLimit(target, ip);

        // 伪代码：不调用任何短信通道。把固定码按 scene+target 存起来，
        // 让 verify 有迹可循（也便于将来换成随机码时只改这里）
        redis.opsForValue().set(CODE_KEY + scene.name() + ":" + target, FIXED_CODE, CODE_TTL);
        log.info("【伪代码】短信验证码已生成：scene={} target={} code={}（未发送真实短信）",
                scene.name(), target, FIXED_CODE);
        return FIXED_CODE;
    }

    /**
     * 校验验证码并一次性消费。
     *
     * <p>判定顺序：先用 Redis 里存的码比对；若 Redis 里没有（例如发码记录已过期，
     * 或调用方直接拿了固定码来测），再与固定码比对。
     * 后者让"跳过发码直接注册/重置"在开发环境也能走通 —— 属刻意放宽，
     * 生产换随机码时删掉第二个分支即可。
     */
    public void verify(String target, SmsScene scene, String input) {
        if (input == null || input.isBlank()) {
            throw BizException.of(ErrorCode.SMS_CODE_INVALID);
        }
        String key = CODE_KEY + scene.name() + ":" + target;
        String stored = redis.opsForValue().get(key);
        // 一次性消费：不论对错都删，避免同一码反复试
        redis.delete(key);

        boolean ok = stored != null ? stored.equals(input.trim()) : FIXED_CODE.equals(input.trim());
        if (!ok) {
            throw BizException.of(ErrorCode.SMS_CODE_INVALID);
        }
    }

    /** 两个维度的限流检查，任一超限即拒绝（G1-6）。 */
    private void checkRateLimit(String target, String ip) {
        exceed(TARGET_KEY + target, TARGET_LIMIT_PER_HOUR, "目标");
        if (ip != null && !ip.isBlank()) {
            exceed(IP_KEY + ip, IP_LIMIT_PER_HOUR, "IP");
        }
    }

    private void exceed(String key, int limit, String dimension) {
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // 首次计数才设 TTL —— 与登录失败计数同一取舍：
                // 每次续期会让"慢慢发"也能把窗口无限延长
                redis.expire(key, WINDOW);
            }
            if (count != null && count > limit) {
                log.warn("短信验证码限流命中：dimension={} key={} count={} limit={}",
                        dimension, key, count, limit);
                throw BizException.of(ErrorCode.SMS_CODE_TOO_FREQUENT);
            }
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Redis 挂了不阻断发码（风控不是业务前置条件）
            log.warn("短信验证码限流检查失败（按放行处理）：err={}", ex.getMessage());
        }
    }
}
