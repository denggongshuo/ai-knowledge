package com.ai.knowledge.utils.pdf;

import org.springframework.ai.vectorstore.VectorStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * 面向 RAG 的 PDF 处理流水线，支持含图片和表格的 PDF。
 *
 * <pre>
 * PDF 文件
 *   │  1. 读取文档内容 {@link PdfContentReader}
 *   │     逐页文本提取 + 表格块识别 + 内嵌图片抽取
 *   ▼  2. 数据清洗 {@link PdfTextCleaner}
 *   │     控制字符去除 / 连字符断词修复 / 中文折行合并 / 空行压缩
 *   ▼  3. 去除噪音 {@link PdfNoiseFilter}
 *   │     页码行 / 重复页眉页脚 / 空块
 *   ▼  4. 内容归档 {@link PdfContentArchiver}
 *   │     markdown + 图片 + meta.json 落盘，可选分块写入向量库
 *   ▼
 * {@link PdfPipelineResult}
 * </pre>
 *
 * 使用示例：
 * <pre>{@code
 * PdfPipeline pipeline = new PdfPipeline(Path.of("./archive"), vectorStore); // vectorStore 可为 null
 * PdfPipelineResult result = pipeline.process(Path.of("manual.pdf"));
 * }</pre>
 */
public class PdfPipeline {

    private final PdfContentReader reader;
    private final PdfTextCleaner cleaner;
    private final PdfNoiseFilter noiseFilter;
    private final PdfContentArchiver archiver;

    /** 仅归档到磁盘，不写入向量库 */
    public PdfPipeline(Path archiveRoot) {
        this(archiveRoot, null);
    }

    /** 归档磁盘 + 分块写入向量库供 RAG 检索，vectorStore 可为 null */
    public PdfPipeline(Path archiveRoot, VectorStore vectorStore) {
        this(new PdfContentReader(), new PdfTextCleaner(), new PdfNoiseFilter(),
                new PdfContentArchiver(archiveRoot, vectorStore));
    }

    /** 完全自定义各步骤实现 */
    public PdfPipeline(PdfContentReader reader, PdfTextCleaner cleaner,
                       PdfNoiseFilter noiseFilter, PdfContentArchiver archiver) {
        this.reader = reader;
        this.cleaner = cleaner;
        this.noiseFilter = noiseFilter;
        this.archiver = archiver;
    }

    /** 执行完整流水线 */
    public PdfPipelineResult process(Path pdfFile) throws IOException {
        PdfParseResult parsed = reader.read(pdfFile);
        return doProcess(parsed, pdfFile.getFileName().toString());
    }

    /** 从输入流执行完整流水线（如接收上传文件场景） */
    public PdfPipelineResult process(InputStream input, String sourceName) throws IOException {
        PdfParseResult parsed = reader.read(input);
        return doProcess(parsed, sourceName);
    }

    private PdfPipelineResult doProcess(PdfParseResult parsed, String sourceName) throws IOException {
        // 1. 读取文档内容（reader.read 已完成）
        // 2. 数据清洗
        List<PdfContentBlock> cleaned = cleaner.clean(parsed.blocks());
        // 3. 去除噪音
        List<PdfContentBlock> filtered = noiseFilter.filter(cleaned);
        // 4. 内容归档
        return archiver.archive(sourceName, filtered, parsed.images(), parsed.totalPages());
    }
}
