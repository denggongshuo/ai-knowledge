package com.knowledge.ai.pipeline.cleaning;

import com.knowledge.ai.pipeline.ParsedDocument;
import com.knowledge.ai.pipeline.ParsedDocument.DocElement;
import com.knowledge.ai.pipeline.ParsedDocument.DocumentIssue;
import com.knowledge.ai.pipeline.PdfDocumentParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * `优化/O3` 页眉页脚剥离的取证（`S4-M05-C2` 步 0）。
 *
 * <h3>⚠️ 这条用例的正向证据是合成的，反向证据才是真的</h3>
 * <p>干跑实测（计划 §8）：手上三份 PDF 样本**没有一份含可被本规则判出的页眉页脚**
 * —— 包括 `O3` §5 判据 1 点名的那份（它是 PPT 导出，页脚只有一个孤零零的页码，
 * 且只出现在 6 张章节隔页上，20 页里占 4 页 = 20%，够不到 60%）。
 * 所以"能剥掉页眉"只能靠合成用例证明；
 * 而"**不会误杀**"反而是真实的 —— 见 {@link #realSamplesAreUntouched()}。
 *
 * <p>这个不对称必须写在最显眼的地方：一份只有合成证据的启发式，
 * 最大的风险不是"不够灵"，而是"在真实文档上悄悄删掉正文"。
 * {@link #repeatedTextInPageBodySurvives()} 与三个边界用例就是冲着这个风险写的。
 */
class DocumentCleanerTest {

    private final DocumentCleaner cleaner = new DocumentCleaner();

    // ==================================================================
    // 判据 1：页眉页脚被剥离
    // ==================================================================

    @Test
    @DisplayName("判据1：每页首尾的重复样板被剥离，正文一段不少")
    void headerFooterStripped() {
        ParsedDocument doc = headerFooterDoc(20);
        int originalCount = doc.elements().size();

        CleanedDocument cleaned = cleaner.clean(doc, CleaningOptions.defaults());

        assertThat(cleaned.changed()).as("20 页 × 2 处页眉页脚，必须判出来").isTrue();
        assertThat(cleaned.stripped())
                .as("判定出两个模式：页眉（含页码）与页脚（第 N 页 · 共 M 页）")
                .hasSize(2);
        assertThat(cleaned.stripped())
                .as("**归一化**后「第 1 页」与「第 20 页」必须归并成同一个模式 —— "
                        + "不归并的话页码逐页在变，连「完全重复」都算不上，这是 `O3` §2 痛点 1")
                .allSatisfy(s -> assertThat(s.pattern()).contains("#"));

        List<String> keptTexts = texts(cleaned.document());
        assertThat(keptTexts)
                .as("页眉不再出现在产物里")
                .noneSatisfy(t -> assertThat(t).contains("内部资料"));
        assertThat(keptTexts)
                .as("页脚同理")
                .noneSatisfy(t -> assertThat(t).contains("共 20 页"));

        assertThat(cleaned.document().elements())
                .as("剥掉的是每页 2 条（1 条页眉 + 1 条页脚）× 20 页 = 40 条")
                .hasSize(originalCount - 40);
        assertThat(keptTexts)
                .as("每页的正文段落全部保留")
                .hasSize(40)
                .allSatisfy(t -> assertThat(t).startsWith("正文段落"));
    }

    @Test
    @DisplayName("判据1：剥离不静默 —— 进 issue，且带种类与「第 N 页起，共 M 页」")
    void strippingIsRecordedAsIssue() {
        CleanedDocument cleaned = cleaner.clean(headerFooterDoc(20), CleaningOptions.defaults());

        assertThat(cleaned.document().issues())
                .as("原来的 issue 一条不动（这份没有），剥离记录按模式各一条")
                .hasSize(2)
                .allSatisfy(i -> assertThat(i.kind())
                        .isEqualTo(ParsedDocument.IssueKind.HEADER_FOOTER_STRIPPED));
        assertThat(cleaned.document().issues())
                .as("锚在**首次出现**的页，不是全文级哨兵 —— sys_parse_issue 的建表口径禁止哨兵值")
                .allSatisfy(i -> assertThat(i.pageNo()).isEqualTo(1));
        assertThat(cleaned.document().issues())
                .as("文案要给现象与页数，让人能判断是不是剥错了")
                .anySatisfy(i -> assertThat(i.reason())
                        .contains("20 页").contains("剥离").contains("稀释语义"));
    }

    // ==================================================================
    // 判据 2：正文中的重复句不被误剥离
    // ==================================================================

    @Test
    @DisplayName("判据2：同样重复的句子，落在**正文位置**就保留 —— 位置才是判据，不是重复度")
    void repeatedTextInPageBodySurvives() {
        String repeated = "本条款适用于本项目所有工作组及成员单位。";
        CleanedDocument cleaned = cleaner.clean(bodyRepeatedDoc(20, repeated), CleaningOptions.defaults());

        assertThat(texts(cleaned.document()))
                .as("这句在 20 页的**正文位置**每页都出现，重复度与页眉一模一样 —— "
                        + "唯一能区分它们的是「落不落在首/尾 3 行以内」。"
                        + "把位置判据拿掉，「凡重复即剥」会在这里当场删掉 20 处正文")
                .filteredOn(t -> t.equals(repeated))
                .hasSize(20);

        assertThat(cleaned.stripped())
                .as("同一份文档里，页眉照剥（它确实在页边）、正文重复照留（它在第 4 行）—— "
                        + "两者都在，才说明去留是被**位置**决定的")
                .hasSize(2);
    }

    // ==================================================================
    // 判据 5：纯函数、可回放
    // ==================================================================

    @Test
    @DisplayName("判据5：同一产物跑两次，结果逐字段一致")
    void cleaningIsReplayable() {
        ParsedDocument doc = headerFooterDoc(20);

        CleanedDocument first = cleaner.clean(doc, CleaningOptions.defaults());
        CleanedDocument second = cleaner.clean(doc, CleaningOptions.defaults());

        assertThat(second.document())
                .as("记录类型的 equals 逐字段比较 —— 元素、派生计数、issue 全都要一致")
                .isEqualTo(first.document());
        assertThat(second.stripped()).isEqualTo(first.stripped());
    }

    @Test
    @DisplayName("没剥到东西时**原样返回入参**（同一引用），不是「看起来一样」")
    void noStrippingReturnsSameInstance() {
        ParsedDocument doc = headerFooterDoc(2);   // 2 页 < minPages

        CleanedDocument cleaned = cleaner.clean(doc, CleaningOptions.defaults());

        assertThat(cleaned.document()).isSameAs(doc);
        assertThat(cleaned.changed()).isFalse();
    }

    // ==================================================================
    // 边界：三条刻意收紧的口子（每一条都对应一种"会删正文"的路径）
    // ==================================================================

    @Test
    @DisplayName("边界：页数不足 minPages 不判定 —— 2 页里重复 2 次说明不了什么")
    void tooFewPagesIsNotJudged() {
        assertThat(cleaner.clean(headerFooterDoc(2), CleaningOptions.defaults()).changed())
                .as("页数不够，宁可漏剥离")
                .isFalse();
        assertThat(cleaner.clean(headerFooterDoc(3), CleaningOptions.defaults()).changed())
                .as("刚好够 3 页就必须判（否则这条用例只是把门槛记错了位置）")
                .isTrue();
    }

    @Test
    @DisplayName("边界：只有一行的页不参与 —— 那种页的「唯一一行」按定义就是它的正文")
    void singleLinePagesDoNotParticipate() {
        List<DocElement> elements = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            elements.add(DocElement.paragraph("谢谢观看", page));
        }
        ParsedDocument doc = doc(10, elements);

        assertThat(cleaner.clean(doc, CleaningOptions.defaults()).changed())
                .as("10 页每页一行、行行相同。让它参与的话，整篇会被剥成空 —— "
                        + "而这是一类真实存在的文档（每页一句的讲义）")
                .isFalse();
    }

    @Test
    @DisplayName("边界：被剥的模式里**不含**正文位置那句（逐条核对清单，不只看条数）")
    void strippedPatternsNeverContainBodyText() {
        String repeated = "本条款适用于本项目所有工作组及成员单位。";
        CleanedDocument cleaned = cleaner.clean(bodyRepeatedDoc(20, repeated), CleaningOptions.defaults());

        assertThat(cleaned.stripped())
                .as("清单要指名道姓写清剥了什么 —— 只断言条数的话，"
                        + "把正文也剥一条、少剥一条页眉，条数照样对得上")
                .extracting(CleanedDocument.StrippedPattern::sample)
                .allSatisfy(s -> assertThat(s).doesNotContain("本条款"));
        assertThat(cleaned.stripped())
                .extracting(CleanedDocument.StrippedPattern::sample)
                .anySatisfy(s -> assertThat(s).contains("内部资料"));
    }

    @Test
    @DisplayName("边界：非分页格式（pages = 1）一律不判 —— Tika/MD/TXT 路径根本没有页眉页脚")
    void singlePageFormatsAreNeverCleaned() {
        List<DocElement> elements = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            elements.add(DocElement.paragraph("同一句话重复 30 次", 1));
        }
        assertThat(cleaner.clean(doc(1, elements), CleaningOptions.defaults()).changed())
                .as("没有页概念的格式，跨页规则无从谈起。若在这里也判，等于按「重复」剥正文")
                .isFalse();
    }

    @Test
    @DisplayName("参数越界当场炸，不留一个「悄悄什么都不剥」的清洗器")
    void badOptionsFailLoudly() {
        assertThatThrownBy(() -> new CleaningOptions(0, 3, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CleaningOptions(1.5, 3, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CleaningOptions(0.6, 0, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CleaningOptions(0.6, 3, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new CleaningOptions(0.6, 3, 3).pageThreshold(7))
                .as("向上取整：7 × 0.6 = 4.2 → 5。四舍五入会给 4，比配置的意思更宽，"
                        + "那不该由取整方式悄悄决定")
                .isEqualTo(5);
    }

    // ==================================================================
    // 反向证据：真样本上必须一处都不剥
    // ==================================================================

    /**
     * ★ 真样本一处不剥、{@code chars} 一字不减。
     *
     * <h3>⚠️ 这条对照的**灵敏度有限**，别高估它</h3>
     * <p>反证实测（计划 §8）：把 {@code HeaderFooterStripper} 的位置判据去掉、
     * 退化成"凡重复即剥"之后，判据 2 的两条用例当场转红，而**本条依然是绿的** ——
     * 真样本上恰好没有"重复到 60% 页"的正文，所以退化版也误杀不到它们。
     *
     * <p>所以本条证明的是"**当前这版规则**在真实文档上不乱删"，
     * 而不是"规则错了会被发现"。真正起区分作用的是合成用例。
     * 它有价值的场景是**将来**：规则一旦调到会误杀，这里会第一个响。
     */
    @Test
    @DisplayName("★ 真样本一处不剥、chars 一字不减（防误杀对照；灵敏度有限，见 javadoc）")
    void realSamplesAreUntouched() throws Exception {
        Path[] samples = {
                Path.of("..", "docs", "测试文件",
                        "江西省农村信用社联合社_业务中台整体规划项目_项目管理章程.pdf").toAbsolutePath().normalize(),
                Path.of("..", "docs", "测试文件", "1-DeepSeek使用.pdf").toAbsolutePath().normalize(),
                Path.of("..", "docs", "测试文件", "百福云PaaS平台应用部署快速入门 v1.0.pdf")
                        .toAbsolutePath().normalize(),
        };
        Assumptions.assumeTrue(java.util.Arrays.stream(samples).allMatch(Files::isRegularFile),
                "docs/测试文件/ 是 gitignored，无样本时跳过（与 DocParseFlowTest 同一条纪律）");

        for (Path sample : samples) {
            ParsedDocument doc = PdfDocumentParser.parse(sample);
            assertThat(doc.pages())
                    .as("%s：页数必须够 minPages，否则清洗器**根本没进入判定**，"
                            + "这条反向证据就成了空断言（它照样是绿的，却什么也没证明）", sample.getFileName())
                    .isGreaterThanOrEqualTo(CleaningOptions.defaults().minPages());

            CleanedDocument cleaned = cleaner.clean(doc, CleaningOptions.defaults());

            assertThat(cleaned.changed())
                    .as("%s：本批手上没有一份样本含可判出的页眉页脚，所以剥离数必须是 0。"
                            + "一旦这里转红，说明规则开始误杀 —— 那比漏剥离严重得多", sample.getFileName())
                    .isFalse();
            assertThat(cleaned.document())
                    .as("%s：没剥到东西时原样返回，chars 与元素序列一字不动", sample.getFileName())
                    .isSameAs(doc);
        }
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /** 20 页，每页 1 条页眉 + 2 条正文 + 1 条页脚。正文用字母保证**归一化后仍逐页不同**。 */
    private static ParsedDocument headerFooterDoc(int pages) {
        List<DocElement> elements = new ArrayList<>();
        for (int page = 1; page <= pages; page++) {
            elements.add(DocElement.paragraph("XX公司 · 内部资料 · 第 " + page + " 页", page));
            elements.add(DocElement.paragraph("正文段落 " + letter(page) + "：这是第 " + page + " 页的正文。", page));
            elements.add(DocElement.paragraph("正文段落 " + letter(page + 1) + "：第 " + page + " 页的另一段。", page));
            elements.add(DocElement.paragraph("第 " + page + " 页 · 共 " + pages + " 页", page));
        }
        return doc(pages, elements);
    }

    /**
     * 每页 7 行，重复句固定落在**第 4 行**（下标 3）。
     *
     * <p>首/尾窗口各 3 行 ⇒ 候选是 0~2 与 4~6，下标 3 被排除在外。
     * 这是"同样重复，位置决定去留"的最小构造。
     */
    private static ParsedDocument bodyRepeatedDoc(int pages, String repeated) {
        List<DocElement> elements = new ArrayList<>();
        for (int page = 1; page <= pages; page++) {
            elements.add(DocElement.paragraph("内部资料 · 第 " + page + " 页", page));
            elements.add(DocElement.paragraph("正文甲 " + letter(page) + "（第 " + page + " 页）", page));
            elements.add(DocElement.paragraph("正文乙 " + letter(page) + "（第 " + page + " 页）", page));
            elements.add(DocElement.paragraph(repeated, page));
            elements.add(DocElement.paragraph("正文丙 " + letter(page) + "（第 " + page + " 页）", page));
            elements.add(DocElement.paragraph("正文丁 " + letter(page) + "（第 " + page + " 页）", page));
            elements.add(DocElement.paragraph("第 " + page + " 页 / 共 " + pages + " 页", page));
        }
        return doc(pages, elements);
    }

    private static ParsedDocument doc(int pages, List<DocElement> elements) {
        // markdown 刻意给一段非空串：清洗要证明的是"它原样带过"，空串证明不了
        return new ParsedDocument("PDF", pages, 0, "# 标题\n\n原文\n\n", List.of(), List.of(),
                0, 0, List.of(), elements);
    }

    private static List<String> texts(ParsedDocument doc) {
        List<String> out = new ArrayList<>();
        for (DocElement el : doc.elements()) {
            if (el.text() != null) {
                out.add(el.text());
            }
        }
        return out;
    }

    /** 逐页唯一的字母：数字会被归一化抹掉，字母不会 —— 正文要"逐页不同"只能用字母。 */
    private static String letter(int n) {
        return String.valueOf((char) ('A' + Math.floorMod(n, 26)));
    }

    /**
     * 步 0.6：`kind` 是**新增字段**，旧 `parse.json` 里没有它。
     *
     * <p>所以这条必须走**真的反序列化**，不能 new 一个对象再断言 —— 记录类没有两参构造器，
     * 兜底逻辑（紧凑构造器里的 {@code kind == null ? OTHER : kind}）只在
     * Jackson 把缺失字段传成 {@code null} 时才生效。用 new 写的话，
     * 测的是"我能不能手动传 null"，而不是"旧产物读不读得出来"。
     */
    @Test
    @DisplayName("步 0.6：旧产物缺 kind 字段时兜底成 OTHER，反序列化不炸")
    void missingKindFallsBackToOther() throws Exception {
        // 旧产物的真实形状：只有 pageNo 与 reason
        String oldJson = "{\"pageNo\":3,\"reason\":\"旧产物里没有 kind 字段\"}";

        DocumentIssue issue = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(oldJson, DocumentIssue.class);

        assertThat(issue.pageNo()).isEqualTo(3);
        assertThat(issue.kind())
                .as("不兜底的话这里会是 null，而 null 种类的 issue 落到 sys_parse_issue "
                        + "会拼不出前缀 —— 也就是「这条问题属于哪一类」彻底丢了")
                .isEqualTo(ParsedDocument.IssueKind.OTHER);
    }
}
