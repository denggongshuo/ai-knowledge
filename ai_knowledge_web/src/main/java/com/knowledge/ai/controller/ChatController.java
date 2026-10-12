package com.knowledge.ai.controller;

import com.knowledge.ai.config.AsyncConfig;
import com.knowledge.ai.core.async.ChatRateLimiter;
import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.core.security.RequiresPerm;
import com.knowledge.ai.pipeline.chat.ChatDtos;
import com.knowledge.ai.pipeline.chat.ChatSink;
import com.knowledge.ai.service.ChatAttachmentService;
import com.knowledge.ai.service.ChatFeedbackService;
import com.knowledge.ai.service.ChatSessionService;
import com.knowledge.ai.service.ChatStreamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 问答入口（`T-800`）—— `POST /api/chat/completions`，SSE 流式。
 *
 * <h3>三个不能想当然的地方</h3>
 * <ol>
 *   <li><b>路径与请求字段是前端定死的</b>：{@code request.ts:245} 打的是
 *       {@code /api/chat/completions}，body 是 <b>{@code message}</b> 而不是 {@code query}，
 *       且**不带 {@code kbIds}**（检索范围由服务端的可见范围决定）。</li>
 *   <li><b>发问只要求登录，但会话管理不是</b>：{@code PermissionCode} 里确实没有
 *       {@code chat:*} —— 而问答侧的权限码在**另一个域名**下：7 个 {@code qa:*} 码
 *       （`T-802` 的四个接口各挂一个，见下方方法注释）。
 *       发问（本方法）仍只要求登录：它不写任何受控资源，收窄了会让普通用户问不出问题。
 *       ⚠️ 立据时这里写的是"没有 {@code chat:*} 所以只要求登录"，**漏了 {@code qa:*}** ——
 *       施工首日发现并订正（计划 §3 修订 1 / §9 遗留 1）。</li>
 *   <li><b>{@code attachmentIds} 已生效，{@code useVision} 仍不生效</b>：
 *       `T-907`（`S6-M09-B`）已接通图片理解，带图提问会先发
 *       {@code event: attachment}（`DESCRIBING` → `DONE`）再做检索。
 *       ⚠️ 本段此前写的是「`T-512`（S6）之前图片不进问答，两个字段收下但不生效」
 *       —— `T-907` 交付后那句话就过期了，`S6-M05-C` 订正。
 *       **仍然不生效的是 {@code useVision}**（「直接读原图」，需求 `09` §4.7）：
 *       前置是「当前对话模型 {@code capabilities.vision = true}」且只对该轮生效，属 `T-806`；
 *       {@code done.usedVision} 至今如实回 {@code false} —— 不假装接受了。</li>
 * </ol>
 *
 * <h3>为什么不在这里写审计点</h3>
 * <p>提问不是 🚩 危险操作。`AuditRiskPolicy.PLANNED` 里「问答」那一行的落点
 * 是问答侧的**管理动作**（反馈处置、会话导出），属后续批次。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
public class ChatController {


    private final ChatStreamService chatStreamService;

    private final ChatSessionService chatSessionService;

    private final ChatAttachmentService chatAttachmentService;

    private final ChatFeedbackService chatFeedbackService;

    /** 问答编排跑在**虚拟线程**上（`T-D07`）—— 不再占平台线程。上下文由该执行器自行搬运。 */
    private final Executor chatVirtualExecutor;

    /** SSE 事件体的序列化 —— 复用容器里那个（与 HTTP 响应同一套 Jackson 配置）。 */
    private final ObjectMapper objectMapper;

    /**
     * 问答入口的全局令牌桶（`app.chat.rate-limit.qps`）。
     *
     * <p>它保护的是上面那个**无界**的虚拟线程执行器：没有它，过载会一路打到模型供应商。
     */
    private final ChatRateLimiter chatRateLimiter;

