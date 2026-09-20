package com.knowledge.ai.pipeline.retrieve;

import com.knowledge.ai.core.ai.ModelClientRegistry;
import com.knowledge.ai.core.ai.NoOpRerankClient;
import com.knowledge.ai.core.ai.RerankClient;
import com.knowledge.ai.core.setting.SystemSettingService;
import com.knowledge.ai.core.setting.SystemSettings;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **步 0 探针（`T-A00`）**：未命中池**会不会有对象**。
 *
 * <h3>为什么这条必须在写业务代码之前跑</h3>
 * <p>{@code miss_flag = 1} 的成立条件是三条**缺一不可**
 * （{@link RetrievalService} 的 {@code RerankOutcome#miss}）：重排**真的跑过** + **有候选** +
 * 最高分 **< {@code missThreshold}**。任何一条不成立，池子就永远是空的 ——
 * 而「池子空」与「确实没人问倒过」**长得一模一样**。
 *
 * <p>本仓已经在同一形态上栽过六次（最近一次是「换 VL 模型没有要处理的对象」）。
 * 所以这一次**先量再做**：量的是两件事 ——
 * <ol>
 *   <li>这条链路上拿到的是**真客户端**还是 {@link NoOpRerankClient}（模型配置在不在）；</li>
 *   <li>一个**明显不相关**的问句，它的 top 分**真的会低于判定线**吗（判定线有没有意义）。</li>
 * </ol>
 *
 * <h3>为什么是「一对差分」而不是一个观测</h3>
 * <p>只测不相关问句「分数低」是不够的：如果模型对**任何**输入都给低分，那
 * 「低于判定线」就没有区分力 —— 未命中池会把**每一次提问**都收进去。
 * 所以同时取一个**相关**问句，两个数一起看：
 * <pre>
 *   相关 &gt; 判定线 &gt; 不相关
 * </pre>
 * 夹在中间才说明这条判定线**真的在分东西**。这也是「阈值先量再定」那条纪律的做法：
 * 探针的产出常常是**否掉**一个数，而不是确认它。
 *
 * <h3>与 {@code @Tag("network")} 的约定</h3>
 * <p>依赖外网 + `.env` 里的 key：未配置时 {@link Assumptions} **跳过而非失败**
 * （与 {@code AiCallVerificationTest} / {@code IndexEnhancerNetworkTest} 同一处理）。
 * 要完全排除：{@code -DexcludedGroups=network}。
 *
 * <p>本类**只读**，不写库、不写桶 —— 探针不许留下要清理的东西。
 */
@Tag("network")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MissProbeNetworkTest {

    /** 候选语料：企业制度域，与种子里那几个库（人力 / 合同 / 售后）同一题材。 */
    private static final List<String> DOCUMENTS = List.of(
            "员工出差前须在系统提交出差申请，经直属主管审批后方可出行。",
            "差旅费用报销应在返回后十五个工作日内提交，发票需与行程单一致。",
            "年休假按工龄分档：满一年不满十年的，每年五天。",
            "请假需提前一个工作日在系统提交，紧急情况可事后补办。",
            "加班可申请调休，调休有效期至次年三月底。");

    private static final String RELATED = "员工出差报销的发票要在几天内提交？";

    private static final String UNRELATED = "量子纠缠的贝尔不等式怎么推导？";

    @Autowired
    private ModelClientRegistry modelClientRegistry;

    @Autowired
    private SystemSettingService systemSettingService;

    @Test
    @DisplayName("重排链路可用且判定线真的有区分力 —— 池子不是空的")
    void rerankDiscriminates() {
        RerankClient client = modelClientRegistry.rerankClient();
        Assumptions.assumeTrue(!(client instanceof NoOpRerankClient),
                "未配置重排模型，跳过探针（这本身也是一种结论：池子会是空的）");

        double missThreshold = systemSettingService.snapshot()
                .decimal(SystemSettings.RETRIEVAL_MISS_THRESHOLD, SystemSettings.DEFAULT_MISS_THRESHOLD);

        double related = topScore(client, RELATED);
        double unrelated = topScore(client, UNRELATED);

        System.out.printf("%n===== T-A00 步 0 探针 =====%n"
                        + "客户端      ：%s%n"
                        + "判定线      ：%.6f%n"
                        + "相关问句 top：%.6f   （%s）%n"
                        + "不相关 top  ：%.6f   （%s）%n"
                        + "=======================%n",
                client.describe(), missThreshold, related, RELATED, unrelated, UNRELATED);

        assertTrue(unrelated < missThreshold,
                "不相关问句的 top 分（" + unrelated + "）没有低于判定线（" + missThreshold
                        + "）⇒ 未命中池几乎收不进东西，T-A00 的闭环节点要重新口径");
        assertFalse(related < missThreshold,
                "相关问句的 top 分（" + related + "）也低于判定线（" + missThreshold
                        + "）⇒ 判定线**没有区分力**：它会把每一次提问都判成未命中");
    }

    private static double topScore(RerankClient client, String query) {
        List<RerankClient.RerankHit> hits = client.rerank(query, DOCUMENTS, 5);
        Assumptions.assumeFalse(hits.isEmpty(), "重排未返回任何结果，无法判定：" + query);
        return hits.get(0).score();
    }
}
