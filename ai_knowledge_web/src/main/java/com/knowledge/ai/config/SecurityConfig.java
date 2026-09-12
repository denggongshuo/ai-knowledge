package com.knowledge.ai.config;

import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.security.JwtAuthFilter;
import com.knowledge.ai.core.security.JwtService;
import com.knowledge.ai.core.web.ResponseWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 配置（T-007）。
 *
 * <p>要点：
 * <ol>
 *   <li><b>无状态</b>：不建 Session，鉴权完全靠 JWT（refreshToken 走 HttpOnly Cookie，
 *       S1 实现 {@code /auth/refresh} 时再处理）。</li>
 *   <li><b>关掉 formLogin / httpBasic / csrf</b>：前后端分离 + JWT，
 *       这三者都会干扰（尤其是 csrf 会让所有非 GET 请求 403）。</li>
 *   <li><b>错误输出统一</b>：401 走 EntryPoint、403 走 AccessDeniedHandler，
 *       都输出 {@code {code,message,data,traceId}}，与业务响应体同构。</li>
 *   <li><b>白名单</b>：登录 / 注册 / 忘记密码 / 发码 / 健康检查 / Actuator。</li>
 * </ol>
 *
 * <p>{@code @EnableMethodSecurity} 让 {@code @PreAuthorize} 可用；
 * 本项目的权限校验主要走自定义的 {@code @RequiresPerm}（AOP，见 RequiresPermAspect），
 * 两者并存不冲突。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** 无需登录即可访问的路径。 */
    private static final String[] PUBLIC_PATHS = {
            "/api/auth/**",
            "/api/health/**",
            "/actuator/**",
            "/error",
    };

    @Value("${app.cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain apiFilterChain(HttpSecurity http,
                                              JwtService jwtService,
                                              ResponseWriter responseWriter) throws Exception {
        JwtAuthFilter jwtAuthFilter = new JwtAuthFilter(jwtService);

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        // 401：没带 token / token 过期或无效
                        .authenticationEntryPoint((request, response, authException) -> {
                            ErrorCode code = JwtAuthFilter.resolveAuthError(request);
                            responseWriter.writeFailure(response, code, code.getMessage(), HttpStatus.UNAUTHORIZED.value());
                        })
                        // 403：已登录但权限不足
                        .accessDeniedHandler((request, response, deniedException) ->
                                responseWriter.writeFailure(response, ErrorCode.FORBIDDEN)))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 密码编码器。统一用 BCrypt（自带盐，无需自己拼 salt）。
     * 明文密码永不入库、永不进日志。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CORS。
     *
     * <p>开发期前端通过 Vite proxy 把 {@code /api} 转发到 8080，是同源请求，
     * 其实不需要 CORS。但直接访问 8080（例如用 curl / 独立页面调试）时
     * 浏览器会发预检，所以保留这份最小配置。
     * 允许的源由 {@code app.cors.allowed-origins} 控制（逗号分隔），生产环境收紧。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(
                Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("X-Trace-Id"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
