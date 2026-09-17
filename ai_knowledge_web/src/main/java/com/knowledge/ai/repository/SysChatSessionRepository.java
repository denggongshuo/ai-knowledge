package com.knowledge.ai.repository;

import com.knowledge.ai.entity.SysChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 问答会话仓储。
 *
 * <p>本批只有问答链路在用（`T-800`/`T-801` 的"解析或开新会话"一跳）。
 * 列表 / 删除 / 导出三个查询属 `T-802`，本批**不加** —— 用不到的方法先不写，
 * 免得占着签名又没有调用方。
 */
public interface SysChatSessionRepository extends JpaRepository<SysChatSession, Long> {

    /**
     * 按 id 取**未删**的会话，并校验归属人。
     *
     * <p>⚠️ `user_id` 与 `deleted_at` 都进条件，而不是取回来再判：
     * 少写一个过滤就是"别人的会话我能接着问"，那是数据泄露而不是功能缺陷。
     */
    Optional<SysChatSession> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);
}
