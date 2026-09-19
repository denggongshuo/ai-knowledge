package com.knowledge.ai.pipeline.vision;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 图片的**感知哈希**（dHash 64 位，`T-O03`）—— **全是纯函数**，无状态、无 IO。
 *
 * <h3>它回答的问题与 {@code imageMd5} 不是同一个</h3>
 * <p>{@code sys_chunk.image_md5} 回答"这两个是**同一个对象**吗"（字节级）。
 * 本类回答"这是**同一张图**吗"（感知级）。差别在**重编码**：
 * 同一张 logo 换个格式、换个压缩参数、被文档工具另存一遍，字节全变而**看起来还是它**。
 * 装饰图判定要的是后者 —— `O3` §4 步 3 点名的也是 pHash 而不是 md5。
 *
 * <h3>为什么是 dHash 而不是 aHash / pHash</h3>
 * <ul>
 *   <li>**dHash**（比较相邻像素的亮度大小）对**压缩噪声**不敏感、对**缩放与亮度**敏感 ——
 *       正合"同一张图被重编码过"这个场景；</li>
 *   <li>**pHash** 要 DCT，代码量与出错面都大一个量级，而本场景（同源 logo）
 *       用不到它那点抗几何变换的能力；</li>
 *   <li>**aHash**（与全局均值比）对整体亮度偏移更敏感，本场景没有这个需求。</li>
 * </ul>
 * ⚠️ **本类不承诺"dHash 是正解"** —— 它是判定链上**可替换的一环**（纯函数、单独可测），
 * 若将来量出"重编码后 dHash 也变了"，换 pHash 只动这一个类。
 *
 * <h3>🔴 一条实测出来的已知限制：dHash 只看**水平**相邻差</h3>
 * <p>写 {@code ImageHashTest} 的负向锚时第一版就撞上了：**横向渐亮的图与纵向渐变的图
 * 得到同一个哈希（全 0）**。原因是 bit 判据是"左边比右边亮吗"——
 * 横向渐亮恒假、纵向渐变在同一行内左右相等也恒假 ⇒ 都是 0。
 * （`ImageHashTest#knownLimitationVerticalStructureIsInvisible` 把它钉住了。）
 *
 * <p>⇒ **dHash 不是通用图像相似度**，它只为"同源重编码"这一个场景设计。
 * 所以装饰图判定**不能只靠哈希**，它同时压着另外三条条件
 * （跨文档 + 尺寸/位置固定 + 面积上界，见 {@code DecorativeImageRule}）。
 * ⚠️ 若将来要它认纵向结构，换 pHash（DCT 版）—— 本类是纯函数，换它只动一个类。
 *
 * <h3>为什么不引依赖</h3>
 * <p>{@code ImageIO} 是 JDK 自带能力，dHash 只是"缩到 9×8 → 逐行比相邻像素"，
 * 没有算法难点。而候选（thumbnailator / imgscalr）**都不直接提供 pHash** ——
 * 引进来之后真正的哈希仍要自己写。OpenCV 有 {@code img_hash}，但那是 ≈100MB 的原生库，
 * 为一次 64 位哈希不成比例（详见计划 §5）。
 *
 * <h3>⚠️ 认不出的格式返回 {@code null}，不抛</h3>
 * <p>本仓抽出的图是 JPEG（`V29` 起 `IMAGE_FORMAT_JPEG` 写死），但 `ImageIO.read`
 * 对 CMYK JPEG、损坏字节等情况会返回 {@code null} 或抛。
 * 这条链上"算不出哈希"的处置是**放弃这张图的装饰图判定**（它照常进索引），
 * **不是**让整篇文档失败 —— 与 {@code ImageCompressor} 同一条取舍。
 */
public final class ImageHash {

    /** 哈希的十六进制长度（64 位 ⇒ 16 字符）。与迁移里 {@code image_phash CHAR(16)} 同源。 */
    public static final int LENGTH = 16;

