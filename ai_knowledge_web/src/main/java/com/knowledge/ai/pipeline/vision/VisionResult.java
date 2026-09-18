package com.knowledge.ai.pipeline.vision;

/**
 * 一次图片理解的产出（需求 `09` FR-SYS-04 §4.6 的 `attachments[]` 形状）。
 *
 * <h3>为什么 {@code desc} 与 {@code searchSummary} 是**一次调用**的两个字段</h3>
 * <p>§4.4 的做法是「用『最后一行输出关键词』的单行约定」，后端切最后一行：
 * 前面的全部是 {@code desc}（**给生成用**，信息完整），最后一行去掉前缀是
 * {@code searchSummary}（**给检索用**，关键词密集）。两者要求本就不同，
 * 一次调用同时产出，不增加成本。
 *
 * <h3>⚠️ 降级时 {@code desc} 是空串而 {@code degraded} 是 {@code true}</h3>
 * <p>这与 {@code sys_chat_attachment.description} 为 {@code NULL} 是**两件事**，
 * 别把它们看成一件事：
 * <ul>
 *   <li>库里那一列 {@code NULL} = **还没有被理解过**（从没调用过 VL）；</li>
 *   <li>这个 record 的 {@code degraded = true} + 空 {@code desc} = **理解过，但没成**
 *       （VL 未配置 / 调用失败 / 返回空，§4.10 第 3 行）—— 它**要落库**，
 *       因为「试过了、没成」正是「重试理解图片」那个入口存在的理由。</li>
 * </ul>
 *
 * @param desc          完整结构化描述（给生成用）。降级时为空串
 * @param searchSummary 检索关键词。解析不出关键词行时**回落成 {@code desc} 整体**
 *                      （§4.10 第 4 行：「不必失败」）
 * @param vlModelName   产出它的 VL 模型名
 * @param degraded      是否降级产出
 * @param promptVersion 生成时的 {@link VisionPrompt#PROMPT_VERSION}，便于回溯
 */
public record VisionResult(String desc,
                           String searchSummary,
                           String vlModelName,
                           boolean degraded,
                           String promptVersion) {

    /**
     * 降级产出（VL 不可用 / 调用失败 / 返回空）。
     *
     * <p>⚠️ 走这条路时**不抛异常** —— 需求 §4.10 的原话是「**降级**：标
     * `degraded = true`，**照常回答** + 提示『图片理解不可用，已按文字问题回答』」。
     * 抛异常就等于拿一次图片理解失败炸掉整个问答，那是比"少一份描述"严重一个量级的后果
     * （与 `S4-D`「查不存在的分区是报错不是返回空」是同一条纪律的反面：
     * 这里的失败**可以**降级，因为降级后用户仍然得到答案）。
     *
     * <p>降级的原因**不进响应体** —— 用户看到的是统一的那句提示（§4.10）。
     * 原因由调用方（{@link VisionDescriber}）写进日志，那里才有上下文。
     */
    public static VisionResult degraded(String vlModelName) {
        return new VisionResult("", "", vlModelName, true, VisionPrompt.PROMPT_VERSION);
    }

    /** 描述文本是否可用（降级产出的不算）。 */
    public boolean hasDescription() {
        return !degraded && desc != null && !desc.isBlank();
    }
}
