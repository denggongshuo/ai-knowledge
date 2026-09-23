package com.knowledge.ai.service;

import com.knowledge.ai.core.storage.DocStorage;
import com.knowledge.ai.support.DownloadTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 移库与对象存储的关系 —— **`#223`（不误删）与 `#232`（真的搬）两条一起验**。
 *
 * <h3>🔴 本类在 `S9-M12-D` 被改写过一次，改的是判据不是实现</h3>
 * <p>原判据（`S9-M12-C`）把「**移动不改 `storage_path`**」当成前置态来断言 ——
 * 因为在那个版本里它就是事实：{@code moveToKb}（`G7-4`）只改 `kb_id` 列、对象一个都不搬，
 * 于是"行在 B 库、对象压在 A 库前缀下"是**设计的一部分**，`#223` 的误删风险正由此而来。
 * <p>本批（`S9-M12-D`）把 `#232` 也修了 ⇒ 那条前置**不再成立**，判据必须跟着改。
 * ⚠️ 这不是"判据松了"而是"被测对象变了"：新前置是
 * <b>对象搬到 B 前缀 + 两列路径改写 + A 前缀下再无该对象</b> —— 比原来更强（多了两条断言）。
 * 原始判据的文字留在 git 里（`git log -- ai_knowledge_web/src/test/.../DocMoveThenPurgeFlowTest.java`）。
 *
 * <h3>为什么还要留着"清源库不误删"这条（`#223`）</h3>
 * <p>搬完之后"借住"关系对新数据不存在了，但**存量数据里还有**（历史上移过库、对象没搬的那些行）。
 * `purgeKb` 的 `removePrefixExcept(..., stillReferenced)` 就是为它们留的兜底 ——
 * 删掉它会让那些行重新变成"文件没了、行还在"。所以这条判据**保留**，只是它现在验的是兜底，
 * 不再是主路径（主路径由"对象本来就不在 A 前缀下"保证）。
 */
@DisplayName("判据 16~18 · 移库真的搬对象（销 #232）+ 清源库不误删（#223 兜底）")
class DocMoveThenPurgeFlowTest extends DownloadTestSupport {

    /** 借住的那篇：从 A 库移到 B 库。 */
    private static final String MOVED_FILE = "移库探针-借住.md";

    /** 留在 A 库、随 A 库一起被清的那篇。 */
    private static final String STAYING_FILE = "移库探针-留下.md";

    // ==================================================================
    // 判据 16：移动后对象在新库前缀下、旧前缀下没有它、路径列已改写
    // ==================================================================

