package com.knowledge.ai.service;

import com.jayway.jsonpath.JsonPath;
import com.knowledge.ai.core.enums.KbConfigDefaults;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M04 阶段 B2 门禁：T-404 保存配置 / T-405 影响预估 / T-408 配额 / T-411 解析配置。
 *
 * <h3>为什么全部走真 HTTP 且不碰演示库</h3>
 * <p>与 {@code KbModuleFlowTest} 同理由（真 HTTP 才测得到 SpEL / 审计 / 上下文），
 * 但本批**多一条硬约束**：{@code T-404} 是"全量覆盖式写"，任何拿演示库当靶子的用例
 * 都会把 V9/V10 回填的种子值改掉，而且在 {@code finally} 里"把原值写回去"**不是
 * no-op** —— 它会把原本可能不同的行统一成同一个值（本仓已踩过这个坑，
 * {@code sprint-delivery} §3 明写）。所以：
 * <ol>
 *   <li>所有写用例**自己建库**（{@code kb_probe_} 前缀）再改它，用完删；</li>
 *   <li>涉及演示库时**只读**（{@code storage-quota} 的 1246 / 38502 就是这么断言的）。</li>
 * </ol>
 */
class KbWriteFlowTest extends ApiTestSupport {

    /** 演示账号的统一口令（V6 建号时写死），与 {@code KbModuleFlowTest} 同一口径。 */
    private static final String DEMO_PASSWORD = "Init@12345";

    /**
     * 只用来"把请求体填合法"的占位编码（预估接口不落库，编码不会被写入）。
     * 必须满足 {@code ^kb_[a-z0-9_]{2,}$}，否则被 {@code @Valid} 拦成 10030。
     */
    private static final String VALID_SHAPE_CODE = "kb_placeholder";

    @Autowired
    private KbService kbService;

    // ==================================================================
    // T-404 · 保存配置
    // ==================================================================

    @Test
    @DisplayName("G3-3：只改名称/描述 → needReindex=false；改 chunkSize → true")
    void saveReturnsNeedReindex() {
        String token = loginAsAdmin().data("$.accessToken");
        String code = probeCode();
        try {
            long id = createProbeKb(token, code);

            // ① 纯基础信息改动：不使索引失效
            Map<String, Object> basic = formWith(f -> {
                f.put("code", code);
                f.put("name", "B2 改名后的探针库");
                f.put("description", "只改了描述");
            }, code);
            RawResponse basicSaved = put("/api/kb/" + id + "/config", wrap(basic), token).assertOk("改基础信息");
            assertThat((Boolean) basicSaved.data("$.needReindex"))
                    .as("改名称/描述不使索引失效 —— 判成 true 会让用户以为每次改名都要重建")
                    .isFalse();

            // ② 改 chunkSize：必须使索引失效
            Map<String, Object> splitChanged = formWith(f -> {
                f.put("code", code);
                f.put("name", "B2 改名后的探针库");
                f.put("description", "只改了描述");
                f.put("splitPolicy", Map.of("preset", "GENERAL", "chunkSize", 256, "overlap", 32,
                        "recursiveSplit", true, "semanticSplit", false));
            }, code);
            RawResponse splitSaved = put("/api/kb/" + id + "/config", wrap(splitChanged), token).assertOk("改 chunkSize");
            assertThat((Boolean) splitSaved.data("$.needReindex"))
                    .as("chunkSize 直接决定分片边界，改了必须重建")
                    .isTrue();

            // ③ 库里也标了脏（返回值与落库是两件事，都验）
            assertThat(reindexRequired(id)).as("needReindex 必须同时落到 sys_kb.reindex_required").isTrue();
        } finally {
            deleteKbByCode(code);
        }
    }

