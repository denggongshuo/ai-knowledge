package com.knowledge.ai.service;

import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.support.RecycleTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-616` 回收站（`G7-10`~`G7-13`）。
 *
 * <h3>本类钉住的三件</h3>
 * <ol>
 *   <li><b>`G7-10` 的剩余天数是个**算术判据**</b> —— 所以在三档的**边界上逐点断言**
 *       （30 / 20 / 19 / 16 / 15 / 0），而不是"看起来差不多"；</li>
 *   <li><b>`G7-11` 的"连带"是**零动作**</b>（删库只写 kb 那一行）—— 正因为它是零动作，
 *       才必须把两个隐含前提钉住：软删的库**不可达**、恢复后 `affectedDocs` **如实数得出**；</li>
 *   <li><b>`O7` 规则表的彻底删除**逐格**</b> —— 源文件删 · 解析产物删 · 向量删 ·
 *       **`sys_download_record` 留**（下载是既成事实）。</li>
 * </ol>
 */
class RecycleFlowTest extends RecycleTestSupport {

    @Test
    @DisplayName("G7-10：剩余天数 = 30 −（今天 − 删除日），三档边界逐点对")
    void remainDaysIsArithmeticOnCalendarDays() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());
        long kbId = createProbeKb(token);
        long docId = ((Number) uploadReady(kbId, "剩余天数探针.md", token).data("$.docId")).longValue();
        deleteDoc(docId, token);

        // 🔴 三档的**分界点**都取到：≥20 绿 / 16~19 橙 / <16 红（G7-10 与前端 remainClass 一致）
        assertThat(remainDaysAfter(docId, 0, token)).as("今天删的 ⇒ 满 30 天").isEqualTo(30);
        assertThat(remainDaysAfter(docId, 10, token)).as("10 天前删 ⇒ 20（绿档下界）").isEqualTo(20);
        assertThat(remainDaysAfter(docId, 11, token)).as("11 天前删 ⇒ 19（橙档上界）").isEqualTo(19);
        assertThat(remainDaysAfter(docId, 14, token)).as("14 天前删 ⇒ 16（橙档下界）").isEqualTo(16);
        assertThat(remainDaysAfter(docId, 15, token)).as("15 天前删 ⇒ 15（红档上界）").isEqualTo(15);
        assertThat(remainDaysAfter(docId, 45, token)).as("45 天前删 ⇒ 夹到 0，不出现负数").isZero();

        RawResponse page = get("/api/recycle/items?type=DOC", token).assertOk("回收站列表");
        assertThat((Integer) page.data("$.keepDays")).as("保留期由服务端下发，不是前端常量")
                .isEqualTo(30);
    }

    @Test
    @DisplayName("G7-10：列表带删除人 / 原库 / 大小；软删的**库**那一 Tab 带其下文档数")
    void listCarriesJoinedColumnsForBothTabs() {
        String token = loginAsAdmin().data("$.accessToken");
        long adminId = adminUserId();
        trackUser(adminId);
        String adminName = jdbc.queryForObject(
                "select real_name from sys_user where id = ?", String.class, adminId);

        long kbId = createProbeKb(token);
        long docId = ((Number) uploadReady(kbId, "列表列探针.md", token).data("$.docId")).longValue();
        deleteDoc(docId, token);

        Map<String, Object> docRow = findRow("DOC", docId, token);
        assertThat(docRow.get("name")).isEqualTo("列表列探针");
        assertThat(docRow.get("origin")).as("文档的 origin = 原所属知识库").isEqualTo("M06-E 下载探针库");
        assertThat(docRow.get("deletedBy")).as("删除人由 V41 的 deleted_by join 出来").isEqualTo(adminName);
        assertThat(docRow.get("originDeleted")).as("原库还在 ⇒ false").isEqualTo(false);
        assertThat(docRow.get("hasNewer")).as("同组没有更新版本 ⇒ false").isEqualTo(false);
        assertThat(docRow.get("docCount")).as("文档条目的 docCount 恒 null（不是 0）").isNull();

        // ---- 同一个 Tab 的**反例**：原库也软删了 ⇒ originDeleted 必须翻成 true ----
        // 🔴 这一条是本类**唯一**能抓住 `(k.deleted_at is not null)` 那个布尔表达式
        //    读法错误的判据：MySQL 把表达式回成 BIGINT 的 1/0，而只认 `Boolean` 的写法
        //    （`Boolean.TRUE.equals(1L)`）对**任何**输入都返回 false ⇒
        //    「原库已删要选目标库」那条分支永远进不去。只测 false 那一半的话，
        //    恒 false 的实现照样全绿（本批实测踩到过：`V41` 的 deleteBy 与这条同一族）。
        long kb3 = createProbeKb(token);
        long orphanDoc = ((Number) uploadReady(kb3, "原库已删.md", token).data("$.docId")).longValue();
        deleteDoc(orphanDoc, token);
        deleteKb(kb3, token);
        assertThat(findRow("DOC", orphanDoc, token).get("originDeleted"))
                .as("原库已进回收站 ⇒ originDeleted 为 true（前端据此弹出「选目标知识库」）")
                .isEqualTo(true);

        // ---- 知识库那一档 ----
        long kb2 = createProbeKb(token);
        uploadReady(kb2, "连带甲.md", token);
        uploadReady(kb2, "连带乙.md", token);
        deleteKb(kb2, token);

        Map<String, Object> kbRow = findRow("KB", kb2, token);
        assertThat(kbRow.get("type")).isEqualTo("KB");
        assertThat(kbRow.get("docCount")).as("知识库条目的 docCount = 会被连带恢复的篇数")
                .isEqualTo(2);
        assertThat(kbRow.get("origin")).as("知识库的 origin = 所属部门（与文档那一档含义不同）")
                .isNotNull();
    }

    @Test
    @DisplayName("G7-11：软删的库不可达；恢复是**零动作**但 affectedDocs 如实数得出")
    void restoringKbRestoresItsDocumentsByNotTouchingThem() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());
        long kbId = createProbeKb(token);
        long docA = ((Number) uploadReady(kbId, "连带丙.md", token).data("$.docId")).longValue();
        long docB = ((Number) uploadReady(kbId, "连带丁.md", token).data("$.docId")).longValue();

        deleteKb(kbId, token);

        // 前提 (i)：软删的库**不可达** —— 这正是"连带恢复"能成立的那半边
        assertThat(get("/api/kb/" + kbId + "/config", token).code())
                .as("软删的库对谁都不可见（kbVisibility 自带 deleted_at is null）")
                .isNotZero();
        assertThat(get("/api/kb/" + kbId + "/docs", token).code()).isNotZero();
        // 且它名下的文档**从未被软删**（这就是"连带"是零动作的原因）
        assertThat(docSoftDeleted(docA)).as("删库**不**连带软删文档 —— 所以恢复时也不需要扫回来")
                .isFalse();
        assertThat(docSoftDeleted(docB)).isFalse();

        RawResponse res = post("/api/recycle/restore",
                Map.of("type", "KB", "ids", List.of(kbId)), token).assertOk("恢复知识库");
        assertThat((Integer) res.data("$.affectedDocs")).as("如实数出连带的篇数").isEqualTo(2);
        assertThat((Boolean) res.data("$.needReindex")).as("需求 §7.2：恢复后需重建索引").isTrue();

        // 恢复之后库可达、文档也在（它们本来就没被动过）
        assertThat(get("/api/kb/" + kbId + "/config", token).code()).isZero();
        assertThat(get("/api/doc/" + docA, token).code()).isZero();
    }

    @Test
    @DisplayName("G7-11：恢复文档 —— 原库还在时原地恢复；原库已删时必须指定目标库")
    void restoringDocNeedsTargetWhenOriginKbIsGone() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());
        long kbId = createProbeKb(token);
        long docId = ((Number) uploadReady(kbId, "换库恢复.md", token).data("$.docId")).longValue();

        // ---- ① 原库已删 + 没给目标库 ⇒ 拒绝（且给出下一步）----
        deleteDoc(docId, token);
        deleteKb(kbId, token);
        RawResponse rejected = post("/api/recycle/restore",
                Map.of("type", "DOC", "ids", List.of(docId)), token);
        assertThat(rejected.code()).as("该拒绝并说清要选目标库：" + rejected.body()).isEqualTo(10030);
        assertThat(rejected.message())
                .as("要说清**为什么**拒（原库没了）以及**下一步做什么**（指定一个库）")
                .contains("原知识库已删除").contains("请指定");
        assertThat(docSoftDeleted(docId)).as("被拒时**零副作用**：它还留在回收站").isTrue();

        // ---- ② 给了目标库 ⇒ 真的换库 ----
        long target = createProbeKb(token);
        RawResponse ok = post("/api/recycle/restore",
                Map.of("type", "DOC", "ids", List.of(docId), "targetKbId", target), token)
                .assertOk("换库恢复");
        // ⚠️ 别写成 `(Long) ok.data(...)`：JsonPath 对放得进 int 的数回的是 **Integer**
        //    （DTO 那侧是 Long），于是转型当场 ClassCastException —— 与 `data()` 那个
        //    `<T> T` 推断不出目标类型是同一族问题。统一走 Number 收口。
        assertThat(((Number) ok.data("$.targetKbId")).longValue()).isEqualTo(target);
        assertThat(jdbc.queryForObject("select kb_id from sys_document where id = ?",
                Long.class, docId)).as("文档已换库").isEqualTo(target);
        assertThat(jdbc.queryForObject("select count(*) from sys_chunk where doc_id = ? and kb_id = ?",
                Long.class, docId, target))
                .as("🔴 分片也要换 kb_id —— 只改文档列会让分片按**旧库密级**参与过滤")
                .isPositive();
        assertThat(docSoftDeleted(docId)).isFalse();
    }

    @Test
    @DisplayName("O7 规则表 · 彻底删除知识库：两个前缀 + 全部代分区 + 行族都清，**下载记录留着**")
    void purgingKbClearsEverythingExceptHistory() {
        String token = loginAsAdmin().data("$.accessToken");
        long adminId = adminUserId();
        trackUser(adminId);
        long kbId = createProbeKb(token);
        long docId = ((Number) uploadReady(kbId, "整库清探针.md", token).data("$.docId")).longValue();
        String storagePath = storagePathOf(docId);

        // ⚠️ **两代分区**都要造出来，否则"全部代都丢了"这条判据区分不了
        //    「丢了全部代」与「只丢了当前代」—— 而后者正是 `T-O27` 那族
        //    （历史代留下来就是一堆谁也认领不了的孤儿向量）。
        //    测试期的 `m05.vectorize.enabled=false` ⇒ 必须自己造，见 `RecycleTestSupport#seedVectors`。
        assertThat(seedVectors(kbId, docId, 1, 2)).as("前置态：历史代（第 1 代）有 2 条向量")
                .isEqualTo(2);
        assertThat(seedVectors(kbId, docId, 2, 3)).as("前置态：当前代（第 2 代）有 3 条向量")
                .isEqualTo(3);
        int generation = generationOf(docId);

        // 造一条下载记录 —— 它是"用户下过什么"的既成事实，**彻底删除也不该抹掉**
        get("/api/doc/download-url/" + docId, token).assertOk("先制造一条下载记录");

        deleteKb(kbId, token);
        // 前置态复查放在**软删之后、彻底删除之前**：证明"分区与向量确实还在"
        // （否则下面那两条 `isFalse()` 可能只是因为本来就没有）
        assertThat(partitionManager.has(MilvusConstants.partitionName(kbId, 1))).isTrue();
        assertThat(partitionManager.has(MilvusConstants.partitionName(kbId, 2))).isTrue();

        RawResponse purged = call("DELETE", "/api/recycle/items",
                Map.of("type", "KB", "ids", List.of(kbId)), token).assertOk("彻底删除知识库");
        assertThat((Integer) purged.data("$.deleted")).isEqualTo(1);

        assertThat(countOf("select count(*) from sys_kb where id = ?", kbId)).as("库行没了").isZero();
        assertThat(countOf("select count(*) from sys_document where kb_id = ?", kbId))
                .as("其下文档行连带没了").isZero();
        assertThat(countOf("select count(*) from sys_chunk where kb_id = ?", kbId)).isZero();
        assertThat(docStorage.sizeOf(storagePath)).as("对象按**前缀**整块删了")
                .isEqualTo(-1L);
        assertThat(partitionManager.has(MilvusConstants.partitionName(kbId, generation)))
                .as("🔴 该库**全部代**的分区都丢了（只删当前代会让历史代变无主向量 —— T-O27 那族）")
                .isFalse();
        assertThat(partitionManager.has(MilvusConstants.partitionName(kbId, 1)))
                .as("🔴 **历史代也丢了**（第 1 代已不是当前代，只按当前代清会把它留在集合里）")
                .isFalse();
        assertThat(countOf("select count(*) from sys_download_record where user_id = ?", adminId))
                .as("🔴 下载记录**留着**：O7 规则表说它永久保留（下载是既成事实）")
                .isPositive();
    }

    @Test
    @DisplayName("G7-12：清空回收站 —— 两类条目一起清，且清完之后列表为空")
    void clearAllEmptiesBothTabs() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());
        long kbId = createProbeKb(token);
        long docId = ((Number) uploadReady(kbId, "清空探针.md", token).data("$.docId")).longValue();
        long kb2 = createProbeKb(token);
        deleteDoc(docId, token);
        deleteKb(kb2, token);

        RawResponse cleared = call("DELETE", "/api/recycle/all", null, token).assertOk("清空回收站");
        assertThat((Integer) cleared.data("$.cleared")).as("两条（1 文档 + 1 库）").isEqualTo(2);

        // ⚠️ `data()` 是 `<T> T data(String)` —— 不给类型时目标类型推断不出来，
        //    编译器会去找 `assertThat(Predicate)` 那些重载。显式转型是必须的。
        assertThat((Integer) get("/api/recycle/items?type=DOC", token)
                .assertOk("查文档档").data("$.total"))
                .as("清空之后文档档为空").isEqualTo(0);
        assertThat((Integer) get("/api/recycle/items?type=KB", token)
                .assertOk("查知识库档").data("$.total"))
                .isEqualTo(0);
    }

    @Test
    @DisplayName("入参：type 必填且只能是 DOC/KB；空 ids 报 10030（不默认成 DOC）")
    void typeIsRequiredAndValidated() {
        String token = loginAsAdmin().data("$.accessToken");
        assertThat(get("/api/recycle/items?type=XX", token).code()).as("非法 type 不静默当成 DOC")
                .isEqualTo(10030);
        assertThat(call("DELETE", "/api/recycle/items", Map.of("ids", List.of(1)), token).code())
                .as("缺 type").isEqualTo(10030);
        assertThat(call("DELETE", "/api/recycle/items",
                Map.of("type", "DOC", "ids", List.of()), token).code())
                .as("空 ids").isEqualTo(10030);
    }

    // ==================== 小工具 ====================

    private void deleteDoc(long docId, String token) {
        call("DELETE", "/api/doc/" + docId, null, token).assertOk("软删文档");
    }

    private void deleteKb(long kbId, String token) {
        call("DELETE", "/api/kb/" + kbId, null, token).assertOk("软删知识库");
    }

    /** 把删除时刻回拨 N 天，再读回接口算出的剩余天数（判据要的是**接口给的那个数**）。 */
    private int remainDaysAfter(long docId, int daysAgo, String token) {
        jdbc.update("update sys_document set deleted_at = ? where id = ?",
                LocalDateTime.now().minusDays(daysAgo), docId);
        Object v = findRow("DOC", docId, token).get("remainDays");
        return ((Number) v).intValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findRow(String type, long id, String token) {
        RawResponse page = get("/api/recycle/items?type=" + type + "&page=1&size=200", token)
                .assertOk("查回收站 " + type);
        List<Map<String, Object>> list = page.data("$.list");
        return list.stream()
                .filter(r -> ((Number) r.get("id")).longValue() == id)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        type + " 档里找不到 id=" + id + "：" + page.body()));
    }

    private long countOf(String sql, Object arg) {
        Long n = jdbc.queryForObject(sql, Long.class, arg);
        return n == null ? 0L : n;
    }
}
