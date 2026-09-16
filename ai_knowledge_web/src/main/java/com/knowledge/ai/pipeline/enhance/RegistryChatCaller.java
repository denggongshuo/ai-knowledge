package com.knowledge.ai.pipeline.enhance;

import com.knowledge.ai.core.ai.ModelClientRegistry;
import lombok.RequiredArgsConstructor;
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
 */
@Component
@RequiredArgsConstructor
public class RegistryChatCaller implements ChatCaller {

    private final ModelClientRegistry registry;

    @Override
    public String call(String prompt) {
        // 返回 content（纯文本）而不是 ChatResponse：调用方只关心文本，
        // 元数据（模型名等）在这里没有消费者。真要看模型名，注册中心的 status() 里有。
        return registry.chatClient().prompt().user(prompt).call().content();
    }
}
