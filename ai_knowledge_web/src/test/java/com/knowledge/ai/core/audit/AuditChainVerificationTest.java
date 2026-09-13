package com.knowledge.ai.core.audit;

import com.jayway.jsonpath.JsonPath;
import com.knowledge.ai.core.model.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计链路端到端验收（T-003 / T-005 · 门 G0-3 / G0-4）。
 *
 * <p>S0 结项时补的一块证据：审计切面此前只有框架、**全项目零处 {@code @AuditLog}**，
 * 因此「注解加上去真的会落库吗」「业务回滚后审计还在吗」两个问题都没有答案。
 * 这两条恰好是 D-1（审计必须先于业务落地）成立的前提，不能靠"代码看着对"结项。
 *
 * <p>三个测试分别对应三门禁：
 * <ol>
 *   <li><b>G0-3</b>：一次真实 HTTP 请求，比对 response body 的 traceId、
 *       日志 MDC 里的 traceId、审计记录的 trace_id —— 三者必须相同。
 *       日志那一处靠探针把 {@code MDC.get("traceId")} 回显出来取证
 *       （logback pattern 里 {@code %X{traceId}} 取的正是这个值）。</li>
 *   <li><b>G0-4 正向</b>：Service 方法只加 {@code @AuditLog}，业务代码零改动即可落库。</li>
 *   <li><b>G0-4 反向（不变量 I-12）</b>：业务事务里写库后抛异常 → 业务侧数据回滚，
 *       <b>审计记录仍在</b>。这条是 {@code REQUIRES_NEW} 存在的唯一理由：
 *       用户点过危险按钮却查不到痕迹，是安全审计最不能接受的失败。</li>
 * </ol>
 *
 * <p><b>探针为什么挂在 {@code /api/health/probe/**}</b>：S0 还没有登录能力，
 * 而 {@code SecurityConfig} 的白名单里只有 {@code /api/auth/**}、{@code /api/health/**}
 * 与 {@code /actuator/**}。探针只存在于测试上下文（{@code @TestConfiguration}），
 * 生产包里没有这些端点，挂白名单前缀是唯一不需要为测试改安全配置的做法。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuditChainVerificationTest.ProbeConfiguration.class)
class AuditChainVerificationTest {

    /** 业务侧在事务里写的标记行，用来证明业务数据确实回滚了。 */
    private static final String PROBE_SETTING_KEY = "probe.audit.rollback";

    /** 审计记录的模块与动作，断言时要对上。 */
    private static final String PROBE_MODULE = AuditModule.SYSTEM;
    private static final String PROBE_ACTION_OK = "探针-成功";
    private static final String PROBE_ACTION_FAIL = "探针-失败";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuditChainVerificationTest.class);

    @Autowired
    private AuditProbeService probeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    // ==================================================================
    // G0-3 · traceId 三处一致
    // ==================================================================

    @Test
    @DisplayName("G0-3：同一请求的 响应体 / 日志MDC / 审计记录 三处 traceId 一致")
    void traceIdIsConsistentAcrossResponseLogAndAudit() {
        String body = restClient().get()
                .uri("/api/health/probe/audit/ok")
                .retrieve()
                .body(String.class);

        assertNotNull(body, "探针应当有响应体");

        String responseTraceId = JsonPath.read(body, "$.traceId");
        String mdcTraceId = JsonPath.read(body, "$.data.mdcTraceId");
        String auditTraceIdInBody = JsonPath.read(body, "$.data.auditTraceId");

        assertTrue(responseTraceId != null && responseTraceId.matches("[0-9a-f]{16}"),
                "响应体 traceId 应为 16 位小写十六进制，实际=" + responseTraceId);

        // ① 响应体 vs ② 日志：ApiResponse 从 TraceContext.current() 取，日志 pattern 从 MDC 取，
        //    两者本应同源 —— 但「本应」不算证据，这里实际比一次。
        assertEquals(responseTraceId, mdcTraceId,
                "日志 MDC 的 traceId 与响应体不一致 —— logback pattern 的 %X{traceId} 取的就是它");

        // ③ 审计切面在同一个请求线程里读 TraceContext，写入 sys_audit_log.trace_id
        assertEquals(responseTraceId, auditTraceIdInBody,
                "审计记录里的 traceId 与响应体不一致 —— 前端按 traceId 找审计日志会找不到");

        Map<String, Object> row = findAuditRowByTraceId(responseTraceId);
        assertNotNull(row, "应当有一条 trace_id = " + responseTraceId + " 的审计记录");
        assertEquals(PROBE_MODULE, row.get("module"));
        assertEquals(PROBE_ACTION_OK, row.get("action"));
        assertEquals(AuditEvent.RESULT_SUCCESS, row.get("result"));
        assertTrue(String.valueOf(row.get("api_path")).contains("/api/health/probe/audit/ok"),
                "审计要能回溯到具体接口，实际=" + row.get("api_path"));
        assertTrue(((Number) row.get("cost_ms")).intValue() >= 0, "耗时应当被记录下来");

        // 清理：审计表唯一索引在 log_no 上，留着记录不影响其它用例，但保持现场干净
        assertEquals(1L, countAuditByTraceId(responseTraceId), "同一请求只应产生一条审计记录");
    }

    // ==================================================================
    // G0-3 · 日志那一处的可观测前提
    // ==================================================================

    @Test
    @DisplayName("G0-3：logback 配置从 MDC 取 traceId，控制台与文件两个输出都带上")
    void logbackConfigPrintsTraceIdFromMdc() throws Exception {
        String xml;
        try (java.io.InputStream in = new org.springframework.core.io.ClassPathResource("logback-spring.xml")
                .getInputStream()) {
            xml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        assertTrue(xml.contains("%X{traceId"),
                "logback 配置必须从 MDC 取 traceId —— 否则「MDC 里有值、日志里看不见」");

        // 两个输出都走同一个 TRACE 属性：只给 console 加、文件不加，
        // 就会出现"本地看得见、落盘日志里查不到"
        assertTrue(xml.contains("name=\"TRACE\""), "应当把 traceId 片段抽成一个可复用属性");
        assertTrue(xml.contains("${TRACE}"), "console 与 file 的 pattern 都应引用该属性");
        assertEquals(2, countOccurrences(xml, "${TRACE}"),
                "console / file 两个 pattern 各引用一次；数量变了说明有一路漏了 traceId");
    }

    @Test
    @DisplayName("《11》§218：第三方 HTTP 库级别被压制，避免 Authorization 头里的 API Key 进日志")
    void sdkLoggersArePinnedToPreventSecretLeak() {
        // 这不是"日志太吵"的偏好，是安全边界：这些库在 DEBUG 下会打印完整请求头，
        // 而模型调用的 API Key 就在 Authorization 头里。
        assertFalse(org.slf4j.LoggerFactory.getLogger("okhttp3").isDebugEnabled(),
                "okhttp3 不能放开到 DEBUG —— 会把带 API Key 的请求头写进日志");
        assertFalse(org.slf4j.LoggerFactory.getLogger("com.openai").isDebugEnabled(),
                "OpenAI SDK 不能放开到 DEBUG —— 同上");
        assertFalse(org.slf4j.LoggerFactory.getLogger("io.milvus").isDebugEnabled(),
                "Milvus SDK 的 DEBUG 噪音过大，且可能带连接串");

        // 项目自己的日志要能正常输出，否则上面三条"压制"可能只是因为整体级别配置错了
        assertTrue(org.slf4j.LoggerFactory.getLogger("com.knowledge.ai").isInfoEnabled(),
                "项目包的 INFO 必须可用，否则等于把日志关掉了");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    // ==================================================================
    // G0-4 · 只加注解即落库
    // ==================================================================

    @Test
    @DisplayName("G0-4：Service 只加 @AuditLog 注解，业务代码零改动即落库")
    void annotationAlonePersistsAuditRecord() {
        long before = countAuditByAction(PROBE_ACTION_OK);

        String returned = probeService.doSomethingAudited("hello");

        assertEquals("hello", returned, "业务返回值不应被切面改动");
        assertEquals(before + 1, countAuditByAction(PROBE_ACTION_OK),
                "@AuditLog 加上去就应当多一条审计记录 —— 业务侧不该写任何审计代码");

        Map<String, Object> row = findLatestAuditByAction(PROBE_ACTION_OK);
        assertNotNull(row);
        assertEquals(AuditEvent.RESULT_SUCCESS, row.get("result"));
        assertEquals("AuditProbeService#doSomethingAudited", row.get("target"),
                "target 未写时默认取「类名#方法名」，便于定位来源");
    }

    // ==================================================================
    // G0-4 · 业务回滚后审计仍在（I-12）
    // ==================================================================

    @Test
    @DisplayName("G0-4 / I-12：业务事务回滚后，审计记录仍在（REQUIRES_NEW）")
    void auditSurvivesBusinessRollback() {
        jdbcTemplate.update("DELETE FROM sys_setting WHERE setting_key = ?", PROBE_SETTING_KEY);
        long before = countAuditByAction(PROBE_ACTION_FAIL);

        // 探针方法内部：先写一行 sys_setting，再抛异常 → 整个业务事务回滚
        assertThrows(IllegalStateException.class, () -> probeService.writeThenFail());

        Integer businessRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_setting WHERE setting_key = ?", Integer.class, PROBE_SETTING_KEY);
        assertEquals(0, businessRows,
                "业务数据应当被回滚 —— 否则这个用例证明不了「审计独立于业务事务」");

        assertEquals(before + 1, countAuditByAction(PROBE_ACTION_FAIL),
                "业务回滚把审计也带走了 —— REQUIRES_NEW 失效，I-12 被破坏");

        Map<String, Object> row = findLatestAuditByAction(PROBE_ACTION_FAIL);
        assertNotNull(row);
        assertEquals(AuditEvent.RESULT_FAILED, row.get("result"), "失败操作也必须留痕");
        String failReason = String.valueOf(row.get("fail_reason"));
        assertTrue(failReason.contains("IllegalStateException") && failReason.contains("探针主动失败"),
                "失败原因要能定位到具体异常，实际=" + failReason);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private RestClient restClient() {
        return RestClient.create("http://127.0.0.1:" + port);
    }

    private Map<String, Object> findAuditRowByTraceId(String traceId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM sys_audit_log WHERE trace_id = ? ORDER BY id DESC", traceId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long countAuditByTraceId(String traceId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_audit_log WHERE trace_id = ?", Long.class, traceId);
        return count == null ? 0L : count;
    }

    private long countAuditByAction(String action) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_audit_log WHERE action = ?", Long.class, action);
        return count == null ? 0L : count;
    }

    private Map<String, Object> findLatestAuditByAction(String action) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM sys_audit_log WHERE action = ? ORDER BY id DESC LIMIT 1", action);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==================================================================
    // 探针（仅存在于测试上下文）
    // ==================================================================

    /**
     * 只存在于测试上下文的探针 Bean。
     *
     * <p>刻意不用 mock：这三个用例验的就是「真注解 + 真切面 + 真事务传播 + 真落库」，
     * 任何替身都会让结论失去意义。
     */
    @TestConfiguration
    static class ProbeConfiguration {

        @Bean
        AuditProbeService auditProbeService(JdbcTemplate jdbcTemplate) {
            return new AuditProbeService(jdbcTemplate);
        }

        @Bean
        AuditProbeController auditProbeController(AuditProbeService probeService, JdbcTemplate jdbcTemplate) {
            return new AuditProbeController(probeService, jdbcTemplate);
        }
    }

    /** 被审计的业务侧：只有注解，没有任何审计相关代码。 */
    static class AuditProbeService {

        private final JdbcTemplate jdbcTemplate;

        AuditProbeService(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @AuditLog(module = PROBE_MODULE, action = PROBE_ACTION_OK, risk = RiskLevel.NORMAL)
        public String doSomethingAudited(String input) {
            return input;
        }

        /**
         * 业务事务里先写库再抛异常：业务数据回滚，审计必须留下。
         *
         * <p>模拟真实场景 —— 「删除知识库」先删了子表，随后校验失败回滚，
         * 但用户确实点过这个危险按钮，痕迹不能丢。
         */
        @AuditLog(module = PROBE_MODULE, action = PROBE_ACTION_FAIL, risk = RiskLevel.HIGH)
        @Transactional
        public void writeThenFail() {
            jdbcTemplate.update(
                    "INSERT INTO sys_setting (setting_key, setting_value, value_type, group_name, label, description, editable, updated_at) "
                            + "VALUES (?, '1', 'INT', '安全', '审计回滚探针', '仅测试用，业务回滚后应消失', 1, NOW())",
                    PROBE_SETTING_KEY);
            throw new IllegalStateException("探针主动失败：用于验证审计不被业务回滚带走");
        }
    }

    /** 把 MDC 里的 traceId 回显出来，作为「日志那一处」的取证。 */
    @RestController
    @RequestMapping("/api/health/probe/audit")
    static class AuditProbeController {

        private final AuditProbeService probeService;

        /**
         * 自己持有一份 JdbcTemplate，<b>不透过 probeService 读它的字段</b>。
         *
         * <p>教训：{@code AuditProbeService} 同时被 {@code @AuditLog} 切面与
         * {@code @Transactional} 代理（CGLIB 子类），字段只存在于**目标对象**上，
         * 从代理对象读字段得到的是 {@code null}。同理，任何依赖都要走方法或独立注入。
         */
        private final JdbcTemplate jdbcTemplate;

        AuditProbeController(AuditProbeService probeService, JdbcTemplate jdbcTemplate) {
            this.probeService = probeService;
            this.jdbcTemplate = jdbcTemplate;
        }

        @GetMapping("/ok")
        public ApiResponse<Map<String, String>> ok() {
            String returned = probeService.doSomethingAudited("probe");
            // 刻意**不在消息里写 traceId**：这样日志行里若出现 traceId，只可能来自
            // logback pattern 的 %X{traceId} —— 是 pattern 生效的硬证据，不是自证。
            log.info("审计链路探针：成功分支已执行");
            Map<String, String> data = new HashMap<>();
            // 日志 pattern 里的 %X{traceId} 取的就是 MDC，这里回显它即可代表"日志里看到的那个值"
            data.put("mdcTraceId", MDC.get("traceId"));
            data.put("auditTraceId", latestAuditTraceId());
            data.put("returned", returned);
            return ApiResponse.ok(data);
        }

        private String latestAuditTraceId() {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT trace_id FROM sys_audit_log WHERE action = ? ORDER BY id DESC LIMIT 1", PROBE_ACTION_OK);
            return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("trace_id"));
        }
    }

    /** 断言辅助：确认审计表确实不可被业务代码删除（仓储不提供 delete 自定义方法）。 */
    @Test
    @DisplayName("G0-4：审计仓储未暴露任何删除入口（审计不可篡改）")
    void auditRepositoryExposesNoDeleteApi() {
        boolean hasCustomDelete = java.util.Arrays.stream(
                        com.knowledge.ai.repository.SysAuditLogRepository.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().toLowerCase().contains("delete"));
        assertFalse(hasCustomDelete, "审计仓储不应有自定义删除方法 —— 审计日志不可编辑、不可删除");
    }
}