    /**
     * 显式构造器（**不用 `@RequiredArgsConstructor`**）：上面那个执行器有 3 个同类型的
     * Bean，必须靠 {@code @Qualifier} 点名叫 {@link AsyncConfig#CHAT_EXECUTOR}。
     * 与 {@code AsyncTaskService} / {@code HybridRetriever} 两处的既有做法一致 ——
     * 字段级的 {@code @Qualifier} 能不能被 Lombok 抄到构造器参数上是另一回事，
     * 不拿它赌。
     */
    public ChatController(ChatStreamService chatStreamService,
                          ChatSessionService chatSessionService,
                          ChatAttachmentService chatAttachmentService,
                          ChatFeedbackService chatFeedbackService,
                          @Qualifier(AsyncConfig.CHAT_VIRTUAL_EXECUTOR) Executor chatVirtualExecutor,
                          ObjectMapper objectMapper,
                          ChatRateLimiter chatRateLimiter) {
        this.chatStreamService = chatStreamService;
        this.chatSessionService = chatSessionService;
        this.chatAttachmentService = chatAttachmentService;
        this.chatFeedbackService = chatFeedbackService;
        this.chatVirtualExecutor = chatVirtualExecutor;
        this.objectMapper = objectMapper;
        this.chatRateLimiter = chatRateLimiter;
    }

    /**
     * {@code POST /chat/completions} → {@code text/event-stream}。
     *
     * <h3>⚠️ 这里曾经是同步的，而那句解释是错的（`S6-M05-C` 的 P0 修复）</h3>
     * <p>改前是「同步跑完 {@code chatStreamService.chat(...)} 才 {@code return emitter}」，
     * 上面原先写着「{@code SseEmitter} 的每一帧在 {@code send} 时就已写出，所以"同步"
     * 不影响流式观感」。**那句话是错的，而且它正是这个缺陷的成因**：
     * Spring MVC 要等控制器方法**返回之后**才调 {@code emitter.initialize(handler)}，
     * 在此之前每一次 {@code send()} 都被 {@code ResponseBodyEmitter} 收进
     * {@code earlySendAttempts}，初始化时**一次性重放**。
     *
     * <p>实测（`S6-M09-B` §9-1）：一次带图问答的**全部帧**（含所有 {@code delta}）
     * 都在 {@code +3778ms} 一次性到达，而请求总耗时也是 3778ms。后果是需求 `09` §4.9
     * 「**必须**通过 SSE 提前给进度，否则用户盯着空白等」**从原理上没生效**，
     * `G5-7` 的实际效果为零，「流式生成」是错觉。
     *
     * <h3>为什么现在换成 {@code Flux<ServerSentEvent>}（`T-D07`）</h3>
     * <p>`SseEmitter` 那条路要靠"方法立刻返回 + 另一个线程 send"才能不触发上面的重放，
     * 断开检测也只能靠 {@code IOException} 猜。`Flux` 是 Spring MVC **原生支持**的返回类型
     * （底层仍是异步 Servlet，**不需要 WebFlux 容器**）：订阅由容器建立，取消由容器触发，
     * 逐帧写出与断开回收都成了框架的职责，不再是我们手写的一堆回调。
     *
     * <h3>🔴 编排仍然跑在虚拟线程上，用 {@code Sinks} 桥到 Flux</h3>
     * <p>{@code ChatStreamService} 是 1000+ 行命令式编排（JPA 阻塞 + 事务 + ThreadLocal），
     * 响应式化它要把数据访问与事务边界全部重写 —— 收益为零，风险极大。所以这里用
     * **单播 {@code Sinks.Many}**：编排在虚拟线程里照旧同步跑，每产生一个事件就
     * {@code tryEmitNext}，Flux 那侧只管往外吐。{@code ChatSink} 抽象**一个字没改**。
     *
     * <h3>⚠️ 客户端断开仍必须落库（`G5-13`）</h3>
     * <p>取消信号从 Flux 侧进来（{@code doOnCancel}），只把 sink **标记为死**、
     * 停止后续发送；**编排照常跑完**，{@code finally} 里的打点与消息落库不受影响。
     * 这是 `S6-M05-C` 那条 P0 的同一纪律，换成 Flux 后重新实现了一遍。
     *
     * <p>跨线程的身份与 traceId 由 {@link AsyncConfig#CHAT_VIRTUAL_EXECUTOR} 的包装搬运。
     *
     * <h3>入口限流（2026-10-12）</h3>
     * <p>提交前先过全局令牌桶（{@link ChatRateLimiter}，`app.chat.rate-limit.qps`），
     * 取不到令牌就抛 {@code CHAT_RATE_LIMITED} → **HTTP 429**。⚠️ 位置有两层讲究：
     * <ul>
     *   <li><b>必须在"提交到执行器"之前</b>：执行器是无界的，放进去就等于放行了；</li>
     *   <li><b>也必须在"返回 Flux"之前</b>：本方法产的是 SSE，响应头一旦发出就再也写不了
     *       JSON 错误体（连"200 带非 0 code"那条退路也没有）。只有在这里同步抛出，
     *       {@code GlobalExceptionHandler} 才来得及把 429 作为普通响应写回去。</li>
     * </ul>
     */
    @PostMapping(value = "/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> completions(@RequestBody ChatDtos.ChatRequest request) {
        LoginUser user = LoginUserContext.require();
        // 限流在鉴权之后：未登录的请求该拿 401，不该先消耗一个令牌（否则一个未登录的
        // 刷子能把全站令牌吃光，而它连一次问答都不会发起）。
        if (!chatRateLimiter.tryAcquire()) {
            throw BizException.of(ErrorCode.CHAT_RATE_LIMITED);
        }
        // 单播 + 缓冲：只有一个订阅者（容器），且快产慢出时把事件排队而不是丢
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();
        ReactiveSink chatSink = new ReactiveSink(sink, objectMapper);

        chatVirtualExecutor.execute(() -> runChat(user, request, chatSink));

        return sink.asFlux()
                // 客户端断开 / 取消：只止发送，不打断编排（编排照常跑完并落库）
                .doOnCancel(() -> chatSink.markDead("客户端已断开"));
    }

