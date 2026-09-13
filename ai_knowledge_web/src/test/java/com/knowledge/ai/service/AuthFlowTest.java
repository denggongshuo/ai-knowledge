package com.knowledge.ai.service;

import com.jayway.jsonpath.JsonPath;
import com.knowledge.ai.core.security.CaptchaStore;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S1 认证链路端到端验收（M01 · 门禁 G1-1 ~ G1-7）。
 *
 * <p>本类只保留**认证用例本身**；起服务、发请求、解析响应、造账号等能力
 * 统一在 {@link ApiTestSupport}（S1 的另一个测试类 {@code UserFlowTest} 共用同一套）。
 *
 * <p>测试用的种子账号是 V2 里建的 {@code admin}（明文密码 {@code Admin@123}，
 * 与前端 mock 的演示口令一致）。
 */
class AuthFlowTest extends ApiTestSupport {

    private static final int CODE_BAD_CREDENTIALS = 10015;
    private static final int CODE_REFRESH_TOKEN_INVALID = 10018;
    private static final int CODE_USERNAME_EXISTS = 10031;
    private static final int CODE_SMS_SCENE_INVALID = 10037;
    private static final int CODE_CAPTCHA_REQUIRED = 10051;
    private static final int CODE_LOGIN_LOCKED = 10052;
    private static final int CODE_SMS_LOGIN_UNSUPPORTED = 10053;

    // ==================================================================
    // G1-1 · 密码登录能拿到令牌与用户信息
    // ==================================================================

    @Test
    @DisplayName("G1-1 密码登录返回 accessToken / user / roles / permissions（字段与前端契约一致）")
    void loginReturnsContractFields() {
        RawResponse response = loginAsAdmin();

        assertEquals(200, response.status(), "业务成功应返回 HTTP 200");
        assertNotNull(response.data("$.accessToken"), "缺少 accessToken");
        assertNotNull(response.data("$.refreshToken"), "缺少 refreshToken（前端 LoginResult 声明了它）");
        assertEquals("Bearer", response.data("$.tokenType"));
        assertEquals(ADMIN, response.data("$.user.username"));
        assertEquals("系统管理员", response.data("$.user.realName"));
        // 超管拿的是通配权限（PermissionCode.WILDCARD），不是 38 个具体码
        assertEquals(List.of("*"), response.data("$.permissions"));
        assertTrue(((List<?>) response.data("$.roles")).size() >= 1, "至少要有一个角色");
        assertEquals("SUPER_ADMIN", response.data("$.roles[0].code"));
        assertEquals("超级管理员", response.data("$.roles[0].name"));
        // traceId 必须存在（G0-2 的统一响应体在 S1 依然成立）
        assertNotNull(response.traceId());
    }

    // ==================================================================
    // G1-2 · refreshToken 进 HttpOnly Cookie
    // ==================================================================

