package com.knowledge.ai.pipeline.vision;

import com.knowledge.ai.core.enums.BlockType;
import com.knowledge.ai.core.enums.ModelType;
import com.knowledge.ai.core.storage.DocStorage;
import com.knowledge.ai.pipeline.Block;
import com.knowledge.ai.pipeline.ImageInfo;
import com.knowledge.ai.pipeline.ParsedDocument;
import com.knowledge.ai.service.ModelUsageRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CyclicBarrier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「锁外描述」那一步（`T-512` 步 5 / 5.1）—— **全替身无网络、无 MinIO、无 Redis**。
 *
 * <h3>为什么这条链必须有用例，而且不能靠集成用例</h3>
 * <p>它在**测试配置里是关掉的**（{@code m05.image.describe.enabled=false}，理由见那个文件 ——
 * 真 VL 40~60 秒/张，开着会让每个含图文档的解析变成分钟级）。关掉之后，
 * 如果没有本类，这条链在跑批里就是**零覆盖** —— 而"关掉等于不测"是本仓明确拒绝的处置
 * （同一份 properties 里连着三条注释都在说这件事）。
 *
 * <h3>它验的是「每张图都有去处」这条恒等式</h3>
 * <p>跳过与失败**都必须被登记**、必须**种类不同**、必须**不牵连文档** ——
 * 三条都是"少了它不会报错，只是慢慢变差"的那类要求。
 *
 * <p>替身一律手写（本仓 pom 没有 Mockito）：替身的行为要能读出来。
 */
class DocumentImageDescriberTest {

    private FakeCaller caller;
    private FakeStorage storage;
    private DocumentImageDescriber describer;

    @BeforeEach
    void setUp() {
        caller = new FakeCaller();
        storage = new FakeStorage();
        VisionDescriber vision = new VisionDescriber(
                caller, new FakeCache(), new ImageCompressor(), new FakeRecorder());
        // 阈值取 12000（与生产默认一致），这样用例里的面积值可以直接对着它读；
        // 并发取 4（生产默认）—— 下面的去重用例要求"派发前就去重"，与并发数无关。
        // 重试取 1（生产默认）：**它会让"失败"用例的调用次数变成 2 次** ——
        // 凡是断言调用次数的用例都要把这一点算进去（下面有专门的用例钉它）
        describer = new DocumentImageDescriber(storage, vision, 12_000d, true, 4, 1);
    }

    // ==================================================================
    // 主路径
    // ==================================================================

    @Test
    @DisplayName("步 5：描述成功 → rawContent = 题注 + 换行 + 描述（题注在前，需求 06:158）")
    void captionComesBeforeDescription() {
        caller.raw = "图中是一条折线，横轴为月份。";
        Block block = imageBlock("图 1 各月营收", 200_000, "md5-a", "images/1/1/a.jpg");

        DocumentImageDescriber.Result result = describer.describe(List.of(block));

        assertThat(result.described()).isEqualTo(1);
        assertThat(result.blocks()).hasSize(1);
        assertThat(result.blocks().getFirst().rawContent())
                .as("题注是作者写的，优先级更高 —— 必须在描述**之前**")
                .isEqualTo("图 1 各月营收\n图中是一条折线，横轴为月份。");
        assertThat(result.blocks().getFirst().imageInfo().vlModelId())
                .as("哪条模型描述的必须记下来 —— T-908 换 VL 时按它筛旧模型产物")
                .isEqualTo("Qwen/Qwen3-VL-8B-Instruct");
        assertThat(result.considered()).isEqualTo(1);
    }

    @Test
    @DisplayName("步 5：没有题注时 rawContent 只有描述（题注稀有是常规路径，不是例外）")
    void descriptionOnlyWhenNoCaption() {
        caller.raw = "一张架构示意图。";
        Block block = imageBlock(null, 200_000, "md5-b", "images/1/1/b.jpg");

        DocumentImageDescriber.Result result = describer.describe(List.of(block));

        assertThat(result.blocks().getFirst().rawContent()).isEqualTo("一张架构示意图。");
    }

    // ==================================================================
    // 过滤（步 5.1）
    // ==================================================================

