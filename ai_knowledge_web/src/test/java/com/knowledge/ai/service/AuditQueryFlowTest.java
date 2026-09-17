package com.knowledge.ai.service;

import com.jayway.jsonpath.JsonPath;
import com.knowledge.ai.controller.AuditController;
import com.knowledge.ai.core.async.AsyncTaskInfo;
import com.knowledge.ai.core.async.AsyncTaskService;
import com.knowledge.ai.core.async.AsyncTaskStatus;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M03 阶段 C 门禁：T-302 列表/详情 · T-303 统计 · T-304 导出 · T-305 脱敏 · T-306 留存。
 *
 * <h3>为什么全部走真 HTTP</h3>
 * <p>与 {@code S2AuditFlowTest} / {@code AuditChangeContractTest} 同理由：
 * {@code operator} / {@code apiPath} 取自请求上下文，绕开过滤器直调 Service
 * 会把它们测成空值。而且权限（{@code @RequiresPerm} 的 403）只在真请求下才成立。
 *
 * <h3>本类的自清纪律</h3>
 * <ol>
 *   <li>写用例**一律自己建库**（{@code kb_probe_} 前缀），用完连同授权删掉；</li>
 *   <li>判据 15 写进知识库描述的 {@code sk-} 探针串**随探针库一起消失**；</li>
 *   <li>判据 18 的 200 天前伪造日志行**正是被测对象**（它必须被删掉）；</li>
 *   <li>判据 13 造一个临时角色（只给 {@code audit:view}）而不是改 AUDITOR 的权限行 ——
 *       动种子权限行的话，用例中途挂掉就会留下权限漂移。</li>
 * </ol>
 */
class AuditQueryFlowTest extends ApiTestSupport {

    /** 留存的真实超期天数（>180 才会被清）。 */
    private static final int EXPIRED_DAYS = 200;

    /** 判据 18 的探针行单号前缀（种子核对脚本按它计数，跑完必须为 0）。 */
    private static final String PROBE_LOG_NO_PREFIX = "LOG-RETENTIONPROBE";

    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private AsyncTaskService asyncTaskService;

    @Autowired
    private AuditRetentionJob auditRetentionJob;

    @Autowired
    private ApplicationContext applicationContext;

    private String token;

    /**
     * 本类跑出来的导出任务 id —— 收尾逐个删。
     *
     * <p>⚠️ `audit-export` 的任务行 **`kb_id` 为 NULL**（导出不是知识库级动作），
     * 所以基类那道「无主 kb 产物」兜底扫**根本扫不到它** —— 它没有归属维度可查。
     * 这类"压根没有归属列"的产物只能由**造它的用例**自己点名清。
     * 实测不删的代价：一轮全量留 **2 行/轮**（`S5` 修复前是 76 行/轮，那 74 行是 kb 族的）。
     */
    private final List<String> exportTaskIds = new ArrayList<>();

    @BeforeEach
    void loginAdmin() {
        token = loginAsAdmin().data("$.accessToken");
    }

    /** 收尾：删掉本类造出来的导出任务行（不做的话它们会一轮轮攒在 `sys_async_task` 里）。 */
    @AfterEach
    void cleanExportTasks() {
        for (String taskId : exportTaskIds) {
            jdbc.update("delete from sys_async_task where task_id = ?", taskId);
        }
        exportTaskIds.clear();
    }

    // ==================================================================
    // T-302 · 列表
    // ==================================================================

