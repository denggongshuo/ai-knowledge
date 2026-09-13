package com.knowledge.ai.repository;

import com.knowledge.ai.entity.SysDepartment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 部门仓储。S1 只用到「按 id 取名字」与「取全部用于拼路径」，
 * 部门树的增删改属 S2（M02）。
 */
public interface SysDepartmentRepository extends JpaRepository<SysDepartment, Long> {

    List<SysDepartment> findAllByOrderBySortAsc();
}
