package com.knowledge.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问答会话（表 {@code sys_chat_session}，`T-802` 建表半边）。
 *
 * <h3>会话与模型是**绑定**关系</h3>
 * <p>需求 `12` 的语义表（`10` §9）冻结了三条：不传 → 用会话绑定的；传了且一致 → 继续；
 * **传了但不一致 → 服务端开新会话**（原会话保留）。所以 {@link #modelId} 记在**会话**上，
 * 而不是每条消息上 —— 不支持同一会话中途换模型，理由有二：上下文跨模型语义漂移、
 * 以及"这条答案是谁生成的"必须可归因。
 *
 * <p>⚠️ 消息行的 {@code model_id} 是**另一个事实**（实际用的是哪个），
 * 与这里"绑定的是哪个"不是一回事。前者是痕迹，后者是配置。
 *
 * <h3>软删</h3>
 * <p>{@link #deletedAt} 与 {@code sys_document} 同型，读侧一律带 {@code is null}。
 * 消息行不单独软删 —— 会话是聚合根。
 */
@Data
@Entity
@Table(
        name = "sys_chat_session",
        indexes = {
                @Index(name = "idx_cs_user", columnList = "user_id, deleted_at, updated_at"),
        }
)
public class SysChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话归属人。会话列表与回看都按它过滤 —— 越权读别人的会话是数据泄露。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 会话标题。本批取首个提问的截断（`T-802` 若做"自动起标题"再改）。 */
    @Column(name = "title", length = 255, nullable = false)
    private String title;

    /** 该会话**绑定**的对话模型 id。可为空 = 用的是"当前默认对话模型"。 */
    @Column(name = "model_id")
    private Long modelId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 每条消息落库时更新；列表按它倒序（避免 {@code max(created_at)} 子查询）。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
