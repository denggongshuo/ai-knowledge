package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.DataScope;
import com.knowledge.ai.core.enums.SecretLevel;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.storage.DocStorage;
import com.knowledge.ai.pipeline.retrieve.RetrievalScope;
import com.knowledge.ai.support.RecycleTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-611` 删除文档（软删进回收站）· `T-612` 跨库文档列表。
 *
 * <h3>本类钉住的核心是 `G7-14` 的**两个半边**</h3>
 * <ol>
 *   <li><b>软删后对象与向量都还在</b>（可恢复的前提）—— 独立观测点：直查 MinIO 对象大小
 *       与 Milvus 分区条数，**不信接口返回**；</li>
 *   <li><b>软删后不可达、不可召回</b> —— 前者走列表/详情接口，后者走
 *       {@code RetrievalScope.resolve}（那正是"能不能被搜出来"的唯一落点）。</li>
 * </ol>
 * <p>⚠️ 少了第②半，"软删后向量仍在"就变成了一条**危险的要求**（向量还在、又搜得出来
 * ⇒ 删了等于没删）。两半必须成对。
 */
class DocDeleteFlowTest extends RecycleTestSupport {

    @Autowired
    private RetrievalScope retrievalScope;

    @Test
    @DisplayName("G7-14：软删只写两个列 —— 对象与向量**一个都不动**")
    void softDeleteTouchesNothingButTwoColumns() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());
        long kbId = createProbeKb(token);
        long docId = ((Number) uploadReady(kbId, "软删探针.md", token).data("$.docId")).longValue();

        String storagePath = storagePathOf(docId);
        long sizeBefore = docStorage.sizeOf(storagePath);
        // ⚠️ 测试期 `m05.vectorize.enabled=false` ⇒ 解析不会产向量。这里**自己造**出
        //    "已被向量化过"的前置态（见 `RecycleTestSupport#seedVectors`），
        //    否则下面那条「向量仍在」就是一句 `0 == 0` 的空话。
        int generation = 1;
        assertThat(seedVectors(kbId, docId, generation, 3)).as("前置态：真的写了 3 条向量")
                .isEqualTo(3);
        long vectorsBefore = vectorCountOf(kbId, generation);
        assertThat(sizeBefore).as("先把前置态证明清楚：对象确实在").isPositive();
        assertThat(vectorsBefore).as("且确实被向量化过（否则本条判据是空断言）")
                .isEqualTo(3);

        RawResponse res = delete("/api/doc/" + docId, token).assertOk("删除文档");
        assertThat((Boolean) res.data("$.recycled")).as("它是「移入回收站」，不是真删").isTrue();
        assertThat(((Number) res.data("$.docId")).longValue()).isEqualTo(docId);

        assertThat(docSoftDeleted(docId)).as("deleted_at 已写").isTrue();
        assertThat(jdbc.queryForObject("select deleted_by from sys_document where id = ?",
                Long.class, docId)).as("deleted_by 也写了（V41 新列）").isNotNull();

        // 🔴 两半的正面证据：**一个字节、一个点都没动**
        assertThat(docStorage.sizeOf(storagePath)).as("MinIO 对象仍在（可恢复的前提）")
                .isEqualTo(sizeBefore);
        assertThat(vectorCountOf(kbId, generation)).as("Milvus 向量仍在（G7-14 的原文）")
                .isEqualTo(vectorsBefore);
        assertThat(generationOf(docId)).as("连「写齐了哪一代」的断点都没被动过 —— "
                        + "彻底删除正是靠它定位该去清哪个分区（清了这列等于让向量永远无主）")
                .isEqualTo(generation);

        // ---- 重复删除：记录在案的取舍（`plan/2026-09-22-S7-M06-F.md` §3.4 假设 5）----
        // 🔴 这条判据保护的是**保留期时钟**：回收站的剩余天数是从 `deleted_at` 算的
        //    ⇒ 只要"再点一次删除"能把它刷成新时刻，快过期的文档就能靠反复点击**无限续期**
        //    （一条真实存在的绕过保留期的方式）。
        //    今天的实现用两道叠加保证它：`requireVisibleDoc` 看不见软删的行 ⇒ 报 10044；
        //    `markDeleted` 的 `and deleted_at is null` 是并发下的第二道。
        //    ⚠️ 本用例钉的是**可观测的那一条**（deleted_at 不变）—— 谁将来把"重复删除"
        //       改成幂等成功，只要顺手让 markDeleted 无条件刷新，这条就会红。
        LocalDateTime firstDeletedAt = jdbc.queryForObject(
                "select deleted_at from sys_document where id = ?", LocalDateTime.class, docId);
        assertThat(delete("/api/doc/" + docId, token).code())
                .as("重复删除报 10044（不是幂等成功 —— 见 DocLifecycleService#batchSoftDelete 的 javadoc）")
                .isEqualTo(10044);
        assertThat(jdbc.queryForObject("select deleted_at from sys_document where id = ?",
                LocalDateTime.class, docId))
                .as("🔴 `deleted_at` **没有被刷新** —— 保留期时钟不会因为重复点击而重置")
                .isEqualTo(firstDeletedAt);
    }

    @Test
    @DisplayName("G7-14 的另一半：软删之后**不可达、不可召回**")
    void softDeletedDocIsNeitherReachableNorRecallable() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());
        long kbId = createProbeKb(token);
        long docId = ((Number) uploadReady(kbId, "不可达探针.md", token).data("$.docId")).longValue();

        // 前置：删之前它是可达的，且**在下发给 Milvus 的过滤表达式里**
        assertThat(get("/api/doc/" + docId, token).code()).isZero();
        assertThat(scopeOf(kbId).milvusFilter()).as("删之前它在 Milvus 过滤表达式里")
                .contains("doc_id in [" + docId + "]");
        int countBefore = scopeOf(kbId).docScopeCount();

        delete("/api/doc/" + docId, token).assertOk("删除文档");

        // ① 不可达：详情 / 按库列表 / 跨库列表，三处都查不到
        assertThat(get("/api/doc/" + docId, token).code()).as("详情 ⇒ 10044").isEqualTo(10044);
        assertThat(kbDocsContains(token, kbId, docId)).as("按库列表里不该有它").isFalse();
        assertThat(allDocsContains(token, docId)).as("跨库列表里也不该有它").isFalse();

        // ② 不可召回：可见文档集合少了一个，且**过滤表达式里不再有它**
        //
        // ⚠️ 判据**不能**落在 `Scope#allowsDoc` 上 —— 它的正常路径恒返回 true
        //    （见那里的 javadoc：「Milvus 已经在索引层筛过，这里不做第二次判断」，
        //    只有文档数超 `MAX_DOC_FILTER` 的退化路径才真判）。拿它当"不可召回"的证据
        //    是一条**恒真的假判据**：软删没生效时它一样是绿的。
        //    真正的落点是 `visibleDocIds` 那句 SQL —— 它带 `d.deleted_at is null`，
        //    而它的两个产物就是下面这两条。
        RetrievalScope.Scope scope = scopeOf(kbId);
        assertThat(scope.docScopeCount())
                .as("可见文档数正好少 1（`RetrievalScope#visibleDocIds` 的 `deleted_at is null`）")
                .isEqualTo(countBefore - 1);
        assertThat(scope.milvusFilter())
                .as("🔴 下发给 Milvus 的表达式里不再有它 —— 否则「软删」等于没删"
                        + "（向量还躺在分区里，那正是 G7-14 两半必须成对的原因）")
                .doesNotContain("doc_id in [" + docId + "]");
    }

    @Test
    @DisplayName("G7-14：**彻底删除**才清对象与向量（那之后 sizeOf = -1 / 向量 = 0）")
    void purgeRemovesObjectsAndVectors() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());
        long kbId = createProbeKb(token);
        long docId = ((Number) uploadReady(kbId, "彻底删探针.md", token).data("$.docId")).longValue();
        String storagePath = storagePathOf(docId);
        // ⚠️ 先把"被向量化过"造出来：否则 `purgeDoc` 里那句
        //    `if (doc.indexGeneration() > 0)` 会直接跳过向量清理，
        //    而用例断言 `count == 0` 照样绿 —— 空断言（见 `RecycleTestSupport#seedVectors`）。
        int generation = 1;
        assertThat(seedVectors(kbId, docId, generation, 3)).isEqualTo(3);
        assertThat(vectorCountOf(kbId, generation)).as("前置态：分区里真有 3 条向量")
                .isEqualTo(3);

        delete("/api/doc/" + docId, token).assertOk("先软删");
        assertThat(vectorCountOf(kbId, generation)).as("🔴 软删**不清**向量（这是 G7-14 的另一半）")
                .isEqualTo(3);

        RawResponse purged = call("DELETE", "/api/recycle/items",
                java.util.Map.of("type", "DOC", "ids", List.of(docId)), token);
        assertThat(purged.code()).as("彻底删除：" + purged.body()).isZero();
        assertThat((Integer) purged.data("$.deleted")).isEqualTo(1);

        assertThat(docRowExists(docId)).as("行没了").isFalse();
        assertThat(docStorage.sizeOf(storagePath)).as("对象也没了（sizeOf 对不存在的对象返回 -1）")
                .isEqualTo(-1L);
        assertThat(vectorCountOf(kbId, generation)).as("到这一步才清向量，且真清到了 0").isZero();
        assertThat(jdbc.queryForObject("select count(*) from sys_chunk where doc_id = ?",
                Long.class, docId)).as("派生行（分片）也清了").isZero();
    }

    @Test
    @DisplayName("批量删除 = 先全验后全删：含一个不存在的 ⇒ **整批拒绝**且零副作用")
    void batchDeleteRejectsTheWholeBatch() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());
        long kbId = createProbeKb(token);
        long docA = ((Number) uploadReady(kbId, "整批甲.md", token).data("$.docId")).longValue();
        long docB = ((Number) uploadReady(kbId, "整批乙.md", token).data("$.docId")).longValue();

        RawResponse res = call("DELETE", "/api/doc/batch",
                java.util.Map.of("ids", List.of(docA, 999999999L, docB)), token);

        assertThat(res.code()).as("含不存在的文档：" + res.body()).isEqualTo(10044);
        // 🔴 关键：**另外两篇也没被删** —— 这才是"整批拒绝"与"静默过滤"的分水岭
        assertThat(docSoftDeleted(docA)).as("甲不该被删（静默过滤会把它删掉）").isFalse();
        assertThat(docSoftDeleted(docB)).as("乙也不该被删").isFalse();
    }

    @Test
    @DisplayName("T-612：跨库列表只回可见的、且行里带 kbId/kbName；软删的库与文档都不出现")
    void crossKbListHonoursVisibility() {
        String token = loginAsAdmin().data("$.accessToken");
        long adminId = adminUserId();
        trackUser(adminId);
        long kbId = createProbeKb(token);
        long docId = ((Number) uploadReady(kbId, "跨库列表探针.md", token).data("$.docId")).longValue();

        RawResponse page = get("/api/doc/all?page=1&size=200", token).assertOk("跨库列表");
        List<java.util.Map<String, Object>> list = page.data("$.list");
        java.util.Map<String, Object> mine = list.stream()
                .filter(r -> ((Number) r.get("id")).longValue() == docId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("跨库列表里找不到刚上传的文档：" + page.body()));
        assertThat(((Number) mine.get("kbId")).longValue()).isEqualTo(kbId);
        assertThat(mine.get("kbName")).as("行里必须带 kbName —— 跨库列表显示的就是它")
                .isEqualTo("M06-E 下载探针库");

        // 非法 kbId 显式报错，**不静默当成「全部」**（否则"按库筛"变成了"看全部"）
        assertThat(get("/api/doc/all?kbId=不是数字", token).code()).isEqualTo(10030);
    }

    // ==================== 小工具 ====================

    /** 带 body 的 DELETE（`http.delete(path, body)` 的服务端形态）。 */
    private RawResponse delete(String path, String token) {
        return call("DELETE", path, null, token);
    }

    private RetrievalScope.Scope scopeOf(long kbId) {
        return retrievalScope.resolve(adminViewer(), List.of(kbId));
    }

    /** 种子超管的视角（与 `RetrievalScopeFlowTest` 同一种构造法：直接造 `LoginUser` 调 Service）。 */
    private static LoginUser adminViewer() {
        return LoginUser.builder()
                .userId(1L).username("admin").deptId(1L)
                .superAdmin(true).dataScope(DataScope.ALL)
                .secretLevelUpper(SecretLevel.SECRET)
                .build();
    }

    private boolean kbDocsContains(String token, long kbId, long docId) {
        RawResponse page = get("/api/kb/" + kbId + "/docs?page=1&size=200", token).assertOk("按库列表");
        List<java.util.Map<String, Object>> list = page.data("$.list");
        return list.stream().anyMatch(r -> ((Number) r.get("id")).longValue() == docId);
    }

    private boolean allDocsContains(String token, long docId) {
        RawResponse page = get("/api/doc/all?page=1&size=200", token).assertOk("跨库列表");
        List<java.util.Map<String, Object>> list = page.data("$.list");
        return list.stream().anyMatch(r -> ((Number) r.get("id")).longValue() == docId);
    }
}
