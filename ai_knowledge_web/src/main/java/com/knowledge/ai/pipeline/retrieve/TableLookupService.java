package com.knowledge.ai.pipeline.retrieve;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowledge.ai.core.enums.KbConfigDefaults;
import com.knowledge.ai.dto.kb.KbDtos;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 表格回表的**取数**那一半（`T-705`）—— 从 MySQL 把父块与行号查出来。
 *
 * <h3>为什么这一次取数值得单独一个类</h3>
 * <p>检索结果里最多十几条分片，但其中每一条表格子块都要往上走一层：拿 {@code parent_id}
 * 取父块内容、还要知道自己是父块的第几行。**逐条查就是 N+1**，而检索在问答热路径上。
 * 所以这里一条 SQL 把三件事一次问回来：父块内容、该父块共几个子块、命中的是第几个。
 *
 * <h3>数不出来就不给，不猜</h3>
 * <p>父块行不存在（文档正在被重解析 —— `sys_chunk` 是先删后插的）时 {@code join} 不命中，
 * 这条分片就**原样返回**：它的内容还是一行文本，用户看到的仍是命中的那一行，
 * 只是没有整表上下文。这比"抛异常让整次问答失败"和"拼一个空表"都好 ——
 * 回表是**增强**，不该成为新的失败点（与 `RetrievalScope` 对 Milvus 的分区缺失同一条口径）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TableLookupService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 给命中的表格子块补上所在的表。
     *
     * @param chunks 精排后的结果（**只读**，返回的是新列表）
     * @return 顺序、条数、分数、名次全部不变；只有表格子块的内容与 {@code tableContext} 变了
     */
    @Transactional(readOnly = true)
    public List<RetrievedChunk> apply(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return chunks;
        }
        List<Long> ids = numericIds(chunks);
        if (ids.isEmpty()) {
            return chunks;
        }

        Map<String, TableLookup.TableFact> facts = loadFacts(ids);
        if (facts.isEmpty()) {
            // 本次结果里没有表格子块 —— 绝大多数检索都走这条路，值得一个 early return
            return chunks;
        }

        List<Long> kbIds = chunks.stream().map(RetrievedChunk::kbId).distinct().toList();
        Map<Long, TableLookup.TableParams> byKb = loadParams(kbIds);
        TableLookup.TableParams fallback = new TableLookup.TableParams(
                KbConfigDefaults.ROW_LIMIT, KbConfigDefaults.ROW_WINDOW);

        List<RetrievedChunk> looked = TableLookup.apply(chunks, facts, byKb, fallback);
        log.info("[回表] 命中分片={} 其中表格子块={}（已补上表格上下文）", chunks.size(), facts.size());
        return looked;
    }

    // ==================== 取数 ====================

    /**
     * 一次问回：父块内容 + 该父块的子块总数 + 本子块是第几个。
     *
     * <p>「第几个」用 {@code count(*) where order_no <= 本行} 数出来，而不是把兄弟全部取回来
     * 在内存里 {@code indexOf} —— 后者要传回整张表的所有行，而本方法只想知道一个序号。
     * 排序依据是 {@code order_no}，与 {@link com.knowledge.ai.pipeline.ChunkingService}
     * 分配序号的顺序一致（子块按数据行顺序产出）。
     *
     * <p>内连接 {@code join sys_chunk p on p.id = c.parent_id} 顺带把
     * 「{@code parent_id} 为 null 的顶层块」排除掉了 —— 它们不在结果里，正是我们要的。
     */
    private Map<String, TableLookup.TableFact> loadFacts(List<Long> chunkIds) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select c.id, c.parent_id, p.raw_content,
                               (select count(*) from sys_chunk s
                                 where s.parent_id = c.parent_id) as child_count,
                               (select count(*) from sys_chunk s2
                                 where s2.parent_id = c.parent_id
                                   and s2.order_no <= c.order_no) as matched_row
                          from sys_chunk c
                          join sys_chunk p on p.id = c.parent_id
                         where c.id in (:ids)
                           and c.chunk_type = 'TABLE'
                        """)
                .setParameter("ids", chunkIds)
                .getResultList();

        Map<String, TableLookup.TableFact> facts = new HashMap<>(rows.size());
        for (Object[] row : rows) {
            String chunkId = String.valueOf(((Number) row[0]).longValue());
            facts.put(chunkId, new TableLookup.TableFact(
                    ((Number) row[1]).longValue(),
                    (String) row[2],
                    ((Number) row[4]).intValue(),
                    ((Number) row[3]).intValue()));
        }
        return facts;
    }

    /**
     * 各库的窗口参数（{@code sys_kb.table_policy_json}）。
     *
     * <p>解析失败**不抛**：一行历史配置写坏了不该让整个库搜不出东西 ——
     * 与 {@code RetrievalService#parseConfig} 同一条口径（退化为默认值并留 warn）。
     * 该列可空（`V9` 的注释：「NULL 读侧用默认值兜底」），所以缺行与 NULL 都走默认值。
     */
    private Map<Long, TableLookup.TableParams> loadParams(List<Long> kbIds) {
        if (kbIds.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select id, table_policy_json from sys_kb where id in (:ids)")
                .setParameter("ids", kbIds)
                .getResultList();

        Map<Long, TableLookup.TableParams> byKb = new HashMap<>(rows.size());
        for (Object[] row : rows) {
            long kbId = ((Number) row[0]).longValue();
            KbDtos.TablePolicy policy = parsePolicy((String) row[1], kbId);
            if (policy != null) {
                byKb.put(kbId, new TableLookup.TableParams(policy.rowLimit(), policy.rowWindow()));
            }
        }
        return byKb;
    }

    private static KbDtos.TablePolicy parsePolicy(String json, long kbId) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, KbDtos.TablePolicy.class);
        } catch (Exception ex) {
            log.warn("[回表] 库级表格策略解析失败，本次使用默认窗口：kbId={} err={}", kbId, ex.getMessage());
            return null;
        }
    }

    /**
     * 把 Milvus 主键（字符串）转成 {@code sys_chunk.id} 用的数字。
     *
     * <p>主键在两侧同值（`MilvusConstants` 建表注释），但类型不同：Milvus 返回字符串。
     * 直接拿字符串去 {@code in} 一个 BIGINT 列，MySQL 会做隐式转换 ——
     * 那样既用不上索引，也会让一个本该"少几条"的坏值变成语法层面的意外。
     * 解析不出来的一律跳过并留痕（主键坏了是**大事**，不能静默）。
     */
    private static List<Long> numericIds(List<RetrievedChunk> chunks) {
        List<Long> ids = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            try {
                ids.add(Long.parseLong(chunk.chunkId()));
            } catch (NumberFormatException ex) {
                log.warn("[回表] 跳过非数字主键：chunkId={} —— 它与 sys_chunk.id 不同源，无法回表",
                        chunk.chunkId());
            }
        }
        return ids;
    }
}