    /**
     * 一轮问答的**全部编排**，跑在 {@link AsyncConfig#CHAT_VIRTUAL_EXECUTOR} 的虚拟线程上。
     *
     * <p>⚠️ 身份与 traceId 由虚拟线程执行器的包装带过来，这里**不要**再自己 {@code set} 一遍 ——
     * 两处都设，将来改一处就会漏一处。
     */
    private void runChat(LoginUser user, ChatDtos.ChatRequest request, ReactiveSink sink) {
        try {
            chatStreamService.chat(user, request, sink);
        } catch (RuntimeException ex) {
            // 到这里说明编排自己炸了（不是模型失败 —— 那条已在编排内转成 error 事件）。
            // 响应头早就发出去了，写不了 JSON 错误体，只能再补一个 error 事件
            log.error("[问答] 编排异常：userId={}", user.getUserId(), ex);
            sink.error("服务处理失败：" + ex.getMessage() + " —— 请稍后重试。");
        } finally {
            sink.complete();
        }
    }


    // ==================================================================
    // T-802 余下半边（S5-M08-B）：会话列表 / 消息列表 / 删除 / 导出
    // ==================================================================

    /**
     * 会话列表（`T-802`），支持标题搜索。
     *
     * <p><b>权限码用的是 {@code qa:history:view} 而不是「只要求登录」</b>：
     * {@link com.knowledge.ai.core.enums.PermissionCode} 里的 7 个 {@code qa:*} 码
     * **早已存在、{@code V2} 已种子、且已按角色差异化授权**（`USER`/`AUDITOR` 有 view 没有 delete/export，
     * `GUEST` 一个都没有）。此前没有端点去用它们 —— 不挂就等于**把这条已裁定的权限边界作废**。
     *
     * <p>⚠️ 它**只挂 {@code view}，不挂 {@code qa:history:search}**：搜索是列表的收窄，
     * 能看全部即能筛，筛选不会放大可见面（详见计划 §9 的遗留登记）。
     */
    @GetMapping("/sessions")
    @RequiresPerm("qa:history:view")
    public ApiResponse<ChatDtos.SessionPage> sessions(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = LoginUserContext.requireUserId();
        return ApiResponse.ok(chatSessionService.list(userId, keyword, page, size));
    }

