package com.knowledge.ai.controller;

import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.core.security.RequiresPerm;
import com.knowledge.ai.dto.system.ModelConfigDtos;
import com.knowledge.ai.service.ModelConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M09 模型配置（`T-900`~`T-903`）—— `/api/system/model/*`。
 *
 * <h3>为什么另起一个 Controller，而不是并进 {@code SystemController}</h3>
 * <p>{@code SystemController} 的类注释写死了「本类**不预铺**那些映射 —— 空的占位方法会让人以为
 * 接口已经在了」。现在它们真的来了：六个映射、四个 🚩 高危写动作、四类模型的启用切换。
 * 并进那边会让那个只有一条映射的"只读字典"类变成大杂烩。故另起本类，
 * 而 {@code SystemController} 保持它原样的克制。
 *
 * <h3>权限码用的是**早已种子的** {@code system:model}</h3>
 * <p>{@code PermissionCode.SYSTEM_MODEL}（「模型配置」，系统管理）与 `V2` 的种子都在，
 * 但**没有任何角色被授予它** —— 也就是说它事实上是**超管专属**
 * （`SUPER_ADMIN` 的通配 `*` 通吃）。这是本批的**有意选择**而非遗漏：
 * 《09》§1.2 把重排/VL 定为「系统级基础设施」，切换它属于运维动作。
 * ⚠️ 但这条边界**至今没有被任何角色证伪过**（没有角色"有它但应该没有"或反之），
 * 与 `#153` 的 `qa:history:search` 是同一种形态，已登记 `S6-M09-A` §9。
 *
 * <h3>审计点为什么挂在这一层</h3>
 * <p>与 {@code ChatController} 的导出接口同一条理由：审计的 {@code operator} 与 {@code apiPath}
 * 取自**请求上下文**，打在 Service 上会落成空值 —— 而"谁把生产环境的对话模型换掉了"
 * 正是这四条日志唯一的价值。
 *
 * <p>四个动作全部登记为 {@code MODEL_CONFIG_CHANGE} / `HIGH`：《05》§4.3 的第 4 类，
 * 且 {@code AuditRiskPolicy} 的类注释把「四类模型的**新增、编辑、删除、启用或切换**」
 * 一并划入这一类。
 */
@Slf4j
@RestController
@RequestMapping("/api/system/model")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelConfigService modelConfigService;

    /**
     * 四类模型列表（`T-900`）。
     *
     * <p>返回 {@code {stats, chat[], embedding, rerank[], vl[]}} —— 形状见
     * {@link ModelConfigDtos.ConfigResponse}。⚠️ {@code embedding} 是**单条对象**。
     */
    @GetMapping("/config")
    @RequiresPerm("system:model")
    public ApiResponse<ModelConfigDtos.ConfigResponse> config() {
        return ApiResponse.ok(modelConfigService.list());
    }

    /**
     * 新增一条（`T-901`）—— 🚩 高危。
     *
     * <p>嵌入不可新增（全局单例，`G6-2`）。新增的行**一律未启用且未测试**：
     * 启用要单独走 {@code activate}，那里有「先测通过」的门槛（`G6-3`）。
     * 若允许在请求体里带 `isActive`，就等于绕过了那道门槛。
     */
    @PostMapping("/config")
    @RequiresPerm("system:model")
    @AuditLog(module = AuditModule.SYSTEM, action = "新增模型配置", risk = RiskLevel.HIGH,
            target = "'模型配置#' + #result.data.id")
    public ApiResponse<ModelConfigDtos.SavedResult> create(@RequestBody ModelConfigDtos.SaveRequest request) {
        Long userId = LoginUserContext.requireUserId();
        return ApiResponse.ok(modelConfigService.create(request, userId));
    }

    /**
     * 编辑一条（`T-901`）—— 🚩 高危。
     *
     * <p>改了 Base URL / 模型名 → **原测试结论失效**，返回 `needRetest = true`，
     * 前端据此把该行的「启用」按钮置灰（《09》§1.6）。
     */
    @PutMapping("/config/{id}")
    @RequiresPerm("system:model")
    @AuditLog(module = AuditModule.SYSTEM, action = "编辑模型配置", risk = RiskLevel.HIGH, target = "#id")
    public ApiResponse<ModelConfigDtos.UpdatedResult> update(@PathVariable("id") Long id,
                                                             @RequestBody ModelConfigDtos.SaveRequest request) {
        return ApiResponse.ok(modelConfigService.update(id, request));
    }

    /**
     * 删除一条（`T-901`）—— 🚩 高危。
     *
     * <p>**当前启用的不可删**：删掉它会让该类当场没有可用模型（问答/检索/解析全线报
     * "模型未配置"），而这与"删一条配置"在用户眼里是同一件事。
     */
    @DeleteMapping("/config/{id}")
    @RequiresPerm("system:model")
    @AuditLog(module = AuditModule.SYSTEM, action = "删除模型配置", risk = RiskLevel.HIGH, target = "#id")
    public ApiResponse<ModelConfigDtos.DeletedResult> delete(@PathVariable("id") Long id) {
        return ApiResponse.ok(modelConfigService.delete(id));
    }

    /**
     * 切换启用（`T-902`）—— 🚩 高危。
     *
     * <p>约束四道：嵌入直接拒 · 必须已通过连接测试 · 同类型其余自动停用 · 失效客户端缓存。
     * 响应里的 {@code needRebuildImageDocs} 告诉前端"换 VL 后需要重建含图文档"（`T-908`）。
     */
    @PostMapping("/config/{id}/activate")
    @RequiresPerm("system:model")
    @AuditLog(module = AuditModule.SYSTEM, action = "启用模型配置", risk = RiskLevel.HIGH, target = "#id")
    public ApiResponse<ModelConfigDtos.ActivateResult> activate(@PathVariable("id") Long id) {
        return ApiResponse.ok(modelConfigService.activate(id));
    }

    /**
     * 连接测试（`T-903`）—— **按待测的那一行真发一次请求**。
     *
     * <p>⚠️ 它**不记审计**：测试是只读探测，不改任何配置、不影响任何行为
     * （它只写 {@code last_test_*} 这三个结论列）。把只读动作记成 🚩 高危会让那几条
     * 真正的变更日志被淹没 —— 与"单文件浏览式下载不计入高危"是同一条取舍。
     */
    @PostMapping("/config/{id}/test")
    @RequiresPerm("system:model")
    public ApiResponse<ModelConfigDtos.TestResult> test(@PathVariable("id") Long id) {
        return ApiResponse.ok(modelConfigService.test(id));
    }
}