    @Test
    @DisplayName("T-404：重建字段集与前端 REBUILD_FIELDS 逐字同源（14 触发 / 7 不触发）")
    void rebuildFieldSetMatchesFrontend() {
        String token = loginAsAdmin().data("$.accessToken");
        String code = probeCode();
        try {
            long id = createProbeKb(token, code);
            String url = "/api/kb/" + id + "/config";

            // ⚠️ 必须**始终在同一个表单对象上改**，不能每轮都从默认值重建 ——
            //    服务端 diff 是「提交值 vs 库内当前值」，每轮重置表单会让上一轮改过的字段
            //    在下一轮又变回去，凭空多出一条 diff。第一次写这个用例就是这么错的：
            //    分片循环把 rowWindow 改成 9 之后，「只改 name」那一轮的表单 rowWindow 回到 5，
            //    于是 needReindex 被 rowWindow 顶成 true，看起来像「改名字触发了重建」。
            Map<String, Object> current = formWith(f -> f.put("code", code), code);

            // 14 个重建字段：逐条改（每条都改到**与当前值不同**的值，否则 diff 为空、
            // needReindex 恒 false，用例会「全绿但什么都没测」——最坏的一种假绿）
            Map<String, java.util.function.Consumer<Map<String, Object>>> rebuildFields = new LinkedHashMap<>();
            rebuildFields.put("splitPolicy.preset", f -> f.put("splitPolicy", splitOf("CUSTOM", 512, 64, true, false)));
            rebuildFields.put("splitPolicy.chunkSize", f -> f.put("splitPolicy", splitOf("CUSTOM", 320, 64, true, false)));
            rebuildFields.put("splitPolicy.overlap", f -> f.put("splitPolicy", splitOf("CUSTOM", 320, 32, true, false)));
            rebuildFields.put("splitPolicy.semanticSplit", f -> f.put("splitPolicy", splitOf("CUSTOM", 320, 32, true, true)));
            rebuildFields.put("retrievalConfig.mode", f -> f.put("retrievalConfig", retrievalOf("VECTOR", 0.15, 5, true)));
            rebuildFields.put("retrievalConfig.recallThreshold", f -> f.put("retrievalConfig", retrievalOf("VECTOR", 0.42, 5, true)));
            rebuildFields.put("retrievalConfig.rerankTopK", f -> f.put("retrievalConfig", retrievalOf("VECTOR", 0.42, 9, true)));
            rebuildFields.put("retrievalConfig.rerankEnabled", f -> f.put("retrievalConfig", retrievalOf("VECTOR", 0.42, 9, false)));
            rebuildFields.put("tablePolicy.dualRepresentation", f -> f.put("tablePolicy", tableOf(true, false, true, true, true, 30, 5)));
            rebuildFields.put("tablePolicy.repeatHeader", f -> f.put("tablePolicy", tableOf(true, false, false, true, true, 30, 5)));
            rebuildFields.put("tablePolicy.mergeCrossPage", f -> f.put("tablePolicy", tableOf(true, false, false, false, true, 30, 5)));
            rebuildFields.put("tablePolicy.ocrTable", f -> f.put("tablePolicy", tableOf(true, false, false, false, false, 30, 5)));
            rebuildFields.put("tablePolicy.rowLimit", f -> f.put("tablePolicy", tableOf(true, false, false, false, false, 12, 5)));
            rebuildFields.put("tablePolicy.rowWindow", f -> f.put("tablePolicy", tableOf(true, false, false, false, false, 12, 9)));

            for (Map.Entry<String, java.util.function.Consumer<Map<String, Object>>> e : rebuildFields.entrySet()) {
                resetReindexFlag(id);
                Map<String, Object> next = new LinkedHashMap<>(current);
                e.getValue().accept(next);
                RawResponse saved = put(url, wrap(next), token).assertOk(e.getKey());
                assertThat((Boolean) saved.data("$.needReindex"))
                        .as("前端 REBUILD_FIELDS 含 " + e.getKey() + " —— 服务端必须同判，"
                                + "否则页面按本地 diff 调 /reindex、库里却没标脏（或反之），两边永远说不拢")
                        .isTrue();
                current = next;
            }

            // 反向：这 7 个明确**不**触发重建（含 visibleRoles —— 它改授权但不改索引）
            Map<String, java.util.function.Consumer<Map<String, Object>>> tagFields = new LinkedHashMap<>();
            tagFields.put("name", f -> f.put("name", "换个名字试试"));
            tagFields.put("description", f -> f.put("description", "换个描述试试"));
            tagFields.put("deptId", f -> f.put("deptId", 4));
            tagFields.put("secretLevel", f -> f.put("secretLevel", "SECRET"));
            tagFields.put("businessDomain", f -> f.put("businessDomain", "运维 / 安全"));
            tagFields.put("customTags", f -> f.put("customTags", List.of("换个标签")));
            tagFields.put("visibleRoles", f -> f.put("visibleRoles", List.of("SUPER_ADMIN", "GUEST")));

            for (Map.Entry<String, java.util.function.Consumer<Map<String, Object>>> e : tagFields.entrySet()) {
                resetReindexFlag(id);
                Map<String, Object> next = new LinkedHashMap<>(current);
                e.getValue().accept(next);
                RawResponse saved = put(url, wrap(next), token).assertOk(e.getKey());
                assertThat((Boolean) saved.data("$.needReindex"))
                        .as("前端 REBUILD_FIELDS **不含** " + e.getKey() + " —— 服务端若判 true，"
                                + "用户每改一次标签就要白等一次全库重建")
                        .isFalse();
                current = next;
            }
        } finally {
            deleteKbByCode(code);
        }
    }

    private static Map<String, Object> splitOf(String preset, int chunkSize, int overlap,
                                               boolean recursive, boolean semantic) {
        return Map.of("preset", preset, "chunkSize", chunkSize, "overlap", overlap,
                "recursiveSplit", recursive, "semanticSplit", semantic);
    }

