package com.knowledge.ai.service;

import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.enums.DataScope;
import com.knowledge.ai.core.enums.PermissionCode;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.dto.admin.AdminDtos;
import com.knowledge.ai.entity.SysRole;
import com.knowledge.ai.repository.SysRoleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色管理（T-203）+ 功能矩阵（T-204）。
 *
 * <p>两者放一起是因为矩阵的根本动作就是"改角色的档位"，拆开会导致
 * 改角色权限与改矩阵档位各写一套"内置角色保护"，迟早漂移。
 *
 * <p><b>时间格式</b>：出参一律 {@code yyyy-MM-dd HH:mm:ss} 字符串 ——
 * 前端矩阵页底部直接渲染「最近保存：{updatedAt}」，LocalDateTime 序列化
 * 成 ISO 带T格式会让那个句子读不通。
 */
@Service
@RequiredArgsConstructor
public class RoleAdminService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 与前端一致的 code 规则：大写字母开头，只含大写字母/数字/下划线。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    private final SysRoleRepository roleRepository;

    @PersistenceContext
    private EntityManager entityManager;

    // ==================== 查询 ====================

    /**
     * 角色列表（**不分页**：前端不发 page/size，响应 {@code {list,total}}）。
     *
     * <p>{@code dataScopeLabel} / {@code permissionCount} 是页面在渲染的派生字段
     * （《10》未登记）—— 缺了列就是空白，S1 的同型教训。{@code permissionCount}
     * 对超管要展开为 38：库里它只有一行通配映射，而前端显示的是权限点数。
     */
    @Transactional(readOnly = true)
    public AdminDtos.RoleListResponse list(String keyword, String type, String status) {
        List<SysRole> roles = roleRepository.findAll().stream()
                .sorted(Comparator.comparing(SysRole::getId))
                .toList();
        Map<Long, Long> memberCounts = memberCounts();
        Map<Long, Long> permCounts = permissionCounts();

        List<AdminDtos.RoleRow> rows = roles.stream()
                .filter(role -> matchKeyword(role, keyword))
                .filter(role -> matchType(role, type))
                .filter(role -> matchStatus(role, status))
                .map(role -> toRow(role, memberCounts, permCounts))
                .toList();
        return new AdminDtos.RoleListResponse(rows, rows.size());
    }

    /** 某角色已授予的权限码；超管的通配在库里是单行 {@code '*'}，这里展开为 38 个。 */
    @Transactional(readOnly = true)
    public AdminDtos.RolePermissionsResponse permissions(Long roleId) {
        SysRole role = roleRepository.findById(roleId)
                .orElseThrow(() -> new BizException(ErrorCode.ROLE_NOT_FOUND));
        return new AdminDtos.RolePermissionsResponse(codesOf(role));
    }

    // ==================== 增改删 / 启停 ====================

    @Transactional
    public AdminDtos.RoleRow create(AdminDtos.RoleSaveRequest request) {
        if (!CODE_PATTERN.matcher(request.code()).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "角色编码需为大写字母开头，仅含大写字母/数字/下划线");
        }
        roleRepository.findByCode(request.code()).ifPresent(existing -> {
            throw new BizException(ErrorCode.PARAM_INVALID, "角色编码已存在：" + request.code());
        });
        SysRole role = new SysRole();
        applyRequest(role, request);
        role.setBuiltin(false);
        role.setSort(nextSort());
        LocalDateTime now = LocalDateTime.now();
        role.setCreatedAt(now);
        role.setUpdatedAt(now);
        roleRepository.save(role);
        savePermissions(role, request.permissions());
        return toRow(role, memberCounts(), permissionCounts());
    }

    @Transactional
    public AdminDtos.RoleRow update(Long id, AdminDtos.RoleSaveRequest request) {
        SysRole role = roleRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.ROLE_NOT_FOUND));
        // 前端编辑禁改 code，后端同样拒绝 —— 两头一致才不会出现"绕过 UI 直调 API"改主键
        if (!role.getCode().equals(request.code())) {
            throw new BizException(ErrorCode.ROLE_BUILTIN_IMMUTABLE, "角色编码不可修改");
        }
        applyRequest(role, request);
        role.setUpdatedAt(LocalDateTime.now());
        roleRepository.save(role);
        savePermissions(role, request.permissions());
        return toRow(role, memberCounts(), permissionCounts());
    }

    /**
     * 删除。三道闸（《05》§0.6 + G2-4）：内置角色不可删；
     * 仍有成员先转移（《01》§2.1 五角色成员合计必须等于 128，删了带成员的角色会破坏这个恒等式）。
     */
    @Transactional
    public AdminDtos.Deleted delete(Long id) {
        SysRole role = roleRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.ROLE_NOT_FOUND));
        if (Boolean.TRUE.equals(role.getBuiltin())) {
            throw new BizException(ErrorCode.ROLE_BUILTIN_IMMUTABLE, "内置角色不可删除");
        }
        long members = memberCounts().getOrDefault(id, 0L);
        if (members > 0) {
            throw new BizException(ErrorCode.ROLE_IN_USE,
                    "角色「" + role.getName() + "」下存在 " + members + " 名成员，请先转移成员");
        }
        entityManager.createNativeQuery("delete from sys_role_permission where role_id = :id")
                .setParameter("id", id)
                .executeUpdate();
        entityManager.createNativeQuery("delete from sys_role_matrix where role_id = :id")
                .setParameter("id", id)
                .executeUpdate();
        roleRepository.delete(role);
        return new AdminDtos.Deleted(true);
    }

    /**
     * 启停。停用超管角色直接拒（G2-4）：它是"系统自锁"的最后一道防线 ——
     * 超管角色一停，全部管理员瞬间降级，而普通用户没有任何入口能把它启用回来。
     */
    @Transactional
    public AdminDtos.RoleRow patchStatus(Long id, boolean enabled) {
        SysRole role = roleRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.ROLE_NOT_FOUND));
        if (!enabled && "SUPER_ADMIN".equals(role.getCode())) {
            throw new BizException(ErrorCode.ROLE_BUILTIN_IMMUTABLE, "超级管理员角色不可停用");
        }
        role.setEnabled(enabled);
        role.setUpdatedAt(LocalDateTime.now());
        roleRepository.save(role);
        return toRow(role, memberCounts(), permissionCounts());
    }

    // ==================== 功能矩阵 ====================

    /**
     * 权限树（38 个原子码按 4 大域分组）。
     *
     * <p>分组节点的 id 用 {@code grp:域}（对齐前端 {@code PERMISSION_TREE} 的 id 约定）；
     * **叶子节点的 id 就是权限码本身** —— 前端勾选树用 id 当 key、提交时按 id→code 映射，
     * 两者必须相等，否则勾选「上传文档」提交出去的会是别的码。
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public AdminDtos.PermissionTreeResponse permissionTree() {
        Map<String, String> groupPrefix = Map.of(
                "知识问答", "qa",
                "知识库管理", "kb",
                "文档", "doc",
                "系统管理", "system");
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select code, label, group_name from sys_permission order by sort")
                .getResultList();

        Map<String, List<AdminDtos.PermissionNode>> byGroup = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String group = (String) row[2];
            byGroup.computeIfAbsent(group, k -> new ArrayList<>())
                    .add(new AdminDtos.PermissionNode((String) row[0], (String) row[1], (String) row[0], null));
        }

        List<AdminDtos.PermissionNode> tree = new ArrayList<>();
        long leaves = 0;
        for (Map.Entry<String, List<AdminDtos.PermissionNode>> entry : byGroup.entrySet()) {
            String prefix = groupPrefix.getOrDefault(entry.getKey(), entry.getKey());
            tree.add(new AdminDtos.PermissionNode("grp:" + prefix, entry.getKey(), null, entry.getValue()));
            leaves += entry.getValue().size();
        }
        return new AdminDtos.PermissionTreeResponse(tree, leaves);
    }

    /**
     * 矩阵读侧。⚠️ {@code levels} 的数组顺序 = roles 顺序（id 升序）——
     * 前端按 {@code levels[key][roleId - 1]} 取档位（见 {@link AdminDtos.MatrixResponse}）。
     * 没有档位行的角色按 NONE 补位，保证数组长度恒等于角色数。
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public AdminDtos.MatrixResponse matrix() {
        List<SysRole> roles = roleRepository.findAll().stream()
                .sorted(Comparator.comparing(SysRole::getId))
                .toList();
        List<AdminDtos.MatrixRole> matrixRoles = roles.stream()
                .map(r -> new AdminDtos.MatrixRole(r.getId(), r.getCode(), r.getName(), Boolean.TRUE.equals(r.getBuiltin())))
                .toList();

        List<Object[]> itemRows = entityManager.createNativeQuery(
                        "select item_key, label, group_name from sys_matrix_item order by sort")
                .getResultList();
        List<AdminDtos.MatrixItem> items = itemRows.stream()
                .map(row -> new AdminDtos.MatrixItem((String) row[0], (String) row[1], (String) row[2]))
                .toList();

        Map<String, Map<Long, String>> byKey = new HashMap<>();
        List<Object[]> levelRows = entityManager.createNativeQuery(
                        "select item_key, role_id, level from sys_role_matrix")
                .getResultList();
        for (Object[] row : levelRows) {
            byKey.computeIfAbsent((String) row[0], k -> new HashMap<>())
                    .put(((Number) row[1]).longValue(), (String) row[2]);
        }

        Map<String, List<String>> levels = new HashMap<>();
        for (AdminDtos.MatrixItem item : items) {
            Map<Long, String> byRole = byKey.getOrDefault(item.key(), Map.of());
            levels.put(item.key(), roles.stream()
                    .map(r -> byRole.getOrDefault(r.getId(), com.knowledge.ai.core.enums.GrantLevel.NONE.name()))
                    .toList());
        }

        LocalDateTime latest = roleRepository.findAll().stream()
                .map(SysRole::getUpdatedAt)
                .filter(java.time.LocalDateTime.class::isInstance)
                .max(LocalDateTime::compareTo)
                .orElse(null);
        return new AdminDtos.MatrixResponse(matrixRoles, items, levels,
                latest == null ? "" : TIME_FORMAT.format(latest), "");
    }

    /**
     * 矩阵保存（批量 diff 提交 🚩 权限变更）。
     *
     * <p>三道校验：角色存在、矩阵项存在、档位合法；超管列**整列拒绝**
     * （G2-3/G2-4：锁定只读 —— 它语义上恒为全权限，允许编辑等于允许把系统锁死）。
     * 字段是 {@code permKey}（前端实际发送），见 {@link AdminDtos.MatrixChange}。
     */
    @Transactional
    @AuditLog(module = AuditModule.GRANT, action = "保存功能矩阵", risk = RiskLevel.HIGH,
            target = "'矩阵变更 ' + #changes.size() + ' 项'")
    public AdminDtos.MatrixSaveResponse saveMatrix(List<AdminDtos.MatrixChange> changes) {
        Set<String> itemKeys = new LinkedHashSet<>();
        entityManager.createNativeQuery("select item_key from sys_matrix_item")
                .getResultList()
                .forEach(k -> itemKeys.add((String) k));

        int applied = 0;
        LocalDateTime now = LocalDateTime.now();
        for (AdminDtos.MatrixChange change : changes) {
            SysRole role = roleRepository.findById(change.roleId())
                    .orElseThrow(() -> new BizException(ErrorCode.ROLE_NOT_FOUND));
            if ("SUPER_ADMIN".equals(role.getCode())) {
                throw new BizException(ErrorCode.ROLE_BUILTIN_IMMUTABLE, "矩阵中超级管理员列锁定不可修改");
            }
            if (!itemKeys.contains(change.permKey())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "未知的功能项：" + change.permKey());
            }
            parseLevel(change.level());
            // 只允许已知档位（防"读写"这类中文值混进来）
            entityManager.createNativeQuery("""
                            insert into sys_role_matrix (role_id, item_key, level, updated_at)
                            values (:roleId, :itemKey, :level, :now)
                            on duplicate key update level = :level, updated_at = :now
                            """)
                    .setParameter("roleId", change.roleId())
                    .setParameter("itemKey", change.permKey())
                    .setParameter("level", change.level())
                    .setParameter("now", now)
                    .executeUpdate();
            applied++;
        }
        return new AdminDtos.MatrixSaveResponse(applied, TIME_FORMAT.format(now), "");
    }

    // ==================== 私有 ====================

    private void applyRequest(SysRole role, AdminDtos.RoleSaveRequest request) {
        if (!CODE_PATTERN.matcher(request.code()).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "角色编码需为大写字母开头，仅含大写字母/数字/下划线");
        }
        DataScope.parse(request.dataScope());
        role.setName(request.name());
        role.setCode(request.code());
        role.setDescription(request.description());
        role.setDataScope(request.dataScope());
        role.setEnabled(request.enabled() == null || request.enabled());
    }

    /** 权限码必须是 {@link PermissionCode} 里登记过的原子码 —— 防止前端传来矩阵 key（两者长得像）。 */
    private void savePermissions(SysRole role, List<String> codes) {
        for (String code : codes) {
            if (!PermissionCode.WILDCARD.equals(code) && PermissionCode.fromCode(code) == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "未知权限码：" + code);
            }
        }
        entityManager.createNativeQuery("delete from sys_role_permission where role_id = :id")
                .setParameter("id", role.getId())
                .executeUpdate();
        LocalDateTime now = LocalDateTime.now();
        for (String code : new LinkedHashSet<>(codes)) {
            entityManager.createNativeQuery("""
                            insert into sys_role_permission (role_id, permission_id, created_at)
                            select :roleId, p.id, :now from sys_permission p where p.code = :code
                            """)
                    .setParameter("roleId", role.getId())
                    .setParameter("code", code)
                    .setParameter("now", now)
                    .executeUpdate();
        }
    }

    private List<String> codesOf(SysRole role) {
        List<String> codes = entityManager.createNativeQuery("""
                        select p.code from sys_permission p
                        join sys_role_permission rp on rp.permission_id = p.id
                        where rp.role_id = :id order by p.sort
                        """)
                .setParameter("id", role.getId())
                .getResultList();
        if (codes.contains(PermissionCode.WILDCARD)) {
            return PermissionCode.allCodes();
        }
        return codes;
    }

    private Map<Long, Long> memberCounts() {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select ur.role_id, count(*) from sys_user_role ur
                        join sys_user u on u.id = ur.user_id and u.deleted_at is null
                        group by ur.role_id
                        """)
                .getResultList();
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    private Map<Long, Long> permissionCounts() {
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select role_id, count(*) from sys_role_permission group by role_id")
                .getResultList();
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    private AdminDtos.RoleRow toRow(SysRole role, Map<Long, Long> memberCounts, Map<Long, Long> permCounts) {
        boolean superAdmin = "SUPER_ADMIN".equals(role.getCode());
        long permissionCount = superAdmin
                ? PermissionCode.values().length
                : permCounts.getOrDefault(role.getId(), 0L);
        DataScope scope = DataScope.parse(role.getDataScope());
        return new AdminDtos.RoleRow(
                role.getId(),
                role.getName(),
                role.getCode(),
                role.getDescription(),
                Boolean.TRUE.equals(role.getBuiltin()),
                role.getDataScope(),
                scope == null ? role.getDataScope() : scope.getLabel(),
                memberCounts.getOrDefault(role.getId(), 0L),
                permissionCount,
                Boolean.TRUE.equals(role.getEnabled()),
                role.getCreatedAt() == null ? null : TIME_FORMAT.format(role.getCreatedAt()));
    }

    private boolean matchKeyword(SysRole role, String keyword) {
        return keyword == null || keyword.isBlank()
                || role.getName().contains(keyword)
                || role.getCode().contains(keyword)
                || (role.getDescription() != null && role.getDescription().contains(keyword));
    }

    private boolean matchType(SysRole role, String type) {
        if (type == null || "all".equalsIgnoreCase(type)) {
            return true;
        }
        return "BUILTIN".equalsIgnoreCase(type) == Boolean.TRUE.equals(role.getBuiltin());
    }

    private boolean matchStatus(SysRole role, String status) {
        if (status == null || "all".equalsIgnoreCase(status)) {
            return true;
        }
        return "ENABLED".equalsIgnoreCase(status) == Boolean.TRUE.equals(role.getEnabled());
    }

    private com.knowledge.ai.core.enums.GrantLevel parseLevel(String raw) {
        try {
            return com.knowledge.ai.core.enums.GrantLevel.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.PARAM_INVALID, "未知的矩阵档位：" + raw);
        }
    }

    private int nextSort() {
        return roleRepository.findAll().stream()
                .mapToInt(SysRole::getSort)
                .max()
                .orElse(0) + 1;
    }
}
