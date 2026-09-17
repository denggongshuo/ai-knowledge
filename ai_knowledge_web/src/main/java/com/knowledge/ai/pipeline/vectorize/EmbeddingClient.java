package com.knowledge.ai.pipeline.vectorize;

import com.knowledge.ai.core.ai.ModelClientRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
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

        /** 每批文本数。小数据下 16 条一批，实测远在服务端限制内。 */
        private final int batchSize;

        RegistryEmbeddingClient(ModelClientRegistry registry,
                                @Value("${m05.vectorize.batch-size:16}") int batchSize) {
            this.registry = registry;
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
                    got = model.embed(batch);
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
