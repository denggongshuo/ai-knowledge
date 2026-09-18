package com.knowledge.ai.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * **探针产物的清理实现**（`S5` 测试可靠性修复批）—— 两个用例基座共用这一份。
 *
 * <h3>为什么必须共用</h3>
 * <p>`#130` 的原话是「{@code cleanProbeData} 是各用例类自己写的，**漏一处没有机器检查能发现**」。
 * 共用只是第一步 —— 真正管用的是 {@link ProbeArtifactCoverageTest} 那道闸；
 * 但闸只能证明"有人想到过这张表"，**删得对不对**还是要靠这里只有一份实现。
 *
 * <h3>两条硬规矩（都是踩出来的）</h3>
 * <ol>
 *   <li><b>归属 id 必须在删行之前抓</b> —— 删完 `sys_kb` / `sys_user` 就再也 join 不上了。
 *       脚本侧为此吃了大亏：残留自检用 `join sys_kb` 数残留，而 `sys_kb` 被同一轮清理删掉
 *       ⇒ **恒返回 0**（实测：自检报"全 0"时 634 行孤儿在躺着，见 `#136`）。
 *       这里**一律不 join**，用调用方抓好的 id 集合直删。</li>
 *   <li><b>先子后父</b> —— 顺序即正确性（`sys_chunk` 早于 `sys_document` 早于 `sys_kb`；
 *       `sys_login_log`/`sys_user_role` 早于 `sys_user`；明细早于主表）。</li>
 * </ol>
 *
 * <p>⚠️ 本类**刻意不做**"清全库"式删除（如 `delete from sys_chunk`）—— 那会连**别人**的
 * 探针数据一起清掉，多个用例类并行/连续跑时会互相打架。只删调用方点名的那批 id。
 */
public final class ProbeCleanup {

    /**
     * 按 `kb_id` 删的那些表，**顺序即执行顺序**。
     *
     * <p>`sys_async_task` 排第一：它没有外键，但先删它能让"作业还在写"这件事尽早暴露
     * （`S4-E1` 的 `quiesceParseJobs` 是脚本侧的同一条防线）。
     */
    private static final List<String> KB_BY_KB_ID = List.of(
            "sys_async_task", "sys_parse_issue", "sys_chunk", "sys_document", "sys_kb_grant");

    /** 按 `doc_id` 删的那些表（文档可能不属于任何一个"要删的库"，故单列一条路）。 */
    private static final List<String> KB_BY_DOC_ID = List.of(
            "sys_async_task", "sys_parse_issue", "sys_chunk");

    private ProbeCleanup() {
    }

