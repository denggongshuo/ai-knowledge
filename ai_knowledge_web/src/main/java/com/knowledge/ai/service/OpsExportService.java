package com.knowledge.ai.service;

import com.knowledge.ai.core.async.AsyncTaskService;
import com.knowledge.ai.core.async.AsyncTaskStatus;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.ops.MissStatus;
import com.knowledge.ai.core.ops.QaMissRepository;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.core.storage.PresignTtl;
import com.knowledge.ai.core.text.Csv;
import com.knowledge.ai.dto.ops.OpsDtos;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 运营报表导出（`T-A02` · 需求 `08` §1.7 「导出报表 | 字段与表格列保持一致，用于周会汇报」）。
 *
 * <h3>结构照 {@link AuditExportService}，但两处是**新的**（都要登记）</h3>
 * <ol>
 *   <li>**任务类型 {@code ops-export}** —— 要同时改 {@code NoticePublisher} 的映射表
 *       （那是通知内容的**唯一**映射处，`T-B03` 的字面要求）；</li>
 *   <li>🔴 **MinIO 前缀 {@code ops-export/}** —— 一次**持久化产物变更**：
 *       必须加进 {@code ProbeCleanup.MINIO_PREFIXES}，否则覆盖率闸与残留自检**都够不着它**
 *       （`#202`/`#203` 的判例：新增一个前缀只写 javadoc ⇒ 一轮跑批留 **180 个孤儿**）。</li>
 * </ol>
 *
 * <h3>为什么导出的是**未命中清单**，而不是把三块拼在一起</h3>
 * <p>需求 §1.7 只说了「字段与表格列保持一致」—— 页面上唯一**带表格**的就是未命中清单
 * （高频榜与点踩归因是图表，它们的"列"不是表格列）。三块拼一份文件会让
 * "字段与表格列保持一致"这句话失去指代对象，也会让周会上的人分不清哪张表是哪张。
 * <p>⚠️ 另外两块的导出**不是不做，是没被要求**：需求 §1.7 只点名了一行。
 * 要加的话是各加一个端点，而不是把它们塞进这一份（一张表两份口径是另一种坏）。
 *
 * <h3>交付通道是通的（与 `AuditExportService` 的差别）</h3>
 * <p>本批时通知中心（`S6-M11`）已经在了，所以 `NoticePublisher` 的映射表加一行就够
 * —— 用户在通知里拿到的是一条**带真下载地址**的动作（`G6-12` 的判据）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpsExportService {

    /** 任务类型标识，与 {@code NoticePublisher} 的映射表**逐字一致**。 */
    public static final String TASK_TYPE = "ops-export";

    /**
     * MinIO 对象名前缀。
     *
     * <p>⚠️ 与 {@code ProbeCleanup.OPS_EXPORT_PREFIX} 是**同一个字面量**，两处必须一起改
     * —— 那边的用例会断言"这个前缀有没有被表态"，而它认的是那个常量。
     * 刻意**不复用** {@code audit-export/}：对象名里的前缀就是**归属与用途**，
     * 让运营报表躺在叫"审计导出"的前缀下，等于给下一个人埋一个"这是审计件"的误读
     * （`T-O11` 的整套判据都建立在「路径即归属」上）。
     */
    public static final String PREFIX = "ops-export/";

    /** 预签名读取有效期（小时）—— 取值与理由在 {@link PresignTtl#OPS_EXPORT_HOURS}。 */
    private static final int URL_TTL_HOURS = PresignTtl.OPS_EXPORT_HOURS;

    /**
     * 单次导出的行数上限。
     *
     * <p>与审计导出（`AuditQueryService#EXPORT_MAX_ROWS`）取同一个数量级，处置也相同：
     * **超限明确拒绝并报出实际条数**，不静默截断。
     */
    private static final int EXPORT_MAX_ROWS = 50_000;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 表格列 → CSV 列（见 {@link #exportCsv}）。 */
    private static final String[] HEADER = {
            "用户问题", "涉及知识库", "提问次数", "提问人", "最高相似度", "最近提问", "状态", "忽略人",
    };

    private final QaMissRepository qaMissRepository;

    /** 只为复用「`kb_ids` 逗号串 → 显示名」那一段规则（只许有一份）。 */
    private final QaMissService qaMissService;

    private final AsyncTaskService asyncTaskService;

    private final MinioClient minioClient;

    @Value("${minio.bucket:rag-documents}")
    private String bucket;

    /**
     * 提交导出任务。
     *
     * <p><b>筛选项在提交时刻从请求线程捕获</b>（与审计导出同因：工作线程没有登录态，
     * 也没有请求参数）。这也是本方法把解析结果先算好的原因 —— 放进任务体里再解析的话，
     * 参数错了会变成"任务失败"，而用户看到的是"导出失败"而不是"筛选条件有问题"。
     */
    public OpsDtos.ExportAccepted submit(String status, String kbId, String keyword) {
        LoginUser user = LoginUserContext.require();
        MissStatus parsedStatus = MissStatus.parseFilter(status);
        Long kb = QaMissService.parseKbId(kbId);
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();

        String idempotencyKey = TASK_TYPE + ":" + user.getUserId() + ":"
                + LocalDateTime.now().format(STAMP);

        String taskId = asyncTaskService.submit(TASK_TYPE,
                AsyncTaskService.TaskSubject.of(null, null, user.getUserId()), idempotencyKey, 0, context -> {
            context.stage("查询未命中清单");
            context.percent(30);
            List<QaMissRepository.Row> rows =
                    qaMissRepository.findAll(parsedStatus, kb, kw, EXPORT_MAX_ROWS);
            if (rows.size() > EXPORT_MAX_ROWS) {
                // ⚠️ **不静默截断**：一份少了行的报表看起来是完整的
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "本次导出超过 " + EXPORT_MAX_ROWS + " 条，请先按状态 / 知识库 / 关键词收窄范围");
            }
            String csv = exportCsv(rows);

            context.stage("写入文件");
            context.percent(70);
            String objectKey = store(user.getUserId(), csv);

            Map<String, Object> payload = new HashMap<>();
            payload.put("objectKey", objectKey);
            payload.put("bytes", csv.getBytes(StandardCharsets.UTF_8).length);
            payload.put("rows", rows.size());
            payload.put("fileUrl", presignedUrl(objectKey));
            payload.put("expiresInHours", URL_TTL_HOURS);
            context.complete(payload);

            log.info("运营报表导出完成：taskId={} userId={} object={} rows={}",
                    context.taskId(), user.getUserId(), objectKey, rows.size());
        });

        return new OpsDtos.ExportAccepted(taskId, AsyncTaskStatus.PENDING.name(),
                "导出任务已提交，完成后将在通知中心提醒");
    }

    // ==================== 私有 ====================

    /**
     * 未命中清单 → CSV。
     *
     * <h4>列与页面表格的对应（需求 §1.7「字段与表格列保持一致」）</h4>
     * <p>页面表格的列是：用户问题（原始问法）· 提问次数 · 提问人 · 最高相似度 · 最近提问 · 状态。
     * 两处**刻意的不完全一致**，都写在这里免得下次被当成 bug：
     * <ul>
     *   <li>页面上「涉及知识库」与「忽略人」是**单元格内的第二行**（`kbName` / `by xxx`），
     *       CSV 的单元格是平的 ⇒ 它们各占一列。信息是同一份，只是形状变了；</li>
     *   <li>「最高相似度」页面渲染成 `补录前 → 补录后`（没重放过只显示补录前）⇒
     *       CSV **保留同一个渲染**，不拆成两个数字列 —— 拆了的话，汇报的人拿到的表
     *       与他在页面上看到的那一列对不上，而那句话的落点正是"用于周会汇报"。</li>
     * </ul>
     */
    private String exportCsv(List<QaMissRepository.Row> rows) {
        Map<Long, String> kbNames = qaMissService.kbNamesOf(rows);
        StringBuilder csv = new StringBuilder();
        csv.append(Csv.BOM);
        csv.append(Csv.row(HEADER));
        for (QaMissRepository.Row row : rows) {
            csv.append(Csv.row(
                    row.question(),
                    QaMissService.namesOf(row.kbIds(), kbNames),
                    String.valueOf(row.askCount()),
                    row.askerName() == null ? "" : row.askerName(),
                    scoreText(row.maxTopScore(), row.scoreAfter()),
                    row.lastAskAt() == null ? "" : QaMissService.TIME.format(row.lastAskAt()),
                    row.status(),
                    row.ignoredByName() == null ? "" : row.ignoredByName()));
        }
        return csv.toString();
    }

    /** 与页面同一个渲染：有补录后分时给 `前 → 后`，否则只给前分（都没有时给 `—`）。 */
    private static String scoreText(Double before, Double after) {
        if (after == null) {
            return before == null ? "—" : String.valueOf(before);
        }
        return (before == null ? "—" : String.valueOf(before)) + " → " + after;
    }

    /** 把 CSV 文本写进 MinIO，返回对象名（`{@link #PREFIX}{userId}/{时间戳}-qa-ops.csv`）。 */
    private String store(Long userId, String csv) {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        String objectKey = PREFIX + userId + "/" + LocalDateTime.now().format(STAMP) + "-qa-ops.csv";
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, bytes.length, -1)
                    .contentType("text/csv; charset=utf-8")
                    .build());
        } catch (Exception ex) {
            // 与审计导出同一条：**明确失败**，不返回"成功但没文件"（用户会以为导出了）
            log.error("运营报表写盘失败：userId={} object={}", userId, objectKey, ex);
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "导出文件写入失败，请稍后重试");
        }
        return objectKey;
    }

    /**
     * 预签名读取 URL；**失败返回 `null` 不抛错** —— 文件已经落盘了，任务算成功，
     * URL 只是"怎么取走"的问题，不该让整个任务变红（与 `AuditExportService`、`AvatarStorage` 同一取舍）。
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
            log.warn("运营报表预签名 URL 生成失败（文件已落盘）：object={} err={}",
                    objectKey, ex.getMessage());
            return null;
        }
    }
}
