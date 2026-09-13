package com.knowledge.ai.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 认证域出入参（M01）。
 *
 * <p>集中放在一个文件里：这些都是**只有字段没有行为**的传输结构，
 * 一个类一个文件会让 13 个接口散成 20 多个 3 行的文件，反而难找。
 * 每个 record 的 javadoc 都写明对应的《10》条款与前端消费方。
 *
 * <h3>字段命名口径（重要，D-8）</h3>
 * <p>出参一律以**前端实际消费的字段名**为准（前端零改动是硬约束）。
 * 当《10》与前端不一致时，采用「超集 + 别名」策略：
 * <ul>
 *   <li>响应里同时给出两个名字（如 {@code resetToken} 与 {@code token}）；
 *   <li>请求里用 {@code @JsonAlias} 同时接受两个名字。</li>
 * </ul>
 * 这样文档与前端都不需要改，且将来收敛任意一侧都只是删字段 / 删别名，属可逆操作。
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    // ==================== 入参 ====================

    /**
     * 密码登录（{@code POST /auth/login}，《10》§1）。
     *
     * <p>{@code account} 支持「用户名 / 手机号 / 邮箱」三种输入 —— 与登录页
     * 输入框的 placeholder 一致（`LoginView.vue`）。
     *
     * <p>{@code captchaId} / {@code captchaCode} **不是必填**：首次登录不带验证码，
     * 失败后前端才强制带上（`LoginView.vue` 的 `needCaptcha`）。
     * 后端在连续失败达阈值后**要求**验证码，见 {@code LoginAttemptGuard}。
     */
    public record LoginRequest(
            @NotBlank(message = "请输入用户名 / 手机号 / 邮箱")
            String account,

            @NotBlank(message = "请输入密码")
            String password,

            String captchaId,

            String captchaCode,

            /** 「记住我（7 天免登录）」。仅影响 accessToken 的 `expiresIn` 与 refresh 有效期。 */
            Boolean remember) {
    }

    /**
     * 发短信验证码（{@code POST /auth/sms-code}，《10》§1）。
     *
     * <p>{@code scene} ∈ {@code login} / {@code register} / {@code reset-password}。
     * <b>是 {@code scene} 不是 {@code purpose}</b> —— 这条口径已冻结，改回会静默丢参数。
     *
     * <p>{@code phone} 与 {@code username} 二选一：忘记密码场景前端传 username，
     * 注册场景传 phone（见 `ForgotPasswordView.vue` 与 `RegisterView.vue`）。
     */
    public record SmsCodeRequest(
            String phone,
            String username,
            String captchaId,
            String captchaCode,
            @NotBlank(message = "缺少验证码场景")
            String scene) {
    }

    /** 注册（{@code POST /auth/register}，《10》§1）。 */
    public record RegisterRequest(
            @NotBlank(message = "请输入用户名")
            @Pattern(regexp = "^[A-Za-z0-9_.]{4,32}$", message = "用户名需 4-32 位字母 / 数字 / 下划线 / 点")
            String username,

            @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
            String phone,

            String smsCode,

            @NotBlank(message = "请输入密码")
            @Size(min = 8, max = 64, message = "密码长度需 8-64 位")
            String password,

            String realName) {
    }

    /**
     * 忘记密码 · 校验身份（{@code POST /auth/verify-reset}）。
     *
     * <p>前端（`ForgotPasswordView.vue`）传 {@code { username, phone, smsCode }}，
     * 《10》§1 写的是 {@code { username, smsCode }}。这里两者都接受，
     * {@code phone} 收到就用于交叉核对手机号，收不到也不报错。
     */
    public record VerifyResetRequest(
            @NotBlank(message = "请输入用户名")
            String username,

            String phone,

            @NotBlank(message = "请输入验证码")
            String smsCode) {
    }

    /**
     * 忘记密码 · 重置（{@code POST /auth/reset-password}）。
     *
     * <p><b>这里就是 D-8 的典型现场</b>：《10》§1 定义的是
     * {@code { resetToken, newPassword }}，而前端 `api/index.ts` 发的是
     * {@code { token, password }}。
     *
     * <p><b>为什么用"两个字段 + 解析方法"而不是 {@code @JsonAlias}</b>：
     * {@code @JsonAlias} 作用在 record 组件上依赖 Jackson 对 record 的注解传播行为，
     * 而本项目用的是 Jackson 3（包名不变、实现重写）—— 这类"看起来应该生效"的
     * 隐式行为一旦不生效，表现是**参数静默为 null**，属于最难查的一类问题。
     * 显式声明两个字段再在服务层择一，行为完全确定，且可以直接写单测。
     *
     * <p>校验放到服务层（因为两个字段二选一，注解无法表达"至少一个非空"）。
     */
    public record ResetPasswordRequest(String token, String resetToken, String password, String newPassword) {

        /** 生效的凭证：《10》的 {@code resetToken} 或前端的 {@code token}。 */
        public String resolveToken() {
            return firstNonBlank(token, resetToken);
        }

        /** 生效的新密码：《10》的 {@code newPassword} 或前端的 {@code password}。 */
        public String resolvePassword() {
            return firstNonBlank(password, newPassword);
        }
    }

    /** 修改密码（{@code POST /user/password}）。字段名对齐前端 `userApi.changePassword`。 */
    public record ChangePasswordRequest(
            @NotBlank(message = "请输入原密码")
            String oldPassword,

            @NotBlank(message = "请输入新密码")
            @Size(min = 8, max = 64, message = "密码长度需 8-64 位")
            String newPassword) {
    }

    // ==================== 出参 ====================

    /**
     * 登录成功响应（《10》§1 + §14 取并集）。
     *
     * <p>字段来源：
     * <ul>
     *   <li>§1：{@code accessToken, refreshToken, user, roles, permissions[]}</li>
     *   <li>§14：另加 {@code tokenType, expiresIn}</li>
     * </ul>
     *
     * <p><b>{@code refreshToken} 为什么仍然出现在响应体里，同时又进 HttpOnly Cookie</b>：
     * G1-2 判据是"前端 {@code document.cookie} 读不到"——Cookie 满足它；
     * 而响应体保留该字段是为了不破坏 mock 时代就存在的字段契约
     * （`stores/auth.ts` 的 {@code LoginResult} 声明了它，虽然当前只消费 accessToken）。
     * 二者并存不冲突：Cookie 负责安全，响应体负责契约。
     */
    public record LoginResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresIn,
            UserView user,
            List<RoleItem> roles,
            List<String> permissions) {
    }

    /** 角色（码 + 名），前端登录后展示与菜单计算都要用。 */
    public record RoleItem(String code, String name) {
    }

    /**
     * 登录响应里的用户对象。
     *
     * <p>字段对齐前端 `stores/auth.ts` 的 {@code AuthUser}：
     * {@code id, username, realName, phone, department, roles: string[], roleNames?, permissions?}。
     * {@code department} 是**部门全路径名**（派生值，不落库）。
     *
     * <p><b>{@code createdAt} 是纯新增字段（2026-09-13）</b>，理由是个人信息页右栏的
     * 「账号信息」要显示**注册时间**，而原先这个视图里没有它 —— 前端只能显示一个写死的
     * 日期（A-6）。加字段对既有消费方（登录页、路由守卫）无影响：它们不读这个名字。
     * 与 {@code lastLoginAt} 同为 {@code yyyy-MM-dd HH:mm:ss} 文本，不是 ISO 串 ——
     * 前端直接展示，不做二次格式化。
     */
    public record UserView(
            Long id,
            String username,
            String realName,
            String phone,
            String email,
            Long deptId,
            String department,
            List<String> roles,
            List<String> roleNames,
            List<String> permissions,
            String avatar,
            String position,
            String intro,
            String status,
            Boolean mustChangePassword,
            String lastLoginAt,
            String createdAt) {

        /**
         * 替换 {@code avatar} 字段。
         *
         * <p>用途：库里 {@code sys_user.avatar} 存的是**对象名**（object key），
         * 直接返出去前端 {@code <img src>} 加载不了，读取时要换成预签名 URL。
         * 做成 record 上的小方法，是为了避免在 Service 里手写一遍 16 个参数 ——
         * 那种写法在加字段时一定会漏改。
         */
        public UserView withAvatar(String newAvatar) {
            return new UserView(id, username, realName, phone, email, deptId, department,
                    roles, roleNames, permissions, newAvatar, position, intro,
                    status, mustChangePassword, lastLoginAt, createdAt);
        }
    }

    /** 可用性检查（{@code check-username} / {@code check-phone}，§14）。 */
    public record AvailabilityResponse(boolean available, String reason) {
    }

    /**
     * 图形验证码响应（{@code GET /auth/captcha}）。
     *
     * <p>{@code imageBase64} 是**带 {@code data:image/png;base64,} 前缀的完整 Data URL** ——
     * 前端（`LoginView.vue`）直接 {@code <img :src="captchaImg">}，不做拼接。
     * 若只返回裸 base64，前端必须自己补前缀，属把格式细节漏给消费方。
     */
    public record CaptchaResponse(String captchaId, String imageBase64) {
    }

    /**
     * 发码响应（§14）：{@code { sent, debugCode?, scene }}。
     *
     * <p>{@code debugCode} 仅开发环境返回。S1 的发码是**伪代码实现**
     * （不发真实短信、校验值固定 {@code 000000}），因此它在本地恒定返回，
     * 便于联调与自动化测试；生产接入真实短信通道后由配置关闭。
     */
    public record SmsCodeResponse(boolean sent, String debugCode, String scene) {
    }

    /** 注册响应（对齐前端 `authApi.register` 声明的 {@code { registered, username }}）。 */
    public record RegisterResponse(boolean registered, String username) {
    }

    /**
     * 忘记密码·校验身份的响应。
     *
     * <p><b>双写 {@code resetToken} 与 {@code token}</b>：前者是《10》§1 的字段名，
     * 后者是前端 `ForgotPasswordView.vue` 实际读取的字段名（{@code res.token}）。
     * 缺任一都会造成"文档对但功能不通"或"功能通但文档错"。
     *
     * <p>{@code maskedPhone} 供页面回显脱敏手机号（前端用它填 {@code form.phone}）。
     */
    public record VerifyResetResponse(String resetToken, String token, String maskedPhone) {
    }

    /** 重置密码响应（对齐前端 `authApi.forgotReset` 声明的 {@code { reset }}）。 */
    public record ResetPasswordResponse(boolean reset) {
    }

    /** 登出响应。 */
    public record LogoutResponse(boolean loggedOut) {
    }

    /**
     * 登录日志行（§14）：{@code { id, method, ip, device, result, time }}。
     *
     * <p>{@code method} 是**中文展示文案**（密码登录 / 验证码登录 / 统一身份认证），
     * 由库里的枚举码映射而来 —— 前端直接渲染该字段，不做二次映射。
     */
    public record LoginLogItem(Long id, String method, String ip, String device, String result, String time) {
    }

    /** 取第一个非空白值；全为空返回 {@code null}。 */
    static String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
