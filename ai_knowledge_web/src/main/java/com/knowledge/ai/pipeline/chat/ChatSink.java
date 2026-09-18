package com.knowledge.ai.pipeline.chat;

/**
 * 一次问答要往外发的四件事（`T-801` 的事件序列）。
 *
 * <h3>为什么要这一层，而不是让编排直接使唤 {@code SseEmitter}</h3>
 * <p>两个理由，都不是"为了好看"：
 * <ol>
 *   <li><b>可测</b>：`G5-11`（空召回不调模型）与"断连仍留痕"两条判据都要求
 *       断言**发出去的事件序列**。用替身收进一个 `List` 比启一个 servlet 容器便宜得多；</li>
 *   <li><b>断连必须在收尾之前被吃掉</b>：{@code SseEmitter.send} 在客户端已断开时抛
 *       {@code IOException}。若让它冒泡出编排，`finally` 里的
 *       **打点落库与消息落库就跑不到** —— 而那正是 `G5-13`「无遗漏」要的东西。
 *       所以实现方负责**就地吞掉发送失败并把自己标记为已死**
 *       （{@link #alive()}），编排只管一路往下走到收尾。</li>
 * </ol>
 *
 * <p>⚠️ {@link #alive()} 返回 {@code false} 之后，实现方仍要接受后续调用（no-op）——
 * 编排不判 {@code alive()} 也要能走完，否则"谁负责判"又会变成两处。
 */
public interface ChatSink {

    /**
     * `event: retrieval`。**首包必须是它**（不带图片时，`G5-7`），
     * 并在 `done` 之前**再发一次终包**（把首包拿不到的生成耗时补上，见 {@link ChatWireMapper}）。
     */
    void retrieval(ChatDtos.RetrievalEvent event);

    /**
     * `event: attachment` —— 图片理解的进度（`T-907` · 需求 `09` §4.9）。
     *
     * <p><b>带图提问时它比 {@link #retrieval} 更早发出</b>：VL 描述是串行阻塞在
     * 检索之前的（1~3 秒），而合成 query 依赖它。不提前给进度，用户会盯着空白等。
     *
     * <p>默认实现是**空操作** —— 本接口有若干只关心"检索 → 生成"的既有替身
     * （用例里的 `List<Object>` 收集器），逼它们全部实现一个新方法是纯负担。
     * ⚠️ 但**生产实现必须覆盖它**（{@code ChatController.EmitterSink}），
     * 否则用户在页面上永远看不到「正在理解图片」。
     */
    default void attachment(ChatDtos.AttachmentEvent event) {
        // 默认空操作：见 javadoc
    }

    /** `event: delta` —— 一小段增量正文。可发多次。 */
    void delta(String content);

    /** `event: done` —— 一轮结束（正常结束与中途出错都会发）。 */
    void done(ChatDtos.DoneEvent event);

    /** `event: error` —— 出错时的可见信号（§9 此前未登记，本批补上）。 */
    void error(String message);

    /**
     * 连接是否还活着。已断开时实现方应停止真正发送，但**不得**打断编排的收尾。
     *
     * <p>默认 {@code true}：只有真的架在 HTTP 连接上的实现（{@code SseEmitter}）才会断开，
     * 用例里的替身恒为活。
     */
    default boolean alive() {
        return true;
    }
}
