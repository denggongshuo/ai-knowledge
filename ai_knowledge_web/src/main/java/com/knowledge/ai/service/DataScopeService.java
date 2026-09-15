package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.DataScope;
import com.knowledge.ai.core.security.LoginUser;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 数据范围 → **可直接拼进 SQL 的条件**（T-212 · D-3）。
 *
 * <p><b>为什么返回 SQL 片段而不是内存过滤</b>：G2-8 的判据是"用 SQL 日志证明
 * 过滤条件出现在 SQL 里"。查全量后内存过滤有两个实质问题：数据量上去之后
 * 每次列表都是全表扫描；而且"看起来能用"的内存过滤一旦漏掉一个分支
 * （比如忘了排除已软删的），就是越权——越权恰恰是**宁可看不见也不可见**的 fail-closed 场景。
 *
 * <p>拼接约定：调用方在原生 SQL 里给用户表起别名 {@code u}，本类返回的片段
 * 直接以 {@code and} 追加到 where。参数用带前缀的命名参数（{@code ds_}），
 * 避免与调用方自己的参数撞名。
 */
@Service
public class DataScopeService {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 用户维度的数据范围条件（表别名固定 {@code u}，见 {@code sys_user}）。
     *
     * <p>语义（《05》§0.2，部门只是计算基准）：
     * <ul>
     *   <li>{@code ALL} —— 不加条件；</li>
     *   <li>{@code DEPT_AND_CHILD} —— 本部门 + 直接子部门。**两层结构**（V1 建表注释），
     *       所以「parent_id = :dept」就等价于递归展开，不必真的写 WITH RECURSIVE；
     *       将来若放开三层，只改这里；</li>
     *   <li>{@code DEPT} —— 仅本部门（不含子部门，边界用例）；</li>
     *   <li>{@code SELF} —— 只看自己；</li>
     *   <li>{@code CUSTOM} —— S2 没有"自定义部门集合"的配置入口，
     *       出现即按 {@code SELF} 处理：**宁可看不见，不可越权**（fail-closed）。
     *       等配置功能落地后在这里展开部门集合。</li>
     * </ul>
     *
     * <p>注意「未分配」（dept_id = 0）不属于任何部门，因此在 DEPT / DEPT_AND_CHILD
     * 下**天然不可见** —— 这与《01》§1.1「未分配是异常信号」的定位一致：
     * 只有持有 ALL 范围的管理员才看得到它。
     */
    public ScopeCondition userScope(LoginUser user) {
        DataScope scope = user.getDataScope() == null ? DataScope.SELF : user.getDataScope();
        Map<String, Object> params = new HashMap<>();
        return switch (scope) {
            case ALL -> new ScopeCondition("1 = 1", params);
            case DEPT_AND_CHILD -> {
                params.put("ds_dept", user.getDeptId());
                yield new ScopeCondition(
                        "(u.dept_id = :ds_dept OR u.dept_id IN (SELECT id FROM sys_department WHERE parent_id = :ds_dept))",
                        params);
            }
            case DEPT -> {
                params.put("ds_dept", user.getDeptId());
                yield new ScopeCondition("u.dept_id = :ds_dept", params);
            }
            case SELF, CUSTOM -> {
                params.put("ds_user", user.getUserId());
                yield new ScopeCondition("u.id = :ds_user", params);
            }
        };
    }

    /**
     * 一段可直接 {@code and} 进 where 的条件 + 它的命名参数。
     *
     * @param sql    不含 {@code where} 的条件片段
     * @param params 命名参数（SQL 里以 {@code :name} 引用）
     */
    public record ScopeCondition(String sql, Map<String, Object> params) {
    }

    // ==================== 知识库维度（T-212 · I-7 三层求交的 ②∩③） ====================

