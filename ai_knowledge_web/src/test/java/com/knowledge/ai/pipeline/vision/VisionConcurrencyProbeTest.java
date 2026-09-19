package com.knowledge.ai.pipeline.vision;

import com.knowledge.ai.pipeline.ImageSink;
import com.knowledge.ai.pipeline.PdfDocumentParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 **VL 端点能不能吃并发** —— 直接决定"文档图片描述"这条链要不要改成延迟/按需（2026-09-19）。
 *
 * <h3>它回答的那个问题</h3>
 * <p>文档侧的图片描述目前是**逐张串行**的：`1-DeepSeek使用.pdf` 有 26 张可描述的图，
 * 而真 VL 实测 **40~60 秒/张** ⇒ **单篇约 20 分钟**，这 20 分钟里文档停在 {@code INDEXING}。
 * <p>在讨论"要不要做一套延迟 + 按图勾选的 UI"之前，先量一件更便宜的事：
 * **把这个循环并行起来，到底能快多少？**
 * <ul>
 *   <li>若近似线性 ⇒ 20 分钟变 3 分钟，那套 UI 的必要性**大减**；</li>
 *   <li>若打平（端点本身串行排队）⇒ 延迟设计**从可选变成必需**。</li>
 * </ul>
 *
 * <h3>⚠️ 它绕过 `VisionDescriber`，直接打 {@link VisionCaller} —— 这是刻意的</h3>
 * <p>{@code VisionDescriber} 带缓存（key = {@code md5:vlModelId:PROMPT_VERSION}）。
 * 用同一张图重复调，**第二次起就是缓存命中** —— 那时量到的是 Redis 的往返，
 * 不是模型端点的并发能力，结论会**完全失真**。
 * <p>直接打 caller 的代价是：本探针的调用**不进用量表**（`ModelUsageRecorder` 在 Describer 里），
 * 也**不写缓存**。两件事都是本探针想要的 —— 探针不该污染生产统计，也不该在 Redis 里留 30 天的垃圾。
 *
 * <h3>⚠️ 它会真花钱</h3>
 * <p>端点是**远程云 API**（`api-siliconflow.cn`）。15 次 `Qwen3-VL-8B` 调用，
 * 成本量级是分/角 —— 但**它确实不是零**。故：
 * {@code @Tag("network")}（不进默认跑批）+ 端点不可用时 {@code Assumptions} **跳过并说明原因**。
 */
@SpringBootTest
@Tag("network")
@DisplayName("探针：VL 端点的并发吞吐曲线（真模型，会花钱）")
class VisionConcurrencyProbeTest {

    private static final Path DIR = Path.of("..", "docs", "测试文件").toAbsolutePath().normalize();
    private static final Path SAMPLE = DIR.resolve("1-DeepSeek使用.pdf");

    /**
     * 并发档位 —— **默认只跑 1 和 4**，完整曲线用系统属性要：
     * {@code -Dvision.probe.levels=1,2,4,8}。
     *
     * <h3>为什么默认要收窄</h3>
     * <p>⚠️ 这些是**真付费调用**，而且每次都是**分钟级**（实测单次 **95~115 秒**）。
     * 跑满 {@code 1,2,4,8} 是 15 次调用、**实测 349 秒**（近 6 分钟）——
     * 加进默认跑批就等于给每次全量再挂 6 分钟。
     * 而本探针要回答的问题（"并发有没有用"）**两档就够了**：
     * 一档串行、一档并行，比一比单次均摊就出结论。
     * 想看完整曲线（比如真要按它定线程数）再显式传那个属性。
     */
    private static int[] levels() {
        String raw = System.getProperty("vision.probe.levels", "1,4");
        try {
            return java.util.Arrays.stream(raw.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .mapToInt(Integer::parseInt).toArray();
        } catch (NumberFormatException ex) {
            return new int[]{1, 4};
        }
    }

    @Autowired
    private VisionCaller caller;

    @Autowired
    private ImageCompressor compressor;

    @Test
    @DisplayName("并发 1 / N 的墙钟时间与吞吐（只量，只对'不抛异常'断言）")
    void concurrencyThroughput() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(SAMPLE), "缺样本 " + SAMPLE);
        Assumptions.assumeTrue(caller.modelRef().present(),
                "VL 模型未配置（modelRef 不 present），跳过");

        byte[] image = firstImageBytes();
        Assumptions.assumeTrue(image != null && image.length > 0, "样本里没抽出可用的图，跳过");
        byte[] payload = compressor.compress(image);
        int[] levels = levels();

