package com.knowledge.ai.pipeline.vision;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * VL 描述缓存（需求 `09` FR-SYS-04 §4.8）。
 *
 * <h3>key = {@code md5(图片) : vlModelId : PROMPT_VERSION} —— 三段缺一不可</h3>
 * <table>
 *   <tr><th>段</th><th>漏掉它的后果</th></tr>
 *   <tr><td>{@code md5}</td><td>所有图共用一份描述（最离谱的那种）</td></tr>
 *   <tr><td>{@code vlModelId}</td><td>换了 VL 模型，同一张图仍返回**旧模型**的描述 ——
 *       而 §4.2 要求「描述按 md5 + vlModelId 长期缓存」，正是为了换模型后能重算</td></tr>
 *   <tr><td>{@code PROMPT_VERSION}</td><td>优化了 prompt 但缓存仍是旧描述，
 *       <b>改完毫无效果且很难发现</b>（§4.8 的原话）</td></tr>
 * </table>
 *
 * <h3>为什么是两个 Redis key 而不是一个装两段的编码</h3>
 * <p>要缓存两个值（{@code desc} 与 {@code searchSummary}），而 {@code desc} 是**任意
 * 多行 Markdown** —— 用一个分隔符拼起来，只要描述里出现那个分隔符就会错切，
 * 而错切的表现是"检索摘要混进了正文"，不报错、只是慢慢变差。
 * 两个 key（正文用主 key、摘要用 {@code :kw} 后缀）没有编码问题，
 * 且用 {@code multiGet} 读时仍是**一次往返**。
 * 代价是多一个 key 的 TTL 要一起维护 —— 见 {@link #put} 里的同批次写入。
 *
 * <h3>缓存故障 = 未命中，不是失败</h3>
 * <p>它整体是**优化**：Redis 挂了、序列化出问题、值被人手工删了，正确答案都是
 * "重新调一次 VL"，而不是"这次问答失败"。所以 {@link #get} 出错返回空、
 * {@link #put} 出错只留 warn —— 与 {@link ImageCompressor} 同一条取舍。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisionDescriptionCache {

    /** key 前缀。独立前缀让"这一族 key 是什么"在 Redis 里一眼可辨（排查时省一次猜）。 */
    private static final String PREFIX = "vision:desc:";

    /** 检索摘要的 key 后缀（同一个逻辑条目拆成两个物理 key，理由见类注释）。 */
    private static final String SUMMARY_SUFFIX = ":kw";

    /**
     * TTL：**30 天**（§4.8 的建议值）。
     *
     * <p>取"建议值"而不是自己定一个：描述是**幂等**的（同图同模型同 prompt 必得同结果），
     * 重算的代价是一次 VL 调用。30 天意味着"一个季度内重复问同一张图不会重复花钱"。
     */
    static final Duration TTL = Duration.ofDays(30);

    private final StringRedisTemplate redisTemplate;

    /**
     * 查缓存。
     *
     * @param md5       图片 md5（**服务端按字节算的**，见 {@code ChatAttachmentStorage}）
     * @param vlModelId VL 模型配置 id
     * @return 命中时返回结果；未命中 / 缓存不可用时返回 {@code null}（调用方照常调 VL）
     */
    public VisionResult get(String md5, String vlModelId) {
        if (isBlank(md5) || isBlank(vlModelId)) {
            // md5 为空（服务端算 md5 失败过）时**不缓存也不查** —— 用一个空串当 key 的
            // 后果是"所有算不出 md5 的图共用一份描述"，那比不缓存坏得多
            return null;
        }
        try {
            String key = key(md5, vlModelId, VisionPrompt.PROMPT_VERSION);
            List<String> values = redisTemplate.opsForValue()
                    .multiGet(List.of(key, key + SUMMARY_SUFFIX));
            if (values == null || values.size() < 2 || values.get(0) == null) {
                return null;
            }
            String desc = values.get(0);
            String summary = values.get(1) == null ? desc : values.get(1);
            // 命中缓存的必然是**成功**的描述（降级产出不写缓存，见 put）⇒ degraded = false
            return new VisionResult(desc, summary, null, false, VisionPrompt.PROMPT_VERSION);
        } catch (RuntimeException ex) {
            log.warn("读取 VL 描述缓存失败（按未命中处理）：md5={} 原因={}", md5, ex.getMessage());
            return null;
        }
    }

    /**
     * 写缓存。
     *
     * <p>⚠️ **降级产出不写缓存**（{@link VisionResult#hasDescription()} 为假时直接返回）：
     * 降级是"这次 VL 没成功"，把它缓存 30 天等于**把一次偶发故障固化成长期状态**
     * —— 用户点了「重试理解图片」，结果命中的还是那份空描述。与 §4.10 第 3 行
     * "给重试入口"的意图直接冲突。
     */
    public void put(String md5, String vlModelId, VisionResult result) {
        if (isBlank(md5) || isBlank(vlModelId) || result == null || !result.hasDescription()) {
            return;
        }
        try {
            String key = key(md5, vlModelId, result.promptVersion());
            redisTemplate.opsForValue().set(key, result.desc(), TTL);
            redisTemplate.opsForValue().set(key + SUMMARY_SUFFIX, result.searchSummary(), TTL);
        } catch (RuntimeException ex) {
            log.warn("写入 VL 描述缓存失败（不影响本次结果）：md5={} 原因={}", md5, ex.getMessage());
        }
    }

    /**
     * 缓存 key —— **唯一拼装处**。
     *
     * <p>读写两侧都调它，不各自拼字符串：两处拼法一旦漂移，表现是
     * "写进去了却永远读不到"（缓存命中率恒 0、每次都真调 VL），
     * 而它不报错、只是**慢慢烧钱**。
     */
    static String key(String md5, String vlModelId, String promptVersion) {
        return PREFIX + md5 + ":" + vlModelId + ":" + promptVersion;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
