package com.knowledge.ai.core.storage;

/**
 * 单个存储的连通性自检结果。
 *
 * <p>{@code detail} 里**只放定位问题需要的信息**（库名 / 集合名 / 版本 / bucket），
 * 不放连接串与账号密码 —— 这个对象会经 {@code GET /api/health/storage} 直接返回，
 * 也会进启动日志（**I-15**：凭据永不落日志）。
 *
 * @param store  存储展示名（MySQL / Redis / ...）
 * @param role   该存储承载的职责，便于看日志的人立刻判断影响面
 * @param up     是否可用
 * @param costMs 探测耗时（毫秒），超时类问题靠它暴露
 * @param detail 正常时为版本/集合/维度等摘要；失败时为失败原因
 */
public record StorageStatus(String store, String role, boolean up, long costMs, String detail) {

    public static StorageStatus up(StorageKind kind, long costMs, String detail) {
        return new StorageStatus(kind.getDisplayName(), kind.getRole(), true, costMs, detail);
    }

    public static StorageStatus down(StorageKind kind, long costMs, String detail) {
        return new StorageStatus(kind.getDisplayName(), kind.getRole(), false, costMs, detail);
    }
}
