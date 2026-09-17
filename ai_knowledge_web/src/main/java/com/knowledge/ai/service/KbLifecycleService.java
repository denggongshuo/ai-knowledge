package com.knowledge.ai.service;

import com.knowledge.ai.core.audit.AuditContext;
import com.knowledge.ai.core.audit.AuditLog;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.core.audit.RiskLevel;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.dto.kb.KbDtos;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库的生命周期终点（`T-407`）—— 删除知识库（软删进回收站）。
 *
 * <h3>为什么是软删</h3>
 * <p>《03-术语与领域模型》：{@code [正常] ──删除──→ [回收站（保留 30 天）] ──┬── 恢复 ──→ [正常]（需重建索引）}。
 * 硬删会把"误删一个库"变成不可挽回的事故，而这个动作是 🚩 高危、且下一步就是不可逆。
 *
 * <h3>删除时**不**动向量、不删文档</h3>
 * <p>回收站语义要求"可恢复"，所以 {@code sys_document} / {@code sys_chunk} /
 * Milvus 分区**全部保留**。恢复后按《03》需要重建索引（恢复流程属 `T-611` 一族，不在本批）。
 *
 * <p>⚠️ 由此带来一条**必须在别处兑现**的义务：软删的库不能再被检索到。
 * 本批的兑现方式是把 {@code deleted_at is null} 加进求交引擎
 * （{@code DataScopeService#kbVisibility}）—— 那是所有可见性读的唯一收口点。
 * <b>检索侧（M07/S5）必须走同一套可见性判定</b>，否则回收站里的库仍然召回得到。
 *
 * <h3>审计：`HIGH`（`G3-8` 的运行时半边）</h3>
 * <p>《10》§4.3 的高危四类含"内容删除"。口径侧 {@code AuditRiskPolicy} 早已冻结，
 * 本批补的是**运行时**那一半 —— 在这之前"删除知识库"没有任何触发接口
 * （S3-D 复核：{@code KbController} 无任何 {@code DeleteMapping}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbLifecycleService {

    private final EntityManager entityManager;

    /**
     * 软删知识库：进回收站，保留 30 天。
     *
     * <p>⚠️ {@code target = "#kbId"} —— 删除之后库里已经没有名字可取了，
     * 审计的 target 只能记 id（与"创建知识库"用 {@code #form.name} 不同，
     * 那个时刻名字是存在的）。
     */
    @Transactional
    @AuditLog(module = AuditModule.KB, action = "删除知识库", risk = RiskLevel.HIGH, target = "'知识库#' + #kbId")
    public KbDtos.KbDeleted softDelete(long kbId) {
        Row row = requireActive(kbId);

        // 变更前后对照（`G3-7`：详情要含 before/after）。删除只有**一个字段真的变了** ——
        // 删除状态。刻意不把 doc_count 之类也写进来充数：它们的值没变，
        // 把没变的字段列成"变更"会让审计详情失真（用户会以为文档被删了）。
        AuditContext.change("删除状态", "正常", "已删除（回收站，保留 30 天）");

        LocalDateTime now = LocalDateTime.now();
        int updated = entityManager.createNativeQuery(
                        "update sys_kb set deleted_at = :now, updated_at = :now "
                                + "where id = :id and deleted_at is null")
                .setParameter("now", now)
                .setParameter("id", kbId)
                .executeUpdate();
        if (updated == 0) {
            // 并发下另一个请求先删掉了 —— 与上面的 requireActive 之间存在的窗口
            throw BizException.of(ErrorCode.KB_NOT_FOUND);
        }

        log.info("[删除知识库] kbId={} 名称={} 文档={} 分片={}（向量与文档保留，可恢复）",
                kbId, row.name(), row.docCount(), row.chunkCount());
        return new KbDtos.KbDeleted(row.id(), true, true, row.name());
    }

    /** 读一个**尚未删除**的库；已删或不存在都按"不存在"报（`10043`）。 */
    @Transactional(readOnly = true)
    Row requireActive(long kbId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select id, name, doc_count, chunk_count
                          from sys_kb
                         where id = :id and deleted_at is null
                        """)
                .setParameter("id", kbId)
                .getResultList();
        if (rows.isEmpty()) {
            throw BizException.of(ErrorCode.KB_NOT_FOUND);
        }
        Object[] r = rows.getFirst();
        return new Row(((Number) r[0]).longValue(), (String) r[1],
                ((Number) r[2]).intValue(), ((Number) r[3]).intValue());
    }

    record Row(long id, String name, int docCount, int chunkCount) {
    }
}
