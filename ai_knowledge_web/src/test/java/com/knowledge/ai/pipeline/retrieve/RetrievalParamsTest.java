package com.knowledge.ai.pipeline.retrieve;

import com.knowledge.ai.core.enums.KbConfigDefaults;
import com.knowledge.ai.core.enums.RetrievalMode;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.setting.SystemSettingService;
import com.knowledge.ai.core.setting.SystemSettings;
import com.knowledge.ai.dto.kb.KbDtos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 检索参数的门禁用例（`G5-3` / `G5-4` · `I-11` · `T-703`）。
 *
 * <h3>两条判据，两种越界处置（这是本用例真正在钉的东西）</h3>
 * <ul>
 *   <li>{@code rerankTopK} 越界 → **夹取**（历史配置里存了个 20，不该让整次问答失败）；</li>
 *   <li>{@code missThreshold <= recallThreshold} → **拒绝**（判据在语义上不成立）。</li>
 * </ul>
 * <p>两条口径的连接点是：都是"配置写歪了"，但**后果的性质不同** ——
 * 一个是"这次多给几条"，一个是"未命中永远不发生"。用例把两种处置分别钉住，
 * 免得后来者为了"一致性"把它们统一成同一种。
 *
 * <p>纯函数用例：{@link SystemSettingService.Snapshot} 是可直接构造的 record。
 */
class RetrievalParamsTest {

    private static final SystemSettingService.Snapshot SEEDS = new SystemSettingService.Snapshot(Map.of(
            SystemSettings.RETRIEVAL_RECALL_THRESHOLD, "0.15",
            SystemSettings.RETRIEVAL_MISS_THRESHOLD, "0.35",
            SystemSettings.RETRIEVAL_LOW_SCORE_WARN, "0.65",
            SystemSettings.RETRIEVAL_RERANK_TOP_K, "5"));

    // ==================== G5-3 · rerankTopK ====================

    @Test
    @DisplayName("G5-3 · 库级配 3 / 5 / 15 各返回对应条数")
    void rerankTopKInRangeIsHonoured() {
        for (int topK : new int[] {3, 5, 15}) {
            RetrievalParams params = RetrievalParams.resolve(config(topK), SEEDS);
            assertThat(params.rerankTopK())
                    .as("区间内的 rerankTopK 必须原样生效（判据 3 的 3/5/15 三档）")
                    .isEqualTo(topK);
        }
    }

    @Test
    @DisplayName("G5-3 · 越界值被**夹取**（口径写死：不是拒绝）")
    void rerankTopKOutOfRangeIsClamped() {
        assertThat(RetrievalParams.resolve(config(20), SEEDS).rerankTopK())
                .as("上界 15").isEqualTo(RetrievalParams.RERANK_TOP_K_MAX);
        assertThat(RetrievalParams.resolve(config(99), SEEDS).rerankTopK()).isEqualTo(15);
        assertThat(RetrievalParams.resolve(config(1), SEEDS).rerankTopK())
                .as("下界 3").isEqualTo(RetrievalParams.RERANK_TOP_K_MIN);
        assertThat(RetrievalParams.resolve(config(0), SEEDS).rerankTopK()).isEqualTo(3);
        // 负值同样是"越界"，不是"错误"
        assertThat(RetrievalParams.resolve(config(-5), SEEDS).rerankTopK()).isEqualTo(3);
    }

    @Test
    @DisplayName("G5-3 · 每路召回条数固定 20")
    void recallTopKIsFixedAtTwenty() {
        assertThat(RetrievalParams.resolve(config(5), SEEDS).recallTopK()).isEqualTo(20);
        assertThat(RetrievalParams.FIXED_RECALL_TOP_K).isEqualTo(20);
    }

    // ==================== G5-4 · I-11 ====================

    @Test
    @DisplayName("G5-4 · 两个阈值来源不同：recallThreshold 取库级，missThreshold 取全局")
    void twoThresholdsComeFromDifferentSources() {
        // 库级召回阈值改成 0.20（全局那份仍是 0.15）—— 生效的必须是库级的
        RetrievalParams params = RetrievalParams.resolve(
                new KbDtos.RetrievalConfig("HYBRID", 0.20, 5, true, 1.2, true), SEEDS);

        assertThat(params.recallThreshold()).as("库级").isEqualTo(0.20);
        assertThat(params.missThreshold()).as("全局").isEqualTo(0.35);
        assertThat(params.lowScoreWarn()).as("全局").isEqualTo(0.65);
        assertThat(params.missThreshold()).isGreaterThan(params.recallThreshold());
    }

    @Test
    @DisplayName("G5-4 · recallThreshold=0.40（≥ 未命中线 0.35）被**拒**，理由写明「未命中无从判定」")
    void recallThresholdAboveMissLineIsRejected() {
        assertThatThrownBy(() -> RetrievalParams.resolve(
                new KbDtos.RetrievalConfig("HYBRID", 0.40, 5, true, 1.2, true), SEEDS))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未命中无从判定")
                .as("必须说清「为什么不行」，而不是只说「参数不合法」")
                .hasMessageContaining("0.4")
                .hasMessageContaining("0.35");
    }

    @Test
    @DisplayName("G5-4 · 两值相等也不行（必须**严格**大于）")
    void equalThresholdsAreAlsoRejected() {
        // 库级召回阈值恰好等于全局未命中线 0.35 —— 没有"低于召回阈值又高于未命中线"的区间
        assertThatThrownBy(() -> RetrievalParams.resolve(
                new KbDtos.RetrievalConfig("HYBRID", 0.35, 5, true, 1.2, true), SEEDS))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未命中无从判定");
    }

