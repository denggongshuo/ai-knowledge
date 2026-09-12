package com.knowledge.ai.core.async;

import lombok.Getter;

/**
 * 异步任务状态（通用外壳）。
 *
 * <p>注意与业务侧的状态**不是一回事**：
 * <ul>
 *   <li>上传任务对外展示的是 {@code UploadStatus}</li>
 *       （{@code UPLOADING/PARSING/DONE/FAILED}），</li>
 *   <li>下载打包展示的是 {@code DownloadStatus}</li>
 *       （{@code PACKING/DONE/EXPIRED/FAILED}），</li>
 * </ul>
 * 但它们在**调度层面**都只关心"跑着 / 成功 / 失败"。
 * 本枚举是调度层的统一状态，业务状态放在 {@link AsyncTaskInfo#getStage()} 里表达。
 */
@Getter
public enum AsyncTaskStatus {

    /** 已入队，尚未开始。 */
    PENDING("排队中"),
    /** 执行中。 */
    RUNNING("执行中"),
    DONE("已完成"),
    FAILED("失败"),
    ;

    private final String label;

    AsyncTaskStatus(String label) {
        this.label = label;
    }

    /** 是否终态（前端可以停止轮询）。 */
    public boolean terminal() {
        return this == DONE || this == FAILED;
    }
}
