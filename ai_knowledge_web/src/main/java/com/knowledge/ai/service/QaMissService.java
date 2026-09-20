package com.knowledge.ai.service;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.ops.MissStatus;
import com.knowledge.ai.core.ops.QaMissRepository;
import com.knowledge.ai.core.ops.TimeWindow;
import com.knowledge.ai.core.setting.SystemSettingService;
import com.knowledge.ai.core.setting.SystemSettings;
import com.knowledge.ai.dto.ops.OpsDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 问答运营 —— 本批只有未命中清单这一块（`T-A00`）。
 *
 * <h3>范围：11 页的哪一块是本批的</h3>
 * <p>需求 `08` §1.8 列了七条端点，本批交三条：
 * {@code GET /ops/qa/misses} · {@code PATCH /ops/qa/misses/{id}} · {@code GET /ops/qa/overview}。
 * 剩下四条（`top-questions` / `feedback-stats` / `export` / `{id}/replay`）分别是
 * `T-A02` 与 `T-A01` —— **没实现的接口不回假数据**（承 `S6-M09-C` 的口径：
 * 返回真数字会让"不支持"看起来像"能用"）。
 *
 * <h3>🔴 待办数与通知同源（`G6-13`）</h3>
 * <p>{@link #pendingCount()} 是**唯一**的待办数来源：`/notices/pending`
 * 与侧边栏角标都从它取。不这么做就会出现「角标说 3、清单说 5」——
 * 而两个数各自都对，只是口径不同（一个数 `PENDING`、一个数 `PENDING + IN_LIST`）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaMissService {

    /** 列表默认页大小 —— 与前端 `OpsQaView` 的分页默认一致。 */
    private static final int DEFAULT_SIZE = 20;

    private static final int MAX_SIZE = 100;

    /**
     * 与通知列表同一格式（`NoticeService.TIME`）—— 一个页面里两种时间格式最扎眼。
     *
     * <p>⚠️ 包级可见：`OpsExportService` 的导出报表要显示「最近提问」的同一个格式
     * —— 页面上 `2026-09-20 14:30` 而 CSV 里 `2026-09-20T14:30:12` 会被当成"导出错了"。
     */
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final QaMissRepository qaMissRepository;

    /** 未命中判定线（`sys_setting` 的 `retrieval.miss-threshold`）—— 页面标题要显示当前值。 */
    private final SystemSettingService systemSettingService;

    // ==================== 清单 · `GET /ops/qa/misses` ====================

    public OpsDtos.MissListResponse list(String status, String kbId, String keyword,
                                         Integer page, Integer size) {
        MissStatus parsed = MissStatus.parseFilter(status);
        Long kb = parseKbId(kbId);
        int limit = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        int offset = Math.max(0, (page == null || page < 1 ? 1 : page) - 1) * limit;

        List<QaMissRepository.Row> rows =
                qaMissRepository.findPage(parsed, kb, keyword, limit, offset);
        Map<Long, String> kbNames = kbNamesOf(rows);

        List<OpsDtos.MissItem> items = new ArrayList<>(rows.size());
        for (QaMissRepository.Row row : rows) {
            items.add(new OpsDtos.MissItem(
                    row.id(),
                    row.question(),
                    row.askCount(),
                    row.lastAskAt() == null ? null : TIME.format(row.lastAskAt()),
                    row.askerName(),
                    row.maxTopScore(),
                    row.scoreAfter(),
                    row.status(),
                    row.ignoredByName(),
                    namesOf(row.kbIds(), kbNames)));
        }
        return new OpsDtos.MissListResponse(
                items,
                qaMissRepository.countPage(parsed, kb, keyword),
                qaMissRepository.sumAskCount(parsed, kb, keyword));
    }

    // ==================== 状态机 · `PATCH /ops/qa/misses/{id}` ====================

    /**
     * 改状态。
     *
     * <p>⚠️ **不做"合法流转校验"** —— 需求 §1.3 画了四条边，但把非法边一律拒掉会把
     * 「已解决 → 待处理」（补录没生效，退回重做）这条**真实存在**的路也堵死，
     * 而需求的图里它恰恰是"重放未达标"的自动边，本批没有重放（`T-A01`）。
     * 所以规则只有两条**硬**的：**状态值必须认识** + **忽略必须留下忽略人**。
     * 「哪些流转该被禁止」等 `T-A01` 把重放接上之后再定 —— 那时才有足够信息。
     */
    @Transactional
    public OpsDtos.PatchMissResponse patch(long id, String status, long operatorId) {
        MissStatus target = MissStatus.parse(status);
        QaMissRepository.Row row = qaMissRepository.findById(id);
        if (row == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "未命中问题不存在：" + id);
        }
        qaMissRepository.updateStatus(id, target, operatorId, LocalDateTime.now());
        return new OpsDtos.PatchMissResponse(id, target.name(), pendingCount());
    }

    // ==================== 四指标 · `GET /ops/qa/overview` ====================

    /**
     * 需求 §1.1 的四个指标。
     *
     * @param range 时间窗，形如 `7d` / `30d`；空 = 7 天
     */
    public OpsDtos.QaOverview overview(String range) {
        LocalDateTime from = TimeWindow.from(range);
        QaMissRepository.TraceStats stats = qaMissRepository.traceStats(from);

        double rate = stats.totalQuestions() == 0
                ? 0d
                : Math.round(stats.missed() * 1000.0 / stats.totalQuestions()) / 10.0;

        return new OpsDtos.QaOverview(
                stats.totalQuestions(),
                stats.missed(),
                rate,
                stats.avgTopScore(),
                pendingCount(),
                systemSettingService.snapshot()
                        .decimal(SystemSettings.RETRIEVAL_MISS_THRESHOLD,
                                SystemSettings.DEFAULT_MISS_THRESHOLD));
    }

    /** 待办数 —— **`/notices/pending` 与侧边栏角标共用这一处**（`G6-13`）。 */
    public int pendingCount() {
        return qaMissRepository.pendingCount();
    }

    // ==================== 内部 ====================

    /**
     * 知识库筛选参数：`all` / 空 = 不筛。
     *
     * <p>⚠️ 为什么在**服务层**翻而不是让 Spring 把 `Long` 绑失败：
     * 前端把"全部"发的是**字面量 `all`**（与 mock 一致）。让参数绑定抛异常的话，
     * 错误发生在进控制器之前 ⇒ 返回的是 Spring 的 400 而不是我们的响应体，
     * 而现象是"页面空着"，与"接口没实现"分不开。见 `OpsQaController#misses`。
     */
    static Long parseKbId(String kbId) {
        if (kbId == null || kbId.isBlank() || "all".equalsIgnoreCase(kbId.trim())) {
            return null;
        }
        try {
            return Long.valueOf(kbId.trim());
        } catch (NumberFormatException ex) {
            throw new BizException(ErrorCode.PARAM_INVALID, "知识库 id 必须是数字或 all，收到：" + kbId);
        }
    }

    /**
     * 这些行涉及的知识库 id → 显示名。
     *
     * <p>⚠️ **包级可见**（不是 `private`）：`OpsExportService` 的导出报表要显示同一列
     * 「涉及知识库」，而"`kb_ids` 是逗号串、要按 id 批量取名字"这条规则**只该有一份**
     * —— 各写一份的后果是同一行在页面上显示三个库、在 CSV 里显示一个，而两边都"看起来对"。
     */
    Map<Long, String> kbNamesOf(List<QaMissRepository.Row> rows) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (QaMissRepository.Row row : rows) {
            ids.addAll(parseIds(row.kbIds()));
        }
        return ids.isEmpty() ? Map.of() : qaMissRepository.kbNames(new ArrayList<>(ids));
    }

    /**
     * `kb_ids` → 显示名。
     *
     * <p>⚠️ 跨库的行用「、」连接而不是只显示第一个：那一行的未命中**横跨了几个库**，
     * 只显示一个会让人以为"问题出在那个库"。一个都解不出（库被删了）时给空串，
     * 不编一个名字出来。
     */
    static String namesOf(String kbIds, Map<Long, String> names) {
        List<Long> ids = parseIds(kbIds);
        LinkedHashSet<String> resolved = new LinkedHashSet<>();
        for (Long id : ids) {
            String name = names.get(id);
            if (name != null) {
                resolved.add(name);
            }
        }
        return String.join("、", resolved);
    }

    private static List<Long> parseIds(String csv) {
        List<Long> ids = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return ids;
        }
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.valueOf(trimmed));
            } catch (NumberFormatException ex) {
                // 列是逗号串 ⇒ 形状由写入方保证。形状不对时**跳过并留痕**，
                // 不猜一个 id（猜错会把问题挂到别的库名下）
                log.warn("[运营] kb_ids 里有非数字片段，已跳过：{}", trimmed);
            }
        }
        return ids;
    }
}
