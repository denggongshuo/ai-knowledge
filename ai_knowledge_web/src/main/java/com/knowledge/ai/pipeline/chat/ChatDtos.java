package com.knowledge.ai.pipeline.chat;

import java.util.List;

/**
 * M08 问答的**线格式**（`T-800` / `T-801`）—— 与 `10-接口约定汇总.md` §9 逐字段一致。
 *
 * <h3>为什么这里要另起一套类型，不直接吐后端的 {@code RetrievalTrace}</h3>
 * <p>两个类型**同名不同型**：后端那份是 26 个字段的**内部留痕**（含阈值、参数、
 * 降级说明、可见 doc 集合大小等"给排查用"的东西），而 §9 那份是前端
 * {@code src/types/index.ts:123} 渲染检索详情面板要的 **7 个数 + stages + chunks**。
 * 二者连"阶段划分"都不一样（见 {@link TraceStage}）。把内部类型直接序列化出去，
 * 前端拿到的是一堆它不认识的字段 + 缺它要的字段 —— `10:369` 把这种不符列为
 * **P0「字段不符会直接白屏」**。
 *
 * <p>所以本批的口径是：**内部 26 字段不动**（它是 `T-707` 打点表的行形状），
 * M08 侧加这一层适配。代价是两套类型并存 —— 映射规则集中在
 * {@link ChatWireMapper}，并由 `10` §14 的映射表登记。
 */
public final class ChatDtos {

    private ChatDtos() {
    }

    /**
     * 问答请求体。
     *
     * <p>字段与前端 {@code request.ts:238} 逐字一致 —— 注意是 **`message`** 而不是 `query`，
     * 且**没有 `kbIds`**（检索范围由服务端的可见范围决定，不由前端指定）。
     *
     * @param message       提问原文
     * @param sessionId     会话 id；不传 = 新会话（§9「前端 V2 尚未传」）
     * @param modelId       对话模型 id；与会话绑定不一致时**服务端开新会话**（`G5-10`）
     * @param attachmentIds 图片附件 id；`T-512`（S6）之前**收下但不处理**
     * @param useVision     本轮是否把原图直接传给模型；同上，本批恒不生效
     */
    public record ChatRequest(String message, Long sessionId, Long modelId,
                              List<String> attachmentIds, Boolean useVision) {
    }

    /**
     * 引用来源的一条（§9 `retrieval.chunks[]`）。
     *
     * <p>⚠️ {@code chunkId} 在**前端类型里是 number**（`types/index.ts:132`），
     * 而后端 {@code RetrievedChunk#chunkId} 是 String（Milvus 主键是字符串字段）。
     * 这里做一次转换，转换失败的分片**整条丢掉并留痕** —— 见 {@link ChatWireMapper}。
     *
     * @param docTitle 文档标题。⚠️ 检索链路本身**不带**它（那边只有 `titlePath` 标题路径），
     *                 由 {@link ChatWireMapper} 按 `docId` 一次批量查回
     */
    public record ChunkRef(long chunkId, long docId, String docTitle, double score, String source) {
    }

    /**
     * 链路的一"段"（§9 `trace.stages[]`）。
     *
     * <p>{@code name} 是**中文展示名**，前端直接渲染，不做映射 —— 所以取值由
     * {@link ChatWireMapper} 冻结，改文案就等于改前端显示。
     */
    public record TraceStage(String name, long costMs) {
    }

