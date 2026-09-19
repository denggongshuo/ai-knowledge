package com.knowledge.ai.service;

import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「换模型 + 重建」四个端点**如实回「不支持」**（`S6-M09-C`，2026-09-19）。
 *
 * <h3>这个类验的不是"功能"，而是"拒绝的质量"</h3>
 * <p>用户 2026-09-19 裁定：本版本**只开放对话模型的切换**，换嵌入 / 换 VL（含重建含图文档）
 * 一律不做。四个端点（`embedding-impact` / `change-embedding` / `vl-impact` /
 * `rebuild-image-docs`）在后端**保留映射**，但**只为了说一句「不支持」而存在**。
 *
 * <p>于是本类的判据全部是**反面的** —— 每一条都在问「它有没有假装成别的样子」：
 * <ul>
 *   <li>不是 <b>404</b>（那说明映射根本没建，前端会当成"接口不存在"）；</li>
 *   <li>不是 <b>500</b>；</li>
 *   <li>两个 <b>GET 不返回真数字</b> —— 它们只读，本可以算出真数，而**返回真数字会让
 *       「不支持」看起来像「能换」**（用户会一路填到勾选确认才撞墙）；</li>
 *   <li>两个 <b>POST 不返回假 `taskId`、不落 `sys_async_task`</b> —— 返回一个任务号等于
 *       「假启动」，用户会去通知中心等一个永远不会来的结果；</li>
 *   <li>整条链路上 <b>一次模型调用都没有</b>（拒绝发生在任何模型工作之前 ⇒ 不花钱）。</li>
 * </ul>
 *
 * <h3>⚠️ 它**不**验「启用切换」</h3>
 * <p>`activate` 是本批**刻意不动**的（用户裁定：只收「换+重建」，切换保留）——
 * 它不改已入库数据，重排切换按《09》§1.11 本就不需要重建索引，且它是已过 `G6-4` 的能力。
 * 收紧它等于**回退一个已验收的功能**。四类的完整覆盖在 {@code ModelConfigFlowTest}，
 * 本类只留一条**零改动的幂等调用**当护栏（见 {@link #activateStaysUntouched()}）。
 */
class ModelSwitchUnsupportedTest extends ApiTestSupport {

    /**
     * 拒绝的业务码。
     *
     * <p>⚠️ 这里**刻意写死数字**而不是引 {@code ErrorCode.MODEL_SWITCH_UNSUPPORTED.getCode()} ——
     * 引枚举的话，哪天那个常量被改成别的码，本类照绿，而前端/文档里按码分支的地方会静默失配。
     * 码是**对外契约**，用例就该钉住它。
     */
    private static final int MODEL_SWITCH_UNSUPPORTED = 10081;

    private String token;

    @BeforeEach
    void login() {
        token = loginAsAdmin().data("$.accessToken");
    }

    // ==================================================================
    // 一、四个端点都是「有语义的拒绝」，不是 404
    // ==================================================================

    @Test
    @DisplayName("GET /embedding-impact —— 回「不支持」，不是 404")
    void embeddingImpactRefuses() {
        RawResponse resp = get("/api/system/model/embedding-impact", token);

        assertNotMissing(resp, "embedding-impact");
        assertThat(resp.code()).isEqualTo(MODEL_SWITCH_UNSUPPORTED);
        // 硬口径：失败必须给「现象 + 下一步动作」
        assertThat(resp.message()).contains("不支持").contains("人工流程");
    }

    @Test
    @DisplayName("GET /vl-impact —— 回「不支持」，不是 404")
    void vlImpactRefuses() {
        RawResponse resp = get("/api/system/model/vl-impact", token);

        assertNotMissing(resp, "vl-impact");
        assertThat(resp.code()).isEqualTo(MODEL_SWITCH_UNSUPPORTED);
        assertThat(resp.message()).contains("不支持").contains("重新上传");
    }

    @Test
    @DisplayName("POST /change-embedding —— 回「不支持」，且**不带请求体也照拒**")
    void changeEmbeddingRefuses() {
        // 带一个**形状完全正确**的请求体：拒绝的理由是"本版本不支持"，不是"body 写错了"。
        // 若这里改回声明具体 DTO，一个字段类型不对的请求会先被 Bean Validation 拦成
        // PARAM_INVALID —— 而那会把用户引向"我再改改参数"，而不是"这条路走不通"
        RawResponse resp = post("/api/system/model/change-embedding",
                Map.of("model", "text-embedding-3-large", "dimension", 3072, "rebuildMode", "DEGRADED"), token);

        assertNotMissing(resp, "change-embedding");
        assertThat(resp.code()).isEqualTo(MODEL_SWITCH_UNSUPPORTED);

        // ⚠️ 空体也照拒 —— 证明它**真的不看请求体**
        RawResponse emptyBody = post("/api/system/model/change-embedding", Map.of(), token);
        assertThat(emptyBody.code()).isEqualTo(MODEL_SWITCH_UNSUPPORTED);
    }

    @Test
    @DisplayName("POST /rebuild-image-docs —— 回「不支持」，不是 404")
    void rebuildImageDocsRefuses() {
        RawResponse resp = post("/api/system/model/rebuild-image-docs", Map.of(), token);

        assertNotMissing(resp, "rebuild-image-docs");
        assertThat(resp.code()).isEqualTo(MODEL_SWITCH_UNSUPPORTED);
    }

    private static void assertNotMissing(RawResponse resp, String what) {
        assertThat(resp.status())
                .as("%s 必须**存在**（有映射、有权限注解）—— 404 会让前端当成「接口没做」", what)
                .isNotEqualTo(404);
        assertThat(resp.status())
                .as("%s 的拒绝走的是业务异常，不是服务端故障", what)
                .isLessThan(500);
    }

    // ==================================================================
    // 二、拒绝不能"看起来像能换" —— 不返回真数字
    // ==================================================================

    @Test
    @DisplayName("🔴 两个 impact **不返回真数字**（返回真数会让「不支持」看着像「能换」）")
    void impactEndpointsReturnNoNumbers() {
        for (String path : new String[]{"/api/system/model/embedding-impact", "/api/system/model/vl-impact"}) {
            RawResponse resp = get(path, token);
            String body = resp.body();

            // 五个数一个都不许出现 —— 它们正是 mock（`src/mock/index.ts:1428`）里那组
            // 8 库 / 1246 篇 / 38502 片 / 42 分钟 / 1180 万 tokens
            assertThat(body)
                    .as("%s 不得出现 impact 的字段名 —— 它的唯一消费方已经不支持这条路了", path)
                    .doesNotContain("kbCount")
                    .doesNotContain("docCount")
                    .doesNotContain("imageDocCount")
                    .doesNotContain("chunkCount")
                    .doesNotContain("estimateMinutes")
                    .doesNotContain("extraTokens");
        }
    }

    // ==================================================================
    // 三、拒绝不能"假启动"
    // ==================================================================

    @Test
    @DisplayName("🔴 两个 POST **不返回假 taskId、不落 sys_async_task**")
    void refusedPostsSubmitNothing() {
        long tasksBefore = countAsyncTasks();

        for (String path : new String[]{"/api/system/model/change-embedding", "/api/system/model/rebuild-image-docs"}) {
            RawResponse resp = post(path, Map.of(), token);
            assertThat(resp.body())
                    .as("%s 不得返回 taskId —— 那是「假启动」，用户会去通知中心等一个不会来的结果", path)
                    .doesNotContain("taskId");
        }

        assertThat(countAsyncTasks())
                .as("被拒的请求不得落任何后台作业")
                .isEqualTo(tasksBefore);
    }

    /**
     * `sys_async_task` 的行数。
     *
     * <p>⚠️ 它**没有自增主键**（PK 是 `task_id` VARCHAR，见 `#150`）⇒ 用不了"按 id 水位线"
     * 那套。这里退而数**总行数**：测试是单 JVM 串行跑的，且测试配置里
     * `app.parse.worker.enabled=false`，两次计数之间不该有别的东西写它。
     */
    private long countAsyncTasks() {
        Long n = jdbc.queryForObject("select count(*) from sys_async_task", Long.class);
        return n == null ? 0L : n;
    }

    // ==================================================================
    // 四、拒绝发生在任何模型工作之前 —— 不花钱
    // ==================================================================

    @Test
    @DisplayName("🔴 「换 + 重建」链路上**一次模型调用都没有**（负向锚：真调 = 真花钱）")
    void refusalCostsNothing() {
        long callsBefore = totalModelCalls();

        get("/api/system/model/embedding-impact", token);
        get("/api/system/model/vl-impact", token);
        post("/api/system/model/change-embedding",
                Map.of("model", "text-embedding-3-large", "dimension", 3072, "rebuildMode", "DEGRADED"), token);
        post("/api/system/model/rebuild-image-docs", Map.of(), token);

        assertThat(totalModelCalls())
                .as("四个端点必须**在调用任何模型之前**就拒绝 —— 尤其是 VL 那两条"
                        + "（实测 40~60 秒/张，真调一次就是真金白银）")
                .isEqualTo(callsBefore);
    }

    /**
     * `sys_model_usage` 的调用总次数。
     *
     * <p>它是**按天 + 模型 + 用途聚合**的表（`T-905`）⇒ 一次调用是**把 `calls` 加一**，
     * 不是**加一行**。所以这里比的是 {@code sum(calls)} 而不是行数 ——
     * 比行数会在"当天该桶已存在"时**恒等**，那是个假绿。
     */
    private long totalModelCalls() {
        Long n = jdbc.queryForObject("select coalesce(sum(calls), 0) from sys_model_usage", Long.class);
        return n == null ? 0L : n;
    }

    // ==================================================================
    // 五、权限边界仍在（拒绝 ≠ 谁都能调）
    // ==================================================================

    @Test
    @DisplayName("四个端点仍要求 system:model —— 普通用户 403（拒绝不等于放宽）")
    void refusalsStillRequirePermission() {
        TestUser user = createUser("msw");

        for (String path : new String[]{"/api/system/model/embedding-impact", "/api/system/model/vl-impact"}) {
            assertThat(get(path, user.accessToken()).status())
                    .as("%s 对普通用户必须是 403，不是 200+「不支持」—— 后者会让越权者拿到「该端点存在」这件事", path)
                    .isEqualTo(403);
        }
        assertThat(post("/api/system/model/change-embedding", Map.of(), user.accessToken()).status())
                .as("change-embedding 对普通用户必须是 403")
                .isEqualTo(403);
        assertThat(post("/api/system/model/rebuild-image-docs", Map.of(), user.accessToken()).status())
                .as("rebuild-image-docs 对普通用户必须是 403")
                .isEqualTo(403);

        // 未登录同理
        assertThat(get("/api/system/model/vl-impact").status()).isEqualTo(401);
    }

    // ==================================================================
    // 六、护栏：「启用切换」没被本批碰过
    // ==================================================================

    @Test
    @DisplayName("护栏：activate 仍然可用（本批刻意**不收**它 —— 收紧等于回退 G6-4）")
    void activateStaysUntouched() {
        Long activeChatId = jdbc.queryForObject(
                "select id from sys_model_config where model_type = 'CHAT' and is_active = 1 limit 1", Long.class);
        assertThat(activeChatId)
                .as("种子里应当有一条启用中的对话模型（`#166` 的基线前提）")
                .isNotNull();

        // 对**已启用**的那条再启用一次：`ModelConfigService#activate` 的幂等分支，
        // `activated=false`。**刻意选这条** —— 它把全局状态换了一模一样的一份，
        // 于是本类既验到了 activate 没坏，又**不需要复原任何状态**（`#166` 的纪律）
        RawResponse resp = post("/api/system/model/config/" + activeChatId + "/activate", Map.of(), token);

        resp.assertOk("重复启用应当幂等成功");
        // ⚠️ 先落到有类型的局部变量：`data(String)` 是泛型方法，直接塞进 `assertThat(...)`
        // 会让类型推断去挑 `assertThat(IntPredicate)` / `assertThat(Predicate)` 两个重载，编译不过
        Boolean activated = resp.data("$.activated");
        assertThat(activated).isEqualTo(Boolean.FALSE);
    }
}
