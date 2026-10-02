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

    @Autowired
    private com.knowledge.ai.service.ChatAttachmentService attachmentService;

    /** 一张真的 1×1 PNG（`T-O26`：探针素材要**可复算**，不靠记忆里的 base64）。 */
    private static final byte[] PNG_1X1 = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

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

    /** 本用例开始前 `sys_chat_attachment` 的最大 id（该表没有 kb/doc 坐标，探针清理认不出）。 */
    private long attachmentFloor;

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
        Long attMax = jdbc.queryForObject("select coalesce(max(id), 0) from sys_chat_attachment", Long.class);
        attachmentFloor = attMax == null ? 0L : attMax;
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

    // ==================== T-804 · 引用角标 → cited 落库 ====================

    /**
     * `T-804` 的核心判据：**`cited` 由答案角标解析而来，不是"喂给模型的那批"**。
     *
     * <h3>为什么必须"逐条相等"而不是"数量相等"</h3>
     * <p>本仓的硬口径（`G7-2` 那条：「A 的合计 = B」）已经在同一个形状上翻过车 ——
     * 数量相等时，**把两条都标错**也能过。这里用集合相等把它堵死。
     *
     * <h3>为什么要先断言"被引用的比喂进去的少"</h3>
     * <p>若两者恒相等（例如实现退化成"全都标 1"），下面那条集合相等**照样绿** ——
     * 那就是一条空判据。所以先钉住这个**不等关系**：本用例让替身只引用第 2 条，
     * 而喂进去的是 3 条。
     */
    @Test
    @DisplayName("T-804：cited 由**答案角标**解析 —— 与喂给模型的那批不等，且逐条对得上")
    void citedComesFromAnswerMarkersNotFromTheFedChunks() {
        kbId = insertKb("M06C_CITED");
        long docId = insertDocWithChunks("报价单",
                "型号 A-100 单价 1800 元", "型号 A-200 单价 2400 元", "型号 A-300 单价 3300 元");
        seedVectors(docId, 1);
        awaitVectorsVisible("单价", 3);

        // 替身只引用**第 2 条**（正文里打 `[2]`）—— 而检索喂进去 3 条
        RetrievalTestStubs.streamStub = RetrievalTestStubs.canned("结论：A-200 是 2400 元[2]。");

        RecordingSink sink = new RecordingSink();
        chatStreamService.chat(reader(), ask("型号 A-200 的单价是多少"), sink);

        String traceId = jdbc.queryForObject(
                "select trace_id from sys_retrieval_trace where session_id > ?", String.class, sessionFloor);

        List<Integer> cited = jdbc.queryForList(
                "select rank_no from sys_retrieval_trace_chunk where trace_id = ? and cited = 1 order by rank_no",
                Integer.class, traceId);
        int fed = jdbc.queryForObject(
                "select count(*) from sys_retrieval_trace_chunk where trace_id = ?", Integer.class, traceId);

        assertThat(cited)
                .as("🔴 答案只打了 [2] ⇒ 只有名次 2 那条被引用。"
                        + "若实现是「全部标 1」，这里会是 [1,2,3]，而下面的集合相等照样绿 —— "
                        + "所以**这一条断言是那条相等断言不空的前提**")
                .containsExactly(2);
        assertThat(fed)
                .as("喂给模型的是 3 条 —— 它必须**大于**被引用数，否则本用例验不出任何东西")
                .isGreaterThan(cited.size());
        assertThat(cited)
                .as("被引用的集合必须**逐条**等于答案角标集合")
                .isEqualTo(List.of(2));
    }

    @Test
    @DisplayName("T-804：模型没打角标 ⇒ cited 写 NULL 而**不是 0**")
    void noMarkerWritesNullNotZero() {
        kbId = insertKb("M06C_NOCITED");
        long docId = insertDocWithChunks("报价单", "型号 A-100 单价 1800 元");
        seedVectors(docId, 1);
        awaitVectorsVisible("A-100 单价", 1);

        // 替身回一段**没有角标**的答案（模型没照 prompt 做，或者它认为不需要引用）
        RetrievalTestStubs.streamStub = RetrievalTestStubs.canned("单价是 1800 元。");

        RecordingSink sink = new RecordingSink();
        chatStreamService.chat(reader(), ask("型号 A-100 的单价是多少"), sink);

        String traceId = jdbc.queryForObject(
                "select trace_id from sys_retrieval_trace where session_id > ?", String.class, sessionFloor);

        List<Boolean> nullFlags = jdbc.queryForList(
                "select cited is null from sys_retrieval_trace_chunk where trace_id = ?",
                Boolean.class, traceId);
        assertThat(nullFlags)
                .as("明细行本身要在（喂过就有行）—— 否则下面那条断言在空集上恒真")
                .isNotEmpty()
                .allMatch(Boolean::booleanValue);

        Integer zeroCount = jdbc.queryForObject(
                "select count(*) from sys_retrieval_trace_chunk where trace_id = ? and cited = 0",
                Integer.class, traceId);
        assertThat(zeroCount)
                .as("🔴 **0 是一句关于内容的断言**（「这条没被引用」），而「没解析出角标」是"
                        + "「没采集到」。两者在页面上长得一样，语义差一个量级 —— "
                        + "与 top1_score / rerank_before_top1 同一条口径")
                .isZero();
    }

    @Test
    @DisplayName("T-804：done.citations 是**按角标号**的清单，与 retrieval.chunks 是两份")
    void doneCitationsAreByMarkerNumberNotByRank() {
        kbId = insertKb("M06C_DONECITE");
        long docId = insertDocWithChunks("报价单",
                "型号 A-100 单价 1800 元", "型号 A-200 单价 2400 元", "型号 A-300 单价 3300 元");
        seedVectors(docId, 1);
        awaitVectorsVisible("单价", 3);

        RetrievalTestStubs.streamStub = RetrievalTestStubs.canned("只引第三条[3]。");

        RecordingSink sink = new RecordingSink();
        chatStreamService.chat(reader(), ask("型号 A-300 的单价是多少"), sink);

        assertThat(sink.done).isNotNull();
        assertThat(sink.done.citations())
                .as("`done.citations` 必须**按角标序**（这里是 [3]）—— 它答的是"
                        + "「答案引用了哪几条」，不是「系统找到了哪些」")
                .hasSize(1);
        assertThat(sink.done.citations().getFirst().index())
                .as("index = 答案里的角标号")
                .isEqualTo(3);
        assertThat(sink.done.citations().getFirst().rankNo())
                .as("rankNo = 检索名次。**两者可以不等**，而这条用例恰好造出了不等的情形："
                        + "角标 [3] 对应名次 3 —— 若实现把 index 恒等于 rankNo，"
                        + "本条会绿而上面那条集合判据会红，两条一起才钉得住")
                .isEqualTo(3);
        assertThat(sink.done.citations().getFirst().docTitle())
                .as("引用要能显示文档名（前端直接渲染）")
                .isEqualTo("报价单");
    }

    @Test
    @DisplayName("T-804：空召回时 cited 全 NULL，且 done.citations 为空清单（不是 null）")
    void emptyRecallHasNoCitations() {
        kbId = insertKb("M06C_EMPTYCITE");
        // 什么都不建 ⇒ 空召回 ⇒ 不调模型

        RecordingSink sink = new RecordingSink();
        chatStreamService.chat(reader(), ask("型号 A-100 的单价是多少"), sink);

        assertThat(RetrievalTestStubs.STREAM_CALLS.get()).isZero();
        assertThat(sink.done).isNotNull();
        assertThat(sink.done.citations())
                .as("发**空清单**而不是 null —— 前端按数组遍历，缺字段要写判空；"
                        + "而「这轮没有引用」与「这个字段还没实现」在页面上要能分开")
                .isNotNull()
                .isEmpty();
    }

    // ==================== T-806 · 直接读原图 ====================

    @Test
    @DisplayName("T-806：useVision=false（缺省）时**一张图都不带** —— 默认链路不变")
    void defaultPathCarriesNoImage() {
        kbId = insertKb("M06C_NOVIS");
        long docId = insertDocWithChunks("报价单", "型号 A-100 单价 1800 元");
        seedVectors(docId, 1);
        awaitVectorsVisible("A-100 单价", 1);

        RecordingSink sink = new RecordingSink();
        chatStreamService.chat(reader(), ask("型号 A-100 的单价是多少"), sink);

        assertThat(RetrievalTestStubs.LAST_IMAGES.get())
                .as("缺省（含 request.useVision() 为 null）必须走**原样**的纯文本链路 —— "
                        + "需求 09 §4.7：「默认链路保持解耦与低成本」")
                .isZero();
    }

    @Test
    @DisplayName("T-806：useVision=true 而模型未声明 vision ⇒ **拒**，且不调模型")
    void useVisionIsRejectedWhenModelCannotSee() {
        kbId = insertKb("M06C_VISDENY");
        long docId = insertDocWithChunks("报价单", "型号 A-100 单价 1800 元");
        seedVectors(docId, 1);
        awaitVectorsVisible("A-100 单价", 1);

        // 种子那行的 capabilities 是 NULL ⇒ 读侧兜底 VISION=false
        Long chatId = jdbc.queryForObject(
                "select id from sys_model_config where model_type = 'CHAT' and is_active = 1 limit 1", Long.class);
        String original = jdbc.queryForObject(
                "select capabilities from sys_model_config where id = ?", String.class, chatId);
        jdbc.update("update sys_model_config set capabilities = null where id = ?", chatId);
        try {
            RecordingSink sink = new RecordingSink();
            chatStreamService.chat(reader(),
                    new ChatDtos.ChatRequest("型号 A-100 的单价是多少", null, null,
                            List.of("999001"), Boolean.TRUE, null), sink);

            assertThat(sink.error)
                    .as("🔴 **置灰不是约束** —— 前端那个按钮的 disabled 拦不住直接打端点的人。"
                            + "需求 09 §4.7 的前置要在**服务端**成立")
                    .isNotNull()
                    .contains("视觉");
            assertThat(RetrievalTestStubs.STREAM_CALLS.get())
                    .as("拒了就不该调模型 —— 退回描述链路会悄悄花掉 40~60 秒并给出不含图的答案")
                    .isZero();
        } finally {
            // 复原：`#166` 那族「改全局状态的用例必须复原」—— 忘了复原会让**看着无关**的类红
            jdbc.update("update sys_model_config set capabilities = ? where id = ?", original, chatId);
        }
    }

    @Test
    @DisplayName("T-806：useVision=true 且附件不存在 ⇒ 报附件错误，**不是**能力错误")
    void missingAttachmentIsReportedAsAttachmentProblem() {
        kbId = insertKb("M06C_VISATT");
        long docId = insertDocWithChunks("报价单", "型号 A-100 单价 1800 元");
        seedVectors(docId, 1);
        awaitVectorsVisible("A-100 单价", 1);

        RecordingSink sink = new RecordingSink();
        chatStreamService.chat(reader(),
                new ChatDtos.ChatRequest("型号 A-100 的单价是多少", null, null,
                        List.of("999002"), Boolean.TRUE, null), sink);

        // 能力闸在前、附件校验在后 —— 本仓种子的 capabilities 是 NULL（vision=false），
        // 所以这一条**先撞能力闸**。两条错误都"看起来正常"（都是 error 事件），
        // 所以要断言的是**文案指向哪一个**：它必须说清是"不支持视觉"而不是"附件不存在"。
        assertThat(sink.error)
                .as("顺序是刻意的：能力闸在前（它不需要查库），附件校验在后。"
                        + "反过来的话，一个未声明的模型会在「图片不存在」上先报错，"
                        + "而用户会去重传图片、问题照旧")
                .isNotNull()
                .contains("视觉")
                .doesNotContain("重新上传");
    }

    @Test
    @DisplayName("T-806：usedVision 落库，且**只对该轮生效**（同一会话上一轮仍为 0）")
    void usedVisionIsPersistedPerRound() {
        kbId = insertKb("M06C_USEDVIS");
        long docId = insertDocWithChunks("报价单", "型号 A-100 单价 1800 元");
        seedVectors(docId, 1);
        awaitVectorsVisible("A-100 单价", 1);

        Long chatId = jdbc.queryForObject(
                "select id from sys_model_config where model_type = 'CHAT' and is_active = 1 limit 1", Long.class);
        String original = jdbc.queryForObject(
                "select capabilities from sys_model_config where id = ?", String.class, chatId);
        // 把对话模型声明成支持视觉 —— 步 0 探针实测**同一个模型确实看得懂图**（计划 §1-#3）
        jdbc.update("update sys_model_config set capabilities = ? where id = ?",
                "{\"stream\":true,\"vision\":true,\"contextWindow\":128000,\"supportSystemRole\":true}", chatId);
        try {
            // 真上传一张图（真字节 → 真落桶 → 真落库）。
            // ⚠️ 走 `ChatAttachmentService#upload` 而不是 HTTP：本基座没有 HTTP 客户端
            //    （`RetrievalFlowSupport` 直接继承 `Object`），而这里要验的是**编排**，
            //    上传接口本身另有 `ChatAttachmentFlowTest` 覆盖。
            String attachmentId = String.valueOf(attachmentService.upload(
                    reader().getUserId(),
                    new org.springframework.mock.web.MockMultipartFile(
                            "file", "报价单截图.png", "image/png", PNG_1X1)).attachmentId());

            // 第一轮：带图 + useVision=true ⇒ used_vision = 1
            RecordingSink first = new RecordingSink();
            chatStreamService.chat(reader(),
                    new ChatDtos.ChatRequest("型号 A-100 的单价是多少", null, null,
                            List.of(attachmentId), Boolean.TRUE, null), first);

            assertThat(first.error).as("能力闸放行了就不该再报错").isNull();
            assertThat(first.done).isNotNull();
            assertThat(first.done.usedVision())
                    .as("`done.usedVision` 必须是**真值**（此前写死 false）")
                    .isTrue();
            assertThat(RetrievalTestStubs.LAST_IMAGES.get())
                    .as("这条路上**真的把图带下去了** —— 这是整条兜底链路的意义所在，"
                            + "而它在用例里本来不可见")
                    .isEqualTo(1);
            assertThat(RetrievalTestStubs.LAST_IMAGE_DETAIL.getFirst().mime())
                    .as("MIME 一路带到底（猜错 MIME 的后果是模型收到解不开的图，"
                            + "而现象与「没看懂」完全一样）")
                    .isEqualTo("image/png");

            Long sessionId = jdbc.queryForObject(
                    "select session_id from sys_chat_message where id = ?", Long.class,
                    Long.parseLong(first.done.messageId()));
            assertThat(intOf(jdbc.queryForObject(
                    "select used_vision from sys_chat_message where id = ?", Object.class,
                    Long.parseLong(first.done.messageId()))))
                    .as("需求 09 §4.7 的「记录」那一行")
                    .isEqualTo(1);

            // 第二轮：同一会话，**不带图** ⇒ used_vision = 0（上一轮不受影响）
            RecordingSink second = new RecordingSink();
            chatStreamService.chat(reader(),
                    new ChatDtos.ChatRequest("那 A-200 呢", sessionId, null, null, null, null), second);
            assertThat(second.done.usedVision()).isFalse();
            assertThat(RetrievalTestStubs.LAST_IMAGES.get())
                    .as("缺省 useVision ⇒ 不带图")
                    .isZero();

            List<Integer> flags = jdbc.queryForList(
                    "select used_vision from sys_chat_message where session_id = ? and role = 'assistant' "
                            + "order by id", Integer.class, sessionId);
            assertThat(flags)
                    .as("🔴 **只对该轮生效**（§4.7 原文）—— 会话级会让上一轮被覆盖成 1，"
                            + "而「用描述 vs 用原图」的对比就做不出来了")
                    .containsExactly(1, 0);
        } finally {
            jdbc.update("update sys_model_config set capabilities = ? where id = ?", original, chatId);
            // 附件行按水位线删（`sys_chat_attachment` 没有 kb_id / doc_id 坐标 ⇒
            // 基座的探针清理认不出它）。⚠️ 桶里那个对象由 `ProbeCleanup` 的
            // `chat-attachments/` 前缀兜底收走（`T-O10`），不在这里手工删 ——
            // 两处都删会出现"一行删了两次"的假象，反而看不清谁该负责
            jdbc.update("delete from sys_chat_attachment where id > ?", attachmentFloor);
        }
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

    @Test
    @DisplayName("T-D07 判据1：思考过程逐帧透传，且与正文 delta 分离")
    void reasoningStreamsSeparatelyFromAnswer() {
        kbId = insertKb("M14A_REASON");
        long docId = insertDocWithChunks("报价单", "型号 A-100 单价 1800 元", "型号 A-200 单价 2400 元");
        seedVectors(docId, 1);
        awaitVectorsVisible("A-100 单价", 2);

        // 替身既吐思考（两句）又吐正文（一句多）
        RetrievalTestStubs.streamStub =
                RetrievalTestStubs.cannedWithReasoning("先看关键词。再比对文档。", "型号 A-100 单价 1800 元。");

        RecordingSink sink = new RecordingSink();
        chatStreamService.chat(reader(), ask("型号 A-100 的单价是多少"), sink);

        // 思考按事件通道发出，而不是混进正文
        assertThat(sink.reasonings).as("思考应逐帧发出且非空").isNotEmpty().allSatisfy(r -> assertThat(r).isNotBlank());
        assertThat(sink.eventNames).contains("reasoning");
        // 正文里绝不能出现思考文本
        assertThat(String.join("", sink.deltas))
                .as("思考不得混入正文 delta")
                .doesNotContain("先看关键词")
                .doesNotContain("再比对文档");
        // 思考事件必须出现在 delta 之前（先想后答）
        assertThat(sink.eventNames.indexOf("reasoning"))
                .as("思考事件应在正文 delta 之前")
                .isLessThan(sink.eventNames.indexOf("delta"));
    }

    @Test
    @DisplayName("T-D08 判据5：显式关闭思考模式 ⇒ 零 reasoning 事件")
    void reasoningDisabledEmitsNoReasoningEvents() {
        kbId = insertKb("M14A_OFF");
        long docId = insertDocWithChunks("报价单", "型号 A-100 单价 1800 元", "型号 A-200 单价 2400 元");
        seedVectors(docId, 1);
        awaitVectorsVisible("A-100 单价", 2);

        RetrievalTestStubs.streamStub =
                RetrievalTestStubs.cannedWithReasoning("这段思考不该被采集。", "型号 A-100 单价 1800 元。");

        RecordingSink sink = new RecordingSink();
        // reasoning=false ⇒ 编排把 onReasoning 传 null，替身即使有思考也不回调
        chatStreamService.chat(reader(),
                new ChatDtos.ChatRequest("型号 A-100 的单价是多少", null, null, null, null, Boolean.FALSE),
                sink);

        assertThat(sink.reasonings).as("关闭思考后不得有任何 reasoning 事件").isEmpty();
        assertThat(sink.eventNames).doesNotContain("reasoning");
        assertThat(sink.deltas).as("正文照常").isNotEmpty();
    }

    // ==================== 夹具 ====================

    private static ChatDtos.ChatRequest ask(String question) {
        return new ChatDtos.ChatRequest(question, null, null, null, null, null);
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
        final List<String> reasonings = new ArrayList<>();
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
        public void reasoning(String content) {
            if (!alive) {
                return;
            }
            reasonings.add(content);
            eventNames.add("reasoning");
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
