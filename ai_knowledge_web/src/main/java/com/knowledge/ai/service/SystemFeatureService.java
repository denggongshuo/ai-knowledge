package com.knowledge.ai.service;

import com.knowledge.ai.core.audit.AuditContext;
import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.setting.SystemSettingService;
import com.knowledge.ai.core.setting.SystemSettings;
import com.knowledge.ai.dto.system.SystemDtos;
import com.knowledge.ai.pipeline.graph.GraphFeatureToggle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 功能开关的读写（`T-C03` · FR-GRAPH-01）。本版只有一个：图谱旁路。
 *
 * <h3>读为什么也走这里，而不让 Controller 直接问 Toggle</h3>
 * <p>因为读与写必须是**同一个口径**。`GET` 若自己读一遍设置，
 * 而 `PUT` 写的是另一个键（或用了不同的默认值），现象是
 * "改完刷新了还是老值"——而两边都不报错。让 {@link GraphFeatureToggle}
 * 成为唯一的 key→default 配对点、本类的读与写都经过它，这个错就没有存在条件。
 *
 * <h3>审计：先读旧值、再登记、最后写</h3>
 * <p>顺序是契约的一部分（`T-3C3` 在角色/用户侧的同一做法）。反过来先写再读的话，
 * 读到的就是新值，日志里会变成「图谱旁路 关→关」——**看着记了一条，实际什么都没说明**。
 * 这类错不会报错，只会在事后追溯时才发现关键那次没人记得下来。
 *
 * <h3>为什么 {@code set} 不做幂等短路</h3>
 * <p>把值设成它已经是的样子，照样写库、照样记一条审计。短路看起来"省一次写"，
 * 代价是**"谁在什么时候按过这个开关"这个事实消失了** —— 而按一次开关这个动作本身
 * 就值得留痕（哪怕值没变，也说明有人动过手）。
 */
@Service
@RequiredArgsConstructor
public class SystemFeatureService {

    private final SystemSettingService settingService;

    private final GraphFeatureToggle graphFeatureToggle;

    /** 当前开关状态（`GET /system/feature/graph`）。 */
    @Transactional(readOnly = true)
    public SystemDtos.FeatureSwitch graphSwitch() {
        return new SystemDtos.FeatureSwitch(graphFeatureToggle.enabled());
    }

    /**
     * 开关图谱旁路（`PUT /system/feature/graph`）。
     *
     * <p>调用方必须已通过 {@code system:model} 的权限校验 —— 权限在后端**必须**再校验一次，
     * 前端隐藏按钮只是体验（《10》§12）。
     *
     * <p>{@code @Transactional} 是必要的：本方法"读旧值 → 写新值"两步之间若有别的写入插进来，
     * 日志里记的旧值就不再是这次改写掉的那个值。
     *
     * @return 写入后的新状态；**回读一次**而不是回显入参 —— 回显的话，
     *         {@code updateBoolean} 影响 0 行（键不存在）时会返回一个**库其实没变**的值
     */
    @Transactional
    @AuditLog(module = AuditModule.SYSTEM, action = "开关图谱旁路", risk = RiskLevel.HIGH,
            target = "'图谱旁路'")
    public SystemDtos.FeatureSwitch setGraphSwitch(boolean enabled) {
        boolean before = graphFeatureToggle.enabled();
        // 读旧值 → 登记 → 再写。登记必须发生在写之前，见类注释
        AuditContext.change("图谱旁路", before ? "开" : "关", enabled ? "开" : "关");
        settingService.updateBoolean(SystemSettings.RETRIEVAL_GRAPH_ENABLED, enabled);
        return new SystemDtos.FeatureSwitch(graphFeatureToggle.enabled());
    }
}
