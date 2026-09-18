package com.knowledge.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDate;

/**
 * 模型用量（表 {@code sys_model_usage}，`T-905`）—— **按天 + 模型 + 用途聚合**。
 *
 * <h3>为什么是聚合表而不是明细表</h3>
 * <p>见 `V27` 的文件头注：消费方只有模型配置页的用量卡，而它要的是**本月合计**。
 * 明细表会为每次问答、每篇文档解析各添一次 INSERT（问答是热路径），
 * 而**没有任何页面看那些行**。聚合后行数上界是 {@code 天数 × 模型数 × 用途数}。
 * ⚠️ 代价如实记着：**无法回溯"这一次调用花了多少"**，真要做逐次归因得另立明细表。
 *
 * <h3>为什么 token 是 {@code 0} 而不是 {@code null}</h3>
 * <p>同一个模型的不同调用可能有的回报了 token、有的没回报。本表的读法是
 * {@code SUM(...)}，而 SQL 的 {@code SUM} **忽略 NULL**、{@code NULL + x} 又是 NULL ——
 * 用可空列会让"没回报"悄悄污染合计。语义上"没回报"就是"这次没记到数"，
 * 计 0 与计 NULL 对合计是同一件事，但只有 0 能让合计保持是数。
 * ⚠️ 对比 {@link #latencyMs} 是**可空**的：那是"**没有测**"（与其记 0ms 说"快得不可能"，
 * 不如说"没测"），两者语义不同 —— 与 `ChatDtos.DoneEvent` 的 {@code TokenUsage} 同一条取舍。
 *
 * <h3>为什么输入与输出**分两列**</h3>
 * <p>{@code sys_model_config} 的计价列就是分开的（{@code input_price} / {@code output_price}），
 * 而两者通常差 3~4 倍。只存合计的话，费用只能用其中一个单价去乘全部 token ——
 * 那不是"估算"，是系统性偏差。⚠️ 合计由查询侧 {@code sum(prompt + completion)} 算，
 * **刻意不存第三列**（冗余列与明细列迟早漂移，而"哪一列是真的"没人说得清）。
 *
 * <h3>{@code modelId} 可空，{@code modelName} 不可空</h3>
 * <p>用量是**已经发生的事实**，模型配置被删掉不会让那次调用没发生过
 * （`T-901` 允许删未启用的模型）。所以 id 允许悬空，而名字冗余存下来 ——
 * 与 {@code SysChatMessage} 同时存 {@code model_id} + {@code model_name} 同一条理由。
 */
@Data
@Entity
@Table(
        name = "sys_model_usage",
        indexes = {
                @Index(name = "idx_mu_date_type", columnList = "occurred_at, model_type"),
                @Index(name = "idx_mu_date_purpose", columnList = "occurred_at, purpose"),
        }
)
public class SysModelUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属日期。月统计按它做范围过滤后 SUM。 */
    @Column(name = "occurred_at", nullable = false)
    private LocalDate occurredAt;

    /** 四类之一（{@code ModelType#name()}）。 */
    @Column(name = "model_type", length = 16, nullable = false)
    private String modelType;

    /** 模型配置 id。**可空** —— 模型被删后这里留 NULL，用量本身仍是事实。 */
    @Column(name = "model_id")
    private Long modelId;

    /** 模型名（冗余存，理由见类注释）。 */
    @Column(name = "model_name", length = 128, nullable = false)
    private String modelName;

    /** 用途桶（{@code ModelUsageRecorder.Purpose#name()}）。 */
    @Column(name = "purpose", length = 24, nullable = false)
    private String purpose;

    /** 调用次数。 */
    @Column(name = "calls", nullable = false)
    private Integer calls = 0;

    /** 输入 token（计价按 {@code input_price}）。见类注释「为什么是 0 而不是 null」。 */
    @Column(name = "prompt_tokens", nullable = false)
    private Long promptTokens = 0L;

    /** 输出 token（计价按 {@code output_price}）。 */
    @Column(name = "completion_tokens", nullable = false)
    private Long completionTokens = 0L;

    /** 耗时合计（毫秒）。{@code null} = **没测**（不是 0ms）。 */
    @Column(name = "latency_ms")
    private Integer latencyMs;

    /** 最后一次写入的 traceId（只留最后一次 —— 见 `V27` 文件头注的边界说明）。 */
    @Column(name = "trace_id", length = 64)
    private String traceId;
}
