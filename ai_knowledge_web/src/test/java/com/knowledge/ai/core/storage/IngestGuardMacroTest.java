package com.knowledge.ai.core.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 宏检测（`T-O04` 步 4）—— 纯函数，喂字节。
 *
 * <h3>🔴 `O4` 文档说「Tika 可直接给出 `hasMacro`」—— 那是错的</h3>
 * <p>本批开工时查过：Tika 3.2.2 的 `tika-core` 与 `tika-parsers-standard-package` 里
 * **没有任何宏相关的类或元数据常量**（`Metadata` 也没有 `HAS_MACRO`）。
 * 这与该文档同族的偏差已经登记过三次，这是第四次（见计划 §1 的开工条件检查）。
 * 所以判定是**本仓自己实现**的，两条路各是一个格式层面的硬事实：
 * <ul>
 *   <li>OOXML（zip）：里面有没有 `vbaProject.bin` 条目；</li>
 *   <li>OLE2：扫 UTF-16LE 的 `VBA` 标记。</li>
 * </ul>
 * 正因如此，本类**必须**逐条钉住 —— 没有第三方实现替我背书。
 *
 * <h3>三态：`TRUE` / `FALSE` / `null`（判不了）</h3>
 * <p>`null` 与 `FALSE` 必须能区分：`has_macro = 0` 在页面上是「确认没有宏」，
 * 而"没检测过"是另一回事。把两者压成一个，用户会以为系统替他看过一遍了。
 */
class IngestGuardMacroTest {

    private final IngestGuard guard = new IngestGuard();

    // ==================================================================
    // OOXML（zip 容器）
    // ==================================================================

    @Test
    @DisplayName("docx 里有 vbaProject.bin ⇒ 含宏")
    void docxWithVbaProjectHasMacro() throws Exception {
        byte[] zip = zipWith("word/document.xml", "x", "word/vbaProject.bin", "MZ...");

        assertThat(guard.hasMacro(file("报告.docx", zip), "docx")).isTrue();
    }

    @Test
    @DisplayName("负向锚：docx 里没有 vbaProject.bin ⇒ **检测过、没有宏**（不是「判不了」）")
    void docxWithoutVbaProjectIsFalseNotUnknown() throws Exception {
        byte[] zip = zipWith("word/document.xml", "x", "[Content_Types].xml", "y");

        assertThat(guard.hasMacro(file("报告.docx", zip), "docx"))
                .as("能打开、能列出条目 ⇒ 这是一个可靠的『没有宏』，不是『判不了』")
                .isFalse();
    }

    @Test
    @DisplayName("负向锚：条目名大小写不敏感（`VBAPROJECT.BIN` 也要认出来）")
    void entryNameMatchIsCaseInsensitive() throws Exception {
        byte[] zip = zipWith("word/VBAPROJECT.BIN", "MZ...");

        assertThat(guard.hasMacro(file("报告.docx", zip), "docx")).isTrue();
    }

    // ==================================================================
    // OLE2（老式 .doc / .xls / .ppt）
    // ==================================================================

    @Test
    @DisplayName("老式 .doc 的 OLE 目录里有 UTF-16LE 的 `VBA` ⇒ 含宏")
    void oleWithVbaStorageHasMacro() {
        byte[] doc = withUtf16Marker("VBA", 4096);

        assertThat(guard.hasMacro(file("旧报表.doc", doc), "doc")).isTrue();
    }

    @Test
    @DisplayName("负向锚：只出现 ASCII 的 `VBA`（不是 UTF-16LE）⇒ **不算**含宏")
    void asciiOnlyVbaIsNotMacro() {
        // ⚠️ 这条钉的是"为什么要带上那三个 0x00"：只找 ASCII 的 VBA 会在压缩流、
        // 随机字节里大量误报，而本仓的取舍是「宁可多提示」—— 但**多到每篇都提示
        // 就等于没有提示**。带上 UTF-16 的零字节才把误报压到可用
        byte[] doc = "....VBA....".getBytes(StandardCharsets.ISO_8859_1);

        assertThat(guard.hasMacro(file("旧报表.doc", doc), "doc")).isFalse();
    }

    @Test
    @DisplayName("负向锚：VBA 标记跨在 8KB 边界上也要认出来（流式滑窗不能丢）")
    void markerAcrossBufferBoundaryIsFound() {
        // 5 字节前缀让 "V" 落在奇数偏移上 —— 那些"只在高字节位推进"的写法会漏掉它
        byte[] doc = withUtf16Marker("VBA", 5);

        assertThat(guard.hasMacro(file("旧报表.doc", doc), "doc"))
                .as("滑窗接缝处漏匹配，表现是「有的带宏文档检测不出来」—— 而它不报错")
                .isTrue();
    }

    // ==================================================================
    // 结构上不可能带宏的格式
    // ==================================================================

    @Test
    @DisplayName("txt / md / pdf：结构上不可能带宏 ⇒ FALSE（是「确认没有」，不是「判不了」）")
    void plainFormatsAreFalseNotUnknown() {
        byte[] body = "这是一份纯文本。VBA".getBytes(StandardCharsets.UTF_8);

        for (String ext : new String[]{"txt", "md", "pdf"}) {
            assertThat(guard.hasMacro(file("文件." + ext, body), ext))
                    .as("%s 结构上不可能带宏 —— 那是**确定**的『没有』", ext)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("坏 zip（读不出条目）⇒ null（判不了），而不是 FALSE")
    void brokenZipIsUnknownNotFalse() {
        byte[] notAZip = "this is not a zip at all".getBytes(StandardCharsets.UTF_8);

        assertThat(guard.hasMacro(file("坏的.docx", notAZip), "docx"))
                .as("判不了与『确认没有』必须能区分 —— 压成一个会让页面上显示成「确认无宏」")
                .isNull();
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, null, bytes);
    }

    /** 造一个 zip：`pairs` 是 name/content 交替的条目。 */
    private static byte[] zipWith(String... pairs) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                zip.putNextEntry(new ZipEntry(pairs[i]));
                zip.write(pairs[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** 造一段字节：{@code prefix} 个填充字节，然后是 UTF-16LE 的 {@code text}，再补零。 */
    private static byte[] withUtf16Marker(String text, int prefix) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < prefix; i++) {
            out.write(0x41);
        }
        out.writeBytes(text.getBytes(StandardCharsets.UTF_16LE));
        for (int i = 0; i < 64; i++) {
            out.write(0x00);
        }
        return out.toByteArray();
    }
}
