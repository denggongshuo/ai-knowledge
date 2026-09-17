package com.knowledge.ai.core.storage;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * `T-O04` 入库准入加固的取证（判据 1 / 2 / 3 / 5 / 7）。
 *
 * <p><b>本类不落库、不联网</b> —— {@link IngestGuard} 是纯函数式的判定，可以直接喂字节。
 * 与 `O4` 判据 4（恶意样本被拦）与判据 6（未部署 ClamAV 的降级）无关，那两条**挂 `S4-E`**。
 *
 * <h3>两类证据，分得很清</h3>
 * <ul>
 *   <li><b>真样本</b>：{@code docs/测试文件/} 的每一份都过一遍准入（判据 2 的可用部分 + 判据 7）。
 *       为什么必须真跑：MIME 映射表里那些值**是从这些样本上测出来的**，不是抄文档来的 ——
 *       换 Tika 版本、换样本，这张表就可能要改，而只有真跑能发现。</li>
 *   <li><b>合成件</b>：伪装扩展名 / ZIP 炸弹 / 加密 PDF 三种**有害输入**用构造的字节验。
 *       真样本里没有有害文件，只能用合成的 —— 这一点与 S4-C2 的"页眉页脚正向证据靠合成、
 *       反向证据才是真的"同一处境，写在这里免得后来者以为漏了。</li>
 * </ul>
 */
class IngestGuardTest {

    private static final Path SAMPLE_DIR = Path.of("..", "docs", "测试文件").toAbsolutePath().normalize();

    private final IngestGuard guard = new IngestGuard();

    // ==================== 判据 2 / 7：真样本全放行 ====================

    @Test
    @DisplayName("判据 2 部分 + 判据 7：真样本全部通过准入（含 6 份 .md —— Tika 报 text/x-web-markdown，映射表必须覆盖）")
    void realSamplesPassAdmission() throws IOException {
        List<Path> samples;
        try (Stream<Path> files = Files.list(SAMPLE_DIR)) {
            samples = files.filter(Files::isRegularFile).sorted().toList();
        }
        assertThat(samples)
                .as("真样本目录读不到内容 —— 本用例会变成空断言（那种「全绿」最危险）")
                .isNotEmpty();

        int mdCount = 0;
        for (Path sample : samples) {
            String name = sample.getFileName().toString();
            if (name.endsWith(".md")) {
                mdCount++;
            }
            MockMultipartFile file = new MockMultipartFile("file", name, null, Files.readAllBytes(sample));

            // ⚠️ 防呆：先确认这份样本**确实探测得出具体类型**。
            // 不确认的话，若 Tika 返回的是 octet-stream 之类的模糊类型，checkMime 会**直接放行**
            // —— 那么"全部通过"就变成了空断言：交叉校验表根本没被验过，改坏了也不会红。
            // （同族教训见 `G4-6` 的"断言前先确认 childCount > 0 —— 空集上那三条恒成立"。）
            String detected = guard.detect(file);
            assertThat(IngestGuard.isAmbiguousMime(detected))
                    .as("真样本「%s」探测出的类型是模糊值 [%s] —— 它会让 checkMime 直接放行，"
                            + "本用例因此变成空断言。要么补探测，要么这条样本确实无法交叉校验", name, detected)
                    .isFalse();

            // 逐份报名字，失败时一眼看出是哪一份 —— 批量断言最怕"3 份里坏 1 份但只说总数"
            assertThatCode(() -> guard.check(file))
                    .as("真样本「%s」被准入拒了（探测为 %s）—— 误拒比误放更伤，改映射表前先看这个实际值",
                            name, detected)
                    .doesNotThrowAnyException();
        }
        assertThat(mdCount).as(".md 样本数（判据 7 点名要覆盖的那一类）").isGreaterThanOrEqualTo(6);
    }

    // ==================== 判据 1：伪装扩展名 ====================

