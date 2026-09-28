package com.knowledge.ai.core.exception;

import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.trace.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 错误出口的 traceId 契约（门禁 {@code G9-9}，`T-D02`）。
 *
 * <p><b>为什么值得单独钉一条用例</b>：{@link GlobalExceptionHandler} 有 16 个处理器，
 * 但在本用例之前**一条针对"响应体里有没有 traceId"的测试都没有** ——
 * `G9-9` 要的是"403/404/500 都能拿到 traceId；403 能拿到缺少的权限码"，
 * 而这三条出口此前只有手工 curl 记录，**改了没人拦得住**。
 *
 * <p><b>为什么走直调 Handler 而不是发 HTTP</b>：这三条出口是纯函数式逻辑
 * （收异常 → 组装 {@link ApiResponse}），没有"必须经 HTTP 全链路才成立"的语义。
 * 对比之下审计打点必须走真 HTTP（要请求上下文里的 operator）—— 那条不适用本用例的写法。
 *
 * <p>与之互补的**真 HTTP 取证**在 `2026-09-28-S9-M13-A.md` §8：403 与 404 已用 curl 实测过，
 * 本用例把它们固化成可回归的断言。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static final String FIXED_TRACE = "abcdef1234567890";

    @AfterEach
    void clearTrace() {
        // ThreadLocal 不清会污染同线程里的后续用例
        TraceContext.clear();
    }

    @Test
    @DisplayName("403：权限不足时响应体带 traceId，且 message 含缺失的权限码")
    void forbiddenCarriesTraceIdAndMissingPermissionCode() {
        TraceContext.set(FIXED_TRACE);
        HttpServletRequest request = mock(HttpServletRequest.class);

        ResponseEntity<ApiResponse<Void>> resp =
                handler.handleBiz(BizException.of(ErrorCode.FORBIDDEN, "无操作权限：缺 删除知识库（kb:delete）"), request);

        assertEquals(403, resp.getStatusCode().value(), "权限不足必须是 HTTP 403");
        ApiResponse<Void> body = resp.getBody();
        assertNotNull(body);
        assertEquals(ErrorCode.FORBIDDEN.getCode(), body.getCode());
        assertEquals(FIXED_TRACE, body.getTraceId(), "403 必须能拿到 traceId");
        assertTrue(body.getMessage().contains("kb:delete"),
                "403 的 message 里必须带缺失的权限码（G9-9 原文要求），实际=" + body.getMessage());
    }

    @Test
    @DisplayName("404：路由不存在时响应体带 traceId（HTTP 404）")
    void routeNotFoundCarriesTraceId() {
        TraceContext.set(FIXED_TRACE);

        ResponseEntity<ApiResponse<Void>> resp =
                handler.handleNoResource(new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/api/nope", "/api/nope"));

        assertEquals(404, resp.getStatusCode().value(), "路由不存在走传输层 404");
        ApiResponse<Void> body = resp.getBody();
        assertNotNull(body);
        assertEquals(ErrorCode.NOT_FOUND.getCode(), body.getCode());
        assertEquals(FIXED_TRACE, body.getTraceId(), "404 必须能拿到 traceId");
    }

    @Test
    @DisplayName("500：未捕获异常时响应体带 traceId，且堆栈不外泄")
    void unexpectedCarriesTraceId() {
        TraceContext.set(FIXED_TRACE);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/boom");

        ResponseEntity<ApiResponse<Void>> resp =
                handler.handleUnexpected(new IllegalStateException("内部炸了：secret-stack-detail"), request);

        assertEquals(500, resp.getStatusCode().value(), "未捕获异常必须是 HTTP 500");
        ApiResponse<Void> body = resp.getBody();
        assertNotNull(body);
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), body.getCode());
        assertEquals(FIXED_TRACE, body.getTraceId(), "500 必须能拿到 traceId");
        assertTrue(!body.getMessage().contains("secret-stack-detail"),
                "异常详情不许回前端，实际=" + body.getMessage());
    }
}
