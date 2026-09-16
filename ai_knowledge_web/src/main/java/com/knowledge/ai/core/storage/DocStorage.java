package com.knowledge.ai.core.storage;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Locale;

/**
 * 文档原始文件存储（`T-500` 的落盘侧）。
 *
 * <p>与 {@link com.knowledge.ai.service.AvatarStorage} **刻意同构**：同一个 bucket
 * （{@code minio.bucket}，默认 {@code rag-documents}）、只把对象名收进一个前缀
 * （这里是 {@code documents/}）、存**对象名**而不是 URL。理由与头像那条完全一样 ——
 * 预签名 URL 会过期、换域名/CDN 时形态又变，存 URL 等于把"以后必须迁移全表数据"写进设计。
 * <b>存事实，不存视图。</b>
 *
 * <h3>为什么 ≥5MB 要改走 MinIO 原生 multipart</h3>
 * <p>{@code putObject} 的第三个参数是 {@code partSize}。单次 PUT 与 multipart 的差别
 * 不在"能不能传 100MB"（都能），而在**失败重试的代价**：单次 PUT 传 90MB 时断在第 89MB，
 * 只能整条重来；multipart 只需重传那一段。MinIO 服务端对 <5MB 的对象有单次 PUT 的路径优化，
 * 而对大对象走 multipart 更稳 —— 所以分界取 **5MB**（与 S3/MinIO 自身的 multipart 下限一致）。
 *
 * <p>⚠️ <b>这里的 multipart 是"客户端到 MinIO"的一段，与《06》§3.1 的"分片上传"不是同一件事</b>：
 * 后者指的是**浏览器到后端**的分段（`T-500` 的 `chunked upload`）。本批按用户 2026-09-16 裁定
 * 采用**单请求 multipart**（浏览器一次 POST，服务端内部再分片转存），**断点续传不做**。
 *
 * <h3>对象名的形态</h3>
 * <pre>
 *   documents/{kbId}/{docId}/{safeName}
 * </pre>
 * <ul>
 *   <li>带 {@code kbId}：便于按库做生命周期策略（将来删库时前缀删除一条命令）；</li>
 *   <li>带 {@code docId}：文档删除后其对象名可定位（`T-611`/`T-616` 要用）；</li>
 *   <li>带 {@code safeName}：**保留原文件名**，运维在 MinIO 控制台上能看懂
 *       （纯 id 命名会让排查变成考古）。{@code safeName} 只清洗路径分隔符与控制字符，
 *       **不去中文** —— 它只进库（{@code sys_document.storage_path}），不下发 URL。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocStorage {

    /**
     * 单次 PUT 与 multipart 的分界，也是 multipart 的分段大小。
     *
     * <p>取 S3/MinIO 自身的 multipart 下限（5MB）：小于它的对象走单次 PUT 更快，
     * 大于它的走 multipart 才能"断哪段重传哪段"。
     */
    private static final long PART_SIZE = 5L * 1024 * 1024;

    /** 对象名前缀。 */
    private static final String PREFIX = "documents/";

    /** 文件大小上限：100 MB（《06》§3.1）。**后端兜底**，前端已先拦一道。 */
    private static final long MAX_BYTES = 100L * 1024 * 1024;

    /** 支持的文件扩展名（《06》§3.1 的 9 种）。 */
    private static final java.util.Set<String> ALLOWED_EXT = java.util.Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md");

    private final MinioClient minioClient;

    @Value("${minio.bucket:rag-documents}")
    private String bucket;

    /**
     * 把上传的文件转存进对象存储。
     *
     * @param kbId   所属知识库（只是对象名的一个层级）
     * @param docId  文档 id（{@code sys_document.id}，调用方需**先建行拿 id**）
     * @param file   浏览器上传的原始文件
     * @return 可直接写入 {@code sys_document.storage_path} 的对象名
     */
    public String store(long kbId, long docId, MultipartFile file) {
        validate(file);

        String objectKey = PREFIX + kbId + "/" + docId + "/" + safeName(file.getOriginalFilename());
        // partSize 传 -1 = 单次 PUT；传正数 = multipart（MinIO 客户端自动分段与提交）。
        // 用 stream 三参重载而不是四参：四参的重载需要知道 partCount，
        // 而 partCount 得先算 ceil(size/partSize) —— 那正是客户端在内部做的事，不必自己算。
        long partSize = file.getSize() >= PART_SIZE ? PART_SIZE : -1L;
        try (InputStream in = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, file.getSize(), partSize)
                    .contentType(contentTypeOf(file))
                    .build());
        } catch (Exception ex) {
            // 转存失败是**基础设施**问题，不是用户输入问题 —— 给系统错误码，
            // 但把原因写进日志（用户看到的仍是可读文案，不含栈）
            log.error("文档转存 MinIO 失败：kbId={} docId={} object={} size={} err={}",
                    kbId, docId, objectKey, file.getSize(), ex.getMessage(), ex);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "文件存储失败，请稍后重试");
        }
        log.info("文档已转存：kbId={} docId={} object={} size={} partSize={}",
                kbId, docId, objectKey, file.getSize(), partSize);
        return objectKey;
    }

    /**
     * 取对象的实际大小（判据 8 用它证明"100MB 文件真的落进桶里了"，而不是只看接口返回）。
     *
     * @return 字节数；对象不存在时返回 {@code -1} 而**不抛异常** ——
     *         这是一个"核对用"的读取，调用方（尤其测试）需要能区分"没传上去"与"MinIO 挂了"
     */
    public long sizeOf(String objectKey) {
        try {
            StatObjectResponse stat = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            return stat.size();
        } catch (Exception ex) {
            log.warn("读取对象大小失败（按不存在处理）：object={} err={}", objectKey, ex.getMessage());
            return -1L;
        }
    }

    /**
     * 读回对象字节（`T-502` 解析的入口：源文件在 MinIO，解析要从它开始）。
     *
     * <p>与 {@link #store} 的方向相反、纪律相同：**对象名是唯一事实源**
     * （{@code sys_document.storage_path}），不存 URL、不接受外部路径 ——
     * 入参只能来自我们自己写库的值，杜绝"拼一个 key 去读别人对象"的口子。
     *
     * @return 对象字节；对象不存在时抛 {@code 10044}（文档的存储对象丢了属于资源缺失，
     *         不是参数错误 —— 比如有人手动清过桶）
     */
    public byte[] open(String objectKey) {
        try (InputStream in = minioClient.getObject(io.minio.GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .build())) {
            return in.readAllBytes();
        } catch (Exception ex) {
            log.error("读回文档对象失败：object={} err={}", objectKey, ex.getMessage());
            throw new BizException(ErrorCode.DOC_NOT_FOUND, "文档文件已不存在，请重新上传");
        }
    }

    /**
     * 写解析产物（`T-502` 的产物侧）。
     *
     * <p>对象名 {@code parsed/{kbId}/{docId}/{name}} —— 与源文件的 {@code documents/} 前缀
     * 平行：一个前缀是"用户给的"，一个前缀是"系统产的"，生命周期策略可以分开定
     * （将来"删源留解析产物"或"一起删"都是一条前缀规则的事）。
     *
     * <p>产物与源文件一样**全量覆盖写**：重跑解析会覆盖旧产物 —— 这正是幂等的一部分
     * （同一个 docId 的产物永远只有一份、且是最新解析的结果）。
     *
     * @return 可直接写入 {@code sys_document.parse_path} 的对象名
     */
    public String storeArtifact(long kbId, long docId, String name, byte[] bytes) {
        String objectKey = "parsed/" + kbId + "/" + docId + "/" + safeName(name);
        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    // 产物是 JSON，远小于 5MB 的 multipart 下限，单次 PUT 足够
                    .stream(in, bytes.length, -1)
                    .contentType("application/json")
                    .build());
        } catch (Exception ex) {
            log.error("解析产物写入 MinIO 失败：kbId={} docId={} object={} err={}",
                    kbId, docId, objectKey, ex.getMessage(), ex);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "解析产物存储失败，请稍后重试");
        }
        return objectKey;
    }

    /** 删除对象（文档被彻底删除时用）。**当前无调用方** —— `T-616` 起接入。 */
    public void remove(String objectKey) {
        try {
            minioClient.removeObject(io.minio.RemoveObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build());
        } catch (Exception ex) {
            // 与 sizeOf 同一取舍：删不掉不该让业务失败（对象存储有生命周期规则兜底）
            log.warn("删除对象失败：object={} err={}", objectKey, ex.getMessage());
        }
    }

    // ==================== 校验 ====================

    /**
     * 校验类型与大小。
     *
     * <p>**public 而不是 private**：调用方（{@code DocService#upload}）需要在**建行之前**
     * 就拦掉不合法的文件 —— 否则会先插一条永远转存不成功的行，再靠失败标记收尾，
     * 白白污染文档列表（用户会看到一条"解析失败"其实根本没接收过）。
     * 校验与转存都属"这个文件能不能进来"这一件事，放在同一个类里合适。
     *
     * <p><b>类型按扩展名判，不按 Content-Type</b> —— 与 {@code AvatarStorage} 的取舍**相反**，
     * 且是刻意的：头像只认 {@code image/*} 是一个很短的封闭集合，Content-Type 撒谎的成本低；
     * 而文档有 9 种格式、浏览器给出的 Content-Type 五花八门（{@code .md} 常是
     * {@code text/plain}、{@code .docx} 可能是 {@code application/octet-stream}），
     * 按它判会造成**大量误拒**。真正的防护在解析阶段（Tika 按**文件头**识别），
     * 那里才是"内容到底是什么"的权威判断点。
     *
     * <p>报错必须可执行（`G4-3`：不许只说"失败"两字）—— 消息里带上被拒的扩展名与支持清单。
     */
    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请选择要上传的文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw BizException.of(ErrorCode.FILE_TOO_LARGE,
                    "文件超过 100 MB 上限（当前 " + (file.getSize() / 1024 / 1024) + " MB）");
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXT.contains(ext)) {
            throw BizException.of(ErrorCode.FILE_TYPE_UNSUPPORTED,
                    "不支持的文件类型「" + (ext.isEmpty() ? "无扩展名" : ext) + "」，"
                            + "仅支持 PDF / DOC / DOCX / XLS / XLSX / PPT / PPTX / TXT / MD");
        }
    }

    /** 由文件名取小写扩展名；没有扩展名返回空串。 */
    public static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 去掉扩展名的文件名（= {@code sys_document.title}）。 */
    public static String titleOf(String filename) {
        if (filename == null || filename.isBlank()) {
            return "未命名文件";
        }
        int dot = filename.lastIndexOf('.');
        String title = dot > 0 ? filename.substring(0, dot) : filename;
        return title.isBlank() ? "未命名文件" : title;
    }

    /**
     * 对象名里的文件名部分：只清洗路径分隔符与控制字符。
     *
     * <p>不做拼音/哈希替换 —— 运维要在 MinIO 控制台上看懂这是哪个文件，
     * 把中文抹掉等于主动放弃这条排查线索。
     */
    private static String safeName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unnamed";
        }
        String cleaned = filename.replace('\\', '_').replace('/', '_')
                .replaceAll("[\\p{Cntrl}]", "_");
        // 对象名总长有限，留出前缀与 id 的空间
        return cleaned.length() > 180 ? cleaned.substring(cleaned.length() - 180) : cleaned;
    }

    /** Content-Type 只用于让浏览器直接打开时有个合理的默认（下载链路），不参与校验。 */
    private static String contentTypeOf(MultipartFile file) {
        String ct = file.getContentType();
        return ct == null || ct.isBlank() ? "application/octet-stream" : ct;
    }
}
