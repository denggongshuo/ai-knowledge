package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.SecretLevel;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 「这篇文档**我看不看得到**」—— 全仓**唯一**的一处口径（不变量 `I-7` 的落点）。
 *
 * <h3>为什么抽出来，而不是在 DocDetailService / DocReindexService / DocAttributeService 各写一遍</h3>
 * <p>这三处都要同一个判定，而**条数只会涨**（`T-611` 删除 / `T-614` 下载 / `T-616` 回收站都要它）。
 * 本仓已经吃过一次"同一个根因在两处各写一遍"的亏：{@code DocService#requireKbVisible} 与
 * {@code DocDetailService#requireVisibleDoc} 是同一段 SQL 的两个副本 —— 多写一遍就是多一个
 * 漏掉的机会，而**漏掉的后果是越权可见**（{@code RetrievalScope} 的类注释里写着同一句话）。
 *
 * <h3>🔴 顺序是硬约束：先判存在、再判可见</h3>
 * <p>两种情形必须给**不同的错**：
 * <ul>
 *   <li>文档不存在（或已软删）⇒ {@link ErrorCode#DOC_NOT_FOUND}（`10044`，HTTP 200）；</li>
 *   <li>文档在、但**所在库**不可见 ⇒ {@link ErrorCode#KB_ACCESS_DENIED}（`10022`，HTTP **403**）。</li>
 * </ul>
 * 后者**不许**退化成前者：那会把「你可以去申请授权」说成「这文档没了」——
 * 正是「判不了必须与判否分开报」那条口径的反面。
 *
 * <h3>它一次给出写侧要用的列，不只是"放行/拒绝"</h3>
 * <p>判定本来就要 join {@code sys_kb}（可见性条件在库上），顺手把
 * {@code status} / {@code secret_level} / {@code kb.secret_level} / {@code owner_id} 带回来，
 * 免得调用方拿到放行之后**再查一遍同一行** —— 两次读之间文档可能已经变了
 * （本仓那条「任何"写"之前都要复核"我手里的那一份还是当前的吗"」）。
 */
@Component
@RequiredArgsConstructor
public class DocAccessService {

    private final EntityManager entityManager;

    private final DataScopeService dataScopeService;

    /**
     * 一次查询同时给出「存在 / 可见」与写侧要用的那几列。
     *
     * @param ownSecretLevel 文档**自己**的密级；{@code null} 表示**未覆盖（继承库级）**
     *                       —— 它不是"没有密级"（生效值见 {@link #effectiveSecretLevel()}）
     * @param indexGeneration 该文档的分片写在哪一代的分区里（移动知识库要按它去**旧库分区**删向量）
     */
    public record DocRow(long id, long kbId, String status, String ownSecretLevel,
                         String kbSecretLevel, Long ownerId, int indexGeneration) {

        /** 生效密级 = {@code max(自己, 库级)}（`I-6`）；{@code sys_kb.secret_level} 是 NOT NULL ⇒ 恒非 {@code null}。 */
        public SecretLevel effectiveSecretLevel() {
            return SecretLevel.max(level(ownSecretLevel), level(kbSecretLevel));
        }

        /** 文档自己设的密级；{@code null} = 继承。前端靠它渲染「（继承知识库）」。 */
        public SecretLevel ownSecretLevelOrNull() {
            return level(ownSecretLevel);
        }

        private static SecretLevel level(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return SecretLevel.valueOf(raw);
            } catch (IllegalArgumentException e) {
                // 认不出的密级值当"未设置"处理，**不抛** —— 读侧不该为一行的脏数据
                // 把整个页面炸掉（与 DocDetailService#level 同一条纪律）
                return null;
            }
        }
    }

    /**
     * 文档不存在 ⇒ `10044`；所在库不可见 ⇒ `10022`（HTTP 403）。两情形**必须给不同的错**。
     */
    @Transactional(readOnly = true)
    public DocRow requireVisibleDoc(long docId) {
        List<?> rows = entityManager.createNativeQuery("""
                        select d.id, d.kb_id, d.status, d.secret_level, k.secret_level, d.owner_id,
                               d.index_generation
                          from sys_document d
                          join sys_kb k on k.id = d.kb_id
                         where d.id = :id and d.deleted_at is null
                        """)
                .setParameter("id", docId)
                .getResultList();
        if (rows.isEmpty()) {
            throw BizException.of(ErrorCode.DOC_NOT_FOUND);
        }
        Object[] r = (Object[]) rows.get(0);
        long kbId = ((Number) r[1]).longValue();

        LoginUser user = LoginUserContext.require();
        DataScopeService.ScopeCondition cond = dataScopeService.kbVisibility(user);
        // ⚠️ 拼接处保留前导空格：text block 会剥掉每行行尾空白，写成 `... and " + cond.sql()`
        // 会拼出 `andEXISTS`（SQL 语法错，B1 §8.1 实测踩到）
        Query query = entityManager.createNativeQuery(
                "select count(*) from sys_kb k where k.id = :kbId and " + cond.sql());
        query.setParameter("kbId", kbId);
        cond.params().forEach(query::setParameter);
        Number visible = (Number) query.getSingleResult();
        if (visible == null || visible.longValue() == 0) {
            throw BizException.of(ErrorCode.KB_ACCESS_DENIED);
        }

        return new DocRow(((Number) r[0]).longValue(), kbId, (String) r[2], (String) r[3],
                (String) r[4], r[5] == null ? null : ((Number) r[5]).longValue(),
                r[6] == null ? 0 : ((Number) r[6]).intValue());
    }
}
