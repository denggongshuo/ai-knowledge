package com.knowledge.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问答消息（表 {@code sys_chat_message}，`T-802` 建表半边）。
 *
 * <h3>为什么这张表本批就得建</h3>
 * <p>§9 的 `done` 事件**必带 `messageId`**，而 `T-808`（回答归因 `used_model_id`）与
 * `T-807`（反馈 `like`/`unlike` + `reason` 落库）**都挂在消息行上**。
 * 若本批只发一个临时 id，那么本批产出的每一次问答**永远无法归因、也无法反馈** ——
 * 与 `T-707` 的 `D-2` 是同一种形状：参与者不齐，数据就永久缺一块。
 *
 * <h3>{@link #modelId} 与 {@link SysChatSession#getModelId()} 的分工</h3>
 * <p>会话上那个是**绑定的**（配置），这里是**实际用的**（痕迹，即 `T-808` 的
 * {@code used_model_id}）。两者不一致本身就是一条线索 —— 那正是
 * 「同一会话为何答案风格变了」要回答的问题。
 *
 * <h3>用量三列可空</h3>
 * <p>模型没回报用量时留 {@code null}，**不填 0** —— "没回报"与"用了 0 个 token"
 * 在运营统计里是两件事，填 0 会把均价算歪。
 */
@Data
@Entity
@Table(
        name = "sys_chat_message",
        indexes = {
                @Index(name = "idx_cm_session", columnList = "session_id, id"),
        }
)
public class SysChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属会话。会话是聚合根，消息不单独软删。 */
    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** {@code user} / {@code assistant}。 */
    @Column(name = "role", length = 16, nullable = false)
    private String role;

    /** 消息正文。user 行是提问原文，assistant 行是完整回答。 */
    @Lob
    @Column(name = "content", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String content;

    /** 该轮**实际使用**的对话模型 id（`T-808` 的 used_model_id）。user 行为 {@code null}。 */
    @Column(name = "model_id")
    private Long modelId;

    /** 模型展示名。模型改名后仍要能还原"当时显示的是什么"。 */
    @Column(name = "model_name", length = 128)
    private String modelName;

    /** 关联 {@code sys_retrieval_trace.trace_id} —— "这轮答案是哪些分片支撑的"从它跳过去。 */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "token_prompt")
    private Integer tokenPrompt;

    @Column(name = "token_completion")
    private Integer tokenCompletion;

    /** 结束原因（{@code stop} / {@code length} / 客户端断开等）；未知为 {@code null}。 */
    @Column(name = "finish_reason", length = 32)
    private String finishReason;

    // ---------- 反馈（T-807 / G5-14）----------

    /**
     * 点赞 / 点踩：{@code like} / {@code unlike}。
     *
     * <p>🔴 {@code null} 的语义是「**从未反馈**」，与 {@code unlike} 是两件事。
     * 运营统计**必须**用 {@code where feedback = 'unlike'}，**不能**用 {@code <> 'like'}——
     * MySQL 里 {@code NULL <> 'like'} 求值为 {@code NULL}（非 true），
     * 会把"从未反馈"整批滤掉，负反馈率的分母静默变小。
     */
    @Column(name = "feedback", length = 16)
    private String feedback;

    /**
     * 点踩原因（{@code ChatFeedbackReason} 的中文标签，5 条枚举之一）。
     *
     * <p>只在 {@link #feedback} 为 {@code unlike} 时有值 —— {@code like} 不带原因，
     * 需求 `07` §6.8 写的是「**点踩时**可选原因」。
     */
    @Column(name = "feedback_reason", length = 64)
    private String feedbackReason;

    /** 反馈时间。可被改写 —— **改主意是覆盖，不是追加**（不保留变更历史，见计划 §3.4 假设 3）。 */
    @Column(name = "feedback_at")
    private LocalDateTime feedbackAt;

    /**
     * 本轮是否把原图直接传给了对话模型（`T-806` · 需求 `09` §4.7）。
     *
     * <h3>三态，不是两态</h3>
     * <p>{@code null} = **未采集**（`V38` 之前的行），{@code 0} = 明确没走原图，
     * {@code 1} = 明确走了。⚠️ 用 {@code Boolean} 而不是 {@code boolean}：
     * 后者会把 {@code null} 静默读成 {@code false}，于是"没采集"在页面上变成
     * 「没用原图」—— 一句关于内容的断言。与 `sys_chat_attachment.degraded` 同一条口径。
     *
     * <p>⚠️ **只对该轮生效**（§4.7 原文），不是会话级 —— 同一会话里两种链路可以交替出现，
     * 而那一列存在的全部理由就是让两者的回答质量能分开看。
     */
    @Column(name = "used_vision")
    private Boolean usedVision;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
