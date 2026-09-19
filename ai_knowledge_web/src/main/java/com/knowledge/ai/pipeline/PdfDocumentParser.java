package com.knowledge.ai.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * PDF 解析器（opendataloader-pdf-core 2.5.0，`T-502` 的 PDF 路径）。
 *
 * <h3>为什么坚持产出 JSON 而不是只拿 markdown</h3>
 * <p>markdown 里的表格是"看起来对齐"的字符串，行列边界已经糊掉了；
 * 而 {@code G4-4}（解析质量）与 `T-504`（表格双表示）需要的是**结构化的 cell 矩阵**
 * —— 《07》§2.2 把它列为"一张表必须拆成的三份"里的第一份。
 * JSON 的 {@code table.rows[].cells[].kids[].content} 是唯一能拿到它的入口
 * （开工探针实测，见 S4-M05-B 计划 §1 样本表）。
 *
 * <h3>为什么标题从 JSON 取而不用 markdown 的 {@code #}</h3>
 * <p>JSON 的 {@code heading level} 是结构化的层级数（1~6），
 * markdown 的 {@code #} 是排版近似 —— 幻灯片型 PDF 会把正文行也标成标题。
 * 层级数是 `T-503` 第一级切分（"标题层级 H1→H3，叶子节点即候选块"）的输入，
 * 用错一层，切分边界就整体漂移。
 *
 * <h3>调用纪律（三条，都是实测踩出来的）</h3>
 * <ol>
 *   <li><b>临时目录 {@code finally} 必删</b>：opendataloader 只吃路径，产物落在
 *       {@code outputFolder}；漏删会让并发上传时磁盘以"每篇文档一份产物"的速度漏；</li>
 *   <li><b>图片输出的两个上限必须给</b>（{@link #MAX_IMAGE_BYTES} / {@link #MAX_TOTAL_IMAGE_BYTES}）
 *       —— ⚠️ <b>这条纪律在 `T-512` 步 2b 被改写过，原话不是这样</b>。原来是
 *       「<b>图片输出必须关</b>（{@code IMAGE_OUTPUT_OFF}）：21.8MB 那类图多的手册
 *       开了会把产物撑到几百 MB」（`T-0S1` §二原话）。
 *       <p>🔴 <b>那句理由在 4/4 份真样本上都不成立</b>（`T512ImageProbeTest` 实测）：
 *       75 图那份 2.52 MB · 57 图 0.65 MB · 63 图 0.48 MB · 21.8MB 手册（15 图）17.50 MB
 *       —— 离"几百 MB"差一个数量级。而需求 `06:158` 点名要「题注 + VL 描述」进索引，
 *       不出图这条路根本走不通。
 *       <p>⚠️ <b>但上限仍要给</b>：实测最坏只到 17.50 MB，而更图多的大型 PDF **没测过**
 *       —— 所以纪律从"一律关掉"改成"开，但单图/单篇各有硬上限，超限不抽并登记"。
 *       这正是本仓对"被探针削弱但没被推翻的老结论"的标准处置：**改写它、把实测数字
 *       一并写进去，而不是删掉它**（删了，下一个人会重新踩一遍）。</li>
 *   <li><b>全局串行</b>（见 {@link #LOCK}）：API 是静态入口（含 {@code shutdown()}），
 *       线程安全性未验证 —— 宁可排队，不要"偶发跨文档串内容"这种没法复现的错。
 *       `S4-E1` 起**等锁带超时**（{@link #LOCK_WAIT_SECONDS}），排不上队的一方会拿到
 *       明确原因而不是无限期挂起。</li>
 * </ol>
 */
@Slf4j
public class PdfDocumentParser {

    /**
     * opendataloader 的调用锁 —— **全局串行**的落点。
     *
     * <p>没有这把锁，多个解析任务会同时走进这个静态 API。S9 的吞吐优化若证实线程安全，
     * 去掉它即可（计划 §3.4 假设 5）。
     *
     * <h3>⚠️ `S4-E1` 为什么把它从 {@code Object} 换成 {@link ReentrantLock}</h3>
     * 原实现是 {@code private static final Object LOCK} + {@code synchronized (LOCK)}。
     * 而 {@code O1` §4 步 5 要的是「带超时的 {@code tryLock}」—— **{@code synchronized} 没有超时**，
     * 这是 `优化/O1` 文档写错的一处（它把这里描述成 {@code ReentrantLock}，见计划 §1-6）。
     *
     * <p>换成 {@code ReentrantLock} 之后，**等锁的那一方**可以带超时放弃：
     * 一个畸形 PDF 长时间占着锁时，后面的作业不再无限期挂起，而是拿到
     * {@code PARSE_QUEUE_TIMEOUT} 的明确原因（"解析排队时间过长，请稍后重试"）。
     * 这是 `O1` 痛点 #3「一把锁卡死整个队列」在本进程内能做的全部 ——
     * ⚠️ **注意它限的是"等锁方"，不是"持锁方"**：持锁的那个线程仍要等本地调用自己返回。
     * 因为同 JVM 下无法安全强杀一个卡在本地代码里的线程（计划 §9）。
     */
    private static final ReentrantLock LOCK = new ReentrantLock(true);

    /** 等锁超时（秒）。超过就放弃并给可执行原因，而不是无限期排队。 */
    private static final long LOCK_WAIT_SECONDS = Long.getLong("m05.parse.lock-wait-seconds", 120L);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 产物文件名的后缀（opendataloader 按 {@code <源名>.json/.md} 落盘，源名带中文时后缀仍固定）。 */
    private static final String JSON_SUFFIX = ".json";

    /**
     * **单张图**的字节上限（`T-512` 步 2b，可配）。
     *
     * <p>取 **2 MB**：4/4 份样本实测单图最大 **1.79 MB**（`健康生活方式` 那份扫描件），
     * 留一次余量。超限的图**不抽**并登记 parse issue —— 见类注释调用纪律第 2 条。
     *
     * <p>⚠️ 用系统属性而不是配置表：与 {@link #LOCK_WAIT_SECONDS} 同一条取舍 ——
     * 它调的是**本进程的解析行为**，不是某个知识库的业务参数（那是 {@code split_policy_json} 管的）。
     */
    private static final long MAX_IMAGE_BYTES =
            Long.getLong("m05.image.max-bytes", 2L * 1024 * 1024);

    /**
     * **单篇文档**所有图的字节上限（`T-512` 步 2b，可配）。
     *
     * <p>取 **50 MB**：4/4 份样本实测单篇最大 **17.50 MB**，留约三倍余量。
     * 与 {@link #MAX_IMAGE_BYTES} 是**两道不同的闸**：单图那道拦"一张巨图"，
     * 这道拦"一篇里图特别多"—— 探针实测 4 份样本的出图数最多才 26 张，
     * 而**更图多的大型 PDF 没测过**，所以按篇给总量兜底。
     */
    private static final long MAX_TOTAL_IMAGE_BYTES =
            Long.getLong("m05.image.max-total-bytes", 50L * 1024 * 1024);

    /**
     * 解析一份 PDF（**不落任何图**）。
     *
     * <p>等价于 {@code parse(pdfPath, ImageSink.NOOP)} —— 保留这个重载是给
     * 解析基线 / 几何探针那类**只要解析结果、不要产物**的调用方用的。
     */
    public static ParsedDocument parse(Path pdfPath) throws IOException {
        return parse(pdfPath, ImageSink.NOOP);
    }

    /**
     * 解析一份 PDF，并把抽出的图交给 {@code sink}。
     *
     * @param pdfPath 源文件路径（**必须是磁盘上的真实文件** —— API 只吃路径不吃流）
     * @param sink    图片字节的出口；{@link ImageSink#NOOP} 表示不落存储
     */
    public static ParsedDocument parse(Path pdfPath, ImageSink sink) throws IOException {
        Path outDir = Files.createTempDirectory("kb-parse-");
        try {
            Config config = new Config();
            config.setOutputFolder(outDir.toString());
            config.setGenerateMarkdown(true);
            config.setGenerateJSON(true);
            config.setGenerateText(false);
            config.setGenerateHtml(false);
            // `T-512` 步 2b：**掀掉**原来"一律关掉"的纪律（理由与实测见类注释调用纪律第 2 条）。
            // 选 EXTERNAL 不选 EMBEDDED：后者把图塞进 JSON，产物会大一截（探针实测 JSON 297 KB
            // 是纯结构的大小，塞图后会翻好几倍）。
            config.setImageOutput(Config.IMAGE_OUTPUT_EXTERNAL);
            // JPEG 而不是 PNG：PDF 内嵌的位图本来就多是 JPEG（实测 DCTDecode），
            // 转 PNG 只会让体积变大而不带来任何信息。
            config.setImageFormat(Config.IMAGE_FORMAT_JPEG);
            // 表格方法保持 DEFAULT（T-0S1 §一-3：DEFAULT 与 CLUSTER 输出逐字节一致，不为 cluster 加复杂度）
            config.setTableMethod(Config.TABLE_METHOD_DEFAULT);
            // 多行单元格压平会让行列错位（T-0S1 §三），所以必须保留换行
            config.setKeepLineBreaks(true);

            // 等锁带超时：拿不到就抛**可执行**的原因回去，而不是无限期挂着
            // （`O1` 痛点 #3：一个畸形 PDF 占住锁 → 整个队列停摆且没有 kill 手段）。
            boolean acquired;
            try {
                acquired = LOCK.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw ParseException.of("解析被中断（服务正在停止或该任务已超时），请重新提交", ex);
            }
            if (!acquired) {
                throw new ParseException("解析排队时间过长（前面有文档正在解析且耗时异常），"
                        + "请稍后重试；若持续出现，请检查是否有超大或损坏的文件");
            }
            try {
                OpenDataLoaderPDF.processFile(pdfPath.toAbsolutePath().toString(), config);
            } finally {
                LOCK.unlock();
            }

            Path json = findFirst(outDir, JSON_SUFFIX);
            if (json == null) {
                // 连 JSON 都没产出：要么文件损坏要么被杀。给可读原因，不让下游猜
                throw new ParseException("PDF 解析未产出任何结构化结果，文件可能已损坏；请确认文件能正常打开后重新上传");
            }
            return fromJson(MAPPER.readTree(Files.readString(json, StandardCharsets.UTF_8)),
                    outDir, sink);
        } catch (ParseException ex) {
            throw ex;
        } catch (Exception ex) {
            // 底层异常（PDFBox 的运行时炸、IO 错）对外不可读 —— 包一层，原始异常进日志与 cause
            throw ParseException.of("PDF 解析失败：" + readableCause(ex)
                    + "。请确认文件未加密且能正常打开，然后重新上传", ex);
        } finally {
            deleteRecursively(outDir);
        }
    }

    /**
     * 从 opendataloader 的 JSON 元素树构造产物。
     *
     * <h3>元素树的形状（2026-09-16 探针重测，`S4-M05-C2` 步 0.1）</h3>
     * <p>{@code type} 的**全集是 6 种**：{@code heading}·{@code paragraph}·{@code list}·
     * {@code table}·{@code image}·**{@code text block}**。
     * 表格的 {@code rows[].cells[].kids[].content} 才是格子文本（cell 自身没有 content 字段）。
     *
     * <p>⚠️ <b>这份清单此前漏了 {@code text block}，代价是静默丢了 8.1% 的内容。</b>
     * 它是**容器**：自己没有 {@code content}，真正的段落与列表在它的 {@code kids[]} 里。
     * 当时的实现只遍历顶层 {@code kids[]}、把不认识的一律丢掉，于是整棵子树消失
     * —— 而 {@code table} 恰好都在顶层，所以既有判据（`table_total = 7`）照旧通过，
     * 没有任何测试发现。**改本方法前先读 {@link #walk} 的 javadoc。**
     *
     * <p>另：{@code header}/{@code footer} **不存在**（同一次探针确认）——
     * 所以页眉页脚得靠 `优化/O3` 的跨页重复检测自己判，不能指望解析器分类。
     */
    static ParsedDocument fromJson(JsonNode root) {
        return fromJson(root, null, ImageSink.NOOP);
    }

    /**
     * @param outDir 产物目录 —— 图片字节的相对路径（JSON 的 {@code source} 字段）按它解析。
     *               {@code null} 表示"没有产物目录可读"（只有 JSON、没有出图文件的场合）
     * @param sink   图片字节的出口
     */
    static ParsedDocument fromJson(JsonNode root, Path outDir, ImageSink sink) {
        String title = textOr(root.get("title"), "");

        Scan scan = new Scan(outDir, sink);
        int pages = intOr(root.get("number of pages"), 0);

        // `T-512` 步 3：**先收题注、再走树** —— 题注节点可能出现在它描述的那张图**前后任意位置**
        // （实测：`caption` 在第 5 页，它 `linked content id` 指向的 image 不一定同页）。
        // 单趟边走边配的话，配对结果会取决于遍历顺序 —— 那是"这份样本看着对、换一份就错"
        // 的一类缺陷，而需求 `06:158` 把题注定成了分片内容的**首选**，错配就直接污染检索。
        collectCaptions(root.get("kids"), scan);

        walk(root.get("kids"), 0, title, scan);

        List<ParsedDocument.Heading> headings = scan.headings;
        List<ParsedDocument.ParsedTable> tables = scan.tables;
        List<String> paragraphs = scan.paragraphs;
        List<ParsedDocument.DocElement> elements = scan.elements;
        // `T-512` 步 6.5：图片那类 issue 在 walk 时逐张攒下，与表格类在这里合流 ——
        // 两条路最后都进同一个 issues 列表，由 `TextLayerGuard#merge` 再并入页级问题
        List<ParsedDocument.DocumentIssue> issues = new ArrayList<>(scan.imageIssues);
        int imageCount = scan.imageCount;

        // ---- ⚠️ 「无文本层」的逐页问题**已移走**（`S4-E2`）----
        // 原先在这里：`chars <= 0` 时逐页产 NO_TEXT_LAYER（一行一页）。
        // 移到 `TextLayerGuard#pageIssues` 有两个理由，第二个是硬要求：
        //   ① 「哪个页丢了文本」的判据**只能有一份** —— 留在这里，Tika 路径（不产这段）
        //      与 PDF 路径就会各有一套行为，而本类与 `TextLayerGuard` 的类注释
        //      都写着"两条路径才不会各写一份、各漂一份"；
        //   ② **混合文档（38 页文本 + 2 页扫描）在这里永远产不出东西** —— `chars <= 0`
        //      整篇为零才进这个分支，而那正是 `优化/O2` 要堵的形态。只加一个"每页判一次"
        //      而不把整篇判据拆开，等于新旧两套逻辑并存。
        // 落库时由 `TextLayerGuard#merge` 把本方法产的 issue（表格类）与页级产的合并。
        int chars = paragraphs.stream().mapToInt(String::length).sum()
                + headings.stream().mapToInt(h -> h.text().length()).sum()
                + tables.stream().mapToInt(PdfDocumentParser::tableCharCount).sum();

        // ---- 检出结构但抽不到内容的表格（G4-4 的"静默故障"正面）----
        // 结构在（rows/cols 都有）、格子全空 —— 正是"识别不出内容的真表格"，
        // 必须让人看见，而不是被 status 的成功掩盖。
        for (ParsedDocument.ParsedTable t : tables) {
            if (!t.layoutArtifact() && !t.hasContent()) {
                issues.add(ParsedDocument.DocumentIssue.missing(t.pageNo(),
                        "第 " + t.pageNo() + " 页的表格检出了结构（" + t.rowCount() + " 行 × "
                                + t.colCount() + " 列）但未提取到内容，该表无法参与检索；"
                                + "疑似扫描件中的表格，请补录文本或启用 OCR 后重建索引",
                        ParsedDocument.IssueKind.TABLE_NOT_PARSED));
            }
        }

        String markdown = buildMarkdown(title, elements, tables);
        return new ParsedDocument("PDF", Math.max(pages, 1), chars, markdown,
                headings, tables, paragraphs.size(), imageCount, issues, elements);
    }

    /** 遍历过程中的累积点 —— 递归需要一个**跨层共享的可变容器**（方法局部变量做不到）。 */
    private static final class Scan {
        private final List<ParsedDocument.Heading> headings = new ArrayList<>();
        private final List<ParsedDocument.ParsedTable> tables = new ArrayList<>();
        private final List<String> paragraphs = new ArrayList<>();
        private final List<ParsedDocument.DocElement> elements = new ArrayList<>();
        private int imageCount;
        /**
         * `T-512` 步 3：题注的 `linked content id` → 题注文本。
         *
         * <p>先于 `walk` 填好（见 {@link #collectCaptions}），因为题注与它描述的图
         * **在元素树里没有固定的先后**。
         */
        private final Map<Integer, String> captionByLinkId = new HashMap<>();

        /** 图片字节的出口（`T-512` 步 2b）。 */
        private final ImageSink sink;
        /** 产物目录 —— JSON 的 {@code source} 是**相对路径**，按它解析。可为 {@code null}。 */
        private final Path outDir;
        /** 本篇已落存储的图片字节合计，对着 {@link #MAX_TOTAL_IMAGE_BYTES} 涨。 */
        private long imageBytesStored;
        /** 图片类 issue（`T-512` 步 6.5）—— 在 walk 时逐张攒，最后并进 {@code fromJson} 的 issues。 */
        private final List<ParsedDocument.DocumentIssue> imageIssues = new ArrayList<>();

        private Scan(Path outDir, ImageSink sink) {
            this.outDir = outDir;
            this.sink = sink == null ? ImageSink.NOOP : sink;
        }
    }

    /**
     * 递归遍历元素树，把可检索的元素收进 {@code scan}。
     *
     * <h3>为什么必须递归（`S4-M05-C2` 步 0.5 修的缺陷）</h3>
     * <p>本方法此前**只遍历顶层 `kids[]`**，不认识的一律落进 {@code default} 丢掉。
     * 而 opendataloader 的元素树里有一类**容器**：{@code type = "text block"} ——
     * 它自己没有 {@code content}，真正的段落与列表在它的 {@code kids[]} 里。
     * 于是整棵子树被**静默**丢弃：20 页样本实测丢 **8.1%** 的可提取文本
     * （32 个后代元素 / 200 字符，而顶层只保留了 2278 字符）。
     *
     * <p>它是"少了一点东西而没人发现"的形态 —— 既有测试全部通过，因为
     * {@code table} 都在顶层（`table_total = 7` 照旧成立），`chars` 又不计被丢的部分。
     *
     * <h3>为什么是"递归"而不是"给 text block 加一个 case"</h3>
     * <p>加 case 而不递归 = 只处理一层，遇到嵌套更深的容器会**再丢一次**。
     * 这里的分界是：**容器往下走、叶子就地处理**。
     *
     * <p>⚠️ **`list` 不递归**：它的条目（`list item`）由 {@link #joinListItems} 整表读走，
     * 再递归会重复计入。这条"谁是容器"的分界线是改本方法时最容易写错的地方。
     *
     * @param inheritedPageNo 父容器的页号；**子元素自己带页号时以子为准**
     */
    private static void walk(JsonNode kids, int inheritedPageNo, String title, Scan scan) {
        if (kids == null || !kids.isArray()) {
            return;
        }
        for (JsonNode el : kids) {
            String type = textOr(el.get("type"), "");
            int pageNo = intOr(el.get("page number"), inheritedPageNo);
            switch (type) {
                case "heading" -> {
                    String text = textOr(el.get("content"), "").trim();
                    if (!text.isEmpty()) {
                        // "Doctitle" 之类的非数字层级归 1；数字层级夹到 1~6
                        int level = switch (textOr(el.get("heading level"), "1")) {
                            case "2" -> 2;
                            case "3" -> 3;
                            case "4" -> 4;
                            case "5" -> 5;
                            case "6" -> 6;
                            default -> 1;
                        };
                        scan.headings.add(new ParsedDocument.Heading(level, text, pageNo));
                        scan.elements.add(ParsedDocument.DocElement.heading(level, text, pageNo));
                    }
                }
                case "table" -> {
                    scan.tables.add(toTable(textOr(el.get("title"), title), pageNo, el));
                    // 下标就是刚插进去的那张 —— 用 size()-1 而不是另设计数器，
                    // 两者一旦分头维护就会错位（这是"单一事实源"的本地落法）
                    scan.elements.add(ParsedDocument.DocElement.table(scan.tables.size() - 1, pageNo));
                }
                case "image" -> {
                    scan.imageCount++;
                    scan.elements.add(ParsedDocument.DocElement.image(
                            pageNo, imageInfoOf(el, pageNo, scan)));
                }
                case "paragraph" -> {
                    String text = textOr(el.get("content"), "").trim();
                    if (!text.isEmpty()) {
                        scan.paragraphs.add(text);
                        scan.elements.add(ParsedDocument.DocElement.paragraph(text, pageNo));
                    }
                }
                case "list" -> {
                    // 列表的 items 各自是一条 content，整表按一个段落计。
                    // **不递归** —— 见 javadoc 末尾那条分界线
                    String joined = joinListItems(el);
                    if (!joined.isBlank()) {
                        scan.paragraphs.add(joined);
                        scan.elements.add(ParsedDocument.DocElement.paragraph(joined, pageNo));
                    }
                }
                default -> {
                    // 容器（`text block`）与题注（`caption`）：**自身不产出元素，但子树必须照常走**。
                    // 这是步 0.5 修的那个缺陷 —— 此前这里什么都不做，于是 `text block`
                    // 的后代（段落、列表）整片消失。题注没有 kids，递归到它是空操作；
                    // "题注挂着表格"那一路仍由 table 侧的 "title" 承接，不进正文。
                    //
                    // ⚠️ `T-512` 起 `caption` **多了一个去处**：它已经被 collectCaptions
                    //    收进 `captionByLinkId`，再由 imageInfoOf 挂到对应的图元素上。
                    //    所以本分支"不产出元素"这一条**依然成立**（题注不单独成元素），
                    //    但它不再是"被丢掉"—— 那是本批要修的那件事。
                    walk(el.get("kids"), pageNo, title, scan);
                }
            }
        }
    }

    /**
     * 收题注（`T-512` 步 3）：`caption` 的 `linked content id` → 题注文本。
     *
     * <p>实测（`T512ImageProbeTest`）：`caption` 节点的字段是
     * {@code [type, pdfua_tag, id, page number, bounding box, linked content id, font, font size, text color, content]}，
     * 11 条题注全部带 `linked content id` —— **这条链是通的**，只是此前没人走。
     *
     * <p>⚠️ 同一张图有多条题注时取**第一条**（{@code putIfAbsent}），**不拼接**：
     * 拼起来会让"作者写的那句话"变成一段混合物，而需求 `06:158` 要的恰恰是那一句
     * （「题注优先级更高，它是作者写的，比 VL 描述更准」）。
     */
    private static void collectCaptions(JsonNode kids, Scan scan) {
        if (kids == null || !kids.isArray()) {
            return;
        }
        for (JsonNode el : kids) {
            if ("caption".equals(textOr(el.get("type"), ""))) {
                JsonNode link = el.get("linked content id");
                String content = textOr(el.get("content"), "").trim();
                if (link != null && link.canConvertToInt() && !content.isEmpty()) {
                    scan.captionByLinkId.putIfAbsent(link.asInt(), content);
                }
            }
            collectCaptions(el.get("kids"), scan);
        }
    }

    /**
     * `image` 节点 → {@link ImageInfo}（`T-512` 步 1）。
     *
     * <p>⚠️ {@code objectKey} / {@code md5} 在这里**恒为 null** —— 它们要等字节真的落到 MinIO
     * 之后才有，而那一步由 `DocParseService` 提供的 `ImageSink` 完成（步 2）。
     * **这里不编造**：节点有 {@code source} 只说明解析器**打算**导出，
     * 导出成功与否由那一步按字节判定（`ImageInfo#hasBytes`）。
     */
    private static ImageInfo imageInfoOf(JsonNode el, int pageNo, Scan scan) {
        JsonNode idNode = el.get("id");
        Integer id = idNode != null && idNode.canConvertToInt() ? idNode.asInt() : null;
        ImageInfo.Box box = boxOf(el.get("bounding box"));
        String caption = id == null ? null : scan.captionByLinkId.get(id);

        // `T-512` 步 2b：**有 `source` 才有字节可抽**。没有的那部分是**常态，不是异常**
        // —— 四份样本实测出图率 100% / 40% / 35% / 11%，一份比一份不一样。
        // 所以它走"**登记 + 继续**"：不抛异常、不算文档失败（用户 2026-09-18 裁定 B）。
        // 不登记的话，`image_parsed < image_total` 在页面上与"还没跑完"长得一模一样。
        String source = textOr(el.get("source"), "").trim();
        if (source.isEmpty()) {
            scan.imageIssues.add(ParsedDocument.DocumentIssue.missing(pageNo,
                    "第 " + pageNo + " 页有 1 张图片未能从文档中提取位图"
                            + "（可能是矢量图或纯版式元素），该图不会参与检索；"
                            + "若其内容重要，请补录文字后重建索引",
                    ParsedDocument.IssueKind.IMAGE_NOT_EXTRACTABLE));
            return new ImageInfo(id, box, caption, null, null, null);
        }

        ImageSink.Stored stored = storeImage(scan, source, pageNo);
        // `vlModelId` 在第 6 个位置留 null —— 它是**唯一**不在解析期产生的字段，
        // 由锁外描述那一步（步 5）经 `ImageInfo#withVlModel` 补上。见该字段的 javadoc。
        return new ImageInfo(id, box, caption,
                stored == null ? null : stored.objectKey(),
                stored == null ? null : stored.md5(),
                null);
    }

    /**
     * 把一张抽出的图的字节交给 {@code sink}（`T-512` 步 2b）。
     *
     * <h3>四道闸，任一不过就返回 {@code null} 并**登记一条 issue**</h3>
     * <ol>
     *   <li>没有产物目录（{@code outDir == null}）—— 只有 JSON、没有出图文件的场合。
     *       ⚠️ **不登记**：那是调用方（解析基线等）**主动**不要产物的形态，
     *       记成"图片无法提取"会是一条**假的**告警。</li>
     *   <li>文件读不到 —— 解析器说导出了、磁盘上却没有。这是**真出了问题**，要看得见。</li>
     *   <li>超单图 / 单篇上限（{@link #MAX_IMAGE_BYTES} / {@link #MAX_TOTAL_IMAGE_BYTES}）
     *       —— 主动放弃，报文里写清是**超限**而不是"提不出来"。</li>
     *   <li>sink 抛异常（MinIO 抖动等）—— 捕获并降级：**一张图存不下不该让整篇文档失败**
     *       （与 {@code IndexEnhancer} "增强失败不牵连文档"同一条取舍）。</li>
     * </ol>
     *
     * <p>⚠️ 第 3 条的两个上限在 4 份真样本上**都没触发**（最大单图 1.79 MB / 单篇 17.50 MB），
     * 所以这条路径目前**只有用例能覆盖** —— 那不是"写了没用的代码"，
     * 是给"更图多的大 PDF"留的兜底，而那一类**本仓样本里没有**。
     */
    private static ImageSink.Stored storeImage(Scan scan, String source, int pageNo) {
        if (scan.outDir == null) {
            return null;
        }
        // `source` 来自我们自己解析的 JSON，理论上就是我们自己临时目录下的相对路径；
        // 仍做一次归一化 + 前缀校验 —— 一个 `../` 就能让"读自己的产物"变成"读任意文件"
        Path file = scan.outDir.resolve(source).normalize();
        if (!file.startsWith(scan.outDir)) {
            registerImageIssue(scan, pageNo,
                    "图片路径越出产物目录，已拒绝读取（" + source + "）");
            return null;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException ex) {
            registerImageIssue(scan, pageNo,
                    "第 " + pageNo + " 页的图片文件读不到（" + source + "）：" + readableCause(ex));
            return null;
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            registerImageIssue(scan, pageNo, "第 " + pageNo + " 页的图片超过单图上限（"
                    + kb(bytes.length) + " KB > " + kb(MAX_IMAGE_BYTES) + " KB），已跳过未抽取");
            return null;
        }
        if (scan.imageBytesStored + bytes.length > MAX_TOTAL_IMAGE_BYTES) {
            registerImageIssue(scan, pageNo,
                    "本篇图片累计已达单篇上限（" + mb(MAX_TOTAL_IMAGE_BYTES) + " MB），"
                            + "第 " + pageNo + " 页起的后续图片已跳过未抽取");
            return null;
        }
        try {
            ImageSink.Stored stored = scan.sink.store(bytes, pageNo, file.getFileName().toString());
            if (stored != null) {
                scan.imageBytesStored += bytes.length;
            }
            return stored;
        } catch (RuntimeException ex) {
            // 存不下就降级（该图不进索引），但**必须留痕** —— 静默丢图是"少了一点东西
            // 而没人发现"的形态，正是本仓最不能接受的那一类
            registerImageIssue(scan, pageNo, "第 " + pageNo + " 页的图片存储失败："
                    + readableCause(ex) + "；该图不会参与检索，请重新上传该文档");
            return null;
        }
    }

    /** 记一条图片类 issue（`IMAGE_NOT_EXTRACTABLE` + 处置"未处理"）。 */
    private static void registerImageIssue(Scan scan, int pageNo, String reason) {
        scan.imageIssues.add(ParsedDocument.DocumentIssue.missing(
                pageNo, reason, ParsedDocument.IssueKind.IMAGE_NOT_EXTRACTABLE));
    }

    /**
     * `bounding box` = {@code [x0, y0, x1, y1]}（实测字段名**带空格**）。
     *
     * <p>形状不对时返回 {@code null} 而不是硬凑四个 0：0 包围盒是一个**看起来合法**的值，
     * 会让下游的"面积过滤"把这张图当成小图标丢掉 —— 那是静默丢内容。
     */
    private static ImageInfo.Box boxOf(JsonNode node) {
        if (node == null || !node.isArray() || node.size() < 4) {
            return null;
        }
        return new ImageInfo.Box(node.get(0).asDouble(), node.get(1).asDouble(),
                node.get(2).asDouble(), node.get(3).asDouble());
    }

    /** {@code table} 元素 → cell 矩阵。格子在 {@code cells[].kids[].content} 里逐段拼。 */
    private static ParsedDocument.ParsedTable toTable(String caption, int pageNo, JsonNode table) {
        List<List<String>> rows = new ArrayList<>();
        JsonNode rowsNode = table.get("rows");
        if (rowsNode != null && rowsNode.isArray()) {
            for (JsonNode row : rowsNode) {
                List<String> cells = new ArrayList<>();
                JsonNode cellsNode = row.get("cells");
                if (cellsNode != null && cellsNode.isArray()) {
                    for (JsonNode cell : cellsNode) {
                        cells.add(cellText(cell));
                    }
                }
                rows.add(cells);
            }
        }
        return new ParsedDocument.ParsedTable(caption, pageNo, rows);
    }

    private static String cellText(JsonNode cell) {
        StringBuilder sb = new StringBuilder();
        JsonNode kids = cell.get("kids");
        if (kids != null && kids.isArray()) {
            for (JsonNode kid : kids) {
                String t = textOr(kid.get("content"), "");
                if (!t.isBlank()) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(t.trim());
                }
            }
        }
        return sb.toString();
    }

    private static int tableCharCount(ParsedDocument.ParsedTable t) {
        return t.rows().stream()
                .flatMap(List::stream)
                .mapToInt(String::length)
                .sum();
    }

    private static String joinListItems(JsonNode list) {
        StringBuilder sb = new StringBuilder();
        JsonNode items = list.get("list items");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                String t = textOr(item.get("content"), "").trim();
                if (!t.isEmpty()) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append("- ").append(t);
                }
            }
        }
        return sb.toString();
    }

    /**
     * 统一形态的 markdown：**按 {@code elements} 的文档顺序**逐条拼。
     *
     * <p>⚠️ 本方法此前是"先把标题全拼完、再拼全部段落、再拼全部表格"—— 注释写的是
     * 「按元素出现顺序的近似」，但那个"近似"其实**不成立**：段落与标题的相对位置整个丢了。
     * 后果是 {@code markdown} 无法用于切分（`T-503` 的第一级要靠它），
     * 且与 {@code TikaDocumentParser}（流式按序）的产物结构**不一致**。
     * `S4-M05-C1` 步 0.6 改为按序拼，两条路径就此同形。
     */
    private static String buildMarkdown(String title,
                                        List<ParsedDocument.DocElement> elements,
                                        List<ParsedDocument.ParsedTable> tables) {
        StringBuilder sb = new StringBuilder();
        if (!title.isBlank()) {
            sb.append("# ").append(title.trim()).append("\n\n");
        }
        for (ParsedDocument.DocElement el : elements) {
            switch (el.kind()) {
                case HEADING -> sb.append("#".repeat(Math.max(1, Math.min(6, el.level()))))
                        .append(' ').append(el.text()).append("\n\n");
                case PARAGRAPH -> sb.append(el.text()).append("\n\n");
                case TABLE -> {
                    String md = tables.get(el.tableIndex()).toMarkdown();
                    if (!md.isBlank()) {
                        sb.append(md).append('\n');
                    }
                }
                // 图片不进正文：题注已在 table 侧承接；在 T-512 产出 VL 描述之前，
                // 这里没有任何可放的文本（占位成"（图片）"会变成一条假正文）。
                case IMAGE -> {
                }
            }
        }
        return sb.toString();
    }

    private static String readableCause(Exception ex) {
        String m = ex.getMessage();
        if (m == null || m.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return m.length() > 200 ? m.substring(0, 200) : m;
    }

    /** 字节数 → 给人看的 KB（图片上限的 issue 文案用）。 */
    private static String kb(long bytes) {
        return String.format("%.1f", bytes / 1024.0);
    }

    /** 字节数 → 给人看的 MB。 */
    private static String mb(long bytes) {
        return String.format("%.1f", bytes / 1024.0 / 1024.0);
    }

    private static Path findFirst(Path dir, String suffix) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))
                    .max(Comparator.comparingLong(PdfDocumentParser::sizeOf))
                    .orElse(null);
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1L;
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // 删不掉只能留日志：临时目录泄漏不该让解析本身失败
                    log.warn("解析临时目录删除失败：{}", p);
                }
            });
        } catch (IOException ex) {
            log.warn("解析临时目录遍历失败：{} err={}", dir, ex.getMessage());
        }
    }

    private static String textOr(JsonNode node, String fallback) {
        return node == null || node.isNull() ? fallback : node.asText();
    }

    private static int intOr(JsonNode node, int fallback) {
        return node == null || node.isNull() || !node.canConvertToInt() ? fallback : node.asInt();
    }
}