    /**
     * 链路明细（§9 `retrieval.trace`）。
     *
     * <h3>前 7 个数为什么必须有，而其中两个首包必然不真</h3>
     * <p>{@code llmGeneration}（生成耗时）与含生成的 {@code totalMs} 在**首包发出的那一刻
     * 还不存在** —— 生成是首包之后才发生的事。这不是实现偷懒，是时序。
     * 本批**不填假数字**（填 0 就是《02》点名的"静默降级"），改为：
     * <ul>
     *   <li><b>首包</b>：{@code llmGeneration = 0}、{@code totalMs} = **检索**端到端，
     *       让引用角标与面板先渲染（`G5-7` 要的就是这个）；</li>
     *   <li><b>终包</b>（`done` 之前再发一次）：两个数都换成真值。</li>
     * </ul>
     * 前端 {@code onRetrieval} 是**幂等覆盖**（`ChatView.vue:259` 直接赋值），
     * 所以多发一次不需要它改任何代码 —— 这也是选"补发终包"而不是"往 `done` 里塞字段"
     * 的原因：后者要改冻结的前端。
     *
     * @param queryAnalysis 「查询解析」耗时。⚠️ 后端**没有**这一步，恒 0 ——
     *                      不拿"可见范围耗时"冒充它（那是`可见范围`那一段的事）
     * @param graphUsed     图谱旁路是否命中。本批恒 {@code false}（`T-C00` 未实现）
     * @param graphCostMs   图谱旁路耗时；恒 {@code null}
     * @param graphFallback 图谱旁路是否降级；恒 {@code null}
     */
    public record TraceWire(long queryAnalysis,
                            long embed,
                            long vectorRecall,
                            long bm25Recall,
                            long rerank,
                            long llmGeneration,
                            long totalMs,
                            List<TraceStage> stages,
                            List<ChunkRef> chunks,
                            boolean graphUsed,
                            Long graphCostMs,
                            Boolean graphFallback) {
    }

    /**
     * 首包 / 终包（§9 `event: retrieval`）。
     *
     * <p>⚠️ {@code chunks} 与 {@code trace.chunks()} **必须是同一份内容**（`G5-9`）——
     * 前端用顶层那份渲染引用角标、用 trace 那份渲染链路面板。由
     * {@link ChatWireMapper} 在构造时把同一个 {@code List} 实例放进两处来保证。
     *
     * @param kbCount 本次命中的知识库数（前端直接显示）
     */
    public record RetrievalEvent(List<ChunkRef> chunks, TraceWire trace, int kbCount) {
    }

    /** §9 `event: delta` —— 一小段增量正文。 */
    public record DeltaEvent(String content) {
    }

    /**
     * token 用量（§9 `done.tokenUsage`）。
     *
     * <p>键名取自前端 mock 的既有形状（`mock/index.ts:786` 的
     * {@code {prompt, completion, total}}），不是 Spring AI 的 `Usage` 原样 ——
     * mock 就是前端**已经按它渲染**的那个形状。
     *
     * @param prompt     提示词 token；模型未回报时为 {@code null}（不填 0）
     * @param completion 生成 token；同上
     * @param total      合计；同上
     */
    public record TokenUsage(Integer prompt, Integer completion, Integer total) {
    }

    /**
     * §9 `event: done` —— 一轮问答的收尾。
     *
     * <p>⚠️ {@code messageId} 在前端类型里是 **string**（`request.ts:213`），
     * 而后端落的是自增 BIGINT。这里发字符串形式，`T-807` 的 `/chat/feedback`
     * 也按字符串收 —— 别在两边各转一次。
     *
     * @param usedVision 本轮是否真的把原图传给了模型。`T-512`（S6）之前恒 {@code false}
     */
    public record DoneEvent(String messageId,
                            Long modelId,
                            String modelName,
                            TokenUsage tokenUsage,
                            String finishReason,
                            boolean usedVision) {
    }

    /**
     * §9 **未登记**、但前端已在分发的事件（`request.ts:276`）。
     *
     * <p>本批登记进 §9：生成中途出错时发它，前端会把它追加到正文并弹提示
     * （`ChatView.vue:289`）。没有它的话，出错只能靠"流突然断了"表达 ——
     * 而用户看到的是**句号不完整的答案**，不知道是没说完还是说完了。
     */
    public record ErrorEvent(String message) {
    }

    // ==================================================================
    // 会话管理（`T-802` 余下半边 · `S5-M08-B`）
    // ==================================================================

