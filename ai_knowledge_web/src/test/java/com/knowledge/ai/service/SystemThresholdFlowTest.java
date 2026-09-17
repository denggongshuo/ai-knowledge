package com.knowledge.ai.service;

import com.knowledge.ai.controller.SystemController;
import com.knowledge.ai.core.setting.SystemSettingService;
import com.knowledge.ai.core.setting.SystemSettings;
import com.knowledge.ai.pipeline.retrieve.RetrievalParams;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局阈值接口的门禁用例（`T-706` · 《10》§11）。
 *
 * <h3>本用例真正在钉的不是"接口能返回数"，是「它和检索读的是同一份真值」</h3>
 * <p>四个数的**字面值**没有意义（0.15/0.35/0.65/5 抄一遍就行）。有意义的是：
 * <ol>
 *   <li>接口返回的 = {@code sys_setting} 里存的（不是常量、不是另一份默认值）；</li>
 *   <li>**且检索路径读到的也是同一个源** —— 这一条是 `T-706` 开发中查出来的真缺陷：
 *       {@code SystemSettingService.KEYS} 一直在查 {@code retrieval.rerank-top-k}，
 *       而 {@code RetrievalParams.resolve} 读的是 Java 常量 {@code KbConfigDefaults.RERANK_TOP_K}
 *       （该键**取了却没用**）。两个源当时都等于 5，所以没有任何现象 —— 直到
 *       "把全站 rerankTopK 改成 7" 这件事同时体现在接口与检索上，本用例才发现它们分叉。</li>
 * </ol>
 *
 * <h3>为什么要动真库（而不是构造一个 Snapshot）</h3>
 * <p>构造 Snapshot 只能证明"Service 会读我喂给它的 map"，证明不了"这个键真的在
 * {@code sys_setting} 里、类型真的是 INT、种子真的种上了"。本用例走**真 HTTP +
 * 真 UPDATE + 真读回**，用完在 {@code finally} 里还原并**断言还原成功** ——
 * 不做"原值回写就算 no-op"那种静默拉平（本仓已有先例）。
 *
 * <p>改的是 {@code retrieval.rerank-top-k} 这一个键：它在 `T-706` 之前
 * **没有任何检索侧读者**，所以改动窗口内不会影响别的用例；还原后逐字校验。
 */
class SystemThresholdFlowTest extends ApiTestSupport {

    private static final String PATH = "/api/system/thresholds";
    private static final String RERANK_TOP_K_KEY = SystemSettings.RETRIEVAL_RERANK_TOP_K;

    @Autowired
    private SystemSettingService systemSettingService;

    // ==================== 判据 5 · 接口返回四字段 ====================

    @Test
    @DisplayName("T-706 · 四字段与 sys_setting 逐字一致（不是抄来的常量）")
    void thresholdsMirrorGlobalSettings() {
        String token = loginAsAdmin().data("$.accessToken");
        RawResponse response = get(PATH, token).assertOk("读取全局阈值");

        assertThat((Double) response.data("$.recallThreshold"))
                .as("recallThreshold 必须等于 sys_setting 里的值")
                .isEqualTo(settingAsDouble(SystemSettings.RETRIEVAL_RECALL_THRESHOLD));
        assertThat((Double) response.data("$.missThreshold"))
                .isEqualTo(settingAsDouble(SystemSettings.RETRIEVAL_MISS_THRESHOLD));
        assertThat((Double) response.data("$.lowScoreWarn"))
                .isEqualTo(settingAsDouble(SystemSettings.RETRIEVAL_LOW_SCORE_WARN));
        assertThat((Integer) response.data("$.rerankTopK"))
                .isEqualTo((int) settingAsDouble(SystemSettings.RETRIEVAL_RERANK_TOP_K));
    }

    @Test
    @DisplayName("T-706 · I-11 在线上配置里成立：missThreshold > recallThreshold")
    void missLineIsAboveRecallThresholdOnLiveData() {
        String token = loginAsAdmin().data("$.accessToken");
        RawResponse response = get(PATH, token).assertOk("读取全局阈值");

        double recall = response.data("$.recallThreshold");
        double miss = response.data("$.missThreshold");
        assertThat(miss)
                .as("I-11：未命中判定线必须严格高于召回阈值，否则未命中无从判定。"
                        + "线上配置违反了它 —— 检索侧会拒绝并抛 PARAM_INVALID，"
                        + "表现是『问什么都报参数错误』")
                .isGreaterThan(recall);
    }

