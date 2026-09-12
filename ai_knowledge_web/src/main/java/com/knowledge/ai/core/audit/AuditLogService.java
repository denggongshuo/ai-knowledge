package com.knowledge.ai.core.audit;

import com.knowledge.ai.entity.SysAuditLog;
import com.knowledge.ai.repository.SysAuditLogRepository;
import com.knowledge.ai.service.AuditLogNoGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 审计日志落库服务（T-005）。
 *
 * <p><b>核心不变量 I-12</b>：{@link Propagation#REQUIRES_NEW}
 * —— 审计写入挂起当前业务事务、另起一个独立事务。
 *
 * <p>为什么必须这样：假设"删除知识库"业务事务里，先删库、再校验失败回滚。
 * 如果审计用同一个事务，那条"尝试删除知识库（失败）"的记录会跟着一起回滚，
 * 结果是**用户明明点过这个危险按钮，系统里却查不到任何痕迹**。
 * 安全审计的价值恰恰在于记录"发生过什么"，包括失败。
 *
 * <p>由 {@link AuditAspect} 调用。因为跨 Bean 调用，Spring 的事务代理才会生效
 * （同一个类内部自调用不会走代理，这是常见坑）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final SysAuditLogRepository repository;

    private final AuditLogNoGenerator logNoGenerator;

    /**
     * 写入一条审计日志。独立事务，不参与调用方的事务。
     *
     * @param event 切面采集到的事件
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditEvent event) {
        LocalDateTime now = LocalDateTime.now();
        SysAuditLog entity = new SysAuditLog();
        entity.setLogNo(logNoGenerator.next(now.toLocalDate()));
        entity.setTraceId(event.traceId());
        entity.setApiPath(event.apiPath());
        entity.setModule(event.module());
        entity.setAction(event.action());
        entity.setRiskLevel(event.riskLevel().name());
        entity.setTarget(event.target());
        entity.setOperator(event.operator());
        entity.setOperatorName(event.operatorName());
        entity.setRoleName(event.roleName());
        entity.setIp(event.ip());
        entity.setUserAgent(event.userAgent());
        entity.setResult(event.result());
        entity.setFailReason(event.failReason());
        entity.setCostMs((int) Math.min(event.costMs(), Integer.MAX_VALUE));
        entity.setAffectedUsers(event.affectedUsers());
        entity.setNote(event.note());
        entity.setCreatedAt(now);
        entity.setCreatedDate(now.toLocalDate());

        repository.save(entity);
    }
}