    @Test
    @DisplayName("判据 1/2/3/4：列表形状 · 时间倒序 · 五个筛选逐个生效 · 时间窗含边界")
    void listShapeOrderFiltersTimeWindow() {
        RawResponse res = get("/api/audit/logs?page=1&size=5", token).assertOk("审计列表");
        assertThat((List<?>) res.data("$.list")).hasSize(5);
        assertThat(((Number) res.data("$.page")).intValue()).isEqualTo(1);
        assertThat(((Number) res.data("$.pageSize")).intValue()).isEqualTo(5);
        assertThat(((Number) res.data("$.total")).longValue())
                .as("total 必须是满足条件的总记录数，不是本页条数")
                .isEqualTo(tableCount("select count(*) from sys_audit_log"));

        // ② 严格时间倒序（同秒用 id 兜底）
        List<Long> ids = ids(res);
        assertThat(ids).isSortedAccordingTo((a, b) -> Long.compare(b, a));
        assertThat(ids).containsExactlyElementsOf(dbIds("""
                select id from sys_audit_log order by created_at desc, id desc limit 5
                """));

        // ③ 五个筛选逐个与 DB 直查对比
        assertSameAsDb("module=知识库", "module = '知识库'");
        assertSameAsDb("riskLevel=HIGH", "risk_level = 'HIGH'");
        assertSameAsDb("result=FAILED", "result = 'FAILED'");
        assertSameAsDb("operator=admin", "operator = 'admin'");
        // keyword 跨 4 列 OR，与 mock 的同一段逻辑逐列一致
        assertSameAsDb("keyword=admin",
                "(operator_name like '%admin%' or operator like '%admin%' "
                        + "or target like '%admin%' or ip like '%admin%')");

        // ④ 时间窗：整日窗口应等于「created_date = 今天」的全部行
        //
        // ⚠️ 为什么断言的是**等式**而不是一个具体数：本用例自己会造审计行
        // （矩阵保存/还原、导出、探针库），硬编码行数必然与实际执行顺序耦合，
        // 表现为"单跑绿、全量红"。等式两边都是**同一时刻**从同一张表取的，与并发无关。
        String today = LocalDate.now().format(DATE);
        long windowed = total("startTime=" + today + "&endTime=" + today);
        assertThat(windowed)
                .as("纯日期 endTime 必须补到 23:59:59 —— 补 00:00:00 会漏掉当天全部记录"
                        + "（日期范围筛选最常见的 off-by-one）")
                .isEqualTo(tableCount(
                        "select count(*) from sys_audit_log where created_date = '" + today + "'"));

        // 带时刻的窗口必须给出**同一个**结果（两种写法并存是刻意的，见 parseTime 的注释）
        assertThat(total("startTime=" + today + "T00:00:00&endTime=" + today + "T23:59:59"))
                .as("ISO `T` 分隔的写法应与纯日期等价")
                .isEqualTo(windowed);

        // ⑤ 时间参数**写坏了要当场报错**，不能静默忽略。
        //    静默忽略的后果是"查全表"：页面上日期范围明明显示着今天，列表却把
        //    三天共 1262 行全列出来 —— 这正是本批 W3 修的那类缺陷
        //    （页面显示的筛选条件与列表内容不一致）。W3 是"根本没发参数"，
        //    这里是"发了但发错了"，修一半等于没修。
        RawResponse malformed = get("/api/audit/logs?page=1&size=1&startTime=2026/09/15", token);
        assertThat(malformed.code())
                .as("无法解析的时间必须报 10030，而不是当成「不筛」返回全表")
                .isEqualTo(10030);

        // ⑥ 时间窗**真的收窄了结果**：这是需求 §4.4「避免一次扫全表」的实质。
        //    只断言"两种写法互相相等"是不够的 —— 两者都忽略窗口时同样相等。
        long unbounded = ((Number) get("/api/audit/logs?page=1&size=1", token)
                .assertOk("无时间窗").data("$.total")).longValue();
        assertThat(unbounded)
                .as("库里不止今天的数据（2026-09-13 起共 %s 行），窗口必须真的收窄结果",
                        unbounded)
                .isGreaterThan(windowed);
    }

