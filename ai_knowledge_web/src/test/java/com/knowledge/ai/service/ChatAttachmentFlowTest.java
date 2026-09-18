package com.knowledge.ai.service;

import com.knowledge.ai.core.storage.ChatAttachmentStorage;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-805` —— 图片附件的预上传与回看（真 HTTP + 真 MinIO）。
 *
 * <h3>本批的边界</h3>
 * <p>「只落 MinIO + 落库，**不调 VL**」是任务行写死的。所以本类**不验**描述、
 * 不验 `attachment{DESCRIBING}` 事件、不验进度 —— 那些属 `T-806`/`T-512`。
 *
 * <h3>为什么每条判据都要摸到**桶里那个对象**</h3>
 * <p>"上传成功"最容易被证成的假象是：库里有一行、接口返回了 presigned URL，
 * 而桶里什么都没有（或只有 0 字节）。所以判据 1 除了回查 `sys_chat_attachment`，
 * 还要用 `ChatAttachmentStorage#sizeOf` 读**对象实际大小**与上传字节数比对 ——
 * 这与 `T-O04` 的"100MB 文件真的落进桶里了"是同一条纪律。
 *
 * <h3>字节从哪来</h3>
 * <p>用一张真实的 1×1 PNG（base64 内联）：Tika 是靠**文件头**判类型的
 * （`\x89PNG\r\n\x1a\n`），随手编的字节探测不出 `image/png`，
 * 那样"类型校验通过"验的就成了别的东西。
 */
class ChatAttachmentFlowTest extends ApiTestSupport {

    /** 1×1 透明 PNG —— 一个**真的**能被 Tika 按文件头认出来的图片。 */
    private static final byte[] PNG_1X1 = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Autowired
    private ChatAttachmentStorage storage;

    private long floor;

    @BeforeEach
    void markFloor() {
        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from sys_chat_attachment", Long.class);
        floor = max == null ? 0L : max;
    }

    /**
     * 兜底清理：**行 + MinIO 对象都清**。
     *
     * <p>⚠️ 对象这一半是刻意的：`probe-teardown` 的 MinIO 清理只覆盖**库族**
     * （`storage_path` / `parse_path`，要 join `sys_kb`），而问答附件是**按人**归属的 ——
     * 它跟头像一样落在清理器的能力范围之外。本类自己造的，自己收掉。
     */
    @AfterEach
    void removeCreatedAttachments() {
        List<String> keys = jdbc.queryForList(
                "select object_key from sys_chat_attachment where id > ?", String.class, floor);
        for (String key : keys) {
            storage.remove(key);
        }
        jdbc.update("delete from sys_chat_attachment where id > ?", floor);
    }

    // ==================================================================
    // 判据 1 / 3 —— 上传真的落了桶 + 落了库
    // ==================================================================

    @Test
    @DisplayName("判据 1：上传真字节 → 返回字串 id / 落库 / **桶里对象大小与上传字节数一致**")
    void uploadReallyLandsInBucketAndDb() {
        TestUser user = createUser("s5att.ok");

        var res = postMultipart("/api/chat/attachments", PNG_1X1, "报价单截图.png",
                "image/png", user.accessToken()).assertOk("上传图片附件");

        String attachmentId = res.data("$.attachmentId");
        assertThat(attachmentId).as("前端类型是 string，别发数字").isNotBlank();
        assertThat(res.data("$.size").toString()).isEqualTo(String.valueOf(PNG_1X1.length));
        assertThat((String) res.data("$.mime")).isEqualTo("image/png");
        assertThat((String) res.data("$.presignedUrl")).as("前端直接当 <img src> 用").isNotBlank();

        Map<String, Object> row = jdbc.queryForMap(
                "select user_id, object_key, name, size, mime, md5 from sys_chat_attachment where id = ?",
                Long.valueOf(attachmentId));
        assertThat(((Number) row.get("user_id")).longValue())
                .as("预上传时会话还不存在，归属只能记在人上").isEqualTo(userIdOf(user.username()));
        assertThat(row.get("name")).as("原文件名只用于展示").isEqualTo("报价单截图.png");
        assertThat(((Number) row.get("size")).longValue()).isEqualTo(PNG_1X1.length);

        String objectKey = (String) row.get("object_key");
        assertThat(objectKey).as("对象名前缀决定生命周期规则能否单独作用于问答附件")
                .startsWith("chat-attachments/");
        assertThat(storage.sizeOf(objectKey))
                .as("**桶里真的有这么多字节** —— 只看接口返回 200 证明不了这件事")
                .isEqualTo(PNG_1X1.length);
    }

