package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.BlockType;
import com.knowledge.ai.core.text.BoilerplateRule;
import com.knowledge.ai.core.text.ContentFingerprint;
import com.knowledge.ai.pipeline.Block;
import com.knowledge.ai.pipeline.retrieve.RetrievalFlowSupport;
import com.knowledge.ai.pipeline.vectorize.VectorChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨文档样板抑制的门禁用例（`T-O09` 判据 3/4 · `S5-M07-A` 步 13）。
 *
 * <h3>判据 3：同一段免责声明放进 25 个文档 → 被标记且不进召回池</h3>
 * <p>分片走**真实写入路径**（{@code DocRecordWriter#replaceChunks}），
 * 而不是 JDBC 直接插 —— 样板识别就长在那条路径上，绕过去测的就是自己的夹具。
 *
 * <h3>判据 4：长度边界不误杀</h3>
 * <p>频次单独用会误杀：一条"注意事项：请勿断电"出现在 50 个文档里是**真样板**；
 * 而"总计"两个字出现得更多却毫无意义。所以本类把两个反例与正例**并排**验 ——
 * 只验正例的话，一个"只要出现 ≥20 次就标"的实现照样能绿。
 */
class BoilerplateTest extends RetrievalFlowSupport {

    @Autowired
    private DocRecordWriter recordWriter;

    @Autowired
    private ChunkReader chunkReader;

    @BeforeEach
    void setUp() {
        resetProbeState();
        kbId = insertKb("boilerplate-probe");
    }

    @AfterEach
    void tearDown() {
        cleanProbeData();
    }

    @Test
    @DisplayName("判据 3 · 同一段免责声明放进 25 个文档 → 全部 is_boilerplate=1，且不进召回池")
    void boilerplateAcrossTwentyFiveDocumentsIsMarkedAndExcluded() {
        String disclaimer = disclaimerOf(280);

        for (int i = 0; i < 25; i++) {
            long docId = insertEmptyDoc("制度文件 " + i);
            recordWriter.replaceChunks(docId, kbId,
                    List.of(new Block(disclaimer, BlockType.PARAGRAPH, null, 1, 1, null, null, null)));
        }

        String hash = ContentFingerprint.of(disclaimer);
        Integer marked = jdbc.queryForObject("""
                select count(*) from sys_chunk where kb_id = ? and content_sha256 = ? and is_boilerplate = 1
                """, Integer.class, kbId, hash);

        assertThat(marked)
                .as("25 篇**全部**都要被标 —— 只标最后一篇的话，is_boilerplate 的含义就依赖入库顺序了")
                .isEqualTo(25);

        // 「不进召回池」的判据落在读取侧：ChunkReader 照样把它读出来（可追溯），
        // 但 inRecallPool() 为 false，ChunkVectorizer 据此跳过它
        long anyDoc = jdbc.queryForObject(
                "select id from sys_chunk where kb_id = ? and is_boilerplate = 1 limit 1", Long.class, kbId);
        List<VectorChunk> chunks = chunkReader.readByDocId(
                jdbc.queryForObject("select doc_id from sys_chunk where id = ?", Long.class, anyDoc));

        assertThat(chunks).as("保留在 sys_chunk 里（可追溯「为什么这段搜不到」）").isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.isBoilerplate()).isTrue();
            assertThat(chunk.vectorText()).as("投影本身仍然算得出来").isNotNull();
            assertThat(chunk.inRecallPool()).as("但不进召回池").isFalse();
        });
        assertThat(VectorChunk.onlyRecallable(chunks)).isEmpty();
    }

    @Test
    @DisplayName("判据 4 · 长度 **< 200** 的高频文本不被标记（「备注」这类通用词）")
    void shortTextIsNotMarked() {
        String shortText = "注意事项：请勿断电，操作前请阅读说明书。";

        assertThat(shortText.length()).as("本用例的前提：它确实短于阈值").isLessThan(BoilerplateRule.MIN_LENGTH);
        for (int i = 0; i < 25; i++) {
            long docId = insertEmptyDoc("短文本 " + i);
            recordWriter.replaceChunks(docId, kbId,
                    List.of(new Block(shortText, BlockType.PARAGRAPH, null, 1, 1, null, null, null)));
        }

        assertThat(markedCount(shortText)).as("频次够了但太短 → 不标").isZero();
    }

    @Test
    @DisplayName("判据 4 · 长度 **> 2000** 的高频文本不被标记（那更可能是同一份文档传了两遍）")
    void longTextIsNotMarked() {
        String longText = disclaimerOf(BoilerplateRule.MAX_LENGTH + 200);

        assertThat(longText.length()).isGreaterThan(BoilerplateRule.MAX_LENGTH);
        for (int i = 0; i < 25; i++) {
            long docId = insertEmptyDoc("长文本 " + i);
            recordWriter.replaceChunks(docId, kbId,
                    List.of(new Block(longText, BlockType.PARAGRAPH, null, 1, 1, null, null, null)));
        }

        assertThat(markedCount(longText))
                .as("长正文的「一模一样」更可能是重复上传 —— 那条路由内容指纹在文档级处理，"
                        + "不该由样板抑制**静默**抹掉")
                .isZero();
    }

    @Test
    @DisplayName("判据 4 · 只有 3 个文档 → 不标（长度合格、频次不够）")
    void belowDocumentThresholdIsNotMarked() {
        String text = disclaimerOf(280);
        for (int i = 0; i < BoilerplateRule.MIN_DOCS - 1; i++) {
            long docId = insertEmptyDoc("少数派 " + i);
            recordWriter.replaceChunks(docId, kbId,
                    List.of(new Block(text, BlockType.PARAGRAPH, null, 1, 1, null, null, null)));
        }

        assertThat(markedCount(text)).as("冷启动期频次不够是**正确行为**，不是缺陷").isZero();
    }

    @Test
    @DisplayName("判据 4 · 频次够了且长度合格 → 在**第 20 篇**时就触发（阈值含当前文档）")
    void thresholdCountsTheCurrentDocument() {
        String text = disclaimerOf(280);
        long triggeredAt = -1;
        for (int i = 1; i <= BoilerplateRule.MIN_DOCS; i++) {
            long docId = insertEmptyDoc("计数 " + i);
            recordWriter.replaceChunks(docId, kbId,
                    List.of(new Block(text, BlockType.PARAGRAPH, null, 1, 1, null, null, null)));
            if (triggeredAt < 0 && markedCount(text) > 0) {
                triggeredAt = i;
            }
        }

        assertThat(triggeredAt)
                .as("阈值口径是「含当前文档在内的不同文档数 ≥ %d」；不含自己会差一篇不触发",
                        BoilerplateRule.MIN_DOCS)
                .isEqualTo(BoilerplateRule.MIN_DOCS);
    }

    @Test
    @DisplayName("判据 4 · 跨库不合并计数（两个库各 15 篇相同文本，谁都不该被标）")
    void countsDoNotLeakAcrossKnowledgeBases() {
        String text = disclaimerOf(280);
        int half = BoilerplateRule.MIN_DOCS - 5;
        for (int i = 0; i < half; i++) {
            recordWriter.replaceChunks(insertEmptyDoc("甲库 " + i), kbId,
                    List.of(new Block(text, BlockType.PARAGRAPH, null, 1, 1, null, null, null)));
        }

        long otherKb = insertKb("boilerplate-probe-other");
        long savedKb = kbId;
        kbId = otherKb;
        for (int i = 0; i < half; i++) {
            recordWriter.replaceChunks(insertEmptyDoc("乙库 " + i), otherKb,
                    List.of(new Block(text, BlockType.PARAGRAPH, null, 1, 1, null, null, null)));
        }
        kbId = savedKb;

        assertThat(markedCount(text)).as("甲库自己不够 20 篇 → 不标").isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from sys_chunk where kb_id = ? and is_boilerplate = 1",
                Integer.class, otherKb)).as("乙库同理").isZero();
    }

    // ==================== 工具 ====================

    private int markedCount(String text) {
        return jdbc.queryForObject("""
                select count(*) from sys_chunk where kb_id = ? and content_sha256 = ? and is_boilerplate = 1
                """, Integer.class, kbId, ContentFingerprint.of(text));
    }

    /** 造一段长度可控的"免责声明"。 */
    private static String disclaimerOf(int length) {
        StringBuilder sb = new StringBuilder(length + 32);
        sb.append("免责声明：本文档仅供内部参考，不构成任何形式的承诺或保证。");
        while (sb.length() < length) {
            sb.append("使用者在依据本文档作出决策前，应自行核实相关信息的准确性与时效性。");
        }
        return sb.substring(0, length);
    }
}
