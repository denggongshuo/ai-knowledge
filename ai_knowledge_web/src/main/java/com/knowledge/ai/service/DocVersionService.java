package com.knowledge.ai.service;

import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.enums.DocStatus;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.dto.doc.DocDtos;
import com.knowledge.ai.pipeline.vectorize.ChunkVectorizer;
import com.knowledge.ai.pipeline.vectorize.VectorWriter;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档版本历史（`T-606` 列表 · `T-607` 对比 · `T-608` 回滚 · `T-609` 标签）。
 *
 * <h3>🔴 一个版本 = 一条 {@code sys_document} 行</h3>
 * <p>选型写在 `V39` 的头注与计划 §5：分片 / 向量 / 检索 trace **全部按 {@code doc_id}** 组织，
 * 所以"每个版本自成一条 doc 行"时，历史版本的分片与引用**天然独立保留** ——
 * 回滚只需复制分片再向量化，既不用重解析，也不用另一套分片存储。
 * 组身份靠 {@code (doc_group_id, version_no)}，当前版本靠 {@code is_current}。
 *
 * <h3>🔴 回滚的失败方向是选过的，不是随手排的</h3>
 * <p>顺序：建新版本行 → 复制目标版本分片 → 向量化 → **成功才**翻旧当前行 + 清其向量。
 * <pre>
 *   建行(INDEXING,is_current=1) → 复制分片 → 向量化 → 翻旧行(is_current=0) + 清旧向量
 * </pre>
 * 为什么**不**先把旧行翻 0：那样在"新版本向量还没写完"的窗口里，整组**没有任何可召回内容**
 * —— 用户点一次回滚，问答里那篇文档凭空消失几秒。现在这个顺序下，旧内容一直可召回，
 * 直到新版本真的就绪（新行此刻没有向量 ⇒ 它一条结果也召不回 ⇒ **不会出现重复结果**）。
 *
 * <p>向量化**没成功**时（开关关闭 / 代已变更），新行翻回 {@code is_current=0} 并标 FAILED，
 * 旧当前行**保持** current —— 组内"恰有一行 is_current=1"的不变量不破，且不会出现
 * 一个"没有内容的当前版本"顶掉还有内容的那一版。⚠️ 这种情况下本方法**抛异常**而不是
 * 返回 {@code rolledBack=false}：回滚没生效却回一个成功体，正是本仓最忌讳的"报告说成功"。
 *
 * <h3>⚠️ 本类用 {@code entityManager} **只读，不写**</h3>
 * <p>写库全部经 {@link DocRecordWriter} 的 {@code @Transactional} 方法。这不是风格问题：
 * {@code Query#executeUpdate()} 在**没有活跃事务**时会抛 {@code TransactionRequiredException}
 * （本批证伪时实测到 —— 在 {@code rollback} 里直接加一句原生 delete，接口当场回 9999）。
 * 而 {@code rollback} 本身**刻意不是** {@code @Transactional}：它跨 Milvus 与多次独立提交，
 * 包成一个大事务会把长事务与"不可回滚的向量写入"混在一起 —— 回滚掉已经写进 Milvus 的向量
 * 是做不到的，那正是本仓反复钉过的取舍。
 *
 * <h3>⚠️ 回滚**不碰** {@code sys_retrieval_trace_chunk}</h3>
 * <p>历史问答的引用锚定在那张表的 {@code chunk_id/doc_id}（{@code V22:140}）。回滚只新增版本行、
 * 一行都不删 ⇒ 旧引用仍指向旧内容，这就是需求 §6.4「回滚后历史问答的引用不会改变」的实现。
 * **那件事是靠"什么都不做"保证的**，所以这里没有任何一行代码在写它 —— 改这里之前先读这条。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocVersionService {

    /** 与 {@link DocDetailService} / {@code DocService} 的列表同一格式（前端直接展示，不解析）。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final EntityManager entityManager;

    private final DocAccessService docAccessService;

    private final DocRecordWriter recordWriter;

    private final ChunkReader chunkReader;

    private final ChunkVectorizer chunkVectorizer;

    private final VectorWriter vectorWriter;

    // ==================================================================
    // T-606 · 版本列表
    // ==================================================================

    /**
     * {@code GET /api/doc/{id}/versions} —— 全组版本，**按 {@code version_no} 降序**。
     *
     * <p>入参是**组内任意一版**的 doc id（不是"当前版本 id"）：页面按 id 打开某一版时，
     * 也要能看到它的兄弟版本。因此这里先由 id 查 {@code doc_group_id} 再取全组。
     *
     * <p>⚠️ {@code current} 取**带 {@code is_current=1} 的那一行**的版本号，不是
     * {@code max(version_no)}：回滚失败会留下一行"版本号更高但不是当前版本"的行，
     * 用 max 会把一个已作废的版本报成当前版本。找不到当前行（数据异常）时才回退到 max。
     */
    @Transactional(readOnly = true)
    public DocDtos.DocVersionList list(long docId) {
        docAccessService.requireVisibleDoc(docId);
        List<VersionRow> rows = loadGroup(requireGroupId(docId));

        VersionRow currentRow = rows.stream().filter(VersionRow::current).findFirst().orElse(null);
        int maxVersion = rows.stream().mapToInt(VersionRow::versionNo).max().orElse(0);
        if (currentRow == null) {
            log.warn("[版本] 组内没有 is_current=1 的行（数据异常），current 回退为最大版本号：docId={} groupRows={}",
                    docId, rows.size());
        }
        String current = "v" + (currentRow == null ? maxVersion : currentRow.versionNo());

        List<DocDtos.DocVersionRow> list = new ArrayList<>(rows.size());
        for (VersionRow r : rows) {
            // `hasNewer` = 组内存在**更高版本号**的行（需求 §6.4 的「已有更新版本」标记）。
            // 按 version_no 判而不是按 id：回滚产出的新行 id 更大但可能版本号更低吗？不会 ——
            // 版本号恒为 max+1。用 version_no 是因为它才是"新旧"的口径，id 只是插入顺序
            boolean hasNewer = rows.stream().anyMatch(o -> o.versionNo() > r.versionNo());
            list.add(new DocDtos.DocVersionRow("v" + r.versionNo(), r.size(), r.chunkCount(),
                    r.ownerName(), time(r.createdAt()), r.current(), r.remark(), hasNewer));
        }
        return new DocDtos.DocVersionList(current, list);
    }

    // ==================================================================
    // T-607 · 版本对比（**仅元数据**，文本 diff 接口预留）
    // ==================================================================

    /**
     * {@code GET /api/doc/{id}/versions/{v}/diff?against={v2}} —— 两版本的元数据对照。
     *
     * <p>🔴 {@code textDiff} **恒为 {@code null}**：需求 §6.8 逐字写「v1 只做元数据对比 +
     * 文本 diff 接口预留」。它不是"这次没算出来"，是**本版不做**（见 {@code DocDtos.VersionDiff}）。
     *
     * @param against 对照版本号；**留空 = 与当前版本比**（页面上的「对比」按钮就是那个语义）。
     *                与 {@code version} 相同则拒绝 —— "和自己比"会回一份全等的数据，
     *                看起来像"两版没差别"，而那是**一句关于内容的错误断言**
     */
    @Transactional(readOnly = true)
    public DocDtos.VersionDiff diff(long docId, String version, String against) {
        docAccessService.requireVisibleDoc(docId);
        List<VersionRow> rows = loadGroup(requireGroupId(docId));

        VersionRow left = findByVersion(rows, version, docId);
        VersionRow right;
        if (against == null || against.isBlank()) {
            right = rows.stream().filter(VersionRow::current).findFirst()
                    .orElseThrow(() -> BizException.of(ErrorCode.PARAM_INVALID,
                            "这篇文档当前没有生效版本，无法与 v" + version + " 对比 —— 请先在版本列表里确认。"));
        } else {
            right = findByVersion(rows, against, docId);
        }
        if (left.versionNo() == right.versionNo()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "v" + left.versionNo() + " 与对照版本是同一个版本，无需对比。");
        }
        return new DocDtos.VersionDiff(metaOf(left), metaOf(right), null);
    }

    // ==================================================================
    // T-608 · 回滚（G7-6）
    // ==================================================================

    /**
     * {@code POST /api/doc/{id}/rollback}（请求体 {@code {version}}）—— 回到某一版。
     *
     * <p>🔴 **不改写历史**：回到 {@code v1} 是**新增**一条版本（内容抄自 {@code v1}、
     * {@code is_current=1}），旧当前版本翻 {@code is_current=0} 并清其向量。
     * 版本行**一行都不删** ⇒ 组内版本数只增不减（需求 §6.4「保留一条新版本记录而不是
     * 改写历史，保证可追溯」）。
     *
     * <p>⚠️ 权限码复用 {@code doc:update}（计划 §3.4 假设 6）：回滚是"管理这篇文档"的动作，
     * 与属性修改同属一类。若审阅认为"覆盖索引"该独立授权，另立 {@code doc:rollback}。
     *
     * @param version 目标版本号（{@code "v2"} 或 {@code "2"} 都接受）
     * @throws BizException 目标版本不属于本组 / 就是当前版本 / 该版本分片不可用 / 向量化未生效
     */
    @AuditLog(module = AuditModule.DOC, action = "版本回滚", risk = RiskLevel.NORMAL,
            target = "'文档#' + #docId")
    public DocDtos.RollbackResult rollback(long docId, String version) {
        DocAccessService.DocRow doc = docAccessService.requireVisibleDoc(docId);
        DocStatus status = statusOf(doc.status());
        if (status.inProgress()) {
            // 库里正在跑解析/索引 ⇒ 此刻回滚会与那条流水线抢"谁是当前版本"。
            // 拒绝而不是排队：用户要的是"立刻回到 v1"，排到解析后面并不是他要的事
            // ⚠️ 这道检查必须留在**建行之前**：挪到 insertRollbackVersion 之后，
            //     一次被拒的请求会在版本列表里留下一条作废行（判据 11 会当场转红，
            //     本批实测：那个用例的"组内仍只有两版"变成 3）
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "这篇文档正在" + status.getLabel() + "，请等它跑完再回滚（重复提交不会加快它）。");
        }

        List<VersionRow> rows = loadGroup(requireGroupId(docId));
        VersionRow currentRow = rows.stream().filter(VersionRow::current).findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.PARAM_INVALID,
                        "这篇文档当前没有生效版本，无法回滚 —— 请先在版本列表里确认。"));
        VersionRow target = findByVersion(rows, version, docId);
        if (target.versionNo() == currentRow.versionNo()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "v" + target.versionNo() + " 已经是当前版本，无需回滚。");
        }
        if (target.chunkCount() <= 0) {
            // §3.4 假设 2 的处置：**不静默产出空索引**。回滚回去只会得到"一篇没有内容的当前版本"
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "v" + target.versionNo() + " 没有可用的分片，回滚它会得到一篇空索引。"
                            + "请改用「重建索引」重新解析它的原始文件。");
        }

        // 回滚的"上传人"是**触发回滚的人**：这一行版本记录是他造的（见 insertRollbackVersion 的注释）
        LoginUser user = LoginUserContext.require();
        DocRecordWriter.RollbackInsert inserted = recordWriter.insertRollbackVersion(
                doc.kbId(), target.docId(), currentRow.groupId(), user.getUserId(),
                "回滚到 v" + target.versionNo());
        int copied = recordWriter.copyChunks(target.docId(), inserted.docId(), doc.kbId());
        if (copied == 0) {
            // 预检过了仍为空 ⇒ 检查与复制之间分片被清掉了。新行必须**退出当前版本**，
            // 否则组里会留下一个空壳当前版本（见类注释的失败方向）
            abandon(inserted.docId(), doc.kbId(), "该版本的分片已不可用，回滚未生效。");
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "v" + target.versionNo() + " 的分片在回滚过程中不可用了，本次回滚未生效，"
                            + "当前版本仍是 v" + currentRow.versionNo() + "。");
        }

        int generation = recordWriter.currentKbGeneration(doc.kbId());
        ChunkVectorizer.Result vectorized = chunkVectorizer.vectorize(
                inserted.docId(), doc.kbId(), generation, chunkReader.readByDocId(inserted.docId()),
                // 代复核照 `DocParseService` / `DocReindexService` 的写法：写 Milvus 之前
                // 重问一次"还是当初那一代吗"（`GenerationGuard` 的位置就在落盘前）
                () -> {
                    int currentGen = recordWriter.currentKbGeneration(doc.kbId());
                    if (currentGen != generation) {
                        throw new ChunkVectorizer.GenerationChangedException(
                                "该知识库的索引代已变更（本次回滚持有第 " + generation
                                        + " 代，当前第 " + currentGen + " 代）—— 本次放弃写入向量");
                    }
                });

        if (!vectorized.allowsReady()) {
            // 开关关闭 / 代变更 ⇒ 强行把旧行翻 0 会让整组没有可召回内容。
            // 如实放弃：新行退出当前版本、标 FAILED，旧当前行不动
            abandon(inserted.docId(), doc.kbId(),
                    "回滚的向量写入未执行：" + vectorized.detail());
            throw BizException.of(ErrorCode.SYSTEM_ERROR,
                    "回滚未生效（" + vectorized.detail() + "），当前版本仍是 v"
                            + currentRow.versionNo() + "。");
        }

        // 到这里向量已经写齐 ⇒ 才把旧当前行移出"当前版本"并清它的向量。
        // ⚠️ 顺序不能颠倒（见类注释）：先翻后清才是"新版本已就绪，旧版本退场"
        recordWriter.markVectorAttribution(inserted.docId(),
                vectorized.modelRef().model(), vectorized.modelRef().version());
        recordWriter.markReady(inserted.docId(), generation);
        recordWriter.markNotCurrent(currentRow.docId());
        int removed = vectorWriter.replaceDocument(
                MilvusConstants.partitionName(doc.kbId(), currentRow.generation()),
                currentRow.docId(), List.of());

        String note = "已回滚到 v" + target.versionNo() + "（保留新版本记录，可追溯）";
        log.info("[版本回滚] docId={} 组={} v{} → 新 v{} (doc {}) · 目标库第 {} 代 · 旧当前行 {} 清掉 {} 条向量",
                docId, currentRow.groupId(), currentRow.versionNo(), inserted.versionNo(),
                inserted.docId(), generation, currentRow.docId(), removed);
        return new DocDtos.RollbackResult(inserted.docId(), "v" + inserted.versionNo(), true, note);
    }

    // ==================================================================
    // T-609 · 标签（**契约保留，最小实现**）
    // ==================================================================

    /**
     * {@code GET /api/doc/{id}/tags} —— 文档级内容标签。
     *
     * <p>⚠️ **恒回空数组，且这是"如实"不是"缺陷"**（计划 §3.4 假设 4）：文档级标签
     * **至今没有归属表**（{@code IndexEnhancer} 的类 javadoc 自己写着这件事），
     * 所以没有任何地方能产出标签。回 {@code []} 与 {@code DocMeta.tags} 的现状一致。
     * 🔴 不要为了让这一格"看起来有东西"而编造标签 —— 那是**一句关于内容的事实断言**。
     *
     * <p>可见性判定照旧先走一遍：**空数组也不能成为"这个 id 存不存在"的探测器**
     * （不判的话，任何人拿一个别的部门的 doc id 都能得到 200 而不是 403）。
     */
    @Transactional(readOnly = true)
    public List<String> tags(long docId) {
        docAccessService.requireVisibleDoc(docId);
        return List.of();
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /** 把一行回滚版本**退出当前版本**并标失败（向量化没成 / 分片不可用两条路共用）。 */
    private void abandon(long newDocId, long kbId, String reason) {
        recordWriter.markNotCurrent(newDocId);
        recordWriter.markFailed(newDocId, reason);
        log.warn("[版本回滚] 新版本已作废（旧当前版本保持不变）：kbId={} newDocId={} 原因={}",
                kbId, newDocId, reason);
    }

    /** 由组内任意一版的 doc id 查它的版本组。 */
    private long requireGroupId(long docId) {
        List<?> rows = entityManager.createNativeQuery(
                        "select doc_group_id from sys_document where id = :id")
                .setParameter("id", docId)
                .getResultList();
        Object raw = rows.isEmpty() ? null : rows.getFirst();
        if (raw == null) {
            // `V39` 回填后逻辑上恒非空 ⇒ 走到这里说明数据异常。
            // **不当成 0**（0 会让它在"组 0"里与所有同样异常的行混成一堆，比报错更难查）
            throw BizException.of(ErrorCode.SYSTEM_ERROR,
                    "这篇文档没有版本组标识（数据异常），无法查询版本历史，请反馈。");
        }
        return ((Number) raw).longValue();
    }

    /**
     * 取一组版本，按 {@code version_no} 降序。
     *
     * <p>⚠️ 带 {@code deleted_at is null}：软删的版本**不展示**。删文档（`T-611`）走软删，
     * 而"回收站里那一版还在版本列表上挂着"会让页面显示一条点不开的记录。
     */
    @SuppressWarnings("unchecked")
    private List<VersionRow> loadGroup(long groupId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select d.id, d.version_no, d.size_bytes, d.chunk_count, d.table_total,
                               d.is_current, d.remark, d.created_at, d.index_generation,
                               u.real_name, u.username
                          from sys_document d
                          left join sys_user u on u.id = d.owner_id
                         where d.doc_group_id = :groupId and d.deleted_at is null
                         order by d.version_no desc
                        """)
                .setParameter("groupId", groupId)
                .getResultList();

        List<VersionRow> list = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            list.add(new VersionRow(
                    ((Number) r[0]).longValue(),
                    ((Number) r[1]).intValue(),
                    num(r[2]),
                    num(r[3]),
                    num(r[4]),
                    isTrue(r[5]),
                    (String) r[6],
                    toTime(r[7]),
                    r[8] == null ? 0 : ((Number) r[8]).intValue(),
                    groupId,
                    ownerName(r[9], r[10])));
        }
        return list;
    }

    /** 按版本号在组内找一行；找不到就**说清是哪一版不属于这篇文档**（不是笼统的"参数错"）。 */
    private static VersionRow findByVersion(List<VersionRow> rows, String version, long docId) {
        int versionNo = parseVersion(version);
        return rows.stream().filter(r -> r.versionNo() == versionNo).findFirst()
                .orElseThrow(() -> BizException.of(ErrorCode.PARAM_INVALID,
                        "v" + versionNo + " 不属于文档 #" + docId + " 的版本组"
                                + "（该组现有版本：" + rows.stream().map(r -> "v" + r.versionNo()).toList() + "）。"));
    }

    /**
     * 版本号字符串 → 版本号。
     *
     * <p>接受 {@code "v2"} 与 {@code "2"}：前者是**接口与前端之间**的展示形态
     * （{@code DocDtos.DocVersionRow#version}），后者是库里 {@code version_no} 的口径。
     * ⚠️ 不做"宽容解析"（不去空格、不认 {@code "V2"}）—— 认不出的当场拒，
     * 否则 {@code "v2 "} 会静默变成 v2，而"静默修正用户的输入"在本项目是一条明确的反对口径
     */
    static int parseVersion(String version) {
        if (version == null || version.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请指定版本号（形如 v2）。");
        }
        String body = version.startsWith("v") ? version.substring(1) : version;
        try {
            int no = Integer.parseInt(body);
            if (no <= 0) {
                throw new NumberFormatException(body);
            }
            return no;
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "版本号「" + version + "」无法识别 —— 请用 v1 / v2 这种形式（对应版本列表里的那一列）。");
        }
    }

    private static DocDtos.VersionDiff.VersionMeta metaOf(VersionRow r) {
        return new DocDtos.VersionDiff.VersionMeta("v" + r.versionNo(), r.size(), r.chunkCount(),
                r.tableTotal(), r.ownerName(), time(r.createdAt()));
    }

    /**
     * 上传人展示名：真名 → 用户名 → 「未知」。
     *
     * <p>⚠️ 与 {@code DocDetailService} 对 {@code owner} 的处理**同一口径**（那边缺真名回「—」）：
     * 这里回「未知」而不是 null，是因为版本列表是一张**表格**，一格空着看起来像"这列没数据"，
     * 而事实是"这条版本的上传人账号已被删除"。两种情形要做的事不同（一个是看别处，一个是查账号）
     */
    private static String ownerName(Object realName, Object username) {
        if (realName != null && !String.valueOf(realName).isBlank()) {
            return String.valueOf(realName);
        }
        if (username != null && !String.valueOf(username).isBlank()) {
            return String.valueOf(username);
        }
        return "未知";
    }

    private static DocStatus statusOf(String raw) {
        try {
            return DocStatus.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            // 认不出的状态当"不可回滚"处理更安全（放行会与一条状态不明的流水线抢当前版本）
            throw BizException.of(ErrorCode.PARAM_INVALID, "这篇文档的状态无法识别：" + raw);
        }
    }

    private static String time(LocalDateTime value) {
        return value == null ? null : value.format(TIME_FORMAT);
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

    private static long num(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    /** MySQL 的 {@code TINYINT(1)} 经原生查询回来是 {@code Integer}（0/1），也可能是 {@code Boolean}。 */
    private static boolean isTrue(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && ((Number) value).intValue() != 0;
    }

    /** 组内一行版本。内部结构，不外露 —— 它比 {@code DocVersionRow} 多带回滚要用的列。 */
    private record VersionRow(long docId, int versionNo, long size, long chunkCount, long tableTotal,
                              boolean current, String remark, LocalDateTime createdAt, int generation,
                              long groupId, String ownerName) {
    }
}
