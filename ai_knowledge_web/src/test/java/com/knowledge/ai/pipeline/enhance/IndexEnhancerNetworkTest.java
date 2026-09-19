package com.knowledge.ai.pipeline.enhance;

import com.knowledge.ai.core.ai.ModelClientRegistry;
import com.knowledge.ai.core.ai.ModelProperties;
import com.knowledge.ai.pipeline.Block;
import com.knowledge.ai.pipeline.ParsedDocument;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-507` 的**真实模型调用**冒烟 —— 单独一个类，因为它是唯一需要 Spring 容器与网络的。
 *
 * <h3>为什么必须与 {@link IndexEnhancerTest} 分开</h3>
 * <p>那边 11 条是纯组装逻辑，毫秒级、可复现；把它们和 {@code @SpringBootTest} 放进同一个类，
 * 每次跑都要起一次容器、而且一旦网络抖动整类都红 —— 而红的那些其实与网络无关。
 * **把"会不会因为外部状态变红"划在类边界上**，比划在方法边界上可靠：
 * 方法级的 {@code Assumptions} 只保护自己，类里的其它用例照旧被容器启动成本拖累。
 *
 * <h3>按本仓既有约定跳过</h3>
 * <p>{@code @Tag("network")} + {@code assumeTrue(hasCredentials())} —— 与
 * {@code AiCallVerificationTest} 同一形状。⚠️ 但要知道这意味着什么：
 * **本仓 surefire 没有 {@code excludedGroups}**，所以只要配了密钥，这条在默认跑里
 * 会真的打出去（本机走 relay）。没配就跳过，而不是失败。
 */
@SpringBootTest
@Tag("network")
@TestPropertySource(properties = "m05.enhance.enabled=true")
class IndexEnhancerNetworkTest {

    @Autowired
    private IndexEnhancer indexEnhancer;

    @Autowired
    private ModelClientRegistry registry;

    @Autowired
    private ModelProperties modelProperties;

    @Test
    @DisplayName("冒烟：真实对话模型能产出可用的文档级增强（摘要 + 标签 + 关系）")
    void realModelProducesUsableEnhancement() {
        Assumptions.assumeTrue(modelProperties.getChat().hasCredentials(),
                () -> "未配置 chat 端点（" + modelProperties.getChat().describe() + "），跳过真实增强调用");

        List<ParsedDocument.DocElement> elements = List.of(
                ParsedDocument.DocElement.heading(1, "3. 接口设计", 1),
                ParsedDocument.DocElement.paragraph(
                        "接口鉴权采用令牌方式。客户端先调 /auth/token 换取访问令牌，"
                                + "令牌有效期 2 小时；过期后用刷新令牌重新换取。"
                                + "刷新令牌有效期 7 天，过期必须重新登录。", 1),
                ParsedDocument.DocElement.heading(2, "3.1 错误码", 1),
                ParsedDocument.DocElement.paragraph(
                        "令牌失效返回错误码 10021，刷新令牌失效返回 10022。", 1));
        ParsedDocument doc = new ParsedDocument("PDF", 1, 120, "", List.of(), List.of(),
                2, 0, List.of(), elements);

        IndexEnhancer.Result result = indexEnhancer.enhance(doc, List.of(
                new Block("接口鉴权采用令牌方式…", com.knowledge.ai.core.enums.BlockType.PARAGRAPH,
                        "3. 接口设计", 1, 1, null, null, null)));

        // 失败时把 detail 带出来：真实调用出错与"输出格式不合约定"是两回事，
        // 断言里不写清原因，下一次只能靠重跑猜
        assertThat(result.status())
                .as("真实增强未成功：%s（提示词 %s）", result.detail(), EnhancePrompts.VERSION)
                .isEqualTo(IndexEnhancer.Status.OK);
        assertThat(result.enhancement().summary())
                .as("摘要不能是敷衍的一句 —— 这里只做长度下限，质量要人工抽查"
                        + "（计划 §3.4 假设 3：摘要质量无法自动评分）")
                .hasSizeGreaterThan(20);
        assertThat(result.blocks().getLast().blockType())
                .isEqualTo(com.knowledge.ai.core.enums.BlockType.SUMMARY);

        System.out.printf("%n===== 真实文档级增强（T-507 冒烟）=====%n"
                        + "  模型=%s%n  摘要=%s%n  标签=%s%n  关系数=%d%n",
                registry.activeSpec(com.knowledge.ai.core.enums.ModelType.CHAT).describe(),
                result.enhancement().summary(),
                result.enhancement().labels(),
                result.enhancement().relations().size());
    }
}
