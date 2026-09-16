package com.knowledge.ai.service;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.storage.DocStorage;
import com.knowledge.ai.pipeline.ParseException;
import com.knowledge.ai.pipeline.ParsedDocument;
import com.knowledge.ai.pipeline.PdfDocumentParser;
import com.knowledge.ai.pipeline.PlainTextParser;
import com.knowledge.ai.pipeline.TikaDocumentParser;
import com.knowledge.ai.pipeline.TextLayerGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
        String parsePath = null;
        try {
            byte[] source = docStorage.open(row.storagePath());
            parsed = dispatch(row.type(), source, row.fileName());
            TextLayerGuard.check(parsed);

            byte[] artifact = MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(parsed);
            parsePath = docStorage.storeArtifact(row.kbId(), docId, ARTIFACT_NAME, artifact);

            recordWriter.markParsed(docId, parsePath, parsed.tableTotal(), parsed.tableParsed());
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
        log.info("文档解析完成：docId={} type={} pages={} chars={} tables={}/{}",
                docId, row.type(), parsed.pages(), parsed.chars(),
                parsed.tableParsed(), parsed.tableTotal());
        Map<String, Object> result = new HashMap<>();
        result.put("docId", docId);
        result.put("status", "INDEXING");
        result.put("pages", parsed.pages());
        result.put("chars", parsed.chars());
        result.put("tableTotal", parsed.tableTotal());
        result.put("tableParsed", parsed.tableParsed());
        result.put("parsePath", parsePath);
        return result;
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
