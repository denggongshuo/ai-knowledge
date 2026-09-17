package com.knowledge.ai.service;

import com.knowledge.ai.entity.SysChatMessage;
import com.knowledge.ai.entity.SysChatSession;
import com.knowledge.ai.pipeline.chat.ChatDtos;
import com.knowledge.ai.repository.SysChatMessageRepository;
import com.knowledge.ai.repository.SysChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 会话与消息（`T-802` 的**建表半边**用到的那部分）。
 *
 * <h3>本批做到哪、不做到哪</h3>
 * <p>**做**：会话解析（不存在就开）、`G5-10` 的"绑定不一致 → 开新会话"那一跳、
 * user/assistant 两条消息落库（`done.messageId` 要真行 id）。
 * <br>**不做**：会话列表 / 消息列表 / 删除 / 导出 —— 属 `T-802` 的余下部分。
 *
 * <h3>⚠️ 本批 {@code modelId} 只驱动"会话绑定"，**不驱动模型选择**</h3>
 * <p>前端传的是**数字**，而后端 {@code ModelConfigSpec#id} 是**字符串**
 * （引导期是 {@code bootstrap-chat}），二者当前**没有映射表** —— {@code sys_model_config}
 * 是 0 行（`汇总.md` §3 的 `#15`）。所以本批实际使用的模型恒为
 * {@code activeSpec(CHAT)}，而 {@code done} 事件**如实回带**那个真实值。
 *
 * <p>这不是"假装支持了换模型"：用户选 B 而实际用 A 时，`done.modelId` 会给出 A 的标识
 * ——**界面说的与实际发生的一致**。让"绑定"真正生效归 `T-803`（S6 把模型配置接进 DB 之后）。
 * 本批把绑定**记下来**是对的：不记的话，`T-803` 落地时这之前的会话全都缺这一列，
 * 又是"事后补不回来"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    /** 标题取首个提问的前 N 个字符。够列表页辨认，又不至于把一整段塞进列表。 */
    private static final int TITLE_MAX = 50;

    private final SysChatSessionRepository sessionRepository;
    private final SysChatMessageRepository messageRepository;

    /**
     * 本次提问落在哪个会话。
     *
     * @param userId          提问人
     * @param requestSessionId 请求里的会话 id；可为空（前端 V2 尚未传 → 视为新会话）
     * @param requestModelId  请求里的模型 id；可为空
     * @return 会话 id（已存在或新建）
     */
    @Transactional
    public long resolve(Long userId, Long requestSessionId, Long requestModelId) {
        if (requestSessionId != null) {
            Optional<SysChatSession> found =
                    sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(requestSessionId, userId);
            if (found.isPresent()) {
                SysChatSession session = found.get();
                if (requestModelId == null || requestModelId.equals(session.getModelId())) {
                    return session.getId();
                }
                // `G5-10`：传了 modelId 且与绑定不一致 → **开新会话，原会话保留**。
                // 需求说的不是"报错"也不是"改绑定" —— 原会话的上下文属于原模型，
                // 改绑定会让它之前的答案失去归因
                log.info("[问答] 模型与会话绑定不一致，服务端开新会话：sessionId={} 原绑定={} 本次={}",
                        session.getId(), session.getModelId(), requestModelId);
                return create(userId, requestModelId);
            }
            // 会话不存在 / 不属于这个人 / 已软删 —— **开新会话并 warn，不让问答失败**。
            // 不报错是有意的：一个失效的会话 id 不该让用户问不出问题；
            // 不静默是有意的：这通常是客户端的 bug，日志里要看得见
            log.warn("[问答] 请求携带的会话不可用，已改为开新会话：userId={} sessionId={}",
                    userId, requestSessionId);
        }
        return create(userId, requestModelId);
    }

    private long create(Long userId, Long modelId) {
        SysChatSession session = new SysChatSession();
        session.setUserId(userId);
        session.setTitle("");
        session.setModelId(modelId);
        LocalDateTime now = LocalDateTime.now();
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return sessionRepository.save(session).getId();
    }

    /**
     * 落一条 user 消息。
     *
     * <p>顺带把会话标题补上（**只在还是空的时候**）：标题是"这个会话在聊什么"的唯一线索，
     * 而首问就是最合适的来源。已在有标题时不覆盖 —— `T-802` 若做自动起标题，
     * 它的结果不该被后一条提问冲掉。
     */
    @Transactional
    public long appendUser(long sessionId, String content) {
        SysChatSession session = touch(sessionId);
        if (session != null && (session.getTitle() == null || session.getTitle().isBlank())) {
            session.setTitle(truncate(content, TITLE_MAX));
        }
        return save(sessionId, "user", content, null, null, null, null, null, null);
    }

    /**
     * 落一条 assistant 消息。
     *
     * @param outcome 模型元数据；**可能是 {@code null}**（生成中途失败时没有元数据）
     * @param traceId 当次检索的 traceId —— 回答与"哪些分片支撑了它"的关联键
     * @return 消息 id，即 `done.messageId`。发的是它的**字符串形式**（前端类型是 string）
     */
    @Transactional
    public long appendAssistant(long sessionId, String content, Long modelId, String modelName,
                                ChatDtos.TokenUsage usage, String finishReason, String traceId) {
        touch(sessionId);
        return save(sessionId, "assistant", content, modelId, modelName,
                traceId,
                usage == null ? null : usage.prompt(),
                usage == null ? null : usage.completion(),
                finishReason);
    }

    /** 刷新会话的最近活动时间 —— 列表按它倒序，不用去扫消息表。 */
    private SysChatSession touch(long sessionId) {
        SysChatSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null) {
            session.setUpdatedAt(LocalDateTime.now());
            sessionRepository.save(session);
        }
        return session;
    }

    private long save(long sessionId, String role, String content, Long modelId, String modelName,
                      String traceId, Integer tokenPrompt, Integer tokenCompletion,
                      String finishReason) {
        SysChatMessage message = new SysChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setModelId(modelId);
        message.setModelName(modelName);
        message.setTraceId(traceId);
        message.setTokenPrompt(tokenPrompt);
        message.setTokenCompletion(tokenCompletion);
        message.setFinishReason(finishReason);
        message.setCreatedAt(LocalDateTime.now());
        return messageRepository.save(message).getId();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }
}
