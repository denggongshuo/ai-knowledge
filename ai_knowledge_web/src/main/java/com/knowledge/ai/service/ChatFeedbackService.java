package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.ChatFeedbackReason;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.entity.SysChatMessage;
import com.knowledge.ai.pipeline.chat.ChatDtos;
import com.knowledge.ai.repository.SysChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 问答反馈（`T-807` · 门禁 `G5-14`）—— 点赞 / 点踩 + 原因**落库**。
 *
 * <h3>为什么这条数据被反复点名「务必落库」</h3>
 * <p>需求 `07` §6.8 的原话是「答案下方的 复制 / 点赞 / 点踩 / 重新生成 是**评估检索质量的
 * 宝贵反馈数据**」，而 §6.8 的标题就是「**务必落库**」。它是运营页（第 11 页）点踩归因的
 * 唯一数据来源 —— 没有它，"答案不准确占 43%"这类结论无从产生。
 *
 * <h3>反馈是**可改**的：后写覆盖前写</h3>
 * <p>用户点错了想改成另一个原因，是正常操作。所以这里不是"追加一条"，是**覆盖三列**。
 * 代价是**丢变更历史**（看不出曾经点过赞）—— 如实登记在计划 §3.4 假设 3。
 * 换成独立表能留史，但那要多一张持久化产物（登记表 + 覆盖率闸 + 残留自检三处），
 * 而当前没有任何消费方需要"历史"——运营统计读的是**当前分布**。
 *
 * <h3>归属：消息 → 会话 → 人</h3>
 * <p>一条反馈挂在**别人的**答案上是没有意义的（运营归因会归错人），
 * 而且它会成为一个"能对任意 messageId 写入"的口子。校验与取数在同一条 SQL 里
 * （{@code SysChatMessageRepository#findOwned}），且**不区分**"不存在"与"不是你的"。
 *
 * <h3>权限码为什么是 {@code qa:ask} 而不是 {@code qa:feedback}</h3>
 * <p>{@code qa:feedback} 的语义是**「处理问答反馈」**——那是运营侧的处置权，
 * 而 `V2__seed_reference_data.sql` 里**`USER` 角色并没有它**。若把它挂在这里，
 * 普通用户就**点不了自己的赞**，而需求 `07` §6.8 明确要求答案下方有这个入口。
 * 所以本接口挂 {@code qa:ask}（能提问就能反馈自己那轮的答案），
 * 真正的作用域边界由**归属校验**提供；{@code qa:feedback} 留给 S8 的运营处置端点。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatFeedbackService {

    /** 点赞。 */
    public static final String LIKE = "like";

    /** 点踩。 */
    public static final String UNLIKE = "unlike";

    private final SysChatMessageRepository messageRepository;

    /**
     * 提交（或改写）一条反馈。
     *
     * @param messageId 消息 id 的字符串形式（前端拿到的 {@code done.messageId} 就是串）
     */
    @Transactional
    public ChatDtos.FeedbackResult submit(Long userId, ChatDtos.FeedbackRequest request) {
        Long messageId = parseId(request.messageId());
        if (messageId == null) {
            // 不给 SQL 传一个 null 主键去碰运气（那要靠 `id = null` 恒不命中的语义，
            // 而这条语义在换库/改查询时会静默失效）
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        String type = request.type() == null ? "" : request.type().strip().toLowerCase(Locale.ROOT);
        if (!LIKE.equals(type) && !UNLIKE.equals(type)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "反馈类型只能是 like（点赞）或 unlike（点踩），收到的是「"
                            + request.type() + "」");
        }

        String reason = request.reason() == null ? "" : request.reason().strip();
        if (LIKE.equals(type) && !reason.isEmpty()) {
            // 需求 `07` §6.8 写的是「**点踩时**可选原因」——点赞带原因说明前端拼错了请求，
            // 静默丢掉会让"前端一直在发一个后端不看的字段"永远没人发现
            throw BizException.of(ErrorCode.PARAM_INVALID, "点赞不需要填原因，请去掉 reason 后重试");
        }
        if (!reason.isEmpty() && !ChatFeedbackReason.isValidLabel(reason)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "点踩原因「" + reason + "」不在可选范围内；请从「答案不准确 / 没找到相关内容 / "
                            + "引用来源不对 / 回答太啰嗦 / 其他」中选择");
        }

        SysChatMessage row = messageRepository.findOwned(messageId, userId)
                // 与附件回看同一条口径：三种情形（不存在 / 不是你的 / 会话已删）**不区分**
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));

        if (!"assistant".equals(row.getRole())) {
            // 页脚那个入口挂在**回答**下面（`07` §6.8）。给提问点赞是"夸自己问得好"，
            // 运营侧也归不出因 —— 拦住而不是静默写进去
            throw BizException.of(ErrorCode.PARAM_INVALID, "只能对回答点赞或点踩，提问消息不支持反馈");
        }

        row.setFeedback(type);
        // 点赞把上一轮可能存在的点踩原因**清掉** —— 否则库里会出现
        // "feedback=like 且 feedback_reason=答案不准确"这种自相矛盾的行，
        // 而运营统计若只按 reason 分组（不看 feedback）会把它们算进负反馈
        row.setFeedbackReason(reason.isEmpty() ? null : reason);
        row.setFeedbackAt(LocalDateTime.now());
        messageRepository.save(row);

        log.info("[问答] 反馈已落库：userId={} messageId={} type={} reason={}",
                userId, messageId, type, reason.isEmpty() ? "无" : reason);
        return new ChatDtos.FeedbackResult(String.valueOf(messageId), type,
                reason.isEmpty() ? null : reason);
    }

    /**
     * 字符串 id → 数字。
     *
     * <p>解析不出**不报参数错误**，而是当成 {@code null} 让调用方走 {@code NOT_FOUND}：
     * 一个格式不对的 id 与一个不存在的 id，对调用方是同一件事 ——
     * 报"参数错误"等于承认"这个 id 的格式我们认识，只是不存在"。
     */
    private static Long parseId(String raw) {
        try {
            return raw == null ? null : Long.valueOf(raw.strip());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
