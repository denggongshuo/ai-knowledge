package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.KbConfigDefaults;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.storage.DocStorage;
import com.knowledge.ai.core.text.ContentFingerprint;
import com.knowledge.ai.pipeline.ChunkingOptions;
import com.knowledge.ai.pipeline.ChunkingService;
import com.knowledge.ai.pipeline.ParseException;
import com.knowledge.ai.pipeline.ParsedDocument;
import com.knowledge.ai.pipeline.PdfDocumentParser;
import com.knowledge.ai.pipeline.PlainTextParser;
import com.knowledge.ai.pipeline.TikaDocumentParser;
import com.knowledge.ai.pipeline.TextLayerGuard;
import com.knowledge.ai.pipeline.cleaning.CleanedDocument;
import com.knowledge.ai.pipeline.cleaning.CleaningOptions;
import com.knowledge.ai.pipeline.cleaning.DocumentCleaner;
import com.knowledge.ai.pipeline.enhance.IndexEnhancer;
import com.knowledge.ai.pipeline.routing.PageContentRouter;
import com.knowledge.ai.pipeline.vectorize.ChunkVectorizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 解析编排（`T-502`）：从「源文件在 MinIO」到「产物落 MinIO + 质量列落库 + 问题行落库」。
 *
 * <h3>流水线的位置</h3>
 * <p>由 {@link DocService} 的上传任务体在 {@code markStored} 之后调用 ——
 * 解析不是独立接口，而是**上传任务的后续阶段**（幂等键沿用同一个）。
 * 这样"传完就解析"是默认行为，`T-406` 的重建索引将来复用同一个入口做"重跑解析"。
 *
 * <h3>编排顺序（每一步为什么在那儿）</h3>
 * <pre>
 *   markParsing          ← 先让用户看到「解析中」（G4-3 状态可观测）
 *   读回源文件           ← 从 storage_path 读，不信任调用方传路径
 *   落临时文件           ← opendataloader 只吃路径
 *   按类型分派           ← PDF / Office / 纯文本 三条路，见下
 *   TextLayerGuard       ← 「无文本层」检测（P0，T-0S1 §六-1）
 *   storeArtifact        ← 产物落 parsed/ 前缀
 *   markParsed           ← INDEXING + 质量列 + parse_path
 *   replaceIssues        ← 逐页问题（成功与失败两条路都调）
 * </pre>
 *
 * <h3>失败的两层兜底</h3>
 * <ul>
 *   <li>{@link ParseException}（业务失败）：文案已可读，直接落 {@code error_msg}；</li>
 *   <li>其它异常（基础设施炸）：包装成统一文案，原始异常进日志。</li>
 * </ul>
 * 两条路的**共同终点**是 {@code replaceIssues} —— 失败的文档也要看得见
 * （《07》§2.6「让上传的人当场就知道」）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocParseService {

    /** 产物对象名里的文件名。 */
    static final String ARTIFACT_NAME = "parse.json";

    /** 纯文本路径直接按字节解（UTF-8 严格）；其余二进制格式落临时文件再分派。 */
    private static final Set<String> PLAIN_TEXT = Set.of("TXT", "MD");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DocStorage docStorage;

    private final DocRecordWriter recordWriter;

    private final ChunkingService chunkingService;

    /** 清洗层（`优化/O3`）—— 解析与分片之间的纯函数变换，见 {@link DocumentCleaner}。 */
    private final DocumentCleaner documentCleaner;

    /** 索引增强（`T-507`）—— 分片之后的文档级摘要调用，见 {@link IndexEnhancer}。 */
    private final IndexEnhancer indexEnhancer;

    /** 向量化（`T-508`）—— 分片之后的嵌入 + Milvus 写入，见 {@link ChunkVectorizer}。 */
    private final ChunkVectorizer chunkVectorizer;

    /** 读已落库的块给向量化用（主键必须是落库后的 chunk.id）。 */
    private final ChunkReader chunkReader;

    /** 内容级近重复识别（`优化/O9` 步 1/2）—— **只提示，不阻断**。 */
    private final ContentDuplicateDetector contentDuplicateDetector;

    /**
     * 页内容路由位（`优化/O2` 步 4）。
     *
     * <p>v1 注入的是 {@link com.knowledge.ai.pipeline.routing.NoOpPageContentRouter}
     * —— **只登记不处理**。`S6` 接 OCR/VL 时改成注入 Ocr 实现即可，
     * 本类、落库、前端都不必动（那正是把路由位抽出来的目的）。
     */
    private final PageContentRouter pageContentRouter;

    private final jakarta.persistence.EntityManager entityManager;

    /**
     * 解析一篇文档（**在工作线程上执行，无登录态** —— 调用方在提交任务时已复核权限）。
     *
     * <p>本方法**不抛异常**：解析失败是业务结果（文档变 {@code FAILED}），不是系统错误。
     * 任务体据此决定 {@code context.complete} 还是 {@code context.fail}。
     *
     * @return 产物摘要（日志与任务结果用；不下发前端 —— 前端走列表与轮询）
     */
    public Map<String, Object> parseDocument(long docId) {
        Row row = requireRow(docId);
        recordWriter.markParsing(docId);

        ParsedDocument parsed = null;
        // 与 parsed 同一种写法：在 try 外声明，成功路径上必然非空（失败路径已 return）。
        // 放在里面的话成功路径读不到它，而"清洗结果"正是成功路径要报的东西
        CleanedDocument cleaned = null;
        String parsePath = null;
        int chunkCount = 0;
        // 同一种写法：`O9` 步 1/2 的近重复提示要在成功路径上报出去（进 task 结果），
        // 而它的取值点在 try 里面
        ContentDuplicateDetector.Hint duplicateHint = ContentDuplicateDetector.Hint.NONE;
        try {
            byte[] source;
            try {
                source = docStorage.open(row.storagePath());
            } catch (Exception ex) {
                // ⚠️ **取源文件失败是基础设施问题**（MinIO 抖动 / 超时 / 对象暂时不可达），
                //    与"这份文件本身不行"是**两件事**。一起判死，会让一次网络抖动永久毁掉
                //    一篇本来能解析的文档 —— 这是 `O1` §4 步 7 点名的那个区分。
                //    故单独 catch 并标 `retryable=true`，由 `ParseWorker` 退避重试。
                String why = "读取源文件失败：" + readable(ex) + "，稍后会自动重试";
                recordWriter.markFailed(docId, why);
                log.error("读取源文件失败（基础设施类，可重试）：docId={}", docId, ex);
                return Map.of("docId", docId, "status", "FAILED", "retryable", true, "reason", why);
            }
            parsed = dispatch(row.type(), source, row.fileName());
            // ⚠️ 体检在清洗**之前**：TextLayerGuard 的判据吃 chars，而清洗只减不增。
            // 反过来排的话，一篇正常文档会因为"剥掉了页眉"而掉到扫描件的阈值以下 —— 误报。
            //
            // `S4-E2`：这里是**两层**判据，别搞混 ——
            //   · check() 是**文档级兜底**：整篇不可用就抛（行为本批一行未改）；
            //   · merge() 是**页级登记**：哪几页丢了内容（混合文档的 2 页扫描件就靠它）。
            // 页级那半边**不在这里抛** —— 部分页缺失是"登记 + 路由"，不是"整篇失败"。
            TextLayerGuard.check(parsed);

            // `优化/O3` 逐文档清洗：页眉页脚剥离。位置有两条约束，缺一不可 ——
            //   ① 在 **storeArtifact 之后**（产物存的是**原始解析结果**）：清洗是"判定噪音"，
            //      不是"抽取"，产物要保持解析器抽到什么样就是什么样。改清洗阈值只需重分片，不必重解析；
            //   ② 在 **chunk 之前**：这是 O3 的硬窗 —— 向量一旦生成，事后清洗等于全库重建
            //      （T-508 属 S4-C3，紧随本批）。
            cleaned = documentCleaner.clean(parsed, cleaningOptions(row.kbId()));

            byte[] artifact = MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(parsed);
            parsePath = docStorage.storeArtifact(row.kbId(), docId, ARTIFACT_NAME, artifact);

            // T-503 / T-504 / T-505：解析产物 → 两级分片 → 块落 sys_chunk（含 chunk_count 回填）。
            // 放在 markParsed **之前**：分片失败时文档应当停在 FAILED 并给出分片侧的原因，
            // 而不是先标成「解析完成」再让用户发现一个块都没有。
            List<com.knowledge.ai.pipeline.Block> blocks =
                    chunkingService.chunk(cleaned.document(), chunkingOptions(row.kbId()));

            // T-507 索引增强：文档级 1 次调用 → SUMMARY 块（parentId = null）。
            // 放在分片**之后**：摘要要基于"实际进了索引的那些块"所覆盖的正文。
            // ⚠️ 增强失败**不牵连文档** —— 正文已经切好能检索了，因为摘要没生成就把整篇判失败，
            // 等于用一件增强功能的故障否掉一篇可用文档。失败原因照实记日志。
            IndexEnhancer.Result enhanced = indexEnhancer.enhance(cleaned.document(), blocks);
            logEnhancement(docId, enhanced);

            chunkCount = recordWriter.replaceChunks(docId, row.kbId(), enhanced.blocks());

            // `O9` 步 1/2：内容指纹 + 同库近重复提示。
            // ⚠️ 位置在**清洗之后、向量化之前**：指纹的输入必须是**规范化文本**，
            //    而清洗（`O3`）正是"去页眉页脚"的那一步 —— 在它之前取指纹，
            //    同一份内容换个页眉就变成两份（`O9` §4 步 1 点名要与 O3 合流）。
            //
            // 提示**不阻断**（O9 判据 1）：`duplicateHint` 只进返回值与日志，
            // 后面该向量化还是向量化。理由见 ContentDuplicateDetector 的类注释。
            duplicateHint = detectDuplicate(docId, row.kbId(), cleaned);

            // 质量四列里的三个：表格两列由解析侧给，图片列由 `O3` 步 4 销掉 S4-B §9 第 3 条
            // 的"采了不用"（imageCount 早已在采，只是没写）。image_parsed 仍等 T-512 的 VL。
            // `S4-E2` 又加两列：pages_total / pages_with_text（`优化/O2` §4 步 6）。
            // ⚠️ 页数取 `cleaned.document()` 而不是 `parsed`：清洗只剥离不增删页，
            //    两者的 pages 恒等 —— 用 cleaned 是为了与下面 `chars` 的取值同源，
            //    免得将来清洗真动了页结构时，两处各取各的。
            // 状态停在这里（INDEXING）—— 向量还没进 Milvus，见下。
            recordWriter.markParsed(docId, parsePath, parsed.tableTotal(), parsed.tableParsed(),
                    cleaned.document().imageCount(),
                    cleaned.document().pages(), cleaned.document().pagesWithText());

            // T-508 向量化：落库的块 → 嵌入 → Milvus。
            // ⚠️ 顺序是**硬约束**（本批 §3.4 假设 #1）：必须在 enhance **之后**，
            // 否则摘要块永远没有向量；而向量一旦生成，改回来就是全库重建。
            //
            // 输入从库里读（ChunkReader）而不是复用上面的 `enhanced.blocks()`：
            // 向量的主键必须是**已落库的 chunk.id**，而且要读回子块落库后被回填的 parent_id。
            // 这同时让 T-406 的重建索引将来能复用同一条路（那时手上只有 docId）。
            //
            // ⚠️ 与增强相反，向量化失败**必须牵连文档** —— 「块都在、向量一个没有」
            // 却标着可检索，是这个项目最不能接受的静默故障形态。所以这里不 catch。
            ChunkVectorizer.Result vectorized = chunkVectorizer.vectorize(
                    docId, row.kbId(), row.generation(), chunkReader.readByDocId(docId));
            if (vectorized.allowsReady()) {
                // `O9` 步 7：只有在**确实写了东西**之后才盖模型归因的章。
                // 开关关掉时一条向量都没写，这时盖章等于声称"这些是某模型产的"
                recordWriter.markVectorAttribution(docId,
                        vectorized.modelRef().model(), vectorized.modelRef().version());
                recordWriter.markReady(docId, row.generation());
            } else {
                // 开关关掉时停在中途态 INDEXING —— 不谎报可检索。日志已在 vectorize 里记过。
                log.warn("向量化未执行，文档停在中途态 INDEXING：docId={} 原因={}",
                        docId, vectorized.detail());
            }
        } catch (ParseException ex) {
            // **文档自身的问题**（无文本层 / 编码不对 / 已加密 / 文件损坏）→ **不可重试**。
            // 重试也还是这份文件；对用户撒谎说"重试中"比直接说"这文件不行"更糟（O1 §4 步 7）。
            recordWriter.markFailed(docId, ex.getMessage());
            // ⚠️ 走 **merge** 而不是 `parsed.issues()`：整篇扫描件的逐页问题现在由
            //    `TextLayerGuard#pageIssues` 产出（解析器那边已移走，理由见 `PdfDocumentParser`）。
            //    用 `parsed.issues()` 的话，这个分支会写出**零行** ——
            //    而"整篇失败"恰恰是最需要逐页定位的情形（`G4-11`）。
            recordWriter.replaceIssues(docId, row.kbId(),
                    parsed != null ? TextLayerGuard.merge(parsed, pageContentRouter) : java.util.List.of());
            log.warn("文档解析失败：docId={} type={} 原因={}", docId, row.type(), ex.getMessage());
            return Map.of("docId", docId, "status", "FAILED", "retryable", false, "reason", ex.getMessage());
        } catch (Exception ex) {
            String reason = "解析失败：" + readable(ex);
            recordWriter.markFailed(docId, reason);
            recordWriter.replaceIssues(docId, row.kbId(), java.util.List.of());
            log.error("文档解析异常：docId={} type={}", docId, row.type(), ex);
            // 未知异常标**不可重试**：它更可能是代码缺陷而不是临时故障，
            // 重试只会把同一个 bug 重放三遍并拖长用户等待，而栈已经在 error 日志里了。
            return Map.of("docId", docId, "status", "FAILED", "retryable", false, "reason", reason);
        }

        // 成功路径：解析器自己的 issue（表格类 / 清洗类）+ **页级新增的**（混合文档的扫描页）
        recordWriter.replaceIssues(docId, row.kbId(), TextLayerGuard.merge(parsed, pageContentRouter));
        log.info("文档解析完成：docId={} type={} pages={} chars={}(原始 {}) tables={}/{} images={} chunks={}",
                docId, row.type(), parsed.pages(), cleaned.document().chars(), parsed.chars(),
                parsed.tableParsed(), parsed.tableTotal(), parsed.imageCount(), chunkCount);
        log.info("文档页级文本层：docId={} pagesWithText={}/{}",
                docId, cleaned.document().pagesWithText(), cleaned.document().pages());
        Map<String, Object> result = new HashMap<>();
        result.put("docId", docId);
        // READY = 向量已入 Milvus、可被检索（`G4-3` 后半）。向量化关掉或没执行时，
        // 文档停在中途态 INDEXING —— 这里照实报状态，不让返回值比库里的更乐观
        result.put("status", recordWriter.statusOf(docId));
        result.put("pages", parsed.pages());
        // chars 报**清洗后**的值：前端"本页多少字"问的是"进了索引多少字"，
        // 而原始值仍留在 parse.json 里可查（两个数都打进了上面的日志）
        result.put("chars", cleaned.document().chars());
        result.put("tableTotal", parsed.tableTotal());
        result.put("tableParsed", parsed.tableParsed());
        result.put("chunkCount", chunkCount);
        result.put("parsePath", parsePath);
        // `O9` 步 1/2：近重复提示走**任务结果**下发（`T-500` 的异步任务结果页能看见），
        // 而不是让整篇文档失败 —— 提示型口径，见 ContentDuplicateDetector 的类注释
        result.put("duplicate", duplicateHint.duplicated());
        if (duplicateHint.duplicated()) {
            result.put("duplicateDocId", duplicateHint.docId());
            result.put("duplicateDocTitle", duplicateHint.docTitle());
            result.put("duplicateHint", duplicateHint.message());
        }
        return result;
    }

    /**
     * 算内容指纹、落库、并在同库找近重复（`O9` 步 1/2）。
     *
     * <p>三件事放一个方法里，是因为它们**必须用同一个指纹**：分开写就有机会
     * "落库用的是 A、比对用的是 B"，而那种不一致的表现是"去重时灵时不灵"。
     *
     * <p>任何一步失败都**不牵连解析** —— 去重是增强，不是解析的一部分。
     * 失败留 warn 并返回"不重复"（宁可漏报，不可误报：误报会让用户以为传错了）。
     */
    private ContentDuplicateDetector.Hint detectDuplicate(long docId, long kbId, CleanedDocument cleaned) {
        try {
            String fingerprint = ContentFingerprint.of(cleaned.document().markdown());
            recordWriter.markContentFingerprint(docId, fingerprint);
            ContentDuplicateDetector.Hint hint = contentDuplicateDetector.detect(kbId, docId, fingerprint);
            if (hint.duplicated()) {
                log.warn("发现同库近重复内容（不阻断）：docId={} kbId={} 已有的={} 《{}》",
                        docId, kbId, hint.docId(), hint.docTitle());
            }
            return hint;
        } catch (Exception ex) {
            log.warn("内容指纹计算或近重复检查失败，本次按「未发现重复」处理：docId={} err={}",
                    docId, ex.getMessage());
            return ContentDuplicateDetector.Hint.NONE;
        }
    }

    /**
     * 读该知识库的分片策略（{@code sys_kb.split_policy_json}）→ {@link ChunkingOptions}。
     *
     * <h3>为什么按库读，而不是用常量默认值</h3>
     * <p>分片策略是**库级配置**（前端「分片设置」Tab 可改，5 个预设的 chunkSize 各不相同：
     * GENERAL 512 / STRUCTURED 640 / CONTRACT 384 / QA_PAIR 320）。硬编码默认值会让
     * 「用户改了配置、系统照旧」—— 配置文件里看得见的值没生效，是最难排查的一类问题。
     *
     * <h3>为什么读失败不抛异常</h3>
     * <p>JSON 列读不出来是 **S3 配置写侧**该保证的不变量（`KbService` 只在几何校验通过后落库）。
     * 因为历史脏数据让整篇文档解析失败，代价远大于按默认参数分片 —— 而且那是**另一个模块**的问题，
     * 不该由解析任务来报。所以降级 + {@code WARN} 日志，日志里有 kbId 与原文，可追。
     *
     * <p>默认值取自 {@link KbConfigDefaults}（与 `KbService#defaultSplitPolicy` 同源），
     * 不在这里另抄一份字面量。
     */
    private ChunkingOptions chunkingOptions(long kbId) {
        List<String> rows = entityManager.createNativeQuery(
                        "select split_policy_json from sys_kb where id = :kbId")
                .setParameter("kbId", kbId)
                .getResultList();
        if (rows.isEmpty() || rows.getFirst() == null || rows.getFirst().isBlank()) {
            return ChunkingOptions.defaults();
        }
        String json = rows.getFirst();
        try {
            var node = MAPPER.readTree(json);
            return new ChunkingOptions(
                    node.path("chunkSize").asInt(KbConfigDefaults.CHUNK_SIZE),
                    node.path("overlap").asInt(KbConfigDefaults.OVERLAP),
                    node.path("semanticSplit").asBoolean(KbConfigDefaults.SEMANTIC_SPLIT));
        } catch (Exception ex) {
            log.warn("知识库分片策略 JSON 解析失败，本次按默认参数分片：kbId={} json={} err={}",
                    kbId, json, ex.getMessage());
            return ChunkingOptions.defaults();
        }
    }

    /**
     * 把增强结果落到日志。
     *
     * <h3>为什么"没失败"也要记</h3>
     * <p>增强有四种非 OK 的结果（关掉 / 空正文 / 模型炸 / 输出不成 JSON），它们的后果**一样**：
     * 这篇文档没有摘要块。如果只在异常时记日志，那"关掉了"与"模型一直炸"在日志里长得一样
     * —— 而这两种情况的处置完全不同（前者正常，后者要查密钥/网络）。
     *
     * <p>分档：{@code DISABLED} 与 {@code EMPTY_INPUT} 是**预期内**的（info），
     * 另两种是**真出了问题**（warn）。`G4-3` 的口径是失败态要给出具体原因，
     * 所以 detail 里带的是可读原因而不是枚举名。
     *
     * <p>⚠️ 目前这些只进日志，**用户看不到** —— 已登记为遗留（计划 §9）。
     */
    private static void logEnhancement(long docId, IndexEnhancer.Result enhanced) {
        switch (enhanced.status()) {
            case OK -> log.info("文档级增强完成：docId={} {}", docId, enhanced.detail());
            case DISABLED, EMPTY_INPUT ->
                    log.info("文档级增强未产出摘要：docId={} 原因={}", docId, enhanced.detail());
            case MODEL_FAILED, BAD_OUTPUT ->
                    log.warn("文档级增强未产出摘要（文档已正常入库，不影响检索）：docId={} 原因={}",
                            docId, enhanced.detail());
        }
    }

    /**
     * 读该知识库的清洗参数（`优化/O3`：阈值必须是配置，不是常量）。
     *
     * <h3>为什么复用 {@code split_policy_json} 而不是新开一列</h3>
     * <p>本批不加迁移（计划 §4）。清洗阈值与分片参数同属"这个库怎么处理文档"，
     * 塞进同一份 JSON 不必改表结构；将来 `T-406` 的"重建索引"若要让用户单独调清洗，
     * 再拆成独立字段也不迟 —— 那时是一次真正的表结构变更，值得单独一个迁移。
     *
     * <p>读不到 / 解析不了 / 键缺失 → 一律回落到 {@link CleaningOptions#defaults()}，
     * 并**留一条 warn**（与 {@link #chunkingOptions(long)} 同一条纪律：配置坏了不能静默变成
     * "什么都没清洗"，也不能因此让整篇文档解析失败）。
     */
    private CleaningOptions cleaningOptions(long kbId) {
        List<String> rows = entityManager.createNativeQuery(
                        "select split_policy_json from sys_kb where id = :kbId")
                .setParameter("kbId", kbId)
                .getResultList();
        if (rows.isEmpty() || rows.getFirst() == null || rows.getFirst().isBlank()) {
            return CleaningOptions.defaults();
        }
        String json = rows.getFirst();
        try {
            var node = MAPPER.readTree(json);
            CleaningOptions defaults = CleaningOptions.defaults();
            return new CleaningOptions(
                    node.path("cleanRatio").asDouble(defaults.ratio()),
                    node.path("cleanEdgeLines").asInt(defaults.edgeLines()),
                    node.path("cleanMinPages").asInt(defaults.minPages()));
        } catch (Exception ex) {
            log.warn("知识库清洗参数 JSON 解析失败，本次按默认参数清洗：kbId={} json={} err={}",
                    kbId, json, ex.getMessage());
            return CleaningOptions.defaults();
        }
    }

    /**
     * 按文档类型分派。
     *
     * <p><b>PDF 走 opendataloader、Office 走 Tika、纯文本不走解析库</b> ——
     * 分派依据是 {@code sys_document.type}（上传时按扩展名登记的域内真值），
     * 不重新嗅探：类型错了在校验层就已经拦了，这里再嗅探一次只会让"登记值"与"实际值"分叉。
     */
    /**
     * 按 {@code sys_document.type} 分派到具体的解析器。
     *
     * <p>⚠️ 可见性从 {@code private} 放宽到**包内可见的 static**（`S5-M07-B` 的 `T-O05`）：
     * 解析基线（{@code ParseBaselineTest}）要用**同一条分派**去跑样本 ——
     * 它在测试里抄一份「PDF 走 opendataloader、MD/TXT 走 PlainTextParser、其余走 Tika」
     * 的话，就有两处会在"新增支持一种格式"时各自演化，
     * 而基线的价值恰恰是"改了解析链路能被发现"：分派抄错一条，基线就永远看不见那条路径的退化。
     */
    static ParsedDocument dispatch(String type, byte[] source, String fileName) {
        if (PLAIN_TEXT.contains(type)) {
            return PlainTextParser.parse(source, fileName);
        }
        if ("PDF".equals(type)) {
            return parsePdf(source);
        }
        // 其余（DOC/DOCX/PPT/PPTX/XLS/XLSX）走 Tika —— 按文件头自动识别，
        // 扩展名只当提示（浏览器对 .docx 常标 application/octet-stream）
        TikaDocumentParser parser = new TikaDocumentParser();
        return parser.parse(new java.io.ByteArrayInputStream(source), fileName);
    }

    /** PDF 必须落临时文件（opendataloader 只吃路径），用完即删。 */
    static ParsedDocument parsePdf(byte[] source) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("kb-parse-", ".pdf");
            Files.write(tmp, source);
            return PdfDocumentParser.parse(tmp);
        } catch (IOException ex) {
            throw new ParseException("文档暂存失败，无法开始解析，请重试", ex);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    log.warn("PDF 临时文件删除失败：{}", tmp);
                }
            }
        }
    }

    /**
     * 把异常压成**给人看的一句话**（`S4-E1` 新增）。
     *
     * <p>为什么不用 {@code ex.getClass().getSimpleName()}：那会把
     * {@code S3Exception} / {@code ConnectException} 这类东西原样甩给用户，
     * 而用户需要的是"发生了什么 + 我该怎么办"。类名进日志（`log.error` 已经带了栈），
     * 给人看的这句话只说人能处理的部分。
     */
    private static String readable(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        // 只取第一行：SDK 的异常消息常常是多行 XML，全塞进 error_msg 会把列撑爆
        int nl = msg.indexOf('\n');
        String oneLine = nl > 0 ? msg.substring(0, nl) : msg;
        return oneLine.length() > 160 ? oneLine.substring(0, 160) : oneLine;
    }

    /** 文档行必须存在，否则解析无从谈起（正常流程不会走到，防御并发清理）。 */
    private Row requireRow(long docId) {
        @SuppressWarnings("unchecked")
        java.util.List<Object[]> rows = entityManager.createNativeQuery("""
                        select d.kb_id, d.file_name, d.type, d.storage_path, k.index_generation
                          from sys_document d
                          join sys_kb k on k.id = d.kb_id
                         where d.id = :id
                        """)
                .setParameter("id", docId)
                .getResultList();
        if (rows.isEmpty() || rows.getFirst()[3] == null) {
            throw new BizException(ErrorCode.DOC_NOT_FOUND, "文档不存在或尚未转存完成，无法解析");
        }
        Object[] r = rows.getFirst();
        return new Row(((Number) r[0]).longValue(), (String) r[1], (String) r[2], (String) r[3],
                ((Number) r[4]).intValue());
    }

    /**
     * 解析所需的文档行快照（读一次，全程用快照 —— 解析期间行可能被改名，不应半路换文件）。
     *
     * @param generation 该库**当前**的代（{@code sys_kb.index_generation}）。日常解析写进这一代；
     *                   重建索引（{@code T-406}）走另一条路，传"新代"。取快照而不是每次现查，
     *                   是为了让"这一篇文档全程只认一个代" —— 否则解析中途该库被切了代，
     *                   会写出一个前半在新代、后半在旧代的文档。
     */
    private record Row(long kbId, String fileName, String type, String storagePath, int generation) {
    }
}
