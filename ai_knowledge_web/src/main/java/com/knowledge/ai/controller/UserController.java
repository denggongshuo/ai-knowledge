package com.knowledge.ai.controller;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.dto.auth.AuthDtos;
import com.knowledge.ai.dto.user.UserDtos;
import com.knowledge.ai.service.LoginLogService;
import com.knowledge.ai.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 当前用户接口（M01 · 《10-接口约定汇总》§2，T-111 / T-112）。
 *
 * <p>{@code /api/user/**} **不在** {@code SecurityConfig} 的白名单里，
 * 因此整段都要求已认证 —— 这正是本控制器与 {@code AuthController} 最大的不同：
 * 这里每个方法都可以直接假定 {@code LoginUserContext} 里有值。
 * 取当前用户统一走 {@link #currentUserId()}，把"理论上不会发生"的兜底收敛到一处。
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // ==================== 资料 ====================

    /** {@code GET /user/profile} —— 当前用户详情。 */
    @GetMapping("/profile")
    public ApiResponse<AuthDtos.UserView> profile() {
        return ApiResponse.ok(userService.profile(currentUserId()));
    }

    /** {@code PUT /user/profile} —— body {@code { realName, email, position, intro }}。 */
    @PutMapping("/profile")
    public ApiResponse<UserDtos.ProfileUpdateResponse> updateProfile(
            @Valid @RequestBody UserDtos.UpdateProfileRequest request) {
        return ApiResponse.ok(userService.updateProfile(currentUserId(), request));
    }

    /**
     * {@code POST /user/avatar} —— {@code multipart/form-data}，字段名 {@code file}。
     *
     * <p>{@code consumes} 显式声明为 multipart：不声明的话，前端那个把 FormData
     * 序列化成 {@code "{}"} 的请求会以 {@code application/json} 进来，
     * 报一个含混的 415/400；显式声明后至少错误是确定的
     * （415 Unsupported Media Type），排查时一眼知道"请求形态不对"。
     */
    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UserDtos.AvatarResponse> uploadAvatar(@RequestPart("file") MultipartFile file) {
        return ApiResponse.ok(userService.uploadAvatar(currentUserId(), file));
    }

    // ==================== 安全设置 ====================

    /**
     * {@code POST /user/password} —— 修改密码 🚩。
     *
     * <p>需要原始 accessToken（不只是 jti）：改密后要把它拉黑，
     * 而黑名单的 TTL 应当等于该令牌的**剩余有效期** —— 勾了「记住我」的令牌
     * 活 7 天，若按默认 2 小时拉黑，剩下的 6 天多它仍然可用。
     */
    @PostMapping("/password")
    public ApiResponse<UserDtos.ChangePasswordResponse> changePassword(
            @Valid @RequestBody AuthDtos.ChangePasswordRequest request,
            HttpServletRequest servletRequest) {
        return ApiResponse.ok(userService.changePassword(currentUserId(), request, resolveAccessToken(servletRequest)));
    }

    /** {@code POST /user/change-phone} —— 换绑手机号 🚩（需验证码）。 */
    @PostMapping("/change-phone")
    public ApiResponse<UserDtos.ChangePhoneResponse> changePhone(@Valid @RequestBody UserDtos.ChangePhoneRequest request) {
        return ApiResponse.ok(userService.changePhone(currentUserId(), request));
    }

    /** {@code POST /user/change-email} —— 换绑邮箱 🚩（需验证码）。 */
    @PostMapping("/change-email")
    public ApiResponse<UserDtos.ChangeEmailResponse> changeEmail(@Valid @RequestBody UserDtos.ChangeEmailRequest request) {
        return ApiResponse.ok(userService.changeEmail(currentUserId(), request));
    }

    // ==================== 权限 / 安全 / 日志 ====================

    /** {@code GET /user/permissions} —— {@code { codes: string[] }}。 */
    @GetMapping("/permissions")
    public ApiResponse<UserDtos.PermissionsView> permissions() {
        return ApiResponse.ok(userService.permissions(currentUserId()));
    }

    /** {@code GET /user/security} —— 安全概览。 */
    @GetMapping("/security")
    public ApiResponse<UserDtos.SecurityView> security() {
        return ApiResponse.ok(userService.security(currentUserId()));
    }

    /**
     * {@code GET /user/login-logs?page=&pageSize=} —— 我的登录日志。
     *
     * <p>分页参数名兼容 {@code pageSize} 与 {@code size} 两个写法：
     * 前端 {@code userApi.loginLogs} 传 {@code pageSize}，《10》§58 写的是 {@code size}，
     * 两者都得收（{@code pageSize} 优先，都缺省为 10）。
     * 响应体按《10》§368 只给 {@code pageSize}，见 {@code LoginLogService.PageResult}。
     */
    @GetMapping("/login-logs")
    public ApiResponse<LoginLogService.PageResult> loginLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Integer size) {
        int effective = pageSize != null ? pageSize : (size != null ? size : 10);
        return ApiResponse.ok(userService.loginLogs(currentUserId(), page, effective));
    }

    // ==================== 内部 ====================

    /**
     * 当前登录用户 id。
     *
     * <p>走到这里理论上一定有值（{@code /api/user/**} 要求已认证）。
     * 仍然兜底成 401 而不是抛 NPE：真正的"没登录"只可能来自过滤器链被改动
     * （比如有人把本段加进白名单），那时应该是一个明确的 401，
     * 而不是一条 500 加一段栈。
     */
    private Long currentUserId() {
        LoginUser user = LoginUserContext.get();
        if (user == null || user.getUserId() == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return user.getUserId();
    }

    private String resolveAccessToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        return null;
    }
}
