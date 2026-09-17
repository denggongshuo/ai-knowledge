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
    private static final String STAGE_SCOPE = "可见范围";
    private static final String STAGE_QUERY = "查询解析";
    private static final String STAGE_EMBED = "Embedding";
    private static final String STAGE_VECTOR = "向量召回";
    private static final String STAGE_BM25 = "BM25 召回";
    private static final String STAGE_FUSE = "RRF 融合";
    private static final String STAGE_RERANK = "Rerank";
    private static final String STAGE_TABLE = "表格回表";
    private static final String STAGE_LLM = "LLM 生成";

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
                // 图谱旁路未实现（`T-C00`）：`graphUsed` 照 §9 保留但恒 false，
                // 另两个可选字段**不发**（null）—— 不发比发一个 0 更诚实：
                // "没走过图谱"与"图谱耗时 0ms"不是同一件事
                false, null, null);
        return new ChatDtos.RetrievalEvent(refs, wire, result.kbCount());
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
