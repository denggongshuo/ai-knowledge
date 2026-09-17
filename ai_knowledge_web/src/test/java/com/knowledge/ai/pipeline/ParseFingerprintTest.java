package com.knowledge.ai.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 解析产物版本指纹的用例（`T-O05` 步 3 · `O5` §5 判据 4 的前半）。
 *
 * <h3>本用例真正在钉的是 `O5` §4 步 3 的那句判据</h3>
 * <p>「**任何一项变了，都意味着"旧产物可能不反映当前逻辑"**」—— 所以核心断言不是
 * "四段都在"，而是**逐段改一个字符，哈希必变**。少了这一条，
 * 一个把所有内容都哈希成常量的实现照样能"四段齐全"。
 *
 * <p>纯函数用例：{@link ParseFingerprint#snapshot()} 不碰任何外部状态。
 */
class ParseFingerprintTest {

    private static final String[] KEYS = {
            ParseFingerprint.KEY_PARSER_VERSION,
            ParseFingerprint.KEY_PARSER_CONFIG_HASH,
            ParseFingerprint.KEY_GUARD_VERSION,
            ParseFingerprint.KEY_CLEANING_VERSION};

    @Test
    @DisplayName("O5-4 · 四段指纹齐全且非空")
    void snapshotCarriesAllFourParts() {
        Map<String, String> meta = ParseFingerprint.snapshot();

        assertThat(meta).containsOnlyKeys(KEYS);
        for (String key : KEYS) {
            assertThat(meta.get(key)).as("指纹的每一段都要有值：%s", key).isNotBlank();
        }
    }

    @Test
    @DisplayName("O5-4 · parserVersion 来自依赖包元数据，不是 'unknown'（否则这段指纹形同虚设）")
    void parserVersionComesFromTheDependencyMetadata() {
        String version = ParseFingerprint.snapshot().get(ParseFingerprint.KEY_PARSER_VERSION);

        // 这一条是**真断言**：如果两个依赖的 jar 都没有 Implementation-Version，
        // 这段指纹会恒为 "unknown" —— 那时它永远不会因为"升级了依赖"而变化，
        // 整个机制就只剩一半。本用例就是用来当场发现那种情况的。
        assertThat(version)
                .as("解析器版本取不到时会退化成 unknown，那样「升级依赖」这个最主要的触发条件就失灵了")
                .doesNotContain("unknown");
    }

    @Test
    @DisplayName("O5-4 · 版本号是 64 位小写十六进制，且**可复现**（同一份代码两次调用相同）")
    void versionIsStableAndWellFormed() {
        String first = ParseFingerprint.version();
        String second = ParseFingerprint.version();

        assertThat(first).matches("[0-9a-f]{64}");
        assertThat(second)
                .as("指纹必须可复现 —— 不可复现的指纹会把「哪些文档需要重解析」变成每次全量")
                .isEqualTo(first);
    }

    @Test
    @DisplayName("O5-4 · **任何一段**变了，哈希必变（这就是那条判据本身）")
    void anyChangedPartChangesTheVersion() {
        Map<String, String> base = new LinkedHashMap<>(ParseFingerprint.snapshot());
        String original = ParseFingerprint.versionOf(base);

        for (String key : KEYS) {
            Map<String, String> mutated = new LinkedHashMap<>(base);
            // 改一个字符就够 —— 判据说的是"变了"，不是"变了多少"
            mutated.put(key, base.get(key) + "!");
            assertThat(ParseFingerprint.versionOf(mutated))
                    .as("改了 %s 之后版本号没变 —— 那批旧产物会被判定为「仍是最新」，永远不会被重解析", key)
                    .isNotEqualTo(original);
        }
    }

    @Test
    @DisplayName("O5-4 · 键序不影响哈希（换一个 Map 实现也必须算出同一个值）")
    void versionDoesNotDependOnMapIterationOrder() {
        Map<String, String> snapshot = ParseFingerprint.snapshot();

        Map<String, String> shuffled = new HashMap<>(snapshot);
        assertThat(ParseFingerprint.versionOf(shuffled))
                .as("按遍历顺序拼串会让同一个内容在不同 JVM 上算出不同指纹 —— "
                        + "表现是「重启一次，全库文档都变成需要重解析」")
                .isEqualTo(ParseFingerprint.versionOf(snapshot));
    }

    @Test
    @DisplayName("O5-4 · 缺段按空串参与哈希：旧产物（没有 meta）不能与「当前版本」撞上")
    void missingPartsDoNotCollideWithTheCurrentVersion() {
        assertThat(ParseFingerprint.versionOf(Map.of()))
                .as("空 meta 的哈希不能等于当前版本，否则旧产物会被当成最新的")
                .isNotEqualTo(ParseFingerprint.version());
    }
}
