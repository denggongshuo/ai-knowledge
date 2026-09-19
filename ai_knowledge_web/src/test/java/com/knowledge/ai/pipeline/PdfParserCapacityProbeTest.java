package com.knowledge.ai.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.api.OpenDataLoaderPDF;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * `PdfDocumentParser` **那把全局串行锁**到底该不该在 —— 两个探针（2026-09-19）。
 *
 * <h3>为什么问这个问题</h3>
 * <p>{@code PdfDocumentParser.LOCK} 的 javadoc 写着「API 是静态入口（含 {@code shutdown()}），
 * <b>线程安全性未验证</b> —— 宁可排队」。注意最后那个词：是「**未验证**」，不是「已知不安全」。
 * 而它的代价是实打实的：**全库的解析串成一条线**。
 * <p>所以本探针把那个"未验证"变成一个**有数字的结论**。两条：
 * <ol>
 *   <li><b>{@code Config.setThreads(int)}</b> —— `javap` 实测这个 setter 存在，
 *       而生产日志里跑的是 {@code Processing 42 pages with 1 threads}（**默认 1**）。
 *       这是一个**白捡的**提速，从来没打开过；</li>
 *   <li><b>并发 {@code processFile} 安不安全</b> —— 安全就能去掉那把锁。</li>
 * </ol>
 *
 * <h3>⚠️ 本探针只读、不改生产</h3>
 * <p>它**直接调 {@link OpenDataLoaderPDF} 的静态入口**（绕开 {@code PdfDocumentParser} 的锁），
 * 生产配置一行不动。结论出来之后要不要改那把锁，是**另一个决定**。
 *
 * <h3>为什么吞吐那条不写断言</h3>
 * <p>计时断言在 CI 上必然 flaky（机器负载、GC、JIT 预热都会让它红），
 * 而"红的测试会被无视"比"没有测试"更糟。所以吞吐那条**只打印**，由人对着数字判断。
 * <p>并发那条**必须断言** —— 它验的是**正确性**（同输入同输出），那是确定性的、不会 flaky。
 */
class PdfParserCapacityProbeTest {

    private static final Path DIR = Path.of("..", "docs", "测试文件").toAbsolutePath().normalize();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 页数最多的一份（42 页）—— 线程数那条只在"页多"的样本上才看得出差别。 */
    private static final Path MANY_PAGES = DIR.resolve("1-DeepSeek使用.pdf");

    /** 21.8MB 那份**全扫描件** —— 本仓最慢的一次解析，才是"那把锁值不值钱"的判据样本。 */
    private static final Path HEAVY = DIR.resolve("健康生活方式（指导手册）.pdf");

    /** 并发那组：四份**内容互不相同**的 PDF，串味了一眼能看出来。 */
    private static final List<Path> CONCURRENT_SAMPLES = List.of(
            DIR.resolve("1-DeepSeek使用.pdf"),
            DIR.resolve("百福云PaaS平台应用部署快速入门 v1.0.pdf"),
            DIR.resolve("江西省农村信用社联合社_业务中台整体规划项目_项目管理章程.pdf"),
            DIR.resolve("2-提示词工程.pdf"));

    // ==================================================================
    // 探针 A：线程数 → 吞吐（只打印）
    // ==================================================================

