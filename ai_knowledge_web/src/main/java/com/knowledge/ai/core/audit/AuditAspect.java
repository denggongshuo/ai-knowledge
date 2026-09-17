package com.knowledge.ai.core.audit;

import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.core.trace.TraceContext;
import com.knowledge.ai.core.web.WebUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 审计切面（T-005 · D-1）。
 *
 * <p>采集顺序是刻意设计的：
 * <pre>
 *   进入方法 → 记开始时间
 *     执行方法
 *       ├─ 正常返回 → result=SUCCESS
 *       └─ 抛异常   → result=FAILED + failReason（**失败也要记**，这是排查的第一手材料）
 *   无论成败，都落库（REQUIRES_NEW，不受业务回滚影响）
 * </pre>
 *
 * <p><b>审计失败绝不影响业务</b>：落库包在 try/catch 里，只打 error 日志。
 * 反过来（因为写日志失败而让用户的删除操作报错）是不可接受的。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    /** 方法入参序列化后进入备注时的长度上限。 */
    private static final int PARAM_SUMMARY_LIMIT = 1000;

    private static final ParameterNameDiscoverer PARAM_NAMES = new DefaultParameterNameDiscoverer();

    private static final ExpressionParser SPEL = new SpelExpressionParser();

    private final AuditLogService auditLogService;

    @Pointcut("@annotation(com.knowledge.ai.core.audit.AuditLog)")
    public void auditPointcut() {
        // 切点由注解声明，方法体空
    }

    @Around("auditPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        AuditLog annotation = method.getAnnotation(AuditLog.class);
        if (annotation == null) {
            return joinPoint.proceed();
        }

        long startNanos = System.nanoTime();
        String result = AuditEvent.RESULT_SUCCESS;
        String failReason = null;
        // `S4-E2`：接住返回值，供 target 里的 `#result` 用（见 `resolveTarget`）。
        // 必须在这里接 —— 切面是 `@Around`，`finally` 里已经拿不到 `proceed()` 的返回了。
        Object returnValue = null;
        try {
            returnValue = joinPoint.proceed();
            return returnValue;
        } catch (Throwable throwable) {
            result = AuditEvent.RESULT_FAILED;
            failReason = summarize(throwable);
            throw throwable;
        } finally {
            long costMs = (System.nanoTime() - startNanos) / 1_000_000L;
            try {
                auditLogService.record(build(joinPoint, method, annotation, returnValue,
                        result, failReason, costMs));
            } catch (Exception persistError) {
                // 审计落库失败不允许影响业务，只留日志
                log.error("审计日志写入失败 module={} action={}", annotation.module(), annotation.action(), persistError);
            }
        }
    }

    private AuditEvent build(ProceedingJoinPoint joinPoint,
                             Method method,
                             AuditLog annotation,
                             Object returnValue,
                             String result,
                             String failReason,
                             long costMs) {
        LoginUser user = LoginUserContext.get();
        HttpServletRequest request = AuditEvent.currentRequest();

        String operator;
        String operatorName;
        if (user != null) {
            operator = user.getUsername();
            operatorName = user.getRealName();
        } else if (request != null) {
            // 请求线程但没有登录态 = **真的匿名访问**（比如未登录打到一个只要求登录的接口）
            operator = ANONYMOUS_OPERATOR;
            operatorName = ANONYMOUS_NAME;
        } else {
            // `S4-E2`（`O6` 判据 4）：**没有请求、也没有登录态 = 系统动作**。
            // 这条来自解析 worker —— `T-O01` 把解析搬到后台之后，完成/失败两条记录
            // 由工作线程写，那里既没有 `LoginUserContext` 也没有 `DispatcherServlet`。
            //
            // ⚠️ 原先这两种情形都被写成 `anonymous`/`匿名`，后果是审计页上
            //    「系统自动跑完的解析」与「某个没登录的人干的事」**长得一模一样** ——
            //    而 `O6` 判据 4 明确要求前者显示为「系统」。判据的措辞是
            //    「不产生**假记录**」：说一次系统动作是"匿名用户"就是假记录。
            //
            // 为什么这条判据成立（"无请求即系统"不会误判）：所有 `@AuditLog` 方法
            // 要么挂在请求线程的 Service 上（那时 request 必非 null），要么就是后台任务。
            // 本地直接 `new` 一个 Service 调用的情形只出现在测试里 —— 那本来就该是系统身份。
            operator = AuditEvent.SYSTEM_OPERATOR;
            operatorName = AuditEvent.SYSTEM_OPERATOR_NAME;
        }
        String roleName = user == null || user.getRoleCodes().isEmpty()
                ? null
                : String.join(",", user.getRoleCodes());

        // T-300：取走业务侧登记的变更明细（"取走"而非"读取" —— drain 后线程即回到干净状态）。
        // 必须放在 finally 路径上，否则业务抛异常时这份登记会残留到池化线程的下一次请求。
        AuditContext.Captured captured = AuditContext.drain();

        return new AuditEvent(
                annotation.module(),
                annotation.action(),
                annotation.risk(),
                resolveTarget(joinPoint, method, annotation, returnValue),
                operator,
                operatorName,
                roleName,
                WebUtils.clientIp(request),
                WebUtils.userAgent(request),
                WebUtils.apiPath(request),
                TraceContext.current(),
                costMs,
                result,
                failReason,
                captured.affectedUsers(),
                captured.changes(),
                annotation.recordParams() ? summarizeArgs(joinPoint) : null,
                // T-302（W1）：受影响角色名与受影响人数**配对**，同一次登记来、同一个快照取走。
                // 与上面的 roleName（操作人自己的角色码）语义相反，别合并。
                captured.affectedRole()
        );
    }

    /** 请求线程里没有登录态时的身份（与下面的"系统"区分开，别合并成一个）。 */
    private static final String ANONYMOUS_OPERATOR = "anonymous";

    private static final String ANONYMOUS_NAME = "匿名";

    private String resolveTarget(ProceedingJoinPoint joinPoint, Method method, AuditLog annotation,
                                 Object returnValue) {
        String expression = annotation.target();
        if (expression == null || expression.isBlank()) {
            return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
        }
        try {
            EvaluationContext context = new StandardEvaluationContext();
            String[] names = PARAM_NAMES.getParameterNames(method);
            Object[] args = joinPoint.getArgs();
            if (names != null) {
                for (int i = 0; i < names.length && i < args.length; i++) {
                    context.setVariable(names[i], args[i]);
                }
            }
            // `S4-E2` 新增：`#result` = 方法返回值，**仅正常返回时存在**。
            // 动机：`DocService#upload` 的审计目标要细化到**具体文档**，而 docId 是
            // 方法**内部**生成的（`recordWriter.insert` 的返回值），方法参数里没有它 ——
            // 只有返回值带得出来（`UploadAccepted.docId`）。
            //
            // ⚠️ 异常路径上 `returnValue` 为 null，`#result.docId` 会求值失败 ——
            // 那正是下面 catch 挡的：**降级为"类名#方法名"，不让审计把业务带崩**。
            // 失败时目标退化是有意的取舍：宁可少一个精确的 target，也不能因为
            // "取不到 docId" 而让整个上传接口报错。
            context.setVariable("result", returnValue);
            Expression parsed = SPEL.parseExpression(expression);
            Object value = parsed.getValue(context);
            return value == null ? "" : String.valueOf(value);
        } catch (Exception ex) {
            // 表达式写错不应该让业务失败，降级为默认值
            log.warn("审计 target SpEL 求值失败 expression={} error={}", expression, ex.getMessage());
            return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
        }
    }

    private String summarizeArgs(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object arg = args[i];
            // 只描述类型与长度，避免把密码 / Token / API Key 原样写进长期留存的日志（I-15）
            if (arg == null) {
                sb.append("null");
            } else if (arg instanceof CharSequence cs) {
                sb.append("String(len=").append(cs.length()).append(")");
            } else {
                sb.append(arg.getClass().getSimpleName());
            }
            if (sb.length() > PARAM_SUMMARY_LIMIT) {
                sb.setLength(PARAM_SUMMARY_LIMIT);
                sb.append("...(truncated)");
                break;
            }
        }
        return sb.toString();
    }

    private String summarize(Throwable throwable) {
        String message = throwable.getMessage();
        String type = throwable.getClass().getSimpleName();
        String text = (message == null || message.isBlank()) ? type : type + ": " + message;
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
