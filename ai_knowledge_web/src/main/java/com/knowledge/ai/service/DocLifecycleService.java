package com.knowledge.ai.service;

import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.core.storage.DocStorage;
import com.knowledge.ai.core.storage.MilvusPartitionManager;
import com.knowledge.ai.dto.doc.DocDtos;
import com.knowledge.ai.pipeline.vectorize.VectorWriter;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 文档生命周期（`T-611` 软删 · `T-616` 的彻底删除半边）。
 *
 * <h3>🔴 两个动作的差别不是"程度"，是"能不能撤回"</h3>
 * <ul>
 *   <li>{@link #softDelete} —— **移入回收站**：只写 `deleted_at` + `deleted_by`。
 *       对象**一个字节都不动**、向量**一个点都不动**（`G7-14` 的正面要求：
 *       "软删后向量仍在"）。30 天内随时可恢复。</li>
 *   <li>{@link #purgeDoc} / {@link #purgeKb} —— **彻底删除**：不可逆。
 *       删什么、留什么**照 `O7` 规则表**逐格来（源文件删 · 解析产物删 · 向量删 ·
 *       审计日志**永久保留**）。</li>
 * </ul>
 * <p>所以本类里每个方法的第一段注释都在回答同一个问题：**"它会动什么？"**
 * —— 一份写错的"删除"是最难发现的那类缺陷（现场已经没了）。
 *
 * <h3>为什么彻底删除的顺序是「对象 → 向量 → 行」</h3>
 * <p>反过来（先删行）一旦对象删除失败，那些对象的**唯一线索**（`storage_path`）就没了
 * ⇒ 它们变成谁也找不到的孤儿。按这个顺序，失败时行还在、重试即可
 * （MinIO 的 remove 与 Milvus 的 delete 都是幂等的）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocLifecycleService {

    private final EntityManager entityManager;

    /** 可见性判定的**唯一**落点（`I-7`）—— 本类的四个方法全部先过它。 */
    private final DocAccessService docAccessService;

    private final DocStorage docStorage;

    private final VectorWriter vectorWriter;

    /** 按代分区（`T-406`）—— 库级彻底删除要按 `kbId` 找出**全部代**再逐个丢。 */
    private final MilvusPartitionManager partitionManager;

    /**
     * `T-C00` 的**清理面** —— 图里的边也是"这篇文档的产物"，删文档时要一并清。
     *
     * <p>🔴 <b>不接这一步就是一个新开的泄漏面</b>：文档行删了、对象删了、向量删了，
     * 而图里的边永远留着，**任何清扫都发现不了它**（`S7-M06-D` 的 `#130` 族形状第四次 ——
     * "删除与自检都依赖同一个会被删掉的父行"）。
     */
    private final com.knowledge.ai.pipeline.graph.GraphWriter graphWriter;

    // ==================================================================
    // `T-611` · 软删（移入回收站）
    // ==================================================================

    /**
     * {@code DELETE /api/doc/{id}} —— 单篇移入回收站。
     *
     * <p>🔴 <b>本方法不删向量、不删对象</b>（`G7-14`）。判据 5 会直查 Milvus 条数与
     * MinIO 对象大小，证明"软删之后它们还在" —— 那是**恢复得起**的前提。
     */
    @Transactional
    @AuditLog(module = AuditModule.DOC, action = "删除文档", risk = RiskLevel.HIGH,
            target = "'文档#' + #docId")
    public DocDtos.DocDeleted softDelete(long docId) {
        LoginUser user = LoginUserContext.require();
        // 授权边界：不存在/已软删 ⇒ 10044；库不可见 ⇒ 10022（HTTP 403）
        docAccessService.requireVisibleDoc(docId);
        markDeleted(docId, user.getUserId());
        log.info("[删除文档] docId={} by={}（移入回收站，保留 30 天；对象与向量均未动）",
                docId, user.getUserId());
        return new DocDtos.DocDeleted(docId, true);
    }

    /**
     * {@code DELETE /api/doc/batch} —— 批量移入回收站。
     *
     * <p>🔴 <b>先全验后全删</b>（承 `S7-M06-E` 的硬口径）：批里**任一**文档不存在或不可见
     * ⇒ **整批拒绝**，一条都不软删。理由与打包下载同一条 —— "跳过不通过的、其余照做"
     * 会让用户看到「已删除 N 项」而实际只删了其中几项，而**少的那几项往往正是他以为删掉的**
     * （回收站里看得见，但用户不会去核）。
     *
     * <p>⚠️ 由此推出一条**已知语义**：重复删除同一篇（或别人刚删过）会拿到 `10044`
     * 而不是"幂等成功"。取这个取舍是因为 `requireVisibleDoc` **看不见**软删的行
     * （它本来就要把软删的挡在外面），想区分"从没存在"与"已被删"就得绕开那条唯一授权边界
     * —— 代价远大于收益。用户看到的是"文档不存在"，刷新即知。
     */
    @Transactional
    @AuditLog(module = AuditModule.DOC, action = "删除文档", risk = RiskLevel.HIGH,
            target = "'批量删除 ' + #docIds.size() + ' 篇'")
    public DocDtos.DocBatchDeleted batchSoftDelete(List<Long> docIds) {
        LoginUser user = LoginUserContext.require();
        List<Long> ids = requireDocIds(docIds);

        // ① 先全验 —— 这一轮**不做任何写**，就是为了让"拒绝"是零副作用的
        for (Long docId : ids) {
            docAccessService.requireVisibleDoc(docId);
        }
        // ② 再全删
        for (Long docId : ids) {
            markDeleted(docId, user.getUserId());
        }
        log.info("[删除文档] 批量 {} 篇 by={}（移入回收站）", ids.size(), user.getUserId());
        return new DocDtos.DocBatchDeleted(ids.size());
    }

    /**
     * 写软删两列。
     *
     * <p>⚠️ {@code and deleted_at is null} 是**幂等护栏**而不是可选项：没有它，重复删除会
     * 把 `deleted_at` 刷新成新的时刻 —— 而回收站的**剩余天数**正是从它算的
     * ⇒ "再点一次删除"就会让一份快过期的文档**重新获得 30 天**。那是用量极小的、
     * 但真实存在的绕过保留期的方式。
     */
    private void markDeleted(long docId, long userId) {
        LocalDateTime now = LocalDateTime.now();
        entityManager.createNativeQuery(
                        "update sys_document set deleted_at = :now, deleted_by = :by, updated_at = :now "
                                + "where id = :id and deleted_at is null")
                .setParameter("now", now)
                .setParameter("by", userId)
                .setParameter("id", docId)
                .executeUpdate();
    }

    // ==================================================================
    // `T-616` · 彻底删除（不可逆）
    // ==================================================================

    /**
     * **物理删除一篇文档**（不可逆）—— 由 `RecycleService` 在"彻底删除/到期清理"时调用。
     *
     * <p>删什么照 `O7` 规则表第 ② 列：源文件 `documents/` **删** · 解析产物 `parsed/` **删** ·
     * 向量**删**（该文档在它那一代分区里的点）。
     * <b>不删</b>：`sys_audit_log`（永久保留）· `sys_download_record`（下载是既成事实）·
     * `sys_retrieval_trace_chunk`（历史问答的引用锚，`S7-M06-D` 立的口径"一字不改"）。
     *
     * <p>还删两处**派生行**：`sys_chunk` 与 `sys_parse_issue` —— 它们是这篇文档的**内容派生物**，
     * 主行没了它们就是孤儿（本仓的探针清理正是按 `kb_id` 直删分片，因为"分片引用的文档行
     * 可能已经不在了"）。
     *
     * <h3>🔴 它为什么自己查一次坐标，而不是复用 {@code requireVisibleDoc}</h3>
     * <p>后者**结构上看不见软删的行**（它必须把软删的挡在外面）—— 而本方法处理的**正是**
     * 软删的行。所以这里不能复用它。但也**绝不能因此新开一个"第二套可见性口径"**
     * （`I-7`）：本方法**只取坐标、不判权限**，权限由唯一的入口把关 ——
     * 回收站那两条写接口挂的是 `doc:recycle`（**只有超管有**，`V2` 的 CROSS JOIN），
     * 语义是「**在回收站里看得到它 ⇒ 就能清它**」。
     */
    @Transactional
    public void purgeDoc(long docId) {
        PurgeCoords doc = loadForPurge(docId);
        if (doc == null) {
            // 已经被清掉了（并发/重入）。**幂等**：不报错、也不产第二份审计。
            // 这一条是定时清理要的 —— 见 `RecycleService#cleanup` 的幂等判据。
            log.info("[彻底删除] 文档已不存在，跳过：docId={}", docId);
            return;
        }
        // 🔴 **在破坏性原语内部再挡一次**：本方法物理删行，而它的入参只有 docId。
        //    万一将来有人从别处（或一条写错的脚本）拿一个**活跃文档**的 id 调进来，
        //    那就是"绕过回收站直接硬删" —— 不可逆、且不留回收站记录。
        //    ⚠️ 上面那个 `loadForPurge == null` 的幂等分支**挡不住它**：
        //    活跃文档的行是存在的，只是 `deleted_at` 为空。
        if (doc.deletedAt() == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "只有回收站里的文档才能彻底删除；该文档仍在正常状态，请先删除它");
        }

        // ① 源文件与解析产物（`O7` ②）—— 先于删行，理由见类注释
        docStorage.remove(doc.storagePath());
        docStorage.remove(doc.parsePath());

        // ② 向量 —— ⚠️ 只在"真的被向量化过"时才清：`index_generation = 0` 表示它
        //    从没进过任何一个代分区，而去清一个不存在的分区是**报错**不是返回空
        //    （`ReindexService#clearIndex` 的注释实测过这条：报 65535）。
        if (doc.indexGeneration() > 0) {
            vectorWriter.replaceDocument(
                    MilvusConstants.partitionName(doc.kbId(), doc.indexGeneration()),
                    doc.id(), List.of());
        } else {
            log.info("[彻底删除] 文档从未向量化，跳过清向量：docId={}", doc.id());
        }

        // ③ 图库（`T-C00`）：实体关系也是这篇文档的产物。
        //    ⚠️ 放在**删主行之前**：`GraphWriter` 按 `(:Document{docId})` 找，
        //    而那个节点与 MySQL 的行是两回事（它不会因为删了行就消失）——
        //    顺序其实无所谓，但放在这里读起来是"清理产物"的一部分。
        //    ⚠️ 它**永不抛**（`07:523`），所以不会让这次彻底删除失败；
        //    失败时 GraphWriter 自己记 error，本方法照常往下走。
        graphWriter.deleteDocument(doc.id(), "文档被彻底删除");

        // ④ 派生行
        entityManager.createNativeQuery("delete from sys_chunk where doc_id = :docId")
                .setParameter("docId", doc.id()).executeUpdate();
        entityManager.createNativeQuery("delete from sys_parse_issue where doc_id = :docId")
                .setParameter("docId", doc.id()).executeUpdate();

        // ⑤ 主行（**提交点**：这一步之前失败都还是"可重试"，之后就不再有这根线索了）
        entityManager.createNativeQuery("delete from sys_document where id = :id")
                .setParameter("id", doc.id()).executeUpdate();
        log.info("[彻底删除] docId={} kbId={} 标题={}（对象 + 向量 + 图 + 派生行 + 主行都已清）",
                doc.id(), doc.kbId(), doc.title());
    }

    /**
     * 彻底删除所需的坐标 —— **刻意不含密级/属主**：那些是可见性判定要的，这里一个都不用。
     *
     * <p>⚠️ 多带一个 {@code deletedAt} 是为了**在破坏性原语内部**再挡一次"没进回收站的行"，
     * 见 {@link #purgeDoc(long)} 里那段。
     */
    private record PurgeCoords(long id, long kbId, int indexGeneration, String title,
                               String storagePath, String parsePath, LocalDateTime deletedAt) {
    }

    private PurgeCoords loadForPurge(long docId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select id, kb_id, index_generation, title, storage_path, parse_path, deleted_at "
                                + "from sys_document where id = :id")
                .setParameter("id", docId)
                .getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        Object[] r = rows.getFirst();
        return new PurgeCoords(((Number) r[0]).longValue(), ((Number) r[1]).longValue(),
                r[2] == null ? 0 : ((Number) r[2]).intValue(), (String) r[3],
                (String) r[4], (String) r[5], toTime(r[6]));
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

    /**
     * **物理删除一个知识库**（不可逆）—— 清空回收站 / 到期清理时调用。
     *
     * <p>删什么（用户 2026-09-22 裁定的"整库全清"）：
     * <ol>
     *   <li>`documents/{kbId}/**` 与 `parsed/{kbId}/**` **两个前缀整块删** ——
     *       ⚠️ **但留下仍被活行引用的那些对象**（见下）；</li>
     *   <li>该库**全部代**的 Milvus 分区（不只当前代）——
     *       ⚠️ 只删当前代的话，历史代分区在库行消失后**谁也认领不了**，
     *       正是 `T-O27` 刚清掉的那一族无主向量；</li>
     *   <li>该库的行族：`sys_chunk` · `sys_parse_issue` · `sys_document` · `sys_kb_grant` · `sys_kb`。</li>
     * </ol>
     *
     * <p><b>不删</b>：`sys_audit_log`（永久）· `sys_download_record`（下载历史要留，
     * 即使所下文件已不存在）· `sys_retrieval_trace*`（打点）。
     *
     * <h3>⚠️ 为什么先分区、再对象、最后删行</h3>
     * <p>三个都是"删完就没线索"的动作，顺序要保证**失败时仍可重试**：
     * `sys_kb` 那一行是唯一能算出前缀与分区名的东西 ⇒ 它必须**最后**删。
     *
     * <h3>🔴 `#223`：为什么"按前缀整块删"会删到别人家的文件</h3>
     * <p>对象名与知识库**绑死**（`documents/{kbId}/…`），而
     * {@code DocReindexService#moveToKb}（`G7-4`）**只改 `kb_id`、不搬对象**
     * （它删的是旧代分区的向量，不是 MinIO 里的字节）。于是「从本库移到 B 库」的文档：
     * 行在 B 库、**对象还躺在本库前缀下**。按前缀整块删会连它的源文件一起删掉 ——
     * 而 B 库那一行仍是 `READY`，用户点下载拿不到文件，**且没有任何地方报错**
     * （`DocStorage#remove` 是静默的）。
     *
     * <p>修法是**留下仍被活行引用的路径**：本步在删行（④）**之前**跑，
     * 所以此刻查 `sys_document.storage_path` / `parse_path` 拿到的正是"还有主"的那批。
     * 排除集的口径是**别的库的全部文档行**（见 {@link #referencedObjectKeys}）——
     * 它们既含活行、也含"在回收站里还没清"的行，两类都留，因为那两类的对象**都还有用**。
     *
     * <p>🔴 **本库自己的行（含本库回收站里的）一律不在排除集里**，它们的对象照删。
     * 别把这条读成疏漏：`purgeKb` 是**整库全清**，步骤④删行时**不看 `deleted_at`**
     * —— 本库回收站里的文档行同样会被删掉。它们的对象若不跟着删，
     * 就是一批"行没了、字节还在"的孤儿（`OrphanMinioSweepTest` 那一族要捞的正是不该存在的这种）。
     * 所以"留"与"删"在这里不是按 `deleted_at` 分的，而是按 **`kb_id` 分**。
     * 剩下的（本库已彻底删除的行留下的孤儿对象）也照删。
     *
     * <p>⚠️ **曾经**这里写的是"那时文档行也一起没了 ⇒ 没法再枚举 `storage_path` 逐个删"。
     * 那句话与代码**自相矛盾**：本步一直在④删行之前 —— 读起来像是在解释
     * "为什么必须按前缀删"，实际描述的是一个**并不存在的约束**。真正的理由是"整库对象一起没"
     * 这个语义，而它恰恰是 `#223` 的成因。
     */
    @Transactional
    public void purgeKb(long kbId) {
        // 🔴 与 `purgeDoc` 同一条闸：**只有进了回收站的库才能被物理删除**。
        //    没有它，"传一个活跃 kb 的 id"= 连带删掉它全部文档与对象 —— 全站最不可逆的一步。
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager.createNativeQuery(
                        "select deleted_at from sys_kb where id = :id")
                .setParameter("id", kbId)
                .getResultList();
        if (rows.isEmpty()) {
            log.info("[彻底删除] 知识库已不存在，跳过：kbId={}", kbId);
            return;
        }
        if (rows.getFirst() == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "只有回收站里的知识库才能彻底删除；该知识库仍在正常状态，请先删除它");
        }

        // ① 向量：全部代。⚠️ 先取代列表再逐个丢 —— 边遍历边丢会让迭代器看到变化
        List<Integer> generations = partitionManager.generationsOf(kbId);
        int dropped = 0;
        for (int generation : generations) {
            if (partitionManager.dropQuietly(MilvusConstants.partitionName(kbId, generation))) {
                dropped++;
            }
        }

        // ② 对象：两个前缀整块删，**留下主在别处的那些**（`#223`，理由见 javadoc）。
        //    ⚠️ 这份名单必须**在④删行之前**取：名单来自 `sys_document` 的行，
        //    而④会把本库的行删掉（借住的那批虽然不在本库，但清单口径是"全部活行"，
        //    放在删行之后取会让语义随实现漂移）
        Set<String> stillReferenced = referencedObjectKeys(kbId);

        // ③ 图库（`T-C00`）—— ⚠️ **必须在删 MySQL 行之前**：图里**没有 `kbId` 维度**
        //    （实体是"同一全库实体合一、跨库共享"，`07:462`），所以清理只能按
        //    「该库下**全部文档**的 docId」来 —— 而那份 docId 列表**只有此刻查得到**
        //    （下一句就把 `sys_document` 行删了）。顺序反了 = 一条都删不到，
        //    而现象是"图里留着已删库的边"（静默）。
        //    ⚠️ 它永不抛（`07:523`），失败时自己记 error。
        graphWriter.deleteDocuments(docIdsOfKb(kbId), kbId);

        // ③.5 现在才动对象 —— 与图同一条"先取证后破坏"的顺序。
        //    ⚠️ 失败会抛 —— 存储泄漏必须让调用方知道
        int objects = docStorage.removePrefixExcept(DocStorage.kbDocPrefix(kbId), stillReferenced)
                + docStorage.removePrefixExcept(DocStorage.kbParsedPrefix(kbId), stillReferenced);
        if (!stillReferenced.isEmpty()) {
            log.warn("[彻底删除] 库 kbId={} 下有 {} 个对象**因仍被活行引用而保留**"
                    + "（这些行在别的库里 —— 移动造成的借住）—— 见 `#223`",
                    kbId, stillReferenced.size());
        }

        // ④ 行族：子表在前、主表在后（无外键，但顺序与探针清理同口径，残留排查时好解释）
        int docs = deleteByKb("delete from sys_chunk where kb_id = :kbId", kbId);
        deleteByKb("delete from sys_parse_issue where kb_id = :kbId", kbId);
        deleteByKb("delete from sys_document where kb_id = :kbId", kbId);
        deleteByKb("delete from sys_kb_grant where kb_id = :kbId", kbId);
        deleteByKb("delete from sys_kb where id = :kbId", kbId);

        log.warn("[彻底删除] 知识库 kbId={} 已物理删除：分区 {} 个 · 对象 {} 个 · 文档 {} 篇"
                        + "（**不可逆**）", kbId, dropped, objects, docs);
    }

    /**
     * **会被本库前缀删到、但主在别处的对象名**（`#223` 的"排除集"）。
     *
     * <h3>🔴 为什么条件是本库之外，而不是本库之内</h3>
     * <p>直觉上会写成 `where kb_id = :kbId`（"本库文档的对象"）—— 那是**反的**。
     * 本库的全部文档行在步骤④都要删掉，它们名下的对象**本来就该一起清**。
     * 真正要救的是另一种：**行在别的库、对象还压在本库前缀下**的那批 ——
     * 它们由 {@code moveToKb} 的"只改 `kb_id`、不搬对象"造成（见 {@link #purgeKb} 的 javadoc）。
     * 所以条件只能是 `kb_id <> :kbId`。
     *
     * <p>于是这里**不需要**解析对象名里的前缀 —— 拿"会活下来的行"的全部路径当排除集，
     * 而 {@link DocStorage#removePrefixExcept} 只在本次前缀范围内应用它，
     * 天然就只会保护到"压在本库前缀下、主却在别处"的那几个。
     * 这也顺带避开了"把对象名嵌 kbId 的约定再抄一遍"（抄漏了就是静默的误删）。
     *
     * <p>⚠️ 两个列都可能为 NULL（未解析的文档没有 `parse_path`）——
     * 收进去之前滤掉，否则 `Set` 里会出现 `null`（虽无害，但会让日志里的数字对不上）。
     */
    private Set<String> referencedObjectKeys(long kbId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select storage_path, parse_path from sys_document where kb_id <> :kbId")
                .setParameter("kbId", kbId)
                .getResultList();
        Set<String> keys = new HashSet<>();
        for (Object[] row : rows) {
            for (Object value : row) {
                if (value != null && !String.valueOf(value).isBlank()) {
                    keys.add(String.valueOf(value));
                }
            }
        }
        return keys;
    }

    private int deleteByKb(String sql, long kbId) {
        return entityManager.createNativeQuery(sql).setParameter("kbId", kbId).executeUpdate();
    }

    /**
     * 该知识库下**全部文档**的 id（`T-C00` 的图清理要，**必须在删行之前调**）。
     *
     * <p>⚠️ 不过滤 `deleted_at`：软删的文档也在回收站里、也在库里，
     * 它们的图边同样要清（`purgeKb` 是"整库全清"，没有"留下回收站里的"这一说）。
     */
    @SuppressWarnings("unchecked")
    private List<Long> docIdsOfKb(long kbId) {
        return ((List<Number>) entityManager.createNativeQuery(
                        "select id from sys_document where kb_id = :kbId")
                .setParameter("kbId", kbId)
                .getResultList()).stream().map(Number::longValue).toList();
    }

    // ==================================================================
    // 入参
    // ==================================================================

    /**
     * 批量入参的形状校验（不涉及权限 —— 权限在下面逐个走 `requireVisibleDoc`）。
     *
     * <p>去重是必须的：同一篇被选两次在页面上做得到（跨库列表里同名文件），
     * 不去重会让"全验"通过而"全删"把同一行更新两次（第二次的 `and deleted_at is null`
     * 会让它无声地不做任何事 —— 结果对，但日志里的条数会撒谎）。
     */
    private static List<Long> requireDocIds(List<Long> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请先选择要删除的文档");
        }
        List<Long> ids = docIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请先选择要删除的文档");
        }
        return ids;
    }
}
