package com.knowledge.ai.service;

import com.knowledge.ai.core.ai.ModelClientRegistry;
import com.knowledge.ai.core.ai.ModelConfigJson;
import com.knowledge.ai.core.ai.ModelConfigSpec;
import com.knowledge.ai.core.enums.ModelType;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.security.LoginUser;
import com.knowledge.ai.core.trace.TraceContext;
import com.knowledge.ai.entity.SysModelConfig;
import com.knowledge.ai.pipeline.chat.ChatDtos;
import com.knowledge.ai.pipeline.chat.ChatSink;
import com.knowledge.ai.pipeline.chat.ChatWireMapper;
import com.knowledge.ai.pipeline.chat.CitationParser;
import com.knowledge.ai.pipeline.chat.StreamingChatCaller;
import com.knowledge.ai.pipeline.retrieve.RetrievalResult;
import com.knowledge.ai.pipeline.retrieve.RetrievalService;
import com.knowledge.ai.pipeline.retrieve.RetrievalTrace;
import com.knowledge.ai.pipeline.retrieve.RetrievalTraceWriter;
import com.knowledge.ai.pipeline.retrieve.RetrievedChunk;
import com.knowledge.ai.pipeline.vision.VisionResult;
import com.knowledge.ai.repository.SysModelConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** `T-806` 的服务端能力闸要读 `capabilities`（见 {@link #requireVisionCapable()}）。 */
    private final ModelClientRegistry modelClientRegistry;
    private final SysModelConfigRepository modelConfigRepository;

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
        //
        // `T-806`：`useVision=true` 时**改走"直接读原图"**（需求 `09` §4.7）——
        // 不调 VL、query 就是原问题、原图随提示词一起给对话模型。两条路的分岔
        // 收敛在下面这一个方法里（调用方不必知道走的是哪条）。
        Attachments attachments;
        try {
            attachments = understandAttachments(user, request, sink, question);
        } catch (BizException ex) {
            // 附件不存在 / 不是你的：**不进链路**（§4.10 第 1 行的同一条处置），
            // 而不是"跳过这张图继续答" —— 静默忽略图片是 §4.10 点名的最糟处理
            //
            // 🔴 但"重新上传"这句**只对附件本身的问题成立**。同一个 catch 现在还会接住
            //    `requireVisionCapable` 抛的能力错误（"模型不支持视觉"），
            //    而给它接一句"请重新上传该图片"是**指错方向** —— 用户会去重传图片、
            //    问题照旧。`S7-M06-C` 施工时被一条判据当场逮到（见计划 §8）。
            //    判据：`NOT_FOUND`（附件）才有那句；能力错误（`PARAM_INVALID`）原样给出。
            log.warn("[问答] 附件校验未通过：userId={} 原因={}", user.getUserId(), ex.getMessage());
            String hint = ex.getErrorCode() == ErrorCode.NOT_FOUND
                    ? " —— 请重新上传该图片后再发送。"
                    : "";
            sink.error(ex.getMessage() + hint);
            return;
        }

        RetrievalResult result = retrievalService.search(user, attachments.query(), null);
        Map<Long, String> docTitles = wireMapper.loadDocTitles(result.chunks());

        sink.retrieval(wireMapper.firstPacket(result, docTitles));

        StringBuilder answer = new StringBuilder();
        Long llmMillis = null;
        StreamingChatCaller.Outcome outcome = null;
        String finishReason = FINISH_EMPTY_RECALL;
        boolean generated = false;
        // 默认空集（不是 null）：空召回与生成失败都会走到收尾，那时"没有引用"是事实
        Set<Integer> citedRanks = Set.of();

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
                // `T-D07`/`T-D08`：思考过程单独走 reasoning 通道。
                // ⚠️ 关闭思考（request.reasoning()==false）时传 **null** —— 与"模型不产思考"
                //    走同一条零事件路径（回调根本不取 reasoning），而不是"取到了再丢掉"，
                //    这样"关了还漏一帧"在结构上不可能发生。
                // ⚠️ 断开时同 delta：只是不再发，仍把这一轮跑完（耗时/用量要记全）
                outcome = streamingChatCaller.stream(prompt(question, result.chunks()),
                        attachments.images(),
                        piece -> {
                            answer.append(piece);
                            // ⚠️ 客户端已断开时**只是不再发**，循环照常跑完：
                            // 中断 Flux 要处理 Reactor 的取消语义，而代价是这次生成的
                            // 耗时/用量就记不全了 —— 记录完整比省这一次调用更要紧
                            if (sink.alive()) {
                                sink.delta(piece);
                            }
                        },
                        reasoningEnabled(request)
                                ? thought -> {
                                    // 思考**不进 answer**：它不参与引用角标解析，也不落库
                                    //（本批范围：只做"显示 + 可折叠"）。
                                    if (sink.alive()) {
                                        sink.reasoning(thought);
                                    }
                                }
                                : null);

                llmMillis = elapsedMillis(genStart);
                finishReason = outcome.finishReason();
                // `T-804`：**角标只在"真的调过模型"的那条路上才有意义** ——
                // 空召回的兜底话术里没有角标，`chunkCount` 也是 0，
                // 走同一条解析只会得到空集（无害，但没必要）
                citedRanks = CitationParser.parse(answer.toString(), result.chunks().size());
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
                    answer.toString(), outcome, llmMillis, finishReason, generated, startNanos,
                    citedRanks, attachments.usedVision());
        }
    }

    /**
     * 本轮是否**采集并下发思考过程**（`T-D08` · 用户 2026-10-02 需求：默认开、可关）。
     *
     * <p>⚠️ 只认显式的 {@code false} 才算关：{@code null}（前端没传 / 旧客户端）按**默认开**。
     * 反过来写（"{@code TRUE} 才算开"）会让所有既有请求在升级后**悄悄失去思考过程** ——
     * 而页面不会报错，只是思考区再也不出现。
     */
    private static boolean reasoningEnabled(ChatDtos.ChatRequest request) {
        return request.reasoning() == null || request.reasoning();
    }


    /**
     * 图片处理（`T-907` + `T-806` · 需求 `09` §4.9/§4.5/§4.7）—— **两条路的分岔点**。
     *
     * <h3>两条路互斥，而不是叠加</h3>
     * <ul>
     *   <li><b>默认链路</b>（`useVision != true`）：调 VL 出描述 → 合成 query → 检索 → 生成。
     *       原图**不进**对话模型。</li>
     *   <li><b>兜底链路</b>（`useVision == true`，§4.7）：**不调 VL**、query 就是原问题、
     *       原图随提示词一起给对话模型。</li>
     * </ul>
     * §4.7 的原文是「默认链路保持解耦与低成本，但精度上限没有丢 ——
     * **只在真正需要时才付 vision 的代价**」。所以若在 `useVision=true` 时仍先跑一遍 VL 描述，
     * 用户点这个按钮要**先白等 40~60 秒**（真 VL 实测，`#179`），而那段描述**根本没被用上**。
     *
     * <h3>🔴 能力闸放在服务端，不只在页面上</h3>
     * <p>前端 {@code ChatView.vue#canReadOriginal} 已按 {@code capabilities.vision} 置灰按钮，
     * 但**置灰不是约束** —— 任何人可以带 {@code useVision: true} 直接打这个端点。
     * §4.7 把前置写成一条规则，规则就要在服务端成立。
     *
     * <h3>为什么"不支持"是拒，而不是"退回描述链路"</h3>
     * <p>退回描述链路会**悄悄花掉 40~60 秒并给出一个不含图的答案**，而用户明确要求过看图 ——
     * 那正是 §4.10 点名的最糟处理（"用户以为模型看到了图，实际没有"）。
     * 拒的代价是用户多点一次，收益是"这件事可见"。
     *
     * <h3>事件顺序（默认链路）</h3>
     * <pre>
     *   attachment{DESCRIBING} × N   ← 逐张先报"正在理解"
     *   …（VL 调用，串行阻塞）…
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
     * 本批未做（做第二趟完整检索会让每次带图提问的检索耗时翻倍）。
     * ⚠️ **兜底链路不合成** —— 那时没有 VL 描述，query 就是原问题，逐字不变。
     *
     * @return 送检的 query + 本轮是否走原图 + 要随提示词给模型的图（**默认链路恒为空**）
     * @throws BizException 附件不存在 / 不是你的；或 `useVision` 而模型未声明支持视觉
     */
    private Attachments understandAttachments(LoginUser user, ChatDtos.ChatRequest request, ChatSink sink,
                                              String question) {
        List<String> ids = request.attachmentIds();
        if (ids == null || ids.isEmpty()) {
            return new Attachments(question, List.of(), false);
        }
        if (Boolean.TRUE.equals(request.useVision())) {
            requireVisionCapable();
            for (String id : ids) {
                // 这一条路上**不发** `DESCRIBING` —— 它说的是"VL 正在描述"，
                // 而本轮不调 VL。发了就是一句假进度，比不发更糟（让人以为在等 VL）
                sink.attachment(ChatDtos.AttachmentEvent.reading(id));
            }
            List<ChatAttachmentService.Original> originals =
                    chatAttachmentService.originals(user.getUserId(), ids);
            List<StreamingChatCaller.ChatImage> images = new ArrayList<>(originals.size());
            for (ChatAttachmentService.Original original : originals) {
                images.add(new StreamingChatCaller.ChatImage(original.bytes(), original.mime()));
            }
            return new Attachments(question, images, true);
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
        String query = summary.isEmpty() ? question : question + " " + summary;
        return new Attachments(query, List.of(), false);
    }

    /**
     * `useVision` 的前置：当前**启用的对话模型**必须声明支持视觉（需求 `09` §4.7）。
     *
     * <p>⚠️ 读的是 {@code sys_model_config.capabilities.vision}，**不是** VL 模型的能力 ——
     * §4.7 的原文是「当前**对话模型** {@code capabilities.vision = true}」。
     * 两者是不同的模型、不同的配置行：VL 那个是"生成描述"的，这个才是"看原图"的。
     *
     * @throws BizException 该模型未声明 vision（含"查不到那一行"与"引导期没有数字 id"两种情形）
     */
    private void requireVisionCapable() {
        ModelConfigSpec spec = modelClientRegistry.activeSpec(ModelType.CHAT);
        if (ModelConfigJson.readVision(capabilitiesOf(spec.id()))) {
            return;
        }
        throw BizException.of(ErrorCode.PARAM_INVALID,
                "当前对话模型「" + spec.model() + "」未声明支持视觉（capabilities.vision），"
                        + "因此不能把原图直接交给它。请去掉「直接读原图」后重试，"
                        + "或到「模型配置」页把该模型的「视觉」能力打开（前提是它确实支持）。");
    }

    /**
     * 取某条模型配置的 `capabilities` JSON。
     *
     * <p>⚠️ **查不到就返回 {@code null}** ⇒ 读侧按默认值 `false` 处理。
     * 不抛：能力闸的失败形态是"拒绝并把原因说清"，而不是一个 500 ——
     * 后者会让用户以为系统坏了，而不是"这个模型不支持"。
     */
    private String capabilitiesOf(String specId) {
        if (specId == null) {
            return null;
        }
        try {
            return modelConfigRepository.findById(Long.valueOf(specId.strip()))
                    .map(SysModelConfig::getCapabilities)
                    .orElse(null);
        } catch (NumberFormatException ex) {
            // 引导配置的 id 是 `bootstrap-chat`（非数字），它没有 `capabilities` 行 ——
            // 与 `#161`/`#138` 那族"引导期没有数字主键"同一条限制
            return null;
        }
    }

    /** {@link #understandAttachments} 的产出：送检 query + 本轮是否读原图 + 随提示词的图。 */
    private record Attachments(String query, List<StreamingChatCaller.ChatImage> images, boolean usedVision) {
    }

    /**
     * 收尾：终包 → 落 assistant 消息 → 落打点 → {@code done}。
     *
     * <p>顺序是有意的：**先发终包再落库**，用户看到的时间线不会因为写库而卡一下；
     * 而 `done` 必须最后发 —— 它带着 {@code messageId}，那个 id 要等消息行落完才有。
     *
     * @param citedRanks 答案里实际出现的角标编号（`T-804`）；**空集 = 没解析出角标**
     *                   ⇒ 打点明细的 `cited` 写 {@code NULL} 而不是 0（见 `CitationParser`）
     */
    private void finish(Long userId, RetrievalResult result, Map<Long, String> docTitles, ChatSink sink,
                        long sessionId, String traceId, String answer,
                        StreamingChatCaller.Outcome outcome, Long llmMillis,
                        String finishReason, boolean generated, long startNanos,
                        Set<Integer> citedRanks, boolean usedVision) {
        long totalMillis = elapsedMillis(startNanos);

        sink.retrieval(wireMapper.finalPacket(result, docTitles,
                llmMillis == null ? 0L : llmMillis, totalMillis));

        long assistantId = chatSessionService.appendAssistant(sessionId, answer,
                outcome == null ? null : outcome.modelId(),
                outcome == null ? null : outcome.modelName(),
                outcome == null ? null : outcome.tokenUsage(),
                finishReason, traceId, usedVision);

        traceWriter.write(new RetrievalTraceWriter.TraceWrite(traceId, userId, sessionId, assistantId,
                result,
                // ⚠️ 没生成就给 null（空召回 / 生成前失败）—— 填 0 会把"没生成"
                // 记成"生成耗时 0ms"，那是 `G5-12` 要读的列
                llmMillis,
                totalMillis,
                citedRanks));

        sink.done(new ChatDtos.DoneEvent(String.valueOf(assistantId),
                outcome == null ? null : outcome.modelId(),
                outcome == null ? null : outcome.modelName(),
                outcome == null ? null : outcome.tokenUsage(),
                finishReason,
                usedVision,
                // `T-804`：**按角标序**的引用条目 —— 与 `retrieval.chunks[]`（按检索名次）
                // 是两份不同的清单。前端拿它渲染"答案真的引用了哪几条"，
                // 点角标能定位到分片；而 `chunks[]` 渲染的是"系统找到了哪些材料"。
                wireMapper.citations(citedRanks, result.chunks(), docTitles)));

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
                .append("如果资料中没有足够信息，请明确回答「资料中未找到相关内容」，不要凭常识补充或编造。\n")
                // 🔴 `T-804`：角标必须在 prompt 里被**要求**过。
                //    此前从头到尾没提过角标，模型**没有理由**输出 `[1]` ——
                //    而前端那个 `[1][2]` 是按 `i + 1` 自己拼的（渲染的是"喂给了模型
                //    哪些分片"，不是"答案引用了哪几条"）。改这一行会**影响全部问答**
                //    （含无引用的普通提问），故它单独一条判据（见计划 §6+7 判据 2/6）。
                .append("凡是用到了某条资料的论断，请在句末标注该资料的编号，形如 [1]；")
                .append("同一句用了多条就依次标注，形如 [1][3]。没有资料支撑的话不要标注。\n\n")
                .append("【资料】\n");
        int index = 1;
        for (RetrievedChunk chunk : chunks) {
            // ⚠️ 编号用**半角方括号**（与角标同形，这是有意的：模型看到 `[1]` 才会照着打 `[1]`）。
            //    正因如此，标题路径**必须换一种括号** —— 两处都用 `[...]` 时，
            //    一个形如 `[12]` 的标题路径会被 `CitationParser` 解析成"引用了第 12 条"，
            //    而那是一条**假引用**，且它在页面上看起来完全正常。
            sb.append("[").append(index++).append("] ");
            if (chunk.titlePath() != null && !chunk.titlePath().isBlank()) {
                sb.append("（").append(chunk.titlePath().replace('\n', ' ')).append("）");
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
