package com.knowledge.ai.pipeline.chat;

import com.knowledge.ai.pipeline.retrieve.RetrievalFlowSupport;
import com.knowledge.ai.pipeline.retrieve.RetrievalTestStubs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `G9-2` 的**端到端半边**（`S9-M12-C`）：图谱降级（关闭 / 超时）**不影响问答、不报错**。
 *
 * <h3>与 {@code GraphRetrieveTimeoutTest} 的分工</h3>
 * <p>那一类判的是**检索服务**：{@code graphFallback=true}、不抛、主链路一条不少。
 * 本类判的是**用户看到的那条链**：SSE 流能不能完整走完、末帧是不是 {@code done}、
 * 中间会不会冒出一个 {@code error} 帧。两者缺一不可 —— 检索侧全绿而问答照样可能
 * 因为"降级后 trace 字段对不上"、"图谱容器为 null 时空指针"之类的原因把流打断，
 * 而那些只有穿过 {@code ChatStreamService} → {@code ChatWireMapper} → SSE 才暴露得出来。
 *
 * <h3>为什么要真实 HTTP + {@code RANDOM_PORT}</h3>
 * <p>同 {@link ChatStreamingFlowTest}：「流有没有被撕断」「错误帧有没有出现」
 * 只在真 servlet 容器里才存在。{@code MOCK} 环境下 SSE 的异步续跑根本不发生，
 * 量出来的"没事"是假的。取帧姿势（JDK {@code HttpClient} + {@code ofInputStream}，
 * 按**字节**攒帧再解码）也照该类 —— 它已经在 `#174`/`#175` 上证明过能判红。
 *
 * <h3>⚠️ 这条判据的判别力来自哪里 —— 为什么"不报错"必须配一格反向断言</h3>
 * <p>"不报错"单独看很弱：一个**根本不会出错**的实现（比如图谱压根没接进去）也满足它。
 * 所以每个场景都同时钉住图谱那一格：
 * <ul>
 *   <li>场景 ⓐ 关闭 ⇒ {@code graphUsed=false} 且 {@code graphCostMs=null}。
 *       证明**旁路真的没跑**（`I-16` 的"完全回到未接图谱"）；</li>
 *   <li>场景 ⓑ 超时 ⇒ {@code graphFallback=true}。证明旁路**真的跑了并降级了** ——
 *       有这一格，场景 ⓐ 的"流没断"才不是空话。</li>
 * </ul>
 *
 * <p>需求出处：`docs/需求文档/07-知识图谱.md` `FR-GRAPH-01`（200ms 超时静默降级）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.TestPropertySource(properties = {
        // 建图侧关掉：本类只关心检索侧的旁路，写图会往 Neo4j 塞东西而无人回收
        "m05.graph.enabled=false",
        // ⚠️ 关键：把预算压到 1ms ⇒ 旁路那两次 Neo4j 往返物理上不可能压进去 ⇒ 必然降级。
        // 靠"真库真的慢"来制造超时是随机红；压预算是把**分支**钉死（照 GraphRetrieveTimeoutTest）
        "m05.graph.retrieve.timeout-ms=1"})
class ChatGraphDegradeEndToEndTest extends RetrievalFlowSupport {

    /** 图谱探针实体名 —— 与 {@code GraphRetrieve*} 两类同前缀，便于认领与清理。 */
    private static final String ENTITY = "图谱探针-端到端";

    /** 能命中探针实体的提问（长度也得够触发线，见 {@code GraphEntityLinker} 的 6-token 门槛）。 */
    private static final String GRAPH_QUERY = "图谱探针-端到端的一致性哈希与容量规划怎么定";

    @LocalServerPort
    private int port;

    @Autowired
    private Driver driver;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 本用例开始前 {@code sys_chat_session} 的最大 id —— 之后新增的全是本轮造的。 */
    private long sessionFloor;

    @BeforeEach
    void setUp() {
        RetrievalTestStubs.resetStream();
        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from sys_chat_session", Long.class);
        sessionFloor = max == null ? 0L : max;
        // 每个用例都从「开」起步；场景 ⓐ 自己再关掉。超时那一格由类级属性（1ms）决定
        setGraphSwitch(true);
    }

    @AfterEach
    void tearDown() {
        cleanGraphEntities();
        // 会话族四张表**没有 kb_id 坐标**，基类的 `cleanProbeData()` 认不出（`#130` 那族）
        jdbc.update("delete from sys_retrieval_trace_chunk where trace_id in "
                + "(select trace_id from sys_retrieval_trace where session_id > ?)", sessionFloor);
        jdbc.update("delete from sys_retrieval_trace where session_id > ?", sessionFloor);
        jdbc.update("delete from sys_chat_message where session_id > ?", sessionFloor);
        jdbc.update("delete from sys_chat_session where id > ?", sessionFloor);
        cleanProbeData();
        // ⚠️ 必须还原为 true：开关是全站单值（不分库），留在 false 会**静默**改变
        // 后续所有用例的检索行为。不只写回，还断言写回成功 —— 否则"还原了"只是句声明
        setGraphSwitch(true);
        assertThat(currentSwitchValue())
                .as("开关没还原成 true ⇒ 后面的批次会莫名其妙少走图谱")
                .isEqualTo("true");
    }