    @Test
    @DisplayName("判据 5/6：详情为扁平字段（无嵌套 detail）· changes 空为 null 非 []")
    void detailIsFlatAndChangesNullOrArray() {
        long withChanges = dbIds("select id from sys_audit_log where changes_json is not null "
                + "order by id desc limit 1").getFirst();
        long withoutChanges = dbIds("select id from sys_audit_log where changes_json is null "
                + "order by id desc limit 1").getFirst();

        RawResponse with = get("/api/audit/logs/" + withChanges, token).assertOk("有变更的详情");

        // ① 11 个顶层字段全在顶层，**不存在嵌套的 detail 对象**
        //    （包一层会让前端 `detail.changes` 静默变 undefined —— 不报错、白屏）
        for (String field : List.of("logNo", "traceId", "apiPath", "costMs", "userAgent",
                "affectedUsers", "roleName", "changes", "note", "failReason", "time")) {
            assertThat(hasKey(with.body(), field))
                    .as("详情必须含顶层字段 %s（T-302 验收要点：扁平字段）", field)
                    .isTrue();
        }
        // ② 标量字段必须有值（null 也算"字段在"，所以这一条是独立断言）
        for (String field : List.of("logNo", "traceId", "apiPath", "costMs", "userAgent", "time")) {
            assertThat(hasValue(with.body(), field))
                    .as("标量字段 %s 不应为 null", field)
                    .isTrue();
        }
        assertThat(hasKey(with.body(), "detail"))
                .as("详情**不能**有嵌套的 detail 对象")
                .isFalse();

        // ② changes 是数组，长度 = DB 里的 JSON 元素数
        List<?> changes = with.data("$.changes");
        int dbLength = JsonPath.<List<?>>read(
                tableString("select changes_json from sys_audit_log where id = " + withChanges), "$").size();
        assertThat(changes).hasSize(dbLength);

        // ③ 无变更 → null（不是 []）
        String noneBody = get("/api/audit/logs/" + withoutChanges, token).assertOk("无变更的详情").body();
        assertThat(JsonPath.<Object>read(noneBody, "$.data.changes"))
                .as("changes_json 为 NULL 时响应里 changes 必须是 null，不能是空数组 —— "
                        + "null = 本就没有对照，[] = 采集过但为空（T-300 冻结口径）")
                .isNull();
    }

    @Test
    @DisplayName("判据 7（W1 修复）：详情 roleName = **受影响角色名**，不是操作人角色码")
    void detailRoleNameIsAffectedRoleNotOperatorRole() {
        long roleId = 5L; // GUEST（外部访客）：非超管、可写，与 AuditChangeContractTest 同口径
        String itemKey = "sys:audit";
        String before = matrixLevel(roleId, itemKey);
        String after = "NONE".equals(before) ? "READ" : "NONE";
        try {
            long base = maxAuditId();
            put("/api/permissions/matrix",
                    Map.of("changes", List.of(Map.of("roleId", roleId, "permKey", itemKey, "level", after))),
                    token).assertOk("矩阵保存");

            long auditId = dbIds("select id from sys_audit_log where action = '保存功能矩阵' and id > "
                    + base + " order by id desc limit 1").getFirst();
            RawResponse detail = get("/api/audit/logs/" + auditId, token).assertOk("矩阵日志详情");

            assertThat((String) detail.data("$.roleName"))
                    .as("roleName 必须是**被改的那个角色名**（外部访客）。"
                            + "若这里出现 SUPER_ADMIN，说明取的是 role_name 列（操作人角色码）——"
                            + "抽屉会渲染成「N 人（SUPER_ADMIN 下全部成员）」，数字对、语义错")
                    .isEqualTo("外部访客");
            assertThat((Integer) detail.data("$.affectedUsers"))
                    .as("人数与角色名同源（都是 GUEST 的成员数）")
                    .isEqualTo(expectedMembersOf(roleId));

            // 落库侧也验一遍（响应对了但列没写，是另一种坏法）
            assertThat(tableString("select affected_role from sys_audit_log where id = " + auditId))
                    .isEqualTo("外部访客");
        } finally {
            put("/api/permissions/matrix",
                    Map.of("changes", List.of(Map.of("roleId", roleId, "permKey", itemKey, "level", before))),
                    token).assertOk("矩阵还原");
        }
    }

    @Test
    @DisplayName("判据 8：无 audit:view 一律 403 + 10020")
    void withoutAuditViewForbidden() {
        // li.na 是 USER 角色（无 audit:view），且真实可登录
        String userToken = login("li.na", "Init@12345").assertOk("李娜登录").data("$.accessToken");

        RawResponse list = get("/api/audit/logs", userToken);
        assertThat(list.status()).isEqualTo(403);
        assertThat(list.code()).isEqualTo(10020);

        assertThat(get("/api/audit/stats", userToken).status()).isEqualTo(403);
        assertThat(get("/api/audit/logs/1", userToken).status()).isEqualTo(403);
    }

    // ==================================================================
    // T-303 · 统计
    // ==================================================================

