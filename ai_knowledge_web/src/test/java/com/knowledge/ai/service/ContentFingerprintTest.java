package com.knowledge.ai.service;

import com.knowledge.ai.core.text.ContentFingerprint;
import com.knowledge.ai.pipeline.retrieve.RetrievalFlowSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容级近重复的门禁用例（`T-O09` 判据 1/2 · `S5-M07-A` 步 12）。
 *
 * <h3>判据 1：近重复被识别（**字节不同**）</h3>
 * <p>同一份内容换个容器（DOCX → PDF）、换个工具另存、或者改一句话存成 v2 ——
 * 它们的 **md5 全都不同**，所以上传侧的既有去重（`kb_id + owner_id + md5`）一条都拦不住。
 * 本用例刻意让两篇文档的 **md5 与标题都不同**，只让**规范化文本**相同，
 * 以此证明"认出来的依据是内容指纹，不是字节"。
 *
 * <h3>判据 2：真不同内容**不误报**</h3>
 * <p>误报的代价比漏报大得多：用户被告知"与《X》内容高度相同"而其实不同，
 * 就会不再相信这个提示，于是这个功能等于不存在。所以本类里**不误报**的用例比
 * **能识别**的用例还多。
 *
 * <p>复用检索流程用例的夹具（它提供探针库、文档行与收尾清理）。
 * 本类**不碰 Milvus**，只验指纹与识别。
 */
class ContentFingerprintTest extends RetrievalFlowSupport {

    @Autowired
    private ContentDuplicateDetector detector;

    @Autowired
    private DocRecordWriter recordWriter;

    /** 一段"内容相同、字节不同"的正文。 */
    private static final String CONTRACT_V2 =
            "第三条 交付与验收：乙方应当在合同生效后三十个工作日内完成交付，"
                    + "甲方在收到交付物后十个工作日内完成验收并出具书面意见。";

    @BeforeEach
    void setUp() {
        resetProbeState();
        kbId = insertKb("csha-probe");
    }

    @AfterEach
    void tearDown() {
        cleanProbeData();
    }

    // ==================== 指纹本身 ====================

    @Test
    @DisplayName("指纹口径：空白差异不影响，大小写与全角半角**不归一**（宁可漏报不可误报）")
    void fingerprintNormalizationIsDeliberatelyNarrow() {
        assertThat(ContentFingerprint.of("a b")).isEqualTo(ContentFingerprint.of("a   b"));
        assertThat(ContentFingerprint.of("a b")).isEqualTo(ContentFingerprint.of("\n a\tb \n"));
        assertThat(ContentFingerprint.of("　全角空格　")).isEqualTo(ContentFingerprint.of("全角空格"));
        // 大小写**不**归一：`Milvus` 与 `milvus` 是两段不同的文本，不该被判成重复
        assertThat(ContentFingerprint.of("Milvus 扩容")).isNotEqualTo(ContentFingerprint.of("milvus 扩容"));
        // 空 / 空白没有指纹 —— 给它一个固定指纹会让所有空块互相命中
        assertThat(ContentFingerprint.of(null)).isNull();
        assertThat(ContentFingerprint.of("   ")).isNull();
        assertThat(ContentFingerprint.of("x")).hasSize(ContentFingerprint.LENGTH);
    }

    @Test
    @DisplayName("两个 null 指纹**不算**相同（本批之前的旧数据两侧都没记，不是「内容一样」）")
    void nullFingerprintsNeverMatch() {
        assertThat(ContentFingerprint.same(null, null)).isFalse();
        assertThat(ContentFingerprint.same(null, "abc")).isFalse();
        assertThat(ContentFingerprint.same("abc", "abc")).isTrue();
    }

    // ==================== 判据 1 ====================

    @Test
    @DisplayName("判据 1 · 字节不同、内容相同 → 提示「内容高度相同」并给出已有 docId，**不阻断**")
    void nearDuplicateWithDifferentBytesIsDetected() {
        // 文档甲：模拟 DOCX 原件
        long docA = insertEmptyDoc("供货合同");
        recordWriter.markContentFingerprint(docA, ContentFingerprint.of(CONTRACT_V2));

        // 文档乙：模拟转存后的 PDF —— **md5 与标题都不同**（否则验的还是字节级去重）
        long docB = insertEmptyDoc("供货合同（转存）");
        String fingerprintB = ContentFingerprint.of("  " + CONTRACT_V2 + "  ");
        recordWriter.markContentFingerprint(docB, fingerprintB);

        assertThat(fingerprintB)
                .as("两篇的字节不同（md5 不同）但指纹必须相同 —— 这是本用例的前提")
                .isEqualTo(ContentFingerprint.of(CONTRACT_V2));
        String md5A = jdbc.queryForObject(
                "select md5 from sys_document where id = ?", String.class, docA);
        String md5B = jdbc.queryForObject(
                "select md5 from sys_document where id = ?", String.class, docB);
        assertThat(md5A).isNotEqualTo(md5B);

        ContentDuplicateDetector.Hint hint = detector.detect(kbId, docB, fingerprintB);

        assertThat(hint.duplicated()).isTrue();
        assertThat(hint.docId()).as("必须给出**已有的那一篇**是哪个").isEqualTo(docA);
        assertThat(hint.docTitle()).isEqualTo("供货合同");
        assertThat(hint.message())
                .as("提示要说人话：给出标题，而不是一个布尔值")
                .contains("供货合同")
                .contains("内容高度相同");
    }

