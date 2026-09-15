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

        // ---------- 第 3 类 · 内容删除 ----------
        register(AuditModule.USER, "删除用户", Category.CONTENT_DELETE, RiskLevel.HIGH,
                "§4.3③内容删除：软删账号，不可逆（保留历史记录但不可再用）");

        // ---------- 第 5 类扩展 · 账号安全变更（2026-09-14 定案） ----------
        register(AuditModule.AUTH, "修改密码", Category.ACCOUNT_SECURITY, RiskLevel.HIGH,
                "第5类扩展：改密后旧令牌全部失效，属账号接管类动作");
        register(AuditModule.AUTH, "重置密码（忘记密码自助）", Category.ACCOUNT_SECURITY, RiskLevel.HIGH,
                "第5类扩展：凭重置凭证改密，可视为账号接管路径");
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
        register(PLANNED, AuditModule.KB, "删除知识库", Category.CONTENT_DELETE, RiskLevel.HIGH,
                "§4.3③内容删除；G3-8 判据正要求它标高危");
        register(PLANNED, AuditModule.SYSTEM, "启用模型配置", Category.MODEL_CONFIG_CHANGE, RiskLevel.HIGH,
                "§4.3④模型配置变更，**含启用切换**");
        register(PLANNED, AuditModule.SYSTEM, "切换重排模型", Category.MODEL_CONFIG_CHANGE, RiskLevel.HIGH,
                "§4.3④：切换重排/VL 属系统级行为变更，必须留痕");
        register(PLANNED, AuditModule.AUDIT, "导出审计日志", Category.DATA_EXPORT, RiskLevel.HIGH,
                "§4.3②数据导出；同时是 T-304「审计自审」——导出这件事本身也要记一条");
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
