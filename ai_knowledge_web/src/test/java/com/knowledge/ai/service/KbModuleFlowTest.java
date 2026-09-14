package com.knowledge.ai.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.enums.DataScope;
import com.knowledge.ai.core.enums.KbConfigDefaults;
import com.knowledge.ai.core.enums.KbPresets;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M04 阶段 B1 门禁：T-400 列表 / T-401 创建 / T-402 预设 / T-403 配置读取。
 *
 * <h3>为什么是"真 HTTP + 查库 + SQL 抓取"三件套</h3>
 * <ul>
 *   <li><b>真 HTTP</b>：{@code target} 的 SpEL、审计采集、{@code LoginUserContext}
 *       都取自请求上下文，绕开过滤器直调 Service 会把它们测成空值 —— 等于没测到链路；</li>
 *   <li><b>查库</b>：判据 1「7 列逐字相等」只能由 {@code select} 回的真值证明，
 *       读接口回显一致也可能是"提交值原样回给你"；</li>
 *   <li><b>SQL 抓取</b>：判据 7 与 {@code DataScopeSqlEvidenceTest} 同理由 ——
 *       "拼串函数返回了正确片段"不等于"片段真的进了 SQL"。</li>
 * </ul>
 *
 * <h3>两条纪律</h3>
 * <ol>
 *   <li>用例造的库一律用 {@code kb_probe_} 前缀，并在 {@code finally} 里
 *       <b>先删授权再删库</b>（无外键约束，顺序其实无所谓，但保持与种子核对口径一致）；</li>
 *   <li>不碰 8 个演示库的任何列 —— 判据 8 的数值断言依赖它们的 dept 分布。</li>
 * </ol>
 */
class KbModuleFlowTest extends ApiTestSupport {

    private static final String BIND_LOGGER = "org.hibernate.SQL";

    /** 演示账号的统一口令（V6 建号时写死）。 */
    private static final String DEMO_PASSWORD = "Init@12345";

    @Autowired
    private KbGrantService kbGrantService;

    @Autowired
    private DataScopeService dataScopeService;

    // ==================================================================
    // T-402 · 预设（判据 4 / 5 / 6）
    // ==================================================================

    @Test
    @DisplayName("G3-2：/kb/presets 与 /kb/{id}/config 的 embedding.locked 均为 true，且无可选模型列表")
    void embeddingIsReadOnly() {
        String token = loginAsAdmin().data("$.accessToken");

        RawResponse presets = get("/api/kb/presets", token).assertOk("预设");
        assertThat((Boolean) presets.data("$.embedding.locked"))
                .as("G3-2：嵌入模型必须只读")
                .isTrue();
        assertThat((Integer) presets.data("$.embedding.dimension"))
                .as("维度取建集合用的那个值，不是模型自称的值")
                .isEqualTo(MilvusConstants.DIMENSION);
        // 反向：响应体里**不存在**"可选嵌入模型列表"这类字段 —— 有它前端就会渲染成下拉
        assertThat(presets.body())
                .as("不得出现可选模型列表，否则前端会把只读项渲染成可切换")
                .doesNotContain("embeddingModels")
                .doesNotContain("\"options\"");

        RawResponse config = get("/api/kb/1/config", token).assertOk("配置读取");
        assertThat((Boolean) config.data("$.embedding.locked"))
                .as("G3-2：设置页的嵌入模型同样只读")
                .isTrue();
    }

