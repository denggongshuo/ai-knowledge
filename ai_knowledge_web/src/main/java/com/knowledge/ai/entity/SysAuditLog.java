package com.knowledge.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 审计日志（表 {@code sys_audit_log}）。
 *
 * <p>对齐《05-需求-权限与审计》§4 与前端 {@code AuditLog} / {@code AuditLogDetail}。
 *
 * <p><b>本表只增不改不删</b>（验收要点：审计日志没有任何编辑/删除入口，
 * 接口层也没有）。因此实体上不提供 setter 之外的任何变更语义，
 * S1 也不会为它生成 PUT/DELETE 接口。
 *
 * <p><b>写入方式</b>：{@code REQUIRES_NEW} 独立事务（不变量 I-12），
 * 业务回滚不会连带回滚日志。
 *
 * <p>索引按查询场景建：列表默认按时间倒序 + 支持 模块 / 操作人 / 结果 过滤
 * （见 {@code GET /api/audit/logs}）。
 */
@Data
@Entity
@Table(
        name = "sys_audit_log",
        indexes = {
                @Index(name = "idx_audit_created_at", columnList = "created_at"),
                @Index(name = "idx_audit_module_created", columnList = "module, created_at"),
                @Index(name = "idx_audit_operator_created", columnList = "operator, created_at"),
                @Index(name = "idx_audit_trace_id", columnList = "trace_id"),
        }
)
public class SysAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 展示用单号：{@code LOG-20260911-0008821}。 */
    @Column(name = "log_no", length = 32, nullable = false, unique = true)
    private String logNo;

    /** 链路追踪 id：与业务日志、前端错误页展示的是同一个值。 */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    /** 接口路径（含查询串），如 {@code PUT /api/permissions/matrix}。 */
    @Column(name = "api_path", length = 512)
    private String apiPath;

    /** 业务模块，取值见 {@code AuditModule}。 */
    @Column(name = "module", length = 32, nullable = false)
    private String module;

    /** 动作描述，如"新建用户"。 */
    @Column(name = "action", length = 64, nullable = false)
    private String action;

    /** 风险等级：{@code HIGH} / {@code NORMAL}。 */
    @Column(name = "risk_level", length = 16, nullable = false)
    private String riskLevel;

    /** 操作对象。 */
    @Column(name = "target", length = 255)
    private String target;

    /** 操作人登录账号。 */
    @Column(name = "operator", length = 64)
    private String operator;

    /** 操作人姓名。 */
    @Column(name = "operator_name", length = 64)
    private String operatorName;

    /**
     * <b>操作人自己的</b>角色码（多角色用逗号连接）。
     *
     * <p>⚠️ 与 {@link #affectedRole} **语义相反**，别合并也别互相顶替：
     * 本字段答的是「谁在操作」，{@code affectedRole} 答的是「谁被影响了」。
     * 前端详情抽屉消费的是后者（{@code AuditLogView.vue:337/349}）。
     */
    @Column(name = "role_name", length = 128)
    private String roleName;
    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    /** {@code SUCCESS} / {@code FAILED}。 */
    @Column(name = "result", length = 16, nullable = false)
    private String result;

    /** 失败原因（失败必填，成功为 null）。 */
    @Column(name = "fail_reason", length = 500)
    private String failReason;

    /** 接口耗时（毫秒）。 */
    @Column(name = "cost_ms")
    private Integer costMs;

    /** 受影响人数（权限变更类动作才有）。 */
    @Column(name = "affected_users")
    private Integer affectedUsers;

    /**
     * 受影响**角色名**（权限变更类动作才有，如「访客」）。{@code V11} 新增列。
     *
     * <p>存在的唯一理由：前端详情抽屉把 {@code roleName} 当受影响角色渲染
     * 「{@code N 人（该角色下全部成员）}」，而 {@link #roleName} 存的是操作人角色码 ——
     * 语义相反。历史 1262 行全为 {@code null}（当时没记），详情接口按 null 返回，
     * 前端已有守卫会省略括号，不伪造。
     */
    @Column(name = "affected_role", length = 128)
    private String affectedRole;

    /** 变更前后对照，JSON 数组字符串：{@code [{label,before,after}]}。 */
    @Column(name = "changes_json", columnDefinition = "TEXT")
    private String changesJson;

    /** 补充说明（如"本次变更使 12 名用户的权限范围扩大"）。 */
    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * 冗余的日期列。
     * 列表页有"按天筛选"，且《05》§4.7 提到日志量大时按月分表 ——
     * 冗余一个 DATE 列让按天聚合与未来的分区裁剪都不必对 DATETIME 做函数运算。
     */
    @Column(name = "created_date", nullable = false)
    private LocalDate createdDate;
}
