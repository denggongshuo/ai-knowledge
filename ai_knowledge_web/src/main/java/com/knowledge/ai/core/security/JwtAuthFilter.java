package com.knowledge.ai.core.security;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 鉴权过滤器（T-007）：{@code Authorization: Bearer <token>} → {@link LoginUser}。
 *
 * <p>链路：
 * <pre>
 *   TraceIdFilter（Servlet 层，最早）
 *     └─> Spring Security FilterChain
 *           └─> JwtAuthFilter（本类）
 *                 ├─ 解析成功 → 查黑名单 → 未撤销才放行（LoginUserContext + SecurityContext）
 *                 └─ 解析失败 / 已撤销 → 记入 request attribute，交给 EntryPoint 输出 401 JSON
 * </pre>
 *
 * <p><b>为什么黑名单校验放在这里而不是 Service 层</b>：撤销是**鉴权语义**，
 * 不是业务语义。放在过滤器里，"已登出的令牌"与"签名不对的令牌"对外表现完全一致
 * （都是 401 + 10012），任何接口都不需要自己判一次，也就不会有人漏判。
 *
 * <p>撤销判定发生在 JWT 解析**之后** —— 因为 jti 在 claims 里，
 * 而 claims 只有在验签通过后才能信任。先查 Redis 再验签是错的：
 * 攻击者可以拿伪造的 jti 去查库，徒增 Redis 压力。
 *
 * <p><b>为什么解析失败不在这里直接写响应</b>：这样"从哪来"（JWT 过期 vs 根本没带 token）
 * 与"怎么回"（HTTP 401 + 统一响应体）解耦，输出格式只有
 * {@code SecurityConfig} 里的 EntryPoint 一处，避免两个地方各写一份 JSON 后走样。
 *
 * <p>本类**不是** {@code @Component}：它只应该存在于 Security 过滤器链里。
 * 若注册成 Bean，Spring Boot 会顺带把它再注册进 Servlet 容器，
 * 导致对每个请求执行两次。
 */
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    /** request attribute：本次请求鉴权失败的具体错误码（10011 过期 / 10012 无效）。 */
    public static final String AUTH_ERROR_ATTR = "ak.auth.error";

    /** 权限对应的 Spring Security authority 前缀。 */
    public static final String PERM_PREFIX = "perm:";

    /** 角色对应的前缀（Spring Security 约定必须为 {@code ROLE_}）。 */
    public static final String ROLE_PREFIX = "ROLE_";

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;

    private final TokenBlacklist tokenBlacklist;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        LoginUser user = null;

        if (token != null) {
            try {
                user = jwtService.parseAccessToken(token);
                // 解析成功只说明"签名与有效期没问题"，还要确认它没被登出 / 改密连带撤销
                if (user.getTokenId() != null && tokenBlacklist.isRevoked(user.getTokenId())) {
                    throw BizException.of(ErrorCode.TOKEN_INVALID);
                }
                LoginUserContext.set(user);
                SecurityContextHolder.getContext().setAuthentication(buildAuthentication(user, request));
            } catch (BizException ex) {
                // 不中断链路：让 Security 的 EntryPoint 统一输出 401
                SecurityContextHolder.clearContext();
                request.setAttribute(AUTH_ERROR_ATTR, ex.getErrorCode());
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            LoginUserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private UsernamePasswordAuthenticationToken buildAuthentication(LoginUser user, HttpServletRequest request) {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(user, null, toAuthorities(user));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return authentication;
    }

    private List<GrantedAuthority> toAuthorities(LoginUser user) {
        List<GrantedAuthority> authorities = new ArrayList<>(user.getRoleCodes().size() + user.getPermissions().size());
        for (String role : user.getRoleCodes()) {
            authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + role));
        }
        for (String perm : user.getPermissions()) {
            authorities.add(new SimpleGrantedAuthority(PERM_PREFIX + perm));
        }
        return authorities;
    }

    /** 鉴权失败时的错误码，缺省为"未登录"。 */
    public static ErrorCode resolveAuthError(HttpServletRequest request) {
        Object attribute = request.getAttribute(AUTH_ERROR_ATTR);
        return attribute instanceof ErrorCode errorCode ? errorCode : ErrorCode.UNAUTHORIZED;
    }
}
