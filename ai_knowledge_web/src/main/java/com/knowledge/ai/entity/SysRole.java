package com.knowledge.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色（表 {@code sys_role}）。
 *
 * <p><b>{@code dataScope} 是角色的属性，不是部门的</b>：同一用户挂多个角色时，
 * 权限点取并集、数据范围取**最宽**（{@code DataScope.widest}）。
 * 范围若挂在部门上就无法叠加 —— 这是本表把 {@code data_scope} 放在这里的原因。
 *
 * <p>{@code dataScope} 以 {@code String} 落库、在 {@code LoginUser} 里才转成
 * {@link com.knowledge.ai.core.enums.DataScope}：因为投影查询
 * （{@code SysRoleRepository.UserRoleView}）返回的就是字符串，
 * 在这里做强类型化会让投影也要跟着转型，收益为零。
 */
@Data
@Entity
@Table(name = "sys_role")
public class SysRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code SUPER_ADMIN} / {@code KB_ADMIN} / {@code USER} / {@code AUDITOR} / {@code GUEST}。 */
    @Column(name = "code", length = 32, nullable = false)
    private String code;

    @Column(name = "name", length = 64, nullable = false)
    private String name;

    @Column(name = "description", length = 255)
    private String description;

    /** {@code ALL} / {@code DEPT_AND_CHILD} / {@code DEPT} / {@code SELF} / {@code CUSTOM}。 */
    @Column(name = "data_scope", length = 24, nullable = false)
    private String dataScope;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    /** {@code true} = 内置角色，不可删除 / 不可改数据范围（G2-4）。 */
    @Column(name = "builtin", nullable = false)
    private Boolean builtin;

    @Column(name = "sort", nullable = false)
    private Integer sort;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
