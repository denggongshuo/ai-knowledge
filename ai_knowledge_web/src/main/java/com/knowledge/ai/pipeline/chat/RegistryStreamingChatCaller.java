package com.knowledge.ai.pipeline.chat;

import com.knowledge.ai.core.ai.ModelClientRegistry;
import com.knowledge.ai.core.ai.ModelConfigSpec;
import com.knowledge.ai.core.enums.ModelType;
import com.knowledge.ai.service.ModelUsageRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.util.List;
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
 * <p>⚠️ 本段原话是「{@code SseEmitter} 是同步栈：**请求线程**负责边收边发」——
 * `S6-M05-C` 起那句里"请求线程"已经不成立（编排搬到了
 * {@code AsyncConfig.CHAT_EXECUTOR} 上）。**但结论不变、而且更强**：
 * 这是"同步阻塞式"的栈，所以用 {@code subscribe()} 依然会让
 * 回调落在别的线程上，{@code SseEmitter} 的并发 `send` 与"什么时候算结束"都会变得难说。
 * 阻塞消费让"循环走完 = 生成结束 = 可以发 done"成为一条直白的因果。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegistryStreamingChatCaller implements StreamingChatCaller {

    private final ModelClientRegistry registry;

    /** `T-905` 的用量落点。见 {@link #recordUsage}。 */
    private final ModelUsageRecorder usageRecorder;

    @Override
    public Outcome stream(String prompt, Consumer<String> onDelta) {
        return stream(prompt, List.of(), onDelta);
    }

    /**
     * 带原图的流式调用（`T-806` · 需求 `09` §4.7）。
     *
     * <h3>🔴 原图走**字节**，不走预签名 URL</h3>
     * <p>步 0 探针实测：本机 relay（`api.deepseek.com`）**自己下载不了外网图** ——
     * `image_url` 给 `http(s)` 形式直接 HTTP 400 `Failed to download image`。
     * 而 MinIO 的预签名 URL 指向 `127.0.0.1:9000`，对公网供应商**必然不可达**
     * （这与 `#178` 那条已知偏差是同一件事，只是那次发生在 VL 链路上）。
     * ⇒ 只能走 base64 数据 URL，而 Spring AI 的 `media(MimeType, Resource)` 正是这条通路
     * （{@code RegistryVisionCaller#invokeBytes} 已经在用）。
     *
     * <h3>为什么用 {@code .user(spec -> …)} 而不是 {@code .user(String)}</h3>
     * <p>后者只收文本，**没有挂 Media 的位置**。这与 VL 链路用的是同一组 API，
     * 不引入新的调用形态。
     */
    @Override
    public Outcome stream(String prompt, List<ChatImage> images, Consumer<String> onDelta) {
        // 先取 spec 再取客户端：`activeSpec` 在未配置对话模型时**抛**
        // MODEL_NOT_CONFIGURED（硬失败，见 ModelClientRegistry 的兜底口径），
        // 与其拿到一个含混的空响应，不如让调用方立刻知道"没配模型"
        ModelConfigSpec spec = registry.activeSpec(ModelType.CHAT);
        ChatClient client = registry.chatClient(spec.id());

        // 流里的每个 ChatResponse 都带着"到目前为止"的结果，而 usage 通常只挂在最后一帧
        // —— 所以留一份引用，循环结束后从它取元数据
        AtomicReference<ChatResponse> lastFrame = new AtomicReference<>();
        for (ChatResponse response : request(client, prompt, images).stream().chatResponse().toIterable()) {
            lastFrame.set(response);
            String piece = textOf(response);
            if (!piece.isEmpty()) {
                onDelta.accept(piece);
            }
        }

        ChatResponse last = lastFrame.get();
        Outcome outcome = new Outcome(numericId(spec.id()), spec.model(),
                usageOf(last), finishReasonOf(last));
        log.info("[问答] 生成结束：model={} finish={} tokens={} 原图={}张",
                outcome.modelName(), outcome.finishReason(), outcome.tokenUsage(),
                images == null ? 0 : images.size());
        recordUsage(spec, outcome);
        return outcome;
    }

    /**
     * 组装这一次请求 —— 带图与不带图**唯一的差别在这里**。
     *
     * <p>不带图时走 `.user(prompt)`（既有形态，逐字不变）；带图时走
     * `.user(spec -> spec.text(prompt).media(...))`。分成两个分支而不是"总是用 lambda"：
     * 后者会让**全部既有问答**都换一条代码路径，而它们与本次改动无关。
     */
    private static ChatClient.ChatClientRequestSpec request(ChatClient client, String prompt,
                                                            List<ChatImage> images) {
        if (images == null || images.isEmpty()) {
            return client.prompt().user(prompt);
        }
        return client.prompt().user(spec -> {
            spec.text(prompt);
            for (ChatImage image : images) {
                MimeType mime = mimeOf(image.mime());
                spec.media(mime, namedResource(image.bytes(), mime));
            }
        });
    }

    /**
     * 附件声明的 MIME 字符串 → Spring AI 的 {@link MimeType}。
     *
     * <p>解析失败（空 / 认不出的串）**退回 {@code image/jpeg} 并留 warn** ——
     * 不抛：附件表里的 `mime` 是客户端上传时声明的，它脏了不该让整轮问答炸掉。
     * ⚠️ 但退回的后果要说清：模型可能收到一张解不开的图，现象与"没看懂"一样，
     * 所以 warn 里必须带**原始串**，好让"这次为什么没看图"能被查证。
     */
    private static MimeType mimeOf(String raw) {
        try {
            return raw == null || raw.isBlank() ? MimeTypeUtils.IMAGE_JPEG : MimeTypeUtils.parseMimeType(raw);
        } catch (Exception ex) {
            log.warn("[问答] 附件 MIME 解析失败，按 image/jpeg 传给模型（原始值「{}」）：{}",
                    raw, ex.getMessage());
            return MimeTypeUtils.IMAGE_JPEG;
        }
    }

    /**
     * 给字节流一个**带扩展名**的文件名。
     *
     * <p>与 {@code RegistryVisionCaller#namedResource} 同一条理由：裸
     * {@code ByteArrayResource#getFilename()} 返回 {@code null}，而 Spring AI 的 OpenAI
     * 适配在把 Resource 转成 base64 data-URI 时要读文件名判类型，某些版本上会抛。
     * 扩展名从 MIME 推 —— 传 `image.jpg` 而实际是 PNG，有些供应商也会翻脸。
     */
    private static ByteArrayResource namedResource(byte[] image, MimeType mime) {
        String sub = mime.getSubtype() == null ? "" : mime.getSubtype().toLowerCase(java.util.Locale.ROOT);
        String ext = switch (sub) {
            case "png" -> "png";
            case "gif" -> "gif";
            case "webp" -> "webp";
            case "bmp" -> "bmp";
            default -> "jpg";
        };
        return new ByteArrayResource(image) {
            @Override
            public String getFilename() {
                return "image." + ext;
            }
        };
    }

    /**
     * 记一次问答生成用量（`T-905`）。
     *
     * <p>⚠️ 记在**这一层**而不是编排层：只有这里同时握着 {@code spec} 与 {@code usage}
     * —— 编排层手上只有 {@code Outcome}（有 usage 没有 spec）。为了统计把注册中心
     * 再塞进编排层，等于让"谁在碰模型客户端"多一处。
     *
     * <p>⚠️ 失败只 warn：一次生成已经在流里发完了，为了记一行统计把它变成错误，
     * 用户看到的是"答案突然断了"。与 {@code TaskContext#persistProgress} 同一条取舍。
     */
    private void recordUsage(ModelConfigSpec spec, Outcome outcome) {
        try {
            ChatDtos.TokenUsage usage = outcome.tokenUsage();
            usageRecorder.record(spec, ModelUsageRecorder.Purpose.CHAT, 1,
                    usage == null || usage.prompt() == null ? 0L : usage.prompt(),
                    usage == null || usage.completion() == null ? 0L : usage.completion(),
                    null);
        } catch (Exception ex) {
            log.warn("问答用量记录失败（不影响本轮回答）：model={} 原因={}",
                    outcome.modelName(), ex.getMessage());
        }
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
