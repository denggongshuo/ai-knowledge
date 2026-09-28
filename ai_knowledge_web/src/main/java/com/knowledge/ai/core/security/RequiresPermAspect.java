package com.knowledge.ai.core.security;

import com.knowledge.ai.core.enums.PermissionCode;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link RequiresPerm} 的校验切面。
 *
 * <p>为什么用 AOP 而不是 HandlerInterceptor：注解可以打在 **Service 方法**上，
 * 不只是 Controller —— 比如"下载文档"可能被 3 个入口触发，权限校验放在
 * Service 上才是真正收口的地方。
 */
@Slf4j
@Aspect
@Component
public class RequiresPermAspect {

    @Before("@annotation(com.knowledge.ai.core.security.RequiresPerm) "
            + "|| @within(com.knowledge.ai.core.security.RequiresPerm)")
    public void check(JoinPoint joinPoint) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        RequiresPerm annotation = AnnotationUtils.findAnnotation(method, RequiresPerm.class);
        if (annotation == null) {
            // 类级注解（@within 命中）
            annotation = AnnotationUtils.findAnnotation(joinPoint.getTarget().getClass(), RequiresPerm.class);
        }
        if (annotation == null) {
            return;
        }

        // 未登录 → 10010（HTTP 401）；已登录但没权限 → 10020（HTTP 403）
        LoginUser user = LoginUserContext.require();

        String[] codes = annotation.value();
        if (codes.length == 0) {
            return;
        }

        boolean pass = annotation.logic() == PermissionLogic.AND
                ? user.hasAllPermissions(codes)
                : user.hasAnyPermission(codes);

        if (!pass) {
            // 真正缺的是哪些码：OR 失败 ⇒ 要求的码一个都没命中，故全部缺失；
            // AND 失败 ⇒ 只缺没命中的那些（已有的不报，否则排查会被引向错方向）。
            List<String> missing = annotation.logic() == PermissionLogic.AND
                    ? Arrays.stream(codes).filter(c -> !user.hasPermission(c)).toList()
                    : Arrays.asList(codes);

            log.warn("权限校验失败 user={} required={} missing={} logic={} method={}",
                    user.getUsername(), String.join(",", codes), String.join(",", missing), annotation.logic(),
                    method.getDeclaringClass().getSimpleName() + "#" + method.getName());

            throw BizException.of(ErrorCode.FORBIDDEN,
                    annotation.message().isBlank() ? describeMissing(missing) : annotation.message());
        }
    }

    /**
     * 把缺失权限码拼成人能读的提示（`T-D03` / 门禁 `G9-9`）。
     *
     * <p><b>为什么放 message 而不是新开响应字段</b>：{@code ApiResponse} 类注释明确守着
     * “恰好四字段”（{@code code/message/data/traceId}）契约，新开顶层字段要连带改前端；
     * 门禁只要求“能拿到缺少的权限码”，message 已足。用户 2026-09-28 裁定取此档。
     *
     * <p>形如 {@code 无操作权限：缺 文档删除（doc:delete）}。未知码退化为裸 code ——
     * 注释里写死的码不该让线上报错，但也不静默吞掉。
     */
    private String describeMissing(List<String> missing) {
        if (missing.isEmpty()) {
            return ErrorCode.FORBIDDEN.getMessage();
        }
        String detail = missing.stream()
                .map(code -> {
                    PermissionCode known = PermissionCode.fromCode(code);
                    return known == null ? code : known.getLabel() + "（" + code + "）";
                })
                .collect(Collectors.joining("、"));
        return ErrorCode.FORBIDDEN.getMessage() + "：缺 " + detail;
    }
}
