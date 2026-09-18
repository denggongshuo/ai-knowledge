package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.ModelType;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M09 模型配置（`T-900`~`T-903`）—— 真 HTTP 取证，对齐门禁 `G6-1`~`G6-5`。
 *
 * <h3>本类覆盖的是"配置这一侧"；"模型调用那一侧"在别处</h3>
 * <p>本类**不验**"换个模型后检索结果真的变了"（那是 `T-906`/`T-908`），只验
 * 配置的增删改查、启用切换、以及**它们对系统的约束**（唯一启用、启用门槛、单例）。
 *
 * <h3>🔴 本类**会改变全局状态** —— 这是本批引入的新耦合，务必知道</h3>
 * <p>`S6-M09-A` 之前，模型口径来自 {@code app.model.*}（`ModelProperties`，**只读、进程内**），
 * 任何用例都动不了它。接上 DB 之后，{@code sys_model_config.is_active} 成了**全局单例约束下的
 * 可变状态** —— 于是"启用一条 CHAT"必然把种子那条挤掉，而
 * {@code ParseWorkerFlowTest} / {@code IndexEnhancerNetworkTest} 这类**看着与模型配置无关**的
 * 用例，会因为拿不到对话模型而红（实测：上一轮遗留的脏状态让它们 2 失败 1 错误）。
 *
 * <p>本类的对策是**开工前 + 收尾各复原一次**（{@link #restoreSeededActiveChat}），
 * 而不是让别处去防御 —— 改状态的用例负责把状态还回去。
 * ⚠️ **残留风险**：进程若在用例中途被杀，仍会留下"没有启用对话模型"的库状态，
 * 下一次跑批的前几个类就会红。这与 `#108`「跑批前先核对基线残留」是同一条纪律，
 * 已登记 `S6-M09-A` §9（建议把 `sys_model_config` 的 `is_active` 并入基线自检）。
 *
 * <h3>为什么不带 {@code @Tag("network")}</h3>
 * <p>{@code T-903} 的连接测试**会真发请求**，但本类**不依赖它成功**：断言的是
 * 「返回契约 + 把结论写回 {@code last_test_*}」，这在有网与无网两种世界里都成立
 * （无网时 `ok=false` 且 message 给出下一步动作 —— 那也是一个必须成立的行为）。
 * 把"真的连通了"写成硬断言会让整个类变成网络依赖，而它验的九成是配置逻辑。
 * （对照：{@code AiCallVerificationTest} 是 `@Tag("network")`，它验的恰恰是"真调通了"。）
 */
class ModelConfigFlowTest extends ApiTestSupport {

    private String token;

    /** 用例开始前 `sys_model_config` 的最大 id —— 收尾删本次新增的。 */
    private long floor;

    @BeforeEach
    void loginAndMarkFloor() {
        token = loginAsAdmin().data("$.accessToken");
        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from sys_model_config", Long.class);
        floor = max == null ? 0L : max;
        restoreSeededActiveChat();
    }

    /**
     * **跑前复原基线**：让种子那条对话模型回到启用态。
     *
     * <p>为什么必须有这一步（实测踩到）：JUnit 不保证方法顺序，而
     * {@link #onlyOneActivePerType} 与 {@link #activeCannotBeDeleted} 都会**启用一条新建的
     * CHAT** —— 而"同类型至多一条启用"意味着它们**把种子那条挤掉了**。
     * `@AfterEach` 只删造出来的行，**不会把启用态还回去** ⇒ 下一条用例看到的是
     * 「一条 CHAT 都没有启用」，`stats.defaultChatName` 变成「未配置」而红。
     *
     * <p>⚠️ 这正是本仓 `#108`/`#120`/`#128` 那一族纪律：**跑批前先问"这个库现在是什么状态"**。
     * 修法不是"让用例别依赖顺序"，而是**每条用例开工前把共享状态复原** ——
     * 因为被挤掉的不是"别人的数据"，是**全局单例约束下的必然副作用**。
     */
    private void restoreSeededActiveChat() {
        Long seedChatId = jdbc.queryForObject(
                "select min(id) from sys_model_config where model_type = 'CHAT' and id <= ?",
                Long.class, floor);
        if (seedChatId == null) {
            return;
        }
        jdbc.update("update sys_model_config set is_active = 0 where model_type = 'CHAT'");
        jdbc.update("update sys_model_config set is_active = 1 where id = ?", seedChatId);
    }

    /**
     * 收尾：删掉本类造的行。
     *
     * <p>⚠️ **不删种子那四行** —— 它们是启动期 {@code ModelConfigSeeder} 落的全局引导配置，
     * 不是探针产物（没有 `user_id`/`kb_id`，两侧清理器的覆盖率闸都扫不到它），
     * 每轮跑批都该原样保留（与 `V6` 的种子账号同理）。
     */
    @AfterEach
    void removeCreatedRows() {
        jdbc.update("delete from sys_model_config where id > ?", floor);
        // 🔴 **收尾必须复原基线，不能只删自己造的行** —— 实测踩到：
        // 启用类用例会把**种子那条 CHAT 挤掉**（同类型至多一条启用），
        // 而只删新建行的话，下一个跑到的用例类（如 `ModelClientRegistryTest`）
        // 会看到"一条对话模型都没启用"，报「对话模型未配置」。
        // 这与 `#108`/`#120` 是同一条纪律：**跑批前问"库现在什么状态"，跑批后把它还回去**。
        restoreSeededActiveChat();
    }

    // ==================================================================
    // G6-1 / G6-2 —— 四类列表 + 嵌入单例
    // ==================================================================

    @Test
    @DisplayName("G6-1：四类各自独立列表；embedding 是**单条对象**；字段形状对齐前端契约")
    void listCoversFourTypesWithFrontendShape() {
        var data = get("/api/system/model/config", token).assertOk("模型配置列表");

        // 四类都在，且 chat/rerank/vl 是数组
        assertThat((Object) data.data("$.chat")).isInstanceOf(List.class);
        assertThat((Object) data.data("$.rerank")).isInstanceOf(List.class);
        assertThat((Object) data.data("$.vl")).isInstanceOf(List.class);
        // 嵌入是**单条对象**（前端类型 `embedding: ModelConfigItem`）。
        // 种子里四类都配齐了，所以这里不该是 null
        assertThat((Object) data.data("$.embedding")).isNotNull();

        Map<String, Object> embedding = data.data("$.embedding");
        assertThat(embedding.get("modelType")).isEqualTo("EMBEDDING");
        assertThat(embedding.get("dimension")).as("嵌入维度是只读硬约束").isEqualTo(1024);

        Map<String, Object> stats = data.data("$.stats");
        assertThat(stats.get("defaultChatName")).as("统计卡要显示当前默认对话模型")
                .isNotEqualTo("未配置");

        // 种子落在 chat 里的那条
        List<Map<String, Object>> chat = data.data("$.chat");
        assertThat(chat).as("种子应落一条对话模型（.env 里配了 CHAT_API_KEY）").isNotEmpty();
        Map<String, Object> first = chat.getFirst();
        assertThat(first.get("displayName")).isNotNull();
        assertThat(first.get("baseUrl")).isNotNull();
        assertThat(first.get("isActive")).isEqualTo(true);
        assertThat(first.get("params")).as("params 是对象，不是 JSON 串").isInstanceOf(Map.class);
        assertThat(first.get("capabilities")).as("capabilities 是对象").isInstanceOf(Map.class);

        // ⚠️ `lastTestAt` 的契约：从未测过 → `null`（**不是空串** —— 空串在页面上是一格空白，
        // 分不出"没测过"与"测过但时间没记下来"）。测过时必须是**已格式化串**而不是时间戳
        // （前端 `{{ row.lastTestAt }}` 原样渲染，发时间戳过去那一行就是空的），那半条由
        // `testWritesResultBack` 覆盖。
        //
        // 🔴 **不能断言在种子那一行上** —— 它的 `last_test_at` 是**可变的环境状态**：
        // 任何一次真实连接测试（开发时在页面上点一下「测试」，或上一批收尾时跑的真后端冒烟）
        // 都会写它，于是一条"断言环境"的用例会在**与它无关的改动之后**变红。
        // 实测：`S6-M09-A` 收尾的真冒烟把种子的 `last_test_at` 写成了当天时间，
        // 下一次跑批这一行就红了 —— 而代码一行没改。
        // 这与 `#129`「每批绿不绿是软的」是同一族，故改为**自造一条没测过的行**来验。
        long freshId = createChat("从未测试的");
        // 造完要**重新取一次列表** —— 上面那份 `data` 是造之前抓的，里面没有它
        var after = get("/api/system/model/config", token).assertOk("模型配置列表");
        List<Map<String, Object>> list = after.data("$.chat");
        Map<String, Object> fresh = list.stream()
                .filter(row -> row.get("id") != null && ((Number) row.get("id")).longValue() == freshId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("新建的行应出现在 chat 列表里，实际：" + list));
        assertThat(fresh.get("lastTestAt")).as("从未测试过 → null（不是空串）").isNull();
        assertThat(fresh.get("lastTestOk")).as("从未测试过 → 结论为假").isEqualTo(false);
    }

    @Test
    @DisplayName("G6-2：嵌入是全局单例 —— 新增被拒 + 切换启用被拒")
    void embeddingIsSingleton() {
        var created = post("/api/system/model/config",
                Map.of("modelType", "EMBEDDING", "displayName", "第二条嵌入",
                        "baseUrl", "https://example.test/v1", "modelName", "BAAI/bge-m3"),
                token);
        assertThat(created.code()).as("嵌入不可新增").isEqualTo(10078);

        long embeddingId = jdbc.queryForObject(
                "select id from sys_model_config where model_type = 'EMBEDDING' limit 1", Long.class);
        assertThat(post("/api/system/model/config/" + embeddingId + "/activate", null, token).code())
                .as("嵌入也不可切换启用（切换 = 换模型 + 全库重建，属 T-906）").isEqualTo(10078);
    }

    // ==================================================================
    // G6-3 / G6-4 —— 启用门槛与唯一性
    // ==================================================================

    @Test
    @DisplayName("G6-3：没测过不让启用（改过 BaseURL 也要重测）")
    void activateRequiresPassedTest() {
        long id = createChat("未测试的模型");

        var activated = post("/api/system/model/config/" + id + "/activate", null, token);
        assertThat(activated.code()).as("G6-3 启用门槛").isEqualTo(10080);
        assertThat(activated.message()).as("G4-3：失败要给下一步动作").contains("测试");
        assertThat(isActive(id)).as("被拒的启用不得留下任何状态").isFalse();
    }

    @Test
    @DisplayName("G6-4：同类型第二条启用 → 第一条自动停用；库层还有唯一索引兜底")
    void onlyOneActivePerType() {
        long first = createChat("甲模型");
        long second = createChat("乙模型");
        forceTestOk(first);
        forceTestOk(second);

        post("/api/system/model/config/" + first + "/activate", null, token).assertOk("启用甲");
        assertThat(isActive(first)).isTrue();

        var switched = post("/api/system/model/config/" + second + "/activate", null, token)
                .assertOk("切到乙");
        assertThat((String) switched.data("$.modelType")).isEqualTo("CHAT");
        assertThat(isActive(second)).as("乙应为启用").isTrue();
        assertThat(isActive(first)).as("甲应被自动停用").isFalse();

        // ⚠️ 库层的唯一索引才是真正的保证 —— 应用层的"先停后启"在并发下会漏。
        // 这里直接绕过应用层插一条，验证索引确实在
        assertThat(rawInsertDuplicateActive()).as("唯一索引必须拦住第二条启用").isNotNull();
    }

    /** 直接 SQL 造第二条启用 → 唯一索引必须拦住。返回异常信息（非 null 即"拦住了"）。 */
    private String rawInsertDuplicateActive() {
        try {
            jdbc.update("insert into sys_model_config "
                            + "(model_type, display_name, protocol, base_url, model_name, currency, "
                            + "is_active, sort_order, last_test_ok, created_at, updated_at) "
                            + "values ('CHAT', '绕过应用层', 'OpenAI 兼容', 'https://x.test/v1', 'm', 'CNY', "
                            + "1, 0, 0, now(), now())");
            return null; // 插进去了 → 索引没起作用
        } catch (RuntimeException ex) {
            return ex.getMessage();
        } finally {
            // 万一插进去了也别留脏（本用例的 @AfterEach 会按水位线兜底）
            jdbc.update("delete from sys_model_config where display_name = '绕过应用层'");
        }
    }

    // ==================================================================
    // G6-5 —— 密钥不外泄
    // ==================================================================

    @Test
    @DisplayName("G6-5：响应里只有掩码，绝不出现 Key 明文")
    void apiKeyNeverLeaks() {
        var body = get("/api/system/model/config", token).assertOk("列表").body();

        assertThat(body).as("响应体里不得出现 apiKey / apiKeyCipher 这类字段名")
                .doesNotContain("\"apiKey\"").doesNotContain("apiKeyCipher");

        List<Map<String, Object>> chat = get("/api/system/model/config", token).assertOk("列表").data("$.chat");
        assertThat(chat).isNotEmpty();
        String masked = String.valueOf(chat.getFirst().get("apiKeyMasked"));
        assertThat(masked).as("掩码应当是脱敏串").contains("•");
        // 真实 key 绝不出现在响应里 —— 这里拿 .env 里的值逐字比对
        String realKey = System.getenv("CHAT_API_KEY");
        if (realKey != null && !realKey.isBlank()) {
            assertThat(body).as("真实 Key 绝不能出现在任何响应里").doesNotContain(realKey);
        }
    }

    // ==================================================================
    // T-901 —— CRUD 的三条硬规则
    // ==================================================================

    @Test
    @DisplayName("T-901：当前启用的不可删除")
    void activeCannotBeDeleted() {
        long id = createChat("不能删的");
        forceTestOk(id);
        post("/api/system/model/config/" + id + "/activate", null, token).assertOk("启用");

        var deleted = call("DELETE", "/api/system/model/config/" + id, null, token);
        assertThat(deleted.code()).isEqualTo(10079);
        assertThat(deleted.message()).as("G4-3：文案要给下一步动作").contains("切换");
        assertThat(countById(id)).as("被拒的删除不得真删掉").isEqualTo(1);
    }

    @Test
    @DisplayName("T-901：改了 BaseURL/模型名 → 原测试结论失效（needRetest）")
    void editingEndpointInvalidatesTest() {
        long id = createChat("要被改的");
        forceTestOk(id);
        assertThat(lastTestOk(id)).as("前置：先让它测过").isTrue();

        var updated = put("/api/system/model/config/" + id,
                Map.of("baseUrl", "https://changed.test/v1"), token).assertOk("改 BaseURL");
        assertThat((Boolean) updated.data("$.needRetest")).as("§1.6 编辑后需重新测试").isTrue();
        assertThat(lastTestOk(id)).as("测试结论必须归零，否则启用门槛形同虚设").isFalse();
        assertThat(lastTestAt(id)).as("测试时间也要清 —— 留着会显示成「测过但没过」").isNull();

        // 只改展示名不该让结论失效
        forceTestOk(id);
        var renamed = put("/api/system/model/config/" + id, Map.of("displayName", "改个名"), token)
                .assertOk("只改名");
        assertThat((Boolean) renamed.data("$.needRetest")).as("只改名字不影响连通性").isFalse();
        assertThat(lastTestOk(id)).isTrue();
    }

    @Test
    @DisplayName("T-901：模型类型创建后不可修改；必填字段缺一即拒")
    void typeIsImmutableAndRequiredFieldsEnforced() {
        long id = createChat("类型测试");

        assertThat(put("/api/system/model/config/" + id,
                Map.of("modelType", "RERANK"), token).code())
                .as("改类型 = 换一个模型，轨迹会全对不上").isEqualTo(10030);

        assertThat(post("/api/system/model/config",
                Map.of("modelType", "CHAT", "displayName", "缺 URL", "modelName", "m"), token).code())
                .isEqualTo(10030);
    }

    // ==================================================================
    // T-903 —— 连接测试
    // ==================================================================

    @Test
    @DisplayName("T-903：连接测试把结论写回 last_test_*（真发请求；成败都写）")
    void testWritesResultBack() {
        long id = createChat("待测的");

        var result = post("/api/system/model/config/" + id + "/test", null, token).assertOk("连接测试");

        boolean ok = (Boolean) result.data("$.ok");
        assertThat((String) result.data("$.message")).as("G4-3：无论成败都要有可读结论").isNotBlank();
        assertThat(lastTestOk(id)).as("测完必须写回结论（失败也写 —— 测过≠没测过）").isEqualTo(ok);
        assertThat(lastTestAt(id)).as("测试时间必须落库").isNotNull();
        long latencyMs = ((Number) result.data("$.latencyMs")).longValue();
        if (ok) {
            assertThat(latencyMs).as("通过时应有首字延迟").isGreaterThan(0L);
        } else {
            assertThat(latencyMs)
                    .as("测不出来时用 -1 而不用 0（0ms 是「快得不可能」，与「没测到」不是一回事）")
                    .isEqualTo(-1L);
            assertThat((String) result.data("$.message")).as("失败文案要给下一步动作").contains("请核对");
        }
    }

    // ==================================================================
    // 审计 —— 四个写动作各留一条 HIGH
    // ==================================================================

    @Test
    @DisplayName("审计：新增/编辑/删除/启用 四个写动作各留一条 module=系统 risk=HIGH")
    void writeActionsAreAudited() {
        long id = createChat("审计用");
        put("/api/system/model/config/" + id, Map.of("displayName", "审计用改"), token).assertOk("编辑");
        forceTestOk(id);
        post("/api/system/model/config/" + id + "/activate", null, token).assertOk("启用");

        List<String> actions = jdbc.queryForList(
                "select action from sys_audit_log where module = '系统' and action like '%模型配置' "
                        + "and created_at >= ? order by id", String.class,
                java.time.LocalDateTime.now().minusMinutes(5));
        assertThat(actions).as("四个写动作都要留痕（连接测试不留 —— 它是只读探测）")
                .contains("新增模型配置", "编辑模型配置", "启用模型配置");

        Integer highRisk = jdbc.queryForObject(
                "select count(*) from sys_audit_log where module = '系统' and action = '启用模型配置' "
                        + "and risk_level = 'HIGH' and created_at >= ?", Integer.class,
                java.time.LocalDateTime.now().minusMinutes(5));
        assertThat(highRisk).as("§4.3④：模型配置变更是高危").isGreaterThanOrEqualTo(1);
    }

    // ==================================================================
    // 权限 —— 无 system:model 的角色进不来
    // ==================================================================

    @Test
    @DisplayName("权限：没有 system:model 的角色读到 403（该码只发了超管）")
    void withoutPermissionIsForbidden() {
        TestUser user = createUser("s6model.noperm");

        assertThat(get("/api/system/model/config", user.accessToken()).status())
                .as("普通 USER 不得读模型配置（含 baseUrl 与掩码）").isEqualTo(403);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 造一条未测试、未启用的对话模型。 */
    private long createChat(String displayName) {
        var created = post("/api/system/model/config",
                Map.of("modelType", "CHAT", "displayName", displayName,
                        "baseUrl", "https://example.test/v1", "modelName", "some-model",
                        "params", Map.of("temperature", 0.2, "timeoutSec", 30, "retries", 1),
                        "capabilities", Map.of("stream", true, "vision", false,
                                "contextWindow", 32000, "supportSystemRole", true)),
                token).assertOk("新增 " + displayName);
        long id = ((Number) created.data("$.id")).longValue();
        assertThat((Object) created.data("$.saved")).isEqualTo(true);
        return id;
    }

    /** 直接把"测试通过"写到行上 —— 绕开真发请求，让启用门槛那几条判据与网络解耦。 */
    private void forceTestOk(long id) {
        jdbc.update("update sys_model_config set last_test_ok = 1, last_test_at = now() where id = ?", id);
    }

    private boolean isActive(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select is_active from sys_model_config where id = ?", Boolean.class, id));
    }

    private boolean lastTestOk(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select last_test_ok from sys_model_config where id = ?", Boolean.class, id));
    }

    private Object lastTestAt(long id) {
        return jdbc.queryForObject("select last_test_at from sys_model_config where id = ?", Object.class, id);
    }

    private int countById(long id) {
        Integer n = jdbc.queryForObject(
                "select count(*) from sys_model_config where id = ?", Integer.class, id);
        return n == null ? 0 : n;
    }
}
