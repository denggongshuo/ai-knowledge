package com.knowledge.ai.service;

import com.knowledge.ai.core.async.AsyncTaskService;
import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.storage.MilvusPartitionManager;
import com.knowledge.ai.pipeline.vectorize.ChunkVectorizer;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 重建索引（`T-406`）—— 按代 partition 写新分区，完成后**一次 UPDATE** 切代（`D-7`）。
 *
 * <h3>为什么不是"原地重建"</h3>
 * <p>{@code D-7} 要求「写新 collection + 原子切换、**不得原地删旧索引**」。而
 * {@code D-4}/{@code R-8} 定死了**单 collection** —— 整集合的切换做不了"只换 8 个库里的某一个"，
 * 而重建入口（{@code POST /kb/{id}/reindex}）恰恰是**按一个库**的。
 * 这个夹缝的解是**分区**：同一个 collection 内按代分组，切换 = 换一个分区名去查。
 * （方案与探针证据见 {@code plan/2026-09-17-S4-D.md} §3.4 与 {@code MilvusPartitionProbeTest}。）
 *
 * <h3>三个关键性质，都是"设计出来的"而不是"顺带有的"</h3>
 * <ol>
 *   <li><b>旧索引在切换前一个字节都没动</b> —— 全程只往新分区写。所以
 *       {@code G3-5}「中途 kill 旧索引仍可用」是**天然满足**的：进程被杀时，
 *       {@code sys_kb.index_generation} 还指在旧代上，检索照常。</li>
 *   <li><b>切换是一次 MySQL UPDATE</b> —— 事务、原子、可回滚，而不是一串 Milvus 操作。</li>
 *   <li><b>断点靠 {@code sys_document.index_generation}</b>，不靠"查 Milvus 里有没有" ——
 *       写入是先删后插，半截写入会被"在不在"误判成已完成。详见 {@code V16} 的注释。</li>
 * </ol>
 *
 * <h3>有文档失败时：**不切换**</h3>
 * <p>切了就等于留一个**已知不完整**的新代，与"旧索引仍可用"这条保证相冲 —— 用户改配置的
 * 目的是"让新配置生效"，而一个缺了文档的新代算不上生效。所以失败时保持旧代，
 * 把失败清单如实返回，让调用方看得见、能重试（重试会**跳过已完成文档**，代价很小）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReindexService {

    /** 异步任务类型（`T-509` 的任务投影按它区分）。 */
    public static final String TASK_TYPE = "KB_REINDEX";

    /**
     * 保留代数：切换后**保留最近 N 代**（含当前代），更老的滚动删除。
     *
     * <p>取 2 而不是 1：留一代给"切完发现新代有问题、想立刻回退"。取 2 而不是更多：
     * 步 0 探针实测 {@code release+drop} 约 <b>1.2s/分区</b>，且分区数 = 库数 × 代数是
     * Milvus 侧的真实开销。回收站语义的长期留存不是这里的事 —— 那是 `T-616` 的生命周期规则表。
     */
    static final int KEEP_GENERATIONS = 2;

    private final EntityManager entityManager;

    private final MilvusPartitionManager partitionManager;

    private final ChunkReader chunkReader;

    private final ChunkVectorizer chunkVectorizer;

    private final DocRecordWriter recordWriter;

    private final AsyncTaskService asyncTaskService;

    /** 见 {@link #switchGeneration} 的注释：同类自调用不经过事务代理，只能用模板显式开。 */
    private final TransactionTemplate transactionTemplate;

    // ==================== 入口 ====================

    /**
     * 提交重建（异步），立即返回 taskId。
     *
     * <p>幂等键 = {@code KB_REINDEX:<kbId>} —— 同一个库连点两次只跑一次
     * （与 {@code DocService} 的幂等键同一条理由，只是这里没有"文件"维度）。
     */
    public String submit(long kbId) {
        requireActiveKb(kbId);
        String taskId = asyncTaskService.submit(TASK_TYPE, TASK_TYPE + ":" + kbId, 0, context -> {
            context.stage("准备新代分区");
            Result result = reindex(kbId);
            context.stage(switch (result.status()) {
                case DONE -> "重建完成，已切换到第 " + result.to() + " 代";
                case BLOCKED -> "重建未切换：有 " + result.failures().size() + " 篇文档失败，旧索引保持可用";
                case NOTHING_TO_DO -> "没有需要重建的文档";
            });
            context.percent(100);
            context.done(result.processed() + result.skipped());
            context.complete(result);
        });
        log.info("[重建] 已受理：kbId={} taskId={}", kbId, taskId);
        return taskId;
    }

    // ==================== 同步核心 ====================

    /**
     * 重建一个知识库的索引（**同步**，测试与异步任务共用这一条路）。
     *
     * <p>⚠️ 刻意不放在一个大事务里 —— 它可能跑几分钟，长事务会锁住 {@code sys_kb} 的行、
     * 且中途失败会回滚掉已经写进 Milvus 的向量（那些是**不能回滚**的）。每一步各自成事务。
     */
    public Result reindex(long kbId) {
        KbRow kb = requireActiveKb(kbId);
        int from = kb.generation();
        int to = from + 1;
        String targetPartition = MilvusConstants.partitionName(kbId, to);

        // ① 先确保分区存在且已 load。写进不存在的分区会**显式失败**（步 0 探针），
        //    而"建了没 load"表现为"写进去了却检索不到"—— 那是最难查的一类。
        partitionManager.ensure(targetPartition);

        List<DocRow> docs = docsToVectorize(kbId, to);
        if (docs.isEmpty()) {
            // 没有文档也要切代 + 清空旧代的库：否则重启后 "index_generation" 与分区不一致，
            // 而检索侧指向一个**已 drop 的分区会报错**（探针实测），不是少几条结果。
            switchGeneration(kbId, to);
            dropOldGenerations(kbId, to);
            log.info("[重建] 无文档需要处理，已切代：kbId={} {} → {}", kbId, from, to);
            return new Result(kbId, from, to, 0, 0, List.of(), Status.NOTHING_TO_DO);
        }

        int processed = 0;
        List<Failure> failures = new ArrayList<>();
        for (DocRow doc : docs) {
            try {
                ChunkVectorizer.Result r = chunkVectorizer.vectorize(
                        doc.id(), kbId, to, chunkReader.readByDocId(doc.id()));
                if (r.allowsReady()) {
                    // `O9` 步 7：换模型重建时，归因**必须跟着刷新** ——
                    // 否则这两列记的是"上一次是谁产的"，而它恰恰是这个字段要回答的问题。
                    // 与 DocParseService 同一条口径：只在确实写过之后盖章
                    recordWriter.markVectorAttribution(doc.id(),
                            r.modelRef().model(), r.modelRef().version());
                    // 成功之后**才**记断点 —— 顺序不能反，否则半截写入会被当成已完成
                    recordWriter.markReady(doc.id(), to);
                    processed++;
                } else {
                    failures.add(new Failure(doc.id(), doc.title(), r.detail()));
                }
            } catch (Exception ex) {
                String reason = ex.getMessage() == null || ex.getMessage().isBlank()
                        ? ex.getClass().getSimpleName() : ex.getMessage();
                // ⚠️ 这里**刻意不改文档状态**（原来写成 markFailed，2026-09-17 由
                // `ReindexFlowTest#resumesFromBreakpointWithoutRedoingFinishedDocs` 逼出来的订正）。
                //
                // 两个理由：
                //   ① 标 FAILED 是**误报** —— 这篇文档在**当前代**里的向量完好无损、检索得到，
                //      它只是在**新代**里还没写成功。而本方法失败时不切代，所以它一点没坏。
                //   ② 标了 FAILED 之后，本方法下一轮的筛选（`status in ('READY','INDEXING')`）
                //      就再也捞不到它 —— **重试永远修不好它**，用户只能删了重传。
                //
                // 诚实的落点是：状态不动（仍是 READY）+ index_generation 仍指旧代（= 它在新代里
                // 还没齐），两者合起来精确表达了"可用，但没跟上新代"。失败清单照实返回给调用方。
                failures.add(new Failure(doc.id(), doc.title(), reason));
                log.warn("[重建] 文档失败（继续下一篇，状态与旧代向量保持不变）：kbId={} docId={} 原因={}",
                        kbId, doc.id(), reason);
            }
        }

        if (!failures.isEmpty()) {
            log.warn("[重建] 有 {} 篇失败，**不切换**（旧代仍可用）：kbId={} 失败={}",
                    failures.size(), kbId, failures);
            return new Result(kbId, from, to, processed, 0, List.copyOf(failures), Status.BLOCKED);
        }

        // ② 原子切换点：一次 UPDATE
        switchGeneration(kbId, to);
        // ③ 迁移期间被排除在外的文档（如已软删）不再处理；滚动清理旧代
        int cleaned = dropOldGenerations(kbId, to);

        log.info("[重建] 完成并切代：kbId={} {} → {} 处理={} 清理旧分区={}",
                kbId, from, to, processed, cleaned);
        return new Result(kbId, from, to, processed, 0, List.of(), Status.DONE);
    }

    // ==================== 各步骤 ====================

    /** 需要向量化的文档：已解析/已就绪、未软删、且**不在目标代**（断点续跑靠最后这条）。 */
    @Transactional(readOnly = true)
    List<DocRow> docsToVectorize(long kbId, int targetGeneration) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select id, title from sys_document
                         where kb_id = :kbId
                           and deleted_at is null
                           and status in ('READY', 'INDEXING')
                           and index_generation <> :generation
                         order by id
                        """)
                .setParameter("kbId", kbId)
                .setParameter("generation", targetGeneration)
                .getResultList();
        List<DocRow> docs = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            docs.add(new DocRow(((Number) r[0]).longValue(), (String) r[1]));
        }
        log.info("[重建] 待处理文档：kbId={} 目标代={} 篇数={}", kbId, targetGeneration, docs.size());
        return docs;
    }

    /**
     * **原子切换点**（`D-7`）：一次 UPDATE 把检索指向新代，并清掉"待重建"标记。
     *
     * <p>这一步之前，检索读到的仍是旧代；这一步之后才是新代。中间没有第三态。
     *
     * <p>⚠️ <b>为什么用 {@link TransactionTemplate} 而不是在这个方法上挂 {@code @Transactional}</b>：
     * 它是由 {@link #reindex} **同类自调用**的，而 Spring 的事务是代理实现的 ——
     * 自调用不经过代理，注解形同虚设（实测报 {@code TransactionRequiredException: No active transaction}）。
     * 同一族的坑在 {@code SysDocument} 的类注释里已经记过一次（"实体落库会被 @Transactional 的
     * 代理语义限制（同类自调用不生效）"）。模板是显式开事务，绕开代理。
     */
    void switchGeneration(long kbId, int generation) {
        transactionTemplate.executeWithoutResult(status -> entityManager.createNativeQuery(
                        "update sys_kb set index_generation = :generation, reindex_required = 0, "
                                + "updated_at = :now where id = :id")
                .setParameter("generation", generation)
                .setParameter("now", LocalDateTime.now())
                .setParameter("id", kbId)
                .executeUpdate());
    }

    /**
     * 滚动清理：删掉比"保留窗口"更老的代分区。
     *
     * <p>⚠️ **必须在切代之后**：切换前删旧代就等于原地重建，正是 `D-7` 禁止的。
     * 且删除要 {@code release → drop}（裸 drop 会以 65535 静默失败，见 {@code MilvusPartitionManager}）。
     *
     * @return 实际删掉的分区数
     */
    int dropOldGenerations(long kbId, int currentGeneration) {
        int oldestKept = currentGeneration - KEEP_GENERATIONS + 1;
        int dropped = 0;
        for (int generation : partitionManager.generationsOf(kbId)) {
            if (generation < oldestKept) {
                if (partitionManager.dropQuietly(MilvusConstants.partitionName(kbId, generation))) {
                    dropped++;
                }
            }
        }
        return dropped;
    }

    /**
     * 清空一个库的索引（`DELETE /kb/{id}/index`，`T-407` 的清空半边）。
     *
     * <p>与"重建"的区别：清空是**真的把向量删掉**（用户明确要求），所以它不保留旧代。
     * 删完后把代推进一代（而不是留在原地）—— 因为指向的分区已被删，留着就是一个
     * **会报错的指针**（探针实测：查不存在的分区报 65535，不是返回空）。
     */
    @Transactional
    @AuditLog(module = AuditModule.KB, action = "清空索引", risk = RiskLevel.HIGH, target = "'知识库#' + #kbId")
    public int clearIndex(long kbId) {
        requireActiveKb(kbId);
        int dropped = 0;
        for (int generation : partitionManager.generationsOf(kbId)) {
            if (partitionManager.dropQuietly(MilvusConstants.partitionName(kbId, generation))) {
                dropped++;
            }
        }
        // 文档的向量没了，断点与状态都要跟上 —— 不然后续重建会误 skip
        entityManager.createNativeQuery(
                        "update sys_document set index_generation = 0, status = 'INDEXING', "
                                + "updated_at = :now "
                                + "where kb_id = :kbId and deleted_at is null and index_generation > 0")
                .setParameter("now", LocalDateTime.now())
                .setParameter("kbId", kbId)
                .executeUpdate();
        entityManager.createNativeQuery(
                        "update sys_kb set reindex_required = 1, updated_at = :now where id = :id")
                .setParameter("now", LocalDateTime.now())
                .setParameter("id", kbId)
                .executeUpdate();
        log.info("[清空索引] kbId={} 删除分区数={}（保留代推进，避免指向已删分区）", kbId, dropped);
        return dropped;
    }

    /** 读知识库行，**软删的视为不存在**。 */
    @Transactional(readOnly = true)
    KbRow requireActiveKb(long kbId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select id, index_generation from sys_kb where id = :id and deleted_at is null")
                .setParameter("id", kbId)
                .getResultList();
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.KB_NOT_FOUND, "知识库不存在或已删除");
        }
        Object[] r = rows.getFirst();
        return new KbRow(((Number) r[0]).longValue(), ((Number) r[1]).intValue());
    }

    // ==================== 类型 ====================

    /** 重建的结局。**只有 {@link #DONE} 与 {@link #NOTHING_TO_DO} 意味着已切代。** */
    public enum Status {
        /** 全部文档处理成功，已切到新代。 */
        DONE,
        /** 没有需要处理的文档，已切代。 */
        NOTHING_TO_DO,
        /** 有文档失败，**未切代**（旧索引保持可用）。 */
        BLOCKED
    }

    /** @param failures 失败的文档（{@code BLOCKED} 时非空） */
    public record Result(long kbId, int from, int to, int processed, int skipped,
                         List<Failure> failures, Status status) {
    }

    public record Failure(long docId, String title, String reason) {
    }

    record DocRow(long id, String title) {
    }

    record KbRow(long id, int generation) {
    }
}
