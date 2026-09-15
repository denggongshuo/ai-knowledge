package com.knowledge.ai.dto.audit;

import com.knowledge.ai.core.audit.AuditChange;

import java.util.List;

/**
 * M03（审计）查询侧的出入参（T-302 / T-303 / T-304）。
 *
 * <p><b>字段名的事实源是前端</b>（与 {@code dto/admin/AdminDtos}、{@code dto/kb/KbDtos} 同一条约定）：
 * 本包字段逐一对齐 {@code ai_knowledge_app/src/types/index.ts} 的
 * {@code AuditLog} / {@code AuditLogDetail}，以及 {@code src/mock/index.ts} 里
 * {@code /api/audit/*} 四个接口的返回体。改名前先看那两处 —— 前端是逐字段解构的，
 * 少一个字段不会报错，只会静默空白。
 *
 * <p><b>详情刻意是扁平字段</b>（{@code T-302} 的验收要点）：前端
 * {@code AuditLogView.vue} 的抽屉直接读 {@code detail.logNo} / {@code detail.traceId} /
 * {@code detail.changes}，**没有嵌套的 {@code detail} 对象**。
 * 包一层 {@code {detail: {...}}} 会让页面所有字段静默变空 —— 不报错，白屏。
 */
public final class AuditDtos {

    private AuditDtos() {
    }

    // ==================== T-302 列表 ====================

    /**
     * 列表行。
     *
     * <p>{@code time} 是**格式化后的字符串**（{@code yyyy-MM-dd HH:mm:ss}）而不是
     * ISO 时间戳：表格「时间」列宽 140px，页面直接渲染不做二次格式化
     * （mock 返回的也是这个形状）。返回 {@code LocalDateTime} 会被 Jackson
     * 序列化成数组或 ISO 串，页面显示的就不是"人看的时间"了。
     */
    public record LogRow(
            long id,
            String time,
            String operator,
            String operatorName,
            String module,
            String action,
            String riskLevel,
            String target,
            String ip,
            String result,
            String failReason
    ) {
    }

    /** 分页壳。字段名与 {@code AdminDtos.UserPage} 同形（{@code page} 是**回显**，不是总页数）。 */
    public record LogPage(List<LogRow> list, long total, long page, long pageSize) {
    }

    // ==================== T-302 详情 ====================

    /**
     * 详情（扁平）。
     *
     * <p>{@code roleName} 取自 {@code sys_audit_log.affected_role} ——
     * <b>受影响角色</b>，不是操作人自己的角色码（操作人角色在 {@code role_name} 列，
     * 本接口**不返回**，见 {@code V11} 的注释）。为 {@code null} 时前端已有守卫，
     * 会自动省略「（该角色下全部成员）」与「查看该角色当前权限」按钮。
     *
     * @param changes 变更明细；**无变更时为 {@code null} 而不是空数组**（T-300 冻结口径，
     *                前端按 {@code detail.changes && detail.changes.length} 判定）
     */
    public record LogDetail(
            long id,
            String time,
            String operator,
            String operatorName,
            String module,
            String action,
            String riskLevel,
            String target,
            String ip,
            String result,
            String failReason,
            String logNo,
            String traceId,
            String apiPath,
            Integer costMs,
            String userAgent,
            Integer affectedUsers,
            String roleName,
            String note,
            List<AuditChange> changes
    ) {
    }

    // ==================== T-303 统计 ====================

    /**
     * 四项统计（按 {@code created_date = ?} 聚合，见 {@code AuditQueryService#stats}）。
     *
     * <p>四个字段名与前端 {@code sysApi.auditStats()} 的泛型逐字一致 ——
     * 页面 {@code onMounted} 里 {@code Object.assign(stats, res)}，名字对不上就是静默留 0。
     */
    public record StatsView(long today, long highRisk, long activeUsers, long failed) {
    }

    // ==================== T-304 导出 ====================

    /**
     * 导出受理回执。与 mock 同形（{@code { taskId, status:'QUEUED', note }}），
     * 所以前端 {@code exportLogs()} 改成真调用时**解析逻辑零改动**。
     *
     * <p>{@code status} 给的是调度层真值 {@code PENDING}（不是字面量 {@code 'QUEUED'}）——
     * 理由见 {@code AuditExportService#submit} 的注释（状态词表只能有一套）。
     * 前端当前不解析这个字段，故"零改动"仍成立。
     */
    public record ExportAccepted(String taskId, String status, String note) {
    }

    /**
     * 导出请求的筛选条件（与列表**同一套参数**，缺省 = 全量）。
     *
     * <p>做成 body 而不是查询串：{@code http.post} 在前端不支持拼 query
     * （见 {@code api/request.ts} 的 {@code post}），而筛选条件有 7 个。
     * 全部可选，{@code null} / 空 / {@code "all"} 视为不筛 —— 与列表同口径。
     */
    public record ExportQuery(
            String startTime,
            String endTime,
            String module,
            String riskLevel,
            String result,
            String operator,
            String keyword
    ) {
    }
}
