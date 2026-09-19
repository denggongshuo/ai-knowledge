package com.knowledge.ai.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.parser.AutoDetectParser;
import org.opendataloader.pdf.api.Config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析产物的**版本指纹**（`T-O05` 步 3 · `O5` §4 步 3）。
 *
 * <h3>它解决的那句话</h3>
 * <p>`O5` §2 的缺口原话：「`parse.json` **无版本信息**：产物是定名覆盖写，既不知道是用哪个
 * 解析器版本产的，也不知道是哪个 `TextLayerGuard` 阈值判的」。后果不是"看不了"，
 * 而是 **「改了解析器之后哪些文档需要重解析」只能靠猜**。
 *
 * <h3>四段指纹，以及"哪一段变 = 什么要重做"</h3>
 * <table border="1">
 *   <caption>指纹各段变了意味着什么</caption>
 *   <tr><th>键</th><th>变了的含义</th></tr>
 *   <tr><td>{@code parserVersion}</td><td>换了依赖 → **要重解析**（产物的形状可能不同）</td></tr>
 *   <tr><td>{@code parserConfigHash}</td><td>改了我们的解析开关 → **要重解析**</td></tr>
 *   <tr><td>{@code guardVersion}</td><td>改了"这份文档能不能用"的判据 → **要重判**（连同重解析）</td></tr>
 *   <tr><td>{@code cleaningVersion}</td><td>改了清洗/投影逻辑 → 严格说只需**重分片**，不必重解析；
 *       但它与前三段一起进同一个哈希，所以本批按"一并重解析"处理（安全侧）</td></tr>
 * </table>
 *
 * <h3>为什么是"算出来的"而不是"手写的"</h3>
 * <p>`parserVersion` 与 `parserConfigHash` 从**依赖包元数据**与**代码里的开关**算 ——
 * 手写常量会在升级依赖时忘记改，而那种忘记**没有任何现象**（指纹说没变，实际变了），
 * 恰好废掉整个机制。{@code cleaningVersion} 是唯一一个**人工声明**的：清洗/投影是逻辑，
 * 逻辑没有稳定的机器可读身份，只能靠"改了要记得改这里"。这一条已写进它的注释。
 */
@Slf4j
public final class ParseFingerprint {

    public static final String KEY_PARSER_VERSION = "parserVersion";

    public static final String KEY_PARSER_CONFIG_HASH = "parserConfigHash";

    public static final String KEY_GUARD_VERSION = "guardVersion";

    public static final String KEY_CLEANING_VERSION = "cleaningVersion";

    /**
     * 清洗与视图投影的版本（`O3` / `O8` 那几层）。
     *
     * <p>⚠️ **这是本类里唯一需要人工维护的一段**：改了 {@code DocumentCleaner} 的规则、
     * 或改了 {@code ChunkViews} 的投影口径，**必须**把这里加一。前两段是算出来的、
     * 第三段来自代码常量，只有这一段没有机器可读的身份。
     * 忘了改的后果是"清洗规则变了，但所有文档都被判定为'产物仍是最新'"——
     * 于是重解析永远不会被触发，而这正是本机制要防的那件事。
     *
     * <h3>变更史（每次加一都要在这里留一行，否则下一个人不知道 v2 是什么）</h3>
     * <ul>
     *   <li>{@code v1} —— `O3`（页眉页脚剥离）/ `O8`（视图投影）落地时的初值；</li>
     *   <li>{@code v2} —— **`T-512` 步 6**：{@code ChunkViews.IMAGE_DESC} 从"抛异常"
     *       改成"返回 {@code rawContent}"。正是上面那句的**字面情形**：
     *       投影口径变了（图片块从此有内容进向量池），不加这就是"规则变了、
     *       而所有文档仍被判产物最新"。</li>
     * </ul>
     */
    static final String CLEANING_VERSION = "v2";

    /** 指纹里的哈希取前 16 个十六进制字符：足够区分、又能在 JSON 里一眼读出来。 */
    private static final int SHORT_HASH_LENGTH = 16;

    /**
     * 解析开关的**显式清单**（`PdfDocumentParser` 真正设过的那些）。
     *
     * <p>为什么不用反射遍历整个 {@code Config} 对象：那样会把**我们用都没用的字段**
     * 也算进指纹 —— 升级库时新增一个默认字段就会让全库文档被判为"需要重解析"，
     * 而那是一次纯粹的误报。指纹要回答的是"**我们的解析行为**变没变"。
     *
     * <p>⚠️ 清单收录的是「**会影响产物**的那些开关」，不是"调用过 setter 的那些"。
     * 反例：{@code generateMarkdown} 被设成 {@code true}，但本仓**不读那个 .md**
     * （markdown 由 {@code buildMarkdown} 从元素树重建）—— 所以它不进清单。
     * 而 {@code imageFormat} 从 `T-512` 步 2b 起**真的进入产物**（决定落盘图的字节），
     * 所以它必须进 —— 否则哪天把 JPEG 改成 PNG，全库文档不会被判"需重解析"，
     * 而"同一张图换了格式"正是一次真实的解析行为变更。
     */
    private static final Map<String, String> PARSER_CONFIG = Map.of(
            "generateJSON", "true",
            "generateText", "false",
            "generateHtml", "false",
            "imageOutput", "EXTERNAL",
            "imageFormat", "JPEG",
            "tableMethod", "DEFAULT",
            "keepLineBreaks", "true");

    private ParseFingerprint() {
    }

