package com.knowledge.ai.service;

import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.enums.PermissionCode;
import com.knowledge.ai.core.enums.LoginMethod;
import com.knowledge.ai.core.enums.SmsScene;
import com.knowledge.ai.core.enums.UserStatus;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.security.JwtService;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.core.security.RefreshTokenStore;
import com.knowledge.ai.core.security.TokenBlacklist;
import com.knowledge.ai.dto.auth.AuthDtos;
import com.knowledge.ai.dto.user.UserDtos;
import com.knowledge.ai.entity.SysLoginLog;
import com.knowledge.ai.entity.SysUser;
import com.knowledge.ai.repository.PermissionQueryRepository;
import com.knowledge.ai.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 当前用户服务（M01 · T-111 个人信息 / T-112 权限·安全·登录日志）。
 *
 * <p>与 {@code AuthService} 的分工：{@code AuthService} 管**不特定于某个人**的
 * 认证动作（登录、注册、发码、忘记密码），本类管**已登录用户对自己**的操作。
 * 分界线的实际价值是"本类的每个方法都必然有一个已认证的 userId"，
 * 因此不需要在每个方法里重复判"这个请求有没有登录"。
 *
 * <h3>几处刻意保留的不对称</h3>
 * <ul>
 *   <li><b>改密会踢掉自己，改资料不会</b>：改密撤销该用户全部 refreshToken
 *       （G1-8）并拉黑当前 accessToken；改昵称不做任何会话处置。</li>
 *   <li><b>{@code /user/permissions} 对超管展开成 38 个码</b>，而登录响应里的
 *       {@code permissions} 仍是 {@code ["*"]} —— 前者是**展示视图**（页面上要按域
 *       分组列出来），后者是**授权表示**。见 {@link #permissions(Long)}。</li>
 *   <li><b>换绑手机 / 邮箱必须验码</b>，尽管前端的弹窗按钮还没接上（见各方法 javadoc）。
 *       这类"改账号找回凭证"的动作一旦放行无校验版本，就是一个可直接接管账号的漏洞，
 *       不能因为"暂时没有调用方"就降级。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 中国大陆手机号。与 {@code AuthDtos.RegisterRequest} 的校验保持一致。 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    /** 安全概览里展示的最近登录设备条数。 */
    private static final int RECENT_DEVICE_LIMIT = 5;

    private final SysUserRepository userRepository;

    private final PermissionQueryRepository permissionQueryRepository;

    private final UserPrincipalAssembler assembler;

    private final PasswordEncoder passwordEncoder;

    private final RefreshTokenStore refreshTokenStore;

    private final TokenBlacklist tokenBlacklist;

    private final JwtService jwtService;

    private final SmsCodeService smsCodeService;

    private final LoginLogService loginLogService;

    private final AvatarStorage avatarStorage;

    // ==================== T-111 · 资料 ====================

    /**
     * 当前用户详情（{@code GET /user/profile}）。
     *
     * <p>出参直接复用 {@code AuthDtos.UserView}（登录响应里那个用户对象）——
     * 个人信息页要的字段是它的子集。头像额外换成**预签名 URL**：
     * 库里存的是对象名，直接返出去前端 {@code <img src>} 加载不了。
     *
     * <p>⚠️ <b>用 {@link #loadReadable} 而不是 {@link #load}</b>（B2 的 W7）：
     * 本接口是**布局壳启动即调**的（{@code BasicLayout.vue:118}），而
     * {@code ACCOUNT_DISABLED} 的 HTTP 状态是 **401** —— 前端 {@code request.ts:96}
     * 见 401 就清 token 跳登录页。所以停用账号一旦被拦在这里，就是
     * 「登录成功 → 立刻被踢回登录页 → 再登录」的死循环，"允许登录"根本兑现不了。
     */
    @Transactional(readOnly = true)
    public AuthDtos.UserView profile(Long userId) {
        UserPrincipalAssembler.Principal principal = assembler.assemble(loadReadable(userId));
        AuthDtos.UserView view = assembler.toUserView(principal);
        return view.withAvatar(avatarStorage.presignedUrl(view.avatar()));
    }

    /**
     * 更新基本资料（{@code PUT /user/profile}）。
     *
     * <p>四个字段都是"传了才改"：前端是整表单提交，用户只改昵称时其它字段会带原值；
     * 传 {@code null} 的语义是**不动它**，不是清空它（清空靠传空串）。
     * 这条口径必须写死，否则"提交表单时某个字段恰好为 undefined"会静默清掉数据。
     *
     * <p>邮箱改了要查唯一性，且必须**排除自己** —— 用户把邮箱原样提交回来时，
     * 通用查询会把"自己"算成冲突。
     */
    @Transactional
    @AuditLog(module = AuditModule.USER, action = "修改个人资料")
    public UserDtos.ProfileUpdateResponse updateProfile(Long userId, UserDtos.UpdateProfileRequest request) {
        SysUser user = load(userId);

        if (request.realName() != null && !request.realName().isBlank()) {
            user.setRealName(request.realName().trim());
        }
        if (request.email() != null) {
            String email = request.email().isBlank() ? null : request.email().trim();
            if (email != null && userRepository.existsByEmailAndIdNotAndDeletedAtIsNull(email, userId)) {
                throw BizException.of(ErrorCode.EMAIL_EXISTS);
            }
            user.setEmail(email);
        }
        if (request.position() != null) {
            user.setPosition(request.position().isBlank() ? null : request.position().trim());
        }
        if (request.intro() != null) {
            user.setIntro(request.intro().isBlank() ? null : request.intro().trim());
        }
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("个人资料已更新：userId={}", userId);
        return new UserDtos.ProfileUpdateResponse(true);
    }

    /**
     * 上传头像（{@code POST /user/avatar}，{@code multipart/form-data}）。
     *
     * <p><b>请求侧依赖前端 A-1 的修复（2026-09-13 已落地）</b>：原先
     * {@code api/index.ts} 的 {@code userApi.avatar(form: FormData)} 走 {@code http.post}，
     * 而 {@code http.post} 会 {@code JSON.stringify(body)} —— FormData 被序列化成
     * {@code "{}"}、{@code Content-Type} 也被设成 {@code application/json}，
     * 于是真后端下这个请求**到不了本方法**。根因是 {@code request.ts} 里
     * {@code isForm = headers instanceof FormData} 是一行死代码（{@code headers}
     * 永远不是 FormData，{@code body} 才是）。
     *
     * <p>现在 {@code request()} 按 {@code body} 判断：是 FormData 就原样透传、
     * **不设 Content-Type**（multipart 的 boundary 只能由浏览器算）。
     * 后端这边始终按**规范形态**实现，没有为了兼容那个被毁掉的请求而留下第二种分支 ——
     * 兼容 {@code "{}"} 只能得到一个永远存不进图的上传接口。
     */
    @Transactional
    @AuditLog(module = AuditModule.USER, action = "更换头像")
    public UserDtos.AvatarResponse uploadAvatar(Long userId, MultipartFile file) {
        SysUser user = load(userId);
        String objectKey = avatarStorage.store(userId, file);
        user.setAvatar(objectKey);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        return new UserDtos.AvatarResponse(avatarStorage.presignedUrl(objectKey));
    }

    // ==================== T-111 · 改密 ====================

    /**
     * 修改密码（{@code POST /user/password}，🚩 高危）。
     *
     * <p><b>G1-8 的主要落点</b>：成功后
     * <ol>
     *   <li>撤销该用户**全部** refreshToken —— 只改哈希值的话，其它设备手上的
     *       refreshToken 还能继续换新令牌，"改密"就白改了；</li>
     *   <li>把**当前** accessToken 拉黑 —— 用户改密多半是因为怀疑泄露，
     *       当前会话没理由继续有效；</li>
     *   <li>前端紧接着会本地登出（{@code ProfileView.vue} 的成功回调），
     *       所以用户体验上就是"改完重新登录"。</li>
     * </ol>
     *
     * <p><b>为什么只能拉黑当前这一个 accessToken</b>：accessToken 是无状态的，
     * 服务端没有"某用户签发过哪些 jti"的索引，无法按用户批量拉黑其它设备的
     * accessToken（它们最长 2 小时自行过期；勾了"记住我"是 7 天）。
     * 这正是 refreshToken 被做成可撤销的原因 —— 攻击者的续期路径被切断了。
     * 若要把窗口压到 0，就该缩短 accessToken 的 TTL 并给前端接上自动刷新
     * （记在 {@code T-D01}），而不是在这里堆一个假的"全设备登出"。
     */
    @Transactional
    @AuditLog(module = AuditModule.AUTH, action = "修改密码", risk = RiskLevel.HIGH)
    public UserDtos.ChangePasswordResponse changePassword(Long userId,
                                                          AuthDtos.ChangePasswordRequest request,
                                                          String accessToken) {
        SysUser user = load(userId);
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw BizException.of(ErrorCode.OLD_PASSWORD_INVALID);
        }
        AuthService.assertPasswordStrength(request.newPassword());
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw BizException.of(ErrorCode.PASSWORD_UNCHANGED);
        }

        LocalDateTime now = LocalDateTime.now();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setPasswordUpdatedAt(now);
        // 用户自己改了密码，就不该再被"下次登录强制修改"拦一次
        user.setMustChangePassword(false);
        user.setUpdatedAt(now);
        userRepository.save(user);

        int revoked = refreshTokenStore.revokeAll(userId);
        revokeCurrentAccessToken(accessToken);
        loginLogService.record(userId, user.getUsername(), LoginMethod.PASSWORD,
                true, "修改密码，已撤销 " + revoked + " 个刷新令牌", null, null);
        log.info("密码修改完成：userId={} 已撤销 refreshToken={} 条", userId, revoked);
        return new UserDtos.ChangePasswordResponse(true, true);
    }

    /** 把当前请求的 accessToken 拉黑（TTL 取其剩余有效期，不浪费 Redis 空间）。 */
    private void revokeCurrentAccessToken(String accessToken) {
        LoginUser current = LoginUserContext.get();
        if (accessToken == null || accessToken.isBlank() || current == null || current.getTokenId() == null) {
            return;
        }
        tokenBlacklist.revoke(current.getTokenId(), jwtService.remainingSeconds(accessToken));
    }

    // ==================== T-111 · 换绑 ====================

    /**
     * 换绑手机号（{@code POST /user/change-phone}，🚩 高危）。
     *
     * <p><b>必须验码</b>：验证码以**新手机号**为目标发送
     * （{@code POST /auth/sms-code { phone: 新号, scene: 'bind' }}），
     * 校验通过才落库。不校验的版本等于"任何登录用户都能把账号绑到自己的手机上，
     * 之后用短信找回密码直接接管" —— 这是一个完整的接管链路，不能因为
     * "前端的『获取验证码』按钮还没接事件"就降级。
     *
     * <p>唯一性也要查，且排除自己（原样提交自己当前号码时不该报"已注册"）。
     */
    @Transactional
    @AuditLog(module = AuditModule.USER, action = "换绑手机号", risk = RiskLevel.HIGH)
    public UserDtos.ChangePhoneResponse changePhone(Long userId, UserDtos.ChangePhoneRequest request) {
        String phone = request.phone() == null ? "" : request.phone().replaceAll("\\s", "");
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "手机号格式不正确");
        }
        if (userRepository.existsByPhoneAndIdNotAndDeletedAtIsNull(phone, userId)) {
            throw BizException.of(ErrorCode.PHONE_EXISTS);
        }
        smsCodeService.verify(phone, SmsScene.BIND, request.code());

        SysUser user = load(userId);
        user.setPhone(phone);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("手机号已换绑：userId={}", userId);
        return new UserDtos.ChangePhoneResponse(true, UserPrincipalAssembler.maskPhone(phone));
    }

    /**
     * 换绑邮箱（{@code POST /user/change-email}，🚩 高危）。
     *
     * <p><b>v1 的口径：用"已绑手机号的短信验证码"证明身份，再设新邮箱</b>。
     * 为什么不用邮箱验证码：本项目 v1 **没有邮件通道**（《04》明确不做邮箱验证），
     * 而 {@code SmsCodeService} 是唯一的验证码设施。给一个"发不出去的验证码"
     * 设计流程只会得到一个永远走不通的接口。
     *
     * <p>因此验证码的目标是用户**当前绑定的手机号**（scene 同样是 {@code bind}）。
     * 没绑手机号的账号无法自助换邮箱 —— 这个限制是刻意的：邮箱在本项目里
     * 是弱标识（可用于登录，但不是找回凭证），没有第二因素可用时就不该放开改绑。
     */
    @Transactional
    @AuditLog(module = AuditModule.USER, action = "换绑邮箱", risk = RiskLevel.HIGH)
    public UserDtos.ChangeEmailResponse changeEmail(Long userId, UserDtos.ChangeEmailRequest request) {
        SysUser user = load(userId);
        String email = request.email() == null ? "" : request.email().trim();
        if (email.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "邮箱格式不正确");
        }
        if (userRepository.existsByEmailAndIdNotAndDeletedAtIsNull(email, userId)) {
            throw BizException.of(ErrorCode.EMAIL_EXISTS);
        }
        if (user.getPhone() == null || user.getPhone().isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "账号未绑定手机号，无法自助换绑邮箱，请联系管理员");
        }
        smsCodeService.verify(user.getPhone(), SmsScene.BIND, request.code());

        user.setEmail(email);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("邮箱已换绑：userId={}", userId);
        // 邮件通道未接入，"已验证"恒为 false —— 前端据此展示"待确认"文案
        return new UserDtos.ChangeEmailResponse(true, false);
    }

    // ==================== T-112 · 权限 / 安全 / 登录日志 ====================

    /**
     * 我的权限（{@code GET /user/permissions}）。
     *
     * <p><b>超管在这里返回 38 个具体码，而不是 {@code ["*"]}</b>。原因：
     * 这个接口的唯一消费方是个人信息页的「我的权限」页签，它把权限码
     * {@code split(':')[0]} 分组后逐个列成标签，并在页面上写明
     * "超级管理员拥有全部 38 个权限点"。返回 {@code ["*"]} 会让那一页
     * 只剩一个 {@code *} 标签。
     *
     * <p>两个口径并存不矛盾，因为它们回答的是两个问题：
     * <ul>
     *   <li>登录响应的 {@code permissions} 回答"**能不能**做" —— 通配符是最紧的表示；</li>
     *   <li>本接口回答"**有哪些**" —— 必须展开成枚举。</li>
     * </ul>
     * 这也是前端 mock 的既有口径（登录给 {@code ['*']}，本接口给全量码）。
     */
    @Transactional(readOnly = true)
    public UserDtos.PermissionsView permissions(Long userId) {
        Set<String> codes = permissionQueryRepository.findPermissionCodes(userId);
        List<String> effective = codes.contains(PermissionCode.WILDCARD)
                ? PermissionCode.allCodes()
                : new ArrayList<>(codes);
        return new UserDtos.PermissionsView(effective);
    }

    /**
     * 安全概览（{@code GET /user/security}）。
     *
     * <p>手机号 / 邮箱一律**脱敏**返回（{@code 138****0001}）：这个接口的用途是
     * "让用户确认自己绑的是哪个号"，给全量值没有额外价值，却把敏感信息
     * 放进了另一个可被读取的响应体里。
     *
     * <p>{@code mfaEnabled} 恒 {@code false}：TOTP 在 v1 只有前端展示
     * （{@code ProfileView.vue} 的二维码是占位图，密钥是写死的常量），
     * 后端没有 secret 表也没有校验逻辑。**不假装已实现** ——
     * 若返回 true，用户会以为自己被双因素保护着。
     */
    @Transactional(readOnly = true)
    public UserDtos.SecurityView security(Long userId) {
        // 只读视图，同 profile() 的理由走 loadReadable（W7）
        SysUser user = loadReadable(userId);

        List<SysLoginLog> recent = loginLogService.recentSuccess(userId, RECENT_DEVICE_LIMIT);
        List<UserDtos.RecentDeviceView> devices = new ArrayList<>(recent.size());
        for (int i = 0; i < recent.size(); i++) {
            SysLoginLog entry = recent.get(i);
            devices.add(new UserDtos.RecentDeviceView(
                    entry.getDevice() != null ? entry.getDevice() : entry.getUserAgent(),
                    entry.getIp(),
                    entry.getLocation(),
                    entry.getCreatedAt() == null ? null : TIME_FORMAT.format(entry.getCreatedAt()),
                    // 最近一次成功登录视作"当前设备"，见 RecentDeviceView 的说明
                    i == 0));
        }

        return new UserDtos.SecurityView(
                UserPrincipalAssembler.maskPhone(user.getPhone()),
                UserPrincipalAssembler.maskEmail(user.getEmail()),
                false,
                user.getPasswordUpdatedAt() == null ? null : TIME_FORMAT.format(user.getPasswordUpdatedAt()),
                devices);
    }

    /** 我的登录日志（{@code GET /user/login-logs}）。字段与分页口径见 {@code LoginLogService#page}。 */
    public LoginLogService.PageResult loginLogs(Long userId, int page, int pageSize) {
        return loginLogService.page(userId, page, pageSize);
    }

    // ==================== 内部 ====================

    /** 取当前有效用户；不存在（被删）时按未登录处理，避免拿一个已删账号继续操作。 */
    private SysUser load(Long userId) {
        SysUser user = loadReadable(userId);
        if (user.getStatus() == UserStatus.DISABLED) {
            throw BizException.of(ErrorCode.ACCOUNT_DISABLED);
        }
        return user;
    }

    /**
     * 取用户的**只读**视角：不因账号被停用而拒绝。
     *
     * <p><b>「停用」在本仓的语义 = 只读 + 知识库不可见</b>（B2 的 W7）。需求
     * 《03》§4.1 /《04》§1.4 /《05》§1.5 只规定了三件事：允许登录、所有知识库不可见、
     * 界面给出明确说明。第三条决定了账号必须**能留在应用里**看到那句说明 ——
     * 而布局壳启动即调 {@code GET /user/profile}，把它拦掉就会因为
     * {@code ACCOUNT_DISABLED} 是 401 而被前端清 token 踢回登录页，形成
     * 「登录成功 → 立刻被踢 → 再登录」的死循环（见 {@link #profile} 的注释）。
     *
     * <p>所以自助服务按**读写分界**：{@code profile} / {@code security} 这类只读视图
     * 走本方法；改资料 / 换头像 / 改密码 / 换绑手机邮箱这类**写**操作仍走
     * {@link #load} 拦截。需求没有规定写侧，取"停用即不可改"是收紧的方向。
     */
    private SysUser loadReadable(Long userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> BizException.of(ErrorCode.USER_NOT_FOUND));
    }
}
