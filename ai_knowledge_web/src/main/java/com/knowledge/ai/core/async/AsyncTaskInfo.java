package com.knowledge.ai.core.async;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 异步任务进度快照（对外返回体）。
 *
 * <p>字段与《10-接口约定汇总》§14 的
 * {@code GET /doc/tasks/{taskId}} → {@code {taskId, stage, percent, chunkDone, chunkTotal, errorMsg}}
 * 逐字对齐；额外补了 {@code status} / {@code finishedAt} / {@code result}，
 * 供下载打包、审计导出等其它异步场景复用同一个外壳。
 *
 * @param <T> 任务成功后的业务结果（如 {@code {fileUrl, expiresAt}}）。失败时为 null。
 */
@Data
public class AsyncTaskInfo<T> {

    private String taskId;

    /** 任务类型，如 {@code doc-parse} / {@code doc-batch-download} / {@code audit-export}。 */
    private String type;

    /** 调度层状态。 */
    private AsyncTaskStatus status;

    /** 业务阶段文案，如"解析中""索引中""打包中"。前端直接展示。 */
    private String stage;

    /** 完成百分比 0~100。 */
    private int percent;

    /** 已完成的分片数（解析/索引类任务）。 */
    private int chunkDone;

    /** 总分片数。未知时为 0，前端展示"处理中"而不是 0%。 */
    private int chunkTotal;

    /** 失败原因（仅 FAILED 时有值）。 */
    private String errorMsg;

    /** 业务结果（仅 DONE 时有值）。 */
    private T result;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;

    /** 提交任务的 traceId，便于把异步日志与触发它的那次请求串起来。 */
    private String traceId;

    /** 耗时毫秒；未结束时为 null。 */
    public Long costMs() {
        if (finishedAt == null || createdAt == null) {
            return null;
        }
        return java.time.Duration.between(createdAt, finishedAt).toMillis();
    }
}
