package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.DataScope;
import com.knowledge.ai.core.enums.KbGrantLevel;
import com.knowledge.ai.core.enums.PermissionCode;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.dto.admin.AdminDtos;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-212 求交引擎单测：**5 种 DataScope × 知识库授权**（G2-7 / G2-8 的取证核心）。
 *
 * <p>求交语义（《05》§0.1）：可见 = ② 数据范围 ∩ ③ 知识库授权，**没有"或"分支**。
 * 每条用例对应一行《05》§0.4 的别名表或 §0.5 的叠加规则：
 * <ul>
 *   <li>授了权但部门在范围外 → 不可见（② 拦下，I-7 的"反向"用例）；</li>
 *   <li>部门在范围内但无授权 → 不可见（③ 拦下，I-7 的"正向"用例）；</li>
 *   <li>多角色/额外授权 → 级别取最高。</li>
 * </ul>
 *
 * <p>数据全部来自 V6/V7 种子（lin.gong 等），**不在用例里造用户** ——
 * 引擎只消费 {@link LoginUser}（userId/deptId/dataScope 是构造参数，与角色表解耦），
 * 所以同一个真实用户可以按任意 DataScope 构造用例。
 */
class KbGrantFlowTest extends ApiTestSupport {

    @Autowired
    private DataScopeService dataScopeService;

    @Autowired
    private KbGrantService kbGrantService;

    private LoginUser user(String username, DataScope scope, Long deptId) {
        Long id = jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
        return LoginUser.builder()
                .userId(id).username(username).realName(username).deptId(deptId)
                .superAdmin(false)
                .dataScope(scope)
                .permissions(Set.of(PermissionCode.WILDCARD))
                .build();
    }

    @Test
    @DisplayName("ALL：超管看到全部 8 库，级别 MANAGE（③ 的最高档）")
    void allScope() {
        LoginUser admin = user("admin", DataScope.ALL, 0L);
        assertThat(dataScopeService.kbVisibility(admin).sql()).doesNotContain("dept_id");
        assertThat(kbGrantService.visibleKbIds(admin)).hasSize(8);
        assertThat(dataScopeService.kbLevel(admin, 1L)).isEqualTo(KbGrantLevel.MANAGE);
    }

    @Test
    @DisplayName("I-7 反向：授权了全部库（SUPER_ADMIN→MANAGE）但部门范围外 → 0 库可见")
    void grantWithoutDeptScope() {
        // lin.gong（dept 11 平台架构组）+ DEPT 范围：sys_kb 里没有归属"平台架构组"的库
        LoginUser lin = user("lin.gong", DataScope.DEPT, 11L);
        assertThat(kbGrantService.visibleKbIds(lin)).isEmpty();
        // 级别同样被 ② 拦下：即使授权表有 MANAGE，范围外返回 NONE
        assertThat(dataScopeService.kbLevel(lin, 1L)).isEqualTo(KbGrantLevel.NONE);
    }

    @Test
    @DisplayName("I-7 正向：DEPT_AND_CHILD 展开后范围内且有授权 → 只见本部门树下的库")
    void deptAndChild() {
        // 张伟是 KB_ADMIN（DEPT_AND_CHILD），基准设为研发中心(1)：
        // 8 库全被 KB_ADMIN 授权（UPLOAD），但 ② 只放行研发中心树下 → 恰好 kb1（直属研发中心）
        LoginUser zhang = user("zhang.wei", DataScope.DEPT_AND_CHILD, 1L);
        List<Long> visible = kbGrantService.visibleKbIds(zhang);
        assertThat(visible).containsExactly(1L);
        assertThat(dataScopeService.kbLevel(zhang, 1L)).isEqualTo(KbGrantLevel.UPLOAD);
        // 条件里必须真的有部门展开（G2-8：过滤在 SQL 层，不在内存）
        assertThat(dataScopeService.kbVisibility(zhang).sql()).contains("parent_id");
    }

    @Test
    @DisplayName("DEPT：不含子部门（边界）—— 基准在组上时 0 库可见")
    void deptWithoutChildren() {
        // lin.gong 以 DEPT 范围、基准平台架构组(11)：没有库直属该组 → 空；
        // 同基准换 DEPT_AND_CHILD 才会因"研发中心直属的 kb1 不在 11 的子树"仍为空，
        // 两者的差异要用"直属库"体现 —— 这里先断言 DEPT 的 SQL 形态与空结果
        LoginUser lin = user("lin.gong", DataScope.DEPT, 11L);
        assertThat(dataScopeService.kbVisibility(lin).sql()).contains("k.dept_id = :ds_dept");
        assertThat(kbGrantService.visibleKbIds(lin)).isEmpty();
    }

