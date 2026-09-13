package com.knowledge.ai.controller;

import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.web.WebUtils;
import com.knowledge.ai.dto.auth.AuthDtos;
import com.knowledge.ai.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（M01，《10-接口约定汇总》§1）。
 *
 * <p>{@code /api/auth/**} 整段在 {@code SecurityConfig} 的白名单里（登录前当然拿不到令牌），
 * 因此本控制器的每个方法都必须自己确认"这个接口本来就该匿名可达"：
 * {@code captcha / login / sms-code / check-* / register / verify-reset / reset-password / refresh}
 * 是；{@code logout} 虽然也在白名单里，但它依赖 {@code LoginUserContext}
 * 来取要拉黑的令牌 —— 未登录时它退化为"只清 Cookie"，属**幂等登出**，是刻意行为
 * （用户令牌过期后点登出不该再报一次 401）。
 *
 * <h3>refreshToken 的双轨：Cookie + 响应体</h3>
 * <p>这是本控制器最需要解释的一处设计：
 * <ul>
 *   <li><b>HttpOnly Cookie</b> —— 满足 G1-2「前端 {@code document.cookie} 读不到」。
 *       它是刷新令牌的**权威载体**，{@code /auth/refresh} 优先从 Cookie 读。</li>
 *   <li><b>响应体字段</b> —— {@code { accessToken, refreshToken, ... }} 是 mock 时代
 *       就冻结的契约（{@code stores/auth.ts} 的 {@code LoginResult} 声明了它）。
 *       前端零改动是硬约束，因此保留。</li>
 * </ul>
 * <p>二者并存不冲突：Cookie 负责安全，响应体负责契约。
 * 将来前端接入自动刷新后，可以只删响应体那一份。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    /** refreshToken 的 Cookie 名。前端不读它，名字只对后端与浏览器有意义。 */
    private static final String REFRESH_COOKIE = "refreshToken";

    /**
     * Cookie 作用域限定在认证路径下。
     *
     * <p>不用 {@code /}：那样每个业务请求（列表、上传、SSE）都会白白带上这个
     * Cookie，既增加请求体积，也扩大泄露面 —— 而只有刷新与登出会用到它。
     */
    private static final String COOKIE_PATH = "/api/auth";

    private final AuthService authService;

    // ==================== 验证码 ====================

    /** {@code GET /auth/captcha} → {@code { captchaId, imageBase64 }}。 */
    @GetMapping("/captcha")
    public ApiResponse<AuthDtos.CaptchaResponse> captcha() {
        return ApiResponse.ok(authService.captcha());
    }

    // ==================== 登录 ====================

    /** {@code POST /auth/login}（密码登录）。 */
    @PostMapping("/login")
    public ApiResponse<AuthDtos.LoginResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request,
                                                     HttpServletRequest servletRequest,
                                                     HttpServletResponse servletResponse) {
        AuthService.LoginResult result = authService.login(request,
                WebUtils.clientIp(servletRequest), WebUtils.userAgent(servletRequest));
        writeRefreshCookie(servletResponse, result.refreshToken(), request.remember());
        return ApiResponse.ok(result.response());
    }

    /**
     * {@code POST /auth/login-by-sms}（短信登录）。
     *
     * <p><b>S1 不实现，但接口必须是"可解释的失败"而不是 404</b>：前端
     * `LoginView.vue` 的「短信登录」分段控件在真后端下依然可点，
     * 返回 404 会让用户看到"请求失败(404)"这种无从处理的信息；
     * 返回结构化业务错误（{@code SMS_LOGIN_UNSUPPORTED}）则前端能直接展示
     * 「短信登录暂未开放，请使用账号密码登录」。
     *
     * <p>实现推迟到 S2（见 {@code docs/进度/03-Sprint排期.md} §Sprint 1 范围调整）。
     */
    @PostMapping("/login-by-sms")
    public ApiResponse<Void> loginBySms(@RequestBody(required = false) Object body) {
        // 走到这里说明请求格式没问题，缺的只是功能本身
        throw com.knowledge.ai.core.exception.BizException.of(
                com.knowledge.ai.core.exception.ErrorCode.SMS_LOGIN_UNSUPPORTED);
    }

    /**
     * {@code POST /auth/refresh}（刷新令牌，需并发去重）。
     *
     * <p>令牌来源顺序：HttpOnly Cookie → 请求体 {@code refreshToken}。
     * 前者是正常路径；后者是给非浏览器客户端（curl / 集成测试）留的入口，
     * 因为没有 Cookie jar 就没法演示刷新流程。
     */
    @PostMapping("/refresh")
    public ApiResponse<AuthDtos.LoginResponse> refresh(HttpServletRequest servletRequest,
                                                       HttpServletResponse servletResponse,
                                                       @RequestBody(required = false) RefreshBody body) {
        String token = readRefreshToken(servletRequest, body);
        AuthService.LoginResult result = authService.refresh(token);
        // 记住我信息在刷新时不可知，按 Cookie 现有 Max-Age 语义续期到默认 7 天
        writeRefreshCookie(servletResponse, result.refreshToken(), true);
        return ApiResponse.ok(result.response());
    }

    /** 请求体形式的 refreshToken（非浏览器客户端用）。 */
    public record RefreshBody(String refreshToken) {
    }

    // ==================== 登出 ====================

    /** {@code POST /auth/logout}（令牌入黑名单）。 */
    @PostMapping("/logout")
    public ApiResponse<AuthDtos.LogoutResponse> logout(HttpServletRequest servletRequest,
                                                       HttpServletResponse servletResponse,
                                                       @RequestBody(required = false) RefreshBody body) {
        String accessToken = resolveAccessToken(servletRequest);
        authService.logout(accessToken, readRefreshToken(servletRequest, body));
        clearRefreshCookie(servletResponse);
        return ApiResponse.ok(new AuthDtos.LogoutResponse(true));
    }

    // ==================== 可用性检查 ====================

    /** {@code GET /auth/check-username}。 */
    @GetMapping("/check-username")
    public ApiResponse<AuthDtos.AvailabilityResponse> checkUsername(@RequestParam String username) {
        return ApiResponse.ok(authService.checkUsername(username));
    }

    /** {@code GET /auth/check-phone}。 */
    @GetMapping("/check-phone")
    public ApiResponse<AuthDtos.AvailabilityResponse> checkPhone(@RequestParam String phone) {
        return ApiResponse.ok(authService.checkPhone(phone));
    }

    // ==================== 注册 ====================

    /** {@code POST /auth/register}。 */
    @PostMapping("/register")
    public ApiResponse<AuthDtos.RegisterResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest request,
                                                           HttpServletRequest servletRequest) {
        return ApiResponse.ok(authService.register(request,
                WebUtils.clientIp(servletRequest), WebUtils.userAgent(servletRequest)));
    }

    // ==================== 发码 ====================

    /** {@code POST /auth/sms-code}（伪代码实现，固定 {@code 000000}）。 */
    @PostMapping("/sms-code")
    public ApiResponse<AuthDtos.SmsCodeResponse> smsCode(@Valid @RequestBody AuthDtos.SmsCodeRequest request,
                                                         HttpServletRequest servletRequest) {
        return ApiResponse.ok(authService.sendSmsCode(request, WebUtils.clientIp(servletRequest)));
    }

    // ==================== 忘记密码（T-108） ====================

    /**
     * {@code POST /auth/verify-reset} —— 第一步：用户名 + 短信验证码 → 一次性重置凭证。
     *
     * <p>响应同时给出 {@code resetToken}（《10》§37）与 {@code token}
     * （前端 `ForgotPasswordView.vue` 读的是 {@code res.token}）。见 {@code AuthDtos.VerifyResetResponse}。
     */
    @PostMapping("/verify-reset")
    public ApiResponse<AuthDtos.VerifyResetResponse> verifyReset(@Valid @RequestBody AuthDtos.VerifyResetRequest request) {
        return ApiResponse.ok(authService.verifyReset(request));
    }

    /**
     * {@code POST /auth/reset-password} —— 第二步：凭证 + 新密码。
     *
     * <p>请求体字段是「超集」：{@code resetToken}/{@code newPassword}（《10》§38）
     * 与 {@code token}/{@code password}（前端）都收，由
     * {@code ResetPasswordRequest.resolveToken()/resolvePassword()} 择一。
     */
    @PostMapping("/reset-password")
    public ApiResponse<AuthDtos.ResetPasswordResponse> resetPassword(@RequestBody AuthDtos.ResetPasswordRequest request) {
        return ApiResponse.ok(authService.resetPassword(request));
    }

    // ==================== Cookie 工具 ====================

    private String readRefreshToken(HttpServletRequest request, RefreshBody body) {
        if (request.getCookies() != null) {
            for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
                if (REFRESH_COOKIE.equals(cookie.getName()) && cookie.getValue() != null
                        && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }
        return body == null ? null : body.refreshToken();
    }

    private String resolveAccessToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        return null;
    }

    /**
     * 写 refreshToken 的 HttpOnly Cookie。
     *
     * <p>属性取舍：
     * <ul>
     *   <li>{@code HttpOnly} —— G1-2 的判据本体，JS 读不到；</li>
     *   <li>{@code SameSite=Lax} —— 开发期前端走 Vite proxy 属同源，Lax 足够；
     *       生产若前后端分域部署需改 {@code None} 并配 {@code Secure}（T-D01 验收）；</li>
     *   <li>{@code Max-Age} —— 「记住我」给 7 天，否则按会话（{@code -1}）；
     *       但服务端有效期始终以 Redis 中的 TTL 为准，Cookie 过期只影响浏览器是否继续携带。</li>
     * </ul>
     */
    private void writeRefreshCookie(HttpServletResponse response, String refreshToken, Boolean remember) {
        if (refreshToken == null) {
            return;
        }
        boolean persistent = Boolean.TRUE.equals(remember);
        String header = REFRESH_COOKIE + "=" + refreshToken
                + "; Path=" + COOKIE_PATH
                + "; HttpOnly"
                + "; SameSite=Lax"
                + (persistent ? "; Max-Age=" + (7 * 24 * 3600) : "");
        response.addHeader(HttpHeaders.SET_COOKIE, header);
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                REFRESH_COOKIE + "=; Path=" + COOKIE_PATH + "; HttpOnly; SameSite=Lax; Max-Age=0");
    }
}
