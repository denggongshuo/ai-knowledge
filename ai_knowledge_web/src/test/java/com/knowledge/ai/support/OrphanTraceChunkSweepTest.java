package com.knowledge.ai.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `#210` · **无主打点明细**的兜底：`sys_retrieval_trace_chunk` 里主行已经不在的那些行。
 *
 * <h3>为什么单独一个类（而不是并进 `OrphanMinioSweepTest`）</h3>
 * <p>两者都是"无主产物兜底"，但**判据的形态完全不同**：那边是 MinIO 对象 + 时间下限，
 * 这边是纯 SQL + "主行还在不在"。混在一个类里，失败信息会指向错误的机制。
 *
 * <h3>本类用的是**差分**，不是"改成预期值"</h3>
 * <p>判据 1 在同一轮里造**两条同龄、同前缀、同结构**的明细 —— 一条有主、一条没有 ——
 * 然后断言**一个死一个活**。这比"造个孤儿看它红不红"强：它证明的是
 * **"主表里有没有那一行"正是区分两者的那个东西**（把其它变量排干净了）。
 * 与 `OrphanMinioSweepTest#referencedObjectSurvivesAndOrphanDies` 同一手法。
 *
 * <h3>⚠️ 本类**不能**证明"那个竞态被修好了"</h3>
 * <p>根因（用例收尾的两条 DELETE 夹住了异步打点的提交窗口）写在
 * {@link ProbeCleanup#sweepOrphanTraceChunks} 的 javadoc 里，且**实测难复现**
 * （单独反复跑 {@code ChatStreamingFlowTest} 3 轮，每轮后孤儿都是 0）。
 * 本类证明的是**兜底本身对**：孤儿会被扫掉，而有主的一根毫毛都不动。
 */
class OrphanTraceChunkSweepTest extends ApiTestSupport {

    /** 探针 traceId 前缀 —— 本用例造的行**自己删**（兜底是兜底，不该被用例当成清理手段）。 */
    private static final String PROBE = "probe210";

    @AfterEach
    void removeMine() {
        jdbc.update("delete from sys_retrieval_trace_chunk where trace_id like ?", PROBE + "%");
        jdbc.update("delete from sys_retrieval_trace where trace_id like ?", PROBE + "%");
    }

    @Test
    @DisplayName("#210 判据 1/2：孤儿明细被扫掉 · **完整的一对必须留下**（同一轮的差分）")
    void orphanChunkDiesAndCompletePairSurvives() {
        String liveTrace = PROBE + "live-" + Long.toHexString(System.nanoTime());
        String orphanTrace = PROBE + "orphan-" + Long.toHexString(System.nanoTime());

        // ① 完整的一对：主行 + 明细行
        insertTrace(liveTrace);
        insertChunk(liveTrace);

        // ② 只有明细、没有主行 —— 这正是竞态留下的那个形状
        //    （`write()` 先 insert 主表、后 insert 明细，收尾的两条 DELETE 夹在提交窗口两侧）
        insertChunk(orphanTrace);

        int removed = ProbeCleanup.sweepOrphanTraceChunks(jdbc);

        assertThat(countChunks(orphanTrace))
                .as("主行不在 ⇒ 孤儿 ⇒ 必须删。⚠️ 这张表**既没有 user_id 也没有 kb_id** —— "
                        + "成了孤儿后没有任何归属维度认领得了它，`sweepOrphanKbArtifacts` 与 "
                        + "`cleanUsers` 结构上都够不着")
                .isZero();
        assertThat(countChunks(liveTrace))
                .as("🔴 **负向锚（本判据的重点）**：主行在 ⇒ 一根毫毛都不能碰。"
                        + "两条明细同龄、同前缀、同结构 —— 差异只有一个：**主表里有没有那一行**。"
                        + "所以这条同时证明了「主行在不在」正是区分两者的那个东西。"
                        + "⚠️ 它也是 javadoc 那句「不会误删在途数据」的实证：`write()` 先主后明细、"
                        + "同一个事务 ⇒ 不存在「明细已可见、主行还没可见」的中间态")
                .isEqualTo(1);
        assertThat(removed)
                .as("**> 0 是信号，不是正常现象**（其余孤儿可能来自别的用例类，故用 ≥1）")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("#210 判据 3：没有孤儿时**什么都不删** —— 兜底不能靠\"删得狠\"来显得有效")
    void cleanDbIsLeftUntouched() {
        String liveTrace = PROBE + "only-" + Long.toHexString(System.nanoTime());
        insertTrace(liveTrace);
        insertChunk(liveTrace);
        insertChunk(liveTrace);     // 同一主行两条明细

        int before = countChunks(liveTrace);
        ProbeCleanup.sweepOrphanTraceChunks(jdbc);

        assertThat(countChunks(liveTrace))
                .as("全都有主 ⇒ 一条都不该少。**「没有孤儿」这件事本身不该产生任何删除动作** —— "
                        + "否则判据 1 的「删掉了」就分不清是兜底对了，还是它删得太狠")
                .isEqualTo(before)
                .isEqualTo(2);
    }

    @Test
    @DisplayName("#210 判据 4：兜底**真的接进了基座收尾**（不是「方法写好了但没人调」）")
    void baseTeardownActuallyCallsTheSweep() {
        String orphan = PROBE + "wired-" + Long.toHexString(System.nanoTime());
        insertChunk(orphan);

        // 直接调**基座那个 @AfterEach 方法本身**（同包可见）——
        // 判据 1~3 只证明"这个方法是对的"，证明不了"JUnit 真的会调它"。
        // ⚠️ 少了这一条，把 `ProbeCleanup.sweepOrphanTraceChunks(...)` 那一行删掉，
        //    整个测试类**照样全绿** —— 那就是"方法在、但没人调"的静默失效。
        sweepOrphanKbArtifacts();

        assertThat(countChunks(orphan))
                .as("基座收尾跑完，孤儿必须没了 ⇒ 证明兜底**挂在必走路径上**，不是一份没人调的实现")
                .isZero();
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 造一条主表行。
     *
     * <p>⚠️ `user_id` 用种子超管、`session_id` 用 0：本表**没有任何外键**
     * （`#210` 已查实：`V22` 建表时就没建），所以这两个值不需要真的存在。
     * 用超管是为了**不引入一个会被 `cleanUsers` 顺手删掉的探针账号** ——
     * 那会让"主行为什么还在"变得难以推理。
     */
    private void insertTrace(String traceId) {
        jdbc.update("insert into sys_retrieval_trace (trace_id, user_id, session_id, query_text) "
                + "values (?, 1, 0, 'probe210 的探针问句（不参与任何业务）')", traceId);
    }

    private void insertChunk(String traceId) {
        jdbc.update("insert into sys_retrieval_trace_chunk "
                + "(trace_id, rank_no, chunk_id, doc_id, score, source) values (?, 1, 1, 1, 0.9, 'vector')",
                traceId);
    }

    /** 独立观测点：直接数库，不看删除命令的返回值。 */
    private int countChunks(String traceId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from sys_retrieval_trace_chunk where trace_id = ?", Integer.class, traceId);
        return n == null ? 0 : n;
    }
}
