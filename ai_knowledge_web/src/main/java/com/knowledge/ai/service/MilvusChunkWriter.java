package com.knowledge.ai.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.storage.MilvusPartitionManager;
import com.knowledge.ai.core.storage.MilvusProperties;
import com.knowledge.ai.core.text.Bm25Text;
import com.knowledge.ai.pipeline.vectorize.VectorChunk;
import com.knowledge.ai.pipeline.vectorize.VectorRecord;
import com.knowledge.ai.pipeline.vectorize.VectorWriter;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.InsertResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Milvus 写入（`T-508`；`S5-M07-A` 迁到 v2 并补 BM25 输入列）。
 *
 * <h3>为什么不用 Spring AI 的 {@code MilvusVectorStore}</h3>
 * <p>步 0.5 探针（2026-09-17）实测其 Builder 只提供**单列 metadata 映射**
 * （{@code metadataFieldName}），无法把 {@code kb_id}/{@code doc_id}/{@code chunk_type}/
 * {@code secret_level} 表达成**四个各自带 INVERTED 索引的标量列** —— 而那正是
 * D-4/R-8 的过滤设计、`I-9`（先算 doc_id 集合再作标量过滤）的前提；
 * 且本集合 {@code enableDynamicField=false}，写未声明字段会被 Milvus 直接拒绝。
 *
 * <h3>幂等</h3>
 * <p>{@link #replaceDocument} **先删后插**，与 {@code DocRecordWriter#replaceChunks}
 * 的"整批重写"同形：重跑解析/向量化不会留下"上一次的残留向量"——
 * 那种向量检索得到、却不再属于这篇文档，是最难排查的一类脏数据。
 *
 * <h3>⚠️ 本类刻意不 flush（2026-09-17 实测教训，写在这里免得下一个人"顺手补上"）</h3>
 * <p>Milvus 的写入进的是 **growing segment**：**不 flush 也能被 `query`/`search` 看到**
 * （STRONG 一致性保证读己之写）。flush 影响的是**持久化与 compaction**，不是可检索性，
 * 而 Milvus 自己有自动 flush。反过来 flush 有**速率限制**（实测每 10 秒一次），
 * 每次写入后 flush 会让每篇文档白等一个窗口。
 *
 * <p>与之配套的一条口径：<b>`get_stats` 的 `rowCount` 只统计已 flush 的数据</b>，
 * 所以它**不能**用来判"向量写进去了没有"（真跑时 `rowCount=0` 而实查 81 条）。
 * 取证一律用 {@code entities/query} 的 {@code count(*)}。
 *
 * <h3>两列文本各司其职（`S5-M07-A` 新增）</h3>
 * <ul>
 *   <li>{@link MilvusConstants#FIELD_CONTENT} —— 投影后的检索文本，**人读 / 回填**用；</li>
 *   <li>{@link MilvusConstants#FIELD_BM25_TEXT} —— 同一段文本经 {@link Bm25Text} 预分词，
 *       **只给 BM25 用**。分两列而不是就地覆盖，是 `O8`「块存事实、视图是投影」的同一条原则。</li>
 * </ul>
 * <p>{@code sparse} 列**不在这里写**：它由集合上的 BM25 函数从 {@code bm25_text} 自动生成，
 * 手动写它反而会与函数打架。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusChunkWriter implements VectorWriter {

    /** Milvus VarChar 上限（与建表时一致）。 */
    private static final int MAX_VARCHAR = 65_535;

    /** {@code title_path} 列的上限（建表时声明 1024）。 */
    private static final int MAX_TITLE_PATH = 1_024;

    /** {@code embedding} 的维度。与 {@link MilvusConstants#DIMENSION} 同源。 */
    private static final int DIMENSION = MilvusConstants.DIMENSION;

    private final MilvusClientV2 client;

    /** 见 {@link #replaceDocument}：写入前必须确保分区存在（不存在就建）。 */
    private final MilvusPartitionManager partitionManager;

    private final MilvusProperties properties;

    /**
     * 用给定向量**整篇替换**某文档在 Milvus 里的全部条目。
     *
     * @param partition 目标分区（`T-406` 的按代切换；日常解析传当前代）
     * @param docId     文档 id（删除表达式按它走）
     * @param records   每个元素 = 一个块 + 它的向量（顺序无关，主键唯一）
     * @return 实际写入的条数
     */
    @Override
    public int replaceDocument(String partition, long docId, List<VectorRecord> records) {
        // ⚠️ 先确保分区存在 —— **这一步不能省，也不能交给调用方去记**。
        // 步 0 探针实测：写进不存在的分区会**显式失败**（不会隐式创建）。2026-09-17 的
        // 页面级 E2E 正是被这一点咬到的：按代分区刚上线时，日常解析路径没人调 ensure，
        // 于是每一篇文档解析到向量化那一步就抛，终态是「解析失败」——
        // 而块已经落库了（chunk_count 有值），现象是"内容都出来了却说失败"，极难反推。
        partitionManager.ensure(partition);
        deleteByDocId(docId, partition);
        if (records.isEmpty()) {
            log.info("[Milvus] 该文档没有需要写入的块（全部被跳过）：docId={} 分区={}", docId, partition);
            return 0;
        }
        insert(records, partition);
        return records.size();
    }

    /**
     * 删掉某文档**在该分区**的全部向量。先删后插的第一步；也是"删除文档"要复用的入口。
     *
     * <p>⚠️ 分区是必填参数，不是可选优化 —— 步 0 探针实测：不指定分区时删的是
     * **整个集合**（含别的库、别的代），那会把别的库的向量一起删掉。
     */
    public void deleteByDocId(long docId, String partition) {
        // doc_id 是 long，直接拼进表达式不存在注入面（不经用户输入）
        String expr = MilvusConstants.FIELD_DOC_ID + " in [" + docId + "]";
        DeleteResp resp = guard("delete(" + expr + " @" + partition + ")", () -> client.delete(
                DeleteReq.builder()
                        .collectionName(properties.getCollection())
                        .partitionName(partition)
                        .filter(expr)
                        .build()));
        long deleted = resp == null ? 0 : resp.getDeleteCnt();
        if (deleted > 0) {
            log.info("[Milvus] 清除旧的文档向量：docId={} 分区={} 条数={}", docId, partition, deleted);
        }
    }

    /**
     * 删掉某文档在**全部分区**的向量（含 `_default`）——「彻底删除文档」用，日常不用它。
     *
     * <p>⚠️ 与 {@link #deleteByDocId} 的差别就是**不带 partitionName**：
     * 一个参数之差，删的范围从"这一代这一篇"变成"整个集合里这一篇"。
     */
    public void deleteByDocIdEverywhere(long docId) {
        String expr = MilvusConstants.FIELD_DOC_ID + " in [" + docId + "]";
        guard("delete(" + expr + ")", () -> client.delete(
                DeleteReq.builder()
                        .collectionName(properties.getCollection())
                        .filter(expr)
                        .build()));
    }

    private void insert(List<VectorRecord> records, String partition) {
        List<JsonObject> rows = new ArrayList<>(records.size());
        int bm25Truncated = 0;
        for (VectorRecord r : records) {
            VectorChunk c = r.chunk();
            // 投影文本只算一次 —— content 与 bm25_text 必须是**同一段文本**的两种视图，
            // 各算一次就有机会算成两份不同的东西（那正是"明明在里面却检索不到"的来源）
            String projection = truncate(c.vectorText(), MAX_VARCHAR);

            Bm25Text.Result bm25 = Bm25Text.of(projection, MAX_VARCHAR);
            if (bm25.truncated()) {
                bm25Truncated++;
            }

            JsonObject row = new JsonObject();
            row.addProperty(MilvusConstants.FIELD_ID, String.valueOf(c.chunkId()));
            row.add(MilvusConstants.FIELD_VECTOR, toJsonArray(r.vector()));
            row.addProperty(MilvusConstants.FIELD_KB_ID, r.kbId());
            row.addProperty(MilvusConstants.FIELD_DOC_ID, r.docId());
            row.addProperty(MilvusConstants.FIELD_CHUNK_TYPE, c.blockType());
            // Milvus 这几列都未声明 nullable，缺值要用空串而不是 null（否则整批插入被拒）
            row.addProperty(MilvusConstants.FIELD_SECRET_LEVEL, c.secretLevel() == null ? "" : c.secretLevel());
            row.addProperty(MilvusConstants.FIELD_TITLE_PATH, truncate(c.titlePath(), MAX_TITLE_PATH));
            row.addProperty(MilvusConstants.FIELD_CONTENT, projection);
            row.addProperty(MilvusConstants.FIELD_BM25_TEXT, bm25.text());
            // 刻意不写 FIELD_SPARSE：它由集合上的 BM25 函数自动生成
            rows.add(row);
        }
        if (bm25Truncated > 0) {
            // 走到这里说明有块的投影文本超过了 Milvus 的单列上限。不静默：
            // 被丢掉的 token 会让那段内容"少被搜到"，但文档本身仍可用
            log.warn("[Milvus] {} 个块的 BM25 文本因超过 {} 字符被按 token 边界截断：docId={}",
                    bm25Truncated, MAX_VARCHAR, records.getFirst().docId());
        }

        InsertResp resp = guard("insert(" + records.size() + " 条 @" + partition + ")", () -> client.insert(
                InsertReq.builder()
                        .collectionName(properties.getCollection())
                        .partitionName(partition)
                        .data(rows)
                        .build()));

        long inserted = resp == null ? 0 : resp.getInsertCnt();
        if (inserted != records.size()) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR,
                    "向量写入条数不符：期望 " + records.size() + " 条，Milvus 报告 " + inserted
                            + " 条 —— 拒绝按「成功了」处理，请重试该文档的解析");
        }
        log.info("[Milvus] 文档向量写入完成：{} 条 → {} @{}", inserted, properties.getCollection(), partition);
    }

    private static JsonArray toJsonArray(float[] vector) {
        if (vector == null || vector.length != DIMENSION) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR,
                    "向量维度不符：期望 " + DIMENSION
                            + "，实际 " + (vector == null ? 0 : vector.length)
                            + " —— 维度锁死是 D-4，写进去就是全库重建的代价");
        }
        JsonArray out = new JsonArray(vector.length);
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

    /**
     * 把 v2 客户端的异常翻译成带**下一步动作**的业务异常（硬口径：失败给「现象 + 下一步」）。
     *
     * <p>v2 客户端失败即抛，不再有"status 非 0 但方法正常返回"的中间态 ——
     * 那种中间态正是当年分区泄漏的根因，故这里**只兜异常**。
     */
    private static <T> T guard(String what, java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (Exception ex) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR,
                    "向量库操作失败（" + what + "）：" + rootMessage(ex)
                            + " —— 请确认 milvus-standalone 健康后重试");
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor == ex ? ex.toString() : cursor.toString();
    }
}