        System.out.println("======== VL 并发探针 ========");
        System.out.println("模型=" + caller.modelRef().name()
                + " · 图片：原始 " + image.length / 1024 + " KB → 压缩后 " + payload.length / 1024 + " KB");
        System.out.println("档位=" + java.util.Arrays.toString(levels)
                + "（改它：-Dvision.probe.levels=1,2,4,8）");
        System.out.printf("%-10s %12s %14s %16s%n", "并发", "墙钟(ms)", "单次均摊(ms)", "吞吐(次/分钟)");

        for (int level : levels) {
            long millis;
            try {
                millis = runBatch(payload, level);
            } catch (RuntimeException ex) {
                // 🔴 **第一档同时兼作冒烟**（省掉一次 95 秒的重复调用）。
                //    它失败 = 端点根本用不了 ⇒ 跳过而不是红：本探针依赖**外部付费服务**，
                //    它不可用不是本仓的缺陷。但**要把原因喊出来** ——
                //    静默跳过会让"没量过"看着像"量过没问题"。
                //
                // ⚠️ 而且**必须打出整条 cause 链**：第一版只打 `ex.getMessage()`，
                //    得到的是「Request failed」四个字 —— 对排查**零信息量**
                //    （实测：那个失败是**偶发**的，重跑就过了；而当时那句话让人无从判断）。
                //    本仓对失败态的硬口径是「现象 + 下一步动作」。
                if (level == levels[0]) {
                    Assumptions.abort(describeFailure(ex, level));
                    return;
                }
                // 后面档位失败是真异常（说明并发本身有问题）—— 让它红
                throw ex;
            }
            double perCall = (double) millis / level;
            System.out.printf("%-10d %12d %14.0f %16.1f%n",
                    level, millis, perCall, 60_000.0 / perCall);
            assertThat(millis).as("并发 %d 时必须跑完", level).isPositive();
        }
        System.out.println("=========================================");
        System.out.println("判读：把「并发 1 的单次均摊」与「并发 N 的单次均摊」比 ——");
        System.out.println("     基本持平 ⇒ 端点是并行的，并行循环直接有效；");
        System.out.println("     随 N 线性上升 ⇒ 端点在排队，得走延迟/按需那条路。");
        System.out.println("⚠️ 采样边界：单张图、每档一轮、单次延迟本身波动 ±20% —— 别当成吞吐上限。");
    }

    /** 把异常摊成「现象 + 下一步动作」，而不是一句 `Request failed`。 */
    private static String describeFailure(RuntimeException ex, int level) {
        StringBuilder chain = new StringBuilder("🔴 VL 端点不可用（并发 " + level + " 那档失败），本次没量到任何东西");
        Throwable cursor = ex;
        int depth = 0;
        while (cursor != null && depth++ < 8) {
            chain.append("\n  ← ").append(cursor.getClass().getName())
                    .append(": ").append(cursor.getMessage());
            cursor = cursor.getCause() == cursor ? null : cursor.getCause();
        }
        chain.append("\n排查顺序：① .env 的 VL_API_KEY 与余额；② curl 直连 "
                + "https://api.siliconflow.cn/v1/models 看 200；"
                + "③ 若前两条都正常 —— 本探针实测过一次**偶发**的 Request failed（重跑即过），"
                + "那就是端点侧抖动，重跑一次即可");
        return chain.toString();
    }

    /** 在**同一个栅栏**上同时放 N 个请求，量整批的墙钟时间。 */
    private long runBatch(byte[] payload, int level) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(level);
        long start = System.nanoTime();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(level);
            for (int i = 0; i < level; i++) {
                Callable<Void> task = () -> {
                    // 带超时：一个任务先抛异常时，其余的不能永久挂在栅栏上
                    //（那会让探针**挂死**而不是失败 —— 挂死难查得多）
                    barrier.await(60, TimeUnit.SECONDS);
                    caller.call(VisionPrompt.of(null), payload, null, "image/jpeg");
                    return null;
                };
                futures.add(pool.submit(task));
            }
            for (Future<?> f : futures) {
                f.get();   // 任一失败就在这里抛，不让它变成一个"看着正常的"耗时
            }
        }
        return (System.nanoTime() - start) / 1_000_000L;
    }

    /**
     * 抽出样本里的第一张图。
     *
     * <p>sink 返回 {@code null} ⇒ **不落任何存储**（探针不该往 MinIO 写东西）；
     * 顺带这也绕开了 `ImageBuffer` 那套"攒着等放行"的逻辑，探针只关心字节。
     */
    private static byte[] firstImageBytes() throws Exception {
        byte[][] holder = new byte[1][];
        ImageSink capture = (bytes, pageNo, sourceName) -> {
            if (holder[0] == null) {
                holder[0] = bytes;
            }
            return null;
        };
        PdfDocumentParser.parse(SAMPLE, capture);
        return holder[0];
    }
}
