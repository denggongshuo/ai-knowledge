package com.knowledge.ai.pipeline.retrieve;

import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.enums.DataScope;
import com.knowledge.ai.core.enums.SecretLevel;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.storage.MilvusPartitionManager;
import com.knowledge.ai.core.text.IdentifierTokenizer;
import com.knowledge.ai.pipeline.vectorize.EmbeddingClient;
import com.knowledge.ai.pipeline.vectorize.VectorChunk;
import com.knowledge.ai.pipeline.vectorize.VectorRecord;
import com.knowledge.ai.pipeline.vectorize.VectorWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * 检索链路流程用例的公共夹具（`S5-M07-A`）。
 *
 * <h3>为什么要有这个基类</h3>
 * <p>{@code HybridRetrievalFlowTest} / {@code RetrievalServiceTest} / {@code RetrievalScopeFlowTest} /
 * {@code IdentifierRetrievalFlowTest} 四个用例要的是同一套东西：一个探针知识库、
 * 几篇文档与分片、写进 Milvus 的向量、以及一个**可预测的嵌入替身**。
 * 每个类各抄一份的结果是四份会各自演化的夹具，而夹具的漂移比业务代码的漂移更隐蔽
 * （它让断言悄悄测错东西）。这与 {@code ApiTestSupport} 的存在理由是同一个。
 *
 * <h3>三件替身，以及它们各自替掉了什么</h3>
 * <ol>
 *   <li>{@link EmbeddingClient}（{@code @Primary}）—— 替掉**模型**那一层。
 *       其余全是真的：真的 Milvus、真的标量过滤、真的两路并行、真的 RRF。
 *       本仓 pom 没有 Mockito，而这个接口是函数式的，一个 lambda 就够；</li>
 *   <li>{@code retrievalExecutor}（独立 Bean，见 {@link RetrievalTestStubs}）—— 替成一个
 *       **记录区间**的池子。这是 `G5-1`「并行有证据」的落点：
 *       与其去比"两路耗时之和 > 墙钟耗时"（本地 Milvus 往返都在毫秒级，
 *       那个不等式的两边会**因为取整而相等**，用例会随机转红），
 *       不如直接记录两个任务**在什么时间段内跑**、再断言它们**重叠**。
 *       重叠与否与耗时无关，是确定性的；</li>
 *   <li>无 —— 不替 MySQL、不替 Milvus。`I-9` 的判据（软删库零召回、跨代返回新代）
 *       只有在真库真集合上才成立。</li>
 * </ol>
 *
 * <p>⚠️ 嵌入替身**用声明式向量**（token 哈希到维度 + L2 归一化），不是随机数：
 * 随机向量的检索结果没有语义，断言"召回了几条"就变成了断言"Milvus 的行为"，
 * 而不是断言本仓的链路。声明式向量下，"提问与某块共享几个 token"与
 * "它们的 L2 距离更近"是同一件事 —— 这让向量那一路的断言**可解释**。
 */
@SpringBootTest
// 重排端点给一组**不会被真正拨号**的占位值：目的是让 `ModelClientRegistry#rerankClient()`
// 走"有配置"那条分支（否则它直接返回 NoOp，"重排跑过"的分支永远进不去），
// 而真正的实现被 RetrievalTestStubs 的工厂替身接管
@org.springframework.test.context.TestPropertySource(properties = {
        "app.model.rerank.base-url=http://127.0.0.1:1/v1",
        "app.model.rerank.api-key=test-key-not-used",
        "app.model.rerank.model=test-reranker-not-used"})
// ⚠️ 必须是**显式** @Import：父类里的嵌套 @TestConfiguration **不会**被自动发现，
// 而失败是静默的（替身没注册 → 用例真去打模型网关，见 RetrievalTestStubs 的类注释）
@org.springframework.context.annotation.Import(RetrievalTestStubs.class)
public abstract class RetrievalFlowSupport {

    /** 一个任务在池子里的占用区间（纳秒）。用来证明两路**真的并行**。 */
    public record Interval(String thread, long startNanos, long endNanos) {

        /** 与另一个区间是否在时间上重叠。 */
        public boolean overlaps(Interval other) {
            return startNanos < other.endNanos && other.startNanos < endNanos;
        }
    }

    /** 本轮记录到的任务区间（每个用例前清空）。 */
    protected static final List<Interval> INTERVALS = Collections.synchronizedList(new ArrayList<>());

    /** 嵌入替身的**人为延迟**（毫秒）。默认 0；`G5-1` 的并行取证用到它。 */
    protected static volatile long embedDelayMillis = 0L;

    /** 嵌入替身被调用过几次（用来验"查询侧确实嵌入了"）。 */
    protected static final java.util.concurrent.atomic.AtomicInteger EMBED_CALLS =
            new java.util.concurrent.atomic.AtomicInteger();

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MilvusPartitionManager partitions;

