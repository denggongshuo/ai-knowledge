package com.knowledge.ai.service;

import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.storage.MilvusPartitionManager;
import com.knowledge.ai.core.storage.MilvusProperties;
import com.knowledge.ai.pipeline.vectorize.VectorChunk;
import com.knowledge.ai.pipeline.vectorize.VectorRecord;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Milvus 写入链路的真机用例（`T-508`；`S5-M07-A` 迁到 v2 并加 BM25 输入列）。
 *
 * <p>⚠️ <b>2026-09-17 那次 v1 → v2 迁移把这里的三件事一起改了</b>：
 * <ol>
 *   <li>客户端换成 {@link MilvusClientV2}（v1 的 {@code MilvusServiceClient} 已从装配里移除）；</li>
 *   <li>{@code G4-10} 的字段集断言从「**恰好 8 列**」改为「**恰好 10 列**」——
 *       BM25 必须有 {@code bm25_text} + {@code sparse} 两列承载，口径订正与理由见
 *       {@link MilvusConstants} 类 javadoc；</li>
 *   <li>新增「{@code bm25_text} 真的写进去了」这条断言 —— 光有列不算数，
 *       列里没内容时 BM25 一路会**静默空转**（检索永远返回空，不报错）。</li>
 * </ol>
 */
@Tag("network")
class MilvusVectorWriteTest {

    /** 测试专用的 doc_id 区间（负号 + 999 段，与自增的正 id 绝不相撞）。 */
    private static final long TEST_DOC_ID = -999_001L;

    private static final long TEST_KB_ID = 1L;

    /**
     * 测试专用分区（`T-406` 起写入必须指定分区）。
     *
     * <p>用 {@code zzvecprobe} 前缀而不是复用真实的 {@code kb1_g1}：真实分区里可能有
     * 真实文档的向量，测试的清理会把它们一起删掉 —— 取证脚本最怕的就是"跑一遍把库清了"。
     * 也刻意与 `MilvusPartitionProbeTest` 的 {@code zzprobe_} 区分开，两者互不干扰。
     */
    private static final String TEST_PARTITION = "zzvecprobe";

    private static MilvusClientV2 client;

    private static MilvusChunkWriter writer;

    private static MilvusPartitionManager partitions;

    @BeforeAll
    static void setUp() {
        MilvusProperties props = new MilvusProperties();
        client = new MilvusClientV2(ConnectConfig.builder()
                .uri("http://" + props.getHost() + ":" + props.getPort())
                .connectTimeoutMs(props.getConnectTimeoutMs())
                .rpcDeadlineMs(props.getRpcDeadlineMs())
                .build());
        partitions = new MilvusPartitionManager(client, props);
        writer = new MilvusChunkWriter(client, partitions, props);
        // 收尾用的删除需要分区**先存在**（删一个不存在的分区会报 partition not found）——
        // 这与"写入自带 ensure"不矛盾：清理路径不该为了删而**创建**一个分区。
        // 「写入一张白纸分区也能成功」由 `writingIntoAFreshPartitionJustWorks` 单独钉。
        partitions.ensure(TEST_PARTITION);
        writer.deleteByDocId(TEST_DOC_ID, TEST_PARTITION);
    }

    @AfterAll
    static void tearDown() {
        if (writer != null && client != null) {
            // 清文档 + 丢分区（分区留着就是幽灵分区，且下次 ensure 到它里面还有脏数据）
            writer.deleteByDocId(TEST_DOC_ID, TEST_PARTITION);
            partitions.dropQuietly(TEST_PARTITION);
            client.close();
        }
    }

    @Test
    @DisplayName("G4-10：集合字段集恰好 10 列（载荷 9 + BM25 稀疏 1），多一列都不行")
    void collectionSchemaIsExactlyTheContract() {
        List<String> names = client.describeCollection(DescribeCollectionReq.builder()
                        .collectionName(new MilvusProperties().getCollection())
                        .build())
                .getFieldNames();

        // 本集合 enableDynamicField=false —— Milvus 会**直接拒绝**写未声明的字段，
        // 所以"字段集就是契约"这件事在写入侧是构造性成立的；这里钉的是**声明面**。
        // 少一列 = 有功能悄悄没了承载；多一列 = 有人绕过契约加了"标签类字段"（I-9 禁的正是它）。
        assertThat(names).containsExactlyInAnyOrder(
                MilvusConstants.FIELD_ID, MilvusConstants.FIELD_VECTOR,
                MilvusConstants.FIELD_KB_ID, MilvusConstants.FIELD_DOC_ID,
                MilvusConstants.FIELD_CHUNK_TYPE, MilvusConstants.FIELD_SECRET_LEVEL,
                MilvusConstants.FIELD_CONTENT, MilvusConstants.FIELD_TITLE_PATH,
                MilvusConstants.FIELD_BM25_TEXT, MilvusConstants.FIELD_SPARSE);
    }

