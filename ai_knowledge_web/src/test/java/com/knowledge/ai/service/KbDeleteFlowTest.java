package com.knowledge.ai.service;

import com.knowledge.ai.core.constant.MilvusConstants;
import com.knowledge.ai.core.storage.MilvusPartitionManager;
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
 * `T-407` 删除知识库 / 清空索引的取证（判据 5 / 6 / 10 / 11 / 12）。
 *
 * <h3>为什么这个类走**真 HTTP**，而 {@code ReindexFlowTest} 直调 Service</h3>
 * <p>判据 5 与 6 考的是**审计留痕**。本仓的硬口径：审计的 {@code operator} / {@code apiPath}
 * 取自**请求上下文**，直调 Service 会测成空值（见 `sprint-delivery` 的取证姿势表）。
 * 所以凡是与审计有关的断言，必须让请求真的走一遍过滤器链。
 * 而重建/续跑那几条考的是**代与断点**，没有请求上下文参与，直调更快也更稳。
 *
 * <h3>为什么探针库直接建行、不走上传</h3>
 * <p>同上：本类考的是"删了之后会怎样"，不是"库是怎么建出来的"。建库那条路
 * （`KbModuleFlowTest` / `KbWriteFlowTest`）已经有 20 多条用例在管了。
 */
class KbDeleteFlowTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MilvusPartitionManager partitions;

    private String token;

    private long kbId;

    @BeforeEach
    void createProbeKb() {
        token = loginAsAdmin().data("$.accessToken");
        kbId = insertKb("delete-probe");
    }

    @AfterEach
    void cleanProbeData() {
        for (int generation : partitions.generationsOf(kbId)) {
            partitions.dropQuietly(MilvusConstants.partitionName(kbId, generation));
        }
        jdbc.update("delete from sys_chunk where kb_id = ?", kbId);
        jdbc.update("delete from sys_document where kb_id = ?", kbId);
        jdbc.update("delete from sys_kb_grant where kb_id = ?", kbId);
        jdbc.update("delete from sys_kb where id = ?", kbId);
        jdbc.update("delete from sys_audit_log where target = ?", "知识库#" + kbId);
    }

    // ==================== 判据 11 / 12：软删 ====================

    @Test
    @DisplayName("判据 11：DELETE /kb/{id} → 进回收站（行还在、deleted_at 非空），列表不再出现")
    void softDeleteMovesKbToRecycleBin() {
        assertThat(kbInList()).as("前提：删之前这个库在列表里，否则下面的断言是空断言").isTrue();

        RawResponse response = call("DELETE", "/api/kb/" + kbId, null, token);

        assertThat(response.code()).isZero();
        // `data()` 是泛型方法，直接塞进 assertThat 会因推断不出类型而报「方法调用不明确」——
        // 先落到有类型的局部变量上（本仓既有用例也是这个写法）
        Object id = response.data("$.id");
        Object deleted = response.data("$.deleted");
        Object recycled = response.data("$.recycled");
        String name = response.data("$.name");

        assertThat(String.valueOf(id)).isEqualTo(String.valueOf(kbId));
        assertThat(deleted).isEqualTo(Boolean.TRUE);
        assertThat(recycled)
                .as("回收站语义：可恢复，保留 30 天（《03》§领域模型）")
                .isEqualTo(Boolean.TRUE);
        assertThat(name)
                .as("回执里带库名 —— 删完页面上就没有它了，带上是让用户能确认删对了")
                .isEqualTo("delete-probe");

        assertThat(deletedAt()).as("软删时间应写上了").isNotNull();
        assertThat(rowStillExists()).as("**行必须还在** —— 硬删就不是回收站了").isTrue();
        assertThat(kbInList()).as("删掉的库不该再出现在列表里").isFalse();
    }

    @Test
    @DisplayName("判据 12：软删后该库不可再操作（读配置按不存在处理，报 10043）")
    void softDeletedKbIsTreatedAsMissing() {
        call("DELETE", "/api/kb/" + kbId, null, token);

        RawResponse config = get("/api/kb/" + kbId + "/config", token);

        assertThat(config.code())
                .as("已删除的库读配置应报「知识库不存在」，而不是「能读能改」")
                .isEqualTo(10043);
    }

    // ==================== 判据 5 / 6：审计 ====================

    @Test
    @DisplayName("判据 6 + G3-8 运行时半边：删除知识库**标高危**（口径侧 S3-D 已冻结，本批补的是触发点）")
    void deleteIsAuditedAsHighRisk() {
        call("DELETE", "/api/kb/" + kbId, null, token);

        List<String> risks = jdbc.queryForList(
                "select risk_level from sys_audit_log where action = ? and target = ?",
                String.class, "删除知识库", "知识库#" + kbId);

        assertThat(risks)
                .as("审计里应有这条记录 —— 在 T-407 之前，KbController 无任何 DeleteMapping，"
                        + "这条策略**没有任何触发点**")
                .containsExactly("HIGH");
    }

    @Test
    @DisplayName("判据 5 + G3-7「删除」类：审计可查，且详情含**变更前后对照**")
    void deleteAuditCarriesBeforeAfter() {
        call("DELETE", "/api/kb/" + kbId, null, token);

        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "select module, action, risk_level, operator, api_path, changes_json "
                        + "from sys_audit_log where action = ? and target = ?",
                "删除知识库", "知识库#" + kbId);
        assertThat(rows).as("应有且仅有一条删除审计").hasSize(1);

        java.util.Map<String, Object> row = rows.getFirst();
        assertThat(row.get("module")).isEqualTo("知识库");
        assertThat(row.get("operator")).as("操作人取自请求上下文 —— 直调 Service 会测成空值").isEqualTo("admin");
        assertThat(String.valueOf(row.get("api_path"))).contains("/kb/" + kbId);

        String changes = String.valueOf(row.get("changes_json"));
        assertThat(changes)
                .as("G3-7 要求详情含「变更前 → 变更后」；空值字面量是 '—'（硬口径）")
                .contains("删除状态")
                .contains("正常")
                .contains("已删除");
    }

    // ==================== 判据 10（HTTP 侧）：清空索引 ====================

    @Test
    @DisplayName("判据 10：DELETE /kb/{id}/index 回报 cleared，且同样留一条高危审计")
    void clearIndexIsAudited() {
        // 先造一个该库的分区，让"清空"有东西可清
        partitions.ensure(MilvusConstants.partitionName(kbId, 1));
        Integer genBefore = jdbc.queryForObject(
                "select index_generation from sys_kb where id = ?", Integer.class, kbId);

        RawResponse response = call("DELETE", "/api/kb/" + kbId + "/index", null, token);

        assertThat(response.code()).isZero();
        Object cleared = response.data("$.cleared");
        assertThat(cleared).isEqualTo(Boolean.TRUE);
        // ⚠️ **期望在 `S6-M05-C` 变了**（与 `ReindexFlowTest#clearIndexOnlyTouchesThatKb` **同因同改**）：
        //    清空**不再删到一个不剩**，而是**切到新代**并留下新代的**空分区**。
        //    理由：检索侧按 `sys_kb.index_generation` 找分区，而
        //    **「查一个不存在的分区是报错，不是返回空」** ⇒ 先切后建会让整个库的检索当场炸。
        //    ⚠️ 本批**先只改了一条**（那条），全量跑批才逮到本条 ——
        //    这两处是**同一形状**，改一处必须搜全（记进计划 §9）。
        assertThat(partitions.generationsOf(kbId))
                .as("只剩**新代那一个空分区**：旧代全被 drop，而新代**必须存在**")
                .containsExactly(genBefore + 1);
        assertThat(partitions.countIn(MilvusConstants.partitionName(kbId, genBefore + 1)))
                .as("新代分区是**空的** —— 清空的意义就是向量一条都不剩")
                .isZero();

        List<String> risks = jdbc.queryForList(
                "select risk_level from sys_audit_log where action = ? and target = ?",
                String.class, "清空索引", "知识库#" + kbId);
        assertThat(risks)
                .as("清空索引也是「内容删除」—— 清完这些文档检索不到了")
                .containsExactly("HIGH");
    }

    // ==================== 工具 ====================

    private long insertKb(String code) {
        jdbc.update("""
                        insert into sys_kb (code, name, dept_id, status,
                                            secret_level, business_domain, reindex_required,
                                            index_generation, created_at, updated_at)
                        values (?, ?, 1, 'READY', 'INTERNAL', '', 1, 1, now(), now())
                        """, code, code);
        long id = jdbc.queryForObject("select id from sys_kb where code = ?", Long.class, code);
        // 挂一条授权给 admin：列表读的是求交结果（认授权行，不认 superAdmin），
        // 不挂的话"删之前它在列表里"这个前提就不成立
        jdbc.update("""
                        insert into sys_kb_grant (kb_id, principal_type, principal_id, level, created_at, updated_at)
                        select ?, 'USER', id, 'MANAGE', now(), now() from sys_user where username = ?
                        """, id, ADMIN);
        return id;
    }

    /** 该库现在是否出现在 {@code GET /kb} 的列表里。 */
    private boolean kbInList() {
        RawResponse response = get("/api/kb", token);
        assertThat(response.code()).isZero();
        List<?> ids = response.data("$.list[*].id");
        return ids.stream().anyMatch(id -> String.valueOf(id).equals(String.valueOf(kbId)));
    }

    private Object deletedAt() {
        return jdbc.queryForObject("select deleted_at from sys_kb where id = ?", Object.class, kbId);
    }

    private boolean rowStillExists() {
        Integer n = jdbc.queryForObject("select count(*) from sys_kb where id = ?", Integer.class, kbId);
        return n != null && n > 0;
    }
}
