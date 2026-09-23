package com.knowledge.ai.controller;

import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.security.RequiresPerm;
import com.knowledge.ai.dto.system.SystemDtos;
import com.knowledge.ai.service.SystemFeatureService;
import com.knowledge.ai.service.SystemThresholdService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统级只读字典（M07 · 《10》§11，`T-706`）。
 *
 * <h3>本类目前只有三条映射，其中一条是写</h3>
 * <p>{@code /system} 下的写操作（模型配置、图谱开关、换嵌入模型）分属 S6 的 M09，
 * 它们全部是 🚩 高危且要审计留痕。本类**不预铺**那些映射 —— 空的占位方法会让人以为
 * "接口已经在了"，而它们真正落地时要配的权限码、审计点、二次确认一个都还没有。
 * 与 {@code AuditController} 类注释里"不声明任何写映射"是同一种克制。
 *
 * <p>⇒ `T-C03`（2026-09-23）落地的 {@code PUT /feature/graph} 是**第一条真正配齐**的写映射：
 * 它有权限码、有审计点、有策略表登记。其余两条（换嵌入模型等）仍按上面的理由不预铺。
 *
 * <h3>为什么不挂 {@code @RequiresPerm}</h3>
 * <p>「只要求登录」不是"忘了写"，是 {@link SecurityConfig} 的
 * {@code .anyRequest().authenticated()} 已经兜住了：没带 token 一律 401。
 * 与 {@code GET /kb/presets}、{@code GET /depts/tree}、{@code GET /roles} 同型 ——
 * 它们是**表单 / 展示数据源**，挂上权限码会把"能进某个页面"与"能读一份字典"绑死。
 *
 * <p>本接口返回的四个数不敏感（它们本来就印在知识库设置页上），
 * 而消费方横跨两个页面：KB 设置页要显示"全站默认值是多少"（否则用户会以为库级值
 * 就是全站值），运营分析页（S8 · M10）只读展示。
 *
 * <p>⚠️ <b>S8 引入运营分析页的权限码时，这条要重新判一次</b> ——
 * 那时若该页整体受码保护，本接口是否也要跟着收窄，得按那时的实际消费方定，
 * 不能默认"当初没挂，以后也别挂"。已登记为遗留。
 */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final SystemThresholdService systemThresholdService;

    private final SystemFeatureService systemFeatureService;

    /**
     * {@code GET /system/thresholds} —— 全局阈值（《10》§11）。
     *
     * <p>返回 {@code {recallThreshold, missThreshold, lowScoreWarn, rerankTopK}}，
     * 响应体口径见 {@link SystemDtos.Thresholds}（⚠️ 是**全站默认值**，
     * 不是任何库的库级值）。
     */
    @GetMapping("/thresholds")
    public ApiResponse<SystemDtos.Thresholds> thresholds() {
        return ApiResponse.ok(systemThresholdService.thresholds());
    }

    /**
     * {@code GET /system/feature/graph} —— 图谱旁路开关状态（`T-C03` · FR-GRAPH-01）。
     *
     * <p>**只要求登录**，与上面的 {@code /thresholds} 同一条口径（见类注释）：
     * 它是一个展示数据源，挂上权限码会把"能进某个页面"与"能读一个二值状态"绑死。
     * 写那一半才是危险动作 —— 见下。
     */
    @GetMapping("/feature/graph")
    public ApiResponse<SystemDtos.FeatureSwitch> graphSwitch() {
        return ApiResponse.ok(systemFeatureService.graphSwitch());
    }

    /**
     * {@code PUT /system/feature/graph} —— 开关图谱旁路（`T-C03` · FR-GRAPH-01）。
     *
     * <h3>为什么这一条挂了权限码，而读那一半没有</h3>
     * <p>类注释说本类"不预铺"写映射，指的是"不在接口还不存在时先占位"。
     * 这条是**真的落地**了，所以它必须配齐权限码 + 审计点（这正是当初不预铺的原因）。
     *
     * <p>权限码取 {@code system:model}：需求《05》§3.2 的矩阵与 §4.3 的高危类别
     * **都没有提到功能开关**，而系统域当下只有这一个写码（且只有超管持有）。
     * 术语义上它不完美（权限矩阵里这会显示为「模型配置」），已登记为待需求方确认的偏差。
     * 收敛上它偏严：一个能改变全站检索行为的开关，只让超管按是合理的。
     */
    @PutMapping("/feature/graph")
    @RequiresPerm("system:model")
    public ApiResponse<SystemDtos.FeatureSwitch> setGraphSwitch(
            @RequestBody SystemDtos.FeatureSwitch request) {
        return ApiResponse.ok(systemFeatureService.setGraphSwitch(request.enabled()));
    }
}
