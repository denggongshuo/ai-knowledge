package com.knowledge.ai.pipeline.graph;

import com.knowledge.ai.core.setting.SystemSettingService;
import com.knowledge.ai.core.setting.SystemSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 图谱旁路开关的**唯一读取口**（`T-C03`）。
 *
 * <h3>为什么单开一个类，而不是三处各自读</h3>
 * <p>这个按键在改造前被**三处**各自用 {@code @Value} 读了一次
 * （{@code RetrievalService} / {@code GraphEntityLinker} / {@code GraphNeighborReader}），
 * 而 `PUT /system/feature/graph` 只能改其中一个来源。三处各写一遍
 * {@code settings.bool(KEY, DEFAULT)} 会立刻退化成同样的形状：**键名与默认值的配对散在几处**，
 * 改键名时漏掉一处不会有任何测试发现。这与
 * {@link com.knowledge.ai.service.SystemThresholdService} 类注释立的规矩是同一条
 * ——「key→default 的映射只存在一个地方，否则库里跑的是 A、页面上显示的是 B，双方都不报错」。
 *
 * <h3>为什么不缓存</h3>
 * <p>需求 `G9-1` 要的是"关得掉"，而缓存会把"关"变成"过一会儿才关"。
 * 代价是一次 {@code sys_setting} 的 KV 主键查询 —— 每轮检索一次，量级可忽略
 * （对比之下省下的是整条 Neo4j 旁路）。
 *
 * <h3>缺行</h3>
 * <p>落到 {@link SystemSettings#DEFAULT_GRAPH_ENABLED}（= 开）并留 {@code warn}，
 * 与 {@link SystemSettingService} 的既有口径一致：检索是问答热路径，
 * 一行配置缺失不该让整站问不出答案；但**静默**兜底比不兜底更糟。
 */
@Component
@RequiredArgsConstructor
public class GraphFeatureToggle {

    private final SystemSettingService settingService;

    /** 本次检索要不要走图谱旁路。 */
    public boolean enabled() {
        return settingService.snapshot().bool(
                SystemSettings.RETRIEVAL_GRAPH_ENABLED,
                SystemSettings.DEFAULT_GRAPH_ENABLED);
    }
}
