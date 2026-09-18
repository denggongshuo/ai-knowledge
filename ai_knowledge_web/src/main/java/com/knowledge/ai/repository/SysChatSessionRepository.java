package com.knowledge.ai.repository;

import com.knowledge.ai.entity.SysChatSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 问答会话仓储。
 *
 * <p>问答链路用"解析或开新会话"一跳（`T-800`/`T-801`）；列表 / 消息列表 / 删除 / 导出
 * 四个查询是 `T-802` 的余下半边（`S5-M08-B`）。
 *
 * <h3>为什么"有无关键字"是两个方法而不是一个带 {@code :pattern is null} 的查询</h3>
 * <p>把 {@code (:pattern is null or s.title like :pattern)} 写成一条 JPQL，看起来更短，
 * 但它把 {@code null} 当参数值传进去时参数类型要靠 Hibernate 猜 —— 猜成 {@code bytea}
 * 之类的形态会在 MySQL 上直接报类型错，且**只在"不传关键字"的那条路径上炸**，
 * 也就是最常用的那条。两个方法各管一种形状，参数类型都是确定的。
 *
 * <h3>{@code user_id} 为什么进条件而不是取回来再判</h3>
 * <p>与 {@link #findByIdAndUserIdAndDeletedAtIsNull} 同一条纪律：少写一个过滤
 * 就是"别人的会话我能看到"，那是**数据泄露**而不是功能缺陷。
 */
public interface SysChatSessionRepository extends JpaRepository<SysChatSession, Long> {

    /**
     * 按 id 取**未删**的会话，并校验归属人。
     *
     * <p>⚠️ `user_id` 与 `deleted_at` 都进条件，而不是取回来再判：
     * 少写一个过滤就是"别人的会话我能接着问"，那是数据泄露而不是功能缺陷。
     */
    Optional<SysChatSession> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    /**
     * 某人的会话列表，按最近活动倒序。
     *
     * <p>倒序键是 {@code updated_at}（每条消息落库时刷新），不是 {@code max(created_at)}
     * 子查询 —— 后者每翻一页都要扫一次消息表（`V23` 的口径）。
     */
    @Query("""
            select s from SysChatSession s
            where s.userId = :userId and s.deletedAt is null
            order by s.updatedAt desc, s.id desc
            """)
    Page<SysChatSession> findActive(@Param("userId") Long userId, Pageable pageable);

    /**
     * 同上，但按标题模糊过滤。
     *
     * @param pattern **已带 {@code %} 的完整模式**（如 {@code %检索%}）—— 这里不再拼，
     *                避免"服务层拼一次、仓储层再拼一次"的重复转义
     */
    @Query("""
            select s from SysChatSession s
            where s.userId = :userId and s.deletedAt is null and s.title like :pattern
            order by s.updatedAt desc, s.id desc
            """)
    Page<SysChatSession> findActiveByTitle(@Param("userId") Long userId,
                                           @Param("pattern") String pattern,
                                           Pageable pageable);
}