    /**
     * 按 `kb_id` / `doc_id` 清一个或多个探针库的产物，最后删库行。
     *
     * @param kbIds 调用方在**删任何行之前**抓到的库 id
     */
    public static void cleanKb(JdbcTemplate jdbc, Collection<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return;
        }
        String in = placeholders(kbIds.size());
        Object[] args = kbIds.toArray();
        for (String table : KB_BY_KB_ID) {
            jdbc.update("delete from " + table + " where kb_id in (" + in + ")", args);
        }
        jdbc.update("delete from sys_kb where id in (" + in + ")", args);
    }

    /**
     * 按 `doc_id` 清文档（**独立于库**）。
     *
     * <p>与 {@link #cleanKb} 不重叠时才有意义 —— 有些用例先建文档、库却不是探针库。
     * 顺序同样**先子后父**：分片 → 问题 → 文档。
     */
    public static void cleanDocs(JdbcTemplate jdbc, Collection<Long> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return;
        }
        String in = placeholders(docIds.size());
        Object[] args = docIds.toArray();
        for (String table : KB_BY_DOC_ID) {
            jdbc.update("delete from " + table + " where doc_id in (" + in + ")", args);
        }
        jdbc.update("delete from sys_document where id in (" + in + ")", args);
    }

    /**
     * 清探针账号及其全部产物。
     *
     * <h3>⚠️ 第一步就是自己抓 id，不是从参数里要</h3>
     * <p>因为最后要删 `sys_user` —— 删完再想"这个账号的登录日志是哪几行"就永远查不到了。
     * 这正是脚本侧 `#136` 的形状，在这里**从结构上堵死**：id 只可能在这里抓到，
     * 下游一律用它，不再有第二次查询的机会。
     *
     * @param usernames 探针账号名
     */
    public static void cleanUsers(JdbcTemplate jdbc, Collection<String> usernames) {
        if (usernames == null || usernames.isEmpty()) {
            return;
        }
        String userIn = placeholders(usernames.size());
        Object[] userArgs = usernames.toArray();

        List<Long> ids = jdbc.queryForList(
                "select id from sys_user where username in (" + userIn + ")", Long.class, userArgs);
        if (!ids.isEmpty()) {
            String idIn = placeholders(ids.size());
            Object[] idArgs = ids.toArray();
            // 子表在前、主表在后。**子查询里的表与删除目标必须是不同的表** ——
            // 同表会报 1093（MySQL 不允许 delete 的目标出现在 From 子句里）
            jdbc.update("delete from sys_retrieval_trace_chunk where trace_id in "
                    + "(select t.trace_id from sys_retrieval_trace t where t.user_id in (" + idIn + "))", idArgs);
            jdbc.update("delete from sys_retrieval_trace where user_id in (" + idIn + ")", idArgs);
            jdbc.update("delete from sys_chat_message where session_id in "
                    + "(select s.id from sys_chat_session s where s.user_id in (" + idIn + "))", idArgs);
            jdbc.update("delete from sys_chat_session where user_id in (" + idIn + ")", idArgs);
            // 问答附件（`T-805`）**自己有归属列**，不顺着会话删 —— 它本来就可以先于会话存在
            // （预上传时还没有会话）。⚠️ 它同时是一个 **MinIO 对象**：这里只删行，
            // 桶里的对象由生命周期规则兜底（与 `DocStorage#remove` 无调用方同一条取舍）
            jdbc.update("delete from sys_chat_attachment where user_id in (" + idIn + ")", idArgs);
            // ⚠️ `sys_async_task` 的归属列是 **`owner_id`** 而不是 `user_id` —— 导出这类
            // **没有知识库维度**的任务（`kb_id` 为 NULL）只能靠它找到。
            // 本批实测：`AuditExportService` 此前压根没传业务坐标 ⇒ `owner_id` 是 NULL，
            // 于是这类任务**谁也认领不了**（已一并修好，见那边注释）
            jdbc.update("delete from sys_async_task where owner_id in (" + idIn + ")", idArgs);
            // `sys_login_log` 是 `#98` 的根因所在：此前**一个类都没删过**（实测 +242 行/轮）
            jdbc.update("delete from sys_login_log where user_id in (" + idIn + ")", idArgs);
            jdbc.update("delete from sys_user_role where user_id in (" + idIn + ")", idArgs);
        }
        jdbc.update("delete from sys_user where username in (" + userIn + ")", userArgs);
    }

    /** 无主产物兜底要扫的表 —— **顺序即执行顺序**（子表在前）。 */
    private static final List<String> ORPHAN_SWEEP = List.of(
            "sys_async_task", "sys_parse_issue", "sys_chunk", "sys_document", "sys_kb_grant");

    /**
     * **兜底**：删掉那些"父行已经不在"的产物（`kb_id` 指向一个不存在的 `sys_kb`）。
     *
     * <h3>为什么需要兜底，而不是让每个用例类自己清干净</h3>
     * <p>因为"每个类自己清"这条约定**已经被证明会漏**：实测有十余个用例类各写一份
     * {@code cleanProbeData}，其中 {@code DocUploadFlowTest} 那份删了 5 张表、
     * **唯独漏 {@code sys_async_task}} —— 一轮全量就留下 **76 行**，
     * 而它们因为 `sys_kb` 行先被删，在库里**谁也看不见**（`#130`）。
     *
     * <p>兜底换了个问法：**不问"你清了没有"，只问"库里还剩不剩无主的东西"**。
     * 于是漏一处的代价从"永久累积"变成"多一次 DELETE"。
     * 这与脚本侧 `#136` 的修法是同一条思路 ——
     * **与归属无关的观测点，才不会被归属的消失骗过去**。
     *
     * <p>⚠️ 它**只兜 kb 族**。用户族（`sys_login_log` 等）的归属是 `user_id`，
     * 而"用户被删、日志要留"在生产里是**合规要求**，不能按无主一刀切 ——
     * 那一族由 {@link #cleanUsers} 按**探针账号**点名清，加上 `ApiTestSupport`
     * 的登录日志水位线。
     *
     * @return 删掉的行数（>0 说明有类漏清了 —— 这是**信号**，不是正常现象）
     */
    public static int sweepOrphanKbArtifacts(JdbcTemplate jdbc) {
        int removed = 0;
        for (String table : ORPHAN_SWEEP) {
            // `not in (子查询)` 里的子查询读的是 `sys_kb`，与删除目标不同表 ⇒ 不会撞 1093
            removed += jdbc.update("delete from " + table + " where kb_id is not null "
                    + "and kb_id not in (select id from sys_kb)");
        }
        return removed;
    }

    private static String placeholders(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> "?")
                .collect(Collectors.joining(","));
    }
}
