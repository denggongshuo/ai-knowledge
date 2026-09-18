package com.knowledge.ai.service;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import com.knowledge.ai.core.storage.PresignTtl;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * 头像存储（T-111 的 {@code POST /user/avatar}）。
 *
 * <p>复用**同一个 MinIO bucket**（{@code minio.bucket}，默认 {@code rag-documents}），
 * 只是把对象名收进 {@code avatars/} 前缀。为什么不单独建一个桶：桶在
 * {@code MinioBucketInitializer} 里创建，多一个桶就多一处启动期依赖与一份策略配置，
 * 而头像与文档在存储层面没有任何差别（都是私有对象 + 预签名读取）。
 *
 * <h3>落库存什么</h3>
 * <p>{@code sys_user.avatar} 存的是**对象名**（object key），不是访问 URL。
 * 理由是 URL 有两种且都会变：预签名 URL 会过期（本项目取 7 天），
 * 换成自定义域名 / CDN 时又是另一种形态 —— 存 URL 等于把"以后必须迁移全表数据"
 * 写进设计。{@link #presignedUrl} 在读取时按需换取，随时可以改域名而不用动数据。
 *
 * <p>这与 V1 建表注释里"派生值不落库"是同一条原则的延伸：**存事实，不存视图**。
 *
 * <h3>校验</h3>
 * <p>类型按 {@code Content-Type} 前缀判 {@code image/}，大小上限 {@value #MAX_BYTES} 字节。
 * 不看扩展名 —— 扩展名是客户端说了算的，Content-Type 至少还要求它撒一个谎。
 * （真正的防注入要靠对象存储的私有策略 + 不做 HTML 响应，见 {@code MinioBucketInitializer}
 * 创建的私有桶；本类只做"明显不是图片的就挡掉"。）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AvatarStorage {

    /** 头像大小上限：2 MB。头像这一用途不需要更大，而大文件会拖慢每一次资料页加载。 */
    private static final long MAX_BYTES = 2L * 1024 * 1024;

    /** 对象名前缀。 */
    private static final String PREFIX = "avatars/";

    /**
     * 预签名读取有效期。
     *
     * <p>取值搬到 {@link PresignTtl#AVATAR_DAYS} 了（`T-O06` 步 4 的「口径集中」半边，
     * `S5-M08-C`）—— 三处预签名的 TTL 散在三个类里时，改的人只看得见自己那一处，
     * 而它们背后是**三种不同的取舍**。理由写在那边。
     */
    private static final int URL_TTL_DAYS = PresignTtl.AVATAR_DAYS;

    private final MinioClient minioClient;

    @Value("${minio.bucket:rag-documents}")
    private String bucket;

    /**
     * 存入头像并返回对象名。
     *
     * @return 可直接写入 {@code sys_user.avatar} 的对象名
     */
    public String store(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请选择要上传的头像文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw BizException.of(ErrorCode.FILE_TOO_LARGE, "头像不能超过 2 MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
            throw BizException.of(ErrorCode.FILE_TYPE_UNSUPPORTED, "头像仅支持图片格式");
        }

        // 对象名带时间戳：换头像即产生新对象，旧对象靠生命周期规则清理。
        // 用固定名（如 avatars/{userId}.png）会被浏览器与 CDN 缓存住"旧头像"，
        // 换完刷新页面还是老图 —— 这类问题排查起来很费时间。
        String objectKey = PREFIX + userId + "/" + System.currentTimeMillis() + extension(contentType);
        try (InputStream in = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, file.getSize(), -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception ex) {
            log.warn("头像上传失败：userId={} err={}", userId, ex.getMessage());
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "头像上传失败，请稍后重试");
        }
        log.info("头像已上传：userId={} object={}", userId, objectKey);
        return objectKey;
    }

    /**
     * 把对象名换成可访问的预签名 URL。
     *
     * <p>对象名是空 / 已是 http 开头时原样返回：前者是"没设过头像"，
     * 后者是历史数据里可能存在的绝对 URL（mock 时代留下的 {@code /mock-avatar/...}）。
     * 这两种情况都不该让资料页报错。
     */
    public String presignedUrl(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.startsWith("http")) {
            return objectKey;
        }
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(URL_TTL_DAYS, TimeUnit.DAYS)
                    .build());
        } catch (Exception ex) {
            // 拿不到 URL 是展示层问题，不该让整个资料接口失败
            log.warn("头像预签名 URL 生成失败（返回对象名）：object={} err={}", objectKey, ex.getMessage());
            return objectKey;
        }
    }

    /** 由 Content-Type 推一个扩展名；认不出就用 {@code .bin}。 */
    private static String extension(String contentType) {
        return switch (contentType.toLowerCase(java.util.Locale.ROOT)) {
            case "image/png" -> ".png";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            default -> ".bin";
        };
    }
}
