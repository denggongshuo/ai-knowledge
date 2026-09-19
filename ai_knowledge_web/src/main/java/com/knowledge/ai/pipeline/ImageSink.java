package com.knowledge.ai.pipeline;

/**
 * 图片字节的**出口**（`T-512` 步 2b）—— 解析器把抽出的每张图交给它。
 *
 * <h3>为什么是一个接口，而不是让解析器直接调 {@code DocStorage}</h3>
 * <p>{@link PdfDocumentParser} 在 <b>pipeline 层</b>：它不认识 MinIO、不认识知识库、
 * 也不知道当前是哪个 docId —— 它只知道"手上有一张图的字节，该交给谁"。
 * 把存储细节留给调用方注入，保住的是那条已经写在类注释里的界线
 * （{@code PdfDocumentParser} 的调用纪律第 2 条）。
 *
 * <p>副作用同样值钱：它**天然可测** —— 探针传一个只记账的假 sink 就能验
 * "哪些图进了、哪些被跳过、跳过时登记了什么"，不必起 MinIO。
 *
 * <h3>为什么字节在这一步存，而不是"先攒着、解析完再存"</h3>
 * <p>字节**只**在 opendataloader 的临时产物目录里，而那个目录在 {@code finally} 就被删。
 * 想留到解析之后，就得多一套暂存区生命周期 —— 而那正是 `#107` 那条纪律
 * （「输入是请求作用域的」）被咬出来的地方。**锁内多持几秒是明码标价的代价**，
 * 比多一套生命周期便宜。
 */
@FunctionalInterface
public interface ImageSink {

    /**
     * 存一张图的字节。
     *
     * @param bytes      图片字节（JPEG —— 与 {@code IMAGE_FORMAT_JPEG} 对应）
     * @param pageNo     所在页（1-based）。只用于对象名与登记，**不参与**存储决策
     * @param sourceName 解析器给的文件名（如 {@code imageFile25.jpeg}）。
     *                   对象名要能在 MinIO 控制台上看懂（与 {@code DocStorage#safeName} 同一条取舍）
     * @return 对象键与 md5；**返回 {@code null} 表示"这张图不落存储"**
     *         （调用方按 {@link ImageInfo#hasBytes()} 为 false 处理，不报错）
     */
    Stored store(byte[] bytes, int pageNo, String sourceName);

    /** 一次存储的结果。 */
    record Stored(String objectKey, String md5) {
    }

    /**
     * 不存任何东西 —— 解析基线等**只要解析结果、不要产物**的场合用。
     *
     * <p>行为上与"这张图没有可提取的位图"**完全一致**（{@code objectKey}/{@code md5} 皆为
     * {@code null}）。⚠️ 但它与那种情况在**语义上不同**：那是"抽不到"，
     * 这是"抽到了但不要" —— 所以用它的场合**不该**登记 {@code IMAGE_NOT_EXTRACTABLE}。
     */
    ImageSink NOOP = (bytes, pageNo, sourceName) -> null;
}
