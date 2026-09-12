package com.knowledge.ai.core.audit;

import lombok.Getter;

/**
 * 审计日志的风险等级。取值与前端 {@code AuditLog['riskLevel']} 逐字一致（只有两档）。
 *
 * <p><b>高危动作的定义</b>（《05-需求-权限与审计》§4，四类）：
 * <ol>
 *   <li>权限扩大（角色权限矩阵升级、用户新增角色、知识库授权级别提高）</li>
 *   <li>数据删除（删除用户 / 角色 / 知识库 / 文档、回收站彻底删除）</li>
 *   <li>系统配置变更（模型配置、阈值、密钥）</li>
 *   <li>批量导出（审计日志导出、用户导出、批量下载打包）</li>
 * </ol>
 *
 * <p>⚠️ <b>单文件下载不带高危标签</b> —— 那是日常动作，标成高危会让告警失去意义。
 * 但 {@code doc:download} 的**批量**形态（{@code /doc/batch-download}）属于第 4 类。
 */
@Getter
public enum RiskLevel {

    NORMAL("普通"),
    HIGH("高危"),
    ;

    private final String label;

    RiskLevel(String label) {
        this.label = label;
    }
}