    @Test
    @DisplayName("SELF：不加部门条件，可见性只由授权决定（普通用户 = 仅已授权知识库）")
    void selfScope() {
        LoginUser liNa = user("li.na", DataScope.SELF, 2L);
        // SELF 不拼部门条件（《01》§2.5：普通用户 = 仅已授权知识库，部门无关）
        assertThat(dataScopeService.kbVisibility(liNa).sql()).doesNotContain("ds_dept");
        // USER 全部库 READ + 额外授权 kb1 UPLOAD → 多源取最高（§0.5）
        assertThat(dataScopeService.kbLevel(liNa, 1L)).isEqualTo(KbGrantLevel.UPLOAD);
        assertThat(dataScopeService.kbLevel(liNa, 2L)).isEqualTo(KbGrantLevel.READ);
        assertThat(kbGrantService.visibleKbIds(liNa)).hasSize(8);
    }

    @Test
    @DisplayName("CUSTOM：暂无配置入口，fail-closed 按 SELF 处理（宁可看不见不可越权）")
    void customFailClosed() {
        LoginUser u = user("li.na", DataScope.CUSTOM, 2L);
        assertThat(dataScopeService.kbVisibility(u).sql()).isEqualTo(
                dataScopeService.kbVisibility(user("li.na", DataScope.SELF, 2L)).sql());
    }

    @Test
    @DisplayName("无任何授权的账号：范围内也 0 库（③ 拦下）")
    void noGrantNoVisibility() {
        // userId=999999 不存在 → 授权子查询恒空
        LoginUser ghost = LoginUser.builder()
                .userId(999_999L).username("ghost").realName("ghost").deptId(1L)
                .superAdmin(false).dataScope(DataScope.ALL)
                .permissions(Set.of(PermissionCode.WILDCARD))
                .build();
        assertThat(kbGrantService.visibleKbIds(ghost)).isEmpty();
        assertThat(dataScopeService.kbLevel(ghost, 1L)).isEqualTo(KbGrantLevel.NONE);
    }

    @Test
    @DisplayName("G2-9 四级授权保存后立即影响可见性与级别（READ→UPLOAD→MANAGE）")
    void savingGrantImmediatelyAffectsVisibility() {
        // 用临时库，避免污染 V7 种子（本接口是单值 level 模型，改现有库会拉平混合档位）
        long kbId = 999_902L;
        jdbc.update("""
                insert into sys_kb (id, code, name, dept_id, status, created_at, updated_at)
                values (?, 'kb_g29_probe', 'G2-9 探针库', 1, 'READY', now(), now())
                """, kbId);
        try {
            LoginUser liNa = user("li.na", DataScope.SELF, 2L);
            // 未授权 → 不可见（③ 拦下）
            assertThat(kbGrantService.visibleKbIds(liNa)).doesNotContain(kbId);
            assertThat(dataScopeService.kbLevel(liNa, kbId)).isEqualTo(KbGrantLevel.NONE);

            // USER 角色(id=3) 授 READ → 保存后**立即**可见
            kbGrantService.save(kbId, new AdminDtos.KbGrantSaveRequest(List.of(3L), "READ", List.of()));
            assertThat(kbGrantService.visibleKbIds(liNa)).contains(kbId);
            assertThat(dataScopeService.kbLevel(liNa, kbId)).isEqualTo(KbGrantLevel.READ);

            // 提到 UPLOAD / MANAGE，每次保存后级别即时反映
            kbGrantService.save(kbId, new AdminDtos.KbGrantSaveRequest(List.of(3L), "UPLOAD", List.of()));
            assertThat(dataScopeService.kbLevel(liNa, kbId)).isEqualTo(KbGrantLevel.UPLOAD);
            kbGrantService.save(kbId, new AdminDtos.KbGrantSaveRequest(List.of(3L), "MANAGE", List.of()));
            assertThat(dataScopeService.kbLevel(liNa, kbId)).isEqualTo(KbGrantLevel.MANAGE);

            // 取消授权（改授其他角色）→ 立即不可见
            kbGrantService.save(kbId, new AdminDtos.KbGrantSaveRequest(List.of(4L), "READ", List.of()));
            assertThat(kbGrantService.visibleKbIds(liNa)).doesNotContain(kbId);
            assertThat(dataScopeService.kbLevel(liNa, kbId)).isEqualTo(KbGrantLevel.NONE);
        } finally {
            jdbc.update("delete from sys_kb_grant where kb_id = ?", kbId);
            jdbc.update("delete from sys_kb where id = ?", kbId);
        }
    }
}
