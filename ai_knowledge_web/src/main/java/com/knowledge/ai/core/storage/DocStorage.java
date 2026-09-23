package com.knowledge.ai.core.storage;

import com.knowledge.ai.core.exception.BizException;
import com.knowledge.ai.core.exception.ErrorCode;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
 *
 * <h3>删除：本类不定义"什么时候删"，只提供"怎么删"</h3>
 * <p>「删一篇文档 / 删一个知识库到底删了什么」是一张**明文规则表**，在
 * <b>{@code docs/后端开发文档/11-M05-存储生命周期规则（O7）.md}</b>（`优化/O7`，2026-09-17）。
 * 改本类的删除行为前先读它 —— 那张表与本类的实现互为反证：
 * 例如它写着"删除知识库**不动物件**"（软删可恢复），所以 {@link #remove} 在 `T-407`
 * 落地时**刻意没有被接上**。谁哪天"顺手"在删库里加了 {@code remove(...)}，那张表就是发现它的依据。
 *
 * <p>{@link #remove} 目前仍无调用方（`T-616` 彻底删除时接），这**不是遗漏**。
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

    /**
     * 解析产物的对象名前缀（`T-502`）。
     *
     * <p>⚠️ 提成常量是 `T-616` 顺手做的：原来它是 {@code storeArtifact} 里的一个字面量，
     * 而库级彻底删除（要按前缀整块删）需要**同一个值**。两处各写一遍字面量的话，
     * 改了其中一处就会让"删掉的"与"写下的"不是同一批对象 —— 那是**删不干净的静默形态**。
     */
    private static final String ARTIFACT_PREFIX = "parsed/";

    /**
     * 打包下载产物的对象名前缀（`T-614`）。
     *
     * <p>⚠️ **必须是独立前缀**：它决定了生命周期规则能否单独作用于打包产物
     * （与 {@code ChatAttachmentStorage.PREFIX} 同一条理由）。打包产物是**临时物**
     * —— 与源文档"长期保存"的寿命完全不同，混在 {@code documents/} 下就没法
     * 只对前者配过期规则。
     *
     * <p>⚠️ 前缀的第二段是**用户 id**（{@code ProbeCleanup} 的
     * {@code BATCH_DOWNLOAD_PREFIX} 登记按它认归属）—— 与
     * {@code ChatAttachmentStorage} 的 {@code chat-attachments/{userId}/...} 同构。
     */
    public static final String BATCH_PREFIX = "batch-download/";

    /**
     * 打包产物的**固定叶子名**。
     *
     * <p>为什么是常量而不是"带时间戳的名字"：对象名要能从
     * {@code ownerId + taskId} **推出来**（见 {@link #batchZipObjectKey}），
     * 推出对象名之后，"这个任务的文件多大、地址是什么"就都能在**重启之后**答出来
     * —— 而任务结果（{@code payload}）是不落库的。
     * 时间戳名字会让这件事需要一个额外的映射表，那是本批明确不做的。
     */
    private static final String BATCH_ZIP_LEAF = "documents.zip";

    /**
     * 打包产物的**对外名字**（= 对象名的叶子段）。`T-615` 的 `DownloadTask.name` 用它。
     *
     * <p>公开出去是为了让"对象叫什么"与"界面上显示叫什么"只有一个来源：
     * 服务层从对象名里取叶子段来显示，一旦有人改了叶子名而忘了改展示文案，
     * 用户看到的文件名就会与实际下到的对不上。
     */
    public static final String BATCH_ZIP_NAME = BATCH_ZIP_LEAF;

    /**
     * 下载记录清单（CSV）的对象名前缀。`T-615` 新增。
     *
     * <p>与 {@code audit-export/} / {@code ops-export/} 同构：**导出件单独一个前缀**，
     * 好让生命周期规则能只作用于它（导出件是临时物，不该跟着源文档一起永久留着）。
     */
    public static final String DOC_EXPORT_PREFIX = "doc-export/";

    /** 文件大小上限：100 MB（《06》§3.1）。**后端兜底**，前端已先拦一道。 */
    private static final long MAX_BYTES = 100L * 1024 * 1024;

    /** 支持的文件扩展名（《06》§3.1 的 9 种）。 */
    private static final java.util.Set<String> ALLOWED_EXT = java.util.Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md");

    private final MinioClient minioClient;

    /** `T-O04` 的按内容准入检查（MIME 交叉校验 / ZIP 炸弹 / 加密）。 */
    private final IngestGuard ingestGuard;

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
        try (InputStream in = file.getInputStream()) {
            return putObject(kbId, docId, in, file.getOriginalFilename(), file.getSize(), contentTypeOf(file));
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("读取上传流失败：kbId={} docId={} err={}", kbId, docId, ex.getMessage(), ex);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "文件存储失败，请稍后重试");
        }
    }

    // ==================================================================
    // `S4-E1` 新增：把上传字节**与请求生命周期解耦**
    //
    // 为什么必须做：`MultipartFile` 是**请求作用域**的 —— 请求一结束，Servlet 容器
    // 就清掉底层的 multipart 临时文件，`file.isEmpty()` 随之变 true。而转存跑在
    // **异步任务**里（`T-006` 的立意：100MB 不让请求线程挂着），于是整条上传链路
    // 一直在**赌一个竞态**：任务必须在请求结束之前读完这个文件。
    //
    // 这个竞态在 `S4-E1` 之前一直侥幸赢着（任务线程从提交到读文件几乎不做事），
    // 直到本批给它加了两次进度落库 —— 十几毫秒的延迟就让它**确定性地输**。
    // 现象极具迷惑性：任务落库为 FAILED，`error_msg` 是
    // 「请选择要上传的文件」（`validate` 的 `isEmpty()` 分支），看起来像前端没传文件。
    //
    // 修法不是"把落库挪走好让竞态继续赢"，而是**把字节定下来**：
    // 在请求线程把它复制到一个临时文件，异步任务从该文件转存，用完即删。
    // 代价是每次上传多一遍本地磁盘写（100MB ≈ 百毫秒级），换来的是
    // **不再依赖任何时序**。见 `plan/2026-09-17-S4-E1.md` §9 遗留。
    // ==================================================================

    /**
     * 把上传字节复制到临时文件，返回该路径（**在请求线程调用**）。
     *
     * <p>调用方负责删除 —— 见 {@link #discardTemp}。异步任务成功与否都要删
     * （否则 100MB 的临时文件会以"每篇文档一份"的速度把磁盘吃光）。
     */
    public Path spillToTemp(MultipartFile file) {
        try {
            Path tmp = Files.createTempFile("kb-upload-", ".bin");
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            return tmp;
        } catch (Exception ex) {
            log.error("上传文件落暂存失败：name={} size={} err={}",
                    file.getOriginalFilename(), file.getSize(), ex.getMessage(), ex);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "文件存储失败，请稍后重试");
        }
    }

    /** 删掉暂存文件。**静默**：它是收尾动作，删不掉不该把已经成功的转存变成失败（只留日志）。 */
    public void discardTemp(Path staged) {
        if (staged == null) {
            return;
        }
        try {
            Files.deleteIfExists(staged);
        } catch (Exception ex) {
            log.warn("删除上传暂存文件失败（不影响业务）：path={} err={}", staged, ex.getMessage());
        }
    }

    /**
     * 从暂存文件转存（**异步任务调用**）。
     *
     * <p>与 {@link #store} 共用同一段 PUT 逻辑（{@link #putObject}）——
     * 两处实现必然漂移，本仓已经吃过这个亏。
     */
    public String storeFromFile(long kbId, long docId, Path source, String fileName,
                                long size, String contentType) {
        try (InputStream in = Files.newInputStream(source)) {
            return putObject(kbId, docId, in, fileName, size, contentType);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("打开上传暂存文件失败：path={} err={}", source, ex.getMessage(), ex);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "文件存储失败，请稍后重试");
        }
    }

    /** 两个入口共用的 PUT。 */
    private String putObject(long kbId, long docId, InputStream in, String fileName,
                             long size, String contentType) {
        String objectKey = PREFIX + kbId + "/" + docId + "/" + safeName(fileName);
        // partSize 传 -1 = 单次 PUT；传正数 = multipart（MinIO 客户端自动分段与提交）。
        // 用 stream 三参重载而不是四参：四参的重载需要知道 partCount，
        // 而 partCount 得先算 ceil(size/partSize) —— 那正是客户端在内部做的事，不必自己算。
        long partSize = size >= PART_SIZE ? PART_SIZE : -1L;
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, size, partSize)
                    .contentType(contentType == null || contentType.isBlank()
                            ? "application/octet-stream" : contentType)
                    .build());
        } catch (Exception ex) {
            // 转存失败是**基础设施**问题，不是用户输入问题 —— 给系统错误码，
            // 但把原因写进日志（用户看到的仍是可读文案，不含栈）
            log.error("文档转存 MinIO 失败：kbId={} docId={} object={} size={} err={}",
                    kbId, docId, objectKey, size, ex.getMessage(), ex);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "文件存储失败，请稍后重试");
        }
        log.info("文档已转存：kbId={} docId={} object={} size={} partSize={}",
                kbId, docId, objectKey, size, partSize);
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
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
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
     * 取对象的**原始输入流**（不读进内存）—— `T-614` 打包下载的入口。
     *
     * <p>与 {@link #open(String)} 的差别是"字节去哪儿"：{@code open} 的消费方
     * （解析、扫描）要的是**一整块字节**（它们要么交给 PDF 库、要么交给杀毒引擎），
     * 而打包要的是**把字节搬进 zip 条目** —— 后者没有理由先落一个 {@code byte[]}。
     *
     * <p>⚠️ 两者都不会把"整批文件"同时读进内存：调用方**逐个**取、**逐个**写。
     * 100MB 的单篇上限意味着峰值是一篇的大小，与"50 篇"无关。
     *
     * <p>🔴 **调用方必须关闭它**（try-with-resources）。它是 MinIO 的 HTTP 响应体，
     * 不关就是连接泄漏 —— 而连接池耗尽后，所有 MinIO 操作（含上传）会一起挂。
     *
     * @return 对象输入流（**未**自动关闭）
     * @throws BizException 对象不存在时抛 {@code 10044}（理由同 {@link #open}）
     */
    public InputStream stream(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception ex) {
            // ⚠️ 注意这里只覆盖"建流就失败"那一半。对象不存在时 MinIO 往往先回 200 头、
            //    真正的 404 在**第一次 read 时**才抛出 —— 那一半由调用方（写 zip 条目处）
            //    捕获，见 DownloadService 的说明。
            log.error("打开文档对象流失败：object={} err={}", objectKey, ex.getMessage());
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
        String objectKey = ARTIFACT_PREFIX + kbId + "/" + docId + "/" + safeName(name);
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

    /**
     * 抽出的一张图的**对象名**（`T-512` 步 2b）。
     *
     * <p>{@code images/{kbId}/{docId}/{name}} —— 与源文件 {@code documents/}、
     * 产物 {@code parsed/} **三个前缀平行**。
     *
     * <h3>为什么单开一个前缀，而不是塞进 {@code parsed/}</h3>
     * <p>图片与解析产物的**生命周期不同**：`O7` 的规则表（{@code 11-M05-存储生命周期规则（O7）.md}）
     * 要能单独定"删图留产物"或"重建索引时重抽图"这类规则 —— 混进 {@code parsed/} 之后，
     * 按前缀删除就再也拆不开了。三个前缀 = 三条能各自定的规则。
     *
     * <p>🔴 <b>它是 {@code static} 且与 {@link #putImage} 分开，是为了让调用方
     * "先知道对象名、后写字节"</b> —— 见 {@code DocParseService.ImageBuffer} 的 javadoc：
     * 图必须在文档被判失败时**根本不要写**，而 {@code ImageInfo} 又要在解析期就拿到
     * 真 objectKey（下游的块与落库都按它走）。两件事都要，只有把"起名"和"写入"拆开。
     */
    public static String imageObjectKey(long kbId, long docId, String name) {
        return "images/" + kbId + "/" + docId + "/" + safeName(name);
    }

    /**
     * 打包产物的**对象名**（`T-614`）。
     *
     * <p>🔴 <b>它只由 {@code ownerId + taskId} 决定，是个可推导的纯函数</b> ——
     * 这是"重启后还能给出下载地址"的全部依据：任务行（{@code sys_async_task}）
     * 里就有这两个值，于是 {@code GET /doc/download-tasks/{taskId}} 不需要
     * 任务结果（不落库）也能知道文件在哪、多大。
     *
     * <p>与 {@link #imageObjectKey} 同一种形状：**起名**与**写入**拆开，
     * 好让"要写哪个名字"在写入之前就定得下来（异步体里 taskId 才有值）。
     *
     * <p>⚠️ {@code taskId} 是 {@code TraceContext.newTraceId()} 产的十六进制串
     * （{@code [0-9a-f]}），不含路径分隔符 —— 但它仍然过一遍 {@link #safeName}：
     * 将来若换了生成器，"对象名里出现 {@code ../}"这种事不该由调用方去保证。
     */
    public static String batchZipObjectKey(long ownerId, String taskId) {
        return BATCH_PREFIX + ownerId + "/" + safeName(taskId) + "/" + BATCH_ZIP_LEAF;
    }

    /**
     * 写一张图的字节（对象名由 {@link #imageObjectKey} 起好）。
     *
     * <p>⚠️ 与 {@code storeArtifact} 同一条取舍：**全量覆盖写**（同名对象覆盖），
     * 重跑解析天然幂等。单图有上限（{@code PdfDocumentParser.MAX_IMAGE_BYTES}，默认 2MB），
     * 远小于 multipart 的 5MB 下限，单次 PUT 足够。
     */
    public void putImage(String objectKey, byte[] bytes) {
        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, bytes.length, -1)
                    .contentType("image/jpeg")
                    .build());
        } catch (Exception ex) {
            log.error("图片写入 MinIO 失败：object={} size={} err={}",
                    objectKey, bytes.length, ex.getMessage(), ex);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "图片存储失败，请稍后重试");
        }
    }

    /** 删除对象（文档被彻底删除时用）。**当前无调用方** —— `T-616` 起接入。 */
    public void remove(String objectKey) {        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build());
        } catch (Exception ex) {
            // 与 sizeOf 同一取舍：删不掉不该让业务失败（对象存储有生命周期规则兜底）
            log.warn("删除对象失败：object={} err={}", objectKey, ex.getMessage());
        }
    }

    /**
     * **按前缀**删掉一批对象，返回实际删掉的个数（`T-616` 的库级彻底删除用）。
     *
     * <h3>为什么需要它 —— 与 {@link #remove(String)} 的分工</h3>
     * <p>库级彻底删除要清的是一整块：`documents/{kbId}/**` 与 `parsed/{kbId}/**`
     * （`O7` 规则表的第 ②/⑥ 列：源文件与解析产物**都删**）。
     * 那时**文档行也一起没了** ⇒ 没法再枚举 `storage_path` 逐个删，只能按前缀扫。
     *
     * <p>⚠️ 与之相对，**文档级**彻底删除**继续用 {@link #remove(String)} 逐个删**
     * （路径就在那一行里，精确删比按前缀扫更安全）—— 别为了"统一"把精确的那条也换掉。
     *
     * <h3>🔴 它与 {@code remove} 的取舍**刻意相反**：失败要抛</h3>
     * <p>{@code remove} 吞异常是因为它在"尽力而为"的路径上（对象存储有生命周期规则兜底）。
     * 而这里**必须让调用方知道没删掉**：需求 `06` §7.9 的原话是「清理失败会造成**存储泄漏**」
     * —— 把失败吞成 warn，用户会以为空间已经释放了，而实际上一个字节都没少。
     *
     * <p>⚠️ **调用方必须遵守的顺序**：先删对象与向量、**再删数据库行**。
     * 反过来（先删行）一旦对象删除失败，那些对象的唯一线索（`storage_path`）就没了
     * ⇒ 它们变成**谁也找不到的孤儿**，正是本仓反复清的那一族。
     * 按这个顺序，失败时行还在、用户重试即可（MinIO 的 remove 对不存在的键是幂等的）。
     *
     * @param prefix 带斜杠的前缀，如 {@code documents/123/}；**不接受空串或裸斜杠**
     *               （那等于"删掉整个桶"，而调用方绝不会有这个意图 —— 拦在这里比事后追悔便宜）
     * @return 实际删掉的对象个数
     */
    public int removePrefix(String prefix) {
        if (prefix == null || prefix.isBlank() || "/".equals(prefix.trim())) {
            // ⚠️ 空前缀在 listObjects 里等于"列出全桶" ⇒ 传错就是灭顶之灾。
            //    这不是"防御性编程"，是这个参数的**误用后果与后果的不可逆性**不成比例。
            throw new BizException(ErrorCode.PARAM_INVALID, "对象前缀不能为空");
        }
        int removed = 0;
        try {
            for (Result<Item> result : minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket).prefix(prefix).recursive(true).build())) {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucket).object(result.get().objectName()).build());
                removed++;
            }
        } catch (Exception ex) {
            log.error("按前缀删除对象失败（已删 {} 个后中断）：prefix={} err={}",
                    removed, prefix, ex.getMessage(), ex);
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "文件清理失败（已清理 " + removed + " 个），请稍后重试");
        }
        log.info("按前缀删除对象完成：prefix={} removed={}", prefix, removed);
        return removed;
    }

    /**
     * **按前缀**删掉一批对象，但**留下 {@code keep} 里点名的那些**（`S9-M12-C` 步 14 · 销 `#223`）。
     *
     * <h3>🔴 为什么需要它 —— `removePrefix` 在库级删除上会误删</h3>
     * <p>对象名与知识库是**绑死**的：{@code documents/{kbId}/{docId}/...} 由上传时的 kbId 决定。
     * 而 {@code DocReindexService#moveToKb}（`G7-4`）只改 {@code sys_document.kb_id} /
     * {@code sys_chunk.kb_id} + 删**旧分区**的向量 —— **一个字节的对象都不搬**。
     * 于是「从 A 库移到 B 库」的那篇文档，行在 B 库、**对象还躺在 A 库的前缀下**。
     * 此时彻底删除 A 库，`removePrefix("documents/{A}/")` 会**连它的源文件一起删掉**，
     * 而 B 库里那一行还在、状态还是 {@code READY} —— 用户点下载拿不到文件，
     * 且**没有任何地方报错**（`remove` 是静默的），排查时看 B 库一切正常。
     *
     * <h3>为什么是"排除集"而不是"逐行删"</h3>
     * <p>逐行删要调 N 次 MinIO，且库级删除本来就是「整库对象一起没」的语义 ——
     * 真正需要保护的只是**凤毛麟角的"借住"对象**。所以保持按前缀列举的一次遍历，
     * 只在删除那一步跳过点名项。这样既没有 N 次往返，也不丢"整块"的语义。
     *
     * @param prefix 要清的前缀（同 {@link #removePrefix} 的非空校验）
     * @param keep   动不得的对象名（**空集 = 与 {@code removePrefix} 等价**）。
     *               ⚠️ 传进来的应当是**仍然被活行引用**的路径 —— 由调用方负责查准
     * @return 实际删掉的对象个数（跳过的不计）
     */
    public int removePrefixExcept(String prefix, java.util.Set<String> keep) {
        if (prefix == null || prefix.isBlank() || "/".equals(prefix.trim())) {
            // 与 removePrefix 同一道闸，同一理由（见那边：空前缀 = 列出全桶）
            throw new BizException(ErrorCode.PARAM_INVALID, "对象前缀不能为空");
        }
        java.util.Set<String> protectedKeys = keep == null ? java.util.Set.of() : keep;
        int removed = 0;
        int skipped = 0;
        try {
            for (Result<Item> result : minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket).prefix(prefix).recursive(true).build())) {
                String name = result.get().objectName();
                if (protectedKeys.contains(name)) {
                    // ⚠️ 这一格是 `#223` 的落点：跳过而不是删。它上面的行还在**别的库**里
                    skipped++;
                    continue;
                }
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucket).object(name).build());
                removed++;
            }
        } catch (Exception ex) {
            log.error("按前缀删除对象失败（已删 {} 个 / 跳过 {} 个后中断）：prefix={} err={}",
                    removed, skipped, prefix, ex.getMessage(), ex);
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "文件清理失败（已清理 " + removed + " 个），请稍后重试");
        }
        log.info("按前缀删除对象完成：prefix={} removed={} skipped={}", prefix, removed, skipped);
        return removed;
    }

    // ==================== 库级前缀（`T-616`）====================

    /**
     * 某个知识库的**源文件**前缀 —— 库级彻底删除时按它整块清（`T-616`）。
     *
     * <p>⚠️ 与 {@link #kbParsedPrefix} 一起，是本类**唯一**两处"按前缀删一大片"的输入。
     * 给出它们而不是让调用方自己拼字符串：拼错一个字符的后果是
     * **一个字节都没删掉而调用方以为删了**（`removePrefix` 只认字面量匹配，
     * 前缀对不上就一个对象都列不出来，且**不报错** —— 那是静默失败）。
     *
     * <p>⚠️ 末尾**必须带斜杠**：不带的话 `documents/12/` 会匹配到 `documents/123/...`
     * —— 删 A 库的文件顺手删掉 B 库的，且没有任何迹象。
     */
    public static String kbDocPrefix(long kbId) {
        return PREFIX + kbId + "/";
    }

    /** 某个知识库的**解析产物**前缀（同上）。 */
    public static String kbParsedPrefix(long kbId) {
        return ARTIFACT_PREFIX + kbId + "/";
    }

    // ==================== 打包下载（`T-614`）====================

    /**
     * 把**已经整好的** zip 文件传上去（`T-614` 的产物侧）。
     *
     * <p>为什么收 {@link Path} 而不是 {@code byte[]}：zip 是把 N 篇源文档**聚合**出来的，
     * 它的体积上限是 {@code 2GB}（门禁 `G7-8`）—— 那不是一个能放进堆里的数。
     * 调用方（{@code DownloadService}）先把 zip 落在**本地临时文件**上，
     * 这里再以流的方式推给 MinIO：全程只有"一篇文档"级别的内存占用。
     *
     * <p>⚠️ 本方法**不删**那个临时文件 —— 删除由调用方在 finally 里做
     * （与 {@link #storeFromFile} 对暂存文件的分工一致：谁落的盘谁负责收）。
     *
     * @param objectKey 由 {@link #batchZipObjectKey} 起好（写入方与读取方共用同一个函数，
     *                  没有"两处各拼一次名字"的漂移面）
     * @param size      zip 的字节数（必须是**已落盘的真实长度**，用于 multipart 分段）
     */
    public void storeBatchZip(String objectKey, Path zipFile, long size) {
        try (InputStream in = Files.newInputStream(zipFile)) {
            // 与 putObject 同一取舍：≥5MB 走 multipart（断哪段重传哪段），
            // 小于它单次 PUT 更快。2GB 的产物必然走 multipart。
            long partSize = size >= PART_SIZE ? PART_SIZE : -1L;
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, size, partSize)
                    .contentType("application/zip")
                    .build());
        } catch (Exception ex) {
            // 与两个导出服务同一条取舍：写盘失败 ⇒ 任务**明确失败**
            // （不能返回"成功但没文件"的结果 —— 用户会以为打好了）
            log.error("打包产物写入 MinIO 失败：object={} size={} err={}",
                    objectKey, size, ex.getMessage(), ex);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "打包文件写入失败，请稍后重试");
        }
        log.info("打包产物已写入：object={} size={}", objectKey, size);
    }

    /**
     * 下载记录清单（CSV）的**对象名**（`T-615`）。与 {@link #batchZipObjectKey} 同形：
     * 起名与写入拆开，写入方在异步体里才拿得到 taskId / 时刻。
     *
     * @param stamp 形如 {@code 20260922-153000}（时间戳进名字是为了同一人多次导出**不互相覆盖**
     *              —— 与 `batchZipObjectKey` 不同：那边一个任务只会产出一个文件，
     *              而"导出"是用户可以反复点的动作）
     */
    public static String docExportObjectKey(long ownerId, String stamp) {
        return DOC_EXPORT_PREFIX + ownerId + "/" + safeName(stamp) + "-downloads.csv";
    }

    /**
     * 写一小段字节（导出件用）。小对象单次 PUT 足够 —— 调用方保证
     * {@code bytes} 是能放进堆里的量级（导出 CSV 是最坏几 MB 的文本，
     * 与 {@link #storeBatchZip} 面对的 2GB 完全是两回事）。
     */
    public void putBytes(String objectKey, byte[] bytes, String contentType) {
        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, bytes.length, -1)
                    .contentType(contentType == null || contentType.isBlank()
                            ? "application/octet-stream" : contentType)
                    .build());
        } catch (Exception ex) {
            log.error("字节写入 MinIO 失败：object={} size={} err={}",
                    objectKey, bytes.length, ex.getMessage(), ex);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "导出文件写入失败，请稍后重试");
        }
    }

    /**
     * 预签名 GET 地址（`T-613`/`T-614`）。
     *
     * <p>🔴 <b>不落库、不缓存</b> —— 每次调用**现签**。理由与
     * {@code SysChatAttachment} 的类注释同一条：预签名 URL 自带 TTL，
     * 存下来就等于存一个必然过期的字符串。
     *
     * <p>{@code response-content-disposition} 决定浏览器是**直接打开**还是**另存为**：
     * <ul>
     *   <li>{@code inline} —— 预览（能在标签页里直接看 PDF）；</li>
     *   <li>{@code attachment} —— 下载（强制落盘，不会把 pdf 变成新标签页）。</li>
     * </ul>
     * 两者走的是**同一个对象**，只是让 MinIO 回一个不同的响应头 ——
     * 所以这里是一个参数，不是两个方法。
     *
     * <p>⚠️ 刻意**不带 {@code filename}**：带上就要处理中文名的 RFC 5987 编码，
     * 而 MinIO SDK 会对 {@code extraQueryParams} 的值再做一次 URL 编码
     * （写进去的 {@code %} 会变成 {@code %25}，签名与实际请求对不上）。
     * 不带时浏览器按对象名的最后一段命名 —— 源文档的叶子名**就是**原始文件名，
     * 打包产物是 {@code documents.zip}，两者都是可读的。
     *
     * @param ttl         有效期。⚠️ 用 {@link Duration} 而不是"分钟数"：本仓的预签名 TTL
     *                    同时存在**分钟**档（文档下载 10 分钟）与**小时**档（导出件 24 小时），
     *                    收成一个 {@code int} 就得在调用点写 {@code * 60}，
     *                    而那个乘数是"单位悄悄换了"最容易被漏掉的地方
     *                    （{@link PresignTtl} 的类注释正是为这件事存在的）
     * @param disposition {@code inline} 或 {@code attachment}
     * @return 预签名 URL；**失败时返回 {@code null} 而不抛** —— 与
     *         {@code AvatarStorage} / 两个导出服务同一条取舍：能不能"把地址拼出来"
     *         与"文件在不在"是两件事，前者失败不该把后者说成失败。
     *         ⚠️ 但**要不要因此失败由调用方决定**：同步点击的接口（用户要的就是地址）
     *         必须自己判空后报错，异步产物的展示可以降级成"地址暂不可用"
     */
    public String presignedGet(String objectKey, Duration ttl, String disposition) {
        // MinIO 的 expiry 是 (int 分钟) 粒度：向上取整，免得"30 秒"被抹成 0（那会直接报错）
        long minutes = Math.max(1L, ttl.toMinutes() + (ttl.toSecondsPart() > 0 ? 1 : 0));
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry((int) minutes, TimeUnit.MINUTES)
                    .extraQueryParams(Map.of("response-content-disposition", disposition))
                    .build());
        } catch (Exception ex) {
            log.warn("预签名 URL 生成失败（文件本身可能仍在）：object={} err={}",
                    objectKey, ex.getMessage());
            return null;
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
        // `T-O04`：前三道都是"按名字"判（闸），这里补"按内容"判（防）——
        // 真实类型交叉校验 + ZIP 炸弹限额 + 加密检测。见 IngestGuard 的类注释。
        //
        // ⚠️ 位置在**白名单之后**：扩展名不在白名单时先给 10034（用户能立刻明白是格式问题），
        // 不必再花一次文件头探测。反过来排会让"不支持的类型"报成"疑似伪装"，指向错误的下一步动作。
        ingestGuard.check(file);
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
