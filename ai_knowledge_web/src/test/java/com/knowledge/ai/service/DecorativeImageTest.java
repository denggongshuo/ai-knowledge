package com.knowledge.ai.service;

import com.knowledge.ai.core.enums.BlockType;
import com.knowledge.ai.pipeline.Block;
import com.knowledge.ai.pipeline.ImageInfo;
import com.knowledge.ai.pipeline.retrieve.RetrievalFlowSupport;
import com.knowledge.ai.pipeline.vectorize.VectorChunk;
import com.knowledge.ai.pipeline.vision.DecorativeImageRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装饰图判定（`T-O03`）—— 分片走**真实写入路径**（`DocRecordWriter#replaceChunks`）。
 *
 * <h3>为什么必须走真路径</h3>
 * <p>与 {@code BoilerplateTest} 同一条理由：判定**就长在落库那条路径上**
 * （`replaceChunks` 里紧挨着 `markBoilerplate`）。用 JDBC 直接插分片就绕过了被验的那段代码。
 *
 * <h3>两条负向锚是这张表的主体</h3>
 * <p>只验"重复的图会被标"是没有意义的 —— 一个"凡图片块都标"的实现照样能绿。
 * 所以并排验三条**不该标**的：只出现一篇的 · **面积到了「≈整页」的** · 没有哈希的。
 * 尤其第二条：`#205` 的假阳性（把全扫描件的**整页内容**判成装饰图）正是它挡的，
 * 而那条的代价是**那部分内容搜不到了**。
 */
class DecorativeImageTest extends RetrievalFlowSupport {

    @Autowired
    private DocRecordWriter recordWriter;

    @Autowired
    private ChunkReader chunkReader;

    /** 一枚"logo"的哈希（面积取 20,000 pt²，远在「≈整页」上界之下）。 */
    private static final String LOGO = "0123456789abcdef";

    /** 与 {@link #LOGO} 只差 **1 位**（末位 f → e）—— 模拟"同一张图被重编码过"。 */
    private static final String LOGO_REENCODED = "0123456789abcdee";

    private static final double LOGO_AREA = 20_000d;

    @BeforeEach
    void setUp() {
        resetProbeState();
        kbId = insertKb("decorative-probe");
    }

    @AfterEach
    void tearDown() {
        cleanProbeData();
    }

    @Test
    @DisplayName("同一张 logo 出现在 N 篇 ⇒ 标 is_decorative=1（跨文档那条判据生效）")
    void logoAcrossDocumentsIsMarked() {
        for (int i = 0; i < DecorativeImageRule.MIN_DOCS; i++) {
            long docId = insertEmptyDoc("手册 " + i);
            recordWriter.replaceChunks(docId, kbId,
                    List.of(imageBlock(LOGO, LOGO_AREA, "md5-" + i)));
        }

        assertThat(markedRowsFor(LOGO))
                .as("出现在 %d 篇里就该被标（阈值见 DecorativeImageRule#MIN_DOCS）",
                        DecorativeImageRule.MIN_DOCS)
                .isPositive();
    }

    @Test
    @DisplayName("🔴 负向锚①：只出现在**一篇**里的图不标 —— 缺了它，一个「凡图片块都标」的实现也能绿")
    void imageInASingleDocumentIsNotMarked() {
        long docId = insertEmptyDoc("孤本");
        recordWriter.replaceChunks(docId, kbId,
                List.of(imageBlock(LOGO, LOGO_AREA, "md5-only")));

        assertThat(markedRowsFor(LOGO))
                .as("跨文档计数含本文档在内只有 1，够不到阈值")
                .isZero();
    }

    @Test
    @DisplayName("🔴 负向锚②：**面积到了「≈整页」的图一律不标** —— 这条挡的是 #205 那个假阳性")
    void fullPageSizedImageIsNeverMarked() {
        String fullPage = "fedcba9876543210";
        for (int i = 0; i < DecorativeImageRule.MIN_DOCS; i++) {
            long docId = insertEmptyDoc("扫描件 " + i);
            // 面积刚好越过上界 —— 取"最接近边界"的那个值，而不是随便给个巨大数
            recordWriter.replaceChunks(docId, kbId,
                    List.of(imageBlock(fullPage, DecorativeImageRule.MAX_AREA + 1, "md5-fp-" + i)));
        }

        assertThat(markedRowsFor(fullPage))
                .as("🔴 全扫描件上「同一包围盒出现在多页/多篇」恒成立（`C2` 补探实测），"
                        + "不排除它就是**把扫描件的整页内容判成装饰图** —— "
                        + "而代价是那部分内容**搜不到了**（误报比漏报更伤）")
                .isZero();
    }