    // ==================== 判据 7 · 与库级配置无关 + 与检索同源 ====================

    @Test
    @DisplayName("T-706 · 改 sys_setting 后接口与检索路径**同时**变化（两处同源）")
    void endpointAndRetrievalReadTheSameSource() {
        String token = loginAsAdmin().data("$.accessToken");
        String original = jdbc.queryForObject(
                "select setting_value from sys_setting where setting_key = ?", String.class, RERANK_TOP_K_KEY);

        try {
            jdbc.update("update sys_setting set setting_value = ? where setting_key = ?",
                    "7", RERANK_TOP_K_KEY);

            RawResponse response = get(PATH, token).assertOk("改配置后重读");
            assertThat((Integer) response.data("$.rerankTopK"))
                    .as("接口报的必须是改后的值 —— 报 5 说明它读的是常量，不是 sys_setting")
                    .isEqualTo(7);

            // 关键的一半：检索路径也得跟着变。
            // `null` 库配置 = "用全站默认值"的那条分支，正是 `retrieval.rerank-top-k` 的用武之地。
            RetrievalParams params = RetrievalParams.resolve(null, systemSettingService.snapshot());
            assertThat(params.rerankTopK())
                    .as("检索路径必须读同一个键。两条路各读一份真值 = "
                            + "「运营页显示 7、检索实际用 5」，且两边都不报错")
                    .isEqualTo(7);
        } finally {
            jdbc.update("update sys_setting set setting_value = ? where setting_key = ?",
                    original, RERANK_TOP_K_KEY);
        }

        // 还原要**验证**，不是回写就算完
        assertThat(jdbc.queryForObject(
                "select setting_value from sys_setting where setting_key = ?", String.class, RERANK_TOP_K_KEY))
                .as("用例必须把 sys_setting 还原成原值，否则污染后续用例")
                .isEqualTo(original);
        assertThat(RetrievalParams.resolve(null, systemSettingService.snapshot()).rerankTopK())
                .as("还原后检索侧也回到原值").isEqualTo(Integer.parseInt(original));
    }

    /**
     * 口径守卫：本接口**不接受任何库参数**。
     *
     * <p>它返回的是"覆盖发生之前"的全站默认值；一旦有人给它加个 {@code kbId}（很自然的
     * "顺手"），同名不同义就会从这里开始蔓延 —— 而加了之后接口仍然 200、页面仍然好看，
     * 没有任何现象。用反射钉住，与 {@code AuditQueryFlowTest#noMutatingEndpoints}
     * 是同一种守卫形态。
     */
    @Test
    @DisplayName("T-706 · 口径守卫：接口不接受 kbId（加了就是同名不同义）")
    void thresholdsEndpointTakesNoKbParameter() throws Exception {
        Method method = SystemController.class.getMethod("thresholds");
        assertThat(method.getParameterCount())
                .as("GET /system/thresholds 必须无参 —— 它是全站默认值，不是某个库的值")
                .isZero();
    }

    // ==================== 判据 6 · 未登录 401 ====================

    @Test
    @DisplayName("T-706 · 未登录 → 401（不是 200 空体）")
    void unauthenticatedIsRejected() {
        RawResponse response = get(PATH);

        assertThat(response.status())
                .as("「只要求登录」由 SecurityConfig 的 anyRequest().authenticated() 兜住，"
                        + "没带 token 必须 401。返回 200 + 空 data 会让前端把 undefined 渲染成空白")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("T-706 · 普通登录用户可读（它是展示数据源，不挂权限码）")
    void anyLoggedInUserCanRead() {
        String token = createUser("threshold").accessToken();
        get(PATH, token).assertOk("普通用户读取全局阈值");
    }

    // ==================== 工具 ====================

    /** 直读 {@code sys_setting} 的原值，供逐字比对（不经过任何应用层缓存）。 */
    private double settingAsDouble(String key) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select setting_value from sys_setting where setting_key = ?", key);
        assertThat(rows)
                .as("键 %s 必须在 sys_setting 里有行（缺行时接口会兜底到默认值，"
                        + "那时本用例比的就不是真值了 —— V2 种子应已种上）", key)
                .hasSize(1);
        return Double.parseDouble(String.valueOf(rows.getFirst().get("setting_value")).trim());
    }
}
