package com.knowledge.ai.service;

import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用量统计（`T-905`）—— 真 HTTP 取证，对齐需求 `09` §1.13 的「费用估算的诚实口径」。
 *
 * <h3>本类最重要的一条是 `cost` 的**三态**</h3>
 * <p>{@code cost} 有三种取值，而其中两种都是「0 元」却**语义相反**：
 * <ul>
 *   <li>{@code NONE}（本地部署 / 包月）→ {@code 0} = **已知不计费**；</li>
 *   <li>未配单价 → {@code null} = **无法估算**。</li>
 * </ul>
 * 把后者显示成「¥0」就是撒谎 —— 用户会以为这个模型是免费的。
 * `mock/data.ts:1147` 的注释逐字写着这条区分，本类是它在后端侧的钉子。
 *
 * <h3>为什么清空当月（而不是只清自己造的行）</h3>
 * <p>`sys_model_usage` 是 `V27` 刚建的表，**没有任何历史数据**；跑批期间它的内容
 * 全部由用例（以及 Spring 上下文里那几条真的走了的链路，如重排降级路径）产生。
 * 而"空表的空态是什么"本身就是判据之一（全 0 + {@code cost: null}）。
 * 所以本类**开工前与收尾各清一次当月** —— 这正是 `#108`「跑批前先让共享状态回到
 * 已知值」那条纪律的用法：不是"顺手删数据"，是**让断言有个确定的起点**。
 *
 * <h3>🔴 本表**没有归属列** —— 这是 `#130` 那个盲区的第三种形态</h3>
 * <p>它没有 `kb_id` / `doc_id` / `user_id`，所以：
 * <ul>
 *   <li>「无主产物兜底」（扫 `kb_id` 悬空）**问不了它**；</li>
 *   <li>覆盖率闸（输入是"带 {@code kb_id/doc_id/user_id} 的表"）**结构上扫不到它**。</li>
 * </ul>
 * 于是探针写进去的行**没有任何机器检查会看到**。本批实测：只跑 7 个用例类就落了
 * 2 行 RERANK + 1 行 SUMMARY，而**全量跑完看到的是 0** —— 因为本类的整月清理
 * 把它盖住了。**"清理掩盖问题"的又一次现身**（与 `#136`「闸说干净 ≠ 真的干净」同族）。
 *
 * <p>修法：{@code ProbeCleanup#sweepProbeUsage}（按 {@code occurred_at} 清当天，
 * 由 {@code ApiTestSupport} 的 {@code @AfterEach} 每个用例方法跑一次）+ 两侧登记表 +
 * {@code ProbeArtifacts.NO_OWNERSHIP_COLUMN} 的**盲区台账**（那条台账有机器检查：
 * 台账里的每一张都必须真的登记过，否则盲区会退化成免检区）。
 */
class ModelUsageFlowTest extends ApiTestSupport {

    private String token;
    private LocalDate today;
    private LocalDate monthStart;

    @BeforeEach
    void setUp() {
        token = loginAsAdmin().data("$.accessToken");
        today = LocalDate.now();
        monthStart = today.withDayOfMonth(1);
        clearMonth();
    }

    @AfterEach
    void cleanup() {
        clearMonth();
        // 连同造出来的模型行一起清 —— 它们会进 `/system/model/config` 的 chat 列表，
        // 留在库里会让**别的类**（如 ModelConfigFlowTest 的条数断言）在下一轮莫名其妙地红
        jdbc.update("delete from sys_model_config where model_name like 'usage-%'");
    }

    private void clearMonth() {
        jdbc.update("delete from sys_model_usage where occurred_at between ? and ?", monthStart, today);
    }

    /** 造一条用量。{@code modelName} 本类内唯一，便于按名字定位到具体那一项。 */
    private void insertUsage(String modelName, String type, String purpose,
                             int calls, long prompt, long completion, Integer latencyMs) {
        jdbc.update("""
                insert into sys_model_usage
                    (occurred_at, model_type, model_id, model_name, purpose, calls,
                     prompt_tokens, completion_tokens, latency_ms, trace_id)
                values (?, ?, null, ?, ?, ?, ?, ?, ?, 'itest-usage')
                """, today, type, modelName, purpose, calls, prompt, completion, latencyMs);
    }

    /** 造一条带计价配置的模型行（只用于给它定价，不参与 options 过滤）。 */
    private void insertPricedModel(String modelName, String billingUnit,
                                   BigDecimal inputPrice, BigDecimal outputPrice, BigDecimal perCallPrice) {
        jdbc.update("""
                insert into sys_model_config
                    (model_type, display_name, protocol, base_url, model_name, params, capabilities,
                     billing_unit, input_price, output_price, per_call_price, currency,
                     is_active, sort_order, last_test_ok, note, created_at, updated_at)
                values ('CHAT', ?, 'OpenAI 兼容', 'https://example.invalid/v1', ?, '{}', '{}',
                        ?, ?, ?, ?, 'CNY', 0, 98, 0, '用例造', now(), now())
                """, modelName, modelName, billingUnit, inputPrice, outputPrice, perCallPrice);
    }

    @Test
    @DisplayName("🔴 cost 三态：已配单价 = 数 · NONE = 0 · 未配单价 = null")
    void costHasThreeStates() {
        // ① 已配单价（PER_TOKEN，输入 ¥2/百万、输出 ¥8/百万）
        insertPricedModel("usage-priced", "PER_TOKEN",
                new BigDecimal("2.000000"), new BigDecimal("8.000000"), null);
        insertUsage("usage-priced", "CHAT", "CHAT", 1, 1_000_000L, 1_000_000L, 1200);

        // ② 本地部署（NONE）—— 已知不计费
        insertPricedModel("usage-free", "NONE", null, null, null);
        insertUsage("usage-free", "CHAT", "CHAT", 3, 500_000L, 0L, 300);

        // ③ 未配单价 —— 无法估算
        insertPricedModel("usage-unpriced", "PER_TOKEN", null, null, null);
        insertUsage("usage-unpriced", "CHAT", "CHAT", 2, 800_000L, 200_000L, 500);

        RawResponse response = get("/api/system/model/usage", token).assertOk("用量统计");

        assertThat(costOf(response, "usage-priced"))
                .isEqualByComparingTo(new BigDecimal("10.00"));   // 1M×¥2 + 1M×¥8 = ¥10
        assertThat(costOf(response, "usage-free"))
                .isEqualByComparingTo(BigDecimal.ZERO);           // 0 而不是 null
        assertThat(costOf(response, "usage-unpriced"))
                .isNull();                                        // null 而不是 0

        // `unpricedCount` **不含 NONE** —— 本地部署不是"忘了配"
        assertThat(response.<Number>data("$.unpricedCount").intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("合计只累加已计价项 —— 少了几个模型要自己说清（unpricedCount）")
    void totalOnlySumsPricedModels() {
        insertPricedModel("usage-a", "PER_TOKEN", new BigDecimal("1.000000"), null, null);
        insertUsage("usage-a", "CHAT", "CHAT", 1, 1_000_000L, 0L, null);
        insertPricedModel("usage-b", "PER_TOKEN", null, null, null);
        insertUsage("usage-b", "CHAT", "CHAT", 1, 9_000_000L, 0L, null);

        RawResponse response = get("/api/system/model/usage", token).assertOk("用量统计");

        // 合计只含 A 的 ¥1，**不含** B（B 无法估算）
        assertThat(totalCost(response)).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(response.<Number>data("$.unpricedCount").intValue()).isEqualTo(1);
        // B 的 token 仍进总量 —— "用了多少"与"花多少钱"是两件事，前者不能因为后者算不出就丢掉
        assertThat(response.<Number>data("$.tokens").longValue()).isEqualTo(10_000_000L);
    }

    @Test
    @DisplayName("byPurpose 各桶之和 == tokens 合计（mock 注释逐字要求的那条不变量）")
    void purposeBucketsSumToTotal() {
        insertUsage("usage-p1", "CHAT", "CHAT", 1, 1_000_000L, 0L, 100);
        insertUsage("usage-p2", "CHAT", "SUMMARY", 2, 2_000_000L, 500_000L, 200);
        insertUsage("usage-p3", "EMBEDDING", "EMBEDDING", 5, 300_000L, 0L, 50);
        insertUsage("usage-p4", "VL", "VL_DESC", 1, 400_000L, 100_000L, 1600);

        RawResponse response = get("/api/system/model/usage", token).assertOk("用量统计");

        long total = response.<Number>data("$.tokens").longValue();
        assertThat(total).isEqualTo(1_000_000L + 2_500_000L + 300_000L + 500_000L);

        List<Number> bucketTokens = response.data("$.byPurpose[*].tokens");
        long sum = bucketTokens.stream().mapToLong(Number::longValue).sum();
        assertThat(sum).isEqualTo(total);

        // 空桶也发（前端按桶名匹配渲染）—— 本用例用到了 4 个，另 2 个（HYQ/RERANK）必须是 0 而不是缺失
        List<String> names = response.data("$.byPurpose[*].name");
        assertThat(names).contains("问答生成", "文档级摘要 + 内容标签", "文档向量化（嵌入）", "图片理解（VL 描述）");
        assertThat(names).hasSize(6);
    }

    @Test
    @DisplayName("空表是**正确的空态**：全 0 + cost=null + 模型列表为空（不是「未实现」）")
    void emptyTableIsAnHonestEmptyState() {
        RawResponse response = get("/api/system/model/usage", token).assertOk("用量统计");

        assertThat(response.<Number>data("$.calls").longValue()).isZero();
        assertThat(response.<Number>data("$.tokens").longValue()).isZero();
        // cost 为 null 而不是 0 —— 0 会被读成"确认不花钱"
        assertThat(totalCost(response)).isNull();
        assertThat(response.<Number>data("$.unpricedCount").intValue()).isZero();
        assertThat(response.<Object>data("$.byModel")).isEqualTo(List.of());
        assertThat(response.<Number>data("$.avgLatencySec").doubleValue()).isZero();
    }

    @Test
    @DisplayName("avgLatencySec 只按**有耗时记录**的行算，没测的不算成 0 秒")
    void averageLatencyIgnoresUnmeasured() {
        insertUsage("usage-l1", "CHAT", "CHAT", 1, 0L, 0L, 2000);
        insertUsage("usage-l2", "CHAT", "CHAT", 1, 0L, 0L, null);

        RawResponse response = get("/api/system/model/usage", token).assertOk("用量统计");

        // 两条调用，但只有一条有耗时 ⇒ 平均 = 2000ms / 1 = 2.0s。
        // 若把"没测"当成 0ms，会算成 1.0s —— 那是把一个未知当成"很快"
        assertThat(response.<Number>data("$.avgLatencySec").doubleValue()).isEqualTo(2.0D);
    }

    /** 取某个模型的 cost。⚠️ JSON 里的数是 {@code Double} 而不是 {@code BigDecimal} —— 转一道。 */
    private static BigDecimal costOf(RawResponse response, String modelName) {
        List<String> names = response.data("$.byModel[*].modelName");
        int index = names.indexOf(modelName);
        assertThat(index).as("byModel 里应有 %s", modelName).isGreaterThanOrEqualTo(0);
        Number raw = response.data("$.byModel[" + index + "].cost");
        return raw == null ? null : new BigDecimal(raw.toString());
    }

    /** 取顶层 cost（同样是 Double）。 */
    private static BigDecimal totalCost(RawResponse response) {
        Number raw = response.data("$.cost");
        return raw == null ? null : new BigDecimal(raw.toString());
    }
}