    @Test
    @DisplayName("判据 9/10：四项统计与 DB 实算一致；?date= 支持历史日期")
    void statsMatchesDatabase() {
        assertStatsFor(LocalDate.now());

        // ② 历史日期（演示库里有 2026-09-14 的行）
        LocalDate historical = LocalDate.parse("2026-09-14");
        assertStatsFor(historical);
    }

    /** 四项逐条与 DB 直算比对。 */
    private void assertStatsFor(LocalDate date) {
        RawResponse res = get("/api/audit/stats?date=" + date.format(DATE), token).assertOk("统计 " + date);
        String d = date.format(DATE);
        assertThat(((Number) res.data("$.today")).longValue())
                .as("%s 的 today", d)
                .isEqualTo(tableCount("select count(*) from sys_audit_log where created_date = '" + d + "'"));
        assertThat(((Number) res.data("$.highRisk")).longValue())
                .isEqualTo(tableCount("select count(*) from sys_audit_log where created_date = '" + d
                        + "' and risk_level = 'HIGH'"));
        assertThat(((Number) res.data("$.activeUsers")).longValue())
                .as("activeUsers = count(distinct operator)（匿名不计入）")
                .isEqualTo(tableCount("select count(distinct operator) from sys_audit_log where created_date = '"
                        + d + "'"));
        assertThat(((Number) res.data("$.failed")).longValue())
                .isEqualTo(tableCount("select count(*) from sys_audit_log where created_date = '" + d
                        + "' and result = 'FAILED'"));
    }

    // ==================================================================
    // T-304 · 导出（含审计自审 G3-9）
    // ==================================================================

    @Test
    @DisplayName("判据 11/12：导出返回 taskId 且任务真跑出可读 CSV；导出本身留痕（G3-9）")
    void exportProducesCsvAndIsSelfAudited() throws Exception {
        long base = maxAuditId();
        String today = LocalDate.now().format(DATE);

        RawResponse accepted = post("/api/audit/export",
                Map.of("startTime", today, "endTime", today), token).assertOk("提交导出");
        String taskId = accepted.data("$.taskId");
        assertThat(taskId).isNotBlank();
        exportTaskIds.add(taskId);
        assertThat((String) accepted.data("$.note")).as("提示语非空（前端直接展示它）").isNotBlank();

        // ② 等任务真跑完 —— 只断言"接口返回了 taskId"是不够的：
        //    任务体里查库 / 生成 CSV / 写 MinIO 任一步挂了，接口照样返回 taskId。
        AsyncTaskInfo<?> info = awaitTerminal(taskId);
        assertThat(info.getStatus())
                .as("导出任务必须真跑成功，errorMsg=%s", info.getErrorMsg())
                .isEqualTo(AsyncTaskStatus.DONE);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) info.getResult();
        assertThat(payload).as("任务结果应带产物定位信息").containsKeys("objectKey", "bytes", "fileUrl", "rows");
        assertThat((Integer) payload.get("bytes")).as("CSV 不能是空文件").isGreaterThan(0);

