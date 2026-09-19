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

    public DocumentImageDescriber(DocStorage docStorage,
                                  VisionDescriber visionDescriber,
                                  @Value("${m05.image.min-area:12000}") double minArea,
                                  @Value("${m05.image.describe.enabled:true}") boolean enabled) {
        this.docStorage = docStorage;
        this.visionDescriber = visionDescriber;
        this.minArea = minArea;
        this.enabled = enabled;
    }

    /**
     * 逐块补描述。
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
        List<Block> out = new ArrayList<>(blocks.size());
        List<ParsedDocument.DocumentIssue> issues = new ArrayList<>();
        // 同篇去重（需求 06:160）。**只在篇内**去重 —— 跨文档是语料级的，
        // 与 DocumentCleaner「无状态纯函数」的设计冲突，本批不做（见计划 §9 W4）
        Set<String> seenMd5 = new HashSet<>();
        int described = 0;
        int skipped = 0;
        int failed = 0;

        for (Block block : blocks) {
            ImageInfo info = block.imageInfo();
            if (block.blockType() != BlockType.IMAGE || info == null) {
                out.add(block);
                continue;
            }
            // 没字节的图在**解析期**就登记过 `IMAGE_NOT_EXTRACTABLE` 了，这里无事可做。
            // 但带题注的块要留着 —— 题注是作者写的真内容，能进索引（需求 06:158）
            if (!info.hasBytes()) {
                addIfHasContent(out, block);
                continue;
            }
            // ---- 过滤① 尺寸下限 ----
            Double area = info.box() == null ? null : info.box().area();
            if (area != null && area > 0 && area < minArea) {
                skipped++;
                issues.add(skipIssue(block, "该图面积过小（" + Math.round(area)
                        + " < 阈值 " + Math.round(minArea) + "），判定为图标或分隔线，"
                        + "未调用图片理解；若它确有内容，请告知管理员调低 " + "m05.image.min-area"));
                addIfHasContent(out, block);
                continue;
            }
            // ---- 过滤② 同篇 md5 去重（需求 06:160 的原话理由：「公司 logo 这类图」）----
            if (info.md5() != null && !info.md5().isBlank() && !seenMd5.add(info.md5())) {
                skipped++;
                issues.add(skipIssue(block, "该图与本文档中另一张图内容相同（imageMd5 重复），"
                        + "已复用同一份理解结果、不重复调用；这是需求 06:160 点名的去重"));
                addIfHasContent(out, block);
                continue;
            }
            // ---- 真调 VL ----
            try {
                byte[] bytes = docStorage.open(info.objectKey());
                VisionResult result = visionDescriber.describe(new VisionDescriber.VisionInput(
                        info.md5(), bytes, null, "image/jpeg",
                        // ⚠️ **文档解析期传 null** —— 这里没有"用户的问题"。
                        //    本行此前是 VisionDescriber 里那句"文档解析期传 null"的**唯一落点**，
                        //    `T-512` 步 5 起它真的被传了（此前那条 javadoc 是承诺，不是事实）
                        null));
                if (!result.hasDescription()) {
                    failed++;
                    issues.add(ParsedDocument.DocumentIssue.missing(block.pageNo(),
                            "第 " + block.pageNo() + " 页的图片理解未成功（VL 返回空或调用失败），"
                                    + "该图暂以题注参与检索；请在模型配置里确认 VL 可用后重建索引",
                            ParsedDocument.IssueKind.IMAGE_DESC_FAILED));
                    addIfHasContent(out, block);
                    continue;
                }
                described++;
                out.add(withDescription(block, info, result));
            } catch (RuntimeException ex) {
                // 读字节失败（对象丢了）/ VL 抛了 —— 都**不牵连文档**，但必须留痕
                failed++;
                issues.add(ParsedDocument.DocumentIssue.missing(block.pageNo(),
                        "第 " + block.pageNo() + " 页的图片理解失败：" + ex.getMessage()
                                + "；该图暂以题注参与检索，请重新上传该文档",
                        ParsedDocument.IssueKind.IMAGE_DESC_FAILED));
                addIfHasContent(out, block);
            }
        }

        log.info("图片描述完成：考虑={} 成功={} 跳过={} 失败={}",
                described + skipped + failed, described, skipped, failed);
        return new Result(List.copyOf(out), described, skipped, failed, List.copyOf(issues));
    }

    /** 把描述拼进块内容：**题注在前、描述在后**（需求 `06:158` 的"题注优先级更高"）。 */
    private static Block withDescription(Block block, ImageInfo info, VisionResult result) {
        String content = info.hasCaption()
                ? info.caption() + "\n" + result.desc()
                : result.desc();
        return new Block(content, block.blockType(), block.titlePath(), block.orderNo(),
                block.pageNo(), block.parentOrderNo(), block.sourceOrderNo(),
                info.withVlModel(result.vlModelName()));
    }

    /**
     * 内容为空的图片块**不进结果**。
     *
     * <p>空 `rawContent` 的 `IMAGE` 块会被 {@code ChunkViews.IMAGE_DESC} 投影成空串，
     * 而空串会被**嵌进向量**（`ChunkVectorizer` 只按"该不该进池"筛，不按"文本空不空"）——
     * 一条内容为空的向量既召回不到任何东西，又占一个位置。
     * `ChunkingService` 已经在源头拦了一道（没字节又没题注的不产出块），
     * 这里是第二道：**描述失败/被跳过之后**才变空的那些。
     */
    private static void addIfHasContent(List<Block> out, Block block) {
        if (block.rawContent() != null && !block.rawContent().isBlank()) {
            out.add(block);
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