    @Test
    @DisplayName("判据 1：md5 是**服务端按字节算的**（它将来是 VL 描述缓存的 key 前半段）")
    void md5IsComputedFromBytes() throws Exception {
        TestUser user = createUser("s5att.md5");

        String returned = postMultipart("/api/chat/attachments", PNG_1X1, "a.png",
                "image/png", user.accessToken()).assertOk("上传").data("$.md5");

        assertThat(returned).isEqualTo(HexFormat.of()
                .formatHex(MessageDigest.getInstance("MD5").digest(PNG_1X1)));

        // 换个字节 → 换个 md5。伪 md5（如"文件名+大小"的哈希）在这里会露馅：
        // 两张不同的图会算出同一个 key，第二张图拿到第一张的描述
        byte[] other = PNG_1X1.clone();
        other[other.length - 1] ^= 0x01;
        String otherMd5 = postMultipart("/api/chat/attachments", other, "a.png",
                "image/png", user.accessToken()).assertOk("上传另一张").data("$.md5");
        assertThat(otherMd5).as("内容不同 ⇒ md5 必须不同").isNotEqualTo(returned);
    }

    // ==================================================================
    // 判据 2 —— 回看与归属
    // ==================================================================

    @Test
    @DisplayName("判据 2：回看本人的附件 → 200；他人的 / 不存在的 → **同码** NOT_FOUND")
    void getChecksOwnership() {
        TestUser owner = createUser("s5att.owner");
        TestUser other = createUser("s5att.other");
        String id = postMultipart("/api/chat/attachments", PNG_1X1, "mine.png",
                "image/png", owner.accessToken()).assertOk("上传").data("$.attachmentId");

        var mine = get("/api/chat/attachments/" + id, owner.accessToken()).assertOk("回看本人的附件");
        assertThat((String) mine.data("$.id")).isEqualTo(id);
        assertThat((String) mine.data("$.type")).isEqualTo("IMAGE");
        assertThat((String) mine.data("$.name")).isEqualTo("mine.png");
        assertThat((String) mine.data("$.url")).as("每次读取现签，库里存的是对象名").isNotBlank();
        // 描述五件套属 T-512 —— 本批如实为 null，**不填假值**
        assertThat((Object) mine.data("$.desc"))
                .as("填 \"\" 会被前端当成「模型确实没看出什么」").isNull();

        int notMine = get("/api/chat/attachments/" + id, other.accessToken()).code();
        int missing = get("/api/chat/attachments/999999999", other.accessToken()).code();
        assertThat(notMine).as("不是你的 → NOT_FOUND").isEqualTo(10040);
        assertThat(missing).as("区分开就泄露了「这个 id 存在，只是不归你」").isEqualTo(10040);
    }

    // ==================================================================
    // 判据 3 —— 超限与类型：**给现象 + 下一步动作**（G4-3）
    // ==================================================================

    @Test
    @DisplayName("判据 3：超过 10MB → FILE_TOO_LARGE，文案带当前大小与下一步动作")
    void oversizedImageIsRejected() {
        TestUser user = createUser("s5att.big");
        byte[] tooBig = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy(PNG_1X1, 0, tooBig, 0, PNG_1X1.length);

        var res = postMultipart("/api/chat/attachments", tooBig, "big.png",
                "image/png", user.accessToken());

        assertThat(res.code()).isEqualTo(10035);
        assertThat(res.message()).as("G4-3：不许只说「失败」两字")
                .contains("10 MB").contains("压缩");
        assertThat(countRows()).as("被拒的上传不得留下行").isZero();
    }

    @Test
    @DisplayName("判据 3：声明 image/png 但内容是文本 → 交叉校验拦下（T-O04 的同一条纪律）")
    void declaredImageWithNonImageContentIsRejected() {
        TestUser user = createUser("s5att.fake");

        var res = postMultipart("/api/chat/attachments", "这不是图片".getBytes(),
                "fake.png", "image/png", user.accessToken());

        assertThat(res.code()).isEqualTo(10034);
        assertThat(res.message()).as("要说清是「内容不像图片」，而不是含混的「类型不支持」")
                .contains("文件头").contains("内容");
        assertThat(countRows()).isZero();
    }

    @Test
    @DisplayName("判据 3：声明非图片类型 → 直接拒（不必白探一次文件头）")
    void nonImageDeclaredTypeIsRejected() {
        TestUser user = createUser("s5att.type");

        var res = postMultipart("/api/chat/attachments", PNG_1X1, "a.png",
                "text/plain", user.accessToken());

        assertThat(res.code()).isEqualTo(10034);
        assertThat(res.message()).contains("仅支持图片");
        assertThat(countRows()).isZero();
    }

    @Test
    @DisplayName("判据 3：SVG 被拒 —— 它是可执行 XML，而附件将来会被 VL 服务取回去解析")
    void svgIsRejected() {
        TestUser user = createUser("s5att.svg");
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes();

        var res = postMultipart("/api/chat/attachments", svg, "x.svg", "image/svg+xml", user.accessToken());

        assertThat(res.code()).isEqualTo(10034);
        assertThat(countRows()).isZero();
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 本次用例新增的附件行数（按水位线数）。 */
    private int countRows() {
        Integer n = jdbc.queryForObject(
                "select count(*) from sys_chat_attachment where id > ?", Integer.class, floor);
        return n == null ? 0 : n;
    }

    private long userIdOf(String username) {
        Long id = jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
        assertThat(id).as("账号 %s 应该存在", username).isNotNull();
        return id;
    }
}