        // ③ G3-9 审计自审：导出这件事自己也要留一条
        Map<String, Object> row = jdbc.queryForMap("""
                select module, action, risk_level, operator, result
                from sys_audit_log where action = '导出审计日志' and id > ? order by id desc limit 1
                """, base);
        assertThat(row.get("module")).isEqualTo("审计");
        assertThat(row.get("risk_level"))
                .as("§4.3② 数据导出 → HIGH（AuditRiskPolicy 已从 PLANNED 移入 IMPLEMENTED）")
                .isEqualTo("HIGH");
        assertThat(row.get("operator")).isEqualTo(ADMIN);
        assertThat(row.get("result")).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("判据 13：导出需 audit:export —— 只有 audit:view 的账号拿 403")
    void exportRequiresAuditExportPermission() {
        // 造一个**只给 audit:view** 的临时角色 + 临时账号。
        // 为什么不改 AUDITOR 的权限行：用例中途挂掉就会留下种子权限漂移。
        String roleCode = "AUDIT_VIEW_ONLY_PROBE";
        Long roleId = null;
        try {
            jdbc.update("""
                    insert into sys_role (code, name, description, data_scope, enabled, builtin, sort,
                                          created_at, updated_at)
                    values (?, '审计只读探针', 'T-302 判据13 临时角色', 'ALL', 1, 0, 99, now(), now())
                    """, roleCode);
            roleId = jdbc.queryForObject("select id from sys_role where code = ?", Long.class, roleCode);
            jdbc.update("""
                    insert into sys_role_permission (role_id, permission_id, created_at)
                    select ?, id, now() from sys_permission where code = 'audit:view'
                    """, roleId);

            TestUser user = createUser("auditee");
            // 换成临时角色（createUser 自动带 USER 角色；JWT 里的权限快照在登录时生成，
            // 所以换完角色要**重新登录**才生效）
            jdbc.update("delete from sys_user_role where user_id = "
                    + "(select id from sys_user where username = ?)", user.username());
            jdbc.update("""
                    insert into sys_user_role (user_id, role_id, created_at)
                    select id, ?, now() from sys_user where username = ?
                    """, roleId, user.username());
            String onlyViewToken = login(user.username(), user.password())
                    .assertOk("临时账号重新登录").data("$.accessToken");

            // 有 audit:view → 列表能读
            get("/api/audit/logs?page=1&size=1", onlyViewToken).assertOk("只读账号看列表");

            // 但没有 audit:export → 导出 403
            RawResponse forbidden = post("/api/audit/export", Map.of(), onlyViewToken);
            assertThat(forbidden.status())
                    .as("两个权限码互不蕴含：只有 audit:view 时导出必须被拒")
                    .isEqualTo(403);
            assertThat(forbidden.code()).isEqualTo(10020);

            // 且导出**没有**留下成功痕迹 —— 切面在 finally 里记一条 FAILED（那是正确的：
            // 「谁尝试导出但被拒」正是审计要回答的问题之一）。所以这里断言的是
            // 「没有成功的导出记录」，而不是「一条记录都没有」。
            assertThat(tableCount("select count(*) from sys_audit_log where operator = '"
                    + user.username() + "' and action = '导出审计日志' and result = 'SUCCESS'"))
                    .as("被拒的导出不得记成成功")
                    .isZero();
            assertThat(tableCount("select count(*) from sys_audit_log where operator = '"
                    + user.username() + "' and action = '导出审计日志' and result = 'FAILED'"))
                    .as("被拒的导出必须留痕（切点包 finally，失败也记）")
                    .isEqualTo(1);
        } finally {
            if (roleId != null) {
                jdbc.update("delete from sys_user_role where role_id = ?", roleId);
                jdbc.update("delete from sys_role_permission where role_id = ?", roleId);
                jdbc.update("delete from sys_role where id = ?", roleId);
            }
        }
    }

    // ==================================================================
    // T-305 · 脱敏（G3-10）
    // ==================================================================

