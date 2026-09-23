package com.knowledge.ai.support;

import com.knowledge.ai.core.async.ParseWorker;
import com.knowledge.ai.core.storage.DocStorage;
import io.minio.ListObjectsArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 下载类用例（`S7-M06-E` · `T-613`/`T-614`/`T-615`）的公共夹具。
 *
 * <h3>为什么单独抽一层，而不是三个用例类各写一份</h3>
 * <p>「上传一篇真文档、等它转存完、等打包任务到终态、把探针库与桶里的产物一起清掉」
 * 这一串在三个用例里一字不差。本仓已经吃过"清理函数各写一份、其中一份漏一张表"的亏
 * （{@code ProbeArtifacts} 的类注释记着四次），所以这里**只写一份**。
 *
 * <h3>🔴 清理**必须**覆盖本批新造的两个桶前缀</h3>
 * <p>{@code batch-download/} 与 {@code doc-export/} 是 {@code S7-M06-E} 新出生的产物。
 * 它们**不在** {@code ApiTestSupport} 那套"按 kb/document 归属"的兜底里
 * —— 那两个对象名由 {@code userId + taskId} 决定，**没有 kb 维度**，
 * 所以只能按用户前缀点名清。漏了的话实测会以"每轮几十个对象"的速度攒下去，
 * 而后果是 {@code KbService#minioUsedBytes} 的全桶递归列举变慢
 * （{@code DocUploadFlowTest} 的类注释记着那次"1030 个对象"的假红）。
 */
@Slf4j
public abstract class DownloadTestSupport extends ApiTestSupport {

    /** 探针库编码前缀。 */
    protected static final String PROBE_KB_PREFIX = "kb_probe_dl_";

    /** 本用例造过的库 id。 */
    private final List<Long> probeKbIds = new ArrayList<>();

    /** 本用例碰过的用户 id（桶里按用户前缀清产物要用）。 */
    private final List<Long> probeUserIds = new ArrayList<>();

    /** 解析作业的执行者（测试期后台轮询关掉了，用例显式驱动）。 */
    @Autowired
    protected ParseWorker parseWorker;

    @Autowired
    protected DocStorage docStorage;

    // ==================================================================
    // 清理
    // ==================================================================

    @AfterEach
    void cleanDownloadProbeData() {
        for (Long userId : probeUserIds) {
            removeObjectsUnder(DocStorage.BATCH_PREFIX + userId + "/");
            removeObjectsUnder(DocStorage.DOC_EXPORT_PREFIX + userId + "/");
            jdbc.update("delete from sys_download_record where user_id = ?", userId);
            // 🔴 本批的任务行**kb_id 是 NULL**（下载没有知识库维度，`TaskSubject.of(null, null, ownerId)`）
            //    ⇒ `sweepOrphanKbArtifacts` 那句 `kb_id is not null and kb_id not in (...)` **够不着它们**，
            //    `cleanUsers` 又只认探针账号（这些用例用的是种子超管）。
            //    不点名清就会每轮留一批"kb 为空的 DONE 任务"—— 与 `#130` 那个形状一模一样，
            //    只是这次的漏点是"归属维度写成了 NULL 就等于谁也认领不了"。
            jdbc.update("delete from sys_async_task where owner_id = ? "
                    + "and type in ('batch-download', 'doc-export-list')", userId);
        }
        probeUserIds.clear();

        for (Long kbId : probeKbIds) {
            for (Map<String, Object> row : jdbc.queryForList(
                    "select storage_path, parse_path from sys_document where kb_id = ?", kbId)) {
                removeObject(row.get("storage_path"));
                removeObject(row.get("parse_path"));
            }
            jdbc.update("delete from sys_download_record where kb_id = ?", kbId);
            jdbc.update("delete from sys_chunk where kb_id = ?", kbId);
            jdbc.update("delete from sys_parse_issue where kb_id = ?", kbId);
            jdbc.update("delete from sys_document where kb_id = ?", kbId);
            jdbc.update("delete from sys_kb_grant where kb_id = ?", kbId);
            jdbc.update("delete from sys_kb where id = ?", kbId);
        }
        probeKbIds.clear();
    }

    /** 记一个用户 id（桶里的产物按它前缀清）。 */
    protected long trackUser(long userId) {
        probeUserIds.add(userId);
        return userId;
    }

    /** 种子超管的 id（下载页的用例用 admin，而它不是探针账号 ⇒ 必须点名清它的产物）。 */
    protected long adminUserId() {
        return jdbc.queryForObject("select id from sys_user where username = ?", Long.class, ADMIN);
    }

    /** 对象名可能为空；删不掉也不该让用例失败。 */
    protected void removeObject(Object objectKey) {
        if (objectKey != null && !objectKey.toString().isBlank()) {
            docStorage.remove(objectKey.toString());
        }
    }

    /** 递归删掉某个前缀下的**全部**对象。 */
    protected void removeObjectsUnder(String prefix) {
        try {
            for (Result<Item> result : minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(minioBucket).prefix(prefix).recursive(true).build())) {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(minioBucket).object(result.get().objectName()).build());
            }
        } catch (Exception ex) {
            // 收尾失败只留痕（与 `DocStorage#remove` 同一取舍）——不把已经通过的用例判红
            log.warn("清理桶内前缀失败（不影响用例结论）：prefix={} err={}", prefix, ex.getMessage());
        }
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /** 建一个探针库（对所有角色可见），登记待清。 */
    protected long createProbeKb(String token) {
        return createProbeKb(token, List.of("SUPER_ADMIN", "USER"));
    }

    /**
     * 建一个探针库，**指定可见角色**。
     *
     * <p>⚠️ 只给 {@code SUPER_ADMIN} 时，普通用户就连"这个库存不存在"都判不到 ——
     * 那正是 `10022`（可申请授权）与 `10044`（文档没了）要分开报的场景。
     * ⚠️ 这不是测试的小花招：{@code visible_roles} 是**知识库自己声明**的可见范围，
     * 与用户的数据范围是**两条并行的**准入线（任一条放行即可见）——
     * 所以"用普通用户测越权"时必须把这条线也关掉，否则测出来的是"用户本来就有权"。
     */
    protected long createProbeKb(String token, List<String> visibleRoles) {
        String code = PROBE_KB_PREFIX + Long.toString(System.nanoTime(), 36);
        Map<String, Object> form = formWith(code);
        form.put("visibleRoles", visibleRoles);
        RawResponse created = post("/api/kb", form, token).assertOk("建探针库");
        long kbId = ((Number) created.data("$.id")).longValue();
        probeKbIds.add(kbId);
        return kbId;
    }

    /** 登记一个已存在的探针库（由别的入口建的）。 */
    protected long trackKb(long kbId) {
        probeKbIds.add(kbId);
        return kbId;
    }

    /**
     * 上传一篇真文档并等它走完转换 + 解析。
     *
     * <p>⚠️ 内容必须是**真的可解析**的（Markdown / 文本），否则文档会走到 FAILED ——
     * 而 {@code storage_path} 仍然是有的（转存发生在解析之前），下载照样成立。
     */
    protected RawResponse uploadReady(long kbId, String filename, String token) {
        byte[] content = ("# " + filename + "\n\n正文段落，用于解析与下载取证。")
                .getBytes(StandardCharsets.UTF_8);
        return uploadReady(kbId, filename, content, token);
    }

    /**
     * 上传一篇**指定正文**的真文档并等它走完转换 + 解析。
     *
     * <p>⚠️ 存在的理由：默认正文（"正文段落，用于解析与下载取证。"）里**没有任何实体名**，
     * 而 `T-C00` 的图谱用例要的是「实体名真的出现在某一块的正文里」——
     * 否则 {@code RelationAnchor} 锚不上、图里一条边都没有，
     * 用例会以"接线断了"的样子红（而其实只是正文里没那个词）。
     *
     * @param content 文件字节（必须是真可解析的 Markdown / 文本）
     */
    protected RawResponse uploadReady(long kbId, String filename, byte[] content, String token) {
        RawResponse accepted = postMultipart("/api/kb/" + kbId + "/doc/upload", content, filename,
                MediaType.APPLICATION_OCTET_STREAM_VALUE, token);
        if (accepted.code() != 0) {
            return accepted;
        }
        awaitTaskDone(accepted.data("$.taskId"), token);
        parseWorker.drainOnce();
        return accepted;
    }

    /** 轮询泛用任务到终态。 */
    protected void awaitTaskDone(String taskId, String token) {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            RawResponse t = get("/api/doc/tasks/" + taskId, token).assertOk("轮询任务");
            Integer percent = t.data("$.percent");
            if (percent != null && percent >= 100) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError("任务 60s 内未到终态：" + taskId);
    }

    /**
     * 轮询**下载任务**到终态（`DONE` / `FAILED`），返回最后一次响应。
     *
     * <p>为什么不用 {@link #awaitTaskDone}：那条读的是泛用任务投影的 `percent`，
     * 而这里要断言的是**下载状态词表**（`PACKING`/`DONE`/`FAILED`）——
     * 两套词表的映射正是本批要验的东西之一，绕过去测就等于没测。
     */
    protected RawResponse awaitDownloadTerminal(String taskId, String token) {
        long deadline = System.currentTimeMillis() + 60_000;
        RawResponse last = null;
        while (System.currentTimeMillis() < deadline) {
            last = get("/api/doc/download-tasks/" + taskId, token).assertOk("轮询下载任务");
            String status = last.data("$.status");
            if ("DONE".equals(status) || "FAILED".equals(status)) {
                return last;
            }
            sleep(100);
        }
        throw new AssertionError("下载任务 60s 内未到终态：" + taskId
                + "（最后一次：" + (last == null ? "null" : last.body()) + "）");
    }

    /** 与 {@code KbWriteFlowTest#formWith} 同源的合法创建表单（编码用探针码）。 */
    protected static Map<String, Object> formWith(String code) {
        Map<String, Object> form = new HashMap<>();
        form.put("name", "M06-E 下载探针库");
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
                "rerankTopK", 5, "rerankEnabled", true, "tableWeight", 1.2,
                "identifierProtect", true));
        return form;
    }

    /**
     * 等某个任务的通知落库。
     *
     * <p>⚠️ 为什么不能"任务到终态了就直接查通知"：{@code TaskContext#complete} 的顺序是
     * **先置 percent=100 / 落终态 → 再发通知**。轮询一旦读到终态就断言通知，
     * 会偶发地读到"还没有" —— 那是一次**假红**（功能是对的，只是跑得比断言慢）。
     * 这类"等待点选在了中间态"的假红在本仓有过战绩（`#210` 那一族）。
     */
    protected Map<String, Object> awaitNotice(String taskId) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "select type, title, content, actions_json from sys_notice where source_task_id = ?",
                    taskId);
            if (!rows.isEmpty()) {
                return rows.getFirst();
            }
            sleep(100);
        }
        throw new AssertionError("20s 内没等到通知：taskId=" + taskId
                + "（任务完成却不产通知 ⇒ 「完成经通知中心交付」这条链路断了）");
    }

    /** 取一个文档的存储对象名（下载产物断言要用）。 */
    protected String storagePathOf(long docId) {
        return jdbc.queryForObject("select storage_path from sys_document where id = ?",
                String.class, docId);
    }

    /** 短睡（轮询间隔）。与 {@code DocUploadFlowTest#sleep} 同形。 */
    protected static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("轮询被中断", ex);
        }
    }
}