    /**
     * 当前代码产出的产物应当带的四段指纹。
     *
     * <p>顺序固定（{@link LinkedHashMap}）—— {@link #versionOf} 的哈希建立在顺序上，
     * 用 {@code HashMap} 会让同一个内容在不同 JVM 上算出不同指纹。
     */
    public static Map<String, String> snapshot() {
        Map<String, String> meta = new LinkedHashMap<>(4);
        meta.put(KEY_PARSER_VERSION, parserVersion());
        meta.put(KEY_PARSER_CONFIG_HASH, shortHash(canonical(PARSER_CONFIG)));
        meta.put(KEY_GUARD_VERSION, shortHash(guardSignature()));
        meta.put(KEY_CLEANING_VERSION, CLEANING_VERSION);
        return Map.copyOf(meta);
    }

    /** 当前指纹的**单一哈希**（64 位小写十六进制）—— 落进 {@code sys_document.parse_version}。 */
    public static String version() {
        return versionOf(snapshot());
    }

    /**
     * 把一组指纹折成一个哈希。**任何一段变了，结果就变** —— 这就是
     * `O5` §4 步 3 的判据「任何一项变了，都意味着"旧产物可能不反映当前逻辑"」。
     *
     * <p>单独暴露出来是为了能用例直接钉"改一段 → 哈希变"（不必真去升级依赖）。
     */
    public static String versionOf(Map<String, String> meta) {
        // 先按**固定键序**取（不遍历 map 的顺序）：调用方传 LinkedHashMap 也好、
        // 从 Jackson 读出来的 LinkedHashMap 也好，同一个内容必须得到同一个哈希
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String key : new String[] {KEY_PARSER_VERSION, KEY_PARSER_CONFIG_HASH,
                KEY_GUARD_VERSION, KEY_CLEANING_VERSION}) {
            ordered.put(key, meta.getOrDefault(key, ""));
        }
        return sha256(canonical(ordered));
    }

    // ==================== 各段 ====================

    /** {@code opendataloader@x.y.z·tika@x.y.z}；取不到版本时给 {@code unknown}（不假装知道）。 */
    private static String parserVersion() {
        return "opendataloader@" + dependencyVersion(Config.class,
                "org.opendataloader", "opendataloader-pdf-core")
                + "·tika@" + dependencyVersion(AutoDetectParser.class,
                "org.apache.tika", "tika-core");
    }

    /**
     * 依赖版本，**两条路依次试**。
     *
     * <p>只试一条是不够的，这一点是**实测**出来的：`opendataloader-pdf-core` 的清单里
     * **没有** {@code Implementation-Version}（Tika 有）。只有清单这一条路时，指纹里会出现
     * {@code opendataloader@unknown} —— 而它恰恰是本项目解析 PDF 的主力依赖，
     * "升级依赖"这个最主要的重解析触发条件就失灵了一半，且**没有任何现象**。
     *
     * <ol>
     *   <li>清单的 {@code Implementation-Version}（{@link Package#getImplementationVersion()}）；</li>
     *   <li>Maven 写进 jar 的 {@code META-INF/maven/<groupId>/<artifactId>/pom.properties}
     *       —— 这条覆盖了"清单没写版本"的 jar；</li>
     *   <li>都没有 → {@code unknown}。**不假装知道**，且 {@code ParseFingerprintTest}
     *       有一条断言专门盯住这种情况（真出现了就 red，而不是静静地留着）。</li>
     * </ol>
     */
    private static String dependencyVersion(Class<?> type, String groupId, String artifactId) {
        Package pkg = type.getPackage();
        String fromManifest = pkg == null ? null : pkg.getImplementationVersion();
        if (fromManifest != null && !fromManifest.isBlank()) {
            return fromManifest;
        }
        String path = "META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
        try (java.io.InputStream in = type.getClassLoader().getResourceAsStream(path)) {
            if (in != null) {
                java.util.Properties properties = new java.util.Properties();
                properties.load(in);
                String fromPom = properties.getProperty("version");
                if (fromPom != null && !fromPom.isBlank()) {
                    return fromPom;
                }
            }
        } catch (Exception ex) {
            // 读不到就是读不到（类加载器不同 / 被打包工具剥掉了）—— 退到 unknown 并留痕
            log.debug("依赖版本读不到 pom.properties：{} err={}", path, ex.getMessage());
        }
        return "unknown";
    }

    /**
     * 守卫的签名：**读它的常量**，不是抄一遍。
     *
     * <p>抄一遍的话，改了 {@code MIN_CHARS_PER_PAGE} 而忘了改这里，指纹就不会变 ——
     * 于是"判据变了但所有文档都被认为是最新的"。读常量让这种漂移不可能发生。
     */
    private static String guardSignature() {
        return "minCharsPerPage=" + TextLayerGuard.MIN_CHARS_PER_PAGE;
    }

    // ==================== 工具 ====================

    /** 规范形式：{@code key=value} 逐行。哈希建立在它上面，改格式等于让所有旧指纹失效。 */
    private static String canonical(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        map.forEach((k, v) -> sb.append(k).append('=').append(v == null ? "" : v).append('\n'));
        return sb.toString();
    }

    private static String shortHash(String text) {
        return sha256(text).substring(0, SHORT_HASH_LENGTH);
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必备算法，走不到这里；真到了就是环境坏了，不该静默给个假指纹
            throw new IllegalStateException("JDK 未提供 SHA-256，无法计算解析产物指纹", ex);
        }
    }
}
