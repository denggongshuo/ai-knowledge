package com.knowledge.ai.controller;

import com.knowledge.ai.core.enums.PermissionCode;
import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.security.RequiresPerm;
import com.knowledge.ai.dto.admin.AdminDtos;
import com.knowledge.ai.service.RoleAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色接口（M02 · 《10》§4，T-203）。
 *
 * <p>列表读侧**只要求登录**：用户管理页的创建弹窗、知识库创建/详情页的
 * 「可见角色」都把它当表单选项数据源（探查报告 §5 调用矩阵）——
 * 收紧到 {@code role:list} 会把普通用户的建库页打挂。写侧严格权限。
 */
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleAdminService roleAdminService;

    /**
     * {@code GET /roles?keyword=&type=&status=} —— **不分页**（前端不发 page/size，
     * 文档写了 page=&size= 是照抄模板，响应是 {@code {list,total}}）。
     * {@code type}: BUILTIN/CUSTOM；{@code status}: ENABLED/DISABLED。
     */
    @GetMapping
    public ApiResponse<AdminDtos.RoleListResponse> list(@RequestParam(required = false) String keyword,
                                                        @RequestParam(required = false) String type,
                                                        @RequestParam(required = false) String status) {
        return ApiResponse.ok(roleAdminService.list(keyword, type, status));
    }

    /** {@code POST /roles} —— 🚩 权限变更。 */
    @PostMapping
    @RequiresPerm("role:create")
    public ApiResponse<AdminDtos.RoleRow> create(@Valid @RequestBody AdminDtos.RoleSaveRequest request) {
        return ApiResponse.ok(roleAdminService.create(request));
    }

    /** {@code PUT /roles/{id}} —— 🚩 权限变更。编辑时 {@code code} 不可改（前端禁改，后端同样拒绝）。 */
    @PutMapping("/{id}")
    @RequiresPerm("role:update")
    public ApiResponse<AdminDtos.RoleRow> update(@PathVariable Long id,
                                                 @Valid @RequestBody AdminDtos.RoleSaveRequest request) {
        return ApiResponse.ok(roleAdminService.update(id, request));
    }

    /** {@code DELETE /roles/{id}} —— 🚩 内置角色 / 带成员被拒（G2-4）。 */
    @DeleteMapping("/{id}")
    @RequiresPerm("role:delete")
    public ApiResponse<AdminDtos.Deleted> delete(@PathVariable Long id) {
        return ApiResponse.ok(roleAdminService.delete(id));
    }

    /** {@code PATCH /roles/{id}/status} —— body {@code { enabled }}；停用超管角色被拒。 */
    @PatchMapping("/{id}/status")
    @RequiresPerm("role:update")
    public ApiResponse<AdminDtos.RoleRow> patchStatus(@PathVariable Long id,
                                                      @Valid @RequestBody AdminDtos.RoleStatusRequest request) {
        return ApiResponse.ok(roleAdminService.patchStatus(id, request.enabled()));
    }

    /** {@code GET /roles/{id}/permissions} —— {@code { codes }}；超管的 {@code '*'} 展开为 38 个。 */
    @GetMapping("/{id}/permissions")
    public ApiResponse<AdminDtos.RolePermissionsResponse> permissions(@PathVariable Long id) {
        return ApiResponse.ok(roleAdminService.permissions(id));
    }
}