    @Test
    @DisplayName("G3-2：预设 5 条、顺序与数值逐条等于前端 mock/data.ts 的 SPLIT_PRESETS")
    void presetsMatchFrontendMock() {
        String token = loginAsAdmin().data("$.accessToken");
        RawResponse response = get("/api/kb/presets", token).assertOk("预设");

        List<Map<String, Object>> presets = response.data("$.presets");
        assertThat(presets).hasSize(5);
        assertThat(presets.stream().map(p -> p.get("id")).toList())
                .as("顺序即展示顺序，与前端 preset-row 渲染次序一致")
                .containsExactly("GENERAL", "STRUCTURED", "CONTRACT", "TABLE_HEAVY", "QA_PAIR");

        // 期望值**不在这里再抄一遍**，而是从 KbPresets 表读 —— 表本身是与前端 mock 对齐的产物，
        // 用例的角色是证明"接口返回的 = 表里的"，而不是制造第三份口径。
        for (Map<String, Object> preset : presets) {
            KbPresets.Preset expected = KbPresets.find((String) preset.get("id"));
            assertThat(expected).as("预设定表里应有 %s", preset.get("id")).isNotNull();
            assertThat(preset.get("chunkSize")).isEqualTo(expected.chunkSize());
            assertThat(preset.get("overlap")).isEqualTo(expected.overlap());
            assertThat(preset.get("semanticSplit")).isEqualTo(expected.semanticSplit());
            assertThat(preset.get("tableAtomic")).isEqualTo(expected.tableAtomic());
            assertThat(preset.get("label")).isEqualTo(expected.label());
        }

        // 与前端默认值同源的两个关键数值（判据 5 的后半）
        assertThat((Integer) response.data("$.defaults.splitPolicy.chunkSize"))
                .as("defaults.splitPolicy.chunkSize 必须与前端 defaultKbConfig() 逐字一致")
                .isEqualTo(512);
        assertThat((Double) response.data("$.defaults.retrievalConfig.recallThreshold"))
                .as("recallThreshold 0.15 是前端既成取值，改成「更合理」的值会让 diff 全量飘红")
                .isEqualTo(0.15);
    }

    @Test
    @DisplayName("G3-1：defaults 与前端 defaultKbConfig() 逐字段一致（四步默认值）")
    void defaultsMatchFrontendDefaultConfig() {
        String token = loginAsAdmin().data("$.accessToken");
        String body = get("/api/kb/presets", token).assertOk("预设").body();

        // 逐字对齐 KbConfigDefaults —— 它是这份默认值的后端唯一来源。
        // ⚠️ 每处都必须显式给类型：{@code JsonPath.read} 是泛型方法，
        // 直接塞进 assertThat 会因为无法推断 T 而命中 Predicate 重载、编译不过。
        // 列表用 asList 比对而不是 toString() 比字符串 —— JsonPath 的 List.toString() 是
        // JSON 形态（["A"]），而 Jackson 反序列化回的是 Java 形态（[A]），两者不等。
        assertThat((Integer) JsonPath.read(body, "$.data.defaults.deptId"))
                .isEqualTo((int) KbConfigDefaults.DEPT_ID);
        assertThat((List<String>) JsonPath.read(body, "$.data.defaults.visibleRoles"))
                .isEqualTo(KbConfigDefaults.VISIBLE_ROLES);
        assertThat((String) JsonPath.read(body, "$.data.defaults.secretLevel"))
                .isEqualTo(KbConfigDefaults.SECRET_LEVEL.name());
        assertThat((String) JsonPath.read(body, "$.data.defaults.businessDomain"))
                .as("业务域里的空格是口径的一部分（'研发 / 平台'），少一个空格会让非默认项 diff 全量飘红")
                .isEqualTo(KbConfigDefaults.BUSINESS_DOMAIN);
        assertThat((String) JsonPath.read(body, "$.data.defaults.splitPolicy.preset"))
                .isEqualTo(KbConfigDefaults.SPLIT_PRESET);
        assertThat((Boolean) JsonPath.read(body, "$.data.defaults.splitPolicy.recursiveSplit"))
                .isEqualTo(KbConfigDefaults.RECURSIVE_SPLIT);
        assertThat((Integer) JsonPath.read(body, "$.data.defaults.tablePolicy.rowLimit"))
                .isEqualTo(KbConfigDefaults.ROW_LIMIT);
        assertThat((Integer) JsonPath.read(body, "$.data.defaults.tablePolicy.rowWindow"))
                .isEqualTo(KbConfigDefaults.ROW_WINDOW);
        assertThat((String) JsonPath.read(body, "$.data.defaults.retrievalConfig.mode"))
                .isEqualTo(KbConfigDefaults.RETRIEVAL_MODE.name());
        assertThat((Double) JsonPath.read(body, "$.data.defaults.retrievalConfig.tableWeight"))
                .isEqualTo(KbConfigDefaults.TABLE_WEIGHT);
    }

