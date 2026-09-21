package com.knowledge.ai.service;

import com.knowledge.ai.pipeline.ChunkingOptions;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-600` / `T-602` / `T-603` / `T-604` / `T-605` —— 文档详情与预览页的只读读侧。
 *
 * <h3>为什么走真 HTTP 而不是直调 service</h3>
 * <p>五条端点的两件最重要的事都发生在 HTTP 层：
 * <ul>
 *   <li><b>权限码</b> —— {@code @RequiresPerm} 的 403 是过滤器给的，直调 service 拿到的是
 *       "方法执行成功了"；</li>
 *   <li><b>两种错要分开</b> —— 文档不存在是 {@code 10044 + HTTP 200}、库不可见是
 *       {@code 10022 + HTTP 403}。这两条只有真发一次请求才区分得出来。</li>
 * </ul>
 *
 * <h3>🔴 判据 2 是本批最要紧的一条：`G7-2` 的合计式<b>要两条</b></h3>
 * <p>只断言「四桶合计 = total」是不够的 —— **把 total 改小就能假绿**（`汇总.md` §5 那条
 * 「A 的合计 = B 先问三句」）。本批那个"装不进桶的一类行"就是**表格子块**，
 * 所以必须补第二条：{@code total + tableSubBlocks == 全表行数}。
 *
 * <h3>⚠️ 判据 8/9 的分母</h3>
 * <p>{@code titlePathCoverage} 与 {@code avgTokens} 的分母**必须与 {@code total} 同源**
 * （顶层块）。探针文档里有一个 {@code title_path = NULL} 的顶层块 ⇒ 覆盖率必然 < 100%，
 * 而 {@code avgTokens} 若把子块也算进分母，断言会当场红（子块短得多，均值会被拉低）。
 */
@DisplayName("T-600/602/603/604/605 · 文档详情/预览页只读读侧")
class DocDetailFlowTest extends ApiTestSupport {

    private static final String KB_CODE = "probe-docdetail";
    private static final String DOC_TITLE = "探针文档-详情";
    private static final String TABLE_CAPTION = "表 2：接口错误码对照表";
    private static final String TABLE_HEADER = "| 错误码 | 含义 | 处理建议 |";
    private static final List<String> TABLE_ROWS = List.of(
            "| 10021 | 无知识库访问权限 | 检查角色与授权 |",
            "| 10022 | 无该知识库访问权限 | 同上 |");

    /** 顶层块的 `raw_content`，按 `order_no`（判据 8/9 要按它手算分母）。 */
    private final List<String> topLevelContents = new ArrayList<>();
    /** 顶层块里 `title_path` 为 NULL 的个数（判据 8 的手算输入）。 */
    private int topLevelWithoutTitlePath;

    private long kbId;
    private long docId;
    private long tableParentId;
    private int childCount;
    private String adminToken;
    private final List<Long> tmpRoleIds = new ArrayList<>();

    @BeforeEach
    void setUpProbe() {
        clearAll();
        buildFixtures();
        adminToken = loginAsAdmin().data("$.accessToken");
    }

    @AfterEach
    void clearFixtures() {
        // `T-804` 起本类会造 `sys_retrieval_trace_chunk` 行 —— 那张表按 `trace_id` 关联，
        // `ProbeCleanup` 的水位线认不出本类造的那几行，故按 trace_id 前缀自清
        jdbc.update("delete from sys_retrieval_trace_chunk where trace_id like 'trace-cite-%'");
        clearAll();
    }

    // ==================================================================
    // 判据 1 / 2 / 2b · `G7-2` 分片统计（**两条等式**）
    // ==================================================================

    @Test
    @DisplayName("判据 1/2/2b：G7-2 四桶合计 == total，且 total + tableSubBlocks == 全表行数")
    void chunkStatSumsAndNothingFallsOnTheFloor() {
        Map<String, Object> stat = stat();

        long total = num(stat, "total");
        long text = num(stat, "text");
        long table = num(stat, "table");
        long summary = num(stat, "summary");
        long image = num(stat, "image");
        long sub = num(stat, "tableSubBlocks");

        // ① 桶内自洽
        assertThat(text + table + summary + image).as("四桶合计必须等于 total").isEqualTo(total);
        // ② 没有任何一行掉在地上 —— 这一条才是"子块既没被塞进某个桶、也没被漏掉"的证明
        assertThat(total + sub).as("total + 子块 == sys_chunk 全表行数").isEqualTo(rowCount());
        // 与 `sys_document.chunk_count` 同源（那里也只计顶层块）⇒ 页面上两处"分片数"不会打架
        assertThat(total).isEqualTo(dbLong("select chunk_count from sys_document where id = ?", docId));

        assertThat(text).as("正文组含 HYPOTHESIS_QA").isEqualTo(5);
        assertThat(table).isEqualTo(1);
        assertThat(image).isEqualTo(1);
        assertThat(summary).isEqualTo(1);
        assertThat(sub).isEqualTo(childCount);
    }

    @Test
    @DisplayName("判据 3：type 五档互斥且完备（并集逐条等于不带 type）")
    void typeBucketsAreMutuallyExclusiveAndComplete() {
        List<Map<String, Object>> all = chunkList(null);
        List<String> union = new ArrayList<>();
        for (String bucket : List.of("text", "table", "image", "summary")) {
            List<Map<String, Object>> part = chunkList(bucket);
            String expect = bucket.equals("text") ? "正文" : bucket;
            assertThat(part).as("「%s」档不该是空的", bucket).isNotEmpty();
            for (Map<String, Object> row : part) {
                assertThat(groupOf((String) row.get("blockType")))
                        .as("「%s」档里混进了 %s", bucket, row.get("blockType"))
                        .isEqualTo(bucket);
            }
            union.addAll(part.stream().map(r -> String.valueOf(r.get("id"))).toList());
            // 五档的 total 之和 == 全量（完备）
            assertThat(num(partOf(bucket), "total")).as("「%s」档的 total", bucket).isEqualTo(part.size());
        }
        assertThat(union).as("四档并集应恰好覆盖全量且无重复").containsExactlyInAnyOrderElementsOf(
                all.stream().map(r -> String.valueOf(r.get("id"))).toList());
        // 子块不属于任何展示分组：全量列表里一个子块都不该出现
        assertThat(all).as("全量里不该出现子块").allSatisfy(
                r -> assertThat(r.get("parentId")).isNull());
        assertThat(all).hasSize((int) num(stat(), "total"));
    }

    @Test
    @DisplayName("判据 3b：type 闭集之外的取值 → 10030（**不静默当成 all**）")
    void unknownTypeIsRejected() {
        var res = get("/api/doc/" + docId + "/chunks?type=bogus", adminToken);

        assertThat(res.code()).as("闭集之外的取值必须显式报错").isEqualTo(10030);
        assertThat(res.message()).contains("text").contains("summary");
    }

    // ==================================================================
    // 判据 4 / 5 / 6 / 7 · `G7-1` 父块与全部子块
    // ==================================================================

    @Test
    @DisplayName("判据 4：G7-1 父块完整内容 + **全部**子块，且逐条与 DB 对得上")
    void parentChunkReturnsAllChildren() {
        Map<String, Object> data = parentChunk(tableParentId);
        List<Map<String, Object>> children = listOf(data.get("children"));

        assertThat(num(data, "total")).isEqualTo(childCount).isEqualTo(children.size());
        // "全部"是可断言的：与 DB 直查逐条相等
        assertThat(children).hasSize((int) dbLong(
                "select count(*) from sys_chunk where parent_id = ?", tableParentId));
        for (Map<String, Object> child : children) {
            long id = num(child, "id");
            assertThat((String) child.get("content"))
                    .as("子块 %s 的内容必须与库里逐字相等", id)
                    .isEqualTo(dbString("select raw_content from sys_chunk where id = ?", id));
        }

        Map<String, Object> parent = mapOf(data.get("parent"));
        assertThat((String) parent.get("content")).as("父块完整内容（含表标题）")
                .isEqualTo(dbString("select raw_content from sys_chunk where id = ?", tableParentId))
                .contains(TABLE_CAPTION);
    }

    @Test
    @DisplayName("判据 5：rows/cols/returnedRows —— 数据行不含表头，本页返回整表")
    void parentChunkShape() {
        Map<String, Object> parent = mapOf(parentChunk(tableParentId).get("parent"));

        assertThat(num(parent, "rows")).as("数据行（不含表头）").isEqualTo(TABLE_ROWS.size());
        assertThat(num(parent, "cols")).isEqualTo(3);
        // 需求 §4.5 对这一页的措辞是「N 行 × M 列全部返回」——"命中行 ± 窗口"只属于检索回表
        assertThat(num(parent, "returnedRows")).isEqualTo(num(parent, "rows"));
        assertThat((String) parent.get("title")).as("表标题取自 markdown 的 **…** 前导行")
                .isEqualTo(TABLE_CAPTION);
        assertThat((String) parent.get("titlePath")).as("所属章节走另一个字段，不混进 title")
                .isEqualTo("1. 概述");
    }

    @Test
    @DisplayName("判据 6：不存在的 parentId / 非表格块 / 子块 id → 各自的明确错")
    void badParentIdsAreRejected() {
        assertThat(get("/api/doc/" + docId + "/chunks/99999999", adminToken).code())
                .as("不存在的 parentId").isEqualTo(10040);
        assertThat(get("/api/doc/" + docId + "/chunks/" + firstChildId(), adminToken).message())
                .as("传子块 id 时要指路").contains("子块");
        long paragraph = dbLong("select id from sys_chunk where doc_id = ? and chunk_type = 'PARAGRAPH' "
                + "order by order_no limit 1", docId);
        assertThat(get("/api/doc/" + docId + "/chunks/" + paragraph, adminToken).message())
                .as("传非表格块时要说明本接口只服务父子关系").contains("不是表格块");
    }

    @Test
    @DisplayName("判据 7：parentId = 0 被拒 —— 「禁用 0 当哨兵」在接口层同样成立")
    void zeroParentIdIsRejected() {
        var res = get("/api/doc/" + docId + "/chunks/0", adminToken);

        assertThat(res.code()).isEqualTo(10030);
        assertThat(res.message()).contains("NULL").contains("不是 0");
    }

    // ==================================================================
    // 判据 8 / 9 / 10 · 分母与口径
    // ==================================================================

    @Test
    @DisplayName("判据 8：titlePathCoverage 的分母 = 顶层块（子块继承父块路径，算进去会虚高）")
    void titlePathCoverageUsesTopLevelDenominator() {
        Map<String, Object> stat = stat();

        long total = num(stat, "total");
        double expected = Math.round((total - topLevelWithoutTitlePath) * 1000.0 / total) / 10.0;

        assertThat(stat.get("titlePathCoverage")).as("覆盖率必须 < 100（探针里有一块没有标题路径）")
                .isNotNull();
        assertThat(((Number) stat.get("titlePathCoverage")).doubleValue())
                .as("分母 = 顶层块（手算 %s/%s）", total - topLevelWithoutTitlePath, total)
                .isEqualTo(expected);
        assertThat(expected).as("探针必须真的造成「低于 100%」这一情形，否则这条判据是恒绿的")
                .isLessThan(100.0);
    }

    @Test
    @DisplayName("判据 9：avgTokens 的分母与 total 同源；**空文档时为 null 而不是 0**")
    void avgTokensUsesTopLevelDenominator() {
        double actual = ((Number) stat().get("avgTokens")).doubleValue();

        long sumTop = topLevelContents.stream().mapToLong(DocDetailFlowTest::tokens).sum();
        double expectedTop = Math.round(sumTop * 10.0 / topLevelContents.size()) / 10.0;

        assertThat(actual).as("分母必须是顶层块").isEqualTo(expectedTop);
        // 若误把子块也算进分母，均值会明显不同 —— 这条是上面那句的**证伪侧**
        assertThat(topLevelContents.size()).as("探针必须有子块，否则两种分母一样大、这条判据没意义")
                .isNotEqualTo((int) rowCount());

        // 空文档：0/0 的均值是"判不了"，不是 0
        long emptyDoc = insertDocRow("探针文档-空");
        Map<String, Object> emptyStat = mapOf(get("/api/doc/" + emptyDoc + "/chunk-stat", adminToken).data("$"));
        assertThat(num(emptyStat, "total")).isZero();
        assertThat(emptyStat.get("avgTokens")).as("没有分片时它必须是 null").isNull();
    }

    @Test
    @DisplayName("判据 10：tokens 与切分窗口同一口径（ChunkingOptions#estimateTokens）")
    void chunkTokensUseTheChunkingMeasure() {
        List<Map<String, Object>> rows = chunkList(null);

        for (Map<String, Object> row : rows) {
            assertThat(num(row, "tokens"))
                    .as("分片 %s 的 tokens", row.get("id"))
                    .isEqualTo(tokens((String) row.get("content")));
        }
    }

    // ==================================================================
    // 判据 11 / 12 / 13 · 目录树 / 元信息 / 四个恒 null
    // ==================================================================

    @Test
    @DisplayName("判据 11：toc 条数 = HEADING 块数；level = title_path 分段数；id = 分片 id 字符串")
    void tocComesFromHeadingChunks() {
        List<Map<String, Object>> list = listOf(get("/api/doc/" + docId + "/toc", adminToken).data("$.list"));

        assertThat(list).hasSize((int) dbLong(
                "select count(*) from sys_chunk where doc_id = ? and chunk_type = 'HEADING'", docId));
        assertThat(list.get(0).get("id")).as("id 是分片 id 的字符串形式（真事实，不是层级引用串）")
                .isEqualTo(String.valueOf(dbLong(
                        "select id from sys_chunk where doc_id = ? and chunk_type = 'HEADING' "
                                + "order by order_no limit 1", docId)));
        assertThat(list.get(0)).containsEntry("label", "1. 概述").containsEntry("level", 1);
        assertThat(list.get(1)).as("二级标题的 title_path 是「1. 概述 > 1.1 细节」⇒ level 2")
                .containsEntry("level", 2);
    }

    @Test
    @DisplayName("判据 12：DocMeta 逐字段与三张表对账")
    void metaMatchesTheDatabase() {
        Map<String, Object> meta = mapOf(get("/api/doc/" + docId, adminToken).data("$"));

        assertThat(num(meta, "id")).isEqualTo(docId);
        assertThat(num(meta, "kbId")).isEqualTo(kbId);
        assertThat((String) meta.get("kbName")).isEqualTo(KB_CODE);
        assertThat((String) meta.get("title")).isEqualTo(DOC_TITLE);
        assertThat((String) meta.get("path")).isEqualTo(DOC_TITLE + ".pdf");
        assertThat((String) meta.get("md5")).isEqualTo(dbString("select md5 from sys_document where id = ?", docId));
        assertThat((String) meta.get("storagePath")).isEqualTo("documents/probe");
        assertThat((String) meta.get("owner")).as("上传人取 owner_id 对应的真名")
                .isEqualTo(dbString("select u.real_name from sys_user u join sys_document d on d.owner_id = u.id "
                        + "where d.id = ?", docId));
        assertThat((String) meta.get("department")).as("部门来自知识库").isEqualTo(
                dbString("select d.name from sys_department d join sys_kb k on k.dept_id = d.id where k.id = ?", kbId));
        assertThat(num(meta, "pageCount")).isEqualTo(5);
        assertThat(num(meta, "chunkCount")).isEqualTo(num(stat(), "total"));
        assertThat((String) meta.get("status")).isEqualTo("READY");
        assertThat((String) meta.get("fileDesc")).as("由 type + pages_with_text 派生").isEqualTo("PDF（原生文本，非扫描件）");
        // 密级：本批没有文档级密级列（I-6 的落地属 S7-M06-B）⇒ 当前生效密级 == 库级
        assertThat((String) meta.get("securityLevel")).isEqualTo("INTERNAL");
        assertThat((String) meta.get("kbSecurityLevel")).isEqualTo("INTERNAL");
        assertThat((String) meta.get("lastIndexAt")).as("最近索引时刻取自解析任务的完成时刻").isNotNull();
    }

    @Test
    @DisplayName("判据 13（S7-M06-C 改写）：citations 转真；downVotes/version/tags **仍严格 null**")
    void uncollectedFieldsAreNullNotZero() {
        Map<String, Object> meta = mapOf(get("/api/doc/" + docId, adminToken).data("$"));

        // 🔴 `S7-M06-A` 时这四个字段**一起**为 null，根因都是"没有采集方"。
        //    `S7-M06-C` 的 `T-804` 让 `cited` 由答案角标解析而来 ⇒ `citations` 转真，
        //    而**另外三个仍然没有采集方**。这条判据按新契约改写 —— 但**不是**把断言放宽：
        //    它现在钉的是"**只有一个字段翻了**"，防止下一个人顺手把四个一起填 0。
        // ⚠️ 取出来是 `Integer`（JSON 反序列化的默认数字类型），所以比 `longValue()`
        // 而不是直接比 `0L` —— 后者会因为 `0L != 0`（装箱类型不同）而红，
        // 而那条红说的是"测试写法不对"，不是"契约不对"
        assertThat(((Number) meta.get("citations")).longValue())
                .as("本探针文档没被打过点引用过 ⇒ 真值是 **0**（不是 null）。"
                        + "⚠️ 这里的 0 与 null 分别是「确实没被引用」与「还没采集」—— "
                        + "T-804 之后本字段有采集方了，所以 0 是诚实的那一个")
                .isZero();
        // `0`/`''`/`[]`/`null` 全是 falsy —— falsy 断言会在"值错了"时照样绿（§5「缺席 ≡ 0」）
        assertThat(meta).containsEntry("downVotes", null);
        assertThat(meta).containsEntry("version", null);
        assertThat(meta).containsEntry("tags", null);

        // 索引任务的 version 同一根因（不是"这份任务没有版本"）
        List<Map<String, Object>> tasks = listOf(get("/api/doc/" + docId + "/index-tasks", adminToken).data("$.list"));
        assertThat(tasks).isNotEmpty();
        assertThat(taskOf(tasks, "DONE")).containsEntry("version", null);
    }

    @Test
    @DisplayName("T-804：citations 只数 `cited = 1` 且属于**本篇**的行（null 与 0 都不算）")
    void citationsCountOnlyReallyCitedRows() {
        long chunkId = dbLong("select id from sys_chunk where doc_id = " + docId + " limit 1");

        // A：本篇的 2 条被引用
        insertTraceChunk("trace-cite-a1", chunkId, 1, 1);
        insertTraceChunk("trace-cite-a2", chunkId, 2, 1);
        // B：**另一篇文档**的行 —— 必须不计数（否则这条判据是空的）
        insertTraceChunkForDoc("trace-cite-b1", chunkId, 1, 1, docId + 999_999L);
        // C：本篇但 `cited` 为 **null**（本批之前的历史行）—— 不算，那是"没采集"
        insertTraceChunkForDoc("trace-cite-c1", chunkId, 1, null, docId);
        // D：本篇但 `cited = 0`（明确没被引用）—— 也不算
        insertTraceChunkForDoc("trace-cite-d1", chunkId, 2, 0, docId);

        Map<String, Object> meta = mapOf(get("/api/doc/" + docId, adminToken).data("$"));

        assertThat(((Number) meta.get("citations")).longValue())
                .as("🔴 三种错法都过不了这条：取 `count(*) where doc_id = ?` 得 4；"
                        + "取 `cited is not null` 得 3；取 `cited <> 0` 得 3。"
                        + "只有「`cited = 1` **且** `doc_id` 是本篇」得 2")
                .isEqualTo(2L);
    }

    /** 插一条打点明细（`cited` 三态都要能造：1 / 0 / null）。 */
    private void insertTraceChunk(String traceId, long chunkId, int rankNo, Integer cited) {
        insertTraceChunkForDoc(traceId, chunkId, rankNo, cited, docId);
    }

    private void insertTraceChunkForDoc(String traceId, long chunkId, int rankNo, Integer cited, long targetDocId) {
        jdbc.update("insert into sys_retrieval_trace_chunk "
                        + "(trace_id, rank_no, chunk_id, doc_id, score, source, cited) "
                        + "values (?, ?, ?, ?, 0.5, 'vector', ?)",
                traceId, rankNo, chunkId, targetDocId, cited);
    }

    // ==================================================================
    // 判据 14 / 15 / 16 · 越权边界 / 权限码 / 只读语义
    // ==================================================================

    @Test
    @DisplayName("判据 14：库不可见 → 403 + 10022；文档不存在 → 200 + 10044（两种错必须不同）")
    void visibilityAndExistenceGiveDifferentErrors() {
        // ⚠️ 这个探针账号**必须自己有 doc:detail 权限**，否则先被权限闸拦成 10020 ——
        // 那样测的是权限、不是可见性，两个数看着都是"403"，混起来这条判据就形同虚设。
        // 🔴 本用例第一版正是这么红的（期望 10022、实测 10020），而它顺带证明了
        // `@RequiresPerm` 在 `requireVisibleDoc` **之前**执行 —— 这是有意的顺序。
        long roleId = createTempRoleWith("pdd.nogrant", "doc:detail", "doc:preview");
        var plain = createUser("dd.plain");
        long uid = dbLong("select id from sys_user where username = ?", plain.username());
        jdbc.update("delete from sys_user_role where user_id = ?", uid);
        jdbc.update("insert into sys_user_role (user_id, role_id, created_at) values (?, ?, now())", uid, roleId);
        // **刻意不给 sys_kb_grant** ⇒ 权限够、库不可见
        String plainToken = login(plain.username(), plain.password()).data("$.accessToken");

        var invisible = get("/api/doc/" + docId, plainToken);
        assertThat(invisible.status()).as("库不可见是 HTTP 403").isEqualTo(403);
        assertThat(invisible.code()).as("复用既有约定 KB_ACCESS_DENIED（不是 10020、更不是 10044）")
                .isEqualTo(10022);
        assertThat(invisible.body()).as("不得泄露任何文档字段").doesNotContain(DOC_TITLE);

        var missing = get("/api/doc/99999999", adminToken);
        assertThat(missing.status()).as("文档不存在仍是 HTTP 200（业务错不带 HTTP 语义）").isEqualTo(200);
        assertThat(missing.code()).as("不许把它也报成 10022 —— 那是\"你可以去申请授权\"的另一件事")
                .isEqualTo(10044);
    }

    @Test
    @DisplayName("判据 15：权限码分别守位（同一 token 打六条，逐条对号）")
    void permissionCodesGuardTheRightEndpoints() {
        // ① 无 token → 401
        assertThat(get("/api/doc/" + docId).code()).as("未登录").isNotZero();

        // ② 只给 doc:preview 的角色：预览四条通、详情两条 403
        long roleId = createTempRoleWith("probe.detail.only", "doc:preview");
        var probe = createUser("dd.perm");
        long uid = dbLong("select id from sys_user where username = ?", probe.username());
        jdbc.update("delete from sys_user_role where user_id = ?", uid);
        jdbc.update("insert into sys_user_role (user_id, role_id, created_at) values (?, ?, now())", uid, roleId);
        // ⚠️ 还必须把库授权给这个人：否则拿到的是 **10022（库不可见）**，
        // 而这条判据要考的是**权限码**（10020）—— 两个数看着都是 403，混起来这条判据就形同虚设
        jdbc.update("""
                        insert into sys_kb_grant (kb_id, principal_type, principal_id, level, created_at, updated_at)
                        values (?, 'USER', ?, 'READ', now(), now())
                        """, kbId, uid);
        String previewOnly = login(probe.username(), probe.password()).data("$.accessToken");

        assertThat(get("/api/doc/" + docId + "/toc", previewOnly).code()).as("doc:preview 掌控目录树").isEqualTo(0);
        assertThat(get("/api/doc/" + docId + "/chunks", previewOnly).code()).isZero();
        assertThat(get("/api/doc/" + docId + "/chunk-stat", previewOnly).code()).isZero();
        assertThat(get("/api/doc/" + docId + "/chunks/" + tableParentId, previewOnly).code()).isZero();
        assertThat(get("/api/doc/" + docId, previewOnly).code()).as("doc:detail 才有元信息").isEqualTo(10020);
        assertThat(get("/api/doc/" + docId + "/index-tasks", previewOnly).code()).isEqualTo(10020);

        // ③ 超管六条全通
        for (String path : List.of("", "/toc", "/chunks", "/chunk-stat", "/index-tasks")) {
            assertThat(get("/api/doc/" + docId + path, adminToken).code()).as("超管应通过 %s", path).isZero();
        }
    }

    @Test
    @DisplayName("判据 16：五条端点跑完，文档行与分片行**逐位不变**（只读语义可断言）")
    void readPathDoesNotWriteAnything() {
        String before = dbString("select concat(updated_at, '|', status, '|', chunk_count) "
                + "from sys_document where id = ?", docId);
        long chunksBefore = rowCount();

        for (String path : List.of("", "/toc", "/chunks", "/chunk-stat", "/index-tasks")) {
            get("/api/doc/" + docId + path, adminToken).assertOk(path);
        }
        get("/api/doc/" + docId + "/chunks/" + tableParentId, adminToken).assertOk("parentChunk");

        assertThat(dbString("select concat(updated_at, '|', status, '|', chunk_count) "
                + "from sys_document where id = ?", docId)).as("只读路径不得改动文档行").isEqualTo(before);
        assertThat(rowCount()).as("只读路径不得增删分片").isEqualTo(chunksBefore);
    }

    @Test
    @DisplayName("判据 17：index-tasks 的耗时由 finished_at - locked_at 派生，chunk_total=0 下发为 null")
    void indexTaskDurationAndUnknownChunkCount() {
        List<Map<String, Object>> list = listOf(get("/api/doc/" + docId + "/index-tasks", adminToken).data("$.list"));

        assertThat(list).as("两条探针任务都要在").hasSize(2);
        // 顺序是"新 → 旧"：这一条与上面那条"两条 created_at 不同"的夹具注释是一对
        assertThat(list.get(0)).as("最新的一条在最前").containsEntry("status", "RUNNING");

        Map<String, Object> done = taskOf(list, "DONE");
        assertThat(num(done, "durationSec")).as("探针任务跑了 74 秒").isEqualTo(74);
        assertThat(num(done, "chunkCount")).isEqualTo(8);

        // 在跑的那条 chunk_total 留 0 = 「未知」（V17 的列注释）⇒ 必须下发 null，
        // 否则页面上会出现一句"0 个分片"，而那是关于结果的断言
        Map<String, Object> running = taskOf(list, "RUNNING");
        assertThat(running.get("chunkCount")).as("chunk_total = 0 是未知，不是零").isNull();
        assertThat(running.get("durationSec")).as("未完成任务没有耗时").isNull();
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /** 建探针库（只授权给 admin）+ 一篇含**全部块类型**的文档 + 两条解析任务。 */
    private void buildFixtures() {
        jdbc.update("""
                        insert into sys_kb (code, name, dept_id, doc_count, chunk_count, status,
                                            secret_level, business_domain, reindex_required,
                                            index_generation, created_at, updated_at)
                        values (?, ?, 1, 1, 0, 'READY', 'INTERNAL', '', 0, 1, now(), now())
                        """, KB_CODE, KB_CODE);
        kbId = dbLong("select id from sys_kb where code = ?", KB_CODE);
        // 可见性求交认的是**授权行**（DataScopeService#kbVisibility），不是超管身份
        jdbc.update("""
                        insert into sys_kb_grant (kb_id, principal_type, principal_id, level, created_at, updated_at)
                        values (?, 'USER', 1, 'MANAGE', now(), now())
                        """, kbId);

        docId = insertDocRow(DOC_TITLE);

        // ---- 顶层块 ----
        addChunk("HEADING", "1. 概述", "1. 概述", true);
        addChunk("PARAGRAPH", "这是一段正文。", "1. 概述", true);
        addChunk("HEADING", "1.1 细节", "1. 概述 > 1.1 细节", true);
        // 刻意留一个没有标题路径的顶层块 ⇒ 覆盖率必然 < 100%（判据 8 的手算输入）
        addChunk("PARAGRAPH", "封面段，标题之前。", null, true);
        // 表格父块：markdown 形状与 ParsedTable#toMarkdown() 逐字一致（表标题 + 表头 + 分隔行 + 数据行）
        tableParentId = addChunk("TABLE", tableMarkdown(), "1. 概述", true);
        addChunk("IMAGE", "图 1：整体链路", "1. 概述", true);
        addChunk("SUMMARY", "本文档概述了接口错误码。", null, true);
        addChunk("HYPOTHESIS_QA", "错误码 10021 是什么？", "1. 概述", true);
        // ---- 表格子块 ----
        childCount = 0;
        for (String row : TABLE_ROWS) {
            addChunk("TABLE", row, "1. 概述", false);
            childCount++;
        }

        jdbc.update("update sys_kb set chunk_count = ? where id = ?", rowCount(), kbId);
        jdbc.update("update sys_document set chunk_count = ?, table_total = 1, table_parsed = 1, "
                        + "image_total = 1, image_parsed = 1, pages_total = 5, pages_with_text = 4 where id = ?",
                topLevelContents.size(), docId);

        // ---- 两条解析任务：一条已完成（74 秒）、一条在跑（chunk_total = 0 = 未知）----
        // ⚠️ 刻意给**不同的** created_at：两条都用 now() 时先后是不确定的，
        // 而"新→旧"是这条端点的一个真实口径 ⇒ 用索引取第 0 条会变成一条会飘的判据
        insertTask("probe-detail-1", "DONE", 8, 3600, 3500, 74);
        insertTask("probe-detail-2", "RUNNING", 0, 60, 30, null);
    }

    /** 真实渲染器的形状：{@code **表标题**} + 表头 + 分隔行 + 数据行（每行尾带换行）。 */
    private static String tableMarkdown() {
        return "**" + TABLE_CAPTION + "**\n" + TABLE_HEADER + "\n|---|---|---|\n"
                + String.join("\n", TABLE_ROWS) + "\n";
    }

    private long addChunk(String type, String content, String titlePath, boolean topLevel) {
        jdbc.update("""
                        insert into sys_chunk (doc_id, kb_id, chunk_type, parent_id, title_path,
                                               raw_content, content_sha256, order_no, page_no, created_at)
                        values (?, ?, ?, ?, ?, ?, ?, ?, 1, now())
                        """, docId, kbId, type, topLevel ? null : tableParentId, titlePath, content,
                com.knowledge.ai.core.text.ContentFingerprint.of(content), nextOrderNo());
        long id = dbLong("select id from sys_chunk where doc_id = ? order by order_no desc limit 1", docId);
        if (topLevel) {
            topLevelContents.add(content);
            if (titlePath == null) {
                topLevelWithoutTitlePath++;
            }
        }
        return id;
    }

    private int nextOrderNo() {
        return (int) dbLong("select coalesce(max(order_no), 0) + 1 from sys_chunk where doc_id = ?", docId);
    }

    private long insertDocRow(String title) {
        String md5 = String.format("%032x", Math.abs(title.hashCode()));
        jdbc.update("""
                        insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5,
                                                  storage_path, status, chunk_count, table_total, table_parsed,
                                                  image_total, image_parsed, pages_total, pages_with_text,
                                                  index_generation, created_at, updated_at)
                        values (?, 1, ?, ?, 'PDF', 1024, ?, 'documents/probe', 'READY', 0, 0, 0, 0, 0, 0, 0, 1, now(), now())
                        """, kbId, title, title + ".pdf", md5);
        return dbLong("select id from sys_document where kb_id = ? and title = ?", kbId, title);
    }

    /**
     * @param createdAgoSec 该任务在多少秒之前被提交（**只用它定先后**，见夹具里的注释）
     * @param lockedAgoSec  多少秒之前被 worker 领取 —— {@code durationSec} 的起点
     * @param durationSec   {@code null} = 还没结束（{@code finished_at} 写 null）
     */
    private void insertTask(String taskId, String status, int chunkTotal,
                            int createdAgoSec, int lockedAgoSec, Integer durationSec) {
        String finished = durationSec == null
                ? "null"
                : "date_add(now(), interval -" + (lockedAgoSec - durationSec) + " second)";
        jdbc.update("""
                        insert into sys_async_task (task_id, type, status, kb_id, doc_id, owner_id,
                                                    stage, percent, chunk_done, chunk_total,
                                                    created_at, updated_at, locked_at, finished_at)
                        values (?, 'doc-parse', ?, ?, ?, 1, '解析中', 100, ?, ?,
                                date_add(now(), interval -%d second), now(),
                                date_add(now(), interval -%d second), %s)
                        """.formatted(createdAgoSec, lockedAgoSec, finished),
                taskId, status, kbId, docId, chunkTotal, chunkTotal);
    }

    /** 按状态取任务行 —— **不按索引**（顺序本身另有一条判据盯着）。 */
    private static Map<String, Object> taskOf(List<Map<String, Object>> list, String status) {
        return list.stream().filter(t -> status.equals(t.get("status"))).findFirst()
                .orElseThrow(() -> new AssertionError("响应里没有 status = " + status + " 的任务：" + list));
    }

    /**
     * 建一个只带指定权限码的临时角色。
     *
     * <p>⚠️ {@code code} 必须**短**：{@code sys_role.code} 是 {@code VARCHAR(32)}，
     * 第一版写 {@code prefix + "." + System.nanoTime()}（19 位）当场撞
     * {@code Data too long} —— 而现象是"用例报数据截断"，与"角色码太长了"看不出关系。
     */
    private long createTempRoleWith(String code, String... permCodes) {
        String roleCode = code + "." + (System.nanoTime() % 1_000_000);
        jdbc.update("insert into sys_role (code, name, description, data_scope, enabled, builtin, sort, "
                        + "created_at, updated_at) values (?, ?, '探针角色', 'ALL', 1, 0, 99, now(), now())",
                roleCode, roleCode);
        long roleId = dbLong("select id from sys_role where code = ?", roleCode);
        tmpRoleIds.add(roleId);
        for (String perm : permCodes) {
            jdbc.update("insert into sys_role_permission (role_id, permission_id, created_at) "
                    + "select ?, id, now() from sys_permission where code = ?", roleId, perm);
        }
        return roleId;
    }

    private void clearAll() {
        jdbc.update("delete from sys_chunk where kb_id = ?", kbIdOrZero());
        jdbc.update("delete from sys_async_task where kb_id = ?", kbIdOrZero());
        jdbc.update("delete from sys_document where kb_id = ?", kbIdOrZero());
        jdbc.update("delete from sys_kb_grant where kb_id = ?", kbIdOrZero());
        jdbc.update("delete from sys_kb where code = ?", KB_CODE);
        // ⚠️ 必须按外键序清：`sys_user_role.role_id` 有指向 `sys_role.id` 的外键，
        // 先删角色会报 `Cannot delete or update a parent row` —— 而现象是
        //「用例全过、收尾报错」，很容易被当成"收尾的小毛病"放过去（第一版正是如此）
        for (long roleId : tmpRoleIds) {
            jdbc.update("delete from sys_user_role where role_id = ?", roleId);
            jdbc.update("delete from sys_role_permission where role_id = ?", roleId);
            jdbc.update("delete from sys_role where id = ?", roleId);
        }
        tmpRoleIds.clear();
        kbId = 0;
        topLevelContents.clear();
        topLevelWithoutTitlePath = 0;
    }

    private long kbIdOrZero() {
        return kbId > 0 ? kbId : -1;
    }

    // ==================================================================
    // 读响应的便捷封装
    // ==================================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> stat() {
        return mapOf(get("/api/doc/" + docId + "/chunk-stat", adminToken).assertOk("chunk-stat").data("$"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> chunkList(String type) {
        String path = "/api/doc/" + docId + "/chunks" + (type == null ? "" : "?type=" + type);
        return (List<Map<String, Object>>) get(path, adminToken).assertOk("chunks").data("$.list");
    }

    private Map<String, Object> partOf(String type) {
        return mapOf(get("/api/doc/" + docId + "/chunks?type=" + type, adminToken).data("$"));
    }

    private Map<String, Object> parentChunk(long parentId) {
        return mapOf(get("/api/doc/" + docId + "/chunks/" + parentId, adminToken)
                .assertOk("chunks/{parentId}").data("$"));
    }

    private long firstChildId() {
        return dbLong("select id from sys_chunk where parent_id = ? order by order_no limit 1", tableParentId);
    }

    private long rowCount() {
        return dbLong("select count(*) from sys_chunk where doc_id = ?", docId);
    }

    private static int tokens(String content) {
        return (int) Math.round(ChunkingOptions.estimateTokens(content));
    }

    /** 展示分组（与后端 {@code DocDetailService.TEXT_GROUP} 的口径一致）。 */
    private static String groupOf(String blockType) {
        return switch (blockType) {
            case "TABLE" -> "table";
            case "IMAGE" -> "image";
            case "SUMMARY" -> "summary";
            default -> "text";
        };
    }

    private long dbLong(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0L : v;
    }

    private String dbString(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        assertThat(value).as("响应里必须有这个对象").isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object value) {
        assertThat(value).as("响应里必须有这个数组").isInstanceOf(List.class);
        return (List<Map<String, Object>>) value;
    }

    private static long num(Map<String, Object> map, String key) {
        Object v = map.get(key);
        assertThat(v).as("响应里必须有字段 %s", key).isNotNull();
        return ((Number) v).longValue();
    }
}
