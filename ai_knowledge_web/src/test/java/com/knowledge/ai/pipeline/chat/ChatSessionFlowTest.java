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
