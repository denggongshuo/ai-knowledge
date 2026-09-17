package com.knowledge.ai.repository;

import com.knowledge.ai.entity.SysChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 问答消息仓储。
 *
 * <p>本批只用 {@code save}（拿自增 id 去填 `done.messageId`）。
 * 消息列表查询属 `T-802`，本批不加。
 */
public interface SysChatMessageRepository extends JpaRepository<SysChatMessage, Long> {
}
