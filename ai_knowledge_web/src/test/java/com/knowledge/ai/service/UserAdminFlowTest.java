package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.DataScope;
import com.knowledge.ai.core.enums.PermissionCode;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.dto.admin.AdminDtos;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * S2 读侧与自锁防护的端到端验证（T-200 / T-202 / T-204 / T-205 / G2-5）。
 *
 * <p>沿用 {@link ApiTestSupport}：起真服务、发真 HTTP、真库真 Redis。
 * 种子管理员持 SUPER_ADMIN → 数据范围 ALL。
 *
 * <p><b>种子状态依赖</b>（V1/V2/V5）：部门 9 行（顶级 6：未分配 0 + 五个中心），
 * 角色 5 行（id 1~5 按 seed 顺序），权限 38 行，矩阵 5×12=60 行。
 * 用例尽量只读；唯一改状态的用例（矩阵保存）在末尾把值改回 seed。
 */
class UserAdminFlowTest extends ApiTestSupport {

    @Autowired
    private UserAdminService userAdminService;

    private String token;

    @BeforeEach
    void loginAdmin() {
        token = loginAsAdmin().data("$.accessToken");
    }

    @AfterEach
    void clearContext() {
        LoginUserContext.clear();
    }

    @Test
    @DisplayName("G2-1 部门树：人数与《01-示例数据字典》逐项一致（V6 预置 127 演示用户后）")
    void deptTree() {
        var resp = get("/api/depts/tree", token).assertOk("部门树");
        List<Map<String, Object>> tree = resp.data("$");
        assertThat(tree).hasSize(6);
        Map<String, Object> rd = byName(tree, "研发中心");
        // 52 = 平台架构 18 + 应用开发 24 + 测试 10（《01》§1.1）
        assertThat(((Number) rd.get("userCount")).longValue()).isEqualTo(52L);
        List<Map<String, Object>> children = rd.get("children") == null
                ? List.of() : (List<Map<String, Object>>) rd.get("children");
        assertThat(children).hasSize(3);
        assertThat(((Number) byName(children, "平台架构组").get("userCount")).longValue()).isEqualTo(18L);
        assertThat(((Number) byName(children, "应用开发组").get("userCount")).longValue()).isEqualTo(24L);
        assertThat(((Number) byName(children, "测试组").get("userCount")).longValue()).isEqualTo(10L);
        assertThat(((Number) byName(tree, "销售中心").get("userCount")).longValue()).isEqualTo(34L);
        assertThat(((Number) byName(tree, "人力资源部").get("userCount")).longValue()).isEqualTo(12L);
        assertThat(((Number) byName(tree, "运维部").get("userCount")).longValue()).isEqualTo(16L);
        assertThat(((Number) byName(tree, "培训中心").get("userCount")).longValue()).isEqualTo(8L);
        assertThat(((Number) byName(tree, "未分配").get("userCount")).longValue()).isEqualTo(6L);
    }

    @Test
    @DisplayName("G2-2 权限树：total=38、4 大分组、系统管理 17 叶、叶子 id 即权限码")
    void permissionTree() {
        var resp = get("/api/permissions/tree", token).assertOk("权限树");
        assertThat(((Number) resp.data("$.total")).longValue()).isEqualTo(38L);
        List<Map<String, Object>> tree = resp.data("$.tree");
        assertThat(tree).hasSize(4);
        // 后端比 mock 树多一个 doc:recycle（回收站管理，V1 刻意单列的不可逆动作权限）：
        // 文档 8 叶（mock 树漏了它）、系统管理 16 叶。前端多渲染一个可勾选项，无害超集。
        // ⚠️ 权限树节点的字段是 label（部门树才是 name），别复用 byName
        Map<String, Object> doc = byLabel(tree, "文档");
        assertThat((List<?>) doc.get("children")).hasSize(8);
        Map<String, Object> system = byLabel(tree, "系统管理");
        assertThat((List<?>) system.get("children")).hasSize(16);
        // 叶子 id == code（前端勾选树按 id→code 映射，两者相等才不会错位）
        Map<String, Object> first = ((List<Map<String, Object>>) doc.get("children")).getFirst();
        assertThat(first.get("id")).isEqualTo(first.get("code"));
    }

