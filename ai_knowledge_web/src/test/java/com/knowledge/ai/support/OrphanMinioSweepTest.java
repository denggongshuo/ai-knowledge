package com.knowledge.ai.support;

import com.knowledge.ai.core.async.AsyncTaskInfo;
import com.knowledge.ai.core.async.AsyncTaskService;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-O10` · 探针产物兜底：**无主 MinIO 对象**能不能被扫出来（`S6-M05-C` 的 `#190`）。
 *
 * <h3>它为什么必须存在</h3>
 * <p>SQL 侧早就有了 `sweepOrphanKbArtifacts`（"不问'你清了没有'，只问'库里还剩不剩无主的东西'"），
 * 而**对象那一侧从来没有对等物** —— 只有"每个用例类各自删自己造的那些"。
 * 那条约定**已被证明会漏**（`#130`），代价在对象侧也是实测过的（桶里攒到 1030 个 ⇒
 * `storage-quota` 全桶递归 ⇒ 读超时 ⇒ 把毫不相干的用例拖红）。
 *
 * <h3>本类用的是**差分**，不是"改成预期值"</h3>
 * <p>判据 1/2 在同一轮里造**两个同龄、同前缀**的对象 —— 一个有行、一个没有 ——
 * 然后断言**一个活一个死**。这比"把行删掉看它红不红"强：它证明的是
 * **"有没有那一行"正是区分两者的那个东西**（同年龄、同前缀把其它变量排干净了）。
 *
 * <p>判据 4 同样是差分，只是变量换成**时间下限**：同一个对象，
 * 用默认下限扫一遍（活着）再用 {@code Duration.ZERO} 扫一遍（死了）。
 * ⇒ **时间下限是承重的**，不是一个摆设参数。
 */
class OrphanMinioSweepTest extends ApiTestSupport {

    /** 假库 id —— 用它造的行会被 `sweepOrphanKbArtifacts` 自动收掉（`kb_id not in sys_kb`），自洁。 */
    private static final long FAKE_KB = 999_000_001L;
    private static final long FAKE_DOC = 999_000_002L;

    /** 本用例造的对象 —— **自己删**（兜底是兜底，不该被用例当成清理手段）。 */
    private final List<String> mine = new ArrayList<>();

