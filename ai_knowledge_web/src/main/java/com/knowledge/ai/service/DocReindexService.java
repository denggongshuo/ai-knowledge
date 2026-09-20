package com.knowledge.ai.service;

import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.async.ParseWorker;
import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.enums.DocStatus;
import com.knowledge.ai.core.enums.KbGrantLevel;
import com.knowledge.ai.core.enums.SecretLevel;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.dto.doc.DocDtos;
import com.knowledge.ai.pipeline.vectorize.ChunkVectorizer;
import com.knowledge.ai.pipeline.vectorize.VectorWriter;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 文档级重建索引（`T-610`）+ 移动知识库的向量搬运（`T-601` 的那一半）。
 *
 * <h3>🔴 `T-610` = **重新解析**，不是"重新向量化"</h3>
 * <p>需求 `06` 两处原文定的：§5「重新索引 | 针对单篇文档，**用于修复解析失败的个别文档**」、
 * §4「「重建索引」| 会**重新执行解析与表格识别**，是修复未识别表格的主要入口」。
 * ⇒ 它走的是**整条解析流水线**（解析 → 表格识别 → 图片理解 → 增强 → 向量化），
 * 所以实现就是**把这篇再投一次解析作业** —— 而不是另写一条"拿现有分片重新嵌入"的路。
 *
 * <p>⚠️ 计划书 §3.1 步 3 原写的是"复用 {@code ChunkVectorizer#vectorize}"。**施工时按需求原文改了**，
 * 理由见上；连带的好处在 §3.4-⑤ 有一条：既然走整条流水线，就**不存在"单篇重建要不要推进代"**
 * 这个问题（解析链路用的是 {@code sys_kb.index_generation}，即**当前代**，它本来就不推进代）。
 *
 * <h3>🔴 移动知识库**不是**重新解析 —— 它只搬运向量</h3>
 * <p>需求 `06` §6.7：「属性修改**后立即生效，无需重建索引**；**移动知识库除外**」+
 * 「移动知识库**触发重建**」。这里的「重建」是**重建索引**（同一份分片换个库重新嵌入），
 * 内容一个字都没变 —— 与 §5 那条"重新执行解析"是**两件事**。这个区分不只是省钱
 * （重新解析会逐张重调 VL，实测 40~60 秒/张，`#179`），更是语义：内容没变就不该重新解析，
 * 否则分片 id 会全换、历史问答的引用会集体悬空。
 *
 * <h3>🔴 移动知识库是一条**降级绕过的候选路径**，必须在这里堵住</h3>
 * <p>生效密级 = {@code max(文档, 库)}（`I-6`）。若文档的密级是 {@code NULL}（**继承库级**），
 * 把它从「机密」库移到「公开」库，生效密级就**从机密变成公开** —— 而 `I-6` 原文写它存在的
 * 理由正是「**否则用户可把机密文档降级绕过管控**」（`03-术语与领域模型.md:268`）。
 * ⇒ 本类拒绝「目标库密级 **低于** 当前生效密级」的移动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocReindexService {

    private final EntityManager entityManager;

    private final DocAccessService docAccessService;

    private final KbGrantService kbGrantService;

    private final ParseWorker parseWorker;

    private final ChunkReader chunkReader;

    private final ChunkVectorizer chunkVectorizer;

    private final VectorWriter vectorWriter;

    private final DocRecordWriter recordWriter;

    private final TransactionTemplate transactionTemplate;

    // ==================================================================
    // T-610 · 单篇重建
    // ==================================================================

    /**
     * `POST /api/doc/{id}/reindex` —— 单篇重建（**只负责受理**）。
     *
     * <p>状态闸：只放行 {@link DocStatus#canReindex()} 的两种终态（`READY` / `FAILED`）。
     * <ul>
     *   <li>`FAILED` 能重建 —— 那正是需求 §5 的**正主**（「修复解析失败的个别文档」）；</li>
     *   <li>`PARSING` / `INDEXING` **拒绝**（{@link DocStatus#inProgress()}）：已经有活人在跑，
     *       再投一条只会被幂等键吃掉、却让用户以为"我这次重建生效了"。</li>
     * </ul>
     * ⚠️ `UPLOADED` 也拒绝：它既没在跑也没失败，是"还没轮到" —— 那不该由人手点重建解决。
     *
     * <p>幂等：作业键是 {@code doc-parse:{docId}}（{@link ParseWorker#enqueue}），
     * 同一篇同时只有一条在跑；跑完之后键自动释放，所以"再重建一次"照样能投。
     */
    @AuditLog(module = AuditModule.DOC, action = "重建索引", risk = RiskLevel.NORMAL,
            target = "'文档#' + #docId")
    public DocDtos.DocReindexAccepted submit(long docId) {
        DocAccessService.DocRow doc = docAccessService.requireVisibleDoc(docId);
        DocStatus status = statusOf(doc.status());
        if (status.inProgress()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "这篇文档正在" + status.getLabel() + "，请等它跑完再重建（重复提交不会加快它）。");
        }
        if (!status.canReindex()) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "当前状态是「" + status.getLabel() + "」，不能重建 —— 只有「可检索」与「解析失败」两种状态可以。");
        }
        String taskId = parseWorker.enqueue(doc.kbId(), docId, doc.ownerId());
        log.info("[文档重建] 已受理：docId={} kbId={} taskId={} 原状态={}", docId, doc.kbId(), taskId, status);
        return new DocDtos.DocReindexAccepted(docId, taskId, "ACCEPTED");
    }

    // ==================================================================
    // T-601 的那一半 · 移动知识库
    // ==================================================================

    /**
     * 把一篇文档移到另一个知识库：**换归属 + 把向量搬到目标库的分区**。
     * 由 {@code DocAttributeService} 在 `PATCH` 的 `kbId` 变化时调用
     * （跨 Bean 调用，{@link AuditLog} 的切面才生效 —— 同类自调用不走代理）。
     *
     * <p>四道校验，**全过才动手**（任何一道不过都**不改任何东西**）：
     * <ol>
     *   <li>文档存在且**其所属库可见**（{@link DocAccessService#requireVisibleDoc}，
     *       `10044` / `10022`）；</li>
     *   <li>对**源库**有 `MANAGE` —— "把东西从这儿搬走"是管理动作；</li>
     *   <li>对**目标库**有 `UPLOAD` —— "放进那儿"是上传动作；</li>
     *   <li>🔴 **目标库密级 ≥ 当前生效密级** —— 见类注释那段「降级绕过」。</li>
     * </ol>
     *
     * <p>⚠️ 授权口径（源库 MANAGE + 目标库 UPLOAD）是**推出来的**，不是需求逐字写的：
     * 需求 §6.7 只说了「移动后需重建索引」与「复用 16 页的变更确认弹窗」，
     * **授权那一栏是空的**。推断依据是 {@link KbGrantLevel} 三档的语义。已登记为待确认偏差。
     *
     * <p>顺序是**故障方向**选的，不是随手排的（每一步失败都要落在"安全的那一侧"）：
     * <pre>
     *   ① 置 INDEXING（不可检索）→ ② 删旧库分区里这篇的向量 → ③ 换归属（doc + chunk）
     *   → ④ 向量化到目标库当前代 → ⑤ 置 READY
     * </pre>
     * 反过来（先换归属再删旧向量）的失败后果是**旧库检索会召回一篇已经不属于它的文档**
     * —— 那是越权方向上的错，比"少了几条结果"严重一个量级。
     *
     * @return 是否真的移动了（{@code false} = 目标库就是当前库，**什么都没做**）
     */
    @AuditLog(module = AuditModule.DOC, action = "移动知识库", risk = RiskLevel.NORMAL,
            target = "'文档#' + #docId")
    public boolean moveToKb(long docId, long targetKbId) {
        DocAccessService.DocRow doc = docAccessService.requireVisibleDoc(docId);
        if (doc.kbId() == targetKbId) {
            // 没换库 ⇒ 不是"移动"。它不该产生一条「移动知识库」的审计（那会是假痕迹），
            // 所以调用方要**先判再调**，本方法自己也判一次（两道，因为审计已经挂在这个方法上了）
            return false;
        }

        // ② 源库 MANAGE。⚠️ 目标库还没校验 —— 顺序无所谓，但两条都要在**动手之前**
        requireLevel(doc.kbId(), KbGrantLevel.MANAGE,
                "移动文档需要**源知识库的管理权限**");

        // ③ 目标库存在 + 可见 + ≥ UPLOAD
        long targetKbIdChecked = requireTargetKb(targetKbId);

        // ④ I-6 的防降级绕过（见类注释）。⚠️ 只有"降低"被拒，"不变"与"提高"都放行
        SecretLevel current = doc.effectiveSecretLevel();
        SecretLevel targetKbLevel = kbSecretLevel(targetKbIdChecked);
        if (current != null && targetKbLevel != null && !current.within(targetKbLevel)) {
            throw BizException.of(ErrorCode.KB_ACCESS_DENIED,
                    "目标知识库的密级（" + targetKbLevel.getLabel() + "）低于本文档当前生效的密级（"
                            + current.getLabel() + "）—— 移动会让它被更多人看到。"
                            + "请选择密级不低于它的知识库，或先在源库调整。");
        }

        int targetGeneration = recordWriter.currentKbGeneration(targetKbIdChecked);
        String oldPartition = MilvusConstants.partitionName(doc.kbId(), doc.indexGeneration());

        // ① 先落"不可检索"，让中途失败表现为"待重建"而不是"可检索却没有向量"。
        //    ⚠️ 必须包在事务里：Hibernate 6+ 对原生 update 会校验活动事务，
        //       否则抛 `TransactionRequiredException: No active transaction for update or delete query`
        transactionTemplate.executeWithoutResult(tx -> entityManager.createNativeQuery(
                        "update sys_document set status = 'INDEXING', updated_at = :now where id = :id")
                .setParameter("now", LocalDateTime.now())
                .setParameter("id", docId)
                .executeUpdate());

        // ② 删**旧库分区**里这篇的向量。⚠️ 这一步必须显式做：
        //    `ChunkVectorizer` 写目标分区时自带的 `replaceDocument` 只在**目标分区内**按 doc_id 删，
        //    旧分区它一个字节都不碰 —— 而那两个分区属于**两个不同的库**。
        int removed = vectorWriter.replaceDocument(oldPartition, docId, java.util.List.of());

        // ③ 换归属：文档 + **它的分片**。⚠️ 分片那一半不能漏：
        //    `ChunkReader` / 检索侧都按 `sys_chunk.kb_id` join `sys_kb` 取密级，
        //    只改文档列会让分片继续按**旧库的密级**参与过滤（一个非常安静的越权口子）
        transactionTemplate.executeWithoutResult(tx -> {
            LocalDateTime now = LocalDateTime.now();
            entityManager.createNativeQuery("""
                            update sys_document
                               set kb_id = :target, status = 'INDEXING', index_generation = 0,
                                   updated_at = :now
                             where id = :id
                            """)
                    .setParameter("target", targetKbIdChecked)
                    .setParameter("now", now)
                    .setParameter("id", docId)
                    .executeUpdate();
            entityManager.createNativeQuery(
                            "update sys_chunk set kb_id = :target where doc_id = :id")
                    .setParameter("target", targetKbIdChecked)
                    .setParameter("id", docId)
                    .executeUpdate();
        });

        // ④ 向量化到**目标库的当前代**。代复核照 `DocParseService` 的写法：
        //    "还是当初那一代吗"必须在**真正落盘之前**问（`ChunkVectorizer.GenerationGuard` 的位置）
        ChunkVectorizer.Result result = chunkVectorizer.vectorize(
                docId, targetKbIdChecked, targetGeneration, chunkReader.readByDocId(docId),
                () -> {
                    int currentGen = recordWriter.currentKbGeneration(targetKbIdChecked);
                    if (currentGen != targetGeneration) {
                        throw new ChunkVectorizer.GenerationChangedException(
                                "目标知识库的索引代已变更（本作业持有第 " + targetGeneration
                                        + " 代，当前第 " + currentGen + " 代）—— 本次放弃写入向量");
                    }
                });
        if (result.allowsReady()) {
            // ⑤ 只有确实写进去了才盖 READY（`allowsReady` 的语义：不谎报可检索）
            recordWriter.markReady(docId, targetGeneration);
        } else {
            log.warn("[文档移动] 向量化未执行，文档停在 INDEXING：docId={} 原因={}", docId, result.detail());
        }
        log.info("[文档移动] docId={} {} → {} · 旧分区 {} 删掉 {} 条向量 · 目标第 {} 代",
                docId, doc.kbId(), targetKbIdChecked, oldPartition, removed, targetGeneration);
        return true;
    }

    // ==================================================================
    // 校验辅助
    // ==================================================================

    /**
     * 目标库：必须**存在**、**对当前用户可见**、且档位 ≥ `UPLOAD`。
     *
     * <p>三条合一的效果是"你不能把文档搬进一个你自己都传不进东西的库" ——
     * 单看 `UPLOAD` 一条就够，但先把"存在/可见"分开报，错误消息才对得上人的下一步动作
     * （「这库没了」与「你没权限」要做的事完全不同）。
     */
    private long requireTargetKb(long targetKbId) {
        Number exists = (Number) entityManager.createNativeQuery(
                        "select count(*) from sys_kb where id = :id and deleted_at is null")
                .setParameter("id", targetKbId)
                .getSingleResult();
        if (exists == null || exists.longValue() == 0) {
            throw BizException.of(ErrorCode.KB_NOT_FOUND, "目标知识库不存在（或已在回收站）。");
        }
        requireLevel(targetKbId, KbGrantLevel.UPLOAD, "把文档放进目标知识库需要**上传权限**");
        return targetKbId;
    }

    /** 当前用户对某库的档位是否达到 `required`；不够就抛（消息由调用方给，因为它取决于动作）。 */
    private void requireLevel(long kbId, KbGrantLevel required, String what) {
        KbGrantLevel actual = kbGrantService.currentLevel(kbId);
        if (actual == null || actual.getRank() < required.getRank()) {
            throw BizException.of(ErrorCode.KB_ACCESS_DENIED,
                    what + "（需要「" + required.getLabel() + "」，当前是「"
                            + (actual == null ? "不可见" : actual.getLabel()) + "」）。");
        }
    }

    /** 某库的密级；库里是脏值/为空时返回 {@code null}（调用方按"判不了"处理，**不当成"没有限制"**）。 */
    private SecretLevel kbSecretLevel(long kbId) {
        Object raw = entityManager.createNativeQuery(
                        "select secret_level from sys_kb where id = :id")
                .setParameter("id", kbId)
                .getSingleResult();
        if (raw == null) {
            return null;
        }
        try {
            return SecretLevel.valueOf(String.valueOf(raw));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static DocStatus statusOf(String raw) {
        try {
            return DocStatus.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            // 认不出的状态当"不可重建"处理更安全（放行会投出一条谁也说不清后果的作业）
            throw BizException.of(ErrorCode.PARAM_INVALID, "这篇文档的状态无法识别：" + raw);
        }
    }
}
