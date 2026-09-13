package com.knowledge.ai.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限码查询（S1 只需要"某人有哪些权限码"）。
 *
 * <p><b>为什么不建 Spring Data 接口</b>：{@code sys_role_permission} 与
 * {@code sys_user_role} 都是纯连接表，没有实体；权限求交与数据范围下推
 * （T-200 起的 M02）会用到远比"一个并集查询"复杂得多的原生 SQL
 * （递归部门树、行级过滤），现在包一层 JpaRepository 反而要为它造 3 个实体。
 * 这里直接用 {@link EntityManager} 写最小可用的两条查询，S2 再按需要演进。
 *
 * <p>权限码的事实源是后端 {@code PermissionCode} 枚举，{@code sys_permission}
 * 是它在库里的投影（只读参考表）—— 所以这里查库拿到的码一定在枚举里有对应值。
 */
@Repository
public class PermissionQueryRepository {

    /** 超管的角色码。命中的用户直接给通配权限 {@code *}，不再查 38 个权限点。 */
    private static final String SUPER_ADMIN_ROLE = "SUPER_ADMIN";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 某用户的**权限码并集**（多角色取并集，《05》§0.5）。
     *
     * <p>超管返回单元素集合 {@code ["*"]}：
     * <ul>
     *   <li>与 {@code PermissionCode.WILDCARD} 的设计一致，鉴权处只需判 {@code contains("*")}；</li>
     *   <li>避免把 38 个短串编进 JWT（超管每次请求都会带上，白白撑大 token）。</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public Set<String> findPermissionCodes(Long userId) {
        if (userId == null) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();

        List<String> roleCodes = entityManager.createNativeQuery("""
                        select r.code from sys_role r
                                 join sys_user_role ur on ur.role_id = r.id
                        where ur.user_id = :userId and r.enabled = 1
                        """)
                .setParameter("userId", userId)
                .getResultList();
        if (roleCodes.contains(SUPER_ADMIN_ROLE)) {
            result.add(com.knowledge.ai.core.enums.PermissionCode.WILDCARD);
            return result;
        }

        List<String> codes = entityManager.createNativeQuery("""
                        select distinct p.code
                        from sys_permission p
                                 join sys_role_permission rp on rp.permission_id = p.id
                                 join sys_user_role ur on ur.role_id = rp.role_id
                                 join sys_role r on r.id = rp.role_id and r.enabled = 1
                        where ur.user_id = :userId
                        order by p.code
                        """)
                .setParameter("userId", userId)
                .getResultList();
        result.addAll(codes);
        return result;
    }
}
