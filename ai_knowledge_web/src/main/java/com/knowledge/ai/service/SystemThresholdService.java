package com.knowledge.ai.service;

import com.knowledge.ai.core.setting.SystemSettingService;
import com.knowledge.ai.core.setting.SystemSettings;
import com.knowledge.ai.dto.system.SystemDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 全局阈值的只读出口（`T-706` · 《10》§11）。
 *
 * <h3>为什么单独一个 Service，而不是让 Controller 直接读 §SettingService</h3>
 * <p>键名 → 兜底默认值 的对应关系是本类**唯一**的业务内容，而它必须和
 * {@code RetrievalParams#resolve} 用的是**同一批**常量
 * （{@link SystemSettings#DEFAULT_*}）。摆在 Controller 里，下一个人改阈值时
 * 大概率只会去找 {@code RetrievalParams}，漏掉这里 —— 于是"库里跑的是 0.35、
 * 运营页显示的是 0.30"，而且**两边都不会报错**。
 *
 * <h3>它是 RetrievalParams 的「显示面」，不是它的另一份实现</h3>
 * <p>两者都从 {@link SystemSettingService#snapshot()} 取值、都用 {@code SystemSettings}
 * 的默认值兜底。它们**唯一的区别**是本类不做
 * {@code I-11} 校验、不夹取 {@code rerankTopK}：本接口是"照实报告库里存了什么"，
 * 校验属于检索路径。所以 {@code rerankTopK} 在这里可能返回 20（越界值），
 * 而检索侧会夹取成 15 —— 这是**刻意的**：
 * 把越界值显示出来，运维才看得见"有人存了个不合法的值"；显示成夹取后的 15
 * 等于替他把配置改漂亮了，越界就永远没人修。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemThresholdService {

    private final SystemSettingService systemSettingService;

    /**
     * 全站阈值快照。
     *
     * <p>缺行或值不合法时**兜底到 {@link SystemSettings} 的默认值**并留 warn
     * （见 {@link SystemSettingService.Snapshot#decimal}）—— 与检索路径同一条口径，
     * 所以"运营页显示的值"与"检索实际用的值"在缺行时也是**一致**的。
     */
    public SystemDtos.Thresholds thresholds() {
        SystemSettingService.Snapshot settings = systemSettingService.snapshot();
        return new SystemDtos.Thresholds(
                settings.decimal(SystemSettings.RETRIEVAL_RECALL_THRESHOLD,
                        SystemSettings.DEFAULT_RECALL_THRESHOLD),
                settings.decimal(SystemSettings.RETRIEVAL_MISS_THRESHOLD,
                        SystemSettings.DEFAULT_MISS_THRESHOLD),
                settings.decimal(SystemSettings.RETRIEVAL_LOW_SCORE_WARN,
                        SystemSettings.DEFAULT_LOW_SCORE_WARN),
                settings.integer(SystemSettings.RETRIEVAL_RERANK_TOP_K,
                        SystemSettings.DEFAULT_RERANK_TOP_K));
    }
}
