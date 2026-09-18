package com.knowledge.ai.pipeline.chat;

import com.knowledge.ai.config.AsyncConfig;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.core.trace.TraceContext;
import com.knowledge.ai.pipeline.retrieve.RetrievalFlowSupport;
import com.knowledge.ai.pipeline.retrieve.RetrievalTestStubs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 问答 SSE **真的是流式的**（`S6-M05-C` 的 P0 修复，遗留 `#174`/`#175`）。
 *
 * <h3>本类为什么必须存在 —— "能跑通"与"看得见"是两件事</h3>
 * <p>`S6-M09-B` 把 VL 描述整条链路在真模型上跑通了，而**页面上看不见它**：
 * 实测一次带图问答的**全部帧**（含所有 {@code delta}）都在 {@code +3778ms}
 * **一次性到达**，请求总耗时也是 3778ms。根因是控制器同步跑完才 {@code return emitter}，
 * 而 Spring MVC 要等方法返回后才 {@code emitter.initialize(handler)} —— 在此之前每一帧
 * 都被收进 {@code earlySendAttempts}，初始化时一次性重放。
 *
 * <p>所以本类的核心不是"事件序列对不对"（那是 {@link ChatStreamFlowTest} 的活），
 * 而是**量每一帧的到达时刻**：
 * <ol>
 *   <li>各 {@code delta} 帧必须**分散**在时间轴上，而不是挤在同一毫秒；
 *   <li>首帧必须**远早于** {@code done}；
 *   <li>流必须**完整结束**，不是被撕断（{@code #175} 的表现是
 *       {@code curl: (18) transfer closed with outstanding read data remaining}）。
 * </ol>
 *
 * <h3>为什么用 JDK 自带的 {@code HttpClient} 而不是基类的 {@code RestClient}</h3>
 * <p>{@code RestClient} 会把响应体**读完再给你** —— 用它量"帧到达时刻"等于用一把
 * 没有刻度的尺子。{@code BodyHandlers.ofInputStream()} 在**响应头到达时**就返回，
 * 之后逐块读、逐块打时间戳，才看得到"什么时候到了什么"。
 * 不引任何新依赖（JDK 自带）。
 *
 * <h3>⚠️ 这条判据必须能判红</h3>
 * <p>施工时**把 {@code ChatController#completions} 的改动临时回退**跑过一次，
 * 本类转红（各 {@code delta} 的到达时刻挤在 1~2ms 内）—— 否则它只是"跑绿了"，
 * 证不了任何东西。记录见计划 §8。
 */
@org.springframework.boot.test.context.SpringBootTest(
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatStreamingFlowTest extends RetrievalFlowSupport {

    /**
     * ⚠️ **本类必须覆盖成 `RANDOM_PORT`**（基类是默认的 `MOCK`）。
     *
     * <p>因为要验的东西**只存在于真 servlet 容器里**：Spring MVC 什么时候调
     * {@code emitter.initialize(handler)}、容器什么时候把帧刷出去、ASYNC 续跑会不会
     * 重过过滤器链（`#175`）—— `MOCK` 环境下这些**一个都不发生**，
     * 量出来的"帧到达时刻"是没有刻度的尺子。
     *
     * <p>代价是**多一个 Spring 上下文**（web 环境不同 ⇒ 上下文缓存不命中）。
     * 换来的是这条判据真的能判红 —— 划算。
     *
     * <p>{@code @SpringBootTest} 是 {@code @Inherited} 的，子类的声明优先于基类；
     * 万一哪天这条不再成立，{@link org.springframework.boot.test.web.server.LocalServerPort}
     * 会**当场注入失败**（不是静默退回 MOCK）。
     */
    @org.springframework.boot.test.web.server.LocalServerPort
    private int port;

    /** 替身吐几段。段数 > 1 才有"分散"可言（一段的话恒为一点）。 */
    private static final int DELTA_COUNT = 3;

    /** 替身每段之间睡多久。要求足够大到能盖过调度抖动。 */
    private static final long DELTA_GAP_MILLIS = 400L;

    @Autowired
    @Qualifier(AsyncConfig.CHAT_EXECUTOR)
    private Executor chatExecutor;

    /** 本用例开始前 {@code sys_chat_session} 的最大 id —— 之后新增的全是本轮造的。 */
    private long sessionFloor;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @BeforeEach
    void m05cSetUp() {
        RetrievalTestStubs.resetStream();
        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from sys_chat_session", Long.class);
        sessionFloor = max == null ? 0L : max;
    }

    @AfterEach
    void m05cTearDown() {
        // 与 `ChatStreamFlowTest` 同形：会话族四张表**没有 kb_id 坐标**，基类的
        // `cleanProbeData()` 认不出它们（`#130` 那族"产物认识、归属维度不认识"）
        jdbc.update("delete from sys_retrieval_trace_chunk where trace_id in "
                + "(select trace_id from sys_retrieval_trace where session_id > ?)", sessionFloor);
        jdbc.update("delete from sys_retrieval_trace where session_id > ?", sessionFloor);
        jdbc.update("delete from sys_chat_message where session_id > ?", sessionFloor);
        jdbc.update("delete from sys_chat_session where id > ?", sessionFloor);
        cleanProbeData();
    }

    // ==================================================================
    // 判据 1 / 3：帧是"流"出来的
    // ==================================================================

    @Test
    @DisplayName("判据 1/3：各 delta 帧分散在时间轴上，且首帧远早于 done、流完整结束")
    void framesArriveIncrementally() throws Exception {
        seedSearchableDoc();
        // 替身每段之间睡 400ms —— 三段的话，delta 帧的到达时刻应当跨越 ≈800ms
        RetrievalTestStubs.streamStub = slowCanned(DELTA_COUNT, DELTA_GAP_MILLIS);

        String token = login();
        SseRun run = readSse(token, "{\"message\":\"型号 A-100 的单价是多少\",\"sessionId\":null}");

        // ---------- ① 各 delta 帧必须分散 ----------
        List<Long> deltaMillis = run.millisOf("delta");
        assertThat(deltaMillis)
                .as("替身吐 %d 段，就应当收到 %d 个 delta 帧（收到 %d 个说明编排被折叠了）",
                        DELTA_COUNT, DELTA_COUNT, deltaMillis.size())
                .hasSize(DELTA_COUNT);

        long spread = deltaMillis.getLast() - deltaMillis.getFirst();
        // 允许 40% 的调度抖动，但**不允许**"挤在一起"：攒完一次性发的话这个跨度是 0~2ms，
        // 与门槛（≈800ms）差两个数量级 —— 所以这条断言的判别力不靠精度，靠数量级
        long floor = (DELTA_COUNT - 1) * DELTA_GAP_MILLIS * 6 / 10;
        assertThat(spread)
                .as("首末 delta 帧相隔 %d ms（应 ≥ %d ms）。"
                        + "⚠️ 挤在一起就是 `#174` 复现：整条 SSE 被攒到方法返回后一次性重放。"
                        + "各帧到达时刻：%s", spread, floor, deltaMillis)
                .isGreaterThanOrEqualTo(floor);

        // ---------- ② 首帧必须远早于 done ----------
        long firstFrame = run.millisOf(run.names().getFirst()).getFirst();
        assertThat(firstFrame)
                .as("首帧在 %d ms 到达、整轮 %d ms —— 首包必须让用户先看到东西（G5-7 的意图），"
                        + "而不是等整轮跑完", firstFrame, run.totalMillis())
                .isLessThan(run.totalMillis() / 2);

        // ---------- ③ 流必须是完整结束的 ----------
        assertThat(run.names().getLast())
                .as("最后一帧必须是 done。不是的话流被撕断了 —— 那正是 `#175`"
                        + "（ASYNC 续跑重过安全链被拒）的表现，而 curl 那边报的是 "
                        + "`(18) transfer closed with outstanding read data remaining`")
                .isEqualTo("done");
        assertThat(run.torn())
                .as("读流过程本身也不能出错（读到 EOF 即正常结束）")
                .isFalse();
    }

    // ==================================================================
    // 判据 5：traceId 跨线程跟过去了
    // ==================================================================

    @Test
    @DisplayName("判据 5：落库的 trace_id 与响应头 X-Trace-Id 是同一个（不是工作线程现场生成的）")
    void traceIdSurvivesTheThreadBoundary() throws Exception {
        seedSearchableDoc();
        RetrievalTestStubs.streamStub = slowCanned(1, 10L);

        String token = login();
        SseRun run = readSse(token, "{\"message\":\"型号 A-100 的单价是多少\",\"sessionId\":null}");

        String headerTraceId = run.traceIdHeader();
        assertThat(headerTraceId).as("响应头 X-Trace-Id 必须有值").isNotBlank();

        // ⚠️ 这是本判据的**判别点**：`ChatStreamService` 读的是
        // `TraceContext.current()`，而它在**没有绑定的线程上会现场生成一个新 id**
        //（`TraceContext` 的 javadoc 明写"兜底生成，不返回 null"）。
        // 于是"漏传 traceId"不会报错、日志里也每条都有 id —— 只是**与触发它的那次请求
        // 不是同一个**，而这正是 traceId 存在的唯一理由（`S4-E2` 在异步解析链路上
        // 踩过同一个坑，那次补的是 `traceIdPropagating`）。
        List<String> stored = jdbc.queryForList(
                "select trace_id from sys_retrieval_trace where session_id > ?", String.class, sessionFloor);
        assertThat(stored).as("本轮应当落下一条检索打点").isNotEmpty();
        assertThat(stored)
                .as("打点里的 trace_id 与响应头不一致 ⇒ 工作线程拿到的是**新生成的** id，"
                        + "不是这次请求的（响应头=%s，库里=%s）", headerTraceId, stored)
                .allMatch(headerTraceId::equals);
    }

    // ==================================================================
    // 判据 7 / 8：执行器把身份带过线程边界，且不残留
    // ==================================================================

    @Test
    @DisplayName("判据 7/8：chatExecutor 传身份与 traceId；池化线程用完必须清干净")
    void chatExecutorPropagatesAndCleans() throws Exception {
        LoginUser probe = LoginUser.builder().userId(4242L).username("m05c.probe").build();
        String traceId = "m05c" + Long.toHexString(System.nanoTime());

        AtomicReference<Long> seenUser = new AtomicReference<>();
        AtomicReference<String> seenTrace = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        // 在**当前线程**上设置，再提交 —— 模拟 `ChatController#completions` 的处境
        LoginUserContext.set(probe);
        TraceContext.set(traceId);
        try {
            chatExecutor.execute(() -> {
                try {
                    seenUser.set(LoginUserContext.userIdOrNull());
                    seenTrace.set(TraceContext.get());
                } finally {
                    done.countDown();
                }
            });
        } finally {
            LoginUserContext.clear();
            TraceContext.clear();
        }
        assertThat(done.await(10, TimeUnit.SECONDS)).as("探针任务应当跑完").isTrue();

        assertThat(seenUser.get()).as("工作线程上必须拿得到身份").isEqualTo(4242L);
        assertThat(seenTrace.get())
                .as("工作线程上必须拿得到**同一个** traceId（不是新生成的）")
                .isEqualTo(traceId);

        // 再提交一个：提交线程此刻**没有**上下文，工作线程**不得**继承上一个用户的身份
        CountDownLatch second = new CountDownLatch(1);
        AtomicReference<Long> leakedUser = new AtomicReference<>(-1L);
        AtomicReference<String> leakedTrace = new AtomicReference<>("none");
        chatExecutor.execute(() -> {
            try {
                leakedUser.set(LoginUserContext.userIdOrNull());
                leakedTrace.set(TraceContext.get());
            } finally {
                second.countDown();
            }
        });
        assertThat(second.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(leakedUser.get())
                .as("池化线程没清干净 ⇒ 下一条不相关的问答会**继承**上一个用户的身份。"
                        + "那不只是日志串味，是**越权**")
                .isNull();
        assertThat(leakedTrace.get()).as("traceId 同样不得残留").isNull();
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 造一个能被 `A-100` 命中的探针库 + 文档 + 向量。 */
    private void seedSearchableDoc() {
        kbId = insertKb("M05C_STREAM");
        long docId = insertDocWithChunks("报价单", "型号 A-100 单价 1800 元", "型号 A-200 单价 2400 元");
        seedVectors(docId, 1);
        awaitVectorsVisible("A-100 单价", 2);
    }

    /** 每段之间睡 {@code gapMillis} 的替身 —— 段与段之间**必须有真实时间差**才量得出"分散"。 */
    private static StreamingChatCaller slowCanned(int pieces, long gapMillis) {
        return (prompt, onDelta) -> {
            RetrievalTestStubs.LAST_PROMPT = prompt;
            for (int i = 0; i < pieces; i++) {
                onDelta.accept("第" + (i + 1) + "段回答内容。");
                try {
                    Thread.sleep(gapMillis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return new StreamingChatCaller.Outcome(7L, "stub-chat-model",
                    new ChatDtos.TokenUsage(11, 22, 33), "stop");
        };
    }

    /**
     * 登录取 token。
     *
     * <p>自己写而不是继承 {@code ApiTestSupport#loginAsAdmin}：基类是
     * {@code RetrievalFlowSupport}（真 Milvus + 真替身），而
     * {@code ApiTestSupport} 是另一条继承链（HTTP 用例专用）。
     * 本类**两条都要**，所以 HTTP 这一侧自己来 —— 反正 {@code client} 已经在手上了。
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

    /** 用 JDK {@code HttpClient} 流式读一次 SSE，记录**每一帧到达的相对毫秒数**。 */
    private SseRun readSse(String token, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/api/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        long start = System.nanoTime();
        // `ofInputStream()` 在**响应头到达时**就返回，之后逐块读 —— 这是能量"到达时刻"
        // 的前提（`RestClient` 会读完再给）
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        SseRun run = new SseRun(response.headers().firstValue(TraceContext.HEADER).orElse(null));

        // 按**字节**攒帧再解码：逐块 `new String(chunk, UTF_8)` 会把跨块的多字节字符切坏，
        // 而本仓的正文全是中文，那是必然会踩的
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = response.body()) {
            byte[] chunk = new byte[1024];
            int read;
            while ((read = in.read(chunk)) != -1) {
                long arrivedAt = (System.nanoTime() - start) / 1_000_000L;
                buffer.write(chunk, 0, read);
                byte[] all = buffer.toByteArray();
                int idx;
                while ((idx = indexOfFrameEnd(all)) != -1) {
                    run.record(new String(all, 0, idx, StandardCharsets.UTF_8), arrivedAt);
                    all = Arrays.copyOfRange(all, idx + 2, all.length);
                    buffer.reset();
                    buffer.write(all);
                }
            }
        } catch (Exception ex) {
            // 读到一半被撕断（`#175` 的形态）—— 记下来，让断言去判红，而不是让本方法抛
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

    /** 一次 SSE 读取的结果：事件名 + 各自到达的相对毫秒数。 */
    private static final class SseRun {

        private final List<String> names = new ArrayList<>();
        private final List<Long> millis = new ArrayList<>();
        private final String traceIdHeader;
        private boolean torn;

        private SseRun(String traceIdHeader) {
            this.traceIdHeader = traceIdHeader;
        }

        private void record(String frame, long arrivedAt) {
            String name = null;
            for (String line : frame.split("\n")) {
                if (line.startsWith("event:")) {
                    name = line.substring("event:".length()).trim();
                    break;
                }
            }
            if (name != null && !name.isEmpty()) {
                names.add(name);
                millis.add(arrivedAt);
            }
        }

        private void markTorn() {
            this.torn = true;
        }

        private List<String> names() {
            return names;
        }

        private boolean torn() {
            return torn;
        }

        private String traceIdHeader() {
            return traceIdHeader;
        }

        /** 某个事件名每一次到达的相对毫秒数。 */
        private List<Long> millisOf(String eventName) {
            List<Long> out = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                if (names.get(i).equals(eventName)) {
                    out.add(millis.get(i));
                }
            }
            return out;
        }

        private long totalMillis() {
            return millis.isEmpty() ? 0L : millis.getLast();
        }
    }
}