    @Test
    @DisplayName("T-203 角色列表：5 角色 + dataScopeLabel/permissionCount 派生字段在用")
    void roleList() {
        var resp = get("/api/roles", token).assertOk("角色列表");
        List<Map<String, Object>> list = resp.data("$.list");
        assertThat(list).hasSize(5);
        Map<String, Object> superAdmin = list.stream()
                .filter(r -> "SUPER_ADMIN".equals(r.get("code"))).findFirst().orElseThrow();
        // 派生字段是页面在渲染的（S1 同型坑：缺了列空白）
        assertThat(superAdmin.get("dataScopeLabel")).isEqualTo("全部数据");
        assertThat(((Number) superAdmin.get("permissionCount")).longValue()).isEqualTo(38L);
        // V6 预置后：admin + lin.gong + emp001 = 3（《01》§2.1）
        assertThat(((Number) superAdmin.get("memberCount")).longValue()).isEqualTo(3L);
        assertThat((Boolean) superAdmin.get("builtin")).isTrue();
    }

    @Test
    @DisplayName("T-204 功能矩阵：12 项、roles 严格 id 升序、levels[key][roleId-1] 契约成立")
    void matrix() {
        var resp = get("/api/permissions/matrix", token).assertOk("功能矩阵");
        List<Map<String, Object>> roles = resp.data("$.roles");
        List<Map<String, Object>> items = resp.data("$.items");
        Map<String, List<String>> levels = resp.data("$.levels");

        assertThat(items).hasSize(12);
        // 前端按 levels[key][roleId - 1] 取档位 —— roles 顺序错位 = 整列错档
        for (int i = 0; i < roles.size(); i++) {
            assertThat(((Number) roles.get(i).get("id")).longValue()).isEqualTo(i + 1L);
        }
        // V5 seed：qa:ask = MANAGE/MANAGE/WRITE/WRITE/READ（角色 id 1~5）
        assertThat(levels.get("qa:ask")).containsExactly("MANAGE", "MANAGE", "WRITE", "WRITE", "READ");
        assertThat(levels.get("sys:audit").get(3)).isEqualTo("WRITE");
    }

    @Test
    @DisplayName("G2-3 矩阵保存：超管列拒绝修改；正常改动生效（用后还原）")
    void matrixSave() {
        // 超管列锁定（G2-4）：roleId=1 恒拒绝
        var denied = put("/api/permissions/matrix",
                Map.of("changes", List.of(Map.of("roleId", 1, "permKey", "sys:role", "level", "READ"))),
                token);
        assertThat(denied.code()).isEqualTo(ErrorCode.ROLE_BUILTIN_IMMUTABLE.getCode());

        // 正常改动：GUEST(5) 的 sys:audit 从 NONE → READ，断言生效后**改回**保持 seed
        var saved = put("/api/permissions/matrix",
                Map.of("changes", List.of(Map.of("roleId", 5L, "permKey", "sys:audit", "level", "READ"))),
                token).assertOk("矩阵保存");
        assertThat(((Number) saved.data("$.applied")).intValue()).isEqualTo(1);

        var after = get("/api/permissions/matrix", token).assertOk("复核");
        assertThat(((Map<String, List<String>>) after.data("$.levels")).get("sys:audit").get(4))
                .isEqualTo("READ");

        // 还原
        put("/api/permissions/matrix",
                Map.of("changes", List.of(Map.of("roleId", 5L, "permKey", "sys:audit", "level", "NONE"))),
                token).assertOk("矩阵还原");
    }

    @Test
    @DisplayName("T-205 用户列表：分页/角色 code 筛选/部门全路径/roleNames 全部就位")
    void userList() {
        var resp = get("/api/users?page=1&size=10", token).assertOk("用户列表");
        List<Map<String, Object>> list = resp.data("$.list");
        assertThat(list).isNotEmpty();
        Map<String, Object> admin = list.get(0);
        assertThat(admin.get("username")).isEqualTo("admin");
        // 种子 admin 的 dept_id=0 → 全路径就是「未分配」
        assertThat(admin.get("department")).isEqualTo("未分配");
        assertThat(admin.get("roleNames")).isEqualTo(List.of("超级管理员"));

        // ⚠️ 前端发的是角色 **code**（不是数字 id）；超管 3 人 = admin + 林工 + emp001
        var filtered = get("/api/users?roleId=SUPER_ADMIN&page=1&size=10", token).assertOk("按角色筛选");
        assertThat(((Number) filtered.data("$.total")).longValue()).isEqualTo(3L);
    }

