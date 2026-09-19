package com.knowledge.ai.support;

import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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

    /**
     * **无主打点明细兜底**（`#210` · `2026-09-19` 测试可靠性批）。
     *
     * <h3>它抓的是什么</h3>
     * <p>{@code sys_retrieval_trace_chunk} 里 {@code trace_id} 指向一个**不存在**的
     * {@code sys_retrieval_trace} 行。实测基线里躺过 **110 行**这种行（父表 0 行，
     * 而那张表的 AUTO_INCREMENT 已经是 371）。
     *
     * <h3>为什么会有 —— 根因有代码证据，不是猜的</h3>
     * <p>{@code RetrievalTraceWriter#write} 在**同一个 {@code REQUIRES_NEW} 事务**里
     * 先 insert 主表、后 insert 明细；而用例收尾的两条 DELETE **跨在这个事务的提交窗口两侧**：
     * <pre>
     *   worker:      BEGIN → insert trace(T) → insert chunks(T) → COMMIT
     *   用例收尾:  ① DELETE chunks WHERE trace_id IN (SELECT … 已提交的 …)   ← 看不见 T ⇒ 删 0 行
     *              ┊  ← worker 在这里 COMMIT（主行与明细**一起**可见）
     *              ② DELETE trace WHERE session_id &gt; floor                  ← 这次看得见 ⇒ 主行被删
     *   ⇒ 明细留下、主行没了 = 孤儿
     * </pre>
     * ⚠️ **窗口只有"两条 DELETE 之间"那一段** ⇒ 它难复现：实测**单独反复跑
     * {@code ChatStreamingFlowTest} 3 轮，每轮后孤儿都是 0**。
     * 所以这条兜底不是"修好了那个竞态"，而是**让竞态的代价从"永久累积"变成"多一次 DELETE"**。
     *
     * <h3>为什么只能靠它（这张表的特殊性）</h3>
     * <p>它**既没有 {@code user_id} 也没有 {@code kb_id}** —— 一旦成了孤儿，
     * **没有任何归属维度能认领它**，`sweepOrphanKbArtifacts` 与 `cleanUsers` 结构上都够不着。
     * 能问的只剩"**与归属无关的那个观测点**"：主行还在不在。
     *
     * <h3>🔴 它不会误删在途数据 —— 这句话必须写下来</h3>
     * <p>`write()` 是**先主后明细、且同一个事务**（见 {@code RetrievalTraceWriter} 的 INSERT 顺序）
     * ⇒ 不存在"明细已可见、主行还没可见"的中间态：要么两者都不可见，要么**一起**可见。
     * 所以"明细在、主行不在"只可能是**已经被删过**的那一半。
     * ⚠️ **若哪天有人把 `write()` 改成两个事务、或先写明细**，这条兜底立刻变成
     * **会删在途数据**的机制 —— 改那里的人必须回来读这一段。
     *
     * @return 删掉的行数。**&gt; 0 是信号**，不是正常现象 —— 说明竞态真的发生了
     */
    public static int sweepOrphanTraceChunks(JdbcTemplate jdbc) {
        return jdbc.update("delete from sys_retrieval_trace_chunk "
                + "where trace_id not in (select trace_id from sys_retrieval_trace)");
    }

    /**
     * **无主通知兜底**（`T-B03` · `2026-09-19-S6-M11` 批）。
     *
     * <h3>为什么本批必须加它 —— 这是 `T-B03` 的**外溢面**</h3>
     * <p>接线之后，**凡是跑完一个异步任务的用例都会产出通知行**：解析类用例
     * （`ParseWorkerFlowTest` / `DocParseFlowTest` / `DocUploadFlowTest` …）走 `doc-upload`，
     * 导出类用例（`AuditQueryFlowTest`）走 `audit-export`。
     * 它们**此前一行都不产** ⇒ 「谁清通知」这个问题在本批之前不存在，是本批**新造出来的**。
     *
     * <h3>问法：还是"库里还剩不剩无主的东西"</h3>
     * <p>与 {@link #sweepOrphanKbArtifacts} 同一条思路，但 `sys_notice` 的归属**有两根线**，
     * 两个方向都要问：
     * <ul>
     *   <li><b>任务线</b> —— {@code source_task_id} 指向的 `sys_async_task` 行已经没了。
     *       这是**通知最主要的无主形态**：用例收尾会删任务行（kb 族兜底按 `kb_id` 删、
     *       {@link #cleanUsers} 按 `owner_id` 删），任务一旦没了，它产出的通知就没人能认领。</li>
     *   <li><b>人线</b> —— {@code user_id} 指向的用户行已经没了（探针账号被删）。</li>
     * </ul>
     * ⚠️ 顺带清两张子表：`sys_notice_read`（通知没了、或人没了，它就成了孤儿）与
     * `sys_notice_setting`（人没了）。**子表在前** —— 与 {@link #ORPHAN_SWEEP} 同序。
     *
     * <h3>🔴 这条判据**不能直接搬到生产**</h3>
     * <p>「任务行不在 ⇒ 通知作废」只在**测试**里成立（任务行与通知同批产生、同批清理）。
     * 生产里若哪天引入任务行的归档/清理，而通知的留存期比它长，这条判据会把
     * **用户还没读的通知**当成孤儿删掉。
     * ⚠️ 真要搬到生产，判据必须补上**时间下限**（`T-O10`/`T-O11` 那两条兜底用的
     * {@link #DEFAULT_MIN_AGE} 就是干这个的），或改成"按通知自己的保留策略"。
     *
     * <h3>⚠️ 它抓不到"归属还在的"残留（射程如实写明）</h3>
     * <p>用**种子超管**（`admin`）跑出来的通知，它的 `owner_id` 与 `source_task_id`
     * 可能都仍然有效 ⇒ **不属于无主**，这条兜底看不见它。那一半靠各用例自己清
     * （`AuditQueryFlowTest` 就是这么做的）。这与 `T-O11` 的 `audit-export/` 兜底是
     * **同一条限制** —— **两条合起来才叫覆盖**。
     *
     * @return 删掉的行数。**&gt; 0 是信号，但比 {@link #sweepOrphanTraceChunks} 那条弱** ——
     *         这里 &gt;0 多半只是"任务行先被删了"，那是**预期顺序**，不是事故
     */
    public static int sweepOrphanNotices(JdbcTemplate jdbc) {
        int removed = 0;
        // 1) 子表：读状态里"人没了"的
        removed += jdbc.update("delete from sys_notice_read "
                + "where user_id not in (select id from sys_user)");
        // 2) 主表：任务线 + 人线（两个 not in 的子查询读的都是**别的表** ⇒ 不会撞 1093）
        removed += jdbc.update("delete from sys_notice "
                + "where (source_task_id is not null "
                + "       and source_task_id not in (select task_id from sys_async_task)) "
                + "   or (user_id is not null and user_id not in (select id from sys_user))");
        // 3) 子表：读状态里"通知没了"的 —— 第 2 步刚制造出来的那一批
        removed += jdbc.update("delete from sys_notice_read "
                + "where notice_id not in (select id from sys_notice)");
        // 4) 设置里"人没了"的
        removed += jdbc.update("delete from sys_notice_setting "
                + "where user_id not in (select id from sys_user)");
        return removed;
    }

    /**
     * **按日期**清模型用量（`T-905` · `S6-M09-B` 新增）—— 清**当天**那一格到今天为止。
     *
     * <h3>为什么是"按日期"而不是"按归属"</h3>
     * <p>因为 {@code sys_model_usage} **一个归属列都没有**（没有 `kb_id` / `doc_id` /
     * `user_id`，它是"哪个模型、干什么用、调了多少次"的聚合）。所以：
     * <ul>
     *   <li>上面的 {@link #sweepOrphanKbArtifacts} 那套"无主产物"问法**问不了它**
     *       （它没有可以悬空的父）；</li>
     *   <li>{@link ProbeArtifactCoverageTest} 的覆盖率闸**结构上也扫不到它**
     *       —— 闸的输入是"带归属列的表"，而它不属于那一类。这是 `#130` 那个盲区的
     *       <b>第三种形态</b>（前两种：`via: 'kb_id'` 认不出 `kb_id` 为 NULL 的行、
     *       `sys_chat_message` 没有归属列只能靠两侧对账发现）。
     *       台账记在 {@link ProbeArtifacts#NO_OWNERSHIP_COLUMN}。</li>
     * </ul>
     * <p>唯一可用且有意义的维度就是**日期**：它是按天聚合的，日期是它的天然主键维度。
     *
     * <h3>⚠️ 代价照实说</h3>
     * <p>它**会把当天的真实用量一并清掉** —— 探针写的行与真实的行业务上无法区分
     * （同一个模型名、同一个用途）。这与 `#148`（存量登录日志 4928 行，"无法与真实流量区分，
     * 不修，记录"）是同一族取舍。之所以仍敢清，是因为本表是**聚合表**：
     * 行数上界 = 天数 × 模型数 × 用途数（满打满算几十行），**不随调用次数增长** ——
     * "清一次就干净"，不清也只是几十行，而清掉的代价是**当天**的统计数。
     *
     * <p>调用点：{@code ApiTestSupport} 的 `@AfterEach`（与无主产物兜底同一处）——
     * 于是每个用例方法跑完都把当天的探针痕迹抹掉，一类跑完不会把灌水留给下一类。
     *
     * @return 删掉的行数
     */
    public static int sweepProbeUsage(JdbcTemplate jdbc) {
        return jdbc.update("delete from sys_model_usage where occurred_at = curdate()");
    }

    /**
     * **MinIO 对象的兜底**（`T-O10`，用户 2026-09-18 裁定收进 O 目录）——
     * 扫四个前缀下的**无主对象**并删掉。
     *
     * <h3>为什么需要它：SQL 侧有兜底，MinIO 侧一直没有</h3>
     * <p>{@link #sweepOrphanKbArtifacts} 早就把"不问'你清了没有'，只问'库里还剩不剩无主的东西'"
     * 这条问法用在了**行**上；而**对象**那一侧**从来没有对等物** ——
     * 只有"每个用例类各自删自己造的那些"（`ParseWorkerFlowTest` / `DocParseFlowTest` /
     * `ChatAttachmentFlowTest` **各写一遍** `removeObject`）。
     *
     * <p>⚠️ **"每个类自己清"这条约定已被证明会漏**（`#130`：`DocUploadFlowTest` 那份删了 5 张表、
     * 唯独漏 `sys_async_task`，一轮全量留 76 行）。**同一个形状**在对象侧原样存在，
     * 代价是实测过的：桶里攒到 1030 个对象 ⇒ `storage-quota` 全桶递归列举 ⇒ 读超时 ⇒
     * **把毫不相干的 `KbWriteFlowTest` 拖红**（现象离原因极远）。
     *
     * <h3>四个前缀**一次收口**，不一个一个补</h3>
     * <p>只在 `documents/` 上做等于把同一个坑留给另外三个 —— 而本仓已为"一处一处补"付过四次学费。
     *
     * <h3>🔴 时间下限是**唯一**的防误删机制，不能省</h3>
     * <p>**上传路径是先落桶、后落行**（`ChatAttachmentService#upload`：`storage.store(...)` →
     * 再 `repository.save(row)`；`DocStorage` 同族）。所以"对象在、行不在"在上传进行中是**正常状态**。
     * 没有时间下限的话，这个兜底会把**正在上传的对象删掉** ——
     * 那是**用一个防残留的机制制造数据丢失**，与「闸必须放在建探针之前」是同一条纪律。
     *
     * <p>⚠️ 因此本方法**必须能被用例以 {@code Duration.ZERO} 调到**：
     * 一个**没法被验证的兜底**与**没有兜底**在证据上是同一件事。
     *
     * <h3>🔴 量"桶里有几个对象"要用**客户端**，不要 `ls` 数容器里的行</h3>
     * <p>MinIO 在对象被删掉之后**不会修剪空目录**，所以
     * {@code ls -1R /data/rag-documents/documents} 会把那些**空目录壳**一起列出来。
     * 实测踩到：按行数读出"905 个孤儿对象"，用客户端一量是 **0** —— 差了两个数量级，
     * 而那个错数字差点促成一次**不可逆的批量删除决定**。
     *
     * <p>⇒ 判据是：**用与删除同一个工具去量**。`listObjects` 说什么才算什么。
     * （与 `#136`「闸说干净与真的干净是两件事」是同一条纪律的又一次现身：
     * 这一次是"观测工具与被观测对象不是同一个"。）
     *
     * <h3>边界（写下来，免得下一个人以为它管全桶）</h3>
     * <p>🔴 **`T-O11` 订正了旧版这里的一句话。** 旧版写的是：
     * 「没有归属列的对象**结构上判不了无主** —— `audit-export/` 就是」。
     * **对了一半** —— 它确实没有归属**列**，但归属**在对象名的路径里**
     * ⇒ 它现在**进了清单**（见 {@link Owner#PATH_USER_ID}）。
     *
     * <p>⚠️ **但那条判据的射程必须说清，否则下一个人会以为它管全了**：
     * `PATH_USER_ID` 判的是「**那个账号的行还在不在**」。于是：
     * <ul>
     *   <li>✅ **探针账号**（{@code ApiTestSupport#createUser} 造的，收尾走 {@link #cleanUsers}
     *       的**硬删**）—— 它们留下的导出对象会被扫到；</li>
     *   <li>🔴 **种子超管 `admin`** —— 他才是 {@code AuditQueryFlowTest} 的真实导出人，
     *       而他的行**永远在** ⇒ **兜底对他产出的残留一个字都做不了**。</li>
     * </ul>
     * ⇒ 那一半**只能靠造它的用例自己清**（{@code AuditQueryFlowTest#cleanExportTasks}）。
     * **两条合起来才叫覆盖**：兜底管"归属真的消失了"，用例管"归属还在、但产物是测试造的"。
     * 这与 `#187` 的结论是同一句 —— **不为一条不存在的流程写代码**：所以这里**没有、也不该有**
     * 一条"按时间清 `admin` 的导出"的判据，那会**删掉真实用户的导出**。
     *
     * <p><b>调用点只在 {@code ApiTestSupport}</b>，`RetrievalFlowSupport` **刻意没加** ——
     * 那个基座的用例全是**直接插库造数据**（`insertDocWithChunks` 之类），**不往桶里写对象**，
     * 加了等于每个方法白花一次 `listObjects` 往返、零收益。
     * ⚠️ **但那条前提会变**：哪天有人在那个基座上写了一个真上传的用例，**就要把调用补上**
     * （否则那个用例的残留**结构上不在兜底的输入里** —— 正是 `#149` 记的那个形态）。
     *
     * @param minAge 只扫**比它更老**的对象；调用方传 {@code Duration.ZERO} 表示"不设下限"（用例用）
     * @return 删掉的对象数。**> 0 是信号，不是正常现象** —— 说明有类漏清了
     */
    public static int sweepOrphanMinioObjects(io.minio.MinioClient minioClient, String bucket,
                                             JdbcTemplate jdbc, java.time.Duration minAge) {
        java.time.Instant cutoff = java.time.Instant.now().minus(minAge);
        int removed = 0;
        for (MinioPrefix prefix : MINIO_PREFIXES) {
            String objectPrefix = prefix.prefix();
            try {
                for (io.minio.Result<io.minio.messages.Item> result : minioClient.listObjects(
                        io.minio.ListObjectsArgs.builder()
                                .bucket(bucket).prefix(objectPrefix).recursive(true).build())) {
                    io.minio.messages.Item item = result.get();
                    // 太新 ⇒ 可能正在上传（先落桶后落行）—— **跳过，不删**
                    if (item.lastModified().toInstant().isAfter(cutoff)) {
                        continue;
                    }
                    Boolean owned = hasOwner(prefix, item.objectName(), jdbc);
                    if (owned == null) {
                        // **判不了就别动它** —— 与"猜一个 chunkId"同一条口径：删错比少删严重得多
                        continue;
                    }
                    if (owned) {
                        continue;       // 有主，活着
                    }
                    minioClient.removeObject(io.minio.RemoveObjectArgs.builder()
                            .bucket(bucket).object(item.objectName()).build());
                    log.warn("兜底删掉无主对象（有类漏清了）：{}/{}", bucket, item.objectName());
                    removed++;
                }
            } catch (Exception ex) {
                // 兜底自己炸了不该让用例红 —— 但它**必须说出来**（否则"扫过了"与"扫失败了"分不清）
                log.warn("无主对象扫描失败（前缀 {}）：{}", objectPrefix, ex.getMessage());
            }
        }
        return removed;
    }

    /**
     * 这个对象**有没有主**。
     *
     * @return {@code true} = 有主（保留）· {@code false} = 无主（可删）·
     *         🔴 {@code null} = **判不了**（调用方必须保留 + 留痕，不许当成"无主"）
     */
    private static Boolean hasOwner(MinioPrefix prefix, String objectName, JdbcTemplate jdbc) {
        if (prefix.owner() == Owner.PATH_USER_ID) {
            Long userId = pathOwnerId(prefix.prefix(), objectName);
            if (userId == null) {
                log.warn("对象名解析不出归属人（**跳过不删**，可能对象名格式变了）：{}", objectName);
                return null;
            }
            // ⚠️ 判据是「**行在不在**」，**刻意不带 `deleted_at is null`**：
            //    `sys_user` 是**软删**（本仓 `#187` 追根时查实过），而软删的用户随时可能被恢复，
            //    他的审计导出仍然是他的；更关键的是**审计产物本身是合规材料**，
            //    不该因为账号停用就被兜底清掉。⇒ 只有**硬删**（行真没了）才算无主。
            Integer rows = jdbc.queryForObject(
                    "select count(*) from sys_user where id = ?", Integer.class, userId);
            return rows != null && rows > 0;
        }
        Integer rows = jdbc.queryForObject(
                "select count(*) from " + prefix.table() + " where " + prefix.column() + " = ?",
                Integer.class, objectName);
        return rows != null && rows > 0;
    }

    /** 默认下限：10 分钟。取值理由见 {@link #sweepOrphanMinioObjects} —— 只要盖过"上传中"那一窗口即可。 */
    public static final java.time.Duration DEFAULT_MIN_AGE = java.time.Duration.ofMinutes(10);

    /**
     * **归属从哪来**（`T-O11` 把前缀登记从"三元组"升级成"带来源的两类"）。
     *
     * <p>为什么需要这个区分：`O10` 的 javadoc 当时写着「没有归属列的对象**结构上判不了无主**」，
     * 而 `audit-export/` 被判进了那一类。**查完之后那句话对了一半** ——
     * 它确实没有归属**列**，但归属**在对象名的路径里**（见 {@link #PATH_OWNER}）。
     */
    private enum Owner {
        /** 归属是**库里的列** —— 判据 `select count(*) from {表} where {列} = 对象名`。 */
        COLUMN,
        /**
         * 归属是**对象名路径的第 2 段**（`audit-export/{userId}/…`）。
         *
         * <h4>为什么只能这样（`AuditExportService` 查出来的事实）</h4>
         * <p>它的对象名由 {@code AuditExportService#store} 拼成
         * {@code audit-export/{userId}/{时间戳}-audit.csv} —— **归属写进了路径**。
         * 而库里**没有任何一列**指向这个对象：{@code objectKey} 只活在任务体的
         * {@code payload} 里，而 {@code SysAsyncTaskRepository#find} 明写
         * 「⚠️ {@code result} **不持久化**」⇒ **任务一结束，全库就再没有它的落点**。
         * 所以「加一列」不是顺手加一列（要动 `AsyncTaskService` 的序列化契约 + 迁移），
         * 而**路径第 2 段是唯一还活着的归属信息**。
         */
        PATH_USER_ID,
    }

    /**
     * 一个受管前缀。
     *
     * <p>表名/列名都是**编译期常量**（不是用户输入），所以拼进 SQL 没有注入面。
     *
     * @param table  {@link Owner#COLUMN} 用归属表；{@link Owner#PATH_USER_ID} 固定 {@code sys_user}
     * @param column {@link Owner#COLUMN} 用归属列；{@link Owner#PATH_USER_ID} 固定 {@code id}
     */
    private record MinioPrefix(String prefix, Owner owner, String table, String column) {
    }

    /** `audit-export/` 的前缀常量（`AuditExportService#store` 用的是同一个字面量，见 {@code 判据 5} 的机器检查）。 */
    public static final String AUDIT_EXPORT_PREFIX = "audit-export/";

    /**
     * **受管前缀清单**。⚠️ 新增一个"系统产出的对象前缀"时，**必须在这里加一行**，
     * 否则它**结构上不在兜底的输入里** —— 那正是 `#149` 记的"闸覆盖了一类产物、
     * 另一类不在输入里"那个形态。
     *
     * <p>🔴 **只写 javadoc 拦不住**（本仓已复现：加 `images/` 时漏了那一行 ⇒ 一轮跑批留 **180 个孤儿**；
     * 而 `audit-export/` 更久 —— 从 `O10` 到 `T-O11` 一直不在清单里，实测攒到 **137 个**）。
     * ⇒ 现在有一条**机器检查**兜着它：见 {@code OrphanMinioSweepTest#everyKnownPrefixIsClassified}。
     */
    private static final List<MinioPrefix> MINIO_PREFIXES = List.of(
            new MinioPrefix("documents/", Owner.COLUMN, "sys_document", "storage_path"),
            new MinioPrefix("parsed/", Owner.COLUMN, "sys_document", "parse_path"),
            new MinioPrefix("chat-attachments/", Owner.COLUMN, "sys_chat_attachment", "object_key"),
            new MinioPrefix("avatars/", Owner.COLUMN, "sys_user", "avatar"),
            // `T-512` 步 2b 新增的第五个前缀 —— 图片块的原图。
            // 归属列取 `sys_chunk.object_key`（图片块那一行），不是 `sys_document`：
            // 图是**块级**产物，一篇文档删了但块还在的情况是存在的。
            new MinioPrefix("images/", Owner.COLUMN, "sys_chunk", "object_key"),
            // `T-O11` 新增的第六个 —— 也是**唯一一个归属不在列里**的（见 Owner#PATH_USER_ID）。
            new MinioPrefix(AUDIT_EXPORT_PREFIX, Owner.PATH_USER_ID, "sys_user", "id"));

    /**
     * 兜底**覆盖了哪些前缀**（供机器检查比对，见 {@code OrphanMinioSweepTest}）。
     *
     * <p>⚠️ 它只暴露 `prefix`，不暴露归属列 —— 用例要断言的是"**这个前缀有没有被表态**"，
     * 不是"它归属列叫什么"。前者是漏项的防线，后者会把实现细节冻进用例。
     */
    public static List<String> coveredMinioPrefixes() {
        return MINIO_PREFIXES.stream().map(MinioPrefix::prefix).toList();
    }

    /**
     * 从对象名里取**归属人 id**（只对 {@link Owner#PATH_USER_ID} 的前缀有意义）。
     *
     * <p>`audit-export/{userId}/{时间戳}-audit.csv` → 取第 2 段并转数字。
     *
     * <p>⚠️ 解析不出来返回 {@code null}，调用方**跳过不删** ——
     * 这与 {@code RetrievalTraceWriter#numericId} 是同一条口径：**不猜**。
     * 猜一个 id 出来的后果是**删错人的东西**，比少删一个严重得多。
     *
     * <p>包级可见是**故意的**：`OrphanMinioSweepTest` 要用它做「对象名 ↔ 解析器对得上」的机器检查。
     */
    static Long pathOwnerId(String prefix, String objectName) {
        if (objectName == null || !objectName.startsWith(prefix)) {
            return null;
        }
        String rest = objectName.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        try {
            return Long.valueOf(rest.substring(0, slash));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String placeholders(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> "?")
                .collect(Collectors.joining(","));
    }
}
