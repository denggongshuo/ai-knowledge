package com.knowledge.ai.pipeline.retrieve;

import com.knowledge.ai.core.enums.BlockType;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HyQ 回落（`T-507` 的挂起半边）—— 命中假设问句时，把它换成**源块正文**。
 *
 * <h3>它回答的是 `汇总.md` §4-271 那个挂了很久的问题</h3>
 * <p>那条判据的原话是：「`D-5` 没给 HyQ 块任何回指源块的字段，而 `G4-6` 的 SQL 断言
 * 要求『凡有 `parent_id` 的块，父块必须是 `TABLE`』——『挂到源块下面』这条最自然的路
 * 是封死的。**命中一个 HyQ 块之后拿什么给大模型，只能在有检索实现时回答**」。
 *
 * <p>答案就是本类：`sys_chunk.source_chunk_id`（`V24`）指向生成这条问句的那个块，
 * 命中 HyQ 时按它把内容**换成源块正文**。问句只用于召回，不用于回答。
 *
 * <h3>为什么挂在"回表"这一步之后</h3>
 * <p>与 `T-705` 的表格回表是**同一类动作**：把"检索到的那一条"换成"该喂给模型的那一份"，
 * 且都在精排与裁剪**之后**（裁掉的分片没必要为它查一次库）。
 * 两者只看内容、不改名次与分数，所以先后顺序不影响结果。
 *
 * <h3>悬空必须降级，不能抛</h3>
 * <p>源块被重解析换掉后，`source_chunk_id` 可能指向一个已经不存在的行。
 * 这时**保留 HyQ 自身的文本**并留痕 —— 理由是 `S4-D` 那条已冻结的口径：
 * 查一个不存在的分区是**报错**而不是返回空，所以检索侧"绝不能指向一个可能不存在的东西"。
 * 在这里抛异常 = 整个检索请求炸掉；而少一条来源最多是这一条引用答得不完整。
 * **代价差着一个量级，所以选降级。**
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HyqSourceService {

    private final EntityManager entityManager;

    /**
     * 把结果里所有 HyQ 分片的内容换成源块正文。
     *
     * <p>没有 HyQ 命中时**原样返回入参那个 List 实例**（不是新列表）——
     * 绝大多数请求走这条路，没必要为"什么也没干"造一个副本。
     */
    @Transactional(readOnly = true)
    public List<RetrievedChunk> apply(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return chunks;
        }
        Set<Long> hyqIds = new LinkedHashSet<>();
        for (RetrievedChunk chunk : chunks) {
            if (isHyq(chunk)) {
                hyqIds.add(numericId(chunk.chunkId()));
            }
        }
        hyqIds.remove(null);
        if (hyqIds.isEmpty()) {
            return chunks;
        }

        Map<Long, String> sourceByHyq = loadSources(hyqIds);

        List<RetrievedChunk> out = new ArrayList<>(chunks.size());
        int replaced = 0;
        for (RetrievedChunk chunk : chunks) {
            if (!isHyq(chunk)) {
                out.add(chunk);
                continue;
            }
            String source = sourceByHyq.get(numericId(chunk.chunkId()));
            if (source == null || source.isBlank()) {
                // 悬空（源块被重解析换掉）或源块内容为空 —— 保留问句文本，留痕不抛
                log.warn("[检索] HyQ 分片没有可用的源块正文，降级为只喂假设问句："
                                + "chunkId={} docId={}（不影响本次检索的其它结果）",
                        chunk.chunkId(), chunk.docId());
                out.add(chunk);
                continue;
            }
            out.add(chunk.withHyqSource(source));
            replaced++;
        }
        log.info("[检索] HyQ 回落：命中 {} 条，其中 {} 条换成了源块正文", hyqIds.size(), replaced);
        return List.copyOf(out);
    }

    /**
     * 批量取 HyQ 分片对应源块的正文（一次查询，不逐条查 —— 那是 N+1）。
     *
     * <p>用 `left join` 而不是 `join`：悬空的行要**留在结果里但值为 null**，
     * 这样调用方能区分"这条 HyQ 查过了、源块没了"与"这条根本没查"。
     * 直接 `join` 会让悬空的行从结果里消失，调用方只能看到"没有键"，
     * 于是"降级"与"漏查"长得一模一样。
     */
    private Map<Long, String> loadSources(Set<Long> hyqIds) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select h.id, s.raw_content
                        from sys_chunk h
                        left join sys_chunk s on s.id = h.source_chunk_id
                        where h.id in (:ids)
                        """)
                .setParameter("ids", hyqIds)
                .getResultList();
        Map<Long, String> sources = new HashMap<>(rows.size());
        for (Object[] row : rows) {
            sources.put(((Number) row[0]).longValue(),
                    row[1] == null ? null : String.valueOf(row[1]));
        }
        return sources;
    }

    private static boolean isHyq(RetrievedChunk chunk) {
        return BlockType.HYPOTHESIS_QA.name().equals(chunk.blockType());
    }

    /**
     * Milvus 主键 → {@code sys_chunk.id}。
     *
     * <p>非数字主键返回 {@code null} 并由调用方过滤 —— 与
     * {@code RetrievalTraceWriter} / {@code ChatWireMapper} / {@code TableLookupService}
     * 同一条口径：**数不出来就不给，不猜**。本仓已有四处独立实现这条规则，
     * 都是因为"猜一个 id"会把明细错挂到别的分片上。
     */
    private static Long numericId(String chunkId) {
        try {
            return Long.valueOf(chunkId);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}