package com.knowledge.ai.pipeline.vision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VL 描述缓存的 **key 口径**（需求 `09` §4.8）—— 纯函数，不碰 Redis。
 *
 * <h3>为什么单测一个拼 key 的函数是值得的</h3>
 * <p>§4.8 用一张表逐段说明了漏掉每一段的后果，而**三段漏掉的表现各不一样、且都不报错**：
 * <ul>
 *   <li>漏 {@code md5} → 所有图共用一份描述（最离谱，但一眼能看出）；</li>
 *   <li>漏 {@code vlModelId} → 换了 VL 模型，同一张图仍返回**旧模型**的描述
 *       （而 §4.2 要的正是"换模型就重算"）；</li>
 *   <li>漏 {@code PROMPT_VERSION} → 优化了 prompt 但缓存仍是旧描述，
 *       <b>改完毫无效果且很难发现</b>（§4.8 的原话）。</li>
 * </ul>
 * 后两条都属于"悄悄失效"，所以这里用**负向锚**把它们钉住：改动任意一段，
 * key 必须变。只断言"拼出来是什么样子"是不够的 —— 那样把三段去掉两段也能通过。
 *
 * <p>⚠️ 缓存的**读写往返**（Redis 真的存进去了吗）不在本类覆盖：那要真 Redis，
 * 而本仓的硬口径是默认跑批"全替身无网络"。往返逻辑由 {@code VisionDescriberTest}
 * 的替身缓存覆盖（它验的是"什么时候该查、什么时候该写"），
 * 真实 Redis 往返属 {@code StringRedisTemplate} 的能力，不是本仓要验的东西。
 */
class VisionDescriptionCacheTest {

    private static final String MD5 = "0cc175b9c0f1b6a831c399e269772661";
    private static final String VL = "7";

    @Test
    @DisplayName("§4.8 key = md5 : vlModelId : PROMPT_VERSION，三段齐全且有序")
    void keyHasAllThreeSegments() {
        String key = VisionDescriptionCache.key(MD5, VL, VisionPrompt.PROMPT_VERSION);

        assertThat(key).isEqualTo("vision:desc:" + MD5 + ":" + VL + ":" + VisionPrompt.PROMPT_VERSION);
        // 三段都真的在（不是"拼了但少一段"）
        assertThat(key).contains(MD5).contains(":" + VL + ":").contains(VisionPrompt.PROMPT_VERSION);
    }

    @Test
    @DisplayName("§4.8 负向锚：换 prompt 版本 ⇒ key 必变（改 prompt 缓存必须失效）")
    void changingPromptVersionChangesKey() {
        String before = VisionDescriptionCache.key(MD5, VL, "v1");
        String after = VisionDescriptionCache.key(MD5, VL, "v2");

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    @DisplayName("§4.8 负向锚：换 VL 模型 ⇒ key 必变（同图不同模型要重算）")
    void changingVlModelChangesKey() {
        String a = VisionDescriptionCache.key(MD5, "7", VisionPrompt.PROMPT_VERSION);
        String b = VisionDescriptionCache.key(MD5, "8", VisionPrompt.PROMPT_VERSION);

        assertThat(b).isNotEqualTo(a);
    }

    @Test
    @DisplayName("§4.8 负向锚：换图片（md5 不同）⇒ key 必变")
    void changingImageChangesKey() {
        String a = VisionDescriptionCache.key("aaaa", VL, VisionPrompt.PROMPT_VERSION);
        String b = VisionDescriptionCache.key("bbbb", VL, VisionPrompt.PROMPT_VERSION);

        assertThat(b).isNotEqualTo(a);
    }

    @Test
    @DisplayName("§4.8 PROMPT_VERSION 与模板在同一个类里 —— 改模板必须同时改它")
    void promptVersionIsDeclaredNextToTemplate() {
        // 这条不是形式主义：版本号与模板**分开在两地**正是 §4.8 点名的那种失效来源
        // （改了模板忘了改版本 → 缓存不失效 → "改完毫无效果"）。
        // 判据只能做成"它俩在同一个类、且版本号非空"—— 机器无法判断
        // "模板改了而版本没改"，那是一条要靠人守的纪律，注释即为留痕。
        assertThat(VisionPrompt.PROMPT_VERSION).isNotBlank();
        assertThat(VisionPrompt.of(null)).isNotBlank();
    }
}
