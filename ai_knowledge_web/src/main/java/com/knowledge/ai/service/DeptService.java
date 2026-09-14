package com.knowledge.ai.service;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.dto.admin.AdminDtos;
import com.knowledge.ai.entity.SysDepartment;
import com.knowledge.ai.repository.SysDepartmentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 部门树与维护（T-200 / T-201）。
 *
 * <p><b>为什么整树一次查出来再拼</b>：部门是**两层、个位数行数**的参考数据
 * （V1 建表注释），且树接口每次都要"全部节点 + 人数"，逐节点查人数是教科书式 N+1。
 * 一次全量 + 内存拼装，行数不随业务增长（两层结构写死在需求里，《05》§1.11 边界）。
 */
@Service
@RequiredArgsConstructor
public class DeptService {

    /** 「未分配」固定为 0（V1 建表注释：它必须真的是 0，前端虚拟根也用 0）。 */
    private static final Long UNASSIGNED_DEPT_ID = 0L;

    private final SysDepartmentRepository departmentRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 部门树（含各节点人数）。
     *
     * <p>{@code userCount} = 该节点**含子节点**的未删除用户数 —— 前端树右侧直接渲染
     * 「研发中心 52」这种数字，52 是含 18+24+10 的合计（《01》§1.1）。
     * 「未分配」人数 = dept_id = 0 的账号数，它没有子节点。
     */
    @Transactional(readOnly = true)
    public List<AdminDtos.DeptNode> tree() {
        List<SysDepartment> all = departmentRepository.findAllByOrderBySortAsc();
        Map<Long, Long> counts = countUsersByDept();

        Map<Long, AdminDtos.DeptNode> nodes = new LinkedHashMap<>();
        for (SysDepartment dept : all) {
            nodes.put(dept.getId(), new AdminDtos.DeptNode(dept.getId(), dept.getName(),
                    dept.getParentId(), counts.getOrDefault(dept.getId(), 0L), new ArrayList<>()));
        }

        List<AdminDtos.DeptNode> roots = new ArrayList<>();
        for (AdminDtos.DeptNode node : nodes.values()) {
            if (node.parentId() == null || !nodes.containsKey(node.parentId())) {
                roots.add(node);
            } else {
                nodes.get(node.parentId()).children().add(node);
            }
        }
        // 顶级按 id 升序（未分配 0 在最前，与 mock DEPTS 顺序一致）
        roots.sort(Comparator.comparing(AdminDtos.DeptNode::id));
        // 父节点人数 = 自身直属 + 子节点人数。⚠️ 必须基于更新后的 nodes 重建 roots ——
        // 原地改 nodes 不会更新 roots 里的旧引用（第一次实现就是这里返回了旧人数）
        return roots.stream()
                .map(root -> {
                    long total = root.userCount();
                    for (AdminDtos.DeptNode child : root.children()) {
                        total += child.userCount();
                    }
                    return new AdminDtos.DeptNode(root.id(), root.name(), root.parentId(),
                            total, root.children());
                })
                .toList();
    }

