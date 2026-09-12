package com.knowledge.ai.core.security;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.aspectj.lang.JoinPoint;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

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
            log.warn("权限校验失败 user={} required={} logic={} method={}",
                    user.getUsername(), String.join(",", codes), annotation.logic(),
                    method.getDeclaringClass().getSimpleName() + "#" + method.getName());
            throw BizException.of(ErrorCode.FORBIDDEN,
                    annotation.message().isBlank() ? null : annotation.message());
        }
    }
}
