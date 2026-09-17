package com.knowledge.ai.pipeline.retrieve;

import com.knowledge.ai.core.enums.KbConfigDefaults;
import com.knowledge.ai.core.text.ContentFingerprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表格回表的**取数**用例（`T-705`）—— 走真库、真 SQL。
 *
 * <h3>纯函数用例管不住的正是这一层</h3>
 * <p>{@code TableWindowTest} 钉的是"给定了事实之后怎么裁"。这里钉的是事实**取得对不对**：
 * 父块内容取到了没、命中行序号数是几、该父块一共几个子块、窗口参数取的是**这个分片所属库**
 * 的还是别人的。这一层错了，{@code TableWindow} 会把错的输入算得完全正确 ——
 * 而现象是"表格上下文有、但行不对"，比"没有上下文"难查得多。
 *
 * <p>用真库而不是替身：那条 SQL 里的两个相关子查询（数总行数、数自己排第几）是本功能
 * 最容易写错的部分，用 mock 等于把它抄一遍再去断言抄本。
 */
class TableLookupFlowTest extends RetrievalFlowSupport {

    /** 库级策略：3 行以上就裁、前后各留 1 行 —— 用小数字让断言的边界看得见。 */
    private static final String POLICY_LIMIT_3_WINDOW_1 =
            "{\"atomicTable\":true,\"dualRepresentation\":true,\"repeatHeader\":true,"
                    + "\"mergeCrossPage\":true,\"ocrTable\":true,\"rowLimit\":3,\"rowWindow\":1}";

    /** 库级策略：100 行以内都不裁 —— 用来证明"读的是库里的值，不是常量"。 */
    private static final String POLICY_LIMIT_100 =
            "{\"atomicTable\":true,\"dualRepresentation\":true,\"repeatHeader\":true,"
                    + "\"mergeCrossPage\":true,\"ocrTable\":true,\"rowLimit\":100,\"rowWindow\":5}";

    @Autowired
    private TableLookupService tableLookupService;

    @AfterEach
    void tearDown() {
        cleanProbeData();
    }

    // ==================== 判据 1 · 真查得到、`rowFrom/rowTo` 对 ====================

    @Test
    @DisplayName("T-705 · 命中第 5 行 → 从库里取到父块、行号与总行数，按库级策略裁出 4~6 行")
    void lookupFillsTableContextFromDatabase() {
        long kb = insertKb("probe-table-1");
        jdbc.update("update sys_kb set table_policy_json = ? where id = ?", POLICY_LIMIT_3_WINDOW_1, kb);
        long parentId = insertTableDoc(kb, 10);

        List<RetrievedChunk> out = tableLookupService.apply(List.of(tableChild(kb, 5)));

        RetrievedChunk.TableContext ctx = out.getFirst().tableContext();
        assertThat(ctx).as("表格子块必须被补上上下文").isNotNull();
        assertThat(ctx.parentChunkId()).isEqualTo(parentId).isNotEqualTo(0L);
        assertThat(ctx.totalRows()).as("10 个数据行（不含表头）").isEqualTo(10);
        assertThat(ctx.windowed()).as("10 > rowLimit 3 → 裁").isTrue();
        assertThat(ctx.rowFrom()).isEqualTo(4);
        assertThat(ctx.rowTo()).isEqualTo(6);
        assertThat(ctx.matchedRow()).as("命中的那一行原文必须留着").isEqualTo("型号 M-5，单价 价5");
        assertThat(out.getFirst().content())
                .as("拿回来的是行窗口，不是原来那一行")
                .contains("| 型号 | 单价 |").contains("| M-4 |").contains("| M-6 |")
                .doesNotContain("| M-3 |").doesNotContain("| M-7 |");
    }

    @Test
    @DisplayName("T-705 · 库级策略不同 → 同一张表结论不同（证明读的是 sys_kb 不是常量）")
    void windowParametersComeFromTheKbPolicy() {
        long strict = insertKb("probe-table-strict");
        jdbc.update("update sys_kb set table_policy_json = ? where id = ?", POLICY_LIMIT_3_WINDOW_1, strict);
        insertTableDoc(strict, 10);

        long lenient = insertKb("probe-table-lenient");
        jdbc.update("update sys_kb set table_policy_json = ? where id = ?", POLICY_LIMIT_100, lenient);
        insertTableDoc(lenient, 10);

        List<RetrievedChunk> strictOut = tableLookupService.apply(List.of(tableChild(strict, 5)));
        List<RetrievedChunk> lenientOut = tableLookupService.apply(List.of(tableChild(lenient, 5)));

        assertThat(strictOut.getFirst().tableContext().windowed()).as("库 rowLimit=3 → 裁").isTrue();
        assertThat(lenientOut.getFirst().tableContext().windowed())
                .as("库 rowLimit=100 → 不裁。若这里也是 true，说明读的是 KbConfigDefaults 而不是库配置")
                .isFalse();
        assertThat(lenientOut.getFirst().tableContext().totalRows()).isEqualTo(10);
    }