    private static Map<String, Object> tableOf(boolean atomic, boolean dual, boolean repeat,
                                               boolean merge, boolean ocr, int rowLimit, int rowWindow) {
        return Map.of("atomicTable", atomic, "dualRepresentation", dual, "repeatHeader", repeat,
                "mergeCrossPage", merge, "ocrTable", ocr, "rowLimit", rowLimit, "rowWindow", rowWindow);
    }

    private static Map<String, Object> retrievalOf(String mode, double threshold, int topK, boolean rerank) {
        return Map.of("mode", mode, "recallThreshold", threshold, "rerankTopK", topK,
                "rerankEnabled", rerank, "tableWeight", 1.2, "identifierProtect", true);
    }

    private void resetReindexFlag(long kbId) {
        jdbc.update("update sys_kb set reindex_required = 0 where id = ?", kbId);
    }

    @Test
    @DisplayName("T-404：reindex_required 是粘性的 —— 后续只改描述不清零")
    void reindexFlagIsSticky() {
        String token = loginAsAdmin().data("$.accessToken");
        String code = probeCode();
        try {
            long id = createProbeKb(token, code);
            assertThat(reindexRequired(id)).as("新库无需重建").isFalse();

            // 第一次：改分片 → 置脏
            put("/api/kb/" + id + "/config", wrap(formWith(f -> {
                f.put("code", code);
                f.put("splitPolicy", Map.of("preset", "CONTRACT", "chunkSize", 384, "overlap", 48,
                        "recursiveSplit", true, "semanticSplit", false));
            }, code)), token).assertOk("首次置脏");
            assertThat(reindexRequired(id)).isTrue();

            // 第二次：只改描述（needReindex=false）→ **不得清零**
            RawResponse second = put("/api/kb/" + id + "/config", wrap(formWith(f -> {
                f.put("code", code);
                f.put("description", "改个描述");
                f.put("splitPolicy", Map.of("preset", "CONTRACT", "chunkSize", 384, "overlap", 48,
                        "recursiveSplit", true, "semanticSplit", false));
            }, code)), token).assertOk("再改描述");
            assertThat((Boolean) second.data("$.needReindex"))
                    .as("本次确实没有使索引失效").isFalse();
            assertThat(reindexRequired(id))
                    .as("上一次的脏还在（唯一能清它的是 S4 的 T-406 重建）—— "
                            + "这里若清零，页面会显示「索引是新的」而实际不是")
                    .isTrue();
        } finally {
            deleteKbByCode(code);
        }
    }

    @Test
    @DisplayName("T-404：全字段落库可回读（判据 3 的写侧版本）")
    void saveThenReadBackIsIdentical() {
        String token = loginAsAdmin().data("$.accessToken");
        String code = probeCode();
        try {
            long id = createProbeKb(token, code);

            Map<String, Object> form = formWith(f -> {
                f.put("code", code);
                f.put("name", "B2 回读探针库");
                f.put("description", "保存后逐字段回读");
                f.put("deptId", 2);
                f.put("secretLevel", "CONFIDENTIAL");
                f.put("businessDomain", "销售 / 售前");
                f.put("customTags", List.of("B2", "回读"));
                f.put("splitPolicy", Map.of("preset", "CUSTOM", "chunkSize", 768, "overlap", 128,
                        "recursiveSplit", false, "semanticSplit", true));
                f.put("tablePolicy", Map.of("atomicTable", false, "dualRepresentation", false,
                        "repeatHeader", true, "mergeCrossPage", false, "ocrTable", false,
                        "rowLimit", 55, "rowWindow", 9));
                f.put("retrievalConfig", Map.of("mode", "VECTOR", "recallThreshold", 0.31,
                        "rerankTopK", 7, "rerankEnabled", false, "tableWeight", 1.7,
                        "identifierProtect", false));
                f.put("visibleRoles", List.of("SUPER_ADMIN", "KB_ADMIN"));
            });
            put("/api/kb/" + id + "/config", wrap(form), token).assertOk("保存全字段");

            RawResponse config = get("/api/kb/" + id + "/config", token).assertOk("回读");
            assertThat((String) config.data("$.config.name")).isEqualTo("B2 回读探针库");
            assertThat((String) config.data("$.config.description")).isEqualTo("保存后逐字段回读");
            assertThat(config.data("$.config.deptId").toString()).isEqualTo("2");
            assertThat((String) config.data("$.config.secretLevel")).isEqualTo("CONFIDENTIAL");
            assertThat((String) config.data("$.config.businessDomain")).isEqualTo("销售 / 售前");
            assertThat((List<String>) config.data("$.config.customTags")).isEqualTo(List.of("B2", "回读"));
            // 泛型方法显式给类型，否则命中 assertThat(Predicate) 重载编译不过（B1 §8.1）
            assertThat((Map<String, Object>) config.data("$.config.splitPolicy"))
                    .as("手改参数后 preset 必须是 CUSTOM，且 chunkSize 是手改的 768")
                    .isEqualTo(form.get("splitPolicy"));
            assertThat((Map<String, Object>) config.data("$.config.tablePolicy")).isEqualTo(form.get("tablePolicy"));
            assertThat((Map<String, Object>) config.data("$.config.retrievalConfig"))
                    .isEqualTo(form.get("retrievalConfig"));
            assertThat((List<String>) config.data("$.visibleRoles"))
                    .as("visibleRoles 是取自 sys_kb_grant 的角色 code")
                    .containsExactly("SUPER_ADMIN", "KB_ADMIN");
            assertThat((String) config.data("$.department"))
                    .as("归属部门改名后页面显示的必须是新部门名（现拼，不落库）")
                    .isEqualTo("销售中心");
        } finally {
            deleteKbByCode(code);
        }
    }

