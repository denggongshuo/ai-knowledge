package com.knowledge.ai.service;

import com.knowledge.ai.core.storage.DocStorage;
import com.knowledge.ai.support.DownloadTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `#223` —— **移库之后清源库，不能把借住的那篇文件删掉**（`S9-M12-C` 步 14）。
 *
 * <h3>它复现的是一个"静默的删除"</h3>
 * <p>对象名与知识库**绑死**（{@code documents/{kbId}/…}，见 {@code DocStorage#kbDocPrefix}），
 * 而 {@code DocReindexService#moveToKb}（`G7-4`）**只改 `kb_id`、不搬对象**
 * （它删的是旧代分区的向量，不是 MinIO 里的字节）。
 * <p>于是"从 A 库移到 B 库"的文档：<b>行在 B 库，对象还压在 A 库前缀下</b>。
 * 此后清 A 库若按前缀整块删，就把它一起删了 ——
 * 而 B 库那一行仍是 {@code READY}、页面照常列着它，用户点下载才拿不到文件，
 * <b>且没有任何地方报错</b>（{@code DocStorage#remove} 是静默的）。这就是 `#223`。
 *
 * <h3>因此两条断言必须成对</h3>
 * <ol>
 *   <li><b>借住的那篇要活下来</b> —— 对象还在、还能 {@code open} 出字节；
 *       光断言"下载接口没报错"是不够的：{@code requireStored} 只判 `storage_path` 非空，
 *       而那一列**本来就非空**（它记的是 A 库的路径）⇒ 它在对象被删之后**照样不报错**。
 *       必须真去桶里读字节。那正是本条判据要防的"看起来通的"形态。</li>
 *   <li><b>A 库自己的对象要照删</b> —— 否则"修法"可能退化成一个**谁都不删**的空实现，
 *       而第 ① 条照样绿。这一条就是它的对照。</li>
 * </ol>
 *
 * <h3>⚠️ 为什么不去 `moveToKb` 里把对象也搬走（那条更彻底）</h3>
 * <p>那是**另一个批次的改动**：它要重写 `storage_path` / `parse_path` 两列、
 * 要对"搬了一半失败"给出可重试的语义，还要处理同一对象的并发引用。
 * 本批只做**不误删**这一半，范围与代价都写在计划 §9 的遗留项里。
 *
 * <h3>⚠️ 本用例**不动**向量化开关，而这不影响结论</h3>
 * <p>测试期 {@code m05.vectorize.enabled=false} ⇒ {@code moveToKb} 拿到的
 * {@code ChunkVectorizer.Result} 是 {@code DISABLED}，于是它**不盖** {@code READY}
 * （那是正字：宁可不报可检索，也不谎报），文档停在 {@code INDEXING}。
 * <p>本条判据要的借住关系只由两件事构成 —— <b>行换了 {@code kb_id}</b> 与
 * <b>对象没搬</b> —— 两件都与文档状态无关。所以刻意**不**打开向量化：
 * 打开它只会让这一步多写一遍真向量，却不会让判据多验到一个字节。
 * （要那一半的是 `DocReindexGuardFalsifyTest`，它验的是代复核接线。）
 */
@DisplayName("判据 12 · 移库后清源库不误删（销 #223）")
class DocMoveThenPurgeFlowTest extends DownloadTestSupport {

    /** 借住的那篇：从 A 库移到 B 库。 */
    private static final String MOVED_FILE = "移库探针-借住.md";

    /** 留在 A 库、随 A 库一起被清的那篇。 */
    private static final String STAYING_FILE = "移库探针-留下.md";

    @Test
    @DisplayName("A→B 移库后彻底删除 A 库 ⇒ B 库那篇仍在桶里（可读回字节），A 库其余对象已清")
    void purgingSourceKbKeepsTheObjectOfTheMovedDoc() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());

        long kbA = createProbeKb(token);
        long kbB = createProbeKb(token);
        long movedDoc = docIdOf(uploadReady(kbA, MOVED_FILE, token));
        long stayingDoc = docIdOf(uploadReady(kbA, STAYING_FILE, token));

        String movedObject = storagePathOf(movedDoc);
        String stayingObject = storagePathOf(stayingDoc);

        // 🔴 前置态：两条路径都必须**真的落在同一个库前缀下**，否则这条判据什么都没验
        //    （比如将来对象名改成不带 kbId 了，前缀删除本身就不存在了 —— 那时该重写本用例）
        assertThat(movedObject).as("前置态：借住这篇的对象在 A 库前缀下")
                .startsWith(DocStorage.kbDocPrefix(kbA));
        assertThat(stayingObject).as("前置态：留下的那篇也在 A 库前缀下")
                .startsWith(DocStorage.kbDocPrefix(kbA));
        assertThat(docStorage.sizeOf(storagePathOf(movedDoc))).as("前置态：借住这篇的对象真的在桶里")
                .isPositive();

        // 移库：只改 kb_id，对象留在 A 库前缀下（`G7-4` 的既有语义）
        // ⚠️ 是 **PATCH** 不是 PUT（`DocController#saveAttributes` 挂的是 `@PatchMapping`）。
        //    发错方法时 Spring 抛 `HttpRequestMethodNotSupportedException`，
        //    而本仓的全局兜底把它翻成 **业务码 10030「不支持的请求方法：PUT」** ——
        //    报错文案看起来像参数问题，实际是方法错了（这一条是实测踩出来的）。
        call("PATCH", "/api/doc/" + movedDoc + "/attributes", Map.of("kbId", kbB), token)
                .assertOk("把文档移到 B 库");

        // 移完之后复查一遍 `storage_path`：**它没变**，这正是 `#223` 的成因
        assertThat(storagePathOf(movedDoc)).as("🔴 移动不改 `storage_path` —— 这就是借住关系的定义")
                .isEqualTo(movedObject);
        assertThat(jdbc.queryForObject("select kb_id from sys_document where id = ?",
                Long.class, movedDoc)).as("归属真的换了").isEqualTo(kbB);

        // 清 A 库：软删 → 彻底删除
        call("DELETE", "/api/kb/" + kbA, null, token).assertOk("软删 A 库");
        call("DELETE", "/api/recycle/items", Map.of("type", "KB", "ids", List.of(kbA)), token)
                .assertOk("彻底删除 A 库");

        // ① 借住的那篇：行还在 B 库，**对象也要还在**
        assertThat(jdbc.queryForObject("select kb_id from sys_document where id = ?",
                Long.class, movedDoc))
                .as("B 库的行不该被 A 库的清理动到").isEqualTo(kbB);
        assertThat(docStorage.sizeOf(movedObject))
                .as("🔴 `#223`：对象被按 A 库前缀整块删掉了。"
                        + "把 `purgeKb` 里的 `removePrefixExcept(..., stillReferenced)` 换回"
                        + "`removePrefix(...)` ⇒ `sizeOf` 返回 -1 ⇒ 本断言当场转红。")
                .isPositive();
        assertThat(new String(docStorage.open(movedObject), java.nio.charset.StandardCharsets.UTF_8))
                .as("不只是 stat 得到：字节要真的读得回来（`open` 直接连 MinIO）")
                .contains(MOVED_FILE);

        // ② 对照面：A 库自己的对象**照删** —— 否则"修法"可以退化成"谁都不删"还能骗过第 ① 条
        assertThat(docStorage.sizeOf(stayingObject)).as("A 库自己的对象该被清掉").isEqualTo(-1L);
        assertThat(jdbc.queryForObject("select count(*) from sys_document where kb_id = ?",
                Long.class, kbA)).as("A 库的行族也清了").isZero();
    }

    /** 上传接口回的 docId。 */
    private static long docIdOf(RawResponse uploaded) {
        return ((Number) uploaded.assertOk("上传探针文档").data("$.docId")).longValue();
    }
}
