package com.knowledge.ai.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 排序规则一致性闸（`T-O13` · `S8-M10-A`）。
 *
 * <h3>它守的是什么</h3>
 * <p>本仓曾经**两套排序规则并存**（21 张 `utf8mb4_0900_ai_ci` + 10 张 `utf8mb4_unicode_ci`，
 * 根因是 `docker-compose.yml` 的服务端默认与一部分迁移里显式写的那份不一致）。
 * 跨表比较两个不同排序规则的字符串列会报
 * <b>1267 Illegal mix of collations</b> —— 建表时看不出来、评审看不出来、编译更看不出来，
 * **只有那条 SQL 真的执行时才炸**（`S6-M11` 的第一条用例就是这么炸的）。
 *
 * <h3>为什么这是一条「闸」而不是一次性的迁移</h3>
 * <p>收敛一次不难，难的是**不再裂开**：不写 `COLLATE` 的建表语句会落回库默认，
 * 而库默认刚被 `V33` 改成目标值 ⇒ 现在的约定是"**不写也对**"。
 * 但下一个人的第一反应可能是"照抄旁边那张表的写法"——如果抄到的是个意外，
 * 分歧就回来了。所以这条闸把"全库一致"变成**可机器检查**的。
 *
 * <h3>豁免只有一张，且**不许扩大**</h3>
 * <p>{@code flyway_schema_history}：Flyway 自己的记账表，没有业务查询 join 它，
 * 而它的 DDL 归 Flyway 所有。{@link #exemptionListIsFrozen()} 钉死"只许这一张" ——
 * 否则豁免名单会慢慢变成"凡是懒得改的都加进来"，闸也就关了。
 */
@SpringBootTest
class CollationUniformityTest {

    /** 收敛目标（`V33`）：MySQL 8 原生、也是库默认。 */
    private static final String TARGET = "utf8mb4_0900_ai_ci";

    /** 冻结豁免名单 —— 改这里必须同时改本条 javadoc 说明理由。 */
    private static final List<String> EXEMPT = List.of("flyway_schema_history");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("全库的字符串列都必须是同一个排序规则（除冻结豁免外）")
    void everyStringColumnSharesOneCollation() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select table_name, collation_name, count(*) as cols
                from information_schema.columns
                where table_schema = database() and collation_name is not null
                group by table_name, collation_name
                order by table_name
                """);

        assertThat(rows)
                .as("一张表都没扫到 —— 闸恒通过，等于没有。先确认 information_schema 查得到本库")
                .isNotEmpty();

        List<String> offenders = rows.stream()
                .filter(r -> !EXEMPT.contains(String.valueOf(r.get("table_name"))))
                .filter(r -> !TARGET.equals(String.valueOf(r.get("collation_name"))))
                .map(r -> r.get("table_name") + "(" + r.get("collation_name") + ")")
                .toList();

        assertThat(offenders)
                .as("这些表与全库不一致：跨表比较它们的字符串列会报 1267，"
                        + "而那条 SQL 平时根本不会跑到。处置：补一条 CONVERT 迁移（别改已执行的 V 文件）")
                .isEmpty();
    }

    @Test
    @DisplayName("库默认也得是目标值 —— 否则下次建表又裂开")
    void databaseDefaultIsTarget() {
        String actual = jdbc.queryForObject(
                "select default_collation_name from information_schema.schemata where schema_name = database()",
                String.class);
        assertThat(actual)
                .as("库默认不是目标值 ⇒ 不写 COLLATE 的建表语句会落回旧排序规则，"
                        + "分歧会在**下一次加表**时原样回来")
                .isEqualTo(TARGET);
    }

    @Test
    @DisplayName("豁免名单冻结 —— 只许 flyway_schema_history 一张")
    void exemptionListIsFrozen() {
        assertThat(EXEMPT)
                .as("豁免是「闸上的洞」。要加必须说清理由并改这条断言 —— 让它至少是一次显式决定")
                .containsExactly("flyway_schema_history");
    }

    @Test
    @DisplayName("历史事故的那条跨表比较现在跑得通（1267 的回归锚）")
    void crossTableComparisonFromTheIncidentWorks() {
        // `NoticeRepository#findTask` 里的那个 join 形状。
        // ⚠️ 它在 `S6-M11` 真的抛过 1267（当时 `sys_notice.source_task_id` 是 0900_ai_ci
        //    而 `sys_async_task.task_id` 是 unicode_ci）—— 那次的处置是**改新表去迁就旧表**，
        //    也就是"哪边更好改就改哪边"，而分歧本身留着。这里把它钉住。
        assertThatCode(() -> jdbc.queryForObject("""
                        select count(*) from sys_notice n
                        join sys_async_task t on n.source_task_id = t.task_id
                        """, Long.class))
                .as("两个字符串列的跨表比较报错 ⇒ 排序规则又裂开了")
                .doesNotThrowAnyException();
    }
}
