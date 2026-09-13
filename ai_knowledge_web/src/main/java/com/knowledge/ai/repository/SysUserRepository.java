package com.knowledge.ai.repository;

import com.knowledge.ai.core.enums.UserStatus;
import com.knowledge.ai.entity.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 用户仓储。
 *
 * <p>所有查询都带 {@code deleted_at IS NULL} —— 软删除账号不该出现在任何业务视图里。
 * 把这条约束写在仓储层（而不是每个 Service 各判一次）是唯一能保证不漏的做法。
 */
public interface SysUserRepository extends JpaRepository<SysUser, Long> {

    /** 按登录账号查（未删除）。 */
    Optional<SysUser> findByUsernameAndDeletedAtIsNull(String username);

    /**
     * 按登录标识查：支持《10》§1 登录页提示的「用户名 / 手机号 / 邮箱」三种输入。
     *
     * <p>写成一条 JPQL 而不是"三次查询取先命中的"：三次往返会引入"查用户名的
     * 同时有人注册了同手机号"这类竞态，也让失败路径多两个分支。
     */
    @org.springframework.data.jpa.repository.Query("""
            select u from SysUser u
            where u.deletedAt is null
              and (u.username = :account or u.phone = :account or u.email = :account)
            """)
    Optional<SysUser> findByAccount(String account);

    Optional<SysUser> findByIdAndDeletedAtIsNull(Long id);

    boolean existsByUsernameAndDeletedAtIsNull(String username);

    boolean existsByPhoneAndDeletedAtIsNull(String phone);

    boolean existsByEmailAndDeletedAtIsNull(String email);

    /**
     * 手机号是否已被**其他人**占用（换绑时用）。
     *
     * <p>不能用 {@link #existsByPhoneAndDeletedAtIsNull}：用户换绑时提交的可能是
     * 自己原来的号码，那条通用查询会把"自己"算成冲突，导致"什么都没改却报已注册"。
     * 唯一性校验在"更新自己"的语义下必须排除自己。
     */
    boolean existsByPhoneAndIdNotAndDeletedAtIsNull(String phone, Long id);

    /** 邮箱是否已被**其他人**占用（换绑 / 改资料时用）。同上，必须排除自己。 */
    boolean existsByEmailAndIdNotAndDeletedAtIsNull(String email, Long id);

    /** 用于组织/用户列表，按部门与状态筛选。 */
    List<SysUser> findByDeptIdAndDeletedAtIsNull(Long deptId);

    long countByStatusAndDeletedAtIsNull(UserStatus status);
}
