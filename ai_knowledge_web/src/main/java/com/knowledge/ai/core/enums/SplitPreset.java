package com.knowledge.ai.core.enums;

import lombok.Getter;

/**
 * 切分预设。取值与前端 {@code SplitPreset} / {@code SplitPresetKey} 逐字一致。
 *
 * <p>前 5 个是内置预设，{@code CUSTOM} 表示用户手工调参后的状态
 * （前端 {@code SplitPresetKey = SplitPreset | 'CUSTOM'}）。
 */
@Getter
public enum SplitPreset {

    GENERAL("通用", "按固定窗口切分，适合大多数说明性文档"),
    STRUCTURED("结构化", "按标题层级切分并注入标题路径，适合手册 / 规范"),
    CONTRACT("合同条款", "按条款编号切分，适合合同 / 制度"),
    TABLE_HEAVY("表格密集", "表格不参与语义切分，按行组切块"),
    QA_PAIR("问答对", "识别 Q/A 成对结构，适合 FAQ"),
    CUSTOM("自定义", "手工调整参数后的状态"),
    ;

    private final String label;

    private final String description;

    SplitPreset(String label, String description) {
        this.label = label;
        this.description = description;
    }

    /** 是否为内置预设（{@code CUSTOM} 不是）。 */
    public boolean builtin() {
        return this != CUSTOM;
    }
}
