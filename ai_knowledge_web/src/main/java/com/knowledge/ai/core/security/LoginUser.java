package com.knowledge.ai.core.security;

import com.knowledge.ai.core.enums.DataScope;
import com.knowledge.ai.core.enums.PermissionCode;
import com.knowledge.ai.core.enums.SecretLevel;
import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.Set;

/**
 * 登录用户（请求级上下文对象）。
 *
 * <p>由 {@link JwtAuthFilter} 从 JWT 解析后放入 {@link LoginUserContext}，
 * 整个请求生命周期内业务代码用 {@code LoginUserContext.require()} 取用。
 *
 * <p><b>设计取舍</b>：S0 阶段把 {@code permissions} 直接编进 JWT（≤38 个短串，
 * token 约 1KB），换取「鉴权零 DB 依赖」—— 这样 G0-1/G0-2 的前后端联调、
 * 以及 {@code @RequiresPerm} 的链路自测都不需要先建权限表。
 * 若后续 token 体积成为问题，可改为只编 {@code roles}，
 * 再由缓存（Redis）解析出权限码，届时只需改 {@link JwtAuthFilter} 与
 * {@code JwtService}，业务代码无感。
 */
@Data
@Builder
public class LoginUser {

    private Long userId;

    /** 登录账号（唯一）。 */
    private String username;

    /** 真实姓名，用于展示与审计。 */
    private String realName;

    /** 所属部门 id。数据范围计算基准，**不是**数据范围本身。 */
    private Long deptId;

    /** 是否超级管理员（拥有 {@code *} 通配权限）。 */
    private boolean superAdmin;

    /** 角色编码集合，如 {@code ["KB_ADMIN"]}。 */
    @Builder.Default
    private Set<String> roleCodes = Collections.emptySet();

    /**
     * 原子权限码并集（多角色取并集，见《05-需求-权限与审计》§0.5）。
     * 超管用 {@link PermissionCode#WILDCARD} 表示全量。
     */
    @Builder.Default
    private Set<String> permissions = Collections.emptySet();

    /** 数据范围（多角色**取最宽**）。 */
    @Builder.Default
    private DataScope dataScope = DataScope.SELF;

    /** 可访问的密级上限。高于它的分片不参与召回。 */
    @Builder.Default
    private SecretLevel secretLevelUpper = SecretLevel.INTERNAL;

    /** JWT 的 jti，用于注销时加入黑名单。 */
    private String tokenId;

    /**
     * 是否拥有某个权限点。
     * 超管（{@code *}）对任何权限点都返回 {@code true}。
     */
    public boolean hasPermission(String code) {
        return hasAnyPermission(code);
    }

    /** 任意一个命中即通过（OR）。 */
    public boolean hasAnyPermission(String... codes) {
        if (codes == null || codes.length == 0) {
            return true;
        }
        if (permissions.contains(PermissionCode.WILDCARD)) {
            return true;
        }
        for (String code : codes) {
            if (code != null && permissions.contains(code)) {
                return true;
            }
        }
        return false;
    }

    /** 全部命中才通过（AND）。 */
    public boolean hasAllPermissions(String... codes) {
        if (codes == null || codes.length == 0) {
            return true;
        }
        if (permissions.contains(PermissionCode.WILDCARD)) {
            return true;
        }
        for (String code : codes) {
            if (code == null || !permissions.contains(code)) {
                return false;
            }
        }
        return true;
    }

    /** 展示用：{@code 林工（lin.gong）}。 */
    public String display() {
        if (realName == null || realName.isBlank()) {
            return username;
        }
        return realName + "（" + username + "）";
    }
}
