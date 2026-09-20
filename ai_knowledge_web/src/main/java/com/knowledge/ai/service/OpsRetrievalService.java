package com.knowledge.ai.service;

import com.knowledge.ai.core.ops.RetrievalOpsRepository;
import com.knowledge.ai.core.ops.TimeWindow;
import com.knowledge.ai.dto.ops.OpsRetrievalDtos;
import com.knowledge.ai.pipeline.chat.ChatWireMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 页 20「检索质量分析」的五个只读端点（`T-A03` · 需求 `08` `FR-OPS-02`）。
 *
 * <h3>这一层只做三件事</h3>
 * <ol>
 *   <li>把 {@link RetrievalOpsRepository} 的聚合值**拼成页面要的形状**（含闭集补全）；</li>
 *   <li>算**比率**（SQL 里只取分子分母，百分比与空值判定在 Java 侧 —— 分母为 0 时
 *       必须回 `null` 而不是 `0`：`0%` 会被读成"一条都没命中"，而真相是"没有数据"）；</li>
 *   <li>把**口径**写进返回值（可比集 / 无法定档 / 覆盖数），页面才说得清自己在哪批行上算。</li>
 * </ol>
 *
 * <h3>🔴 空窗口一律回 `null` 或空列表，**不回 0**</h3>
 * <p>实测 `sys_retrieval_trace` 是 **0 行**（本批开工实测），而需求 §2.8 最后一条点名要
 * 「没有检索打点时页面显示"数据不足"而不是空白或假数据」—— 回 0 正好就是那句点名要防的。
 */
@Service
@RequiredArgsConstructor
public class OpsRetrievalService {

    /** 「走势」判定至少要多少样本（每半区各自判）。 */
    public static final int TREND_MIN_SAMPLES = 5;

    /** 「走势」判定的变化幅度（百分点）——小于它算稳定。 */
    public static final double TREND_DELTA_PP = 5d;

    /** 分档标签与区间（需求 §2.3 + 本批补的第 6 档）。**顺序即展示顺序**。 */
    private static final String[] BUCKET_LABELS = {"优秀", "良好", "一般", "边缘", "未命中", "无法定档"};
    private static final String[] BUCKET_RANGES = {
            "0.80 ~ 1.00", "0.60 ~ 0.80", "0.40 ~ 0.60", "0.35 ~ 0.40", "< 0.35", "—"};

    /**
     * 链路九段的名字与顺序 —— **就是 `ChatWireMapper.stages()` 的那一套**（检索详情面板的时间线）。
     * 常量的定义在那边（单一事实源），这里只做引用。
     */
    private static final String[] STAGE_NAMES = {
            ChatWireMapper.STAGE_SCOPE, ChatWireMapper.STAGE_QUERY, ChatWireMapper.STAGE_EMBED,
            ChatWireMapper.STAGE_VECTOR, ChatWireMapper.STAGE_BM25, ChatWireMapper.STAGE_FUSE,
            ChatWireMapper.STAGE_RERANK, ChatWireMapper.STAGE_TABLE, ChatWireMapper.STAGE_LLM};

    private final RetrievalOpsRepository repository;

    private final SystemThresholdService systemThresholdService;

    // ==================== ① overview ====================

    public OpsRetrievalDtos.Overview overview(String range) {
        LocalDateTime from = TimeWindow.from(range);
        RetrievalOpsRepository.Window w = repository.window(from);
        double missThreshold = systemThresholdService.thresholds().missThreshold();

        return new OpsRetrievalDtos.Overview(
                w.total(), w.comparable(), w.noRerank(), w.emptyRecall(),
                w.avgTop1(),
                rate(w.hits(), w.comparable()),
                // ⚠️ 净提升取 rerank-effect 的**同子集**口径，不是本对象两个比率的差
                //（那两个比率分母不同，相减会把"覆盖率"混进"提升"里）
                gain(from),
                w.avgChunks(),
                missThreshold);
    }

    // ==================== ② pipeline ====================

