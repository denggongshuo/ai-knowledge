package com.knowledge.ai.pipeline.chat;

import com.knowledge.ai.pipeline.retrieve.RetrievalFlowSupport;
import com.knowledge.ai.service.ChatSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话与模型绑定（`G5-10`）—— 与 {@link ChatStreamFlowTest} 同一个 Spring 上下文
 * （继承同一个基类即可复用，省一次容器启动）。
 *
 * <h3>为什么 {@code modelId} 的断言只到"绑定"为止</h3>
 * <p>本批 {@code modelId} **不驱动模型选择**：前端传数字而后端
 * {@code ModelConfigSpec#id} 是字符串，二者没有映射表（`sys_model_config` 0 行）。
 * 所以这里验的是「绑定被记下来 + 不一致时开新会话且原会话保留」这一跳，
 * 而"实际用了哪个模型"由 `done` 事件如实回带（见 {@code ChatStreamFlowTest}）。
 * 让绑定真正生效归 `T-803`。
 */
class ChatSessionFlowTest extends RetrievalFlowSupport {

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private ChatWireMapper wireMapper;

    private long sessionFloor;

    @BeforeEach
    void m08SetUp() {
        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from sys_chat_session", Long.class);
        sessionFloor = max == null ? 0L : max;
    }

    @AfterEach
    void m08TearDown() {
        // `T-804` 起本类会造打点明细行 —— 按 trace_id 前缀自清（该表没有 kb/doc 水位线）
        jdbc.update("delete from sys_retrieval_trace_chunk where trace_id like 'hist-cite-%' "
                + "or trace_id like 'legacy-cite-%'");
        jdbc.update("delete from sys_chat_message where session_id > ?", sessionFloor);
        jdbc.update("delete from sys_chat_session where id > ?", sessionFloor);
        cleanProbeData();
    }

    @Test
    @DisplayName("不传 sessionId → 新会话；modelId 被记在会话上")
    void withoutSessionIdOpensNewSession() {
        long sessionId = chatSessionService.resolve(reader().getUserId(), null, 7L);

        var row = jdbc.queryForMap("select * from sys_chat_session where id = ?", sessionId);
        assertThat(((Number) row.get("model_id")).longValue()).isEqualTo(7L);
        assertThat(row.get("deleted_at")).isNull();
    }

    @Test
    @DisplayName("G5-10：modelId 与绑定一致 → 继续用原会话")
    void sameModelKeepsTheSession() {
        long first = chatSessionService.resolve(reader().getUserId(), null, 7L);

        long second = chatSessionService.resolve(reader().getUserId(), first, 7L);

        assertThat(second).as("绑定一致就该继续，不该另起一个").isEqualTo(first);
    }

    @Test
    @DisplayName("G5-10：modelId 与绑定不一致 → 开新会话，**原会话保留**")
    void differentModelOpensNewSessionAndKeepsTheOld() {
        long first = chatSessionService.resolve(reader().getUserId(), null, 7L);

        long second = chatSessionService.resolve(reader().getUserId(), first, 9L);

        assertThat(second).isNotEqualTo(first);
        var old = jdbc.queryForMap("select * from sys_chat_session where id = ?", first);
        assertThat(old.get("deleted_at"))
                .as("原会话必须保留 —— 它之前的答案属于原模型，改绑定会让那些答案失去归因")
                .isNull();
        var fresh = jdbc.queryForMap("select * from sys_chat_session where id = ?", second);
        assertThat(((Number) fresh.get("model_id")).longValue()).isEqualTo(9L);
    }

    @Test
    @DisplayName("会话不可用（不存在 / 不是自己的 / 已删）→ 开新会话而不是让问答失败")
    void unusableSessionFallsBackToNewOne() {
        long opened = chatSessionService.resolve(reader().getUserId(), 999_999_999L, null);

        assertThat(opened).isGreaterThan(sessionFloor);
    }

    @Test
    @DisplayName("标题取首个提问的截断，且只有第一次会写")
    void titleComesFromTheFirstQuestion() {
        long sessionId = chatSessionService.resolve(reader().getUserId(), null, null);

        chatSessionService.appendUser(sessionId, "型号 A-100 的单价是多少");
        chatSessionService.appendUser(sessionId, "那 A-200 呢");

        var row = jdbc.queryForMap("select * from sys_chat_session where id = ?", sessionId);
        assertThat(row.get("title")).isEqualTo("型号 A-100 的单价是多少");
    }

    // ==================== T-804 · 历史引用的口径 ====================

    /**
     * 历史 `citations` 只回**被引用过**的那几条（`T-804`）。
     *
     * <h3>为什么要造"三条里只引一条"而不是"只有一条"</h3>
     * <p>只造一条的话，`where cited = 1` 与"不过滤"两种写法**结果相同** —— 判据是空的。
     * 造三条（1 被引用 / 0 明确没被引用 / null 没采集）才能把三种写法分开。
     */
    @Test
    @DisplayName("T-804：历史 citations 只回 `cited = 1` 的行（逐条对得上，不是数量对得上）")
    void historyCitationsOnlyReturnReallyCitedRows() {
        kbId = insertKb("M06C_HISTCITE");
        long docId = insertDocWithChunks("报价单", "型号 A-100 单价 1800 元");
        long chunkId = jdbc.queryForObject(
                "select id from sys_chunk where doc_id = ? limit 1", Long.class, docId);

        long sessionId = chatSessionService.resolve(reader().getUserId(), null, null);
        chatSessionService.appendUser(sessionId, "型号 A-100 的单价是多少");
        String traceId = "hist-cite-" + System.nanoTime();
        long messageId = chatSessionService.appendAssistant(sessionId, "1800 元[1]。",
                null, null, null, "stop", traceId, false);

        insertChunk(traceId, 1, chunkId, docId, 1);
        insertChunk(traceId, 2, chunkId, docId, 0);
        insertChunk(traceId, 3, chunkId, docId, null);

        var page = chatSessionService.messages(reader().getUserId(), sessionId);
        var assistant = page.list().stream().filter((m) -> m.role().equals("assistant")).findFirst().orElseThrow();

        assertThat(assistant.citations())
                .as("🔴 三种写法在这条用例上会给出不同的结果：不过滤 ⇒ 3 条；"
                        + "`cited <> 0` ⇒ 2 条；只有 `cited = 1` ⇒ 1 条")
                .hasSize(1);
        assertThat(assistant.citations().getFirst().chunkId()).isEqualTo(chunkId);
        assertThat(assistant.citations().getFirst().rankNo())
                .as("名次 1 那条被引用")
                .isEqualTo(1);
        assertThat(messageId).isPositive();
    }

    /**
     * 旧数据（`cited` 全 `null`，`T-804` 之前落的行）**退回全部**。
     *
     * <h3>为什么不是"退回空"</h3>
     * <p>`cited` 全 `null` 的语义是**没采集**，不是"都没被引用"。退回空会让历史会话的
     * 引用卡片**整块消失** —— 而用户看不出"这是历史数据的限制"还是"那次真的没引用"。
     * 退回全部是**旧口径**（= 喂给模型的那批），比空更接近真相。
     */
    @Test
    @DisplayName("T-804：`cited` 全 null 的旧数据退回全部（不是退回空）")
    void legacyRowsFallBackToAllChunks() {
        kbId = insertKb("M06C_LEGACY");
        long docId = insertDocWithChunks("报价单", "型号 A-100 单价 1800 元");
        long chunkId = jdbc.queryForObject(
                "select id from sys_chunk where doc_id = ? limit 1", Long.class, docId);

        long sessionId = chatSessionService.resolve(reader().getUserId(), null, null);
        chatSessionService.appendUser(sessionId, "型号 A-100 的单价是多少");
        String traceId = "legacy-cite-" + System.nanoTime();
        chatSessionService.appendAssistant(sessionId, "1800 元。", null, null, null, "stop", traceId, false);

        // 三条**全 null** —— 模拟本批之前落的行
        insertChunk(traceId, 1, chunkId, docId, null);
        insertChunk(traceId, 2, chunkId, docId, null);

        var page = chatSessionService.messages(reader().getUserId(), sessionId);
        var assistant = page.list().stream().filter((m) -> m.role().equals("assistant")).findFirst().orElseThrow();

        assertThat(assistant.citations())
                .as("全 null = 没采集 ⇒ 退回**全部**。退回空会让历史会话的引用卡片整块消失，"
                        + "而用户分不出那是「历史限制」还是「那次真没引用」")
                .hasSize(2);
    }

    /** 插一条打点明细；`cited` 三态都要能造。 */
    private void insertChunk(String traceId, int rankNo, long chunkId, long docId, Integer cited) {
        jdbc.update("insert into sys_retrieval_trace_chunk "
                        + "(trace_id, rank_no, chunk_id, doc_id, score, source, cited) "
                        + "values (?, ?, ?, ?, 0.5, 'vector', ?)",
                traceId, rankNo, chunkId, docId, cited);
    }

    @Test
    @DisplayName("文档标题能按 docId 批量查回（引用卡片要的是文档标题，不是标题路径）")
    void docTitlesAreResolvedByDocId() {
        kbId = insertKb("M08A_TITLE");
        long docId = insertDocWithChunks("季度报价单（2026Q3）", "型号 A-100 单价 1800 元");

        var titles = wireMapper.loadDocTitles(List.of(chunkOf(docId)));

        assertThat(titles).containsEntry(docId, "季度报价单（2026Q3）");
    }

    private static com.knowledge.ai.pipeline.retrieve.RetrievedChunk chunkOf(long docId) {
        return new com.knowledge.ai.pipeline.retrieve.RetrievedChunk("1001", docId, 1L, "PARAGRAPH",
                "标题", "正文", 0.9, com.knowledge.ai.pipeline.retrieve.RetrievedChunk.ScoreKind.RERANK,
                1, 1, 0.9, 0.9, java.util.Map.of(), false, null);
    }
}
