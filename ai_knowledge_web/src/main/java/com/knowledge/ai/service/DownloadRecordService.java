package com.knowledge.ai.service;

import com.knowledge.ai.core.model.PageQuery;
import com.knowledge.ai.core.model.PageResult;
import com.knowledge.ai.dto.doc.DocDtos;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 下载记录（`sys_download_record` · `V40` · `T-615`）—— **写一处、读一处**。
 *
 * <h3>它与审计（`sys_audit_log`）不是一回事，别合并</h3>
 * <p>门禁 `G7-9` 要求每次下载**同时**留下两份证据，它们服务两个完全不同的消费者：
 * <ul>
 *   <li>{@code sys_audit_log}（`@AuditLog` 切面写的）—— **合规留痕**：谁在何时做了什么、
 *       含高危分级（批量下载 = HIGH）。它的读者是审计员，走 `/api/audit/logs`。</li>
 *   <li>本表 —— **面向用户的历史清单**：我下过哪些文件、多大、什么时候。
 *       它的读者是下载页的「下载记录」Tab。{@code V40} 的文件头注写了为什么不复用审计表
 *       （审计的 {@code changes_json} 是"变更对照"结构，按人分页要退化成 JSON 解析）。</li>
 * </ul>
 * <p>两者的写入时机也因此不同：审计挂在**动作**上（受理打包时一条），
 * 记录挂在**文件**上（打包里每篇各一条）。</p>
 *
 * <h3>🔴 写失败**不吞**（与 `ModelUsageRecorder` 的取舍相反）</h3>
 * <p>用量是观测数据 —— 写不进去不该把一篇能解析的文档判失败。
 * 下载记录不是：门禁 `G7-9` 的原话是「每次下载**同时具备**审计证据和下载记录证据」——
 * 记录写不进去的时候，"这次下载发生过"就没有面向用户的那一半证据，
 * 而这个缺口**恰恰只可能出现在异常路径上**（正常路径它必然写成功）。
 * 吞掉它等于让 `G7-9` 在两个方向上不对称：留下了合规审计、却少了用户可见的历史。
 * <p>所以：抛出去 ⇒ 请求失败 ⇒ 用户看到明确的错，而不是"以为下到了、历史里却没有"。
 * ⚠️ 这不是"宁可不可用"：插入一张两列的窄表失败，说明库本身有问题，
 * 那种时候"静默地少一条记录"是更坏的结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadRecordService {

    /** 单篇下载（{@code GET /doc/download-url/{docId}}）。与前端 {@code DownloadRecord.type} 逐字一致。 */
    public static final String TYPE_SINGLE = "SINGLE";

    /** 打包下载中的一篇（每篇各一条）。 */
    public static final String TYPE_BATCH = "BATCH";

    /**
     * 展示列的占位 —— **只在 join 真的落空时用**（文档 / 知识库那一行已经不存在了）。
     *
     * <p>为什么是「已删除」而不是空串：下载历史**应当保留**已经下过的文件记录
     * （用户回头要查"我什么时候下过那份材料"），而空标题在表格里看起来像**数据缺失**。
     * 两者对用户的含义完全相反 —— 一个说"没了"，一个说"系统坏了"。
     *
     * <p>⚠️ <b>软删（进回收站）走不到这里</b>：{@link #SELECT_ROWS} 的 join **刻意不带**
     * {@code d.deleted_at is null} —— 于是被软删的文档**仍然 join 得到它当初的标题**。
     * 这是有意的：下载是既成事实，"我下过的那个文件叫什么"不因为**别人后来**
     * 把它挪进回收站而变得不可知。占位留给真正取不到名字的那一档
     * （`T-616` 的彻底删除 / 知识库被硬删）。
     */
    private static final String DELETED = "已删除";

    /** 取不到来源 IP 时的占位。列表里一个空单元格会被读成"加载失败"。 */
    private static final String NO_IP = "—";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 列表与导出**共用的**取数语句（末尾不带 {@code order by} / {@code limit}，
     * 由调用方各自补）。两个 {@code left join} 的理由见 {@link #page}。
     */
    private static final String SELECT_ROWS = """
            select r.id, r.doc_id, d.title, k.name, r.size_bytes,
                   u.real_name, r.created_at, r.ip, r.download_type
              from sys_download_record r
              left join sys_document d on d.id = r.doc_id
              left join sys_kb k on k.id = r.kb_id
              left join sys_user u on u.id = r.user_id
             where r.user_id = :userId
            """;

    private final EntityManager entityManager;

    /**
     * 写一条下载记录。
     *
     * <p>⚠️ {@code sizeBytes} 是**该文档**的字节数（打包时也是单篇大小，不是整个 zip）
     * —— 前端那列叫「大小」，用户看的是"这个文件多大"。
     *
     * @param ip 来源 IP；**可空**（取不到时落 NULL，读出来显示 {@code —}）。
     *           ⚠️ 它来自 {@code WebUtils#clientIp} 的代理头，**可伪造** ——
     *           只用于展示与追溯，不参与任何权限判断（那条纪律写在该方法的 javadoc 里）
     */
    @Transactional
    public void record(long docId, long kbId, long userId, String type, long sizeBytes, String ip) {
        entityManager.createNativeQuery(
                        "insert into sys_download_record (doc_id, kb_id, user_id, download_type, "
                                + "size_bytes, ip, created_at) "
                                + "values (:docId, :kbId, :userId, :type, :size, :ip, :now)")
                .setParameter("docId", docId)
                .setParameter("kbId", kbId)
                .setParameter("userId", userId)
                .setParameter("type", type)
                .setParameter("size", Math.max(sizeBytes, 0L))
                .setParameter("ip", ip)
                .setParameter("now", LocalDateTime.now())
                .executeUpdate();
    }

    /**
     * 我的下载历史，时间倒序。
     *
     * <p>🔴 <b>按 {@code user_id} 过滤</b> —— 这是"我的下载记录"，不是"全站下载流水"。
     * 后者若将来要做，那是审计页的事（`sys_audit_log` 里已经有）；
     * 这条链路上没有任何地方需要把别人的下载清单展示给当前用户。
     *
     * <p>展示列 {@code docTitle} / {@code kbName} **查询时 join**（`V40` 头注的口径）。
     * 两个都是 {@code left join}：文档 / 知识库被删之后记录**仍然要在**，
     * 用 {@code inner join} 会让"删了一篇文档"顺带抹掉与之相关的下载历史。
     * <p>⚠️ 且 join **不带** {@code deleted_at is null}：软删的文档仍显示它当初的标题
     * （理由见 {@link #DELETED}）。
     */
    @Transactional(readOnly = true)
    public PageResult<DocDtos.DownloadRecordRow> page(long userId, PageQuery query) {
        int size = query.normalizedSize();
        int offset = query.offset();

        long total = ((Number) entityManager.createNativeQuery(
                        "select count(*) from sys_download_record where user_id = :userId")
                .setParameter("userId", userId)
                .getSingleResult()).longValue();

        // ⚠️ order by 要带 id 兜底：created_at 是**秒级** DATETIME，
        // 同一秒内批量写下的 N 条 BATCH 记录时间完全相同 —— 只按它排序，
        // 翻页时行序在两次查询之间可以不一样（同一条记录出现两次 / 漏掉）。
        Query rows = entityManager.createNativeQuery(SELECT_ROWS
                        + " order by r.created_at desc, r.id desc limit :limit offset :offset")
                .setParameter("userId", userId)
                .setParameter("limit", size)
                .setParameter("offset", offset);

        List<DocDtos.DownloadRecordRow> list = new ArrayList<>();
        for (Object row : rows.getResultList()) {
            list.add(toRow((Object[]) row));
        }
        return PageResult.of(list, total, query);
    }

    /**
     * 导出用：**不分页**地把我的下载记录取出来（`T-615` 的 `POST /doc/export-list`）。
     *
     * <p>⚠️ 它与 {@link #page} **共用同一段 SQL 与同一个行映射**（{@link #SELECT_ROWS} /
     * {@link #toRow}）：导出与页面显示必须是**同一份数据**，两份写法必然漂移，
     * 而漂移的表现是"导出来的和屏幕上看到的不一样"—— 用户会先怀疑自己看错了。
     *
     * @param limit 硬上限。超过它时**只导出前 limit 条**并记 warn ——
     *              ⚠️ 这是一处**已知的降级**（用户不会被告知被截断），登记在本批 §9 遗留：
     *              要正确做它需要"分页流式写 CSV"，而那与"最多 2GB 的打包下载"是两件事，
     *              本批不做。一万条下载记录在任何真实使用下都远达不到
     */
    @Transactional(readOnly = true)
    public List<DocDtos.DownloadRecordRow> listForExport(long userId, int limit) {
        Query rows = entityManager.createNativeQuery(SELECT_ROWS
                        + " order by r.created_at desc, r.id desc limit :limit")
                .setParameter("userId", userId)
                .setParameter("limit", limit);
        List<DocDtos.DownloadRecordRow> list = new ArrayList<>();
        for (Object row : rows.getResultList()) {
            list.add(toRow((Object[]) row));
        }
        if (list.size() >= limit) {
            log.warn("下载记录导出已达上限 {} 条，超出部分**未导出**：userId={}", limit, userId);
        }
        return list;
    }

    private static DocDtos.DownloadRecordRow toRow(Object[] r) {
        return new DocDtos.DownloadRecordRow(
                ((Number) r[0]).longValue(),
                ((Number) r[1]).longValue(),
                r[2] == null ? DELETED : (String) r[2],
                r[3] == null ? DELETED : (String) r[3],
                r[4] == null ? 0L : ((Number) r[4]).longValue(),
                r[5] == null ? DELETED : (String) r[5],
                time(r[6]),
                r[7] == null || ((String) r[7]).isBlank() ? NO_IP : (String) r[7],
                (String) r[8]);
    }

    /** 时间列在 JDBC 侧可能是 {@code Timestamp} 也可能是 {@code LocalDateTime}，两者都接。 */
    private static String time(Object value) {
        LocalDateTime t;
        if (value instanceof LocalDateTime ldt) {
            t = ldt;
        } else if (value instanceof java.sql.Timestamp ts) {
            t = ts.toLocalDateTime();
        } else {
            return null;
        }
        return t.format(TIME_FORMAT);
    }
}
