package com.knowledge.ai.entity;

import com.knowledge.ai.core.enums.BillingUnit;
import com.knowledge.ai.core.enums.ModelType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型配置（表 {@code sys_model_config}，`T-900`~`T-903`）。
 *
 * <h3>这张表是「`.env` 唯一来源」时代的终结</h3>
 * <p>{@code S0`~`S5` 期间模型口径只有一处：{@code application.yml} 的 {@code app.model.*}
 * （← {@code .env}），由 {@code BootstrapModelConfigSource} 读。接上 DB 之后，
 * <b>DB 是"有哪些模型、用哪一条、参数与能力是什么"的真相源</b>，
 * 而 {@code .env} 退化成**密钥的兜底来源**（见 {@link #apiKeyCipher}）。
 *
 * <p>⚠️ 这一条不是"锦上添花"：{@code ModelConfigSource} 换成 {@code @Primary} 的 DB 实现后，
 * 表空就等于**没有任何可用模型** ⇒ {@code ModelClientRegistry#activeSpec} 抛
 * {@code MODEL_NOT_CONFIGURED} ⇒ 问答 / 检索 / 解析全线不可用。
 * 所以 {@code ModelConfigSeeder} 与 DB 来源**必须同批合入**。
 *
 * <h3>{@link #apiKeyCipher}：2026-10-11 起真的会被写上东西</h3>
 * <p>它存的是**加密后的 Key**（AES-256-GCM，见 {@code core/security/ModelKeyCipher}），
 * 由配置页录入、服务端加密后落库；取用时在
 * {@code DbModelConfigSource#plaintextKeyOf} 一处解密 —— 那是**唯一**的密钥解析点。
 *
 * <p>仍然保留 {@code V1} 列注释定义的兜底语义：{@code NULL} = 用环境变量同名配置。
 * 于是有两类行天然走 `.env`：启动期种子落的四行（{@code ModelConfigSeeder} 写 `null`），
 * 以及密文解不开的行（主密钥轮换 / 被人工改动）—— 后者会记 ERROR 并如实降级，
 * **绝不把密文当明文交出去**。
 *
 * <p>页面上的 Key 是**只读掩码**，掩码同样由 `plaintextKeyOf` 现算
 * （刻意不落一列掩码快照：换了 key 而掩码没变就是撒谎；而"掩码与实际调用各走一条路"
 * 会出现"页面显示的是一把 key、真正发出去的是另一把"）。
 *
 * <h3>两个 JSON 列刻意是 {@code String}</h3>
 * <p>{@link #params} 与 {@link #capabilities} 存 JSON **串**，序列化 / 反序列化归 Service
 * —— 与 {@code SysKb} 的三张 {@code *_policy_json} 同一条取舍：挂
 * {@code AttributeConverter} 声明成对象，失败时会抛在 Hibernate 内部，
 * 错误信息指向 SQL 而不是业务字段。读侧的兜底规则见 {@code ModelConfigDefaults}。
 *
 * <h3>为什么没有 {@code active_key} 字段</h3>
 * <p>那一列是 {@code V26} 为**唯一索引**加的生成列（`IF(is_active, model_type, NULL)`），
 * 由数据库自己算。映射它既没有读侧需求，写侧也不该由应用来碰。
 * {@code ddl-auto: validate} 只校验"实体声明的列在表里存在"，不要求反过来。
 */
@Data
@Entity
@Table(name = "sys_model_config")
public class SysModelConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 四类之一。**创建后不可修改**（《09》§1.5：改类型等于换一个模型，该另建一条）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "model_type", length = 16, nullable = false)
    private ModelType modelType;

    /** 展示名称，如「DeepSeek-V3（硅基流动）」。前端切换器展示它。 */
    @Column(name = "display_name", length = 64, nullable = false)
    private String displayName;

    /**
     * 接口协议，如「OpenAI 兼容」；决定请求体格式。
     *
     * <p>⚠️ 字段初值**不是可选的装饰**：`protocol` / {@link #currency} / {@link #isActive} /
     * {@link #sortOrder} / {@link #lastTestOk} 在库里都是 `NOT NULL`，而**Hibernate 的 INSERT
     * 会把实体上为 null 的列显式写成 NULL** —— 于是 DDL 里的 `DEFAULT` **不会生效**，
     * 报的是 `Column 'xxx' cannot be null`。本批实测踩到过一次（种子漏设 `currency`，
     * 整个应用上下文起不来）。给初值是从结构上堵死这一类。
     */
    @Column(name = "protocol", length = 32, nullable = false)
    private String protocol = "OpenAI 兼容";

    /** OpenAI 兼容接口根地址。⚠️ **要写全**（可能含 `/v1`）—— 客户端不做 `/v1` 补全。 */
    @Column(name = "base_url", length = 255, nullable = false)
    private String baseUrl;

    /**
     * 加密后的 API Key（AES-256-GCM，{@code "v1:" + Base64(IV‖密文‖tag)}）。
     *
     * <p>{@code null} / 空 = **用环境变量同名配置**（`V1` 列注释定义的语义；种子行恒为 null）；
     * 解不开时也走这条回退，并记 ERROR。写入与解密都只在
     * {@code ModelConfigService} 与 {@code DbModelConfigSource#plaintextKeyOf} 两处，
     * 后者是全系统**唯一**的解析点。
     *
     * @see SysModelConfig 类注释「apiKeyCipher：2026-10-11 起真的会被写上东西」
     */
    @Column(name = "api_key_cipher", length = 512)
    private String apiKeyCipher;

    /** 模型标识，如 {@code BAAI/bge-m3}。 */
    @Column(name = "model_name", length = 128, nullable = false)
    private String modelName;

    /** 向量维度（仅 EMBEDDING，只读展示 1024）。 */
    @Column(name = "dimension")
    private Integer dimension;

    /** 归一化方式（仅 EMBEDDING）。 */
    @Column(name = "norm", length = 16)
    private String norm;

    /** 批量大小（仅 EMBEDDING）。 */
    @Column(name = "batch_size")
    private Integer batchSize;

    /** 相似度度量（仅 EMBEDDING）：{@code COSINE} / {@code DOT} / {@code EUCLIDEAN}。 */
    @Column(name = "metric", length = 16)
    private String metric;

    /** 生成参数 JSON 串（{@code {temperature,maxTokens,timeoutSec,retries}}）；{@code null} 时读侧给默认值。 */
    @Column(name = "params", columnDefinition = "TEXT")
    private String params;

    /** 能力标记 JSON 串（{@code {stream,vision,contextWindow,supportSystemRole}}）。 */
    @Column(name = "capabilities", columnDefinition = "TEXT")
    private String capabilities;

    /** 输入单价（元/百万 tokens）。{@code null} = **未配置**（费用未知），与 {@code 0}/{@code NONE} 语义不同。 */
    @Column(name = "input_price")
    private BigDecimal inputPrice;

    /** 输出单价（元/百万 tokens）；语义同 {@link #inputPrice}。 */
    @Column(name = "output_price")
    private BigDecimal outputPrice;

    /** 按次单价（元/次）；语义同 {@link #inputPrice}。 */
    @Column(name = "perCallPrice")
    private BigDecimal perCallPrice;

    /** 币种。见 {@link #protocol} 的注释：这类 `NOT NULL` 列必须给实体初值。 */
    @Column(name = "currency", length = 8, nullable = false)
    private String currency = "CNY";

    /** 计费方式：按 token / 按次 / 本地不计费。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "billing_unit", length = 24)
    private BillingUnit billingUnit;

    /**
     * 是否当前启用。
     *
     * <p>⚠️ 与 {@link #lastTestOk} 是两个独立事实，**不可互相派生**：
     * 启用门槛要求"先测试通过"（服务层保证），但测试通过**不等于**已启用。
     *
     * <p>同类型至多一条为真 —— 由 {@code uk_model_active}（生成列 `active_key`）在库层保证。
     */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = false;

    /** 列表内排序（越小越前）。见 {@link #protocol} 的注释。 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    /** 最近一次连接测试时间。{@code null} = **从未测试过**（与 {@link #lastTestOk} 为假是两件事）。 */
    @Column(name = "last_test_at")
    private LocalDateTime lastTestAt;

    /** 最近一次连接测试是否通过；启用门槛读它（`G6-3`）。见 {@link #protocol} 的注释。 */
    @Column(name = "last_test_ok", nullable = false)
    private Boolean lastTestOk = false;

    /** 最近一次测试的首字延迟（毫秒）。 */
    @Column(name = "last_latency_ms")
    private Integer lastLatencyMs;

    /**
     * 最近一次被启用 / 切换的时间（`W11` 的 {@code vlModel.appliedAt} 读它）。
     *
     * <p>⚠️ **刻意不复用 {@link #updatedAt}**：改个备注也会让它变，显示出来的"生效时间"就是假的。
     */
    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    /** 创建人（{@code sys_user.id}）；可空 —— 种子落下的四行没有创建人。 */
    @Column(name = "created_by")
    private Long createdBy;

    /** 备注。 */
    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