    @Test
    @DisplayName("T-402 / W3：VL 模型取不到时返 enabled=false + 空 name / 空 appliedAt，不抛错")
    void vlModelProjectionIsSafe() {
        String token = loginAsAdmin().data("$.accessToken");
        RawResponse response = get("/api/kb/presets", token).assertOk("预设");

        // .env 配了 VL_API_MODEL，所以这里断言的是"结构正确"而非某个具体状态：
        // 关键不变量是 appliedAt 恒为空串（无来源，W3），前端靠 v-if 守卫不渲染「自  起生效」。
        assertThat((String) response.data("$.vlModel.appliedAt"))
                .as("appliedAt 无来源（sys_model_config 到 S6 才接 DB），必须是空串而不是假时间")
                .isEmpty();
        boolean enabled = response.data("$.vlModel.enabled");
        String name = response.data("$.vlModel.name");
        if (!enabled) {
            assertThat(name).as("未启用时 name 为空串（文案由前端决定，后端只给数据）").isEmpty();
        }
    }

    // ==================================================================
    // T-401 · 创建（判据 1 / 2 / 3 / 12 / 13 / 14）
    // ==================================================================

    @Test
    @DisplayName("G3-1：创建向导四步全字段落库 + CUSTOM 预设 + 创建立即可回读（判据 1/2/3）")
    void createPersistsAllFieldsAndReadsBack() {
        String token = loginAsAdmin().data("$.accessToken");
        String code = probeCode();
        long auditBase = maxAuditId();

        Map<String, Object> form = new HashMap<>();
        form.put("name", "B1 全字段探针库");
        form.put("code", code);
        form.put("deptId", 1);
        form.put("visibleRoles", List.of("SUPER_ADMIN", "KB_ADMIN", "AUDITOR"));
        form.put("secretLevel", "CONFIDENTIAL");
        form.put("businessDomain", "研发 / 应用");
        form.put("description", "B1 判据 1 的四步全字段探针");
        form.put("customTags", List.of("探针", "B1"));
        // ⚠️ 非默认值：判据 2 要求 preset=CUSTOM 时 chunkSize 是**手改值**（不是预设带的 512）
        form.put("splitPolicy", Map.of(
                "preset", "CUSTOM", "chunkSize", 896, "overlap", 96,
                "recursiveSplit", false, "semanticSplit", true));
        form.put("tablePolicy", Map.of(
                "atomicTable", true, "dualRepresentation", false, "repeatHeader", false,
                "mergeCrossPage", false, "ocrTable", false, "rowLimit", 12, "rowWindow", 3));
        form.put("retrievalConfig", Map.of(
                "mode", "KEYWORD", "recallThreshold", 0.42, "rerankTopK", 9,
                "rerankEnabled", false, "tableWeight", 0.8, "identifierProtect", false));

        long id;
        try {
            RawResponse created = post("/api/kb", form, token).assertOk("创建知识库");
            id = ((Number) created.data("$.id")).longValue();
            assertThat((String) created.data("$.status"))
                    .as("新库无文档，状态应为域内真值 READY（不是前端的 ACTIVE）")
                    .isEqualTo("READY");

            // ---------- 判据 1 / 2：7 列逐字相等（真查库，不看回显） ----------
            Map<String, Object> row = jdbc.queryForMap("""
                    select name, dept_id, secret_level, business_domain, custom_tags,
                           split_policy_json, table_policy_json, retrieval_config_json, owner_id, status
                    from sys_kb where id = ?
                    """, id);
            assertThat(row.get("name")).isEqualTo("B1 全字段探针库");
            assertThat(((Number) row.get("dept_id")).longValue()).isEqualTo(1L);
            assertThat(row.get("secret_level")).isEqualTo("CONFIDENTIAL");
            assertThat(row.get("business_domain")).isEqualTo("研发 / 应用");
            assertThat((String) row.get("custom_tags")).isEqualTo("[\"探针\",\"B1\"]");

            String split = (String) row.get("split_policy_json");
            assertThat((String) JsonPath.read(split, "$.preset"))
                    .as("手改参数后预设必须落成 CUSTOM")
                    .isEqualTo("CUSTOM");
            assertThat(JsonPath.read(split, "$.chunkSize").toString())
                    .as("chunkSize 必须是手改的 896，不是预设带的 512 —— 顺序写反时在这里暴露")
                    .isEqualTo("896");
            assertThat((Boolean) JsonPath.read(split, "$.semanticSplit")).isTrue();
            assertThat((Boolean) JsonPath.read(split, "$.recursiveSplit")).isFalse();

            String table = (String) row.get("table_policy_json");
            assertThat(JsonPath.read(table, "$.rowLimit").toString()).isEqualTo("12");
            assertThat(JsonPath.read(table, "$.rowWindow").toString()).isEqualTo("3");
            assertThat((Boolean) JsonPath.read(table, "$.dualRepresentation")).isFalse();

            String retrieval = (String) row.get("retrieval_config_json");
            assertThat((String) JsonPath.read(retrieval, "$.mode")).isEqualTo("KEYWORD");
            assertThat(JsonPath.read(retrieval, "$.recallThreshold").toString()).isEqualTo("0.42");
            assertThat(JsonPath.read(retrieval, "$.tableWeight").toString()).isEqualTo("0.8");

            // 创建人 / 计数：owner 取当前登录用户（scope=mine 的依据），计数从 0 起
            assertThat(((Number) row.get("owner_id")).longValue())
                    .isEqualTo(jdbc.queryForObject("select id from sys_user where username = 'admin'", Long.class));
            assertThat(row.get("status")).isEqualTo("READY");

            // 可见角色 → 授权行（档位映射）：SUPER_ADMIN→MANAGE / KB_ADMIN→UPLOAD / 其余→READ
            Map<String, String> levels = new LinkedHashMap<>();
            jdbc.queryForList("""
                            select r.code, g.level from sys_kb_grant g
                            join sys_role r on r.id = g.principal_id
                            where g.kb_id = ? order by r.id
                            """, id)
                    .forEach(r -> levels.put((String) r.get("code"), (String) r.get("level")));
            assertThat(levels).containsEntry("SUPER_ADMIN", "MANAGE")
                    .containsEntry("KB_ADMIN", "UPLOAD")
                    .containsEntry("AUDITOR", "READ");
            assertThat(levels)
                    .as("未勾选的角色不得被授权（否则「可见角色」这个字段就失去了意义）")
                    .doesNotContainKeys("USER", "GUEST");

            // ---------- 判据 3：创建立即可回读，config 与提交值一致 ----------
            RawResponse config = get("/api/kb/" + id + "/config", token).assertOk("创建后回读");
            assertThat((String) config.data("$.config.name")).isEqualTo("B1 全字段探针库");
            assertThat((String) config.data("$.config.code")).isEqualTo(code);
            assertThat(config.data("$.config.deptId").toString()).isEqualTo("1");
            assertThat((String) config.data("$.config.secretLevel")).isEqualTo("CONFIDENTIAL");
            assertThat((String) config.data("$.config.businessDomain")).isEqualTo("研发 / 应用");
            assertThat((String) config.data("$.config.description")).isEqualTo("B1 判据 1 的四步全字段探针");
            assertThat((List<String>) config.data("$.config.customTags")).isEqualTo(List.of("探针", "B1"));
            // 深等比要显式给类型：{@code RawResponse#data} 是泛型方法，
            // 直接塞进 assertThat 会因无法推断 T 命中 Predicate 重载而编译不过。
            assertThat((Map<String, Object>) config.data("$.config.splitPolicy"))
                    .as("回读的 splitPolicy 必须与提交值深等")
                    .isEqualTo(form.get("splitPolicy"));
            assertThat((Map<String, Object>) config.data("$.config.tablePolicy"))
                    .isEqualTo(form.get("tablePolicy"));
            assertThat((Map<String, Object>) config.data("$.config.retrievalConfig"))
                    .isEqualTo(form.get("retrievalConfig"));
            assertThat((List<String>) config.data("$.visibleRoles"))
                    .as("visibleRoles 取自 sys_kb_grant 的角色 code，与提交值一致")
                    .containsExactly("SUPER_ADMIN", "KB_ADMIN", "AUDITOR");
            // W1：status 是域内真值，statusLabel 才是展示文案
            assertThat((String) config.data("$.status")).isEqualTo("READY");
            assertThat((String) config.data("$.statusLabel")).isEqualTo("已就绪");
            assertThat((String) config.data("$.department")).isEqualTo("研发中心");

            // ---------- 判据 14：G3-7 产出半边 —— 建库落审计 ----------
            List<Map<String, Object>> audits = jdbc.queryForList("""
                    select module, action, risk_level, target, changes_json, result
                    from sys_audit_log where action = '创建知识库' and id > ? order by id desc limit 1
                    """, auditBase);
            assertThat(audits).as("建库必须落一条审计").isNotEmpty();
            Map<String, Object> audit = audits.getFirst();
            assertThat(audit.get("module")).isEqualTo("知识库");
            assertThat(audit.get("risk_level"))
                    .as("§4.3 四类不含建库 → NORMAL；标高危会让高危筛选退化成噪声")
                    .isEqualTo("NORMAL");
            assertThat((String) audit.get("target")).contains("B1 全字段探针库");
            assertThat(audit.get("result")).isEqualTo("SUCCESS");
            assertThat(audit.get("changes_json"))
                    .as("建库没有 before，落 NULL 而不是 []（与 A 阶段判据 5 同口径）")
                    .isNull();
        } finally {
            deleteKbByCode(code);
        }
    }

