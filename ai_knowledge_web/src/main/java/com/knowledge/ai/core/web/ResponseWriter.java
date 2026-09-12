package com.knowledge.ai.core.web;

import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.trace.TraceContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 过滤器阶段直接写响应体（不经 Spring MVC 的返回值处理）。
 *
 * <p>存在的理由：Spring Security 的过滤器链在 {@code DispatcherServlet} **之前**，
 * 那里抛异常不会被 {@code @RestControllerAdvice} 接住。若不做这一步，
 * 前端收到的会是 Spring Security 的默认 HTML 错误页，
 * {@code request.ts} 解析 JSON 失败 → 报"请求失败（401）"而不是"登录已过期"。
 *
 * <p><b>为什么手写 JSON 而不是注入 ObjectMapper</b>：
 * Spring Boot 4 把默认 JSON 实现切到了 Jackson 3（{@code tools.jackson}），
 * 包名与 Jackson 2（{@code com.fasterxml.jackson}）不同，注入哪个
 * ObjectMapper 会随版本与依赖而变化。这里只需要一个 4 字段的**扁平**对象，
 * 手写反而更稳、零依赖。
 */
@Component
public class ResponseWriter {

    /** 写统一响应体的失败分支。 */
    public void writeFailure(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        writeFailure(response, errorCode, errorCode.getMessage(), errorCode.getHttpStatus());
    }

    /** 写统一响应体的失败分支（自定义 message / HTTP 状态）。 */
    public void writeFailure(HttpServletResponse response, ErrorCode errorCode, String message, int httpStatus)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        // 成功路径的 traceId 由 TraceIdFilter 写进响应头；这里也确保带上
        String traceId = TraceContext.current();
        response.setHeader(TraceContext.HEADER, traceId);
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String body = "{\"code\":" + errorCode.getCode()
                + ",\"message\":\"" + escape(message == null ? errorCode.getMessage() : message) + "\""
                + ",\"data\":null"
                + ",\"traceId\":\"" + escape(traceId) + "\"}";
        response.getWriter().write(body);
        response.getWriter().flush();
    }

    /** 最小化 JSON 字符串转义，避免注入破坏响应结构。 */
    private String escape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
