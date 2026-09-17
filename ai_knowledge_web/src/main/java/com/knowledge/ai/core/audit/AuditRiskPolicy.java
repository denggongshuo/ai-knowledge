package com.knowledge.ai.core.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 高危判定口径（T-301）——**《05》§4.3 的可执行版本**。
 *
 * <p>《05》§4.3 说「高危操作标记（**界限必须写死，否则标记会失去意义**）」。
 * 本文类就是那句"写死"：把四类高危 + 一项扩展做成一张可被反射用例逐条比对的表，
 * 于是"新加了一个 {@code @AuditLog} 但没登记风险等级"会**直接构建失败**，
 * 而不是等到某天发现审计页的高危筛选漏了东西。
 *
 * <h3>四类高危（《05》§4.3 原文）</h3>
 * <ol>
 *   <li>{@link Category#PERMISSION_EXPANSION 权限扩大} —— 矩阵权限升级、用户新增角色、知识库授权级别提高</li>
 *   <li>{@link Category#DATA_EXPORT 数据导出} —— 批量下载、导出会话、导出报表、导出审计日志</li>
 *   <li>{@link Category#CONTENT_DELETE 内容删除} —— 删除文档 / 知识库 / 彻底删除 / 清空回收站</li>
 *   <li>{@link Category#MODEL_CONFIG_CHANGE 模型配置变更} —— 四类模型的新增、编辑、删除、**启用或切换**</li>
 * </ol>
 *
 * <h3>第 5 类扩展：账号安全变更（2026-09-14 定案）</h3>
 * <p>§4.3 的四类**没有覆盖**「密码修改 / 密码重置 / 换绑手机 / 换绑邮箱」，
 * 而代码里这 4 处已标 {@code HIGH}。处置结论：**保留 HIGH，登记为第 5 类扩展**
 * （{@link Category#ACCOUNT_SECURITY}）。
 *
 * <p>理由：这 4 个动作都是**账号接管**类（改密等于夺权、换绑等于换身份凭证），
 * 降成 NORMAL 会丢掉"账号可能被盗用"的历史线索 —— 而这正是事后追溯最需要的一条。
 * 按本仓「超集 · 纯新增 · 可逆」的先例登记（同 {@code SmsScene.BIND}、{@code T-411}）。
 *
 * <p>⚠️ **§4.3 的「明确不计入高危」不做任何扩展**：单文件浏览式下载**仍为 NORMAL**
 * （见 {@link #planned()} 里的「下载文档」条目）。否则每天上百条高危，标记会退化成噪声 ——
 * 这正是 §4.3 那句话要防的事。
 *
 * <h3>维护约定</h3>
 * <p>新增 {@code @AuditLog} 时必须同步在此登记，否则 {@code AuditChangeContractTest} 失败。
 */
public final class AuditRiskPolicy {

    /** §4.3 四类 + 第 5 类扩展。 */
    public enum Category {

        PERMISSION_EXPANSION("权限扩大"),
        DATA_EXPORT("数据导出"),
        CONTENT_DELETE("内容删除"),
        MODEL_CONFIG_CHANGE("模型配置变更"),
        /** 第 5 类扩展，2026-09-14 定案 —— 见类注释。 */
        ACCOUNT_SECURITY("账号安全变更"),
        /** §4.3「明确不计入高危」的那些动作。登记在此是为了**留下"为什么不标"的证据**。 */
        NOT_HIGH_RISK("不计入高危"),
        ;

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 一条判定规则。
     *
     * @param category 归类；{@link Category#NOT_HIGH_RISK} 表示§4.3 明确排除
     * @param reason   为什么这么定（写下来，避免后人"顺手"改回去）
     */
    public record Rule(Category category, RiskLevel risk, String reason) {
    }

    // ==================================================================
    // 已实现动作（必须与代码里的 @AuditLog 逐条一致）
    // ==================================================================

    private static final Map<String, Rule> IMPLEMENTED = new LinkedHashMap<>();

    static {
        // ---------- 第 1 类 · 权限扩大 ----------
        register(AuditModule.GRANT, "保存功能矩阵", Category.PERMISSION_EXPANSION, RiskLevel.HIGH,
                "§4.3①权限扩大：矩阵档位升级即权限扩大（`permKey` 逐个 diff 提交）");
        register(AuditModule.USER, "编辑用户（含角色调整）", Category.PERMISSION_EXPANSION, RiskLevel.HIGH,
                "§4.3①权限扩大：roleIds 全量替换，可能新增角色；同时可能改部门（数据范围）");
        register(AuditModule.GRANT, "调整知识库授权", Category.PERMISSION_EXPANSION, RiskLevel.HIGH,
                "§4.3①权限扩大：库的可见角色集与档位全量替换");
        register(AuditModule.KB, "保存配置", Category.PERMISSION_EXPANSION, RiskLevel.HIGH,
                "§4.3①权限扩大：同一请求可改 visibleRoles（落到 sys_kb_grant 全量替换），"
                        + "勾上 KB_ADMIN 会把该库最高档位从 READ 抬到 UPLOAD —— 正是「知识库授权级别提高」。"
                        + "与「创建知识库」标 NORMAL 不矛盾：新库还没有内容、初始授权不覆盖任何既有权限，"
                        + "而编辑一个已有内容的库是真扩权。策略表按 (module, action) 做静态键，"
                        + "做不到「只改了描述就降级」，两害相权宁可粗、不漏标（B2 计划 §3.4 假设 4）");

        // ---------- T-3C3 补打点（2026-09-15 · S3-C2）----------
        // 本节的动作名与档次由用户 2026-09-15 逐条裁定，出处见 S3-M03-C2 计划头部。
        register(AuditModule.USER, "新建用户", Category.PERMISSION_EXPANSION, RiskLevel.HIGH,
                "§4.3①权限扩大：`roleIds` 是**必填**（@NotEmpty），所以「建号」与「赋角色」是同一个原子动作"
                        + "——等价于「用户新增角色」。不因为「新建的对象此前不存在」而降到 NORMAL："
                        + "凭空造一个带 SUPER_ADMIN 的账号正是最典型的越权路径，事后追溯首先要看它");
        register(AuditModule.USER, "批量导入用户", Category.PERMISSION_EXPANSION, RiskLevel.HIGH,
                "§4.3①权限扩大：批量形态的「新建 + 赋角色」，一次请求可能造出几十个带角色的账号 —— "
                        + "权限扩大面比单个新建更大。《10》§3 该行本就带 🚩。与「新建用户」同档");
        register(AuditModule.GRANT, "编辑角色", Category.PERMISSION_EXPANSION, RiskLevel.HIGH,
                "§4.3①权限扩大：`permissions` 是全量替换（先 delete 再 insert），一次「编辑」"
                        + "可以把这个角色的权限点整个换掉，而该角色下所有成员的权限**立即随之改变**。"
                        + "与「保存功能矩阵」是同一类动作的两条路径（按权限点改 / 按角色改），档次必须一致");
        register(AuditModule.GRANT, "启用/停用角色", Category.PERMISSION_EXPANSION, RiskLevel.HIGH,
                "§4.3①权限扩大：停用会让该角色**全部成员**的权限按《05》§242 降级为默认角色 —— "
                        + "一次点击改变 N 个人的权限，且是「看不出被改了什么」的那种"
                        + "（成员自己的角色列表没变，是档位被抽掉了）；启用则把这些权限一次性恢复");

        // ---------- 第 3 类 · 内容删除 ----------
        register(AuditModule.USER, "删除用户", Category.CONTENT_DELETE, RiskLevel.HIGH,
                "§4.3③内容删除：软删账号，不可逆（保留历史记录但不可再用）");
        // `T-407` 落地（2026-09-17 · S4-D）：下面两条**从 PLANNED 移入 IMPLEMENTED**。
        // 移入的判据是"注解真的挂上去了" —— 本类的正向断言（注解 → 策略）会当场发现漏登记，
        // 而反向断言（策略 → 注解）会排除 PLANNED，所以**留在 PLANNED 里不会再报错**，
        // 必须靠人工在实装那一刻把它搬过来（这一步漏了就是"策略表说它是计划、而它已经在跑"）。
        register(AuditModule.KB, "删除知识库", Category.CONTENT_DELETE, RiskLevel.HIGH,
                "§4.3③内容删除：软删进回收站（`T-407`）。`G3-8` 的判据正要求它标高危 —— "
                        + "口径侧早在 S3-D 冻结（本条原先就在 PLANNED 表里），本批补的是**运行时**那一半："
                        + "在这之前 `KbController` 无任何 `DeleteMapping`，这条策略**没有任何触发点**");
        register(AuditModule.KB, "清空索引", Category.CONTENT_DELETE, RiskLevel.HIGH,
                "§4.3③内容删除：清空后该库文档全部检索不到（块还在 MySQL，但向量已删）—— "
                        + "对用户而言与删除内容等价。`T-407` 的清空半边，与「删除知识库」同批落地");

        // ---------- 第 5 类扩展 · 账号安全变更（2026-09-14 定案） ----------
        register(AuditModule.AUTH, "修改密码", Category.ACCOUNT_SECURITY, RiskLevel.HIGH,
                "第5类扩展：改密后旧令牌全部失效，属账号接管类动作");
        register(AuditModule.AUTH, "重置密码（忘记密码自助）", Category.ACCOUNT_SECURITY, RiskLevel.HIGH,
                "第5类扩展：凭重置凭证改密，可视为账号接管路径");
        register(AuditModule.USER, "重置密码（管理员）", Category.ACCOUNT_SECURITY, RiskLevel.HIGH,
                "第5类扩展（T-3C3 补）：与「重置密码（忘记密码自助）」是**同一件事的两条路径**"
                        + "——一条用户自助、一条管理员代劳。档次必须一致，否则会出现"
                        + "「自己重置算高危、管理员重置不算」这种反直觉的口径");
        register(AuditModule.USER, "启用/停用账号", Category.ACCOUNT_SECURITY, RiskLevel.HIGH,
                "第5类扩展（T-3C3 补）：停用会立即撤销全部刷新令牌、该账号所有知识库不可见（W7）；"
                        + "启用则把这些权限一次性恢复。两个方向都是「账号可用性」的开关，"
                        + "属账号接管类动作的面 —— 与「修改密码」保留 HIGH 同一条理由");
        register(AuditModule.USER, "换绑手机号", Category.ACCOUNT_SECURITY, RiskLevel.HIGH,
                "第5类扩展：手机号是登录凭证 + 找回密码通道，换绑等于换身份凭据");
        register(AuditModule.USER, "换绑邮箱", Category.ACCOUNT_SECURITY, RiskLevel.HIGH,
                "第5类扩展：同换绑手机号（邮箱可作找回通道）");

        // ---------- §4.3「明确不计入高危」 ----------
        register(AuditModule.USER, "修改个人资料", Category.NOT_HIGH_RISK, RiskLevel.NORMAL,
                "§4.3 明确排除：普通查看/编辑不属四类，标高危会让标记失效");
        register(AuditModule.USER, "更换头像", Category.NOT_HIGH_RISK, RiskLevel.NORMAL,
                "§4.3 明确排除：同上，纯资料类改动");
        register(AuditModule.KB, "创建知识库", Category.NOT_HIGH_RISK, RiskLevel.NORMAL,
                "§4.3 四类不含建库：新建库不扩大任何既有权限（初始授权只覆盖向导里勾选的角色 + 超管 MANAGE），"
                        + "《01》§10.1 的建库样例也没有 🚩。标高危会让「高危」退化成「所有写操作」");
        register(AuditModule.GRANT, "新建角色", Category.NOT_HIGH_RISK, RiskLevel.NORMAL,
                "§4.3 四类不含建角色（T-3C3）：新角色**零成员**（sys_user_role 里还没有引用），"
                        + "建它不改变任何人的现有权限 —— 与「创建知识库」同一条理由（建一个没人被挂上去的容器）。"
                        + "**但「给它配权限」是另一件事**：走 `编辑角色` 或经 `编辑用户` 挂人时，"
                        + "那两次操作各自已标高危。策略表按 (module, action) 做静态键，"
                        + "做不到「看 permissions 是否为空再降级」，两害相权宁可粗、不漏标");
        register(AuditModule.GRANT, "删除角色", Category.NOT_HIGH_RISK, RiskLevel.NORMAL,
                "§4.3③不含删空角色（T-3C3）：走到删除那一行时该角色**必定 0 成员**（成员 >0 会被"
                        + " ROLE_IN_USE 拦下），所以删它不改变任何人的权限。§4.3③指的是**内容**"
                        + "（文档/知识库）与可用权限载体，删一个空角色更接近「清理无用配置」。"
                        + "⚠️ 若将来放宽「有成员也能删」，此处必须同步改 HIGH 并加 affectedUsers");
        register(AuditModule.SYSTEM, "新建部门", Category.NOT_HIGH_RISK, RiskLevel.NORMAL,
                "§4.3 四类不含组织结构维护（T-3C3）：不扩大权限、不是数据导出、删的不是内容、与模型配置无关。"
                        + "**但必须留痕**：改部门 = 改所有人的数据范围（数据范围按部门收敛）。"
                        + "模块归 SYSTEM 的依据见 AuditModule#SYSTEM 的 javadoc");
        register(AuditModule.SYSTEM, "编辑部门", Category.NOT_HIGH_RISK, RiskLevel.NORMAL,
                "§4.3 四类不含组织结构维护（T-3C3）。同「新建部门」：非高危但必须留痕");
        register(AuditModule.SYSTEM, "删除部门", Category.NOT_HIGH_RISK, RiskLevel.NORMAL,
                "§4.3③不含删空部门（T-3C3）：三道闸（无子部门 / 无成员 / 无知识库）挡着，"
                        + "删的是空组织节点。⚠️ 若将来放宽「有内容也能删」，此处必须同步改 HIGH");

        // ---------- 第 2 类 · 数据导出（T-304 于 S3-C 落地，2026-09-15 从 PLANNED 移入） ----------
        register(AuditModule.AUDIT, "导出审计日志", Category.DATA_EXPORT, RiskLevel.HIGH,
                "§4.3②数据导出；同时是 T-304「审计自审」—— 导出这件事本身也要记一条。"
                        + "移入 IMPLEMENTED 的时点是 @AuditLog 注解真正落到 AuditController#export 上；"
                        + "在此之前它必须留在 PLANNED，否则反向断言（策略→注解）会报幽灵条目");

        // ---------- 文档 · 上传（2026-09-16 · S4-M05-A 落地） ----------
        register(AuditModule.DOC, "上传文档", Category.NOT_HIGH_RISK, RiskLevel.NORMAL,
                "§4.3 四类**均不含上传**：它不是权限扩大（上传权限本身由角色授予，这条不改授权）、"
                        + "不是数据导出（数据**进**系统不是出）、删的不是内容（是新增）、与模型配置无关。"
                        + "《05》§4.2 的采集范围把「上传」列在「文档」行里 —— **要留痕**，但档位是 NORMAL。"
                        + "⚠️ 若将来加上「覆盖上传会顶掉既有版本」，那一步要单独评估是否升档"
                        + "（内容被替换 ≠ 内容被新增，但 §4.3③ 说的是删除，覆盖是灰色地带，本批不擅自扩大）");

        // ---------- 文档 · 解析链路（2026-09-17 · S4-E2 的 T-O06 落地） ----------
        // 三条一起登记：它们是**同一件事的三个时点**（谁触发 / 跑成了 / 跑砸了）。
        // 分档理由共用一条：都不改变权限、不删除数据、不导出数据、不动模型配置 ——
        // 唯一的效果是让一篇**已经授权可见**的文档变得可检索（或不可）。
        register(AuditModule.DOC, "触发解析", Category.NOT_HIGH_RISK, RiskLevel.NORMAL,
                "§4.3 四类均不含「触发解析」：它不扩大任何权限（文档的可见性由上传时的授权与密级决定，"
                        + "解析只是让它可被检索）、不删除内容、不导出数据。"
                        + "**为什么不能因为「上传也是 NORMAL」就省掉这条**：`O6` §2 痛点 2 的原话是"
                        + "「粒度到库、不到文档 —— 出问题时无法回答『这个人动过哪些文档』」。"
                        + "上传那条的 target 此前是『知识库#N』，本批两条都细化到『文档#N』，"
                        + "于是按文档一筛就是完整生命周期（`O6` 判据 6）");
        register(AuditModule.DOC, "解析完成", Category.NOT_HIGH_RISK, RiskLevel.NORMAL,
                "同上。⚠️ 与「触发解析」分开登记而不是合成一条 + 结果字段：审计页的动作筛选是独立入口，"
                        + "运维最常筛的就是「解析失败」；而接口级 result（SUCCESS/FAILED）说的是"
                        + "**接口调用**成没成 —— 接口早就 200 返回了，解析是几分钟后的事。两者不是一回事");
        register(AuditModule.DOC, "解析失败", Category.NOT_HIGH_RISK, RiskLevel.NORMAL,
                "同上。**系统动作**（worker 线程写的，无登录态）→ 操作人记 `system`/「系统」，"
                        + "见 `AuditAspect#build` 里那条新规则。"
                        + "失败原因走日志与 `sys_async_task.error_msg`，**不**写进审计的 `fail_reason` —— "
                        + "那里的语义是「被审计的方法抛了异常」，而解析失败是**正常返回的业务结果**"
                        + "（`DocParseService#parseDocument` 从不抛异常），两处职责不重叠");
    }

    // ==================================================================
    // 已裁定但尚未实现（S6 / S7 落地时必须按此打标）
    // ==================================================================

    /**
     * 已定口径、但**代码里还没有对应注解**的动作。
     *
     * <p>单列一张表的理由：口径必须**在实现之前**冻结。等 S7 做下载时再讨论
     * 「单文件下载算不算高危」，很容易被"既然都记了审计，顺手标高危吧"带偏 ——
     * 而 §4.3 恰恰明确说了不算。
     *
     * <p>正向断言（注解 → 策略）不受本表影响；反向断言（策略 → 注解）会排除本表。
     */
    private static final Map<String, Rule> PLANNED = new LinkedHashMap<>();

    static {
        register(PLANNED, AuditModule.DOC, "下载文档", Category.NOT_HIGH_RISK, RiskLevel.NORMAL,
                "§4.3 明确不计入高危：单文件浏览式下载是日常动作，否则每天上百条高危、标记失效（G3-8 判据）");
        register(PLANNED, AuditModule.DOC, "批量下载", Category.DATA_EXPORT, RiskLevel.HIGH,
                "§4.3②数据导出：批量形态属数据外带风险（与单文件下载的分界就在「批量」）");
        register(PLANNED, AuditModule.DOC, "删除文档", Category.CONTENT_DELETE, RiskLevel.HIGH,
                "§4.3③内容删除");
        register(PLANNED, AuditModule.SYSTEM, "启用模型配置", Category.MODEL_CONFIG_CHANGE, RiskLevel.HIGH,
                "§4.3④模型配置变更，**含启用切换**");
        register(PLANNED, AuditModule.SYSTEM, "切换重排模型", Category.MODEL_CONFIG_CHANGE, RiskLevel.HIGH,
                "§4.3④：切换重排/VL 属系统级行为变更，必须留痕");
    }

    private AuditRiskPolicy() {
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /** 查已实现动作的规则；未登记返回空。 */
    public static Optional<Rule> findImplemented(String module, String action) {
        return Optional.ofNullable(IMPLEMENTED.get(key(module, action)));
    }

    /** 查已裁定未实现动作的规则；未登记返回空。 */
    public static Optional<Rule> findPlanned(String module, String action) {
        return Optional.ofNullable(PLANNED.get(key(module, action)));
    }

    /** 已实现动作 → 规则（只读视图，供用例遍历与文档生成）。 */
    public static Map<String, Rule> implemented() {
        return Map.copyOf(IMPLEMENTED);
    }

    /** 已裁定未实现动作 → 规则（只读视图）。 */
    public static Map<String, Rule> planned() {
        return Map.copyOf(PLANNED);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private static void register(String module, String action, Category category, RiskLevel risk, String reason) {
        register(IMPLEMENTED, module, action, category, risk, reason);
    }

    private static void register(Map<String, Rule> target, String module, String action,
                                 Category category, RiskLevel risk, String reason) {
        String key = key(module, action);
        Rule previous = target.put(key, new Rule(category, risk, reason));
        if (previous != null) {
            // 重复登记 = 后一条静默覆盖前一条，是最难查的那类配置错误
            throw new IllegalStateException("审计风险策略重复登记：" + key);
        }
    }

    /**
     * 组合键：{@code "模块 动作"}（**一个半角空格分隔**）。
     *
     * <p>分隔符必须与 {@code AuditChangeContractTest} 里构造查找键的口径一致 ——
     * 那边的键来自遍历 {@code @AuditLog} 后按 {@code module + " " + action} 拼的。
     */
    private static String key(String module, String action) {
        return module + ' ' + action;
    }
}