    @Test
    @DisplayName("探针 A：Config.setThreads 打开之后快多少（**两份样本**，只量不断言）")
    void threadSweep() throws Exception {
        // ⚠️ 为什么必须两份：第一版只量了 42 页那份，结论是"线程几乎没用"（1.28x）——
        //    但那是因为**它本来就只要 200ms**（页级工作量小、固定开销占大头）。
        //    而"那把锁值不值钱"取决于**最慢的那一类文档**，所以必须补上 21.8MB 的扫描件。
        //    这正是本仓那条纪律：**结论要写明采样边界**，否则"线程没用"会被误读成普适的。
        List<Path> samples = List.of(MANY_PAGES, HEAVY).stream()
                .filter(Files::isRegularFile).toList();
        Assumptions.assumeTrue(!samples.isEmpty(), "缺样本（已 gitignore）");

        System.out.println("======== 探针 A：opendataloader setThreads 扫描 ========");
        // 🔴 预热：本探针**第一版**漏了它，于是 `threads=1` 量到的是 JVM **冷启动**那一次
        //    （实测同一份文件冷 807ms / 热 216ms —— 差 3.7 倍），
        //    而 `threads=4/8` 跑在热身上 ⇒ 算出 3.19x 的**假加速**。
        //    这正是"观测方式本身改变了观测结果"：**测的是顺序，不是配置**。
        timeParse(samples.getFirst(), 1);

        for (Path sample : samples) {
            System.out.println("---- " + sample.getFileName() + "（"
                    + sample.toFile().length() / 1024 / 1024 + " MB）----");
            System.out.printf("%-10s %12s %12s %10s%n", "threads", "最快(ms)", "最慢(ms)", "相对 1 线程");
            long baseline = -1;
            for (int threads : new int[]{1, 4, 8}) {
                // 每种配置跑 3 次取**最快**：最小值是基准测试里对噪声最稳的统计量
                // （中位数会被一次 GC 抬起来，平均值更糟）
                long best = Long.MAX_VALUE;
                long worst = 0;
                for (int rep = 0; rep < 3; rep++) {
                    long millis = timeParse(sample, threads);
                    best = Math.min(best, millis);
                    worst = Math.max(worst, millis);
                }
                if (baseline < 0) {
                    baseline = best;
                }
                System.out.printf("%-10d %12d %12d %9.2fx%n",
                        threads, best, worst, (double) baseline / best);
            }
        }
        // ---- 🔴 决定性的一问：开线程会不会**改变产物**？----
        // 光"更快"不足以采纳它：`setThreads` 若让产物变了，那它就不是白捡的，
        // 而是一次**解析行为变更**（要进 PARSER_CONFIG、要让全库重解析）。
        // 所以这里必须验"同输入同输出"，而不只是看秒表。
        System.out.println("---- 产物一致性：threads=1 的指纹 vs 4 / 8 ----");
        for (Path sample : samples) {
            String one = digest(parseAndRead(sample, newTempDir(), 1));
            String four = digest(parseAndRead(sample, newTempDir(), 4));
            String eight = digest(parseAndRead(sample, newTempDir(), 8));
            System.out.println("  " + sample.getFileName());
            System.out.println("    threads=1 → " + one);
            System.out.println("    threads=4 → " + four + (four.equals(one) ? "  ✅ 一致" : "  🔴 不一致"));
            System.out.println("    threads=8 → " + eight + (eight.equals(one) ? "  ✅ 一致" : "  🔴 不一致"));
            assertThat(four).as("threads=4 的产物必须与 threads=1 逐维相同 —— 不同就说明它改变了"
                    + "解析行为，采纳它就要连带改 PARSER_CONFIG 指纹并让全库重解析").isEqualTo(one);
            assertThat(eight).as("threads=8 同上").isEqualTo(one);
        }
        System.out.println("⚠️ 只量不断言 —— 计时断言在 CI 上必然 flaky，而'红的测试会被无视'更糟");
        System.out.println("=====================================================");

        // 唯一的能力断言：setThreads 不炸（值能不能生效由上面的数字回答）
        assertThat(timeParse(MANY_PAGES, 4)).as("setThreads(4) 必须能跑通，不抛异常").isPositive();
    }

    // ==================================================================
    // 探针 B：并发 processFile 正不正确（必须断言）
    // ==================================================================

    @Test
    @DisplayName("探针 B：并发调 processFile 会不会串内容（决定那把锁能不能去掉）")
    void concurrentProcessFileIsSafe() throws Exception {
        List<Path> present = CONCURRENT_SAMPLES.stream().filter(Files::isRegularFile).toList();
        Assumptions.assumeTrue(present.size() >= 2,
                "至少要有两份不同样本，否则'串味'测不出来（样本已 gitignore）");

        // ---- ① 串行基线：每份单独跑，记下它的"指纹" ----
        List<ParsedDocument> baselines = new ArrayList<>();
        for (Path sample : present) {
            baselines.add(parseAndRead(sample, newTempDir(), DEFAULT_THREADS));
        }

        System.out.println("======== 探针 B：并发 processFile ========");
        for (int i = 0; i < present.size(); i++) {
            System.out.println("  基线 " + present.get(i).getFileName() + " → " + digest(baselines.get(i)));
        }

        // ---- ② 并发：所有线程在同一个栅栏上起跑，各自独立的 Config + 输出目录 ----
        int rounds = 3;
        int mismatches = 0;
        for (int round = 1; round <= rounds; round++) {
            List<Path> outDirs = new ArrayList<>();
            for (int i = 0; i < present.size(); i++) {
                outDirs.add(newTempDir());
            }
            CyclicBarrier barrier = new CyclicBarrier(present.size());
            // 用虚拟线程：本探针要的是"真的同时进去"，而不是被池大小限住
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<ParsedDocument>> futures = new ArrayList<>();
                for (int i = 0; i < present.size(); i++) {
                    Path sample = present.get(i);
                    Path outDir = outDirs.get(i);
                    Callable<ParsedDocument> task = () -> {
                        // ← 关键：让所有线程**同时**进入静态入口。
                        // ⚠️ 带超时是必须的 —— 不带的话，任何一个任务先抛异常，
                        //    其余的会**永久挂在栅栏上**，而 `pool.close()` 会等着它们
                        //    ⇒ 探针不是失败，是**挂死**（那比失败难查得多）
                        barrier.await(30, java.util.concurrent.TimeUnit.SECONDS);
                        return parseAndRead(sample, outDir, DEFAULT_THREADS);
                    };
                    futures.add(pool.submit(task));
                }
                for (int i = 0; i < present.size(); i++) {
                    ParsedDocument got = futures.get(i).get();
                    ParsedDocument want = baselines.get(i);
                    if (!digest(got).equals(digest(want))) {
                        mismatches++;
                        System.out.println("  🔴 第 " + round + " 轮串味：" + present.get(i).getFileName()
                                + " 期望 " + digest(want) + " 实得 " + digest(got));
                    }
                }
            }
            System.out.println("  第 " + round + " 轮完成（" + present.size() + " 份并发）");
        }
        System.out.println("=========================================");

