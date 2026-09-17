package com.knowledge.ai.service;

import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.storage.MilvusProperties;
import com.knowledge.ai.pipeline.vectorize.VectorChunk;
import com.knowledge.ai.pipeline.vectorize.VectorRecord;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.QueryResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.collection.FlushParam;
import io.milvus.param.dml.QueryParam;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * `T-508` 的**真库写入**取证：向量真进 Milvus、字段集合规、先删后插幂等。
 *
 * <p>{@code @Tag("network")} —— 不是"联网"字面意思，而是**依赖外部运行中的组件**
 * （milvus-standalone 容器）。仓库的 surefire 不排除它，但它在 Milvus 不可用时会
 * 明确失败而不是静默跳过：写不进去正是本批要防的故障。
 *
 * <h3>为什么用负的 doc_id</h3>
 * <p>真实文档 id 是 MySQL 自增（正数）。用 {@code -999xxx} 这个不可能与真实文档相撞的
 * 区间，既能随时清理，又**不会误删别人的向量** —— 取证脚本最怕的就是"跑一遍把库清了"。
 */
@Tag("network")
class MilvusVectorWriteTest {

    /** 测试专用的 doc_id 区间（负号 + 999 段，与自增的正 id 绝不相撞）。 */
    private static final long TEST_DOC_ID = -999_001L;

    private static final long TEST_KB_ID = 1L;

    private static MilvusServiceClient client;

    private static MilvusChunkWriter writer;

    @BeforeAll
    static void setUp() {
        MilvusProperties props = new MilvusProperties();
        client = new MilvusServiceClient(ConnectParam.newBuilder()
                .withHost(props.getHost())
                .withPort(props.getPort())
                .withConnectTimeout(props.getConnectTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .build());
        writer = new MilvusChunkWriter(client, props);
        writer.deleteByDocId(TEST_DOC_ID, props.getCollection());
    }

    @AfterAll
    static void tearDown() {
        if (writer != null && client != null) {
            writer.deleteByDocId(TEST_DOC_ID, new MilvusProperties().getCollection());
            client.flush(FlushParam.newBuilder()
                    .withCollectionNames(List.of(new MilvusProperties().getCollection()))
                    .build());
            client.close();
        }
    }

    @Test
    @DisplayName("G4-10：向量真进 Milvus，且标量字段集就是契约的那 4+2 列（I-9 无标签字段）")
    void vectorsAreWrittenWithContractFieldsOnly() {
        writer.replaceDocument(TEST_DOC_ID, List.of(
                record(1, "PARAGRAPH", "正文一", "1. 总则", null),
                record(2, "TABLE", "型号 A-100，单价 ￥1,200", "2. 报价", 2L),
                record(3, "SUMMARY", "本文是……", null, null)));

        QueryResults written = queryAllFields();

        assertThat(rowCount(written)).isEqualTo(3);
        // 字段集：契约要求的 8 列，**多一列都不行**（多出来的必然是没定义过的标签类字段）
        assertThat(fieldNames(written)).containsExactlyInAnyOrder(
                MilvusConstants.FIELD_ID, MilvusConstants.FIELD_VECTOR,
                MilvusConstants.FIELD_KB_ID, MilvusConstants.FIELD_DOC_ID,
                MilvusConstants.FIELD_CHUNK_TYPE, MilvusConstants.FIELD_SECRET_LEVEL,
                MilvusConstants.FIELD_CONTENT, MilvusConstants.FIELD_TITLE_PATH);
    }

    @Test
    @DisplayName("幂等：同一文档写两遍，条数不变（先删后插）")
    void rewriteIsIdempotent() {
        List<VectorRecord> records = List.of(
                record(11, "PARAGRAPH", "第一版", null, null),
                record(12, "PARAGRAPH", "第二版", null, null));

        writer.replaceDocument(TEST_DOC_ID, records);
        writer.replaceDocument(TEST_DOC_ID, records);

        assertThat(rowCount(queryByDocId())).isEqualTo(2);
    }

    @Test
    @DisplayName("重写时旧条被清掉：块少了不会留下幽灵向量")
    void shrinkRemovesStaleVectors() {
        writer.replaceDocument(TEST_DOC_ID, List.of(
                record(21, "PARAGRAPH", "a", null, null),
                record(22, "PARAGRAPH", "b", null, null),
                record(23, "PARAGRAPH", "c", null, null)));
        assertThat(rowCount(queryByDocId())).isEqualTo(3);

        // 模拟重解析后只剩 2 块：旧的第 3 条必须消失
        writer.replaceDocument(TEST_DOC_ID, List.of(
                record(31, "PARAGRAPH", "a", null, null),
                record(32, "PARAGRAPH", "b", null, null)));

        QueryResults hits = queryByDocId();
        assertThat(rowCount(hits)).isEqualTo(2);
        assertThat(ids(hits)).containsExactlyInAnyOrder("31", "32");
    }

    @Test
    @DisplayName("空列表 = 只删不插（清空该文档的向量）")
    void emptyListDeletesOnly() {
        writer.replaceDocument(TEST_DOC_ID, List.of(record(41, "PARAGRAPH", "x", null, null)));
        assertThat(rowCount(queryByDocId())).isEqualTo(1);

        writer.replaceDocument(TEST_DOC_ID, List.of());
        assertThat(rowCount(queryByDocId())).isZero();
    }

    @Test
    @DisplayName("维度不符当场失败，且原因可行动（不是把 1024 维的东西硬写进去）")
    void wrongDimensionFailsLoudly() {
        VectorRecord bad = new VectorRecord(
                new VectorChunk(51, "PARAGRAPH", "x", null, "INTERNAL", null),
                TEST_KB_ID, TEST_DOC_ID, new float[512]);

        assertThatThrownBy(() -> writer.replaceDocument(TEST_DOC_ID, List.of(bad)))
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

    private static QueryResults queryByDocId() {
        return query(List.of(MilvusConstants.FIELD_ID, MilvusConstants.FIELD_DOC_ID,
                MilvusConstants.FIELD_CHUNK_TYPE));
    }

    /** 取回该测试文档的全部字段（用于断言"字段集没多出别的东西"）。 */
    private static QueryResults queryAllFields() {
        return query(List.of("*"));
    }

    private static QueryResults query(List<String> outFields) {
        // STRONG：本测试要读回**刚写进去的**数据，不能受 Bounded 一致性影响
        R<QueryResults> result = client.query(QueryParam.newBuilder()
                .withCollectionName(new MilvusProperties().getCollection())
                .withExpr(MilvusConstants.FIELD_DOC_ID + " in [" + TEST_DOC_ID + "]")
                .withOutFields(outFields)
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .build());
        assertThat(result.getStatus()).isEqualTo(R.Status.Success.getCode());
        return result.getData();
    }

    /** 命中行数 = 主键列的数据条数（无命中时 Milvus 返回空字段列表，不是错误）。 */
    private static int rowCount(QueryResults results) {
        return results.getFieldsDataList().stream()
                .filter(f -> MilvusConstants.FIELD_ID.equals(f.getFieldName()))
                .mapToInt(f -> f.getScalars().getStringData().getDataCount())
                .findFirst().orElse(0);
    }

    private static List<String> ids(QueryResults results) {
        return results.getFieldsDataList().stream()
                .filter(f -> MilvusConstants.FIELD_ID.equals(f.getFieldName()))
                .flatMap(f -> f.getScalars().getStringData().getDataList().stream())
                .toList();
    }

    private static Set<String> fieldNames(QueryResults results) {
        return results.getFieldsDataList().stream()
                .map(f -> f.getFieldName())
                .collect(Collectors.toSet());
    }
}
