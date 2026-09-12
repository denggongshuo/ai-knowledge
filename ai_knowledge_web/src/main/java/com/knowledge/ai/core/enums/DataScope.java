package com.knowledge.ai.core.enums;

import lombok.Getter;

/**
 * 数据范围（角色的属性，不是部门属性）。取值与前端 {@code DataScope} 逐字一致。
 *
 * <p>叠加规则（《05-需求-权限与审计》§0.5）：同一用户多角色时**取最宽**。
 * 宽窄顺序：{@code ALL > DEPT_AND_CHILD > DEPT > SELF}，{@code CUSTOM} 由角色额外配置。
 */
@Getter
public enum DataScope {

    ALL("全部数据", 4),
    DEPT_AND_CHILD("本部门及以下", 3),
    DEPT("本部门", 2),
    SELF("仅本人", 1),
    CUSTOM("自定义", 0),
    ;

    private final String label;

    /** 宽窄权重，用于多角色取最宽。 */
    private final int breadth;

    DataScope(String label, int breadth) {
        this.label = label;
        this.breadth = breadth;
    }

    /** 多角色叠加：取最宽。{@code CUSTOM} 视为 0，不参与比较。 */
    public static DataScope widest(DataScope left, DataScope right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.breadth >= right.breadth ? left : right;
    }
}
