package com.knowledge.ai.pipeline.retrieve;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HyQ 回落取证（`T-507` 的挂起半边）—— **销 `汇总.md` §4-271 那条挂起判据**。
 *
 * <h3>这条判据在问什么</h3>
 * <p>「命中一个 HyQ 块之后拿什么给大模型，只能在有检索实现时回答」。
 * 检索实现（`S5-M07`）已经在了，所以本类给出答案并把它钉住：
 * **喂源块正文**，不是那条问句。
 *
 * <h3>为什么两条用例缺一不可</h3>
 * <p>① 正常路径证明"能取回源文"；② **悬空路径**证明"源块没了时不炸"。
 * 只测①的话，一个 `join` 写成 `inner join` 的实现照样全绿 ——
 * 而它在源块被重解析换掉的那天会让**整个检索请求 500**。
 * `S4-D` 已经为同类事付过一次代价（查不存在的分区是报错不是返回空）。
 */
@SpringBootTest
class HyqSourceFlowTest {

    @Autowired
    private HyqSourceService hyqSourceService;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> probeDocIds = new ArrayList<>();

    @AfterEach
    void cleanProbeData() {
        for (Long docId : probeDocIds) {
            jdbc.update("delete from sys_chunk where doc_id = ?", docId);
            jdbc.update("delete from sys_document where id = ?", docId);
        }
        probeDocIds.clear();
    }

    @Test
    @DisplayName("判据14（汇总 §4-271）：命中 HyQ 时分片内容被换成**源块正文**，问句只用于召回")
    void hyqHitFeedsSourceBlockNotTheQuestion() {
        long docId = insertProbeDoc();
        long sourceId = insertChunk(docId, "PARAGRAPH", "接口鉴权采用令牌方式，令牌有效期 2 小时。", null);
        long hyqId = insertChunk(docId, "HYPOTHESIS_QA", "令牌有效期是多久？\n鉴权用的是什么方式？", sourceId);

        List<RetrievedChunk> resolved = hyqSourceService.apply(List.of(hyq(hyqId, docId)));

        assertThat(resolved).hasSize(1);
        assertThat(resolved.getFirst().content())
                .as("喂给大模型的必须是**源块正文** —— 把问句原样喂过去，模型只能看到一个孤零零的提问")
                .contains("令牌有效期 2 小时");
        assertThat(resolved.getFirst().content())
                .as("召回用的问句不该出现在喂给模型的正文里")
                .doesNotContain("令牌有效期是多久？");
        assertThat(resolved.getFirst().chunkId())
                .as("⚠️ chunkId 不动 —— 引用角标与打点明细指的仍是**真正被检索到的那条分片**，"
                        + "换掉的只是内容")
                .isEqualTo(String.valueOf(hyqId));
    }

    @Test
    @DisplayName("判据15：源块悬空时**降级不抛异常**（保留问句并留痕），检索照常返回")
    void danglingSourceFallsBackWithoutThrowing() {
        long docId = insertProbeDoc();
        // 指向一个不存在的源块 —— 源块被重解析换掉后就是这个形状
        long hyqId = insertChunk(docId, "HYPOTHESIS_QA", "报价单里 A-100 多少钱？", 999_999_999L);
        RetrievedChunk hit = hyq(hyqId, docId);

        List<RetrievedChunk> resolved = hyqSourceService.apply(List.of(hit));

        assertThat(resolved)
                .as("绝不能抛 —— 少一条来源最多是这一条引用答得不完整，"
                        + "而抛异常会让**整个检索请求**炸掉，代价差一个量级")
                .hasSize(1);
        assertThat(resolved.getFirst().content())
                .as("降级 = **保留分片原有内容**（那条问句），而不是把它清空 —— "
                        + "清空会让这条引用在提示词里变成一个空编号，"
                        + "模型看到的是一段无来由的空白")
                .isEqualTo(hit.content());
    }

    @Test
    @DisplayName("普通分片**原样穿过** —— 回落不该碰任何非 HyQ 的内容")
    void ordinaryChunksPassThroughUntouched() {
        long docId = insertProbeDoc();
        long plainId = insertChunk(docId, "PARAGRAPH", "一段普通正文。", null);

        RetrievedChunk plain = hyq(plainId, docId).withRank(1);
        RetrievedChunk ordinary = new RetrievedChunk(String.valueOf(plainId), docId, 8L, "PARAGRAPH",
                "1. 背景", "一段普通正文。", 0.9, RetrievedChunk.ScoreKind.RERANK,
                1, 1, 0.9, 0.9, Map.of(), false, null);

        List<RetrievedChunk> resolved = hyqSourceService.apply(List.of(ordinary));

        assertThat(resolved).hasSize(1);
        assertThat(resolved.getFirst().content()).isEqualTo("一段普通正文。");
        assertThat(resolved).containsExactly(ordinary);
        assertThat(plain).isNotNull();
    }

    // ==================================================================

    private RetrievedChunk hyq(long chunkId, long docId) {
        return new RetrievedChunk(String.valueOf(chunkId), docId, 8L, "HYPOTHESIS_QA",
                "3. 接口设计", "令牌有效期是多久？", 0.9, RetrievedChunk.ScoreKind.RERANK,
                1, 1, 0.9, 0.9, Map.of(), false, null);
    }

    private long insertProbeDoc() {
        String md5 = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5,
                                          status, chunk_count, table_total, table_parsed,
                                          image_total, image_parsed, created_at, updated_at)
                values (8, 1, 'HyQ 回落探针', 'probe.md', 'MD', 16, ?,
                        'READY', 0, 0, 0, 0, 0, now(), now())
                """, md5);
        Long id = jdbc.queryForObject("select id from sys_document where md5 = ?", Long.class, md5);
        probeDocIds.add(id);
        return id;
    }

    private long insertChunk(long docId, String type, String content, Long sourceChunkId) {
        jdbc.update("""
                        insert into sys_chunk (doc_id, kb_id, chunk_type, parent_id, source_chunk_id,
                                               title_path, raw_content, order_no, page_no, created_at)
                        values (?, 8, ?, null, ?, '3. 接口设计', ?, ?, 1, now())
                        """,
                docId, type, sourceChunkId, content, nextOrder(docId));
        return jdbc.queryForObject("select max(id) from sys_chunk where doc_id = ?", Long.class, docId);
    }

    private int nextOrder(long docId) {
        Integer n = jdbc.queryForObject("select count(*) from sys_chunk where doc_id = ?",
                Integer.class, docId);
        return (n == null ? 0 : n) + 1;
    }
}