    @Autowired
    protected VectorWriter vectorWriter;

    @Autowired
    protected RetrievalScope retrievalScope;

    @Autowired
    protected io.milvus.v2.client.MilvusClientV2 milvusClient;

    @Autowired
    protected com.knowledge.ai.core.storage.MilvusProperties milvusProperties;

    /** 注入到的是 {@code @Primary} 的替身（见 {@link RetrievalTestStubs}）。 */
    @Autowired
    protected EmbeddingClient embeddingClient;

    /**
     * 一个**显式装配**的检索内核：用它自己的记录型执行器。
     *
     * <p>为什么不直接注入容器里的 `HybridRetriever`：那样它拿到的是
     * {@code AsyncConfig#retrievalExecutor}，用例就观察不到两个任务的执行区间了。
     * 显式 new 让"我观察的是哪个池子"写在代码里，而不是靠容器里谁最后注册。
     */
    protected HybridRetriever retriever() {
        return new HybridRetriever(milvusClient, milvusProperties, embeddingClient, RetrievalTestStubs.newRecordingExecutor());
    }

    /** 本用例**主**知识库 id。 */
    protected long kbId;

    /**
     * 本用例建过的**全部**知识库（含测试中途额外建的）。
     *
     * <p>为什么不能只记 {@link #kbId}：用例经常要临时再建一两个库（密级探针、不可见库……）。
     * 只清主库的话，那一个会在库里躺着 —— 下一次运行的 {@code insertKb} 就撞
     * {@code uk_kb_code}，报的却是"主键冲突"这种与用例意图毫不相干的错。
     * **本类第一次跑正是这么红的**，所以登记改成"建一个记一个"，由收尾统一清。
     */
    protected final List<Long> createdKbIds = new ArrayList<>();

    protected final List<Long> docIds = new ArrayList<>();

    /** 建一个探针库：{@code INTERNAL} 密级 + 一条 USER 授权（可见性求交要靠它）。 */
    protected long insertKb(String code) {
        return insertKb(code, "INTERNAL");
    }

    protected long insertKb(String code, String secretLevel) {
        return insertKb(code, secretLevel, null);
    }

    /**
     * 建一个带**库级检索配置**的探针库。
     *
     * @param retrievalConfigJson {@code sys_kb.retrieval_config_json} 的原样 JSON；
     *                            {@code null} = 不写（走 {@code KbConfigDefaults} 的默认值）
     */
    protected long insertKb(String code, String secretLevel, String retrievalConfigJson) {
        jdbc.update("""
                        insert into sys_kb (code, name, dept_id, doc_count, chunk_count, status,
                                            secret_level, business_domain, reindex_required,
                                            index_generation, retrieval_config_json, created_at, updated_at)
                        values (?, ?, 1, 0, 0, 'READY', ?, '', 0, 1, ?, now(), now())
                        """, code, code, secretLevel, retrievalConfigJson);
        long id = jdbc.queryForObject("select id from sys_kb where code = ?", Long.class, code);
        createdKbIds.add(id);
        // `DataScopeService#kbVisibility` 认的是**授权行**，不认 superAdmin ——
        // 不挂这条，检索会因为"库不可见"返回空，而那不是用例要考的东西
        jdbc.update("""
                        insert into sys_kb_grant (kb_id, principal_type, principal_id, level, created_at, updated_at)
                        values (?, 'USER', 1, 'MANAGE', now(), now())
                        """, id);
        return id;
    }

    /**
     * 只建一篇**文档行**、不建分片。
     *
     * <p>给"分片要走真实写入路径"的用例用（如样板识别要经
     * {@code DocRecordWriter#markBoilerplate}）—— 那种用例必须让 writer 自己插分片，
     * 直接 JDBC 抄一份就绕过了被验的那段代码。
     */
    protected long insertEmptyDoc(String title) {
        String md5 = String.format("%032x", Math.abs(title.hashCode()));
        jdbc.update("""
                        insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5,
                                                  storage_path, status, chunk_count, table_total, table_parsed,
                                                  image_total, image_parsed, index_generation, created_at, updated_at)
                        values (?, 1, ?, ?, 'PDF', 1, ?, 'documents/probe', 'READY', 0, 0, 0, 0, 0, 1, now(), now())
                        """, kbId, title, title + ".pdf", md5);
        Long docId = jdbc.queryForObject(
                "select id from sys_document where kb_id = ? and title = ?", Long.class, kbId, title);
        docIds.add(docId);
        return docId;
    }

