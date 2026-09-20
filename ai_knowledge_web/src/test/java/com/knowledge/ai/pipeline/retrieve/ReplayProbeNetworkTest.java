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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **步 0 探针（`T-A01` 重放验证）**：补录之后，分数**真的会跨过判定线**吗。
 *
 * <h3>为什么这条必须在写业务代码之前跑</h3>
 * <p>上一批（`T-A00`）量的是「池子会不会有对象」——结论是判定线夹在相关（`0.964`）与
 * 不相关（`0.000016`）之间，**有区分力**。本批要量的是它的**另一半**：
 * 重放验证的「达标自动关闭」这条边，要求「把一份真答得上该问题的文档补进库之后，
 * 重跑同一个问题，Top1 会落到判定线**之上**」。
 *
 * <p>这条如果不成立，整条闭环的**成功分支根本走不到** —— 而现象是
 * 「每条都退回待处理」，与"补的文档确实没用"**长得一模一样**。本仓已经栽过七次
 * 同一形态（最近一次是上一批的 `miss_flag` 恒 0 风险），所以这次照旧：**先量再做**。
 *
 * <h3>量的是哪一档（本批唯一可能被否掉的前提）</h3>
 * <p>补录进库的文档**会被切成块**，重排比较的是「问句 × 单个块」。所以探针给的候选
 * 不是"一整篇文档"，而是**一个块的形状**（一两百字、可能带标题路径）。四档：
 * <ol>
 *   <li>完全无关（企业制度域的一条）—— 复现"入池"那一侧；</li>
 *   <li><b>只提到关键词、但不回答</b> —— 真实语料里最常见的那一档。它落在线上还是线下，
 *       决定了判定线对"提到了"与"答上了"是否分得开；</li>
 *   <li><b>真的回答了</b>（推导过程写全）—— 🔴 <b>本批的前提就在这一档</b>；</li>
 *   <li>同一篇补录文档里的**另一段**（不回答该问题）—— 它是判据 4 那条负向锚的现实形状：
 *       "文档进库了，但召回的是别的段"。</li>
 * </ol>
 *
 * <h3>与 {@code @Tag("network")} 的约定</h3>
 * <p>依赖外网 + `.env` 里的 key：未配置时 {@link Assumptions} **跳过而非失败**
 * （与 {@link MissProbeNetworkTest} / {@code AiCallVerificationTest} 同一处理）。
 * 要完全排除：{@code -DexcludedGroups=network}。
 *
 * <p>本类**只读**：不写库、不写桶、不写 MinIO —— 探针不许留下要清理的东西。
 */
