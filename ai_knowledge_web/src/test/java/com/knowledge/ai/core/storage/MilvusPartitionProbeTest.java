package com.knowledge.ai.core.storage;

import com.knowledge.ai.core.constant.MilvusConstants;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.ShowPartitionsResponse;
import io.milvus.param.ConnectParam;
import io.milvus.param.R;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.param.partition.CreatePartitionParam;
import io.milvus.param.partition.DropPartitionParam;
import io.milvus.param.partition.HasPartitionParam;
import io.milvus.param.partition.ReleasePartitionsParam;
import io.milvus.param.partition.ShowPartitionsParam;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `S4-D` **步 0 探针**：Milvus 2.6 的 partition 能力实测 —— 决定整批的重建原子性方案。
 *
 * <h3>为什么必须真库探，而不是只看 SDK 方法面</h3>
 * <p>{@code javap} 已证 {@code withPartitionName}（写入）/ {@code withPartitionNames}（检索、查询）/
 * {@code createPartition}·{@code dropPartition}·{@code hasPartition}·{@code showPartitions} **都在**。
 * 但**方法在 ≠ 行为对** —— 本仓库刚被这类事咬过：{@code describeIndex(fieldName=…)} 的参数
 * **被服务端静默忽略**，守卫因此恒真，4 个 INVERTED 索引从 S0 起从未创建且零日志
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

    private static MilvusServiceClient client;

    private static MilvusProperties props;

    @BeforeAll
    static void setUp() {
        props = new MilvusProperties();
        client = new MilvusServiceClient(ConnectParam.newBuilder()
                .withHost(props.getHost())
                .withPort(props.getPort())
                .withConnectTimeout(props.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
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
    @DisplayName("探针 4：drop 前必须先 release —— 直接 drop 已加载分区会以 65535 失败（且不检查返回值就看不出来）")
    void dropPartitionOnlyRemovesThatPartition() {
        createPartition("pe");
        createPartition("pf");
        insertInto("pe", PROBE_DOC_A, 21);
        insertInto("pf", PROBE_DOC_B, 22);

        // ① 先坐实「裸 drop 会失败」—— 这是本探针最值钱的一条，也是步 0 之前没人知道的事
        R<?> bareDrop = dropPartitionRaw(fullName("pe"));
        assertThat(bareDrop.getStatus())
                .as("""
                        **裸 dropPartition（不先 release）竟然成功了** —— \
                        那说明它是被 Milvus 拒绝的：本探针第一次跑时它返回 65535(UnexpectedError)，\
                        而当时的断言只看了"查得到 21"，把原因误判成了"删除可见性延迟"。\
                        [服务端原话] %s""", msg(bareDrop))
                .isNotEqualTo(R.Status.Success.getCode());

        // ② release 之后再 drop 必须成功
        R<?> released = releaseRaw(fullName("pe"));
        R<?> dropped = dropPartitionRaw(fullName("pe"));
        assertThat(dropped.getStatus())
                .as("release 之后 drop 应成功。[release 返回] %s ; [drop 原话] %s",
                        released.getStatus(), msg(dropped))
                .isEqualTo(R.Status.Success.getCode());

        // ③ 三个观测点分开断言 —— 只看 query 会把"没删掉"误读成"删除延迟"
        assertThat(hasPartition(fullName("pe")))
                .as("drop 成功后 hasPartition 应为 false")
                .isFalse();
        assertThat(listPartitionNames())
                .as("drop 成功后 showPartitions 不该再列出它")
                .doesNotContain(fullName("pe"));
        // ④ 查一个已 drop 的分区：Milvus **报错**（65535），不是"返回空"也不是"回落到查全部"
        R<QueryResults> onDropped = queryRaw(List.of(fullName("pe")));
        assertThat(onDropped.getStatus())
                .as("""
                        查已 drop 的分区应**显式失败** —— 实测确实如此（65535）。\
                        这是**安全**的语义（比静默返回空集、或回落到查全部都好），\
                        但它是检索侧的一条硬约束：**绝不能去查一个可能不存在的分区**，\
                        否则整个检索请求会炸，而不是少几条结果。\
                        [服务端原话] %s""", msg(onDropped))
                .isNotEqualTo(R.Status.Success.getCode());
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

        R<?> result = insertIntoPartition(missing, PROBE_DOC_A, 31);

        assertThat(result.getStatus())
                .as("写进不存在的分区必须显式失败 —— 若它静默成功，"
                        + "重建就会把向量写进一个检索侧根本不会去查的地方（本项目最忌的静默故障）")
                .isNotEqualTo(R.Status.Success.getCode());
    }

    // ==================== 问题 6：分区数上限 ====================

    @Test
    @DisplayName("探针 6：连续建 32 个分区全部成功 —— 远超本设计所需（库数 × 保留代数 ≈ 24）")
    void partitionCountCeilingIsFarAboveOurNeed() {
        List<String> created = new ArrayList<>();
        for (int i = 0; i < PARTITION_COUNT_PROBE; i++) {
            String name = PROBE_PREFIX + "n" + i;
            R<?> result = createPartitionRaw(name);
            assertThat(result.getStatus())
                    .as("建第 %d 个分区就失败 —— 分区数上限不足以支撑 库数 × 保留代数", i + 1)
                    .isEqualTo(R.Status.Success.getCode());
            created.add(name);
        }

        // 建成的必须真能列出来 —— 只看返回值不够（同 describeIndex 那个教训）
        assertThat(listPartitionNames())
                .as("32 个分区应全部出现在 showPartitions 里")
                .containsAll(created);
    }

    // ==================== 工具 ====================

    private static String fullName(String suffix) {
        return PROBE_PREFIX + suffix;
    }

    private static R<?> createPartitionRaw(String name) {
        return client.createPartition(CreatePartitionParam.newBuilder()
                .withCollectionName(props.getCollection())
                .withPartitionName(name)
                .build());
    }

    /** ensure-exists 语义：已存在则不重复建（真实实现要按这个来，重复建会报错）。 */
    private static void createPartition(String suffix) {
        String name = fullName(suffix);
        if (hasPartition(name)) {
            return;
        }
        assertThat(createPartitionRaw(name).getStatus())
                .as("建分区 %s 应成功", name)
                .isEqualTo(R.Status.Success.getCode());
    }

    private static boolean hasPartition(String name) {
        R<Boolean> result = client.hasPartition(HasPartitionParam.newBuilder()
                .withCollectionName(props.getCollection())
                .withPartitionName(name)
                .build());
        assertThat(result.getStatus()).isEqualTo(R.Status.Success.getCode());
        return Boolean.TRUE.equals(result.getData());
    }

    private static R<?> dropPartitionRaw(String name) {
        return client.dropPartition(DropPartitionParam.newBuilder()
                .withCollectionName(props.getCollection())
                .withPartitionName(name)
                .build());
    }

    /**
     * 释放分区（drop 的前置）。
     *
     * <p>返回原样交给调用方判断 —— 清理路径上"已释放"不是错误，但**失败原因要能带出去**。
     */
    private static R<?> releaseRaw(String name) {
        return client.releasePartitions(ReleasePartitionsParam.newBuilder()
                .withCollectionName(props.getCollection())
                .withPartitionNames(List.of(name))
                .build());
    }

    private static Set<String> listPartitionNames() {
        R<ShowPartitionsResponse> result = client.showPartitions(ShowPartitionsParam.newBuilder()
                .withCollectionName(props.getCollection())
                .build());
        assertThat(result.getStatus()).isEqualTo(R.Status.Success.getCode());
        return Set.copyOf(result.getData().getPartitionNamesList());
    }

    /**
     * 收尾：把所有 {@code zzprobe_} 开头的分区 **release + drop** 掉。
     *
     * <p>⚠️ 第一版这里**没检查返回值** —— 而裸 drop 会以 65535 失败，于是 32 个探针分区
     * 全留在了集合里（下次再跑 `createPartition` 已存在的分区也会失败）。
     * 这正是本仓硬规矩「拒绝按成功了处理」要防的形态，收尾**必须校验并断言**。
     */
    private static void dropProbePartitions() {
        List<String> leaked = new ArrayList<>();
        for (String name : listPartitionNames()) {
            if (!name.startsWith(PROBE_PREFIX)) {
                continue;
            }
            releaseRaw(name);
            if (dropPartitionRaw(name).getStatus() != R.Status.Success.getCode()) {
                leaked.add(name);
            }
        }
        assertThat(leaked)
                .as("探针分区清理失败 —— 会变成幽灵分区，且下次 createPartition 同名会失败")
                .isEmpty();
    }

    private static R<?> insertIntoPartition(String partition, long docId, long chunkId) {
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field(MilvusConstants.FIELD_ID, List.of(String.valueOf(chunkId))));
        fields.add(new InsertParam.Field(MilvusConstants.FIELD_VECTOR,
                List.of(asFloatList(unitVector(chunkId)))));
        fields.add(new InsertParam.Field(MilvusConstants.FIELD_KB_ID, List.of(1L)));
        fields.add(new InsertParam.Field(MilvusConstants.FIELD_DOC_ID, List.of(docId)));
        fields.add(new InsertParam.Field(MilvusConstants.FIELD_CHUNK_TYPE, List.of("PARAGRAPH")));
        fields.add(new InsertParam.Field(MilvusConstants.FIELD_SECRET_LEVEL, List.of("INTERNAL")));
        fields.add(new InsertParam.Field(MilvusConstants.FIELD_CONTENT, List.of("探针")));
        fields.add(new InsertParam.Field(MilvusConstants.FIELD_TITLE_PATH, List.of("")));

        return client.insert(InsertParam.newBuilder()
                .withCollectionName(props.getCollection())
                .withPartitionName(partition)
                .withFields(fields)
                .build());
    }

    /** 写进探针分区并断言成功（问题 5 的用例走 {@link #insertIntoPartition} 直取返回值断言失败）。 */
    private static void insertInto(String suffix, long docId, long chunkId) {
        R<?> result = insertIntoPartition(fullName(suffix), docId, chunkId);
        assertThat(result.getStatus())
                .as("写进分区 %s 应成功", suffix)
                .isEqualTo(R.Status.Success.getCode());
    }

    /** 按分区**后缀**查并**断言成功**（用例专用；要验失败语义请用 {@link #queryRaw}）。 */
    private static List<String> idsInPartitions(List<String> suffixes) {
        R<QueryResults> result = queryRaw(
                suffixes.stream().map(MilvusPartitionProbeTest::fullName).toList());
        assertThat(result.getStatus())
                .as("查分区 %s 应成功。[服务端原话] %s", suffixes, msg(result))
                .isEqualTo(R.Status.Success.getCode());
        return result.getData().getFieldsDataList().stream()
                .filter(f -> MilvusConstants.FIELD_ID.equals(f.getFieldName()))
                .flatMap(f -> f.getScalars().getStringData().getDataList().stream())
                .collect(Collectors.toList());
    }

    /** 原样返回查询结果 —— 让调用方自己判成败（查已 drop 的分区会失败，那是有意义的观测）。 */
    private static R<QueryResults> queryRaw(List<String> fullPartitionNames) {
        QueryParam.Builder builder = QueryParam.newBuilder()
                .withCollectionName(props.getCollection())
                .withExpr(MilvusConstants.FIELD_DOC_ID + " in [" + PROBE_DOC_A + "," + PROBE_DOC_B + "]")
                .withOutFields(List.of(MilvusConstants.FIELD_ID))
                .withConsistencyLevel(ConsistencyLevelEnum.STRONG);
        if (!fullPartitionNames.isEmpty()) {
            builder.withPartitionNames(fullPartitionNames);
        }
        return client.query(builder.build());
    }

    private static float[] unitVector(long seed) {
        float[] v = new float[MilvusConstants.DIMENSION];
        v[(int) (seed % MilvusConstants.DIMENSION)] = 1.0f;
        return v;
    }

    /**
     * 安全取 {@code R} 的文本。
     *
     * <p>⚠️ {@code R#getMessage()} 在**成功**时内部 {@code exception} 为 null，直接调会 NPE
     * （本探针第一版就在断言描述里踩了）—— 失败的诊断信息反而把测试自己搞崩。
     */
    private static String msg(R<?> result) {
        try {
            return String.valueOf(result.getMessage());
        } catch (RuntimeException ex) {
            return "<无异常对象：status=" + result.getStatus() + ">";
        }
    }

    /** FloatVector 字段要的是 {@code List<List<Float>>}，不是 {@code List<float[]>}。 */
    private static List<Float> asFloatList(float[] vector) {
        List<Float> out = new ArrayList<>(vector.length);
        for (float v : vector) {
            out.add(v);
        }
        return out;
    }
}
