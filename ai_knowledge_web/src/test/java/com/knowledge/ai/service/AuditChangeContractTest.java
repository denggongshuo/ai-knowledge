package com.knowledge.ai.service;

import com.knowledge.ai.core.audit.AuditChange;
import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditRiskPolicy;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-300 / T-301 契约取证。
 *
 * <h3>为什么需要一个"扫全项目"的用例</h3>
 * <p>审计的风险等级散落在每个 {@code @AuditLog} 注解上，而 {@link AuditRiskPolicy}
 * 是它们的**唯一口径来源**。两边靠人肉对齐必然漂移 —— 本次实现过程中就出现过
 * 「改完 Service 忘了同步策略表」的瞬间。所以把口径做成可执行断言：
 * <b>注解改了但策略表没改 → 构建失败</b>，而不是等到某天发现审计页的高危筛选漏了东西。
 *
 * <h3>两个方向都要断言</h3>
 * <ul>
 *   <li><b>正向</b>（注解 → 策略）：每处 {@code @AuditLog} 必须在策略表里登记，且等级一致；</li>
 *   <li><b>反向</b>（策略 → 注解）：策略表里标为「已实现」的项，代码里必须有对应注解 ——
 *       否则策略表会积累已删除动作的"幽灵条目"。</li>
 * </ul>
 * ⚠️ 反向断言**排除** {@link AuditRiskPolicy#planned()}：那是"已裁定口径但功能还没做"的
 * S6/S7 动作（如「单文件下载不标高危」）。它们的存在是为了**在实现前冻结口径**，
 * 不是"忘了写注解"。
 *
 * <h3>为什么 changes 的取证走真 HTTP</h3>
 * <p>与 {@code S2AuditFlowTest} 同理由：{@code operator} / {@code apiPath} 取自请求上下文，
 * 绕开过滤器直调 Service 会把它们测成空值 —— 那等于没测到采集链路。
 */
class AuditChangeContractTest extends ApiTestSupport {

    /** 矩阵取证用的角色（V2 种子里非超管、可写）。 */
    private static final long TEST_ROLE_ID = 5L;
    private static final String TEST_ITEM_KEY = "sys:audit";

    @Autowired
    private ApplicationContext applicationContext;

    private String token;

    @BeforeEach
    void loginAdmin() {
        token = loginAsAdmin().data("$.accessToken");
    }

    // ==================================================================
    // T-301 · 策略表与注解逐条一致
    // ==================================================================

    @Test
    @DisplayName("T-301：每处 @AuditLog 都在 AuditRiskPolicy 登记，且高危等级一致")
    void everyAuditAnnotationIsRegisteredInPolicy() {
        Map<String, RiskLevel> fromAnnotations = scanAnnotations();
        assertThat(fromAnnotations)
                .as("项目里应当有若干 @AuditLog（全为零说明扫描逻辑失效，不是通过）")
                .isNotEmpty();

        List<String> missing = new ArrayList<>();
        List<String> mismatched = new ArrayList<>();
        for (Map.Entry<String, RiskLevel> entry : fromAnnotations.entrySet()) {
            String[] parts = entry.getKey().split(" ", 2);
            AuditRiskPolicy.Rule rule = AuditRiskPolicy
                    .findImplemented(parts[0], parts.length > 1 ? parts[1] : "")
                    .orElse(null);
            if (rule == null) {
                missing.add(entry.getKey() + "（注解 " + entry.getValue() + "）");
            } else if (rule.risk() != entry.getValue()) {
                mismatched.add(entry.getKey() + "：注解=" + entry.getValue() + " 策略表=" + rule.risk());
            }
        }

        assertThat(missing)
                .as("""
                        以下 @AuditLog 未在 AuditRiskPolicy 登记 —— 新加审计点时必须同步登记，
                        否则审计页的高危筛选会漏掉它（《05》§4.3「界限必须写死」）""")
                .isEmpty();
        assertThat(mismatched)
                .as("注解的风险等级与策略表不一致，两者必须逐字相同")
                .isEmpty();
    }

    @Test
    @DisplayName("T-301：策略表标为「已实现」的项，代码里必须有对应注解（反向）")
    void everyImplementedPolicyRuleHasAnnotation() {
        Map<String, RiskLevel> fromAnnotations = scanAnnotations();
        List<String> ghosts = new ArrayList<>();
        for (Map.Entry<String, AuditRiskPolicy.Rule> entry : AuditRiskPolicy.implemented().entrySet()) {
            if (!fromAnnotations.containsKey(entry.getKey())) {
                ghosts.add(entry.getKey() + "（策略表=" + entry.getValue().risk() + "）");
            }
        }
        assertThat(ghosts)
                .as("策略表里登记为「已实现」但代码中找不到注解 —— 多半是动作被删了而策略表没跟着删")
                .isEmpty();
    }

    @Test
    @DisplayName("T-301 / G3-8：单文件下载**不**标高危，批量下载标高危")
    void singleFileDownloadIsNotHighRisk() {
        // §4.3「明确不计入高危」：单文件浏览式下载是日常动作，
        // 标成高危会让告警失去意义 —— 这正是 G3-8 要防的事。
        AuditRiskPolicy.Rule single = AuditRiskPolicy.findPlanned("文档", "下载文档").orElseThrow();
        assertThat(single.risk()).isEqualTo(RiskLevel.NORMAL);
        assertThat(single.category()).isEqualTo(AuditRiskPolicy.Category.NOT_HIGH_RISK);

        // 分界就在"批量"：批量形态属 §4.3② 数据导出
        AuditRiskPolicy.Rule batch = AuditRiskPolicy.findPlanned("文档", "批量下载").orElseThrow();
        assertThat(batch.risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(batch.category()).isEqualTo(AuditRiskPolicy.Category.DATA_EXPORT);

        // 「删除知识库」是 G3-8 的另一半：它必须标高危
        AuditRiskPolicy.Rule kbDelete = AuditRiskPolicy.findPlanned("知识库", "删除知识库").orElseThrow();
        assertThat(kbDelete.risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(kbDelete.category()).isEqualTo(AuditRiskPolicy.Category.CONTENT_DELETE);
    }

    @Test
    @DisplayName("T-301：第 5 类扩展「账号安全变更」保留 HIGH（2026-09-14 定案）")
    void accountSecurityChangesStayHighRisk() {
        for (String[] pair : List.of(
                new String[]{"认证", "修改密码"},
                new String[]{"认证", "重置密码（忘记密码自助）"},
                new String[]{"用户", "换绑手机号"},
                new String[]{"用户", "换绑邮箱"})) {
            AuditRiskPolicy.Rule rule = AuditRiskPolicy.findImplemented(pair[0], pair[1]).orElseThrow();
            assertThat(rule.risk())
                    .as("%s/%s 属账号接管类动作，§4.3 四类未覆盖但必须保留 HIGH", pair[0], pair[1])
                    .isEqualTo(RiskLevel.HIGH);
            assertThat(rule.category()).isEqualTo(AuditRiskPolicy.Category.ACCOUNT_SECURITY);
        }
    }

    // ==================================================================
    // T-300 · changes_json 真 HTTP 取证
    // ==================================================================

    @Test
    @DisplayName("T-300：矩阵保存落 changes（真旧值）+ 影响用户数")
    void matrixSavePersistsChanges() {
        long base = maxAuditId();

        // 先读改前档位（作为 before 的期望值），再改，改完比对
        String levelBefore = currentMatrixLevel();
        String levelAfter = "NONE".equals(levelBefore) ? "READ" : "NONE";

        put("/api/permissions/matrix",
                Map.of("changes", List.of(Map.of("roleId", TEST_ROLE_ID,
                        "permKey", TEST_ITEM_KEY, "level", levelAfter))),
                token).assertOk("矩阵保存");

        Map<String, Object> row = latestAudit("保存功能矩阵", base);
        String json = (String) row.get("changes_json");

        assertThat(json).as("矩阵保存必须落 changes_json").isNotNull();
        assertThat(json).contains("\"label\"").contains("\"before\"").contains("\"after\"");
        assertThat(json)
                .as("before 必须是**改前**的档位，不是提交值 —— 顺序写反时会在这里暴露")
                .contains("\"before\":\"" + levelBefore + "\"");
        assertThat(json).contains("\"after\":\"" + levelAfter + "\"");

        Integer affected = (Integer) row.get("affected_users");
        assertThat(affected)
                .as("影响用户数应落库，且等于该角色的真实成员数（去重、排除已删除账号）")
                .isEqualTo(expectedMembersOf(TEST_ROLE_ID));

        // 还原到 seed
        put("/api/permissions/matrix",
                Map.of("changes", List.of(Map.of("roleId", TEST_ROLE_ID,
                        "permKey", TEST_ITEM_KEY, "level", levelBefore))),
                token).assertOk("矩阵还原");
    }

    @Test
    @DisplayName("T-300：空值 / 空串一律规范化成 EM DASH（U+2014），与前端字面量一致")
    void blankValuesBecomeEmDash() {
        long base = maxAuditId();

        // 用临时库取证：新建一个没有任何授权的库 → before 侧两行都是"无"
        long kbId = 999_902L;
        jdbc.update("""
                insert into sys_kb (id, code, name, dept_id, doc_count, chunk_count, status, created_at, updated_at)
                values (?, 'kb_e2e_change', '变更对照临时库', 1, 0, 0, 'READY', now(), now())
                """, kbId);
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("roleIds", List.of(3L));
            body.put("level", "READ");
            body.put("extraUserIds", List.of());

            put("/api/kb/" + kbId + "/grants", body, token).assertOk("给空库配授权");

            Map<String, Object> row = latestAudit("调整知识库授权", base);
            String json = (String) row.get("changes_json");
            assertThat(json).isNotNull();
            assertThat(json)
                    .as("空授权库的 before 侧应规范化为 EM DASH（U+2014），"
                            + "码点不同会让前端的档位标签判断走错分支")
                    .contains("—");
            assertThat(json).contains("授权级别").contains("可见角色");
        } finally {
            jdbc.update("delete from sys_kb_grant where kb_id = ?", kbId);
            jdbc.update("delete from sys_kb where id = ?", kbId);
        }
    }

    @Test
    @DisplayName("T-300：未登记变更的动作落 NULL（不是 []）")
    void actionsWithoutChangesPersistNull() {
        long base = maxAuditId();

        // 修改个人资料没有任何 AuditContext 登记 —— 它的 changes_json 必须是 NULL。
        // 写 [] 会让"未采集"与"采集了但无变更"在数据上无法区分。
        Map<String, Object> body = new HashMap<>();
        body.put("realName", "审计契约探针");
        put("/api/user/profile", body, token).assertOk("改个人资料");

        Map<String, Object> row = latestAudit("修改个人资料", base);
        assertThat(row.get("changes_json"))
                .as("没有登记过变更明细的动作，changes_json 必须是 NULL")
                .isNull();

        // 还原（改回种子管理员姓名）
        Map<String, Object> restore = new HashMap<>();
        restore.put("realName", "系统管理员");
        put("/api/user/profile", restore, token).assertOk("还原姓名");
    }

    @Test
    @DisplayName("T-300：登记上下文不跨线程 —— 上一次请求的 changes 不会漏进下一次")
    void auditContextDoesNotLeakAcrossRequests() {
        // ① 带 changes 的请求（矩阵保存）
        long base1 = maxAuditId();
        String before = currentMatrixLevel();
        String after = "NONE".equals(before) ? "READ" : "NONE";
        put("/api/permissions/matrix",
                Map.of("changes", List.of(Map.of("roleId", TEST_ROLE_ID,
                        "permKey", TEST_ITEM_KEY, "level", after))),
                token).assertOk("矩阵保存（带 changes）");
        assertThat(((String) latestAudit("保存功能矩阵", base1).get("changes_json"))).isNotNull();

        // ② 紧接着一个不带 changes 的请求（改资料）
        long base2 = maxAuditId();
        Map<String, Object> body = new HashMap<>();
        body.put("realName", "泄露探针");
        put("/api/user/profile", body, token).assertOk("改个人资料（不带 changes）");
        assertThat(latestAudit("修改个人资料", base2).get("changes_json"))
                .as("上一条的 changes 残留到了本条 —— ThreadLocal 未被清理，是 AuditContext 最坏的 bug")
                .isNull();

        // 还原
        Map<String, Object> restore1 = new HashMap<>();
        restore1.put("realName", "系统管理员");
        put("/api/user/profile", restore1, token).assertOk("还原姓名");
        put("/api/permissions/matrix",
                Map.of("changes", List.of(Map.of("roleId", TEST_ROLE_ID,
                        "permKey", TEST_ITEM_KEY, "level", before))),
                token).assertOk("矩阵还原");
    }

    // ==================================================================
    // 私有
    // ==================================================================

    /**
     * 反射扫全项目：{@code "module action" → RiskLevel}。
     *
     * <p>用 {@link AopUtils#getTargetClass} 取目标类 —— Service 被事务与审计切面
     * 双重代理（CGLIB 子类），直接在代理类上找注解会**找不到方法**（注解在目标类上）。
     * 这个坑与本仓 {@code AuditChainVerificationTest} 记录的"别透过代理读字段"同源。
     */
    private Map<String, RiskLevel> scanAnnotations() {
        Map<String, RiskLevel> found = new LinkedHashMap<>();
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> type;
            try {
                type = AopUtils.getTargetClass(applicationContext.getBean(beanName));
            } catch (Exception ex) {
                continue; // 工厂 Bean / 无法解析的 bean 直接跳过
            }
            if (type.getName().startsWith("com.knowledge.ai.")
                    && !type.getName().startsWith("com.knowledge.ai.core.audit.")) {
                for (Method method : type.getDeclaredMethods()) {
                    AuditLog annotation = method.getAnnotation(AuditLog.class);
                    if (annotation != null) {
                        found.put(annotation.module() + " " + annotation.action(), annotation.risk());
                    }
                }
            }
        }
        return found;
    }

    /** 当前审计表最大 id，作为"本次操作产生的新行"下界。 */
    private long maxAuditId() {
        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from sys_audit_log", Long.class);
        return max == null ? 0L : max;
    }

    /** 取 id 大于下界、指定 action 的最新一行；没有则让用例失败。 */
    private Map<String, Object> latestAudit(String action, long afterId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select module, action, risk_level, operator, target, result, api_path,
                       changes_json, affected_users
                from sys_audit_log where action = ? and id > ? order by id desc limit 1
                """, action, afterId);
        assertThat(rows)
                .as("action=%s 且 id>%s 应有一条审计记录（@AuditLog 未生效？）", action, afterId)
                .isNotEmpty();
        return rows.getFirst();
    }

    /** 读矩阵里 {@code sys:audit} 那一格的当前档位；无行视为 NONE（与读侧同口径）。 */
    private String currentMatrixLevel() {
        List<String> rows = jdbc.queryForList(
                "select level from sys_role_matrix where role_id = ? and item_key = ?",
                String.class, TEST_ROLE_ID, TEST_ITEM_KEY);
        return rows.isEmpty() ? "NONE" : rows.getFirst();
    }

    /** 该角色当前的真实成员数（去重、排除已软删账号）—— 与服务端 {@code memberCountDistinct} 同口径。 */
    private int expectedMembersOf(long roleId) {
        Integer n = jdbc.queryForObject("""
                select count(distinct ur.user_id) from sys_user_role ur
                join sys_user u on u.id = ur.user_id and u.deleted_at is null
                where ur.role_id = ?
                """, Integer.class, roleId);
        return n == null ? 0 : n;
    }

    /** 断言 U+2014 这个码点本身 —— 肉眼分不出 EM DASH / HORIZONTAL BAR，只能靠断言。 */
    @Test
    @DisplayName("T-300：AuditChange.EMPTY 就是 U+2014（前端 AuditLogView.vue:316 消费的字面量）")
    void emptyPlaceholderIsEmDash() {
        assertThat(AuditChange.EMPTY).hasSize(1);
        assertThat((int) AuditChange.EMPTY.charAt(0)).isEqualTo(0x2014);
        assertThat(AuditChange.EMPTY).isEqualTo("—");
        // 反向对照：常见的"看起来一样"的码点必须**不等于**它
        assertThat(AuditChange.EMPTY).isNotEqualTo("―"); // HORIZONTAL BAR
        assertThat(AuditChange.EMPTY).isNotEqualTo("─"); // BOX DRAWINGS LIGHT HORIZONTAL
        assertThat(AuditChange.EMPTY).isNotEqualTo("-");
    }
}
