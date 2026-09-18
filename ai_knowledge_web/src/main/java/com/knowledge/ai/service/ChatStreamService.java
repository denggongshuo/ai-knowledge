package com.knowledge.ai.service;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.trace.TraceContext;
import com.knowledge.ai.pipeline.chat.ChatDtos;
import com.knowledge.ai.pipeline.chat.ChatSink;
import com.knowledge.ai.pipeline.chat.ChatWireMapper;
import com.knowledge.ai.pipeline.chat.StreamingChatCaller;
import com.knowledge.ai.pipeline.retrieve.RetrievalResult;
import com.knowledge.ai.pipeline.retrieve.RetrievalService;
import com.knowledge.ai.pipeline.retrieve.RetrievalTrace;
import com.knowledge.ai.pipeline.retrieve.RetrievalTraceWriter;
import com.knowledge.ai.pipeline.retrieve.RetrievedChunk;
import com.knowledge.ai.pipeline.vision.VisionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 一轮问答的编排（`T-800` · `T-801` · `T-708`）。
 *
 * <h3>事件序列（§9）</h3>
 * <pre>
 *   retrieval(首包) → delta* → retrieval(终包) → done
 * </pre>
 * 不带图片时首包必须是 {@code retrieval}（`G5-7`）。**终包是本批补的** ——
 * 见 {@link ChatWireMapper#finalPacket}：首包发出时生成还没开始，
 * {@code llmGeneration} 与含生成的 {@code totalMs} 必然没有真值。
 * 前端 {@code onRetrieval} 是幂等覆盖，多发一次不需要它改任何代码。
 *
 * <h3>{@code T-708} 的分流：空召回与未命中是**两件事**</h3>
 * <ul>
 *   <li><b>空召回</b>（{@code chunks} 为空）：**不调大模型**，直接给可解释的兜底话术。
 *       让模型在"没有任何资料"的情况下开口，等于请它编 —— 《02》把这种
 *       「看起来答了、其实没有依据」列为比报错更坏的形态。</li>
 *   <li><b>未命中</b>（有分片但最高分低于 {@code missThreshold}）：**照常调模型**，
 *       只是把 {@code miss_flag} 落库（`G5-11` 后半）。「分低」不等于「没有」——
 *       把它也拦掉会把大量本可回答的问题变成兜底话术。</li>
 * </ul>
 *
 * <h3>兜底话术必须区分「还没有」与「真没有」（`汇总.md` §3 的 `#114`）</h3>
 * <p>Milvus 在 {@code BOUNDED} 一致性下有**秒级写可见窗口**：刚写完的分片立刻检索
 * 可能少几条，隔一秒再查又回来了。所以"什么都没搜到"至少有三种成因，
 * 说成同一句话会让用户做出错误的下一步：
 * <ul>
 *   <li>这个库**确实还没有**文档 → 让他去上传；</li>
 *   <li>文档在，但索引**还没建好 / 刚建好还没可见** → 让他稍等重试；</li>
 *   <li>正在**重建索引**（`T-709` 已降级为关键词检索）→ 告诉他精度会降、重建完自动恢复。</li>
 * </ul>
 * 判别只用 trace 里**已经有的**事实（可见文档数、降级说明），不新造状态。
 *
 * <h3>收尾落库放在 {@code finally} 里，不是"顺手"</h3>
 * <p>`G5-13` 要求「每次提问都落 Top1 相似度（无遗漏）」。客户端中途断开、生成报错、
 * 兜底话术 —— 这些路径**都发生过一次真实的提问**，都必须留痕。
 * {@link ChatSink} 的实现方负责把发送失败就地吃掉，所以这条链能一直走到收尾。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStreamService {

    /** 模型未回报结束原因时的兜底值（空召回不调模型，也走它）。 */
    private static final String FINISH_EMPTY_RECALL = "empty_recall";

    private final RetrievalService retrievalService;
    private final ChatSessionService chatSessionService;
    private final StreamingChatCaller streamingChatCaller;
    private final ChatWireMapper wireMapper;
    private final RetrievalTraceWriter traceWriter;
    private final ChatAttachmentService chatAttachmentService;

    /**
     * 跑一轮问答。
     *
     * <p>**不抛业务异常给上层**：SSE 响应头已经发出去了，此时再让全局异常处理器写 JSON，
     * 前端会把这段 JSON 当 SSE 帧解析 —— 结果是界面一直卡在"生成中"。
     * 所有可预期的失败都走 {@code event: error}。
     */
    public void chat(LoginUser user, ChatDtos.ChatRequest request, ChatSink sink) {
        String question = request.message() == null ? "" : request.message().strip();
        if (question.isEmpty()) {
            sink.error("请先输入问题");
            return;
        }

        long startNanos = System.nanoTime();
        String traceId = TraceContext.current();

        long sessionId = chatSessionService.resolve(user.getUserId(), request.sessionId(), request.modelId());
        chatSessionService.appendUser(sessionId, question);

        // `T-907`：图片理解**串行阻塞在检索之前**（需求 `09` §4.9 的时序）。
        // 它必须在 appendUser 之后 —— 用户消息落的是**原问题**，不是合成 query
        String searchQuery;
        try {
            searchQuery = understandAttachments(user, request, sink, question);
        } catch (BizException ex) {
            // 附件不存在 / 不是你的：**不进链路**（§4.10 第 1 行的同一条处置），
            // 而不是"跳过这张图继续答" —— 静默忽略图片是 §4.10 点名的最糟处理
            log.warn("[问答] 附件校验未通过：userId={} 原因={}", user.getUserId(), ex.getMessage());
            sink.error(ex.getMessage() + " —— 请重新上传该图片后再发送。");
            return;
        }

        RetrievalResult result = retrievalService.search(user, searchQuery, null);
        Map<Long, String> docTitles = wireMapper.loadDocTitles(result.chunks());

        sink.retrieval(wireMapper.firstPacket(result, docTitles));

        StringBuilder answer = new StringBuilder();
        Long llmMillis = null;
        StreamingChatCaller.Outcome outcome = null;
        String finishReason = FINISH_EMPTY_RECALL;
        boolean generated = false;

        try {
            if (result.empty()) {
                // `G5-11` 前半：**一条都没有时不调大模型**。这里刻意不 catch ——
                // 兜底话术是我们自己写死的文本，它没有失败的可能
                String note = emptyRecallNote(result.trace());
                answer.append(note);
                sink.delta(note);
            } else {
                generated = true;
                long genStart = System.nanoTime();
                outcome = streamingChatCaller.stream(prompt(question, result.chunks()),
                        piece -> {
                            answer.append(piece);
                            // ⚠️ 客户端已断开时**只是不再发**，循环照常跑完：
                            // 中断 Flux 要处理 Reactor 的取消语义，而代价是这次生成的
                            // 耗时/用量就记不全了 —— 记录完整比省这一次调用更要紧
                            if (sink.alive()) {
                                sink.delta(piece);
                            }
                        });
                llmMillis = elapsedMillis(genStart);
                finishReason = outcome.finishReason();
            }
        } catch (RuntimeException ex) {
            // 生成失败**不吞**：用户必须看见"这次没答上来"以及能做什么（`G4-3`）
            log.warn("[问答] 生成失败：traceId={} err={}", traceId, ex.getMessage());
            finishReason = "error";
            String note = "生成回答时出错：" + ex.getMessage() + " —— 请稍后重试；若持续失败，请检查对话模型是否可用。";
            answer.append(note);
            sink.error(note);
        } finally {
            finish(user.getUserId(), result, docTitles, sink, sessionId, traceId,
                    answer.toString(), outcome, llmMillis, finishReason, generated, startNanos);
        }
    }

    /**
     * 图片理解（`T-907` · 需求 `09` §4.9/§4.5）。
     *
     * <h3>事件顺序</h3>
     * <pre>
     *   attachment{DESCRIBING} × N   ← 逐张先报"正在理解"
     *   …（VL 调用，1~3s，串行阻塞）…
     *   attachment{DONE} × N         ← 逐张报结果（成功与降级都发，由 degraded 区分）
     *   retrieval                    ← 此时 query 已含 searchSummary
     * </pre>
     * ⚠️ **带图时首包是 {@code attachment} 而不是 {@code retrieval}** ——
     * 这与 `G5-7`「首包必须是 retrieval」不冲突：那条门禁成立于**不带图片**的问答
     * （它要证的是"用户不必等生成就能看到引用角标"），而带图时描述在检索之前，
     * 先发 {@code retrieval} 就必然要编一个还没算出来的 query。两句话的条件不同。
     *
     * <h3>query 合成（§4.5 的 v1 口径：拼接）</h3>
     * <p>{@code searchQuery = 用户问题 + " " + searchSummary}。
     * ⚠️ 这带来一处**必须登记的偏差**：{@code sys_retrieval_trace.query} 从此在带图时
     * 存的是**合成 query**，而 `FR-SYS-03` §3.2 把那一列定义为「用户**原始**问法（不归一化）」。
     * 原始问题**没有丢** —— 它在 {@code sys_chat_message.content} 里（{@link ChatSessionService#appendUser}
     * 落的是原文）。§4.5 原本还要求「同时记录仅文字 query 与合成 query **两路**的召回结果」，
     * 本批未做（做第二趟完整检索会让每次带图提问的检索耗时翻倍，见计划 §3.4 假设 3）。
     *
     * @return 实际送去检索的 query（无附件时**就是原问题**，逐字不变）
     */
    private String understandAttachments(LoginUser user, ChatDtos.ChatRequest request, ChatSink sink,
                                         String question) {
        List<String> ids = request.attachmentIds();
        if (ids == null || ids.isEmpty()) {
            return question;
        }
        for (String id : ids) {
            sink.attachment(ChatDtos.AttachmentEvent.describing(id));
        }

        List<ChatAttachmentService.Described> described =
                chatAttachmentService.describe(user.getUserId(), ids, question);

        StringBuilder summary = new StringBuilder();
        for (ChatAttachmentService.Described item : described) {
            VisionResult result = item.result();
            sink.attachment(ChatDtos.AttachmentEvent.done(item.attachmentId(),
                    result.desc(), result.searchSummary(), result.vlModelName(), result.degraded()));
            if (result.hasDescription() && result.searchSummary() != null
                    && !result.searchSummary().isBlank()) {
                if (!summary.isEmpty()) {
                    summary.append(' ');
                }
                summary.append(result.searchSummary().strip());
            }
        }

        // 降级 / 无描述时 summary 为空 ⇒ 检索仍按原问题走。
        // 刻意不在那时拼一个空串：`"问题 "` 与 `"问题"` 对向量化是不同的输入，
        // 而它换不来任何好处（多一个尾随空格只会让排查时分不清是哪种情形）
        return summary.isEmpty() ? question : question + " " + summary;
    }

    /**
     * 收尾：终包 → 落 assistant 消息 → 落打点 → {@code done}。
     *
     * <p>顺序是有意的：**先发终包再落库**，用户看到的时间线不会因为写库而卡一下；
     * 而 `done` 必须最后发 —— 它带着 {@code messageId}，那个 id 要等消息行落完才有。
     */
    private void finish(Long userId, RetrievalResult result, Map<Long, String> docTitles, ChatSink sink,
                        long sessionId, String traceId, String answer,
                        StreamingChatCaller.Outcome outcome, Long llmMillis,
                        String finishReason, boolean generated, long startNanos) {
        long totalMillis = elapsedMillis(startNanos);

        sink.retrieval(wireMapper.finalPacket(result, docTitles,
                llmMillis == null ? 0L : llmMillis, totalMillis));

        long assistantId = chatSessionService.appendAssistant(sessionId, answer,
                outcome == null ? null : outcome.modelId(),
                outcome == null ? null : outcome.modelName(),
                outcome == null ? null : outcome.tokenUsage(),
                finishReason, traceId);

        traceWriter.write(new RetrievalTraceWriter.TraceWrite(traceId, userId, sessionId, assistantId,
                result,
                // ⚠️ 没生成就给 null（空召回 / 生成前失败）—— 填 0 会把"没生成"
                // 记成"生成耗时 0ms"，那是 `G5-12` 要读的列
                llmMillis,
                totalMillis));

        sink.done(new ChatDtos.DoneEvent(String.valueOf(assistantId),
                outcome == null ? null : outcome.modelId(),
                outcome == null ? null : outcome.modelName(),
                outcome == null ? null : outcome.tokenUsage(),
                finishReason,
                // `T-907` 已接图片理解（描述链路），但**「直接读原图」仍未做** ——
                // 那是需求 §4.7 的独立入口，前置是「当前对话模型 capabilities.vision = true」
                // 且只对该轮生效，属 `T-806`。本批如实回 false，不假装
                false));

        log.info("[问答] 完成：traceId={} 会话={} 分片={} 生成={} 耗时={}ms（其中生成 {}ms）",
                traceId, sessionId, result.chunks().size(),
                generated ? finishReason : "未调用模型", totalMillis,
                llmMillis == null ? "无" : llmMillis);
    }

    /**
     * 空召回的兜底话术 —— **按成因分岔**，不发同一句话。
     *
     * <p>判据只用 trace 里已有的事实，不新造状态：
     * {@code docScopeCount == 0} ⇒ 这个库确实没有可检索的文档；
     * 大于 0 却没有分片 ⇒ 文档在但索引还没可见（含 `#114` 的秒级窗口与"正在重建"）。
     */
    private static String emptyRecallNote(RetrievalTrace trace) {
        if (trace == null) {
            return "没有检索到相关内容 —— 请换个说法再试一次。";
        }
        if (trace.degraded()) {
            return "知识库正在重建索引，本次已降级为关键词检索，但依然没有匹配到相关内容。"
                    + "重建完成后会自动恢复完整的检索能力，请稍后重试。";
        }
        if (trace.kbCount() == 0) {
            return "没有可检索的知识库 —— 请先确认你已被授权访问某个知识库，或联系管理员开通。";
        }
        if (trace.docScopeCount() == 0) {
            return "所选知识库里还没有可检索的文档 —— 请先上传文档并等待解析、索引完成，然后再提问。";
        }
        return "知识库里已有 " + trace.docScopeCount() + " 篇文档，但目前没有匹配到相关内容。"
                + "如果这些文档是刚刚上传或正在重建索引，它们的索引可能还没建好"
                + "（也有可能是刚建立、还需要几秒才对检索可见）—— 请稍后重试；"
                + "若长时间如此，请换个说法或补充更具体的关键词。";
    }

    /**
     * 拼提示词。
     *
     * <p>**明确禁止编造**，并给出"没找到"时的标准说法：不给这句，模型倾向于
     * 拿捏着几分相关性硬答，而用户分不出"有依据"与"像有依据"。
     * 这与 `T-708` 空召回不调模型是同一条纪律的两个落点。
     */
    private static String prompt(String question, List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("请根据下面提供的资料回答问题。\n")
                .append("如果资料中没有足够信息，请明确回答「资料中未找到相关内容」，不要凭常识补充或编造。\n\n")
                .append("【资料】\n");
        int index = 1;
        for (RetrievedChunk chunk : chunks) {
            sb.append("(").append(index++).append(") ");
            if (chunk.titlePath() != null && !chunk.titlePath().isBlank()) {
                sb.append("[").append(chunk.titlePath().replace('\n', ' ')).append("] ");
            }
            sb.append(chunk.content()).append("\n\n");
        }
        sb.append("【问题】\n").append(question).append("\n");
        return sb.toString();
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