    @Test
    @DisplayName("判据 1 · 「不阻断」体现在接口形状上：提示只是一个返回值，没有任何拒绝语义")
    void hintIsAdvisoryOnly() {
        long docA = insertEmptyDoc("制度 V1");
        recordWriter.markContentFingerprint(docA, ContentFingerprint.of(CONTRACT_V2));
        long docB = insertEmptyDoc("制度 V2");
        recordWriter.markContentFingerprint(docB, ContentFingerprint.of(CONTRACT_V2));

        ContentDuplicateDetector.Hint hint = detector.detect(kbId, docB, ContentFingerprint.of(CONTRACT_V2));

        // 提示对象里**没有** blocking / reject / allow 之类的字段 —— 调用方无从"据此拒绝"
        assertThat(hint.getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactlyInAnyOrder("duplicated", "docId", "docTitle", "message");
        // 而且两篇都实实在在地在库里 —— 没有任何一步把它们拦下来
        assertThat(jdbc.queryForObject(
                "select count(*) from sys_document where kb_id = ?", Integer.class, kbId)).isEqualTo(2);
    }

    // ==================== 判据 2 ====================

    @Test
    @DisplayName("判据 2 · 同模板不同内容的合同**不**提示重复")
    void genuinelyDifferentContractsAreNotFlagged() {
        long docA = insertEmptyDoc("合同-甲");
        recordWriter.markContentFingerprint(docA, ContentFingerprint.of(
                CONTRACT_V2 + "第四条 违约责任：逾期交付按日万分之五计罚。"));
        long docB = insertEmptyDoc("合同-乙");
        // 只差最后一句 —— 但指纹是**全等**比较，差一个字就是两份
        String fingerprintB = ContentFingerprint.of(
                CONTRACT_V2 + "第四条 违约责任：逾期交付按日万分之三计罚。");
        recordWriter.markContentFingerprint(docB, fingerprintB);

        ContentDuplicateDetector.Hint hint = detector.detect(kbId, docB, fingerprintB);

        assertThat(hint.duplicated()).as("差一个字就是两份合同，不该报重复").isFalse();
        assertThat(hint.docId()).isNull();
        assertThat(hint.message()).isEmpty();
    }

    @Test
    @DisplayName("判据 2 · 跨库的同内容**不**算重复（两个部门各自维护同一份国标是两件事）")
    void sameContentInAnotherKbIsNotADuplicate() {
        long docA = insertEmptyDoc("国标-本部");
        recordWriter.markContentFingerprint(docA, ContentFingerprint.of(CONTRACT_V2));

        long otherKb = insertKb("csha-probe-other");
        long savedKb = kbId;
        kbId = otherKb;
        long docB = insertEmptyDoc("国标-分部");
        recordWriter.markContentFingerprint(docB, ContentFingerprint.of(CONTRACT_V2));
        kbId = savedKb;

        ContentDuplicateDetector.Hint hint = detector.detect(otherKb, docB, ContentFingerprint.of(CONTRACT_V2));

        assertThat(hint.duplicated()).as("限定同库 —— 跨库内容相同不是重复").isFalse();
    }

    @Test
    @DisplayName("判据 2 · 软删的那一篇不算「已存在」")
    void softDeletedDocumentIsNotACandidate() {
        long docA = insertEmptyDoc("已删除的旧版");
        recordWriter.markContentFingerprint(docA, ContentFingerprint.of(CONTRACT_V2));
        jdbc.update("update sys_document set deleted_at = now() where id = ?", docA);

        long docB = insertEmptyDoc("新版");
        ContentDuplicateDetector.Hint hint = detector.detect(kbId, docB, ContentFingerprint.of(CONTRACT_V2));

        assertThat(hint.duplicated())
                .as("用户已经删掉的那一篇不该再拿来提示 —— 那是死链")
                .isFalse();
    }

    @Test
    @DisplayName("指纹为空（旧数据 / 空文档）时不报重复")
    void nullFingerprintYieldsNoHint() {
        insertEmptyDoc("没有指纹的旧文档");
        long docB = insertEmptyDoc("新文档");

        assertThat(detector.detect(kbId, docB, null).duplicated()).isFalse();
        assertThat(detector.detect(kbId, docB, "   ").duplicated()).isFalse();
    }

    @Test
    @DisplayName("自己不和自己比（同一篇重复调用检测不该报重复）")
    void documentDoesNotMatchItself() {
        long docA = insertEmptyDoc("自己");
        String fingerprint = ContentFingerprint.of(CONTRACT_V2);
        recordWriter.markContentFingerprint(docA, fingerprint);

        assertThat(detector.detect(kbId, docA, fingerprint).duplicated()).isFalse();
    }
}
