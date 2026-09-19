package com.knowledge.ai.core.notice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * `sys_notice.actions_json` 这一列的**读写口径（唯一一处）**。
 *
 * <h3>为什么单起一个类</h3>
 * <p>沿用 {@code core/ai/ModelConfigJson} 的先例（那边管 `sys_model_config.params`）：
 * 一列 JSON 有**两个读者**（写侧 {@link NoticePublisher}、读侧 {@code NoticeService}），
 * 序列化各写一份的话，"写进去的"与"读出来的"迟早不是同一个形状 ——
 * 而那种错的表现是**按钮静默消失**（详情页还在、动作没了，没人报错）。
 *
 * <h3>读到坏 JSON 怎么办：**不抛**</h3>
 * <p>退回**空列表**并留 warn。与 {@code ModelConfigJson} 同一条取舍 ——
 * 但这里要**多讲一句**：空列表会让这条通知**违反需求 §2.10 的验收要点**
 * （「每条通知都有至少一个可执行动作」）。之所以仍然选择不抛：那一条验收要点是
 * **写侧的约束**（`actions_json` 列上卡了 `NOT NULL`，{@link NoticePublisher} 也只产出
 * 至少一个动作），读到空说明**数据被外力写坏**，此时"整页 500"比"少几个按钮"糟得多。
 * 但必须留痕 —— 静默退回空列表会让人以为这本来就没动作。
 */
@Slf4j
public final class NoticeActionsJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NoticeActionsJson() {
    }

    /**
     * 动作数组 → 列值。
     *
     * @param actions 至少一个（写侧保证）；{@code null} / 空列表写成一串空数组 {@code []}
     *                —— ⚠️ 那会撞 `NOT NULL` 之外**没有**的约束，所以调用方不该传空，
     *                {@link NoticePublisher} 在构造动作前就拒绝产出无动作的通知
     */
    public static String write(List<NoticeAction> actions) {
        try {
            return MAPPER.writeValueAsString(actions == null ? List.of() : actions);
        } catch (Exception ex) {
            // 这里几乎不可能发生（都是自己写的 record），但绝不把异常抛给"发通知"这条路 ——
            // 发通知失败不该连累任务终态（见 NoticePublisher 的取舍）。
            log.warn("通知动作序列化失败，落空数组：err={}", ex.getMessage());
            return "[]";
        }
    }

    /**
     * 列值 → 动作数组。
     *
     * @param json 列值；{@code null} / 空 / 坏 JSON / 不是数组 一律返回**空列表**（并留痕）
     */
    public static List<NoticeAction> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<NoticeAction> parsed = MAPPER.readValue(json, new TypeReference<List<NoticeAction>>() {
            });
            return parsed == null ? List.of() : parsed;
        } catch (Exception ex) {
            log.warn("通知动作反序列化失败，退回空列表（这条通知在页面上会缺按钮）："
                    + "err={} json={}", ex.getMessage(), json);
            return List.of();
        }
    }
}
