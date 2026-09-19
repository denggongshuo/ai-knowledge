package com.knowledge.ai.pipeline;

import com.knowledge.ai.pipeline.vision.ImageHash;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `T-512` 步 2b 的用例 —— **抽图这条链本身**（不碰 MinIO）。
 *
 * <h3>为什么用一个假 sink</h3>
 * <p>抽图与"存到哪"是两件事：前者是解析器的职责（哪张图有字节、哪张没有、没有的怎么登记），
 * 后者是 {@code DocParseService.ImageBuffer} 的职责。拿假 sink 把两者分开，
 * 这一层就能**不起 MinIO、不起 Spring** 地验 —— 而它恰恰是"少了一点东西而没人发现"
 * 最容易发生的地方（探针实测：75 个图元素里只有 26 个有字节）。
 *
 * <h3>钉的是那条恒等式，不是某个数字</h3>
 * <p>样本换一份，26/49 就变了。所以断言写成
 * <b>{@code 存下的 + 登记为无法提取的 == image_total}</b>
 * —— 它才是"每张图都有去处"的形状，而 `26` 只是这一份样本上的取值。
 * 只钉 26 的断言，换样本就红；而红的原因不是缺陷，是样本变了（那是噪声，会被无视）。
 *
 * <p>⚠️ 样本在 {@code docs/测试文件/}（**gitignored**）⇒ 无样本的机器上**跳过**而不是失败。
 */
class PdfImageExtractionTest {

    private static final Path SAMPLE =
            Path.of("..", "docs", "测试文件", "1-DeepSeek使用.pdf").toAbsolutePath().normalize();

    /** 记账用的假 sink —— 只收字节，不落盘。 */
    private static final class RecordingSink implements ImageSink {
        private final List<String> names = new ArrayList<>();
        private long totalBytes;

        @Override
        public Stored store(byte[] bytes, int pageNo, String sourceName) {
            names.add(sourceName);
            totalBytes += bytes.length;
            // 与生产同形：名字确定的 key + 按字节算的 md5（这里用长度占位即可，
            // 真要验 md5 是 DocParseService 那一侧的事）
            return new Stored("images/1/1/" + sourceName, String.valueOf(bytes.length));
        }
    }

    @Test
    @DisplayName("T-512 步 2b：有字节的进 sink，没字节的被登记 —— 两张相加恒等于 image_total")
    void everyImageEitherStoredOrRegistered() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(SAMPLE), "缺样本 " + SAMPLE);

        RecordingSink sink = new RecordingSink();
        ParsedDocument doc = PdfDocumentParser.parse(SAMPLE, sink);

        List<ParsedDocument.DocumentIssue> imageIssues = doc.issues().stream()
                .filter(i -> i.kind() == ParsedDocument.IssueKind.IMAGE_NOT_EXTRACTABLE)
                .toList();
        long storedElements = doc.elements().stream()
                .filter(e -> e.kind() == ParsedDocument.DocElement.Kind.IMAGE)
                .filter(e -> e.imageInfo() != null && e.imageInfo().hasBytes())
                .count();

        System.out.println("======== T-512 步 2b：抽图清册 ========");
        System.out.println("image 元素       = " + doc.imageCount());
        System.out.println("交给 sink 的      = " + sink.names.size() + "（" + sink.totalBytes / 1024 + " KB）");
        System.out.println("登记无法提取的    = " + imageIssues.size());
        System.out.println("带 objectKey 的元素 = " + storedElements);
        System.out.println("======================================");

        // 🔴 决定性恒等式：每张图**要么被抽出来、要么被登记**，不许有第三种下场
        assertThat(sink.names.size() + imageIssues.size())
                .as("存下的 + 登记为无法提取的 == 图元素总数。"
                        + "不相等就说明有图**静默消失**了 —— 那正是 T-512 步 0 探针"
                        + "（75 个元素只有 26 个有字节）逼出这条纪律时的形态")
                .isEqualTo(doc.imageCount());

        // 解析器交给 sink 的每一张，元素上也必须带得出来（两个视图不许各说各话）
        assertThat(storedElements)
                .as("`ImageInfo.hasBytes()` 为真的元素数必须等于交给 sink 的张数 —— "
                        + "否则下游（步 4 的块 / 步 8 的落库）会按元素上的值走，与真实存储分叉")
                .isEqualTo(sink.names.size());

        // `T-O03`：感知哈希**在解析期就算好了**，且只在有字节的那些元素上。
        // ⚠️ 这条不能只靠真上传的用例证 —— 那条路要真 VL（分钟级），而这里解析一次就够
        List<String> phashes = doc.elements().stream()
                .filter(e -> e.kind() == ParsedDocument.DocElement.Kind.IMAGE)
                .map(ParsedDocument.DocElement::imageInfo)
                .filter(java.util.Objects::nonNull)
                .map(ImageInfo::phash)
                .filter(java.util.Objects::nonNull)
                .toList();
        System.out.println("带 phash 的元素   = " + phashes.size());
        assertThat(phashes)
                .as("有字节的图必须在解析期就算出 phash —— 那一跳字节在手上，"
                        + "搬去别处算要么重读文件、要么让判定侧去 MinIO 取（把纯函数变成有 IO 的）")
                .isNotEmpty()
                .allSatisfy(h -> assertThat(h)
                        .as("dHash 是 64 位 ⇒ 16 位小写十六进制，与 CHAR(16) 同源")
                        .hasSize(ImageHash.LENGTH)
                        .matches("[0-9a-f]{16}"));

        // 环境前提：这份样本必须**两种都有**，否则本用例只在验一半的分支
        assertThat(sink.names).as("样本上必须有可导出的图，否则没验到抽取那半边").isNotEmpty();
        assertThat(imageIssues).as("样本上必须有抽不出的图（实测 49/75），否则没验到登记那半边").isNotEmpty();
        assertThat(imageIssues)
                .as("登记必须带**页码**与**可行动的原因** —— 用户要能点开看到'那几张为什么没有'")
                .allSatisfy(i -> {
                    assertThat(i.pageNo()).as("页码").isPositive();
                    assertThat(i.reason()).as("原因").contains("第 " + i.pageNo() + " 页");
                });
    }

    @Test
    @DisplayName("T-512 步 2b：单图 / 单篇上限不触发时，不留任何'超限'登记（负向锚）")
    void capsDoNotFireOnNormalSamples() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(SAMPLE), "缺样本 " + SAMPLE);

        ParsedDocument doc = PdfDocumentParser.parse(SAMPLE, new RecordingSink());
        // 实测这份样本单图最大 320 KB、单篇 2.52 MB，离 2MB / 50MB 差得远。
        // 若这里红了，说明上限**被调到了样本以下** —— 那是一次真实的回归，不是噪声。
        assertThat(doc.issues())
                .as("正常样本不该有任何'超限跳过'的登记（那会让上限变成一条静默丢内容的通道）")
                .noneSatisfy(i -> assertThat(i.reason()).contains("上限"));
    }
}
