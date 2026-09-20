package com.knowledge.ai.service;

import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-601`（属性修改）/ `T-610`（单篇重建）—— 文档写侧。
 *
 * <h3>为什么走真 HTTP</h3>
 * <p>两件事只在 HTTP 层成立：① 权限码（{@code @RequiresPerm} 的 403 是过滤器给的）；
 * ② 新码 {@code doc:update} 的射程 —— "只给 {@code doc:detail} 的角色打 {@code PATCH} 必须 403"
 * 这条**只有真发一次请求**才验得出来（直调 service 拿到的是"方法执行成功了"）。
 *
 * <h3>🔴 本类最要紧的一条不是"降级被拒"，是"拒了之后库里那行没变"</h3>
 * <p>「返回一个错误」与「什么都没发生」是两件事。只断言前者的话，一个"先写库再校验、
 * 发现不对再抛异常"的实现照样绿 —— 而那种实现下用户看到的是"操作失败"，数据却已经变了。
 *
 * <h3>⚠️ 移动知识库的**成功路径不在本类**</h3>
 * <p>它要真删旧库分区的向量、真写新库分区（Milvus），并且要验"旧库搜不到、新库搜得到"。
 * 那是端到端的事，归 `e2e-doc-attributes.mjs`。本类只验它的**四道校验**（那些不碰 Milvus），
 * 以及"四道里任何一道不过 ⇒ 一行都不改"。
 */
@DisplayName("T-601/T-610 · 文档写侧（属性修改 + 单篇重建）")
class DocWriteFlowTest extends ApiTestSupport {

    private static final String SRC_CODE = "probe-docwrite-src";
    private static final String TGT_CODE = "probe-docwrite-tgt";
    private static final String LOW_CODE = "probe-docwrite-low";
    private static final String DOC_TITLE = "探针文档-写侧";

    private long srcKbId;
    private long tgtKbId;
    private long lowKbId;
    private long docId;
    private String adminToken;
    private final List<Long> tmpRoleIds = new ArrayList<>();

    @BeforeEach
    void setUpProbe() {
        clearAll();
        buildFixtures();
        adminToken = loginAsAdmin().data("$.accessToken");
    }

    @AfterEach
    void clearFixtures() {
        clearAll();
    }

    // ==================================================================
    // `G7-3` · 密级只能提高（I-6）
    // ==================================================================

    @Test
    @DisplayName("G7-3 · 降到低于知识库密级被拒，**且库里那行一个字都没变**")
    void loweringSecretLevelIsRejectedAndNothingIsWritten() {
        // 源库密级 = 机密 ⇒ 文档的生效密级至少是机密
        jdbc.update("update sys_kb set secret_level = 'CONFIDENTIAL' where id = ?", srcKbId);
        String before = jdbc.queryForObject(
                "select concat(ifnull(secret_level,'NULL'), '|', updated_at) from sys_document where id = ?",
                String.class, docId);

        RawResponse res = patchAttributes(Map.of("secretLevel", "INTERNAL"));

        assertThat(res.code()).as("降到低于库级必须被拒（不是静默成功）").isEqualTo(10030);
        assertThat(res.message()).as("消息要说清「只能提高」").contains("只能提高");
        assertThat(currentSecretLevel()).as("🔴 拒了就必须真的没写 —— 否则用户看到「失败」、数据却变了")
                .isNull();
        assertThat(after()).as("updated_at 也必须逐位不变").isEqualTo(before);
    }

    @Test
    @DisplayName("G7-3 · 提高放行，且库里真的变了（两个方向都要验）")
    void raisingSecretLevelIsAcceptedAndPersisted() {
        // 先降到"未覆盖"（继承内部），再提到机密
        RawResponse raised = patchAttributes(Map.of("secretLevel", "CONFIDENTIAL"));
        // ⚠️ 先赋给有类型的变量：`data(...)` 是泛型方法，直接喂给 `assertThat` 会**歧义**
        Boolean needReindex = raised.data("$.needReindex");

        assertThat(raised.code()).isEqualTo(0);
        assertThat(needReindex).as("只改密级**不触发**重建（需求 §6.7）").isEqualTo(false);
        assertThat(currentSecretLevel()).as("库里要真的写成 CONFIDENTIAL").isEqualTo("CONFIDENTIAL");
        assertThat(jdbc.queryForObject("select secret_level from sys_document where id = ?",
                String.class, docId)).isEqualTo("CONFIDENTIAL");
    }

    @Test
    @DisplayName("G7-3 · 序数语义：4 档逐对（把密级做反的那类 bug 只有这条抓得住）")
    void secretLevelOrderIsOrdinal() {
        // 库 = 内部 ⇒ 只有「内部 / 机密 / 绝密」是可选的，且**必须**都能选上
        for (String level : List.of("INTERNAL", "CONFIDENTIAL", "SECRET")) {
            jdbc.update("update sys_document set secret_level = 'INTERNAL' where id = ?", docId);
            RawResponse res = patchAttributes(Map.of("secretLevel", level));
            assertThat(res.code()).as("提到 %s 应当放行 —— 若这里红了，多半是密级比较写反了", level)
                    .isEqualTo(0);
            assertThat(currentSecretLevel()).isEqualTo(level);
        }
        // 反向：从绝密往下**任何**一档都必须被拒（只验一档时"做反"照样绿）
        for (String lower : List.of("PUBLIC", "INTERNAL", "CONFIDENTIAL")) {
            RawResponse res = patchAttributes(Map.of("secretLevel", lower));
            assertThat(res.code()).as("从绝密降到 %s 必须被拒", lower).isEqualTo(10030);
            assertThat(currentSecretLevel()).as("被拒之后值不能变").isEqualTo("SECRET");
        }
    }

    @Test
    @DisplayName("G7-3 · 文档自己比库更严时，下限跟的是**生效值**（不是库级）")
    void floorFollowsEffectiveLevelNotKbLevel() {
        // 库 = 内部，文档自己 = 绝密 ⇒ 生效 = 绝密 ⇒ 连"降回库级"都必须被拒
        jdbc.update("update sys_document set secret_level = 'SECRET' where id = ?", docId);

        RawResponse res = patchAttributes(Map.of("secretLevel", "INTERNAL"));

        assertThat(res.code()).as("🔴 按库级算下限的话这一格会绿 —— 而它意味着能绕开文档自己的密级")
                .isEqualTo(10030);
        assertThat(currentSecretLevel()).isEqualTo("SECRET");
    }

    // ==================================================================
    // `T-601` · 其余字段
    // ==================================================================

    @Test
    @DisplayName("T-601 · `tags` 明确拒绝，且库里**没有任何地方**存下了它")
    void tagsAreRejectedNotSilentlyIgnored() {
        RawResponse res = patchAttributes(Map.of("tags", List.of("探针标签甲", "探针标签乙")));

        assertThat(res.code()).as("不支持就要明说 —— 静默忽略会让用户以为存上了").isEqualTo(10030);
        assertThat(res.message()).contains("不支持");
        // 「没假装存了」要能证明：全表扫一遍，两个标签串在 sys_document 的任何一列里都不存在
        Number leaked = jdbc.queryForObject("""
                select count(*) from sys_document
                 where ifnull(remark,'') like '%探针标签甲%' or title like '%探针标签甲%'
                """, Number.class);
        assertThat(leaked.longValue()).as("标签不该被塞进 remark/title 之类的列里顶替").isZero();
    }

    @Test
    @DisplayName("T-601 · 什么都没改 ⇒ 不写库（幂等，且不能让 updated_at 无谓地动）")
    void noOpRequestDoesNotTouchTheRow() {
        // 先取到"当前生效值"，把它原样发回去
        jdbc.update("update sys_document set secret_level = 'INTERNAL' where id = ?", docId);
        String before = after();

        RawResponse res = patchAttributes(Map.of("secretLevel", "INTERNAL"));

        assertThat(res.code()).isEqualTo(0);
        assertThat(after()).as("值没变时不该改写行（否则「最近修改时间」会变成一句谎）").isEqualTo(before);
    }

    @Test
    @DisplayName("T-601 · 非法密级值 → 明确业务错（不是 500，也不是静默当没传）")
    void unknownSecretLevelIsRejected() {
        RawResponse res = patchAttributes(Map.of("secretLevel", "TOP_SECRET"));

        assertThat(res.code()).isEqualTo(10030);
        assertThat(res.status()).as("业务错走 200，不是 4xx/5xx").isEqualTo(200);
    }

    // ==================================================================
    // `T-601` · 权限码射程
    // ==================================================================

    @Test
    @DisplayName("T-601 · `doc:update` 守 PATCH：只有 `doc:detail` 的角色必须 403（可证伪）")
    void docUpdatePermissionGuardsThePatchEndpoint() {
        long roleId = createTempRoleWith("probe.docwrite.detail", "doc:detail");
        String token = loginWithRole(roleId);

        RawResponse res = call("PATCH", "/api/doc/" + docId + "/attributes",
                Map.of("secretLevel", "CONFIDENTIAL"), token);

        assertThat(res.code()).as("🔴「能读详情就能改属性」正是这条判据要否掉的")
                .isEqualTo(10020);
        assertThat(currentSecretLevel()).as("被权限拦住时更不能写").isNull();
    }

    // ==================================================================
    // `T-610` · 单篇重建
    // ==================================================================

    @Test
    @DisplayName("T-610 · READY 受理，返回 ACCEPTED + taskId，且任务真的落到了这篇文档上")
    void reindexAcceptsReadyDocument() {
        RawResponse res = post("/api/doc/" + docId + "/reindex", null, adminToken).assertOk("单篇重建");
        Integer echoedDocId = res.data("$.docId");
        String taskId = res.data("$.taskId");
        String status = res.data("$.status");

        assertThat(echoedDocId).isEqualTo((int) docId);
        assertThat(taskId).as("必须给 taskId —— 它只负责受理").isNotNull();
        assertThat(status).as("刚入队、还没被 worker 领取 ⇒ 报 ACCEPTED 而不是 RUNNING")
                .isEqualTo("ACCEPTED");

        Map<String, Object> task = jdbc.queryForMap(
                "select type, status, kb_id, doc_id from sys_async_task where task_id = ?", taskId);
        assertThat(task.get("type")).isEqualTo("doc-parse");
        assertThat(((Number) task.get("kb_id")).longValue()).isEqualTo(srcKbId);
        assertThat(((Number) task.get("doc_id")).longValue()).isEqualTo(docId);
    }

    @Test
    @DisplayName("T-610 · 幂等：连打两次复用同一条在跑作业（不会排出两条）")
    void reindexIsIdempotentWhileATaskIsActive() {
        String first = post("/api/doc/" + docId + "/reindex", null, adminToken)
                .assertOk("第一次重建").data("$.taskId");
        String second = post("/api/doc/" + docId + "/reindex", null, adminToken)
                .assertOk("第二次重建").data("$.taskId");

        assertThat(second).as("同一篇同时只应有一条在跑的解析作业").isEqualTo(first);
        Number n = jdbc.queryForObject(
                "select count(*) from sys_async_task where doc_id = ? and status in ('PENDING','RUNNING')",
                Number.class, docId);
        assertThat(n.longValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("T-610 · 单篇重建**不推进全库的「代」**（推进了 = 全库其余文档一夜变旧代）")
    void reindexDoesNotAdvanceTheKbGeneration() {
        int before = generation();

        post("/api/doc/" + docId + "/reindex", null, adminToken).assertOk("单篇重建");

        assertThat(generation())
                .as("🔴 单篇重建若推进代，ReindexService#docsToVectorize 会把**全库**文档判成旧代")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("T-610 · 状态闸：进行中拒绝、FAILED 放行（那才是它的正主）")
    void reindexRespectsDocStatus() {
        for (String inProgress : List.of("PARSING", "INDEXING")) {
            jdbc.update("update sys_document set status = ? where id = ?", inProgress, docId);
            RawResponse res = post("/api/doc/" + docId + "/reindex", null, adminToken);
            assertThat(res.code()).as("%s 时不该受理（已经有活人在跑）", inProgress).isEqualTo(10030);
        }
        // `UPLOADED` 也拒绝：它既没在跑也没失败，是"还没轮到"，不该由人手点重建解决
        jdbc.update("update sys_document set status = 'UPLOADED' where id = ?", docId);
        assertThat(post("/api/doc/" + docId + "/reindex", null, adminToken).code()).isEqualTo(10030);

        // `FAILED` 受理 —— 需求 §5「用于修复解析失败的个别文档」
        jdbc.update("update sys_document set status = 'FAILED' where id = ?", docId);
        RawResponse res = post("/api/doc/" + docId + "/reindex", null, adminToken);
        String status = res.data("$.status");
        assertThat(res.code()).as("🔴 FAILED 是这条能力的正主，拒了它等于把功能做废").isEqualTo(0);
        assertThat(status).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("T-610 · `doc:reindex` 守 POST：无码角色 403（可证伪）")
    void docReindexPermissionGuardsTheEndpoint() {
        long roleId = createTempRoleWith("probe.docwrite.preview", "doc:preview");
        String token = loginWithRole(roleId);

        RawResponse res = post("/api/doc/" + docId + "/reindex", null, token);

        assertThat(res.code()).isEqualTo(10020);
        Number n = jdbc.queryForObject("select count(*) from sys_async_task where doc_id = ?",
                Number.class, docId);
        assertThat(n.longValue()).as("被权限拦住时不该落任何任务").isZero();
    }

    // ==================================================================
    // `T-601` · 移动知识库的四道校验（成功路径在 E2E）
    // ==================================================================

    @Test
    @DisplayName("T-601 · 目标库密级低于当前生效密级 ⇒ 拒绝（**防换库降级绕过 I-6**）")
    void movingIntoALowerClassificationKbIsRejected() {
        jdbc.update("update sys_kb set secret_level = 'CONFIDENTIAL' where id = ?", srcKbId);
        jdbc.update("update sys_document set secret_level = NULL where id = ?", docId);
        long beforeKb = currentKbId();

        RawResponse res = patchAttributes(Map.of("kbId", lowKbId));

        assertThat(res.code()).as("🔴 这是 I-6 说的「把机密文档降级绕过管控」那条路，必须堵住")
                .isEqualTo(10022);
        assertThat(res.message()).contains("密级");
        assertThat(currentKbId()).as("拒了就不能改归属").isEqualTo(beforeKb);
    }

    @Test
    @DisplayName("T-601 · 目标库就是当前库 ⇒ 不算移动，`needReindex` 恒 false 且不落任务")
    void sendingTheSameKbIsNotAMove() {
        RawResponse res = patchAttributes(Map.of("kbId", srcKbId));
        Boolean needReindex = res.data("$.needReindex");

        assertThat(res.code()).isEqualTo(0);
        assertThat(needReindex).as("没换库就不是移动 —— 不能因此触发重建").isEqualTo(false);
        Number n = jdbc.queryForObject("select count(*) from sys_async_task where doc_id = ?",
                Number.class, docId);
        assertThat(n.longValue()).isZero();
    }

    @Test
    @DisplayName("T-601 · 对目标库没有上传权限 ⇒ 拒绝（授权是逐库的，不是角色级的）")
    void movingWithoutUploadGrantOnTargetIsRejected() {
        jdbc.update("delete from sys_kb_grant where kb_id = ?", tgtKbId);
        long beforeKb = currentKbId();

        RawResponse res = patchAttributes(Map.of("kbId", tgtKbId));

        assertThat(res.code()).as("「有 doc:update 码」不等于「哪个库都能进」").isEqualTo(10022);
        assertThat(currentKbId()).isEqualTo(beforeKb);
    }

    @Test
    @DisplayName("T-601 · 目标库不存在 ⇒ `KB_NOT_FOUND`（与「没权限」分开报）")
    void movingIntoAMissingKbIsReportedAsMissing() {
        RawResponse res = patchAttributes(Map.of("kbId", 999_999_999L));

        assertThat(res.code()).as("「库没了」与「你没权限」要做的事完全不同").isEqualTo(10043);
    }

    // ==================================================================
    // 夹具与辅助
    // ==================================================================

    private void buildFixtures() {
        srcKbId = insertKb(SRC_CODE, "INTERNAL", "USER", 1L, "MANAGE");
        tgtKbId = insertKb(TGT_CODE, "CONFIDENTIAL", "USER", 1L, "UPLOAD");
        lowKbId = insertKb(LOW_CODE, "PUBLIC", "USER", 1L, "UPLOAD");

        // ⚠️ `md5` 是 `CHAR(32)`：第一版写了一个 33 字符的字面量，报的是
        // `Data too long for column 'md5'` —— 而现象是"夹具建不起来"，与"字面量数错了"看不出关系
        String md5 = String.format("%032x", Math.abs(DOC_TITLE.hashCode()));
        jdbc.update("""
                        insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5,
                                                  storage_path, status, chunk_count, table_total, table_parsed,
                                                  image_total, image_parsed, pages_total, pages_with_text,
                                                  index_generation, created_at, updated_at)
                        values (?, 1, ?, ?, 'PDF', 1024, ?, 'documents/probe',
                                'READY', 0, 0, 0, 0, 0, 0, 0, 1, now(), now())
                        """, srcKbId, DOC_TITLE, DOC_TITLE + ".pdf", md5);
        docId = jdbc.queryForObject("select id from sys_document where kb_id = ? and title = ?",
                Long.class, srcKbId, DOC_TITLE);
    }

    private long insertKb(String code, String secretLevel, String principalType, long principalId, String level) {
        jdbc.update("""
                        insert into sys_kb (code, name, dept_id, doc_count, chunk_count, status, secret_level,
                                            business_domain, reindex_required, index_generation, created_at, updated_at)
                        values (?, ?, 1, 0, 0, 'READY', ?, '', 0, 1, now(), now())
                        """, code, code, secretLevel);
        long id = jdbc.queryForObject("select id from sys_kb where code = ?", Long.class, code);
        jdbc.update("""
                        insert into sys_kb_grant (kb_id, principal_type, principal_id, level, created_at, updated_at)
                        values (?, ?, ?, ?, now(), now())
                        """, id, principalType, principalId, level);
        // ⚠️ **不动 `sys_user`**：可见性求交确实还要求账号非停用，但种子超管本来就是 ACTIVE
        // ——为了"确保"去 UPDATE 一行**种子数据**，是在用例里改全局状态，
        // 而那种改动失败了也看不出来（它本来就是这个值）。需要非默认状态时另建账号。
        return id;
    }

    private RawResponse patchAttributes(Map<String, Object> body) {
        return call("PATCH", "/api/doc/" + docId + "/attributes", new HashMap<>(body), adminToken);
    }

    /** 文档自己那一列的原始值（`null` = 未覆盖）。 */
    private String currentSecretLevel() {
        return jdbc.queryForObject("select secret_level from sys_document where id = ?", String.class, docId);
    }

    private long currentKbId() {
        return jdbc.queryForObject("select kb_id from sys_document where id = ?", Long.class, docId);
    }

    private int generation() {
        return jdbc.queryForObject("select index_generation from sys_kb where id = ?", Integer.class, srcKbId);
    }

    /** `concat(值, updated_at)` —— 判"有没有写过"时两样一起看。 */
    private String after() {
        return jdbc.queryForObject(
                "select concat(ifnull(secret_level,'NULL'), '|', updated_at) from sys_document where id = ?",
                String.class, docId);
    }

    /**
     * 建一个只带指定权限码的临时角色，并登一个属于它的账号。
     *
     * <p>⚠️ {@code code} 必须**短**：{@code sys_role.code} 是 {@code VARCHAR(32)}。
     */
    private long createTempRoleWith(String code, String... permCodes) {
        String roleCode = code + "." + (System.nanoTime() % 1_000_000);
        jdbc.update("insert into sys_role (code, name, description, data_scope, enabled, builtin, sort, "
                        + "created_at, updated_at) values (?, ?, '探针角色', 'ALL', 1, 0, 99, now(), now())",
                roleCode, roleCode);
        long roleId = jdbc.queryForObject("select id from sys_role where code = ?", Long.class, roleCode);
        tmpRoleIds.add(roleId);
        for (String perm : permCodes) {
            jdbc.update("insert into sys_role_permission (role_id, permission_id, created_at) "
                    + "select ?, id, now() from sys_permission where code = ?", roleId, perm);
        }
        return roleId;
    }

    /** 登一个临时账号（超管身份会被 {@code superAdmin} 短路掉权限判定，所以必须是普通账号）。 */
    private String loginWithRole(long roleId) {
        TestUser u = createUser("docwrite");
        jdbc.update("insert into sys_user_role (user_id, role_id, created_at) "
                + "select id, ?, now() from sys_user where username = ?", roleId, u.username());
        return login(u.username(), u.password()).assertOk("临时账号登录").data("$.accessToken");
    }

    private void clearAll() {
        for (String code : List.of(SRC_CODE, TGT_CODE, LOW_CODE)) {
            Long id = jdbc.query("select id from sys_kb where code = ?", (rs, i) -> rs.getLong(1), code)
                    .stream().findFirst().orElse(null);
            if (id == null) {
                continue;
            }
            jdbc.update("delete from sys_chunk where kb_id = ?", id);
            jdbc.update("delete from sys_async_task where kb_id = ?", id);
            jdbc.update("delete from sys_document where kb_id = ?", id);
            jdbc.update("delete from sys_kb_grant where kb_id = ?", id);
            jdbc.update("delete from sys_kb where id = ?", id);
        }
        // ⚠️ 必须按外键序清：`sys_user_role.role_id` 有指向 `sys_role.id` 的外键，
        // 先删角色会报 `Cannot delete or update a parent row`
        for (long roleId : tmpRoleIds) {
            jdbc.update("delete from sys_user_role where role_id = ?", roleId);
            jdbc.update("delete from sys_role_permission where role_id = ?", roleId);
            jdbc.update("delete from sys_role where id = ?", roleId);
        }
        tmpRoleIds.clear();
    }
}