    @Test
    @DisplayName("G4-10：向量真进 Milvus，且 BM25 输入列被真的写上了内容")
    void vectorsAreWrittenWithBm25InputPopulated() {
        writer.replaceDocument(TEST_PARTITION, TEST_DOC_ID, List.of(
                record(1, "PARAGRAPH", "正文一", "1. 总则", null),
                record(2, "TABLE", "型号 A-100，单价 ￥1,200", "2. 报价", 2L),
                record(3, "SUMMARY", "本文是……", null, null)));

        QueryResp written = query(TEST_PARTITION, List.of(
                MilvusConstants.FIELD_ID, MilvusConstants.FIELD_CONTENT, MilvusConstants.FIELD_BM25_TEXT));

        assertThat(written.getQueryResults()).hasSize(3);

        // ⚠️ 这条断言是本次迁移新加的，防的是一种**静默空转**：
        //    列建出来了、写入没填 → BM25 一路永远返回空结果，而**不会报任何错**。
        //    所以"列存在"必须与"列里有东西"分开验。
        for (QueryResp.QueryResult row : written.getQueryResults()) {
            String bm25Text = String.valueOf(row.getEntity().get(MilvusConstants.FIELD_BM25_TEXT));
            assertThat(bm25Text)
                    .as("bm25_text 不能为空 —— 空了 BM25 就是静默空转")
                    .isNotBlank();
        }

        // 表格那一行里两个标识符必须**各自整体成 token**。
        // 断言的是「切出来的 token 列表里有没有它」，而不是「整串里含不含这个子串」——
        // 后者对 `A-1000` 也成立，正是这一步最容易写错的地方（`G4-8` 运行时侧的最小证据；
        // 完整的检索级取证在 IdentifierRetrievalFlowTest）。
        List<String> tableTokens = written.getQueryResults().stream()
                .map(r -> String.valueOf(r.getEntity().get(MilvusConstants.FIELD_BM25_TEXT)))
                .filter(t -> t.contains("A-100"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没找到含 A-100 的那一行：BM25 文本可能被切碎了"))
                .lines()
                .flatMap(line -> java.util.Arrays.stream(line.split(" ")))
                .toList();
        assertThat(tableTokens)
                .as("标识符必须整体成 token（被切成 a/100 就会让 A-1000 也命中）")
                .contains("A-100", "￥1,200")
                .doesNotContain("100");
    }

    @Test
    @DisplayName("幂等：同一文档写两遍，条数不变（先删后插）")
    void rewriteIsIdempotent() {
        List<VectorRecord> records = List.of(
                record(11, "PARAGRAPH", "第一版", null, null),
                record(12, "PARAGRAPH", "第二版", null, null));

        writer.replaceDocument(TEST_PARTITION, TEST_DOC_ID, records);
        writer.replaceDocument(TEST_PARTITION, TEST_DOC_ID, records);

        assertThat(rowCount(queryByDocId())).isEqualTo(2);
    }

    @Test
    @DisplayName("重写时旧条被清掉：块少了不会留下幽灵向量")
    void shrinkRemovesStaleVectors() {
        writer.replaceDocument(TEST_PARTITION, TEST_DOC_ID, List.of(
                record(21, "PARAGRAPH", "a", null, null),
                record(22, "PARAGRAPH", "b", null, null),
                record(23, "PARAGRAPH", "c", null, null)));
        assertThat(rowCount(queryByDocId())).isEqualTo(3);

        // 模拟重解析后只剩 2 块：旧的第 3 条必须消失
        writer.replaceDocument(TEST_PARTITION, TEST_DOC_ID, List.of(
                record(31, "PARAGRAPH", "a", null, null),
                record(32, "PARAGRAPH", "b", null, null)));

        QueryResp hits = queryByDocId();
        assertThat(rowCount(hits)).isEqualTo(2);
        assertThat(ids(hits)).containsExactlyInAnyOrder("31", "32");
    }

    @Test
    @DisplayName("空列表 = 只删不插（清空该文档的向量）")
    void emptyListDeletesOnly() {
        writer.replaceDocument(TEST_PARTITION, TEST_DOC_ID, List.of(record(41, "PARAGRAPH", "x", null, null)));
        assertThat(rowCount(queryByDocId())).isEqualTo(1);

        writer.replaceDocument(TEST_PARTITION, TEST_DOC_ID, List.of());
        assertThat(rowCount(queryByDocId())).isZero();
    }

    @Test
    @DisplayName("回归：写进一个**从未建过**的分区也能成功 —— replaceDocument 自带 ensure")
    void writingIntoAFreshPartitionJustWorks() {
        // 2026-09-17 页面级 E2E 逮到过一次：按代分区刚上线时，日常解析路径没人调 ensure，
        // 于是每篇文档解析到向量化那一步就抛，终态「解析失败」——而块已经落库了，
        // 现象是"内容都出来了却说失败"，极难反推。所以这条回归必须钉住。
        String fresh = "zzvecprobe_fresh";
        assertThat(partitions.has(fresh)).as("前提：这个分区确实还不存在，否则本用例是空断言").isFalse();
        try {
            writer.replaceDocument(fresh, TEST_DOC_ID, List.of(record(71, "PARAGRAPH", "新分区", null, null)));
            assertThat(rowCount(query(fresh, List.of(MilvusConstants.FIELD_ID))))
                    .as("向量应写进那个刚被自动建出来的分区")
                    .isEqualTo(1);
        } finally {
            writer.deleteByDocId(TEST_DOC_ID, fresh);
            partitions.dropQuietly(fresh);
        }
    }

    @Test
    @DisplayName("维度不符当场失败，且原因可行动（不是把 1024 维的东西硬写进去）")
    void wrongDimensionFailsLoudly() {
        VectorRecord bad = new VectorRecord(
                new VectorChunk(51, "PARAGRAPH", "x", null, "INTERNAL", null),
                TEST_KB_ID, TEST_DOC_ID, new float[512]);

        assertThatThrownBy(() -> writer.replaceDocument(TEST_PARTITION, TEST_DOC_ID, List.of(bad)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("向量维度不符")
                .hasMessageContaining("1024");
    }

    // ==================== 工具 ====================

    private static VectorRecord record(long chunkId, String type, String content,
                                       String titlePath, Long parentId) {
        return new VectorRecord(
                new VectorChunk(chunkId, type, content, titlePath, "INTERNAL", parentId),
                TEST_KB_ID, TEST_DOC_ID, unitVector(chunkId));
    }

    /** 造一个确定性的 1024 维单位向量（内容不重要，本测试只验写入链路）。 */
    private static float[] unitVector(long seed) {
        float[] v = new float[MilvusConstants.DIMENSION];
        v[(int) (seed % MilvusConstants.DIMENSION)] = 1.0f;
        return v;
    }

    private static QueryResp queryByDocId() {
        return query(TEST_PARTITION, List.of(MilvusConstants.FIELD_ID, MilvusConstants.FIELD_DOC_ID,
                MilvusConstants.FIELD_CHUNK_TYPE));
    }

    private static QueryResp query(String partition, List<String> outFields) {
        // STRONG：本测试要读回**刚写进去的**数据，不能受 Bounded 一致性影响。
        // ⚠️ v2 客户端**失败即抛**（不再有"status 非 0 但正常返回"的中间态），
        //    所以这里没有 assertThat(status) 那一层 —— 能走到下一行就是成功了。
        return client.query(QueryReq.builder()
                .collectionName(new MilvusProperties().getCollection())
                .partitionNames(List.of(partition))
                .filter(MilvusConstants.FIELD_DOC_ID + " in [" + TEST_DOC_ID + "]")
                .outputFields(outFields)
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build());
    }

    /** 命中行数（无命中时 v2 返回空列表，不是错误）。 */
    private static int rowCount(QueryResp resp) {
        return resp.getQueryResults() == null ? 0 : resp.getQueryResults().size();
    }

    private static List<String> ids(QueryResp resp) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : entities(resp)) {
            out.add(String.valueOf(row.get(MilvusConstants.FIELD_ID)));
        }
        return out;
    }

    private static Set<String> fieldNames(QueryResp resp) {
        Set<String> out = new java.util.LinkedHashSet<>();
        entities(resp).forEach(row -> out.addAll(row.keySet()));
        return out;
    }

    private static List<Map<String, Object>> entities(QueryResp resp) {
        if (resp.getQueryResults() == null) {
            return List.of();
        }
        return resp.getQueryResults().stream().map(QueryResp.QueryResult::getEntity).toList();
    }
}
