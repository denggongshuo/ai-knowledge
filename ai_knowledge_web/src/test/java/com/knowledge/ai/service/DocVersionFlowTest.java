package com.knowledge.ai.service;

import com.jayway.jsonpath.JsonPath;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 读侧三条：**判据 4**（版本列表 · `T-606`）· **判据 8**（对比仅元数据 · `T-607`）·
 * **判据 9**（标签如实为空 · `T-609`）。
 *
 * <h2>为什么不与回滚放在同一个类</h2>
 * <p>{@code DocRollbackFlowTest} 要开 {@code m05.vectorize.enabled=true} 并 spy 掉
 * {@code VectorWriter}；本类**一个开关都不碰**（纯读侧）。分开之后，
 * 回滚那条路哪天因为 Milvus / 嵌入中转站不可用而红，**不会连带**把版本列表的判据一起染红
 * —— 一份红里混着两种完全不同的原因，是最难读的一种失败。
 *
 * <h2>夹具刻意造成"当前版本不是最高版本号"</h2>
 * <p>C 组（{@link #abandonedVersionIsNotReportedAsCurrent}）里有一行 {@code v3} 是
 * **回滚失败留下的作废行**（`DocVersionService#abandon` 的产物：`is_current=0` + `FAILED`）。
 * 这正是 {@code list()} 里那句"`current` 取带 `is_current=1` 的那一行、**不是** max(version_no)"
 * 的**唯一真实触发场景** —— 没有它，那段代码是一条永远走不到的分支，
 * 而"永远走不到的分支"与"写错了的分支"在库里长得一模一样。
 */
@DisplayName("判据 4/8/9 · 版本列表 · 版本对比（仅元数据）· 标签如实为空")
class DocVersionFlowTest extends ApiTestSupport {

    private static final String KB_CODE = "probe-ver-list";

    /** 第二个库：**没有任何授权行** —— 用来证明"空数组不是存在性探测器"。 */
    private static final String HIDDEN_KB_CODE = "probe-ver-hidden";

    private long kbId;
    private long hiddenKbId;

    /** A 组：正常的两版（v2 是当前版本）。 */
    private long a1;
    private long a2;

    /** B 组：另一个文档（只有一个版本）—— 用它证明列表**不会串组**。 */
    private long b1;

    /** C 组：v2 是当前版本，v3 是回滚失败留下的作废行。 */
    private long c2;
    private long c3;

    /** 「隐藏库」里的文档。 */
    private long hiddenDocId;

    private String token;

    @BeforeEach
    void versionSetUp() {
        token = loginAsAdmin().data("$.accessToken");

        kbId = insertKb(KB_CODE, true);
        hiddenKbId = insertKb(HIDDEN_KB_CODE, false);

        a1 = insertVersion("手册.pdf", 1, false, 1000L, 3L, 1L, null);
        a2 = insertVersion("手册.pdf", 2, true, 2000L, 5L, 2L, "补录：来自问答运营");
        makeGroup(a1, a1);
        makeGroup(a1, a2);

        b1 = insertVersion("别的.pdf", 1, true, 500L, 7L, 0L, null);
        makeGroup(b1, b1);

        long c1 = insertVersion("方案.pdf", 1, false, 800L, 4L, 0L, null);
        c2 = insertVersion("方案.pdf", 2, true, 900L, 6L, 1L, null);
        c3 = insertVersion("方案.pdf", 3, false, 800L, 0L, 0L, "回滚到 v1");
        makeGroup(c1, c1);
        makeGroup(c1, c2);
        makeGroup(c1, c3);
        // 作废行照 `abandon` 的产物造：不是当前版本 + FAILED
        jdbc.update("update sys_document set status = 'FAILED' where id = ?", c3);

        hiddenDocId = insertDocIn(hiddenKbId, "看不见的.pdf");
    }

    @AfterEach
    void versionTearDown() {
        for (long kb : new long[]{kbId, hiddenKbId}) {
            jdbc.update("delete from sys_chunk where kb_id = ?", kb);
            jdbc.update("delete from sys_document where kb_id = ?", kb);
            jdbc.update("delete from sys_kb_grant where kb_id = ?", kb);
            jdbc.update("delete from sys_kb where id = ?", kb);
        }
        kbId = 0;
        hiddenKbId = 0;
    }

    // ==================================================================
    // 判据 4 · 版本列表
    // ==================================================================

    @Test
    @DisplayName("判据 4：按 version_no 降序 · 标出 current · 标出 hasNewer · 不串组")
    void versionListIsOrderedAndMarked() {
        var res = get("/api/doc/" + a1 + "/versions", token).assertOk("版本列表");

        assertThat(str(res, "$.current"))
                .as("`current` 是**当前生效版本**的版本号，标题栏直接用")
                .isEqualTo("v2");
        assertThat(listOf(res, "$.list"))
                .as("A 组恰好两版 —— B 组的那一行**不许**混进来（串组会让版本列表看起来像文档列表）")
                .hasSize(2);

        assertThat(str(res, "$.list[0].version")).isEqualTo("v2");
        assertThat(bool(res, "$.list[0].current"))
                .as("v2 就是当前版本 ⇒ 前端据它**隐藏「回滚」按钮**（§6.7 / 判据 13）")
                .isTrue();
        assertThat(bool(res, "$.list[0].hasNewer"))
                .as("v2 是最高版本 ⇒ 没有「已有更新版本」标记")
                .isFalse();
        assertThat(str(res, "$.list[0].note"))
                .as("备注取 `remark` 原值（补录时前端带进来的那条问题原文）")
                .isEqualTo("补录：来自问答运营");

        assertThat(str(res, "$.list[1].version")).isEqualTo("v1");
        assertThat(bool(res, "$.list[1].current")).isFalse();
        assertThat(bool(res, "$.list[1].hasNewer"))
                .as("v1 之上还有 v2 ⇒ 前端要打「已有更新版本」标记（需求 §6.4）")
                .isTrue();

        assertThat(nullable(res, "$.list[1].note"))
                .as("🔴 v1 没有备注 ⇒ 必须是 **null** 而不是空串：前者前端不渲染那一格，"
                        + "后者会渲染出一个空白气泡（两者在页面上的差别就是「有备注」与「没有」）")
                .isNull();
    }

    /**
     * ⚠️ 入参是**组内任意一版**的 doc id，不是"当前版本 id"。
     *
     * <p>页面可以从任何一版的链接打开（历史版本同样有详情页），
     * 那一刻若只按 {@code id} 取一行，用户会看到"这个版本组只有一版"——
     * 一个**由打开方式决定的假象**。
     */
    @Test
    @DisplayName("判据 4：从**旧版本**的 id 打开，看到的仍是整组（且与从当前版本打开完全一致）")
    void versionListIsTheSameFromAnyVersionOfTheGroup() {
        var fromOld = get("/api/doc/" + a1 + "/versions", token).assertOk("从 v1 打开");
        var fromCurrent = get("/api/doc/" + a2 + "/versions", token).assertOk("从 v2 打开");

        assertThat(at(fromOld, "$"))
                .as("两次必须**完全一致** —— 差一点点都说明列表的某一项跟着入参变了。"
                        + "⚠️ 比的是 `data` 子树而不是整个响应体：`traceId` 每次请求本来就不同"
                        + "（第一版直接比 `body()`，红在 `traceId` 上 —— 那种红是**断言写错**，"
                        + "不是实现有问题，但看起来一模一样）")
                .isEqualTo(at(fromCurrent, "$"));
    }

    /**
     * 回滚失败会留下一行"版本号更高但不生效"的残骸。
     *
     * <p>🔴 这条判据钉的是 {@code list()} 里那句
     * "`current` 取 {@code is_current=1} 的那一行、**不是** {@code max(version_no)}"：
     * 用 max 的话，这里会把一个**已经作废、0 分片**的 v3 报成当前版本
     * —— 页面标题栏写着 v3，而问答里召回的其实是 v2 的内容。
     */
    @Test
    @DisplayName("判据 4：回滚失败留下的作废行**不冒充**当前版本（current 取 is_current 而不是 max）")
    void abandonedVersionIsNotReportedAsCurrent() {
        var res = get("/api/doc/" + c2 + "/versions", token).assertOk("版本列表（含作废行）");

        assertThat(str(res, "$.current"))
                .as("🔴 当前版本是 v2，不是版本号最大的 v3。"
                        + "把 `current` 改成 max(version_no) ⇒ 本断言当场转红")
                .isEqualTo("v2");

        assertThat(listOf(res, "$.list")).hasSize(3);
        assertThat(str(res, "$.list[0].version")).isEqualTo("v3");
        assertThat(bool(res, "$.list[0].current"))
                .as("作废行**留在列表里如实展示**（`abandon` 只翻 is_current 并标 FAILED，一行都没删"
                        + "—— 需求 §6.4 要的就是可追溯），但它不是当前版本")
                .isFalse();
        assertThat(str(res, "$.list[1].version")).isEqualTo("v2");
        assertThat(bool(res, "$.list[1].current")).isTrue();
        assertThat(bool(res, "$.list[1].hasNewer"))
                .as("⚠️ 这一格在**正常态**下恒 false（当前版本就是最高版本），"
                        + "但作废行存在时它是真的「上面还有一行更高的版本号」——"
                        + "`DocDtos.DocVersionRow#hasNewer` 的注释已按实测改正（原先写着「当前版本恒 false」）")
                .isTrue();
    }

    // ==================================================================
    // 判据 8 · 版本对比（仅元数据）
    // ==================================================================

    @Test
    @DisplayName("判据 8：`against` 留空 = 与**当前版本**比；只给元数据，`textDiff` 恒 null")
    void diffDefaultsToCurrentAndCarriesMetadataOnly() {
        var res = get("/api/doc/" + a1 + "/versions/v1/diff", token).assertOk("版本对比（不带 against）");

        assertThat(str(res, "$.left.version")).isEqualTo("v1");
        assertThat(str(res, "$.right.version"))
                .as("不带 `against` ⇒ 与当前版本（v2）比 —— 页面上的「对比」按钮就是这个语义")
                .isEqualTo("v2");

        // 🔴 元数据必须是**各自那一行**的值，不能两边取同一个
        assertThat(number(res, "$.left.size")).isEqualTo(1000L);
        assertThat(number(res, "$.right.size")).isEqualTo(2000L);
        assertThat(number(res, "$.left.chunkCount")).isEqualTo(3L);
        assertThat(number(res, "$.right.chunkCount")).isEqualTo(5L);
        assertThat(number(res, "$.left.tableTotal")).isEqualTo(1L);
        assertThat(number(res, "$.right.tableTotal")).isEqualTo(2L);

        String realName = jdbc.queryForObject(
                "select coalesce(nullif(real_name, ''), username) from sys_user where id = 1", String.class);
        assertThat(str(res, "$.left.owner")).isEqualTo(realName);
        assertThat(str(res, "$.left.at"))
                .as("时间与版本列表同一格式（`yyyy-MM-dd HH:mm:ss`，前端直接展示不解析）")
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");

        assertThat(nullable(res, "$.textDiff"))
                .as("🔴 **恒 null**：需求 §6.8 逐字写「v1 只做元数据对比 + 文本 diff 接口预留」。"
                        + "⚠️ 它与 `downVotes`/`tags` 那类「未采集」**不是一回事** ——"
                        + "那些是缺陷，这条是**本版不做**（字段留着是为了将来接文本 diff 时契约不变）")
                .isNull();
    }

    @Test
    @DisplayName("判据 8：`against` 显式指定时按它比；与 `version` 相同则拒（不报「两版没差别」）")
    void diffAgainstExplicitVersionAndRejectsSameVersion() {
        var res = get("/api/doc/" + a1 + "/versions/v2/diff?against=v1", token).assertOk("v2 比 v1");

        assertThat(str(res, "$.left.version")).isEqualTo("v2");
        assertThat(str(res, "$.right.version")).isEqualTo("v1");

        var same = get("/api/doc/" + a1 + "/versions/v1/diff?against=v1", token);
        assertThat(same.code())
                .as("🔴 「和自己比」必须拒。放行的话会回一份全等数据，"
                        + "而页面上看起来像在说「这两版没差别」—— 那是一句**关于内容的错误断言**")
                .isEqualTo(10030);
        assertThat(same.message()).contains("同一个版本");
    }

    @Test
    @DisplayName("判据 8：不属于本组的版本号 ⇒ 10030，且**把该组现有版本列出来**")
    void diffRejectsVersionOutsideTheGroup() {
        var res = get("/api/doc/" + a1 + "/versions/v9/diff", token);

        assertThat(res.code()).isEqualTo(10030);
        assertThat(res.message())
                .as("说清「哪一版不属于这篇文档」而不是笼统的参数错 —— "
                        + "并附上该组现有版本，用户能立刻看出自己点错了哪一列")
                .contains("v9").contains("[v2, v1]");
    }

    @Test
    @DisplayName("判据 8：版本号**不做宽容解析** —— `V2` / `0` / `abc` 一律 10030（不静默修正）")
    void versionNumberIsParsedStrictly() {
        for (String bad : new String[]{"V2", "0", "abc", "v"}) {
            var res = get("/api/doc/" + a1 + "/versions/" + bad + "/diff", token);
            assertThat(res.code())
                    .as("「" + bad + "」必须被拒。⚠️ 认了 `V2` / `v2 ` 就等于**静默修正用户的输入**"
                            + "（本项目一条明确的反对口径）；认了 `0` 会去找一个不存在的 v0")
                    .isEqualTo(10030);
        }
    }

    // ==================================================================
    // 判据 9 · 标签
    // ==================================================================

    @Test
    @DisplayName("判据 9：`GET /doc/{id}/tags` 恒回 `[]`，且这是**如实**不是缺陷")
    void tagsIsHonestlyEmpty() {
        var res = get("/api/doc/" + a1 + "/tags", token).assertOk("文档标签");

        Object raw = JsonPath.read(res.body(), "$.data");
        assertThat(raw)
                .as("响应体里必须真的是一个 JSON 数组（而不是 null 或缺字段）—— 前端 `tags` 那一栏是不可枚举的")
                .isInstanceOf(List.class);
        assertThat((List<?>) raw)
                .as("🔴 空数组。文档级标签**至今没有归属表**（计划 §3.4 假设 4）⇒ 没有任何地方能产出标签。"
                        + "⚠️ 这一格**不许**为了「看起来有东西」而编造 —— 那是关于内容的事实断言；"
                        + "它与 `DocMeta.tags` 的现状（也恒为空）是同一件事")
                .isEmpty();
    }

    /**
     * 🔴 空数组**不许**成为"这个 id 存不存在"的探测器。
     *
     * <p>两条错必须**分开报**（`DocAccessService` 的类注释逐字写着这条）：
     * 文档不存在 ⇒ `10044`；文档在、库不可见 ⇒ `10022`（HTTP 403，可以去申请授权）。
     * 后者退化成前者就等于把"你可以去申请授权"说成"这文档没了"。
     */
    @Test
    @DisplayName("判据 9：不可见的文档 ⇒ 10022/403（**不是** 200 的空数组，也不是 10044）")
    void tagsOnInvisibleOrMissingDocIsRejected() {
        var invisible = get("/api/doc/" + hiddenDocId + "/tags", token);
        assertThat(invisible.status())
                .as("文档在、知识库不可见 —— 不能回 200 的空数组："
                        + "否则任何人拿一个别的部门的 doc id 都能问出「这个 id 是存在的」")
                .isEqualTo(403);
        assertThat(invisible.code()).isEqualTo(10022);

        var missing = get("/api/doc/999999999/tags", token);
        assertThat(missing.status())
                .as("⚠️ 与上一条**必须是两个不同的错**：不存在是 10044（HTTP 200），"
                        + "不可见是 10022（HTTP 403）。两者合并就分不清「去申请授权」与「文档没了」")
                .isEqualTo(200);
        assertThat(missing.code()).isEqualTo(10044);

        // 同样两条错在版本列表上也要成立（同一条 requireVisibleDoc，别只测一个端点）
        assertThat(get("/api/doc/" + hiddenDocId + "/versions", token).code()).isEqualTo(10022);
        assertThat(get("/api/doc/999999999/versions", token).code()).isEqualTo(10044);
    }

    // ==================== 夹具 ====================

    private long insertKb(String code, boolean granted) {
        jdbc.update("""
                        insert into sys_kb (code, name, dept_id, status,
                                            secret_level, business_domain, reindex_required,
                                            index_generation, created_at, updated_at)
                        values (?, ?, 1, 'READY', 'INTERNAL', '', 0, 1, now(), now())
                        """, code, code);
        long id = dbLong("select id from sys_kb where code = ?", code);
        if (granted) {
            // 可见性求交认的是**授权行**（`DataScopeService#kbVisibility`），不是超管身份
            jdbc.update("""
                            insert into sys_kb_grant (kb_id, principal_type, principal_id, level, created_at, updated_at)
                            values (?, 'USER', 1, 'MANAGE', now(), now())
                            """, id);
        }
        return id;
    }

    private long insertVersion(String fileName, int versionNo, boolean current, long size,
                               long chunkCount, long tableTotal, String remark) {
        // 标题带版本号：同一个库里"同名文件"的每一版是**独立一行**，标题不重复才查得回来
        String title = fileName + " v" + versionNo;
        String md5 = String.format("%032x", Math.abs((fileName + versionNo).hashCode()));
        jdbc.update("""
                        insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5,
                                                  storage_path, status, chunk_count, table_total, table_parsed,
                                                  image_total, image_parsed, pages_total, pages_with_text,
                                                  index_generation, version_no, is_current, remark,
                                                  created_at, updated_at)
                        values (?, 1, ?, ?, 'PDF', ?, ?, 'documents/probe', 'READY', ?, ?, ?,
                                0, 0, 1, 1,
                                1, ?, ?, ?, now(), now())
                        """, kbId, title, fileName, size, md5, chunkCount, tableTotal, tableTotal,
                versionNo, current ? 1 : 0, remark);
        return dbLong("select id from sys_document where kb_id = ? and title = ?", kbId, title);
    }

    /** 把 {@code member} 挂进以 {@code anchor} 为组标识的那一组（`V39` 的回填口径：组标识 = 首版 id）。 */
    private void makeGroup(long anchor, long member) {
        jdbc.update("update sys_document set doc_group_id = ? where id = ?", anchor, member);
    }

    private long insertDocIn(long kb, String fileName) {
        String md5 = String.format("%032x", Math.abs(fileName.hashCode()));
        jdbc.update("""
                        insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5,
                                                  storage_path, status, chunk_count, table_total, table_parsed,
                                                  image_total, image_parsed, pages_total, pages_with_text,
                                                  index_generation, version_no, is_current, created_at, updated_at)
                        values (?, 1, ?, ?, 'PDF', 100, ?, 'documents/probe', 'READY', 0, 0, 0,
                                0, 0, 0, 0, 1, 1, 1, now(), now())
                        """, kb, fileName, fileName, md5);
        long id = dbLong("select id from sys_document where kb_id = ? and title = ?", kb, fileName);
        makeGroup(id, id);
        return id;
    }

    private long dbLong(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        assertThat(value).as("夹具查询应有结果：" + sql).isNotNull();
        return value;
    }

    // ==================================================================
    // 取值助手
    // ==================================================================

    /*
     * ⚠️ 这四行不是"为了好看"，两道坑都踩过：
     *
     * ① `RawResponse#data` 是 `<T> T data(String)`，而 AssertJ 的 `assertThat` 有一堆
     *    泛型重载（`Predicate<T>` / `IntPredicate` / …）—— 直接写
     *    `assertThat(res.data("$.current"))` 时 javac **推不出** `T`，报的是
     *    「`assertThat` 的引用不明确」，位置指向那一行，看起来像 AssertJ 坏了。
     *    先落到**显式类型**的局部（本方法的返回类型就是那个显式类型）即可解掉。
     *
     * ② 路径**相对 `$.data`**（`data()` 内部拼 `"$.data" + path.substring(1)`），
     *    所以这里收到的形如 `$.list[0].note`，而 `JsonPath.read` 要的是全路径 ——
     *    前缀由本方法统一补。**对着响应体写断言时按 data 相对路径写**，
     *    与 `res.data("$.x")` 的心智模型一致（第一版把 `$.data.list` 传进来，前缀变成了
     *    `$.data.data.list`；另一种写法是直接传 `$.current` 去读根，报
     *    `No results for path: $['current']` —— 两种都只差一个前缀，但报错都不提"前缀"）。
     */

    private static Object at(RawResponse res, String path) {
        return JsonPath.read(res.body(), "$.data" + path.substring(1));
    }

    /** 读一个字符串字段（值必须非 null —— null 走 {@link #nullable}）。 */
    private static String str(RawResponse res, String path) {
        return String.valueOf(at(res, path));
    }

    private static boolean bool(RawResponse res, String path) {
        return Boolean.TRUE.equals(at(res, path));
    }

    /** 读一个**允许为 null** 的字段（`isNull()` 那一类断言用它）。 */
    private static Object nullable(RawResponse res, String path) {
        return at(res, path);
    }

    private static long number(RawResponse res, String path) {
        Object raw = at(res, path);
        return raw == null ? -1L : ((Number) raw).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(RawResponse res, String path) {
        return (List<Object>) at(res, path);
    }
}
