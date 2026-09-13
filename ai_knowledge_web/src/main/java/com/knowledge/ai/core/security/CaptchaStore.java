package com.knowledge.ai.core.security;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 图形验证码（T-103）。
 *
 * <p><b>存 Redis 而不是 HttpSession</b>：本项目是无状态鉴权（{@code SessionCreationPolicy.STATELESS}），
 * 引入 Session 只为放一个验证码，会把"无状态"这条简化破坏掉 —— 之后所有
 * 水平扩展、负载均衡都要跟着考虑粘性会话。存 Redis 是同一个思路的延续。
 *
 * <p><b>为什么要 {@code captchaId} 而不是直接用 {@code account} 当键</b>：
 * 同一个账号可能开着多个登录页，用账号当键会互相覆盖；用随机 id 则每个页面一张，
 * 互不干扰，也让攻击者无法靠"猜到键"来预置验证码。
 *
 * <p><b>一次性消费</b>：校验采用「读出即删」——无论对错都删。
 * 这样攻击者无法用同一个 captchaId 反复试字符（否则 4 位字符最多试 10^4 次就能过）。
 * 代价是用户输错一次要换一张图，这正是所有图形验证码的通用交互。
 *
 * <p><b>大小写不敏感</b>：hutool 的干扰线验证码偶尔会出现易混字符，
 * 严格区分大小写会显著抬高正常用户的失败率，而对暴力破解的防护增益很小
 * （真正的防护来自一次性消费 + 连续失败锁定）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CaptchaStore {

    private static final String KEY_PREFIX = "auth:captcha:";

    /** 有效期：2 分钟。够用户输入，又不至于让验证码长期可复用。 */
    private static final Duration TTL = Duration.ofMinutes(2);

    /** 图片宽高与字符数。4 位字符 + 干扰线是本项目原型（`01-登录.html`）的既定形态。 */
    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    private static final int CODE_COUNT = 4;
    private static final int LINE_COUNT = 20;

    private final StringRedisTemplate redis;

    /** 生成结果：id 给前端回传，base64 图片直接给 {@code <img src>}。 */
    public record Captcha(String captchaId, String imageBase64, String code) {
    }

    /** 生成一张新验证码并入库。 */
    public Captcha generate() {
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(WIDTH, HEIGHT, CODE_COUNT, LINE_COUNT);
        String captchaId = JwtService.newTokenId();
        String code = captcha.getCode();
        redis.opsForValue().set(KEY_PREFIX + captchaId, code, TTL);
        // data:image/png;base64,... —— 前端直接塞进 <img src>，不需要额外的图片接口
        return new Captcha(captchaId, captcha.getImageBase64Data(), code);
    }

    /**
     * 校验并**立即删除**验证码。
     *
     * @return {@code true} 表示校验通过
     */
    public boolean verify(String captchaId, String input) {
        if (captchaId == null || captchaId.isBlank() || input == null || input.isBlank()) {
            return false;
        }
        String key = KEY_PREFIX + captchaId;
        String expected = redis.opsForValue().get(key);
        // 读出即删：不给"同一张图反复试"的机会
        redis.delete(key);
        if (expected == null) {
            log.debug("验证码不存在或已过期：captchaId={}", captchaId);
            return false;
        }
        boolean ok = expected.equalsIgnoreCase(input.trim());
        if (!ok) {
            log.debug("验证码不匹配：captchaId={}", captchaId);
        }
        return ok;
    }

    /** 主动失效（如发码成功后作废本次图形验证码）。 */
    public void invalidate(String captchaId) {
        if (captchaId != null && !captchaId.isBlank()) {
            redis.delete(KEY_PREFIX + captchaId);
        }
    }
}
