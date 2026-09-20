package com.knowledge.ai.service;

import com.knowledge.ai.core.ai.RerankClient;
import com.knowledge.ai.core.ops.MissKeyNormalizer;
import com.knowledge.ai.core.ops.QaMissRepository;
import com.knowledge.ai.dto.ops.OpsDtos;
import com.knowledge.ai.pipeline.retrieve.RetrievalFlowSupport;
import com.knowledge.ai.pipeline.retrieve.RetrievalTestStubs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 重放验证（`T-A01` · 需求 `08` §1.4 闭环第 4 步 · 门禁 `G8-5`）。
 *
 * <h3>为什么接在 {@link RetrievalFlowSupport} 上</h3>
 * <p>重放要验的是**真检索跑完之后**的结论：召回分有没有上去、召回到的是不是补录那篇。
 * 替掉检索的话，验的就成了替身自己的行为 —— 那正是 `RetrievalTestStubs` 类注释里
 * 记着的那次教训（替身没生效、用例照样绿）。所以这里**只替模型那一层**
 * （嵌入与重排），MySQL / Milvus / 检索链路全是真的。
 *
 * <h3>为什么直接调 {@code ReplayService} 而不走 HTTP</h3>
 * <p>{@link RetrievalFlowSupport} 是 {@code WebEnvironment.MOCK}（检索用例不需要容器端口），
 * 而本类要的是真库真集合。**权限那半边**（`USER`/`AUDITOR` 403）在
 * {@code QaMissFlowTest#permissionBoundary} 里用真 HTTP 钉住 ——
 * 两处各自只失败于一个原因，比在一处硬凑两套夹具清楚。
 *
 * <h3>🔴 重排替身按**内容**打分，这是本类能立住的关键</h3>
 * <p>重放的两个判据（分数提升 / 命中补录文档）都取决于"哪一篇被排到前面"。
 * 替身若按固定顺序打分，"命中补录文档"就成了我摆出来的而不是链路算出来的。
 * 这里按**分片正文里有没有某个词**给分 —— 于是"命中谁"完全由 Milvus 实际召回了
 * 哪些块决定，替身只负责把分数放上去。
 *
 * <h3>自清</h3>
 * <p>池子行按自增 id 水位线删（提问人是种子超管 `id=1`，无主兜底够不着 ——
 * `ProbeCleanup#sweepOrphanMisses` 的射程之外）；库/文档/分片/分区走基类的
 * {@link RetrievalFlowSupport#cleanProbeData()}。⚠️ **重放自己不产任何行**
 * （那是本类的一条判据），所以没有别的要清。
 */
class QaMissReplayFlowTest extends RetrievalFlowSupport {

    /** 探针问题：它在探针库里"没人答得上"（补录前分很低），也正是补录备注要带的那串文本。 */
    private static final String QUESTION = "贝尔不等式怎么推导";

    /** 补录文档的一段正文 —— 与问题共享足够多的字，向量那一路招得回来。 */
    private static final String ANSWER_TEXT =
            "贝尔不等式的推导从 EPR 佯谬出发：假设存在局域隐变量，可写出 CHSH 形式，"
                    + "推导得 |S| ≤ 2；而量子力学对自旋单态给出 2√2，故局域隐变量被证伪。";

    /** 另一篇文档的正文 —— 同样与问题相关（所以它会被召回），但它**不是**补录的那篇。 */
    private static final String OTHER_TEXT =
            "贝尔不等式的实验检验：用纠缠光子对做偏振分析，实验上观察到违反贝尔不等式的结果。";

    /** 与问题**毫无关系**的一段 —— 用它当补录文档，验证"补了个没用的"这条边。 */
    private static final String UNRELATED_TEXT = "差旅费用报销应在返回后十五个工作日内提交，发票需与行程单一致。";

    @Autowired
    private ReplayService replayService;

    @Autowired
    private QaMissRepository qaMissRepository;

    @Autowired
    private QaMissService qaMissService;

    /** `sys_qa_miss` 的自增水位线 —— 之后新增的行全是本轮造的。 */
    private long missFloor;

    @BeforeEach
    void setUpProbe() {
        RetrievalTestStubs.resetRerank();
        kbId = insertKb("s8qareplay");
        Long max = jdbc.queryForObject("select coalesce(max(id), 0) from sys_qa_miss", Long.class);
        missFloor = max == null ? 0L : max;
    }

    @AfterEach
    void tearDownProbe() {
        RetrievalTestStubs.resetRerank();
        jdbc.update("delete from sys_qa_miss where id > ?", missFloor);
        cleanProbeData();
    }

    // ==================== G8-5 ① 分数达标 ② 命中补录文档 ====================

    @Test
    @DisplayName("G8-5：补录一篇真答得上的文档 ⇒ 达标且命中 ⇒ 自动关闭，且展示 补录前 → 补录后")
    void improvedSupplementIsResolved() {
        long id = seedMiss(0.02);
        long docId = supplement(ANSWER_TEXT, QUESTION, "READY");
        awaitVectorsVisible(QUESTION, 1);
        stubRerankByKeyword("贝尔", 0.93);

        OpsDtos.ReplayResult res = replayService.replay(id, reader());
        assertEmbeddingStubInUse();

        assertThat(res.before()).as("补录前的分来自**记录值**（池子的 max_top_score）").isEqualTo(0.02);
        assertThat(res.after()).as("重放拿到的分与打点/池子**同一个算法**").isEqualTo(0.93);
        assertThat(res.improved()).isTrue();
        assertThat(res.hitSupplementedDoc()).isTrue();
        assertThat(res.status()).as("达标 且 命中补录文档 ⇒ 自动关闭").isEqualTo("RESOLVED");
        assertThat(res.note()).as("回话要能回答『凭什么』—— 要**点名是哪一篇**，否则用户无从复核")
                .contains("0.93", "补录文档", "关闭");

        assertThat(statusOf(id)).isEqualTo("RESOLVED");
        assertThat(scoreAfterOf(id)).as("闭环证据落库：列表要显示 补录前 → 补录后")
                .isEqualByComparingTo("0.93");
        assertThat(jdbc.queryForObject("select resolved_at from sys_qa_miss where id = ?",
                LocalDateTime.class, id)).as("已解决要留时刻").isNotNull();
        assertThat(jdbc.queryForObject("select status from sys_document where id = ?",
                String.class, docId)).isEqualTo("READY");
    }

    @Test
    @DisplayName("G8-5 负向锚：分数上去了、但召回的**不是**补录那篇 ⇒ 不置已解决")
    void scoreUpButOtherDocIsNotResolution() {
        long id = seedMiss(0.02);
        supplement(OTHER_TEXT, "别的问题", "READY");     // 这篇被召回、且分很高
        supplement(ANSWER_TEXT, QUESTION, "READY");      // 这篇才是"为这条问题补录"的
        awaitVectorsVisible(QUESTION, 2);
        // 只给"另一篇"高分 —— 于是分达标了，但命中的不是补录文档
        stubRerankByKeyword("实验检验", 0.93);

        OpsDtos.ReplayResult res = replayService.replay(id, reader());

        assertThat(res.after()).isEqualTo(0.93);
        assertThat(res.improved()).as("分数确实提升了 —— 这正是最容易被误判成『解决了』的形状")
                .isTrue();
        assertThat(res.hitSupplementedDoc()).as("需求 §1.4 的注：「否则可能只是别的文档碰巧被召回」")
                .isFalse();
        assertThat(res.status()).as("分是别的文档抬上去的 ⇒ 不算解决").isEqualTo("PENDING");
        assertThat(res.note()).contains("不是", "退回待处理");
        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select resolved_at from sys_qa_miss where id = ?",
                LocalDateTime.class, id)).as("没解决就不能留 solved 时刻").isNull();
    }

    @Test
    @DisplayName("G8-5 负向锚：补录一篇与问题无关的文档 ⇒ 分数不达标 ⇒ 退回待处理")
    void unrelatedSupplementKeepsPending() {
        long id = seedMiss(0.02);
        supplement(UNRELATED_TEXT, QUESTION, "READY");
        // 无关文本不召回到任何东西 ⇒ 连一条候选都没有。⚠️ 这正是"补了个没用的"的真实现象
        stubRerankByKeyword("贝尔", 0.93);

        OpsDtos.ReplayResult res = replayService.replay(id, reader());

        assertThat(res.after()).as("一条都没召回 ⇒ after 是 null，**不是 0**").isNull();
        assertThat(res.improved()).isFalse();
        assertThat(res.hitSupplementedDoc()).isFalse();
        assertThat(res.status()).isEqualTo("PENDING");
        assertThat(res.note()).as("要说清是『什么都没搜到』，而不是笼统的『未达标』").contains("没有召回");
        assertThat(statusOf(id)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("G8-5：重放幂等 —— 连点两次，五个值全不变，且 ask_count 不涨")
    void replayIsIdempotent() {
        long id = seedMiss(0.02);
        supplement(ANSWER_TEXT, QUESTION, "READY");
        awaitVectorsVisible(QUESTION, 1);
        stubRerankByKeyword("贝尔", 0.93);

        OpsDtos.ReplayResult first = replayService.replay(id, reader());
        int askCount = jdbc.queryForObject("select ask_count from sys_qa_miss where id = ?",
                Integer.class, id);
        OpsDtos.ReplayResult second = replayService.replay(id, reader());

        assertThat(second.before()).isEqualTo(first.before());
        assertThat(second.after()).isEqualTo(first.after());
        assertThat(second.improved()).isEqualTo(first.improved());
        assertThat(second.hitSupplementedDoc()).isEqualTo(first.hitSupplementedDoc());
        assertThat(second.status()).isEqualTo(first.status());
        assertThat(jdbc.queryForObject("select ask_count from sys_qa_miss where id = ?",
                Integer.class, id))
                .as("🔴 重放**不是一次提问** —— ask_count 涨了的话，"
                        + "上一批那条 sum(ask_count) == count(miss_flag=1) 的对账判据当场失效")
                .isEqualTo(askCount);
    }

    @Test
    @DisplayName("🔴 重放**不落打点**：跑完打点行数与 7 天统计一个都不动")
    void replayWritesNoTrace() {
        long id = seedMiss(0.02);
        supplement(ANSWER_TEXT, QUESTION, "READY");
        awaitVectorsVisible(QUESTION, 1);
        stubRerankByKeyword("贝尔", 0.93);

        long tracesBefore = count("select count(*) from sys_retrieval_trace");
        long chunksBefore = count("select count(*) from sys_retrieval_trace_chunk");
        OpsDtos.QaOverview before = qaMissService.overview("7d");

        replayService.replay(id, reader());

        assertThat(count("select count(*) from sys_retrieval_trace"))
                .as("重放也落一行打点的话，需求 §1.1 的『提问总数』会把一次重放算成一次提问"
                        + "—— 而页面上的数只会『略微偏大』，看不出原因")
                .isEqualTo(tracesBefore);
        assertThat(count("select count(*) from sys_retrieval_trace_chunk")).isEqualTo(chunksBefore);
        OpsDtos.QaOverview after = qaMissService.overview("7d");
        assertThat(after.totalQuestions()).isEqualTo(before.totalQuestions());
        assertThat(after.missed()).isEqualTo(before.missed());
    }

    // ==================== 前置自检：判不了的时候不许判 ====================

    @Test
    @DisplayName("前置自检：补录文档**还没就绪** ⇒ 如实回『判不了』且**不改状态**")
    void notReadySupplementBlocksVerdict() {
        long id = seedMiss(0.02);
        long docId = supplement(ANSWER_TEXT, QUESTION, "INDEXING");
        stubRerankByKeyword("贝尔", 0.93);

        OpsDtos.ReplayResult res = replayService.replay(id, reader());

        assertThat(res.status()).as("排序失误（先重放再等索引）不该被记成一个关于内容的结论")
                .isEqualTo("PENDING");
        assertThat(res.after()).as("没跑检索就不该有分").isNull();
        assertThat(res.note()).contains("还在处理中", "READY");
        assertThat(statusOf(id)).isEqualTo("PENDING");
        assertThat(scoreAfterOf(id)).as("判不了 ⇒ 不写分数").isNull();

        // 解析失败：要说清失败原因与下一步（`G4-3`）
        jdbc.update("update sys_document set status = 'FAILED', error_msg = ? where id = ?",
                "未提取到可复制文本（疑似扫描件）", docId);
        OpsDtos.ReplayResult failed = replayService.replay(id, reader());
        assertThat(failed.status()).isEqualTo("PENDING");
        assertThat(failed.note()).as("失败要给『现象 + 下一步动作』，不是一句『失败』")
                .contains("解析失败", "未提取到可复制文本", "重新上传");
    }

    @Test
    @DisplayName("前置自检：压根没有补录文档 ⇒ 不改状态，并说清怎么补")
    void missingSupplementBlocksVerdict() {
        long id = seedMiss(0.02);
        supplement(ANSWER_TEXT, "别的问题", "READY");   // 备注是别人的，等于没为本条补录

        OpsDtos.ReplayResult res = replayService.replay(id, reader());

        assertThat(res.status()).isEqualTo("PENDING");
        assertThat(res.hitSupplementedDoc()).isFalse();
        assertThat(res.note()).contains("补录");
        assertThat(scoreAfterOf(id)).isNull();
    }

    @Test
    @DisplayName("前置自检：池子里没有『补录前』的分（旧行）⇒ 不作判定")
    void noBeforeScoreBlocksVerdict() {
        long id = seedMiss(0.02);
        jdbc.update("update sys_qa_miss set max_top_score = null where id = ?", id);
        supplement(ANSWER_TEXT, QUESTION, "READY");
        awaitVectorsVisible(QUESTION, 1);
        stubRerankByKeyword("贝尔", 0.93);

        OpsDtos.ReplayResult res = replayService.replay(id, reader());

        assertThat(res.before()).isNull();
        assertThat(res.status()).isEqualTo("PENDING");
        assertThat(res.note()).contains("补录前", "没有对比");
        assertThat(scoreAfterOf(id)).isNull();
    }

    // ==================== 状态机与关联键 ====================

    @Test
    @DisplayName("G8-3：已忽略的问题上重放 ⇒ 只报分数，**不**把它拉回待处理")
    void replayOnIgnoredDoesNotResurrect() {
        long id = seedMiss(0.02);
        jdbc.update("update sys_qa_miss set status = 'IGNORED', ignored_by = 1, ignored_at = now() "
                + "where id = ?", id);
        supplement(ANSWER_TEXT, QUESTION, "READY");
        awaitVectorsVisible(QUESTION, 1);
        stubRerankByKeyword("贝尔", 0.93);

        OpsDtos.ReplayResult res = replayService.replay(id, reader());

        assertThat(res.status()).as("需求 §1.3：记忽略人就是为了『避免同一问题被反复忽略又反复冒出』"
                        + "—— 一次重放把它悄悄拉回待处理，正好把那条理由推翻")
                .isEqualTo("IGNORED");
        assertThat(statusOf(id)).isEqualTo("IGNORED");
        assertThat(scoreAfterOf(id)).as("但分数是证据，照样记下来").isEqualByComparingTo("0.93");
        assertThat(res.note()).contains("已被忽略");
    }

    @Test
    @DisplayName("关联键：备注与池子问句用**同一个归一化** ⇒ 标点/全半角不同也认得出")
    void remarkIsMatchedByTheSameNormalizer() {
        long id = seedMiss(0.02);
        // 池子里存的是"？"，补录时备注写成全角问号 + 空格 —— 归一到同一个键
        supplement(ANSWER_TEXT, "贝尔不等式 怎么推导？？", "READY");
        awaitVectorsVisible(QUESTION, 1);
        stubRerankByKeyword("贝尔", 0.93);

        OpsDtos.ReplayResult res = replayService.replay(id, reader());

        assertThat(res.hitSupplementedDoc())
                .as("认不出来的话，重放会永远说『不是补录那篇』—— 而那是**关联断了**，不是内容问题")
                .isTrue();
        assertThat(res.status()).isEqualTo("RESOLVED");
    }

    // ==================== 夹具 ====================

    /** 沉淀一行未命中（走**生产同一个方法**，与 `QaMissFlowTest` 同一条口径）。 */
    private long seedMiss(double score) {
        qaMissRepository.upsert(MissKeyNormalizer.keyOf(QUESTION), QUESTION, 1L, score,
                String.valueOf(kbId), LocalDateTime.now());
        Long id = jdbc.queryForObject("select id from sys_qa_miss where miss_key = ?",
                Long.class, MissKeyNormalizer.keyOf(QUESTION));
        assertThat(id).as("沉淀必须真的落库").isNotNull();
        return id;
    }

    /**
     * 造一篇**带补录备注**的文档。
     *
     * <p>备注用 JDBC 直接写：`insertDocWithChunks` 的 SQL 里没有这一列（它是共用夹具）。
     * ⚠️ **备注的写入路径本身不必在这里验** —— `DocRecordWriter#insert` 的那条 INSERT
     * 已经被既有的上传用例跑过了（少一列会当场报错），它才是"上传带 remark"的承重点。
     */
    private long supplement(String chunkText, String remark, String status) {
        long docId = insertDocWithChunks("补录文档-" + Math.abs(chunkText.hashCode() % 100000), chunkText);
        jdbc.update("update sys_document set remark = ?, status = ? where id = ?", remark, status, docId);
        // ⚠️ `insertDocWithChunks` 只写 MySQL 那半边 —— 不写 Milvus 的话检索**一条都召不回**，
        // 而现象是重放说"什么都没搜到"，看起来像"补的文档没用"
        seedVectors(docId, 1);
        return docId;
    }

    /**
     * 重排替身：**按正文里有没有关键词给分**（见类注释）。
     *
     * <p>其余候选给 0.01 —— 低于判定线（`sys_setting` 的 0.35），于是"谁被排到前面"
     * 完全由"谁真的被召回了"决定。
     */
    private void stubRerankByKeyword(String keyword, double score) {
        // ⚠️ `RerankClient` 不是函数式接口（三个方法），只能匿名类 —— 与 `RetrievalServiceTest`
        // 里那两处替身同形
        RetrievalTestStubs.rerankStub = new RerankClient() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String describe() {
                return "用例替身：按正文含『" + keyword + "』给 " + score + "，其余给 0.01";
            }

            @Override
            public List<RerankHit> rerank(String query, List<String> documents, int topK) {
                ToDoubleFunction<String> scoring =
                        document -> document.contains(keyword) ? score : 0.01;
                List<RerankHit> hits = new ArrayList<>(documents.size());
                for (int i = 0; i < documents.size(); i++) {
                    hits.add(new RerankHit(i, scoring.applyAsDouble(documents.get(i))));
                }
                hits.sort(Comparator.comparingDouble(RerankHit::score).reversed());
                return hits;
            }
        };
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("select status from sys_qa_miss where id = ?", String.class, id);
    }

    private java.math.BigDecimal scoreAfterOf(long id) {
        return jdbc.queryForObject("select score_after from sys_qa_miss where id = ?",
                java.math.BigDecimal.class, id);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}
