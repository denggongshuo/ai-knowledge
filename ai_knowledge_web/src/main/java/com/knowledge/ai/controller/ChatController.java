package com.knowledge.ai.controller;

import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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
 *   <li><b>不发 {@code attachment} 事件</b>：`T-512`（S6）之前图片不进问答。
 *       请求里的 {@code attachmentIds} / {@code useVision} 收下但**不生效**，
 *       {@code done.usedVision} 如实回 {@code false} —— 不假装接受了。</li>
 * </ol>
 *
 * <h3>为什么不在这里写审计点</h3>
 * <p>提问不是 🚩 危险操作。`AuditRiskPolicy.PLANNED` 里「问答」那一行的落点
 * 是问答侧的**管理动作**（反馈处置、会话导出），属后续批次。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    /**
     * SSE 超时。
     *
     * <p>取值 5 分钟：一次问答含检索 + 生成，远超普通接口，但**不能设 0**
     * （0 = 永不超时）—— 客户端异常消失时，那个请求线程会一直挂着。
     * 5 分钟足够任何一次正常生成，又给"僵尸连接"留了一条回收路径。
     */
    private static final long SSE_TIMEOUT_MILLIS = 5 * 60 * 1000L;

    private final ChatStreamService chatStreamService;

    private final ChatSessionService chatSessionService;

    private final ChatAttachmentService chatAttachmentService;

    private final ChatFeedbackService chatFeedbackService;

    /**
     * {@code POST /chat/completions} → {@code text/event-stream}。
     *
     * <p>事件序列见 {@link ChatStreamService}。⚠️ 这里**同步跑完**再返回 emitter：
     * {@code SseEmitter} 的每一帧在 {@code send} 时就已写出，所以"同步"不影响流式观感，
     * 反而让"生成结束 = 可以发 done"成为一条直白的因果（不必处理跨线程的完成通知）。
     */
    @PostMapping(value = "/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter completions(@RequestBody ChatDtos.ChatRequest request) {
        LoginUser user = LoginUserContext.require();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        EmitterSink sink = new EmitterSink(emitter);

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
        return emitter;
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
     * 把 {@link ChatSink} 架到 {@link SseEmitter} 上。
     *
     * <p>**核心职责是"客户端断开时不把异常放出去"**：`SseEmitter.send` 在连接已断时抛
     * {@code IOException}，若让它冒泡，编排的 {@code finally}（打点落库 + 消息落库）
     * 就跑不到 —— 而 `G5-13` 要的正是"每次提问都留痕"。
     * 所以这里就地吞掉、把自己标记为已死，后续发送全部降级为 no-op。
     */
    private static final class EmitterSink implements ChatSink {

        private final SseEmitter emitter;

        private boolean alive = true;

        EmitterSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void retrieval(ChatDtos.RetrievalEvent event) {
            send("retrieval", event);
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
            try {
                emitter.send(SseEmitter.event().name(name)
                        .data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException ex) {
                alive = false;
                log.warn("[问答] SSE 连接已断开，后续事件不再发送（收尾落库仍会执行）：{}", ex.getMessage());
            }
        }

        /**
         * 收尾。已断开时**不调** {@code complete()} —— 对一条已经断掉的连接调它只会再抛一次；
         * 容器会在超时或连接关闭时自行回收。
         */
        void complete() {
            if (!alive) {
                return;
            }
            try {
                emitter.complete();
            } catch (RuntimeException ex) {
                log.warn("[问答] SSE 收尾失败（不影响已完成的事件与落库）：{}", ex.getMessage());
            }
        }
    }
}