    @Test
    @DisplayName("步 5.1 过滤①：面积低于阈值 → 不调 VL，登记 IMAGE_SKIPPED（不是失败）")
    void tooSmallIsSkippedNotFailed() {
        Block block = imageBlock("小图标", 5_000, "md5-small", "images/1/1/s.jpg");

        DocumentImageDescriber.Result result = describer.describe(List.of(block));

        assertThat(caller.callCount).as("**一次 VL 都不该调** —— 那正是过滤存在的理由").isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(result.issues()).singleElement()
                .satisfies(i -> assertThat(i.kind())
                        .as("种类必须是 SKIPPED 而不是 DESC_FAILED —— 用户对两者的处置方向相反")
                        .isEqualTo(ParsedDocument.IssueKind.IMAGE_SKIPPED));
        assertThat(result.blocks().getFirst().rawContent())
                .as("被跳过时保留题注 —— 题注仍是能检索的真内容")
                .isEqualTo("小图标");
    }

    @Test
    @DisplayName("步 5.1 过滤②：同篇 imageMd5 重复 → 第二次不再调 VL（需求 06:160）")
    void duplicateMd5DescribedOnce() {
        caller.raw = "公司 logo。";
        Block first = imageBlock("", 200_000, "md5-same", "images/1/1/1.jpg");
        Block second = imageBlock("", 200_000, "md5-same", "images/1/1/2.jpg");

        DocumentImageDescriber.Result result = describer.describe(List.of(first, second));

        assertThat(caller.callCount).as("同一个 md5 只调一次 —— 需求点名的理由就是'公司 logo 这类图'")
                .isEqualTo(1);
        assertThat(result.described()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.considered()).as("两张都进了队列，各有去处").isEqualTo(2);
    }

    // ==================================================================
    // 失败不牵连文档
    // ==================================================================