    @Test
    @DisplayName("T-404：code 提交了不同的值 → 10030，且库内值不变")
    void codeCannotBeChanged() {
        String token = loginAsAdmin().data("$.accessToken");
        String code = probeCode();
        try {
            long id = createProbeKb(token, code);
            RawResponse denied = put("/api/kb/" + id + "/config",
                    wrap(formWith(f -> f.put("code", "kb_something_else"), code)), token);
            assertThat(denied.code())
                    .as("编码是创建后不可改的持久标识（uk_kb_code）—— 静默忽略会让用户以为改成功了")
                    .isEqualTo(10030);
            assertThat(jdbc.queryForObject("select code from sys_kb where id = ?", String.class, id))
                    .as("被拒的请求不得改动任何东西")
                    .isEqualTo(code);
        } finally {
            deleteKbByCode(code);
        }
    }

    @Test
    @DisplayName("T-404：校验与创建同口径（名称 1 字 / 角色空集 / 几何非法）")
    void saveValidationMatchesCreate() {
        String token = loginAsAdmin().data("$.accessToken");
        String code = probeCode();
        try {
            long id = createProbeKb(token, code);

            // ① 名称 1 字 → 10030（@Size(min=2)）—— 验证 @Valid 真的级联进了 record 组件
            assertThat(put("/api/kb/" + id + "/config",
                    wrap(formWith(f -> {
                        f.put("code", code);
                        f.put("name", "短");
                    }, code)), token).code())
                    .as("名称 1 字必须被拦 —— 不拦说明 @Valid 没级联进 KbConfigRequest.config")
                    .isEqualTo(10030);

            // ② 可见角色空集 → 10030（@NotEmpty）
            assertThat(put("/api/kb/" + id + "/config",
                    wrap(formWith(f -> {
                        f.put("code", code);
                        f.put("visibleRoles", List.of());
                    }, code)), token).code())
                    .as("空集等于「保存后没人看得见」，与创建同口径拦下")
                    .isEqualTo(10030);

            // ③ overlap >= chunkSize → 10030（业务校验，无注解可表达）
            assertThat(put("/api/kb/" + id + "/config",
                    wrap(formWith(f -> {
                        f.put("code", code);
                        f.put("splitPolicy", Map.of("preset", "CUSTOM", "chunkSize", 100, "overlap", 100,
                                "recursiveSplit", true, "semanticSplit", false));
                    }, code)), token).code())
                    .as("overlap >= chunkSize 会让有效步长 <= 0，分片退化；估算侧还会除零")
                    .isEqualTo(10030);

            // ④ 部门不存在 → 10065
            assertThat(put("/api/kb/" + id + "/config",
                    wrap(formWith(f -> {
                        f.put("code", code);
                        f.put("deptId", 999_999);
                    }, code)), token).code())
                    .isEqualTo(10065);

            // 四次被拒都不该改动库里的值
            Map<String, Object> row = jdbc.queryForMap(
                    "select name, dept_id, split_policy_json from sys_kb where id = ?", id);
            assertThat(row.get("name")).as("被拒的保存不得留痕").isEqualTo("B2 探针库");
            assertThat(row.get("split_policy_json"))
                    .as("分片策略没被那次非法提交改掉")
                    .isEqualTo("{\"preset\":\"GENERAL\",\"chunkSize\":512,\"overlap\":64,"
                            + "\"recursiveSplit\":true,\"semanticSplit\":false}");
        } finally {
            deleteKbByCode(code);
        }
    }

