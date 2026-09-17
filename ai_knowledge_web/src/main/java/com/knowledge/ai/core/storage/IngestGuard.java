package com.knowledge.ai.core.storage;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 入库准入加固（`T-O04` · `优化/O4` 的同步四道）。
 *
 * <h3>{@code DocStorage#validate} 原有四道一条不动，本类只**加**</h3>
 * <p>原有：空文件（`10030`）· 100MB（`10035`）· 扩展名白名单 9 种（`10034`）· 对象名清洗。
 * 本类补的是**按内容判**的那几道 —— 原来只有"闸"（看名字），没有"防"（看内容）。
 *
 * <h3>三道（第四道见下）</h3>
 * <ol>
 *   <li><b>MIME 交叉校验</b> —— {@code Tika.detect} 按**文件头**判真实类型，与扩展名比对。
 *       原来把 {@code evil.zip} 改名 {@code 报告.pdf} 能过去，靠的是"解析阶段 Tika 会识别"
 *       —— 那是**恰好没出事**，不是设计上挡住了（`O4` §2 第 1 条的原话）。</li>
 *   <li><b>ZIP 炸弹限额</b> —— 9 种格式里 docx/xlsx/pptx 全是 ZIP 容器，而 100MB 上限
 *       **完全不约束解压后的体积**（解压 10⁴ 倍是常见手法）。</li>
 *   <li><b>加密检测</b> —— 加密件在**准入阶段**拦掉，比"建行后解析失败"干净：
 *       列表里不会出现一条假文档（同 S4-B 判据 6 空文件的处置方式）。</li>
 * </ol>
 *
 * <h3>⚠️ 本类**不做**的两件事（刻意，见计划 §3.3 与 §9）</h3>
 * <ul>
 *   <li><b>恶意扫描（ClamAV）</b> —— `O4` 自己把它设计成**异步**、进 `T-O01` 的 worker 队列，
 *       而 `T-O01` 排在 `S4-E`（本批之后）。仓促在本批起一个自建线程池，等于拆架构时再重接一次。
 *       故 `O4` 判据 4（恶意样本被拦）与判据 6（未部署降级）本批**挂起**。</li>
 *   <li><b>宏标记</b> —— `O4` §4 步 4 要求"放行 + 标记 {@code hasMacro}，下载/预览页提示"。
 *       标记需要落库（新列）**加上**页面展示，本批没有承接它的地方。**只检测不落地等于没做**，
 *       故不做（登记在 §9）。</li>
 * </ul>
 *
 * <h3>误拒比误放更伤：模糊类型一律放行</h3>
 * <p>有些工具生成的 docx 文件头不规范。所以只对**明显不符**（ZIP 伪装成 PDF、图片伪装成 Office）
 * 拒收；对模糊类型（{@code application/octet-stream} 等）**放行并记日志** ——
 * 与 `O4` §6 的第一条风险对策一致。
 */
@Slf4j
@Component
public class IngestGuard {

    /** 解压后总字节上限。与 100MB 输入上限的关系：**输入上限管"传输"，本限额管"处理"**，不可互替。 */
    private static final long MAX_UNCOMPRESSED_BYTES = 1024L * 1024 * 1024;

    /** ZIP 条目数上限。 */
    private static final int MAX_ZIP_ENTRIES = 5_000;

    /** MIME 探测读多少字节（Tika 自身只看文件头，给足余量）。 */
    private static final int MIME_PROBE_BYTES = 8 * 1024;

    /** 加密探测的头/尾各读多少字节。PDF 的 {@code /Encrypt} 在**尾部** trailer 里，头尾都要看。 */
    private static final int ENCRYPT_PROBE_BYTES = 8 * 1024;

    /** ZIP 容器型扩展名（这三种 + odt 的解析产物是压缩包，要过炸弹限额）。 */
    private static final Set<String> ZIP_CONTAINER_EXT = Set.of("docx", "xlsx", "pptx");

    /**
     * 扩展名 → **可接受的** MIME 集合。
     *
     * <p>取值全部来自**实测**（{@code IngestGuardTest#realSamplesPassAdmission} 对
     * {@code docs/测试文件/} 的真样本逐个跑）—— 不是照抄 Tika 文档。
     * 所以这张表是"跑出来的"，改它之前先跑那个用例看实际探测值。
     *
     * <p>⚠️ 一个扩展名对应多个 MIME 是**常态**不是异常：Office 有两种容器
     * （老的 CFB、新的 OOXML），而 Tika 的探测器在**不解析**的前提下通常只给到容器级
     * （{@code application/zip} / {@code application/x-tika-ooxml}），不会给到具体子类型。
     */
    static final Map<String, Set<String>> ACCEPTED_MIMES = Map.of(
            "pdf", Set.of("application/pdf"),
            "doc", Set.of("application/msword", "application/x-tika-msoffice",
                    "application/vnd.ms-office", "application/x-tika-msoffice-encrypted"),
            "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/x-tika-ooxml", "application/zip", "application/x-tika-ooxml-protected"),
            "xls", Set.of("application/vnd.ms-excel", "application/x-tika-msoffice",
                    "application/vnd.ms-office", "application/x-tika-msoffice-encrypted"),
            "xlsx", Set.of("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/x-tika-ooxml", "application/zip", "application/x-tika-ooxml-protected"),
            "ppt", Set.of("application/vnd.ms-powerpoint", "application/x-tika-msoffice",
                    "application/vnd.ms-office", "application/x-tika-msoffice-encrypted"),
            "pptx", Set.of("application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/x-tika-ooxml", "application/zip", "application/x-tika-ooxml-protected"),
            // .md 会被 Tika 报成 text/x-web-markdown（S4-B §3.4 假设 2 已实测），映射表必须覆盖它
            "md", Set.of("text/x-web-markdown", "text/markdown", "text/plain"),
            "txt", Set.of("text/plain"));

    /**
     * "模糊" MIME —— 探测不出具体类型时**放行**。
     *
     * <p>误拒比误放更伤：把一份合法的、只是文件头不规范的文件判成"伪装"，用户无从下手。
     */
    private static final Set<String> AMBIGUOUS_MIMES = Set.of(
            "application/octet-stream", "application/x-unknown", "");

    /** 加密的 Office（CFB 容器 + 加密标志）：扩展名是 docx/xlsx/pptx 但探测到它就说明是加密件。 */
    private static final Set<String> ENCRYPTED_OFFICE_MIMES = Set.of(
            "application/x-tika-msoffice-encrypted", "application/x-tika-ooxml-protected");

    private final Tika tika = new Tika();

    /**
     * 准入检查（在 {@code DocStorage#validate} 的原有四道**之后**调用）。
     *
     * @throws BizException 不合规时抛；消息必须**可执行**（`G4-3`：不许只说"失败"两字）
     */
    public void check(org.springframework.web.multipart.MultipartFile file) {
        String ext = DocStorage.extensionOf(file.getOriginalFilename());
        String detected = detect(file);
        String normalized = detected == null ? "" : detected.toLowerCase(Locale.ROOT);

        // 顺序有讲究：加密要先判 —— 加密的 Office 探测出来是 CFB 容器，
        // 若先走 MIME 比对，用户会收到"疑似伪装"，而真实原因是"文档加密了"，
        // 两者的下一步动作完全不同（前者要换文件，后者只要解除密码）。
        if (ENCRYPTED_OFFICE_MIMES.contains(normalized) || looksEncryptedPdf(file, ext)) {
            throw BizException.of(ErrorCode.FILE_ENCRYPTED,
                    "该文档已加密，无法读取内容；请解除密码后重新上传"
                            + "（加密件与「扫描件」不同：扫描件是能打开但没文本层，本件是打不开）");
        }

        checkMime(ext, normalized);

        if (ZIP_CONTAINER_EXT.contains(ext)) {
            checkZipBomb(file);
        }
    }

    /** 按文件头探测真实 MIME。探测失败不阻断 —— 那是"判不了"，不是"不合规"。 */
    String detect(org.springframework.web.multipart.MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return tika.detect(in, file.getOriginalFilename());
        } catch (IOException ex) {
            log.warn("MIME 探测失败（放行，交由解析阶段裁决）：name={} 原因={}",
                    file.getOriginalFilename(), ex.getMessage());
            return "";
        }
    }

    /**
     * 扩展名与真实类型交叉校验。
     *
     * <p>包内可见以便单测直接喂 (扩展名, MIME) 组合 —— 9 种格式里有 4 种**没有真样本**
     * （见 §9），那几种只能靠这条纯函数路径覆盖。
     */
    void checkMime(String ext, String detectedMime) {
        if (isAmbiguousMime(detectedMime)) {
            log.debug("MIME 探测结果为模糊类型，放行：ext={} detected={}", ext, detectedMime);
            return;
        }
        Set<String> accepted = ACCEPTED_MIMES.get(ext);
        if (accepted == null || accepted.contains(detectedMime)) {
            return;
        }
        throw BizException.of(ErrorCode.FILE_TYPE_MISMATCH,
                "文件内容与扩展名不符：扩展名是「." + ext + "」，但内容实际是「" + detectedMime
                        + "」，疑似改了扩展名。请上传真实格式的文件");
    }

    /**
     * 该 MIME 是否属于"探测不出具体类型"。
     *
     * <p>包内可见是**为了能被测试断言**：真样本用例必须先确认"探测得出的确不是模糊值"，
     * 否则"全部通过"会变成空断言（模糊值会被 {@link #checkMime} 直接放行）。
     */
    static boolean isAmbiguousMime(String detectedMime) {
        return AMBIGUOUS_MIMES.contains(detectedMime == null ? "" : detectedMime.toLowerCase(Locale.ROOT));
    }

    /**
     * ZIP 炸弹限额：**预扫 ZIP 中央目录**，按声明的解压后大小求和，**一个字节都不解压**。
     *
     * <p>为什么必须走中央目录而不是 {@code ZipInputStream}：后者读的是**本地头**，
     * 而 {@code ZipOutputStream} 产出的条目在本地头里普遍把 size 写成 0（真值放在数据描述符里），
     * 于是 {@code getSize()} 返回 {@code -1}。那样兜底就只能"读出来数"——
     * 而**读出来数正是炸弹要你做的事**（10KB 声明成 4GB，你为了数清楚得先展开 4GB）。
     * 中央目录在文件尾部，记录了每个条目**权威的**未压缩大小，读它是 O(条目数) 的小量 I/O。
     * （`优化/O4` §4 步 2 说的"预扫 ZIP 中央目录"就是这个。）
     *
     * <p>{@code ZipFile} 需要随机访问，所以先落一个临时文件 —— 代价是一次磁盘写入，
     * 换来"绝不解压"。临时文件在 {@code finally} 里删，路径由 JDK 生成（不进业务目录）。
     */
    void checkZipBomb(org.springframework.web.multipart.MultipartFile file) {
        Path tmp = null;
        try {
            tmp = Files.createTempFile("kb-zipscan-", ".bin");
            file.transferTo(tmp);
            long total = 0;
            int entries = 0;
            try (ZipFile zip = new ZipFile(tmp.toFile())) {
                Enumeration<? extends ZipEntry> it = zip.entries();
                while (it.hasMoreElements()) {
                    ZipEntry entry = it.nextElement();
                    if (++entries > MAX_ZIP_ENTRIES) {
                        throw bomb(entries + " 个条目（上限 " + MAX_ZIP_ENTRIES + "）");
                    }
                    long size = entry.getSize();
                    if (size > 0) {
                        total += size;
                    }
                    if (total > MAX_UNCOMPRESSED_BYTES) {
                        throw bomb("解压后超过 " + (MAX_UNCOMPRESSED_BYTES / 1024 / 1024) + " MB");
                    }
                }
            }
            log.debug("ZIP 限额通过：name={} 条目={} 声明解压总量={}B",
                    file.getOriginalFilename(), entries, total);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            // 读不出中央目录 = 不是合法 ZIP。**不在这里拒绝** —— 类型判定是 checkMime 的职责，
            // 这里越权拒绝会把"文件损坏"报成"疑似炸弹"，两者下一步动作不同。
            log.debug("ZIP 结构读取失败（不判炸弹，交由解析阶段裁决）：name={} 原因={}",
                    file.getOriginalFilename(), ex.getMessage());
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ex) {
                    log.warn("ZIP 探测临时文件删除失败：{}", tmp);
                }
            }
        }
    }

    private static BizException bomb(String detail) {
        return BizException.of(ErrorCode.FILE_BOMB_SUSPECTED,
                "文档解压后体积异常（" + detail + "），已拒绝。"
                        + "正常文档不该有这个压缩比；若确实是正常文件，请先重新导出再上传");
    }

    /**
     * 加密 PDF：找 trailer 里的 {@code /Encrypt} 标记。
     *
     * <p>为什么头尾都看：PDF 的 trailer 在**文件尾**，而 {@code /Encrypt} 就写在里面；
     * 但小文件可能整个都在探测窗口内，所以头也要扫。
     *
     * <p>为什么用标记扫描而不是 PDFBox：准入阶段**不该解析** —— 那正是炸弹与畸形文件
     * 要你做的事。这里只需要一个"像不像加密件"的判据，判错了的代价是用户重传一次。
     */
    private static boolean looksEncryptedPdf(org.springframework.web.multipart.MultipartFile file, String ext) {
        if (!"pdf".equals(ext)) {
            return false;
        }
        try {
            long size = file.getSize();
            try (InputStream in = file.getInputStream()) {
                if (containsEncryptMarker(in.readNBytes(ENCRYPT_PROBE_BYTES))) {
                    return true;
                }
                if (size > 2L * ENCRYPT_PROBE_BYTES) {
                    skipFully(in, size - 2L * ENCRYPT_PROBE_BYTES);
                    return containsEncryptMarker(in.readNBytes(ENCRYPT_PROBE_BYTES));
                }
                return false;
            }
        } catch (IOException ex) {
            log.debug("加密探测失败（放行）：name={} 原因={}", file.getOriginalFilename(), ex.getMessage());
            return false;
        }
    }

    private static boolean containsEncryptMarker(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains("/Encrypt");
    }

    /** {@code InputStream#skip} 不保证跳满 —— 循环跳到目标为止（大文件只探尾部时要用）。 */
    private static void skipFully(InputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    return;
                }
                remaining--;
                continue;
            }
            remaining -= skipped;
        }
    }
}