    /**
     * 知识库可见性条件（表别名固定 {@code k}，见 {@code sys_kb}）。
     *
     * <p><b>这是 I-7 的落点</b>：可见 = ② 数据范围 ∩ ③ 知识库授权，
     * 两个维度用 AND 硬交 —— 授权给了但部门在范围外 → 不可见；
     * 部门在范围内但没有任何授权 → 同样不可见。**没有"或"的分支**，
     * 任何一维放行都等于越权。
     *
     * <p>③ 的语义：用户的**任一角色**被授了 READ 及以上，或用户本人被额外授权
     * （kb:grant-extra），库即进入候选 —— 多角色叠加取最宽（《05》§0.5），
     * 具体到什么级别由 {@link #kbLevel} 再算。
     *
     * <p>② 按 {@link #userScope} 同一套规则展开；但 {@code SELF} 在库维度上
     * **不加部门条件** —— 知识库不是用户创建的，「仅本人」对库的含义由 ③ 表达
     * （《01》§2.5：普通用户 = 仅已授权知识库 + 只读）。
     *
     * <p><b>④ 账号状态也是条件的一部分</b>（B2 的 W7，2026-09-15 加）：
     * 需求《03》§4.1 /《04》§1.4 /《05》§1.5 三处逐字写着「『已禁用』不是禁止登录：
     * 允许登录，但**无法访问任何知识库**」。把它做成**条件片段**而不是在业务方法里
     * 另写一个 if，是因为后者会在新增查询点时被漏掉 —— 知识库的列表 / 单库读取 /
     * 保存 / 解析配置全走本方法，一处生效即处处生效。
     *
     * <p>用子查询而不是 join：调用方把本片段**裸拼进 where**（{@link #userScope} 的
     * 拼接约定），加 join 会改变调用方 from 子句的形态，那样的改动会波及每个调用点。
     */
    public ScopeCondition kbVisibility(LoginUser user) {
        DataScope scope = user.getDataScope() == null ? DataScope.SELF : user.getDataScope();
        Map<String, Object> params = new HashMap<>();
        params.put("ds_uid", user.getUserId());
        String granted = """
                EXISTS (SELECT 1 FROM sys_kb_grant g WHERE g.kb_id = k.id
                        AND g.level IN ('READ', 'UPLOAD', 'MANAGE')
                        AND ((g.principal_type = 'ROLE' AND g.principal_id IN
                              (SELECT ur.role_id FROM sys_user_role ur WHERE ur.user_id = :ds_uid))
                             OR (g.principal_type = 'USER' AND g.principal_id = :ds_uid)))""";
        // 停用账号恒不可见（W7）：与授权、部门两维**硬交**，不设豁免
        String active = """
                EXISTS (SELECT 1 FROM sys_user u WHERE u.id = :ds_uid AND u.status <> 'DISABLED')""";
        String visible = granted + " AND " + active;
        return switch (scope) {
            case ALL -> new ScopeCondition(visible, params);
            case DEPT_AND_CHILD -> {
                params.put("ds_dept", user.getDeptId());
                yield new ScopeCondition(visible + """
                        AND (k.dept_id = :ds_dept OR k.dept_id IN
                             (SELECT id FROM sys_department WHERE parent_id = :ds_dept))""", params);
            }
            case DEPT -> {
                params.put("ds_dept", user.getDeptId());
                yield new ScopeCondition(visible + " AND k.dept_id = :ds_dept", params);
            }
            case SELF, CUSTOM -> new ScopeCondition(visible, params);
        };
    }

    /**
     * 用户对某个知识库的**实际级别**（多角色取最高 + 额外授权参与折算）。
     * 返回 {@code NONE} = 无授权（对 ② 范围外的库即使有授权行也返回 NONE —— 求交不做豁免）。
     *
     * <p>⚠️ <b>本方法不含"账号已停用"那一维</b>（W7 只落进了 {@link #kbVisibility}）。
     * 当前主代码里它**没有调用方**（{@code KbGrantService#currentLevel} 也无人调），
     * 所以没有造成不一致；但**一旦有人拿它做授权判定，必须补上状态检查** ——
     * 否则停用账号会在代码路径上"有级别"。补法与 {@code kbVisibility} 同型：
     * {@code select count(*) from sys_user where id = :uid and status <> 'DISABLED'}。
     */
    public com.knowledge.ai.core.enums.KbGrantLevel kbLevel(LoginUser user, long kbId) {
        DataScope scope = user.getDataScope() == null ? DataScope.SELF : user.getDataScope();
        if (scope == DataScope.DEPT || scope == DataScope.DEPT_AND_CHILD) {
            // 与 kbVisibility 同一套展开规则，两条写死的 SQL 替代"模板替换"，行为可读
            String cond = scope == DataScope.DEPT
                    ? "k.dept_id = :dept"
                    : "(k.dept_id = :dept OR k.dept_id IN (SELECT id FROM sys_department WHERE parent_id = :dept))";
            // MySQL 的 count(*)>0 返回 BIGINT(1/0)，不是 BOOLEAN —— 用 Number 收
            Number hits = (Number) entityManager.createNativeQuery(
                            "select count(*) from sys_kb k where k.id = :kbId and " + cond)
                    .setParameter("kbId", kbId)
                    .setParameter("dept", user.getDeptId())
                    .getSingleResult();
            if (hits == null || hits.longValue() == 0) {
                return com.knowledge.ai.core.enums.KbGrantLevel.NONE;
            }
        }
        List<String> levels = entityManager.createNativeQuery("""
                        select g.level from sys_kb_grant g
                        where g.kb_id = :kbId and g.level in ('READ', 'UPLOAD', 'MANAGE')
                          and ((g.principal_type = 'ROLE' and g.principal_id in
                                (select ur.role_id from sys_user_role ur where ur.user_id = :uid))
                               or (g.principal_type = 'USER' and g.principal_id = :uid))
                        """)
                .setParameter("kbId", kbId)
                .setParameter("uid", user.getUserId())
                .getResultList();
        int rank = 0;
        for (String level : levels) {
            rank = Math.max(rank, switch (level) {
                case "MANAGE" -> 3;
                case "UPLOAD" -> 2;
                case "READ" -> 1;
                default -> 0;
            });
        }
        return switch (rank) {
            case 3 -> com.knowledge.ai.core.enums.KbGrantLevel.MANAGE;
            case 2 -> com.knowledge.ai.core.enums.KbGrantLevel.UPLOAD;
            case 1 -> com.knowledge.ai.core.enums.KbGrantLevel.READ;
            default -> com.knowledge.ai.core.enums.KbGrantLevel.NONE;
        };
    }
}