    /** 直属人数（不含子节点），按部门分组；没有用户的部门不出现在结果里。 */
    private Map<Long, Long> countUsersByDept() {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select dept_id, count(*) from sys_user
                        where deleted_at is null group by dept_id
                        """)
                .getResultList();
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    /** 新建部门。两层结构：{@code parentId} 非空时父部门必须是顶级。 */
    @Transactional
    public AdminDtos.DeptCreated create(String name, Long parentId) {
        if (parentId != null) {
            SysDepartment parent = departmentRepository.findById(parentId)
                    .orElseThrow(() -> new BizException(ErrorCode.DEPT_NOT_FOUND));
            if (parent.getParentId() != null) {
                // 两层封顶：不允许"组下再挂组"（《05》§1.11 边界）
                throw new BizException(ErrorCode.PARAM_INVALID, "部门最多两层，不能在分组下再建分组");
            }
        }
        departmentRepository.findAllByOrderBySortAsc().stream()
                .filter(d -> d.getName().equals(name))
                .findFirst()
                .ifPresent(d -> {
                    throw new BizException(ErrorCode.PARAM_INVALID, "同名部门已存在：" + name);
                });
        SysDepartment dept = new SysDepartment();
        dept.setName(name);
        dept.setParentId(parentId);
        dept.setSort(nextSort());
        LocalDateTime now = LocalDateTime.now();
        dept.setCreatedAt(now);
        dept.setUpdatedAt(now);
        SysDepartment saved = departmentRepository.save(dept);
        return new AdminDtos.DeptCreated(saved.getId(), saved.getName(), saved.getParentId());
    }

    /** 编辑（改名 / 移动）。移动同样受两层约束，且不能把部门挪进自己的子树（两层下即挪到自己的孩子）。 */
    @Transactional
    public void update(Long id, String name, Long parentId) {
        SysDepartment dept = departmentRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.DEPT_NOT_FOUND));
        if (UNASSIGNED_DEPT_ID.equals(id)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "「未分配」是虚拟节点，不可编辑");
        }
        if (parentId != null && !parentId.equals(dept.getParentId())) {
            if (parentId.equals(id)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "部门不能以自己为父");
            }
            SysDepartment parent = departmentRepository.findById(parentId)
                    .orElseThrow(() -> new BizException(ErrorCode.DEPT_NOT_FOUND));
            if (parent.getParentId() != null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "部门最多两层，不能在分组下再建分组");
            }
        }
        if (name != null && !name.isBlank()) {
            dept.setName(name);
        }
        if (parentId != null) {
            dept.setParentId(parentId);
        }
        dept.setUpdatedAt(LocalDateTime.now());
        departmentRepository.save(dept);
    }

    /**
     * 删除。四个前置校验缺一不可：虚拟节点不可删、有子部门先删子、
     * 部门下有用户先转移、**部门下有知识库先转移**（《05》§1.6）。
     *
     * <p>知识库校验在 S2 时留了 TODO（当时 {@code sys_kb} 还是骨架、写 SQL 会报错），
     * V7 建表后回补。**校验的是直属知识库**（{@code sys_kb.dept_id}）：子部门的知识库
     * 由子部门自己的删除流程把关，这里不越级 —— 与「有子部门先删子部门」是同一条逻辑链。
     */
    @Transactional
    public AdminDtos.Deleted delete(Long id) {
        if (UNASSIGNED_DEPT_ID.equals(id)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "「未分配」是虚拟节点，不可删除");
        }
        SysDepartment dept = departmentRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.DEPT_NOT_FOUND));
        boolean hasChild = departmentRepository.findAllByOrderBySortAsc().stream()
                .anyMatch(d -> id.equals(d.getParentId()));
        if (hasChild) {
            throw new BizException(ErrorCode.DEPT_NOT_EMPTY, "该部门下存在子部门，请先删除或转移子部门");
        }
        Long members = (Long) entityManager.createNativeQuery(
                        "select count(*) from sys_user where dept_id = :id and deleted_at is null")
                .setParameter("id", id)
                .getSingleResult();
        if (members != null && members > 0) {
            throw new BizException(ErrorCode.DEPT_NOT_EMPTY,
                    "该部门下仍有 " + members + " 名成员，请先转移成员再删除");
        }
        Long kbs = (Long) entityManager.createNativeQuery(
                        "select count(*) from sys_kb where dept_id = :id")
                .setParameter("id", id)
                .getSingleResult();
        if (kbs != null && kbs > 0) {
            throw new BizException(ErrorCode.DEPT_NOT_EMPTY,
                    "该部门下仍有 " + kbs + " 个知识库，请先转移知识库再删除");
        }
        departmentRepository.delete(dept);
        return new AdminDtos.Deleted(true);
    }

    private int nextSort() {
        return departmentRepository.findAllByOrderBySortAsc().stream()
                .mapToInt(SysDepartment::getSort)
                .max()
                .orElse(0) + 1;
    }
}
