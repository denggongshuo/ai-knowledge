package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.DataScope;
import com.knowledge.ai.core.enums.PermissionCode;
import com.knowledge.ai.core.enums.SecretLevel;
import com.knowledge.ai.dto.auth.AuthDtos;
import com.knowledge.ai.entity.SysDepartment;
import com.knowledge.ai.entity.SysUser;
import com.knowledge.ai.repository.PermissionQueryRepository;
import com.knowledge.ai.repository.SysDepartmentRepository;
import com.knowledge.ai.repository.SysRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 把 {@link SysUser} 组装成「鉴权主体」与「出参视图」。
 *
 * <p>本类是**唯一的组装点**：登录、刷新令牌、{@code GET /user/profile}、
 * {@code GET /user/permissions} 都需要"用户的角色 + 权限 + 数据范围"，
 * 如果各处各查一遍，最容易出现的 bug 是**某一处忘了取并集**（多角色用户
 * 在某个页面少一个权限），而且这种 bug 只在多角色账号上暴露，很难发现。
 *
 * <p><b>为什么不用 JPA 的 {@code @ManyToMany} 关联</b>：{@code sys_user_role} /
 * {@code sys_role_permission} 是纯连接表，用关联映射会带来 N+1 查询与
 * "改动一个角色权限需要重载整个对象图"的代价。这里改成三条显式查询
 * （角色 / 权限 / 部门），每条都只取需要的列，数量固定为 3，与角色数无关。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPrincipalAssembler {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 「未分配」虚拟部门的 id（V1 建表注释：它必须真的是 0）。 */
    private static final Long UNASSIGNED_DEPT_ID = 0L;

    public static final String UNASSIGNED_DEPT_NAME = "未分配";

    private final SysRoleRepository roleRepository;

    private final SysDepartmentRepository departmentRepository;

    private final PermissionQueryRepository permissionQueryRepository;

    /** 用户 + 角色 + 权限 的组装结果（供签令牌与出参共用，避免各查一半）。 */
    public record Principal(
            SysUser user,
            List<SysRoleRepository.UserRoleView> roles,
            Set<String> permissionCodes,
            DataScope dataScope,
            String departmentName) {

        public boolean superAdmin() {
            return permissionCodes.contains(PermissionCode.WILDCARD);
        }

        public Set<String> roleCodes() {
            Set<String> codes = new LinkedHashSet<>(roles.size());
            roles.forEach(role -> codes.add(role.getCode()));
            return codes;
        }

        public List<AuthDtos.RoleItem> roleItems() {
            return roles.stream().map(role -> new AuthDtos.RoleItem(role.getCode(), role.getName())).toList();
        }
    }

    /**
     * 组装鉴权主体。
     *
     * <p>数据范围按 {@link DataScope#widest} **取最宽** —— 多角色叠加时
     * 任何一个角色给了更大范围，用户就拥有该范围（《05》§0.5）。
     */
    @Transactional(readOnly = true)
    public Principal assemble(SysUser user) {
        List<SysRoleRepository.UserRoleView> roles = roleRepository.findRolesByUserId(user.getId());
        Set<String> permissions = permissionQueryRepository.findPermissionCodes(user.getId());

        DataScope scope = null;
        for (SysRoleRepository.UserRoleView role : roles) {
            scope = DataScope.widest(scope, parseScope(role.getDataScope()));
        }

        return new Principal(user, roles, permissions, scope == null ? DataScope.SELF : scope,
                departmentName(user.getDeptId()));
    }

    /** 由鉴权主体构造请求级登录用户（JWT claims 的来源）。 */
    public com.knowledge.ai.core.security.LoginUser toLoginUser(Principal principal, String tokenId) {
        boolean superAdmin = principal.superAdmin();
        return com.knowledge.ai.core.security.LoginUser.builder()
                .userId(principal.user().getId())
                .username(principal.user().getUsername())
                .realName(principal.user().getRealName())
                .deptId(principal.user().getDeptId())
                .superAdmin(superAdmin)
                .roleCodes(principal.roleCodes().isEmpty() ? Collections.emptySet() : principal.roleCodes())
                .permissions(principal.permissionCodes())
                .dataScope(principal.dataScope())
                // 密级上限：超管可读全部密级，其他人默认到「内部」。
                // v1 不给普通角色开放可读密级配置（《03》I-6 只规定"文档密级不得低于知识库密级"），
                // 将来若需要按角色配密级上限，改这一处即可。
                .secretLevelUpper(superAdmin ? SecretLevel.SECRET : SecretLevel.INTERNAL)
                .tokenId(tokenId)
                .build();
    }

    /** 由鉴权主体构造出参视图（字段对齐前端 {@code AuthUser}）。 */
    public AuthDtos.UserView toUserView(Principal principal) {
        SysUser user = principal.user();
        return new AuthDtos.UserView(
                user.getId(),
                user.getUsername(),
                user.getRealName(),
                user.getPhone(),
                user.getEmail(),
                user.getDeptId(),
                principal.departmentName(),
                new ArrayList<>(principal.roleCodes()),
                principal.roles().stream().map(SysRoleRepository.UserRoleView::getName).toList(),
                new ArrayList<>(principal.permissionCodes()),
                user.getAvatar(),
                user.getPosition(),
                user.getIntro(),
                user.getStatus() == null ? null : user.getStatus().name(),
                Boolean.TRUE.equals(user.getMustChangePassword()),
                user.getLastLoginAt() == null ? null : TIME_FORMAT.format(user.getLastLoginAt()),
                user.getCreatedAt() == null ? null : TIME_FORMAT.format(user.getCreatedAt()));
    }

    /**
     * 部门全路径名。
     *
     * <p>两层结构：子部门返回「父 / 子」，顶级部门返回自身名。
     * 「未分配」（{@code deptId = 0}）是虚拟节点，直接返回固定文案 ——
     * 它不在部门树里，查库也查不到。
     */
    @Transactional(readOnly = true)
    public String departmentName(Long deptId) {
        if (deptId == null || UNASSIGNED_DEPT_ID.equals(deptId)) {
            return UNASSIGNED_DEPT_NAME;
        }
        Map<Long, SysDepartment> byId = departmentRepository.findAllByOrderBySortAsc().stream()
                .collect(Collectors.toMap(SysDepartment::getId, Function.identity(), (a, b) -> a));
        SysDepartment dept = byId.get(deptId);
        if (dept == null) {
            return UNASSIGNED_DEPT_NAME;
        }
        if (dept.getParentId() != null) {
            SysDepartment parent = byId.get(dept.getParentId());
            if (parent != null) {
                return parent.getName() + " / " + dept.getName();
            }
        }
        return dept.getName();
    }

    /** 手机号脱敏：{@code 13800000001 → 138****0001}。忘记密码回显与安全概览都要用。 */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /** 邮箱脱敏：{@code admin@corp.com → ad***@corp.com}。 */
    public static String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return email;
        }
        String local = email.substring(0, at);
        String domain = email.substring(at);
        if (local.length() <= 2) {
            return local.charAt(0) + "***" + domain;
        }
        return local.substring(0, 2) + "***" + domain;
    }

    private DataScope parseScope(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DataScope.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            log.warn("未知的数据范围取值：{}（按 SELF 处理）", raw);
            return DataScope.SELF;
        }
    }
}
