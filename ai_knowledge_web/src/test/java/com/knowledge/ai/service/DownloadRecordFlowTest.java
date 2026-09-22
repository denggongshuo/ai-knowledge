package com.knowledge.ai.service;

import com.knowledge.ai.core.storage.DocStorage;
import com.knowledge.ai.core.text.Csv;
import com.knowledge.ai.support.DownloadTestSupport;
import io.minio.ListObjectsArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-615` / `G7-9`：下载历史（列表 + 导出清单）。
 *
 * <h3>它与审计的关系（本类的存在理由）</h3>
 * <p>门禁 `G7-9` 要**两份**证据，它们服务两个不同的读者：
 * {@code sys_audit_log} 给审计员（合规留痕，`BatchDownloadFlowTest` 里断言），
 * {@code sys_download_record} 给**用户自己**（"我下过什么"）。
 * 本类钉的是后者 —— 包括它的两条口径：展示列**查询时 join**（不冗余落库）、
 * 文档软删后**记录仍在**（用「已删除」占位而不是抹掉历史）。
 */
class DownloadRecordFlowTest extends DownloadTestSupport {

    @Test
    @DisplayName("G7-9：单篇下载后能在下载历史里查到它，展示列由 join 取（标题/库名都是真值）")
    void singleDownloadShowsUpInHistoryWithJoinedColumns() {
        String token = loginAsAdmin().data("$.accessToken");
        long adminId = adminUserId();
        trackUser(adminId);
        long kbId = createProbeKb(token);
        long docId = ((Number) uploadReady(kbId, "下载记录探针.md", token)
                .data("$.docId")).longValue();

        get("/api/doc/download-url/" + docId, token).assertOk("取下载地址");

        RawResponse page = get("/api/doc/download-records?page=1&size=10", token)
                .assertOk("查下载历史");
        List<Map<String, Object>> list = page.data("$.list");
        assertThat(list).isNotEmpty();

        Map<String, Object> first = list.getFirst();
        assertThat(first.get("docId")).isEqualTo(((Number) docId).intValue());
        assertThat(first.get("docTitle")).as("标题是 join 来的真值（不是冗余落库的快照）")
                .isEqualTo("下载记录探针");
        assertThat(first.get("kbName")).isEqualTo("M06-E 下载探针库");
        assertThat(first.get("type")).isEqualTo("SINGLE");
        assertThat((String) first.get("at")).isNotBlank();
        assertThat((String) first.get("ip")).as("取不到才写「—」；真 HTTP 请求有 remoteAddr")
                .isNotBlank().isNotEqualTo("—");
        // 分页壳走全站标准（§0 的 `{list,total,page,size}`）
        assertThat((Integer) page.data("$.size")).isEqualTo(10);
        assertThat((Integer) page.data("$.page")).isEqualTo(1);
    }

    @Test
    @DisplayName("T-615：`pageSize` 与 `size` 两个名字都认（需求写 size、前端历史写 pageSize）")
    void pageSizeAliasIsAccepted() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());

        RawResponse viaSize = get("/api/doc/download-records?page=1&size=7", token)
                .assertOk("按 size 查");
        assertThat((Integer) viaSize.data("$.size")).isEqualTo(7);

        RawResponse viaAlias = get("/api/doc/download-records?page=1&pageSize=3", token)
                .assertOk("按 pageSize 查");
        assertThat((Integer) viaAlias.data("$.size"))
                .as("只认 size 的话这里会静默退回默认 20 —— 现象是「每页条数设了没用」")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("T-615：文档被删之后**记录仍在** —— 软删仍显示真标题，行真的没了才占位「已删除」")
    void historySurvivesDocDeletionAndPlaceholdersOnlyWhenRowIsGone() {
        String token = loginAsAdmin().data("$.accessToken");
        long adminId = adminUserId();
        trackUser(adminId);
        long kbId = createProbeKb(token);
        // ⚠️ 扩展名与内容必须**真的对得上**：`IngestGuard` 会按文件头交叉校验
        //    （把 Markdown 字节挂个 `.docx` 名会被判 10072 疑似伪装扩展名）
        long docId = ((Number) uploadReady(kbId, "将被删掉的.md", token)
                .data("$.docId")).longValue();

        get("/api/doc/download-url/" + docId, token).assertOk("取下载地址");

        // ---- 1) 软删：记录留下，且**标题照旧** ----
        //    为什么不是「已删除」：下载是既成事实，而"我下过的那个文件叫什么"这件事
        //    不因为**别人后来**把它挪进回收站而变得不可知。抹掉它才是丢信息。
        jdbc.update("update sys_document set deleted_at = now() where id = ?", docId);
        Map<String, Object> softDeleted = recordOf(token, docId);
        assertThat(softDeleted).as("软删之后记录必须还在").isNotNull();
        assertThat(softDeleted.get("docTitle"))
                .as("软删只是「不参与检索与列表」，不代表历史该失忆").isEqualTo("将被删掉的");
        assertThat(softDeleted.get("kbName")).isEqualTo("M06-E 下载探针库");

        // ---- 2) 硬删（行真的没了，`T-616` 的「彻底删除」）：这才轮到占位 ----
        jdbc.update("delete from sys_document where id = ?", docId);
        Map<String, Object> purged = recordOf(token, docId);
        assertThat(purged).as("行都没了，记录仍要在").isNotNull();
        assertThat(purged.get("docTitle"))
                .as("join 不到就占位「已删除」—— 空串会被读成「数据缺失」，两者含义正好相反")
                .isEqualTo("已删除");
    }

    /** 在下载历史里找某个文档的那一条（找不到返回 {@code null}）。 */
    private Map<String, Object> recordOf(String token, long docId) {
        RawResponse page = get("/api/doc/download-records?page=1&size=50", token)
                .assertOk("查下载历史");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) page.data("$.list");
        return list.stream()
                .filter(r -> ((Number) r.get("docId")).longValue() == docId)
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("T-615：只看得见自己的下载记录（别人的一条都不出现）")
    void historyIsScopedToTheCaller() {
        String adminToken = loginAsAdmin().data("$.accessToken");
        long adminId = adminUserId();
        trackUser(adminId);
        long kbId = createProbeKb(adminToken);
        long docId = ((Number) uploadReady(kbId, "归属探针.md", adminToken)
                .data("$.docId")).longValue();
        get("/api/doc/download-url/" + docId, adminToken).assertOk("超管下载一次");

        String userToken = createUser("recprobe").accessToken();
        RawResponse page = get("/api/doc/download-records?page=1&size=50", userToken)
                .assertOk("新用户查历史");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) page.data("$.list");
        assertThat(list).as("新用户一条记录都不该看见").isEmpty();
        assertThat((Integer) page.data("$.total")).isZero();
    }

    @Test
    @DisplayName("T-615：导出下载记录清单 ⇒ 异步产 CSV（BOM + 表头 + 真数据行）并经通知中心给地址")
    void exportListProducesRealCsvAndNotice() {
        String token = loginAsAdmin().data("$.accessToken");
        long adminId = adminUserId();
        trackUser(adminId);
        long kbId = createProbeKb(token);
        long docId = ((Number) uploadReady(kbId, "导出探针.md", token).data("$.docId")).longValue();
        get("/api/doc/download-url/" + docId, token).assertOk("先制造一条下载记录");

        long recordsBefore = recordCountOf(adminId);

        RawResponse accepted = post("/api/doc/export-list", null, token).assertOk("提交导出");
        String taskId = accepted.data("$.taskId");
        assertThat((String) accepted.data("$.status")).isEqualTo("PENDING");
        assertThat((String) accepted.data("$.note")).as("前端泛型里有 note，少了它那句提示是 undefined")
                .isNotBlank();

        awaitTaskDone(taskId, token);

        // ---- 产物：真去桶里找、真读出来看 ----
        List<String> keys = listObjects(DocStorage.DOC_EXPORT_PREFIX + adminId + "/");
        assertThat(keys).as("导出件必须落在 doc-export/ 前缀下（生命周期规则要能单独作用于它）")
                .hasSize(1);
        String csv = new String(docStorage.open(keys.getFirst()), StandardCharsets.UTF_8);
        assertThat(csv).as("Excel 认 BOM，不然中文是乱码").startsWith(Csv.BOM);
        assertThat(csv).contains("文档标题").contains("下载方式").contains("来源IP");
        assertThat(csv).as("必须真有那一行数据（不是只有表头的空文件）").contains("导出探针");

        // 🔴 导出**不写**下载记录：导出记录本身不是"下载了一个文件"，
        //    写进去会让历史把自己的导出动作也算成一次下载，越滚越多
        assertThat(recordCountOf(adminId)).as("导出不该产生下载记录").isEqualTo(recordsBefore);

        // ---- 交付：通知中心 + 可点的动作 ----
        Map<String, Object> notice = awaitNotice(taskId);
        assertThat(notice.get("type")).isEqualTo("SYSTEM");
        assertThat((String) notice.get("title")).contains("下载记录导出完成");
        assertThat((String) notice.get("actions_json")).contains(keys.getFirst().substring(0, 20));
    }

    // ==================== 小工具 ====================

    private long recordCountOf(long userId) {
        Long n = jdbc.queryForObject("select count(*) from sys_download_record where user_id = ?",
                Long.class, userId);
        return n == null ? 0L : n;
    }

    private List<String> listObjects(String prefix) {
        List<String> keys = new ArrayList<>();
        try {
            for (Result<Item> result : minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(minioBucket).prefix(prefix).recursive(true).build())) {
                keys.add(result.get().objectName());
            }
        } catch (Exception ex) {
            throw new AssertionError("列举桶内对象失败：prefix=" + prefix, ex);
        }
        return keys;
    }
}