    /**
     * 某会话的消息列表（含历史引用 `citations[]`）。
     *
     * <p>归属校验在服务层：不是本人的 / 不存在 / 已软删，三者一律 {@code NOT_FOUND}
     * —— **不区分**它们是有意的，区分开就泄露了"这个 id 确实存在"。
     */
    @GetMapping("/sessions/{id}/messages")
    @RequiresPerm("qa:history:view")
    public ApiResponse<ChatDtos.MessagePage> messages(@PathVariable("id") Long sessionId) {
        Long userId = LoginUserContext.requireUserId();
        return ApiResponse.ok(chatSessionService.messages(userId, sessionId));
    }

    /**
     * 删除会话（**软删**，消息行保留）。
     *
     * <p>权限 {@code qa:history:delete} —— `USER` 与 `AUDITOR` **都没有**这个码，
     * 即"能回看自己的历史"与"能抹掉自己的历史"是两件事。
     */
    @DeleteMapping("/sessions/{id}")
    @RequiresPerm("qa:history:delete")
    public ApiResponse<Map<String, Boolean>> deleteSession(@PathVariable("id") Long sessionId) {
        Long userId = LoginUserContext.requireUserId();
        return ApiResponse.ok(Map.of("deleted", chatSessionService.softDelete(userId, sessionId)));
    }

    /**
     * 导出会话为 Markdown —— 🚩 **数据导出（高危）**。
     *
     * <p><b>为什么审计点挂在这里而不是 Service 上</b>：`operator` 与 `apiPath` 取自
     * **请求上下文**（{@code AuditAspect} 从 {@code LoginUserContext} / 请求里取），
     * 直调 Service 会测成空值 —— 而"谁导出了哪个会话"正是这条日志唯一的价值。
     *
     * <p>《01》§487 把「导出会话记录」与批量下载归为同一类**数据外带**行为；
     * 《05》§4.3② 把数据导出列为高危四类之一。
     *
     * <p>⚠️ 本注释**刻意不写那几个注解的字面形式**（连反例也不写）：
     * {@code audit-api.mjs} 用正则从本目录源码里抓路由，**不区分注释与真注解** ——
     * 在 javadoc 里写一遍就会多出一条幽灵路由（{@code DocController} 头注记录了这次踩坑）。
     */
    @GetMapping("/sessions/{id}/export")
    @RequiresPerm("qa:session:export")
    @AuditLog(module = AuditModule.QA, action = "导出会话", risk = RiskLevel.HIGH, target = "#sessionId")
    public ResponseEntity<byte[]> exportSession(@PathVariable("id") Long sessionId,
                                                @RequestParam(defaultValue = "md") String format) {
        Long userId = LoginUserContext.requireUserId();
        String markdown = chatSessionService.exportMarkdown(userId, sessionId);
        byte[] body = markdown.getBytes(StandardCharsets.UTF_8);

        // ⚠️ 文件名可能含中文（会话标题就是首个提问），http 头只能放 ASCII ——
        // 用 RFC 5987 的 `filename*=UTF-8''…` 带上真名，同时给一个纯 ASCII 的
        // `filename=` 兜底（老客户端只认前者时会拿到一个安全的默认名，而不是乱码）
        String ascii = "chat-session-" + sessionId + ".md";
        String encoded = URLEncoder.encode("会话-" + sessionId + ".md", StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/markdown; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded)
                .body(body);
    }

    // ==================================================================
    // 图片附件（`T-805`）· `S5-M08-C`
    // ==================================================================

    /**
     * 图片**预上传** —— 只落 MinIO + 落库，**不调 VL**。
     *
     * <p>「预」的含义是时序：图片在用户点发送之前就传完了，而 VL 描述是**发送时**
     * 才生成的（需求 `09` FR-SYS-04）。所以本接口不产生任何描述字段，
     * 也不用 `attachment` 事件往前端推进度 —— 那些是 `T-806`/`T-512`。
     *
     * <p><b>请求形状是 {@code multipart/form-data}，字段名 {@code file}</b>。
     * 这与需求 `09` §FR-SYS-04 的接口表一致 —— 而本批开工前的实情是：前端走的是
     * {@code http.post} 发一个 JSON 元数据对象，**文件字节从来没有上传过**。
     * 两边核对后按需求实现，前端同步改用既有的 {@code http.upload}（顺带拿到真进度）。
     *
     * <p>权限 {@code qa:ask}：上传附件是"提问"的一部分，而 {@code qa:feedback}
     * 是运营侧处置权（`USER` 角色没有它）。详见 {@code ChatFeedbackService} 的类注释。
     */
    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPerm("qa:ask")
    public ApiResponse<ChatDtos.AttachmentUploadResult> uploadAttachment(
            @RequestParam("file") MultipartFile file) {
        Long userId = LoginUserContext.requireUserId();
        return ApiResponse.ok(chatAttachmentService.upload(userId, file));
    }

