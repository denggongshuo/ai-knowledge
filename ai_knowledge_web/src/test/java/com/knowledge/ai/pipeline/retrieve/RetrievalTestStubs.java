package com.knowledge.ai.pipeline.retrieve;

import com.knowledge.ai.core.ai.ModelClientFactory;
import com.knowledge.ai.core.text.IdentifierTokenizer;
import com.knowledge.ai.pipeline.vectorize.EmbeddingClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 检索流程用例的替身（`S5-M07-A`）。
 *
 * <h3>⚠️ 为什么是**顶层类 + 显式 {@code @Import}**，而不是 {@code RetrievalFlowSupport} 的嵌套类</h3>
 * <p>这是本批踩到的一个真坑，写在这里免得下一个人再踩一遍：
 * Spring Boot **不会**自动发现**父类**里声明的嵌套 {@code @TestConfiguration} 类
 * （它只扫测试类自身那一层）。后果不是"报错"，而是**静默**的：
 * 替身没注册，容器里剩下的那个 {@code RegistryEmbeddingClient} 就成了唯一候选，
 * 于是用例**真的去打模型网关**——测试仍然可能绿，但绿的原因是模型返回了什么，
 * 而不是本仓的链路对了，而且每次运行都在花钱与依赖网络。
 *
 * <p>本类的三个用例在改之前正是这样"绿"的。所以现在：
 * <ol>
 *   <li>替身是**顶层类**，由 {@code RetrievalFlowSupport} 的 {@code @Import} 显式引入
 *       （{@code @Import} 在父类上，子类天然继承，不依赖任何扫描约定）；</li>
 *   <li>{@link RetrievalFlowSupport} 在 {@code @BeforeEach} 里断言
 *       {@code EMBED_CALLS} 由替身累加 —— **替身没生效就当场转红**，
 *       而不是等到某天有人发现测试在调真模型。</li>
 * </ol>
 */
@TestConfiguration
public class RetrievalTestStubs {

    /**
     * 嵌入替身：token 哈希 + L2 归一化。
     *
     * <p>为什么不是"每条文本一个随机向量"：随机向量之间两两正交，
     * 于是"最相关的那条"完全由 Milvus 的内部次序决定，断言就没有意义了。
     * 声明式向量让"共享 token 多" ↔ "距离近"成为**可推导**的事实。
     *
     * <p>中文仍按 {@link IdentifierTokenizer} 的单字切（与写入侧同一个函数）——
     * 于是"提问 {@code A-100}"与"正文含 {@code A-100}"在这个空间里**天然靠近**，
     * 而"`A-100` 与 `A-1000`"是两个不相干的 token。
     */
    @Bean
    @Primary
    EmbeddingClient stubEmbeddingClient() {
        return texts -> {
            RetrievalFlowSupport.EMBED_CALLS.incrementAndGet();
            long delay = RetrievalFlowSupport.embedDelayMillis;
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            return texts.stream().map(RetrievalFlowSupport::hashedVector).toList();
        };
    }

    /**
     * 记录执行区间的检索执行器。
     *
     * <p>**它是一个独立 Bean，不覆盖 {@code AsyncConfig#retrievalExecutor}** ——
     * 覆盖同名 Bean 要放开 {@code allow-bean-definition-overriding}，而"谁最后注册谁赢"
     * 取决于配置类的处理顺序：那是一个**没写在任何地方**的隐式依赖，会在某次无关改动后
     * 悄悄失效（表现为 {@code INTERVALS} 为空、断言说"任务没跑"）。
     * 需要观察并行的用例改为**自己 new 一个 {@code HybridRetriever}}**（见
     * {@code RetrievalFlowSupport#retriever()}），把这个池子显式传进去。
     *
     * <p>线程数与拒绝策略与真身保持一致 —— 替身行为不同的话，
     * "这条用例绿了"就不能推出"线上是对的"。
     */
    @Bean
    @Primary
    Executor recordingRetrievalExecutor() {
        return newRecordingExecutor();
    }

    /**
     * 用例可替换的重排替身。默认**报告"不可用"** —— 于是 {@code RetrievalService}
     * 走「不重排」降级，这与"没配重排模型"的产品行为一致（只是 describe 说明了这是替身）。
     *
     * <p>刻意不用 {@code NoOpRerankClient.INSTANCE}：那个的 describe 是"未配置重排模型"，
     * 而本测试上下文里重排**是配置了的**（见基类的 {@code @TestPropertySource}）——
     * 用它会让人在日志里读到一个与事实不符的原因。
     */
    private static final com.knowledge.ai.core.ai.RerankClient UNAVAILABLE = new com.knowledge.ai.core.ai.RerankClient() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public String describe() {
            return "试替身：本次按不可用处理";
        }