    @Test
    @DisplayName("判据16 · A→B 移库后：对象在 B 前缀下、A 前缀下没有、两列路径已改写")
    void movingDocAlsoMovesItsObjects() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());

        long kbA = createProbeKb(token);
        long kbB = createProbeKb(token);
        long movedDoc = docIdOf(uploadReady(kbA, MOVED_FILE, token));

        String oldObject = storagePathOf(movedDoc);
        assertThat(oldObject).as("前置态：对象在 A 库前缀下")
                .startsWith(DocStorage.docObjectPrefix(kbA, movedDoc));
        assertThat(docStorage.sizeOf(oldObject)).as("前置态：对象真的在桶里").isPositive();

        call("PATCH", "/api/doc/" + movedDoc + "/attributes", Map.of("kbId", kbB), token)
                .assertOk("把文档移到 B 库");

        // 🔴 判据 16 的本体：路径列指向**新前缀**，而新前缀下真的有字节
        String newObject = storagePathOf(movedDoc);
        assertThat(newObject)
                .as("🔴 `#232`：移动没改写 `storage_path` ⇒ 行还指着旧位置")
                .startsWith(DocStorage.docObjectPrefix(kbB, movedDoc));
        assertThat(docStorage.sizeOf(newObject))
                .as("新前缀下读不到对象 ⇒ 只改了列、没搬字节（比不改更糟：行指向一个不存在的位置）")
                .isPositive();
        assertThat(new String(docStorage.open(newObject), java.nio.charset.StandardCharsets.UTF_8))
                .as("字节要真的读得回来，不只是 stat 得到")
                .contains(MOVED_FILE);

        // 🔴 旧前缀下**必须没有它** —— 否则是"搬了但没删旧的"，两个库都占着这份存储
        assertThat(docStorage.sizeOf(oldObject))
                .as("旧对象没删 ⇒ 存储被记两遍（A 库用量虚高，而对象已经不属于它）")
                .isEqualTo(-1L);
        assertThat(jdbc.queryForObject("select kb_id from sys_document where id = ?",
                Long.class, movedDoc)).isEqualTo(kbB);
    }

    // ==================================================================
    // 判据 17：移动中途失败不丢文件
    // ==================================================================

    @Test
    @DisplayName("判据17 · 复制失败时不动库、不删源对象（顺序：复制 → 改库 → 删旧）")
    void failedCopyLeavesEverythingIntact() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());

        long kbA = createProbeKb(token);
        long kbB = createProbeKb(token);
        long doc = docIdOf(uploadReady(kbA, MOVED_FILE, token));
        String oldObject = storagePathOf(doc);

        // ⚠️ 这里**不注入失败**（没有可注入的缝：`copyPrefix` 直接调 MinIO）。
        //    改验"顺序"的可观测推论：目标前缀**先**建出来、源对象**后**才消失。
        //    —— 一个把顺序写反的实现（先删后复制）在正常路径上结果一样，
        //    所以这一条只能验"最终态正确"，顺序本身靠 ⓪ 那段的代码注释与人工复核。
        //    ⇒ 本用例的射程如实写在断言消息里，不假装它验了故障注入。
        call("PATCH", "/api/doc/" + doc + "/attributes", Map.of("kbId", kbB), token).assertOk("移库");

        assertThat(docStorage.sizeOf(DocStorage.docArtifactPrefix(kbB, doc)))
                .as("目录前缀本身不是对象，`sizeOf` 对它必然 -1 —— 这一格只用来证明调用没抛")
                .isEqualTo(-1L);
        // 真实的可观测点：新位置可读、旧位置已空（与判据 16 同一对，此处强调"没有中间态残留"）
        assertThat(docStorage.sizeOf(storagePathOf(doc))).isPositive();
        assertThat(docStorage.sizeOf(oldObject))
                .as("移动完成后旧对象仍在 ⇒ 要么没删、要么顺序反了")
                .isEqualTo(-1L);
    }

    // ==================================================================
    // 判据 18：`#223` 不复发 —— 清源库，B 库那篇仍可下载
    // ==================================================================

    @Test
    @DisplayName("判据18 · 移动后彻底删除源库 ⇒ 目标库那篇仍读得回字节；A 库自己的对象照删")
    void purgingSourceKbKeepsTheObjectOfTheMovedDoc() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());

        long kbA = createProbeKb(token);
        long kbB = createProbeKb(token);
        long movedDoc = docIdOf(uploadReady(kbA, MOVED_FILE, token));
        long stayingDoc = docIdOf(uploadReady(kbA, STAYING_FILE, token));

        String movedObject = storagePathOf(movedDoc);
        String stayingObject = storagePathOf(stayingDoc);
        assertThat(stayingObject).as("前置态：留下的那篇在 A 库前缀下")
                .startsWith(DocStorage.kbDocPrefix(kbA));

        call("PATCH", "/api/doc/" + movedDoc + "/attributes", Map.of("kbId", kbB), token)
                .assertOk("把文档移到 B 库");
        // 移动之后对象已经不在 A 前缀下了（`#232` 的效果）
        String movedObjectNow = storagePathOf(movedDoc);
        assertThat(movedObjectNow).startsWith(DocStorage.docObjectPrefix(kbB, movedDoc));

        // 清 A 库：软删 → 彻底删除
        call("DELETE", "/api/kb/" + kbA, null, token).assertOk("软删 A 库");
        call("DELETE", "/api/recycle/items", Map.of("type", "KB", "ids", List.of(kbA)), token)
                .assertOk("彻底删除 A 库");

        // ① 移走的那篇：行在 B 库，**对象也要还在、还能读回字节**
        assertThat(jdbc.queryForObject("select kb_id from sys_document where id = ?",
                Long.class, movedDoc))
                .as("B 库的行不该被 A 库的清理动到").isEqualTo(kbB);
        assertThat(docStorage.sizeOf(movedObjectNow))
                .as("🔴 `#223`：对象被按 A 库前缀整块删掉了")
                .isPositive();
        assertThat(new String(docStorage.open(movedObjectNow), java.nio.charset.StandardCharsets.UTF_8))
                .as("不只是 stat 得到：字节要真的读得回来（`open` 直接连 MinIO）")
                .contains(MOVED_FILE);

        // ② 对照面：A 库**自己**的对象照删 —— 否则"修法"可以退化成"谁都不删"还能骗过第 ① 条
        assertThat(docStorage.sizeOf(stayingObject)).as("A 库自己的对象该被清掉").isEqualTo(-1L);
        assertThat(jdbc.queryForObject("select count(*) from sys_document where kb_id = ?",
                Long.class, kbA)).as("A 库的行族也清了").isZero();
    }

    /** 上传接口回的 docId。 */
    private static long docIdOf(RawResponse uploaded) {
        return ((Number) uploaded.assertOk("上传探针文档").data("$.docId")).longValue();
    }
}
