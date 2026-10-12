package com.knowledge.ai.service;

import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批次 5 审计打点取证（T-204 / T-206 / T-211 的 🚩 高危动作）。
 *
 * <p>《05》§4.2 的采集范围把「角色权限矩阵变更 / 用户角色调整 / 知识库授权变更」
 * 都列为必记；§4.3 的四类高危里它们同属「权限扩大」。本类逐条钉住：
 * 三处入口各落一条 {@code sys_audit_log}，且 {@code risk_level='HIGH'}、
 * 操作人是真实登录者、目标含对象 id、结果为 SUCCESS。
 *
 * <p><b>为什么走真 HTTP 而不是直接调 Service</b>：审计的 {@code operator} /
 * {@code apiPath} / IP 全部取自请求上下文（{@code LoginUserContext} + {@code WebUtils}），
 * 绕开过滤器直调 Service 会把这三项测成空值，等于没测到采集链路。
 *
 * <p><b>为什么改动做成幂等</b>：这三页的写操作都会真改库。用例把**原值原样回写**
 * （矩阵改回 seed、用户角色数组原样提交、知识库授权按当前详情全量回写），
 * 断言只关心「留没留痕」，跑完种子状态不变，不会让后续用例以数据漂移的方式假失败。
 */
class S2AuditFlowTest extends ApiTestSupport {

    private String token;

    @BeforeEach
    void loginAdmin() {
        token = loginAsAdmin().data("$.accessToken");
    }

    @Test
    @DisplayName("矩阵保存留痕：授权模块 / HIGH / 操作人 admin / 目标含变更项数")
    void matrixSaveIsAudited() {
        long base = maxAuditId();
        put("/api/permissions/matrix",
                Map.of("changes", List.of(Map.of("roleId", 5L, "permKey", "sys:audit", "level", "READ"))),
                token).assertOk("矩阵保存");

        Map<String, Object> row = latestAudit("保存功能矩阵", base);
        assertThat(row.get("module")).isEqualTo("授权");
        assertThat(row.get("risk_level")).isEqualTo("HIGH");
        assertThat(row.get("operator")).isEqualTo(ADMIN);
        assertThat(row.get("result")).isEqualTo("SUCCESS");
        assertThat((String) row.get("target")).contains("1 项");
        assertThat((String) row.get("api_path")).contains("/api/permissions/matrix");

        // 还原到 seed（还原动作本身也留痕，属预期，不影响断言）
        put("/api/permissions/matrix",
                Map.of("changes", List.of(Map.of("roleId", 5L, "permKey", "sys:audit", "level", "NONE"))),
                token).assertOk("矩阵还原");
    }

    @Test
    @DisplayName("用户角色调整留痕：用户模块 / HIGH / 目标为 用户#{id}")
    void userRoleAdjustIsAudited() {
        // emp119 是 V6 预置演示账号（USER 角色）；原值回写 → 状态不变，仅触发审计
        List<Map<String, Object>> list = get("/api/users?keyword=emp119&page=1&size=10", token)
                .assertOk("按关键字查用户").data("$.list");
        assertThat(list).isNotEmpty();
        Map<String, Object> user = list.getFirst();
        long id = ((Number) user.get("id")).longValue();

        Map<String, Object> body = new HashMap<>();
        body.put("realName", user.get("realName"));
        body.put("phone", user.get("phone"));
        body.put("email", user.get("email") == null ? "" : user.get("email"));
        body.put("deptId", user.get("deptId"));
        body.put("roleIds", user.get("roles"));

        long base = maxAuditId();
        put("/api/users/" + id, body, token).assertOk("编辑用户（角色原样回写）");

        Map<String, Object> row = latestAudit("编辑用户（含角色调整）", base);
        assertThat(row.get("module")).isEqualTo("用户");
        assertThat(row.get("risk_level")).isEqualTo("HIGH");
        assertThat(row.get("operator")).isEqualTo(ADMIN);
        assertThat(row.get("result")).isEqualTo("SUCCESS");
        assertThat(row.get("target")).isEqualTo("用户#" + id);
    }

    @Test
    @DisplayName("知识库授权变更留痕：授权模块 / HIGH / 目标为 知识库#{kbId}")
    void kbGrantSaveIsAudited() {
        // ⚠️ 不能用现有库“原值回写”当 no-op：该接口是**单值 level + 角色集合**模型，
        // 保存时把全部角色归一成同一档位，而种子库各角色档位并不相同
        // （SUPER=MANAGE、KB_ADMIN=UPLOAD、USER/AUDITOR=READ）——回写会静默把混合档位
        // 拉平（第一次实现就是这么把 kb1 的 USER 授权从 READ 改成了 MANAGE，
        // 连带 KbGrantFlowTest 的 selfScope/deptAndChild 变红）。故改用**临时库**取证。
        long kbId = 999_901L;
        jdbc.update("""
                insert into sys_kb (id, code, name, dept_id, status, created_at, updated_at)
                values (?, 'kb_e2e_audit', '审计取证临时库', 1, 'READY', now(), now())
                """, kbId);
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("roleIds", List.of(3L));
            body.put("level", "READ");
            body.put("extraUserIds", List.of());

            long base = maxAuditId();
            put("/api/kb/" + kbId + "/grants", body, token).assertOk("保存知识库授权");

            Map<String, Object> row = latestAudit("调整知识库授权", base);
            assertThat(row.get("module")).isEqualTo("授权");
            assertThat(row.get("risk_level")).isEqualTo("HIGH");
            assertThat(row.get("operator")).isEqualTo(ADMIN);
            assertThat(row.get("result")).isEqualTo("SUCCESS");
            assertThat(row.get("target")).isEqualTo("知识库#" + kbId);
        } finally {
            jdbc.update("delete from sys_kb_grant where kb_id = ?", kbId);
            jdbc.update("delete from sys_kb where id = ?", kbId);
        }
    }

    // ==================== 私有 ====================

    /** 当前审计表最大 id —— 作为"本次操作产生的新行"的下界。 */
    private long maxAuditId() {
        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from sys_audit_log", Long.class);
        return max == null ? 0L : max;
    }

    /** 取 id 大于下界、指定 action 的**最新**一条审计行；没有则让用例失败。 */
    private Map<String, Object> latestAudit(String action, long afterId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select module, action, risk_level, operator, target, result, api_path
                from sys_audit_log where action = ? and id > ? order by id desc limit 1
                """, action, afterId);
        assertThat(rows)
                .as("action=%s 且 id>%s 应有一条审计记录（@AuditLog 未生效？）", action, afterId)
                .isNotEmpty();
        return rows.getFirst();
    }
}
