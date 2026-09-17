package com.knowledge.ai.service;

import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.storage.MilvusProperties;
import com.knowledge.ai.pipeline.vectorize.VectorChunk;
import com.knowledge.ai.pipeline.vectorize.VectorRecord;
import com.knowledge.ai.pipeline.vectorize.VectorWriter;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Milvus 写入（`T-508`）—— 分片向量落库。
 *
 * <h3>为什么不用 Spring AI 的 {@code MilvusVectorStore}</h3>
 * <p>步 0.5 探针（2026-09-17）实测其 Builder 只提供**单列 metadata 映射**
 * （{@code metadataFieldName}），无法把 {@code kb_id}/{@code doc_id}/{@code chunk_type}/
 * {@code secret_level} 表达成**四个各自带 INVERTED 索引的标量列** —— 而那正是
 * D-4/R-8 的过滤设计、`I-9`（先算 doc_id 集合再作标量过滤）的前提；
 * 且本集合 {@code enableDynamicField=false}，写未声明字段会被 Milvus 直接拒绝。
 * 结论与理由写在 {@code config/MilvusConfig} 的类 javadoc 里。
 *
 * <h3>幂等</h3>
 * <p>{@link #replaceDocument(long, List)} **先删后插**，与 {@code DocRecordWriter#replaceChunks}
 * 的"整批重写"同形：重跑解析/向量化不会留下"上一次的残留向量"——
 * 那种向量检索得到、却不再属于这篇文档，是最难排查的一类脏数据。
 *
 * <h3>⚠️ 本类刻意不 flush（2026-09-17 实测教训，写在这里免得下一个人"顺手补上"）</h3>
 * <p>Milvus 的写入进的是 **growing segment**：**不 flush 也能被 `query`/`search` 看到**
 * （STRONG 一致性保证读己之写）。flush 影响的是**持久化与 compaction**，不是可检索性，
 * 而 Milvus 自己有自动 flush。
 *
 * <p>反过来，flush 有**速率限制**：实测报
 * {@code request is rejected by grpc RateLimiter middleware: rate limit exceeded[rate=0.1]}
 * —— 即 **每 10 秒只允许一次**。原先每次写入后 flush，5 条用例的测试跑了 **68 秒**
 * （SDK 内部重试把失败掩盖成"慢"而不是报错 —— 最难查的那种形态）；去掉后 **6.7 秒**。
 *
 * <p>与之配套的一条口径：<b>`get_stats` 的 `rowCount` 只统计已 flush 的数据</b>，
 * 所以它**不能**用来判"向量写进去了没有"（真跑时 `rowCount=0` 而实查 81 条）。
 * 取证一律用 {@code entities/query} 的 `count(*)` —— 那才是"能不能被检索到"的口径。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusChunkWriter implements VectorWriter {

    /** Milvus VarChar 上限（与建表时一致）。 */
    private static final int MAX_VARCHAR = 65_535;

    /** {@code title_path} 列的上限（建表时声明 1024）。 */
    private static final int MAX_TITLE_PATH = 1_024;

    private final MilvusServiceClient client;

    private final MilvusProperties properties;

    /**
     * 用给定向量**整篇替换**某文档在 Milvus 里的全部条目。
     *
     * @param docId   文档 id（删除表达式按它走）
     * @param records 每个元素 = 一个块 + 它的向量（顺序无关，主键唯一）
     * @return 实际写入的条数
     */
    @Override
    public int replaceDocument(long docId, List<VectorRecord> records) {
        String collection = properties.getCollection();
        deleteByDocId(docId, collection);
        if (records.isEmpty()) {
            log.info("[Milvus] 该文档没有需要写入的块（全部被跳过）：docId={}", docId);
            return 0;
        }
        insert(records, collection);
        return records.size();
    }

    /** 删掉某文档的全部向量。先删后插的第一步；也是"删除文档"将来要复用的入口。 */
    public void deleteByDocId(long docId, String collection) {
        // doc_id 是 long，直接拼进表达式不存在注入面（不经用户输入）
        String expr = MilvusConstants.FIELD_DOC_ID + " in [" + docId + "]";
        R<MutationResult> result = client.delete(DeleteParam.newBuilder()
                .withCollectionName(collection)
                .withExpr(expr)
                .build());
        assertOk(result, "delete(" + expr + ")");
        long deleted = result.getData() == null ? 0 : result.getData().getDeleteCnt();
        if (deleted > 0) {
            log.info("[Milvus] 清除旧的文档向量：docId={} 条数={}", docId, deleted);
        }
    }

    private void insert(List<VectorRecord> records, String collection) {
        List<String> ids = new ArrayList<>(records.size());
        // Int64 字段要 Java 的 Long（Integer 会被 Milvus 拒绝：实测 "field value type must be Long"）
        List<Long> ownerKbIds = new ArrayList<>(records.size());
        List<Long> docIds = new ArrayList<>(records.size());
        List<String> chunkTypes = new ArrayList<>(records.size());
        List<String> secretLevels = new ArrayList<>(records.size());
        List<String> contents = new ArrayList<>(records.size());
        List<String> titlePaths = new ArrayList<>(records.size());
        List<List<Float>> vectors = new ArrayList<>(records.size());

        for (VectorRecord r : records) {
            VectorChunk c = r.chunk();
            ids.add(String.valueOf(c.chunkId()));
            ownerKbIds.add(r.kbId());
            docIds.add(r.docId());
            chunkTypes.add(c.blockType());
            // Milvus 这几列都未声明 nullable，缺值要用空串而不是 null（否则整批插入被拒）
            secretLevels.add(c.secretLevel() == null ? "" : c.secretLevel());
            titlePaths.add(truncate(c.titlePath(), MAX_TITLE_PATH));
            // content 存**投影后的检索文本**（= 送嵌入的那份），不是 raw_content：
            // M07 的 BM25 必须与向量在**同一个文本空间**里比，否则两路召回结果不可比。
            // 块的原始内容永在 MySQL 的 raw_content 里，事实没有丢。
            contents.add(truncate(c.vectorText(), MAX_VARCHAR));
            vectors.add(asFloatList(r.vector()));
        }

        List<InsertParam.Field> fields = List.of(
                new InsertParam.Field(MilvusConstants.FIELD_ID, ids),
                new InsertParam.Field(MilvusConstants.FIELD_VECTOR, vectors),
                new InsertParam.Field(MilvusConstants.FIELD_KB_ID, ownerKbIds),
                new InsertParam.Field(MilvusConstants.FIELD_DOC_ID, docIds),
                new InsertParam.Field(MilvusConstants.FIELD_CHUNK_TYPE, chunkTypes),
                new InsertParam.Field(MilvusConstants.FIELD_SECRET_LEVEL, secretLevels),
                new InsertParam.Field(MilvusConstants.FIELD_CONTENT, contents),
                new InsertParam.Field(MilvusConstants.FIELD_TITLE_PATH, titlePaths));

        R<MutationResult> result = client.insert(InsertParam.newBuilder()
                .withCollectionName(collection)
                .withFields(fields)
                .build());
        assertOk(result, "insert(" + records.size() + " 条)");

        long inserted = result.getData() == null ? 0 : result.getData().getInsertCnt();
        if (inserted != records.size()) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR,
                    "向量写入条数不符：期望 " + records.size() + " 条，Milvus 报告 " + inserted
                            + " 条 —— 拒绝按「成功了」处理，请重试该文档的解析");
        }
        log.info("[Milvus] 文档向量写入完成：{} 条 → {}", inserted, collection);
    }

    private static List<Float> asFloatList(float[] vector) {
        if (vector == null || vector.length != MilvusConstants.DIMENSION) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR,
                    "向量维度不符：期望 " + MilvusConstants.DIMENSION
                            + "，实际 " + (vector == null ? 0 : vector.length)
                            + " —— 维度锁死是 D-4，写进去就是全库重建的代价");
        }
        List<Float> out = new ArrayList<>(vector.length);
        for (float v : vector) {
            out.add(v);
        }
        return out;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static void assertOk(R<?> result, String what) {
        if (result.getStatus() == null || result.getStatus() != R.Status.Success.getCode()) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR,
                    "向量库操作失败（" + what + "）：" + result.getMessage()
                            + " —— 请确认 milvus-standalone 健康后重试");
        }
    }

}
