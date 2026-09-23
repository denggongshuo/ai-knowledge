package com.knowledge.ai.pipeline.graph;

import com.knowledge.ai.pipeline.Block;
import com.knowledge.ai.pipeline.ParsedDocument;
import com.knowledge.ai.pipeline.enhance.EnhancePrompts;
import com.knowledge.ai.pipeline.enhance.IndexEnhancer;
import com.knowledge.ai.support.ApiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `#231` 的真实关系锚定率 —— **用真 chat 模型量，不再用替身**（`S9-M12-D` 销账）。
 *
 * <h3>为什么必须要这条（前四批都卡在这里）</h3>
 * <p>`S9-M12-A`/`B` 都点名"先在 `T-C02` 落地时量锚定率"，`S9-M12-C` 量了一次 ——
 * 但那次用的是**给死 8 条关系的替身模型**（`RelationAnchorRateProbeTest`），
 * 它**根本不读提示词** ⇒ 得到的 `5/8 = 62.5%` 是**构造样本的上界**，
 * 既不能说"真实语料下也是这样"，也**看不出提示词改动有没有用**。
 * <p>本批开工时实测 relay 已恢复（`chat`/`embed`/`rerank` 三条通路全 `200`）
 * ⇒ 这条账第一次有了取证条件。
 *
 * <h3>🔴 它量的是「模型给出的实体名有多常**逐字**出现在正文里」</h3>
 * <p>`RelationAnchor` 的规则是：subject（找不到再 object）**逐字**出现在某个非 SUMMARY 块里
 * ⇒ 锚上；否则丢。所以锚定率的上游**不是锚定代码**，而是**模型的用词**：
 * 它把正文里的「订单系统」写成「订单平台」，那条关系就丢了。
 * <p>`S9-M12-C` 为此把提示词改成要求「实体名逐字照抄」（`doc-enhance-v4`）——
 * **这条用例就是去量那个改动到底有没有把数抬上去的**。
 *
 * <h3>⚠️ 三条边界，照实写清楚（不写就会被当成一个可复现的定数）</h3>
 * <ol>
 *   <li><b>样本只有 1 篇</b> ⇒ 这个比例是**一份样本上的观测**，不是"系统的锚定率"。
 *       要一个能对外说的数，得先有一批真语料（本仓 `docs/测试文件/` 那几份互不相关，
 *       见 `#217` 那一族"没有正样本语料"的账）。</li>
 *   <li><b>模型带随机性</b> ⇒ 数**不是逐位可复现**的（承 `S8-M10-B` 那条
 *       「幂等不能按分数逐位相同写」）。所以断言只钉**区间与下界**，不钉等号。</li>
 *   <li><b>relay 不可用时它会红</b>（模型调不通 ⇒ 0 条关系 ⇒ 锚定率 0）。
 *       ⚠️ 那是"量不了"而**不是"锚定率是 0"** —— 两者必须可分辨，
 *       所以下面第一条断言专门把"模型没产出"与"产出了但锚不上"分开报。</li>
 * </ol>
 */
@TestPropertySource(properties = {
        // 🔴 **必须打开**：`src/test/resources/application.properties:19` 把 `m05.enhance.enabled`
        //    默认关着（跑批不该依赖真模型），而关掉时 `IndexEnhancer#enhance` 直接返回
        //    `Status.DISABLED` + `enhancement == null` ⇒ 本类会以"模型一条关系都没产出"的形态红。
        //    ⚠️ **本类第一次跑就是栽在这里**（全量 `953 / 1 红`）—— 而那条断言**说对了**
        //    （它报的是"量不了"，不是"锚定率是 0"，正是它设计时要区分的那两件事），
        //    只是我当时以为是 relay 的问题，去查了模型通路。**这条注释就是那次排查的产物。**
        "m05.enhance.enabled=true"})
@DisplayName("#231 · 真实锚定率（真 chat 模型，非替身）")
class RelationAnchorRateRealModelTest extends ApiTestSupport {

