package com.knowledge.ai.service;

import com.knowledge.ai.core.async.AsyncTaskInfo;
import com.knowledge.ai.core.async.AsyncTaskService;
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

    private final AsyncTaskService asyncTaskService;

    private final DocParseService docParseService;

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
            target = "'知识库#' + #kbId")
    public DocDtos.UploadAccepted upload(long kbId, MultipartFile file) {
        LoginUser user = LoginUserContext.require();
        requireKbVisible(kbId);
        docStorage.validate(file);

        String realName = normalizeFileName(file.getOriginalFilename());
        String title = DocStorage.titleOf(realName);
        String type = DocStorage.extensionOf(realName).toUpperCase(Locale.ROOT);
        long size = file.getSize();
        String md5 = md5Of(file);

        long docId = recordWriter.insert(kbId, user.getUserId(), title, realName,
                type.isEmpty() ? "UNKNOWN" : type, size, md5);

        // 幂等键含 (kbId, ownerId, md5)：同一个人对同一个库传同一个文件，连点也只跑一次。
        // ⚠️ 刻意**不含 docId** —— 那样每次调用都是新 key，幂等就失效了；
        //    代价是"刚传完立刻再传同一文件"会复用已完成的任务（拿回同一个 taskId），
        //    这是可接受的：完成态的任务读出来仍是 DONE，前端展示"已完成"而非"进行中"。
        String idempotencyKey = TASK_TYPE + ":" + kbId + ":" + user.getUserId() + ":" + md5;
        String taskId = asyncTaskService.submit(TASK_TYPE, idempotencyKey, 0, context -> {
            context.stage("接收文件");
            context.percent(20);
            context.stage("转存对象存储");
            context.percent(60);
            try {
                String objectKey = docStorage.store(kbId, docId, file);
                context.stage("写入文档记录");
                context.percent(90);
                recordWriter.markStored(docId, objectKey);

                // ---- S4-B：转存完成即解析（同一任务体的后续阶段，不另立任务）----
                // 阶段文案与百分比对齐"上传只是受理、解析才是内容"的语义；
                // parseDocument 内部已把失败落成 FAILED + error_msg，这里只透传结果形状。
                context.stage("解析中");
                Map<String, Object> parseResult = docParseService.parseDocument(docId);
                boolean parseOk = "INDEXING".equals(parseResult.get("status"));
                context.stage(parseOk ? "解析完成，等待建立索引" : "解析失败，详见文档列表");
                context.percent(100);

                Map<String, Object> payload = new HashMap<>();
                payload.put("docId", docId);
                payload.put("objectKey", objectKey);
                payload.put("bytes", size);
                payload.put("parse", parseResult);
                // 解析失败时任务本身仍算"完成"（受理与转存都成功了）；
                // 失败的事实由文档状态（FAILED + error_msg）与任务 payload 共同承载，
                // 前端轮询读 errorMsg 的通道不变（G4-3）。
                context.complete(payload);
                log.info("文档上传完成：kbId={} docId={} taskId={} name={} bytes={} parse={}",
                        kbId, docId, context.taskId(), realName, size,
                        parseResult.get("status"));
            } catch (BizException ex) {
                // 业务异常的消息本来就是给用户看的（如"文件超过 100 MB 上限"），直接用
                recordWriter.markFailed(docId, ex.getMessage());
                throw ex;
            } catch (Exception ex) {
                recordWriter.markFailed(docId, "文件存储失败，请稍后重试");
                throw ex;
            }
        });

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
                               u.real_name, d.created_at
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
                    r[10] == null ? "—" : (String) r[10],
                    formatTime(r[11])));
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
                        select i.doc_id, d.title, i.page_no, i.reason
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
                    (String) r[3]));
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
        Number exists = (Number) entityManager.createNativeQuery(
                        "select count(*) from sys_kb where id = :id")
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
