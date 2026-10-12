package com.knowledge.ai.core.ai;

import com.knowledge.ai.core.enums.ModelType;
import com.knowledge.ai.core.security.ModelKeyCipher;
import com.knowledge.ai.entity.SysModelConfig;
import com.knowledge.ai.repository.SysModelConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * {@link ModelConfigSource} 的 **DB 实现**（`T-900`~`T-903`）—— 模型口径的真正来源。
 *
 * <h3>它一上线，整个系统的模型调用就换了源头</h3>
 * <p>{@code S0`~`S5` 期间模型口径只有一处：{@code app.model.*}（← `.env`），由
 * {@link BootstrapModelConfigSource} 读。本类标了 {@link Primary} ⇒ Spring 注入
 * {@code ModelConfigSource} 时**优先选它** —— 这一步把 DB 变成"有哪些模型、用哪一条"的真相源，
 * 而 `.env` 退化成**密钥来源**（{@link #apiKeyOf}）。
 *
 * <p>⚠️ 因此它与 {@code ModelConfigSeeder} **必须同批合入**：
 * 表空 ⇒ 本类返回空列表 ⇒ {@code ModelClientRegistry#activeSpec} 抛
 * {@code MODEL_NOT_CONFIGURED} ⇒ **问答 / 检索 / 解析全线不可用**。
 * 种子负责保证"表不空"，本类负责"表空时如实报错、不假装有配置"。
 *
 * <h3>为什么用 {@code @Primary} 而不是 {@code @ConditionalOnMissingBean}</h3>
 * <p>{@link ModelConfigSource} 的 javadoc 曾写「用 {@code @ConditionalOnMissingBean} 排序」——
 * 那是**错的**，{@link BootstrapModelConfigSource} 的类注释记了实测结论：
 * 该注解只适用于自动配置类或 {@code @Bean} 方法，放在被组件扫描的 {@code @Component} 上
 * **会被提前求值而把 Bean 整个跳过**（启动报 {@code No qualifying bean of type 'ModelConfigSource'}）。
 * 本批顺手订正了那句 javadoc，此处按实测结论用 {@code @Primary}。
 *
 * <h3>密钥从哪来（2026-10-11 起变了）</h3>
 * <p>`S6-M09-A` 期间 {@code api_key_cipher} **恒为 {@code null}**，密钥一律按**类型**从
 * {@code ModelProperties}（← `.env`）取。代价记在该计划 §9-9：新增一条指向别家供应商的模型时
 * 密钥**无处可取**，只能借用同类型在 `.env` 的那一把。
 *
 * <p>现在密钥**随行落库**（配置页录入 → {@link com.knowledge.ai.core.security.ModelKeyCipher}
 * 加密成 {@code api_key_cipher}），取用时解密。优先级由 {@link #plaintextKeyOf} **一处**决定：
 * <pre>
 *   api_key_cipher 非空 且 能解密  → 用解密后的明文（在别家供应商的模型从此有救）
 *   其余一切情况                  → 回退 .env 的同名配置（= V1 列注释写的「NULL = 用环境变量同名配置」）
 * </pre>
 * ⚠️ **这一处必须是唯一入口**：掩码（配置页显示）与实际调用如果各走一条路，
 * 一旦密文解不开就会出现「页面显示的是一把 key、实际发出去的是另一把」——
 * 与「页面显示已切换、实际仍打旧端点」同族的**看不见的失效**。
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class DbModelConfigSource implements ModelConfigSource {

    private final SysModelConfigRepository repository;

    private final ModelProperties modelProperties;

    private final ModelKeyCipher modelKeyCipher;

    /**
     * 某类型**当前启用**的那一条。
     *
     * <p>返回列表只是为了让 {@code active()} 的默认实现（取首条）成立 ——
     * 同类型至多一条启用，由库层的 {@code uk_model_active} 唯一索引保证，
     * 所以这里最多一条。**不启用即不可用**（这与"配了就能用"是两件事，
     * 启用还要求连接测试通过，见 `G6-3`）。
     */
    @Override
    public List<ModelConfigSpec> listEnabled(ModelType type) {
        List<SysModelConfig> active = repository.findByModelTypeAndIsActiveTrue(type);
        if (active.isEmpty()) {
            // 如实返回空列表，**不兜底到引导配置** —— 见类注释：兜底会造成
            // "配置页列出的是 DB、实际生效的是 yml" 的双真相源，用户改不动正在用的东西。
            // 调用方（ModelClientRegistry）对"正常返回空"的处理是**显式报错**，这是有意的。
            return List.of();
        }
        if (active.size() > 1) {
            // 唯一索引在库层挡着，走到这里说明索引被绕过（如手工 SQL）。
            // 不抛：让检索继续用第一条，但必须留痕 —— 双启用是"归因不可能"的状态
            log.error("模型配置出现同类型双启用（唯一索引应已挡住）：type={} ids={} —— 取第一条，请人工核对",
                    type, active.stream().map(SysModelConfig::getId).toList());
        }
        return List.of(toSpec(active.getFirst()));
    }

    /**
     * **行 → spec**（含密钥解析，见 {@link #plaintextKeyOf}）。
     *
     * <p>公开是因为它还有第二个调用方：`T-903` 的连接测试要能对**未启用**的行
     * 也造一个客户端（"先测通过才能启用"——测的时候它还没启用）。
     * 让 Service 自己再写一遍映射，就会出现"测试用一份映射、上线用另一份"的漂移，
     * 而那种漂移的表现是**测通了却调不通**。
     */
    public ModelConfigSpec specOf(SysModelConfig row) {
        return toSpec(row);
    }

    /**
     * 一条配置**实际生效**的密钥明文 —— 全系统唯一的密钥解析点。
     *
     * <p>两个调用方：{@link #toSpec}（喂给客户端构造 ⇒ 真正发出去的那把）与
     * `ModelConfigService#toItem`（算配置页上的掩码）。两者共用本方法，掩码才不会撒谎。
     *
     * <p>回退链见类注释。解密失败**不抛**、只记 ERROR：宁可让这条路继续用 `.env` 的旧密钥活着，
     * 也不要因为一行密文读不出来就让整条问答链路报错 —— 但**绝不把密文当明文交出去**。
     */
    public String plaintextKeyOf(SysModelConfig row) {
        String cipherText = row.getApiKeyCipher();
        if (cipherText != null && !cipherText.isBlank()) {
            Optional<String> plain = modelKeyCipher.decrypt(cipherText);
            if (plain.isPresent() && !plain.get().isBlank()) {
                return plain.get();
            }
            // ⚠️ 只报 id / type，不带密文本身（日志也是泄漏面）
            log.error("模型配置 id={} 的 api_key_cipher 解密失败（主密钥可能已轮换），"
                    + "本条回退到 .env 的同类密钥：type={}", row.getId(), row.getModelType());
        }
        return apiKeyOf(row.getModelType());
    }

    @Override
    public String sourceName() {
        return "db(sys_model_config)";
    }

    // ==================================================================
    // 行 → spec
    // ==================================================================

    private ModelConfigSpec toSpec(SysModelConfig row) {
        return new ModelConfigSpec(
                // ⚠️ 这里是**数字字符串**（自增主键），而不是引导配置的 `bootstrap-chat`。
                // 下游 `RegistryStreamingChatCaller#numericId` 因此能解析出数字 ⇒
                // `sys_chat_message.model_id`（`T-808` 的 used_model_id）**从此恒非空** ——
                // 那正是 `#138`/`#139`/`#161` 挂了很久的那条账。
                String.valueOf(row.getId()),
                row.getModelType(),
                row.getBaseUrl(),
                plaintextKeyOf(row),
                row.getModelName(),
                // `version` 是**热更新的命门**：注册中心的缓存 key 是 `type|id|version`，
                // 版本不随配置变 ⇒ 缓存 key 不变 ⇒ 页面上显示"已切换"而实际仍打旧端点。
                // 用 `updated_at` 的毫秒值：任何一次编辑都会换版本（多失效几次无害）。
                versionOf(row),
                Boolean.TRUE.equals(row.getIsActive()),
                ModelConfigJson.readParams(row.getParams()));
    }

    /** 版本号：`db-{updatedAt 毫秒}`；`updated_at` 缺失时退回 `created_at`。 */
    private static String versionOf(SysModelConfig row) {
        LocalDateTime stamp = row.getUpdatedAt() != null ? row.getUpdatedAt() : row.getCreatedAt();
        long millis = stamp == null ? 0L : stamp.toInstant(ZoneOffset.UTC).toEpochMilli();
        return "db-" + millis;
    }

    /**
     * 按**类型**取环境变量里的密钥 —— {@link #plaintextKeyOf} 的**回退分支**。
     *
     * <p>什么时候走到这里：① 该行的 {@code api_key_cipher} 为空（种子行、或管理员没在页面上填过）；
     * ② 密文存在但解不开（主密钥轮换 / 密文被改）。
     *
     * <p>⚠️ 与 {@code BootstrapModelConfigSource} 有一处**刻意的不同**：
     * 那边"端点不全就不给出这条配置"，这边**不跳过**。
     * 理由：DB 里的这条是管理员**显式启用**过的、且启用前必须连接测试通过（`G6-3`）——
     * 它已经证明过自己能通。此时若因为 `.env` 里少了 key 就把它变成
     * "该类无可用模型"，等于在管理员的决定之上再叠一层**看不见**的否决。
     * 所以这里只留 warn，让调用方按真实错误（鉴权失败）去暴露问题。
     */
    private String apiKeyOf(ModelType type) {
        ModelProperties.Endpoint endpoint = modelProperties.endpointOf(type);
        String apiKey = endpoint == null ? null : endpoint.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("模型配置：{} 类当前启用的那条没有可用密钥（.env 的对应 key 为空）——"
                    + "调用会在网关侧失败；若是本地部署模型可忽略此条", type.getLabel());
        }
        return apiKey;
    }
}
