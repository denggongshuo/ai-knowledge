package com.knowledge.ai.pipeline.vision;

import com.knowledge.ai.core.enums.BlockType;
import com.knowledge.ai.core.storage.DocStorage;
import com.knowledge.ai.pipeline.Block;
import com.knowledge.ai.pipeline.ImageInfo;
import com.knowledge.ai.pipeline.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * **文档侧的图片描述**（`T-512` 步 5）—— 把 {@code IMAGE} 块从"只有题注"补成"题注 + VL 描述"。
 *
 * <h3>🔴 为什么它必须在解析锁**之外**</h3>
 * <p>{@code PdfDocumentParser} 有一把**全局串行锁**（{@code LOCK}），而真 VL 实测
 * **40~60 秒/张**（`#179`）。`1-DeepSeek使用.pdf` 有 **26 张可描述的图** ⇒ 若描述发生在
 * 解析期，单篇就是 **≈20 分钟**，而这 20 分钟里**全库的解析全被那把锁挡着**。
 *
 * <p>所以整条链被切成两段，**这是本批最主要的结构决定**：
 * <pre>
 *   🔒 锁内（{@code PdfDocumentParser}）  ：抽字节 + 算 md5 + 落 MinIO  ← 快，字节只在那儿
 *   🔓 锁外（本类）                        ：逐张调 VL 写进块            ← 慢，绝不许进锁
 * </pre>
 *
 * <h3>位置：`chunk` 之后、`replaceChunks` 之前 —— 两条都是硬约束</h3>
 * <ol>
 *   <li>必须在 {@code chunk} <b>之后</b>：要知道哪些是 `IMAGE` 块、要拿它们的 {@code orderNo}；</li>
 *   <li>必须在 {@code replaceChunks} <b>之前</b>：块落库后 {@code rawContent} **不再改写**
 *       （`O8` 的「块存事实」）—— 那之后想补描述就只能重新解析。</li>
 * </ol>
 *
 * <h3>降级矩阵：任何一张图出问题都**不牵连文档**</h3>
 * <p>与 {@code IndexEnhancer} 同一条取舍 —— 描述是**增强**。一份图多的手册因为
 * VL 挂了就整篇判失败，等于用一件增强功能的故障否掉一篇可用文档。
 * 所以这里**不抛**：失败的图保持"只有题注"，原因进 issue + 日志。
 *
 * <h3>先过滤，再调 VL（步 5.1）</h3>
 * <p>真 VL 一分钟一张，不能来者不拒。两道过滤（见 {@link #describe}）：
 * ① 尺寸下限；② 同篇内 `imageMd5` 去重（需求 `06:160`）。
 * 被跳过的图**登记进 issue**，且用**独立的种类** `IMAGE_SKIPPED` ——
 * 让「没去理解」与「理解失败」在页面上长得不一样。
 */
@Slf4j
@Service
public class DocumentImageDescriber {

    /**
     * 描述的最小**包围盒面积**（PDF 用户空间单位²，可配）。
     *
     * <h3>这个数是怎么来的，以及它有多硬</h3>
     * <p>⚠️ **它是拍的，而且本仓规矩是"阈值先量再定"** —— 所以它被标成可配、
     * 并由 `T-512` 判据 15（步 9b 的探针）去量：过滤后每篇还剩几张、真 VL 每张多久。
     * **探针的产出很可能是否掉这个数**（本仓先例：`O2` 建议的页级阈值 10 就是被探针
     * 否掉的 —— 实测会让 4 份正常文档里的 7 个页面误报）。那**也是产出**。
     *
     * <p>取值依据（探针实测的包围盒面积）：`1-DeepSeek使用.pdf` 里"没有可导出位图"
     * 的那批——正是小装饰——中位面积 **14,988**；而有字节的那批中位 **114,094**。
     * 所以 12000 卡在两者之间偏下：**它主要拦小图标，不太可能拦到真图**。
     * ⚠️ 但这条推理建立在一个样本上，且面积单位是 PDF 用户空间而非像素 ——
     * 换一份图尺寸体系不同的 PDF 就可能失准。**故它可配、且待量**。
     */
    private static final double DEFAULT_MIN_AREA = 12_000d;

    private final DocStorage docStorage;
    private final VisionDescriber visionDescriber;
    private final double minArea;

    /**
     * 开关（默认**开**）。
     *
     * <h3>为什么需要它 —— 不是为了省事，是因为它慢得会影响别的批次</h3>
     * <p>真 VL **40~60 秒/张**。开着它，任何含图文档的解析都要多花十几分钟 ——
     * 而本仓有一大批 Spring 集成用例会真的上传并解析文档（`DocParseFlowTest` 等），
     * 它们**不该**因为一次图片理解而变成分钟级。
     *
     * <p>这与 {@code m05.enhance.enabled} / {@code m05.vectorize.enabled} 是**同一个形状**：
     * 生产默认开、测试配置里关掉，真模型那条路由
     * {@code @Tag("network")} 的专用用例覆盖。所以它不是"为了跑得快而关掉功能"，
     * 是"把慢的网络依赖从默认路径里摘出来，让它只在被显式启用时发生"。
     *
     * <p>⚠️ 关掉时**不留 issue**（那是"这个环境没开"，不是"这张图理解不了"）——
     * 与 {@code ChunkVectorizer} 关掉时只记日志、不标红同一口径。
     */
    private final boolean enabled;

    /**
     * 同时最多几张图在调 VL（`T-512` 步 5 的并行上限，可配）。
     *
     * <h3>为什么要有这个数，以及为什么默认是 4</h3>
     * <p>改并行之前是**逐张串行**：单张实测 **95~115 秒**（比上游记录的 40~60 秒更慢），
     * `1-DeepSeek` 那份 26 张 ⇒ **单篇约 45 分钟**。
     * <p>{@code VisionConcurrencyProbeTest} 实测端点**真并行**（并发 8 时吞吐 11.3 次/分，
     * 串行只有 0.5），所以并行是本批收益最大的一处改动。
     *
     * <p>⚠️ 但**默认取 4 而不是 8**，是有意的保守：端点是**外部付费云服务**，
     * 实测只跑了一轮、没被限流，而"没观测到限流"不等于"不会限流"。
     * 并发 4 已把 45 分钟压到约 5 分钟；要更快就调这个值（8 实测也没问题）。
     */
    private final int concurrency;

    /**
     * 失败后**额外再试几次**（`#209`）。{@code 0} = 不重试（退回本批之前的行为）。
     *
     * <h3>为什么需要它 —— 一次偶发失败会让那张图"永久"没有描述</h3>
     * <p>`S6-M05-C2` §8.12 实测：26 张里有 4 张连续两轮被端点拒绝，而**第三轮全过了**
     * （真调的 4 张 md5 与前两轮失败的那 4 张**逐字相同**）。
     * ⇒ 那不是"这张图端点就是不收"，是**偶发**；而原来的实现里，偶发一次就等于
     * 那张图**永久**退化成只有题注，用户唯一的补救是**重传整篇文档**。
     *
     * <h3>⚠️ 默认值 1 的采样边界（如实写，别当成标定过的数）</h3>
     * <p>依据是 `C2` §8.12 那 **4 张**的观察（"第 2 次重试成功 4/4"），**n=4**。
     * 它足以支持"加一次重试"，**不足以支持"一次就够了"** —— 所以它是可配的，
     * 且真 VL 那条用例（`ImageDescribeNetworkTest`）会把"重试救回几张"打出来。
     * 若那个数长期 > 0，该调的是这个值，不是再加一层重试。
     *
     * <h3>为什么不做退避</h3>
     * <p>刻意**不加**：退避的长度同样要有依据，而手上没有"隔多久重试才有效"的数据。
     * 先做**立即重试**并把它跑出数字 —— 若量出立即重试无效而隔几秒有效，那时再加。
     * （凭感觉写一个 500ms，是把一个没有依据的数藏进代码里。）
     */
    private final int retry;

    public DocumentImageDescriber(DocStorage docStorage,
                                  VisionDescriber visionDescriber,
                                  @Value("${m05.image.min-area:12000}") double minArea,
                                  @Value("${m05.image.describe.enabled:true}") boolean enabled,
                                  @Value("${m05.image.describe.concurrency:4}") int concurrency,
                                  @Value("${m05.image.describe.retry:1}") int retry) {
        this.docStorage = docStorage;
        this.visionDescriber = visionDescriber;
        this.minArea = minArea;
        this.enabled = enabled;
        this.concurrency = concurrency;
        // 负数当 0：配置写错时**退回不重试**而不是抛 —— 这条链上代价是"少一份描述"，
        // 为它让整篇文档的解析失败，量级不对（与 `compressor` 那几处的取舍同源）
        this.retry = Math.max(0, retry);
    }

    /**
     * 逐块补描述 —— **三阶段：分诊（串行）→ 并行描述 → 组装（串行、保序）**。
     *
     * @param blocks 分片产出（本方法**不改写**入参，返回新列表）
     * @return 新块列表 + 三个计数 + 新增的 issue
     */
    public Result describe(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return new Result(List.of(), 0, 0, 0, List.of());
        }
        if (!enabled) {
            // ⚠️ 这里**原样返回入参**而不是过滤一遍：关掉开关时图片块仍带着题注进索引
            //     （题注是作者写的真内容），只是没有描述。过滤会把它们也丢掉。
            log.info("图片描述开关未开启（m05.image.describe.enabled=false），跳过：块数={}", blocks.size());
            return new Result(blocks, 0, 0, 0, List.of());
        }

        // `out` 按**入参下标**占位（末尾统一剔掉 null）。🔴 并行之后"保序"只能靠下标 ——
        // 按完成顺序追加会让块序随网络抖动而变，而它是回表与详情页的阅读顺序
        List<Block> out = new ArrayList<>(java.util.Collections.nCopies(blocks.size(), null));
        List<ParsedDocument.DocumentIssue> issues = new ArrayList<>();
        Set<String> seenMd5 = new HashSet<>();
        List<Candidate> candidates = new ArrayList<>();
        int skipped = 0;

        // ==================== 阶段 1 · 分诊（串行）====================
        // 🔴 **去重必须在这里做完，绝不能挪到派发之后**（本次改并行最要紧的一条）：
        //    "同篇 imageMd5 只调一次"靠的是"谁先到谁被描述"。并行派发之后那个顺序就没了
        //    ⇒ 同一个 md5 会被**并发调两次**，而那正是需求 06:160 点名的
        //    「公司 logo 这类图」要防的事（重复花钱，且两次结果可能不一致）。
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            ImageInfo info = block.imageInfo();
            if (block.blockType() != BlockType.IMAGE || info == null) {
                out.set(i, block);
                continue;
            }
            // 没字节的图在**解析期**就登记过 `IMAGE_NOT_EXTRACTABLE` 了，这里无事可做。
            // 但带题注的块要留着 —— 题注是作者写的真内容，能进索引（需求 06:158）
            if (!info.hasBytes()) {
                out.set(i, withContentOrNull(block));
                continue;
            }
            // ---- 过滤① 尺寸下限 ----
            Double area = info.box() == null ? null : info.box().area();
            if (area != null && area > 0 && area < minArea) {
                skipped++;
                issues.add(skipIssue(block, "该图面积过小（" + Math.round(area)
                        + " < 阈值 " + Math.round(minArea) + "），判定为图标或分隔线，"
                        + "未调用图片理解；若它确有内容，请告知管理员调低 " + "m05.image.min-area"));
                out.set(i, withContentOrNull(block));
                continue;
            }
            // ---- 过滤② 同篇 md5 去重（需求 06:160 的原话理由：「公司 logo 这类图」）----
            if (info.md5() != null && !info.md5().isBlank() && !seenMd5.add(info.md5())) {
                skipped++;
                issues.add(skipIssue(block, "该图与本文档中另一张图内容相同（imageMd5 重复），"
                        + "已复用同一份理解结果、不重复调用；这是需求 06:160 点名的去重"));
                out.set(i, withContentOrNull(block));
                continue;
            }
            candidates.add(new Candidate(i, block, info));
        }

        // ==================== 阶段 2 · 并行描述 ====================
        Attempt[] attempts = describeAll(candidates);

        // ==================== 阶段 3 · 组装（串行，按下标保序）====================
        int described = 0;
        int failed = 0;
        int rescuedByRetry = 0;
        for (int i = 0; i < attempts.length; i++) {
            Attempt attempt = attempts[i];
            if (attempt == null) {
                // 走到这里说明**任务本身**炸了（不是"描述失败"）—— `describeOne` 从不抛。
                // 按失败计并保留原块，绝不留一个 NPE（那会看着像代码缺陷而不是外部故障）
                Candidate candidate = candidates.get(i);
                failed++;
                issues.add(ParsedDocument.DocumentIssue.missing(candidate.block().pageNo(),
                        "第 " + candidate.block().pageNo() + " 页的图片描述任务未能完成（内部异常），"
                                + "该图暂以题注参与检索；请重新上传该文档",
                        ParsedDocument.IssueKind.IMAGE_DESC_FAILED));
                out.set(candidate.slot(), withContentOrNull(candidate.block()));
                continue;
            }
            if (attempt.failure() != null) {
                failed++;
                issues.add(ParsedDocument.DocumentIssue.missing(attempt.block().pageNo(),
                        "第 " + attempt.block().pageNo() + " 页的图片理解失败：" + attempt.failure()
                                + "；该图暂以题注参与检索，请重新上传该文档",
                        ParsedDocument.IssueKind.IMAGE_DESC_FAILED));
                out.set(attempt.slot(), withContentOrNull(attempt.block()));
            } else {
                described++;
                if (attempt.attempts() > 1) {
                    rescuedByRetry++;
                }
                out.set(attempt.slot(),
                        withDescription(attempt.block(), attempt.info(), attempt.result()));
            }
        }

        List<Block> kept = out.stream().filter(java.util.Objects::nonNull).toList();
        // ⚠️ `重试救回` 与 `重试上限` 必须出现在这一行里：它是"retry 该取多少"的证据来源。
        //    只有"成功 N 张"的话，看不出这 N 张里有多少是本可以不花的钱
        log.info("图片描述完成：考虑={} 成功={}（其中重试救回 {}）跳过={} 失败={} 并发上限={} 重试上限={}",
                described + skipped + failed, described, rescuedByRetry, skipped, failed, concurrency, retry);
        return new Result(kept, described, skipped, failed, List.copyOf(issues));
    }

    /**
     * 并发描述，**结果与入参等长同序**（某个下标为 {@code null} = 该任务本身炸了）。
     *
     * <h3>为什么不用 {@code VisionDescriber#describeAll}</h3>
     * <p>它的契约是"返回列表与输入**等长同序**"，而本类要做的是**去重之后张数变少**
     * —— 两个语义对不上。而且它内部不设并发上限（聊天侧最多 3 张，不需要）。
     * 所以这里自己编排，只复用它**每一张**的描述逻辑（{@link VisionDescriber#describe}）。
     *
     * <h3>并发上限为什么必须给</h3>
     * <p>端点是**外部付费云服务**。实测它真并行（并发 8 时吞吐 **11.3 次/分**，
     * 而串行只有 0.5 —— 见 {@code VisionConcurrencyProbeTest}），
     * 但**没有上限的并发**会同时打爆速率限制与账单。上限做成配置（{@link #concurrency}）。
     *
     * <h3>为什么用数组收集结果，而不是 {@code List}</h3>
     * <p>{@code ArrayList.set} 在多线程下**不是**线程安全的，包一层
     * {@code Collections.synchronizedList} 又要额外加锁。数组的**引用写入是原子的**，
     * 且每个任务只写自己的下标；{@code Future.get()} 提供 happens-before，
     * 所以汇总时能看见全部写入。
     */
    private Attempt[] describeAll(List<Candidate> candidates) {
        Attempt[] attempts = new Attempt[candidates.size()];
        if (candidates.isEmpty()) {
            return attempts;
        }
        if (candidates.size() == 1) {
            // 单张不起线程：启一个虚拟线程比干活还贵，而单张是**最常见**的情形
            attempts[0] = describeOne(candidates.getFirst());
            return attempts;
        }
        Semaphore permits = new Semaphore(Math.max(1, concurrency));
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                int slot = i;
                Candidate candidate = candidates.get(i);
                futures.add(pool.submit(() -> {
                    try {
                        permits.acquire();
                        try {
                            attempts[slot] = describeOne(candidate);
                        } finally {
                            permits.release();
                        }
                    } catch (InterruptedException ex) {
                        // 中断 = 服务在停。补一个"失败"而不是留 null ——
                        // null 会在阶段 3 走"任务炸了"那条路，而那描述的**不是**这里发生的事
                        Thread.currentThread().interrupt();
                        attempts[slot] = Attempt.failed(candidate, "描述被中断（服务正在停止）", 0);
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                awaitQuietly(future);
            }
        }
        return attempts;
    }

    /** 等一个并行任务。**不抛**：`describeOne` 已经把失败折成结果，这里只兜"任务本身炸了"。 */
    private static void awaitQuietly(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            // 阶段 3 会按"attempts[i] == null"兜底，所以这里只留痕
            log.warn("图片描述任务异常（该图会退回题注）：{}", ex.getMessage());
        }
    }

    /**
     * 描述一张图，**失败就再试**（`#209`）。**绝不抛** —— 任何失败都折成 {@link Attempt#failure()}。
     *
     * <h3>为什么重试写在这里，而不是外面再包一轮</h3>
     * <p>因为"重试"要的粒度是**一张图**，而外面那一层拿到的是"这一批的下标数组"
     * （重试就得把那批里失败的下标挑出来再编一次并发）—— 那是把并发编排写两遍。
     * 收在这里之后，并发上限仍然只由 {@link #concurrency} 管：重试发生在**同一个任务内**，
     * 不会因为重试把在途请求数顶上去。
     *
     * <h3>为什么读字节**不**在重试范围内</h3>
     * <p>对象读不到 = 那个 key 指向的东西没了，**不是瞬时故障** —— 重试只是把同一个失败
     * 再问一遍，白等一轮。而 VL 的失败才是这次要接住的偶发。
     */
    private Attempt describeOne(Candidate candidate) {
        ImageInfo info = candidate.info();
        byte[] bytes;
        try {
            bytes = docStorage.open(info.objectKey());
        } catch (RuntimeException ex) {
            return Attempt.failed(candidate, "读取图片对象失败：" + ex.getMessage(), 0);
        }

        int maxAttempts = retry + 1;
        String lastFailure = "VL 返回空或调用失败";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                VisionResult result = visionDescriber.describe(new VisionDescriber.VisionInput(
                        info.md5(), bytes, null, "image/jpeg",
                        // ⚠️ **文档解析期传 null** —— 这里没有"用户的问题"。
                        //    本行此前是 VisionDescriber 里那句"文档解析期传 null"的**唯一落点**，
                        //    `T-512` 步 5 起它真的被传了（此前那条 javadoc 是承诺，不是事实）
                        null));
                if (result.hasDescription()) {
                    if (attempt > 1) {
                        // 这条日志是 `retry` 该取多少的**唯一数据来源** —— 别把它降成 debug
                        log.info("图片描述重试成功：第 {} 次尝试（md5={}）", attempt, info.md5());
                    }
                    return new Attempt(candidate.slot(), candidate.block(), info, result, null, attempt);
                }
            } catch (RuntimeException ex) {
                // VL 抛了 —— **不牵连文档**，但要把原因带下去（fail 的 reason 会进 parse issue）
                lastFailure = ex.getMessage();
            }
        }
        return Attempt.failed(candidate,
                lastFailure + (maxAttempts > 1 ? "（已尝试 " + maxAttempts + " 次）" : ""), maxAttempts);
    }

    /**
     * 把描述拼进块内容：**题注在前、描述在后**（需求 `06:158` 的"题注优先级更高"）。
     */
    private static Block withDescription(Block block, ImageInfo info, VisionResult result) {
        String content = info.hasCaption()
                ? info.caption() + "\n" + result.desc()
                : result.desc();
        return new Block(content, block.blockType(), block.titlePath(), block.orderNo(),
                block.pageNo(), block.parentOrderNo(), block.sourceOrderNo(),
                info.withVlModel(result.vlModelName()));
    }

    /**
     * 内容为空的图片块返回 {@code null}（由调用方剔除）。
     *
     * <p>空 `rawContent` 的 `IMAGE` 块会被 {@code ChunkViews.IMAGE_DESC} 投影成空串，
     * 而空串会被**嵌进向量**（`ChunkVectorizer` 只按"该不该进池"筛，不按"文本空不空"）——
     * 一条内容为空的向量既召回不到任何东西，又占一个位置。
     * `ChunkingService` 已经在源头拦了一道（没字节又没题注的不产出块），
     * 这里是第二道：**描述失败/被跳过之后**才变空的那些。
     */
    private static Block withContentOrNull(Block block) {
        return block.rawContent() != null && !block.rawContent().isBlank() ? block : null;
    }

    /** 待描述的一张图：它在结果里的**下标**、原块、以及图片身份。 */
    private record Candidate(int slot, Block block, ImageInfo info) {
    }

    /**
     * 一次描述尝试。{@code failure != null} ⇒ 失败（`result` 为 {@code null}）。
     *
     * @param attempts 这件事**实际发生了几次调用**（`#209` 起）。
     *                 ⚠️ 它不参与判定，只用于把"重试救回来的"与"一次就成了的"分开计数 ——
     *                 而那个数正是 {@code m05.image.describe.retry} 该取多少的依据。
     *                 没有它，日志里只能看到"成功了 N 张"，看不出重试值不值
     */
    private record Attempt(int slot, Block block, ImageInfo info, VisionResult result, String failure,
                           int attempts) {

        static Attempt failed(Candidate candidate, String reason, int attempts) {
            return new Attempt(candidate.slot(), candidate.block(), candidate.info(), null, reason, attempts);
        }
    }

    /**
     * 跳过型 issue —— 用 {@code skipped(..)} 而不是 {@code missing(..)}。
     *
     * <p>差别只在 {@code disposition}，而它决定**告警条认不认**：
     * 图标与重复 logo 几乎每份带图文档都有，记成"内容缺失"会让告警条常态化变红，
     * 把真正该看的"图片理解失败"淹掉（本仓「误报比漏报更伤」）。
     */
    private static ParsedDocument.DocumentIssue skipIssue(Block block, String reason) {
        return ParsedDocument.DocumentIssue.skipped(block.pageNo(),
                "第 " + block.pageNo() + " 页的图片未进入理解队列：" + reason,
                ParsedDocument.IssueKind.IMAGE_SKIPPED);
    }

    /**
     * 一次描述的结果。
     *
     * @param blocks    新的块列表（内容为空的图片块已被剔除）
     * @param described 成功描述的张数 → 回填 {@code sys_document.image_parsed}
     * @param skipped   按策略跳过的张数（未达尺寸阈值 / 同图重复）
     * @param failed    调用失败的张数（VL 不可用 / 对象读不到）
     * @param issues    新增的逐页问题（跳过与失败都登记，**两者种类不同**）
     */
    public record Result(List<Block> blocks, int described, int skipped, int failed,
                         List<ParsedDocument.DocumentIssue> issues) {

        /**
         * 进入描述队列的张数 —— **恒等于** {@code described + skipped + failed}。
         *
         * <p>把这条恒等式做成方法而不是散在调用方，是因为它是"每张图都有去处"的可证形式：
         * 一旦有人加了个 {@code continue} 而忘了计数，这里当场就对不上。
         */
        public int considered() {
            return described + skipped + failed;
        }
    }
}
