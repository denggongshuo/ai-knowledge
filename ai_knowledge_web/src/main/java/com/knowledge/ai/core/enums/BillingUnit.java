package com.knowledge.ai.core.enums;

import lombok.Getter;

/**
 * 计费单位。取值与前端 {@code BillingUnit} 逐字一致。
 *
 * <p>三态：
 * <pre>
 *   PER_TOKEN  按 token 计费（多数 LLM / 嵌入模型）
 *   PER_CALL   按调用次数计费（部分重排 / OCR 服务）
 *   NONE       不产生费用（自建 / 包月 / 免费额度）
 * </pre>
 *
 * <p><b>口径提醒</b>：页面上展示的是**费用估算**，是派生指标，不是账单。
 * 措辞上不要写成"实际费用"（《01-示例数据字典》）。
 */
@Getter
public enum BillingUnit {

    PER_TOKEN("按 Token", "token"),
    PER_CALL("按调用", "次"),
    NONE("不计量", ""),
    ;

    private final String label;

    /** 单位后缀，用于拼接「1.24 元 / 百万 token」这类文案。 */
    private final String unit;

    BillingUnit(String label, String unit) {
        this.label = label;
        this.unit = unit;
    }

    public boolean billable() {
        return this != NONE;
    }
}
