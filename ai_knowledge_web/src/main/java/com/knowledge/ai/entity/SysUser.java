package com.knowledge.ai.entity;

import com.knowledge.ai.core.enums.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户（表 {@code sys_user}）。
 *
 * <p>字段口径对齐 {@code V1__init_skeleton_schema.sql} 的建表注释与前端 {@code UserInfo}。
 *
 * <p><b>刻意没有的两个字段</b>（建表脚本已说明，这里重申，因为最容易"顺手加上"）：
 * <ul>
 *   <li>{@code department}（部门全路径名）—— 由 {@code dept_id} 关联部门树拼出，落库就会
 *       在部门改名后漂移；</li>
 *   <li>{@code hasBusinessData} —— 按是否存在知识库 / 文档 / 会话算出来的派生值，
 *       属于「读的时候才知道」的量，存下来必然过期。</li>
 * </ul>
 *
 * <p><b>{@code passwordHash} 永不返回前端</b>：它只在本实体内用于 BCrypt 校验，
 * 出参一律走 DTO。因此本类**不做** {@code @JsonIgnore} 之类的兜底 ——
 * 兜底会让人误以为"可以直接返回实体"，而正确做法是一直用 DTO（见 {@code AuthDtos}）。
 */
@Data
@Entity
@Table(name = "sys_user")
public class SysUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登录标识，全站唯一（决策 1：统一用用户名，不用工号）。 */
    @Column(name = "username", length = 64, nullable = false)
    private String username;

    /** BCrypt 摘要。明文密码永不入库、永不进日志。 */
    @Column(name = "password_hash", length = 100, nullable = false)
    private String passwordHash;

    @Column(name = "real_name", length = 64, nullable = false)
    private String realName;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "email", length = 128)
    private String email;

    /** 所属部门；{@code 0} = 「未分配」虚拟节点。数据范围另由角色 {@code dataScope} 决定。 */
    @Column(name = "dept_id", nullable = false)
    private Long deptId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private UserStatus status;

    @Column(name = "position", length = 64)
    private String position;

    @Column(name = "intro", length = 500)
    private String intro;

    @Column(name = "avatar", length = 255)
    private String avatar;

    /** 管理员重置密码后置 {@code true}，强制该用户下次登录后修改。 */
    @Column(name = "must_change_password", nullable = false)
    private Boolean mustChangePassword;

    /**
     * 密码最后修改时间（V4 新增）。
     *
     * <p>《04》§253 要求个人信息页显示「密码 N 天前更新」，《10》§367 的
     * {@code /user/security} 出参含 {@code passwordUpdatedAt}。**不能用
     * {@code updatedAt} 顶替** —— 用户改一次昵称就会把"密码多久没换"重置。
     *
     * <p>写入点只有三处：注册、改密（T-111）、重置密码（T-108）。
     */
    @Column(name = "password_updated_at")
    private LocalDateTime passwordUpdatedAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 软删除时间。<b>有业务数据的账号只能停用不能删除</b>（G2-6），删除前必须校验。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** 账号是否可用（未软删且状态允许登录）。 */
    public boolean isDeleted() {
        return deletedAt != null;
    }
}
