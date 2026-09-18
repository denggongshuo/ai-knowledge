package com.knowledge.ai.pipeline.vision;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

/**
 * 送 VL 之前的图片压缩（需求 `09` §4.3 的「工程要点」表）。
 *
 * <h3>为什么压：这不是优化，是成本</h3>
 * <p>需求原话：「VL 的 token 与延迟跟像素强相关，这一步省 **30~50% 耗时**，
 * 且对描述质量几乎无损」。所以它**不是正确性前提** —— 这一点决定了下面全部的错误处理方式：
 * 压不了就用原图，绝不因为它失败而打断整条链路。
 *
 * <h3>用 JDK 自带的 {@code javax.imageio}，不引第三方库</h3>
 * <p>需求要的是「长边 ≤1536px、JPEG q85」这一条固定规则，不是什么自适应压缩。
 * {@code java.desktop} 模块（JDK 25 已含）的 {@code ImageIO} + {@code ImageWriteParam}
 * 就是它。<b>不引 {@code thumbnailator} / {@code imgscalr}</b> ——
 * 本仓先例：CSV 而非 poi-ooxml（省 15MB）、手写 4 字段 JSON 而非注入 ObjectMapper。
 *
 * <h3>⚠️ WebP 会压不动，这是预期路径</h3>
 * <p>{@code ChatAttachmentStorage} 的白名单含 WebP，而 JDK 的 {@code ImageIO}
 * **默认读不了 WebP**（没有内置 reader）。那时 {@link #compress} 返回原字节 ——
 * 上游照常把它送给 VL（多数网关自己认 WebP）。所以这里的"失败"分支不是异常路径，
 * 是一条**每次遇到 WebP 都会走**的常规分支。
 *
 * <h3>为什么统一转成 JPEG 而不是保持原格式</h3>
 * <p>q85 只有 JPEG 有；保持 PNG 就必须走无损重编码，"省 30~50%" 立刻不成立。
 * 代价是**透明通道会被填成白底**（见 {@link #toJpeg}）—— 截图类图片多数不透明，
 * 而带透明的图标不需要精细描述。这条取舍写在这里，是因为它会**静默**发生：
 * 描述结果不会报错，只是透明区域变成白块。
 */
@Slf4j
@Component
public class ImageCompressor {

    /** 长边上限（像素）。需求 §4.3 的取值。 */
    static final int MAX_EDGE = 1536;

    /** JPEG 质量。需求 §4.3 的取值。 */
    static final float JPEG_QUALITY = 0.85f;

    /**
     * 压缩一张图。
     *
     * @param original 原始字节
     * @return 压缩后的 JPEG 字节；**压不了时原样返回 {@code original}**（绝不抛）
     */
    public byte[] compress(byte[] original) {
        if (original == null || original.length == 0) {
            return original;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(original));
            if (image == null) {
                // 读不出来（WebP / 少见格式 / 字节损坏）：交给 VL 自己判断
                log.debug("图片无法用 JDK ImageIO 解码，按原图送出：bytes={}", original.length);
                return original;
            }
            BufferedImage scaled = scaleDown(image);
            byte[] jpeg = toJpeg(scaled);
            if (jpeg == null || jpeg.length == 0) {
                return original;
            }
            log.debug("图片压缩：{} → {} 字节（{}x{} → {}x{}）",
                    original.length, jpeg.length, image.getWidth(), image.getHeight(),
                    scaled.getWidth(), scaled.getHeight());
            return jpeg;
        } catch (Exception ex) {
            // 压缩是优化不是前提 —— 它失败时"用原图"永远是对的下一步
            log.warn("图片压缩失败（改用原图送出）：bytes={} 原因={}", original.length, ex.getMessage());
            return original;
        }
    }

    /** 只缩小不放大 —— 一张 400px 的图放大到 1536px 只会让 token 变多、描述变糊。 */
    private static BufferedImage scaleDown(BufferedImage source) {
        int w = source.getWidth();
        int h = source.getHeight();
        int longEdge = Math.max(w, h);
        if (longEdge <= MAX_EDGE) {
            return source;
        }
        double ratio = (double) MAX_EDGE / longEdge;
        int targetW = Math.max(1, (int) Math.round(w * ratio));
        int targetH = Math.max(1, (int) Math.round(h * ratio));

        // 一律画到 TYPE_INT_RGB 上：JPEG 本来就没有 alpha 通道，直接编码带 alpha 的类型时
        // "透明变成什么颜色"依实现而定（有的变黑）。显式转 RGB + 铺白底，
        // 让"透明 → 白"这件事是**我们决定的**，而不是某个编码器的默认行为
        BufferedImage target = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            // 先铺白底，再画 —— 这就是"透明填成白色"的那一步，写出来而不是依赖默认
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, targetW, targetH);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, targetW, targetH, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    /** 编码成 JPEG q85。编码器不可用时返回 {@code null}（调用方回落原图）。 */
    private static byte[] toJpeg(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), param);
            return out.toByteArray();
        } catch (Exception ex) {
            log.warn("JPEG 编码失败（改用原图送出）：原因={}", ex.getMessage());
            return null;
        } finally {
            writer.dispose();
        }
    }
}