    /**
     * 回看附件（含 VL 描述的位置）。
     *
     * <p>⚠️ 描述字段本批**如实为 {@code null}**（属 `T-512`），不是"没实现完"——
     * 见 {@link ChatDtos.AttachmentView} 的注释。
     *
     * <p>归属校验在服务层：不是本人的 / 不存在，**同码 {@code NOT_FOUND}**。
     */
    @GetMapping("/attachments/{id}")
    @RequiresPerm("qa:ask")
    public ApiResponse<ChatDtos.AttachmentView> attachment(@PathVariable("id") Long id) {
        Long userId = LoginUserContext.requireUserId();
        return ApiResponse.ok(chatAttachmentService.get(userId, id));
    }

    // ==================================================================
    // 反馈（`T-807` / `G5-14`）· `S5-M08-C`
    // ==================================================================

    /**
     * 点赞 / 点踩（+ 点踩原因）—— **务必落库**（需求 `07` §6.8 的标题原话）。
     *
     * <p>为什么这里**不写审计点**：反馈的**提交**不是 🚩 危险操作，
     * 而 `AuditRiskPolicy` 里「问答」那一行的落点是问答侧的**管理动作**
     * （反馈处置、会话导出）—— 用户自己点了个踩不需要进审计页。
     * 挂在这里会让审计日志被普通用户的操作淹没，反而盖住真正的管理动作。
     *
     * <p>权限 {@code qa:ask}（理由同上传附件），作用域边界由**归属校验**提供：
     * 只能反馈自己会话里的回答。
     */
    @PostMapping("/feedback")
    @RequiresPerm("qa:ask")
    public ApiResponse<ChatDtos.FeedbackResult> feedback(@RequestBody ChatDtos.FeedbackRequest request) {
        Long userId = LoginUserContext.requireUserId();
        return ApiResponse.ok(chatFeedbackService.submit(userId, request));
    }

    /**
     * 把 {@link ChatSink} 架到 {@code Sinks.Many} 上（`T-D07`）。
     *
     * <h3>核心职责没变：客户端断开时不把异常放出去、仍让人把账记完</h3>
     * <p>`SseEmitter` 时代是在 {@code send()} 抛 {@code IOException} 时就地吞掉
     * （`S6-M05-C` 的 P0）。换成 {@code Sinks} 后**没有异常可吞**：
     * {@code tryEmitNext} 只返回结果码，断了也不抛。断开信号改从两条来：
     * <ol>
     *   <li>{@code Flux#doOnCancel}（客户端取消订阅）→ {@link #markDead}；</li>
     *   <li>{@code tryEmitNext} 返回非 {@code OK}（取消 / 终止）→ 也标记死。</li>
     * </ol>
     * 两条都只**停止发送**，绝不打断编排 —— {@code finally} 里的打点落库照跑，
     * 这是 `G5-13`「每次提问都留痕」的要求。
     *
     * <h3>⚠️ 必须显式终止 sink</h3>
     * <p>不像 {@code SseEmitter.complete()} 会自己关连接，{@code Sinks} 不说"完了"
     * 那侧就永远不 {@code onComplete} —— 前端会一直等下去（`done` 事件发了也没用，
     * fetch 的 reader 不返回 EOF）。所以 {@link #complete()} **无论活死都要发射终止信号**：
     * 活着发 {@code tryEmitComplete}，死了发 {@code tryEmitError} 让 Flux 结束。
     */
    private static final class ReactiveSink implements ChatSink {