    /** 建一篇文档 + 给定文本的若干分片（每个分片一条 chunk）。 */
    protected long insertDocWithChunks(String title, String... chunkTexts) {
        String md5 = String.format("%032x", Math.abs(title.hashCode()));
        jdbc.update("""
                        insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5,
                                                  storage_path, status, chunk_count, table_total, table_parsed,
                                                  image_total, image_parsed, index_generation, created_at, updated_at)
                        values (?, 1, ?, ?, 'PDF', 1, ?, 'documents/probe', 'READY', ?, 0, 0, 0, 0, 1, now(), now())
                        """, kbId, title, title + ".pdf", md5, chunkTexts.length);
        Long docId = jdbc.queryForObject(
                "select id from sys_document where kb_id = ? and title = ?", Long.class, kbId, title);
        int order = 1;
        for (String text : chunkTexts) {
            jdbc.update("""
                            insert into sys_chunk (doc_id, kb_id, chunk_type, parent_id, title_path,
                                                   raw_content, content_sha256, order_no, page_no, created_at)
                            values (?, ?, 'PARAGRAPH', null, null, ?, ?, ?, 1, now())
                            """, docId, kbId, text,
                    com.knowledge.ai.core.text.ContentFingerprint.of(text), order++);
        }
        jdbc.update("update sys_kb set doc_count = doc_count + 1, chunk_count = chunk_count + ? where id = ?",
                chunkTexts.length, kbId);
        docIds.add(docId);
        return docId;
    }

