package com.knowledge.ai.service;

import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 问答页模型选项（`T-904`）—— 真 HTTP 取证。
 *
 * <h3>🔴 本类最重要的一条判据不是"过滤对不对"，是"**普通用户能不能用**"</h3>
 * <p>本接口的消费方是问答页的切换器（`ChatView.vue:29` 已在调），而本类其余六个
 * `/api/system/model/*` 端点全挂 {@code @RequiresPerm("system:model")} ——
 * 那个权限码在 `V2` 的授权矩阵里**只有 {@code SUPER_ADMIN} 有**。
 * 照着邻居抄一个注解，结果就是**普通用户在问答页开不了切换器**，
 * 而它在用例层**不会红**：用例默认拿的是管理员 token。
 *
 * <p>所以这里有一条**负向锚**（{@link #optionsIsForEndUsersNotAdminsOnly}）：
 * 同一个 USER token，调本接口要 200，调邻居 {@code /config} 要 403。
 * 缺了后半句，"其实没挂权限"和"挂对了"在用例里长得一模一样。
 *
 * <h3>为什么用 jdbc 造行而不是走 {@code POST /config}</h3>
 * <p>因为要验的是**过滤条件**（{@code last_test_ok}），而走新增接口造出来的行
 * 一律是"未测试"（`T-901` 有意为之：不让请求体里的 {@code isActive} 绕过启用门槛）。
 * 想造出"已测试通过"的行只能直接改库 —— 而那正是本用例要的**已知输入**。
 */
class ModelOptionFlowTest extends ApiTestSupport {

    /** 本类造的行统一带这个前缀，便于精确清理（不去动种子的 4 行）。 */
    private static final String TAG = "optflow";

    private String token;
    private String tag;

    @BeforeEach
    void setUp() {
        token = loginAsAdmin().data("$.accessToken");
        tag = TAG + "-" + System.nanoTime();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from sys_model_config where model_name like ?", TAG + "-%");
    }

    /** 插一条未启用的对话模型（避开启用的唯一索引），可指定测试结论。 */
    private long insertChat(String suffix, boolean tested) {
        String modelName = tag + "-" + suffix;
        jdbc.update("""
                insert into sys_model_config
                    (model_type, display_name, protocol, base_url, model_name, params, capabilities,
                     billing_unit, currency, is_active, sort_order, last_test_ok, note, created_at, updated_at)
                values ('CHAT', ?, 'OpenAI 兼容', 'https://example.invalid/v1', ?, '{}',
                        '{"stream":true,"vision":false,"contextWindow":128000,"supportSystemRole":true}',
                        'PER_TOKEN', 'CNY', 0, 99, ?, '用例造', now(), now())
                """, suffix, modelName, tested);
        Long id = jdbc.queryForObject(
                "select id from sys_model_config where model_name = ?", Long.class, modelName);
        assertThat(id).isNotNull();
        return id;
    }

    @Test
    @DisplayName("只回 last_test_ok=1 的行；未测试过的绝不出现（选了只会拿到连接错误）")
    void onlyTestedModelsAreOffered() {
        insertChat("tested", true);
        insertChat("untested", false);

        RawResponse response = get("/api/system/model/options?type=CHAT", token).assertOk("模型选项");
        List<String> names = response.data("$.list[*].modelName");

        assertThat(names).contains(tag + "-tested");
        assertThat(names).doesNotContain(tag + "-untested");
    }

    @Test
    @DisplayName("契约：{list:[{id,displayName,modelName,isActive,capabilities}]}，id 是数字")
    void shapeMatchesFrontendContract() {
        long id = insertChat("shape", true);

        RawResponse response = get("/api/system/model/options?type=CHAT", token).assertOk("模型选项");
        int index = response.<List<String>>data("$.list[*].modelName").indexOf(tag + "-shape");
        assertThat(index).isGreaterThanOrEqualTo(0);

        // id 必须是**数字**（前端 ModelOption.id: number）—— 与 ChatDtos 那些字符串 id 不是一回事
        Number gotId = response.data("$.list[" + index + "].id");
        assertThat(gotId.longValue()).isEqualTo(id);
        // capabilities 是对象且有 vision（`#170`：本批只回显，不做上下文校验）
        assertThat(response.<Object>data("$.list[" + index + "].capabilities")).isNotNull();
        assertThat(response.<Object>data("$.list[" + index + "].capabilities.vision")).isNotNull();
    }

    @Test
    @DisplayName("🔴 负向锚：USER token 调 options 要通，调邻居 /config 要被挡（403）")
    void optionsIsForEndUsersNotAdminsOnly() {
        TestUser user = createUser("optuser");

        // ① 本接口：普通用户必须能用（消费方是问答页切换器）
        RawResponse options = get("/api/system/model/options?type=CHAT", user.accessToken());
        assertThat(options.code()).isEqualTo(CODE_OK);

        // ② 同一个 token 调邻居 —— 必须 403。缺了这条，
        //    "其实压根没挂权限"和"挂对了 qa:ask"在用例里长得一模一样
        RawResponse config = get("/api/system/model/config", user.accessToken());
        assertThat(config.status()).isEqualTo(403);
    }

    @Test
    @DisplayName("G6-5 密钥不外泄：响应体里逐个搜真实 Key，必须不出现")
    void responseLeaksNoSecret() {
        insertChat("secret", true);

        RawResponse response = get("/api/system/model/options?type=CHAT", token).assertOk("模型选项");
        String body = response.body();

        // 与 G6-5 同一取证方式：拿 .env 里的真 Key 逐字比对
        for (String key : new String[]{"CHAT_API_KEY", "EMBEDDING_API_KEY", "VL_API_KEY"}) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank() && value.length() >= 8) {
                assertThat(body).doesNotContain(value);
            }
        }
        // 且字段名本身也不该出现（少发字段就是最小暴露面）
        assertThat(body).doesNotContain("apiKey").doesNotContain("apiKeyCipher");
    }

    @Test
    @DisplayName("不传 type 时默认 CHAT；认不出的 type 不会 500")
    void defaultsToChat() {
        RawResponse response = get("/api/system/model/options", token).assertOk("模型选项");
        assertThat(response.<Object>data("$.list")).isNotNull();
    }
}
