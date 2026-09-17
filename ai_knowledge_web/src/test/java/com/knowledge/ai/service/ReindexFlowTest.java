package com.knowledge.ai.service;

import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.enums.DataScope;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.core.enums.PermissionCode;
import com.knowledge.ai.core.storage.MilvusPartitionManager;
import com.knowledge.ai.pipeline.vectorize.EmbeddingClient;
import com.knowledge.ai.pipeline.vectorize.VectorRecord;
import com.knowledge.ai.pipeline.vectorize.VectorChunk;
import com.knowledge.ai.pipeline.vectorize.VectorWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-406` 重建索引的取证（判据 1 / 2 / 4 / 7 / 8 / 9 / 10 · 含 S3 的 `G3-4` / `G3-5` 机制 / `G3-6`）。
 *
 * <h3>为什么不走上传→解析那条路造数据</h3>
 * <p>本类考的是**重建**，不是解析。直接把 KB / 文档 / 分片写成行，省掉一次真 PDF 解析
 * （十几秒 + 一次模型调用），换来的是"这个用例在考什么"一目了然。
 * 与 {@code IndexEnhanceFlowTest} 的探针做法一致。
 *
 * <h3>用 {@code @Primary} 换掉嵌入通道，而不是 mock</h3>
 * <p>本仓 pom **没有 Mockito**，也不需要为这一个替身引进来 —— {@link EmbeddingClient} 是函数式接口。
 * 它替掉的是**模型**那一层，重建的其余部分（挑块 → 投影 → 分批 → 写 Milvus → 切代）全是真的。
 * 那个替身还兼一个职责：**遇到带 {@link #POISON} 标记的文本就抛**，
 * 这样"中途失败"才是可复现的（真去 kill 进程没法在单测里做，见类末说明）。
 *
 * <h3>⚠️ `G3-5`「中途 kill 旧索引仍可用」在本类怎么验</h3>
 * <p>kill 进程与"某篇文档向量化抛异常"对**代**的影响是同一个：都不会走到切代那一步。
 * 所以本类断言的是那个**机制** —— 失败时 {@code sys_kb.index_generation} 不变、
 * 旧代分区条数一个不少。真正的进程级 kill 另有一条实测证据（见计划 §8），
 * 而检索侧能返回旧结果这件事需要 M07 的检索接口，**合并到 `S5` 的 `T-700` 验收**。
 */
@SpringBootTest
// 测试用的 application.properties 把向量化关了（`m05.vectorize.enabled=false`，与 enhance 同一条
// 理由：免得每次上传真打模型）。本类考的就是向量化那条路，必须打开 —— 关着的话
// ChunkVectorizer 会返回 DISABLED，于是每篇文档都"失败"，用例只会绿成一片假象。
@TestPropertySource(properties = "m05.vectorize.enabled=true")
class ReindexFlowTest {

    /** 毒标记：文本里带它的块会让替身嵌入抛异常，用来复现"中途失败"。 */
    private static final String POISON = "__POISON__";

    /** 记录替身收到过的全部文本（跨多次重建累计），用来验"续跑不重跑已完成文档"。 */
    private static final List<String> EMBEDDED = Collections.synchronizedList(new ArrayList<>());

    @TestConfiguration
    static class StubEmbedding {

        @Bean
        @Primary
        EmbeddingClient stubEmbeddingClient() {
            return texts -> {
                EMBEDDED.addAll(texts);
                for (String text : texts) {
                    if (text.contains(POISON)) {
                        throw new EmbeddingClient.EmbeddingFailedException("探针注入的嵌入失败：" + text);
                    }
                }
                return texts.stream().map(ReindexFlowTest::unitVector).toList();
            };
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReindexService reindexService;

    @Autowired
    private KbLifecycleService kbLifecycleService;

    @Autowired
    private MilvusPartitionManager partitions;

    @Autowired
    private VectorWriter vectorWriter;

    @Autowired
    private KbService kbService;

    /** 每次用例自建的知识库 id（收尾要连分区一起清）。 */
    private long kbId;

    private final List<Long> docIds = new ArrayList<>();

    @BeforeEach
    void createProbeKb() {
        EMBEDDED.clear();
        kbId = insertKb("reindex-probe");
    }

    @AfterEach
    void cleanProbeData() {
        // 顺序：先丢 Milvus 分区（指向它的是 sys_kb），再删库/文档/分片
        for (int generation : partitions.generationsOf(kbId)) {
            partitions.dropQuietly(MilvusConstants.partitionName(kbId, generation));
        }
        for (Long docId : docIds) {
            jdbc.update("delete from sys_chunk where doc_id = ?", docId);
            jdbc.update("delete from sys_document where id = ?", docId);
        }
        jdbc.update("delete from sys_kb_grant where kb_id = ?", kbId);
        jdbc.update("delete from sys_kb where id = ?", kbId);
        docIds.clear();
    }

    // ==================== 判据 8：切代是一次 UPDATE ====================

    @Test
    @DisplayName("判据 8：重建完成后代 +1、reindex_required 归 0，且新代分区里就是那些向量")
    void reindexSwitchesGenerationAtomically() {
        long docId = insertDoc("文档甲", 1, "正文甲");
        seedVectors(docId, 1, "正文甲");

        ReindexService.Result result = reindexService.reindex(kbId);

        assertThat(result.status()).isEqualTo(ReindexService.Status.DONE);
        assertThat(result.from()).isEqualTo(1);
        assertThat(result.to()).isEqualTo(2);
        assertThat(generation()).as("切代后的值").isEqualTo(2);
        assertThat(reindexRequired()).as("重建完成应清掉「待重建」标记").isZero();
        assertThat(partitions.countIn(MilvusConstants.partitionName(kbId, 2)))
                .as("新代分区里应有这篇文档的向量")
                .isEqualTo(1);
        assertThat(docGeneration(docId)).as("文档的断点也跟到新代").isEqualTo(2);
    }

    // ==================== 判据 7 / G3-5 机制：失败不切代 ====================

    @Test
    @DisplayName("判据 7 + G3-5 机制：有文档失败时**不切代**，旧代分区条数一个不少（kill 的等价情形）")
    void failureKeepsOldGenerationIntact() {
        long okDoc = insertDoc("好文档", 1, "正常正文");
        long badDoc = insertDoc("坏文档", 1, "正常正文" + POISON);
        seedVectors(okDoc, 1, "正常正文");
        seedVectors(badDoc, 1, "正常正文" + POISON);
        long oldCount = partitions.countIn(MilvusConstants.partitionName(kbId, 1));
        assertThat(oldCount).as("前提：旧代分区里确实有向量，否则本用例是空断言").isEqualTo(2);

        ReindexService.Result result = reindexService.reindex(kbId);

        assertThat(result.status()).isEqualTo(ReindexService.Status.BLOCKED);
        assertThat(result.failures()).hasSize(1);
        assertThat(generation())
                .as("有失败就不切代 —— 切了就是留一个**已知不完整**的新代，与「旧索引仍可用」相冲")
                .isEqualTo(1);
        assertThat(partitions.countIn(MilvusConstants.partitionName(kbId, 1)))
                .as("旧代分区必须一个点都不少（这正是 D-7 的「不得原地删旧索引」）")
                .isEqualTo(oldCount);
    }

    // ==================== 判据 4 / G3-6：可续跑 ====================

    @Test
    @DisplayName("判据 4 + G3-6：失败后修好再跑，**不重跑已完成文档**（断点靠 sys_document.index_generation）")
    void resumesFromBreakpointWithoutRedoingFinishedDocs() {
        long okDoc = insertDoc("先成功", 1, "第一段正文");
        long badDoc = insertDoc("后失败", 1, "第二段正文" + POISON);
        seedVectors(okDoc, 1, "第一段正文");
        seedVectors(badDoc, 1, "第二段正文" + POISON);

        reindexService.reindex(kbId);
        assertThat(docGeneration(okDoc)).as("第一轮：好文档已到新代").isEqualTo(2);
        assertThat(docGeneration(badDoc)).as("第一轮：坏文档停在原地").isEqualTo(1);

        // 修好：把毒标记去掉
        jdbc.update("update sys_chunk set raw_content = ? where doc_id = ?", "第二段正文", badDoc);
        EMBEDDED.clear();

        ReindexService.Result second = reindexService.reindex(kbId);

        assertThat(second.status()).isEqualTo(ReindexService.Status.DONE);
        assertThat(generation()).isEqualTo(2);
        assertThat(EMBEDDED)
                .as("第二轮不该再嵌入已完成文档的文本 —— 那是「重跑已完成文档」，正是 G3-6 要防的")
                .doesNotContain("第一段正文");
        assertThat(EMBEDDED)
                .as("第二轮该处理的正是那篇坏文档")
                .contains("第二段正文");
    }

    // ==================== 判据 9：滚动清理 ====================

    @Test
    @DisplayName("判据 9：连续重建多次后只保留最近 2 代，更老的代分区被 drop")
    void keepsOnlyRecentGenerations() {
        long docId = insertDoc("文档乙", 1, "正文乙");
        seedVectors(docId, 1, "正文乙");

        // 1 → 2 → 3 → 4
        for (int i = 0; i < 3; i++) {
            assertThat(reindexService.reindex(kbId).status()).isEqualTo(ReindexService.Status.DONE);
        }

        assertThat(generation()).isEqualTo(4);
        assertThat(partitionGenerations())
                .as("保留窗口 = 最近 2 代（含当前代）—— 留一代给「切完想回退」，不留更多是因为 "
                        + "release+drop 约 1.2s/分区（步 0 探针实测）")
                .containsExactly(3, 4);
    }

    // ==================== 判据 10：清空索引只清该库 ====================

    @Test
    @DisplayName("判据 10：清空索引只删该库的分区，别的库一条不动")
    void clearIndexOnlyTouchesThatKb() {
        long otherKb = insertKb("reindex-probe-other");
        try {
            long docA = insertDoc("本库文档", 1, "本库正文");
            long docB = insertDocIn(otherKb, "别库文档", 1, "别库正文");
            seedVectors(docA, 1, "本库正文");
            seedVectorsIn(otherKb, docB, 1, "别库正文");

            reindexService.clearIndex(kbId);

            assertThat(partitionGenerations()).as("本库的分区应被清掉").isEmpty();
            assertThat(docGeneration(docA)).as("本库文档的断点归 0（向量没了）").isZero();
            assertThat(partitions.countIn(MilvusConstants.partitionName(otherKb, 1)))
                    .as("**别的库一条都不能少** —— 这是清空索引最容易写错的地方")
                    .isEqualTo(1);
        } finally {
            for (int g : partitions.generationsOf(otherKb)) {
                partitions.dropQuietly(MilvusConstants.partitionName(otherKb, g));
            }
            jdbc.update("delete from sys_chunk where kb_id = ?", otherKb);
            jdbc.update("delete from sys_document where kb_id = ?", otherKb);
            jdbc.update("delete from sys_kb_grant where kb_id = ?", otherKb);
            jdbc.update("delete from sys_kb where id = ?", otherKb);
        }
    }

    // ==================== 判据 2 / G3-4：预估与实际同量级 ====================

    @Test
    @DisplayName("判据 2 + G3-4：重建后实际分片数与预估 chunkAfter 同量级（±10%）")
    void chunkAfterMatchesActualChunkCount() {
        long docId = insertDoc("文档丙", 1, "正文丙");
        seedVectors(docId, 1, "正文丙");

        // `impact` 是给"保存配置确认框"用的，内部要判可见性 —— 那是登录取的上下文，
        // 直调 Service 时得自己放一个（同 UserAdminFlowTest 的做法）
        LoginUserContext.set(LoginUser.builder()
                .userId(1L).username("admin").realName("admin").deptId(1L)
                .superAdmin(true).dataScope(DataScope.ALL)
                .permissions(java.util.Set.of(PermissionCode.WILDCARD))
                .build());

        // 预估走 T-404 的真实入口（`POST /kb/{id}/config/impact` 的 Service 层），
        // 传"当前配置" —— 于是 chunkAfter 应当接近现状。
        // ⚠️ 不能拿"库里的行数"当预估值：那是**实际值**，与实际值比等于自己跟自己比，
        // 判据会变成恒真的空断言（这正是 G3-4 要防的那种假绿）。
        long predicted = kbService.impact(kbId, KbService.defaultConfigForm()).chunkAfter();

        reindexService.reindex(kbId);

        long actual = jdbc.queryForObject(
                "select count(*) from sys_chunk where kb_id = ?", Long.class, kbId);
        double drift = predicted == 0 ? 0 : Math.abs(actual - predicted) * 100.0 / predicted;
        assertThat(predicted)
                .as("预估值本身为 0 会让偏差恒为 0 —— 那是空断言，先确认它算出了东西")
                .isPositive();
        assertThat(drift)
                .as("预估 %d 与实际 %d 偏差 %.1f%% —— G3-4 要求 ±10%% 以内", predicted, actual, drift)
                .isLessThanOrEqualTo(10.0);
    }

    // ==================== 数据构造 ====================

    private long insertKb(String code) {
        jdbc.update("""
                        insert into sys_kb (code, name, dept_id, doc_count, chunk_count, status,
                                            secret_level, business_domain, reindex_required,
                                            index_generation, created_at, updated_at)
                        values (?, ?, 1, 0, 0, 'READY', 'INTERNAL', '', 1, 1, now(), now())
                        """, code, code);
        long id = jdbc.queryForObject("select id from sys_kb where code = ?", Long.class, code);
        // 给探针库挂一条授权：`KbService.impact`（判据 2 的预估值来源）会走
        // `DataScopeService#kbVisibility` 的求交，而它认的是**授权行**，不认 superAdmin ——
        // 不挂的话会以"无该知识库的权限"失败，而那不是本用例要考的东西。
        jdbc.update("""
                        insert into sys_kb_grant (kb_id, principal_type, principal_id, level, created_at, updated_at)
                        values (?, 'USER', 1, 'MANAGE', now(), now())
                        """, id);
        return id;
    }


    private long insertDoc(String title, int generation, String chunkText) {
        return insertDocIn(kbId, title, generation, chunkText);
    }

    /** 建一篇文档 + 它的一个正文块（重建要的输入就这两样）。 */
    private long insertDocIn(long kb, String title, int generation, String chunkText) {
        String md5 = String.format("%032x", Math.abs(title.hashCode()));
        jdbc.update("""
                        insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5,
                                                  storage_path, status, chunk_count, table_total, table_parsed,
                                                  image_total, image_parsed, index_generation, created_at, updated_at)
                        values (?, 1, ?, ?, 'PDF', 1, ?, 'documents/probe', 'READY', 1, 0, 0, 0, 0, ?, now(), now())
                        """, kb, title, title + ".pdf", md5, generation);
        Long docId = jdbc.queryForObject(
                "select id from sys_document where kb_id = ? and title = ?", Long.class, kb, title);
        jdbc.update("""
                        insert into sys_chunk (doc_id, kb_id, chunk_type, parent_id, title_path,
                                               raw_content, order_no, page_no, created_at)
                        values (?, ?, 'PARAGRAPH', null, null, ?, 1, 1, now())
                        """, docId, kb, chunkText);
        // 维护库上的计数：`sys_kb.chunk_count` 是 `impact` 算 `chunkAfter` 的**基准**
        // （`chunkAfter = chunkBefore × stepOld / stepNew`），而 `chunkBefore = 0` 会走
        // "空库返 0" 那条分支 —— 判据 2 就变成空断言了。
        jdbc.update("update sys_kb set doc_count = doc_count + 1, chunk_count = chunk_count + 1 "
                + "where id = ?", kb);
        docIds.add(docId);
        return docId;
    }

    /** 把某文档的向量按"第 generation 代"直接写进对应分区（模拟它此前已被向量化）。 */
    private void seedVectors(long docId, int generation, String text) {
        seedVectorsIn(kbId, docId, generation, text);
    }

    private void seedVectorsIn(long kb, long docId, int generation, String text) {
        Long chunkId = jdbc.queryForObject(
                "select id from sys_chunk where doc_id = ? order by order_no limit 1", Long.class, docId);
        String partition = MilvusConstants.partitionName(kb, generation);
        partitions.ensure(partition);
        // 直接走 Milvus 写入（不经 ChunkVectorizer）—— 本类只关心"旧代里有没有东西"，
        // 那几个点的内容是什么无所谓
        vectorWriter.replaceDocument(partition, docId, List.of(new VectorRecord(
                new VectorChunk(chunkId, "PARAGRAPH", text, null, "INTERNAL", null),
                kb, docId, unitVector(text))));
    }

    // ==================== 读状态 ====================

    private int generation() {
        return jdbc.queryForObject("select index_generation from sys_kb where id = ?", Integer.class, kbId);
    }

    private int reindexRequired() {
        return jdbc.queryForObject("select reindex_required from sys_kb where id = ?", Integer.class, kbId);
    }

    private int docGeneration(long docId) {
        return jdbc.queryForObject("select index_generation from sys_document where id = ?",
                Integer.class, docId);
    }

    private List<Integer> partitionGenerations() {
        return partitions.generationsOf(kbId);
    }

    private static float[] unitVector(String seed) {
        float[] v = new float[MilvusConstants.DIMENSION];
        v[Math.abs(seed.hashCode()) % MilvusConstants.DIMENSION] = 1.0f;
        return v;
    }
}