    public OpsRetrievalDtos.Pipeline pipeline(String range) {
        LocalDateTime from = TimeWindow.from(range);
        RetrievalOpsRepository.Stages s = repository.stages(from);
        long totalRows = repository.window(from).total();

        Double fused = s.fusedCount();
        Double gain = gain(from);

        // 段名与顺序**取自 `ChatWireMapper`**（检索详情面板的同一套）——
        // 两处各写一份的话，面板与这张图迟早对不上，而"对不上"表现为两个页面各说各话、各自都自洽。
        // ⚠️ 名字数组与成本数组**同长**（`STAGE_NAMES.length` 同时管住两个），
        // 所以"少一段、多一段"在结构上就不可能 —— 不需要另写一条断言去比长度。
        Double[] costs = {s.scope(), s.queryAnalysis(), s.embed(), s.vectorSearch(), s.bm25(),
                s.fuse(), s.rerank(), s.table(), s.llm()};
        // 需求 §2.2「关注指标」列：**只有一个有采集点**（RRF 的 `fused_count`），
        // 另有重排那个（来自新加的 `rerank_before_top1`）。其余四个（改写成功率 /
        // Top20 内命中率 / 精确匹配命中率 / 首字延迟·引用采纳率）**后端没有采集点**
        // ⇒ 如实给 null（页面显示 `—`），不拿别的数冒充，也不留 mock 的 96.4% / 88.2%
        String[] metrics = {null, "改写成功率", null, "Top20 内命中率", "精确匹配命中率",
                "去重后候选数", "重排前后命中率差", null, "首字延迟 / 引用采纳率"};
        String[] values = {null, null, null, null, null,
                fused == null ? null : String.valueOf(Math.round(fused)),
                gain == null ? null : "+" + one(gain) + "pp",
                null, null};

        List<OpsRetrievalDtos.Stage> list = new ArrayList<>(STAGE_NAMES.length);
        for (int i = 0; i < STAGE_NAMES.length; i++) {
            list.add(stage(STAGE_NAMES[i], costs[i], metrics[i], values[i]));
        }

        return new OpsRetrievalDtos.Pipeline(list, s.rows(), totalRows - s.rows(),
                ms(s.side()), ms(s.llm()), ms(s.other()), ms(s.total()));
    }

    // ==================== ③ score-distribution ====================

    public OpsRetrievalDtos.ScoreDistribution scoreDistribution(String range) {
        RetrievalOpsRepository.Buckets b = repository.buckets(TimeWindow.from(range));

        // ⚠️ 分母是**提问总数**（含"无法定档"那档）⇒ 各档合计恰为 100%
        long[] counts = {b.excellent(), b.good(), b.fair(), b.marginal(), b.miss(),
                b.noRerank() + b.emptyRecall()};
        List<OpsRetrievalDtos.Bucket> list = new ArrayList<>(counts.length);
        for (int i = 0; i < counts.length; i++) {
            boolean danger = i == 4 || i == 5;   // 未命中 / 无法定档
            list.add(new OpsRetrievalDtos.Bucket(BUCKET_LABELS[i], BUCKET_RANGES[i],
                    counts[i], percent(counts[i], b.total()), danger));
        }
        return new OpsRetrievalDtos.ScoreDistribution(list, b.total(),
                b.noRerank() + b.emptyRecall(), b.noRerank(), b.emptyRecall());
    }

    // ==================== ④ rerank-effect ====================

    public OpsRetrievalDtos.RerankEffect rerankEffect(String range) {
        LocalDateTime from = TimeWindow.from(range);
        // ⚠️ `window` 只取**一次**：取两次的话，两次查询之间新落一行打点
        // ⇒ `comparable` 与 `avgLatencyMs` 来自**两个快照**，两个数各自都对而互相矛盾
        //（与 `QaOpsRepository#filter` 的"只写一处"是同一条纪律）
        RetrievalOpsRepository.Window w = repository.window(from);
        RetrievalOpsRepository.BeforeAfter ba = repository.beforeAfter(from);

        Double before = rate(ba.beforeHits(), ba.covered());
        Double after = rate(ba.afterHits(), ba.covered());
        return new OpsRetrievalDtos.RerankEffect(
                before, after,
                before == null || after == null ? null : Math.round((after - before) * 10) / 10d,
                w.avgRerankMillis(),
                ba.covered(),
                w.comparable());
    }

    // ==================== ⑤ kb-quality ====================

    public OpsRetrievalDtos.KbQuality kbQuality(String range) {
        LocalDateTime from = TimeWindow.from(range);
        LocalDateTime mid = TimeWindow.mid(range);
        List<RetrievalOpsRepository.KbAgg> aggs = repository.singleKbAggregates(from, mid);

        Map<Long, String> names = kbNames(aggs);
        List<OpsRetrievalDtos.KbQualityRow> rows = new ArrayList<>(aggs.size());
        long questions = 0;
        for (RetrievalOpsRepository.KbAgg agg : aggs) {
            Long kbId = firstId(agg.kbIds());
            questions += agg.total();
            rows.add(new OpsRetrievalDtos.KbQualityRow(
                    kbId,
                    kbId == null ? ("#" + agg.kbIds()) : names.getOrDefault(kbId, "已删除库#" + kbId),
                    agg.total(),
                    agg.avgTop1(),
                    rate(agg.miss(), agg.comparable()),
                    trend(agg),
                    // 「主要问题」来自文档质量列 / 解析问题表，与检索打点是两张表的事 ⇒ 不猜
                    null));
        }
        // 需求 §7.4 的表按未命中率升序；空值（算不出）排最后
        rows.sort(Comparator.comparing(OpsRetrievalDtos.KbQualityRow::missRate,
                Comparator.nullsLast(Comparator.naturalOrder())));

        long others = repository.multiKbQuestions(from);
        if (others > 0) {
            // 跨库提问单独一行 —— 拆给每个库会把一次提问算多次，各库合计就会**超过**提问总数，
            // 而需求 §2.8 第 4 条要求的是 ≤（并点名"未列出的归入其他"）
            rows.add(new OpsRetrievalDtos.KbQualityRow(null, "其他（跨库提问）", others,
                    null, null, "UNKNOWN", null));
        }
        return new OpsRetrievalDtos.KbQuality(rows, questions, others);
    }

