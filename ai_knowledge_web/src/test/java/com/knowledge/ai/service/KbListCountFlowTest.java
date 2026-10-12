package com.knowledge.ai.service;

import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `GET /kb` 列表的**文档数 / 分片数必须现算**，且 `sys_kb` 上那两个示例列**已被删掉**
 * （2026-10-12 · `V45`）。
 *
 * <h3>背景：那两个数曾经是假的</h3>
 * <p>`sys_kb.doc_count / chunk_count` 是 `V7__s2_kb_skeleton_and_grants.sql:28` 建表时 seed 的
 * **示例常量**（128 / 42 / 316 … 合计 1246；分片合计 38502），而**全仓没有任何代码写过它们**
 * ⇒ 库下 0 篇文档时列表说「128 篇」，与"点进去 0 篇"自相矛盾（W3 的"双答案"）。
 * 用户 2026-10-12 以「数据和真实情况对不上」把它翻了出来。
 *
 * <p>修法分两步、必须一起看：① 五个消费方改成经 {@link KbCountReader} 从 `sys_document`
 * **现算**；② `V45` 把两列**删掉**（它们既无人读也无人写了，留着只会让人以为有意义）。
 *
 * <h3>⚠️ 本类原先的"负向锚"随删列失效了，换了两个更有牙齿的</h3>
 * <p>改之前这里靠**故意把冗余列写成 999 / 8888** 来证伪"读冗余列"的实现 —— 列删了，
 * 这招自然用不了。取而代之：
 * <ul>
 *   <li>{@link #sampleCounterColumnsAreGone}：**直接查 `information_schema`** 断言那两列不在
 *       —— 谁把列加回来，这条当场红；</li>
 *   <li>{@link #countsArePerKbAndTrackSoftDelete}：造**两个**库并给不同篇数，断言各自的
 *       计数互不串（不按 `kb_id` 分组的实现会红），且**软删的不计入**
 *       （漏 `deleted_at is null` 的实现会红）。</li>
 * </ul>
 */
class KbListCountFlowTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private String token;

    private long kbId;

    /** 第二个探针库 —— 用来验"计数按库分组、互不串"。 */
    private long otherKbId;

    @BeforeEach
    void createProbeKbs() {
        token = loginAsAdmin().data("$.accessToken");
        kbId = insertProbeKb("kb-count-probe", "计数探针库");
        otherKbId = insertProbeKb("kb-count-probe2", "计数探针库二");
    }

    @AfterEach
    void cleanProbeData() {
        for (long id : List.of(kbId, otherKbId)) {
            jdbc.update("delete from sys_chunk where kb_id = ?", id);
            jdbc.update("delete from sys_document where kb_id = ?", id);
            jdbc.update("delete from sys_kb_grant where kb_id = ?", id);
            jdbc.update("delete from sys_kb where id = ?", id);
        }
    }

    @Test
    @DisplayName("V45：`sys_kb` 上不再有 doc_count / chunk_count —— 结构上回不去读示例常量")
    void sampleCounterColumnsAreGone() {
        List<String> leftovers = jdbc.queryForList("""
                select column_name from information_schema.columns
                 where table_schema = database() and table_name = 'sys_kb'
                   and column_name in ('doc_count', 'chunk_count')
                """, String.class);
        assertThat(leftovers)
                .as("这两列已随 V45 删除。加回来就意味着有人得去维护它（上传/删除/软删/恢复/"
                        + "重建/移库六条路径，漏一条就是新的静默漂移）")
                .isEmpty();
    }

    @Test
    @DisplayName("列表的 docCount/chunkCount 来自 sys_document 现算：空库为 0，入库后跟着涨，软删后跟着落")
    void countsArePerKbAndTrackSoftDelete() {
        assertThat(countInList(kbId, "docCount")).as("库下一篇文档都没有 → 0").isZero();
        assertThat(countInList(kbId, "chunkCount")).isZero();

        long ownerId = jdbc.queryForObject("select id from sys_user where username = ?",
                Long.class, ADMIN);
        insertDoc(kbId, ownerId, "探针文档一", "bbbb0000000000000000000000000001", 5);
        insertDoc(kbId, ownerId, "探针文档二", "bbbb0000000000000000000000000002", 3);
        // 另一个库另一份数：计数若不分库（漏 `kb_id` 条件）会把它算进来 ⇒ 下面第一条断言就红
        insertDoc(otherKbId, ownerId, "别的库的文档", "bbbb0000000000000000000000000003", 11);

        assertThat(countInList(kbId, "docCount")).as("本库两篇").isEqualTo(2L);
        assertThat(countInList(kbId, "chunkCount")).as("5 + 3 个分片").isEqualTo(8L);
        assertThat(countInList(otherKbId, "docCount")).as("另一个库不该被算进来").isEqualTo(1L);
        assertThat(countInList(otherKbId, "chunkCount")).isEqualTo(11L);

        // 软删一篇：列表里不该再算它（与 `DocService#page` 同一口径）——
        // 漏 `deleted_at is null` 的实现会得到 2 / 8 而红
        jdbc.update("update sys_document set deleted_at = now() where kb_id = ? and title = ?",
                kbId, "探针文档一");
        assertThat(countInList(kbId, "docCount")).as("软删的不算").isEqualTo(1L);
        assertThat(countInList(kbId, "chunkCount")).isEqualTo(3L);
    }

    // ==================== 工具 ====================

    private long insertProbeKb(String code, String name) {
        // ⚠️ 列清单里**没有** doc_count / chunk_count —— 它们已被 V45 删除
        jdbc.update("""
                        insert into sys_kb (code, name, dept_id, status, secret_level,
                                            business_domain, reindex_required, index_generation,
                                            created_at, updated_at)
                        values (?, ?, 1, 'READY', 'INTERNAL', '', 0, 1, now(), now())
                        """, code, name);
        long id = jdbc.queryForObject("select id from sys_kb where code = ?", Long.class, code);
        // 可见性认的是**授权行**（`DataScopeService#kbVisibility`），不认超管身份
        jdbc.update("""
                        insert into sys_kb_grant (kb_id, principal_type, principal_id, level, created_at, updated_at)
                        select ?, 'USER', id, 'MANAGE', now(), now() from sys_user where username = ?
                        """, id, ADMIN);
        return id;
    }

    /** 从 {@code GET /kb} 的列表里取指定库的那一项，读指定计数。 */
    private long countInList(long targetKbId, String field) {
        RawResponse response = get("/api/kb", token);
        assertThat(response.code()).as("列表要能读到（否则下面的断言是空断言）").isZero();
        List<?> list = response.data("$.list");
        return list.stream()
                .map(item -> (java.util.Map<?, ?>) item)
                .filter(item -> String.valueOf(item.get("id")).equals(String.valueOf(targetKbId)))
                .map(item -> ((Number) item.get(field)).longValue())
                .findFirst()
                .orElseThrow(() -> new AssertionError("探针库 " + targetKbId + " 应出现在列表里，实际：" + list));
    }

    /** 造一篇文档（只填 NOT NULL 的那些列；分片表 `sys_chunk` 与本用例无关）。 */
    private void insertDoc(long targetKbId, long ownerId, String title, String md5, int chunkCount) {
        jdbc.update("""
                        insert into sys_document (kb_id, owner_id, title, file_name, type, size_bytes, md5,
                                                  storage_path, status, chunk_count, table_total, table_parsed,
                                                  image_total, image_parsed, pages_total, pages_with_text,
                                                  index_generation, created_at, updated_at)
                        values (?, ?, ?, ?, 'MD', 1024, ?, 'probe/list-count', 'READY', ?, 0, 0, 0, 0, 1, 1, 1, now(), now())
                        """, targetKbId, ownerId, title, title + ".md", md5, chunkCount);
    }
}
