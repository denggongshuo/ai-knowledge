package com.knowledge.ai.pipeline.vectorize;

import com.knowledge.ai.core.ai.ModelClientRegistry;
import com.knowledge.ai.core.ai.ModelConfigSpec;
import com.knowledge.ai.core.enums.ModelType;
import com.knowledge.ai.service.ModelUsageRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 嵌入调用通道（`T-508`）—— 收成一个函数式接口，**唯一碰嵌入模型的地方**。
 *
 * <h3>为什么是一个接口而不是直接注入 {@link EmbeddingModel}</h3>
 * <p>与 {@code enhance/ChatCaller} 同一条理由：向量化逻辑（投影 / 分批 / 幂等）不该与
 * "真去联网"耦合在一起。收成接口后，{@link ChunkVectorizer} 的正确性可以用假 client
 * 验（不联网、不花钱、结果稳定），真实调用另有一条 {@code @Tag("network")} 冒烟。
 *
 * <p>它同时是"**不得自己 {@code new} 模型客户端**"（`T-014` 验收要点）这条纪律的落点：
 * 依赖关系本身就挡住了绕道 —— 拿不到 {@code RegistryEmbeddingClient} 就没有别的路。
 */
@FunctionalInterface
public interface EmbeddingClient {

    /**
     * 把若干条文本编码成向量。
     *
     * @param texts 待编码文本，顺序即返回顺序
     * @return 与 {@code texts} **等长且同序**的向量
     * @throws EmbeddingFailedException 调用失败或返回条数不匹配（不静默截断）
     */
    List<float[]> embed(List<String> texts);

    /**
     * 当前生效的嵌入模型标识（`O9` 步 7）—— **"这条向量是谁产的"**。
     *
     * <p>为什么放在这个接口上、而不是让调用方去问 {@code ModelClientRegistry}：
     * 向量化这一跳才是"决定了用哪个模型"的那一跳，让它把归因一并报出来，
     * 调用方（{@code ChunkVectorizer}）就不必再持有模型注册中心 ——
     * 那条依赖会让 {@code ChunkVectorizer} 没法用假 client 单测。
     *
     * <p>默认实现返回 {@link EmbeddingModelRef#NONE}（空标识）：接口是
     * {@link FunctionalInterface}，加了抽象方法就不再是函数式接口，
     * 而那会波及所有用 lambda 造 client 的用例 —— 用一个 default 方法
     * 既拿回了信息，又保住了"假 client 可以用 lambda 写"。
     *
     * <p>⚠️ 读到的必须是**此刻**的配置：换模型之后写进来的向量要能被区分出来，
     * 而那正是这个字段存在的全部理由。所以实现方**不要**把它缓存成字段。
     */
    default EmbeddingModelRef modelRef() {
        return EmbeddingModelRef.NONE;
    }

    /**
     * 产出向量的模型归因。
     *
     * @param model   模型标识，如 {@code BAAI/bge-m3}
     * @param version 配置版本（{@code ModelConfigSpec#version()}）——
     *                与 {@code model} 合起来唯一标识"哪一版配置干的"
     */
    record EmbeddingModelRef(String model, String version) {

        /** 未知 / 不可得。**不是"没有模型"** —— 只是这一路没报出来。 */
        public static final EmbeddingModelRef NONE = new EmbeddingModelRef("", "");

        /** 是否拿到了归因。**不静默**：false 时落库要留空串而不是编一个值。 */
        public boolean known() {
            return model != null && !model.isBlank();
        }
    }

    /**
     * 走 {@link ModelClientRegistry#embeddingModel()} 的真实实现。
     *
     * <p><b>分批由本类负责</b>（不在调用方）：单文档的块数可能上百，而一次 HTTP 请求
     * 塞几百条文本既有请求体上限问题、也放大了失败时的重试代价。
     *
     * <p>实测口径（2026-09-17）：bge-m3 单次 5 条 0.31s、单条 0.24s —— 分批相比逐条
     * 是数量级的差别，所以"分成几批调"这件事本身就是成本控制的第一层（`O9` 的地基）。
     */
    @Slf4j
    @Component
    class RegistryEmbeddingClient implements EmbeddingClient {

        private final ModelClientRegistry registry;

        /** `T-905` 的用量落点。见 {@link #recordUsage}。 */
        private final ModelUsageRecorder usageRecorder;

        /** 每批文本数。小数据下 16 条一批，实测远在服务端限制内。 */
        private final int batchSize;