    // ==================== 内部 ====================

    /** `rerank-effect` 的净提升（百分点），算不出来回 `null`。 */
    private Double gain(LocalDateTime from) {
        RetrievalOpsRepository.BeforeAfter ba = repository.beforeAfter(from);
        Double before = rate(ba.beforeHits(), ba.covered());
        Double after = rate(ba.afterHits(), ba.covered());
        return before == null || after == null ? null : Math.round((after - before) * 10) / 10d;
    }

    /**
     * 走势：把窗口对半切，比两个半区的未命中率。
     *
     * <p>⚠️ **任一半区样本不足就回 `UNKNOWN`**（不是 `STABLE`）—— 把"没数据"说成"稳定"
     * 正好会让需求 §2.5 最想抓的那句（"稳定偏低 vs 持续下降"）失效。
     */
    private static String trend(RetrievalOpsRepository.KbAgg agg) {
        if (agg.h1Comparable() < TREND_MIN_SAMPLES || agg.h2Comparable() < TREND_MIN_SAMPLES) {
            return "UNKNOWN";
        }
        double h1 = 100d * agg.h1Miss() / agg.h1Comparable();
        double h2 = 100d * agg.h2Miss() / agg.h2Comparable();
        double delta = h2 - h1;
        if (delta > TREND_DELTA_PP) {
            return "DOWN";      // 未命中率在涨 = 质量在降
        }
        if (delta < -TREND_DELTA_PP) {
            return "UP";
        }
        return "STABLE";
    }

    private Map<Long, String> kbNames(List<RetrievalOpsRepository.KbAgg> aggs) {
        List<Long> ids = new ArrayList<>();
        for (RetrievalOpsRepository.KbAgg agg : aggs) {
            Long id = firstId(agg.kbIds());
            if (id != null) {
                ids.add(id);
            }
        }
        Map<Long, String> out = new HashMap<>();
        for (Object[] row : repository.kbNames(ids)) {
            String name = row[1] == null ? null : String.valueOf(row[1]);
            // 软删的库也要有名字（否则"某一个库"与"其他"的账对不上），但必须**看得出来**它是删过的
            out.put(((Number) row[0]).longValue(),
                    name == null ? "已删除库" : (row[2] == null ? name : name + "（已删除）"));
        }
        return out;
    }

    /** `kb_ids` 是逗号串；`kb_count = 1` 的库里它只有一个 id（多值/非数字回 `null`）。 */
    private static Long firstId(String kbIds) {
        if (kbIds == null || kbIds.isBlank() || kbIds.contains(",")) {
            return null;
        }
        try {
            return Long.valueOf(kbIds.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static OpsRetrievalDtos.Stage stage(String name, Double cost, String metric, String metricValue) {
        return new OpsRetrievalDtos.Stage(name, ms(cost), metric, metricValue, action(name));
    }

    /**
     * 需求 §2.2「异常时的动作」列。
     *
     * <p>它是**固定 playbook**（不随数据变），与"这个阶段的数好不好"无关 ——
     * 数不好时该做的那件事，不会因为有数据就变。<b>页面当前不渲染它</b>，
     * 保留是为了接口契约与需求那张表逐项对齐。
     */
    private static String action(String stage) {
        return switch (stage) {
            case ChatWireMapper.STAGE_QUERY -> "调整 HyQ 生成提示词";
            case ChatWireMapper.STAGE_VECTOR -> "调 chunkSize / 换嵌入模型";
            case ChatWireMapper.STAGE_BM25 -> "优化分词与标识符保护";
            case ChatWireMapper.STAGE_FUSE -> "调整融合权重";
            case ChatWireMapper.STAGE_RERANK -> "切换重排模型（切换前先跑评测集对比 Recall@5） / 调候选数";
            case ChatWireMapper.STAGE_LLM -> "调 prompt 与上下文长度";
            default -> null;
        };
    }

    /** 分子/分母 → 百分数（保留 1 位）。**分母为 0 回 `null`**，不回 0。 */
    private static Double rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return Math.round(1000d * numerator / denominator) / 10d;
    }

    private static double percent(long count, long total) {
        if (total <= 0) {
            return 0d;
        }
        return Math.round(1000d * count / total) / 10d;
    }

    private static long ms(Double d) {
        return d == null ? 0L : Math.round(d);
    }

    private static String one(Double d) {
        return String.valueOf(Math.round(d * 10) / 10d);
    }
}