    @Test
    @DisplayName("G2-5 自锁防护：停用自己被拒（前端只拦了 UI，这里是唯一防线）")
    void disableSelfDenied() {
        var resp = call("PATCH", "/api/users/1/status", Map.of("status", "DISABLED"), token);
        assertThat(resp.code()).isEqualTo(ErrorCode.USER_SELF_OPERATION_DENIED.getCode());
        assertThat(resp.message()).contains("不能停用当前登录账号");
        // 状态没被改
        var list = get("/api/users?page=1&size=10", token).assertOk("复核状态");
        Map<String, Object> admin = ((List<Map<String, Object>>) list.data("$.list")).get(0);
        assertThat(admin.get("status")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("T-212 DataScope：SELF 范围下列表只看到自己（条件已拼进 SQL）")
    void selfScopeOnlySeesSelf() {
        LoginUser self = LoginUser.builder()
                .userId(1L).username("admin").realName("admin").deptId(1L)
                .superAdmin(false)
                .dataScope(DataScope.SELF)
                .permissions(Set.of(PermissionCode.WILDCARD))
                .build();
        LoginUserContext.set(self);
        try {
            AdminDtos.UserPage page = userAdminService.page(null, null, null, null, 1, 20);
            assertThat(page.total()).isEqualTo(1L);
            assertThat(page.list()).allSatisfy(row -> assertThat(row.id()).isEqualTo(1L));
        } finally {
            LoginUserContext.clear();
        }
    }

    @Test
    @DisplayName("G2-4 内置角色保护：删除/停用超管角色被拒；有成员的自定义角色禁删")
    void builtinRoleProtected() {
        // 内置角色不可删（SUPER_ADMIN id=1）
        var delBuiltin = call("DELETE", "/api/roles/1", null, token);
        assertThat(delBuiltin.code()).isEqualTo(ErrorCode.ROLE_BUILTIN_IMMUTABLE.getCode());
        // 停用超管角色 = 系统自锁的最后一道防线，直接拒
        var disableSuper = call("PATCH", "/api/roles/1/status", Map.of("enabled", false), token);
        assertThat(disableSuper.code()).isEqualTo(ErrorCode.ROLE_BUILTIN_IMMUTABLE.getCode());
        // AUDITOR(id=4, builtin=0) 下有 4 名成员 → 只能先转移
        var delInUse = call("DELETE", "/api/roles/4", null, token);
        assertThat(delInUse.code()).isEqualTo(ErrorCode.ROLE_IN_USE.getCode());
        assertThat(delInUse.message()).contains("请先转移成员");
    }

    @Test
    @DisplayName("T-211 知识库授权列表：GET /kb/grants（page/pageSize），行字段齐全、updatedAt 为时间串")
    void kbGrantList() {
        var resp = get("/api/kb/grants?page=1&pageSize=8", token).assertOk("授权列表");
        List<Map<String, Object>> list = resp.data("$.list");
        assertThat(list).isNotEmpty();
        assertThat(((Number) resp.data("$.total")).longValue()).isEqualTo(8L);
        // 回归：updatedAt 曾经被强转 java.sql.Timestamp → ClassCastException（Hibernate 7 返回 LocalDateTime）→ 9999
        assertThat(list.getFirst()).containsKeys("kbId", "name", "department", "docCount", "updatedAt", "roles", "level");
        assertThat(list.getFirst().get("updatedAt")).asString().contains("-");
    }

    @Test
    @DisplayName("G2-5 自锁防护：停用最后一个超级管理员被拒（另两个超管临时禁用，用例后复原）")
    void disableLastSuperAdminDenied() {
        Long adminId = jdbc.queryForObject("select id from sys_user where username = 'admin'", Long.class);
        Long actorId = jdbc.queryForObject("select id from sys_user where username = 'emp001'", Long.class);
        try {
            // 制造「admin 是最后一个启用超管」的局面：临时禁用 lin.gong / emp001
            jdbc.update("update sys_user set status = 'DISABLED' where username in ('lin.gong', 'emp001')");
            // 操作人用 emp001（非 admin），避开"停用自己"分支，专测"最后一个超管"分支
            LoginUser actor = LoginUser.builder()
                    .userId(actorId).username("emp001").realName("emp001").deptId(1L)
                    .superAdmin(true).dataScope(DataScope.ALL)
                    .permissions(Set.of(PermissionCode.WILDCARD)).build();
            LoginUserContext.set(actor);
            BizException ex = assertThrows(BizException.class,
                    () -> userAdminService.patchStatus(adminId, "DISABLED"));
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.LAST_SUPER_ADMIN);
        } finally {
            jdbc.update("update sys_user set status = 'ACTIVE' where username in ('lin.gong', 'emp001')");
            LoginUserContext.clear();
        }
    }

    private Map<String, Object> byName(List<Map<String, Object>> nodes, String name) {
        return nodes.stream().filter(n -> name.equals(n.get("name"))).findFirst().orElseThrow();
    }

    private Map<String, Object> byLabel(List<Map<String, Object>> nodes, String label) {
        return nodes.stream().filter(n -> label.equals(n.get("label"))).findFirst().orElseThrow();
    }
}