    @Test
    @DisplayName("G5-4 · 全局未命中线被调低到召回阈值以下时，同样被拒")
    void globalMissLineBelowConfiguredRecallIsRejected() {
        SystemSettingService.Snapshot broken = new SystemSettingService.Snapshot(Map.of(
                SystemSettings.RETRIEVAL_MISS_THRESHOLD, "0.10"));
        assertThatThrownBy(() -> RetrievalParams.resolve(config(5), broken))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未命中无从判定");
    }

    // ==================== 默认值与降级 ====================

    @Test
    @DisplayName("库配置为 null 时全部走默认值（历史行可能没有这一列）")
    void nullConfigFallsBackToDefaults() {
        RetrievalParams params = RetrievalParams.resolve(null, SEEDS);

        assertThat(params.mode()).isEqualTo(KbConfigDefaults.RETRIEVAL_MODE);
        assertThat(params.recallThreshold()).isEqualTo(SystemSettings.DEFAULT_RECALL_THRESHOLD);
        assertThat(params.rerankTopK()).isEqualTo(SystemSettings.DEFAULT_RERANK_TOP_K);
        assertThat(params.rerankEnabled()).isTrue();
        assertThat(params.tableWeight()).isEqualTo(KbConfigDefaults.TABLE_WEIGHT);
        assertThat(params.missThreshold()).isEqualTo(SystemSettings.DEFAULT_MISS_THRESHOLD);
        assertThat(params.lowScoreWarn()).isEqualTo(SystemSettings.DEFAULT_LOW_SCORE_WARN);
    }

    @Test
    @DisplayName("系统参数缺行时用兜底默认值，而不是抛（检索是热路径，一行配置缺失不该打断问答）")
    void missingSettingsFallBackToConstants() {
        RetrievalParams params = RetrievalParams.resolve(config(5), new SystemSettingService.Snapshot(Map.of()));

        assertThat(params.missThreshold()).isEqualTo(SystemSettings.DEFAULT_MISS_THRESHOLD);
        assertThat(params.lowScoreWarn()).isEqualTo(SystemSettings.DEFAULT_LOW_SCORE_WARN);
    }

    @Test
    @DisplayName("检索方式脏数据不炸：认不出来按默认 HYBRID")
    void unknownModeFallsBackToHybrid() {
        RetrievalParams params = RetrievalParams.resolve(
                new KbDtos.RetrievalConfig("NOPE", 0.15, 5, true, 1.2, true), SEEDS);

        assertThat(params.mode()).isEqualTo(KbConfigDefaults.RETRIEVAL_MODE);
        assertThat(params.usesVector()).isTrue();
        assertThat(params.usesBm25()).isTrue();
    }

    @Test
    @DisplayName("三种检索方式各自开哪几路")
    void modeDecidesChannels() {
        assertThat(RetrievalParams.resolve(mode("VECTOR"), SEEDS).usesBm25()).isFalse();
        assertThat(RetrievalParams.resolve(mode("KEYWORD"), SEEDS).usesVector()).isFalse();
        RetrievalParams hybrid = RetrievalParams.resolve(mode("HYBRID"), SEEDS);
        assertThat(hybrid.usesVector()).isTrue();
        assertThat(hybrid.usesBm25()).isTrue();
    }

    // ==================== 跨库合成 ====================

    @Test
    @DisplayName("跨库取最保守：rerankTopK 最小、recallThreshold 最大、rerankEnabled 全开才开")
    void crossKbMergeTakesTheMostConservative() {
        RetrievalParams loose = RetrievalParams.resolve(
                new KbDtos.RetrievalConfig("HYBRID", 0.10, 15, true, 1.5, true), SEEDS);
        RetrievalParams strict = RetrievalParams.resolve(
                new KbDtos.RetrievalConfig("HYBRID", 0.20, 3, false, 1.0, true), SEEDS);

        RetrievalParams merged = RetrievalParams.conservative(List.of(loose, strict));

        assertThat(merged.rerankTopK()).as("取最小").isEqualTo(3);
        assertThat(merged.recallThreshold()).as("取最大（最严）").isEqualTo(0.20);
        assertThat(merged.rerankEnabled()).as("有一个没开就不开").isFalse();
        assertThat(merged.tableWeight()).as("取最小").isEqualTo(1.0);
    }

    @Test
    @DisplayName("单库时原样返回，不做任何合成")
    void singleKbIsReturnedAsIs() {
        RetrievalParams only = RetrievalParams.resolve(
                new KbDtos.RetrievalConfig("KEYWORD", 0.15, 9, true, 1.2, true), SEEDS);

        assertThat(RetrievalParams.conservative(List.of(only))).isSameAs(only);
    }

    @Test
    @DisplayName("空集合抛出可执行的错，而不是返回一个空壳参数")
    void emptyCollectionIsRejected() {
        assertThatThrownBy(() -> RetrievalParams.conservative(List.of()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("没有可检索的知识库");
    }

    // ==================== 构造 ====================

    private static KbDtos.RetrievalConfig config(int rerankTopK) {
        return new KbDtos.RetrievalConfig(RetrievalMode.HYBRID.name(), 0.15, rerankTopK, true, 1.2, true);
    }

    private static KbDtos.RetrievalConfig mode(String mode) {
        return new KbDtos.RetrievalConfig(mode, 0.15, 5, true, 1.2, true);
    }
}
