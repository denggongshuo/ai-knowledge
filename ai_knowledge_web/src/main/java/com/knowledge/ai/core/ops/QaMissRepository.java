package com.knowledge.ai.core.ops;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 未命中池（`sys_qa_miss`）与打点表的读侧（`T-A00`）。
 *
 * <h3>为什么是 native query 而不是 JPA 实体</h3>
 * <p>与 {@code NoticeRepository} / {@code SysAsyncTaskRepository} 同一条口径：
 * 没有关联、没有生命周期，全是"按条件查/写一行"。映射成实体只会多一层要与库结构
 * 同步的负担 —— 而收益是零（本表从来不会被 {@code findById} 之外的地方当对象用）。
 *
 * <h3>🔴 两个计数不是一回事，别互相顶替</h3>
 * <ul>
 *   <li>{@link #countPage} = **聚合后的行数**（清单里显示几条）—— 分页要用它，
 *       否则页数会多于实际行数；</li>
 *   <li>{@link #sumAskCount} = 这些行的**提问次数合计**（需求 §1.10 的
 *       「未命中清单总条数 = 统计卡的未命中提问数」在**有重复提问时**说的其实是它）。</li>
 * </ul>
 * <p>需求 `08` §1.10 那两条验收要点把这两件事写成了一个数 —— 只有在
 * 「每个问法都只被问过一次」时才成立，而需求 §1.2 又同时要求把重复提问聚合起来。
 * **两者不可兼得**，所以两个数都返回、都在页面上有落点，让判据自己选。见本批 §8。
 */
@Repository
@RequiredArgsConstructor
public class QaMissRepository {

    private final EntityManager entityManager;

    /** 清单一行（含 join 出来的显示名）。 */
    public record Row(long id,
                      String question,
                      int askCount,
                      LocalDateTime lastAskAt,
                      Long lastUserId,
                      Double maxTopScore,
                      Double scoreAfter,
                      String status,
                      Long ignoredBy,
                      String kbIds,
                      String askerName,
                      String ignoredByName) {
    }

    /** 打点表的窗口统计（需求 §1.1 的三个数字）。 */
    public record TraceStats(long totalQuestions, long missed, Double avgTopScore) {
    }

    /**
     * 一篇**为某条未命中补录的**文档（`T-A01` · `V34` 的 `remark`）。
     *
     * @param status 文档状态（{@code DocStatus} 名）。重放的前置自检要看它 ——
     *               补录文档还没 {@code READY} 就去重放，会得到"未达标"这个**错误的结论**，
     *               而它与"补的文档确实没用"长得一模一样
     */
    public record SupplementedDoc(long docId, String title, String status, String errorMsg) {
    }

    private static final String SELECT_ROW = """
            select m.id, m.question, m.ask_count, m.last_ask_at, m.last_user_id, m.max_top_score,
                   m.score_after, m.status, m.ignored_by, m.kb_ids,
                   u.real_name, ig.real_name
            from sys_qa_miss m
            left join sys_user u on u.id = m.last_user_id
            left join sys_user ig on ig.id = m.ignored_by
            """;

    // ==================== 沉淀（写侧）====================

    /**
     * 幂等沉淀一行：同一归一化键**只累加，不新增**。
     *
     * <p>⚠️ {@code REQUIRES_NEW} 是**故意**的：调用它的是检索打点那条 {@code REQUIRES_NEW}
     * 事务（`RetrievalTraceWriter`）。共用一个事务的话，池子写失败会把**打点行一起回滚**
     * —— 那是证据流，比池子重要得多。拆开之后最坏的后果是"有一条打点没有池子行"，
     * 而那正是对账判据要抓的东西（可重建），反过来则不可恢复。
     *
     * <p>三条字段的更新规则都写在 SQL 里，避免"读-改-写"那种竞态：
     * <ul>
     *   <li>{@code ask_count + 1} —— 在库里自增，并发提问不会丢计数；</li>
     *   <li>{@code max_top_score} 取**较大者**且**不让 NULL 冲掉已有值**（`case when`）；</li>
     *   <li>{@code question} **不更新** —— 需求要的是"原样记录用户问法"，
     *       第一次记下的那份就是原始问法，后续重复提问不该改写它。</li>
     * </ul>
     *
     * <p>⚠️ <b>不动 {@code status}</b>：状态是人的意图或重放的结论，不该被"又问了一次"改写。
     * 「已解决的行又被问倒要不要自动复活」是一条**产品口径**，本批不做，已登记。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsert(String missKey, String question, Long userId, Double topScore,
                       String kbIds, LocalDateTime at) {
        entityManager.createNativeQuery("""
                        insert into sys_qa_miss (miss_key, question, ask_count, first_ask_at, last_ask_at,
                                                 last_user_id, max_top_score, kb_ids, status, updated_at)
                        values (:key, :question, 1, :now, :now, :uid, :score, :kbIds, 'PENDING', :now)
                        on duplicate key update
                            ask_count     = ask_count + 1,
                            last_ask_at   = :now,
                            last_user_id  = coalesce(:uid, last_user_id),
                            max_top_score = case when :score is null then max_top_score
                                                 else greatest(coalesce(max_top_score, :score), :score) end,
                            updated_at    = :now
                        """)
                .setParameter("key", missKey)
                .setParameter("question", question)
                .setParameter("now", at)
                .setParameter("uid", userId)
                .setParameter("score", topScore == null ? null : BigDecimal.valueOf(topScore))
                .setParameter("kbIds", kbIds == null ? "" : kbIds)
                .executeUpdate();
    }

    // ==================== 列表（读侧）====================

    /** 一行；不存在返回 {@code null}（由服务层翻成 10040）。 */
    public Row findById(long id) {
        List<Row> rows = query(SELECT_ROW + " where m.id = :id", Map.of("id", id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Row> findPage(MissStatus status, Long kbId, String keyword, int limit, int offset) {
        Filter filter = filter(status, kbId, keyword);
        // 需求 §1.2：「默认按提问次数降序 —— 把「很多人问但答不上来」排在最前」
        String sql = SELECT_ROW + filter.where()
                + " order by m.ask_count desc, m.last_ask_at desc, m.id desc limit :limit offset :offset";
        Map<String, Object> params = new HashMap<>(filter.params());
        params.put("limit", limit);
        params.put("offset", offset);
        return query(sql, params);
    }

    public long countPage(MissStatus status, Long kbId, String keyword) {
        Filter filter = filter(status, kbId, keyword);
        return scalar("select count(*) from sys_qa_miss m" + filter.where(), filter.params());
    }

    /**
     * 导出用：**全部**匹配行（无分页），排序与 {@link #findPage} 一致。
     *
     * <p>🔴 **多取一行，用来判断"是不是超限"** —— 调用方给的 {@code cap} 是上限，
     * 返回 {@code cap + 1} 行就说明还有更多。这样避免"再发一条 count 查询"，
     * 也避免"先数后取"两次之间数据变了导致的自相矛盾。
     * <p>⚠️ 调用方**必须**检查 `size() > cap` 并**明确拒绝**（报出实际条数与上限），
     * **不能静默截断** —— 一份少了行的报表看起来是完整的，
     * 而"上周明明有 12 条怎么只导出了 10 条"没人查得出来（与审计导出同一条口径）。
     */
    public List<Row> findAll(MissStatus status, Long kbId, String keyword, int cap) {
        Filter filter = filter(status, kbId, keyword);
        String sql = SELECT_ROW + filter.where()
                + " order by m.ask_count desc, m.last_ask_at desc, m.id desc limit :cap";
        Map<String, Object> params = new HashMap<>(filter.params());
        params.put("cap", cap + 1);
        return query(sql, params);
    }

    /** 这些行的**提问次数合计** —— 见类注释「两个计数不是一回事」。 */
    public long sumAskCount(MissStatus status, Long kbId, String keyword) {
        Filter filter = filter(status, kbId, keyword);
        return scalar("select coalesce(sum(m.ask_count), 0) from sys_qa_miss m" + filter.where(),
                filter.params());
    }

    /** 待办数（需求 §1.1 的「待处理问题」）—— **`/notices/pending` 与侧边栏角标共用它**（`G6-13`）。 */
    public int pendingCount() {
        return (int) scalar("select count(*) from sys_qa_miss where status in ('PENDING', 'IN_LIST')", Map.of());
    }

    /**
     * 按 id 改状态。返回受影响行数（0 = 那一行不存在）。
     *
     * <p>⚠️ <b>`ignored_by` / `ignored_at` 离开 IGNORED 时**刻意不清空**</b>：需求 §1.3
     * 要求记忽略人的理由是「避免同一问题被反复忽略又反复冒出」—— 而"它曾经被谁放弃过"
     * 正是下一次判断的依据。清空等于把那条理由抹掉。列表上只在 IGNORED 状态展示它。
     *
     * <p>⚠️ 反过来，`resolved_at` 离开 RESOLVED 时**要清空**：它是"什么时候修好的"，
     * 退回待处理就意味着那个结论不成立了，留着会让"已解决时长"这类将来的统计算错。
     */
    public int updateStatus(long id, MissStatus status, Long ignoredBy, LocalDateTime now) {
        return entityManager.createNativeQuery("""
                        update sys_qa_miss
                        set status = :status,
                            ignored_by = case when :status = 'IGNORED' then :uid else ignored_by end,
                            ignored_at = case when :status = 'IGNORED' then :now else ignored_at end,
                            resolved_at = case when :status = 'RESOLVED' then :now else null end,
                            updated_at = :now
                        where id = :id
                        """)
                .setParameter("status", status.name())
                .setParameter("uid", ignoredBy)
                .setParameter("now", now)
                .setParameter("id", id)
                .executeUpdate();
    }

    // ==================== 重放验证（`T-A01`）====================

    /**
     * 找出为某条未命中**补录**的文档（`V34` 的 `remark` 承载问题原文）。
     *
     * <h3>为什么在 Java 侧比对，而不是一条 SQL join</h3>
     * <p>关联键是 {@link MissKeyNormalizer#keyOf}（NFKC → 去标点 → 小写 → SHA-256），
     * 那是**Java 函数，进不了 SQL**。所以先取"有备注的文档"，再在内存里算键比对。
     * 扫描面只有**补录产出的那几行**（普通上传 {@code remark} 恒 {@code NULL}），
     * 所以这个"全表扫"实际是几十行量级 —— `V34` 的注释里写了为什么因此不加索引。
     *
     * <h3>为什么不按知识库缩小范围</h3>
     * <p>运营页的「补文档」在**清单行跨多个知识库**时会退化成"跳知识库列表让用户自己选"
     * （{@code OpsQaView.supplement()} 的既有分支）—— 那时补录的文档**可能落在任何库里**。
     * 按库筛会漏掉那一半。而多算几篇无害：命中与否最终由"召回到了没有"决定，
     * 检索本身只召回提问人**可见**的库（`RetrievalScope`）。
     *
     * <p>{@code deleted_at is null}：软删掉的补录文档不算 —— 它已经不在检索范围里了，
     * 拿它去判"命中"会永远判否，而那与"还没补录"应该是两回事。
     */
    public List<SupplementedDoc> supplementedDocs(String remarkKey) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select id, title, status, error_msg, remark from sys_document "
                                + "where remark is not null and deleted_at is null")
                .getResultList();
        List<SupplementedDoc> result = new ArrayList<>();
        for (Object[] r : rows) {
            if (!remarkKey.equals(MissKeyNormalizer.keyOf((String) r[4]))) {
                continue;
            }
            result.add(new SupplementedDoc(((Number) r[0]).longValue(), (String) r[1],
                    (String) r[2], (String) r[3]));
        }
        return result;
    }

    /**
     * 写重放结论：`score_after` + 状态 + `resolved_at`。
     *
     * <p>⚠️ <b>只动这三样</b>：{@code ignored_by}/{@code ignored_at} 不碰
     * （"它曾经被谁放弃过"是下一次判断的依据，见 {@link #updateStatus}）；
     * {@code ask_count} / {@code last_ask_at} / {@code max_top_score} 也不碰 ——
     * **重放不是一次提问**，它是针对某一次沉淀结论的复核（本批 §3.4 假设 5/6）。
     *
     * <p>`resolved_at` 的处置与 {@link #updateStatus} **同一条规则**：只在 RESOLVED 时写，
     * 离开 RESOLVED 时清空。⚠️ 那条规则因此在 SQL 里出现了两次 —— 复制规则是漂移的开始，
     * 所以另有判据钉住它：{@code QaMissReplayFlowTest} 断言
     * 「{@code resolved_at} 非空 ⟺ 状态是 {@code RESOLVED}」（含"先解决再退回"那一路）。
     * 光靠注释对齐两处 SQL，迟早会漏（本仓已复现多次）。
     */
    @Transactional
    public int markReplayed(long id, MissStatus status, Double scoreAfter, LocalDateTime now) {
        return entityManager.createNativeQuery(
                        "update sys_qa_miss set status = :status, score_after = :score, "
                                + "resolved_at = case when :status = 'RESOLVED' then :now else null end, "
                                + "updated_at = :now where id = :id")
                .setParameter("status", status.name())
                .setParameter("score", scoreAfter == null ? null : BigDecimal.valueOf(scoreAfter))
                .setParameter("now", now)
                .setParameter("id", id)
                .executeUpdate();
    }

    // ==================== 打点窗口统计（需求 §1.1）====================

    /**
     * 近 {@code from} 以来的提问数 / 未命中数 / 平均 Top1。
     *
     * <p>⚠️ 三个口径各有出处，别改：
     * <ul>
     *   <li>**提问总数** = 打点行数（每次提问一行）；</li>
     *   <li>**未命中提问** = `miss_flag = 1` 的行数 —— 需求 §1.1 的定义就是
     *       「Rerank 后最高分 < 0.35 的提问**数**」。⚠️ 空召回**不算**（`G5-11` 早分流了：
     *       那是"一条都没召回"，与"召回了但都不像"是两件事）；</li>
     *   <li>**平均 Top1** = `avg(top1_score)` —— `avg` **天然跳过 NULL**，
     *       而空召回那一行的 Top1 就是 NULL（`V22` 的注释：「NULL = 未知，0 = 分数真的是 0」）。
     *       ⇒ 空召回**不进分母**，这是对的：把"没有分数"按 0 计会把整体质量凭空拉低。</li>
     * </ul>
     */
    public TraceStats traceStats(LocalDateTime from) {
        Object[] row = (Object[]) entityManager.createNativeQuery("""
                        select count(*),
                               coalesce(sum(case when miss_flag = 1 then 1 else 0 end), 0),
                               avg(top1_score)
                        from sys_retrieval_trace
                        where created_at >= :from
                        """)
                .setParameter("from", from)
                .getSingleResult();
        return new TraceStats(
                row[0] == null ? 0L : ((Number) row[0]).longValue(),
                row[1] == null ? 0L : ((Number) row[1]).longValue(),
                row[2] == null ? null : ((Number) row[2]).doubleValue());
    }

    /** 知识库显示名（`kb_ids` 是逗号串，所以按 id 批量取，不在 SQL 里 join）。 */
    public Map<Long, String> kbNames(List<Long> kbIds) {
        if (kbIds.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select id, name from sys_kb where id in (:ids)")
                .setParameter("ids", kbIds)
                .getResultList();
        Map<Long, String> map = new LinkedHashMap<>(rows.size());
        for (Object[] r : rows) {
            map.put(((Number) r[0]).longValue(), (String) r[1]);
        }
        return map;
    }

    // ==================== 内部 ====================

    private record Filter(String where, Map<String, Object> params) {
    }

    /** 列表三件共用的筛选条件 —— **只写一处**，免得"列表筛了、计数没筛"。 */
    private static Filter filter(MissStatus status, Long kbId, String keyword) {
        StringBuilder sql = new StringBuilder(" where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (status != null) {
            sql.append(" and m.status = :status");
            params.put("status", status.name());
        }
        if (kbId != null) {
            // `kb_ids` 是逗号串（与 sys_retrieval_trace 同口径）⇒ 只能 FIND_IN_SET。
            // ⚠️ 一行若跨了多个库，按其中任一库筛都会命中它 —— 这是**近似**，不是精确关联
            sql.append(" and find_in_set(:kbId, m.kb_ids)");
            params.put("kbId", String.valueOf(kbId));
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" and m.question like :kw");
            params.put("kw", "%" + keyword.trim() + "%");
        }
        return new Filter(sql.toString(), params);
    }

    private List<Row> query(String sql, Map<String, Object> params) {
        var query = entityManager.createNativeQuery(sql);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            query.setParameter(e.getKey(), e.getValue());
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Row> result = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            result.add(new Row(
                    ((Number) r[0]).longValue(),
                    (String) r[1],
                    ((Number) r[2]).intValue(),
                    (LocalDateTime) r[3],
                    r[4] == null ? null : ((Number) r[4]).longValue(),
                    decimal(r[5]),
                    decimal(r[6]),
                    (String) r[7],
                    r[8] == null ? null : ((Number) r[8]).longValue(),
                    (String) r[9],
                    (String) r[10],
                    (String) r[11]));
        }
        return result;
    }

    private long scalar(String sql, Map<String, Object> params) {
        var query = entityManager.createNativeQuery(sql);
        for (Map.Entry<String, Object> e : params.entrySet()) {
            query.setParameter(e.getKey(), e.getValue());
        }
        Object value = query.getSingleResult();
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static Double decimal(Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }
}