        private final Sinks.Many<ServerSentEvent<String>> sink;

        private final ObjectMapper objectMapper;

        /**
         * 连接是否还活着。
         *
         * <p>⚠️ **必须 `volatile`**：写它的是容器线程（`doOnCancel`），
         * 读它的是编排所在虚拟线程（{@link ChatSink#alive()}）。
         */
        private volatile boolean alive = true;

        ReactiveSink(Sinks.Many<ServerSentEvent<String>> sink,
                     ObjectMapper objectMapper) {
            this.sink = sink;
            this.objectMapper = objectMapper;
        }

        /** 标记为死：只停发送，不影响编排继续跑完收尾。幂等。 */
        private void markDead(String why) {
            if (alive) {
                alive = false;
                log.warn("[问答] SSE {}，后续事件不再发送（收尾落库仍会执行）", why);
            }
        }

        @Override
        public void retrieval(ChatDtos.RetrievalEvent event) {
            send("retrieval", event);
        }

        /**
         * `event: attachment`（`T-907`）。
         *
         * <p>⚠️ **必须覆盖它** —— {@link com.knowledge.ai.pipeline.chat.ChatSink#attachment}
         * 的默认实现是空操作（为了让既有替身不必改），不覆盖的话
         * 用户在页面上**永远看不到「正在理解图片」**，而这条链路的其余部分全对：
         * 描述生成了、检索也用了它、落库也落了。属于"用例绿而运行时缺一块"
         * 那一族（`S5-M08-C` §9-1 登记的形态）。
         */
        @Override
        public void attachment(ChatDtos.AttachmentEvent event) {
            send("attachment", event);
        }

        /**
         * `event: reasoning`（`T-D07`）。
         *
         * <p>⚠️ **必须覆盖它** —— 理由同 {@link #attachment}：默认实现是空操作，
         * 不覆盖的话思考过程收得到、编排里也拿得到，但**页面上一帧都看不到**。
         */
        @Override
        public void reasoning(String content) {
            send("reasoning", new ChatDtos.ReasoningEvent(content));
        }

        @Override
        public void delta(String content) {
            send("delta", new ChatDtos.DeltaEvent(content));
        }

        @Override
        public void done(ChatDtos.DoneEvent event) {
            send("done", event);
        }

        @Override
        public void error(String message) {
            send("error", new ChatDtos.ErrorEvent(message));
        }

        @Override
        public boolean alive() {
            return alive;
        }

        /** 事件名与 §9 逐字一致（前端按名字分发，拼错就是静默收不到）。 */
        private void send(String name, Object data) {
            if (!alive) {
                return;
            }
            String json;
            try {
                json = objectMapper.writeValueAsString(data);
            } catch (tools.jackson.core.JacksonException ex) {
                // 序列化失败**不抛**：抛出去会打断编排收尾。记日志、丢这一帧
                log.error("[问答] SSE 事件序列化失败，丢弃该帧：event={} err={}", name, ex.getMessage());
                return;
            }
            Sinks.EmitResult result = sink.tryEmitNext(
                    ServerSentEvent.<String>builder().event(name).data(json).build());
            if (result.isFailure()) {
                // 取消 / 已终止 —— 当成断开，后续不再发。⚠️ 不抛，编排要跑完
                markDead("发射失败：" + result);
            }
        }

        /**
         * 收尾。**无论活死都要发射终止信号**，否则 Flux 不结束、前端 reader 不返回。
         *
         * <p>活着 ⇒ {@code tryEmitComplete}（正常结束）；
         * 已死 ⇒ {@code tryEmitError}（把取消/异常态收口，让上游及时断开）。
         */
        void complete() {
            if (alive) {
                Sinks.EmitResult result = sink.tryEmitComplete();
                if (result.isFailure()) {
                    log.warn("[问答] SSE 收尾失败（不影响已完成的事件与落库）：{}", result);
                }
                alive = false;
            } else {
                // 已经断开 / 已终止：补一个 error 收口，避免 Flux 悬着
                sink.tryEmitError(new IllegalStateException("SSE 连接已断开"));
            }
        }
    }
}
