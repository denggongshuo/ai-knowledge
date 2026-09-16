package com.knowledge.ai.pipeline.enhance;

/**
 * **一次对话调用**的抽象（`T-507`）。
 *
 * <h3>为什么不直接在 {@link IndexEnhancer} 里用 Spring AI 的 {@code ChatClient}</h3>
 * <p>因为 `T-507` 的判据主体是**组装逻辑** —— 拆块、拼提示词、把模型吐回来的东西装成
 * {@link com.knowledge.ai.pipeline.Block}。那些逻辑必须是**确定性、可单测、不联网**的
 * （计划 §3.4 假设 3）。
 *
 * <p>{@code ChatClient} 是一串流式 API（{@code prompt().user(..).call().chatResponse()}），
 * 想在测试里替换掉它得去 mock 整条链；而本仓的 surefire **没有** {@code excludedGroups}，
 * 任何"真调模型"的用例在默认跑里都会真的打出去（本机走 relay）。
 * 把这一个入口收成函数式接口之后：组装逻辑用假实现跑（毫秒级、可断言提示词原文），
 * 真实调用单独一条 {@code @Tag("network")} 冒烟。
 *
 * <h3>为什么不是"返回结构化对象"的接口</h3>
 * <p>因为模型的输出本来就是**一段文本** —— 让它看起来像结构化的是解析层的事，
 * 不该把这层伪装推给调用方。解析失败是 `T-507` 必须自己面对的真实情况
 * （见 {@link IndexEnhancer} 对 {@code BAD_OUTPUT} 的处理）。
 */
@FunctionalInterface
public interface ChatCaller {

    /**
     * 发一次提示词，取回模型输出的文本。
     *
     * @param prompt 完整提示词（含输出格式要求）
     * @return 模型的原始文本输出；**不做任何清洗**（剥围栏、截首尾由解析层负责）
     * @throws RuntimeException 模型不可用 / 网络失败 —— 调用方负责决定要不要让文档失败
     */
    String call(String prompt);
}
