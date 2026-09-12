package com.knowledge.ai.core.enums;

import lombok.Getter;

/**
 * 批量下载任务的打包状态。取值与前端 {@code DownloadStatus} 逐字一致。
 *
 * <pre>
 *   PACKING  打包中（前端轮询 /doc/download-tasks/{taskId}）
 *   DONE     已就绪，fileUrl 可下载
 *   EXPIRED  预签名 URL 已过期（默认 24h）
 *   FAILED   打包失败，errorMsg 有原因
 * </pre>
 */
@Getter
public enum DownloadStatus {

    PACKING("打包中"),
    DONE("已就绪"),
    EXPIRED("已过期"),
    FAILED("打包失败"),
    ;

    private final String label;

    DownloadStatus(String label) {
        this.label = label;
    }

    /** 是否终态（前端停止轮询）。 */
    public boolean terminal() {
        return this != PACKING;
    }
}