        @Override
        public java.util.List<RerankHit> rerank(String query, java.util.List<String> documents, int topK) {
            return java.util.List.of();
        }
    };

    public static volatile com.knowledge.ai.core.ai.RerankClient rerankStub = UNAVAILABLE;

    /** 见 {@code rerankOverridingFactory}：每次调用都**现读** {@link #rerankStub}。 */
    private static final com.knowledge.ai.core.ai.RerankClient DELEGATE = new com.knowledge.ai.core.ai.RerankClient() {
        @Override
        public boolean available() {
            return rerankStub.available();
        }

        @Override
        public String describe() {
            return rerankStub.describe();
        }

        @Override
        public java.util.List<RerankHit> rerank(String query, java.util.List<String> documents, int topK) {
            return rerankStub.rerank(query, documents, topK);
        }
    };

    /** 每个用例前后复位成"不可用"，免得一个用例装上的替身漏给下一个。 */
    public static void resetRerank() {
        rerankStub = UNAVAILABLE;
    }

    /**
     * 把 {@code createRerank} 接管过来的客户端工厂。
     *
     * <h3>为什么必须替这一处，而不能像嵌入那样直接换 Bean</h3>
     * <p>{@code ModelClientRegistry#rerankClient()} 是**自己调工厂造**客户端（`T-014` 的
     * "注册中心是唯一构造点"纪律），它**不去容器里找** {@code RerankClient} Bean。
     * 所以想控制重排的返回值，只能从**工厂**这一层切进去。
     *
     * <h3>为什么不干脆让 {@code .env} 里的 RERANKER_API_* 缺席、走自然降级</h3>
     * <p>那样只能测"重排不可用"这一条分支，而 `G5-5` 要验的是
     * **重排真的跑过之后**、低于降权线的分片仍在结果里。那条分支必须先有分可打。
     *
     * <p>⚠️ 只覆盖 {@code createRerank}；对话与嵌入照旧走父类 ——
     * 替身覆盖得越宽，"用例绿了"能推出的结论就越少。
     */
    @Bean
    @Primary
    ModelClientFactory rerankOverridingFactory(
            org.springframework.beans.factory.ObjectProvider<io.micrometer.observation.ObservationRegistry> observations,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        // `S6-M09-A` 起 `ModelClientFactory` 不再持有 `ModelProperties` —— 生成参数
        // （temperature / maxTokens / timeoutSec / retries）改由 `ModelConfigSpec#params()`
        // 随模型走，见那边的 javadoc
        return new ModelClientFactory(observations, meterRegistry) {
            @Override
            public com.knowledge.ai.core.ai.RerankClient createRerank(
                    com.knowledge.ai.core.ai.ModelConfigSpec spec) {
                // ⚠️ 返回的是**转发器**，不是当时的 rerankStub：
                // `ModelClientRegistry` 按 `spec.cacheKey()` **缓存**客户端，而整个测试类共用
                // 一个 Spring 上下文 —— 直接返回当时的替身，就会被**第一个**用到重排的用例
                // 钉死，后面所有用例改 rerankStub 都不生效（现象是"改一行测试没反应"）。
                return DELEGATE;
            }
        };
    }

    /** 工厂方法：Bean 与用例里手工 new 的 {@code HybridRetriever} 共用同一个池子形状。 */
    static ThreadPoolTaskExecutor newRecordingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("ak-recall-test-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setTaskDecorator(task -> () -> {
            long start = System.nanoTime();
            try {
                task.run();
            } finally {
                RetrievalFlowSupport.INTERVALS.add(new RetrievalFlowSupport.Interval(
                        Thread.currentThread().getName(), start, System.nanoTime()));
            }
        });
        executor.initialize();
        return executor;
    }

    // ==================================================================
    // M08（`T-800`）的流式对话替身
    // ==================================================================

    /**
     * 流式对话替身被调用过几次。
     *
     * <p>`G5-11` 前半「空召回**不调大模型**」的唯一硬证据就是这个计数器为 0 ——
     * 光断言"回答了兜底话术"是不够的：真调了模型再把它的话丢掉，看起来一模一样。
     */
    public static final java.util.concurrent.atomic.AtomicInteger STREAM_CALLS =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 最后一次流式调用收到的提示词 —— 用来验"分片正文真的进了提示词"。 */
    public static volatile String LAST_PROMPT = "";

    /** 默认替身：回一段固定文本并按句切分，模拟真流式。 */
    private static final com.knowledge.ai.pipeline.chat.StreamingChatCaller DEFAULT_STREAM = canned(
            "这是替身模型的回答。它由两句话组成。");

    /** 用例可替换的流式替身。 */
    public static volatile com.knowledge.ai.pipeline.chat.StreamingChatCaller streamStub = DEFAULT_STREAM;

    /** 每个用例前后复位 —— 免得一个用例装的替身漏给下一个（与 {@link #resetRerank} 同理）。 */
    public static void resetStream() {
        streamStub = DEFAULT_STREAM;
        STREAM_CALLS.set(0);
        LAST_PROMPT = "";
    }

    /**
     * 造一个"按句吐字"的替身。
     *
     * <p>**按句切分而不是整段一次给**：`T-801` 的事件序列要验 `delta*`（可多次），
     * 一次性吐完的话"发了几个 delta"恒为 1，那条判据就退化成了摆设。
     */
    public static com.knowledge.ai.pipeline.chat.StreamingChatCaller canned(String answer) {
        return (prompt, onDelta) -> {
            LAST_PROMPT = prompt;
            for (String piece : answer.split("(?<=。)")) {
                if (!piece.isEmpty()) {
                    onDelta.accept(piece);
                }
            }
            return new com.knowledge.ai.pipeline.chat.StreamingChatCaller.Outcome(
                    7L, "stub-chat-model",
                    new com.knowledge.ai.pipeline.chat.ChatDtos.TokenUsage(11, 22, 33), "stop");
        };
    }

    /**
     * 流式对话替身 Bean。
     *
     * <p>与 {@link #rerankOverridingFactory} 同一个理由返回**转发器**而不是当时的
     * {@code streamStub}：Spring 上下文整个测试类是共用的，直接返回当时的替身会被
     * **第一个**用到它的用例钉死，后面所有用例改 {@code streamStub} 都不生效
     * （现象是"改一行测试没反应"）。
     */
    @Bean
    @Primary
    com.knowledge.ai.pipeline.chat.StreamingChatCaller streamingChatCallerStub() {
        return (prompt, onDelta) -> {
            STREAM_CALLS.incrementAndGet();
            return streamStub.stream(prompt, onDelta);
        };
    }

}
