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
        /**
         * 第 6 类扩展，2026-09-18 定案（`T-O04` 步 3）。
         *
         * <h3>为什么必须新开一类，而不是塞进既有五类</h3>
         * <p>§4.3 那四类（+第 5 类）的共同点是「**人做了什么**」：扩权、导出、删除、改配置、改账号。
         * 而「恶意内容被拦」不是人的动作，是**系统在响应之后自己发现的**一件事 ——
         * 操作人写的是「系统」（扫描跑在 worker 线程上）。
         *
         * <p>塞进哪一类都不成立：它不是权限扩大（没人扩权）、不是数据导出（恰恰相反，
         * 拦下它是**防止**内容流出）、不是内容删除（没有东西被删，文件还在桶里当取证材料）。
         * 硬塞的后果是**审计页按类筛选时会把它归错组**，而"这批高危里哪些是安全事件"
         * 恰恰是出事时第一个要问的问题。
         *
         * <p>⚠️ 与第 5 类同一条纪律：新增高危类**必须在类注释里写明定案日期与理由** ——
         * 否则下一个人会以为 §4.3 本来就有六类。
         */
        SECURITY_EVENT("安全事件"),
        /**
         * 第 7 类扩展，2026-09-23 定案（`T-C03`）。
         *
         * <h3>为什么必须新开一类，而不是塞进既有六类</h3>
         * <p>本类的第一个成员是「**开关图谱旁路**」：`PUT /system/feature/graph` 一按下去，
         * **所有用户的问答结果都会变**（图谱支路参与融合 vs 不参与），而**没有任何一条数据被改**。
         *
         * <p>塞进哪一类都不成立：
         * <ul>
         *   <li>不是 {@link #MODEL_CONFIG_CHANGE} —— §4.3④ 的射程是「**四类模型**（对话 / 嵌入 /
         *       重排 / VL）的新增、编辑、删除、启用或切换」（《05》§4.3 原文）。图谱旁路不是模型，
         *       硬塞进去会让"这批高危里哪些动了模型"这个筛选题失真；</li>
         *   <li>不是 {@link #PERMISSION_EXPANSION} / {@link #DATA_EXPORT} /
         *       {@link #CONTENT_DELETE} —— 没有扩权、没有导出、没有东西被删；</li>
         *   <li>不是 {@link #SECURITY_EVENT} —— 那一类的定义是「**系统自己发现的**恶意内容」，
         *       操作人写「系统」；而本类恰恰是**人**按的开关；</li>
         *   <li>不是 {@link #NOT_HIGH_RISK} —— 这条与 §4.3 那句「界限必须写死，否则标记会失去意义」
         *       直接冲突：一个能改变全站检索行为的开关若不算高危，高危标记就没有意义了。</li>
         * </ul>
         *
         * <p>⚠️ 与第 5、6 类同一条纪律：新增高危类**必须在类注释里写明定案日期与理由** ——
         * 否则下一个人会以为 §4.3 本来就有七类。
         */
        SYSTEM_BEHAVIOR_CHANGE("系统行为变更"),
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
        register(AuditModule.QA, "导出会话", Category.DATA_EXPORT, RiskLevel.HIGH,
                "§4.3②数据导出（`S5-M08-B` 的 T-802 导出半边）。《01》§487 把「导出会话记录」"
                        + "与批量下载并列为**数据外带**行为 —— 会话正文里可能有用户贴进来的内部数据，"
                        + "带出去一份就是一份。《05》§4.2 的采集范围也在「问答」行点名了它。"
                        + "⚠️ 与「导出审计日志」的差别：那条是审计自审（导的是日志本身），"
                        + "这条导的是**业务内容**，但同属 §4.3② 一类，档次一致");
        register(AuditModule.QA, "导出运营报表", Category.DATA_EXPORT, RiskLevel.HIGH,
                "§4.3②数据导出（`T-A02` 的运营报表导出）。本类的 {@link Category#DATA_EXPORT}"
                        + "枚举注释里**逐字列着「导出报表」**（与批量下载 / 导出会话 / 导出审计日志并列）"
                        + "⇒ 这一条是**口径要求**，不是「要不要加」的判断。"
                        + "⚠️ 与本模块「导出会话」的差别：那份是**某个用户自己的**会话正文，"
                        + "这份是**全站**未命中清单 —— 含**提问原文 + 提问人姓名**"
                        + "（`sys_qa_miss.question` / `last_user_id`），外带面更宽（跨用户）。"
                        + "两者同属 §4.3②，档次一致");
        // ⚠️ 「删除会话」**刻意不登记**：《05》§4.2 把「问答」模块的采集范围明确列成
        // 「会话导出、批量反馈处理」两项，**不含删除**；§4.3③「内容删除」指的是文档/知识库
        // 这类平台内容资产，而删会话删的是**自己**的记录（查询条件带 user_id，越权做不到）。
        // 不去"顺手补一个审计点"：审计点每加一个就要同步策略表 + 冻结清单，
        // 而《05》已经用枚举的方式裁过了 —— 需求没点名的就不加。

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
        // `T-O04` 步 3（S6-M09-B 的 O 项）：恶意内容被拦 —— **本仓第一条 SECURITY_EVENT**。
        // `O4` 判据 4 的原话就是「`FILE_MALICIOUS` + 审计 **HIGH**」，档次不需另裁。
        // ⚠️ 它是唯一一条**操作人写「系统」**的高危记录：扫描跑在上传响应之后的后台线程上。
        // 要追"是谁传的这篇"，走同 docId 的「上传文档」那条（那条记着真实操作人，
        // 而 target 的 `文档#N` 表达式与这里**完全一致** —— 按它一筛就是这篇的完整生命周期）
        register(AuditModule.DOC, "恶意内容拦截", Category.SECURITY_EVENT, RiskLevel.HIGH,
                "安全事件：文件被 ClamAV 判定为恶意内容（命中病毒签名）。"
                        + "它不是人的动作、也不是上面五类里的任何一类 —— 故 `S6-M09-B` 新开第 6 类。"
                        + "标 HIGH 的实质理由是：**这是本仓少见的真的该报警的那一类**，"
                        + "它不该被日常上传的正常记录淹没");
        register(AuditModule.DOC, "解析失败", Category.NOT_HIGH_RISK, RiskLevel.NORMAL,
                "同上。**系统动作**（worker 线程写的，无登录态）→ 操作人记 `system`/「系统」，"
                        + "见 `AuditAspect#build` 里那条新规则。"
                        + "失败原因走日志与 `sys_async_task.error_msg`，**不**写进审计的 `fail_reason` —— "
                        + "那里的语义是「被审计的方法抛了异常」，而解析失败是**正常返回的业务结果**"
                        + "（`DocParseService#parseDocument` 从不抛异常），两处职责不重叠");
        // ---------- `S7-M06-B`（2026-09-20）：需求《05》§6 文档审计动作表里的最后两条 ----------
        // 依据是**逐字的**：`05:367` 的文档那一行写着「上传、下载、删除、**重建索引**、**移动知识库**」。
        // 下载两条仍在 PLANNED（属 `T-614`/`T-615`），这两条本批落地。
        register(AuditModule.DOC, "重建索引", Category.NOT_HIGH_RISK, RiskLevel.NORMAL,
                "§4.3 四类均不含它：不删内容、不导出、不扩权、不改账号。"
                        + "🔴 **它必须与「库级重建不审计」分开读** —— `KbController#reindex` 的注释写着"
                        + "库级重建「不走 @AuditLog：重建不改变业务语义（内容一样，只是重算索引）」。"
                        + "**本条不是那条的例外，是另一件事**：文档级重建走的是**整条解析流水线**"
                        + "（需求《06》§4「会重新执行解析与表格识别」、§5「用于修复解析失败的个别文档」）"
                        + "⇒ 表格识别、摘要、图片描述都会**重算**，内容语义真的会变。"
                        + "**判据**：把两者的实现摆在一起看 —— 库级只调 `ChunkVectorizer`，"
                        + "文档级调 `ParseWorker.enqueue`");
        register(AuditModule.DOC, "移动知识库", Category.NOT_HIGH_RISK, RiskLevel.NORMAL,
                "§4.3 四类均不含它。**为什么不是「权限扩大」**：那一类说的是**改授权**这个动作"
                        + "（`KB 保存配置` 那条的理由逐字是「勾上 KB_ADMIN 会把档位从 READ 抬到 UPLOAD」）；"
                        + "而移动文档**不修改任何一条授权**，它只是换了个归属 —— "
                        + "而且服务层**拒绝**目标库密级低于当前生效密级的移动（`I-6` 防降级绕过），"
                        + "所以它也不可能把一篇机密文档放进一个密级更松的库。"
                        + "⚠️ 照实说清它**确实会**改变「谁能看到这篇」（两个库的授权集合不同）—— "
                        + "这正是需求把它列进审计动作表的理由，但「可见集合变了」与「有人扩了权」是两件事");
        // ---------- `S7-M06-D`（2026-09-21）：版本回滚（`G7-6`） ----------
        // 回滚 = 复制目标版本的分片为**新版本** + 重新向量化 + 清旧当前版本的向量，
        // **不删任何版本行**（§6.4「保留新版本记录而非改写历史，保证可追溯」）。
        // 🔴 **为什么不是「内容删除」**：`CONTENT_DELETE` 说的是删库/删文档那种"记录消失"，
        //   而回滚**一行都不删**（旧当前版本只是翻 is_current=0，仍在版本历史里）；它覆盖的是
        //   Milvus 里的向量，而那**可逆**（能再回滚回去）。历史问答的引用锚定在
        //   `sys_retrieval_trace_chunk`，回滚一字不动那张表 ⇒ 引用不变（`G7-6`）。
        //   ⇒ 风险等同一次「重建索引」，标 NORMAL。
        register(AuditModule.DOC, "版本回滚", Category.NOT_HIGH_RISK, RiskLevel.NORMAL,
                "§4.3 四类均不沾；回滚=新增版本+重向量化+清旧向量、不删版本行且可逆，风险等同「重建索引」，标 NORMAL");

        // ---------- `S7-M06-E`（2026-09-21）：下载（`G7-9`）—— 从 PLANNED 移入 ----------
        // 依据同「重建索引/移动知识库」那两条：`05:367` 的文档审计动作行逐字含「下载」。
        // 移入判据是"注解真的挂上去了"（`DownloadService` 的 @AuditLog）。删除文档仍留 PLANNED（属 `T-611`/F 批）。
        register(AuditModule.DOC, "下载文档", Category.NOT_HIGH_RISK, RiskLevel.NORMAL,
                "§4.3 明确不计入高危：单文件浏览式下载是日常动作（`G3-8` 冻结口径逐字：单文件浏览式下载**仍 NORMAL**）。"
                        + "否则每天上百条高危、标记失效 —— 这正是 §4.3「界限必须写死」要防的事。"
                        + "⚠️ 与「批量下载」的分界就在**批量**：一次拉一篇是浏览、一次拉一批是数据外带");
        register(AuditModule.DOC, "批量下载", Category.DATA_EXPORT, RiskLevel.HIGH,
                "§4.3②数据导出：`DATA_EXPORT` 枚举注释逐字列着「**批量下载**、导出会话、导出报表、导出审计日志」"
                        + "⇒ 这是**口径要求**不是判断。一次打包把 ≤50 篇文档带出系统，属数据外带风险面，"
                        + "与单文件下载的分界就在「批量」。与「导出会话/运营报表」同属 §4.3②，档次一致");
        register(AuditModule.DOC, "导出文档清单", Category.DATA_EXPORT, RiskLevel.HIGH,
                "§4.3②数据导出（`T-615` 的 export-list）。⚠️ 与「批量下载」的差别：那个带走**文件本身**，"
                        + "这个带走**清单**（有哪些文件、多大、谁传的）—— 清单本身也是一份可外带的资产"
                        + "（暴露了库里有什么），故同归 §4.3② 标 HIGH。与运营报表导出同一条形态");

        // ---------- `S7-M06-F`（2026-09-22）：删除 / 回收站（`G7-10`~`G7-14`） ----------
        // 「删除文档」从 PLANNED 移入（`DocLifecycleService#softDelete` 的 @AuditLog 真的挂上去了）。
        // ⚠️ 承 `S7-M06-E` §8.3 的教训：「从 PLANNED 变 IMPLEMENTED」牵着**三处**
        //    （策略表档位 · 钉在旧档位上的断言 · 前端 mock）—— 本批的 `AuditChangeContractTest`
        //    与 `AuditCoverageFlowTest` 都要同步，别只改这里。
        register(AuditModule.DOC, "删除文档", Category.CONTENT_DELETE, RiskLevel.HIGH,
                "§4.3③内容删除（`05:414` 的动作行逐字含「删除文档」）。"
                        + "⚠️ **它是软删**（进回收站、30 天内可恢复），但仍标高危 —— "
                        + "`§4.3③` 判的是「**内容从用户视野里消失**」而不是「字节被抹掉」："
                        + "删除之后该文档立刻不可检索、不可下载，与真删在用户的感受上没有差别。"
                        + "⚠️ 与「彻底删除」是**两条不同的日志**：这条说「进了回收站」，那条说「没了」");
        // 下面两条是**新登记**（此前不在表里）。依据是 `05:414` 那一行逐字列着
        // 「删除文档、删除知识库、**彻底删除、清空回收站**」——四个条目，不再细分文档/知识库
        // （`Category#CONTENT_DELETE` 的 javadoc 里也是这么写的）。
        register(AuditModule.DOC, "彻底删除", Category.CONTENT_DELETE, RiskLevel.HIGH,
                "§4.3③内容删除（`05:414` 逐字含「彻底删除」）。🔴 **本仓唯一真正抹掉内容的动作** —— "
                        + "源文件、解析产物、Milvus 向量一并清掉，**不可逆**（`O7` 规则表第 ② 列）。"
                        + "⚠️ 与「删除文档」的分界就是**能不能恢复**：那条进回收站，这条没有回头路");
        register(AuditModule.DOC, "清空回收站", Category.CONTENT_DELETE, RiskLevel.HIGH,
                "§4.3③内容删除（`05:414` 逐字含「清空回收站」）；需求 `06` §7.6 另点名"
                        + "「清空与彻底删除都**必须记审计日志**」。⚠️ 它是**批量且不可逆**的彻底删除 "
                        + "⇒ 一次动作可能抹掉几十篇文档，是 §4.3 四类里后果最重的一条");
        register(AuditModule.DOC, "恢复", Category.NOT_HIGH_RISK, RiskLevel.NORMAL,
                "§4.3 四类均不沾。⚠️ **它是本批唯一一条超出了 §4.3 动作表的登记** —— "
                        + "依据是 `O7` 规则表的「审计日志 × ⑤恢复」那一格写着**「记一条」**。"
                        + "该记的理由：**恢复是内容可见性的变更**（恢复后该文档重新可被召回），"
                        + "比大多数 NORMAL 动作更需要留痕。标 NORMAL 的推理与「版本回滚」同源 —— "
                        + "它不销毁任何东西、且可再次删除（可逆）");

        // ---------- 系统 · 模型配置（2026-09-18 · S6-M09-A 的 T-900~T-903 落地） ----------
        // 四条一起登记：它们**本来就是同一条裁定**（`MODEL_CONFIG_CHANGE` 的类注释原文是
        // 「四类模型的**新增、编辑、删除、启用或切换**」），`PLANNED` 里那两条只预告了"启用"半边。
        register(AuditModule.SYSTEM, "新增模型配置", Category.MODEL_CONFIG_CHANGE, RiskLevel.HIGH,
                "§4.3④模型配置变更。**为什么「新增」也算高危**：新增一条模型本身不改行为，"
                        + "但它是「启用」的必经前置 —— 而启用会改变全站的检索与生成行为。"
                        + "把新增记成 NORMAL 会让「谁在什么时候把这条配置放进来」查不到，"
                        + "而启用那条日志只说明「谁按了开关」");
        register(AuditModule.SYSTEM, "编辑模型配置", Category.MODEL_CONFIG_CHANGE, RiskLevel.HIGH,
                "同上。编辑能改的东西里，Base URL 与模型名会**直接改变系统的对外行为**"
                        + "（把请求打到别的供应商去了），而 temperature 之类改的是生成风格。"
                        + "**不细分档位**：编辑是一个动作，按字段分档会让审计页出现"
                        + "「同一个人点了同一次保存，进了两条不同等级的日志」");
        register(AuditModule.SYSTEM, "删除模型配置", Category.MODEL_CONFIG_CHANGE, RiskLevel.HIGH,
                "同上，且与 §4.3③ 有一条**交叉**：删除配置删的不是内容（不是文档/知识库），"
                        + "故按④归类而不是③。⚠️ 当前启用的那条**删不掉**（服务层拒绝），"
                        + "所以这条日志记的都是「删掉了一条没在用的」");
        register(AuditModule.SYSTEM, "启用模型配置", Category.MODEL_CONFIG_CHANGE, RiskLevel.HIGH,
                "§4.3④原话「**含启用切换**」。⚠️ 本批把「启用」与「切换重排/VL」**收敛成同一个动作名** —— "
                        + "它们走的是同一个端点、同一段代码、同一个后果（系统级行为变更）。"
                        + "`PLANNED` 里原有的「切换重排模型」因此**已并入本条**并被移除："
                        + "留一条永远不会兑现的预告，下一个人会以为「重排切换还没做」");

        // ---------- 系统 · 功能开关（2026-09-23 · S9-M12-C 的 T-C03 落地） ----------
        register(AuditModule.SYSTEM, "开关图谱旁路", Category.SYSTEM_BEHAVIOR_CHANGE, RiskLevel.HIGH,
                "第 7 类新增，理由见 `Category#SYSTEM_BEHAVIOR_CHANGE` 的 javadoc。"
                        + "**为什么必须记且必须 HIGH**：这一个二值开关决定**所有用户**的问答里"
                        + "图谱支路参不参与融合 —— 按下去之后检索行为全站改变，而"
                        + "**没有任何一条业务数据被改**，于是「没记日志」这件事除了审计之外"
                        + "没有任何其他地方看得出来。它是 §4.3「界限必须写死，否则标记会失去意义」"
                        + "要防的那类动作：后果大、痕迹轻");
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
        // 🔵 **本表当前为空** —— 这不是"忘了登记"，是事实：`05:414` 与 `05:367` 那两张动作表里
        //    可落地的条目**已经全部交付**。历次移入记录（留在这里，供追溯"什么时候还有预告"）：
        //    · 「下载文档」「批量下载」→ `S7-M06-E`（2026-09-21，`DownloadService`）；
        //    · 「删除文档」→ `S7-M06-F`（2026-09-22，`DocLifecycleService`）——**本表最后一条**；
        //    · 「启用模型配置」「切换重排模型」→ `S6-M09-A`（2026-09-18，后者**并入**前者）。
        // ⚠️ 表空之后 `AuditChangeContractTest` 的反向断言（策略 → 注解）暂时没有输入 ——
        //    那不代表它坏了，只代表"没有待落地的预告项"。**"表空"与"解析失败"要分得清**：
        //    前者是正常状态，后者会让断言变成空断言（本仓那条"闸的输入为空"的形态）。
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