    // ==================================================================
    // 场景 ⓐ：图谱关 ⇒ 问答照常，且旁路确实没跑
    // ==================================================================

    @Test
    @DisplayName("G9-2 · 图谱关：SSE 流完整结束、无 error 帧，且 retrieval 帧报「没跑」")
    void graphOffStillAnswers() throws Exception {
        seedSearchableDoc();
        setGraphSwitch(false);
        RetrievalTestStubs.streamStub = canned(2);

        SseRun run = readSse(login(), "{\"message\":\"型号 A-100 的单价是多少\",\"sessionId\":null}");

        assertStreamIsHealthy(run, "图谱关");

        SseFrame retrieval = run.firstNamed("retrieval");
        assertThat((Boolean) retrieval.json("$.trace.graphUsed"))
                .as("关掉了图谱还占着融合位 ⇒ `I-16` 的「与未接图谱时完全一致」不成立")
                .isFalse();
        assertThat(retrieval.json("$.trace.graphCostMs"))
                .as("没跑过就**不下发**成本（不是下发 0）—— 「没跑」与「耗时 0ms」不是一回事")
                .isNull();
    }

    // ==================================================================
    // 场景 ⓑ：图谱开 + 超时 ⇒ 静默降级，问答照常
    // ==================================================================

    @Test
    @DisplayName("G9-2 · 超时降级：SSE 流完整结束、无 error 帧，且 retrieval 帧报 graphFallback")
    void graphTimeoutStillAnswers() throws Exception {
        seedSearchableDoc();
        RetrievalTestStubs.streamStub = canned(2);

        SseRun run = readSse(login(), "{\"message\":\"" + GRAPH_QUERY + "\",\"sessionId\":null}");

        assertStreamIsHealthy(run, "超时降级");

        SseFrame retrieval = run.firstNamed("retrieval");
        assertThat((Boolean) retrieval.json("$.trace.graphFallback"))
                .as("1ms 预算下旁路必然超时 ⇒ 必须标降级。这一格证明旁路**真的跑了**，"
                        + "否则场景 ⓐ 的『流没断』毫无信息量")
                .isTrue();
    }

    // ==================================================================
    // 共用断言：什么叫"问答不受影响、不报错"
    // ==================================================================

    /**
     * 流的三条健康标准。
     *
     * <p>① 末帧是 {@code done} —— 不是的话流被撕断了（`#175` 的形态）；
     * ② 全程没有 {@code error} 帧 —— 降级**不许**升级成报错（需求 `07` `FR-GRAPH-01` 的红线）；
     * ③ 读到 EOF，没有异常中断。
     *
     * @param what 场景名，只用于失败信息
     */
    private void assertStreamIsHealthy(SseRun run, String what) {
        assertThat(run.names())
                .as("[%s] 图谱出问题不该让问答连一帧都没有。实际帧序：%s", what, run.names())
                .isNotEmpty();
        assertThat(run.names())
                .as("[%s] 出现 error 帧 ⇒ 静默降级变成了报错。实际帧序：%s", what, run.names())
                .doesNotContain("error");
        assertThat(run.names().getLast())
                .as("[%s] 末帧必须是 done。不是的话流被撕断了 —— 那正是 `#175` 的表现。"
                        + "实际帧序：%s", what, run.names())
                .isEqualTo("done");
        assertThat(run.torn())
                .as("[%s] 读流本身不能出错（读到 EOF 即正常结束）", what)
                .isFalse();
    }

    // ==================================================================
    // 造数据
    // ==================================================================

    private void setGraphSwitch(boolean on) {
        jdbc.update("update sys_setting set setting_value = ?"
                + " where setting_key = 'retrieval.graph-bypass-enabled'", Boolean.toString(on));
    }

    private String currentSwitchValue() {
        return jdbc.queryForObject(
                "select setting_value from sys_setting where setting_key = 'retrieval.graph-bypass-enabled'",
                String.class);
    }

    /** 一篇**可被向量路召回**的文档 —— 保证主链路有东西可答（否则"没报错"是空绿）。 */
    private void seedSearchableDoc() {
        kbId = insertKb("M12C_E2E");
        long docId = insertDocWithChunks("报价单",
                "型号 A-100 单价 1800 元，一致性哈希与容量规划见附录",
                "型号 A-200 单价 2400 元");
        seedVectors(docId, 1);
        awaitVectorsVisible("A-100 单价", 2);
    }

