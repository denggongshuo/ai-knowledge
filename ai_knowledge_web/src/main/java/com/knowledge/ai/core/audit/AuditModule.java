package com.knowledge.ai.core.audit;

/**
 * 审计日志的模块名。
 *
 * <p>约束为常量而不是字面量，是因为模块名会**作为筛选条件与索引列**，
 * 拼错一个字母就会让该模块的日志在列表页"消失"。
 *
 * <p>与前端 {@code AuditLogView.vue} 的筛选下拉、以及
 * 《05-需求-权限与审计》§4 的四类高风险动作对齐。
 */
public final class AuditModule {

    public static final String AUTH = "认证";
    public static final String USER = "用户";
    public static final String ROLE = "角色";
    public static final String GRANT = "授权";
    public static final String KB = "知识库";
    public static final String DOC = "文档";
    public static final String QA = "问答";
    public static final String SYSTEM = "系统";

    /** 审计日志自身的操作（导出等）——审计自审。 */
    public static final String AUDIT = "审计";

    private AuditModule() {
    }
}