    @Test
    @DisplayName("T-404：越权写 10022 / 不存在 10043；只读角色拿不到 kb:config → 403")
    void configWriteRejectsUnauthorized() {
        String adminToken = loginAsAdmin().data("$.accessToken");
        String code = probeCode();
        try {
            long id = createProbeKb(token(adminToken), code);

            // ① 不存在 → 10043（admin 是 ALL 范围 + 有权限，排除权限干扰）
            assertThat(put("/api/kb/999999/config",
                    wrap(formWith(f -> f.put("code", "kb_whatever"), code)), adminToken).code())
                    .isEqualTo(10043);

            // ② 越权 → 10022：zhang.wei 的部门（平台架构组 11）在 kb1 的部门（研发中心 1）之外
            String zhangToken = login("zhang.wei", DEMO_PASSWORD).assertOk("张伟登录").data("$.accessToken");
            assertThat(put("/api/kb/1/config",
                    wrap(formWith(f -> f.put("code", "kb_prod_doc"), null)), zhangToken).code())
                    .as("有 kb:config 功能权限但逐库不可见 → 10022，不是静默改成功")
                    .isEqualTo(10022);

            // ③ 功能权限不足 → 403（li.na 是 USER，没有 kb:config）
            String liNaToken = login("li.na", DEMO_PASSWORD).assertOk("李娜登录").data("$.accessToken");
            RawResponse forbidden = put("/api/kb/" + id + "/config",
                    wrap(formWith(f -> f.put("code", code), code)), liNaToken);
            assertThat(forbidden.status())
                    .as("li.na 无 kb:update/kb:config → HTTP 403（RequiresPerm 在 Service 之前拦下）")
                    .isEqualTo(403);
            assertThat(forbidden.code()).isEqualTo(10020);
        } finally {
            deleteKbByCode(code);
        }
    }

    @Test
    @DisplayName("G3-7 产出侧：保存落审计，changes_json 含真实 before/after，空值走 U+2014")
    void saveWritesAuditWithChanges() {
        String token = loginAsAdmin().data("$.accessToken");
        String code = probeCode();
        try {
            long id = createProbeKb(token, code);
            long auditBase = maxAuditId();

            // 描述从空改成有值（before 为空 → 必须规范成 —，而不是 null 或空串）
            put("/api/kb/" + id + "/config", wrap(formWith(f -> {
                f.put("code", code);
                f.put("description", "B2 审计探针");
                f.put("secretLevel", "PUBLIC");
                f.put("splitPolicy", Map.of("preset", "CUSTOM", "chunkSize", 256, "overlap", 32,
                        "recursiveSplit", true, "semanticSplit", false));
            }, code)), token).assertOk("保存");

            Map<String, Object> audit = jdbc.queryForMap("""
                    select module, action, risk_level, target, result, changes_json
                    from sys_audit_log where action = '保存配置' and id > ? order by id desc limit 1
                    """, auditBase);
            assertThat(audit.get("module")).isEqualTo("知识库");
            assertThat(audit.get("risk_level"))
                    .as("可改 visibleRoles = §4.3① 权限扩大 → HIGH")
                    .isEqualTo("HIGH");
            assertThat((String) audit.get("target")).contains("知识库#" + id);
            assertThat(audit.get("result")).isEqualTo("SUCCESS");

            String changesJson = (String) audit.get("changes_json");
            assertThat(changesJson).as("保存必有 before/after，不能是 NULL").isNotBlank();
            List<Map<String, Object>> changes = JsonPath.read(changesJson, "$");
            Map<String, Map<String, Object>> byLabel = new LinkedHashMap<>();
            changes.forEach(c -> byLabel.put((String) c.get("label"), c));

            // 本次表单实际动了四项：描述（空→有值）、密级（INTERNAL→PUBLIC）、
            // 分片策略预设（GENERAL→CUSTOM，因为 chunkSize 被手改）、chunkSize（512→256）、
            // overlap（64→32）。
            // ⚠️ 第一次用例漏了「分片策略预设」与「overlap」两项 —— containsOnlyKeys 直接把它们
            // 抓了出来。这正是它该起的作用：明细里出现**意外**字段时立刻暴露，
            // 而不是靠人工扫一遍说"看着差不多"（B1 §8.1 的同类教训）
            assertThat(byLabel).as("只有真正改了的字段才进明细，且一个不多一个不少")
                    .containsOnlyKeys("描述", "密级", "分片策略预设", "chunkSize", "overlap");
            assertThat(byLabel.get("分片策略预设").get("before")).isEqualTo("通用文档");
            assertThat(byLabel.get("分片策略预设").get("after"))
                    .as("CUSTOM 必须展示成中文「自定义」—— 与前端 presetLabel 同口径，"
                            + "否则同一条变更页面显示中文、审计显示英文")
                    .isEqualTo("自定义");
            assertThat(byLabel.get("overlap").get("before")).isEqualTo("64");
            assertThat(byLabel.get("overlap").get("after")).isEqualTo("32");
            // 未改的字段一条都不该出现（这里挑两个最容易被"顺手登记"的）
            assertThat(byLabel).as("没改的字段不得进明细").doesNotContainKeys("知识库名称", "归属部门", "可见角色");

            assertThat(byLabel.get("描述").get("before"))
                    .as("原值为空 → 必须是 EM DASH（前端按该字面量分支渲染「无」）")
                    .isEqualTo("—");
            assertThat(byLabel.get("描述").get("after")).isEqualTo("B2 审计探针");

            assertThat(byLabel.get("密级").get("before")).as("密级展示的是中文标签，不是枚举名").isEqualTo("内部");
            assertThat(byLabel.get("密级").get("after")).isEqualTo("公开");

            // chunkSize 是数字：展示文本用 String.valueOf，与前端 dirty() 的 fmt() 同口径
            assertThat(byLabel.get("chunkSize").get("before")).isEqualTo("512");
            assertThat(byLabel.get("chunkSize").get("after")).isEqualTo("256");
        } finally {
            deleteKbByCode(code);
        }
    }

