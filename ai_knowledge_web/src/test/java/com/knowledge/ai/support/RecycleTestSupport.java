package com.knowledge.ai.support;

import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.storage.MilvusPartitionManager;
import com.knowledge.ai.pipeline.vectorize.VectorChunk;
import com.knowledge.ai.pipeline.vectorize.VectorRecord;
import com.knowledge.ai.pipeline.vectorize.VectorWriter;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

/**
 * 删除 / 回收站类用例（`S7-M06-F` · `T-611`/`T-612`/`T-616`）的公共夹具。
 *
 * <p>它**继承 {@link DownloadTestSupport}**（而不是另起一份）：上传真文档、建探针库、
 * 按用户前缀清桶、按 kb 清行族 —— 这些一件不差地都要用。
 * 本类只补"这一批才有"的那三件：**按文档看向量条数**、**造出"已被向量化过"的真前置态**、
 * **清回收站残留**。
 *
 * <p>⚠️ 为什么值得抽一层：本批的清理面比下载那批**更宽**（软删行、硬删行、
 * 无主任务行、广播通知、**Milvus 分区**），而"每个用例类自己清"已被本仓证明会漏（`#130` 一族）。
 *
 * <h3>🔴 为什么必须有 {@link #seedVectors}</h3>
 * <p>测试期的 `m05.vectorize.enabled=false`（`src/test/resources/application.properties:22`）
 * ⇒ **解析流程一条向量都不写**，`sys_document.index_generation` 恒为 0。
 * 而本批最重要的两条判据（`G7-14` 的「软删后向量仍在」与 `O7` 的「彻底删除才清向量」）
 * 问的都是**向量条数**。
 * <p>不造前置态的写法是「断言 0 == 0」—— 它**看着是绿的、实际什么都没验**，
 * 而这是本仓反复吃亏的形状（长期记忆「Success 不等于执行了」）。
 * 本方法把"这篇文档已经被向量化过"这件事**真的做出来**：分区里有 N 个点、
 * 文档断点（`index_generation`）指向那一代 —— 与解析链路成功之后的状态逐项一致。
 */
public abstract class RecycleTestSupport extends DownloadTestSupport {

    /** 与 Milvus 集合的维度一致（`MilvusConstants.DIMENSION`）。 */
    private static final int DIMENSION = 1024;

    @Autowired
    protected MilvusPartitionManager partitionManager;

    @Autowired
    protected VectorWriter vectorWriter;

    /** 本用例造出来的代分区（`@AfterEach` 按它清，**否则留下一堆幽灵向量**）。 */
    private final List<String> seededPartitions = new ArrayList<>();

    /**
     * 某篇文档在 Milvus 里的**向量条数**。
     *
     * <p>⚠️ 用 {@code countIn(分区)} 而不是按 doc_id 过滤查询：本夹具建的探针库里
     * **只有这一篇文档**，所以"该代分区里的全部点"就是"这篇文档的点"。
     * 这样不必为测试新增一个 Milvus 过滤查询能力，而判据要的那件事（软删前后条数变没变）
     * 一点没少 —— 它本来就是**差值**判据（软删前后相等、彻底删后为 0）。
     *
     * @param generation 文档的 {@code index_generation}；{@code 0} = 从没向量化过
     */
    protected long vectorCountOf(long kbId, int generation) {
        if (generation <= 0) {
            return 0L;
        }
        return partitionManager.countIn(MilvusConstants.partitionName(kbId, generation));
    }