    @Test
    @DisplayName("判据 14：全项目 @AuditLog 的 recordParams 恒为 false")
    void recordParamsNeverEnabled() {
        List<String> offenders = new ArrayList<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> type;
            try {
                type = org.springframework.aop.support.AopUtils
                        .getTargetClass(applicationContext.getBean(beanName));
            } catch (Exception ex) {
                continue;
            }
            if (!type.getName().startsWith("com.knowledge.ai.")
                    || type.getName().startsWith("com.knowledge.ai.core.audit.")) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                com.knowledge.ai.core.audit.AuditLog annotation =
                        method.getAnnotation(com.knowledge.ai.core.audit.AuditLog.class);
                if (annotation != null && annotation.recordParams()) {
                    offenders.add(type.getSimpleName() + "#" + method.getName());
                }
            }
        }
        assertThat(offenders)
                .as("I-15：入参可能含密码 / API Key，recordParams 一旦打开就会把密钥写进长期留存的日志")
                .isEmpty();
    }

    @Test
    @DisplayName("判据 15/16（G3-10）：Key 形状字符串不进 DB，也不进导出 CSV")
    void keyShapedValueNeverReachesDatabaseOrExport() throws Exception {
        String probeKey = "sk-abcdefghijkl0123456789"; // 24 位，命中 `sk-` + ≥16 位
        String code = "kb_probe_leak_" + Math.floorMod(System.nanoTime(), 100000);
        String kbId = null;
        try {
            kbId = String.valueOf(((Number) post("/api/kb", configForm(code, "脱敏探针库", "普通描述"), token)
                    .assertOk("建脱敏探针库").data("$.id")).longValue());

            long base = maxAuditId();
            // 把密钥形状的串写进**自由文本**（描述）—— 产出侧的两层避让挡不住这种写法
            put("/api/kb/" + kbId + "/config",
                    saveBody(code, "脱敏探针库", "临时测试，key 用 " + probeKey), token).assertOk("保存含密钥的描述");

            Map<String, Object> row = jdbc.queryForMap("""
                    select target, changes_json, note, fail_reason, api_path
                    from sys_audit_log where action = '保存配置' and id > ? order by id desc limit 1
                    """, base);
            String changesJson = (String) row.get("changes_json");
            assertThat(changesJson).as("描述改了必须进变更明细").isNotBlank();
            assertThat(changesJson)
                    .as("落库前脱敏：changes_json 里不得出现密钥原文")
                    .doesNotContain(probeKey)
                    .contains("***");
            for (String column : List.of("target", "note", "fail_reason", "api_path")) {
                // ⚠️ 这几列**多数本来就是 NULL**（note / fail_reason 只在特定动作才有值），
                // 所以必须先把 null 归一成空串再断言 —— 直接 doesNotContain 会报
                // "Expecting actual not to be null"，那是断言写错，不是脱敏坏了。
                String value = row.get(column) == null ? "" : String.valueOf(row.get(column));
                assertThat(value)
                        .as("自由文本列 %s 不得含密钥原文", column)
                        .doesNotContain("sk-");
            }
            assertThat(tableString("select description from sys_kb where id = " + kbId))
                    .as("脱敏只作用于审计，**不改业务数据** —— 业务侧该怎么存还怎么存，"
                            + "否则用户会发现自己的描述被系统改写了")
                    .contains(probeKey);

            // 判据 16：导出 CSV 里也不能有明文 Key
            RawResponse accepted = post("/api/audit/export", Map.of("keyword", "知识库#" + kbId), token)
                    .assertOk("导出含探针的那一页");
            exportTaskIds.add(accepted.data("$.taskId"));
            AsyncTaskInfo<?> info = awaitTerminal(accepted.data("$.taskId"));
            String objectKey = String.valueOf(
                    ((Map<?, ?>) info.getResult()).get("objectKey"));
            String csv = readObject(objectKey);
            assertThat(csv)
                    .as("导出文件不得含明文 Key（脱敏在写入侧，导出只是读同一份数据）")
                    .doesNotContain("sk-")
                    .contains("***");
            assertThat(csv.split("\r\n", -1).length)
                    .as("CSV 至少有表头 + 1 行数据")
                    .isGreaterThan(2);
        } finally {
            if (kbId != null) {
                jdbc.update("delete from sys_kb_grant where kb_id = " + kbId);
                jdbc.update("delete from sys_kb where id = " + kbId);
            }
        }
    }

    // ==================================================================
    // T-306 · 留存 + 不可改删
    // ==================================================================

    @Test
    @DisplayName("判据 17：AuditController 只有 4 个只读映射，无 PUT/PATCH/DELETE")
    void noMutatingEndpoints() {
        Set<String> mappings = new LinkedHashSet<>();
        for (Method method : AuditController.class.getDeclaredMethods()) {
            String verb = verbOf(method);
            if (verb == null) {
                continue;
            }
            for (String path : pathsOf(method)) {
                mappings.add(verb + " " + ROOT_PATH + path);
            }
        }
        assertThat(mappings)
                .as("审计日志的接口层不得有任何写入口（《05》§4.8）")
                .containsExactlyInAnyOrder(
                        "GET /api/audit/logs",
                        "GET /api/audit/logs/{id}",
                        "GET /api/audit/stats",
                        "POST /api/audit/export");
        assertThat(AuditController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly(ROOT_PATH);
    }

    /** 类级 {@code @RequestMapping} 的前缀（写死会与方法级路径脱节，故从注解读）。 */
    private static final String ROOT_PATH =
            AuditController.class.getAnnotation(RequestMapping.class).value()[0];

    private static String verbOf(Method method) {
        if (method.getAnnotation(GetMapping.class) != null) {
            return "GET";
        }
        if (method.getAnnotation(PostMapping.class) != null) {
            return "POST";
        }
        if (method.getAnnotation(PutMapping.class) != null) {
            return "PUT";
        }
        if (method.getAnnotation(PatchMapping.class) != null) {
            return "PATCH";
        }
        if (method.getAnnotation(DeleteMapping.class) != null) {
            return "DELETE";
        }
        return null;
    }

    private static List<String> pathsOf(Method method) {
        String[] values;
        GetMapping get = method.getAnnotation(GetMapping.class);
        PostMapping post = method.getAnnotation(PostMapping.class);
        PutMapping put = method.getAnnotation(PutMapping.class);
        PatchMapping patch = method.getAnnotation(PatchMapping.class);
        DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
        if (get != null) {
            values = get.value();
        } else if (post != null) {
            values = post.value();
        } else if (put != null) {
            values = put.value();
        } else if (patch != null) {
            values = patch.value();
        } else {
            values = delete.value();
        }
        return values.length == 0 ? List.of("") : List.of(values);
    }

    @Test
    @DisplayName("判据 18：留存清理只删超期行，180 天内的真实行一行未少")
    void retentionPurgesOnlyExpired() {
        long before = tableCount("select count(*) from sys_audit_log");
        LocalDateTime expiredAt = LocalDateTime.now().minusDays(EXPIRED_DAYS);
        String logNo = PROBE_LOG_NO_PREFIX + "-" + Math.floorMod(System.nanoTime(), 1000000);
        try {
            jdbc.update("""
                    insert into sys_audit_log (log_no, trace_id, module, action, risk_level, target,
                                               operator, operator_name, ip, result, changes_json,
                                               created_at, created_date)
                    values (?, 'probe', '系统', '留存探针', 'NORMAL', '留存探针',
                            'admin', '系统管理员', '127.0.0.1', 'SUCCESS', null, ?, ?)
                    """, logNo, expiredAt, expiredAt.toLocalDate());

            int deleted = auditRetentionJob.purgeBefore(LocalDate.now().minusDays(auditRetentionJob.retentionDays()));
            assertThat(deleted).as("恰好删掉那一条探针行").isEqualTo(1);
            assertThat(tableCount("select count(*) from sys_audit_log"))
                    .as("删完总数恰好回到插入前 —— 180 天内的行一行未少")
                    .isEqualTo(before);
            assertThat(auditRetentionJob.retentionDays()).as("留存期来自配置，默认 180").isEqualTo(180);
        } finally {
            jdbc.update("delete from sys_audit_log where log_no like ?", PROBE_LOG_NO_PREFIX + "%");
        }
    }

    // ==================================================================
    // 私有：HTTP / SQL 小工具
    // ==================================================================

    /** 用同一组筛选条件发一次列表请求，与 DB 直查的 id 序列逐行比对。 */
    private void assertSameAsDb(String query, String whereSql) {
        RawResponse res = get("/api/audit/logs?page=1&size=20&" + query, token).assertOk("列表 " + query);
        assertThat(ids(res))
                .as("筛选 %s 的结果应与 DB 同条件查询逐行一致", query)
                .containsExactlyElementsOf(dbIds("select id from sys_audit_log where " + whereSql
                        + " order by created_at desc, id desc limit 20"));
    }

    private List<Long> ids(RawResponse res) {
        List<Number> raw = res.data("$.list[*].id");
        return raw.stream().map(Number::longValue).toList();
    }

    /** 列表 total（只传筛选条件，page/size 固定为 1）。 */
    private long total(String query) {
        return ((Number) get("/api/audit/logs?page=1&size=1&" + query, token)
                .assertOk("列表 " + query).data("$.total")).longValue();
    }

    private List<Long> dbIds(String sql) {
        return jdbc.queryForList(sql, Long.class);
    }

    private long tableCount(String sql) {
        Number n = jdbc.queryForObject(sql, Number.class);
        return n == null ? 0L : n.longValue();
    }

    private String tableString(String sql) {
        List<String> rows = jdbc.queryForList(sql, String.class);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private long maxAuditId() {
        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from sys_audit_log", Long.class);
        return max == null ? 0L : max;
    }

    private String matrixLevel(long roleId, String itemKey) {
        List<String> rows = jdbc.queryForList(
                "select level from sys_role_matrix where role_id = ? and item_key = ?",
                String.class, roleId, itemKey);
        return rows.isEmpty() ? "NONE" : rows.getFirst();
    }

    private int expectedMembersOf(long roleId) {
        Integer n = jdbc.queryForObject("""
                select count(distinct ur.user_id) from sys_user_role ur
                join sys_user u on u.id = ur.user_id and u.deleted_at is null
                where ur.role_id = ?
                """, Integer.class, roleId);
        return n == null ? 0 : n;
    }

    /** 轮询异步任务直到终态（导出是异步的，"接口返回 taskId"不等于任务成功）。 */
    private AsyncTaskInfo<?> awaitTerminal(String taskId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Optional<AsyncTaskInfo<Object>> found = asyncTaskService.find(taskId);
            if (found.isPresent() && found.get().getStatus().terminal()) {
                return found.get();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("导出任务 10 秒内未到终态，taskId=" + taskId);
    }

    // ---- 建库表单（与 KbWriteFlowTest 同形；这里只需要 name/description 两处可改）----

    /**
     * ⚠️ 两个接口的**请求体形状不同**，这是本仓一个容易踩的点：
     * <ul>
     *   <li>{@code POST /kb}（创建）收**裸的** {@code KbConfigForm}；</li>
     *   <li>{@code PUT /kb/{id}/config}（保存）收 {@code {config: KbConfigForm}}。</li>
     * </ul>
     * 统一包一层会让创建拿到 10030（config 为 null），看着像"建库接口坏了"。
     */
    private Map<String, Object> configForm(String code, String name, String description) {
        Map<String, Object> config = new HashMap<>();
        config.put("name", name);
        config.put("code", code);
        config.put("deptId", 1);
        config.put("visibleRoles", List.of("SUPER_ADMIN"));
        config.put("secretLevel", "INTERNAL");
        config.put("businessDomain", "研发 / 平台");
        config.put("description", description);
        config.put("customTags", List.of());
        config.put("splitPolicy", Map.of("preset", "GENERAL", "chunkSize", 512, "overlap", 64,
                "recursiveSplit", true, "semanticSplit", false));
        config.put("tablePolicy", Map.of("atomicTable", true, "dualRepresentation", true,
                "repeatHeader", true, "mergeCrossPage", true, "ocrTable", true,
                "rowLimit", 30, "rowWindow", 5));
        config.put("retrievalConfig", Map.of("mode", "HYBRID", "recallThreshold", 0.15,
                "rerankTopK", 5, "rerankEnabled", true, "tableWeight", 1.2, "identifierProtect", true));
        return config;
    }

    /** 保存接口的请求体形状：{@code {config: form}}。 */
    private Map<String, Object> saveBody(String code, String name, String description) {
        Map<String, Object> body = new HashMap<>();
        body.put("config", configForm(code, name, description));
        return body;
    }

    /** 用 MinIO SDK 读回对象（导出产物是 CSV 文本）。 */
    private String readObject(String objectKey) throws Exception {
        try (java.io.InputStream in = minioClient.getObject(
                io.minio.GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Autowired
    private io.minio.MinioClient minioClient;

    @org.springframework.beans.factory.annotation.Value("${minio.bucket:rag-documents}")
    private String bucket;

    /**
     * {@code data} 对象里是否存在该键（**值可以是 null**）。
     *
     * <p>为什么不能写成 {@code JsonPath.read(body, "$.data.note") != null}：
     * 那样"键在但值为 null"与"键不在"会被判成同一个结果，而这恰恰是要区分的两件事。
     *
     * <p>本仓没配 {@code spring.jackson.default-property-inclusion: non_null}，
     * 所以 null 字段依然出现在 JSON 里；但这条**很容易被人顺手打开全局剪枝**。
     * 前端若用 `Object.keys` 或表格自动列生成，剪掉一列是静默的 —— 所以钉住它。
     */
    private static boolean hasKey(String body, String field) {
        try {
            Map<String, Object> data = JsonPath.parse(body).read("$.data", Map.class);
            return data != null && data.containsKey(field);
        } catch (Exception ex) {
            return false;
        }
    }

    /** 键存在且值非 null。 */
    private static boolean hasValue(String body, String field) {
        try {
            Object value = JsonPath.parse(body).read("$.data." + field);
            return value != null;
        } catch (Exception ex) {
            return false;
        }
    }
}
