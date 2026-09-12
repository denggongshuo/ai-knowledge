package com.knowledge.ai.core.audit;

import com.knowledge.ai.core.web.WebUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 一次审计事件的采集结果（切面组装，Service 落库）。
 *
 * <p>字段名与前端 {@code AuditLog} / {@code AuditLogDetail} 逐字段对齐
 * （见《10-接口约定汇总》§14）。
 *
 * <p>做成 record 是因为它是一次性的、不可变的采集快照。
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
        String note
) {

    /** {@code result} 取值。 */
    public static final String RESULT_SUCCESS = "SUCCESS";

    public static final String RESULT_FAILED = "FAILED";

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