        assertThat(mismatches)
                .as("并发解析**串了内容** —— 同输入不同输出。这直接说明那把锁**不能去**"
                        + "（而它此前只是'未验证'，现在它是一条有证据的结论）")
                .isZero();
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 与生产同形的一串开关；**唯一多出来的是 threads**。 */
    private static final int DEFAULT_THREADS = 1;

    private static long timeParse(Path pdf, int threads) throws Exception {
        Path outDir = newTempDir();
        long start = System.nanoTime();
        try {
            runProcess(pdf, outDir, threads);
        } finally {
            deleteRecursively(outDir);
        }
        return (System.nanoTime() - start) / 1_000_000L;
    }

    private static ParsedDocument parseAndRead(Path pdf, Path outDir, int threads) throws Exception {
        try {
            runProcess(pdf, outDir, threads);
            Path json = findJson(outDir);
            if (json == null) {
                throw new IllegalStateException("没拿到 JSON：" + pdf.getFileName());
            }
            return PdfDocumentParser.fromJson(
                    MAPPER.readTree(Files.readString(json, StandardCharsets.UTF_8)));
        } finally {
            deleteRecursively(outDir);
        }
    }

    /**
     * **直接**调静态入口 —— 绕开 {@code PdfDocumentParser} 的那把锁，这正是探针要问的那件事。
     *
     * <p>其余开关与 {@code PdfDocumentParser.parse} 同形，**只把图输出降级成
     * {@code IMAGE_OUTPUT_OFF}**（生产是 `EXTERNAL`）。两条理由，都不是图省事：
     * <ul>
     *   <li>本探针量的是**页级并行**与**页级确定性**，两者都**不依赖**是否把图写出来
     *       —— 指纹里的 {@code imageCount} 数的是**图元素**，与出图模式无关；</li>
     *   <li>开着的话，探针 B 要跑 4 份 × 3 轮 = 12 次，其中有 75 图那份
     *       ⇒ 光图就要写掉几百 MB 的临时文件，而它**不改变结论**。</li>
     * </ul>
     * ⚠️ 代价要说清：如果静态状态藏在**图片导出**那段里，本探针看不见它。
     * 那是一条已知的盲区，登记在计划里。
     */
    private static void runProcess(Path pdf, Path outDir, int threads) throws Exception {
        Config config = new Config();
        config.setOutputFolder(outDir.toString());
        config.setGenerateMarkdown(true);
        config.setGenerateJSON(true);
        config.setGenerateText(false);
        config.setGenerateHtml(false);
        config.setImageOutput(Config.IMAGE_OUTPUT_OFF);
        config.setTableMethod(Config.TABLE_METHOD_DEFAULT);
        config.setKeepLineBreaks(true);
        config.setThreads(threads);
        OpenDataLoaderPDF.processFile(pdf.toAbsolutePath().toString(), config);
    }

    /**
     * 一份产物的**指纹** —— 串味会让它变。
     *
     * <p>取的都是"内容量"而不是"内容本身"：页数 / 字符数 / 表格数 / 图元素数 / 标题数 /
     * markdown 的哈希。前五个是独立维度（只串一个也能看出来），最后一个盖住正文。
     */
    private static String digest(ParsedDocument doc) {
        return "pages=" + doc.pages()
                + " chars=" + doc.chars()
                + " tables=" + doc.tableTotal()
                + " images=" + doc.imageCount()
                + " headings=" + doc.headings().size()
                + " md#" + Integer.toHexString(doc.markdown().hashCode());
    }

    private static Path newTempDir() throws Exception {
        return Files.createTempDirectory("kb-capacity-probe-");
    }

    private static Path findJson(Path dir) throws Exception {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".json"))
                    .findFirst().orElse(null);
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 探针的临时目录清不掉不影响结论
                }
            });
        }
    }
}
