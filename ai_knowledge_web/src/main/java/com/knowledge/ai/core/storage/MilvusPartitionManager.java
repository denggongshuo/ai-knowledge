package com.knowledge.ai.core.storage;

import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.service.partition.request.CreatePartitionReq;
import io.milvus.v2.service.partition.request.DropPartitionReq;
import io.milvus.v2.service.partition.request.HasPartitionReq;
import io.milvus.v2.service.partition.request.ListPartitionsReq;
import io.milvus.v2.service.partition.request.LoadPartitionsReq;
import io.milvus.v2.service.partition.request.ReleasePartitionsReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Milvus 分区的生命周期（`T-406` · D-7 的按代切换；`S5-M07-A` 迁到 v2）。
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
 *       还泄漏了 32 个分区）；</li>
 *   <li><b>查一个已 drop 的分区会报错</b>（不是返回空集）→ 检索侧绝不能指向不存在的分区。</li>
 * </ol>
 *
 * <p>⚠️ <b>v2 迁移后这三条一条没变</b>，只是"从返回值里读失败"换成了"抛异常"：
 * v2 客户端失败即抛 {@code MilvusException}（unchecked），不再有
 * "看起来成功、其实 status 非 0"的中间态 —— 这反而消掉了当年那个
 * 「探针没看返回值，把没删掉误读成删除延迟」的坑。故本类的所有调用都包在
 * {@link #guard} 里，把异常翻译成带**下一步动作**的 {@link BizException}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusPartitionManager {

    private final MilvusClientV2 client;

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
            guard("createPartition(" + partition + ")", () -> client.createPartition(
                    CreatePartitionReq.builder()
                            .collectionName(collection)
                            .partitionName(partition)
                            .build()));
            log.info("[Milvus] 分区已创建：{}", partition);
        }
        guard("loadPartitions(" + partition + ")", () -> client.loadPartitions(
                LoadPartitionsReq.builder()
                        .collectionName(collection)
                        .partitionNames(List.of(partition))
                        .build()));
    }

    public boolean has(String partition) {
        return Boolean.TRUE.equals(guard("hasPartition(" + partition + ")", () -> client.hasPartition(
                HasPartitionReq.builder()
                        .collectionName(properties.getCollection())
                        .partitionName(partition)
                        .build())));
    }

    /**
     * 删除分区（**先 release 再 drop**，见类注释的约束 ②）。
     *
     * <p>分区不存在时静默返回 {@code false} —— 滚动清理会重复跑到已删的代上，
     * 那不是错误。
     *
     * @return true = 确实删掉了一个分区
     */
    public boolean dropQuietly(String partition) {
        if (!has(partition)) {
            return false;
        }
        String collection = properties.getCollection();
        // release 可能因为"本来就没加载"而失败 —— 那不是问题，故只记日志不抛
        try {
            client.releasePartitions(ReleasePartitionsReq.builder()
                    .collectionName(collection)
                    .partitionNames(List.of(partition))
                    .build());
        } catch (Exception ex) {
            log.debug("[Milvus] 释放分区未成功（继续尝试 drop）：{} 原因={}", partition, rootMessage(ex));
        }
        guard("dropPartition(" + partition + ")", () -> client.dropPartition(
                DropPartitionReq.builder()
                        .collectionName(collection)
                        .partitionName(partition)
                        .build()));
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
        QueryResp resp = guard("count(" + partition + ")", () -> client.query(QueryReq.builder()
                .collectionName(properties.getCollection())
                .partitionNames(List.of(partition))
                .filter(MilvusConstants.FIELD_KB_ID + " >= 0")
                .outputFields(List.of("count(*)"))
                // STRONG：这是**取证**用的读数，不接受"可能读到旧值"
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build()));
        return countOf(resp);
    }

    public List<String> allPartitionNames() {
        List<String> names = guard("listPartitions", () -> client.listPartitions(
                ListPartitionsReq.builder()
                        .collectionName(properties.getCollection())
                        .build()));
        return names == null ? List.of() : List.copyOf(names);
    }

    // ==================== 工具 ====================

    /** {@code count(*)} 的结果形状：一行 {@code {"count(*)": <Number>}}。 */
    private static long countOf(QueryResp resp) {
        if (resp == null || resp.getQueryResults() == null || resp.getQueryResults().isEmpty()) {
            return 0;
        }
        Object value = resp.getQueryResults().getFirst().getEntity().get("count(*)");
        return value instanceof Number n ? n.longValue() : 0;
    }

    /**
     * 把 v2 客户端的异常翻译成带**下一步动作**的业务异常（硬口径：失败给「现象 + 下一步」）。
     *
     * <p>v2 客户端失败即抛，不再有"status 非 0 但方法正常返回"的中间态 ——
     * 这正是当年那次分区泄漏的根因形态，故这里**不做返回值检查、只兜异常**。
     */
    private static <T> T guard(String what, java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (Exception ex) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR,
                    "向量库分区操作失败（" + what + "）：" + rootMessage(ex)
                            + " —— 请确认 milvus-standalone 健康后重试");
        }
    }

    private static void guard(String what, Runnable action) {
        guard(what, () -> {
            action.run();
            return null;
        });
    }

    private static String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor == ex ? ex.toString() : cursor.toString();
    }
}
