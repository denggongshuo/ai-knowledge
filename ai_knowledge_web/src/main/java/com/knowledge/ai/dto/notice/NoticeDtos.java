package com.knowledge.ai.dto.notice;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 通知中心的接口契约（需求《09》§2.8 的七条端点）。
 *
 * <h3>字段名为什么逐字照抄前端</h3>
 * <p>这些 record 的字段名与前端 {@code types/index.ts} 的
 * {@code NoticeItem} / {@code NoticeCounts} / {@code PendingItem} / {@code NoticeSetting}
 * **一字不差** —— 它们是接口契约（《10-接口约定汇总》§11），不是内部模型。
 * 少一个字段前端不会报错，只会**静默少渲染一块**（`#159` 那条"透传链路"的家族病），
 * 所以对字段名不做"优化"。
 *
 * <h3>⚠️ 两个字段是**服务端算出来的展示值**，不是库里的列</h3>
 * <ul>
 *   <li>{@code NoticeItem.group}（今天 / 昨天 / 更早）—— 需求 §2.4 要求按时间分组扫读。
 *       它在库里没有列，也**不该有**：那是"相对今天"的函数，落库当天是对的、第二天就错了。</li>
 *   <li>{@code NoticeItem.time}（`yyyy-MM-dd HH:mm`）—— 格式由服务端定死，
 *       前端直接显示（与 `time` 在 mock 里的形状一致）。</li>
 * </ul>
 *
 * <h3>可选字段用包装类型，不用原始类型</h3>
 * <p>{@code NoticeSetting.forced} / {@code NoticeItem.needAction} 在契约里是「有就带」的字段
 * （前端判 `if (n.muteable)`）。用 {@code Boolean} 才能在"没有这个属性"与"false"之间区分 ——
 * 不过本实现一律给值，包装类型只是**保住将来不发它的余地**（与 `#141` 那条
 * 「不发而不是发 0」同一条口径）。
 */
public final class NoticeDtos {

    private NoticeDtos() {
    }

    // ==================== 列表 · `GET /notices` ====================

    /** 一条通知（对应前端 {@code NoticeItem}）。 */
    public record NoticeItem(
            long id,
            String type,
            String title,
            String content,
            String time,
            boolean read,
            /** 今天 / 昨天 / 更早 —— 服务端按 `created_at` 算，库里没有这一列 */
            String group,
            /** 至少一个（需求 §2.10）；空数组说明数据被外力写坏，见 {@code NoticeActionsJson} */
            List<Action> actions,
            Boolean needAction,
            Boolean muteable) {
    }

    /** 通知上的动作按钮。 */
    public record Action(String label, String to, boolean primary) {
    }

    /** 六个计数（需求 §2.3）。⚠️ 字段名是**大写的类型名** —— 前端就按这个名字读。 */
    public record NoticeCounts(long all, long unread, long UPLOAD, long ALERT,
                              long PERMISSION, long SYSTEM) {
    }

    /** `GET /notices` 的响应体。 */
    public record NoticeListResponse(List<NoticeItem> list, long total, NoticeCounts counts) {
    }

    // ==================== 顶栏角标 ====================

    /** `GET /notices/unread-count`。 */
    public record UnreadCountResponse(long count) {
    }

    // ==================== 待我处理 ====================

    /** 「待我处理」聚合的一项。 */
    public record PendingItem(String key, String label, long count, String desc,
                              String to, String btnText) {
    }

    /** `GET /notices/pending`。 */
    public record PendingResponse(List<PendingItem> list, long total) {
    }

    // ==================== 已读 ====================

    /** `PATCH /notices/{id}/read`。 */
    public record ReadResponse(long id, boolean read, long unread) {
    }

    /** `POST /notices/read-all`。 */
    public record ReadAllResponse(long updated, long unread) {
    }

    // ==================== 设置 ====================

    /** 一条设置（对应前端 {@code NoticeSetting}）。 */
    public record NoticeSetting(String type, String label, boolean enabled,
                                /** 强制开启（系统告警）—— 前端据此置灰开关 */
                                Boolean forced,
                                /** 输入框下方的说明文案 */
                                String hint) {
    }

    /** `GET /notices/settings`。 */
    public record SettingsResponse(List<NoticeSetting> list) {
    }

    /** `PUT /notices/settings` 的请求体。 */
    public record SettingsSaveRequest(@NotNull List<Item> list) {

        public record Item(@NotNull String type, @NotNull Boolean enabled) {
        }
    }

    /**
     * `PUT /notices/settings` 的响应体。
     *
     * <p>{@code applied} 只含**真的生效了**的那些 —— 关掉「系统告警」的请求会被剔除。
     * 需求 §2.5 的"强制开启"如果只是前端置灰，绕开 UI 直调接口就能关掉它，
     * 所以**后端必须也拒绝**，并且**要回话说明**（{@code note}），否则前端会以为保存成功了。
     */
    public record SettingsSaveResponse(boolean saved, List<SettingsSaveRequest.Item> applied, String note) {
    }
}
