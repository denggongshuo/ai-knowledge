package com.knowledge.ai.core.audit;

import com.knowledge.ai.core.web.WebUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 一次审计事件的采集结果（切面组装，Service 落库）。
 *
 * <p>字段名与前端 {@code AuditLog} / {@code AuditLogDetail} 逐字段对齐
 * （见《10-接口约定汇总》§14）。
 *
 * <p>做成 record 是因为它是一次性的、不可变的采集快照。
 *
 * @param changes       变更前后对照（T-300）。由业务侧经 {@link AuditContext} 登记、
 *                      切面 {@code drain()} 取走；**未登记时是空列表**（落库为 NULL，不是 {@code []}）
 * @param affectedUsers 受影响人数。未登记时为 {@code null} —— 与 {@code 0} 语义不同：
 *                      {@code null} = 这类动作本就没有影响面，{@code 0} = 影响面确实是 0 人
 * @param affectedRole  受影响**角色名**（T-302 查询侧的 W1 修复）。与 {@code affectedUsers}
 *                      配对登记。⚠️ 不要与 {@code roleName} 混：后者是**操作人自己的角色码**，
 *                      两者语义相反，前端详情抽屉消费的是本字段
 * @param roleName      操作人自己的角色码（逗号连接）。前端审计页**不消费**它，
 *                      保留是因为"谁在用超管身份操作"仍是有效的审计事实
 */
public record AuditEvent(
        String module,
        String action,
        RiskLevel riskLevel,
        String target,
        String operator,
        String operatorName,
        String roleName,
        String ip,
        String userAgent,
        String apiPath,
        String traceId,
        long costMs,
        String result,
        String failReason,
        Integer affectedUsers,
        List<AuditChange> changes,
        String note,
        String affectedRole
) {

    /** {@code result} 取值。 */
    public static final String RESULT_SUCCESS = "SUCCESS";

    public static final String RESULT_FAILED = "FAILED";

    public AuditEvent {
        changes = (changes == null) ? List.of() : List.copyOf(changes);
    }

    /** 从当前请求线程读取客户端信息（不在 Web 线程时返回空串，不抛异常）。 */
    public static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    /** 便捷：当前请求的客户端 IP。 */
    public static String currentIp() {
        return WebUtils.clientIp(currentRequest());
    }
}
