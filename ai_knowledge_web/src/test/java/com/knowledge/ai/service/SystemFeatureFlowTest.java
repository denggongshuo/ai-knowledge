package com.knowledge.ai.service;

import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-C03` / FR-GRAPH-01 —— 图谱旁路开关端点的真 HTTP 取证。
 *
 * <h3>为什么必须走 HTTP 而不是直调 {@code SystemFeatureService}</h3>
 * <p>三件事只在请求上下文里成立：① {@code @RequiresPerm} 由 MVC 拦截器执行，
 * 直调**根本不经过它** —— 那样的用例无论权限码挂没挂都会绿；
 * ② 审计的 {@code operator} / {@code apiPath} 取自请求上下文，直调会测成空值
 * （`S2AuditFlowTest` 的教训）；③ 「改了立刻生效」这条要穿过真实的
 * Controller → Service → {@code sys_setting} 整条链。
 *
 * <h3>本类没有一条断言拿接口返回值当"做成了"的证据</h3>
 * <p>每条判据都**回查 {@code sys_setting} 那一行**。接口回 200 只证明它没抛异常。
 *
 * <h3>⚠️ 收尾必须还原开关</h3>
 * <p>开关是全站单值（不分库），把库留在 `false` 会静默改变后续所有用例的检索行为。
 * 收尾不只写回 `true`，还**断言写回成功** —— 否则"还原了"只是一句声明。
 */
class SystemFeatureFlowTest extends ApiTestSupport {

    private static final String KEY = "retrieval.graph-bypass-enabled";

    /** 用例开始前那一行是否存在（缺行时收尾不该凭空造一行出来）。 */
    private boolean rowExistedBefore;

    @BeforeEach
    void rememberAndReset() {
        String raw = rawValue();
        rowExistedBefore = raw != null;
        // 每个用例都从「开」起步，断言才有确定性（否则前一个用例留在什么值就说不清了）
        setRaw("true");
    }

    @AfterEach
    void restoreSwitch() {
        if (rowExistedBefore) {
            setRaw("true");
            assertThat(rawValue()).as("开关没还原成 true ⇒ 后面的批次会莫名其妙少走图谱").isEqualTo("true");
        } else {
            jdbc.update("delete from sys_setting where setting_key = ?", KEY);
        }
    }

    // ==================================================================
    // 读
    // ==================================================================

    @Test
    @DisplayName("GET 返回当前开关状态（只要求登录）")
    void getReturnsCurrentState() {
        setRaw("false");

        RawResponse response = get("/api/system/feature/graph", adminToken());

        response.assertOk("读开关状态");
        assertThat((Boolean) response.data("$.enabled"))
                .as("库行是 false ⇒ 这里必须是 false；回 true 说明读得不是同一行")
                .isFalse();
    }

    @Test
    @DisplayName("GET 不带 token ⇒ 401（只要求登录 ≠ 不要求登录）")
    void getWithoutTokenIsUnauthorized() {
        assertThat(get("/api/system/feature/graph").status()).isEqualTo(401);
    }

    // ==================================================================
    // 写
    // ==================================================================

    @Test
    @DisplayName("PUT 关闭 ⇒ sys_setting 那一行真的变成 false，且 GET 立刻反映")
    void putOffPersistsAndIsVisibleImmediately() {
        put("/api/system/feature/graph", Map.of("enabled", false), adminToken())
                .assertOk("关闭图谱旁路");

        assertThat(rawValue()).as("返回 200 只证明没抛异常 —— 这一行才是证据").isEqualTo("false");
        assertThat((Boolean) get("/api/system/feature/graph", adminToken()).data("$.enabled"))
                .as("改完立刻生效（G9-1「关得掉」）")
                .isFalse();
    }

    @Test
    @DisplayName("PUT 打开 ⇒ 那一行真的变回 true")
    void putOnPersists() {
        setRaw("false");

        put("/api/system/feature/graph", Map.of("enabled", true), adminToken())
                .assertOk("打开图谱旁路");

        assertThat(rawValue()).isEqualTo("true");
    }

    @Test
    @DisplayName("🔴 PUT 无 system:model ⇒ 403，且库里的值一个字节都没动")
    void putWithoutPermissionIsForbiddenAndChangesNothing() {
        TestUser plain = createUser("s9graph.noperm");

        RawResponse response = put("/api/system/feature/graph", Map.of("enabled", false),
                plain.accessToken());

        assertThat(response.status()).as("普通用户不该能改全站检索行为").isEqualTo(403);
        // ⚠️ 这一条比 403 更重要：拦截必须发生在**写之前**。
        // 若权限校验挂在写之后，接口照样回 403 而库已经被改了 —— 那时只看状态码是看不出来的
        assertThat(rawValue()).as("被拒绝的请求不许留下任何痕迹").isEqualTo("true");
    }

    // ==================================================================
    // 审计（三处同步的第三处：实际落库）
    // ==================================================================

    @Test
    @DisplayName("🔴 PUT 成功 ⇒ 审计落一条 HIGH，且 operator/apiPath 非空")
    void putIsAudited() {
        long floor = maxAuditId();

        put("/api/system/feature/graph", Map.of("enabled", false), adminToken())
                .assertOk("关闭图谱旁路");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select action, module, risk_level, operator, api_path from sys_audit_log"
                        + " where id > ? and action = ?", floor, "开关图谱旁路");

        assertThat(rows).as("标了 🚩 的动作必须留痕").hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("risk_level")).as("全站行为变更按 §4.3「界限必须写死」定 HIGH").isEqualTo("HIGH");
        assertThat(row.get("module")).isEqualTo("系统");
        assertThat(row.get("operator")).as("取自请求上下文；直调 Service 这里会是空值").isNotNull();
        assertThat(row.get("api_path")).as("同上").isNotNull();
    }

    @Test
    @DisplayName("PUT 把值设成它已经是的样子，照样记一条 —— 按过开关这件事本身要留痕")
    void noopToggleStillAudited() {
        long floor = maxAuditId();

        put("/api/system/feature/graph", Map.of("enabled", true), adminToken())
                .assertOk("再关一次（值没变）");

        assertThat(jdbc.queryForObject(
                "select count(*) from sys_audit_log where id > ? and action = ?",
                Integer.class, floor, "开关图谱旁路"))
                .as("幂等短路会让「谁按过开关」这个事实消失")
                .isEqualTo(1);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private String adminTokenCache;

    /**
     * 种子超管的 access token。
     *
     * <p>缓存一次，避免每个用例都登录一遍（超管登录会写 `sys_auth_login_log`，
     * 且每次都要过一次真实密码校验）。
     *
     * <p>刻意**不**构造 {@code TestUser}：那个 record 是基类的 {@code protected} 嵌套类型，
     * 而本类与基类**不同包** ⇒ 子类可以引用这个类型、但不能 {@code new} 它
     * （JLS 的 protected 规则要求访问经由子类型引用）。直接取 token 反而更省。
     */
    private String adminToken() {
        if (adminTokenCache == null) {
            adminTokenCache = (String) loginAsAdmin().data("$.accessToken");
        }
        return adminTokenCache;
    }

    private String rawValue() {
        List<String> values = jdbc.queryForList(
                "select setting_value from sys_setting where setting_key = ?", String.class, KEY);
        return values.isEmpty() ? null : values.get(0);
    }

    private void setRaw(String value) {
        jdbc.update("update sys_setting set setting_value = ? where setting_key = ?", value, KEY);
    }

    private long maxAuditId() {
        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from sys_audit_log", Long.class);
        return max == null ? 0L : max;
    }
}
