package com.knowledge.ai.pipeline.retrieve;

import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.enums.SecretLevel;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.storage.MilvusPartitionManager;
import com.knowledge.ai.service.DataScopeService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检索的**可见范围**（`T-700` 的第 ① 步 · 不变量 `I-9`）。
 *
 * <h3>为什么不变量 I-9 一定要在 MySQL 侧先算</h3>
 * <p>《11-非功能需求与技术架构》§2.1 的 `I-9`：**过滤条件由业务侧传给 Milvus 做标量过滤，
 * 而不是把密级当作可检索标签**。落成一句话就是本类：
 * <ol>
 *   <li>先在 **MySQL**（唯一有权限事实的地方）算出「这个用户看得见哪些库、哪些文档」；</li>
 *   <li>把 {@code doc_id} 集合作为**标量过滤条件**交给 Milvus，让它**在索引层就把不可见的筛掉**；</li>
 *   <li>**绝不**"先召回 20 条、再在内存里剔掉越权的" —— 那样既浪费了 topK 名额
 *       （20 条里有 15 条被剔掉，只剩 5 条），又让越权内容**已经流过了检索链路**。</li>
 * </ol>
 *
 * <h3>三个维度硬交，没有"或"</h3>
 * <ul>
 *   <li>② 数据范围 ∩ ③ 知识库授权 —— 由 {@link DataScopeService#kbVisibility} 一条 SQL 表达
 *       （它的类注释写着"任何一维放行都等于越权"）；</li>
 *   <li>④ 账号状态（停用账号看不到任何库）—— 同样在 {@code kbVisibility} 里；</li>
 *   <li>⑤ 软删 —— 库的 {@code deleted_at} 在 {@code kbVisibility} 里，
 *       文档的在 {@link #visibleDocIds} 里。</li>
 * </ul>
 * <p>本类**不再自己写一遍**这些条件，只是把它们组装起来 —— 这是"过滤条件只有一处口径"
 * 这条纪律的落点：多写一遍就是多一个漏掉的机会，而漏掉的后果是越权可见。
 *
 * <h3>密级在这一层拦，而不是在 Milvus 标量上再拦一遍</h3>
 * <p>密级是**知识库**的属性（{@code sys_kb.secret_level}），库不可见则它的文档全不可见 ——
 * 于是"密级超限"已经在 {@link #visibleKbs} 的 WHERE 里被表达成"这些库根本不在集合里"。
 * Milvus 那列 {@code secret_level} 仍然保留（它是写入侧的标量），但检索时**不重复过滤**：
 * 两处都判、两处口径一旦不一致，就是"某批分片谁都搜不到"这种查不出原因的故障。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalScope {

    /**
     * {@code doc_id in [...]} 表达式里 doc 数量的上限。
     *
     * <p>超过它就**退化为**"只按可见性过滤到库一级 + 召回后在内存里再筛文档"
     * （{@link Scope#docFallback()} 会为 {@code true}）。为什么要有这个上限：
     * Milvus 的表达式是一个**字符串**，几万个 id 拼进去会撞上它的长度限制，
     * 而那时的现象是"检索直接报错"，比"少召回几条"严重得多。
     *
     * <p>⚠️ 退化路径**确实会降低召回精度**（被筛掉的候选白占了 topK 名额），
     * 所以它必须**可见**：{@link RetrievalTrace#docScopeFallback()} 会把它带出去。
     * 这正是本仓那条"静默降级比不降级更糟"的口径。
     * 正常规模（每用户可见文档数百篇）永远走不到这里。
     */
    public static final int MAX_DOC_FILTER = 4096;

    @PersistenceContext
    private EntityManager entityManager;

    private final DataScopeService dataScopeService;

    private final MilvusPartitionManager partitionManager;

    /**
     * 算出一个用户此刻**可检索的全部范围**。
     *
     * @param user          登录用户（**必须已登录** —— 检索没有匿名入口）
     * @param requestedKbIds 本次提问限定的知识库；{@code null}/空 = 该用户可见的全部库。
     *                       限定值**只是取交集**，不能放大范围：传一个看不见的 id 进来，
     *                       结果里也不会有它
     */
    @Transactional(readOnly = true)
    public Scope resolve(LoginUser user, List<Long> requestedKbIds) {
        List<KbRow> kbs = visibleKbs(user, requestedKbIds);
        List<Long> kbIds = kbs.stream().map(KbRow::id).toList();
        if (kbIds.isEmpty()) {
            // 一条分片都搜不到，而且这一步**不该**去连 Milvus —— 空集合直接短路，
            // 少一次网络往返；也避免"分区全是已删的"这种边界在 Milvus 侧炸出 65535
            return new Scope(List.of(), Set.of(), List.of(), false);
        }

        List<String> partitions = existingPartitions(kbs);
        DocScope docScope = visibleDocIds(kbIds);
        return new Scope(kbIds, docScope.docIds(), partitions, docScope.fallback());
    }

    /**
     * 可见的知识库：② 数据范围 ∩ ③ 授权 ∩ ④ 账号状态 ∩ ⑤ 软删 ∩ ⑥ 密级上限 ∩ ⑦ 请求限定。
     *
     * <p>前四项**整条**来自 {@link DataScopeService#kbVisibility}（别名固定 {@code k}，
     * 参数 {@code ds_uid}/{@code ds_dept}），本方法只补后两项 —— 见类注释。
     */
    private List<KbRow> visibleKbs(LoginUser user, List<Long> requestedKbIds) {
        if (requestedKbIds != null && !requestedKbIds.isEmpty()) {
            // 空集合会在 SQL 里变成 `k.id in ()` —— MySQL 有语法错。
            // 上面已判过空，这里再判一次是防"调用方传了个只含 null 的列表"
            List<Long> clean = requestedKbIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
            if (clean.isEmpty()) {
                return List.of();
            }
        }

        DataScopeService.ScopeCondition cond = dataScopeService.kbVisibility(user);
        StringBuilder sql = new StringBuilder(
                "select k.id, k.secret_level, k.index_generation from sys_kb k where ")
                .append(cond.sql())
                // ⑥ 密级：只取用户上限以内的库。「未分配 / 越限」的库连出现都不出现，
                //    而不是"出现了但被过滤掉" —— 后者会让 kbCount 报出一个用户看不见的数字
                .append(" and k.secret_level in (:secretLevels)");
        if (requestedKbIds != null && !requestedKbIds.isEmpty()) {
            sql.append(" and k.id in (:requested)");
        }
        sql.append(" order by k.id");

        Query query = entityManager.createNativeQuery(sql.toString());
        cond.params().forEach(query::setParameter);
        query.setParameter("secretLevels", allowedSecretLevels(user));
        if (requestedKbIds != null && !requestedKbIds.isEmpty()) {
            query.setParameter("requested", requestedKbIds.stream().filter(java.util.Objects::nonNull).distinct().toList());
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<KbRow> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            out.add(new KbRow(((Number) row[0]).longValue(), String.valueOf(row[1]),
                    row[2] == null ? 1 : ((Number) row[2]).intValue()));
        }
        return out;
    }

    /**
     * 用户密级上限以内的等级名列表。
     *
     * <p>用**白名单**（枚举全量筛一遍）而不是"把上限转成 rank 再比大小"：
     * 白名单进的是 SQL 参数，比较逻辑留在 Java；把算术搬进 SQL 会让"密级加了一档"
     * 这种事从"改一个枚举"变成"改一处 SQL"。且密级只有 4 档，白名单长度可以忽略。
     */
    private static List<String> allowedSecretLevels(LoginUser user) {
        SecretLevel upper = user.getSecretLevelUpper() == null ? SecretLevel.PUBLIC : user.getSecretLevelUpper();
        return java.util.Arrays.stream(SecretLevel.values())
                .filter(level -> level.within(upper))
                .map(Enum::name)
                .toList();
    }

    /**
     * 各库**当前代**的分区名，只保留**确实存在**的。
     *
     * <p>⚠️ 「只保留存在的」不是优化，是**正确性**：步 0 探针实测，查一个已 drop 的分区
     * 会**报错（65535）**而不是返回空集。于是"某个库刚重建完、旧代已删"这种完全正常的
     * 状态，会让**整次检索请求炸掉**，而不是少几条结果。
     */
    private List<String> existingPartitions(List<KbRow> kbs) {
        Set<String> names = new LinkedHashSet<>(kbs.size());
        for (KbRow kb : kbs) {
            String partition = MilvusConstants.partitionName(kb.id(), kb.generation());
            if (partitionManager.has(partition)) {
                names.add(partition);
            } else {
                // 留痕：库可见却没有分区，意味着"这个库刚建好还没解析过文档"（正常）
                // 或"代切换写了一半"（不正常）。两者现象相同，日志是同一条线索
                log.info("[检索] 可见库没有对应的 Milvus 分区，本次跳过：kbId={} 代={} 分区={}",
                        kb.id(), kb.generation(), partition);
            }
        }
        return List.copyOf(names);
    }

    /**
     * 可见文档集合（`I-9` 的第 ① 步本体）。
     *
     * <p>只查 {@code sys_document}，不看 {@code sys_chunk}：库级可见性已经限定了范围，
     * 而"哪些文档属于它"是文档表的事实。按 {@code id} 升序，让表达式**可复现** ——
     * 同一份数据两次调用拼出同一个字符串，日志与取证才能直接比对。
     */
    private DocScope visibleDocIds(List<Long> kbIds) {
        @SuppressWarnings("unchecked")
        List<Number> rows = entityManager.createNativeQuery("""
                        select d.id from sys_document d
                         where d.kb_id in (:kbIds) and d.deleted_at is null
                         order by d.id
                        """)
                .setParameter("kbIds", kbIds)
                .getResultList();

        // LinkedHashSet：**保序**（按 id 升序），于是 milvusFilter() 拼出的表达式可复现 ——
        // 同一份数据两次调用得到同一个字符串，日志与取证才能直接比对；同时 allowsDoc 是 O(1)
        Set<Long> ids = rows.stream().map(Number::longValue)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.size() <= MAX_DOC_FILTER) {
            return new DocScope(ids, false);
        }
        log.warn("[检索] 可见文档数 {} 超过标量过滤上限 {}，退化为「库级过滤 + 结果侧再筛」—— "
                        + "本次召回精度会下降，请关注该用户的可见库规模：kbIds={}",
                ids.size(), MAX_DOC_FILTER, kbIds);
        // ⚠️ 退化时 docIds **仍然保留全量**（只是不进 Milvus 表达式）：
        // 结果侧的再筛要靠它。丢掉它就不是"退化"而是"取消过滤"——那就是越权。
        return new DocScope(ids, true);
    }

    /**
     * 一次检索的可见范围。
     *
     * @param kbIds       可见知识库 id（已按密级与请求限定过滤）
     * @param docIds      可见文档 id **全量**（两种路径下都是全量，见 {@link #allowsDoc}）
     * @param partitions  本次要查的 Milvus 分区（**只含确实存在的**）
     * @param docFallback 是否退化成了「库级过滤 + 结果侧再筛」。{@code true} 时
     *                    {@link #milvusFilter()} 不含 doc 条件，改由 {@link #allowsDoc} 兜住
     */
    public record Scope(List<Long> kbIds,
                        Set<Long> docIds,
                        List<String> partitions,
                        boolean docFallback) {

        /** 有没有可查的东西。为空时调用方应短路返回空结果，**不要**去连 Milvus。 */
        public boolean empty() {
            return kbIds.isEmpty() || partitions.isEmpty();
        }

        /** 下发给 trace 的"MySQL 侧可见文档数"（两种路径下都是**真实总数**，不报 0）。 */
        public int docScopeCount() {
            return docIds.size();
        }

        /** 下发给 Milvus 的过滤表达式；退化路径只按库过滤。 */
        public String milvusFilter() {
            String byKb = MilvusConstants.FIELD_KB_ID + " in ["
                    + kbIds.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]";
            if (docFallback || docIds.isEmpty()) {
                return byKb;
            }
            return byKb + " and " + MilvusConstants.FIELD_DOC_ID + " in ["
                    + docIds.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]";
        }

        /**
         * 该文档是否在可见范围内。
         *
         * <p>正常路径下 Milvus 已经在索引层筛过，这里恒 {@code true}（不做第二次判断 ——
         * 两处都判、口径一旦漂移就是"某批分片谁都搜不到"）。
         * **退化路径**下这是唯一的一道过滤，必须真的判。
         */
        public boolean allowsDoc(long docId) {
            return !docFallback || docIds.contains(docId);
        }
    }

    /** 库行：id / 密级 / 当前代。 */
    private record KbRow(long id, String secretLevel, int generation) {
    }

    /** 文档集合 + 是否退化。 */
    private record DocScope(Set<Long> docIds, boolean fallback) {
    }
}
