package com.knowledge.ai.service;

import com.knowledge.ai.core.audit.AuditContext;
import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.enums.UserStatus;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.core.security.RefreshTokenStore;
import com.knowledge.ai.dto.admin.AdminDtos;
import com.knowledge.ai.entity.SysDepartment;
import com.knowledge.ai.entity.SysUser;
import com.knowledge.ai.repository.SysDepartmentRepository;
import com.knowledge.ai.repository.SysRoleRepository;
import com.knowledge.ai.repository.SysUserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户管理（T-205~T-209）。管理「别人」的账号，与 {@link UserService}（用户管自己）分开 ——
 * 两者的权限模型完全不同：这边每个方法都假定已过功能权限注解，列表还要叠加数据范围。
 *
 * <p><b>出参对齐的前端契约</b>（探查代理 2026-09-13 逐接口核对，勿凭记忆改）：
 * <ul>
 *   <li>行字段 {@code department} 是**全路径名**（「研发中心 / 平台架构组」），不是 {@code deptName}；</li>
 *   <li>{@code roleNames}（中文名数组）页面在渲染 tag，缺了列空白；</li>
 *   <li>分页请求是 {@code page/size}、响应是 {@code page + pageSize}；</li>
 *   <li>创建请求的名字字段是 {@code name}，编辑是 {@code realName} —— 前端就这么发的。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 初始/重置密码字符集：大小写 + 数字，去掉易混淆字符（0/O、1/l/I）。 */
    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SysUserRepository userRepository;

    private final SysRoleRepository roleRepository;

    private final SysDepartmentRepository departmentRepository;

    private final DataScopeService dataScopeService;

    /** 复用鉴权侧的部门全路径名口径（「研发中心 / 平台架构组」），避免第二套实现漂移。 */
    private final UserPrincipalAssembler principalAssembler;

    private final PasswordEncoder passwordEncoder;

    private final RefreshTokenStore refreshTokenStore;

    private final InviteTokenStore inviteTokenStore;

    @PersistenceContext
    private EntityManager entityManager;

    // ==================== 列表（T-205，数据范围拼进 SQL —— G2-8） ====================

    /**
     * 用户分页列表。数据范围条件由 {@link DataScopeService#userScope} 返回、**直接拼进 where**
     * —— p6spy / SQL 日志里应能看到 `u.dept_id = ?` 这类条件，而不是查全量后内存过滤。
     *
     * <p>部门筛选沿用 mock 的语义：**选父部门包含子部门**（前端点「研发中心」要看到三个组的用户）。
     * {@code deptId=all} 是前端虚拟根「全部用户」。
     */
    @Transactional(readOnly = true)
    public AdminDtos.UserPage page(String deptId, String keyword, String roleId, String status,
                                   long page, long size) {
        LoginUser current = LoginUserContext.require();
        DataScopeService.ScopeCondition scope = dataScopeService.userScope(current);

        List<String> where = new ArrayList<>();
        where.add("(" + scope.sql() + ")");
        where.add("u.deleted_at is null");
        Map<String, Object> params = new HashMap<>(scope.params());

        if (deptId != null && !deptId.isBlank() && !"all".equals(deptId)) {
            params.put("q_dept", parseDeptId(deptId));
            where.add("u.dept_id IN (SELECT d.id FROM sys_department d WHERE d.id = :q_dept OR d.parent_id = :q_dept)");
        }
        if (keyword != null && !keyword.isBlank()) {
            params.put("q_kw", "%" + keyword.trim() + "%");
            where.add("(u.username LIKE :q_kw OR u.real_name LIKE :q_kw OR u.email LIKE :q_kw OR u.phone LIKE :q_kw)");
        }
        if (roleId != null && !roleId.isBlank() && !"all".equals(roleId)) {
            // ⚠️ 前端发的是角色 **code**（下拉 option :value 绑 code），不是数字 id
            params.put("q_role", roleId);
            where.add("""
                    u.id IN (SELECT ur.user_id FROM sys_user_role ur
                             JOIN sys_role r ON r.id = ur.role_id WHERE r.code = :q_role)""");
        }
        if (status != null && !status.isBlank() && !"all".equals(status)) {
            params.put("q_status", parseStatus(status).name());
            where.add("u.status = :q_status");
        }
        String whereSql = String.join(" AND ", where);

        jakarta.persistence.Query countQuery = entityManager.createNativeQuery(
                "select count(*) from sys_user u where " + whereSql);
        params.forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        long safeSize = size <= 0 ? 20 : Math.min(size, 200);
        long safePage = page <= 0 ? 1 : page;
        jakarta.persistence.Query dataQuery = entityManager.createNativeQuery(
                "select u.* from sys_user u where " + whereSql + " order by u.id asc", SysUser.class);
        params.forEach(dataQuery::setParameter);
        dataQuery.setFirstResult((int) ((safePage - 1) * safeSize));
        dataQuery.setMaxResults((int) safeSize);
        @SuppressWarnings("unchecked")
        List<SysUser> users = dataQuery.getResultList();

        return new AdminDtos.UserPage(toRows(users), total, safePage, safeSize);
    }

    // ==================== 创建 / 编辑（T-206） ====================

    /**
     * 创建用户。两种开通方式（《05》§1.4）：
     * <ul>
     *   <li>{@code INVITE} —— {@code PENDING} + 邀请令牌，用户自助设密后转 ACTIVE；</li>
     *   <li>{@code PASSWORD} —— {@code ACTIVE} + 随机初始密码 + 首登强制修改。
     *       前端 toast 固定说"创建后为待激活"，但响应里带了 {@code initialPassword}，
     *       联调时若文案与实际状态冲突，改前端 toast 一句话即可（响应是纯新增字段）。</li>
     * </ul>
     */
    @Transactional
    public AdminDtos.UserCreateResponse create(AdminDtos.UserCreateRequest request) {
        String username = request.username().trim().toLowerCase();
        if (userRepository.findByUsernameAndDeletedAtIsNull(username).isPresent()) {
            throw new BizException(ErrorCode.USERNAME_EXISTS);
        }
        assertContactUnique(request.phone(), request.email(), null);

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setRealName(request.name().trim());
        user.setPhone(blankToNull(request.phone()));
        user.setEmail(blankToNull(request.email()));
        user.setDeptId(requireDept(request.deptId()));
        user.setStatus(UserStatus.PENDING);
        user.setMustChangePassword(false);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        // password_hash 非空约束：PENDING 用户还没有密码，先放一个不可登录的随机占位，
        // 邀请接受 / 设初始密码时再覆盖 —— 不能用空串（BCrypt 校验会把空串当真密码比）
        user.setPasswordHash(passwordEncoder.encode(generatePassword()));
        userRepository.save(user);

        setRoles(user, request.roleIds());

        String initialPassword = null;
        if ("PASSWORD".equalsIgnoreCase(request.inviteMode())) {
            initialPassword = generatePassword();
            user.setPasswordHash(passwordEncoder.encode(initialPassword));
            user.setStatus(UserStatus.ACTIVE);
            user.setMustChangePassword(true);
            user.setUpdatedAt(LocalDateTime.now());
            userRepository.save(user);
        } else if ("INVITE".equalsIgnoreCase(request.inviteMode())) {
            int days = request.inviteDays() == null ? InviteTokenStore.DEFAULT_INVITE_DAYS : request.inviteDays();
            String token = inviteTokenStore.issue(user.getId(), days);
            log.info("创建用户并签发邀请：username={} 有效期={} 天（token 已生成，接受端点待批次 4 接通）", username, days);
        } else {
            throw new BizException(ErrorCode.PARAM_INVALID, "inviteMode 仅支持 INVITE / PASSWORD");
        }
        return new AdminDtos.UserCreateResponse(user.getId(), user.getStatus().name(), initialPassword);
    }

    /**
     * 编辑（不含 username —— 前端禁改，后端连收都不收）。角色数组是**全量替换**。
     *
     * <p>🚩 权限变更高危：角色数组整发整换，等价于「权限扩大 / 收缩」。审计按
     * {@link RiskLevel#HIGH} 留痕，且**失败也记**（切点包 finally）。
     */
    @Transactional
    @AuditLog(module = AuditModule.USER, action = "编辑用户（含角色调整）", risk = RiskLevel.HIGH,
            target = "'用户#' + #id")
    public AdminDtos.UserRow update(Long id, AdminDtos.UserUpdateRequest request) {
        SysUser user = userRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BizException(ErrorCode.USER_NOT_FOUND));

        // ============ T-300：先读旧值并登记，再改 ============
        // 顺序不能反（见 AuditContext 的 javadoc）。这里读两样：角色与部门 ——
        // 前者是"权限扩大/收缩"的直接体现，后者决定数据范围（能看哪些部门的库）。
        List<String> oldRoleCodes = currentRoleCodes(id);
        String oldDeptName = departmentNameOf(user.getDeptId());
        String newDeptName = departmentNameOf(requireDept(request.deptId()));
        AuditContext.change("角色",
                oldRoleCodes.isEmpty() ? null : String.join("、", oldRoleCodes),
                (request.roleIds() == null || request.roleIds().isEmpty())
                        ? null : String.join("、", request.roleIds()));
        AuditContext.change("部门", oldDeptName, newDeptName);
        // 影响面是这次被编辑的这一个人
        AuditContext.affectedUsers(1);

        user.setRealName(request.realName().trim());
        user.setPhone(blankToNull(request.phone()));
        user.setEmail(blankToNull(request.email()));
        user.setDeptId(requireDept(request.deptId()));
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        setRoles(user, request.roleIds());
        return toRows(List.of(user)).getFirst();
    }

    /** 用户当前的角色编码（id 升序，供审计 before 侧与对照展示）。 */
    private List<String> currentRoleCodes(Long userId) {
        return entityManager.createNativeQuery("""
                        select r.code from sys_user_role ur
                        join sys_role r on r.id = ur.role_id
                        where ur.user_id = :id order by r.id
                        """)
                .setParameter("id", userId)
                .getResultList();
    }

    /** 部门名（含「未分配」虚拟节点），与列表行的 {@code department} 字段同一口径。 */
    private String departmentNameOf(Long deptId) {
        return principalAssembler.departmentName(deptId);
    }

    // ==================== 状态机（T-207，含自锁防护 G2-5） ====================

    /**
     * 状态切换。只接受 ACTIVE/DISABLED（前端只在这两档间切换；PENDING 只能通过
     * 邀请接受流转，不走这个接口）。
     *
     * <p>自锁防护（G2-5）服务端必须兜底：前端全部是本地拦截（不发请求），
     * 意味着**绕过 UI 直调 API 时这里是唯一防线**。
     */
    @Transactional
    public AdminDtos.UserRow patchStatus(Long id, String status) {
        LoginUser current = LoginUserContext.require();
        UserStatus target = parseStatus(status);
        if (target != UserStatus.ACTIVE && target != UserStatus.DISABLED) {
            throw new BizException(ErrorCode.PARAM_INVALID, "只允许在 正常 / 已禁用 之间切换");
        }
        SysUser user = userRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BizException(ErrorCode.USER_NOT_FOUND));

        if (target == UserStatus.DISABLED) {
            if (id.equals(current.getUserId())) {
                throw new BizException(ErrorCode.USER_SELF_OPERATION_DENIED, "不能停用当前登录账号");
            }
            if (isLastActiveSuperAdmin(id)) {
                throw new BizException(ErrorCode.LAST_SUPER_ADMIN, "系统至少保留一个启用的超级管理员");
            }
            // 禁用立即收回全部刷新令牌：禁用是安全动作，不能等他下一个请求被 403 才生效
            int revoked = refreshTokenStore.revokeAll(id);
            log.info("禁用用户：userId={} 撤销 refreshToken={} 条", id, revoked);
        }

        user.setStatus(target);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        return toRows(List.of(user)).getFirst();
    }

    // ==================== 删除（G2-6，软删） ====================

    /**
     * 删除账号（**软删**，`deleted_at` 置时间戳）。
     *
     * <p><b>三道闸</b>（《05》§1.5 / §1.6）：
     * <ol>
     *   <li>不能删自己 —— 与停用同类，自锁防护（{@link ErrorCode#USER_SELF_OPERATION_DENIED}）；</li>
     *   <li>不能删最后一个启用中的超级管理员 —— 否则系统再无人能管（{@link ErrorCode#LAST_SUPER_ADMIN}）；</li>
     *   <li><b>有业务数据的账号禁止删除</b>（{@link ErrorCode#USER_HAS_BUSINESS_DATA}）——
     *       创建过知识库或上传过文档的账号只能禁用：删了会留下**孤立数据**
     *       （库还在、创建人没了）。判定见 {@link #hasBusinessData}。</li>
     * </ol>
     *
     * <p><b>为什么软删而不是物理删</b>：账号可能仍被历史记录引用（登录日志、审计日志），
     * 且"删除"在《05》§1.5 的语义是"不可逆但保留历史记录"。物理删会破坏审计链。
     * 软删后该账号不出现在任何列表（仓储层统一 `deleted_at is null`），
     * 也不能再登录（{@code findByUsernameAndDeletedAtIsNull} 查不到）。
     *
     * <p>🚩 高危：删除账号不可逆，接 {@link AuditLog} 留痕。**先自锁防护、再删**，
     * 保证被拒的操作不产生副作用。
     */
    @Transactional
    @AuditLog(module = AuditModule.USER, action = "删除用户", risk = RiskLevel.HIGH,
            target = "'用户#' + #id")
    public AdminDtos.Deleted delete(Long id) {
        LoginUser current = LoginUserContext.require();
        SysUser user = userRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BizException(ErrorCode.USER_NOT_FOUND));

        if (id.equals(current.getUserId())) {
            throw new BizException(ErrorCode.USER_SELF_OPERATION_DENIED, "不能删除当前登录账号");
        }
        if (isLastActiveSuperAdmin(id)) {
            throw new BizException(ErrorCode.LAST_SUPER_ADMIN, "系统至少保留一个启用的超级管理员");
        }
        if (hasBusinessData(id)) {
            throw new BizException(ErrorCode.USER_HAS_BUSINESS_DATA);
        }

        // 删除前先收掉登录态：与停用同理，账号已删除不该等下一个请求才失效
        int revoked = refreshTokenStore.revokeAll(id);
        user.setDeletedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        log.info("删除账号（软删）：userId={} username={} 撤销 refreshToken={} 条",
                id, user.getUsername(), revoked);
        return new AdminDtos.Deleted(true);
    }

    // ==================== 重置密码 / 重发邀请（T-208） ====================

    /**
     * 管理员重置密码：生成新随机密码 + 强制首登修改 + **撤销该用户全部登录态**（《05》§1.5）。
     * 新密码放进响应返回给管理员 —— v1 没有通知通道，不给明文这个账号就没人能登录了。
     */
    @Transactional
    public AdminDtos.ResetPasswordResponse resetPassword(Long id) {
        SysUser user = userRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BizException(ErrorCode.USER_NOT_FOUND));
        String newPassword = generatePassword();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(true);
        user.setPasswordUpdatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);
        int revoked = refreshTokenStore.revokeAll(id);
        log.info("管理员重置密码：userId={} 撤销 refreshToken={} 条", id, revoked);
        return new AdminDtos.ResetPasswordResponse(true, true,
                "已重置为初始密码，用户下次登录需修改", newPassword);
    }

    /** 重发邀请：仅 PENDING（《05》§1.5「重发邀请仅在待激活时出现」）。每次重发签新令牌、旧的随 TTL 过期。 */
    @Transactional
    public AdminDtos.ResendInviteResponse resendInvite(Long id) {
        SysUser user = userRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BizException(ErrorCode.USER_NOT_FOUND));
        if (user.getStatus() != UserStatus.PENDING) {
            throw new BizException(ErrorCode.PARAM_INVALID, "仅待激活账号可重发邀请");
        }
        inviteTokenStore.issue(id, InviteTokenStore.DEFAULT_INVITE_DAYS);
        return new AdminDtos.ResendInviteResponse(true, InviteTokenStore.DEFAULT_INVITE_DAYS);
    }

    // ==================== 批量导入（T-209） ====================

    /**
     * 批量导入（CSV 先行，Excel 待定）。
     *
     * <p>**为什么 CSV**：Excel 解析要引 poi-ooxml（约 15MB 依赖），而前端「导入」按钮
     * 目前是演示占位、根本不发请求 —— 为一个没人调的接口先背最重的依赖不划算。
     * 模板列：{@code username,realName,phone,email,department,roles}（roles 是角色编码，
     * 分号分隔，缺省 USER）。逐行校验「用户名重复」「部门不存在」两类错误（《05》§1.7），
     * 报告精确到行号 + 原因；**行与行之间互不影响**，错的跳过、对的照常入库。
     */
    @Transactional
    public AdminDtos.ImportResponse importUsers(byte[] content) {
        if (content == null || content.length == 0) {
            throw new BizException(ErrorCode.IMPORT_TEMPLATE_INVALID, "文件为空");
        }
        String text = new String(content, java.nio.charset.StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        String[] lines = text.split("\r?\n");
        if (lines.length < 2) {
            throw new BizException(ErrorCode.IMPORT_TEMPLATE_INVALID, "缺少数据行");
        }

        Map<String, Long> deptIdByName = departmentRepository.findAllByOrderBySortAsc().stream()
                .collect(Collectors.toMap(SysDepartment::getName, SysDepartment::getId, (a, b) -> a));
        Set<String> roleCodes = roleRepository.findAll().stream().map(com.knowledge.ai.entity.SysRole::getCode)
                .collect(Collectors.toSet());
        Set<String> existingUsernames = userRepository.findAll().stream()
                .filter(u -> u.getDeletedAt() == null)
                .map(SysUser::getUsername)
                .collect(Collectors.toSet());

        List<AdminDtos.ImportRowError> errors = new ArrayList<>();
        int success = 0;
        Set<String> seenInFile = new LinkedHashSet<>();
        LocalDateTime now = LocalDateTime.now();

        for (int i = 1; i < lines.length; i++) {
            int rowNo = i; // 报告行号含表头：第 2 行数据 → row=2，与 Excel 行号一致
            String[] cols = lines[i].split(",", -1);
            if (cols.length < 5) {
                errors.add(new AdminDtos.ImportRowError(rowNo, "列数不足，模板为 6 列"));
                continue;
            }
            String username = cols[0].trim().toLowerCase();
            String realName = cols[1].trim();
            String phone = blankToNull(cols[2].trim());
            String email = blankToNull(cols[3].trim());
            String deptName = cols[4].trim();
            String roles = cols.length > 5 ? cols[5].trim() : "USER";

            if (username.isBlank() || realName.isBlank()) {
                errors.add(new AdminDtos.ImportRowError(rowNo, "用户名与姓名为必填"));
                continue;
            }
            if (existingUsernames.contains(username) || seenInFile.contains(username)) {
                errors.add(new AdminDtos.ImportRowError(rowNo, "用户名重复：" + username));
                continue;
            }
            Long deptId = deptIdByName.get(deptName);
            if (deptId == null) {
                errors.add(new AdminDtos.ImportRowError(rowNo, "部门不存在：" + deptName));
                continue;
            }
            List<String> codes = java.util.Arrays.stream(roles.split("[;；]"))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList();
            if (codes.isEmpty() || codes.stream().anyMatch(c -> !roleCodes.contains(c))) {
                errors.add(new AdminDtos.ImportRowError(rowNo, "角色编码不存在：" + roles));
                continue;
            }

            SysUser user = new SysUser();
            user.setUsername(username);
            user.setRealName(realName);
            user.setPhone(phone);
            user.setEmail(email);
            user.setDeptId(deptId);
            user.setStatus(UserStatus.PENDING);
            user.setMustChangePassword(false);
            user.setPasswordHash(passwordEncoder.encode(generatePassword()));
            user.setCreatedAt(now);
            user.setUpdatedAt(now);
            userRepository.save(user);
            setRoles(user, codes);
            existingUsernames.add(username);
            seenInFile.add(username);
            success++;
        }
        return new AdminDtos.ImportResponse(success, errors.size(), errors);
    }

    // ==================== 私有 ====================

    /**
     * 部门全路径名（两层：子部门「父 / 子」，顶级为自身名，「未分配」固定文案）。
     * 与 {@link UserPrincipalAssembler#departmentName} 同口径 —— 那边逐用户查库，
     * 列表场景先整表载入再查 map，避免 N 次全表读。
     */
    private String departmentName(Map<Long, SysDepartment> byId, Long deptId) {
        if (deptId == null || deptId == 0L) {
            return UserPrincipalAssembler.UNASSIGNED_DEPT_NAME;
        }
        SysDepartment dept = byId.get(deptId);
        if (dept == null) {
            return UserPrincipalAssembler.UNASSIGNED_DEPT_NAME;
        }
        if (dept.getParentId() != null) {
            SysDepartment parent = byId.get(dept.getParentId());
            if (parent != null) {
                return parent.getName() + " / " + dept.getName();
            }
        }
        return dept.getName();
    }

    /**
     * **有业务数据禁删**（G2-6，《05》§1.5）：「创建过知识库 或 上传过文档」的账号只能禁用。
     *
     * <p>S2 阶段这里恒 {@code false}（{@code sys_kb} 还是骨架、文档表要到 S4 才建）；
     * V8 起 {@code sys_kb.owner_id} 已落创建人，本方法改为**真实查询**。
     * 文档侧的「上传过文档」在 S4 建 {@code sys_document} 后并入本条 SQL。
     *
     * <p>用 {@code count(*) > 0} 的 {@code select 1 ... limit 1} 语义而不是全表 count：
     * 只关心存在性，命中一条即可返回。
     */
    private boolean hasBusinessData(Long userId) {
        Number owned = (Number) entityManager.createNativeQuery(
                        "select count(*) from sys_kb where owner_id = :uid limit 1")
                .setParameter("uid", userId)
                .getSingleResult();
        if (owned != null && owned.longValue() > 0) {
            return true;
        }
        // TODO(S4)：sys_document 建表后，这里并入「上传过文档」的同类存在性查询
        //           （《05》§1.5 的「或上传过文档」半边）。
        return false;
    }

    private boolean isLastActiveSuperAdmin(Long userId) {
        Long active = (Long) entityManager.createNativeQuery("""
                        select count(*) from sys_user u
                        join sys_user_role ur on ur.user_id = u.id
                        join sys_role r on r.id = ur.role_id and r.code = 'SUPER_ADMIN'
                        where u.status = 'ACTIVE' and u.deleted_at is null and u.id <> :id
                        """)
                .setParameter("id", userId)
                .getSingleResult();
        return active == null || active == 0;
    }

    private Long requireDept(Long deptId) {
        if (deptId == null || departmentRepository.findById(deptId).isEmpty()) {
            throw new BizException(ErrorCode.DEPT_NOT_FOUND);
        }
        return deptId;
    }

    private void assertContactUnique(String phone, String email, Long excludeId) {
        // 不用 ":ex is null" 混合模板：JPA 对 native null 参数要做类型推断，容易运行时炸。
        // 拆成两条写死的 SQL，代价是两行 if，换来行为确定。
        String base = "select count(*) from sys_user u where u.%s = :v and u.deleted_at is null";
        String withExclude = base + " and u.id <> :ex";
        if (phone != null && !phone.isBlank()) {
            jakarta.persistence.Query q = entityManager.createNativeQuery(
                    excludeId == null ? base.formatted("phone") : withExclude.formatted("phone"));
            q.setParameter("v", phone);
            if (excludeId != null) {
                q.setParameter("ex", excludeId);
            }
            if (((Number) q.getSingleResult()).longValue() > 0) {
                throw new BizException(ErrorCode.PHONE_EXISTS);
            }
        }
        if (email != null && !email.isBlank()) {
            jakarta.persistence.Query q = entityManager.createNativeQuery(
                    excludeId == null ? base.formatted("email") : withExclude.formatted("email"));
            q.setParameter("v", email);
            if (excludeId != null) {
                q.setParameter("ex", excludeId);
            }
            if (((Number) q.getSingleResult()).longValue() > 0) {
                throw new BizException(ErrorCode.EMAIL_EXISTS);
            }
        }
    }

    /** 全量替换用户的角色。角色 code 必须真实存在 —— 前端传的是 code 数组。 */
    private void setRoles(SysUser user, List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "至少分配一个角色（不存在无角色用户，《01》§1.2）");
        }
        Map<String, com.knowledge.ai.entity.SysRole> byCode = roleRepository.findAll().stream()
                .collect(Collectors.toMap(com.knowledge.ai.entity.SysRole::getCode, Function.identity(), (a, b) -> a));
        List<Long> roleIds = new ArrayList<>();
        for (String code : new LinkedHashSet<>(roleCodes)) {
            com.knowledge.ai.entity.SysRole role = byCode.get(code);
            if (role == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "角色不存在：" + code);
            }
            roleIds.add(role.getId());
        }
        entityManager.createNativeQuery("delete from sys_user_role where user_id = :id")
                .setParameter("id", user.getId())
                .executeUpdate();
        LocalDateTime now = LocalDateTime.now();
        for (Long roleId : roleIds) {
            entityManager.createNativeQuery(
                            "insert into sys_user_role (user_id, role_id, created_at) values (:u, :r, :now)")
                    .setParameter("u", user.getId())
                    .setParameter("r", roleId)
                    .setParameter("now", now)
                    .executeUpdate();
        }
    }

    private List<AdminDtos.UserRow> toRows(List<SysUser> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        Map<Long, SysDepartment> deptById = departmentRepository.findAllByOrderBySortAsc().stream()
                .collect(Collectors.toMap(SysDepartment::getId, Function.identity(), (a, b) -> a));
        Map<Long, List<com.knowledge.ai.entity.SysRole>> rolesByUser = new HashMap<>();
        entityManager.createNativeQuery("""
                        select ur.user_id, r.id, r.code, r.name from sys_user_role ur
                        join sys_role r on r.id = ur.role_id order by r.id
                        """)
                .unwrap(org.hibernate.query.NativeQuery.class)
                .getResultList()
                .forEach(row -> {
                    Object[] cols = (Object[]) row;
                    long userId = ((Number) cols[0]).longValue();
                    com.knowledge.ai.entity.SysRole role = new com.knowledge.ai.entity.SysRole();
                    role.setId(((Number) cols[1]).longValue());
                    role.setCode((String) cols[2]);
                    role.setName((String) cols[3]);
                    rolesByUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(role);
                });

        Map<String, String> roleNamesByCode = roleRepository.findAll().stream()
                .collect(Collectors.toMap(com.knowledge.ai.entity.SysRole::getCode,
                        com.knowledge.ai.entity.SysRole::getName, (a, b) -> a));

        List<AdminDtos.UserRow> rows = new ArrayList<>(users.size());
        for (SysUser user : users) {
            List<com.knowledge.ai.entity.SysRole> roles =
                    rolesByUser.getOrDefault(user.getId(), List.of());
            List<String> codes = roles.stream().map(com.knowledge.ai.entity.SysRole::getCode).toList();
            List<String> names = codes.stream().map(c -> roleNamesByCode.getOrDefault(c, c)).toList();
            rows.add(new AdminDtos.UserRow(
                    user.getId(),
                    user.getUsername(),
                    user.getRealName(),
                    user.getPhone(),
                    user.getEmail(),
                    departmentName(deptById, user.getDeptId()),
                    user.getDeptId(),
                    codes,
                    names,
                    user.getStatus() == null ? null : user.getStatus().name(),
                    user.getStatus() == null ? "" : statusLabel(user.getStatus()),
                    user.getCreatedAt() == null ? null : TIME_FORMAT.format(user.getCreatedAt()),
                    user.getLastLoginAt() == null ? null : TIME_FORMAT.format(user.getLastLoginAt()),
                    Boolean.TRUE.equals(user.getMustChangePassword()),
                    hasBusinessData(user.getId())));
        }
        return rows;
    }

    private String statusLabel(UserStatus status) {
        return switch (status) {
            case ACTIVE -> "正常";
            case PENDING -> "待激活";
            case DISABLED -> "已禁用";
        };
    }

    private String generatePassword() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(PASSWORD_CHARS.charAt(RANDOM.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }

    private String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    private UserStatus parseStatus(String raw) {
        try {
            return UserStatus.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new BizException(ErrorCode.PARAM_INVALID, "未知的账号状态：" + raw);
        }
    }

    private Long parseDeptId(String raw) {
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException ex) {
            throw new BizException(ErrorCode.PARAM_INVALID, "deptId 非法：" + raw);
        }
    }
}
