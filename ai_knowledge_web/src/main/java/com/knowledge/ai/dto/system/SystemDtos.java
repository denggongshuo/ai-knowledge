package com.knowledge.ai.dto.system;

/**
 * 系统级只读字典的响应体（M07 · 《10》§11，`T-706`）。
 *
 * <h3>为什么与 {@link com.knowledge.ai.dto.kb.KbDtos} 分家</h3>
 * <p>它们**同名不同义**，这是本项目反复踩的形态，所以刻意不放在一起：
 * <ul>
 *   <li>{@link Thresholds#recallThreshold()} —— 这里返回的是**全站默认值**
 *       （来自 {@code sys_setting}）。</li>
 *   <li>{@code KbDtos.RetrievalConfig#recallThreshold()} —— 那个是**库级值**
 *       （来自 {@code sys_kb.retrieval_config_json}），且**可以覆盖**全站默认值。</li>
 * </ul>
 * <p>四个字段名与前端 {@code src/api/index.ts} 的 {@code thresholds()} 及
 * {@code src/types/index.ts} 逐字一致 —— 名字对不上会**静默丢字段**（页面显示 undefined），
 * 不会报错。这与 {@code KbDtos} 的类注释是同一条约定。
 */
public final class SystemDtos {

    private SystemDtos() {
    }

    /**
     * {@code GET /system/thresholds} —— 全局阈值（运营分析页只读展示）。
     *
     * <h3>⚠️ 口径：这四个值**不随任何库的库级配置变化**</h3>
     * <p>本接口不接受 {@code kbId}，也不读 {@code sys_kb}。其中
     * {@code recallThreshold} 与 {@code rerankTopK} 在 {@code RetrievalParams} 里是
     * <b>库级可覆盖</b>的参数，而它们在本响应里是**覆盖发生之前**的全站默认值。
     *
     * <p>之所以要把这句话写在这里而不是留给读者推断：这两个名字在两个地方出现、
     * 含义不同。下一个人若把它当成"当前库的阈值"，就会得出
     * "我在设置页把召回阈值调成 0.3 了，接口却还返回 0.15 —— 是 bug"的结论。
     * 而它返回 0.15 是**对的**。
     *
     * <p>反过来，要问"某个库此刻实际用的是什么阈值"，那是
     * {@code GET /kb/{kbId}/config} 的 {@code retrievalConfig} 加上本接口的全局值
     * 按 {@code RetrievalParams#resolve} 的规则合成的结果 —— 目前没有对外接口，
     * `T-711` 的评测与它无关（评测直接调 Service）。
     *
     * @param recallThreshold 全站召回阈值（宽进）。库级可覆盖
     * @param missThreshold   未命中判定线（严出）。**只有全局一处**，不可被库级覆盖
     * @param lowScoreWarn    降权提示线。低于它**只标黄、不过滤**
     * @param rerankTopK      精排保留数的全站默认值，合法区间 [3, 15]
     */
    public record Thresholds(double recallThreshold,
                             double missThreshold,
                             double lowScoreWarn,
                             int rerankTopK) {
    }
}
