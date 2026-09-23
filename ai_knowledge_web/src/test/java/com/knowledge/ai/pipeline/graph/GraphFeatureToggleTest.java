package com.knowledge.ai.pipeline.graph;

import com.knowledge.ai.core.setting.SystemSettingService;
import com.knowledge.ai.core.setting.SystemSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GraphFeatureToggle} 的**取值口径**（`S9-M12-C` 步 11 的另一半）。
 *
 * <h3>这条判据防的是什么</h3>
 * <p>{@code T-C03} 把开关的事实源从启动期属性换成了 {@code sys_setting} 的一行
 * （`V43` 种的）。于是多出一条**新的失败模式**：那一行**没种上**时怎么办。
 * 兜底值本身不是重点，重点是**兜底选了哪一边** —— 选"关"的话，一次迁移失败
 * 会表现为「图谱悄悄停了」，而站上其他地方一切正常，没有人会去看；
 * 选"开"则退化成"功能照旧"。所以这一格要钉死。
 *
 * <h3>为什么不需要真库</h3>
 * <p>要验的是"键缺失时 {@link GraphFeatureToggle} 怎么取值"，而这件事与
 * "值存在哪儿"无关 —— 喂一个空快照就是缺行的**最直接**构造。
 * 放进真库反而更差：得靠"删掉 V43 种的那行"来制造缺行，而那是**破坏种子**
 * （批次工艺明令禁止），且会让同库跑批的其他用例跟着漂。
 *
 * <p>⚠️ 本类**不**重复验"键名对得上"：那一半由 {@code SystemFeatureFlowTest} 在真库上钉着 ——
 * 它用字面量 {@code 'retrieval.graph-bypass-enabled'} 读行、而 PUT 走本类的常量，
 * 两边一旦漂开那条用例必定转红。同一条事实不设两个来源。
 */
class GraphFeatureToggleTest {

    /** 返回值由用例指定、其余照旧的 {@link SystemSettingService} 替身（不需要 EntityManager）。 */
    private static final class FixedSettingService extends SystemSettingService {

        private final Map<String, String> values;

        private FixedSettingService(Map<String, String> values) {
            this.values = values;
        }

        @Override
        public Snapshot snapshot() {
            return new Snapshot(values);
        }
    }

    @Test
    @DisplayName("缺行（V43 没种上）⇒ 兜底为「开」，不是「关」")
    void missingRowFallsBackToEnabled() {
        GraphFeatureToggle toggle = new GraphFeatureToggle(new FixedSettingService(Map.of()));

        assertThat(toggle.enabled())
                .as("兜底若是 false，一次迁移失败就会让图谱**悄悄停掉**而无人察觉；"
                        + "选 true 则退化成「功能照旧」")
                .isTrue();
    }

    @Test
    @DisplayName("兜底常量本身必须是 true —— 上一条只是它的推论")
    void defaultConstantIsEnabled() {
        assertThat(SystemSettings.DEFAULT_GRAPH_ENABLED)
                .as("与 m05.graph.enabled（建图侧，默认 true）的取向保持一致："
                        + "「缺配置」不该等于「关功能」")
                .isTrue();
    }

    @Test
    @DisplayName("行存在时以行为准 —— 不能被兜底盖过去")
    void rowWinsOverFallback() {
        GraphFeatureToggle toggle = new GraphFeatureToggle(new FixedSettingService(
                Map.of(SystemSettings.RETRIEVAL_GRAPH_ENABLED, "false")));

        assertThat(toggle.enabled())
                .as("有行还返回兜底 ⇒ 页面上关掉的开关在检索侧根本没生效")
                .isFalse();
    }
}