    /**
     * 往图上挂一个**只有图谱够得着**的实体 —— 让"旁路跑没跑"这件事有观测点。
     *
     * <p>形制照 {@code GraphRetrieveTimeoutTest}：实体名必须与提问逐字一致，
     * 否则 {@code GraphEntityLinker} 的词典匹配不中，旁路会在**触发之前**就静默返回 ——
     * 那时 {@code graphFallback} 恒为 false，场景 ⓑ 会红得看不出原因。
     */
    private void seedGraphEntity() {
        Long docId = jdbc.queryForObject(
                "select id from sys_document where kb_id = ? order by id limit 1", Long.class, kbId);
        Long chunkId = jdbc.queryForObject(
                "select id from sys_chunk where doc_id = ? order by order_no limit 1", Long.class, docId);
        try (Session session = driver.session()) {
            session.run("""
                    MERGE (s:Entity {name: $subject})
                    MERGE (o:Entity {name: '图谱探针-对端'})
                    MERGE (s)-[r:REL {relation: '基于', sourceChunkId: $chunkId}]->(o)
                    SET r.confidence = 0.9
                    """, Map.of("subject", ENTITY, "chunkId", chunkId)).consume();
        }
    }

    private void cleanGraphEntities() {
        try (Session session = driver.session()) {
            session.run("MATCH (e:Entity) WHERE e.name STARTS WITH '图谱探针-' DETACH DELETE e").consume();
        } catch (Exception ignored) {
            // 清不掉不该让用例失败（与 GraphRetrieveTimeoutTest 同口径）
        }
    }

    private static StreamingChatCaller canned(int pieces) {
        return (prompt, onDelta) -> {
            RetrievalTestStubs.LAST_PROMPT = prompt;
            for (int i = 0; i < pieces; i++) {
                onDelta.accept("第" + (i + 1) + "段回答内容。");
            }
            return new StreamingChatCaller.Outcome(7L, "stub-chat-model",
                    new ChatDtos.TokenUsage(11, 22, 33), "stop");
        };
    }

    // ==================================================================
    // HTTP
    // ==================================================================

    /**
     * 登录取 token。
     *
     * <p>自己写而不是继承 {@code ApiTestSupport#loginAsAdmin}：基类是
     * {@code RetrievalFlowSupport}（真 Milvus + 真替身），而 {@code ApiTestSupport}
     * 是另一条继承链（HTTP 用例专用）。本类**两条都要**，所以 HTTP 这一侧自己来
     * —— 反正 {@code client} 已经在手上了。同 {@link ChatStreamingFlowTest}。
     */
    private String login() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"account\":\"admin\",\"password\":\"Admin@123\",\"remember\":true}",
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as("登录失败：%s", response.body()).isEqualTo(200);
        return com.jayway.jsonpath.JsonPath.read(response.body(), "$.data.accessToken");
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    /** 流式读一次 SSE，按帧攒下来（含原始 JSON，供断言读 {@code trace} 各格）。 */
    private SseRun readSse(String token, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/api/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        SseRun run = new SseRun();

        // 按**字节**攒帧再解码：逐块 `new String(chunk, UTF_8)` 会把跨块的多字节字符切坏，
        // 而本仓的正文全是中文，那是必然会踩的
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = response.body()) {
            byte[] chunk = new byte[1024];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
                byte[] all = buffer.toByteArray();
                int idx;
                while ((idx = indexOfFrameEnd(all)) != -1) {
                    run.record(new String(all, 0, idx, StandardCharsets.UTF_8));
                    all = Arrays.copyOfRange(all, idx + 2, all.length);
                    buffer.reset();
                    buffer.write(all);
                }
            }
        } catch (Exception ex) {
            // 读到一半被撕断（`#175` 的形态）—— 记下来让断言判红，而不是让本方法抛
            run.markTorn();
        }
        return run;
    }

    /** SSE 帧以空行分隔（`\n\n`）。 */
    private static int indexOfFrameEnd(byte[] bytes) {
        for (int i = 0; i + 1 < bytes.length; i++) {
            if (bytes[i] == '\n' && bytes[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** 一帧：事件名 + 该帧 {@code data:} 行的 JSON。 */
    private record SseFrame(String name, String data) {

        Object json(String path) {
            return com.jayway.jsonpath.JsonPath.read(data, path);
        }
    }

    /** 一次 SSE 读取的结果。 */
    private static final class SseRun {

        private final List<SseFrame> frames = new ArrayList<>();
        private boolean torn;

        private void record(String frame) {
            String name = null;
            StringBuilder data = new StringBuilder();
            for (String line : frame.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring("data:".length()).trim());
                }
            }
            if (name != null && !name.isEmpty()) {
                frames.add(new SseFrame(name, data.toString()));
            }
        }

        private void markTorn() {
            this.torn = true;
        }

        private List<String> names() {
            return frames.stream().map(SseFrame::name).toList();
        }

        private boolean torn() {
            return torn;
        }

        private SseFrame firstNamed(String eventName) {
            return frames.stream().filter(f -> f.name().equals(eventName)).findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "没收到 " + eventName + " 帧。实际帧序：" + names()));
        }
    }
}
