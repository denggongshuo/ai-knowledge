package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.KbConfigDefaults;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.storage.DocStorage;
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
        try {
            byte[] source = docStorage.open(row.storagePath());
            parsed = dispatch(row.type(), source, row.fileName());
            // ⚠️ 体检在清洗**之前**：TextLayerGuard 的判据吃 chars，而清洗只减不增。
            // 反过来排的话，一篇正常文档会因为"剥掉了页眉"而掉到扫描件的阈值以下 —— 误报。
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
            chunkCount = recordWriter.replaceChunks(docId, row.kbId(),
                    chunkingService.chunk(cleaned.document(), chunkingOptions(row.kbId())));

            // 质量四列里的三个：表格两列由解析侧给，图片列由 `O3` 步 4 销掉 S4-B §9 第 3 条
            // 的"采了不用"（imageCount 早已在采，只是没写）。image_parsed 仍等 T-512 的 VL。
            recordWriter.markParsed(docId, parsePath, parsed.tableTotal(), parsed.tableParsed(),
                    cleaned.document().imageCount());
        } catch (ParseException ex) {
            recordWriter.markFailed(docId, ex.getMessage());
            recordWriter.replaceIssues(docId, row.kbId(), parsed != null ? parsed.issues() : java.util.List.of());
            log.warn("文档解析失败：docId={} type={} 原因={}", docId, row.type(), ex.getMessage());
            return Map.of("docId", docId, "status", "FAILED", "reason", ex.getMessage());
        } catch (Exception ex) {
            String reason = "解析失败：" + (ex.getMessage() == null || ex.getMessage().isBlank()
                    ? ex.getClass().getSimpleName() : ex.getMessage());
            recordWriter.markFailed(docId, reason);
            recordWriter.replaceIssues(docId, row.kbId(), java.util.List.of());
            log.error("文档解析异常：docId={} type={}", docId, row.type(), ex);
            return Map.of("docId", docId, "status", "FAILED", "reason", reason);
        }

        recordWriter.replaceIssues(docId, row.kbId(), parsed.issues());
        log.info("文档解析完成：docId={} type={} pages={} chars={}(原始 {}) tables={}/{} images={} chunks={}",
                docId, row.type(), parsed.pages(), cleaned.document().chars(), parsed.chars(),
                parsed.tableParsed(), parsed.tableTotal(), parsed.imageCount(), chunkCount);
        Map<String, Object> result = new HashMap<>();
        result.put("docId", docId);
        result.put("status", "INDEXING");
        result.put("pages", parsed.pages());
        // chars 报**清洗后**的值：前端"本页多少字"问的是"进了索引多少字"，
        // 而原始值仍留在 parse.json 里可查（两个数都打进了上面的日志）
        result.put("chars", cleaned.document().chars());
        result.put("tableTotal", parsed.tableTotal());
        result.put("tableParsed", parsed.tableParsed());
        result.put("chunkCount", chunkCount);
        result.put("parsePath", parsePath);
        return result;
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
    private ParsedDocument dispatch(String type, byte[] source, String fileName) {
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
    private ParsedDocument parsePdf(byte[] source) {
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

    /** 文档行必须存在，否则解析无从谈起（正常流程不会走到，防御并发清理）。 */
    private Row requireRow(long docId) {
        @SuppressWarnings("unchecked")
        java.util.List<Object[]> rows = entityManager.createNativeQuery("""
                        select kb_id, file_name, type, storage_path from sys_document where id = :id
                        """)
                .setParameter("id", docId)
                .getResultList();
        if (rows.isEmpty() || rows.getFirst()[3] == null) {
            throw new BizException(ErrorCode.DOC_NOT_FOUND, "文档不存在或尚未转存完成，无法解析");
        }
        Object[] r = rows.getFirst();
        return new Row(((Number) r[0]).longValue(), (String) r[1], (String) r[2], (String) r[3]);
    }

    /** 解析所需的文档行快照（读一次，全程用快照 —— 解析期间行可能被改名，不应半路换文件）。 */
    private record Row(long kbId, String fileName, String type, String storagePath) {
    }
}
