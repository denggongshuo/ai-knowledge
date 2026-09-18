package com.knowledge.ai.pipeline.vision;

import com.knowledge.ai.core.ai.ModelClientRegistry;
import com.knowledge.ai.core.enums.ModelType;
import com.knowledge.ai.service.ModelUsageRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 图片理解的编排（`T-907`）—— **需求 `09` FR-SYS-04 的核心**。
 *
 * <h3>一次调用两产出（§4.4）</h3>
 * <p>VL 返回的文本**按"最后一行是关键词"切**，不解析 JSON：
 * <pre>
 *   …描述正文（多行）…
 *   关键词：A, B, C
 *     ↓
 *   desc          = 前面所有行（给生成用，信息完整）
 *   searchSummary = 最后一行去掉前缀（给检索用，关键词密集）
 * </pre>
 * §4.4 的原话解释了为什么不要求 JSON：「很多 VL 模型会输出带 markdown 代码块或
 * 前后解释文字的 JSON，解析失败会**直接打断整条链路**」。所以本实现的失败面被压到最小 ——
 * 连"没有关键词行"都**不算失败**（§4.10 第 4 行：用 {@code desc} 整体当
 * {@code searchSummary}，「不必失败」）。
 *
 * <h3>降级矩阵（§4.10）在本类的落点</h3>
 * <table>
 *   <tr><th>失败点</th><th>本类怎么做</th></tr>
 *   <tr><td>VL 未配置 / 调用失败 / 返回空</td><td>{@link VisionResult#degraded} ——
 *       <b>不抛</b>，照常让问答往下走（§4.10 第 3 行）</td></tr>
 *   <tr><td>解析不出关键词行</td><td>不失败，{@code searchSummary = desc}（第 4 行）</td></tr>
 *   <tr><td>图片格式/大小不合规</td><td><b>不在这里</b> —— 那是
 *       {@code ChatAttachmentStorage} 在上传时就拒掉的（第 1 行），
 *       根本走不到本类</td></tr>
 *   <tr><td>MinIO 上传失败</td><td>同上（第 2 行），上传阶段就明确报错</td></tr>
 *   <tr><td>检索无结果</td><td>不在这里 —— 走既有未命中沉淀（第 5 行）</td></tr>
 * </table>
 *
 * <h3>多图逐张并行（§4.3）</h3>
 * <p>需求原话：「逐张**并行**调用（描述质量更稳，且便于按图缓存）」。
 * 并行度天然被输入规模限死 —— 需求 §4.5 规定单次最多 3 张，
 * 所以用虚拟线程执行器（JDK 25，零配置、用完即回收）而不是池化：
 * 为一件事最多并发 3 个的任务去维护一个连接池，是负收益。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisionDescriber {

    /** 关键词行的前缀（中英文冒号都收 —— VL 输出用哪种冒号不由我们决定）。 */
    private static final String KEYWORD_PREFIX = "^关键词[：:]\\s*";

    private final VisionCaller caller;
    private final VisionDescriptionCache cache;
    private final ImageCompressor compressor;
    private final ModelUsageRecorder usageRecorder;

    /**
     * 描述一张图（`VisionInput`）。
     *
     * <p>顺序固定：**先查缓存 → 再压缩 → 再调用 → 再回写缓存**。
     * 压缩放在缓存之后（命中缓存时一行都不压），回写放在调用之后
     * （降级产出不写缓存，见 {@link VisionDescriptionCache#put}）。
     */
    public VisionResult describe(VisionInput input) {
        VisionCaller.VisionModelRef modelRef = caller.modelRef();
        if (!modelRef.present()) {
            // 未配置 VL：连缓存都不用查（缓存 key 的第二段就是 vlModelId，没有它查不了）
            log.info("[图片理解] 未配置 VL 模型，本次降级：attachment={}", input.md5());
            return VisionResult.degraded("");
        }

        VisionResult cached = cache.get(input.md5(), modelRef.id());
        if (cached != null) {
            log.debug("[图片理解] 缓存命中：md5={} vl={}", input.md5(), modelRef.name());
            return new VisionResult(cached.desc(), cached.searchSummary(),
                    modelRef.name(), false, cached.promptVersion());
        }

        byte[] payload = compressor.compress(input.bytes());
        long startNanos = System.nanoTime();
        try {
            VisionCaller.VisionRaw raw = caller.call(
                    VisionPrompt.of(input.question()), payload, input.presignedUrl(), input.mime());
            long millis = (System.nanoTime() - startNanos) / 1_000_000L;

            String text = raw == null || raw.text() == null ? "" : raw.text().strip();
            if (text.isEmpty()) {
                // §4.10 第 3 行把"返回空"与"调用失败"归为同一处置
                log.warn("[图片理解] VL 返回空，本次降级：md5={} vl={}", input.md5(), modelRef.name());
                recordUsage(modelRef, raw, millis);
                return VisionResult.degraded(modelRef.name());
            }

            VisionResult result = split(text, modelRef.name());
            cache.put(input.md5(), modelRef.id(), result);
            recordUsage(modelRef, raw, millis);
            log.info("[图片理解] 描述完成：md5={} vl={} 描述 {} 字 / 关键词 {} 字",
                    input.md5(), modelRef.name(), result.desc().length(), result.searchSummary().length());
            return result;
        } catch (RuntimeException ex) {
            // **不抛**：图片理解不了时照常按文字问题回答（§4.10 第 3 行）。
            // 抛出去就等于拿一次图片失败炸掉整个问答 —— 比"少一份描述"严重一个量级
            long millis = (System.nanoTime() - startNanos) / 1_000_000L;
            log.warn("[图片理解] VL 调用失败，本次降级：md5={} vl={} 原因={}",
                    input.md5(), modelRef.name(), ex.getMessage());
            // 失败也记一次用量：走到这里说明 `modelRef` 是有值的（未配置的情况上面已经
            // return 了），即**请求真的发出去了** —— 供应商多半照计费。不记会让
            // "这个月花了多少"漏掉失败的那些。与 {@code RetrievalService#recordRerankUsage}
            // 刻意不同：那边失败时确实一条请求都没发出去（连接前就抛了）
            recordUsage(modelRef, null, millis);
            return VisionResult.degraded(modelRef.name());
        }
    }

    /**
     * 逐张并行描述（§4.3）。
     *
     * <p>任一张失败**不影响其余**（各自降级），所以返回的列表与输入**等长同序**——
     * 调用方按位对应即可，不必自己去配 id（那是出错最容易出错的地方）。
     */
    public List<VisionResult> describeAll(List<VisionInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        if (inputs.size() == 1) {
            // 单张不走执行器：启线程比干活还贵，而单张恰恰是最常见的情形
            return List.of(describe(inputs.getFirst()));
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<VisionResult>> futures = new ArrayList<>(inputs.size());
            for (VisionInput input : inputs) {
                futures.add(pool.submit(() -> describe(input)));
            }
            List<VisionResult> results = new ArrayList<>(inputs.size());
            for (Future<VisionResult> future : futures) {
                results.add(await(future));
            }
            return results;
        }
    }

    /** 取一条并行结果。{@link #describe} 自己已经把失败转成降级了，这里只兜"任务本身炸了"。 */
    private static VisionResult await(Future<VisionResult> future) {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return VisionResult.degraded("");
        } catch (Exception ex) {
            log.warn("[图片理解] 并行描述任务异常：原因={}", ex.getMessage());
            return VisionResult.degraded("");
        }
    }

    /**
     * 切两产出（§4.4 的算法逐行）。
     *
     * <p>⚠️ 判据是「**最后一行是否以「关键词」开头**」，不是「最后一行里有没有冒号」——
     * 描述正文里出现冒号太常见了（表格的 `| 项 | 值 |`、`版本：v1.4`），
     * 用宽松判据会把正文的最后一行当成关键词吃掉。
     */
    static VisionResult split(String raw, String vlModelName) {
        String[] lines = raw.split("\n");
        String last = lines.length == 0 ? "" : lines[lines.length - 1].strip();
        if (!last.startsWith("关键词")) {
            // §4.10 第 4 行：解析不出关键词行**不必失败** —— 整段描述当检索摘要。
            // 这比抛异常好得多：检索质量可能略降，但链路是通的
            return new VisionResult(raw, raw, vlModelName, false, VisionPrompt.PROMPT_VERSION);
        }
        String summary = last.replaceFirst(KEYWORD_PREFIX, "").strip();
        String desc = lines.length <= 1
                ? ""
                : String.join("\n", Arrays.copyOf(lines, lines.length - 1)).strip();
        if (desc.isEmpty()) {
            // 极端情形：模型只回了关键词那一行。desc 为空但 summary 有值 ——
            // 这是**成功**（不是降级）：检索拿得到关键词，只是没有正文可给生成用
            return new VisionResult("", summary, vlModelName, false, VisionPrompt.PROMPT_VERSION);
        }
        return new VisionResult(desc, summary, vlModelName, false, VisionPrompt.PROMPT_VERSION);
    }

    /**
     * 记一次 VL 用量（`T-905`）。
     *
     * <p>⚠️ 走的是 {@code (类型, 标识, 模型名)} 那个重载，**不是 spec 那个** ——
     * 本类按设计不持有 {@link ModelClientRegistry}（`T-014` 的纪律：碰模型客户端的地方
     * 只有一个），手上只有 {@link VisionCaller.VisionModelRef}。
     * 为记一行用量把注册中心塞进描述器，等于为了统计破坏那条结构纪律。
     *
     * <p>失败不抛（{@link ModelUsageRecorder} 内部已吞并留 warn），这里不必再包 try。
     *
     * <p>⚠️ token 取不到时传 {@code 0}：本表的 {@code SUM(...)} 要能直接累加，
     * 而"没回报"在合计意义下就是没记到数（理由见 {@code SysModelUsage} 类注释）。
     * 耗时则如实传 —— 它是单独聚合的，{@code null}（没测）不会被压成 0。
     *
     * <p>⚠️ 输入/输出**分开传**（不合成一个合计）：VL 的输入含图片、输出是描述文本，
     * 两者单价不同，合成一个数就再也拆不开了。
     */
    private void recordUsage(VisionCaller.VisionModelRef modelRef, VisionCaller.VisionRaw raw, long millis) {
        usageRecorder.record(ModelType.VL, modelRef.id(), modelRef.name(),
                ModelUsageRecorder.Purpose.VL_DESC, 1,
                raw == null || raw.promptTokens() == null ? 0L : raw.promptTokens(),
                raw == null || raw.completionTokens() == null ? 0L : raw.completionTokens(),
                toInt(millis));
    }

    private static Integer toInt(long millis) {
        return millis < 0 ? null : (int) Math.min(millis, Integer.MAX_VALUE);
    }

    /**
     * 一张图的输入。
     *
     * @param md5          服务端按字节算的 md5（缓存 key 第一段）。
     *                     ⚠️ 为空时**不查也不写缓存**（见 {@link VisionDescriptionCache#get}）
     * @param bytes        图片字节；{@code null} = 调用方只有 URL
     * @param presignedUrl 预签名 URL；{@code null} = 只有字节
     * @param mime         原始 MIME
     * @param question     用户问题。**文档解析期传 {@code null}**（§4.3 的两链路对照表）
     */
    public record VisionInput(String md5, byte[] bytes, String presignedUrl, String mime, String question) {
    }
}
