package com.knowledge.ai.core.constant;

/**
 * 缓存相关的常量。
 *
 * <p><b>为什么缓存 key 要带 {@link #PROMPT_VERSION}</b>：
 * 检索增强（HyQ 假设问题、文档摘要、标签抽取）的结果会被缓存。
 * 一旦 prompt 改了，旧缓存就**语义失效**了 —— 但 key 不变的话缓存会继续命中，
 * 表现为"改了 prompt 没生效"。把 prompt 版本编进 key，改 prompt 时只需 bump
 * 版本号，旧缓存自然不再命中（而不是靠人工清 Redis）。
 *
 * <p>约束（《11-非功能需求与技术架构》§1.4）：**缓存本身放上游**（网关 / 应用层），
 * 本项目只负责把 key 的构造口径固定下来。
 */
public final class CacheConstants {

    /** 缓存 key 统一前缀。 */
    public static final String PREFIX = "ak:";

    /**
     * 检索增强 prompt 的版本号。
     *
     * <p>**改 `src/main/resources/prompts/` 下任何一个模板，都要把它 +1。**
     */
    public static final String PROMPT_VERSION = "v1";

    /** 检索增强结果缓存前缀：{@code ak:enhance:{promptVersion}:{md5(内容)}}。 */
    public static final String ENHANCE = PREFIX + "enhance:";

    /** HyQ（假设性问题）缓存前缀。 */
    public static final String HYQ = PREFIX + "hyq:";

    /** 文档级摘要缓存前缀。 */
    public static final String SUMMARY = PREFIX + "summary:";

    /** 图像描述缓存前缀：{@code ak:vldesc:{md5(图片字节)}} —— 与图片内容绑定，不含 prompt 版本。 */
    public static final String VL_DESC = PREFIX + "vldesc:";

    /** 令牌黑名单前缀（注销后到过期前的窗口期）。 */
    public static final String TOKEN_BLACKLIST = PREFIX + "jwt:blacklist:";

    /** refreshToken 会话前缀，改密 / 重置时按 userId 批量失效。 */
    public static final String REFRESH_SESSION = PREFIX + "jwt:refresh:";

    /** 短信验证码前缀：{@code ak:sms:{scene}:{phone}}。 */
    public static final String SMS_CODE = PREFIX + "sms:";

    private CacheConstants() {
    }
}