        RegistryEmbeddingClient(ModelClientRegistry registry,
                                ModelUsageRecorder usageRecorder,
                                @Value("${m05.vectorize.batch-size:16}") int batchSize) {
            this.registry = registry;
            this.usageRecorder = usageRecorder;
            this.batchSize = Math.max(1, batchSize);
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            EmbeddingModel model = registry.embeddingModel();
            List<float[]> out = new ArrayList<>(texts.size());
            for (int from = 0; from < texts.size(); from += batchSize) {
                int to = Math.min(from + batchSize, texts.size());
                List<String> batch = texts.subList(from, to);
                List<float[]> got;
                try {
                    // ⚠️ 用 `embedForResponse` 而不是 `embed(List)` —— 两者返回的向量**完全一样**
                    // （后者就是前者的一个 default 包装），但只有前者带得出 token 用量。
                    // `T-905` 的 `byPurpose` 里「文档向量化（嵌入）」那一桶要靠它，
                    // 而用量在**这一层不带出来，上层再想补也补不回来**。
                    EmbeddingResponse response = model.embedForResponse(batch);
                    recordUsage(response, batch.size());
                    got = response.getResults().stream().map(Embedding::getOutput).toList();
                } catch (EmbeddingFailedException ex) {
                    throw ex;
                } catch (Exception ex) {
                    // 把"第几批、多少条"带上 —— 否则排查时只剩一个远端的 500
                    throw new EmbeddingFailedException(
                            "嵌入模型调用失败（第 " + (from / batchSize + 1) + " 批，" + batch.size()
                                    + " 条）：" + rootMessage(ex)
                                    + " —— 请检查嵌入模型的配置与网络后重试", ex);
                }
                if (got == null || got.size() != batch.size()) {
                    throw new EmbeddingFailedException("嵌入模型返回条数不符：期望 " + batch.size()
                            + " 条，实际 " + (got == null ? 0 : got.size())
                            + " —— 拒绝按位对应，请反馈（这会导致向量与分片错位）");
                }
                out.addAll(got);
            }
            log.debug("嵌入完成：{} 批 / {} 条", (texts.size() + batchSize - 1) / batchSize, texts.size());
            return out;
        }

        /**
         * 记一次嵌入用量（`T-905`）。
         *
         * <p>⚠️ 嵌入**只有输入没有输出**：把网关回报的 token 全记进 {@code promptTokens}，
         * {@code completionTokens} 恒 0。若网关连总数都不报，则退回 {@code batch.size()}
         * —— 那时至少"调了几次、处理了几条"是准的（比整条不记强）。
         *
         * <p>⚠️ 记用量**不能影响嵌入本身**：这里整段包在 try 里，任何异常只留 warn。
         * 与 {@code ModelUsageRecorder} 内部那条取舍同源，但这里多包一层是因为
         * **调用点在核心写入路径上** —— 用量失败绝不能让一批向量写不进去。
         */
        private void recordUsage(EmbeddingResponse response, int batchCount) {
            try {
                Usage usage = response == null || response.getMetadata() == null
                        ? null : response.getMetadata().getUsage();
                long prompt = usage == null || usage.getPromptTokens() == null
                        ? batchCount : usage.getPromptTokens().longValue();
                usageRecorder.record(registry.activeSpec(ModelType.EMBEDDING),
                        ModelUsageRecorder.Purpose.EMBEDDING, batchCount, prompt, 0L, null);
            } catch (Exception ex) {
                log.warn("嵌入用量记录失败（不影响向量写入）：批={} 原因={}", batchCount, ex.getMessage());
            }
        }

        /**
         * ⚠️ 每次**现查**，刻意不缓存成字段。
         *
         * <p>缓存了就会在"运维换掉嵌入模型"之后继续上报旧标识 —— 于是新写入的向量被标成
         * 老模型的，而这个字段的全部价值恰恰是**区分新旧**。查一次是内存/配置读，不值得为它冒险。
         */
        @Override
        public EmbeddingModelRef modelRef() {
            ModelConfigSpec spec = registry.activeSpec(ModelType.EMBEDDING);
            return new EmbeddingModelRef(spec.model(), spec.version());
        }
    }

    /** 嵌入通道失败。**必须带可执行的下一步动作**（硬口径：失败给「现象 + 下一步」）。 */
    class EmbeddingFailedException extends RuntimeException {

        public EmbeddingFailedException(String message) {
            super(message);
        }

        public EmbeddingFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 取最内层异常的可读原因（远端 SDK 常把真原因埋在 cause 链里）。 */
    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
    }
}
