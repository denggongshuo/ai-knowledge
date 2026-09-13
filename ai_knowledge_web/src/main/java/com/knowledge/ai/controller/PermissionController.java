package com.knowledge.ai.controller;

import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.security.RequiresPerm;
import com.knowledge.ai.dto.admin.AdminDtos;
import com.knowledge.ai.service.RoleAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 权限树与功能矩阵（M02 · 《10》§4，T-202 / T-204）。
 *
 * <p>权限码的事实源是 {@code PermissionCode} 枚举（38 个），{@code sys_permission}
 * 只是它在库里的投影 —— 树接口从库里读（分组与排序由种子数据定），
 * total 是**叶子数**（前端角标显示权限点数 38，不是分组数 4）。
 */
@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final RoleAdminService roleAdminService;

    /** {@code GET /permissions/tree} —— {@code { tree, total }}，total = 38。 */
    @GetMapping("/tree")
    public ApiResponse<AdminDtos.PermissionTreeResponse> tree() {
        return ApiResponse.ok(roleAdminService.permissionTree());
    }

    /** {@code GET /permissions/matrix} —— 12 项 × 5 角色；levels 数组与角色 id 升序严格对齐。 */
    @GetMapping("/matrix")
    @RequiresPerm("grant:matrix")
    public ApiResponse<AdminDtos.MatrixResponse> matrix() {
        return ApiResponse.ok(roleAdminService.matrix());
    }

    /**
     * {@code PUT /permissions/matrix} —— 批量 diff 提交 🚩 权限变更高危。
     * ⚠️ 请求字段是 {@code permKey}（前端实际发送），《10》§4 写的 {@code permCode} 是笔误。
     */
    @PutMapping("/matrix")
    @RequiresPerm("grant:matrix")
    public ApiResponse<AdminDtos.MatrixSaveResponse> saveMatrix(
            @Valid @RequestBody AdminDtos.MatrixSaveRequest request) {
        return ApiResponse.ok(roleAdminService.saveMatrix(request.changes()));
    }
}
