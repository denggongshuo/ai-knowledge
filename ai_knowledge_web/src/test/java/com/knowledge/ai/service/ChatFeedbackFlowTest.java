package com.knowledge.ai.service;

import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-807` / 门禁 **`G5-14`** —— 反馈（点赞 / 点踩 + 原因）**真实入库**的真 HTTP 取证。
 *
 * <h3>为什么要走 HTTP 而不是直调 {@code ChatFeedbackService}</h3>
 * <p>三件事只在请求上下文里成立：① 权限码由 MVC 拦截器执行，直调根本不经过它
 * ——那样的用例**无论权限码挂没挂都会绿**；② {@code user_id} 取自
 * {@code LoginUserContext}，直调要自己伪造登录态；③ 「消息 → 会话 → 人」这条归属链
 * 只在真实请求下才被走通。与 {@code ChatSessionApiFlowTest} 同一条理由。
 *
 * <h3>会话与消息为什么直接 insert</h3>
 * <p>走 {@code POST /chat/completions} 要真调模型，而本仓基线是"全替身、无网络"。
 * 本类验的是**写侧三列**与**归属边界**，两者都不依赖消息是怎么生成的。
 *
 * <h3>本类没有一条断言读 Service 的返回值当"做成了"的证据</h3>
 * <p>每条判据都回查 `sys_chat_message` 的三列 —— 接口返回 200 只证明它没抛异常。
 */
class ChatFeedbackFlowTest extends ApiTestSupport {

    /** 用例开始前 `sys_chat_message` 的最大 id —— 收尾兜底删本次新增的。 */
    private long messageFloor;

    private long sessionFloor;

    @BeforeEach
    void markFloors() {
        Long m = jdbc.queryForObject("select coalesce(max(id), 0) from sys_chat_message", Long.class);
        messageFloor = m == null ? 0L : m;
        Long s = jdbc.queryForObject("select coalesce(max(id), 0) from sys_chat_session", Long.class);
        sessionFloor = s == null ? 0L : s;
    }

    /**
     * 兜底清理。
     *
     * <p>主路径是 {@code createUser} 登记后由 {@code ProbeCleanup#cleanUsers} 按 `user_id`
     * 清（那条路径**必须先删消息再删会话**，否则子查询找不到会话）。这里再按水位线扫一遍，
     * 防的是"造了行但没挂在被跟踪账号上"。
     */
    @AfterEach
    void removeCreatedRows() {
        jdbc.update("delete from sys_chat_message where id > ?", messageFloor);
        jdbc.update("delete from sys_chat_session where id > ?", sessionFloor);
    }

    // ==================================================================
    // 判据 4 —— like / unlike + reason 真实入库
    // ==================================================================

    @Test
    @DisplayName("G5-14：点踩 + 原因 → 三列真的写进 sys_chat_message")
    void unlikeWithReasonIsPersisted() {
        TestUser user = createUser("s5fb.unlike");
        long uid = userIdOf(user.username());
        long sessionId = insertSession(uid);
        long msgId = insertMessage(sessionId, "assistant", "这是回答");

        post("/api/chat/feedback",
                Map.of("messageId", String.valueOf(msgId), "type", "unlike", "reason", "答案不准确"),
                user.accessToken()).assertOk("提交点踩");

        Map<String, Object> row = queryMessage(msgId);
        assertThat(row.get("feedback")).isEqualTo("unlike");
        assertThat(row.get("feedback_reason")).isEqualTo("答案不准确");
        assertThat(row.get("feedback_at")).as("反馈时间必须落 —— 运营页按它做时间范围过滤").isNotNull();
    }

    @Test
    @DisplayName("G5-14：点赞落库，且原因列必须为 NULL（不是空串）")
    void likeIsPersistedWithoutReason() {
        TestUser user = createUser("s5fb.like");
        long uid = userIdOf(user.username());
        long sessionId = insertSession(uid);
        long msgId = insertMessage(sessionId, "assistant", "这是回答");

        post("/api/chat/feedback",
                Map.of("messageId", String.valueOf(msgId), "type", "like"),
                user.accessToken()).assertOk("提交点赞");

        Map<String, Object> row = queryMessage(msgId);
        assertThat(row.get("feedback")).isEqualTo("like");
        // ⚠️ 断言 null 而不是"不在集合里"：空串与 NULL 在 `where feedback_reason is null`
        // 之下是不同的结果，而运营统计很可能就按那条写
        assertThat(row.get("feedback_reason")).as("点赞不带原因 —— 写空串会让 `is null` 查不到").isNull();
    }

    @Test
    @DisplayName("G5-14：点踩**可以不带**原因（需求 07 §6.8 写的是「点踩时**可选**原因」）")
    void unlikeWithoutReasonIsAllowed() {
        TestUser user = createUser("s5fb.noreason");
        long uid = userIdOf(user.username());
        long sessionId = insertSession(uid);
        long msgId = insertMessage(sessionId, "assistant", "这是回答");

        post("/api/chat/feedback",
                Map.of("messageId", String.valueOf(msgId), "type", "unlike"),
                user.accessToken()).assertOk("点踩不带原因");

        Map<String, Object> row = queryMessage(msgId);
        assertThat(row.get("feedback")).isEqualTo("unlike");
        assertThat(row.get("feedback_reason")).isNull();
    }

    // ==================================================================
    // 判据 5 —— 非法输入被拒
    // ==================================================================

    @Test
    @DisplayName("G5-14：非法 type / 点赞带原因 / 非法原因，三种都拒且**都没写库**")
    void invalidInputsAreRejectedAndNothingIsWritten() {
        TestUser user = createUser("s5fb.reject");
        long uid = userIdOf(user.username());
        long sessionId = insertSession(uid);
        long msgId = insertMessage(sessionId, "assistant", "这是回答");

        assertThat(post("/api/chat/feedback",
                Map.of("messageId", String.valueOf(msgId), "type", "love"),
                user.accessToken()).code()).as("type 只能是 like/unlike").isEqualTo(10030);

        assertThat(post("/api/chat/feedback",
                Map.of("messageId", String.valueOf(msgId), "type", "like", "reason", "答案不准确"),
                user.accessToken()).code()).as("需求：原因只属点踩").isEqualTo(10030);

        assertThat(post("/api/chat/feedback",
                Map.of("messageId", String.valueOf(msgId), "type", "unlike", "reason", "随便写的"),
                user.accessToken()).code()).as("原因是闭集，运营页要能分组统计").isEqualTo(10030);

        assertThat(queryMessage(msgId).get("feedback"))
                .as("三次都被拒，库里必须还是「从未反馈」").isNull();
    }

    // ==================================================================
    // 判据 6 —— 反馈可改（后写覆盖前写）
    // ==================================================================

    @Test
    @DisplayName("G5-14：先点赞后点踩 → 库里只有终态，且原因跟着换")
    void feedbackCanBeChangedAndOverwrites() {
        TestUser user = createUser("s5fb.change");
        long uid = userIdOf(user.username());
        long sessionId = insertSession(uid);
        long msgId = insertMessage(sessionId, "assistant", "这是回答");

        post("/api/chat/feedback",
                Map.of("messageId", String.valueOf(msgId), "type", "like"),
                user.accessToken()).assertOk("先点赞");
        post("/api/chat/feedback",
                Map.of("messageId", String.valueOf(msgId), "type", "unlike", "reason", "没找到相关内容"),
                user.accessToken()).assertOk("改成点踩");

        Map<String, Object> row = queryMessage(msgId);
        assertThat(row.get("feedback")).isEqualTo("unlike");
        assertThat(row.get("feedback_reason")).isEqualTo("没找到相关内容");
        assertThat(jdbc.queryForObject(
                "select count(*) from sys_chat_message where id = ?", Integer.class, msgId))
                .as("覆盖语义 —— 同一消息不会多出一行").isEqualTo(1);
    }

    @Test
    @DisplayName("G5-14：点赞必须把上一轮的点踩原因**清掉**（否则库里出现自相矛盾的行）")
    void likeClearsPreviousReason() {
        TestUser user = createUser("s5fb.clear");
        long uid = userIdOf(user.username());
        long sessionId = insertSession(uid);
        long msgId = insertMessage(sessionId, "assistant", "这是回答");

        post("/api/chat/feedback",
                Map.of("messageId", String.valueOf(msgId), "type", "unlike", "reason", "回答太啰嗦"),
                user.accessToken()).assertOk("先点踩");
        post("/api/chat/feedback",
                Map.of("messageId", String.valueOf(msgId), "type", "like"),
                user.accessToken()).assertOk("再点赞");

        Map<String, Object> row = queryMessage(msgId);
        assertThat(row.get("feedback")).isEqualTo("like");
        assertThat(row.get("feedback_reason"))
                .as("feedback=like 却留着 feedback_reason，会让只按 reason 分组的统计把它算进负反馈")
                .isNull();
    }

    // ==================================================================
    // 判据 7 —— 归属边界
    // ==================================================================

    @Test
    @DisplayName("G5-14：给**别人的**消息反馈 → 与「消息不存在」同码 NOT_FOUND，且没写库")
    void feedbackOnSomeoneElsesMessageIsNotFound() {
        TestUser owner = createUser("s5fb.owner");
        TestUser other = createUser("s5fb.other");
        long ownerId = userIdOf(owner.username());
        long sessionId = insertSession(ownerId);
        long msgId = insertMessage(sessionId, "assistant", "这是别人的回答");

        int notMine = post("/api/chat/feedback",
                Map.of("messageId", String.valueOf(msgId), "type", "like"),
                other.accessToken()).code();
        int missing = post("/api/chat/feedback",
                Map.of("messageId", "999999999", "type", "like"),
                other.accessToken()).code();

        assertThat(notMine).as("不是你的 → NOT_FOUND").isEqualTo(10040);
        assertThat(missing).as("不存在 → 同一个码；区分开就泄露了「这个 id 存在」").isEqualTo(10040);
        assertThat(queryMessage(msgId).get("feedback")).as("越权调用不得留下任何写入").isNull();
    }

    @Test
    @DisplayName("G5-14：已软删会话里的消息不能再被反馈")
    void feedbackOnDeletedSessionIsNotFound() {
        TestUser user = createUser("s5fb.deleted");
        long uid = userIdOf(user.username());
        long sessionId = insertSession(uid);
        long msgId = insertMessage(sessionId, "assistant", "这是回答");

        get("/api/chat/sessions/" + sessionId + "/messages", user.accessToken()).assertOk("删除前能看");
        jdbc.update("update sys_chat_session set deleted_at = now() where id = ?", sessionId);

        assertThat(post("/api/chat/feedback",
                Map.of("messageId", String.valueOf(msgId), "type", "like"),
                user.accessToken()).code())
                .as("用户已经删掉那段对话了，再往里写反馈等于写进一个他看不到的地方")
                .isEqualTo(10040);
    }

    @Test
    @DisplayName("G5-14：只能反馈**回答**，给提问点赞会被拒（页脚那个入口挂在回答下面）")
    void feedbackOnUserMessageIsRejected() {
        TestUser user = createUser("s5fb.question");
        long uid = userIdOf(user.username());
        long sessionId = insertSession(uid);
        long msgId = insertMessage(sessionId, "user", "这是提问");

        assertThat(post("/api/chat/feedback",
                Map.of("messageId", String.valueOf(msgId), "type", "like"),
                user.accessToken()).code())
                .as("给提问点赞运营侧归不出因").isEqualTo(10030);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private Map<String, Object> queryMessage(long id) {
        return jdbc.queryForMap(
                "select feedback, feedback_reason, feedback_at, created_at from sys_chat_message where id = ?", id);
    }

    private long userIdOf(String username) {
        Long id = jdbc.queryForObject("select id from sys_user where username = ?", Long.class, username);
        assertThat(id).as("账号 %s 应该存在", username).isNotNull();
        return id;
    }

    private long insertSession(long userId) {
        jdbc.update("insert into sys_chat_session (user_id, title, model_id, created_at, updated_at) "
                + "values (?, '反馈用例会话', null, now(), now())", userId);
        return jdbc.queryForObject("select max(id) from sys_chat_session", Long.class);
    }

    private long insertMessage(long sessionId, String role, String content) {
        jdbc.update("insert into sys_chat_message (session_id, role, content, created_at) "
                + "values (?, ?, ?, now())", sessionId, role, content);
        return jdbc.queryForObject("select max(id) from sys_chat_message", Long.class);
    }
}
