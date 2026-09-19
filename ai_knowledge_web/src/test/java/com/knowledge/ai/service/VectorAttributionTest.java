package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.BlockType;
import com.knowledge.ai.pipeline.Block;
import com.knowledge.ai.pipeline.retrieve.RetrievalFlowSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 向量模型归因的门禁用例（`T-O09` 判据 7 · `S5-M07-A` 步 14）。
 *
 * <h3>判据 7：`embedModelId + version` 非空、**换模型后能筛出旧版本向量**</h3>
 *
 * <h3>⚠️ 一处与计划 §3.1 步 14 措辞的偏差（已在计划 §9 登记）</h3>
 * <p>步 14 的原话是「落 **Milvus 标量列**」，但集合本批已为 BM25 重建过一次
 * （10 列，见 {@code MilvusConstants} 的列数口径），再加两列就是**第二次重建**；
 * 而这两列是"这条向量的元数据"、**不是检索过滤维度** —— 检索从不按模型筛，
 * 只在"换模型要找出旧向量"这个**离线**动作里被查一次。
 * 所以按计划 §4 的**数据变更清单**（那份表把这两列列在 {@code sys_chunk} 下）
 * 落在 MySQL。两条口径冲突时以 §4 为准，理由写在这里与结项文档的遗留节。
 *
 * <p>本类因此验的是**存储与可筛性**（那才是判据的内容），不依赖 Milvus。
 */
class VectorAttributionTest extends RetrievalFlowSupport {

    @Autowired
    private DocRecordWriter recordWriter;

    @BeforeEach
    void setUp() {
        resetProbeState();
        kbId = insertKb("attribution-probe");
    }

    @AfterEach
    void tearDown() {
        cleanProbeData();
    }

    @Test
    @DisplayName("判据 7 · 归因写进分片：两列都非空，且整篇文档一起盖章")
    void attributionIsStampedOnEveryChunkOfTheDocument() {
        long docId = docWithChunks("归因文档", "第一段正文", "第二段正文", "第三段正文");

        recordWriter.markVectorAttribution(docId, "BAAI/bge-m3", "v1");

        List<Object[]> rows = jdbc.query(
                "select embed_model_id, embed_model_version from sys_chunk where doc_id = ?",
                (rs, i) -> new Object[] {rs.getString(1), rs.getString(2)}, docId);

        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row[0]).isEqualTo("BAAI/bge-m3");
            assertThat(row[1]).isEqualTo("v1");
        });
    }

    @Test
    @DisplayName("判据 7 · 换模型后能按版本**筛出旧向量**（这是这两列存在的全部理由）")
    void oldVectorsCanBeSelectedByModelVersion() {
        long oldDoc = docWithChunks("旧模型文档", "旧模型的正文");
        long newDoc = docWithChunks("新模型文档", "新模型的正文");
        recordWriter.markVectorAttribution(oldDoc, "BAAI/bge-m3", "v1");
        recordWriter.markVectorAttribution(newDoc, "BAAI/bge-m3-2026", "v2");

        List<Long> staleDocIds = jdbc.queryForList("""
                select distinct doc_id from sys_chunk
                 where kb_id = ? and (embed_model_id is null or embed_model_id <> ?)
                """, Long.class, kbId, "BAAI/bge-m3-2026");

        assertThat(staleDocIds)
                .as("换模型之后，这条查询就是「哪些向量该重建」的清单")
                .containsExactly(oldDoc);
        assertThat(staleDocIds).doesNotContain(newDoc);
    }

    @Test
    @DisplayName("判据 7 · 本批之前的旧向量（两列为 NULL）也算「该重建」")
    void legacyVectorsWithoutAttributionCountAsStale() {
        long legacy = docWithChunks("历史文档", "没有任何归因的正文");

        List<Long> stale = jdbc.queryForList(
                "select distinct doc_id from sys_chunk where kb_id = ? and embed_model_id is null",
                Long.class, kbId);

        assertThat(stale).containsExactly(legacy);
    }

    @Test
    @DisplayName("归因不可得时**留 NULL**，不写空串（「未记录」与「记了但是空的」是两回事）")
    void unknownAttributionStaysNull() {
        long docId = docWithChunks("无归因文档", "正文");

        recordWriter.markVectorAttribution(docId, "", "");

        List<Object[]> rows = jdbc.query(
                "select embed_model_id, embed_model_version from sys_chunk where doc_id = ?",
                (rs, i) -> new Object[] {rs.getObject(1), rs.getObject(2)}, docId);

        assertThat(rows).allSatisfy(row -> {
            assertThat(row[0]).as("必须是 NULL —— 空串会被读成「记了，只是模型名是空的」").isNull();
            assertThat(row[1]).isNull();
        });
    }

    @Test
    @DisplayName("重建（换代）时归因会被**刷新**，记的不是上一次是谁产的")
    void reattributionOverwritesThePreviousValue() {
        long docId = docWithChunks("重建文档", "正文");
        recordWriter.markVectorAttribution(docId, "BAAI/bge-m3", "v1");
        recordWriter.markVectorAttribution(docId, "BAAI/bge-m3-2026", "v2");

        assertThat(jdbc.queryForObject(
                "select embed_model_id from sys_chunk where doc_id = ?", String.class, docId))
                .isEqualTo("BAAI/bge-m3-2026");
        assertThat(jdbc.queryForObject(
                "select embed_model_version from sys_chunk where doc_id = ?", String.class, docId))
                .isEqualTo("v2");
    }

    // ==================== 工具 ====================

    /** 走真实写入路径建一篇带分片的文档（归因是 `replaceChunks` 之后的步骤）。 */
    private long docWithChunks(String title, String... texts) {
        long docId = insertEmptyDoc(title);
        List<Block> blocks = new java.util.ArrayList<>();
        int order = 1;
        for (String text : texts) {
            blocks.add(new Block(text, BlockType.PARAGRAPH, null, order++, 1, null, null, null));
        }
        recordWriter.replaceChunks(docId, kbId, blocks);
        return docId;
    }
}
