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

    /**
     * 已确定的 traceId 挂在请求上的属性名（`S6-M05-C`）。
     *
     * <p>存在的唯一理由：**异步续跑必须复用同一个 id**，见
     * {@link #shouldNotFilterAsyncDispatch()} 与 {@link #resolve}。
     */
    private static final String ATTR_TRACE_ID = "ak.traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolve(request);
        TraceContext.set(traceId);
        // 已提交的响应上 setHeader 是空操作，但首次 dispatch 时它是有意义的
        response.setHeader(TraceContext.HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }

    /**
     * **异步续跑时也要带上 traceId**（`S6-M05-C` 的 P0 修复，与
     * {@code JwtAuthFilter#shouldNotFilterAsyncDispatch} 同族）。
     *
     * <h3>为什么必须补这一处</h3>
     * <p>缺陷 `#174`/`#175` 定位过程中最扎眼的一幕是：那两条
     * {@code AuthorizationDeniedException} 的 ERROR 日志**自己丢了 traceId** ——
     * 同一请求的 INFO 是 {@code [c651ab1777164765]}，ERROR 是 {@code ERROR [] [...]}。
     * 因为 SSE 完成后的 ASYNC 续跑发生在 {@code finally} 已 {@code clear()} 之后，
     * 而 {@code OncePerRequestFilter} 默认跳过续跑。
     * **全仓唯一能定位这个缺陷的两行日志，恰好是最查不出链路的两行。**
     *
     * <h3>⚠️ 为什么不能只覆盖这一个方法</h3>
     * <p>因为它只会"再跑一遍 {@link #resolve}"，而请求头里**通常没有**
     * {@code X-Trace-Id}（是服务端生成后回写的）—— 那样第二次会**生成一个新 id**，
     * 于是同一请求的两段日志**仍然是两个 id**，等于没修。
     * 所以 {@code resolve} 必须优先读 {@link #ATTR_TRACE_ID}。
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    private String resolve(HttpServletRequest request) {
        // ① 已经定过的（ASYNC 续跑走这一条）—— **必须最先判**，理由见
        //    shouldNotFilterAsyncDispatch 的 javadoc：不判它就会"再生成一个新 id"
        Object decided = request.getAttribute(ATTR_TRACE_ID);
        if (decided instanceof String existing && isSafe(existing)) {
            return existing;
        }
        // ② 上游（网关 / Nginx / 前端）传入的
        String header = request.getHeader(TraceContext.HEADER);
        if (header == null) {
            return remember(request, TraceContext.newTraceId());
        }
        String trimmed = header.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH || !isSafe(trimmed)) {
            return remember(request, TraceContext.newTraceId());
        }
        return remember(request, trimmed);
    }

    /** 定下来的 id 记在请求上，供 ASYNC 续跑复用。 */
    private static String remember(HttpServletRequest request, String traceId) {
        request.setAttribute(ATTR_TRACE_ID, traceId);
        return traceId;
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
