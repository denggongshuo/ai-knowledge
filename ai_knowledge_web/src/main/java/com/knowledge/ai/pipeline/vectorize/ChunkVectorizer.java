package com.knowledge.ai.pipeline.vectorize;

import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 分片向量化（`T-508`）—— 「块 → 嵌入文本 → 向量 → Milvus」的编排。
 *
 * <h3>只做三件事</h3>
 * <ol>
 *   <li><b>挑</b>：哪些块进检索池（{@link VectorChunk#vectorText()} 为 {@code null} 的跳过）；</li>
 *   <li><b>投影</b>：进的是「标题路径 + 原文」，<b>不改写块的原始内容</b>（`O8` / D-5 §1.7）；</li>
 *   <li><b>写</b>：交给 {@link VectorWriter} 先删后插（幂等）。</li>
 * </ol>
 *
 * <h3>为什么把"取哪种文本"放在 {@link VectorChunk} 而不是这里</h3>
 * <p>那样向量化这一层就不必长出 {@code if (blockType == TABLE)} 的分支 ——
 * 新增块类型时改的是那个类型自己的声明，不是这条管线（`O8` 步 3 的"泛化点"）。
 *
 * <h3>失败必须可行动</h3>
 * <p>嵌入失败 / 写入失败一律**抛出**，由调用方（{@code DocParseService}）落成
 * {@code FAILED} + 具体原因。**不静默降级成"文档可用但没有向量"** ——
 * 那会让文档标着 `READY` 却检索不到，是本项目最不能接受的静默故障形态。
 */
@Slf4j
@Service
public class ChunkVectorizer {

    private final EmbeddingClient embeddingClient;

    private final VectorWriter writer;

    /**
     * ⚠️ 做成构造器参数而不是 {@code @Value} 字段（与 {@code IndexEnhancer} 同一条理由）：
     * 字段注入的话，想造一个"关掉开关"的实例就得靠反射。
     */
    private final boolean enabled;

    public ChunkVectorizer(EmbeddingClient embeddingClient,
                           VectorWriter writer,
                           @Value("${m05.vectorize.enabled:true}") boolean enabled) {
        this.embeddingClient = embeddingClient;
        this.writer = writer;
        this.enabled = enabled;
    }

    /**
     * 把一篇文档的块全部向量化并写入 Milvus 的**指定代**。
     *
     * @param docId      文档 id（Milvus 的删除按它走）
     * @param kbId       所属知识库
     * @param generation 写进哪一代分区（{@code T-406} 的按代切换）。
     *                   日常解析传 {@code sys_kb.index_generation}；重建索引传"新代"。
     * @param chunks     该文档的全部块（**含不进池的**：本方法自己挑，调用方不必先过滤 ——
     *                   让"哪些块进池"只有一个口径）
     * @param guard      **写入前的最后一道复核**（`S6-M05-C`）：见 {@link GenerationGuard}。
     *                   它在**真正写 Milvus 之前、嵌入之后**调用 —— 放在这个位置是有意的：
     *                   调用方在更早处判一次能省下嵌入的钱，但**只有这里能挡住
     *                   "判定通过之后、写入之前"那段窗口**，而重建分区这个动作就在写入里。
     * @return 实际写入的条数
     */
    public Result vectorize(long docId, long kbId, int generation, List<VectorChunk> chunks,
                            GenerationGuard guard) {
        if (!enabled) {
            log.info("向量化开关未开启（m05.vectorize.enabled=false），跳过：docId={}", docId);
            return new Result(0, 0, Status.DISABLED, "向量化开关未开启（m05.vectorize.enabled=false）",
                    EmbeddingClient.EmbeddingModelRef.NONE);
        }

        String partition = MilvusConstants.partitionName(kbId, generation);
        // 两种"跳过"分别计数：投影维度（表格父块）是 `D-5` 的既有口径，
        // 样板维度（`O9` 步 4）是本批新增。合成一个数会让日志里只剩"跳过 N 条"，
        // 而 N 里混着两种完全不同的原因 —— 那正是排查时最想知道的那件事
        int projected = VectorChunk.onlyVectorized(chunks).size();
        int skippedProjection = chunks.size() - projected;
        List<VectorChunk> candidates = VectorChunk.onlyRecallable(chunks);
        int skippedBoilerplate = projected - candidates.size();
        int skipped = chunks.size() - candidates.size();
        if (candidates.isEmpty()) {
            // 空块集也要走一次删除：上一次解析若写过向量，不清掉就会留下"文档没有块、
            // 却检索得到"的幽灵 —— 与 DocRecordWriter 空块集也要回填 chunk_count = 0 同一条理由
            // ⚠️ 这一步**同样要过复核**：`replaceDocument` 会 ensure 分区，
            //    所以"删"这个动作也会把被清掉的分区重建出来（只是没有向量而已）
            guard.verify();
            writer.replaceDocument(partition, docId, List.of());
            log.info("没有需要向量化的块（全部被跳过）：docId={} 跳过={}（表格父块 {} + 样板 {}）",
                    docId, skipped, skippedProjection, skippedBoilerplate);
            return new Result(0, skipped, Status.EMPTY, "没有需要向量化的块",
                    EmbeddingClient.EmbeddingModelRef.NONE);
        }

        List<String> texts = candidates.stream().map(VectorChunk::vectorText).toList();

        // 归因先取：它在 embed 之前失败的话，本次什么都没写，比"写进去了但归因取不到"干净。
        // 而且 embed 内部同样要解析模型配置 —— 这一步过了，那一步基本不会因配置失败。
        EmbeddingClient.EmbeddingModelRef modelRef = embeddingClient.modelRef();

        List<float[]> vectors;
        try {
            vectors = embeddingClient.embed(texts);
        } catch (EmbeddingClient.EmbeddingFailedException ex) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, ex.getMessage(), ex);
        }

        if (vectors.size() != candidates.size()) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR,
                    "嵌入结果条数与块数不符：块 " + candidates.size() + " 个，向量 " + vectors.size()
                            + " 个 —— 拒绝按位对应，请反馈");
        }

        List<VectorRecord> records = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            records.add(new VectorRecord(candidates.get(i), kbId, docId, vectors.get(i),
                    modelRef.model(), modelRef.version()));
        }
        // 🔴 **最后一道复核**：从"上面的判定"到这里，可能已经过去好几秒（嵌入是网络调用）。
        //    在这期间「清空索引」/「重建」都可能把这一代切走 —— 而 `replaceDocument`
        //    会 ensure 分区，也就是**把已经被 drop 的分区重建出来**。实测过一次：
        //    用户拿到 `{"cleared":true}` 之后，分区与 4 条向量原样重现。
        guard.verify();
        int written = writer.replaceDocument(partition, docId, records);
        log.info("文档向量化完成：docId={} 分区={} 写入={} 跳过={}（表格父块/未实现的图片块 {} + 样板 {}）模型={}",
                docId, partition, written, skipped, skippedProjection, skippedBoilerplate,
                modelRef.model().isBlank() ? "<未报出>" : modelRef.model());
        return new Result(written, skipped, Status.OK, "已写入 " + written + " 个向量", modelRef);
    }

    /** 向量化的三种结局。**只有 {@link #OK} 允许把文档标成可检索。** */
    /**
     * **写入前的最后一道复核**（`S6-M05-C`）—— 「我手里的这一代**还是**该库当前那一代吗」。
     *
     * <h3>它存在的理由是一件实测过的缺陷</h3>
     * <pre>
     *   ① 文档 B 正在解析/索引（它在 parseDocument 开头读到当时的分区代 = 1）
     *   ② 用户点「清空索引」→ 代 1 的分区被 drop，文档状态退回 INDEXING
     *   ③ B 跑完 → `MilvusChunkWriter#replaceDocument`「已自带 ensure」→ **代 1 的分区被重建**
     *   ④ 用户拿到 `{"cleared":true}`，而分区与 4 条向量原样重现 —— **静默地撤销了他明确要求的操作**
     * </pre>
     *
     * <h3>为什么是一个回调，而不是在这里直接查库</h3>
     * <p>{@code ChunkVectorizer} 是**纯管线**（嵌入 + 写向量），不碰 MySQL ——
     * 把 DB 依赖引进来会让它从"可单测的变换"变成"要起库的组件"。
     * 而"当前是哪一代"的口径只有调用方知道（日常解析 = {@code sys_kb.index_generation}；
     * 重建 = 那个**还没切过去**的新代），所以判断权交给调用方。
     *
     * <p>⚠️ {@code ReindexService} 传的是**空实现** —— 这是**有意的**：
     * 重建本来就写"当前代 + 1"，那一刻它**还不是**当前代，用同一条判据会把它自己拦下来。
     * 见那边的调用点注释。
     */
    @FunctionalInterface
    public interface GenerationGuard {

        /**
         * 复核。**不通过就抛**（{@code BizException}）—— 用异常而不是返回布尔量，
         * 是因为"不通过"必须**中断整条写入**，而返回布尔量会留出"忘了判返回值"的口子。
         */
        void verify();
    }

    /**
     * 复核不通过 —— 该库的代已经变了，本次**放弃写入**。
     *
     * <p>⚠️ **调用方绝不能把它当成"这篇文档解析失败"**：文档本身一点问题都没有，
     * 它是被"清空索引 / 重建索引"这件事波及的。标 `FAILED` 会：
     * ① 让用户以为文件有问题；② 把状态推到终态，后续重建索引的筛选
     * （`status in ('READY','INDEXING')`）**再也捞不到它** —— 于是它永远修不好。
     * 正确的落点是**留在 `INDEXING`**（有块、不可检索），与 `clearIndex` 对其它文档的处置一致。
     */
    public static class GenerationChangedException extends RuntimeException {

        public GenerationChangedException(String message) {
            super(message);
        }
    }

    public enum Status {
        /** 正常写入。 */
        OK,
        /** 没有需要向量化的块（空文档 / 全部被跳过）—— 也算成功，只是没写东西。 */
        EMPTY,
        /** 开关关闭，整批跳过。 */
        DISABLED
    }

    /**
     * @param written  实际写入 Milvus 的条数
     * @param skipped  被跳过的块数（表格父块 + 图片块）
     * @param status   三种结局之一
     * @param detail   给人看的说明
     * @param modelRef 产出这批向量的模型归因（`O9` 步 7）。调用方据此回填
     *                 {@code sys_chunk.embed_model_id/version} —— 有了它，换嵌入模型
     *                 才能"按模型筛出旧向量"，而不是只能全库重建。
     *                 非 OK 路径上是 {@link EmbeddingClient.EmbeddingModelRef#NONE}（没写就没归因）。
     */
    public record Result(int written, int skipped, Status status, String detail,
                         EmbeddingClient.EmbeddingModelRef modelRef) {

        /** 是否可以把文档推进到「可被检索」。 */
        public boolean allowsReady() {
            return status == Status.OK || status == Status.EMPTY;
        }
    }
}
