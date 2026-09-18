package com.knowledge.ai.service;

import com.knowledge.ai.core.async.AsyncTaskInfo;
import com.knowledge.ai.core.async.AsyncTaskService;
import com.knowledge.ai.core.async.ParseWorker;
import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.core.storage.DocStorage;
import com.knowledge.ai.dto.doc.DocDtos;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M05 文档管线 · 阶段 A：**上传入库**（`T-500` / `T-501` / `T-509` / `T-510`）。
 *
 * <p><b>本类的边界</b>：文件的「身份 + 落盘 + 状态 + 列表」。**不解析、不分片、不向量化**
 * —— 那三件事在 S4-B / S4-C（`T-502`~`T-508`）。所以本批文档的终态是 {@code UPLOADED}，
 * 而那正是《03》§4.2 状态机的**起点**，不是伪造的中间态。
 *
 * <h3>为什么上传要走异步任务</h3>
 * <p>100MB 的转存是**秒级到分钟级**的 I/O，不能让 HTTP 请求线程挂着（`T-006` 的立意）。
 * 所以接口立刻返回 {@code {docId, taskId}}，前端轮询 {@code GET /doc/tasks/{taskId}}。
 * 幂等键取 {@code doc-upload:{kbId}:{ownerId}:{md5}} —— 用户连点两下**只跑一次**
 * （第二下拿回同一个 taskId）。
 *
 * <p><b>写入的落点在 {@link DocRecordWriter} 而不在本类</b>：上传的写入横跨两个线程
 * （建行在 HTTP 线程、回写对象名在工作线程），而 {@code @Transactional} 是代理语义，
 * 同类内部自调用不生效。理由详见那个类的头注释。
 *
 * <h3>越权与存在性为什么复用求交条件</h3>
 * <p>与 {@code KbService#configRowOrDeny} 同一条路：调
 * {@link DataScopeService#kbVisibility} 拿到 SQL 片段**裸拼进 where**，
 * 而不是自己写一套 join。三条理由：① 本项目的「可见」= 授权 ∩ 数据范围 ∩ 账号状态
 * （第四维，B2 的 W7 加的）**三处求交**，自己写一遍必然漏一维；② 将来加第五维时只有一处要改；
 * ③ 求交在 SQL 层完成（`G2-8` / D-3：不是查全量后内存过滤）。
 *
 * <p>不存在（{@code 10043}）与无权限（{@code 10022}）**分开报** ——
 * 合并成一句会让用户分不清"库名打错了"与"你没被授权"，两者的下一步动作不同。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 异步任务类型标识，与 {@code AsyncTaskInfo#getType()} 的口径一致。 */
    public static final String TASK_TYPE = "doc-upload";

    /** 文档列表每页上限（前端不传 size 时用它）。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final EntityManager entityManager;

    private final DataScopeService dataScopeService;

    private final DocStorage docStorage;

    private final DocRecordWriter recordWriter;

    /** `T-O04` 步 4：宏检测（**请求线程**做，见 {@code upload} 里的注释）。 */
    private final com.knowledge.ai.core.storage.IngestGuard ingestGuard;

    /** `T-O04` 步 3：恶意扫描的异步编排。 */
    private final DocScanService docScanService;

    private final AsyncTaskService asyncTaskService;

    /**
     * 解析作业的投递口（`S4-E1`）。
     *
     * <p>⚠️ 这里依赖的是 **`ParseWorker` 的入队方法**，不是 `DocParseService` 本身 ——
     * 上传**不再直接解析**（原因见 {@code upload} 里的注释）。本类因此不再需要
     * `DocParseService`，那正是这次拆分在依赖图上的样子。
     */
    private final ParseWorker parseWorker;

    /**
     * 解析链路的审计留痕点（`S4-E2` · `T-O06` 步 1）。
     *
     * <p>⚠️ 是**另一个 Bean**，不是本类的一个私有方法 —— 审计靠 `@AuditLog` 注解 + AOP 代理，
     * 而**同类内部自调用不走代理**（本仓已为此踩过两次：`ReindexService#switchGeneration`
     * 的 `@Transactional`、以及 `KbController` 那批注释）。写成私有方法的话，
     * 审计会**静默不落库**，而接口一切正常。
     */
    private final ParseAuditTrail parseAuditTrail;

    // ==================== T-501 · MD5 去重（秒传判断）====================

    /**
     * 判断该文件是否已在**本库**存在（去重键见 {@code V12} 的 {@code idx_doc_md5} 注释：
     * {@code (kb_id, owner_id, md5)}）。
     *
     * <p><b>这是"提示"不是"拦截"</b>：《06》§3.2 的口径是「命中 → 提示『文件已存在，是否覆盖』
     * → 覆盖即产生新版本」。所以本方法只回答"命中没命中"，**是否继续上传由用户决定**
     * （{@link #upload} 不会因为命中而拒绝）。这条取舍的理由写在 `V12` 的索引注释里：
     * 加唯一约束会与"覆盖 = 归档新版本"直接冲突（v1/v2 共享 md5）。
     *
     * <p>判定按 {@code deleted_at is null} 过滤 —— 回收站里的文档不该触发"已存在"，
     * 否则用户会遇到"删了又传还是提示已存在"这种没法自救的状态。
     */
    @Transactional(readOnly = true)
    public DocDtos.Md5Check checkMd5(long kbId, DocDtos.Md5CheckRequest request) {
        requireKbVisible(kbId);
        String md5 = normalizeMd5(request == null ? null : request.md5());
        Long ownerId = LoginUserContext.require().getUserId();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select d.id, d.title from sys_document d
                        where d.kb_id = :kbId and d.owner_id = :ownerId and d.md5 = :md5
                          and d.deleted_at is null
                        order by d.id limit 1
                        """)
                .setParameter("kbId", kbId)
                .setParameter("ownerId", ownerId)
                .setParameter("md5", md5)
                .getResultList();

        if (rows.isEmpty()) {
            // 未命中也给一句话：空串在前端会渲染出一个空提示框（见 DocDtos.Md5Check 的 javadoc）
            return new DocDtos.Md5Check(false, null, null, "未发现重复，继续上传即可");
        }
        Object[] row = rows.getFirst();
        return new DocDtos.Md5Check(true, ((Number) row[0]).longValue(), (String) row[1],
                "文件已存在，将归档为新版本（不覆盖历史问答引用）");
    }

    // ==================== T-500 · 上传 ====================

    /**
     * 受理上传：校验 → 建行拿 id → 排异步任务转存 MinIO。
     *
     * <p><b>为什么先建行再转存</b>：对象名里要带 {@code docId}（见 {@link DocStorage} 的头注释），
     * 而 id 要落库才有。若先转存后建行，失败时会在桶里留下**无主对象**
     * （没有任何数据库行引用它，清理无从下手）。
     *
     * <p><b>MD5 由服务端重算</b>：客户端传来的值只用于"秒传预判"（那是
     * {@link #checkMd5} 的事），入库事实必须以服务端算出来的为准 —— 否则客户端可以
     * 伪造 md5 让去重判定被绕过，而 `T-501` 的整条链路都建立在"md5 是内容指纹"之上。
     *
     * <p>注解在**本方法**上（不在 controller）：`@AuditLog` 需要 {@code LoginUserContext}
     * 与请求上下文，二者在 Service 层都还在（切面按 `@AuditLog` 的 module/action 取）。
     * 在此打点还顺带覆盖了 `apiPath`（切面从当前请求取），见 `S3-M03-C2` 的冻结口径：
     * **新增审计点必须同步三处** —— ① 本注解 ② {@code AuditRiskPolicy.IMPLEMENTED} 登记
     * ③ {@code AuditCoverageFlowTest#expectedActions()} 的冻结清单（24 → 25）。
     *
     * @return {@code {docId, taskId, name, kbId, chunkTotal=0}}；{@code chunkTotal} 恒 0
     *         —— 分片总数要解析完才知道（S4-B 起由任务体回填）
     */
    @Transactional
    @AuditLog(module = AuditModule.DOC, action = "上传文档", risk = RiskLevel.NORMAL,
            target = "'文档#' + #result.docId")
    public DocDtos.UploadAccepted upload(long kbId, MultipartFile file) {
        LoginUser user = LoginUserContext.require();
        requireKbVisible(kbId);
        docStorage.validate(file);

        String realName = normalizeFileName(file.getOriginalFilename());
        String title = DocStorage.titleOf(realName);
        String type = DocStorage.extensionOf(realName).toUpperCase(Locale.ROOT);
        long size = file.getSize();
        String md5 = md5Of(file);

        // ---- `T-O04` 步 4：宏检测必须在**请求线程**做完 ----
        // ⚠️ 与暂存同一个理由（见下面 `spillToTemp` 的注释）：`MultipartFile` 只在这次请求
        // 的内存里活着，而异步任务体里已经读不到它了。宏检测要读字节，所以它**不能**
        // 跟着扫描一起挪到异步里去 —— 挪过去只会拿到一个"文件已被容器清掉"的异常，
        // 而那会被 `hasMacro` 的 catch 吞成 `null`（判不了），表现为 `has_macro` 永远是空。
        Boolean hasMacro = ingestGuard.hasMacro(file, DocStorage.extensionOf(realName));

        long docId = recordWriter.insert(kbId, user.getUserId(), title, realName,
                type.isEmpty() ? "UNKNOWN" : type, size, md5);

        // ⚠️ **必须在请求线程把字节定下来**（`S4-E1`）：`MultipartFile` 是请求作用域的，
        //    请求一结束容器就清掉它。转存跑在异步任务里，不落暂存就是**赌一个竞态**——
        //    详见 `DocStorage` 的 `spillToTemp` 注释（那条竞态在 `S4-E1` 被确定性触发过）。
        String contentType = file.getContentType();
        Path staged = docStorage.spillToTemp(file);

        // ---- `S4-E2`（`O6` 步 1）：**在这里**留痕「谁触发了这篇文档的解析」----
        // 为什么是这里而不是真正投递作业的那一步：投递发生在**异步体里**（转存成功之后
        // 才入队，理由见下面 `parseWorker.enqueue` 的注释），那一刻请求线程的
        // `LoginUserContext` 已经不存在了 —— 在那儿写审计只会得到一条"匿名"的假记录。
        //
        // 这就是 `O6` §2 的立场：**授权与留痕都发生在有身份的那一刻**，工作线程只执行
        // 已被授权过的既定作业。它与 `ParseWorker` 的执行用同一个 traceId 串起来。
        parseAuditTrail.parseSubmitted(kbId, docId);

        // 幂等键含 (kbId, ownerId, md5)：同一个人对同一个库传同一个文件，连点也只跑一次。
        // ⚠️ 刻意**不含 docId** —— 那样每次调用都是新 key，幂等就失效了；
        //    代价是"刚传完立刻再传同一文件"会复用已完成的任务（拿回同一个 taskId），
        //    这是可接受的：完成态的任务读出来仍是 DONE，前端展示"已完成"而非"进行中"。
        String idempotencyKey = TASK_TYPE + ":" + kbId + ":" + user.getUserId() + ":" + md5;
        long ownerId = user.getUserId();
        // 任务投不出去（如落库失败）时，没人会删暂存文件 —— 这里兜住
        String taskId;
        try {
            taskId = asyncTaskService.submit(TASK_TYPE,
                    AsyncTaskService.TaskSubject.of(kbId, docId, ownerId), idempotencyKey, 0, context -> {
                        context.stage("接收文件");
                        context.percent(20);
                        context.stage("转存对象存储");
                        context.percent(60);
                        try {
                            String objectKey = docStorage.storeFromFile(kbId, docId, staged, realName, size, contentType);
                            context.stage("写入文档记录");
                            context.percent(90);
                            recordWriter.markStored(docId, objectKey);
                            // 宏结论是**请求线程**测好的（见 upload 里的注释），这里只是落库
                            recordWriter.markMacro(docId, hasMacro);

                            // ---- `T-O04` 步 3：恶意扫描（异步，不阻塞上传响应）----
                            // 它在 `markStored` **之后**投递：扫描要从 MinIO 读那个对象，
                            // 而对象名是 store 成功才有的。与 `parseWorker.enqueue` 同一条时序纪律。
                            String scanTaskId = docScanService.submit(kbId, docId, ownerId, objectKey, realName);

                            // ---- `S4-E1`：转存完成后**只投作业，不解析** ----
                            // 这次拆分不是设计偏好，是技术必然（计划 §3.4 假设 1）：
                            // `file`（MultipartFile）的字节只活在这次请求的内存里，任务体的闭包把它捕获了。
                            // 若解析仍在同一个任务体里跑，「进程被杀后重启接着做」（O1 判据 4）
                            // 就永远不成立 —— 重启后源文件只剩 MinIO 里那份，而它要等 store 成功才有。
                            // 所以解析必须成为一个**可被重新领取的作业**，而它只能在 store 之后入队。
                            //
                            // 上传任务的终态因此回到 UPLOADED（不是 INDEXING）：转存是它负责的全部。
                            // 解析的成败由解析作业 + 文档状态共同承载（`G4-3` 的 errorMsg 通道不变）。
                            String parseTaskId = parseWorker.enqueue(kbId, docId, ownerId);
                            context.stage("已转存，等待解析");
                            context.percent(100);

                            Map<String, Object> payload = new HashMap<>();
                            payload.put("docId", docId);
                            payload.put("objectKey", objectKey);
                            payload.put("bytes", size);
                            payload.put("parseTaskId", parseTaskId);
                            payload.put("scanTaskId", scanTaskId);
                            context.complete(payload);
                            log.info("文档转存完成，已投解析作业：kbId={} docId={} taskId={} parseTaskId={} name={} bytes={}",
                                    kbId, docId, context.taskId(), parseTaskId, realName, size);
                        } catch (BizException ex) {
                            // 业务异常的消息本来就是给用户看的（如"文件超过 100 MB 上限"），直接用
                            recordWriter.markFailed(docId, ex.getMessage());
                            throw ex;
                        } catch (Exception ex) {
                            recordWriter.markFailed(docId, "文件存储失败，请稍后重试");
                            throw ex;
                        } finally {
                            // 暂存文件**无论成败都要删** —— 100MB 一份，漏删就是按篇吃磁盘
                            docStorage.discardTemp(staged);
                        }
                    });
        } catch (RuntimeException ex) {
            docStorage.discardTemp(staged);
            throw ex;
        }

        return new DocDtos.UploadAccepted(docId, taskId, realName, kbId, 0);
    }

    // ==================== T-509 · 任务状态 ====================

    /**
     * 任务进度（对内完整快照 → 对外六字段投影）。
     *
     * <p>投影而不是直接下发 {@link AsyncTaskInfo} 的理由：内部快照含 {@code result}
     * （里面可能有预签名 URL）与 {@code traceId}，前者不该无差别下发、后者不该出现在业务响应里。
     */
    public DocDtos.TaskView taskView(String taskId) {
        AsyncTaskInfo<Object> info = asyncTaskService.require(taskId);
        return new DocDtos.TaskView(info.getTaskId(), info.getStage(), info.getPercent(),
                info.getChunkDone(), info.getChunkTotal(), info.getErrorMsg());
    }

    // ==================== T-510 · 文档列表 ====================

    /**
     * 库内文档列表（关键字 / 状态 / 解析质量三档筛选）。
     *
     * <p><b>{@code total} 与 {@code list} 同源</b>：两者都从 {@code sys_document} 实查
     * —— 不是从 {@code sys_kb.doc_count} 那个示例冗余列读（那会造出"知识库列表说 128 篇、
     * 这里说 0 篇"的双答案，见本批 §1 W3）。
     *
     * <p>口径是：<b>{@code total} = 该库文档总数（不带筛选）</b>，{@code displayed} = 本页条数。
     * 前端把 {@code total} 渲染成「共 N 篇」—— 那句说的是"这个库有多少篇"，
     * 不是"筛完还剩几篇"（后者是 {@code displayed}）。两者分开，才不会互相冒充。
     *
     * <p>{@code quality=issue} = 存在未识别表格或图片
     * （{@code tableParsed < tableTotal} 或 {@code imageParsed < imageTotal}），
     * 与前端顶部告警条同源。
     */
    @Transactional(readOnly = true)
    public DocDtos.DocPage page(long kbId, String keyword, String status, String quality,
                                Integer page, Integer size) {
        requireKbVisible(kbId);

        StringBuilder where = new StringBuilder(" where d.deleted_at is null and d.kb_id = :kbId");
        Map<String, Object> params = new HashMap<>();
        params.put("kbId", kbId);

        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (d.title like :kw or d.file_name like :kw)");
            params.put("kw", "%" + keyword.trim() + "%");
        }
        if (status != null && !status.isBlank() && !"all".equals(status)) {
            where.append(" and d.status = :status");
            params.put("status", status);
        }
        if ("issue".equals(quality)) {
            where.append(" and (d.table_parsed < d.table_total or d.image_parsed < d.image_total)");
        }

        int pageSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, 200);
        int offset = Math.max(0, (page == null || page <= 0 ? 1 : page) - 1) * pageSize;

        long total = ((Number) entityManager.createNativeQuery(
                        "select count(*) from sys_document d where d.deleted_at is null and d.kb_id = :kbId")
                .setParameter("kbId", kbId)
                .getSingleResult()).longValue();

        // 分页用参数绑定（:limit/:offset）而不是字符串拼接 —— 虽然后者是内部算出的整数、
        // 没有注入面，但"SQL 里出现拼接的数字"会让后续 review 每次都要重新论证一遍安全性。
        Query query = entityManager.createNativeQuery("""
                        select d.id, d.title, d.type, d.size_bytes, d.chunk_count, d.status,
                               d.table_total, d.table_parsed, d.image_total, d.image_parsed,
                               d.pages_total, d.pages_with_text,
                               u.real_name, d.created_at,
                               d.scan_status, d.has_macro
                        from sys_document d
                        left join sys_user u on u.id = d.owner_id
                        """ + where + " order by d.id desc limit :limit offset :offset");
        params.forEach(query::setParameter);
        query.setParameter("limit", pageSize);
        query.setParameter("offset", offset);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<DocDtos.DocRow> list = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            list.add(new DocDtos.DocRow(
                    ((Number) r[0]).longValue(),
                    (String) r[1],
                    (String) r[2],
                    ((Number) r[3]).longValue(),
                    ((Number) r[4]).longValue(),
                    (String) r[5],
                    ((Number) r[6]).longValue(),
                    ((Number) r[7]).longValue(),
                    ((Number) r[8]).longValue(),
                    ((Number) r[9]).longValue(),
                    ((Number) r[10]).longValue(),
                    ((Number) r[11]).longValue(),
                    r[12] == null ? "—" : (String) r[12],
                    formatTime(r[13]),
                    // `T-O04`：扫描结论与宏标记。两者都可能为 null ——
                    // scan_status 为 null = 该行早于扫描能力上线（**不是** SKIPPED）；
                    // has_macro 为 null = 没检测过（**不是**"没有宏"）。
                    // 前端据此决定显示「未扫描」标记与否，所以这个区分必须原样透传
                    (String) r[14],
                    // ⚠️ `has_macro` 是 `TINYINT(1)`，而 MySQL 驱动默认把
                    // `tinyInt1isBit=true` ⇒ **原生查询回给我们的是 `Boolean` 而不是数字**。
                    // 直接 `((Number) r[15])` 会抛 ClassCastException，而它的表现是
                    // **整个文档列表接口 500**（实测踩到）—— 一个"列的类型"问题炸掉了一整页。
                    // 两种都收下：驱动版本/连接参数一变就会在两个之间切，而这里没有理由挑边。
                    boolOf(r[15])));
        }
        return new DocDtos.DocPage(list, total, list.size());
    }

    // ==================== T-511 · 未识别表格 / 解析问题清单 ====================

    /**
     * 库内逐页解析问题（驱动上传页顶部告警条，`G4-11`）。
     *
     * <p><b>行粒度 = (文档, 页码)</b>：同一篇扫描件 15 页就有 15 行 ——
     * 「第几页」是门禁原文点名的字段，聚合成"每文档一行"会把它丢掉；
     * 前端的"篇数"按 {@code docId} 去重（本批前端步骤）。
     *
     * <p><b>截断 100 条</b>：清单的用途是"告诉人去哪修"，不是数据导出；
     * 真有超过 100 处问题时，靠列表的质量列逐篇排查比一个滚不到头的告警条更有用。
     *
     * <p>只统计**未删除**文档的问题 —— 回收站里的文档不该再告警（与 {@link #checkMd5}
     * 的 {@code deleted_at is null} 同一条口径）。
     */
    @Transactional(readOnly = true)
    public DocDtos.ParseIssuePage parseIssues(long kbId) {
        requireKbVisible(kbId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select i.doc_id, d.title, i.page_no, i.reason, i.kind, i.disposition
                        from sys_parse_issue i
                        join sys_document d on d.id = i.doc_id and d.deleted_at is null
                        where i.kb_id = :kbId
                        order by i.doc_id, i.page_no, i.id
                        limit 100
                        """)
                .setParameter("kbId", kbId)
                .getResultList();

        List<DocDtos.ParseIssueRow> list = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            list.add(new DocDtos.ParseIssueRow(
                    ((Number) r[0]).longValue(),
                    (String) r[1],
                    ((Number) r[2]).intValue(),
                    (String) r[3],
                    (String) r[4],
                    (String) r[5]));
        }
        return new DocDtos.ParseIssuePage(list, list.size());
    }

    // ==================== 内部 ====================

    /**
     * 求交放行：库存在 + 当前用户能看见它。不存在 → {@code 10043}，无权限 → {@code 10022}。
     *
     * <p>与 {@code KbService#configRowOrDeny} 同一范式（那里还会取回整行配置），
     * 这里只要一个"放行/拒绝"的判定，所以只查 {@code count}。
     */
    private void requireKbVisible(long kbId) {
        // 软删的库视为不存在（`T-407`）：回收站里的库不该还能上传文档。
        // 下面那条按可见性求交的 SQL 已自动带上同一条件（`DataScopeService#kbVisibility`），
        // 这一条是**先判存在、再判可见**的第一道，所以它自己也要带。
        Number exists = (Number) entityManager.createNativeQuery(
                        "select count(*) from sys_kb where id = :id and deleted_at is null")
                .setParameter("id", kbId)
                .getSingleResult();
        if (exists == null || exists.longValue() == 0) {
            throw BizException.of(ErrorCode.KB_NOT_FOUND);
        }
        DataScopeService.ScopeCondition cond = dataScopeService.kbVisibility(LoginUserContext.require());
        // ⚠️ 拼接处保留前导空格：text block 会剥掉每行行尾空白，写成
        // `... and " + cond.sql()` 会拼出 `andEXISTS`（SQL 语法错，B1 §8.1 实测踩到）
        Query query = entityManager.createNativeQuery(
                "select count(*) from sys_kb k where k.id = :id and " + cond.sql());
        query.setParameter("id", kbId);
        cond.params().forEach(query::setParameter);
        Number visible = (Number) query.getSingleResult();
        if (visible == null || visible.longValue() == 0) {
            throw BizException.of(ErrorCode.KB_ACCESS_DENIED);
        }
    }

    /** 时间列在 JDBC 侧可能是 {@code Timestamp} 也可能是 {@code LocalDateTime}，两者都接。 */
    /**
     * `TINYINT(1)` 列 → Boolean。**两种都收**。
     *
     * <p>MySQL 驱动默认 `tinyInt1isBit=true`，于是原生查询回的是 {@link Boolean}；
     * 一旦有人调了那个连接参数、或换了驱动版本，回来的就是 {@link Number}。
     * 只认一种的代价在 `T-O04` 实测过：**整个文档列表接口 500**，
     * 报出来的是一句 `Boolean cannot be cast to Number` —— 与"列表"这个功能看起来
     * 毫无关系，排查要从接口翻到 SQL 再翻到连接参数。
     */
    private static Boolean boolOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return ((Number) value).intValue() != 0;
    }

    private static String formatTime(Object value) {
        if (value == null) {
            return "—";
        }
        if (value instanceof java.sql.Timestamp ts) {
            return TIME_FORMAT.format(ts.toLocalDateTime());
        }
        if (value instanceof LocalDateTime ldt) {
            return TIME_FORMAT.format(ldt);
        }
        return String.valueOf(value);
    }

    /** 文件名为空时给一个占位名（不是拒绝：某些客户端确实不发 filename）。 */
    private static String normalizeFileName(String fileName) {
        return (fileName == null || fileName.isBlank()) ? "未命名文件" : fileName.trim();
    }

    /**
     * 由文件内容算 MD5（32 位小写十六进制）。
     *
     * <p>用 {@link MessageDigest} 逐块读，**不 {@code readAllBytes()}** ——
     * 100MB 文件一次性进堆会让并发上传时的内存占用失控。8KB 缓冲区是磁盘块与
     * 网络包大小的折中，再加一个 MD5 的计算开销本来就极低。
     */
    private static String md5Of(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception ex) {
            log.warn("计算上传文件 MD5 失败：name={} err={}", file.getOriginalFilename(), ex.getMessage());
            throw new BizException(ErrorCode.SYSTEM_ERROR, "文件校验失败，请重试");
        }
    }

    /**
     * 归一化客户端传来的 md5：小写、去空白；长度不对直接拒。
     *
     * <p>拒而不是"当着查不到处理"：32 位以外的值进 SQL 必然查不到，
     * 于是接口会回 {@code duplicated=false} —— 而那是**一个错误答案**，
     * 会让前端以为"这文件是新的"。参数不合法就该显式报 {@code 10030}。
     */
    private static String normalizeMd5(String md5) {
        String v = md5 == null ? "" : md5.trim().toLowerCase(Locale.ROOT);
        if (!v.matches("[0-9a-f]{32}")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "md5 必须是 32 位十六进制字符串");
        }
        return v;
    }
}
