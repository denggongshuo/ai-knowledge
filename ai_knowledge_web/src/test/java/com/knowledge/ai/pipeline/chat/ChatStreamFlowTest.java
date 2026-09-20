package com.knowledge.ai.pipeline.chat;

import com.knowledge.ai.pipeline.retrieve.RetrievalFlowSupport;
import com.knowledge.ai.pipeline.retrieve.RetrievalTestStubs;
import com.knowledge.ai.service.ChatStreamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 问答全链路（`T-800` / `T-801` / `T-708`）—— 真 MySQL、真 Milvus、真检索；
 * **只替模型那一层**（{@link RetrievalTestStubs#streamingChatCallerStub}）。
 *
 * <h3>为什么必须接在 {@link RetrievalFlowSupport} 上</h3>
 * <p>本批要验的不是"编排逻辑对不对"，而是"**落库了没有**"：
 * `G5-12`（6 阶段耗时齐全）、`G5-13`（每次提问都落 Top1，无遗漏）、
 * `G5-11`（空召回不调模型）—— 这三条只有在真库真集合上才谈得上。
 * 替掉检索的话，验的就成了替身自己的行为。
 *
 * <h3>⚠️ 收尾必须自己删 chat/trace 四张表</h3>
 * <p>基类的 {@code cleanProbeData()} 按 {@code kb_id} / {@code doc_id} 认产物，
 * 而打点与会话**都没有这两个坐标**（打点只有 `user_id` + `session_id`）
 * —— 它认不出来。这正是 `汇总.md` §3 的 `#130` 那族问题的形状：
 * 「产物认识、归属维度不认识」。本类用**自增 id 水位线**兜住自己造的那一批，
 * 并把"这四张表要进残留自检"登记进计划 §9（`#130` 的批次统一收口）。
 */
class ChatStreamFlowTest extends RetrievalFlowSupport {

    @Autowired
    private ChatStreamService chatStreamService;

    /** 本用例开始前 `sys_chat_session` 的最大 id —— 之后新增的全是本轮造的。 */
    private long sessionFloor;

    /**
     * 本用例开始前 `sys_qa_miss` 的最大 id（`T-A00`）。
     *
     * <p>⚠️ **必须自己删，不能靠无主兜底**：本类的提问人是 `reader()` = **种子超管**
     * （`userId = 1`），那一行删不掉 —— 超管当然还在。这正是
     * `ProbeCleanup#sweepOrphanMisses` javadoc 里说的"射程之外的那一半"。
     */
    private long missFloor;

    @BeforeEach
    void m08SetUp() {
        RetrievalTestStubs.resetStream();
        // 重排替身是静态可换的，别的用例（或本类上一个用例）装的必须复位，
        // 否则"未命中"那条用例装的低分替身会漏给它后面所有用例
        RetrievalTestStubs.resetRerank();
        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from sys_chat_session", Long.class);
        sessionFloor = max == null ? 0L : max;
        Long missMax = jdbc.queryForObject("select coalesce(max(id), 0) from sys_qa_miss", Long.class);
        missFloor = missMax == null ? 0L : missMax;
    }

    @AfterEach
    void m08TearDown() {
        // 先删子表（按 trace_id 关联的明细），再删主表；顺序反了会留下孤儿明细
        jdbc.update("delete from sys_retrieval_trace_chunk where trace_id in "
                + "(select trace_id from sys_retrieval_trace where session_id > ?)", sessionFloor);
        jdbc.update("delete from sys_retrieval_trace where session_id > ?", sessionFloor);
        jdbc.update("delete from sys_chat_message where session_id > ?", sessionFloor);
        jdbc.update("delete from sys_chat_session where id > ?", sessionFloor);
        // `T-A00` 的池子行（本类只有 `missIsSunkIntoPool` 会造）—— 提问人是种子超管，
        // 无主兜底够不着它，见 `missFloor` 的注释
        jdbc.update("delete from sys_qa_miss where id > ?", missFloor);
        // 基类的探针清理**不会自动跑**（它没有 @AfterEach），每个用例类自己调 —— 与
        // RetrievalServiceTest / TableLookupFlowTest 等一致
        cleanProbeData();
    }

    // ==================== 有结果：完整事件序列 + 落库 ====================

    @Test
    @DisplayName("G5-7/G5-8：首包是 retrieval，序列为 retrieval→delta*→retrieval→done")
    void eventSequenceStartsWithRetrieval() {
        kbId = insertKb("M08A_SEQ");
        long docId = insertDocWithChunks("报价单", "型号 A-100 单价 1800 元", "型号 A-200 单价 2400 元");
        seedVectors(docId, 1);
        awaitVectorsVisible("A-100 单价", 2);

        RecordingSink sink = new RecordingSink();
        chatStreamService.chat(reader(), ask("型号 A-100 的单价是多少"), sink);

        assertThat(sink.eventNames).isNotEmpty();
        assertThat(sink.eventNames.getFirst())
                .as("不带图片时首包必须是 retrieval（G5-7）")
                .isEqualTo("retrieval");
        assertThat(sink.eventNames.getLast()).isEqualTo("done");
        assertThat(sink.eventNames).filteredOn("retrieval"::equals)
                .as("retrieval 发两次：首包 + 终包（终包补上生成耗时）")
                .hasSize(2);
        assertThat(sink.deltas)
                .as("替身按句吐字，delta 应当是**多次**而不是一次给完")
                .hasSizeGreaterThan(1);
        assertThat(sink.done).isNotNull();
    }

    @Test
    @DisplayName("g5-12/G5-13：打点行落库，6 阶段耗时齐全且 Top1 非空")
    void traceRowIsPersistedWithAllStages() {
        kbId = insertKb("M08A_TRACE");
        long docId = insertDocWithChunks("报价单", "型号 A-100 单价 1800 元", "型号 A-200 单价 2400 元");
        seedVectors(docId, 1);
        awaitVectorsVisible("A-100 单价", 2);

        RecordingSink sink = new RecordingSink();
        chatStreamService.chat(reader(), ask("型号 A-100 的单价是多少"), sink);

        var row = jdbc.queryForMap("select * from sys_retrieval_trace order by id desc limit 1");
        assertThat(row.get("embed_millis")).as("Embedding 段有真值（拆了计时器才有）").isNotNull();
        assertThat(row.get("vector_search_millis")).isNotNull();
        assertThat(row.get("bm25_recall_millis")).isNotNull();
        assertThat(row.get("rerank_millis")).isNotNull();
        assertThat(row.get("llm_generation_millis"))
                .as("真调了模型，生成耗时必须有值")
                .isNotNull();
        assertThat(row.get("total_millis")).isNotNull();
        assertThat(((Number) row.get("chunk_count")).intValue()).isGreaterThan(0);
        assertThat(row.get("top1_score"))
                .as("G5-13 / I-14：Top1 相似度必须落")
                .isNotNull();
        assertThat(intOf(row.get("miss_flag"))).isZero();
        // 分片明细：与 chunk_count 对得上
        int detail = jdbc.queryForObject(
                "select count(*) from sys_retrieval_trace_chunk where trace_id = ?",
                Integer.class, row.get("trace_id"));
        assertThat(detail).isEqualTo(((Number) row.get("chunk_count")).intValue());
    }

    @Test
    @DisplayName("T-801：done.messageId 是**真行 id**，且能查到那条 assistant 消息")
    void doneMessageIdIsARealRow() {
        kbId = insertKb("M08A_MSG");
        long docId = insertDocWithChunks("报价单", "型号 A-100 单价 1800 元");
        seedVectors(docId, 1);
        awaitVectorsVisible("A-100 单价", 1);

        RecordingSink sink = new RecordingSink();
        chatStreamService.chat(reader(), ask("型号 A-100 的单价是多少"), sink);

        long messageId = Long.parseLong(sink.done.messageId());
        var row = jdbc.queryForMap("select * from sys_chat_message where id = ?", messageId);
        assertThat(row.get("role")).isEqualTo("assistant");
        assertThat(row.get("model_id")).as("T-808 的 used_model_id 落点").isNotNull();
        assertThat(row.get("model_name")).isEqualTo("stub-chat-model");
        assertThat(row.get("trace_id")).isNotNull();
        assertThat(String.valueOf(row.get("content"))).isEqualTo(String.join("", sink.deltas));
        // 同一会话里 user + assistant 各一条
        int count = jdbc.queryForObject("select count(*) from sys_chat_message where session_id = ?",
                Integer.class, row.get("session_id"));
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("T-707：Embedding 与向量检索是**分开计时**的两段（弱断言 isNotNull 抓不到这个）")
    void embedAndVectorSearchAreTimedSeparately() {
        kbId = insertKb("M08A_SPLIT");
        long docId = insertDocWithChunks("报价单", "型号 A-100 单价 1800 元");
        seedVectors(docId, 1);
        awaitVectorsVisible("A-100 单价", 1);

        // 只在**查询侧**嵌入时生效（seedVectors 直接走 hashedVector，不经过替身），
        // 且必须在 awaitVectorsVisible 之后设 —— 它自己也会调嵌入
        embedDelayMillis = 300L;
        RecordingSink sink = new RecordingSink();
        chatStreamService.chat(reader(), ask("型号 A-100 的单价是多少"), sink);

        var row = jdbc.queryForMap("select * from sys_retrieval_trace order by id desc limit 1");
        assertThat(((Number) row.get("embed_millis")).longValue())
                .as("嵌入替身睡了 300ms —— 这一段必须是真值。⚠️ 若两段共用一个计时器，"
                        + "这里会是 0，而 isNotNull 抓不到（0 也不是 null）")
                .isGreaterThanOrEqualTo(250L);
        assertThat(((Number) row.get("recall_millis")).longValue())
                .as("召回墙钟必然覆盖嵌入那一段")
                .isGreaterThanOrEqualTo(((Number) row.get("embed_millis")).longValue());
    }

    // ==================== T-708：空召回与未命中必须分流 ====================

    @Test
    @DisplayName("G5-11 前半：空召回**不调大模型**，直接兜底话术（负向锚：去掉分流必红）")
    void emptyRecallNeverCallsTheModel() {
        kbId = insertKb("M08A_EMPTY");
        // 什么都不建：可见范围内没有文档 —— 这是"真没有"那一支
        RecordingSink sink = new RecordingSink();
        chatStreamService.chat(reader(), ask("型号 A-100 的单价是多少"), sink);

        assertThat(RetrievalTestStubs.STREAM_CALLS.get())
                .as("空召回**一次也不能调模型** —— 让它在没有资料时开口就是请它编")
                .isZero();
        assertThat(sink.deltas).hasSize(1);
        assertThat(sink.deltas.getFirst())
                .as("话术要说清'还没有'与'该怎么办'，不能只说'没找到'")
                .contains("还没有可检索的文档");
        assertThat(sink.done).isNotNull();
        assertThat(sink.done.finishReason()).isEqualTo("empty_recall");
    }

    @Test
    @DisplayName("#114：文档在、但索引还没可见时，话术与「真没有」不是同一句")
    void emptyRecallDistinguishesNotYetFromNothing() {
        kbId = insertKb("M08A_NOTYET");
        // 建了文档与分片，但**故意不写向量** —— 可见文档数 > 0 而召回为空
        insertDocWithChunks("报价单", "型号 A-100 单价 1800 元");

        RecordingSink sink = new RecordingSink();
        chatStreamService.chat(reader(), ask("型号 A-100 的单价是多少"), sink);

        assertThat(RetrievalTestStubs.STREAM_CALLS.get()).isZero();
        assertThat(sink.deltas.getFirst())
                .as("这一支必须提'稍后重试'，因为成因是索引还没建好/还没可见")
                .contains("稍后重试")
                .doesNotContain("还没有可检索的文档");
    }

    @Test
    @DisplayName("G5-11 后半：未命中（分低）**照常调模型**，只是 miss_flag 落库")
    void missStillCallsTheModel() {
        kbId = insertKb("M08A_MISS");
        long docId = insertDocWithChunks("报价单", "型号 A-100 单价 1800 元");
        seedVectors(docId, 1);
        awaitVectorsVisible("A-100 单价", 1);
        // 装一个"分数远低于 missThreshold(0.35)"的重排替身 —— 这才是"未命中"的成因
        RetrievalTestStubs.rerankStub = lowScoreRerank();

        RecordingSink sink = new RecordingSink();
        chatStreamService.chat(reader(), ask("型号 A-100 的单价是多少"), sink);

        assertThat(RetrievalTestStubs.STREAM_CALLS.get())
                .as("未命中不等于没有 —— 把它也拦掉会把大量本可回答的问题变成兜底话术")
                .isEqualTo(1);
        var row = jdbc.queryForMap("select * from sys_retrieval_trace order by id desc limit 1");
        assertThat(intOf(row.get("miss_flag")))
                .as("miss_flag 是 G5-11 后半的落点")
                .isEqualTo(1);
        assertThat(intOf(row.get("rerank_applied"))).isEqualTo(1);
    }

    // ==================== T-A00 · 未命中沉淀 ====================

    @Test
    @DisplayName("T-A00：未命中沉淀进池子；同一问法两次提问合成一行，且与打点行**对得上**")
    void missIsSunkIntoPool() {
        kbId = insertKb("M08A_POOL");
        long docId = insertDocWithChunks("报价单", "型号 A-100 单价 1800 元");
        seedVectors(docId, 1);
        awaitVectorsVisible("A-100 单价", 1);
        RetrievalTestStubs.rerankStub = lowScoreRerank();

        // 同一句话问两次，**标点不同** —— 归一化的那半边就靠这一步验
        chatStreamService.chat(reader(), ask("型号 A-100 的单价是多少"), new RecordingSink());
        chatStreamService.chat(reader(), ask("型号 A-100 的单价是多少！！"), new RecordingSink());

        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "select * from sys_qa_miss where id > ?", missFloor);
        assertThat(rows)
                .as("两次提问必须合成**一行**（`G8-2`：不是把每次提问当成一行）")
                .hasSize(1);
        assertThat(((Number) rows.getFirst().get("ask_count")).intValue()).isEqualTo(2);

        // 🔴 对账：池子是打点的**派生**，两个数说的必须是同一件事。
        //    ⚠️ 本条的强度全在"同一个窗口"上：池子按 id 水位线、打点按 session 水位线，
        //    两边都只圈本轮自己造的那些。少了任何一边的窗口，它就退化成"比两个全局数"
        //    —— 而全局数会因为别的用例而恒不相等，于是这条判据只能被删掉。
        Long asked = jdbc.queryForObject(
                "select coalesce(sum(ask_count), 0) from sys_qa_miss where id > ?", Long.class, missFloor);
        Long missed = jdbc.queryForObject(
                "select count(*) from sys_retrieval_trace where session_id > ? and miss_flag = 1",
                Long.class, sessionFloor);
        assertThat(asked)
                .as("池子的提问次数合计必须等于 miss_flag=1 的打点行数 —— 对不上说明有一边漏了")
                .isEqualTo(2L)
                .isEqualTo(missed);

        // 池子里的"最高相似度"与打点里的 Top1 是**同一个算法**算出来的
        Double poolTop = jdbc.queryForObject(
                "select max_top_score from sys_qa_miss where id > ?", Double.class, missFloor);
        Double traceTop = jdbc.queryForObject(
                "select max(top1_score) from sys_retrieval_trace where session_id > ? and miss_flag = 1",
                Double.class, sessionFloor);
        assertThat(poolTop)
                .as("两处对不上而两边看着都合理 —— 这正是要钉住它的理由")
                .isEqualTo(traceTop);
    }

    // ==================== 断连仍留痕 ====================

    @Test
    @DisplayName("G5-13「无遗漏」：客户端中途断开，打点与消息**仍然**落库")
    void clientDisconnectStillPersists() {
        kbId = insertKb("M08A_GONE");
        long docId = insertDocWithChunks("报价单", "型号 A-100 单价 1800 元");
        seedVectors(docId, 1);
        awaitVectorsVisible("A-100 单价", 1);

        RecordingSink sink = new RecordingSink();
        sink.dieOnFirstDelta = true;
        chatStreamService.chat(reader(), ask("型号 A-100 的单价是多少"), sink);

        assertThat(RetrievalTestStubs.STREAM_CALLS.get())
                .as("连接断了，但生成照常跑完 —— 记录完整比省这一次调用更要紧")
                .isEqualTo(1);
        assertThat(sink.done).as("连接已断，done 发不出去").isNull();

        Integer traces = jdbc.queryForObject(
                "select count(*) from sys_retrieval_trace where session_id > ?", Integer.class, sessionFloor);
        assertThat(traces).as("断开也必须留痕").isEqualTo(1);
        Integer messages = jdbc.queryForObject(
                "select count(*) from sys_chat_message where session_id > ?", Integer.class, sessionFloor);
        assertThat(messages).as("user + assistant 两条都要在").isEqualTo(2);
    }

    // ==================== 夹具 ====================

    private static ChatDtos.ChatRequest ask(String question) {
        return new ChatDtos.ChatRequest(question, null, null, null, null);
    }

    /**
     * {@code TINYINT(1)} 列取出来是 **{@link Boolean}**（MySQL 驱动的约定），
     * 而 {@code INT} 列是 {@link Number} —— 同一个方法里两种都要接得住。
     * ⚠️ 别统一强转成 {@code Number}：那会在 {@code boolean} 列上抛 ClassCastException，
     * 而报错行指向的是断言，不是这里的类型假设（本批两处都是这么红的）。
     */
    private static int intOf(Object value) {
        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        return ((Number) value).intValue();
    }

    /** 一个把所有分片压到 0.05 分的重排替身 —— 让 {@code miss()} 必然成立。 */
    private static com.knowledge.ai.core.ai.RerankClient lowScoreRerank() {
        return new com.knowledge.ai.core.ai.RerankClient() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String describe() {
                return "试替身：全部低分";
            }

            @Override
            public List<RerankHit> rerank(String query, List<String> documents, int topK) {
                List<RerankHit> hits = new ArrayList<>();
                for (int i = 0; i < Math.min(topK, documents.size()); i++) {
                    hits.add(new RerankHit(i, 0.05d));
                }
                return hits;
            }
        };
    }

    /**
     * 收事件的替身。
     *
     * <p>{@link #dieOnFirstDelta} 模拟**客户端中途离开**：第一次 delta 之后
     * {@link #alive()} 变 false，后续事件全部不发 —— 与控制器里 {@code EmitterSink}
     * 的真实行为一致（它也是标记已死后不再 send）。
     */
    private static final class RecordingSink implements ChatSink {

        final List<String> eventNames = new ArrayList<>();
        final List<String> deltas = new ArrayList<>();
        ChatDtos.DoneEvent done;
        String error;
        boolean dieOnFirstDelta;

        private boolean alive = true;

        @Override
        public void retrieval(ChatDtos.RetrievalEvent event) {
            if (alive) {
                eventNames.add("retrieval");
            }
        }

        @Override
        public void delta(String content) {
            if (!alive) {
                return;
            }
            if (dieOnFirstDelta) {
                alive = false;
                return;
            }
            deltas.add(content);
            eventNames.add("delta");
        }

        @Override
        public void done(ChatDtos.DoneEvent event) {
            if (alive) {
                done = event;
                eventNames.add("done");
            }
        }

        @Override
        public void error(String message) {
            if (alive) {
                error = message;
                eventNames.add("error");
            }
        }

        @Override
        public boolean alive() {
            return alive;
        }
    }
}
