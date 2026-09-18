package com.knowledge.ai.pipeline.enhance;

import com.knowledge.ai.service.ModelUsageRecorder;

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

    /**
     * 带**用途**的一次调用（`T-905`）—— 让实现方能把它记进用量统计。
     *
     * <h3>为什么是 {@code default} 方法而不是改 {@link #call(String)} 的返回类型</h3>
     * <p>本接口是 {@link FunctionalInterface}，改抽象方法签名会让**所有用 lambda 造的替身
     * 当场编译不过**（`IndexEnhancerTest` / `IndexEnhanceFlowTest` / `RetrievalTestStubs` 里
     * 有好几处）。这与 {@code EmbeddingClient#modelRef()} 当初选 {@code default} 是同一条理由
     * ——`T-507` 的注释原话是「用一个 default 方法既拿回了信息，又保住了『假 client 可以用
     * lambda 写』」。于是这里照同一形状加一个**重载**，默认实现直接委托给
     * {@link #call(String)}。
     *
     * <p>⚠️ 默认实现**不记用量**（它什么都做不了）—— 所以只有生产实现
     * （{@code RegistryChatCaller}）覆盖它才真的有数。这在用例侧是**有意**的：
     * 替身不该往库里写行。但如果将来出现第二个生产实现而忘了覆盖，
     * 表现是"这一路的用量永远是 0"而不是报错 —— 登记在计划 §9。
     *
     * @param purpose 这次调用属于哪个用途桶（摘要 / HyQ）。**必填** ——
     *                归不了桶的用量没法进 `byPurpose`，而 `byPurpose` 正是本批要交的东西
     */
    default String call(String prompt, ModelUsageRecorder.Purpose purpose) {
        return call(prompt);
    }
}
