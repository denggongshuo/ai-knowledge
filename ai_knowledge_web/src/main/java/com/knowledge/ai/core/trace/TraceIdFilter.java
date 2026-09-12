package com.knowledge.ai.core.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * traceId 入口过滤器（T-003）。
 *
 * <p>职责：请求进来时确定 traceId → 绑定上下文 → 回写响应头 → 请求结束清理。
 * 它是**第一个**执行的过滤器（{@link Ordered#HIGHEST_PRECEDENCE}），
 * 这样后续的鉴权过滤器、审计切面、全局异常处理都能拿到同一个 traceId。
 *
 * <p>取值规则：优先用上游（网关 / Nginx / 前端）传入的 {@code X-Trace-Id}，
 * 但做长度与字符校验，防止被塞入伪造值污染日志；不合法则重新生成。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 允许的最大长度，超过视为非法。 */
    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolve(request);
        TraceContext.set(traceId);
        response.setHeader(TraceContext.HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }

    private String resolve(HttpServletRequest request) {
        String header = request.getHeader(TraceContext.HEADER);
        if (header == null) {
            return TraceContext.newTraceId();
        }
        String trimmed = header.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH || !isSafe(trimmed)) {
            return TraceContext.newTraceId();
        }
        return trimmed;
    }

    /** 只接受可见 ASCII（字母、数字、连字符、下划线），避免日志注入。 */
    private boolean isSafe(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
