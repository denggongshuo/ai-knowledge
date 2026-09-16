package com.knowledge.ai.service;

import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.AuditRiskPolicy;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3-M03-C2 审计补打点取证：**覆盖率本体**（判据 1~12）。
 *
 * <p>与既有两类用例的分工：{@link S2AuditFlowTest} 管批次 5 的三处 🚩 入口留没留痕；
 * {@link AuditChangeContractTest} 管 T-300/T-301 的通用契约（注解 ↔ 策略表、changes_json
 * 的规范化与不跨线程泄漏）；<b>本类</b>管 T-3C3 补的 11 个动作<b>逐个走真 HTTP</b> ——
 * 既钉住三元组冻结清单，也钉住每条日志的变更明细正文（前端「变更前 → 变更后」列直接读它）。
 *
 * <p>覆盖率为什么用冻结清单：扫描式断言只能发现「少登记了」，发现不了「少打了一个点」——
 * 某个写方法忘了挂 {@code @AuditLog}，扫描结果照样自洽、用例照样绿。
 * {@link #expectedActions()} 把裁定过的 25 个动作逐条写死，少一个、多一个、等级错一档都红。
 *
 * <p>⚠️ 与计划书的算术偏差（实测为准）：计划头写「总数由 12 → 23」，实测 HEAD 为 <b>13</b>、
 * C2 结束时为 <b>24</b>（新增 11 条不变）。差异来自撰写时把 {@code AuditContext} 的 javadoc 示例
 * 算成了真实注解。本类断言实测值 24，已回填计划 §8。
 *
 * <p><b>S4-M05-A（2026-09-16）加 1 条 → 25</b>：`T-500` `DocService#upload` 的
 * 「文档 / 上传文档 / NORMAL」。同步三处（本仓冻结口径）：① 注解 ②
 * {@code AuditRiskPolicy.IMPLEMENTED} 登记带理由 ③ 本清单 —— 漏任一处本用例即红。
 */
class AuditCoverageFlowTest extends ApiTestSupport {

    /**
     * 前端 {@code AuditLogView.vue} 的 MODULES 下拉 —— 审计模块名的取值域。
     *
     * <p>本批新增用到了「系统」（部门三连）这个以前没人用过的模块：模块名不在下拉里的话，
     * 日志**写得进去但筛不出来**，从运营视角等于丢了。
     */
    private static final List<String> FRONTEND_MODULES =
            List.of("认证", "用户", "授权", "知识库", "文档", "问答", "系统", "审计");

    /** 本批（T-3C3）新增的 11 个动作 —— 与计划 §2 逐条一致。 */
    private static final List<String> NEW_IN_C2 = List.of(
            AuditModule.USER + " 新建用户",
            AuditModule.USER + " 批量导入用户",
            AuditModule.USER + " 启用/停用账号",
            AuditModule.USER + " 重置密码（管理员）",
            AuditModule.GRANT + " 编辑角色",
            AuditModule.GRANT + " 启用/停用角色",
            AuditModule.GRANT + " 新建角色",
            AuditModule.GRANT + " 删除角色",
            AuditModule.SYSTEM + " 新建部门",
            AuditModule.SYSTEM + " 编辑部门",
            AuditModule.SYSTEM + " 删除部门");

    private String token;

    @BeforeEach
    void loginAdmin() {
        token = loginAsAdmin().data("$.accessToken");
    }

    // ==================================================================
    // 冻结清单与扫描（判据 1 / 2）
    // ==================================================================

    /**
     * 裁定过的 25 个动作 → 风险等级。
     *
     * <p>写死而**不从 {@code AuditRiskPolicy} 读**：策略表自己就是被测对象，
     * 两边都"自证"等于没测 —— 策略表漏登记、注解也漏挂时，双向比对照样通过。
     */
    private static Map<String, RiskLevel> expectedActions() {
        Map<String, RiskLevel> expected = new LinkedHashMap<>();
        // ---- 用户（10）----
        expected.put(AuditModule.USER + " 编辑用户（含角色调整）", RiskLevel.HIGH);
        expected.put(AuditModule.USER + " 新建用户", RiskLevel.HIGH);
        expected.put(AuditModule.USER + " 批量导入用户", RiskLevel.HIGH);
        expected.put(AuditModule.USER + " 启用/停用账号", RiskLevel.HIGH);
        expected.put(AuditModule.USER + " 重置密码（管理员）", RiskLevel.HIGH);
        expected.put(AuditModule.USER + " 删除用户", RiskLevel.HIGH);
        expected.put(AuditModule.USER + " 换绑手机号", RiskLevel.HIGH);
        expected.put(AuditModule.USER + " 换绑邮箱", RiskLevel.HIGH);
        expected.put(AuditModule.USER + " 修改个人资料", RiskLevel.NORMAL);
        expected.put(AuditModule.USER + " 更换头像", RiskLevel.NORMAL);
        // ---- 授权（6）----
        expected.put(AuditModule.GRANT + " 保存功能矩阵", RiskLevel.HIGH);
        expected.put(AuditModule.GRANT + " 调整知识库授权", RiskLevel.HIGH);
        expected.put(AuditModule.GRANT + " 编辑角色", RiskLevel.HIGH);
        expected.put(AuditModule.GRANT + " 启用/停用角色", RiskLevel.HIGH);
        expected.put(AuditModule.GRANT + " 新建角色", RiskLevel.NORMAL);
        expected.put(AuditModule.GRANT + " 删除角色", RiskLevel.NORMAL);
        // ---- 知识库（2）----
        expected.put(AuditModule.KB + " 保存配置", RiskLevel.HIGH);
        expected.put(AuditModule.KB + " 创建知识库", RiskLevel.NORMAL);
        // ---- 文档（1）---- （S4-M05-A 新增：上传入库）
        expected.put(AuditModule.DOC + " 上传文档", RiskLevel.NORMAL);
        // ---- 认证（2）----
        expected.put(AuditModule.AUTH + " 修改密码", RiskLevel.HIGH);
        expected.put(AuditModule.AUTH + " 重置密码（忘记密码自助）", RiskLevel.HIGH);
        // ---- 系统（3）----
        expected.put(AuditModule.SYSTEM + " 新建部门", RiskLevel.NORMAL);
        expected.put(AuditModule.SYSTEM + " 编辑部门", RiskLevel.NORMAL);
        expected.put(AuditModule.SYSTEM + " 删除部门", RiskLevel.NORMAL);
        // ---- 审计（1）----
        expected.put(AuditModule.AUDIT + " 导出审计日志", RiskLevel.HIGH);
        return expected;
    }

    /**
     * 扫全仓的 {@code @AuditLog}。
     *
     * <p>用 classpath 扫描而不是扒 Spring 容器的代理对象：注解挂在 Service 的 public 方法上，
     * 扫描器直接读字节码，既不受 JDK 动态代理影响，也不依赖 bean 是否已被实例化。
     *
     * <p>⚠️ 这里必须放**全通过**的过滤器，不能写 {@code AnnotationTypeFilter(AuditLog.class)}：
     * {@code AnnotationTypeFilter} 只看**类上的**注解，而 {@code @AuditLog} 挂在方法上，
     * 用它筛出来的候选是 0 个 —— 用例会以「清单为空」的姿态"通过"，正是最危险的假绿
     * （实测踩过：扫描结果 {@code {}}，报错信息反而是"Expected size: 24 but was: 0"）。
     */
    private Map<String, RiskLevel> scanAnnotations() {
        // 只认**生产**类：src/test 下的「探针」类（AuditChangeContractTest / S2AuditFlowTest
        // 用的 探针-成功 / 探针-失败）同样挂着 @AuditLog，一起扫进来会得到 26 而不是 24。
        // 判据是注解所在类的 code source 是否就是生产根（mvn 下即 target/classes）。
        URL productionRoot = AuditLog.class.getProtectionDomain().getCodeSource().getLocation();

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((metadataReader, metadataReaderFactory) -> true);
        Map<String, RiskLevel> found = new LinkedHashMap<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.knowledge.ai")) {
            Class<?> type;
            try {
                type = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("扫描出的类加载不到：" + definition.getBeanClassName(), e);
            }
            if (!isProductionClass(type, productionRoot)) {
                continue;
            }
            for (Method method : type.getMethods()) {
                AuditLog annotation = method.getAnnotation(AuditLog.class);
                if (annotation == null) {
                    continue;
                }
                found.put(annotation.module() + " " + annotation.action(), annotation.risk());
            }
        }
        assertThat(found)
                .as("必须扫到生产类：结果为空说明过滤器或 classpath 变了 —— 那会让本用例变成假绿")
                .isNotEmpty();
        return found;
    }

    private static boolean isProductionClass(Class<?> type, URL productionRoot) {
        CodeSource codeSource = type.getProtectionDomain().getCodeSource();
        return codeSource != null && productionRoot.equals(codeSource.getLocation());
    }

    // ==================================================================
    // 落库取证
    // ==================================================================

    /** 本用例动作之前的最大日志 id —— 用它划出"这段是我造的痕迹"，不去猜时序。 */
    private long maxAuditId() {
        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from sys_audit_log", Long.class);
        return max == null ? 0L : max;
    }

    /**
     * 取 {@code id > afterId} 的指定动作日志，断言**恰好一条**。
     *
     * <p>多于一条说明同一动作被记了两次（切面重复织入或方法被调了两遍），
     * 这在审计里是"同一次操作看起来像两次"，必须红。
     */
    private Map<String, Object> latestAudit(String action, long afterId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, module, action, risk_level, operator, result, target, changes_json, "
                        + "affected_users, affected_role, fail_reason "
                        + "from sys_audit_log where action = ? and id > ? order by id desc",
                action, afterId);
        assertThat(rows).as("动作 [%s] 必须留痕一条（id > %s）", action, afterId).hasSize(1);
        return rows.get(0);
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private long anyRealDeptId() {
        Long id = jdbc.queryForObject("select id from sys_department order by id limit 1", Long.class);
        assertThat(id).as("种子部门必须存在（新建用户要求 deptId 真实）").isNotNull();
        return id;
    }

    private long adminUserId() {
        Long id = jdbc.queryForObject("select id from sys_user where username = ?", Long.class, ADMIN);
        assertThat(id).as("种子管理员必须存在").isNotNull();
        return id;
    }

    private static String uniqueUsername(String prefix) {
        return prefix + "." + System.currentTimeMillis() + "." + Math.floorMod(System.nanoTime(), 1000);
    }

    /**
     * 造一个"随便怎么折腾都不心疼"的管理员建号（{@code PASSWORD} 模式，拿到初始密码），
     * 并登记到 {@link #createdUsers} 等用例结束统一删。
     */
    private long createDisposableUser(String prefix) {
        String username = uniqueUsername(prefix);
        Map<String, Object> body = new HashMap<>();
        body.put("name", "审计取证" + prefix);
        body.put("username", username);
        body.put("phone", "");
        body.put("email", "");
        body.put("deptId", anyRealDeptId());
        body.put("roleIds", List.of("USER"));
        body.put("inviteMode", "PASSWORD");
        RawResponse created = post("/api/users", body, token).assertOk("造夹具账号");
        trackUser(username);
        return ((Number) created.data("$.id")).longValue();
    }

    /** 删角色（先删关联再删主表，避开外键；用例中途失败也能兜底）。 */
    private void deleteRoleQuietly(long roleId) {
        jdbc.update("delete from sys_role_permission where role_id = ?", roleId);
        jdbc.update("delete from sys_role where id = ?", roleId);
    }

    /** 删部门（兜底清理）。 */
    private void deleteDeptQuietly(long deptId) {
        jdbc.update("delete from sys_department where id = ?", deptId);
    }

    // ==================================================================
    // 判据 1 / 2 —— 覆盖率本体
    // ==================================================================

    @Test
    @DisplayName("判据 1·2：@AuditLog 冻结清单 25 条 · 与策略表逐条相等 · 模块名全在前端下拉里")
    void annotationInventoryMatchesFrozenPlan() {
        Map<String, RiskLevel> actual = scanAnnotations();

        assertThat(actual)
                .as("总数变化必须是有意为之：新增审计点请同步改本用例与 AuditRiskPolicy")
                .hasSize(25);
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expectedActions());

        for (String key : NEW_IN_C2) {
            assertThat(actual)
                    .as("本批新增动作 [%s] 必须挂上 @AuditLog 且等级 = %s",
                            key, expectedActions().get(key))
                    .containsEntry(key, expectedActions().get(key));
        }

        assertThat(AuditRiskPolicy.implemented().keySet())
                .as("策略表 ↔ 注解双向相等：少登记是构建隐患，多登记是幽灵条目")
                .containsExactlyInAnyOrderElementsOf(actual.keySet());

        actual.keySet().forEach(key -> assertThat(FRONTEND_MODULES)
                .as("模块名必须在前端 MODULES 下拉里，否则日志「写得进、筛不出」：%s", key)
                .contains(key.split(" ", 2)[0]));
    }

    // ==================================================================
    // 判据 3~9 —— 11 个新动作逐个走真 HTTP
    // ==================================================================

    @Test
    @DisplayName("判据 3：新建用户 → 用户/HIGH，明细含账号·部门·角色·初始密码，且绝不记密码值（I-15）")
    void createUserIsAudited() {
        String username = uniqueUsername("g26.create");
        String realName = "审计取证账号";
        long base = maxAuditId();

        Map<String, Object> body = new HashMap<>();
        body.put("name", realName);
        body.put("username", username);
        body.put("phone", "");
        body.put("email", "");
        body.put("deptId", anyRealDeptId());
        body.put("roleIds", List.of("USER"));
        body.put("inviteMode", "PASSWORD");
        RawResponse created = post("/api/users", body, token).assertOk("新建用户");
        trackUser(username);
        String initialPassword = created.data("$.initialPassword");
        assertThat(initialPassword).isNotBlank();

        Map<String, Object> row = latestAudit("新建用户", base);
        assertThat(row.get("module")).isEqualTo(AuditModule.USER);
        assertThat(row.get("risk_level")).isEqualTo("HIGH");
        assertThat(row.get("operator")).isEqualTo(ADMIN);
        assertThat(row.get("result")).isEqualTo("SUCCESS");
        // target 是"姓名"（#request.name()），不是登录名 —— 审计页给人看的是这个人是谁
        assertThat(row.get("target")).isEqualTo(realName);

        String changes = (String) row.get("changes_json");
        assertThat(changes).isNotNull();
        assertThat(changes).contains("账号", "部门", "角色", "初始密码");
        assertThat(changes).contains(username);
        assertThat(changes)
                .as("新建的 before 侧全是 em dash（与 AuditChange 的既有口径一致）")
                .contains("—");
        assertThat(changes).contains("已设置，首登强制修改");
        assertThat(changes)
                .as("I-15：只记「已设置初始密码」这件事，明文密码绝不能进 changes_json")
                .doesNotContain(initialPassword);
        assertThat(((Number) row.get("affected_users")).intValue())
                .as("影响面 = 这次新建的这一个人")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("判据 4：启用/停用账号 → 用户/HIGH，状态用中文展示名记「正常 ↔ 已停用」（双向）")
    void patchStatusIsAudited() {
        long id = createDisposableUser("g26.status");

        long disabledBase = maxAuditId();
        call("PATCH", "/api/users/" + id + "/status", Map.of("status", "DISABLED"), token)
                .assertOk("停用账号");
        Map<String, Object> disabled = latestAudit("启用/停用账号", disabledBase);
        assertThat(disabled.get("module")).isEqualTo(AuditModule.USER);
        assertThat(disabled.get("risk_level")).isEqualTo("HIGH");
        assertThat(disabled.get("operator")).isEqualTo(ADMIN);
        assertThat(disabled.get("result")).isEqualTo("SUCCESS");
        assertThat(disabled.get("target")).isEqualTo("用户#" + id);
        String before = (String) disabled.get("changes_json");
        assertThat(before)
                .as("用 getLabel() 的中文展示名，不写 ACTIVE/DISABLED 技术代号")
                .contains("状态", "正常", "已停用");
        assertThat(((Number) disabled.get("affected_users")).intValue()).isEqualTo(1);

        // 反方向：两条日志的 before/after 恰好互换 —— 抄反了会在这里暴露
        long enabledBase = maxAuditId();
        call("PATCH", "/api/users/" + id + "/status", Map.of("status", "ACTIVE"), token)
                .assertOk("启用账号");
        String after = (String) latestAudit("启用/停用账号", enabledBase).get("changes_json");
        assertThat(after).contains("状态", "正常", "已停用");
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    @DisplayName("判据 5：管理员重置密码 → 用户/HIGH，明细记「登录密码 + 登录态全部撤销」，明文绝不进库（I-15）")
    void adminResetPasswordIsAudited() {
        long id = createDisposableUser("g26.reset");
        long base = maxAuditId();

        RawResponse res = post("/api/users/" + id + "/reset-password", null, token)
                .assertOk("管理员重置密码");
        String newPassword = res.data("$.newPassword");
        assertThat(newPassword).isNotBlank();

        Map<String, Object> row = latestAudit("重置密码（管理员）", base);
        assertThat(row.get("module")).isEqualTo(AuditModule.USER);
        assertThat(row.get("risk_level"))
                .as("与「重置密码（忘记密码自助）」是同一件事的两条路径，档次必须一致")
                .isEqualTo("HIGH");
        assertThat(row.get("operator")).isEqualTo(ADMIN);
        assertThat(row.get("target")).isEqualTo("用户#" + id);

        String changes = (String) row.get("changes_json");
        assertThat(changes).contains("登录密码", "已设置", "已重置，首登强制修改");
        assertThat(changes)
                .as("登录态撤销条数是本次操作的影响面证据")
                .contains("登录态", "已全部撤销");
        assertThat(changes)
                .as("I-15：新密码只出现在给管理员的响应里，绝不进审计")
                .doesNotContain(newPassword);
        assertThat(((Number) row.get("affected_users")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("判据 6：批量导入用户 → 用户/HIGH，明细汇总「导入结果 + 新增账号」，影响面 = 成功行数")
    void importUsersIsAudited() {
        String suffix = String.valueOf(Math.floorMod(System.nanoTime(), 1_000_000));
        String accepted = "g26.imp." + suffix + "a";
        String rejected = "g26.imp." + suffix + "b";
        String deptName = jdbc.queryForObject(
                "select name from sys_department order by id limit 1", String.class);
        // 模板是「按列位」解析的（不校验表头文案）：用户名,姓名,手机号,邮箱,部门,角色
        String csv = "username,realName,phone,email,department,roles\n"
                + accepted + ",导入用户甲,,," + deptName + ",USER\n"
                + rejected + ",导入用户乙,,,不存在的部门-" + suffix + ",USER\n";

        long base = maxAuditId();
        RawResponse res = postMultipart("/api/users/import",
                csv.getBytes(StandardCharsets.UTF_8), "users.csv", "text/csv", token)
                .assertOk("批量导入用户");
        trackUser(accepted);
        assertThat(((Number) res.data("$.success")).intValue()).isEqualTo(1);
        assertThat(((Number) res.data("$.failed")).intValue()).isEqualTo(1);

        Map<String, Object> row = latestAudit("批量导入用户", base);
        assertThat(row.get("module")).isEqualTo(AuditModule.USER);
        assertThat(row.get("risk_level"))
                .as("批量形态的「新建 + 赋角色」，权限扩大面比单个新建更大")
                .isEqualTo("HIGH");
        assertThat(row.get("result")).isEqualTo("SUCCESS");

        String changes = (String) row.get("changes_json");
        assertThat(changes).contains("导入结果", "成功 1 行 / 失败 1 行");
        assertThat(changes)
                .as("只汇总入库名单，不逐行登记 —— 失败行不该出现在新增账号里")
                .contains("新增账号", accepted)
                .doesNotContain(rejected);
        assertThat(((Number) row.get("affected_users")).intValue())
                .as("影响面 = 真正入库的行数，不是文件行数")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("判据 7·8：角色三连 + 启停 → 授权/NORMAL·HIGH，明细含编码·范围·权限点，启停带影响面")
    void roleCrudIsAudited() {
        String suffix = String.valueOf(Math.floorMod(System.nanoTime(), 1_000_000));
        String code = "G26_ROLE_" + suffix;
        String name = "审计取证角色" + suffix;
        List<String> permissions = jdbc.queryForList(
                "select code from sys_permission where code <> '*' order by code limit 2", String.class);
        assertThat(permissions).hasSize(2);

        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("code", code);
        body.put("description", "T-3C3 用例临时角色");
        body.put("dataScope", "ALL");
        body.put("enabled", true);
        body.put("permissions", permissions);

        long createBase = maxAuditId();
        RawResponse created = post("/api/roles", body, token).assertOk("新建角色");
        long roleId = ((Number) created.data("$.id")).longValue();
        try {
            Map<String, Object> createRow = latestAudit("新建角色", createBase);
            assertThat(createRow.get("module")).isEqualTo(AuditModule.GRANT);
            assertThat(createRow.get("risk_level"))
                    .as("新角色零成员、不改变任何人的现有权限 → NORMAL")
                    .isEqualTo("NORMAL");
            assertThat(createRow.get("target"))
                    .as("target = #request.name()：角色名，不是 id")
                    .isEqualTo(name);
            assertThat((String) createRow.get("changes_json"))
                    .contains("角色编码", "数据范围", "权限点", code, "2 项");

            // 编辑：permissions 是全量替换，一次「编辑」能把角色权限整个换掉 → HIGH
            String renamed = name + "改";
            long updateBase = maxAuditId();
            body.put("name", renamed);
            put("/api/roles/" + roleId, body, token).assertOk("编辑角色");
            Map<String, Object> updateRow = latestAudit("编辑角色", updateBase);
            assertThat(updateRow.get("risk_level")).isEqualTo("HIGH");
            assertThat(updateRow.get("target")).isEqualTo("角色#" + roleId);
            assertThat((String) updateRow.get("changes_json"))
                    .contains("角色名称", "数据范围", "权限点", "状态", name);
            assertThat(updateRow.get("affected_role"))
                    .as("受影响角色名登记在 applyRequest 之前 → 编辑这条记的是改动前的名字")
                    .isEqualTo(name);

            // 启停：一次点击改变该角色全部成员的权限
            long statusBase = maxAuditId();
            call("PATCH", "/api/roles/" + roleId + "/status", Map.of("enabled", false), token)
                    .assertOk("停用角色");
            Map<String, Object> statusRow = latestAudit("启用/停用角色", statusBase);
            assertThat(statusRow.get("risk_level")).isEqualTo("HIGH");
            assertThat((String) statusRow.get("changes_json")).contains("状态", "启用", "停用");
            assertThat(statusRow.get("affected_role"))
                    .as("affected_role 记的是「登记当刻」的名字 —— 此时已是改后的名字")
                    .isEqualTo(renamed);

            // 删除：走到这一行时该角色必定 0 成员（有成员会被拦） → NORMAL
            long deleteBase = maxAuditId();
            call("DELETE", "/api/roles/" + roleId, null, token).assertOk("删除角色");
            Map<String, Object> deleteRow = latestAudit("删除角色", deleteBase);
            assertThat(deleteRow.get("risk_level")).isEqualTo("NORMAL");
            assertThat(deleteRow.get("target")).isEqualTo("角色#" + roleId);
            assertThat((String) deleteRow.get("changes_json"))
                    .as("删完就查不到了，这条明细是唯一的追溯线索")
                    .contains("角色", "权限点", renamed, code);
        } finally {
            deleteRoleQuietly(roleId);
        }
    }

    @Test
    @DisplayName("判据 9：部门三连 → 系统/NORMAL，明细记「部门名称 + 上级部门」（改部门=改一群人的数据范围）")
    void deptCrudIsAudited() {
        String suffix = String.valueOf(Math.floorMod(System.nanoTime(), 1_000_000));
        String name = "审计取证部" + suffix;

        long createBase = maxAuditId();
        RawResponse created = post("/api/depts", Map.of("name", name), token).assertOk("新建部门");
        long deptId = ((Number) created.data("$.id")).longValue();
        try {
            Map<String, Object> createRow = latestAudit("新建部门", createBase);
            assertThat(createRow.get("module"))
                    .as("「系统」是本批新增用到的模块，前端下拉必须认它")
                    .isEqualTo(AuditModule.SYSTEM);
            assertThat(createRow.get("risk_level")).isEqualTo("NORMAL");
            assertThat(createRow.get("target")).isEqualTo(name);
            assertThat((String) createRow.get("changes_json"))
                    .as("顶级部门的上级是「—」，不能写成 null 或空串")
                    .contains("部门名称", "上级部门", name, "—");

            long updateBase = maxAuditId();
            put("/api/depts/" + deptId, Map.of("name", name + "改"), token).assertOk("编辑部门");
            Map<String, Object> updateRow = latestAudit("编辑部门", updateBase);
            assertThat(updateRow.get("risk_level")).isEqualTo("NORMAL");
            assertThat(updateRow.get("target")).isEqualTo("部门#" + deptId);
            assertThat((String) updateRow.get("changes_json"))
                    .contains("部门名称", "上级部门", name, name + "改");

            long deleteBase = maxAuditId();
            call("DELETE", "/api/depts/" + deptId, null, token).assertOk("删除部门");
            Map<String, Object> deleteRow = latestAudit("删除部门", deleteBase);
            assertThat(deleteRow.get("risk_level")).isEqualTo("NORMAL");
            assertThat(deleteRow.get("target")).isEqualTo("部门#" + deptId);
            assertThat((String) deleteRow.get("changes_json"))
                    .contains("部门名称", "上级部门", name + "改", "—");
        } finally {
            deleteDeptQuietly(deptId);
        }
    }

    @Test
    @DisplayName("判据 10：被拒的高危操作也留痕 —— 自停用被拦仍记 FAILED + 原因，且明细里有「想改成什么」")
    void deniedOperationStillLeavesTrace() {
        long adminId = adminUserId();

        long selfBase = maxAuditId();
        RawResponse denied = call("PATCH", "/api/users/" + adminId + "/status",
                Map.of("status", "DISABLED"), token);
        assertThat(denied.code())
                .as("当前登录账号不能被自己停用（自锁防护）")
                .isEqualTo(ErrorCode.USER_SELF_OPERATION_DENIED.getCode());

        Map<String, Object> row = latestAudit("启用/停用账号", selfBase);
        assertThat(row.get("module")).isEqualTo(AuditModule.USER);
        assertThat(row.get("risk_level")).isEqualTo("HIGH");
        assertThat(row.get("operator")).isEqualTo(ADMIN);
        assertThat(row.get("target")).isEqualTo("用户#" + adminId);
        assertThat(row.get("result"))
                .as("被拒也是「发生过」：不留痕就查不到「谁试过停用超管」")
                .isEqualTo("FAILED");
        assertThat((String) row.get("fail_reason")).isNotBlank();
        assertThat((String) row.get("changes_json"))
                .as("明细在拦截之前就登记好了 —— 失败行也能看出「想改成什么」")
                .contains("状态", "正常", "已停用");

        // 内置角色删不掉：这一条连 changes 都来不及登记，但"尝试"本身必须留痕
        long builtinBase = maxAuditId();
        RawResponse builtin = call("DELETE", "/api/roles/1", null, token);
        assertThat(builtin.code()).isNotEqualTo(CODE_OK);
        Map<String, Object> builtinRow = latestAudit("删除角色", builtinBase);
        assertThat(builtinRow.get("result")).isEqualTo("FAILED");
        assertThat(builtinRow.get("risk_level")).isEqualTo("NORMAL");
        assertThat((String) builtinRow.get("fail_reason")).isNotBlank();
    }

    @Test
    @DisplayName("判据 11·12：新动作能被审计页筛出来（模块 / 关键词 / 详情 changes 原样回吐）")
    @SuppressWarnings("unchecked")
    void newActionsAreQueryable() {
        String suffix = String.valueOf(Math.floorMod(System.nanoTime(), 1_000_000));
        String realName = "审计取证查询" + suffix;
        String username = uniqueUsername("g26.query");
        String deptName = "审计取证查询部" + suffix;

        Map<String, Object> userBody = new HashMap<>();
        userBody.put("name", realName);
        userBody.put("username", username);
        userBody.put("phone", "");
        userBody.put("email", "");
        userBody.put("deptId", anyRealDeptId());
        userBody.put("roleIds", List.of("USER"));
        userBody.put("inviteMode", "PASSWORD");
        post("/api/users", userBody, token).assertOk("造痕：新建用户");
        trackUser(username);

        RawResponse dept = post("/api/depts", Map.of("name", deptName), token).assertOk("造痕：新建部门");
        long deptId = ((Number) dept.data("$.id")).longValue();
        try {
            // ① 模块筛选（用户）
            List<Map<String, Object>> userRows = get(
                    "/api/audit/logs?module=" + AuditModule.USER + "&size=20", token)
                    .assertOk("按模块筛「用户」").data("$.list");
            assertThat(userRows).isNotEmpty();
            assertThat(userRows).allSatisfy(r ->
                    assertThat(r.get("module")).isEqualTo(AuditModule.USER));
            Map<String, Object> created = userRows.stream()
                    .filter(r -> "新建用户".equals(r.get("action")))
                    .filter(r -> realName.equals(r.get("target")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("用户类日志里找不到刚造的「新建用户」"));
            assertThat(created.get("riskLevel")).isEqualTo("HIGH");
            assertThat(created.get("result")).isEqualTo("SUCCESS");

            // ② 模块筛选（系统）—— 本批之前从没有日志用过这个模块
            List<Map<String, Object>> sysRows = get(
                    "/api/audit/logs?module=" + AuditModule.SYSTEM + "&size=20", token)
                    .assertOk("按模块筛「系统」").data("$.list");
            assertThat(sysRows).isNotEmpty();
            assertThat(sysRows).allSatisfy(r ->
                    assertThat(r.get("module")).isEqualTo(AuditModule.SYSTEM));
            assertThat(sysRows).anySatisfy(r ->
                    assertThat(r.get("action")).isEqualTo("新建部门"));

            // ③ 关键词（4 列 OR，含 target）—— 前端搜索框打的就是这里
            List<Map<String, Object>> kwRows = get("/api/audit/logs?keyword=" + realName, token)
                    .assertOk("按关键词筛").data("$.list");
            assertThat(kwRows).anySatisfy(r ->
                    assertThat(r.get("action")).isEqualTo("新建用户"));

            // ④ 详情：changes 原样回吐给前端「变更前 → 变更后」列
            Map<String, Object> detail = get("/api/audit/logs/" + created.get("id"), token)
                    .assertOk("审计详情").data("$");
            assertThat(detail.get("module")).isEqualTo(AuditModule.USER);
            assertThat(detail.get("action")).isEqualTo("新建用户");
            assertThat(detail.get("riskLevel")).isEqualTo("HIGH");
            assertThat(((Number) detail.get("affectedUsers")).intValue()).isEqualTo(1);
            List<Map<String, Object>> changes = (List<Map<String, Object>>) detail.get("changes");
            assertThat(changes).isNotEmpty();
            assertThat(changes).anySatisfy(c -> {
                assertThat(c.get("label")).isEqualTo("账号");
                assertThat(c.get("after")).isEqualTo(username);
            });
        } finally {
            deleteDeptQuietly(deptId);
        }
    }
}
