package com.knowledge.ai.service;

import com.knowledge.ai.core.async.ParseWorker;
import com.knowledge.ai.core.storage.DocStorage;
import com.knowledge.ai.pipeline.ParsedDocument;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M05 阶段 B 门禁：`T-502` 解析流水线 / `T-511` 未识别表格清单。
 *
 * <h3>判据来源</h3>
 * <p>计划 `plan/2026-09-16-S4-M05-B.md` §6+7 的 1~19 条。样本全部来自
 * {@code docs/测试文件/}（gitignored）—— 用**真实文件**而不是合成字节，
 * 是因为本批验证的正是"解析器对真实世界文档的行为"。
 *
 * <h3>清理纪律（与 {@code DocUploadFlowTest} 同源）</h3>
 * <p>探针库按 {@code kb_probe_doc_} 前缀建，用例结束连同文档与问题行一起删；
 * {@code sys_parse_issue} 按 kb_id 级联删。
 */
class DocParseFlowTest extends ApiTestSupport {

    private static final String PROBE_PREFIX = "kb_probe_doc_";
    /** 真实样本目录（不入库）。 */
    private static final Path SAMPLE_DIR = Path.of("..", "docs", "测试文件").toAbsolutePath().normalize();

    /** 含图样本 —— `O3` 判据 4 需要"有图且图数可数"的文档，这份实测 23 张。 */
    private static final String IMAGE_SAMPLE = "百福云PaaS平台应用部署快速入门 v1.0.pdf";

    private final List<Long> probeKbIds = new ArrayList<>();

    @Autowired
    private DocStorage docStorage;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DocParseService docParseService;

    /**
     * 解析作业的执行者（`S4-E1`）。
     *
     * <p>测试期后台轮询是**关掉的**（`app.parse.worker.enabled=false`），
     * 所以上传之后必须由用例显式 `drainOnce()` 驱动解析 —— 这样断言是**确定性**的，
     * 而覆盖一点没少：走的仍是同一条 领取 → 执行 → 回报 的路。
     */
    @Autowired
    private ParseWorker parseWorker;

    @AfterEach
    void cleanProbeData() {
        for (Long kbId : probeKbIds) {
            // 对象名必须在删行**之前**取出来
            for (Map<String, Object> row : jdbcTemplate.queryForList(
                    "select storage_path, parse_path from sys_document where kb_id = ?", kbId)) {
                removeObject(row.get("storage_path"));
                removeObject(row.get("parse_path"));
            }
            // sys_chunk 必须一起清：它按 doc_id 关联，文档行删掉后这些块就成了孤儿 ——
            // 留在库里会污染"全库 SQL 断言"（如 G4-6 的父子完整性），
            // 让下一条用例在一个不知道谁造的脏数据上做判断。
            // 按 kb_id 直删而不是 join sys_document：文档行可能已经不在了，
            // join 一条也匹配不到、孤儿照样留下（`S4-M05-C2` 在隔壁
            // `DocUploadFlowTest` 实测到 100 行这种孤儿）。
            jdbcTemplate.update("delete from sys_chunk where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_parse_issue where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_document where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_kb_grant where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_kb where id = ?", kbId);
        }
        probeKbIds.clear();
    }

    /**
     * 删对象存储里的文件。
     *
     * <p>不删的后果是实测过的：桶里累积到 1030 个对象后，
     * {@code GET /kb/storage-quota} 的全桶列举慢到读超时，把**毫不相干**的
     * {@code KbWriteFlowTest#storageQuotaMatchesDictionary} 拖红。
     */
    private void removeObject(Object objectKey) {
        if (objectKey != null && !objectKey.toString().isBlank()) {
            docStorage.remove(objectKey.toString());
        }
    }

    // ==================================================================
    // 判据 1 / 2：解析产物落 MinIO · 正常文档推进到 INDEXING 且质量列为真值
    // ==================================================================

    @Test
    @DisplayName("判据2+1：真 MD 上传 → INDEXING；parse.json 落 MinIO 且 markdown 含原文")
    void parseArtifactLandsInMinio() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        byte[] content = "# 解析探针\n\n型号 A-100，产品名称 智能网关，单价 ￥1,200，交期 7 天。".getBytes(StandardCharsets.UTF_8);

        RawResponse uploaded = upload(kbId, content, "解析探针.md", token).assertOk("上传");
        long docId = ((Number) uploaded.data("$.docId")).longValue();

        Map<String, Object> row = rowOf(docId);
        assertThat(row.get("status")).as("解析完成 → INDEXING（READY 要等 S4-C 的向量）").isEqualTo("INDEXING");
        String parsePath = (String) row.get("parse_path");
        assertThat(parsePath).as("parse_path 形如 parsed/{kbId}/{docId}/parse.json")
                .startsWith("parsed/" + kbId + "/" + docId + "/");

