package com.knowledge.ai.service;

import com.knowledge.ai.core.audit.AuditContext;
import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.enums.KbGrantLevel;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.dto.admin.AdminDtos;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库授权（T-211）+ 可见性/级别求交（T-212 的使用方）。
 *
 * <p><b>页面是"单值 level + 角色集合"模型</b>（mock 定下的交互），
 * 而库里 {@code sys_kb_grant} 是每主体一行级别。折中：保存时把全部角色写成
 * 同一 level；读取时 level 取**最高**档 —— 这与"多角色叠加取最高"（《05》§0.5）
 * 是同一条规则，页面上永远显示用户实际能拿到的最强档位。
 */
@Service
@RequiredArgsConstructor
public class KbGrantService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 页面单选的合法档位。NONE = 不可见 = 不授权，语义上不该出现在保存请求里。 */
    private static final Set<String> SAVABLE_LEVELS = Set.of("READ", "UPLOAD", "MANAGE");

    private final DataScopeService dataScopeService;

    /** 文档数**唯一**口径（2026-10-12）。授权页原来读 `sys_kb.doc_count` 那个示例常量。 */
    private final KbCountReader kbCountReader;

    @PersistenceContext
    private EntityManager entityManager;

    // ==================== 列表（GET /kb/grants） ====================

    /** 分页参数是 {@code page/pageSize}（前端这里与 /users 的 size 并存，见契约报告）。 */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public AdminDtos.KbGrantPage list(long page, long pageSize) {
        long total = ((Number) entityManager.createNativeQuery("select count(*) from sys_kb")
                .getSingleResult()).longValue();
        long safeSize = pageSize <= 0 ? 8 : Math.min(pageSize, 100);
        long safePage = page <= 0 ? 1 : page;

        // 不选 doc_count：那一列是 V7 seed 的示例常量（全仓无人写），文档数一律现算，
        // 与知识库列表 / 配额卡同一口径（见 KbCountReader）
        List<Object[]> kbs = entityManager.createNativeQuery(
                        "select id, code, name, dept_id, updated_at from sys_kb order by id")
                .setFirstResult((int) ((safePage - 1) * safeSize))
                .setMaxResults((int) safeSize)
                .getResultList();

        Map<Long, KbCountReader.Counts> counts = kbCountReader.countsOf(
                kbs.stream().map(kb -> ((Number) kb[0]).longValue()).toList());

        Map<Long, List<AdminDtos.RoleBrief>> rolesByKb = new HashMap<>();
        Map<Long, Integer> rankByKb = new HashMap<>();
        List<Object[]> grants = entityManager.createNativeQuery("""
                        select g.kb_id, r.code, r.name, g.level from sys_kb_grant g
                        join sys_role r on r.id = g.principal_id
                        where g.principal_type = 'ROLE' and g.level <> 'NONE'
                        order by r.id
                        """)
                .getResultList();
        for (Object[] row : grants) {
            long kbId = ((Number) row[0]).longValue();
            rolesByKb.computeIfAbsent(kbId, k -> new ArrayList<>())
                    .add(new AdminDtos.RoleBrief((String) row[1], (String) row[2]));
            int rank = rankOf((String) row[3]);
            rankByKb.merge(kbId, rank, Math::max);
        }

        List<AdminDtos.KbGrantRow> rows = new ArrayList<>();
        for (Object[] kb : kbs) {
            long kbId = ((Number) kb[0]).longValue();
            long deptId = ((Number) kb[3]).longValue();
            rows.add(new AdminDtos.KbGrantRow(
                    kbId,
                    (String) kb[2],
                    departmentName(deptId),
                    counts.getOrDefault(kbId, KbCountReader.Counts.ZERO).docCount(),
                    toTimeText(kb[4]),
                    rolesByKb.getOrDefault(kbId, List.of()),
                    levelName(rankByKb.getOrDefault(kbId, 0))));
        }
        return new AdminDtos.KbGrantPage(rows, total, safePage, safeSize);
    }

    // ==================== 详情（GET /kb/{kbId}/grants） ====================

    /**
     * 全量角色清单 + granted 标记（前端勾选回显）+ 额外用户。
     * level 取**最高**档 —— 单值模型下展示最强档位（见类注释）。
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public AdminDtos.KbGrantDetail detail(long kbId) {
        // 同上：doc_count 不取（示例常量），文档数现算
        Object[] kb = (Object[]) entityManager.createNativeQuery(
                        "select name, dept_id from sys_kb where id = :id")
                .setParameter("id", kbId)
                .getSingleResult();

        Map<Long, Integer> grantedRoles = new HashMap<>();
        List<Object[]> grantRows = entityManager.createNativeQuery(
                        "select principal_id, level from sys_kb_grant where kb_id = :id and principal_type = 'ROLE'")
                .setParameter("id", kbId)
                .getResultList();
        for (Object[] row : grantRows) {
            grantedRoles.put(((Number) row[0]).longValue(), rankOf((String) row[1]));
        }

        List<Object[]> roleRows = entityManager.createNativeQuery(
                        "select id, name, code from sys_role order by id")
                .getResultList();
        Map<Long, Long> memberCounts = memberCounts();
        List<AdminDtos.KbGrantRoleDetail> roles = new ArrayList<>();
        int maxRank = 0;
        for (Object[] row : roleRows) {
            long roleId = ((Number) row[0]).longValue();
            Integer rank = grantedRoles.get(roleId);
            roles.add(new AdminDtos.KbGrantRoleDetail(roleId, (String) row[1],
                    memberCounts.getOrDefault(roleId, 0L), rank != null));
            if (rank != null) {
                maxRank = Math.max(maxRank, rank);
            }
        }

        List<AdminDtos.KbGrantExtraUser> extraUsers = new ArrayList<>();
        List<Object[]> userRows = entityManager.createNativeQuery("""
                        select u.id, u.real_name from sys_kb_grant g
                        join sys_user u on u.id = g.principal_id
                        where g.kb_id = :id and g.principal_type = 'USER' and g.level <> 'NONE'
                        order by u.id
                        """)
                .setParameter("id", kbId)
                .getResultList();
        for (Object[] row : userRows) {
            extraUsers.add(new AdminDtos.KbGrantExtraUser(((Number) row[0]).longValue(), (String) row[1]));
        }

        return new AdminDtos.KbGrantDetail(kbId, (String) kb[0], departmentName(((Number) kb[1]).longValue()),
                kbCountReader.countOf(kbId).docCount(), roles, levelName(maxRank), extraUsers);
    }

    // ==================== 保存（PUT /kb/{kbId}/grants 🚩） ====================

    /**
     * 全量替换该库授权。角色与额外用户**至少一项**（前端本地校验，这里兜底）——
     * 全空 = 无人可见该库，更接近"删库"而不是"改授权"，必须显式走知识库删除。
     */
    @Transactional
    @AuditLog(module = AuditModule.GRANT, action = "调整知识库授权", risk = RiskLevel.HIGH,
            target = "'知识库#' + #kbId")
    public AdminDtos.KbGrantSaved save(long kbId, AdminDtos.KbGrantSaveRequest request) {
        requireKb(kbId);
        if (!SAVABLE_LEVELS.contains(request.level())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "level 仅支持 READ/UPLOAD/MANAGE（NONE = 不可见，请直接移除授权而不是保存 NONE）");
        }
        if ((request.roleIds() == null || request.roleIds().isEmpty())
                && (request.extraUserIds() == null || request.extraUserIds().isEmpty())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请至少配置一个可见角色或额外授权用户");
        }

        // ============ T-300：先读旧值并登记，再执行全量替换 ============
        // 该接口是"删光重建"式全量替换，一旦开始 executeUpdate 旧值就没了，
        // 所以登记必须在两条 delete 之前。
        String[] before = grantSnapshot(kbId);
        AuditContext.change("授权级别", before[0], request.level());
        AuditContext.change("可见角色", before[1], roleNamesOf(request.roleIds()));
        // T-302（W1）：受影响面 = 这次被授权的角色集合（**after 侧**）。
        // 用 after 而不是 before：抽屉展示的是「这次改动波及了谁」，用户看的是新授权。
        // 人数同源（同一批 roleIds 的成员数），两者配对。
        List<Long> affected = request.roleIds() == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(request.roleIds()));
        AuditContext.affectedUsers(memberCountDistinct(affected));
        AuditContext.affectedRole(roleNamesOf(affected));

        entityManager.createNativeQuery(
                        "delete from sys_kb_grant where kb_id = :id and principal_type = 'ROLE'")
                .setParameter("id", kbId)
                .executeUpdate();
        entityManager.createNativeQuery(
                        "delete from sys_kb_grant where kb_id = :id and principal_type = 'USER'")
                .setParameter("id", kbId)
                .executeUpdate();

        LocalDateTime now = LocalDateTime.now();
        for (Long roleId : new LinkedHashSet<>(request.roleIds())) {
            entityManager.createNativeQuery("""
                            insert into sys_kb_grant (kb_id, principal_type, principal_id, level, created_at, updated_at)
                            values (:kbId, 'ROLE', :pid, :level, :now, :now)
                            """)
                    .setParameter("kbId", kbId).setParameter("pid", roleId)
                    .setParameter("level", request.level()).setParameter("now", now)
                    .executeUpdate();
        }
        for (Long userId : new LinkedHashSet<>(request.extraUserIds())) {
            entityManager.createNativeQuery("""
                            insert into sys_kb_grant (kb_id, principal_type, principal_id, level, created_at, updated_at)
                            values (:kbId, 'USER', :pid, :level, :now, :now)
                            """)
                    .setParameter("kbId", kbId).setParameter("pid", userId)
                    .setParameter("level", request.level()).setParameter("now", now)
                    .executeUpdate();
        }
        return new AdminDtos.KbGrantSaved(true);
    }

    // ==================== 求交引擎的便捷封装（供后续知识库模块用） ====================

    /** 当前用户可见的知识库 id 列表（② ∩ ③，SQL 层过滤）。 */
    @Transactional(readOnly = true)
    public List<Long> visibleKbIds(LoginUser user) {
        DataScopeService.ScopeCondition cond = dataScopeService.kbVisibility(user);
        jakarta.persistence.Query q = entityManager.createNativeQuery(
                "select k.id from sys_kb k where " + cond.sql());
        cond.params().forEach(q::setParameter);
        return ((List<Number>) q.getResultList()).stream().map(Number::longValue).toList();
    }

    /** 当前用户对某库的实际级别（登录态从上下文取）。 */
    public KbGrantLevel currentLevel(long kbId) {
        return dataScopeService.kbLevel(LoginUserContext.require(), kbId);
    }

    // ==================== 私有 ====================

    private void requireKb(long kbId) {
        Long n = (Long) entityManager.createNativeQuery("select count(*) from sys_kb where id = :id")
                .setParameter("id", kbId)
                .getSingleResult();
        if (n == null || n == 0) {
            throw new BizException(ErrorCode.KB_NOT_FOUND);
        }
    }

    /**
     * 该库**当前**授权的可读快照（before 侧），用于 T-300 的变更前后对照。
     *
     * <p><b>为什么只产出「档位」与「可见角色」两项</b>：这个接口的模型是
     * **单值 level + 角色集合**（见类注释），保存时把所有角色归一成同一档位、
     * 并全量替换角色集合 —— 所以"改了什么"在语义上就只有这两件事。
     * 逐角色展开成 N 行反而会误导（看着像每个角色独立可调，实际不是）。
     *
     * <p>返回 {@code [档位文本, 角色名单文本]}，两项都可能是 {@code null}
     * （表示该库当前无人可见），由 {@link AuditChange} 规范化成 {@code —}。
     */
    private String[] grantSnapshot(long kbId) {
        // ⚠️ 单列原生查询返回的是 List<String>，不是 List<Object[]> ——
        // 按 Object[] 遍历会 ClassCastException（8 条 KbGrantFlowTest 直接变红）。
        List<String> levelRows = entityManager.createNativeQuery(
                        "select distinct level from sys_kb_grant where kb_id = :id and level <> 'NONE'")
                .setParameter("id", kbId)
                .getResultList();
        int maxRank = 0;
        for (String level : levelRows) {
            maxRank = Math.max(maxRank, rankOf(level));
        }

        List<String> roleNames = entityManager.createNativeQuery("""
                        select r.name from sys_kb_grant g
                        join sys_role r on r.id = g.principal_id
                        where g.kb_id = :id and g.principal_type = 'ROLE' and g.level <> 'NONE'
                        order by r.id
                        """)
                .setParameter("id", kbId)
                .getResultList();

        return new String[]{
                maxRank == 0 ? null : levelName(maxRank),
                roleNames.isEmpty() ? null : String.join("、", roleNames)
        };
    }

    /** 提交值里的角色名单（after 侧），口径与 {@link #grantSnapshot} 的「可见角色」一致。 */
    private String roleNamesOf(List<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return null;
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(roleIds));
        List<String> names = entityManager.createNativeQuery(
                        "select name from sys_role where id in (:ids) order by id")
                .setParameter("ids", ids)
                .getResultList();
        return names.isEmpty() ? null : String.join("、", names);
    }

    /**
     * 去重后的受影响人数（T-302 W1 的配套）：同一人在多个被授权角色里只算一次。
     *
     * <p>与 {@code RoleAdminService#memberCountDistinct} 同口径（含 {@code deleted_at is null}）——
     * 同一页面两个抽屉的人数不该用两种算法。
     */
    private int memberCountDistinct(java.util.Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return 0;
        }
        Number n = (Number) entityManager.createNativeQuery("""
                        select count(distinct ur.user_id) from sys_user_role ur
                        join sys_user u on u.id = ur.user_id and u.deleted_at is null
                        where ur.role_id in (:ids)
                        """)
                .setParameter("ids", roleIds)
                .getSingleResult();
        return n == null ? 0 : n.intValue();
    }

    private Map<Long, Long> memberCounts() {        List<Object[]> rows = entityManager.createNativeQuery("""
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

    private String departmentName(Long deptId) {
        if (deptId == null || deptId == 0L) {
            return UserPrincipalAssembler.UNASSIGNED_DEPT_NAME;
        }
        Object name = entityManager.createNativeQuery("select name from sys_department where id = :id")
                .setParameter("id", deptId)
                .getSingleResult();
        return name == null ? UserPrincipalAssembler.UNASSIGNED_DEPT_NAME : (String) name;
    }

    /**
     * 原生查询里的 DATETIME 列转字符串。Hibernate 7 返回 {@link LocalDateTime}
     * （直接强转 {@code java.sql.Timestamp} 会 ClassCastException→9999，已踩过），
     * 旧驱动可能返回 Timestamp，两种都收。
     */
    private String toTimeText(Object value) {
        if (value instanceof LocalDateTime ldt) {
            return TIME_FORMAT.format(ldt);
        }
        if (value instanceof java.sql.Timestamp ts) {
            return TIME_FORMAT.format(ts.toLocalDateTime());
        }
        return value == null ? "" : String.valueOf(value);
    }

    private int rankOf(String level) {
        return switch (level) {
            case "MANAGE" -> 3;
            case "UPLOAD" -> 2;
            case "READ" -> 1;
            default -> 0;
        };
    }

    private String levelName(int rank) {
        return switch (rank) {
            case 3 -> "MANAGE";
            case 2 -> "UPLOAD";
            case 1 -> "READ";
            default -> "NONE";
        };
    }
}