    @Test
    @DisplayName("T-404：可见角色变了才重写 sys_kb_grant，且超管恒在")
    void saveRewritesGrants() {
        String token = loginAsAdmin().data("$.accessToken");
        String code = probeCode();
        try {
            long id = createProbeKb(token, code);
            String url = "/api/kb/" + id + "/config";

            // ① 加一个角色：AUDITOR → READ
            put(url, wrap(formWith(f -> {
                f.put("code", code);
                f.put("visibleRoles", List.of("SUPER_ADMIN", "AUDITOR"));
            }, code)), token).assertOk("加授权");
            Map<String, String> levels = grantLevels(id);
            assertThat(levels).containsEntry("AUDITOR", "READ").containsEntry("SUPER_ADMIN", "MANAGE");
            assertThat(levels).as("未勾选的角色不得被授权").doesNotContainKeys("KB_ADMIN", "USER");

            // ② 超管没勾也要在（否则保存一次就把超管挤出可见范围）
            put(url, wrap(formWith(f -> {
                f.put("code", code);
                f.put("visibleRoles", List.of("AUDITOR"));
            }, code)), token).assertOk("只勾审计员");
            assertThat(grantLevels(id))
                    .as("超管恒加授 —— 与创建时同口径，否则可能保存出一个创建者自己看不见的库")
                    .containsKey("SUPER_ADMIN");

            // ③ 只改名称（角色没变）→ 授权行不得被重写
            String createdAtBefore = jdbc.queryForObject(
                    "select min(created_at) from sys_kb_grant where kb_id = ?", String.class, id);
            put(url, wrap(formWith(f -> {
                f.put("code", code);
                f.put("name", "只改了名字");
                f.put("visibleRoles", List.of("AUDITOR"));
            }, code)), token).assertOk("只改名");
            assertThat(jdbc.queryForObject(
                    "select min(created_at) from sys_kb_grant where kb_id = ?", String.class, id))
                    .as("角色集没变就不该删重建 —— 重写会刷掉 created_at，破坏审计线索")
                    .isEqualTo(createdAtBefore);
        } finally {
            deleteKbByCode(code);
        }
    }

    // ==================================================================
    // T-405 · 影响预估
    // ==================================================================

    @Test
    @DisplayName("T-405：形状齐全 + chunkSize 调小则 chunkAfter 变大 + 空库返 0")
    void impactShapeAndMonotonic() {
        String token = loginAsAdmin().data("$.accessToken");
        String code = probeCode();
        try {
            long id = createProbeKb(token, code);

            // ① 形状：五个字段齐全（对齐前端 ReindexImpact 类型）
            RawResponse impact = post("/api/kb/" + id + "/config/impact",
                    wrap(formWith(f -> f.put("code", code), code)), token).assertOk("预估");
            for (String field : List.of("docCount", "chunkBefore", "chunkAfter",
                    "estimateMinutes", "estimateTokens")) {
                assertThat((Object) impact.data("$." + field)).as("响应体缺 " + field).isNotNull();
            }

            // ② 空库：不得硬凑成 1（那会把"这个库还没有文档"显示成"预计 1 个分片"）
            assertThat(((Number) impact.data("$.chunkAfter")).longValue())
                    .as("新库 chunkCount=0 → chunkAfter 必须也是 0")
                    .isZero();
            assertThat(((Number) impact.data("$.chunkBefore")).longValue()).isZero();

            // ③ 单调性：拿一个有分片的演示库（只读，用 admin 的 ALL 范围）比两次不同 chunkSize
            //    库 1 = 8240 分片、当前 chunkSize 512 / overlap 64（步长 448）
            long wide = chunkAfterFor(1L, 1024, 128, token);
            long narrow = chunkAfterFor(1L, 256, 32, token);
            assertThat(narrow)
                    .as("窗口减半 → 同样正文切出的分片必须变多（估算模型的方向性）")
                    .isGreaterThan(wide);
            assertThat(chunkAfterFor(1L, 512, 64, token))
                    .as("参数没变 → 估算值应等于现值本身")
                    .isEqualTo(8240L);
        } finally {
            deleteKbByCode(code);
        }
    }

    // ==================================================================
    // T-408 · 存储配额
    // ==================================================================

