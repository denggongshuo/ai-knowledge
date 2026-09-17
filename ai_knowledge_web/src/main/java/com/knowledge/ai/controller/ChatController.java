package com.knowledge.ai.controller;

import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.pipeline.chat.ChatDtos;
import com.knowledge.ai.pipeline.chat.ChatSink;
import com.knowledge.ai.service.ChatStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 问答入口（`T-800`）—— `POST /api/chat/completions`，SSE 流式。
 *
 * <h3>三个不能想当然的地方</h3>
 * <ol>
 *   <li><b>路径与请求字段是前端定死的</b>：{@code request.ts:245} 打的是
 *       {@code /api/chat/completions}，body 是 <b>{@code message}</b> 而不是 {@code query}，
 *       且**不带 {@code kbIds}**（检索范围由服务端的可见范围决定）。</li>
 *   <li><b>权限只要求登录</b>：{@code PermissionCode} 里没有 {@code chat:*}，
 *       而问答本身不写任何受控资源。与 {@code /system/thresholds} / {@code /kb/presets}
 *       同型 —— 不收窄到某个权限码，免得普通用户问不出问题。
 *       ⚠️ 将来引入问答侧的运营权限时**要重新判**（已登记 `#122` 的同族问题）。</li>
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
