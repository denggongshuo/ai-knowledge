package com.knowledge.ai.pipeline.enhance;

import com.knowledge.ai.core.constant.CacheConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `EnhancePrompts` 的**措辞契约**（`S9-M12-C` 步 15 · O 轨「锚定率」）。
 *
 * <h3>🔴 这条判据是"退而求其次"，如实说明它换掉了什么</h3>
 * <p>计划里本步的取证方式是"改动前后各跑一次 {@code RelationAnchorRateProbeTest}，如实报两个数"。
 * <b>那个方案执行不了</b>：探针用的是一个给死 8 条关系的**替身模型**
 * （{@code RelationAnchorRateProbeTest.StubModel#stubChatCaller}），
 * 它压根不读提示词 —— 提示词怎么改，那 8 条关系与它们的锚定结果一个字节都不变。
 * <p>所以这里换成一个**能真的转红**的判据：提示词字符串里必须写着那条规则。
 * 它证明的**只是**"规则还在提示词里"，**不**证明模型会照做 ——
 * 后者只有真模型可用时才量得出来（`S9-M12-C` §9）。
 *
 * <h3>为什么值得为一句措辞写一个用例</h3>
 * <p>因为它**不是一个普通的句子**，而是 `RelationAnchor` 能不能锚上的前置条件：
 * 锚定规则是"实体名在块文本里的**首次出现**"，所以被同义改写过的实体名
 * 在正文里根本不存在 ⇒ 那条关系被丢弃 ⇒ 图上没有边 ⇒ `G9-4` 的分题型验收失去基础。
 * 而它住在 Java 字符串里，一次"顺手润色提示词"就能把它删掉，
 * 删掉之后**没有任何测试会红**（关系照旧抽出来，只是锚不上）。
 * 本仓对"静默失效"的一贯处置就是：钉一条能被推翻的判据。
 */
class EnhancePromptsTest {

    @Test
    @DisplayName("文档级提示词要求实体名**逐字照抄**，并点名禁止同义改写 / 缩写")
    void relationsMustBeCopiedVerbatim() {
        String prompt = EnhancePrompts.documentLevel("正文：路由表由配置中心下发。", false);

        assertThat(prompt)
                .as("🔴 把 `EnhancePrompts` 要求 3 里那句「必须是内容里逐字出现过的原词」删掉 ⇒ "
                        + "本断言当场转红。删掉它的后果不是报错，而是"
                        + "**关系照旧抽出来、只是锚不上正文**（图上没有边，且无处报警）")
                .contains("逐字")
                .contains("照抄");
        assertThat(prompt)
                .as("三类改写要逐个点名 —— 只说'逐字'，模型容易理解成'用词要准确'，"
                        + "而它真实的默认行为就是顺手换个更通用的说法")
                .contains("同义词").contains("缩写").contains("全称");
        assertThat(prompt)
                .as("必须写出**为什么**（会被回源到正文），否则下一次润色提示词的人"
                        + "看不出这条是硬约束还是风格建议")
                .contains("回源");
    }

    @Test
    @DisplayName("两个版本号一起 +1（改了模板却没改缓存版本 = 旧缓存继续命中）")
    void bothPromptVersionsMoveTogether() {
        assertThat(EnhancePrompts.VERSION).as("产出物标签").isEqualTo("doc-enhance-v4");
        assertThat(CacheConstants.PROMPT_VERSION)
                .as("🔴 缓存 key 的版本段。它与上面那个是**两个不同的东西**，"
                        + "但改提示词时两者都要动：只改前者的话 `ak:enhance:v3:*` 的旧缓存"
                        + "继续命中，表现为「改了提示词却没生效」——本仓踩过一次"
                        + "（`CacheConstants` 的 javadoc 记着那一笔）")
                .isEqualTo("v4");
    }
}