    @Test
    @DisplayName("T-408：配额与数据字典一致（8 库 / 1246 文档 / 38502 分片）")
    void storageQuotaMatchesDictionary() {
        String token = loginAsAdmin().data("$.accessToken");
        RawResponse quota = get("/api/kb/storage-quota", token).assertOk("配额");

        for (String field : List.of("used", "kbCount", "docCount", "chunkCount", "monthTokens")) {
            assertThat((Object) quota.data("$." + field)).as("响应体缺 " + field).isNotNull();
        }
        assertThat(((Number) quota.data("$.kbCount")).longValue())
                .as("《01》示例数据字典：8 个知识库")
                .isEqualTo(8);
        assertThat(((Number) quota.data("$.docCount")).longValue())
                .as("《01》：文档合计 1,246 篇")
                .isEqualTo(1246);
        assertThat(((Number) quota.data("$.chunkCount")).longValue())
                .as("《01》：分片合计 38,502 个")
                .isEqualTo(38502);
        assertThat((Object) quota.data("$.used"))
                .as("used 是带单位的字符串，不是字节数（前端直接渲染）")
                .isInstanceOf(String.class);
        assertThat(((Number) quota.data("$.monthTokens")).longValue())
                .as("无 token 用量表（S6 的 T-905 才有）→ 恒 0，不是编出来的数")
                .isZero();
    }

    // ==================================================================
    // T-411 · 只读解析配置
    // ==================================================================