@Tag("network")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReplayProbeNetworkTest {

    /** 探针问句：**离种子里那几个库（人力 / 合同 / 售后）足够远**，所以它必然未命中。 */
    private static final String QUESTION = "量子纠缠里的贝尔不等式是怎么推导出来的？";

    /** 候选，顺序即 {@link RerankClient.RerankHit#index()} 的下标。 */
    private static final List<Candidate> CANDIDATES = List.of(
            new Candidate("① 完全无关", "差旅费用报销应在返回后十五个工作日内提交，发票需与行程单一致。"),

            new Candidate("② 只提到不回答",
                    "第 3 章 量子计算概述：本章介绍量子比特、量子门与量子纠缠的基本概念，"
                            + "并给出贝尔不等式的历史背景与命名由来；推导过程不在本章范围内，"
                            + "读者可参考量子信息方向的专业文献。"),

            new Candidate("③ 真的回答了",
                    "贝尔不等式的推导从 EPR 佯谬出发。假设存在局域隐变量 λ，则两个粒子的测量结果"
                            + "由 λ 与各自的测量方向唯一决定，联合概率可分解为 p(a,b|λ)=p(a|λ)p(b|λ)。"
                            + "由此写出关联函数 E(a,b)=∫dλ ρ(λ)A(a,λ)B(b,λ)，并考察"
                            + "S=E(a,b)−E(a,b′)+E(a′,b)+E(a′,b′)。由于每一项的绝对值不超过 1，"
                            + "可以证明 |S|≤2，这就是 CHSH 形式的贝尔不等式。而量子力学对自旋单态"
                            + "给出 E(a,b)=−a·b，取四个方向夹角 45° 时算得 S=2√2，大于 2，"
                            + "因此局域隐变量理论被实验证伪。"),

            new Candidate("④ 同篇的另一段",
                    "第 4 章 实验实现：本章介绍用纠缠光子对检验贝尔不等式的典型光路，"
                            + "包括自发参量下转换光源、偏振分析与符合计数，以及各类实验漏洞"
                            + "（探测效率漏洞、局域性漏洞）及其闭合方案。"));

    private record Candidate(String label, String text) {
    }

    @Autowired
    private ModelClientRegistry modelClientRegistry;

    @Autowired
    private SystemSettingService systemSettingService;

    @Test
    @DisplayName("补录一份真答得上的文档后，重放分能跨过判定线 —— 闭环的成功分支走得到")
    void supplementCanClearTheThreshold() {
        RerankClient client = modelClientRegistry.rerankClient();
        Assumptions.assumeTrue(!(client instanceof NoOpRerankClient),
                "未配置重排模型，跳过探针（这本身也是一种结论：重放拿不到分，闭环走不通）");

        double threshold = systemSettingService.snapshot()
                .decimal(SystemSettings.RETRIEVAL_MISS_THRESHOLD, SystemSettings.DEFAULT_MISS_THRESHOLD);

        Map<Integer, Double> scores = scoreOf(client, CANDIDATES.stream().map(Candidate::text).toList());

        StringBuilder report = new StringBuilder();
        report.append(String.format("%n===== T-A01 步 0 探针 =====%n"))
                .append(String.format("客户端      ：%s%n", client.describe()))
                .append(String.format("判定线      ：%.6f%n", threshold))
                .append(String.format("问句        ：%s%n", QUESTION));
        for (int i = 0; i < CANDIDATES.size(); i++) {
            Double score = scores.get(i);
            report.append(String.format("  %s：%s%n", CANDIDATES.get(i).label(),
                    score == null ? "（模型未返回该候选）"
                            : String.format("%.6f   %s", score, score >= threshold ? "≥ 线" : "< 线")));
        }
        report.append("===========================").append(System.lineSeparator());
        System.out.print(report);

        Double unrelated = scores.get(0);
        Double answered = scores.get(2);
        Assumptions.assumeTrue(unrelated != null && answered != null,
                "模型没把全部候选都返回，本探针无法下结论（top_n 行为异常）");

        assertTrue(unrelated < threshold,
                "① 无关候选（" + unrelated + "）没有低于判定线（" + threshold + "）⇒ 探针的基准不对");

        // 🔴 本批唯一可能被否掉的前提：补录一篇"真答得上"的文档 ⇒ 分要跨过线。
        // 证伪时的分支（计划 §3.4 假设 7）：E2E 换语料，或把判据退成
        // "分数提升 + 命中补录文档"（不含达标）。
        assertTrue(answered >= threshold,
                "③ 真答得上的候选（" + answered + "）仍低于判定线（" + threshold + "）"
                        + "⇒ 「补录 ⇒ 达标自动关闭」这条边走不到：重放永远退回待处理，"
                        + "而它与「补的文档确实没用」长得一模一样。改判据或换语料，见计划 §3.4 假设 7");
    }

    /** 一次 rerank 拿到**全部**候选的分（下标 → 分）。 */
    private static Map<Integer, Double> scoreOf(RerankClient client, List<String> candidates) {
        List<RerankClient.RerankHit> hits = client.rerank(QUESTION, candidates, candidates.size());
        Map<Integer, Double> scores = new LinkedHashMap<>();
        for (RerankClient.RerankHit hit : hits) {
            scores.put(hit.index(), hit.score());
        }
        return scores;
    }
}
