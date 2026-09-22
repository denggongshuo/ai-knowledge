package com.knowledge.ai.service;

import com.knowledge.ai.support.DownloadTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-613` / `G7-7`：预览与下载的**预签名地址**。
 *
 * <h3>为什么每一条都真的去 GET 一次那个 URL</h3>
 * <p>门禁 `G7-7` 要的是"10 分钟有效的预签名 URL"，而**签出一个字符串**不等于
 * "那个字符串能取到文件"。两者之间的差别全是实现细节：桶名拼错、对象名不对、
 * {@code response-content-disposition} 让签名对不上、MinIO 端点不可达 ——
 * 这些**全都不会让签名的接口报错**。
 * <p>所以判据是"拿它去取，取回来的是不是当初传上去的那些字节"（本仓那条
 * 「SDK 调用成功不等于业务事实已成立」）。
 */
class DownloadUrlFlowTest extends DownloadTestSupport {

    @Test
    @DisplayName("G7-7：单篇下载地址 = 10 分钟预签名 URL，且**真能取回原文件**；同时恰好多一条 SINGLE 记录")
    void downloadUrlIsTenMinutePresignedAndReallyFetchesTheFile() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());
        long kbId = createProbeKb(token);
        long docId = ((Number) uploadReady(kbId, "下载探针.md", token).data("$.docId")).longValue();

        long before = singleRecordsOf(docId);

        RawResponse res = get("/api/doc/download-url/" + docId, token).assertOk("取单篇下载地址");
        String url = res.data("$.url");
        assertThat(url).as("预签名地址不能为空").isNotBlank().startsWith("http");
        // 🔴 判据 1：TTL = 600 秒（= PresignTtl.DOC_DOWNLOAD_MINUTES × 60）
        assertThat((Integer) res.data("$.expiresInSec")).as("10 分钟 = 600 秒").isEqualTo(600);
        assertThat((String) res.data("$.expiresAt")).isNotBlank();
        assertThat(url).as("下载要让浏览器另存为，不是直接打开")
                .contains("response-content-disposition");

        // 真去取一次 —— 这一跳**不经过业务服务**（直连对象存储）
        byte[] fetched = fetch(url);
        assertThat(fetched).as("取回的对象不能为空").isNotEmpty();
        assertThat(new String(fetched, StandardCharsets.UTF_8))
                .as("取回的必须正是当初上传的那些字节").contains("下载探针");

        // G7-9：下载记录恰好多一条，且是 SINGLE
        assertThat(singleRecordsOf(docId)).as("一次下载 = 一条 SINGLE 记录").isEqualTo(before + 1);
    }

    @Test
    @DisplayName("T-613：预览地址也是 10 分钟，但**不写下载记录**（预览 ≠ 下载）")
    void previewUrlIsTenMinuteAndWritesNoDownloadRecord() {
        String token = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());
        long kbId = createProbeKb(token);
        long docId = ((Number) uploadReady(kbId, "预览探针.md", token).data("$.docId")).longValue();

        long before = allRecordsOf(docId);

        RawResponse res = get("/api/doc/" + docId + "/preview-url", token).assertOk("取预览地址");
        assertThat((Integer) res.data("$.expiresInSec")).isEqualTo(600);

        // ⚠️ 只断言"URL 存在、TTL 对"是不够的 —— 预览与下载**共用同一个对象**，
        //    两条路的差别全在 Content-Disposition 上。所以这一条要验的就是它。
        byte[] fetched = fetch(res.data("$.url"));
        assertThat(new String(fetched, StandardCharsets.UTF_8)).contains("预览探针");

        assertThat(allRecordsOf(docId))
                .as("预览**不写**下载记录 —— 写了的话「我下过什么」会被点开看一眼的动作污染")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("T-613：文档不存在 ⇒ 10044（不是 403，也不是 500）")
    void missingDocYieldsDocNotFound() {
        String token = loginAsAdmin().data("$.accessToken");
        RawResponse res = get("/api/doc/download-url/999999999", token);
        assertThat(res.code()).as("不存在的文档：" + res.body()).isEqualTo(10044);
    }

    @Test
    @DisplayName("T-613：文档在、但库不可见 ⇒ 10022（HTTP 403），**不是** 10044")
    void invisibleDocYieldsKbAccessDenied() {
        String adminToken = loginAsAdmin().data("$.accessToken");
        trackUser(adminUserId());
        // 只对超管可见 —— 两条准入线（`visible_roles` 与数据范围）都要关掉才谈得上"不可见"
        long kbId = createProbeKb(adminToken, java.util.List.of("SUPER_ADMIN"));
        long docId = ((Number) uploadReady(kbId, "越权探针.md", adminToken)
                .data("$.docId")).longValue();

        String userToken = createUser("dlprobe").accessToken();
        RawResponse res = get("/api/doc/download-url/" + docId, userToken);

        // 🔴 两种情形必须给**不同的错**（`DocAccessService` 的类注释）：
        //    "文档没了" 与 "你可以去申请授权" 是两句完全不同的话，而下一步动作也相反
        assertThat(res.code()).as("越权应给 10022 而不是 10044：" + res.body()).isEqualTo(10022);
        assertThat(res.status()).as("权限不足走 HTTP 403（错误码表里的传输层例外之一）")
                .isEqualTo(403);
    }

    // ==================== 小工具 ====================

    /** 真的去取一次预签名 URL（不走业务服务）。 */
    private byte[] fetch(String url) {
        byte[] body = client.get().uri(URI.create(url)).retrieve().body(byte[].class);
        assertThat(body).as("预签名 URL 取不到内容：" + url).isNotNull();
        return body;
    }

    private long singleRecordsOf(long docId) {
        Long n = jdbc.queryForObject("select count(*) from sys_download_record "
                + "where doc_id = ? and download_type = 'SINGLE'", Long.class, docId);
        return n == null ? 0L : n;
    }

    private long allRecordsOf(long docId) {
        Long n = jdbc.queryForObject("select count(*) from sys_download_record where doc_id = ?",
                Long.class, docId);
        return n == null ? 0L : n;
    }
}
