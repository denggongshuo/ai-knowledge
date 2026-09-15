package com.knowledge.ai.service;

import com.knowledge.ai.core.async.AsyncTaskService;
import com.knowledge.ai.core.async.AsyncTaskStatus;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.dto.audit.AuditDtos;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 审计日志导出（T-304）。
 *
 * <h3>为什么走异步任务</h3>
 * <p>导出可能上万行、要连 MinIO。同步做的话请求线程会被占住几十秒，
 * 前端只能转圈等 —— 而 AsyncTaskService 就是为这个场景铺的
 * （{@code AsyncTaskInfo} 的 javadoc 里明写 {@code audit-export} 是它的复用场景之一）。
 * 接口立刻返回 {@code {taskId, status:'QUEUED'}}，与 mock 同形，**前端零改动**。
 *
 * <h3>为什么任务类型是 {@code audit-export}、对象前缀是 {@code audit-export/}</h3>
 * <p>与 {@code AsyncTaskInfo} javadoc 里登记的取值一致；{@code AvatarStorage}
 * 已有"复用同一个 bucket、只把对象名收进前缀"的先例，这里照做
 * （多一个桶就多一处启动期依赖与一份策略配置，而导出文件与文档在存储层面没有差别）。
 *
 * <h3>交付通道是断的（刻意）</h3>
 * <p>任务会真跑、CSV 会真落盘、预签名 URL 会真生成并存进任务结果。
 * 但"导出完成后通知用户"那一跳属于**通知中心（S6）**，
 * 任务轮询接口 {@code GET /doc/tasks/{taskId}} 属 **S4**。
 * 本批不假装它通了 —— 见 C 批计划 §3.3 与 §9-3。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditExportService {

    /** 任务类型标识，与 {@code AsyncTaskInfo#getType()} 的口径一致。 */
    public static final String TASK_TYPE = "audit-export";

    /** MinIO 对象名前缀。 */
    private static final String PREFIX = "audit-export/";

    /** 预签名 URL 有效期（小时）。导出文件是敏感物，比头像（7 天）短得多。 */
    private static final int URL_TTL_HOURS = 24;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final AuditQueryService auditQueryService;

    private final AsyncTaskService asyncTaskService;

    private final MinioClient minioClient;

    @Value("${minio.bucket:rag-documents}")
    private String bucket;

    /**
     * 提交导出任务。
     *
     * <p><b>筛选项随任务一起带走</b>：它们必须在**提交时刻**从请求线程捕获，
     * 而不是在任务体里再读一次 {@code LoginUserContext} —— 工作线程没有登录态
     * （{@code AuditContext} 的 javadoc 记录的"不跨线程"是同一个根因）。
     * 这也是本方法把 {@code LoginUser} 显式传进去的原因。
     *
     * <p><b>幂等键</b>取 {@code 操作人 + 秒级时间戳}：同一人连点两下"导出"是常见操作，
     * 复用同一条任务比生成两份几 MB 的文件合理。但**不加到"永久幂等"**——
     * 同一人隔一分钟再导一次应该是一份新文件（数据变了）。
     */
    public AuditDtos.ExportAccepted submit(String startTime, String endTime, String module,
                                           String riskLevel, String result, String operator,
                                           String keyword) {
        LoginUser user = LoginUserContext.require();
        String idempotencyKey = "audit-export:" + user.getUserId() + ":"
                + LocalDateTime.now().format(STAMP);

        String taskId = asyncTaskService.submit(TASK_TYPE, idempotencyKey, 0, context -> {
            context.stage("查询日志");
            context.percent(20);
            String csv = auditQueryService.exportCsv(startTime, endTime, module, riskLevel,
                    result, operator, keyword);

            context.stage("写入文件");
            context.percent(70);
            String objectKey = store(user.getUserId(), csv);

            Map<String, Object> payload = new HashMap<>();
            payload.put("objectKey", objectKey);
            payload.put("bytes", csv.getBytes(StandardCharsets.UTF_8).length);
            payload.put("rows", Math.max(0, csv.split("\r\n", -1).length - 2)); // 去表头与末尾空行
            payload.put("fileUrl", presignedUrl(objectKey));
            payload.put("expiresInHours", URL_TTL_HOURS);
            context.complete(payload);

            log.info("审计导出完成：taskId={} userId={} object={} bytes={}",
                    context.taskId(), user.getUserId(), objectKey, csv.length());
        });

        // status 给的是**调度层的真值** PENDING，不是 mock 那个字面量 'QUEUED'。
        // 理由：异步任务的状态词表只能有一套。S4 的 GET /doc/tasks/{taskId}
        // 会返回 AsyncTaskStatus 的枚举名，若本接口先发一个 'QUEUED'，
        // 同一个 taskId 在两次请求里会有两种状态词 —— 这正是本仓
        // `sys_kb.status` 词表分叉（《10》§14.2-2 登记在案）踩过的坑。
        // 前端当前不解析这个字段（只弹提示），故"前端零改动"仍然成立。
        return new AuditDtos.ExportAccepted(taskId, AsyncTaskStatus.PENDING.name(),
                "导出任务已提交，完成后将在通知中心提醒");
    }

    // ==================== 私有 ====================

    /** 把 CSV 文本写进 MinIO，返回对象名。 */
    private String store(Long userId, String csv) {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        String objectKey = PREFIX + userId + "/" + LocalDateTime.now().format(STAMP) + "-audit.csv";
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, bytes.length, -1)
                    .contentType("text/csv; charset=utf-8")
                    .build());
        } catch (Exception ex) {
            // 导出任务失败要**明确失败**（任务状态置 FAILED + errorMsg），
            // 不能返回一个"成功但没文件"的结果 —— 用户会以为导出了。
            log.error("审计导出写盘失败：userId={} object={}", userId, objectKey, ex);
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "导出文件写入失败，请稍后重试");
        }
        return objectKey;
    }

    /**
     * 预签名读取 URL。
     *
     * <p>失败时**返回 null 而不是抛错**：文件已经落盘了，任务算成功；
     * URL 只是"怎么把它取走"的问题，不该让整个任务变红
     * （与 {@code AvatarStorage#presignedUrl} 同一取舍）。
     */
    private String presignedUrl(String objectKey) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(URL_TTL_HOURS, TimeUnit.HOURS)
                    .build());
        } catch (Exception ex) {
            log.warn("审计导出预签名 URL 生成失败（文件已落盘）：object={} err={}",
                    objectKey, ex.getMessage());
            return null;
        }
    }
}
