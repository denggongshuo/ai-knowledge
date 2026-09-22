package com.knowledge.ai.service;

import com.knowledge.ai.core.async.AsyncTaskService;
import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.model.PageQuery;
import com.knowledge.ai.dto.recycle.RecycleDtos;
import com.knowledge.ai.pipeline.vectorize.VectorWriter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 回收站（`T-616` · 需求 `06` §7 · 门禁 `G7-10`~`G7-14`）。
 *
 * <h3>🔴 这是全仓唯一一个「越过可见性」的列表，理由要写下来</h3>
 * <p>回收站的列表**必须**看见那些被正常可见性挡在外面的行：软删的文档、软删的库。
 * 而全仓其它列表一律过 {@code DataScopeService#kbVisibility}，它自带
 * {@code k.deleted_at IS NULL} —— 直接拿来用会把**这里最该看见的那批**（库已删）
 * 全部滤掉，而且**不报错**（列表就是空的，看起来像"回收站是空的"）。
 * <p>所以本服务的可见性口径只有一条：**挂 `doc:recycle` 权限**（`V2` 里它只授给了
 * `SUPER_ADMIN` —— 那个角色的数据范围本来就是"全部数据"）。
 * <p>⚠️ **哪天把 `doc:recycle` 授给别的角色，这里必须补库级可见性**，而补的时候记住上面那句：
 * 不能直接用 {@code kbVisibility}（它会把软删的库滤掉）。
 *
 * <h3>🔴 两条写接口都要 `type`（与需求 §7.8 的契约差一个字段）</h3>
 * <p>`sys_document.id` 与 `sys_kb.id` 是两个**独立的自增序列** ⇒ 光凭一个 `ids` 列表
 * 无法判定它指的是文档还是知识库（id=5 可能两者都有）。见 {@link RecycleDtos} 的类注释。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecycleService {

    /**
     * 保留期（天）。需求 `06` §7.2 写死「**30 天**；到期行为：系统自动清理，清理后无法恢复」。
     *
     * <p>⚠️ 它是**响应里回给前端**的（`keepDays`）而不是前端常量 —— 页头那句「保留 30 天」
     * 写死在前端的话，哪天策略改了就会出现"后端清 60 天、页面说 30 天"的静默不一致。
     */
    public static final int KEEP_DAYS = 30;

    public static final String TYPE_DOC = "DOC";
    public static final String TYPE_KB = "KB";

    /**
     * 超期清理的**异步任务类型**。⚠️ 与 {@code NoticePublisher} 的
     * {@code TYPE_RECYCLE_CLEANUP} **逐字一致**（同一个协议的两端，改一处必须改另一处）。
     *
     * <p>为什么清理要走异步任务而不是让调度线程直接干：
     * <ol>
     *   <li><b>失败要发通知</b>（需求 §7.9），而 `NoticePublisher` 的唯一产出点是
     *       **异步任务终态** —— 走这条路才拿得到 `taskId` 与 `errorMsg`；</li>
     *   <li>`sys_async_task` 里留下**一次执行记录**：「什么时候跑过、清了多少、失败原因」
     *       在库里查得到，而不是只散在日志里；</li>
     *   <li>它与手工的 `POST /recycle/cleanup` 是**同一条业务路径**
     *       （都调 {@link #cleanup()}），只差"谁触发、要不要通知"。</li>
     * </ol>
     */
    public static final String TASK_TYPE_CLEANUP = "recycle-cleanup";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 取不到删除人时的占位（`V41` 之前删的行）。⚠️ 不编"未知用户" —— 那是在造一个不存在的身份。 */
    private static final String UNKNOWN_BY = "—";

    private final EntityManager entityManager;

    private final DocLifecycleService docLifecycleService;

    /** 把文档恢复到**另一个库**时要清它在旧库分区里的点（与 `moveToKb` 同一处依赖）。 */
    private final VectorWriter vectorWriter;

    /** 定时清理走异步任务（理由见 {@link #TASK_TYPE_CLEANUP}）。 */
    private final AsyncTaskService asyncTaskService;

    // ==================================================================
    // 列表（`G7-10`）
    // ==================================================================

    /**
     * {@code GET /api/recycle/items?type=&keyword=&page=&size=}。
     *
     * <p>两个 Tab 走两条查询（文档 / 知识库），因为两侧的"来源"含义不同
     * （文档是原知识库、知识库是所属部门），字段也不同（知识库多一个"其下多少篇"）。
     */
    @Transactional(readOnly = true)
    public RecycleDtos.RecyclePage items(String type, String keyword, PageQuery query) {
        String normalized = normalizeType(type);
        String kw = (keyword == null || keyword.isBlank()) ? null : "%" + keyword.trim() + "%";
        int size = query.normalizedSize();
        int offset = query.offset();

        List<RecycleDtos.RecycleItem> list = new ArrayList<>();
        long total;
        if (TYPE_KB.equals(normalized)) {
            total = countOf("select count(*) from sys_kb k where k.deleted_at is not null"
                    + (kw == null ? "" : " and k.name like :kw"), kw);
            Query q = entityManager.createNativeQuery("""
                            select k.id, k.name, dp.name,
                                   (select count(*) from sys_document d
                                     where d.kb_id = k.id and d.deleted_at is null),
                                   (select coalesce(sum(d.size_bytes), 0) from sys_document d
                                     where d.kb_id = k.id and d.deleted_at is null),
                                   u.real_name, k.deleted_at
                              from sys_kb k
                              left join sys_department dp on dp.id = k.dept_id
                              left join sys_user u on u.id = k.deleted_by
                             where k.deleted_at is not null
                            """ + (kw == null ? "" : " and k.name like :kw")
                            + " order by k.deleted_at desc, k.id desc limit :limit offset :offset")
                    .setParameter("limit", size).setParameter("offset", offset);
            if (kw != null) {
                q.setParameter("kw", kw);
            }
            for (Object row : q.getResultList()) {
                Object[] r = (Object[]) row;
                LocalDateTime at = toTime(r[6]);
                list.add(new RecycleDtos.RecycleItem(num(r[0]), TYPE_KB, (String) r[1],
                        r[2] == null ? UNKNOWN_BY : (String) r[2],
                        (int) num(r[3]), num(r[4]),
                        text(r[5]), time(at), remainDays(at), null, null));
            }
        } else {
            total = countOf("select count(*) from sys_document d join sys_kb k on k.id = d.kb_id "
                    + "where d.deleted_at is not null"
                    + (kw == null ? "" : " and (d.title like :kw or k.name like :kw)"), kw);
            Query q = entityManager.createNativeQuery("""
                            select d.id, d.title, k.name, d.size_bytes, u.real_name, d.deleted_at,
                                   (k.deleted_at is not null),
                                   (d.doc_group_id is not null and exists (
                                        select 1 from sys_document n
                                         where n.doc_group_id = d.doc_group_id
                                           and n.deleted_at is null
                                           and n.version_no > d.version_no))
                              from sys_document d
                              join sys_kb k on k.id = d.kb_id
                              left join sys_user u on u.id = d.deleted_by
                             where d.deleted_at is not null
                            """ + (kw == null ? "" : " and (d.title like :kw or k.name like :kw)")
                            + " order by d.deleted_at desc, d.id desc limit :limit offset :offset")
                    .setParameter("limit", size).setParameter("offset", offset);
            if (kw != null) {
                q.setParameter("kw", kw);
            }
            for (Object row : q.getResultList()) {
                Object[] r = (Object[]) row;
                LocalDateTime at = toTime(r[5]);
                list.add(new RecycleDtos.RecycleItem(num(r[0]), TYPE_DOC, (String) r[1],
                        (String) r[2], null, num(r[3]), text(r[4]), time(at), remainDays(at),
                        bool(r[6]), bool(r[7])));
            }
        }
        return new RecycleDtos.RecyclePage(list, total, KEEP_DAYS);
    }

    // ==================================================================
    // 恢复（`G7-11`）
    // ==================================================================

    /**
     * {@code POST /api/recycle/restore} —— 从回收站恢复。
     *
     * <h3>两条与"删除"不对称的地方</h3>
     * <ol>
     *   <li><b>知识库的恢复是"连带"的，但代价是零</b>：`KbLifecycleService#softDelete`
     *       只写 kb 那一行，其下文档**从未被动过** ⇒ 恢复 = 清 kb 的 `deleted_at`，
     *       文档自然回到列表里。`affectedDocs` 是**如实数出来**给用户看的
     *       （需求 §7.3「必须在确认框里讲清楚影响多少文档」）。</li>
     *   <li><b>文档恢复要处理"原库已删"</b>（需求 §7.5）：那种情况必须指定 `targetKbId`，
     *       本方法会把它**移到**目标库 —— 搬归属这件事有四个动作一个都不能漏
     *       （见 {@link #moveToTargetKb}）。</li>
     * </ol>
     *
     * <p>🔴 <b>恢复也审计</b>：需求 `05` §4.3 的动作表里没有它，但
     * `O7` 规则表的「审计日志 × ⑤恢复」那一格写着**「记一条」**。
     * 而且它确实该记 —— **恢复是内容可见性的变更**（恢复后该文档重新可被召回），
     * 比大多数 NORMAL 动作更需要留痕。风险标 NORMAL：它不销毁任何东西、且可再次删除
     * （与「版本回滚」同一档的推理）。
     */
    @Transactional
    @AuditLog(module = AuditModule.DOC, action = "恢复", risk = RiskLevel.NORMAL,
            target = "'恢复' + #request.type() + ' × ' + #request.ids().size() + ' 项'")
    public RecycleDtos.RestoreResult restore(RecycleDtos.RestoreRequest request) {
        String type = normalizeType(request == null ? null : request.type());
        List<Long> ids = requireIds(request == null ? null : request.ids());
        Long targetKbId = request.targetKbId();
        return TYPE_KB.equals(type) ? restoreKbs(ids) : restoreDocs(ids, targetKbId);
    }

    private RecycleDtos.RestoreResult restoreDocs(List<Long> ids, Long targetKbId) {
        // ---- ① 全验（这一轮不做任何写：让"拒绝"是零副作用的）----
        List<DocInBin> found = new ArrayList<>(ids.size());
        int needTarget = 0;
        for (Long id : ids) {
            DocInBin doc = loadDocInBin(id);
            if (doc == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "有文档不在回收站里（可能已被彻底删除或已恢复）—— 请刷新后重试");
            }
            if (doc.originDeleted()) {
                needTarget++;
                if (targetKbId == null) {
                    throw BizException.of(ErrorCode.PARAM_INVALID,
                            "「" + doc.title() + "」的原知识库已删除，请指定要恢复到哪个知识库");
                }
                requireTargetKb(targetKbId);
            }
            found.add(doc);
        }

        // ---- ② 全恢复 ----
        int affected = 0;
        for (DocInBin doc : found) {
            if (doc.originDeleted()) {
                moveToTargetKb(doc, targetKbId);
            }
            entityManager.createNativeQuery(
                            "update sys_document set deleted_at = null, deleted_by = null, "
                                    + "updated_at = :now where id = :id")
                    .setParameter("now", LocalDateTime.now())
                    .setParameter("id", doc.id())
                    .executeUpdate();
            affected++;
        }
        String note = needTarget > 0
                ? "已恢复 " + affected + " 篇文档（其中 " + needTarget + " 篇移到了指定知识库）；"
                        + "需重建索引才能参与检索"
                : "已恢复 " + affected + " 篇文档；需重建索引才能参与检索";
        log.info("[恢复] 文档 {} 篇（需换库 {} 篇）", affected, needTarget);
        return new RecycleDtos.RestoreResult(affected, true, affected,
                needTarget > 0 ? targetKbId : null, note);
    }

    private RecycleDtos.RestoreResult restoreKbs(List<Long> ids) {
        int affected = 0;
        // ---- ① 全验 ----
        for (Long id : ids) {
            if (!existsInBin("select count(*) from sys_kb where id = :id and deleted_at is not null", id)) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "有知识库不在回收站里（可能已被彻底删除或已恢复）—— 请刷新后重试");
            }
        }
        // ---- ② 全恢复 + 数出连带的文档数 ----
        for (Long id : ids) {
            affected += (int) countOf("select count(*) from sys_document "
                    + "where kb_id = :kbId and deleted_at is null", null, id);
            entityManager.createNativeQuery(
                            "update sys_kb set deleted_at = null, deleted_by = null, updated_at = :now "
                                    + "where id = :id")
                    .setParameter("now", LocalDateTime.now())
                    .setParameter("id", id)
                    .executeUpdate();
        }
        log.info("[恢复] 知识库 {} 个（连带 {} 篇文档）", ids.size(), affected);
        return new RecycleDtos.RestoreResult(ids.size(), true, affected, null,
                "已恢复 " + ids.size() + " 个知识库（连带 " + affected + " 篇文档）；需重建索引才能参与检索");
    }

    /**
     * 把一篇文档**移到**目标知识库（原库已删的情形）。
     *
     * <p>四个动作**一个都不能漏** —— 它们照 {@code DocReindexService#moveToKb} 的既定做法：
     * <ol>
     *   <li>删它在**旧库分区**里的向量：`ChunkVectorizer` 将来写目标分区时只在
     *       **目标分区内**按 doc_id 删，旧分区它一个字节都不碰，而那是**另一个库**的分区
     *       ⇒ 不清就是一堆谁也认领不了的残留点；</li>
     *   <li>`sys_document` 换 `kb_id` + 退回 `INDEXING` + `index_generation = 0`；</li>
     *   <li>**`sys_chunk` 也要换 `kb_id`** —— 检索侧按 `sys_chunk.kb_id` join `sys_kb` 取密级，
     *       只改文档列会让分片继续按**旧库的密级**参与过滤（一个非常安静的越权口子）；</li>
     *   <li>向量化**不做**：本批统一回 `needReindex = true` 让用户自己决定何时重建
     *       （需求 §7.2 要的是"提示"）。</li>
     * </ol>
     */
    private void moveToTargetKb(DocInBin doc, Long targetKbId) {
        if (doc.indexGeneration() > 0) {
            vectorWriter.replaceDocument(
                    MilvusConstants.partitionName(doc.kbId(), doc.indexGeneration()),
                    doc.id(), List.of());
        }
        LocalDateTime now = LocalDateTime.now();
        entityManager.createNativeQuery("""
                        update sys_document
                           set kb_id = :target, status = 'INDEXING', index_generation = 0,
                               updated_at = :now
                         where id = :id
                        """)
                .setParameter("target", targetKbId).setParameter("now", now)
                .setParameter("id", doc.id()).executeUpdate();
        entityManager.createNativeQuery("update sys_chunk set kb_id = :target where doc_id = :id")
                .setParameter("target", targetKbId).setParameter("id", doc.id()).executeUpdate();
        log.info("[恢复] 文档换库：docId={} {} → {}（旧分区向量已清）",
                doc.id(), doc.kbId(), targetKbId);
    }

    // ==================================================================
    // 彻底删除 / 清空（`G7-12`）
    // ==================================================================

    /**
     * {@code DELETE /api/recycle/items} —— 彻底删除（🚩 不可逆）。
     *
     * <p>⚠️ **`deleted` 是"实际清掉的条数"而不是"请求条数"**：已经不在回收站里的 id
     * 会被**静默跳过**（不报错）。这是刻意的 —— `G7-13` 要求定时清理**幂等**，
     * 而"重入时对已消失的行报错"必然破坏幂等。用户连点两次也不会看到红字。
     */
    @Transactional
    @AuditLog(module = AuditModule.DOC, action = "彻底删除", risk = RiskLevel.HIGH,
            target = "'彻底删除' + #request.type() + ' × ' + #request.ids().size() + ' 项'")
    public RecycleDtos.PurgeResult purge(RecycleDtos.PurgeRequest request) {
        String type = normalizeType(request == null ? null : request.type());
        List<Long> ids = requireIds(request == null ? null : request.ids());
        return new RecycleDtos.PurgeResult(purgeAll(type, ids));
    }

    /**
     * {@code DELETE /api/recycle/all} —— 清空回收站（最高危，需求 §7.6）。
     *
     * <p>⚠️ **清空是"对所有条目逐个走同一条彻底删除"**，不是另一条代码路径 ——
     * 两条路径必然漂移，而这里漂移的后果是"某类条目没被清干净却报了 cleared=N"。
     */
    @Transactional
    @AuditLog(module = AuditModule.DOC, action = "清空回收站", risk = RiskLevel.HIGH)
    public RecycleDtos.ClearResult clearAll() {
        int cleared = 0;
        cleared += purgeAll(TYPE_DOC, allIds("select id from sys_document where deleted_at is not null"));
        cleared += purgeAll(TYPE_KB, allIds("select id from sys_kb where deleted_at is not null"));
        log.warn("[清空回收站] 清掉 {} 个条目（**不可逆**）", cleared);
        return new RecycleDtos.ClearResult(cleared);
    }

    // ==================================================================
    // 定时清理（`G7-13`）
    // ==================================================================

    /**
     * {@code POST /api/recycle/cleanup} —— 清理超期项。
     *
     * <h3>🔴 幂等是怎么成立的</h3>
     * <p>判据只看 `deleted_at < 今天 − 30 天`。清完之后那些行**已经不存在了**
     * ⇒ 第二次跑同一秒，那条 `select` 选不出任何 id ⇒ `cleaned = 0`。
     * 所以"幂等"不是靠一层去重，而是靠**查询条件本身只认未清理的行**。
     * <p>⚠️ 但**它需要 `purgeDoc` 对"行已不存在"保持安静**（返回而不抛）——
     * 否则并发/重入时第二次调用会抛，那同样破坏幂等。见 `DocLifecycleService#purgeDoc`。
     *
     * <p>⚠️ 本方法**不吞异常**：清理失败必须让调用方（定时任务）知道，由它去发告警
     * （需求 §7.9「失败要有告警（走通知中心）」）。
     */
    @Transactional
    public RecycleDtos.CleanupResult cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(KEEP_DAYS);
        int cleaned = purgeAll(TYPE_DOC, expiredIds("select id from sys_document", "deleted_at", cutoff));
        cleaned += purgeAll(TYPE_KB, expiredIds("select id from sys_kb", "deleted_at", cutoff));
        String note = cleaned == 0
                ? "没有超过 " + KEEP_DAYS + " 天的条目，无需清理"
                : "已清理 " + cleaned + " 个超过 " + KEEP_DAYS + " 天的条目";
        log.info("[回收站清理] cutoff={} cleaned={}", cutoff, cleaned);
        return new RecycleDtos.CleanupResult(cleaned, note);
    }

    /** 供 `RecycleCleanupJob` 与测试直调：**清理超期项**（与 `POST /recycle/cleanup` 同一段）。 */
    public RecycleDtos.CleanupResult cleanupExpired() {
        return cleanup();
    }

    /**
     * **提交**一次超期清理（`RecycleCleanupJob` 的入口，`T-616`）。
     *
     * <p>⚠️ 任务的业务坐标是 {@link AsyncTaskService.TaskSubject#none()} —— **三个都为空**。
     * 定时任务没有提交人，这是事实而不是遗漏；由此它也是本仓第一个走**广播通知**的类型
     * （见 `NoticePublisher#BROADCAST_TYPES` 那段"两种 NULL 成因"的说明）。
     *
     * <p>⚠️ 这个 taskId 与 `AsyncTaskInfo` 的口径一致，可被 `GET /doc/tasks/{taskId}` 轮询
     * （虽然没人会去轮询一个凌晨的任务 —— 它留在库里是为了**可查**）。
     */
    public String submitCleanup() {
        return asyncTaskService.submit(TASK_TYPE_CLEANUP,
                AsyncTaskService.TaskSubject.none(), null, 0, context -> {
                    context.stage("清理超期项");
                    RecycleDtos.CleanupResult result = cleanup();
                    java.util.Map<String, Object> payload = new java.util.HashMap<>();
                    payload.put("cleaned", result.cleaned());
                    payload.put("note", result.note());
                    context.complete(payload);
                });
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private int purgeAll(String type, List<Long> ids) {
        int n = 0;
        for (Long id : ids) {
            if (TYPE_KB.equals(type)) {
                docLifecycleService.purgeKb(id);
            } else {
                docLifecycleService.purgeDoc(id);
            }
            n++;
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    private List<Long> allIds(String sql) {
        return ((List<Number>) entityManager.createNativeQuery(sql).getResultList())
                .stream().map(Number::longValue).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Long> expiredIds(String selectFrom, String column, LocalDateTime cutoff) {
        return ((List<Number>) entityManager.createNativeQuery(
                        selectFrom + " where " + column + " is not null and " + column + " < :cutoff")
                .setParameter("cutoff", cutoff).getResultList())
                .stream().map(Number::longValue).toList();
    }

    private long countOf(String sql, String kw) {
        return countOf(sql, kw, null);
    }

    private long countOf(String sql, String kw, Long idParam) {
        Query q = entityManager.createNativeQuery(sql);
        if (kw != null) {
            q.setParameter("kw", kw);
        }
        if (idParam != null) {
            q.setParameter("kbId", idParam);
        }
        return ((Number) q.getSingleResult()).longValue();
    }

    private boolean existsInBin(String sql, long id) {
        return ((Number) entityManager.createNativeQuery(sql).setParameter("id", id)
                .getSingleResult()).longValue() > 0;
    }

    /** 回收站里的一篇文档 + 恢复时要用到的坐标。 */
    private record DocInBin(long id, long kbId, int indexGeneration, String title,
                            boolean originDeleted) {
    }

    @SuppressWarnings("unchecked")
    private DocInBin loadDocInBin(long docId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select d.id, d.kb_id, d.index_generation, d.title,
                               (k.deleted_at is not null)
                          from sys_document d
                          join sys_kb k on k.id = d.kb_id
                         where d.id = :id and d.deleted_at is not null
                        """)
                .setParameter("id", docId).getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        Object[] r = rows.getFirst();
        return new DocInBin(((Number) r[0]).longValue(), ((Number) r[1]).longValue(),
                r[2] == null ? 0 : ((Number) r[2]).intValue(), (String) r[3], bool(r[4]));
    }

    /**
     * 目标库必须**存在且未被软删**。
     *
     * <p>⚠️ 这里**刻意不套密级检查**：本接口只有超管能到（`doc:recycle`），
     * 而 `I-6` 的防降级是**写侧密级变更**的纪律（`DocAttributeService`/`DocReindexService`），
     * 不是在"把误删的东西放回去"这条路上。若哪天 `doc:recycle` 下放给非超管，
     * 这里要补的是**库级可见性 + 密级**两件事 —— 已登记在本批 §9。
     */
    private void requireTargetKb(Long targetKbId) {
        if (targetKbId == null || !existsInBin(
                "select count(*) from sys_kb where id = :id and deleted_at is null", targetKbId)) {
            throw BizException.of(ErrorCode.KB_NOT_FOUND,
                    "目标知识库不存在或已被删除，请选择另一个知识库");
        }
    }

    private static String normalizeType(String type) {
        String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (!TYPE_DOC.equals(t) && !TYPE_KB.equals(t)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "type 只能是 DOC 或 KB（当前值：" + (type == null ? "空" : type) + "）");
        }
        return t;
    }

    private static List<Long> requireIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请先选择要操作的条目");
        }
        List<Long> cleaned = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (cleaned.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请先选择要操作的条目");
        }
        return cleaned;
    }

    /**
     * 剩余保留天数 = {@code 30 − (今天 − 删除日)}，夹在 {@code [0, 30]}。
     *
     * <p>⚠️ **按"日"而不是"小时"算**：需求 §7.10 的判据原文是
     * 「剩余天数 = 30 −（今天 − 删除日）」—— 按小时算会让"今天删的"与"昨天删的"
     * 在跨零点那一刻突然差出一天，而页面上的三档色阶（`G7-10`）正是按这个数染的。
     */
    private static int remainDays(LocalDateTime deletedAt) {
        if (deletedAt == null) {
            return 0;
        }
        long between = ChronoUnit.DAYS.between(deletedAt.toLocalDate(), java.time.LocalDate.now());
        return (int) Math.max(0, Math.min(KEEP_DAYS, KEEP_DAYS - between));
    }

    private static long num(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static String text(Object value) {
        return value == null ? UNKNOWN_BY : (String) value;
    }

    /**
     * 读一个 SQL **布尔表达式**（{@code (k.deleted_at is not null)} 这类）。
     *
     * <p>🔴 <b>必须同时收 {@link Boolean} 与 {@link Number}，否则它恒为 false</b>。
     * MySQL 驱动默认 {@code tinyInt1isBit=true}，只有 `TINYINT(1)` **列**才回 Boolean；
     * 而本类的这几处是**表达式**（类型是 BIGINT），回来的是 1/0。
     * 第一版写成 {@code Boolean.TRUE.equals(value)} ⇒ 对 {@code 1L} 恒 false
     * —— 后果是「原库已删，请选目标知识库」那条分支**永远进不去**：
     * 用户把文档恢复到一个已经不存在的库里，接口报成功，而列表上那篇文档
     * 谁也再看不到（`G7-11` 的失败态）。本批的用例抓到过这个形态。
     *
     * <p>与 {@code DocService#boolOf} 同一条口径、同一段实现 —— `T-O04` 从那头
     * 踩过同一个坑（现象是整个列表接口 500，报的却是 {@code Boolean cannot be cast to Number}）。
     */
    private static boolean bool(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return ((Number) value).intValue() != 0;
    }

    private static String time(LocalDateTime t) {
        return t == null ? null : t.format(TIME_FORMAT);
    }

    /** 时间列在 JDBC 侧可能是 {@code Timestamp} 也可能是 {@code LocalDateTime}，两者都接。 */
    private static LocalDateTime toTime(Object value) {
        if (value instanceof LocalDateTime t) {
            return t;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return null;
    }
}