    @Test
    @DisplayName("T-401：校验口径与前端 validateStep0 一致 —— 四种非法各返对应码，且库表零新增")
    void createValidationMatchesFrontend() {
        String token = loginAsAdmin().data("$.accessToken");
        long before = kbCount();

        // ① 名称 1 字 → 10030（@Size(min=2)）
        assertThat(post("/api/kb", formWith(b -> b.put("name", "短"), null), token).code())
                .as("名称 1 字应被参数校验拦下").isEqualTo(10030);

        // ② 编码大写 kb_X → 10030（@Pattern，与前端 /^kb_[a-z0-9_]{2,}$/ 同源）
        assertThat(post("/api/kb", formWith(b -> b.put("code", "kb_X"), null), token).code())
                .as("大写编码前端就拒，后端也必须拒 —— 否则直接调接口能建出脏编码")
                .isEqualTo(10030);

        // ③ 可见角色为空 → 10030（@NotEmpty）
        assertThat(post("/api/kb", formWith(b -> b.put("visibleRoles", List.of()), null), token).code())
                .as("无可见角色的库等于「建完就没人看得见」，应在参数层拦下")
                .isEqualTo(10030);

        // ④ 部门不存在 → 10065（业务校验，要查库）
        assertThat(post("/api/kb", formWith(b -> b.put("deptId", 999_999), null), token).code())
                .as("部门不存在应返 DEPT_NOT_FOUND，而不是建成一个谁都看不见的库")
                .isEqualTo(10065);

        assertThat(kbCount()).as("四种被拒的请求都不该留下行").isEqualTo(before);
    }

