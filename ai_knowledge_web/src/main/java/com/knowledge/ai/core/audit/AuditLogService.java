package com.knowledge.ai.core.audit;

import com.knowledge.ai.entity.SysAuditLog;
import com.knowledge.ai.repository.SysAuditLogRepository;
import com.knowledge.ai.service.AuditLogNoGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
     * JSON 序列化器（T-300）。注入 Boot 4 **自动配置**的那个 —— 即 Jackson 3
     * （{@code tools.jackson.databind.ObjectMapper}）。
     *
     * <p>为什么不 {@code new ObjectMapper()}：本仓 classpath 上同时存在
     * Jackson 3（Boot 4 默认，{@code tools.jackson}）与 Jackson 2
     * （JWT 库 {@code jjwt-jackson} 的传递依赖，{@code com.fasterxml}）两份同名类。
     * 自己 new 会绕开 Boot 的统一配置，且将来加字段（如日期类型）容易踩到
     * 「注入哪个 ObjectMapper 随版本变化」这个坑 —— {@code ResponseWriter} 的
     * javadoc 记录过它。走注入则由 Boot 保证给到正确的那个，
     * 实证见 {@code JacksonProbeTest}。
     */
    private final ObjectMapper jsonMapper;

    /**
     * 写入一条审计日志。独立事务，不参与调用方的事务。
     *
     * <p><b>审计失败绝不影响业务</b>：整个方法被 {@link AuditAspect} 包在 try/catch 里，
     * 这里只需保证"要么完整写入、要么整体抛错让它记日志"，不做半程兜底。
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
        entity.setChangesJson(serializeChanges(event.changes()));
        entity.setNote(event.note());
        entity.setCreatedAt(now);
        entity.setCreatedDate(now.toLocalDate());

        repository.save(entity);
    }

    /**
     * 把变更明细序列化进 {@code changes_json}（T-300）。
     *
     * <p><b>无变更时写 {@code null} 而不是 {@code []}</b>：前端详情抽屉是
     * {@code v-if="detail.changes && detail.changes.length"}（{@code AuditLogView.vue:307}），
     * 两者视觉上等价 —— 但语义不等价：{@code null} = "这次操作本就没有对照"，
     * {@code []} = "采集过，结果是空"。当前没有动作会产生"采集了但为空"，
     * 所以写 {@code null} 既准确又给将来留了区分度。
     *
     * <p>序列化失败**不让审计整体失败**：降级为 {@code null} 并留 error 日志。
     * 变更明细丢失比"整个审计写不进去"轻 —— 后者会让这次操作的痕迹**完全消失**。
     */
    private String serializeChanges(List<AuditChange> changes) {
        if (changes == null || changes.isEmpty()) {
            return null;
        }
        try {
            return jsonMapper.writeValueAsString(changes);
        } catch (Exception ex) {
            log.error("审计变更明细序列化失败，降级为 null（changes 条数={}）", changes.size(), ex);
            return null;
        }
    }
}
