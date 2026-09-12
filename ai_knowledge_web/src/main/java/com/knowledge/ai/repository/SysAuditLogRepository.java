package com.knowledge.ai.repository;

import com.knowledge.ai.entity.SysAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审计日志仓储。
 *
 * <p>注意这里**只声明读与写**，没有任何 {@code delete} 的自定义方法 ——
 * 审计日志不可编辑、不可删除（《05-需求-权限与审计》§4.8）。
 */
@Repository
public interface SysAuditLogRepository extends JpaRepository<SysAuditLog, Long> {

    /** 高危日志列表（运营看板/告警用）。 */
    List<SysAuditLog> findTop50ByRiskLevelOrderByCreatedAtDesc(String riskLevel);

    /** 统计某时间窗内的失败操作数。 */
    long countByResultAndCreatedAtAfter(String result, LocalDateTime after);
}
