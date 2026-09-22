package com.knowledge.ai.service;

import com.knowledge.ai.core.async.AsyncTaskService;
import com.knowledge.ai.core.async.AsyncTaskStatus;
import com.knowledge.ai.core.async.SysAsyncTaskRepository;
import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.model.PageQuery;
import com.knowledge.ai.core.model.PageResult;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.core.storage.DocStorage;
import com.knowledge.ai.core.storage.PresignTtl;
import com.knowledge.ai.core.text.Csv;
import com.knowledge.ai.dto.doc.DocDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 文档下载（`T-613` 预览地址 / `T-614` 单篇 + 打包 / `T-615` 任务与记录导出）。
 *
 * <h3>🔴 授权只有一处入口：{@link DocAccessService#requireVisibleDoc(long)}</h3>
 * <p>本类**不自己写"这篇文档我看不看得到"的 SQL**（不变量 `I-7`）。那是本仓吃过亏的地方：
 * 同一段可见性判定写两遍，多出来的一遍就是"越权可见"的那个漏。
 *
 * <h3>🔴 身份在**请求线程**捕获，工作线程只用捕获下来的值</h3>
 * <p>{@code LoginUserContext} 是 {@code ThreadLocal}，请求结束即失效（见 `AsyncContext`
 * 那一族的记录）。所以打包任务体里**绝不能**再调 {@code requireVisibleDoc}
 * —— 它内部要 {@code LoginUserContext.require()}，在工作线程里只会抛"未登录"。
 * <p>取而代之的是：**提交时**把每篇文档的坐标（id / kbId / 文件名 / 对象名 / 大小）
 * 逐个鉴权后快照下来，任务体只负责"把已经获准的那几个对象打进 zip"。
 * 这不是偷懒，是本仓 `O6` §2 写明的立场：<b>授权与留痕都发生在有身份的那一刻，
 * 工作线程只执行已被授权过的既定作业</b>。排队期间文档若被删，打包会以
 * "文件已不存在"明确失败，而不是悄悄给出一份少了几篇的 zip。
 *
 * <h3>🔴 打包为什么先落本地临时文件，而不是边打边推给对象存储</h3>
 * <p>zip 的条目要先写完才知道整个文件多大，而 MinIO 的 PUT 要一个确定的长度
 * （{@code PutObjectArgs#stream(stream, size, partSize)} 不认"边写边知道"）。
 * 落一个本地临时文件是**唯一不需要引额外依赖**的做法（本批 §5 的决定：不引 commons-compress）。
 * 代价是多一次本地磁盘 IO，收益是内存峰值始终是"一篇文档"级别 —— 见 {@link #packBatch}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadService {

    /**
     * 打包下载的任务类型。
     *
     * <p>⚠️ 与 {@code NoticePublisher} 里的 {@code TYPE_BATCH_DOWNLOAD} **逐字一致**
     * —— 那两处是同一个协议的两端（一边提交、一边据此发通知），改一处必须改另一处。
     */
    public static final String TASK_TYPE = "batch-download";

    /** 下载记录清单导出的任务类型。⚠️ 同样与 {@code NoticePublisher} 的 case 逐字一致。 */
    public static final String EXPORT_TASK_TYPE = "doc-export-list";

    /** 单次打包的文档数上限（门禁 `G7-8` · 需求 `06` §5.8「≤ 50 个文件」）。 */
    public static final int MAX_BATCH_DOCS = 50;

    /** 单次打包的**源文件聚合**大小上限（门禁 `G7-8`「≤ 2 GB」）。 */
    public static final long MAX_BATCH_BYTES = 2L * 1024 * 1024 * 1024;

    /** 下载任务列表一次最多返回多少条。 */
    private static final int TASK_LIST_LIMIT = 50;

    /**
     * 下载记录导出的一次性上限（见 {@code DownloadRecordService#listForExport} 的说明）。
     */
    private static final int MAX_EXPORT_ROWS = 10_000;

    /** 浏览器**直接打开**（预览）。 */
    private static final String DISPOSITION_INLINE = "inline";

    /** 浏览器**另存为**（下载）。 */
    private static final String DISPOSITION_ATTACHMENT = "attachment";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DocAccessService docAccessService;

    private final DocStorage docStorage;

    private final DownloadRecordService downloadRecordService;

    private final AsyncTaskService asyncTaskService;

    private final SysAsyncTaskRepository taskRepository;

    // ==================================================================
    // `T-613` · 预览地址 / 下载地址（`G7-7`）
    // ==================================================================

    /**
     * {@code GET /api/doc/{id}/preview-url} —— 预览用的预签名地址（{@code inline}）。
     *
     * <p>🔴 <b>不写下载记录</b>（计划 §3.1 步 4）：预览 ≠ 下载。把"点开看了一眼"
     * 写进下载历史，那份清单就不再回答它该回答的问题（我到底把哪些文件取走过）。
     * 审计侧也**不记** —— 预览不是数据导出，`AuditRiskPolicy` 里没有它的位置。
     *
     * <p>它与下载走**同一个对象**，只是让对象存储回一个不同的
     * {@code Content-Disposition}（见 {@code DocStorage#presignedGet}）。
     */
    public DocDtos.DownloadUrl previewUrl(long docId) {
        DocAccessService.DocRow doc = docAccessService.requireVisibleDoc(docId);
        String objectKey = requireStored(doc, "预览");
        String url = docStorage.presignedGet(objectKey,
                Duration.ofMinutes(PresignTtl.DOC_PREVIEW_MINUTES), DISPOSITION_INLINE);
        return urlOf(url, PresignTtl.DOC_PREVIEW_MINUTES);
    }

    /**
     * {@code GET /api/doc/download-url/{docId}} —— 单篇下载的预签名地址（{@code attachment}）。
     *
     * <p>🔴 <b>顺序是：鉴权 → 签名 → **写记录** → 返回</b>（`G7-9`）。
     * 反过来（先返回再写）会出现"地址已经给出去了、记录没写成功"——
     * 那次下载真的发生了，历史里却查不到。而现在这条路一旦写失败就整体失败，
     * 用户拿到的是明确的错，不会"以为下到了"。
     *
     * <p>审计（{@code 下载文档} · NORMAL）由 {@code @AuditLog} 切面在方法返回后写，
     * 独立事务（`I-12`）—— 所以记录写失败时审计仍会留下一条 {@code result=FAILED}，
     * 那是**对的**：失败了也要留痕，正是审计存在的理由。
     *
     * @param ip 来源 IP（{@code WebUtils#clientIp}）。⚠️ 可伪造，只用于展示与追溯
     */
    @AuditLog(module = AuditModule.DOC, action = "下载文档", risk = RiskLevel.NORMAL,
            target = "'文档#' + #docId")
    public DocDtos.DownloadUrl downloadUrl(long docId, String ip) {
        LoginUser user = LoginUserContext.require();
        DocAccessService.DocRow doc = docAccessService.requireVisibleDoc(docId);
        String objectKey = requireStored(doc, "下载");

        String url = docStorage.presignedGet(objectKey,
                Duration.ofMinutes(PresignTtl.DOC_DOWNLOAD_MINUTES), DISPOSITION_ATTACHMENT);
        // 同步点击的接口：地址拼不出来就是失败（不像异步产物展示可以降级成"暂不可用"）
        if (url == null || url.isBlank()) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "下载地址生成失败，请稍后重试");
        }

        downloadRecordService.record(docId, doc.kbId(), user.getUserId(),
                DownloadRecordService.TYPE_SINGLE, doc.sizeBytes(), ip);
        return urlOf(url, PresignTtl.DOC_DOWNLOAD_MINUTES);
    }

    // ==================================================================
    // `T-614` · 打包下载（`G7-8`）
    // ==================================================================

    /**
     * {@code POST /api/doc/batch-download} —— 受理打包（同步校验上限、异步打包）。
     *
     * <h3>🔴 授上是"全有或全无"</h3>
     * <p>列表里**任意一篇**不存在 / 已软删 / 不可见 ⇒ **整批拒绝**，
     * 不静默过滤掉那几篇再打包剩下的。理由：静默过滤会让用户拿到一个
     * "看着成功、里面少了几篇"的 zip —— 而**少的那几篇往往正是他真正要的那几篇**
     * （比如跨部门的那份材料），他会以为是自己选漏了。
     * <p>所以错误必须当场、具体：`不存在的给 10044，不可见的给 10022（HTTP 403）`
     * —— 那正是 {@code DocAccessService} 要区分这两种错的原因（见它的类注释）。
     *
     * <h3>上限为什么在受理时同步校验</h3>
     * <p>超限是**用户能当场改的**（少选几个 / 分批）。挪到异步里再失败，用户要等一个
     * 打包任务跑完才知道自己选多了 —— 那是把"立刻能纠正的输入错误"变成"一次白等"。
     */
    @AuditLog(module = AuditModule.DOC, action = "批量下载", risk = RiskLevel.HIGH,
            target = "'批量下载 ' + #docIds.size() + ' 篇'")
    public DocDtos.BatchDownloadAccepted batchDownload(List<Long> docIds, String ip) {
        LoginUser user = LoginUserContext.require();
        long ownerId = user.getUserId();

        List<Long> ids = requireBatchScope(docIds);

        // ---- 请求线程：逐个鉴权 + 快照（工作线程不许再碰 LoginUserContext）----
        List<PackItem> items = new ArrayList<>(ids.size());
        long totalBytes = 0L;
        for (Long docId : ids) {
            DocAccessService.DocRow doc = docAccessService.requireVisibleDoc(docId);
            String objectKey = requireStored(doc, "下载");
            items.add(new PackItem(docId, doc.kbId(), doc.fileName(), objectKey,
                    Math.max(doc.sizeBytes(), 0L)));
            totalBytes += Math.max(doc.sizeBytes(), 0L);
        }
        if (totalBytes > MAX_BATCH_BYTES) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "所选文档合计约 " + mb(totalBytes) + " MB，超过单次 2 GB 上限；"
                            + "请分批下载（例如先下前 25 篇，再下剩下的）");
        }

        // 幂等键含秒级时间戳：同一人连点两下"批量下载"是常见操作，复用同一条任务比
        // 打两份一模一样的 zip 合理。⚠️ 刻意**不含 docIds** —— 那会让"改了选中文档再点一次"
        // 变成一条新任务（正确），但也意味着同一秒内两次不同选择会拿到同一个 taskId
        // （可接受的边界：用户不会在 1 秒内改主意，而改了主意就会跨秒）
        String idempotencyKey = TASK_TYPE + ":" + ownerId + ":" + LocalDateTime.now().format(STAMP);
        List<PackItem> snapshot = List.copyOf(items);
        String snapshotIp = ip;

        // chunkTotal 填文档数：打包任务里"一个分片"就是"一篇文档"，
        // 于是 sys_async_task.chunk_total 持久化下来正好是 DownloadTaskView 要的 count
        // （任务结果 payload 不落库，这是重启后仍能答出"总共几篇"的唯一来源）
        String taskId = asyncTaskService.submit(TASK_TYPE,
                AsyncTaskService.TaskSubject.of(null, null, ownerId), idempotencyKey,
                snapshot.size(), context -> packBatch(context, ownerId, snapshot, snapshotIp));

        log.info("打包下载已受理：taskId={} userId={} docs={} bytes={}",
                taskId, ownerId, snapshot.size(), totalBytes);
        return new DocDtos.BatchDownloadAccepted(taskId, snapshot.size(), AsyncTaskStatus.PENDING.name());
    }

    // ==================================================================
    // `T-615` · 任务列表 / 任务详情
    // ==================================================================

    /**
     * {@code GET /api/doc/download-tasks} —— 我的打包任务（按创建时间倒序）。
     *
     * <p>🔴 归属过滤在 SQL 里（{@code owner_id}），不是取回之后在内存里筛
     * —— 后者一旦有人把 {@code limit} 调大或忘了那一步，漏的就是别人的任务列表。
     */
    public DocDtos.DownloadTaskList taskList() {
        LoginUser user = LoginUserContext.require();
        long ownerId = user.getUserId();
        List<SysAsyncTaskRepository.DownloadTaskRow> rows =
                taskRepository.listDownloadTasks(ownerId, TASK_TYPE, TASK_LIST_LIMIT);
        List<DocDtos.DownloadTaskView> list = new ArrayList<>(rows.size());
        for (SysAsyncTaskRepository.DownloadTaskRow row : rows) {
            list.add(toView(row, ownerId));
        }
        return new DocDtos.DownloadTaskList(list, taskRepository.countDownloadTasks(ownerId, TASK_TYPE));
    }

    /**
     * {@code GET /api/doc/download-tasks/{taskId}} —— 单条任务详情。
     *
     * <p>🔴 查不到 / 不是我的 / 不是下载任务 ⇒ 一律 {@code 10045}，**三种情形同一个错**。
     * 分开报（"这是别人的任务"）等于告诉试探者"这个 id 存在"—— 那是没必要的泄露。
     * 归属条件在 SQL 里（{@code findDownloadTask} 的三个 where）。
     */
    public DocDtos.DownloadTaskView taskDetail(String taskId) {
        LoginUser user = LoginUserContext.require();
        long ownerId = user.getUserId();
        SysAsyncTaskRepository.DownloadTaskRow row = taskRepository
                .findDownloadTask(taskId, ownerId, TASK_TYPE)
                .orElseThrow(() -> BizException.of(ErrorCode.TASK_NOT_FOUND));
        return toView(row, ownerId);
    }

    // ==================================================================
    // `T-615` · 下载历史 / 导出清单
    // ==================================================================

    /**
     * {@code GET /api/doc/download-records} —— 我的下载历史（分页）。
     *
     * <p>分页语义完全交给 {@link DownloadRecordService#page}（复用全站标准分页口径）。
     */
    public PageResult<DocDtos.DownloadRecordRow> records(PageQuery query) {
        LoginUser user = LoginUserContext.require();
        return downloadRecordService.page(user.getUserId(), query);
    }

    /**
     * {@code POST /api/doc/export-list} —— 导出**下载记录清单**（异步 CSV → 通知中心）。
     *
     * <p>🔴 导的是**下载记录**，不是文档列表 —— 端点名 {@code export-list} 容易读错，
     * 原型与 UI 文案三处都指向"下载记录"（见 {@link DocDtos.ExportListAccepted} 的说明）。
     *
     * <p>与打包下载同一个形状（{@code AuditExportService} 是更早的先例）：
     * 提交线程捕获身份 → 异步体查数据、写 CSV、落 MinIO → 预签名地址进 payload →
     * `TaskContext#complete` → `NoticePublisher` 据此产出通知。
     * <p>⚠️ 与打包不同的一点：这里**不写 {@code sys_download_record}**
     * —— 导出记录本身不是"下载了一个文件"，写进去会让下载历史把自己的导出动作
     * 也算成一次下载，越滚越多。
     */
    @AuditLog(module = AuditModule.DOC, action = "导出文档清单", risk = RiskLevel.HIGH)
    public DocDtos.ExportListAccepted exportList() {
        LoginUser user = LoginUserContext.require();
        long ownerId = user.getUserId();
        String stamp = LocalDateTime.now().format(STAMP);
        String idempotencyKey = EXPORT_TASK_TYPE + ":" + ownerId + ":" + stamp;

        String taskId = asyncTaskService.submit(EXPORT_TASK_TYPE,
                AsyncTaskService.TaskSubject.of(null, null, ownerId), idempotencyKey, 0, context -> {
                    // ⚠️ 工作线程：只用下面这些**已经捕获好的**值（ownerId / stamp），
                    //    绝不再读 LoginUserContext（见类注释）
                    context.stage("查询下载记录");
                    context.percent(30);
                    List<DocDtos.DownloadRecordRow> rows =
                            downloadRecordService.listForExport(ownerId, MAX_EXPORT_ROWS);
                    String csv = csvOf(rows);

                    context.stage("写入文件");
                    context.percent(70);
                    String objectKey = DocStorage.docExportObjectKey(ownerId, stamp);
                    docStorage.putBytes(objectKey, csv.getBytes(StandardCharsets.UTF_8),
                            "text/csv; charset=utf-8");

                    Map<String, Object> payload = new HashMap<>();
                    payload.put("objectKey", objectKey);
                    payload.put("bytes", csv.getBytes(StandardCharsets.UTF_8).length);
                    payload.put("rows", rows.size());
                    payload.put("fileUrl", docStorage.presignedGet(objectKey,
                            Duration.ofHours(PresignTtl.DOC_EXPORT_HOURS), DISPOSITION_ATTACHMENT));
                    payload.put("expiresInHours", PresignTtl.DOC_EXPORT_HOURS);
                    context.complete(payload);

                    log.info("下载记录导出完成：taskId={} userId={} rows={} object={}",
                            context.taskId(), ownerId, rows.size(), objectKey);
                });

        return new DocDtos.ExportListAccepted(taskId, AsyncTaskStatus.PENDING.name(),
                "导出任务已提交，完成后将在通知中心提醒");
    }

    // ==================================================================
    // 内部 · 打包
    // ==================================================================

    /**
     * 打包任务体（**工作线程**）。只做三件事：取流 → 写 zip 条目 → 落 MinIO。
     *
     * <p>🔴 逐篇 {@code open}→{@code write}→{@code closeEntry}：**不把 N 篇的字节同时拿在手上**。
     * 内存峰值是"一篇文档"的量级（单篇上限 100MB），与"50 篇"无关 ——
     * 若有人图省事先把 50 篇读成 {@code List<byte[]>}，2GB 上限会直接把堆打爆。
     *
     * <p>🔴 每篇的 {@code BATCH} 记录写在**它真的进了 zip 之后**（{@code closeEntry} 之后）：
     * 写在前面就会出现"记录了、但打包在后面失败了"—— 那条记录会指向一个
     * 用户根本没拿到的文件。与"审计绝不回滚业务"相反：这里记录是**结果**的证据，
     * 只能跟在结果后面。
     */
    private void packBatch(AsyncTaskService.TaskContext context, long ownerId,
                           List<PackItem> items, String ip) {
        Path zip = null;
        try {
            context.stage("打包中");
            context.percent(10);
            zip = Files.createTempFile("kb-batch-download-", ".zip");

            Set<String> usedNames = new HashSet<>();
            int packed = 0;
            try (ZipOutputStream zos = new ZipOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(zip)), StandardCharsets.UTF_8)) {
                for (PackItem item : items) {
                    zos.putNextEntry(new ZipEntry(entryName(usedNames, item.fileName(), item.docId())));
                    try (InputStream in = docStorage.stream(item.objectKey())) {
                        in.transferTo(zos);
                    }
                    zos.closeEntry();

                    downloadRecordService.record(item.docId(), item.kbId(), ownerId,
                            DownloadRecordService.TYPE_BATCH, item.sizeBytes(), ip);
                    packed++;
                    // 真实进度：chunk_done 就是"已打进去的篇数"（chunk_total 在提交时已填）
                    context.done(packed);
                }
            }

            long size = Files.size(zip);
            context.stage("写入存储");
            context.percent(85);
            String objectKey = DocStorage.batchZipObjectKey(ownerId, context.taskId());
            docStorage.storeBatchZip(objectKey, zip, size);

            Map<String, Object> payload = new HashMap<>();
            payload.put("objectKey", objectKey);
            payload.put("fileUrl", docStorage.presignedGet(objectKey,
                    Duration.ofMinutes(PresignTtl.DOC_DOWNLOAD_MINUTES), DISPOSITION_ATTACHMENT));
            payload.put("expiresAt", LocalDateTime.now()
                    .plusMinutes(PresignTtl.DOC_DOWNLOAD_MINUTES).format(TIME_FORMAT));
            payload.put("count", packed);
            payload.put("bytes", size);
            context.complete(payload);

            log.info("打包下载完成：taskId={} userId={} docs={} bytes={} object={}",
                    context.taskId(), ownerId, packed, size, objectKey);
        } catch (BizException ex) {
            // 已有明确原因（对象不存在 / 写盘失败），原样抛让任务带上它
            throw ex;
        } catch (Exception ex) {
            log.error("打包下载失败：taskId={} userId={} err={}",
                    context.taskId(), ownerId, ex.getMessage(), ex);
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "打包失败：" + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        } finally {
            // 成功与否都要删：2GB 的临时文件按"每次打包一份"的速度能把磁盘吃光。
            // 删不掉只记 warn（与 DocStorage#discardTemp 同一取舍）
            docStorage.discardTemp(zip);
        }
    }

    /**
     * zip 条目名。重名时加 {@code (2)} / {@code (3)} 后缀。
     *
     * <p>为什么要去重：zip **允许**重名条目，但解压时后一个会**覆盖**前一个 ——
     * 用户拿到一个"选了 5 篇、解开只有 4 篇"的包，而 zip 本身完全合法。
     * 同名在跨库选择时是常态（各库都有「管理制度.pdf」）。
     */
    private static String entryName(Set<String> used, String fileName, long docId) {
        String base = (fileName == null || fileName.isBlank()) ? "文档-" + docId : fileName;
        // 条目名里的路径分隔符必须清掉：部分解压工具会据此建目录，甚至有拒绝解压的
        base = base.replace('\\', '_').replace('/', '_');
        if (used.add(base)) {
            return base;
        }
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String ext = dot > 0 ? base.substring(dot) : "";
        for (int n = 2; ; n++) {
            String candidate = stem + "(" + n + ")" + ext;
            if (used.add(candidate)) {
                return candidate;
            }
        }
    }

    // ==================================================================
    // 内部 · 投影与校验
    // ==================================================================

    /**
     * 持久化任务行 → 对外视图。
     *
     * <p>🔴 <b>完成态的 {@code size} / {@code fileUrl} 由对象存储回答，不来自任务结果</b>
     * —— 结果 payload 不落库（{@code SysAsyncTaskRepository#find} 的注释），
     * 但打包产物的对象名是 {@code ownerId + taskId} 的纯函数（{@code DocStorage#batchZipObjectKey}），
     * 所以**重启之后仍然答得出**，且每次现签一个新鲜的 URL
     * （需求 `06` §5.8「预签名 URL 过期后给出重新生成入口，不是死路」——
     * 那条要求由"每次读都重签"满足，不需要单独的续签接口）。
     */
    private DocDtos.DownloadTaskView toView(SysAsyncTaskRepository.DownloadTaskRow row, long ownerId) {
        String taskId = row.taskId();
        String createdAt = row.createdAt() == null ? null : row.createdAt().format(TIME_FORMAT);
        int count = row.chunkTotal();

        AsyncTaskStatus status = statusOf(row.status());
        if (status != AsyncTaskStatus.DONE) {
            // PENDING / RUNNING ⇒ PACKING；FAILED ⇒ FAILED（词表见 DocDtos.DownloadTaskView）
            String view = status == AsyncTaskStatus.FAILED ? "FAILED" : "PACKING";
            return new DocDtos.DownloadTaskView(taskId, DocStorage.BATCH_ZIP_NAME, count, 0L,
                    view, row.percent(), createdAt, null, null, row.errorMsg());
        }

        String objectKey = DocStorage.batchZipObjectKey(ownerId, taskId);
        long size = docStorage.sizeOf(objectKey);
        if (size < 0) {
            // 任务确实完成过，但产物已经不在了（被清理 / 桶被清空）。
            // 如实报「失败」并且**原因就是这一句** —— 需求 `06` §5.4 把"文件已删除"
            // 列在失败态的失败原因里，正是这个情形。继续报 DONE 会给用户一个点不动的按钮。
            log.warn("打包任务已完成但产物不在存储中：taskId={} object={}", taskId, objectKey);
            return new DocDtos.DownloadTaskView(taskId, DocStorage.BATCH_ZIP_NAME, count, 0L,
                    "FAILED", 100, createdAt, null, null,
                    "打包文件已不在存储中（可能已被清理），请重新发起打包下载");
        }

        String fileUrl = docStorage.presignedGet(objectKey,
                Duration.ofMinutes(PresignTtl.DOC_DOWNLOAD_MINUTES), DISPOSITION_ATTACHMENT);
        String expiresAt = fileUrl == null ? null : LocalDateTime.now()
                .plusMinutes(PresignTtl.DOC_DOWNLOAD_MINUTES).format(TIME_FORMAT);
        return new DocDtos.DownloadTaskView(taskId, DocStorage.BATCH_ZIP_NAME, count, size,
                "DONE", 100, createdAt, fileUrl, expiresAt, null);
    }

    /** 状态词：库里存的是 {@code AsyncTaskStatus} 的枚举名，认不出时**如实报失败**并留痕。 */
    private static AsyncTaskStatus statusOf(String raw) {
        try {
            return AsyncTaskStatus.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException ex) {
            // 脏值不该让整个列表 500。报 FAILED（不报 PACKING —— 那会让前端一直转圈）
            log.warn("无法识别的任务状态（按失败处理）：raw={}", raw);
            return AsyncTaskStatus.FAILED;
        }
    }

    /**
     * 批量入参的**形状与数量**校验（不涉及权限）。
     *
     * <p>三条都得给**现象 + 下一步动作**（`G4-3`）：只说"参数非法"用户不知道要改什么。
     */
    private static List<Long> requireBatchScope(List<Long> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请先选择要下载的文档");
        }
        // 去重：同一篇选两次在页面上做得到（跨库列表里同名文件），
        // 不去重就会在 zip 里出现两份、下载记录也写两条
        List<Long> ids = docIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请先选择要下载的文档");
        }
        if (ids.size() > MAX_BATCH_DOCS) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "单次最多打包 " + MAX_BATCH_DOCS + " 个文件，当前选了 " + ids.size()
                            + " 个；请分批下载（例如先下前 " + MAX_BATCH_DOCS + " 个）");
        }
        return ids;
    }

    /**
     * 文档还没转存完（{@code storage_path} 为空）时拒绝。
     *
     * <p>上传是两步的（先插行、转存成功才回填对象名，见 {@code DocRecordWriter#insertVersioned}），
     * 所以"列表里看得到、文件还没有"是一个真实存在的中间态。
     * 它的下一步动作与"对象丢了"完全不同 —— 一个是等，一个是重新上传 —— 故分开报。
     */
    private static String requireStored(DocAccessService.DocRow doc, String what) {
        String objectKey = doc.storagePath();
        if (objectKey == null || objectKey.isBlank()) {
            throw BizException.of(ErrorCode.DOC_NOT_FOUND,
                    "文档「" + doc.title() + "」的文件尚未存储完成，暂时无法" + what
                            + "；请等待其解析完成后重试");
        }
        return objectKey;
    }

    private static DocDtos.DownloadUrl urlOf(String url, int minutes) {
        return new DocDtos.DownloadUrl(url,
                LocalDateTime.now().plusMinutes(minutes).format(TIME_FORMAT), minutes * 60);
    }

    private static String mb(long bytes) {
        return String.valueOf(bytes / 1024 / 1024);
    }

    private static String csvOf(List<DocDtos.DownloadRecordRow> rows) {
        StringBuilder sb = new StringBuilder(Csv.BOM);
        sb.append(Csv.row("文档标题", "知识库", "下载方式", "大小(字节)", "操作人", "下载时间", "来源IP"));
        for (DocDtos.DownloadRecordRow r : rows) {
            sb.append(Csv.row(r.docTitle(), r.kbName(), typeLabel(r.type()),
                    String.valueOf(r.size()), r.operator(), r.at(), r.ip()));
        }
        return sb.toString();
    }

    /** 库里的 {@code SINGLE}/{@code BATCH} → 中文（导出给用户看的表里不该出现枚举名）。 */
    private static String typeLabel(String type) {
        return DownloadRecordService.TYPE_BATCH.equals(type) ? "批量" : "单文件";
    }

    /**
     * 请求线程上快照下来的**一篇待打包文档**。
     *
     * <p>🔴 它存在的理由就是"工作线程不能重新鉴权"（见类注释）：
     * 鉴权要身份、身份是 {@code ThreadLocal}，所以必须在提交线程里做完并把
     * **结果**（而不是"去查一遍的条件"）带过去。
     */
    private record PackItem(long docId, long kbId, String fileName, String objectKey, long sizeBytes) {
    }
}
