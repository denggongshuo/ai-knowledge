package com.knowledge.ai.service;

import com.knowledge.ai.core.audit.AuditChange;
import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.text.Csv;
import com.knowledge.ai.dto.audit.AuditDtos;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计日志查询（T-302 列表/详情 · T-303 统计）。
 *
 * <h3>为什么用原生 SQL 而不是 JPA 派生查询</h3>
 * <p>筛选维度是 5 个可选条件的**组合**（模块 / 风险 / 结果 / 操作人 / 关键字）
 * 外加一个时间窗。用 JPA 要把它们拼成
 * {@code findByModuleAndRiskLevelAndResultAndOperatorContainingAndCreatedAtBetween…}
 * 的 2^n 个方法，且 {@code keyword} 要跨 4 列 OR —— 派生查询表达不了。
 * 本仓既有先例：{@code UserAdminService#page} / {@code KbGrantService#list} 都是
 * "原生 SQL 拼 where + {@code EntityManager}"。
 *
 * <h3>分页参数名是 {@code page/size}（不是 {@code pageSize}）</h3>
 * <p>前端 {@code AuditLogView.vue:62} 发的是 {@code size}，mock 也是按 {@code size} 读的。
 * 这里**两个都收**（{@code size} 优先）—— 与 {@code UserController#loginLogs} 同一处置：
 * 《10》§368 写 {@code pageSize}、前端写 {@code size}，两者都有既成事实，
 * 单收一个会让另一种调用静默拿到默认 10 条（看着像"筛选没生效"）。
 *
 * <h3>为什么详情要单独一个查询</h3>
 * <p>列表不查 {@code changes_json}（267 行有值，长度可达数 KB）——
 * 一页 10 条全带上的话，列表响应会平白大几十 KB，而列表页根本不渲染变更明细。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 列表列集：**不含** {@code changes_json} / {@code note}（见类注释）。 */
    private static final String LIST_COLUMNS = """
            id, created_at, operator, operator_name, module, action,
            risk_level, target, ip, result, fail_reason
            """;

    /** 排序口径：时间倒序 + id 兜底。同一秒内的多条（批量操作常见）靠 id 才能稳定。 */
    private static final String ORDER_BY = " order by created_at desc, id desc";

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * JSON 反序列化器。注入 Boot 4 自动配置的那个（Jackson 3，{@code tools.jackson}）——
     * 与 {@code AuditLogService} **同一个实例**，写读两端口径才一致。
     *
     * <p>本仓 classpath 上同时有 Jackson 2（jjwt-jackson 的传递依赖），
     * 自己 {@code new} 会绕开 Boot 配置，且将来加字段容易踩到"注入哪个"的坑。
     */
    private final ObjectMapper jsonMapper;

    // ==================================================================
    // T-302 · 列表
    // ==================================================================

    /**
     * 分页查询。五个筛选参数都为可选（{@code null} / 空串 / {@code "all"} 视为不筛）。
     *
     * @param startTime 起始时刻（含）；前端传 {@code yyyy-MM-dd HH:mm:ss}，见 {@link #parseTime}
     * @param endTime   结束时刻（含）
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public AuditDtos.LogPage list(String startTime, String endTime, String module, String riskLevel,
                                  String result, String operator, String keyword,
                                  long page, long size) {
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        appendFilters(where, params, startTime, endTime, module, riskLevel, result, operator, keyword);

        String whereSql = where.isEmpty() ? "" : " where " + String.join(" and ", where);

        Query countQuery = entityManager.createNativeQuery(
                "select count(*) from sys_audit_log" + whereSql);
        params.forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        long safeSize = size <= 0 ? 10 : Math.min(size, 200);
        long safePage = page <= 0 ? 1 : page;

        Query dataQuery = entityManager.createNativeQuery(
                "select " + LIST_COLUMNS + " from sys_audit_log" + whereSql + ORDER_BY);
        params.forEach(dataQuery::setParameter);
        dataQuery.setFirstResult((int) ((safePage - 1) * safeSize));
        dataQuery.setMaxResults((int) safeSize);

        List<AuditDtos.LogRow> rows = new ArrayList<>();
        for (Object row : dataQuery.getResultList()) {
            rows.add(toRow((Object[]) row));
        }
        return new AuditDtos.LogPage(rows, total, safePage, safeSize);
    }

    /**
     * 五个筛选条件 + 时间窗，拼成 where 片段。
     *
     * <p>抽出来是因为 {@link #exportCsv} 要走**同一段**条件 —— 导出"把筛出来的结果存下来"
     * 是用户的预期，导出全表则是数据外带。两处各写一遍必然漂移
     * （典型症状：页面筛了模块、导出的 CSV 里却有全部模块）。
     */
    private void appendFilters(List<String> where, Map<String, Object> params,
                               String startTime, String endTime, String module, String riskLevel,
                               String result, String operator, String keyword) {
        if (isPresent(module)) {
            params.put("q_module", module.trim());
            where.add("module = :q_module");
        }
        if (isPresent(riskLevel)) {
            params.put("q_risk", riskLevel.trim().toUpperCase(java.util.Locale.ROOT));
            where.add("risk_level = :q_risk");
        }
        if (isPresent(result)) {
            params.put("q_result", result.trim().toUpperCase(java.util.Locale.ROOT));
            where.add("result = :q_result");
        }
        if (isPresent(operator)) {
            params.put("q_operator", operator.trim());
            where.add("operator = :q_operator");
        }
        if (isPresent(keyword)) {
            // 4 列 OR，与 mock（`src/mock/index.ts` 的 audit/logs 处理器）逐列一致：
            // 操作人姓名 / 操作人账号 / 对象 / 来源 IP。占位符提示语也写着这四项。
            // 多加一列（比如 action）看着更"好用"，但会让真后端与 mock 的同一输入
            // 返回不同结果 —— 两套行为的漂移比少一列更难查。
            params.put("q_kw", "%" + keyword.trim() + "%");
            where.add("(operator_name like :q_kw or operator like :q_kw "
                    + "or target like :q_kw or ip like :q_kw)");
        }
        LocalDateTime from = parseTime(startTime, false);
        if (from != null) {
            params.put("q_from", from);
            where.add("created_at >= :q_from");
        }
        LocalDateTime to = parseTime(endTime, true);
        if (to != null) {
            params.put("q_to", to);
            where.add("created_at <= :q_to");
        }
    }

    // ==================================================================
    // T-302 · 详情（扁平字段）
    // ==================================================================

    /**
     * 详情。
     *
     * <p>{@code roleName} 取的是 {@code affected_role} 列（**受影响角色**），
     * 不是 {@code role_name}（操作人角色码）—— 前端详情抽屉消费的是前者，
     * 语义见 {@code V11} 的迁移注释。为 {@code null} 时前端会自动省略括号与按钮。
     */
    @Transactional(readOnly = true)
    public AuditDtos.LogDetail detail(long id) {
        List<?> rows = entityManager.createNativeQuery("""
                        select id, created_at, operator, operator_name, module, action,
                               risk_level, target, ip, result, fail_reason,
                               log_no, trace_id, api_path, cost_ms, user_agent,
                               affected_users, affected_role, note, changes_json
                        from sys_audit_log where id = :id
                        """)
                .setParameter("id", id)
                .getResultList();
        if (rows.isEmpty()) {
            throw BizException.of(ErrorCode.NOT_FOUND, "审计日志不存在");
        }
        Object[] row = (Object[]) rows.getFirst();
        return new AuditDtos.LogDetail(
                ((Number) row[0]).longValue(),
                formatTime(row[1]),
                (String) row[2],
                (String) row[3],
                (String) row[4],
                (String) row[5],
                (String) row[6],
                (String) row[7],
                (String) row[8],
                (String) row[9],
                (String) row[10],
                (String) row[11],
                (String) row[12],
                (String) row[13],
                row[14] == null ? null : ((Number) row[14]).intValue(),
                (String) row[15],
                row[16] == null ? null : ((Number) row[16]).intValue(),
                (String) row[17],
                (String) row[18],
                parseChanges((String) row[19]));
    }

    /**
     * {@code changes_json} → {@code changes[]}。
     *
     * <p><b>反序列化失败降级为 {@code null} 并留 warn</b>，不抛异常：
     * 详情整条 500 的话，用户连"这条日志的动作是什么"都看不到 ——
     * 为了一个附加展示字段牺牲整条记录的可见性不划算。
     * 这与落库侧"序列化失败降级为 null"（{@code AuditLogService#serializeChanges}）
     * 是对称的取舍：**审计的可用性不能高于业务的可用性，但也不该被一个字段拖垮**。
     *
     * <p>无 {@code changes_json} 时返回 {@code null}（不是空列表）—— T-300 冻结口径。
     */
    private List<AuditChange> parseChanges(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            List<AuditChange> parsed = jsonMapper.readValue(json, new TypeReference<List<AuditChange>>() {
            });
            return (parsed == null || parsed.isEmpty()) ? null : parsed;
        } catch (Exception ex) {
            log.warn("审计变更明细反序列化失败，降级为 null（changes_json 长度={}）：{}",
                    json.length(), ex.getMessage());
            return null;
        }
    }

    // ==================================================================
    // T-303 · 统计
    // ==================================================================

    /**
     * 四项统计，全部按 {@code created_date = ?} 聚合。
     *
     * <p>{@code date} 缺省 = 今天。走冗余的 {@code created_date} 列而不是
     * {@code date(created_at)}：后者是函数运算，用不上 {@code idx_audit_*_created}，
     * 且与未来按月分区不兼容 —— 那一列就是为这个建的。
     *
     * <p>{@code activeUsers} 用 {@code count(distinct operator)}：匿名（{@code operator}
     * 为 null）**不计入**。取"不计入"是因为匿名不是一个"人"，
     * 而把 null 算作 1 会让运维看到一个永远偏大的数（详见 C 批计划 §3.4 假设 2）。
     */
    @Transactional(readOnly = true)
    public AuditDtos.StatsView stats(LocalDate date) {
        LocalDate target = date == null ? LocalDate.now() : date;
        List<?> rows = entityManager.createNativeQuery("""
                        select count(*),
                               sum(case when risk_level = 'HIGH' then 1 else 0 end),
                               count(distinct operator),
                               sum(case when result = 'FAILED' then 1 else 0 end)
                        from sys_audit_log where created_date = :d
                        """)
                .setParameter("d", target)
                .getResultList();
        if (rows.isEmpty()) {
            return new AuditDtos.StatsView(0L, 0L, 0L, 0L);
        }
        Object[] row = (Object[]) rows.getFirst();
        // sum() 在零行时返回 NULL，count() 返回 0 —— 不能直接 longValue()
        return new AuditDtos.StatsView(
                row[0] == null ? 0L : ((Number) row[0]).longValue(),
                row[1] == null ? 0L : ((Number) row[1]).longValue(),
                row[2] == null ? 0L : ((Number) row[2]).longValue(),
                row[3] == null ? 0L : ((Number) row[3]).longValue());
    }

    // ==================================================================
    // T-304 · 导出（CSV 文本生成，落 MinIO 由 AuditExportService 负责）
    // ==================================================================

    /**
     * 按与列表**同一套条件**取全部匹配行（不分页），生成 CSV 文本。
     *
     * <p>为什么不复用 {@link #list}：导出的语义是"把筛出来的东西全给我"，
     * 分页会静默丢掉后面的行 —— 而用户看到的是"导出成功"。
     * 这里仍然给一个**硬上限**（{@value #EXPORT_MAX_ROWS}）：
     * 真要导出百万行时，正确做法是分片异步 + 通知，而不是让一次 HTTP 请求
     * 把堆撑爆。超限时明确报错，好过悄悄截断。
     *
     * @return CSV 全文（含表头）
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public String exportCsv(String startTime, String endTime, String module, String riskLevel,
                            String result, String operator, String keyword) {
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        appendFilters(where, params, startTime, endTime, module, riskLevel, result, operator, keyword);
        String whereSql = where.isEmpty() ? "" : " where " + String.join(" and ", where);

        Query countQuery = entityManager.createNativeQuery(
                "select count(*) from sys_audit_log" + whereSql);
        params.forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();
        if (total > EXPORT_MAX_ROWS) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "本次导出 " + total + " 条，超出单次上限 " + EXPORT_MAX_ROWS
                            + " 条，请先按时间范围收窄");
        }

        Query dataQuery = entityManager.createNativeQuery(
                "select " + LIST_COLUMNS + ", changes_json, note from sys_audit_log"
                        + whereSql + ORDER_BY);
        params.forEach(dataQuery::setParameter);

        StringBuilder csv = new StringBuilder();
        // BOM：Excel 打开无 BOM 的 UTF-8 CSV 会把中文显示成乱码。
        // 这不是"锦上添花" —— 导出审计日志的人第一件事就是双击打开它。
        csv.append('﻿');
        csv.append("时间,操作人账号,操作人姓名,模块,操作,风险等级,对象,来源IP,结果,失败原因,变更明细,备注\r\n");
        for (Object raw : dataQuery.getResultList()) {
            Object[] row = (Object[]) raw;
            AuditDtos.LogRow r = toRow(row);
            csv.append(Csv.cell(r.time())).append(',')
                    .append(Csv.cell(r.operator())).append(',')
                    .append(Csv.cell(r.operatorName())).append(',')
                    .append(Csv.cell(r.module())).append(',')
                    .append(Csv.cell(r.action())).append(',')
                    .append(Csv.cell(r.riskLevel())).append(',')
                    .append(Csv.cell(r.target())).append(',')
                    .append(Csv.cell(r.ip())).append(',')
                    .append(Csv.cell(r.result())).append(',')
                    .append(Csv.cell(r.failReason())).append(',')
                    .append(Csv.cell(renderChanges((String) row[11]))).append(',')
                    .append(Csv.cell((String) row[12])).append("\r\n");
        }
        return csv.toString();
    }

    /**
     * 把 {@code changes_json} 压成一行人类可读文本：{@code label: before→after; …}。
     *
     * <p><b>为什么导出必须带变更明细</b>：合规核查要看的正是"到底什么被改了"。
     * 只导列表列的话，导出文件回答不了它存在的理由。
     *
     * <p>解析失败降级成空串（与详情同口径：一个附加字段不该让整件事失败）。
     * 反序列化后**不再做脱敏** —— 值是落库前就已经脱敏过的（T-305 挂在写入侧），
     * 这里再打一次码纯属多余，且会在将来误伤正常内容。
     */
    private String renderChanges(String changesJson) {
        List<AuditChange> changes = parseChanges(changesJson);
        if (changes == null || changes.isEmpty()) {
            return "";
        }
        return changes.stream()
                .map(c -> c.label() + ": " + c.before() + "→" + c.after())
                .reduce((a, b) -> a + "; " + b)
                .orElse("");
    }

    /** 单次导出的行数上限。超限要求收窄条件，而不是静默截断。 */
    private static final long EXPORT_MAX_ROWS = 50_000;

    // ==================================================================
    // 私有
    // ==================================================================

    /** 原生行 → 列表行（列序与 {@link #LIST_COLUMNS} 一一对应）。 */
    private AuditDtos.LogRow toRow(Object[] row) {
        return new AuditDtos.LogRow(
                ((Number) row[0]).longValue(),
                formatTime(row[1]),
                (String) row[2],
                (String) row[3],
                (String) row[4],
                (String) row[5],
                (String) row[6],
                (String) row[7],
                (String) row[8],
                (String) row[9],
                (String) row[10]);
    }

    /**
     * 原生查询的 DATETIME 列 → 展示字符串。
     *
     * <p>Hibernate 7 的原生查询对 {@code DATETIME} 返回 {@link LocalDateTime}
     * （不是 {@link Timestamp}），但驱动版本变化时可能变成后者 ——
     * 两种都收，这不是过度防御，{@code KbGrantService} 里已经因为同类问题踩过一次
     * （那里记的是"单列原生查询返回 List&lt;String&gt; 而非 List&lt;Object[]&gt;"）。
     */
    private static String formatTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime ldt) {
            return TIME_FORMAT.format(ldt);
        }
        if (value instanceof Timestamp ts) {
            return TIME_FORMAT.format(ts.toLocalDateTime());
        }
        return String.valueOf(value);
    }

    /**
     * 解析前端传来的时间参数。**解析不了就明确报错**，不静默忽略。
     *
     * <p>前端 {@code el-date-picker} 配 {@code value-format="YYYY-MM-DD"}，
     * 所以发出来的是**纯日期** {@code 2026-09-15}；但《10》§11 的契约写的是
     * {@code startTime} / {@code endTime}，语义是时刻。三种写法都收：
     * <ul>
     *   <li>纯日期 + 是起始 → 补 {@code 00:00:00}；</li>
     *   <li>纯日期 + 是结束 → 补 {@code 23:59:59}，否则"选到今天"会漏掉今天的所有记录
     *       —— 这是日期范围筛选最常见的一个 off-by-one；</li>
     *   <li>带时刻（ISO 的 {@code T} 分隔或空格分隔，秒可省）。</li>
     * </ul>
     *
     * <p><b>为什么解析失败要报 10030，而不是"忽略这个条件"</b>：忽略的后果是
     * **查全表**（页面上日期范围明明显示着"今天"，列表却把 1262 行全列出来）。
     * 这正是本批 W3 修的那个缺陷 —— 页面显示的筛选条件与列表内容不一致 ——
     * 只不过 W3 是"根本没发参数"，这里是"发了但发错了"。同一类错，不能一边修一边留。
     * 报错至少让调用方当场发现。
     */
    private LocalDateTime parseTime(String raw, boolean endOfDay) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().replace('T', ' ');
        try {
            if (value.length() == 10) {
                LocalDate d = LocalDate.parse(value);
                return endOfDay ? d.atTime(23, 59, 59) : d.atStartOfDay();
            }
            return LocalDateTime.parse(value.length() > 19 ? value.substring(0, 19) : value,
                    TIME_FORMAT);
        } catch (Exception ex) {
            log.warn("审计查询时间参数无法解析：raw={}", raw);
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "时间参数格式不支持：" + raw + "（支持 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss）");
        }
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank() && !"all".equals(value);
    }
}
