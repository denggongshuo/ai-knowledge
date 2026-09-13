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
 * 登录日志（表 {@code sys_login_log}，T-112 / G1-4）。
 *
 * <p>与 {@link SysAuditLog} 的分工：审计面向**管理员**、只记 🚩 危险操作；
 * 登录日志面向**用户自己**（个人信息页「登录日志」Tab）与风控（异常登录红色标记）。
 * 登录是匿名可达的动作（失败时账号可能都不存在），因此没有 {@code @AuditLog} 切面，
 * 由 {@code AuthService} 显式写入。
 *
 * <p><b>只增不改不删</b>：它是风控证据。账号被彻底删除后日志必须保留，
 * 所以 {@code user_id} 允许为 {@code null} 且**不建外键**。
 */
@Data
@Entity
@Table(
        name = "sys_login_log",
        indexes = {
                @Index(name = "idx_login_log_user_time", columnList = "user_id, created_at"),
                @Index(name = "idx_login_log_created", columnList = "created_at"),
                @Index(name = "idx_login_log_result_created", columnList = "result, created_at"),
        }
)
public class SysLoginLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 id；账号不存在导致的失败为 {@code null}。 */
    @Column(name = "user_id")
    private Long userId;

    /** 提交的登录账号。失败时也记录 —— 这是发现「有人在猜账号」的唯一线索。 */
    @Column(name = "username", length = 64)
    private String username;

    /**
     * 登录方式枚举码：{@code PASSWORD} / {@code SMS} / {@code SSO}。
     *
     * <p>落库存码、出参映射成中文（密码登录 / 验证码登录 / 统一身份认证）：
     * 库存文案则改文案要刷历史数据，而前端要的是中文 —— 映射只能在出参侧。
     */
    @Column(name = "method", length = 16, nullable = false)
    private String method;

    @Column(name = "ip", length = 64)
    private String ip;

    /** 归属地。v1 只区分「内网 / 外网」，不做 IP 库解析（《04》明确不做）。 */
    @Column(name = "location", length = 64)
    private String location;

    /** 设备，由 User-Agent 简化为「浏览器 · 操作系统」。 */
    @Column(name = "device", length = 128)
    private String device;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    /** {@code SUCCESS} / {@code FAILED}。 */
    @Column(name = "result", length = 16, nullable = false)
    private String result;

    /** 失败原因（成功为 {@code null}）。措辞不得泄露账号是否存在。 */
    @Column(name = "fail_reason", length = 255)
    private String failReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 冗余日期列，供按天筛选 / 聚合与未来分区裁剪。 */
    @Column(name = "created_date", nullable = false)
    private LocalDate createdDate;
}
