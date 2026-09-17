package com.knowledge.ai.pipeline.chat;

import com.knowledge.ai.core.ai.ModelClientRegistry;
import com.knowledge.ai.core.ai.ModelConfigSpec;
import com.knowledge.ai.core.enums.ModelType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * {@link StreamingChatCaller} 的生产实现 —— 问答侧**唯一**碰模型客户端的地方。
 *
 * <p>与 {@code RegistryChatCaller}（增强侧）同一条纪律：任何模块要调模型都经
 * {@link ModelClientRegistry}，不得自己 {@code new}（`T-014` 的验收要点）。
 * 两个实现类各自持有注册中心，"唯一入口"这一条因此是**结构性**的。
 *
 * <h3>为什么要 {@code .stream().chatResponse()} 而不是 {@code .stream().content()}</h3>
 * <p>后者只给文本，而 §9 的 `done` 事件**必带** `tokenUsage` 与 `finishReason`
 * —— 那两个数只在 {@link ChatResponse} 的元数据里。取 `chatResponse()` 一次拿到两样，
 * 不必为了凑元数据再发一次请求。
 *
 * <h3>为什么用 {@code toIterable()} 阻塞消费</h3>
 * <p>{@code SseEmitter} 是同步栈：请求线程负责边收边发。用 {@code subscribe()} 会让
 * 回调落在别的线程上，{@code SseEmitter} 的并发 `send` 与"什么时候算结束"都会变得难说。
 * 阻塞消费让"循环走完 = 生成结束 = 可以发 done"成为一条直白的因果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegistryStreamingChatCaller implements StreamingChatCaller {

    private final ModelClientRegistry registry;

    @Override
    public Outcome stream(String prompt, Consumer<String> onDelta) {
        // 先取 spec 再取客户端：`activeSpec` 在未配置对话模型时**抛**
        // MODEL_NOT_CONFIGURED（硬失败，见 ModelClientRegistry 的兜底口径），
        // 与其拿到一个含混的空响应，不如让调用方立刻知道"没配模型"
        ModelConfigSpec spec = registry.activeSpec(ModelType.CHAT);
        ChatClient client = registry.chatClient(spec.id());

        // 流里的每个 ChatResponse 都带着"到目前为止"的结果，而 usage 通常只挂在最后一帧
        // —— 所以留一份引用，循环结束后从它取元数据
        AtomicReference<ChatResponse> lastFrame = new AtomicReference<>();
        for (ChatResponse response : client.prompt().user(prompt).stream().chatResponse().toIterable()) {
            lastFrame.set(response);
            String piece = textOf(response);
            if (!piece.isEmpty()) {
                onDelta.accept(piece);
            }
        }

        ChatResponse last = lastFrame.get();
        Outcome outcome = new Outcome(numericId(spec.id()), spec.model(),
                usageOf(last), finishReasonOf(last));
        log.info("[问答] 生成结束：model={} finish={} tokens={}",
                outcome.modelName(), outcome.finishReason(), outcome.tokenUsage());
        return outcome;
    }

    /** 一帧里的增量正文；没有内容（如只有元数据的收尾帧）时返回空串。 */
    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /**
     * 用量。⚠️ 取不到就是 {@code null}，**不填 0** ——
     * "模型没回报用量"与"用量是 0"在运营统计里是两件事。
     */
    private static ChatDtos.TokenUsage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return null;
        }
        return new ChatDtos.TokenUsage(toInt(usage.getPromptTokens()),
                toInt(usage.getCompletionTokens()), toInt(usage.getTotalTokens()));
    }

    private static String finishReasonOf(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getMetadata() == null) {
            return null;
        }
        return response.getResult().getMetadata().getFinishReason();
    }

    /**
     * 模型配置 id → 数字。
     *
     * <p>⚠️ **解析不出就返回 {@code null}**，这正是当前的常态：引导配置的 id 是
     * {@code bootstrap-chat}（`ModelConfigSpec` 的 javadoc 写明）。前端 `done.modelId`
     * 声明为 number，所以发字符串过去会让类型说谎；发一个编出来的数字更糟。
     * `S6` 把模型配置接进 DB 后主键才是数字，这一跳自然变成恒非空。
     */
    private static Long numericId(String specId) {
        try {
            return Long.valueOf(specId);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer toInt(Number value) {
        return value == null ? null : value.intValue();
    }
}
