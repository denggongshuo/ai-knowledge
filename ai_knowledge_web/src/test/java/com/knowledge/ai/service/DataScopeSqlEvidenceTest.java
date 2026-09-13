package com.knowledge.ai.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2-8 取证：数据范围过滤**下推到了 SQL 层**，不是"查全量后内存过滤"（D-3）。
 *
 * <p>判据原文（`03-Sprint排期` §Sprint 2 · G2-8）：用 p6spy/SQL 日志证明过滤条件
 * 出现在 SQL 里。本类用 Logback 的 {@link ListAppender} 挂到 {@code org.hibernate.SQL}，
 * 在**真 HTTP 请求**下抓取 Hibernate 实际执行的语句，断言 where 子句里出现了
 * {@code dept_id} / {@code parent_id} 的展开条件。
 *
 * <p>为什么必须抓真实 SQL 而不是断言 `DataScopeService.userScope(...).sql()` 的返回值：
 * 那是"拼串函数返回了正确片段"，不等于"这个片段真的进了最终 SQL"——中间任何一处
 * 忘记把片段拼进 where，前者仍然全绿。G2-8 要的正是后者。
 *
 * <p>夹具：造一个 {@code DEPT_AND_CHILD} 范围 + {@code user:list} 权限的探针角色，
 * 挂到注册出来的账号上（并改到 dept=11 平台架构组，使范围展开有实际意义），
 * 重新登录拿到带新范围的令牌后调 {@code GET /api/users}。用例结束删掉探针角色，
 * 账号由基类统一清理。
 */
class DataScopeSqlEvidenceTest extends ApiTestSupport {

    private static final String BIND_LOGGER = "org.hibernate.SQL";

    @Test
    @DisplayName("G2-8：DEPT_AND_CHILD 用户的列表查询，SQL 里带 dept_id + parent_id 展开")
    void deptScopeFilterIsInSql() {
        TestUser probe = createUser("probe.scope");
        Long userId = jdbc.queryForObject("select id from sys_user where username = ?", Long.class, probe.username());
        jdbc.update("update sys_user set dept_id = 11 where id = ?", userId);

        String code = "PROBE_DEPT_SCOPE";
        jdbc.update("""
                insert into sys_role (code, name, description, data_scope, enabled, builtin, sort, created_at, updated_at)
                values (?, '探针部门范围角色', 'G2-8 取证用，用例结束即删', 'DEPT_AND_CHILD', 1, 0, 900, now(), now())
                """, code);
        Long roleId = jdbc.queryForObject("select id from sys_role where code = ?", Long.class, code);
        jdbc.update("""
                insert into sys_role_permission (role_id, permission_id, created_at)
                select ?, p.id, now() from sys_permission p where p.code = 'user:list'
                """, roleId);
        jdbc.update("insert into sys_user_role (user_id, role_id, created_at) values (?, ?, now())", userId, roleId);

        Logger sqlLogger = (Logger) LoggerFactory.getLogger(BIND_LOGGER);
        Level previousLevel = sqlLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        sqlLogger.addAppender(appender);
        sqlLogger.setLevel(Level.DEBUG);

        try {
            // 必须重新登录：JWT 里带的是签发那一刻的 dataScope / 权限，改库不会改变旧令牌
            String token = login(probe.username(), DEFAULT_PASSWORD).assertOk("探针重新登录").data("$.accessToken");
            get("/api/users?page=1&size=5", token).assertOk("数据范围用户列表");

            String userListSql = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(s -> s.contains("sys_user u"))
                    .reduce("", (a, b) -> a + "\n" + b);

            assertThat(userListSql).as("应抓到 sys_user 列表的 SQL").isNotBlank();
            // ② 数据范围展开真的进了 where：本部门 + 子部门（两层结构，parent_id 等价递归）
            assertThat(userListSql).contains("u.dept_id = ?");
            assertThat(userListSql).contains("parent_id = ?");
            // 软删过滤与用户表别名同在
            assertThat(userListSql).contains("u.deleted_at is null");
            // 反向：DEPT 范围不应用 SELF 的 "u.id = ?" 条件（多角色取最宽，不是最窄）
            assertThat(userListSql).doesNotContain("u.id = ?");

            System.out.println("【G2-8 SQL 实录】\n" + userListSql);
        } finally {
            sqlLogger.detachAppender(appender);
            sqlLogger.setLevel(previousLevel);
            jdbc.update("delete from sys_user_role where role_id = ?", roleId);
            jdbc.update("delete from sys_role_permission where role_id = ?", roleId);
            jdbc.update("delete from sys_role where id = ?", roleId);
        }
    }
}
