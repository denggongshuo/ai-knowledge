package com.knowledge.ai.service;

import com.knowledge.ai.core.async.ParseWorker;
import com.knowledge.ai.pipeline.ParseFingerprint;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析质量的**聚合与可追溯**（`T-O05` 步 5/6 · `O5` §4 步 5/6）。
 *
 * <h3>为什么必须有"聚合"这一层</h3>
 * <p>`O5` §4 步 6 的原话：「`sys_parse_issue` 是行级明细，缺**聚合**。
 * 企业运维要的是**分布**，不是 2000 行明细」。明细回答"哪一页怎么了"，
 * 分布回答"这个库里解析到底行不行" —— 而后者才是决定"要不要换解析器/调参数"的依据。
 *
 * <h3>本类只有一处分组查询，汇总在 Java 里做</h3>
 * <p>按格式分组一条 SQL，总数由各分组**相加**得到，而不是再发一条"总数"查询 ——
 * 两条查询各自的 where 一旦漂移（某天有人给其中一条加了条件），
 * 就会出现"各格式之和 ≠ 总数"，而这种不一致在页面上表现为一个说不清的差额。
 *
 * <h3>⚠️ 本批**只交服务，不交 HTTP 接口**（与计划 §3.1 E8 的偏差，已登记）</h3>
 * <p>计划说"只交聚合接口，页面接线归 S8"。实际做的时候收窄了一层，理由两条：
 * <ol>
 *   <li>**没有需求点名的路径**：`O5` §4 步 6 只说"接原型 20 页"，而那个页面属 S8 的 M10。
 *       现在给一个 `/ops/parse-quality` 等于替 S8 猜路径名与响应形状；</li>
 *   <li>**它的权限码还不存在**：`PermissionCode` 里没有 `ops:*`，与 `T-706` 遇到的是同一个问题
 *       （那边我选了"只要求登录"并把 S8 复核写进遗留）。连着两处都这么选，
 *       等于用一个临时口径铺两条接口 —— 不如把形状留给有消费方的那一批定。</li>
 * </ol>
 * <p>所以本类交付的是**判据 6 真正要的那件事**：「聚合口径与库内真值逐项吻合」，
 * 它可以在没有 HTTP 层的情况下被完整验证（见 {@code ParseQualityFlowTest}）。
 * 将来加一层 Controller 是十来行，且那时形状由页面决定。
 *
 * <h3>哪些指标是**算得出来**的，哪些不是</h3>
 * <p>本类刻意不产出「解析耗时 P95」：那要读 {@code sys_async_task} 的
 * {@code finished_at - created_at}，而解析只是那条任务里的一段（转存、分片、向量化都在里面），
 * 拿它当"解析耗时"是**把任务耗时说成解析耗时** —— 一个不会被发现的口径错。
 * 真要它，应当由打点（`T-707` 那套）来给。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParseQualityService {

    /** 终态但不是失败的状态：解析完成（READY 已可检索 / INDEXING 向量还没落完）。 */
    private static final String STATUS_READY = "READY";

    private static final String STATUS_INDEXING = "INDEXING";

    private static final String STATUS_FAILED = "FAILED";

    @PersistenceContext
    private EntityManager entityManager;

    private final ParseWorker parseWorker;

    // ==================== 步 5：按版本筛出"需重解析" ====================

    /**
     * 一个库里"解析产物不是当前版本"的文档数（`O5` §5 判据 4）。
     *
     * @param kbId 知识库
     */
    @Transactional(readOnly = true)
    public Outdated outdated(long kbId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select count(*),
                               sum(case when parse_version = :current then 1 else 0 end),
                               sum(case when parse_version is not null and parse_version <> :current
                                        then 1 else 0 end),
                               sum(case when parse_version is null then 1 else 0 end)
                          from sys_document
                         where kb_id = :kbId and deleted_at is null
                        """)
                .setParameter("kbId", kbId)
                .setParameter("current", ParseFingerprint.version())
                .getResultList();

        Object[] row = rows.isEmpty() ? new Object[] {0L, null, null, null} : rows.getFirst();
        return new Outdated(kbId, longOf(row[0]), longOf(row[1]), longOf(row[2]), longOf(row[3]));
    }

    /**
     * 需重解析的文档 id（**含 `parse_version IS NULL` 的那批**）。
     *
     * <p>⚠️ NULL 必须算"需要重解析"：那是本批之前的产物，也是最该重做的一批。
     * 把它排除掉的话，这个入口会在一个刚升完级的老库上**返回空集** ——
     * 现象是"指纹加好了，但没有文档需要重解析"，看着像一切正常。
     *
     * @param limit 一次最多返回多少（投递要限流；0 或负 = 不限）
     */
    @Transactional(readOnly = true)
    public List<Long> outdatedDocIds(long kbId, int limit) {
        String sql = """
                select id from sys_document
                 where kb_id = :kbId and deleted_at is null
                   and (parse_version is null or parse_version <> :current)
                 order by id
                """;
        if (limit > 0) {
            sql = sql + " limit " + limit;
        }
        @SuppressWarnings("unchecked")
        List<Object> rows = entityManager.createNativeQuery(sql)
                .setParameter("kbId", kbId)
                .setParameter("current", ParseFingerprint.version())
                .getResultList();
        List<Long> ids = new ArrayList<>(rows.size());
        for (Object row : rows) {
            ids.add(((Number) row).longValue());
        }
        return List.copyOf(ids);
    }

    /**
     * 把"需重解析"的文档批量投进解析队列（**复用 `O1` 的作业队列**，不另起一套）。
     *
     * <p>幂等由队列保证：幂等键是 {@code doc-parse:{docId}}，同一篇文档同时只有一个在跑
     * （`ParseWorker#enqueue` 的注释）。所以重复调用本方法**不会**造出重复作业 ——
     * `O5` §4 步 5 点名的"注意幂等"落在这里。
     *
     * @param ownerId 提交人（`O6` 的"谁触发了这次解析"要读它）；定时任务触发时传 {@code null}
     * @return 本次投递产生的 taskId 列表
     */
    public List<String> submitReparse(long kbId, Long ownerId, int limit) {
        List<Long> docIds = outdatedDocIds(kbId, limit);
        List<String> taskIds = new ArrayList<>(docIds.size());
        for (Long docId : docIds) {
            taskIds.add(parseWorker.enqueue(kbId, docId, ownerId));
        }
        log.info("[解析质量] 已投递重解析：kbId={} 篇数={}", kbId, taskIds.size());
        return List.copyOf(taskIds);
    }

    // ==================== 步 6：聚合指标 ====================

    /**
     * 解析质量聚合。
     *
     * @param kbId {@code null} = 全站
     */
    @Transactional(readOnly = true)
    public Metrics metrics(Long kbId) {
        List<FormatRow> byFormat = byFormat(kbId);

        long total = 0;
        long ready = 0;
        long indexing = 0;
        long failed = 0;
        long chars = 0;
        long pagesTotal = 0;
        long pagesWithText = 0;
        long tableTotal = 0;
        long tableParsed = 0;
        long imageTotal = 0;
        long imageParsed = 0;
        for (FormatRow row : byFormat) {
            total += row.total();
            ready += row.ready();
            indexing += row.indexing();
            failed += row.failed();
            chars += row.chars();
            pagesTotal += row.pagesTotal();
            pagesWithText += row.pagesWithText();
            tableTotal += row.tableTotal();
            tableParsed += row.tableParsed();
            imageTotal += row.imageTotal();
            imageParsed += row.imageParsed();
        }

        long issueCount = issueCount(kbId);
        return new Metrics(total, ready, indexing, failed, rate(ready + indexing, ready + indexing + failed),
                chars, pagesTotal, pagesWithText, rate(pagesWithText, pagesTotal),
                charsPerPage(chars, pagesTotal),
                tableTotal, tableParsed, rate(tableParsed, tableTotal),
                imageTotal, imageParsed,
                issueCount, byFormat, issueByKind(kbId));
    }

    private List<FormatRow> byFormat(Long kbId) {
        String sql = """
                select type,
                       count(*),
                       sum(case when status = 'READY' then 1 else 0 end),
                       sum(case when status = 'INDEXING' then 1 else 0 end),
                       sum(case when status = 'FAILED' then 1 else 0 end),
                       coalesce(sum(chars), 0),
                       coalesce(sum(pages_total), 0),
                       coalesce(sum(pages_with_text), 0),
                       coalesce(sum(table_total), 0),
                       coalesce(sum(table_parsed), 0),
                       coalesce(sum(image_total), 0),
                       coalesce(sum(image_parsed), 0)
                  from sys_document
                 where deleted_at is null
                """
                + (kbId == null ? "" : "   and kb_id = :kbId\n")
                + " group by type order by count(*) desc, type";
        var query = entityManager.createNativeQuery(sql);
        if (kbId != null) {
            query.setParameter("kbId", kbId);
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        List<FormatRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new FormatRow((String) r[0], longOf(r[1]), longOf(r[2]), longOf(r[3]), longOf(r[4]),
                    longOf(r[5]), longOf(r[6]), longOf(r[7]), longOf(r[8]), longOf(r[9]),
                    longOf(r[10]), longOf(r[11])));
        }
        return List.copyOf(out);
    }

    /**
     * 逐页问题的条数。
     *
     * <p>⚠️ **join {@code sys_document} 是刻意的**：这样它与上面那组文档指标用的是
     * **同一个总体**（都排除软删文档）。不 join 的话，"库里有 N 个文档、却有 M 条问题"
     * 会因为软删文档的问题行没被清掉而 M > N —— 判据 6 要求的"与库内真值逐项吻合"
     * 就会变成两套真值在比。
     */
    private long issueCount(Long kbId) {
        String sql = """
                select count(*) from sys_parse_issue i
                  join sys_document d on d.id = i.doc_id
                 where d.deleted_at is null
                """
                + (kbId == null ? "" : "   and i.kb_id = :kbId");
        var query = entityManager.createNativeQuery(sql);
        if (kbId != null) {
            query.setParameter("kbId", kbId);
        }
        return ((Number) query.getSingleResult()).longValue();
    }

    /** 按问题种类分布。{@code kind} 为 NULL 的旧行归到 {@code UNKNOWN}（**不伪造种类**）。 */
    private List<KindRow> issueByKind(Long kbId) {
        String sql = """
                select coalesce(i.kind, 'UNKNOWN'), count(*) from sys_parse_issue i
                  join sys_document d on d.id = i.doc_id
                 where d.deleted_at is null
                """
                + (kbId == null ? "" : "   and i.kb_id = :kbId")
                + " group by 1 order by count(*) desc, 1";
        var query = entityManager.createNativeQuery(sql);
        if (kbId != null) {
            query.setParameter("kbId", kbId);
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        List<KindRow> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new KindRow((String) r[0], longOf(r[1])));
        }
        return List.copyOf(out);
    }

    // ==================== 比值口径 ====================

    /**
     * 比率。
     *
     * <p>分母为 0 时返回 {@code 0} 而不是 {@code NaN} —— 后者会一路带到 JSON 里变成 {@code null}
     * （Jackson 默认不序列化 NaN），前端拿到 null 再算百分比就是"空白页"。
     * 而"没有分母"与"比率是 0"在这个页面上的呈现是一样的（都是"—"），所以取 0 不损失信息。
     */
    private static double rate(long numerator, long denominator) {
        return denominator <= 0 ? 0d : (double) numerator / denominator;
    }

    /**
     * 平均每页字符数。
     *
     * <p>⚠️ 分母用 {@code max(1, pagesTotal)}，且**不分页格式（TXT/MD）贡献 chars 但不贡献页数**
     * —— 于是当库里混有 MD/TXT 时，这个值是一个**上界**（真实值只会更小）。
     * 这与 {@code ParsedDocument#charsPerPage} 的"不分页按整篇一页算"是同一条口径，
     * 只是聚合层面无法逐篇兜底。页面上应标注口径，不要让读者以为它是精确值。
     */
    private static double charsPerPage(long chars, long pagesTotal) {
        return pagesTotal <= 0 ? chars : (double) chars / pagesTotal;
    }

    private static long longOf(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    // ==================== 结果类型 ====================

    /**
     * 版本分布。
     *
     * @param total          文档总数（不含软删）
     * @param current        产物已是当前版本的
     * @param outdated       产物是**旧版本**的
     * @param unknownVersion 产物**没有版本信息**的（本批之前的行）——
     *                       它和 {@code outdated} 一起构成"需要重解析"的那批，
     *                       分开报是为了让运维看清"是升级导致的，还是历史遗留"
     */
    public record Outdated(long kbId, long total, long current, long outdated, long unknownVersion) {

        /** 需要重解析的篇数。 */
        public long needsReparse() {
            return outdated + unknownVersion;
        }
    }

    /** 按格式的一行。 */
    public record FormatRow(String type, long total, long ready, long indexing, long failed, long chars,
                            long pagesTotal, long pagesWithText, long tableTotal, long tableParsed,
                            long imageTotal, long imageParsed) {
    }

    /** 按问题种类的一行。{@code kind} 为 {@code UNKNOWN} 表示旧行（列可空，未回填）。 */
    public record KindRow(String kind, long count) {
    }

    /**
     * 解析质量聚合。
     *
     * @param successRate   {@code (ready + indexing) / (ready + indexing + failed)}。
     *                      ⚠️ 分子含 {@code INDEXING}：解析完成即 INDEXING（{@code markParsed} 的口径），
     *                      所以它属"解析这一步成功"。而分母里的 {@code FAILED} **混了
     *                      解析失败与向量化失败**（没有列区分它们，原因在 {@code error_msg} 里）——
     *                      本指标因此是"这一步没失败的比例"，不是精确的"解析器成功率"
     * @param textLayerRate  {@code pagesWithText / pagesTotal}
     * @param tableParseRate {@code tableParsed / tableTotal} —— 与状态列**独立**的那条
     *                       （《06》§3.6：表格识别失败是静默故障，状态列看不出来）
     * @param charsPerPage   见 {@code charsPerPage} 的口径说明（混有 MD/TXT 时是上界）
     * @param issueCount     逐页问题条数，与 {@code issueByKind} 之和恒等（同一总体、同一次查询口径）
     */
    public record Metrics(long total, long ready, long indexing, long failed, double successRate,
                          long chars, long pagesTotal, long pagesWithText, double textLayerRate,
                          double charsPerPage,
                          long tableTotal, long tableParsed, double tableParseRate,
                          long imageTotal, long imageParsed,
                          long issueCount, List<FormatRow> byFormat, List<KindRow> issueByKind) {
    }
}
