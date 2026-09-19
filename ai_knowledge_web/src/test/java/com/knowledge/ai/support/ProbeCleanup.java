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
     * <p><b>只覆盖"有归属列的前缀"。</b>没有归属列的对象**结构上判不了无主** ——
     * `audit-export/` 就是（`#78` 已登记：它没有归属列）。
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
        for (String[] prefix : MINIO_PREFIXES) {
            String objectPrefix = prefix[0];
            String table = prefix[1];
            String column = prefix[2];
            try {
                for (io.minio.Result<io.minio.messages.Item> result : minioClient.listObjects(
                        io.minio.ListObjectsArgs.builder()
                                .bucket(bucket).prefix(objectPrefix).recursive(true).build())) {
                    io.minio.messages.Item item = result.get();
                    // 太新 ⇒ 可能正在上传（先落桶后落行）—— **跳过，不删**
                    if (item.lastModified().toInstant().isAfter(cutoff)) {
                        continue;
                    }
                    Integer rows = jdbc.queryForObject(
                            "select count(*) from " + table + " where " + column + " = ?",
                            Integer.class, item.objectName());
                    if (rows != null && rows > 0) {
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

    /** 默认下限：10 分钟。取值理由见 {@link #sweepOrphanMinioObjects} —— 只要盖过"上传中"那一窗口即可。 */
    public static final java.time.Duration DEFAULT_MIN_AGE = java.time.Duration.ofMinutes(10);

    /**
     * 四个前缀 → **归属列**。表名/列名都是**编译期常量**（不是用户输入），所以拼进 SQL 没有注入面。
     *
     * <p>⚠️ 新增一个"系统产出的对象前缀"时，**必须在这里加一行**，否则它**结构上不在兜底的输入里**
     * —— 那正是 `#149` 记的"闸覆盖了一类产物、另一类不在输入里"那个形态。
     */
    private static final String[][] MINIO_PREFIXES = {
            {"documents/", "sys_document", "storage_path"},
            {"parsed/", "sys_document", "parse_path"},
            {"chat-attachments/", "sys_chat_attachment", "object_key"},
            {"avatars/", "sys_user", "avatar"},
            // `T-512` 步 2b 新增的第五个前缀 —— 图片块的原图。
            // ⚠️ 上面那句 javadoc 说的"新增前缀必须在这里加一行"，**本批真的被抓了一次**：
            //    加 `images/` 时漏了这一行，于是那一轮全量跑批在桶里留了 **180 个孤儿**
            //    （对照 `documents/` 是 0 —— 因为它在清单里）。
            //    归属列取 `sys_chunk.object_key`（图片块那一行），不是 `sys_document`：
            //    图是**块级**产物，一篇文档删了但块还在的情况是存在的。
            {"images/", "sys_chunk", "object_key"},
    };

    private static String placeholders(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> "?")
                .collect(Collectors.joining(","));
    }
}
