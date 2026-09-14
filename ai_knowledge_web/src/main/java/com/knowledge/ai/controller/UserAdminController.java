package com.knowledge.ai.controller;

import com.knowledge.ai.core.enums.PermissionCode;
import com.knowledge.ai.core.model.ApiResponse;
import com.knowledge.ai.core.security.RequiresPerm;
import com.knowledge.ai.dto.admin.AdminDtos;
import com.knowledge.ai.service.UserAdminService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户管理接口（M02 · 《10》§3，T-205~T-209）。
 *
 * <p>与 {@link UserController}（{@code /api/user/**}，用户管自己）是两个域：
 * 这里每个方法都是"管别人"，全部挂功能权限码，列表再叠加数据范围（T-212）。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserAdminService userAdminService;

    /**
     * {@code GET /users?deptId=&keyword=&roleId=&status=&page=&size=}。
     *
     * <p>{@code roleId} 是角色**编码**（前端下拉 :value 绑 code）；
     * {@code deptId} 支持 {@code all} 与部门 id（选父部门含子部门）；
     * 数据范围在 SQL 层过滤（G2-8）。前端实际发 {@code page/size}。
     */
    @GetMapping
    @RequiresPerm("user:list")
    public ApiResponse<AdminDtos.UserPage> page(@RequestParam(name = "deptId", required = false) String deptId,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(name = "roleId", required = false) String roleId,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(defaultValue = "1") long page,
                                                @RequestParam(name = "size", defaultValue = "20") long size) {
        return ApiResponse.ok(userAdminService.page(deptId, keyword, roleId, status, page, size));
    }

    /** {@code POST /users} —— 🚩。inviteMode = INVITE（待激活+邀请令牌）| PASSWORD（激活+初始密码）。 */
    @PostMapping
    @RequiresPerm("user:create")
    public ApiResponse<AdminDtos.UserCreateResponse> create(
            @Valid @RequestBody AdminDtos.UserCreateRequest request) {
        return ApiResponse.ok(userAdminService.create(request));
    }

    /**
     * {@code PUT /users/{id}} —— 编辑（含角色全量替换，🚩 权限变更）。
     * 批量分配角色也走这里（前端在旧角色数组上追加后整发）。
     */
    @PutMapping("/{id}")
    @RequiresPerm("user:update")
    public ApiResponse<AdminDtos.UserRow> update(@PathVariable Long id,
                                                 @Valid @RequestBody AdminDtos.UserUpdateRequest request) {
        return ApiResponse.ok(userAdminService.update(id, request));
    }

    /** {@code PATCH /users/{id}/status} —— 🚩。只允许 ACTIVE/DISABLED；自锁防护在服务端兜底（G2-5）。 */
    @PatchMapping("/{id}/status")
    @RequiresPerm("user:update")
    public ApiResponse<AdminDtos.UserRow> patchStatus(@PathVariable Long id,
                                                      @RequestBody Map<String, String> body) {
        return ApiResponse.ok(userAdminService.patchStatus(id, body.get("status")));
    }

    /** {@code POST /users/{id}/reset-password} —— 🚩。新密码随响应返回（v1 无通知通道）。 */
    @PostMapping("/{id}/reset-password")
    @RequiresPerm("user:reset-pwd")
    public ApiResponse<AdminDtos.ResetPasswordResponse> resetPassword(@PathVariable Long id) {
        return ApiResponse.ok(userAdminService.resetPassword(id));
    }

    /** {@code POST /users/{id}/resend-invite} —— 仅 PENDING。 */
    @PostMapping("/{id}/resend-invite")
    @RequiresPerm("user:update")
    public ApiResponse<AdminDtos.ResendInviteResponse> resendInvite(@PathVariable Long id) {
        return ApiResponse.ok(userAdminService.resendInvite(id));
    }

    /**
     * {@code POST /users/import} —— 🚩 {@code multipart/form-data}，逐行校验报告。
     * 字段名 {@code file}（与 {@code /user/avatar} 一致）；CSV 先行（前端按钮尚未真调，
     * 见 {@link UserAdminService#importUsers} 的决策说明）。
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPerm("user:import")
    public ApiResponse<AdminDtos.ImportResponse> importUsers(@RequestPart("file") MultipartFile file) {
        try {
            return ApiResponse.ok(userAdminService.importUsers(file.getBytes()));
        } catch (java.io.IOException ex) {
            throw new com.knowledge.ai.core.exception.BizException(
                    com.knowledge.ai.core.exception.ErrorCode.IMPORT_TEMPLATE_INVALID, "文件读取失败");
        }
    }

    /**
     * {@code DELETE /users/{id}} —— 🚩 删除账号（**软删**）。
     *
     * <p>三道闸（G2-6，服务端是唯一防线 —— 前端 {@code removeUser} 只做了本地拦截）：
     * 不能删自己 / 不能删最后一个超管 / **有业务数据只能禁用**。
     */
    @DeleteMapping("/{id}")
    @RequiresPerm("user:delete")
    public ApiResponse<AdminDtos.Deleted> delete(@PathVariable Long id) {
        return ApiResponse.ok(userAdminService.delete(id));
    }
}