    /**
     * 会话列表的一行（{@code GET /chat/sessions}）。
     *
     * <h3>字段形状以 mock 为准，不是以"后端方便"为准</h3>
     * <p>{@code mock/index.ts:71} 立了一条规矩：**后端就绪后切真实接口，前端零改动**。
     * 前端 {@code types/index.ts:191} 的 {@code ChatSessionItem} 已经把形状定死了，
     * 而 {@code ChatView.vue:341-346} 是**逐字段原样渲染**的（{@code {{ s.time }}} /
     * {@code {{ s.count }} 条} / {@code {{ s.modelName }}}）。所以：
     * <ul>
     *   <li>{@link #id} 是**字符串** —— 前端类型是 {@code string}，后端主键是 {@code BIGINT}。
     *       与 {@code DoneEvent#messageId} 同一条口径：发它的字符串形式，**别让两边各转一次**；</li>
     *   <li>{@link #time} 是**已经格式化好的展示串**（{@code 10:24} / {@code 昨天} / {@code 周二}），
     *       **不是时间戳** —— 前端不做格式化，发时间戳过去那一行就是空的；</li>
     *   <li>{@link #count} 是**消息条数**，前端直接拼「N 条」。</li>
     * </ul>
     *
     * <h3>{@code modelId} 与 {@code modelName} 为什么可能来自两个地方</h3>
     * <p>需求 `09:124` 要的是「每个会话显示**所用**模型，便于回看与归因」。
     * 而 {@code sys_chat_session} 只存了**绑定**的 {@code model_id}（配置），没存名字 ——
     * 按 id 反查 {@code sys_model_config} 当前**恒为空**（`汇总.md` §3 的 `#15`，
     * 模型配置尚未接进 DB）。所以：
     * <ul>
     *   <li>{@link #modelName} 取该会话**最后一条 assistant 消息**的 {@code model_name}
     *       —— 那是 {@code done} 事件如实回带的真实模型名（`T-808` 的落点），既有值、
     *       又恰好就是「所用」那个；</li>
     *   <li>{@link #modelId} 优先取同一条消息的实际 {@code model_id}，**没有 assistant 消息时**
     *       回落到会话绑定的 {@code model_id}。</li>
     * </ul>
     * 两者都为 {@code null} 时前端 {@code v-if="s.modelName"} 自然不显示那一行 ——
     * 不是"显示成空字符串"，是**整行不出现**。
     *
     * @param id    会话 id（**字符串形式**，见上）
     * @param title 会话标题（首个提问的截断；`T-802` 若做自动起标题再改）
     * @param time  最近活动的展示串，由服务端格式化
     * @param count 该会话的消息条数
     */
    public record ChatSessionItem(String id, String title, String time, long count,
                                  Long modelId, String modelName) {
    }

    /**
     * 会话列表响应。
     *
     * <p>⚠️ 形状是 {@code { list, total }}（`10-接口约定汇总.md:443` 与 mock 一致），
     * **不是**项目通用的 {@code PageResult}（那个多带 {@code page/size}）。
     * 前端类型是 {@code { list: ChatSessionItem[]; total: number }} ——
     * 多出来的字段虽不致命，但既然 §14 点了名，就按 §14 给。
     */
    public record SessionPage(List<ChatSessionItem> list, long total) {
    }

    /**
     * 一条历史引用（{@code GET /chat/sessions/{id}/messages} 的 {@code citations[]}）。
     *
     * <p>字段与 {@code mock/index.ts:717} 的 {@code citations} 逐字一致。
     *
     * <p>⚠️ 它表达的是**「这一轮喂给模型的分片」**，不是"答案真的引用了哪几条"
     * —— {@code sys_retrieval_trace_chunk.cited} 本批**恒为 NULL**（"被引用"要解析答案角标，
     * 属 `T-804`）。与实时 `retrieval` 事件的 {@code chunks[]} **同源同口径**。
     *
     * @param index 角标序号，从 1 起（= 检索名次）
     */
    public record Citation(int index, long docId, String docTitle, long chunkId,
                           double score, String source) {
    }

    /**
     * 消息列表的一行。
     *
     * <p>⚠️ {@link #createdAt} 是**毫秒时间戳数字**，不是格式化字符串 ——
     * mock 里就是 {@code Date.now() - 3600_000} 这样的数（{@code mock/index.ts:700}）。
     * 与本类里 {@code ChatSessionItem#time} 的"已格式化串"**不是一回事**，
     * 别因为都在聊天域就统一成一种写法。
     *
     * @param id          消息 id（**字符串形式**，同 {@code ChatSessionItem#id}）
     * @param attachments 图片附件。`T-805`（图片附件）未落地 ⇒ **恒为空列表** ——
     *                    发空数组而不是省掉这个字段：前端按数组遍历，缺字段要写判空
     */
    public record ChatMessageItem(String id, String role, String content, long createdAt,
                                  Long modelId, String modelName,
                                  List<Citation> citations, List<Object> attachments) {
    }

    /** 消息列表响应。形状同 {@link SessionPage}（{@code {list, total}}）。 */
    public record MessagePage(List<ChatMessageItem> list, long total) {
    }
}
