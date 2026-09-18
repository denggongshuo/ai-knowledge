package com.knowledge.ai.service;

import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import org.springframework.stereotype.Component;

/**
 * 恶意扫描的审计留痕（`T-O04` 步 3）—— 与 {@link ParseAuditTrail} 同形。
 *
 * <h3>为什么是单独一个类，而不是并进 {@code ParseAuditTrail}</h3>
 * <p>{@code ParseAuditTrail} 的类注释把它的职责写死了：「**谁在什么时候触发了哪篇文档的解析**」。
 * 恶意扫描不是解析链路的一环（它跑在解析**之前**，而且它的结论会**阻止**解析发生），
 * 把两个关注点塞进一个类，读的人会以为"解析失败"与"被安全扫描拦下"是同一件事
 * —— 而它们的下一步动作完全不同（前者换文件重试，后者找安全管理员）。
 *
 * <h3>为什么是 HIGH</h3>
 * <p>`O4` 判据 4 原话：「恶意样本被拦 → `FILE_MALICIOUS` + 审计 `HIGH`」。
 * 语义上也成立：《05》§4.3 把「安全事件」与数据导出、权限变更并列。
 * 这是本仓少见的**真的该报警**的那一类 —— 它不该被日常上传的正常记录淹没。
 *
 * <h3>⚠️ 操作人显示为「系统」</h3>
 * <p>它由后台 worker 线程调用，那时请求上下文早没了（`LoginUserContext` 是 ThreadLocal）。
 * 「系统」是**如实**的：扫描不是用户动作，是系统在响应之后自己跑的。
 * 要追"是谁传的这篇"，走同 docId 的「上传文档」那条（它记着真实操作人）。
 *
 * <p>三个同步点（漏一处就红）：本注解 / {@code AuditRiskPolicy.IMPLEMENTED} /
 * {@code AuditCoverageFlowTest#expectedActions()}。
 */
@Component
public class DocScanAuditTrail {

    /**
     * 文档因恶意内容被拦下（`O4` 判据 4）。
     *
     * <p>{@code target} 用 {@code '文档#' + #docId} —— 与 {@link ParseAuditTrail} 的
     * 「触发解析」「解析完成」「解析失败」**同一个表达式**，于是按 {@code target = 文档#N}
     * 一筛，就是这篇文档的完整生命周期（含"它为什么没被解析"）。
     */
    @AuditLog(module = AuditModule.DOC, action = "恶意内容拦截", risk = RiskLevel.HIGH,
            target = "'文档#' + #docId")
    public void maliciousBlocked(long docId) {
        // 空体是刻意的：注解即全部内容，切面在方法返回后落库（同 ParseAuditTrail）。
    }
}
