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
 * 部门（表 {@code sys_department}，仅两层）。
 *
 * <p><b>「未分配」是 id = 0 的虚拟节点</b>，不是真实部门：它用来把"没归到任何部门的账号"
 * 显形出来，便于发现异常账号。因为 {@code deptId = 0} 会作为真实取值出现在接口里，
 * {@code id} 必须真的是 0（V2 种子用显式 id 插入），不能靠自增。
 *
 * <p>S1 只需要「按 id 取名字」这一个能力（登录响应与个人信息要返回
 * {@code department} 全路径名）；部门树与维护属 S2（M02 权限与组织）。
 */
@Data
@Entity
@Table(name = "sys_department")
public class SysDepartment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 64, nullable = false)
    private String name;

    /** 父部门 id；{@code null} = 顶级。仅两层。 */
    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "sort", nullable = false)
    private Integer sort;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