    /**
     * 把探针文档造成**"已经被向量化过"**的样子（`m05.vectorize.enabled=false` 的替代）。
     *
     * <p>做两件与解析链路一致的事，缺一不可：
     * <ol>
     *   <li>往 {@code kb{kbId}_g{generation}} 写 {@code count} 条真向量
     *       （走 {@link VectorWriter#replaceDocument} —— 与生产同一条写入通道）；</li>
     *   <li>把 {@code sys_document.index_generation} 盖成 {@code generation}
     *       —— 这是"这篇文档的向量写齐了哪一代"的**唯一**记录，
     *       而彻底删除正是靠它定位该去删哪个分区（{@code DocLifecycleService#purgeDoc}
     *       的 {@code if (doc.indexGeneration() > 0)}）。只写向量不盖这一列，
     *       被测代码会跳过向量清理，用例就变成了"验了别的东西"。</li>
     * </ol>
     *
     * @return 真的写进去的条数（供断言"前置态成立了"）
     */
    protected int seedVectors(long kbId, long docId, int generation, int count) {
        String partition = MilvusConstants.partitionName(kbId, generation);
        List<VectorRecord> records = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // 探针块的 id 取负数段（与自增的 chunk.id 绝不相撞，也不会被别的用例误认领）
            VectorChunk chunk = new VectorChunk(-(docId * 100 + i), "PARAGRAPH",
                    "探针正文 " + i, "探针标题", "INTERNAL", null);
            records.add(new VectorRecord(chunk, kbId, docId, unitVector(i)));
        }
        int written = vectorWriter.replaceDocument(partition, docId, records);
        jdbc.update("update sys_document set index_generation = ? where id = ?", generation, docId);
        seededPartitions.add(partition);
        return written;
    }

    /**
     * 单位向量（第 {@code slot} 维为 1）。**不用随机数**：同一条记录两次构造必须得到同一个向量，
     * 否则"写进去的"与"数出来的"之间就多了一个不可复现的变量。
     */
    private static float[] unitVector(int slot) {
        float[] v = new float[DIMENSION];
        v[Math.floorMod(slot, DIMENSION)] = 1.0f;
        return v;
    }

    /** 某篇文档当前的 {@code index_generation}（`0` = 从没向量化过）。 */
    protected int generationOf(long docId) {
        Integer n = jdbc.queryForObject(
                "select index_generation from sys_document where id = ?", Integer.class, docId);
        return n == null ? 0 : n;
    }

    /** 该文档是否还在（含软删的行）。 */
    protected boolean docRowExists(long docId) {
        Long n = jdbc.queryForObject(
                "select count(*) from sys_document where id = ?", Long.class, docId);
        return n != null && n > 0;
    }

    /** 该文档是否已被软删（进回收站）。 */
    protected boolean docSoftDeleted(long docId) {
        Long n = jdbc.queryForObject(
                "select count(*) from sys_document where id = ? and deleted_at is not null",
                Long.class, docId);
        return n != null && n > 0;
    }

    /**
     * 把本用例造成的**回收站残留**清干净。
     *
     * <p>"清干净"分三步，缺一不可：
     * <ol>
     *   <li><b>Milvus 的代分区</b>（{@link #seedVectors} 造出来的那些）——
     *       父类的 {@code cleanDownloadProbeData} 只删 SQL 行与桶对象，
     *       **不认识向量**（它是 `S7-M06-E` 的夹具，那一批一条向量都不写）。
     *       不在这里收，本批的用例就会每跑一轮在集合里留一批谁也认领不了的幽灵点
     *       —— 那正是 `T-O27` 花了一整批去清的东西；</li>
     *   <li>探针库的软删行由父类按 `kb_id` 无条件删（那条 `delete from sys_document
     *       where kb_id = ?` 不看 `deleted_at`），这里不必重复；</li>
     *   <li>无主的 `sys_async_task` 行由 {@code ApiTestSupport} 的
     *       {@code sweepOrphanSystemTasks} 兜底（定时清理任务三个坐标全空）。</li>
     * </ol>
     *
     * <p>⚠️ 顺序：本方法在父类的 {@link #cleanDownloadProbeData()} **之前**跑
     * （JUnit 先跑子类的 `@AfterEach`），所以这里别去删 kb 行 —— 那是父类的活。
     * 先删点再丢分区是定死的顺序（裸 drop 会被"分区已加载"挡下来）。
     */
    @AfterEach
    void cleanRecycleResidue() {
        for (String partition : seededPartitions) {
            if (partitionManager.has(partition)) {
                partitionManager.dropQuietly(partition);
            }
        }
        seededPartitions.clear();
        // 本批新表的残留按"探针库"清；按用户的那些父类已经在做。
        // ⚠️ 这里必须按 **id 前缀**清而不是按终态：本批的两个用例会**手工插**
        //    `probe-*` 的行来验广播告警（它们故意停在 RUNNING），而父类那条
        //    `sweepOrphanSystemTasks` 的谓词原先只认 `DONE/FAILED` ⇒ 够不着它们。
        //    实测代价：跑四轮之后库里躺着 7 行 RUNNING 任务 + 3 条广播通知
        //    （本批开工核基线时抓到）。**清掉任务行是必需的**，否则它们产的那条广播通知
        //    永远不会变成无主（`sweepOrphanNotices` 问的正是"产它的任务还在不在"）。
        jdbc.update("delete from sys_async_task where owner_id is null and kb_id is null "
                + "and doc_id is null and task_id like 'probe-%'");
    }
}
