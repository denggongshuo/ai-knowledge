package com.knowledge.ai.core.ai;

import com.knowledge.ai.core.enums.ModelType;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-014 验收：模型读取、客户端缓存与失效、来源不可用时的兜底。
 *
 * <p><b>为什么这组用例必须存在</b>：T-014 的验收原话是「业务模块只经此取模型，
 * 不自己 new 客户端」，但真正容易写错、也最容易在 S6 暴露问题的是**缓存与失效的边界**：
 * 缓存键少一段 → 改了配置不生效；失效范围过大 → 改了 A 模型把 B 的客户端也清掉
 * （需求点名的场景）。这两类 bug 都"运行时不报错"，只能靠断言挡住。
 *
 * <p>分两半：
 * <ul>
 *   <li><b>带 Spring 上下文的一半</b>：验证真实装配出的客户端会被缓存、失效后会重建。</li>
 *   <li><b>纯单元的一半</b>：用手写的 {@link ModelConfigSource} 构造注册中心，
 *       精确摆布"来源抛错"与"来源返回空"两种情形 ——
 *       这两条分支在真实环境里很难复现（要真把 DB 拔掉），但语义差别很大，必须钉住。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ModelClientRegistryTest {

    @Autowired
    private ModelClientRegistry registry;

    @Autowired
    private ModelProperties modelProperties;

    // ==================================================================
    // 缓存与失效（需要真实客户端）
    // ==================================================================

    @Test
    @DisplayName("T-014：同一规格重复取客户端 → 命中缓存，返回同一个实例")
    void chatClientIsCached() {
        Assumptions.assumeTrue(modelProperties.getChat().hasCredentials(),
                "未配置 chat 端点，跳过缓存断言");

        ChatClient first = registry.chatClient();
        ChatClient second = registry.chatClient();

        assertSame(first, second, "相同规格应命中同一个缓存实例 —— 否则每次请求都在重建客户端");

        ModelConfigSpec spec = registry.activeSpec(ModelType.CHAT);
        assertTrue(registry.peekCached(spec).isPresent(),
                "取过之后缓存里应当有 " + spec.cacheKey());
    }

    @Test
    @DisplayName("T-014：失效指定模型后重建，且只影响该模型")
    void invalidateRebuildsOnlyThatModel() {
        Assumptions.assumeTrue(modelProperties.getChat().hasCredentials(),
                "未配置 chat 端点，跳过失效断言");

        ModelConfigSpec chatSpec = registry.activeSpec(ModelType.CHAT);
        ChatClient before = registry.chatClient();
        assertSame(before, registry.chatClient(), "前置条件：已缓存");

        int removed = registry.invalidate(ModelType.CHAT, chatSpec.id());
        assertEquals(1, removed, "应当只清掉这一个模型配置的缓存");
        assertTrue(registry.peekCached(chatSpec).isEmpty(), "失效后缓存里不应再有它");

        ChatClient after = registry.chatClient();
        assertNotSame(before, after, "失效后应重建出新的客户端");

        // 「只影响该模型」的反面：其它类型（如已配置的嵌入）不受影响
        if (modelProperties.getEmbedding().hasCredentials()) {
            ModelConfigSpec embeddingSpec = registry.activeSpec(ModelType.EMBEDDING);
            registry.embeddingModel();
            assertTrue(registry.peekCached(embeddingSpec).isPresent(),
                    "清 CHAT 的缓存不应把 EMBEDDING 的也清掉 —— 这正是需求点名的场景");
        }
    }

    @Test
    @DisplayName("T-014：嵌入是全局单例 —— 缓存命中且无「按 id 取」的口子")
    void embeddingIsSingleton() {
        Assumptions.assumeTrue(modelProperties.getEmbedding().hasCredentials(),
                "未配置 embedding 端点，跳过单例断言");

        EmbeddingModel first = registry.embeddingModel();
        assertSame(first, registry.embeddingModel(), "嵌入模型必须复用同一实例（全局单例）");

        // 反射确认没有 embeddingModel(String) 这类重载 —— 嵌入不可按库切换（决策 D-4）
        boolean hasIdOverload = java.util.Arrays.stream(ModelClientRegistry.class.getMethods())
                .anyMatch(m -> m.getName().equals("embeddingModel") && m.getParameterCount() > 0);
        assertFalse(hasIdOverload,
                "注册中心不应提供「按 id 取嵌入模型」的口子 —— 嵌入全局单例，换它等于全库重建");
    }

    @Test
    @DisplayName("T-014：状态概览可读，且不含任何密钥")
    void statusIsReadableAndSecretFree() {
        Assumptions.assumeTrue(modelProperties.getChat().hasCredentials(), "未配置 chat 端点，跳过");

        var status = registry.status();
        String text = status.toString();

        // `S6-M09-A`（2026-09-18）起来源**就是** DB 了 —— 本条断言当时写的
        // 「S6 接 DB 后这里会变成 db」正是现在发生的事。改动只有这一处常量：
        // 语义（"状态里要说清当前用的是哪个来源"）没变，它才是这条判据要守的东西
        assertEquals("db(sys_model_config)", status.get("source"),
                "S6 起模型口径的真相源是 sys_model_config；引导配置退化为种子与密钥来源");
        assertTrue(text.contains(ModelType.CHAT.name()), "状态里应能看到各类模型的解析结果");
        assertFalse(text.contains(modelProperties.getChat().getApiKey()),
                "状态里出现了真实密钥 —— G0-8 要求一律只报「已配置」");
        assertTrue(text.contains("key=已配置"), "应当用「已配置」表达有无密钥");
    }

    @Test
    @DisplayName("T-014：取不存在的模型 id 给出明确业务错误，而不是空指针")
    void unknownModelIdFailsClearly() {
        BizException ex = assertThrows(BizException.class,
                () -> registry.specById(ModelType.CHAT, "no-such-model-id"));
        assertEquals(ErrorCode.MODEL_NOT_CONFIGURED, ex.getErrorCode());
    }

    // ==================================================================
    // 来源兜底语义（纯单元，不需要 Spring）
    // ==================================================================

    @Test
    @DisplayName("T-014：配置来源抛错 → 回退「最近一次有效配置」，不打断调用方")
    void fallsBackToLastKnownGoodWhenSourceFails() {
        ModelConfigSpec spec = new ModelConfigSpec(
                "m1", ModelType.CHAT, "https://example.test/v1", "k", "some-model", "v1", true,
                ModelConfigSpec.GenerationParams.defaults());

        ModelConfigSource flaky = new ModelConfigSource() {
            private int calls = 0;

            @Override
            public List<ModelConfigSpec> listEnabled(ModelType type) {
                calls++;
                if (calls == 1) {
                    return List.of(spec);
                }
                throw new IllegalStateException("模拟 DB 抖动");
            }

            @Override
            public String sourceName() {
                return "flaky";
            }
        };

        // clientFactory 传 null：本用例只验规格解析，不构造客户端
        ModelClientRegistry unit = new ModelClientRegistry(flaky, null);

        assertSame(spec, unit.activeSpec(ModelType.CHAT), "第一次：来源正常");
        assertSame(spec, unit.activeSpec(ModelType.CHAT),
                "第二次：来源抛错时应回退最近一次有效配置 —— 配置中心抖动不该让全站问答中断");
    }

    @Test
    @DisplayName("T-014：来源正常但返回空 → 显式报错，**不**拿旧配置顶（那是两回事）")
    void emptyConfigFailsInsteadOfFallingBack() {
        ModelConfigSpec spec = new ModelConfigSpec(
                "m1", ModelType.CHAT, "https://example.test/v1", "k", "some-model", "v1", true,
                ModelConfigSpec.GenerationParams.defaults());

        ModelConfigSource disappearing = new ModelConfigSource() {
            private int calls = 0;

            @Override
            public List<ModelConfigSpec> listEnabled(ModelType type) {
                calls++;
                // 第一次有配置（进入 lastKnownGood），之后配置被删了 —— 来源本身是好的
                return calls == 1 ? List.of(spec) : List.of();
            }

            @Override
            public String sourceName() {
                return "disappearing";
            }
        };

        ModelClientRegistry unit = new ModelClientRegistry(disappearing, null);

        assertSame(spec, unit.activeSpec(ModelType.CHAT));

        BizException ex = assertThrows(BizException.class, () -> unit.activeSpec(ModelType.CHAT),
                "配置被删掉时继续用旧配置，会让页面显示的模型与实际调用的模型不一致");
        assertEquals(ErrorCode.MODEL_NOT_CONFIGURED, ex.getErrorCode());
    }

    @Test
    @DisplayName("T-014：来源不可用且无兜底 → 报明确的业务错误，带上原因")
    void failsWithClearMessageWhenNoFallback() {
        ModelConfigSource broken = new ModelConfigSource() {
            @Override
            public List<ModelConfigSpec> listEnabled(ModelType type) {
                throw new IllegalStateException("连接池已耗尽");
            }

            @Override
            public String sourceName() {
                return "broken";
            }
        };

        ModelClientRegistry unit = new ModelClientRegistry(broken, null);
        BizException ex = assertThrows(BizException.class, () -> unit.activeSpec(ModelType.EMBEDDING));
        assertEquals(ErrorCode.MODEL_NOT_CONFIGURED, ex.getErrorCode());
    }

    @Test
    @DisplayName("T-014：每个 ModelType 都能映射到一种端点，不存在「漏配一类」的静默分支")
    void everyModelTypeIsHandled() {
        // 引导配置对四类都返回至多一条：只要没配就对不上，不该出现 NullPointerException
        for (ModelType type : ModelType.values()) {
            List<ModelConfigSpec> specs = new BootstrapModelConfigSource(modelProperties).listEnabled(type);
            assertTrue(specs.size() <= 1, type + " 的引导配置至多一条");
            specs.forEach(spec -> assertEquals(type, spec.type()));
        }
    }
}
