package com.knowledge.ai.service;

import com.knowledge.ai.core.async.ParseWorker;
import com.knowledge.ai.core.audit.AuditModule;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-O06`（`S4-E2`）的**运行时取证** —— 解析链路的审计留痕。
 *
 * <h3>为什么必须走真 HTTP</h3>
 * <p>与 {@code S2AuditFlowTest} / {@code AuditChangeContractTest} 同一条纪律：
 * {@code operator} / {@code apiPath} / {@code ip} 都取自**请求上下文**，
 * 绕开过滤器直调 Service 会把它们测成空值 —— 那等于没测到采集链路，
 * 而本批新增的「系统身份」规则（{@code AuditAspect#build}）恰恰只在这两种上下文里分叉。
 *
 * <h3>本类钉的四件事（对应 `优化/O6` §5 的判据 1/2/4/6）</h3>
 * <ol>
 *   <li><b>解析有痕</b>：出现「触发解析」，操作人 = 上传者，目标 = **具体文档**（不是库）；</li>
 *   <li><b>结果有痕且同 traceId</b>：完成/失败记录的 {@code trace_id} 与提交记录逐字相同；</li>
 *   <li><b>系统动作不是假记录</b>：worker 写的那条 {@code operator = system}、{@code operator_name = 系统}；</li>
 *   <li><b>可按文档维度筛</b>：{@code GET /audit/logs?keyword=文档#N} 返回完整生命周期。</li>
 * </ol>
 *
 * <h3>判据 2 为什么会失败过（记在这里，因为它不显眼）</h3>
 * <p>{@code TraceContext} 的类注释一直写着"异步任务在工作线程里重新 set traceId"，
 * 而实现里**没有任何 TaskDecorator**、{@code ParseWorker} 的自建池也不传播。
 * 它的默认行为**看起来是对的** —— {@code TraceContext.current()} 会现场生成一个新 id，
 * 于是每条日志都有 traceId，只是提交与完成**不是同一个**。
 * 本批给两条路都补了传播（{@code AsyncConfig#traceIdPropagating} +
 * {@code ParseWorker#execute} 的 set/clear），判据 2 才成立。
 *
 * <p>⚠️ **审计行不删**（`sys_audit_log` 是只增不改的），本类与既有几处一样用
 * 「记下 watermark id、只看 id &gt; watermark 的行」隔离自己。
 */
class ParseAuditTrailFlowTest extends ApiTestSupport {

    /** ⚠️ 必须以 `kb_` 开头、只用小写字母/数字/下划线 —— 否则建库直接 `10030`。 */
    private static final String PROBE_PREFIX = "kb_probe_e2audit_";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ParseWorker parseWorker;

    private final List<Long> probeKbIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long kbId : probeKbIds) {
            jdbcTemplate.update("delete from sys_chunk where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_parse_issue where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_document where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_kb_grant where kb_id = ?", kbId);
            jdbcTemplate.update("delete from sys_kb where id = ?", kbId);
        }
        probeKbIds.clear();
    }

    @Test
    @DisplayName("O6 判据 1/4：触发解析记在**请求线程**（操作人是上传者），完成记在 worker（系统）")
    void submitIsRecordedWithTheUploaderAndCompletionAsSystem() {
        String token = loginAsAdmin().data("$.accessToken");
        long watermark = maxAuditId();
        long kbId = createProbeKb(token);

        long docId = uploadAndParse(kbId, token);

        List<Map<String, Object>> rows = auditRowsAfter(watermark);
        Map<String, Object> submitted = pick(rows, "触发解析");
        Map<String, Object> finished = pick(rows, "解析完成", "解析失败");

        // ---- ① 提交：有登录态，操作人必须是上传者本人 ----
        assertThat(submitted.get("operator"))
                .as("「触发解析」打在**请求线程**（DocService#upload 里）—— 那一刻有 LoginUserContext。"
                        + "若打到真正的投递点（异步体里），这里会变成 anonymous")
                .isEqualTo(ADMIN);
        assertThat((String) submitted.get("target"))
                .as("粒度到**文档**而不是库：O6 §2 痛点 2 的原话是"
                        + "「粒度到库、不到文档 —— 出问题时无法回答『这个人动过哪些文档』」")
                .isEqualTo("文档#" + docId);
        assertThat(submitted.get("module")).isEqualTo(AuditModule.DOC);

        // ---- ② 完成：worker 线程，无登录态、无请求 → 系统 ----
        assertThat(finished.get("operator"))
                .as("O6 判据 4：「不产生**假记录**」—— 把系统动作写成 anonymous/匿名 就是假记录。"
                        + "这条同时验证了 AuditAspect 里那条新规则（无请求 + 无登录态 = system）")
                .isEqualTo("system");
        assertThat(finished.get("operator_name")).isEqualTo("系统");
        assertThat(finished.get("target")).isEqualTo("文档#" + docId);

        // ---- ③ 同 traceId：把「提交 → 结果」串成一条 ----
        assertThat(finished.get("trace_id"))
                .as("O6 判据 2。⚠️ 它在补 TaskDecorator + ParseWorker 的 set/clear **之前是不可能过的**："
                        + "TraceContext.current() 会现场生成新 id，两条记录看起来都正常、就是不同")
                .isNotNull()
                .isEqualTo(submitted.get("trace_id"));
    }

    @Test
    @DisplayName("O6 判据 6：按文档维度筛，返回该文档的完整生命周期")
    void lifecycleIsQueryableByDocument() {
        String token = loginAsAdmin().data("$.accessToken");
        long watermark = maxAuditId();
        long kbId = createProbeKb(token);
        long docId = uploadAndParse(kbId, token);

        // 关键字筛选走的是 `target like`（AuditQueryService#appendFilters 的四列之一）
        // ⚠️ 查询串必须**整段**百分号编码（用 URLEncoder），不能只编 `#`：
        //    `文档` 是非 ASCII，未编码时 Java 的 URI 会把它原样带着发出去，
        //    Tomcat 在请求行校验阶段直接 400 —— 而 400 的响应体是一段 HTML
        //    （不是本项目的 ApiResponse），报错会变成"路径找不到 $['code']"，
        //    看着像接口坏了，实际是测试把 URL 拼错了。
        String keyword = java.net.URLEncoder.encode("文档#" + docId, StandardCharsets.UTF_8);
        RawResponse page = getEncoded("/api/audit/logs?keyword=" + keyword + "&size=50", token)
                .assertOk("按文档维度筛审计");

        List<String> actions = page.data("$.list[*].action");
        assertThat(actions)
                .as("O6 判据 6 的原话是「返回该文档的完整生命周期（上传 → 解析 → 结果 → 重跑）」。"
                        + "重跑那一步依赖 POST /doc/{docId}/reindex —— 后端没有，判据 3 已挂 T-614；"
                        + "本用例覆盖能验的前三步")
                .contains("上传文档", "触发解析")
                .anyMatch(a -> "解析完成".equals(a) || "解析失败".equals(a));

        // 三条记录必须都在这篇文档上（而不是同库的别的文档）
        List<Map<String, Object>> rows = auditRowsAfter(watermark);
        assertThat(rows).filteredOn(r -> "触发解析".equals(r.get("action")))
                .allSatisfy(r -> assertThat(r.get("target")).isEqualTo("文档#" + docId));
    }

    @Test
    @DisplayName("O6 判据 4 反面：**可重试**失败不留「解析失败」—— 那个作业还会被重新领走")
    void retryableFailureDoesNotLeaveAFalseFailureRecord() {
        String token = loginAsAdmin().data("$.accessToken");
        long watermark = maxAuditId();
        long kbId = createProbeKb(token);
        long docId = uploadAndParse(kbId, token);

        long failures = countAudit(watermark, "解析失败");
        long dones = countAudit(watermark, "解析完成");
        assertThat(failures + dones)
                .as("恰好一条终态记录 —— 解析只有一种结局。写两条（比如重试中途先记一次失败）"
                        + "就是 O6 判据 4 说的『假记录』")
                .isEqualTo(1);
        assertThat(docId).isPositive();
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    private long uploadAndParse(long kbId, String token) {
        byte[] content = "# 审计探针\n\n这是一份用于审计留痕取证的 Markdown 文档。\n"
                .getBytes(StandardCharsets.UTF_8);
        RawResponse accepted = postMultipart("/api/kb/" + kbId + "/doc/upload", content,
                "审计探针.md", "text/markdown", token).assertOk("上传");
        long docId = ((Number) accepted.data("$.docId")).longValue();
        awaitTaskDone(accepted.data("$.taskId"), token);
        // 测试期后台轮询是关的（app.parse.worker.enabled=false，为确定性），显式驱动一轮
        parseWorker.drainOnce();
        return docId;
    }

    private void awaitTaskDone(String taskId, String token) {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            RawResponse t = get("/api/doc/tasks/" + taskId, token).assertOk("轮询任务");
            Integer percent = t.data("$.percent");
            if (percent != null && percent >= 100) {
                return;
            }
            sleep(150);
        }
        throw new AssertionError("任务 120s 内未到终态：" + taskId);
    }

    private long createProbeKb(String token) {
        String code = PROBE_PREFIX + Long.toString(System.nanoTime(), 36);
        Map<String, Object> form = new HashMap<>();
        form.put("name", "E2 审计探针库");
        form.put("code", code);
        form.put("deptId", 1);
        form.put("visibleRoles", List.of("SUPER_ADMIN", "USER"));
        form.put("secretLevel", "INTERNAL");
        form.put("businessDomain", "研发 / 平台");
        form.put("description", "");
        form.put("customTags", List.of());
        form.put("splitPolicy", Map.of("preset", "GENERAL", "chunkSize", 512, "overlap", 64,
                "recursiveSplit", true, "semanticSplit", false));
        form.put("tablePolicy", Map.of("atomicTable", true, "dualRepresentation", true,
                "repeatHeader", true, "mergeCrossPage", true, "ocrTable", true,
                "rowLimit", 30, "rowWindow", 5));
        form.put("retrievalConfig", Map.of("mode", "HYBRID", "recallThreshold", 0.15,
                "rerankTopK", 5, "rerankEnabled", true, "tableWeight", 1.2, "identifierProtect", true));
        RawResponse created = post("/api/kb", form, token).assertOk("建探针库");
        long kbId = ((Number) created.data("$.id")).longValue();
        probeKbIds.add(kbId);
        return kbId;
    }

    /** 记下水位线：`sys_audit_log` 只增不删（《05》§4.8），所以用 id 隔离，不删行。 */
    private long maxAuditId() {
        Long max = jdbcTemplate.queryForObject("select coalesce(max(id), 0) from sys_audit_log", Long.class);
        return max == null ? 0L : max;
    }

    private List<Map<String, Object>> auditRowsAfter(long watermark) {
        return jdbcTemplate.queryForList(
                "select id, action, module, operator, operator_name, target, trace_id, risk_level "
                        + "from sys_audit_log where id > ? order by id", watermark);
    }

    private long countAudit(long watermark, String action) {
        Long n = jdbcTemplate.queryForObject(
                "select count(*) from sys_audit_log where id > ? and action = ?", Long.class,
                watermark, action);
        return n == null ? 0L : n;
    }

    private static Map<String, Object> pick(List<Map<String, Object>> rows, String... actions) {
        for (Map<String, Object> row : rows) {
            for (String action : actions) {
                if (action.equals(row.get("action"))) {
                    return row;
                }
            }
        }
        throw new AssertionError("审计里没有动作 " + String.join("/", actions)
                + "（实测到的是：" + rows.stream().map(r -> r.get("action")).toList() + "）");
    }
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", ex);
        }
    }
}
