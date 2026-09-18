package com.knowledge.ai.core.storage;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 问答图片附件的对象存储（`T-805`）。
 *
 * <p>与 {@link DocStorage} / {@code AvatarStorage} **刻意同构**：同一个 bucket
 * （{@code minio.bucket}）、只把对象名收进一个前缀、**存事实不存 URL**
 * （预签名每次读取时现签）。第三条存储通路照同一形状写，是为了让
 * 「删前缀 = 删一类东西」这条生命周期规则（`T-O07` 的留存规则表）继续成立。
 *
 * <h3>会话归属为什么在这里不存在</h3>
 * <p>预上传发生在**会话创建之前**（需求 `09` FR-SYS-04：图片先传、发送时才生成描述），
 * 所以对象名里只有 {@code userId} 这一层。等 `T-806`/`T-512` 把附件绑到消息上，
 * 归属校验提到会话层 —— 那时**不需要挪对象**，因为对象名从来不承载归属，
 * 归属在 {@code sys_chat_attachment.user_id} 那一列上。
 *
 * <h3>为什么 md5 由**服务端**算，而不是收客户端送来的那个</h3>
 * <p>前端的 {@code ChatView} 用的是 {@code pseudoMd5(f)}（读文件名与大小拼出来的伪值），
 * 而需求 `09` §4.8 把 md5 定为 **VL 描述缓存的 key 前半段**
 * （{@code md5(图片) : vlModelId : PROMPT_VERSION}）。用伪值当缓存 key 的后果是：
 * **两张不同的图会算出同一个 key**，于是第二张图拿到第一张的描述 —— 一个静默的错答。
 * 缓存启用在 `T-512`，但 key 的**取值来源**必须现在就定对，否则那时才发现要回填历史数据。
 *
 * <h3>类型怎么判</h3>
 * <p>两道**交叉**校验，任一不过都拒：
 * <ol>
 *   <li>客户端声明的 {@code Content-Type} 前缀是 {@code image/}；</li>
 *   <li>Tika 按**文件头**探测出的真实类型前缀也是 {@code image/}。</li>
 * </ol>
 * 只看第一条会被"声明成 image/png 的任意字节"绕过；只看第二条则会拒掉合法的
 * 小语种图片（Tika 对少见格式返回 {@code application/octet-stream}）。
 * 两道都过才算数 —— 与 `T-O04` 给文档做的 MIME 交叉校验是同一条纪律。
 *
 * <p>⚠️ **位置上的取舍**：与 {@code DocStorage} 不同，这里**先**判声明类型再判内容
 * —— 因为白名单只有一个前缀（{@code image/}），先判它能让"根本不是图片"立刻拿到
 * 类型错误码，不必白探一次文件头。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatAttachmentStorage {

    /**
     * 单张图片上限：**10 MB**（需求 `09` §4.5「单张 ≤ 10MB」，前端已先拦一道）。
     *
     * <p>后端必须再拦一次 —— 前端那道是**体验**（提前告诉用户不必等），
     * 不是**准入**：绕过前端的成本是改一行 JS。
     */
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    /** 对象名前缀。⚠️ 必须是独立前缀 —— 它决定了生命周期规则能否单独作用于问答附件。 */
    private static final String PREFIX = "chat-attachments/";

    /**
     * 允许的图片格式，**按探测出的 MIME 判**（{@link #extensionOf}）。
     *
     * <p>{@code svg} 刻意**不在**列：SVG 是可执行的 XML，浏览器打开它会执行其中脚本。
     * 头像那边允许它（{@code AvatarStorage} 的 5 种里含 {@code svg}），
     * 但问答附件会被**将来**的 VL 服务取回去解析，暴露面比头像大，本批不收。
     * 白名单只有 {@link #extensionOf} 一处 —— 不另存一份常量：两份必然会漂移。
     */
    private final MinioClient minioClient;

    private final Tika tika = new Tika();

    @Value("${minio.bucket:rag-documents}")
    private String bucket;

    /**
     * 一次上传的**全部字节**（≤10 MB，进内存）。
     *
     * <p>之所以不是"流式转存"：md5 与文件头探测都要看内容，而 {@code MultipartFile}
     * 的流**只能读一次**。10 MB 的上限让"整个读进内存"成为可接受的代价
     * ——换成"先落临时文件再读两遍"要多一遍磁盘 IO，而这里没有异步任务
     * （{@code DocStorage} 那套 {@code spillToTemp} 是为**跨请求生命周期**准备的，本处不需要）。
     */
    public record Stored(String objectKey, String md5, long size, String mime, String extension) {
    }

    /**
     * 校验并转存。
     *
     * @param userId 上传人（对象名的第一层）
     * @return 对象名与**服务端算出的** md5
     * @throws BizException 不合规时抛；消息必须**可执行**（`G4-3`：带现象 + 下一步动作）
     */
    public Stored store(Long userId, MultipartFile file) {
        String declared = file.getContentType();
        if (declared == null || !declared.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw BizException.of(ErrorCode.FILE_TYPE_UNSUPPORTED,
                    "问答附件仅支持图片（PNG / JPG / GIF / WebP / BMP），当前文件类型是「"
                            + (declared == null ? "未声明" : declared) + "」；请换一张图片后重试");
        }
        if (file.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "图片是空的，请重新选择或重新粘贴");
        }
        if (file.getSize() > MAX_BYTES) {
            throw BizException.of(ErrorCode.FILE_TOO_LARGE,
                    "单张图片超过 10 MB 上限（当前 " + (file.getSize() / 1024 / 1024) + " MB）；"
                            + "请压缩后重试，或改用截图粘贴");
        }

        byte[] bytes = readAll(file);
        String detected = detect(bytes);
        if (detected == null || !detected.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw BizException.of(ErrorCode.FILE_TYPE_UNSUPPORTED,
                    "文件内容不像图片（按文件头探测为「" + (detected == null ? "未知" : detected)
                            + "」），与声明的「" + declared + "」不符；请确认这是图片文件而不是改了扩展名");
        }
        String ext = extensionOf(detected);
        if (ext == null) {
            throw BizException.of(ErrorCode.FILE_TYPE_UNSUPPORTED,
                    "不支持的图片格式（探测为「" + detected + "」）；"
                            + "请改用 PNG / JPG / GIF / WebP / BMP —— SVG 暂不支持");
        }

        // 对象名用 UUID 而不是原文件名：原文件名是客户端给的，可能撞名、可能含路径分隔符、
        // 也可能是 emoji。名只用于展示，存在 DB 的 `name` 列里。
        String objectKey = PREFIX + userId + "/" + UUID.randomUUID() + "." + ext;
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new java.io.ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(detected)
                    .build());
        } catch (Exception ex) {
            // 基础设施问题，不是用户输入问题 —— 给系统错误码，原因进日志
            log.error("问答附件转存 MinIO 失败：userId={} object={} size={} err={}",
                    userId, objectKey, bytes.length, ex.getMessage(), ex);
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "图片存储失败，请稍后重试");
        }

        String md5 = md5Of(bytes);
        log.info("问答附件已转存：userId={} object={} size={} mime={}", userId, objectKey, bytes.length, detected);
        return new Stored(objectKey, md5, bytes.length, detected, ext);
    }

    /** 短 TTL 的预签名读地址（口径见 {@link PresignTtl#CHAT_ATTACHMENT_SECONDS}）。 */
    public String presignedUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(PresignTtl.CHAT_ATTACHMENT_SECONDS, TimeUnit.SECONDS)
                    .build());
        } catch (Exception ex) {
            // 拿不到 URL 是展示层问题，不该让整个接口失败（与 AvatarStorage 同一取舍）——
            // 返回 null，前端退化成"缩略图占位"
            log.warn("附件预签名 URL 生成失败：object={} err={}", objectKey, ex.getMessage());
            return null;
        }
    }

    /**
     * 删除对象（落库失败时回收孤儿对象）。
     *
     * <p>与 {@code DocStorage#remove} 同一取舍：删不掉**只留日志**，不抛 ——
     * 对象存储有生命周期规则兜底，为一个回收动作把已经失败的业务再炸一次没有意义。
     */
    public void remove(String objectKey) {
        try {
            minioClient.removeObject(io.minio.RemoveObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build());
        } catch (Exception ex) {
            log.warn("删除附件对象失败（由生命周期规则兜底）：object={} err={}", objectKey, ex.getMessage());
        }
    }

    /** 取对象实际大小，用于核对"真的落进桶了"（对象不存在返回 {@code -1}，不抛）。 */
    public long sizeOf(String objectKey) {
        try {
            return minioClient.statObject(io.minio.StatObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build()).size();
        } catch (Exception ex) {
            log.warn("读取附件对象大小失败（按不存在处理）：object={} err={}", objectKey, ex.getMessage());
            return -1L;
        }
    }

    private byte[] readAll(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readAllBytes();
        } catch (Exception ex) {
            log.error("读取附件流失败：name={} err={}", file.getOriginalFilename(), ex.getMessage(), ex);
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "图片读取失败，请重试");
        }
    }

    /** 按文件头探测真实 MIME。探测失败返回 {@code null}（调用方按"不像图片"处理）。 */
    private String detect(byte[] bytes) {
        try {
            return tika.detect(bytes);
        } catch (Exception ex) {
            log.warn("附件 MIME 探测失败：err={}", ex.getMessage());
            return null;
        }
    }

    /** 探测出的 MIME → 扩展名；不在白名单返回 {@code null}。 */
    private static String extensionOf(String mime) {
        String normalized = mime.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/bmp", "image/x-ms-bmp" -> "bmp";
            default -> null;
        };
    }

    /** 服务端算的 md5（小写十六进制）。见类注释「为什么 md5 由服务端算」。 */
    private static String md5Of(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
        } catch (Exception ex) {
            // MD5 是 JDK 必备算法，走不到这里；真走到了也不该让上传失败 ——
            // md5 目前的消费方（VL 缓存）在 T-512，缺它不影响本批的任何判据
            log.warn("附件 md5 计算失败（留空）：err={}", ex.getMessage());
            return "";
        }
    }
}
