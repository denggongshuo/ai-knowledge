package com.knowledge.ai.core.audit;

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
 * 审计登记上下文的**兜底清理**（配合 {@link AuditContext}）。
 *
 * <p><b>为什么 {@link AuditAspect} 之外还需要它</b>：切面只在挂了
 * {@code @AuditLog} 的方法上执行 {@code drain()}。若某个方法调了
 * {@code AuditContext.change(...)} 却**忘了加注解**，那份登记就没人取走 ——
 * 而 Tomcat 线程是池化的，下一次请求复用同一线程时，它的审计会**带上这次的变更明细**。
 * 那是最坏的一类 bug：日志内容是对的格式、错的归属，且只在并发下偶发。
 *
 * <p>所以清理做成两处：
 * <ol>
 *   <li>{@link AuditAspect} —— 正常路径，采集完即 {@code drain()}；</li>
 *   <li>本过滤器 —— 请求结束 {@code clear()}，兜住"登记了但没注解"这条漏网路径。</li>
 * </ol>
 *
 * <p>顺序放在 {@link com.knowledge.ai.core.trace.TraceIdFilter} 之后的**最外层**：
 * 它的 {@code finally} 要在业务与切面都跑完之后执行，才能兜住后者漏掉的残值。
 * 数值给得比过滤器的 {@code HIGHEST_PRECEDENCE} 大，即"更靠内"。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuditContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 无条件清理：此时正常路径早被切面 drain 过，这里是空操作；
            // 只有"登记了但没注解"的情况下才真正起作用。
            AuditContext.clear();
        }
    }
}
