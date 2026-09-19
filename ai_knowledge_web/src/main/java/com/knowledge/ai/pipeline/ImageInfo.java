package com.knowledge.ai.pipeline;

/**
 * 一张图片的**身份与出处**（`T-512`）—— 解析产物里的 `IMAGE` 元素与分片后的 `IMAGE` 块共用。
 *
 * <h3>为什么是一个独立类型，而不是往 {@link ParsedDocument.DocElement} 里塞四个字段</h3>
 * <p>{@code DocElement} 的 5 个字段（{@code kind/text/pageNo/level/tableIndex}）里已经有两条
 * "只有某一种 kind 有意义"的先例（{@code level} 只对 `HEADING`、{@code tableIndex} 只对 `TABLE`），
 * 再加四个"只对 `IMAGE` 有意义"的字段会让那个 record 变成一张"谁都能往里塞"的桌子。
 * 更重要的是：**分片后的 {@link Block} 也需要同样这四个值** —— 一个独立类型可以让它们
 * 在"解析产物 → 块"这一跳上**原样传递**，不必两头各定义一份再写转换。
 *
 * <h3>⚠️ 实测（`T512ImageProbeTest`）：`objectKey` 为 null 是常态，不是异常</h3>
 * <p>{@code 1-DeepSeek使用.pdf} 实测：**75 个 `image` 元素里只有 26 个有可导出的位图**。
 * 没导出的 49 张里，中位包围盒面积 14,988（≈122×122 的小装饰，同一尺寸反复出现 ——
 * 需求 `06:160` 自己写的「公司 logo 这类图」），**但至少有一张面积 180,386 的真图也被跳过**。
 * 最可能的解释是**矢量图**（opendataloader 只能导出位图 XObject），但**这条只是推断，未证**。
 *
 * <p>⇒ 所以本类型的 {@code objectKey} / {@code md5} **可空是设计的一部分**：
 * 「没有可提取的位图」是一个**要登记给人看**的事实（用户 2026-09-18 裁定 B），
 * 不是"程序出了点小状况"。
 *
 * @param sourceId  opendataloader 的 `id`。**题注配对的唯一钥匙** ——
 *                  `caption` 节点的 `linked content id` 指向它（实测该链是通的：11/11 有值）
 * @param box       包围盒 `[x0, y0, x1, y1]`（PDF 用户空间坐标）。缺失为 {@code null}
 * @param caption   **题注**（若有）。需求 `06:158` 把它定为分片内容的**首选**：
 *                  「题注（若有）+ VL 描述 —— 题注优先级更高，它是作者写的，比 VL 描述更准」。
 *                  ⚠️ 实测它很稀有：11 条题注 / 75 张图、能配上对 8 条（**≈11%**）
 *                  ⇒ 下游必须把「没有题注」当**常规路径**，不是例外分支
 * @param objectKey MinIO 对象键；**{@code null} = 这张图没有可提取的位图**（见类注释）
 * @param md5       图片字节的 md5（需求 `06:159` 点名的 `imageMd5`）。
 *                  用途有二：缓存 key 的原料（与 `T-907` 的问答附件同一条）、
 *                  以及需求 `06:160` 的「按 `imageMd5` 去重」（**本批只落列、不做去重**）。
 *                  {@code null} 当且仅当 {@code objectKey} 为 {@code null}
 * @param vlModelId 这条描述出自哪条 **VL 模型**（需求 `06:159` 点名的元数据，
 *                  落 `sys_chunk.vl_model_id`）。
 *                  <p>⚠️ <b>它是本类型里唯一**不是解析产物**的字段</b>：上面四个都由
 *                  {@code PdfDocumentParser} 在解析期填好，而这个要在**锁外描述**那一步
 *                  （`T-512` 步 5）才知道 ⇒ 在 {@code parse.json} 里它**恒为 {@code null}**，
 *                  那不是缺陷，是"那一刻还没发生"。
 *                  <p>为什么放这里而不往 {@link Block} 再加一个字段：见类注释那句
 *                  「只对 IMAGE 有意义的值收进这个类型」—— `Block` 的 8 个字段里已经有
 *                  三个是"只有某类块有意义"了，再拆一个出来，`Block` 就成了那张桌子。
 *                  <p>消费方：`T-908`（换 VL）要按它筛出"是旧模型产的那些图"重算。
 */
public record ImageInfo(Integer sourceId,
                        Box box,
                        String caption,
                        String objectKey,
                        String md5,
                        String vlModelId) {

    /**
     * 补上「谁描述的我」—— 锁外描述那一步（`T-512` 步 5）用。
     *
     * <p>返回新实例而不是原地改：record 天然不可变，而这个值在解析期**确实还不存在**。
     */
    public ImageInfo withVlModel(String model) {
        return new ImageInfo(sourceId, box, caption, objectKey, md5, model);
    }

    /**
     * 有没有可描述的字节。
     *
     * <p>用**这一个方法**而不是到处判 {@code objectKey != null}：将来若改了
     * "什么算可提取"的口径（例如允许直接吃矢量图渲染结果），只有这里要动。
     */
    public boolean hasBytes() {
        return objectKey != null && !objectKey.isBlank();
    }

    /** 有题注吗（需求 `06:158` 的"题注优先"只在它为 true 时有意义）。 */
    public boolean hasCaption() {
        return caption != null && !caption.isBlank();
    }

    /**
     * 包围盒。**用 record 而不是 `double[]`**：数组组件的 {@code equals}/{@code hashCode}
     * 是**引用比较**，会让两个"几何完全相同"的 {@code ImageInfo} 判为不等 ——
     * 而它嵌在 {@link ParsedDocument.DocElement} 里，那个 record 的相等性会被它带歪
     * （用例里 `assertThat(doc.elements()).containsExactly(...)` 这种断言会莫名其妙地红）。
     */
    public record Box(double x0, double y0, double x1, double y1) {

        public double width() {
            return Math.abs(x1 - x0);
        }

        public double height() {
            return Math.abs(y1 - y0);
        }

        /**
         * 面积。
         *
         * <p>{@code T-512} 的「小图标过滤」用它，{@code T-O03} 的「尺寸/位置固定」也用得上 ——
         * 两处都不必各自再写一遍"宽 × 高"。
         */
        public double area() {
            return width() * height();
        }
    }
}
