package com.knowledge.ai.pipeline.enhance;

import com.knowledge.ai.core.ai.ModelClientRegistry;
import com.knowledge.ai.core.ai.ModelConfigSpec;
import com.knowledge.ai.core.enums.ModelType;
import com.knowledge.ai.service.ModelUsageRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

/**
 * {@link ChatCaller} 的生产实现 —— 唯一一处真的碰模型客户端。
 *
 * <h3>为什么必须是单独一个 Spring 组件，而不是 {@code IndexEnhancer} 里的一个方法</h3>
 * <p>`T-014` 的验收要点原话：**任何模块要调模型，都经 {@code ModelClientRegistry} 取客户端，
 * 不得自己 {@code new}**。把"唯一碰注册中心的地方"收成一个组件之后，
 * {@link IndexEnhancer} 就不再持有注册中心、也就不可能绕过它 ——
 * 依赖关系本身成了那条纪律的保证。
 *
 * <p>取的是 {@code chatClient()}（当前启用的对话模型），**不按 id 选**：
 * 解析增强是系统行为，不是某个用户在问答页选了哪个模型。
 *
 * <h3>`T-905`：用量在这里记，不在 {@link IndexEnhancer} 里记</h3>
 * <p>因为**只有这一层拿得到 token 用量** —— 它得用 {@code chatResponse()} 而不是
 * {@code content()}（后者只给文本）。用量在下面不带出来，上面再想补也补不回来。
 * 而"哪次调用算摘要、哪次算 HyQ"只有 {@link IndexEnhancer} 知道，
 * 所以它通过 {@link #call(String, ModelUsageRecorder.Purpose)} 把用途传下来。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegistryChatCaller implements ChatCaller {

    private final ModelClientRegistry registry;

    private final ModelUsageRecorder usageRecorder;

    @Override
    public String call(String prompt) {
        // 不带用途的调用（如既有的 {@code @Tag("network")} 冒烟）走这条：
        // **不记用量** —— 归不了桶的用量会让 byPurpose 出现一个说不清来源的增量
        return text(callRaw(prompt));
    }

    @Override
    public String call(String prompt, ModelUsageRecorder.Purpose purpose) {
        ChatResponse response = callRaw(prompt);
        record(response, purpose);
        return text(response);
    }

    private ChatResponse callRaw(String prompt) {
        return registry.chatClient().prompt().user(prompt).call().chatResponse();
    }

    /**
     * 取文本。**不做任何清洗** —— 剥围栏、截首尾是 {@link IndexEnhancer} 解析层的职责
     * （`ChatCaller` 的类注释写死了这条边界）。
     */
    private static String text(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /**
     * 记用量（`T-905`）。
     *
     * <p>⚠️ 记用量**绝不能影响增强本身**：整段包在 try 里，任何异常只留 warn。
     * 摘要是解析链路上的一跳，为了统计让它失败是本末倒置
     * （与 {@code TaskContext#persistProgress} 同一条取舍）。
     */
    private void record(ChatResponse response, ModelUsageRecorder.Purpose purpose) {
        try {
            ModelConfigSpec spec = registry.activeSpec(ModelType.CHAT);
            Usage usage = usageOf(response);
            usageRecorder.record(spec, purpose, 1,
                    toLong(usage == null ? null : usage.getPromptTokens()),
                    toLong(usage == null ? null : usage.getCompletionTokens()),
                    null);
        } catch (Exception ex) {
            log.warn("增强用量记录失败（不影响文档入库）：purpose={} 原因={}", purpose, ex.getMessage());
        }
    }

    private static Usage usageOf(ChatResponse response) {
        return response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
    }

    /** 没回报时给 0（本表的 SUM 要能直接累加，见 {@code SysModelUsage} 类注释）。 */
    private static long toLong(Number value) {
        return value == null ? 0L : value.longValue();
    }
}