    @Test
    @DisplayName("🔴 负向锚③：**没有哈希**的图片不参与判定（NULL 不当成「相同」）")
    void imagesWithoutPhashAreNotCompared() {
        for (int i = 0; i < DecorativeImageRule.MIN_DOCS; i++) {
            long docId = insertEmptyDoc("无哈希 " + i);
            recordWriter.replaceChunks(docId, kbId,
                    List.of(imageBlock(null, LOGO_AREA, "md5-null-" + i)));
        }

        assertThat(jdbc.queryForObject(
                "select count(*) from sys_chunk where kb_id = ? and chunk_type = 'IMAGE' "
                        + "and is_decorative = 1", Integer.class, kbId))
                .as("一批 NULL 互相之间**不是**「相同」—— 当成相同会让所有旧图互相命中")
                .isZero();
    }

    @Test
    @DisplayName("**重编码过**的同一张图也算同一张（差 1 位 ⇒ 精确匹配抓不到，这正是 image_phash 存在的理由）")
    void reencodedCopyCountsAsTheSameImage() {
        long first = insertEmptyDoc("原件");
        recordWriter.replaceChunks(first, kbId, List.of(imageBlock(LOGO, LOGO_AREA, "md5-a")));
        for (int i = 1; i < DecorativeImageRule.MIN_DOCS; i++) {
            long docId = insertEmptyDoc("重编码 " + i);
            recordWriter.replaceChunks(docId, kbId,
                    List.of(imageBlock(LOGO_REENCODED, LOGO_AREA, "md5-b" + i)));
        }

        assertThat(markedRowsFor(LOGO_REENCODED))
                .as("哈希只差 1 位 ⇒ 汉明距离够近，算同一张。"
                        + "⚠️ 这条同时印证了 LSH 取候选没把这对漏掉（它们前三段完全相同）")
                .isPositive();
    }

    @Test
    @DisplayName("被判装饰图之后**不进召回池**（判定的全部意义就在这一条）")
    void markedImageIsKeptOutOfTheRecallPool() {
        long last = 0;
        for (int i = 0; i < DecorativeImageRule.MIN_DOCS; i++) {
            last = insertEmptyDoc("手册 " + i);
            recordWriter.replaceChunks(last, kbId,
                    List.of(imageBlock(LOGO, LOGO_AREA, "md5-" + i)));
        }

        List<VectorChunk> chunks = chunkReader.readByDocId(last);
        VectorChunk image = chunks.stream()
                .filter(c -> "IMAGE".equals(c.blockType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没读到图片块"));

        assertThat(image.isDecorative()).as("读取层要照实读出这一列（不是顺手筛掉）").isTrue();
        assertThat(image.inRecallPool())
                .as("🔴 这才是判定的目的：它不进向量召回池，不占 top-k 的名额")
                .isFalse();
    }

    // ==================================================================

    /** 有多少行的 phash 是它、且被判成了装饰图。 */
    private int markedRowsFor(String phash) {
        Integer n = jdbc.queryForObject(
                "select count(*) from sys_chunk where kb_id = ? and image_phash = ? and is_decorative = 1",
                Integer.class, kbId, phash);
        return n == null ? 0 : n;
    }

    /**
     * 造一个图片块。
     *
     * <p>⚠️ `phash` 传 {@code null} 时它同时是"没有哈希"那条负向锚的输入 ——
     * 判定侧必须**两侧都非空才比较**，否则一批 NULL 会互相命中。
     */
    private static Block imageBlock(String phash, double area, String md5) {
        double side = Math.sqrt(area);
        ImageInfo info = new ImageInfo(1, new ImageInfo.Box(0, 0, side, side), null,
                "images/probe/" + md5 + ".jpg", md5, null, phash);
        return new Block("图 " + md5, BlockType.IMAGE, "1. 总览", 1, 1, null, null, info);
    }
}
