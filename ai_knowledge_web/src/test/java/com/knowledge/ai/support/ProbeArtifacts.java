package com.knowledge.ai.support;

import java.util.Map;

/**
 * **探针产物的登记表**（`S5` 测试可靠性修复批）—— Java 用例侧的"单一登记表"。
 *
 * <h3>为什么需要它：`#130` 的形状</h3>
 * <p>「新增一个持久化产物 → 要在 N 处加清理」这条约定，**光靠注释提醒一定会漏**。
 * 本仓此前已经复现过 <b>四次</b>（`sys_chunk` 表 → Milvus 向量 → 按代分区 → `sys_async_task`），
 * 而「产物认识、归属维度不认识」还有两个方向（`#109` 后端、`#130` 用例侧）。
 *
 * <p>漏了会怎样 —— **实测**（本批开工前跑一轮全量，前后各查一次库）：
 * <ul>
 *   <li>{@code sys_async_task} <b>+76 行/轮</b> —— {@code RetrievalFlowSupport#cleanProbeData()}
 *       <b>根本没删这张表</b>；</li>
 *   <li>{@code sys_login_log} <b>+242 行/轮</b> —— {@code ApiTestSupport#deleteUser} 只删
 *       {@code sys_user_role} 与 {@code sys_user}，**测试侧没有任何一个类删过登录日志**。</li>
 * </ul>
 * 两处都是"少写了一条 delete"，而**没有任何机器检查会发现**。本类就是那道机器检查。
 *
 * <h3>与脚本侧的关系</h3>
 * <p>脚本侧（{@code ai_knowledge_app/scripts/lib/probe-teardown.mjs}）有同样的一张表，
 * 那是 `S4-D2` 建的。**两侧刻意不合并**（一个 JS 一个 Java，跨语言共用只有"起子进程"或
 * "复制一份"，都比现在更糟），但**登记集合要对账** —— 见
 * {@link ProbeArtifactCoverageTest} 的「两侧对账」判据。
 *
 * @see ProbeCleanup 清理实现
 * @see ProbeArtifactCoverageTest 覆盖率闸（漏登记就点名）
 */
public final class ProbeArtifacts {

    /**
     * 「探针会写脏、且**已有清理**」的表 —— **新增持久化产物时必须在这里加一行**。
     *
     * <p>值写的是**怎么清的**，不是"已登记"三个字 —— 没理由的登记等于把闸关掉。
     */
    public static final Map<String, String> REGISTERED = Map.ofEntries(
            Map.entry("sys_kb", "探针库主表。按 code 删（它自己没有 kb_id，是别人指向它）"),
            Map.entry("sys_document", "文档行。按 kb_id 删，**必须先于 sys_kb**"),
            Map.entry("sys_chunk", "分片。按 kb_id 删"),
            Map.entry("sys_parse_issue", "逐页问题。按 kb_id 删"),
            Map.entry("sys_kb_grant", "知识库授权。按 kb_id 删"),
            Map.entry("sys_async_task",
                    "异步任务。按 kb_id 删。⚠️ S5 补 —— 此前**根本没有清理**，实测 +76 行/轮；"
                            + "而漏掉它的现象很隐蔽：任务行都 DONE 了，只有 kb 行没了才看得出是孤儿"),
            Map.entry("sys_user", "账号主表。按 username 删，**必须在最后**"),
            Map.entry("sys_user_role", "用户角色关联。按 user_id 删，先于 sys_user（避开外键）"),
            Map.entry("sys_login_log",
                    "登录日志。按 user_id 删。⚠️ S5 补 —— 此前**测试侧一个类都没删过**，实测 +242 行/轮。"
                            + "⚠️ 与生产口径不冲突：生产里「用户删了日志要留」是**合规要求**，"
                            + "而这里删的是**探针账号**的日志 —— 它从来不是真实风控证据"),
            Map.entry("sys_chat_session", "问答会话。按 user_id 删（探针账号的）；⚠️ 用例若用**种子超管**提问，走会话水位线，见 ChatStreamFlowTest"),
            Map.entry("sys_chat_attachment",
                    "问答图片附件（T-805）。按 user_id 删 —— **它有归属列**，不顺着会话删："
                            + "预上传发生在会话之前，附件可以先于会话存在。"
                            + "⚠️ 它同时是一个 MinIO 对象（chat-attachments/ 前缀）：本表只登记**行**，"
                            + "桶里的对象不在两侧清理器的能力范围内（与 documents/ 同一条取舍，靠生命周期规则）"),
            Map.entry("sys_chat_message",
                    "问答消息。它**没有 user_id**，按 session_id 顺着父表删，**先于 sys_chat_session**。"
                            + "⚠️ 这一行是**两侧对账**逼出来的（脚本侧登记了它、Java 侧漏了）："
                            + "清理器其实一直在删它，但登记表里没有 —— 而它没有归属列，"
                            + "覆盖率闸**扫不到**，只有拿两份登记表取差集才看得出来"),
            Map.entry("sys_retrieval_trace", "检索打点。按 user_id 删（同上）"),
            Map.entry("sys_retrieval_trace_chunk", "打点明细。它没有 user_id，按 trace_id 顺着父表删，**先于 sys_retrieval_trace**")
    );

    /**
     * **显式豁免**：确实会被探针写脏、但**有意不清**的表。
     *
     * <p>豁免必须写理由 —— 没理由的豁免等于把闸关掉。
     *
     * <p>⚠️ 当前**为空**：脚本侧那条 `sys_audit_log` 豁免在这里用不上，
     * 因为 `sys_audit_log` **没有** `kb_id`/`doc_id`/`user_id` 三列（它的归属列叫 `operator`），
     * 本来就进不了闸的扫描范围。留着这个 Map 是为了让"豁免"这件事**有地方写**，
     * 而不是某天有人在闸里加个 `if` 绕过。
     */
    public static final Map<String, String> EXEMPT = Map.of();

    private ProbeArtifacts() {
    }
}
