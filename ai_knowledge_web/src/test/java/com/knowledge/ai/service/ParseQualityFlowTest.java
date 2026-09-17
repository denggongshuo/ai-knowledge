package com.knowledge.ai.service;

import com.knowledge.ai.pipeline.ParseFingerprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 解析质量的可追溯与聚合用例（`T-O05` 步 5/6 · `O5` §5 判据 4/5/6）。
 *
 * <h3>判据 6 的钉子：聚合口径与库内真值**逐项吻合**</h3>
 * <p>这一条不是在验"接口能返回数"，是在验 **"仪表盘上的数与真相是同一个"**。
 * 所以它拿聚合结果去比**直查 SQL**，而不是比另一处也是我写的期望值 ——
 * 后者只能证明"我两次算得一样"，证明不了"我算对了"。
 *
 * <h3>判据 5 的钉子：重解析幂等</h3>
 * <p>`O5` §4 步 5 点名「注意幂等」。幂等在**队列**那一层（幂等键 {@code doc-parse:{docId}}），
 * 所以用例验的是"连投两次，第二次不产生新任务、库里也不多行"——
 * 而不是再实现一遍幂等判断。
 *
 * <h3>本用例自己造的每一行自己删</h3>
 * <p>文档、问题行、以及 {@code submitReparse} 投出去的 {@code sys_async_task} 行
 * 全部按 kb / taskId 清掉。`sys_async_task` 是已知的测试残留产物
 * （`plan/汇总.md` §3 #98/#105/#109/#115），本用例**不添新的**。
 */
@SpringBootTest
class ParseQualityFlowTest {

    private static final String OLD_VERSION = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ParseQualityService parseQualityService;

    @Autowired
    private DocRecordWriter recordWriter;

    private long kbId;

