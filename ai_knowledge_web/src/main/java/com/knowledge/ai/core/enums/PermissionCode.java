package com.knowledge.ai.core.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 原子权限码（**38 个**，后端鉴权的唯一依据 · T-012）。
 *
 * <p>这份枚举是权限点清单的**唯一事实源**：前端 {@code PERMISSION_TREE}、
 * 数据库 {@code sys_permission} 种子数据、后端 {@code @RequiresPerm} 的取值
 * 都必须与它一致。改动前先读《05-需求-权限与审计》§0.3。
 *
 * <p><b>编码约定</b>：{@code 域:动作[:变体]}，2~3 段。
 * （早期文档写的"三段式 `模块:对象:动作`、例 `kb:doc:download` 已废弃 ——
 * 那种写法会让"下载文档"变成 `kb:doc:download`，与"知识库域"混淆。
 * 现在的口径是**按动作归属的域**命名：下载文档属于文档域，故为 {@code doc:download}。）
 *
 * <p><b>与「功能矩阵项」的关系</b>：矩阵的 12 项是**运营视图**（{@code MATRIX_ITEMS}），
 * 由这些原子码聚合展示，**矩阵 key 不是权限码**，后端不做校验。
 *
 * <p><b>关于 {@link #DOC_RECYCLE}</b>：回收站的「还原 / 彻底删除」是**不可逆**动作
 * （30 天保留期后系统也会自动清理），必须独立授权，故单列一个权限点，
 * 且默认**只授予超级管理员**（知识库管理员可用 {@link #DOC_DELETE} 把文档删进回收站，
 * 但处置回收站留给超管）。
 */
@Getter
public enum PermissionCode {

    // ==================== 知识问答（7） ====================

    QA_ASK("qa:ask", "发起问答", "知识问答"),
    QA_ASK_STREAM("qa:ask:stream", "流式问答", "知识问答"),
    QA_HISTORY_VIEW("qa:history:view", "查看历史会话", "知识问答"),
    QA_HISTORY_SEARCH("qa:history:search", "搜索历史会话", "知识问答"),
    QA_HISTORY_DELETE("qa:history:delete", "删除会话记录", "知识问答"),
    QA_SESSION_EXPORT("qa:session:export", "导出会话记录", "知识问答"),
    QA_FEEDBACK("qa:feedback", "处理问答反馈", "知识问答"),

    // ==================== 知识库管理（7） ====================

    KB_VIEW("kb:view", "查看知识库列表", "知识库管理"),
    KB_LIST("kb:list", "查看知识库详情", "知识库管理"),
    KB_CREATE("kb:create", "创建知识库", "知识库管理"),
    KB_UPDATE("kb:update", "编辑知识库", "知识库管理"),
    KB_CONFIG("kb:config", "配置检索参数", "知识库管理"),
    KB_DELETE("kb:delete", "删除知识库", "知识库管理"),
    KB_GRANT("kb:grant", "调整知识库授权", "知识库管理"),

    // ==================== 文档（9） ====================

    DOC_UPLOAD("doc:upload", "上传文档", "文档"),
    DOC_PREVIEW("doc:preview", "预览文档", "文档"),
    DOC_DOWNLOAD("doc:download", "下载文档", "文档"),
    DOC_DETAIL("doc:detail", "查看文档详情", "文档"),
    DOC_DELETE("doc:delete", "删除文档", "文档"),
    DOC_REINDEX("doc:reindex", "重建索引", "文档"),
    DOC_VERSION("doc:version", "版本管理", "文档"),
    /** 回收站管理（还原 / 彻底删除）。不可逆动作，默认仅超级管理员。 */
    DOC_RECYCLE("doc:recycle", "回收站管理", "文档"),
    /**
     * 修改文档属性（密级 / 所属知识库）—— 需求 `06` §6.7 的属性面板。
     *
     * <p>⚠️ **需求 `05` §5 的权限矩阵里没有这个点**（文档域只列了「上传 / 预览下载 /
     * 删除重建索引」）⇒ 本码是**推出来的**，依据是既有码的射程：与
     * {@link #DOC_REINDEX} 同族且更轻（它不改内容、不重算向量）⇒ 同样给 `KB_ADMIN`。
     * 已登记为待需求方确认的偏差（`plan/2026-09-20-S7-M06-B.md` §9）。
     *
     * <p>🔴 它**只表达"能改属性"这一层**；"能不能改**这一篇**"由服务层按库判 ——
     * 移动知识库要**同时**对源库（`MANAGE`）与目标库（{@link #DOC_UPLOAD}）有权限。
     * 码是角色级的、授权是逐库的，两者不能互相顶替（与 {@link #DOC_DELETE} 同一条分工）。
     */
    DOC_UPDATE("doc:update", "修改文档属性", "文档"),

    // ==================== 用户（6） ====================

    USER_LIST("user:list", "查看用户", "系统管理"),
    USER_CREATE("user:create", "新建用户", "系统管理"),
    USER_UPDATE("user:update", "编辑用户", "系统管理"),
    USER_DELETE("user:delete", "删除用户", "系统管理"),
    USER_RESET_PWD("user:reset-pwd", "重置密码", "系统管理"),
    USER_IMPORT("user:import", "批量导入", "系统管理"),

    // ==================== 角色（4） ====================

    ROLE_LIST("role:list", "查看角色", "系统管理"),
    ROLE_CREATE("role:create", "新建角色", "系统管理"),
    ROLE_UPDATE("role:update", "编辑角色", "系统管理"),
    ROLE_DELETE("role:delete", "删除角色", "系统管理"),

    // ==================== 授权（2） ====================

    GRANT_MATRIX("grant:matrix", "角色权限矩阵", "系统管理"),
    GRANT_USER_ROLE("grant:user-role", "用户角色分配", "系统管理"),

    // ==================== 审计（2） ====================

    AUDIT_VIEW("audit:view", "查看审计日志", "系统管理"),
    AUDIT_EXPORT("audit:export", "导出审计日志", "系统管理"),

    // ==================== 系统（2） ====================

    SYSTEM_MODEL("system:model", "模型配置", "系统管理"),
    /** 知识库额外授权用户：对单个用户做逐库授权，凌驾于角色授权之上。 */
    KB_GRANT_EXTRA("kb:grant-extra", "知识库额外授权用户", "系统管理"),
    ;

    /** 超级管理员角色的通配符：拥有全部权限点。 */
    public static final String WILDCARD = "*";

    /** 全部权限码（不含通配符），与前端 {@code ALL_PERMISSION_CODES} 顺序一致。 */
    public static List<String> allCodes() {
        return Arrays.stream(values()).map(PermissionCode::getCode).toList();
    }

    /** 权限码 -> 中文名。 */
    public static Map<String, String> labels() {
        return Arrays.stream(values())
                .collect(Collectors.toUnmodifiableMap(PermissionCode::getCode, PermissionCode::getLabel));
    }

    private final String code;

    private final String label;

    /** 权限树分组名，与前端 {@code PERMISSION_TREE} 的 label 一致。 */
    private final String group;

    PermissionCode(String code, String label, String group) {
        this.code = code;
        this.label = label;
        this.group = group;
    }

    /** 按 code 字符串反查，找不到返回 {@code null}（不抛异常，便于审计宽松匹配）。 */
    public static PermissionCode fromCode(String code) {
        for (PermissionCode value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }

    public boolean isKnown(String code) {
        return fromCode(code) != null;
    }
}
