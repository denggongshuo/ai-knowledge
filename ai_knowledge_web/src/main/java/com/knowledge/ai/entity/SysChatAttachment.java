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
 * <h3>{@link #md5} 从 `T-907` 起真的被读了</h3>
 * <p>`S5-M08-C` 落它时它是"只写不读"——为了**别让缓存 key 的那一半事后补不回来**
 * （与 `V23` 提前建消息表同一种形状）。`T-907` 把 VL 描述缓存接上之后，
 * 它就是 key 的第一段（{@code md5 : vlModelId : PROMPT_VERSION}，需求 `09` §4.8）。
 * ⚠️ 由此可反推一件必须成立的事：**它必须是服务端按字节算的真值** ——
 * 前端此前那个 {@code pseudoMd5}（读文件名与大小拼的）会让两张不同的图撞进同一个缓存项。
 *
 * <h3>描述五件套的三态语义（`V27` 新增）</h3>
 * <p>{@link #description} 为 {@code null} = **还没理解过**；非空 = 理解过。
 * 而 {@link #degraded} 是那个二者之间的第三态：{@code null} / {@code false} / {@code true}
 * 分别是「没有描述」「有描述且没降级」「有描述但降级产出」。三态压成两态，
 * 用户就分不出「模型没看出东西」与「模型根本没跑起来」—— 而这两者的下一步动作相反。
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

    /**
     * **服务端**按字节算的 md5（`S5-M08-C` 起；此前前端发的是伪值）。
     *
     * <p>它是 VL 描述缓存 key 的第一段（{@code md5 : vlModelId : PROMPT_VERSION}，
     * 需求 `09` §4.8）。`T-907` 起真的被读了 —— 伪值会让**两张不同的图撞进同一个缓存项**。
     */
    @Column(name = "md5", length = 32, nullable = false)
    private String md5;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // ==================================================================
    // VL 描述五件套 + prompt 版本（`T-907` · `V27`）
    // ==================================================================

    /**
     * VL 产出的完整结构化描述（**给生成用**，需求 `09` §4.6）。
     *
     * <p>⚠️ 列名是 {@code description} 而不是 {@code desc} —— 后者是 MySQL 保留字。
     * 对外契约（`ChatDtos.AttachmentView#desc`）仍叫 `desc`：契约名与列名本来就不必相同
     * （先例：接口字段 {@code blockType} ↔ 列名 {@code chunk_type}）。
     *
     * <p>{@code null} = **还没有被理解过**，不是"理解结果为空"。
     */
    @Column(name = "description", length = 8000)
    private String description;

    /**
     * 从描述**最后一行**切出的检索关键词（**给检索用**，需求 §4.4）。
     *
     * <p>与 {@link #description} 是**一次调用同时产出的两份**：前者信息完整、后者关键词密集，
     * 分别喂生成与检索，所以要求本就不同。解析不出关键词行时回落成整段描述（§4.10 第 4 行）。
     */
    @Column(name = "search_summary", length = 1000)
    private String searchSummary;

    /** 产出该描述的 VL 模型配置 id。模型被删则保持原值 —— 描述是既成事实，不随配置消失。 */
    @Column(name = "vl_model_id")
    private Long vlModelId;

    /** VL 模型名（冗余存）。理由同 {@code SysChatMessage#modelName}：模型删了还读得出是谁产的。 */
    @Column(name = "vl_model_name", length = 128)
    private String vlModelName;

    /**
     * 是否**降级**产出（需求 §4.10 第 3 行）。
     *
     * <p>⚠️ 用 {@link Boolean} 而不是 {@code boolean}：`null` = 还没有描述、
     * {@code false} = 有描述且没降级、{@code true} = 有描述但是降级产出的。
     * 三态，用基本类型必然要把前两者压成一个 {@code false}。
     */
    @Column(name = "degraded")
    private Boolean degraded;

    /** 生成时的 {@code PROMPT_VERSION}（缓存 key 第三段，§4.8）。 */
    @Column(name = "prompt_version", length = 16)
    private String promptVersion;
}
