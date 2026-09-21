package com.knowledge.ai.service;

import com.knowledge.ai.core.async.ParseWorker;
import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.storage.DocStorage;
import com.knowledge.ai.core.storage.MilvusPartitionManager;
import com.knowledge.ai.core.storage.MilvusProperties;
import com.knowledge.ai.pipeline.vectorize.VectorChunk;
import com.knowledge.ai.pipeline.vectorize.VectorRecord;
import com.knowledge.ai.pipeline.vectorize.VectorWriter;
import com.knowledge.ai.support.ApiTestSupport;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **判据 2**（同名上传 ⇒ 同组新版本；非同名 ⇒ 新组）· **判据 3**（归档后旧版本向量被清）。
 *
 * <h2>为什么走真 HTTP + 真 multipart，而不是直调 {@code DocService#upload}</h2>
 * <p>分组判定发生在 {@code DocRecordWriter#insertVersioned} 里，而它的输入
 * {@code (kbId, ownerId, fileName)} 三个值**全部来自容器解析出的请求**：
 * 文件名来自 {@code MultipartFile#getOriginalFilename()}、上传人来自登录态。
 * 直调 Service 就得自己造 {@code MultipartFile} 替身与 {@code LoginUserContext}，
 * 那测的是"我把参数传对了"，不是"接口把这三样认对了"。同 {@code DocUploadFlowTest} 的口径。
 *
 * <h2>🔴 判据 3 的向量**从 Milvus 读回来**，不是"我们调用过清理"</h2>
 * <p>"归档后旧版本向量被清"这句话，只有把旧版本的向量**先真的写进去**、再读回来确认是 0
 * 才算被验过。所以本类用真的 {@code VectorWriter} 往探针分区写，并用独立
 * {@code MilvusClientV2}（{@code ConsistencyLevel.STRONG}）读回。
 * 若只断言"上传接口返回了成功"，{@code replaceDocument(..., List.of())} 被谁注释掉都测不出来
 * —— 那正是本仓反复钉的"报告说成功了"。
 *
 * <p>嵌入开关**不开**（本类没有 {@code @TestPropertySource}）：判据 3 验的是**清理**
 * （一次 Milvus 删除），那条路不需要嵌入；把真嵌入拉进来只会让这条判据多依赖一个外部服务。
 * 需要真向量的那半在本批的 {@code DocRollbackFlowTest} 里（那里必须调嵌入，因为
 * {@code G7-6} 的原文就是"重新向量化"）。
 *
 * <h2>为什么要造"回滚失败留下的作废行"</h2>
 * <p>{@link #reuploadAfterAbandonedRowKeepsVersionNumbersDistinct} 钉的是一条**新发现的口径**：
 * 上传路径原先把版本号算成"当前行的版本号 + 1"，而回滚路径算的是"组内 max(version_no) + 1"
 * （{@code DocRecordWriter#insertRollbackVersion}）。同一个不变量有两个写法时，作废行
 * （{@code DocVersionService#abandon} 留下的 {@code is_current=0} 行）会让两者分家 ——
 * 组里冒出**两个 v3**，而 {@code findByVersion} 用的是 {@code findFirst()}。
 * 这个类的存在理由就是让那种分家当场转红。
 */
@DisplayName("判据 2/3 · 同名上传归档为新版本 + 非同名新建组 + 归档清旧向量")
class UploadArchiveFlowTest extends ApiTestSupport {

    /** 探针库编码前缀（{@code ^kb_[a-z0-9_]{2,}$}），供残留排查时一眼认出。 */
    private static final String PROBE_PREFIX = "kb_probe_up_";

    /** 夹具文档的向量落在第 1 代分区（{@code V16}：文档的 {@code index_generation} 0 = 没有向量，首代从 1 起）。 */
    private static final int GENERATION = 1;

    /** 读 Milvus 用的独立客户端（不在 Spring 上下文里，与 {@code MilvusVectorWriteTest} 同一条做法）。 */
    private static MilvusClientV2 milvus;

    /** 本用例造过的库 id（结束后一并清掉：对象 → 分片 → 问题行 → 文档 → 授权 → 库）。 */
    private final List<Long> probeKbIds = new ArrayList<>();

    @Autowired
    private ParseWorker parseWorker;

    @Autowired
    private DocStorage docStorage;

    @Autowired
    private MilvusPartitionManager partitions;

    @Autowired
    private VectorWriter vectorWriter;

    @Autowired
    private JdbcTemplate jdbc;

    private String token;

    @BeforeAll
    static void openMilvus() {
        MilvusProperties props = new MilvusProperties();
        milvus = new MilvusClientV2(ConnectConfig.builder()
                .uri("http://" + props.getHost() + ":" + props.getPort())
                .connectTimeoutMs(props.getConnectTimeoutMs())
                .rpcDeadlineMs(props.getRpcDeadlineMs())
                .build());
    }

    @AfterAll
    static void closeMilvus() {
        if (milvus != null) {
            milvus.close();
        }
    }

    @BeforeEach
    void login() {
        token = loginAsAdmin().data("$.accessToken");
    }

    /**
     * 清理与 {@code DocUploadFlowTest#cleanProbeData} 同源（那里记着两次实测踩到的遗漏：
     * 孤儿分片、以及攒到 1030 个对象的 MinIO 桶）。对象名必须在**删行之前**取出来。
     */
    @AfterEach
    void cleanProbeData() {
        for (Long kbId : probeKbIds) {
            for (Map<String, Object> row : jdbc.queryForList(
                    "select storage_path, parse_path from sys_document where kb_id = ?", kbId)) {
                removeObject(row.get("storage_path"));
                removeObject(row.get("parse_path"));
            }
            // 先丢 Milvus 分区，再删库族数据（顺序与 DocRollbackFlowTest 一致：分区名由 kbId 决定）
            for (int generation : partitions.generationsOf(kbId)) {
                partitions.dropQuietly(MilvusConstants.partitionName(kbId, generation));
            }
            jdbc.update("delete from sys_chunk where kb_id = ?", kbId);
            jdbc.update("delete from sys_parse_issue where kb_id = ?", kbId);
            jdbc.update("delete from sys_document where kb_id = ?", kbId);
            jdbc.update("delete from sys_kb_grant where kb_id = ?", kbId);
            jdbc.update("delete from sys_kb where id = ?", kbId);
        }
        probeKbIds.clear();
    }

    private void removeObject(Object objectKey) {
        if (objectKey != null && !objectKey.toString().isBlank()) {
            docStorage.remove(objectKey.toString());
        }
    }

    // ==================================================================
    // 判据 2 / 3 · 同名 ⇒ 同组新版本 + 归档清旧向量
    // ==================================================================

    @Test
    @DisplayName("判据 2/3：同名二次上传 ⇒ 同组 v2 · 旧行 is_current=0 · 组内恰一行当前 · "
            + "旧版本向量从 Milvus 清空 · 旧版本分片一行不删")
    void sameNameReuploadArchivesThePreviousVersion() {
        long kbId = createProbeKb();
        String partition = MilvusConstants.partitionName(kbId, GENERATION);
        partitions.ensure(partition);

        long v1 = uploadAndWait(kbId, "# 手册 v1\n\n第一版正文。", "手册.md");

        // 首版自成一组的**锚点**：doc_group_id 回填成自身 id（V39 的 ①/③ 口径在上传路径上的落点）
        assertThat(num(row(v1).get("doc_group_id"))).as("首版的组标识 = 自身 id").isEqualTo(v1);
        assertThat(num(row(v1).get("version_no"))).isEqualTo(1);
        assertThat(num(row(v1).get("is_current"))).isEqualTo(1);

        // ---- 把 v1 摆成"已经解析并向量化过"的样子 ----
        // 这是判据 3 的**前置**：一个从来没有向量的旧版本，"它的向量被清空"是一句空话。
        long c1 = insertChunk(v1, "第一版正文");
        long c2 = insertChunk(v1, "第一版的第二段");
        jdbc.update("update sys_document set chunk_count = 2, index_generation = ? where id = ?",
                GENERATION, v1);
        // ⚠️ 必须**一次调用写两条**：`replaceDocument` 的语义是"替换这篇文档的向量"
        // （先 delete by doc_id 再 insert），分两次调等于第二次把第一次删掉 ——
        // 实测第一版就是这么写的，前置断言当场读到 1 条。
        seedVectors(partition, kbId, v1, List.of(
                vectorFor(kbId, v1, c1, "第一版正文"),
                vectorFor(kbId, v1, c2, "第一版的第二段")));
        assertThat(vectorsOf(partition, v1))
                .as("前置：v1 此刻在 Milvus 里真的有 2 条向量 —— 否则下面那句「被清空」是空断言")
                .isEqualTo(2);
        // 分片也留一份前置快照：“一行不删”要比的是**同一批 id**，不是某个写死的条数
        // （真解析给 v1 落的那些分片有几条，取决于解析器，用例不该把它算进自己的算术里）
        List<Long> chunksBefore = chunkIdsOf(v1);

        // ---- 同名（内容变了）再传一次 ----
        long v2 = uploadAndWait(kbId, "# 手册 v2\n\n第二版正文，内容真的变了。", "手册.md");

        assertThat(v2).as("同名上传产生**新行**，不覆盖旧行（需求 §3.2「覆盖即产生新版本」）")
                .isNotEqualTo(v1);
        assertThat(num(row(v2).get("doc_group_id"))).as("同名 ⇒ 同组").isEqualTo(v1);
        assertThat(num(row(v2).get("version_no"))).isEqualTo(2);
        assertThat(num(row(v2).get("is_current")))
                .as("新版本成为当前版本 ⇒ 它才是检索侧唯一放行的那一行（G7-5）")
                .isEqualTo(1);
        assertThat(num(row(v1).get("is_current"))).as("旧版本退出当前版本").isZero();
        assertThat(count("select count(*) from sys_document where doc_group_id = ? and is_current = 1", v1))
                .as("组内**恰有一行** is_current=1")
                .isEqualTo(1);

        // 判据 3 的正题：**读回来**是 0，不是"我们调用过清理"
        assertThat(vectorsOf(partition, v1))
                .as("归档后旧版本的向量必须真的从 Milvus 里消失")
                .isZero();

        // 归档**一行都不删**：分片留给历史问答的引用（I-5 的另一半，与回滚同一条口径）
        assertThat(chunksBefore).as("前置：v1 至少有我插的两条分片").hasSizeGreaterThanOrEqualTo(2);
        assertThat(chunkIdsOf(v1))
                .as("旧版本的分片必须**逐行原样**保留 —— 删了它们，历史问答的引用就成了悬空 id")
                .containsExactlyElementsOf(chunksBefore);

        // 版本列表（读侧）也认这件事：当前版本 = v2，组内两行
        List<Map<String, Object>> list = get("/api/doc/" + v2 + "/versions", token)
                .assertOk("版本列表").data("$.list");
        assertThat(list).hasSize(2);
        // ⚠️ 先赋给显式类型的变量：`data()` 是 `<T> T data(String)`，直接当 `assertThat` 的实参
        //    会与 AssertJ 的 `assertThat(Predicate<T>)` 重载撞上（引用不明确，编译期就红）
        String current = get("/api/doc/" + v2 + "/versions", token).assertOk("版本列表").data("$.current");
        assertThat(current).as("当前版本 = 带 is_current=1 的那一行").isEqualTo("v2");
    }

    @Test
    @DisplayName("判据 2：不同文件名 ⇒ 各自成组 · 各自的 v1 · 旧组不受影响")
    void differentFileNameStartsANewGroup() {
        long kbId = createProbeKb();

        long a = uploadAndWait(kbId, "# 手册\n\n甲文件的正文。", "手册.md");
        long b = uploadAndWait(kbId, "# 附录\n\n乙文件的正文。", "附录.md");

        assertThat(num(row(b).get("doc_group_id")))
                .as("文件名不同 ⇒ 新组（组键是 kb_id + owner_id + file_name，不是 kb_id 单键）")
                .isEqualTo(b);
        assertThat(num(row(b).get("version_no"))).isEqualTo(1);
        assertThat(num(row(a).get("doc_group_id"))).isEqualTo(a);
        assertThat(num(row(a).get("version_no"))).as("甲文件不该因为别人上传而涨版本").isEqualTo(1);
        assertThat(num(row(a).get("is_current"))).isEqualTo(1);
        assertThat(count("select count(*) from sys_document where doc_group_id = ?", a))
                .as("甲文件那一组仍只有它自己一行")
                .isEqualTo(1);
        assertThat((List<?>) get("/api/doc/" + b + "/versions", token).assertOk("附录的版本列表").data("$.list"))
                .as("版本列表按组取 —— 从乙文件打进去不该看见甲文件")
                .hasSize(1);
    }

    @Test
    @DisplayName("判据 2：同名同人但**换了知识库** ⇒ 新组（组键的左半边是 kb_id）")
    void sameNameInAnotherKbStartsANewGroup() {
        long kbA = createProbeKb();
        long kbB = createProbeKb();
        byte[] same = "# 手册\n\n两份库里同名同内容的文件。".getBytes(StandardCharsets.UTF_8);

        long inA = uploadAndWait(kbA, same, "手册.md", token);
        long inB = uploadAndWait(kbB, same, "手册.md", token);

        assertThat(num(row(inB).get("doc_group_id")))
                .as("换成另一个库 ⇒ 新组；否则跨库归档会把 A 库的文档按下去")
                .isEqualTo(inB);
        assertThat(num(row(inB).get("version_no"))).isEqualTo(1);
        assertThat(num(row(inA).get("version_no"))).isEqualTo(1);
        assertThat(num(row(inA).get("is_current")))
                .as("A 库那一版必须仍是当前版本（同一个 md5 在另一库上传不该影响它）")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("判据 2：同名同库但**换了上传人** ⇒ 新组（组键含 owner_id）")
    void sameNameFromAnotherOwnerStartsANewGroup() {
        long kbId = createProbeKb();
        byte[] bytes = "# 手册\n\n同名，换个人传。".getBytes(StandardCharsets.UTF_8);

        long mine = uploadAndWait(kbId, bytes, "手册.md", token);

        // 探针账号（新注册用户默认 USER 角色：本该能看见库、且 doc:upload 是 WRITE）
        TestUser other = createUser("up.other");
        long theirs = uploadAndWait(kbId, bytes, "手册.md", other.accessToken());

        assertThat(num(row(theirs).get("doc_group_id")))
                .as("换人 ⇒ 新组。否则别人传一个同名文件就能把我这一版归档掉")
                .isEqualTo(theirs);
        assertThat(num(row(theirs).get("version_no"))).isEqualTo(1);
        assertThat(num(row(theirs).get("owner_id"))).isNotEqualTo(num(row(mine).get("owner_id")));
        assertThat(num(row(mine).get("version_no"))).as("别人上传不该让我的版本号 +1").isEqualTo(1);
        assertThat(num(row(mine).get("is_current"))).isEqualTo(1);
        assertThat(count("select count(*) from sys_document where doc_group_id = ?", mine))
                .isEqualTo(1);
    }

    // ==================================================================
    // 版本号的唯一性（上传路径与回滚路径必须是同一个口径）
    // ==================================================================

    @Test
    @DisplayName("判据 2（补）：组内有作废行（回滚失败留下的 v3）时，再次同名上传取 v4 —— "
            + "版本号在组内唯一，作废行不许被当成'上一个当前版本'")
    void reuploadAfterAbandonedRowKeepsVersionNumbersDistinct() {
        long kbId = createProbeKb();
        long v1 = uploadAndWait(kbId, "# 手册 v1\n\n第一版。", "手册.md");
        long v2 = uploadAndWait(kbId, "# 手册 v2\n\n第二版。", "手册.md");

        // 造一条"回滚失败留下的作废行"——形状与 DocVersionService#abandon 落下的一致：
        // 版本号 max+1、is_current=0、status=FAILED。**它仍然占着 v3 这个号**。
        long ghost = insertAbandonedRow(kbId, v1, "手册.md", 3);
        assertThat(count("select count(distinct version_no) from sys_document where doc_group_id = ?", v1))
                .as("前置：组内此刻有三个不同的版本号（1/2/3）")
                .isEqualTo(3);

        long v3 = uploadAndWait(kbId, "# 手册 v3\n\n第三版。", "手册.md");

        assertThat(num(row(v3).get("version_no")))
                .as("作废行占着 v3 ⇒ 新版本必须往下取 v4。取 3 的话组内出现两个 v3，"
                        + "而 DocVersionService#findByVersion 用的是 findFirst() ⇒ 该版本号指向哪一行不确定")
                .isEqualTo(4);
        assertThat(count("select count(*) from sys_document where doc_group_id = ?", v1)).isEqualTo(4);
        assertThat(count("select count(distinct version_no) from sys_document where doc_group_id = ?", v1))
                .as("组内版本号两两不同")
                .isEqualTo(4);
        assertThat(num(row(ghost).get("is_current")))
                .as("作废行不是当前版本 —— 归档翻的是 v2 那一行，不许动它")
                .isZero();
        assertThat(row(ghost).get("status")).as("作废行的状态也不该被改写").isEqualTo("FAILED");
        assertThat(num(row(v2).get("is_current"))).as("被归档的是 v2（它才是当前版本）").isZero();
        assertThat(count("select count(*) from sys_document where doc_group_id = ? and is_current = 1", v1))
                .as("恰有一行当前版本")
                .isEqualTo(1);
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /** 真 multipart 上传 + 等异步任务 + 驱动一轮解析，返回新行的 doc id。 */
    private long uploadAndWait(long kbId, String text, String filename) {
        return uploadAndWait(kbId, text.getBytes(StandardCharsets.UTF_8), filename, token);
    }

    /**
     * 见 {@link #uploadAndWait(long, String, String)}；{@code accessToken} 指定上传人。
     *
     * <p>与 {@code DocUploadFlowTest#upload} 同一形状：接口是"受理即返回"的，
     * {@code storage_path} 由工作线程回写 ⇒ 不等就断言，测到的是竞态而不是功能。
     * {@code S4-E1} 起上传（转存）与解析是两个作业，所以还要显式 {@code drainOnce()}
     * 把解析作业跑一轮（本类只借它把文档推进到终态，不对解析产物下断言）。
     */
    private long uploadAndWait(long kbId, byte[] content, String filename, String accessToken) {
        RawResponse accepted = postMultipart("/api/kb/" + kbId + "/doc/upload", content, filename,
                "application/octet-stream", accessToken);
        accepted.assertOk("上传 " + filename);
        awaitTaskDone(accepted.data("$.taskId"), accessToken);
        parseWorker.drainOnce();
        return ((Number) accepted.data("$.docId")).longValue();
    }

    private void awaitTaskDone(String taskId, String accessToken) {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            // 泛型要显式写：`data()` 是 `<T> T data(String)`，不给类型时推成 Object 比不了 int
            Integer percent = get("/api/doc/tasks/" + taskId, accessToken).assertOk("轮询任务").data("$.percent");
            if (percent != null && percent >= 100) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError("任务 60s 内未到终态：" + taskId);
    }

    /** 建一个探针库（走真接口，拿到的是一个各列齐全的合法库行），登记待清。 */
    private long createProbeKb() {
        String code = PROBE_PREFIX + Long.toString(System.nanoTime(), 36);
        long kbId = ((Number) post("/api/kb", formWith(code), token)
                .assertOk("建探针库").data("$.id")).longValue();
        probeKbIds.add(kbId);
        return kbId;
    }

    private Map<String, Object> row(long docId) {
        return jdbc.queryForMap("select kb_id, owner_id, file_name, doc_group_id, version_no, "
                + "is_current, status, chunk_count, size_bytes, index_generation "
                + "from sys_document where id = ?", docId);
    }

    /** 一篇文档全部分片的 id（按 id 升序，可逐行比对）。 */
    private List<Long> chunkIdsOf(long docId) {
        return jdbc.queryForList("select id from sys_chunk where doc_id = ? order by id",
                Long.class, docId);
    }

    /** 一条分片（只填判据用得到的列），返回它的 id。 */
    private long insertChunk(long docId, String content) {
        int orderNo = (int) count("select count(*) from sys_chunk where doc_id = ?", docId) + 1;
        // kb_id 由文档行带出来（不在测试里重述一遍"这篇属于哪个库"）
        jdbc.update("""
                        insert into sys_chunk (doc_id, kb_id, chunk_type, raw_content, order_no,
                                               page_no, created_at)
                        select ?, kb_id, 'PARAGRAPH', ?, ?, 1, now()
                          from sys_document where id = ?
                        """, docId, content, orderNo, docId);
        return jdbc.queryForObject("select id from sys_chunk where doc_id = ? and order_no = ?",
                Long.class, docId, orderNo);
    }

    /**
     * 直接写一行"回滚失败留下的作废版本"：与 {@code DocVersionService#abandon} 落下的形状一致。
     * 分片数为 0（那一行没有可用分片）、{@code storage_path} 为 null（没有对象可清），
     * 组标识指向本组锚点、{@code is_current = 0}。
     */
    private long insertAbandonedRow(long kbId, long groupId, String fileName, int versionNo) {
        jdbc.update("""
                        insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5,
                                                  storage_path, status, chunk_count, table_total, table_parsed,
                                                  image_total, image_parsed, pages_total, pages_with_text,
                                                  doc_group_id, version_no, is_current,
                                                  created_at, updated_at)
                        values (?, 1, ?, ?, 'PDF', 900, 'ffffffffffffffffffffffffffffffff',
                                null, 'FAILED', 0, 0, 0, 0, 0, 1, 1,
                                ?, ?, 0, now(), now())
                        """, kbId, "作废行 " + fileName, fileName, groupId, versionNo);
        return jdbc.queryForObject(
                "select id from sys_document where doc_group_id = ? and version_no = ?",
                Long.class, groupId, versionNo);
    }

    /** 直接把向量写进分区（模拟"这一版此前已被向量化"）。**一次给全**，理由见调用处。 */
    private void seedVectors(String partition, long kbId, long docId, List<VectorRecord> records) {
        vectorWriter.replaceDocument(partition, docId, records);
    }

    private static VectorRecord vectorFor(long kbId, long docId, long chunkId, String text) {
        return new VectorRecord(new VectorChunk(chunkId, "PARAGRAPH", text, null, "INTERNAL", null),
                kbId, docId, unitVector(text));
    }

    private long vectorsOf(String partition, long docId) {
        QueryResp resp = milvus.query(QueryReq.builder()
                .collectionName(new MilvusProperties().getCollection())
                .partitionNames(List.of(partition))
                .filter(MilvusConstants.FIELD_DOC_ID + " in [" + docId + "]")
                .outputFields(List.of(MilvusConstants.FIELD_ID))
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build());
        return resp.getQueryResults() == null ? 0 : resp.getQueryResults().size();
    }

    private static float[] unitVector(String seed) {
        float[] v = new float[MilvusConstants.DIMENSION];
        v[Math.abs(seed.hashCode()) % MilvusConstants.DIMENSION] = 1.0f;
        return v;
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    /**
     * 读一个数值列，把 {@code is_current} 这类 {@code TINYINT(1)} 也一并接住。
     *
     * <p>⚠️ MySQL 的 {@code TINYINT(1)} 经 JDBC 回来是 **{@code Boolean}**（不是 {@code Integer}），
     * 直接 {@code (Number) value} 会抛 {@code ClassCastException} —— 报错信息里只有
     * "Boolean cannot be cast to Number"，看不出是哪个列。{@code DocVersionService#isTrue}
     * 是同一件事在生产侧的写法（读侧要同时容忍两种回来形状）。
     */
    private static long num(Object value) {
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        return value == null ? 0 : ((Number) value).longValue();
    }

    /** 与 {@code KbWriteFlowTest#formWith} 同源的合法创建表单（编码用探针码）。 */
    private static Map<String, Object> formWith(String code) {
        Map<String, Object> form = new HashMap<>();
        form.put("name", "M06 归档探针库");
        form.put("code", code);
        form.put("deptId", 1);
        form.put("visibleRoles", List.of("SUPER_ADMIN", "USER"));
        form.put("secretLevel", "INTERNAL");
        form.put("businessDomain", "研发 / 平台");
        form.put("description", "");
        form.put("customTags", List.of());
        form.put("splitPolicy", Map.of("preset", "GENERAL", "chunkSize", 512, "overlap", 64,
                "recursiveSplit", true, "semanticSplit", false));
        form.put("tablePolicy", Map.of("atomicTable", true, "dualRepresentation", true,
                "repeatHeader", true, "mergeCrossPage", true, "ocrTable", true,
                "rowLimit", 30, "rowWindow", 5));
        form.put("retrievalConfig", Map.of("mode", "HYBRID", "recallThreshold", 0.15,
                "rerankTopK", 5, "rerankEnabled", true, "tableWeight", 1.2, "identifierProtect", true));
        return form;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