    /**
     * 把某文档的全部分片按当前代写进 Milvus（模拟"它已被向量化"）。
     *
     * <p>直接走 {@link VectorWriter} 而不经 {@code ChunkVectorizer}：用例要控制的是
     * **检索侧**的输入，不是写入侧的链路（那条链路另有 {@code MilvusVectorWriteTest} 钉着）。
     */
    protected void seedVectors(long docId, int generation) {
        String partition = MilvusConstants.partitionName(kbId, generation);
        partitions.ensure(partition);
        List<Object[]> rows = jdbc.query(
                "select id, raw_content from sys_chunk where doc_id = ? order by order_no",
                (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2)}, docId);
        List<VectorRecord> records = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            long chunkId = (Long) row[0];
            String text = (String) row[1];
            records.add(new VectorRecord(
                    new VectorChunk(chunkId, "PARAGRAPH", text, null, "INTERNAL", null),
                    kbId, docId, hashedVector(text), "BAAI/bge-m3", "v1"));
        }
        vectorWriter.replaceDocument(partition, docId, records);
    }

    /**
     * 等刚写入的向量**变得可被检索看见**（`BOUNDED` 一致性下的有界窗口）。
     *
     * <h3>为什么必须有这一步 —— 本批实测到的一条真行为</h3>
     * <p>{@code HybridRetriever} 的检索用 {@code ConsistencyLevel.BOUNDED}（与建集合时一致）。
     * 实测（2026-09-17，本机 standalone，探针见 `plan/2026-09-17-S5-M07-A.md` §9）：
     * 写入 5 条向量后**立刻**检索只回 4 条，隔 1 秒再查回 5 条 —— 而**重复查会自己变全**，
     * 所以这不是 IVF 索引漏召回（那种漏不会随时间自愈），是**写可见性**的窗口。
     *
     * <p>产品侧这是可接受的（"刚解析完的文档立刻提问，可能差几条"，窗口是秒级）；
     * 但**用例不能去赌这个窗口** —— 赌的结果就是"单独跑绿、一起跑红"，
     * 而那种红会被当成实现有问题，浪费一整轮排查。
     *
     * <p>⚠️ 刻意用**真检索**来等，而不是读分区计数：分区计数走的是 `STRONG`，
     * 它会立刻返回全量，等不到"检索侧真的能看见"。等的东西必须与断言的东西同源。
     *
     * @param query        用于探测的提问（要与目标分片有 token 交集）
     * @param expectedHits 期望的向量命中条数
     */
    protected void awaitVectorsVisible(String query, int expectedHits) {
        awaitRecallVisible(query, "VECTOR", expectedHits);
    }

    /**
     * 同 {@link #awaitVectorsVisible}，但可以指定**哪一路**通道来等。
     *
     * <p>为什么需要这一维：向量与 BM25 是**同一批 Milvus 列**，但可见性不是一回事 ——
     * 实测（见 {@link #awaitVectorsVisible} 的类注释）里向量那一路的窗口是秒级，
     * 而"等到了向量"并不等于"等到了 BM25"（反之亦然）。用错了通道等，
     * 就会在**另一条通道**上翻车，而现象是"明明写进去了却搜不到"。
     *
     * @param mode         {@code "VECTOR"} 或 {@code "KEYWORD"}（本方法只用这两路来探测）
     * @param expectedHits 期望该路召回的条数
     */
    protected void awaitRecallVisible(String query, String mode, int expectedHits) {
        RetrievalScope.Scope scope = retrievalScope.resolve(reader(), List.of(kbId));
        RetrievalParams params = RetrievalParams.resolve(
                new com.knowledge.ai.dto.kb.KbDtos.RetrievalConfig(mode, 0.15, 50, false, 1.2, true),
                new com.knowledge.ai.core.setting.SystemSettingService.Snapshot(java.util.Map.of()));

        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        int last = -1;
        while (System.nanoTime() < deadline) {
            HybridRetriever.Recall recall = retriever().recall(query, scope, params);
            last = recall.vectorHits().size() + recall.bm25Hits().size();
            if (last >= expectedHits) {
                // 探测本身用掉了替身与执行器，清干净，免得污染用例自己的断言
                resetProbeState();
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        org.assertj.core.api.Assertions.fail(
                "等待写入的向量可检索超时（" + mode + "）：期望至少 " + expectedHits
                        + " 条，最后一次只看到 " + last + " 条");
    }

    /** 一个"看得见全部"的用户：超管 + ALL 范围 + 绝密上限。 */
    protected static LoginUser reader() {
        return LoginUser.builder()
                .userId(1L)
                .username("admin")
                .realName("探针")
                .deptId(1L)
                .superAdmin(true)
                .dataScope(DataScope.ALL)
                .secretLevelUpper(SecretLevel.SECRET)
                .build();
    }

    /** 一个密级上限只有 PUBLIC 的用户（用来验密级过滤）。 */
    protected static LoginUser publicOnlyReader() {
        return LoginUser.builder()
                .userId(1L)
                .username("admin")
                .deptId(1L)
                .superAdmin(false)
                .dataScope(DataScope.ALL)
                .secretLevelUpper(SecretLevel.PUBLIC)
                .build();
    }

    /**
     * 断言**替身确实生效了**。
     *
     * <p>这条断言防的是本批踩过的那类静默失败：替身没注册 → 容器里只剩真身 →
     * 用例**真的去打模型网关**，而它照样可能绿。见 {@link RetrievalTestStubs} 的类注释。
     * 只验向量那一路的用例调用它；`KEYWORD` 模式的用例本就不会碰嵌入，不该调。
     */
    protected static void assertEmbeddingStubInUse() {
        org.assertj.core.api.Assertions.assertThat(EMBED_CALLS.get())
                .as("嵌入替身没有被调用 —— 说明它没被注册，用例真去打模型网关（见 RetrievalTestStubs 类注释）")
                .isGreaterThan(0);
    }

    /** 清掉本轮记录的任务区间与嵌入计数。 */
    protected static void resetProbeState() {
        INTERVALS.clear();
        EMBED_CALLS.set(0);
        embedDelayMillis = 0L;
    }

    /**
     * 声明式嵌入：token 哈希到维度上累加，再 L2 归一化。
     *
     * <p>中文仍按 {@link IdentifierTokenizer} 的单字切（与写入侧同一个函数）——
     * 于是"提问 `A-100`"与"正文含 `A-100`"的向量在这个空间里**天然靠近**，
     * 而"`A-100` 与 `A-1000`"是两个不相干的 token。
     */
    protected static float[] hashedVector(String text) {
        float[] vector = new float[MilvusConstants.DIMENSION];
        for (String token : IdentifierTokenizer.tokenize(text)) {
            vector[Math.floorMod(token.hashCode(), MilvusConstants.DIMENSION)] += 1.0f;
        }
        double norm = 0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        if (norm == 0) {
            // 空文本：给一个固定方向，避免零向量（Milvus 对零向量做 L2 会得到 NaN 距离）
            vector[0] = 1.0f;
            return vector;
        }
        double sqrt = Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / sqrt);
        }
        return vector;
    }

    /** 收尾：先丢 Milvus 分区（指向它的是 sys_kb），再删库/文档/分片。 */
    protected void cleanProbeData() {
        for (Long id : createdKbIds) {
            // 分区先丢：它按 kbId 命名，库行没了就再也找不回这些分区
            for (int generation : partitions.generationsOf(id)) {
                partitions.dropQuietly(MilvusConstants.partitionName(id, generation));
            }
            jdbc.update("delete from sys_chunk where kb_id = ?", id);
            jdbc.update("delete from sys_document where kb_id = ?", id);
            jdbc.update("delete from sys_kb_grant where kb_id = ?", id);
            jdbc.update("delete from sys_kb where id = ?", id);
        }
        createdKbIds.clear();
        for (Long docId : docIds) {
            jdbc.update("delete from sys_chunk where doc_id = ?", docId);
            jdbc.update("delete from sys_document where id = ?", docId);
        }
        docIds.clear();
        resetProbeState();
    }
}
