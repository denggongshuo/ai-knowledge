package com.knowledge.ai.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问答图片附件（表 {@code sys_chat_attachment}，`T-805`）。
 *
 * <h3>为什么归属记在 {@link #userId} 上，而不是会话上</h3>
 * <p>需求 `09` FR-SYS-04 的链路是「**预上传 → 发送时才由 VL 生成描述**」：
 * 图片在用户点发送**之前**就上传完了，而此刻**会话还不存在**
 * （会话是 `completions` 里 {@code ChatSessionService#resolve} 才建的）。
 * 所以这张表必须有一个不依赖会话的归属 —— 那就是上传人。
 *
 * <p>需求 `09:661` 写「回看需校验**会话**归属」，那条口径要等附件与会话真正绑定
 * （`T-806`/`T-512`）之后才谈得上，本类**不预留**绑定的空列。
 *
 * <h3>为什么只存 {@link #objectKey} 不存 URL</h3>
 * <p>与 {@code DocStorage} 类注释同一条纪律：**存事实，不存视图**。预签名 URL 有 TTL，
 * 落库就等于落一个必然过期的字符串；每次回看时重新签发，域名换了也不用动数据。
 *
 * <h3>{@link #md5} 目前只写不读</h3>
 * <p>它是 VL 描述缓存 key 的前半段（`md5 : vlModelId : PROMPT_VERSION`，需求 `09` §4.8），
 * 而描述生成属 `T-512`（S6）。现在落它，是为了**别让缓存 key 的那一半事后补不回来**
 * ——与 `V23` 提前建消息表是同一种形状。
 */
@Data
@Entity
@Table(
        name = "sys_chat_attachment",
        indexes = {
                @Index(name = "idx_ca_user", columnList = "user_id, id"),
        }
)
public class SysChatAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 上传人。预上传时会话还不存在，归属只能记在这里 —— 回看时按它校验。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** MinIO 对象名（{@code chat-attachments/{userId}/{uuid}.{ext}}）。预签名每次读取时按它现签。 */
    @Column(name = "object_key", length = 512, nullable = false)
    private String objectKey;

    /** 原始文件名。 */
    @Column(name = "name", length = 255, nullable = false)
    private String name;

    /** 字节数。与 MinIO 对象大小对照可发现落盘截断。 */
    @Column(name = "size", nullable = false)
    private Long size;

    /** 客户端声明的 MIME；白名单校验在服务层（只收 {@code image/*}）。 */
    @Column(name = "mime", length = 128)
    private String mime;

    /** 客户端算的 md5。⚠️ 目前**只写不读** —— 消费方（VL 描述缓存）属 `T-512`。 */
    @Column(name = "md5", length = 32, nullable = false)
    private String md5;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
