package com.knowledge.ai.service;

import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.enums.LoginMethod;
import com.knowledge.ai.core.enums.SmsScene;
import com.knowledge.ai.core.enums.UserStatus;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.security.CaptchaStore;
import com.knowledge.ai.core.security.JwtService;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.core.security.RefreshTokenStore;
import com.knowledge.ai.core.security.ResetTokenStore;
import com.knowledge.ai.core.security.TokenBlacklist;
import com.knowledge.ai.dto.auth.AuthDtos;
import com.knowledge.ai.entity.SysUser;
import com.knowledge.ai.repository.SysRoleRepository;
import com.knowledge.ai.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 认证主服务（M01 · T-103/T-105/T-107/T-109/T-110）。
 *
 * <p>本类只负责**编排**：查用户 → 校验 → 发令牌 → 记日志。
 * 具体能力都在各自的小类里（{@code CaptchaStore} / {@code LoginAttemptGuard} /
 * {@code SmsCodeService} / {@code UserPrincipalAssembler} / {@code LoginLogService}），
 * 这样每一条风控规则都能被单独测试，也不会出现"测试登录要先把验证码也造出来"的连锁。
 *
 * <h3>两条贯穿全类的规则</h3>
 * <ol>
 *   <li><b>失败提示不区分账号是否存在</b>（G1-4）：账号不存在与密码错误都抛
 *       {@link ErrorCode#BAD_CREDENTIALS}，文案完全相同。
 *       否则攻击者可以先用登录接口枚举出有效账号，再来撞密码。</li>
 *   <li><b>失败也要写登录日志</b>（G1-4 的另一半）：风控的价值在失败记录里。
 *       日志走独立事务，业务抛异常不影响它落库。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 「记住我」时 accessToken 的有效期（秒）= 7 天，与 refreshToken 同长。 */
    private static final long REMEMBER_TTL_SECONDS = 7L * 24 * 3600;

    private final SysUserRepository userRepository;

    private final SysRoleRepository roleRepository;

    private final PasswordEncoder passwordEncoder;

    private final JwtService jwtService;

    private final RefreshTokenStore refreshTokenStore;

    private final ResetTokenStore resetTokenStore;

    private final TokenBlacklist tokenBlacklist;

    private final CaptchaStore captchaStore;

    private final LoginAttemptGuard attemptGuard;

    private final SmsCodeService smsCodeService;

    private final LoginLogService loginLogService;

    private final UserPrincipalAssembler assembler;

    /** 登录（含令牌对）的结果。refreshToken 单独带出，供 Controller 写入 HttpOnly Cookie。 */
    public record LoginResult(AuthDtos.LoginResponse response, String refreshToken) {
    }

    // ==================== T-103 图形验证码 ====================

    /**
     * 生成图形验证码。
     *
     * <p>响应字段固定为 {@code { captchaId, imageBase64 }}（前端
     * `authApi.captcha` 直接取这两个字段，{@code imageBase64} 直接塞进 {@code <img src>}）。
     */
    public AuthDtos.CaptchaResponse captcha() {
        CaptchaStore.Captcha captcha = captchaStore.generate();
        return new AuthDtos.CaptchaResponse(captcha.captchaId(), captcha.imageBase64());
    }

    // ==================== T-105 密码登录 ====================

    /**
     * 账号密码登录。
     *
     * <p>顺序刻意如此 —— 先做**不需要查库**的拦截（锁定、验证码），
     * 再查库。这样被锁定的账号或脚本流量不会打到数据库上。
     */
    public LoginResult login(AuthDtos.LoginRequest request, String ip, String userAgent) {
        String account = request.account() == null ? "" : request.account().trim();

        // ① 锁定检查（G1-5）
        if (attemptGuard.isLocked(account)) {
            long remain = attemptGuard.lockRemainingSeconds(account);
            loginLogService.record(null, account, LoginMethod.PASSWORD, false,
                    "账号锁定中（剩余 " + remain + " 秒）", ip, userAgent);
            throw BizException.of(ErrorCode.LOGIN_LOCKED);
        }

        // ② 验证码：达到阈值就必须带；没到阈值时若带了也要校验
        //    （前端在第一次失败后就会开始带，不能因为"还没到阈值"就忽略它）
        boolean required = attemptGuard.captchaRequired(account);
        if (required && isBlank(request.captchaCode())) {
            throw BizException.of(ErrorCode.CAPTCHA_REQUIRED);
        }
        if (!isBlank(request.captchaCode()) && !captchaStore.verify(request.captchaId(), request.captchaCode())) {
            // 验证码错不记入"密码失败"计数：它不是密码尝试，计数会让正常用户被误锁
            throw BizException.of(ErrorCode.CAPTCHA_INVALID);
        }

        // ③ 查用户（支持用户名 / 手机号 / 邮箱）
        Optional<SysUser> found = userRepository.findByAccount(account);
        if (found.isEmpty()) {
            failLogin(null, account, ip, userAgent, "账号不存在");
            throw BizException.of(ErrorCode.BAD_CREDENTIALS);
        }
        SysUser user = found.get();

        // ④ 密码校验（BCrypt）
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            failLogin(user.getId(), account, ip, userAgent, "密码错误");
            throw BizException.of(ErrorCode.BAD_CREDENTIALS);
        }

        // ⑤ 状态校验：密码对了才检查状态 —— 否则"账号已停用"会变成账号存在性的探针
        if (user.getStatus() == UserStatus.DISABLED) {
            failLogin(user.getId(), account, ip, userAgent, "账号已停用");
            throw BizException.of(ErrorCode.ACCOUNT_DISABLED);
        }
        if (user.getStatus() == UserStatus.PENDING) {
            failLogin(user.getId(), account, ip, userAgent, "账号待激活");
            throw BizException.of(ErrorCode.ACCOUNT_PENDING);
        }

        // ⑥ 成功：清计数、更新登录时间、记日志、发令牌
        attemptGuard.clear(account);
        LoginResult result = issueTokens(user, Boolean.TRUE.equals(request.remember()));
        loginLogService.record(user.getId(), account, LoginMethod.PASSWORD, true, null, ip, userAgent);
        log.info("登录成功：userId={} username={}", user.getId(), user.getUsername());
        return result;
    }

    // ==================== T-100/T-102 刷新令牌 ====================

    /**
     * 用 refreshToken 换新令牌对（含并发去重，G1-3）。
     *
     * <p>去重逻辑全部在 {@link RefreshTokenStore#rotate} 里：多个并发调用只会
     * 触发一次真实轮换，且它们拿到**同一对**新令牌。
     */
    public LoginResult refresh(String refreshToken) {
        RefreshTokenStore.Rotation rotation = refreshTokenStore.rotate(refreshToken, userId -> {
            SysUser user = userRepository.findByIdAndDeletedAtIsNull(userId)
                    .orElseThrow(() -> BizException.of(ErrorCode.REFRESH_TOKEN_INVALID));
            if (user.getStatus() != UserStatus.ACTIVE) {
                throw BizException.of(ErrorCode.ACCOUNT_DISABLED);
            }
            return new RefreshTokenStore.TokenPair(
                    refreshTokenStore.issue(userId, JwtService.newTokenId()),
                    mintAccessToken(user));
        });

        if (rotation == null) {
            throw BizException.of(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        SysUser user = userRepository.findByIdAndDeletedAtIsNull(rotation.userId()).orElse(null);
        if (user == null) {
            throw BizException.of(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        // 重新组装出参：宽限窗口里只存了令牌，用户视图要现算（保证与当前角色权限一致）
        UserPrincipalAssembler.Principal principal = assembler.assemble(user);
        long expiresIn = jwtService.remainingSeconds(rotation.accessToken());
        AuthDtos.LoginResponse response = new AuthDtos.LoginResponse(
                rotation.accessToken(),
                rotation.refreshToken(),
                "Bearer",
                expiresIn,
                assembler.toUserView(principal),
                principal.roleItems(),
                List.copyOf(principal.permissionCodes()));
        return new LoginResult(response, rotation.refreshToken());
    }

    // ==================== T-110 登出 ====================

    /**
     * 登出：accessToken 入黑名单 + 撤销该 refreshToken。
     *
     * <p>只撤销提交上来的这一个 refreshToken，而不是"该用户全部" ——
     * 在手机上登出不应该把电脑上也踢下线。
     */
    public void logout(String accessToken, String refreshToken) {
        LoginUser user = LoginUserContext.get();
        if (!isBlank(accessToken) && user != null && user.getTokenId() != null) {
            tokenBlacklist.revoke(user.getTokenId(), jwtService.remainingSeconds(accessToken));
        }
        if (!isBlank(refreshToken)) {
            refreshTokenStore.revoke(refreshToken);
        }
    }

    /** 撤销某用户的全部 refreshToken（G1-8：改密 / 重置密码后连带失效）。 */
    public int revokeAllRefreshTokens(Long userId) {
        return refreshTokenStore.revokeAll(userId);
    }

    // ==================== T-104 发码 ====================

    /**
     * 发送短信验证码（伪代码实现，固定 {@code 000000}）。
     *
     * <p>发码前若带了图形验证码则校验 —— 这是 G1-5「图形验证码挡住脚本刷」
     * 在发码链路上的落点。不带也放行（原型上发码按钮是首要动作，
     * 强制先看图会打断流程），防护由限流承担。
     */
    public AuthDtos.SmsCodeResponse sendSmsCode(AuthDtos.SmsCodeRequest request, String ip) {
        SmsScene scene = SmsScene.parse(request.scene());
        String target = firstNonBlank(request.phone(), request.username());
        if (target == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "缺少手机号或用户名");
        }
        if (!isBlank(request.captchaCode()) && !captchaStore.verify(request.captchaId(), request.captchaCode())) {
            throw BizException.of(ErrorCode.CAPTCHA_INVALID);
        }
        String debugCode = smsCodeService.send(target, scene, ip);
        return new AuthDtos.SmsCodeResponse(true, debugCode, scene.getWire());
    }

    // ==================== T-107 注册 ====================

    /**
     * 注册（不变量 I-1）。
     *
     * <p><b>I-1：注册用户自动归属「未分配」部门（{@code deptId = 0}）且自动带 {@code USER} 角色</b>。
     * 两者都不是可选项 —— "没有任何角色的用户"在本系统的权限模型里是死号
     * （三层求交第一层就为空），留出这种状态只会制造排查成本。
     *
     * <p>注册即 {@code ACTIVE}：本项目没有"邮箱/短信激活"环节（《04》明确不做
     * 邮箱验证），{@code PENDING} 只用于管理员建号后待用户首次登录的场景。
     */
    @Transactional
    public AuthDtos.RegisterResponse register(AuthDtos.RegisterRequest request, String ip, String userAgent) {
        String username = request.username().trim();
        if (userRepository.existsByUsernameAndDeletedAtIsNull(username)) {
            throw BizException.of(ErrorCode.USERNAME_EXISTS);
        }
        String phone = normalize(request.phone());
        if (phone != null && userRepository.existsByPhoneAndDeletedAtIsNull(phone)) {
            throw BizException.of(ErrorCode.PHONE_EXISTS);
        }
        if (phone != null) {
            smsCodeService.verify(phone, SmsScene.REGISTER, request.smsCode());
        }
        assertPasswordStrength(request.password());

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRealName(isBlank(request.realName()) ? username : request.realName().trim());
        user.setPhone(phone);
        user.setDeptId(0L);
        user.setStatus(UserStatus.ACTIVE);
        user.setMustChangePassword(false);
        LocalDateTime now = LocalDateTime.now();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        // 注册即设密码，密码更新时间从这里起算（《04》§253 的「密码 N 天前更新」）
        user.setPasswordUpdatedAt(now);
        SysUser saved = userRepository.save(user);

        // I-1：自动带 USER 角色。用一条 insert-select 完成，
        // 避免"先查角色 id 再插入"多一次往返，也避免角色码拼错时静默插 0 行
        int granted = roleRepository.grantRole(saved.getId(), SysRoleRepository.USER_ROLE_CODE);
        if (granted == 0) {
            // 角色表里没有 USER 角色属部署事故（V2 种子必带），直接失败好过留下无角色用户
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "内置角色 USER 缺失，注册无法完成");
        }
        log.info("注册成功：userId={} username={}（已自动分配 USER 角色与未分配部门）",
                saved.getId(), username);

        loginLogService.record(saved.getId(), username, LoginMethod.PASSWORD, true, "注册", ip, userAgent);
        return new AuthDtos.RegisterResponse(true, username);
    }

    // ==================== T-108 忘记密码（两步） ====================

    /**
     * 忘记密码 · 第一步：校验身份，换一张一次性重置凭证。
     *
     * <p><b>校验顺序刻意是"先验证码、后查用户"</b>：反过来的话，
     * "账号不存在"会绕过验证码检查直接返回，攻击者拿它当用户名枚举器用。
     * 先验码则任何无效请求都停在同一个地方。
     *
     * <p><b>关于 {@code phone}</b>：《10》§37 的 body 是 {@code { username, smsCode }}，
     * 而前端 `ForgotPasswordView.vue` 发的是
     * {@code { username, phone: '13888886666', smsCode }} —— 那个手机号是**页面里
     * 写死的占位值**（真实绑定手机号这一步拿不到）。因此这里把 {@code phone} 当作
     * "验证码发到哪儿去了"的键（发码接口也是用它当键的），**不做**"是否等于用户
     * 绑定手机号"的交叉核对 —— 否则前端固定值必然对不上，整条链路直接不可用。
     * 待前端改为回填真实号后，这一处应当补上核对。
     *
     * <p>返回的 {@code maskedPhone} 优先取**用户真实绑定手机号**的脱敏值
     * （这正是要展示给用户确认的信息），用户没绑手机时退回请求里那个号码的脱敏值。
     */
    public AuthDtos.VerifyResetResponse verifyReset(AuthDtos.VerifyResetRequest request) {
        String username = request.username() == null ? "" : request.username().trim();
        String target = firstNonBlank(request.phone(), username);

        // ① 先消费短信验证码（一次性）
        smsCodeService.verify(target, SmsScene.RESET_PASSWORD, request.smsCode());

        // ② 再确认账号存在且可用
        SysUser user = userRepository.findByUsernameAndDeletedAtIsNull(username)
                .orElseThrow(() -> BizException.of(ErrorCode.USER_NOT_FOUND));
        if (user.getStatus() == UserStatus.DISABLED) {
            throw BizException.of(ErrorCode.ACCOUNT_DISABLED);
        }

        String token = resetTokenStore.issue(user.getId());
        String masked = UserPrincipalAssembler.maskPhone(user.getPhone());
        if (masked == null) {
            masked = UserPrincipalAssembler.maskPhone(target);
        }
        log.info("忘记密码身份校验通过：userId={}", user.getId());
        // 双写 resetToken（《10》§37）与 token（前端读的是 res.token）
        return new AuthDtos.VerifyResetResponse(token, token, masked);
    }

    /**
     * 忘记密码 · 第二步：用重置凭证换新密码。
     *
     * <p><b>顺序：强度校验 → 消费凭证 → 改密</b>。强度校验放最前，是因为
     * 凭证一旦消费就作废，若先消费再因"密码太弱"拒绝，用户要重新走一遍验证码 ——
     * 而这属于**可预检**的错误，不该付这个代价。
     *
     * <p><b>G1-8 在这里落地</b>：改密成功即撤销该用户全部 refreshToken。
     * 用户改密码的动机多半是"怀疑账号被盗"，此时把其它设备上的续期能力全部切断
     * 才是这个动作的意义；只改哈希值的话，攻击者手上的 refreshToken 还能继续换新令牌。
     *
     * <p>另外把 {@code must_change_password} 清掉：能自助重置密码的人已经完成了
     * "改密"这件事本身，没有理由再让他登录后被强制改一次。
     */
    @Transactional
    @AuditLog(module = AuditModule.AUTH, action = "重置密码（忘记密码自助）", risk = RiskLevel.HIGH)
    public AuthDtos.ResetPasswordResponse resetPassword(AuthDtos.ResetPasswordRequest request) {
        String token = request.resolveToken();
        String password = request.resolvePassword();
        if (isBlank(token)) {
            throw BizException.of(ErrorCode.RESET_TOKEN_INVALID);
        }
        assertPasswordStrength(password);

        Long userId = resetTokenStore.consume(token);
        if (userId == null) {
            throw BizException.of(ErrorCode.RESET_TOKEN_INVALID);
        }
        SysUser user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> BizException.of(ErrorCode.RESET_TOKEN_INVALID));
        if (passwordEncoder.matches(password, user.getPasswordHash())) {
            throw BizException.of(ErrorCode.PASSWORD_UNCHANGED);
        }

        LocalDateTime now = LocalDateTime.now();
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setPasswordUpdatedAt(now);
        user.setMustChangePassword(false);
        user.setUpdatedAt(now);
        userRepository.save(user);

        int revoked = refreshTokenStore.revokeAll(userId);
        loginLogService.record(userId, user.getUsername(), LoginMethod.PASSWORD, true,
                "重置密码，已撤销 " + revoked + " 个刷新令牌", null, null);
        log.info("密码重置完成：userId={} 已撤销 refreshToken={} 条", userId, revoked);
        return new AuthDtos.ResetPasswordResponse(true);
    }

    // ==================== T-109 可用性检查 ====================

    /** 用户名可用性（{@code { available, reason }}）。 */
    public AuthDtos.AvailabilityResponse checkUsername(String username) {
        if (isBlank(username)) {
            return new AuthDtos.AvailabilityResponse(false, "请输入用户名");
        }
        boolean taken = userRepository.existsByUsernameAndDeletedAtIsNull(username.trim());
        return new AuthDtos.AvailabilityResponse(!taken, taken ? "该用户名已被占用" : "该用户名可用");
    }

    /** 手机号可用性。 */
    public AuthDtos.AvailabilityResponse checkPhone(String phone) {
        if (isBlank(phone)) {
            return new AuthDtos.AvailabilityResponse(false, "请输入手机号");
        }
        boolean taken = userRepository.existsByPhoneAndDeletedAtIsNull(phone.trim());
        return new AuthDtos.AvailabilityResponse(!taken, taken ? "该手机号已注册" : "该手机号可用");
    }

    // ==================== 内部 ====================

    /** 签发令牌对并组装登录响应。 */
    private LoginResult issueTokens(SysUser user, boolean remember) {
        UserPrincipalAssembler.Principal principal = assembler.assemble(user);
        String jti = JwtService.newTokenId();
        LoginUser loginUser = assembler.toLoginUser(principal, jti);

        long accessTtl = remember ? REMEMBER_TTL_SECONDS : jwtService.accessTtlSeconds();
        String accessToken = jwtService.issueAccessToken(loginUser, accessTtl);
        String refreshToken = refreshTokenStore.issue(user.getId(), jti);

        // 只有登录这一条路径会更新 last_login_at；refresh 不更新（否则"最近登录"
        // 会变成"最近刷过令牌"，与用户理解不符）
        user.setLastLoginAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        AuthDtos.LoginResponse response = new AuthDtos.LoginResponse(
                accessToken,
                refreshToken,
                "Bearer",
                accessTtl,
                assembler.toUserView(principal),
                principal.roleItems(),
                List.copyOf(principal.permissionCodes()));
        return new LoginResult(response, refreshToken);
    }

    /**
     * 只签 accessToken（refresh 轮换时用）。
     *
     * <p>不复用 {@link #issueTokens}：轮换时 refreshToken 的有效期与 jti 由
     * 轮换逻辑决定，这里只需要一个能通过鉴权的 accessToken。
     * 用新 jti 而不是沿用旧 jti —— 旧 jti 随旧 accessToken 一起被撤销，
     * 复用会让"登出旧设备"连带把新令牌也拉黑。
     */
    private String mintAccessToken(SysUser user) {
        UserPrincipalAssembler.Principal principal = assembler.assemble(user);
        LoginUser loginUser = assembler.toLoginUser(principal, JwtService.newTokenId());
        return jwtService.issueAccessToken(loginUser);
    }

    /** 记一次失败（计数 + 日志）。 */
    private void failLogin(Long userId, String account, String ip, String userAgent, String reason) {
        int failures = attemptGuard.recordFailure(account);
        loginLogService.record(userId, account, LoginMethod.PASSWORD, false,
                reason + "（第 " + failures + " 次）", ip, userAgent);
    }

    /**
     * 密码强度校验。
     *
     * <p>口径：长度 ≥ 8，且至少包含**两类**字符（小写 / 大写 / 数字 / 符号）。
     * 不采用"必须含特殊字符"的硬规则 —— 那会显著提高用户使用弱变体
     * （{@code Password1!}）的概率，而两类字符 + 足够长度对撞库的抵抗更实在。
     *
     * <p><b>包内可见的静态方法</b>：注册（本类）与改密（{@code UserService}）必须用
     * **同一套**策略 —— 若各写一份，改密时的规则会悄悄比注册时宽（或严），
     * 而这类漂移只在"用户注册后改密码"这条路径上暴露。
     */
    static void assertPasswordStrength(String password) {
        if (password == null || password.length() < 8) {
            throw BizException.of(ErrorCode.PASSWORD_WEAK, "密码长度至少 8 位");
        }
        int kinds = 0;
        if (password.chars().anyMatch(Character::isLowerCase)) {
            kinds++;
        }
        if (password.chars().anyMatch(Character::isUpperCase)) {
            kinds++;
        }
        if (password.chars().anyMatch(Character::isDigit)) {
            kinds++;
        }
        if (password.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch))) {
            kinds++;
        }
        if (kinds < 2) {
            throw BizException.of(ErrorCode.PASSWORD_WEAK, "密码需包含字母 / 数字 / 符号中的至少两类");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalize(String value) {
        if (isBlank(value)) {
            return null;
        }
        // 允许用户输入带空格的手机号（原型上就有 "138 0000 0001" 这种写法）
        return value.replaceAll("\\s", "");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }
}