    @Test
    @DisplayName("判据 1：把 .zip 改名成 .pdf 上传 → FILE_TYPE_MISMATCH（不是靠解析阶段「恰好没出事」）")
    void zipRenamedToPdfIsRejected() throws IOException {
        MockMultipartFile disguised = new MockMultipartFile("file", "报告.pdf", null, smallZip());

        assertThatThrownBy(() -> guard.check(disguised))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_TYPE_MISMATCH))
                .hasMessageContaining(".pdf")
                .hasMessageContaining("请上传真实格式的文件");
    }

    @Test
    @DisplayName("判据 1 反向：模糊类型（octet-stream）放行 —— 误拒比误放更伤")
    void ambiguousMimeIsAllowed() {
        // 直接走纯函数路径：文件头探测不出具体类型时，不该判成"伪装"
        assertThatCode(() -> guard.checkMime("docx", "application/octet-stream"))
                .doesNotThrowAnyException();
    }

    // ==================== 判据 3：ZIP 炸弹 ====================

    @Test
    @DisplayName("判据 3：ZIP 条目数超限 → FILE_BOMB_SUSPECTED（且不解压任何内容）")
    void zipBombByEntryCountIsRejected() throws IOException {
        // 用"条目数"造炸弹而不是"解压体积"：后者要真写出 1GB 才能让声明值变大（慢且吃盘），
        // 而两者的判定路径是同一条（都在中央目录的循环里），覆盖面一样。
        MockMultipartFile bomb = new MockMultipartFile("file", "bomb.docx", null, zipWithManyEntries(5_001));

        assertThatThrownBy(() -> guard.check(bomb))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_BOMB_SUSPECTED))
                .hasMessageContaining("已拒绝")
                // 消息必须可行动（G4-3：不许只说"失败"两字）
                .hasMessageContaining("重新导出");
    }

    @Test
    @DisplayName("判据 3 反向：正常 docx 的结构不被误判为炸弹")
    void normalDocxIsNotABomb() throws IOException {
        MockMultipartFile normal = new MockMultipartFile("file", "正常.docx", null, smallZip());
        assertThatCode(() -> guard.checkZipBomb(normal)).doesNotThrowAnyException();
    }

    // ==================== 判据 5：加密 PDF ====================

    @Test
    @DisplayName("判据 5：加密 PDF 的提示**不同于**扫描件 —— 提到「加密」，不是「未提取到可复制文本」")
    void encryptedPdfGetsItsOwnMessage() {
        MockMultipartFile encrypted = new MockMultipartFile("file", "加密.pdf", null, minimalEncryptedPdf());

        assertThatThrownBy(() -> guard.check(encrypted))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FILE_ENCRYPTED))
                .hasMessageContaining("加密")
                .hasMessageContaining("解除密码")
                // 关键：不能与扫描件（无文本层）共用一套话术 —— 两者的下一步动作完全不同
                .hasMessageNotContaining("未提取到可复制文本");
    }

    // ==================== 映射表自身的完整性 ====================

    @ParameterizedTest
    @DisplayName("9 种白名单扩展名**每一种**都有映射（其中 4 种没有真样本，只能靠这条纯函数路径覆盖）")
    @CsvSource({
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md"
    })
    void everyAllowedExtensionHasAMapping(String ext) {
        assertThat(IngestGuard.ACCEPTED_MIMES)
                .as("扩展名 .%s 在白名单里却没有 MIME 映射 —— 它会被 checkMime 判成「不符」而全部误拒", ext)
                .containsKey(ext);
    }

    // ==================== 构造件 ====================

    /** 一个合法的、很小的 ZIP（模拟正常的 docx/xlsx 容器结构）。 */
    private static byte[] smallZip() throws IOException {
        return zipWithManyEntries(3);
    }

    /** 造一个含 {@code n} 个条目的 ZIP（n 大时就是"条目数炸弹"）。 */
    private static byte[] zipWithManyEntries(int n) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < n; i++) {
                zip.putNextEntry(new ZipEntry("entry-" + i + ".xml"));
                zip.write(("x").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /**
     * 一个**合成的**"加密 PDF"：结构最小化，只在尾部 trailer 里带 {@code /Encrypt}。
     *
     * <p>为什么是合成的：真样本里没有加密件（`docs/测试文件/` 全是能正常打开的文档）。
     * 本用例验的是"**标记被认出来之后走哪条分支**"，不是"能不能解开一个真实的加密 PDF" ——
     * 后者要 PDFBox 参与，而准入阶段**刻意不解析**（那正是炸弹与畸形文件要你做的事）。
     */
    private static byte[] minimalEncryptedPdf() {
        String pdf = """
                %PDF-1.7
                1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
                2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj
                3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj
                trailer<</Size 4/Root 1 0 R/Encrypt 5 0 R/ID[<01><01>]>>
                startxref
                0
                %%EOF
                """;
        return pdf.getBytes(StandardCharsets.ISO_8859_1);
    }
}
