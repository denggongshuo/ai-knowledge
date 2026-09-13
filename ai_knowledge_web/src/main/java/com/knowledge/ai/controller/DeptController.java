package com.knowledge.ai.controller;

import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.dto.admin.AdminDtos;
import com.knowledge.ai.service.DeptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 部门接口（M02 · 《10》§3，T-200 / T-201）。
 *
 * <p>读侧（{@code GET /depts/tree}）**只要求登录**、不挂权限码：
 * {@code KbCreateView} 与 {@code KbDetailView} 都在用它选归属部门，
 * 挂上 {@code user:create} 之类的码会把普通用户的知识库创建页打挂 ——
 * 部门树是表单选项数据源，不是敏感清单。写侧才要求权限。
 */
@RestController
@RequestMapping("/api/depts")
@RequiredArgsConstructor
public class DeptController {

    private final DeptService deptService;

    /** {@code GET /depts/tree} —— 两层树 + 含子人数；「未分配」是 id=0 的节点。 */
    @GetMapping("/tree")
    public ApiResponse<java.util.List<AdminDtos.DeptNode>> tree() {
        return ApiResponse.ok(deptService.tree());
    }

    /** {@code POST /depts} —— body {@code { name, parentId? }}。 */
    @PostMapping
    public ApiResponse<AdminDtos.DeptCreated> create(@Valid @RequestBody AdminDtos.DeptSaveRequest request) {
        return ApiResponse.ok(deptService.create(request.name(), request.parentId()));
    }

    /** {@code PUT /depts/{id}} —— 改名 / 移动。 */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody AdminDtos.DeptSaveRequest request) {
        deptService.update(id, request.name(), request.parentId());
        return ApiResponse.ok(null);
    }

    /** {@code DELETE /depts/{id}} —— 有用户/子部门被拒。 */
    @DeleteMapping("/{id}")
    public ApiResponse<AdminDtos.Deleted> delete(@PathVariable Long id) {
        return ApiResponse.ok(deptService.delete(id));
    }
}
