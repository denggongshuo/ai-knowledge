package com.knowledge.ai.service;

import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W7：{@code DISABLED} 账号「允许登录，但知识库不可见」的端到端取证（M04 · B2）。
 *
 * <h3>这条账为什么挂了三个 Sprint</h3>
 * <p>需求在**三处逐字一致**地写着同一条规则：
 * <ul>
 *   <li>《03-术语与领域模型》§4.1：「『已禁用』不是禁止登录：允许登录，但无法访问任何知识库。
 *       目的是让用户看到明确提示而不是『密码错误』」；</li>
 *   <li>《04-需求-认证与账号》§1.4 与验收项：「『已禁用』账号能登录成功，但看到的是权限说明而非错误提示」；</li>
 *   <li>《05-需求-权限与审计》§1.5 同句。</li>
 * </ul>
 * <p>而实现是 {@code AuthService#login} 在密码校验通过后**直接抛**
 * {@code ACCOUNT_DISABLED}（10013 / HTTP 401）—— 是**拒绝登录**，不是放行。且从未被任何门禁覆盖
 * （{@code AuthFlowTest} 里没有这条断言），所以三个 Sprint 都没暴露。
 *
 * <h3>为什么现在能做了</h3>
 * <p>判据要「能登录**但看不到库**」，下半句需要 {@code GET /kb} 与 {@code kb:view} 同时在位 ——
 * 那是 B1 的交付。B1 结束时前置刚齐备，故 B1 计划 §9-6 把它定到本批。
 *
 * <h3>为什么必须是真 HTTP</h3>
 * <p>它验的是**登录链路的整体行为**：令牌签发（{@code login}）、令牌可续（{@code refresh}）、
 * 布局壳启动即调的 {@code GET /user/profile}、以及求交引擎里那一句账号状态条件。
 * 任何一环直调 Service 都测不到 —— 尤其第三环，直调会绕开 {@code JwtAuthFilter}，
 * 于是"登录后立刻被 401 踢回登录页"这个真缺陷根本不会出现。
 */
class AuthDisabledAccountTest extends ApiTestSupport {

    /** 本用例自己造的账号前缀，便于残留排查。 */
    private static final String PROBE_PREFIX = "w7probe";

    // ==================================================================
    // 主体判据
    // ==================================================================

    @Test
    @DisplayName("W7-1：《04》§1.4 —— 「已禁用」账号能登录成功（不是 10013/401）")
    void disabledCanLogin() {
        TestUser user = createDisabledUser();
        try {
            RawResponse logged = login(user.username(), user.password());
            assertThat(logged.code())
                    .as("《03》§4.1 /《04》§1.4 /《05》§1.5 三处明写「『已禁用』不是禁止登录」；"
                            + "返 10013 是拒绝登录，与需求相反 —— 而且用户看到的是「账号已停用」这类"
                            + "错误提示，不是需求要的「权限说明」")
                    .isEqualTo(CODE_OK);
            assertThat((String) logged.data("$.accessToken")).as("必须真的发令牌").isNotBlank();
            assertThat((String) logged.data("$.user.username")).isEqualTo(user.username());
        } finally {
            trackUser(user.username());
        }
    }

    @Test
    @DisplayName("W7-2：《05》§1.5 —— 登录成功但 /kb 恒为空（授权给了也看不见）")
    void disabledSeesNoKb() {
        TestUser user = createDisabledUser();
        try {
            String token = login(user.username(), user.password()).assertOk("停用账号登录")
                    .data("$.accessToken");

            RawResponse list = get("/api/kb", token).assertOk("停用账号列表");
            assertThat(((Number) list.data("$.total")).longValue())
                    .as("「无法访问任何知识库」—— 判定落在 DataScopeService#kbVisibility 的求交条件里，"
                            + "不是靠前端不渲染。返非 0 就是越权可见")
                    .isZero();
            assertThat((List<?>) list.data("$.list")).as("list 也必须为空，不能只把 total 写 0").isEmpty();

            // 反向对照：同一个库，换一个有权限的账号确实看得见 ——
            // 否则"总数为 0"可能只是因为库里没数据，证不成本条
            assertThat(((Number) get("/api/kb", loginAsAdmin().data("$.accessToken"))
                    .assertOk("超管列表").data("$.total")).longValue())
                    .as("对照组：库是存在的，停用账号看不到不是因为没数据")
                    .isEqualTo(8);

            // 单库读取同样拦下（它走的是同一条求交条件，但走的是另一段 SQL）
            assertThat(get("/api/kb/1/config", token).code())
                    .as("逐库读取也必须不可见 —— 只堵列表会留下「按 id 猜」的越权口子")
                    .isEqualTo(10022);
        } finally {
            trackUser(user.username());
        }
    }

    @Test
    @DisplayName("W7-3：停用账号能刷新令牌（否则「允许登录」只成立 30 分钟）")
    void disabledCanRefresh() {
        TestUser user = createDisabledUser();
        try {
            String refreshToken = login(user.username(), user.password()).assertOk("停用账号登录")
                    .data("$.refreshToken");

            RawResponse refreshed = post("/api/auth/refresh", Map.of("refreshToken", refreshToken));
            assertThat(refreshed.code())
                    .as("不放行刷新的话，访问令牌一过期就被拒 —— 等于换个地方把人拒了，"
                            + "「允许登录」变成一次性体验")
                    .isEqualTo(CODE_OK);
            assertThat((String) refreshed.data("$.accessToken")).isNotBlank();

            // 刷新出来的新令牌同样看不到库（新令牌不能绕过那一维）
            assertThat(((Number) get("/api/kb", (String) refreshed.data("$.accessToken"))
                    .assertOk("刷新后列表").data("$.total")).longValue())
                    .isZero();
        } finally {
            trackUser(user.username());
        }
    }

    @Test
    @DisplayName("W7-4：停用账号能读自己的资料（布局壳启动即调，拦掉会形成登录死循环）")
    void disabledCanReadOwnProfile() {
        TestUser user = createDisabledUser();
        try {
            String token = login(user.username(), user.password()).assertOk("停用账号登录")
                    .data("$.accessToken");

            RawResponse profile = get("/api/user/profile", token);
            assertThat(profile.status())
                    .as("本接口在 BasicLayout.vue 启动时就被调用，而 ACCOUNT_DISABLED 的 HTTP 状态是 401 —— "
                            + "前端 request.ts 见 401 会清 token 跳登录页，形成"
                            + "「登录成功 → 立刻被踢 → 再登录」的死循环。"
                            + "需求要的「界面给出明确说明」正需要这个页面能渲染出来")
                    .isEqualTo(200);
            assertThat(profile.code()).isEqualTo(CODE_OK);
            assertThat((String) profile.data("$.username")).isEqualTo(user.username());
        } finally {
            trackUser(user.username());
        }
    }

    @Test
    @DisplayName("W7-5：停用账号不能改自己的资料（写侧保持收紧）")
    void disabledCannotWriteProfile() {
        TestUser user = createDisabledUser();
        try {
            String token = login(user.username(), user.password()).assertOk("停用账号登录")
                    .data("$.accessToken");

            RawResponse denied = put("/api/user/profile", Map.of("realName", "改名试试"), token);
            assertThat(denied.code())
                    .as("需求只规定了「知识库不可见」，没规定写侧。取「停用即不可改」是收紧的方向 —— "
                            + "本用例把这个取舍钉住，免得后人以为是漏了")
                    .isEqualTo(10013);
        } finally {
            trackUser(user.username());
        }
    }

    @Test
    @DisplayName("W7-6：回归 —— 正常账号与待激活账号的行为都不受影响")
    void otherStatusesUnaffected() {
        // 正常账号：能登录、能看库（8 库）
        String adminToken = loginAsAdmin().data("$.accessToken");
        assertThat(((Number) get("/api/kb", adminToken).assertOk("超管列表").data("$.total")).longValue())
                .as("改动不得影响正常账号").isEqualTo(8);

        // 待激活：**仍然拒绝登录**（本次只放行 DISABLED，PENDING 没走完激活流程）
        TestUser pending = createUser(PROBE_PREFIX + "pending");
        try {
            jdbc.update("update sys_user set status = 'PENDING' where username = ?", pending.username());
            assertThat(login(pending.username(), pending.password()).code())
                    .as("PENDING 未被放开 —— 它与 DISABLED 是两回事："
                            + "前者是「还没走完激活」，后者是「被管理员停用」")
                    .isEqualTo(10014);
        } finally {
            trackUser(pending.username());
        }
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /**
     * 造一个 {@code DISABLED} 账号。
     *
     * <p>先注册（拿到 {@code ACTIVE}）再改状态，而不是直接插行 ——
     * 注册链路会把密码摘要、角色、部门都建好，与真实账号同构。
     */
    private TestUser createDisabledUser() {
        TestUser user = createUser(PROBE_PREFIX);
        int updated = jdbc.update("update sys_user set status = 'DISABLED' where username = ?",
                user.username());
        assertThat(updated).as("停用状态没写进去，后面几条断言全是空转").isEqualTo(1);
        return user;
    }
}
