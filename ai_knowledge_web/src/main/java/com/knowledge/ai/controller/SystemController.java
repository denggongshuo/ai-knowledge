package com.knowledge.ai.controller;

import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.dto.system.SystemDtos;
import com.knowledge.ai.service.SystemThresholdService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统级只读字典（M07 · 《10》§11，`T-706`）。
 *
 * <h3>本类目前只有一条映射，且刻意只有一条</h3>
 * <p>{@code /system} 下的写操作（模型配置、图谱开关、换嵌入模型）分属 S6 的 M09，
 * 它们全部是 🚩 高危且要审计留痕。本类**不预铺**那些映射 —— 空的占位方法会让人以为
 * "接口已经在了"，而它们真正落地时要配的权限码、审计点、二次确认一个都还没有。
 * 与 {@code AuditController} 类注释里"不声明任何写映射"是同一种克制。
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
}
