package com.knowledge.ai.service;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.entity.SysChatMessage;
import com.knowledge.ai.entity.SysChatSession;
import com.knowledge.ai.pipeline.chat.ChatDtos;
import com.knowledge.ai.repository.SysChatMessageRepository;
import com.knowledge.ai.repository.SysChatSessionRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /** 会话列表默认单页条数（前端不传 {@code size} 时用它）。 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 单页上限，与 {@code PageQuery#MAX_SIZE} 同值 —— 防止一次拉爆消息表。 */
    private static final int MAX_PAGE_SIZE = 200;

    /** {@link #displayTime} 里"今天"要显示的部分。 */
    private static final DateTimeFormatter TIME_OF_DAY = DateTimeFormatter.ofPattern("HH:mm");

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("M月d日");

    private static final DateTimeFormatter FULL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 星期几的中文名，索引 = {@code DayOfWeek#getValue() - 1}（周一是 1）。 */
    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private final SysChatSessionRepository sessionRepository;
    private final SysChatMessageRepository messageRepository;

    /**
     * 读 {@code sys_retrieval_trace_chunk}（分片明细）用。
     *
     * <p>⚠️ 这两张 {@code sys_retrieval_*} 表**没有实体类也没有仓储** ——
     * 写入侧 {@code RetrievalTraceWriter} 走的也是原生 SQL。这里沿用同一条路，
     * 不为了读一次就补一个只有本类在用的实体。
     */
    private final EntityManager entityManager;

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

    // ==================================================================
    // T-802 余下半边（S5-M08-B）：列表 / 消息列表 / 删除 / 导出
    // ==================================================================

    /**
     * 会话列表（含标题搜索）。
     *
     * <p><b>越权防护在 SQL 里</b>：{@code user_id} 是查询条件，不是取回来再筛。
     * 别人的会话在这条路径上**根本查不出来** —— 而不是查出来再判"无权限"
     * （后者会把"存在这个会话"这条信息泄露出去）。
     *
     * @param keyword 标题模糊；空/空白等同不传
     */
    @Transactional(readOnly = true)
    public ChatDtos.SessionPage list(Long userId, String keyword, int page, int size) {
        int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 1) - 1, pageSize);

        Page<SysChatSession> found = (keyword == null || keyword.isBlank())
                ? sessionRepository.findActive(userId, pageable)
                : sessionRepository.findActiveByTitle(userId, "%" + keyword.strip() + "%", pageable);

        List<SysChatSession> sessions = found.getContent();
        Map<Long, Long> counts = messageCounts(sessions);
        Map<Long, Object[]> models = lastAssistantModels(sessions);

        List<ChatDtos.ChatSessionItem> items = new ArrayList<>(sessions.size());
        for (SysChatSession session : sessions) {
            // 绑定的是"配置"，消息行上的是"实际用的" —— 后者优先，因为它才是需求
            // `09:124` 要的「**所用**模型」。没有 assistant 消息（刚建的空会话）才回落到绑定值
            Long modelId = session.getModelId();
            String modelName = null;
            Object[] actual = models.get(session.getId());
            if (actual != null) {
                if (actual[1] != null) {
                    modelId = ((Number) actual[1]).longValue();
                }
                modelName = actual[2] == null ? null : String.valueOf(actual[2]);
            }
            items.add(new ChatDtos.ChatSessionItem(
                    String.valueOf(session.getId()),
                    session.getTitle() == null ? "" : session.getTitle(),
                    displayTime(session.getUpdatedAt()),
                    counts.getOrDefault(session.getId(), 0L),
                    modelId,
                    modelName));
        }
        return new ChatDtos.SessionPage(items, found.getTotalElements());
    }

    /**
     * 某会话的消息列表（含历史引用）。
     *
     * <p>先 {@link #requireOwned} —— 会话不是本人的、不存在、已软删，三者一律
     * {@code NOT_FOUND}。**不区分**"不存在"与"不是你的"是有意的：区分开就等于
     * 告诉调用方"这个 id 确实存在，只是不归你"，那是一个可枚举的信息泄露面。
     */
    @Transactional(readOnly = true)
    public ChatDtos.MessagePage messages(Long userId, Long sessionId) {
        requireOwned(userId, sessionId);

        // 排序用 id 不用 created_at：同一秒落的 user/assistant 两行时间戳完全相同，
        // 按时间排会偶发把"回答"排到"提问"前面
        List<SysChatMessage> rows = messageRepository.findBySessionIdOrderByIdAsc(sessionId);
        Map<String, List<ChatDtos.Citation>> citations = citations(rows);

        List<ChatDtos.ChatMessageItem> items = new ArrayList<>(rows.size());
        for (SysChatMessage row : rows) {
            String traceId = row.getTraceId();
            items.add(new ChatDtos.ChatMessageItem(
                    String.valueOf(row.getId()),
                    row.getRole(),
                    row.getContent() == null ? "" : row.getContent(),
                    epochMillis(row.getCreatedAt()),
                    row.getModelId(),
                    row.getModelName(),
                    // ⚠️ 必须先判 null 再查表，不能写成 `citations.getOrDefault(row.getTraceId(), …)`：
                    // user 消息的 trace_id 本来就是 NULL，而 `citations` 在"一条 trace 都没有"时
                    // 返回的是 `Map.of()` —— **不可变 Map 的 `getOrDefault(null, …)` 会抛 NPE**
                    // （它连 null 键都拒绝，不会走到默认值）。实测踩到：单条 user 消息的会话
                    // 一查消息列表就 500，而那条会话恰好没有 assistant 行
                    traceId == null ? List.of() : citations.getOrDefault(traceId, List.of()),
                    // 图片附件属 `T-805` —— 未落地 ⇒ 恒空。发空数组而不是省掉字段：
                    // 前端按数组遍历，缺字段要额外写判空
                    List.of()));
        }
        return new ChatDtos.MessagePage(items, items.size());
    }

    /**
     * 软删会话（消息行**不动**）。
     *
     * <p>与 {@code sys_document} 同型：只置 {@code deleted_at}，列表与消息随即取不到。
     * 消息行不级联删 —— 会话是聚合根，且"删了会话"与"抹掉问答记录"是两件事，
     * 后者属审计/留存口径，不在这里顺手做。
     *
     * @return 恒 {@code true}；查不到会先抛 {@code NOT_FOUND}（不是返回 false ——
     *         "删掉了"与"没有这条"对调用方是两回事）
     */
    @Transactional
    public boolean softDelete(Long userId, Long sessionId) {
        SysChatSession session = requireOwned(userId, sessionId);
        session.setDeletedAt(LocalDateTime.now());
        sessionRepository.save(session);
        return true;
    }

    /**
     * 导出会话为 Markdown（🚩 数据导出，调用方负责记审计）。
     *
     * <p><b>为什么 Markdown 由后端产出而不是前端拼</b>：导出要**记审计**（`需求/05:368`
     * 把"会话导出"列为问答模块的审计动作），而审计的 {@code operator}/{@code apiPath}
     * 取自请求上下文。前端本地拼一份字符串再下载，等于这条导出**没有留下任何痕迹**
     * —— 那正是数据导出风险最需要防的（《01》§487 把它和批量下载归为一类）。
     *
     * <p>格式取 `需求/01:539`「会话导出格式 = Markdown」。结构本批自拟（需求没给
     * 字段级契约），已登记进 {@code 13-接口变更记录.md}。
     */
    @Transactional(readOnly = true)
    public String exportMarkdown(Long userId, Long sessionId) {
        SysChatSession session = requireOwned(userId, sessionId);
        List<SysChatMessage> rows = messageRepository.findBySessionIdOrderByIdAsc(sessionId);

        StringBuilder md = new StringBuilder();
        md.append("# ").append(session.getTitle() == null || session.getTitle().isBlank()
                ? "未命名会话" : session.getTitle()).append("\n\n");
        md.append("- 会话 ID：").append(session.getId()).append('\n');
        md.append("- 消息数：").append(rows.size()).append('\n');
        md.append("- 创建时间：").append(format(session.getCreatedAt())).append('\n');
        md.append("- 导出时间：").append(format(LocalDateTime.now())).append("\n\n");
        md.append("---\n\n");

        for (SysChatMessage row : rows) {
            // 角色用二级标题而不是粗体：Markdown 里标题能进目录，导出件拿到手
            // 一眼能看出"哪几段是提问、哪几段是回答"
            md.append("## ").append("user".equals(row.getRole()) ? "提问" : "回答").append('\n');
            md.append("- 时间：").append(format(row.getCreatedAt()));
            if (row.getModelName() != null && !row.getModelName().isBlank()) {
                md.append(" · 模型：").append(row.getModelName());
            }
            md.append("\n\n");
            // 正文原样带出 —— 不转义。转义会把已经存在的 Markdown 结构（表格/代码块）
            // 变成字面反斜杠，而导出件的用途是给人读，不是给解析器读
            md.append(row.getContent() == null ? "" : row.getContent()).append("\n\n");
        }
        return md.toString();
    }

    // ==================== 内部 ====================

    /**
     * 取会话并校验归属；不可用一律 {@code NOT_FOUND}（三种情形不区分，见 {@link #messages}）。
     */
    private SysChatSession requireOwned(Long userId, Long sessionId) {
        return sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }

    /** 批量取消息条数（一次查询，不逐会话查 —— 那是 N+1）。 */
    private Map<Long, Long> messageCounts(List<SysChatSession> sessions) {
        Set<Long> ids = ids(sessions);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : messageRepository.countBySessionIds(ids)) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    /** 批量取各会话最后一条 assistant 消息的模型（一次查询）。 */
    private Map<Long, Object[]> lastAssistantModels(List<SysChatSession> sessions) {
        Set<Long> ids = ids(sessions);
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Object[]> models = new HashMap<>();
        for (Object[] row : messageRepository.lastAssistantModelBySessionIds(ids)) {
            models.put(((Number) row[0]).longValue(), row);
        }
        return models;
    }

    private static Set<Long> ids(List<SysChatSession> sessions) {
        Set<Long> ids = new LinkedHashSet<>(sessions.size());
        for (SysChatSession session : sessions) {
            ids.add(session.getId());
        }
        return ids;
    }

    /**
     * 按 {@code trace_id} 批量重建历史引用。
     *
     * <p>数据源是 {@code sys_retrieval_trace_chunk}（`S5-M08-A` 打点写进去的分片明细），
     * 与实时 {@code retrieval} 事件的 {@code chunks[]} **同源同口径** ——
     * 两处都写/读同一批行、都用 {@code rank_no} 定序。
     *
     * <p>⚠️ 只认**数字主键**的分片吗？不需要在这里再判一次 ——
     * 写入侧 {@code RetrievalTraceWriter} 已经跳过了非数字主键，
     * 所以表里不会出现那种行。再判一次是重复口径，反而容易两处漂移。
     */
    private Map<String, List<ChatDtos.Citation>> citations(List<SysChatMessage> rows) {
        Set<String> traceIds = new LinkedHashSet<>();
        for (SysChatMessage row : rows) {
            if (row.getTraceId() != null && !row.getTraceId().isBlank()) {
                traceIds.add(row.getTraceId());
            }
        }
        if (traceIds.isEmpty()) {
            return Map.of();
        }

        @SuppressWarnings("unchecked")
        List<Object[]> chunkRows = entityManager.createNativeQuery("""
                        select c.trace_id, c.rank_no, c.chunk_id, c.doc_id, c.score, c.source
                        from sys_retrieval_trace_chunk c
                        where c.trace_id in (:traceIds)
                        order by c.trace_id, c.rank_no
                        """)
                .setParameter("traceIds", traceIds)
                .getResultList();

        Map<Long, String> titles = docTitles(chunkRows);
        Map<String, List<ChatDtos.Citation>> byTrace = new HashMap<>();
        for (Object[] row : chunkRows) {
            String traceId = String.valueOf(row[0]);
            long docId = ((Number) row[3]).longValue();
            byTrace.computeIfAbsent(traceId, k -> new ArrayList<>())
                    .add(new ChatDtos.Citation(
                            row[1] == null ? 0 : ((Number) row[1]).intValue(),
                            docId,
                            titles.getOrDefault(docId, ""),
                            ((Number) row[2]).longValue(),
                            row[4] == null ? 0d : ((Number) row[4]).doubleValue(),
                            row[5] == null ? "" : String.valueOf(row[5])));
        }
        byTrace.replaceAll((k, v) -> List.copyOf(v));
        return byTrace;
    }

    /**
     * 按 docId 批量补文档标题。
     *
     * <p>⚠️ **不过滤 {@code deleted_at}** —— 与 {@code ChatWireMapper#loadDocTitles} 同一条口径：
     * 引用指向的是**已经发生过**的那次检索，文档后来被软删不该让历史答案的角标变成空标题。
     */
    private Map<Long, String> docTitles(List<Object[]> chunkRows) {
        Set<Long> docIds = new LinkedHashSet<>();
        for (Object[] row : chunkRows) {
            docIds.add(((Number) row[3]).longValue());
        }
        if (docIds.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select id, title from sys_document where id in (:ids)")
                .setParameter("ids", docIds)
                .getResultList();
        Map<Long, String> titles = new HashMap<>(rows.size());
        for (Object[] row : rows) {
            titles.put(((Number) row[0]).longValue(), row[1] == null ? "" : String.valueOf(row[1]));
        }
        return titles;
    }

    /**
     * 最近活动的展示串（前端 {@code {{ s.time }}} 原样渲染，**不做格式化**）。
     *
     * <p>规则与 mock 的观感一致（{@code '10:24'} / {@code '昨天'} / {@code '周二'}）：
     * 今天给时刻、昨天给"昨天"、一周内给星期几、更早给日期。
     * <b>本项目的"一周内"取自然周差</b>（{@code 今天 - 目标 < 7 天}）而不是 ISO 同周 ——
     * 后者会让"上周日"和"本周一"显示成同一种"周X"而看起来像同一天。
     *
     * <p>{@code null} 入参返回空串：{@code updated_at} 是 NOT NULL，理论上到不了这里，
     * 但列表页不该因为一行脏数据整页炸掉。
     */
    static String displayTime(LocalDateTime time) {
        if (time == null) {
            return "";
        }
        LocalDate today = LocalDate.now();
        LocalDate day = time.toLocalDate();
        long days = today.toEpochDay() - day.toEpochDay();
        if (days == 0) {
            return time.format(TIME_OF_DAY);
        }
        if (days == 1) {
            return "昨天";
        }
        if (days > 1 && days < 7) {
            return WEEKDAYS[day.getDayOfWeek().getValue() - 1];
        }
        return day.getYear() == today.getYear() ? day.format(MONTH_DAY) : day.format(FULL_DATE);
    }

    /** {@code LocalDateTime} → 毫秒时间戳（前端消息行的 {@code createdAt} 是数字）。 */
    private static long epochMillis(LocalDateTime time) {
        return time == null ? 0L : time.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** 导出件里的时间用可读格式，不用时间戳 —— 导出是给人读的。 */
    private static String format(LocalDateTime time) {
        return time == null ? "—" : time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }
}
