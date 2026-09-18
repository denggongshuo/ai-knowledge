package com.knowledge.ai.core.enums;

import lombok.Getter;

import java.util.Arrays;

/**
 * 点踩原因（`T-807`，`G5-14` 的 `reason`）。
 *
 * <p>取值与需求《07-检索与问答》§6.8 与《01-示例数据字典》§7.11 **逐字一致** ——
 * 那两处各列了一份，措辞相同，故以它们为准。
 *
 * <p>⚠️ **前端 `mock/data.ts` 的展示串与之有漂移**（它写的是「回答太笼统」「引用不相关」，
 * 而需求两处都是「回答太啰嗦」「引用来源不对」）。本枚举取**需求**那一份：
 * mock 是运营页在 S8 之前的占位数据，不是口径来源。
 *
 * <h3>为什么不存代号而存标签本身</h3>
 * <p>{@link #getLabel()} 就是落库值。与 {@code AuditModule} 直接以中文作取值同一条口径 ——
 * 运营页要按原因分组，存代号就得再维护一张映射表，而那张表没有任何第二个消费方。
 *
 * <h3>为什么是"闭集"</h3>
 * <p>`G5-14` 要求"运营页可统计"，而自由文本的 reason 统计出来是一堆同义不同写的长尾。
 * 所以服务层**白名单校验**，不在集合内一律拒绝 —— 前端给的是选择项，不是输入框。
 */
@Getter
public enum ChatFeedbackReason {

    INACCURATE("答案不准确"),
    NOT_FOUND("没找到相关内容"),
    WRONG_CITATION("引用来源不对"),
    TOO_VERBOSE("回答太啰嗦"),
    OTHER("其他"),
    ;

    /** 落库值，也是前端提交值。 */
    private final String label;

    ChatFeedbackReason(String label) {
        this.label = label;
    }

    /** 标签是否合法（{@code null} 不算 —— 点踩必须带原因）。 */
    public static boolean isValidLabel(String label) {
        return label != null && Arrays.stream(values()).anyMatch(r -> r.label.equals(label));
    }
}