    @AfterEach
    void removeMine() {
        for (String key : mine) {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder().bucket(minioBucket).object(key).build());
            } catch (Exception ignored) {
                // 用例的清理失败不该掩盖断言结果
            }
        }
        mine.clear();
        jdbc.update("delete from sys_document where id = ?", FAKE_DOC);
        jdbc.update("delete from sys_chat_attachment where object_key like ?", "chat-attachments/999000003/%");
    }

    @Test
    @DisplayName("T-O10 判据 1/2/3：有行的活、没行的死 —— **同一轮、同龄、同前缀**的差分")
    void referencedObjectSurvivesAndOrphanDies() throws Exception {
        // `documents/` 与 `parsed/` 两个前缀挂同一张 sys_document 行的两列
        String keptDoc = put("documents/" + FAKE_KB + "/" + FAKE_DOC + "/kept.pdf");
        String keptArtifact = put("parsed/" + FAKE_KB + "/" + FAKE_DOC + "/kept.json");
        String orphanDoc = put("documents/" + FAKE_KB + "/" + FAKE_DOC + "/orphan.pdf");
        String orphanArtifact = put("parsed/" + FAKE_KB + "/" + FAKE_DOC + "/orphan.json");
        insertDoc(keptDoc, keptArtifact);

        int removed = ProbeCleanup.sweepOrphanMinioObjects(minioClient, minioBucket, jdbc, Duration.ZERO);

        assertThat(exists(orphanDoc)).as("没有行指向它 ⇒ 必须被删掉").isFalse();
        assertThat(exists(orphanArtifact)).as("同上的产物侧").isFalse();
        assertThat(exists(keptDoc))
                .as("**有行指向它 ⇒ 一根毫毛都不能碰**。它与上面那两个同龄、同前缀 —— "
                        + "差异只有一个：库里有没有那一行。所以这条同时证明了"
                        + "「有没有那一行」正是区分两者的那个东西")
                .isTrue();
        assertThat(exists(keptArtifact)).as("同上").isTrue();
        assertThat(removed).as("删掉 2 个（**计数 > 0 是信号**，不是正常现象）").isEqualTo(2);
    }

    @Test
    @DisplayName("T-O10 判据 4：**时间下限是承重的** —— 默认下限放过「刚上传、行还没写」的对象")
    void freshOrphanSurvivesDefaultMinAge() throws Exception {
        // 模拟"正在上传"：对象刚落到桶里，`repository.save(row)` 还没执行
        String inFlight = put("documents/" + FAKE_KB + "/" + FAKE_DOC + "/inflight.pdf");

        ProbeCleanup.sweepOrphanMinioObjects(minioClient, minioBucket, jdbc, ProbeCleanup.DEFAULT_MIN_AGE);
        assertThat(exists(inFlight))
                .as("**默认下限必须放过它** —— 上传路径是「先落桶、后落行」，"
                        + "所以「对象在、行不在」在上传进行中是**正常状态**。"
                        + "删了它就是**用防残留的机制制造数据丢失**")
                .isTrue();

        // 同一轮里换一个下限再扫 —— **差分**：活/死的唯一变量是时间下限
        ProbeCleanup.sweepOrphanMinioObjects(minioClient, minioBucket, jdbc, Duration.ZERO);
        assertThat(exists(inFlight))
                .as("下限一撤它就该死 ⇒ 证明**时间下限正是那个承重的参数**，不是摆设")
                .isFalse();
    }

    @Test
    @DisplayName("T-O10 判据 6：四个前缀**全都在覆盖范围内**（含 avatars/，它此前没人删）")
    void allFourPrefixesAreCovered() throws Exception {
        // 用基座的 `createUser`（它自建自删，收尾挂在 `createdUsers` 上）
        TestUser probe = createUser("ominio.sweep");
        Long userId = jdbc.queryForObject(
                "select id from sys_user where username = ?", Long.class, probe.username());
        assertThat(userId).as("探针账号必须真的建出来了，否则本判据测的是空气").isNotNull();

        String avatarKept = put("avatars/" + userId + "/kept.png");
        String avatarOrphan = put("avatars/" + userId + "/orphan.png");
        String chatKept = put("chat-attachments/" + userId + "/kept.png");
        String chatOrphan = put("chat-attachments/" + userId + "/orphan.png");

        jdbc.update("update sys_user set avatar = ? where id = ?", avatarKept, userId);
        jdbc.update("insert into sys_chat_attachment (user_id, object_key, name, size, mime, md5, created_at) "
                + "values (?, ?, 'kept.png', 4, 'image/png', 'x', ?)", userId, chatKept, LocalDateTime.now());

        ProbeCleanup.sweepOrphanMinioObjects(minioClient, minioBucket, jdbc, Duration.ZERO);

        assertThat(exists(avatarOrphan)).as("`avatars/` 的孤儿 —— 这个前缀此前**没人删**（#78）").isFalse();
        assertThat(exists(chatOrphan)).as("`chat-attachments/` 的孤儿").isFalse();
        assertThat(exists(avatarKept)).as("有主，不能碰").isTrue();
        assertThat(exists(chatKept)).as("有主，不能碰").isTrue();

        jdbc.update("update sys_user set avatar = null where id = ?", userId);
    }

    // ==================================================================
    // T-O11 —— `audit-export/`：**唯一一个归属不在列里的前缀**
    // ==================================================================

    @Test
    @DisplayName("T-O11 判据 1/2：`audit-export/` 的归属**在对象名里** —— 账号在就留、账号硬删了才删")
    void auditExportOwnerComesFromTheObjectPath() throws Exception {
        // ① 探针账号 —— 它收尾会被 `cleanUsers` **硬删**，正属"归属真的会消失"那一类
        TestUser probe = createUser("o11.export");
        Long probeId = jdbc.queryForObject(
                "select id from sys_user where username = ?", Long.class, probe.username());
        assertThat(probeId).as("探针账号必须真的建出来了，否则本判据测的是空气").isNotNull();

        // ② 种子超管 —— 他的行**永远在**（`AuditQueryFlowTest` 的真导出人就是他）
        Long adminId = jdbc.queryForObject(
                "select id from sys_user where username = ?", Long.class, ADMIN);
        assertThat(adminId).as("种子超管必须在 —— 这是负向锚的另一半").isNotNull();

        String probeOrphan = put(ProbeCleanup.AUDIT_EXPORT_PREFIX + probeId + "/20260919T120000-audit.csv");
        String adminKept = put(ProbeCleanup.AUDIT_EXPORT_PREFIX + adminId + "/20260919T120000-audit.csv");

        // ③ **差分**：唯一变量是"那个账号的行还在不在"
        deleteUser(probe.username());
        assertThat(jdbc.queryForObject(
                "select count(*) from sys_user where id = ?", Integer.class, probeId))
                .as("探针账号必须真被硬删了 —— 否则这条差分退化成'两个都有主'，测不出东西")
                .isZero();

        ProbeCleanup.sweepOrphanMinioObjects(minioClient, minioBucket, jdbc, Duration.ZERO);

        assertThat(exists(probeOrphan))
                .as("账号行没了 ⇒ 无主 ⇒ 必须删。⚠️ 它没有归属**列**，"
                        + "`documents/` 那套「查 sys_document.storage_path」的问法对它一个字都用不上 —— "
                        + "判的是**对象名第 2 段**")
                .isFalse();
        assertThat(exists(adminKept))
                .as("🔴 **负向锚（本判据的重点）**：种子超管还在 ⇒ 他的导出一根毫毛都不能碰。"
                        + "两个对象同龄、同前缀、同结构 —— 差异只有一个：账号行还在不在。"
                        + "删错一个就是**删掉真实用户的审计导出**")
                .isTrue();
    }

    @Test
    @DisplayName("T-O11 判据 5：**对象名 ↔ 解析器对得上** —— 真导出产出的 key 必须解析得出导出人")
    void realExportKeyIsResolvableToItsOwner() throws Exception {
        String token = loginAsAdmin().data("$.accessToken");
        Long adminId = jdbc.queryForObject(
                "select id from sys_user where username = ?", Long.class, ADMIN);
        String today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        RawResponse accepted = post("/api/audit/export",
                java.util.Map.of("startTime", today, "endTime", today), token).assertOk("提交导出");
        String taskId = accepted.data("$.taskId");
        assertThat(taskId).isNotBlank();

        // 等任务**真跑完** —— 只断言"接口返回了 taskId"是不够的：任务体里查库 / 生成 CSV /
        // 写 MinIO 任一步挂了，接口照样返回 taskId（`AuditQueryFlowTest` 判据 11/12 的原话）
        AsyncTaskInfo<?> info = awaitTerminal(taskId);
        String objectKey = String.valueOf(((java.util.Map<?, ?>) info.getResult()).get("objectKey"));
        mine.add(objectKey);        // 自己删（兜底是兜底，不该被用例当成清理手段）
        jdbc.update("delete from sys_async_task where task_id = ?", taskId);

        assertThat(ProbeCleanup.pathOwnerId(ProbeCleanup.AUDIT_EXPORT_PREFIX, objectKey))
                .as("🔴 **这是防「路径约定漂移」的机器检查，不是重复断言**："
                        + "`AuditExportService#store` 一旦改了对象名格式，兜底会**静默失效** —— "
                        + "它扫不到任何'有主'的对象，于是把**真实导出当无主删掉**。"
                        + "所以必须拿**真产出的 key** 去喂解析器；拿一个手拼的样例喂它只能证明"
                        + "'我拼的那个能被解析'，证明不了生产写的那个能被解析")
                .isEqualTo(adminId);
    }

    /**
     * **冻结清单**：系统目前会往桶里写这些前缀。
     *
     * <p>新增一个就改这里 —— 强制"**过一次人的判断**"：进 `ProbeCleanup` 的覆盖集（并写明归属列/归属来源），
     * 还是登记成豁免（并写明理由）。手法与 `AuditCoverageFlowTest#expectedActions()` 的 25 条一致：
     * **逐条写死，防的是"扫描式断言对漏挂是盲的"**。
     */
    private static final java.util.Set<String> FROZEN_MINIO_PREFIXES = java.util.Set.of(
            "documents/", "parsed/", "chat-attachments/", "avatars/", "images/", "audit-export/",
            // `T-A02` 加了第七个 —— 它**刻意不复用** `audit-export/`（理由见 `ProbeCleanup` 的
            // `OPS_EXPORT_PREFIX`）。这一次是**先加清单再加服务**，与 `images/` 那次相反。
            "ops-export/");

    @Test
    @DisplayName("T-O11 判据 6：七个前缀**逐个表态** —— 覆盖集必须与冻结清单完全相等")
    void everyKnownPrefixIsClassified() {
        assertThat(ProbeCleanup.coveredMinioPrefixes())
                .as("🔴 本仓「新增持久化产物必须同步登记」这条约定**只靠 javadoc 已经被抓过两次**："
                        + "加 `images/` 时漏登记 ⇒ 一轮跑批在桶里留 180 个孤儿；"
                        + "`audit-export/` 更久（从 `O10` 到 `T-O11` 一直不在清单里，实测攒到 137 个）。"
                        + "冻结清单 + 这条断言就是那个**机器检查** —— 新前缀必须先过一次人的判断，"
                        + "而不是靠下一个人恰好想起来还有这么一类")
                .containsExactlyInAnyOrderElementsOf(FROZEN_MINIO_PREFIXES);
    }

    /** 等异步任务到终态（超时**判红**，不静默返回）。 */
    private AsyncTaskInfo<?> awaitTerminal(String taskId) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            AsyncTaskInfo<?> info = asyncTaskService.find(taskId).orElse(null);
            if (info != null && info.getStatus().terminal()) {
                return info;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("导出任务未在 20s 内到达终态：" + taskId);
    }

    @Autowired
    private AsyncTaskService asyncTaskService;

    // ==================================================================
    // 工具
    // ==================================================================

    /** 造一个对象（内容随便），并登记进 {@link #mine} 以便自查自删。 */
    private String put(String key) throws Exception {
        byte[] body = ("probe:" + key).getBytes(StandardCharsets.UTF_8);
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(minioBucket).object(key)
                .stream(new ByteArrayInputStream(body), body.length, -1)
                .contentType("application/octet-stream").build());
        mine.add(key);
        return key;
    }

    /** 对象还在吗（**独立观测点**：直接问 MinIO，不看删除命令的返回值）。 */
    private boolean exists(String key) {
        try {
            minioClient.statObject(io.minio.StatObjectArgs.builder()
                    .bucket(minioBucket).object(key).build());
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /** 造一条 `sys_document` 行，两个对象列都指向真对象。 */
    private void insertDoc(String storagePath, String parsePath) {
        jdbc.update("insert into sys_document (id, kb_id, title, type, file_name, md5, owner_id, "
                        + "storage_path, parse_path, status, created_at, updated_at) "
                        + "values (?, ?, 'T-O10 探针文档', 'pdf', 'p.pdf', ?, 1, ?, ?, 'UPLOADED', ?, ?)",
                FAKE_DOC, FAKE_KB, "0".repeat(32), storagePath, parsePath,
                LocalDateTime.now(), LocalDateTime.now());
    }
}
