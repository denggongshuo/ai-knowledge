package com.knowledge.ai.controller;

import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.security.RequiresPerm;
import com.knowledge.ai.dto.audit.AuditDtos;
import com.knowledge.ai.service.AuditExportService;
import com.knowledge.ai.service.AuditQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 审计日志查询侧（M03 · T-302 ~ T-306）。
 *
 * <h3>⚠️ 本控制器**只有四个映射，且全是读或"提交一个只读任务"**</h3>
 * <p>《05》§4.8 要求「审计日志没有任何编辑/删除入口，**接口层也没有**」。
 * 本类不声明任何 {@code PUT} / {@code PATCH} / {@code DELETE} 映射 ——
 * 这不是"忘了写"，是本批的验收要点之一（{@code AuditQueryFlowTest#noMutatingEndpoints}
 * 用反射钉住它：将来有人"顺手"加一个删除接口，用例会红）。
 *
 * <p>为什么 {@code POST /audit/export} 不算"写"：它写的是**另一个对象**
 * （MinIO 里的导出文件），对审计日志本身只读，并且这个动作**自己也要留痕**
 * （审计自审，见 {@link #export}）。
 *
 * <h3>权限</h3>
 * <p>读侧要 {@code audit:view}、导出要 {@code audit:export}（种子里的 AUDITOR
 * 与 SUPER_ADMIN 持有）。二者**不互相蕴含** —— 只有 {@code audit:view} 的账号
 * 发导出会拿到 403 + 10020，这是判据 13。
 *
 * <p>与「读侧若是表单数据源只要求登录」的例外不适用：审计页不是任何表单的数据源，
 * 审计日志本身是敏感物（含操作人、IP、变更明细），必须显式授权。
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditQueryService auditQueryService;

    private final AuditExportService auditExportService;

    /**
     * {@code GET /audit/logs} —— 审计日志列表（时间倒序 + 5 个可选筛选 + 时间窗）。
     *
     * <p>分页参数名同时收 {@code size}（前端发的）与 {@code pageSize}（《10》§368 写的）——
     * 理由见 {@code AuditQueryService} 的类注释。
     */
    @GetMapping("/logs")
    @RequiresPerm("audit:view")
    public ApiResponse<AuditDtos.LogPage> logs(
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String riskLevel,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer pageSize) {
        long effectiveSize = size != null ? size : (pageSize != null ? pageSize : 10);
        return ApiResponse.ok(auditQueryService.list(startTime, endTime, module, riskLevel,
                result, operator, keyword, page, effectiveSize));
    }

    /**
     * {@code GET /audit/logs/{id}} —— 详情。
     *
     * <p>响应体是**扁平字段**（{@code AuditDtos.LogDetail}），没有嵌套的 {@code detail}
     * 对象 —— 前端抽屉直接读 {@code detail.changes}，包一层会让页面全部字段静默变空。
     */
    @GetMapping("/logs/{id}")
    @RequiresPerm("audit:view")
    public ApiResponse<AuditDtos.LogDetail> detail(@PathVariable long id) {
        return ApiResponse.ok(auditQueryService.detail(id));
    }

    /**
     * {@code GET /audit/stats?date=} —— 四项统计（缺省 = 今天）。
     *
     * <p>{@code @DateTimeFormat} 是必需的：前端发 {@code 2026-09-14} 这种纯日期串，
     * 不注解的话 Spring 对 {@code LocalDate} 参数只认 ISO 且**要求显式声明格式**
     * （不声明时行为随配置漂移，表现为一个没人看得懂的 400）。
     */
    @GetMapping("/stats")
    @RequiresPerm("audit:view")
    public ApiResponse<AuditDtos.StatsView> stats(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(auditQueryService.stats(date));
    }

    /**
     * {@code POST /audit/export} —— 导出为 CSV（异步，🚩 审计自审）。
     *
     * <p><b>{@code module = AuditModule.AUDIT}</b>：这条日志说的是"有人在导审计日志"，
     * 它本身必须留在审计里（G3-9）。{@code risk = HIGH} 因为 §4.3② 把数据导出列为高危，
     * 而"把审计日志整体带走"是其中影响面最大的一种。
     *
     * <p><b>筛选条件原样带走</b>：页面筛了模块再点导出，导出的是筛出来的那些行。
     * 这既是用户预期，也是数据外带面的控制 —— 见 {@code AuditQueryService#exportCsv}。
     */
    @PostMapping("/export")
    @RequiresPerm("audit:export")
    @AuditLog(module = AuditModule.AUDIT, action = "导出审计日志", risk = RiskLevel.HIGH)
    public ApiResponse<AuditDtos.ExportAccepted> export(
            @RequestBody(required = false) AuditDtos.ExportQuery query) {
        AuditDtos.ExportQuery q = query == null
                ? new AuditDtos.ExportQuery(null, null, null, null, null, null, null)
                : query;
        return ApiResponse.ok(auditExportService.submit(q.startTime(), q.endTime(), q.module(),
                q.riskLevel(), q.result(), q.operator(), q.keyword()));
    }
}