    /** dHash 的采样宽度（要比 8 列多一格才能得到 8 个"相邻差"）。 */
    private static final int WIDTH = 8;

    /** dHash 的采样高度。 */
    private static final int HEIGHT = 8;

    /**
     * LSH 的分带数 —— 把 64 位切成几段做**候选**筛选。
     *
     * <p>取 4：每段 16 位。这是"候选筛选"的经典折中 —— 段越少，假阴性越低（任何一段相同即入选），
     * 但候选集越大。4 段 16 位在本场景（一个库里几千张图）下候选量可控。
     * ⚠️ **这个数要按量调**（见计划 §3.4 假设 4）：本批只落机制 + 记实测。
     */
    static final int BANDS = 4;

    private ImageHash() {
    }

    /**
     * 算 dHash。
     *
     * @param bytes 图片字节；{@code null} / 空 / 认不出的格式 ⇒ 返回 {@code null}
     * @return 16 位小写十六进制；算不出时为 {@code null}（**不抛**，见类注释）
     */
    public static String dHash(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException ex) {
            // 损坏的字节流。与"认不出的格式"同一条处置：放弃这一张，不牵连文档
            return null;
        }
        if (source == null) {
            return null;
        }

        // 缩到 9×8 的**灰度**：`TYPE_BYTE_GRAY` 顺带把色彩空间归一了 ——
        // 同一张图存成灰度或彩色，在这一步之后就没有区别了（这正是我们要的"感知"）
        BufferedImage small = new BufferedImage(WIDTH + 1, HEIGHT, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = small.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, WIDTH + 1, HEIGHT, null);
        } finally {
            g.dispose();
        }

        long hash = 0L;
        var raster = small.getRaster();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                // 从左到右逐位拼：第 0 位是最高位。方向固定即可 —— 它只影响换序，不影响距离
                hash = (hash << 1) | (raster.getSample(x, y, 0) > raster.getSample(x + 1, y, 0) ? 1L : 0L);
            }
        }
        return String.format("%016x", hash);
    }

    /**
     * 两个哈希的**汉明距离**（不同的位数）。
     *
     * @return 0~64；任一为空或长度不对时返回 {@link #LENGTH} 的 4 倍（64）——
     *         即"**最远**"，让"没有可比性"落在"不同"那一侧。
     *         ⚠️ 不返回 {@code 0}：那会把"没算出来"当成"完全一样"，是这条链上最危险的一侧
     */
    public static int hamming(String left, String right) {
        if (left == null || right == null || left.length() != LENGTH || right.length() != LENGTH) {
            return LENGTH * 4;
        }
        try {
            return Long.bitCount(Long.parseUnsignedLong(left, 16) ^ Long.parseUnsignedLong(right, 16));
        } catch (NumberFormatException ex) {
            // 不是十六进制 —— 与"没算出来"同一条处置
            return LENGTH * 4;
        }
    }

    /**
     * 把哈希切成 {@link #BANDS} 段（LSH 的**候选**依据）—— 任一段相同即落进候选集。
     *
     * <p>为什么需要它：跨文档判定要在 SQL 里找"看起来一样"的图，而 SQL 只会值相等。
     * 直接 `where image_phash = ?` 只能精确匹配（那等于 md5，见类注释）。
     * 分带之后按段取并集当**候选**，再在 Java 侧算汉明距离 —— 这是 LSH 的标准做法，
     * 而且**不需要任何新依赖**（十六进制字符串按 4 字符一段 `in (...)` 即可）。
     *
     * @return 段列表；哈希不合法时返回空列表（调用方据此跳过 —— **别拿空列表去拼 SQL**）
     */
    public static List<String> bands(String hash) {
        if (hash == null || hash.length() != LENGTH) {
            return List.of();
        }
        int width = LENGTH / BANDS;
        List<String> out = new ArrayList<>(BANDS);
        for (int i = 0; i < BANDS; i++) {
            out.add(hash.substring(i * width, (i + 1) * width));
        }
        return List.copyOf(out);
    }
}
