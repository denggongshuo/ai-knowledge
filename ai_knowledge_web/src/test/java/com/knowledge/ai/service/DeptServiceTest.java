package com.knowledge.ai.service;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 部门删除的四道闸（T-200/T-201 · 《05》§1.6）。
 *
 * <p>S2 结项时这里只有三道（虚拟节点 / 子部门 / 成员），**知识库校验留了 TODO**
 * —— 当时 {@code sys_kb} 还是骨架、硬写 SQL 会报错。V7 建表后回补，本类补上门禁用例。
 *
 * <p>用例全部"造临时部门 → 用完删"，**不动种子部门** ——
 * 种子部门名与人数是 G2-1 的断言对象，改动会连累 {@code UserAdminFlowTest}。
 */
class DeptServiceTest extends ApiTestSupport {

    @Autowired
    private DeptService deptService;

    /** 造一个空的顶级部门（成员 0、子部门 0、知识库 0），返回 id。 */
    private long createDept(String name) {
        jdbc.update("insert into sys_department (name, parent_id, sort, created_at, updated_at) "
                + "values (?, null, 999, now(), now())", name);
        Long id = jdbc.queryForObject("select id from sys_department where name = ?", Long.class, name);
        return id == null ? -1L : id;
    }

    /** 拆除临时部门：先删挂在它下面的知识库（sys_kb 无外键，但会挡住下一步校验）。 */
    private void deleteDept(long id) {
        jdbc.update("delete from sys_kb where dept_id = ?", id);
        jdbc.update("delete from sys_department where id = ?", id);
    }

    @Test
    @DisplayName("G2-6 部门删除：空部门可删（对照）；挂上知识库后被拒，提示先转移知识库")
    void deptDeleteBlockedByKnowledgeBase() {
        long deptId = createDept("G26临时部门A");
        try {
            // 对照组：此时无用户 / 无子部门 / 无知识库 → 可删
            assertThat(deptService.delete(deptId).deleted()).isTrue();

            // 重建一个同名部门并挂知识库，验证「有知识库被拒」
            deptId = createDept("G26临时部门A");
            jdbc.update("""
                    insert into sys_kb (code, name, dept_id, doc_count, chunk_count, status, created_at, updated_at)
                    values ('kb_g26_temp', 'G26临时知识库', ?, 0, 0, 'READY', now(), now())
                    """, deptId);

            final long probe = deptId;
            BizException ex = assertThrows(BizException.class, () -> deptService.delete(probe));
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DEPT_NOT_EMPTY);
            assertThat(ex.getMessage()).contains("知识库");

            // 被拒后部门仍在
            Long alive = jdbc.queryForObject(
                    "select count(*) from sys_department where id = ?", Long.class, deptId);
            assertThat(alive).isEqualTo(1L);
        } finally {
            deleteDept(deptId);
        }
    }

    @Test
    @DisplayName("G2-6 部门删除：有成员被拒（回归 S2 已实现的三道闸之一）")
    void deptDeleteBlockedByMembers() {
        long deptId = createDept("G26临时部门B");
        try {
            jdbc.update("""
                    insert into sys_user (username, password_hash, real_name, dept_id, status,
                                          must_change_password, created_at, updated_at)
                    values ('g26.member', '$2b$10$XRFAfIgqFeWwRtKENYFwjOZKQDjfz7f7/Ca2tqDgZfIyqlfUQX6O2',
                            'G26成员', ?, 'ACTIVE', 0, now(), now())
                    """, deptId);
            trackUser("g26.member");
            Long uid = jdbc.queryForObject(
                    "select id from sys_user where username = 'g26.member'", Long.class);
            jdbc.update("insert into sys_user_role (user_id, role_id, created_at) "
                    + "select ?, id, now() from sys_role where code = 'USER'", uid);

            final long probe = deptId;
            BizException ex = assertThrows(BizException.class, () -> deptService.delete(probe));
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.DEPT_NOT_EMPTY);
            assertThat(ex.getMessage()).contains("成员");
        } finally {
            // 必须**先删成员再删部门**：sys_user.dept_id 有外键指向 sys_department，
            // 顺序反了会撞 FK 约束（基类的账号清理由 @AfterEach 执行，晚于这里）。
            jdbc.update("delete ur from sys_user_role ur join sys_user u on u.id = ur.user_id "
                    + "where u.username = 'g26.member'");
            jdbc.update("delete from sys_user where username = 'g26.member'");
            deleteDept(deptId);
        }
    }

    @Test
    @DisplayName("部门删除：虚拟节点「未分配」不可删；未知 id 报部门不存在")
    void deptDeleteEdgeCases() {
        BizException virtual = assertThrows(BizException.class, () -> deptService.delete(0L));
        assertThat(virtual.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(virtual.getMessage()).contains("虚拟节点");

        BizException missing = assertThrows(BizException.class, () -> deptService.delete(999_999L));
        assertThat(missing.getErrorCode()).isEqualTo(ErrorCode.DEPT_NOT_FOUND);
    }
}
