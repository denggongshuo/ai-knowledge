package com.knowledge.ai.core.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.knowledge.ai.core.constant.MilvusConstants;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.service.partition.request.CreatePartitionReq;
import io.milvus.v2.service.partition.request.DropPartitionReq;
import io.milvus.v2.service.partition.request.HasPartitionReq;
import io.milvus.v2.service.partition.request.ListPartitionsReq;
import io.milvus.v2.service.partition.request.ReleasePartitionsReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `S4-D` **步 0 探针**：Milvus 2.6 的 partition 能力实测 —— 决定整批的重建原子性方案。
 *
 * <h3>为什么必须真库探，而不是只看 SDK 方法面</h3>
 * <p>{@code javap} 已证那些方法**都在**。但**方法在 ≠ 行为对** —— 本仓库刚被这类事咬过：
 * {@code describeIndex(fieldName=…)} 的参数**被服务端静默忽略**，守卫因此恒真，
 * 4 个 INVERTED 索引从 S0 起从未创建且零日志
 * （见 {@code MilvusCollectionInitializer#collectionIndexNames} 的 javadoc）。
 * 所以「分区隔离」这条**设计的地基**必须由真实数据往返来证。
 *
 * <h3>本探针要回答的 6 个问题</h3>
 * <ol>
 *   <li>写进 A 分区的点，查 A 分区能查到吗？（写入路由生效）</li>
 *   <li>查 A 分区，会**漏进** B 分区的点吗？（隔离 —— **本方案存亡所系**）</li>
 *   <li>不指定分区时，是"查全部"还是"查不到"？（默认语义）</li>
 *   <li>{@code dropPartition} 只删该分区，不牵连别的？（按代滚动清理的基础）</li>
 *   <li>写进**不存在**的分区会怎样？（实现要不要先 ensure-exists）</li>
 *   <li>分区数上限够不够用？（本设计要 库数 × 保留代数）</li>
 * </ol>
 *
 * <p>⚠️ <b>2026-09-17（`S5-M07-A`）迁到 v2 —— 三条"预期失败"的观测换了表达方式，但结论一条没变。</b>
 * v1 时代失败体现为<b>返回值里的非 0 status</b>（当年正因如此漏看过一次：探针没检查返回值，
 * 把"没删掉"误读成"删除延迟"，泄漏了 32 个分区）；v2 <b>失败即抛</b>。所以下面三条
 * 从「断言 status ≠ Success」改成了 {@link #expectFailure}，语义完全一样，而且更难漏看。
 *
 * <p>{@code @Tag("network")} —— 依赖运行中的 milvus-standalone 容器；不可用时**明确失败**而不是跳过。
 * 分区名前缀 {@code zzprobe_} 与本批真实用的 {@code kb{N}_g{K}} 绝不相撞，收尾统一 drop。
 */
@Tag("network")
class MilvusPartitionProbeTest {

    /** 探针专用分区前缀 —— 与真实分区命名 {@code kb{N}_g{K}} 不相交，可随时整批清理。 */
    private static final String PROBE_PREFIX = "zzprobe_";

    /** 分区数上限探针要建多少个。本设计实际需要 = 库数(8) × 保留代数(2~3) ≈ 24，取 32 留余量。 */
    private static final int PARTITION_COUNT_PROBE = 32;

    private static final long PROBE_DOC_A = -999_101L;

    private static final long PROBE_DOC_B = -999_102L;

    private static MilvusClientV2 client;

    private static MilvusProperties props;

    @BeforeAll
    static void setUp() {
        props = new MilvusProperties();
        client = new MilvusClientV2(ConnectConfig.builder()
                .uri("http://" + props.getHost() + ":" + props.getPort())
                .connectTimeoutMs(props.getConnectTimeoutMs())
                .rpcDeadlineMs(props.getRpcDeadlineMs())
                .build());
        dropProbePartitions();
    }

    @AfterAll
    static void tearDown() {
        if (client != null) {
            dropProbePartitions();
            client.close();
        }
    }

    // ==================== 问题 1~3：写入路由与隔离 ====================

    @Test
    @DisplayName("探针 1+2+3：写进 A 分区的点只在 A 分区可见 —— 分区隔离成立（本方案的地基）")
    void partitionIsolationHolds() {
        createPartition("pa");
        createPartition("pb");
        insertInto("pa", PROBE_DOC_A, 1);
        insertInto("pb", PROBE_DOC_B, 2);

        // 问题 1：写进 A 的能查到
        assertThat(idsInPartitions(List.of("pa")))
                .as("写进 A 分区的点，查 A 分区应能查到")
                .containsExactly("1");

        // 问题 2（关键）：A 分区**不含** B 的点
        assertThat(idsInPartitions(List.of("pa")))
                .as("分区隔离：查 A 绝不能漏进 B 的点 —— 不成立则整个按代切换方案作废")
                .doesNotContain("2");
        assertThat(idsInPartitions(List.of("pb")))
                .as("反向同样成立")
                .containsExactly("2");

        // 问题 3：不指定分区 = 查全部（不是查不到）
        assertThat(idsInPartitions(List.of()))
                .as("不指定分区时应为「全部」（含默认 _default 分区），否则检索侧漏数据")
                .contains("1", "2");
    }

    @Test
    @DisplayName("探针 4：多分区联合查询 = 各分区并集（切代期间可同时查两代的降级路径）")
    void queryingTwoPartitionsIsUnion() {
        createPartition("pc");
        createPartition("pd");
        insertInto("pc", PROBE_DOC_A, 11);
        insertInto("pd", PROBE_DOC_B, 12);

        assertThat(idsInPartitions(List.of("pc", "pd")))
                .as("传多个分区名应取并集")
                .containsExactlyInAnyOrder("11", "12");
    }

    // ==================== 问题 4：drop 的隔离性 ====================

    @Test
    @DisplayName("探针 4：drop 前必须先 release —— 直接 drop 已加载分区会失败（v2 下体现为抛异常）")
    void dropPartitionOnlyRemovesThatPartition() {
        createPartition("pe");
        createPartition("pf");
        insertInto("pe", PROBE_DOC_A, 21);
        insertInto("pf", PROBE_DOC_B, 22);

        // ① 先坐实「裸 drop 会失败」—— 这是本探针最值钱的一条，也是步 0 之前没人知道的事。
        //    v1 时代它返回 65535 而"方法正常返回"，当时的断言只看了"查得到 21"，
        //    把原因误判成了"删除可见性延迟"；v2 直接抛，这类误读从根上没了。
        String bareDropFailure = expectFailure("裸 dropPartition（不先 release）",
                () -> dropPartitionRaw(fullName("pe")));
        assertThat(bareDropFailure)
                .as("裸 drop 竟然成功了 —— 那么 `release → drop` 这个固定顺序就失去了证据；"
                        + "而一旦哪天它会静默失败，32 个幽灵分区会重演")
                .isNotNull();

        // ② release 之后再 drop 必须成功
        releaseRaw(fullName("pe"));
        dropPartitionRaw(fullName("pe"));

        // ③ 三个观测点分开断言 —— 只看 query 会把"没删掉"误读成"删除延迟"
        assertThat(hasPartition(fullName("pe")))
                .as("drop 成功后 hasPartition 应为 false")
                .isFalse();
        assertThat(listPartitionNames())
                .as("drop 成功后 listPartitions 不该再列出它")
                .doesNotContain(fullName("pe"));
        // ④ 查一个已 drop 的分区：Milvus **报错**，不是"返回空"也不是"回落到查全部"
        String onDroppedFailure = expectFailure("查已 drop 的分区",
                () -> queryRaw(List.of(fullName("pe"))));
        assertThat(onDroppedFailure)
                .as("""
                        查已 drop 的分区应**显式失败** —— 实测确实如此。\
                        这是**安全**的语义（比静默返回空集、或回落到查全部都好），\
                        但它是检索侧的一条硬约束：**绝不能去查一个可能不存在的分区**，\
                        否则整个检索请求会炸，而不是少几条结果。""")
                .isNotNull();
        assertThat(idsInPartitions(List.of("pf")))
                .as("另一个分区必须完好 —— 这是「滚动清理保留 N 代」的正确性前提")
                .containsExactly("22");
    }

    // ==================== 问题 5：写进不存在的分区 ====================

    @Test
    @DisplayName("探针 5：写进不存在的分区 → 报错（所以实现必须先 ensure-exists，不能靠隐式创建）")
    void insertingIntoMissingPartitionFails() {
        String missing = PROBE_PREFIX + "never_created";
        assertThat(hasPartition(missing)).as("前提：该分区确实不存在").isFalse();

        String failure = expectFailure("写进不存在的分区",
                () -> insertIntoPartition(missing, PROBE_DOC_A, 31));

        assertThat(failure)
                .as("写进不存在的分区必须显式失败 —— 若它静默成功，"
                        + "重建就会把向量写进一个检索侧根本不会去查的地方（本项目最忌的静默故障）")
                .isNotNull();
    }

    // ==================== 问题 6：分区数上限 ====================

    @Test
    @DisplayName("探针 6：连续建 32 个分区全部成功 —— 远超本设计所需（库数 × 保留代数 ≈ 24）")
    void partitionCountCeilingIsFarAboveOurNeed() {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < PARTITION_COUNT_PROBE; i++) {
            String name = PROBE_PREFIX + "n" + i;
            // v2 下这一行不抛就代表成功 —— 不再有"status 非 0 而方法照样返回"的中间态
            createPartitionRaw(name);
            created.add(name);
        }

        // 建成的必须真能列出来 —— 只看调用没抛不够（同 describeIndex 那个教训）
        assertThat(listPartitionNames())
                .as("32 个分区应全部出现在 listPartitions 里")
                .containsAll(created);
    }

    // ==================== 工具 ====================

    private static String fullName(String suffix) {
        return PROBE_PREFIX + suffix;
    }

    private static void createPartitionRaw(String name) {
        client.createPartition(CreatePartitionReq.builder()
                .collectionName(props.getCollection())
                .partitionName(name)
                .build());
    }

    /** ensure-exists 语义：已存在则不重复建（真实实现要按这个来，重复建会报错）。 */
    private static void createPartition(String suffix) {
        String name = fullName(suffix);
        if (hasPartition(name)) {
            return;
        }
        createPartitionRaw(name);
    }

    private static boolean hasPartition(String name) {
        return Boolean.TRUE.equals(client.hasPartition(HasPartitionReq.builder()
                .collectionName(props.getCollection())
                .partitionName(name)
                .build()));
    }

    private static void dropPartitionRaw(String name) {
        client.dropPartition(DropPartitionReq.builder()
                .collectionName(props.getCollection())
                .partitionName(name)
                .build());
    }

    /** 释放分区（drop 的前置）。"本来就没加载"不算错误，故调用方按需选择是否吞掉。 */
    private static void releaseRaw(String name) {
        client.releasePartitions(ReleasePartitionsReq.builder()
                .collectionName(props.getCollection())
                .partitionNames(List.of(name))
                .build());
    }

    private static Set<String> listPartitionNames() {
        List<String> names = client.listPartitions(ListPartitionsReq.builder()
                .collectionName(props.getCollection())
                .build());
        return names == null ? Set.of() : Set.copyOf(names);
    }

    /**
     * 收尾：把所有 {@code zzprobe_} 开头的分区 **release + drop** 掉。
     *
     * <p>⚠️ 第一版这里**没检查返回值** —— 而裸 drop 会失败，于是 32 个探针分区
     * 全留在了集合里（下次再跑 `createPartition` 已存在的分区也会失败）。
     * 这正是本仓硬规矩「拒绝按成功了处理」要防的形态：v2 下失败即抛，
     * 所以清理循环里的异常**不吞**，让它在收尾当场炸出来。
     */
    private static void dropProbePartitions() {
        List<String> leaked = new ArrayList<>();
        for (String name : listPartitionNames()) {
            if (!name.startsWith(PROBE_PREFIX)) {
                continue;
            }
            expectFailure("release（可能本来就没加载）", () -> releaseRaw(name));
            if (expectFailure("drop " + name, () -> dropPartitionRaw(name)) != null) {
                leaked.add(name);
            }
        }
        assertThat(leaked)
                .as("探针分区清理失败 —— 会变成幽灵分区，且下次 createPartition 同名会失败")
                .isEmpty();
    }

    private static void insertIntoPartition(String partition, long docId, long chunkId) {
        JsonObject row = new JsonObject();
        row.addProperty(MilvusConstants.FIELD_ID, String.valueOf(chunkId));
        row.add(MilvusConstants.FIELD_VECTOR, asJsonArray(unitVector(chunkId)));
        row.addProperty(MilvusConstants.FIELD_KB_ID, 1L);
        row.addProperty(MilvusConstants.FIELD_DOC_ID, docId);
        row.addProperty(MilvusConstants.FIELD_CHUNK_TYPE, "PARAGRAPH");
        row.addProperty(MilvusConstants.FIELD_SECRET_LEVEL, "INTERNAL");
        row.addProperty(MilvusConstants.FIELD_CONTENT, "探针");
        row.addProperty(MilvusConstants.FIELD_TITLE_PATH, "");
        // `S5-M07-A` 起 bm25_text 是必填载荷列（未声明 nullable）——
        // 漏了它整批插入会被 Milvus 拒，而报错信息只会说"字段数为空"
        row.addProperty(MilvusConstants.FIELD_BM25_TEXT, "探 针");

        client.insert(InsertReq.builder()
                .collectionName(props.getCollection())
                .partitionName(partition)
                .data(List.of(row))
                .build());
    }

    /** 写进探针分区并断言成功（问题 5 的用例直取"是否抛"来验失败语义）。 */
    private static void insertInto(String suffix, long docId, long chunkId) {
        insertIntoPartition(fullName(suffix), docId, chunkId);
    }

    /** 按分区**后缀**查（成功即返回 id 列表；要验失败语义请用 {@link #expectFailure} 包 {@link #queryRaw}）。 */
    private static List<String> idsInPartitions(List<String> suffixes) {
        QueryResp resp = queryRaw(suffixes.stream().map(MilvusPartitionProbeTest::fullName).toList());
        if (resp.getQueryResults() == null) {
            return List.of();
        }
        return resp.getQueryResults().stream()
                .map(r -> String.valueOf(r.getEntity().get(MilvusConstants.FIELD_ID)))
                .collect(Collectors.toList());
    }

    /** 原样返回查询结果 —— 调用方自己判成败（查已 drop 的分区会抛，那是有意义的观测）。 */
    private static QueryResp queryRaw(List<String> fullPartitionNames) {
        QueryReq.QueryReqBuilder builder = QueryReq.builder()
                .collectionName(props.getCollection())
                .filter(MilvusConstants.FIELD_DOC_ID + " in [" + PROBE_DOC_A + "," + PROBE_DOC_B + "]")
                .outputFields(List.of(MilvusConstants.FIELD_ID))
                .consistencyLevel(ConsistencyLevel.STRONG);
        if (!fullPartitionNames.isEmpty()) {
            builder.partitionNames(fullPartitionNames);
        }
        return client.query(builder.build());
    }

    /**
     * 执行一个**预期会失败**的操作，返回失败原因；没失败则返回 {@code null}。
     *
     * <p>这是 v1 → v2 迁移里唯一"换表达方式"的地方：v1 把失败塞进返回值，
     * v2 直接抛。三条探针结论（裸 drop 会失败 / 查已 drop 分区会失败 / 写不存在的分区会失败）
     * 因此从"断言 status ≠ 0"变成"断言真的抛了"。
     *
     * <p>⚠️ <b>不要把它当"吞异常"用</b>：返回 {@code null} 的语义是
     * 「本该失败的**成功了**」—— 调用方必须把这个 null 当作**失败**来处理。
     */
    private static String expectFailure(String what, Runnable action) {
        try {
            action.run();
            return null;
        } catch (Exception ex) {
            return what + " → " + rootMessage(ex);
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor.toString();
    }

    private static float[] unitVector(long seed) {
        float[] v = new float[MilvusConstants.DIMENSION];
        v[(int) (seed % MilvusConstants.DIMENSION)] = 1.0f;
        return v;
    }

    /** FloatVector 字段要的是 JSON 数组，不是 {@code float[]}。 */
    private static JsonArray asJsonArray(float[] vector) {
        JsonArray out = new JsonArray(vector.length);
        for (float v : vector) {
            out.add(v);
        }
        return out;
    }
}
