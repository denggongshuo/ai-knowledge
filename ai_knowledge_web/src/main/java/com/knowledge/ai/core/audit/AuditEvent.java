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

    /**
     * **系统动作**的操作人取值（`S4-E2` · `O6` 判据 4）。
     *
     * <p>由 {@code AuditAspect} 在「既没有登录态、也不在 Web 请求中」时写入 ——
     * 也就是后台 worker（解析 / 索引 / 打包）自己产生的那些记录。
     *
     * <p>为什么必须是**一个明确的值**而不是 {@code null} 或沿用 {@code anonymous}：
     * 审计页要能一眼分清「系统自动跑完的解析」与「某个没登录的人干的事」。
     * `O6` 判据 4 的原话是「**不产生假记录**」—— 把系统动作写成匿名用户就是假记录。
     *
     * <p>⚠️ 与 {@code anonymous} **不是一回事**，别合并：后者是"真的有人匿名为之"。
     * 取值用英文小写 `system` 与 `sys_user.username` 的命名习惯一致（账号名是英文）。
     */
    public static final String SYSTEM_OPERATOR = "system";

    /** 系统动作的展示名（前端审计页「操作人」列展示这个）。 */
    public static final String SYSTEM_OPERATOR_NAME = "系统";

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