    @Test
    @DisplayName("T-705 · 库没有配策略（该列为 NULL）→ 走 KbConfigDefaults 的 30/5")
    void nullPolicyFallsBackToDefaults() {
        long kb = insertKb("probe-table-null");
        assertThat(jdbc.queryForObject("select table_policy_json from sys_kb where id = ?", String.class, kb))
                .as("前提：本用例建库时不写该列").isNull();
        insertTableDoc(kb, KbConfigDefaults.ROW_LIMIT + 5);

        List<RetrievedChunk> out = tableLookupService.apply(List.of(tableChild(kb, 20)));

        RetrievedChunk.TableContext ctx = out.getFirst().tableContext();
        assertThat(ctx.windowed()).as("35 行 > 默认 30 → 裁").isTrue();
        assertThat(ctx.rowFrom()).isEqualTo(15);
        assertThat(ctx.rowTo()).isEqualTo(25);
    }

    // ==================== 不该动的绝不动 ====================

    @Test
    @DisplayName("T-705 · 非表格分片原样返回（不带 context、内容不变、列表实例都不换）")
    void nonTableChunkIsUntouched() {
        long kb = insertKb("probe-table-plain");
        insertTableDoc(kb, 10);
        // 同一个库里放一条普通正文分片 —— "同库里既有表格块也有正文块"才是真实形态
        jdbc.update("""
                        insert into sys_chunk (doc_id, kb_id, chunk_type, parent_id, title_path,
                                               raw_content, content_sha256, order_no, page_no, created_at)
                        values (0, ?, 'PARAGRAPH', null, null, ?, ?, 1, 1, now())
                        """, kb, "这是一段普通正文", ContentFingerprint.of("这是一段普通正文"));
        String plainChunkId = String.valueOf(jdbc.queryForObject(
                "select id from sys_chunk where kb_id = ? and chunk_type = 'PARAGRAPH'", Long.class, kb));

        List<RetrievedChunk> input = List.of(chunk(plainChunkId, kb, "PARAGRAPH", "这是一段普通正文"));
        List<RetrievedChunk> out = tableLookupService.apply(input);

        assertThat(out).isSameAs(input);
        assertThat(out.getFirst().tableContext()).isNull();
    }

    @Test
    @DisplayName("T-705 · 父块已不存在（重解析竞态）→ 该分片原样返回，不抛")
    void danglingParentLeavesChunkAlone() {
        long kb = insertKb("probe-table-dangling");
        // 解析是"按 doc_id 先删后插"的，所以"子块还在、父块已没了"这个窗口真实存在。
        // 回表是**增强**，不该在此时把整次问答打挂
        jdbc.update("""
                        insert into sys_chunk (doc_id, kb_id, chunk_type, parent_id, title_path,
                                               raw_content, content_sha256, order_no, page_no, created_at)
                        values (0, ?, 'TABLE', 99999999, null, '型号 M-1，单价 价1', ?, 2, 1, now())
                        """, kb, ContentFingerprint.of("型号 M-1，单价 价1"));
        String id = String.valueOf(jdbc.queryForObject(
                "select id from sys_chunk where kb_id = ? and parent_id = 99999999", Long.class, kb));

        List<RetrievedChunk> out = tableLookupService.apply(
                List.of(chunk(id, kb, "TABLE", "型号 M-1，单价 价1")));

        assertThat(out.getFirst().tableContext())
                .as("父块取不到就退回「一行文本」，而不是抛异常或拼一张空表").isNull();
        assertThat(out.getFirst().content()).isEqualTo("型号 M-1，单价 价1");
    }

    @Test
    @DisplayName("T-705 · 顶层 TABLE 块（父块自己）不会被当成子块去回表")
    void tableParentIsNotLookedUp() {
        long kb = insertKb("probe-table-parentonly");
        long parentId = insertTableDoc(kb, 10);

        List<RetrievedChunk> out = tableLookupService.apply(
                List.of(chunk(String.valueOf(parentId), kb, "TABLE", "| 型号 | 单价 |")));

        assertThat(out.getFirst().tableContext())
                .as("父块不进召回池，万一漏进来也不该自己回表到自己").isNull();
    }

