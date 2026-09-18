package com.knowledge.ai.pipeline.vectorize;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.pipeline.Block;
import com.knowledge.ai.pipeline.ChunkViews;
import com.knowledge.ai.core.enums.BlockType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * `T-508` 的**纯逻辑**部分：挑块 / 投影 / 分批调用 / 幂等调用形状。
 *
 * <p>不联网、不碰 Milvus、不碰数据库 —— 依赖全用假实现（与 {@code IndexEnhancerTest} 同一姿势）。
 * 真库写入另见 {@code MilvusVectorWriteTest}（`@Tag("network")`）。
 */
class ChunkVectorizerTest {

    /** 假的嵌入通道：记录**被调用了几次、每次几条**（分批是成本控制的第一层，必须可断言）。 */
    private static final class FakeEmbedding implements EmbeddingClient {

        private final List<Integer> batchSizes = new ArrayList<>();
        private final int dim;

        FakeEmbedding(int dim) {
            this.dim = dim;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            batchSizes.add(texts.size());
            List<float[]> out = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                float[] v = new float[dim];
                v[0] = i;
                out.add(v);
            }
            return out;
        }
    }

    /** 假的落库通道：记下最后一次的 docId 与记录。 */
    private static final class FakeWriter implements VectorWriter {

        private long lastDocId = -1;
        private String lastPartition = null;
        private List<VectorRecord> lastRecords = List.of();
        private int calls = 0;

        @Override
        public int replaceDocument(String partition, long docId, List<VectorRecord> records) {
            calls++;
            lastPartition = partition;
            lastDocId = docId;
            lastRecords = List.copyOf(records);
            return records.size();
        }
    }

    private static VectorChunk chunk(long id, String type, String raw, String titlePath, Long parentId) {
        return new VectorChunk(id, type, raw, titlePath, "INTERNAL", parentId);
    }

    @Test
    @DisplayName("写出的分区名 = kb{kbId}_g{代}（T-406 按代切换的命名口径，读写两侧必须一致）")
    void writesIntoThePartitionOfThatGeneration() {
        FakeWriter writer = new FakeWriter();
        ChunkVectorizer vectorizer = new ChunkVectorizer(new FakeEmbedding(1024), writer, true);

        vectorizer.vectorize(100L, 8L, 3, List.of(chunk(1, "PARAGRAPH", "正文", null, null)));

        assertThat(writer.lastPartition)
                .as("分区名由 MilvusConstants#partitionName 统一给出 —— 读取侧（T-406 切代后查哪个分区）"
                        + "与写入侧若各拼各的，就会写出一个检索永远不去查的分区")
                .isEqualTo("kb8_g3");
    }

    @Test
    @DisplayName("表格父块不进检索池，子块进（G4-5 / D-5 §1.4）")
    void tableParentIsSkippedAndChildIsNot() {
        FakeWriter writer = new FakeWriter();
        ChunkVectorizer vectorizer = new ChunkVectorizer(new FakeEmbedding(1024), writer, true);

        List<VectorChunk> chunks = List.of(
                chunk(1, "PARAGRAPH", "正文一", null, null),
                chunk(2, "TABLE", "| 列A | 列B |", null, null),        // 父块 → 跳过
                chunk(3, "TABLE", "型号 A-100，单价 ￥1,200", null, 2L), // 子块 → 进
                chunk(4, "SUMMARY", "摘要", null, null));

        ChunkVectorizer.Result r = vectorizer.vectorize(100L, 1L, 1, chunks);

        assertThat(r.status()).isEqualTo(ChunkVectorizer.Status.OK);
        assertThat(r.written()).isEqualTo(3);
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(writer.lastRecords).extracting(x -> x.chunk().chunkId())
                .containsExactly(1L, 3L, 4L);
        assertThat(r.allowsReady()).isTrue();
    }

    @Test
    @DisplayName("投影 = 标题路径 + 换行 + 原文，且与 ChunkViews 口径一致（判据 6）")
    void projectionMatchesChunkViews() {
        // 同一份内容、两条路：Block（分片期）vs VectorChunk（已落库）——
        // 两者必须给出逐字节相同的文本，否则"重建索引"与"首次索引"会产出不同的向量
        Block block = new Block("正文内容", BlockType.PARAGRAPH, "3. 接口 > 3.1 鉴权", 5, 2, null, null);
        VectorChunk row = chunk(7, "PARAGRAPH", "正文内容", "3. 接口 > 3.1 鉴权", null);

        assertThat(row.vectorText()).isEqualTo(ChunkViews.defaultVectorText(block));
        assertThat(row.vectorText()).isEqualTo("3. 接口 > 3.1 鉴权\n正文内容");
        assertThat(row.projectionChangesText()).isTrue();
    }

    @Test
    @DisplayName("标题路径为空时退化为原文，不留孤零零的换行")
    void blankTitlePathFallsBackToRaw() {
        VectorChunk row = chunk(7, "PARAGRAPH", "封面段", null, null);
        assertThat(row.vectorText()).isEqualTo("封面段");
        assertThat(row.projectionChangesText()).isFalse();

        VectorChunk blank = chunk(8, "PARAGRAPH", "封面段", "   ", null);
        assertThat(blank.vectorText()).isEqualTo("封面段");
    }

    @Test
    @DisplayName("空块集也要删一次（清掉上一次的残留向量）")
    void emptyChunkSetStillDeletes() {
        FakeWriter writer = new FakeWriter();
        ChunkVectorizer vectorizer = new ChunkVectorizer(new FakeEmbedding(1024), writer, true);

        // 只有表格父块 → 进池的是 0 个
        ChunkVectorizer.Result r = vectorizer.vectorize(100L, 1L, 1,
                List.of(chunk(2, "TABLE", "| a |", null, null)));

        assertThat(r.status()).isEqualTo(ChunkVectorizer.Status.EMPTY);
        assertThat(r.written()).isZero();
        assertThat(writer.calls).isEqualTo(1);
        assertThat(writer.lastRecords).isEmpty();
        // 空集也算"可以标 READY"：一篇真的没有可检索内容的文档，标可检索是对的
        assertThat(r.allowsReady()).isTrue();
    }

    @Test
    @DisplayName("开关关闭时：不调嵌入、不写库、不允许标 READY")
    void disabledSkipsEverything() {
        FakeEmbedding embedding = new FakeEmbedding(1024);
        FakeWriter writer = new FakeWriter();
        ChunkVectorizer vectorizer = new ChunkVectorizer(embedding, writer, false);

        ChunkVectorizer.Result r = vectorizer.vectorize(100L, 1L, 1,
                List.of(chunk(1, "PARAGRAPH", "正文", null, null)));

        assertThat(r.status()).isEqualTo(ChunkVectorizer.Status.DISABLED);
        assertThat(r.allowsReady()).isFalse();
        assertThat(embedding.batchSizes).isEmpty();
        assertThat(writer.calls).isZero();
    }

    @Test
    @DisplayName("N 个块只调一次嵌入通道（分批是通道自己的事，不在这里逐条调）")
    void embedsAllCandidatesInOneChannelCall() {
        FakeEmbedding embedding = new FakeEmbedding(1024);
        ChunkVectorizer vectorizer = new ChunkVectorizer(embedding, new FakeWriter(), true);

        List<VectorChunk> chunks = new ArrayList<>();
        for (int i = 1; i <= 35; i++) {
            chunks.add(chunk(i, "PARAGRAPH", "正文 " + i, null, null));
        }

        vectorizer.vectorize(100L, 1L, 1, chunks);

        // ⚠️ 本用例**测的不是分批** —— 分批在 EmbeddingClient.RegistryEmbeddingClient 里
        // （那里才碰得到模型、才有 batchSize）。这一层要保证的是：
        // **不要自己逐条调通道**（那会把"分批"这个唯一的成本控制点绕过去）。
        assertThat(embedding.batchSizes).containsExactly(35);
    }

    @Test
    @DisplayName("嵌入条数与块数不符：当场失败，拒绝按位对应")
    void mismatchedVectorCountFails() {
        // 一个永远少返回一条的假实现 —— 真出错时最危险的形态是"静默错位"
        EmbeddingClient shortChanged = texts -> {
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < texts.size() - 1; i++) {
                out.add(new float[1024]);
            }
            return out;
        };
        ChunkVectorizer vectorizer = new ChunkVectorizer(shortChanged, new FakeWriter(), true);

        assertThatThrownBy(() -> vectorizer.vectorize(100L, 1L, 1, List.of(
                chunk(1, "PARAGRAPH", "a", null, null),
                chunk(2, "PARAGRAPH", "b", null, null))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不符");
    }

    @Test
    @DisplayName("嵌入失败带上可行动的下一步（硬口径：现象 + 动作）")
    void embeddingFailureIsActionable() {
        EmbeddingClient boom = texts -> {
            throw new EmbeddingClient.EmbeddingFailedException(
                    "嵌入模型调用失败（第 1 批，2 条）：connection reset —— 请检查嵌入模型的配置与网络后重试");
        };
        ChunkVectorizer vectorizer = new ChunkVectorizer(boom, new FakeWriter(), true);

        assertThatThrownBy(() -> vectorizer.vectorize(100L, 1L, 1, List.of(
                chunk(1, "PARAGRAPH", "a", null, null),
                chunk(2, "PARAGRAPH", "b", null, null))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请检查嵌入模型的配置与网络后重试");
    }
}