    /**
     * 语料：一份**结构清楚、实体名会反复出现**的中文技术文档。
     *
     * <p>刻意让它像一份真文档（有标题、有段落、有重复指代），而不是"实体名堆砌"的构造样本 ——
     * 构造样本上的锚定率必然接近 100%，量不出真问题。
     */
    private static final String BODY = """
            # 订单系统的分片方案

            订单系统在 2024 年完成了分库分表改造，核心路由基于一致性哈希实现。
            一致性哈希把每个分片映射到一个环上，扩缩容时只影响相邻的分片。

            数据分片依赖路由表，路由表由配置中心统一下发。配置中心出现故障时，
            订单系统会退回到本地缓存的路由表继续工作。

            监控告警接入统一平台，值班安排由运维组负责。容量规划每季度复核一次，
            复核结论进入架构评审。一致性哈希的虚拟节点数量是一个可调参数。
            """;

    /** 语料体量下限：模型一条都不给 = 量不了（不是"锚定率 0"）—— 见 assertThat(given) 那段。 */
    private static final int GIVEN_FLOOR = 1;

    @Autowired
    private IndexEnhancer indexEnhancer;

    @Autowired
    private Driver driver;

    private final List<Long> probeDocIds = new ArrayList<>();

    @AfterEach
    void cleanGraphResidue() {
        try (Session session = driver.session()) {
            if (!probeDocIds.isEmpty()) {
                session.run("MATCH ()-[r:REL]-() WHERE r.docId IN $ids DELETE r",
                        Map.of("ids", List.copyOf(probeDocIds))).consume();
                session.run("MATCH ()-[r:CITES]-() WHERE r.docId IN $ids DELETE r",
                        Map.of("ids", List.copyOf(probeDocIds))).consume();
            }
            // 探针实体一律带前缀，收尾按前缀清（本用例不建库/文档，只走纯函数链路）
            session.run("MATCH (e:Entity) WHERE e.name STARTS WITH '锚定率探针' DETACH DELETE e").consume();
        } catch (Exception ignored) {
            // 清不掉不该让用例失败：结论已由断言给出
        }
        probeDocIds.clear();
    }

    /**
     * 跑一次**真增强**（真 chat 模型），把产出锚定并统计。
     *
     * <p>⚠️ 不建库、不上传、不落库 —— 只调 {@link IndexEnhancer#enhance} 与
     * {@link RelationAnchor#anchor} 两个纯函数，所以它**不受任何库状态影响**，
     * 也不留任何 MySQL 残留。这也是它能脱离 `DownloadTestSupport` 的原因。
     */
    private int[] measureOnce() {
        // 🔴 `ParsedDocument#markdown` **不是**增强的输入 —— `IndexEnhancer#documentContent`
        //    遍历的是 `elements()`（`pieceOf` 按 kind 取文本：HEADING 加 `#`、PARAGRAPH 取原文、
        //    TABLE 查 `tables()`、IMAGE 返回 null）。
        //    第一版只填了 `markdown`、`elements` 给空列表 ⇒ `status=EMPTY_INPUT`
        //    （"没有可摘要的正文"），而现象是"模型一条关系都没产出" —— **看起来像 relay 不通**。
        //    ⇒ 这里按真实解析产物的形状给元素。
        List<ParsedDocument.DocElement> elements = List.of(
                ParsedDocument.DocElement.heading(1, "订单系统的分片方案", 1),
                ParsedDocument.DocElement.paragraph(
                        "订单系统在 2024 年完成了分库分表改造，核心路由基于一致性哈希实现。"
                                + "一致性哈希把每个分片映射到一个环上，扩缩容时只影响相邻的分片。", 1),
                ParsedDocument.DocElement.paragraph(
                        "数据分片依赖路由表，路由表由配置中心统一下发。配置中心出现故障时，"
                                + "订单系统会退回到本地缓存的路由表继续工作。", 1),
                ParsedDocument.DocElement.paragraph(
                        "监控告警接入统一平台，值班安排由运维组负责。容量规划每季度复核一次，"
                                + "复核结论进入架构评审。一致性哈希的虚拟节点数量是一个可调参数。", 1));
        ParsedDocument doc = new ParsedDocument(
                "MD", 1, BODY.length(), BODY, List.of(), List.of(), 4, 0,
                List.of(), elements, Map.of());
        // 与真实链路同一种块：一个正文块（内容就是全文）。锚定看的是"实体名在不在块文本里"，
        // 切得再细也不改变这个判据 ⇒ 单块足够，且让结论与分片策略解耦。
        List<Block> blocks = List.of(new Block(BODY,
                com.knowledge.ai.core.enums.BlockType.PARAGRAPH, "", 1, 1, null, null, null));

        IndexEnhancer.Result result = indexEnhancer.enhance(doc, blocks);
        System.out.printf("[真实锚定率] 增强结果 status=%s detail=%s%n", result.status(), result.detail());
        IndexEnhancer.Enhancement enhancement = result.enhancement();
        List<IndexEnhancer.Enhancement.Relation> given =
                enhancement == null || enhancement.relations() == null
                        ? List.of() : enhancement.relations();

        List<RelationAnchor.Anchored> anchored = RelationAnchor.anchor(given, blocks);
        // ⚠️ 锚上的条数由 anchor 给出；"丢了几条、为什么丢"由 GIVEN - anchored 得到
        System.out.printf("[真实锚定率] 提示词版本=%s · 模型给出 %d 条 · 锚上 %d 条 = %.1f%%%n",
                EnhancePrompts.VERSION, given.size(), anchored.size(),
                given.isEmpty() ? 0.0 : 100.0 * anchored.size() / given.size());
        for (IndexEnhancer.Enhancement.Relation r : given) {
            boolean hit = anchored.stream().anyMatch(a -> a.relation().equals(r));
            System.out.printf("    %s %s -%s-> %s（confidence %.2f）%n",
                    hit ? "✅ 锚上" : "❌ 丢弃", r.subject(), r.predicate(), r.object(), r.confidence());
        }
        return new int[] { given.size(), anchored.size() };
    }