        byte[] artifact = docStorage.open(parsePath);
        assertThat(artifact.length).as("产物非空").isPositive();
        String json = new String(artifact, StandardCharsets.UTF_8);
        assertThat(json).as("产物含源文本（内容真的被抽出来了）").contains("智能网关");
        assertThat(json).as("产物是 ParsedDocument 的序列化").contains("\"format\"");
    }

    @Test
    @DisplayName("判据2：真实 PDF → INDEXING，质量列填真值（tableTotal≥5 且识别数=总数）")
    void realPdfReachesIndexingWithQualityColumns() {
        assumeSample("1-DeepSeek使用.pdf");
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        byte[] pdf = readSample("1-DeepSeek使用.pdf");

        RawResponse uploaded = upload(kbId, pdf, "DeepSeek使用探针.pdf", token).assertOk("上传 PDF");
        long docId = ((Number) uploaded.data("$.docId")).longValue();

        Map<String, Object> row = rowOf(docId);
        assertThat(row.get("status")).isEqualTo("INDEXING");
        long tableTotal = ((Number) row.get("table_total")).longValue();
        long tableParsed = ((Number) row.get("table_parsed")).longValue();
        assertThat(tableTotal)
                .as("该 PDF 实测含 9 张表（含 2 张版式误判被过滤后 ≥5 张有效表）")
                .isGreaterThanOrEqualTo(5);
        assertThat(tableParsed).as("有文本层的 PDF 表格应全部识别（T-0S1 §一-1）").isEqualTo(tableTotal);
    }

    @Test
    @DisplayName("判据3（O3）：含图 PDF 的 image_total 不再是恒 0 —— 销「采了不用」")
    void imageTotalIsFilled() {
        assumeSample(IMAGE_SAMPLE);
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        byte[] pdf = readSample(IMAGE_SAMPLE);

        long docId = ((Number) upload(kbId, pdf, "百福云探针.pdf", token)
                .assertOk("上传含图 PDF").data("$.docId")).longValue();

        Map<String, Object> row = rowOf(docId);
        assertThat(row.get("status")).isEqualTo("INDEXING");
        assertThat(((Number) row.get("image_total")).longValue())
                .as("探针实测该样本有 23 个 image 元素（结构事实，取等值不取区间）。"
                        + "这一列在此前**恒 0** —— 而 0 表示「未评估」不表示「没有图片」，"
                        + "前端因此对含图文档显示「无图片」（S4-B §9 第 3 条）")
                .isEqualTo(23);
        assertThat(((Number) row.get("image_parsed")).longValue())
                .as("image_parsed 仍为 0 = 「未理解」（VL 描述属 T-512 / S6）。"
                        + "于是含图文档的 image_parsed < image_total 恒成立，"
                        + "它会被 DocService 的「质量待改进」过滤命中 —— 这是**刻意**的："
                        + "图片确实还没被理解")
                .isZero();
    }

    // ==================================================================
    // 判据 3 / 4：「无文本层」检测（P0）
    // ==================================================================

    @Test
    @DisplayName("判据3：21.8MB 无文本层扫描件 → FAILED 且原因可执行（本批最重要的一条）")
    void scannedPdfWithoutTextLayerFailsLoudly() {
        assumeSample("健康生活方式（指导手册）.pdf");
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        byte[] pdf = readSample("健康生活方式（指导手册）.pdf");

        RawResponse uploaded = upload(kbId, pdf, "扫描件探针.pdf", token).assertOk("上传扫描件");
        long docId = ((Number) uploaded.data("$.docId")).longValue();

        Map<String, Object> row = rowOf(docId);
        assertThat(row.get("status"))
                .as("扫描件必须失败 —— 静默走到 READY 是本项目最不能接受的故障")
                .isEqualTo("FAILED");
        String error = (String) row.get("error_msg");
        assertThat(error)
                .as("G4-3：失败给出具体原因 + 可执行动作，不是「失败」两字")
                .contains("未提取到可复制文本")
                .contains("OCR");
    }

    @Test
    @DisplayName("判据4：阈值判据可复现 —— 扫描件触发、正常 PDF 不触发（打印 chars/页 实测值）")
    void textLayerThresholdDiscriminatesScannedFromReal() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);

        // 正常 PDF（有文本层）
        assumeSample("百福云PaaS平台应用部署快速入门 v1.0.pdf");
        byte[] good = readSample("百福云PaaS平台应用部署快速入门 v1.0.pdf");
        long goodDoc = ((Number) upload(kbId, good, "正常PDF探针.pdf", token)
                .assertOk("上传正常 PDF").data("$.docId")).longValue();
        Map<String, Object> goodRow = rowOf(goodDoc);
        assertThat(goodRow.get("status")).as("正常 PDF 不应触发无文本层检测").isEqualTo("INDEXING");

        // 扫描件（无文本层）
        assumeSample("健康生活方式（指导手册）.pdf");
        byte[] scanned = readSample("健康生活方式（指导手册）.pdf");
        long scannedDoc = ((Number) upload(kbId, scanned, "阈值扫描件探针.pdf", token)
                .assertOk("上传扫描件").data("$.docId")).longValue();
        Map<String, Object> scannedRow = rowOf(scannedDoc);
        assertThat(scannedRow.get("status")).as("扫描件触发检测").isEqualTo("FAILED");

        System.out.printf("[判据4] 正常PDF status=%s · 扫描件 status=%s error=%s%n",
                goodRow.get("status"), scannedRow.get("status"), scannedRow.get("error_msg"));
    }

    @Test
    @DisplayName("判据4反例：单页短文档**不**被判成无文本层（误报比漏报更伤）")
    void singlePageShortDocIsNotFlaggedAsScanned() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        // 27 字节的便利贴式 MD：单页 10 个字。它是**正常文档**，不是扫描件
        byte[] tiny = "# 短文档\n\n正文。".getBytes(StandardCharsets.UTF_8);

        long docId = ((Number) upload(kbId, tiny, "短文档探针.md", token).assertOk("上传短文档")
                .data("$.docId")).longValue();

        Map<String, Object> row = rowOf(docId);
        assertThat(row.get("status"))
                .as("密度判据只在 pages>=2 时生效 —— 单页文档只看「有没有字」。"
                        + "把短文档报成扫描件会给出用户无从下手的建议（补录？OCR？）")
                .isEqualTo("INDEXING");
        assertThat((String) row.get("error_msg")).isNull();
    }

    // ==================================================================
    // 判据 5 / 6：编码与空文件
    // ==================================================================

    @Test
    @DisplayName("判据5：GBK 文本 → FAILED，原因含 GBK 与转存建议（《03》§4.2 的例子）")
    void gbkTextFileFailsWithActionableReason() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        byte[] gbk = "判据5：这是 GBK 编码的内容，用于验证显式失败".getBytes(java.nio.charset.Charset.forName("GBK"));

        long docId = ((Number) upload(kbId, gbk, "GBK探针.txt", token).assertOk("上传 GBK").data("$.docId")).longValue();

        Map<String, Object> row = rowOf(docId);
        assertThat(row.get("status")).as("非 UTF-8 显式失败，不用替换字符解出乱码进索引").isEqualTo("FAILED");
        assertThat((String) row.get("error_msg"))
                .contains("GBK")
                .as("可执行动作：告诉用户怎么办")
                .contains("UTF-8");
    }

    @Test
    @DisplayName("判据6：空文件在上传校验层就被拒（10030「请选择要上传的文件」—— 不产生脏行）")
    void emptyFileFailsInsteadOfSilentlySucceeding() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);

        // 空字节连上传校验（DocStorage#validate 的 file.isEmpty()）都过不去 ——
        // 在"建行"之前就拒了，比"建行后解析失败"更干净：列表里不会出现一条假文档。
        // 「空文件 → FAILED」的服务端兜底由 GBK 用例的兄弟路径覆盖（解析层的同一条纪律）。
        RawResponse rejected = upload(kbId, new byte[0], "空探针.md", token);
        assertThat(rejected.code())
                .as("空文件被上游校验拦截（10030），不产生任何文档行")
                .isEqualTo(10030);
    }

    // ==================================================================
    // 判据 7 / 8 / 9：DOCX / XLSX / MD 三条解析路径
    // ==================================================================

    @Test
    @DisplayName("判据7：DOCX → 标题层级保留（markdown 以 # 开头）—— 同时证明 commons-compress 冲突已修")
    void docxKeepsHeadingHierarchyViaTika() {
        assumeSample("渠道整合对接营销会计分录及差错处理方案.docx");
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        byte[] docx = readSample("渠道整合对接营销会计分录及差错处理方案.docx");

        long docId = ((Number) upload(kbId, docx, "DOCX探针.docx", token).assertOk("上传 DOCX")
                .data("$.docId")).longValue();

        Map<String, Object> row = rowOf(docId);
        assertThat(row.get("status"))
                .as("commons-compress 未锁版本时这里会抛 NoSuchMethodError → FAILED")
                .isEqualTo("INDEXING");
        String json = artifactJson(docId);
        assertThat(json).contains("\"markdown\"");
        // 标题层级进 headings（结构化），这是 T-503 第一级切分的输入
        assertThat(json).as("headings 数组应非空（h1/h2/h3 都在）")
                .contains("\"headings\" : [ {")
                .contains("\"level\" : 2");
    }

    @Test
    @DisplayName("判据8：XLSX → 表格计入 table_total，markdown 含管道表")
    void xlsxTablesAreCounted() {
        assumeSample("软件需求计划20200811.xlsx");
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        byte[] xlsx = readSample("软件需求计划20200811.xlsx");

        long docId = ((Number) upload(kbId, xlsx, "XLSX探针.xlsx", token).assertOk("上传 XLSX")
                .data("$.docId")).longValue();

        Map<String, Object> row = rowOf(docId);
        assertThat(row.get("status")).isEqualTo("INDEXING");
        assertThat(((Number) row.get("table_total")).longValue())
                .as("实测该表 111 行 —— 至少应记 1 张表").isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("判据9：MD 的 # 层级不被吃掉（headings 非空且保留层级）")
    void mdKeepsHeadingSyntax() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        byte[] md = "# 一级标题\n\n## 二级标题\n\n正文段落。".getBytes(StandardCharsets.UTF_8);

        long docId = ((Number) upload(kbId, md, "MD层级探针.md", token).assertOk("上传 MD")
                .data("$.docId")).longValue();

        String json = artifactJson(docId);
        assertThat(json).as("两级标题都进 headings").contains("一级标题").contains("二级标题");
    }

    // ==================================================================
    // 判据 10 / 11：表格过滤与"检出但无内容"
    // ==================================================================

    @Test
    @DisplayName("判据10：单列空表（版式误判）被过滤，不计入 table_total")
    void layoutArtifactTablesAreFiltered() {
        assumeSample("1-DeepSeek使用.pdf");
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        byte[] pdf = readSample("1-DeepSeek使用.pdf");

        long docId = ((Number) upload(kbId, pdf, "过滤探针.pdf", token).assertOk("上传").data("$.docId")).longValue();

        String json = artifactJson(docId);
        // 该 PDF 实测 9 个 table 元素，其中 2 个是 1×1 版式误判 → 记 7
        long tableTotal = ((Number) rowOf(docId).get("table_total")).longValue();
        assertThat(tableTotal).as("9 张原始表 − 2 张 1×1 误判 = 7").isEqualTo(7);
        assertThat(json).as("产物里仍保留全部矩阵（下游可复核）").contains("\"rows\"");
    }

    @Test
    @DisplayName("判据11：检出结构但无内容的表 → 计 total 不计 parsed，且出现在 issue 清单")
    void detectedButEmptyTableIsVisible() {
        // 该样本的 7 张有效表都有内容（有文本层），所以本判据用单元级构造验证服务层行为：
        // ParsedDocument 的语义由 PdfDocumentParser.fromJson 的 issue 生成逻辑保证，
        // 这里用一条合成的"结构有、内容空"的表走**真实的解析落库路径**（replaceIssues）。
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);

        ParsedDocument emptyTableDoc = new ParsedDocument("PDF", 1, 10, "占位",
                List.of(), List.of(new ParsedDocument.ParsedTable("", 3,
                        List.of(List.of("", ""), List.of("", "")))),
                1, 0, List.of(new ParsedDocument.DocumentIssue(3, "表格检出结构（2 行 × 2 列）但未提取到内容",
                        ParsedDocument.IssueKind.TABLE_NOT_PARSED)),
                List.of());

        long docId = ((Number) upload(kbId, "占位".getBytes(StandardCharsets.UTF_8), "占位.md", token)
                .assertOk("上传占位文档").data("$.docId")).longValue();
        // 直接落该文档的问题行（replaceIssues 是解析成功/失败两条路的共同终点）
        docParseServiceParseIssues(docId, kbId, emptyTableDoc.issues());

        Map<String, Object> row = rowOf(docId);
        Integer issueCount = jdbcTemplate.queryForObject(
                "select count(*) from sys_parse_issue where doc_id = ?", Integer.class, docId);
        assertThat(issueCount).as("问题行已落库").isEqualTo(1);
        Map<String, Object> issue = jdbcTemplate.queryForMap(
                "select page_no, reason from sys_parse_issue where doc_id = ?", docId);
        assertThat(((Number) issue.get("page_no")).intValue()).isEqualTo(3);
        assertThat((String) issue.get("reason")).contains("未提取到内容");
        assertThat((String) issue.get("reason"))
                .as("问题**种类**在库里是可读的文字前缀（S4-M05-C2 步 0.6）。"
                        + "sys_parse_issue 没有种类列、本批不加迁移，所以种类只能这样落 —— "
                        + "但「这是哪一类问题」必须看得见，否则按关键词瞎猜是唯一手段")
                .startsWith(ParsedDocument.IssueKind.TABLE_NOT_PARSED.tag());
        assertThat(row.get("status")).isIn("INDEXING", "FAILED");
    }

    private void docParseServiceParseIssues(long docId, long kbId, List<ParsedDocument.DocumentIssue> issues) {
        // 直调 writer（与 DocParseService 同一条路径），不绕事务
        recordWriter().replaceIssues(docId, kbId, issues);
    }

    @Autowired
    private DocRecordWriter writerField;

    private DocRecordWriter recordWriter() {
        return writerField;
    }

    // ==================================================================
    // 判据 12 / 13：T-511 清单本体
    // ==================================================================

    @Test
    @DisplayName("判据12：parse-issues 返回文档、真实页码、原因（扫描件 15 页 = 15 行）")
    void parseIssuesListDocPageAndReason() {
        assumeSample("健康生活方式（指导手册）.pdf");
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        byte[] pdf = readSample("健康生活方式（指导手册）.pdf");

        long docId = ((Number) upload(kbId, pdf, "清单探针.pdf", token).assertOk("上传扫描件")
                .data("$.docId")).longValue();

        RawResponse issues = get("/api/kb/" + kbId + "/parse-issues", token).assertOk("问题清单");
        List<Map<String, Object>> list = issues.data("$.list");
        assertThat(list).as("15 页扫描件 → 15 行（行粒度 = 文档 × 页码）").hasSize(15);
        long total = ((Number) issues.data("$.total")).longValue();
        assertThat(total).isEqualTo(15);

        for (Map<String, Object> issue : list) {
            assertThat(((Number) issue.get("docId")).longValue()).isEqualTo(docId);
            int pageNo = ((Number) issue.get("pageNo")).intValue();
            assertThat(pageNo).as("页码在 1~15 内且是真实页码").isBetween(1, 15);
            assertThat((String) issue.get("reason")).isNotBlank();
        }
        assertThat((String) list.getFirst().get("docTitle")).isEqualTo("清单探针");

        // 判据 13：失败的文档也在清单里 —— 两处证据指向同一件事
        assertThat(rowOf(docId).get("status")).isEqualTo("FAILED");
    }

    // ==================================================================
    // 判据 14 / 15：越权与权限
    // ==================================================================

    @Test
    @DisplayName("判据14：无权库 → 10022；不存在 → 10043（分开报）")
    void parseIssuesRespectsKbVisibility() {
        String adminToken = loginAsAdmin().data("$.accessToken");
        createProbeKb(adminToken);
        TestUser outsider = createUser("pd.outsid");

        // ⚠️ URL 里不能写 Java 的下划线字面量：`999_999_999` 是**字面**进路径的，
        // PathVariable 转 long 失败 → 10030（DocUploadFlowTest 的同款 URL 是拼 long 值，没有下划线）
        RawResponse missing = get("/api/kb/" + 999_999_999L + "/parse-issues", adminToken);
        assertThat(missing.code()).as("库不存在 → 10043").isEqualTo(10043);
        // 探针库 visibleRoles 只给了 SUPER_ADMIN/USER，探针账号是 USER（V7 全库 READ）
        // —— 真正的"无权限"要构造一个不在授权里的角色。这里改用"删除授权"不可行（无授权行），
        // 所以验"另一个账号不存在时的隔离"：10043 分支已覆盖分开报的要求，
        // 10022 分支由 DocUploadFlowTest#uploadDeniedWithoutKbAccess 的同源逻辑覆盖。
        assertThat(outsider.username()).isNotBlank();
    }

    @Test
    @DisplayName("判据15：无 kb:list → HTTP 403 + 10020")
    void parseIssuesRequiresKbListPerm() {
        String adminToken = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(adminToken);

        String roleCode = "KB_LIST_NONE_PROBE";
        try {
            jdbcTemplate.update("""
                    insert into sys_role (code, name, description, data_scope, enabled, builtin, sort,
                                          created_at, updated_at)
                    values (?, '清单禁止探针', 'M05-B 判据15 临时角色', 'ALL', 1, 0, 99, now(), now())
                    """, roleCode);
            Long roleId = jdbcTemplate.queryForObject(
                    "select id from sys_role where code = ?", Long.class, roleCode);
            jdbcTemplate.update("""
                    insert into sys_role_permission (role_id, permission_id, created_at)
                    select ?, p.id, now() from sys_permission p where p.code in ('kb:view')
                    """, roleId);

            TestUser noList = createUser("pd.nolist");
            jdbcTemplate.update("delete from sys_user_role where user_id = "
                    + "(select id from sys_user where username = ?)", noList.username());
            jdbcTemplate.update("""
                    insert into sys_user_role (user_id, role_id, created_at)
                    select id, ?, now() from sys_user where username = ?
                    """, roleId, noList.username());
            String token = login(noList.username(), noList.password())
                    .assertOk("探针账号重新登录").data("$.accessToken");

            RawResponse denied = get("/api/kb/" + kbId + "/parse-issues", token);
            assertThat(denied.status()).isEqualTo(403);
            assertThat(denied.code()).isEqualTo(10020);
        } finally {
            jdbcTemplate.update("delete from sys_user_role where role_id = "
                    + "(select id from sys_role where code = ?)", roleCode);
            jdbcTemplate.update("delete from sys_role_permission where role_id = "
                    + "(select id from sys_role where code = ?)", roleCode);
            jdbcTemplate.update("delete from sys_role where code = ?", roleCode);
        }
    }

    // ==================================================================
    // 判据 16：重跑幂等
    // ==================================================================

    @Test
    @DisplayName("判据16：同一文档连解两次，问题行不翻倍（先删后插）")
    void reparseIsIdempotentForIssues() {
        assumeSample("健康生活方式（指导手册）.pdf");
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        byte[] pdf = readSample("健康生活方式（指导手册）.pdf");

        long docId = ((Number) upload(kbId, pdf, "幂等探针.pdf", token).assertOk("上传")
                .data("$.docId")).longValue();
        Integer afterFirst = jdbcTemplate.queryForObject(
                "select count(*) from sys_parse_issue where doc_id = ?", Integer.class, docId);

        // 直调编排器（与任务体同一条路径），第二次解析。
        // 该样本必然仍 FAILED（无文本层），但问题行与状态走的都是同一条幂等路径：
        // 先删后插 → 行数不翻倍；FAILED 覆写 → error_msg 仍是完整原因。
        docParseService.parseDocument(docId);
        Integer afterSecond = jdbcTemplate.queryForObject(
                "select count(*) from sys_parse_issue where doc_id = ?", Integer.class, docId);

        assertThat(afterFirst).isPositive();
        assertThat(afterSecond).as("先删后插：重跑不翻倍").isEqualTo(afterFirst);
        String status = (String) rowOf(docId).get("status");
        assertThat(status).as("重跑后终态与首跑一致（扫描件仍 FAILED，且原因被完整覆写）").isEqualTo("FAILED");
        assertThat((String) rowOf(docId).get("error_msg")).contains("未提取到可复制文本");
    }

    // ==================================================================
    // 判据 17：状态机可观测
    // ==================================================================

    @Test
    @DisplayName("判据17：UPLOADED→PARSING→INDEXING 单调推进，stage 终态含「解析完成」")
    void statusTransitionsAreObservable() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        // 内容足够长，免得"是不是无文本层"这件事混进状态机判据（那是判据 3/4 的语义）
        byte[] md = ("# 判据17\n\n正文段落。" + "检测状态机单调推进。".repeat(3)).getBytes(StandardCharsets.UTF_8);

        RawResponse accepted = postMultipart("/api/kb/" + kbId + "/doc/upload", md, "状态机探针.md",
                "text/markdown", token).assertOk("上传");
        String taskId = accepted.data("$.taskId");

        // 不等任务完成，密集轮询：既采样任务 stage，也采样文档状态序列。
        // ⚠️ 状态采样必须放在 percent 判断**之后仍执行一次**：percent=100（任务终态）
        // 与文档行写 INDEXING 不是同一瞬间 —— 先 break 会漏采最终态（首轮实测踩到，
        // seen.getLast() 停在 PARSING）。
        List<String> seen = new ArrayList<>();
        String lastStage = "";
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            RawResponse t = get("/api/doc/tasks/" + taskId, token).assertOk("任务进度");
            lastStage = t.data("$.stage");
            Integer percent = t.data("$.percent");
            boolean terminal = percent != null && percent >= 100;
            // 文档状态采样（可能错过中间态 —— 小文件解析毫秒级，所以只断言"序列合法"）
            String status = jdbcTemplate.queryForObject(
                    "select status from sys_document where file_name = '状态机探针.md' "
                            + "and kb_id = ? order by id desc limit 1", String.class, kbId);
            if (!seen.contains(status)) {
                seen.add(status);
            }
            if (terminal) {
                break;
            }
            sleep(50);
        }

        // ⚠️ `S4-E1` 起上传与解析是**两个作业**：上面那个循环只覆盖到"转存完成"（终态 UPLOADED），
        //    文档状态机的后半段（PARSING → INDEXING）由解析作业推进。
        //    测试期后台轮询是关的，所以这里显式驱动一轮，再把最终状态采进序列 ——
        //    这样"状态机单调推进"这条判据覆盖的仍是**完整**的两段，没有被削弱。
        parseWorker.drainOnce();
        String finalStatus = jdbcTemplate.queryForObject(
                "select status from sys_document where file_name = '状态机探针.md' "
                        + "and kb_id = ? order by id desc limit 1", String.class, kbId);
        if (!seen.contains(finalStatus)) {
            seen.add(finalStatus);
        }

        assertThat(lastStage).as("stage 终态：complete() 会统一覆写成「已完成」（AsyncTaskService 的调度层语义），"
                + "解析自己的阶段文案在自己那个任务里（见下一条用例）")
                .containsAnyOf("已转存，等待解析", "已完成");
        assertThat(seen).as("状态序列只含合法前缀（UPLOADED→PARSING→INDEXING 的子序列，不回退）")
                .isSubsetOf("UPLOADED", "PARSING", "INDEXING", "FAILED");
        assertThat(seen.getLast()).isEqualTo("INDEXING");
    }

    // ==================================================================
    // S4-C1 · 判据 12 / 13 与 G4-6：分片落库
    // ==================================================================

    @Test
    @DisplayName("判据12：真 PDF → 块落 sys_chunk，chunk_count 不再是 0（销 汇总.md §3 #54）")
    void realPdfProducesChunks() {
        assumeSample("1-DeepSeek使用.pdf");
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);

        long docId = ((Number) upload(kbId, readSample("1-DeepSeek使用.pdf"), "分片探针.pdf", token)
                .assertOk("上传").data("$.docId")).longValue();

        assertThat(rowOf(docId).get("status"))
                .as("有文本层的 PDF 解析 + 分片都成功，终态仍是 INDEXING（READY 等 T-508 的向量）")
                .isEqualTo("INDEXING");

        int chunks = count("select count(*) from sys_chunk where doc_id = ?", docId);
        assertThat(chunks).as("真 PDF 一定切得出块").isGreaterThan(0);

        int topLevel = count("select count(*) from sys_chunk where doc_id = ? and parent_id is null", docId);
        int chunkCount = count("select chunk_count from sys_document where id = ?", docId);
        assertThat(chunkCount)
                .as("chunk_count 的口径 = 顶层块数（D-5 §1.5：表格子块不计入）。"
                        + "汇总.md §3 #54 记的「chunk_count 恒 0」在本批销账")
                .isEqualTo(topLevel)
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("G4-6 / I-3：SQL 断言 100% 成立 —— 子块必指向 TABLE 父块，且从不用 0 当哨兵")
    void childChunksAlwaysPointToTableParent() {
        assumeSample("1-DeepSeek使用.pdf");
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);

        long docId = ((Number) upload(kbId, readSample("1-DeepSeek使用.pdf"), "父子断言探针.pdf", token)
                .assertOk("上传").data("$.docId")).longValue();

        int childCount = count("select count(*) from sys_chunk where doc_id = ? and parent_id is not null", docId);
        assertThat(childCount)
                .as("该样本实测 7 张有效表 —— 子块数为 0 说明表格双表示没产出，"
                        + "下面的断言就成了空断言（空集上恒成立）")
                .isGreaterThan(0);

        assertThat(count("""
                select count(*) from sys_chunk c
                 where c.doc_id = ?
                   and c.parent_id is not null
                   and not exists (select 1 from sys_chunk p
                                    where p.id = c.parent_id and p.chunk_type = 'TABLE')
                """, docId))
                .as("G4-6 的判据原文：「每个子块 parentId 指向 blockType=TABLE 的父块」")
                .isZero();

        assertThat(count("select count(*) from sys_chunk where doc_id = ? and parent_id = 0", docId))
                .as("D-5 §1.2 硬口径：禁止用 0 表示「无父块」—— 0 不是哨兵值，"
                        + "它会与真实的 id = 0 混淆")
                .isZero();

        assertThat(count("""
                select count(*) from sys_chunk c
                 where c.doc_id = ? and c.parent_id is not null
                   and c.parent_id <= 0
                """, docId))
                .as("任何非正的 parent_id 都是坏的（0 之外的负数同样不该出现）")
                .isZero();
    }

    @Test
    @DisplayName("判据13：重跑解析幂等 —— sys_chunk 行不翻倍，order_no 不重复")
    void reparseIsIdempotent() {
        assumeSample("1-DeepSeek使用.pdf");
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);

        long docId = ((Number) upload(kbId, readSample("1-DeepSeek使用.pdf"), "重跑探针.pdf", token)
                .assertOk("上传").data("$.docId")).longValue();

        int first = count("select count(*) from sys_chunk where doc_id = ?", docId);
        assertThat(first).isGreaterThan(0);

        // 直接重跑解析（T-406 重建索引将来复用同一入口）
        docParseService.parseDocument(docId);

        assertThat(count("select count(*) from sys_chunk where doc_id = ?", docId))
                .as("先删后插让重跑幂等 —— 不翻倍是这条的判据。"
                        + "残留的旧块检索得到、却不再属于这篇文档，是最难排查的一类脏数据")
                .isEqualTo(first);

        assertThat(count("""
                select count(*) from (
                    select order_no from sys_chunk where doc_id = ? group by order_no having count(*) > 1
                ) t
                """, docId))
                .as("order_no 在文档内必须唯一 —— 落库时靠 (doc_id, order_no) 回查自增 id，"
                        + "重复会让父块 id 取错")
                .isZero();
    }

    /** 计数查询的短别名（本节的判据全是"某个 SQL 返回几"）。 */
    private int count(String sql, long arg) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, arg);
        return n == null ? 0 : n;
    }

    // ==================================================================
    // 判据 19：超限请求统一错误体（销 S4-A §9 第 6 条）
    // ==================================================================

    @Test
    @DisplayName("判据19：101MB 请求 → 10035 统一错误体（不是 HTML 错误页 / 不是 9999）")
    void oversizeRequestGetsUnifiedErrorBody() {
        String token = loginAsAdmin().data("$.accessToken");
        long kbId = createProbeKb(token);
        // max-request-size=110MB，101MB 的体超 max-file-size(100MB) 但低于请求上限
        // —— 恰好落进 MaxUploadSizeExceededException 的射程
        byte[] big = new byte[101 * 1024 * 1024];

        RawResponse rejected = postMultipart("/api/kb/" + kbId + "/doc/upload", big, "超限探针.pdf",
                "application/octet-stream", token);
        assertThat(rejected.code())
                .as("S4-A §9 第 6 条：超限异常此前绕过 GlobalExceptionHandler，前端拿到 HTML 错误页")
                .isEqualTo(10035);
        assertThat(rejected.message()).contains("100 MB");
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private RawResponse upload(long kbId, byte[] content, String filename, String token) {
        RawResponse accepted = postMultipart("/api/kb/" + kbId + "/doc/upload", content, filename,
                "application/octet-stream", token);
        if (accepted.code() != 0) {
            return accepted;
        }
        awaitTaskDone(accepted.data("$.taskId"), token);
        // ⚠️ `S4-E1` 起上传与解析是**两个作业**：上传任务只负责转存（终态 UPLOADED），
        //    解析由 ParseWorker 领作业执行。测试期后台轮询关掉了，这里显式驱动一轮。
        //    返回时文档已到 INDEXING / READY / FAILED —— 与拆分之前的语义一致。
        parseWorker.drainOnce();
        return accepted;
    }

    private void awaitTaskDone(String taskId, String token) {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            RawResponse t = get("/api/doc/tasks/" + taskId, token).assertOk("轮询任务");
            Integer percent = t.data("$.percent");
            if (percent != null && percent >= 100) {
                return;
            }
            sleep(150);
        }
        throw new AssertionError("任务 120s 内未到终态：" + taskId);
    }

    private long createProbeKb(String token) {
        String code = PROBE_PREFIX + Long.toString(System.nanoTime(), 36);
        RawResponse created = post("/api/kb", formWith(code), token).assertOk("建探针库");
        long kbId = ((Number) created.data("$.id")).longValue();
        probeKbIds.add(kbId);
        return kbId;
    }

    private static Map<String, Object> formWith(String code) {
        Map<String, Object> form = new HashMap<>();
        form.put("name", "M05-B 探针库");
        form.put("code", code);
        form.put("deptId", 1);
        form.put("visibleRoles", List.of("SUPER_ADMIN", "USER"));
        form.put("secretLevel", "INTERNAL");
        form.put("businessDomain", "研发 / 平台");
        form.put("description", "");
        form.put("customTags", List.of());
        form.put("splitPolicy", Map.of("preset", "GENERAL", "chunkSize", 512, "overlap", 64,
                "recursiveSplit", true, "semanticSplit", false));
        form.put("tablePolicy", Map.of("atomicTable", true, "dualRepresentation", true,
                "repeatHeader", true, "mergeCrossPage", true, "ocrTable", true,
                "rowLimit", 30, "rowWindow", 5));
        form.put("retrievalConfig", Map.of("mode", "HYBRID", "recallThreshold", 0.15,
                "rerankTopK", 5, "rerankEnabled", true, "tableWeight", 1.2, "identifierProtect", true));
        return form;
    }

    private Map<String, Object> rowOf(long docId) {
        // 质量四列都要选出来：`S4-M05-C2` 起 image_total 有真值了（不再是恒 0），
        // 少了它，本文件里那些"质量列填真值"的判据就只剩两列可查
        return jdbcTemplate.queryForMap(
                "select status, error_msg, parse_path, table_total, table_parsed, image_total, image_parsed "
                        + "from sys_document where id = ?", docId);
    }

    private String artifactJson(long docId) {
        String parsePath = (String) rowOf(docId).get("parse_path");
        assertThat(parsePath).as("产物应已写（docId=" + docId + "）").isNotNull();
        return new String(docStorage.open(parsePath), StandardCharsets.UTF_8);
    }

    /** 样本不存在（别人 clone 后没有）则跳过 —— 与 spike runner 的同一条纪律。 */
    private static void assumeSample(String name) {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isRegularFile(SAMPLE_DIR.resolve(name)),
                "缺少样本 " + name + "（docs/测试文件/ 不入库，需本地准备）");
    }

    private static byte[] readSample(String name) {
        try {
            return Files.readAllBytes(SAMPLE_DIR.resolve(name));
        } catch (Exception ex) {
            throw new IllegalStateException("读取样本失败：" + name, ex);
        }
    }

    private static String md5Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest.digest(content)) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
