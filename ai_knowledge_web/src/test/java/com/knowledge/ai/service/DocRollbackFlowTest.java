package com.knowledge.ai.service;

import com.jayway.jsonpath.JsonPath;
import com.knowledge.ai.core.constant.MilvusConstants;
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
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **判据 5**（回滚后旧内容重新向量化）· **判据 6**（历史引用不变）· **判据 7**（版本行只增不减）·
 * **判据 10**（审计）· **判据 11**（拒绝非法回滚目标）。
 *
 * <h2>🔴 这个类**真的写 Milvus**、也**真的调嵌入**</h2>
 * <p>没有 spy、没有 stub。两条理由：
 * <ol>
 *   <li>`G7-6` 的原文是「回滚后旧内容**重新向量化**」—— 用一个恒返回 0 的
 *       {@code replaceDocument} 把它顶掉，"重新向量化"这句话就没被验过；
 *       而 {@code S7-M06-B} 那次教训（`DocReindexGuardFalsifyTest` 的类注释）恰好说明
 *       这种"接线看起来对"的判据有多容易写成空断言。</li>
 *   <li>本仓的默认测试套件**本来就要求基础设施在跑** —— `MilvusVectorWriteTest`
 *       （`@Tag("network")`）**不在** surefire 的排除名单里（`pom.xml` 只排了 `eval`）。
 *       所以"让本类也真写"没有增加任何前提，只是不再借 stub 回避它。</li>
 * </ol>
 * <p>因此本类**可以**声称"Milvus 里真有 v3 的向量、且 v2 的向量真被清了"，
 * 而且只对它自己那一个探针分区的读数负责（取证一律 `ConsistencyLevel.STRONG`，
 * 否则读到的是刚写完之前的旧值 —— 那种红看起来像实现有问题）。
 *
 * <h2>为什么夹具里 v1 有 3 个块、却只有 2 条向量</h2>
 * <p>表格**父块**不进检索池（{@code VectorChunk#isTableParent()} → {@code vectorText()}
 * 为 {@code null}），子块进。于是"块数 3 / 向量数 2"正好把
 * **"复制的是块、向量化的是投影"**这两件事分开钉住：
 * 若谁哪天把"复制块"写成了"复制向量"，或把父块也塞进池子，这个数字就会对不上。
 */
@DisplayName("判据 5/6/7/10/11 · 回滚：新增版本 + 重向量化 + 清旧向量 + 历史引用不变 + 审计 + 拒绝非法目标")
@TestPropertySource(properties = "m05.vectorize.enabled=true")
class DocRollbackFlowTest extends ApiTestSupport {

    private static final String KB_CODE = "probe-roll";

    /** 代固定为 1（{@code V39} 之前就有的口径：代从 1 起）。 */
    private static final int GENERATION = 1;

    /** 历史问答引用的 trace id（本类自己造一条，用来钉"引用不变"）。 */
    private static final String TRACE_ID = "probe-roll-trace";

    /** 读 Milvus 用的独立客户端（不在 Spring 上下文里，与 `MilvusVectorWriteTest` 同一条做法）。 */
    private static MilvusClientV2 milvus;

    @Autowired
    private MilvusPartitionManager partitions;

    @Autowired
    private VectorWriter vectorWriter;

    private long kbId;

    /** 版本组的标识 = 首版那一行的 id（`V39` 回填口径）。 */
    private long anchor;

    /** v1：被回滚到的目标版本。 */
    private long v1;

    /** v2：回滚发生时的当前版本。 */
    private long v2;

    /** v2 的正文分片 id —— 历史引用指向它。 */
    private long v2Chunk;

    /** Z 组：目标版本**没有分片**（`chunk_count = 0`）。`z1` 同时是这一组的组标识。 */
    private long z1;

    private long z2;

    /** Y 组：文档**正在索引**（状态不是可回滚的终态）。 */
    private long y2;

    /** 审计水位线 —— `sys_audit_log` 只增不删（《05》§4.8），按 id 隔离。 */
    private long auditFloor;

    private String token;

    private String partition;

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
    void rollbackSetUp() {
        token = loginAsAdmin().data("$.accessToken");
        auditFloor = maxAuditId();

        kbId = insertKb();
        partition = MilvusConstants.partitionName(kbId, GENERATION);
        partitions.ensure(partition);

        // ---- A 组：v1（历史）→ v2（当前）----
        v1 = insertVersion("手册.pdf", 1, false, 1000L, "READY");
        makeGroup(v1, v1);
        // ⚠️ 组标识单独记：`makeGroup` 后面还会被 Z/Y 两组调用，
        //    让它在内部写本字段的话，最后落地的是 **y1** —— 而 A 组的那些断言会静默查错组
        anchor = v1;
        insertChunk(v1, "PARAGRAPH", null, null, "型号 A-100 单价 1800 元", 1);
        long tableParent = insertChunk(v1, "TABLE", null, "1. 报价", "| 型号 | 单价 |", 2);
        insertChunk(v1, "TABLE", tableParent, "1. 报价", "型号 | 单价", 3);
        setChunkCount(v1, 3);

        v2 = insertVersion("手册.pdf", 2, true, 2000L, "READY");
        makeGroup(v1, v2);
        v2Chunk = insertChunk(v2, "PARAGRAPH", null, null, "第二版正文", 1);
        setChunkCount(v2, 1);
        // v2 是当前版本 ⇒ 它的向量就在库里（模拟"同名上传第二版之后"的状态）。
        // ⚠️ v1 作为历史版本**没有**向量 —— 与"同名上传时清掉旧版向量"（`G7-5` 的写侧）一致
        seedVector(v2, v2Chunk, "第二版正文");

        // ---- Z 组：目标版本 0 分片 ----
        z1 = insertVersion("方案.pdf", 1, false, 500L, "READY");
        makeGroup(z1, z1);
        setChunkCount(z1, 0);
        z2 = insertVersion("方案.pdf", 2, true, 600L, "READY");
        makeGroup(z1, z2);
        insertChunk(z2, "PARAGRAPH", null, null, "方案的当前版本", 1);
        setChunkCount(z2, 1);

        // ---- Y 组：文档正在索引 ----
        long y1 = insertVersion("报告.pdf", 1, false, 700L, "READY");
        makeGroup(y1, y1);
        insertChunk(y1, "PARAGRAPH", null, null, "第一版", 1);
        setChunkCount(y1, 1);
        y2 = insertVersion("报告.pdf", 2, true, 800L, "INDEXING");
        makeGroup(y1, y2);
        insertChunk(y2, "PARAGRAPH", null, null, "第二版（解析中）", 1);
        setChunkCount(y2, 1);

        // ---- 历史问答的引用：一条 trace + 一条指向 v2 分片的明细 ----
        insertHistoricalReference();
    }

    @AfterEach
    void rollbackTearDown() {
        // 顺序：先丢 Milvus 分区（指向它的只有本类的 kb 行），再删库族数据
        for (int generation : partitions.generationsOf(kbId)) {
            partitions.dropQuietly(MilvusConstants.partitionName(kbId, generation));
        }
        jdbc.update("delete from sys_retrieval_trace_chunk where trace_id = ?", TRACE_ID);
        jdbc.update("delete from sys_retrieval_trace where trace_id = ?", TRACE_ID);
        jdbc.update("delete from sys_chunk where kb_id = ?", kbId);
        jdbc.update("delete from sys_document where kb_id = ?", kbId);
        jdbc.update("delete from sys_kb_grant where kb_id = ?", kbId);
        jdbc.update("delete from sys_kb where id = ?", kbId);
        kbId = 0;
    }

    // ==================================================================
    // 判据 5 / 7 · 回滚 = 新增版本 + 重向量化 + 清旧向量
    // ==================================================================

    @Test
    @DisplayName("判据 5/7：回滚到 v1 ⇒ 新增 v3（内容 = v1）· is_current=1 · Milvus 真有 v3 向量 · "
            + "旧 v2 退出当前版本且向量被清 · 版本行一行不删")
    void rollbackCreatesNewVersionAndReVectorizes() {
        var res = post("/api/doc/" + v2 + "/rollback", Map.of("version", "v1"), token)
                .assertOk("回滚到 v1");

        long newDoc = dbLong("select id from sys_document where doc_group_id = ? and version_no = 3", anchor);

        assertThat(str(res, "$.version"))
                .as("🔴 回滚**不改写历史**：回到 v1 是新增一条版本，不是把 v1 翻成当前")
                .isEqualTo("v3");
        assertThat(number(res, "$.docId"))
                .as("响应里的 docId 是**新版本**的 id，不是被回滚到的那个旧 id —— "
                        + "前端要拿它去刷新页面，给错了会打开一个只读的历史版本")
                .isEqualTo(newDoc);
        assertThat(bool(res, "$.rolledBack")).isTrue();
        assertThat(str(res, "$.note")).contains("已回滚到 v1");

        // ---- 新行：内容抄自 v1、当前生效、INDEXING 已推进到 READY ----
        assertThat(dbLong("select version_no from sys_document where id = ?", newDoc)).isEqualTo(3L);
        assertThat(dbLong("select is_current from sys_document where id = ?", newDoc)).isEqualTo(1L);
        assertThat(dbStr("select status from sys_document where id = ?", newDoc))
                .as("向量写完之后才盖章 —— 盖成 READY 就代表它能被检索到")
                .isEqualTo("READY");
        assertThat(dbLong("select size_bytes from sys_document where id = ?", newDoc))
                .as("新版本的文件与 v1 是同一份（回滚不重解析、不重新上传）")
                .isEqualTo(1000L);
        assertThat(dbLong("select table_total from sys_document where id = ?", newDoc))
                .as("解析产出（表格数）也是抄来的 —— 回滚不重跑解析")
                .isEqualTo(1L);
        assertThat(dbStr("select remark from sys_document where id = ?", newDoc))
                .as("备注如实写「回滚到 v1」，版本列表的 note 那一列就是它")
                .isEqualTo("回滚到 v1");
        assertThat(dbLong("select chunk_count from sys_document where id = ?", newDoc))
                .as("分片数是**复制出来的真实条数**，不是从 v1 抄的字段")
                .isEqualTo(3L);

        // ---- 组内恰有一行当前版本，且是新行 ----
        assertThat(currentCount())
                .as("🔴 「组内恰有一行 is_current=1」是不变量：0 行 ⇒ 整篇文档从检索里消失；"
                        + "2 行 ⇒ 同一篇文档被召回两次")
                .isEqualTo(1L);
        assertThat(dbLong("select version_no from sys_document where doc_group_id = ? and is_current = 1", anchor))
                .isEqualTo(3L);
        assertThat(dbLong("select is_current from sys_document where id = ?", v2))
                .as("旧当前版本退出（但**行还在** —— 下一行钉这件事）")
                .isEqualTo(0L);
        assertThat(dbLong("select count(*) from sys_document where doc_group_id = ?", anchor))
                .as("🔴 判据 7：版本行**一行都不删**（需求 §6.4「保留一条新版本记录而不是改写历史」）。"
                        + "删掉 v2 就等于抹掉了「谁改过什么」")
                .isEqualTo(3L);

        // ---- 分片真的复制了，且父子关系重新映射到**新版本自己**的块 ----
        assertThat(dbLong("select count(*) from sys_chunk where doc_id = ?", newDoc)).isEqualTo(3L);
        long newChildParent = dbLong(
                "select parent_id from sys_chunk where doc_id = ? and order_no = 3", newDoc);
        assertThat(dbLong("select count(*) from sys_chunk where id = ? and doc_id = ?", newChildParent, newDoc))
                .as("🔴 表格子块必须指向**新版本自己**的父块。照抄旧 parent_id 的话，"
                        + "回表会拿到**历史版本**那张表 —— 页面上的表格内容与正文不是同一版")
                .isEqualTo(1L);

        // ---- 判据 5：向量真的重算了、旧的真的清了（读的是 Milvus 本身，不是日志） ----
        assertThat(vectorsOf(newDoc))
                .as("v1 的 3 个块里，正文 + 表格子块进池、表格父块不进 ⇒ 恰好 2 条向量。"
                        + "为 0 说明压根没重向量化（`G7-6` 没成立）；为 3 说明表格父块被塞进了池子")
                .isEqualTo(2L);
        assertThat(vectorsOf(v2))
                .as("🔴 旧当前版本的向量必须被清掉。不清就会留下「幽灵版本」——"
                        + "v2 已不是当前版本、`RetrievalScope` 也已经不放行它，"
                        + "但 Milvus 里那几条仍然占着召回名额")
                .isZero();
        assertThat(vectorsOf(v1))
                .as("历史版本本来就不该有向量（v1 是随 v2 上传被归档的那一版）")
                .isZero();
        assertThat(contentsOf(newDoc))
                .as("向量是**真嵌入**产出的：`content` 列不能是空串（空了就是「写了个壳」）")
                .isNotEmpty()
                .allMatch(c -> !c.isBlank());

        // ---- 读侧：版本列表立刻看得见 v3 ----
        assertThat(str(get("/api/doc/" + newDoc + "/versions", token).assertOk("版本列表"), "$.current"))
                .as("回滚之后，页面上那一栏必须已经显示 v3 是当前版本")
                .isEqualTo("v3");
    }

    // ==================================================================
    // 判据 6 · 历史问答的引用不变
    // ==================================================================

    /**
     * 🔴 这一条**靠"什么都不做"成立** —— {@code DocVersionService#rollback} 里
     * 没有一行代码在写 {@code sys_retrieval_trace_chunk}。
     *
     * <p>所以要钉的不是"它写对了"，而是**"没人顺手清它"**：
     * 回滚时"把旧版本的痕迹收拾干净"看起来非常像一个负责任的动作，
     * 而它会**静默改掉历史问答的引用** —— 用户看一周前那条回答，
     * 点开的正文变成了另一个版本的。那种缺陷没有任何报错，只能靠这条判据挡。
     */
    @Test
    @DisplayName("判据 6：回滚后历史问答的引用**逐字节不变**，被引用的分片仍指向 v2 的内容")
    void rollbackLeavesHistoricalReferencesUntouched() {
        String beforeRef = historicalReference();

        post("/api/doc/" + v2 + "/rollback", Map.of("version", "v1"), token).assertOk("回滚到 v1");

        assertThat(historicalReference())
                .as("🔴 引用的全部列（trace_id/rank_no/chunk_id/doc_id/source）必须一模一样。"
                        + "回滚只新增版本行 —— 顺手「清理旧版本痕迹」会改掉历史回答指着的内容")
                .isEqualTo(beforeRef);
        assertThat(dbLong("select count(*) from sys_retrieval_trace_chunk where trace_id = ?", TRACE_ID))
                .isEqualTo(1L);
        assertThat(dbStr("select raw_content from sys_chunk where id = ?", v2Chunk))
                .as("被引用的那条分片必须原文还在（父块/子块也没被重新映射过 —— 它属于 v2）")
                .isEqualTo("第二版正文");
        assertThat(dbLong("select doc_id from sys_chunk where id = ?", v2Chunk))
                .as("分片的归属还是 v2 —— 复制分片是**新增**，不是把旧的分片搬过去")
                .isEqualTo(v2);
    }

    // ==================================================================
    // 判据 10 · 审计
    // ==================================================================

    @Test
    @DisplayName("判据 10：回滚落一条 `module=文档 action=版本回滚 risk=NORMAL`，target 精确到文档")
    void rollbackIsAudited() {
        post("/api/doc/" + v2 + "/rollback", Map.of("version", "v1"), token).assertOk("回滚到 v1");

        Map<String, Object> row = jdbc.queryForMap(
                "select module, action, risk_level, target, operator, result, trace_id from sys_audit_log "
                        + "where id > ? and action = '版本回滚' order by id desc limit 1", auditFloor);
        assertThat(row.get("module")).isEqualTo("文档");
        assertThat(row.get("risk_level"))
                .as("`AuditRiskPolicy` 登记为 NORMAL（§4.3 四类高风险均不沾：回滚不删版本行、可逆）")
                .isEqualTo("NORMAL");
        assertThat(row.get("target"))
                .as("target 精确到**被回滚的那一篇**（`'文档#' + #docId`），不是笼统的 action 名")
                .isEqualTo("文档#" + v2);
        assertThat(row.get("operator")).isEqualTo(ADMIN);
        assertThat(row.get("result")).isEqualTo("SUCCESS");
        assertThat(row.get("trace_id")).isNotEqualTo("");
    }

    // ==================================================================
    // 判据 11 · 拒绝非法回滚目标
    // ==================================================================

    @Test
    @DisplayName("判据 11：目标就是当前版本 / 不在本组 / 不能识别 / 没传 ⇒ 10030，且**什么都没变**")
    void rollbackRejectsInvalidTargets() {
        assertRejected("v2", "已经是当前版本");
        assertRejected("v9", "不属于文档");
        assertRejected("abc", "无法识别");
        assertRejected("0", "无法识别");
        assertGroupUnchanged();
    }

    @Test
    @DisplayName("判据 11：没传版本号（空 body）⇒ 10030「请指定版本号」，不是 500")
    void rollbackRejectsMissingVersion() {
        var res = post("/api/doc/" + v2 + "/rollback", Map.of(), token);

        assertThat(res.code()).isEqualTo(10030);
        assertThat(res.message()).contains("请指定版本号");
        assertGroupUnchanged();
    }

    @Test
    @DisplayName("判据 11：目标版本**没有分片** ⇒ 拒（不静默产出一篇空索引的当前版本）")
    void rollbackRejectsVersionWithoutChunks() {
        var res = post("/api/doc/" + z2 + "/rollback", Map.of("version", "v1"), token);

        assertThat(res.code()).isEqualTo(10030);
        assertThat(res.message())
                .as("要说清「这一版没有可用分片」并给出下一步（重建索引），"
                        + "而不是笼统的参数错 —— 用户看不出该做什么")
                .contains("没有可用的分片");
        assertThat(dbLong("select is_current from sys_document where id = ?", z2))
                .as("被拒之后 Z 组的当前版本**原样不动**")
                .isEqualTo(1L);
        assertThat(dbLong("select count(*) from sys_document where doc_group_id = ?", z1))
                .as("🔴 拒绝发生在**建行之前**：一次被拒的回滚不该在版本列表里留下任何痕迹")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("判据 11：文档正在索引 ⇒ 拒（不与解析流水线抢「谁是当前版本」）")
    void rollbackRejectsDocInProgress() {
        var res = post("/api/doc/" + y2 + "/rollback", Map.of("version", "v1"), token);

        assertThat(res.code()).isEqualTo(10030);
        assertThat(res.message()).contains("正在索引");
        assertThat(dbLong("select count(*) from sys_document where doc_group_id = "
                + "(select doc_group_id from sys_document where id = ?)", y2))
                .as("拒绝时组内仍只有两版（没建新行）")
                .isEqualTo(2L);
        assertThat(dbLong("select is_current from sys_document where id = ?", y2)).isEqualTo(1L);
    }

    /**
     * 权限：{@code doc:update} 是回滚的准入码（计划 §3.4 假设 6）。
     *
     * <p>⚠️ 这个用户**能看见**这个库（下面给了授权行）—— 所以 403 只可能来自
     * "缺那个权限码"，而不是"库不可见"（那是 `10022`，另一个错号）。
     * 两条错分开报是本仓的硬口径（`DocAccessService` 类注释）。
     */
    @Test
    @DisplayName("判据 11：没有 `doc:update` 的用户 ⇒ 403/10020，且回滚没发生")
    void rollbackRequiresDocUpdatePermission() {
        TestUser outsider = createUser("rollback.perm");
        long userId = dbLong("select id from sys_user where username = ?", outsider.username());
        jdbc.update("""
                        insert into sys_kb_grant (kb_id, principal_type, principal_id, level, created_at, updated_at)
                        values (?, 'USER', ?, 'READ', now(), now())
                        """, kbId, userId);

        var res = post("/api/doc/" + v2 + "/rollback", Map.of("version", "v1"), outsider.accessToken());

        assertThat(res.status()).isEqualTo(403);
        assertThat(res.code()).isEqualTo(10020);
        assertThat(dbLong("select is_current from sys_document where id = ?", v2))
                .as("被权限拦下的请求**一行都没写**")
                .isEqualTo(1L);
        assertThat(dbLong("select count(*) from sys_document where doc_group_id = ?", anchor)).isEqualTo(2L);
        assertThat(vectorsOf(v2))
                .as("旧当前版本的向量也不该被动过 —— 权限检查必须发生在**任何**副作用之前")
                .isEqualTo(1L);
    }

    // ==================================================================
    // 断言助手
    // ==================================================================

    /** 回滚请求应被拒（`10030`），且消息里能看出原因。 */
    private void assertRejected(String version, String messageFragment) {
        var res = post("/api/doc/" + v2 + "/rollback", Map.of("version", version), token);

        assertThat(res.code())
                .as("回滚到「" + version + "」必须被拒，实际响应：" + res.body())
                .isEqualTo(10030);
        assertThat(res.message()).contains(messageFragment);
    }

    /** A 组在若干次被拒的回滚之后必须**完全没动**。 */
    private void assertGroupUnchanged() {
        assertThat(dbLong("select count(*) from sys_document where doc_group_id = ?", anchor))
                .as("被拒的回滚不许留下任何版本行（拒绝发生在建行之前）")
                .isEqualTo(2L);
        assertThat(currentCount()).isEqualTo(1L);
        assertThat(dbLong("select is_current from sys_document where id = ?", v2)).isEqualTo(1L);
        assertThat(vectorsOf(v2))
                .as("向量也没被清 —— 「清旧向量」只可能发生在**新版本写成功之后**")
                .isEqualTo(1L);
    }

    private long currentCount() {
        return dbLong("select count(*) from sys_document where doc_group_id = ? and is_current = 1", anchor);
    }

    // ==================================================================
    // Milvus 读数（STRONG —— 取证不接受"可能读到旧值"）
    // ==================================================================

    private long vectorsOf(long docId) {
        return queryDoc(docId, List.of(MilvusConstants.FIELD_ID)).size();
    }

    private List<String> contentsOf(long docId) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : queryDoc(docId, List.of(MilvusConstants.FIELD_CONTENT))) {
            out.add(String.valueOf(row.get(MilvusConstants.FIELD_CONTENT)));
        }
        return out;
    }

    private List<Map<String, Object>> queryDoc(long docId, List<String> outFields) {
        QueryResp resp = milvus.query(QueryReq.builder()
                .collectionName(new MilvusProperties().getCollection())
                .partitionNames(List.of(partition))
                .filter(MilvusConstants.FIELD_DOC_ID + " in [" + docId + "]")
                .outputFields(outFields)
                .consistencyLevel(ConsistencyLevel.STRONG)
                .build());
        return resp.getQueryResults() == null
                ? List.of()
                : resp.getQueryResults().stream().map(QueryResp.QueryResult::getEntity).toList();
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private long insertKb() {
        jdbc.update("""
                        insert into sys_kb (code, name, dept_id, doc_count, chunk_count, status,
                                            secret_level, business_domain, reindex_required,
                                            index_generation, created_at, updated_at)
                        values (?, ?, 1, 0, 0, 'READY', 'INTERNAL', '', 0, ?, now(), now())
                        """, KB_CODE, KB_CODE, GENERATION);
        long id = dbLong("select id from sys_kb where code = ?", KB_CODE);
        jdbc.update("""
                        insert into sys_kb_grant (kb_id, principal_type, principal_id, level, created_at, updated_at)
                        values (?, 'USER', 1, 'MANAGE', now(), now())
                        """, id);
        return id;
    }

    private long insertVersion(String fileName, int versionNo, boolean current, long size, String status) {
        String title = fileName + " v" + versionNo;
        String md5 = String.format("%032x", Math.abs((fileName + versionNo).hashCode()));
        jdbc.update("""
                        insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5,
                                                  storage_path, status, chunk_count, table_total, table_parsed,
                                                  image_total, image_parsed, pages_total, pages_with_text,
                                                  index_generation, version_no, is_current, created_at, updated_at)
                        values (?, 1, ?, ?, 'PDF', ?, ?, 'documents/probe', ?, 0, 1, 1,
                                0, 0, 1, 1, ?, ?, ?, now(), now())
                        """, kbId, title, fileName, size, md5, status, GENERATION, versionNo, current ? 1 : 0);
        return dbLong("select id from sys_document where kb_id = ? and title = ?", kbId, title);
    }

    private long insertChunk(long docId, String type, Long parentId, String titlePath,
                             String content, int orderNo) {
        jdbc.update("""
                        insert into sys_chunk (doc_id, kb_id, chunk_type, parent_id, title_path,
                                               raw_content, order_no, page_no, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, 1, now())
                        """, docId, kbId, type, parentId, titlePath, content, orderNo);
        return dbLong("select id from sys_chunk where doc_id = ? and order_no = ?", docId, orderNo);
    }

    private void setChunkCount(long docId, int count) {
        jdbc.update("update sys_document set chunk_count = ? where id = ?", count, docId);
    }

    private void makeGroup(long anchorId, long member) {
        jdbc.update("update sys_document set doc_group_id = ? where id = ?", anchorId, member);
    }

    /** 直接把向量写进分区（模拟"这一版此前已被向量化"）。 */
    private void seedVector(long docId, long chunkId, String text) {
        vectorWriter.replaceDocument(partition, docId, List.of(new VectorRecord(
                new VectorChunk(chunkId, "PARAGRAPH", text, null, "INTERNAL", null),
                kbId, docId, unitVector(text))));
    }

    private static float[] unitVector(String seed) {
        float[] v = new float[MilvusConstants.DIMENSION];
        v[Math.abs(seed.hashCode()) % MilvusConstants.DIMENSION] = 1.0f;
        return v;
    }

    /** 一条历史问答引用：trace 主行 + 一条指向 v2 分片的明细。 */
    private void insertHistoricalReference() {
        jdbc.update("""
                        insert into sys_retrieval_trace (trace_id, user_id, query_text, kb_ids, kb_count)
                        values (?, 1, '型号 A-100 多少钱？', ?, 1)
                        """, TRACE_ID, String.valueOf(kbId));
        jdbc.update("""
                        insert into sys_retrieval_trace_chunk (trace_id, rank_no, chunk_id, doc_id, source)
                        values (?, 1, ?, ?, 'VECTOR')
                        """, TRACE_ID, v2Chunk, v2);
    }

    /** 那条引用的可比较快照（**全部列**，只比它自己那一行）。 */
    private String historicalReference() {
        return jdbc.queryForMap(
                "select trace_id, rank_no, chunk_id, doc_id, source from sys_retrieval_trace_chunk "
                        + "where trace_id = ?", TRACE_ID).toString();
    }

    private long maxAuditId() {
        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from sys_audit_log", Long.class);
        return max == null ? 0L : max;
    }

    private long dbLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        assertThat(value).as("夹具/断言查询应有结果：" + sql).isNotNull();
        return value;
    }

    private String dbStr(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    // ==================================================================
    // 读响应的便捷封装
    // ==================================================================

    /*
     * ⚠️ 两条坑（与 `DocVersionFlowTest` 同源，写在这里免得下一个人重踩）：
     * ① `RawResponse#data` 是 `<T> T data(String)`，直接喂给 AssertJ 的 `assertThat`
     *    （它有一堆泛型重载）会让 javac 报"引用不明确"；先落到显式类型的局部。
     * ② 路径**相对 `$.data`**（`data()` 内部拼 `"$.data" + ...`），前缀由本方法统一补。
     */

    private static Object at(RawResponse res, String path) {
        return JsonPath.read(res.body(), "$.data" + path.substring(1));
    }

    private static String str(RawResponse res, String path) {
        return String.valueOf(at(res, path));
    }

    private static boolean bool(RawResponse res, String path) {
        return Boolean.TRUE.equals(at(res, path));
    }

    private static long number(RawResponse res, String path) {
        Object raw = at(res, path);
        return raw == null ? -1L : ((Number) raw).longValue();
    }
}