    private final List<String> createdTaskIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc.update("""
                        insert into sys_kb (code, name, dept_id, doc_count, chunk_count, status,
                                            secret_level, business_domain, reindex_required,
                                            index_generation, created_at, updated_at)
                        values ('probe-pq', 'probe-pq', 1, 0, 0, 'READY', 'INTERNAL', '', 0, 1, now(), now())
                        """);
        kbId = jdbc.queryForObject("select id from sys_kb where code = 'probe-pq'", Long.class);
    }

    @AfterEach
    void tearDown() {
        for (String taskId : createdTaskIds) {
            jdbc.update("delete from sys_async_task where task_id = ?", taskId);
        }
        createdTaskIds.clear();
        jdbc.update("delete from sys_parse_issue where kb_id = ?", kbId);
        jdbc.update("delete from sys_chunk where kb_id = ?", kbId);
        jdbc.update("delete from sys_document where kb_id = ?", kbId);
        jdbc.update("delete from sys_kb where id = ?", kbId);
    }

    // ==================== 判据 4 · 版本指纹真实可筛 ====================

    @Test
    @DisplayName("T-O05 步 4 · markParsed 把 chars 与 parse_version 写进库（不是只算不落）")
    void markParsedWritesCharsAndVersion() {
        long docId = insertDoc("回填探针", "READY", null);

        recordWriter.markParsed(docId, "parsed/1/1/parse.json", 7, 5, 3, 20, 18, 12345,
                ParseFingerprint.version());

        assertThat(jdbc.queryForObject("select chars from sys_document where id = ?", Integer.class, docId))
                .as("chars 是「平均 chars/页」的分子，落了它聚合才不必去读 MinIO")
                .isEqualTo(12345);
        assertThat(jdbc.queryForObject("select parse_version from sys_document where id = ?", String.class, docId))
                .as("指纹必须与当前代码算出的一致，否则这篇文档会被自己立刻判成「需要重解析」")
                .isEqualTo(ParseFingerprint.version());
    }

    @Test
    @DisplayName("O5-4 · 三类版本分布分得开：当前 / 旧版本 / 版本未知（NULL）")
    void outdatedSeparatesCurrentFromOldAndUnknown() {
        insertDoc("当前版本", "READY", ParseFingerprint.version());
        insertDoc("旧版本", "READY", OLD_VERSION);
        insertDoc("版本未知", "READY", null);

        ParseQualityService.Outdated outdated = parseQualityService.outdated(kbId);

        assertThat(outdated.total()).isEqualTo(3);
        assertThat(outdated.current()).isEqualTo(1);
        assertThat(outdated.outdated()).isEqualTo(1);
        assertThat(outdated.unknownVersion()).isEqualTo(1);
        assertThat(outdated.needsReparse())
                .as("需要重解析的 = 旧版本 + 版本未知。**NULL 必须算进来** —— "
                        + "把它当「已是最新」的话，刚加完指纹的老库会筛出空集，"
                        + "现象是「一切正常」")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("O5-4 · 软删文档不计入（否则重解析入口会把回收站里的文档也重跑一遍）")
    void softDeletedDocumentsAreExcluded() {
        insertDoc("在架", "READY", null);
        long deleted = insertDoc("已软删", "READY", null);
        jdbc.update("update sys_document set deleted_at = now() where id = ?", deleted);

        assertThat(parseQualityService.outdated(kbId).total()).isEqualTo(1);
        assertThat(parseQualityService.outdatedDocIds(kbId, 0)).hasSize(1);
    }

    // ==================== 判据 5 · 重解析幂等 ====================

    @Test
    @DisplayName("O5-5 · 连投两次不产生重复作业（幂等键在队列那一层）")
    void submitReparseIsIdempotent() {
        insertDoc("待重解析", "READY", null);
        insertDoc("也是旧版本", "READY", OLD_VERSION);

        List<String> first = parseQualityService.submitReparse(kbId, 1L, 10);
        createdTaskIds.addAll(first);
        List<String> second = parseQualityService.submitReparse(kbId, 1L, 10);

        assertThat(first).as("两篇都需要重解析").hasSize(2);
        assertThat(second)
                .as("第二次投递必须复用同一个 taskId —— 否则连点两下就把同一个文档排了两遍队")
                .isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "select count(*) from sys_async_task where type = 'doc-parse' and kb_id = ?",
                Integer.class, kbId))
                .as("库里也不许多出行")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("O5-5 · 投递的作业带业务坐标（kb_id/doc_id），重启后认得出归属")
    void submittedJobsCarryTheirBusinessCoordinates() {
        long docId = insertDoc("坐标探针", "READY", null);

        createdTaskIds.addAll(parseQualityService.submitReparse(kbId, 1L, 10));

        assertThat(jdbc.queryForObject(
                "select kb_id from sys_async_task where type = 'doc-parse' and doc_id = ?",
                Long.class, docId))
                .as("与 `T-709` 查出的是同一个形态：kb_id 为 NULL 时，任务认得出来、归属认不出来")
                .isEqualTo(kbId);
    }

    // ==================== 判据 6 · 聚合与库内真值逐项吻合 ====================

    @Test
    @DisplayName("O5-6 · 文档指标与直查 SQL 逐项吻合")
    void documentMetricsMatchDirectQueries() {
        insertDocWithQuality("已就绪", "READY", 7, 5, 3, 20, 18, 12345);
        insertDocWithQuality("索引中", "INDEXING", 2, 2, 0, 10, 10, 5000);
        insertDocWithQuality("失败", "FAILED", 0, 0, 0, 0, 0, 0);

        ParseQualityService.Metrics metrics = parseQualityService.metrics(kbId);

        assertThat(metrics.total()).isEqualTo(3);
        assertThat(metrics.ready()).isEqualTo(1);
        assertThat(metrics.indexing()).isEqualTo(1);
        assertThat(metrics.failed()).isEqualTo(1);
        assertThat(metrics.successRate())
                .as("解析完成即 INDEXING，所以 (READY + INDEXING) 属「这一步成功」")
                .isEqualTo(2.0 / 3.0);
        assertThat(metrics.chars()).isEqualTo(12345 + 5000);
        assertThat(metrics.pagesTotal()).isEqualTo(30);
        assertThat(metrics.pagesWithText()).isEqualTo(28);
        assertThat(metrics.textLayerRate()).isEqualTo(28.0 / 30.0);
        assertThat(metrics.tableTotal()).isEqualTo(9);
        assertThat(metrics.tableParsed()).isEqualTo(7);
        assertThat(metrics.imageTotal()).isEqualTo(3);
        assertThat(metrics.charsPerPage()).isEqualTo((12345 + 5000) / 30.0);

        // 与**直查**比，而不是与「我另算一遍」比 —— 后者只能证明我两次算得一样
        Long directChars = jdbc.queryForObject(
                "select coalesce(sum(chars),0) from sys_document where kb_id = ? and deleted_at is null",
                Long.class, kbId);
        assertThat(metrics.chars()).isEqualTo(directChars == null ? 0L : directChars.longValue());
        assertThat(metrics.byFormat().stream().mapToLong(ParseQualityService.FormatRow::total).sum())
                .as("各格式之和必须等于总数 —— 它们来自同一条分组查询，不该对不上")
                .isEqualTo(metrics.total());
        assertThat(metrics.byFormat()).allSatisfy(row -> assertThat(row.type()).isNotBlank());
    }

    @Test
    @DisplayName("O5-6 · 未识别表格清单与 sys_parse_issue 直查逐项吻合（本条是判据 6 的核心）")
    void issueMetricsMatchTheIssueTable() {
        long docId = insertDoc("有问题行", "READY", null);
        insertIssue(docId, 3, "NO_TEXT_LAYER");
        insertIssue(docId, 7, "NO_TEXT_LAYER");
        insertIssue(docId, 9, "EMPTY_PAGE");

        ParseQualityService.Metrics metrics = parseQualityService.metrics(kbId);

        long direct = jdbc.queryForObject(
                "select count(*) from sys_parse_issue where kb_id = ?", Long.class, kbId);
        assertThat(metrics.issueCount()).as("聚合报的条数必须等于表里真实的条数").isEqualTo((int) direct);
        assertThat(metrics.issueCount()).isEqualTo(3);
        assertThat(metrics.issueByKind().stream().mapToLong(ParseQualityService.KindRow::count).sum())
                .as("分种类之和必须等于总数（同一总体、同一次口径）")
                .isEqualTo(metrics.issueCount());
        assertThat(metrics.issueByKind()).extracting(ParseQualityService.KindRow::kind)
                .containsExactlyInAnyOrder("NO_TEXT_LAYER", "EMPTY_PAGE");
    }

    @Test
    @DisplayName("O5-6 · 库内没有文档时给 0 而不是 NaN（NaN 会被序列化成 null，页面变空白）")
    void emptyKbYieldsZeroesNotNaN() {
        ParseQualityService.Metrics metrics = parseQualityService.metrics(kbId);

        assertThat(metrics.total()).isZero();
        assertThat(metrics.successRate()).isZero();
        assertThat(metrics.textLayerRate()).isZero();
        assertThat(metrics.tableParseRate()).isZero();
        assertThat(metrics.charsPerPage()).isZero();
        assertThat(metrics.issueCount()).isZero();
        assertThat(metrics.byFormat()).isEmpty();
    }

    @Test
    @DisplayName("O5-6 · 全站口径（kbId=null）不小于单库口径")
    void siteWideMetricsCoverTheWholeSite() {
        insertDoc("本库文档", "READY", null);

        ParseQualityService.Metrics site = parseQualityService.metrics(null);
        ParseQualityService.Metrics one = parseQualityService.metrics(kbId);

        assertThat(site.total()).isGreaterThanOrEqualTo(one.total());
        assertThat(site.issueCount()).isGreaterThanOrEqualTo(one.issueCount());
    }

    // ==================== 夹具 ====================

    private long insertDoc(String title, String status, String parseVersion) {
        return insertDocWithQuality(title, status, 0, 0, 0, 0, 0, 0, parseVersion);
    }

    private long insertDocWithQuality(String title, String status, int tableTotal, int tableParsed,
                                      int imageTotal, int pagesTotal, int pagesWithText, int chars) {
        return insertDocWithQuality(title, status, tableTotal, tableParsed, imageTotal,
                pagesTotal, pagesWithText, chars, null);
    }

    private long insertDocWithQuality(String title, String status, int tableTotal, int tableParsed,
                                      int imageTotal, int pagesTotal, int pagesWithText, int chars,
                                      String parseVersion) {
        String md5 = String.format("%032x", Math.abs(title.hashCode()));
        jdbc.update("""
                        insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5,
                                                  storage_path, status, chunk_count, table_total, table_parsed,
                                                  image_total, image_parsed, pages_total, pages_with_text,
                                                  chars, parse_version, index_generation, created_at, updated_at)
                        values (?, 1, ?, ?, 'PDF', 1, ?, 'documents/probe', ?, 0, ?, ?, ?, 0, ?, ?, ?, ?, 1, now(), now())
                        """, kbId, title, title + ".pdf", md5, status, tableTotal, tableParsed,
                imageTotal, pagesTotal, pagesWithText, chars, parseVersion);
        return jdbc.queryForObject("select id from sys_document where kb_id = ? and title = ?",
                Long.class, kbId, title);
    }

    private void insertIssue(long docId, int pageNo, String kind) {
        jdbc.update("""
                        insert into sys_parse_issue (doc_id, kb_id, page_no, reason, kind, disposition, created_at)
                        values (?, ?, ?, ?, ?, 'MISSING', now())
                        """, docId, kbId, pageNo, "第 " + pageNo + " 页未提取到文本", kind);
    }
}
