package com.knowledge.ai.pipeline.vision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.function.IntBinaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 感知哈希（`T-O03`）—— **全纯函数、不联网、不碰磁盘**。
 *
 * <h3>为什么它必须有负向锚</h3>
 * <p>"同一张图两次编码得到同一个哈希"这条**单独是没用的** ——
 * 一个恒返回常量的实现照样能过。真正要证的是**它还能把不同的图分开**，
 * 否则装饰图判定会把整个库的图都判成"同一张"。
 *
 * <h3>图是现生成的，不依赖样本文件</h3>
 * <p>{@code docs/测试文件/} 不入库，用例不能依赖它。而 dHash 要的性质
 * （对缩放/编码不敏感、对不同内容敏感）用两张合成图就能证 ——
 * 真样本上的表现是另一回事，登记在计划 §8 的采样边界里。
 */
class ImageHashTest {

    @Test
    @DisplayName("同一张图两次编码 ⇒ 同一个哈希（这是底线，虽然它单独说明不了什么）")
    void sameImageSameHash() {
        byte[] png = png(200, 200, (x, y) -> x * 255 / 200);
        assertThat(ImageHash.dHash(png)).isEqualTo(ImageHash.dHash(png));
    }

    @Test
    @DisplayName("缩到一半 ⇒ 同一个哈希（dHash 对**缩放**不敏感 —— 重编码场景要的就是这个）")
    void scalingPreservesHash() {
        IntBinaryOperator gradient = (x, y) -> x * 255 / 200;
        String big = ImageHash.dHash(png(200, 200, gradient));
        String small = ImageHash.dHash(png(100, 100, gradient));
        assertThat(small).as("同一张图换个尺寸存 ⇒ 必须还是同一张").isEqualTo(big);
    }

    @Test
    @DisplayName("灰度与彩色同一张图 ⇒ 同一个哈希（缩到 TYPE_BYTE_GRAY 那一步把色彩空间归一了）")
    void colorSpaceIsNormalised() {
        String colored = ImageHash.dHash(png(200, 200, (x, y) -> (x * 255 / 200) << 16 | 0x00ff00));
        String gray = ImageHash.dHash(png(200, 200, (x, y) -> x * 255 / 200));
        assertThat(gray).as("同一张图存成彩色或灰度，感知上就是同一张").isEqualTo(colored);
    }

    @Test
    @DisplayName("🔴 负向锚：**不同的图必须得到不同的哈希** —— 缺了它，恒返回常量的实现也能绿")
    void differentImagesDifferentHash() {
        String gradient = ImageHash.dHash(png(200, 200, (x, y) -> x * 255 / 200));
        String checker = ImageHash.dHash(png(200, 200, (x, y) -> ((x / 20 + y / 20) % 2) * 255));

        assertThat(gradient).isNotEqualTo(checker);
        assertThat(ImageHash.hamming(gradient, checker))
                .as("不只是「不等」—— 距离要真的拉开（汉明距离是判定的实际依据）")
                .isGreaterThan(8);
    }

    @Test
    @DisplayName("⚠️ **已知限制**：dHash 只看**水平**相邻差 ⇒ 纵向结构看不见，两张不同的图会撞")
    void knownLimitationVerticalStructureIsInvisible() {
        String horizontal = ImageHash.dHash(png(200, 200, (x, y) -> x * 255 / 200));
        String vertical = ImageHash.dHash(png(200, 200, (x, y) -> y * 255 / 200));

        // 横向渐亮：左暗右亮 ⇒ `left > right` 恒假 ⇒ 每一位 0；
        // 纵向渐变：同一行内左右相等 ⇒ 同样恒假 ⇒ 也每一位 0。**两者撞在 0 上。**
        assertThat(vertical)
                .as("⚠️ 这是**实测出来的** dHash 性质，不是笔误：写负向锚时第一版就选了"
                        + "「横向渐变 vs 纵向渐变」这一对，当场转红。"
                        + "它说明 dHash 只为「同源重编码」设计，**不是通用图像相似度** —— "
                        + "判定侧因此还压着另外三条条件（跨文档 + 同尺寸高频 + 面积上界），"
                        + "不能只靠哈希")
                .isEqualTo(horizontal)
                .isEqualTo("0000000000000000");
    }

    @Test
    @DisplayName("认不出的字节 ⇒ null，**不抛**（这张图放弃判定，文档照常走）")
    void unreadableBytesYieldNullInsteadOfThrowing() {
        assertThat(ImageHash.dHash(null)).isNull();
        assertThat(ImageHash.dHash(new byte[0])).isNull();
        assertThat(ImageHash.dHash("这不是图片".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isNull();
    }

    @Test
    @DisplayName("hamming：自身为 0 · 最坏为 64 · **算不出时返回 64 而不是 0**")
    void hammingEdgeCases() {
        String hash = ImageHash.dHash(png(200, 200, (x, y) -> x * 255 / 200));
        assertThat(ImageHash.hamming(hash, hash)).isZero();
        assertThat(ImageHash.hamming(hash, null))
                .as("🔴 返回 0 会把「没算出来」当成「完全一样」—— 那是这条链上最危险的一侧")
                .isEqualTo(64);
        assertThat(ImageHash.hamming(hash, "不是十六进制")).isEqualTo(64);
        assertThat(ImageHash.hamming(hash, "abc"))
                .as("长度不对（旧数据 / 半截哈希）同样归到「最远」").isEqualTo(64);
    }

    @Test
    @DisplayName("bands：切成 4 段、拼起来等于原哈希 —— 它是 LSH 取候选的唯一依据")
    void bandsSplitTheHash() {
        String hash = "0123456789abcdef";
        List<String> bands = ImageHash.bands(hash);

        assertThat(bands).as("16 位十六进制按 4 段切，每段 4 字符").hasSize(4);
        assertThat(String.join("", bands)).as("拼起来必须还原 —— 否则按段取的候选会漏").isEqualTo(hash);
        assertThat(ImageHash.bands(null)).isEmpty();
        assertThat(ImageHash.bands("短")).as("非法哈希给空列表，**别拿它去拼 SQL**").isEmpty();
    }

    /** 现生成一张 PNG（不依赖任何样本文件）。 */
    private static byte[] png(int width, int height, IntBinaryOperator rgb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, rgb.applyAsInt(x, y));
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (java.io.IOException ex) {
            throw new AssertionError("现生成 PNG 失败（JDK 必须支持 png）", ex);
        }
    }
}
