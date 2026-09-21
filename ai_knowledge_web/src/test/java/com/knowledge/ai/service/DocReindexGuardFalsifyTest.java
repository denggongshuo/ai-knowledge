package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.DataScope;
import com.knowledge.ai.core.enums.PermissionCode;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.security.LoginUserContext;
import com.knowledge.ai.pipeline.vectorize.ChunkVectorizer;
import com.knowledge.ai.pipeline.vectorize.VectorWriter;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `G7-15` —— **「移动知识库」的代复核必须真的接线**（销 `plan/汇总.md` §3 的 `#211`）。
 *
 * <h2>为什么这条判据此前不存在，以及它为什么值钱</h2>
 * <p>`S7-M06-B` 的 {@code DocReindexService#moveToKb} **确实**传了
 * {@link ChunkVectorizer.GenerationGuard}（照 {@code DocParseService} 的写法：
 * 嵌入之后、写 Milvus 之前）。但当时**没有任何判据**证明那段接线是活的 ——
 * 而"传了一个回调"与"那个回调真的会在该调的时候被调用"是两件事：
 * <ul>
 *   <li>回调可以被传进去却**从不被调用**（某个分支漏了 {@code verify()}）；</li>
 *   <li>它可以被调用但**判据写反了**（代相等时抛、不等时放行）—— 那样"清空索引"
 *       会被撤销得**更彻底**，而页面上一切正常。</li>
 * </ul>
 * <p>两种错法的共同点：**没有任何现象**。这正是本仓反复记下的那个形状
 * （「闸说干净 ≠ 真的干净」「Success 不等于执行了」）。
 *
 * <h2>🔴 它怎么做到"拔掉即转红"</h2>
 * <p>把 {@code DocReindexService} 里那个 guard 换成**空实现**（{@code () -> {}}）——
 * 也就是 {@code ReindexService} 传的那种写法（那里**是有意的**：重建本来就写
 * "当前代 + 1"，那一刻它还不是当前代，用同一条判据会把它自己拦死）。
 * 空实现下复核永不抛 ⇒ {@link #guardBlocksWriteWhenGenerationChanged()} 当场转红。
 * **本批施工时实测过这一次**（见计划 §8 的证伪记录）。
 *
 * <h2>怎么造出"复核不通过"（而不是自己抛一个异常）</h2>
 * <p>用 spy 包住 {@code ChunkVectorizer}：在它**真正执行之前**把
 * 目标库的 {@code index_generation} 加一。于是 {@code moveToKb} 手里那个
 * {@code targetGeneration} 与复核回调里读到的**必然不等** ——
 * 复核是**真的走了一遍它自己的判断逻辑**才失败的。
 *
 * <h2>⚠️ 两条前提，不写下来会被下一次改动无声推翻</h2>
 * <ol>
 *   <li>本类把 {@code m05.vectorize.enabled} **打开**（测试全局默认是关的）。
 *       关着的时候 {@code ChunkVectorizer#vectorize} 在**复核之前**就
 *       {@code return DISABLED} —— 那条路上复核根本不跑，判据会**静默失效**
 *       （第一版正是这么红的）；</li>
 *   <li>真 Milvus 写入被 spy 掉（{@code replaceDocument} 恒返回 0）——
 *       本类验的是**复核**，不是向量落库（那属 {@code MilvusVectorWriteTest}，
 *       `@Tag("network")`）。⚠️ **正因如此**，本类**不能**声称"向量真的写进去了"。</li>
 * </ol>
 *
 * <h2>⚠️ 它不声称"修好了竞态"</h2>
 * <p>真正的竞态（"复核通过之后、写入之前"那一段窗口）在单线程用例里造不出来，
 * 与 `#210` 那族同一条限制。如实写在这里，免得下一个人以为这条判据覆盖了并发。
 */
@DisplayName("G7-15 · 移动知识库的代复核接线（销 #211）")
@TestPropertySource(properties = "m05.vectorize.enabled=true")
class DocReindexGuardFalsifyTest extends ApiTestSupport {

    private static final String SRC_CODE = "probe-guard-src";
    private static final String TGT_CODE = "probe-guard-tgt";
    private static final String DOC_TITLE = "探针文档-代复核";

    @Autowired
    private DocReindexService docReindexService;

    /** spy 向量化器：用来“在真正执行之前把代改掉”。 */
    @MockitoSpyBean
    private ChunkVectorizer chunkVectorizer;

    /** spy 向量落库通道：本类不碰真 Milvus（见类注释第 2 条）。 */
    @MockitoSpyBean
    private VectorWriter vectorWriter;

    /** 代被改过几次（每个用例最多一次 —— 改两次会把两边又凑成相等）。 */
    private static final AtomicInteger BUMPED = new AtomicInteger(0);

    private long srcKbId;
    private long tgtKbId;
    private long docId;

    @BeforeEach
    void guardSetUp() {
        BUMPED.set(0);
        Mockito.reset(chunkVectorizer, vectorWriter);

        // `moveToKb` 的可见性 / 授权判定读的是**登录取的上下文**（直调 Service 时要自己放一个）。
        // 与 `ReindexFlowTest` 同一条做法：放种子超管
        LoginUserContext.set(LoginUser.builder()
                .userId(1L).username("admin").realName("admin").deptId(1L)
                .superAdmin(true).dataScope(DataScope.ALL)
                .permissions(java.util.Set.of(PermissionCode.WILDCARD))
                .build());

        srcKbId = insertKb(SRC_CODE);
        tgtKbId = insertKb(TGT_CODE);
        docId = insertDoc(srcKbId);
        insertChunk(docId, srcKbId);

        // 🔴 就在**真正执行之前**把目标库的代加一：`moveToKb` 手里的 `targetGeneration`
        //    是它**动手之前**读的，而复核回调在写入之前读到的是 +1 ⇒ **必然不等** ⇒ 复核失败。
        //    ⚠️ 挂点很重要：第一版挂在 `currentKbGeneration` 上，而那次读在**只读事务**里
        //    ⇒ 当场报 `Connection is read-only`。`vectorize` 跑在事务外，可以写。
        Mockito.doAnswer(inv -> {
            if (BUMPED.getAndIncrement() == 0) {
                jdbc.update("update sys_kb set index_generation = index_generation + 1 where id = ?", tgtKbId);
            }
            return inv.callRealMethod();
        }).when(chunkVectorizer).vectorize(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyInt(),
                Mockito.anyList(), Mockito.any());

        // 不碰真 Milvus：无论复核过没过，`replaceDocument` 都只被"调用到"而已
        Mockito.doReturn(0).when(vectorWriter)
                .replaceDocument(Mockito.anyString(), Mockito.anyLong(), Mockito.anyList());
    }

    @AfterEach
    void guardTearDown() {
        LoginUserContext.clear();
        Mockito.reset(chunkVectorizer, vectorWriter);
        jdbc.update("delete from sys_chunk where doc_id = ?", docId);
        jdbc.update("delete from sys_document where id = ?", docId);
        jdbc.update("delete from sys_kb_grant where kb_id in (?, ?)", srcKbId, tgtKbId);
        jdbc.update("delete from sys_kb where id in (?, ?)", srcKbId, tgtKbId);
    }

    @Test
    @DisplayName("G7-15：代已变 ⇒ 复核拦下写入，且文档**留在 INDEXING**（不是 FAILED）")
    void guardBlocksWriteWhenGenerationChanged() {
        boolean blocked = moveAndSeeIfBlocked();

        assertThat(blocked)
                .as("🔴 复核必须拦下这次写入。把 `DocReindexService` 里那个 guard 换成"
                        + "`() -> {}`（空实现）⇒ 复核永不抛 ⇒ 本断言当场转红 ⇒ **接线是活的**。"
                        + "⚠️ 这条断言此前不存在（`S7-M06-B` §9-① / `汇总.md` §3-#211）")
                .isTrue();
        assertThat(jdbc.queryForObject(
                "select status from sys_document where id = ?", String.class, docId))
                .as("复核失败 ⇒ 留在 INDEXING（`GenerationChangedException` 的 javadoc 逐字写着："
                        + "标 FAILED 会让重建索引的筛选 `status in ('READY','INDEXING')` "
                        + "**再也捞不到它**，于是它永远修不好）")
                .isEqualTo("INDEXING");
        // ⚠️ **不能断言 `never()`**：`moveToKb` 在**复核之前**还有一次
        // `replaceDocument(oldPartition, docId, List.of())` —— 那是"删旧库分区里的向量"，
        // 与复核无关，它**本来就该发生**（第一版断言 `never()` 就是在这里红的）。
        // 要断言的是：**写入目标库的那一次没发生**。
        var partitions = org.mockito.ArgumentCaptor.forClass(String.class);
        Mockito.verify(vectorWriter, Mockito.atMostOnce())
                .replaceDocument(partitions.capture(), Mockito.anyLong(), Mockito.anyList());
        assertThat(partitions.getAllValues())
                .as("🔴 被碰过的分区只能是**源库**那一个。"
                        + "目标库的分区一旦出现，就说明复核没拦住写入 —— "
                        + "而 `replaceDocument` 会 ensure 分区，也就是把已经被 drop 的分区重建出来，"
                        + "正是「清空索引被静默撤销」那个实测过的缺陷")
                .allMatch(p -> p.startsWith("kb" + srcKbId + "_"), "源库分区")
                .doesNotContain("kb" + tgtKbId + "_1");
    }

    @Test
    @DisplayName("G7-15 的正向对照：代**没变**时移动正常走完（否则上面那条可能是「永远失败」）")
    void withoutGenerationChangeTheMoveSucceeds() {
        BUMPED.set(1); // 让 spy 不再改代

        boolean blocked = moveAndSeeIfBlocked();

        assertThat(blocked)
                .as("代没变 ⇒ 复核放行。**这条是上一条的对照**：没有它，"
                        + "一个「永远抛」的实现也能让上一条绿")
                .isFalse();
        assertThat(jdbc.queryForObject(
                "select kb_id from sys_document where id = ?", Long.class, docId))
                .as("归属真的换了")
                .isEqualTo(tgtKbId);
        assertThat(jdbc.queryForObject(
                "select kb_id from sys_chunk where doc_id = ? limit 1", Long.class, docId))
                .as("⚠️ **分片那一半不能漏**：只改文档列会让分片继续按旧库的密级参与过滤"
                        + "（一个非常安静的越权口子）")
                .isEqualTo(tgtKbId);
    }

    /** @return {@code true} = 复核拦下了写入 */
    private boolean moveAndSeeIfBlocked() {
        try {
            docReindexService.moveToKb(docId, tgtKbId);
            return false;
        } catch (ChunkVectorizer.GenerationChangedException ex) {
            return true;
        }
    }

    // ==================== 夹具 ====================

    private long insertKb(String code) {
        jdbc.update("""
                        insert into sys_kb (code, name, dept_id, doc_count, chunk_count, status, secret_level,
                                            business_domain, reindex_required, index_generation, created_at, updated_at)
                        values (?, ?, 1, 0, 0, 'READY', 'INTERNAL', '', 0, 1, now(), now())
                        """, code, code);
        long id = jdbc.queryForObject("select id from sys_kb where code = ?", Long.class, code);
        jdbc.update("""
                        insert into sys_kb_grant (kb_id, principal_type, principal_id, level, created_at, updated_at)
                        values (?, 'USER', 1, 'MANAGE', now(), now())
                        """, id);
        return id;
    }

    private long insertDoc(long kbId) {
        String md5 = String.format("%032x", Math.abs(DOC_TITLE.hashCode()));
        jdbc.update("""
                        insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5,
                                                  storage_path, status, chunk_count, table_total, table_parsed,
                                                  image_total, image_parsed, pages_total, pages_with_text,
                                                  index_generation, created_at, updated_at)
                        values (?, 1, ?, ?, 'PDF', 1024, ?, 'documents/probe',
                                'READY', 1, 0, 0, 0, 0, 1, 1, 1, now(), now())
                        """, kbId, DOC_TITLE, DOC_TITLE + ".pdf", md5);
        return jdbc.queryForObject("select id from sys_document where kb_id = ? and title = ?",
                Long.class, kbId, DOC_TITLE);
    }

    /**
     * 插一条分片。
     *
     * <p>⚠️ 列名照 `V14` 的定义（{@code chunk_type} / {@code raw_content} / {@code order_no}）——
     * 别按实体字段名想当然：库里的列名与 DTO 的 key 是**两个口径**（`D-5` §1.3）。
     */
    private void insertChunk(long docId, long kbId) {
        jdbc.update("""
                        insert into sys_chunk (doc_id, kb_id, chunk_type, raw_content, order_no, created_at)
                        values (?, ?, 'PARAGRAPH', '型号 A-100 单价 1800 元', 1, now())
                        """, docId, kbId);
    }
}
