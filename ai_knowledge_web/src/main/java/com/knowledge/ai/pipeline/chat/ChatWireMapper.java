package com.knowledge.ai.pipeline.chat;

import com.knowledge.ai.pipeline.retrieve.RetrievalResult;
import com.knowledge.ai.pipeline.retrieve.RetrievalTrace;
import com.knowledge.ai.pipeline.retrieve.RetrievedChunk;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后端 {@link RetrievalTrace}（26 字段）→ §9 线格式 {@link ChatDtos.TraceWire}。
 *
 * <p>整批只有这一处做这个转换，映射规则**逐条写死**在 {@link #stages} 与
 * {@link #wire} 里，并由 `10` §14 的映射表登记 —— 两套同名类型并存时，
 * 唯一的防线就是"转换只有一处"。
 *
 * <h3>为什么不把内部 26 字段直接发出去</h3>
 * <p>见 {@link ChatDtos} 的类注释：前端按 7 个数渲染，多发的字段它不认识、
 * 少发的字段它渲染不出来（`10:369` 列为 P0）。
 *
 * <h3>丢不掉的那三个"额外阶段"</h3>
 * <p>§9 的 `stages[]` 列了 6 段，而后端实际测到 **9 段** —— 多出「可见范围 / RRF 融合 /
 * 表格回表」。把它们丢掉会有两个后果：① 详情面板的 6 段之和对不上 `totalMs`
 * （§9 自己写着"`totalMs` ≈ 上述 6 项之和"，那三个不记的阶段也是有耗时的）；
 * ② `T-705` 刚做出来的"表格回表耗时"在界面上看不见。
 * 所以**照实追加**，并把 `stages[]` 的多值登记进 §14。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWireMapper {

    /**
     * 阶段中文名 —— **冻结**。前端 {@code ChatView} 直接渲染这个字符串，不做映射，
     * 所以改文案就等于改界面显示。前 6 个与 `mock/index.ts:249` 逐字一致
     * （前端是照 mock 开发的），后 3 个是本批新增的真实阶段。
     */
    public static final String STAGE_SCOPE = "可见范围";
    public static final String STAGE_QUERY = "查询解析";
    public static final String STAGE_EMBED = "Embedding";
    public static final String STAGE_VECTOR = "向量召回";
    public static final String STAGE_BM25 = "BM25 召回";
    public static final String STAGE_FUSE = "RRF 融合";
    public static final String STAGE_RERANK = "Rerank";
    public static final String STAGE_TABLE = "表格回表";
    public static final String STAGE_LLM = "LLM 生成";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * **首包**（`G5-7`：不带图片时它必须是第一个事件）。
     *
     * <p>此刻生成还没开始，所以 `llmGeneration` 与含生成的总耗时**没有真值**。
     * 本批的选择是：{@code llmGeneration = 0}、{@code totalMs} = **检索**端到端 ——
     * 先让引用角标与面板渲染出来，终包再纠正（见 {@link #finalPacket}）。
     * ⚠️ 不编一个像样的假数字：那是 `mock/index.ts:246` 的做法
     * （它写死 `RETRIEVAL_COST.llmGeneration`），真后端不能照抄。
     */
    public ChatDtos.RetrievalEvent firstPacket(RetrievalResult result, Map<Long, String> docTitles) {
        return wire(result, docTitles, 0L, result.trace().totalMillis());
    }

    /**
     * **终包** —— 在 `done` 之前再发一次，把两个首包拿不到的数换成真值。
     *
     * <p>前端 {@code ChatView.vue:259} 的 {@code onRetrieval} 是**幂等覆盖**
     * （{@code m.trace = data.trace}），所以多发一次**不需要前端改一行代码** ——
     * 这正是选"补发终包"而不是"往 `done` 里加字段"的原因：后者要动已冻结的前端类型。
     *
     * @param llmGenerationMillis 生成耗时（真实测到的）
     * @param totalMillis         端到端（检索 + 生成）
     */
    public ChatDtos.RetrievalEvent finalPacket(RetrievalResult result, Map<Long, String> docTitles,
                                               long llmGenerationMillis, long totalMillis) {
        return wire(result, docTitles, llmGenerationMillis, totalMillis);
    }

    private ChatDtos.RetrievalEvent wire(RetrievalResult result, Map<Long, String> docTitles,
                                         long llmGenerationMillis, long totalMillis) {
        RetrievalTrace trace = result.trace();
        List<ChatDtos.ChunkRef> refs = refs(result.chunks(), docTitles);
        // ⚠️ 同一个 `List` 实例放进两处 —— `G5-9` 要的是**同一份内容**，
        // 而"两次 map 出内容恰好一样"是巧合，不是保证。这里由构造强制
        ChatDtos.TraceWire wire = new ChatDtos.TraceWire(
                0L,
                trace.embedMillis(),
                trace.vectorSearchMillis(),
                trace.bm25Millis(),
                trace.rerankMillis(),
                llmGenerationMillis,
                totalMillis,
                stages(trace, llmGenerationMillis),
                refs,
                // 图谱旁路（`T-C02`）：`graphUsed` 说"这次图谱有没有贡献分片"；
                // `graphCostMs` **只在真的跑过时**下发 —— 不发比发一个 0 更诚实：
                // "没走过图谱"与"图谱耗时 0ms"不是同一件事（判据 1 与判据 9 一正一反钉这条）
                trace.graphUsed(),
                graphCostMs(trace),
                trace.graphFallback() ? Boolean.TRUE : null);
        return new ChatDtos.RetrievalEvent(refs, wire, result.kbCount());
    }

    /**
     * 图谱旁路的耗时：**只有真的跑过才下发**（没跑就 {@code null}）。
     *
     * <p>"跑过"用三个字段的并来判：命中（{@code graphUsed}）、降级（{@code graphFallback}）、
     * 或耗时非 0。⚠️ 这建立在 {@code trace} 的口径上 —— 开关关时三个字段全 0/false，
     * 见 {@code V42} 的列注释与 {@link RetrievalTrace#graphMillis()}。
     */
    private static Long graphCostMs(RetrievalTrace trace) {
        boolean ran = trace.graphUsed() || trace.graphFallback() || trace.graphMillis() > 0L;
        return ran ? trace.graphMillis() : null;
    }

    /**
     * **答案实际引用的那几条**（`T-804` · `done.citations[]`）。
     *
     * <h3>🔴 它与 {@link #firstPacket} 的 {@code chunks[]} 是**两份不同的清单**</h3>
     * <p>{@code chunks[]} 答的是「系统**找到了**哪些材料」（按检索名次，最多前 5 条）；
     * 本方法答的是「答案**真的用上了**哪几条」（按答案里的角标号）。
     * 在 `T-804` 之前，前端是拿前者按 `i + 1` 拼出 `[1][2]` 的 ——
     * 那让一个**没被引用**的分片也显示成 `[1]`，而用户看不出来。
     *
     * <h3>角标号 → 分片，靠"喂给模型的顺序"这一步</h3>
     * <p>{@code ChatStreamService#prompt} 按 {@code chunks} 的**列表顺序**给资料编号
     * （第 i 条写 `[i]`），所以角标 `n` 对应的就是 {@code chunks.get(n - 1)}。
     * ⚠️ 这个映射**依赖"两处用同一个列表"** —— 若将来 prompt 改了排序（例如按分数重排），
     * 这里必须同步改，否则角标会指向**另一条分片**，而它看起来完全正常。
     * 判据见 `CitationTraceFlowTest`：拿真落库的 `rank_no` 与答案角标逐条对。
     *
     * @param citedRanks 答案里出现的角标号（**空集 = 没解析出角标** ⇒ 返回空列表，
     *                   调用方据此让 `cited` 列写 {@code NULL} 而不是 0）
     * @return **按角标号升序**的引用条目（不是按检索名次）
     */
    public List<ChatDtos.Citation> citations(Set<Integer> citedRanks, List<RetrievedChunk> chunks,
                                             Map<Long, String> docTitles) {
        if (citedRanks == null || citedRanks.isEmpty() || chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<ChatDtos.Citation> out = new ArrayList<>(citedRanks.size());
        List<Integer> sorted = new ArrayList<>(citedRanks);
        sorted.sort(Integer::compareTo);
        for (Integer no : sorted) {
            // 越界在 `CitationParser` 已经滤过一遍。这里再判一次不是重复：
            // 本方法的入参是**公开的**（将来可能有人从别处调它），而"越界即跳过"
            // 是它自己的契约，不该靠调用方守
            if (no == null || no < 1 || no > chunks.size()) {
                continue;
            }
            RetrievedChunk chunk = chunks.get(no - 1);
            long chunkId;
            try {
                chunkId = Long.parseLong(chunk.chunkId());
            } catch (NumberFormatException ex) {
                // 与 `refs` 同一条口径：主键非数字的分片**整条丢掉**，
                // 不猜一个 id 出来（错挂到别的分片上比少一条更糟）
                log.warn("[问答] 引用条目的分片主键非数字，跳过：角标={} chunkId={}", no, chunk.chunkId());
                continue;
            }
            out.add(new ChatDtos.Citation(no, chunk.rank(), chunk.docId(),
                    docTitles.getOrDefault(chunk.docId(), ""), chunkId,
                    chunk.score(), chunk.source().getCode()));
        }
        return List.copyOf(out);
    }

    /**
     * 9 段耗时，**按真实发生的先后**排（不是按 §9 的编号排）。
     *
     * <p>"先后"这件事对排查有用：面板从上往下读就是一次检索的时间线。
     */
    private static List<ChatDtos.TraceStage> stages(RetrievalTrace trace, long llmGenerationMillis) {
        List<ChatDtos.TraceStage> stages = new ArrayList<>(9);
        stages.add(new ChatDtos.TraceStage(STAGE_SCOPE, trace.scopeMillis()));
        // 后端没有"查询解析"这一步，恒 0。保留这一项是为了 §9 的 6 段完整
        // —— 且 0 本身说的是实话（这一步没花时间，因为它不存在）
        stages.add(new ChatDtos.TraceStage(STAGE_QUERY, 0L));
        stages.add(new ChatDtos.TraceStage(STAGE_EMBED, trace.embedMillis()));
        stages.add(new ChatDtos.TraceStage(STAGE_VECTOR, trace.vectorSearchMillis()));
        stages.add(new ChatDtos.TraceStage(STAGE_BM25, trace.bm25Millis()));
        stages.add(new ChatDtos.TraceStage(STAGE_FUSE, trace.fuseMillis()));
        stages.add(new ChatDtos.TraceStage(STAGE_RERANK, trace.rerankMillis()));
        stages.add(new ChatDtos.TraceStage(STAGE_TABLE, trace.tableMillis()));
        stages.add(new ChatDtos.TraceStage(STAGE_LLM, llmGenerationMillis));
        return List.copyOf(stages);
    }

    /**
     * 分片 → 引用条目。
     *
     * <p>⚠️ `chunkId` 转数字失败的分片**整条丢掉**：§9 的 `chunks[].chunkId` 是 number，
     * 发一个字符串过去前端会把角标指向一个不存在的分片。与
     * {@code TableLookupService#numericIds} / {@code RetrievalTraceWriter} 同一条口径 ——
     * **数不出来就不给，不猜**。丢掉的分片仍会出现在 `sys_retrieval_trace_chunk` 吗？
     * 不会（那边同样跳过）—— 两处口径一致，所以"库里没有"与"界面没有"不会互相矛盾。
     */
    private static List<ChatDtos.ChunkRef> refs(List<RetrievedChunk> chunks, Map<Long, String> docTitles) {
        List<ChatDtos.ChunkRef> refs = new ArrayList<>(chunks.size());
        for (RetrievedChunk chunk : chunks) {
            long chunkId;
            try {
                chunkId = Long.parseLong(chunk.chunkId());
            } catch (NumberFormatException ex) {
                log.warn("[问答] 引用条目跳过主键非数字的分片：chunkId={}", chunk.chunkId());
                continue;
            }
            refs.add(new ChatDtos.ChunkRef(chunkId, chunk.docId(),
                    docTitles.getOrDefault(chunk.docId(), ""),
                    chunk.score(),
                    chunk.source().getCode()));
        }
        return List.copyOf(refs);
    }

    /**
     * 按 docId 批量取文档标题（一次查询，不是逐条查）。
     *
     * <p>检索链路本身**不带**文档标题 —— 它只有 {@code titlePath}（标题路径，
     * "3. 接口设计 &gt; 3.1 鉴权流程"），那是**章节**路径不是**文档**标题。
     * 引用卡片要显示的是后者（`types/index.ts:131` 的 {@code docTitle}），
     * 所以这一跳必须在 M08 补。
     *
     * <p>⚠️ **不过滤 `deleted_at`**：引用指向的是一个**已经发生**的检索，
     * 文档后来被软删不该让历史答案的角标变成空标题。查不到（文档被彻底删除）时
     * 返回的 Map 里没有它，{@link #refs} 落空串，前端按 `?? '未知文档'` 兜底。
     */
    @Transactional(readOnly = true)
    public Map<Long, String> loadDocTitles(List<RetrievedChunk> chunks) {
        Set<Long> docIds = new LinkedHashSet<>();
        for (RetrievedChunk chunk : chunks) {
            docIds.add(chunk.docId());
        }
        if (docIds.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select id, title from sys_document where id in (:ids)")
                .setParameter("ids", docIds)
                .getResultList();
        Map<Long, String> titles = new HashMap<>(rows.size());
        for (Object[] row : rows) {
            titles.put(((Number) row[0]).longValue(), row[1] == null ? "" : String.valueOf(row[1]));
        }
        return titles;
    }
}
