package com.knowledge.ai.core.ai;

import com.knowledge.ai.core.constant.MilvusConstants;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S0 验收自动化（T-009 · 门 G0-6）。
 *
 * <p>G0-6 的原话是「真实调用硅基流动：对话返回内容 + {@code bge-m3} 返回 1024 维向量」。
 * 这句话没法靠看代码或 mock 证明，只能真打一次接口 —— 本测试就是那个"打一次"，
 * 并且把结论固化成断言，换机器 / 换同事 / 改配置后都能重跑。
 *
 * <p><b>为什么这组断言是这么写的</b>
 * <ul>
 *   <li><b>维度对齐 {@link MilvusConstants#DIMENSION} 而不是写死 1024</b>：
 *       G0-6 与 G0-7 真正要保证的是"模型吐出的维度 == 集合锁死的维度"。
 *       两边各自断言 1024 只能证明两处都写了 1024，交叉断言才能证明**对得上**。</li>
 *   <li><b>断言 L2 归一化</b>：项目用 L2 度量（{@code MilvusConstants.METRIC_TYPE}）
 *       的前提就是 bge-m3 输出已归一化。这条不验，"为什么用 L2 不用 COSINE"就是空口。</li>
 *   <li><b>断言语义排序</b>：只验"返回了 1024 个数"证明不了这是真嵌入 ——
 *       随机数也能凑够 1024 维。加一条"同主题的相似度高于无关主题"，
 *       才说明拿到的是**语义向量**，是 M05 检索能力的地基。</li>
 *   <li><b>断言模型名来自接口响应元数据</b>：证明"配置里写的模型"确实被用了，
 *       而不是被某个默认值悄悄顶掉（这类漂移在只断言"有返回值"时完全看不出来）。</li>
 * </ul>
 *
 * <p><b>运行前提</b>：能访问外网 + {@code .env} 里有可用的 key。
 * key 缺失或为占位值时**跳过而非失败**（{@link Assumptions}）——
 * 这样"只跑存储、不跑模型"的本地场景仍然是绿的，而不会误报成缺陷。
 * 需要完全排除时加 {@code -DexcludedGroups=network}。
 */
@Tag("network")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AiCallVerificationTest {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired
    private ModelProperties modelProperties;

    @Test
    @DisplayName("G0-6：对话模型真实返回内容（DeepSeek 走 OpenAI 兼容协议）")
    void chatModelReturnsRealContent() {
        ModelProperties.Endpoint chat = modelProperties.getChat();
        Assumptions.assumeTrue(chat.hasCredentials(),
                () -> "未配置 chat 端点（" + chat.describe() + "），跳过真实对话调用");

        ChatResponse response = chatClientBuilder.build()
                .prompt()
                // 用一个有唯一正确答案的问题：能答对才说明模型真的读懂了输入，
                // 而不是"随便吐了段非空字符串"就算过
                .user("用一个词回答：中国的首都是哪座城市？")
                .call()
                .chatResponse();

        assertNotNull(response, "对话接口未返回响应");
        assertNotNull(response.getResult(), "对话响应缺少 result（choices 为空）");

        String content = response.getResult().getOutput().getText();
        assertNotNull(content, "对话响应内容为 null");
        assertTrue(content.contains("北京"),
                "模型未能正确回答，返回内容：" + content);

        // 元数据里的模型名 = 实际被调用的模型，用来证明配置生效而非落到默认值
        String usedModel = response.getMetadata().getModel();
        assertNotNull(usedModel, "响应元数据未回传模型名");
        assertTrue(usedModel.contains(chat.getModel()),
                "实际调用模型与配置不一致：配置=" + chat.getModel() + "，实际=" + usedModel);

        System.out.printf("%n===== 对话模型（S0 / G0-6）=====%n"
                        + "  endpoint=%s%n  usedModel=%s%n  content=%s%n",
                chat.describe(), usedModel, content.strip());
    }

    @Test
    @DisplayName("G0-6：嵌入模型返回的维度与 Milvus 锁死的维度一致，且已 L2 归一化")
    void embeddingMatchesLockedDimensionAndIsNormalized() {
        ModelProperties.Endpoint embedding = modelProperties.getEmbedding();
        Assumptions.assumeTrue(embedding.hasCredentials(),
                () -> "未配置 embedding 端点（" + embedding.describe() + "），跳过真实向量化调用");

        // 走 embedForResponse 而不是 embed(String)：前者能拿到响应元数据（模型名、用量），
        // 是"配置生效"的直接证据；后者只给一个 float[]
        EmbeddingResponse response = embeddingModel.embedForResponse(
                List.of("知识库文档的分片策略应当如何设计"));

        assertNotNull(response, "嵌入接口未返回响应");
        assertEquals(1, response.getResults().size(), "单条输入应返回一个向量");

        float[] vector = response.getResults().get(0).getOutput();
        assertNotNull(vector, "嵌入向量为 null");

        // 与 G0-7 交叉断言：模型输出维度必须等于集合锁死的维度。
        // 不匹配的后果不是"报错"，而是写入 Milvus 时每一条都失败（R-8）。
        assertEquals(MilvusConstants.DIMENSION, vector.length,
                "嵌入维度与 Milvus 锁死的维度不一致：模型=" + vector.length
                        + "，集合=" + MilvusConstants.DIMENSION + "（改维度等于全库重建 38,502 条向量）");

        assertTrue(modelProperties.getEmbedding().hasCredentials());
        String usedModel = response.getMetadata().getModel();
        assertNotNull(usedModel, "嵌入响应元数据未回传模型名");
        assertTrue(usedModel.contains(embedding.getModel()),
                "实际调用模型与配置不一致：配置=" + embedding.getModel() + "，实际=" + usedModel);

        // L2 归一化：向量模长应约等于 1。（用 L2 度量的前提就是这个）
        double norm = l2Norm(vector);
        assertEquals(1.0d, norm, 0.02d,
                "向量未做 L2 归一化（模长=" + norm + "），与「用 L2 度量」的前提不符");

        System.out.printf("%n===== 嵌入模型（S0 / G0-6）=====%n"
                        + "  endpoint=%s%n  usedModel=%s%n  dim=%d（= MilvusConstants.DIMENSION）L2 模长=%.4f%n",
                embedding.describe(), usedModel, vector.length, norm);
    }

    @Test
    @DisplayName("G0-6：嵌入向量具备语义区分度（同主题相似度 > 无关主题）")
    void embeddingsCarrySemantics() {
        ModelProperties.Endpoint embedding = modelProperties.getEmbedding();
        Assumptions.assumeTrue(embedding.hasCredentials(),
                () -> "未配置 embedding 端点（" + embedding.describe() + "），跳过语义校验");

        // 一次请求三句，省调用次数
        List<String> texts = List.of(
                "知识库文档的分片策略应当如何设计",
                "文档切片与分块大小的最佳实践",
                "今天北京天气晴朗适合出门散步");

        List<float[]> vectors = embeddingModel.embed(texts);
        assertEquals(3, vectors.size(), "批量嵌入应返回三个向量");

        double related = cosine(vectors.get(0), vectors.get(1));
        double unrelated = cosine(vectors.get(0), vectors.get(2));

        // 留 0.05 的余量：不追求绝对值，只要能稳定区分开"同主题"与"无关"
        assertTrue(related > unrelated + 0.05d,
                "嵌入未体现语义区分度：同主题相似度=" + related + "，无关主题相似度=" + unrelated);

        System.out.printf("%n===== 嵌入语义区分度（S0 / G0-6）=====%n"
                        + "  同主题（分片策略 ↔ 分块实践）=%.4f%n"
                        + "  无关主题（分片策略 ↔ 天气）  =%.4f%n",
                related, unrelated);
    }

    private static double l2Norm(float[] vector) {
        double sum = 0d;
        for (float v : vector) {
            sum += (double) v * v;
        }
        return Math.sqrt(sum);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0d;
        double na = 0d;
        double nb = 0d;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