    @Test
    @DisplayName("G1-2 refreshToken 落在 HttpOnly Cookie 中（前端 document.cookie 读不到）")
    void refreshTokenIsHttpOnlyCookie() {
        RawResponse response = loginAsAdmin();

        List<String> cookies = response.setCookies();
        assertFalse(cookies.isEmpty(), "登录响应必须带 Set-Cookie");

        String refreshCookie = cookies.stream()
                .filter(value -> value.startsWith("refreshToken="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Set-Cookie 中没有 refreshToken：" + cookies));

        assertTrue(refreshCookie.contains("HttpOnly"), "refreshToken 必须带 HttpOnly：" + refreshCookie);
        assertTrue(refreshCookie.contains("Path=/api/auth"), "作用域应限定在认证路径：" + refreshCookie);
        assertTrue(refreshCookie.contains("SameSite=Lax"), "缺 SameSite：" + refreshCookie);
    }

    // ==================================================================
    // G1-4 · 账号枚举防护
    // ==================================================================

    @Test
    @DisplayName("G1-4 密码错误与账号不存在返回完全一致的提示（不泄露账号是否存在）")
    void loginDoesNotLeakAccountExistence() {
        // 必须先造一个**真实存在**的账号。拿两个都不存在的账号互比是恒真断言 ——
        // 它们走的是同一条分支，永远相等，测不出任何东西。
        TestUser existing = createUser("enum.probe");

        RawResponse wrongPassword = login(existing.username(), "WrongPassword@1");
        RawResponse noSuchAccount = login("enum.absent." + System.nanoTime(), "WrongPassword@1");

        assertEquals(CODE_BAD_CREDENTIALS, wrongPassword.code(), "真实账号 + 错密码应为凭证错误");
        assertEquals(CODE_BAD_CREDENTIALS, noSuchAccount.code(), "不存在的账号也应是同一个凭证错误");
        assertEquals(wrongPassword.message(), noSuchAccount.message(),
                "两条失败路径的文案必须逐字相同，否则可被用来枚举账号");
        // 这里**是 401 而不是 200**：1001x 里「凭证本身有问题」的项与 1002x 是
        // ErrorCode 中唯二的传输层例外（见 ErrorCode 的 HTTP 口径说明）。
        // 前端 request.ts 正是靠 status===401 清 token 并跳登录页，
        // 若把登录失败改成 HTTP 200，前端的这条兜底就永远不触发。
        assertEquals(401, wrongPassword.status(), "凭证错误属传输层 401，不是业务 200");
        assertEquals(401, noSuchAccount.status());
    }

    // ==================================================================
    // G1-5 · 暴力破解防护
    // ==================================================================

    @Test
    @DisplayName("G1-5 连续失败 3 次后必须出示图形验证码，5 次后锁定")
    void bruteForceProtectionKicksIn() {
        String account = "lock.probe." + System.currentTimeMillis();

        try {
            // 前 3 次失败不要求验证码（否则首次登录的用户会莫名被拦）
            for (int i = 1; i <= LoginAttemptGuard.CAPTCHA_AT; i++) {
                RawResponse response = login(account, "WrongPassword@1");
                assertEquals(CODE_BAD_CREDENTIALS, response.code(), "第 " + i + " 次应为密码错误");
            }

            // 已达阈值，且没带验证码 → 明确要求先过验证码
            RawResponse needCaptcha = login(account, "WrongPassword@1");
            assertEquals(CODE_CAPTCHA_REQUIRED, needCaptcha.code(),
                    "连续失败达阈值后应要求图形验证码，实际：" + needCaptcha.body());

            // 继续试错到 5 次：**必须带一张真实有效的验证码**才能走到密码校验那一步。
            //
            // 这里直接向 CaptchaStore 要一张图（它把 code 一并返回），而不是塞一个
            // 不存在的 captchaId —— 后者会在验证码环节就被拒，而 CAPTCHA_INVALID
            // **不计入**失败次数（刻意的：验证码错不等于密码错，计进去会让输错图的
            // 正常用户被连坐锁定）。于是计数永远卡在 3，锁定这条链路就测不到了。
            for (int i = 0; i < 2; i++) {
                CaptchaStore.Captcha captcha = captchaStore.generate();
                RawResponse response = post("/api/auth/login", Map.of(
                        "account", account, "password", "WrongPassword@1",
                        "captchaId", captcha.captchaId(), "captchaCode", captcha.code()));
                assertEquals(CODE_BAD_CREDENTIALS, response.code(),
                        "带有效验证码的第 " + (i + 1) + " 次试错应仍为密码错误：" + response.body());
            }

            // 累计 5 次 → 锁定
            RawResponse locked = login(account, "WrongPassword@1");
            assertEquals(CODE_LOGIN_LOCKED, locked.code(),
                    "连续失败 5 次后应锁定，实际：" + locked.body());
        } finally {
            cleanupRedisKeys("auth:fail:count:" + account, "auth:fail:lock:" + account);
        }
    }

    // ==================================================================
    // G1-3 · 刷新去重（并发 10 次只产生 1 次真实刷新）
    // ==================================================================

    @Test
    @DisplayName("G1-3 并发 10 次 /auth/refresh 只产生 1 次真实轮换，且都拿到同一对新令牌")
    void concurrentRefreshOnlyRotatesOnce() throws Exception {
        String refreshToken = loginAsAdmin().data("$.refreshToken");

        int concurrency = 10;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        try {
            List<Callable<String>> tasks = new ArrayList<>(concurrency);
            for (int i = 0; i < concurrency; i++) {
                tasks.add(() -> post("/api/auth/refresh", Map.of("refreshToken", refreshToken)).body());
            }
            List<Future<String>> futures = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);

            Set<String> newRefreshTokens = new HashSet<>();
            Set<String> newAccessTokens = new HashSet<>();
            for (Future<String> future : futures) {
                String body = future.get();
                int code = JsonPath.read(body, "$.code");
                assertEquals(CODE_OK, code, "并发刷新不应失败，实际响应：" + body);
                newRefreshTokens.add(JsonPath.read(body, "$.data.refreshToken"));
                newAccessTokens.add(JsonPath.read(body, "$.data.accessToken"));
            }

            assertEquals(1, newRefreshTokens.size(),
                    "10 个并发调用必须拿到**同一个**新 refreshToken，实际拿到 " + newRefreshTokens.size() + " 个");
            assertEquals(1, newAccessTokens.size(),
                    "新 accessToken 也应一致（同一对令牌）");
            assertNotEquals(refreshToken, newRefreshTokens.iterator().next(), "轮换后必须是新令牌");
        } finally {
            pool.shutdownNow();
        }

        // 旧 refreshToken 的处置需要分清两层，这是并发去重的真实代价：
        //   · 宽限窗口（30s）内旧 token 仍能换到**同一对**新令牌 —— 这正是"10 并发不互相踢下线"的实现方式；
        //   · 窗口过后，旧 token 才真正作废。
        // 这里不能"等 30 秒"，改为删掉宽限键来模拟窗口已过 —— 断言的是同一个事实，但快 30 秒。
        cleanupRedisKeys("auth:rt:grace:" + refreshToken);
        RawResponse reuseOld = post("/api/auth/refresh", Map.of("refreshToken", refreshToken));
        assertEquals(CODE_REFRESH_TOKEN_INVALID, reuseOld.code(),
                "宽限窗口过期后旧 refreshToken 不应再可用，实际：" + reuseOld.body());
    }

    // ==================================================================
    // T-101 · 黑名单生效（G1-8 的前置）
    // ==================================================================

    @Test
    @DisplayName("T-101 登出后旧 accessToken 立即失效（HTTP 401）")
    void logoutRevokesAccessToken() {
        RawResponse login = loginAsAdmin();
        String accessToken = login.data("$.accessToken");
        String refreshToken = login.data("$.refreshToken");

        // 登出**必须带上要拉黑的那个 accessToken**：/api/auth/logout 在 Security 白名单里
        // （登出时令牌可能已过期，不该再报 401），所以它靠 Authorization 头去取要撤销的令牌 ——
        // 不带头的话它退化为"只清 Cookie + 撤销 refreshToken"，accessToken 依然有效。
        RawResponse logout = post("/api/auth/logout", Map.of("refreshToken", refreshToken), accessToken);
        assertEquals(CODE_OK, logout.code(), "登出应成功：" + logout.body());

        // 用已登出的令牌访问受保护资源 → 401（由 SecurityConfig 的 EntryPoint 统一输出）
        RawResponse after = get("/api/user/permissions", accessToken);
        assertEquals(401, after.status(), "已登出的令牌必须被拒（黑名单生效）");
    }

    // ==================================================================
    // G1-7 / I-1 · 注册自动带角色与部门
    // ==================================================================

    @Test
    @DisplayName("G1-7 / I-1 注册后自动归属「未分配」部门（deptId=0）并带 USER 角色")
    void registerAssignsDefaultDeptAndRole() {
        String username = "s1.reg." + System.currentTimeMillis();
        // 手机号每次运行都换一个：发码限流是"同目标 1 小时 ≤5 次"，
        // 用固定号码的话同一小时内跑到第 6 次就会红，属测试自身造成的假失败。
        String phone = uniquePhone();

        // 发码（伪代码实现：固定 000000）
        RawResponse sms = post("/api/auth/sms-code", Map.of("phone", phone, "scene", "register"));
        assertEquals(CODE_OK, sms.code(), "发码应成功：" + sms.body());
        assertEquals(FIXED_SMS_CODE, sms.data("$.debugCode"), "S1 发码为伪代码，debugCode 固定 000000");
        assertEquals("register", sms.data("$.scene"));

        RawResponse register = post("/api/auth/register", Map.of(
                "username", username,
                "phone", phone,
                "smsCode", FIXED_SMS_CODE,
                "password", DEFAULT_PASSWORD,
                "realName", "S1 注册用例"));
        assertEquals(CODE_OK, register.code(), "注册应成功：" + register.body());
        trackUser(username);
        assertEquals(Boolean.TRUE, register.data("$.registered"));
        assertEquals(username, register.data("$.username"));

        // I-1：必须有 USER 角色
        List<String> roleCodes = jdbc.queryForList(
                "select r.code from sys_role r join sys_user_role ur on ur.role_id = r.id "
                        + "join sys_user u on u.id = ur.user_id where u.username = ?",
                String.class, username);
        assertTrue(roleCodes.contains("USER"), "注册用户必须自动带 USER 角色，实际：" + roleCodes);

        // I-1：必须归属「未分配」部门（deptId = 0，不是 null）
        Long deptId = jdbc.queryForObject(
                "select dept_id from sys_user where username = ?", Long.class, username);
        assertEquals(0L, deptId, "注册用户应归属「未分配」部门");

        // 注册后可直接登录
        RawResponse login = login(username, DEFAULT_PASSWORD);
        assertEquals(CODE_OK, login.code(), "注册后应能直接登录：" + login.body());
        assertEquals(List.of("USER"), login.data("$.user.roles"));
    }

    // ==================================================================
    // 其它契约断言
    // ==================================================================

    @Test
    @DisplayName("G1-7 用户名重复时注册被拒")
    void registerRejectsDuplicateUsername() {
        RawResponse response = post("/api/auth/register", Map.of(
                "username", ADMIN,
                "password", DEFAULT_PASSWORD));
        assertEquals(CODE_USERNAME_EXISTS, response.code());
    }

    @Test
    @DisplayName("T-103 图形验证码返回 captchaId 与可直接用于 img src 的 Data URL")
    void captchaReturnsDataUrl() {
        RawResponse response = get("/api/auth/captcha");
        assertEquals(CODE_OK, response.code());
        assertNotNull(response.data("$.captchaId"));
        String image = response.data("$.imageBase64");
        assertTrue(image.startsWith("data:image/png;base64,"),
                "imageBase64 必须是可直接塞进 <img src> 的 Data URL，实际前缀：" + image.substring(0, 20));
    }

    @Test
    @DisplayName("T-109 可用性检查返回 {available, reason}")
    void availabilityChecksMatchContract() {
        RawResponse taken = get("/api/auth/check-username?username=" + ADMIN);
        assertEquals(CODE_OK, taken.code());
        assertEquals(Boolean.FALSE, taken.data("$.available"));
        assertNotNull(taken.data("$.reason"));

        RawResponse free = get("/api/auth/check-username?username=surely.free." + System.nanoTime());
        assertEquals(Boolean.TRUE, free.data("$.available"));
    }

    @Test
    @DisplayName("T-106 短信登录未开放时返回结构化业务错误，而非 404")
    void smsLoginReturnsStructuredError() {
        RawResponse response = post("/api/auth/login-by-sms",
                Map.of("phone", "13800000001", "smsCode", FIXED_SMS_CODE));
        assertEquals(CODE_SMS_LOGIN_UNSUPPORTED, response.code(),
                "前端登录页的短信 tab 在真后端下应是可解释的失败：" + response.body());
        assertEquals(200, response.status(), "业务错误必须是 HTTP 200");
    }

    @Test
    @DisplayName("T-104 发码 scene 非法时按参数错误拒绝")
    void smsCodeRejectsInvalidScene() {
        RawResponse response = post("/api/auth/sms-code",
                Map.of("phone", uniquePhone(), "scene", "not-a-scene"));
        assertEquals(CODE_SMS_SCENE_INVALID, response.code(), "scene 不在枚举内应返回 SMS_SCENE_INVALID");
    }
}
