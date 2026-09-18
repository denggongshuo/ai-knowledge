package com.knowledge.ai.service;

import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-802` 余下半边（`S5-M08-B`）—— 会话列表 / 消息列表 / 删除 / 导出的**真 HTTP** 取证。
 *
 * <h3>为什么这批判据必须走 HTTP，不能直调 Service</h3>
 * <p>两件事只在请求上下文里成立：
 * <ul>
 *   <li><b>权限</b>：{@code @RequiresPerm} 由 MVC 拦截器/切面执行，直调 Service 根本不经过它
 *       —— 那样的用例**无论权限码挂没挂都会绿**；</li>
 *   <li><b>审计</b>：{@code operator} 与 {@code apiPath} 取自 {@code LoginUserContext} 与请求，
 *       直调 Service 会落成空值。而"谁导出了哪个会话"正是这条日志唯一的价值。</li>
 * </ul>
 *
 * <h3>会话数据为什么直接 insert 而不是走问答</h3>
 * <p>走 {@code POST /chat/completions} 要真调模型（本仓基线是"全替身无网络"）。
 * 而本类要验的是**读侧**：列表怎么拼、归属怎么过滤、引用怎么重建 ——
 * 这些都不依赖会话是怎么生成的。所以直接造行，把模型调用挡在门外。
 */
class ChatSessionApiFlowTest extends ApiTestSupport {

    /** 用例开始前 `sys_chat_session` 的最大 id —— 收尾兜底删本次新增的。 */
    private long sessionFloor;

    @BeforeEach
    void markChatFloor() {
        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from sys_chat_session", Long.class);
        sessionFloor = max == null ? 0L : max;
    }

    /**
     * 兜底清理。主路径其实是 {@link #trackUser}/{@link ApiTestSupport#createUser}
     * 收尾时按 {@code user_id} 清（{@code ProbeCleanup#cleanUsers} 覆盖了会话与消息），
     * 这里再按水位线扫一遍 —— 防的是"造了行但没挂在被跟踪账号上"那种漏法。
     */
    @AfterEach
    void removeCreatedSessions() {
        jdbc.update("delete from sys_chat_message where session_id > ?", sessionFloor);
        jdbc.update("delete from sys_chat_session where id > ?", sessionFloor);
    }

    // ==================================================================
    // 判据 1 / 3 —— 列表
    // ==================================================================

    @Test
    @DisplayName("判据 1：列表带展示串 time / 条数 count / 所用模型 modelName")
    void listCarriesTimeCountAndModel() {
        TestUser user = createUser("s5sess.list");
        long uid = userIdOf(user.username());
        long sessionId = insertSession(uid, "RAG 检索链路设计");
        insertMessage(sessionId, "user", "RAG 是怎么做的？", null, null, null);
        insertMessage(sessionId, "assistant", "三段式：召回 → 精排 → 生成", 1L, "DeepSeek-V3", null);

        var row = get("/api/chat/sessions?page=1&size=10", user.accessToken())
                .assertOk("会话列表")
                .data("$.list[0]");

        assertThat(((Map<?, ?>) row).get("title")).isEqualTo("RAG 检索链路设计");
        assertThat(((Number) ((Map<?, ?>) row).get("count")).intValue())
                .as("前端直接拼「N 条」").isEqualTo(2);
        assertThat(((Map<?, ?>) row).get("time")).as("前端 {{ s.time }} 原样渲染，不做格式化")
                .asString().isNotBlank();
        assertThat(((Map<?, ?>) row).get("modelName")).as("需求 09:124「每个会话显示所用模型」")
                .isEqualTo("DeepSeek-V3");
        assertThat(((Map<?, ?>) row).get("id")).as("前端类型是 string，别发数字")
                .isEqualTo(String.valueOf(sessionId));
    }

    @Test
    @DisplayName("判据 2：列表**只含本人** —— 越权读会话是数据泄露")
    void listHidesOtherUsersSessions() {
        TestUser mine = createUser("s5sess.mine");
        TestUser other = createUser("s5sess.other");
        insertSession(userIdOf(mine.username()), "我的会话");
        insertSession(userIdOf(other.username()), "别人的会话");

        var data = get("/api/chat/sessions?page=1&size=50", mine.accessToken())
                .assertOk("会话列表")
                .data("$.list");

        List<?> list = (List<?>) data;
        assertThat(list).as("自己的会话必须在").isNotEmpty();
        assertThat(list).allSatisfy(item -> assertThat(((Map<?, ?>) item).get("title"))
                .as("别人的会话不该出现在我的列表里").isNotEqualTo("别人的会话"));
    }

    @Test
    @DisplayName("判据 3：标题搜索命中/不命中都是 200，而不是把全量当结果返回")
    void listFiltersByTitle() {
        TestUser user = createUser("s5sess.search");
        long uid = userIdOf(user.username());
        insertSession(uid, "季度报价单解读");
        insertSession(uid, "多跳推理与知识图谱");

        // ⚠️ 用 getEncoded 而不是 get：`RestClient.uri(String)` 是 **URI 模板**，
        // 会把查询串里的 `%` 再编码一次（%E6 → %25E6），服务端解码后拿到的是字面量
        // "%E6%8A%A5%E4%BB%B7"，自然一条都搜不到 —— 而这看起来像"搜索功能没生效"
        var hit = getEncoded("/api/chat/sessions?keyword=%E6%8A%A5%E4%BB%B7&page=1&size=50",
                user.accessToken()).assertOk("按标题搜索").data("$.list");
        var miss = getEncoded("/api/chat/sessions?keyword=%E4%B8%8D%E5%AD%98%E5%9C%A8%E7%9A%84%E8%AF%8D&page=1&size=50",
                user.accessToken()).assertOk("搜不到也应是 200").data("$.list");

        assertThat((List<?>) hit).hasSize(1);
        assertThat(((Map<?, ?>) ((List<?>) hit).getFirst()).get("title")).isEqualTo("季度报价单解读");
        assertThat((List<?>) miss).as("搜不到就是空列表，不是全量").isEmpty();
    }

    // ==================================================================
    // 判据 4 —— 消息列表与历史引用
    // ==================================================================

    @Test
    @DisplayName("判据 4：消息列表带 role / modelId / modelName，且**历史引用**从打点明细重建")
    void messagesCarryModelAndCitations() {
        TestUser user = createUser("s5sess.msg");
        long uid = userIdOf(user.username());
        long sessionId = insertSession(uid, "报价单问题");
        String traceId = "s5sess" + Math.floorMod(System.nanoTime(), 1_000_000_000L);
        long docId = insertDoc(uid, "季度报价单（2026Q3）");

        insertTrace(traceId, uid, sessionId);
        insertTraceChunk(traceId, 1, 1001L, docId, "vector");
        insertTraceChunk(traceId, 2, 1002L, docId, "bm25");
        insertMessage(sessionId, "user", "A-100 多少钱？", null, null, null);
        insertMessage(sessionId, "assistant", "1800 元。", 1L, "DeepSeek-V3", traceId);

        List<?> list = (List<?>) get("/api/chat/sessions/" + sessionId + "/messages", user.accessToken())
                .assertOk("消息列表").data("$.list");

        assertThat(list).hasSize(2);
        Map<?, ?> answer = (Map<?, ?>) list.get(1);
        assertThat(answer.get("role")).isEqualTo("assistant");
        assertThat(answer.get("modelName")).isEqualTo("DeepSeek-V3");
        assertThat(answer.get("content")).isEqualTo("1800 元。");

        List<?> citations = (List<?>) answer.get("citations");
        assertThat(citations).as("trace_id 上挂着两条打点明细，引用必须重建出来 —— "
                + "否则 sys_chat_message.trace_id 这一列永远只有写入没有读取").hasSize(2);
        Map<?, ?> first = (Map<?, ?>) citations.getFirst();
        assertThat(((Number) first.get("index")).intValue()).isEqualTo(1);
        assertThat(((Number) first.get("chunkId")).longValue()).isEqualTo(1001);
        assertThat(first.get("docTitle")).as("引用卡片显示的是**文档**标题，不是标题路径")
                .isEqualTo("季度报价单（2026Q3）");
        assertThat(first.get("source")).isEqualTo("vector");
        assertThat(((Map<?, ?>) citations.get(1)).get("source")).isEqualTo("bm25");
    }

    @Test
    @DisplayName("会话不可用（不存在 / 不是自己的 / 已软删）一律 NOT_FOUND，**不区分**三者")
    void unusableSessionIsNotFound() {
        TestUser mine = createUser("s5sess.guard");
        TestUser other = createUser("s5sess.victim");
        long othersSession = insertSession(userIdOf(other.username()), "别人的会话");

        var notMine = get("/api/chat/sessions/" + othersSession + "/messages", mine.accessToken());
        var missing = get("/api/chat/sessions/999999999/messages", mine.accessToken());

        assertThat(notMine.code()).as("不是自己的会话 → 查不到").isEqualTo(10040);
        assertThat(missing.code()).as("不存在的会话 → 同一个码。"
                + "区分开就等于告诉调用方「这个 id 确实存在，只是不归你」，那是个可枚举的信息泄露面")
                .isEqualTo(10040);
    }

    // ==================================================================
    // 判据 5 —— 软删
    // ==================================================================

    @Test
    @DisplayName("判据 5：删除**只软删** —— 消息行仍在库里，列表随即取不到")
    void deleteIsSoftAndKeepsMessages() {
        // 用 KB_ADMIN 而不是 createUser 的默认角色：本仓**默认角色（USER）没有
        // qa:history:delete**，拿它测删除只会拿到 403。角色边界见判据 22 那条用例
        Actor user = userWithRole("s5sess.del", "KB_ADMIN");
        long uid = userIdOf(user.username());
        long sessionId = insertSession(uid, "待删会话");
        insertMessage(sessionId, "assistant", "内容", null, null, null);

        var deleted = call("DELETE", "/api/chat/sessions/" + sessionId, null, user.token());
        assertThat(deleted.code()).isEqualTo(CODE_OK);

        var row = jdbc.queryForMap("select * from sys_chat_session where id = ?", sessionId);
        assertThat(row.get("deleted_at")).as("软删标记").isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from sys_chat_message where session_id = ?",
                Integer.class, sessionId))
                .as("消息行**不级联删** —— 「删了会话」与「抹掉问答记录」是两件事")
                .isEqualTo(1);

        List<?> afterDelete = (List<?>) get("/api/chat/sessions?page=1&size=50", user.token())
                .assertOk("删除后再查列表").data("$.list");
        assertThat(afterDelete).allSatisfy(item -> assertThat(((Map<?, ?>) item).get("id"))
                .as("软删的会话不该还在列表里").isNotEqualTo(String.valueOf(sessionId)));
    }

    // ==================================================================
    // 判据 6 / 8 —— 导出（🚩 数据导出）
    // ==================================================================

    @Test
    @DisplayName("判据 6：导出走真 HTTP 时**落下高危审计**（operator 取自请求上下文）")
    void exportWritesHighRiskAudit() {
        Actor admin = loginAsAdminUser();
        long uid = userIdOf(admin.username());
        long sessionId = insertSession(uid, "客户续约流程");
        insertMessage(sessionId, "user", "续约流程有几步？", null, null, null);

        long before = auditCount();
        var response = get("/api/chat/sessions/" + sessionId + "/export?format=md", admin.token());
        assertThat(response.status()).as("导出应成功，实际：" + response.body()).isEqualTo(200);
        assertThat(auditCount()).as("导出必须留痕 —— 《01》§487 把会话导出归为数据外发行为").isEqualTo(before + 1);

        Map<String, Object> row = jdbc.queryForMap(
                "select module, action, risk_level, operator from sys_audit_log "
                        + "where id = (select max(id) from sys_audit_log)");
        assertThat(row.get("module")).isEqualTo("问答");
        assertThat(row.get("action")).isEqualTo("导出会话");
        assertThat(row.get("risk_level")).as("§4.3② 数据导出 = 高危").isEqualTo("HIGH");
        assertThat(row.get("operator")).as("真 HTTP 才拿得到 operator；直调 Service 这里是空的")
                .isEqualTo(admin.username());
    }

    @Test
    @DisplayName("判据 8：导出体是 Markdown，文件名走 RFC 5987（会话标题可能含中文）")
    void exportBodyIsMarkdownWithEncodedFilename() {
        Actor user = userWithRole("s5sess.export", "KB_ADMIN");
        long sessionId = insertSession(userIdOf(user.username()), "客户续约流程");
        insertMessage(sessionId, "user", "续约流程有几步？", null, null, null);
        insertMessage(sessionId, "assistant", "一共 4 步。", 1L, "DeepSeek-V3", null);

        var response = export(sessionId, user.token());

        assertThat(response.body()).as("Markdown 首行应是会话标题")
                .startsWith("# 客户续约流程");
        assertThat(response.body()).contains("## 提问").contains("## 回答").contains("一共 4 步。");
        assertThat(response.body()).as("模型名要带出去 —— 导出件的用途之一就是回答「这条是谁生成的」")
                .contains("DeepSeek-V3");
        // 头里必须是 `filename*=UTF-8''…` 而不是裸中文（http 头只能放 ASCII）
        assertThat(response.disposition())
                .as("会话标题含中文，文件名必须走 RFC 5987，否则客户端拿到的是乱码名")
                .contains("attachment;")
                .contains("filename*=UTF-8''");
    }

    /** 一次导出：**带上响应头** —— {@code RawResponse} 只留状态码/体/Set-Cookie，看不了 Content-Disposition。 */
    private ExportResponse export(long sessionId, String token) {
        return client.get().uri(url("/api/chat/sessions/" + sessionId + "/export?format=md"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange((request, response) -> new ExportResponse(
                        response.getStatusCode().value(),
                        response.bodyTo(String.class),
                        response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)));
    }

    private record ExportResponse(int status, String body, String disposition) {
    }

    // ==================================================================
    // 判据 22 / 23 —— 权限边界（施工首日修订新增）
    // ==================================================================

    @Test
    @DisplayName("判据 22：USER / AUDITOR 能查会话，但**不能删、不能导**")
    void userAndAuditorCanReadButNotDestroy() {
        for (String role : List.of("USER", "AUDITOR")) {
            Actor user = userWithRole("s5sess." + role.toLowerCase(), role);
            long sessionId = insertSession(userIdOf(user.username()), role + " 的会话");

            assertThat(get("/api/chat/sessions?page=1&size=10", user.token()).code())
                    .as("%s 应能看自己的会话列表", role).isEqualTo(CODE_OK);
            assertThat(get("/api/chat/sessions/" + sessionId + "/messages", user.token()).code())
                    .as("%s 应能回看自己的消息", role).isEqualTo(CODE_OK);

            var del = call("DELETE", "/api/chat/sessions/" + sessionId, null, user.token());
            var exp = get("/api/chat/sessions/" + sessionId + "/export?format=md", user.token());
            assertThat(del.status()).as("%s **没有** qa:history:delete，删会话必须 403", role).isEqualTo(403);
            assertThat(exp.status()).as("%s **没有** qa:session:export，导出必须 403", role).isEqualTo(403);
            assertThat(del.code()).isEqualTo(10020);
        }
    }

    @Test
    @DisplayName("判据 23：KB_ADMIN 四个接口全通 · GUEST 连列表都进不去")
    void kbAdminPassesAndGuestIsLockedOut() {
        Actor admin = userWithRole("s5sess.kbadmin", "KB_ADMIN");
        long uid = userIdOf(admin.username());
        long sessionId = insertSession(uid, "KB_ADMIN 的会话");
        insertMessage(sessionId, "user", "问题", null, null, null);

        assertThat(get("/api/chat/sessions?page=1&size=10", admin.token()).code()).isEqualTo(CODE_OK);
        assertThat(get("/api/chat/sessions/" + sessionId + "/messages", admin.token()).code())
                .isEqualTo(CODE_OK);
        assertThat(get("/api/chat/sessions/" + sessionId + "/export?format=md", admin.token()).status())
                .as("KB_ADMIN 有 qa:session:export").isEqualTo(200);

        Actor guest = userWithRole("s5sess.guest", "GUEST");
        assertThat(get("/api/chat/sessions?page=1&size=10", guest.token()).status())
                .as("GUEST 一个 qa:* 码都没有，列表就该进不去").isEqualTo(403);
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /**
     * 造一个**指定角色**的账号。
     *
     * <p>⚠️ 改完角色必须**重新登录** —— 权限码是编进 JWT 的（{@code LoginUser} 的 javadoc：
     * "S0 阶段把 permissions 直接编进 JWT…换取鉴权零 DB 依赖"）。
     * 拿旧令牌去测新角色，测的还是旧权限，而用例**照样可能绿**。
     */
    private Actor userWithRole(String prefix, String roleCode) {
        TestUser created = createUser(prefix);
        Long uid = userIdOf(created.username());
        jdbc.update("delete from sys_user_role where user_id = ?", uid);
        jdbc.update("insert into sys_user_role (user_id, role_id, created_at) "
                + "select ?, id, now() from sys_role where code = ?", uid, roleCode);
        assertThat(jdbc.queryForObject("select count(*) from sys_user_role where user_id = ?",
                Integer.class, uid)).as("角色切换必须生效，否则后面测的是旧权限").isEqualTo(1);

        return new Actor(created.username(), login(created.username(), created.password())
                .data("$.accessToken"));
    }

    /** 种子超管的令牌（导出审计那条要用它 —— 它有 {@code qa:session:export}）。 */
    private Actor loginAsAdminUser() {
        return new Actor(ADMIN, login(ADMIN, ADMIN_PASSWORD).data("$.accessToken"));
    }

    /**
     * 「谁 + 他的令牌」。
     *
     * <p>不复用 {@code ApiTestSupport.TestUser}：那个替身**不能在包外构造**
     * （它是 {@code protected record}），而本类在 {@code service} 包下。
     * 与其把它的可见性放宽，不如在这里只带本类真正要用的两样。
     */
    private record Actor(String username, String token) {
    }

    private long userIdOf(String username) {
        Long id = jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
        assertThat(id).as("账号 %s 应该存在", username).isNotNull();
        return id;
    }

    private long insertSession(long userId, String title) {
        jdbc.update("insert into sys_chat_session (user_id, title, model_id, created_at, updated_at) "
                + "values (?, ?, null, now(), now())", userId, title);
        return jdbc.queryForObject("select max(id) from sys_chat_session", Long.class);
    }

    private void insertMessage(long sessionId, String role, String content,
                               Long modelId, String modelName, String traceId) {
        jdbc.update("insert into sys_chat_message "
                        + "(session_id, role, content, model_id, model_name, trace_id, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, now())",
                sessionId, role, content, modelId, modelName, traceId);
    }

    /** 造一篇文档（引用重建要补文档标题；`kb_id` 用探针库，随 kb 兜底清理一起走）。 */
    private long insertDoc(long ownerId, String title) {
        jdbc.update("insert into sys_document (kb_id, title, file_name, type, size_bytes, md5, "
                        + "status, owner_id, created_at, updated_at) "
                        + "values (0, ?, ?, 'MD', 1, ?, 'READY', ?, now(), now())",
                title, title + ".md", "s5sess" + System.nanoTime(), ownerId);
        return jdbc.queryForObject("select max(id) from sys_document", Long.class);
    }

    private void insertTrace(String traceId, long userId, long sessionId) {
        jdbc.update("insert into sys_retrieval_trace (trace_id, user_id, session_id, query_text) "
                + "values (?, ?, ?, 'A-100 多少钱？')", traceId, userId, sessionId);
    }

    private void insertTraceChunk(String traceId, int rankNo, long chunkId, long docId, String source) {
        jdbc.update("insert into sys_retrieval_trace_chunk "
                        + "(trace_id, rank_no, chunk_id, doc_id, score, source) values (?, ?, ?, ?, 0.9, ?)",
                traceId, rankNo, chunkId, docId, source);
    }

    private long auditCount() {
        Long count = jdbc.queryForObject("select count(*) from sys_audit_log", Long.class);
        return count == null ? 0L : count;
    }
}