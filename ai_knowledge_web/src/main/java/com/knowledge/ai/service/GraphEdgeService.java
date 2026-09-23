package com.knowledge.ai.service;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.dto.ops.OpsDtos;
import com.knowledge.ai.pipeline.graph.GraphWriter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 人工图谱补边（`T-C04` · 需求 `07:544`「针对未命中问题添加人工关系边」· 术语见 `03:102`）。
 *
 * <h3>这一层只干一件事：**把会让边变成垃圾的输入挡在图库之外**</h3>
 * <p>{@link GraphWriter#addManualEdge} 是"写"，它不查 MySQL（分层的事）。而一条人工边写错了
 * **不会报错、不会有日志**，只有两种安静的下场：
 * <ul>
 *   <li><b>悬空锚点</b> —— {@code sourceChunkId} 不是真的分片 id。检索侧
 *       {@code GraphRetriever} 回源时 {@code chunk == null} 就 {@code continue}，
 *       于是这条边**看着在、跳不到原文**（需求 `:469` 要的"可溯源"落空）。</li>
 *   <li><b>动词凭空造</b> —— 需求 `07:468` 把 `relation` 限成 8 个固定动词。
 *       写进去一个"喜欢"，它永远匹配不到任何既有边的语义，只会在图里积压。</li>
 * </ul>
 * <p>所以本类**先校验、后写图**，且校验失败时**图里一行都不动**（有判据钉住）。
 *
 * <h3>为什么不校验「实体名逐字出现在分片正文里」</h3>
 * <p>那条规则（`S9-M12-C` 立的提示词要求）是给**模型**的，不是给人。这里不校验它有两个理由：
 * ① 操作员补的往往正是"模型没抽出来"的那条边，实体名可能本来就不在正文里（那正是要补的原因）；
 * ② 校验它会让"先补边、后改文档"的正常顺序被误拒。⚠️ 代价照实说：实体名写得与正文不一致时，
 * 边跳得到、但溯源语义弱 —— 已登记在计划 §9。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphEdgeService {

    /**
     * 需求 `07:468` 逐字划定的关系动词闭集。
     *
     * <p>⚠️ **它是闭集，不是建议**：需求原文写的是「relation 限**固定动词集**」。
     * 开放自由输入会让图里出现检索侧永远匹配不到的动词（而"图里积压着没人查的边"
     * 是看不出来的 —— 边数照涨）。新增动词请先改需求，再改这里。
     */
    public static final Set<String> ALLOWED_PREDICATES = Set.of(
            "基于", "提出者", "包含", "使用", "依赖", "属于", "负责", "引用");

    /** 人工边一律 1.0（需求 `03:102`）。**不接收调用方传值** —— 理由见 {@link GraphWriter#addManualEdge}。 */
    private static final double MANUAL_CONFIDENCE = 1.0d;

    private final GraphWriter graphWriter;

    private final DocAccessService docAccessService;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 给一条未命中问题补一条边。
     *
     * <p>顺序是**先校验全部、再写图**：任何一条校验不过都直接抛，图库零改动。
     *
     * @param missId    未命中问题 id（用来回显"补的是哪条问题"，**并校验它还在清单里**）
     * @param subject   实体名（主体）
     * @param predicate 关系动词，必须在 {@link #ALLOWED_PREDICATES} 内
     * @param object    实体名（客体）
     * @param docId     这条关系出自哪篇文档（须**可见**，且**已解析成功**）
     * @param chunkId   回源锚：**必须确实属于 {@code docId}** 的 `sys_chunk.id`
     */
    @Transactional
    public OpsDtos.ManualEdgeResult addEdge(long missId, String subject, String predicate, String object,
                                            long docId, long chunkId) {
        requireMissExists(missId);
        String sub = requireEntityName(subject, "subject（主体）");
        String obj = requireEntityName(object, "object（客体）");
        if (sub.equals(obj)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "主体与客体不能是同一个实体");
        }
        if (predicate == null || !ALLOWED_PREDICATES.contains(predicate.strip())) {
            // ⚠️ 把闭集**列在报错里**：下一个人不必去翻需求文档才知道能填什么。
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "关系动词仅支持 " + String.join("/", ALLOWED_PREDICATES.stream().sorted().toList())
                            + "，实际收到「" + predicate + "」");
        }
        String pred = predicate.strip();

        // ① 文档可见 + 已解析成功（未解析的文档没有分片，锚什么都悬空）
        DocAccessService.DocRow doc = docAccessService.requireVisibleDoc(docId);
        if (!"READY".equals(doc.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "该文档尚未解析成功（当前状态 " + doc.status() + "），没有可分片可锚 —— "
                            + "等它 READY 后再补边");
        }

        // ② 锚点必须真的是这篇文档的分片。
        //    🔴 这一条是本类存在的**主要理由**：GraphRetriever 对悬空锚点是静默 continue，
        //    写进去看不出来（见类注释）。
        requireChunkOfDoc(docId, chunkId);

        GraphWriter.Result result = graphWriter.addManualEdge(docId, chunkId, sub, pred, obj);
        if (!result.applied()) {
            // Neo4j 挂了 / 开关关了 —— 如实回失败原因（承 G9-3：不让运营页 500）。
            // ⚠️ 不 throw：这**不是**参数错，抛出去会变成"补边接口坏了"，
            //    而真相是"图库此刻不可用，可以稍后重试"。
            log.warn("[图谱补边] 未落库：missId={} docId={} 原因={}", missId, docId, result.detail());
        }
        return new OpsDtos.ManualEdgeResult(result.applied(), docId, chunkId, sub, pred, obj,
                MANUAL_CONFIDENCE, result.detail());
    }

    /**
     * 未命中行必须存在。
     *
     * <p>⚠️ **不拒 `IGNORED`**：需求 `07:544` 只说"针对未命中问题补边"，没设状态门槛；
     * 而运营的实际用法可能正是"先把这条忽略了，再补一条边"。
     * 拒掉它会让"忽略"变成一个不可逆的动作。
     */
    private void requireMissExists(long missId) {
        Number n = (Number) entityManager.createNativeQuery(
                        "select count(*) from sys_qa_miss where id = :id")
                .setParameter("id", missId)
                .getSingleResult();
        if (n == null || n.longValue() == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "未命中问题不存在：" + missId);
        }
    }

    private void requireChunkOfDoc(long docId, long chunkId) {
        Number n = (Number) entityManager.createNativeQuery(
                        "select count(*) from sys_chunk where id = :id and doc_id = :docId")
                .setParameter("id", chunkId)
                .setParameter("docId", docId)
                .getSingleResult();
        if (n == null || n.longValue() == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "分片 " + chunkId + " 不属于文档 " + docId
                            + " —— 锚到别的文档的分片会让这条边回不到原文（补边前后图库均未改动）");
        }
    }

    private static String requireEntityName(String raw, String field) {
        String v = raw == null ? "" : raw.strip();
        if (v.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, field + "不能为空");
        }
        // 实体名是 Neo4j 的联合主键（需求 07:462），长度上给一个宽松但仍有限的上界，
        // 免得一条粘贴错的整段正文被当成实体名写进图。
        if (v.length() > 128) {
            throw new BizException(ErrorCode.PARAM_INVALID, field + "过长（上限 128 字符，实际 " + v.length() + "）");
        }
        return v;
    }

    /** 供用例断言"合法动词一个不少"（避免闭集被误改）。 */
    public static List<String> allowedPredicates() {
        return ALLOWED_PREDICATES.stream().sorted().toList();
    }
}