    @Test
    @DisplayName("T-705 · 空列表与全非表格时不做任何查询、原样返回")
    void emptyInputShortCircuits() {
        assertThat(tableLookupService.apply(List.of())).isEmpty();
        List<RetrievedChunk> chunks = List.of(chunk("not-a-number", 1L, "PARAGRAPH", "正文"));
        assertThat(tableLookupService.apply(chunks))
                .as("主键不是数字（与 sys_chunk.id 不同源）时应跳过并留痕，而不是让 SQL 报错")
                .isSameAs(chunks);
    }

    // ==================== 夹具 ====================

    /**
     * 建一篇文档 + 一个 TABLE 父块 + {@code dataRows} 个 TABLE 子块。
     *
     * <p>子块的 {@code order_no} 从 2 起（父块占 1），于是"第 N 个子块"的序号恰好是 N ——
     * 这正是 {@code TableLookupService} 那条相关子查询要数的东西。
     *
     * @return 父块 id
     */
    private long insertTableDoc(long kb, int dataRows) {
        String md5 = String.format("%032x", Math.abs(("table-" + kb + "-" + dataRows).hashCode()));
        jdbc.update("""
                        insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5,
                                                  storage_path, status, chunk_count, table_total, table_parsed,
                                                  image_total, image_parsed, index_generation, created_at, updated_at)
                        values (?, 1, ?, ?, 'PDF', 1, ?, 'documents/probe', 'READY', ?, 1, 1, 0, 0, 1, now(), now())
                        """, kb, "表格探针-" + kb, "表格探针-" + kb + ".pdf", md5, dataRows + 1);
        Long docId = jdbc.queryForObject(
                "select id from sys_document where kb_id = ? and title = ?", Long.class, kb, "表格探针-" + kb);

        String parentMarkdown = markdown(dataRows);
        insertChunk(docId, kb, null, parentMarkdown, 1);
        Long parentId = jdbc.queryForObject(
                "select id from sys_chunk where doc_id = ? and parent_id is null", Long.class, docId);
        for (int i = 1; i <= dataRows; i++) {
            insertChunk(docId, kb, parentId, row(i), 1 + i);
        }
        return parentId;
    }

    private void insertChunk(long docId, long kb, Long parentId, String content, int orderNo) {
        jdbc.update("""
                        insert into sys_chunk (doc_id, kb_id, chunk_type, parent_id, title_path,
                                               raw_content, content_sha256, order_no, page_no, created_at)
                        values (?, ?, 'TABLE', ?, '报价 > 明细', ?, ?, ?, 1, now())
                        """, docId, kb, parentId, content, ContentFingerprint.of(content), orderNo);
    }

    private static String markdown(int rows) {
        StringBuilder sb = new StringBuilder("| 型号 | 单价 |\n|---|---|\n");
        for (int i = 1; i <= rows; i++) {
            sb.append("| M-").append(i).append(" | 价").append(i).append(" |\n");
        }
        return sb.toString();
    }

    private static String row(int i) {
        return "型号 M-" + i + "，单价 价" + i;
    }

    /**
     * 一个"指向第 {@code rowNo} 个子块"的检索结果 —— chunkId 就是那一行在真库里的 id。
     *
     * <p>两点都是刻意的：
     * <ul>
     *   <li>**查库反查 id**，不拿 {@code rowNo} 编一个。编出来的 id 在库里不存在，
     *       回表会走"父块取不到"那条路，用例会**以"通过"的方式**什么都没验到；</li>
     *   <li>**必须带 {@code kb}**：{@code tableChild} 的 id 与 {@code kbId} 都取自这个库。
     *       给一个假的 kbId，窗口参数就会落到兜底值上 —— 于是"库级策略生效"那条用例
     *       会**因为读不到策略而恰好也通过**（兜底值恰好也是 30/5 时就完全看不出差别）。</li>
     * </ul>
     */
    private RetrievedChunk tableChild(long kb, int rowNo) {
        Long chunkId = jdbc.queryForObject("""
                        select c.id from sys_chunk c join sys_chunk p on p.id = c.parent_id
                         where c.kb_id = ? and c.raw_content = ? and p.parent_id is null
                        """, Long.class, kb, row(rowNo));
        return chunk(String.valueOf(chunkId), kb, "TABLE", row(rowNo));
    }

    private RetrievedChunk chunk(String chunkId, long kbId, String blockType, String content) {
        return new RetrievedChunk(chunkId, 1L, kbId, blockType, "报价 > 明细", content,
                0.9, RetrievedChunk.ScoreKind.RERANK, 1, 1, 0.9, 0.9,
                Map.of(RetrievalHit.Channel.VECTOR, 1), false, null);
    }
}
