package com.knowledge.ai.pipeline.graph;

import com.knowledge.ai.core.setting.SystemSettingService;

/**
 * 开关的**固定值替身**（`T-C03` 起 {@link GraphFeatureToggle} 的契约是"每次问一次设置"）。
 *
 * <h3>为什么需要一个替身</h3>
 * <p>改造成 `sys_setting` 驱动之后，纯单测（{@link GraphEntityLinkerTest}）要验的仍然是
 * "**开关关 ⇒ 一个驱动都不取**"这类零开销断言，而这件事与"值存在哪儿"无关。
 * 让它去真库读一行，等于把一条纯函数判据变成集成判据 —— 慢，且失败时看不出是
 * 匹配逻辑错了还是那一行没种上。
 *
 * <p>与同包的 {@link ProbeDriverProvider} 同一条做法：**把承诺变成可断言的事实**，
 * 而不是注释里的一句话。
 */
class ProbeFeatureToggle extends GraphFeatureToggle {

    private final boolean on;

    /** @param on 固定返回的开关值 */
    ProbeFeatureToggle(boolean on) {
        // 父类的 SystemSettingService 只在"没被覆写"时才会用到；本替身覆写了 enabled()，
        // 那个依赖永远不会被触碰
        super(new SystemSettingService());
        this.on = on;
    }

    @Override
    public boolean enabled() {
        return on;
    }
}
