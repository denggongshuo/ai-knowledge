package com.knowledge.ai.core.storage;

import java.util.List;

/**
 * 五存储自检汇总（{@code GET /api/health/storage} 的响应体）。
 *
 * @param allUp   是否五个全部连通
 * @param upCount 连通数
 * @param total   总数（固定 5，写出来是为了日志/页面不用数数组长度）
 * @param items   逐项明细
 */
public record StorageOverview(boolean allUp, int upCount, int total, List<StorageStatus> items) {

    public static StorageOverview of(List<StorageStatus> items) {
        int upCount = (int) items.stream().filter(StorageStatus::up).count();
        return new StorageOverview(upCount == items.size(), upCount, items.size(), items);
    }
}
