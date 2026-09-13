package com.knowledge.ai.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * M02（权限与组织）的出入参。
 *
 * <p><b>字段名的事实源不是本文档，而是前端</b>：本包的字段逐一对齐
 * {@code ai_knowledge_app} 的 {@code types/index.ts} 与 mock 返回体
 * （2026-09-13 用探查代理逐接口核对过）。两个容易改错的点：
 * <ul>
 *   <li>用户列表行的部门字段叫 {@code department}（全路径名「研发中心 / 平台架构组」），
 *       不是 {@code deptName} —— 前端没有 {@code deptName} 这个消费点；</li>
 *   <li>矩阵保存的字段是 {@code permKey}，《10》§4 写的 {@code permCode} 是笔误
 *       （前端 {@code saveMatrix} 发的就是 {@code permKey}）。</li>
 * </ul>
 *
 * <p>派生字段（{@code roleNames} / {@code dataScopeLabel} / {@code permissionCount} /
 * {@code statusLabel}）在《10》里没有登记，但页面在渲染 —— 缺了就静默空白，见 S1 同型坑。
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    // ==================== 部门 ====================

    /**
     * 部门树节点。{@code userCount} 是**含子节点**的人数（前端树右侧直接渲染）；
     * 「未分配」是 {@code id = 0} 的虚拟节点，与真实部门同构。
     */
    public record DeptNode(Long id, String name, Long parentId, long userCount, List<DeptNode> children) {
    }

    public record DeptSaveRequest(@NotBlank String name, Long parentId) {
    }

    public record DeptCreated(Long id, String name, Long parentId) {
    }

    public record Deleted(boolean deleted) {
    }

    // ==================== 权限 ====================

    /**
     * 权限树节点（38 个原子码按 4 大域分组）。**叶节点的 {@code id} 就是权限码本身**
     * （前端勾选树用 id 当 key，提交时按 id→code 映射，两者相等才不会错位）。
     */
    public record PermissionNode(String id, String label, String code, List<PermissionNode> children) {
    }

    public record PermissionTreeResponse(List<PermissionNode> tree, long total) {
    }

    // ==================== 角色 ====================

    public record RoleRow(long id, String name, String code, String description, boolean builtin,
                          String dataScope, String dataScopeLabel, long memberCount, long permissionCount,
                          boolean enabled, String createdAt) {
    }

    /** 角色列表不分页：前端不发 page/size，响应就是 {@code { list, total }}。 */
    public record RoleListResponse(List<RoleRow> list, long total) {
    }

    /**
     * 创建 / 编辑共用。{@code permissions} 是权限**码数组**（前端由勾选叶子映射而来）；
     * {@code code} 全站是字符串主键，编辑时前端禁改。
     */
    public record RoleSaveRequest(@NotBlank String name, @NotBlank String code, String description,
                                  @NotNull String dataScope, Boolean enabled,
                                  @NotEmpty List<String> permissions) {
    }

    public record RoleStatusRequest(@NotNull Boolean enabled) {
    }

    public record RolePermissionsResponse(List<String> codes) {
    }

    // ==================== 功能矩阵 ====================

    /**
     * 矩阵读侧。⚠️ {@code levels} 的每个数组**必须与 {@code roles} 的顺序一致**
     * （角色 id 升序）：前端按 {@code levels[key][roleId - 1]} 取档位，
     * 顺序错位会让整列显示错档 —— 这是本接口最隐蔽的契约。
     */
    public record MatrixResponse(List<MatrixRole> roles, List<MatrixItem> items,
                                 Map<String, List<String>> levels, String updatedAt, String updatedBy) {
    }

    public record MatrixRole(long id, String code, String name, boolean builtin) {
    }

    public record MatrixItem(String key, String label, String group) {
    }

    /** ⚠️ 字段名是 {@code permKey}（前端实际发送），《10》§4 写的 {@code permCode} 是笔误。 */
    public record MatrixChange(@NotNull Long roleId, @NotNull String permKey, @NotNull String level) {
    }

    public record MatrixSaveRequest(@NotEmpty List<MatrixChange> changes) {
    }

    public record MatrixSaveResponse(int applied, String updatedAt, String updatedBy) {
    }

    // ==================== 用户管理 ====================

    public record UserRow(long id, String username, String realName, String phone, String email,
                          String department, Long deptId, List<String> roles, List<String> roleNames,
                          String status, String statusLabel, String createdAt, String lastLoginAt,
                          boolean mustChangePassword, boolean hasBusinessData) {
    }

    /** 分页参数前端发 {@code page/size}；响应体统一 {@code page + pageSize}（前端只读 list/total）。 */
    public record UserPage(List<UserRow> list, long total, long page, long pageSize) {
    }

    /**
     * 创建请求。⚠️ 名字字段是 {@code name}（编辑时是 {@code realName}，两处不同，前端就这么发的）。
     * {@code inviteMode}: {@code INVITE}（发邀请链接，自助设密）| {@code PASSWORD}（管理员设初始密码）。
     */
    public record UserCreateRequest(@NotBlank String name, @NotBlank String username, String phone,
                                    String email, @NotNull Long deptId, @NotEmpty List<String> roleIds,
                                    @NotBlank String inviteMode, Integer inviteDays) {
    }

    /**
     * {@code initialPassword} 是**纯新增**字段：mock 不返回它，前端也不消费，
     * 但 v1 没有邮件/短信通道，管理员设初始密码后必须有一次拿到明文的机会，
     * 否则这个账号谁也登不进。仅 {@code inviteMode = PASSWORD} 时有值。
     */
    public record UserCreateResponse(long id, String status, String initialPassword) {
    }

    public record UserUpdateRequest(@NotBlank String realName, String phone, String email,
                                    @NotNull Long deptId, @NotNull List<String> roleIds) {
    }

    public record UserStatusRequest(@NotBlank String status) {
    }

    /**
     * {@code newPassword} 同 {@link UserCreateResponse} 的理由：重置后必须有人能登录
     * 这个账号。mock 只返回 {@code { reset, mustChangePassword, message }}，多出的字段前端忽略。
     */
    public record ResetPasswordResponse(boolean reset, boolean mustChangePassword, String message,
                                        String newPassword) {
    }

    public record ResendInviteResponse(boolean sent, int inviteExpireDays) {
    }

    /** 批量导入的逐行错误（行号从 1 起，对应模板数据行）。 */
    public record ImportRowError(int row, String error) {
    }

    public record ImportResponse(int success, int failed, List<ImportRowError> rows) {
    }

    // ==================== 知识库授权（T-211） ====================

    public record RoleBrief(String code, String name) {
    }

    /** {@code GET /kb/grants} 行。⚠️ 分页参数前端发 {@code page/pageSize}（与 /users 的 size 并存）。 */
    public record KbGrantRow(long kbId, String name, String department, long docCount, String updatedAt,
                             List<RoleBrief> roles, String level) {
    }

    public record KbGrantPage(List<KbGrantRow> list, long total, long page, long pageSize) {
    }

    /** {@code GET /kb/{kbId}/grants} 的角色行 —— 与列表行的 {@code roles:[{code,name}]} **结构不同**，前端分开消费。 */
    public record KbGrantRoleDetail(long roleId, String name, long memberCount, boolean granted) {
    }

    public record KbGrantExtraUser(long userId, String name) {
    }

    public record KbGrantDetail(long kbId, String name, String department, long docCount,
                                List<KbGrantRoleDetail> roles, String level, List<KbGrantExtraUser> extraUsers) {
    }

    /** 保存 = 全量替换该库的授权（角色集合 + 额外用户集合 + 统一 level，页面就是单值模型）。 */
    public record KbGrantSaveRequest(@NotNull List<Long> roleIds, @NotBlank String level,
                                     @NotNull List<Long> extraUserIds) {
    }

    public record KbGrantSaved(boolean saved) {
    }
}
