package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.SecretLevel;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.core.text.MarkdownTable;
import com.knowledge.ai.dto.doc.DocDtos;
import com.knowledge.ai.pipeline.ChunkingOptions;
import com.knowledge.ai.pipeline.ChunkingService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档详情 / 预览页的**只读**读侧（`T-600` / `T-602` / `T-603` / `T-604` / `T-605`）。
 *
 * <h3>本类为什么单独一个 service</h3>
 * <p>{@link DocService} 管的是文档的**生命周期**（上传 / 去重 / 任务 / 库内列表 / 解析问题），
 * 它已经 585 行；而这里是"打开一篇文档之后能看到的四件东西"。两者共享的只有
 * 「这个文档我看不看得到」这一个判定（见 {@link #requireVisibleDoc}）。
 *
 * <h3>🔴 只读语义是可断言的，不是自我声明</h3>
 * <p>本类**没有任何 insert/update/delete**，也不写审计（无写操作 ⇒ 无审计点；
 * 冻结清单仍 37）。用例 `DocDetailFlowTest` 有一条判据在跑完五条端点后比对
 * {@code sys_document.updated_at} 与 {@code sys_chunk} 行数**逐位不变** ——
 * 声明"我是只读的"不算数，跑一遍没变才算。
 *
 * <h3>可见性只有一处口径</h3>
 * <p>{@link #requireVisibleDoc} 复用 {@code DataScopeService#kbVisibility}（I-7 的落点），
 * **不另写 SQL**。库不可见 ⇒ {@code KB_ACCESS_DENIED(10022) + HTTP 403}，
 * 与 {@link DocService#upload} 那条路径**给同一个错**：同一个根因在两个页面上
 * 报不同的错，前端就得写两套处理。
 *
 * <h3>⚠️ token 数是派生的</h3>
 * <p>{@code sys_chunk} 没有 token 计数列。这里复用 {@link ChunkingOptions#estimateTokens}
 * （切分窗口用的**同一个**口径），不是新引入的近似 —— 若哪天要换成嵌入模型的精确
 * tokenizer，那是"全站分片窗口一起换"，不是本接口单独换。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocDetailService {

    /** 与 {@link DocService} 的列表接口同一格式（前端表格直接展示，不解析）。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** `T-` 前缀是需求 §4.5 的展示形态；两位/三位补零同其示例（`T-03` / `T-03-002`）。 */
    private static final String TABLE_REF_PREFIX = "T-";

    /** 分片列表的 `type` 闭集（对齐需求 §4.4 的四个展示分组 + 「全部」）。 */
    private static final List<String> TYPE_CLOSURE = List.of("all", "text", "table", "image", "summary");

    /**
     * 「正文」组（需求 §4.4：`PARAGRAPH` 另含 `HEADING`/`CODE`/`LIST` 同归正文）。
     *
     * <p>⚠️ `HYPOTHESIS_QA` **也归这里**：8 种 blockType 要归进 4 个展示分组，
     * 而需求的原文只点了那四种。HyQ 块是检索用的问句，展示上它就是一条正文，
     * 单开一档会让 chips 与 `ChunkStat` 的字段一起长出来（`G7-2` 的合计式也要跟着改）。
     */
    private static final List<String> TEXT_GROUP =
            List.of("PARAGRAPH", "HEADING", "CODE", "LIST", "HYPOTHESIS_QA");

    private static final int DEFAULT_PAGE_SIZE = 200;

    private final EntityManager entityManager;

    private final DataScopeService dataScopeService;

    /** 可见性判定的**唯一**落点（`I-7`）—— 本类不再自己写那段 SQL。 */
    private final DocAccessService docAccessService;

    // ==================================================================
    // T-600 · 文档元信息
    // ==================================================================

    /**
     * `GET /api/doc/{id}` —— 文档元信息（详情页与预览页共用同一个端点）。
     *
     * <p>{@code citations}/{@code downVotes}/{@code version}/{@code tags} 四个字段
     * **恒为 {@code null}**，理由逐条写在 {@link DocDtos.DocMetaView} 的 javadoc 里
     * （一句话：不是"没有数据"，是"没有采集方/落库方"，返回 0 会变成一句关于内容的断言）。
     */
    public DocDtos.DocMetaView meta(long docId) {
        requireVisibleDoc(docId);
        Object[] r = (Object[]) entityManager.createNativeQuery("""
                        select d.id, d.kb_id, k.name, d.title, d.file_name, d.type, d.size_bytes,
                               d.pages_total, d.chunk_count, d.status, k.secret_level,
                               u.real_name, d.created_at, d.md5, d.storage_path,
                               d.table_total, d.table_parsed, d.image_total, d.image_parsed,
                               dep.name, d.pages_with_text,
                               (select max(t.finished_at) from sys_async_task t
                                 where t.doc_id = d.id and t.type = 'doc-parse' and t.status = 'DONE'),
                               d.secret_level,
                               d.version_no,
                               -- `T-804`：这篇文档的分片被答案**实际引用**过多少次。
                               -- 只数 `cited = 1` 的行 —— `cited is null`（本批之前的行）**不算数**：
                               -- 那是"没采集"，不是"没被引用"。两者差一个量级
                               (select count(*) from sys_retrieval_trace_chunk c
                                 where c.doc_id = d.id and c.cited = 1)
                        from sys_document d
                        join sys_kb k on k.id = d.kb_id
                        left join sys_user u on u.id = d.owner_id
                        left join sys_department dep on dep.id = k.dept_id
                        where d.id = :id
                        """)
                .setParameter("id", docId)
                .getSingleResult();

        String type = (String) r[5];
        long pagesTotal = num(r[7]);
        long pagesWithText = num(r[20]);
        String kbLevel = (String) r[10];
        String ownLevel = (String) r[22];
        long citedCount = num(r[24]);
        // 🔴 生效密级 = max(文档级, 库级) —— 在 **Java 侧**算，且只走这一个方法。
        //    ⚠️ 不要改成 SQL 的 greatest()：密级名按字母序与密级序**错位**
        //    （greatest('INTERNAL','CONFIDENTIAL') 返回 'INTERNAL'）⇒ 判断做反，
        //    而现象是"有人看得见绝密"而不是报错。见 SecretLevel#max 与 RetrievalScope 的类注释。
        //    `sys_kb.secret_level` 是 NOT NULL ⇒ 生效值**恒非 null**（`max(null, kb)` 返回 kb）。
        SecretLevel effective = SecretLevel.max(level(ownLevel), level(kbLevel));
        String effectiveLevel = effective == null ? kbLevel : effective.name();

        return new DocDtos.DocMetaView(
                num(r[0]), num(r[1]), (String) r[2], (String) r[3], (String) r[4], type,
                num(r[6]), (int) pagesTotal, num(r[8]), (String) r[9],
                effectiveLevel,
                r[11] == null ? "—" : (String) r[11], time(r[12]),
                "v" + num(r[23]),
                fileDesc(type, pagesTotal, pagesWithText), (String) r[19],
                (String) r[13], (String) r[14],
                num(r[15]), num(r[16]), num(r[17]), num(r[18]),
                // `T-804`：引用次数转真。
                // ⚠️ `downVotes` 仍然是 null —— 它要的是"这篇文档的答案被点踩几次"，
                // 是 `sys_chat_message.feedback` × `trace_id` × `doc_id` 的**跨表口径**，
                // 与本列（引用计数）不是同一件事。一起转真会让两条判据看起来像一条
                citedCount, null, r[21] == null ? null : time(r[21]),
                kbLevel, ownLevel, null);
    }

    /** 库里的密级字符串 → 枚举；**认不出的一律当 {@code null}**（不抛：读侧不该为脏数据炸掉整页）。 */
    private static SecretLevel level(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SecretLevel.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 文件描述（需求 §4.7 要的「PDF（原生文本，非扫描件）」这类文案）。
     *
     * <p>⚠️ <b>{@code pages_total == 0} 时**不判**</b>：那一列是 {@code NOT NULL DEFAULT 0}，
     * 而 0 既可能是"整篇扫描件、统计确实是 0"、也可能是"这行早于页级统计落库"。
     * 两者的下一步动作不同（前者要 OCR，后者要重解析），所以文案必须分开 ——
     * 与 `KbDocRow.pagesTotal` 那条「`0/0` 必须渲染成 `—`」是同一个坑。
     */
    private static String fileDesc(String type, long pagesTotal, long pagesWithText) {
        String t = type == null || type.isBlank() ? "未知格式" : type;
        if (pagesTotal <= 0) {
            return t + "（文本层未统计）";
        }
        return pagesWithText > 0 ? t + "（原生文本，非扫描件）" : t + "（扫描件，无文本层）";
    }

    // ==================================================================
    // T-602 · 目录树
    // ==================================================================

    /**
     * `GET /api/doc/{id}/toc` —— 目录树，由 `chunk_type = 'HEADING'` 的块派生。
     *
     * <p>{@code level} 取该块 {@code title_path} 的分段数：{@code ChunkingService}
     * 是「先 pushToc 再产块」，所以标题块的 {@code title_path} **包含它自己** ——
     * 分段数就是它的层级。分隔符复用 {@link ChunkingService#TITLE_PATH_SEPARATOR}，
     * 不在这里再写一次 {@code " > "}（写两次就会有分叉的一天）。
     */
    @SuppressWarnings("unchecked")
    public DocDtos.TocView toc(long docId) {
        requireVisibleDoc(docId);
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select id, raw_content, title_path
                        from sys_chunk
                        where doc_id = :id and chunk_type = 'HEADING'
                        order by order_no
                        """)
                .setParameter("id", docId)
                .getResultList();

        List<DocDtos.TocItem> list = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            String path = (String) r[2];
            int level = path == null || path.isBlank()
                    ? 1
                    : path.split(java.util.regex.Pattern.quote(ChunkingService.TITLE_PATH_SEPARATOR), -1).length;
            list.add(new DocDtos.TocItem(String.valueOf(num(r[0])), (String) r[1], level));
        }
        return new DocDtos.TocView(list);
    }

    // ==================================================================
    // T-603 · 分片列表 + 分片统计
    // ==================================================================

    /**
     * `GET /api/doc/{id}/chunks?type=&page=&size=` —— 分片列表。
     *
     * <p>{@code type} 是**闭集**（{@code all/text/table/image/summary}）。闭集之外的取值
     * **显式报 {@code 10030}**，不静默当成 {@code all}：静默降级的表现是"我筛了图片，
     * 它把全部给我了"，而用户会以为那个库真没有图片块。
     */
    @SuppressWarnings("unchecked")
    public DocDtos.ChunkPage chunks(long docId, String type, Integer page, Integer size) {
        requireVisibleDoc(docId);
        String bucket = type == null || type.isBlank() ? "all" : type.trim();
        if (!TYPE_CLOSURE.contains(bucket)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "type 仅支持 " + String.join("/", TYPE_CLOSURE) + "，实际收到「" + type + "」");
        }

        int pageSize = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, 500);
        int offset = Math.max(0, (page == null || page <= 0 ? 1 : page) - 1) * pageSize;

        StringBuilder where = new StringBuilder(" where doc_id = :id");
        Map<String, Object> params = new HashMap<>();
        params.put("id", docId);
        appendBucket(where, params, bucket);

        long total = countOf("select count(*) from sys_chunk c" + where, params);

        Query query = entityManager.createNativeQuery("""
                        select c.id, c.doc_id, d.title, c.title_path, c.chunk_type, c.order_no,
                               c.raw_content, c.parent_id,
                               (select count(*) from sys_chunk x where x.parent_id = c.id)
                        from sys_chunk c
                        join sys_document d on d.id = c.doc_id
                        """ + where + " order by c.order_no limit :limit offset :offset");
        params.forEach(query::setParameter);
        query.setParameter("limit", pageSize);
        query.setParameter("offset", offset);

        List<Object[]> rows = query.getResultList();
        List<DocDtos.ChunkRow> list = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            list.add(new DocDtos.ChunkRow(
                    num(r[0]), num(r[1]), (String) r[2], (String) r[3], (String) r[4], (int) num(r[5]),
                    (String) r[6], tokensOf((String) r[6]),
                    r[7] == null ? null : num(r[7]), num(r[8])));
        }
        return new DocDtos.ChunkPage(list, total, statOf(docId));
    }

    /** `GET /api/doc/{id}/chunk-stat` —— 分片统计（`G7-2` 的落点）。 */
    public DocDtos.ChunkStat statOf(long docId) {
        Object[] r = (Object[]) entityManager.createNativeQuery("""
                        select
                          sum(case when parent_id is null then 1 else 0 end),
                          sum(case when parent_id is null and chunk_type in (:textGroup) then 1 else 0 end),
                          sum(case when parent_id is null and chunk_type = 'TABLE' then 1 else 0 end),
                          sum(case when parent_id is null and chunk_type = 'SUMMARY' then 1 else 0 end),
                          sum(case when parent_id is null and chunk_type = 'IMAGE' then 1 else 0 end),
                          sum(case when parent_id is not null then 1 else 0 end),
                          sum(case when parent_id is null and title_path is not null then 1 else 0 end)
                        from sys_chunk where doc_id = :id
                        """)
                .setParameter("id", docId)
                .setParameter("textGroup", TEXT_GROUP)
                .getSingleResult();

        long total = num(r[0]);
        long covered = num(r[6]);

        // `avgTokens` 的分母**必须与 `total` 同源**（顶层块）—— 分子分母不同源时，
        // 两个数说的不是一批行。而 token 是派生量（见类注释）⇒ 只能把顶层块取回来在 Java 侧算。
        // 这篇文档的行数在详情页量级（示例：222），且这一页本来就要把全部分片下发一次，
        // 故不做"只取长度"之类会引入第二口径的优化。
        @SuppressWarnings("unchecked")
        List<String> contents = entityManager.createNativeQuery(
                        "select raw_content from sys_chunk where doc_id = :id and parent_id is null")
                .setParameter("id", docId)
                .getResultList();
        Double avg = null;
        if (total > 0) {
            long sum = 0;
            for (String content : contents) {
                sum += tokensOf(content);
            }
            // 分母用**实查到的顶层块数**（与上面的 total 同一条 where），
            // 不用 contents.size() —— 那样两个数会各自漂移，而它们本该是同一个数
            avg = Math.round(sum * 10.0 / total) / 10.0;
        }

        return new DocDtos.ChunkStat(
                total, num(r[1]), num(r[2]), num(r[3]),
                // 图片块：**0 与"这类块根本不存在"要分开** —— `T-512` 之前落的文档没有 IMAGE 块。
                // 用 null 表达"这个维度对它不适用"，与 `imageTotal?` 的可空同源
                num(r[4]) == 0 ? null : num(r[4]),
                num(r[5]), avg,
                total == 0 ? 0.0 : Math.round(covered * 1000.0 / total) / 10.0);
    }

    // ==================================================================
    // T-604 · 父块 + 全部子块（`G7-1`）
    // ==================================================================

    /**
     * `GET /api/doc/{id}/chunks/{parentId}` —— 父块完整内容 + **全部**子块。
     *
     * <p>{@code parentId = 0} **显式报错**：`sys_chunk.parent_id` 的 javadoc 写着
     * 「**禁止用 `0` 表示"无父块"**」，那条纪律在接口层同样成立 ——
     * 放行 0 等于把这个哨兵从后门放回来（它匹配不到任何行，表现得像"这张表没有子块"，
     * 而那与"父块 id 传错了"是两件事）。
     */
    @SuppressWarnings("unchecked")
    public DocDtos.ParentChunkView parentChunk(long docId, long parentId) {
        requireVisibleDoc(docId);
        if (parentId <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "parentId 必须是父块的真实 id（收到 " + parentId + "）。"
                            + "本接口不接受 0 —— 顶层块的 parent_id 是 NULL，不是 0");
        }

        List<Object[]> parents = entityManager.createNativeQuery("""
                        select id, order_no, title_path, raw_content, chunk_type, parent_id
                        from sys_chunk
                        where id = :pid and doc_id = :docId
                        """)
                .setParameter("pid", parentId)
                .setParameter("docId", docId)
                .getResultList();
        if (parents.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "文档 " + docId + " 里没有 id = " + parentId + " 的分片");
        }
        Object[] p = parents.get(0);
        if (!"TABLE".equals(p[4]) || p[5] != null) {
            // 传进来的是子块、或不是表格块 —— 两种都是"调用方拿错了 id"，
            // 而它需要的下一步动作不同，所以分开说
            throw new BizException(ErrorCode.NOT_FOUND,
                    p[5] != null
                            ? "id = " + parentId + " 是表格的**子块**，请传它的父块 id"
                            : "id = " + parentId + " 不是表格块（实际类型 " + p[4] + "），本接口只服务表格的父子关系");
        }

        String content = (String) p[3];
        MarkdownTable.Shape shape = MarkdownTable.of(content);
        int orderNo = (int) num(p[1]);
        String ref = TABLE_REF_PREFIX + String.format("%02d", orderNo);

        List<Object[]> childRows = entityManager.createNativeQuery("""
                        select id, order_no, raw_content
                        from sys_chunk
                        where parent_id = :pid
                        order by order_no
                        """)
                .setParameter("pid", parentId)
                .getResultList();

        List<DocDtos.ParentChunkView.ChildBlock> children = new ArrayList<>(childRows.size());
        int seq = 0;
        for (Object[] c : childRows) {
            seq++;
            children.add(new DocDtos.ParentChunkView.ChildBlock(
                    num(c[0]), (int) num(c[1]),
                    ref + "-" + String.format("%03d", seq), (String) c[2]));
        }

        return new DocDtos.ParentChunkView(
                new DocDtos.ParentChunkView.ParentBlock(
                        num(p[0]), orderNo, ref, shape.caption(), (String) p[2],
                        // 本页返回**整表**：需求 §4.5 对这一页的措辞是「N 行 × M 列全部返回」。
                        // "命中行 ± 窗口"的裁剪只属于检索回表（`TableWindow`，T-705）——
                        // 用户自己点进来看的表没有"命中行"
                        shape.rows(), shape.cols(), shape.rows(), content),
                children, children.size());
    }

    // ==================================================================
    // T-605 · 索引任务记录
    // ==================================================================

    /**
     * `GET /api/doc/{id}/index-tasks` —— 该文档的解析/索引任务记录。
     *
     * <p>底表是 {@code sys_async_task}（**没有独立的 index-task 表**），
     * 按 {@code type = 'doc-parse'} 且 {@code doc_id} 过滤、新→旧。
     *
     * <p><b>{@code status} 原样下发 {@code AsyncTaskStatus}</b>（{@code PENDING/RUNNING/
     * DONE/FAILED}），**不在这一层改名**。
     *
     * <p>🔴 本批第一版在这里做了 {@code DONE → SUCCESS} 的映射，**随后撤掉了** ——
     * 理由不是"哪个词更好看"，而是：一旦在 API 边界改名，这个取值就**再也不可能
     * 三方逐字相等**，`check-enums.mjs` 的取值登记表就覆盖不了它（它只能比"三方一致"），
     * 于是后端/前端/mock 三份词表又回到"靠人同步"。不改名时三者相同，
     * 那条闸能机械地盯住它。
     *
     * <p>⚠️ mock 与前端类型里原本还有一个 {@code INFO} —— 它在本仓**没有任何定义**
     * （既不在 {@code AsyncTaskStatus} 里，CSS 里也没有 {@code .dot.info}，那一行的圆点
     * 本来就是裸的）⇒ 本批把这个取值收回。{@code DONE} 也是本仓既有词汇
     * （{@code DownloadStatus} 就是 {@code PACKING/DONE/EXPIRED/FAILED}）。
     *
     * <p><b>{@code durationSec}</b> = {@code finished_at - locked_at}：这张表**没有
     * started_at**，{@code locked_at} 是"被 worker 领取"的时刻，是最接近执行起点的那个事实。
     *
     * <p><b>{@code chunkCount}</b> 取 {@code chunk_total}；该列 {@code 0} 的口径是
     * 「未知」（`V17` 的列注释：「前端展示"处理中"而不是 0%」）⇒ **0 下发为 {@code null}**，
     * 否则页面上会出现一句"0 个分片"，而那是关于结果的断言。
     */
    @SuppressWarnings("unchecked")
    public DocDtos.IndexTaskPage indexTasks(long docId) {
        requireVisibleDoc(docId);
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select task_id, stage, status, created_at, finished_at, locked_at,
                               chunk_total, error_msg
                        from sys_async_task
                        where doc_id = :id and type = 'doc-parse'
                        order by created_at desc
                        """)
                .setParameter("id", docId)
                .getResultList();

        List<DocDtos.IndexTaskRow> list = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            long chunkTotal = num(r[6]);
            Long duration = durationSec(r[5], r[4]);
            list.add(new DocDtos.IndexTaskRow(
                    (String) r[0],
                    r[1] == null ? "" : (String) r[1],
                    (String) r[2],
                    time(r[3]),
                    chunkTotal <= 0 ? null : chunkTotal,
                    duration,
                    (String) r[7],
                    // 同 `DocMetaView.version`：没有该来源，不给一个像版本号的东西
                    null));
        }
        return new DocDtos.IndexTaskPage(list, list.size());
    }

    // ==================================================================
    // 共用
    // ==================================================================

    /**
     * 文档必须**存在**且**其所属库对当前用户可见**。
     *
     * <p>两种失败的错**必须不同**（照 {@link DocService} 的 `requireKbVisible` 范式：
     * 先判存在、再判可见）：
     * <ul>
     *   <li>文档不存在/已软删 → {@code DOC_NOT_FOUND(10044)}（HTTP 200）；</li>
     *   <li>文档在、库不可见 → {@code KB_ACCESS_DENIED(10022)}（**HTTP 403**）。</li>
     * </ul>
     * 后者**不许**退化成 10044：那会把「你可以去申请授权」说成「这文档没了」，
     * 两件事的下一步动作完全不同（「判不了必须与判否分开报」）。
     *
     * <p>⚠️ **实现已移到 {@link DocAccessService}**（`S7-M06-B`）—— 本方法只是个转发。
     * 抽出去的理由：`T-601` 的属性修改与 `T-610` 的单篇重建要用**同一个**判定，
     * 而"同一个根因在两处各写一遍"就是多一个漏掉的机会，**漏掉的后果是越权可见**。
     */
    private void requireVisibleDoc(long docId) {
        docAccessService.requireVisibleDoc(docId);
    }

    /**
     * 按展示分组往 where 里补一档条件。
     *
     * <p>四个桶**互斥且完备**（`G7-2` 靠这个）：表格只算父块（子块走 {@code tableSubBlocks}）、
     * 其余四种 blockType 全归正文。
     */
    private void appendBucket(StringBuilder where, Map<String, Object> params, String bucket) {
        switch (bucket) {
            case "all" -> {
                // 全部分组 = 顶层块（子块不属于任何展示分组）
                where.append(" and c.parent_id is null");
            }
            case "text" -> {
                where.append(" and c.parent_id is null and c.chunk_type in (:textGroup)");
                params.put("textGroup", TEXT_GROUP);
            }
            case "table" -> where.append(" and c.parent_id is null and c.chunk_type = 'TABLE'");
            case "image" -> where.append(" and c.parent_id is null and c.chunk_type = 'IMAGE'");
            case "summary" -> where.append(" and c.parent_id is null and c.chunk_type = 'SUMMARY'");
            default -> throw new IllegalStateException("未处理的展示分组：" + bucket);
        }
    }

    private long countOf(String sql, Map<String, Object> params) {
        Query query = entityManager.createNativeQuery(sql);
        params.forEach(query::setParameter);
        return ((Number) query.getSingleResult()).longValue();
    }

    private static Long durationSec(Object lockedAt, Object finishedAt) {
        LocalDateTime from = toTime(lockedAt);
        LocalDateTime to = toTime(finishedAt);
        if (from == null || to == null) {
            return null;
        }
        long sec = Duration.between(from, to).getSeconds();
        return Math.max(0, sec);
    }

    private static String time(Object value) {
        LocalDateTime t = toTime(value);
        return t == null ? null : t.format(TIME_FORMAT);
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

    private static int tokensOf(String rawContent) {
        return (int) Math.round(ChunkingOptions.estimateTokens(rawContent == null ? "" : rawContent));
    }

    private static long num(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }
}
