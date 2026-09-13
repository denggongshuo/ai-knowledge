package com.knowledge.ai.repository;

import com.knowledge.ai.entity.SysLoginLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 登录日志仓储。
 *
 * <p>刻意**不提供任何 delete 方法** —— 登录日志是风控证据，只增不改不删。
 * 与 {@code SysAuditLogRepository} 同一约定。
 */
public interface SysLoginLogRepository extends JpaRepository<SysLoginLog, Long> {

    /** 某用户的登录日志，按时间倒序（前端「登录日志」Tab）。 */
    Page<SysLoginLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** 某用户最近的 N 条记录，供「安全概览」的 recentDevices 用。 */
    java.util.List<SysLoginLog> findTop10ByUserIdAndResultOrderByCreatedAtDesc(Long userId, String result);
}
