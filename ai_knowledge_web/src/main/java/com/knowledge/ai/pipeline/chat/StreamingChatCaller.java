package com.knowledge.ai.pipeline.chat;

import java.util.List;
import java.util.function.Consumer;

/**
 * **一次流式对话调用**的抽象（`T-800`）—— `ChatCaller` 的流式兄弟。
 *
 * <h3>为什么不扩 {@code ChatCaller}，而是新开一个接口</h3>
 * <p>{@code ChatCaller#call} 是"发一次提示词、拿回一整段文本"，它的消费者是
 * {@code IndexEnhancer}（解析增强）—— 那边要的就是整段文本，**不需要流**。
 * 把那个接口改成流式（或让它返回带元数据的对象）会牵动 26 处既有引用与两个用例类，
 * 而收益为零：增强侧一个 token 一个 token 地收反而更麻烦。
 *
 * <p>两会事本来也不同：增强是**系统行为**（不按 id 选模型），问答是**用户行为**
 * （用户可能选了某个模型，`done` 还要回带 `modelId/modelName` 供归因）。
 * 所以是两个接口，而不是一个接口的两个方法。
 *
 * <h3>为什么用回调而不是返回 {@code Flux}</h3>
 * <p>本仓的 SSE 是 **Spring MVC 的 {@code SseEmitter}**（同步栈），不是 WebFlux。
 * 让领域接口暴露 {@code Flux} 会把"要不要打断/背压"这些响应式概念引进一个同步调用链，
 * 而这里真正需要的只有一件事：**每收到一段就交给调用方**。
 * 回调也让用例不必引 Reactor 就能造替身（与 {@code ChatCaller} 同一条理由）。
 */
@FunctionalInterface
public interface StreamingChatCaller {

    /**
     * 发一次提示词，逐段回调，返回本次调用的元数据。
     *
     * <p>**阻塞**直到生成结束。
     *
     * <p>⚠️ 这句话原先续着写「调用方跑在请求线程上（`SseEmitter` 的既有形态）」——
     * `S6-M05-C` 起**不再成立**：`ChatController#completions` 把编排搬到了
     * `AsyncConfig.CHAT_EXECUTOR` 上，正因如此 `SseEmitter` 才能真的边收边发
     * （同步跑在请求线程上时，每一帧都会被 Spring 攒到方法返回后才一次性重放，
     * 见 `SseEmitter` 那处缺陷的完整记录）。**调用方在工作线程上**，
     * 且依赖 `chatContextPropagating` 把身份与 traceId 带过去。
     *
     * @param prompt  完整提示词
     * @param onDelta 每收到一段增量正文调用一次（**不做任何清洗**）
     * @return 本次调用的元数据（`done` 事件要回带的那些）
     * @throws RuntimeException 模型不可用 / 网络失败 —— 调用方决定怎么向用户交代
     */
    Outcome stream(String prompt, Consumer<String> onDelta);

    /**
     * 发一次提示词**并附原图**，逐段回调（`T-806` · 需求 `09` §4.7）。
     *
     * <h3>为什么是"再加一个默认方法"而不是改 {@link #stream}</h3>
     * <p>改签名会牵动全部既有替身与 26 处引用，而**绝大多数调用点根本不该带图**
     * （默认链路只传 VL 描述，见 §4.7）。加默认方法让"不带图的调用点"一个字都不用改，
     * 而"带图的"必须显式走新签名 —— 这个不对称是有意的：
     * **多模态是例外路径，默认路径要保持单一**。
     *
     * <h3>🔴 为什么默认实现是"抛"而不是"退化成纯文本"</h3>
     * <p>需求 `09` §4.10 逐字写着「**最糟的处理是静默忽略图片** —— 用户以为模型看到了图，
     * 实际没有，这类问题极难排查」。退化成纯文本正是那个最糟的处理：它**能跑通、能出答案**，
     * 而答案里少了一个用户明确要求过的东西，页面上看不出来。
     * 所以这里抛 —— 调用方（编排）会把它转成 {@code event: error}，用户**立刻看见**。
     *
     * <p>⚠️ 生产实现必须覆写它（{@code RegistryStreamingChatCaller}）。
     * 不覆写的替身一旦被喂图会**当场炸**，而不是安静地给一个不含图的答案。
     *
     * @param images 原图（**空 / {@code null} 时等价于 {@link #stream(String, Consumer)}**）
     */
    default Outcome stream(String prompt, List<ChatImage> images, Consumer<String> onDelta) {
        if (images == null || images.isEmpty()) {
            return stream(prompt, onDelta);
        }
        throw new UnsupportedOperationException(
                "当前对话调用方不支持直接读原图（未覆写多模态入口）—— "
                        + "本轮若继续，答案会在**没有看到图**的情况下产生，那比失败更糟。"
                        + "请改用支持视觉的链路，或关闭 useVision。");
    }

    /**
     * 一张原图。
     *
     * <h3>为什么带 {@code mime} 而不是让实现自己猜</h3>
     * <p>Spring AI 把 {@code Media} 转成 base64 data-URI 时要写 MIME。猜错（例如把 PNG
     * 声明成 JPEG）的后果**不是报错**，而是模型看到一张解不开的图 ——
     * 现象与"模型没看懂"完全一样。MIME 在附件落库时就有（`sys_chat_attachment.mime`），
     * 一路上都有真值，没有理由在这里猜。
     *
     * @param bytes 原始字节（**已经是图片本体**，不是 base64 串）
     */
    record ChatImage(byte[] bytes, String mime) {
    }

    /**
     * 一次流式调用的元数据。
     *
     * <p>{@code modelName} 目前是**型号标识**（如 {@code deepseek-ai/DeepSeek-V3}）
     * 而不是展示名 —— 引导配置里没有"展示名"这个字段。这与
     * `汇总.md` §3 的 `#10`（`vlModel.name` 返型号标识）是**同一族**已知偏差，
     * 一并等 `S6` 接 `sys_model_config` 后统一。
     *
     * @param modelId      对话模型 id。⚠️ **可空**：后端 `ModelConfigSpec#id` 是字符串
     *                     （引导期是 {@code bootstrap-chat}），而前端 `done.modelId`
     *                     声明为 number —— 解析不出数字时发 {@code null}，
     *                     而不是编一个。`S6` 接 DB 后主键才是数字，届时自然非空
     * @param modelName    模型名（见上）
     * @param tokenUsage   用量；模型未回报时为 {@code null}
     * @param finishReason 结束原因（{@code stop} / {@code length} / …）；未知为 {@code null}
     */
    record Outcome(Long modelId,
                   String modelName,
                   ChatDtos.TokenUsage tokenUsage,
                   String finishReason) {
    }
}
