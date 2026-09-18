package com.knowledge.ai.pipeline.vision;

import com.knowledge.ai.core.ai.ModelClientRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.net.URI;
import java.util.Optional;

/**
 * {@link VisionCaller} 的生产实现 —— **唯一一处真的碰 VL 模型客户端**。
 *
 * <h3>两条传输通路，以及本部署下真正会走哪一条</h3>
 * <p>需求 `09` §4.3 的「工程要点」表写的是：**先落 MinIO，传 10 分钟预签名 URL**
 * （请求体从十几 MB 降到几百字节）；**若 VL 服务取不到内网 URL 则回退 base64**。
 * 两条本类都实现了，顺序也照需求：先用 URL，失败再用字节。
 *
 * <p>🔴 **但本仓的实际部署下，URL 通路几乎必然失败** —— 预签名 URL 指向的是
 * {@code 127.0.0.1:9000} 的 MinIO（`app.minio` / `minio.bucket` 的本机配置），
 * 而 VL 供应商是**公网服务**（硅基流动 / 阿里云）。公网服务取不到本机地址，
 * 于是第一次调用必然超时或 4xx，再回退 base64 —— **每次描述都白等一跳**。
 *
 * <p>这是**发现，不是猜测**：它由"MinIO 端点与 VL 端点分属内网/公网"这一条
 * 拓扑事实直接推出。本类先把两条都实现（需求怎么写就怎么做），
 * 并把这一条登记进计划 §9 —— 真要在生产上省这一跳，得让 MinIO 有一个
 * VL 可达的地址（内网穿透 / 对象存储换公网端点 / 直传 OSS），那是**部署面**的改动，
 * 不在本批范围内。⚠️ 在那之前，每次图片理解都多一次注定失败的请求。
 *
 * <h3>压缩只作用于 base64 通路</h3>
 * <p>走 URL 时，VL 服务取到的是**桶里的原图** —— 我们本地压的那份根本没参与传输。
 * 需求 §4.3 把"传 URL"与"描述前压缩"并列列为两条工程要点，但两者在 URL 通路上
 * **从原理上无法同时成立**（URL 指向的对象不是压缩后的那份）。
 * 本批按需求原文实现两条，把这一处张力如实登记（计划 §9），
 * 不假装压缩在 URL 通路上生效了。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegistryVisionCaller implements VisionCaller {

    private final ModelClientRegistry registry;

    @Override
    public VisionRaw call(String prompt, byte[] image, String presignedUrl, String mime) {
        Optional<ChatClient> client = registry.visionClient();
        if (client.isEmpty()) {
            // 未配置 VL：抛出去让 VisionDescriber 转成降级（§4.10 第 3 行）。
            // 不在这里就地降级 —— "降级长什么样"是描述器的口径，不是调用方的
            throw new IllegalStateException("未配置可用的 VL（视觉）模型");
        }

        if (presignedUrl != null && !presignedUrl.isBlank()) {
            try {
                // `PromptUserSpec#media(MimeType, URL)` 收的是 java.net.URL 而不是 URI ——
                // 别用 `new URL(...)`（JDK 20 起标记废弃），走 URI 转
                return invokeUrl(client.get(), prompt, MimeTypeUtils.parseMimeType(
                        mime == null || mime.isBlank() ? "image/jpeg" : mime),
                        URI.create(presignedUrl).toURL());
            } catch (RuntimeException | java.net.MalformedURLException ex) {
                // ⚠️ 这条 warn 在本部署下**每次都会出现**（见类注释）——
                // 它是"URL 通路不通"的可见信号，不是偶发噪音
                log.warn("[图片理解] 预签名 URL 通路失败，回退 base64：原因={}", ex.getMessage());
            }
        }
        if (image == null || image.length == 0) {
            // 两条通路都没有：这是调用方的问题（既没给字节也没给 URL），
            // 但对外仍走降级 —— 用户不该因为一次图片理解失败而拿不到答案
            throw new IllegalStateException("既没有图片字节也没有预签名 URL，无法调用 VL");
        }
        ByteArrayResource resource = namedResource(image);
        return invokeBytes(client.get(), prompt, MimeTypeUtils.IMAGE_JPEG, resource);
    }

    @Override
    public VisionModelRef modelRef() {
        return registry.visionSpec()
                .map(spec -> new VisionModelRef(spec.id(), spec.model()))
                .orElse(VisionModelRef.NONE);
    }

    /** URL 通路（`PromptUserSpec#media(MimeType, URL)`）。 */
    private static VisionRaw invokeUrl(ChatClient client, String prompt,
                                       org.springframework.util.MimeType mime, java.net.URL url) {
        return toRaw(client.prompt()
                .user(spec -> spec.text(prompt).media(mime, url))
                .call()
                .chatResponse());
    }

    /** 字节通路（`PromptUserSpec#media(MimeType, Resource)`）。 */
    private static VisionRaw invokeBytes(ChatClient client, String prompt,
                                         org.springframework.util.MimeType mime, ByteArrayResource resource) {
        return toRaw(client.prompt()
                .user(spec -> spec.text(prompt).media(mime, resource))
                .call()
                .chatResponse());
    }

    /**
     * {@link ChatResponse} → {@link VisionRaw}。
     *
     * <p>⚠️ 用 {@code .chatResponse()} 而不是 {@code .content()}：后者只给文本，
     * 而用量（token）**必须在这里带出来** —— 上层再想补也补不回来（理由见
     * {@link VisionCaller.VisionRaw} 的注释）。与 {@code RegistryStreamingChatCaller}
     * 用 {@code .stream()} 而非 {@code .content()} 是同一条理由。
     */
    private static VisionRaw toRaw(ChatResponse response) {
        if (response == null || response.getResult() == null
                || response.getResult().getOutput() == null) {
            // 返回体结构不对 → 交给调用方按"返回空"走降级（§4.10 第 3 行）
            return VisionRaw.of("");
        }
        String text = response.getResult().getOutput().getText();
        return new VisionRaw(text == null ? "" : text,
                promptTokens(response), completionTokens(response));
    }

    private static Integer promptTokens(ChatResponse response) {
        Usage usage = usageOf(response);
        return usage == null ? null : toInt(usage.getPromptTokens());
    }

    private static Integer completionTokens(ChatResponse response) {
        Usage usage = usageOf(response);
        return usage == null ? null : toInt(usage.getCompletionTokens());
    }

    private static Usage usageOf(ChatResponse response) {
        return response.getMetadata() == null ? null : response.getMetadata().getUsage();
    }

    /** {@code Integer} / {@code Long} → {@code Integer}；取不到就是 {@code null}（**不填 0**）。 */
    private static Integer toInt(Number value) {
        return value == null ? null : value.intValue();
    }

    /**
     * 给字节流一个文件名。
     *
     * <p>为什么不能直接用裸 {@code ByteArrayResource}：它的 {@code getFilename()} 返回
     * {@code null}，而下游（Spring AI 的 OpenAI 适配）在把 Resource 转成 base64
     * data-URI 时要读文件名来判类型 —— 某些版本上会抛。
     * 与 {@code DocStorage} 里"给对象名一个安全的扩展名"是同一条理由：
     * **传输层要一个名字，就给它一个确定的名字**。
     */
    private static ByteArrayResource namedResource(byte[] image) {
        return new ByteArrayResource(image) {
            @Override
            public String getFilename() {
                return "image.jpg";
            }
        };
    }
}