    @Test
    @DisplayName("T-401：编码唯一 —— 同一 code 连发两次，第二次 10038 且库表只多 1 行")
    void createRejectsDuplicateCode() {
        String token = loginAsAdmin().data("$.accessToken");
        String code = probeCode();
        long before = kbCount();
        try {
            post("/api/kb", formWith(b -> b.put("code", code), null), token).assertOk("第一次创建");

            RawResponse second = post("/api/kb", formWith(b -> b.put("code", code), null), token);
            assertThat(second.code())
                    .as("编码重复必须返 KB_CODE_EXISTS —— 与「格式不合法」分开，前端才能提示「换个编码」")
                    .isEqualTo(10038);
            assertThat(kbCount()).as("只应多出 1 行").isEqualTo(before + 1);
        } finally {
            deleteKbByCode(code);
        }
    }

    @Test
    @DisplayName("T-401：超管恒加授 MANAGE —— 向导里没勾超管也建不出孤儿库")
    void createAlwaysGrantsSuperAdmin() {
        String token = loginAsAdmin().data("$.accessToken");
        String code = probeCode();
        try {
            // 只勾 AUDITOR，刻意不勾 SUPER_ADMIN
            post("/api/kb", formWith(b -> {
                b.put("code", code);
                b.put("visibleRoles", List.of("AUDITOR"));
            }, null), token).assertOk("创建");

            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select r.code, g.level from sys_kb_grant g
                    join sys_role r on r.id = g.principal_id where g.kb_id =
                      (select id from sys_kb where code = ?)
                    """, code);
            assertThat(rows.stream().map(r -> r.get("code")).toList())
                    .as("超管必须恒被加授 —— 否则可能建出创建者自己都看不见的孤儿库（§3.4 假设 1）")
                    .contains("SUPER_ADMIN");
        } finally {
            deleteKbByCode(code);
        }
    }

    // ==================================================================
    // T-400 · 列表（判据 7 / 8 / 9）
    // ==================================================================

    @Test
    @DisplayName("G2-8：GET /kb 的数据范围与授权过滤真的进了 SQL（含 sys_kb_grant 的 EXISTS）")
    void listFilterIsInSql() {
        String adminToken = loginAsAdmin().data("$.accessToken");

        Logger sqlLogger = (Logger) LoggerFactory.getLogger(BIND_LOGGER);
        Level previousLevel = sqlLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        sqlLogger.addAppender(appender);
        sqlLogger.setLevel(Level.DEBUG);
        try {
            get("/api/kb", adminToken).assertOk("超管列表");
            String adminSql = capturedSql(appender).stream()
                    .filter(s -> s.contains("sys_kb_grant"))
                    .reduce("", (a, b) -> a + "\n" + b);
            assertThat(adminSql).as("应抓到带授权子查询的列表 SQL").isNotBlank();
            assertThat(adminSql).contains("sys_kb_grant");
            assertThat(adminSql).contains("g.principal_id");
            // ③ 授权维度真的进了 where —— 只剩 ② 部门条件的写法会在这里暴露
            appender.list.clear();

            // ② 部门展开：zhang.wei 是 DEPT_AND_CHILD（KB_ADMIN），列表 SQL 里必须有部门树展开
            get("/api/kb", login("zhang.wei", DEMO_PASSWORD).assertOk("演示账号登录")
                    .data("$.accessToken")).assertOk("部门范围列表");
            String deptSql = capturedSql(appender).stream()
                    .filter(s -> s.contains("sys_kb_grant"))
                    .reduce("", (a, b) -> a + "\n" + b);
            assertThat(deptSql)
                    .as("DEPT_AND_CHILD 的列表查询必须带部门树展开（两层结构用 parent_id 等价递归）")
                    .contains("sys_department")
                    .contains("parent_id");
            System.out.println("【T-400 列表 SQL 实录】\n" + deptSql);
        } finally {
            sqlLogger.detachAppender(appender);
            sqlLogger.setLevel(previousLevel);
        }
    }

    @Test
    @DisplayName("T-400：可见性数值 —— ALL 见 8 库 / 部门外见 0 库（I-7 正反向）+ scope=mine")
    void listVisibilityAndScopeMine() {
        String adminToken = loginAsAdmin().data("$.accessToken");
        RawResponse adminList = get("/api/kb?scope=all", adminToken).assertOk("超管列表");
        assertThat(((Number) adminList.data("$.total")).longValue()).isEqualTo(8);
        assertThat((List<?>) adminList.data("$.list")).hasSize(8);

        // I-7 反向：zhang.wei 的部门是平台架构组(11)，8 个库都不在其部门树下 → 0 库
        // （他被 KB_ADMIN 授权了全部库，但 ② 拦下 —— 授权给了不等于看得见）
        String zhangToken = login("zhang.wei", DEMO_PASSWORD).assertOk("张伟登录").data("$.accessToken");
        RawResponse zhangList = get("/api/kb", zhangToken).assertOk("部门外列表");
        assertThat(((Number) zhangList.data("$.total")).longValue())
                .as("授权在但部门范围外 → 0 库（I-7 的「② 拦下」方向）")
                .isZero();

        // 服务层对照：同一账号把基准挪到研发中心(1) 后，恰好看见 kb1
        // （构造 LoginUser 而不改库 —— 引擎只消费 userId/deptId/dataScope，与角色表解耦）
        LoginUser zhangInDept1 = LoginUser.builder()
                .userId(jdbc.queryForObject("select id from sys_user where username = 'zhang.wei'", Long.class))
                .username("zhang.wei").realName("张伟").deptId(1L).superAdmin(false)
                .dataScope(DataScope.DEPT_AND_CHILD).permissions(Set.of("*")).build();
        assertThat(kbGrantService.visibleKbIds(zhangInDept1)).containsExactly(1L);
        assertThat(dataScopeService.kbLevel(zhangInDept1, 1L)).isEqualTo(
                com.knowledge.ai.core.enums.KbGrantLevel.UPLOAD);

        // W5：scope=mine = owner_id 是当前用户。
        // admin 不是任何库的创建人（V8 回填给了具体账号）→ 0 库
        assertThat(((Number) get("/api/kb?scope=mine", adminToken).assertOk("我创建的").data("$.total")).longValue())
                .as("admin 的 owner_id 在 8 个演示库里都不出现 → 0")
                .isZero();
        // li.na 是 kb_sales_contract / kb_sales_talk 的创建人 → 2 库
        String liNaToken = login("li.na", DEMO_PASSWORD).assertOk("李娜登录").data("$.accessToken");
        assertThat(((Number) get("/api/kb?scope=mine", liNaToken).assertOk("李娜我创建的").data("$.total")).longValue())
                .as("「我创建的」必须真的按 owner_id 收窄，否则按钮点了没反应（静默失效）")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("T-400：keyword / deptId / status 在 SQL 层收窄")
    void listFiltersWork() {
        String token = loginAsAdmin().data("$.accessToken");

        assertThat(((Number) get("/api/kb?keyword=合同", token).assertOk("按名搜").data("$.total")).longValue())
                .as("keyword 走 like 收窄").isEqualTo(1);
        assertThat(((Number) get("/api/kb?deptId=2", token).assertOk("按部门").data("$.total")).longValue())
                .as("销售中心名下有 2 个库").isEqualTo(2);
        assertThat(((Number) get("/api/kb?status=INDEXING", token).assertOk("按状态").data("$.total")).longValue())
                .as("种子只有 kb_sales_contract 是 INDEXING").isEqualTo(1);
        // 组合条件：任一维都不该被丢掉
        assertThat(((Number) get("/api/kb?deptId=2&keyword=合同", token).assertOk("组合").data("$.total")).longValue())
                .isEqualTo(1);
    }

    // ==================================================================
    // T-403 · 配置读取（判据 10 / 11）
    // ==================================================================

    @Test
    @DisplayName("T-403：越权读配置返 10022；不存在返 10043（顺序：先判存在再判可见）")
    void configRejectsUnauthorizedAndMissing() {
        // 不存在 → 10043。用 admin（ALL 范围 + kb:list）排除"因权限被拒"的干扰
        String adminToken = loginAsAdmin().data("$.accessToken");
        assertThat(get("/api/kb/999999/config", adminToken).code())
                .as("库不存在必须返 KB_NOT_FOUND，而不是 10022 —— 正常用户输错 id 时要拿到可执行的提示")
                .isEqualTo(10043);

        // 越权 → 10022（kb1 归属研发中心，zhang.wei 的范围是平台架构组）
        String zhangToken = login("zhang.wei", DEMO_PASSWORD).assertOk("张伟登录").data("$.accessToken");
        RawResponse denied = get("/api/kb/1/config", zhangToken);
        assertThat(denied.code())
                .as("有 kb:list 功能权限但逐库授权/范围不放行 → 10022，不是 200 带数据")
                .isEqualTo(10022);
        assertThat(denied.body()).doesNotContain("产品技术文档库");
    }

    // ==================================================================
    // 夹具工具
    // ==================================================================

    /** 本次运行内唯一的探针编码（必须匹配 ^kb_[a-z0-9_]{2,}$，故全小写 + 下划线）。 */
    private String probeCode() {
        return "kb_probe_" + Long.toString(System.nanoTime(), 36);
    }

    /**
     * 以默认表单为底，按需覆盖字段。
     *
     * @param mutate 覆盖动作；{@code code} 未覆盖时用探针编码
     */
    private Map<String, Object> formWith(java.util.function.Consumer<Map<String, Object>> mutate, String code) {
        Map<String, Object> form = new HashMap<>();
        form.put("name", "B1 校验探针库");
        form.put("code", code == null ? probeCode() : code);
        form.put("deptId", 1);
        form.put("visibleRoles", List.of("SUPER_ADMIN"));
        form.put("secretLevel", "INTERNAL");
        form.put("businessDomain", "研发 / 平台");
        form.put("description", "");
        form.put("customTags", List.of());
        form.put("splitPolicy", Map.of("preset", "GENERAL", "chunkSize", 512, "overlap", 64,
                "recursiveSplit", true, "semanticSplit", false));
        form.put("tablePolicy", Map.of("atomicTable", true, "dualRepresentation", true,
                "repeatHeader", true, "mergeCrossPage", true, "ocrTable", true,
                "rowLimit", 30, "rowWindow", 5));
        form.put("retrievalConfig", Map.of("mode", "HYBRID", "recallThreshold", 0.15,
                "rerankTopK", 5, "rerankEnabled", true, "tableWeight", 1.2, "identifierProtect", true));
        mutate.accept(form);
        return form;
    }

    /** 删掉探针库（先授权后主表）。 */
    private void deleteKbByCode(String code) {
        List<Long> ids = jdbc.queryForList("select id from sys_kb where code = ?", Long.class, code);
        for (Long id : ids) {
            jdbc.update("delete from sys_kb_grant where kb_id = ?", id);
            jdbc.update("delete from sys_kb where id = ?", id);
        }
    }

    private long kbCount() {
        Long n = jdbc.queryForObject("select count(*) from sys_kb", Long.class);
        return n == null ? 0L : n;
    }

    private long maxAuditId() {
        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from sys_audit_log", Long.class);
        return max == null ? 0L : max;
    }

    private List<String> capturedSql(ListAppender<ILoggingEvent> appender) {
        List<String> out = new ArrayList<>(appender.list.size());
        for (ILoggingEvent event : appender.list) {
            out.add(event.getFormattedMessage());
        }
        return out;
    }
}
