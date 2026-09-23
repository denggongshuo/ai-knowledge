package com.knowledge.ai.pipeline.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体链接（`T-C01`）的判据：触发策略、最长优先、零驱动调用（`S9-M12-B` 判据 1/2/3）。
 *
 * <h3>为什么这一半不需要 Neo4j</h3>
 * <p>`link()` 的三种"不查"分支（**开关关** / **长度不足** / 词典为空）都在**取驱动之前**返回，
 * 而匹配本身是纯函数 {@link GraphEntityLinker#match}。剩下的"从图里取实体名全集"那一步
 * 由 {@code GraphNeighborReaderTest} 与 {@code GraphRetrieveFlowTest} 在真库上验。
 * ⇒ 本类用记数替身把"**零开销**"这件事变成可断言的事实，而不是注释里的承诺。
 */
class GraphEntityLinkerTest {

    private static final long TIMEOUT_MS = 200L;

    /** 提问长度刚好到触发线（6 个 token）时的样本。 */
    private static final String LONG_ENOUGH = "订单系统的A-100一致性哈希方案";

    @Test
    @DisplayName("判据1/2 · 开关关闭 ⇒ 一个驱动都不取（零开销）")
    void disabledNeverTouchesTheDriver() {
        ProbeDriverProvider provider = new ProbeDriverProvider(null);
        GraphEntityLinker linker = new GraphEntityLinker(provider, false, TIMEOUT_MS);

        GraphEntityLinker.Result result = linker.link(LONG_ENOUGH);

        assertThat(provider.calls()).as("关掉开关还去取驱动 = 零开销是假的").isZero();
        assertThat(result.entities()).isEmpty();
        assertThat(result.fallback()).as("关掉不是降级").isFalse();
    }

    @Test
    @DisplayName("判据2 · 提问长度 < 6 个 token ⇒ 静默跳过、不查 Neo4j")
    void shortQuerySkipsEntirely() {
        ProbeDriverProvider provider = new ProbeDriverProvider(null);
        GraphEntityLinker linker = new GraphEntityLinker(provider, true, TIMEOUT_MS);

        GraphEntityLinker.Result result = linker.link("A-100");

        assertThat(provider.calls()).as("< 6 个 token 就不该碰 Neo4j").isZero();
        assertThat(result.entities()).isEmpty();
        assertThat(result.millis()).as("跳过是 0 耗时").isZero();
    }

    @Test
    @DisplayName("判据2 · 长度够但没有实体命中 ⇒ 同样静默跳过")
    void noEntitySkipsEntirely() {
        List<String> dictionary = List.of("一致性哈希", "订单系统");

        assertThat(GraphEntityLinker.match("这次的容量规划与监控告警安排是什么", dictionary)).isEmpty();
    }

    @Test
    @DisplayName("判据3 · 最长优先：A-100 命中时不许被 A-1 抢先")
    void longestMatchWins() {
        List<String> dictionary = List.of("A-1", "A-100", "一致性哈希");

        List<String> matched = GraphEntityLinker.match("订单系统的A-100用了什么一致性哈希", dictionary);

        assertThat(matched).containsExactly("A-100", "一致性哈希");
        assertThat(matched).as("短的那个不许出现").doesNotContain("A-1");
    }

    @Test
    @DisplayName("判据3 · 保序：按在提问里出现的位置稳定排序")
    void matchKeepsOrderOfAppearance() {
        List<String> dictionary = List.of("监控告警", "容量规划", "迁移步骤");

        assertThat(GraphEntityLinker.match("容量规划、迁移步骤与监控告警", dictionary))
                .containsExactly("容量规划", "迁移步骤", "监控告警");
    }
}