    @Test
    @DisplayName("步 5：VL 失败 → 登记 IMAGE_DESC_FAILED、保留题注、**不抛异常**")
    void failureDoesNotThrowAndIsRegisteredSeparately() {
        caller.failWith = new IllegalStateException("VL 服务不可达");
        Block block = imageBlock("图 2", 200_000, "md5-c", "images/1/1/c.jpg");

        DocumentImageDescriber.Result result = describer.describe(List.of(block));

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.described()).isZero();
        assertThat(result.issues()).singleElement()
                .satisfies(i -> assertThat(i.kind())
                        .as("失败与跳过**种类不同** —— 判据 25 要的三者可分")
                        .isEqualTo(ParsedDocument.IssueKind.IMAGE_DESC_FAILED));
        assertThat(result.blocks().getFirst().rawContent())
                .as("降级后仍有题注 —— 文档不会因为一张图理解失败而丢掉可用内容")
                .isEqualTo("图 2");
    }

    @Test
    @DisplayName("步 5：描述失败**且没有题注** → 该块被剔除（不产出空内容的向量）")
    void emptyAfterFailureIsDropped() {
        caller.failWith = new IllegalStateException("VL 服务不可达");
        Block block = imageBlock(null, 200_000, "md5-d", "images/1/1/d.jpg");

        DocumentImageDescriber.Result result = describer.describe(List.of(block));

        assertThat(result.blocks())
                .as("rawContent 为空的 IMAGE 块会被投影成空串、嵌进一条空的向量 —— "
                        + "既召回不到东西又占位置，故剔除")
                .isEmpty();
        assertThat(result.failed()).as("剔除不等于不计数 —— 它仍然进过队列").isEqualTo(1);
    }

    // ==================================================================
    // `#209` 重试
    // ==================================================================

    @Test
    @DisplayName("#209：偶发失败后**重试一次就成功** → 那张图不会永久只剩题注")
    void transientFailureIsRescuedByRetry() {
        caller.failFirst = 1;                       // 第一次偶发失败，第二次成功
        caller.raw = "重试之后拿到的描述。";
        Block block = imageBlock("图 4", 200_000, "md5-r", "images/1/1/r.jpg");

        DocumentImageDescriber.Result result = describer.describe(List.of(block));

        assertThat(caller.callCount)
                .as("必须真的调了两次 —— 只调一次说明重试没接上")
                .isEqualTo(2);
        assertThat(result.failed())
                .as("🔴 这次要是一，说明重试白加了 —— 而生产里那 4 张图正是这个形态"
                        + "（S6-M05-C2 §8.12：前两轮失败、第三轮全过）")
                .isZero();
        assertThat(result.described()).isEqualTo(1);
        assertThat(result.blocks().getFirst().rawContent())
                .as("救回来之后内容与一次就成功的**完全一样**（题注在前、描述在后）")
                .isEqualTo("图 4\n重试之后拿到的描述。");
        assertThat(result.issues()).as("救回来了就不该留失败 issue").isEmpty();
    }

    @Test
    @DisplayName("#209：重试上限用尽仍失败 → 照旧降级，且 reason 里写明**试了几次**")
    void retriesExhaustedStillDegradesWithVisibleAttemptCount() {
        caller.failWith = new IllegalStateException("VL 服务不可达");
        Block block = imageBlock("图 5", 200_000, "md5-x", "images/1/1/x.jpg");

        DocumentImageDescriber.Result result = describer.describe(List.of(block));

        assertThat(caller.callCount)
                .as("重试上限 1 ⇒ 总共 2 次").isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.issues()).singleElement()
                .satisfies(i -> assertThat(i.reason())
                        .as("🔴 必须写明**试了几次** —— 「试过 2 次仍失败」与「一次就失败」"
                                + "对排查的含义完全不同（前者是端点持续不可用，后者可能只是抖动）")
                        .contains("已尝试 2 次"));
    }

    @Test
    @DisplayName("🔴 #209 负向锚：**成功的那些一次都不多调** —— 重试不许变成「每次都调两遍」")
    void successfulOnesAreNeverCalledTwice() {
        caller.raw = "一次就好。";
        List<Block> blocks = List.of(
                imageBlock("甲", 200_000, "m-1", "images/1/1/1.jpg"),
                imageBlock("乙", 200_000, "m-2", "images/1/1/2.jpg"),
                imageBlock("丙", 200_000, "m-3", "images/1/1/3.jpg"));

        DocumentImageDescriber.Result result = describer.describe(blocks);

        assertThat(result.described()).isEqualTo(3);
        assertThat(caller.callCount)
                .as("🔴 这张锚值是必需的：把重试写成「每张都先试两次」照样能让上面那条用例绿，"
                        + "而它会让**正常路径的调用次数翻倍** —— 那是每分钟都在烧的钱")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("🔴 #209：重试上限配 0 ⇒ 退回不重试（一次性杀掉这个行为的能力）")
    void retryZeroFallsBackToNoRetry() {
        caller.failFirst = 1;
        caller.raw = "本来能拿到的描述。";
        DocumentImageDescriber noRetry = new DocumentImageDescriber(
                storage, new VisionDescriber(caller, new FakeCache(), new ImageCompressor(),
                new FakeRecorder()), 12_000d, true, 4, 0);
        Block block = imageBlock("图 6", 200_000, "md5-z", "images/1/1/z.jpg");

        DocumentImageDescriber.Result result = noRetry.describe(List.of(block));

        assertThat(caller.callCount).isEqualTo(1);
        assertThat(result.failed())
                .as("关掉重试之后行为与 `S6-M05-C2` 之前**逐字一致** —— 这是回退开关")
                .isEqualTo(1);
    }

    // ==================================================================
    // 恒等式与开关
    // ==================================================================

    @Test
    @DisplayName("恒等式：described + skipped + failed == considered（每张图都有去处）")
    void everyImageIsAccountedFor() {
        caller.raw = "描述。";
        List<Block> blocks = List.of(
                imageBlock("大图一", 200_000, "md5-1", "images/1/1/1.jpg"),
                imageBlock("小图标", 1_000, "md5-2", "images/1/1/2.jpg"),
                imageBlock("大图一（重复）", 200_000, "md5-1", "images/1/1/3.jpg"),
                imageBlock("大图三", 200_000, "md5-3", "images/1/1/4.jpg"));

        DocumentImageDescriber.Result result = describer.describe(blocks);

        assertThat(result.considered()).as("四张都进过队列").isEqualTo(4);
        assertThat(result.described()).isEqualTo(2);   // md5-1 与 md5-3
        assertThat(result.skipped()).isEqualTo(2);     // 小图标 + md5-1 重复
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("开关关掉（测试配置的常态）：块**原样返回**，不调 VL、不留 issue")
    void disabledReturnsBlocksUntouched() {
        DocumentImageDescriber off = new DocumentImageDescriber(
                storage, new VisionDescriber(caller, new FakeCache(),
                new ImageCompressor(), new FakeRecorder()), 12_000d, false, 4, 1);
        List<Block> blocks = List.of(imageBlock("图 3", 200_000, "md5-e", "images/1/1/e.jpg"));

        DocumentImageDescriber.Result result = off.describe(blocks);

        assertThat(result.blocks()).as("原样返回 —— 图片块仍带题注进索引，只是没有描述").isSameAs(blocks);
        assertThat(caller.callCount).isZero();
        assertThat(result.issues())
                .as("关掉是'这个环境没开'，不是'这张图理解不了' —— 不留 issue")
                .isEmpty();
    }

    // ==================================================================
    // 并行（2026-09-19 改）
    // ==================================================================

    @Test
    @DisplayName("并行：去重**必须在派发之前**做完（同 md5 只调一次，与并发无关）")
    void dedupHappensBeforeDispatch() {
        caller.raw = "公司 logo。";
        // 同一张 logo 出现 4 次 —— 若去重被挪到并行之后，就会**并发调 4 次**
        //（那正是需求 06:160 点名的"公司 logo 这类图"，会重复花钱且结果可能不一致）
        List<Block> blocks = List.of(
                imageBlock("", 200_000, "logo-md5", "images/1/1/a.jpg"),
                imageBlock("", 200_000, "logo-md5", "images/1/1/b.jpg"),
                imageBlock("", 200_000, "logo-md5", "images/1/1/c.jpg"),
                imageBlock("", 200_000, "logo-md5", "images/1/1/d.jpg"));

        DocumentImageDescriber.Result result = describer.describe(blocks);

        assertThat(caller.callCount)
                .as("同一 md5 只允许调一次 —— 并行之后这条只能靠「派发前分诊」保证")
                .isEqualTo(1);
        assertThat(result.described()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(3);
    }

    @Test
    @DisplayName("并行：结果**按下标保序**（不随完成顺序变）")
    void resultsKeepInputOrder() {
        caller.raw = "同一段描述。";
        // ⚠️ 分不清"哪张"是替身的固有局限（`describeOne` 传的 presignedUrl 是 null，
        //    而三张图的字节又都一样）—— 但**题注前缀来自块本身**，足以定位。
        //    判据因此落在"每个位置的题注对不对"，而不是"模型返回了哪句话"。
        List<Block> blocks = List.of(
                imageBlock("图一", 200_000, "md5-1", "images/1/1/1.jpg"),
                imageBlock("图二", 200_000, "md5-2", "images/1/1/2.jpg"),
                imageBlock("图三", 200_000, "md5-3", "images/1/1/3.jpg"));

        DocumentImageDescriber.Result result = describer.describe(blocks);

        assertThat(result.blocks()).hasSize(3);
        // ⚠️ 判据落在**题注的出现顺序**上，而不是 `orderNo` —— 后者由 `imageBlock` 助手
        //    统一给 1（那是块自己的字段，本用例没在验它）。而题注前缀来自入参的块、按位对应，
        //    所以"顺序对不对"在它上面一眼可见。题注在第一行（`withDescription` 拼的）。
        assertThat(result.blocks()).extracting(b -> b.rawContent().split("\n")[0])
                .as("题注的出现顺序必须与入参一致 —— 顺序是回表与详情页的阅读顺序，"
                        + "绝不能随网络抖动而变")
                .containsExactly("图一", "图二", "图三");
    }

    @Test
    @DisplayName("并行：真的并行了（4 张图必须**同时在**模型里 —— 串行会让它超时）")
    void actuallyRunsInParallel() {
        int n = 4;
        DocumentImageDescriber parallel = new DocumentImageDescriber(
                storage, new VisionDescriber(new RendezvousCaller(n), new FakeCache(),
                new ImageCompressor(), new FakeRecorder()), 12_000d, true, n, 1);
        List<Block> blocks = List.of(
                imageBlock("一", 200_000, "p-1", "images/1/1/1.jpg"),
                imageBlock("二", 200_000, "p-2", "images/1/1/2.jpg"),
                imageBlock("三", 200_000, "p-3", "images/1/1/3.jpg"),
                imageBlock("四", 200_000, "p-4", "images/1/1/4.jpg"));

        DocumentImageDescriber.Result result = parallel.describe(blocks);

        // 替身要求 4 个调用**在栅栏上会合**才返回；串行的话第一个就会等超时 ⇒ 全失败。
        // 所以"4 张全描述成功"本身就是"它们真的同时在跑"的证据。
        assertThat(result.described())
                .as("4 张必须全部成功 —— 替身要求它们在栅栏上会合，串行会让第一个就超时")
                .isEqualTo(n);
        assertThat(result.failed()).isZero();
    }

    // ==================================================================
    // 替身
    // ==================================================================

    /** 造一个图片块。面积 = 宽 × 高，直接对着阈值读。 */
    private static Block imageBlock(String caption, double area, String md5, String objectKey) {
        // 正方形最省事：边长 = sqrt(area)
        double side = Math.sqrt(area);
        // 第 7 位（phash）留 null：描述链不碰它，装饰图判定才碰（`T-O03`）
        ImageInfo info = new ImageInfo(1, new ImageInfo.Box(0, 0, side, side),
                caption == null || caption.isBlank() ? null : caption, objectKey, md5, null, null);
        return new Block(caption == null ? "" : caption, BlockType.IMAGE, "1. 总览",
                1, 1, null, null, info);
    }

    private static final class FakeStorage extends DocStorage {

        FakeStorage() {
            super(null, null);
        }

        @Override
        public byte[] open(String objectKey) {
            return new byte[]{1, 2, 3};
        }
    }

    private static final class FakeCache extends VisionDescriptionCache {

        FakeCache() {
            super(null);
        }

        @Override
        public VisionResult get(String md5, String vlModelId) {
            return null;   // 恒未命中：用例要的是"每次都真调"，缓存另有专测
        }

        @Override
        public void put(String md5, String vlModelId, VisionResult result) {
            // 不写：本类验的是块内容，不是缓存
        }
    }

    private static final class FakeRecorder extends ModelUsageRecorder {

        FakeRecorder() {
            super(null);
        }

        @Override
        public void record(ModelType type, String modelId, String modelName,
                           Purpose purpose, int calls, long promptTokens,
                           long completionTokens, Integer latencyMs) {
            // 用量另有专测
        }
    }

    /**
     * **只证明"真的并行了"** 的替身：每个调用都要在栅栏上**会合**才返回。
     *
     * <p>串行执行时，第一个调用会独自等满超时然后抛 —— 于是"全部成功"就等价于
     * "它们确实同时在跑"。这比断言墙钟时间稳（计时断言会 flaky），
     * 又能抓住"把并发上限写成 1"这种退化。
     */
    private static final class RendezvousCaller implements VisionCaller {

        private final CyclicBarrier barrier;

        RendezvousCaller(int parties) {
            this.barrier = new CyclicBarrier(parties);
        }

        @Override
        public VisionRaw call(String prompt, byte[] image, String presignedUrl, String mime) {
            try {
                // 超时给得短：串行时第一个调用会在这里等满，用例当场变红而不是挂住
                barrier.await(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ex) {
                throw new IllegalStateException(
                        "没有等到其余并发调用会合（说明它们是**串行**跑的）：" + ex);
            }
            return VisionRaw.of("会合成功。");
        }

        @Override
        public VisionModelRef modelRef() {
            return new VisionModelRef("4", "Qwen/Qwen3-VL-8B-Instruct");
        }
    }

    /** 与 {@code VisionDescriberTest} 的替身同形（那边是私有的，这里不外借）。 */
    private static final class FakeCaller implements VisionCaller {

        String raw = "";
        RuntimeException failWith;
        /**
         * 前几次调用**偶发失败**（`#209` 的重试用例），之后成功。
         *
         * <p>与 {@link #failWith} 的分工：后者是"一直失败"（测降级），
         * 这个是"失败一阵就好了"（测重试真的能救回来）——
         * 而后者才是生产里的实际形态（`S6-M05-C2` §8.12：同一批图第一轮全失败、第三轮全过）。
         */
        int failFirst;
        int callCount;
        final VisionModelRef model = new VisionModelRef("4", "Qwen/Qwen3-VL-8B-Instruct");

        @Override
        public VisionRaw call(String prompt, byte[] image, String presignedUrl, String mime) {
            callCount++;
            if (failWith != null) {
                throw failWith;
            }
            if (callCount <= failFirst) {
                throw new IllegalStateException("偶发失败（第 " + callCount + " 次调用）");
            }
            return VisionRaw.of(raw);
        }

        @Override
        public VisionModelRef modelRef() {
            return model;
        }
    }
}
