package com.knowledge.ai.core.audit;

import java.util.ArrayList;
import java.util.List;

/**
 * 审计登记通道（T-300）：业务侧写、切面读。
 *
 * <p><b>为什么需要它</b>：{@link AuditLog} 注解只能描述"操作了什么"，
 * 描述不了"从什么变成了什么" —— {@code before} 只有业务代码知道（它刚读过旧值）。
 * 所以契约是：业务侧在**读旧值之后、写新值之前**调 {@link #change}，
 * 切面在 {@code finally} 里 {@link #drain} 取走。
 *
 * <pre>
 *   @Transactional
 *   @AuditLog(module = AuditModule.GRANT, action = "保存功能矩阵", risk = RiskLevel.HIGH)
 *   public ... saveMatrix(List&lt;MatrixChange&gt; changes) {
 *       Map&lt;String,String&gt; before = readCurrentLevels();     // ① 先读旧值
 *       for (var c : changes) {
 *           AuditContext.change(labelOf(c), before.get(c.permKey()), c.level());  // ② 登记
 *       }
 *       AuditContext.affectedUsers(countRoleMembers(roleId));                   // ② 登记
 *       applyChanges(changes);                                                  // ③ 再写新值
 *   }
 * </pre>
 *
 * <p><b>为什么是 ThreadLocal</b>：与 {@link com.knowledge.ai.core.security.LoginUserContext}、
 * {@link com.knowledge.ai.core.trace.TraceContext} 同一取舍 —— 审计是横切关注点，
 * 一路透传参数会污染所有业务方法签名。
 *
 * <p><b>代价是不跨线程</b>：异步任务（解析 / 索引 / 打包）在工作线程里调 {@code change}
 * 不会被触发方请求的切面采集到。异步任务若需要审计，应在提交时把结果写进业务表，
 * 由后续的同步操作补记（见《2026-09-14-S3-M03-A》§3.3「明确不做」）。
 *
 * <p><b>清理责任在两处</b>（缺一即可能残留）：
 * <ol>
 *   <li>{@link AuditAspect} —— 每次采集完 {@code finally} 里 {@link #drain}，覆盖正常路径；</li>
 *   <li>{@link AuditContextFilter} —— 请求结束时 {@link #clear}，兜住
 *       「登记了但所在方法没挂 {@code @AuditLog}」这条漏网路径。</li>
 * </ol>
 * 池化线程不清理的后果不是"少一条日志"，而是**下一条审计会带上前一次的变更明细**。
 */
public final class AuditContext {

    /** 登记内容为空时的占位，避免每次 drain 都新建对象。 */
    private static final Captured EMPTY = new Captured(List.of(), null, null);

    private static final ThreadLocal<Holder> HOLDER = new ThreadLocal<>();

    private AuditContext() {
    }

    // ==================== 业务侧写入 ====================

    /**
     * 登记一行「变更前后对照」。
     *
     * <p>调用时机必须是**读旧值之后、写新值之前** —— 反过来登记到的 before 就是新值，
     * 而前端的「共 N 项，其中 M 项变更」会据此算出 M=0，看着像"什么都没改"。
     *
     * <p>同一操作可多次调用，按登记顺序进入 {@code changes[]}（顺序即前端表格行序）。
     */
    public static void change(String label, String before, String after) {
        holder().changes.add(new AuditChange(label, before, after));
    }

    /**
     * 登记受影响人数（权限变更类动作才有）。
     *
     * <p>「影响用户数」是判断风险等级的关键（《05》§4.5）：
     * 改了一个没人用的角色的权限，与改了全员角色的权限，风险不是一个量级。
     * 多次调用以**最后一次**为准（同一次操作只有一个影响面）。
     */
    public static void affectedUsers(int count) {
        holder().affectedUsers = Math.max(count, 0);
    }

    /**
     * 登记受影响角色名（T-302 查询侧的 W1 修复，2026-09-15）。
     *
     * <p><b>为什么需要它</b>：前端详情抽屉用 {@code roleName} 渲染
     * 「{@code N 人（该角色下全部成员）}」，而 {@code sys_audit_log.role_name} 列存的
     * 是**操作人自己的角色码**（{@code AuditAspect#build} 从 {@code LoginUser.getRoleCodes()} 写）——
     * 语义刚好相反。接真后端后抽屉会显示「12 人（SUPER_ADMIN 下全部成员）」：
     * 数字是真的、格式也是真的，只有那个角色名是假的。
     *
     * <p>所以独立登记一笔「受影响角色」，落进新增的 {@code affected_role} 列。
     * 与 {@link #affectedUsers} 是**配对**的：有影响面才两个都有，没影响面两个都是 null。
     *
     * <p>多次调用以最后一次为准（与 {@link #affectedUsers} 同口径）。
     *
     * @param roleName 角色名（展示名，如「访客」），不是角色码
     */
    public static void affectedRole(String roleName) {
        holder().affectedRole = (roleName == null || roleName.isBlank()) ? null : roleName;
    }

    // ==================== 切面读取 ====================

    /**
     * 取走本次登记的全部内容，并**立即解除绑定**。
     *
     * <p>是"取走"不是"读取"：{@link AuditAspect} 在 {@code finally} 里调用，
     * 调用后当前线程回到干净状态。这样即便下一个方法忘了清理也不会串味。
     *
     * @return 快照；从未登记过时返回 {@link #EMPTY}（changes 为空列表、affectedUsers 为 {@code null}）
     */
    public static Captured drain() {
        Holder holder = HOLDER.get();
        HOLDER.remove();
        if (holder == null) {
            return EMPTY;
        }
        return new Captured(List.copyOf(holder.changes), holder.affectedUsers, holder.affectedRole);
    }

    /** 清空但**不返回** —— 供 {@link AuditContextFilter} 在请求结束时兜底。 */
    public static void clear() {
        HOLDER.remove();
    }

    // ==================== 内部 ====================

    private static Holder holder() {
        Holder holder = HOLDER.get();
        if (holder == null) {
            holder = new Holder();
            HOLDER.set(holder);
        }
        return holder;
    }

    private static final class Holder {
        private final List<AuditChange> changes = new ArrayList<>();
        private Integer affectedUsers;
        private String affectedRole;
    }

    /**
     * 一次采集的快照。
     *
     * @param changes       变更明细，**不可变**；无登记时是空列表
     * @param affectedUsers 受影响人数；未登记时为 {@code null}（与 0 语义不同：0 是"确实没人受影响"）
     * @param affectedRole  受影响角色名；未登记时为 {@code null}。与 {@code affectedUsers} 配对 ——
     *                      仅在后者的登记点（权限变更类动作）才有意义
     */
    public record Captured(List<AuditChange> changes, Integer affectedUsers, String affectedRole) {

        public boolean isEmpty() {
            return changes.isEmpty() && affectedUsers == null && affectedRole == null;
        }
    }
}