    @Test
    @DisplayName("#231 · 真模型产出关系，且逐字照抄的那些能锚上（下界断言，非等号）")
    void realModelAnchorRateIsMeasured() {
        int[] givenAndAnchored = measureOnce();
        int given = givenAndAnchored[0];
        int anchored = givenAndAnchored[1];

        // 🔴 第一条把"量不了"与"量出来是 0"分开 —— 这是本条用例最要紧的一句断言。
        //    模型调不通时 `enhance` 会静默降级（不抛），产出的 relations 是空列表；
        //    若不先钉这一条，下面那句 "锚定率 >= 某值" 会以"0 条关系"的形态红，
        //    看起来像锚定代码坏了，其实是 relay 不通。
        assertThat(given)
                .as("模型一条关系都没产出 ⇒ 本次**量不了**（relay/模型不可用），"
                        + "而不是'锚定率是 0'。看 surefire 输出里 [真实锚定率] 那一行确认。"
                        + "⚠️ 这条红了请先查模型通路，不要去改锚定代码")
                .isGreaterThanOrEqualTo(GIVEN_FLOOR);

        // 下界（不是等号）：真模型带随机性，逐位可复现的断言会变成偶发红。
        // 取 0.3 的依据：锚定规则只要求"实体名逐字出现在正文里"，而本语料里
        // 主要实体（订单系统 / 一致性哈希 / 路由表 / 配置中心）都反复出现 ⇒
        // 一个正常的模型至少该有一半左右能对上；低于 0.3 说明**用词严重偏离原文**，
        // 那正是 `S9-M12-C` 的提示词改动要治的病。
        assertThat(100.0 * anchored / given)
                .as("真实锚定率 %.1f%%（%d/%d）低于下界 —— 模型没用正文原词，"
                        + "关系回不到原文（这是 `doc-enhance-v4` 的'逐字照抄'那条规则要治的）",
                        100.0 * anchored / given, anchored, given)
                .isGreaterThanOrEqualTo(30.0);

        // 上界：锚定率不可能是 100%（总会有改写），若真出现 100% 要人工看一眼
        // 是不是语料太好（那这条用例就量不出问题了）—— 如实报，不判红。
        System.out.printf("[真实锚定率] ⚠️ 这是**单篇样本**上的观测，不是系统的锚定率；"
                        + "要对外说一个数，先补一批真语料（见 #217 那一族的账）· 提示词 %s%n",
                EnhancePrompts.VERSION);
    }

    @Test
    @DisplayName("#231 · 提示词确实要求了逐字照抄（口径与量出来的数对得上）")
    void promptAsksForVerbatimEntityNames() {
        // 这条把"提示词说了什么"与"数是多少"分开验：
        // 前者是**可转红的文本断言**（删掉那条规则即红），后者才是实测值。
        // 两者一起看才能回答"C 批那个改动有没有落地且有没有效果"。
        String prompt = EnhancePrompts.documentLevel(BODY, false);
        assertThat(prompt).contains("逐字").contains("照抄");
    }
}
