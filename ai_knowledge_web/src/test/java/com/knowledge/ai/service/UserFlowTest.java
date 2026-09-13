package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.PermissionCode;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1 用户域端到端验收（M01 · T-108 忘记密码 / T-111 个人信息 / T-112 权限·安全·日志）。
 *
 * <p>与 {@code AuthFlowTest} 的分工：那个类验"能不能进来"（登录、刷新、注册、风控），
 * 本类验"进来之后能对自己做什么"。两者共用 {@link ApiTestSupport}。
 *
 * <h3>为什么这些用例必须走真服务</h3>
 * <p>本类覆盖的三条链路都是"看起来对、实际不通"的高发区：
 * <ul>
 *   <li><b>忘记密码</b>：两步之间有一次性凭证，凭证是否真被消费、Redis 键是否真删掉，
 *       只有跑真的 Redis 才知道；</li>
 *   <li><b>改密连带失效（G1-8）</b>：要断言旧 refreshToken 真的换不到新令牌、
 *       旧 accessToken 真的进黑名单 —— 这两件事都发生在 Redis 里；</li>
 *   <li><b>头像上传</b>：multipart 参数名与 MinIO 对象名，mock 测不出来。</li>
 * </ul>
 *
 * <h3>用例隔离</h3>
 * <p>本类的用例一律用 {@link ApiTestSupport#createUser} 自己造账号，
 * **不碰 {@code admin}** —— 改密、换绑这类操作会改掉账号本身的状态，
 * 共用一个账号会让用例之间产生隐藏的先后依赖（还可能在并行执行时互相破坏）。
 * 唯一读 {@code admin} 的地方是"超管权限展开"，那里只读不写。
 */
class UserFlowTest extends ApiTestSupport {

    private static final int CODE_SMS_CODE_INVALID = 10016;
    private static final int CODE_PASSWORD_WEAK = 10019;
    private static final int CODE_BAD_CREDENTIALS = 10015;
    private static final int CODE_PHONE_EXISTS = 10032;
    private static final int CODE_EMAIL_EXISTS = 10033;
    private static final int CODE_FILE_TYPE_UNSUPPORTED = 10034;
    private static final int CODE_REFRESH_TOKEN_INVALID = 10018;
    private static final int CODE_RESET_TOKEN_INVALID = 10054;
    private static final int CODE_OLD_PASSWORD_INVALID = 10055;
    private static final int CODE_PASSWORD_UNCHANGED = 10056;

    /** 头像用例用的 1×1 PNG（最小合法图片，免得仓库里多一个二进制夹具文件）。 */
    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static final String NEW_PASSWORD = "NewPass@6789";

    // ==================================================================
    // T-108 · 忘记密码（两步）
    // ==================================================================

    @Test
    @DisplayName("T-108 忘记密码全流程：验码换凭证 → 换密码 → 新密码可登录、旧密码失效")
    void forgotPasswordFullFlow() {
        TestUser user = createUser("forgot");

        RawResponse verified = post("/api/auth/verify-reset", Map.of(
                "username", user.username(), "phone", user.phone(), "smsCode", FIXED_SMS_CODE));
        verified.assertOk("第一步 /auth/verify-reset");

        // D-8：文档字段名 resetToken 与前端消费的 token 必须同时存在
        String resetToken = verified.data("$.resetToken");
        assertNotNull(resetToken, "缺少《10》§37 的 resetToken");
        assertEquals(resetToken, verified.data("$.token"), "resetToken 与 token 必须是同一张凭证（双写）");
        assertEquals(masked(user.phone()), verified.data("$.maskedPhone"), "应回显脱敏手机号供页面展示");

        RawResponse reset = post("/api/auth/reset-password",
                Map.of("token", resetToken, "password", NEW_PASSWORD));
        reset.assertOk("第二步 /auth/reset-password");
        assertEquals(Boolean.TRUE, reset.data("$.reset"));

        // 新密码可登录
        assertEquals(CODE_OK, login(user.username(), NEW_PASSWORD).code(), "重置后应能用新密码登录");
        // 旧密码不再可用（且仍是 401 的传输层口径，前端据此清 token）
        RawResponse oldPassword = login(user.username(), user.password());
        assertEquals(CODE_BAD_CREDENTIALS, oldPassword.code(), "旧密码必须失效");
        assertEquals(401, oldPassword.status());
    }

    @Test
    @DisplayName("T-108 重置凭证一次性：同一张 resetToken 第二次提交必然失败")
    void resetTokenIsSingleUse() {
        TestUser user = createUser("forgot.once");
        String token = post("/api/auth/verify-reset", Map.of(
                "username", user.username(), "phone", user.phone(), "smsCode", FIXED_SMS_CODE))
                .assertOk("第一步")
                .data("$.resetToken");

        post("/api/auth/reset-password", Map.of("token", token, "password", NEW_PASSWORD))
                .assertOk("第一次使用凭证");

        RawResponse reuse = post("/api/auth/reset-password",
                Map.of("token", token, "password", "Another@6789"));
        assertEquals(CODE_RESET_TOKEN_INVALID, reuse.code(),
                "凭证已被消费，第二次必须拒绝，实际：" + reuse.body());
    }

    @Test
    @DisplayName("T-108 重置接口同时接受《10》的 {resetToken,newPassword} 与前端字段名")
    void resetPasswordAcceptsDocumentFieldNames() {
        TestUser user = createUser("forgot.doc");
        String token = post("/api/auth/verify-reset", Map.of(
                "username", user.username(), "phone", user.phone(), "smsCode", FIXED_SMS_CODE))
                .assertOk("第一步")
                .data("$.resetToken");

        // 这里刻意只用文档字段名，验证"超集 + 双字段"策略的另一半
        post("/api/auth/reset-password",
                Map.of("resetToken", token, "newPassword", NEW_PASSWORD))
                .assertOk("用《10》§38 的字段名重置");
        assertEquals(CODE_OK, login(user.username(), NEW_PASSWORD).code());
    }

    @Test
    @DisplayName("T-108 密码强度校验发生在消费凭证之前（弱密码不浪费这张凭证）")
    void weakPasswordDoesNotConsumeResetToken() {
        TestUser user = createUser("forgot.weak");
        String token = post("/api/auth/verify-reset", Map.of(
                "username", user.username(), "phone", user.phone(), "smsCode", FIXED_SMS_CODE))
                .assertOk("第一步")
                .data("$.resetToken");

        RawResponse weak = post("/api/auth/reset-password", Map.of("token", token, "password", "12345678"));
        assertEquals(CODE_PASSWORD_WEAK, weak.code(), "纯数字密码应被强度校验拦下，实际：" + weak.body());

        // 关键断言：凭证没被消费，用户不必重新走一遍验证码
        post("/api/auth/reset-password", Map.of("token", token, "password", NEW_PASSWORD))
                .assertOk("弱密码被拒后，同一张凭证仍应可用");
        assertEquals(CODE_OK, login(user.username(), NEW_PASSWORD).code());
    }

    @Test
    @DisplayName("T-108 新旧密码相同时返回 PASSWORD_UNCHANGED")
    void resetPasswordRejectsUnchangedPassword() {
        TestUser user = createUser("forgot.same");
        String token = post("/api/auth/verify-reset", Map.of(
                "username", user.username(), "phone", user.phone(), "smsCode", FIXED_SMS_CODE))
                .assertOk("第一步")
                .data("$.resetToken");

        RawResponse same = post("/api/auth/reset-password",
                Map.of("token", token, "password", user.password()));
        assertEquals(CODE_PASSWORD_UNCHANGED, same.code(), "不应把密码重设成同一个，实际：" + same.body());
    }

    @Test
    @DisplayName("T-108 无效 / 伪造的 resetToken 一律按 RESET_TOKEN_INVALID 拒绝")
    void resetPasswordRejectsUnknownToken() {
        RawResponse response = post("/api/auth/reset-password",
                Map.of("token", "forged." + System.nanoTime(), "password", NEW_PASSWORD));
        assertEquals(CODE_RESET_TOKEN_INVALID, response.code());
    }

    @Test
    @DisplayName("T-108 短信验证码错误时不签发凭证（且不泄露账号是否存在）")
    void verifyResetRejectsWrongSmsCode() {
        TestUser user = createUser("forgot.code");
        RawResponse response = post("/api/auth/verify-reset", Map.of(
                "username", user.username(), "phone", user.phone(), "smsCode", "111111"));
        assertEquals(CODE_SMS_CODE_INVALID, response.code(),
                "验证码错应停在第一步，拿不到任何凭证：" + response.body());
    }

    // ==================================================================
    // G1-8 · 改密撤销全部会话
    // ==================================================================

    @Test
    @DisplayName("G1-8 修改密码后：全部 refreshToken 失效、当前 accessToken 被拉黑、新密码可登录")
    void changePasswordRevokesAllSessions() {
        TestUser user = createUser("g18");

        // 造"第二台设备"：再登录一次，拿到另一个 refreshToken。
        // 只撤销当前这一个的话，其它设备仍能续期，"改密"就是假的。
        String secondDeviceRefresh = login(user.username(), user.password()).data("$.refreshToken");
        assertNotEquals(user.refreshToken(), secondDeviceRefresh, "两次登录应拿到不同的 refreshToken");

        RawResponse changed = post("/api/user/password",
                Map.of("oldPassword", user.password(), "newPassword", NEW_PASSWORD), user.accessToken());
        changed.assertOk("修改密码");
        assertEquals(Boolean.TRUE, changed.data("$.changed"));
        assertEquals(Boolean.TRUE, changed.data("$.reloginRequired"), "改密后必然要求重新登录");

        // ① 两个 refreshToken 都换不到新令牌
        assertEquals(CODE_REFRESH_TOKEN_INVALID,
                post("/api/auth/refresh", Map.of("refreshToken", user.refreshToken())).code(),
                "当前设备的 refreshToken 应失效");
        assertEquals(CODE_REFRESH_TOKEN_INVALID,
                post("/api/auth/refresh", Map.of("refreshToken", secondDeviceRefresh)).code(),
                "其它设备的 refreshToken 也必须失效（这是 G1-8 的重点）");

        // ② 当前 accessToken 进黑名单 → 401
        assertEquals(401, get("/api/user/permissions", user.accessToken()).status(),
                "改密后当前 accessToken 应被拉黑");

        // ③ 新密码可用、旧密码不可用
        assertEquals(CODE_OK, login(user.username(), NEW_PASSWORD).code(), "新密码应可登录");
        assertEquals(CODE_BAD_CREDENTIALS, login(user.username(), user.password()).code(), "旧密码应失效");
    }

    @Test
    @DisplayName("T-111 修改密码：原密码错误 / 新旧相同分别返回 10055 / 10056")
    void changePasswordRejectsBadInput() {
        TestUser user = createUser("chpwd");

        RawResponse wrongOld = post("/api/user/password",
                Map.of("oldPassword", "WrongOld@123", "newPassword", NEW_PASSWORD), user.accessToken());
        assertEquals(CODE_OLD_PASSWORD_INVALID, wrongOld.code(), "实际：" + wrongOld.body());
        assertEquals(200, wrongOld.status(), "业务错误一律 HTTP 200");

        RawResponse same = post("/api/user/password",
                Map.of("oldPassword", user.password(), "newPassword", user.password()), user.accessToken());
        assertEquals(CODE_PASSWORD_UNCHANGED, same.code(), "实际：" + same.body());

        // 两次失败都不该动到会话：原密码依然能登录
        assertEquals(CODE_OK, login(user.username(), user.password()).code());
    }

    // ==================================================================
    // T-111 · 个人资料
    // ==================================================================

    @Test
    @DisplayName("T-111 GET /user/profile 返回个人信息页消费的字段（含派生部门名）")
    void profileReturnsFrontendContractFields() {
        TestUser user = createUser("profile");

        RawResponse profile = get("/api/user/profile", user.accessToken()).assertOk("查个人资料");
        assertEquals(user.username(), profile.data("$.username"));
        assertEquals("测试用户", profile.data("$.realName"));
        assertEquals(user.phone(), profile.data("$.phone"));
        // department 是部门全路径名（派生值，不落库）；新注册用户归属「未分配」
        assertEquals("未分配", profile.data("$.department"));
        assertEquals(0L, ((Number) profile.data("$.deptId")).longValue());
        assertEquals(List.of("USER"), profile.data("$.roles"));
        assertNotNull(profile.data("$.status"));
        // 没设过头像，avatar 应为空而不是报错
        assertNull(profile.data("$.avatar"));
    }

    @Test
    @DisplayName("T-111 PUT /user/profile 是「传了才改」：未传的字段保持原值")
    void updateProfileKeepsFieldsNotSent() {
        TestUser user = createUser("profile.patch");

        RawResponse first = put("/api/user/profile", Map.of("realName", "改名后的我"), user.accessToken());
        first.assertOk("只改姓名");
        assertEquals(Boolean.TRUE, first.data("$.saved"));

        RawResponse second = put("/api/user/profile", Map.of("position", "后端工程师", "intro", "关注 RAG"), user.accessToken());
        second.assertOk("只改职位与简介");

        RawResponse profile = get("/api/user/profile", user.accessToken()).assertOk("回读");
        assertEquals("改名后的我", profile.data("$.realName"), "第二次提交没带 realName，不该被清掉");
        assertEquals("后端工程师", profile.data("$.position"));
        assertEquals("关注 RAG", profile.data("$.intro"));
    }

    @Test
    @DisplayName("T-111 邮箱唯一性校验必须排除自己（原样提交自己的邮箱不应报冲突）")
    void updateProfileEmailUniquenessExcludesSelf() {
        TestUser user = createUser("profile.email");

        String email = user.username() + "@example.com";
        put("/api/user/profile", Map.of("email", email), user.accessToken()).assertOk("首次设置邮箱");
        // 前端是整表单提交，用户只改昵称时会把同一个邮箱带回来 —— 这必须成功
        put("/api/user/profile", Map.of("realName", "换个名字", "email", email), user.accessToken())
                .assertOk("原样提交自己的邮箱");

        RawResponse taken = put("/api/user/profile", Map.of("email", ADMIN_EMAIL), user.accessToken());
        assertEquals(CODE_EMAIL_EXISTS, taken.code(), "占用他人邮箱应被拒，实际：" + taken.body());
    }

    @Test
    @DisplayName("T-111 头像上传：落库存对象名，出参是可访问的预签名 URL")
    void avatarUploadStoresObjectKeyAndReturnsUrl() {
        TestUser user = createUser("avatar");

        RawResponse uploaded = postMultipart("/api/user/avatar", TINY_PNG, "me.png", "image/png", user.accessToken());
        uploaded.assertOk("上传头像");
        String url = uploaded.data("$.url");
        assertNotNull(url);
        assertTrue(url.startsWith("http"), "出参应是可直接给 <img src> 用的预签名 URL，实际：" + url);

        // 库里必须是对象名（不是 URL）——存 URL 等于把"以后迁移全表"写进设计
        String stored = jdbc.queryForObject(
                "select avatar from sys_user where username = ?", String.class, user.username());
        assertNotNull(stored);
        assertTrue(stored.startsWith("avatars/"), "sys_user.avatar 应存对象名，实际：" + stored);

        // 资料接口读出来时应已换成 URL
        String fromProfile = get("/api/user/profile", user.accessToken()).assertOk("回读资料").data("$.avatar");
        assertTrue(fromProfile.startsWith("http"), "资料页的头像应是预签名 URL，实际：" + fromProfile);
    }

    @Test
    @DisplayName("T-111 头像只接受图片类型")
    void avatarRejectsNonImage() {
        TestUser user = createUser("avatar.bad");
        RawResponse response = postMultipart("/api/user/avatar",
                "not an image".getBytes(), "evil.txt", "text/plain", user.accessToken());
        assertEquals(CODE_FILE_TYPE_UNSUPPORTED, response.code(), "实际：" + response.body());
    }

    // ==================================================================
    // T-111 · 换绑
    // ==================================================================

    @Test
    @DisplayName("T-111 换绑手机号必须验码，成功后可用新号登录")
    void changePhoneRequiresSmsCodeAndRebinds() {
        TestUser user = createUser("phone");
        String newPhone = uniquePhone();

        RawResponse changed = post("/api/user/change-phone",
                Map.of("phone", newPhone, "code", FIXED_SMS_CODE), user.accessToken());
        changed.assertOk("换绑手机号");
        assertEquals(Boolean.TRUE, changed.data("$.changed"));
        assertEquals(masked(newPhone), changed.data("$.phone"), "回显应是脱敏后的新号");

        // 账号支持用手机号登录，换绑后新号应可直接登录
        assertEquals(CODE_OK, login(newPhone, user.password()).code(), "换绑后应能用新手机号登录");

        // 验证码错 → 拒绝
        RawResponse badCode = post("/api/user/change-phone",
                Map.of("phone", uniquePhone(), "code", "111111"), user.accessToken());
        assertEquals(CODE_SMS_CODE_INVALID, badCode.code(), "实际：" + badCode.body());

        // 占用他人手机号 → 拒绝（唯一性校验先于验码）
        RawResponse taken = post("/api/user/change-phone",
                Map.of("phone", ADMIN_PHONE, "code", FIXED_SMS_CODE), user.accessToken());
        assertEquals(CODE_PHONE_EXISTS, taken.code(), "实际：" + taken.body());
    }

    @Test
    @DisplayName("T-111 换绑邮箱用已绑手机号的验证码证明身份，v1 恒为「未验证」")
    void changeEmailRequiresSmsVerification() {
        TestUser user = createUser("email");
        String newEmail = user.username() + "@corp.example";

        RawResponse changed = post("/api/user/change-email",
                Map.of("email", newEmail, "code", FIXED_SMS_CODE), user.accessToken());
        changed.assertOk("换绑邮箱");
        assertEquals(Boolean.TRUE, changed.data("$.changed"));
        // v1 没有邮件通道，不能谎报"已验证"
        assertEquals(Boolean.FALSE, changed.data("$.emailVerified"));

        // 安全概览里应看到脱敏后的新邮箱
        String maskedEmail = get("/api/user/security", user.accessToken())
                .assertOk("安全概览").data("$.email");
        assertTrue(maskedEmail.contains("***"), "邮箱必须脱敏展示，实际：" + maskedEmail);
        assertFalse(maskedEmail.equals(newEmail), "不应返回邮箱明文");
    }

    // ==================================================================
    // T-112 · 权限
    // ==================================================================

    @Test
    @DisplayName("T-112 /user/permissions 对超管展开成 38 个具体码，普通用户只拿自己那 9 个")
    void permissionsExpandSuperAdminToAllCodes() {
        List<String> adminCodes = get("/api/user/permissions", loginAsAdmin().data("$.accessToken"))
                .assertOk("超管权限")
                .data("$.codes");

        assertEquals(38, adminCodes.size(),
                "超管必须展开成 38 个码（页面上要按域分组列出），实际 " + adminCodes.size() + " 个");
        assertEquals(Set.copyOf(PermissionCode.allCodes()), Set.copyOf(adminCodes));
        assertFalse(adminCodes.contains(PermissionCode.WILDCARD),
                "这是展示视图，返回 * 会让那一页只剩一个标签");

        TestUser user = createUser("perm");
        List<String> userCodes = get("/api/user/permissions", user.accessToken())
                .assertOk("普通用户权限").data("$.codes");
        assertEquals(9, userCodes.size(), "USER 角色在 V2 里授予 9 个权限点，实际：" + userCodes);
        assertTrue(userCodes.contains("kb:view"));
        assertTrue(PermissionCode.allCodes().containsAll(userCodes), "普通用户的码必须是全量码的子集");
    }

    // ==================================================================
    // T-112 · 安全概览
    // ==================================================================

    @Test
    @DisplayName("T-112 /user/security 脱敏手机与邮箱、mfa 恒 false、带密码更新时间与最近设备")
    void securityViewMasksContactsAndReportsPasswordAge() {
        TestUser user = createUser("security");

        RawResponse security = get("/api/user/security", user.accessToken()).assertOk("安全概览");

        assertEquals(masked(user.phone()), security.data("$.phone"), "手机号必须脱敏");
        assertNull(security.data("$.email"), "新注册用户没绑邮箱");
        // TOTP 在 v1 只有前端占位图，后端没有校验逻辑 —— 不能谎报已开启
        assertEquals(Boolean.FALSE, security.data("$.mfaEnabled"), "mfaEnabled 必须如实为 false");
        assertNotNull(security.data("$.passwordUpdatedAt"), "注册时写入的密码更新时间应能读出来");

        List<?> devices = security.data("$.recentDevices");
        assertFalse(devices.isEmpty(), "刚登录过，最近设备不应为空");
        assertEquals(Boolean.TRUE, security.data("$.recentDevices[0].current"),
                "最近一次成功登录视作当前设备");
    }

    // ==================================================================
    // T-112 · 登录日志
    // ==================================================================

    @Test
    @DisplayName("T-112 /user/login-logs 分页可用，且 pageSize / size 两个参数名都收")
    void loginLogsArePagedAndAcceptBothSizeNames() {
        TestUser user = createUser("logs");

        RawResponse firstPage = get("/api/user/login-logs?page=1&pageSize=1", user.accessToken())
                .assertOk("登录日志（pageSize）");
        assertTrue(((Number) firstPage.data("$.total")).longValue() >= 2,
                "注册 + 登录至少产生 2 条记录，实际：" + firstPage.data("$.total"));
        assertEquals(1, ((Number) firstPage.data("$.page")).intValue());
        assertEquals(1, ((Number) firstPage.data("$.pageSize")).intValue());
        assertEquals(1, ((List<?>) firstPage.data("$.list")).size(), "pageSize=1 应只回 1 条");

        // 前端传 pageSize，《10》§58 写 size —— 两个名字都必须收
        RawResponse alias = get("/api/user/login-logs?page=1&size=5", user.accessToken())
                .assertOk("登录日志（size）");
        assertEquals(5, ((Number) alias.data("$.pageSize")).intValue(), "size 应被当作 pageSize 生效");

        // 行字段是前端列表列直接渲染的
        RawResponse rows = get("/api/user/login-logs?page=1&pageSize=10", user.accessToken()).assertOk("取全量");
        Map<String, Object> row = rows.data("$.list[0]");
        assertNotNull(row.get("method"), "method 应是中文展示文案");
        assertNotNull(row.get("result"));
        assertNotNull(row.get("time"));
    }

    // ==================================================================
    // 鉴权边界
    // ==================================================================

    @Test
    @DisplayName("T-111 /api/user/** 未带令牌一律 401（不落到业务逻辑）")
    void userEndpointsRequireAuthentication() {
        assertEquals(401, get("/api/user/profile").status());
        assertEquals(401, get("/api/user/permissions").status());
        assertEquals(401, put("/api/user/profile", Map.of("realName", "匿名"), null).status());
        assertEquals(401, post("/api/user/password",
                Map.of("oldPassword", "x", "newPassword", NEW_PASSWORD), null).status());
    }
}