    @Test
    @DisplayName("T-411：四栏齐全，且随该库的 splitPolicy 变化")
    void parseConfigFollowsKbConfig() {
        String token = loginAsAdmin().data("$.accessToken");
        String code = probeCode();
        try {
            long id = createProbeKb(token, code);

            RawResponse first = get("/api/kb/" + id + "/parse-config", token).assertOk("解析配置");
            for (String tab : List.of("split", "table", "image", "enhance")) {
                assertThat((List<?>) first.data("$." + tab))
                        .as("四栏 " + tab + " 不得为空 —— 上传页按四栏渲染")
                        .isNotEmpty();
            }
            // 值来自该库的策略：新库是 GENERAL / 512 / 64。
            // 标签取 KbPresets 的「通用文档」而不是 SplitPreset 枚举的「通用」——
            // 两者在本仓确实不同字，页面下拉用的是前者（B1 的 KbPresets 逐条对齐过 mock）
            assertThat(parseConfigValue(first, "split", "策略预设")).isEqualTo("通用文档");
            assertThat(parseConfigValue(first, "split", "chunkSize / overlap")).isEqualTo("512 / 64 tokens");
            assertThat(parseConfigValue(first, "split", "第一级 · 结构边界"))
                    .as("结构边界是系统固定口径 —— 前端 @mock 也是这句，且带锁标记")
                    .isEqualTo("标题层级 / 表格整表 / 代码块 / 完整列表");
            // 嵌入模型来自 registry（不是库里的列），且与 /kb/presets 同源
            assertThat(parseConfigValue(first, "split", "嵌入模型"))
                    .as("嵌入模型行必须与 /kb/presets 的 embedding 同口径")
                    .contains("维").contains("L2");

            // 改该库的分片策略后，四栏的对应行必须跟着变
            put("/api/kb/" + id + "/config", wrap(formWith(f -> {
                f.put("code", code);
                f.put("splitPolicy", Map.of("preset", "CONTRACT", "chunkSize", 384, "overlap", 48,
                        "recursiveSplit", true, "semanticSplit", false));
                f.put("tablePolicy", Map.of("atomicTable", true, "dualRepresentation", true,
                        "repeatHeader", true, "mergeCrossPage", false, "ocrTable", false,
                        "rowLimit", 18, "rowWindow", 4));
            }, code)), token).assertOk("改配置");

            RawResponse second = get("/api/kb/" + id + "/parse-config", token).assertOk("再读解析配置");
            assertThat(parseConfigValue(second, "split", "策略预设"))
                    .as("预设必须跟随库配置 —— 写死文案的话上传页永远显示「通用」")
                    .isEqualTo("合同条款");
            assertThat(parseConfigValue(second, "split", "chunkSize / overlap")).isEqualTo("384 / 48 tokens");
            assertThat(parseConfigValue(second, "table", "回表行数上限"))
                    .as("表格策略行取 rowLimit + rowWindow，两个都要跟")
                    .isEqualTo("18 行 · ±4");
            assertThat(parseConfigValue(second, "table", "跨页表格合并"))
                    .as("布尔项按值渲染，不是固定文案")
                    .isEqualTo("关");

            // 四栏只读：没有任何写侧映射。
            // ⚠️ 断言**业务码而不是 HTTP 状态**：本仓口径是"业务错误一律 HTTP 200 + 非 0 code"，
            // 方法不匹配由 GlobalExceptionHandler 转成 200 + 10030 "不支持的请求方法"。
            // 第一版按 HTTP 405 断言，拿到 200 时看起来像"接口能写"，其实是口径不同
            RawResponse writeAttempt = put("/api/kb/" + id + "/parse-config", Map.of(), token);
            assertThat(writeAttempt.status())
                    .as("业务错误是 HTTP 200（只有 401/403/404/500 非 200）")
                    .isEqualTo(200);
            assertThat(writeAttempt.code()).as("没有写侧 → 10030").isEqualTo(10030);
            assertThat(writeAttempt.message()).contains("不支持的请求方法");
        } finally {
            deleteKbByCode(code);
        }
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /** 本批探针库的编码前缀统一为 {@code kb_probe_}（与 B1 同口径，便于残留排查）。 */
    private String probeCode() {
        return "kb_probe_" + Long.toString(System.nanoTime(), 36);
    }

    private String token(String adminToken) {
        return adminToken;
    }

    /** 建一个探针库，返回 id。库名固定，便于用例内断言。 */
    private long createProbeKb(String token, String code) {
        RawResponse created = post("/api/kb", formWith(f -> {
            f.put("code", code);
            f.put("name", "B2 探针库");
        }, code), token).assertOk("建探针库");
        return ((Number) created.data("$.id")).longValue();
    }

    /** 包一层 {@code {config: form}} —— 前端就是这种形状（见 {@code KbDtos.KbConfigRequest}）。 */
    private static Map<String, Object> wrap(Map<String, Object> form) {
        Map<String, Object> body = new HashMap<>();
        body.put("config", form);
        return body;
    }

    /**
     * 以默认表单为底，按需覆盖；{@code code} 用**本次调用新建的探针编码**。
     *
     * <p>保存接口要求提交的 code 与库内一致，所以调用方几乎总是要先建库、再拿那个 code 提交。
     * 这个重载给"本用例会覆盖 code"的场景用（覆盖后要与被改的库一致，调用方自己负责）。
     */
    private Map<String, Object> formWith(java.util.function.Consumer<Map<String, Object>> mutate) {
        return formWith(mutate, probeCode());
    }

    /** 以默认表单为底，按需覆盖；{@code fallbackCode} 为 null 时现场生成一个。 */
    private Map<String, Object> formWith(java.util.function.Consumer<Map<String, Object>> mutate,
                                         String fallbackCode) {        Map<String, Object> form = new HashMap<>();
        form.put("name", "B2 探针库");
        form.put("code", fallbackCode == null ? probeCode() : fallbackCode);
        form.put("deptId", 1);
        form.put("visibleRoles", List.of("SUPER_ADMIN"));
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
        mutate.accept(form);
        return form;
    }

    /** 该库某个 chunkSize/overlap 下的估算分片数。 */
    private long chunkAfterFor(long kbId, int chunkSize, int overlap, String token) {
        RawResponse response = post("/api/kb/" + kbId + "/config/impact",
                wrap(formWith(f -> {
                    // ⚠️ 预估**不改任何东西**，但请求体仍要过 @Valid（与保存同一个 record），
                    // 所以 code 必须满足 ^kb_[a-z0-9_]{2,}$ —— 随手填 "x" 会拿到 10030，
                    // 看起来像"预估接口坏了"，实际是测试把请求拼错了（第一版就踩了）
                    f.put("code", VALID_SHAPE_CODE);
                    f.put("splitPolicy", Map.of("preset", "CUSTOM", "chunkSize", chunkSize,
                            "overlap", overlap, "recursiveSplit", true, "semanticSplit", false));
                }, null)), token).assertOk("预估 kb" + kbId);
        return ((Number) response.data("$.chunkAfter")).longValue();
    }

    /** 四栏里按 {@code label} 取值；取不到直接失败（而不是返 null 让断言变模糊）。 */
    private static String parseConfigValue(RawResponse response, String tab, String label) {
        List<Map<String, Object>> groups = response.data("$." + tab);
        return groups.stream()
                .filter(g -> label.equals(g.get("label")))
                .map(g -> (String) g.get("value"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("四栏 " + tab + " 里没有用 " + label + " 标记的行"));
    }

    /** 该库的授权档位表（角色 code → level）。 */
    private Map<String, String> grantLevels(long kbId) {
        Map<String, String> levels = new LinkedHashMap<>();
        jdbc.queryForList("""
                        select r.code, g.level from sys_kb_grant g
                        join sys_role r on r.id = g.principal_id
                        where g.kb_id = ? and g.principal_type = 'ROLE' order by r.id
                        """, kbId)
                .forEach(r -> levels.put((String) r.get("code"), (String) r.get("level")));
        return levels;
    }

    private boolean reindexRequired(long kbId) {
        Boolean flag = jdbc.queryForObject(
                "select reindex_required from sys_kb where id = ?", Boolean.class, kbId);
        return Boolean.TRUE.equals(flag);
    }

    /** 删掉探针库（先授权后主表）。 */
    private void deleteKbByCode(String code) {
        List<Long> ids = jdbc.queryForList("select id from sys_kb where code = ?", Long.class, code);
        for (Long id : ids) {
            jdbc.update("delete from sys_kb_grant where kb_id = ?", id);
            jdbc.update("delete from sys_kb where id = ?", id);
        }
    }

    private long maxAuditId() {
        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from sys_audit_log", Long.class);
        return max == null ? 0L : max;
    }
}
