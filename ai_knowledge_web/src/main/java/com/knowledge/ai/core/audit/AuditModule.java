package com.knowledge.ai.core.audit;

/**
 * 审计日志的模块名。
 *
 * <p>约束为常量而不是字面量，是因为模块名会**作为筛选条件与索引列**，
 * 拼错一个字母就会让该模块的日志在列表页"消失"。
 *
 * <p>与前端 {@code AuditLogView.vue} 的筛选下拉、以及
 * 《05-需求-权限与审计》§4 的四类高风险动作对齐。
 *
 * <p><b>取值必须是前端 MODULES 的子集</b>：前端列表页的下拉是
 * {@code ['认证','用户','授权','知识库','文档','问答','系统','审计']}（{@code AuditLogView.vue:60}，
 * 2026-09-15 的 S3-C 批补上「审计」）。放一个不在其中的模块名，日志**写得进去但筛不出来**
 * —— 从运营视角看等于丢了。
 * 所以「角色」相关动作归 {@link #GRANT}（矩阵/授权），不单设 {@code ROLE}；
 * 「部门」相关动作归 {@link #SYSTEM}，不单设 {@code DEPT}（前端路由也把部门归在「系统管理」组）。
 */
public final class AuditModule {

    public static final String AUTH = "认证";
    public static final String USER = "用户";
    public static final String GRANT = "授权";
    public static final String KB = "知识库";
    public static final String DOC = "文档";
    public static final String QA = "问答";
    public static final String SYSTEM = "系统";

    /**
     * 审计日志自身的操作（导出等）——审计自审。
     *
     * <p>⚠️ 该值**曾经不在前端 MODULES 里**（写得进、筛不出）。{@code T-304} 落地时
     * 前端已把它加进下拉（{@code AuditLogView.vue:60}），所以现在可以正常筛选。
     */
    public static final String AUDIT = "审计";

    private AuditModule() {
    }
}
