package com.knowledge.ai.service;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.notice.NoticeAction;
import com.knowledge.ai.core.notice.NoticeActionsJson;
import com.knowledge.ai.core.notice.NoticeRepository;
import com.knowledge.ai.core.notice.NoticeType;
import com.knowledge.ai.dto.notice.NoticeDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通知中心的读侧（`T-B00`/`T-B01`/`T-B02`）。
 *
 * <p>写侧在 {@code core/notice/NoticePublisher} —— 本类**只读不写**（唯一的写是"标已读"与
 * "存设置"，那两件是用户对自己数据的操作，不是"产出通知"）。
 *
 * <h3>🔴 `pending` 只出有源的那一半</h3>
 * <p>需求 §2.3 的「待我处理」有两项：**未命中问题** 与 **解析失败**。
 * 其中未命中问题的源是**未命中清单**，属 `T-A00`（S8）—— 本仓**没有那张表**
 * （`sys_retrieval_trace.miss_flag` 只是原始标记，不是"已沉淀为清单"）。
 * ⇒ 本批只出解析那一项，未命中那一项**不发**。
 *
 * <p>⚠️ **不发 ≠ 发 0**。这是本仓已经定过的口径（`#169`：模型用量只覆盖对话链路时
 * 「刻意不给 0：那会被读成'本月确实没用量'」）。这里同理 —— 发 0 会被读成
 * "**确实没有待处理的未命中问题**"，而事实是"**这个问题还没有地方去数**"。
 * 两者对用户的意义完全相反。
 *
 * <p>`G6-13`（「`/notices/pending` 的 `total` 与 `/ops/qa/overview` 待处理数一致」）
 * 因此标**挂起**，待 `T-A00` 落地后**与那批合并验收**。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoticeService {

    /** 列表默认页大小 —— 需求 §2.4 是「加载更多」，前端每次要 10 条（`visibleCount += 10`）。 */
    private static final int DEFAULT_SIZE = 10;

    private static final int MAX_SIZE = 50;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NoticeRepository noticeRepository;

    // ==================== 列表 · `T-B00` ====================

    /** `GET /notices?type=&read=&page=&size=`。 */
    public NoticeDtos.NoticeListResponse list(long userId, String type, String read, Integer page, Integer size) {
        NoticeType parsedType = NoticeType.parseOrNull(type);
        Boolean parsedRead = parseRead(read);
        int limit = clamp(size);
        int offset = Math.max(0, (page == null || page < 1 ? 1 : page) - 1) * limit;

        List<NoticeDtos.NoticeItem> items = noticeRepository
                .findPage(userId, parsedType, parsedRead, limit, offset)
                .stream()
                .map(NoticeService::toItem)
                .toList();

        NoticeRepository.Counts counts = noticeRepository.counts(userId);
        return new NoticeDtos.NoticeListResponse(items, counts.all(), new NoticeDtos.NoticeCounts(
                counts.all(), counts.unread(),
                counts.of(NoticeType.UPLOAD), counts.of(NoticeType.ALERT),
                counts.of(NoticeType.PERMISSION), counts.of(NoticeType.SYSTEM)));
    }

    /** `GET /notices/unread-count` —— 顶栏角标（需求 §2.6）。 */
    public NoticeDtos.UnreadCountResponse unreadCount(long userId) {
        return new NoticeDtos.UnreadCountResponse(noticeRepository.unreadCount(userId));
    }

    /** `GET /notices/pending` —— 见类注释：**只出有源的那一项**。 */
    public NoticeDtos.PendingResponse pending() {
        int docs = noticeRepository.pendingParseIssueCount();
        List<NoticeDtos.PendingItem> list = new ArrayList<>();
        if (docs > 0) {
            list.add(new NoticeDtos.PendingItem("parse", "文档解析问题待处理", docs,
                    docs + " 篇文档存在未识别表格或解析失败，内容无法被检索命中",
                    "/kb", "查看解析质量"));
        }
        // ⚠️ 未命中问题那一项**刻意不发** —— 见类注释。
        return new NoticeDtos.PendingResponse(list, list.size());
    }

    // ==================== 已读 · `T-B02` ====================

    /** `PATCH /notices/{id}/read`。 */
    public NoticeDtos.ReadResponse setRead(long userId, long noticeId, boolean read) {
        if (!noticeRepository.setRead(noticeId, userId, read)) {
            // 不可见 = 不存在（不区分"没有这条"与"不归你看"，避免探测他人通知）
            throw new BizException(ErrorCode.NOT_FOUND, "通知不存在");
        }
        return new NoticeDtos.ReadResponse(noticeId, read, noticeRepository.unreadCount(userId));
    }

    /** `POST /notices/read-all` —— 需求 §2.7：**不需要二次确认**（已读可逆）。 */
    public NoticeDtos.ReadAllResponse readAll(long userId) {
        int updated = noticeRepository.markAllRead(userId);
        return new NoticeDtos.ReadAllResponse(updated, noticeRepository.unreadCount(userId));
    }

    // ==================== 设置 · `T-B02` ====================

    /**
     * `GET /notices/settings` —— **四类永远全给**。
     *
     * <p>库里只存"被关掉的差异"（没有行 = 开启），补齐四类是这里的事 ——
     * 那样将来加第五类不必回填数据行。
     */
    public NoticeDtos.SettingsResponse settings(long userId) {
        Map<NoticeType, Boolean> saved = noticeRepository.settings(userId);
        List<NoticeDtos.NoticeSetting> list = new ArrayList<>();
        for (NoticeType type : NoticeType.values()) {
            boolean enabled = !type.canDisable() || saved.getOrDefault(type, true);
            list.add(new NoticeDtos.NoticeSetting(type.name(), type.label(), enabled,
                    type.canDisable() ? null : Boolean.TRUE, type.hint()));
        }
        return new NoticeDtos.SettingsResponse(list);
    }

    /**
     * `PUT /notices/settings` —— 🔴 **系统告警关不掉，且要回话说明**（需求 §2.5）。
     *
     * <p>把「关掉 ALERT」的请求**剔除**而不是整个请求拒绝：一次提交里其它三类该生效的要生效
     * （前端是整表提交）。剔除的同时用 {@code note} 把原因说出来 ——
     * 否则前端会显示"保存成功"而那一项纹丝不动，用户只会以为坏了。
     */
    public NoticeDtos.SettingsSaveResponse saveSettings(long userId, NoticeDtos.SettingsSaveRequest request) {
        List<NoticeDtos.SettingsSaveRequest.Item> applied = new ArrayList<>();
        boolean sawRejectedAlert = false;
        for (NoticeDtos.SettingsSaveRequest.Item item : request.list()) {
            NoticeType type = NoticeType.parseOrNull(item.type());
            if (type == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "未知的通知类型：" + item.type());
            }
            if (!type.canDisable() && !Boolean.TRUE.equals(item.enabled())) {
                sawRejectedAlert = true;
                continue;
            }
            noticeRepository.saveSetting(userId, type, Boolean.TRUE.equals(item.enabled()));
            applied.add(item);
        }
        String note = sawRejectedAlert
                ? "系统告警为强制开启，不受设置影响"
                : "已保存";
        return new NoticeDtos.SettingsSaveResponse(true, applied, note);
    }

    // ==================== 映射 ====================

    private static NoticeDtos.NoticeItem toItem(NoticeRepository.Row row) {
        List<NoticeDtos.Action> actions = NoticeActionsJson.read(row.actionsJson()).stream()
                .map(NoticeService::toAction)
                .toList();
        return new NoticeDtos.NoticeItem(
                row.id(),
                row.type(),
                row.title(),
                row.content(),
                row.createdAt() == null ? "" : row.createdAt().format(TIME),
                row.read(),
                groupOf(row.createdAt()),
                actions,
                row.needAction(),
                row.muteable());
    }

    private static NoticeDtos.Action toAction(NoticeAction action) {
        return new NoticeDtos.Action(action.label(), action.to(), action.primary());
    }

    /**
     * 时间分组（需求 §2.4「按时间分组：今天 / 昨天 / 更早，比显示绝对时间更易扫读」）。
     *
     * <p>⚠️ 它必须**每次请求现算**，不能落库 —— "今天"是相对当前日期的函数，
     * 落库当天是对的、第二天就错了（这正是"派生值不进库"的典型）。
     */
    private static String groupOf(LocalDateTime createdAt) {
        if (createdAt == null) {
            return "更早";
        }
        LocalDate day = createdAt.toLocalDate();
        LocalDate today = LocalDate.now();
        if (day.isEqual(today)) {
            return "今天";
        }
        return day.isEqual(today.minusDays(1)) ? "昨天" : "更早";
    }

    /** `read` 参数：`unread` / `read` / 其它（含 `all`）= 不筛。 */
    private static Boolean parseRead(String read) {
        if ("unread".equalsIgnoreCase(read)) {
            return Boolean.FALSE;
        }
        if ("read".equalsIgnoreCase(read)) {
            return Boolean.TRUE;
        }
        return null;
    }

    private static int clamp(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
