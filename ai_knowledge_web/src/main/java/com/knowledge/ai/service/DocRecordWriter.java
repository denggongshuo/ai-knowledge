package com.knowledge.ai.service;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.text.BoilerplateRule;
import com.knowledge.ai.core.text.ContentFingerprint;
import com.knowledge.ai.pipeline.Block;
import com.knowledge.ai.pipeline.ParsedDocument;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code sys_document} 的**短事务写点**（`T-500` 的落库侧）。
 *
 * <h3>为什么不把这些语句直接写在 {@link DocService} 里</h3>
 * <p>因为**上传的写入发生在两个不同的线程上**：建行在 HTTP 线程（随请求提交），
 * 回写对象名与失败标记在 {@code taskExecutor} 的工作线程（任务体里）。
 * Spring 的 {@code @Transactional} 是**代理**语义 —— 同类内部自调用不生效，
 * 所以"给 DocService 自己加一个方法再自己调"是拿不到事务的。
 *
 * <h3>为什么每个方法都自成一个事务</h3>
 * <ul>
 *   <li>{@link #markStored} 跑在工作线程上，**调用它的时候 HTTP 线程的事务早已提交**
 *       （见 {@code DocService#upload} 的时序说明），所以它必须自己开；
 *       传播用默认的 {@code REQUIRED} 即可 —— 工作线程上本来就没有事务；</li>
 *   <li>{@link #markFailed} 用 {@code REQUIRES_NEW}：它是在**捕获异常之后**调的，
 *       而异常可能正是由"当前事务已被标记回滚"引起的（例如约束冲突）。
 *       若沿用同一个事务，这条失败标记会被一起回滚掉 —— 于是文档永远停在
 *       {@code UPLOADED}、`error_msg` 恒空，`G4-3`「失败给出具体原因」直接落空。
 *       {@code REQUIRES_NEW} 与 {@code AuditLogService#record}（不变量 I-12）是同一个取舍。</li>
 * </ul>
 *
 * <p>本类**只做写入**，不含任何业务判断 —— 判断留在 {@link DocService}，
 * 这样"为什么这么写"与"怎么写"不会混在两处。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocRecordWriter {

    private final EntityManager entityManager;

    /**
     * 建行并返回新 id。此时 {@code storage_path} 为 {@code null}、状态为 {@code UPLOADED}。
     *
     * <p>取 id 用「按 (kb_id, owner_id, md5) 倒序取第一条」而不是 {@code getGeneratedKeys}：
     * Hibernate 的 {@code createNativeQuery(...).executeUpdate()} **不返回自增键**
     * （返回的是受影响行数）。用同一事务内可见性保证拿到刚插的那条 ——
     * 这也是本方法必须是独立的 {@code @Transactional}` 方法的原因之一。
     */
    @Transactional
    public long insert(long kbId, long ownerId, String title, String fileName, String type,
                       long sizeBytes, String md5) {
        LocalDateTime now = LocalDateTime.now();
        entityManager.createNativeQuery("""
                        insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5,
                                                  storage_path, status, chunk_count,
                                                  table_total, table_parsed, image_total, image_parsed,
                                                  created_at, updated_at)
                        values (:kbId, :ownerId, :title, :fileName, :type, :size, :md5,
                                null, 'UPLOADED', 0, 0, 0, 0, 0, :now, :now)
                        """)
                .setParameter("kbId", kbId)
                .setParameter("ownerId", ownerId)
                .setParameter("title", title)
                .setParameter("fileName", fileName)
                .setParameter("type", type)
                .setParameter("size", sizeBytes)
                .setParameter("md5", md5)
                .setParameter("now", now)
                .executeUpdate();

        @SuppressWarnings("unchecked")
        List<Number> ids = entityManager.createNativeQuery(
                        "select id from sys_document where kb_id = :kbId and owner_id = :ownerId and md5 = :md5 "
                                + "order by id desc limit 1")
                .setParameter("kbId", kbId)
                .setParameter("ownerId", ownerId)
                .setParameter("md5", md5)
                .getResultList();
        if (ids.isEmpty()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "文档记录写入失败，请重试");
        }
        return ids.getFirst().longValue();
    }

    /** 转存成功后回写对象名。工作线程调用（`T-500` 的任务体第 3 步）。 */
    @Transactional
    public void markStored(long docId, String objectKey) {
        entityManager.createNativeQuery(
                        "update sys_document set storage_path = :path, updated_at = :now where id = :id")
                .setParameter("path", objectKey)
                .setParameter("now", LocalDateTime.now())
                .setParameter("id", docId)
                .executeUpdate();
    }

    /**
     * 进入解析态（`T-502` 的第 1 步）。
     *
     * <p>独立方法而不是并进 {@link #markParsed}：状态机里 {@code PARSING} 是**可观测的中间态**
     * （`G4-3` 的判据是"状态可观测"），用户轮询时要能看到"解析中"，
     * 而不是从「已上传」直接跳到终态 —— 那会让 100MB 文档在轮询里表现为"卡住"。
     */
    @Transactional
    public void markParsing(long docId) {
        entityManager.createNativeQuery(
                        "update sys_document set status = 'PARSING', error_msg = null, updated_at = :now "
                                + "where id = :id")
                .setParameter("now", LocalDateTime.now())
                .setParameter("id", docId)
                .executeUpdate();
    }

    /**
     * 解析成功：写产物路径、填质量列、推进到 {@code INDEXING}。
     *
     * <p><b>终态是 INDEXING 而不是 READY</b>：READY 的语义是"唯一可被检索的状态"（《03》§4.2），
     * 而向量还没写进 Milvus（`T-508` 属 S4-C）—— 这时标 READY 正是本项目最怕的"报告说成功"。
     * {@code chunk_count} **不同步更新**：分片在 {@code replaceChunks} 里回填
     * （`T-505` 已接上），这里不重复写。
     *
     * <p>⚠️ {@code image_total} 是 {@code S4-M05-C2} 新接的（`O3` §4 步 4）：解析阶段
     * **早就知道**图片数（{@code ParsedDocument#imageCount}），只是一直没写进质量列
     * —— "采了不用"。{@code image_parsed} 仍留 0，表示"未理解"（`T-512` 的 VL 才填）。
     * 于是含图文档的 {@code image_parsed < image_total} 恒成立，它会被
     * {@code DocService} 的"质量待改进"过滤命中 —— 这是**刻意的**：
     * 图片确实还没被理解，页面上的"有 N 张图（未理解）"就是这个意思。
     */
    @Transactional
    public void markParsed(long docId, String parsePath, int tableTotal, int tableParsed, int imageTotal,
                           int pagesTotal, int pagesWithText) {
        entityManager.createNativeQuery("""
                        update sys_document
                           set status = 'INDEXING', parse_path = :path,
                               table_total = :tableTotal, table_parsed = :tableParsed,
                               image_total = :imageTotal,
                               pages_total = :pagesTotal, pages_with_text = :pagesWithText,
                               error_msg = null, updated_at = :now
                         where id = :id
                        """)
                .setParameter("path", parsePath)
                .setParameter("tableTotal", tableTotal)
                .setParameter("tableParsed", tableParsed)
                .setParameter("imageTotal", imageTotal)
                .setParameter("pagesTotal", pagesTotal)
                .setParameter("pagesWithText", pagesWithText)
                .setParameter("now", LocalDateTime.now())
                .setParameter("id", docId)
                .executeUpdate();
    }

    /**
     * 可检索：向量已全部落 Milvus（`T-508`），推进 {@code INDEXING → READY}。
     *
     * <p><b>为什么必须是独立的一步、而不是并进 {@link #markParsed}</b>：
     * {@code READY} 的语义是「**唯一可被检索的状态**」（《03》§4.2）。解析完只完成了
     * "块有了"，向量还没进 Milvus —— 那时标 READY 就是本项目最怕的"报告说成功"
     * （`G4-3` 后半句盯的就是这一跳，也是 C2 §9 #52 登记的那条）。
     *
     * <p>调用方是 {@code DocParseService}，**只在向量化确实成功之后**调
     * （{@code ChunkVectorizer.Result#allowsReady()}）。
     */
    @Transactional
    public void markReady(long docId, int generation) {
        entityManager.createNativeQuery(
                        "update sys_document set status = 'READY', error_msg = null, "
                                + "index_generation = :generation, updated_at = :now "
                                + "where id = :id")
                .setParameter("generation", generation)
                .setParameter("now", LocalDateTime.now())
                .setParameter("id", docId)
                .executeUpdate();
    }

    /**
     * 读文档当前状态（供编排层"照实回报"用）。
     *
     * <p>存在的理由：{@code DocParseService} 的返回值以前写死 {@code "INDEXING"} ——
     * 向量化开关关掉、文档真停在中途态时，返回值会**比库里的状态更乐观**。
     * 状态只有一个事实源，回报要查它，而不是猜。
     */
    @Transactional(readOnly = true)
    public String statusOf(long docId) {
        List<?> rows = entityManager.createNativeQuery(
                        "select status from sys_document where id = :id")
                .setParameter("id", docId)
                .getResultList();
        return rows.isEmpty() || rows.getFirst() == null ? "UNKNOWN" : String.valueOf(rows.getFirst());
    }

    /**
     * 整批重建逐页问题（先删后插）。
     *
     * <p>调用时机：解析成功与失败**两条路都调** —— 失败的文档也要看得见
     * （《07》§2.6「让上传的人**当场**就知道」）。先删后插让重跑天然幂等：
     * 同一文档的问题行永远对齐"最近一次解析"。
     *
     * <p><b>不在 {@code markParsed} 里顺带写</b>：issue 行数是任意多的，
     * 与"更新文档一行"不是一个事务形状；而且失败路径没有 parse_path 可写，
     * 拆开让两条路各调各的，不硬凑同一个方法签名。
     */
    @Transactional
    public void replaceIssues(long docId, long kbId, List<ParsedDocument.DocumentIssue> issues) {
        entityManager.createNativeQuery("delete from sys_parse_issue where doc_id = :docId")
                .setParameter("docId", docId)
                .executeUpdate();
        if (issues == null || issues.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (ParsedDocument.DocumentIssue issue : issues) {
            entityManager.createNativeQuery("""
                            insert into sys_parse_issue
                                (doc_id, kb_id, page_no, reason, kind, disposition, created_at)
                            values (:docId, :kbId, :pageNo, :reason, :kind, :disposition, :now)
                            """)
                    .setParameter("docId", docId)
                    .setParameter("kbId", kbId)
                    .setParameter("pageNo", issue.pageNo())
                    // `reason` 是**纯原因文本**（`S4-E2` 起不再拼种类前缀 —— 种类有列了）。
                    // 同一信息存两份必然漂：改一次文案，前缀与列就对不上。
                    .setParameter("reason", truncate(issue.reason()))
                    .setParameter("kind", issue.kind().name())
                    .setParameter("disposition", issue.disposition().name())
                    .setParameter("now", now)
                    .executeUpdate();
        }
    }

    /**
     * 整批重建分片块（`T-505`，先删后插），并回填 {@code sys_document.chunk_count}。
     *
     * <h3>为什么是"整批重写"而不是增量</h3>
     * <p>与 {@link #replaceIssues} 同一条理由：重跑解析时块集可能整个变样
     * （换了 chunkSize、标题层级变了），增量对账的成本高于重写，而且会留下
     * "上一次的残留块"——那些块检索得到、却不再属于这篇文档，
     * 是最难排查的一类脏数据。先删后插让重跑天然幂等。
     *
     * <h3>为什么分两遍插（父块先、子块后）</h3>
     * <p>子块的 {@code parent_id} 要指向父块的**自增 id**，而那个 id 只有插进去才知道。
     * 分片阶段子块只持有"父块的 orderNo"（{@link Block#parentOrderNo()}）——
     * 那是刻意的：分片是纯函数，不该依赖数据库。
     *
     * <p>两遍插入**不依赖"父块一定排在子块前面"**这个假设。虽然 {@code ChunkingService}
     * 目前确实先产出父块，但把正确性建立在产出的顺序上，会在将来某次改动后
     * 变成一个静默的父子错位。
     *
     * @return 写入的**顶层块**数（= 回填的 {@code chunk_count}），
     *         口径见 D-5 §1.5 与 {@code BlockType#countsInChunkTotal()}
     */
    @Transactional
    public int replaceChunks(long docId, long kbId, List<Block> blocks) {
        entityManager.createNativeQuery("delete from sys_chunk where doc_id = :docId")
                .setParameter("docId", docId)
                .executeUpdate();

        int topLevel = 0;
        if (blocks != null && !blocks.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            // orderNo → 刚落库的父块 id
            Map<Integer, Long> parentIds = new HashMap<>();

            for (Block block : blocks) {
                if (!block.isTopLevel()) {
                    continue;
                }
                parentIds.put(block.orderNo(), insertChunk(docId, kbId, block, null, now));
            }
            for (Block block : blocks) {
                if (block.isTopLevel()) {
                    continue;
                }
                Long parentId = parentIds.get(block.parentOrderNo());
                if (parentId == null) {
                    // 分片产出了指向不存在父块的子块 —— 契约违例（D-5 §1.2）。
                    // 落一条 parent_id = null 子块会更糟：它既不会被回表取到，
                    // 又会让 G4-6 的"子块必有 TABLE 父块"断言在事后才发现是假的。
                    throw new BizException(ErrorCode.SYSTEM_ERROR,
                            "分片结果自相矛盾：第 " + block.orderNo() + " 块指向不存在的父块 #"
                                    + block.parentOrderNo() + "，本次解析未写入任何分片");
                }
                insertChunk(docId, kbId, block, parentId, now);
            }

            topLevel = (int) blocks.stream()
                    .filter(Block::isTopLevel)
                    .filter(b -> b.blockType().countsInChunkTotal())
                    .count();

            entityManager.createNativeQuery(
                            "update sys_document set chunk_count = :n, updated_at = :now where id = :id")
                    .setParameter("n", topLevel)
                    .setParameter("now", now)
                    .setParameter("id", docId)
                    .executeUpdate();

            // `O9` 步 3/4：块都落库了，现在做跨文档样板识别。
            // ⚠️ 位置是硬约束：必须在**全部 insert 之后**（计数要看得见本文档自己的块），
            // 且必须在**向量化之前**（样板块不进召回池，判据在 ChunkVectorizer 那一跳）
            markBoilerplate(docId, kbId, blocks);
        } else {
            // 空块集也要回填 0：上次解析若写过 chunk_count，不归零会留下
            // "文档没有块、却报告有 42 个分片"的假数
            entityManager.createNativeQuery(
                            "update sys_document set chunk_count = 0, updated_at = :now where id = :id")
                    .setParameter("now", LocalDateTime.now())
                    .setParameter("id", docId)
                    .executeUpdate();
        }
        return topLevel;
    }

    /**
     * 插一个块并返回它的自增 id。
     *
     * <p>取 id 用「按 (doc_id, order_no) 回查」而不是 {@code getGeneratedKeys} ——
     * 与 {@link #insert} 同一个理由：Hibernate 的 {@code createNativeQuery(...).executeUpdate()}
     * 不返回自增键。{@code (doc_id, order_no)} 在本次写入内唯一（orderNo 是 1..N），
     * 且刚做过全删，所以这条回查是确定的。
     */
    private Long insertChunk(long docId, long kbId, Block block, Long parentId, LocalDateTime now) {
        entityManager.createNativeQuery("""
                        insert into sys_chunk (doc_id, kb_id, chunk_type, parent_id, title_path,
                                               raw_content, content_sha256, order_no, page_no, created_at)
                        values (:docId, :kbId, :chunkType, :parentId, :titlePath,
                                :rawContent, :contentSha256, :orderNo, :pageNo, :now)
                        """)
                .setParameter("docId", docId)
                .setParameter("kbId", kbId)
                .setParameter("chunkType", block.blockType().name())
                .setParameter("parentId", parentId)
                .setParameter("titlePath", blankToNull(block.titlePath()))
                .setParameter("rawContent", block.rawContent())
                // `O9` 步 1：**块的原始内容**的指纹，不是投影文本（含标题路径）的 ——
                // 样板判定要的是"这段文本跨文档是否一样"，而标题路径恰恰因文档而异
                .setParameter("contentSha256", ContentFingerprint.of(block.rawContent()))
                .setParameter("orderNo", block.orderNo())
                .setParameter("pageNo", block.pageNo())
                .setParameter("now", now)
                .executeUpdate();

        @SuppressWarnings("unchecked")
        List<Number> ids = entityManager.createNativeQuery(
                        "select id from sys_chunk where doc_id = :docId and order_no = :orderNo")
                .setParameter("docId", docId)
                .setParameter("orderNo", block.orderNo())
                .getResultList();
        if (ids.isEmpty()) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "分片写入失败，请重试");
        }
        return ids.getFirst().longValue();
    }

    /** 空串与 {@code null} 在库里是两种值 —— 标题路径没有就是没有，不写空串进去。 */
    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    // ==================== O9 步 3/4 · 跨文档样板识别 ====================

    /**
     * 标记**跨文档样板**分片（`O9` 步 3/4）。
     *
     * <h3>计数口径：含本文档在内、**不同文档**数</h3>
     * <p>为什么按"文档数"而不是"块数"：同一段免责声明在一篇 200 页手册里可能出现 8 次，
     * 但那**只说明这一篇重复**，不说明它是跨文档样板。按块数计，一篇文档就能把阈值顶到 ——
     * 那会把"这篇文档自己重复"误判成"全公司都这么写"。
     *
     * <h3>为什么标记动作覆盖**全库同指纹的行**（含先前文档）</h3>
     * <p>这是本方法唯一一处"看起来多做了"的地方，理由必须写清楚：
     * 样板是**语料级的既有事实**，不是"第 20 篇文档到来时才发现的新情况"。
     * 若只标当前文档，{@code is_boilerplate} 的含义就依赖**入库顺序** ——
     * 前 19 篇是 0、后 6 篇是 1，而它们的内容**一模一样**。
     * 那种字段没法用来回答"这段为什么搜不到"，而那是它存在的唯一目的。
     *
     * <p>UPDATE 走 {@code idx_chunk_csha}（V19 建的），范围 = 同库 + 同指纹，代价可控。
     * ⚠️ **但它只改 MySQL 的标记**：先前那 19 篇的向量**已经在 Milvus 里**，本次不会去删。
     * 那个缺口（旧向量仍可被召回）需要一次离线回扫 + Milvus 删除，`O9` §6 与计划 §3.3
     * 明确把它放在了独立小批 —— 这里登记，见结项文档的遗留节。
     *
     * @return 本次被置为 1 的行数（含先前文档的），仅用于日志与取证
     */
    @Transactional
    public int markBoilerplate(long docId, long kbId, List<Block> blocks) {
        // 本文档各块指纹 → 其规范化文本（长度判据要用）。用 LinkedHashMap 去重并保序，
        // 让"哪几个指纹参与了判定"可复现
        Map<String, String> candidates = new LinkedHashMap<>();
        for (Block block : blocks) {
            String hash = ContentFingerprint.of(block.rawContent());
            if (hash != null) {
                candidates.putIfAbsent(hash, ContentFingerprint.normalize(block.rawContent()));
            }
        }
        if (candidates.isEmpty()) {
            return 0;
        }

        // 一次查回这批指纹在**其它文档**里的文档数（不是每块一次 COUNT）
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select content_sha256, count(distinct doc_id) from sys_chunk
                         where kb_id = :kbId and doc_id <> :docId and content_sha256 in (:hashes)
                         group by content_sha256
                        """)
                .setParameter("kbId", kbId)
                .setParameter("docId", docId)
                .setParameter("hashes", candidates.keySet())
                .getResultList();

        Map<String, Long> othersByHash = new HashMap<>(rows.size());
        for (Object[] row : rows) {
            othersByHash.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }

        List<String> hit = new ArrayList<>();
        for (Map.Entry<String, String> entry : candidates.entrySet()) {
            // +1 = 本文档自己。`BoilerplateRule.MIN_DOCS` 的口径是「含当前文档在内」
            long documentCount = othersByHash.getOrDefault(entry.getKey(), 0L) + 1;
            if (BoilerplateRule.shouldMark(documentCount, entry.getValue())) {
                hit.add(entry.getKey());
            }
        }
        if (hit.isEmpty()) {
            return 0;
        }

        int marked = entityManager.createNativeQuery("""
                        update sys_chunk set is_boilerplate = 1
                         where kb_id = :kbId and content_sha256 in (:hashes) and is_boilerplate = 0
                        """)
                .setParameter("kbId", kbId)
                .setParameter("hashes", hit)
                .executeUpdate();
        log.info("识别出跨文档样板分片（`O9` 步 4）：docId={} kbId={} 命中指纹数={} 标记行数={}"
                        + "（含先前文档，阈值 = 出现在 ≥{} 个文档且长度 {}~{} 字符，**不进向量召回池**）",
                docId, kbId, hit.size(), marked,
                BoilerplateRule.MIN_DOCS, BoilerplateRule.MIN_LENGTH, BoilerplateRule.MAX_LENGTH);
        return marked;
    }

    /**
     * 回填文档级内容指纹（`O9` 步 1）—— 规范化后的**整篇文本**的 sha256。
     *
     * <p>独立的 UPDATE 而不是并进 {@link #markParsed}：指纹的来源是**清洗后**的文本
     * （{@code CleanedDocument#document()#markdown()}），而 {@code markParsed} 的参数
     * 已经够多了（7 个）。多一个参数就是让每个调用点都要多传一个自己并不关心的值。
     */
    @Transactional
    public void markContentFingerprint(long docId, String contentSha256) {
        if (contentSha256 == null || contentSha256.isBlank()) {
            // 空文档没有指纹 —— 不写空串（空串是一个"有值的值"，会让 SQL 里的
            // `content_sha256 = ''` 把一批空文档判成互相重复）
            return;
        }
        entityManager.createNativeQuery(
                        "update sys_document set content_sha256 = :csha where id = :id")
                .setParameter("csha", contentSha256)
                .setParameter("id", docId)
                .executeUpdate();
    }

    /**
     * 回填向量模型归因（`O9` 步 7）—— 整篇文档的所有分片一起写。
     *
     * <p><b>为什么在向量化之后、且按 {@code doc_id} 整篇写</b>：一次 {@code vectorize}
     * 调用用的**是同一个模型**（它的 {@code Result#modelRef()} 就是那一次的证据）。
     * 逐块写要 N 次 UPDATE，而它们写的必然是同一个值。
     *
     * <p>⚠️ 只在向量化**确实写了东西**之后调（{@code allowsReady()}）。开关关掉时
     * 一条向量都没写，这时候盖章等于声称"这些是某模型产的"，而其实什么都没有。
     */
    @Transactional
    public void markVectorAttribution(long docId, String modelId, String modelVersion) {
        if (modelId == null || modelId.isBlank()) {
            // 归因不可得（EmbeddingModelRef.NONE）—— 留 NULL，界面按「未记录」渲染。
            // **不要**写空串：空串会被读成"记了，只是模型名是空的"
            log.info("向量模型归因不可得，本次不回填（界面按「未记录」展示）：docId={}", docId);
            return;
        }
        entityManager.createNativeQuery("""
                        update sys_chunk
                           set embed_model_id = :modelId, embed_model_version = :modelVersion
                         where doc_id = :docId
                        """)
                .setParameter("modelId", modelId)
                .setParameter("modelVersion", modelVersion)
                .setParameter("docId", docId)
                .executeUpdate();
    }

    /**
     * 标记失败并留下**具体原因**。
     *
     * <p>{@code G4-3} 的判据是「失败态给出**具体原因**（非"失败"两字）」，
     * 所以这里的 {@code reason} 由调用方给出可执行的话（如"文件存储失败：连接超时"），
     * 不是异常类名。超长截断到列宽 512。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(long docId, String reason) {
        String msg = reason == null || reason.isBlank() ? "处理失败，请重试" : reason.trim();
        if (msg.length() > 500) {
            msg = msg.substring(0, 500);
        }
        try {
            entityManager.createNativeQuery(
                            "update sys_document set status = 'FAILED', error_msg = :msg, updated_at = :now "
                                    + "where id = :id")
                    .setParameter("msg", msg)
                    .setParameter("now", LocalDateTime.now())
                    .setParameter("id", docId)
                    .executeUpdate();
        } catch (Exception ex) {
            // 连"记下失败"都失败了 —— 只能留日志，不能让异常再往上冒把任务体的原始失败原因盖掉
            log.error("回写文档失败状态时出错：docId={} 原始原因={} err={}", docId, msg, ex.getMessage());
        }
    }

    /** reason 截断到 {@code sys_parse_issue.reason} 的列宽 512。 */
    private static String truncate(String reason) {
        if (reason == null || reason.isBlank()) {
            return "解析发现问题（未提供细节）";
        }
        String v = reason.trim();
        return v.length() > 500 ? v.substring(0, 500) : v;
    }
}
