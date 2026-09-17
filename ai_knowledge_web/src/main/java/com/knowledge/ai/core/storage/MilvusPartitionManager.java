package com.knowledge.ai.core.storage;

import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.ShowPartitionsResponse;
import io.milvus.param.R;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.partition.CreatePartitionParam;
import io.milvus.param.partition.DropPartitionParam;
import io.milvus.param.partition.HasPartitionParam;
import io.milvus.param.partition.LoadPartitionsParam;
import io.milvus.param.partition.ReleasePartitionsParam;
import io.milvus.param.partition.ShowPartitionsParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Milvus 分区的生命周期（`T-406` · D-7 的按代切换）。
 *
 * <h3>为什么单独一个组件，而不是塞进 {@code MilvusChunkWriter}</h3>
 * <p>{@code MilvusChunkWriter} 的职责是"**文档的向量**怎么写"，分区是"**容器**怎么管" ——
 * 两者变更的理由完全不同（前者随分片契约变，后者随重建策略变）。分开之后，
 * 重建流程只依赖本类，不会被写入细节牵动。
 *
 * <h3>三条硬约束，全部来自步 0 探针实测（{@code MilvusPartitionProbeTest}）</h3>
 * <ol>
 *   <li><b>写进不存在的分区会显式失败</b>（不会隐式创建）→ 写之前必须 {@link #ensure}；</li>
 *   <li><b>drop 前必须 release</b> —— 裸 {@code dropPartition} 会以 {@code 65535} 失败，
 *       而且**只体现在返回值里**（探针第一版没检查返回值，把"没删掉"误读成"删除延迟"，
 *       还泄漏了 32 个分区）。所以本类所有会失败的调用都走 {@link #assertOk}；</li>
 *   <li><b>查一个已 drop 的分区会报错</b>（不是返回空集）→ 检索侧绝不能指向不存在的分区。</li>
 * </ol>
 *
 * <p>本类**不负责**决定"该建哪些分区" —— 命名口径在
 * {@link MilvusConstants#partitionName}，保留代数与滚动策略在重建服务里。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusPartitionManager {

    private final MilvusServiceClient client;

    private final MilvusProperties properties;

    /**
     * 确保分区存在且可用（幂等）。
     *
     * <p>已存在则只做 {@code loadPartitions}（重复建会报错；而新建之后不 load 则**查不到**，
     * 表现为"写进去了却检索不到"—— 那是最难查的一类）。
     */
    public void ensure(String partition) {
        String collection = properties.getCollection();
        if (!has(partition)) {
            assertOk(client.createPartition(CreatePartitionParam.newBuilder()
                    .withCollectionName(collection)
                    .withPartitionName(partition)
                    .build()), "createPartition(" + partition + ")");
            log.info("[Milvus] 分区已创建：{}", partition);
        }
        assertOk(client.loadPartitions(LoadPartitionsParam.newBuilder()
                .withCollectionName(collection)
                .withPartitionNames(List.of(partition))
                .build()), "loadPartitions(" + partition + ")");
    }

    public boolean has(String partition) {
        R<Boolean> result = client.hasPartition(HasPartitionParam.newBuilder()
                .withCollectionName(properties.getCollection())
                .withPartitionName(partition)
                .build());
        assertOk(result, "hasPartition(" + partition + ")");
        return Boolean.TRUE.equals(result.getData());
    }

    /**
     * 删除分区（**先 release 再 drop**，见类注释的约束 ②）。
     *
     * <p>分区不存在时静默返回 {@code false} —— 滚动清理会重复跑到已删的代上，
     * 那不是错误。其余失败一律抛出（尤其是 drop 返回 65535 这种"看着成功其实没删"）。
     *
     * @return true = 确实删掉了一个分区
     */
    public boolean dropQuietly(String partition) {
        if (!has(partition)) {
            return false;
        }
        String collection = properties.getCollection();
        // release 可能因为"本来就没加载"而失败 —— 那不是问题，故只记日志不抛
        R<?> released = client.releasePartitions(ReleasePartitionsParam.newBuilder()
                .withCollectionName(collection)
                .withPartitionNames(List.of(partition))
                .build());
        if (released.getStatus() == null || released.getStatus() != R.Status.Success.getCode()) {
            log.debug("[Milvus] 释放分区未成功（继续尝试 drop）：{} 原因={}", partition, safeMessage(released));
        }
        assertOk(client.dropPartition(DropPartitionParam.newBuilder()
                .withCollectionName(collection)
                .withPartitionName(partition)
                .build()), "dropPartition(" + partition + ")");
        log.info("[Milvus] 分区已删除：{}", partition);
        return true;
    }

    /**
     * 列出某知识库**现有的代分区**（按代升序）。
     *
     * <p>只认 {@link MilvusConstants#partitionName} 拼出来的形状 —— 别的前缀（如
     * {@code _default}、别的库、探针用的临时分区）一概不算。
     */
    public List<Integer> generationsOf(long kbId) {
        String prefix = MilvusConstants.PARTITION_PREFIX + kbId + MilvusConstants.PARTITION_GENERATION_SEPARATOR;
        List<Integer> generations = new ArrayList<>();
        for (String name : allPartitionNames()) {
            if (!name.startsWith(prefix)) {
                continue;
            }
            try {
                generations.add(Integer.parseInt(name.substring(prefix.length())));
            } catch (NumberFormatException ex) {
                // 形状不符的（人工建的、旧版本留下的）不参与代的判断，但要留痕
                log.warn("[Milvus] 分区 {} 形状类似 {} 却不是代分区，已忽略", name, prefix);
            }
        }
        generations.sort(Comparator.naturalOrder());
        return generations;
    }

    /** 分区里的向量条数。**取证一律用它**，不用 {@code get_stats.rowCount}（后者只算已 flush 的）。 */
    public long countIn(String partition) {
        R<QueryResults> result = client.query(QueryParam.newBuilder()
                .withCollectionName(properties.getCollection())
                .withPartitionNames(List.of(partition))
                .withExpr(MilvusConstants.FIELD_KB_ID + " >= 0")
                .withOutFields(List.of("count(*)"))
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                .build());
        assertOk(result, "count(" + partition + ")");
        return countOf(result.getData());
    }

    public List<String> allPartitionNames() {
        R<ShowPartitionsResponse> result = client.showPartitions(ShowPartitionsParam.newBuilder()
                .withCollectionName(properties.getCollection())
                .build());
        assertOk(result, "showPartitions");
        return result.getData() == null ? List.of() : List.copyOf(result.getData().getPartitionNamesList());
    }

    // ==================== 工具 ====================

    /** {@code count(*)} 的结果形状：一个字段名为 {@code count(*)} 的 Int64 标量。 */
    private static long countOf(QueryResults results) {
        if (results == null) {
            return 0;
        }
        return results.getFieldsDataList().stream()
                .filter(f -> "count(*)".equals(f.getFieldName()))
                .mapToLong(f -> f.getScalars().getLongData().getData(0))
                .findFirst().orElse(0);
    }

    private static void assertOk(R<?> result, String what) {
        if (result.getStatus() == null || result.getStatus() != R.Status.Success.getCode()) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR,
                    "向量库分区操作失败（" + what + "）：" + safeMessage(result)
                            + " —— 请确认 milvus-standalone 健康后重试");
        }
    }

    /** 同 {@code MilvusChunkWriter#safeMessage}：{@code R#getMessage()} 在无异常对象时会 NPE。 */
    private static String safeMessage(R<?> result) {
        try {
            return String.valueOf(result.getMessage());
        } catch (RuntimeException ex) {
            return "<Milvus 未提供错误文本，status=" + result.getStatus() + ">";
        }
    }
}
