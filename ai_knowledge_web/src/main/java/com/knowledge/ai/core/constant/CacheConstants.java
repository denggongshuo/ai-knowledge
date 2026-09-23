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
     * <p>**改任何一个增强提示词模板，都要把它 +1。**
     *
     * <p>⚠️ 2026-09-18（`S5-M08-B`）由 `v1` → `v2`：`EnhancePrompts` 的**文档级提示词**
     * 改了输出契约（新增 `chapters` —— 章节级摘要复用同一次调用，`I-10`），
     * 并新增了分片级 HyQ 提示词。虽然这些模板是**写在 Java 里的字符串**而不是
     * `resources/prompts/` 下的文件，但本版本号的用途是"旧缓存语义失效"，
     * 与模板存在哪儿无关 —— 不改它，`ak:enhance:v1:*` 的旧缓存会继续命中，
     * 而它们**没有 chapters 字段**，表现为"改了提示词却没生效"。
     *
     * <p>⚠️ 2026-09-22（`S9-M12-A`）由 `v2` → `v3`：`EnhancePrompts` 的**文档级提示词**又改了
     * 输出契约 —— `relations` 多一个 **`confidence`** 字段（`FR-GRAPH-01` 要求"关系必须带
     * confidence"，见 `docs/需求文档/07-需求-检索与问答.md:467`）。
     * 旧缓存里的关系**没有这个字段**，反序列化后一律落到 `0.0` ⇒ 检索侧 `>= 0.6` 全判否
     * ⇒ 表现为"图谱一条边都跳不出去"。不改版本号的话，这个现象**只会出现在老文档上**，
     * 是最难查的那一类。`EnhancePrompts.VERSION` 同时 +1（两者用途不同，见那边的 javadoc）。
     */
    public static final String PROMPT_VERSION = "v3";

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
