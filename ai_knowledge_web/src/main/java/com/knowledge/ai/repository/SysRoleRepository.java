package com.knowledge.ai.repository;

import com.knowledge.ai.entity.SysRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 角色仓储（T-107 注册分角色、S2 权限求交都要用）。
 *
 * <p><b>为什么 {@code sys_user_role} 不建独立实体</b>：它是纯连接表，没有任何自有字段
 * （只有 {@code created_at}），为它建实体只换来"多两个类 + 多一次对象组装"，
 * 却要承担复合主键（{@code @IdClass} / {@code @EmbeddedId}）的额外复杂度。
 * 这里用两条原生 SQL 直接读写，语义更直白。
 */
public interface SysRoleRepository extends JpaRepository<SysRole, Long> {

    /** 普通用户角色码（I-1：注册时自动分配）。 */
    String USER_ROLE_CODE = "USER";

    /** 超级管理员角色码（拥有全部权限）。 */
    String SUPER_ADMIN_ROLE_CODE = "SUPER_ADMIN";

    Optional<SysRole> findByCode(String code);

    List<SysRole> findByEnabledTrueOrderBySortAsc();

    /**
     * 查某用户的角色（仅启用的角色）。
     *
     * <p>返回投影而不是实体：调用方要的是"码 + 名 + 数据范围"三个标量，
     * 拿实体再逐个取字段会白白带上 8 个用不到的列。
     */
    @Query(value = """
            select r.id          as id,
                   r.code        as code,
                   r.name        as name,
                   r.data_scope  as dataScope
            from sys_role r
                     join sys_user_role ur on ur.role_id = r.id
            where ur.user_id = :userId
              and r.enabled = 1
            order by r.sort
            """, nativeQuery = true)
    List<UserRoleView> findRolesByUserId(@Param("userId") Long userId);

    /** 给用户挂一个角色（按角色码）。已存在则忽略 —— 保证注册接口可重复调用不出错。 */
    @Modifying
    @Query(value = """
            insert into sys_user_role (user_id, role_id, created_at)
            select :userId, r.id, now() from sys_role r
            where r.code = :roleCode
              and not exists (select 1 from sys_user_role ur
                              where ur.user_id = :userId and ur.role_id = r.id)
            """, nativeQuery = true)
    int grantRole(@Param("userId") Long userId, @Param("roleCode") String roleCode);

    /** 用户拥有的角色码（I-1 断言用：注册用户必须至少有一个角色）。 */
    @Query(value = """
            select r.code from sys_role r
                     join sys_user_role ur on ur.role_id = r.id
            where ur.user_id = :userId
            """, nativeQuery = true)
    List<String> findRoleCodesByUserId(@Param("userId") Long userId);

    /** 用户角色投影。 */
    interface UserRoleView {

        Long getId();

        String getCode();

        String getName();

        String getDataScope();
    }
}
