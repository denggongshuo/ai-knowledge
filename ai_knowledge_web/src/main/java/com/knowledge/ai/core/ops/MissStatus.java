package com.knowledge.ai.core.ops;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;

/**
 * 未命中问题的状态机（`T-A00` · 需求 `08` §1.3）。
 *
 * <pre>
 *                 ┌──── 去补文档 ────→ [已加入清单] ──重建索引 + 重放──→ [已解决]
 *                 │                          │                            │
 * [待处理 PENDING] │                    重放未达标 ──→ 回到 [待处理]         │
 *                 │                                                       │
 *                 └──── 忽略 ────→ [已忽略 IGNORED] ──恢复──→ [待处理]      │
 * </pre>
 *
 * <h3>为什么状态值用字符串而不是序号</h3>
 * <p>它要落库、要进 JSON、还要与前端 {@code types/index.ts} 的
 * {@code MissStatus} 逐字对齐（`PENDING`/`IN_LIST`/`RESOLVED`/`IGNORED`）。
 * 存序号会让"库里是 1、前端以为是 IN_LIST"这类错位**看起来一切正常**。
 *
 * <h3>🔴 本批只交付"人工触发"的那几条边</h3>
 * <p>需求 §1.3 里两条边是**自动**的：「重放未达标 → 回到待处理」与
 * 「分数达标自动关闭」—— 两条都长在**重放验证**上，而重放是 `T-A01`。
 * 本批把状态机**完整建出来**（四条状态 + 合法的流转规则），但
 * `RESOLVED` 由人点、自动那两条边**没接线**。差别是"能人工走完"与"能自动收敛"，
 * 别把前者当成后者。
 *
 * <h3>为什么"恢复"只在 IGNORED 上开</h3>
 * <p>需求 §1.3 的画里只有「已忽略 --恢复--> 待处理」一条回边，理由是
 * 「避免同一问题被反复忽略又反复冒出」：被忽略的**不计入待办数**
 * （§1.3 的规则表），所以它必须有一条**显式**的路回来 —— 否则那个问题就永久沉底了。
 */
public enum MissStatus {

    /** 待处理 —— 自动入池后的初始状态，**计入待办数**（驱动侧边栏角标）。 */
    PENDING("待处理"),

    /** 已加入清单 —— 人已认领、准备去补文档，**仍计入待办数**（还没解决）。 */
    IN_LIST("已加入清单"),

    /** 已解决 —— 补录并重建后由重放验证确认（本批由人工标记）。 */
    RESOLVED("已解决"),

    /** 已忽略 —— 不计入待办数，但仍保留在清单里可查；必须记**忽略人**。 */
    IGNORED("已忽略");

    private final String label;

    MissStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 解析入参；不认识的值**报错**而不是默认成 PENDING（静默兜底会让拼错的状态被"接受"）。 */
    public static MissStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "状态不能为空，取值：PENDING / IN_LIST / RESOLVED / IGNORED");
        }
        for (MissStatus value : values()) {
            if (value.name().equalsIgnoreCase(raw.trim())) {
                return value;
            }
        }
        throw new BizException(ErrorCode.PARAM_INVALID,
                "不认识的状态「" + raw + "」，取值：PENDING / IN_LIST / RESOLVED / IGNORED");
    }

    /** 用来筛列表的入参：空/`all` 表示不筛。 */
    public static MissStatus parseFilter(String raw) {
        if (raw == null || raw.isBlank() || "all".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        return parse(raw);
    }

    /**
     * 是否计入「待我处理」（需求 §1.3：「被忽略的问题**不计入待办数**」）。
     *
     * <p>⚠️ 判据是**状态本身**，不是"有没有被忽略过"：`IN_LIST`（已认领、还没补完）
     * 仍然算待办 —— 它不是"不用管了"，只是"有人在管了"。
     */
    public boolean countsAsPending() {
        return this == PENDING || this == IN_LIST;
    }
}
